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

## 관련 파일

- `app/main.py` — 앱/미들웨어/라우터 등록, `/health`
- `app/routers/deid.py` — KPST 비식별 11개 엔드포인트
- `app/routers/vlm.py` — IntelliVIX VLM verify/describe/status
- `app/routers/augment.py` — 증강 AI 확장 placeholder(501 스텁)
- `app/services/vlm_sim.py` — VLM 콜백 페이로드 생성 + 비동기 발사(SSRF 경고 주석)
- `app/config.py` — `MOCK_*` 환경변수 설정
- `tests/` — pytest (deid/vlm/augment/health/state)
