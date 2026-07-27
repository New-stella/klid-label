# B 클러스터 Part2 — B-5/B-6/B-7 실동작 전수 검증 (2026-07-25 1차)

> 대상: `docs/test-cases/B-batch-deidentify.md` 78~149행 (TC-BATCH-050~089, TC-VLM-001~030)
> 스택: klid-postgres / klid-backend(:18081→8080) / klid-ai-server / klid-mock-server(:9400) / klid-frontend 전부 기동
> DB 스키마: `public` (klid_system, user=klid_user)
> 토큰: `POST /v1/dev/tokens` — ★함정: INTERNAL 채널 인가역할은 **토큰 role 클레임이 아니라 `LS_USER_ROLE` 조회**가 출처.
> 실재 userNo(1001=REVIEWER, 2001/2002=WORKER, 3001=PORTAL_USER)로 발급하지 않으면 전 API 403 (초기 오진 → 정정 후 재실행)
> ★VLM 전제: `VLM_CLIENT_ENABLED=false` (backend 컨테이너 env 실측). `_raw/vlm-wiring.md` 실증 결과 —
> `WebClientConfig.vlmWebClient` 의 **HTTPS-only + 사설IP 차단 가드** 때문에 로컬 mock 배선이 **원천 불가**(부트 크래시).
> mock-server 24h 인바운드 로그에 `videovlm` 요청 **0건**, `ls_webhook_idempotency` **0행** → **VLM 외부연동 전 구간 미경유**.

## B-5. 마킹 완료 브릿지 (동시성·가드)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-050 | 브릿지: 영상 미존재 → skip | PASS | 정적 `MarkingBatchBridge.java:62-66` findById empty → warn+return. 라인 일치 | MarkingBatchBridgeTest#onMarkingCompleted_noRawVideo_skips | 실동작 재현 불가(이벤트는 마킹 생성 시에만 발행되고 마킹은 영상 존재를 요구) — 논리적 도달 불가 방어층 |
| TC-BATCH-051 | PROCESSING/COMPLETED 재트리거 차단 | PASS | 정적 :45-46,:72-76 `SKIP_BATCH_STAGES`. **실동작 간접**: rawSn 19(COMPLETED)·26(COMPLETED) 마킹 요청 → 412 로 상위 가드가 선차단(이벤트 미발행) | MarkingBatchBridgeTest#onMarkingCompleted_rawDataCompleted_skips, #_rawDataProcessing_skips | 2중 방어. HTTP 경로로는 브릿지 가드에 도달 자체가 안 됨 |
| TC-BATCH-052 | 비식별 미완료 트리거 차단 | PASS | 정적 :80-84 `DEIDENTIFIED.equals` 부정. **실동작 간접**: rawSn 9(deIdntfYn='F', MARKING_READY) 마킹 → 412 PRECONDITION_FAILED | MarkingBatchBridgeTest#_notDeidentified_skips, #_deidentFailed_skips | 우회 경로 탐색 결과 없음 — 마킹 생성 API 가 유일한 이벤트 발행처(`MarkingService.java:187` 단일) |
| TC-BATCH-053 | tx1 claim 성공 → 트리거 | **PASS(LIVE)** | rawSn 24 에 배정 생성(status row=ASSIGNED, ver=1) → WORKER 2001 AUTO 마킹 201 → 로그 `enqueued rawSn=24` **1회**, `AsyncBatchRunner starting batch rawSn=24` **1회**. status ver 1→3 | BatchTransitionServiceTest#tryClaimBatchQueued_affectedOne_returnsTrue_andPersists | tx1(조건부 UPDATE) 경로 실측 확인 |
| TC-BATCH-054 | 미배정 REVIEWER — row 부재 시 생성 | **PASS(LIVE)** | rawSn 15/16 은 `ls_raw_data_status` row **부재** 상태 → REVIEWER 직접 마킹 201 → row 생성 + `enqueued` + 배치 기동. 마킹 전 `select … where raw_data_id in (15,16)` = 0행 → 마킹 후 1행 | MarkingBatchBridgeTest#_unassignedRowAbsent_tx2Creates_triggers | FIX B(고착 제거) 실동작 입증 |
| TC-BATCH-055 | 동시 row 생성 경쟁 — 1건만 | **PASS(LIVE)** | rawSn 16 에 3 동시 POST → 3건 모두 201, 브릿지 3회 진입. 로그: `enqueued rawSn=16` **1회**, `concurrent row creation rawSn=16 — skipping` **2회** → `batch already claimed/in-progress` 2회. 배치 스레드는 `batch-async-2` **단 1개** | BatchTransitionServiceRowCreationIT#concurrentRowCreation_exactlyOneClaims_noExceptionPropagates | PG unique 위반 → 별도 REQUIRES_NEW tx 밖에서 catch 하는 패턴이 실제로 작동(같은 tx 재시도 회피) |
| TC-BATCH-056 | 동시 2 이벤트 중 1건만 BATCH_QUEUED | PASS | **실동작(위 3동시)에서 최종 불변식(배치 1회) 입증**. 단 실측 경로는 tx2(row 생성 경합)였고, 케이스가 지목한 tx1 조건부 UPDATE 직렬화(`BatchTransitionService.java:187-195`)는 로컬 데이터상 재현 불가(MARKING_READY 영상에 status row 가 없었음) | MarkingBatchBridgeTest#_concurrentDoubleMarking_triggersOnce (tx1 경로) | 근거 라인 일치 |
| TC-BATCH-057 | 이미 claimed면 skip | **PASS(LIVE)** | 로그 `[MarkingBatchBridge] batch already claimed/in-progress rawSn=16 — skipping` ×2 (`:106-109`) | MarkingBatchBridgeTest#_bothClaimFalse_skips | |
| TC-BATCH-058 | tryCreateBatchQueuedRow: row 존재 → false 멱등 | PASS | 정적 `BatchTransitionService.java:239-241` existsById → false | BatchTransitionServiceTest#tryCreateBatchQueuedRow_existingRow_false | 케이스 근거 238-241 중 238은 주석 — 실질 일치 |
| TC-BATCH-059 | 할당형 PK saveAndFlush 즉시 flush | PASS | 정적 :242-247 `saveAndFlush`+예외 미포착 전파. **실동작**: rawSn16 경합에서 `DataIntegrityViolationException` 이 브릿지까지 전파돼 catch 됨(로그) | BatchTransitionServiceTest#_concurrentInsert_propagatesException + RowCreationIT | |
| TC-BATCH-060 | 로그 인젝션 방어(CWE-117) | PASS | 정적 :120-122 `replace("\n","").replace("\r","")`, 적용처 :74(dataSttsCd) :82(deIdntfYn) 2곳. 그 외 로그 인자는 `rawSn`(Long)이라 주입 불가 | **없음** | 회귀 테스트 부재 → [B-ISSUE-26]. Tab(`\t`)은 미제거(로그 1행 유지에는 무해) |

