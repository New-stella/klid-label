# D 클러스터 part1 — 검수 워크플로우 / 상태머신 / 배정 (D-1 ~ D-3, 원문 6~75행)

> 대상: `docs/test-cases/D-review-version-notify.md` 6~75행 (TC-REVIEW-001~034, TC-ASSIGN-001~021) — 총 55 케이스
> 환경: 로컬 풀스택(klid-backend `:18081`, klid-postgres, klid-ai-server, klid-mock-server:9400, klid-frontend). backend V130 HEAD 재빌드본.
> `CONTROL_NOTIFY_ENABLED=false`, `VLM_CLIENT_ENABLED=false` (설정 비활성 — 통지/VLM 미발화는 설계된 동작)
> DB 스키마 = `public`, 접속 `docker exec klid-postgres psql -U klid_user -d klid_system`
> 토큰: `/v1/dev/tokens` — REVIEWER(1001)/REVIEWER2(1002)/WORKER(2001)/WORKER2(2002)/PORTAL(3001)
> **rawSn=26(E 클러스터 참조 데이터)은 읽기 전용으로만 사용** — 프레임목록 조회(GET) 2회만 수행, 상태 변경 없음. 최종 상태 `APPROVED` 유지 확인.

## 실동작 검증에 사용한 영상 (rawSn=26 회피용 스크래치)

| rawSn | 시작 상태 | 용도 | 종료 상태 |
|--:|---|---|---|
| 4 | ASSIGNED | 상태머신 전 사이클(제출/취소/반려/재제출/승인/재검수) + assign 강등 반증 | ASSIGNED |
| 5 | (status 행 없음) | 신규 배정 / 동시 승인 경합(9병렬) | APPROVED |
| 6 | (status 행 없음) | 재배정 / event_annotation 자동승인 | APPROVED |
| 8 | REJECTED | 미허용 전이(REJECTED→APPROVED) 조회성 | REJECTED (불변) |
| 9 | ASSIGNED | 미허용 전이(ASSIGNED→APPROVED/IN_REVIEW) | ASSIGNED (불변) |
| 10 | (status 행 없음) | 동시 배정 PK 경합 / 동시 재배정 경합 | IN_REVIEW |
| 13 | APPROVED | 재배정 차단(TC-ASSIGN-010) 검증 | APPROVED (불변) |

---

