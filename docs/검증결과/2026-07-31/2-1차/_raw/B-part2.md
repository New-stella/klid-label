# B 클러스터 part2 — B-5 마킹완료 브릿지 / B-6 MarkingService / B-7 VLM 위탁·콜백·보류재개

- 회차: **2-1차** (2026-07-31)
- 담당 범위: `docs/test-cases/B-batch-deidentify.md` §B-5(13) · §B-6(27) · §B-7(36) = **76건**
- 이슈 ID 대역: **B-ISSUE-21 ~ B-ISSUE-45**
- 검증 환경: `_raw/stack-bringup.md`(풀스택 healthy, 외부연동 전부 `klid-mock-server:9400` 실배선) + `_raw/pipeline-drive.md`(rawSn=4 전구간 실구동)
- 이번 회차 본 에이전트가 추가 실구동한 데이터: **rawSn=5**(AUTO 마킹→배치→VLM 실왕복 완주) · **rawSn=6**(intervalFrames 상한 케이스 → 이후 신고/보류/해소 시나리오) · **rawSn=11**(신고 게이트 보류 시나리오)
- 인증: JWT 직접 발급(HS256, `JWT_SECRET` UTF-8 raw, `iss=klid-auth`) — REVIEWER=1001 / WORKER(배정)=2001 / WORKER(미배정)=2002 / PORTAL=3001 / 역할행 없음=9999

## 판정 집계

| 판정 | 건수 |
|---|---:|
| PASS | 66 |
| FAIL | 0 |
| PARTIAL | 4 |
| BLOCKED | 1 |
| N/A | 0 |
| 확인필요 | 5 |
| **합계** | **76** |

> **B-ISSUE-23(이월) 재확인 결과: 미해소.** TC-BATCH-096 실동작 재현으로 `intervalFrames=999999999` 가 여전히 201 + `marks 1건(frameIndex=0)` 으로 통과함을 확인했다(아래 B-ISSUE-25).

---

## B-5. 마킹 완료 브릿지 (동시성·가드) — 13건

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-BATCH-050 | PASS | [정적] | `MarkingBatchBridge.java:98-103` — `videoRepository.findById` empty → WARN + `MarkingBatchTriggerReport.skipped(REASON_VIDEO_NOT_FOUND)` + return(미트리거). 단위테스트 `MarkingBatchBridgeTest#영상_행_미존재시_스킵` |
| TC-BATCH-051 | PASS | [실동작]+[정적] | `SKIP_BATCH_STAGES = {PROCESSING, COMPLETED}`(`:81-82`) → `:109-114` skip. 실동작 간접 확증: rawSn=4(COMPLETED)·rawSn=5(마킹 후 PROCESSING)에 재마킹 시도 시 브릿지 이전 단계인 `MarkingGuards.requirePreconditions` 가 412 로 먼저 차단(재현 로그 아래 §실동작 로그 A). 브릿지 자체 분기는 `MarkingBatchBridgeTest#배치_COMPLETED_영상에…`/`#배치_PROCESSING_중…` 2건이 커버 |
| TC-BATCH-052 | PASS | [정적] | `:118-123` `!"Y".equals(raw.getDeIdntfYn())` → WARN + `skipped(REASON_NOT_DEIDENTIFIED)` + 미트리거. 테스트 `#비식별_미완료영상은_배치트리거_안함`·`#비식별_실패영상은_배치트리거_안함` |
| TC-BATCH-053 | PASS | [실동작] | rawSn=5 마킹 201 직후 backend 로그 `[MarkingBatchBridge] handling marking completed rawSn=5` → `enqueued rawSn=5`(같은 스레드 `http-nio-8080-exec-6`) → `batch-async-2` 에서 파이프라인 실행. `tryClaimBatchQueued` 성공 경로 |
| TC-BATCH-054 | PASS | [실동작]+[정적] | rawSn=5·6 은 마킹 전 `LS_RAW_DATA_STATUS` row 자체가 없었음(배정 없음, 배정된 것은 rawSn=4 뿐). 마킹 후 `ls_raw_data_status` 에 rawSn=5·6 행이 `ver=2` 로 생성·존재 → tx1 false → `tryCreateBatchQueuedRow` 가 생성한 경로가 실제로 탔음. 코드 `BatchTransitionService.java:300-322` |
| TC-BATCH-055 | PASS | [정적] | `:138-144` `catch (DataIntegrityViolationException)` → `concurrent row creation rawSn={} — skipping` + `claimed=false`. AFTER_COMMIT 은 활성 tx 밖이라 catch 가 유효. 테스트 `#동시_row생성경합_tx2가_DataIntegrityViolationException_던지면_잡아서_스킵` |
| TC-BATCH-056 | PASS | [실동작]+[정적] | `BatchTransitionService.tryClaimBatchQueued`(`:254-262`)가 조건부 UPDATE(check-and-set, 영향행수 1 판정). **실동작**: rawSn=5 에 동시 3요청 → 브릿지 `enqueued` 로그 **정확히 1회**, `ls_batch_proc_log` rawSn=5 행 1건, `ls_webhook_idempotency` VLM 발급 1건, `LS_DATA_META` 신규 1건(`new=1 updated=0`) — 배치가 중복 시작되지 않음 |
| TC-BATCH-057 | PASS | [정적] | `:146-153` 두 클레임 모두 false → `batch already claimed/in-progress or review-owned … skipping` + `skipped(REASON_ALREADY_CLAIMED)` |
| TC-BATCH-058 | PASS | [정적] | `BatchTransitionService.java:300-305` `existsById(rawSn)` true → `return false`(멱등 스킵) |
| TC-BATCH-059 | PASS | [정적] | `:300-322` 할당형 PK(`LsRawDataStatus`)라 `saveAndFlush` 로 즉시 INSERT flush, `DataIntegrityViolationException` 은 catch 하지 않고 전파(REQUIRES_NEW tx 롤백) → 호출부가 별도 tx 밖에서 catch |
| TC-BATCH-060 | PASS | [정적] | `MarkingBatchBridge.java:166-168` `sanitize()` 가 `\n`/`\r` 제거. 로그에 들어가는 DB 유래 문자열은 `dataSttsCd`·`deIdntfYn` 둘뿐이고 둘 다 sanitize 경유. 나머지 인자는 `Long rawSn`(주입 표면 없음) |
| TC-BATCH-061 | PASS | [정적] | `SKIP_STATUSES`(`:66-71`)가 `BatchTransitionService.REVIEW_OWNED_STATUSES`(`:77-81`, PENDING/IN_REVIEW/APPROVED/REJECTED)를 `Stream.concat` 으로 흡수 → 입구(브릿지)·본체(`BatchOrchestrator.process` 진입 가드)·출구(`transitionRawDataStatus`)가 같은 상수 공유. 테스트 `#클레임_skip집합에_검수소유상태_4종이_포함된다` |
| TC-BATCH-062 | PARTIAL | [실동작]+[정적] | **triggered 분기 실동작 확인**: rawSn=5 응답에 `"batchTriggered":true,"batchSkipReason":null`. **skip 분기는 실동작 미재현** — 마킹 API 프리컨디션(`MARKING_READY` 요구)이 브릿지 skip 조건보다 먼저 발화해, 브릿지가 skip 을 기록하는 상태 조합(예: stage=MARKING_READY·work=검수소유)을 HTTP 만으로 만들 수 없었다. 정적/단위테스트(`#배치_미트리거시_스킵사유가_리포트에_기록된다`, `#비식별_미완료_스킵도_사유가_리포트된다`)로 커버 → **B-ISSUE-26** |

