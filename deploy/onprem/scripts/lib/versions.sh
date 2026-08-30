# shellcheck shell=bash
# ============================================================================
# versions.sh — 수집 대상 런타임/모델의 버전·다운로드 URL 단일 출처
#
#   package/* 스크립트가 source 하여 사용한다. 폐쇄망 정합을 위해
#   대상 서버와 동일한 OS/아키텍처(linux x86_64, glibc) 빌드를 받아야 한다.
#
#   ★ 타깃 OS = 레드햇 엔터프라이즈 리눅스 8.9 (RHEL 8 계열, x86_64, glibc 2.28, dnf/rpm).
#     RHEL 8 은 8.0~8.10 전 버전이 glibc 2.28 로 고정이므로 8.9 도 2.28 이다.
#     ⚠ 구 서술 폐기(2026-08-28) — "타깃 OS = Rocky Linux 9 (RHEL 9 계열, glibc 2.34)".
#       그 전제로 받은 el9 RPM 은 RHEL 8 에 설치되지 않고, glibc 2.31 기반 정적 바이너리는
#       2.28 에서 GLIBC_2.31 not found 로 죽는다.
#
#   ※ 아래 URL/버전은 "권장 기본값"이다. 사내 미러나 다른 패치 버전을 쓰려면
#     이 파일만 수정하면 된다. 모든 URL 은 빌드머신(인터넷 가능)에서만 호출된다.
# ============================================================================

# ---- 타깃 OS / 수집용 컨테이너 이미지 ----
#   RPM·pip wheel 은 반드시 타깃과 같은 세대(el8, glibc 2.28)에서 수집해야 한다.
#   맥/윈도 빌드머신에서는 아래 이미지를 docker 로 띄워 수집한다.
#   ★ --platform linux/amd64 를 반드시 지정한다 — Apple Silicon 에서 생략하면 aarch64 RPM 을
#     받아 놓고 "성공"으로 끝나는 조용한 실패가 된다(타깃 x86_64 에 설치 불가).
#   검증: 2026-08-30, rockylinux/rockylinux:8 → Rocky Linux 8.10 / glibc 2.28 / x86_64,
#     baseos·appstream·extras·powertools 리포 정상(vault 아님).
#     (동등 대안 almalinux:8 도 8.10 / glibc 2.28 로 동일 확인. 어느 쪽이든 el8 RPM 은 호환)
EL8_BUILDER_IMAGE="rockylinux/rockylinux:8"
EL8_BUILDER_PLATFORM="linux/amd64"
TARGET_OS_LABEL="레드햇 엔터프라이즈 리눅스 8.9 (el8, x86_64, glibc 2.28)"

# ---- 빌드 도구 버전(빌드머신에 설치되어 있어야 함, 번들 대상 아님) ----
BUILD_JDK_MAJOR="17"        # backend bootJar 빌드용 (Temurin 17)
BUILD_NODE_MAJOR="20"       # frontend vite build 용
BUILD_PYTHON_MINOR="3.11"   # ai-server pip download 용 (대상과 동일 마이너)

# ---- 대상 서버 런타임(번들 대상) ----
# ★★ 아래 TEMURIN_JRE_* 는 <더 이상 수집되지 않는다> (2026-08-30 사용자 확정, 구속).
#   배포 형상이 <외부 WAS 에 WAR 반입>으로 확정됐고(@design DEPLOY-001), 대상 장비의 WAS
#   (Tomcat 10.1.x)가 이미 Java 17 로 돌고 있어 자바 런타임을 한 벌 더 반입하지 않는다.
#   40-collect-runtimes.sh 도 11-install-runtimes.sh 도 이 값을 읽지 않는다.
#   ⚠ 핀을 지우지 않고 남기는 이유: 베어메탈 형상(java -jar)으로 되돌려야 할 때 재조달의
#     기준값이 여기 말고는 없다. 되살리려면 위 두 스크립트에 수집·설치를 다시 배선해야 한다.
#   ⚠ 이 핀과 아래 JDK17_FULL_* 는 <별개>다. 둘 다 지금은 반입되지 않지만 이유가 다르다 —
#     이쪽은 "형상이 자바를 안 쓴다", 저쪽은 "현장 재빌드 요구가 없다"(빌드 키트 기본 제외).
#
# Eclipse Temurin JRE 17 (linux x64, glibc, hotspot). 헤드리스 JRE 로 충분.
# 검증: 2026-06-24, 출처 https://api.adoptium.net/v3/assets/latest/17/hotspot?os=linux&architecture=x64&image_type=jre
#   (release_name jdk-17.0.19+10, package.checksum = sha256)
TEMURIN_JRE_VERSION="17.0.19+10"
TEMURIN_JRE_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/OpenJDK17U-jre_x64_linux_hotspot_17.0.19_10.tar.gz"
TEMURIN_JRE_SHA256="adb5a2364baa51de1ef91bb9911f5a61d24b045fe1d6647cb8050272a3a8ee75"

