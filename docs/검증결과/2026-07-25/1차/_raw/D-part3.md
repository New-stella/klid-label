# D 클러스터 Part 3 — D-6 관제 통지 / D-7 관제 조회 API / D-8 데이터마트 View

> 대상: `docs/test-cases/D-review-version-notify.md` 120~182행
> 검증일: 2026-07-25 · 환경: 로컬 풀스택(klid-backend :18081 / klid-postgres / klid-mock-server :9400)
> DB: `klid_system` DB, **public 스키마** (klid_at 아님)
> 참조 데이터: `rawSn=26` (APPROVED + export SUCCEEDED, frame_cnt=32) — 상태 변경 없음(SELECT/조회만)

## ★ 환경 사실 — 통지 기능 런타임 비활성

`docker exec klid-backend env` → **`CONTROL_NOTIFY_ENABLED=false`** (정본: `.env:41`, `application.yml:199`).
`@ConditionalOnProperty(authoring.control-notify.enabled=true)` 가 걸린 빈
(`ControlNotifyClient` / `ControlNotifyService` / `ControlNotifyDebouncer` / `ControlNotifyEventListener` /
`ControlNotifyFallbackRetryJob` / `TaskQueryService` / `TaskQueryController`) 이 **전부 미등록**.

실측:
- `GET /v1/tasks/26/summary|labels|meta` (미인증) → **404** ×3 (핸들러 자체 부재)
- `SELECT ... FROM ls_control_notify_fallback` → **0 rows** (rawSn=26 승인 완료본에도 통지 관찰행 없음)
- ⇒ **rawSn=26 검수 승인 시 `TASK_COMPLETED` 통지는 실제로 발행되지 않았음**(미발행 사실 확정).

활성화는 **설정만으로 가능**(`.env` 의 `CONTROL_NOTIFY_ENABLED=true` + `CONTROL_NOTIFY_URL` 지정, 코드 수정 불요).
지시에 따라 직접 켜지 않았으므로 D-6/D-7 의 integration 성격 케이스는 정적+테스트 근거로 판정하고 `PARTIAL` 로 표기했다.
(C-ISSUE-24 기보고 사항과 동일 원인)

---

