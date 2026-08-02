# 스택 기동/배선 실측 검증 — 2026-08-01

## 1. 헬스 실측
- mock-server(:9400) `/health` → `{"status":"ok"}` (200), `/docs` → 200
- ai-server(:19300→9300) `/docs` → 200
- frontend(:13000→5174) `/` → 200
- backend(:18081→8080) — context-path가 `/api` 이므로 `/actuator/health`는 404, `/api/actuator/health` → 200 (정상, 문서화 누락 주의)

## 2. backend 실효 환경변수 (docker exec klid-backend env)
```
AI_MOCK_MODE=true              ← backend 컨테이너에 설정돼 있으나 backend 코드/application*.yml 어디서도 소비되지 않음(grep 무결과). 이 변수는 ai-server 전용 플래그가 backend 환경에 잘못 복사된 것으로 보임(무해하지만 오해 소지 — 정리 권장)
AI_SERVER_URL=http://klid-ai-server:9300      ✅ 컨테이너명 정상
AUGMENT_API_BASE_URL=http://klid-mock-server:9400  ✅
AUGMENT_EXTERNAL_MODE=http                     ✅ (mock 아님, 실 HTTP 위탁)
CONTROL_NOTIFY_ENABLED=true
CONTROL_NOTIFY_URL=http://klid-mock-server:9400  ✅
DEIDENTIFY_MOCK_MODE=false                     ✅ 내부 self-fill 아님 — mock-server(KPST 역할)로 실제 위탁
KPST_DEID_BASE_URL=http://klid-mock-server:9400  ✅
VLM_CLIENT_ENABLED=true
VLM_SERVICE_URL=http://klid-mock-server:9400   ✅
```
- ai-server 실효값: `AI_MOCK_MODE=false` → YOLO/SAM2 실제 추론 경로(mock 아님) ✅

## 3. 내부 목 모드 플래그 판정
- `DEIDENTIFY_MOCK_MODE=false` → **정상**: 비식별은 내부 자체생성이 아니라 mock-server(KPST 대역)를 실제로 호출
- `AI_MOCK_MODE=true`(backend 쪽 스트레이 변수) → backend에서는 미사용(no-op), ai-server 쪽 실효값은 `false`이므로 실추론 동작. **결함 아님, 다만 backend 환경에 남아있는 것은 설정 위생 이슈로 기록**

## 4. mock-server 인바운드 로그 (docker logs klid-mock-server, 헬스체크 제외)
실제 비즈니스 트래픽 확인됨:
- `POST /project` (KPST 프로젝트 생성) 200 — 다건
- `GET /retrieve_progress` 200 — 비식별 진행 폴링
- `POST /v1/videovlm/describe` 200 + 콜백 `POST http://klid-backend:8080/api/v1/vlm/callback` 200 — VLM 시계열 요청/콜백 왕복 확인
- `POST /api/data-set/v2/jobs/{id}/notify-completed`, `notify-updated` 202 — 관제 통지 왕복
- `POST /api/genai/jobs` 202 + 콜백 `POST http://klid-backend:8080/api/v1/genai/callback` 200 — 증강 요청/콜백 왕복

→ **비식별(KPST)·VLM·증강 3개 연동 모두 mock-server 경유가 로그로 실증됨.**

## 5. 컨테이너 간 이름 해석
- `docker exec klid-backend curl http://klid-mock-server:9400/health` → 200 ✅
- `docker exec klid-backend curl http://klid-ai-server:9300/docs` → 200 ✅
- 도커 네트워크 내 서비스명 DNS 해석 정상

## 6. DB 스키마 확인 (Critical 결함 발견)
- `docker exec klid-postgres psql -U klid_user -d klid_system -c "\dn"` → 스키마 목록에 `klid_at` **없음**, `public`만 존재
- `information_schema.tables`에서 `ls_%` 테이블 57개 전부 `table_schema=public`
- `select count(*) from public.ls_data_raw` → 33행 (실 데이터 존재, `klid_at.ls_data_raw`는 relation 없음 에러)
- backend `application*.yml` 전체에 `klid_at`/`default_schema`/`default-schema` 언급 **전무** (grep 무결과)

**판정: X (배선 끊김/문서-실측 불일치)** — CLAUDE.md는 "klid_at 스키마 운영"을 명시하지만 실제로는 Hibernate/Flyway가 스키마 미지정으로 PostgreSQL 기본 `public` 스키마에 전 테이블을 생성·적재하고 있음. 기능 동작 자체(FK/조회)는 self-consistent하여 당장 깨지지 않으나, 문서 정합성 위반이며 향후 `klid_at.` 접두로 직접 쿼리하는 배치/리포트/관제 연동 View 스크립트가 있다면 전부 실패한다.

## 종합 판정
| 연동 | 배선 | 근거 |
|---|---|---|
| 비식별(KPST) → mock-server | O | env(`DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_BASE_URL`) + 로그(POST /project, retrieve_progress) |
| VLM → mock-server | O | env(`VLM_SERVICE_URL`) + 로그(describe 요청 + callback 200 왕복) |
| 증강(GenAI) → mock-server | O | env(`AUGMENT_API_BASE_URL`, `AUGMENT_EXTERNAL_MODE=http`) + 로그(genai/jobs 요청 + callback 200 왕복) |
| ai-server(YOLO/SAM2) 실추론 | O | ai-server 컨테이너 `AI_MOCK_MODE=false` (backend의 `AI_MOCK_MODE=true`는 backend 미소비 스트레이 변수) |
| DB 스키마 `klid_at` | **X** | 실제 데이터는 전량 `public` 스키마, `klid_at` 스키마 자체가 미생성. CLAUDE.md 서술과 불일치 |