## D-1. 검수 워크플로우 (TC-REVIEW)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-REVIEW-001 | 제출 정상(ASSIGNED→PENDING) | PASS | [실동작] `POST /v1/reviews/4/submit` (W1) → 200 `dataSttsCd=PENDING`, `ls_task_event_log` evnt_id=29 SUBMIT actor=2001. 근거라인 `ReviewService.java:343-355` 일치 | `ReviewControllerTest:ReviewController_배정된_WORKER의_submit는_PENDING으로_전이` | — |
| TC-REVIEW-002 | 재제출(REJECTED→PENDING) | PASS | [실동작] rawSn=4 REJECTED 상태에서 submit → 200 PENDING (ver 6→7). `ReviewStateMachine.java:48` 일치 | `ReviewStateMachineTest` 허용전이 | — |
| TC-REVIEW-003 | 재검수 재제출(APPROVED→PENDING) | PASS | [실동작] rawSn=4 APPROVED → submit(W1) 200 PENDING, rawSn 동일(버전업 없음). `ReviewStateMachine.java:50,55` 일치 | `ReviewControllerTest:재검수_APPROVED_영상의_배정WORKER_submit는_200_PENDING_전이` | — |
| TC-REVIEW-004 | 제출 차단 — 배치완료 COMPLETED 점프 방지 | PASS | [정적] `ReviewStateMachine.java:43-51` ALLOWED 맵에 `COMPLETED` 키 없음 → `:59-63` INVALID_INPUT. **실동작 재현 불가**(`LS_RAW_DATA_STATUS.DATA_STTS_CD`에 COMPLETED 를 쓰는 코드 경로 없음 — `transitionTo` 호출자 전수 확인) | 없음(전용 케이스 부재) | 동일 원리의 ASSIGNED/REJECTED 출발 차단은 실동작 확인 |
| TC-REVIEW-005 | 제출 차단 — IN_REVIEW에서 제출 | PASS | [실동작] rawSn=4 IN_REVIEW → submit 400 "허용되지 않은 상태 전이입니다 (IN_REVIEW → PENDING)" | `ReviewStateMachineTest` | — |
| TC-REVIEW-006 | 제출 — 타 WORKER 배정(IDOR) | PASS | [실동작] W2(2002)가 rawSn=4 submit → 403 "본인에게 배정되지 않은 영상입니다". `ReviewService.java:496-509` 일치 | `ReviewControllerTest:미배정_WORKER가_submit_시도시_403_IDOR` | — |
| TC-REVIEW-007 | 제출 — REVIEWER가 submit 호출 | PASS | [실동작] REVIEWER 토큰 submit → 403. 단 응답 메시지는 `@PreAuthorize("hasRole('WORKER')")`(ReviewController.java:151)의 일반 "권한이 없습니다" — 서비스층 "WORKER 권한이 필요합니다"(`ReviewService.java:500-502`)는 HTTP 경로에서 도달 불가(2중 방어) | `ReviewControllerTest:REVIEWER가_cancel_submit_호출시_403` | 상태코드 일치, 메시지 문구만 상이 |
| TC-REVIEW-008 | 제출 — 미인증 | PASS | [실동작] 토큰 없이 submit → 401 UNAUTHORIZED | `ReviewControllerTest:미인증_cancel_submit_호출시_401` | — |
| TC-REVIEW-009 | 검수시작 정상(PENDING→IN_REVIEW) | PASS | [실동작] `POST /v1/reviews/4/start` (REV) → 200 `IN_REVIEW`. `ReviewService.java:393-401` 일치 | `ReviewControllerTest` | — |
| TC-REVIEW-010 | 검수시작 — 비REVIEWER | PASS | [실동작] W1 start → 403 | `ReviewControllerTest` | — |
| TC-REVIEW-011 | 승인 정상(IN_REVIEW→APPROVED) | PASS | [실동작] rawSn=6 approve → 200 APPROVED + 로그 `[Version] approved snapshot rawSn=6` + `[Dataset] materialized rawSn=6 inserted=true` + `[DatasetExportBridge] review approved` (=ReviewApprovedEvent 발행) + `ls_evnt_anno_review.rvw_stts_cd` AUTO_GENERATED→APPROVED. rawSn=26 은 `ls_label_version` 16건(SAVE_REASON=APPROVED). `ReviewService.java:406-447` 일치 | `ReviewServiceEventPublishTest` 5건(이벤트/스냅샷/evntAnno/meta/materialize) | 부가 관찰 → **D-ISSUE-04** |
| TC-REVIEW-012 | 승인 — PENDING에서 직행 차단 | PASS | [실동작] rawSn=4 PENDING → approve 400 "(PENDING → APPROVED)" | `ReviewControllerTest:PENDING에서_APPROVED_직접_전이는_불가` | — |
| TC-REVIEW-013 | 승인 — 이미 APPROVED 재승인 시도 | PASS | [실동작] rawSn=4 APPROVED → approve 409 CONFLICT "이미 APPROVED 된 영상은…". `ReviewStateMachine.java:54-58` 일치 | `ReviewControllerTest:중복_승인_시도시_409_CONFLICT` | — |
| TC-REVIEW-014 | 승인 — 비REVIEWER | PASS | [실동작] W1 approve → 403 | `ReviewControllerTest:WORKER가_approve_호출시_403` | — |
| TC-REVIEW-015 | 승인 시 event_annotation 자동 APPROVED 동결 | PASS | [실동작] rawSn=6 에 `PUT /v1/videos/6/event-annotation` 으로 `evnt_anno_sn=3` 생성(`reviewStatus=AUTO_GENERATED`) → approve 후 `ls_evnt_anno_review` rvw_sn=3 `rvw_stts_cd=APPROVED, rvw_id=1001, rvw_dt=11:21:25.195`(승인 시각과 동일 tx). `ReviewService.java:431-435` 일치 | `ReviewServiceEventPublishTest:event_annotation_자동승인이_materialize_직전에_호출됨` | — |
| TC-REVIEW-016 | 승인 시 시계열 메타 검토행 자동 APPROVED | PASS | [실동작-이력] 신규 메타행 생성 경로가 VLM 콜백뿐(`PUT /v1/frames/{srcSn}/meta`는 기존 행 값 수정만, `MetaUpdateRequest.java:15-17`)이라 이번 세션에서 신규 생성 불가. 대신 동일 스택 DB 실측: rawSn=17 APPROVE 이벤트 `ocrn_dt=2026-07-23 16:33:32.388949` ↔ `ls_data_meta_review(data_raw_sn=17).rvw_dt=16:33:32.440725, rvw_id=1001` — 같은 승인 트랜잭션에서 자동 확정됨을 확인. `ReviewService.java:436-439` 일치 | `ReviewServiceEventPublishTest:시계열메타_검토행_자동확정이_materialize_직전에_호출됨` | 신규 생성 미수행(경로 부재) |
| TC-REVIEW-017 | 승인 — 스냅샷 스킵 발생 시 WARN(승인 성공) | PASS | [정적] `ReviewService.java:424-430` — `commit.hasSkips()` 시 WARN, 라벨 본문/PII 미출력(rawSn/skipped/actor 만). 실동작에서 10MB 초과 프레임 미보유로 스킵 미발생(정상) | `ReviewServiceEventPublishTest:M2_스냅샷_스킵_발생시_WARN_로깅_경로_타고_승인은_정상_성공` / `M2_스냅샷_스킵_없으면_WARN_미발생` | — |
| TC-REVIEW-018 | 반려 정상(IN_REVIEW→REJECTED) | PASS | [실동작] reject(사유) → 200 REJECTED + `ls_data_issue` data_issue_sn=2 (`issue_type_cd=REJECTION`, `issue_stts_cd=RESOLVED`, `reported_user_no=1001`) + `ls_task_event_log` evnt_id=32 REJECT(rsn 포함). `ReviewService.java:452-481` 일치 | `ReviewControllerTest:반려시_LS_DATA_ISSUE_생성_+_상태_REJECTED` | — |
| TC-REVIEW-019 | 반려 — 사유 누락 | PASS | [실동작] `{"reason":""}` → 400 "reason: 반려 사유는 필수입니다."(@Valid) | `ReviewControllerTest:반려_사유_누락시_INVALID_INPUT_400` | — |
| TC-REVIEW-020 | 반려 — 재반려 시 parent 이슈 연결 | PASS | [실동작] 2차 반려 → `ls_data_issue` data_issue_sn=3, `up_data_issue_sn=2` 계층 연결. `ReviewService.java:459-465` 일치 | `IssueThreadRepositoryTest` | — |
| TC-REVIEW-021 | 검수취소 정상(PENDING→ASSIGNED) | PASS | [실동작] rawSn=4 PENDING → cancel-submit(W1) 200 ASSIGNED + `ls_task_event_log` evnt_id=30 CANCEL_SUBMIT (rsn=null, PII 미포함). `ReviewService.java:370-388` 일치 | `ReviewControllerTest:REVIEW_PENDING서_본인WORKER_취소_200_ASSIGNED복귀` | — |
| TC-REVIEW-022 | 검수취소 — IN_REVIEW 차단 | PASS | [실동작] IN_REVIEW → cancel-submit 400 "(IN_REVIEW → ASSIGNED)" | `ReviewControllerTest:IN_REVIEW서_취소시_400` | — |
| TC-REVIEW-023 | 검수취소 — APPROVED 차단 | PASS | [실동작] APPROVED → cancel-submit 409 CONFLICT | `ReviewControllerTest:APPROVED서_취소시_409_CONFLICT` | — |
| TC-REVIEW-024 | 검수취소 — 타 WORKER(IDOR) | PASS | [실동작] W2 cancel-submit → 403 "본인에게 배정되지 않은 영상입니다" | `ReviewControllerTest:타WORKER_취소시_403_IDOR` | — |
| TC-REVIEW-025 | 상세조회 — WORKER 본인 배정만 | PASS | [실동작] `GET /v1/reviews/6` (W1, 본인 배정) → 200. `ReviewService.java:515-532` 일치 | `ReviewControllerTest:본인_배정_WORKER의_GET_reviews_단건은_200` | — |
| TC-REVIEW-026 | 상세조회 — WORKER 타인 배정 차단(IDOR) | PASS | [실동작] W2 → 403 / PORTAL → 403 / 미인증 → 401 (3채널 모두 확인) | `ReviewControllerTest:타인_배정_WORKER의_GET_reviews_단건은_403_IDOR` | 채널 격리 함께 확인 |
| TC-REVIEW-027 | 프레임목록 — 영상 미존재 | PASS | [실동작] `GET /v1/reviews/999999/frames` → 404 "영상을 찾을 수 없습니다". `ReviewService.java:287-288` 일치 | `ReviewFramesControllerTest:videoId_미존재_시_404` | — |
| TC-REVIEW-028 | 프레임목록 — N+1 회피(2쿼리) | PASS | [실동작] rawSn=26 frames 조회 중 SQL 로그 실측: `LS_DATA_RAW`×1(존재확인) + `LS_DATA_SRC`×1 + `LS_DATA_LBL`×1 = 총 3쿼리(프레임/라벨 각 1회 = TC 기대 충족). 응답 16프레임 `frameNo` 0~15 오름차순, 프레임당 라벨 6~10건 | `ReviewFramesControllerTest:프레임_N개_라벨_M개_단일_쿼리_확인_N1_회피` | — |
| TC-REVIEW-029 | 목록 — REVIEWER 전용+status 필터 | PASS | [실동작] `GET /v1/reviews` status별 실측 — 전체 8건(APPROVED 7·REJECTED 1, ASSIGNED/FAILED 제외 = 화이트리스트 `ReviewRepository.java:66-71`), APPROVED 7, REJECTED 1, PENDING/IN_REVIEW 0. WORKER 호출 403 | `ReviewListWhitelistFilterIT` 4건 | — |
| TC-REVIEW-030 | 이슈목록 — REVIEWER 전용 | PASS | [실동작] `GET /v1/reviews/4/issues` → 2건(reg_dt DESC, upDataIssueSn 계층 포함). WORKER 403 | `IssueControllerTest` | — |

