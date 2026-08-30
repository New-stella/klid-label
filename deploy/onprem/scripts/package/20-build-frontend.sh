#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 20-build-frontend.sh — [빌드머신] frontend(React+Vite) 정적 빌드 + 수집
#
#   ★ Vite 는 VITE_* 변수를 "빌드 시점"에 정적 치환한다. 따라서 API base 와
#     토큰 인입 모드는 여기서 주입해야 한다(대상 서버에서 변경 불가).
#       VITE_API_BASE_URL : 프론트가 호출할 API base. 웹 서버(httpd)가 /api 를
#                           backend(127.0.0.1:8080)로 프록시하므로 '/api/v1'.
#       VITE_TOKEN_INGRESS: 토큰 인계 채널(기본 localStorage).
#                           'url'/'both'/'all' 은 JWT 를 URL 쿼리에 싣는 채널을 열어
#                           접근 로그·리퍼러·히스토리에 토큰이 잔존한다(CWE-598).
#                           레거시 호환이 필요한 현장만 명시 override.
#       VITE_CONTROL_LOGIN_URL / VITE_PORTAL_LOGIN_URL:
#                           세션 만료·401 시 이동할 상위 시스템 로그인 페이지.
#                           ★ 기본값 없음 — 미설정이면 빌드를 중단한다(fail-closed).
#   결과: frontend/dist → artifacts/frontend/dist
#
#   ★ 제3자 라이선스 고지도 여기서 함께 수집한다 (2026-08-30 신설).
#     dist 에는 <폰트 바이너리 2,166개>(Pretendard·D2Coding)와 번들된 npm 패키지 코드가
#     실린다. 둘 다 재배포이므로 고지가 따라가야 하는데, 2026-08-30 이전까지 dist 안에
#     라이선스 파일이 <0건>이었다.
#     산출: licenses/frontend/…              (매체 루트의 고지 모음)
#           artifacts/frontend/dist/licenses/ (웹으로도 열람 가능하도록 산출물 안에 동봉)
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/licenses.sh
source "${SELF_DIR}/../lib/licenses.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"
FE_SRC="${REPO}/frontend"
OUT="${ONPREM}/artifacts/frontend"

[[ -d "${FE_SRC}" ]] || die "frontend 디렉토리를 찾을 수 없습니다: ${FE_SRC}"
require_cmd node npm
ensure_dir "${OUT}"

# 빌드 시점 주입 변수(필요 시 환경변수로 override 가능)
export VITE_API_BASE_URL="${VITE_API_BASE_URL:-/api/v1}"
export VITE_TOKEN_INGRESS="${VITE_TOKEN_INGRESS:-localStorage}"
# 온프렘 번들은 dev 라우트(/dev/login·/dev/upload)를 dist 에 포함하되,
# 실제 게이팅은 BE DEV_LOGIN_ENABLED / DEV_UPLOAD_ENABLED 런타임 토글이 결정한다
# (FE 라우트만 존재, BE off 면 /v1/dev/* 호출 시 404). 관제서버 미기동 브링업 대비.
export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
export VITE_DEV_UPLOAD_ENABLED="${VITE_DEV_UPLOAD_ENABLED:-true}"
# 상위 시스템 로그인 URL(H-ISSUE-02) — 기본값 없이 fail-closed. 비면 세션 만료 시 막다른 화면.
export VITE_CONTROL_LOGIN_URL="${VITE_CONTROL_LOGIN_URL:-}"
export VITE_PORTAL_LOGIN_URL="${VITE_PORTAL_LOGIN_URL:-}"
require_upstream_login_urls
info "[frontend] VITE_API_BASE_URL=${VITE_API_BASE_URL} VITE_TOKEN_INGRESS=${VITE_TOKEN_INGRESS} VITE_DEV_LOGIN_ENABLED=${VITE_DEV_LOGIN_ENABLED} VITE_DEV_UPLOAD_ENABLED=${VITE_DEV_UPLOAD_ENABLED}"
info "[frontend] VITE_CONTROL_LOGIN_URL=${VITE_CONTROL_LOGIN_URL} VITE_PORTAL_LOGIN_URL=${VITE_PORTAL_LOGIN_URL}"

