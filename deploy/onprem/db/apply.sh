#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# apply.sh — [DB 서버] 이 폴더 하나만 있으면 DB 작업이 끝난다
#
#   ★ 왜 이 폴더가 자족적인가 (2026-09-07 확정)
#     DB 작업은 <DB 서버에서 직접> 하게 된다. 응용 서버에는 psql 이 없고, 매체 전체를
#     DB 서버로 옮길 이유도 없다. 그래서 이 폴더만 복사하면 되게 만든다 —
#     필요한 도구(tools/)를 매체 조립 때 함께 담는다.
#
#   ★ 두 가지가 들어 있다. 성격이 다르니 섞지 말 것.
#       schema.sql       <전체> — 빈 데이터베이스를 한 번에 세운다
#       incremental/     <증분> — 이미 선 데이터베이스에 얹는다
#     전체 파일은 그 시점까지의 증분을 <이미 품고 있다>. 전체를 넣은 뒤 같은 증분을
#     다시 실행하면 멱등하지 않은 문장에서 실패한다 — 그래서 --schema 는 적용 이력에
#     그 증분들을 <적용된 것으로> 기록한다.
#
#   사용법
#     ./apply.sh --status            현황만 (아무것도 바꾸지 않는다)
#     ./apply.sh --schema            빈 DB 를 전체 스크립트로 세운다 (최초 1회)
#     ./apply.sh --dry-run           적용 예정 증분만 보여준다
#     ./apply.sh                     미적용 증분을 번호순으로 적용
#
#   접속값 — 아래 순서로 찾는다. 손으로 칠 것이 없게 하려는 것이 요점이다.
#     ① 환경변수 (CONTROL_DB_HOST/PORT/NAME/USERNAME/PASSWORD · DB_SCHEMA)
#     ② 이 폴더의 db.env            (있으면 읽는다 — 형식은 KEY=값)
#     ③ /etc/klid/application.properties   (WAS 와 같은 장비일 때)
#   ⚠ 비밀번호는 환경변수로만 넘긴다 — 명령 이력에 남지 않는다.
# ============================================================================
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="${SELF}/tools"
[[ -d "${TOOLS}/lib" ]] || { echo "✗ tools/ 가 없습니다: ${TOOLS}" >&2
                             echo "  매체의 onprem/db 를 통째로 복사하세요(tools/ 포함)." >&2; exit 1; }
# shellcheck source=tools/lib/common.sh
source "${TOOLS}/lib/common.sh"

# ---- 접속값 ---------------------------------------------------------------
if [[ -r "${SELF}/db.env" ]]; then
  # source 하지 않는다 — 비밀번호에 ! # 공백이 들어 있으면 셸이 다르게 해석한다.
  while IFS= read -r _l; do
    [[ "${_l}" =~ ^[[:space:]]*([A-Z_][A-Z0-9_]*)=(.*)$ ]] || continue
    [[ -n "${!BASH_REMATCH[1]:-}" ]] && continue      # 환경변수가 이긴다
    export "${BASH_REMATCH[1]}=${BASH_REMATCH[2]%$'\r'}"
  done < "${SELF}/db.env"
  info "[db] db.env 에서 접속값을 읽었습니다."
fi
klid_load_db_props    # /etc/klid/application.properties (있으면)

CONTROL_DB_NAME="${CONTROL_DB_NAME:-klid_system}"
DB_SCHEMA="${DB_SCHEMA:-klid_at}"
DB_APP_USER="${DB_APP_USER:-${CONTROL_DB_USERNAME:-postgres}}"
DB_APP_PASSWORD="${DB_APP_PASSWORD:-${CONTROL_DB_PASSWORD:-}}"
export DB_APP_USER DB_APP_PASSWORD DB_SCHEMA CONTROL_DB_NAME
klid_pg_env
export PGPASSWORD="${DB_APP_PASSWORD}"

export KLID_INC_DIR="${SELF}/incremental"
require_cmd psql
# ★ -w : 비밀번호를 <물어보지 않는다>. 프롬프트가 뜨면 스크립트가 거기서 멈추는데,
#   그 상태는 "느리다"로만 보여 원인을 알기 어렵다. 값이 없으면 빨리 실패하는 편이 낫다.
#   (trust/peer 인증이면 비밀번호 없이도 붙으므로 값 자체를 강제하지는 않는다.)
export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-10}"
PSQL=(psql -w -U "${DB_APP_USER}" -d "${CONTROL_DB_NAME}" -v ON_ERROR_STOP=1)

