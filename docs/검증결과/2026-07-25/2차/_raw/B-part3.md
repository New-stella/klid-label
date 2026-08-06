# B 클러스터 part3 (B-7·B-8) 2차 검증 결과

> 대상: `docs/test-cases/B-batch-deidentify.md` §B-7(TC-VLM-001~025·030~040, 36건) · §B-8(TC-BATCH-100~118, 19건) = **55건**
> 환경: backend `localhost:18081` (이미지 `bdc64ea2ac26` = HEAD `ca3c712b` 재빌드본) · mock-server `:9400` · postgres 스키마 `public`
> 실측 시각: 2026-07-31 03:17~03:27 KST (= mock 로그 UTC 18:17~18:27). `~~취소선~~` 폐기 행 **0건**(집계 제외 대상 없음).
> ⚠ 다른 에이전트가 같은 스택에서 rawSn 134~143 을 동시 구동 중이었다. 본 검증의 모든 실동작 근거는 **내가 생성한 rawSn=144**(관제 클립 `DEV-CLIP-9301`) 또는 **내 고유 request_id**(`6af6d189…` / `407da499…` / `d44875d4…`)로 식별된 것만 인용했다.

---

## 집계

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-7 VLM 위탁·콜백·보류/재개 | 36 | 31 | **1** | 4 | 0 | 0 | 0 |
| B-8 FfmpegFrameExtractor | 19 | 19 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **55** | **50** | **1** | **4** | 0 | 0 | 0 |

- 근거 확인 방식: **[실동작] 21건 · [정적] 34건**.
- **근거 `file:line` 드리프트: B-7 24건 / B-8 2건**(아래 별도 절). B-7 은 `862ca6d8`(외부연동 논블로킹화, Phase C-1)로 `VlmTimeseriesStep` 이 전면 재작성돼 케이스 표의 라인이 **거의 전부** 무효다.
- self-fill 확증 사례 **0건**(아래 절).

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|:--:|
| **B-ISSUE-21** | VLM 이 로컬 목업을 구조적으로 경유 불가(`VLM_CLIENT_ENABLED=false` + `WebClientConfig` HTTPS-only/사설IP 차단으로 배선 시 부팅 크래시) → TC-VLM 전반 실동작 검증 불가 | **✅ 해소.** `VLM_CLIENT_ENABLED=true` · `VLM_SERVICE_URL=http://klid-mock-server:9400`(prop `vlm.client.url`) · `VLM_ALLOW_INSECURE_URL=true`(`application.yml:626-632`) 로 부팅 정상. 내 요청 3건이 backend 컨테이너 IP `172.18.0.5` 에서 mock `POST /v1/videovlm/describe` → 200 → 역방향 콜백 200 까지 완주 | 해소 |
| **B-ISSUE-22** | 동일 rawSn 에 마킹 N건 생성 가능, VLM 은 첫 건만 위탁 → 나머지 영구 PENDING | **✅ 해소.** 실측 `ls_marking` 은 rawSn 당 1행(4/126·5/132·6/133·7/135…), `VlmResultService.markingsInScope`(:186-208)가 `ACTIVE_STATUSES`(PENDING+VLM_REQUESTED)로 넓어져 선행 레이스도 닫힘 | 해소 |
| **B-ISSUE-24** | VLM skip(disabled)이 DB 에 무흔적 → 사후 재처리 대상 식별 불가 | **✅ 해소.** `BatchStatusService.recordVlmSkipped`(:58-62)+`createSkipped` 로 `LS_BATCH_PROC_LOG(PROC_STEP_CD='VLM', PROC_STTS_CD='SKIPPED', ERR_MSG_CN=사유)` 적재. 실측: rawSn=144 행 sn=19, rawSn=128 행 sn=8~11,13 | 해소 |
| **B-ISSUE-25** | VLM 콜백 무인증 + rate limit 미적용 + IP allowlist TODO 미이행 | **✅ 해소(3계층).** `VlmResultController` 주석대로 ①IP allowlist(`webhook.vlm.allowed-ip-cidrs`, `application.yml:671`) ②`HmacWebhookFilter` 무서명 가드(size cap + rate limit) ③발급 게이트. TODO 주석 제거됨. ⚠ dev 실효값 `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none` 이라 이 환경에선 IP 층이 사실상 무제한(운영 형상 별건) | 해소 |
| **B-ISSUE-27** | TC-VLM-010 "45s" 근거 불일치 + TC-VLM-013/024/025 테스트 부재 | **🔶 부분.** 근거 불일치는 더 커졌다(45s 블록 자체가 삭제 — B-ISSUE-42). 커버 갭 4건은 **그대로 미해소**(B-ISSUE-46) | 미해소 |
| **B-ISSUE-41(1차)** | 07-22 이전 레거시 프레임 `DE_IDNTF_SRC_FILE_PATH_NM` NULL 백필 미이행 | 스코프 밖(카탈로그도 "—"). 신규 추출분은 전부 non-null 실측(126·132·133·129~131) | 이월 |
| **E-ISSUE-41(1차)** | 해상도 파생 orgnl/deid 프레임 경로 **동일** → 2벌 바이트 동일 | B-8 축에서는 재발 없음 — `frames/raw/{rawSn}` vs `frames/deid/{rawSn}` 분기 + md5 상이 실측(아래 절) | 해소(B-8 축) |
| **UNCERTAINTIES #13** | VLM 타임아웃 이중 구조(블록 45s ↔ 실효 10s) | **재정정 필요.** 블록 45s 는 코드에 더 이상 없다 — 단일 실효 타임아웃 `vlm.client.timeout-seconds=10`(`VlmClient.java:71,108`)만 존재(B-ISSUE-42) | 갱신 필요 |
| **test-baseline 스킵 5건** | 전부 "라이브 목 서버 실연동 IT — KPST/VLM 왕복" 조건부 SKIP | **이번에도 미실행.** 즉 `VlmClient`↔벤더 계약(describe 요청/응답 스키마·request_id echo·4xx 비재시도)의 **자동 회귀 가드는 2차에도 0건**이다. 본 검증의 왕복 근거는 전부 수동 실동작이며, 자동화된 계약 가드는 여전히 부재 | 미해소 |

---

## ★ VLM 신고 게이트 진입점별 실측

> 게이트 위치: `VlmTimeseriesStep.doSubmit()` **:303-307** — `resolveDeidentifiedPath`(:309) **이전**, `existsById`(:276) **이후**. `run`/`runWithMarking` 이 공통으로 부르는 private 본체라 **어느 진입점도 우회 불가**.

| 진입점 | 신고중 호출 | 기대 | 실측 | 판정 |
|---|---|---|---|:--:|
| `execute(ctx)` (오케스트레이터/Quartz) | `POST /v1/dev/batch/trigger?rawSn=144` (144 `DE_IDENT_YN='F'`) | 외부 호출 0 · SKIPPED · 사유 적재 | mock `videovlm/describe` 누계 **13→13(불변)** · `ls_batch_proc_log` sn=19 `VLM/SKIPPED/비식별 누락 신고 구간…` · backend WARN `withheld — deident report open rawSn=144` | **PASS** |
| `execute(ctx)` (별건 재현) | `trigger?rawSn=128` (128 `'F'`) | 동상 | mock 누계 **9→9(불변)** · sn=13 SKIPPED 적재 | **PASS** |
| `runWithMarking(rawSn, marking)` | (직접 호출 API 없음 — 재개 러너 `VlmWithheldResumeRunner:71` 이 프록시 경유 호출) | 스텝 안 게이트로 차단 | 게이트가 `doSubmit` 공통 본체(:303)에 있어 이 경로도 통과 필수 — 코드상 우회 경로 0 | **PASS**(정적) |
| `run(rawSn)` (재개 러너 :69) | 신고 해소 직후 재개 | 게이트 재판정 후 진행 | 03:26:33 resume → describe submit `d44875d4…` → mock 누계 **15→16** → 콜백 200 → 메타 15건. 게이트 재판정 통과 확인 | **PASS**(실동작) |
| `BatchReprocessService.retry` / Quartz 재큐 | (오케스트레이터 경유 = 위 1행과 동일 경로) | 동상 | 동상 | **PASS**(정적) |

