#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# render-frontend-config.sh — 프론트엔드 <런타임 설정> 생성기
#
#   정본  : /etc/klid/frontend.env            (운영자가 고치는 유일한 파일)
#   생성물: <web root>/klid-config.js         (브라우저가 받는 파일 — 생성물이다)
#
#   ★ 왜 있나 — Vite 는 VITE_* 를 <빌드 시점>에 정적 치환한다. 그래서 상위 시스템 로그인
#     주소처럼 환경마다 다른 값이 산출물에 굳어, 폐쇄망 반입(빌드머신이 고객 환경의 실주소를
#     모른다)에서는 배포 가능한 산출물 자체를 만들 수 없었다. 실제로 예시 주소가 구워진 dist 가
#     반입 대상으로 놓여 있었다. 이제 값의 정본은 <대상 서버의 설정 디렉터리>이며,
#     백엔드가 DB 접속정보를 /etc/klid 에서 읽는 것과 같은 관례다.
#
#   ★★ 이 생성물은 <브라우저로 그대로 내려간다>. 그래서 정본을 통째로 내보내지 않고
#      아래 ALLOWED_KEYS 에 <명시 열거된 키만> 싣는다(allowlist). /etc/klid 에는 DB 비밀번호·
#      토큰 검증 비밀값·웹훅 서명값이 함께 사는데, denylist 로 하면 <새로 생긴 비밀값>이
#      목록에 없다는 이유로 그대로 새어 나간다. 새 값을 노출하려면 사람이 이 목록에 추가해야
#      하고, 그 행위 자체가 검토 지점이 된다.
#
#   ★ fail-closed 는 여기 있다 — 예전에는 <빌드>가 로그인 주소를 요구했다(require_upstream_login_urls).
#     빌드를 환경 무관으로 바꾸면서 그 가드를 이 자리로 옮겼다. 방어가 사라진 것이 아니라
#     설치·반영 시점으로 이동한 것이다. 값이 없으면 생성하지 않고 <비정상 종료>한다.
#
#   사용법:
#     sudo /opt/klid/bin/klid-frontend-config          # 정본을 고친 뒤 반영(재빌드·재설치 불필요)
#     KLID_ETC=... WEB_ROOT=... ./render-frontend-config.sh
#
#   환경변수:
#     KLID_ETC     설정 정본 디렉터리 (기본 /etc/klid)
#     KLID_PREFIX  설치 루트          (기본 /opt/klid)
#     WEB_ROOT     생성물을 놓을 문서 루트 (기본 ${KLID_PREFIX}/web/dist)
#     CONFIG_FILE  정본 파일 경로     (기본 ${KLID_ETC}/frontend.env)
# ============================================================================

KLID_ETC="${KLID_ETC:-/etc/klid}"
KLID_PREFIX="${KLID_PREFIX:-/opt/klid}"
WEB_ROOT="${WEB_ROOT:-${KLID_PREFIX}/web/dist}"
CONFIG_FILE="${CONFIG_FILE:-${KLID_ETC}/frontend.env}"
OUT_FILE="${WEB_ROOT}/klid-config.js"

# 앱이 읽는 전역 이름. frontend/src/lib/runtimeConfig.ts 의 RUNTIME_CONFIG_GLOBAL 과 같아야 한다
# (셸이라 import 할 수 없어, 프론트 테스트가 두 값이 같은지 고정한다).
GLOBAL_NAME='__KLID_RUNTIME_CONFIG__'

# ---- allowlist — 브라우저로 내보낼 키 (이 목록 밖은 절대 나가지 않는다) ----
ALLOWED_KEYS=(
  VITE_API_BASE_URL
  VITE_TOKEN_INGRESS
  VITE_CONTROL_LOGIN_URL
  VITE_PORTAL_LOGIN_URL
  VITE_DEV_LOGIN_ENABLED
  VITE_DEV_UPLOAD_ENABLED
)

# ---- 필수 키 — 없거나 스킴이 빠지면 생성하지 않는다 ----
#   저작도구는 자체 로그인 UI 가 없어 이 값이 비면 세션 만료 시 이동할 곳이 없다.
#   스킴이 없으면 브라우저가 상대경로로 해석해 저작도구 자기 자신으로 되돌아온다.
REQUIRED_URL_KEYS=(VITE_CONTROL_LOGIN_URL VITE_PORTAL_LOGIN_URL)

_info() { printf '[frontend-config] %s\n' "$*"; }
_warn() { printf '[frontend-config] 경고: %s\n' "$*" >&2; }
_die()  { printf '[frontend-config] 실패: %s\n' "$*" >&2; exit 1; }

[[ -f "${CONFIG_FILE}" ]] || _die "설정 정본이 없습니다: ${CONFIG_FILE}
  (설치가 템플릿을 배치합니다 — deploy/onprem/config/frontend/frontend.env.template)"
[[ -d "${WEB_ROOT}" ]] || _die "웹 문서 루트가 없습니다: ${WEB_ROOT}"

