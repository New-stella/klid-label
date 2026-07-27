# mock-server — 외부 벤더 API 목(mock) 서버

우리 BE(Spring Boot)가 외부 벤더 서버의 실제 개발 완료 전에 **실연동 테스트**를 하기 위한
로컬/테스트 전용 목 서버다. 다음 외부 벤더 API를 흉내낸다.

- **KPST 비식별화** 솔루션 (프로젝트 생성 → 폴링 진행률 → 리포트/프레임 조회)
- **IntelliVIX Video VLM** 시계열 (verify/describe, 비동기 콜백)
- **생성형 AI(증강)** — 「생성형 AI API 연동명세서 v1.1」 정합 (`/api/genai/*`, 작업 접수 →
  단계별 Webhook → 결과 파일 실제 생성 → 상태/결과 조회/취소)

인증/DB 없이 인메모리 상태(`app.state`)만으로 동작한다.

---

## ⚠ 보안 경고 (반드시 숙지)

**이 서버는 로컬/테스트 전용이다. 운영/외부망에 절대 노출하지 말 것.**

- **인증이 없다.** 모든 엔드포인트가 무인증으로 열려 있다.
- **SSRF 표면 (CWE-918):** VLM `verify`/`describe`는 요청자가 지정한 임의 `callback_url`로
  서버측 outbound POST를 발사한다. 이는 벤더 규격 동작(비동기 콜백)이라 목 서버에서 재현하지만,
  임의 URL로의 서버측 요청은 SSRF 벡터다. **신뢰된 네트워크에서만 기동**하고, 공개망/운영에
  노출하면 안 된다. (VLM은 별도 allowlist 없이 문서/코드 주석 경고로 갈음 —
  `app/services/vlm_sim.py` 상단 경고 주석 참조. 생성형 AI(`/api/genai/*`)는
  `MOCK_GENAI_CALLBACK_ALLOW_HOSTS` **`host:port`** allowlist + 스킴 + (선택) 경로 접두사 +
  자기참조 차단으로 막는다.)
- **요청 본문 크기 상한 (CWE-400):** `/api/genai/*` 는 `MOCK_GENAI_MAX_BODY_BYTES`(기본 1MiB)로
  제한한다. KPST(`/project` 등)·VLM 경로는 **아직 무제한**이므로 실사용 시 리버스 프록시나
  uvicorn 설정으로 크기 제한을 두는 것을 권장한다.

---

## 실행법

### 1) 자체 가상환경 (권장)

```bash
cd mock-server
python -m venv .venv
.venv/bin/pip install -r requirements.txt
```

### 2) 기존 ai-server 가상환경 재사용 (의존성 동일 계열)

레포의 `ai-server/.venv`를 그대로 써도 된다(FastAPI/uvicorn/pytest 등 동일 계열).

```bash
# 예: 테스트 실행
cd mock-server
../ai-server/.venv/bin/python -m pytest
```

### 기동

```bash
cd mock-server
uvicorn app.main:app --host 0.0.0.0 --port 9400
# (자체 venv면: .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 9400)
```

포트 **9400**을 사용한다(ai-server 9300과 충돌 회피). 환경변수는 `.env.example` 참조.

### 헬스체크

```bash
curl http://localhost:9400/health
# {"status":"ok"}
```

### 테스트

```bash
cd mock-server
../ai-server/.venv/bin/python -m pytest        # 또는 자체 venv 의 pytest
```

---

## 엔드포인트 목록

### KPST 비식별화 (루트 경로 규격 — prefix 없음)

| 메서드 | 경로 | 설명 |
|:------:|------|------|
| GET  | `/` | 연결 확인(`Connect` 평문 반환) |
| POST | `/project` | 비식별 프로젝트 생성(작업 접수) |
| POST | `/delete_project_name` | 프로젝트명으로 삭제 |
| POST | `/delete_project_id` | 프로젝트 ID로 삭제 |
| GET  | `/retrieve_progress` | 진행률 조회(시간경과에 따라 상승) |
| GET  | `/retrieve_report` | 비식별 결과 리포트 조회 |
| GET  | `/manual_deid_info` | 수동 비식별 정보 조회 |
| GET  | `/manual_deid_info/project_id` | 프로젝트 ID 기준 수동 비식별 정보 |
| GET  | `/manual_deid_info/project_name` | 프로젝트명 기준 수동 비식별 정보 |
| GET  | `/dataset_frames` | 비식별 데이터셋 프레임 목록 |
| GET  | `/retrieve_job_logs` | 작업 로그 조회 |