info "[frontend] 의존성 설치 (npm ci)..."
( cd "${FE_SRC}" && npm ci )

info "[frontend] 프로덕션 빌드 (npm run build)..."
( cd "${FE_SRC}" && npm run build )

[[ -d "${FE_SRC}/dist" ]] || die "빌드 결과(dist)를 찾을 수 없습니다: ${FE_SRC}/dist"

rm -rf "${OUT}/dist"
cp -R "${FE_SRC}/dist" "${OUT}/dist"
ok "[frontend] 수집: ${OUT}/dist  ($(du -sh "${OUT}/dist" | cut -f1))"

# ---- 제3자 라이선스 고지 수집 (폰트 + 번들 npm 패키지) ----------------------
#   ★ 대상 판정은 <npm 이 알려주는 production 의존성 트리>다. devDependencies 는 dist 에
#     실리지 않으므로 고지 대상이 아니고, 목록을 손으로 적으면 package.json 이 바뀔 때 낡는다.
#   ★ 라이선스 파일은 패키지 루트에만 있는 것이 아니다 — pretendard-gov 는 dist/LICENSE.txt 에
#     둔다. 루트만 보면 <가장 중요한 폰트 고지를 놓친다>(실측). 그래서 depth 2 까지 훑고
#     <그 패키지 안에 중첩된> node_modules 만 제외한다(중첩분은 트리에서 따로 잡힌다).
#     ⚠ 제외 패턴을 '*/node_modules/*' 로 쓰면 안 된다 — 패키지 경로 자체가 node_modules
#       아래라 <전부> 걸려 수집이 0건이 된다(실측으로 밟았다). 반드시 ${_pkg} 로 앵커한다.
FE_LIC_DIR="$(lic_root)/frontend"
lic_reset_area "frontend"
FE_INV="${FE_LIC_DIR}/INVENTORY.tsv"
lic_inventory_init "${FE_INV}"

_fe_pkgs="$(mktemp)"
( cd "${FE_SRC}" && npm ls --omit=dev --parseable --all 2>/dev/null | tail -n +2 ) > "${_fe_pkgs}" || true
if [[ ! -s "${_fe_pkgs}" ]]; then
  warn "[frontend] npm ls 로 production 의존성 목록을 얻지 못했습니다 — 폰트 고지만 수집합니다."
fi