---

## B-6. MarkingService (자동/수동, 경계값, 인가) — 27건

> 아래 HTTP 응답은 전부 `POST http://localhost:18081/api/v1/videos/{rawSn}/markings` 실호출 결과다.

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-BATCH-070 | PASS | [실동작] | 토큰 없이 호출 → `401 {"errorCode":"UNAUTHORIZED","message":"인증이 필요합니다."}`. 프로브 이전 거부(`MarkingGuards.java:53-55`) |
| TC-BATCH-071 | PASS | [실동작] | REVIEWER(1001)가 **미배정** rawSn=5·6 에 마킹 성공(201). `MarkingGuards.java:56-58` 전체 허용 |
| TC-BATCH-072 | PASS | [실동작] | WORKER 2002(미배정) → rawSn=5 `403 FORBIDDEN`, rawSn=**999999(미존재)** 도 동일하게 `403 FORBIDDEN`(NOT_FOUND 아님 — 존재 미노출). 대조군: WORKER 2001(rawSn=4 배정자)은 rawSn=4 에서 403 이 아니라 **412**(인가 통과 후 프리컨디션에서 거부) → 인가 판정이 배정 기준으로 정확히 동작. 추가 반증: 역할행 없는 userNo=9999 → `403`(fail-closed) |
| TC-BATCH-073 | PASS | [정적] | `MarkingService.java:105-130` — `precheckReader.precheck()` 가 먼저, `durationResolver.resolveDurationSec()` 는 그 뒤. 테스트 `MarkingServiceOrchestrationTest#미배정_WORKER_AUTO요청_프로브_미트리거되고_FORBIDDEN` |
| TC-BATCH-074 | PASS | [실동작] | REVIEWER + rawSn=999999 → `404 NOT_FOUND "영상을 찾을 수 없습니다."`(`MarkingGuards.java:80-82`) |
| TC-BATCH-075 | PASS | [실동작]+[정적] | `MarkingGuards.java:83-86`. 실동작 대체 확증: 비식별 신고로 `DE_IDNTF_YN='F'` 가 된 rawSn=6 에 대해 배치 FRAME_EXTRACT 가 `비식별이 완료되지 않은 영상입니다 rawSn=6` 로 거부(같은 규칙축). 마킹 API 자체는 `MARKING_READY` 조건이 먼저라 412 사유가 4단계에서 나오며, 단위테스트 `MarkingServiceTest#비식별_미완료_영상_마킹생성시_PRECONDITION_FAILED` 가 직접 커버 |
| TC-BATCH-076 | PASS | [실동작] | REVIEWER + rawSn=4(COMPLETED) → `412 PRECONDITION_FAILED "이미 처리된 영상은 재마킹할 수 없습니다."`(역전 차단, `MarkingGuards.java:87-90`) |
| TC-BATCH-077 | PASS | [정적] | `MarkingGuards.java:91-95` — `evntTypeCd` null/blank → INVALID_INPUT. 테스트 2건(`#이벤트유형_null…`,`#이벤트유형_blank…`) + `MarkingControllerTest#POST_이벤트유형_미지정_영상_400` |
| TC-BATCH-078 | PASS | [실동작] | AUTO + `intervalFrames` 생략/`0`/`-5` 3케이스 모두 `400 INVALID_INPUT "자동 모드에서 intervalFrames 는 1 이상이어야 합니다."`(`MarkingService.java:196-198`) |
| TC-BATCH-079 | PASS | [정적] | `MarkingService.java:269-273` backstop `durationSec==null||<=0` → INVALID_INPUT. 테스트 `#자동마킹_주입_durationSec_null이면_INVALID_INPUT_단건퇴화방지`, `#FIX_A_외부해석이_전부_실패해_null이_주입되면_backstop_INVALID_INPUT` |
| TC-BATCH-080 | PASS | [실동작] | rawSn=5(`VDO_LEN_SEC=30`, pin fps=10) + `intervalFrames=100` → `marks=[{0,"00:00"},{100,"00:10"},{200,"00:20"}]`. totalFrames=round(30×10)=300, **300 미포함**(off-by-one 정상), step=100 |
| TC-BATCH-081 | PASS | [정적] | `MarkingService.java:276` `(int) Math.round(durationSec * fps)` — 분수 fps 반올림. 테스트 `#자동마킹_실fps25…`,`#자동마킹_실fps60…`,`#M3_자동마킹_fps미상_30폴백…` |
| TC-BATCH-082 | PASS | [실동작] | `ls_marking` 실측: rawSn=4·5·6 전 행에 `fps=10` 이 pin 저장됨. `MarkingService.java:191,216-218`(`createAuto`/`createManual` 인자로 fps 전달) |
| TC-BATCH-083 | PASS | [실동작] | MANUAL + `marks:[]` / marks 생략 → 둘 다 `400 "수동 모드에서 marks 는 필수입니다."`(`:204-206`) |
| TC-BATCH-084 | PASS | [실동작] | `mode:"X"` / `"auto"` / `" AUTO "` 3케이스 모두 `400 "mode 는 AUTO 또는 MANUAL 이어야 합니다."` — 대소문자·trim 미허용 fail-closed 확인(`:210-212`) |
| TC-BATCH-085 | PASS | [실동작] | rawSn=5 마킹 201 직후 `MarkingBatchBridge` 로그 발화 = `MarkingCompletedEvent` 발행 확인(`:237`) |
| TC-BATCH-086 | PASS | [실동작] | 응답 `"eventName":"INTRUSION"` = `ls_data_raw.evnt_type_cd`('INTRUSION') 와 일치(`:182`) |
| TC-BATCH-087 | PASS | [실동작] | 브릿지가 `@TransactionalEventListener(AFTER_COMMIT)` 로 **커밋 이후** 같은 요청 스레드(`http-nio-8080-exec-6`)에서 실행됨이 로그로 확인 → persist 가 self 프록시 경유로 실제 트랜잭션을 가졌다는 증거(`:128-130`) |
| TC-BATCH-088 | PASS | [실동작]+[정적] | `VideoDurationResolver:74,110` 이 `@Transactional(NOT_SUPPORTED)` — 트랜잭션/커넥션 밖 수행. 3단 폴백 우선순위 **실측 확증**: rawSn=5 의 `VDO_LEN_SEC=30`, `LS_DATA_META.video.duration_ms=5000`(실파일 5초). 자동 마킹이 300프레임(=30초×10fps)을 만들었으므로 **1단(VDO_LEN_SEC)이 우선**함이 확인됨 |
| TC-BATCH-089 | PASS | [실동작] | PORTAL 채널 토큰(3001) → `403 FORBIDDEN "권한이 없습니다."`. `MarkingController.java:51 @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` |
| TC-BATCH-090 | PASS | [실동작] | rawSn=5 에 활성 마킹 존재 상태에서 순차 재요청 → `409 CONFLICT "이미 진행 중인 마킹이 있습니다…"`. (근거 드리프트: 카탈로그 `MarkingService.java:177` → 실제 `:178`) |
| TC-BATCH-091 | PASS | [실동작] | rawSn=5 동시 3요청 → **201 ×1 / 409 ×2, 500 은 0건**. backend 로그에 `[Marking] concurrent duplicate rejected rawSn=5` 2건 = `save+flush` 를 감싼 catch 가 `DataIntegrityViolationException`→409 변환. DB `ls_marking` 에 rawSn=5 활성행 1건만 존재(부분 유니크 `uk_ls_marking_raw_actvtn ... WHERE stts_cd IN ('PENDING','VLM_REQUESTED')` 실재 확인). 부분 저장 없음 |
| TC-BATCH-092 | PASS | [실동작] | MANUAL + 동일 `frameIndex:10` 2회 → `400 "중복된 마킹 시점입니다: frameIndex=10"`(`:208`) |
| TC-BATCH-093 | PASS | [실동작] | 상한식 검증: dur=30·fps=10 → `manualFrameIndexLimit = round(300)+ceil(10) = 310`. `frameIndex=310` → 400(경계 배타 확인), `frameIndex=999999999` → 400 `"(허용 상한 310 프레임 미만)"`. `frameIndex=-1` 은 DTO Bean Validation 이 400 |
| TC-BATCH-094 | PASS | [정적] | `MarkingService.java:317-321` — `durationSec` null/≤0 또는 fps≤0 이면 WARN 후 **상한만** return, 중복·하한은 그 위 루프에서 이미 적용. 테스트 `MarkingManualValidationTest#영상길이를_알_수_없으면_상한만_스킵하고_하한과_중복은_그대로_400` |
| TC-BATCH-095 | PASS | [정적] | `MarkingService.java:124-126` MANUAL 은 `resolveDurationSecWithoutProbe` 사용(`VideoDurationResolver:111`, DB 2단만). 테스트 `MarkingServiceOrchestrationTest#MANUAL_모드는_사전확인통과해도_프로브_미트리거하고_마킹성공` |
| TC-BATCH-096 | PASS | [실동작] | **B-ISSUE-23 미해소 재확인.** rawSn=6 + `intervalFrames=999999999` → `201`, `marks=[{"frameIndex":0,"timestamp":"00:00"}]` 1건. 하한(≥1)만 검증되고 상한(dur×fps)은 여전히 미검증. 케이스가 "현재 동작 고정"으로 기술돼 있어 케이스 자체로는 PASS 이나 **이슈는 이월 유지**(B-ISSUE-25) |

