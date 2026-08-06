# D 클러스터 part2 (D-3·D-4) 2차 검증 결과

- 검증 일시: 2026-07-31 03:36~03:45 KST
- 대상: `docs/test-cases/D-review-version-notify.md` `## D-3. 검수 배정 (TC-ASSIGN)` 26건 + `## D-4. 버전관리 스냅샷 (TC-VERSION)` 17건
- 실행 형상: backend `localhost:18081` (HEAD `ca3c712b` 재빌드본 `bdc64ea2ac26`) · postgres `:5432` 스키마 `public` · mock-server `:9400`
- 방법: 실제 HTTP 호출 + DB 행 변화 실측(동시성은 실제 병렬 요청) → 실동작 불가 항목만 `file:line` 정적 대조 + `backend/src/test/` 커버 대조(테스트 실행 안 함, `test-baseline.md` 4,367 tests / 실패 0 기준 원용)
- 파일 수정 0건(본 파일 1개만 신규 작성) · 빌드/테스트 실행 0건 · **backend 컨테이너 재기동 0건**
- 보호 데이터 무손상 확인(작업 종료 시점): rawSn **126** = `LS_DATA_RAW.COMPLETED/Y` · `LS_RAW_DATA_STATUS.APPROVED` · `V_COMPLETED_VIDEO` 1행 · `LS_LABEL_VERSION` 3행 / rawSn **133** = `DE_IDENT_YN='F'` 유지 / rawSn **134** 미변경

## 집계

| 구분 | 대상 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| D-3 (TC-ASSIGN) | 26 | 26 | 0 | 0 | 0 | 0 | 0 |
| D-4 (TC-VERSION) | 15 | 15 | 0 | 0 | 0 | **2** | 0 |
| **합계** | **41** | **41** | 0 | 0 | 0 | 2 | 0 |

> ⚠ D-4 는 표 행 17건 중 `~~TC-VERSION-011~~`·`~~TC-VERSION-012~~` **2건이 취소선 폐기**(2026-07-30, `snapshotDeidentReport` 제거)라 **검증 대상 15건**이다. 폐기 2건은 위 표에서 `N/A` 로만 표기하고 PASS/FAIL 집계에서 제외했다.
> ⚠ 전건 PASS 이나 **카탈로그에 케이스가 없는 잔존 결함 1건(D-ISSUE-21, HIGH)** 과 **1차 미해소 이월 2건(D-ISSUE-05 · D-ISSUE-07)** 이 있다. 아래 «이슈 상세» 참조.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **D-ISSUE-01** | `POST /v1/assignments` 가 APPROVED 영상을 무검증 ASSIGNED 강등 | `rejectApprovedTargets`(`AssignmentService.java:141-152`) 신설. seed APPROVED(20012)·실데이터 APPROVED(126) 둘 다 **409 `ASSIGNMENT_ALREADY_COMPLETED`**, 상태 `APPROVED` 유지, `V_COMPLETED_VIDEO` 에 126 잔존(1행) | **해소** |
| — (D-ISSUE-01 잔여) | APPROVED 외 상태는 여전히 무검증 | `PENDING`(146)·`IN_REVIEW`(155) 에 신규 배정 → **201 + ASSIGNED 강등**, 검수 목록에서 소실 | **미해소 → D-ISSUE-21 신규 기표** |
| **D-ISSUE-02** | 동시 재배정 4병렬 전부 200, `@Version` 부재 → 이력 4중복 | `LS_TASK_ASSIGNMENT.VER`(`LsTaskAssignment.java:58-59`, V134) + `reassign` 내 `authrtRepository.flush()` + `OptimisticLockingFailureException`→409 국소 변환. 4병렬 **1×200 / 3×409**, `LS_TASK_ASSIGN_HISTORY` **+1행**, `LS_TASK_EVENT_LOG` REASSIGN **+1행** | **해소** |
| **TC-ASSIGN-014** | 위 케이스 1차 FAIL | 8병렬 스트레스에서도 **1×200 / 6×409 / 1×400**, 이력 +1행만 | **해소(PASS)** |
| **D-ISSUE-05** | IN_REVIEW 상태 재배정 허용 | 가드가 여전히 `STTS_APPROVED` 단일값(`AssignmentService.java:201-206`). IN_REVIEW(155) 재배정 → **200 성공**, 상태 `IN_REVIEW` 유지 | **미해소(현행 고정)** — TC-ASSIGN-026 이 "현행 허용"을 기대값으로 삼으므로 케이스는 PASS |
| **D-ISSUE-07**(참고) | 상태행 PK 충돌을 "동일 작업자 중복 배정" 으로 오안내 | 서로 다른 작업자(2001/2002) 동시 배정 → 패자 409 `"이미 동일 작업자에게 배정된 영상이 있습니다."` | **미해소(LOW, 메시지 품질)** |
| **D-ISSUE-25**(참고) | 비식별 신고 스냅샷 write-only | `snapshotDeidentReport` 제거(`VersionService.java:303-307` 사유 주석). `LS_LABEL_VERSION` 에 `DATA_SRC_SN IS NULL` 행 **0건** | **해소 → TC-VERSION-011/012 폐기 정당** |

