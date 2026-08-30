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
#     models/weights/yolox_s.onnx        : YOLOX 가중치(빌드머신의 ai-server/weights/ 에서 복사)
#                                          ⚠ git 에 없다(.gitignore) — 없으면 아래 스크립트로 먼저 받는다:
#                                            ai-server/scripts/download-yolox-weights.sh
#     models/hf-cache/                   : (옵션 PREFETCH_HF=1) HF 모델 캐시
#     licenses/python-packages/          : 위 wheel·sam2·가중치의 제3자 라이선스 고지
#                                          (2026-08-30 신설 — 아래 「라이선스 고지」 절)
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
# shellcheck source=../lib/licenses.sh
source "${SELF_DIR}/../lib/licenses.sh"

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
# facebookresearch/sam2 는 태그가 없어 full clone 후 SHA 체크아웃한다 — 첫 수집 시
# 수 분 소요될 수 있다(.git 은 vendor/번들 전에 제거한다, 아래 rm -rf .git 참고).
# 재현성 경고: SAM2_GIT_REF 미설정이면 기본 브랜치 HEAD 를 받게 되어 빌드마다 달라질 수 있다.
if [[ -z "${SAM2_GIT_REF:-}" ]]; then
  warn "[ai-server] SAM2_GIT_REF 미설정 — 기본 브랜치 HEAD 를 받습니다(재현성 보장 안 됨)."
  warn "  재현성을 위해 versions.sh 에 특정 커밋/태그를 고정하는 것을 권장합니다."
  confirm "그래도 기본 브랜치 HEAD 로 진행할까요?" \
    || die "[ai-server] SAM2_GIT_REF 를 versions.sh 에 고정한 뒤 다시 실행하세요."
fi
rm -rf "${SAM2_OUT}/sam2-src"
if [[ -n "${SAM2_GIT_REF:-}" ]]; then
  # SAM2_GIT_REF 는 40자 커밋 SHA(브랜치/태그 아님) → clone 후 정확한 커밋으로 checkout.
  #   --branch 는 브랜치/태그만 받으므로 SHA 에는 쓸 수 없다. 전체 clone 후 checkout 한다.
  git clone "${SAM2_GIT_URL}" "${SAM2_OUT}/sam2-src"
  ( cd "${SAM2_OUT}/sam2-src" && git checkout --quiet "${SAM2_GIT_REF}" ) \
    || die "[ai-server] sam2 커밋 체크아웃 실패: ${SAM2_GIT_REF} (versions.sh 의 SAM2_GIT_REF 확인)"
  info "[ai-server] sam2 ref 고정: ${SAM2_GIT_REF}"
else
  # 핀 미설정 — 기본 브랜치 HEAD(재현성 보장 안 됨, 위에서 confirm 받음).
  git clone --depth 1 "${SAM2_GIT_URL}" "${SAM2_OUT}/sam2-src"
fi
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