**보류가 실패가 아님(TC-VLM-032) 실측**: rawSn=144 의 `LS_BATCH_PROC_LOG` 에서 VLM SKIPPED 행 `RTRY_NMTM=0`, 진행 행(FRAME_EXTRACT)만 재시도 카운트 증가. 트리거 응답에도 VLM 관련 예외 없음(파이프라인은 VLM 을 지나 FRAME_EXTRACT 까지 진행한 뒤 "마킹 데이터가 없습니다"로 실패 — VLM 이 원인이 아님).

---

## ★ 프레임 2벌 실측 (원본 ≠ 비식별)

기준 영상 rawSn=126 (`LS_DATA_SRC` 65/66/67). 비식별 입력 영상은 **co-locate** 위치
`/app/storage/raw/seed/126/deid/sample-cctv-1080p-mask.mp4` — `STORAGE_DEIDENTIFIED_PATH=/app/storage/deidentified` **밖**이다. 즉 구 `deidentified-path` 단독 판정이었다면 이 영상은 신뢰불가로 거부돼 비식별 벌이 결손됐을 형상이며, 실제로 2벌이 나온 것이 **TC-BATCH-113 회귀 방지의 직접 증거**다.

| frm_no | `SRC_FILE_PATH_NM` (원본) | md5 | `DE_IDNTF_SRC_FILE_PATH_NM` (비식별) | md5 | 동일? |
|--:|---|---|---|---|:--:|
| 0 | `/app/storage/raw/frames/raw/126/frame-0.jpg` | `c155a39c37409fdee2aec1a423c011f5` | `/app/storage/deidentified/frames/deid/126/frame-0.jpg` | `064376b24753aaf61f950a89f66b0e96` | **아니오** |
| 1 | `…/frames/raw/126/frame-1.jpg` | `ec0a85e7457d538c340ee04ac5e64118` | `…/frames/deid/126/frame-1.jpg` | `d3cdcc46de5d07c6c78d063c32fd6be5` | **아니오** |
| 2 | `…/frames/raw/126/frame-2.jpg` | `7f05666339f083e10104722270a7ffcd` | `…/frames/deid/126/frame-2.jpg` | `57576ea03c17ef38cf1e7325771558fe` | **아니오** |

- 경로 분기: `{base}/frames/**raw**/{rawSn}` vs `{base}/frames/**deid**/{rawSn}` (`FfmpegFrameExtractor.resolveSafeOutputDir:377-384`) — 두 base 가 같아도 충돌 없음. **1차 E-41 형태(`dst, dst` 로 두 컬럼 동일)의 재발 없음.**
- export 산출물도 동일: `126/v2/orgnl/0000.jpg`(`c155a39c…`) ≠ `126/v2/deid/0000.jpg`(`064376b2…`).
- 이력(TC-BATCH-112): `LS_DATA_SRC_HSTRY` src 65/66/67 각 **`CREATED` + `DEID_ATTACHED` 2건** 실측.
- 다른 영상 실측도 동일: 132(6프레임)·133(6프레임)·파생 129/130/131 전부 `DE_IDNTF_SRC_FILE_PATH_NM` non-null. **PII fail-open(원본을 비식별본으로 적재) 사례 0건.**

---