## B-6. MarkingService (자동/수동, 경계값, 인가)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-070 | actor null → UNAUTHORIZED | **PASS(LIVE)** | 무토큰 `POST /v1/videos/15/markings` → **401** `{"errorCode":"UNAUTHORIZED"}` | MarkingControllerTest#미인증_요청_401 | SecurityConfig 단계에서 차단(프로브 이전) |
| TC-BATCH-071 | REVIEWER 전체 허용 | **PASS(LIVE)** | REVIEWER(1001)가 **배정 없는** rawSn 15/16/25 에 마킹 접근 성공(15/16=201, 25=검증거부 400) `MarkingGuards.java:53-55` | MarkingServiceTest#비식별_완료_영상_마킹생성_정상_가드_통과 | |
| TC-BATCH-072 | 미배정 WORKER → FORBIDDEN(존재 미노출) | **PASS(LIVE)** | WORKER 2001 → rawSn 15: **403** "본인에게 배정된 영상의 마킹만…" / **미존재 99999 도 403**(NOT_FOUND 아님) → 리소스 존재 미노출 확인 | MarkingControllerTest#I4_타인배정_영상…403, MarkingServiceTest#I4_미배정_WORKER_FORBIDDEN | 반증 시도(존재/미존재 응답 차이) → 차이 없음 |
| TC-BATCH-073 | 인가 우선 — 미배정 시 ffprobe 미실행 | PASS | 정적 `MarkingService.java:99-108`(precheck → AUTO 만 resolve). 실동작 간접: 미배정 WORKER AUTO 요청이 즉시 403(수 ms) | MarkingServiceOrchestrationTest#미배정_WORKER_AUTO요청_프로브_미트리거되고_FORBIDDEN 외 2건 | ffprobe 호출 여부는 HTTP 로 직접 관측 불가 → 단위테스트 위임 |
| TC-BATCH-074 | 영상 미존재 → NOT_FOUND | **PASS(LIVE)** | REVIEWER + 99999 → **404** "영상을 찾을 수 없습니다." (`MarkingGuards.java:77-79`) | MarkingControllerTest#POST_미존재_영상_마킹_404 | 인가 통과자에게만 404 노출 = 설계대로 |
| TC-BATCH-075 | 비식별 미완료 → 412 | **PASS(LIVE)** | rawSn 9(deIdntfYn='F') → **412** "비식별이 완료된 영상에서만 마킹할 수 있습니다." | MarkingServiceTest#비식별_미완료_영상…PRECONDITION_FAILED | |
| TC-BATCH-076 | MARKING_READY 아님 → 412 | **PASS(LIVE)** | rawSn 19/26(COMPLETED) → **412** "이미 처리된 영상은 재마킹할 수 없습니다." | MarkingServiceTest#이미_COMPLETED…, #이미_PROCESSING… | 배치 FAILED 영상(15/16/24)도 동일 412 → 재마킹 불가(재처리 큐 소관) |
| TC-BATCH-077 | 이벤트유형 미지정 → INVALID_INPUT | PASS | 정적 `MarkingGuards.java:88-92` | MarkingServiceTest#이벤트유형_null…, #이벤트유형_blank…, MarkingControllerTest#POST_이벤트유형_미지정_400 | 로컬 전 영상이 `evnt_type_cd` 보유 → live 재현 불가 |
| TC-BATCH-078 | AUTO intervalFrames null/≤0 → 400 | **PASS(LIVE)** | rawSn15: null→400, 0→400, -5→400 모두 "자동 모드에서 intervalFrames 는 1 이상이어야 합니다."(`MarkingService.java:162-164`) | MarkingServiceTest#자동모드_intervalFrames_0이하_INVALID_INPUT | ★상한 미검증 발견 → [B-ISSUE-23] |
| TC-BATCH-079 | durationSec null/≤0 backstop | PASS | 정적 :219-223 | MarkingServiceTest#자동마킹_주입_durationSec_null…, #…0이하…, #FIX_A_외부해석이_전부_실패해_null이_주입되면_backstop | live 는 VDO_LEN_SEC 이 항상 존재해 1단 폴백에서 해결 |
| TC-BATCH-080 | AUTO 정상 marks(실 fps, off-by-one) | **PASS(LIVE)** | rawSn 24(dur=30s, fps=30, interval=30) → `json_array_length(mark_cn)=**30**`, 마지막 원소 `{"frameIndex":870,"timestamp":"00:29"}` → totalFrames=900 미포함(off-by-one 정확), step 30 정확 | MarkingServiceTest#자동마킹_끝경계프레임_미포함_off_by_one_검증 외 5건 | rawSn16(dur=113,fps=30,interval=600) → 0/600/…/3000 (3390 미만) 도 정합 |
| TC-BATCH-081 | 분수 fps(29.97) 반올림 | PARTIAL | 정적 `MarkingService.java:226` `Math.round(durationSec*fps)` 확인. 그러나 **generateAutoMarks 에 29.97 을 넣는 테스트가 없음**(fps 25/30/60 만). 29.97 파싱은 VideoFpsResolverTest#분수fps_29.97_정확파싱 이 별도 커버 | VideoFpsResolverTest#분수fps_29.97_정확파싱 (파싱만) | 커버리지 갭 → [B-ISSUE-27] |
| TC-BATCH-082 | fps pin 저장(TOCTOU 제거) | **PASS(LIVE)** | `ls_marking.fps` 컬럼 실측 — markingSn 24/25/26/27/30 모두 `30` 저장. `MarkingService.java:157,180-182` createAuto/createManual 에 fps 전달 | MarkingServiceTest#자동마킹_해석한실fps25가_마킹레코드에_pin…, #수동마킹도…pin된다 | |
| TC-BATCH-083 | MANUAL marks 비면 400 | **PASS(LIVE)** | rawSn 25(MARKING_READY): `marks:[]`→400, `marks` 생략→400 "수동 모드에서 marks 는 필수입니다."(`:169-172`) | **없음**(서비스 단위테스트 미확인) | 거부 케이스라 대상 영상 상태 불변(재확인함) |
| TC-BATCH-084 | mode 미지 → 400 | **PASS(LIVE)** | rawSn25: `"X"`→400, `"auto"`(소문자)→400, `" AUTO "`(공백)→400 모두 "mode 는 AUTO 또는 MANUAL 이어야 합니다."(`:174-175`). 별도로 `""`→400 (@NotBlank) | 없음 | 대소문자·trim 미허용 = fail-closed |
| TC-BATCH-085 | 생성 성공 → MarkingCompletedEvent 발행 | **PASS(LIVE)** | 201 직후 로그 `[Marking] created rawSn=24 …` → 동일 스레드 `[MarkingBatchBridge] handling marking completed rawSn=24` (`:187`) | MarkingServiceTest#마킹_생성시_MarkingCompletedEvent_발행 | |
| TC-BATCH-086 | 이벤트명 자동소싱 = evntTypeCd | **PASS(LIVE)** | rawSn24 `evnt_type_cd='INTRUSION'` → 응답 `eventName:"INTRUSION"`, rawSn15/16 `EV03000101` → 동일 (`:148`) | 없음(응답 단언으로 간접) | |
| TC-BATCH-087 | persist self 프록시(AFTER_COMMIT 보존) | **PASS(LIVE)** | AFTER_COMMIT 리스너가 **실제로 발화**(위 로그) = persist 가 프록시 경유 트랜잭션에서 커밋됐다는 실증. self 자기호출이었다면 트랜잭션 부재로 이벤트가 즉시/미발화 | MarkingServiceOrchestrationTest(오케스트레이션), VlmMarkingTransitionPersistenceIntegrationTest(유사 패턴) | |
| TC-BATCH-088 | durationSec 3단 폴백 | PASS | 정적 `VideoDurationResolver.java:74-97` 3단 + null. live 는 1단(VDO_LEN_SEC)만 사용 | VideoDurationResolverTest 8건(3단 전부 + 경계), VideoDurationResolverTxIsolationIT#커넥션0 | |
| TC-BATCH-089 | 컨트롤러: WORKER/REVIEWER만 | **PASS(LIVE)** | PORTAL_USER(3001) 토큰 → **403** `{"message":"권한이 없습니다."}` (`MarkingController.java:51`). WORKER 배정자 201, REVIEWER 201 | MarkingControllerTest#I4_본인배정_영상_마킹생성_WORKER_201 | 채널 격리도 동시 확인(CHANNEL_PORTAL → /v1/videos/** 거부) |

