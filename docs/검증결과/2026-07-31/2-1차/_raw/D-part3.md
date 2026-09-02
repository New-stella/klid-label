# D-part3 — D-5 diff/rollback(TC-DIFF 26) + D-6 관제 통지(TC-NOTIFY 45) = 71건

- 회차: 2026-07-31 / 2-1차
- 담당 범위: `docs/test-cases/D-review-version-notify.md` L129~211
- 이슈 ID 대역: **D-ISSUE-31 ~ 55**
- 판정 기준: `UNCERTAINTIES.md` ★확정정책 + **#3(TASK_COMPLETED 6필드 평면 계약)**, #26, #27
- 실동작 환경: backend `http://localhost:18081/api`, mock-server `:9400`, DB `public` 스키마 (모두 기동 유지, 재기동 없음)

## 0. 실동작 근거 요약 (이 파트의 판정 토대)

| 근거 | 내용 |
|---|---|
| **관제 통지 실왕복** | mock-server 인바운드: `POST /api/data-set/v2/jobs/4/notify-completed` **202**, `.../4/notify-updated` **202**, `.../5/notify-completed` **202** (`docker logs klid-mock-server`) |
| **실제 전송 페이로드(DB 관찰행)** | `ls_control_notify_fallback` 7행 전부 `STTS_CD=SUCCEEDED / SEND_RSLT_CD=SUCCESS`, `PAYLOAD_CN` 에 **실제 전송 본문** 보존 |
| TASK_COMPLETED 실본문 | `{"job_id":"4","event_type_cd":"INTRUSION","lclgv_cd":"11110","lclgv_nm":null,"duration_sec":30,"image_count":30}` · rawSn=5 는 `"image_count":3` |
| TASK_MODIFIED 실본문 | `{"job_id":"4","changed_items":{"images":["0000.jpg"…"0029.jpg"],"jsons":["0000.json"…]}}` (30/30), rawSn=5 는 3/3 |
| **self-fill 대조** | `ls_data_raw`(rawSn=4) `evnt_type_cd=INTRUSION`·`lclgv_cd=11110`·`vdo_len_sec=30`, `count(ls_data_src where raw_sn=4)=**30**` / rawSn=5 는 `=**3**` → 페이로드 값이 **영상마다 DB 실측을 따라 달라짐**. 상수 0/null 하드코딩 **0건** |
| `lclgv_nm=null` 정당성 | `select * from mng_ex_local_gov` → **0행**. backend WARN `[ControlNotify] local gov not found lclgvCd=11110` → 지어내지 않고 null (self-fill 아님) |
| **순서 보장(C-2)** | `03:04:26.283 export succeeded rawSn=4 version=1` → `03:04:26.292 TASK_COMPLETED sent` / `03:16:22.909 export succeeded version=2` → `03:16:22.917 TASK_MODIFIED sent` |
| **자기치유 409 실발동** | `03:17:19.181 [ControlNotify] completed conflicted -> resend as updated rawSn=4` → `TASK_COMPLETED sent rawSn=4 **actual=TASK_MODIFIED**` (rawSn=5 도 `03:18:48` 동일) |
| **디바운스 전용 스케줄러** | 기동 로그 `[ControlNotifyDebounce] flush scheduler started intervalMs=10000 leaseMs=300000`, flush 로그의 스레드명 `**control-notify-debounce-flush**` |
| **디바운스 공유DB 축적** | `ls_mon_noti_acml` 실행 중 관측: `{"frames":{"25":["LABEL_ADDED","LABEL_UPDATED"],"26":["LABEL_ADDED"]}}` (STTS_CD=PENDING → flush 후 삭제) |
| **롤백 멱등 실측** | `POST /v1/versions/6c53c75f…/rollback {"srcSn":1}` → 200 + 로그 `[Version] rollback no-op (already active and labels identical) srcSn=1`. 전/후 `ls_data_lbl.lbl_sn=1` 불변·`mdfcn_dt` NULL 유지·`ls_data_src.lbl_ver=1` 불변·`ls_data_lbl_hstry` 1건 유지·`ls_mon_noti_acml` 0행 유지 |
| diff/rollback HTTP 실측 | 아래 표의 `[실동작]` 행 참조 (REVIEWER/WORKER/PORTAL dev 토큰 3종으로 왕복) |

> **결론(선반영)**: TASK_COMPLETED 6필드 계약은 **self-fill 없음**. 옛 확정 기대값(`totalFrames`/`labeledFrames`/`reviewerName`)은 계약에서 소멸했고, 케이스 문구도 이미 6필드로 갱신돼 있어 **드리프트 없음**.

---

