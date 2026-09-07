#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# gen-cbd-init-sql.sh — [빌드머신] 설계 산출물용 <전체 DB 초기 생성 스크립트> 생성
#
#   빈 데이터베이스 하나를 이 스크립트로 세울 수 있게 만든다. 결과는 두 파일이다.
#     docs/db/KLID_AT_schema.sql     — 표·뷰·시퀀스·인덱스·제약·주석 (양식의 「DB생성 스크립트」)
#     docs/db/KLID_AT_initdata.sql   — 코드값·설정 기본값 (양식의 「코드 및 초기 데이터 생성 스크립트」)
#
#   ★ 왜 둘로 나누는가 — 산출물 양식이 그 둘을 <다른 절>로 요구하기 때문이다.
#     한 파일로 합치면 어느 절에 넣어야 할지가 사라진다. 순서는 스키마 먼저, 데이터 나중이다.
#
#   ★★ 설치용 schema.sql 과 무엇이 다른가 — <주석>이다.
#     설치용은 pg_dump 에 --no-comments 를 줘서 표·컬럼 설명을 전부 버린다. 설치에는
#     필요 없기 때문이다. 그런데 설계 산출물에서는 그 한글 설명이 <핵심>이다 —
#     컬럼이 무엇을 뜻하는지가 설계서가 말해야 하는 바로 그것이라, 같은 파일을 그대로
#     낼 수 없다. 그래서 별도 생성기를 둔다.
#     ⚠ 두 파일을 하나로 합치려 들지 말 것. 설치본에 주석을 넣으면 로드 시간과 파일
#       크기만 늘고, 설계본에서 주석을 빼면 부록으로서 값이 없어진다.
#
#   ★ 손으로 쓰지 않는다. 마이그레이션 전량을 클린 컨테이너에 실제로 적용한 뒤 덤프한다 —
#     그래야 산출물이 실제 스키마와 어긋나지 않는다. 설치용 생성기와 같은 기계장치다.
#
#   ★ 순서를 재배치하지 않는다. 덤프가 내는 순서는 <의존 관계를 만족하는 순서>라,
#     보기 좋게 절을 나누려고 옮기면 그대로 로드가 깨진다. 대신 머리말에 목차를 붙이고
#     꼬리에 검증 질의를 붙인다 — 읽는 사람이 무엇이 몇 개인지 먼저 알고 시작하게.
#
#   ★ 시드 행을 포함한다. 코드값·설정 기본값이 없으면 빈 데이터베이스를 세워도 앱이
#     돌지 않으므로, 「초기 생성」이라는 이름값을 하려면 함께 있어야 한다.
#
#   요구: docker(데몬 기동) + pg_dump + psql. 폐쇄망이 아닌 빌드머신에서 실행.
#   마이그레이션이 늘면 다시 돌린다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SELF_DIR}/lib/common.sh"

require_cmd docker
require_cmd pg_dump
require_cmd psql

REPO="$(repo_root)"
MIG_DIR="${REPO}/backend/src/main/resources/db/migration"
OUT_DDL="${REPO}/docs/db/KLID_AT_schema.sql"
OUT_DAT="${REPO}/docs/db/KLID_AT_initdata.sql"

CTR="klid-cbd-init-gen-$$"
PORT="${CBD_INIT_GEN_PORT:-55433}"
PGVER="${CBD_INIT_GEN_PG_IMAGE:-postgres:16-alpine}"
FWVER="${CBD_INIT_GEN_FLYWAY_IMAGE:-flyway/flyway:10-alpine}"
DBNAME="klid_system"
DBUSER="klid_user"
DBPW="genpw"
# 앱과 같은 축의 환경변수 이름(두 번째 진실원 금지).
DB_SCHEMA="${DB_SCHEMA:-klid_at}"

[[ -d "${MIG_DIR}" ]] || die "마이그레이션 디렉토리 없음: ${MIG_DIR}"

cleanup() { docker rm -f "${CTR}" >/dev/null 2>&1 || true; }
trap cleanup EXIT

MIG_COUNT="$(find "${MIG_DIR}" -maxdepth 1 -name 'V*.sql' | wc -l | tr -d ' ')"
info "[cbd-init] 마이그레이션 ${MIG_COUNT}개 · 대상 스키마 ${DB_SCHEMA}"

info "[cbd-init] 클린 PostgreSQL 기동 (${PGVER}, port ${PORT})"
docker run -d --name "${CTR}" \
  -e POSTGRES_USER="${DBUSER}" -e POSTGRES_PASSWORD="${DBPW}" -e POSTGRES_DB="${DBNAME}" \
  -p "${PORT}:5432" "${PGVER}" >/dev/null

info "[cbd-init] DB ready 대기"
for _ in $(seq 1 30); do
  docker exec "${CTR}" pg_isready -U "${DBUSER}" -d "${DBNAME}" >/dev/null 2>&1 && break
  sleep 1
done
docker exec "${CTR}" pg_isready -U "${DBUSER}" -d "${DBNAME}" >/dev/null 2>&1 \
  || die "PostgreSQL 기동 실패"