## D-6. 관제 통지 (TC-NOTIFY)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-NOTIFY-001 | TASK_COMPLETED 발행(승인 AFTER_COMMIT) | PARTIAL | [정적] `ReviewService.java:444` publish → `ControlNotifyEventListener.java:29-32` AFTER_COMMIT → `ControlNotifyService.java:43-57`. [실동작] enabled=false 로 rawSn=26 승인분 통지 미발행(fallback 0행) | `ControlNotifyEventListenerTest` "ReviewApprovedEvent_수신시_sendCompleted_호출" | D-ISSUE-48 |
| TC-NOTIFY-002 | 승인 롤백 시 통지 미발행 | PARTIAL | [정적] `ControlNotifyEventListener.java:29` `@TransactionalEventListener(AFTER_COMMIT)` — Spring 계약상 롤백 시 미호출 | 롤백 전용 IT 없음(리스너 직접호출 테스트만) | D-ISSUE-48 |
| TC-NOTIFY-003 | **TASK_COMPLETED 페이로드 실카운트** | **FAIL** | [정적] `ControlNotifyService.java:93-103` — `null`(reviewerName)·`0`(totalFrames)·`0`(labeledFrames) **하드코딩** | `TaskPayloadStructureTest` 는 PII 부재만 검증(카운트 미검증) | **self-fill 결함** · D-ISSUE-41 |
| TC-NOTIFY-004 | 전송 실패 시 폴백 적재 | PASS | [정적] `ControlNotifyService.java:50-56` catch → `enqueuePending` + `incrementCompletedFailed` | `ControlNotifyServiceTest` "TASK_COMPLETED_실패시_폴백큐_적재됨" | |
| TC-NOTIFY-005 | 성공 관찰행 적재 | PASS | [정적] `ControlNotifyService.java:48-49,84-91` → `recordImmediateSuccess` (`STTS=SUCCEEDED`+`SEND_RSLT=SUCCESS`) | `ControlNotifyServiceTest` "즉시_통지_성공시_SEND_RSLT_SUCCESS_행이_적재된다" | |
| TC-NOTIFY-006 | 관찰행 적재 실패 시 통지 성공 유지 | PASS | [정적] `ControlNotifyService.java:84-91` try/catch + warn, 예외 삼킴 | `ControlNotifyServiceTest` "관찰_적재_실패해도_통지성공_metrics는_유지된다" | |
| TC-NOTIFY-007 | TASK_MODIFIED 페이로드 — 변경프레임/종류만 | PARTIAL | [정적] `TaskModifiedPayload.java:19-26` + `ControlNotifyService.java:105-115`. PII/토큰/본문 없음은 충족. **단 frameIds·changeTypes 가 분리된 dedup 리스트라 "프레임↔변경종류" 페어링 소실**, 변경 요약 카운트 부재 | `TaskPayloadStructureTest` (PII 부재만) | D-ISSUE-42 |
| TC-NOTIFY-008 | 디바운스 — 동일 rawSn 축적 후 1회 flush | **FAIL** | [정적] `ControlNotifyDebouncer.java:43-64` dedup(Set)·1회 flush 자체는 정상. **그러나 `addFrame`(77-83)이 `ConcurrentHashMap.newKeySet()` 에 `srcSn=null` 을 `add` → NPE** (영상 단위 META_UPDATED 경로 3곳이 null 전달) | `ControlNotifyDebouncerTest` 8케이스 — **null srcSn 케이스 없음** | D-ISSUE-43 |
| TC-NOTIFY-009 | 셧다운 시 잔여 flush | PASS | [정적] `ControlNotifyDebouncer.java:66-72` `@PreDestroy flushAll()` | `ControlNotifyDebouncerTest` "flushAll_모든_윈도우_즉시_flush" | |
| TC-NOTIFY-010 | 만료 스캔 주기(10s fixedDelay) | PASS | [정적] `ControlNotifyDebouncer.java:53-64` `@Scheduled(fixedDelay=10_000)` + cutoff 이하만 flush + `incrementDebounceFlush` | `ControlNotifyDebouncerTest` "flushExpiredWindows_60초_경과_윈도우만_flush" 외 3건 | |
| TC-NOTIFY-011 | changeType 계약값 검증 | PARTIAL | [정적] `ChangeType.java:16-26` 4값 정의, 발행 11곳 전부 상수 사용(매직스트링 0). **단 `LABEL_ADDED` 는 코드베이스 어디서도 발행되지 않는 dead 상수**이며 발행 시점 런타임 검증 없음 | `ChangeType.ALL` 공유 | D-ISSUE-44 |
| TC-NOTIFY-012 | 검수 전 저장 통지 미발행 | PASS | [정적] `LabelService.java:366` `isReviewApproved(rawSn)` 가드 (`LabelService.java:526-531`) | `LabelServiceTaskModifiedGuardTest`, `MetaServiceTaskModifiedGuardTest`, `EvntAnnoServiceTaskModifiedGuardTest` | |
| TC-NOTIFY-013 | 무변경 통지·이력 미발행 | PASS | [정적] `LabelService.java:356-358`(이력) / `366`(통지) 모두 `!changes.isEmpty()` 가드 | `LabelServiceFullReplaceIntegrationTest` | |
| TC-NOTIFY-014 | 다양한 변경경로 TASK_MODIFIED 발행 | **FAIL** | [정적] 발행처 11곳 확인. **영상 단위 3경로가 `srcSn=null` 발행** → `EnvironmentMetaService.java:113`, `EvntAnnoService.java:129`, `EvntAnnoReviewService.java:227` ⇒ 디바운서 NPE 로 통지 유실. 추가로 `LabelService.java:370` 은 추가/삭제도 전부 `LABEL_UPDATED` 로 통일 | `EnvironmentMetaServiceTest`·`EvntAnnoServiceTaskModifiedGuardTest` 등은 **이벤트 발행 여부만** 검증(디바운서 소비 미검증) | D-ISSUE-43·44 |
| TC-NOTIFY-015 | 폴백 idempotency 중복 방지 | PASS | [정적] `ControlNotifyFallbackService.java:91-95` `DataIntegrityViolationException` 삼킴 + `IDMP_KEY unique`(`LsControlNotifyFallback.java:55`) | `ControlNotifyFallbackServiceTest` "enqueuePending_중복_idempotencyKey_멱등_처리" | |
| TC-NOTIFY-016 | 폴백 큐 깊이 상한(DoS) | PASS | [정적] `ControlNotifyFallbackService.java:36,74-82` `MAX_QUEUE_DEPTH=10000` + `IllegalStateException` + `queue.full` metric | "enqueuePending_큐_깊이_초과시_예외" | CWE-770 대응 확인 |
| TC-NOTIFY-017 | 폴백 enabled=false 게이트 | PASS | [정적] `ControlNotifyFallbackService.java:71-73,113-115` → `Optional.empty()` | "enqueuePending_enabled_false시_empty_반환", "recordImmediateSuccess_disabled시_empty_반환" | |
| TC-NOTIFY-018 | 재시도 claim 원자 CAS | PASS | [정적] `ControlNotifyFallbackService.java:136-143` + `LsControlNotifyFallbackRepository.java:45-48` `UPDATE ... WHERE STTS_CD='PENDING'`, `updated!=1 → empty` | "claimForRetry_CAS_실패시_empty" | |
| TC-NOTIFY-019 | 재시도 백오프 스케줄 | PASS | [정적] `LsControlNotifyFallback.java:190-192` `min(60, 1<<min(rtryCnt,6))` 분, PENDING 복귀 | "failAndSchedule_백오프_2의n승분_cap_60분_검증" | javadoc(172행) 예시 `1,2,4,8,16` vs 실제 `2,4,8,16,32` — 주석 드리프트 |
| TC-NOTIFY-020 | dead-letter 전이 | PASS | [정적] `LsControlNotifyFallback.java:183-189` `rtryCnt > maxRtryCnt(5)` → `DEAD_LETTER`+`dlqDt`, `nextRtryDt=null` | "failAndSchedule_maxRetry_초과시_DEAD_LETTER" | |
| TC-NOTIFY-021 | 재시도 잡 — due 항목 처리 | PASS | [정적] `ControlNotifyFallbackRetryJob.java:48-71` `BATCH_SIZE=20`, claim→processOne→markSucceeded | "runOnce_pending_항목_처리_성공", "runOnce_claimForRetry_실패시_skip" | |
| TC-NOTIFY-022 | 재시도 잡 — 재실패 재스케줄 | PASS | [정적] `ControlNotifyFallbackRetryJob.java:62-65` catch → `markFailedAndSchedule` | "runOnce_processOne_실패시_markFailedAndSchedule" | |
| TC-NOTIFY-023 | 에러 메시지 sanitize | PASS | [정적] `LsControlNotifyFallback.java:228-235` 제어문자→공백, `(?i)(authorization\|token\|bearer\s+)`→`***`, `https?://`→`URL_REDACTED`, 1900 truncate | `LsControlNotifyFallbackTest` sanitize 5케이스 | CWE-117/209 |
| TC-NOTIFY-024 | 재시도 성공 관찰행 멱등 | PASS | [정적] `ControlNotifyFallbackService.java:124-128` UK 충돌 삼킴 | "recordImmediateSuccess_중복키_충돌시_멱등_처리" | |
| TC-NOTIFY-025 | ConditionalOnProperty 비활성 시 빈 미등록 | PASS | **[실동작]** `CONTROL_NOTIFY_ENABLED=false` 상태에서 `/v1/tasks/**` 3EP 전부 **404**(핸들러 부재) + fallback 큐 0행 ⇒ Service/Debouncer/Listener/Controller 미생성 확인 | `ControlNotifyClientConditionalTest` | 유일하게 실동작으로 확정된 D-6 케이스 |

