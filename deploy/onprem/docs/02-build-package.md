# 02. [빌드머신] 패키지 수집

인터넷이 되는 빌드머신(대상과 동일 Linux x86_64)에서 1회 실행한다.

## 실행

```bash
cd deploy/onprem
./scripts/package.sh
```

옵션:

```bash
# 데비안 .deb 수집 생략(비데비안 빌드머신 또는 대상이 직접 설치)
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
| 5 | `package/50-collect-syspkgs.sh` | (데비안) ffmpeg/libgl1/libglib2.0-0/curl `.deb` |

각 디렉토리에 `SHA256SUMS` 가 생성되어 전송 무결성을 검증한다.

> **런타임 공식 체크섬 검증(fail-closed)**: `40-collect-runtimes.sh` 는 JRE/Python/Caddy tar.gz 를
> 받은 직후 `scripts/lib/versions.sh` 의 공식 체크섬과 대조한다(불일치 시 즉시 중단). 설치 단계
> `11-install-runtimes.sh` 도 압축 해제 직전 동일 검증을 한 번 더 수행한다. 이는 디렉토리 단위
> `SHA256SUMS`(전송 무결성)와 별개의 **출처(공급망) 무결성** 검증이다.
> Caddy 는 공식 SHA256 을 발행하지 않으므로(SHA-512 만 제공) tar.gz 는 `CADDY_SHA512` 로 검증한다.

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

### 런타임 / 시스템 패키지

| 무엇 | 어디서 수집 | 번들 위치 | 대략 용량 |
|------|-------------|-----------|:---------:|
| Temurin JRE 17 (17.0.19+10) | adoptium (versions.sh URL) | `runtimes/jdk/*.tar.gz` | ~45MB |
| CPython 3.11 standalone (3.11.15) | python-build-standalone (태그 20260623) | `runtimes/python/*.tar.gz` | ~30MB |
| Caddy (2.11.4) | caddyserver releases | `runtimes/caddy/*.tar.gz` | ~30MB |
| ffmpeg/libgl1/libglib2.0-0/curl `.deb` | apt-get download | `syspkgs/deb/*.deb` | 수십 MB |

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
