# 2차 스택 실측 (2026-07-31, 호스트 실제시각 기준 2026-07-30 17:33 UTC)

컨테이너는 이미 기동 중이었음(재기동 안 함). `docker inspect` 라벨(`com.docker.compose.project.config_files`)이
가리키는 경로는 `/Users/ck/orca/workspaces/klid-label/issue-0730/docker-compose.yml` 이나 실제로는 존재하지
않는 옛 worktree 경로다(`git -C .../issue-0730 rev-parse` 실패) — PR #64 머지 후 그 worktree 는 삭제됐고,
스택은 그 worktree에서 `docker compose up`으로 마지막 기동된 뒤 지금까지 살아있는 상태로 추정. **동일 저장소
(`verify-tc`)이고 커밋도 main HEAD 계열**이라 실측 신뢰도에는 영향 없음(§2 확인).

## 0. 1차 대비 변화 요약

| 항목 | 1차(2026-07-25) | 2차(2026-07-30/31) | 비고 |
|------|------|------|------|
| DB 스키마 | `klid_at` 없음, `public` 사용 | 동일하게 `public` | 불변(알려진 이슈) |
| DB 데이터 | rawSn=26 등 장기 누적 데이터 | **완전히 새 DB**(postgres 컨테이너 2026-07-30 11:22 새로 생성, Flyway 재적용) | rawSn=26 참조 **무효** — §5 참조 |
| backend `/actuator/health`(전체) | 503 DOWN | **200 UP** | 개선 |
| `CONTROL_NOTIFY_ENABLED` | `false` (`/v1/tasks/**` 404) | **`true`**, `GET /v1/tasks/123/summary` → 200 실증 | 1차 결함 해소 |
| `WEBHOOK_HMAC_SECRET_AUGMENT` | 빈 값(E-ISSUE-04, 콜백 전건 401) | **비어있지 않음**(32B 값) + 코드상 서명필수 경로 0개로 재설계(Phase 7-A2, 구 `/v1/aug/callback` 제거, `/v1/genai/callback` 무서명) | 구조적으로 해소 |
| 증강(augment) 외부 연동 모드 | `authoring.augment.external.mode=dev`(자족 시뮬레이터, mock 미경유 — ENV-ISSUE-01 HIGH) | **`mode=http`**, `AUGMENT_API_BASE_URL=http://klid-mock-server:9400` | 배선은 정상화. 단 이번 업타임엔 backend发 실호출 로그 없음(§4) |
| VLM 외부 연동 | `VLM_CLIENT_ENABLED=false`(전면 미실행 — ENV-ISSUE-02 MEDIUM) | **`true`**, mock-server 실제 2회 왕복 확인(§4) | 해소 |
| 파이프라인 참조 데이터 | rawSn=26 (프레임16/라벨131/APPROVED/export SUCCEEDED) | **rawSn=26 존재하지 않음**(DB 리셋). 최선 참조는 rawSn=123(비식별 완료/5프레임/33라벨/작업상태 미완료), APPROVED 1건은 seed 합성데이터(20012, 실라벨 0) | 2차는 승인·export 실측 참조가 없음 — §5/§7 |
| backend 이미지-소스 정합 | (1차 기록 없음) | **이미지가 HEAD보다 backend 커밋 11개 뒤처짐**(§2) | 신규 위험 |

## 1. 서비스 헬스

| 서비스 | 포트 | 상태 | 근거 |
|--------|-----:|:----:|------|
| klid-postgres | 5432 | OK | `docker ps` healthy, DB 쿼리 응답 |
| klid-backend | 18081→8080 | OK | `GET /api/actuator/health/liveness`→`{"status":"UP"}`, `/readiness`→UP, **`/actuator/health`(전체)→200 UP**(1차는 503) |
| klid-ai-server | 19300→9300 | OK | `GET /health`→`{"status":"ok"}` |
| klid-mock-server | 9400 | OK | `GET /health`→`{"status":"ok"}` |
| klid-frontend | 13000→5174 | OK | `GET /`→200 |
| DB Flyway | - | OK | `flyway_schema_history`: 144행, `max(version)=146`(문자열 정렬 함정으로 `max()` SQL은 "99" 오답 — `ORDER BY installed_rank DESC`로 실측), `success=false` 0건 |

## 2. 이미지-소스 정합 (HEAD 반영 여부) — ★ 뒤처짐 확인