## D-7. 관제 조회 API (TC-NOTIFY, 조회)

> UNCERTAINTIES #4 확정에 따라 `TaskQueryService` 본인배정 검증 부재는 IDOR 로 판정하지 않음.

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-NOTIFY-026 | 요약 조회 — REVIEWER/WORKER | PARTIAL | [정적] `TaskQueryService.java:48-87` 프레임/라벨/메타 카운트+상태+최종수정일. [실동작] enabled=false 로 EP 404 — 실호출 불가 | `TaskQueryServiceTest` "getSummary_정상_라벨_메타_카운트_정확" 외 2 · `TaskQueryControllerTest` "영상별_요약_조회_정상_200" | D-ISSUE-45(페이징 부재)·48 |
| TC-NOTIFY-027 | 라벨 조회 — 파일경로 미포함(Privacy) | PASS | [정적] `TaskLabelsResponse.LabelItem` = `(lblSn, lblTypeCd, labelNm, pointCn)` 뿐 — `TaskQueryService.java:155-166` record 구조로 경로 필드 원천 차단 | `TaskQueryControllerTest` "응답에_원본_이미지_경로_미포함" | CWE-359 대응 확인 |
| TC-NOTIFY-028 | 라벨 조회 — frameIds 필터 | PASS | [정적] `TaskQueryService.java:101-106` `HashSet` contains 필터, null/empty 시 전체 | "getLabels_frameIds_필터_적용", "getLabels_빈_frameIds_전체_반환", `TaskQueryControllerTest` "특정_프레임_라벨_필터_조회" | 필터 자체는 정상. 상한 부재는 D-ISSUE-45 |
| TC-NOTIFY-029 | 메타 조회 | PASS | [정적] `TaskQueryService.java:128-142` `META_SN/KEY/VL` 3필드 매핑 | "getMeta_정상_메타_목록_반환", "getMeta_메타_없는_영상_빈_목록" | |
| TC-NOTIFY-030 | 조회 — 영상 미존재 | PASS | [정적] `TaskQueryService.java:146-149` `findRawOrThrow` → `ErrorCode.NOT_FOUND` (summary/labels/meta 3경로 공통 진입) | "getSummary/getLabels/getMeta_미존재_rawSn_NOT_FOUND_예외" ×3, `TaskQueryControllerTest` "미존재_영상_조회시_404" | |
| TC-NOTIFY-031 | 조회 — 미인증/권한없음 | PARTIAL | [정적] `TaskQueryController.java:51,67,85` `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` → PORTAL 배제. [실동작] 미인증 요청이 **401 아닌 404**(빈 미등록 때문) | `TaskQueryControllerTest` "미인증_조회시_401"(standalone MockMvc). **PORTAL 403 케이스 테스트 없음** | D-ISSUE-48 |

## D-8. 데이터마트 View (TC-MARTVIEW)

