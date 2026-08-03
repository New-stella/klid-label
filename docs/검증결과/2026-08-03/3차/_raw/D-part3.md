# D 클러스터 part3 — D-6 관제 통지(TC-NOTIFY) + D-7 관제 조회 API

- 회차: **3차** (2026-08-03 지정 / 실행 2026-08-04 01:10~01:30 KST)
- 담당 범위: `docs/test-cases/D-review-version-notify.md` **166~234행** — D-6(45건) + D-7(8건) = **53건**
- 실측 환경: 풀스택 기동 상태(`_raw/stack-bringup.md` 재확인 — backend `localhost:18081/api`, mock-server `:9400`, PostgreSQL `public` 스키마), 외부 연동 4종 전부 mock-server 실배선
- 이슈 ID 시작번호: **D-ISSUE-41**
- 빌드/테스트 미실행(지시 준수). 테스트 통과 여부는 `_raw/test-baseline.md`(backend 5203건 / 실패 0) 대조로 판정
- 프로덕션 코드 무수정. 카탈로그 정정은 담당 라인범위(166~234) 안에서만 수행

---

## 0. 이번 회차 실동작 근거 (요약)

| # | 실동작 | 근거 |
|---|--------|------|
| L1 | **TASK_COMPLETED 페이로드 6필드 평면 snake_case 실물** | `ls_control_notify_fallback.payload_cn` (SEND_RSLT=SUCCESS 관찰행) — `{"job_id":"900","event_type_cd":"EV02000201","lclgv_cd":"1168000000","lclgv_nm":null,"duration_sec":5,"image_count":5}` |
| L2 | **TASK_MODIFIED changed_items 실물** | `{"job_id":"101","changed_items":{"images":["0000.jpg",…"0009.jpg"],"jsons":["0000.json",…]}}` / 비재생성 경로 `{"job_id":"4","changed_items":{"images":[],"jsons":["0000.json"]}}` |
| L3 | **통지 경로 계약 왕복** | mock-server 로그 `POST /api/data-set/v2/jobs/{101,900,906,27,115,4,33}/notify-completed\|notify-updated` 202/409/404 |
| L4 | **409 자기치유(completed→updated)** | backend `completed conflicted -> resend as updated rawSn=27` → mock `409 Conflict` 직후 `notify-updated 202`, 관찰행 `evnt_type_cd=TASK_MODIFIED` |
| L5 | **404 자기치유(updated→completed)** | `updated not-found -> fallback to completed rawSn=906/900/4` → mock `404` 직후 `notify-completed 202` |
| L6 | **job_id fail-closed** | dead-letter 행 payload `{"job_id":"../../etc/passwd"…}` · `{"job_id":"abc"…}` → `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.`(전송 0회) |
| L7 | **에러 sanitize** | dead-letter `last_err_msg_cn` = `지원하지 않는 eventType: token***` · `URL_REDACTED TOK`(개행→공백) · Jackson `Source: REDACTED` |
| L8 | **dead-letter 전이** | `rtry_nmtm=6 > max_rtry_nmtm=5` → `stts_cd=DEAD_LETTER`, `dlq_dt` 세팅, `next_rtry_dt=NULL` (6행) |
| L9 | **디바운스 축적 = 공유 DB** | `ls_mon_noti_acml` 관측: `raw_sn=115 / stts_cd=PENDING / export_rprcs_yn=Y / chg_dtl_cn={"video":{"*":["META_UPDATED"]}}` → flush 후 행 삭제(0건) |
| L10 | **★ export 실패 시 통지 보류(재현 실험)** | `PUT /v1/videos/147/environment-meta`(APPROVED·프레임 0건) → 60s 디바운스 → `async re-export(+notify) starting rawSn=147` → `no frames — skip export` → **`async export not notifiable — notify withheld rawSn=147 outcome=NO_INPUT`** → `ls_control_notify_fallback WHERE raw_sn=147` **0행**(통지 미발송) |
| L11 | **대조군(정상 경로)** | `PUT /v1/videos/115/environment-meta` → export v3 SUCCEEDED(01:26:14.976) → **그 다음** `TASK_MODIFIED sent rawSn=115 … reExport=true`(.981) — export→통지 순서 실측 |
| L12 | **IDOR 차단** | WORKER(2001) → `GET /v1/tasks/906/{summary,labels,meta}` **전부 403** `본인에게 배정되지 않은 영상입니다.` / 본인 배정 101 은 200 / REVIEWER 는 906 도 200 |
| L13 | **신고 게이트 412** | `rawSn=9`(`DE_IDENT_YN='F'`, 2001 배정) → labels **412**, summary/meta 200, **REVIEWER 도 412** |
| L14 | **인증 헤더 fail-open(실효)** | 기동 로그 `[ControlNotify] 통지가 활성화됐으나 인증 토큰(authoring.control-notify.token)이 비어 있습니다 …` + `docker exec klid-backend env`에 `CONTROL_NOTIFY_TOKEN` **부재** → 이 환경의 전 통지가 `x-access-token` **없이** 전송됨 |

---

## 1. D-6. 관제 통지 (TC-NOTIFY) — 45건

