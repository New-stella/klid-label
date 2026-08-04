# 스택 기동/배선 실측 검증 — 2026-08-03 (3차 §3-1)

- 검증 워크트리: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0803` (branch `qa-0803`)
- 실제 docker compose 조작은 동일 커밋(HEAD `e065da42`)의 형제 워크트리 `/Users/chanki/Documents/workspace/klid-label`(branch `main`, `.env` 보유)에서 수행 — 컨테이너명이 `container_name:`으로 고정돼 있어 어느 디렉터리에서 기동하든 동일 컨테이너를 대상으로 함. 두 워크트리 소스 트리는 빌드 산출물(`.gradle`/`dist`/`weights` 등) 외 diff 없음(확인됨) → 이번 회차 검증 대상 코드와 이미지가 일치.

## 0. 착수 전 상태 — 스택은 이미 떠 있었으나 3일 전 이미지로 STALE

- `docker ps`: klid-frontend/klid-ai-server/klid-backend/klid-mock-server/klid-postgres 전부 `Up 2 days (healthy)`.
- 이미지 빌드 시각: 4개 서비스 전부 `2026-08-01T14:0x` — 반면 이 워크트리 HEAD는 `e065da42`(2026-08-03 14:26, PR #76 병합)로 **약 20개 커밋 · Flyway V159~V163 5건 · 최근 2일치 기능(영상 개인정보 메타 화면, 영상목록 검색필터 4종, 일별 작업량 통계, 이벤트유형 설정화면 이관, MNG_CLIP_SCHEDULE_QUE→LS_ 개명, 보안 픽스 등) 미반영** 상태였음.
- **판정: 문제(환경 버전 격차)** — 그대로 검증하면 이번 회차가 검증해야 할 최신 코드가 아니라 2일 전 코드를 실동작 검증하게 됨. §3-1 목적("실동작 기준")에 위배.
- **조치**: `docker compose -f docker-compose.yml -f docker-compose.local.yml build klid-backend klid-frontend klid-ai-server mock-server` → 4개 이미지 전부 재빌드 성공(backend gradle bootJar BUILD SUCCESSFUL 18s 포함) → `up -d`로 재기동. **코드/설정 파일은 일절 수정하지 않았고, 기존 코드로부터 이미지만 재생성**(§10 예외 조항 취지에 부합 — "설정으로 안 되고 코드를 고쳐야만" 뜨는 상황이 아니라 이미지 신선도 문제였음).

## 1. docker compose 구성 확인

- 파일: `docker-compose.yml`(base) + `docker-compose.local.yml`(override) — `config --services` → `klid-postgres, klid-storage-init, mock-server, klid-ai-server, klid-backend, klid-frontend` 6개 정상 파싱.
- mock-server 배선은 파일 주석에도 명시: "외부 벤더(비식별 KPST / VLM)는 mock-server 컨테이너로 **실제 HTTP 연동** — 저작도구가 스스로 결과를 채우는 내부 self-fill 경로(DEIDENTIFY_MOCK_MODE=true)는 쓰지 않는다."
- **판정: OK**

## 2. 서비스별 헬스 실측 (재빌드·재기동 후)

| 서비스 | 확인 명령 | 실제 출력 | 판정 |
|---|---|---|---|
| mock-server `/health` | `curl localhost:9400/health` | `{"status":"ok"}` HTTP 200 | OK |
| mock-server `/docs` | `curl -o /dev/null -w '%{http_code}' localhost:9400/docs` | 200 | OK |
| backend `/actuator/health`(context-path 무시 루트) | `curl localhost:18081/actuator/health` | HTTP 404 (Tomcat 기본 404 페이지) | 예상된 동작 — context-path가 `/api`라 루트에는 없음(1차에서도 동일 확인, 문서화 누락 주의사항) |
| backend `/api/actuator/health` | `curl localhost:18081/api/actuator/health` | `{"status":"UP","groups":["liveness","readiness"]}` HTTP 200 | OK |
| backend Flyway | `docker logs klid-backend \| grep -i flyway` | `Migrating schema "public" to version "159"→"163"` × 5건, 최종 `Successfully applied 5 migrations ... now at version v163` | OK — V158(기존)→V163(HEAD) 전건 성공 |
| ai-server `/docs` | `curl -o /dev/null -w '%{http_code}' localhost:19300/docs` | 200 | OK |
| frontend `/` | `curl -o /dev/null -w '%{http_code}' localhost:13000/` | 200 | OK |
| DB 스키마 | `psql -c '\dn'` | 스키마 목록 = `public` 하나뿐, `klid_at` 없음 | **문제(기존 알려진 갭, 재확인)** — CLAUDE.md/문서는 `klid_at` 운영을 명시하나 실제 Hibernate/Flyway는 스키마 미지정으로 `public`에 전체 적재(`ls_%` 테이블 59개 전부 `public`, `klid_at` 스키마 자체 미생성). 1차(2026-08-01) 실측과 동일 — 회귀 아님, 지속되는 문서-실측 불일치. F/G 클러스터의 데이터마트 View·SQL 직접조회 케이스에서 `klid_at.*` 하드코딩 쿼리가 있으면 오탐(false FAIL) 유의 |

Flyway 최종 상태(`flyway_schema_history` installed_rank=158): `version=163, description='add video privacy meta', success=t`.

## 3. backend 실효 환경변수 — 외부 연동 URL이 mock-server를 가리키는지

`docker exec klid-backend env`(재빌드 후):

```
AI_MOCK_MODE=true                                   ← backend 컨테이너에만 존재, backend 코드/yml 미소비(1차와 동일 스트레이 변수, 무해)
AI_SERVER_URL=http://klid-ai-server:9300            ✅ 컨테이너명 정상
AUGMENT_API_BASE_URL=http://klid-mock-server:9400   ✅
AUGMENT_EXTERNAL_MODE=http                          ✅ (실 HTTP 위탁, mock 아님)
CONTROL_NOTIFY_ENABLED=true
CONTROL_NOTIFY_URL=http://klid-mock-server:9400     ✅
DEIDENTIFY_MOCK_MODE=false                          ✅ 내부 self-fill 아님
KPST_DEID_ENABLED=true
KPST_DEID_BASE_URL=http://klid-mock-server:9400     ✅
VLM_CLIENT_ENABLED=true
VLM_SERVICE_URL=http://klid-mock-server:9400        ✅
```

ai-server 컨테이너: `AI_MOCK_MODE=false`(YOLO/SAM2 실추론 경로), `CONTROL_NOTIFY_URL=http://klid-mock-server:9400`.