---

## B-7. VLM 위탁 Step + 콜백 수신 + 신고 보류/재개 — 36건

> ⚠ **이 절 전체의 근거 `file:line` 이 대량 드리프트했다.** `VlmTimeseriesStep` 이 **Phase C-1(논블로킹 제출)** 로 재작성되어 파일이 130줄대 → **418줄**로 커졌고, `.block(45s)` 가 제거됐으며, 마킹 전이가 별도 빈(`VlmMarkingTxService`)의 **제출 전 선커밋**으로 이동했다. 상세는 B-ISSUE-24.

### 실동작 근거 (mock-server 실경유)

- rawSn=5: backend `[Batch][VlmTimeseries] describe submit rawSn=5 request_id=0e73fe67-… hasMarking=true` → mock `[MOCK][VLM] describe accepted request_id=0e73fe67-… callback_url=http://klid-backend:8080/api/v1/vlm/callback` → mock `media duration probed file=clip-9102-**mask**.mp4`(비식별본) → mock `callback sent … status=200` → backend `[Webhook][Vlm] result applied request_id=0e73fe67-… rawSn=5 new=1 updated=0 markingsTransitioned=1`
- `ls_webhook_idempotency`: `(0e73fe67-…, VLM, rawSn=5, PROCESSED, aply_dt=03:12:27)` — 발급→처리 원장 왕복 실측
- self-fill 흔적 0건: 시계열 메타(`0-5` 구간 서술)는 mock 응답에서만 유입, 저작도구 자체 생성 값 없음

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-VLM-001 | PASS | [실동작]+[정적] | rawSn=5·6 로그 `hasMarking=true`. `VlmTimeseriesStep.java:221-226`(구 135-142) `markings.get(0)`, 목록은 `MarkingLoadStep:52` 의 `findByRawSnOrderByRegDtDescMarkingSnDesc` = 최신 1건. V142 부분 유니크로 활성 다건 자체가 불가(TC-BATCH-091 실증) |
| TC-VLM-002 | PASS | [실동작] | 마킹 0건인 rawSn=11 에 dev 트리거 → `run(rawSn)` 분기 진입 확인(VLM 단계가 실행되어 SKIPPED 감사 행을 남김). 코드 `:224-226`(구 139-141) |
| TC-VLM-003 | PASS | [정적] | `:269-273`(구 182-186) — `!vlmClient.isEnabled()` → 로그 + `batchStatusService.recordVlmSkipped(rawSn, SKIP_REASON_DISABLED)` + `VlmTimeseriesResponse.skipped(null)`, 외부 호출 0. `BatchStatusService.java:58-62`(구 57-64). 테스트 `VlmTimeseriesStepTest#VLM_비활성일_때_LS_BATCH_PROC_LOG_에_VLM_SKIPPED_행이_사유와_함께_남는다` (로컬은 `VLM_CLIENT_ENABLED=true` 라 실동작 미재현) |
| TC-VLM-004 | PASS | [정적] | `:261-263`(구 174-176) rawSn null → INVALID_INPUT, 등록·호출 이전 |
| TC-VLM-005 | PASS | [정적] | `:276-278`(구 189-191) `existsById` false → NOT_FOUND, **enabled 체크 이후**(순서 유지) |
| TC-VLM-006 | PASS | [정적] | `resolveDeidentifiedPath` `:390-400`(구 280-290) — `LS_DEIDENT_PROC_LOG` 최신 성공의 `DE_IDNTF_FILE_PATH_NM` 만 사용, 없으면 `EXTERNAL_API_ERROR`. **원본 경로를 media.path 에 넣는 코드가 존재하지 않음**(grep 확인). 실동작 보강: mock 로그의 media 파일명이 `clip-9102-mask.mp4`(비식별본) |
| TC-VLM-007 | PASS | [정적] | `:318-325`(구 231-238) `ledger.recordIssued` 실패 → `EXTERNAL_API_ERROR` throw, `vlmClient.submitTimeseries` 미도달(코드상 아래 줄) |
| TC-VLM-008 | PASS | [정적] | `PersistentWebhookIdempotencyLedger.java:57-58 @Transactional(REQUIRES_NEW)` — 독립 커밋. 실동작 보강: rawSn=5 원장 행이 콜백(2초 뒤)에 정상 역조회됨 |
| TC-VLM-009 | PARTIAL | [정적] | **기대결과 미충족(의도된 재설계)** — 빈 응답은 이제 `switchIfEmpty(Mono.error(...))`(`:364-365`) 로 만들어져 **비동기 err 핸들러**(`:369-370`)로 흘러 `outcomeRecorder.onSubmitFailed` 가 `SKIP_REASON_SUBMIT_FAILED` 로 감사 기록하고, `doSubmit` 은 예외를 던지지 않고 `submitted` 를 반환한다. "EXTERNAL_API_ERROR 로 전파" 라는 단언은 더 이상 성립하지 않는다. 다만 안전 속성("무흔적 유실 금지")은 유지되며 재개 대상으로 회수된다 → **B-ISSUE-22** |
| TC-VLM-010 | 확인필요 | [정적] | **`BLOCK_TIMEOUT`(45s) 상수가 코드에서 완전히 사라졌다**(grep: `VlmTimeseriesStep` 내 `.block(` 0건, 주석의 과거 서술만 잔존 `:70,:348`). 현재는 `VlmClient` 의 `.timeout(vlm.client.timeout-seconds)` **단일 10초**(`VlmClient.java:71,79` · `application.yml:633`) + Resilience4j Retry/CircuitBreaker(TimeLimiter 미사용, `application.yml:585-587` 주석 명시)뿐이다. 카탈로그의 "45s ↔ 10s 이중 구조" 와 `UNCERTAINTIES.md #13` 서술 모두 **정정 대상** → **B-ISSUE-21** |
| TC-VLM-011 | PASS | [실동작]+[정적] | 전이 자체는 성립 — `ls_marking` rawSn=5 가 PENDING→(VLM_REQUESTED)→VLM_COMPLETED 로 도달했고 콜백 로그 `markingsTransitioned=1`. 단 구현 위치·시점이 이동: `persistMarkingTransition`(구 `:262,300-308`) → **`VlmMarkingTxService.persistVlmRequested`(REQUIRES_NEW, `:53-61`)를 제출 *전* 선커밋**(`VlmTimeseriesStep.java:337`). detached 엔티티 명시 `save(=merge)` 는 유지 |
| TC-VLM-012 | PASS | [정적] | `LsMarking.markVlmRequested()`(`:232-235`)가 `PENDING` 에서만 전이(그 외 no-op) → `VlmMarkingTxService:55-59` 가 `transitioned && markingSn!=null` 일 때만 save. 테스트 `VlmTimeseriesStepMarkingTest#retry_재실행_시_이미_VLM_COMPLETED_마킹은_VLM_REQUESTED로_역행하지_않는다` |
| TC-VLM-013 | PASS | [실동작]+[정적] | `resolveCallbackUrl()` `:403-411`(구 311-319) = 고정 `callbackBaseUrl` + `HmacWebhookFilter.PATH_VLM`(`/v1/vlm/callback`), 사용자 입력 미반영. 실동작: mock 이 수신한 `callback_url=http://klid-backend:8080/api/v1/vlm/callback`(= `WEBHOOK_CALLBACK_BASE_URL` 환경값) |
| TC-VLM-014 | PASS | [실동작] | 미발급 `request_id=deadbeef-…` 콜백 → `401 UNAUTHORIZED "발급되지 않은 request_id 입니다."`(`VlmResultService.java:69-74`) |
| TC-VLM-015 | PASS | [실동작] | 이미 PROCESSED 인 `0e73fe67-…` 로 **description 을 바꿔** 재전송 → `200 {"applied":false}`, `LS_DATA_META` 값 미변경(덮어쓰기 없음). `:77-80` |
| TC-VLM-016 | PASS | [정적] | `:83-88` `entry.rawSn()==null` → UNAUTHORIZED. 테스트 `VlmResultServiceTest#발급됐으나_rawSn_매핑_없으면_UNAUTHORIZED_거부` |
| TC-VLM-017 | PASS | [실동작] | `status:"done"` → `400 INVALID_INPUT "status: status 는 completed|failed 중 하나여야 합니다."` — DTO `@Pattern`(`VlmResultRequest.java:47-49`)이 1차 차단하므로 서비스 진입 전 400. 원장은 PROCESSED 로 마킹되지 않아 재전송 허용(DB 확인: 해당 케이스로 상태 변화 없음). 서비스 백스톱도 `:96-102` 에 이중 존재 |
| TC-VLM-018 | PASS | [정적] | `:105-109` failed → `handleFailed`(error 로그 + `markVlmFailed`) → `markProcessedInTx` → true. `:210-232` |
| TC-VLM-019 | PASS | [정적] | `:112-116` completed + `existsById` false → NOT_FOUND(사용자 메시지 일반화, rawSn 은 서버 로그만) |
| TC-VLM-020 | PARTIAL | [정적] | `dedupSegments` `:238-250`(구 192-204) — 중복 metaKey → `INVALID_INPUT`. **실동작 미재현**: 이 분기는 `state != PROCESSED` 인 원장 행이 필요한데, mock-server 의 콜백 지연이 2초라 발급~콜백 창을 안정적으로 선점하지 못했다(2회 시도 모두 mock 콜백이 먼저 PROCESSED 로 마감). 단위테스트 `VlmResultServiceTest#한_콜백_내_중복_구간_metaKey는_400_거부_및_조용한_덮어쓰기_없음` 이 커버 → **B-ISSUE-28** |
| TC-VLM-021 | PASS | [실동작] | rawSn=5 콜백 결과 `new=1 updated=0`, `ls_data_meta` 에 `('0-5', 서술)` 1건 신규 + `ls_data_meta_review` 에 `(data_meta_sn=20, META_TYPE_CD=VLM, SRC_SYS_CD=AI_SERVER, RVW_STTS_CD=PENDING)` 1건 생성. 기존 키(`video.*` 6종)는 검수행 미생성 |
| TC-VLM-022 | PASS | [실동작] | 콜백 로그 `markingsTransitioned=1` + `ls_marking` rawSn=5 `stts_cd=VLM_COMPLETED`(`:158-161`) |
| TC-VLM-023 | PASS | [정적] | `:63 @Transactional("controlTransactionManager")` 단일 트랜잭션 + `:164 ledger.markProcessedInTx`(REQUIRED, outer 참여) → 중간 예외 시 PROCESSED 도 함께 롤백. 중간 예외를 catch 하지 않음(전파). 테스트 `#검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능` |
| TC-VLM-024 | PASS | [정적] | `:69 ledger.lookupForProcessing` → `LsWebhookIdempotencyRepository.java:23 @Lock(LockModeType.PESSIMISTIC_WRITE)` — 동일 request_id 동시 콜백 직렬화. 두 번째는 커밋된 PROCESSED 를 보고 멱등 스킵(TC-VLM-015 실동작이 그 후속 상태를 확증) |
| TC-VLM-025 | PASS | [정적] | `:48 LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]")` + `:252-255 safe()` 로 requestId/status/metaKey/error 전부 치환 후 로그 |
| TC-VLM-030 | PASS | [실동작] | `/v1/vlm/callback` 은 `SecurityConfig.java:101` permitAll + HMAC 비대상(`HmacWebhookFilter.java:49` 계약 명시). 로컬 `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none` 이라 IP 게이트도 없음. **실제 차단은 발급 게이트뿐**임을 실동작으로 확인: 임의 `request_id`("notissued123", 형식 유효) → `401`. UUIDv4 예측 불가성이 유일한 방벽 |
| TC-VLM-031 | PASS | [실동작] | `DE_IDNTF_YN='F'` 인 rawSn=11 에 dev 배치 트리거 → `ls_batch_proc_log` 에 `(data_raw_sn=11, proc_step_cd='VLM', proc_stts_cd='SKIPPED', err_msg_cn='비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)')` 적재. **mock-server 의 `/v1/videovlm/describe` 요청 수 3→3 (외부 호출 0건)**. rawSn=6 신고 후 재트리거에서도 동일 결과 재현. 게이트가 `resolveDeidentifiedPath` **이전**에 위치(`:303-307` vs `:309`) |
| TC-VLM-032 | PASS | [실동작] | 위 실행에서 VLM 단계는 **예외를 던지지 않았다** — 파이프라인은 그 다음 단계(FRAME_EXTRACT)에서 별개 사유(`마킹 데이터가 없습니다 rawSn=11` / `비식별이 완료되지 않은 영상입니다 rawSn=6`)로 FAILED 했고, VLM 행은 `SKIPPED`(FAILED 아님)로 남았다. 재시도 큐·시도 상한을 소진시키는 VLM 실패행 0건 |
| TC-VLM-033 | PASS | [정적] | `:303` 게이트 호출에 try/catch 없음 → `DeidentReportGate.isUnderDeidentReport` 의 DB 오류가 그대로 전파(게이트 Javadoc 도 "삼켜 false 로 만들지 않는다" 명시). 위탁 미진행 = fail-closed |
| TC-VLM-034 | PASS | [실동작] | rawSn=6: 신고(`POST /v1/videos/6/deident-report` → 201, `DE_IDNTF_YN='F'`) → 배치 재트리거로 VLM 보류 기록 → 외부 재비식별 대체(비식별 산출물 mtime 갱신) → `POST /v1/deident-reports/1/resolve` → **200**. backend 로그 순서 실측: `[DeidentReport] resolved-manually rprtSn=1 rawSn=6` → `[VlmResumeBridge] deident gate reopened rawSn=6 — checking withheld VLM submit`(AFTER_COMMIT) → `[VlmResume] …`(스레드 `batch-async-2` = `@Async`). DB `de_ident_yn='Y'`, 신고 `RESOLVED`. 배선 전 구간 실동작 확인 |
| TC-VLM-035 | PASS | [실동작] | 위 재개에서 `[VlmResume] resume skipped — timeseries meta already present rawSn=6 count=7` 로그 + **mock describe 요청 수 4→4(외부 호출 0건)**. `VlmWithheldResumeRunner.java:94-99`(구 79-93) 의 메타 0건 멱등 조건이 실제로 재위탁을 막음 |
| TC-VLM-036 | PARTIAL | [정적] | **기대결과 일부 무효(의도된 확장)** — 재개 판정이 `VlmTimeseriesStep.RESUMABLE_SKIP_REASONS`(`:150-152`) **4종**(신고 보류 / 비동기 제출 실패 / ACK 미수신 / 콜백 미수신)으로 확대됐고, 판정 API 도 `BatchStatusService.isStageSkippedWithAnyReason`(`:115-119`)로 바뀌었다. 케이스의 "**보류 사유가 신고일 때만**" 은 더 이상 사실이 아니다. 다만 케이스의 반증 예시(`SKIP_REASON_DISABLED` 는 재개 대상 아님)와 "사유 문자열 단일 원천 = 스텝 상수" 는 여전히 성립 → **B-ISSUE-23(신규번호)** |
| TC-VLM-037 | PASS | [정적] | `VlmWithheldResumeRunner.java:73-78`(구 71-76) `catch (RuntimeException)` → `log.warn(... cause={} e.getClass().getSimpleName())` (클래스명만, 메시지 미노출). 보류 기록·메타0 조건 보존 → 다음 해소/스윕에서 재시도. 테스트 `#재위탁_실패는_삼켜서_해소_트랜잭션에_영향을_주지_않는다` |
| TC-VLM-038 | PASS | [정적] | `BatchStatusService.java:149-156`(구 109-116) `@Transactional(REQUIRES_NEW)`. 호출자는 이제 `VlmSubmitOutcomeRecorder.java:81`(비동기 완료 핸들러)이며, 스텝 롤백과 무관하게 ACK 기록이 남는다. 실동작 보강: `ls_batch_proc_log.resp_payload_cn = {"requestId":"0e73fe67-…","status":"accepted"}` 실적재 |
| TC-VLM-039 | PASS | [정적] | `recordVlmSkipped`(`:58-62`)는 **REQUIRED 유지**. 다만 Phase C-1 에서 비동기 핸들러/스위퍼 전용으로 `recordVlmSkippedInNewTx`(REQUIRES_NEW, `:78-82`)가 **추가**됐고 공통 로직은 비트랜잭션 private 헬퍼(`:85-88`)로 분리 — 케이스의 "REQUIRES_NEW 로 바꾸지 않는다" 취지는 그대로 지켜짐 |
| TC-VLM-040 | PASS | [정적] | `execute` `:218-220 @Transactional(REQUIRES_NEW)` 가 경계. `runWithMarking`/`run` 도 각각 REQUIRES_NEW 선언을 갖지만 `execute` 에서 **자기호출**(`:223,225`)이라 어드바이스 미적용 → 중첩 없음. 프록시 경유 진입(`VlmWithheldResumeRunner:69,71`)일 때만 각자 경계를 연다. 테스트 `VlmAsyncSubmitTransactionBoundaryIT` |

