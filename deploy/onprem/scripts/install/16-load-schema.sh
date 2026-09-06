#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 16-load-schema.sh — [대상 서버] (옵션) db/schema.sql 을 빈 control DB 에 1회 로드
#
#   ★ 온프렘은 Flyway 를 쓰지 않는다(확정) — 테이블을 만드는 경로는 이 로드 하나뿐이다.
#     이 단계가 db/schema.sql(전체 통합 DDL + 시드)을 로드한다.
#     ⚠ 아무도 로드하지 않으면 대신 만들어 주는 것이 없다(마이그레이션이 돌지 않는다).
#
#   ★★스키마가 없어도 backend 는 <기동에 성공한다> — 이것이 이 단계의 가장 큰 함정이다.
#     구 서술 폐기(2026-08-30): "backend 는 ddl-auto=validate 로 검증만 하므로 스키마가 없으면
#     기동에 실패한다". 그 설정은 <선언만 있고 Hibernate 에 도달하지 않는다> — 이 앱은 EMF 를
#     직접 만들면서 spring.jpa.properties.* 만 넘기고, ddl-auto 를 병합하는 표준 경로를 우회한다.
#     ⇒ "기동됐으니 스키마가 들어갔다"는 판단은 <틀리다>. 빈 스키마인 채로 운영에 넘어가고
#       화면·배치가 처음 DB 를 건드릴 때 비로소 깨진다.
#     ⇒ 유일한 판정 수단은 <테이블 개수를 세어 보는 것>이다(아래 로드 후 검증과 같은 쿼리).
#
#   ★ 적재 주체는 조건부다 (2026-09-05 확정) — 현장 담당 선적용 우선 + 우리가 채움.
#     비었으면 적재하고, 이미 있고 기대와 맞으면 건너뛰며, 기대와 다르면 덮어쓰지 않고 멈춘다.
#   ⚠ 구 옵트인 SCHEMA_LOAD_RUN=1 폐기 — 기본이 「안 함」이면 아무도 적재하지 않은 채 넘어간다.
#     빠져나갈 문은 SKIP_SCHEMA_LOAD=1 하나로 남긴다.
#
#   ★ 스키마는 ${DB_SCHEMA:-klid_at}(앱과 같은 축). schema.sql 이 CREATE SCHEMA 와 스키마 한정
#     객체명을 모두 담고 있으므로 로드하는 psql 세션의 search_path 와 무관하게 그 스키마에 생성된다.
#
#   ★ SKIP_SCHEMA_LOAD=1 은 "스키마가 이미 준비돼 있다"는 선언이다(DBA 선적용 등).
#     Flyway 로 대신 만들겠다는 뜻이 아니다 — 그 경로는 온프렘에 없다.
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

CONTROL_DB_NAME="${CONTROL_DB_NAME:-klid_system}"
DB_APP_USER="${DB_APP_USER:-${CONTROL_DB_USERNAME:-klid_user}}"
SCHEMA_SQL="$(onprem_root)/db/schema.sql"
# ★ 대상 스키마는 앱과 <같은 환경변수>를 읽는다(두 번째 진실원 금지).
#   앱: 커넥션 currentSchema / Flyway schemas·default-schema / Quartz tablePrefix /
#       JPA hibernate.default_schema 가 모두 ${DB_SCHEMA:klid_at} 하나를 본다.
#   여기서 다른 값을 쓰면 "로드는 됐는데 앱은 빈 스키마를 본다"가 된다(오류 아닌 조용한 분기).
DB_SCHEMA="${DB_SCHEMA:-klid_at}"
# SQL 리터럴로 보간되므로 allowlist 로 먼저 검증한다(인젝션 차단).
#   psql 은 -c 문자열에서 :'var' 보간을 하지 않으므로(서버로 그대로 전송) 변수 주입을 쓸 수 없다.
[[ "${DB_SCHEMA}" =~ ^[a-z][a-z0-9_]{0,62}$ ]] \
  || die "[schema] 부적합한 DB_SCHEMA='${DB_SCHEMA}' — 소문자 시작 + [a-z0-9_], 최대 63자만 허용합니다."