### IntelliVIX Video VLM (`/v1/videovlm/*`)

| 메서드 | 경로 | 설명 |
|:------:|------|------|
| POST | `/v1/videovlm/verify` | 이벤트 검증 접수 → 즉시 `accepted`, 이후 콜백 발사 |
| POST | `/v1/videovlm/describe` | 상황 묘사 접수 → 즉시 `accepted`, 이후 구간별 결과 콜백 |
| GET  | `/v1/videovlm/status` | VLM 목 상태 확인 |

### 생성형 AI(증강) — 「생성형 AI API 연동명세서 v1.1」 (`/api/genai/*`)

목이 **제공**하는 4종:

| 메서드 | 경로 | 명세서 | 설명 |
|:------:|------|:------:|------|
| POST | `/api/genai/jobs` | §4.1 | 작업 요청 → **202** `{request_id, job_id, status:"RECEIVED", received_at}` |
| GET  | `/api/genai/jobs/{job_id}` | §4.4 | 상태 조회(progress/current_step/error 포함) |
| GET  | `/api/genai/jobs/{job_id}/results` | §4.5 | 결과 조회 — **SUCCEEDED 에서만**(그 외 409 STATE_CONFLICT) |
| POST | `/api/genai/jobs/{job_id}/cancel` | §4.6 | 취소 — **RECEIVED·RUNNING 에서만**(종결 상태는 409). `requested_by` 필수 |

목이 **발신**하는 2종:

| 방향 | 대상 | 명세서 | 설명 |
|:----:|------|:------:|------|
| POST | 요청 바디의 `callback_url` | §4.2 | 진행·결과 Webhook — **단계마다 자동 발사** |
| POST | `{MOCK_GENAI_STATUS_SYNC_URL}/api/genai/jobs/{job_id}/status-sync` | §4.3 | 상태 동기화 — **수동 트리거만**(아래 목 전용 EP) |

목 전용 보조 EP (**명세서에 없는 목 서버 전용 기능**):

| 메서드 | 경로 | 설명 |
|:------:|------|------|
| GET  | `/api/genai/_mock/jobs` | 작업 목록/상태 + 진행 중 백그라운드 태스크 수 |
| POST | `/api/genai/_mock/reset` | 진행 태스크 취소 + 작업 저장소 초기화 |
| POST | `/api/genai/_mock/jobs/{job_id}/status-sync` | ③ 상태 동기화 수동 발신 |

> **구 placeholder 경로 제거:** 기존 `POST /v1/augment` · `GET /v1/augment/status`(501 스텁)는
> 명세서 경로로 **대체·삭제**되었다(잔존시키지 않음).

### 공통

| 메서드 | 경로 | 설명 |
|:------:|------|------|
| GET | `/health` | 헬스체크(`{"status":"ok"}`) |

---

## 우리 BE 연동 설정 예시

아래는 `backend/src/main/resources/application.yml` / `application-local.yml`에서 grep으로 확인한
**실제 설정 키**다. 목 서버를 가리키도록 base-url/enabled만 바꾸면 우리 BE가 목 서버를 호출한다.

### KPST 비식별화

```yaml
# application.yml (기본), application-local.yml (local override)
kpst:
  deid:
    enabled: true                          # env: KPST_DEID_ENABLED
    base-url: http://localhost:9400        # env: KPST_DEID_BASE_URL (목 서버로 지정)
```

> local 프로파일은 기본이 **mock 비식별**(`authoring.integration.deidentify.mock-mode=true`,
> env `DEIDENTIFY_MOCK_MODE`)이라 KPST를 호출하지 않는다. 목 서버로 실제 KPST 폴링 흐름을
> 태우려면 `DEIDENTIFY_MOCK_MODE=false` + `KPST_DEID_ENABLED=true` +
> `KPST_DEID_BASE_URL=http://localhost:9400`으로 기동한다. 목 서버는 http 평문이라 KPST용
> `kpst.deid.ca-cert-path`(env `KPST_DEID_CA_CERT_PATH`)는 불필요하다(https일 때만 요구됨).

### IntelliVIX Video VLM

```yaml
vlm:
  client:
    enabled: true                          # env: VLM_CLIENT_ENABLED
    url: http://localhost:9400             # env: VLM_SERVICE_URL (키 이름은 base-url 아닌 url)
    token: ""                              # env: VLM_SERVICE_TOKEN (목 서버는 무인증이라 빈 값 가능)
```