# ============================================================================
# ---- 오프라인 빌드 키트(buildtools/) — 타깃에서 "소스 재빌드" 용 ----
#   사전 빌드 아티팩트(jar/dist)와 별개로, 폐쇄망 타깃(RHEL 8.9)에서 소스를
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

# ★ 같은 릴리스의 <full 아카이브> — 라이선스 고지 19종을 꺼내려고만 받는다 (2026-08-30 신설).
#   ⚠ 왜 별도 URL 인가: 우리가 실제로 반입하는 install_only tarball 에는 <licenses/ 디렉터리가
#     없다>(실측 — 그 안에는 python/install/... 만 들어 있다). 그래서 OpenSSL·Berkeley DB·
#     ncurses·libedit·tcl 등 <번들 파이썬이 정적으로 안고 있는 제3자 고지 19종>을 한 건도
#     담지 못한 채 배포하고 있었다. full 아카이브에만 python/licenses/*.txt 가 들어 있다.
#   ★ full 아카이브 자체는 <반입하지 않는다>. 71MB 를 받아 168KB 의 고지만 꺼내고 버린다.
#     실행 런타임은 여전히 install_only 다(형상 변경 아님).
#   검증: 2026-08-30, 동 릴리스 SHA256SUMS 행과 실제 다운로드 파일 해시 일치 확인.
#     추출된 목록(19): bdb · bzip2 · cpython · expat · libedit · libffi · liblzma · libuuid ·
#       libX11 · libXau · libxcb · mpdecimal · ncurses · openssl-1.1 · openssl-3 · sqlite ·
#       tcl · tix · zlib
PYTHON_STANDALONE_FULL_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20260623/cpython-3.11.15%2B20260623-x86_64-unknown-linux-gnu-pgo%2Blto-full.tar.zst"
PYTHON_STANDALONE_FULL_SHA256="ca7dadd778176c1aac3d37f3444adcb7bb2aa5c2e8e282f856727811b0207cdf"

# ---- ffmpeg/ffprobe — 시스템 RPM(RPM Fusion el8) 설치본 ----
#   backend FFmpegStep(net.bramp 래퍼)이 호출하는 실행 파일. 프레임 추출·duration 산출용.
#
#   ★ 조달 경로: 정적 바이너리 번들 → 배포판 RPM 으로 전환한다(2026-08-28 사용자 확정, 구속).
#     관제지원시스템이 이미 쓰고 있는 형상과 맞춘다. dev 서버(cudo_246) 실측:
#       ffmpeg 3.4.13 · --enable-gpl --enable-libx264 --enable-libx265
#       RPM License: GPLv2+ · Vendor: RPM Fusion · Source: ffmpeg-3.4.13-1.el7.src.rpm
#
#   ⚠ 구 정책 폐기(2026-08-28) — 아래 두 줄은 더 이상 우리 정책이 아니다. 되살리지 말 것.
#       "라이선스: LGPL 빌드를 쓴다(지방정부 납품 — GPL 의무 회피). … 우리는 인코딩을 하지
#        않으며 GPL 코덱(libx264/x265 = 인코더)은 불필요하다."
#       "glibc: BtbN 빌드는 glibc 에 동적 링크(약 glibc 2.31 기반 빌드) … Rocky 9 의 glibc 2.34 는
#        상위호환이므로 정상 동작한다(빌드 glibc ≤ 대상 glibc)."
#     폐기 사유: 타깃이 RHEL 8.9(glibc 2.28)로 확정되어 2.28 < 2.31 로 그 부등식이 뒤집힌다.
#       BtbN 정적 바이너리는 타깃에서 GLIBC_2.31 not found 로 죽는다(배포 차단급).
#     라이선스 성격 변화: el8 RPM Fusion 의 ffmpeg 는 GPLv3+ 다(실측 아래). 인코더(x264/x265)가
#       포함되지만 저작도구는 ffmpeg 를 별도 프로세스로 호출할 뿐 링크하지 않으므로
#       코드 전염 우려가 낮다(관제 OSS-077 이 적은 판단과 동일 축).
#     ⚠ 대가: 정적 번들을 택했던 원래 이유가 되살아난다 — ffmpeg 는 base/AppStream 에 없고
#       EPEL + RPM Fusion + PowerTools(CRB) 세 리포가 필요하다. 그래서 전이 의존성까지
#       통째로 받아 오프라인 번들에 넣는다(50-collect-syspkgs.sh).
#
#   ★ 필요한 리포 3종(수집 컨테이너에서 활성화 — 타깃에는 필요 없다, 오프라인 RPM 설치이므로):
#     - EPEL 8            : ${EPEL_RELEASE_RPM_URL}
#     - RPM Fusion free 8 : ${RPMFUSION_FREE_RELEASE_RPM_URL}  (ffmpeg 본체 제공)
#     - PowerTools(CRB)   : ${EL8_CRB_REPO_ID}  — libSDL2 등 ffmpeg 의존을 제공.
#       ⚠ 이걸 켜지 않으면 "nothing provides libSDL2-2.0.so.0()(64bit) needed by ffmpeg" 로
#         의존 해소가 실패한다(2026-08-30 실측). RHEL 8 에서의 동등 리포는
#         codeready-builder-for-rhel-8-x86_64-rpms 다.
#
#   ★ 버전 핀을 두지 않는다 — 정적 tarball 과 달리 RPM 은 리포의 현재 최신을 받는다.
#     받은 *.rpm 의 전송 무결성은 syspkgs/ffmpeg/SHA256SUMS 로 검증한다(기존 방식과 동일).
#   검증: 2026-08-30, ${EL8_BUILDER_IMAGE} 에서 수집·오프라인 설치·실행까지 확인
#     → ffmpeg 4.4.8-1.el8 / ffprobe 4.4.8 / License GPLv3+ / Vendor RPM Fusion
#     → 설치 경로 /usr/bin/ffmpeg, /usr/bin/ffprobe
EPEL_RELEASE_RPM_URL="https://dl.fedoraproject.org/pub/epel/epel-release-latest-8.noarch.rpm"
RPMFUSION_FREE_RELEASE_RPM_URL="https://download1.rpmfusion.org/free/el/rpmfusion-free-release-8.noarch.rpm"
# PowerTools 리포 id — Rocky/Alma 8 은 "powertools", RHEL 8 은 CRB(구독 리포)다.
EL8_CRB_REPO_ID="powertools"
FFMPEG_RPM_PKGS=(ffmpeg)
# 타깃에 설치된 뒤의 실행 파일 절대경로(설치 스크립트·설정 템플릿이 공유하는 단일 출처).
FFMPEG_BIN_PATH="/usr/bin/ffmpeg"
FFPROBE_BIN_PATH="/usr/bin/ffprobe"

