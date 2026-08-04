# 00. 로컬 자족 실행 (외부 0개 테스트)

> **목적**: 관제서버·포털·비식별 서버·실 VLM 등 **외부 서버 0개**로 라벨링·검수·마킹·배치 파이프라인을 **사람이 직접 클릭으로 검증**하는 로컬 환경을 띄운다.
> **정본**: 루트 [`CLAUDE.md`](../../CLAUDE.md), `application-local.yml`, `docker-compose.local.yml`. 충돌 시 정본이 우선.

## 무엇이 외부 없이 되고, 무엇이 mock 인가

| 구성 | 로컬 동작 | 비고 |
|------|----------|------|
| PostgreSQL (control + portal) | **필수** (번들/로컬) | MNG_*·QRTZ_* 는 Flyway 가 로컬에 stub 생성 — 관제 실DB 불요 |
| 인증 (관제/포털 토큰) | **자체 발급** | `POST /api/v1/dev/tokens` (HS256 동일 시크릿 서명) — 외부 발급 서버 불요 |
| 시드 데이터 | **자동 적재** | `DevSeedRunner`(local)가 `db/seed/dev-seed.sql` 멱등 적재 — **추가만 하고 아무것도 지우지 않는다**(아래 주의) |
| 비식별 (KPST) | **목 서버로 실 HTTP 연동** | compose 의 `mock-server` 컨테이너(`klid-mock-server:9400`)에 실제 위탁한다(`mock-mode=false`, `kpst.deid.enabled=true`, `base-url=http://klid-mock-server:9400`, 계정 `authoring`). 벤더 실서버·내부망 불요. 목이 공유 볼륨에 비식별 결과 파일을 생성해 BE 무결성 검증까지 통과한다 |
| ai-server (YOLOX/SAM2) | **CPU 실추론** | 가중치: YOLOX ONNX(`yolox_s.onnx`) 동봉, SAM2 는 Meta HF(`facebook/sam2-hiera-tiny`). 탐지는 YOLOX 단일 백엔드(onnxruntime). GPU 불필요 |
| VLM 시계열 | **목 서버로 실 HTTP 연동** | `VLM_CLIENT_ENABLED=true` + `klid-mock-server:9400` + `VLM_ALLOW_INSECURE_URL=true`(평문·내부 호스트 완화, local/dev 전용). `describe` 위탁 → 목이 `/v1/vlm/callback` 으로 결과 콜백. `false` 면 단계가 통째로 SKIPPED 되어 결과가 빈다(`LS_BATCH_PROC_LOG` 에 `VLM/SKIPPED` 기록). 완화 플래그 없이 `enabled=true` 만 켜면 `VlmUrlPolicy` 가 빈 생성을 막아 **기동이 실패**한다 |
| 관제 통지 | **목 서버로 실 HTTP 전송** | `CONTROL_NOTIFY_ENABLED=true` + `CONTROL_NOTIFY_URL=http://klid-mock-server:9400`(local override). 목이 409/404 를 관제 계약대로 돌려주므로 자기치유가 실동작 검증된다 |
| 증강 (생성형 AI) | **목 서버로 실 HTTP 위탁** | `AUGMENT_EXTERNAL_MODE=http` + `AUGMENT_API_BASE_URL=http://klid-mock-server:9400`. 목의 `POST /api/genai/jobs` 가 `job_id` 를 발급하고 결과를 `/v1/genai/callback` 으로 push 한다. 구 자족 시뮬레이터(`mode=dev`)는 제거됐다 |
| 관제 자동 적재 픽업 | **수동 트리거** | `POST /api/v1/dev/batch/scan` (REVIEWER 토큰) — 시드 클립 픽업 검증 |