> **주의:** VLM은 KPST와 달리 설정 키가 `base-url`이 아니라 **`vlm.client.url`**이다(실제 확인값).
> 기본값이 이미 `http://localhost:9400` / `enabled=false`이므로, `VLM_CLIENT_ENABLED=true`만 켜면
> 목 서버로 호출이 나간다.

### (참고) ai-server

목 서버 범위는 아니지만 인접 설정으로, ai-server는
`authoring.integration.ai-server.base-url`(env `AI_SERVER_URL`, 기본 `http://localhost:9300`)로
지정한다. 목 서버(9400)와 포트가 다르다.

---

## 콜백 흐름 (VLM)

VLM `verify`/`describe`는 **즉시** `{"request_id","status":"accepted"}`로 접수하고, 지연
(`MOCK_CALLBACK_DELAY_SECONDS`, 기본 2초) 후 요청의 `callback_url`로 결과를 POST한다.

우리 BE의 VLM 콜백 수신 엔드포인트는 **`POST /v1/vlm/callback`**이다
(`VlmResultController`, `@RequestMapping("/v1/vlm")` + `@PostMapping("/callback")`).
따라서 목 서버 호출 시 `callback_url`을 우리 BE가 접근 가능한 주소로 넘겨야 한다. 예:

```
callback_url = http://<BE-host>:8080/v1/vlm/callback
```

목 서버는 이 URL로 서버측에서 POST를 발사하므로(SSRF 주의), 목 서버가 실제로 도달할 수 있는
주소여야 한다. 콜백 대상이 다운이어도 목 서버 동기 응답/안정성에는 영향이 없다(콜백 실패 격리).

> **증강(생성형 AI) 콜백은 VLM과 별개다.** 아래 "생성형 AI(증강) 목" 절을 참조. 목은 명세서
> §4.2 규격 그대로 발신하며, 우리 BE의 기존 수신부(`POST /v1/aug/callback`, HMAC 서명 필수 +
> `data_aug_sn`/`aug_type_cd` 등 **다른 페이로드**)와는 계약이 다르다. 목이 우리 구 계약에
> 맞춰주는 관대 파싱은 **의도적으로 넣지 않았다**(갭 은폐 금지). BE 개조 시점에 정합시킨다.

### 실패 콜백 트리거 (BE 실패 처리 테스트용)

목 서버는 결정적(deterministic) 실패를 재현하기 위해 아래 입력이면 동기 응답은 규격대로
`accepted`를 유지하되 콜백을 `status=failed`로 발사한다.

- `request_id`가 `"fail"`(대소문자 무시)로 시작하거나,
- `media.path`에 `"fail"`이 포함된 경우.

우리 BE `VlmResultService`의 실패 콜백 처리 통합 테스트에 사용한다.

---

## 상태ful 동작 (KPST)

KPST 목은 프로젝트 생성(`POST /project`) 후 `retrieve_progress`의 진행률이 **시간 경과에 따라
상승**한다. 경과 시간에 배속을 곱해 진행률을 계산하므로, `MOCK_SIM_SPEED_FACTOR`를 키우면
100%에 더 빨리 도달한다(테스트 시간 단축). 인메모리 상태라 서버 재기동 시 초기화된다.

---

## 더미 비식별 출력 파일 생성 (e2e 파이프라인용)

기본 상태ful 시뮬레이션은 파일 I/O가 없다. 하지만 우리 BE의 후속 파이프라인(마킹·프레임추출)은
KPST가 산출한 **비식별 결과 파일**을 실제로 읽어야 진행된다. 이를 위해 목 서버는 `POST /project`
처리 시 **각 데이터셋마다 `{export_path}/{마스킹명}`에 더미 파일을 생성**한다.

- **마스킹 파일명 규칙:** `{원본stem}_{yyyyMMddHHmm}_mask{확장자}`.
  예: `a.mp4` → `a_202607211530_mask.mp4`. 확장자가 없으면 `_mask`만 붙는다(폴더명 등).
  타임스탬프는 **프로젝트 생성 시 1회** 계산(`datetime.now`, `%Y%m%d%H%M`)해 데이터셋에 고정 저장하고,
  이후 진행률 조회마다 재계산하지 않는다(불일치 방지).
- **단일 소스(Critical):** 데이터셋명 = `retrieve_progress` 응답의 `dsStatus[].fileName` = **실제 생성
  파일명(마스킹명)** 이 모두 동일하다. BE는 완료 폴링에서 그 `fileName`으로 `{export_path}/{fileName}`
  회수 경로를 산출하므로(no-copy) 셋이 하나의 소스여야 회수가 성립한다.