> 전 케이스 **실제 `SELECT` 실행**으로 검증. 대상 DB: `klid_system` / 스키마 `public` / 뷰 4종 존재.

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-MARTVIEW-001 | V_COMPLETED_VIDEO — APPROVED 게이트 | PASS | **[실동작]** `LS_RAW_DATA_STATUS` 17행 중 APPROVED 5건(13,14,17,19,26) → 뷰 정확히 5행. **PENDING(4)·ASSIGNED(7,9,11,12,27,28)·REJECTED(8)·FAILED(15,16,23,24) 전부 미노출**. 특히 raw 4 는 `ACTIVE_YN='Y'` 메타 + export 행까지 있는데도 제외됨 | `DatamartViewRebuildIT` "재검수중_PENDING이면_미노출"/"재승인시_다시_노출" | 게이트 실효 확인 |
| TC-MARTVIEW-002 | export 경로/프레임수 | PASS | **[실동작]** 14→`/app/storage/labeling/14/v1`,24 / 17→`.../17/v1`,24 / **26→`/app/storage/labeling/26/v1`,32**. [정적] `V114:86-93` `LEFT JOIN LATERAL ... ORDER BY EXPORT_VER_NO DESC LIMIT 1` | `DatamartViewSlimIT` "EXPORT_PATH_NM이_노출되고_APPROVED영상당_1row다" | |
| TC-MARTVIEW-003 | 미export null·1row | PASS | **[실동작]** 13·19 → `export_path_nm`/`frame_cnt` 둘 다 **null**, 각 1행. 뷰 총 5행 = APPROVED 5건 ⇒ 조인 행증식 0 | `DatamartViewSlimIT` "미export_영상은_null이어도_노출된다" | 다버전 export 데이터가 없어 LATERAL LIMIT 1 의 "다버전→1행"은 실측 미확인(현재 전 영상 ver=1) |
| TC-MARTVIEW-004 | PARTIAL/PENDING export 제외 | PASS | **[실동작]** 13(PARTIAL,frame_cnt=11)·19(PARTIAL,12) → 뷰에서 null. 4(FAILED) 는 APPROVED 게이트로 선차단. [정적] `V114:90` `EXPORT_STTS_CD='SUCCEEDED'` | 〃 | |
| TC-MARTVIEW-005 | ACTIVE_YN 스냅샷 게이트 | PARTIAL | [정적] `V114:94` `WHERE m.ACTIVE_YN='Y'`. **[실동작] `LS_DATASET_VIDEO_META` 6행 전부 `ACTIVE_YN='Y'` — 반증 데이터 0건이라 필터 실효 미확인** | `DatamartViewRebuildIT` "V_COMPLETED_VIDEO_ACTIVE_Y만_노출" GREEN | D-ISSUE-49 |
| TC-MARTVIEW-006 | V_COMPLETED_FRAME — 원본/비식별 경로 분리 | PARTIAL | **[실동작]** rawSn=26: 16프레임 전부 `raw/frames/raw/26/*` ≠ `deidentified/frames/deid/26/*` (TC 전제 충족). **그러나 같은 뷰에서 rawSn=19 는 12프레임 전부 ORIGINAL == DEIDENTIFIED(`/app/storage/raw/resolution/19/frames/frame-N.jpg`), rawSn=13 은 DEIDENTIFIED 11건 NULL** | — | D-ISSUE-46 (privacy) |
| TC-MARTVIEW-007 | DESCRIPTION 노출 | PASS | **[실동작]** 뷰 마지막 컬럼 `description varchar(1000)` 존재. `LS_DATA_SRC.FRM_EXPLN` 비-null 2건 중 APPROVED 영상분 1건이 뷰에 노출 | — | |
| TC-MARTVIEW-008 | V_COMPLETED_LABEL_CHANGE — 변경점만, 본문 미노출 | **FAIL** | **[실동작]** `CHG_DTL_CN` 이 **라벨 본문 전량 노출**: `[{"lblSn":83,"kind":"DELETED","labelName":"car","before":{"lblTypeCd":"BBOX","labelId":2,"labelNm":"car","pointCn":"[[685.92,1222.01],[789.91,1313.21]]"},...}]` — 좌표·속성이 before/after 로 그대로 실림. [정적] `V115:58-76` | `DatamartViewSlimIT` "APPROVED영상의_저장이벤트를_반환한다"(본문 미노출 assert 없음) | **근거 드리프트**: TC 근거 `V114:105-121 / CHG_KIND_CD` → 실제는 **V115 재정의** (`ADD_CNT/MDFCN_CNT/DEL_CNT/CHG_DTL_CN`). D-ISSUE-47 |
| TC-MARTVIEW-009 | LABEL_CHANGE — APPROVED 게이트 | PASS | **[실동작]** `LS_DATA_LBL_HSTRY` 이력 중 raw 4(PENDING) 17건은 뷰에서 **전부 제외**, APPROVED 인 13(3건)·14(1건)만 노출. [정적] `V115:71-76` `EXISTS ... DATA_STTS_CD='APPROVED'` | `DatamartViewSlimIT` "비APPROVED영상의_저장이벤트는_안나온다" | |
| TC-MARTVIEW-010 | V_COMPLETED_META — APPROVED만 | PASS | **[실동작]** `LS_DATA_META_REVIEW` 전 1행 `APPROVED` → 뷰 1행(raw 17, `manual-timeseries`). [정적] `V101:103,109-110` `RVW_STTS_CD='APPROVED'` + `EXISTS` 영상상태 APPROVED 이중 게이트 | `DatamartViewRebuildIT` | |
| TC-MARTVIEW-011 | V_COMPLETED_META — video.* 제외 | PARTIAL | [정적] `V101:104` `META_KEY NOT LIKE 'video.%'`. **[실동작] `LS_DATA_META` 에 `video.*` 키 0건 — 반증 데이터 없어 필터 실효 미확인** | `DatamartViewRebuildIT` "video기술메타_제외_VLM시계열만" GREEN | D-ISSUE-49 |
| TC-MARTVIEW-012 | 라벨 내용 뷰 제거 확인 | PASS | **[실동작]** `\dv public.*` → `v_completed_frame`/`v_completed_label_change`/`v_completed_meta`/`v_completed_video` **4종만**. `V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR` 부재. [정적] `V114:30-31` DROP | `DatamartViewSlimIT` "LABEL과_LABEL_ATTR_뷰가_제거되어_존재하지_않는다" | |
| TC-MARTVIEW-013 | 뷰 멱등성(CREATE OR REPLACE) | PASS | [정적] `V114:42`(VIDEO)·`V115:58`(LABEL_CHANGE)·`V101`(META)·`V104`(FRAME) 모두 `CREATE OR REPLACE VIEW`. `V115:26` 의 선행 `DROP VIEW IF EXISTS` 는 컬럼 타입 교체를 위한 의도적 재정의(IF EXISTS 라 재실행 안전). [실동작] 뷰 4종 컬럼 순서/타입 정상 | `DatamartViewRebuildIT` "기존_출력컬럼_전부_보존"/"신규_메타컬럼_노출" | |
| TC-MARTVIEW-014 | 승인→materialize→rollback 정합 | PARTIAL | [정적] `DatasetMaterializeApproveRollbackIT` 는 **"materialize 실패 시 approve 전체 롤백"** 1케이스만 보유 — TC 가 요구하는 *승인 후 롤백 시 뷰·스냅샷 정합* 시나리오는 미커버 | `DatasetMaterializeApproveRollbackIT` (1 DisplayName) | D-ISSUE-50 |

---

## 이슈 상세 (FAIL / PARTIAL 전건)