## 1. D-5. diff / rollback (TC-DIFF) — 26건

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|------|------|
| TC-DIFF-001 | PASS | [정적] `VersionService.java:1200-1234 diffLabelMaps` ADDED/REMOVED/MODIFIED 3분류 + `VersionServiceTest`("diff_두_스냅샷_labels_JSON_파싱하여_라벨_단위_ADDED_REMOVED_MODIFIED_반환","diff_라벨_shape_변경시_MODIFIED_분류_before_after_좌표_모두_포함") | 동일 srcSn 의 2개 APPROVED 버전이 DB에 없어 실동작 분류는 미재현(같은 해시 diff=`[]` 만 실측). 식별축은 `items[].id` |
| TC-DIFF-002 | PASS | [정적] `VersionService.java:1180-1197 readPoints` — `TYPE_SKELETON` 만 3번째 원소(v) 포함. 테스트 "버전diff_SKELETON_v만_바뀌면_MODIFIED_감지"/"…v_동일이면_변화없음"/"기존BBOX_2튜플_회귀없음" | |
| TC-DIFF-003 | PASS | [실동작] `GET /v1/versions/{H2}/diff?compareWith={H1}`(srcSn 2 vs 1) → `200 {"data":[]}`. 내용이 서로 다른데 빈 결과 → `VersionService.java:359-361` 조기 반환 실행 확인 | |
| TC-DIFF-004 | PASS | [실동작] 비-hex `zzzz` → **400** `INVALID_INPUT "잘못된 버전 해시 형식입니다."`, 128자(>64) → **400**. [정적] `validateHash 1247-1258` | null 은 컨트롤러 `@PathVariable` 필수라 404/400 로 수렴 |
| TC-DIFF-005 | PASS | [실동작] 미존재 `deadbeef` → **404** `NOT_FOUND "to 버전을 찾을 수 없습니다."` | |
| TC-DIFF-006 | PASS | [실동작] 미배정 WORKER(userNo=2999) 토큰 → **403** `FORBIDDEN`. 무토큰 → **401**. [정적] `348-349` 양 버전 각각 `accessGuard.verifyAndGet` | |
| TC-DIFF-007 | PASS | [정적] `computeLabelDiffs 1129-1136` — `parseLabelsById` 가 던진 예외를 catch 후 `List.of()`, 로그는 예외 클래스명만(CWE-209 회피) | |
| TC-DIFF-008 | PARTIAL | [정적] `410-522` ①`replaceFrameLabels 663-704` full-replace + `insertRestoredWithExplicitIds`(옛 LBL_SN 보존, 점유 PK 만 신규발급 폴백) ②`activateRollbackTarget 535-545` 재활성(신규 행 0) ③`recordRollbackEvent 509-510`. IT `VersionRollbackRestoreIT`/`VersionRollbackHistoryIT` 10+7건 | **①의 "AI메타·TRCK_ID 보존"은 충족하나 라벨 속성값 `LS_DATA_LBL_ATTR_VAL` 은 스냅샷에 없어 삭제만 되고 복원 안 됨(영구 소실)** → **D-ISSUE-32** |
| TC-DIFF-009 | PASS | [정적] `parseSnapshotLabels 580-582`(빈/공백 → `List.of()`) → `replaceFrameLabels` 가 기존 라벨 delete 후 `restore` 가 0건 삽입 → 프레임 라벨 0 | |
| TC-DIFF-010 | PASS | [정적] `441-442` — 라벨 교체 **전에** `parseSnapshotLabels` 수행, 파싱 실패 시 `INVALID_INPUT`(400) 로 전체 롤백. `589,596` | SKELETON `points` 누락도 `canonicalKeypointJson 818-831` 에서 동일 400(fail-closed) |
| TC-DIFF-011 | PASS | [정적] `427-430 workLockService.isRawLocked → CONFLICT(409)`. 테스트 "작업락_잠긴_영상_rollback시_CONFLICT_거부_라벨_미변경" | 현재 `ls_auth_work_lock` 잠금 행 없어 실동작 미재현 |
| TC-DIFF-012 | PASS | [정적] `516-520` `TaskModifiedEvent(rawSn, srcSn, LABEL_UPDATED, actorNo, **true**)`. 테스트 "APPROVED_영상_rollback시_TaskModifiedEvent_LABEL_UPDATED_발행" + `VersionServiceRollbackIdempotencyTest`("실질_변경이_있는_롤백에서는_exportRegenerated_통지가_발행된다") | flush→re-export→통지 직렬화는 §0 실동작(TASK_MODIFIED regen 경로)에서 동형 확인 |
| TC-DIFF-013 | PASS | [정적] `516 isReviewApproved` 가드. 테스트 "미검수_영상_rollback시_TaskModifiedEvent_미발행" | |
| TC-DIFF-014 | PASS | [실동작] rollback(H1, srcSn=1) → 200, 로그 `rollback no-op (already active and labels identical)`. **LBL_SN=1 불변 · `mdfcn_dt` NULL 유지 · `lbl_ver` 1 불변 · 이력 1건 그대로 · `ls_mon_noti_acml` 0행(통지·재생성 미발행)** | 전제 검증: 스냅샷 items(id=1,BBOX,person,[[10,10],[50,50]])와 현재 `ls_data_lbl` 완전 일치 상태에서 호출 |
| TC-DIFF-015 | PASS | [정적] `537-541` `findByDataSrcSnAndVersionHash(newHash)` → 있으면 그 행 activate + `deactivateOthers`. 테스트 "R12_1_롤백_대상_payload_해시가_기존_버전과_동일하면_UNIQUE_충돌없이_기존행_active_전환" | |
| TC-DIFF-016 | PASS | [실동작] 미존재 해시 rollback → **404** `"롤백 대상 버전을 찾을 수 없습니다."` / 타 프레임 해시(H2)+srcSn=1 → **404**(srcSn 스코프 강제 확인) | |
| TC-DIFF-017 | PASS | [실동작] 무토큰 → **401** `UNAUTHORIZED`, 미배정 WORKER → **403**, PORTAL 토큰 → **403** | `412-414` 의 `actor==null` 분기는 필터가 선차단해 HTTP 로는 도달 불가(방어 심층화) |
| TC-DIFF-018 | PASS | [정적] `444-449 findActiveForUpdate`(비관적 잠금) 를 라벨 교체보다 **먼저** 획득, `VersionServiceRollbackLockOrderTest`("잠금_획득이_라벨_DELETE_보다_먼저") | 2노드 동시 롤백은 단일 인스턴스라 실재현 불가 |
| TC-DIFF-019 | PASS | [정적] `607-608 rawPointsJson` 보존 + `818-831 canonicalKeypointJson` 정규 재직렬화. IT "SKELETON_왕복_롤백_후_POINT_CN_의_v_가_변질되지_않는다" | |
| TC-DIFF-020 | PASS | [정적] `1260-1262` PORTAL → false. **프로덕션 호출자 0건 재확인** — `grep isCommittable` 결과가 정의 1 + 테스트 3 뿐 | 케이스 문구(“dead code, D-ISSUE-28 미해소”)와 현행 일치 → 이월 상태 그대로 |
| TC-DIFF-021 | PASS | [정적] `432-437 accessGuard.requireNotUnderDeidentReport` → `LabelAccessGuard:135-140` `PRECONDITION_FAILED(412)`. IT `DeidentReportGateCoverageIT`("신고_상태에서는_롤백이_거부된다") | `de_ident_yn='F'` + 버전 보유 영상이 DB에 없어 실동작 미재현 |
| TC-DIFF-022 | PASS | [정적] `351-357` — 인가(`verifyAndGet`) **이후** rawSn 단위 평가, 두 버전 동일 영상이면 1회만 조회(N+1 회피). IT "신고_상태에서_label_history_와_version_diff_가_차단된다" | |
| TC-DIFF-023 | PASS | [정적] `341-346 requireFrameScoped` → 인가 이전 `INVALID_INPUT(400)`. `VersionServiceDiffNullSrcSnTest` 3건(from/to 양측, 정상 경로 무영향) | 현 DB에 `DATA_SRC_SN IS NULL` 버전 0행이라 실동작 미재현 |
| TC-DIFF-024 | PASS | [정적] `486-498`(멱등 경로) + `540`(교체 경로) 모두 `deactivateOthers`. 테스트 "조기_반환시에도_잉여_ACTIVE_가_정리된다" | |
| TC-DIFF-025 | PASS | [정적] `451-473` `srcRepository.lockAndReadLabelVersion` 을 멱등 판정 **이전** 호출, 락 순서 VERSION→SRC→LBL 주석·테스트 "멱등_판정_전에_프레임_락을_취득한다" | bump(UPDATE) 아닌 스칼라 FOR UPDATE 라 no-op 시 `LBL_VER` 미증가 — TC-DIFF-014 실측과 정합 |
| TC-DIFF-026 | FAIL | [정적] `findByHashOrThrow 1020-1026` 이 **여전히 `matches.get(0)`**. `findByVersionHash` 에 정렬·유일성 제약 없음 | **D-ISSUE-27 미해소 → D-ISSUE-31 로 이월** |

