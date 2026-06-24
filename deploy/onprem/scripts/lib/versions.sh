# shellcheck shell=bash
# ============================================================================
# versions.sh — 수집 대상 런타임/모델의 버전·다운로드 URL 단일 출처
#
#   package/* 스크립트가 source 하여 사용한다. 폐쇄망 정합을 위해
#   대상 서버와 동일한 OS/아키텍처(linux x86_64, glibc) 빌드를 받아야 한다.
#
#   ※ 아래 URL/버전은 "권장 기본값"이다. 사내 미러나 다른 패치 버전을 쓰려면
#     이 파일만 수정하면 된다. 모든 URL 은 빌드머신(인터넷 가능)에서만 호출된다.
# ============================================================================

# ---- 빌드 도구 버전(빌드머신에 설치되어 있어야 함, 번들 대상 아님) ----
BUILD_JDK_MAJOR="17"        # backend bootJar 빌드용 (Temurin 17)
BUILD_NODE_MAJOR="20"       # frontend vite build 용
BUILD_PYTHON_MINOR="3.11"   # ai-server pip download 용 (대상과 동일 마이너)

# ---- 대상 서버 런타임(번들 대상) ----
# Eclipse Temurin JRE 17 (linux x64, glibc). 헤드리스 JRE 로 충분.
# TODO(확인필요): 정확한 패치 버전·체크섬은 https://adoptium.net/temurin/releases/?os=linux&arch=x64&package=jre&version=17
#                에서 확인 후 갱신. 아래는 안정 LTS 패치 예시값.
TEMURIN_JRE_VERSION="17.0.13+11"
TEMURIN_JRE_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jre_x64_linux_hotspot_17.0.13_11.tar.gz"

# python-build-standalone — 격리된 CPython 3.11 (linux x86_64, glibc, gnu)
# install-only 빌드(불필요 빌드 산출물 제외). 정확한 릴리스 태그는 아래 저장소에서 확인.
# TODO(확인필요): 최신 릴리스 태그/파일명·체크섬은
#   https://github.com/astral-sh/python-build-standalone/releases 에서 확인 후 갱신.
PYTHON_STANDALONE_TAG="20241016"
PYTHON_STANDALONE_VERSION="3.11.10"
PYTHON_STANDALONE_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20241016/cpython-3.11.10+20241016-x86_64-unknown-linux-gnu-install_only.tar.gz"

# Caddy 정적 바이너리 (linux amd64) — 프론트 정적 서빙 + /api 리버스프록시
# TODO(확인필요): 최신 안정 버전/체크섬은 https://github.com/caddyserver/caddy/releases 에서 확인.
CADDY_VERSION="2.8.4"
CADDY_URL="https://github.com/caddyserver/caddy/releases/download/v2.8.4/caddy_2.8.4_linux_amd64.tar.gz"

# ---- ai-server torch CPU 인덱스 ----
# pip download 시 CUDA wheel(거대) 대신 CPU wheel 을 받기 위한 인덱스.
PYTORCH_CPU_INDEX_URL="https://download.pytorch.org/whl/cpu"

# requirements.txt lock 기준 torch 버전(참고). pip download 가 lock 을 그대로 따른다.
#   torch==2.12.0, torchvision==0.27.0 (ai-server/requirements.txt 확인값)
# CPU 인덱스에 동일 버전 wheel 이 없으면 pip 가 에러를 낸다 →
#   06-troubleshooting.md "torch CPU" 절 참고(인덱스에 존재하는 인접 버전으로 lock 조정).

# ---- sam2 VCS 의존성 ----
# requirements.txt: sam-2 @ git+https://github.com/facebookresearch/sam2.git
SAM2_GIT_URL="https://github.com/facebookresearch/sam2.git"
# TODO(확인필요): 재현성을 위해 특정 커밋/태그로 고정 권장. 빈 값이면 기본 브랜치 HEAD.
SAM2_GIT_REF=""

# ---- HuggingFace 모델(선택 — DETECTOR_BACKEND=rtdetr 또는 SAM2 사용 시) ----
# yolox(yolox_s.onnx)만 쓰면 HF 모델 prefetch 불필요.
HF_SAM2_MODEL_ID="facebook/sam2-hiera-tiny"
HF_RTDETR_MODEL_ID="PekingU/rtdetr_v2_r50vd"