if [[ "${SKIP_SCHEMA_LOAD:-0}" == "1" ]]; then
  info "[schema] SKIP_SCHEMA_LOAD=1 — 스키마 로드 생략(스키마가 이미 준비된 것으로 간주)."
  warn "[schema] 실제로 준비돼 있는지 <반드시 세어서> 확인하세요 — 비어 있어도 backend 는 기동에 성공합니다."
  warn "[schema]   psql -tAX -d <DB> -c \"SELECT count(*) FROM information_schema.tables WHERE table_schema='${DB_SCHEMA}' AND table_type='BASE TABLE';\""
  warn "[schema]   0 이 아니어야 합니다. 기동 성공은 스키마 정합의 근거가 아닙니다."
  exit 0
fi

if ! command -v psql >/dev/null 2>&1; then
  info "[schema] psql 이 없어 자동 적재를 할 수 없습니다. 아래를 <사람이> 수행하세요:"
  warn "[schema] ★ 이 적재가 테이블을 만드는 <유일한 경로>입니다(온프렘은 Flyway 미사용)."
  warn "[schema]   수행하지 않으면 테이블이 생기지 않습니다. ⚠ 그래도 backend 는 <기동에 성공합니다> —"
  warn "[schema]   기동 성공을 확인으로 삼지 마세요. 화면·배치가 DB 를 건드리는 순간 전부 실패합니다."
  cat <<TXT

  # 빈 데이터베이스에 전체 스키마(+시드)를 1회 적재
  #   → ${DB_SCHEMA} 스키마가 파일 안에서 생성되므로 사전 CREATE SCHEMA 불필요.
  psql -h <HOST> -p <PORT> -U ${DB_APP_USER} -d ${CONTROL_DB_NAME} \\
       -v ON_ERROR_STOP=1 -f "${SCHEMA_SQL}"

  # 적재 확인 — 기동 성공이 아니라 <개수>로 판정한다
  psql -h <HOST> -p <PORT> -U ${DB_APP_USER} -d ${CONTROL_DB_NAME} \\
       -c "select count(*) from information_schema.tables where table_schema='${DB_SCHEMA}';"
TXT
  exit 0
fi

require_cmd psql
[[ -f "${SCHEMA_SQL}" ]] || die "[schema] schema.sql 을 찾을 수 없습니다: ${SCHEMA_SQL}"
DB_APP_PASSWORD="${DB_APP_PASSWORD:-${CONTROL_DB_PASSWORD:-}}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD 또는 CONTROL_DB_PASSWORD 가 필요합니다(앱 유저 비밀번호)}"
export PGHOST="${PGHOST:-${CONTROL_DB_HOST:-127.0.0.1}}"
export PGPORT="${PGPORT:-${CONTROL_DB_PORT:-5432}}"
export PGPASSWORD="${DB_APP_PASSWORD}"

# 멱등 가드: 대상 스키마에 이미 BASE TABLE 이 있으면 로드하지 않는다(중복 로드/덮어쓰기 방지).
#   스키마가 아직 없으면 count=0 이 되어 정상적으로 로드가 진행된다(schema.sql 이 CREATE SCHEMA 포함).
# ★ 설치 도구가 소유한 표는 세지 않는다 (2026-09-06 신설)
#   apply-migrations.sh 가 만드는 적용 이력 표(ls_schm_aplcn_hstry)는 schema.sql 에 없다 —
#   Flyway 마이그레이션이 아니라 설치 도구의 메타 표이고 앱은 읽지 않는다.
#   그대로 세면 <기대 +1> 이 되어 아래 판정이 매번 "기대와 다릅니다"로 갈라진다.
#   ⚠ 이것은 진짜 불일치를 가리는 예외가 아니다 — 이 이름 하나만 뺀다.
existing="$(psql -tAX -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='${DB_SCHEMA}' AND table_type='BASE TABLE' AND table_name <> 'ls_schm_aplcn_hstry';" \
  2>/dev/null || echo "ERR")"

if [[ "${existing}" == "ERR" ]]; then
  die "[schema] DB 접속 실패 — host=${PGHOST}:${PGPORT} user=${DB_APP_USER} db=${CONTROL_DB_NAME}"
fi
# ★ 매체가 기대하는 객체 수 — 하드코딩하지 않고 적재할 파일에서 센다(마이그레이션이 늘면 낡는다).
#   information_schema.tables 는 BASE TABLE 과 VIEW 를 함께 세므로 둘을 더한다.
_want_t="$(grep -c '^CREATE TABLE klid_at\.' "${SCHEMA_SQL}" 2>/dev/null || echo 0)"
_want_v="$(grep -c '^CREATE VIEW klid_at\.'  "${SCHEMA_SQL}" 2>/dev/null || echo 0)"
_want=$(( _want_t + _want_v ))