## ★동시성 실측

| # | 시나리오 | 병렬수 | 응답 분포 | DB 결과 | 판정 |
|---|---|:--:|---|---|:--:|
| C1 | 재배정 동시 요청 (`PATCH /v1/assignments/55`, 2001→2002) | 4 | `200 ×1 / 409 ×3` | `VER 1→2`, `LS_TASK_ASSIGN_HISTORY` 1→**2**(+1), `LS_TASK_EVENT_LOG` REASSIGN 1→**2**(+1) | PASS |
| C2 | 동일 시나리오 스트레스 (2002→2001) | 8 | `200 ×1 / 409 ×6 / 400 ×1` | `VER 2→3`, 이력 2→**3**(+1), 이벤트 2→**3**(+1) | PASS |
| C3 | 상태행 미존재 영상(150)에 서로 다른 작업자 동시 배정 | 2 | `201 ×1 / 409 ×1` | `LS_TASK_ASSIGNMENT` 1행, `LS_RAW_DATA_STATUS(150)` 1행 ASSIGNED | PASS |
| C4 | `assign` vs `approve` 경합 (152, 3라운드 반복) | 2×3 | 매 라운드 `approve=200 / assign=409` | 상태 `APPROVED` 유지, **500 없음**(`OptimisticLockingFailure`→CONFLICT 국소 변환 `AssignmentService.java:100-109`) | PASS |
| C5 | `reassign` vs `approve` 경합 (152, 3라운드) | 2×3 | R1 `reassign=200 / approve=200` · R2·R3 `409 / 409` | 3라운드 모두 **"승인 후 재배정" 미발생**, 이력 1행 고정. R1 은 재배정이 먼저 커밋된 허용 순서 | PASS |
| C6 | 동일 영상 동시 승인 (`POST /v1/reviews/153/approve`) | 4 | `200 ×1 / 409 ×3` | `LS_LABEL_VERSION` **1행만** 신규(41), 중복 스냅샷 0 | PASS |

> C2 의 `400` 1건은 승자 커밋 뒤 값을 읽은 요청이 "현재 배정된 작업자와 동일합니다"(`:211-213`)로 거부된 것으로, 이력·이벤트를 남기지 않아 직렬화 결과에는 영향이 없다. 다만 **동일 경합의 응답 코드가 타이밍에 따라 409/400 으로 갈린다**(FE 재시도 분기 관점 LOW).

