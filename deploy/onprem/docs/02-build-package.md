# 02. [빌드머신] 패키지 수집

타깃은 **Rocky Linux 9 (x86_64, glibc 2.34)** 다. 수집은 두 부류로 나뉜다:

- **OS 무관 산출물**(jar·FE dist·런타임 tarball·ffmpeg 정적·sam2·yolox): mac 등 어떤 빌드머신에서도 OK.
- **OS 종속 산출물**(pip wheel·RPM): 반드시 **Rocky 9(rockylinux:9 컨테이너/머신)+인터넷**에서 수집.

가장 간단한 길은 **전부 rockylinux:9 컨테이너에서 한 번에 수집**하는 것이다(아래 "Rocky 9 컨테이너 수집").

## 실행 (Rocky 9 환경에서 전체 수집)

```bash
cd deploy/onprem
./scripts/package.sh
```

옵션:

```bash
# 시스템 의존성(RPM/ffmpeg) 수집 생략
SKIP_SYSPKGS=1 ./scripts/package.sh

# HF 모델(rtdetr/sam2)도 사전 다운로드(DETECTOR_BACKEND=rtdetr 또는 SAM2 사용 시)
PREFETCH_HF=1 ./scripts/package.sh

# frontend 빌드 시점 변수 override (기본 /api/v1, all)
VITE_API_BASE_URL=/api/v1 VITE_TOKEN_INGRESS=all ./scripts/package.sh

# ai-server wheel 을 받을 python 명시(대상과 동일 3.11)
PYTHON_BIN=python3.11 ./scripts/package.sh
```

## 단계별 수집물

| 단계 | 스크립트 | 수집 |
|------|----------|------|
| 1 | `package/10-build-backend.sh` | `./gradlew bootJar` → `artifacts/backend/klid-backend.jar` |
| 2 | `package/20-build-frontend.sh` | `npm ci && npm run build` → `artifacts/frontend/dist` (VITE_* 빌드 주입) |
| 3 | `package/30-collect-ai-server.sh` | `app/` 소스 + pip wheel(torch CPU) + sam2 소스 + yolox 가중치 (+옵션 HF) |
| 4 | `package/40-collect-runtimes.sh` | Temurin JRE17 / CPython 3.11 standalone / Caddy (tar.gz) |
| 5 | `package/50-collect-syspkgs.sh` | **ffmpeg 정적 tarball**(`syspkgs/ffmpeg/`) + **Rocky 9 RPM**(`mesa-libGL`/`libglvnd-glx`/`glib2` → `syspkgs/rpm/`) |

각 디렉토리에 `SHA256SUMS` 가 생성되어 전송 무결성을 검증한다.

> **50 단계의 자동 분기**: ffmpeg 정적 바이너리는 curl 만 있으면 어디서든 받는다(공식 SHA256 검증).
> RPM 은 `dnf`/`yum` 이 있을 때만 `dnf download --resolve` 로 받고, 없으면(mac 등) **graceful SKIP** +
> "rockylinux:9 컨테이너에서 수집" 안내를 남긴다(데비안 빌드머신이면 `.deb` 폴백). 기본 경로는 Rocky 9(rpm).

## Rocky 9 컨테이너 수집 (권장 — wheel·RPM 정합)

mac/다른 OS 빌드머신에서 jar/FE dist/런타임/ffmpeg 까지 만든 뒤, **wheel·RPM 만** Rocky 9 컨테이너에서
채우거나, 처음부터 전부 컨테이너에서 수집한다. 예(전체 수집):

```bash
cd deploy/onprem
docker run --rm -v "$PWD/../..:/work" -w /work/deploy/onprem rockylinux:9 bash -lc '
  dnf -y install dnf-plugins-core java-17-openjdk-devel nodejs python3.11 git curl tar xz findutils && \
  ./scripts/package.sh
'
```

RPM 만 별도로 채우려면(50 단계가 mac 에서 SKIP 한 경우):

```bash
docker run --rm -v "$PWD:/work" -w /work rockylinux:9 bash -lc '
  dnf -y install dnf-plugins-core && \
  dnf download --resolve --alldeps --downloaddir syspkgs/rpm \
    mesa-libGL libglvnd-glx glib2 && \
  ( cd syspkgs/rpm && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
'
```

### 어디서 무엇을 채우나 (산출물별 빌드머신)

| 산출물 | 위치 | OS 종속? | 어디서 수집 |
|--------|------|:--------:|-------------|
| backend jar | `artifacts/backend/` | 무관 | mac/Linux 어디서나(JDK17) |
| frontend dist | `artifacts/frontend/dist/` | 무관 | mac/Linux 어디서나(Node20) |
| 런타임 JRE/Python/Caddy | `runtimes/` | 무관(linux tarball) | mac/Linux 어디서나(curl) |
| **ffmpeg 정적** | `syspkgs/ffmpeg/` | **무관(정적)** | mac/Linux 어디서나(curl) |
| sam2 소스 / yolox 가중치 | `vendor/sam2/`, `models/weights/` | 무관 | mac/Linux 어디서나(git/curl) |
| **pip wheel(torch CPU 등)** | `vendor/wheels/` | **Rocky 9 정합** | **rockylinux:9 컨테이너/머신** |
| **시스템 RPM(mesa-libGL 등)** | `syspkgs/rpm/` | **Rocky 9 정합** | **rockylinux:9 컨테이너/머신**(`dnf download`) |