- **내용:** `{input_path}/{원본basename}`에 원본이 있으면 **그 원본을 마스킹명으로 복사**하고, 없으면
  **placeholder 바이트**(`MOCK_DEIDENTIFIED\n`)로 비어있지 않게 만든다.
- **무결성 통과 조건:** BE의 `isUsableDeidFile`은 **파일 존재 + 크기 > 0바이트**만 본다(유효 mp4 불필요).
  이 더미 파일은 **실제 비식별 처리 결과가 아니라**, BE 무결성 통과용 **플레이스홀더 또는 원본 복사본**이다.
- **이미지 폴더 모드(`is_img=1`):** 폴더 basename에 마스킹 규칙을 적용한 이름(`{folderbase}_{ts}_mask`)으로
  placeholder 1개만 둔다(폴더 재귀 복사는 하지 않음 — 영상 모드가 핵심). 이 이름도 progress `fileName`과 일치.
- **덮어쓰기 금지(no-overwrite):** target이 이미 존재하면 덮어쓰지 않고 **skip + 로그**(멱등 재실행 안전).
- **견고성:** 파일 쓰기/복사 실패(권한·디스크·경로 문제 등)는 예외를 삼켜 **로그만** 남기며,
  `POST /project` 응답(200/success)·서버 안정성에 영향을 주지 않는다.

### 설정

| 환경변수 | 기본값 | 설명 |
|----------|:------:|------|
| `MOCK_WRITE_OUTPUT_FILES` | `true` | 더미 출력 파일 생성 on/off. `false`면 파일을 만들지 않는다(순수 상태 시뮬레이션). |
| `MOCK_OUTPUT_BASE` | (빈값) | **쓰기 허용 루트.** 설정 시 `export_path`가 resolve 후 이 base 하위일 때만 파일을 쓴다(경로순회/임의 절대경로 쓰기 차단). **미설정(`''`)이면 fail-closed — 어떤 파일도 생성하지 않는다.** |

> **실제 파일 생성 조건:** `MOCK_WRITE_OUTPUT_FILES=true` **그리고** `MOCK_OUTPUT_BASE` 설정, **둘 다** 참일
> 때만. e2e 시 BE의 `STORAGE_DEIDENTIFIED_PATH`와 동일 루트를 `MOCK_OUTPUT_BASE`로 지정한다. 미설정이면
> 목이 아무 파일도 만들지 않으므로(fail-closed) 파이프라인이 안 열린다 — 반드시 설정할 것.

### 경로 순회(CWE-22) 방어 + 임의경로 쓰기 차단

- **basename 정화(마스킹명 조립 전):** `os.path.basename`으로 plain basename만 취하고, 빈값/`.`/`..`/구분자면
  그 항목은 **데이터셋·파일 모두 스킵 + 로그**. 슬래시/`..`가 섞여도 마스킹명은 항상 basename 기준으로 안전.
- **fail-closed:** `MOCK_OUTPUT_BASE` 미설정이면 임의 절대경로 쓰기를 막기 위해 **아무 파일도 쓰지 않는다**.
- **export_path 정규화:** `Path(export_path).resolve()` 후 `MOCK_OUTPUT_BASE` 하위인지 검증. 아니면 쓰기 스킵.
- **심층 방어:** 최종 쓰기 경로가 정규화된 export_path 하위인지 한 번 더 확인한다.
- **no-overwrite:** 파일 생성은 O_EXCL 원자 연산이라 기존 파일을 덮어쓰지 않는다.
- **자원 상한:** `files[]`는 최대 1000개(초과 시 거부). 복사 소스(input_path)도 정화 후 존재 확인 — 입력 밖 탈출 차단.

### Docker 볼륨 마운트 안내 (Critical)

e2e로 우리 BE가 이 더미 파일을 읽으려면, 목 서버가 파일을 쓰는 경로와 BE가 읽는 경로가 **동일한
실제 위치**를 가리켜야 한다. 목 서버를 Docker로 띄운다면 BE의 `STORAGE_DEIDENTIFIED_PATH`(출력)와
원본 `input` 경로를 **목 컨테이너에도 동일 경로로 마운트**해야 한다.

```bash
# 예: BE가 /nas-storage 를 비식별 저장 경로(STORAGE_DEIDENTIFIED_PATH)로 쓰고,
#     원본 input 이 /nas-storage/videos 하위에 있다고 가정
docker run --rm -p 9400:9400 \
  -e MOCK_WRITE_OUTPUT_FILES=true \
  -e MOCK_OUTPUT_BASE=/nas-storage \
  -v /nas-storage:/nas-storage \
  mock-server
```