## D-2. 검수 상태머신 (TC-REVIEW, 단위)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-REVIEW-031 | 허용 전이 전수 매트릭스 | PASS | [실동작] 7개 전이 **전부 HTTP 실행 확인** — ASSIGNED→PENDING(200), PENDING→IN_REVIEW(200), PENDING→ASSIGNED(200), IN_REVIEW→APPROVED(200), IN_REVIEW→REJECTED(200), REJECTED→PENDING(200), APPROVED→PENDING(200). `ReviewStateMachine.java:43-51` 일치 | `ReviewStateMachineTest` 7건 | — |
| TC-REVIEW-032 | 미허용 전이 전수 | PASS | [실동작] ASSIGNED→APPROVED 400 / ASSIGNED→IN_REVIEW 400 / PENDING→APPROVED 400 / PENDING→PENDING 400 / IN_REVIEW→PENDING 400 / IN_REVIEW→ASSIGNED 400 / REJECTED→APPROVED 400 / REJECTED→IN_REVIEW 400 — 8종 실측 전부 INVALID_INPUT. `ReviewStateMachine.java:59-63` 일치 | `ReviewStateMachineTest` | — |
| TC-REVIEW-033 | APPROVED→IN_REVIEW/REJECTED 직행 | PASS | [실동작] APPROVED 에서 start→409, reject→409, approve→409, cancel-submit→409 (4종 전부 CONFLICT). `ReviewStateMachine.java:54-58` 일치 | `ReviewStateMachineTest:APPROVED에서_PENDING_외_직행은_409_CONFLICT` | — |
| TC-REVIEW-034 | 알 수 없는 from 상태 | PARTIAL | [정적] COMPLETED/PROCESSING → ALLOWED 맵 미등록 → INVALID_INPUT(400) 성립. **from=null 은 400 아님** — `ReviewStateMachine.java:59` `ALLOWED.get(from)` 의 ALLOWED 는 `Map.of(...)`(ImmutableCollections.MapN)이라 `get(null)`이 `probe()`에서 NPE → 500 | 없음(COMPLETED/PROCESSING/null 전용 케이스 부재) | **D-ISSUE-03** |