_fe_n=0; _fe_txt=0; _fe_nam=0; _fe_unres=0
while IFS= read -r _pkg; do
  [[ -d "${_pkg}" ]] || continue
  _name="${_pkg#*/node_modules/}"
  _slug="$(lic_slug "${_name}")"
  _fe_n=$((_fe_n+1))
  _cnt=0
  while IFS= read -r _f; do
    [[ -n "${_f}" ]] || continue
    mkdir -p "${FE_LIC_DIR}/npm/${_slug}"
    cp -f "${_f}" "${FE_LIC_DIR}/npm/${_slug}/$(lic_slug "${_f#"${_pkg}"/}")" && _cnt=$((_cnt+1))
  done < <(find "${_pkg}" -maxdepth 2 -type f \
             \( -iname 'LICENSE' -o -iname 'LICENSE.*' -o -iname 'LICENCE' -o -iname 'LICENCE.*' \
                -o -iname 'COPYING' -o -iname 'COPYING.*' -o -iname 'NOTICE' -o -iname 'NOTICE.*' \) \
             -not -path "${_pkg}/node_modules/*" 2>/dev/null | sort)
  # package.json 의 license 필드(이름만) — 전문이 없을 때의 유일한 단서다.
  _lic="$(node -e 'try{const p=require(process.argv[1]+"/package.json");const l=p.license||p.licenses;process.stdout.write(typeof l==="string"?l:JSON.stringify(l||""))}catch(e){}' "${_pkg}" 2>/dev/null || true)"
  _ver="$(node -e 'try{process.stdout.write(require(process.argv[1]+"/package.json").version||"")}catch(e){}' "${_pkg}" 2>/dev/null || true)"
  if [[ "${_cnt}" -gt 0 ]]; then
    _fe_txt=$((_fe_txt+1)); _src="ARCHIVE"; _st="OK"
  elif [[ -n "${_lic}" ]]; then
    _fe_nam=$((_fe_nam+1)); _src="METADATA"; _st="TEXT_MISSING"
  else
    _fe_unres=$((_fe_unres+1)); _src="NONE"; _st="UNRESOLVED"
  fi
  lic_inventory_add "${FE_INV}" frontend "${_name}" "${_ver}" "${_lic}" "${_src}" "${_st}"
done < "${_fe_pkgs}"
rm -f "${_fe_pkgs}"

# ---- 폰트: D2Coding 은 npm 패키지에 전문이 없어 <사람이 채운 사본>을 쓴다 ----
#   ★ 이 한 건만 수동이다. d2coding@1.3.2 에는 LICENSE/OFL 파일이 0건이고(실측),
#     폰트 name 테이블만 SIL OFL 1.1 이라고 말한다. 자동 수집으로는 전문을 얻을 수 없다.
_D2_SRC="$(lic_root)/manual/fonts/D2Coding-OFL-1.1.txt"
if [[ -f "${_D2_SRC}" ]]; then
  mkdir -p "${FE_LIC_DIR}/npm/d2coding"
  cp -f "${_D2_SRC}" "${FE_LIC_DIR}/npm/d2coding/OFL-1.1.txt"
  # 위 루프가 이미 TEXT_MISSING 으로 적었으므로 그 행을 걷어내고 MANUAL/OK 로 다시 적는다.
  #   ⚠ grep -P 는 BSD grep(mac)에 없다 — awk 로만 처리한다(빌드머신이 mac 인 경우 대비).
  _tmp_inv="$(mktemp)"
  awk -F'\t' 'NR==1 || !($1=="frontend" && $2=="d2coding")' "${FE_INV}" > "${_tmp_inv}"
  mv "${_tmp_inv}" "${FE_INV}"
  lic_inventory_add "${FE_INV}" frontend "d2coding" "" "OFL-1.1" "MANUAL" "OK"
  ok "[frontend] D2Coding OFL 전문 동봉(수동 사본): ${FE_LIC_DIR}/npm/d2coding/OFL-1.1.txt"
else
  warn "[frontend] D2Coding OFL 전문이 없습니다: ${_D2_SRC}"
  warn "  dist 에 D2Coding 폰트 바이너리가 실리는데 라이선스 텍스트 없이 재배포하게 됩니다."
fi

ok "[frontend] 라이선스 고지 수집: ${FE_LIC_DIR} (패키지 ${_fe_n} / 전문 ${_fe_txt} / 이름만 ${_fe_nam} / 미해석 ${_fe_unres})"
[[ "${_fe_nam}" -gt 0 || "${_fe_unres}" -gt 0 ]] && \
  warn "[frontend] 전문 없는 항목이 있습니다 — 70-generate-notices.sh 의 UNRESOLVED 목록을 확인하세요."

# ---- 산출물(dist) 안에도 고지를 동봉한다 ------------------------------------
#   ★ dist 는 웹 서버가 그대로 서빙하는 디렉터리다. 매체 루트의 licenses/ 는 설치 담당자만
#     보지만, 여기 넣으면 운영 중에도 확인할 수 있다(폰트 재배포 고지의 실질적 접근성).
rm -rf "${OUT}/dist/licenses"
mkdir -p "${OUT}/dist/licenses"
cp -R "${FE_LIC_DIR}/." "${OUT}/dist/licenses/" 2>/dev/null || true
ok "[frontend] 산출물 동봉: ${OUT}/dist/licenses ($(du -sh "${OUT}/dist/licenses" 2>/dev/null | cut -f1))"

# ★ SHA256SUMS 는 licenses/ 를 동봉한 <뒤에> 써야 한다. 앞에서 쓰면 목록에서 빠져
#   install.sh 의 무결성 검증이 고지 파일만 검증 없이 통과시킨다(10-build-backend.sh 의
#   WAR 순서 사고와 같은 형태다).
sha256_write "${OUT}/dist"