# 웹 서버는 여기서 버전을 고정하지 않는다 — httpd 를 배포판 RPM(syspkgs)으로 설치한다.
#   ★ 구 형상(Caddy 정적 바이너리 다운로드)은 폐기했다(2026-08-19). 배포 대상의 웹 서버를
#     관제지원시스템과 하나로 통일하기 위함이며, RPM 은 모듈 적재와 SELinux 문맥이
#     이미 갖춰져 있어 우리가 관리할 표면이 줄어든다.

# ---- PostgreSQL 16 (PGDG, RHEL 8.9 / el8 오프라인 번들) ----
#   ★ 번들 PG 는 "옵션"이다(USE_BUNDLED_POSTGRES=1 기본). 타깃에 이미 PG 가 있으면 끈다(=0).
#   ★ DB 스키마는 db/schema.sql(전체 통합 DDL)을 1회 로드해 만든다 — 온프렘은 Flyway 를 쓰지
#     않는다(flyway.enabled=false). 따라서 PG 사전요건은
#     "빈 DB 2개(control/portal) + 접속 사용자"뿐이고, 그 로드는 설치 단계(16-load-schema.sh)나
#     DBA 가 <사람 손으로> 수행한다(자동으로 만들어지지 않는다).
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
# ⚠ 구 값 폐기(2026-08-28) — EL-9-x86_64. el9 PG RPM 은 RHEL 8 에 설치되지 않는다.
#   검증: 2026-08-30, 아래 EL-8 URL HTTP 200 확인.
PGDG_REPO_RPM_URL="https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
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
#   ★ manylinux_2_28 태그가 곧 glibc 2.28 기준선이라 RHEL 8.9 와 정확히 일치한다 —
#     타깃 OS 정정(Rocky 9 → RHEL 8.9)에도 이 wheel 은 재수집이 불필요하다.
#     ⚠ 구 근거 폐기: "glibc 2.34 호환이라 그대로 쓴다"(근거만 낡았고 결론은 그대로 참).
#   (출처: https://download.pytorch.org/whl/cpu/torch/ , .../torchvision/)
#   → lock 핀 그대로 CPU 인덱스에서 받을 수 있다. 인접 버전 조정 불필요.
# 만약 향후 lock 의 torch 버전이 CPU 인덱스에 없으면 06-troubleshooting.md "torch CPU" 절 참고.
#
# CPU 핀(참고/문서용): lock 버전이 CPU 인덱스에 존재함을 확인했으므로 lock 값과 동일하다.
# 수집 스크립트는 기본적으로 lock 에서 핀을 추출하지만, lock 의 torch 가 CPU 인덱스에 없는
# 상황에서만 troubleshooting 절차에 따라 아래 값으로 대체한다(sam2 의 torch>=2.5.1 하한 충족).
TORCH_CPU_PIN="torch==2.12.0"
TORCHVISION_CPU_PIN="torchvision==0.27.0"

