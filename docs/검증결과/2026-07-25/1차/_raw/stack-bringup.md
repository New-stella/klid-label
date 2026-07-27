# 로컬 풀스택 기동 결과 (목업서버 연동) — 2026-07-25 1차

## 기동 결과

컨테이너가 이미 기동돼 있었음(klid-backend 32h, klid-postgres 35h, klid-frontend 42h, klid-ai-server 3d, klid-mock-server 3d) — 재기동 대신 헬스 실측으로 검증. `docker inspect` 라벨(`com.docker.compose.project.config_files`)로 실제 사용된 compose 조합이 `docker-compose.yml + docker-compose.local.yml`임을 확인(요구된 mock-server 배선 override 포함).

| 서비스 | 포트 | 상태 | 헬스 근거 | 비고 |
|--------|-----:|:----:|-----------|------|
| klid-postgres | 5432 | OK | `docker inspect` Health=healthy, pg_isready | named volume(`klid-label_klid-pgdata`) — local.yml 오버라이드 적용 확인 |
| klid-backend | 18081→8080 | OK | `GET /api/actuator/health/liveness` → `{"status":"UP"}` (200), `/readiness` → UP(200), docker Health=healthy | `/api/actuator/health`(전체)는 503 DOWN — liveness/readiness 는 UP이라 앱 자체는 정상, 상세 컴포넌트는 `show-details=when-authorized`라 익명 조회로는 원인 미확인(별도 조사 필요, 이번 스코프 아님) |
| klid-ai-server | 19300→9300 | OK | `GET /health` → `{"status":"ok"}` (200), docker Health=healthy | `AI_MOCK_MODE=false`+`AI_DEVICE=cpu` 실추론(YOLOX/SAM2 CPU) — mock 아님(내부 추론 사이드카라 mock-server 대상 아님, 설계상 정상) |
| klid-mock-server | 9400 | OK | `GET /health` → `{"status":"ok"}` (200), `GET /docs` → 200 | KPST 11EP + VLM 3EP + augment 2EP 노출 확인(아래 목록) |
| klid-frontend | 13000→5174 | OK | `GET /` → 200, docker Health=healthy | Vite dev 서버 |
| DB 스키마 | - | ★불일치 | `\dn` 결과 `klid_at` 스키마 **없음** — 실제 테이블은 `public` 스키마(78개 public 테이블, `flyway_schema_history`도 public) | CLAUDE.md는 `klid_at` 운영을 명시하나 실배포는 `public`. 기존 알려진 이력(memory `cudo246-deploy-layout`)과 동일 — 이번 세션 신규 발견 아님, 참고로 재기재 |

Flyway: `public.flyway_schema_history` 127행, `max(version)=99`, `success=false` 0건 — 마이그레이션 전량 성공.

## 사용한 기동 명령

기존 컨테이너가 3일~42시간째 정상 기동 중이라 재기동하지 않았음. 재현 시 사용할 명령(compose 파일 헤더 주석 그대로):

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

검증에 실제 사용한 명령들:

```bash
docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
docker inspect klid-backend --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}'
docker inspect klid-postgres --format '{{json .Mounts}}'
curl -s http://localhost:18081/api/actuator/health/liveness
curl -s http://localhost:9400/health
curl -s http://localhost:9400/openapi.json
curl -s http://localhost:19300/health
curl -s -o /dev/null -w "%{http_code}" http://localhost:13000/
docker exec klid-backend sh -c "getent hosts klid-mock-server"
docker exec klid-backend sh -c "wget -qO- http://klid-mock-server:9400/health"
docker exec klid-postgres psql -U klid_user -d klid_system -c "\dn"
docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT count(*), max(version) FROM public.flyway_schema_history;"
docker logs klid-mock-server --tail 100
docker exec klid-backend sh -c "find /app/storage/deidentified -type f | wc -l"
```

## 외부 연동 배선 실측

