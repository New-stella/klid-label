#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 20-verify-frontend-config.sh — [대상 서버 A / app 역할] 프론트엔드 런타임 설정 <최종 게이트>
#
#   ★★ 이 단계는 <자리를 옮긴 fail-closed> 다 (2026-08-30, 구속).
#     그 가드는 원래 <빌드>에 있었고(require_upstream_login_urls), 빌드를 환경 무관으로
#     바꾸면서 <설치>로 옮겼다. 그런데 옮긴 위치가 틀렸다 — 14-install-frontend.sh 안,
#     그것도 <자기가 방금 놓은 빈 템플릿을 즉시 읽는> 자리였다. 그래서:
#       · 첫 설치는 <구조적으로 반드시> 이 지점에서 죽고
#       · set -e 라 httpd 설치·SELinux 문맥·15·16·17·19 단계가 통째로 실행되지 않았다
#       · 현장에서는 "설치는 돌았는데 웹 서버가 없다"로 보였다
#     → 되돌리기 어렵고 값과 무관한 것(httpd 설치·SELinux 문맥·정적 파일·WAR 배치)을 먼저
#       끝내고, 값 누락은 <여기, 맨 마지막>에서 막는다.
#
#   ★ 방어를 <없앤 것이 아니다>. 값이 비면 설치 전체를 실패로 종결한다.
#     값 없이 "설치 완료"가 나오면 지금보다 나쁘다 — 저작도구는 자체 로그인 UI 가 없어
#     상위 시스템 로그인 URL 이 비면 세션 만료 시 <이동할 곳이 없다>. 그런데 화면은
#     그럭저럭 뜨고 httpd 도 정상이라, 막지 않으면 운영 중에야 드러난다.
#
#   ★ 19-verify-ffmpeg.sh 와 <같은 관례>다: 설치 맨 끝에서 검증만 하고, 미충족이면 die.
#     앞 단계는 이미 끝나 있으므로 값을 채운 뒤 install.sh 를 다시 돌리면 이어진다(멱등).
#
#   ★ 판정은 이 스크립트가 다시 하지 않는다 — 생성기(render-frontend-config.sh)를 그대로
#     부르고 그 성패를 읽는다. 조건을 여기서 재유도하면 생성기와 갈려, "검증은 통과했는데
#     생성은 실패"하는 상태가 생긴다(소비자가 생산자의 성공조건을 재유도하지 않는다).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

KLID_ETC="${KLID_ETC:-/etc/klid}"
KLID_PREFIX="${KLID_PREFIX:-/opt/klid}"

if ! klid_role_has app; then
  info "[frontend-config] 역할이 app 이 아니므로 검증을 건너뜁니다(KLID_ROLE=${KLID_ROLE:-all})."
  exit 0
fi

FE_CONFIG="${KLID_ETC}/frontend.env"
RENDERER="${KLID_PREFIX}/bin/klid-frontend-config"
WEB_ROOT="${KLID_PREFIX}/web/dist"
OUT_FILE="${WEB_ROOT}/klid-config.js"

# 14 단계가 돌지 않았으면 여기서 판정할 것이 없다 — 상태를 흐리지 말고 그대로 말한다.
[[ -x "${RENDERER}" ]] \
  || die "[frontend-config] 설정 생성기가 없습니다: ${RENDERER}
     → 14-install-frontend.sh 가 실행되지 않았습니다. 설치를 처음부터 다시 실행하세요:
       sudo ${SCRIPTS_DIR}/install.sh --role=app"
[[ -f "${FE_CONFIG}" ]] \
  || die "[frontend-config] 설정 정본이 없습니다: ${FE_CONFIG}
     → 14-install-frontend.sh 가 템플릿을 배치하지 못했습니다. 설치를 다시 실행하세요."

info "[frontend-config] 런타임 설정 검증(생성기 실행): ${FE_CONFIG}"
if KLID_ETC="${KLID_ETC}" KLID_PREFIX="${KLID_PREFIX}" WEB_ROOT="${WEB_ROOT}" "${RENDERER}"; then
  [[ -s "${OUT_FILE}" ]] \
    || die "[frontend-config] 생성기는 성공했는데 산출물이 비어 있습니다: ${OUT_FILE}
       → 디스크 여유·권한을 확인하고 다시 실행하세요:  sudo ${RENDERER}"
  ok "[frontend-config] 런타임 설정 확인: ${OUT_FILE}"
  exit 0
fi

# ---- 미충족 — 복붙 가능한 한 줄로 안내하고 설치를 실패로 종결한다 ----
#   "어딘가에 설정 파일이 있다"로 끝내지 않는다(19 단계의 안내 형식과 같은 규칙).
warn "----------------------------------------------------------------"
warn "[frontend-config] 프론트엔드 런타임 설정이 <미완성>입니다 — 설치를 완료로 볼 수 없습니다."
warn ""
warn "  1) 아래 파일을 열어 <이 배포 향에 해당하는> 상위 로그인 URL 을 채우세요(스킴 http/https 포함):"
warn ""
warn "       sudo \$EDITOR ${FE_CONFIG}"
warn ""
warn "         KLID_DEPLOY_FLAVOR=control   → VITE_CONTROL_LOGIN_URL=https://<관제지원시스템 로그인 주소>"
warn "         KLID_DEPLOY_FLAVOR=portal    → VITE_PORTAL_LOGIN_URL=https://<포털 로그인 주소>"
warn ""
warn "       ※ 배포 향을 비워 두면 두 주소를 모두 요구합니다. 해당하지 않는 주소를 억지로"
warn "          채우지 말고 배포 향을 선언하세요(그 배포에서 실제로 쓰이지 않는 값입니다)."
warn ""
warn "  2) 채운 뒤 아래 <한 줄>이면 반영됩니다(재빌드·재설치 불필요):"
warn ""
warn "       sudo ${RENDERER}"
warn ""
warn "  3) 설치를 완료 상태로 만들려면 설치 스크립트를 다시 실행하세요(멱등):"
warn ""
warn "       sudo ${SCRIPTS_DIR}/install.sh --role=app"
warn ""
warn "  ※ 앞 단계(httpd·정적 자산·WAR 배치·DB)는 이미 끝나 있습니다. 다시 돌려도 안전합니다."
warn "  ※ 자세한 설명: deploy/onprem/docs/04-configuration.md 'frontend 런타임 설정'"
warn "----------------------------------------------------------------"
die "[frontend-config] 필수 설정 미충족 — 설치를 실패로 종결합니다.
     (이 값이 비면 세션 만료 시 상위 로그인 페이지로 이동할 수 없습니다. 화면은 뜨고
      httpd 도 정상이라, 여기서 막지 않으면 운영 중에야 드러납니다.)"
