#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 15-init-db.sh — [대상 서버] (옵션) PostgreSQL DB/유저 생성 안내·수행
#
#   ★ 스키마/테이블은 backend 가 기동 시 Flyway 로 자동 생성·검증한다
#     (application.yml: spring.flyway.enabled=true, ddl-auto=validate).
#     따라서 이 단계는 "DB·유저·접속권한 준비"까지만 담당하고, 테이블 생성은 하지 않는다.
#
#   - psql 이 있고 DB_INIT_RUN=1 이면 control/portal DB·유저를 생성한다.
#   - 그 외에는 수동 준비 안내만 출력한다(폐쇄망 DBA 가 직접 수행하는 경우가 많음).
#
#   ※ MNG_*/QRTZ_* 공유 스키마는 관제 인프라가 제공한다(backend 는 validate 로 참조).
#     온프렘이 자체 PostgreSQL 을 운영한다면 해당 공유 테이블도 사전 준비되어야 한다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

CONTROL_DB_NAME="${CONTROL_DB_NAME:-klid_system}"
PORTAL_DB_NAME="${PORTAL_DB_NAME:-portal}"
DB_APP_USER="${DB_APP_USER:-klid_user}"

if [[ "${DB_INIT_RUN:-0}" != "1" ]] || ! command -v psql >/dev/null 2>&1; then
  info "[db] 자동 DB 생성을 수행하지 않습니다(기본). 아래를 DBA 가 준비하세요:"
  cat <<TXT

  # 1) 애플리케이션 DB 유저 생성(비밀번호는 backend.env 의 *_DB_PASSWORD 와 일치)
  CREATE ROLE ${DB_APP_USER} LOGIN PASSWORD '<강력한_비밀번호>';

  # 2) control / portal DB 생성
  CREATE DATABASE ${CONTROL_DB_NAME} OWNER ${DB_APP_USER} ENCODING 'UTF8';
  CREATE DATABASE ${PORTAL_DB_NAME}  OWNER ${DB_APP_USER} ENCODING 'UTF8';

  # 3) 스키마/테이블은 backend 가 기동 시 Flyway 로 자동 생성합니다(추가 작업 불필요).
  #    단, 관제 공유 테이블(MNG_*/QRTZ_*)은 ddl-auto=validate 로 검증만 하므로
  #    온프렘 자체 DB 라면 해당 공유 스키마도 사전 준비되어 있어야 합니다.

  # 자동 생성을 원하면(psql 접속 가능 시):
  #   sudo DB_INIT_RUN=1 PGHOST=... PGPORT=5432 PGUSER=postgres PGPASSWORD=... \\
  #        DB_APP_USER=${DB_APP_USER} DB_APP_PASSWORD='...' \\
  #        ./scripts/install/15-init-db.sh
TXT
  exit 0
fi

require_cmd psql
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD 환경변수가 필요합니다(앱 유저 비밀번호)}"
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-5432}"
info "[db] DB 초기화: host=${PGHOST}:${PGPORT} user=${DB_APP_USER}"

# 멱등: 존재하지 않을 때만 생성
psql -v ON_ERROR_STOP=1 -d postgres <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${DB_APP_USER}') THEN
    CREATE ROLE ${DB_APP_USER} LOGIN PASSWORD '${DB_APP_PASSWORD}';
  END IF;
END
\$\$;
SQL

for dbn in "${CONTROL_DB_NAME}" "${PORTAL_DB_NAME}"; do
  if ! psql -tAc "SELECT 1 FROM pg_database WHERE datname='${dbn}'" -d postgres | grep -q 1; then
    psql -v ON_ERROR_STOP=1 -d postgres -c "CREATE DATABASE ${dbn} OWNER ${DB_APP_USER} ENCODING 'UTF8';"
    ok "[db] 생성: ${dbn}"
  else
    info "[db] 이미 존재(생략): ${dbn}"
  fi
done

ok "[db] DB/유저 준비 완료. 테이블은 backend 기동 시 Flyway 가 생성합니다."
