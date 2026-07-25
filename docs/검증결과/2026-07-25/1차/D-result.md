# D. 검수 / 버전관리 / 관제통지 — 1차 검증 결과

> 검증일 2026-07-25 · 기준: **실동작**(풀스택+목업서버, backend V130)

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

# D-4 / D-5 검증 결과 — 버전관리 스냅샷 · diff / rollback

- 담당 범위: `docs/test-cases/D-review-version-notify.md` **76~119행** (TC-VERSION-001~014, TC-DIFF-001~020)
- 검증 일시: 2026-07-25 / 로컬 풀스택(klid-backend V130 HEAD 재빌드, klid-postgres, klid-ai-server, klid-mock-server, klid-frontend)
- DB 스키마: `public` (klid_system / klid_user), BE 호스트 포트 `18081`
- 근거 파일: `backend/src/main/java/kr/co/cudo/authoring/version/service/VersionService.java` (817행)

## 실동작 시나리오 (본 검증에서 실제 구동한 것)

| # | 행위 | 대상 | 결과 |
|--:|------|------|------|
| 1 | 스냅샷 완전성 대조 | rawSn=26 (참조 데이터, **읽기만**) | 16프레임 = 16 스냅샷, payload items 합계 **131 = DB 라벨 131** 정확 일치 |
| 2 | SHA-256 결정성 | srcSn=446 payload | `shasum -a 256` 결과 = `version_hash` **완전 일치** |
| 3 | 라벨 저장 → 버전 미생성 | srcSn=421 (rawSn=19) | PUT `/v1/frames/421/labels` 후 버전 행 1건 그대로 |
| 4 | 수정 후 재승인 → v2 적층 | rawSn=19 | 변경 프레임만 v2 생성, 나머지 11프레임 멱등 |
| 5 | diff v1↔v2 (양방향) | srcSn=421 | MODIFIED/REMOVED/ADDED 정확, 역방향 정확 반전 |
| 6 | rollback → v1 | srcSn=421 | 라벨 본문 복원 성공, **버전은 기존행 재활성(신규 ROLLBACK 행 없음)** |
| 7 | 롤백 후 재승인 → v3 | srcSn=421 | 내용 동일한데 **v3 신규 생성**, diff v1↔v3 = 8건 오분류 |
| 8 | 멱등 롤백(active==대상) | srcSn=421 | 버전행 미생성 O, 그러나 **라벨 delete+recreate 실행됨** |
| 9 | 가드 매트릭스 | — | 해시형식/미존재/IDOR/미인증/PORTAL/srcSn불일치 전건 실행 |

> ⚠ **rawSn=26 은 읽기 전용으로만 사용**했다(후속 E 클러스터 참조 데이터 보호). 쓰기 시나리오는 전부 **rawSn=19 / srcSn=421** 에서 수행했다. 원복 결과는 문서 말미 「환경 변경 및 원복」 참조.

---

## D-4. 버전관리 스냅샷 (TC-VERSION)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-VERSION-001 | 승인 스냅샷 생성 | PASS | [실동작] rawSn=26 → `ls_label_version` 16행 전부 `save_reason_cd=APPROVED`·`actvtn_yn=Y`, 프레임별 1행. SHA-256 재계산 = `version_hash` 일치(srcSn=446: `3f8f7d2f…59f3`). payload `items` 합계 131 = `ls_data_lbl` 131건 [정적] VersionService.java:124-166 | `VersionServiceTest#commitApprovedWritesSnapshotPerFrame` (L157) | 스냅샷 단위는 **영상 1건이 아니라 프레임별 N행**. 16행 합집합이 영상 전체를 손실 없이 커버함을 실측 확인 |
| TC-VERSION-002 | 라벨 없는 프레임 스킵 | PASS | [실동작] rawSn=13 프레임 11 중 라벨 0인 7프레임(src 359~363,366,367) → 버전 0행. 로그 `[Version] approved snapshot rawSn=5 frames=1 created=0 skipped=0` [정적] :150-153 | L175 | 빈 스냅샷 적재 없음 확인 |
| TC-VERSION-003 | 멱등 — 무변경 재승인 | PASS | [실동작] rawSn=19 재승인 시 로그 `frames=12 created=1 skipped=0` — 미변경 11프레임 새 버전 미생성. src 422/424/428/430 은 ver_no=1 유지 [정적] :225-230 | L195 | |
| TC-VERSION-004 | 재승인 시 변경분 새 버전 적층 | PASS | [실동작] srcSn=421 → `lbl_version_sn=37, ver_no=2, actvtn_yn=Y` 생성 + 기존 16번행 `Y→N` deactivate [정적] :232-234 | L208 | |
| TC-VERSION-005 | 프레임 없음 | PARTIAL | [정적] :135-139 `frames.isEmpty()` → `CommitResult.EMPTY(0,0)` + 로그. **실동작 미검증** — 프레임 0 영상(rawSn=9/10)은 타 세션 in-flight 상태라 승인 미실행 | L227 | 미검증 사유 = 공유 환경 보호(파괴적 되돌림 불가) |
| TC-VERSION-006 | 영상 미존재 | PARTIAL | [정적] :132-133 `videoRepository.findById(...).orElseThrow(NOT_FOUND)` | L303 | `commitApproved` 는 `ReviewService.approve` 내부 전용 호출(ReviewService.java:424) — 외부 HTTP 진입점 없어 실동작 직접 호출 불가 |
| TC-VERSION-007 | rawSn/actor null 가드 | PARTIAL | [정적] :126-131 rawSn null→`IllegalArgumentException`, actor null→`UNAUTHORIZED` | — | 상동(내부 전용 호출) |
| TC-VERSION-008 | 대용량 1MB 초과 단순화 후 승인 성공 | PARTIAL | [정적] :201-207 → :595-610 `serializeSnapshotWithSimplification` 1MB 초과 시 `PolygonSimplifier` 후 10MB 한도 적용, 승인 미차단 | L240 | 실데이터 최대 payload 53,790B(rawSn=26 src 450) — 1MB 경계 미도달로 실동작 미유발 |
| TC-VERSION-009 | 10MB 초과 프레임 SKIPPED | PARTIAL | [정적] :208-218 `catch(CustomException)` → `FrameSnapshotOutcome.SKIPPED`, 전체 승인 유지. 집계는 `CommitResult.skipped` | L273, L288 | 실동작 유발 불가(정상 데이터 도달 불가 경로) |
| TC-VERSION-010 | 동시 승인 스냅샷 직렬화(Race) | PARTIAL | [정적] :221-223 `findActiveForUpdate` → `LsLabelVersionRepository.java:29` `@Lock(LockModeType.PESSIMISTIC_WRITE)` 확인 | `VersionServiceRollbackLockOrderTest` | 동시 승인 실부하 미유발 |
| TC-VERSION-011 | 비식별 신고 스냅샷 | PASS | [실동작] rawSn=8 → `lbl_version_sn=1, save_reason_cd=DEIDENT_REPORT, actvtn_yn=N, data_src_sn=NULL, items=28` [정적] :255-277 | `VersionServiceDeidentSnapshotTest` | ⚠ 이 스냅샷은 **복원 경로가 없음** → D-ISSUE-25 |
| TC-VERSION-012 | 비식별 신고 — 라벨 0 스킵 | PASS | [실동작] 로그 `[Version] deident-report snapshot skipped (no labels) rawSn=7` (false 반환 경로) [정적] :261-265 | 동상 | |
| TC-VERSION-013 | 버전목록 조회 IDOR | PASS | [실동작] `GET /v1/frames/446/versions` — REVIEWER 200 (`isCurrent:true` 표시 정상) / 미배정 WORKER(userNo=9999) **403 FORBIDDEN** / 미인증 **401** / 버전 0 프레임(359) 200 `[]` [정적] :279-290 | L615, L622, L635 | |
| TC-VERSION-014 | 스냅샷에 PII/본문 로그 미출력 | PASS | [실동작] `docker logs klid-backend` 전량 grep — `VersionService` 로그 8종 모두 rawSn/srcSn/count/hash/actor 만. 좌표(`[[`)·`points`·`payload=`·`"label"` 매칭 **0건** [정적] :211,216 | — | CWE-359 방어 실동작 확인 |