# ---- PEP 517 빌드 백엔드(setuptools / wheel) ----
#   requirements.txt 는 pip-compile 산출물이라 setuptools 가 <락에서 제외>된다("considered to
#   be unsafe" 주석만 남는다). 그런데 이 번들에는 wheel 이 없는 sdist 가 있고(2026-08-30 실측:
#   antlr4-python3-runtime, iopath) sam2 도 로컬 소스로 설치하므로, pip 가 만드는 <빌드 격리
#   환경>이 setuptools·wheel 을 다시 찾는다. 폐쇄망(--no-index)에서는 그 조회가 나갈 수 없어
#   설치가 그 자리에서 멈춘다 → 30-collect-ai-server.sh 가 여기 핀으로 명시 반입한다.
#
#   ★ 핀을 두는 이유: 이 두 개만 "수집 시점의 최신"으로 흘러가면 빌드머신을 언제 돌리느냐에
#     따라 반입물이 달라져 재현성이 깨진다(락의 다른 의존성은 전부 pip-compile 로 고정된다).
#
#   ★★ setuptools 는 <상한이 있다> — torch 가 걸어 둔 제약이다. 위아래 양쪽을 만족해야 한다:
#        하한  setuptools>=61.0  ← sam2 의 [build-system] requires (${SAM2_GIT_REF} 커밋 pyproject.toml)
#        상한  setuptools<82     ← ${TORCH_CPU_PIN} 의 wheel METADATA "Requires-Dist: setuptools<82"
#      sam2 는 [build-system] requires 에 setuptools 와 torch 를 <함께> 적으므로, PEP 517 빌드
#      격리 환경은 그 둘을 <동시에> 만족시켜야 한다. 상한을 넘기면 그 자리에서 죽는다:
#        ERROR: Cannot install setuptools>=61.0 and torch==2.12.0+cpu because these package
#               versions have conflicting dependencies. … ERROR: ResolutionImpossible
#      ⚠ 이 실패는 <조용하다>. venv 에 이미 있는 setuptools 는 빌드 격리가 쓰지 않으므로 로컬
#        설치 상태로는 드러나지 않고, 13-install-ai-server.sh 가 sam2 실패를 흘려보내면(현재는
#        모델 캐시가 동봉된 납품에서만 die) 설치가 완주한다. 그러면 SAM2 분할·Track 이
#        <mock 으로 폴백>한다 — ENV=stg|prd 면 503 으로 거부되지만 그 사실은 현장에서
#        누군가 그 기능을 처음 눌러 볼 때에야 드러난다.
#
#   ★ 그래서 "PyPI 최신"을 그대로 쓰지 않는다. 2026-08-30 에 실제로 그렇게 84.0.0 으로 올렸다가
#     이 결함을 만들었다. <상한을 함께 확인하지 않은 인상>이 원인이다.
#   ⚠ 올릴 때 반드시 함께 볼 것:
#       ① TORCH_CPU_PIN 이 바뀌면 그 wheel 의 Requires-Dist 를 다시 읽어 상한을 재확인한다
#          (torch 가 상한을 풀거나 더 좁힐 수 있다 — 우리가 기억한 값이 아니라 METADATA 가 정본).
#       ② SAM2_GIT_REF 가 바뀌면 그 커밋의 pyproject.toml 에서 하한을 재확인한다.
#       ③ 무네트워크 리허설로 sam2 가 <실제로 빌드·설치·import 되는지>까지 확인한다.
#     30-collect-ai-server.sh 의 3-c 검증이 이 상·하한을 wheel METADATA 로 자동 대조하지만,
#     그것은 <받은 뒤>에 도는 사후 그물이다. 핀을 고를 때 먼저 확인하는 편이 싸다.
#
#   검증: 2026-08-30
#     - PyPI 조회: setuptools 최신 안정은 84.0.0 이나 <82 조건에 걸린다. 82.0.0(2026-02-08)·
#       82.0.1·83.0.0·84.0.0 은 상한 초과 → 조건을 만족하는 <최신>은 81.0.0 (2026-02-06,
#       yanked 아님, requires_python >=3.9 ⊇ 타깃 3.11). 그 아래는 80.10.2 이므로 81.0.0 을 택한다.
#     - wheel 0.48.0 (requires_python >=3.9) — 상한 제약 없음, 최신 유지.
#     - 무네트워크 리허설(--network none 컨테이너): 이 핀으로 sam2 빌드·설치·import 성공.
PEP517_SETUPTOOLS_PIN="setuptools==81.0.0"
PEP517_WHEEL_PIN="wheel==0.48.0"

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