## B-7 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-VLM-001 | Step: 마킹 있으면 runWithMarking(최신 1건) | PASS | [정적] `VlmTimeseriesStep.java:218-227` + `BatchStepExecuteTest#VLM_execute_마킹있으면_runWithMarking_호출_stage_VLM` | 근거 드리프트(doc 135-142). `ls_marking` 실측 rawSn 당 1행 |
| TC-VLM-002 | Step: 마킹 없으면 run | PASS | [실동작] rawSn=144 트리거 로그 `describe submit rawSn=144 … hasMarking=false` + `:224-226` | 드리프트(doc 139-141) |
| TC-VLM-003 | enabled=false → SKIPPED + DB 기록 | PASS | [정적] `:269-273` → `BatchStatusService.recordVlmSkipped:58-62` · `VlmTimeseriesStepTest#VLM_비활성일_때_LS_BATCH_PROC_LOG_에_VLM_SKIPPED_행이_사유와_함께_남는다` | 이 환경은 `enabled=true` 라 라이브 재현 불가(컨테이너 재기동 금지). 드리프트(doc 182-186·57-64) |
| TC-VLM-004 | rawSn null → INVALID_INPUT | PASS | [정적] `:261-263`(enabled 체크보다 앞) + 테스트 존재 | 드리프트(doc 174-176) |
| TC-VLM-005 | 영상 미존재 → NOT_FOUND | PASS | [정적] `:276-278`(enabled 체크 이후 ✔) + 테스트 존재 | 드리프트(doc 189-191) |
| TC-VLM-006 | 비식별 경로 없음 → fail-closed | PASS | [실동작] mock 로그 `media duration probed file=sample-cctv-1080p-**mask**.mp4` = 비식별본만 전달 · [정적] `resolveDeidentifiedPath:390-400`(원본 경로 전송 코드 부재) | 드리프트(doc 280-290) |
| TC-VLM-007 | recordIssued 실패 → describe 미호출 | PASS | [정적] `:318-325` try/catch → `EXTERNAL_API_ERROR` abort(제출 코드 :361 이전) + 테스트 존재 | 드리프트(doc 231-238) |
| TC-VLM-008 | recordIssued 독립 커밋(REQUIRES_NEW) | PASS | [정적] `PersistentWebhookIdempotencyLedger.java:57-58` `@Transactional(REQUIRES_NEW)` · [실동작] ledger 행 `6af6d189…/VLM/PROCESSED/144` 생성 | 드리프트(doc 232) |
| TC-VLM-009 | describe 응답 null → EXTERNAL_API_ERROR | **PARTIAL** | [정적] 빈 응답 가드는 `:364-365 switchIfEmpty(Mono.error(EXTERNAL_API_ERROR))` 로 존치하나, **호출자에게 전파되지 않고** `:369-370` 비동기 `onSubmitFailed` 로 흡수 | **B-ISSUE-43** — 논블로킹 전환 부수효과, 케이스 기대값 무효 |
| TC-VLM-010 | 블록 45s ↔ 실효 10s 이중 구조 | **PARTIAL** | [정적] 실효 10s 는 성립(`VlmClient.java:71,108` + `application.yml:633`). **`BLOCK_TIMEOUT=45s`·`.block(...)` 은 코드에 존재하지 않음**(grep 0건, 주석 문구로만 잔존 `:70,348`) | **B-ISSUE-42** — UNCERTAINTIES #13 의 "이중 구조" 전제 자체가 갱신 대상 |
| TC-VLM-011 | 위탁 성공 시 마킹 PENDING→VLM_REQUESTED | **PARTIAL** | [정적] 전이는 존재하나 시점이 반전 — **제출 전 선커밋**(`:337` → `VlmMarkingTxService.persistVlmRequested`, REQUIRES_NEW 별도 빈). 성공 여부와 무관하게 먼저 전이 | **B-ISSUE-44** — 콜백 선행 레이스 폐쇄를 위한 의도된 변경. 기대값("위탁 성공 시") 무효 |
| TC-VLM-012 | retry — 이미 전이된 마킹 no-op | PASS | [정적] `VlmMarkingTxService.persistVlmRequested` → `LsMarking.markVlmRequested()` PENDING 한정 · `VlmTimeseriesStepMarkingTest#retry_재실행_시_이미_VLM_COMPLETED_마킹은_VLM_REQUESTED로_역행하지_않는다` | 드리프트(doc 300-308) |
| TC-VLM-013 | 콜백 URL 고정 base(SSRF 차단) | PASS | [실동작] mock 로그 `describe accepted … callback_url=http://klid-backend:8080/api/v1/vlm/callback`(= `WEBHOOK_CALLBACK_BASE_URL` 고정값) · [정적] `resolveCallbackUrl:403-411`(사용자 입력 미반영) | 드리프트(doc 311-319). 전용 단위테스트는 여전히 부재(B-ISSUE-46) |
| TC-VLM-014 | 미발급 request_id → UNAUTHORIZED | PASS | [실동작] `POST /v1/vlm/callback` `request_id=11111111-2222-4333-8444-555555555555` → **401** `{"errorCode":"UNAUTHORIZED","message":"발급되지 않은 request_id 입니다."}` | |
| TC-VLM-015 | 이미 PROCESSED → 멱등 스킵 | PASS | [실동작] `8cc179fb…`(rawSn=126, PROCESSED) 재전송 → **200 `applied:false`**, `select count(*) … meta_vl like '%REPLAY%'` = **0** (오염 없음) | |
| TC-VLM-016 | rawSn 매핑 없음 → UNAUTHORIZED | PASS | [정적] `VlmResultService.java:83-88` + `VlmResultServiceTest#발급됐으나_rawSn_매핑_없으면_UNAUTHORIZED_거부` | 라이브 재현 불가(해당 원장 행이 전부 PROCESSED 라 :77 멱등 스킵이 선행) |
| TC-VLM-017 | 미지 status → INVALID_INPUT(멱등 미마킹) | PASS | [실동작] `status="done"` → **400** `status 는 completed\|failed 중 하나여야 합니다`(DTO `@Pattern` 1차) · [정적] 서비스 2차 방어 `:94-102`(PROCESSED 미마킹) | |
| TC-VLM-018 | failed → error 기록+VLM_FAILED+멱등 | PASS | [정적] `:105-109` + `handleFailed:210-232` + `VlmResultServiceMarkingTest#failed_콜백_수신시_VLM_REQUESTED_마킹을_VLM_FAILED로_전이_고착해제` | 드리프트(doc 168-186 → 210-232) |
| TC-VLM-019 | completed+영상 미존재 → NOT_FOUND | PASS | [정적] `:112-116` | **전용 테스트 없음**(B-ISSUE-46) |
| TC-VLM-020 | 한 콜백 내 중복 metaKey → INVALID_INPUT | PASS | [정적] `dedupSegments:238-250` + `VlmResultServiceTest#한_콜백_내_중복_구간_metaKey는_400_거부_및_조용한_덮어쓰기_없음` | 드리프트(doc 192-204 → 238-250) |
| TC-VLM-021 | META upsert — 신규만 검수큐 PENDING | PASS | [실동작] 144 1회차 `new=15 updated=0` → `ls_data_meta_review` 15행 `PENDING/VLM/AI_SERVER`. 2회차 재위탁 `new=0 updated=15` → 검수행 **15 유지(중복 0)** | 케이스 기대 그대로 |
| TC-VLM-022 | 마킹 VLM_REQUESTED→VLM_COMPLETED | PASS | [실동작] `ls_marking` 4/126·5/132·6/133 전부 `VLM_COMPLETED` · [정적] `:158-161` + `VlmResultServiceMarkingTest` | 144 는 마킹 없어 `markingsTransitioned=0`(정상) |
| TC-VLM-023 | 원자성 — 중간 실패 시 PROCESSED 롤백 | PASS | [정적] 단일 `@Transactional`(`:63`) + `markProcessedInTx`(REQUIRED, ledger `:115-116`) + `VlmResultServiceTest#검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능` | |
| TC-VLM-024 | 동일 request_id 동시 콜백 직렬화 | PASS | [정적] `lookupForProcessing`(`:69`, ledger `:136-137` 비관적 락) | **동시성 IT 없음**(증강 채널엔 `AugmentCallbackIdempotencyIT`·`GenAiCallbackRollupConcurrencyIT` 가 있는데 VLM 만 비대칭) — B-ISSUE-46 |
| TC-VLM-025 | 로그 마스킹(CR/LF/tab) | PASS | [정적] `VlmResultService.safe():252-255` `[\r\n\t]→_` · `VlmClient.safeForLog():176-179` | **전용 테스트 없음**(B-ISSUE-46) |
| TC-VLM-030 | 콜백 무인증 — isIssued 게이트 차단 | PASS | [실동작] 무토큰·무서명 콜백이 필터를 통과해 컨트롤러에 도달하나 발급 게이트에서 **401** | ⚠ 이 환경 `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none`, rate limit 은 실패 5회/분 기준이라 400/401 40연발로는 미발화(관측). A 클러스터 축 |
| TC-VLM-031 | ★신고 구간 위탁 보류 — 경로 해석 직전 게이트 | PASS | [실동작] 144·128 양쪽 mock describe 누계 불변 + SKIPPED 사유 적재 · [정적] `:303-307`(→ `:309 resolveDeidentifiedPath` 이전) | 드리프트(doc 216-220,222) |
| TC-VLM-032 | 보류는 실패가 아니다 | PASS | [실동작] VLM SKIPPED 행 `RTRY_NMTM=0`, 예외 미발생, 파이프라인이 다음 단계까지 진행 · [정적] `:303-307` 은 예외 대신 `skipped` 반환 | 드리프트(doc 206-220) |
| TC-VLM-033 | 게이트 조회 실패는 fail-closed | PASS | [정적] `:303` 에 try/catch 없음 → `DeidentReportGate:66-71` 예외 그대로 전파(주석 `:63-64` 도 동일 규약 명시) | 드리프트(doc 215-216) |
| TC-VLM-034 | 해소 시 재위탁 배선 | **FAIL** | [실동작] 배선·이벤트는 발화하나(`VlmResumeBridge:34-39` → `resumeAsync`) **멱등 조건이 항상 참이라 재위탁이 실행되지 않음**: `resume skipped — timeseries meta already present rawSn=144 count=6`(count=6 은 ingest 시점 `video.*` 기술메타) | **B-ISSUE-41 (CRITICAL)** — A/B 대조로 확증 |
| TC-VLM-035 | 재위탁 멱등: 시계열 메타 ≥1건이면 skip | **PARTIAL** | [실동작] skip 동작 자체는 성립하나 판정이 **시계열 메타로 한정되지 않음**(`VlmWithheldResumeRunner:94` → `LsDataMetaRepository.countByRawSn:29` = `RAW_SN` 전건 카운트) | 동일 이슈 **B-ISSUE-41** |
| TC-VLM-036 | 재위탁 조건: 보류 사유가 신고일 때만 | PASS | [정적] `RESUMABLE_SKIP_REASONS`(`:150-152`)에 `SKIP_REASON_DISABLED` **미포함** → 재개 대상 아님 · `BatchStatusService.isStageSkippedWithAnyReason:114-119` | 드리프트: 단일 상수 → **4종 목록**(신고보류·제출실패·ACK미수신·콜백미수신)으로 확장. 케이스의 "단일 원천은 SKIP_REASON_DEIDENT_REPORT" 서술 갱신 필요 |
| TC-VLM-037 | 재위탁 실패는 삼킴(best-effort) | PASS | [정적] `VlmWithheldResumeRunner:73-78` `catch(RuntimeException) → log.warn(클래스명만)` + `VlmWithheldResumeRunnerTest#재위탁_실패는_삼켜서_해소_트랜잭션에_영향을_주지_않는다` | 드리프트(doc 71-76) |
| TC-VLM-038 | recordVlmTimeseriesResult = REQUIRES_NEW | PASS | [정적] `BatchStatusService.java:149-156` `@Transactional(REQUIRES_NEW)` · [실동작] `ls_batch_proc_log` `RESP_PAYLOAD_CN={"requestId":…,"status":…}` 적재(sn 4/5/6) | 드리프트(doc 109-116). 호출자가 `VlmSubmitOutcomeRecorder.onAccepted`(파이프라인 스레드 밖)로 바뀌어 REQUIRES_NEW 필요성은 오히려 강해짐 |
| TC-VLM-039 | recordVlmSkipped 는 REQUIRED 유지(의도) | PASS | [정적] `:58-62` REQUIRED 유지 + 신규 `recordVlmSkippedInNewTx:78-82`(REQUIRES_NEW)를 **별도 메서드로 분리**, 공통 로직은 비트랜잭션 private 헬퍼 `saveVlmSkipRow:85-88` | 드리프트(doc 57-64,104-108). 자기호출 회피 설계 확인 |
| TC-VLM-040 | VLM 스텝 트랜잭션 경계 = execute | PASS | [정적] `:218-220` `@Transactional(REQUIRES_NEW)` 쓰기가능, `:223/:225` 는 자기호출이라 중첩 없음 · `BatchStepExecuteTest` 2건 | 드리프트(doc 133-142,150-168). ⚠ 프록시 경유(재개 러너)로 `run()` 이 직접 호출되면 `readOnly=true` 경계가 개시된다 — **B-ISSUE-45** 참조 |