**판정: OK** — 비식별(KPST)·VLM·증강(GenAI)·관제통지 4개 외부 연동 전부 mock-server(:9400)로 실효 배선됨. `AI_MOCK_MODE=true`는 backend에서 미사용 스트레이 변수(1차 실측과 동일, 결함으로 집계하지 않음 — 다만 설정 위생 이슈로 재기록).

## 4. 내부 목 모드 플래그 실효값

- `DEIDENTIFY_MOCK_MODE=false` → **정상**(true였다면 결함). 비식별은 mock-server(KPST 대역)를 실제 호출하는 경로.
- 그 외 내부 self-fill 성격의 `*_MOCK_MODE` 류 검색(`env | grep -i mock`) 결과 backend 쪽에는 `AI_MOCK_MODE=true`(위 3번 항목, 미소비) 외 추가 발견 없음.
- **판정: OK** (self-fill 스위치 꺼짐 확인)

## 5. mock-server 인바운드 로그 관찰

재기동 직후 컨테이너가 새로 생성되어 로그는 리셋됐지만(헬스체크 로그만 14줄), 재기동 전(구 컨테이너, 2026-08-01~08-03 누적 로그 12,259줄)에서 **실제 비즈니스 트래픽이 이미 실증**돼 있었음(2차 회차 검증 에이전트들이 구동한 파이프라인 결과, DB에도 88건 데이터로 반영됨 — 아래 6번 참조):

```
11703  GET /health (헬스체크)
   61  POST /api/genai/jobs                              ← 증강 요청
   21  GET /retrieve_progress                             ← 비식별(KPST) 진행 폴링
   11  POST /api/data-set/v2/jobs/27/notify-completed      ← 관제 통지(완료)
   10  POST /api/data-set/v2/jobs/27/notify-updated        ← 관제 통지(수정)
    9  POST /project                                       ← KPST 프로젝트 생성
    8  POST /v1/videovlm/verify                            ← VLM
    7  POST /v1/videovlm/describe                          ← VLM 시계열 요청
    4  POST /delete_project_name
   기타 notify-completed/updated (jobId 다수), genai jobs cancel/results 등
```

일부 `POST /api/genai/jobs` 400 Bad Request 관측됨(`input_files[].file_path 는 허용된 루트의 절대경로여야 합니다`, path guard 거부) — 이는 §3-1 배선 판정과 무관한 개별 케이스 이슈로 보이며(2차 회차에서 이미 다뤘을 가능성), 이번 환경 준비 단계에서는 **판정에 반영하지 않고 사실만 기록**. 재빌드 후 컨테이너 간 통신 자체는 정상 확인(아래 6번).