## B-7. VLM 위탁 Step + 콜백 수신

> ★공통 제약: `VLM_CLIENT_ENABLED=false` + `WebClientConfig` HTTPS-only 가드로 **목업 경유 실동작 검증 자체가 불가**([B-ISSUE-21]).
> 아래 "PASS(정적)"는 코드+단위/통합테스트 기반이며 **외부 벤더 실왕복은 미확인**임을 명시한다.

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-VLM-001 | 마킹 있으면 runWithMarking(first) | PASS(정적) | `VlmTimeseriesStep.java:104-111` `markings.get(0)`. **live 로 다중 마킹 상황 실재 확인**(rawSn16 `marking check count=3`) → 2·3번째 마킹은 위탁 대상에서 누락 | VlmTimeseriesStepMarkingTest#runWithMarking_… | 다중 마킹 누락 → [B-ISSUE-22] |
| TC-VLM-002 | 마킹 없으면 run | PASS(정적) | :108-109 | VlmTimeseriesStepMarkingTest#runWithMarking_null_마킹시_기존_run_호출과_동일 | |
| TC-VLM-003 | enabled=false → 즉시 SKIPPED, 외부호출 0 | **PASS(LIVE)** | 배치 로그 `[Batch][VlmTimeseries] skipped (disabled) rawSn=15/16/24` (`:147-151`). **mock-server 24h 인바운드 `videovlm` 0건**, `ls_webhook_idempotency` **0행**(등록도 안 함) | VlmTimeseriesStepTest#enabled_false_시_외부_호출_0건_등록_0건_SKIPPED_반환, #_recordVlmTimeseriesResult_미호출 | ★단, DB에 skip 흔적 0 → [B-ISSUE-24] |
| TC-VLM-004 | rawSn null → INVALID_INPUT | PASS(정적) | :143-145 | VlmTimeseriesStepTest#rawSn_null_시_INVALID_INPUT | |
| TC-VLM-005 | 영상 미존재 → NOT_FOUND | PASS(정적) | :153-156 (enabled 체크 **이후**) | VlmTimeseriesStepTest#영상_미존재_시_NOT_FOUND_예외_등록_미수행 | |
| TC-VLM-006 | 비식별 경로 없음 → fail-closed | PASS(정적) | :214-224 최신 성공 procLog 의 `DE_IDNTF_FILE_PATH_NM` 만 사용, 없으면 EXTERNAL_API_ERROR. 원본 경로 전송 코드 없음(Grep 확인) | VlmTimeseriesStepTest#비식별_경로_없으면_원본_미전송_fail_closed | 개인정보 보호 핵심 가드 |
| TC-VLM-007 | request_id 등록 실패 → describe 미호출 | PASS(정적) | :166-173 try/catch → EXTERNAL_API_ERROR abort (describe 이전) | VlmTimeseriesStepTest#recordIssued가_describe_호출_전에_수행됨_등록실패시_describe_미호출 | |
| TC-VLM-008 | recordIssued 독립 커밋(REQUIRES_NEW) | PASS(정적) | :163-167 + `WebhookIdempotencyLedger.recordIssued` REQUIRES_NEW | VlmTimeseriesStepTest#위탁_성공_시_recordIssued로_… | live 미검증(ledger 0행) |
| TC-VLM-009 | describe 응답 null → EXTERNAL_API_ERROR | PASS(정적) | :182-185 | VlmTimeseriesStepTest#빈_응답_시_EXTERNAL_API_ERROR | |
| TC-VLM-010 | 45s 블록 타임아웃 | 확인필요 | 정적 :74 `BLOCK_TIMEOUT=45s`, :181 `.block(BLOCK_TIMEOUT)`. **그러나 실효 타임아웃은 `VlmClient` 의 `vlm.client.timeout-seconds=10`**(`VlmClient.java:71,76,108` `.timeout(timeout)`) → 45s block 은 도달 불가 상한. 케이스 문구 "45s 타임아웃"은 실효값과 불일치 | **없음**(타임아웃 테스트 부재) | [B-ISSUE-27]. Retry(exp backoff) 누적 시에만 45s 가 의미 |
| TC-VLM-011 | 위탁 성공 시 마킹 PENDING→VLM_REQUESTED | PASS(정적) | :196,:234-242 `markVlmRequested()` true 시에만 save | VlmTimeseriesStepMarkingTest#runWithMarking_마킹_상태_VLM_REQUESTED_전이, VlmMarkingTransitionPersistenceIntegrationTest | **live 반증**: DB `ls_marking` 전 10행이 `PENDING` — 위탁이 한 번도 안 일어남을 확증(self-fill 없음 ✓) |
| TC-VLM-012 | retry — 이미 전이된 마킹 no-op | PASS(정적) | :234-241 transitioned=false → save 미호출 | VlmTimeseriesStepMarkingTest#retry_재실행_시_이미_VLM_COMPLETED…역행하지_않는다, PersistenceIT#retry… | |
| TC-VLM-013 | 콜백 URL 고정 base(SSRF 차단) | PASS(정적) | :244-253 `callbackBaseUrl`(@Value `authoring.webhook.callback-base-url`) + 상수 `HmacWebhookFilter.PATH_VLM` 결합. 요청 DTO·사용자 입력이 URL 구성에 개입하는 경로 없음 | 없음(전용 테스트 부재) | [B-ISSUE-27] 커버리지 |
| TC-VLM-014 | 미발급 request_id → UNAUTHORIZED | **PASS(LIVE)** | `POST /v1/vlm/callback {"request_id":"forged-abc-123","status":"completed",…}` → **401** "발급되지 않은 request_id 입니다."(`VlmResultService.java:69-74`) | VlmResultServiceTest#미발급_request_id_콜백은_UNAUTHORIZED_거부, VlmDescribeCallbackFlowIntegrationTest#미등록_request_id_콜백은_401 | 위조 콜백 8종 전부 401 |
| TC-VLM-015 | 이미 PROCESSED → 멱등 스킵 | PASS(정적) | :77-80 | VlmResultServiceTest#동일_request_id_재수신_시_멱등_스킵 | live 도달 불가(발급 ledger 0행) |
| TC-VLM-016 | rawSn 매핑 없음 → UNAUTHORIZED | PASS(정적) | :83-88 | VlmResultServiceTest#발급됐으나_rawSn_매핑_없으면_UNAUTHORIZED_거부 | 동상 |
| TC-VLM-017 | 미지 status → INVALID_INPUT(멱등 미마킹) | **PASS(LIVE)** | `status:"done"` → **400** "status 는 completed\|failed 중 하나여야 합니다."(DTO `@Pattern` 1차). 서비스 2차 방어 :94-102 는 정적 확인 | VlmResultServiceTest#미지_status_콜백은_INVALID_INPUT_거부되고_completed_오처리_및_멱등마킹_안함 | DTO 가 먼저 잡아 서비스 분기는 HTTP 로 도달 불가(2중 방어) |
| TC-VLM-018 | failed → error 기록+VLM_FAILED+멱등 | PASS(정적) | :105-109,:168-186. live 는 401 게이트에서 차단(정상) | VlmResultServiceMarkingTest#failed_콜백_수신시_VLM_REQUESTED_마킹을_VLM_FAILED로_전이_고착해제 | 코드 주석에 "자동 복구 잡 미구현" 명시(기지의 갭) |
| TC-VLM-019 | completed+영상 미존재 → NOT_FOUND | PASS(정적) | :112-116 | 없음(직접 테스트 미확인) | |
| TC-VLM-020 | 한 콜백 내 중복 metaKey → INVALID_INPUT | PASS(정적) | :192-204 `putIfAbsent` → 400. **live 시도**: dup metaKey 콜백 → 401(발급 게이트가 선행) → dedup 로직 미도달 | VlmResultServiceTest#한_콜백_내_중복_구간_metaKey는_400_거부 | live 재현 불가는 게이트 설계상 정상 |
| TC-VLM-021 | META upsert — 신규만 검수큐 PENDING | PASS(정적) | :123-150 | VlmResultServiceTest#배치_내_동일_영상_기존_meta는_값갱신만_신규_meta만_검수큐_진입 | live: `ls_data_meta` 1행(EXTERNAL 수기), VLM 유래 0행 → **self-fill 없음 확증** |
| TC-VLM-022 | 마킹 VLM_REQUESTED→VLM_COMPLETED | PASS(정적) | :152-157 | VlmResultServiceMarkingTest 3건 | |
| TC-VLM-023 | 원자성 — 중간 실패 시 PROCESSED 롤백 | PASS(정적) | :63 단일 `@Transactional` + :160 `markProcessedInTx`(REQUIRED, 마지막) | VlmResultServiceTest#검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능 | |
| TC-VLM-024 | 동일 request_id 동시 콜백 직렬화 | PARTIAL | 정적 :69 `lookupForProcessing`(비관적 락) 확인. **동시성 테스트 부재**(멱등 스킵은 순차 테스트만). live 도 발급 ledger 0행이라 재현 불가 | VlmResultServiceTest#동일_request_id_재수신_시_멱등_스킵(순차) | [B-ISSUE-27] — B-5 는 동시성 IT 가 있는데 콜백 측은 없음(비대칭) |
| TC-VLM-025 | 로그 마스킹(CR/LF/tab) | PASS(정적) | :48,:206-209 `[\r\n\t]` → `_` 치환, 전 로그 인자에 `safe()` 적용 확인 | 없음 | request_id 는 DTO `@Pattern ^[A-Za-z0-9_-]+$` 로 1차 차단(live 확인: `abc\ndef` → 400) |
| TC-VLM-030 | 콜백 무인증(HMAC 없음) — isIssued 게이트 차단 | PARTIAL | **live**: HMAC 헤더 없이 `POST /v1/vlm/callback` 도달 → 서비스 발급 게이트에서 401. `HmacWebhookFilter.java:105,150-153` VLM 은 `sizeCapOnlyPaths`(HMAC 미적용, 본문 4MB 캡만). request_id 는 UUIDv4 라 추측 불가 → 주입 차단은 성립 | 없음(컨트롤러 테스트 파일 부재) | ★그러나 **rate limit 미적용**(`isRateLimited` 는 HMAC 분기 :163-169 에만) + 컨트롤러 :22 "IP allowlist 재검토" TODO 미이행 → [B-ISSUE-25]. 위조 15연타 전부 401, 429 없음(실측) |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-21] TC-VLM-001~030 전반 — VLM 외부 연동이 로컬 목업을 구조적으로 경유할 수 없어 실동작 검증 불가
- **심각도**: HIGH
- **기대 동작(기대효과)**: 검증 대전제("외부 연동은 전부 목업서버가 대행")대로 `vlm.client.url` 을 `klid-mock-server:9400` 으로 지정하면 describe 위탁 → mock 콜백 왕복이 실동작으로 확인돼야 한다.
- **현재 동작(이슈 내용)**:
  - backend 실효 env `VLM_CLIENT_ENABLED=false` (컨테이너 env 실측) → `VlmTimeseriesStep.java:148-151` 즉시 SKIPPED.
  - 활성화 시도는 `_raw/vlm-wiring.md` 실증대로 **부트 크래시**: `WebClientConfig.java:130 validateExternalUrl` → `vlm.client.url 은 HTTPS 스키마만 허용됩니다 (현재: http)`. https 로 우회해도 docker 브리지 사설IP(172.18.0.4)가 SSRF 가드(:150-155)에 걸림. mock-server 는 TLS 미지원.
  - 실증: `docker logs klid-mock-server --since 24h | grep videovlm` → **0건**. `select * from ls_webhook_idempotency` → **0행**. `ls_marking` 전 10행 `stts_cd=PENDING`(VLM_REQUESTED 전이 0건). `ls_data_meta` 의 유일 1행은 수기 EXTERNAL 메타.
  - KPST(비식별)는 `KpstWebClientConfig` 가 http+사설IP 를 명시 허용해 정상 경유(mock 로그에 `/project`, `/retrieve_progress` 인바운드 확인) → **두 외부 클라이언트의 보안 정책이 비대칭**.
