#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 17-load-portal-schema.sh — [대상 서버] (옵션) db/portal-schema.sql 을 빈 포털 DB 에 1회 로드
#
#   ★ 왜 별도 단계인가: 저작도구 Flyway 는 @Primary(=control) 데이터소스에만 붙는다.
#     포털 DB(PORTAL_DB_*)는 어떤 자동 마이그레이션도 받지 않으므로, 스키마를 넣지 않으면
#     빈 DB 로 운영에 들어간다. 그 상태에서 메타 복제 워커는 매 tick graceful skip 만 하고
#     포털 복제본이 <영구 미갱신>된다(dev cudo_246 실측 결함 — 조용해서 늦게 발견된다).
#
#   - psql 이 있고 SCHEMA_LOAD_RUN=1 이면 포털 DB 에 로드한다(테이블 있으면 skip 가드).
#   - 그 외에는 수동 로드 안내만 출력한다(폐쇄망 DBA 가 직접 수행하는 경우가 많음).
#   - 포털 복제를 쓰지 않는 구성이면 SKIP_PORTAL_SCHEMA_LOAD=1 로 생략하되,
#     그 환경은 META_REPLICATION_ENABLED=false 를 <명시>할 것(기본값이 true 라 켜진 채로 남는다).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

PORTAL_DB_NAME="${PORTAL_DB_NAME:-portal}"
DB_APP_USER="${DB_APP_USER:-${CONTROL_DB_USERNAME:-klid_user}}"
PORTAL_SCHEMA_SQL="$(onprem_root)/db/portal-schema.sql"

if [[ "${SKIP_PORTAL_SCHEMA_LOAD:-0}" == "1" ]]; then
  info "[portal-schema] SKIP_PORTAL_SCHEMA_LOAD=1 — 포털 스키마 로드 생략."
  warn "[portal-schema] 포털 복제를 쓰지 않는다면 META_REPLICATION_ENABLED=false 를 명시하세요(기본 true)."
  exit 0
fi

if [[ "${SCHEMA_LOAD_RUN:-0}" != "1" ]] || ! command -v psql >/dev/null 2>&1; then
  info "[portal-schema] 자동 로드를 수행하지 않습니다(기본). 아래를 DBA 가 수행하세요:"
  cat <<TXT

  # 빈 포털 DB 에 복제본 스키마를 1회 로드
  psql -h <PORTAL_HOST> -p <PORTAL_PORT> -U ${DB_APP_USER} -d ${PORTAL_DB_NAME} \\
       -v ON_ERROR_STOP=1 -f "${PORTAL_SCHEMA_SQL}"

  # 로드하지 않을 경우: 메타 복제가 계속 0건으로 유지됩니다(포털 복제본 미갱신).
TXT
  exit 0
fi

require_cmd psql
[[ -f "${PORTAL_SCHEMA_SQL}" ]] \
  || die "[portal-schema] portal-schema.sql 을 찾을 수 없습니다: ${PORTAL_SCHEMA_SQL} (gen-schema-sql.sh 재실행 필요)"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD 환경변수가 필요합니다(앱 유저 비밀번호)}"
# 포털 DB 는 control 과 다른 호스트일 수 있다 — PORTAL_DB_* 를 우선 사용한다.
export PGHOST="${PORTAL_DB_HOST:-${PGHOST:-${CONTROL_DB_HOST:-127.0.0.1}}}"
export PGPORT="${PORTAL_DB_PORT:-${PGPORT:-${CONTROL_DB_PORT:-5432}}}"
export PGPASSWORD="${PORTAL_DB_PASSWORD:-${DB_APP_PASSWORD}}"
PORTAL_USER="${PORTAL_DB_USERNAME:-${DB_APP_USER}}"

existing="$(psql -tAX -U "${PORTAL_USER}" -d "${PORTAL_DB_NAME}" \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';" \
  2>/dev/null || echo "ERR")"

if [[ "${existing}" == "ERR" ]]; then
  die "[portal-schema] 포털 DB 접속 실패 — host=${PGHOST}:${PGPORT} user=${PORTAL_USER} db=${PORTAL_DB_NAME}"
fi
if [[ "${existing}" != "0" ]]; then
  info "[portal-schema] 이미 테이블 ${existing}개 존재 — 로드 생략(멱등 가드)."
  exit 0
fi

info "[portal-schema] portal-schema.sql 로드: host=${PGHOST}:${PGPORT} user=${PORTAL_USER} db=${PORTAL_DB_NAME}"
psql -v ON_ERROR_STOP=1 -U "${PORTAL_USER}" -d "${PORTAL_DB_NAME}" -f "${PORTAL_SCHEMA_SQL}" >/dev/null
loaded="$(psql -tAX -U "${PORTAL_USER}" -d "${PORTAL_DB_NAME}" \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';")"
ok "[portal-schema] 로드 완료 — 포털 public 테이블 ${loaded}개. 메타 복제 워커가 다음 tick 부터 복제합니다."
