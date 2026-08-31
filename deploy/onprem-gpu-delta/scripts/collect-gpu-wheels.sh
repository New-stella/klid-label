#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# collect-gpu-wheels.sh — [빌드머신·인터넷 필요] GPU 델타 휠을 모은다
#
#   CPU 매체(deploy/onprem)의 vendor/wheels 와 <다른 것만> 남긴다.
#   판이 올라간 것이 딸려와도 넣지 않는다 — 잠긴 판을 흔들면 CPU 판과 갈린다.
#
#   사용법:  ./scripts/collect-gpu-wheels.sh [출력디렉터리]
# ============================================================================
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-${SELF_DIR}/../wheels}"
CPU_WHEELS="${CPU_WHEELS:-${SELF_DIR}/../../onprem/vendor/wheels}"

# 판 근거는 README.md §1 — cu128 도 onnxruntime-gpu 1.27 도 <쓰면 안 된다>. 거기 이유가 있다.
CUDA_INDEX="https://download.pytorch.org/whl/cu126"
TORCH_PIN="torch==2.12.0"
TORCHVISION_PIN="torchvision==0.27.0"
ORT_GPU_PIN="onnxruntime-gpu==1.26.0"

command -v docker >/dev/null || { echo "docker 가 필요합니다(대상과 같은 리눅스 x86_64 휠을 받는다)."; exit 1; }
[[ -d "${CPU_WHEELS}" ]] || { echo "CPU 매체 휠을 찾을 수 없습니다: ${CPU_WHEELS}"; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "${TMP}"' EXIT
echo "[1/3] 휠 내려받기 (인터넷 필요)..."
docker run --rm --platform linux/amd64 -v "${TMP}:/out" python:3.11-slim bash -c "
  pip download --no-cache-dir --dest /out --index-url ${CUDA_INDEX} \
    --extra-index-url https://pypi.org/simple ${TORCH_PIN} ${TORCHVISION_PIN} &&
  pip download --no-cache-dir --dest /out ${ORT_GPU_PIN}"

echo "[2/3] CPU 매체와 겹치는 것·드리프트 제외..."
mkdir -p "${OUT}"; rm -f "${OUT}"/*.whl "${OUT}/SHA256SUMS"
python3 - "${TMP}" "${CPU_WHEELS}" "${OUT}" <<'PY'
import sys, pathlib, shutil
src, cpu, out = map(pathlib.Path, sys.argv[1:4])
have = {f.name for f in cpu.iterdir()}
MUST = ("torch-", "torchvision-", "onnxruntime_gpu-", "nvidia_", "triton-", "cuda_")
n = 0
for f in sorted(src.glob("*.whl")):
    if f.name in have or not f.name.startswith(MUST):
        continue          # 이미 있거나, 잠긴 판이 있는데 새 판이 딸려온 것
    shutil.copy2(f, out / f.name); n += 1
print(f"   델타 휠 {n}개")
PY

echo "[3/3] 체크섬..."
( cd "${OUT}" && sha256sum *.whl > SHA256SUMS ) 2>/dev/null \
  || ( cd "${OUT}" && shasum -a 256 *.whl > SHA256SUMS )
echo "완료: ${OUT}  ($(du -sh "${OUT}" | cut -f1))"
