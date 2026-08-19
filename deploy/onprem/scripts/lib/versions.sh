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
# Eclipse Temurin JRE 17 (linux x64, glibc, hotspot). 헤드리스 JRE 로 충분.
# ★ JRE 는 "설치 런타임"(대상 서버 backend 실행)용이다. 소스 빌드용 JDK(javac 포함)는
#   아래 JDK17_FULL_* 핀(별개)을 쓴다 — 둘 다 유지한다(혼동 금지).
# 검증: 2026-06-24, 출처 https://api.adoptium.net/v3/assets/latest/17/hotspot?os=linux&architecture=x64&image_type=jre
#   (release_name jdk-17.0.19+10, package.checksum = sha256)
TEMURIN_JRE_VERSION="17.0.19+10"
TEMURIN_JRE_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/OpenJDK17U-jre_x64_linux_hotspot_17.0.19_10.tar.gz"
TEMURIN_JRE_SHA256="adb5a2364baa51de1ef91bb9911f5a61d24b045fe1d6647cb8050272a3a8ee75"

# ============================================================================
# ---- 오프라인 빌드 키트(buildtools/) — 타깃에서 "소스 재빌드" 용 ----
#   사전 빌드 아티팩트(jar/dist)와 별개로, 폐쇄망 타깃(Rocky 9)에서 소스를
#   인터넷 없이 재빌드하기 위한 빌드 도구다. 의존성 캐시(gradle-home/node_modules)
#   까지 함께 번들해야 오프라인 빌드가 닫힌다(60-collect-buildtools.sh 참조).
# ============================================================================

# Eclipse Temurin JDK 17 full (linux x64, glibc, hotspot) — javac 포함, gradle bootJar 용.
#   위 TEMURIN_JRE 와 동일 릴리스(jdk-17.0.19+10)의 image_type=jdk 자산이다(JRE 와 별개 파일).
# 검증: 2026-06-25, 출처 https://api.adoptium.net/v3/assets/latest/17/hotspot?os=linux&architecture=x64&image_type=jdk
#   (release_name jdk-17.0.19+10, package.checksum = sha256, package.name 아래 URL 의 basename)
JDK17_FULL_VERSION="17.0.19+10"
JDK17_FULL_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.19_10.tar.gz"
JDK17_FULL_SHA256="d8afc263758141a66e0e3aafc321e783f7016696f4eaea067d340a269037d331"

# Node.js 20 (linux x64, tar.xz) — frontend vite build 용. BUILD_NODE_MAJOR=20 과 정합.
#   최신 20.x LTS(v20.20.2). nodejs.org/dist 의 dated 자산은 불변이라 재현 가능.
# 검증: 2026-06-25, 출처 https://nodejs.org/dist/v20.20.2/SHASUMS256.txt
#   (node-v20.20.2-linux-x64.tar.xz 행)
NODE20_VERSION="20.20.2"
NODE20_URL="https://nodejs.org/dist/v20.20.2/node-v20.20.2-linux-x64.tar.xz"
NODE20_SHA256="df770b2a6f130ed8627c9782c988fda9669fa23898329a61a871e32f965e007d"

# Gradle 8.8 (bin zip) — backend 빌드 도구. backend 가 gradle 8.8 사용 확인:
#   backend/gradle/wrapper/gradle-wrapper.properties → gradle-8.8-bin.zip
#   backend/Dockerfile → FROM gradle:8.8-jdk17, backend/gradle.lockfile 존재.
# 검증: 2026-06-25, 출처(공식) https://services.gradle.org/distributions/gradle-8.8-bin.zip.sha256
GRADLE_DIST_VERSION="8.8"
GRADLE_DIST_URL="https://services.gradle.org/distributions/gradle-8.8-bin.zip"
GRADLE_DIST_SHA256="a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612"

