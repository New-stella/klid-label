#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 15-init-db.sh — [대상 서버] (옵션) PostgreSQL DB/유저 생성 안내·수행
#
#   ★ 스키마/테이블은 다음 단계(16-load-schema.sh)가 db/schema.sql 로드로 준비한다
#     (온프렘은 Flyway 미사용 — SPRING_FLYWAY_ENABLED=false).
#     ⚠ 스키마가 없어도 앱은 <기동에 성공한다> — ddl-auto=validate 는 선언만 있고 이 앱의
#       EMF 구성에 도달하지 않는다. 기동 성공을 스키마 준비의 근거로 삼지 말 것.
#     따라서 이 단계는 "DB·유저·접속권한 준비"까지만 담당하고, 테이블 생성은 하지 않는다.
#
#   - psql 이 있고 DB_INIT_RUN=1 이면 control DB·유저를 생성한다.
#   - 그 외에는 수동 준비 안내만 출력한다(폐쇄망 DBA 가 직접 수행하는 경우가 많음).
#
#   ★ 역할 분담: 번들 PG 엔진 설치·기동은 10-install-postgresql.sh, 이 스크립트는 DB·유저만 만든다
#     (테이블은 16 단계가 만든다 — 중복 생성 금지).
#   ※ LS_*·QRTZ_* 와 뷰·시드는 16-load-schema.sh 의 db/schema.sql 로드로 생성된다. 빈 DB 2개 +
#     앱 유저(DB OWNER)만 준비하면 된다. 대상 스키마에 이미 테이블이 있으면 16 단계는 멱등
#     가드(테이블 존재 시 skip)로 건너뛴다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

CONTROL_DB_NAME="${CONTROL_DB_NAME:-klid_system}"
DB_APP_USER="${DB_APP_USER:-klid_user}"

if [[ "${DB_INIT_RUN:-0}" != "1" ]] || ! command -v psql >/dev/null 2>&1; then
  info "[db] 자동 DB 생성을 수행하지 않습니다(기본). 아래를 DBA 가 준비하세요:"
  cat <<TXT

  # 1) 애플리케이션 DB 유저 생성(비밀번호는 backend.env 의 *_DB_PASSWORD 와 일치)
  CREATE ROLE ${DB_APP_USER} LOGIN PASSWORD '<강력한_비밀번호>';

  # 2) control DB 생성
  CREATE DATABASE ${CONTROL_DB_NAME} OWNER ${DB_APP_USER} ENCODING 'UTF8';

  # 3) 스키마/테이블은 16-load-schema.sh 가 db/schema.sql 로드로 생성합니다(빈 DB + OWNER 권한).
  #    LS_* 62개·QRTZ_* 11개·뷰 4개 + 시드 66행이 한 번에 로드됩니다(온프렘은 Flyway 미사용 —
  #    아무도 로드하지 않으면 테이블이 생기지 않습니다). ⚠ 그래도 backend 는 기동에 성공하므로
  #    반드시 테이블 개수를 세어 확인하세요. 기대값은 매체에서 셉니다(하드코딩하지 않는다):
#      grep -c '^CREATE TABLE klid_at\.' db/schema.sql   +   grep -c '^CREATE VIEW klid_at\.' db/schema.sql

  # 자동 생성을 원하면(psql 접속 가능 시):
  #   sudo DB_INIT_RUN=1 PGHOST=... PGPORT=5432 PGUSER=postgres PGPASSWORD=... \\
  #        DB_APP_USER=${DB_APP_USER} DB_APP_PASSWORD='...' \\
  #        ./scripts/install/15-init-db.sh
TXT
  exit 0
fi

require_cmd psql
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD 환경변수가 필요합니다(앱 유저 비밀번호)}"
# H2: 슈퍼유저로 접속할 PGUSER 를 강제(예: postgres). root 로 실행돼도 PG 슈퍼유저 계정으로 접속한다.
: "${PGUSER:?PGUSER 환경변수 필요(예: postgres) — DB/유저 생성용 슈퍼유저}"
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-5432}" PGUSER

# H3: SQL 식별자(유저명/DB명)는 정규식 allowlist 로 검증한 뒤에만 사용(인젝션 차단).
#   소문자 시작 + [a-z0-9_], 최대 63자. 위반 시 die.
validate_ident() {
  local name="$1" val="$2"
  [[ "${val}" =~ ^[a-z][a-z0-9_]{0,62}$ ]] \
    || die "[db] 부적합한 식별자 ${name}='${val}' — 소문자 시작 + [a-z0-9_], 최대 63자만 허용합니다."
}
validate_ident DB_APP_USER     "${DB_APP_USER}"
validate_ident CONTROL_DB_NAME "${CONTROL_DB_NAME}"

info "[db] DB 초기화: host=${PGHOST}:${PGPORT} superuser=${PGUSER} appuser=${DB_APP_USER}"

# 멱등: 존재하지 않을 때만 생성.
# H3: 비밀번호는 heredoc 직접 보간 금지 → psql -v 로 변수 주입 + :'pwd' 메타커맨드로 안전하게 quote.
#   식별자(DB_APP_USER)는 위에서 allowlist 검증을 통과했으므로 그대로 보간한다.
psql -v ON_ERROR_STOP=1 -U "${PGUSER}" -d postgres \
     -v approle="${DB_APP_USER}" -v apppwd="${DB_APP_PASSWORD}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'approle', :'apppwd')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'approle')
\gexec
SQL

for dbn in "${CONTROL_DB_NAME}"; do
  if ! psql -tAc "SELECT 1 FROM pg_database WHERE datname='${dbn}'" -U "${PGUSER}" -d postgres | grep -q 1; then
    psql -v ON_ERROR_STOP=1 -U "${PGUSER}" -d postgres \
      -c "CREATE DATABASE ${dbn} OWNER ${DB_APP_USER} ENCODING 'UTF8';"
    ok "[db] 생성: ${dbn}"
  else
    info "[db] 이미 존재(생략): ${dbn}"
  fi
done

ok "[db] DB/유저 준비 완료. 스키마는 다음 단계(16-load-schema.sh)가 schema.sql 로 로드합니다."