- 마운트를 빠뜨리면 목이 **컨테이너 안에만** 쓰고 BE는 못 읽어 파이프라인이 막힌다.
- `MOCK_OUTPUT_BASE=/nas-storage`로 두면 목이 그 밖(예: 컨테이너 로컬)으로 쓰는 것을 차단해
  마운트 누락을 조기에 드러낼 수 있다.

---

## 생성형 AI(증강) 목 — 「생성형 AI API 연동명세서 v1.1」

외부 **생성형 AI 시스템**을 연기한다. 계약은 관제가 확정한 명세서 v1.1(LogiCraft EXTSYS-002 /
INT-001·019·020·029·030·031)이며, 경로 prefix는 `/api/genai`, 본문은 JSON이다.

### 상태머신 (§3.2)

```
RECEIVED ──(단계 지연)──> RUNNING ──> SUCCEEDED
                            │           
                            ├────────> FAILED
                            └────────> CANCELED   (RECEIVED/RUNNING 에서만 취소 가능)
```

- 종결 상태(`SUCCEEDED`/`FAILED`/`CANCELED`)에서는 **어떤 상태 변경도 거부**한다 → `409 STATE_CONFLICT`.
- 취소가 먼저 성립하면 진행 중이던 백그라운드 작업은 다음 전이 시점에 스스로 중단하며,
  **취소 상태를 SUCCEEDED로 덮어쓰지 않는다**(상태 전이는 락으로 직렬화 — CWE-362).
- 결과 조회(§4.5)는 `SUCCEEDED` 에서만 허용한다.

### 진행 단계 + Webhook (②, §4.2)

`POST /api/genai/jobs` 는 **202로 즉시 접수**하고 백그라운드로 다음 단계를 진행하며,
각 전이마다 요청 바디의 `callback_url` 로 Webhook을 POST한다.

| 순서 | status | progress | current_step | 비고 |
|:----:|--------|:--------:|--------------|------|
| 1 | RUNNING | 10 | `PREPROCESS` | |
| 2 | RUNNING | 50 | `INFERENCE` | |
| 3 | RUNNING | 90 | `POSTPROCESS` | 이 다음에 결과 파일 생성 |
| 4 | SUCCEEDED | 100 | `COMPLETED` | `results[]` 포함 |

- 단계 사이 지연은 `MOCK_GENAI_STEP_DELAY_SEC`(기본 2초, 테스트는 0).
- `callback_url` 은 선택 항목 — 없으면 Webhook을 발사하지 않고 작업만 진행한다.
- Webhook 수신측이 401/404를 주거나 다운되어도 **목은 죽지 않고 작업을 계속 진행**한다.
  전송은 `MOCK_GENAI_WEBHOOK_MAX_ATTEMPTS`(기본 2회) 안에서만 재시도하며 **무한 재시도하지 않는다**.
- **취소 시에는 Webhook을 발사하지 않는다**(취소는 호출자가 이미 결과를 알고 있는 동기 응답).

### 상태 동기화 (③, §4.3) — 수동 트리거만

명세서상 status-sync 경로는 **수신측(우리)이 제공**하는 것이라, 목이 주소를 유추하면 안 된다.
따라서 자동 발신은 하지 않고, 대상 base URL을 `MOCK_GENAI_STATUS_SYNC_URL` 로 명시했을 때만
목 전용 EP(`POST /api/genai/_mock/jobs/{job_id}/status-sync`)로 수동 발신한다.
실제 발신 주소는 `{base}/api/genai/jobs/{job_id}/status-sync` 이며, 미설정이면 `{"sent": false}`.

### 결과 파일은 **실제로 생성**된다 (§5.3)

파일 본문은 API로 주고받지 않는다(경로만 교환). 목은 공유 스토리지에 산출물을 직접 만든다.

- 출력 위치: **`{MOCK_GENAI_OUTPUT_BASE}/genai/{job_id}/{sequence:03d}_{원본stem}_genai{ext}`**
- 내용: 입력 파일이 있으면 **그 파일을 복사**, 없으면(T2I/T2V) placeholder 바이트(`MOCK_GENAI_GENERATED\n`).
- `media_type`: `T2I`·`I2I` → `IMAGE`, `T2V`·`I2V` → `VIDEO`. 확장자는 유형에 맞으면 원본 유지, 아니면 기본값(`.png`/`.mp4`).
- 각 결과에 `generated_data_id`(uuid) · `checksum`(SHA-256) · `media_metadata`(mime_type/size_bytes) 포함.
- **fail-closed:** `MOCK_GENAI_OUTPUT_BASE` 미설정이면 파일을 만들지 않고 작업을 `FAILED(RESULT_SAVE_FAILED)`
  로 종결한다(임의 절대경로 쓰기 차단). e2e에서는 BE와 동일 루트를 반드시 지정할 것.