# ---- 3-b) PEP 517 빌드 백엔드 wheel 반입 (폐쇄망 필수) ----
#   ★ 없으면 폐쇄망에서 <설치가 멈춘다>. 2026-08-30 실측으로 확인한 실패 경로:
#     ① requirements.txt 는 pip-compile 산출물이라 끝에 "considered to be unsafe … setuptools"
#        주석만 남고 setuptools 가 <락에서 제외>돼 있다 → vendor/wheels 에 들어가지 않는다.
#     ② 그런데 이 번들에는 wheel 이 없어 sdist 로만 받히는 패키지가 있고(아래 검사),
#        sam2 도 <로컬 소스>로 설치한다. 둘 다 PEP 517 빌드를 거친다.
#     ③ pip 는 빌드 격리(build isolation) 환경을 새로 만들고 그 안에서 setuptools·wheel 을
#        <다시 찾는다>. venv 에 setuptools 가 이미 있어도 <격리 환경은 그것을 쓰지 않는다>.
#        실측: "ERROR: Could not find a version that satisfies the requirement setuptools>=61
#               (from versions: none)" → 설치 실패.
#     ④ --no-index --find-links 로 도는 폐쇄망에서는 그 조회가 인터넷으로 나가지도 못한다.
#   → 그래서 빌드 백엔드(setuptools·wheel)를 명시적으로 wheels 에 넣는다.
#   ⚠ pip 자체는 반입 대상이 아니다 — 번들 python(python-build-standalone)의 venv 가
#     ensurepip 로 pip 를 제공한다(13-install-ai-server.sh 는 pip 업그레이드를 하지 않는다).
#   ★ 버전은 versions.sh 의 PEP517_* 핀으로 고정한다(단일 출처). 락의 다른 의존성은 전부
#     pip-compile 로 고정되는데 이 보강분만 "수집 시점의 최신"으로 흘러가면, 빌드머신을 언제
#     돌리느냐에 따라 반입물이 달라져 재현성이 깨진다.
#   ★ 하한 검증: sam2 의 [build-system] requires 가 setuptools>=61.0 이다(SAM2_GIT_REF 커밋의
#     pyproject.toml). 핀을 그 아래로 낮추면 폐쇄망에서 sam2 빌드가 "찾을 수 없다"로 실패하므로
#     받기 전에 막는다.
_su_ver="${PEP517_SETUPTOOLS_PIN#setuptools==}"
[[ "$(printf '%s\n61.0\n' "${_su_ver}" | sort -V | head -1)" == "61.0" ]] \
  || die "[ai-server] setuptools 핀(${PEP517_SETUPTOOLS_PIN})이 sam2 의 하한 61.0 미만입니다 — versions.sh 의 PEP517_SETUPTOOLS_PIN 을 올리세요."

info "[ai-server] (보강) PEP 517 빌드 백엔드 wheel 수집(${PEP517_SETUPTOOLS_PIN}, ${PEP517_WHEEL_PIN})..."
"${PYBIN}" -m pip download \
  --dest "${WHEELS}" \
  --only-binary=:all: \
  "${PEP517_SETUPTOOLS_PIN}" "${PEP517_WHEEL_PIN}" \
  || die "[ai-server] setuptools/wheel wheel 다운로드 실패 — 이대로 반입하면 폐쇄망에서 sam2/sdist 설치가 실패합니다."

# ---- 3-c) sdist 혼입 점검 (폐쇄망 경고) ----
#   sdist(.tar.gz)는 설치 시점에 빌드를 요구한다. 순수 파이썬이면 위 빌드 백엔드로 충분하지만,
#   C 확장 sdist 라면 타깃에 컴파일러·헤더가 필요해진다 — 폐쇄망에서 그 자리에 멈춘다.
#   ★ --only-binary=:all: 를 <전체에 강제하지는 않는다>: 2026-08-30 실측 기준 이 lock 에는
#     wheel 이 아예 없는 패키지가 2개(antlr4-python3-runtime, iopath) 있어 강제하면 수집이
#     실패한다. 둘 다 순수 파이썬이라 빌드 백엔드만 있으면 설치된다.
_sdists="$(ls -1 "${WHEELS}"/*.tar.gz 2>/dev/null | wc -l | tr -d ' ')"
if [[ "${_sdists}" -gt 0 ]]; then
  warn "[ai-server] sdist(.tar.gz) ${_sdists} 개가 번들에 포함됩니다 — 타깃에서 빌드가 필요합니다:"
  ls -1 "${WHEELS}"/*.tar.gz 2>/dev/null | sed 's|.*/|      |'
  warn "  순수 파이썬이면 위에서 반입한 setuptools/wheel 로 설치됩니다."
  warn "  C 확장이 섞이면 타깃에 컴파일러·헤더가 필요하니 02-build-package.md 의 무네트워크"
  warn "  설치 리허설로 반드시 확인하세요."
fi

ok "[ai-server] wheel 수집: ${WHEELS}  ($(ls -1 "${WHEELS}"/*.whl "${WHEELS}"/*.tar.gz 2>/dev/null | wc -l | tr -d ' ') 개, $(du -sh "${WHEELS}" | cut -f1))"