# python-build-standalone — 격리된 CPython 3.11 (linux x86_64, glibc, gnu)
# install_only 빌드(불필요 빌드 산출물 제외).
# 검증: 2026-06-24, 출처 https://github.com/astral-sh/python-build-standalone/releases/tag/20260623
#   SHA256 출처: 동 릴리스 SHA256SUMS (cpython-3.11.15+20260623-...-install_only.tar.gz 행)
PYTHON_STANDALONE_TAG="20260623"
PYTHON_STANDALONE_VERSION="3.11.15"
PYTHON_STANDALONE_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20260623/cpython-3.11.15+20260623-x86_64-unknown-linux-gnu-install_only.tar.gz"
PYTHON_STANDALONE_SHA256="60295e3e703b48c270e8d8c685195b8d5c2f0b8a596c1a910d7e24a2cc55afdd"

# ffmpeg/ffprobe 정적 바이너리 (linux64, LGPL) — backend FFmpegStep(net.bramp 래퍼)용
#   ★ Rocky Linux 9 정책: ffmpeg 는 base/AppStream 에 없고 RPM Fusion/EPEL 미러가 필요해
#     폐쇄망에서 의존성 지옥에 빠진다. 따라서 정적 코덱 바이너리를 번들한다.
#   ★ 라이선스: LGPL 빌드를 쓴다(지방정부 납품 — GPL 의무 회피). LGPL 빌드는 native H.264/HEVC
#     디코더를 포함하므로 프레임 추출(디코드)·duration 추출에 충분하다. 우리는 인코딩을 하지 않으며
#     GPL 코덱(libx264/x265 = 인코더)은 불필요하다.
#   ★ glibc: BtbN 빌드는 glibc 에 동적 링크(약 glibc 2.31 기반 빌드), 코덱만 정적 링크된다.
#     Rocky 9 의 glibc 2.34 는 상위호환이므로 정상 동작한다(빌드 glibc ≤ 대상 glibc).
#   BtbN FFmpeg-Builds 의 "dated autobuild" 태그는 자산이 불변(immutable)이라 재현 가능하다
#   ("latest" 태그는 매일 덮어써져 체크섬이 바뀌므로 핀에 부적합 — dated 태그를 쓴다).
#   linux64-lgpl(비-shared) = 코덱 정적 링크. 압축은 .tar.xz (수집/설치 스크립트가 xz 로 해제).
#   검증: 2026-06-24, 출처 https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-06-23-13-52
#     SHA256 출처: 동 릴리스 checksums.sha256 의 (ffmpeg-n7.1.5-linux64-lgpl-7.1.tar.xz 행)
#     공식 checksums.sha256 와 대조 일치 확인: 2026-06-24
FFMPEG_STATIC_VERSION="n7.1.5"
FFMPEG_STATIC_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-06-23-13-52/ffmpeg-n7.1.5-linux64-lgpl-7.1.tar.xz"
FFMPEG_STATIC_SHA256="86821c89fcde7adf381005329dc696a02fa99ba2bc5b6f0a4fac3dafc247e1b2"

# 웹 서버는 여기서 버전을 고정하지 않는다 — httpd 를 배포판 RPM(syspkgs)으로 설치한다.
#   ★ 구 형상(Caddy 정적 바이너리 다운로드)은 폐기했다(2026-08-19). 배포 대상의 웹 서버를
#     관제지원시스템과 하나로 통일하기 위함이며, RPM 은 모듈 적재와 SELinux 문맥이
#     이미 갖춰져 있어 우리가 관리할 표면이 줄어든다.