# ★★ 적재는 조건부다 — 현장 담당 선적용 우선 + 우리가 채움 (2026-09-05 확정)
#     ① 비어 있으면        → 우리가 적재한다
#     ② 이미 있고 기대와 맞으면 → 건너뛴다 (현장 담당이 미리 적용해 둔 경우)
#     ③ 이미 있는데 다르면  → <덮어쓰지 않고> 멈춘다. 현장 데이터가 사라질 수 있다.
#   즉 현장 담당의 선적용 관행과 우리 적재가 둘 다 성립하며, 어느 쪽이 먼저 했든 결과가 같다.
#   ⚠ 데이터베이스 서버 설치와 데이터베이스·계정 생성은 우리 일이 <아니다>(현장 선행 조건).
#     그것과 이 스키마 적재를 같은 것으로 읽지 말 것.
if [[ "${existing}" != "0" ]]; then
  if [[ "${_want}" -gt 0 && "${existing}" == "${_want}" ]]; then
    ok "[schema] 이미 적재돼 있고 개수가 기대값과 같습니다(${existing}개) — 건너뜁니다."
    exit 0
  fi
  warn "[schema] ★ ${DB_SCHEMA} 에 이미 객체 ${existing}개가 있는데 기대값과 다릅니다(기대 ${_want}개)."
  warn "[schema]   <덮어쓰지 않습니다> — 현장 데이터가 사라질 수 있습니다."
  warn "[schema]   이전 반입 형상이거나 부분 적재일 수 있습니다. 현장 데이터베이스 담당과 함께"
  warn "[schema]   어긋난 지점을 확인하세요(db/README.md 의 증분 적용 규칙)."
  exit 0
fi

# ★ 구 스키마 잔존 가드(fail-closed) — 대상 스키마는 비었는데 public 에 저작도구 테이블이 있으면
#   스키마 전환 <이전 형상>의 DB 다. 여기서 그냥 로드하면 <빈 대상 스키마가 새로 생기고> 데이터는
#   public 에 남아, 앱이 오류 없이 빈 스키마를 보는 조용한 분기가 된다. 로드 대신 이관을 안내한다.
if [[ "${DB_SCHEMA}" != "public" ]]; then
  legacy="$(psql -tAX -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" \
    -c "SELECT count(*) FROM information_schema.tables
        WHERE table_schema='public' AND table_type='BASE TABLE'
          AND (table_name LIKE 'ls\_%' OR table_name LIKE 'mng\_%' OR table_name LIKE 'qrtz\_%');")"
  if [[ "${legacy}" != "0" ]]; then
    die "[schema] public 에 저작도구 테이블 ${legacy}개가 남아 있고 ${DB_SCHEMA} 는 비어 있습니다.
     신규 설치가 아니라 <스키마 이관> 대상입니다. 여기서 로드하면 빈 ${DB_SCHEMA} 만 생기고
     데이터는 public 에 남습니다(오류 없이 앱이 빈 스키마를 봅니다).
     → docs/09-operations-runbook.md '스키마 이관(public → ${DB_SCHEMA})' 절차를 먼저 수행하세요.
       (복사가 아니라 ALTER ... SET SCHEMA 로 <이동> + flyway_schema_history 동반 이동)"
  fi
fi

info "[schema] schema.sql 로드: host=${PGHOST}:${PGPORT} user=${DB_APP_USER} db=${CONTROL_DB_NAME} schema=${DB_SCHEMA}"
psql -v ON_ERROR_STOP=1 -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" -f "${SCHEMA_SQL}" >/dev/null
loaded="$(psql -tAX -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" \
  -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='${DB_SCHEMA}' AND table_type='BASE TABLE';")"
[[ "${loaded}" != "0" ]] \
  || die "[schema] 로드 후에도 ${DB_SCHEMA} 에 테이블이 없습니다 — schema.sql 의 대상 스키마와 DB_SCHEMA 가 다릅니다."
if [[ "${_want_t}" -gt 0 ]]; then
  ok "[schema] 로드 완료 — ${DB_SCHEMA} 테이블 ${loaded}개 (이 매체 기대: 테이블 ${_want_t} + 뷰 ${_want_v})"
else
  ok "[schema] 로드 완료 — ${DB_SCHEMA} 테이블 ${loaded}개."
fi
