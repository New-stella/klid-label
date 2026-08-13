#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# gen-schema-sql.sh — [빌드머신] Flyway 전체 마이그레이션 → 단일 schema.sql 생성
#
#   backend/src/main/resources/db/migration 의 V0~Vn 을 클린 PostgreSQL 컨테이너에
#   Flyway 로 적용한 뒤 pg_dump 로 추출한다. 결과는 deploy/onprem/db/schema.sql.
#
#   ★ 왜 덤프인가: 손으로 76개 마이그레이션의 누적 ALTER 를 펼치면 validate 와
#     어긋나기 쉽다. Flyway 가 실제 적용한 스키마를 그대로 덤프하면 100% 일치한다.
#
#   ★ 대상 스키마는 앱과 같은 축(${DB_SCHEMA:-klid_at})이다.
#     앱은 커넥션 currentSchema / Flyway schemas·default-schema / Quartz tablePrefix /
#     JPA hibernate.default_schema 네 지점이 모두 이 값을 읽는다. 여기서 다른 값을 쓰면
#     "설치는 됐는데 앱이 빈 스키마를 본다"가 되므로 <같은 환경변수 이름>을 쓴다.
#     Flyway CLI 에도 -schemas 로 넘겨야 한다 — 안 넘기면 CLI 기본값 public 에 적용된다.
#
#   요구: docker(데몬 기동) + pg_dump(호스트). 폐쇄망이 아닌 빌드머신에서 실행.
#   마이그레이션 추가/변경 시 반드시 재실행하여 schema.sql 을 갱신할 것.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

require_cmd docker
require_cmd pg_dump
require_cmd psql

REPO="$(repo_root)"
MIG_DIR="${REPO}/backend/src/main/resources/db/migration"
OUT="${REPO}/deploy/onprem/db/schema.sql"
PORTAL_MIG_DIR="${REPO}/backend/src/main/resources/db/portal"
PORTAL_OUT="${REPO}/deploy/onprem/db/portal-schema.sql"
PORTAL_DBNAME="portal"

CTR="klid-schema-gen-$$"
PORT="${SCHEMA_GEN_PORT:-55432}"
PGVER="${SCHEMA_GEN_PG_IMAGE:-postgres:16-alpine}"
FWVER="${SCHEMA_GEN_FLYWAY_IMAGE:-flyway/flyway:10-alpine}"
DBNAME="klid_system"
DBUSER="klid_user"
DBPW="genpw"
# 앱과 같은 축의 환경변수 이름(두 번째 진실원 금지). 기본값도 앱과 동일하게 klid_at.
DB_SCHEMA="${DB_SCHEMA:-klid_at}"

[[ -d "${MIG_DIR}" ]] || die "마이그레이션 디렉토리 없음: ${MIG_DIR}"

cleanup() { docker rm -f "${CTR}" >/dev/null 2>&1 || true; }
trap cleanup EXIT

info "[schema] 클린 PostgreSQL 기동 (${PGVER}, port ${PORT})"
docker run -d --name "${CTR}" \
  -e POSTGRES_USER="${DBUSER}" -e POSTGRES_PASSWORD="${DBPW}" -e POSTGRES_DB="${DBNAME}" \
  -p "${PORT}:5432" "${PGVER}" >/dev/null

info "[schema] DB ready 대기"
for _ in $(seq 1 30); do
  docker exec "${CTR}" pg_isready -U "${DBUSER}" -d "${DBNAME}" >/dev/null 2>&1 && break
  sleep 1
done
docker exec "${CTR}" pg_isready -U "${DBUSER}" -d "${DBNAME}" >/dev/null 2>&1 \
  || die "PostgreSQL 기동 실패"

info "[schema] Flyway migrate (전체 마이그레이션 적용, schema=${DB_SCHEMA})"
docker run --rm -v "${MIG_DIR}:/flyway/sql:ro" "${FWVER}" \
  -url="jdbc:postgresql://host.docker.internal:${PORT}/${DBNAME}" \
  -user="${DBUSER}" -password="${DBPW}" \
  -locations=filesystem:/flyway/sql \
  -schemas="${DB_SCHEMA}" -defaultSchema="${DB_SCHEMA}" \
  -createSchemas=true \
  -connectRetries=10 \
  migrate

# 유출 가드: 마이그레이션이 대상 스키마 밖(public)에 객체를 만들면 -n 덤프에서 조용히 빠진다.
#   → 설치 후 ddl-auto=validate 가 "없는 테이블"로 실패한다. 여기서 먼저 크게 실패시킨다.
leaked="$(PGPASSWORD="${DBPW}" psql -tAX -h 127.0.0.1 -p "${PORT}" -U "${DBUSER}" -d "${DBNAME}" \
  -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relkind IN ('r','v','m','S');")"
[[ "${leaked}" == "0" ]] \
  || die "[schema] public 에 객체 ${leaked}개 유출 — 대상 스키마(${DB_SCHEMA}) 밖이라 덤프에 담기지 않습니다."

