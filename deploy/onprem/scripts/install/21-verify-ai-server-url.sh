#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 21-verify-ai-server-url.sh — AI 추론 서버 주소가 <조용히 틀린 채> 남지 않게 한다
#
#   왜 필요한가:
#     AI_SERVER_URL 이 틀려도 아무 신호가 없다. backend 는 정상 기동하고 헬스체크도
#     통과하며, 오토라벨링(YOLO 탐지 · SAM2 분할/추적)만 호출 시점에 실패한다.
#     그런데 기본값은 loopback 이고, ai-server 를 <다른 장비>에 두는 2대 구성에서는
#     그 기본값이 반드시 틀리다. 그래서 설치 끝에서 눈에 띄게 만든다.
#
#   ★ 판정은 <역할>로 가른다 — 무조건 필수로 만들지 않는다.
#       · 역할 미지정(all, 단일 서버) : ai-server 가 같은 장비에 설치되므로 loopback 이 <맞다>.
#                                       여기서 경고하면 정상 설치가 매번 경고를 뱉는다.
#       · --role=was (WAS 장비)       : 이 장비에는 ai-server 를 설치하지 않는다. 따라서
#                                       loopback 은 <가리킬 대상이 없는 주소>다 → 경고한다.
#
#   ★ 설치를 실패로 만들지 않는다(die 하지 않는다). 이 스크립트가 도는 시점은 운영자가
#     설정을 편집하기 <전>이라서다 — install.sh 의 마무리 안내가 "1) 환경설정 편집" 이고,
#     그 시점에는 DB 비밀번호조차 비어 있다. AI 주소만 골라 기동을 막으면 일관성이 없다.
#     (비교: 20 단계는 die 한다. 그건 값이 없으면 생성기가 산출물을 <아예 만들지 못해>
#      설치가 물리적으로 미완성이기 때문이고, 여기는 값이 문법적으로 유효하기 때문이다.)
#
#   ★ 도달 확인(연결 시도)은 <주소를 이미 바꾼 경우에만> 하고, 실패해도 경고로 끝낸다.
#     ai-server(서버 B)가 아직 기동되지 않았을 수 있어 실패를 결함으로 단정할 수 없다.
#     건너뛰려면 AI_SERVER_PROBE=0.
#
#   단독 실행(설정을 고친 뒤 다시 확인할 때):
#       sudo KLID_ROLE=was ./scripts/install/21-verify-ai-server-url.sh
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"

KLID_ETC="${KLID_ETC:-/etc/klid}"
KLID_ROLE="${KLID_ROLE:-all}"

if ! klid_role_has was; then
  info "[ai-url] 이 역할에는 백엔드가 없으므로 검증을 건너뜁니다(KLID_ROLE=${KLID_ROLE})."
  exit 0
fi

# ---- 값 읽기: WAR 형상 정본 → 베어메탈 형상 순 ----
#   같은 키 이름을 두 파일이 나눠 갖는다. 실제로 쓰이는 쪽을 앞에 둔다.
read_key() {  # read_key <파일> <키>
  [[ -f "$1" ]] || return 1
  local v
  v="$(grep -E "^[[:space:]]*$2=" "$1" | tail -1 | cut -d= -f2- | xargs 2>/dev/null || true)"
  [[ -n "${v}" ]] || return 1
  printf '%s\n' "${v}"
}

AI_URL=""
SRC_FILE=""
for f in "${KLID_ETC}/application.properties" "${KLID_ETC}/backend.env"; do
  if AI_URL="$(read_key "${f}" AI_SERVER_URL)"; then SRC_FILE="${f}"; break; fi
done

if [[ -z "${AI_URL}" ]]; then
  warn "[ai-url] AI_SERVER_URL 을 어느 설정 파일에서도 찾지 못했습니다"
  warn "         (확인한 곳: ${KLID_ETC}/application.properties · ${KLID_ETC}/backend.env)"
  warn "         → 12-install-backend.sh 가 설정 템플릿을 배치했는지 확인하세요."
  exit 0