---

## D-5. diff / rollback (TC-DIFF)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-DIFF-001 | diff — ADDED/REMOVED/MODIFIED 분류 | PASS | [실동작] srcSn=421 v1→v2: `MODIFIED(176)` 좌표 +7.0 정확 반영 / `REMOVED(179)` / `ADDED(391)` — 총 3건, 무변경 177·178 결과 제외. 역방향(v2→v1) = `REMOVED(391)`,`MODIFIED(176)`,`ADDED(179)` 정확 반전 [정적] :298-314 | L647, L712 | |
| TC-DIFF-002 | diff — SKELETON v 변경 감지 | PARTIAL | [정적] :734-751 `readPoints` 가 SKELETON 일 때만 3번째 원소(v) 포함, `equalsContent` 로 MODIFIED 판정 | L752, L768, L780 | **실동작 미검증** — DB 전체 SKELETON 라벨 1건뿐이고 `lbl_payload LIKE '%SKELETON%'` 스냅샷 **0건** |
| TC-DIFF-003 | diff — 다른 프레임(srcSn) | PASS | [실동작] src446 해시 vs src447 해시 → HTTP 200 `data:[]` [정적] :307-309 | — | |
| TC-DIFF-004 | diff — 해시 형식 위반 | PASS | [실동작] non-hex(`zzzz`) → 400 `INVALID_INPUT`, 66자(>64) → 400 [정적] :800-812 | L605 | `compareWith` 누락 시에도 400 |
| TC-DIFF-005 | diff — 존재하지 않는 해시 | PASS | [실동작] hex 64자 `0000…0000` → 404 `NOT_FOUND` "to 버전을 찾을 수 없습니다." [정적] :560-566 | L794 | ⚠ `.get(0)` 비결정 선택 → D-ISSUE-27 |
| TC-DIFF-006 | diff — 접근권한(IDOR) | PASS | [실동작] 미배정 WORKER(9999) → 403 `FORBIDDEN` [정적] :304-305 `accessGuard.verifyAccess` 양 버전 각각 호출 | — | |
| TC-DIFF-007 | diff — 손상 JSON | PARTIAL | [정적] :687-690 `catch(Exception)` → `List.of()` + `reason=클래스명`만 로깅(본문 미출력) | — | 손상 payload 주입 불가(DB 쓰기 금지)로 실동작 미검증 |
| TC-DIFF-008 | rollback 정상 — 라벨 본문 실제 복원 | **PARTIAL** | [실동작] **복원 부분 PASS**: srcSn=421 v1 롤백 → `ls_data_lbl` 4건이 v1 좌표와 정확 일치(person 275.62…, car 392.61… 복귀), 추가분(391) 삭제. 로그 `rollback restored labels srcSn=421 deleted=4 created=4`.<br>**버전 부분 불일치**: 기대 "새 active(ROLLBACK)" ≠ 실제 "기존 v1행 재활성" — `save_reason_cd='ROLLBACK'` 행 **미생성**(DB 전체 0건) [정적] :338-387, :410-419 | L336, L355, L408, L510 | → **D-ISSUE-21**(ROLLBACK 분기 도달 불가) / **D-ISSUE-22**(lbl_sn 재발급) / **D-ISSUE-23**(AI메타·trackId 유실) |
| TC-DIFF-009 | rollback — 빈 스냅샷으로 복원 | PARTIAL | [정적] :439-441 공백 스냅샷→`List.of()`, :451-453 `items` 부재→`List.of()`, :478-485 `replaceFrameLabels` 가 삭제만 수행 | — | 빈 payload 버전행이 DB에 존재하지 않아 실동작 미검증(빈 프레임은 :152 에서 스냅샷 자체가 스킵됨) |
| TC-DIFF-010 | rollback — 손상 스냅샷 전체 롤백 | PARTIAL | [정적] :443-449 `readTree` 실패→`INVALID_INPUT(400)`, :454-456 `items` 비배열→400. **라벨 교체(:373) 이전(:363)에 파싱**하므로 부분 적용 없음 — 순서 정합 확인 | L493 | 손상 데이터 주입 불가로 실동작 미검증 |
| TC-DIFF-011 | rollback — 작업락 영상 차단 | PARTIAL | [정적] :355-358 `workLockService.isRawLocked` → `CONFLICT` | L469 | **실동작 미검증** — `ls_auth_work_lock` LOCKED 영상은 rawSn=5/6/8 뿐이며 이들은 프레임 단위 버전행이 없어 :348 NOT_FOUND 가 락 검사(:355)보다 먼저 발동(실제 확인: rawSn=8 → 404). 락 주입은 DB 쓰기라 미수행 |
| TC-DIFF-012 | rollback — APPROVED 영상 TASK_MODIFIED 발행 | PARTIAL | [정적] :381-385 `isReviewApproved` 참일 때 `TaskModifiedEvent(LABEL_UPDATED)` publish. rawSn=19 는 롤백 시점 APPROVED 였으므로 publish 경로 진입 | L429 | **소비 미관찰** — `ControlNotifyEventListener` 가 `@ConditionalOnProperty(authoring.control-notify.enabled=true)`(listener:21)인데 본 환경 `CONTROL_NOTIFY_ENABLED=false` → 빈 미등록. `ls_control_notify_fallback` 0행 |
| TC-DIFF-013 | rollback — 미APPROVED 통지 미발행 | PARTIAL | [정적] :510-515 상태행 없거나 APPROVED 아니면 `false` → publish 안 함 | L452 | 상동(리스너 비활성) |
| TC-DIFF-014 | rollback — 멱등(현재 active 동일) | **PARTIAL** | [실동작] active(v3)로 롤백 → 응답이 기존행(`lblHstrySn=38`) 반환, 버전 행 수 3 유지(**새 행 미생성 PASS**), 로그 `rollback idempotent`.<br>그러나 **같은 호출에서 `rollback restored labels srcSn=421 deleted=4 created=4`** 도 기록 — 라벨은 delete+재삽입되어 `lbl_sn` 392~395 → 396~399 로 변동 [정적] :373 이 :401-408 보다 **먼저** 실행 | L556 | → **D-ISSUE-24** |
| TC-DIFF-015 | rollback — 동일해시 기존행 재활성 | PASS | [실동작] v1 해시 롤백 → 16번행 `N→Y`, 37번행 `Y→N`, 총 행 수 불변(신규 INSERT 없음) [정적] :410-419 | L524 | 프로덕션에서는 이 경로가 **항상** 선택됨(D-ISSUE-21) |
| TC-DIFF-016 | rollback — 대상 버전 미존재 | PASS | [실동작] `0000…0000` → 404 "롤백 대상 버전을 찾을 수 없습니다." / 타 프레임(422) 해시를 srcSn=421 로 롤백 → 404 (srcSn 스코핑 정상) [정적] :348-349 | L596 | |
| TC-DIFF-017 | rollback — actor null / IDOR | PASS | [실동작] 미인증 → **401**, 미배정 WORKER(9999) → **403**, 잘못된 해시 형식 → 400, `srcSn` 누락 → 400(`srcSn: must not be null`) [정적] :340-346 | L583 | |
| TC-DIFF-018 | rollback — Race 잠금 순서(교체 前 잠금) | PARTIAL | [정적] :365-373 `findActiveForUpdate`(PESSIMISTIC_WRITE, repo:29) → 그 다음 `replaceFrameLabels` 순서 확인. 주석 의도와 코드 순서 일치 | `VersionServiceRollbackLockOrderTest` | 동시 롤백 실부하 미유발 |
| TC-DIFF-019 | rollback — SKELETON 삼중값 무손실 복원 | PARTIAL | [정적] :462-467 `rawPointsJson` 보존, :492-494 SKELETON 이면 원본 JSON 그대로 재저장 | L379, `VersionServiceKeypointSnapshotTest` | 실데이터 SKELETON 스냅샷 0건으로 실동작 미검증 |
| TC-DIFF-020 | isCommittable — PORTAL 채널 배제 | PASS | [실동작] PORTAL 토큰(channel=PORTAL) → listVersions **403**, diff **403**, rollback **403** [정적] :814-816 | L313 | 실차단 주체는 컨트롤러 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")`. `isCommittable` 자체는 프로덕션 미참조 → D-ISSUE-28 |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [D-ISSUE-21] TC-DIFF-008 — `SAVE_REASON_ROLLBACK` 분기가 프로덕션에서 도달 불가(dead branch) + 이를 검증하는 테스트 2건이 위양성
- **심각도**: HIGH
- **기대 동작(기대효과)**: 롤백 시 `LS_LABEL_VERSION` 에 `SAVE_REASON_CD='ROLLBACK'` 인 새 active 버전이 적층되어, "언제 누가 어느 버전으로 되돌렸는가"가 이력으로 남는다(TC 기대값 + `VersionController` javadoc "새 active 버전이 생성되며 LS_LABEL_VERSION에 기록").
- **현재 동작(이슈 내용)**: 롤백 결과 해시는 대상 스냅샷 payload 로부터 재계산된다.
  - `VersionService.java:376` `String newHash = sha256Hex(snapshot);` (snapshot = `target.getLabelPayload()`)
  - 정상 스냅샷은 `version_hash == sha256(lbl_payload)` 가 항상 성립한다(실측: srcSn=446 에서 `shasum -a 256` 결과가 저장 해시와 완전 일치).
  - 따라서 `:410-411` `findByDataSrcSnAndVersionHash(srcSn, newHash)` 는 **항상 대상 행 자신을 찾아** `:412-419` 재활성 경로로 분기하고, `:421-422` `saveActiveVersion(..., SAVE_REASON_ROLLBACK, ...)` 에는 도달하지 못한다.
  - **실측**: srcSn=421 롤백 2회 실행 후에도 `save_reason_cd='ROLLBACK'` 행 0건. DB 전체 집계도 `APPROVED 37 / DEIDENT_REPORT 1` 뿐.
  - 로그도 항상 `[Version] rollback reactivated existing …` 만 출력되고 `[Version] rolled back …`(:423) 은 미출력.