재기동 후 최소 확인: `docker exec klid-backend curl http://klid-mock-server:9400/health` → 200, 로그에 backend 컨테이너 IP(172.20.0.x)발 `GET /health` 즉시 기록 확인(요청-로그 왕복 실시간 반영됨).

**판정: OK** — mock-server가 backend로부터 실제 인바운드를 받는다는 것이 로그로 실증됨(4개 외부 연동 전부 왕복 기록 존재).

## 6. 컨테이너 간 이름 해석

- `docker exec klid-backend curl http://klid-mock-server:9400/health` → 200
- `docker exec klid-backend curl http://klid-ai-server:9300/docs` → 200
- **판정: OK**

## 7. 데이터 보존 확인 (postgres 컨테이너는 재생성하지 않음)

- `klid-postgres`는 `Up 2 days` 그대로(재빌드 대상에서 제외) → 이전 회차(2차) 검증 중 생성된 테스트 데이터 보존됨.
- `SELECT count(*) FROM public.ls_data_raw` → **88건**, 최근 raw_sn 901~906 `COMPLETED` 상태 확인.
- **판정: OK** (§3-3 파이프라인 실구동은 이번 태스크 범위 아님 — 별도 `_raw/pipeline-drive.md`에서 필요 시 신규 구동)

## 8. 참고 — 알려진 미해결 갭 (1차 이후 지속, 이번 회차 결함 아님)

- `ai-server/weights` 디렉터리 여전히 빈 디렉토리(`docker exec klid-ai-server ls /app/weights` → 0 files) → YOLO/SAM2 실제 가중치 미탑재. 오토라벨 **정확도**(검출 결과 값 자체) 관련 케이스는 계속 BLOCKED 대상(추론 파이프라인 자체는 동작하되 모델 가중치 부재로 실질적 검출은 기대 불가). 배선/경로/API 계약 검증에는 영향 없음.
- 포트 매핑(1차에서 확정된 값 재확인, 변경 없음): backend `localhost:18081`(내부 8080, context-path `/api`), frontend `localhost:13000`(내부 5174), ai-server `localhost:19300`(내부 9300), mock-server `localhost:9400`(내부 9400, 변경 없음). 컨테이너 간 통신은 내부 포트 그대로.

## 종합 판정표

| 항목 | 배선/상태 | 근거 |
|---|---|---|
| 풀스택 기동 | OK (재빌드 후 5개 컨테이너 전부 healthy) | `docker ps`, health check 결과 |
| 코드-이미지 정합(HEAD 일치) | OK (재빌드로 해소) | Flyway V163 적용 확인, git diff 소스 동일 |
| 비식별(KPST) → mock-server | OK | env(`DEIDENTIFY_MOCK_MODE=false`,`KPST_DEID_BASE_URL`) + 과거 로그(`/project`,`/retrieve_progress`) |
| VLM → mock-server | OK | env(`VLM_SERVICE_URL`) + 과거 로그(`/v1/videovlm/describe`,`/verify`) |
| 증강(GenAI) → mock-server | OK | env(`AUGMENT_API_BASE_URL`,`AUGMENT_EXTERNAL_MODE=http`) + 과거 로그(`/api/genai/jobs`) |
| 관제통지 → mock-server | OK | env(`CONTROL_NOTIFY_URL`) + 과거 로그(`notify-completed`/`notify-updated`) |
| ai-server(YOLO/SAM2) 실추론 경로 | OK(경로) / 가중치 미탑재(정확도 BLOCKED) | `AI_MOCK_MODE=false`, weights 디렉터리 empty |
| 내부 목 모드 플래그 | OK (전부 false/미소비) | env 덤프 |
| 컨테이너 이름 해석 | OK | docker exec curl 왕복 |
| DB 스키마 `klid_at` | **문제(지속)** | 실제는 `public`, `klid_at` 미생성 — 문서 정정 또는 코드 정합 필요(수정은 이번 태스크 범위 아님) |

## 다음 단계

- §3-1 완료. 다음은 §3-2(자동테스트 baseline, 없으면 실행) 및 §3-3(정상 시나리오 파이프라인 실구동) 진행 가능.
- **주의**: 재빌드로 컨테이너가 교체되어 mock-server 로그 히스토리는 리셋됨. 이후 클러스터 검증 에이전트가 "mock-server 로그로 실동작 확인" 시 이번 회차(3차)에 새로 발생한 요청만 잡힘 — 과거(2차) 로그가 필요하면 이 문서의 5번 섹션을 참고.