MODE="apply"
for a in "$@"; do
  case "${a}" in
    --status|--dry-run) MODE="${a#--}" ;;
    --schema)           MODE="schema" ;;
    --help|-h)          sed -n '3,35p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "알 수 없는 옵션: ${a} (--help 로 사용법)" ;;
  esac
done

info "[db] 접속 ${PGHOST}:${PGPORT} · DB=${CONTROL_DB_NAME} · 계정=${DB_APP_USER} · 스키마=${DB_SCHEMA}"
if ! "${PSQL[@]}" -tAc "select 1" >/dev/null 2>&1; then
  warn "[db] 접속 실패 — host=${PGHOST} port=${PGPORT} db=${CONTROL_DB_NAME} user=${DB_APP_USER}"
  [[ -n "${DB_APP_PASSWORD}" ]] || warn "     비밀번호가 비어 있습니다(설정을 못 찾았을 수 있습니다)."
  die  "[db] 이 폴더에 db.env 를 만들어 두면 다음부터 아무것도 치지 않아도 됩니다 — README-apply.md 참고."
fi

_count() { "${PSQL[@]}" -tAc "select count(*) from information_schema.tables
                              where table_schema='${DB_SCHEMA}'" 2>/dev/null || echo 0; }

# ---- 전체 스크립트 --------------------------------------------------------
if [[ "${MODE}" == "schema" ]]; then
  SCHEMA="${SELF}/schema.sql"
  [[ -f "${SCHEMA}" ]] || die "[db] 전체 스크립트가 없습니다: ${SCHEMA}"
  _now="$(_count)"
  if [[ "${_now}" -gt 0 ]]; then
    warn "[db] ★ ${DB_SCHEMA} 에 이미 ${_now}개가 있습니다."
    warn "     --schema 는 <빈 데이터베이스>에 쓰는 것입니다. 이미 선 곳에는 증분을 얹으세요."
    warn "     정말 새로 세우려면 사람이 먼저 스키마를 정리해야 합니다(되돌릴 수 없습니다)."
    die  "[db] 진행하지 않습니다."
  fi
  info "[db] 전체 스크립트 적재: $(basename "${SCHEMA}")  ($(wc -l < "${SCHEMA}") 줄)"
  # ★ search_path 를 걸 필요가 없다 — 이 파일은 객체명이 스키마로 한정돼 있다.
  #   (증분은 반대다. 그래서 아래 적용 경로는 반드시 search_path 를 건다.)
  "${PSQL[@]}" -q -f "${SCHEMA}" || die "[db] 적재 실패 — 위 오류를 확인하세요."
  ok "[db] 적재 완료 — ${DB_SCHEMA} 표·뷰 $(_count)개"
  # ★ 전체 파일은 증분을 이미 품고 있다. 그 사실을 이력에 남긴다 —
  #   안 남기면 다음 회차에 같은 증분이 미적용으로 보여 다시 실행된다.
  info "[db] 이 전체 파일에 포함된 증분을 <적용된 것으로> 기록합니다."
  "${TOOLS}/apply-migrations.sh" --mark-only
  ok "[db] 끝났습니다. 다음 회차부터는 ./apply.sh 로 증분만 얹으면 됩니다."
  exit 0
fi

# ---- 증분 ------------------------------------------------------------------
case "${MODE}" in
  status)  exec "${TOOLS}/apply-migrations.sh" --status ;;
  dry-run) exec "${TOOLS}/apply-migrations.sh" --dry-run ;;
esac

_now="$(_count)"
if [[ "${_now}" -eq 0 ]]; then
  warn "[db] ${DB_SCHEMA} 가 비어 있습니다 — 증분을 얹을 대상이 없습니다."
  die  "[db] 빈 데이터베이스라면 먼저:  ./apply.sh --schema"
fi
warn "[db] 운영 데이터베이스를 변경합니다. 증분은 <되돌아가지 않습니다>."
warn "     백업을 현장 절차로 떠 두었는지 확인하세요 — 이 스크립트는 백업을 뜨지 않습니다."
confirm "계속할까요?" || die "[db] 사용자가 중단했습니다."
exec "${TOOLS}/apply-migrations.sh"