> **런타임/ffmpeg 공식 체크섬 검증(fail-closed)**: `40-collect-runtimes.sh`(JRE/Python/Caddy)와
> `50-collect-syspkgs.sh`(ffmpeg 정적)는 tarball 을 받은 직후 `scripts/lib/versions.sh` 의 공식
> 체크섬과 대조한다(불일치 시 즉시 중단). 설치 단계 `11-install-runtimes.sh` 도 압축 해제 직전
> 동일 검증을 한 번 더 수행한다. 이는 디렉토리 단위 `SHA256SUMS`(전송 무결성)와 별개의
> **출처(공급망) 무결성** 검증이다.
> Caddy 는 공식 SHA256 을 발행하지 않으므로(SHA-512 만 제공) tar.gz 는 `CADDY_SHA512` 로 검증한다.
> ffmpeg 정적은 BtbN 의 dated autobuild 태그(불변 자산)의 `checksums.sha256` 값으로 검증한다.

## ★ 복사 대상 파일/라이브러리 명세표

### backend

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| 실행 jar | `backend/build/libs/*.jar` (bootJar) | `artifacts/backend/klid-backend.jar` | ~60–90MB |

> 런타임 의존(JRE17·ffmpeg·curl)은 runtimes/·syspkgs/ 에서 별도 수집.

### frontend

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| 정적 dist | `frontend/dist` (vite build) | `artifacts/frontend/dist` | ~수 MB |

> VITE_API_BASE_URL=/api/v1, VITE_TOKEN_INGRESS=all 이 **빌드 시점에 정적 치환**됨.

### ai-server

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| 앱 소스 | `ai-server/app` | `artifacts/ai-server/app` | < 1MB |
| requirements | `ai-server/requirements.txt` | `artifacts/ai-server/requirements.txt` | 작음 |
| pip wheel(일반) | PyPI (`pip download`) | `vendor/wheels/*.whl` | 수백 MB |
| **torch/torchvision (CPU)** | **PyTorch CPU 인덱스** | `vendor/wheels/` | **~200MB+** |
| sam2 소스 | `git clone facebookresearch/sam2` | `vendor/sam2/sam2-src` | ~수십 MB |
| YOLOX 가중치 | `ai-server/weights/yolox_s.onnx` | `models/weights/yolox_s.onnx` | ~35MB |
| (옵션) HF 모델 | HuggingFace Hub | `models/hf-cache/` | 모델별 수백 MB |

### 런타임 / 시스템 의존성

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| Temurin JRE 17 (17.0.19+10) | adoptium (versions.sh URL) | `runtimes/jdk/*.tar.gz` | ~45MB |
| CPython 3.11 standalone (3.11.15) | python-build-standalone (태그 20260623) | `runtimes/python/*.tar.gz` | ~30MB |
| Caddy (2.11.4) | caddyserver releases | `runtimes/caddy/*.tar.gz` | ~30MB |
| **ffmpeg 정적 (n7.1.5)** | BtbN FFmpeg-Builds (versions.sh URL) | `syspkgs/ffmpeg/*.tar.xz` | ~40MB |
| **RPM: mesa-libGL/libglvnd-glx/glib2** | `dnf download`(Rocky 9) | `syspkgs/rpm/*.rpm` | 수~수십 MB |

## 함정 요약(반드시 인지)

- **torch CPU**: 기본 PyPI 는 CUDA wheel(거대)을 준다. 스크립트는 PyTorch CPU 인덱스에서 받는다.
  현재 lock 버전 `torch==2.12.0` / `torchvision==0.27.0` 의 **cp311 x86_64 CPU wheel 이 CPU 인덱스에
  존재함을 확인**(2026-06-24)했으므로 lock 그대로 받힌다. 향후 lock 의 torch 버전이 CPU 인덱스에
  없을 때만 실패하며 그 경우 → 06-troubleshooting "torch CPU" 절.
- **sam-2**: requirements 의 `git+https://.../sam2.git` 는 폐쇄망에서 설치 불가 → git 소스를 vendor 해
  설치 시 로컬 경로로 처리한다. Dockerfile 은 torch 를 strip 했지만 **베어메탈은 torch 도 설치**한다.
- **HF 모델**: yolox 백엔드만 쓰면 불필요. rtdetr/SAM2 사용 시 `PREFETCH_HF=1` 로 미리 받아야 폐쇄망에서 동작.

## 전송

수집이 끝나면 `deploy/onprem/` 폴더 **전체**를 USB/전송 매체로 복사해 대상 서버로 옮긴다.
(`VERSION.built` 에 수집 시점/커밋이 기록된다.)