---

## B-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-100 | execute: marks 비면 INVALID_INPUT | PASS | [실동작] rawSn=144/128 트리거 → `ls_batch_proc_log` `FRAME_EXTRACT/FAILED/마킹 데이터가 없습니다. rawSn=144` · [정적] `FfmpegFrameExtractor.java:133-136` | 근거 라인 일치 |
| TC-BATCH-101 | execute: 추출 0건 → INTERNAL_ERROR | PASS | [정적] `:144-147` + `BatchStepExecuteTest#FRAME_execute_추출결과_0건이면_INTERNAL_ERROR` | 일치 |
| TC-BATCH-102 | 영상 메타 blank → INVALID_INPUT | PASS | [정적] `:177-179` | 일치 |
| TC-BATCH-103 | 비식별 미완료 → INVALID_INPUT | PASS | [정적] `:185-188` `!"Y".equals(deIdntfYn)` + `FfmpegFrameExtractorTest#비식별_미완료_영상은_프레임추출에서_차단된다` | 일치. `'F'`(신고/실패)도 여기서 차단 — 신고 구간 배치는 VLM 보류 후 이 단계에서 FAILED 로 끝난다(설계 정합) |
| TC-BATCH-104 | 원본 미존재 → INVALID_INPUT | PASS | [정적] `:190-193` | 일치 |
| TC-BATCH-105 | ★비식별 경로가 허용 base 전부의 밖 → RAW only(fail-closed) | PASS | [정적] `:206-213` WARN 에 `rawSn` 만 출력(경로 원문 미노출, CWE-209) + 원본 폴백 코드 부재 · 판정은 `isUnderAllowedDeidBase:316-333` → `readableDeidVideoBases` · `FfmpegFrameExtractorTest#MEDsec_비식별경로가_base밖이면_fail_closed로_RAW만추출_비식별경로_미저장`, `#결함2_경로순회…`, `#결함2_다른영상의_co_locate_디렉터리_경로는_거부된다_rawSn_교차차단` | 일치 |
| TC-BATCH-106 | 비식별 경로 null/파일 부재 → RAW only | PASS | [정적] `:216-225`(파일 부재 `maskName` 해시로 마스킹 / 경로 null 분기 별도) + `#V2_마킹_비식별_영상_없으면_RAW_만_graceful` | 일치 |
| TC-BATCH-107 | 정상: 2벌 추출+deid 경로 INSERT 시점 저장 | PASS | [실동작] 126/132/133 전 프레임 `DE_IDNTF_SRC_FILE_PATH_NM` non-null, export v1/v2 `deid/` 벌 실재 · [정적] `:260-271` 비식별 프레임을 `create()` **이전에** 기록하고 6-arg `LsDataSrc.create(...)` 에 deidPath 포함 · `FfmpegFrameExtractorDeidPersistIT` 2건 | 일치. 1차 "export 항상 PARTIAL" 회귀 없음 |
| TC-BATCH-108 | seekMillis=round(frameIndex×1000/fps), pin fps 우선 | PASS | [정적] `:248 Math.round(mark.frameIndex()*1000.0/fps)` · `:234 effectiveFps(pinnedFps,…)` · `:393-398` · 테스트 6건(`M3_seekMillis…`, `TOCTOU_마킹이pin한30fps…`, `60fps_마킹pin…`) · [실동작] `ls_marking.fps=29.97002997002997` 가 pin 값으로 저장돼 있음 | 경미 드리프트(doc 266 → 실제 248) |
| TC-BATCH-109 | pin fps null/비정상 → resolveFps 폴백 | PASS | [정적] `:393-398`(null/NaN/Inf/≤0 → `fpsResolver.resolveFps`, 미상 시 30.0) + `#마킹pin이_null이면_resolveFps로_폴백한다_하위호환` | 일치 |
| TC-BATCH-110 | 출력 경로 순회 방어(CWE-22) | PASS | [정적] `resolveSafeOutputDir:377-384` normalize + `startsWith(base)` + `#경로순회_가드_raw와_deid_세그먼트_모두_base_하위로_정규화되어_통과한다` | 일치 |
| TC-BATCH-111 | frames raw/deid 서브세그먼트 분기(충돌 없음) | PASS | [실동작] `/app/storage/raw/frames/raw/126/…` vs `/app/storage/deidentified/frames/deid/126/…`, md5 상이 · [정적] `:377-384` `FrameKind` 세그먼트 · `#동일_base_주입돼도_원본과_비식별_프레임_경로가_달라_디스크_덮어쓰기_없음` | 일치 |
| TC-BATCH-112 | 이력: deid 채운 경우 CREATED+DEID_ATTACHED 2건 | PASS | [실동작] `ls_data_src_hstry` src 65/66/67 각 2행(`CREATED`,`DEID_ATTACHED`) · [정적] `:272-276` | 일치 |
| TC-BATCH-113 | ★co-locate 산출 비식별 영상 채택 | PASS | [실동작] 126 의 비식별 입력이 `/app/storage/raw/seed/126/deid/…-mask.mp4`(deid base 밖)인데도 비식별 벌 3장 실재 + `DE_IDNTF_SRC_FILE_PATH_NM` 채워짐 · [정적] `:316-333` → `VideoArtifactRootResolver.readableDeidVideoBases:337-346` · `#결함2_원본옆_co_locate_비식별영상도_채택되어_비식별프레임경로가_저장된다` | 구 결함(항상 RAW only) 회귀 없음 |
| TC-BATCH-114 | 구 위치도 계속 허용 — 2-way allowlist | PASS | [정적] `readableDeidVideoBases:337-346` 가 `deidentifiedBase` ∪ `readableDeidVideoDirs:308-320`(구 `{deid_base}/videos/{rawSn}` + co-locate) 를 모두 반환, 전략 플래그와 무관 · `#CWE59보강_후에도_심링크아닌_정상_co_locate_비식별영상은_여전히_채택된다` | 실동작 사례는 이 환경에 없음(파생 129~131 의 비식별 영상만 구 위치에 있으나 그 프레임은 프레임추출기가 아닌 파생 확정 경로 산출물) |
| TC-BATCH-115 | 심링크 방어: 대상 파일이 원본을 가리키면 거부 | PASS | [정적] `underBaseWithRealPath:356-367` — `verifyRealPathUnder` 실패를 **예외가 아니라 false** 로 접어 기존 RAW only 분기 흡수 · `VideoArtifactRootResolver.verifyRealPathUnder:458-466` · `#CWE59_co_locate_허용경로의_심링크가_원본영상을_가리키면_거부되고_RAW만_추출된다` | 일치 |
| TC-BATCH-116 | 심링크 방어: 중간 세그먼트 심링크도 거부 | PASS | [정적] `realOrNearest:474-497`(대상 부재 시 최근접 실재 조상 `toRealPath()` 후 잔여 세그먼트 재부착, 해석 불가는 FORBIDDEN) · `#CWE59_경로중간_세그먼트가_심링크로_base밖을_가리키면_거부되고_RAW만_추출된다` | 일치 |
| TC-BATCH-117 | 리졸버 미주입(단위 수동 생성) → 구 동작 | PASS | [정적] `:316-319` `artifactRootResolver == null` → `underBaseWithRealPath(deidPath, baseDeidPath)` 단독 판정(허용 범위 축소 = fail-secure) | 일치 |
| TC-BATCH-118 | 후보 도출 예외 시 구 동작 폴백 | PASS | [정적] `:320-326` `catch(RuntimeException)` → 구 동작 · 리졸버측도 이중 폴백(`:340-344`, `:314-318`) | 일치 |

