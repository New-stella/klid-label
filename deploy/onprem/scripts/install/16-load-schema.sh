#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 16-load-schema.sh — [대상 서버] (옵션) db/schema.sql 을 빈 control DB 에 1회 로드
#
#   ★ SPRING_FLYWAY_ENABLED=false 운영(온프렘/이중화)의 스키마 준비 단계.
#     backend 는 부팅 시 ddl-auto=validate 로 검증만 하므로, 그 전에 전체 스키마가
#     존재해야 한다. 이 단계가 db/schema.sql(Flyway V0~Vn 통합 덤프 + 시드)을 로드한다.
#
#   - psql 이 있고 SCHEMA_LOAD_RUN=1 이면 control DB 에 로드한다(테이블 있으면 skip 가드).
#   - 그 외에는 수동 로드 안내만 출력한다(폐쇄망 DBA 가 직접 수행하는 경우가 많음).
#
#   ★ Flyway 로 부트스트랩하는 단일 노드 구성이면(SPRING_FLYWAY_ENABLED=true) 이 단계를
#     건너뛴다(SKIP_SCHEMA_LOAD=1 또는 SCHEMA_LOAD_RUN 미설정).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

CONTROL_DB_NAME="${CONTROL_DB_NAME:-klid_system}"
DB_APP_USER="${DB_APP_USER:-${CONTROL_DB_USERNAME:-klid_user}}"
SCHEMA_SQL="$(onprem_root)/db/schema.sql"

if [[ "${SKIP_SCHEMA_LOAD:-0}" == "1" ]]; then
  info "[schema] SKIP_SCHEMA_LOAD=1 — 스키마 로드 생략(Flyway 부트스트랩 구성으로 간주)."
  exit 0
fi

if [[ "${SCHEMA_LOAD_RUN:-0}" != "1" ]] || ! command -v psql >/dev/null 2>&1; then
  info "[schema] 자동 스키마 로드를 수행하지 않습니다(기본). 아래를 DBA 가 수행하세요:"
  cat <<TXT

  # 빈 control DB 에 전체 스키마(+시드)를 1회 로드 (앱 유저=소유자 로 접속 권장)
  psql -h <HOST> -p <PORT> -U ${DB_APP_USER} -d ${CONTROL_DB_NAME} \\
       -v ON_ERROR_STOP=1 -f "${SCHEMA_SQL}"

  # 자동 로드를 원하면(psql 접속 가능 시):
  #   sudo SCHEMA_LOAD_RUN=1 PGHOST=... PGPORT=5432 \\
  #        DB_APP_USER=${DB_APP_USER} DB_APP_PASSWORD='...' \\
  #        CONTROL_DB_NAME=${CONTROL_DB_NAME} ./scripts/install/16-load-schema.sh
TXT
  exit 0
fi

require_cmd psql
[[ -f "${SCHEMA_SQL}" ]] || die "[schema] schema.sql 을 찾을 수 없습니다: ${SCHEMA_SQL}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD 환경변수가 필요합니다(앱 유저 비밀번호)}"
export PGHOST="${PGHOST:-${CONTROL_DB_HOST:-127.0.0.1}}"
export PGPORT="${PGPORT:-${CONTROL_DB_PORT:-5432}}"
export PGPASSWORD="${DB_APP_PASSWORD}"

# 멱등 가드: public 에 이미 BASE TABLE 이 있으면 로드하지 않는다(중복 로드/덮어쓰기 방지).
existing="$(psql -tAX -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';" \
  2>/dev/null || echo "ERR")"

if [[ "${existing}" == "ERR" ]]; then
  die "[schema] DB 접속 실패 — host=${PGHOST}:${PGPORT} user=${DB_APP_USER} db=${CONTROL_DB_NAME}"
fi
if [[ "${existing}" != "0" ]]; then
  info "[schema] 이미 테이블 ${existing}개 존재 — 로드 생략(멱등 가드). 기존 스키마 유지."
  exit 0
fi

info "[schema] schema.sql 로드: host=${PGHOST}:${PGPORT} user=${DB_APP_USER} db=${CONTROL_DB_NAME}"
psql -v ON_ERROR_STOP=1 -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" -f "${SCHEMA_SQL}" >/dev/null
loaded="$(psql -tAX -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';")"
ok "[schema] 로드 완료 — public 테이블 ${loaded}개. backend 는 ddl-auto=validate 로 검증만 합니다."