---

## 실동작 로그 A — 동시성/브릿지 원본 발췌

```
03:12:25.259 [http-nio-8080-exec-6] [Marking] created rawSn=5, mode=AUTO, markingSn=2
03:12:25.260 [http-nio-8080-exec-6] [MarkingBatchBridge] handling marking completed rawSn=5
03:12:25.264 [http-nio-8080-exec-6] [MarkingBatchBridge] enqueued rawSn=5          ← 1회만
03:12:25.266 [http-nio-8080-exec-4] [Marking] concurrent duplicate rejected rawSn=5 → 409
03:12:25.267 [http-nio-8080-exec-5] [Marking] concurrent duplicate rejected rawSn=5 → 409
03:12:25.276 [batch-async-2]        [Batch][VlmTimeseries] describe submit rawSn=5 request_id=0e73… hasMarking=true
03:12:25.280 [vlm-submit-1]         [Batch][VlmTimeseries] accepted rawSn=5 request_id=0e73… status=accepted
03:12:25.617 [batch-async-2]        [BatchOrchestrator] completed rawSn=5
03:12:27.355 [http-nio-8080-exec-10][Webhook][Vlm] result applied request_id=0e73… rawSn=5 new=1 updated=0 markingsTransitioned=1
```

```
03:16:49.209 [http-nio-8080-exec-8] [DeidentReport] resolved-manually rprtSn=1 rawSn=6 actor=1001
03:16:49.211 [http-nio-8080-exec-8] [VlmResumeBridge] deident gate reopened rawSn=6 — checking withheld VLM submit
03:16:49.215 [batch-async-2]        [VlmResume] resume skipped — timeseries meta already present rawSn=6 count=7
```