> **외부 0개 정의**: 관제/포털/**벤더 실서버**(비식별·VLM)가 없다는 뜻. ai-server 와 mock-server 는 compose 스택 내부 서비스로 **실제로 구동**한다(외부 아님). SAM2(Meta) 최초 기동 시에만 HuggingFace 에서 모델을 1회 받는다(이후 캐시로 오프라인). 탐지용 YOLOX 는 동봉 ONNX 가중치를 사용하므로 다운로드가 없다.
>
> ★ **구속 원칙 — 내부 self-fill 금지**: 저작도구가 스스로 결과를 채우는 경로(`DEIDENTIFY_MOCK_MODE=true` = 외부 무접촉 원본 복사)는 쓰지 않는다. 그 경로로는 "연동이 실제로 되는지"를 검증할 수 없기 때문이다. 로컬·dev 모두 **목 서버(:9400)에 실제 HTTP 요청**을 보낸다. 네이티브(방법 B)로 파이프라인까지 돌리려면 mock-server 를 호스트에서 함께 띄우고 `KPST_DEID_BASE_URL`/`VLM_SERVICE_URL=http://localhost:9400` 을 주입한다(미기동 시 비식별은 실패 처리 — 원본은 보존).

---

## 방법 A — Docker Compose (올인원)

GPU 없는 환경(Mac 등)은 **로컬 override** 를 함께 적용한다.

```bash
cp .env.example .env
#   .env 에서 최소: CONTROL_DB_PASSWORD, JWT_SECRET, STREAM_SIGN_SECRET 채움
#   (JWT_SECRET/STREAM_SIGN_SECRET: openssl rand -hex 48 / -hex 32)
#   ENV 환경변수는 설정하지 말 것 (dev/stg/prd 면 LocalProfileGuard·비식별 mock 게이트가 부트 차단)

# 목 서버(벤더 KPST·VLM) 이미지 최초 1회 빌드
docker compose -f docker-compose.yml -f docker-compose.local.yml build mock-server

docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

`docker-compose.local.yml` 가 적용하는 것:
- backend: **`SPRING_PROFILES_ACTIVE=local` 고정** — base compose 는 `dev` 로 뜨므로 로컬 자족 기동은 여기서 되돌린다(로컬 기본 시크릿·`DevSeedRunner`·mock 게이팅이 local 프로파일에 달려 있다)
- ai-server: `AI_MOCK_MODE=false` + `AI_DEVICE=cpu` + `runtime: runc`(nvidia 제거) + 가중치/HF 캐시 마운트 (탐지는 YOLOX 단일)
- postgres/backend/mock-server: `/data` 호스트 절대경로 → named volume (비-Linux 호환)

> ⚠ **두 파일을 반드시 함께 지정**한다. `-f docker-compose.yml` 만 쓰면 dev 프로파일 + `/data` 바인드로 뜬다.

| 서비스 | URL |
|--------|-----|
| backend | http://localhost:18081 (Swagger: `/api/swagger-ui.html`) |
| ai-server | http://localhost:19300 (`/health`) |
| mock-server (벤더 목: KPST·VLM) | http://localhost:9400 (`/health`) |
| frontend | http://localhost:13000 (`/dev/login`) |

> GPU 서버(cudo_246) 배포는 override 없이 `docker compose -f docker-compose.yml up -d` (nvidia + cuda, **dev 프로파일**). 서버 `.env` 갱신 항목은 `docs/operations/config-key-changes-20260727.md` §4-2 참조.
>
> mock-server 는 **무인증 + 콜백 SSRF 표면**이라 로컬/dev 신뢰망 전용이다. stg/prd 는 도커를 쓰지 않아 전파되지 않는다.

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

# 3) ai-server (CPU 실추론 — 탐지 YOLOX 단일)
cd ai-server && python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env          # AI_MOCK_MODE=false, AI_DEVICE=cpu (탐지 YOLOX 단일 — 동봉 ONNX)
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

## 시드 재적재 정책 (재기동해도 작업 데이터는 남는다)

`DevSeedRunner` 는 부팅마다 `db/seed/dev-seed.sql` 을 **단일 트랜잭션**으로 적재한다.

- **추가만 한다** — 모든 INSERT 가 `ON CONFLICT DO NOTHING/DO UPDATE` 이며 **DELETE 가 없다**. 재기동해도 그동안 만든 영상·프레임·라벨·검수 상태는 그대로 남는다.
  - 과거에는 "시드 범위 DELETE 후 INSERT" 였고, `DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'` 가 자식 FK(`ON DELETE CASCADE`)를 타고 **프레임까지 지웠다**. 뒤이은 INSERT 가 실패하면 삭제만 커밋돼 **재기동 = 작업 데이터 소실**이었다(실제 사고: 프레임 23건 전량 소실).
- **실패해도 반쯤 적용되지 않는다** — 어느 구문이 실패하면 전량 롤백되고, 로그에 `[DevSeed] seed apply FAILED — 전량 롤백되어 시드가 적용되지 않았습니다` 가 남는다. 부팅은 계속된다(로컬 편의 기능).
- **파이프라인을 처음부터 다시 돌리려면** 재기동이 아니라 재큐 API 를 쓴다 — `POST /api/v1/control-ingests/{rcptnSn}/requeue`(단건) / `POST /api/v1/control-ingests/requeue`(일괄, `{"limit":100}`). 완전 초기화가 필요하면 로컬 DB 볼륨을 지우고 다시 띄운다(명시적 파괴 행위여야 한다).
- **라벨 마스터를 화면에서 수정해도 시드가 깨지지 않는다** — 라벨명을 바꾸거나 COCO 매핑(`DTCT_TYPE_CD`)을 다른 라벨에 옮겨도, 시드는 충돌 시 조용히 물러난다(활성 라벨 1개 = COCO 클래스 1개 제약은 유지).

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
| 배치/오토라벨 (YOLOX·SAM2) | 업로드 `POST /api/v1/dev/autolabel-test` 또는 `POST /api/v1/dev/batch/trigger?rawSn=` | ai-server(내부, 실추론) |

> 시드 클립(`DEV-CLIP-*`)의 `FILE_PATH` 는 실파일이 없을 수 있어 픽업·적재·이벤트 발행까지 검증된다. 마킹/프레임 추출까지 끝까지 돌리려면 `POST /api/v1/dev/autolabel-test` 로 실제 영상을 업로드한다.

---

## 목 산출물의 육안 확인 (2026-07-30, mock-server)

목 서버 산출물이 화면에서 **구분되지 않아** "정말 처리된 것인가"를 확인할 수 없던 문제를 해소했다. 목 전용 개발 편의이며 제품 기능이 아니다(정본: [`mock-server/README.md`](../../mock-server/README.md)).

| 항목 | 동작 |
|------|------|
| **비식별 워터마크** | 목이 산출하는 비식별 영상의 **모든 프레임 우측하단에 `MOCK 비식별 완료` 텍스트를 실제로 굽는다**(ffmpeg `drawtext`, 흰 글씨 + 반투명 검정 박스). 마킹/라벨링 화면 스트리밍 재생만으로 비식별 처리 여부가 구분된다. 문구에 `MOCK` 이 있는 이유 — 목이 굽는 대상은 실제 마스킹된 영상이 아니라 **원본 복사본**이라, `비식별 완료` 만 새기면 PII 가 남은 영상에 보증 문구가 박힌다 |
| 워터마크 폴백 | ffmpeg 부재 · `drawtext`(libfreetype) 미포함 빌드 · 한글 폰트 부재 · 인코딩 실패/타임아웃 · 길이 검증 실패면 **WARN 후 원본 바이트 복사**로 자동 전환(워터마킹 실패는 산출 실패가 아니다). 컨테이너 이미지에는 `ffmpeg` + `fonts-nanum` 이 포함돼 실제로 구워진다 — **호스트 venv 실행 시엔 복사로 폴백될 수 있다** |
| **VLM describe 시계열** | 구 고정 16초 2구간 대신 **대상 영상 실제 길이 전체를 덮는 구간 배열**을 만든다(window 8초, 겹침·빈틈 없음, 최대 450구간). 길이는 3단 폴백 — ①요청 `media.duration_sec`(목 전용 확장, BE 는 보내지 않음) → ②`ffprobe`(절대경로 + 허용 루트 안일 때만) → ③고정 **16초** |
| **KPST `POST /project` 비동기 접수** | **즉시 200 반환 + 백그라운드 산출**로 바뀌었다. 응답 직후에는 결과 파일이 아직 없는 것이 정상이며, 완료는 `retrieve_progress` 의 `procState`(**1** 진행중 / **2** 완료 / **99** 오류)로 확인한다. 산출이 끝나기 전에는 진행률이 99% 로 눌려 `procState=1` 로 보고된다 |

> **⛔ 목의 `POST /project` 를 동기로 되돌리지 말 것**: 구 구현은 요청 안에서 인코딩까지 마쳤는데, 그러면 BE `KpstDeidentifyClient` 의 45초 타임아웃 안에 끝내야 해서 ①산출물을 잘라 빨리 끝내거나(조용한 절단 = 학습데이터 오염) ②45초 초과로 위탁 실패(`DE_IDNTF_YN='F'`) + 같은 `projectName` 재위탁이 409 로 영구 차단, 둘 중 하나로 귀결된다. 실제 KPST 도 비동기이고 BE 는 `KpstDeidentPollJob` 으로 완료를 감지한다.
>
> ⚠ `MOCK_INPUT_BASE` 는 사실상 필수다 — 미설정 시 자동 도출이 `MOCK_OUTPUT_BASE` 의 1단 상위라, `/nas-storage` 같은 최상위 1단 경로를 주면 허용 루트가 `/` 로 붕괴한다. 붕괴 항목은 채택되지 않고 **fail-closed** 로 떨어져 원본을 읽지 않으며(산출 실패 `procState=99`), VLM describe 의 ffprobe 길이 조회도 비활성돼 16초 폴백이 된다.

> **스토리지 권한 (Docker 배포 필수)**: backend 컨테이너는 비루트(uid 999)로 실행되는데, host bind-mount `/data/klid/storage` 가 root 소유로 생성되면 영상 저장 경로(`raw/`)에 쓰지 못해 업로드가 `영상 파일 저장에 실패했습니다`(AccessDeniedException)로 실패한다. compose 의 **`klid-storage-init`(one-shot)** 서비스가 backend 기동 전 `mkdir -p raw/deidentified && chown -R 999:999` 로 자동 보정한다. 수동 보정이 필요하면: `chown -R 999:999 /data/klid/storage && chmod -R 775 /data/klid/storage`. backend Dockerfile 은 uid/gid 를 999 로 고정해 init 의 chown 과 정합한다.

---

## 외부 0개 토글 (요약)

| 토글 | 로컬 값 | 의미 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | local (override 가 고정) | 자립 기동 + 시드/로컬 기본값 게이팅 |
| `DEIDENTIFY_MOCK_MODE` | **false** (compose 주입) | 내부 self-fill 금지 — 목 서버로 실 위탁. `true` 면 외부 무접촉 원본 복사로 되돌아간다 |
| `KPST_DEID_ENABLED` / `KPST_DEID_BASE_URL` | true / `http://klid-mock-server:9400` | KPST 폴링 경로 + 목 서버 주소(http, ca-cert 불요) |
| `AI_MOCK_MODE` / `AI_DEVICE` | false / cpu | ai-server 실추론 (GPU 불필요). 탐지는 YOLOX 단일 |
| `VLM_CLIENT_ENABLED` / `VLM_SERVICE_URL` / `VLM_ALLOW_INSECURE_URL` | true / `http://klid-mock-server:9400` / true | VLM describe 위탁 + 콜백 수신. 완화 플래그는 local/dev 프로파일에서만 인정 — 그 밖(또는 `ENV=stg\|prd` 표식)에서 true 면 기동 실패 |
| `AUGMENT_EXTERNAL_MODE` / `AUGMENT_API_BASE_URL` | http / `http://klid-mock-server:9400` | 증강 위탁을 목 서버로 실제 POST. `noop` 은 외부 미연동(dev/stg/prd 기본) |
| `WEBHOOK_CALLBACK_BASE_URL` | `http://klid-backend:8080/api` | 목 서버가 결과를 되돌려줄 주소. localhost 면 콜백이 전부 유실된다 |
| `CONTROL_NOTIFY_ENABLED` / `CONTROL_NOTIFY_URL` | true / `http://klid-mock-server:9400` | 관제 통지를 목 서버로 실제 전송(local override) |
| `BATCH_ENABLED` / `TRAINING_SCAN_ENABLED` | false | Quartz 자동 트리거 off (scan 은 수동) |
| `AUTHORING_DEV_SEED_ENABLED` | true | dev-seed 자동 적재 |
| `ENV` | (미설정) | dev/stg/prd 면 부트 차단 |

## 주의 / 한계

- **탐지는 YOLOX 단일 백엔드** — 동봉 ONNX 가중치(`yolox_s.onnx`)를 ONNX Runtime 으로 로드하므로 탐지용 모델 다운로드가 없다(완전 오프라인). 가중치 부재 시 자동 mock fallback(기동 무중단). SAM2(Meta)만 최초 1회 HF 다운로드가 필요하며 이후 캐시(`HF_HOME=/app/.hf-cache`)로 오프라인.
- **GPU 옵트인** — GPU 환경은 `AI_DEVICE=cuda` + override 없이 `docker-compose.yml`(runtime nvidia).
- **VLM 실추론 미지원** — 위탁·콜백 왕복은 목 서버(`klid-mock-server:9400`)로 실제 수행하되 응답 내용은 규격에 맞는 더미다(연동 경로 검증용이며 실 벤더 추론 결과가 아니다). 다만 `describe` 구간은 **대상 영상 실제 길이 전체를 덮도록** 생성되므로(위 §목 산출물의 육안 확인) 시계열 메타 패널에서 구간 수·시각이 영상과 맞아떨어진다.
- **local/dev 비식별·VLM·증강 = 목 서버 실 HTTP 연동** — `application-local.yml`·`application-dev.yml` 기본값이 `klid-mock-server:9400` 이다(http, ca-cert 불요, 계정 `authoring`). **목 서버가 없으면 비식별 단계가 실패('F')한다**(원본은 보존 — 삭제되지 않는다). 벤더 실서버로 옮길 때는 `KPST_DEID_BASE_URL`/`VLM_SERVICE_URL`/`AUGMENT_API_BASE_URL` 만 주입하고, HTTPS 로 바뀌면 `*_ALLOW_INSECURE_URL` 완화 플래그를 내린다. `DEIDENTIFY_MOCK_MODE=true`(내부 self-fill)는 **연동 검증을 무력화하므로 상시 사용 금지** — 순수 local 프로파일에서만 허용되며(비-local/`ENV`=dev·stg·prd 에서 true 면 `DeidentifyStep` 부트 차단), 오프라인 응급 우회 용도로만 쓴다. 운영(prd)은 `application.yml` 기본 + 배포 환경변수로 별도 설정한다.
