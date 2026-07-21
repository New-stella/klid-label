# mock-server — 외부 벤더 API 목(mock) 서버

우리 BE(Spring Boot)가 외부 벤더 서버의 실제 개발 완료 전에 **실연동 테스트**를 하기 위한
로컬/테스트 전용 목 서버다. 다음 외부 벤더 API를 흉내낸다.

- **KPST 비식별화** 솔루션 (프로젝트 생성 → 폴링 진행률 → 리포트/프레임 조회)
- **IntelliVIX Video VLM** 시계열 (verify/describe, 비동기 콜백)
- **증강 AI** (WINTER/NIGHT/RAIN) — 향후 확장 placeholder (현재 미구현 스텁)

인증/DB 없이 인메모리 상태(`app.state`)만으로 동작한다.

---

## ⚠ 보안 경고 (반드시 숙지)

**이 서버는 로컬/테스트 전용이다. 운영/외부망에 절대 노출하지 말 것.**

- **인증이 없다.** 모든 엔드포인트가 무인증으로 열려 있다.
- **SSRF 표면 (CWE-918):** VLM `verify`/`describe`는 요청자가 지정한 임의 `callback_url`로
  서버측 outbound POST를 발사한다. 이는 벤더 규격 동작(비동기 콜백)이라 목 서버에서 재현하지만,
  임의 URL로의 서버측 요청은 SSRF 벡터다. **신뢰된 네트워크에서만 기동**하고, 공개망/운영에
  노출하면 안 된다. (별도 allowlist는 목 목적상 과하므로 문서/코드 주석 경고로 갈음 —
  `app/services/vlm_sim.py` 상단 경고 주석 참조.)
- **요청 본문 크기 상한 없음 (CWE-400):** 본문 크기 제한이 없다. 실사용 시 리버스 프록시나
  uvicorn 설정으로 크기 제한을 두는 것을 권장한다(로컬 테스트라 기본은 무제한).

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

### 증강 AI (`/v1/augment/*`) — 향후 확장 placeholder

| 메서드 | 경로 | 설명 |
|:------:|------|------|
| GET  | `/v1/augment/status` | `not_implemented` placeholder(등록 확인용) |
| POST | `/v1/augment` | 501 미구현 스텁(확장 지점만 확정, 로직 없음) |

> 증강 라우터는 향후 증강 AI 목을 얹는 확장 지점이며, VLM(verify/describe)과 동일한
> 비동기 콜백 패턴을 따를 예정이다. 완료 콜백은 CLAUDE.md "증강 = 새 영상(RAW_SN)" 규칙에 따라
> 원본을 `ORGNL_RAW_SN`으로 참조하는 **새 영상**을 생성하는 형태가 될 예정이다.

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

> 증강 콜백(향후)은 우리 BE의 **`POST /v1/aug/callback`**(`AugmentResultController`)로 갈 예정이나,
> 현재 증강 라우터는 501 스텁이라 콜백을 발사하지 않는다.

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

## 관련 파일

- `app/main.py` — 앱/미들웨어/라우터 등록, `/health`
- `app/routers/deid.py` — KPST 비식별 11개 엔드포인트
- `app/routers/vlm.py` — IntelliVIX VLM verify/describe/status
- `app/routers/augment.py` — 증강 AI 확장 placeholder(501 스텁)
- `app/services/vlm_sim.py` — VLM 콜백 페이로드 생성 + 비동기 발사(SSRF 경고 주석)
- `app/config.py` — `MOCK_*` 환경변수 설정
- `tests/` — pytest (deid/vlm/augment/health/state)
