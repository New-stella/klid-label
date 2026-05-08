#!/usr/bin/env bash
# SAM2 가중치 다운로드 스크립트 (ai-server/weights/)
#
# 사용법:
#   ./scripts/download-sam2-weights.sh            # sam2_t.pt (tiny, ~38MB)
#   ./scripts/download-sam2-weights.sh sam2_s.pt  # small (~89MB)
#   ./scripts/download-sam2-weights.sh sam2_b.pt  # base+ (~312MB)
#   ./scripts/download-sam2-weights.sh sam2_l.pt  # large (~856MB)
#
# 다운로드 후 ai-server/.env 에서:
#   AI_MOCK_MODE=false
#   SAM2_WEIGHTS_PATH=./weights/sam2_t.pt
set -euo pipefail

MODEL="${1:-sam2_t.pt}"
WEIGHTS_DIR="$(dirname "$0")/../weights"
mkdir -p "$WEIGHTS_DIR"
TARGET="$WEIGHTS_DIR/$MODEL"

if [[ -f "$TARGET" ]]; then
  echo "[weights] already exists: $TARGET"
  exit 0
fi

# ultralytics 공식 GitHub Releases (SSRF allowlist: github.com)
BASE_URL="https://github.com/ultralytics/assets/releases/download/v8.3.0"
URL="$BASE_URL/$MODEL"

echo "[weights] downloading $MODEL from ultralytics GitHub Releases..."
if command -v curl &>/dev/null; then
  curl -fL --progress-bar -o "$TARGET" "$URL"
elif command -v wget &>/dev/null; then
  wget -q --show-progress -O "$TARGET" "$URL"
else
  echo "[weights] ERROR: curl 또는 wget 이 필요합니다."
  exit 1
fi

echo "[weights] saved to: $TARGET"
echo ""
echo "다음 단계:"
echo "  1. ai-server/.env 에 아래 설정 추가:"
echo "       AI_MOCK_MODE=false"
echo "       SAM2_WEIGHTS_PATH=./weights/$MODEL"
echo "  2. ai-server 재시작: uvicorn app.main:app --reload"