- **테스트 위양성**: `VersionServiceTest.java:336` `assignedWorkerRollbackRestoresSnapshot` / `:510` `reviewerRollbackCreatesNewVersion` 은 픽스처 해시를 `"feedface1234567890abcdef1234567890abcdef"`(40자, payload 의 SHA-256 아님)로 시드한다. 이 때만 `newHash != 저장 해시`가 되어 ROLLBACK 분기가 실행되고 `assertThat(rollback.getSaveReasonCd()).isEqualTo(SAVE_REASON_ROLLBACK)` 가 통과한다. **프로덕션에서 발생할 수 없는 상태를 픽스처로 만들어 통과시키는 구조**라, 두 테스트는 회귀 방어력이 없다. (반면 `:524` `rollbackToExistingHashReactivatesInsteadOfInsert` 는 실제 sha256 을 써서 프로덕션 동작과 일치한다.)
- **재현/확인 경로**:
  ```bash
  V1=$(docker exec klid-postgres psql -U klid_user -d klid_system -At \
    -c "SELECT version_hash FROM ls_label_version WHERE lbl_version_sn=16;")
  curl -s -X POST "http://localhost:18081/api/v1/versions/$V1/rollback" \
    -H "Authorization: Bearer $REV" -H "Content-Type: application/json" -d '{"srcSn":421}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT save_reason_cd, count(*) FROM ls_label_version GROUP BY 1;"   # ROLLBACK 0건
  # 해시 결정성(= 분기 도달 불가의 근거)
  docker exec klid-postgres psql -U klid_user -d klid_system -At \
    -c "SELECT lbl_payload FROM ls_label_version WHERE data_src_sn=446;" > /tmp/p.txt
  printf '%s' "$(cat /tmp/p.txt)" | shasum -a 256   # == version_hash
  ```