---

## 이슈 대장 (B-ISSUE-21 ~ B-ISSUE-45)

### [B-ISSUE-21] TC-VLM-010 — VLM 블록 상한 45s 가 코드에서 소멸(논블로킹 전환), 카탈로그·UNCERTAINTIES #13 서술이 낡음
- **심각도**: LOW (카탈로그 정합 — 코드 결함 아님)
- **기대 동작(기대효과)**: 케이스 표·`UNCERTAINTIES.md #13` 은 "블록 상한 `BLOCK_TIMEOUT=45s`(재시도 누적) ↔ 실효 클라이언트 타임아웃 10s 의 **이중 구조**"를 판정 기준으로 제시한다. 검증자가 이 기준으로 타임아웃 회귀를 판정할 수 있어야 한다.
- **현재 동작(이슈 내용)**: Phase C-1(논블로킹 제출)에서 `.block(...)` 자체가 제거됐다.
  ```java
  // VlmTimeseriesStep.java:361-370 (현재)
  vlmClient.submitTimeseries(req)
      .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
      .subscribe(resp -> SubmitSignalDispatch.run(vlmSubmitScheduler, ...),
                 err  -> SubmitSignalDispatch.run(vlmSubmitScheduler, ...));
  ```
  `grep -n "BLOCK_TIMEOUT\|\.block(" VlmTimeseriesStep.java` → **주석(:70, :348)의 과거 서술만** 남고 실행 코드 0건. 남은 타임아웃은 `VlmClient.java:71,79` 의 `.timeout(Duration.ofSeconds(max(1, vlm.client.timeout-seconds)))` = `application.yml:633` **10초 단일**이며, TimeLimiter 는 명시적으로 미사용(`application.yml:585-587`).
