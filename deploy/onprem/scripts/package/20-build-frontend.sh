#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 20-build-frontend.sh — [빌드머신] frontend(React+Vite) 정적 빌드 + 수집
# @step 인터넷=필요(npm ci) | 소요=수 분 | 선행=없음 | 재실행=안전(매번 재빌드 — 빌드 성공 뒤에만 dist 를 교체한다)
#
#   ★★ 이 산출물은 <환경 무관>이다 (2026-08-30 전환). 상위 시스템 로그인 주소·개발용 화면
#     토글처럼 현장마다 다른 값은 더 이상 빌드에 굽지 않고, 대상 서버의 설정 정본
#     (/etc/klid/frontend.env)에서 <런타임>에 읽는다. 폐쇄망 반입은 빌드머신에서 한 번 만들어
#     매체로 넘기는 모델인데, 빌드가 고객 환경의 실주소를 요구하면 <배포 가능한 산출물 자체를
#     만들 수 없다>. 실제로 예시 주소(control.example.local)가 구워진 dist 가 반입 대상으로
#     놓여 있었다.
#
#   ★ 그래서 구 fail-closed 가드(require_upstream_login_urls) 호출을 <여기서 걷어냈다>.
#     방어가 사라진 것이 아니라 <설치 시점>으로 옮겼다 —
#       deploy/onprem/scripts/install/render-frontend-config.sh 가 필수 값이 비면 생성을 거부하고,
#       14-install-frontend.sh 가 그 실패로 설치를 중단한다.
#     ⚠ 되살리지 말 것. 되살리면 위의 "환경 무관 산출물"이 다시 성립하지 않는다.
#
#   ★ 아래 VITE_* 는 <런타임 값이 없을 때의 기본값>으로만 남는다(개발·컨테이너 경로 보존).
#       VITE_API_BASE_URL : 웹 서버(httpd)가 /api 를 backend 로 프록시하므로 '/api/v1'.
#       VITE_TOKEN_INGRESS: 토큰 인계 채널 기본값(localStorage).
#                           'url'/'both'/'all' 은 JWT 를 URL 쿼리에 싣는 채널을 열어
#                           접근 로그·리퍼러·히스토리에 토큰이 잔존한다(CWE-598).
#   ★★ 화면 산출물은 <배포 향마다 따로> 만든다 (2026-08-31 신설).
#     라우트 채널 값(VITE_BUILD_CHANNEL)은 <빌드 시점>에 굳어 반대 향 화면 코드를 산출물에서
#     통째로 걷어낸다 — 관제 산출물에는 /portal 라우트가 0건이고, 포털 산출물에는 내부
#     화면(/dashboard·/admin/*·/manage/*)이 0건이다. 그래서 <한 번만 빌드하면> 포털향 설치에
#     포털 화면이 하나도 없는 산출물이 올라간다. 빌드도 설치도 성공하고 오류도 없어
#     <조용히> 어긋난다(04-configuration.md D-4 가 이 증상을 못 박았다: "머리 영역이 겹치고
#     화면이 뜨지 않는다"). 설계 근거는 INT-013.
#     ⚠ 채널을 <명시하지 않은> 빌드는 기본값인 관제로 접힌다. 그래서 포털 산출물은 반드시
#       `npm run build:portal` 로 만든다 — `npm run build` 로는 절대 나오지 않는다.
#
#   ★ 매체에는 두 벌을 <함께> 싣고, 어느 것을 까는지는 <설치>가 KLID_DEPLOY_FLAVOR 로 고른다.
#     향마다 매체를 따로 뜨는 안은 택하지 않았다 — 매체는 이미 수 GB 규모라 화면 산출물
#     증분이 무시할 수준인 반면, 매체가 두 종류가 되면 반입·검수·자료실 분할 절차가 통째로
#     두 배가 된다(3GB 파일 제한 때문에 이미 분할돼 있다).
#     ⚠ 대가: 프론트엔드 빌드 시간이 <약 2배>가 된다(채널당 1회). 백엔드·수집 단계는 그대로다.
#
#   결과: artifacts/frontend/dist/control/   ← 관제 연동 배포용 (npm run build:control)
#         artifacts/frontend/dist/portal/    ← 포털 연동 배포용 (npm run build:portal)
#         artifacts/frontend/BUILD-INFO.txt (이 빌드에 실제로 들어간 값 — dist 만 보고는
#         무엇으로 구워졌는지 알 수 없어, 확인하려면 번들 JS 를 grep 해야 했다)
#     ★ 무결성 단위는 종전대로 <artifacts/frontend/dist> 하나다 — SHA256SUMS 가 그 아래를
#       재귀로 훑으므로 install.sh 의 검증 목록(VERIFY_DIRS)은 바뀌지 않는다.
#
#   ★ 제3자 라이선스 고지도 여기서 함께 수집한다 (2026-08-30 신설).
#     dist 에는 <폰트 바이너리 2,166개>(Pretendard·D2Coding)와 번들된 npm 패키지 코드가
#     실린다. 둘 다 재배포이므로 고지가 따라가야 하는데, 2026-08-30 이전까지 dist 안에
#     라이선스 파일이 <0건>이었다.
#     산출: licenses/frontend/…              (매체 루트의 고지 모음)
#           artifacts/frontend/dist/{control,portal}/licenses/ (웹으로도 열람 가능하도록
#                                              향마다 산출물 안에 동봉)
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
# 상위 시스템 로그인 URL — <빌드에 요구하지 않는다>(위 머리말 참조). 값을 주면 런타임 설정이
# 없을 때의 폴백으로만 쓰인다. 대상 서버의 정본은 /etc/klid/frontend.env 다.
export VITE_CONTROL_LOGIN_URL="${VITE_CONTROL_LOGIN_URL:-}"
export VITE_PORTAL_LOGIN_URL="${VITE_PORTAL_LOGIN_URL:-}"
info "[frontend] VITE_API_BASE_URL=${VITE_API_BASE_URL} VITE_TOKEN_INGRESS=${VITE_TOKEN_INGRESS} VITE_DEV_LOGIN_ENABLED=${VITE_DEV_LOGIN_ENABLED} VITE_DEV_UPLOAD_ENABLED=${VITE_DEV_UPLOAD_ENABLED}"
info "[frontend] VITE_CONTROL_LOGIN_URL=${VITE_CONTROL_LOGIN_URL} VITE_PORTAL_LOGIN_URL=${VITE_PORTAL_LOGIN_URL}"