### [D-ISSUE-41] TC-NOTIFY-003 — TASK_COMPLETED 페이로드가 0/null 하드코딩(self-fill)
- **심각도**: CRITICAL
- **기대 동작(기대효과)**: `TASK_COMPLETED` 는 관제서버가 조회 API 를 호출하기 전 1차 판단에 쓰는 메타 통지다. CLAUDE.md 계약 = 이벤트타입 + 작업ID + **영상 메타(파일명·길이·채널)** + 검수 완료 일시 + **프레임 개수** + **결과 요약 카운트(라벨 N건·메타 M건)** + 요청 ID. UNCERTAINTIES #3 확정에 따라 `totalFrames`/`labeledFrames`/`reviewerName` 은 **실카운트**여야 한다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/controlnotify/service/ControlNotifyService.java:93-103`
  ```java
  return new TaskCompletedPayload(
          "TASK_COMPLETED",
          event.rawSn(),
          null,  // reviewerName 은 추후 lookup 가능하나 현 단계에서는 미포함
          event.approvedAt(),
          0,     // totalFrames 는 추후 lookup
          0,     // labeledFrames 는 추후 lookup
          UUID.randomUUID().toString());
  ```
  - 외부/DB 조회 없이 **상수를 채워 전송**한다 = self-fill 의 전형. `ReviewApprovedEvent`(rawSn/reviewerNo/approvedAt)에 카운트가 없고, 서비스가 어떤 repository 도 주입받지 않는다(`ControlNotifyService.java:31-33`).
  - 추가로 `TaskCompletedPayload.java:19-27` 에 **영상 메타(파일명·길이·채널) 필드 자체가 없고**, 결과 요약 카운트(라벨/메타 건수)도 없다 — 계약의 절반이 미구현.
  - `reviewerName` 은 `ReviewApprovedEvent.reviewerNo` 가 있음에도 매핑하지 않는다.
  - 즉 관제서버는 모든 완료 통지를 "프레임 0개·라벨 0개·검수자 미상"으로 수신한다.
- **재현/확인 경로**:
  ```bash
  # 1) 통지 활성화 (설정만, 코드 수정 불요)
  #    .env: CONTROL_NOTIFY_ENABLED=true / CONTROL_NOTIFY_URL=http://klid-mock-server:9400
  # 2) 영상 승인 후 적재된 페이로드 확인
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select evnt_type_cd, payload_cn from ls_control_notify_fallback where evnt_type_cd='TASK_COMPLETED';"
  # → "totalFrames":0,"labeledFrames":0,"reviewerName":null 확인
  # 실카운트 대조(rawSn=26): 프레임 16, export frame_cnt 32
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_data_src where raw_sn=26;"
  ```
- **영향**: 관제 연동 계약(CLAUDE.md "TASK_COMPLETED 페이로드 — 메타만") 미이행. 관제가 통지만으로 완료 규모를 판단할 수 없어 매 건 조회 API 왕복을 강제하며, 0 값이 "라벨 0건 완료"로 오해되어 수신측 데이터마트 UPSERT 를 오염시킬 수 있다.
- **수정 방향(제안)**: `ReviewApprovedEvent` 에 카운트/영상메타를 실어 보내거나(승인 트랜잭션에서 이미 집계됨), `ControlNotifyService` 에 `LsDataSrcRepository`/`LsDataLblRepository`/`LsDatasetVideoMeta` 조회를 주입해 payload 빌드 시점에 실카운트를 채운다. 조회 실패 시 0 으로 대체하지 말고 통지를 폴백 큐로 보낼 것(fail-closed).

### [D-ISSUE-42] TC-NOTIFY-007 — TASK_MODIFIED 가 프레임↔변경종류 페어링을 잃고 요약 카운트도 없음
- **심각도**: HIGH
- **기대 동작(기대효과)**: CLAUDE.md — "**변경 프레임 목록**(각 항목: 프레임 ID `SRC_SN` + 변경 종류 `LABEL_ADDED|LABEL_UPDATED|LABEL_DELETED|META_UPDATED`) + **변경 요약 카운트** + 요청 ID".
- **현재 동작(이슈 내용)**: `controlnotify/dto/TaskModifiedPayload.java:19-26` 이 `List<Long> frameIds` 와 `List<String> changeTypes` 를 **평행한 별개 리스트**로 보유. `ControlNotifyDebouncer.java:77-78` 이 각각 `Set` 으로 dedup 하므로 "프레임 A 는 라벨수정, 프레임 B 는 메타수정" 정보가 `frameIds=[A,B], changeTypes=[LABEL_UPDATED, META_UPDATED]` 로 뭉개져 **어느 프레임이 어떤 변경인지 복원 불가**. 변경 요약 카운트 필드도 없다.
- **재현/확인 경로**: 활성화 후 서로 다른 changeType 을 같은 rawSn 의 다른 프레임에 발생시킨 뒤
  `select payload_cn from ls_control_notify_fallback where evnt_type_cd='TASK_MODIFIED';`
- **영향**: 관제 연동 계약 위반. 관제가 변경 종류별 차등 처리를 할 수 없어 통지 수신 시 항상 전량 재조회해야 한다(통지의 요약 가치 소멸).
- **수정 방향(제안)**: `List<FrameChange(srcSn, changeType)>` 형태로 페이로드를 바꾸고 `DebouncedWindow` 를 `Map<Long, Set<String>>` 으로 축적. 요약 카운트(`changedFrameCount`, 종류별 건수)를 별도 필드로 추가. 관제팀 계약 협의 필요.

### [D-ISSUE-43] TC-NOTIFY-008 / TC-NOTIFY-014 — 영상 단위(srcSn=null) 변경 통지가 디바운서 NPE 로 유실
- **심각도**: HIGH
- **기대 동작(기대효과)**: 영상 단위 메타 수정(촬영환경 메타, event_annotation 등)도 `TASK_MODIFIED` 로 관제에 통지되어야 한다.
- **현재 동작(이슈 내용)**:
  - 발행 측이 `srcSn` 에 **명시적으로 `null`** 을 넣는 경로가 3곳:
    - `dataset/service/EnvironmentMetaService.java:112-113` → `new TaskModifiedEvent(rawSn, null, ChangeType.META_UPDATED, ...)`
    - `evntanno/service/EvntAnnoService.java:128-129` → 동일
    - `evntanno/service/EvntAnnoReviewService.java:226-227` → 동일
  - 소비 측 `controlnotify/service/ControlNotifyDebouncer.java:77-83`:
    ```java
    private final Set<Long> frameIds = ConcurrentHashMap.newKeySet();
    void addFrame(Long srcSn, String changeType) { frameIds.add(srcSn); ... }
    ```
    `ConcurrentHashMap.KeySetView.add(null)` 은 **NullPointerException** 을 던진다(널 키 불허). null 가드 없음.
  - 호출 경로가 `@TransactionalEventListener(AFTER_COMMIT)`(`ControlNotifyEventListener.java:34-37`) 이므로 예외는 Spring 이 삼키고 **통지가 조용히 사라진다**(폴백 큐에도 안 들어감 — 폴백은 전송 실패에만 동작).
  - 부수적으로 `flushExpiredWindows` 의 `windows.remove(rawSn)` → `window.getFrameIds()` 사이에 들어온 `accumulate` 는 유실되는 좁은 경합 구간도 존재(LOW).