- **재현/확인 경로**:
  ```
  docker exec klid-backend env | grep VLM_CLIENT_ENABLED       # → false
  docker logs klid-mock-server --since 24h | grep -i videovlm  # → 0건
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select count(*) from ls_webhook_idempotency;"  # → 0
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,count(*) from ls_marking group by 1;"  # → PENDING만
  ```
- **영향**: TC-VLM-005~012, 015, 016, 018~024 (18건)의 **실왕복 검증 부재**. 벤더 계약(IntelliVIX v2.0.1) 정합은 코드/단위테스트 신뢰에만 의존. self-fill 은 **없음**(메타·마킹 전이 0건으로 확증) — 이 점은 오히려 fail-closed 로 정상.
- **수정 방향(제안)**: `WebClientConfig.vlmWebClient` 의 검증을 `KpstWebClientConfig` 와 동일하게 스킴 분기(http 허용+WARN)하거나 local 프로파일 한정 완화. (구현 금지 — vlm-wiring.md 의 동일 제안과 중복 계상)

### [B-ISSUE-22] TC-VLM-001 — 동일 rawSn 에 마킹 N건 생성 가능, VLM 은 첫 건만 위탁 → 나머지 마킹 영구 PENDING
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 영상 1건당 마킹은 1건으로 수렴하거나, 다중 마킹이 허용된다면 전 건이 파이프라인 상태 머신을 완주해야 한다.
- **현재 동작(이슈 내용)**: `MarkingService.create` 에 중복 마킹 방지 가드가 없다(`ls_marking` 에 rawSn UNIQUE 없음 — `\d ls_marking` 실측 인덱스는 PK + `idx_lm_raw` 비유니크). 동시 3요청 → `markingSn 25,26,27` 3행 생성(전부 201). 배치는 1회만 돌고(`MarkingLoadStep … count=3`), `VlmTimeseriesStep.java:107` 이 `markings.get(0)` 만 위탁 → 나머지 2건은 어떤 전이도 받지 못한다.
  - 실측: `select marking_sn,raw_sn,stts_cd from ls_marking where raw_sn=16;` → 25/26/27 모두 `PENDING`.
