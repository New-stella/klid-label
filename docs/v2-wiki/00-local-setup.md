# 00. 로컬 자족 실행 (외부 0개 테스트)

> **목적**: 관제서버·포털·비식별 서버·실 VLM 등 **외부 서버 0개**로 라벨링·검수·마킹·배치 파이프라인을 **사람이 직접 클릭으로 검증**하는 로컬 환경을 띄운다.
> **정본**: 루트 [`CLAUDE.md`](../../CLAUDE.md), `application-local.yml`, `docker-compose.local.yml`. 충돌 시 정본이 우선.

## 무엇이 외부 없이 되고, 무엇이 mock 인가

| 구성 | 로컬 동작 | 비고 |
|------|----------|------|
| PostgreSQL (control + portal) | **필수** (번들/로컬) | MNG_*·QRTZ_* 는 Flyway 가 로컬에 stub 생성 — 관제 실DB 불요 |
| 인증 (관제/포털 토큰) | **자체 발급** | `POST /api/v1/dev/tokens` (HS256 동일 시크릿 서명) — 외부 발급 서버 불요 |
| 시드 데이터 | **자동 적재** | `DevSeedRunner`(local)가 `db/seed/dev-seed.sql` 멱등 적재 |
| 비식별 | **실 KPST 연동 (기본)** | `application-local.yml` 기본값이 KPST API 연동 테스트 서버 실연동(`mock-mode=false`, `kpst.deid.enabled=true`, `base-url=http://222.118.130.251:9989`, 계정 `authoring`). **내부망 접근 필요.** 오프라인 자족이 필요하면 `DEIDENTIFY_MOCK_MODE=true`(+`KPST_DEID_ENABLED=false`)로 mock 복사(원본→비식별 경로 복사 + `DE_IDNTF_YN='Y'`) 전환 |
| ai-server (YOLOX/SAM2/RT-DETR) | **CPU 실추론** | 가중치: YOLOX ONNX(`yolox_s.onnx`) 동봉, SAM2 는 Meta HF(`facebook/sam2-hiera-tiny`), RT-DETR 은 HF 캐시. GPU 불필요 |
| VLM 시계열 | **비활성 (NO-OP)** | `vlm.client.enabled=false`. 실로드 미구현 |
| 관제 통지 / 증강 | **비활성 / mock** | `control-notify.enabled=false`, 증강 클라이언트 mock |
| 관제 자동 적재 픽업 | **수동 트리거** | `POST /api/v1/dev/batch/scan` (REVIEWER 토큰) — 시드 클립 픽업 검증 |

> **외부 0개 정의**: 관제/포털/실VLM 서버가 없다는 뜻. ai-server 는 compose 스택 내부 서비스로 **실제로 구동**한다(외부 아님). RT-DETR·SAM2(Meta) 최초 기동 시에만 HuggingFace 에서 모델을 1회 받는다(이후 캐시로 오프라인). YOLOX 는 동봉 ONNX 가중치 사용.
>
> ⚠ **비식별은 예외 — 기본값이 실 KPST 연동(외부)으로 변경됨**: local 도 KPST API 연동 테스트 서버(`222.118.130.251:9989`, 내부망 http)에 실제 연동한다. 즉 비식별 단계만은 "외부 0개"가 아니며 **내부망 접근이 필요**하다. 완전 오프라인 자족이 필요하면 `DEIDENTIFY_MOCK_MODE=true` + `KPST_DEID_ENABLED=false` 로 기존 mock 복사 모드로 되돌린다. (현재 KPST 테스트 서버 마스킹 엔진이 잡을 `procState 99`(에러)로 실패시킬 수 있어, 연동은 되어도 비식별 결과물이 안 나올 수 있다 — 연결·업로드·프로젝트·폴링 API 는 정상.)

---

## 방법 A — Docker Compose (올인원)

GPU 없는 환경(Mac 등)은 **로컬 override** 를 함께 적용한다.