# ---- 4) YOLOX 가중치 동봉 ----
#   ★ 이 파일은 <repo 동봉본이 아니다>. .gitignore 대상이라 git 에 없고, 빌드머신에서
#     ai-server/scripts/download-yolox-weights.sh 로 받아 둔 것을 복사한다.
#     출처: Megvii-BaseDetection/YOLOX 릴리스 0.1.1rc0 자산(Apache-2.0). 상세는
#     ai-server/weights/README.md.
#   ★ 존재만 보고 복사하지 않는다 — <해시를 검증>한다. 가중치는 그 자체로 검증할 방법이
#     없는 바이너리라, 잘못된/손상된 파일이 들어와도 서버는 정상 기동하고 200 을 돌려준다.
#     즉 잘못된 반입이 폐쇄망에서 <조용히> 성립한다. 기대 해시의 단일 출처는 git 추적 파일
#     ai-server/weights/yolox_s.onnx.sha256 이며 다운로드 스크립트와 같은 파일을 읽는다.
_yolox_src="${AI_SRC}/weights/yolox_s.onnx"
_yolox_sha_file="${AI_SRC}/weights/yolox_s.onnx.sha256"
if [[ -f "${_yolox_src}" ]]; then
  if [[ -f "${_yolox_sha_file}" ]]; then
    _yolox_expected="$(awk '{print tolower($1)}' "${_yolox_sha_file}" | head -1)"
    [[ "${_yolox_expected}" =~ ^[0-9a-f]{64}$ ]] \
      || die "[ai-server] 기대 해시 형식 오류: ${_yolox_sha_file}"
    # 불일치면 die (fail-closed) — 잘못된 가중치를 반입물에 싣지 않는다.
    verify_file_sha256 "${_yolox_src}" "${_yolox_expected}" "yolox_s.onnx"
  else
    die "[ai-server] 기대 해시 파일 없음: ${_yolox_sha_file}
       이 파일은 git 추적 대상입니다(ai-server/.gitignore 의 예외). 저장소 상태를 확인하세요."
  fi
  cp "${_yolox_src}" "${WEIGHTS_OUT}/yolox_s.onnx"
  ok "[ai-server] 가중치 수집: ${WEIGHTS_OUT}/yolox_s.onnx  ($(du -h "${WEIGHTS_OUT}/yolox_s.onnx" | cut -f1))"
else
  warn "[ai-server] yolox 가중치 없음: ${_yolox_src} — 탐지(YOLOX 단일) 실행에 필요"
  warn "  git 에 없는 파일입니다. 빌드머신에서 아래를 먼저 실행하세요:"
  warn "    (cd ${AI_SRC} && ./scripts/download-yolox-weights.sh)"
fi

# ---- 5) SAM2 모델 prefetch (기본 수행) ----
# ★ 기본값을 뒤집었다(구: 기본 생략 → 신: 기본 수행). 2026-08-19
#
#   SAM2(클릭/박스 분할·Track)는 <제품이 제공하는 기능>이다. 그런데 기본 생략이라
#   폐쇄망 번들에 모델이 들어가지 않았고, 그 결과 다음이 <조용히> 성립했다:
#     ① 번들에 HF 캐시 없음
#     ② 대상 서버는 HF_HUB_OFFLINE=1 이라 다운로드를 시도조차 하지 않음
#     ③ 로더가 예외를 삼키고 mock 으로 폴백(load_failed)
#     ④ 서버는 정상 기동하고 API 도 200 을 돌려준다
#   즉 SAM2 분할·Track 이 전부 가짜 응답인데 <어디서도 실패로 드러나지 않는다>.
#
#   그래서 기본 수행으로 바꾸고, 실패하면 <빌드를 멈춘다>. 빌드머신에서 시끄럽게
#   실패하는 편이 폐쇄망에서 조용히 기능이 죽는 것보다 낫다.
#
#   SAM2 를 정말 쓰지 않는 납품이면 PREFETCH_HF=0 으로 명시적으로 끈다.
if [[ "${PREFETCH_HF:-1}" != "0" ]]; then
  info "[ai-server] HF 모델 prefetch (HF_HOME=${HF_OUT})..."
  if "${PYBIN}" -c 'import huggingface_hub' 2>/dev/null; then
    HF_HOME="${HF_OUT}" "${PYBIN}" - <<PY || die "[ai-server] SAM2 모델 prefetch 실패 — 이대로 반입하면 폐쇄망에서 SAM2 가 mock 으로 동작합니다. 네트워크를 확인하고 다시 실행하거나, SAM2 를 쓰지 않는 납품이면 PREFETCH_HF=0 으로 명시하세요."