- 기존 파일은 O_EXCL로 **덮어쓰지 않는다**(멱등 재실행 안전).

### FAILED 재현 트리거 (채택 방식: 요청 필드 기반)

**`request_id` 가 `"fail"`(대소문자 무시)로 시작하면** 접수는 규격대로 202로 성공하고,
진행 중 `FAILED(MODEL_EXECUTION_FAILED)` 로 종결하며 Webhook에 `error_code`·`error_message`를 싣는다.
(VLM 목의 `is_failure_trigger` 관례와 동일. 별도 제어 EP를 만들지 않았다.)
입력 파일이 실제로 존재하지 않는 경우도 같은 코드로 FAILED 처리된다.

### 멱등성 — `Idempotency-Key`(선택)

동일 키로 재요청하면 **기존 job을 그대로 반환**하며(중복 job 생성 없음) 백그라운드 진행도
재시작하지 않는다. 헤더가 없으면 매번 새 job이다. 등록(check-then-act)은 락으로 직렬화한다.

### 오류 코드 (§3.3)

응답 본문은 `{"code": ..., "error_code": ..., "message": ...}` — `code`가 명세서 필드이고
`error_code`는 목 서버 공통 규격 호환용으로 **같은 값**을 함께 싣는다.

| HTTP | code | 발생 조건 |
|:----:|------|-----------|
| 400 | `REQUIRED_FIELD_MISSING` | 필수 필드 누락, `I2I`/`I2V` 인데 `input_files` 없음, cancel `requested_by` 누락 |
| 400 | `UNSUPPORTED_EVENT_TYPE` | `MOCK_GENAI_EVENT_TYPES` 설정 시 목록 밖 `evnt_type` |
| 400 | `INVALID_METADATA` | `prompt` 가 객체(JSON object)가 아님, `prompt` 크기 > `MOCK_GENAI_MAX_PROMPT_BYTES` |
| 400 | `INVALID_PARAMETER` | enum/길이 위반, `sequence` 중복, 허용 밖 `file_path`(경로 탈출/상대경로/base 미설정), 차단된 `callback_url`(호스트·포트·경로·자기참조), JSON 파싱 실패(심층 중첩 포함) |
| 404 | `JOB_NOT_FOUND` | 없는 `job_id` |
| 404 | `RESULT_NOT_FOUND` | SUCCEEDED 인데 결과 항목이 비어 있음 |
| 409 | `STATE_CONFLICT` | SUCCEEDED 아닌 상태의 결과 조회, 종결 상태 취소 |
| 413 | `GA-MEDIA-001` | 입력 파일 크기 > `MOCK_GENAI_MAX_INPUT_BYTES`, 요청 본문 크기 > `MOCK_GENAI_MAX_BODY_BYTES` |
| 500 | `MODEL_EXECUTION_FAILED` | 실패 트리거, 입력 파일 부재 (FAILED 상태/Webhook으로 전달) |
| 500 | `RESULT_SAVE_FAILED` | 출력 base 미설정/쓰기 실패 (FAILED 상태/Webhook으로 전달) |

**의도적으로 구현하지 않은 것**

- `401 UNAUTHENTICATED` / `403 FORBIDDEN` — **인증 전체가 이번 스코프 제외**(차후 개발).
  목 서버는 무인증이며 `x-access-token` 등 인증 헤더를 요구·검증하지 않는다.
- `REQUEST_NOT_FOUND` — request_id 기준 조회 EP가 명세서에 없어 발생 지점이 없다.
- `CALLBACK_FAILED` / `INTERNAL_SERVER_ERROR` — 전자는 Webhook 전송 실패라 응답으로 돌려줄
  대상이 없고(로그로만 남김), 후자는 목 공통 핸들러가 `INTERNAL_ERROR`로 처리한다.

### 환경변수