| 연동 대상 | 설정 키 | 실효값(런타임) | 목업서버 향함? | 근거 |
|-----------|--------|---------------|:---:|------|
| 비식별(KPST) | `DEIDENTIFY_MOCK_MODE`, `KPST_DEID_ENABLED`, `KPST_DEID_BASE_URL` | `false` / `true` / `http://klid-mock-server:9400` | **O** | ① `docker inspect klid-backend` env 실측 그대로 위 값 확인. ② `docker exec klid-backend wget -qO- http://klid-mock-server:9400/health` → `{"status":"ok"}` (네트워크 도달 확인). ③ mock-server 로그(`docker logs klid-mock-server`)에 backend IP(`172.18.0.5`)발 실제 `POST /project`·`GET /retrieve_progress` 200 다건 기록. ④ `/app/storage/deidentified` 하위에 391개 실파일, 그 중 파일명이 KPST mock 산출 규칙(`{stem}_{yyyyMMddHHmm}_mask.mp4`, 예: `1c41d5d7-..._202607230730_mask.mp4`)과 일치 — 실제 왕복 산출물 확인 |
| VLM | `vlm.client.enabled`(`VLM_CLIENT_ENABLED`) | `false` (compose 미오버라이드, application.yml 기본값 그대로) | **X** | `docker inspect` env에 `VLM_CLIENT_ENABLED=false` 확인. 코드(`VlmClient.submitTimeseries`)는 `enabled=false`면 외부 호출 자체를 생략하고 즉시 `VlmTimeseriesResponse.skipped(...)` 반환(자체 값 생성은 아니고 "미실행"). mock-server에는 `/v1/videovlm/describe` 등 3EP가 살아있으나(openapi 확인) mock-server 로그에 `videovlm` 관련 요청 0건 — 3일간 단 한 번도 VLM 경로가 mock을 호출한 적 없음 |
| 증강 | `authoring.augment.external.mode`(`AUGMENT_EXTERNAL_MODE`) | `dev` (compose 미오버라이드, application-local.yml 기본값) | **X** | 아래 ENV-ISSUE-01 참조 — mock-server의 `POST /v1/augment`를 호출하는 코드 경로가 코드베이스에 존재하지 않음(구현체 2종: Noop/DevAugmentCallbackSimulator뿐). mock-server 로그에 `/v1/augment` 요청 0건 |
| ai-server (YOLO/SAM2) | `AI_SERVER_URL`, `AI_MOCK_MODE`, `AI_DEVICE` | `http://klid-ai-server:9300` / `false` / `cpu` | 해당없음(비-mock 대상) | ai-server는 "외부 벤더"가 아니라 BE의 내부 추론 사이드카(CLAUDE.md 아키텍처 원칙)이므로 mock-server 경유 대상이 아님. `AI_MOCK_MODE=false`로 실제 CPU 추론(YOLOX/SAM2) 수행 중 — 이 자체는 설계대로 정상 |

## ★ 자체 채움(self-fill) 의심 경로 — 발견 시 결함 기록

### [ENV-ISSUE-01] 증강(SFR-07) 외부 연동이 mock-server를 전혀 호출하지 않고 내부 시뮬레이터가 자체 완료 처리
- **심각도**: HIGH
- **기대 동작**: 로컬에서 증강 요청(`POST /v1/augments/request`)이 발생하면 외부 증강 시스템(로컬에서는 mock-server `POST /v1/augment`)에 실제로 위탁되고, mock-server가 만든 결과를 콜백으로 수신해 반영해야 한다(비식별·VLM과 동일 원칙).
- **현재 동작**:
  - `backend/src/main/resources/application-local.yml:104-105` — `authoring.augment.external.mode` 기본값이 `dev`.
  - `backend/src/main/java/kr/co/cudo/authoring/augment/integration/ExternalAugmentClient.java` 인터페이스의 구현체는 단 2개뿐:
    - `NoopExternalAugmentClient`(`mode=noop`, 운영 기본) — 로그만 남기고 즉시 `ack=true` 반환, 외부 호출 0건.
    - `DevAugmentCallbackSimulator`(`mode=dev`, local 기본 활성) — 외부 시스템을 흉내내어 **본인이 직접** `SIMULATED_STATUS = "SUCCESS"`를 만들고, HMAC 서명까지 자체 발급해 자기 자신의 콜백 엔드포인트(`/api/v1/aug/callback`)로 되쏘는 구조. mock-server의 `POST /v1/augment`를 호출하는 코드는 어디에도 없음(grep 결과 `ExternalAugmentClient` 구현체가 이 2개뿐임을 확인).
  - 런타임 증거: mock-server가 3일간 기동 중이며 openapi에 `/v1/augment`, `/v1/augment/status`가 정상 노출됨에도, `docker logs klid-mock-server`에 해당 경로 요청이 **1건도 없음**(반면 KPST `/project`·`/retrieve_progress`는 다건 기록).