- **재현/확인 경로**: MARKING_READY 영상에 `POST /v1/videos/{rawSn}/markings` 를 3회 동시 실행 → 201 ×3, `ls_marking` 3행, 배치 1회.
  ```
  for i in 1 2 3; do curl -s -X POST .../v1/videos/16/markings -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":600}' & done; wait
  ```
- **영향**: 시계열 메타가 일부 마킹 기준으로만 생성됨. 프레임 추출도 어떤 marks 를 쓰는지 비결정적(순서 의존). 고아 마킹 누적으로 상태 조회/통계 왜곡.
- **수정 방향(제안)**: ① rawSn 당 활성 마킹 1건 제약(부분 유니크 인덱스 또는 서비스 가드 409) 또는 ② 배치가 전 마킹을 순회 위탁. 브릿지의 배치 1회 보장과 별개 축이므로 마킹 생성 측에서 막는 편이 blast-radius 가 작다.

### [B-ISSUE-23] TC-BATCH-078 — AUTO intervalFrames 상한 미검증 → 과대값이 marks 1건으로 퇴화
- **심각도**: LOW
- **기대 동작(기대효과)**: `intervalFrames` 가 `totalFrames` 를 초과하면 자동 마킹이 의미를 잃으므로 400 으로 거부하거나 최소 marks 수를 보장한다.
- **현재 동작(이슈 내용)**: `MarkingService.java:162-164` 는 하한(≥1)만 검증한다. `intervalFrames=999999999` 요청이 **201** 로 통과하고 `marks` 는 `[{"frameIndex":0,"timestamp":"00:00"}]` 단 1건 → 이후 프레임 추출도 1프레임만 산출.
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:18081/api/v1/videos/15/markings -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":999999999}'
  # → 201, marks 1건 (실측: markingSn=24)
  ```
- **영향**: 오조작/오입력 시 학습데이터 프레임이 1장만 생성되는데 상태는 정상 완료로 흐른다. `durationSec≤0` 은 backstop 으로 막으면서 반대편 경계는 무방비(비대칭).
- **수정 방향(제안)**: `@Max` 또는 서비스에서 `intervalFrames < totalFrames` 검증, 혹은 산출 marks 가 1건이면 경고/거부.

### [B-ISSUE-24] TC-VLM-003 — VLM 단계 skip(disabled)이 DB에 무흔적 → 시계열 메타 누락의 사후 추적 불가
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 파이프라인 단계가 건너뛰어졌다면 `LS_BATCH_PROC_LOG` 에 SKIPPED 로 남아, 완료된 영상에 시계열 메타가 없는 이유를 사후 추적할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.java:147-151` 이 로그만 남기고 즉시 반환하며 `batchStatusService.recordVlmTimeseriesResult` 를 호출하지 않는다(단위테스트 `#enabled_false_시_BatchStatusService_recordVlmTimeseriesResult_미호출` 로 의도 확정). 실측 `ls_batch_proc_log` 의 rawSn 16/24 행에 `proc_step_cd` 가 `FRAME_EXTRACT` 만 있고 **VLM 단계 행은 아예 없음**.
- **재현/확인 경로**:
  ```
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select proc_step_cd, proc_stts_cd from ls_batch_proc_log where data_raw_sn=24;"   # VLM 행 없음
  docker logs klid-backend | grep "VlmTimeseries] skipped"                                 # 로그에만 존재
  ```