- **재현/확인 경로**: 활성화 후 APPROVED 영상의 촬영환경 메타를 수정
  ```bash
  curl -X PUT http://localhost:18081/v1/videos/26/environment-meta -H "Authorization: Bearer <REVIEWER>" \
       -H 'Content-Type: application/json' -d '{"weather":"CLEAR"}'
  docker logs klid-backend 2>&1 | grep -i "NullPointer\|TaskModified"
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select * from ls_control_notify_fallback;"   # → 행 없음(유실)
  ```
- **영향**: 검수 완료 후 영상 단위 메타 수정이 관제에 **전혀 통지되지 않음** → 데이터마트 정합 깨짐. 단위 테스트가 srcSn=null 을 다루지 않아 BE 3013 GREEN 에도 잡히지 않는다.
- **수정 방향(제안)**: `DebouncedWindow.addFrame` 에서 `srcSn != null` 일 때만 `frameIds.add`, `changeTypes` 는 항상 축적. 영상 단위 변경임을 나타내는 별도 플래그(또는 D-ISSUE-42 의 `FrameChange` 에서 `srcSn` nullable 허용)를 페이로드에 반영. `ControlNotifyDebouncerTest` 에 null srcSn 회귀 케이스 추가.

### [D-ISSUE-44] TC-NOTIFY-011 / TC-NOTIFY-014 — `LABEL_ADDED` 는 발행되지 않는 dead 계약값
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `changeType` 4값이 실제 변경 성격에 맞게 발행되어 관제가 종류별로 판단할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 발행처 11곳 전수 조사 결과 실제 사용값은 `META_UPDATED`(7곳)·`LABEL_UPDATED`(3곳)·`LABEL_DELETED`(1곳, `label/service/DeidentReportService.java:147`)뿐이며 **`LABEL_ADDED` 는 어디서도 발행되지 않는다**. `label/service/LabelService.java:367-370` 은 주석대로 추가/수정/삭제를 모두 `LABEL_UPDATED` 하나로 통일한다(의도된 설계 결정이나 계약값 4종의 의미가 붕괴). 또한 발행 시점에 `ChangeType.ALL` 화이트리스트 검증은 수행되지 않는다(상수 사용 관례에만 의존).
- **재현/확인 경로**:
  ```bash
  grep -rn "new TaskModifiedEvent(" -A5 backend/src/main/java --include=*.java | grep -c "ChangeType.LABEL_ADDED"   # → 0
  ```
- **영향**: 관제가 `LABEL_ADDED` 를 기대해 분기 로직을 구현하면 영원히 도달하지 않는 죽은 경로가 된다. 계약 문서(CLAUDE.md)와 실제 발행 집합 불일치.
- **수정 방향(제안)**: ① `LabelService.bulkUpsert` 에서 `changes` 의 kind 분포로 ADDED/UPDATED/DELETED 를 구분 발행하거나, ② 계약값을 실제 발행 집합(3종)으로 축소하고 CLAUDE.md·관제 계약을 정정. 어느 쪽이든 `ChangeType.ALL` 로 발행 시점 assert 를 추가.

