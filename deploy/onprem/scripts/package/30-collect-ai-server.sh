#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 30-collect-ai-server.sh — [빌드머신] ai-server 소스 + pip wheel + sam2 + 모델 수집
#
#   ai-server 는 사전 빌드 산출물이 아니라 "소스 + 오프라인 wheel" 로 번들한다.
#   대상 서버(폐쇄망)에서 번들 python 으로 venv 를 만들고
#   pip install --no-index --find-links vendor/wheels 로 설치한다.
#
#   수집물:
#     artifacts/ai-server/app           : FastAPI 앱 소스
#     artifacts/ai-server/requirements.txt
#     vendor/wheels/*.whl|*.tar.gz       : 모든 pip 의존성(torch/torchvision=CPU)
#     vendor/sam2/                       : sam2 git 소스(VCS 의존성 오프라인화)
#     models/weights/yolox_s.onnx        : YOLOX 가중치(repo 동봉본)
#     models/hf-cache/                   : (옵션 PREFETCH_HF=1) HF 모델 캐시
#
#   ★ 함정 메모:
#     - torch/torchvision 은 PyTorch CPU 인덱스에서 받는다(기본 PyPI 는 CUDA wheel).
#     - sam-2 는 git+ VCS 의존성 → git clone 후 소스 디렉토리로 vendor.
#     - requirements.txt(lock) 에는 --hash 가 없어 무결성 검증 불가 → vendor 후
#       SHA256SUMS 를 우리가 생성(전송 무결성 보장용).
# ============================================================================

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${SELF_DIR}/../lib/common.sh"
# shellcheck source=../lib/versions.sh
source "${SELF_DIR}/../lib/versions.sh"

REPO="$(repo_root)"
ONPREM="$(onprem_root)"
AI_SRC="${REPO}/ai-server"
OUT="${ONPREM}/artifacts/ai-server"
WHEELS="${ONPREM}/vendor/wheels"
SAM2_OUT="${ONPREM}/vendor/sam2"
WEIGHTS_OUT="${ONPREM}/models/weights"
HF_OUT="${ONPREM}/models/hf-cache"

[[ -d "${AI_SRC}" ]] || die "ai-server 디렉토리를 찾을 수 없습니다: ${AI_SRC}"
require_cmd git
ensure_dir "${OUT}" "${WHEELS}" "${SAM2_OUT}" "${WEIGHTS_OUT}" "${HF_OUT}"

# 대상과 동일한 python 3.11 을 사용해야 wheel 태그(cp311)가 맞다.
PYBIN="${PYTHON_BIN:-python3.11}"
command -v "${PYBIN}" >/dev/null 2>&1 || PYBIN="python3"
require_cmd "${PYBIN}"
pyver="$("${PYBIN}" -c 'import sys;print("%d.%d"%sys.version_info[:2])')"
info "[ai-server] python: ${PYBIN} (${pyver})"
if [[ "${pyver}" != "${BUILD_PYTHON_MINOR}" ]]; then
  warn "대상 런타임은 Python ${BUILD_PYTHON_MINOR} 입니다. 현재 ${pyver} 로 wheel 을 받으면"
  warn "cp${pyver//./} 태그 wheel 이 생성되어 대상(cp311)과 불일치할 수 있습니다."
  confirm "그래도 계속할까요?" || die "Python ${BUILD_PYTHON_MINOR} 로 다시 실행하세요(PYTHON_BIN=python3.11)."
fi

# ---- 1) 앱 소스 + requirements 복사 ----
info "[ai-server] 앱 소스 복사..."
rm -rf "${OUT}/app"
cp -R "${AI_SRC}/app" "${OUT}/app"
cp "${AI_SRC}/requirements.txt" "${OUT}/requirements.txt"
# tests 는 운영에 불필요하나 스모크 참고용으로 동봉(선택)
[[ -d "${AI_SRC}/tests" ]] && cp -R "${AI_SRC}/tests" "${OUT}/tests" || true
ok "[ai-server] 소스 수집: ${OUT}/app"

# ---- 2) sam2 VCS 의존성 → 소스 vendor ----
info "[ai-server] sam2 git clone (VCS 의존성 오프라인화)..."
# 재현성 경고: SAM2_GIT_REF 미설정이면 기본 브랜치 HEAD 를 받게 되어 빌드마다 달라질 수 있다.
if [[ -z "${SAM2_GIT_REF:-}" ]]; then
  warn "[ai-server] SAM2_GIT_REF 미설정 — 기본 브랜치 HEAD 를 받습니다(재현성 보장 안 됨)."
  warn "  재현성을 위해 versions.sh 에 특정 커밋/태그를 고정하는 것을 권장합니다."
  confirm "그래도 기본 브랜치 HEAD 로 진행할까요?" \
    || die "[ai-server] SAM2_GIT_REF 를 versions.sh 에 고정한 뒤 다시 실행하세요."
fi
rm -rf "${SAM2_OUT}/sam2-src"
git clone --depth 1 ${SAM2_GIT_REF:+--branch "${SAM2_GIT_REF}"} "${SAM2_GIT_URL}" "${SAM2_OUT}/sam2-src"
# .git 제거(용량 절감) — 소스 디렉토리만 필요
rm -rf "${SAM2_OUT}/sam2-src/.git"
ok "[ai-server] sam2 소스 vendor: ${SAM2_OUT}/sam2-src"