## D-3. 검수 배정 (TC-ASSIGN)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-ASSIGN-001 | 배정 정상 | PARTIAL | [실동작] `POST /v1/assignments {workerId:2001,rawDataIds:[5],reviewerId:1001}` → 201, `ls_task_assignment` id=24(LABELER)/25(REVIEWER), `ls_raw_data_status(5)=ASSIGNED` 생성, `ls_task_event_log` ASSIGN 기록. `AssignmentService.java:56-102` 일치. **그러나 `markAssigned()`(:75)가 무조건 실행되어 APPROVED 검수완료 영상을 조용히 강등** | `AssignmentControllerTest:REVIEWER가_배정_시_LS_TASK_ASSIGNMENT에_LABELER_INSERT`, `AssignmentServiceTest:assign_시_LS_TASK_EVENT_LOG에_ASSIGN_이벤트_누적` | **D-ISSUE-01 (HIGH)** |
| TC-ASSIGN-002 | 배정 — 비REVIEWER | PASS | [실동작] WORKER assign → 403, PORTAL assign → 403, WORKER reassign(PATCH) → 403 | `AssignmentControllerTest:WORKER가_assignments_POST_호출시_403` | — |
| TC-ASSIGN-003 | 배정 — 미인증 actor null(NPE 가드) | PASS | [실동작] 토큰 없이 assign → 401 UNAUTHORIZED(NPE·500 없음). `AssignmentService.java:501-503` 일치 | `AssignmentServiceTest:requireReviewer_actor가_null이면_UNAUTHORIZED` | — |
| TC-ASSIGN-004 | 배정 — 존재하지 않는 작업자 | PASS | [실동작] workerId=999999 → 400 "존재하지 않는 작업자입니다". `AssignmentService.java:61-63` 일치 | `AssignmentControllerTest:존재하지_않는_workerId_배정시_INVALID_INPUT` | 역할 검증은 없음 → **D-ISSUE-06** |
| TC-ASSIGN-005 | 배정 — 존재하지 않는 검수자 | PASS | [실동작] reviewerId=999999 → 400 "존재하지 않는 검수자입니다". `AssignmentService.java:64-66` 일치 | 없음 | — |
| TC-ASSIGN-006 | 배정 — 중복 배정(UK 충돌) | PASS | [실동작] rawSn=5 에 동일 worker 2001 재배정 → 409 CONFLICT. `AssignmentService.java:83-86` 일치 | `AssignmentControllerTest:한_영상에_같은_작업자_중복_배정시_409_CONFLICT` | — |
| TC-ASSIGN-007 | 배정 + REVIEWER 동시등록 | PASS | [실동작] reviewerId=1001 포함 요청 → `ls_task_assignment` id=25 `task_type_cd=REVIEWER` INSERT 확인. `AssignmentService.java:90-100` 일치 | `AssignmentServiceTest:assign_요청에_reviewerId가_있으면_응답_Item에도_reviewerId가_반영` | — |
| TC-ASSIGN-008 | 배정 — REVIEWER 중복 등록 skip | PASS | [실동작] rawSn=5 에 worker 2002 + 동일 reviewer 1001 재요청 → 201, LABELER id=27 신규 생성 보존, REVIEWER row 는 중복 미생성(id=25 1건 유지). `AssignmentService.java:122-135` 일치 | 없음 | — |
| TC-ASSIGN-009 | 재배정 정상 | PASS | [실동작] `PATCH /v1/assignments/28 {workerId:2001}` → 200, `ls_task_assign_history` hstry_seq=2(prev 1001→new 2001, chg_user_no=1001), `ls_task_event_log` REASSIGN(prev_user_no 포함), `prev.reassignTo` 반영. `AssignmentService.java:138-192` 일치 | `AssignmentControllerTest:재배정_시_LS_TASK_ASSIGN_HISTORY에_이전_레코드_INSERT`, `AssignmentServiceTest:reassign_시_actor_sub와_시각이_HSTRY에_기록` | — |
| TC-ASSIGN-010 | 재배정 — 완료(APPROVED) 차단 | PASS | [실동작] assignment 11(rawSn=13, APPROVED) 재배정 → 409 `ASSIGNMENT_ALREADY_COMPLETED` "완료된 작업은 재배정할 수 없습니다". `AssignmentService.java:146-153` 일치 | `AssignmentServiceTest:reassign_완료된_배정은_거부_ASSIGNMENT_ALREADY_COMPLETED` | IN_REVIEW 중 재배정은 허용 → **D-ISSUE-05** |
| TC-ASSIGN-011 | 재배정 — 배정 미존재 | PASS | [실동작] assignmentId=999999 → 404 "배정을 찾을 수 없습니다". `AssignmentService.java:143-144` 일치 | 없음 | — |
| TC-ASSIGN-012 | 재배정 — 동일 작업자 | PASS | [실동작] id=24(worker 2001) → 2001 재배정 400 "현재 배정된 작업자와 동일합니다". `AssignmentService.java:158-160` 일치 | 없음 | 동시 요청 시 무력화 → D-ISSUE-02 |
| TC-ASSIGN-013 | 재배정 — 새 작업자 이미 배정(사전) | PASS | [실동작] id=24 → 2002(rawSn=5 에 이미 LABELER) 재배정 409 "선택한 작업자는 이미 해당 영상에 배정되어 있습니다". `AssignmentService.java:165-175` 일치 | 없음 | — |
| TC-ASSIGN-014 | 재배정 — 동시성 UK 충돌(flush) | FAIL | [실동작] assignment 29 를 동일 신규 worker(1002)로 **4병렬 PATCH → 4건 전부 200**. `ls_task_assign_history` 에 동일 내용 4행(hstry 4~7, prev 2002→new 1002), `ls_task_event_log` REASSIGN 4행(evnt 63~66) 중복 적재. 기대 CONFLICT(409) 미발생 — 재배정은 기존 row 의 UPDATE 라 UK 위반이 발생하지 않아 `AssignmentService.java:183-189` 방어가 사실상 사문화 | 없음 | **D-ISSUE-02** |
| TC-ASSIGN-015 | 배정이력 — WORKER 본인만(IDOR) | PASS | [실동작] W2 가 assignment 24(worker 2001) 이력 조회 → 403 "본인 배정 이력만 조회할 수 있습니다". W1(본인) → 200. `AssignmentService.java:212-218` 일치 | `AssignmentControllerTest:WORKER가_다른_작업자_배정_이력_조회시_403_FORBIDDEN_IDOR_방어` | — |
| TC-ASSIGN-016 | 배정이력 — REVIEWER 전체 | PASS | [실동작] REVIEWER 가 assignment 28 이력 조회 → ASSIGN·REASSIGN 이 `ocrn_dt` ASC 통합 반환, actor/subject/prev 이름 매핑됨(userNo batch lookup 1회, `AssignmentService.java:220-233`) | `AssignmentServiceTest:getHistory_재배정_2회_있을때_총_3건_시간순_반환` | — |
| TC-ASSIGN-017 | 배정이력 — 잘못된 ID | PASS | [실동작] `/v1/assignments/0/history` → 400 "잘못된 배정 ID 입니다". `AssignmentService.java:206-208` 일치 | 없음 | — |
| TC-ASSIGN-018 | 배정목록 — WORKER 본인만(param 무시) | PASS | [실동작] W1(2001)이 `?workerId=2002` 지정 → 200, 결과 13건 전부 workerId=2001(본인 sub 강제). `AssignmentService.java:258-261` 일치 | `AssignmentControllerTest:WORKER가_다른_workerId_조회시_본인_데이터만_반환` | — |
| TC-ASSIGN-019 | 배정목록 — REVIEWER 필터/전체 | PASS | [실동작] REVIEWER 전체 15건(worker 2001·2002), `?workerId=2002` 필터 2건. `AssignmentService.java:262-267` 일치 | `AssignmentControllerTest:list_기본_sort_regDt_desc_적용` | — |
| TC-ASSIGN-020 | 배정목록 — 기타 역할/미인증 | PASS | [실동작] PORTAL_USER → 403, 미인증 → 401. `AssignmentService.java:254-269` 일치 | `AssignmentServiceTest:listAssignments에_actor가_null이면_UNAUTHORIZED` | — |
| TC-ASSIGN-021 | 상태 upsert 동시 INSERT 충돌 | PASS | [실동작] status 행 없는 rawSn=10 에 서로 다른 worker(2001/2002)로 동시 배정 → A 201 / B 409. `ls_raw_data_status(10)` 1행, `ls_task_assignment` 1행만 생성(중복 없음, 재시도 가능). `AssignmentService.java:492-495` 일치 | 없음 | 메시지 오도 → **D-ISSUE-07** |