### [D-ISSUE-45] TC-NOTIFY-026 / TC-NOTIFY-028 — 관제 조회 API 라벨/프레임 조회에 페이징·상한 부재
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/api-design.md` — "목록 조회는 반드시 페이징 적용. 전체 조회(페이징 없는 findAll) 금지".
- **현재 동작(이슈 내용)**: `controlnotify/controller/TaskQueryController.java:66-73` 의 `getLabels` 는 `Pageable` 없이 `List<TaskLabelsResponse>` 전량 반환. `controlnotify/service/TaskQueryService.java:98,113-115` 가 영상의 **전 프레임 + 전 라벨을 한 번에 메모리 적재**한다. `frameIds` 파라미터에도 `@Size` 상한이 없어(`TaskQueryController.java:71`) 임의 길이 리스트를 받는다. `getSummary` 역시 전 프레임·전 라벨을 로드해 카운트한다(`TaskQueryService.java:54-67`).
- **재현/확인 경로**: 활성화 후 `curl "http://localhost:18081/v1/tasks/26/labels" -H "Authorization: Bearer <REVIEWER>"` → 16프레임 전량 반환(대용량 영상에서는 수천 프레임).
- **영향**: CWE-770 (Unrestricted Resource Consumption) / OWASP API4:2023. 인증된 계정 하나로 대용량 영상을 반복 조회해 BE 힙·대역폭을 고갈시킬 수 있다.
- **수정 방향(제안)**: `getLabels` 에 `Pageable`(기본 20, 최대 100) 적용, `frameIds` 에 `@Size(max=...)` 부여, `getSummary` 는 count 쿼리(`countDistinct`)로 대체해 전량 적재 제거.

### [D-ISSUE-46] TC-MARTVIEW-006 — V_COMPLETED_FRAME 이 원본 경로를 비식별 경로로 노출(파생영상)
- **심각도**: HIGH
- **기대 동작(기대효과)**: CLAUDE.md — "신규 추출은 `{base}/frames/raw|deid/{rawSn}` 로 분기 저장돼 두 경로가 항상 상이(원본 덮어쓰기 0)". 관제가 `DEIDENTIFIED_PATH` 로 픽업한 파일은 반드시 비식별본이어야 한다.
- **현재 동작(이슈 내용)** — [실동작]:
  ```
  select raw_sn, count(*) tot,
         count(*) filter (where original_path = deidentified_path) same,
         count(*) filter (where deidentified_path is null) deidnull
  from v_completed_frame group by 1;

   raw_sn | tot | same | deidnull
   -------+-----+------+---------
       13 |  11 |    0 |      11      ← 비식별 경로 전량 NULL
       14 |  12 |    0 |       0
       17 |  12 |    0 |       0
       19 |  12 |   12 |       0      ← 원본 == 비식별 (12/12)
       26 |  16 |    0 |       0      ← 참조 데이터는 정상 분리
  ```
  rawSn=19 실값: `original_path = deidentified_path = /app/storage/raw/resolution/19/frames/frame-0.jpg` — **해상도 파생영상 경로가 `raw/` 아래 단일 경로**로 기록돼 있고 뷰가 그대로 두 컬럼에 복제한다. rawSn=26 등 정상 추출본은 `raw/frames/raw/26/` vs `deidentified/frames/deid/26/` 로 분리되어 있어 대비된다.
- **재현/확인 경로**: 위 SQL 그대로 (`docker exec klid-postgres psql -U klid_user -d klid_system -c "..."`).
- **영향**: 데이터마트로 반출되는 "비식별 경로"가 실제로는 비식별 미적용 프레임을 가리킨다 = 개인정보 노출 경로(CWE-359 / 비식별 정책 위반). rawSn=13 은 반대로 비식별 경로 NULL 이라 관제 픽업이 결측된다. 뷰 자체에 불변식(원본≠비식별, 비식별 NOT NULL) 가드가 없어 파생영상 생성 경로의 결함이 그대로 마트까지 전파된다.
- **수정 방향(제안)**: ① 해상도 파생영상(`ls_data_src` 생성 경로)이 raw/deid 를 분기 저장하도록 정정, ② 뷰에 `DEIDENTIFIED_PATH IS NOT NULL AND DEIDENTIFIED_PATH <> ORIGINAL_PATH` 게이트를 걸어 fail-closed 로 노출 차단, ③ 기존 19/13 데이터 백필. (본 검증 범위상 rawSn=19/13 상태는 변경하지 않았음)

### [D-ISSUE-47] TC-MARTVIEW-008 — V_COMPLETED_LABEL_CHANGE 가 라벨 본문(좌표)을 그대로 노출
- **심각도**: HIGH
- **기대 동작(기대효과)**: CLAUDE.md V114 정책 — "라벨 좌표·속성 **본문은 검수 승인 export 폴더 JSON 에 존재하므로 뷰로 중복 노출하지 않음**(구 `V_COMPLETED_LABEL`·`V_COMPLETED_LABEL_ATTR` 제거)". 변경점 뷰는 *변경이 있었다는 사실*만 전달해야 한다.
- **현재 동작(이슈 내용)** — [실동작] `select lbl_hstry_sn, raw_sn, left(chg_dtl_cn,600) from v_completed_label_change;`
  ```json
  [{"lblSn":83,"kind":"DELETED","labelName":"car",
    "before":{"lblTypeCd":"BBOX","labelId":2,"labelNm":"car",
              "pointCn":"[[685.9210689525468,1222.0134712939594],[789.9154049892563,1313.2119858975082]]"},
    "after":null}, ...]
  ```
  `V115__restructure_lbl_hstry_save_event.sql:58-66` 이 `h.CHG_DTL_CN` (= `List<LabelChange>` diff JSON, before/after 전체 라벨 스냅샷)을 뷰 컬럼으로 그대로 노출한다. `V_COMPLETED_LABEL` 을 제거한 목적(라벨 본문 뷰 중복 제거)이 무력화됐다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select lbl_hstry_sn, raw_sn, left(chg_dtl_cn,400) from v_completed_label_change limit 3;"
  ```
- **영향**: 관제 연동 계약(라벨 본문 비노출) 위반 + 라벨 데이터가 export JSON 과 뷰 두 곳에 존재해 **동기화 시점 차이로 불일치**할 수 있다(V114 슬림화가 해결하려던 문제의 재발).
- **부수 발견 — 근거 드리프트**: TC-MARTVIEW-008 의 근거는 `V114:105-121` + 기대값 `CHG_KIND_CD` 이나, **V115 가 해당 뷰를 DROP 후 재정의**(`V115:26,58`)하여 실제 출력 컬럼은 `LBL_HSTRY_SN/RAW_SN/SRC_SN/ADD_CNT/MDFCN_CNT/DEL_CNT/CHG_DTL_CN/REG_ID/REG_DT` 다. 테스트케이스 근거를 V115 로 갱신 필요.
- **수정 방향(제안)**: 뷰에서 `CHG_DTL_CN` 컬럼을 제거하고 건수 3종(`ADD_CNT/MDFCN_CNT/DEL_CNT`)만 노출하거나, 좌표·속성을 제거한 축약 diff(예: `lblSn`+`kind`+`labelName`)를 별도 생성 컬럼으로 파생. 관제팀 계약 협의 대상.