## D-3 결과표 (TC-ASSIGN)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-ASSIGN-001 | 배정 정상 | PASS | [실동작] `POST /v1/assignments {workerId:2002,rawDataIds:[155]}` → **201**. `LS_TASK_ASSIGNMENT(55,155,2002,LABELER)` INSERT · `LS_RAW_DATA_STATUS(155)=ASSIGNED` 생성 · `LS_TASK_EVENT_LOG(30,ASSIGN,actor=1001,subject=2002)` | 3부수효과 모두 확인 |
| TC-ASSIGN-002 | 배정 — 비REVIEWER | PASS | [실동작] WORKER 토큰 → **403 FORBIDDEN** | PORTAL 토큰도 403 |
| TC-ASSIGN-003 | 배정 — 미인증 actor null(NPE 가드) | PASS | [실동작] 무토큰 → **401 UNAUTHORIZED**(NPE·500 없음) / [정적] `AssignmentService.java:618-628` null 선가드 | 테스트 `AssignmentServiceTest:344 requireReviewer_actor가_null이면_UNAUTHORIZED` |
| TC-ASSIGN-004 | 배정 — 존재하지 않는 작업자 | PASS | [실동작] `workerId=999999` → **400 INVALID_INPUT** "존재하지 않는 작업자입니다." | 역할 검증은 여전히 없음(1차 D-ISSUE-06, 스키마 제약) |
| TC-ASSIGN-005 | 배정 — 존재하지 않는 검수자 | PASS | [실동작] `reviewerId=999999` → **400 INVALID_INPUT** | |
| TC-ASSIGN-006 | 배정 — 중복 배정(UK 충돌) | PASS | [실동작] 동일 (155,2002) 재배정 → **409 CONFLICT** | |
| TC-ASSIGN-007 | 배정 + REVIEWER 동시등록 | PASS | [실동작] `reviewerId:1002` → 201, `LS_TASK_ASSIGNMENT(57 LABELER)` + `(58,154,1002,REVIEWER)` 2행 | 응답 `reviewerId=1002` 반영 |
| TC-ASSIGN-008 | 배정 — REVIEWER 중복 등록 skip | PASS | [실동작] 동일 reviewer 재전송 → 201, REVIEWER 행 **58 그대로 1행**, 신규 worker 행(59) 정상 생성 | `assignReviewers` 격리 try-catch 동작 |
| TC-ASSIGN-009 | 재배정 정상 | PASS | [실동작] `PATCH /assignments/55 {2001}` → 200. `LS_TASK_ASSIGN_HISTORY(1, authrt 55, prev 2002→new 2001)` · `LS_TASK_EVENT_LOG(34,REASSIGN,prev=2002,subject=2001)` · `user_no 2002→2001` | |
| TC-ASSIGN-010 | 재배정 — 완료(APPROVED) 차단 | PASS | [실동작] seed APPROVED(20012/assignment 22)·실데이터 APPROVED(126/assignment 46) 모두 **409 `ASSIGNMENT_ALREADY_COMPLETED`** | |
| TC-ASSIGN-011 | 재배정 — 배정 미존재 | PASS | [실동작] `/assignments/999999` → **404 NOT_FOUND** | |
| TC-ASSIGN-012 | 재배정 — 동일 작업자 | PASS | [실동작] → **400 INVALID_INPUT** | |
| TC-ASSIGN-013 | 재배정 — 새 작업자 이미 배정(사전) | PASS | [실동작] 154 에 2001·2002 공존 상태에서 57→2001 → **409 CONFLICT** "선택한 작업자는 이미 해당 영상에 배정되어 있습니다." | |
| TC-ASSIGN-014 | **재배정 동시성 — @Version 직렬화** | PASS | [실동작] C1(4병렬)·C2(8병렬) — 성공 1건 + 나머지 409, `LS_TASK_ASSIGN_HISTORY` **+1행만** / [정적] `LsTaskAssignment.java:58-59` `@Version VER` | **1차 FAIL → 해소**. IT `AssignmentReassignConcurrencyIT:82` |
| TC-ASSIGN-015 | 배정이력 — WORKER 본인만(IDOR) | PASS | [실동작] WORKER(2001)가 타인 배정(57=2002) 이력 조회 → **403** / 본인(55) → 200 | |
| TC-ASSIGN-016 | 배정이력 — REVIEWER 전체 | PASS | [실동작] REVIEWER → 200, `occurredAt` **ASC**(evnt 30→34→36) / [정적] `findByUserNoIn` 단일 IN 배치(`:293-296`) | |
| TC-ASSIGN-017 | 배정이력 — 잘못된 ID | PASS | [실동작] `0`·`-1` → **400** "잘못된 배정 ID 입니다." · `abc` → 400(타입 변환) | |
| TC-ASSIGN-018 | 배정목록 — WORKER 본인만(param 무시) | PASS | [실동작] WORKER 가 `workerId=2002` 지정 → 응답 workerId 전량 **2001**, total 41(무지정과 동일) | 403 아닌 **무시** 계약 유지 |
| TC-ASSIGN-019 | 배정목록 — REVIEWER 필터/전체 | PASS | [실동작] 전체 48건(전부 `LABELER`) / `workerId=2002` 필터 7건 | REVIEWER 타입 행은 목록에서 제외 |
| TC-ASSIGN-020 | 배정목록 — 기타 역할/미인증 | PASS | [실동작] PORTAL → **403** / 무토큰 → **401** (목록·이력 양쪽 동일) | |
| TC-ASSIGN-021 | 상태 upsert 동시 INSERT 충돌 | PASS | [실동작] C3 — 상태행 없는 150 에 서로 다른 작업자 동시 배정 → **201/409**, 재시도 가능 | ⚠ 409 메시지가 "동일 작업자 중복" 으로 **오안내**(1차 D-ISSUE-07 미해소, LOW) |
| TC-ASSIGN-022 | **APPROVED 영상 신규 배정 차단** | PASS | [실동작] 20012·126 모두 **409 `ASSIGNMENT_ALREADY_COMPLETED`**, 상태 APPROVED 유지, `V_COMPLETED_VIDEO(126)` 1행 잔존 / [정적] `AssignmentService.java:141-152` | **D-ISSUE-01 해소.** ⚠ 잔여 갭 → **D-ISSUE-21** |
| TC-ASSIGN-023 | 배정 부분성공 금지 | PASS | [실동작] `[144, 20012]` → **409**, `LS_TASK_ASSIGNMENT(144)` 0행 · `LS_RAW_DATA_STATUS(144)` 0행 / [정적] `findByRawDataIdIn` 단일 IN 1회 | 전체 실패 정책 확인 |
| TC-ASSIGN-024 | 배정 중 승인 경합 → 409(500 아님) | PASS | [실동작] C4 3라운드 전부 `assign=409 CONFLICT`("다른 사용자가 먼저 …"), 500 0건 / [정적] `:100-109` 국소 catch | IT `AssignmentOptimisticLockConflictIT:54` |
| TC-ASSIGN-025 | 재배정 상태행 공유잠금(FOR SHARE) | PASS | [실동작] C5 3라운드 — "승인 후 재배정" 미발생 / [정적] `LsRawDataStatusRepository.findByRawDataIdForShare` `@Lock(PESSIMISTIC_READ)` | IT `AssignmentReassignConcurrencyIT:171` |
| TC-ASSIGN-026 | 배정 — IN_REVIEW 재배정 허용 여부 | PASS | [실동작] 155 를 `IN_REVIEW` 로 만든 뒤 `PATCH /assignments/55` → **200**, 상태 `IN_REVIEW` 유지 / [정적] 가드가 `STTS_APPROVED` 단일값(`:201-206`) | 기대값 = "현행 허용" 이므로 PASS. **D-ISSUE-05 미해소 유지 — 정책 확정 필요** |