---

## 근거 드리프트 / self-fill 점검

### 근거 `file:line` 드리프트

| 대상 | 건수 | 원인 | 조치 필요 |
|---|--:|---|---|
| `VlmTimeseriesStep.java` (TC-VLM-001~013, 031~033, 040) | **17** | `862ca6d8`(Phase C-1 논블로킹 제출) 로 파일이 178줄 → 417줄로 재작성. 케이스가 가리키는 라인이 전부 다른 코드 | 카탈로그 근거 일괄 갱신 |
| `BatchStatusService.java` (TC-VLM-003, 036, 038, 039) | **4** | `recordVlmSkippedInNewTx` · `isStageSkippedWithAnyReason` 신설로 라인 이동 | 갱신 |
| `VlmWithheldResumeRunner.java` (TC-VLM-034~037) | **3** | 재개 사유 4종화로 라인 이동(54-70→57-79, 79-93→88-101, 80-84→88-90) | 갱신 |
| `VlmResultService.java` (TC-VLM-018, 020, 022) | **3**(경미) | `markingsInScope`/`isCreatedAfterSubmit` 신설로 `handleFailed`·`dedupSegments` 가 뒤로 밀림 | 갱신 |
| `FfmpegFrameExtractor.java` (TC-BATCH-108) | **1**(경미) | seekMillis 계산 라인 266→248 | 갱신 |
| `VideoArtifactRootResolver.java` (TC-BATCH-114) | **1**(경미) | `readableDeidVideoDirs` 308-327→308-320, `readableDeidVideoBases` 337-348→337-346 | 갱신 |

> **의미 있는 드리프트(단순 라인 이동이 아닌 것)**: TC-VLM-009(예외 전파 → 비동기 흡수) · TC-VLM-010(45s 블록 삭제) · TC-VLM-011(전이 시점 반전) · TC-VLM-036(사유 단일 → 4종). 이 4건은 라인만 고쳐선 안 되고 **기대결과 문장 자체**를 갱신해야 한다.

### self-fill 점검

| 점검 항목 | 결과 | 근거 |
|---|:--:|---|
| VLM 시계열 메타를 BE 가 자체 생성하는가 | **아니오** | rawSn=144 에서 A/B 확증 — 위탁 보류(mock 미호출) 구간엔 시계열 메타 **0건**, 콜백 수신 직후에만 15건 생성. `VlmResultService` 는 `req.results()` 외 소스가 없다(`:129-140`) |
| 위탁 media.path 가 원본으로 대체되는가 | **아니오** | mock 프로브 로그가 `sample-cctv-1080p-**mask**.mp4`(비식별본). `resolveDeidentifiedPath:390-400` 에 원본 폴백 코드 자체가 없음 |
| 목 응답 문구가 실제로 외부에서 온 것인가 | **예** | 적재된 메타 문안이 mock 시뮬레이터 템플릿(`- 장소: … - 날씨: … - 상황: … - 환경: … - 심각성: N/10점`)이며 BE 코드에 이 문자열 0건 |
| 프레임 비식별 벌이 원본 복사인가 | **아니오** | md5 3쌍 전부 상이(mock KPST 가 워터마크를 실제로 합성한 `-mask.mp4` 에서 추출) |
| 위양성 테스트 | **1건 발견** | `VlmWithheldResumeRunnerTest#resumesWithheldSubmit` 이 `countByRawSn`=0 을 스텁 — 프로덕션에서 성립 불가능한 전제(B-ISSUE-41) |

### 검증 중 발생시킨 환경 변화 (정직 기록)

| # | 무엇을 | 왜 | 되돌림 |
|---|---|---|---|
| 1 | `mng_clip_master`/`mng_clip_evnt_lst` 에 `DEV-CLIP-9301` 1건 INSERT | 다른 에이전트가 쓰는 126~143 을 건드리지 않고 전용 검증 영상을 확보 (pipeline-drive §5 와 동일 방식, 관제 소유 입력 픽스처) | 유지(추가분) — 적재 결과가 **rawSn=144** |
| 2 | rawSn=144 로 배치 트리거 5회 · 비식별 신고 2회 · resolve 2회 | 게이트 보류·재개 경로 실동작 확인 | 최종 상태: `DATA_STTS_CD=FAILED`(마킹 없어 FRAME_EXTRACT 실패), `DE_IDENT_YN='Y'`, 시계열 메타 15건 |
| 3 | rawSn=144 의 `ls_data_meta` 21행 + `ls_data_meta_review` 15행 DELETE | **B-ISSUE-41 의 A/B 대조 실험** — "메타 0건일 때 재개가 실제로 동작하는가"를 분리 검증하기 위함. 삭제 대상은 **내가 만든 영상의 데이터만** | 이후 재개로 시계열 15건 재생성(`video.*` 6건은 미복구) |
| 4 | `docker exec klid-backend touch …/144/deid/…-mask.mp4` | resolve 가드(`DeidentReportService:568-580` — 비식별 산출물 mtime > 신고시각)를 통과시키기 위한 "외부 재비식별" 모사 | 내 영상 파일 1개 mtime 만 변경 |
| 5 | `/v1/vlm/callback` 보안 프로브 4회(미발급/재전송/미지 status/비UUID) | 전부 거부 경로 — DB 오염 0건 확인함 | 해당 없음 |

- **컨테이너 재시작·재빌드 0회. 소스/설정/테스트 파일 수정 0건. 빌드·테스트 실행 0회.** 신규 작성 파일은 본 문서 1개.

---

## 이슈 상세

### [B-ISSUE-41] TC-VLM-034 / TC-VLM-035 — 보류된 VLM 위탁의 **재개가 영구 no-op**: 멱등 조건이 시계열 메타가 아니라 `LS_DATA_META` **전건**을 세어 ingest 시점 `video.*` 기술메타에 항상 걸린다