- **영향**: 롤백 이력이 별도 행으로 남지 않아 **"되돌리기 행위" 자체가 감사 추적에서 소실**된다. 재활성된 행의 `REG_ID`/`REG_DT` 는 최초 승인자·승인시각이므로, 누가 언제 롤백했는지 DB 만으로 복원할 수 없다(로그에만 존재). SFR-08 버전관리의 이력 요건과 문서(Controller javadoc·CLAUDE.md "롤백은 대상 검수완료 스냅샷을 새 active 버전으로 복원")에 대한 실질 미충족.
- **수정 방향(제안)**: ① 롤백을 항상 신규 행으로 적층하되 `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 와 충돌하지 않도록 버전 식별 축을 분리(예: 재활성 대신 `SAVE_REASON_CD='ROLLBACK'` + 별도 이력 테이블/컬럼에 롤백 actor·시각 기록), 또는 ② 재활성 경로에서도 롤백 수행자/시각을 갱신·별도 이력에 남기고, TC 기대값을 "기존행 재활성"으로 정정. 어느 쪽이든 위양성 테스트 2건은 실제 sha256 픽스처로 교정 필요.

### [D-ISSUE-22] TC-DIFF-008 — 롤백이 `LBL_SN` 을 재발급해 이후 diff 가 "전량 교체"로 오분류(round-trip 불안정)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 스냅샷 v1 → 수정 → v2 → v1 로 롤백 → 재승인하면, 라벨 내용이 v1 과 동일하므로 v1 대비 diff 는 **변경 0건**이어야 한다(또는 최소한 "전부 삭제+전부 추가"로 보이지 않아야 한다).
- **현재 동작(이슈 내용)**: `replaceFrameLabels`(`VersionService.java:478-504`)가 기존 라벨을 `deleteAll` 후 `LsDataLbl.createManual(...)` 로 재생성하므로 IDENTITY PK 가 새로 발급된다(주석 :325 "lbl_sn 재발급 허용"). diff 의 라벨 식별자는 `items[].id`(=`LBL_SN`, :668-669)이므로 동일 좌표라도 다른 객체로 인식된다.
  - **실측**: srcSn=421 라벨 `176,177,178,179` → 롤백 후 `392,393,394,395` → 멱등 롤백 후 `396,397,398,399` → 재롤백 후 `400,401,402,403`.
  - 롤백 후 재승인으로 만든 v3(`5c3a62fe…`)는 v1 과 **좌표 집합이 완전히 동일**(payload `points` 정렬 비교 결과 IDENTICAL)한데도 새 해시로 적층됐고, `diff(v1 → v3)` 는 **8건**을 반환했다: `REMOVED 176/177/178/179 + ADDED 392/393/394/395`.
- **재현/확인 경로**:
  ```bash
  # (이미 적층된 v1=16, v3=38 기준)
  V1=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c "SELECT version_hash FROM ls_label_version WHERE lbl_version_sn=16;")
  V3=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c "SELECT version_hash FROM ls_label_version WHERE lbl_version_sn=38;")
  curl -s "http://localhost:18081/api/v1/versions/$V3/diff?compareWith=$V1" -H "Authorization: Bearer $REV"
  # → 8건 (REMOVED×4 + ADDED×4), 좌표는 동일
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT jsonb_agg(e->'points' ORDER BY e->>'label') FROM ls_label_version, jsonb_array_elements((lbl_payload::jsonb)->'items') e WHERE lbl_version_sn IN (16);"
  # 38번과 비교 시 완전 동일
  ```
- **영향**: 데이터 손실은 아니나 **버전 비교 결과가 사실과 다르게 보고**된다. ① 검수자가 diff 화면에서 "라벨 전량 삭제 후 재작성"으로 오독, ② `V_COMPLETED_LABEL_CHANGE`/`LS_DATA_LBL_HSTRY` 기반 변경점이 롤백 1회로 프레임 전체 라벨 수만큼 부풀려져 관제서버로 전달될 수 있음(TASK_MODIFIED 변경 프레임 목록·요약 카운트 왜곡). ③ 롤백을 반복할수록 `LBL_SN` 이 무한 증가.
- **수정 방향(제안)**: 롤백 복원을 `LBL_SN` 보존형으로 전환(스냅샷 `items[].id` 를 그대로 사용해 삭제 대상만 delete / 존재분은 update / 없는 것만 insert). PK 를 명시 지정해야 하므로 IDENTITY 재사용 가능성 검토 필요. 대안으로 diff 식별 축을 `LBL_SN` 이 아닌 안정 키(예: trackId + 라벨명 + 순번)로 바꾸는 방법도 있으나, 라벨 이력 전반의 식별 축을 함께 바꿔야 해 영향 범위가 크다.

### [D-ISSUE-23] TC-DIFF-008 — 롤백이 AI 메타(`LS_DATA_LBL_AI_INFO`)와 `TRCK_ID` 를 복원하지 않고 고아 행을 남김
- **심각도**: HIGH
- **기대 동작(기대효과)**: 롤백은 해당 스냅샷 시점의 라벨 상태로 되돌리는 연산이므로, 오토라벨 출처(`LBL_SRC_CD` YOLO/SAM2/INTERPOLATED)·신뢰도(`CONF_SCORE`)·자동라벨 여부(`AUTO_LBL_YN`)·트랙 연속성(`TRCK_ID`)이 함께 복원되어야 한다. 최소한 삭제되는 라벨의 부수 데이터는 함께 정리되어야 한다.
- **현재 동작(이슈 내용)**:
  1. `replaceFrameLabels`(:495-496)는 `LsDataLbl.createManual(srcSn, lblTypeCd, labelId, label, pointsJson, null)` 만 호출한다. `createManual`(`LsDataLbl.java:226-239`)은 `autoLblYn(AUTO_NO)`, `confScore(null)` 을 **강제**하고 `trackId` 인자를 받지 않는다 → `TRCK_ID`(실제 컬럼)는 항상 NULL 이 된다.
  2. `autoLblYn/confScore/lblSrcCd` 는 `@Transient`(`LsDataLbl.java:88-114`)로, 실제 저장소는 별도 테이블 `LS_DATA_LBL_AI_INFO`(FK 없음, `DATA_LBL_SN` 로 참조)다. `VersionService` 는 이 테이블을 **전혀 참조하지 않는다**(`grep -c "AiInfo" VersionService.java` = **0**).
  3. **비대칭**: 같은 "라벨 삭제" 연산인 `LabelService.bulkUpsert` 는 `LabelService.java:350` 에서 `aiInfoRepository.deleteByDataLblSnIn(delSns);` 로 정리한다. 롤백 경로만 이 처리가 빠져 있다.
  4. 결과적으로 롤백 시 구 `LBL_SN` 의 AI 메타 행이 **고아로 잔존**하고, 복원된 라벨은 AI 메타가 없어 전부 수동 라벨로 보인다.
  5. **블라스트 반경**: `LS_DATA_LBL_AI_INFO` 는 rawSn=26 에 131행(라벨 131건과 1:1), rawSn=13/14/17/27/28 에도 존재. rawSn=26 의 어느 프레임이든 롤백하면 해당 프레임의 AI 메타가 전부 고아화된다.
  6. 동일 구조로 `LS_DATA_LBL_ATTR_VAL`(`ls_data_lbl` 에 **FK 제약 보유**: `fk_ls_data_lbl_attr_lbl`)도 미처리다. 현재 0행이라 미발현이지만, 속성값이 존재하는 프레임을 롤백하면 `deleteAll`(:482)이 **FK 위반으로 500** 이 되거나 속성값이 유실된다.
- **재현/확인 경로**:
  ```sql
  -- 롤백 대상 프레임의 AI 메타 존재 확인 (예: rawSn=26)
  SELECT data_src_sn, count(*) FROM ls_data_lbl_ai_info WHERE data_raw_sn=26 GROUP BY 1;
  -- 롤백 실행 후 고아 검출
  SELECT count(*) FROM ls_data_lbl_ai_info a
    LEFT JOIN ls_data_lbl l ON l.lbl_sn = a.data_lbl_sn WHERE l.lbl_sn IS NULL;
  -- trackId 유실 확인
  SELECT lbl_sn, trck_id FROM ls_data_lbl WHERE src_sn = <롤백한 srcSn>;
  ```
  (본 검증은 rawSn=26 보호를 위해 AI 메타가 0건인 srcSn=421 에서만 롤백을 실행했으므로 고아 행은 실제로 만들지 않았다. 위 3·5 항이 정적 근거다.)
- **영향**: **데이터 손실** — 조건: 오토라벨(YOLO/SAM2/보간) 산출 라벨이 있는 프레임을 롤백할 때. 손실 항목 = 라벨 출처·신뢰도·자동라벨 플래그·트랙 ID. 학습데이터 export(NIA/COCO)의 provenance 필드와 트랙 연속성이 훼손되고, 고아 행이 무한 누적된다. 속성값 보유 프레임에서는 FK 위반으로 롤백 자체가 500 이 될 수 있다(가용성).
- **수정 방향(제안)**: `replaceFrameLabels` 에 ① 삭제 시 `aiInfoRepository.deleteByDataLblSnIn(...)` + 속성값 정리를 추가(LabelService 와 동일 규약), ② 스냅샷 payload 에 이미 존재하는 `autoLblYn/confScore/trackId/lblSrcCd`(`LabelResponse.Item` — `VersionService.java:649-650` 에서 직렬화 확인됨)를 `parseSnapshotLabels`/`RestoredLabel` 에서 함께 읽어 복원하도록 확장. payload 에 이미 값이 있으므로 스키마 변경 없이 복원 가능하다.

### [D-ISSUE-24] TC-DIFF-014 — "멱등" 롤백도 라벨을 삭제·재생성하며, 그 결과 active 스냅샷과 실제 라벨의 ID 가 어긋남
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 현재 active 와 동일한 버전으로 롤백하면 아무 것도 바뀌지 않아야 한다(no-op).
- **현재 동작(이슈 내용)**: `rollback`(:363-378)은 **먼저** `replaceFrameLabels`(:373)로 라벨을 지우고 다시 만든 **뒤에** `upsertRollbackVersion`(:378) 안에서 멱등 여부(:401-408)를 판정한다. 멱등이라 판정돼도 라벨 계층의 파괴적 재작성은 이미 끝난 상태다.
  - **실측**: active=v3(`5c3a62fe…`) 상태에서 v3 로 롤백 → 응답은 기존행(`lblHstrySn=38`), 버전 행 수 3 유지(정상). 그러나 같은 요청 로그에 두 줄이 함께 남았다.
    ```
    [Version] rollback restored labels srcSn=421 deleted=4 created=4
    [Version] rollback idempotent srcSn=421 hash=5c3a62fe… actor=1001
    ```
    라벨 `lbl_sn` 은 392~395 → **396~399** 로 변동.
  - 부수 결과: active 버전 v3 의 payload `items[].id` 는 `392~395` 인데 실제 `LS_DATA_LBL` 은 `396~399` 다 → **active 스냅샷과 작업본의 식별자가 불일치**.
- **재현/확인 경로**:
  ```bash
  ACT=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c \
    "SELECT version_hash FROM ls_label_version WHERE data_src_sn=421 AND actvtn_yn='Y';")
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT lbl_sn FROM ls_data_lbl WHERE src_sn=421 ORDER BY 1;"
  curl -s -X POST "http://localhost:18081/api/v1/versions/$ACT/rollback" -H "Authorization: Bearer $REV" \
       -H "Content-Type: application/json" -d '{"srcSn":421}'
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT lbl_sn FROM ls_data_lbl WHERE src_sn=421 ORDER BY 1;"  # 값이 변함
  docker logs klid-backend --since 1m 2>&1 | grep "rollback"
  ```
- **영향**: 데이터 내용 손실은 없으나, ① no-op 이어야 할 요청이 `LS_DATA_LBL` 전 행 delete+insert 를 유발(불필요 I/O·이력 오염), ② D-ISSUE-22/23 의 부작용(ID 증가·AI 메타 고아화)이 **의미 없는 반복 클릭만으로도** 누적, ③ active 스냅샷 payload 의 `id` 가 실제 라벨과 불일치해 후속 diff 신뢰도 저하.
- **수정 방향(제안)**: 멱등 판정을 라벨 교체 **이전**으로 끌어올린다. 잠금(:368) 획득 직후 `activeVersions` 중 `newHash` 일치 행이 있으면 `replaceFrameLabels` 를 건너뛰고 즉시 기존 active 를 반환. (잠금은 이미 교체 전에 획득하므로 Race 순서 규약 — TC-DIFF-018 — 는 유지된다.)

### [D-ISSUE-25] TC-VERSION-011 — 비식별 신고 스냅샷(`DEIDENT_REPORT`)에 복원 경로가 존재하지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `snapshotDeidentReport` javadoc(:238-241)은 "**복원 가능한** 전체 라벨 스냅샷을 기록한다 … 기존 `LS_DATA_LBL_HSTRY` 는 좌표 복원이 불가하므로"라고 명시한다. 즉 신고로 삭제된 라벨을 되살릴 수단이 있어야 한다.
- **현재 동작(이슈 내용)**: 이 스냅샷은 `LsLabelVersion.createInactiveRawSnapshot(rawSn, …)`(:271-273)으로 적재되어 `DATA_SRC_SN` 이 **NULL** 이다(실측: `lbl_version_sn=1, data_raw_sn=8, data_src_sn=NULL, items=28, actvtn_yn=N`). 반면 두 조회/복원 진입점은 모두 srcSn 스코프다.
  - `listVersions`(:281) → `findByDataSrcSnOrderByRegDtDesc(srcSn)` → NULL 행은 절대 매칭되지 않음.
  - `rollback`(:348) → `findByDataSrcSnAndVersionHash(srcSn, hash)` → 동일하게 매칭 불가.
  - **실측**: rawSn=8 의 프레임(srcSn=4)으로 `GET /v1/frames/4/versions` → `data: []`. 해당 해시로 rollback → **404** "롤백 대상 버전을 찾을 수 없습니다."
- **재현/확인 경로**:
  ```bash
  H8=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c \
    "SELECT version_hash FROM ls_label_version WHERE save_reason_cd='DEIDENT_REPORT' LIMIT 1;")
  S8=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c "SELECT src_sn FROM ls_data_src WHERE raw_sn=8 LIMIT 1;")
  curl -s "http://localhost:18081/api/v1/frames/$S8/versions" -H "Authorization: Bearer $REV"        # []
  curl -s -X POST "http://localhost:18081/api/v1/versions/$H8/rollback" -H "Authorization: Bearer $REV" \
       -H "Content-Type: application/json" -d "{\"srcSn\":$S8}"                                       # 404
  ```
- **영향**: 비식별 누락 신고로 **영상 전체 라벨이 삭제**된 뒤(R1 v1.14), 오탐이었거나 재비식별 완료 후 라벨을 복구하려 해도 API 로는 불가능하다. 스냅샷은 DB 에 있으나 write-only 상태이므로 수기 SQL 없이는 복원 수단이 없다. 라벨 작업량 손실 위험.
- **수정 방향(제안)**: ① rawSn 스코프 조회/복원 진입점 추가(예: `GET /v1/videos/{rawSn}/versions`, `POST /v1/versions/{hash}/restore-raw`)하고 payload 의 프레임별 분해 복원 로직을 구현하거나, ② 신고 스냅샷을 프레임 단위로 분할 적재해 기존 srcSn 경로에 자연스럽게 노출. 어느 쪽이든 "신고 스냅샷은 검수 버전 목록에 섞이면 안 된다"(현재 `ACTIVE_YN='N'` 의도)는 제약을 유지해야 하므로 별도 API 분리가 안전하다.

### [D-ISSUE-26] TC-DIFF-005 관련 — `DATA_SRC_SN` 이 NULL 인 버전 해시로 diff 호출 시 처리되지 않은 500
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 어떤 입력에도 `ApiResponse` 규격의 4xx 로 응답해야 한다(rules/api-design.md — 에러 응답에 내부 정보 노출 금지, 미처리 예외 금지 / OWASP A10:2025 Mishandling of Exceptional Conditions).
- **현재 동작(이슈 내용)**: `diff`(:302-305)는 `findByHashOrThrow` 로 버전을 찾은 뒤 `accessGuard.verifyAccess(fromVersion.getDataSrcSn(), actor)` 를 호출한다. `DEIDENT_REPORT` 스냅샷은 `DATA_SRC_SN` 이 NULL 이므로 `LabelAccessGuard.verifyAndGet`(`LabelAccessGuard.java:46`)의 `srcRepository.findById(null)` 에서 예외가 발생한다.
  - **실측**: HTTP **500**. 백엔드 로그:
    ```
    ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
    org.springframework.dao.InvalidDataAccessApiUsageException: The given id must not be null
    Caused by: java.lang.IllegalArgumentException: The given id must not be null
    ```
- **재현/확인 경로**:
  ```bash
  H8=$(docker exec klid-postgres psql -U klid_user -d klid_system -At -c \
    "SELECT version_hash FROM ls_label_version WHERE save_reason_cd='DEIDENT_REPORT' LIMIT 1;")
  curl -s -o /dev/null -w "%{http_code}\n" \
    "http://localhost:18081/api/v1/versions/$H8/diff?compareWith=$H8" -H "Authorization: Bearer $REV"   # 500
  ```
- **영향**: 인증된 REVIEWER/WORKER 누구나 유발 가능한 미처리 500. 응답 본문에 내부 정보는 노출되지 않으나(GlobalExceptionHandler 가 일반화) 오류 처리 규약 위반이며, 신고 이력이 쌓일수록 노출면이 커진다. 데이터 손상·권한 우회는 없음.
- **수정 방향(제안)**: `diff` 에서 `getDataSrcSn() == null` 인 버전을 **비교 대상 아님**으로 판단해 `INVALID_INPUT(400)` 또는 `NOT_FOUND(404)` 로 조기 반환. 방어적으로 `LabelAccessGuard.verifyAndGet` 진입부에도 `srcSn == null` 가드를 추가.

### [D-ISSUE-27] TC-DIFF-005 관련 — `findByHashOrThrow` 가 다중 매칭 시 `.get(0)` 을 비결정적으로 선택
- **심각도**: LOW
- **기대 동작(기대효과)**: 해시로 버전을 특정할 때 결과가 결정적이어야 한다.
- **현재 동작(이슈 내용)**: `VersionService.java:560-566`
  ```java
  List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
  if (matches.isEmpty()) { throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage); }
  return matches.get(0);
  ```
  UNIQUE 제약은 `(DATA_SRC_SN, VERSION_HASH)` 복합(`uk_ls_label_version_src_hash`)이므로 **서로 다른 프레임이 같은 해시를 가질 수 있다**. `findByVersionHash` 는 정렬 없이 리스트를 반환하고 `.get(0)` 은 DB 반환 순서에 의존한다.
- **재현/확인 경로**: 현재 데이터에는 프레임 간 동일 해시가 없어 실동작 재현 불가(스냅샷 payload 에 `srcSn`/`frameNo` 가 포함되어 충돌 확률이 매우 낮음). 스키마·코드 정적 근거만 존재.
  ```sql
  SELECT version_hash, count(*) FROM ls_label_version GROUP BY 1 HAVING count(*) > 1;  -- 현재 0행
  ```
- **영향**: 인가는 선택된 버전 기준으로 재검증되므로 **권한 우회는 아니다**. 다만 diff 대상이 비결정적으로 뒤바뀔 수 있어 결과 재현성이 떨어진다. 실현 가능성 낮음.
- **수정 방향(제안)**: diff API 가 srcSn 컨텍스트를 받도록 시그니처를 확장해 `findByDataSrcSnAndVersionHash` 를 쓰거나, 최소한 정렬 기준(예: `ORDER BY LBL_VERSION_SN`)을 명시.

### [D-ISSUE-28] TC-DIFF-020 — `isCommittable` 이 프로덕션에서 참조되지 않는 dead code
- **심각도**: LOW
- **기대 동작(기대효과)**: PORTAL 채널 배제 가드가 실제 실행 경로에서 동작한다.
- **현재 동작(이슈 내용)**: `VersionService.java:814-816` `public static boolean isCommittable(TokenClaims actor)` 의 참조처는 `VersionServiceTest.java:315-317`(자기 자신을 검증하는 단위 테스트) **뿐**이다. `grep -rn "isCommittable" backend/src frontend/src` 결과 프로덕션 참조 0건. 실제 PORTAL 차단은 `VersionController` 의 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 가 수행한다.
- **재현/확인 경로**:
  ```bash
  grep -rn "isCommittable" backend/src frontend/src   # 테스트 4줄 + 선언 1줄만
  # 실차단은 정상 동작:
  curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:18081/api/v1/frames/421/versions" -H "Authorization: Bearer $PORTAL_TOKEN"  # 403
  ```
- **영향**: 기능 결함 없음(차단은 실제로 동작). 다만 테스트가 "PORTAL 배제 검증"이라는 이름으로 **실행되지 않는 코드**를 검증하고 있어, 컨트롤러의 `@PreAuthorize` 가 제거·완화돼도 이 테스트는 통과한다(회귀 방어 공백).
- **수정 방향(제안)**: `isCommittable` 을 제거하고 PORTAL 배제 회귀 테스트를 `VersionControllerTest` 의 `@WithMockUser`/MockMvc 403 검증으로 이관하거나, 서비스 진입부에서 실제로 호출하도록 배선.

---

## 반증 시도 요약 (확증편향 차단 기록)

| 반증 포인트 | 시도한 반증 | 결과 |
|------------|-----------|------|
| 스냅샷이 라벨 저장 시점에도 새는가 | 승인 상태에서 `PUT /v1/frames/421/labels` 실행 후 버전 행 수 비교 | **누출 없음** (1행 → 1행). 정책 준수 |
| 스냅샷이 영상 전체를 담는가 | rawSn=26 의 16개 payload `items` 개수를 프레임별 `ls_data_lbl` 실카운트와 1:1 대조, 합계 대조 | **131 = 131 완전 일치**, 프레임 누락 0 |
| 해시가 결정적인가 | 저장 payload 를 추출해 `shasum -a 256` 재계산 | **완전 일치**. 다만 이 결정성이 D-ISSUE-21(ROLLBACK 분기 도달 불가)의 원인이기도 함 |
| 동일 payload 재스냅샷이 동일 해시인가 | 미변경 11프레임 재승인 | 새 행 미생성(멱등) — 해시 동일 확인 |
| 롤백이 이력을 파괴하는가 | 롤백 후 v1·v2 행 잔존 여부 확인 | **원본 스냅샷 보존됨**(16/37/38 모두 잔존, `actvtn_yn` 만 전환). 이력 파괴 없음 |
| 롤백이 실제로 라벨을 복원하는가 | 좌표 단위 비교 | **정확 복원**. 단 식별자·AI메타·trackId 는 유실(D-ISSUE-22/23) |
| 롤백 round-trip 이 안정적인가 | v1→수정→v2→롤백→재승인 v3 후 diff(v1,v3) | **불안정** — 좌표 동일한데 8건 오분류 (D-ISSUE-22) |
| 멱등 롤백이 진짜 no-op 인가 | active 로 롤백 후 `lbl_sn` 비교 | **no-op 아님** — 라벨 재작성됨 (D-ISSUE-24) |
| 순서 뒤바뀐 diff(v2→v1) | 역방향 호출 | 정확히 반전 (REMOVED↔ADDED) — 정상 |
| 동일 버전 self-diff | v1 vs v1 | 200 `[]` — 정상 |
| 타인 영상 버전 조회·롤백(CWE-639) | 미배정 WORKER(userNo=9999) 로 list/diff/rollback | 전부 **403** — 방어 정상 |
| 채널 격리 | PORTAL 토큰으로 3개 엔드포인트 | 전부 **403** — 방어 정상 |
| 미인증 접근 | 토큰 없이 list/rollback | **401** — 정상 |
| 라벨 0건 프레임 승인 시 스냅샷 | rawSn=13 의 라벨 0 프레임 7개 | 버전 0행 — 정상 스킵 |
| PII 로그 누출 | 전체 로그에서 좌표·payload·label 본문 grep | **0건** — 정상 |
| 기존 테스트가 실동작을 보장하는가 | 롤백 테스트 픽스처의 해시 실체 확인 | **위양성 2건 발견** (D-ISSUE-21) |

---

## 환경 변경 및 원복 (공유 스택 — 다른 에이전트 참고용)

**rawSn=26 은 일절 변경하지 않았다** (읽기 전용 SQL·GET 만 수행). 후속 E 클러스터 참조 데이터 무결.

변경한 대상: **rawSn=19 / srcSn=421** (프레임 12개 중 1개)

| 항목 | 원래 상태 | 현재 상태 | 원복 |
|------|----------|----------|:--:|
| `ls_raw_data_status.data_stts_cd` (rawSn=19) | `APPROVED` | `APPROVED` | 완료 |
| active 버전 (srcSn=421) | `lbl_version_sn=16` (ver_no=1) | `lbl_version_sn=16` (ver_no=1) | 완료 |
| 라벨 내용 (srcSn=421) | person×1 + car×3, 특정 좌표 | **좌표·라벨명·타입·labelId 전부 동일** | 완료(내용 기준) |
| `ls_label_version` 행 (srcSn=421) | 1행 (16) | **3행 (16, 37, 38)** — 37·38 은 `actvtn_yn=N` | ✗ **원복 불가** |
| `ls_data_lbl.lbl_sn` (srcSn=421) | 176, 177, 178, 179 | **400, 401, 402, 403** | ✗ **원복 불가** |
| `ls_task_event_log` (rawSn=19) | 기존 | submit/start/approve **2사이클 추가** | ✗ 원복 불가 |

- `lbl_sn` 재발급과 버전 행 적층은 **본 이슈(D-ISSUE-21/22)의 산물 자체**이며, DB 직접 쓰기가 금지되어 되돌리지 않았다. 라벨 **내용**은 원본과 완전히 동일하므로 rawSn=19 를 참조하는 후속 검증의 라벨 데이터 정합성에는 영향이 없다. 단 **`lbl_sn` 값 자체나 버전 행 수를 하드코딩해 비교하는 검증이 있다면 위 값을 사용**해야 한다.
- 그 외 어떤 파일도 수정하지 않았고, 빌드·테스트도 실행하지 않았다.

---

## 요약

- 총 **34건** / PASS **17** / FAIL **0** / PARTIAL **17** / BLOCKED **0** / N/A **0** / 확인필요 **0**
  - D-4 (14건): PASS 8 · PARTIAL 6
  - D-5 (20건): PASS 9 · PARTIAL 11
- **근거 라인 드리프트: 0건** — TC 표의 `VersionService.java` 인용 34개 라인 범위를 전건 대조했고 모두 현재 코드와 일치했다.
- **self-fill 결함: 0건** — 모든 PASS 는 실제 HTTP 응답 또는 DB 조회 결과를 근거로 한다.
- **테스트 위양성 결함: 2건** — `VersionServiceTest#assignedWorkerRollbackRestoresSnapshot`, `#reviewerRollbackCreatesNewVersion` (D-ISSUE-21). BE 3013건 GREEN 이지만 이 2건은 프로덕션 불가능 상태를 픽스처로 만들어 통과한다.
- PARTIAL 17건 중 **실동작 미검증 사유 분류**: 내부 전용 호출이라 HTTP 진입점 없음 3건(V-005/006/007) · 실데이터 부재(SKELETON 스냅샷 0건, 빈/손상 payload 행 없음, 1MB 초과 payload 없음) 7건 · DB 쓰기 금지로 상태 주입 불가(작업락) 1건 · 환경 설정으로 소비자 비활성(control-notify) 2건 · 동시성 실부하 미유발 2건 · 기대값 일부 불일치 2건(DIFF-008/014, 이슈로 분리).
- **신규 이슈 8건**: HIGH 3 (D-ISSUE-21/22/23) · MEDIUM 3 (D-ISSUE-24/25/26) · LOW 2 (D-ISSUE-27/28).
- 핵심 결론: **스냅샷 생성 정책·완전성·해시 결정성·인가·PII 방어는 실동작으로 전부 건전**하다. 결함은 **롤백 경로에 집중**되어 있으며, 공통 뿌리는 `replaceFrameLabels` 가 "라벨 행을 통째로 지우고 새로 만든다"는 설계다 — 여기서 식별자 재발급(D-ISSUE-22), AI 메타·trackId 유실(D-ISSUE-23), 멱등 위반(D-ISSUE-24)이 파생되고, 해시 결정성과 맞물려 ROLLBACK 이력 자체가 남지 않는다(D-ISSUE-21).

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