| 환경변수 | 기본값 | 설명 |
|----------|:------:|------|
| `MOCK_GENAI_STEP_DELAY_SEC` | `2.0` | 단계 간 지연(초). 0이면 즉시 진행(테스트용) |
| `MOCK_GENAI_OUTPUT_BASE` | (빈값) | **결과 파일 쓰기 허용 루트.** 미설정 시 fail-closed → `FAILED(RESULT_SAVE_FAILED)` |
| `MOCK_GENAI_INPUT_BASE` | (빈값) | `input_files[].file_path` 허용 루트. 미설정 시 `OUTPUT_BASE` 사용. **둘 다 비면 모든 입력 경로를 400 으로 거절**(fail-closed) |
| `MOCK_GENAI_CALLBACK_ALLOW_HOSTS` | `localhost:8080,127.0.0.1:8080,[::1]:8080,host.docker.internal:8080,klid-backend:8080,backend:8080` | Webhook/status-sync 대상 allowlist. 항목은 `host:port`(그 포트만) 또는 `host`(모든 포트, 하위호환). IPv6 는 `[::1]:8080`. 빈값=전부 차단, `*`=검사 생략(로컬 전용) |
| `MOCK_GENAI_CALLBACK_PATH_PREFIXES` | (빈값) | 콜백 대상 **경로 접두사** allowlist. 빈값이면 경로 제한 없음(예: `/api/genai/`) |
| `MOCK_GENAI_SELF_HOST_ALIASES` | (빈값) | 목 자신을 가리키는 추가 호스트 별칭. 루프백/바인드 호스트 + `MOCK_PORT` 조합은 기본으로 차단됨 |
| `MOCK_GENAI_STATUS_SYNC_URL` | (빈값) | ③ status-sync 대상 **base URL**. 미설정 시 비활성 |
| `MOCK_GENAI_EVENT_TYPES` | (빈값) | 허용 `evnt_type` 목록. 빈값이면 검증 안 함 |
| `MOCK_GENAI_MAX_INPUT_BYTES` | `5368709120` | 입력 파일 1건 크기 상한(접수 시 + 처리 시 fd 기준 재검증, 초과 시 413/FAILED) |
| `MOCK_GENAI_MAX_BODY_BYTES` | `1048576` | 요청 본문 크기 상한(초과 시 413 `GA-MEDIA-001`) |
| `MOCK_GENAI_MAX_PROMPT_BYTES` | `65536` | `prompt` 직렬화 크기 상한(초과 시 400 `INVALID_METADATA`) |
| `MOCK_GENAI_MAX_JOBS` | `1000` | 인메모리 잡 보관 상한. 초과 시 오래된 작업부터 만료(FIFO) |
| `MOCK_GENAI_WEBHOOK_MAX_ATTEMPTS` | `2` | Webhook 전송 시도 횟수 상한(무한 재시도 금지) |
| `MOCK_GENAI_WEBHOOK_RETRY_DELAY_SEC` | `0.5` | Webhook 재시도 간 지연(초) |

> ⚠ `MOCK_GENAI_CALLBACK_ALLOW_HOSTS` 기본값은 **포트 8080(BE 컨테이너 포트) 한정**이다.
> BE를 다른 포트로 띄우면 `host:port` 를 그 포트로 바꿔야 콜백이 접수된다(포트를 생략하면
> 그 호스트의 모든 포트가 열리므로 권장하지 않는다).

### 보안 가드

- **경로 탈출(CWE-22):** `file_path`는 **절대경로 + 입력 base 하위**만 접수(아니면 400).
  **입력 base 가 설정되지 않으면 전부 거절**한다(fail-closed — 임의 절대경로가 파일 존재·크기
  오라클이 되는 것을 막는다). 출력 경로는 항상 `{output_base}/genai/{job_id}/` 하위이며
  파일명은 basename 기준으로 조립한다.
- **TOCTOU·심볼릭링크(CWE-367/59):** 접수 시 통과한 경로라도 **처리 시점에 다시 base 소속을
  검증**하고, 입력은 `O_RDONLY|O_NOFOLLOW` 로 열어 **fd 기준 `fstat`** 으로 정규파일 여부와
  크기를 재확인한다. 접수 후 입력을 base 밖 심볼릭링크로 바꿔치기해도 유출되지 않고
  `FAILED(MODEL_EXECUTION_FAILED)` 로 끝난다. 복사 바이트 수에도 상한이 걸린다(CWE-400).
  → 입력 base 와 출력 base 를 **같은 디렉터리로 두지 말 것**(쓰기 권한이 곧 읽기 권한이 된다).