info "[frontend] 의존성 설치 (npm ci)..."
( cd "${FE_SRC}" && npm ci )

# ---- 배포 향별 빌드 ---------------------------------------------------------
#   ★ 먼저 <스테이징>에 두 벌을 모두 만들고, 둘 다 성공한 뒤에 기존 dist 를 교체한다.
#     앞서 지워 두면 두 번째 빌드가 실패했을 때 <아무 산출물도 없는> 상태가 남는다
#     (이 단계의 재실행 안전성은 "빌드 성공 뒤에만 dist 를 교체한다"에 기대고 있다).
BUILD_FLAVORS=(control portal)
STAGE="${OUT}/.dist.staging"
rm -rf "${STAGE}"
ensure_dir "${STAGE}"

for _flavor in "${BUILD_FLAVORS[@]}"; do
  info "[frontend] 프로덕션 빌드 [${_flavor}] (npm run build:${_flavor})..."
  # 앞 채널의 산출물이 섞이지 않게 매번 비우고 시작한다. vite 가 outDir 를 비우기는 하지만,
  # 그 기본 동작에 기대면 설정 한 줄로 조용히 깨진다(채널이 섞인 산출물은 눈으로 안 보인다).
  rm -rf "${FE_SRC}/dist"
  ( cd "${FE_SRC}" && npm run "build:${_flavor}" ) \
    || die "[frontend] ${_flavor} 채널 빌드 실패 — 위 오류를 확인하세요."
  [[ -d "${FE_SRC}/dist" ]] || die "빌드 결과(dist)를 찾을 수 없습니다: ${FE_SRC}/dist (${_flavor})"
  [[ -f "${FE_SRC}/dist/index.html" ]] || die "[frontend] ${_flavor} 산출물에 index.html 이 없습니다 — 빌드가 반쪽입니다."
  cp -R "${FE_SRC}/dist" "${STAGE}/${_flavor}"
  ok "[frontend] 빌드 [${_flavor}]: $(du -sh "${STAGE}/${_flavor}" | cut -f1)"
