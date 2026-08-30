#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# 30-collect-ai-server.sh — [빌드머신] ai-server 소스 + pip wheel + sam2 + 모델 수집
# @step 인터넷=필요 | 소요=수 분 | 선행=없음 | 재실행=안전(el8/x86_64 전용 — 다른 플랫폼은 착수 전에 가드가 막는다)
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
#
#   ⚠⚠ 이 단계는 빌드머신 OS 가 타깃과 같을 때만 안전하다 (2026-08-30 실측).
#     `pip download` 가 "이미 받은 파일은 건너뛴다"는 성질은 <같은 플랫폼일 때만> 성립한다.
#     이미 manylinux wheel 이 들어 있는 매체 위에서 맥(darwin/arm64)으로 이 단계를 돌리면,
#     pip 는 그 wheel 들이 자기 플랫폼에 안 맞는다고 보고 <맥용 wheel 을 새로 받아 나란히 쌓는다>.
#     실측: 21개 · +197MB 가 vendor/wheels 에 섞였고, 그중 torch 가 CPU 판이 아닌 기본 판이라
#     아래 3-c 의존성 폐포 검사가 CUDA 의존을 요구하며 die 했다(검사는 제 일을 했다 —
#     다만 <오염이 먼저 일어나고> 나서 죽었다).
#   ★ 그래서 아래 0) 에 <플랫폼 가드>를 두어 다운로드 <앞에서> 막는다(2026-08-30 신설).
#     이제 맥에서 돌리면 vendor/wheels 에 손도 대지 않고 사유·올바른 실행법과 함께 죽는다.
#     폐포 검사는 그대로 남는다 — 가드는 "어디서 돌리는가", 폐포 검사는 "무엇을 받았는가"라
#     서로 다른 것을 본다. 하나가 다른 하나를 대신하지 않는다.
#     → 올바른 실행: el8/x86_64 컨테이너·머신에서 돌린다(02-build-package.md).
#     → 가드 이전 판으로 이미 섞였다면 `vendor/wheels/*macosx*.whl` 을 지우면 원상복구된다
#       (없어지는 파일은 없고 새로 생기기만 하므로 되돌릴 수 있다). 0-b 가 그 사실을 알린다.
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