---

## 이슈 상세

### [D-ISSUE-01] TC-ASSIGN-001 — `POST /v1/assignments` 가 APPROVED(검수완료) 영상을 무검증으로 ASSIGNED 로 강등
- **심각도**: HIGH
- **기대 동작(기대효과)**: 검수 승인(`APPROVED`)은 작업 종결 상태다. CLAUDE.md 상 승인 시점에 라벨 버전 스냅샷이 확정되고 `TASK_COMPLETED` 가 관제서버로 push 되며, 데이터마트 View 4종이 `DATA_STTS_CD='APPROVED'` 게이트로 노출된다. 따라서 APPROVED 영상의 상태를 되돌리는 경로는 **반드시 `ReviewStateMachine.verify` 를 통과**해야 하고, APPROVED 출발은 `PENDING`(WORKER 재제출) 한 곳만 허용돼야 한다. 재배정(`reassign`)에는 이미 이 가드가 있다(`AssignmentService.java:146-153`).
- **현재 동작(이슈 내용)**: 신규 배정 경로에는 동일 가드가 없다. `AssignmentService.java:70-81`
  ```java
  for (Long rawDataId : req.rawDataIds()) {
      LsTaskAssignment entity = authrtRepository.save(LsTaskAssignment.createLabeler(...));
      LsRawDataStatus stts = upsertDataStts(rawDataId);
      stts.markAssigned();          // ← 현재 상태 무관 무조건 ASSIGNED (LsRawDataStatus.java:78-81, 검증 없는 setter)
  ```
  실측: rawSn=4 를 APPROVED 로 만든 뒤 다른 작업자(2002)를 배정하니 **201 성공 + `ls_raw_data_status(4)` APPROVED→ASSIGNED(ver 15→16)**. 결과적으로 rawSn=4 는 `V_COMPLETED_VIDEO` 에서 사라졌다(조회 결과 5,6,13,14,17,19,26 만 노출 — 4 없음). `ls_label_version` 스냅샷과 `LS_DATASET_VIDEO_META` 동결 데이터는 남아 있어 **뷰/스냅샷 불일치** 상태가 된다. 이벤트 로그에도 "강등" 흔적은 남지 않고 ASSIGN 만 기록된다.
  → B 클러스터가 배치 경로(`BatchTransitionService.java:283-291`)에서 찾은 동일 유형(B-ISSUE-03)에 더해, **검수/배정 API 경로에 존재하는 세 번째 무검증 writer**다.
- **재현/확인 경로**:
  ```bash
  # 사전: rawSn=X 가 APPROVED (submit→start→approve)
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H "Content-Type: application/json" -d '{"workerId":2002,"rawDataIds":[X]}'   # → 201
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT raw_data_id,data_stts_cd FROM public.ls_raw_data_status WHERE raw_data_id=X;"  # → ASSIGNED
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT raw_sn FROM public.v_completed_video WHERE raw_sn=X;"              # → 0 rows
  ```