# ---- 3) pip wheel 수집 ----
# requirements.txt 에서 sam-2(git+) 와 torch/torchvision 을 분리 처리한다.
#   - 일반 의존성: PyPI 에서 다운로드
#   - torch/torchvision: PyTorch CPU 인덱스에서 다운로드
#   - sam-2: vendor 한 소스로 설치할 것이므로 wheel 수집 대상에서 제외
TMP_REQ="$(mktemp)"
trap 'rm -f "${TMP_REQ}"' EXIT

# git+ 라인(sam-2)과 torch 계열을 제거한 일반 requirements 생성
# torch/torchvision 만 분리(torch-geometric·torchmetrics 등 과매칭 방지).
grep -ivE '^torch(vision)?([[:space:]]|==|@|$)' "${AI_SRC}/requirements.txt" \
  | grep -ivE '(^|[[:space:]])sam-2[[:space:]]*@' \
  | grep -vE '^\s*#' \
  | grep -vE 'git\+https' > "${TMP_REQ}" || true

info "[ai-server] (1/2) 일반 의존성 wheel 다운로드 (PyPI)..."
"${PYBIN}" -m pip download \
  --dest "${WHEELS}" \
  --requirement "${TMP_REQ}"

# torch/torchvision 은 lock 버전을 CPU 인덱스에서 받는다.
# requirements.txt 에서 정확한 핀 버전을 추출(예: torch==2.12.0).
torch_pin="$(grep -iE '^torch==' "${AI_SRC}/requirements.txt" | head -n1 | tr -d '[:space:]' || true)"
tv_pin="$(grep -iE '^torchvision==' "${AI_SRC}/requirements.txt" | head -n1 | tr -d '[:space:]' || true)"
info "[ai-server] (2/2) torch CPU wheel 다운로드: ${torch_pin:-torch} ${tv_pin:-torchvision}"
warn "  CPU 인덱스(${PYTORCH_CPU_INDEX_URL})에 해당 버전 wheel 이 없으면 실패할 수 있습니다."
warn "  실패 시 06-troubleshooting.md 'torch CPU' 절을 참고해 인접 버전으로 조정하세요."
# --extra-index-url(PyPI) 폴백을 두지 않는다 — CPU 인덱스에 없으면 거대 CUDA wheel 로
# 조용히 폴백되어 번들에 혼입되므로, 명시적으로 실패시켜 troubleshooting 으로 유도한다.
"${PYBIN}" -m pip download \
  --dest "${WHEELS}" \
  --index-url "${PYTORCH_CPU_INDEX_URL}" \
  "${torch_pin:-torch}" "${tv_pin:-torchvision}" \
  || die "[ai-server] torch CPU wheel 다운로드 실패 — 06-troubleshooting.md 의 'torch CPU' 절을 참조하세요."

ok "[ai-server] wheel 수집: ${WHEELS}  ($(ls -1 "${WHEELS}"/*.whl "${WHEELS}"/*.tar.gz 2>/dev/null | wc -l | tr -d ' ') 개, $(du -sh "${WHEELS}" | cut -f1))"

# ---- 4) YOLOX 가중치 동봉 ----
if [[ -f "${AI_SRC}/weights/yolox_s.onnx" ]]; then
  cp "${AI_SRC}/weights/yolox_s.onnx" "${WEIGHTS_OUT}/yolox_s.onnx"
  ok "[ai-server] 가중치 수집: ${WEIGHTS_OUT}/yolox_s.onnx  ($(du -h "${WEIGHTS_OUT}/yolox_s.onnx" | cut -f1))"
else
  warn "[ai-server] yolox 가중치 없음: ${AI_SRC}/weights/yolox_s.onnx — DETECTOR_BACKEND=yolox 사용 시 필요"
fi

# ---- 5) (옵션) HF 모델 prefetch ----
# DETECTOR_BACKEND=rtdetr 또는 SAM2(클릭/박스 분할)를 쓰면 HF 모델이 필요하다.
# yolox 만 쓰면 생략 가능(기본 생략).
if [[ "${PREFETCH_HF:-0}" == "1" ]]; then
  info "[ai-server] HF 모델 prefetch (HF_HOME=${HF_OUT})..."
  if "${PYBIN}" -c 'import huggingface_hub' 2>/dev/null; then
    HF_HOME="${HF_OUT}" "${PYBIN}" - <<PY || warn "HF prefetch 일부 실패 — 사용 백엔드에 따라 무시 가능"
import os
from huggingface_hub import snapshot_download
for mid in ["${HF_SAM2_MODEL_ID}", "${HF_RTDETR_MODEL_ID}"]:
    print("download:", mid)
    snapshot_download(repo_id=mid)
PY
    ok "[ai-server] HF 모델 캐시: ${HF_OUT}"
  else
    warn "huggingface_hub 미설치 — HF prefetch 생략. 먼저 'pip install huggingface_hub' 후 PREFETCH_HF=1 재실행."
  fi
else
  info "[ai-server] HF 모델 prefetch 생략(PREFETCH_HF=1 로 활성화). yolox 백엔드만 쓰면 불필요."
fi

# ---- 무결성 체크섬 ----
sha256_write "${WHEELS}"
[[ -f "${WEIGHTS_OUT}/yolox_s.onnx" ]] && sha256_write "${WEIGHTS_OUT}" || true