- **재현/확인 경로**: `grep -rn "BLOCK_TIMEOUT" backend/src/main/java` → 0건. `sed -n 585,590p backend/src/main/resources/application.yml` → `# vlmClient TimeLimiter 미사용 … 타임아웃 단일 출처: vlm.client.timeout-seconds`.
- **영향**: 기능 영향 없음. 다음 회차 검증자가 "45s 상한"을 찾다 못 찾고 **결함으로 오보고**하거나, 반대로 존재하지 않는 상한을 근거로 거짓 PASS 를 낼 위험.
- **수정 방향(제안)**: ① TC-VLM-010 기대결과를 "제출은 논블로킹(subscribe) — 스레드 블로킹 상한 없음. 단일 호출 실효 타임아웃 = `vlm.client.timeout-seconds`(10s) + Resilience4j Retry/CircuitBreaker. 완료 신호는 `vlmSubmitScheduler` 전용 풀에서 기록" 으로 교체 ② `UNCERTAINTIES.md #13` 의 "이중 구조" 문구를 같은 취지로 갱신 ③ `VlmTimeseriesStep.java:70,:348` 주석의 "45s" 표현은 과거 서술임이 문맥상 명확하나 오독 소지가 있어 정리 권장.

### [B-ISSUE-22] TC-VLM-009 — describe 빈/실패 응답이 스텝 예외가 아니라 비동기 감사 기록으로 전환됨
- **심각도**: LOW (의도된 재설계 — 카탈로그 기대결과 정정 대상)
- **기대 동작(기대효과)**: 카탈로그는 "describe 응답 null → `EXTERNAL_API_ERROR`" 로, 즉 **스텝이 동기 실패**해 `BatchOrchestrator` FAILED + 재시도 큐를 타는 것을 기대한다.
- **현재 동작(이슈 내용)**: 빈 응답은 `switchIfEmpty(Mono.error(...))`(`VlmTimeseriesStep.java:364-365`)로 만들어져 **err 핸들러**(`:369-370`)로 흐르고 `VlmSubmitOutcomeRecorder.onSubmitFailed` 가 `SKIP_REASON_SUBMIT_FAILED` 감사 행만 남긴다. `doSubmit` 은 `VlmTimeseriesResponse.submitted(requestId)` 를 반환하며 **예외를 던지지 않는다**(`:381`). 클래스 Javadoc `:118-126` 이 "실패 사슬을 되살리지 **않는다** — 재시도 큐는 rawSn 단위로 파이프라인 전체를 재실행하므로" 라고 의도를 명시.
- **재현/확인 경로**: `sed -n 355,382p backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java`. 테스트 `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다`.
- **영향**: 기능상 안전 속성("무흔적 유실 금지")은 유지되나, **회수가 재시도 큐가 아니라 `VlmSubmitPendingSweeper`+`VlmWithheldResumeRunner` 경로로 옮겨졌다**. 그 스위퍼가 동작하지 않으면 VLM 결과가 조용히 결손된다(회수 경로 단일 의존).
- **수정 방향(제안)**: TC-VLM-009 기대결과를 "빈/실패 응답 → 예외 미전파. `LS_BATCH_PROC_LOG` 에 `VLM/SKIPPED/SKIP_REASON_SUBMIT_FAILED` 감사 행 + `VlmWithheldResumeRunner` 재개 대상(메타 0건 조건)" 으로 교체하고, **스위퍼 회수 케이스를 신규 도출**(현재 카탈로그 B-7 에 `VlmSubmitPendingSweeper` 전용 케이스가 없다 — B-ISSUE-24 참조).

### [B-ISSUE-23] TC-VLM-036 — VLM 재개 대상 사유가 "신고 1종" → 4종으로 확대되어 케이스 단언이 낡음
- **심각도**: LOW (의도된 확장 — 카탈로그 정정 대상)
- **기대 동작(기대효과)**: "재위탁 조건: 보류 사유가 **신고일 때만**", 판정 API 는 `BatchStatusService.isStageSkippedWithReason`(단일 사유).
- **현재 동작(이슈 내용)**:
  ```java
  // VlmTimeseriesStep.java:150-152
  public static final List<String> RESUMABLE_SKIP_REASONS = List.of(
          SKIP_REASON_DEIDENT_REPORT, SKIP_REASON_SUBMIT_FAILED,
          SKIP_REASON_ACK_MISSING, SKIP_REASON_CALLBACK_MISSING);
  // VlmWithheldResumeRunner.java:89-90
  batchStatusService.isStageSkippedWithAnyReason(rawSn, BatchStage.VLM, RESUMABLE_SKIP_REASONS)
  ```
  `BatchStatusService.isStageSkippedWithAnyReason`(`:115-119`)가 신설됐다(구 `isStageSkippedWithReason` `:101-105` 도 잔존).
- **재현/확인 경로**: `sed -n 148,153p .../VlmTimeseriesStep.java`; 테스트 `VlmWithheldResumeRunnerTest#재개_사유_목록에_비동기_제출실패와_ACK_미수신이_포함된다`.
- **영향**: 케이스대로 "신고 외 사유는 재개 안 됨"을 검증하면 **거짓 FAIL** 이 난다. 반대로 `SKIP_REASON_DISABLED` 가 재개 대상이 아니라는 핵심 안전 단언은 여전히 유효.
- **수정 방향(제안)**: TC-VLM-036 기대결과를 "재개 대상 = `RESUMABLE_SKIP_REASONS` 4종. `SKIP_REASON_DISABLED` 는 재개 대상 아님(false). 단일 원천은 `VlmTimeseriesStep` 상수" 로 교체하고, `SKIP_REASON_CALLBACK_MISSING`/`ACK_MISSING` 회수 케이스를 신규 도출.