- **영향**: 운영에서 VLM 비활성/장애 구간에 처리된 영상들이 "메타 없음 + 무기록"으로 남아, 사후 재처리 대상 식별이 로그 보존기간에 종속된다.
- **수정 방향(제안)**: disabled 경로에서도 `LS_BATCH_PROC_LOG` 에 `proc_step_cd=VLM, proc_stts_cd=SKIPPED` 1행 기록(또는 영상 단위 skip 플래그).

### [B-ISSUE-25] TC-VLM-030 — VLM 콜백이 무인증 + rate limit 미적용 + IP allowlist TODO 미이행
- **심각도**: MEDIUM (CWE-770 Unrestricted Resource Consumption / CWE-307 brute force 방어 부재)
- **기대 동작(기대효과)**: 인증 없이 공개된 엔드포인트는 최소한 요청 빈도 제한(또는 IP allowlist)으로 보호돼야 한다. 동일 필터가 augment 경로에는 이미 이 방어를 적용하고 있다.
- **현재 동작(이슈 내용)**: `HmacWebhookFilter.java:150-153` 이 `/v1/vlm/callback` 을 `sizeCapOnlyPaths` 로 분기해 **본문 4MB 캡만** 적용하고 즉시 체인으로 넘긴다. `isRateLimited()` 호출은 HMAC 분기(:163-169)에만 있어 VLM 경로는 무제한이다. `VlmResultController.java:22` 의 `// TODO(보안): 실운영 전 IP allowlist 재검토` 는 미이행. 콜백 1건당 `lookupForProcessing`(비관적 락 SELECT)이 무조건 실행된다.
- **재현/확인 경로**:
  ```
  for i in $(seq 1 15); do curl -s -o /dev/null -w "%{http_code} " -X POST \
    http://localhost:18081/api/v1/vlm/callback -H 'Content-Type: application/json' \
    -d "{\"request_id\":\"forged-rl-$i\",\"status\":\"failed\",\"error\":{\"code\":\"E\",\"message\":\"m\"}}"; done
  # 실측 → 401 401 401 ... (15회 전부, 429 없음)
  ```