# ---- PostgreSQL 16 (PGDG, Rocky 9 오프라인 번들) ----
#   ★ 번들 PG 는 "옵션"이다(USE_BUNDLED_POSTGRES=1 기본). 타깃에 이미 PG 가 있으면 끈다(=0).
#   ★ DB 스키마는 backend Flyway 가 자동 부트스트랩한다(V2__phase3_video_queue_quartz.sql 가
#     LS_*·MNG_*·QRTZ_* 를 CREATE TABLE IF NOT EXISTS 로 생성, flyway.enabled=true). 따라서
#     PG 사전요건은 "빈 DB 2개(control/portal) + 접속 사용자"뿐이다(관제 스키마 사전 적재 불필요).
#   수집(55-collect-postgresql.sh)은 dnf/yum 환경에서만 수행되며, 비-RHEL(mac)은 graceful SKIP 한다.
#   설치(10-install-postgresql.sh)는 syspkgs/postgresql/*.rpm 을 오프라인(--disablerepo='*')으로 설치한다.
#
#   PGDG repo 추가:    dnf install -y ${PGDG_REPO_RPM_URL}
#   내장 모듈 비활성:  dnf -qy module disable postgresql
#   수집 패키지:       ${POSTGRES_RPM_PKGS[*]}
#   initdb:            /usr/pgsql-16/bin/postgresql-16-setup initdb
#   서비스:            postgresql-16
#
#   ⚠ PGDG repo RPM 은 "...repo-latest.noarch.rpm" 이라 URL 자체가 가변(latest) → 버전 고정 체크섬
#     핀이 불가하다. 수집 스크립트는 repo RPM 을 한 번 설치해 PGDG repo 메타만 추가하고 그것으로
#     PG16 RPM 을 받는다. 받은 PG16 *.rpm 의 전송 무결성은 syspkgs/postgresql/SHA256SUMS 로 검증한다.
POSTGRES_MAJOR="16"
PGDG_REPO_RPM_URL="https://download.postgresql.org/pub/repos/yum/reporpms/EL-9-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
# 받을 PG16 RPM(전이 의존성은 dnf download --resolve --alldeps 가 함께 받는다).
POSTGRES_RPM_PKGS=(postgresql16-server postgresql16 postgresql16-libs postgresql16-contrib)

# ---- ai-server torch CPU 인덱스 ----
# pip download 시 CUDA wheel(거대) 대신 CPU wheel 을 받기 위한 인덱스.
PYTORCH_CPU_INDEX_URL="https://download.pytorch.org/whl/cpu"

# requirements.txt lock 기준 torch 버전(참고). pip download 가 lock 을 그대로 따른다.
#   torch==2.12.0, torchvision==0.27.0 (ai-server/requirements.txt 확인값)
# 검증: 2026-06-24, CPU 인덱스에 cp311 x86_64 wheel 존재 확인
#   - torch-2.12.0+cpu-cp311-cp311-manylinux_2_28_x86_64.whl
#   - torchvision-0.27.0+cpu-cp311-cp311-manylinux_2_28_x86_64.whl
#   (출처: https://download.pytorch.org/whl/cpu/torch/ , .../torchvision/)
#   → lock 핀 그대로 CPU 인덱스에서 받을 수 있다. 인접 버전 조정 불필요.
# 만약 향후 lock 의 torch 버전이 CPU 인덱스에 없으면 06-troubleshooting.md "torch CPU" 절 참고.
#
# CPU 핀(참고/문서용): lock 버전이 CPU 인덱스에 존재함을 확인했으므로 lock 값과 동일하다.
# 수집 스크립트는 기본적으로 lock 에서 핀을 추출하지만, lock 의 torch 가 CPU 인덱스에 없는
# 상황에서만 troubleshooting 절차에 따라 아래 값으로 대체한다(sam2 의 torch>=2.5.1 하한 충족).
TORCH_CPU_PIN="torch==2.12.0"
TORCHVISION_CPU_PIN="torchvision==0.27.0"

# ---- sam2 VCS 의존성 ----
# requirements.txt: sam-2 @ git+https://github.com/facebookresearch/sam2.git
SAM2_GIT_URL="https://github.com/facebookresearch/sam2.git"
# 재현성을 위해 기본 브랜치(main) HEAD 커밋을 40자 전체 SHA 로 고정.
# 핀: 2026-06-24 — facebookresearch/sam2 main HEAD (committed 2024-12-16, 저장소에 태그 없음)
#   출처: https://api.github.com/repos/facebookresearch/sam2/commits/main
# ※ SHA(브랜치/태그가 아님)이므로 collect 스크립트는 clone 후 git checkout 으로 처리한다.
SAM2_GIT_REF="2b90b9f5ceec907a1c18123530e92e794ad901a4"

# ---- HuggingFace 모델(선택 — SAM2 분할/Track 사용 시) ----
# 탐지는 YOLOX 단일(yolox_s.onnx)이라 HF 모델 prefetch 는 SAM2 만 대상.
HF_SAM2_MODEL_ID="facebook/sam2-hiera-tiny"