# ---- 0) 플랫폼 가드 — <오염이 일어나기 전에> 막는다 (2026-08-30 신설) ----
#   왜 필요한가: 이 단계의 `pip download` 는 <돌고 있는 머신의 플랫폼>으로 의존성을 해석한다.
#   맥(darwin/arm64)에서 돌리면 manylinux wheel 이 이미 있어도 "내 플랫폼에 안 맞는다"고 보고
#   맥용 wheel 을 새로 받아 나란히 쌓는다(실측: 21개 · +197MB). 3-c 의 의존성 폐포 검사가
#   그걸 잡아 die 하지만 <오염이 먼저 일어나고 나서> 죽는다 — 사람이 손으로 되돌려야 한다.
#   그래서 다운로드 <앞>에서 판정한다. 여기서 막으면 vendor/wheels 는 손도 대지 않는다.
#
#   ★ 수집 로직(인덱스 URL · 버전 핀 · --no-deps · 폐포 검사)은 <아무것도 바꾸지 않는다>.
#     이 가드는 "어디서 돌리는가"만 본다.
#   ⚠ 이 가드가 없어도 맥에서는 어차피 이 단계가 성공할 수 없다(폐포 검사에서 죽는다).
#     달라지는 것은 <매체가 더럽혀지느냐 아니냐>다.
if [[ "${ALLOW_FOREIGN_PLATFORM_WHEELS:-0}" != "1" ]]; then
  _bs="$(uname -s)"; _bm="$(uname -m)"
  if [[ "${_bs}" != "Linux" || "${_bm}" != "x86_64" ]]; then
    _deploy_dir="$(cd "${ONPREM}/.." && pwd)"
    die "[ai-server] 이 단계는 타깃과 같은 플랫폼에서만 돌립니다 — 현재: ${_bs} ${_bm} / 타깃: Linux x86_64 (RHEL 8.9, glibc 2.28).
     여기서 그대로 진행하면 ${_bs}/${_bm} 용 wheel 이 vendor/wheels 에 <섞여 쌓입니다>
     (실측: 21개 · +197MB). 뒤의 의존성 폐포 검사가 잡아내지만 그때는 이미 오염된 뒤입니다.

     올바른 실행(02-build-package.md):
       el8 / x86_64 컨테이너나 머신에서 이 단계를 돌리세요. 예)
         docker run --rm --platform ${EL8_BUILDER_PLATFORM:-linux/amd64} \\
           -v ${_deploy_dir}:/w -w /w/onprem ${EL8_BUILDER_IMAGE:-rockylinux/rockylinux:8} \\
           bash -lc './scripts/package-step.sh 30'

     이미 섞였다면(되돌리는 명령):
       rm -f ${WHEELS}/*macosx*.whl ${WHEELS}/*win32*.whl ${WHEELS}/*win_amd64*.whl
       (없어지는 정상 파일은 없습니다 — 맥/윈도우 태그는 타깃에서 쓰이지 않습니다.)

     그래도 강행하려면: ALLOW_FOREIGN_PLATFORM_WHEELS=1 (오염을 감수한다는 뜻입니다)"
  fi
  unset _bs _bm _deploy_dir
else
  warn "[ai-server] ALLOW_FOREIGN_PLATFORM_WHEELS=1 — 플랫폼 가드를 껐습니다."
  warn "            타깃과 다른 플랫폼의 wheel 이 vendor/wheels 에 섞일 수 있습니다."
fi

# ---- 0-b) 이미 섞여 있는 외래 플랫폼 wheel 보고 ----
#   가드는 <앞으로의 오염>만 막는다. 가드 이전 판으로 돌려 이미 섞인 매체가 있을 수 있어
#   여기서 세어 알린다(자동 삭제하지 않는다 — 지우는 판단은 사람이 한다).
_foreign_whl="$(find "${WHEELS}" -maxdepth 1 -type f -name '*.whl' \
  \( -name '*macosx*' -o -name '*win32*' -o -name '*win_amd64*' \) 2>/dev/null | wc -l | tr -d ' ')"
if [[ "${_foreign_whl}" != "0" ]]; then
  warn "[ai-server] vendor/wheels 에 타깃과 다른 플랫폼의 wheel ${_foreign_whl}개가 이미 섞여 있습니다."
  warn "            되돌리기: rm -f ${WHEELS}/*macosx*.whl ${WHEELS}/*win32*.whl ${WHEELS}/*win_amd64*.whl"
fi
unset _foreign_whl

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
#   함정 2건(2026-08-30 수정):
#     (1) __pycache__/*.pyc 가 함께 복사되어 빌드머신의 바이트코드가 반입물에 실린다.
#         빌드머신 파이썬이 대상(3.11)과 다르면 cpython-314 같은 태그가 매체에 남는다.
#         동작에는 무해하나(3.11 은 자기 태그가 아닌 캐시를 무시한다) 반입물 위생 문제다.
#         제외를 tar --exclude 로 하지 않고 복사 후 find 로 지우는 이유는 tar 방언 차이다 —
#         GNU tar 는 --exclude='./__pycache__' 를 선두 고정으로 해석해 중첩된 것을 놓치고
#         bsdtar 는 잡는다. 빌드머신의 tar 가 어느 쪽인지에 결과가 좌우되면 안 된다.
#     (2) tests 에 rm -rf 가 없어 재수집할 때마다 한 겹씩 중첩됐다.
#         cp -R src dst 는 dst 가 이미 있으면 그 안으로 들어가므로 tests/tests/tests/... 로
#         쌓인다(실제로 tests/tests 까지 진행돼 있었다).
info "[ai-server] 앱 소스 복사..."
rm -rf "${OUT}/app" "${OUT}/tests"
cp -R "${AI_SRC}/app" "${OUT}/app"
cp "${AI_SRC}/requirements.txt" "${OUT}/requirements.txt"
# tests 는 운영에 불필요하나 스모크 참고용으로 동봉(선택)
[[ -d "${AI_SRC}/tests" ]] && cp -R "${AI_SRC}/tests" "${OUT}/tests" || true
# 파이썬 바이트코드 캐시 제거 — 방금 복사한 트리 안에서만 지운다.
for _pysrc in "${OUT}/app" "${OUT}/tests"; do
  [[ -d "${_pysrc}" ]] || continue
  find "${_pysrc}" -type d -name '__pycache__' -prune -exec rm -rf {} +
  find "${_pysrc}" \( -name '*.pyc' -o -name '*.pyo' \) -delete
done
unset _pysrc
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
#
# ★★ --no-deps 로 <본체만> 받는다 (2026-08-30 실측 실패 대응).
#   --index-url 은 PyPI 를 <대체>한다(추가가 아니다). 그래서 torch 의 의존성까지 CPU 인덱스라는
#   좁은 인덱스 안에서만 찾게 되고, 그 인덱스에는 torch 계열 말고는 빌드 의존이 없다.
#   실측 실패 경로:
#     ① typing-extensions 4.16.0 <wheel> 을 pip 가 discard 한다 —
#        "inconsistent Name: expected 'typing-extensions', but metadata has 'typing_extensions'"
#        (CPU 인덱스가 노출하는 이름과 wheel METADATA 의 이름 표기가 어긋난다)
#     ② wheel 이 버려지니 sdist(typing_extensions-4.16.0.tar.gz) 로 폴백
#     ③ 그 sdist 의 PEP 517 빌드 의존 flit_core<4,>=3.11 을 CPU 인덱스에서 찾지 못함
#        → "ERROR: No matching distribution found for flit_core" 로 <수집 전체가 죽는다>
#   ⚠ 해법으로 --extra-index-url(PyPI) 를 더하지 말 것 — 위 금지 사유가 그대로 되살아난다
#     (CPU 판이 없거나 우선순위가 밀리면 수 GB 짜리 CUDA torch 가 조용히 섞인다).
#   → 대신 <의존성 해석 자체를 끈다>. torch/torchvision 의 의존성은 위 (1/2) 단계에서 이미
#     전부 받혔다 — requirements.txt 가 pip-compile 산출물이라 torch 의 의존성(filelock ·
#     typing-extensions · sympy · networkx · jinja2 · fsspec · numpy · pillow …)도 그 lock 안에
#     함께 고정돼 있기 때문이다.
#   ⚠ --no-deps 는 "의존성이 이미 다 있다"는 <전제에 기댄다>. 그 전제가 깨지면 수집은 성공으로
#     끝나고 <폐쇄망 설치가 그 자리에서 죽는다> — 조용한 실패다.
#     그래서 아래 3-c 에서 받은 wheel 의 Requires-Dist 를 읽어 폐포를 <반드시> 검증한다.
#     그 검증을 지우면 이 --no-deps 는 안전하지 않다. 세트로 다뤄야 한다.
"${PYBIN}" -m pip download \
  --dest "${WHEELS}" \
  --index-url "${PYTORCH_CPU_INDEX_URL}" \
  --no-deps \
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
#   ★ 하한 사전검사: sam2 의 [build-system] requires 가 setuptools>=61.0 이다(SAM2_GIT_REF 커밋의
#     pyproject.toml). 핀을 그 아래로 낮추면 폐쇄망에서 sam2 빌드가 "찾을 수 없다"로 실패하므로
#     받기 전에 막는다.
#   ⚠ 이 61.0 은 <사전검사용 사본>이고 판정의 정본이 아니다. 아래 3-c 가 vendor 한 sam2 의
#     pyproject.toml 을 직접 읽어 상·하한을 대조하므로, sam2 가 요구를 바꾸면 그쪽이 잡는다.
#     여기 숫자는 "받기 전에 싸게 걸러내는" 용도이므로 어긋나도 최종 판정은 3-c 가 한다.
#   ⚠ <상한은 여기서 검사하지 않는다>. 상한(torch 의 setuptools<82)은 우리가 외울 값이 아니라
#     받은 torch wheel 의 METADATA 에 있다 — 여기에 숫자로 박으면 torch 핀이 바뀔 때 조용히
#     낡는 두 번째 진실원이 된다. 3-c 가 METADATA 를 읽어 판정한다.
_su_ver="${PEP517_SETUPTOOLS_PIN#setuptools==}"
[[ "$(printf '%s\n61.0\n' "${_su_ver}" | sort -V | head -1)" == "61.0" ]] \
  || die "[ai-server] setuptools 핀(${PEP517_SETUPTOOLS_PIN})이 sam2 의 하한 61.0 미만입니다 — versions.sh 의 PEP517_SETUPTOOLS_PIN 을 올리세요."

info "[ai-server] (보강) PEP 517 빌드 백엔드 wheel 수집(${PEP517_SETUPTOOLS_PIN}, ${PEP517_WHEEL_PIN})..."
# ★★ --no-deps 로 <핀한 두 개만> 받는다 (2026-08-30 — packaging 이중 반입 대응).
#   wheel 은 packaging>=24.0 을 요구한다. --no-deps 없이 받으면 pip 가 그 요구를 <락과 무관하게>
#   새로 풀어 PyPI 최신을 함께 끌어온다(실측: packaging 26.3 이 딸려 왔다). 그런데 락은
#   packaging==26.2 를 고정하고 있어 같은 배포물이 <두 판> 반입된다. 설치를 깨뜨리지는 않지만
#   ① 재현성이 깨지고(빌드머신을 언제 돌리느냐에 따라 반입물이 달라진다)
#   ② 폐쇄망에서 어느 판이 쓰이는지가 pip 의 해석에 맡겨지며
#   ③ 라이선스 인벤토리가 같은 패키지를 두 줄로 계상한다.
#   → 락이 이미 고정한 의존은 <락에서만> 온다. 위 torch --no-deps 와 같은 원칙이다.
#   ⚠ 이 --no-deps 도 전제("그 의존은 이미 락에 있다")에 기댄다. 그래서 아래 3-c 가
#     setuptools·wheel 을 <검증 루트에 포함해> 폐포를 확인한다. 세트로 다뤄야 한다.
"${PYBIN}" -m pip download \
  --dest "${WHEELS}" \
  --only-binary=:all: \
  --no-deps \
  "${PEP517_SETUPTOOLS_PIN}" "${PEP517_WHEEL_PIN}" \
  || die "[ai-server] setuptools/wheel wheel 다운로드 실패 — 이대로 반입하면 폐쇄망에서 sam2/sdist 설치가 실패합니다."

# ---- 3-c) 의존성 폐포 검증 — <버전 지정자까지> 대조 (--no-deps 의 안전장치) ----
#   ★ 위 (2/2)·(보강) 두 단계가 --no-deps 로 <본체만> 받는다. 그 전제("의존성은 이미 다
#     받혔다")가 깨지면 수집은 <성공으로 끝나고> 폐쇄망 설치가 그 자리에서 죽는다. 빌드머신에서
#     시끄럽게 실패하는 편이 낫다 — 그래서 받은 wheel 의 METADATA(Requires-Dist)를 실제로 읽어
#     반입물(vendor/wheels) 안에서 의존성이 <닫히는지> 확인하고, 하나라도 어긋나면 die 한다.
#
#   ★★ 이름만 보지 않고 <버전 지정자를 평가>한다 (2026-08-30 — 이 검사가 실패를 통과시킨 뒤 보강).
#     구 검사는 "그 이름의 배포물이 있는가"만 봤다. 그래서 다음이 <통과>했다:
#         torch 2.12.0+cpu 의 "Requires-Dist: setuptools<82"  vs  반입된 setuptools 84.0.0
#     이름은 있으니 통과 → 폐쇄망에서 sam2 빌드가 ResolutionImpossible 로 죽었다.
#     이름 존재는 필요조건일 뿐이고, 설치를 가르는 것은 <버전>이다.
#
#   ★ sam2 의 [build-system] requires 도 함께 본다. sam2 는 wheel 이 아니라 <소스 디렉터리>로
#     반입되므로 그 빌드 의존(setuptools>=61.0, torch>=2.5.1)은 어떤 wheel METADATA 에도 없다.
#     wheel 만 훑는 검사는 이 축을 <구조적으로> 볼 수 없다. 실제 실패가 난 자리가 여기다.
#
#   ★ requirements.txt 를 다시 읽어 판정하지 않는다 — 그러면 "lock 이 곧 진실"이라는 같은
#     전제를 두 번 믿는 것이라 검증이 되지 않는다. 판정 근거는 <실제로 받은 파일>이다.
#   ★ 마커는 <타깃 환경>(cp311 · Linux · x86_64) 기준으로 평가한다. 빌드머신의 기본 환경으로
#     평가하면 mac 에서 돌릴 때 sys_platform=='darwin' 이 되어 리눅스 전용 의존을 통째로
#     건너뛴다 — 검사가 조용히 무력해진다.
#   ★ extras(선택 기능) 의존은 제외한다. 우리는 extras 없이 설치하므로 설치 대상이 아니다.
#   ★ 폐포를 <재귀로> 따라간다. 직접 의존만 보면 그 아래 한 단이 비었을 때를 놓친다.
#   ★ <평가하지 못한 것은 통과시키지 않고 이름을 남긴다>. "검사 못 함"과 "통과"는 다르다 —
#     구 검사는 파싱 실패한 요구사항을 조용히 continue 했다(그 요구는 아무도 안 본 게 된다).
info "[ai-server] (검증) 반입물 안에서 의존성이 <버전까지> 닫히는지 확인..."
"${PYBIN}" - "${WHEELS}" "${SAM2_OUT}/sam2-src" <<'PY' || die "[ai-server] 반입물(vendor/wheels)의 의존성이 닫히지 않습니다.
       위에 [없음]/[불만족] 으로 표시된 항목이 폐쇄망 설치를 실패시킵니다.
       조치(누락): 누락분을 ai-server/requirements.txt(lock)에 반영해 (1/2) 단계에서 함께 받게 하세요.
       조치(버전 불만족): versions.sh 의 해당 핀을 지정자를 만족하는 값으로 조정하세요
         — 특히 PEP517_SETUPTOOLS_PIN 은 sam2 의 하한과 torch 의 상한을 <동시에> 만족해야 합니다.
       ⚠ torch 다운로드에 --extra-index-url(PyPI)을 더해 해결하려 하지 마세요 — CUDA wheel 혼입 경로가 열립니다."
import pathlib
import re
import sys
import zipfile

wheels = pathlib.Path(sys.argv[1])
sam2_src = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else None


def norm(name):
    """PEP 503 정규화 — typing_extensions 와 typing-extensions 를 같은 것으로 본다."""
    return re.sub(r"[-_.]+", "-", name).strip().lower()


try:
    from pip._vendor.packaging.requirements import Requirement
    from pip._vendor.packaging.version import InvalidVersion, Version
except Exception:
    try:
        from packaging.requirements import Requirement
        from packaging.version import InvalidVersion, Version
    except Exception:
        print("packaging 모듈을 찾을 수 없어 의존성 폐포를 검증하지 못했습니다.")
        print("  pip 가 vendor 한 packaging 도, 독립 packaging 도 없습니다.")
        print("  ★ 이 검사를 건너뛴 채로 반입하지 마세요 — 이 검사가 없으면 위 --no-deps 는")
        print("    안전하지 않습니다(폐쇄망 설치 실패가 빌드 시점에 드러나지 않습니다).")
        sys.exit(2)

# 반입물 인덱스: {정규화이름: [(Version|None, 파일, wheel여부), ...]}
#   wheel  파일명 규약: {name}-{version}-{pytag}-{abi}-{platform}.whl
#   sdist  파일명 규약: {name}-{version}.tar.gz
present = {}


def _add(name, ver_str, path, is_wheel):
    try:
        ver = Version(ver_str)
    except (InvalidVersion, Exception):
        ver = None
    present.setdefault(norm(name), []).append((ver, path, is_wheel))


for f in sorted(wheels.iterdir()):
    if f.name.endswith(".whl"):
        parts = f.name[: -len(".whl")].split("-")
        if len(parts) >= 2:
            _add(parts[0], parts[1], f, True)
    elif f.name.endswith(".tar.gz"):
        stem = f.name[: -len(".tar.gz")]
        if "-" in stem:
            nm, _, vr = stem.rpartition("-")
            _add(nm, vr, f, False)

# 타깃 런타임 환경(마커 평가 기준) — 빌드머신 환경이 아니다.
TARGET_ENV = {
    "implementation_name": "cpython",
    "implementation_version": "3.11.15",
    "os_name": "posix",
    "platform_machine": "x86_64",
    "platform_python_implementation": "CPython",
    "platform_release": "",
    "platform_system": "Linux",
    "platform_version": "",
    "python_full_version": "3.11.15",
    "python_version": "3.11",
    "sys_platform": "linux",
}


def requires_dist(whl):
    """wheel 의 dist-info/METADATA 에서 Requires-Dist 헤더만 뽑는다."""
    out = []
    with zipfile.ZipFile(whl) as z:
        metas = [n for n in z.namelist()
                 if n.endswith(".dist-info/METADATA") and n.count("/") == 1]
        if not metas:
            return out
        text = z.read(metas[0]).decode("utf-8", "replace")
    for line in text.splitlines():
        if line == "":
            break  # 헤더 끝(본문 시작)
        if line.lower().startswith("requires-dist:"):
            out.append(line.split(":", 1)[1].strip())
    return out


missing = []      # 이름 자체가 반입물에 없음
unsatisfied = []  # 이름은 있으나 <어떤 반입 버전도> 지정자를 만족하지 못함
unchecked = []    # 파싱·평가 실패 — "통과"가 아니라 "검사 못 함"
edges = []


def check(parent, raw):
    """요구사항 하나를 반입물과 대조. 만족하면 그 이름을, 아니면 None 을 돌려준다."""
    try:
        req = Requirement(raw)
    except Exception as exc:
        unchecked.append((parent, raw, "요구사항 파싱 실패: %s" % exc))
        return None
    if req.marker is not None:
        if "extra" in str(req.marker):
            return None  # 선택 기능 — 설치 대상 아님
        try:
            if not req.marker.evaluate(TARGET_ENV):
                return None
        except Exception as exc:
            unchecked.append((parent, raw, "마커 평가 실패(필수로 간주): %s" % exc))
    dep = norm(req.name)
    cands = present.get(dep, [])
    if not cands:
        edges.append((parent, dep, raw, "없음"))
        missing.append((parent, raw))
        return None
    spec = req.specifier
    if not str(spec):
        edges.append((parent, dep, raw, ""))
        return dep
    unparsable = [p for (v, p, _w) in cands if v is None]
    ok = []
    for (ver, path, _w) in cands:
        if ver is None:
            continue
        try:
            if spec.contains(ver, prereleases=True):
                ok.append(str(ver))
        except Exception as exc:
            unchecked.append((parent, raw, "지정자 평가 실패: %s" % exc))
            return dep
    if unparsable:
        unchecked.append(
            (parent, raw,
             "버전을 읽지 못한 반입 파일: %s" % ", ".join(p.name for p in unparsable)))
    if not ok:
        have = ", ".join(str(v) for (v, _p, _w) in cands if v is not None) or "(버전 불명)"
        edges.append((parent, dep, raw, "불만족"))
        unsatisfied.append((parent, raw, have))
        return None
    edges.append((parent, dep, raw, ""))
    return dep


ROOTS = ["torch", "torchvision", "setuptools", "wheel"]
# 루트 자체가 없으면 "간선 0건이라 통과"가 되어 검사가 무의미해진다 — 먼저 막는다.
_absent = [r for r in ROOTS if not any(w for (_v, _p, w) in present.get(r, []))]
if _absent:
    print("★ 루트 wheel 이 반입물에 없습니다: %s" % ", ".join(_absent))
    print("  (해당 다운로드 단계가 실제로 성공했는지 확인하세요)")
    sys.exit(1)

seen = set()
queue = list(ROOTS)
while queue:
    name = norm(queue.pop(0))
    if name in seen:
        continue
    seen.add(name)
    for (_ver, f, is_wheel) in present.get(name, []):
        if not is_wheel:
            # sdist 는 METADATA 위치가 제각각이라 잎으로 둔다 — 아래에서 명시 보고한다.
            continue
        for raw in requires_dist(f):
            dep = check(name, raw)
            if dep:
                queue.append(dep)

# ---- sam2 [build-system] requires — wheel METADATA 에 없는 축 ----
#   sam2 는 소스 디렉터리로 반입되므로 이 요구는 pyproject.toml 에만 있다.
#   PEP 517 빌드 격리 환경이 이 목록을 <동시에> 만족시켜야 한다.
sam2_reqs = []
if sam2_src is not None and (sam2_src / "pyproject.toml").is_file():
    try:
        import tomllib
        with open(sam2_src / "pyproject.toml", "rb") as fh:
            sam2_reqs = tomllib.load(fh).get("build-system", {}).get("requires", []) or []
    except Exception as exc:
        unchecked.append(("sam2/pyproject.toml", "[build-system] requires",
                          "읽기 실패: %s" % exc))
    for raw in sam2_reqs:
        check("sam2(build-system)", raw)
elif sam2_src is not None:
    unchecked.append(("sam2", "[build-system] requires",
                      "pyproject.toml 을 찾지 못함: %s" % sam2_src))

for parent, dep, raw, flag in edges:
    print("    %-22s -> %-24s %s%s"
          % (parent, dep, raw, ("   [%s]" % flag) if flag else ""))
print("  검사 간선 %d / 반입 배포물 %d / 추적한 패키지 %d / sam2 빌드요구 %d"
      % (len(edges), len(present), len(seen), len(sam2_reqs)))

# 같은 배포물이 여러 판 들어온 경우 — 재현성 결함이라 이름을 남긴다(설치를 깨뜨리진 않는다).
dups = {n: c for n, c in present.items() if len({str(v) for (v, _p, _w) in c}) > 1}
if dups:
    print("")
    print("⚠ 같은 배포물이 여러 판 반입됐습니다(재현성 결함 — 락과 다른 판이 섞였는지 확인):")
    for n, c in sorted(dups.items()):
        print("    %s: %s" % (n, ", ".join(sorted(p.name for (_v, p, _w) in c))))

_sdist_leaves = sorted(n for n, c in present.items() if all(not w for (_v, _p, w) in c))
if _sdist_leaves:
    print("")
    print("⚠ sdist 라 의존성을 읽지 못해 <잎으로 둔> 배포물(그 아래 단은 검사되지 않았습니다):")
    print("    %s" % ", ".join(_sdist_leaves))

if unchecked:
    print("")
    print("⚠ 평가하지 못한 요구사항 — <통과가 아니라 미검사>입니다. 사람이 확인하세요:")
    for parent, raw, why in unchecked:
        print("    %s 의 '%s' — %s" % (parent, raw, why))

if missing:
    print("")
    print("★ 반입물(vendor/wheels)에 없는 필수 의존:")
    for parent, raw in missing:
        print("    %s 가 요구: %s" % (parent, raw))

if unsatisfied:
    print("")
    print("★ 이름은 있으나 <버전이 요구를 만족하지 않는> 의존:")
    for parent, raw, have in unsatisfied:
        print("    %s 가 요구: %s   — 반입된 판: %s" % (parent, raw, have))

if missing or unsatisfied:
    sys.exit(1)
print("  OK — 반입물 안에서 의존성이 버전까지 닫힙니다(sam2 빌드 요구 포함).")
PY
ok "[ai-server] 의존성 폐포 검증 통과(버전 지정자 대조 + sam2 빌드 요구 포함)"

# ---- 3-d) sdist 혼입 점검 (폐쇄망 경고) ----
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
#   ★ 본문은 35-collect-python-licenses.sh 로 <떼어냈다>(2026-08-30). 그 로직은 이미 디스크에
#     있는 반입물만 읽으므로 수집 결과에 의존하지 않는데, 여기 붙어 있으면 다시 돌리려고
#     수 GB 를 재다운로드해야 했다. 실제로 그래서 휠을 교체한 뒤 고지 스테이징만 낡은 채로
#     남았다(setuptools 84.0.0 · packaging 26.3 이 고지에만 있고 반입물엔 없었다).
#   ⚠ 여기서 호출하는 것은 그대로 둔다 — 수집 직후 자동으로 최신화되어야 한다.
#     스테이징만 다시 만들 때는 아래 두 줄이면 된다(재다운로드 없음):
#       bash scripts/package/35-collect-python-licenses.sh
#       bash scripts/package/70-generate-notices.sh
bash "${SELF_DIR}/35-collect-python-licenses.sh"

# ---- 무결성 체크섬 ----
sha256_write "${WHEELS}"
[[ -f "${WEIGHTS_OUT}/yolox_s.onnx" ]] && sha256_write "${WEIGHTS_OUT}" || true