| ID | 판정 | 근거 확인 |
|----|:----:|-----------|
| TC-NOTIFY-001 | PASS | [실동작] rawSn=33: `export succeeded`(01:23:53.252) → `TASK_COMPLETED sent`(.255). 승인 이벤트 직후가 아니라 `DatasetExportCompletedEvent` 소비 시점. 역케이스 L10(147)에서 export 미성공 → 통지 0건. [정적] `ControlNotifyEventListener.java:38-41` `@EventListener(DatasetExportCompletedEvent)` · `DatasetExportBridge.java:36-43` → `runApprovalAsync` · `AsyncDatasetExportRunner.java:67-76`(`if (doExport(rawSn,true))` 성립 시에만 publish). 테스트 `ControlNotifyEventListenerTest#DatasetExportCompletedEvent_수신시_sendCompleted_호출` |
| TC-NOTIFY-002 | PASS | [정적] `DatasetExportBridge.java:36` `@TransactionalEventListener(phase=AFTER_COMMIT)` — 롤백 시 미호출이라 export·통지 모두 미발생. 승인 tx 롤백을 라이브로 만들 수단이 없어 정적 판정(테스트 `DatasetExportBridgeTest` 커버) |
| TC-NOTIFY-003 | PASS | [실동작] L1 — `job_id`·`event_type_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count` **정확히 6필드, 평면, snake_case**. `image_count=5` = `SELECT count(*) FROM ls_data_src WHERE raw_sn=900` 실측 일치, `duration_sec=5` = `VDO_LEN_SEC` 실측. 라벨/PII/토큰/원본경로 **0건**. 상수 self-fill 0(영상 미존재면 예외 → 폴백). [정적] `TaskCompletedPayload.java:24-32`(@JsonProperty 필드 고정) · `ControlNotifyPayloadFactory.java:78-92`. ⚠ `event_type_cd` 는 매핑 없이 pass-through — DB 안에 `INTRUSION`(rawSn=101)과 `EV02000201`(rawSn=900/906) **두 코드체계가 공존한 채 그대로 전송**된다(D-ISSUE-63 이월 미해소 / UNCERTAINTIES #27). 케이스 기대값 자체(=LS_DATA_RAW 값)는 충족하므로 PASS 유지 |
| TC-NOTIFY-004 | PASS | [정적] `ControlNotifyService.java:130-134` catch → `enqueueQuietly` + `metrics.incrementCompletedFailed()`. [실동작 간접] 폴백 큐 60행(SUCCEEDED 54 / DEAD_LETTER 6)이 적재 경로 실동작 증거. 테스트 `ControlNotifyServiceTest#TASK_COMPLETED_실패시_폴백큐_적재됨` |
| TC-NOTIFY-005 | PASS | [실동작] rawSn=27 — 요청은 completed 였으나 409 자기치유로 **실제 전송은 updated**, 관찰행 `evnt_type_cd=TASK_MODIFIED` + payload 가 `changed_items` 구조(=실제 전송분). 요청 종류가 아니라 `SendOutcome` 기준 기록 확인. [정적] `ControlNotifyService.java:126-129,318-325` |
| TC-NOTIFY-006 | PASS | [정적] `ControlNotifyService.java:318-325` — `recordImmediateSuccess` 예외를 catch 후 warn 만, 통지 성공 흐름 유지. `ControlNotifyFallbackService.recordImmediateSuccess` 는 `REQUIRES_NEW` 라 롤백 경계 분리 |
| TC-NOTIFY-007 | PASS | [실동작] L2 — 항목이 `{FRM_NO 4자리 zero-pad}.jpg/.json`(`0000.jpg`…). SRC_SN·좌표·메타본문·절대경로 **미포함**. 두 리스트 항상 non-null(비재생성 경로도 `"images":[]`). [정적] `TaskModifiedPayload.java:20-54`(compact 생성자 null→`List.of()`) · `ControlNotifyPayloadFactory.java:103-126` · `ExportFileNaming.java:30-58`(`%04d`, 음수 fail-closed) |
| TC-NOTIFY-008 | PASS | [실동작] L9 — `ls_mon_noti_acml`(V144) 에 `(raw_sn, chg_dtl_cn, export_rprcs_yn)` 실적재. 프레임↔변경종류 페어 보존도 flush 로그로 확인: `flush rawSn=905 regen=true frames=459=[META_UPDATED, LABEL_ADDED],460=[…],461=[META_UPDATED]`. 영상단위 변경은 `{"video":{"*":[…]}}` 별도 슬롯. [정적] `ControlNotifyDebouncer.java:195-197`(근거 드리프트 — 카탈로그 174-176) · `JpaControlNotifyDebounceStore.java:61-94` · `V144__create_ls_mon_noti_acml.sql` |
| TC-NOTIFY-009 | PASS | [정적] `ControlNotifyDebouncer.java:211-219`(@PreDestroy: 스케줄러 정지는 항상, drain 은 토글) · `244-252`(drain, `MAX_DRAIN_ROUNDS=20` @93) · `flush(now,…)` 로 만료 무관 drain. 남은 건 DB 잔존(complete 미호출). 근거 드리프트(카탈로그 80,190-203) |
| TC-NOTIFY-010 | PASS | [실동작] 기동 로그 `[ControlNotifyDebounce] flush scheduler started intervalMs=10000 leaseMs=300000` + 실제 flush 로그의 스레드명이 **`control-notify-debounce-flush`**(예: `01:24:24.860 [control-notify-debounce-flush]`) — `@Scheduled`/`@EnableScheduling` 비의존 실증. [정적] `ControlNotifyDebouncer.java:162-177`(@PostConstruct, daemon 단일 스레드) · `141`(`regen-flush.enabled` 기본 true). 근거 드리프트 |
| TC-NOTIFY-011 | PASS | [정적] `ChangeType.java:18-30` — 4종 상수 + `ALL` 정의만 존재. 프로덕션 경로 `ControlNotifyDebouncer.send/summarizeChangeTypes`(334-369)에 **런타임 검증기 없음** 재확인(2026-08-03 기술과 일치). [실동작] flush 로그에 발행값 4종 관측(`META_UPDATED`,`LABEL_ADDED`,`LABEL_UPDATED`,`LABEL_DELETED` — rawSn=27 `frames=301=[LABEL_UPDATED, LABEL_ADDED, LABEL_DELETED]`) — 실발행값이 계약집합 안. 근거 드리프트 |
| TC-NOTIFY-012 | PASS | [정적] `LabelService.java:413` `if (!changes.isEmpty() && isReviewApproved(current.getRawSn()))` — 승인 전 저장은 통지 미발행 |
| TC-NOTIFY-013 | PASS | [정적] `LabelService.java:397·403·413` — `changes` 비면 이력·버전 bump·통지 3종 모두 미발생 |
| TC-NOTIFY-014 | PASS | [정적] 7경로 전수 확인 — `LabelService.java:423`(`toChangeTypes` 루프, regen=true) · `TrackEditService.java:326` · `TrackMergeService.java:201` · `VersionService.java:518` · `EnvironmentMetaService.java:125` · `FrameDescriptionService.java:60` · `FramePrivacyMetaService.java:158,180` — **7경로 전부 마지막 인자 `true`**. [실동작] 2경로 실증 — 라벨(`flush rawSn=101 regen=true frames=468=[LABEL_UPDATED]` → export v2 → 통지) · 촬영환경(L11 rawSn=115 → export v3 → `reExport=true` 통지). 근거 드리프트(FramePrivacyMetaService 131-132,154-155 → 158-159,180-181) |
| TC-NOTIFY-015 | PASS | [정적] `ControlNotifyFallbackService.java:91-95` — `DataIntegrityViolationException` catch 후 동일 key 멱등 반환. UK 는 V44 `IDMP_KEY` |
| TC-NOTIFY-016 | PASS | [정적] `ControlNotifyFallbackService.java:36`(`MAX_QUEUE_DEPTH=10000`) · `75-82`(`countBySttsCdIn(PENDING,RETRYING) >= MAX` → `IllegalStateException` + `control.notify.fallback.queue.full`). CWE-770 방어 성립 |
| TC-NOTIFY-017 | PASS | [정적] `:71-73`(enqueuePending) · `:113-115`(recordImmediateSuccess) 둘 다 `if(!enabled) return Optional.empty()`. 이 빈은 `@ConditionalOnProperty` 가 아니라 `@Value` 플래그 방식이라 토글 off 에서도 빈은 존재하되 no-op |
| TC-NOTIFY-018 | PASS | [정적] `ControlNotifyFallbackService.java:136-143` + `LsControlNotifyFallbackRepository.claimAtomically` = `UPDATE … SET STTS='RETRYING' WHERE queueSn=:sn AND STTS='PENDING'` → `updated != 1` 이면 `Optional.empty()`. 조건부 원자 UPDATE 확인 |
| TC-NOTIFY-019 | PASS | [정적] `LsControlNotifyFallback.java:194-213` — `rtryCnt+=1` 후 `backoffMin = min(60, 1L << min(rtryCnt,6))` → PENDING + `now.plusMinutes(backoff)`, cap 60 성립. ⚠ 같은 메서드 javadoc(190행)은 `(1, 2, 4, 8, 16)` 이라 적혀 있으나 실제는 **2,4,8,16,32** — **D-ISSUE-64 이월 미해소**(문서 결함, 동작은 케이스 기대와 일치) |
| TC-NOTIFY-020 | PASS | [실동작] L8 — dead-letter 6행 전부 `rtry_nmtm=6`, `max_rtry_nmtm=5`, `dlq_dt` 세팅, `next_rtry_dt` NULL. [정적] `LsControlNotifyFallback.java:201-207`, `DEFAULT_MAX_RETRY=5`(48행) |
| TC-NOTIFY-021 | PASS | [정적] `ControlNotifyFallbackRetryJob.java:31`(BATCH_SIZE=20) · `48-71`(due 조회 → `claimForRetry` → `processOne` → `markSucceeded`, claim 실패 시 continue). [실동작 간접] dead-letter 행이 `rtry_nmtm=6` 까지 올라간 것 자체가 due 폴링 루프가 6회 실주행한 증거 |
| TC-NOTIFY-022 | PASS | [정적] `ControlNotifyFallbackRetryJob.java:62-65` — `RuntimeException` → `markFailedAndSchedule(queueSn, 예외클래스명+메시지)`. [실동작 간접] dead-letter `last_err_msg_cn` 이 재실패 누적 결과 |
| TC-NOTIFY-023 | PASS | [실동작] L7 — 실제 DB 값에서 토큰(`token=SEKRET123`→`token***`)·URL(`http://a.b/x`→`URL_REDACTED`)·개행(제어문자→공백) 전부 정화 확인. [정적] `LsControlNotifyFallback.java:246-253`(제어문자→공백 → 토큰류 마스킹 → URL redact → 1900자 truncate) |
| TC-NOTIFY-024 | PASS | [정적] `ControlNotifyFallbackService.java:108-129` — `recordImmediateSuccess` 도 `DataIntegrityViolationException` 삼킴 + 멱등 반환. `REQUIRES_NEW` 로 상위 롤백 분리 |
| TC-NOTIFY-025 | PASS | [정적] 토글 종속 6빈 전수 확인 — `ControlNotifyClient.java:42` · `ControlNotifyService.java:52-53` · `ControlNotifyPayloadFactory.java:60` · `ControlNotifyEventListener.java:27` · `ControlNotifyFallbackRetryJob.java:27` · `TaskQueryController.java:46` 모두 `@ConditionalOnProperty(havingValue="true")`. 항상 등록 3빈 — `ControlNotifyDebouncer.java:85-87`(@Component only) · `TaskModifiedAccumulateListener.java:27-30` · `DatasetExportBridge.java:29-30`(`matchIfMissing=true`). 근거 드리프트(72-74) |
| TC-NOTIFY-032 | PASS | [실동작] L3 — mock 로그 경로가 `POST /api/data-set/v2/jobs/{job_id}/notify-completed\|notify-updated` 정확 일치, 응답 **202** 를 성공 수용(mock 은 202 를 반환하는데 backend 는 예외 없이 성공 처리). [정적] `ControlNotifyClient.java:54-58`(경로 상수) · `90-108`(`contentType(APPLICATION_JSON)` + `is2xxSuccessful()` 전체 수용) |
| TC-NOTIFY-033 | PASS | [실동작] L4 — rawSn=27/115/4 에서 `409 Conflict` 수신 → 즉시 `notify-updated 202`, 반환 outcome=TASK_MODIFIED(로그 `actual=TASK_MODIFIED`), 관찰행도 MODIFIED. 메트릭 `control.notify.selfheal.completed_to_updated` 존재 확인(`ControlNotifyMetrics:68`). [정적] `ControlNotifyService.java:215-233` |
| TC-NOTIFY-034 | PASS | [실동작] L5 — rawSn=906/900/4 에서 `404 Not Found` → `notify-completed 202`, `actual=TASK_COMPLETED`. 메트릭 `…selfheal.updated_to_completed`(`ControlNotifyMetrics:71`). [정적] `ControlNotifyService.java:244-263` |
| TC-NOTIFY-035 | PASS | [정적] `ControlNotifyService.java:225-231`(409 분기 안에서는 `sendTaskModified` 만 호출, `dispatchModified` 재진입 없음) · `255-261`(대칭). 즉 전환된 통지가 4xx 여도 예외가 그대로 상위로 → 폴백 큐. 테스트 `폴백은_재귀하지_않는다`·`completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다` |
| TC-NOTIFY-036 | PASS | [실동작] 4xx(409/404) 가 `ControlNotifyStatusException` 으로 잡혀 자기치유 분기가 실제 발동함을 L4/L5 가 실증. [정적] `ControlNotifyClient.java:110-120` — `400≤s<500` → `ControlNotifyStatusException`(Resilience4j ignore 대상), 그 외 → `IllegalStateException`(재시도·서킷 집계). 5xx 는 라이브 재현 수단 없어 정적 + 테스트(`수정통지가_500이면_자기치유하지_않고_폴백큐로_간다`) |
| TC-NOTIFY-037 | PASS | [실동작] L6 — 폴백 큐에 오염 JSON(`"job_id":"../../etc/passwd"`, `"abc"`)이 실재하고, 재시도 시 `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.` 로 **전송 전에** 거부되어 dead-letter 로 종결. mock 로그에 해당 경로 요청 **0건**(경로 세그먼트 조작 미발생). [정적] `ControlNotifyService.java:66-73`(`\d{1,19}`) · `275-279` |
| TC-NOTIFY-038 | PASS | [정적] `ControlNotifyService.java:112-123` — 조립 예외 시 **통지를 버리지 않고** `PAYLOAD_REBUILD_REQUIRED`(=`""`) 로 큐잉 → `ControlNotifyFallbackRetryJob.java:85-101` 에서 `isPayloadRebuildRequired` → `dispatch*(null, rawSn)` 재조립. 테스트 `페이로드_조립에_실패하면_통지가_폴백큐에_적재되어_재시도된다`·`폴백_재시도가_페이로드_없이_호출되면_dispatch_가_재조립한다` |
| TC-NOTIFY-039 | PASS | [정적] `ControlNotifyService.java:170-188` — `exportRegenerated=false` 면 REBUILD 표식이 아니라 **빈 `ChangedItems` 확정 적재**, true 면 REBUILD_REQUIRED. 테스트 `MED2_…빈_changed_items_로_적재되어_재시도때_전프레임_blast_안됨`/`…REBUILD_REQUIRED로_적재` |
| TC-NOTIFY-040 | PASS | [실동작] **L10 재현 실험** — APPROVED·프레임 0건 영상(rawSn=147)에 승인후 수정을 넣어 export 를 `NO_INPUT` 으로 종결시킨 결과 `notify withheld … outcome=NO_INPUT` 로그 + `ls_control_notify_fallback` 에 rawSn=147 행 **0건**(전 기간). 대조군 115 는 정상 통지. **예외 없는 실패 경로에서도 통지가 보류됨을 실증** → 1차 D-ISSUE-61(CRITICAL) **해소 확정**. [정적] `AsyncDatasetExportRunner.java:134-149` — `DatasetExportOutcome.notifiable()` 단일 판정 + `outcome==null` fail-closed. `DatasetExportOutcome` 7값 중 통지 허용은 COMPLETED/PARTIAL/IDEMPOTENT_SKIP 뿐. 테스트 `DatasetExportNotifyGateTest` 8건 |
| TC-NOTIFY-041 | PARTIAL | [정적] `DatasetExportFailureRecoverer.java:119-170` — FAILED anchor 조회 → `txService.claimForRetry`(조건부 UPDATE, RTY_NMTM) → `runApprovalAsync`(완료 이벤트 재발행) 배선 정상. **그러나 "통지 유실이 아니라 지연" 이 성립하지 않는 종결이 있다** — `NO_INPUT` 은 `DatasetExportTxService.loadPreparation`(103-106)이 **export 행 자체를 만들지 않고** skip 하므로 `findRetryableFailedAnchors`(FAILED 만 스캔)가 영원히 못 집는다. 디바운스 윈도우는 이미 `complete()`(삭제)됐고 폴백 큐에도 안 들어가 **TASK_MODIFIED 가 영구 유실**된다(L10 rawSn=147 실측 — 통지 0건, export 행 0건, 윈도우 0건). → **D-ISSUE-41** |
| TC-NOTIFY-042 | PASS | [정적] `DatasetExportFailureRecoverer.java:141-144` — `deidentReportGate.isUnderDeidentReport(rawSn)` 판정이 **`txService.claimForRetry` 호출(147행)보다 앞**에 있어 `RTY_NMTM` 미증가 + `deidentSkipped` 집계 후 INFO 로그(159-162). 예산 미소모 성립 |
| TC-NOTIFY-043 | PASS | [정적] `TaskModifiedAccumulateListener.java:27-37` — 토글 없는 `@Component` + AFTER_COMMIT 축적. `ControlNotifyDebouncer.java:334-357`(`send`) — `exportRegenerated` 면 `notifyService==null`(토글 off) 이어도 `exportRunner.runReExportThenNotify(rawSn, true, null)` 를 **항상** 호출, 통지 콜백만 생략. 테스트 `HIGH-E_통지_토글_off여도_승인후_수정_재생성_윈도우는_export를_트리거한다`·`재export_트리거는_control_notify_토글과_무관하게_동작한다`. 이 환경은 토글 on 이라 라이브 재현 불가(정적+테스트). 근거 드리프트 |
| TC-NOTIFY-044 | PASS | [정적] `JpaControlNotifyDebounceStore.java:102-110` `claim` → `LsMonNotiAcmlRepository.claimForFlush` = `UPDATE … SET STTS='FLUSHING' WHERE notiAcmlSn=:sn AND ((STTS='PENDING' AND REG_DT<=:windowCutoff) OR (STTS='FLUSHING' AND MDFCN_DT<=:leaseCutoff))` → `!=1` 이면 미전송. 축적 INSERT 도 `ON CONFLICT (RAW_SN) WHERE STTS_CD='PENDING' DO NOTHING` 원자. [실동작] flush 후 `ls_mon_noti_acml` 0행(=`deleteFlushed` 성공), 통지 1회. 2노드 동시성 자체는 단일 노드 환경이라 IT(`ControlNotifyDebounceCrossNodeIT`) 대조. 근거 드리프트 |
| TC-NOTIFY-045 | PASS | [정적] `ControlNotifyDebouncer.java:290-318` — `send` 예외 시 `store.complete()` **미호출** → FLUSHING 잔존 → `leaseCutoff` 만료 후 재클레임. `MIN_LEASE_MILLIS=60_000`(96행)로 하한 clamp, 기본 300s(실측 기동 로그 `leaseMs=300000`). 실패 시 `metrics.incrementDropped()` + ERROR, `return false` 로 루프 지속. 근거 드리프트 |
| TC-NOTIFY-046 | PASS | [정적] `ControlNotifyService.java:281-309` `enqueueQuietly` — 예외를 밖으로 안 던지고 `metrics.incrementDropped()`(=`control.notify.dropped`, `ControlNotifyMetrics:107`) + ERROR. 테스트 `폴백큐가_가득_차도_예외가_호출자로_전파되지_않는다`·`폴백큐가_가득_차도_디바운서_flush_루프가_중단되지_않고_나머지_윈도우를_처리한다` |
| TC-NOTIFY-047 | PASS | [실동작] 마스터 미존재 분기 실증 — `WARN [ControlNotify] local gov not found lclgvCd=1168000000` 직후 페이로드 `"lclgv_nm":null`(L1). 값을 지어내지 않음. [정적] `ControlNotifyPayloadFactory.java:150-174` — 미존재 null / `USE_YN<>'Y'` null(폐지 명칭 미전송) / `LCLGV_NM_MAX_LENGTH=100`(66행) 초과 시 `substring(0,100)`. 테스트 3건(`지자체_마스터에_없으면…`, `폐지된_지자체_코드는…`, `지자체명이_100자를_넘으면_절단된다`) |
| TC-NOTIFY-048 | PASS | [정적] `ControlNotifyPayloadFactory.java:185-219` + `LsDataSrcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn`(135-138) — 쿼리 조건이 `s.rawSn=:rawSn AND s.srcSn IN :srcSns AND coalesce(srcFilePathNm,'')<>'' AND coalesce(deIdntfSrcFilePathNm,'')<>''` 이라 **타 영상 srcSn·한쪽 벌만 보유 프레임이 구조적으로 결과에서 빠진다**. 미해석분은 `unresolved` 카운트 + WARN(식별자·건수만, 좌표/경로 미출력) + `metrics.incrementUnresolvedFrame`. 테스트 4건 |
| TC-NOTIFY-049 | PASS | [실동작] L11 — rawSn=115 전량 재생성 통지의 `images`/`jsons` 가 **6건**(`0000,0001,0002,0090,0091,0092`)으로 `ls_data_src` 중 원천 경로 보유 프레임과 일치. [정적] `ControlNotifyPayloadFactory.java:120-126` → `findExportableFrameNosByRawSn`(158-160, "둘 중 하나라도 보유") — writer 가 skip 할 프레임(양쪽 다 부재)만 제외 |
| TC-NOTIFY-050 | PASS | [정적] `DatasetExportBridge.java:81-88` — `@TransactionalEventListener(AFTER_COMMIT) onDeidentReportResolved` → `runApprovalAsync`(force=true + 완료 이벤트 발행 → 보류 통지 재개). APPROVED 판정은 발행 측(`DeidentReportService.publishResolvedForExportRecovery`)이 수행. 신고 차단은 export 행을 남기지 않아 회수기가 못 집는 것도 코드로 확인(`DatasetExportService` 진입 게이트 예외) → 이 재트리거가 유일 복구 경로 성립 |
| TC-NOTIFY-051 | PARTIAL | [정적] 부착 배선은 실재 — `WebClientConfig.java:76`(`CONTROL_NOTIFY_TOKEN_HEADER="x-access-token"`) · `104-111`(토큰 non-blank 면 `defaultHeader` 부착 + 평문 http 경고) → **D-ISSUE-62 해소 확인**. 테스트 `ControlNotifyWebClientAuthHeaderTest` 4건(baseline 전건 통과). **그러나 [실동작] L14** — `docker exec klid-backend env` 에 `CONTROL_NOTIFY_TOKEN` **미존재**, 기동 로그에 `인증 토큰 … 비어 있습니다` WARN → **이 환경의 통지 8건 전부 `x-access-token` 없이 전송**됐고 mock 이 202 로 받아줬다. `enabled=true` + 토큰 공백이 **WARN 만(fail-open)** 인 잔여 갭이 라이브로 성립 → **D-ISSUE-42** |

---

## 2. D-7. 관제 조회 API (TC-NOTIFY, 조회) — 8건

> ★ UNCERTAINTIES **#4 반전** 적용 — IDOR 케이스(TC-NOTIFY-052)를 검증 대상에 **포함**해 실제로 반증 시도했다.

| ID | 판정 | 근거 확인 |
|----|:----:|-----------|
| TC-NOTIFY-026 | PASS | [실동작] `GET /v1/tasks/101/summary`(WORKER 2001, 본인 배정) → 200 `{"rawSn":101,"status":"COMPLETED","totalFrames":10,"labeledFrames":1,"totalLabels":1,"totalMeta":7,"reviewerName":"김검수","lastModifiedAt":"2026-08-03T15:07:17.599778Z"}` — DB 실측(`ls_data_src` 10행, `ls_data_lbl` 1행, `ls_data_meta` 7행)과 일치. [정적] `TaskQueryController.java:66-73` · `TaskQueryService.java:74-98`(전부 COUNT 집계) |
| TC-NOTIFY-027 | PASS | [실동작] labels 응답 항목이 `{"lblSn","lblTypeCd","label","points"}` 뿐 — `filePath`/`deIdntfSrcFilePath` **미포함**. 프레임 레벨도 `{"srcSn","frameNo","labels"}` 만. [정적] `TaskLabelsResponse.java`(record 구조로 강제) · `TaskQueryService.java:234-245` |
| TC-NOTIFY-028 | PARTIAL | [실동작] 기능은 전건 충족 — `frameIds` 101개 → **400**, 100개(경계) → 200, `size=10000` → **size 100 클램프**, 미지정 → **20**, `frameIds=468,469` → `totalElements=2` (페이징 **전** 필터 적용 확인). [정적] `TaskQueryController.java:87-103`(@Size) · `TaskQueryService.java:54-57,107-110,202-208`(서비스 2중 방어) → D-ISSUE-45 해소 유지. ⚠ **그러나 400 응답 message 가 `"getLabels.frameIds: frameIds 는 최대 100개까지…"`** — 컨트롤러 **메서드명이 그대로 노출**된다(CWE-209). 1차 **D-ISSUE-24 미해소 이월** |
| TC-NOTIFY-029 | PASS | [실동작] `GET /v1/tasks/101/meta` → 기본 `size=20`, `?size=10000` → `size=100` 클램프, 항목이 `{metaSn, metaKey, metaVal}` 구조. [정적] `TaskQueryController.java:114-123` · `TaskQueryService.java:147-150`(+`cappedMeta` 192-199, `metaSn` 오름차순 고정으로 페이지 간 안정성) |
| TC-NOTIFY-030 | PASS | [실동작] REVIEWER 토큰으로 `rawSn=99999999` → summary/labels/meta **전부 404** `{"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}`. [정적] `TaskQueryService.java:225-228` |
| TC-NOTIFY-031 | PASS | [실동작] 토큰 없음 → **401** `UNAUTHORIZED` / PORTAL_USER 토큰 → **403** `FORBIDDEN`(`@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 실효). [정적] `TaskQueryController.java:46,67,86,115`. 토글 off 시 컨트롤러 부재 404 는 `@ConditionalOnProperty`(46행) 정적 확인 |
| TC-NOTIFY-052 | PASS | [실동작] **IDOR 반증 시도 실패 = 차단 성립** — 배정 이력 없는 WORKER(2001, `ls_task_assignment` 에 raw 906 행 0건)가 `GET /v1/tasks/906/summary\|labels\|meta` 호출 시 **3경로 전부 403** `본인에게 배정되지 않은 영상입니다.`. 대조: 본인 배정 101 은 3경로 200, REVIEWER 는 906 도 3경로 200(연동 영향 없음). [정적] `TaskQueryController.java:71,96,121` 진입부 `labelAccessGuard.verifyRawAccess` · `LabelAccessGuard.java:83-100`(actor null→401 / REVIEWER 얼리리턴 / WORKER 는 `existsByUserNoAndTaskTypeCdAndRawDataId(self, LABELER, rawSn)` 필수 / 그 외 403). 테스트 `TaskQueryControllerTest#배정되지_않은_WORKER_는_rawSn_순회로_라벨을_읽을_수_없다_403`. **UNCERTAINTIES #4 반전 확정 — 구 "의도된 광범위 허용" 은 더 이상 사실이 아니다** |
| TC-NOTIFY-053 | PASS | [실동작] `rawSn=9`(`DE_IDENT_YN='F'`, WORKER 2001 배정) → **labels 412** `PRECONDITION_FAILED / 비식별 재처리 대기 중인 영상입니다.`, summary·meta 는 **200**(좌표 미포함이라 대상 아님) — 범위 정확. **REVIEWER 토큰도 동일 412**(역할 무관 프리컨디션). 미배정 WORKER 는 여전히 403 이 먼저(인가 이후 평가 순서 확인 — 906 은 403, 9 는 403 아닌 412). [정적] `TaskQueryController.java:97-101` — `verifyRawAccess`(96) **이후** `requireNotUnderDeidentReport`(101) |

---

## 3. 판정 집계

| 판정 | 건수 | 케이스 |
|------|:---:|--------|
| PASS | **50** | D-6 42건 + D-7 6건 |
| PARTIAL | **3** | TC-NOTIFY-028 · TC-NOTIFY-041 · TC-NOTIFY-051 |
| FAIL | 0 | — |
| BLOCKED | 0 | — |
| N/A | 0 | — |
| 확인필요 | 0 | — |
| **합계** | **53** | (폐기 케이스 없음) |

PASS율 94.3% (50/53). **CRITICAL 등급 신규 결함 0건.**

---

## 4. 이슈

### [D-ISSUE-41] TC-NOTIFY-041 — `NO_INPUT` 종결은 export 행을 남기지 않아 회수기가 못 집고, 디바운스 윈도우는 이미 삭제돼 TASK_MODIFIED 가 **영구 유실**된다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md 구속 정책 — *"export 가 실패하면 통지를 보류하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(**통지 유실이 아니라 성공 시점으로 지연**)"*. 즉 보류된 통지는 **반드시 되살아나는 경로**가 있어야 한다. 그래야 관제가 승인 후 수정을 영원히 모르는 상태가 되지 않는다.
- **현재 동작(이슈 내용)**: 보류는 되지만 **되살아나지 않는 종결이 있다**.
  - `DatasetExportTxService.java:101-106` — 프레임 0건(또는 활성 메타 0건)이면 **`LS_DATASET_EXPORT` 행을 만들지 않고** `Optional.empty()` 로 skip → `DatasetExportOutcome.NO_INPUT`.
    ```java
    List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
    if (frames.isEmpty()) {
        log.info("[DatasetExport] no frames — skip export rawSn={}", rawSn);
        return Optional.empty();     // ← FAILED 행도 남기지 않는다
    }
    ```
  - `DatasetExportFailureRecoverer.java:125` — `exportRepository.findRetryableFailedAnchors(...)` 는 **FAILED 행만** 스캔한다. 행이 없으므로 회수 대상이 되지 않는다.
  - `ControlNotifyDebouncer.java:344-351` — 재생성 윈도우는 `exportRunner.runReExportThenNotify`(`@Async`) 에 **위임만 하고 즉시 반환**하므로, 호출부 `claimAndSendIsolated`(303-306)가 예외 없음으로 판단해 `store.complete(acmlSn)` → **윈도우 행 DELETE**. 축적분(변경 프레임·변경종류)도 함께 사라진다.
  - 폴백 큐(`ls_control_notify_fallback`)에도 들어가지 않는다 — 통지 함수(`sendModified`)가 아예 호출되지 않았기 때문.
- **재현/확인 경로** (이번 회차 실측, rawSn=147):
  ```bash
  # 전제: rawSn=147 은 LS_RAW_DATA_STATUS='APPROVED', DE_IDENT_YN='Y', LS_DATA_SRC 0건
  curl -X PUT 'http://localhost:18081/api/v1/videos/147/environment-meta' \
       -H "Authorization: Bearer $REVIEWER_JWT" -H 'Content-Type: application/json' \
       -d '{"weather":"비","timeOfDay":"NGT","season":"WINTER"}'      # 200
  # 60s 디바운스 후 backend 로그
  #   [DatasetExport] async re-export(+notify) starting rawSn=147 forceRegenerate=true
  #   [DatasetExport] no frames — skip export rawSn=147
  #   [DatasetExport] async export not notifiable — notify withheld rawSn=147 outcome=NO_INPUT
  ```
  ```sql
  SELECT count(*) FROM ls_control_notify_fallback WHERE raw_sn=147;  -- 0  (통지 흔적 없음)
  SELECT count(*) FROM ls_dataset_export         WHERE data_raw_sn=147; -- 0 (회수 anchor 없음)
  SELECT count(*) FROM ls_mon_noti_acml          WHERE raw_sn=147;   -- 0  (윈도우 삭제됨)
  ```
  대조군 rawSn=115(프레임 6건)는 같은 조작으로 export v3 SUCCEEDED → `TASK_MODIFIED` 정상 발송(queue_sn 83).
- **영향**: 데이터 정합 — 관제가 승인 후 메타/라벨 수정을 **영원히 인지하지 못한다**(CLAUDE.md *"데이터마트 학습데이터셋의 라벨링 정보 동기화"* 요구 위반). 현재 확인된 트리거는 "프레임 0건 / 활성 동결메타 0건인 APPROVED 영상"이라 발생 폭은 좁지만, `NO_INPUT` 판정은 **활성 메타 부재**로도 진입하므로(동결 스냅샷이 비활성화된 영상) 정상 데이터에서도 도달 가능하다. 보안 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 **비통지 종결(`notifiable()==false`)일 때 디바운스 윈도우를 되돌리거나**(complete 를 러너 콜백 이후로 이동) ⓑ `NO_INPUT` 도 `LS_DATASET_EXPORT` 에 종결 행(예: `NO_INPUT`/`FAILED`)을 남겨 회수기 스캔 대상이 되게 하거나 ⓒ 최소 조치로 **보류된 통지를 폴백 큐에 `PAYLOAD_REBUILD_REQUIRED` 로 적재**해 재시도 잡이 살리게 한다. ⓐ는 D-ISSUE-61 수정 때 함께 지적된 "러너의 성공/실패를 상위가 알 수 있게 한다"(1차 §3072)와 같은 축이다. **구현은 하지 않는다.**

### [D-ISSUE-42] TC-NOTIFY-051 — 통지 활성인데 인증 토큰이 비면 **WARN 만 내고 무인증 전송**(fail-open), 로컬 실효값이 실제로 빈 토큰

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 관제 inbound SPI 계약(API-251/API-285)은 `x-access-token` 을 요구한다. 통지가 활성(`authoring.control-notify.enabled=true`)인데 토큰이 없으면, 실환경에서 **전 통지가 401 로 거부 → 폴백 큐가 재시도 상한을 소진 → dead-letter 고착 → 관제 동기화 전면 중단**이 확정적으로 발생한다. 보안 통제의 미설정은 fail-closed(기동 차단 또는 최소한 통지 비활성)로 다뤄야 조기에 드러난다.
- **현재 동작(이슈 내용)**: `WebClientConfig.java:104-116`
  ```java
  if (token != null && !token.isBlank()) { ... builder.defaultHeader(CONTROL_NOTIFY_TOKEN_HEADER, token.trim()); }
  else if (enabled) {
      log.warn("[ControlNotify] 통지가 활성화됐으나 인증 토큰(...)이 비어 있습니다 — 관제 SPI 가 {} 를 요구하면 전 통지가 401 로 거부됩니다.", ...);
  }
  return builder.build();   // ← 헤더 없이 그대로 기동·전송
  ```
  이번 회차 실효값 실측: `docker exec klid-backend env | grep CONTROL_NOTIFY` → `CONTROL_NOTIFY_ENABLED=true` · `CONTROL_NOTIFY_URL=...` 뿐이고 **`CONTROL_NOTIFY_TOKEN` 자체가 없다**. 기동 로그 `2026-08-03 23:54:19.600 [main] WARN ... 인증 토큰 ... 비어 있습니다`. 그 상태로 이번 회차 통지 8건이 전부 발송·수락됐다(mock 이 인증을 검사하지 않기 때문).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -c CONTROL_NOTIFY_TOKEN     # 0
  docker logs klid-backend | grep '인증 토큰'                      # 기동 WARN 1건
  docker logs klid-mock-server | grep -c 'notify-completed HTTP/1.1" 202'  # 무인증인데 수락됨
  ```
- **영향**: 보안 — 인증 없는 outbound 통지(CWE-306 Missing Authentication / CWE-1188 insecure default). 실질 피해는 stg/prd 배포 시 **통지 전면 401 → dead-letter 고착**(가용성·데이터 정합). 또한 WARN 은 배포 로그에 묻혀 미설정이 무증상으로 지나간다 — 이 저장소가 `QuartzClusteringGuard`·`VlmUrlPolicy`·`GenAiIntegrationWiringGuard` 에서 이미 채택한 "배포 환경 fail-closed 기동 차단" 패턴과 어긋난다.
- **수정 방향(제안)**: `QuartzClusteringGuard` 골격을 재사용해 **stg/prd(및 `ENV` 배포 표식) + `control-notify.enabled=true` + 토큰 공백** 조합이면 `@PostConstruct` 에서 **기동 실패**시킨다. local/dev(목 서버, 인증 미요구)는 현행 WARN 유지. 대안으로 통지만 자동 비활성화(빈 미등록)하되, 그 경우 "통지가 조용히 안 나가는" 상태가 되므로 기동 차단이 더 낫다. 병행으로 `.env.example`·`deploy/onprem/env.template` 에 `CONTROL_NOTIFY_TOKEN` 항목을 명시한다(⚠ 빈값 기입은 `${KEY:default}` 무력화 사고 이력 참조 — 주석으로 필수 표기). **구현은 하지 않는다.**

---

## 5. 이전 회차 이슈 대조

| 이슈 | 케이스 | 이번 회차 판정 |
|------|--------|---------------|
| **D-ISSUE-61** (CRITICAL, 1차) — export 가 FAILED/무예외 실패로 마감돼도 TASK_COMPLETED/MODIFIED 발송 | TC-NOTIFY-040/001/041 | **✅ 해소 확정(실동작 실증).** `DatasetExportOutcome.notifiable()` 단일 판정 + `null` fail-closed. rawSn=147 재현 실험에서 `NO_INPUT` 종결 시 통지 0건. 2차 타겟 재검증(PASS)을 **전수 회차에서 라이브로 재확인**. 단 "유실 아닌 지연" 의 복구 경로가 `NO_INPUT` 에서 끊긴다 → 신규 **D-ISSUE-41** |
| **D-ISSUE-62** (HIGH, 1차) — 통지에 `x-access-token` 미부착 | TC-NOTIFY-051 | **✅ 배선 해소.** `WebClientConfig.java:76,111` 부착 + 전용 테스트 4건. **단 fail-open 잔여 갭이 라이브로 성립** → 신규 **D-ISSUE-42** |
| **D-ISSUE-63** (1차) — `event_type_cd` 매핑 없이 pass-through, 두 코드체계 공존 | TC-NOTIFY-003 | **미해소 이월.** `ControlNotifyPayloadFactory.toControlEventTypeCd`(139-141)는 여전히 항등함수. DB 실측으로 `INTRUSION`(rawSn=101)·`EV02000201`(900/906) 두 계통이 그대로 전송됨을 재확인. UNCERTAINTIES **#27**(관제 8대 코드값 미수령) 그대로 — 관제팀 협의 선행 필요. 케이스 기대값 자체는 충족이라 판정은 PASS |
| **D-ISSUE-64** (LOW, 1차) — 백오프 javadoc(1,2,4,8,16) vs 실제(2,4,8,16,32) | TC-NOTIFY-019 | **미해소 이월.** `LsControlNotifyFallback.java:190` 주석 그대로. 동작은 `2^rtryCnt` cap 60 으로 케이스 기대 충족 |
| **D-ISSUE-24** (1차) — `frameIds` 상한 400 응답에 컨트롤러 메서드명 노출 | TC-NOTIFY-028 | **미해소 이월(실동작 재확인).** 응답 message = `"getLabels.frameIds: frameIds 는 최대 100개까지 지정할 수 있습니다."` — `@Validated` 메서드 검증의 `ConstraintViolation.propertyPath` 가 그대로 노출(CWE-209). `GlobalExceptionHandler` 에서 `ConstraintViolationException` 의 propertyPath 앞 세그먼트를 제거하면 해소 |
| **D-ISSUE-45** (1차) — 관제 조회 무페이징 | TC-NOTIFY-028/029 | **✅ 해소 유지(실동작).** labels·meta 기본 20 / 상한 100 클램프 재확인 |
| **UNCERTAINTIES #4** — 관제 조회 API IDOR "의도된 광범위 허용" | TC-NOTIFY-052 | **✅ 반전 확정.** 3경로 전부 `verifyRawAccess` 적용, 미배정 WORKER 403 실측. UNCERTAINTIES 원본의 🔄 표기가 사실과 일치 — 원본 갱신 불필요 |
| **UNCERTAINTIES #3** — TASK_COMPLETED payload | TC-NOTIFY-003 | **✅ 확정 재확인.** 6필드 평면 snake_case + `/api/data-set/v2/jobs/{job_id}/notify-*` 경로가 실물로 확인됨. 구 기대값(`totalFrames`/`labeledFrames`/`reviewerName`)은 계약에 존재하지 않음 |

---

## 6. 카탈로그 근거 드리프트 (이번 회차 정정 완료)

`docs/test-cases/D-review-version-notify.md` 166~234행 내에서 아래 12건의 `file:line` 근거가 실제 코드 위치와 어긋나 **Edit 로 직접 정정**했다(프로덕션 코드 무수정). 대부분 `ControlNotifyDebouncer.java` 가 커진 뒤 라인이 60~70행씩 밀린 계통 드리프트다.

| TC | 컬럼 | 구 근거 | 정정 |
|----|------|---------|------|
| 008 | 근거 | `ControlNotifyDebouncer.java:174-176` | `:195-197`(accumulate) |
| 009 | 근거 | `:80,190-203` | `:93,211-219,244-252` |
| 010 | 근거 | `:100-103,141-166` | `:132-134,141,162-177` |
| 011 | 근거 | `:289-320(검증 없음)` | `:334-369(검증 없음)` |
| 014 | 근거 | `FramePrivacyMetaService.java:131-132,154-155` | `:158-159,180-181` |
| 015 | 근거 | `ControlNotifyFallbackService.java:67-107` | `:66-96` |
| 016 | 근거 | `:36,76-81` | `:36,75-82` |
| 017 | 근거 | `:71-73,109-113` | `:71-73,113-115` |
| 018 | 근거 | `:137-145` | `:136-143` |
| 021·022 | 근거 | `ControlNotifyFallbackRetryJob.java:31,40-73` | `:31,48-71`(022 는 재실패 `62-65` 명시) |
| 024 | 근거 | `ControlNotifyFallbackService.java:109-135` | `:108-129` |
| 025 | 근거 | `ControlNotifyDebouncer.java:72-74` | `:85-87` |
| 040 | 근거 | `AsyncDatasetExportRunner.java:67-76,90-130` | `:67-76,90-106,134-149` |
| 043 | 근거 | `ControlNotifyDebouncer.java:285-308` | `:334-357` |
| 044 | 근거 | `ControlNotifyDebouncer.java:241-269` / `JpaControlNotifyDebounceStore.java:103-112` | `:259-271,290-318` / `:102-110` |
| 045 | 근거 | `ControlNotifyDebouncer.java:82-83,241-269` | `:96,290-318` |

추가로 3건의 **기대결과 보강**(정정이 아니라 실측 사실 부기)을 같은 라인범위에 넣었다 — TC-NOTIFY-003(`event_type_cd` pass-through·두 코드체계 공존, D-ISSUE-63 이월) · TC-NOTIFY-028(400 message 메서드명 노출, D-ISSUE-24 이월) · TC-NOTIFY-041(`NO_INPUT` 은 회수 대상이 아님, D-ISSUE-41) · TC-NOTIFY-040(판정 원천이 `DatasetExportOutcome.notifiable()` 임을 명시) · TC-NOTIFY-051(3차 실증 fail-open, D-ISSUE-42).

> ⚠ **병합 담당자 유의**: `D-review-version-notify.md` 상단 **`## 변경 이력` 표에 3차 행을 이미 다른 파트가 추가**해 두었다(“근거 file:line 전수 재확인 회차”). 본 파트의 정정 16건 + 보강 5건은 그 행에 **아직 반영돼 있지 않다** — 병합 시 해당 행의 정정 건수를 갱신하거나 D-6/D-7 정정 내역을 요약에 덧붙일 것. 파트 간 동시 편집 충돌을 피하려고 본 파트는 담당 라인범위(166~234) 밖을 건드리지 않았다.

> 드리프트가 **없던** 파일: `ControlNotifyService.java`(전 11케이스 정확) · `ControlNotifyClient.java` · `ControlNotifyPayloadFactory.java` · `TaskCompletedPayload/TaskModifiedPayload/ExportFileNaming` · `TaskQueryController.java`(전 8케이스 정확) · `TaskQueryService.java` · `WebClientConfig.java`(2차에서 갱신됨) · `DatasetExportFailureRecoverer.java` · `DatasetExportBridge.java`.

---

## 7. 참고 — 이번 회차가 남긴 데이터 (다른 에이전트 유의)

- 검증 목적으로 **API 호출만** 수행했고 DB 직접 수정·코드 수정은 하지 않았다.
- 신규 생성 데이터: `ls_control_notify_fallback` queue_sn **83**(rawSn=115 TASK_MODIFIED), `ls_dataset_export` export_sn **71**(rawSn=115 v3 SUCCEEDED), `ls_data_raw` 147/115 의 촬영환경 3필드(수동값 `비/NGT/WINTER`, `눈/DAY/FALL`).
- **rawSn=147 은 "APPROVED + 프레임 0건" 이라 export 가 항상 `NO_INPUT` 으로 끝난다** — 다른 클러스터가 이 영상으로 export/통지 케이스를 검증하면 오탐이 난다.
- mock-server 등록부(`GET /api/data-set/v2/jobs`)에 job_id 4·27·33·101·115·900·906 이 등록돼 있어, 이후 최초 완료 통지는 **409 자기치유 경로**를 탄다. 초기화하려면 `POST http://localhost:9400/api/data-set/v2/jobs/_reset`.