## D-4 결과표 (TC-VERSION)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-VERSION-001 | 승인 스냅샷 생성 | PASS | [실동작] rawSn 153 승인 → `LS_LABEL_VERSION` **2행**(`SAVE_REASON_CD=APPROVED`, `ACTVTN_YN=Y`, `VERSION_HASH` 길이 **64**=SHA-256 hex). 참조 126 은 3행(src 65/66/67) | 승인 시점 자동 생성 확인 |
| TC-VERSION-002 | 라벨 없는 프레임 스킵 | PASS | [실동작] 153 프레임 3개 중 라벨 0건 프레임(src 127) 미생성 → 로그 `created=2 skipped=0 frames=3`. 별도 실데이터 136(12프레임/라벨 49) → 버전 **10행**, 146(3프레임) → **2행** | 빈 스냅샷 0건 |
| TC-VERSION-003 | 멱등 — 무변경 재승인 | PASS | [실동작] 153 재제출→재시작→재승인(라벨 무수정) → 로그 `created=0 skipped=0`, `LS_LABEL_VERSION` 행 수 불변(37·38 그대로) | |
| TC-VERSION-004 | 재승인 시 변경분 새 버전 적층 | PASS | [실동작] src 129 에 라벨 1건 추가 후 재승인 → 신규 `verNo=2 ACTVTN_YN=Y`(39), 기존 `verNo=1 → N`(38). 무수정 프레임(128)은 active 유지 | |
| TC-VERSION-005 | 프레임 없음 | PASS | [실동작] 프레임 0건 영상 155 승인(`noLabelConfirmed=true`) → 200, `LS_LABEL_VERSION(155)` **0행**, 예외 0. export 는 `no frames — skip` | `CommitResult.EMPTY` 경로 |
| TC-VERSION-006 | 영상 미존재 | PASS | [정적] `VersionService.java:166-167` `orElseThrow(NOT_FOUND)` | 테스트 `VersionServiceTest:303` |
| TC-VERSION-007 | rawSn/actor null 가드 | PASS | [정적] `:160-165` — `rawSn==null`→`IllegalArgumentException`, `actor==null`→`UNAUTHORIZED` | HTTP 진입점에서는 401 선차단 |
| TC-VERSION-008 | 대용량 라벨 1MB 초과 단순화 후 승인 성공 | PASS | [정적] `:1041-1056` — 1MB 초과 시 `simplifyPolygons` 후 재직렬화, 한도 10MB(`:101`) | 테스트 `VersionServiceTest:240 BE_4_대용량_폴리곤…` |
| TC-VERSION-009 | 10MB 초과 프레임 SKIPPED | PASS | [정적] `:271-284` — `CustomException` catch → `FrameSnapshotOutcome.SKIPPED`(해당 프레임만), `ReviewService.java:498-501` 승인 유지 + WARN | ⚠ `skipped>0` 전용 테스트 부재(상류 `MAX_POINTS_PER_LABEL` 상한으로 실질 도달 불가) |
| TC-VERSION-010 | 동시 승인 스냅샷 직렬화(Race) | PASS | [실동작] C6 4병렬 approve → `200×1 / 409×3`, 신규 버전 **1행**(41) / [정적] `:288-289` `findActiveForUpdate` 비관적 잠금 | 중복 스냅샷 0 |
| ~~TC-VERSION-011~~ | ~~비식별 신고 스냅샷~~ | N/A | 폐기(2026-07-30). `snapshotDeidentReport` 제거 확인(`:303-307` 사유 주석) | 집계 제외 |
| ~~TC-VERSION-012~~ | ~~비식별 신고 — 라벨 0 스킵~~ | N/A | 폐기(2026-07-30). 메서드 부재 | 집계 제외 |
| TC-VERSION-013 | 버전목록 조회 IDOR | PASS | [실동작] 미배정 WORKER(파생 129의 src 68) → **403** "본인에게 배정되지 않은 영상입니다." / PORTAL → 403 / 무토큰 → 401 / 배정 WORKER·REVIEWER → 200 + `isCurrent:true` | `accessGuard.verifyAccess` |
| TC-VERSION-014 | 스냅샷에 PII/본문 로그 미출력 | PASS | [실동작] 구동 전체 `[Version]` 로그 전수 확인 — `rawSn/srcSn/frames/created/skipped/actor/version` 식별자·건수만. 좌표·라벨명·페이로드 0건 | CWE-359 |
| TC-VERSION-015 | **버전목록은 신고 구간에도 200** | PASS | [실동작] 신고 OPEN 영상 133 의 src 78 → `GET /v1/frames/78/versions` **200**(`data:[]`). 동일 프레임 `GET /labels` 는 **412** 로 대조 확인. 응답 `VersionItem` = commitSha/shortHash/authorName/message/committedAt/isCurrent — `LABEL_PAYLOAD` 미포함 | 게이트 미적용이 의도(`:309-314`) |
| TC-VERSION-016 | 레거시 rawSn 스코프 스냅샷 잔존 행 | PASS | [실동작] `select count(*) … where data_src_sn is null` → **0행**(그중 `DEIDENT_REPORT` 0) / [정적] `listVersions` 는 `findByDataSrcSnOrderByRegDtDesc`(프레임 스코프)만 사용 | 신규 적재 경로 부재 |
| TC-VERSION-017 | 승인 스냅샷 원자성 | PASS | [정적] `ReviewService.java:467-518` 단일 `@Transactional` 안에서 `transitionTo` → `commitApproved`(`:495`) → `materialize`(`:513`) | IT `DatasetMaterializeApproveRollbackIT:129`, 단위 `ReviewServiceEventPublishTest:199` |