- **심각도**: **CRITICAL** (데이터 영구 결손 + 유일 복구 경로 소실)
- **기대 동작(기대효과)**: `CLAUDE.md` 구속 정책 — "보류는 스스로 재개되지 않으므로 해소 시 `DeidentGateReopenedEvent` → `VlmResumeBridge` → `VlmWithheldResumeRunner` 가 이 SKIPPED 기록을 근거로 **재위탁**한다(멱등 조건: **시계열 메타 0건**). 이 배선이 없으면 시계열 메타가 영구 결손된다."
- **현재 동작(이슈 내용)**: 멱등 조건이 "시계열 메타 0건"이 아니라 **"그 영상의 `LS_DATA_META` 행이 0건"** 으로 구현돼 있다.

  `backend/src/main/java/kr/co/cudo/authoring/batch/runner/VlmWithheldResumeRunner.java:88-101`
  ```java
  private boolean isWithheld(Long rawSn) {
      boolean withheldLogged = batchStatusService.isStageSkippedWithAnyReason(
              rawSn, BatchStage.VLM, VlmTimeseriesStep.RESUMABLE_SKIP_REASONS);
      if (!withheldLogged) return false;
      long metaCount = metaRepository.countByRawSn(rawSn);   // ← 전건 카운트
      if (metaCount > 0) {
          log.info("[VlmResume] resume skipped — timeseries meta already present rawSn={} count={}", rawSn, metaCount);
          return false;
      }
      return true;
  }
  ```
  `LsDataMetaRepository.java:29` 의 `long countByRawSn(Long rawSn)` 에는 `META_KEY` 필터가 없다. 그런데 같은 `LS_DATA_META` 테이블에는 **적재(ingest) 시점에 `VideoMetaService`(`:62,72-77` — `video.fps/codec/bit_rate/duration_ms/filesize/resolution`)가 ffprobe 실측 6건을 AFTER_COMMIT @Async 로 먼저 써 넣는다.** 즉 **정상 적재된 모든 영상은 VLM 이 한 번도 돌기 전에 이미 `countByRawSn >= 1`** 이다 → `isWithheld` 는 언제나 false → **재위탁이 실행되지 않는다.**

  **실동작 A/B 확증 (rawSn=144, 내가 생성한 전용 영상)**

  | | 조건 | resolve 후 로그 | mock `videovlm/describe` 누계 | 시계열 메타 |
  |---|---|---|---|---|
  | **A** (실환경 그대로) | `LS_DATA_META` = `video.*` 6건 | `[VlmResume] resume skipped — timeseries meta already present rawSn=144 **count=6**` | 13 → **13 (불변)** | **0건 (영구 결손)** |
  | **B** (메타 0으로 비운 대조군) | `LS_DATA_META` = 0건 | `[VlmResume] deident report resolved — resuming withheld VLM submit rawSn=144` → `describe submit … d44875d4…` | 15 → **16 (재위탁 발생)** | **15건 생성** |

  A 와 B 의 유일한 차이는 `video.*` 6건의 존재 여부다. 재개 메커니즘 자체(브릿지·이벤트·러너·게이트 재판정)는 **전부 정상 동작**하며, 막고 있는 것은 이 카운트 범위 하나다.

  **테스트가 잡지 못한 이유(위양성)**: `backend/src/test/java/kr/co/cudo/authoring/batch/runner/VlmWithheldResumeRunnerTest.java`
  ```java
  @DisplayName("신고_보류됐던_VLM_위탁은_해제_후_재위탁된다")
  void resumesWithheldSubmit() {
      stubWithheld(true);
      when(metaRepository.countByRawSn(RAW_SN)).thenReturn(0L);   // ← 프로덕션에서 성립 불가능한 전제
      ...
      verify(vlmTimeseriesStep).run(RAW_SN);
  }
  ```
  Mockito 스텁이 DB 현실(ffprobe 메타 선적재)을 재현하지 않아 GREEN 이다. `LsDataMetaRepository` 를 진짜로 붙인 IT 는 없다.
- **재현/확인 경로**:
  ```bash
  BASE=http://localhost:18081/api; RT=<REVIEWER 토큰>
  # 1) 비식별 완료(MARKING_READY) 영상 하나를 신고 → 'F'
  curl -s -X POST $BASE/v1/videos/144/deident-report -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"reason":"..."}'
  # 2) 배치 트리거 → VLM 보류(SKIPPED) 기록, 외부 호출 0
  curl -s -X POST "$BASE/v1/dev/batch/trigger?rawSn=144" -H "Authorization: Bearer $RT"
  docker logs klid-mock-server | grep -c videovlm/describe      # 증가 없음
  # 3) 해소 → 재개가 skip 되는 것을 확인
  docker exec klid-backend touch /app/storage/raw/seed/144/deid/sample-cctv-1080p-mask.mp4
  curl -s -X POST $BASE/v1/deident-reports/5/resolve -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"note":"..."}'
  docker logs klid-backend --since 30s | grep VlmResume
  #   → [VlmResume] resume skipped — timeseries meta already present rawSn=144 count=6
  ```
  ```sql
  -- 어떤 영상이 이 조건에 걸리는가 = ingest 되어 video.* 메타를 가진 전부
  select raw_sn, count(*) filter (where meta_key like 'video.%') as tech,
         count(*) filter (where meta_key not like 'video.%') as timeseries
  from ls_data_meta group by raw_sn order by raw_sn;
  ```
- **영향**:
  1. **비식별 누락 신고 구간에 배치가 돈 영상은 시계열 메타(`LS_DATA_META`)가 영구 결손**된다. 보류는 실패 행을 남기지 않으므로 `BatchRetryQueue`·실패 회수기 어느 쪽도 집지 않고(설계상 그렇게 만든 것), 재개가 유일한 복구 경로인데 그 경로가 닫혀 있다.
  2. 파급은 신고 경로에 국한되지 않는다 — `RESUMABLE_SKIP_REASONS`(`VlmTimeseriesStep:150-152`)의 **나머지 3종**(비동기 제출 실패 `SKIP_REASON_SUBMIT_FAILED`, ACK 미수신 `SKIP_REASON_ACK_MISSING`, 콜백 창 만료 `SKIP_REASON_CALLBACK_MISSING`)도 **같은 `isWithheld` 를 통과해야 재개**되므로, Phase C-1 논블로킹 전환이 도입한 **미결 회수(`VlmSubmitPendingSweeper`) 전체가 함께 무력화**된다. 즉 "ACK 를 못 받았거나 콜백이 안 온 위탁"도 회수되지 않는다.
  3. 결손된 메타는 `LS_DATA_META_REVIEW` 검수큐에도 진입하지 않아 REVIEWER 가 "메타가 없다"는 사실 자체를 화면에서 알 수 없고, 검수 승인 시 export `event` 블록과 `V_COMPLETED_META` 도 비어 나간다.
  4. 자동 회귀 가드는 위양성이라 이 상태가 조용히 유지된다.
- **수정 방향(제안)**: `countByRawSn` 대신 **시계열 메타만 세는 카운트**를 쓴다 — 예: `LsDataMetaRepository` 에 `countByRawSnAndMetaKeyNotStartingWith(rawSn, VideoMetaService.KEY_PREFIX)` 또는 `countTimeseriesByRawSn`(구간키 패턴 `^\d+-\d+$` / `META_KEY NOT LIKE 'video.%'`) 를 신설하고 `VlmWithheldResumeRunner:94` 를 그 쪽으로 바꾼다. 판정 축의 단일 원천은 `VideoMetaService.KEY_PREFIX`(이미 public 상수)를 재사용해 문자열을 재구현하지 않는다. 회귀 가드는 **Mockito 스텁이 아니라 실제 리포지토리를 붙인 IT**(ingest 메타 6건이 있는 상태에서 재개가 발생하는지)로 세운다. ⚠ **구현하지 않는다.**

### [B-ISSUE-42] TC-VLM-010 — `BLOCK_TIMEOUT=45s` 가 코드에서 사라졌다(논블로킹 전환). 케이스·UNCERTAINTIES #13 의 "이중 구조" 전제가 무효

- **심각도**: MEDIUM (문서·기대값 정합 / 판정 기준 오염)
- **기대 동작(기대효과)**: 케이스와 `UNCERTAINTIES.md #13` 은 "`BLOCK_TIMEOUT=45s` 는 재시도 누적 상한, 단일 호출 실효 타임아웃은 `vlm.client.timeout-seconds`(10s) — **둘 다 초과 시 EXTERNAL_API_ERROR**"를 기대한다.
- **현재 동작(이슈 내용)**: Phase C-1(`862ca6d8`)에서 `.block(45s)` 가 **제거**됐다. `VlmTimeseriesStep` 에 `BLOCK_TIMEOUT` 상수도, `block(` 호출도 없다(grep 0건 — 주석 `:70`, `:348` 에 "구 코드는 `.block(45s)`" 라는 서술로만 잔존).
  ```java
  // VlmTimeseriesStep.java:361-377 — subscribe 만 하고 즉시 반환
  vlmClient.submitTimeseries(req)
          .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
          .subscribe(resp -> …onAccepted(…), err -> …onSubmitFailed(…));
  return VlmTimeseriesResponse.submitted(requestId);   // ACK 도 기다리지 않는다
  ```
  현재 유일한 시간 제약은 `VlmClient.java:71`(`vlm.client.timeout-seconds:10`, `application.yml:633` 실값 10) 이 `:108 .timeout(timeout)` 으로 거는 **10초**이고, 그 위에 Retry(max-attempts 3, wait 1s, ×2 backoff)·CircuitBreaker 가 얹힌다(`application.yml:500-507,547-553`). 파이프라인 스레드는 **어느 값에도 블록되지 않는다.**
  4xx 비재시도는 `:106 onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)` → `NonRetryableExternalException` 이 retry/CB `ignore-exceptions` 에 등록돼 정상 배선(케이스 표엔 없으나 확인함).