- **영향**: 데이터 정합성 — ①`TASK_COMPLETED` 통지 후 관제서버가 완료로 인지한 작업이 저작도구에서 조용히 미완료로 되돌아감(재통지 없음) ②데이터마트 4종 View 에서 검수완료 영상이 사라짐 ③`LS_LABEL_VERSION`(APPROVED 스냅샷)·`LS_DATASET_VIDEO_META`(동결)와 상태행 불일치. 보안 축으로는 CWE-840(비즈니스 로직 검증 누락). REVIEWER 권한만 있으면 UI의 일반 배정 조작으로 유발되므로 도달성이 높다.
- **수정 방향(제안)**: `assign()` 루프 안에서 `upsertDataStts` 직후 현재 `dataSttsCd` 가 `APPROVED`(및 검수 진행 중 `IN_REVIEW`/`PENDING`)인 경우 `ASSIGNMENT_ALREADY_COMPLETED`/`CONFLICT` 로 차단하거나, 최소한 `markAssigned()` 를 `ReviewStateMachine.verify(current, ASSIGNED)` 경유로 바꾼다. 근본 처방은 `LsRawDataStatus.transitionTo/markAssigned` 를 상태머신 경유 단일 진입점으로 좁혀 배치·검수·배정 3경로가 같은 규칙을 공유하게 하는 것(B-ISSUE-03 과 공통 수정).

### [D-ISSUE-02] TC-ASSIGN-014 — 동시 재배정이 전혀 직렬화되지 않아 배정 이력이 중복 적재됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 테스트케이스 기대값은 "사전 통과 후 경합 시 CONFLICT(409)". 재배정은 감사 대상 행위이므로 동시 요청 N건이 들어와도 실제 전이는 1회, `LS_TASK_ASSIGN_HISTORY`/`LS_TASK_EVENT_LOG` 도 1건이어야 한다.
- **현재 동작(이슈 내용)**: `LsTaskAssignment` 엔티티에 `@Version`/비관적 잠금이 없고(`LsTaskAssignment.java` 전체에 `@Version` 부재), 재배정은 기존 row 의 UPDATE 라 UK 위반이 발생하지 않는다. 따라서 `AssignmentService.java:183-189` 의 `DataIntegrityViolationException` → CONFLICT 방어가 발화하지 않는다. 동일 작업자 가드(`:158-160`)도 4스레드가 모두 커밋 전 값(prev=2002)을 읽어 통과한다.
  실측(assignment 29, 2002→1002, 4병렬 PATCH):
  ```
  1 200 / 2 200 / 3 200 / 4 200
  ls_task_assign_history: hstry 4,5,6,7 = (authrt 29, prev 2002, new 1002) 동일 4행
  ls_task_event_log:      evnt 63,64,65,66 = REASSIGN(prev 2002 → subject 1002) 동일 4행
  ```
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code}\n" -X PATCH \
    http://localhost:18081/api/v1/assignments/29 -H "Authorization: Bearer $REV" \
    -H "Content-Type: application/json" -d '{"workerId":1002}' & done; wait
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT * FROM public.ls_task_assign_history WHERE authrt_seq=29;"
  ```
- **영향**: CWE-362 (비즈니스 로직 Race Condition). 최종 배정 결과 자체는 동일해 데이터 손상은 없으나 **배정 이력이 실제보다 부풀려져 감사 추적이 왜곡**된다(SCR-TASK-003 작업 이력 화면·감리 대상 이력). 검수 워크플로우(`LS_RAW_DATA_STATUS`)는 `@Version` 으로 보호되지만 배정 테이블은 무방비라는 비대칭.
- **수정 방향(제안)**: `LsTaskAssignment` 에 `@Version` 추가(→ 경합 시 `OptimisticLockingFailureException` → CONFLICT 매핑) 또는 `reassign` 진입 시 `findById` 를 `PESSIMISTIC_WRITE` 로 잠그고 동일작업자 가드를 잠금 이후로 이동. 어느 쪽이든 이력 INSERT 는 전이 성공 1회에만 일어나야 한다.

### [D-ISSUE-03] TC-REVIEW-034 — `ReviewStateMachine.verify(null, …)` 가 400 이 아닌 NPE(500)
- **심각도**: LOW
- **기대 동작(기대효과)**: 테스트케이스는 `from=COMPLETED/PROCESSING/null` 모두 `INVALID_INPUT(400)` 을 기대한다. 상태머신은 알 수 없는 입력에 대해 fail-closed 로 400 을 던져야 하며 500(내부 오류)을 노출해선 안 된다(security.md — 예외 처리 시 내부 구현 노출 금지).
- **현재 동작(이슈 내용)**: `ReviewStateMachine.java:43-51` 의 `ALLOWED` 는 `Map.of(...)` (JDK `ImmutableCollections.MapN`) 이다. `:59` `ALLOWED.get(from)` 에서 `from=null` 이면 `MapN.probe(null)` 이 `pk.hashCode()` 를 호출해 **NullPointerException** 이 발생하며, `:60-63` 의 `allowed == null` 분기에 도달하지 못한다. COMPLETED/PROCESSING 은 맵 미등록 키라 정상적으로 `INVALID_INPUT` 을 던진다(이 두 값은 정상).
- **재현/확인 경로**: HTTP 로는 재현 불가 — `LS_RAW_DATA_STATUS.DATA_STTS_CD` 는 `nullable=false`(`LsRawDataStatus.java:37`)이고 `transitionTo` 호출자 전수(`AssignmentService:75`, `BatchTransitionService:243,289`, `ReviewService:348,375,398,411,468`)에서 null 을 넣는 경로가 없다. 단위 수준 확인: `new ReviewStateMachine().verify(null, "PENDING")`.
- **영향**: 현재 도달 불가(이론적). 향후 상태 컬럼이 nullable 로 바뀌거나 다른 소스(외부 연동/DTO)에서 상태를 주입하면 500 + 스택트레이스 경로가 열린다.
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null || to == null) throw new CustomException(INVALID_INPUT, ...)` 가드 추가. 겸해 `ReviewStateMachineTest` 에 COMPLETED/PROCESSING/null 3케이스를 추가(현재 전용 테스트 없음).