### 2계층 분리(작업 임시저장 vs 학습데이터 버전) 부가 실측

- `PUT /v1/frames/128/labels`(라벨 추가 저장) → 200, `LS_LABEL_VERSION(153)` **0행 유지**, `LS_DATA_LBL_HSTRY` 저장이벤트만 적재. 이후 승인에서 비로소 2행 생성 → **CLAUDE.md 2계층 분리 준수**(테스트 `VersionServiceTest:321` 회귀가드와 일치).
- 해시 결정성 반증: 동일 내용을 **역순으로** 재전송해도 `id` 를 함께 보내면 `LBL_SN` 이 보존되고(438~442 불변) 저장 자체가 no-op(이력 미생성) → 스냅샷 정렬(`Comparator.comparing(LsDataLbl::getLblSn)`, `:183-184`)로 해시가 흔들리지 않음.
  ⚠ 단, 요청에서 `id` 를 **생략**하면 full-replace 계약대로 전량 삭제→재삽입되어 `LBL_SN` 이 재발급되고 결과적으로 내용이 같아도 해시가 달라진다(검증 중 최초 관측 → 클라이언트 요청 결함으로 판별, **제품 결함 아님**). FE 는 `id` 를 echo 하므로 실사용 경로에는 영향 없음.

## 근거 드리프트