# ---- 값 읽기 ----
#   ★ 정본을 `source` 하지 않는다 — 그러면 설정 파일이 <임의 셸 코드>가 되고, allowlist 도
#     "실행 뒤에 고르는" 형태가 되어 늦다. 키=값 형태만 문자 그대로 읽는다.
#     같은 키가 여러 번 나오면 <마지막 것>이 이긴다(셸 파일의 통상 해석과 같다).
#   ⚠ 같은 줄 주석(`KEY=값 # 설명`)은 <값의 일부>가 된다 — 잘라내면 URL 조각(`#fragment`)을
#     값으로 쓸 수 없게 되기 때문이다. 템플릿 머리말이 이 제약을 안내한다.
read_key() {
  local key="$1" line
  line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "${CONFIG_FILE}" | tail -n 1 || true)"
  [[ -n "${line}" ]] || { printf '%s' ''; return 0; }
  line="${line#*=}"
  # 양끝 공백 제거 → 감싼 따옴표 한 겹 제거 (KEY="값" / KEY='값' 모두 허용)
  line="$(printf '%s' "${line}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  case "${line}" in
    \"*\") line="${line:1:${#line}-2}" ;;
    \'*\') line="${line:1:${#line}-2}" ;;
  esac
  printf '%s' "${line}"
}

is_allowed() {
  local k
  for k in "${ALLOWED_KEYS[@]}"; do [[ "${k}" == "$1" ]] && return 0; done
  return 1
}

# ---- allowlist 밖 키 안내 ----
#   조용히 버리면 "왜 값이 안 나오지"를 아무도 모른다. 반대로 <값은 절대 찍지 않는다> —
#   여기 걸리는 키가 곧 비밀값일 수 있다(그래서 걸러내는 것이다). 키 이름만 남긴다.
while IFS= read -r _k; do
  [[ -n "${_k}" ]] || continue
  is_allowed "${_k}" || _warn "허용 목록 밖 키라 내보내지 않습니다(무시): ${_k}"
done < <(grep -E '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=' "${CONFIG_FILE}" \
           | sed -e 's/[[:space:]]*=.*$//' -e 's/^[[:space:]]*//' | sort -u)

# ---- 필수 값 검증 (fail-closed) ----
_missing=0
for _k in "${REQUIRED_URL_KEYS[@]}"; do
  _v="$(read_key "${_k}")"
  if [[ -z "${_v}" ]]; then
    _warn "${_k} 미설정 — 세션 만료 시 상위 로그인 페이지로 이동할 수 없습니다."
    _missing=1
  elif [[ "${_v}" != http://* && "${_v}" != https://* ]]; then
    _warn "${_k} 값에 스킴이 없습니다 — http:// 또는 https:// 로 시작해야 합니다."
    _missing=1
  fi
done
[[ "${_missing}" -eq 0 ]] || _die "상위 시스템 로그인 URL 이 필요합니다. ${CONFIG_FILE} 에 채우세요. 예:
       VITE_CONTROL_LOGIN_URL=https://control.example.local/login
       VITE_PORTAL_LOGIN_URL=https://portal.example.local/login
     (deploy/onprem/docs/04-configuration.md 'frontend 런타임 설정' 참고)"

# ---- JS 문자열 이스케이프 ----
#   값은 URL·플래그라 한 줄이지만, 역슬래시·따옴표가 섞이면 생성물이 문법 오류가 되어
#   <설정이 통째로 무시된다>(그 실패는 화면에서 조용하다). 반드시 감싸서 내보낸다.
js_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

_tmp="$(mktemp "${OUT_FILE}.XXXXXX")"
trap 'rm -f "${_tmp}"' EXIT

{
  printf '/*\n'
  printf ' * klid-label 프론트엔드 런타임 설정 — <생성물이다. 직접 고치지 마라.>\n'
  printf ' *   다음 생성에서 덮인다. 값은 %s 에서 고치고\n' "${CONFIG_FILE}"
  printf ' *   %s 를 다시 실행한다(재빌드·재설치 불필요).\n' "${KLID_PREFIX}/bin/klid-frontend-config"
  printf ' *\n'
  printf ' * ★ 이 파일은 브라우저로 그대로 내려간다 — 비밀값을 넣는 자리가 아니다.\n'
  printf ' *   내보낼 키는 생성기의 allowlist 로만 정해지며, 목록 밖 키는 실리지 않는다.\n'
  printf ' *\n'
  printf ' * 생성: %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')"
  printf ' */\n'
  printf 'window.%s = {\n' "${GLOBAL_NAME}"
  _emitted=0
  for _k in "${ALLOWED_KEYS[@]}"; do
    _v="$(read_key "${_k}")"
    # 값이 없는 키는 아예 싣지 않는다 — 빈 문자열을 실으면 앱이 "미설정"과 구분하려다
    # 빌드 기본값까지 잃는다(앱은 빈 값을 미설정으로 해석하지만, 안 싣는 쪽이 뜻이 분명하다).
    [[ -n "${_v}" ]] || continue
    printf '  "%s": "%s",\n' "${_k}" "$(js_escape "${_v}")"
    _emitted=$((_emitted + 1))
  done
  printf '};\n'
} > "${_tmp}"

# 생성 실패(디스크 가득 등)로 반쪽 파일이 남지 않게 원자 교체한다.
#   ★ 임시 파일을 <같은 디렉터리>에 만드는 것이 중요하다 — SELinux Enforcing 장비에서 파일
#     문맥은 생성 위치의 부모에서 상속된다. /tmp 에 만들어 옮기면 httpd 가 못 읽어(403)
#     "설정만 안 먹는" 상태가 된다. 설치의 restorecon 은 최초 1회뿐이고 재생성 때는 돌지 않는다.
chmod 0644 "${_tmp}"
mv -f "${_tmp}" "${OUT_FILE}"
trap - EXIT

_info "생성: ${OUT_FILE}  (정본 ${CONFIG_FILE})"
_info "★ 웹 서버가 이 파일을 캐시하지 않아야 값 변경이 즉시 반영된다(설정 템플릿의 Cache-Control no-store)."