fi

# ---- loopback 판정 ----
#   호스트만 떼어 본다. 스킴·포트·경로는 판정에 쓰지 않는다.
_host="${AI_URL#*://}"; _host="${_host%%/*}"; _host="${_host%%:*}"
_is_loopback=0
case "${_host}" in
  127.*|localhost|::1|"[::1]"|0.0.0.0) _is_loopback=1 ;;
esac

if [[ "${_is_loopback}" -eq 1 ]]; then
  if [[ "${KLID_ROLE}" == "all" ]]; then
    ok "[ai-url] AI_SERVER_URL=${AI_URL} (단일 서버 구성 — ai-server 가 같은 장비에 있으므로 정상)"
    exit 0
  fi
  warn "----------------------------------------------------------------"
  warn "[ai-url] AI 추론 서버 주소가 아직 <이 장비 자신>을 가리킵니다."
  warn ""
  warn "    현재값 : AI_SERVER_URL=${AI_URL}"
  warn "    파일   : ${SRC_FILE}"
  warn ""
  warn "  이 장비는 서버 A(app) 이고 ai-server 는 여기에 설치되지 않습니다."
  warn "  이대로 두면 <기동도 헬스체크도 정상인데> 오토라벨링(YOLO·SAM2)만 실패합니다."
  warn ""
  warn "  1) 서버 B 주소로 바꾸세요:"
  warn ""
  warn "       sudo \$EDITOR ${SRC_FILE}"
  warn "         AI_SERVER_URL=http://<서버 B 주소>:9300"
  warn ""
  warn "  2) 서버 B 에서도 바인드 주소를 열어야 원격에서 닿습니다(기본은 loopback):"
  warn ""
  warn "       sudo \$EDITOR ${KLID_ETC}/ai-server.env"
  warn "         AI_BIND_HOST=<서버 B 주소>"
  warn "       sudo systemctl restart klid-ai-server"
  warn "       # 방화벽에서 9300/tcp 을 서버 A 에서만 오도록 함께 여세요."
  warn ""
  warn "  3) 고친 뒤 이 검사를 다시 돌려 확인하세요:"
  warn ""
  warn "       sudo KLID_ROLE=was ${SELF_DIR}/21-verify-ai-server-url.sh"
  warn ""
  warn "  ※ backend 설정을 바꾸면 WAS 재기동이 필요합니다(04-configuration.md 반영 방법 표)."
  warn "----------------------------------------------------------------"
  exit 0
fi

# ---- 주소가 이미 바뀌어 있다 → 도달 확인(선택) ----
ok "[ai-url] AI_SERVER_URL=${AI_URL} (${SRC_FILE})"
if [[ "${AI_SERVER_PROBE:-1}" != "1" ]]; then
  info "[ai-url] 도달 확인 생략(AI_SERVER_PROBE=0)."
  exit 0
fi
if ! command -v curl >/dev/null 2>&1; then
  info "[ai-url] curl 이 없어 도달 확인을 생략합니다. 수동 확인: curl -fsS ${AI_URL}/health"
  exit 0
fi
info "[ai-url] 도달 확인: ${AI_URL}/health"
if curl -fsS --connect-timeout 3 --max-time 5 "${AI_URL}/health" >/dev/null 2>&1; then
  ok "[ai-url] ai-server 응답 확인(200)."
else
  warn "[ai-url] 지금은 ${AI_URL}/health 에 닿지 않습니다."
  warn "         ★ 이것만으로 설정이 틀렸다고 단정하지 않습니다 — 서버 B 가 아직 기동되지"
  warn "           않았거나 방화벽이 아직 열리지 않았을 수 있습니다."
  warn "         서버 B 기동 후 서버 A 에서 다시 확인하세요:"
  warn "           curl -fsS ${AI_URL}/health"
fi