### [B-ISSUE-24] TC-VLM-001~040 — B-7 절 근거 `file:line` 대량 드리프트 + 신규 컴포넌트 5종 케이스 미도출
- **심각도**: MEDIUM (카탈로그 정합 — 검증 재현성 훼손)
- **기대 동작(기대효과)**: 근거 `file:line` 으로 케이스를 바로 대조할 수 있어야 하고, 프로덕션에 존재하는 실행 컴포넌트는 케이스로 커버돼야 한다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep` 이 **418줄**로 재작성되어 B-7 의 스텝 관련 근거가 사실상 전부 어긋난다. 실측 대조(카탈로그 → 실제):
  `001` 135-142→**221-226** / `002` 139-141→**224-226** / `003` 182-186→**269-273** / `004` 174-176→**261-263** / `005` 189-191→**276-278** / `006` 280-290→**390-400** / `007` 231-238→**318-325** / `008` 232→**319** / `009` 247-250→**364-365(의미도 변경)** / `010` 78,246→**소멸** / `011` 262,300-308→**337 + VlmMarkingTxService:53-61** / `012` 300-308→**VlmMarkingTxService:53-61** / `013` 311-319→**403-411** / `031` 216-220,222→**303-307,309** / `032` 206-220→**260-307** / `033` 215-216→**303** / `034` VlmWithheldResumeRunner 54-70→**57-72** / `035` 79-93→**88-101** / `036` BatchStatusService 74-78→**115-119** / `037` 71-76→**73-78** / `038` BatchStatusService 109-116→**149-156** / `039` 57-64,104-108→**58-62,100-105** / `040` 133-142,150-168→**218-227,236-255**.
  또한 **케이스가 하나도 없는 신규 실행 컴포넌트**: `VlmSubmitOutcomeRecorder`, `VlmSubmitPendingSweeper`(ACK/콜백 창 회수), `VlmMarkingTxService`, `SubmitSignalDispatch`(전용 풀 거부 처리), `vlmSubmitScheduler`. 이들에는 이미 테스트가 6파일 존재(`VlmTimeseriesStepNonBlockingTest`, `VlmSubmitOutcomeRecorderTest`, `VlmSubmitPendingSweeperTest`, `VlmSubmitAckWindowIT`, `VlmSubmitReclaimAtomicClaimIT`, `VlmAsyncSubmitTransactionBoundaryIT`)인데 카탈로그에는 대응 케이스가 없다.
  반면 **`VlmResultService`·`MarkingService`·`MarkingGuards`·`MarkingBatchBridge`·`BatchTransitionService` 의 근거 라인은 거의 정확**했다(오차 ≤2줄).
- **재현/확인 경로**: `grep -n "public BatchStage stage\|private VlmTimeseriesResponse doSubmit\|resolveCallbackUrl" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java`
- **영향**: 다음 회차가 B-7 을 정적 대조할 때 근거를 찾지 못해 판정 품질이 떨어지고, 논블로킹 전환으로 새로 생긴 **회수 경로(스위퍼)** 가 무검증 상태로 남는다.
- **수정 방향(제안)**: ① B-7 절 근거 라인을 위 실측값으로 일괄 갱신 ② `VlmSubmitPendingSweeper`(ACK 창/콜백 창 2패스·원자 클레임)·`VlmSubmitOutcomeRecorder`(ACK 기록/실패 보상, 상태 강등 금지)·`SubmitSignalDispatch`(전용 풀 거부 시 호출 스레드 미사용) 케이스를 신규 도출 ③ `TC-VLM-011/012` 를 `VlmMarkingTxService` 근거로 재작성.

### [B-ISSUE-25] TC-BATCH-096 — AUTO 마킹 `intervalFrames` 상한 미검증 (이월 B-ISSUE-23, **미해소**)
- **심각도**: LOW
- **기대 동작(기대효과)**: `intervalFrames` 는 하한(≥1)뿐 아니라 총 프레임 수(`round(durationSec×fps)`) 대비 상한도 검증돼야 한다. 상한이 없으면 "자동 마킹"이 사실상 1프레임 마킹으로 퇴화하면서도 201 을 반환해, 사용자는 정상 자동 마킹이 된 줄 안다.
- **현재 동작(이슈 내용)**: 실동작 재현 — `POST /v1/videos/6/markings {"mode":"AUTO","intervalFrames":999999999}` → **201**, `"marks":[{"frameIndex":0,"timestamp":"00:00"}]` 1건, `frme_intv_nocs=999999999` 로 그대로 DB 적재, 배치까지 정상 트리거(`batchTriggered:true`).
  ```java
  // MarkingService.java:196-198  — 하한만
  if (req.intervalFrames() == null || req.intervalFrames() <= 0) { throw ... }
  // MarkingService.java:277      — 상한 없음
  for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) { ... }
  ```
  대조: MANUAL 경로에는 상한 검증이 있다(`manualFrameIndexLimit`, TC-BATCH-093 실측 310). **AUTO/MANUAL 비대칭.**
- **재현/확인 경로**:
  ```bash
  curl -X POST http://localhost:18081/api/v1/videos/{MARKING_READY_rawSn}/markings \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' \
    -d '{"mode":"AUTO","intervalFrames":999999999}'   # → 201, marks 1건
  ```
- **영향**: 기능 — 자동 마킹 퇴화(프레임 1장만 추출 → 학습데이터 결손)가 무증상으로 통과. 보안 영향 없음.
- **수정 방향(제안)**: `MarkingService.create` AUTO 분기에서 `intervalFrames >= totalFrames`(= `round(autoDurationSec×fps)`) 이면 `INVALID_INPUT`. 단 `durationSec` 미상(null)일 때는 MANUAL 과 동일하게 **상한만 skip + WARN**(전부 skip 금지). 검증 위치는 `generateAutoMarks` 내부가 아니라 `create` 로 두어 메시지에 상한값을 실을 것.

### [B-ISSUE-26] TC-BATCH-062 — 브릿지 skip 사유 응답 반영의 skip 분기를 HTTP 로 재현할 수 없다(검증 사각)
- **심각도**: LOW
- **기대 동작(기대효과)**: 마킹은 201 인데 배치가 시작되지 않은 경우 `batchTriggered=false` + `batchSkipReason` 이 응답에 실려 "무음 스킵"이 없어야 한다. 이 경로가 **실환경에서 실제로 도달 가능**해야 케이스가 의미를 갖는다.
- **현재 동작(이슈 내용)**: `MarkingGuards.requirePreconditions` 가 `LS_DATA_RAW.DATA_STTS_CD == MARKING_READY` 와 `DE_IDNTF_YN='Y'` 를 요구하므로(`MarkingGuards.java:83-90`), 브릿지의 skip 조건 4종 중 `REASON_STAGE_ALREADY_RUN`·`REASON_NOT_DEIDENTIFIED`·`REASON_VIDEO_NOT_FOUND` 는 **마킹 API 단계에서 이미 412/404 로 거부되어 브릿지에 도달하지 않는다**. 유일하게 도달 가능한 것은 `REASON_ALREADY_CLAIMED`(stage=MARKING_READY + work=검수소유, 예: 배정→검수제출→반려 이력이 있는 영상)뿐이며, 이 상태 조합은 API 만으로 만들려면 검수 워크플로를 한 바퀴 돌려야 한다. 실동작으로 확인한 것은 `batchTriggered:true` 분기뿐이다.
- **재현/확인 경로**: rawSn=4(COMPLETED)·rawSn=5(PROCESSING) 재마킹 → 둘 다 `412`(브릿지 미도달). `MarkingBatchBridgeTest` 4건이 단위로만 커버.
- **영향**: 검증 사각 — 응답 필드 배선이 실제 HTTP 경로에서 살아 있는지 확인되지 않은 채 남는다. 기능 결함은 아님.
- **수정 방향(제안)**: 다음 회차에 `배정 → 검수 제출 → 반려`로 `work=REJECTED · stage=MARKING_READY` 영상을 만들어 마킹 → `batchTriggered=false` + `REASON_ALREADY_CLAIMED` 를 실측하는 절차를 케이스 비고에 명시(또는 TC-BATCH-062 를 그 시나리오로 구체화).

### [B-ISSUE-27] 비식별이 **한 번도 성공한 적 없는** 영상에 신고를 접수하면 해소 불가 — 작업락·VLM 보류 영구 고착
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 비식별 누락 신고는 "외부 솔루션으로 수동 재비식별 후 `resolve` 로 해제" 하는 복구 가능한 상태여야 한다. 특히 VLM 보류는 **`resolve` 가 유일한 재개 경로**이므로(`VlmTimeseriesStep.java:296-300` Javadoc), 해소 불가 상태가 만들어지면 시계열 메타가 영구 결손된다.
- **현재 동작(이슈 내용)**: 신고 접수 가드는 `DE_IDNTF_YN='N'`(미수행)만 412 로 막고 `'F'`(시도했으나 실패)는 통과시킨다(`DeidentReportService.doReport` → `requireDeidentAttempted`, `:186`). 반면 해소 가드는 **성공 procLog 존재**를 필수 전제로 한다:
  ```java
  // DeidentReportService.verifyDeidentArtifact :543-546
  LsDeidentProcLog procLog = procLogRepository.findLatestSuccessByDataRawSn(rawSn)
          .orElseThrow(() -> deidentNotVerified(rawSn));   // 성공 이력 0건 → 항상 409
  ```
  → 비식별이 처음부터 실패해 성공 procLog 가 0건인 영상(`KPST_SOURCE_MISSING` 등)은 신고가 201 로 접수되지만 `resolve` 가 **구조적으로 영구 409**다. 실측:
  - `POST /v1/videos/11/deident-report` → **201** (rprtSn=2, rawSn=11 은 `de_ident_yn='F'`, `ls_deident_proc_log` 성공행 0건)
  - `POST /v1/deident-reports/2/resolve` → **409 "비식별 산출물이 확인되지 않습니다…"** (재시도해도 동일)
  - 대조군: 성공 procLog 가 있는 rawSn=6 은 산출물 mtime 갱신 후 `resolve` → **200** 정상
  결과적으로 `LS_DEIDENT_REPORT` 는 OPEN 고착, 작업락 미해제, `DE_IDNTF_YN='F'` 고착 → 라벨 조회 게이트·export 게이트·VLM 재개가 모두 영구 차단된다.
- **재현/확인 경로**:
  ```sql
  -- 전제: 비식별이 실패해 성공 procLog 가 없는 영상
  select r.raw_sn, r.de_ident_yn,
         (select count(*) from ls_deident_proc_log p
           where p.data_raw_sn=r.raw_sn and p.proc_stts_cd='SUCCEEDED') as ok_logs
    from ls_data_raw r where r.de_ident_yn='F';
  ```
  ```bash
  curl -X POST .../v1/videos/11/deident-report -d '{"reason":"x"}'   # 201
  curl -X POST .../v1/deident-reports/2/resolve                      # 409 (영구)
  ```
- **영향**: 데이터 정합/가용성 — 복구 진입점 없는 잠금 상태 생성(fail-closed 가 과도하게 흡수). 관리자 UI 로도 해제 수단이 없다(`DISMISSED` 전이 API 부재 확인). 보안 관점에서는 안전한 방향(과차단)이라 CWE 없음.
- **수정 방향(제안)**: 셋 중 택1 — ① 접수 가드 강화: `requireDeidentAttempted` 를 "성공 procLog ≥1건" 으로 좁혀 성공 이력 없는 영상은 **412 로 접수 거부**(신고 대신 배치 재처리 안내). ② 해소 가드 완화: 성공 procLog 가 0건이면 procLog 대신 `LS_DATA_RAW` 기준 비식별 산출물 경로를 `DeidentArtifactIntegrity` 로 직접 검증하는 대체 분기 추가. ③ REVIEWER 전용 `DISMISSED` 전이 API 를 노출해 관리자가 오접수를 닫을 수 있게 함. **①이 최소 변경이며 정책과도 정합**(비식별을 못 한 영상은 신고 대상이 아니라 배치 재처리 대상).

### [B-ISSUE-28] TC-VLM-020 — 콜백 내 중복 metaKey 거부를 실동작으로 재현할 수 없음(목업 콜백 지연 2초)
- **심각도**: LOW (검증 절차 — 코드 결함 아님)
- **기대 동작(기대효과)**: 한 콜백 안의 중복 `metaKey`(=`{start_sec}-{end_sec}`) 는 400 으로 거부되고 조용한 덮어쓰기가 없어야 한다. 이 분기는 원장 상태가 `ISSUED/ACCEPTED`(미처리)일 때만 도달한다.
- **현재 동작(이슈 내용)**: mock-server 의 `callback_delay=2.00s` 로 인해 위탁 발급~mock 콜백 도착 창이 2초뿐이라, 그 안에 수동 중복 콜백을 밀어넣는 시도가 2회 모두 실패했다(그 사이 원장이 `PROCESSED` 로 마감돼 `VlmResultService.java:77-80` 멱등 스킵으로 200/`applied:false` 반환). 코드·단위테스트는 정상.
- **재현/확인 경로**: `select idmp_key,stts_cd from ls_webhook_idempotency where raw_sn=?` 로 `PROCESSED` 이전 창을 잡아야 함. 실행 로그상 창 확보 실패.
- **영향**: 검증 사각(1건). 실제 리스크는 낮음(단위테스트 커버).
- **수정 방향(제안)**: mock-server 의 콜백 지연을 환경변수로 늘릴 수 있으면(예: `MOCK_CALLBACK_DELAY`) 다음 회차에 지연을 키운 상태로 발급 창을 확보해 실동작 판정한다. 코드 수정은 불필요.

---

## 검증 환경 잔여물 (다음 회차 참고 — 원복하지 않았음)

| 대상 | 상태 | 사유 |
|---|---|---|
| rawSn=5 | `ls_data_raw.data_stts_cd=COMPLETED`, 작업상태 `ASSIGNED`, 마킹 1건(VLM_COMPLETED), 시계열 메타 1건 | 정상 파이프라인 완주(의도) |
| rawSn=6 | `de_ident_yn='Y'`(신고 해소됨), `data_stts_cd=FAILED`, `ls_batch_proc_log` 에 VLM/SKIPPED + FRAME_EXTRACT/FAILED | 신고→보류→해소 시나리오 실행 결과. 신고 #1 은 `RESOLVED` |
| rawSn=6 비식별본 파일 | `/app/storage/raw/seed/6/deid/clip-9103-mask.mp4` 의 **mtime 만** 갱신(내용 불변) | `resolve` 의 산출물 신선도 게이트 통과용(외부 수동 재비식별 대체) |
| rawSn=11 | `data_stts_cd=FAILED`, `de_ident_yn='F'`, **신고 #2 OPEN 고착 + 작업락 미해제** | B-ISSUE-27 재현 부산물. 해소 API 가 구조적으로 409라 원복 불가 |
| 마킹 PK | `ls_marking.marking_sn` 이 2→5 로 점프 | 동시 3요청 중 2건이 IDENTITY 시퀀스만 소비하고 롤백(정상) |

> 코드·설정·다른 결과 파일은 일절 수정하지 않았고, 컨테이너도 재기동하지 않았다. 빌드/테스트도 실행하지 않았다(테스트 커버 확인은 파일 Grep 으로만 수행).