- **영향**: "로컬은 무조건 목업서버를 본다"는 원칙이 증강 도메인 전체에서 위반됨. mock-server의 증강 스텁이 실제로는 한 번도 검증되지 않은 채 방치되고, 배포 전 실 외부 시스템 계약(요청 바디 스키마·콜백 서명 등)과의 정합성이 로컬 테스트로는 전혀 검증되지 않는다. 이후 예정된 "배치 파이프라인 정상 시나리오" 테스트케이스가 증강 단계를 포함한다면, 그 결과는 실제 연동이 아니라 자체 시뮬레이션 결과를 검증하는 셈이 된다.
- **수정 방향(제안, 이번 작업에서는 미적용)**: mock-server의 `POST /v1/augment`를 실제로 호출하고 결과를 콜백으로 수신하는 3번째 `ExternalAugmentClient` 구현체(예: `mode=live`)를 추가하고, `docker-compose.local.yml`에서 `AUGMENT_EXTERNAL_MODE=live`(가칭) + mock-server base-url을 명시적으로 오버라이드하도록 구성. 현재 구조상 `DevAugmentCallbackSimulator`는 "콜백 수신측 검증"(HMAC 서명·idempotency)만을 위한 것으로 보이며, mock-server 위탁 자체를 대체하지 못한다.

### [ENV-ISSUE-02] VLM 외부 연동이 로컬에서 통째로 비활성화되어 mock-server에 단 한 번도 도달하지 않음
- **심각도**: MEDIUM (자체 값 "생성"은 아니고 "미실행+스킵"이라 ENV-ISSUE-01보다 낮게 분류하되, 원칙1 위반은 동일)
- **기대 동작**: 로컬에서 VLM 위탁(`POST /v1/videovlm/describe`)이 mock-server로 실제 호출되어야 한다.
- **현재 동작**: `VLM_CLIENT_ENABLED=false`(compose 어디에도 override 없음, `application.yml:381` 기본값 그대로 유지) → `VlmClient.submitTimeseries()`가 `if (!enabled)` 분기로 외부 호출 없이 즉시 `VlmTimeseriesResponse.skipped(requestId)` 반환(`backend/.../common/client/VlmClient.java:92-95`). `VlmTimeseriesStep.doSubmit()`도 `vlmClient.isEnabled()`가 false면 ledger 등록조차 하지 않고 조기 반환(`backend/.../batch/step/VlmTimeseriesStep.java` doSubmit 상단). 값을 스스로 "채우지"는 않지만(결과 자체가 없음), 파이프라인 관점에서는 VLM 단계가 통째로 없는 것처럼 동작.
- **영향**: mock-server의 VLM 3EP(`verify`/`describe`/`status`)가 로컬 검증 대상에서 완전히 빠짐. VLM 콜백 수신(`POST /v1/vlm/callback`) 경로도 로컬에서 트리거될 일이 없어 미검증.
- **수정 방향(제안, 이번 작업에서는 미적용)**: `docker-compose.local.yml`에 `VLM_CLIENT_ENABLED=true` + `VLM_SERVICE_URL=http://klid-mock-server:9400` override 추가(현재 파일에는 비식별만 override되어 있고 VLM은 헤더 주석에 "VLM 은 local 에서 enabled=false"로 의도적으로 명시돼 있음 — 즉 현재는 "알려진 설계"이나, 이번 작업의 절대원칙 기준으로는 결함으로 재분류해 기록).

## mock-server 제공 엔드포인트 목록

`GET /openapi.json` 실측 결과:

- `GET /` — 헬스/루트
- `GET /health` — 헬스체크(`{"status":"ok"}`)
- `POST /project` — KPST 비식별 프로젝트 생성
- `POST /delete_project_name`, `POST /delete_project_id` — 프로젝트 삭제
- `GET /retrieve_progress` — 비식별 진행상태 조회 (backend가 실제 폴링 중 — 로그 확인)
- `GET /retrieve_report` — 비식별 결과 리포트
- `GET /manual_deid_info`, `/manual_deid_info/project_id`, `/manual_deid_info/project_name` — 수동 비식별 정보
- `GET /dataset_frames` — 프레임셋 조회
- `GET /retrieve_job_logs` — 작업 로그
- `POST /v1/videovlm/verify`, `POST /v1/videovlm/describe`, `GET /v1/videovlm/status` — VLM (로컬에서 미사용, ENV-ISSUE-02)
- `POST /v1/augment`, `GET /v1/augment/status` — 증강 (로컬에서 미사용, ENV-ISSUE-01)

## 기동 실패·차단 사항

없음 — 5개 서비스(postgres/backend/ai-server/mock-server/frontend) 전부 이미 정상 기동·헬스 OK 상태였음. `GET /api/actuator/health`(전체, 인증 없음)가 503 DOWN을 반환하는 점만 예외이나, liveness/readiness는 UP이고 docker healthcheck도 healthy이므로 이후 테스트케이스 검증(배치 파이프라인)의 차단 사유는 아님. 원인(어느 HealthIndicator가 DOWN인지)은 `show-details=when-authorized` 정책상 익명 호출로는 확인 불가 — 필요 시 인증 토큰으로 재조회 권장(이번 스코프 밖).