### [D-ISSUE-04] TC-REVIEW-011(부가) — 라벨 0건·프레임 이미지 부재 영상도 승인되어 데이터마트 View 에 빈 레코드로 노출
- **심각도**: MEDIUM (확인필요 — 정책 확인 대상)
- **기대 동작(기대효과)**: 검수 승인은 "학습데이터로 확정" 을 뜻하며, 승인된 영상은 `V_COMPLETED_*` 를 통해 관제/데이터마트로 흘러간다. 라벨이 0건이거나 프레임 이미지 실체가 없는 영상은 학습데이터가 될 수 없으므로 승인 단계에서 걸러지거나 최소한 경고돼야 한다.
- **현재 동작(이슈 내용)**: `ReviewService.approve`(`:406-447`)에 라벨/프레임 존재 검증이 없다. 실측 — rawSn=4(프레임 1건, 라벨 0건)를 승인하니 200 APPROVED, `VersionService` 로그 `frames=1 created=0 skipped=0`(스냅샷 0건), 이어지는 AFTER_COMMIT export 는 `[DatasetExport] nothing produced — marked FAILED rawSn=4 version=1` 로 실패했고 `ls_dataset_export` export_sn=6 `export_stts_cd=FAILED` 만 남았다. 그럼에도 승인은 그대로 확정된다. rawSn=5·6 도 같은 방식으로 `v_completed_video` 에 `export_path_nm`·`frame_cnt` 가 NULL 인 행으로 노출된다(기존 rawSn=13 도 동일 형태).
  더불어 export 실패는 승인 트랜잭션 밖(AFTER_COMMIT `DatasetExportBridge`)이라 롤백되지 않고 재시도 큐도 없다 — "승인은 성공했으나 산출물 없음" 상태가 영구화된다.
- **재현/확인 경로**:
  ```bash
  # 라벨 0건 영상 X 를 submit→start→approve
  curl -s -X POST http://localhost:18081/api/v1/reviews/X/approve -H "Authorization: Bearer $REV"   # 200
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT export_sn,export_stts_cd FROM public.ls_dataset_export WHERE data_raw_sn=X;"   # FAILED
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT raw_sn,export_path_nm,frame_cnt FROM public.v_completed_video WHERE raw_sn=X;" # 노출, 값 NULL
  ```
- **영향**: 데이터 품질 — 관제/데이터마트가 "검수완료" 로 픽업하지만 실제 학습데이터(export 폴더)가 없는 영상이 유입된다. 승인 부수효과 4종 중 ①스냅샷 ②상태 ③materialize 는 동일 트랜잭션으로 원자적이지만(테스트 `materialize_실패시_approve_예외_전파_동일트랜잭션_롤백` 으로 확인), ④export 와 `TASK_COMPLETED` 통지는 AFTER_COMMIT 이라 실패해도 승인이 유지된다. 통지는 실패 시 `ControlNotifyService.java:50-55` 가 dead-letter 큐(`enqueuePending`)로 회수하지만 **export 실패는 회수 경로가 없다**.
- **수정 방향(제안)**: ①승인 전 사전 조건(라벨 ≥1건 또는 프레임 이미지 존재) 검증 후 400/409 로 차단할지 정책 확정 ②차단하지 않는다면 export 실패에도 재시도/알림 경로(현재 `LS_BAT_RTY_WTNG` 유사)를 붙여 "승인됐으나 산출물 없음" 이 방치되지 않게 한다. 어느 쪽인지 사용자 확정 필요.

### [D-ISSUE-05] TC-ASSIGN-010(부가) — 검수 진행 중(IN_REVIEW) 영상의 재배정이 허용됨
- **심각도**: LOW (확인필요 — 의도된 정책일 수 있음)
- **기대 동작(기대효과)**: 재배정 가드의 목적이 "진행 중인 검수 흐름 보호"라면, 검수자가 이미 검수를 시작한(IN_REVIEW) 영상의 작업자 교체는 차단되거나 최소한 검수 상태를 되돌려야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:146-153` 의 가드는 `STTS_APPROVED` 한 값만 본다. 실측 — rawSn=10 이 `IN_REVIEW` 인 상태에서 `PATCH /v1/assignments/29 {workerId:2002}` → 200 성공, 상태는 `IN_REVIEW` 그대로 유지되고 기존 작업자(2001)는 배정을 잃는다. 결과적으로 "검수 중인데 배정 작업자만 바뀐" 상태가 만들어진다.
- **재현/확인 경로**: 위 rawSn=10 시퀀스(submit → start → PATCH /v1/assignments/29).
- **영향**: 상태 일관성/운영 혼선. 데이터 손상은 없음.
- **수정 방향(제안)**: 가드를 `APPROVED` 단일 값에서 검수 진행 상태 집합(`PENDING`/`IN_REVIEW`/`APPROVED`)으로 확장할지, 아니면 현행(라벨 작업자 교체는 검수 중에도 허용)이 의도인지 정책 확정.

### [D-ISSUE-06] TC-ASSIGN-004(부가) — 배정 대상의 역할 미검증 (REVIEWER 계정·자기 자신을 LABELER 로 배정 가능)
- **심각도**: LOW (확인필요)
- **기대 동작(기대효과)**: `TASK_TYPE_CD='LABELER'` 배정 대상은 WORKER 역할 계정이어야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:61-63` 은 `userRepository.findByUserNo` 로 **존재 여부만** 확인한다. 실측 — REVIEWER 계정(1001)이 자기 자신(1001)을 rawSn=6 의 LABELER 로 배정 → 201 성공(`ls_task_assignment` id=28, user_no=1001, LABELER). 근본 원인은 `MNG_ACCT_USER` 에 역할 컬럼 자체가 없다는 것(실제 스키마: user_no/user_id/user_nm/user_email/use_yn/reg_dt/upd_dt) — 역할은 JWT `role` 클레임에만 존재한다.
- **재현/확인 경로**: `POST /v1/assignments -d '{"workerId":1001,"rawDataIds":[6]}'` (REVIEWER 토큰) → 201.
- **영향**: 권한 상승은 없다 — `submit`/`cancelSubmit` 은 토큰의 `role == WORKER` 를 요구(`ReviewService.java:500-502`)하므로 REVIEWER 토큰으로는 여전히 제출할 수 없고, 배정 행이 "죽은 배정"으로 남을 뿐이다. 다만 배정 목록·통계에 비작업자가 섞인다.
- **수정 방향(제안)**: 역할 원천이 JWT뿐이면 DB 검증이 불가하므로, ①`MNG_ACCT_USER` 또는 별도 매핑에 역할 컬럼 확보 후 검증하거나 ②배정 UI가 노출하는 후보 목록을 서버가 제한하는 방식 중 선택. 정책 확정 필요.