- **영향**: 인증 없이 DB 락 SELECT 를 유발하는 pre-auth DoS 표면. 데이터 주입 자체는 UUIDv4 request_id 게이트로 차단되므로 **무결성 침해는 아님**(위조 8종 전부 401 확인).
- **수정 방향(제안)**: `handleSizeCapOnly` 에도 IP 기반 rate limit 적용, 그리고 prd 프로파일에서 벤더 IP allowlist 필터 추가(TODO 종결).

### [B-ISSUE-26] TC-BATCH-060 — 로그 인젝션(CWE-117) sanitize 회귀 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 계층(sanitize) 은 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: `MarkingBatchBridge.java:120-122` 의 `sanitize()` 는 구현돼 있으나 `MarkingBatchBridgeTest` 12개 케이스 중 CR/LF 를 주입해 검증하는 테스트가 없다(`grep -niE "sanitize|CWE-117"` 0건). 누군가 로그 문장을 리팩터링하며 `sanitize()` 를 빼도 테스트가 잡지 못한다.
- **재현/확인 경로**: `grep -c "sanitize" backend/src/test/java/kr/co/cudo/authoring/marking/listener/MarkingBatchBridgeTest.java` → 0
- **영향**: 방어 로직의 조용한 소실 위험. 현재 코드 자체는 정상(탭 미제거이나 로그 1행 파괴는 아님).
- **수정 방향(제안)**: `dataSttsCd="PROC\nING"` 같은 값으로 로그 캡처 단언 테스트 1건 추가.

