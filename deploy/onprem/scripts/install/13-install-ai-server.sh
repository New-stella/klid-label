#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 13-install-ai-server.sh — [대상 서버] ai-server venv 생성 + 오프라인 pip 설치
#   + 앱 소스/모델 배치 + env 템플릿 + systemd 유닛
#
#   외부 네트워크 호출 없음:
#     pip install --no-index --find-links vendor/wheels (+ sam2 로컬 소스)
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
require_root

: "${KLID_PREFIX:?install.sh 에서 호출되어야 합니다}"
ONPREM="$(onprem_root)"
AI_DIR="${KLID_PREFIX}/ai"
VENV="${AI_DIR}/venv"
WHEELS="${ONPREM}/vendor/wheels"
SAM2_SRC="${ONPREM}/vendor/sam2/sam2-src"

# 런타임 python 경로 로드(11 단계가 기록)
# shellcheck source=/dev/null
source "${KLID_PREFIX}/runtime/runtime.env"
: "${KLID_PYTHON:?런타임 python 경로 누락 — 11-install-runtimes.sh 가 선행되어야 합니다}"

ensure_dir "${AI_DIR}"

# ---- 앱 소스 배치 ----
info "[ai-server] 앱 소스 배치..."
rm -rf "${AI_DIR}/app"
cp -R "${ONPREM}/artifacts/ai-server/app" "${AI_DIR}/app"
cp "${ONPREM}/artifacts/ai-server/requirements.txt" "${AI_DIR}/requirements.txt"
ok "[ai-server] 소스: ${AI_DIR}/app"

# ---- 모델/가중치 배치 ----
ensure_dir "${AI_DIR}/weights" "${AI_DIR}/.hf-cache"
if [[ -f "${ONPREM}/models/weights/yolox_s.onnx" ]]; then
  install -m 0644 "${ONPREM}/models/weights/yolox_s.onnx" "${AI_DIR}/weights/yolox_s.onnx"
  ok "[ai-server] 가중치: ${AI_DIR}/weights/yolox_s.onnx"
else
  warn "[ai-server] yolox 가중치 미동봉 — yolox 백엔드는 mock 응답으로 동작(weights_missing)"
fi
# SAM2 모델 캐시 복사
#   ★ 없을 때 조용히 넘어가지 않는다. 바로 위 yolox 가중치는 없으면 경고하는데 이쪽만
#     아무 말이 없어, 모델이 빠진 번들이 <아무 신호 없이> 설치를 통과하고 있었다.
#     대상 서버는 HF_HUB_OFFLINE=1 이라 내려받지도 못하므로, 여기서 알리지 않으면
#     SAM2 가 mock 으로 도는 사실을 운영 중에 알 방법이 없다(서버는 정상 기동하고
#     API 도 200 을 돌려준다).
if [[ -d "${ONPREM}/models/hf-cache" ]] && [[ -n "$(ls -A "${ONPREM}/models/hf-cache" 2>/dev/null)" ]]; then
  cp -R "${ONPREM}/models/hf-cache/." "${AI_DIR}/.hf-cache/"
  ok "[ai-server] SAM2 모델 캐시 배치: ${AI_DIR}/.hf-cache"
else
  warn "[ai-server] ★SAM2 모델 캐시 미동봉 — SAM2 분할·Track 이 mock 응답으로 동작합니다."
  warn "  이 서버는 오프라인이라 모델을 내려받지 못합니다. 기동·헬스체크·API 응답은"
  warn "  모두 정상이라 운영 중에는 드러나지 않습니다."
  warn "  조치: 빌드머신에서 패키징을 다시 수행해 models/hf-cache 를 채운 뒤 재설치하세요."
fi

# ---- venv + 오프라인 설치 ----
info "[ai-server] venv 생성: ${VENV}"
"${KLID_PYTHON}" -m venv "${VENV}"
PIP="${VENV}/bin/pip"

# pip 자체 업그레이드는 오프라인이므로 생략(번들 wheel 에 pip 가 있으면 사용 가능).
info "[ai-server] pip 오프라인 설치 (--no-index --find-links vendor/wheels)..."
# requirements.txt 에서 sam-2(git+) 라인은 제외하고 wheel 로 설치.
REQ_NO_SAM2="$(mktemp)"; trap 'rm -f "${REQ_NO_SAM2}"' EXIT
grep -ivE '(^|[[:space:]])sam-2[[:space:]]*@' "${AI_DIR}/requirements.txt" \
  | grep -vE 'git\+https' > "${REQ_NO_SAM2}" || true

"${PIP}" install --no-index --find-links "${WHEELS}" -r "${REQ_NO_SAM2}" \
  || die "[ai-server] 오프라인 wheel 설치 실패 — 06-troubleshooting.md '누락 wheel' 절 참고"

# sam2 는 vendor 한 로컬 소스로 설치(빌드 의존성도 wheels 에서 충당).
if [[ -d "${SAM2_SRC}" ]]; then
  info "[ai-server] sam2 로컬 소스 설치: ${SAM2_SRC}"
  "${PIP}" install --no-index --find-links "${WHEELS}" "${SAM2_SRC}" \
    || warn "[ai-server] sam2 설치 실패 — SAM2 분할/Track 미사용이면 무시 가능(06-troubleshooting 참고)"
else
  warn "[ai-server] sam2 소스 미동봉(${SAM2_SRC}) — SAM2 기능 사용 시 필요"
fi

ok "[ai-server] 패키지 설치 완료"

# ---- env 파일 ----
ENV_DST="${KLID_ETC}/ai-server.env"
if [[ -f "${ENV_DST}" ]]; then
  info "[ai-server] env 이미 존재(보존): ${ENV_DST}"
else
  install -m 0640 "${ONPREM}/config/ai-server/env.template" "${ENV_DST}"
  chown root:"${KLID_GROUP}" "${ENV_DST}"
  ok "[ai-server] env 템플릿 설치: ${ENV_DST}"
fi

chown -R "${KLID_USER}:${KLID_GROUP}" "${AI_DIR}"

# ---- systemd 유닛 ----
UNIT_SRC="${ONPREM}/config/systemd/klid-ai-server.service"
UNIT_DST="${SYSTEMD_DIR}/klid-ai-server.service"
sed \
  -e "s#@KLID_USER@#${KLID_USER}#g" \
  -e "s#@KLID_GROUP@#${KLID_GROUP}#g" \
  -e "s#@KLID_PREFIX@#${KLID_PREFIX}#g" \
  -e "s#@KLID_ETC@#${KLID_ETC}#g" \
  "${UNIT_SRC}" > "${UNIT_DST}"
chmod 0644 "${UNIT_DST}"
ok "[ai-server] systemd 유닛 설치: ${UNIT_DST}"