done

rm -rf "${OUT}/dist"
mv "${STAGE}" "${OUT}/dist"
ok "[frontend] 수집: ${OUT}/dist  ($(du -sh "${OUT}/dist" | cut -f1))"
for _flavor in "${BUILD_FLAVORS[@]}"; do
  info "[frontend]   · ${_flavor} → ${OUT}/dist/${_flavor}"
done

# ---- 이 빌드에 실제로 들어간 값 기록 ----------------------------------------
#   ★ 로그로만 찍으면 매체를 받은 사람은 <dist 만 보고는 무엇으로 구워졌는지 알 수 없다>.
#     실제로 확인하려면 번들 JS 를 grep 해야 했다. 산출물 옆에 남긴다.
#   ★ dist <안>이 아니라 옆에 둔다 — 이 파일은 운영 중 웹으로 공개할 이유가 없다.
#     (그래서 dist 의 SHA256SUMS 목록에는 들어가지 않는다. 무결성 대상이 아닌 참고 기록이다.)
{
  printf '# klid-label frontend 빌드 기록\n'
  printf '#   이 값들은 <런타임 설정이 없을 때의 폴백>이다. 대상 서버의 정본은\n'
  printf '#   /etc/klid/frontend.env 이며 설치 시 klid-config.js 로 생성된다.\n'
  printf 'built_at=%s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')"
  printf 'git_commit=%s\n' "$(cd "${REPO}" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  printf 'node=%s\n' "$(node -v 2>/dev/null || echo unknown)"
  # ★ 어느 향들이 들어 있는지 — dist 하위 디렉터리를 열어 보지 않고도 알 수 있어야 한다.
  #   설치는 이 목록 중 KLID_DEPLOY_FLAVOR 가 가리키는 하나만 배치한다.
  printf 'build_flavors=%s\n' "${BUILD_FLAVORS[*]}"
  printf 'VITE_API_BASE_URL=%s\n' "${VITE_API_BASE_URL}"
  printf 'VITE_TOKEN_INGRESS=%s\n' "${VITE_TOKEN_INGRESS}"
  printf 'VITE_DEV_LOGIN_ENABLED=%s\n' "${VITE_DEV_LOGIN_ENABLED}"
  printf 'VITE_DEV_UPLOAD_ENABLED=%s\n' "${VITE_DEV_UPLOAD_ENABLED}"
  printf 'VITE_CONTROL_LOGIN_URL=%s\n' "${VITE_CONTROL_LOGIN_URL}"
  printf 'VITE_PORTAL_LOGIN_URL=%s\n' "${VITE_PORTAL_LOGIN_URL}"
} > "${OUT}/BUILD-INFO.txt"
ok "[frontend] 빌드 기록: ${OUT}/BUILD-INFO.txt"

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
#   ★ 향마다 동봉한다 — 설치가 배치하는 것은 <한 향의 dist 하나>이고 그것이 곧 웹 문서
#     루트가 된다. 상위(dist/)에 한 벌만 두면 웹에서는 아무 향에서도 열리지 않는다.
for _flavor in "${BUILD_FLAVORS[@]}"; do
  rm -rf "${OUT}/dist/${_flavor}/licenses"
  mkdir -p "${OUT}/dist/${_flavor}/licenses"
  cp -R "${FE_LIC_DIR}/." "${OUT}/dist/${_flavor}/licenses/" 2>/dev/null || true
  ok "[frontend] 산출물 동봉: ${OUT}/dist/${_flavor}/licenses ($(du -sh "${OUT}/dist/${_flavor}/licenses" 2>/dev/null | cut -f1))"
done

# ★ SHA256SUMS 는 licenses/ 를 동봉한 <뒤에> 써야 한다. 앞에서 쓰면 목록에서 빠져
#   install.sh 의 무결성 검증이 고지 파일만 검증 없이 통과시킨다(10-build-backend.sh 의
#   WAR 순서 사고와 같은 형태다).
sha256_write "${OUT}/dist"