import os
from huggingface_hub import snapshot_download
for mid in ["${HF_SAM2_MODEL_ID}"]:
    print("download:", mid)
    snapshot_download(repo_id=mid)
PY
    ok "[ai-server] HF 모델 캐시: ${HF_OUT}"
  else
    die "[ai-server] huggingface_hub 미설치 — SAM2 모델을 받을 수 없습니다. 'pip install huggingface_hub' 후 다시 실행하세요. (SAM2 를 쓰지 않는 납품이면 PREFETCH_HF=0)"
  fi
else
  warn "[ai-server] ★SAM2 모델 prefetch 를 명시적으로 껐습니다(PREFETCH_HF=0)."
  warn "  대상 서버는 HF_HUB_OFFLINE=1 이라 모델을 내려받지 못하고, SAM2 분할·Track 은"
  warn "  <mock 응답>으로 동작합니다. 서버는 정상 기동하고 API 도 200 을 돌려주므로"
  warn "  운영 중에는 이 사실이 드러나지 않습니다. 의도한 것이 맞는지 확인하세요."
fi

# ---- 라이선스 고지 수집 (wheel + sam2 + 가중치) -----------------------------
#   ★ 대상은 <실제로 반입되는 wheel 파일>이다. requirements.txt 를 읽어 목록을 만들면
#     torch 처럼 별도 인덱스에서 받은 것·빌드 백엔드 보강분(setuptools/wheel)이 빠진다.
#   ★ wheel 안 고지 파일은 두 자리에 있다 — PEP 639 이후는 `*.dist-info/licenses/**`,
#     그 이전은 dist-info 바로 아래. 둘 다 훑는다(한쪽만 보면 세대 하나를 통째로 놓친다).
#   ★★ opencv-python 의 `LICENSE-3RD-PARTY.txt` 가 여기서 <반드시> 딸려 나와야 한다.
#     그 파일이 이 반입물의 유일한 FFmpeg(LGPL-2.1)·Qt5(LGPL-3.0) 고지다.
#     ⚠ 그 두 건은 고지만으로 끝나지 않는다 — 대응 소스 제공 의무가 남는다.
#       licenses/manual/LGPL-SOURCE-OFFER.md 를 함께 반입할 것.
LIC_PY_PKG="$(lic_root)/python-packages"
if ! command -v unzip >/dev/null 2>&1; then
  warn "[ai-server] unzip 이 없어 wheel 라이선스 고지를 수집하지 못했습니다."