- backend 이미지 빌드시각: `2026-07-30T11:22:16Z`(=20:22:16 KST, 프롬프트 기술과 일치)
- `git log -1 --format=%ci -- backend/` = `2026-07-30 14:51:16 +0000`(커밋 `95215bd8`) — **이미지 빌드보다 3.5시간 뒤**
- `git log --oneline --since="2026-07-30 11:22:16" -- backend/` = **11개 커밋 미반영**:
  `95215bd8`(PR#66 외부연동 3곳 논블로킹화 머지) · `862ca6d8`(VLM·KPST·증강 논블로킹 리팩터) ·
  `476bc91a`(서빙 경로 링크추종 폐쇄·필터 fail-open 제거·405 정규화, 보안 백로그 5건) ·
  `0beff7fb`(PR#64 파생영상 라벨링 백지 해소·배정 목록 서버사이드 필터) · `eace1213` · `609b192e` ·
  `46f47cee`(파생영상 라벨링 캔버스 백지 — 심링크/TOCTOU 하드닝) · `6ccceea6` · `9303d602` ·
  `09376333`(배치 스텝 트랜잭션 경계 복원) · `a56ab156`
- ai-server 이미지: `2026-07-30T02:47:01Z` — 이후 `ai-server/` 커밋 0건(정합).
- frontend 이미지: `2026-07-30T11:22:52Z` — 이후 `frontend/` 커밋 2건(`eace1213`, `46f47cee`, 위 backend 목록과 중복 — FE/BE 동시 변경 PR) 미반영.
- mock-server 이미지: `2026-07-30T13:33:41Z`(backend/frontend보다 늦게 빌드 — mock은 최신).

**판정에 미치는 영향**: 위 11개 미반영 커밋 중 `476bc91a`(경로 순회·필터 fail-open·405), `46f47cee`(파생영상
프레임 서빙 심링크/TOCTOU), `09376333`(배치 트랜잭션 경계) 은 보안·상태전이 테스트케이스의 판정 대상 코드
자체다. **이 커밋들을 검증하는 TC는 "현재 실행 중인 이미지 기준으로는 구버전 검증"** 이 되므로, 해당
TC들의 PASS/FAIL 결과에 "이미지가 이 수정을 반영하지 않음"이라는 단서를 반드시 부기해야 한다. 재빌드는
지시에 따라 수행하지 않음.

## 3. 외부 연동 실배선

`docker exec klid-backend env`(런타임 실효값) 기준:

| 연동 | 실효 설정 | mock 경유 O/X | 근거 |
|------|-----------|:---:|------|
| 비식별(KPST) | `DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_ENABLED=true`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400` | **O** | mock 로그: `POST /project`→200(`prj_id=1`), `GET /retrieve_progress`→200, 워터마크 합성·완료 로그 확인(§4) |
| VLM | `VLM_CLIENT_ENABLED=true`, `VLM_SERVICE_URL=http://klid-mock-server:9400`(prop `vlm.client.url`, `base-url` 아님), `VLM_ALLOW_INSECURE_URL=true` | **O** | mock 로그: `POST /v1/videovlm/describe`→200 ×2, 콜백 `POST http://klid-backend:8080/api/v1/vlm/callback`→200 ×2(§4) |
| 증강(augment) | `authoring.augment.external.mode`(env `AUGMENT_EXTERNAL_MODE`)=**`http`**, `AUGMENT_API_BASE_URL=http://klid-mock-server:9400`, `allow-insecure-url`(local 프로파일 기본 `true`) | **배선 O / 왕복 미관측** | 1차 `noop`/`dev` 자족 시뮬레이터 코드 자체가 Phase 7-A2에서 삭제되고 `http` 단일 구현체로 교체됨(코드 확인: `authoring.augment.external.mode` yml 주석 "이 둘이 전부다 — dev 모드 제거됨"). 단 §4 로그상 backend 컨테이너발 실호출 0건 — 관측된 4건은 호스트발 수동 테스트 |
| 관제 통지 | `CONTROL_NOTIFY_ENABLED=true`, `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` | O(배선) | 1차 `false`에서 전환. `GET /v1/tasks/123/summary`(REVIEWER 토큰) → 200 실증(§6) |
| ai-server(YOLO/SAM2) | `AI_SERVER_URL=http://klid-ai-server:9300`, ai-server 자체 env `AI_MOCK_MODE=false`,`AI_DEVICE=cpu` | 해당없음(내부 사이드카) | backend 컨테이너 env엔 `AI_MOCK_MODE=true`가 찍히지만 backend 코드 어디서도 이 키를 참조하지 않음(grep 0건) — ai-server 컨테이너 자체 env가 `false`이므로 실추론 정상, backend측 값은 미사용 dead env |
| Webhook HMAC | `WEBHOOK_HMAC_SECRET_AUGMENT`=32바이트 비-placeholder 값 | - | 1차 E-ISSUE-04(빈 값→401) 자체가 원인 무효화됨: 코드 리뷰 결과 서명 필수 경로가 현재 **0개**(`WebhookProtectedPaths.SIGNATURE_REQUIRED = List.of()`) — 구 `/v1/aug/callback`(HMAC 필수) 삭제, 신 `/v1/genai/callback`은 벤더 계약상 원래 무서명. 시크릿 값 자체는 살아있으나 현재 어떤 경로도 검증하지 않음 |

**self-fill 재확인**: `AI_MOCK_MODE`(backend측 미사용 dead env)를 제외하면 내부 목모드로 외부 호출을 우회하는
플래그는 발견되지 않음. `DevAugmentCallbackSimulator`(자체 HMAC 서명 후 자기 콜백으로 되쏘던 1차 결함 코드)는
코드베이스에서 삭제됨(grep 0건) — self-fill 경로 자체가 제거됨.

## 4. mock-server 인바운드 증거

`docker logs klid-mock-server`(컨테이너 기동 후 전체 로그, 1000줄 — 대부분 15초 간격 헬스체크):

- **KPST 비식별**: `13:36:13` `POST /project`→200(`prj_id=1 name=raw123`) → `13:36:38` 미디어 duration probe·워터마크 합성(`'MOCK 비식별 완료'` 우측하단) → `production completed` → `13:36:38` `GET /retrieve_progress`→200. **실왕복 확인**.
- **VLM**: `13:37:12` `POST /v1/videovlm/describe`→200(`request_id=650155b2...`) → `13:37:15` mock→backend 콜백 `POST http://klid-backend:8080/api/v1/vlm/callback`→200. 동일 패턴 `13:40:14`에 2회차(`request_id=8fb28094...`)도 성공. **실왕복 2건 확인**.
- **증강(genai)**: `POST /api/genai/jobs` 4건 전부 **400**("input_files[].file_path 는 허용된 루트의 절대경로여야 합니다", 요청 경로가 `/storage/deidentified/...`로 `MOCK_GENAI_INPUT_BASE=/app/storage` 접두사 없이 옴). **호출자 IP가 `172.18.0.1`(도커 브리지 게이트웨이 = 호스트발)이며 backend 컨테이너 IP(`172.18.0.5`)가 아님** — backend 앱이 실제로 위탁한 흔적이 아니라 이전 세션(2차 Phase3 부분재검증으로 추정)의 수동 curl 테스트로 판단됨. **이번 컨테이너 업타임 동안 backend→mock 증강 실호출은 0건**.
- 그 외 backend발(`172.18.0.5`) 트래픽은 루트(`GET /`)·헬스체크뿐.

## 5. DB 실태 + 참조 데이터

- 스키마: `public`(1차와 동일, `klid_at` 없음)
- postgres 컨테이너 생성/시작: `2026-07-30T11:22:50Z`/`11:22:53Z` — **backend와 동시에 새로 뜬 완전 신규 DB**(1차 데이터 승계 아님)
- 행수: `ls_data_raw`=38(PENDING 2, COMPLETED 36) · `ls_data_src`=40 · `ls_data_lbl`=64 · `ls_dataset_export`=**0** · `ls_data_aug`=**0** · `ls_deident_proc_log`=3 · `ls_portal_uld`=0
- `ls_raw_data_status`(작업/검수 워크플로우): ASSIGNED 33 · PENDING 1 · APPROVED **1** · REJECTED 1
- 파생영상(`orgnl_raw_sn IS NOT NULL`): **0건** — 증강·해상도파생 전무

파이프라인 진행 참조표(rawSn 123~125 = 실제 시나리오 투입, 20001~20035 = 목록/필터 테스트용 합성 seed 1프레임/0라벨):

| rawSn | 배치상태 | 작업상태 | 비식별 | 프레임 | 라벨 | 비고 |
|---|---|---|---|---|---|---|
| 123 | COMPLETED | ASSIGNED(DB) / board API는 UNASSIGNED로 표시(불일치 관측, 원인 미조사) | Y | 5 | 33 | 1차 rawSn=26 대체할 **최선 실측 참조**. 검수 미완료(APPROVED 아님) |
| 124, 125 | PENDING | - | F(비식별 실패/미수행) | 0 | 0 | 비식별 미완 상태로 정지 |
| 20012 | COMPLETED | **APPROVED** | Y | 1 | 0 | seed 합성 데이터 — 실라벨 없음, export/승인 실동작 결과 아님 |
| 20013 | COMPLETED | REJECTED | Y | 1 | 0 | seed 합성 |
| 20001~20011,20014~20035 | COMPLETED | ASSIGNED | Y | 1 | 0 | seed 합성, 목록 페이징용 |

**★ APPROVED이면서 export SUCCEEDED인 실제 rawSn은 이번 환경에 존재하지 않는다.** `ls_dataset_export` 테이블이
비어 있어 export 재생성·데이터마트 뷰(`V_COMPLETED_*`) 관련 TC는 **검증 에이전트가 직접 라벨링→검수 승인까지
드라이브해서 export를 최초 생성**시켜야 한다(사전 준비된 참조 데이터 없음).

## 6. 검증 에이전트용 인증/요청 레시피

Dev 전용 토큰 발급 엔드포인트(`authoring.dev.login.enabled=true`, local 기본값) — **실제 실행해 200/201 확인 완료**:

```bash
# REVIEWER (userNo 기본 1001)
curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
  -H "Content-Type: application/json" \
  -d '{"role":"REVIEWER","channel":"INTERNAL"}'

# WORKER (userNo 기본 2001)
curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
  -H "Content-Type: application/json" \
  -d '{"role":"WORKER","channel":"INTERNAL"}'

# PORTAL_USER (userNo 기본 3001, channel은 PORTAL 고정)
curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
  -H "Content-Type: application/json" \
  -d '{"role":"PORTAL_USER","channel":"PORTAL"}'
```

응답의 `data.token`(또는 `data.authorizationHeader` 그대로)을 이후 요청에 사용:

```bash
TOKEN="<위 응답의 data.token>"
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:18081/api/v1/tasks/board?page=0&size=1"
# → 200, totalElements=36 확인됨

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:18081/api/v1/tasks/123/summary"
# → 200 {"rawSn":123,"status":"COMPLETED","totalFrames":5,"labeledFrames":3,"totalLabels":33,...}

# 무토큰 → 401 확인됨
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18081/api/v1/tasks/board
```

시드 계정(참고, dev 토큰 발급 시 자동 매핑): `reviewer1`(1001)/`reviewer2`(1002)/`worker1`(2001)/`worker2`(2002)/`portal1`(3001) — `backend/src/main/resources/db/seed/dev-seed.sql`.

## 7. 2차 판정 시 반드시 반영할 제약

1. **backend 이미지가 HEAD 대비 11개 backend 커밋 뒤처짐**(§2) — `476bc91a`(경로 순회/필터 fail-open/405), `46f47cee`(파생영상 프레임 서빙 심링크·TOCTOU), `09376333`(배치 트랜잭션 경계) 을 대상으로 하는 TC는 **이번 실행 이미지에 그 수정이 없다는 점을 결과에 명시**할 것. 재빌드는 이번 작업 스코프 밖.
2. **증강(augment) 왕복은 배선만 정상화됐고 이번 업타임에 backend발 실호출 증거가 없다**(§4) — 증강 관련 TC가 "실제 콜백 왕복 성공"을 요구한다면, 검증 에이전트가 직접 증강 요청 API를 호출해 새로 왕복을 발생시켜야 하며, mock 로그의 기존 400 4건(호스트발 수동 테스트)을 backend 증거로 오인하지 말 것.
3. **APPROVED+export SUCCEEDED 실측 참조 데이터가 없다**(§5) — 1차의 rawSn=26 참조는 무효. export/데이터마트 뷰/롤백/diff 관련 TC는 검증 에이전트가 rawSn=123(비식별 완료, 5프레임/33라벨)을 라벨링 완료→검수 승인까지 직접 진행시켜야 최초의 실측 export 산출물이 생긴다.
4. **`ls_data_aug`=0, 파생영상 0건** — 증강·해상도변경(SFR-06-03/07) 관련 TC는 사전 데이터가 전혀 없어 전량 신규 드라이브 필요.
5. **`WEBHOOK_HMAC_SECRET_AUGMENT`는 현재 어떤 경로도 검증하지 않는다**(§3) — 코드가 Phase 7-A2에서 구 `/v1/aug/callback`(HMAC 필수)을 제거했다. HMAC 서명 검증을 다루는 TC가 있다면 "현재 서명 필수 웹훅 경로 0개"라는 실측을 근거로 판정할 것(1차 E-ISSUE-04와 다른 방식으로 사실상 무효화됨 — "고쳐짐"이 아니라 "그 경로 자체가 없어짐").
6. **rawSn=123의 작업상태 표시 불일치 관측**(§5 표) — DB `ls_raw_data_status.data_stts_cd='ASSIGNED'`인데 `GET /v1/tasks/board` 응답은 동일 rawSn을 `status:"UNASSIGNED"`로 표시. 원인 미조사(스코프 외) — 배정/보드 관련 TC 판정 시 이 불일치를 이미 알려진 관측사실로 참고하되, 본 파일에서 결함으로 단정하지 않음.
7. compose 라벨이 가리키는 worktree 경로(`issue-0730`)가 실존하지 않음(§ 서두) — 재현 시 `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d`를 **현재 워크스페이스(`verify-tc`) 루트**에서 실행할 것.