info "[schema] pg_dump → ${OUT} (schema=${DB_SCHEMA})"
ensure_dir "$(dirname "${OUT}")"
{
  cat <<HDR
-- ============================================================================
-- schema.sql — 저작도구 전체 스키마 단일 생성본 (Flyway V0~Vn 통합 스냅샷)
--
-- ★ 생성물(수기 편집 금지). gen-schema-sql.sh 가 Flyway 로 적용한 스키마를 덤프한다.
--   → Flyway 가 실제 적용/검증한 스키마와 100% 동일하므로 ddl-auto=validate 통과 보장.
--
-- ★ 대상 스키마 = ${DB_SCHEMA} (앱의 \${DB_SCHEMA:klid_at} 와 같은 축).
--   덤프는 CREATE SCHEMA 를 포함하고 모든 객체가 스키마 한정이므로, 로더의 search_path 와
--   무관하게 항상 이 스키마에 만들어진다.
--
-- 용도(온프렘/이중화): Flyway 를 부팅 경로에서 제외(SPRING_FLYWAY_ENABLED=false)하고,
--   설치 시 이 파일을 빈 DB 에 1회 로드. 두 노드 모두 검증만 → advisory lock 경합 없음.
--
-- flyway_schema_history 제외(Flyway 미사용). 시드(system_config/cm_code/qrtz_locks) 포함.
-- 재생성: deploy/onprem/scripts/gen-schema-sql.sh (빌드머신, docker 필요)
-- ============================================================================

HDR
  PGPASSWORD="${DBPW}" pg_dump -h 127.0.0.1 -p "${PORT}" -U "${DBUSER}" -d "${DBNAME}" \
    --no-owner --no-privileges --no-comments \
    -n "${DB_SCHEMA}" \
    -T "${DB_SCHEMA}.flyway_schema_history"
} > "${OUT}"

grep -q "^CREATE SCHEMA ${DB_SCHEMA};" "${OUT}" \
  || die "[schema] 덤프에 CREATE SCHEMA ${DB_SCHEMA} 가 없습니다 — 로더가 빈 DB 에 먹이면 실패합니다."

TBLS="$(grep -c "^CREATE TABLE ${DB_SCHEMA}\." "${OUT}" || true)"
ok "[schema] 생성 완료: ${OUT} (${DB_SCHEMA} CREATE TABLE ${TBLS}개, $(wc -l < "${OUT}") 라인)"

# ============================================================================
# 포털 복제본 스키마 — control 과 물리 분리된 포털 DB(PORTAL_DB_*)용.
#
#   ★ 이 산출물이 없으면 포털 DB 가 빈 상태로 운영에 들어가고, 메타 복제 워커가 매 tick
#     graceful skip 만 하며 포털 복제본이 <영구 미갱신>된다(dev cudo_246 실측 결함).
#     설치 경로에 로드 단계(17-load-portal-schema.sh)를 두기 위해 여기서 함께 생성한다.
#
#   ★ 포털은 DB_SCHEMA 대상이 아니다 — public 을 그대로 쓴다(아래 -T 도 public 한정).
#     별개 물리 DB 이고 앱의 portal 데이터소스도 스키마를 지정하지 않는다. control 과 다른 것이 정상.
# ============================================================================
[[ -d "${PORTAL_MIG_DIR}" ]] || die "포털 DDL 디렉토리 없음: ${PORTAL_MIG_DIR}"

info "[portal-schema] 포털 DB 생성 + DDL 적용"
docker exec "${CTR}" psql -U "${DBUSER}" -d "${DBNAME}" \
  -v ON_ERROR_STOP=1 -c "CREATE DATABASE ${PORTAL_DBNAME};" >/dev/null

docker run --rm -v "${PORTAL_MIG_DIR}:/flyway/sql:ro" "${FWVER}" \
  -url="jdbc:postgresql://host.docker.internal:${PORT}/${PORTAL_DBNAME}" \
  -user="${DBUSER}" -password="${DBPW}" \
  -locations=filesystem:/flyway/sql \
  -connectRetries=10 \
  migrate

info "[portal-schema] pg_dump → ${PORTAL_OUT}"
{
  cat <<'HDR'
-- ============================================================================
-- portal-schema.sql — 포털 DB 복제본 스키마 단일 생성본 (db/portal/V1~Vn 통합 스냅샷)
--
-- ★ 생성물(수기 편집 금지). gen-schema-sql.sh 가 db/portal 의 DDL 을 적용한 결과를 덤프한다.
--
-- 용도: control 과 물리 분리된 포털 DB(PORTAL_DB_*)에 1회 로드. 저작도구 Flyway 는 control
--   (@Primary) 데이터소스에만 붙으므로 포털 스키마는 설치 단계가 책임진다
--   (17-load-portal-schema.sh). 로드하지 않으면 메타 복제가 조용히 0건으로 유지된다.
--
-- flyway_schema_history 제외. 재생성: deploy/onprem/scripts/gen-schema-sql.sh
-- ============================================================================

HDR
  PGPASSWORD="${DBPW}" pg_dump -h 127.0.0.1 -p "${PORT}" -U "${DBUSER}" -d "${PORTAL_DBNAME}" \
    --no-owner --no-privileges --no-comments \
    -T public.flyway_schema_history
} > "${PORTAL_OUT}"

PORTAL_TBLS="$(grep -c '^CREATE TABLE' "${PORTAL_OUT}" || true)"
ok "[portal-schema] 생성 완료: ${PORTAL_OUT} (CREATE TABLE ${PORTAL_TBLS}개)"