### [B-ISSUE-27] TC-VLM-010/013/024/025, TC-BATCH-081 — 테스트 커버리지 갭 + "45s 타임아웃" 근거 불일치
- **심각도**: LOW (단, TC-VLM-010 근거 불일치는 **확인필요**)
- **기대 동작(기대효과)**: 케이스가 지목한 동작(타임아웃 실효값, SSRF 고정 base, 콜백 동시성, 로그 마스킹, 분수 fps 반올림)이 테스트로 고정돼 있어야 한다.
- **현재 동작(이슈 내용)**:
  1. **TC-VLM-010 근거 불일치**: 케이스는 "45s 블록 타임아웃"이나, 실효 타임아웃은 `VlmClient.java:71,76` 의 `vlm.client.timeout-seconds:10` → `:108 .timeout(timeout)` 이다. `VlmTimeseriesStep.java:74` 의 45s `block()` 은 Retry(exp backoff) 누적을 감안한 상한이며 단일 호출로는 도달하지 않는다. `application.yml:384 timeout-seconds: 10` 실측. → UNCERTAINTIES #13 의 "VLM 45s" 전제와 코드가 어긋남.
  2. 타임아웃 동작 테스트 **없음**.
  3. TC-VLM-013(콜백 URL 고정 base) 전용 테스트 **없음**.
  4. TC-VLM-024(동시 콜백 직렬화) 동시성 IT **없음** — B-5 브릿지는 동시성 IT 가 있는데 콜백 측만 비대칭.
  5. TC-VLM-025 로그 마스킹 테스트 **없음**.
  6. TC-BATCH-081: `generateAutoMarks` 에 29.97 을 넣는 테스트 없음(25/30/60 만).
  7. `VlmResultControllerTest` 파일 자체가 부재.
- **재현/확인 경로**: `grep -rn "BLOCK_TIMEOUT\|resolveCallbackUrl\|29.97" backend/src/test/` → 해당 단언 0건
- **영향**: 회귀 감지 공백. 특히 45s/10s 이중 타임아웃은 운영 타임아웃 산정 문서(D4/연동규격서)와 드리프트할 소지.
- **수정 방향(제안)**: 케이스 문구를 "블록 상한 45s / 실효 클라이언트 타임아웃 10s"로 분리 기술하고, 위 5종 테스트 보강.

### [B-ISSUE-28] (참고 기록) 마킹 단계 rawSn 비식별 신고 미구현 — UNCERTAINTIES #2 확정
- **심각도**: MEDIUM (미구현 갭 — 케이스 표 대상 아님, 기록 목적)
- **기대 동작(기대효과)**: CLAUDE.md "마킹 단계(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`)" 가 존재해야 함(문서상 planned).
- **현재 동작(이슈 내용)**: 구현된 것은 라벨링 단계(`DeidentReportController.java:91` `POST /v1/labels/{srcSn}/deident-report`)뿐. rawSn 경로는 **컨트롤러 자체가 없음**.
- **재현/확인 경로**: `curl -X POST .../v1/videos/25/deident-report -H "Authorization: Bearer $RT" -d '{"reason":"test"}'` → **404**
- **영향**: 마킹 화면에서 비식별 누락을 발견해도 신고 경로가 없어 라벨링 단계까지 진행해야 신고 가능.
- **수정 방향(제안)**: 정책 확정 후 rawSn 기준 신고 엔드포인트 추가(작업락 + 영상 단위 처리).

---

## 요약
- 총 **57건** (B-5: 11 / B-6: 20 / B-7: 26)
- **PASS 53** (그중 **실동작 확인 PASS 21건** — LIVE 표기) / **FAIL 0** / **PARTIAL 3** (TC-BATCH-081, TC-VLM-024, TC-VLM-030) / **BLOCKED 0** / **N/A 0** / **확인필요 1** (TC-VLM-010)
- **근거 라인 드리프트: 1건** (TC-VLM-030 근거 `VlmResultController.java:18-40` → 실제 16-41, 경미)
- **self-fill 결함: 0건** — VLM 미경유 상태에서 `ls_data_meta`(VLM 유래) 0행 · `ls_marking` 전 행 PENDING · `ls_webhook_idempotency` 0행으로 **저작도구가 외부 응답 없이 값을 채우지 않음을 실측 확증**
- 신규 이슈 **8건**: HIGH 1(B-ISSUE-21) / MEDIUM 4(22, 24, 25, 28) / LOW 3(23, 26, 27)
- **핵심 반증 결과**: B-5 동시성(3동시 마킹 → 배치 정확히 1회, PG unique 위반의 별도-tx catch 패턴 실동작 확인)과 B-6 인가/프리컨디션(401/403/404/412 전 경계 실측)은 **반증 시도에도 무너지지 않음**. 반면 B-7 은 외부 미경유로 실동작 확인이 불가한 구조적 제약이 최대 리스크.