| 범위 | 카탈로그 표기 | 실제 | 성격 |
|---|---|---|---|
| **D-3 전 26건** | `AssignmentService.java:57-114`, `:59`, `:62-64`, `:65-67`, `:86-88`, `:102-104,150-175`, `:161-174`, `:177-244`, `:190-195`, `:182-183`, `:200-202`, `:204-217`, `:225-241`, `:264-270`, `:257-303`, `:258-260`, `:305-`, `:69,130-141`, `:89-98`, `:187-195` | 각각 `:68-125`, `:70`(본체 `:618-628`), `:73-75`, `:76-78`, `:97-99`, `:113-115,161-186`, `:172-185`, `:188-255`, `:201-206`, `:193-194`, `:211-213`, `:215-228`, `:236-252`, `:268-281`, `:268-314`, `:269-271`, `:330-`, `:80,141-152`, `:100-109`, `:198-206` | **일괄 +10~12행 오프셋**(파일 상단 `MAX_EVENT_TYPE_OPTIONS` 상수·주석 블록 추가분). 참조 메서드·의미는 전부 일치 — 라인만 갱신 필요 |
| TC-ASSIGN-014 | "V134 `LS_TASK_ASSIGNMENT.VER`" | 실 DDL `ver bigint not null default 0` 존재, 엔티티 `LsTaskAssignment.java:58-59` `@Version` | 정합(드리프트 아님) |
| **D-4 전 15건** | `VersionService.java:159-207` / `:190-197` / `:288-300` / `:170-172,216-221` / `:167` / `:160-165` / `:90-101,1041-` / `:264-283` / `:315-326` / `:205-206` / `:309-326` / `:303-307,317` / `ReviewService.java:493-513` | 순서대로 `:158-208` / `:190-197` / `:288-300` / `:170-173,217` / `:166-167` / `:160-165` / `:90-101,1041-1056` / `:271-284` / `:315-326` / `:205-206` / `:309-326` / `:303-307,317` / `:493-513` | **정합**(±1~7행 이내). 갱신 불필요 |

## 이슈 상세

### [D-ISSUE-21] TC-ASSIGN-022(부가) — 신규 배정이 `PENDING`(검수 대기)·`IN_REVIEW`(검수 중) 영상을 무검증으로 `ASSIGNED` 로 강등해 검수 큐에서 사라지게 한다

- **심각도**: HIGH
- **기대 동작(기대효과)**: D-ISSUE-01 의 근본 처방은 "`markAssigned()`(검증 없는 setter)를 상태머신 경유 단일 진입점으로 좁혀 배치·검수·배정 3경로가 같은 규칙을 공유"하는 것이었다(1차 ISSUES.md 수정 방향). 작업자가 이미 **검수 제출을 마친(PENDING)** 영상이나 검수자가 **검수를 시작한(IN_REVIEW)** 영상은, 배정 조작만으로 그 워크플로 상태를 되돌릴 수 없어야 한다. 되돌린다면 최소한 별도 이벤트(예: 검수 취소)로 감사에 남아야 한다.
- **현재 동작(이슈 내용)**: 이번 회차에 신설된 가드는 **APPROVED 한 값만** 본다.
  `backend/src/main/java/kr/co/cudo/authoring/assignment/service/AssignmentService.java:141-152`
  ```java
  private void rejectApprovedTargets(List<Long> rawDataIds) {
      ...
      boolean anyApproved = dataSttsRepository.findByRawDataIdIn(rawDataIds).stream()
              .anyMatch(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()));   // ← APPROVED 만
  ```
  가드를 통과하면 상태 전이는 여전히 검증 없는 setter 다.
  `AssignmentService.java:88-89`
  ```java
  LsRawDataStatus stts = upsertDataStts(rawDataId);
  stts.markAssigned();          // 현재 상태 무관 무조건 ASSIGNED
  ```
  `LsRawDataStatus.java:83-86`
  ```java
  public void markAssigned() {
      this.dataSttsCd = STTS_ASSIGNED;   // ReviewStateMachine.verify 미경유
      this.updDt = LocalDateTime.now();
  }
  ```
  **실동작 근거(2026-07-31 03:39 KST)**
  - `PENDING`(검수 대기) 영상 rawSn=146 에 신규 배정 → **201**, `LS_RAW_DATA_STATUS(146)` `PENDING → ASSIGNED`(`VER` 15→16). 직전 `GET /v1/reviews?size=100` 에 노출되던 146 이 **검수 목록에서 소실**(응답 `totalElements=10`, `[155,132,151,128,136,134,126,20013,20012,20011]` — 146 부재).
  - `IN_REVIEW`(검수 진행 중) 영상 rawSn=155 에 신규 배정 → **201**, `IN_REVIEW → ASSIGNED`(`VER` 3→4). 진행 중이던 검수가 배정 조작만으로 리셋.
  - 두 경우 모두 `LS_TASK_EVENT_LOG` 에는 **`ASSIGN` 이벤트만** 남고 상태 강등 흔적이 없다(rawSn 155 이벤트 로그 실측: `ASSIGN` → `REASSIGN` → `REASSIGN` 뿐).
  - (재현 후 146 은 `POST /v1/reviews/146/submit` 으로 `PENDING` 복구 완료)