```bash
cp .env.example .env
#   .env 에서 최소: CONTROL_DB_PASSWORD, JWT_SECRET, STREAM_SIGN_SECRET 채움
#   (JWT_SECRET/STREAM_SIGN_SECRET: openssl rand -hex 48 / -hex 32)
#   ENV 환경변수는 설정하지 말 것 (dev/stg/prd 면 LocalProfileGuard·비식별 mock 게이트가 부트 차단)

docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

`docker-compose.local.yml` 가 적용하는 것:
- ai-server: `AI_MOCK_MODE=false` + `AI_DEVICE=cpu` + `DETECTOR_BACKEND=rtdetr` + `runtime: runc`(nvidia 제거) + 가중치/HF 캐시 마운트
- postgres/backend: `/data` 호스트 절대경로 → named volume (비-Linux 호환)

| 서비스 | URL |
|--------|-----|
| backend | http://localhost:18081 (Swagger: `/api/swagger-ui.html`) |
| ai-server | http://localhost:19300 (`/health`) |
| frontend | http://localhost:13000 (`/dev/login`) |

> GPU 서버(cudo_246) 배포는 override 없이 `docker compose -f docker-compose.yml up -d` (nvidia + cuda).

---

## 방법 B — Native (Mac 권장, 가벼움)

```bash
# 0) compose 로 DB만 띄울 경우에도 .env 필요 (env_file 참조)
cp .env.example .env          # CONTROL_DB_PASSWORD 등 채움

# 1) PostgreSQL (control=klid_system + portal) 기동 — 로컬 설치 또는 도커 단독
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d klid-postgres

# 2) backend (local 프로파일 — DB만 있으면 외부 0개로 부팅, 시드 자동 적재)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'
#   (필요 시 프레임 이미지: --args='--spring.profiles.active=local,seed-image-gen')

# 3) ai-server (CPU 실추론 + RT-DETR)
cd ai-server && python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env          # AI_MOCK_MODE=false, AI_DEVICE=cpu, DETECTOR_BACKEND=rtdetr
uvicorn app.main:app --host 0.0.0.0 --port 9300