- **SSRF(CWE-918):** `callback_url`·status-sync 대상은 스킴 `http|https` + **`host:port`**
  allowlist + (선택) 경로 접두사 통과분만. **목 서버 자신**(루프백/바인드 호스트 + `MOCK_PORT`)
  을 가리키는 대상은 allowlist 에 있어도 거부한다(자기 SSRF 로 `_mock/*` 상태 파괴 방지).
  차단 시 접수 자체를 400으로 거절해 오설정을 조기에 드러낸다(allowlist 비면 fail-closed).
- **자원 고갈(CWE-770/400):** 요청 본문은 `Content-Length` 선검사 + 스트리밍 누적 검사로
  상한 안에서만 읽고(초과 413), `prompt` 직렬화 크기와 인메모리 잡 보관 수(FIFO 만료)에도
  상한이 있다.
- **예외 처리(CWE-755):** 심층 중첩 JSON 이 유발하는 `RecursionError` 등 파싱 실패는 모두
  400 `INVALID_PARAMETER` 로 매핑한다(500 누출 금지).
- **고아 산출물 방지:** 취소 등으로 종결이 확정되면 이미 만든 결과 파일을 정리한다.
- **입력 검증(CWE-20/915):** pydantic 모델로 타입·필수·길이(`request_id` 64 / `file_path` 500 등)·
  enum을 강제하고 **미선언 필드는 무시**한다(Mass Assignment 차단).
- **로그 인젝션(CWE-117):** job_id·경로·URL 등 사용자 입력은 `sanitize_for_log` 경유 후 로깅.
- **정보 노출(CWE-209):** 오류 메시지는 고정 문구이며 스택트레이스/내부 경로를 담지 않는다.
- **동시성(CWE-362):** 상태 전이·멱등키 등록의 check-then-act를 단일 락으로 직렬화.
- **태스크 누수:** 진행 태스크 참조를 레지스트리에 보관하고 lifespan shutdown에서 취소·정리한다.

### 사용 예시

```bash
# 1) 작업 요청 (I2V)
curl -i -X POST http://localhost:9400/api/genai/jobs \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: req-0001' \
  -d '{"request_id":"req-0001","request_channel":"AUTHORING","evnt_type":"FIRE",
       "operation_type":"AUGMENT","generation_mode":"I2V",
       "input_files":[{"sequence":1,"file_path":"/nas-storage/videos/10/deidentified.mp4"}],
       "prompt":{"season":"winter","weather":"snow"},
       "callback_url":"http://klid-backend:8080/api/genai/jobs/x/webhook"}'

# 2) 상태 / 결과 조회
curl http://localhost:9400/api/genai/jobs/{job_id}
curl http://localhost:9400/api/genai/jobs/{job_id}/results

# 3) 취소
curl -X POST http://localhost:9400/api/genai/jobs/{job_id}/cancel \
  -H 'Content-Type: application/json' -d '{"reason":"테스트","requested_by":"w1"}'

# 4) [목 전용] 상태 확인 / 초기화 / status-sync 수동 발신
curl http://localhost:9400/api/genai/_mock/jobs
curl -X POST http://localhost:9400/api/genai/_mock/reset
curl -X POST http://localhost:9400/api/genai/_mock/jobs/{job_id}/status-sync
```

> **참고 — 현재 우리 BE는 이 목을 호출하지 않는다.** 증강 클라이언트가 전 환경 기본
> `NoopExternalAugmentClient` 이기 때문이며 정상이다. 이 목은 규격서 도착·BE 개조 시점을
> 대비해 **먼저 세워둔 것**이다.

---

## 관련 파일

- `app/main.py` — 앱/미들웨어/라우터 등록, `/health`, shutdown 시 백그라운드 태스크 정리
- `app/routers/deid.py` — KPST 비식별 11개 엔드포인트
- `app/routers/vlm.py` — IntelliVIX VLM verify/describe/status
- `app/routers/augment.py` — 생성형 AI(증강) `/api/genai/*` + 목 전용 `_mock` EP
- `app/schemas/genai.py` — 명세서 v1.1 요청/응답 스키마 + 상태·오류코드 enum
- `app/services/genai_sim.py` — 단계 진행 시뮬레이션 · 결과 파일 생성 · Webhook 발신 · 보안 가드
- `app/services/vlm_sim.py` — VLM 콜백 페이로드 생성 + 비동기 발사(SSRF 경고 주석)
- `app/state.py` — 인메모리 상태(KPST 프로젝트 + 생성형 AI 작업 저장소)
- `app/config.py` — `MOCK_*` 환경변수 설정
- `tests/` — pytest (deid/vlm/genai/health/state + `test_genai_security_hardening.py` 보안 회귀)