- **재현/확인 경로**:
  ```bash
  BASE=http://localhost:18081/api
  # 사전: rawSn=X 가 PENDING(검수 제출 완료) 또는 IN_REVIEW
  curl -s -X POST $BASE/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2002,"rawDataIds":[X]}'   # → 201
  ```
  ```sql
  SELECT raw_data_id, data_stts_cd, ver FROM ls_raw_data_status WHERE raw_data_id = X;  -- → ASSIGNED
  SELECT evnt_type_cd FROM ls_task_event_log WHERE raw_data_id = X ORDER BY evnt_id;    -- → ASSIGN 만
  ```
  검수 목록 소실 확인: `curl -s -H "Authorization: Bearer $REV" "$BASE/v1/reviews?page=0&size=100"`
- **영향**: 워크플로 정합성 + 감사 추적. ①작업자가 제출한 검수 건이 **검수자 대기열에서 예고 없이 사라진다**(검수자는 원인을 알 수 없고 이벤트 로그에도 근거가 없다) ②진행 중 검수(IN_REVIEW)가 무효화되어 검수자 작업이 유실된다 ③상태 되돌림이 `ReviewStateMachine` 를 우회하므로 상태머신이 상태 전이의 단일 진실원이 아니게 된다(D-ISSUE-05·B-ISSUE-03 과 동일 뿌리 = "검증 없는 상태 writer"). 보안 축은 CWE-840(비즈니스 로직 검증 누락). REVIEWER 권한만 있으면 일반 배정 UI 조작으로 도달한다.
- **수정 방향(제안)**: ① `rejectApprovedTargets` 의 차단 집합을 `APPROVED` 단일값에서 **검수 진행 상태 집합**(`PENDING`/`IN_REVIEW`/`APPROVED`)으로 넓힐지, ②아니면 강등을 허용하되 `ReviewStateMachine.verify(current, ASSIGNED)` 를 경유시키고 별도 이벤트(`REVIEW_CANCELLED` 등)를 남길지 **정책 확정**이 먼저다. 어느 쪽이든 `markAssigned()` 를 상태머신 경유 단일 진입점으로 좁히는 것이 근본 처방이며, 이는 재배정 축의 D-ISSUE-05 와 같은 결정에 묶어야 한다(두 경로가 다른 규칙을 갖는 현행이 비대칭의 원인). ⚠ **구현하지 않는다.**

### [D-ISSUE-22] TC-ASSIGN-021(부가) — 상태행 PK 충돌을 "동일 작업자 중복 배정" 으로 계속 오안내 (1차 D-ISSUE-07 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 409 메시지는 실제 충돌 원인을 반영해야 한다(운영 진단성). 서로 다른 작업자를 동시에 배정해 `LS_RAW_DATA_STATUS` PK 가 충돌한 경우와, 같은 작업자를 두 번 배정해 `LS_TASK_ASSIGNMENT` UK 가 충돌한 경우는 다른 안내여야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:97-99` 의 catch 가 두 원인을 한 메시지로 뭉갠다.
  ```java
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  ```
  실측(C3): 상태행이 없는 rawSn=150 에 **서로 다른** 작업자(2001/2002)를 동시 배정 → 패자 응답 `409 {"message":"이미 동일 작업자에게 배정된 영상이 있습니다."}`. 실제 충돌은 `upsertDataStts`(`:613-616`)의 PK INSERT 경합이고 작업자 중복이 아니다.
