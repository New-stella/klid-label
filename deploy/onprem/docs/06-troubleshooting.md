# 06. 트러블슈팅 (오프라인 설치)

## 누락 wheel — `pip ... No matching distribution`

증상: `13-install-ai-server.sh` 에서 `--no-index` 설치가 특정 패키지를 못 찾음.

원인/해결:
- 빌드머신 python 과 대상 python 의 **마이너/ABI 불일치**(cp310 vs cp311). 빌드머신을 `python3.11` 로
  맞춰 `PYTHON_BIN=python3.11 ./scripts/package.sh` 재수집.
- 빌드머신 **OS/glibc 불일치**(manylinux 태그). 대상과 동일 Linux x86_64 빌드머신에서 재수집.
- 일부 패키지의 sdist 만 받힌 경우 → 대상에 빌드도구가 없어 실패. 빌드머신에서 동일 환경으로 wheel 을
  강제 수집하거나 누락분만 `pip download --only-binary=:all:` 로 보강.

## torch CPU — 버전/인덱스 문제

증상: `30-collect-ai-server.sh` 의 torch CPU 다운로드 실패(예: `torch==2.12.0` 가 CPU 인덱스에 없음).

원인: requirements lock 의 torch/torchvision 버전이 PyTorch CPU 인덱스(`download.pytorch.org/whl/cpu`)에
존재하지 않을 수 있다(lock 은 CUDA/기본 인덱스 기준 핀일 수 있음).

해결:
- CPU 인덱스에 존재하는 인접 버전으로 조정. 인덱스에서 가용 버전 확인 후
  `scripts/lib/versions.sh` 또는 requirements 의 핀을 맞춘다.
- 또는 빌드머신에서 직접:
  ```bash
  python3.11 -m pip download --dest vendor/wheels \
    --index-url https://download.pytorch.org/whl/cpu \
    torch torchvision    # 버전 핀 없이 CPU 인덱스 최신 호환본
  ```
  단, sam2 가 요구하는 torch 하한(소스 기준 torch>=2.5.1)을 충족해야 한다.

## sam2 설치 실패

증상: `pip install vendor/sam2/sam2-src` 실패.

해결:
- SAM2 분할/Track 을 쓰지 않으면 무시 가능(yolox 탐지/오토라벨링은 동작).
- 쓰려면: sam2 빌드 의존(torch 등)이 wheels 에 있어야 한다. torch CPU 수집이 선행됐는지 확인.
- 재현성 위해 `versions.sh` 의 `SAM2_GIT_REF` 를 특정 커밋으로 고정 후 재수집 권장.

## ffmpeg / ffprobe 없음

증상: backend 프레임 추출/duration 추출 실패(`ffmpeg`/`ffprobe` not found).

해결:
- 데비안: `syspkgs/deb/` 에 ffmpeg `.deb` 가 포함됐는지 확인 → `sudo dpkg -i syspkgs/deb/*.deb`.
- 비데비안: `sudo dnf install ffmpeg`(미러 필요). 또는 backend.env `FFMPEG_BIN`/`FFPROBE_BIN` 에 절대경로.

## libGL / opencv (`ImportError: libGL.so.1`)

증상: ai-server 기동 시 opencv import 실패.

해결: `libgl1`, `libglib2.0-0` 설치(데비안 `.deb` 또는 RHEL `mesa-libGL glib2`).

## glibc 불일치 (`version 'GLIBC_2.xx' not found`)

원인: 빌드머신 glibc > 대상 서버 glibc.
해결: **대상 서버와 동일(또는 더 낮은) glibc 빌드머신**에서 재수집. 런타임 바이너리/whl 모두 영향.

## HF 모델 못 찾음 (rtdetr/SAM2, 오프라인)

증상: `OSError: ... not found ... offline mode`.
해결:
- `DETECTOR_BACKEND=yolox` 면 HF 불필요 — yolox 로 변경.
- rtdetr/SAM2 필요 시 빌드머신에서 `PREFETCH_HF=1 ./scripts/package.sh` 로 `models/hf-cache` 채워 재설치,
  ai-server.env `HF_HOME` 가 캐시 경로를 가리키는지 확인.

## 포트 충돌

증상: `Address already in use` (80/8080/9300).
해결: `sudo ss -ltnp | grep -E ':(80|8080|9300)'` 로 점유 프로세스 확인 후 정리, 또는 유닛/설정에서 포트 변경.
80 은 Caddy(비루트)라 `setcap`/`AmbientCapabilities` 가 필요 — 14 스크립트가 처리하나 실패 시 경고 참고.

## 권한 오류

증상: 저장소/캐시 쓰기 권한 거부.
해결: `/var/lib/klid`, `/opt/klid/ai/.hf-cache` 등이 `klid:klid` 소유인지 확인
(`sudo chown -R klid:klid /var/lib/klid /opt/klid`).

## backend 부팅 실패 — webhook HMAC

증상: prd 부팅 시 `WEBHOOK_HMAC_SECRET_VLM 환경변수가 필수입니다 (prd)`.
해결: `backend.env` 의 `WEBHOOK_HMAC_SECRET_VLM`/`_AUGMENT` 를 강한 값으로 채움(`openssl rand -hex 32`).

## backend 부팅 실패 — DB validate

증상: Hibernate `ddl-auto=validate` 가 `MNG_*`/`QRTZ_*` 테이블/컬럼 부재로 실패.
해결: 관제 공유 스키마가 대상 DB 에 준비됐는지 확인. 온프렘 자체 DB 면 공유 테이블 사전 생성 필요
(관제 인프라/DBA 협의). 04-configuration.md D 절 참고.

## backend 부팅 실패 — 비식별 설정오류

증상: `DeidentifyStep` 설정오류 거부(KPST disabled + mock 아님).
해결: 04-configuration.md B 절 "비식별 자족 기동 주의" 참고 — KPST 연동(①) 또는 앱 보강(②) 결정 필요.