- **재현/확인 경로**:
  ```bash
  grep -rn "BLOCK_TIMEOUT\|\.block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java   # 0건(주석 제외)
  grep -n "timeout-seconds" backend/src/main/resources/application.yml                                              # 633: timeout-seconds: 10
  ```
- **영향**: 검증자가 "45s 안에 안 끝나면 EXTERNAL_API_ERROR"를 기준으로 판정하면 **존재하지 않는 동작을 근거로 PASS/FAIL 을 매긴다.** 또 운영 관점에서 "느린 벤더가 배치 스레드를 45초 붙잡는다"는 리스크 서술도 더는 사실이 아니다(대신 새 리스크는 "ACK 를 못 받은 위탁의 회수" 쪽으로 옮겨갔고 그 회수가 B-ISSUE-41 로 막혀 있다).
- **수정 방향(제안)**: TC-VLM-010 의 기대결과를 "**단일 실효 타임아웃 10s**(`vlm.client.timeout-seconds`) + Retry 3회/exp backoff + CircuitBreaker. 초과·실패는 호출자에게 전파되지 않고 `VlmSubmitOutcomeRecorder.onSubmitFailed` → `SKIP_REASON_SUBMIT_FAILED` 감사 행으로 기록"으로 재작성하고, `UNCERTAINTIES.md #13` 의 "이중 구조" 문구를 폐기한다. ⚠ **구현하지 않는다.**

### [B-ISSUE-43] TC-VLM-009 — describe 실패·빈 응답이 **호출자에게 전파되지 않아** 배치는 성공으로 마감된다(논블로킹 전환의 관측 가능성 손실)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스 — "describe 응답 null → `EXTERNAL_API_ERROR`".
- **현재 동작(이슈 내용)**: 가드 자체는 살아 있으나 **예외가 비동기 경로로 흡수**된다.
  ```java
  // VlmTimeseriesStep.java:362-377
  .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, "VLM describe 응답이 비어있습니다 rawSn=" + rawSn)))
  .subscribe(resp -> …,
             err  -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                        () -> outcomeRecorder.onSubmitFailed(rawSn, markingSn, err)));
  …
  return VlmTimeseriesResponse.submitted(requestId);
  ```
  `doSubmit` 은 항상 `submitted` 를 돌려주므로 `BatchOrchestrator` 는 VLM 단계를 **성공으로 간주**하고 파이프라인을 계속 진행해 `COMPLETED` 로 마감한다. 실패 사실은 `VlmSubmitOutcomeRecorder.onSubmitFailed` 가 `LS_BATCH_PROC_LOG` 에 `VLM/SKIPPED/SKIP_REASON_SUBMIT_FAILED` 감사 행으로만 남긴다(상태 강등은 의도적으로 금지 — 주석 "★ 상태 강등 금지").
  설계 의도(지각 실패가 완료된 파이프라인을 FAILED 로 역행시키지 않게)는 타당하나, **회수 경로가 B-ISSUE-41 로 막혀 있어** 현재는 "실패했고, 기록만 남았고, 아무도 다시 시도하지 않는다"가 된다.
- **재현/확인 경로**: mock 을 오류 응답으로 만들 수 없어 이번 회차에 실동작 재현은 못 했다(컨테이너 재기동 금지). 정적으로는 위 스니펫 + `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다` / `#외부_호출_실패는_파이프라인_스레드로_전파되지_않고_완료핸들러에_위임된다` 가 이 동작을 **기대값으로 고정**하고 있다.
- **영향**: (a) 케이스 표의 "EXTERNAL_API_ERROR" 기대값이 무효. (b) 외부 VLM 이 계속 실패해도 배치는 전건 `COMPLETED` 로 보이고 시계열 메타만 조용히 비는 상태가 된다 — 운영 알림·대시보드가 `LS_BATCH_PROC_LOG` 의 `SKIPPED` 행을 별도로 보지 않는 한 무증상이다(CWE-778 계열 관측 실패).
- **수정 방향(제안)**: ①TC-VLM-009 기대값을 "`EXTERNAL_API_ERROR` 가 `onSubmitFailed` 로 흡수되어 `SKIP_REASON_SUBMIT_FAILED` 감사 행이 남는다"로 갱신. ②운영 축에서는 `LS_BATCH_PROC_LOG(VLM, SKIPPED)` 잔존 건수를 메트릭/알림으로 노출(파이프라인 상태를 강등하지 않으면서 가시화). ⚠ **구현하지 않는다.**

### [B-ISSUE-44] TC-VLM-011 — 마킹 `PENDING→VLM_REQUESTED` 전이 시점이 "위탁 성공 후"에서 "**제출 전 선커밋**"으로 반전됐다(기대값 무효)

- **심각도**: LOW (동작은 의도된 개선, 문서 정합 문제)
- **기대 동작(기대효과)**: 케이스 — "위탁 **성공 시** 마킹 PENDING→VLM_REQUESTED, `persistMarkingTransition` 후 save".
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep:337` 이 **제출 이전에** 별도 빈을 프록시 경유로 호출해 독립 커밋한다.
  ```java
  markingTxService.persistVlmRequested(marking);   // REQUIRES_NEW, 제출 전 선커밋
  Long markingSn = marking == null ? null : marking.getMarkingSn();
  …
  vlmClient.submitTimeseries(req).subscribe(...);  // 이후에 제출
  ```
  `persistMarkingTransition` 메서드는 존재하지 않고 `VlmMarkingTxService.persistVlmRequested`(REQUIRES_NEW) / `markVlmFailedIfRequested`(제출 확정 실패 시 보상)로 분리됐다. 근거는 **콜백 선행 레이스 폐쇄** — 논블로킹 제출에서 벤더 콜백이 ACK 보다 먼저 도착하면 수신부가 전이 대상을 못 찾고, 그 뒤 스텝이 올려버려 `VLM_REQUESTED` 영구 고착이 된다. 수신부도 `markingsInScope`(`VlmResultService:186-208`)로 `ACTIVE_STATUSES` 를 보게 넓혀 **양단 방어**를 이룬다.
- **재현/확인 경로**: `VlmTimeseriesStepNonBlockingTest#상관키_등록과_마킹_VLM_REQUESTED_전이는_외부_제출보다_먼저_수행된다`.
- **영향**: 케이스 기대값이 현재 코드와 어긋난다. 또 "제출이 확정 실패하면 마킹이 `VLM_REQUESTED` 로 잠깐 올라갔다가 `VLM_FAILED` 로 보상 전이"라는 **중간 상태가 새로 생겼다** — 이 사이에 화면이 마킹을 조회하면 실제로는 위탁되지 않은 건이 "요청됨"으로 보인다(짧은 창).
- **수정 방향(제안)**: TC-VLM-011 을 "제출 **전** 선커밋(REQUIRES_NEW, `VlmMarkingTxService`)" 으로 재작성하고, 실패 보상 전이(`markVlmFailedIfRequested`)를 별도 케이스로 신설. ⚠ **구현하지 않는다.**

### [B-ISSUE-45] TC-VLM-040 / TC-VLM-003 관련 — 재개 경로가 프록시로 부르는 `run()` 은 `readOnly=true` 트랜잭션이라, 그 안에서 보류를 **다시 기록**하려 하면 실패한다 (미재현·정적)