else
  lic_reset_area "python-packages"
  _pinv="${LIC_PY_PKG}/INVENTORY.tsv"
  lic_inventory_init "${_pinv}"
  _pw=0; _ptxt=0; _pnam=0; _punres=0
  while IFS= read -r _whl; do
    [[ -n "${_whl}" ]] || continue
    _pw=$((_pw+1))
    _wb="$(basename "${_whl}")"
    # wheel 파일명 규약: {name}-{version}-{pytag}-...
    _wname="${_wb%%-*}"
    _wver="$(printf '%s' "${_wb#"${_wname}"-}" | cut -d- -f1)"
    _cnt="$(lic_wheel_copy_notices "${_whl}" "${LIC_PY_PKG}/$(lic_slug "${_wname}-${_wver}")")"
    _wlic="$(lic_wheel_metadata_license "${_whl}")"
    if [[ "${_cnt}" -gt 0 ]]; then
      _ptxt=$((_ptxt+1)); _psrc="ARCHIVE"; _pst="OK"
    elif [[ -n "${_wlic}" ]]; then
      _pnam=$((_pnam+1)); _psrc="METADATA"; _pst="TEXT_MISSING"
    else
      _punres=$((_punres+1)); _psrc="NONE"; _pst="UNRESOLVED"
    fi
    lic_inventory_add "${_pinv}" python-packages "${_wname}" "${_wver}" "${_wlic}" "${_psrc}" "${_pst}"
  done < <(find "${WHEELS}" -maxdepth 1 -name '*.whl' -type f | sort)

  # sdist(.tar.gz)도 반입물이다 — 이름/버전만 인벤토리에 남긴다(내부 구조가 제각각이라
  # 고지 파일 위치를 일반화할 수 없다. 전문은 사람이 OVERRIDES.tsv 로 채운다).
  while IFS= read -r _sd; do
    [[ -n "${_sd}" ]] || continue
    _sb="$(basename "${_sd}" .tar.gz)"
    lic_inventory_add "${_pinv}" python-packages "${_sb}" "" "" "NONE" "UNRESOLVED"
    _punres=$((_punres+1))
  done < <(find "${WHEELS}" -maxdepth 1 -name '*.tar.gz' -type f | sort)

  # sam2 는 wheel 이 아니라 <소스 디렉터리>로 반입되므로 따로 챙긴다.
  if [[ -d "${SAM2_OUT}/sam2-src" ]]; then
    _n=0
    while IFS= read -r _f; do
      [[ -n "${_f}" ]] || continue
      mkdir -p "${LIC_PY_PKG}/sam2"
      cp -f "${_f}" "${LIC_PY_PKG}/sam2/$(lic_slug "$(basename "${_f}")")" && _n=$((_n+1))
    done < <(find "${SAM2_OUT}/sam2-src" -maxdepth 1 -type f \
               \( -iname 'LICENSE' -o -iname 'LICENSE.*' -o -iname 'NOTICE' -o -iname 'NOTICE.*' \) 2>/dev/null | sort)
    if [[ "${_n}" -gt 0 ]]; then
      lic_inventory_add "${_pinv}" python-packages "sam2 (소스 vendor)" "${SAM2_GIT_REF:-}" "" ARCHIVE OK
    else
      lic_inventory_add "${_pinv}" python-packages "sam2 (소스 vendor)" "${SAM2_GIT_REF:-}" "" NONE UNRESOLVED
      _punres=$((_punres+1))
    fi
  fi

  # YOLOX 가중치 — 바이너리라 안에 고지가 없다. 출처·라이선스는 저장소 기록이 정본이므로
  # 그 기록 파일을 그대로 동봉한다(우리가 문장을 새로 쓰지 않는다).
  if [[ -f "${WEIGHTS_OUT}/yolox_s.onnx" ]]; then
    if [[ -f "${AI_SRC}/weights/README.md" ]]; then
      mkdir -p "${LIC_PY_PKG}/yolox-weights"
      cp -f "${AI_SRC}/weights/README.md" "${LIC_PY_PKG}/yolox-weights/PROVENANCE.md"
      lic_inventory_add "${_pinv}" python-packages "yolox_s.onnx (모델 가중치)" "0.1.1rc0" "Apache-2.0" METADATA TEXT_MISSING
      _pnam=$((_pnam+1))
    else
      lic_inventory_add "${_pinv}" python-packages "yolox_s.onnx (모델 가중치)" "" "" NONE UNRESOLVED
      _punres=$((_punres+1))
    fi
  fi

  # SAM2 HF 모델 캐시 — 받았을 때만 인벤토리에 남긴다(전문은 캐시에 없다).
  if [[ -n "$(ls -A "${HF_OUT}" 2>/dev/null || true)" ]]; then
    lic_inventory_add "${_pinv}" python-packages "${HF_SAM2_MODEL_ID} (HF 모델 캐시)" "" "" NONE UNRESOLVED
    _punres=$((_punres+1))
  fi

  ok "[ai-server] 라이선스 고지 수집: ${LIC_PY_PKG} (wheel ${_pw} / 전문 ${_ptxt} / 이름만 ${_pnam} / 미해석 ${_punres})"
  if [[ ! -d "${LIC_PY_PKG}" ]] || ! find "${LIC_PY_PKG}" -name 'LICENSE-3RD-PARTY*' -print -quit | grep -q .; then
    warn "[ai-server] ★opencv 의 LICENSE-3RD-PARTY 고지를 찾지 못했습니다."
    warn "  그 파일이 이 반입물의 유일한 FFmpeg(LGPL-2.1)·Qt5(LGPL-3.0) 고지입니다 — 반드시 확인하세요."
  fi
  [[ "${_punres}" -gt 0 ]] && warn "[ai-server] 미해석 ${_punres} 건 — licenses/manual/OVERRIDES.tsv 에 사람이 적어야 합니다."
fi

# ---- 무결성 체크섬 ----
sha256_write "${WHEELS}"
[[ -f "${WEIGHTS_OUT}/yolox_s.onnx" ]] && sha256_write "${WEIGHTS_OUT}" || true