### [D-ISSUE-48] TC-NOTIFY-001·002 / TC-NOTIFY-026·031 — 통지·조회 기능이 런타임 비활성이라 실동작 미검증
- **심각도**: MEDIUM (검증 커버리지 갭 — 코드 결함 아님)
- **기대 동작(기대효과)**: 로컬 풀스택 검증 회차에서 관제 통지/조회가 목업서버(:9400) 상대로 실제 왕복하며 발행 시점·페이로드·인가가 실측되어야 한다.
- **현재 동작(이슈 내용)**: `.env:41` `CONTROL_NOTIFY_ENABLED=false` → `@ConditionalOnProperty` 빈 7종 미등록.
  - `GET /v1/tasks/26/summary|labels|meta` (미인증) → **404 ×3** (기대 401/403 을 실측 불가)
  - `ls_control_notify_fallback` **0 rows** — rawSn=26 은 APPROVED·export SUCCEEDED 인데 통지 관찰행이 없다 ⇒ **TASK_COMPLETED 미발행 확정**
  - 목업서버 `mock-server/` 에 관제 inbound SPI(`POST /api/v1/notify`) 스텁이 있는지도 별도 확인 필요(현 `CONTROL_NOTIFY_URL` 기본값은 `http://localhost:8090` 으로 목업서버 :9400 을 가리키지 않음, `application.yml:200`).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep CONTROL_NOTIFY   # CONTROL_NOTIFY_ENABLED=false
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18081/v1/tasks/26/summary   # 404
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select count(*) from ls_control_notify_fallback;"  # 0
  ```
- **영향**: D-6/D-7 31케이스 중 실동작으로 확정된 것은 TC-NOTIFY-025 하나뿐. AFTER_COMMIT 발행 시점, 실제 페이로드 JSON, 401/403 인가, 디바운스 60s 실 flush, Resilience4j 재시도·서킷 동작이 전부 미실측 상태다(D-ISSUE-41~44 도 정적 근거로만 확정).
- **수정 방향(제안)**: 검증 전용으로 `CONTROL_NOTIFY_ENABLED=true` + `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` 을 설정하고 목업서버에 `POST /api/v1/notify` 수신 스텁(요청 본문 기록)을 두어 2차 회차에서 페이로드 실측을 수행. **rawSn=26 은 E 클러스터 참조 데이터이므로 별도 신규 영상으로 재현할 것.**

### [D-ISSUE-49] TC-MARTVIEW-005 / TC-MARTVIEW-011 — 필터 반증 데이터 부재로 실효성 미확인
- **심각도**: LOW (검증 커버리지 갭)
- **기대 동작(기대효과)**: `ACTIVE_YN='N'` 스냅샷과 `video.*` 기술메타가 각각 뷰에서 제외되는 것을 **실제 반례로** 확인해야 한다.
- **현재 동작(이슈 내용)**: [실동작] `select active_yn, count(*) from ls_dataset_video_meta group by 1;` → `Y | 6` (N 행 0건). `select meta_key, count(*) from ls_data_meta group by 1;` → `manual-timeseries | 1` (video.* 0건). 필터 절 자체는 정적으로 존재(`V114:94`, `V101:104`)하고 `DatamartViewRebuildIT` 가 Testcontainers 에서 GREEN 이지만, 현 로컬 DB 로는 필터를 통과시켜 보는 반증이 불가능하다.
- **재현/확인 경로**: 위 두 SQL.
- **영향**: 뷰 게이트의 회귀 여부를 운영 데이터로 감지할 수 없다(테스트 픽스처에만 의존).
- **수정 방향(제안)**: 검증용 시드에 `ACTIVE_YN='N'` 스냅샷 1건 + `META_KEY='video.codec'` 메타 1건을 추가해 회차마다 반례 SELECT 가 0행임을 확인.

### [D-ISSUE-50] TC-MARTVIEW-014 — 승인→materialize→롤백 후 뷰·스냅샷 정합 시나리오 미커버
- **심각도**: LOW
- **기대 동작(기대효과)**: 승인 후 롤백(버전 되돌림)이 일어났을 때 `V_COMPLETED_*` 4뷰와 `LS_DATASET_VIDEO_META` 스냅샷이 정합 상태를 유지해야 한다.
- **현재 동작(이슈 내용)**: `backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java` 는 DisplayName 1건 — "materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영" 뿐. 이는 *승인 트랜잭션 롤백* 검증이며, TC 가 요구하는 *승인 완료 후 버전 롤백 시 뷰 정합* 은 검증하지 않는다.
- **재현/확인 경로**: `grep -n "DisplayName" backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java`
- **영향**: 롤백 후 뷰가 구버전 라벨/신버전 export 를 섞어 노출하는 회귀를 감지하지 못한다.
- **수정 방향(제안)**: 승인 → materialize → `VersionService.rollback` → 4뷰 SELECT 정합 검증 IT 를 추가.

---

## 요약

- 총 **45**건 / PASS **31** / FAIL **4** / PARTIAL **10** / BLOCKED 0 / N/A 0 / 확인필요 0
  - D-6 (25건): PASS 18 · FAIL 3 (003·008·014) · PARTIAL 4 (001·002·007·011)
  - D-7 (6건): PASS 4 · PARTIAL 2 (026·031)
  - D-8 (14건): PASS 9 · FAIL 1 (MARTVIEW-008) · PARTIAL 4 (005·006·011·014)
- 근거 라인 드리프트: **2건**
  - TC-MARTVIEW-008 근거 `V114:105-121` + 기대 컬럼 `CHG_KIND_CD` → 실제는 **V115 재정의**(`ADD_CNT/MDFCN_CNT/DEL_CNT/CHG_DTL_CN`)
  - `LsControlNotifyFallback.java:172` javadoc 백오프 예시 `1,2,4,8,16` vs 실제 코드 `2,4,8,16,32`(190행)
- **self-fill 결함: 1건** — D-ISSUE-41 (`TASK_COMPLETED` totalFrames/labeledFrames=0, reviewerName=null 하드코딩)
- 실동작으로 확정된 건: D-8 전 14케이스(실 SELECT) + TC-NOTIFY-025(404 실측) + D-6/D-7 미발행·404 사실
- **CRITICAL 1 / HIGH 4 / MEDIUM 3 / LOW 2** (D-ISSUE-41~50)