- **심각도**: LOW (현재는 B-ISSUE-41 때문에 도달 자체가 불가 — 41 을 고치면 표면화)
- **기대 동작(기대효과)**: 어느 진입점으로 들어와도 VLM 보류/비활성 사유는 `LS_BATCH_PROC_LOG` 에 남아야 한다(B-ISSUE-24 해소의 전제).
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.run` 은 `@Transactional(REQUIRES_NEW, **readOnly = true**)`(`:236-239`) 다. `execute` 경로에서는 자기호출이라 이 속성이 무시되고 `execute` 의 쓰기 가능 경계를 쓰지만, **`VlmWithheldResumeRunner:69` 는 프록시 경유로 `run` 을 직접 호출**하므로 readOnly 경계가 실제로 개시된다. 그 안에서 게이트가 여전히 닫혀 있거나 `enabled=false` 면
  `:271` / `:305` 의 `batchStatusService.recordVlmSkipped(...)`(REQUIRED, `BatchStatusService:58-62`)가 **readOnly 트랜잭션에 참여**해 `LsBatchProcLog`(`@GeneratedValue(IDENTITY)`, `LsBatchProcLog.java:25-28`) INSERT 를 시도한다. `HibernateJpaDialect` 가 readOnly 를 JDBC 커넥션에 전파하므로 PostgreSQL 은 `cannot execute INSERT in a read-only transaction`(25006)로 거부한다. 그 예외는 `resumeAsync` 의 `catch(RuntimeException)`(`:73-78`)이 삼켜 WARN 한 줄만 남긴다.
  같은 경로의 다른 쓰기(`ledger.recordIssued`, `markingTxService.persistVlmRequested`, `recordVlmSkippedInNewTx`)는 전부 `REQUIRES_NEW` 별도 빈이라 영향이 없다 — **문제는 REQUIRED 인 `recordVlmSkipped` 하나**다.
- **재현/확인 경로**: 이번 회차 **미재현**. 재개가 실제로 발생한 유일한 케이스(B 대조군)는 게이트가 열려 제출 경로로 갔기 때문에 이 분기를 타지 않았다. 재현하려면 "메타 0건 + 보류 기록 有 + resolve 직후 다시 `'F'`" 또는 `vlm.client.enabled=false` 상태에서 재개를 발화시켜야 한다.
- **영향**: B-ISSUE-41 을 고쳐 재개가 살아난 뒤, **재개했는데 여전히 보류해야 하는 경우의 감사 행이 유실**된다(무한 재개 시도 + 무기록). 보안 영향은 없다(외부 전송은 여전히 차단됨).
- **수정 방향(제안)**: `run()` 의 `readOnly=true` 를 제거하거나(주석 `:213-216` 이 "이 경로엔 dirty 엔티티가 없다"고 하나 `recordVlmSkipped` 는 새 엔티티 INSERT 다), `recordVlmSkipped` 호출을 `recordVlmSkippedInNewTx`(이미 존재, `:78-82`)로 통일한다. 후자는 커넥션 점유가 늘어 주석에 적힌 커넥션 기아 우려가 있으므로 **전자(readOnly 제거)가 안전**하다. 회귀 가드는 ambient tx 없는 IT 로 세운다. ⚠ **구현하지 않는다.**

### [B-ISSUE-46] TC-VLM-013 / 019 / 024 / 025 — 1차 B-ISSUE-27 의 테스트 커버 갭이 **그대로 이월**, 게다가 벤더 왕복 계약 IT 는 2차에도 SKIP

- **심각도**: LOW (품질 게이트 신뢰도)
- **기대 동작(기대효과)**: P0/P1 로 분류된 보안·동시성 케이스는 자동 회귀 가드를 보유해야 한다.
- **현재 동작(이슈 내용)**:
  | 케이스 | 우선 | 커버 상태 |
  |---|:--:|---|
  | TC-VLM-013 콜백 URL 고정 base(SSRF) | P1 | 부분 — `VlmTimeseriesStepTest:177` 이 `endsWith("/v1/vlm/callback")` 만 단언. **base 가 사용자 입력에 오염되지 않음**을 단언하는 테스트 없음 |
  | TC-VLM-019 completed + 영상 미존재 → NOT_FOUND | P1 | **없음**(`VlmResultServiceTest` 에 해당 시나리오 부재) |
  | TC-VLM-024 동일 request_id 동시 콜백 직렬화 | **P0** | **없음.** 증강 채널엔 `AugmentCallbackIdempotencyIT`·`GenAiCallbackRollupConcurrencyIT`·`AugmentDeidentConcurrencyIT` 가 있는데 VLM 만 비대칭 |
  | TC-VLM-025 로그 마스킹(CR/LF/tab) | P1 | **없음**(`safe()`/`safeForLog()` 전용 테스트 0건) |

  추가로 `test-baseline.md §5` 의 **backend 스킵 5건이 전부 "라이브 목 서버 실연동 IT — KPST/VLM 클라이언트 왕복"** 이며, 그중 `VLM describe 는 accepted 와 request_id echo 를 반환한다` 가 포함된다. 즉 `VlmClient` 의 벤더 계약 정합(요청 스키마·`accepted` 화이트리스트·request_id echo 검증 `:154-169`)은 **1차·2차 모두 자동 실행 0회**다. 본 검증이 수동으로 왕복을 확인했으나 CI 회귀 가드는 여전히 없다.
- **재현/확인 경로**:
  ```bash
  grep -rn "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/webhook/VlmResultServiceTest.java
  ls backend/src/test/java/kr/co/cudo/authoring/webhook/ | grep -i concurren   # Augment 계열만 존재
  ```
- **영향**: TC-VLM-024 는 P0(동시 콜백 → 검수큐 중복 적재/META race)인데 비관적 락 배선이 깨져도 CI 가 잡지 못한다. 라이브 목서버 IT 스킵은 벤더 계약 드리프트를 무증상으로 만든다.
- **수정 방향(제안)**: ①`VlmCallbackIdempotencyIT`(동일 request_id 2스레드 동시 POST → 1건만 `applied:true`, 검수행 N건 유지)를 증강 IT 패턴 그대로 신설. ②`VlmResultServiceTest` 에 영상 미존재/로그 마스킹 케이스 추가. ③`resolveCallbackUrl` 이 설정 base 만 쓰고 요청값을 반영하지 않음을 단언하는 단위테스트. ④라이브 목서버 IT 를 CI 에서 mock-server 를 띄우고 실행하는 프로파일로 승격(현재는 조건부 SKIP 이라 영구 미실행). ⚠ **구현하지 않는다.**

---

## 부기 — 결함으로 보고하지 않은 관측 사항

| 관측 | 왜 결함이 아닌가 |
|---|---|
| `DeidentReportGate` 가 `DE_IDNTF_YN='F'` 단일 컬럼만 보고 `ORGNL_RAW_SN` 을 보지 않음 | `UNCERTAINTIES.md ★1` 확정 정책(조상/자손 전파는 4라운드 후 철회). 재보고 금지 대상 |
| 128(KPST 실패로 `'F'`)에 "비식별 누락 **신고** 구간" 사유가 찍힘 | `CLAUDE.md` 가 명시한 `'F'` 의 의미 이중성(신고 / 비식별 API 실패). 게이트는 의도적으로 둘을 같게 취급한다 |
| `readableDeidVideoBases` 가 `deidentifiedBase` **전체**를 base 로 포함해 구 위치 축에서는 rawSn 교차 차단이 없음 | TC-BATCH-114 가 요구하는 "구 위치 계속 허용"의 직접 결과. 경로 출처는 해당 rawSn 의 `LS_DEIDENT_PROC_LOG` self-lookup 이라 오염에 DB 쓰기 권한이 필요하고, co-locate 축은 교차 차단 테스트가 존재 |
| 신고 구간 영상의 배치가 FRAME_EXTRACT 에서 `INVALID_INPUT` → FAILED 로 끝남 | TC-BATCH-103 의 기대값 그대로(비식별 선행 가드). "보류" 규약은 **외부 전송(VLM)** 에만 적용된다는 것이 `CLAUDE.md` 규정 |
| dev 환경 `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none` | 운영 형상(stg/prd)에서 벤더 대역을 지정하는 설정 축. A 클러스터 환경 검증 스코프 |