### [D-ISSUE-07] TC-ASSIGN-021(부가) — 상태행 PK 충돌을 "동일 작업자 중복 배정" 으로 오안내
- **심각도**: LOW
- **기대 동작(기대효과)**: 에러 메시지는 실제 충돌 원인을 반영해야 한다(운영 진단성).
- **현재 동작(이슈 내용)**: 서로 **다른** 작업자(2001/2002)를 같은 rawSn=10 에 동시 배정했을 때 패자가 받는 응답은 `409 "이미 동일 작업자에게 배정된 영상이 있습니다."` 다. 실제 충돌은 `LS_RAW_DATA_STATUS` PK INSERT 경합(`AssignmentService.java:492-495`)이고, 작업자 중복은 아니다. `:83-86` 의 catch 가 두 원인을 한 메시지로 뭉갠다.
- **재현/확인 경로**: status 행이 없는 rawSn 에 서로 다른 workerId 로 `POST /v1/assignments` 2건 동시 실행.
- **영향**: 운영/지원 진단 혼선. 데이터 영향 없음(재시도하면 성공).
- **수정 방향(제안)**: `DataIntegrityViolationException` 의 제약명(UK vs PK)에 따라 메시지를 분기하거나, 상태행 upsert 를 별도 try-catch 로 분리해 "일시적 충돌 — 재시도 필요" 로 안내.

---

## 반증(적극적 실패 유도) 수행 내역 — 결함 미발견 항목

| 반증 시나리오 | 결과 |
|---|---|
| 두 REVIEWER(1001·1002) 동시 승인 9병렬 | **방어 성립** — 1건 200 / 8건 409 "다른 검수자가 먼저 처리했습니다", `ls_task_event_log` APPROVE 정확히 1건, 최종 APPROVED 1회 (낙관적 잠금 `LsRawDataStatus.java:52-55` 실효) |
| 미배정 REVIEWER(1002)의 검수 조작 | 인가 통과(현재 정책 — `ReviewControllerTest:REVIEWER는_본인_검수자_미배정_영상도_검수_가능_현재정책`). 상태 오류만 400 반환 → 의도된 광범위 허용, UNCERTAINTIES #4 취지와 일관 |
| PORTAL_USER 채널 격리 | assign/reviews 목록/상세/이슈 전부 403 |
| 이미 제출된(PENDING) 영상 재제출 | 400 "(PENDING → PENDING)" 차단 |
| APPROVED → PENDING 외 4방향(start/reject/approve/cancel) | 전부 409 CONFLICT |
| REJECTED 후 재승인 직행 | 400 "(REJECTED → APPROVED)" 차단 |
| WORKER 의 승인/검수시작/프레임목록/이슈목록/검수목록 | 전부 403 |
| self-fill 점검 | D-1~D-3 범위에서 외부 응답 없이 값을 자체 생성한 지점 **0건**. `TASK_COMPLETED` payload 의 0/null 하드코딩(`ControlNotifyService.java:97-100`)은 존재하나 D-5(TC-NOTIFY-003) 소관이며 UNCERTAINTIES #3 로 이미 확정된 기존 결함 |

## 환경 변경 기록 (다른 클러스터 참고용)

- **rawSn=26 무변경** — GET 조회 2회만. 최종 `ls_raw_data_status(26)=APPROVED` 유지 확인.
- 신규 데이터: `ls_task_assignment` id=23(raw4/2002), 24·25·27(raw5), 28(raw6), 29(raw10/최종 1002) / `ls_data_issue` sn=2,3(raw4) / `ls_evnt_anno` sn=3 + `ls_evnt_anno_review` rvw_sn=3(raw6) / `ls_dataset_export` sn=6(raw4, FAILED) / `ls_task_assign_history` hstry 2~7 / `ls_task_event_log` evnt 29~66.
- 상태 변경: rawSn=4 ASSIGNED(원상 복귀), 5·6 → APPROVED(신규), 10 → IN_REVIEW(신규). rawSn=8·9·13 은 조회성 시도만 하여 불변.
- 파일 수정 0건, 빌드/테스트 실행 0건.

## 요약

- 총 55건 / PASS 52 / FAIL 1 / PARTIAL 2 / BLOCKED 0 / N/A 0 / 확인필요 0
- 근거 라인 드리프트: **0건** — `ReviewService.java`, `ReviewStateMachine.java`, `AssignmentService.java` 의 인용 라인 41개 구간 전부 실파일과 일치
- self-fill 결함: **0건**
- 이슈: HIGH 1 (D-ISSUE-01 배정이 검수완료 상태 강등) / MEDIUM 2 (D-ISSUE-02 동시 재배정 이력 중복, D-ISSUE-04 빈 영상 승인·export 실패 방치) / LOW 4 (D-ISSUE-03·05·06·07)
- 최우선 처리 권고: **D-ISSUE-01** — B-ISSUE-03(배치 경로)과 근본 원인이 같으므로 `LsRawDataStatus` 상태 변경을 `ReviewStateMachine` 단일 진입점으로 통합하는 공통 수정으로 함께 처리할 것
