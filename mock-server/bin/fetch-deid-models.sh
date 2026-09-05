#!/usr/bin/env bash
# ============================================================================
# 비식별 엔진 모델 조달 — OpenCV Zoo 에서 받아 mock-server/models/ 에 둔다.
#
#   모델이 없으면 목 서버는 <b>기존 워터마크 동작</b>으로 자동 폴백하므로, 이 스크립트를
#   돌리지 않아도 목 서버·CI 는 그대로 동작한다. 실제 마스킹을 보고 싶을 때만 실행한다.
#
#   라이선스 — 전부 permissive (이 프로젝트의 AGPL 금지 정책 통과):
#     얼굴   YuNet            MIT
#     간판   PP-OCRv3 det     Apache-2.0
#     사람   YOLOX            Apache-2.0
#
#   ⚠ 파일은 git 에 커밋하지 않는다(합계 약 38MB). models/.gitignore 가 막는다.
#   ⚠ 반드시 media.githubusercontent.com(LFS) 경로를 쓴다 — raw.githubusercontent.com 은
#     실제 모델이 아니라 <b>LFS 포인터 텍스트</b>(131B)를 준다. 아래 크기 가드가 그것을 걸러낸다.
# ============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models"
LFS="https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models"

mkdir -p "$DIR"

fetch() {  # fetch <출력명> <URL>
  local out="$DIR/$1" url="$2"
  if [ -s "$out" ]; then
    echo "  = $1 (이미 있음)"
    return 0
  fi
  echo "  ↓ $1"
  if ! curl -sSL --fail --max-time 300 -o "$out.part" "$url"; then
    echo "  ! $1 내려받기 실패 — 건너뜀 (해당 대상만 비활성)" >&2
    rm -f "$out.part"
    return 0
  fi
  # LFS 포인터(수백 바이트)를 실제 모델로 오인하지 않도록 최소 크기를 확인한다.
  if [ "$(wc -c < "$out.part")" -lt 100000 ]; then
    echo "  ! $1 가 LFS 포인터/오류 응답으로 보임 — 폐기" >&2
    rm -f "$out.part"
    return 0
  fi
  mv "$out.part" "$out"
}

echo "비식별 엔진 모델을 내려받습니다 → $DIR"
fetch face_detection_yunet.onnx \
  "$LFS/face_detection_yunet/face_detection_yunet_2023mar.onnx"
fetch text_detection_ppocr.onnx \
  "$LFS/text_detection_ppocr/text_detection_en_ppocrv3_2023may.onnx"
fetch object_detection_yolox.onnx \
  "$LFS/object_detection_yolox/object_detection_yolox_2022nov.onnx"

echo
echo "완료 — 현재 모델:"
ls -la "$DIR" | grep -E "\.onnx$" || echo "  (없음 — 목 서버는 워터마크로 폴백합니다)"