**TC-DIFF 집계**: PASS 24 / PARTIAL 1 / FAIL 1 / BLOCKED 0 / N/A 0 / 확인필요 0

---

## 2. D-6. 관제 통지 (TC-NOTIFY) — 45건

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|------|------|
| TC-NOTIFY-001 | PASS | [실동작] `DatasetExportBridge review approved rawSn=4` → `AsyncDatasetExportRunner async approval export starting` → `export succeeded version=1`(03:04:26.283) → `TASK_COMPLETED sent`(.292) → mock 202. **승인 이벤트 직후 발행 아님**을 시각 순서로 확인 | `ControlNotifyEventListener:38-41` 이 `DatasetExportCompletedEvent` 만 소비 |
| TC-NOTIFY-002 | PASS | [정적] `DatasetExportBridge:36-37` `@TransactionalEventListener(AFTER_COMMIT)` — 롤백 시 미호출 → export·통지 모두 미발생. `DatasetExportBridgeTest` | |
| TC-NOTIFY-003 | PASS | [실동작] 전송 본문 6필드 평면 snake_case 실측(§0). `event_type_cd/lclgv_cd/duration_sec` = `ls_data_raw` 실값, `image_count` = `count(ls_data_src)` 실측(rawSn4=30, rawSn5=3). `lclgv_nm=null` 은 마스터 0행 + WARN 근거. **상수 self-fill 0건**. 라벨·PII·토큰·원본경로 미포함 | `event_type_cd` 매핑표 미수령(#27) → **D-ISSUE-34**(확인필요, 결함 아님) / `image_count` 의미 → **D-ISSUE-35**(확인필요) |
| TC-NOTIFY-004 | PASS | [정적] `ControlNotifyService:130-134` catch → `enqueueQuietly` + `metrics.incrementCompletedFailed()`. 테스트 "TASK_COMPLETED_실패시_폴백큐_적재됨" | 이번 회차 실패 0건이라 큐 적재는 미재현(성공행만 관측) |
| TC-NOTIFY-005 | PASS | [실동작] 자기치유 발동 건(queue_sn=4, rawSn=4)의 관찰행 `EVNT_TYPE_CD=**TASK_MODIFIED**` — 요청 종류(completed)가 아니라 **실제 전송분** 기준. `ControlNotifyService:126-129,318-325` | |
| TC-NOTIFY-006 | PASS | [정적] `recordSendSuccess 318-325` try/catch + warn, 통지 성공 유지. 테스트 "관찰_적재_실패해도_통지성공_metrics는_유지된다" | |
| TC-NOTIFY-007 | PASS | [실동작] TASK_MODIFIED 본문 = `{"job_id":"4","changed_items":{"images":["0000.jpg"…],"jsons":["0000.json"…]}}` — **FRM_NO 4자리 zero-pad**, SRC_SN·좌표·메타본문·절대경로 없음. `ChangedItems` compact 생성자가 null→`List.of()` 강제 | `ExportFileNaming:44-58`(음수 fail-closed) |
| TC-NOTIFY-008 | PASS | [실동작] `ls_mon_noti_acml` 행 관측 — `chg_dtl_cn={"frames":{"25":["LABEL_ADDED","LABEL_UPDATED"],"26":["LABEL_ADDED"]}}`, `export_rprcs_yn=Y`, flush 후 행 삭제(`deleteFlushed`). `V144__create_ls_mon_noti_acml.sql` 존재 | 저장 내용에 본문·PII 없음(식별자+변경종류만) |
| TC-NOTIFY-009 | PASS | [정적] `ControlNotifyDebouncer:80,191-204` `@PreDestroy flushAll` — 스케줄러 shutdownNow 후 `windowCutoff=now` 로 만료 무관 drain, `MAX_DRAIN_ROUNDS=20`. 테스트 "flushAll_모든_윈도우_즉시_flush","종료시_flushAll_도_개별_실패에_중단되지_않는다" | |
| TC-NOTIFY-010 | PASS | [실동작] 기동 로그 `flush scheduler started intervalMs=10000 leaseMs=300000` + flush 로그 스레드명 `control-notify-debounce-flush`(데몬 단일 스레드). `@Scheduled/@EnableScheduling` 비의존 확인(`100-103,142-157`) | `regen-flush.enabled=false` 게이트는 `144-147` |
| TC-NOTIFY-011 | PASS | [실동작] flush 요약 로그 `frames=25=[LABEL_UPDATED, LABEL_ADDED],26=[LABEL_DELETED, LABEL_ADDED]` — 계약 4종만 관측되고 **전송 페이로드에는 미포함**(§0 본문). `ChangeType:18-28` 4상수 | |
| TC-NOTIFY-012 | PASS | [정적] `LabelService:412-426` `if (!changes.isEmpty() && isReviewApproved(rawSn))` — 검수 전 저장은 미발행 | |
| TC-NOTIFY-013 | PASS | [정적] `LabelService:397-406,412` — `changes` 비면 이력·버전 bump·통지 전부 미발생 | |
| TC-NOTIFY-014 | PASS | [정적] 7경로 전부 `exportRegenerated=true` 확인: `LabelService:423-424`·`TrackEditService:326`·`TrackMergeService:201`·`VersionService:518-519`·`EnvironmentMetaService:125-126`·`FrameDescriptionService:60-61`·`FramePrivacyMetaService:131-132,154-155`. [실동작] 라벨 수정 → `export succeeded version=2` → `TASK_MODIFIED sent … reExport=true` 순서 | **카탈로그 드리프트(결함 아님)**: regen=true 발행처가 실제 **9곳** — `DatasetVideoMetaEnvCorrectionTx:79-80`·`EvntAnnoReviewService:230-231` 이 표에 누락. regen=false(4-arg) 발행처는 `MetaService:83`·`EvntAnnoService:128`·`DeidentReportService:231` |
| TC-NOTIFY-015 | PARTIAL | [정적] `ControlNotifyFallbackService:91-95` `DataIntegrityViolationException` 삼킴 + 멱등 반환(계약 충족). 그러나 `idempotencyKey = UUID.randomUUID()`(Service:110,164)라 **매 시도 새 키** → UK 는 사실상 발동하지 않는 안전망 | 중복 통지 자체를 막는 dedup 아님 → **D-ISSUE-36**(LOW) |
| TC-NOTIFY-016 | PASS | [정적] `ControlNotifyFallbackService:36,74-82` `countBySttsCdIn(ACTIVE) >= 10000` → `IllegalStateException` + `control.notify.fallback.queue.full` 카운터 | check-then-act 라 동시성 하에선 soft cap(초과 가능) — 케이스 단언 범위 밖, 참고 |
| TC-NOTIFY-017 | PASS | [정적] `71-73`(enqueuePending) / `113-115`(recordImmediateSuccess) 모두 `!enabled → Optional.empty()` | |
| TC-NOTIFY-018 | PASS | [정적] `137-143 claimForRetry` → `LsControlNotifyFallbackRepository:45-48` 조건부 UPDATE(`STTS_CD='PENDING'` 일 때만), `updated != 1 → empty` | |
| TC-NOTIFY-019 | PASS | [정적] `LsControlNotifyFallback:194-213` — `rtryCnt+=1` 후 `min(60, 1<<min(rtryCnt,6))` 분 → PENDING 복귀 | 실제 백오프는 2·4·8·16·32(cap 60)로 javadoc 예시 "(1,2,4,8,16)"과 1칸 어긋남 — **주석 표기 오류(동작은 계약 충족)** |
| TC-NOTIFY-020 | PASS | [정적] `201-207` `rtryCnt > maxRtryCnt(5)` → `DEAD_LETTER` + `dlqDt=now` + `nextRtryDt=null` | |
| TC-NOTIFY-021 | PASS | [정적] `ControlNotifyFallbackRetryJob:31,48-71` `BATCH_SIZE=20`, due 조회 → `claimForRetry` → `processOne` → `markSucceeded` | 이번 회차 due 항목 0건(전건 즉시 성공) |
| TC-NOTIFY-022 | PASS | [정적] `62-65` catch → `markFailedAndSchedule(예외종류 + 메시지)`. `ControlNotifyFallbackRetryJobTest` | |
| TC-NOTIFY-023 | PASS | [정적] `LsControlNotifyFallback:246-253 sanitizeError` — 제어문자 `[\r\n\t -]`→공백, `(?i)(authorization\|token\|bearer\s+)[^\s,;]*`→`$1***`, `https?://…`→`URL_REDACTED`, 1900자 truncate. `LsControlNotifyFallbackTest` | |
| TC-NOTIFY-024 | PASS | [정적] `109-129 recordImmediateSuccess` UK 충돌 삼킴(REQUIRES_NEW) | 실효 dedup 한계는 D-ISSUE-36 과 동일 축 |
| TC-NOTIFY-025 | PASS | [정적] `@ConditionalOnProperty(control-notify.enabled=true)` 부착: `ControlNotifyClient:42`·`ControlNotifyService:53`·`ControlNotifyPayloadFactory:60`·`ControlNotifyEventListener:27`·`ControlNotifyFallbackRetryJob:27`·`TaskQueryController:46`(+`TaskQueryService:48`). 미부착(항상 등록): `ControlNotifyDebouncer:73`·`TaskModifiedAccumulateListener:27`·`DatasetExportBridge:29-30`(dataset-export 토글, matchIfMissing) | `ControlNotifyFallbackService` 는 빈은 항상 등록되고 `enabled` 필드로 게이트(케이스 목록과 정합) |
| TC-NOTIFY-032 | PASS | [실동작] mock 인바운드 `POST /api/data-set/v2/jobs/4/notify-completed` **202**, `.../4/notify-updated` **202**. [정적] `ControlNotifyClient:55-58` 경로 상수, `93 contentType(APPLICATION_JSON)`, `96-99 is2xxSuccessful` 전체 수용 | 200/201 수용은 정적(목은 202 반환) |
| TC-NOTIFY-033 | PASS | [실동작] rawSn=4 재승인 시 mock 409 → 로그 `completed conflicted -> resend as updated rawSn=4` → `actual=TASK_MODIFIED`, 관찰행 `TASK_MODIFIED`(queue_sn=4). rawSn=5 도 동일 재현(queue_sn=6). `metrics.incrementSelfHealCompletedToUpdated()` 호출 지점 `227` | `buildModifiedForAllFrames` 로 전 프레임(30/30, 3/3) 전송 실측 |
| TC-NOTIFY-034 | PASS | [정적] `ControlNotifyService:251-262` 404 → `buildCompleted` 1회 폴백 + `selfHealUpdatedToCompleted`. mock `control.py:163-170` 이 실제 404 반환. 테스트 "updated_가_404면_completed_로_1회_폴백된다" | 실동작에서는 선행 completed 가 항상 존재해 404 미발생 |
| TC-NOTIFY-035 | PASS | [정적] `225-231`(409 분기 내부는 재차 자기치유 없음) / `255-261`(404 분기 동일). 테스트 "폴백은_재귀하지_않는다","completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다" | |
| TC-NOTIFY-036 | PASS | [정적] `ControlNotifyClient:115-120` 4xx→`ControlNotifyStatusException`(=`NonRetryableExternalException`, R4J ignore) / 5xx→`IllegalStateException`(재시도·서킷 집계). 테스트 "409_404_이외의_4xx_는_자기치유하지_않고_실패_처리된다","수정통지가_500이면…폴백큐로_간다" | |
| TC-NOTIFY-037 | PASS | [정적] `ControlNotifyService:73 JOB_ID_PATTERN=\d{1,19}` + `assertValidJobId` 를 dispatchCompleted/dispatchModified **및 치유 페이로드**(`229`,`259`)에 모두 적용 → 경로 세그먼트 조작 차단 | mock 측도 동일 정규식으로 400(이중 방어) |
| TC-NOTIFY-038 | PASS | [정적] `112-123` 조립 실패 시 폐기 금지 — `PAYLOAD_REBUILD_REQUIRED`("") 로 큐잉, `ControlNotifyFallbackRetryJob:85-101` 이 `rebuild → dispatch*(null,…)` 로 재조립. 테스트 "페이로드_조립에_실패하면_통지가_폴백큐에_적재되어_재시도된다" | |
| TC-NOTIFY-039 | PASS | [정적] `170-188` — regen=false 면 `TaskModifiedPayload(jobId, ChangedItems.empty())` 확정 적재, regen=true 면 REBUILD_REQUIRED. 테스트 MED2 2건 | |
| TC-NOTIFY-040 | PASS | [정적] `AsyncDatasetExportRunner:67-76`(성공 시에만 `DatasetExportCompletedEvent`), `90-106`(성공 시에만 콜백), `121-130 doExport` 예외→false. `AsyncDatasetExportRunnerTest` | 신고 게이트 차단도 같은 false 경로(주석 115-117) |
| TC-NOTIFY-041 | PASS | [정적] `DatasetExportFailureRecoverer:119-170` — `findRetryableFailedAnchors(cutoff, attempts, size)` → `txService.claimForRetry`(조건부 UPDATE, `DatasetExportTxService:257-261`) → `runner.runApprovalAsync`(성공 시 완료 이벤트 재발행). `DatasetExportFailureRecoveryIT` | 실행 로그 `[DatasetExportRecovery] no retryable failed export`(FAILED 행 0건) |
| TC-NOTIFY-042 | PASS | [정적] `141-144` — `deidentReportGate.isUnderDeidentReport(rawSn)` 를 **클레임 이전**에 평가해 `continue`(RTY_NMTM 미증가) + INFO 집계 로그 `159-162` | |
| TC-NOTIFY-043 | PASS | [정적] `TaskModifiedAccumulateListener:27-37`(토글 없음, 항상 등록) + `ControlNotifyDebouncer:296-303`(regen 윈도우는 `notifyService==null` 이어도 `runReExportThenNotify` 위임). 테스트 "HIGH-E_통지_토글_off여도…export를_트리거한다","재export_트리거는_control_notify_토글과_무관하게_동작한다" | 로컬은 토글 on 이라 off 형상은 정적/테스트 근거 |
| TC-NOTIFY-044 | PASS | [정적] `ControlNotifyDebouncer:242-269` → `JpaControlNotifyDebounceStore:103-110` → `LsMonNotiAcmlRepository:88-96 claimForFlush` 조건부 원자 UPDATE(1행만). IT `ControlNotifyDebounceCrossNodeIT` | 단일 인스턴스라 2노드 실재현 불가(설계·쿼리 확인) |
| TC-NOTIFY-045 | PASS | [정적] `255-269` — send 실패 시 `complete()` 미호출 → FLUSHING 잔존, `findFlushableAnchors` 의 `STTS_CD='FLUSHING' AND MDFCN_DT<=leaseCutoff` 축으로 재클레임. `MIN_LEASE_MILLIS=60_000` clamp(84), 기본 300s(실동작 로그 `leaseMs=300000`), `dropped` metric + ERROR, 루프 유지 | 테스트 "flush_실패로_남은_윈도우는_임차_만료_후_재클레임되어_다시_발송된다" |
| TC-NOTIFY-046 | PASS | [정적] `ControlNotifyService:301-309 enqueueQuietly` — 예외 삼킴 + `metrics.incrementDropped()` + ERROR. 테스트 "폴백큐가_가득_차도_예외가_호출자로_전파되지_않는다","…디바운서_flush_루프가_중단되지_않고" | |
| TC-NOTIFY-047 | PASS | [실동작] 마스터 미존재 → `lclgv_nm:null` + WARN `local gov not found lclgvCd=11110`(실전송 본문으로 확인). [정적] `ControlNotifyPayloadFactory:150-174` `USE_YN<>'Y'`→null, 100자 초과 절단. 테스트 3건("지자체_마스터에_없으면…null","폐지된_지자체_코드는…","100자를_넘으면_절단") | |
| TC-NOTIFY-048 | PASS | [정적] `185-219 resolveFrameNos` — `findBothVelExportableFrameNoByRawSnAndSrcSnIn`(양 벌 보유 프레임만) 결과에 없으면 목록 제외 + WARN(식별자·건수만) + `incrementUnresolvedFrame`. 테스트 4건 | 실동작에서는 미해석 프레임 0건(로그 무발생) |
| TC-NOTIFY-049 | PASS | [실동작] rawSn=4 전량 통지 30건 = `DatasetExportWriter … written=30 skipped=0` 과 일치 / rawSn=5 는 3건. [정적] `120-126 findExportableFrameNosByRawSn`(양 벌 중 하나라도 경로 보유) | |
| TC-NOTIFY-050 | PASS | [정적] `DatasetExportBridge:81-88 onDeidentReportResolved`(AFTER_COMMIT) → `runApprovalAsync` → force 재산출 + 완료 이벤트로 보류 통지 재개 | 신고 해소 시나리오 실재현 안 함(타 에이전트 데이터 간섭 회피) |
| TC-NOTIFY-051 | 확인필요 | [정적] `WebClientConfig:76-80 controlNotifyWebClient` 는 `baseUrl` 만 설정(`defaultHeader` 없음), `ControlNotifyClient:90-108 doPost` 도 `Content-Type` 만 부착 → **`x-access-token` 미배선 유지**(UNCERTAINTIES #26 그대로) | mock 이 인증을 요구하지 않아 202 로 통과 — 실관제 연동 시 401 예상 → **D-ISSUE-33** |

**TC-NOTIFY 집계**: PASS 43 / PARTIAL 1 / FAIL 0 / BLOCKED 0 / N/A 0 / 확인필요 1

---

## 3. 판정 집계 (D-part3 합계 71건)

| 판정 | 건수 |
|---|--:|
| PASS | **67** |
| PARTIAL | **2** |
| FAIL | **1** |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | **1** |

- 실동작(`[실동작]`) 근거로 판정한 케이스: **21건** (mock 인바운드 로그 · 전송 페이로드 DB 관찰행 · HTTP 왕복 · 상태 전/후 대조)
- **self-fill 결함: 0건** — 관제 통지 전 필드가 DB 실측이며 영상별로 값이 달라짐을 2개 영상(rawSn 4/5)으로 교차 확인

---

## 4. 이슈 대장 (D-ISSUE-31 ~ 36)

### [D-ISSUE-31] TC-DIFF-026 — 다중 매칭 버전 해시에서 `matches.get(0)` 비결정 선택 (D-ISSUE-27 이월·미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `versionHash` 는 페이로드 SHA-256 이라 **서로 다른 프레임이 동일 라벨 집합을 가지면 해시가 충돌**한다(예: 빈 라벨 프레임, 동일 좌표 복제 프레임). `diff` 가 어느 버전을 집을지 결정적이어야 하며, 불가하면 "해시가 모호하다"고 명시 거부해야 사용자가 잘못된 프레임의 좌표 diff 를 보고 판단하는 일이 없다.
- **현재 동작(이슈 내용)**: `VersionService.java:1020-1026`
  ```java
  private LsLabelVersion findByHashOrThrow(String versionHash, String notFoundMessage) {
      List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
      if (matches.isEmpty()) { throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage); }
      return matches.get(0);   // ← 정렬·유일성 보장 없음
  }
  ```
  `findByVersionHash` 에 `OrderBy` 도 없고 `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 는 프레임별로만 걸려 있어 전역 유일이 아니다. DB 플랜/인덱스에 따라 반환 순서가 달라질 수 있다.
- **재현/확인 경로**: 서로 다른 두 프레임(srcSn A/B)이 동일 라벨 페이로드로 승인 스냅샷을 만들면 `LS_LABEL_VERSION` 에 같은 `VERSION_HASH` 2행이 생긴다. 이후 `GET /api/v1/versions/{hash}/diff?compareWith={other}` 가 어느 행을 잡는지 비결정.
  ```sql
  select version_hash, count(*) from ls_label_version group by version_hash having count(*)>1;
  ```
  (현 DB는 4행 전부 해시 상이 → 재현 데이터 없음. 코드 축으로만 확인)
- **영향**: 기능 정합 — 사용자가 요청하지 않은 프레임의 좌표 before/after 가 노출될 수 있다(인가는 그 프레임 기준으로 다시 걸리므로 권한 우회는 아님). CWE-670(부적절한 제어 흐름) 성격.
- **수정 방향(제안)**: ①`diff` API 가 `srcSn` 을 함께 받아 `findByDataSrcSnAndVersionHash`(rollback 과 동일 조회)로 좁히거나 ②`findByVersionHash` 를 `OrderByLabelVersionSnAsc` 로 결정화하고 2건 이상이면 `INVALID_INPUT`("해시가 여러 프레임에 걸쳐 모호합니다")로 명시 거부. **구현하지 않음.**

### [D-ISSUE-32] TC-DIFF-008 — 롤백이 라벨 속성값(`LS_DATA_LBL_ATTR_VAL`)을 복원 없이 영구 삭제
- **심각도**: HIGH
- **기대 동작(기대효과)**: 롤백은 "대상 검수완료 스냅샷 상태로의 복원"이다. 라벨 본문뿐 아니라 그 라벨에 딸린 **속성값**(occlusion/truncation 등 `LS_DATA_LBL_ATTR_VAL`)도 스냅샷 시점 값으로 돌아가야 학습데이터 확정본이 재현된다. 최소한 소실이 발생하면 안 된다.
- **현재 동작(이슈 내용)**: 승인 스냅샷 페이로드에 속성값이 **애초에 담기지 않는다**(실 payload 실측: `items:[{id,lblTypeCd,label,labelId,labelName,color,points,autoLblYn,confScore,trackId,lblSrcCd}]` — attr 키 없음). 그런데 롤백은 교체 전 전부 지운다. `VersionService.java:681-683`
  ```java
  // 고아 방지 순서: 자식(ATTR_VAL — 실 FK) → 자식(AI_INFO) → 부모(LBL). 모두 bulk delete.
  attrValRepository.deleteByLblSnIn(delSns);
  ```
  javadoc(655-658)도 “라벨 속성값은 스냅샷 페이로드에 없으므로 복원되지 않고, FK 위반(500) 방지를 위해 삭제된다”고 명시. 즉 **설계된 소실**이며 되돌릴 원천이 없다. 기존 테스트("속성값을_보유한_프레임_롤백시_FK_위반_500_이_발생하지_않는다")도 500 미발생만 단언하고 값 보존은 단언하지 않는다.
- **재현/확인 경로**: 속성값을 가진 프레임을 승인(스냅샷 생성) → 라벨 수정 → 같은 프레임 롤백.
  ```sql
  select * from ls_data_lbl_attr_val where lbl_sn in (select lbl_sn from ls_data_lbl where src_sn = :srcSn);
  -- 롤백 후 0행이 되고 어떤 버전 스냅샷에도 복구 원천이 없음
  ```
- **영향**: 데이터 정합 — 검수완료로 확정된 학습데이터의 속성 메타가 롤백 1회로 복구 불가하게 소실된다. export JSON 의 속성 필드도 함께 비게 되어 데이터마트 보유본과 어긋난다. 사용자 관점에서 "되돌리기"가 오히려 데이터를 파괴한다.
- **수정 방향(제안)**: ①`commitApproved` 스냅샷 페이로드(`LabelResponse`)에 라벨별 속성값을 포함시키고 ②`replaceFrameLabels` 가 `restore` 이후 속성값도 재삽입. 스키마 변경 없이 payload 확장만으로 가능(구 스냅샷은 attr 키 부재 → 기존 동작 유지로 하위호환). 단기 완화책으로는 속성값 보유 프레임 롤백 시 경고 응답/로그. **구현하지 않음.**

### [D-ISSUE-33] TC-NOTIFY-051 — 관제 통지에 인증 헤더(`x-access-token`) 미부착 (UNCERTAINTIES #26 유지)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 관제 inbound SPI(API-251/API-285) 계약이 `x-access-token` 인증 헤더를 요구한다. 헤더가 없으면 실관제 연동 시 전 통지가 401 로 실패하고, 폴백 큐 → 5회 백오프 → dead-letter 로 **완료/수정 통지가 전량 유실 경로**를 탄다.
- **현재 동작(이슈 내용)**: 어느 계층에도 헤더 배선이 없다.
  ```java
  // WebClientConfig.java:76-80
  @Bean(name = "controlNotifyWebClient")
  public WebClient controlNotifyWebClient(@Value("${authoring.control-notify.url:http://localhost:8090}") String baseUrl) {
      return WebClient.builder().baseUrl(baseUrl).build();   // defaultHeader 없음
  }
  // ControlNotifyClient.java:91-94 — .contentType(APPLICATION_JSON) 만 부착
  ```
  또한 토큰을 담을 설정 키(`authoring.control-notify.token` 류)가 `application.yml:359-` 에도 없다(`vlm.client.token` 은 존재 — 비대칭).
- **재현/확인 경로**: 현 mock-server(`mock-server/app/routers/control.py:24` "인증 없음")는 헤더를 검사하지 않아 202 로 통과하므로 **로컬에서는 드러나지 않는다**. 실관제 또는 헤더 검사 목을 붙이면 즉시 401.
  ```
  docker logs klid-mock-server | grep notify   # 202 Accepted — 인증 무검사
  ```
- **영향**: 연동 갭(기능 전면 실패). 보안 관점으로는 통지 엔드포인트가 **송신 측 인증 없이** 호출되는 구조라 관제 측이 발신자를 식별할 수단이 없다(CWE-306 Missing Authentication for Critical Function — 수신 측 책임이나 계약상 저작도구가 토큰을 실어야 함).
- **수정 방향(제안)**: `authoring.control-notify.token` 설정키 추가(환경변수 주입, 평문 커밋 금지) → `controlNotifyWebClient` 에 `defaultHeader("x-access-token", token)`(빈 값이면 미부착 + 기동 WARN). `vlmWebClient` 의 토큰 배선 패턴을 그대로 재사용. **관제팀과 헤더명·발급 방식 확정 후 진행** — 확정 전까지 구현하지 않음.

### [D-ISSUE-34] TC-NOTIFY-003 — 관제 `event_type_cd` 8대 코드 매핑표 미수령, 저작도구 보유값 그대로 전송 (UNCERTAINTIES #27 유지)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 관제 `datasets.event_type_cd` 는 관제 측 8대 이벤트 코드 체계를 따라야 한다. 저작도구 코드계(`LS_DATA_RAW.EVNT_TYPE_CD`)와 값이 다르면 관제가 미지 코드로 적재하거나 거부한다.
- **현재 동작(이슈 내용)**: 매핑 단일 지점이 항등 함수다. `ControlNotifyPayloadFactory.java:139-141`
  ```java
  private String toControlEventTypeCd(String authoringEventTypeCd) {
      return authoringEventTypeCd;   // 관제 코드 목록 미수령 — 보유값 그대로
  }
  ```
  실전송 본문 실측값: `"event_type_cd":"INTRUSION"` (= `ls_data_raw.evnt_type_cd`, 관제 시드 `MNG_CLIP_EVNT_LST` 유래). 관제 수용 여부 미확인.
- **재현/확인 경로**:
  ```sql
  select distinct evnt_type_cd from ls_data_raw;   -- INTRUSION, LOITERING, FALLDOWN, (null)
  select payload_cn from ls_control_notify_fallback where evnt_type_cd='TASK_COMPLETED';
  ```
- **영향**: 연동 정합 미확인. `evnt_type_cd` 가 NULL 인 영상(현 DB 6건)은 `event_type_cd:null` 로 나가는데 관제 필수 여부도 미확정.
- **수정 방향(제안)**: 관제팀에서 8대 코드값 목록 수령 → `toControlEventTypeCd` **한 곳**만 `Map`/`enum` 매핑으로 교체(호출부에 매핑 흩뿌리지 말 것) + 미매핑 값 정책(거부 vs `ETC`) 확정. **결함 아님 — 확정 전까지 현행 유지.**

### [D-ISSUE-35] TC-NOTIFY-003 — `image_count` 가 프레임 행 수(30)라 실제 산출 이미지 수(60, 원본+비식별 2벌)와 불일치
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 관제가 `image_count` 로 수신 산출물의 완결성을 1차 판단한다면, 그 값이 실제 export 폴더의 이미지 수와 같은 의미여야 한다.
- **현재 동작(이슈 내용)**: `ControlNotifyPayloadFactory.java:83,91`
  ```java
  long imageCount = srcRepository.countByRawSn(rawSn);   // 프레임 행 수
  … Math.toIntExact(imageCount));
  ```
  실측 대조(rawSn=4): 통지 `image_count=30` ↔ export 실적 `DatasetExportWriter … kind=ORIGINAL written=30` + `kind=DEIDENTIFIED written=30`, `ls_dataset_export.frame_cnt=**60**`, `V_COMPLETED_VIDEO.frame_cnt=60`(pipeline-drive 기록). 클래스 javadoc(49-52)은 "원천 프레임 행 수이며 상한(기대치)"이라고 스스로 한계를 명시하지만, **2벌 산출 구조로 인한 2배 차이**는 언급하지 않는다.
- **재현/확인 경로**:
  ```sql
  select (select count(*) from ls_data_src where raw_sn=4) frames,
         (select frame_cnt from ls_dataset_export where data_raw_sn=4 order by export_ver_no desc limit 1) exported;
  -- 30 vs 60
  ```
- **영향**: 연동 정합 — 관제가 "이미지 개수" 기준 정합 검사를 하면 매번 절반으로 인식한다. self-fill 은 아니며(값 자체는 DB 실측) **의미 정의 미확정** 문제다.
- **수정 방향(제안)**: 관제와 `image_count` 정의 확정(프레임 수 vs 파일 수 vs 원본 벌만). 파일 수라면 `countByRawSn * 산출 벌 수` 또는 `findExportableFrameNosByRawSn().size()` 기반으로 교체하되, export 미완료 시점 조달 제약(N-6, 통지 시점에 export 행 없음)을 유지할 수 있는 계산식이어야 한다. **구현하지 않음.**

### [D-ISSUE-36] TC-NOTIFY-015/024 — 폴백 큐 idempotency 키가 매 시도 새 UUID 라 실효 중복 방지가 없음
- **심각도**: LOW
- **기대 동작(기대효과)**: `IDMP_KEY` UNIQUE 는 "같은 통지가 두 번 적재/기록되지 않게" 하는 장치다. 2노드에서 같은 승인/수정에 대해 통지가 중복 발생하면 한쪽만 남아야 한다.
- **현재 동작(이슈 내용)**: 키가 통지 내용과 무관한 난수다. `ControlNotifyService.java:110,164`
  ```java
  String requestId = UUID.randomUUID().toString();   // sendCompleted / sendModified 각각
  ```
  따라서 같은 rawSn·같은 이벤트의 중복 통지도 **항상 다른 키**로 적재돼 UK 가 걸리지 않는다. 실측으로도 rawSn=4 TASK_MODIFIED 가 서로 다른 키로 3행(queue_sn 2·5·7) 적재됐다. 예외 삼킴 로직(`91-95`, `124-128`)은 정상 동작하나 **사실상 도달하지 않는 방어**다.
- **재현/확인 경로**:
  ```sql
  select evnt_type_cd, raw_sn, count(*) from ls_control_notify_fallback group by 1,2;
  -- TASK_MODIFIED / 4 / 3행 (모두 상이한 IDMP_KEY)
  ```
- **영향**: 기능 영향 낮음(관찰행 중복 = 감사 노이즈). 다만 케이스가 기대하는 "멱등"은 **결과적으로 보장되지 않으므로** 향후 재시도 중복 발송 방지 근거로 이 UK 를 신뢰하면 안 된다.
- **수정 방향(제안)**: 키를 결정적으로 — 예: `sha256(eventType + rawSn + exportVerNo)` 또는 `eventType:rawSn:exportSn`. 단 재승인마다 새 통지가 정당하므로 **버전 축(export_ver_no)을 반드시 포함**해야 정상 재통지가 막히지 않는다. **구현하지 않음.**

---

## 5. 카탈로그 드리프트 (케이스 문구 정정 후보 — 결함 아님)

| 케이스 | 드리프트 내용 |
|---|---|
| TC-NOTIFY-014 | "재export **7경로**" → 실제 `exportRegenerated=true` 발행처는 **9곳**. 누락 2곳: `DatasetVideoMetaEnvCorrectionTx.java:79-80`(META_UPDATED, srcSn=null), `EvntAnnoReviewService.java:230-231`(META_UPDATED, srcSn=null). 표에 적힌 7곳은 전부 실재·정합 |
| TC-NOTIFY-019 | 근거 클래스 javadoc(`LsControlNotifyFallback:190`)의 백오프 예시 "(1, 2, 4, 8, 16)"이 실제 동작(2·4·8·16·32, cap 60)과 1칸 어긋남 — 케이스 기대값 "2^n분(cap60)"은 충족 |
| TC-NOTIFY-025 | 케이스가 열거한 토글 종속 빈 6종 외에 `TaskQueryService`(`:48`)도 동일 조건부. 반대로 `ControlNotifyFallbackService` 는 빈 자체는 항상 등록되고 `enabled` 필드로 게이트(케이스 문구와 모순 없음, 명시만 필요) |
| TC-DIFF-004 | 기대값의 "null" 입력은 `@PathVariable` 필수 경로 변수라 HTTP 로는 재현 불가(서비스 단위에서만 유효) |

## 6. 미재현 사유 정리 (BLOCKED 아님 — 데이터 전제 부재/단일 인스턴스)

| 케이스 | 사유 |
|---|---|
| TC-DIFF-021/022 | `DE_IDNTF_YN='F'` **이면서 버전 스냅샷을 보유한** 영상이 DB에 없음. 신고 API 호출은 대상 영상에 작업락을 걸어 **병렬 검증 중인 다른 에이전트를 차단**하므로 의도적으로 실행하지 않음. `DeidentReportGateCoverageIT`(Testcontainers)가 rollback/diff 차단을 종단 검증 |
| TC-DIFF-018 / TC-NOTIFY-044 | 2노드 Active-Active 필요 — 로컬은 단일 인스턴스. 조건부 원자 UPDATE 쿼리와 전용 IT 로 확인 |
| TC-NOTIFY-004/021/022/034/041 | 이번 구동에서 통지 실패·404·FAILED export 가 0건(전건 즉시 성공)이라 폴백/재시도/회수 경로가 자연 발생하지 않음. 실패 주입은 mock 설정 변경(=환경 변경)이 필요해 미수행 |