info "[cbd-init] Flyway migrate (전량 적용)"
docker run --rm -v "${MIG_DIR}:/flyway/sql:ro" "${FWVER}" \
  -url="jdbc:postgresql://host.docker.internal:${PORT}/${DBNAME}" \
  -user="${DBUSER}" -password="${DBPW}" \
  -locations=filesystem:/flyway/sql \
  -schemas="${DB_SCHEMA}" -defaultSchema="${DB_SCHEMA}" \
  -createSchemas=true -connectRetries=10 \
  migrate >/dev/null

q() { PGPASSWORD="${DBPW}" psql -tAX -h 127.0.0.1 -p "${PORT}" -U "${DBUSER}" -d "${DBNAME}" -c "$1"; }

# 유출 가드 — 대상 스키마 밖에 객체가 생기면 덤프에 안 담겨 조용히 빠진다.
leaked="$(q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='public' AND c.relkind IN ('r','v','m','S');")"
[[ "${leaked}" == "0" ]] \
  || die "[cbd-init] public 에 객체 ${leaked}개 유출 — 대상 스키마 밖이라 덤프에 담기지 않습니다."

N_TABLE="$(q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${DB_SCHEMA}' AND c.relkind='r' AND c.relname <> 'flyway_schema_history';")"
N_VIEW="$(q  "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${DB_SCHEMA}' AND c.relkind='v';")"
N_SEQ="$(q   "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${DB_SCHEMA}' AND c.relkind='S';")"
# ⚠ Flyway 자신의 이력 표는 산출물에서 빼므로 집계에서도 빼야 한다 — 안 빼면 머리말 수치가
#   로드 결과보다 인덱스 2·제약 1 만큼 커서, 읽는 사람이 「덜 만들어졌다」로 오해한다.
N_IDX="$(q   "SELECT count(*) FROM pg_indexes WHERE schemaname='${DB_SCHEMA}' AND tablename <> 'flyway_schema_history';")"
N_CON="$(q   "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
              JOIN pg_class t ON t.oid=c.conrelid
              WHERE n.nspname='${DB_SCHEMA}' AND t.relname <> 'flyway_schema_history';")"
N_TCOM="$(q  "SELECT count(*) FROM pg_description d JOIN pg_class c ON c.oid=d.objoid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${DB_SCHEMA}' AND d.objsubid=0;")"
N_CCOM="$(q  "SELECT count(*) FROM pg_description d JOIN pg_class c ON c.oid=d.objoid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${DB_SCHEMA}' AND d.objsubid>0;")"
N_SEED="$(q  "SELECT coalesce(sum(n),0) FROM (
                SELECT (xpath('/row/c/text()', query_to_xml(
                  format('SELECT count(*) AS c FROM %I.%I', schemaname, tablename), false, true, '')))[1]::text::bigint AS n
                FROM pg_tables WHERE schemaname='${DB_SCHEMA}' AND tablename <> 'flyway_schema_history') t;")"

info "[cbd-init] 표 ${N_TABLE} · 뷰 ${N_VIEW} · 시퀀스 ${N_SEQ} · 인덱스 ${N_IDX} · 제약 ${N_CON}"
info "[cbd-init] 주석 표 ${N_TCOM} · 컬럼 ${N_CCOM} · 시드 ${N_SEED}행"

[[ "${N_TCOM}" -gt 0 && "${N_CCOM}" -gt 0 ]] \
  || die "[cbd-init] 주석이 0건입니다 — 이 산출물의 존재 이유가 주석이므로 그대로 내지 않습니다."

ensure_dir "$(dirname "${OUT_DDL}")"

# ── ① DB생성 스크립트 (DDL + 주석) ──────────────────────────────────────────
info "[cbd-init] pg_dump(스키마) → ${OUT_DDL}"
{
  cat <<HDR
-- ============================================================================
-- 학습데이터 저작도구 — DB 생성 스크립트 (${DB_SCHEMA})
--
-- 빈 데이터베이스에 이 파일을 그대로 실행하면 전체 스키마가 선다. 손질 없이 쓴다.
--
--   psql -d <데이터베이스> -f KLID_AT_schema.sql
--
-- 담긴 것 — 스키마 1 · 표 ${N_TABLE} · 뷰 ${N_VIEW} · 시퀀스 ${N_SEQ}
--           인덱스 ${N_IDX} · 제약 ${N_CON} · 주석 표 ${N_TCOM}·컬럼 ${N_CCOM}
--
-- ★ 초기 데이터는 이 파일에 없다 — KLID_AT_initdata.sql 을 <이 파일 다음에> 실행한다.
--   코드값과 설정 기본값이 없으면 스키마는 서도 앱이 돌지 않는다.
--
-- ★ 스키마 생성부터 포함하므로 접속 세션의 탐색 경로와 무관하게 항상 ${DB_SCHEMA} 에 만들어진다.
--
-- ★ 순서는 의존 관계를 만족하는 순서다. 보기 좋게 절을 나누려고 옮기면 로드가 깨진다.
--   표를 찾으려면 이름으로 검색하고, 그 뜻은 바로 뒤따르는 COMMENT 를 본다.
--
-- 만든 방식 — 손으로 쓰지 않았다. 형상 관리의 스키마 변경 파일 ${MIG_COUNT}개를 클린
--   데이터베이스에 전량 적용한 뒤 덤프한 것이라 실제 스키마와 어긋날 수 없다.
--   재생성: deploy/onprem/scripts/gen-cbd-init-sql.sh
-- ⚠ 수기 편집 금지(생성물). 고칠 것이 있으면 스키마 변경 파일을 고치고 다시 생성한다.
-- ⚠ 설치용 스키마 파일과 다른 파일이다 — 그쪽은 주석을 담지 않는다. 둘을 합치지 말 것.
--
-- 생성 시각: $(date '+%Y-%m-%d %H:%M:%S%z')
-- ============================================================================

HDR
  PGPASSWORD="${DBPW}" pg_dump -h 127.0.0.1 -p "${PORT}" -U "${DBUSER}" -d "${DBNAME}" \
    --schema-only --no-owner --no-privileges \
    -n "${DB_SCHEMA}" -T "${DB_SCHEMA}.flyway_schema_history"
} > "${OUT_DDL}"