# 4) frontend
cd frontend && npm install && npm run dev   # http://localhost:5174
```

---

## 인증 — dev 토큰 발급

저작도구는 독립 로그인 UI 가 없고 관제/포털 발급 JWT 를 인계받는다. 로컬은 **자체 발급**으로 대체한다.

- **FE**: `/dev/login` → 역할(REVIEWER/WORKER/PORTAL_USER) 선택 → 토큰 발급 → `localStorage` 저장(운영 인계 흐름 1:1 재현)
- **API/Swagger**: `POST /api/v1/dev/tokens` (body `{"role":"REVIEWER","channel":"INTERNAL"}`) → `data.token` 을 Swagger **Authorize** 또는 `Authorization: Bearer` 헤더로 사용

> **주의**: `/api/v1/dev/batch/**`(scan/trigger 등)는 **REVIEWER 토큰 필수**(무인증 차단). `/api/v1/dev/tokens` 만 무인증 진입.

---

## 시나리오별 검증 경로

| 시나리오 | 경로 | 외부 |
|---------|------|:---:|
| 인증/진입 (역할별) | `/dev/login` 또는 `POST /api/v1/dev/tokens` | 0 |
| 작업 배정·재배정·이력 | 관리 화면 (REVIEWER) | 0 |
| 라벨링 (bbox/polygon/seg) | 라벨링 캔버스 (WORKER) | 0 |
| 검수 승인/반려 + 버전 스냅샷 / diff·롤백 | 검수 화면 (REVIEWER) | 0 |
| 포털 (데이터마트 Load·사용자 라벨) | 포털 (PORTAL_USER) | 0 |
| 마킹 (비식별 영상 스트리밍) | 마킹 화면 — 비식별 mock 으로 `DE_IDNTF_YN='Y'` 선행 필요 | 0 (mock) |
| 관제 자동 적재 픽업 | `POST /api/v1/dev/batch/scan` (REVIEWER) → 시드 클립 LS_DATA_RAW 적재 | 0 |
| 배치/오토라벨 (RT-DETR·SAM2) | 업로드 `POST /api/v1/dev/autolabel-test` 또는 `POST /api/v1/dev/batch/trigger?rawSn=` | ai-server(내부, 실추론) |

> 시드 클립(`DEV-CLIP-*`)의 `FILE_PATH` 는 실파일이 없을 수 있어 픽업·적재·이벤트 발행까지 검증된다. 마킹/프레임 추출까지 끝까지 돌리려면 `POST /api/v1/dev/autolabel-test` 로 실제 영상을 업로드한다.

> **스토리지 권한 (Docker 배포 필수)**: backend 컨테이너는 비루트(uid 999)로 실행되는데, host bind-mount `/data/klid/storage` 가 root 소유로 생성되면 영상 저장 경로(`raw/`)에 쓰지 못해 업로드가 `영상 파일 저장에 실패했습니다`(AccessDeniedException)로 실패한다. compose 의 **`klid-storage-init`(one-shot)** 서비스가 backend 기동 전 `mkdir -p raw/deidentified && chown -R 999:999` 로 자동 보정한다. 수동 보정이 필요하면: `chown -R 999:999 /data/klid/storage && chmod -R 775 /data/klid/storage`. backend Dockerfile 은 uid/gid 를 999 로 고정해 init 의 chown 과 정합한다.

---

## 외부 0개 토글 (요약)

| 토글 | 로컬 값 | 의미 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | local | 자립 기동 + mock/시드 게이팅 |
| (`application-local.yml`) `deidentify.mock-mode` (`DEIDENTIFY_MOCK_MODE`) | **false (기본)** | 실 KPST 연동. `true` 면 mock no-op 복사로 전환 |
| `KPST_DEID_ENABLED` / `KPST_DEID_BASE_URL` | true / `http://222.118.130.251:9989` | KPST 폴링 경로 + 테스트 서버 주소(내부망 http, ca-cert 불요) |
| `AI_MOCK_MODE` / `AI_DEVICE` | false / cpu | ai-server 실추론 (GPU 불필요) |
| `DETECTOR_BACKEND` | rtdetr | 탐지/트래킹 기본 백엔드 (yolo 로 전환 가능) |
| `VLM_CLIENT_ENABLED` | false | VLM NO-OP |
| `CONTROL_NOTIFY_ENABLED` | false | 관제 통지 빈 미등록 |
| `BATCH_ENABLED` / `TRAINING_SCAN_ENABLED` | false | Quartz 자동 트리거 off (scan 은 수동) |
| `AUTHORING_DEV_SEED_ENABLED` | true | dev-seed 자동 적재 |
| `ENV` | (미설정) | dev/stg/prd 면 부트 차단 |

## 주의 / 한계

- **RT-DETR 최초 1회 인터넷 필요** — `PekingU/rtdetr_v2_r50vd`(~100MB) HF 다운로드. 이후 캐시(`HF_HOME=/app/.hf-cache`)로 오프라인. 다운로드 실패 시 자동 mock fallback(기동 무중단). 완전 오프라인을 원하면 `DETECTOR_BACKEND=yolo`(로컬 `.pt`).
- **GPU 옵트인** — GPU 환경은 `AI_DEVICE=cuda` + override 없이 `docker-compose.yml`(runtime nvidia).
- **VLM 실추론 미지원** — 항상 mock. local 에선 `enabled=false` 라 무관.
- **local/dev 비식별 기본 = 실 KPST 연동** — `application-local.yml`·`application-dev.yml` 기본값이 KPST API 연동 테스트 서버 실연동이다(`222.118.130.251:9989`, 내부망 http, ca-cert 불요, 계정 `authoring`). **내부망 접근이 없으면 비식별 단계가 실패('F')한다.** 오프라인 자족 검증은 `DEIDENTIFY_MOCK_MODE=true`(+`KPST_DEID_ENABLED=false`)로 mock 복사 모드 전환 — `mock-mode=true` 는 순수 local 프로파일에서만 허용(비-local/`ENV`=dev·stg·prd 에서 true 면 `DeidentifyStep` 부트 차단). 운영(prd)은 `application.yml` 기본 + 배포 환경변수로 별도 설정한다.