### 후속 클러스터 영향
- B(마킹/배치)·C(오토라벨링/VLM)·D(라벨링/버전관리)·E(증강)는 mock-server 경유 자체는 **정상**이므로 이 축으로는 차단 요인 없음
- 단, **DB 스키마 불일치**는 `klid_at.*`를 전제로 한 데이터마트 View(`V_COMPLETED_*`) 검증·SQL 직접 조회 기반 테스트케이스(주로 F/G 클러스터의 관제 연동·데이터마트 View 검증)에서 스키마 하드코딩 쿼리 사용 시 실패 원인이 될 수 있음 — 테스트케이스 실행 전 스키마 이슈를 알고 있어야 오탐(false FAIL)을 피함

---

## ⚠ 추가 기록 — 스택 재빌드 시도 및 보류 결정 (2026-08-01)

1차 stack-bringup 실측(위 본문) 이후, 실행 중인 klid-backend 컨테이너가 **구버전(2026-07-31 빌드, flyway V146)**이라는 사실이 확인됐다 — 방금 origin/main에 병합된 아래 두 기능이 이미지에 반영되지 않았다:
- LS_DATA_INGEST 적재 리팩터 (커밋 6c8a5303 등, 관제 인입 테이블 도입 — B 클러스터 영향)
- 증강 폐기/복구 기능 (커밋 08a76392·b99bbde2·1bf06ce5·56d30478, Flyway V153~V158 — E 클러스터 영향)

**재빌드 2회 시도했으나 모두 실패**: Docker Desktop 내부 프록시(`http.docker.internal:3128` / `hubproxy.docker.internal:5555`)가 무응답이라 4개 서비스(klid-backend/klid-frontend/klid-ai-server/mock-server) 전부 베이스 이미지 pull 단계에서 `DeadlineExceeded`. 호스트 macOS에서 `registry-1.docker.io` 직접 curl은 정상 — **리포·코드 문제가 아니라 이 개발머신 Docker Desktop 인프라 문제**로 결론.

**사용자 확인 결과**: 사용자가 외부에 있어 Docker Desktop을 지금 재시작할 수 없음. → **재빌드는 보류하고, 기존 V146 스택으로 검증을 진행**하기로 결정(사용자 명시 지시, 2026-08-01).

### 이번 회차 검증에 대한 영향 (판정 시 반드시 반영)
- **B 클러스터(배치/비식별)**: `LS_DATA_INGEST` 관련 신규 케이스는 이 스택으로 실동작 검증 불가 → **BLOCKED(사유: 환경 버전 격차, 재빌드 대기)**로 기록
- **E 클러스터(증강/해상도/Export/메타)**: 증강 폐기(discard)·복구(restore)·진행률(progress)·취소(cancel) 관련 신규 케이스(V153~V158, `AugmentDiscard*`/`AugmentCancel*`/`AugmentProgress*` 등) → **BLOCKED(사유: 환경 버전 격차, 재빌드 대기)**로 기록. 기존(구버전에도 존재하던) 증강 요청/결과 조회 케이스는 정상 검증 가능
- **H 클러스터(FE/E2E)**: frontend 컨테이너도 동일 시점 구버전이라, 증강 폐기/복구 관련 화면(AugmentCancelModal/AugmentProgressPanel/RestoreReasonModal 등)의 **브라우저 실동작 검증은 BLOCKED**. 단, 이들은 vitest 컴포넌트 테스트로 이미 baseline에서 전량 통과 확인됨(test-baseline.md 참고) — 정적+단위테스트 근거는 유효
- **A/C/D/F/G 클러스터**: 위 두 기능과 직접 관련 없는 케이스는 기존 스택으로 정상 실동작 검증 진행. 단 각 에이전트는 케이스별로 관련 커밋 범위(b2b44f0e~56d30478)에 걸리는지 개별 판단 필요

---

## ✅ 최종 갱신 — 스택 재빌드 완료 (2026-08-01 23:10 KST)

Docker Desktop 프록시 복구 확인 후 재빌드 성공(4개 서비스, 약 350초). **flyway_schema_history 직접 재확인 결과 V158(최신) 정상 적용**(이전 재빌드 에이전트의 "V99" 보고는 DB 미지정 조회 오류로 판명 — `psql -U klid_user -d klid_system` 명시 필요, `-d` 생략 시 다른 DB로 접속됨).

**기존 테스트 데이터 보존 확인**: rawSn 26/28/30/31/32 등 클러스터 A·B 검증 중 생성한 데이터 그대로 존재(klid-postgres 컨테이너 미재생성).

**⚠ 포트 변경 (이후 검증 에이전트는 아래 포트 사용)**:
| 서비스 | 이전 | 현재 |
|---|---|---|
| backend | localhost:8080 | **localhost:18081** (context-path `/api` 동일) |
| frontend | localhost:5174 | **localhost:13000** |
| ai-server | localhost:9300 | **localhost:19300** |
| mock-server | localhost:9400 | 9400 (변경 없음, 127.0.0.1 바인딩) |

컨테이너 이름(klid-backend 등)과 `docker exec`/`docker logs` 사용법은 동일. `docker exec klid-backend ...`처럼 컨테이너 내부에서 서로 통신할 때는 기존 내부 포트(8080/5174/9300/9400) 그대로 사용.

**이제부터 남은 클러스터(C~H) 검증은 이 최신 바이너리(V158) 기준으로 진행.** ai-server YOLO/SAM2 가중치는 여전히 미탑재(빈 디렉토리) — 오토라벨 정확도 관련 케이스는 계속 BLOCKED 대상.