# ── ② 코드 및 초기 데이터 생성 스크립트 ─────────────────────────────────────
#   COPY 가 아니라 INSERT 로 낸다 — 설계 산출물은 사람이 읽고 한 줄씩 고를 수 있어야 한다.
info "[cbd-init] pg_dump(데이터) → ${OUT_DAT}"
{
  cat <<HDR
-- ============================================================================
-- 학습데이터 저작도구 — 코드 및 초기 데이터 생성 스크립트 (${DB_SCHEMA})
--
-- ★ KLID_AT_schema.sql 을 먼저 실행한 뒤 이 파일을 실행한다. 순서를 바꾸면 대상 표가 없어 실패한다.
--
--   psql -d <데이터베이스> -f KLID_AT_schema.sql
--   psql -d <데이터베이스> -f KLID_AT_initdata.sql
--
-- 담긴 것 — ${N_SEED}행 (공통코드·시스템 설정 기본값·스케줄러 잠금 행 등)
--
-- 이 데이터가 없으면 스키마는 서도 앱이 돌지 않는다. 그래서 별도 산출물로 낸다.
-- 사람이 한 줄씩 읽고 고를 수 있도록 INSERT 로 낸다(대량 적재용 형식이 아니다).
--
-- ⚠ 수기 편집 금지(생성물). 값을 바꾸려면 스키마 변경 파일의 시드를 고치고 다시 생성한다.
--
-- 생성 시각: $(date '+%Y-%m-%d %H:%M:%S%z')
-- ============================================================================

HDR
  PGPASSWORD="${DBPW}" pg_dump -h 127.0.0.1 -p "${PORT}" -U "${DBUSER}" -d "${DBNAME}" \
    --data-only --column-inserts --no-owner --no-privileges \
    -n "${DB_SCHEMA}" -T "${DB_SCHEMA}.flyway_schema_history"
} > "${OUT_DAT}"

# ── 산출물 자체 검사 ────────────────────────────────────────────────────────
grep -q "^CREATE SCHEMA ${DB_SCHEMA};" "${OUT_DDL}" \
  || die "[cbd-init] DDL 에 CREATE SCHEMA ${DB_SCHEMA} 가 없습니다 — 빈 DB 에 먹이면 실패합니다."

dumped_com="$(grep -c "^COMMENT ON " "${OUT_DDL}" || true)"
[[ "${dumped_com}" -ge $(( N_TCOM + N_CCOM )) ]] \
  || die "[cbd-init] 주석이 ${dumped_com}건만 실렸습니다(DB 에는 $(( N_TCOM + N_CCOM ))건)."

dumped_ins="$(grep -c "^INSERT INTO " "${OUT_DAT}" || true)"
[[ "${dumped_ins}" -eq "${N_SEED}" ]] \
  || die "[cbd-init] 초기 데이터가 ${dumped_ins}행만 실렸습니다(DB 에는 ${N_SEED}행)."

# DDL 에 데이터가, 데이터에 DDL 이 섞이지 않았는지 — 두 절의 경계가 흐려지면 양식이 무너진다.
! grep -q "^INSERT INTO \|^COPY " "${OUT_DDL}" \
  || die "[cbd-init] DDL 파일에 데이터가 섞였습니다."
! grep -q "^CREATE TABLE \|^CREATE INDEX " "${OUT_DAT}" \
  || die "[cbd-init] 데이터 파일에 DDL 이 섞였습니다."

ok "[cbd-init] DDL : ${OUT_DDL}  ($(du -h "${OUT_DDL}" | cut -f1), $(wc -l < "${OUT_DDL}" | tr -d ' ')줄, 주석 ${dumped_com}건)"
ok "[cbd-init] 데이터: ${OUT_DAT}  ($(du -h "${OUT_DAT}" | cut -f1), ${dumped_ins}행)"