- **재현/확인 경로**:
  ```bash
  for w in 2001 2002; do curl -s -o /dev/null -w "%{http_code}\n" -X POST $BASE/v1/assignments \
    -H "Authorization: Bearer $REV" -H 'Content-Type: application/json' \
    -d "{\"workerId\":$w,\"rawDataIds\":[<상태행 없는 rawSn>]}" & done; wait
  ```
- **영향**: 운영·지원 진단 혼선만 있고 데이터 영향은 없다(재시도하면 성공). 1차 D-ISSUE-07 과 동일 건이며 이번 회차에도 변화 없음.
- **수정 방향(제안)**: 제약명(PK vs UK)으로 메시지를 분기하거나, 상태행 upsert 를 별도 try-catch 로 분리해 "일시적 충돌 — 재시도해 주세요" 로 안내. ⚠ **구현하지 않는다.**

### [D-ISSUE-23] TC-ASSIGN-014(부가) — 동일 재배정 경합의 응답 코드가 타이밍에 따라 409/400 으로 갈린다

- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 원인(다른 요청이 먼저 재배정 성공)으로 거부되는 요청은 클라이언트가 동일하게 분기할 수 있도록 일관된 상태코드를 받아야 한다. 케이스 기대값도 "성공 1건 + 나머지 CONFLICT(409)" 다.
- **현재 동작(이슈 내용)**: 8병렬 스트레스(C2)에서 `200 ×1 / 409 ×6 / **400 ×1**` 이 관측됐다. 400 은 승자 커밋 이후에 `findById` 를 수행한 요청이 동일작업자 가드(`AssignmentService.java:211-213`)에 먼저 걸린 것으로, 낙관적 잠금 충돌(`:244-252`, 409)에 도달하지 못한다.
  ```java
  if (prev.getUserNo().equals(req.workerId())) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "현재 배정된 작업자와 동일합니다.");
  }
  ```
- **재현/확인 경로**: `for i in $(seq 1 8); do curl -s -o /dev/null -w "%{http_code}\n" -X PATCH $BASE/v1/assignments/{id} -H "Authorization: Bearer $REV" -H 'Content-Type: application/json' -d '{"workerId":2001}' & done; wait`
- **영향**: 데이터 정합성 영향 없음(이력·이벤트 모두 +1행 유지, C1/C2 확인). FE 가 409 만 "다시 시도" 로 처리하고 400 은 입력 오류로 처리하면 동일 상황에서 다른 문구가 노출된다. 정보 노출 관점의 위험은 없다.
- **수정 방향(제안)**: 동일작업자 가드를 "요청 시점 값" 이 아니라 "전이 결과" 기준으로 판정하거나(예: 잠금/버전 확인 이후로 이동), 경합 상황에서 나온 400 을 409 로 승격. 혹은 케이스 기대값에 "선착 커밋 후 동일작업자로 수렴한 요청은 400" 을 명시해 계약을 고정. ⚠ **구현하지 않는다.**

---

## 부기 — 검증 중 만들거나 바꾼 데이터 (다른 에이전트 주의)

| rawSn | 변경 | 사유 |
|---|---|---|
| 150·152·153·154·155 | 신규 배정/재배정 생성(`assignment_id` 55·57·59·60·62·63·65·66), 152·153·155 는 **APPROVED** 로 전이 | D-3 배정·D-4 승인 스냅샷 시나리오 구동용. 원래 배정행이 없던 영상만 선택 |
| 153 | `LS_LABEL_VERSION` 5행(37~41), `LS_DATA_LBL(128/129)` LBL_SN 재발급 | 버전 생성/멱등/적층 실증 |
| 146 | 신규 배정으로 `PENDING→ASSIGNED` 강등 → **재제출로 `PENDING` 복구 완료** | D-ISSUE-21 재현. 원상 복구함 |
| 126 · 132 · 133 · 134 · 136 | **변경 없음** | 보호 대상 |

- `LS_TASK_ASSIGN_HISTORY` 는 검증 전 0행 → 검증 후 3행(전부 assignment 55). `LS_TASK_EVENT_LOG` 는 검증 중 ASSIGN/REASSIGN/APPROVE 가 누적됐다.
- 검증 시간대(03:36~03:45)에 다른 에이전트가 동일 스택에서 rollback(srcSn 132·133)·승인(rawSn 156) 을 수행한 로그가 관측됐다. 본 판정 근거는 모두 **본 세션이 직접 발생시킨 요청의 응답·DB 행**으로만 구성했다.
