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
#   요구: docker(데몬 기동) + pg_dump(호스트). 폐쇄망이 아닌 빌드머신에서 실행.
#   마이그레이션 추가/변경 시 반드시 재실행하여 schema.sql 을 갱신할 것.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

require_cmd docker
require_cmd pg_dump

REPO="$(repo_root)"
MIG_DIR="${REPO}/backend/src/main/resources/db/migration"
OUT="${REPO}/deploy/onprem/db/schema.sql"

CTR="klid-schema-gen-$$"
PORT="${SCHEMA_GEN_PORT:-55432}"
PGVER="${SCHEMA_GEN_PG_IMAGE:-postgres:16-alpine}"
FWVER="${SCHEMA_GEN_FLYWAY_IMAGE:-flyway/flyway:10-alpine}"
DBNAME="klid_system"
DBUSER="klid_user"
DBPW="genpw"

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

info "[schema] Flyway migrate (전체 마이그레이션 적용)"
docker run --rm -v "${MIG_DIR}:/flyway/sql:ro" "${FWVER}" \
  -url="jdbc:postgresql://host.docker.internal:${PORT}/${DBNAME}" \
  -user="${DBUSER}" -password="${DBPW}" \
  -locations=filesystem:/flyway/sql \
  -connectRetries=10 \
  migrate

info "[schema] pg_dump → ${OUT}"
ensure_dir "$(dirname "${OUT}")"
{
  cat <<'HDR'
-- ============================================================================
-- schema.sql — 저작도구 전체 스키마 단일 생성본 (Flyway V0~Vn 통합 스냅샷)
--
-- ★ 생성물(수기 편집 금지). gen-schema-sql.sh 가 Flyway 로 적용한 스키마를 덤프한다.
--   → Flyway 가 실제 적용/검증한 스키마와 100% 동일하므로 ddl-auto=validate 통과 보장.
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
    -T public.flyway_schema_history
} > "${OUT}"

TBLS="$(grep -c '^CREATE TABLE' "${OUT}" || true)"
ok "[schema] 생성 완료: ${OUT} (CREATE TABLE ${TBLS}개, $(wc -l < "${OUT}") 라인)"
