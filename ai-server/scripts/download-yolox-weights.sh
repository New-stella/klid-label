#!/usr/bin/env bash
# YOLOX 가중치 다운로드 스크립트 (ai-server/weights/)
#
#   탐지 백엔드는 YOLOX(ONNX Runtime) + ByteTrack 단일 백엔드다. 이 스크립트가 받는
#   yolox_s.onnx 는 Megvii-BaseDetection/YOLOX 공식 릴리스 자산이며 라이선스는 Apache-2.0 이다.
#   (구 ultralytics YOLOv8/SAM 가중치 다운로드 스크립트는 AGPL-3.0 배포물이라 제거됐다.)
#
#   ★ 이 파일이 존재하는 이유 = 재현성. 가중치 바이너리는 .gitignore 대상이라 git 에 없다.
#     받는 URL 과 기대 해시를 코드로 고정해 두지 않으면 "이 개발 PC 에만 있는 파일" 이 되고,
#     아무도 같은 번들을 다시 만들 수 없다.
#
#   무결성: 기대 SHA256 은 weights/yolox_s.onnx.sha256 (git 추적) 이 단일 출처다.
#           불일치면 <실패>한다 — 조용히 넘어가지 않는다.
#
# 사용법:
#   ./scripts/download-yolox-weights.sh          # 없으면 받고, 있으면 해시만 검증
#   ./scripts/download-yolox-weights.sh --force  # 기존 파일을 지우고 다시 받는다
#
# 받은 뒤 ai-server/.env 설정(기본값과 동일하므로 보통 손댈 필요 없음):
#   AI_MOCK_MODE=false
#   YOLOX_WEIGHTS_PATH=./weights/yolox_s.onnx
set -euo pipefail

MODEL_FILE="yolox_s.onnx"
# Megvii-BaseDetection/YOLOX 릴리스 0.1.1rc0 (2021-08-18) 자산 (SSRF allowlist: github.com)
RELEASE_TAG="0.1.1rc0"
URL="https://github.com/Megvii-BaseDetection/YOLOX/releases/download/${RELEASE_TAG}/${MODEL_FILE}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEIGHTS_DIR="${SCRIPT_DIR}/../weights"
TARGET="${WEIGHTS_DIR}/${MODEL_FILE}"
SHA_FILE="${WEIGHTS_DIR}/${MODEL_FILE}.sha256"

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

die() { echo "[weights] ERROR: $*" >&2; exit 1; }

mkdir -p "${WEIGHTS_DIR}"

# ---- 기대 해시 로드 (단일 출처) ----
[[ -f "${SHA_FILE}" ]] || die "기대 해시 파일이 없습니다: ${SHA_FILE}
       이 파일은 git 추적 대상입니다. 저장소가 온전한지 확인하세요."
EXPECTED="$(awk '{print tolower($1)}' "${SHA_FILE}" | head -1)"
[[ "${EXPECTED}" =~ ^[0-9a-f]{64}$ ]] || die "기대 해시 형식이 올바르지 않습니다: ${SHA_FILE}"

# ---- 해시 계산 (linux: sha256sum / macOS: shasum -a 256) ----
hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print tolower($1)}'
  else
    die "sha256 도구를 찾을 수 없습니다 (sha256sum / shasum)."
  fi
}

# ---- 이미 있으면 해시만 검증 ----
if [[ -f "${TARGET}" && "${FORCE}" -eq 0 ]]; then
  actual="$(hash_file "${TARGET}")"
  if [[ "${actual}" == "${EXPECTED}" ]]; then
    echo "[weights] already exists & verified: ${TARGET}"
    exit 0
  fi
  die "기존 파일의 해시가 기대값과 다릅니다: ${TARGET}
       기대: ${EXPECTED}
       실제: ${actual}
     → 손상/변조 또는 다른 모델일 수 있습니다. --force 로 다시 받으세요."
fi

# ---- 다운로드 (임시 파일 → 검증 통과 후에만 확정) ----
TMP="${TARGET}.download.$$"
cleanup() { rm -f "${TMP}"; }
trap cleanup EXIT

echo "[weights] downloading ${MODEL_FILE} from Megvii-BaseDetection/YOLOX ${RELEASE_TAG} (Apache-2.0)..."
if command -v curl >/dev/null 2>&1; then
  curl -fL --progress-bar -o "${TMP}" "${URL}" || die "다운로드 실패: ${URL}"
elif command -v wget >/dev/null 2>&1; then
  wget -q --show-progress -O "${TMP}" "${URL}" || die "다운로드 실패: ${URL}"
else
  die "curl 또는 wget 이 필요합니다."
fi

actual="$(hash_file "${TMP}")"
if [[ "${actual}" != "${EXPECTED}" ]]; then
  die "SHA256 불일치 — 받은 파일을 폐기합니다.
       기대: ${EXPECTED}
       실제: ${actual}
       URL : ${URL}
     → 릴리스 자산이 교체되었거나 전송이 손상되었습니다. 그대로 쓰지 마세요."
fi

mv "${TMP}" "${TARGET}"
trap - EXIT
echo "[weights] verified & saved: ${TARGET}"
echo "[weights] sha256=${EXPECTED}  license=Apache-2.0 (Megvii-BaseDetection/YOLOX)"
