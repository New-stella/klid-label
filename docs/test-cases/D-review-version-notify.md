# D. 검수 + 버전관리 + 관제 통지/연동 — 테스트 케이스

> 214 케이스 (REVIEW 52 · ASSIGN 35 · STAT 5 · VERSION 17 · DIFF 29 · NOTIFY 53 · MARTVIEW 23) · 계층: unit / integration / security · 우선순위 Critical/High/Med/Low · [← README](README.md) ※ 카운트 = `grep -cE '^\| ~*TC-'`(ID 취소선 폐기 행 포함, 2026-08-05 회차 7(로컬) 에서 200→214, D-3a `TC-ASSIGN-027~035` + D-3b `TC-STAT-001~005` 신설)
> 근거 경로: `backend/src/main/java/kr/co/cudo/authoring/` (뷰는 `backend/src/main/resources/db/migration/`)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 47건 | 63건 | 2건 | ★관제 통지 계약 전면 교체(경로 `notify-completed`/`notify-updated` · 완료 6필드 평면 snake_case · 수정=변경 **파일명** 목록) · 통지 트리거를 승인 이벤트→**export SUCCEEDED 이벤트**로 이동(실패 시 통지 보류·회수 후 재개) · 재export 7경로 + 통지 토글 독립 · 디바운스 **DB 영속화**(V144 크로스노드 1회 flush) · 롤백=**재활성**(적층 폐기)·진짜 no-op · 비식별 신고 스냅샷 경로 제거(D-ISSUE-25) · 검수목록 QueryDSL 재작성(lenient 정렬 폴백·`/summary` KPI) · 승인 라벨0건 게이트 + negative sample · 배정 APPROVED 차단 · 데이터마트 뷰 V133/V137/V138/V139 재정의 · 촬영환경 self-fill 제거 |
| 2 | 2026-08-03 | 2건 | 0건 | 0건 | **2026-08-03 사용자 확정 2건의 파급만 반영**(케이스 신설·폐기 없음). ①**결정 1**(커밋 `80171828`) — 라벨링 화면의 시계열 메타 승인/반려 UI 제거로 **TC-REVIEW-016(승인 시 자동 동결)이 검토 상태 확정의 유일 경로**가 됐다. BE API·`LS_DATA_META_REVIEW` 는 존치하므로 케이스는 그대로 두고 서술만 정정. ②**결정 2**(커밋 `b27b3108`) — 버전 전용 페이지 `/history/:videoId`(SC-010) 삭제. **D-4/D-5 기능 케이스는 전부 유효**하고 화면 진입 경로만 라벨링 인라인 `HistoryPanel` 로 바뀌었다 → D-4 절 머리말에 명시(기능 케이스를 페이지 삭제와 함께 폐기하지 않도록 못 박음) |
| 3 | 2026-08-03 | 42건 | 0건 | 0건 | **근거 `file:line` 전수 재확인 회차** — 197행 전량을 실제 코드와 대조(A=155 정확 / B=42 라인드리프트·기대결과 정정 행, D=0 폐기 / E=0 미해결. B 는 5개 파트가 담당 구간별로 나눠 정정한 고유 TC 행 수이며 `git diff` 로 재확인된 정본 수치 — part1 10행(D-1) · part2 5행(D-5) · part3 21행(D-6/D-7) · part4 1행(D-3) · part5 5행(D-8)). 라인드리프트는 대부분 `AssignmentService.java`(D-3, 22건) 증가로 인한 하방 이동. 기대결과·전제 보강이 함께 들어간 대표 행: TC-REVIEW-007/017/050/051(승인·전이 500 계열 신규결함 표기) · TC-DIFF-002/006/008/018/024(SKELETON diff·오라클·REG_USER_NO 소실·write skew 신규결함 표기) · TC-NOTIFY-003/011/028/040/041/051(event_type_cd pass-through·changeType 런타임 미검증·frameIds 400 메시지 노출·NO_INPUT 통지유실·D-ISSUE-62 해소+fail-open 잔여갭) · TC-MARTVIEW-002/003/004/013/016(`DatasetMaterializeApproveRollbackIT` 존재 확인 — D-ISSUE-50 해소 / E-ISSUE-81 PARTIAL export 정책반전 반영). `RejectRequest.java` 경로 모호성은 `review/dto` 로 확정(TC-REVIEW-019, 2차부터 유지). ⚠ 이 행의 "정정" 수치는 최초 작성 시 45~49건으로 잠정 기재됐던 것을 병합 담당자가 `git diff --stat`(42/42 라인, unique TC-ID 42건) 로 재검증해 **42건으로 정정**했다 |
| 4 | 2026-08-04 | 43건 | 2건 | 0건 | **D-ISSUE-21(write skew) 수정 반영** — 롤백/검수승인 모두 활성 버전 목록 조회를 **프레임 행 락(직렬화 앵커) 이후로 이동**해 재조회한 값으로만 판정·비활성화한다(락 순서 규약 VERSION→SRC→LBL 는 불변, 조회 **시점**만 이동). 구 동작(앵커 이전 1회 조회 → 동시 롤백 후 ACTIVE 2건 잔존, 재현 2/2)은 **폐기**. 정정: TC-DIFF-018(미해소→해소) · TC-DIFF-024(“단일 스레드에서만 성립” 서술 폐기) · TC-DIFF-025(재조회가 순서 규약을 깨지 않는 근거 추가) · TC-VERSION-010(승인 경로 앵커 추가 — 범위 확장분) + `VersionService.java` 라인드리프트 39행. 신규: TC-DIFF-027(동시 롤백 후 `isCurrent` 1건) · TC-DIFF-028(동시 검수승인×롤백 ACTIVE 1건). 회귀 가드 = `VersionRollbackRestoreIT`(pg_locks 잠금대기 관측 기반 **결정적** 동시성 IT 3건) + `VersionServiceRollbackLockOrderTest`(InOrder 순서 가드) |

| 5 | 2026-08-04 | 5건 | 1건 | 0건 | **D-ISSUE-21 수정의 리뷰 지적(MEDIUM 5건) 보강** — 동작 정책 변경 없음(4차 결론 유지). ①**선취 `lockActiveVersions` 유지 확정**: DB 리뷰의 "프레임당 쿼리 1→3" 지적에 대해 제거를 검토했으나, 이 선취가 롤백·승인 두 경로가 공유하는 잠금 순서 규약(VERSION→SRC)의 **첫 간선**이라 한쪽만 빼면 두 경로가 반대 방향이 되어 ABBA(40P01)가 성립하고, 선취가 잠그는 행은 재조회가 다시 잠그는 **같은 행**이라 **락 보유 시간은 전혀 줄지 않는다**(쿼리 1회만 감소) → **유지**하고 근거를 코드 주석에 고정. 대신 `commitApproved` 에 **elapsed 관측(INFO) + 3s 초과 WARN**을 추가해 락 보유 구간을 가시화(TC-VERSION-010 정정). ②IT 의 `pg_locks` 관측을 **DB 전역 → 이 경로가 잠그는 두 테이블**(`LS_LABEL_VERSION`·`LS_DATA_SRC`)로 스코프 축소 — 무관한 세션의 대기가 섞이면 경합 성립 전에 T1 이 커밋돼 회귀를 놓치던 flaky 위험 제거(TC-DIFF-018/027/028 정정). ③동시성 케이스에 **`LS_DATA_LBL_HSTRY` 건수 단언** 추가(중복·누락 기록 회귀 검출 — ACTIVE 건수만으로는 통과). 신규: **TC-DIFF-029**(승인 선점 → 롤백 대기 **역순**). ⚠ **3-way 이상 경합 케이스는 두지 않는다** — 테스트 커넥션 풀이 `maximum-pool-size: 2`(의도적)라 3번째 요청은 행 락이 아니라 커넥션 획득에서 막혀 거짓 안전감만 준다. 검증: `VersionRollbackRestoreIT` 15건 9회 연속 green + **역가드**(수정 되돌림 시 동시성 4건 전부 ACTIVE=2 로 실패). ⚠ 이 행의 "선취가 잠그는 행은 재조회가 다시 잠그는 **같은 행**" 이라는 서술은 **6차에서 부정확으로 정정**됐다(선취 유지 결론 자체는 유효 — 6차 행 참조) |
| 6 | 2026-08-04 | 3건 | 0건 | 0건 | **주석 서술 정정 회차 — 실행 코드·동작·정책 무변경**(테스트 무영향). 5차가 남긴 잠금 순서 근거 주석 중 "앵커 이후의 VERSION **재조회**는 이미 선취한 행이라 새 간선을 만들지 않는다"는 서술이 **부정확**함이 재검토로 드러나 정정했다: ①재조회는 **선취 시점에 없던 새 ACTIVE 행**을 반환할 수 있다(락 이후 최신 상태를 다시 보는 것이 write skew 수정의 목적 자체라 "재조회 결과 ⊆ 선취 집합" 보장은 애초에 성립하지 않는다) ②`activateRollbackTarget` 은 **선취 대상이 아니었던 비활성 대상 행**을 SRC 앵커 보유 상태에서 UPDATE(activate)한다. 따라서 SRC→VERSION **잔여 간선**이 남아 "T2 가 선취로 VERSION 을 쥔 채 SRC 대기 / T1 이 SRC 를 쥔 채 그 VERSION 요구"하는 **3-트랜잭션 인터리빙에서 이론적 교착(40P01)** 이 성립할 수 있다. ★**인지된 잔여 리스크**이며 두 경로의 선취를 동시에 제거해 VERSION 획득 진입점을 앵커 이후 한 곳으로 단일화하는 **락 순서 규약 재설계는 별도 이슈로 이월**. 정정: TC-DIFF-025(부정확 서술 교체 + 잔여 교착 명시) · TC-DIFF-028/029(`VersionService.java` 라인드리프트 +3/+15행) |
| 7 | 2026-08-05 | 0건 | 14건(TC-ASSIGN-027~035 · TC-STAT-001~005) | 0건 | **이벤트유형 필터 표시명 그룹핑이 작업목록·배정목록·통계에도 적용됨(R3~R5)** — `EventTypeFilterSupport`(신설)가 `EventTypeService.groupIndex()`(표시명 그룹 옵션)를 `/v1/tasks/board/event-types`·`/v1/assignments/event-types`·목록 필터·KPI 집계에 공용 적용한다(옵션 dedup·대표/비대표 코드 그룹 전체 매칭·절단은 접은 뒤 판정·WORKER 인가 축 보존). **D-3a 신설**(TC-ASSIGN-027~035, 이 도메인의 첫 그룹 축 케이스). `StatsService` 의 이벤트 분포도 같은 그룹 옵션을 순회해 합산하도록 바뀌어 **D-3b 신설**(TC-STAT-001~005) — 이 카탈로그에 통계 BE 케이스가 아예 없던 갭을 이번 변경 범위 안에서만 메웠다(통계 전면 카탈로그화는 별건). 영상 목록(`/v1/videos`)의 동일 축은 [B-18](B-batch-deidentify.md) 소관, FE 표시는 [H-18/H-16](H-frontend-e2e.md) 소관 |

> 표기 규칙: 케이스명 `(신규)` = 이번 회차 추가 · `~~취소선~~` + 기대결과 `**[폐기 …]**` = 정책 변경으로 무효화된 케이스(ID 추적성 유지를 위해 행은 보존).

## D-1. 검수 워크플로우 (TC-REVIEW)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-REVIEW-001 | 제출 정상(ASSIGNED→PENDING) | WORKER 본인 배정, ASSIGNED | submit(videoId, WORKER) | PENDING, LsTaskEventLog.submit, 200 | integration | High | ReviewService.java:394-406 |
| TC-REVIEW-002 | 재제출(REJECTED→PENDING) | REJECTED, 본인 배정 | submit | PENDING 성공 | unit | High | ReviewStateMachine.java:48 |
| TC-REVIEW-003 | 재검수 재제출(APPROVED→PENDING) | APPROVED, 본인 배정 | submit | PENDING(동일 작업 ID, 버전업 아님) | unit | High | ReviewStateMachine.java:50,55 |
| TC-REVIEW-004 | 제출 차단 — 배치완료 COMPLETED 점프 방지 | COMPLETED(배치상태) | submit | INVALID_INPUT(400) | unit | **Critical** | ReviewStateMachine.java:43-51,59-63 |
| TC-REVIEW-005 | 제출 차단 — IN_REVIEW에서 제출 | IN_REVIEW | submit | INVALID_INPUT(400) | unit | Med | ReviewStateMachine.java:59-63 |
| TC-REVIEW-006 | 제출 — 타 WORKER 배정(IDOR) | WORKER, 본인 미배정 | submit | FORBIDDEN(403) | security | **Critical** | ReviewService.java:614-627 |
| TC-REVIEW-007 | 제출 — REVIEWER가 submit 호출 | REVIEWER | submit | FORBIDDEN(403). ⚠ **실제 차단 지점은 컨트롤러 `@PreAuthorize("hasRole('WORKER')")`** 라 응답 메시지는 표준 `"권한이 없습니다."` 다 — 서비스의 `"WORKER 권한이 필요합니다."`(618-620)는 HTTP 경로로 도달하지 않는다(3차 실측 정정, 구 기대문구 `"WORKER 권한 필요"` 폐기) | security | High | ReviewController.java:221 · ReviewService.java:618-620(HTTP 미도달) |
| TC-REVIEW-008 | 제출 — 미인증 | actor=null | submit | UNAUTHORIZED(401) | security | High | ReviewService.java:615-617 |
| TC-REVIEW-009 | 검수시작 정상(PENDING→IN_REVIEW) | REVIEWER, PENDING | startReview | IN_REVIEW | integration | High | ReviewService.java:444-452 |
| TC-REVIEW-010 | 검수시작 — 비REVIEWER | WORKER | startReview | FORBIDDEN(403) | security | High | ReviewService.java:652-659 |
| TC-REVIEW-011 | 승인 정상(IN_REVIEW→APPROVED) | REVIEWER, IN_REVIEW, **라벨 1건 이상** | approve | APPROVED+스냅샷(commitApproved)+evntAnno/meta 자동승인+materialize+ReviewApprovedEvent | integration | **Critical** | ReviewService.java:467-518 |
| TC-REVIEW-012 | 승인 — PENDING에서 직행 차단 | PENDING | approve | INVALID_INPUT(400) | unit | High | ReviewStateMachine.java:43-51,59-63 |
| TC-REVIEW-013 | 승인 — 이미 APPROVED 재승인 시도 | APPROVED | approve(→APPROVED) | CONFLICT(409) | unit | High | ReviewStateMachine.java:54-58 |
| TC-REVIEW-014 | 승인 — 비REVIEWER | WORKER | approve | FORBIDDEN(403) | security | **Critical** | ReviewService.java:469,652-659 |
| TC-REVIEW-015 | 승인 시 event_annotation 자동 APPROVED 동결 | IN_REVIEW, evntAnno 존재 | approve | autoApproveOnVideoApproval → 같은 tx 의 materialize 가 EVNT_ANNO_CN 동결 | integration | High | ReviewService.java:502-506 |
| TC-REVIEW-016 | 승인 시 시계열 메타 검토행 자동 APPROVED | LS_DATA_META_REVIEW 존재 | approve | `metaService.autoApproveOnVideoApproval` → `V_COMPLETED_META` 노출. ★**2026-08-03 부로 검토 상태를 확정하는 유일한 경로**다 — 라벨링 화면의 승인/반려 UI 가 제거돼(결정 1, 커밋 `80171828` → [TC-FE-276~278](H-frontend-e2e.md)) 사람이 개별 메타를 APPROVED 로 만들 화면 동선이 없다. **BE API `POST /v1/meta/{metaReviewSn}/approve\|reject` 와 `LS_DATA_META_REVIEW` 는 존치**(FE 진입점만 부재)이므로 엔드포인트 케이스를 폐기하지 말 것 | integration | High | ReviewService.java:507-510 · MetaController.java:83-89(approve)·103-110(reject) |
| TC-REVIEW-017 | 승인 — 스냅샷 스킵 발생 시 WARN(승인 성공) | 직렬화/10MB 초과 프레임 | approve | 승인 성공+`approved with snapshot skips` WARN(본문/PII 미출력). ⚠ **직렬화·10MB 초과 경로만 스킵으로 흡수**된다 — 스냅샷 **DTO 구성**(`LabelResponse.of`, VersionService.java:268-269)은 try 밖이라 좌표 JSON 손상 등으로 던지면 **승인 전체가 500+롤백**(D-ISSUE-01, 1차→3차 이월·3차 실측 재현) | integration | Med | ReviewService.java:496-501 · VersionService.java:268-287 |
| TC-REVIEW-018 | 반려 정상(IN_REVIEW→REJECTED) | REVIEWER, IN_REVIEW, 사유 | reject(reason) | REJECTED+LsDataIssue INSERT+이벤트로그 | integration | High | ReviewService.java:570-599 |
| TC-REVIEW-019 | 반려 — 사유 누락 | reason 빈값 | reject | 400(@Valid) | unit | Med | review/dto/RejectRequest.java:9-13(@NotBlank, augment/dto 아님 — ReviewController.java:18,314 로 확정) |
| TC-REVIEW-020 | 반려 — 재반려 시 parent 이슈 연결 | 직전 반려 이슈 존재 | reject | UP_DATA_ISSUE_SN 계층 연결 | integration | Med | ReviewService.java:576-584 |
| TC-REVIEW-021 | 검수취소 정상(PENDING→ASSIGNED) | WORKER 본인, PENDING | cancelSubmit | ASSIGNED+cancelSubmit 이벤트(PII 미포함) | integration | High | ReviewService.java:421-439 |
| TC-REVIEW-022 | 검수취소 — IN_REVIEW 차단 | IN_REVIEW | cancelSubmit | INVALID_INPUT(400, PENDING만) | unit | High | ReviewStateMachine.java:47,59-63 |
| TC-REVIEW-023 | 검수취소 — APPROVED 차단 | APPROVED | cancelSubmit(→ASSIGNED) | CONFLICT(409) | unit | Med | ReviewStateMachine.java:54-58 |
| TC-REVIEW-024 | 검수취소 — 타 WORKER(IDOR) | 본인 미배정 | cancelSubmit | FORBIDDEN(403) | security | High | ReviewService.java:423,614-627 |
| TC-REVIEW-025 | 상세조회 — WORKER 본인 배정만 | WORKER 본인 배정 | getDetail | 200 | security | High | ReviewService.java:633-650 |
| TC-REVIEW-026 | 상세조회 — WORKER 타인 배정 차단(IDOR) | WORKER, 미배정 | getDetail | FORBIDDEN(403) | security | **Critical** | ReviewService.java:640-649 |
| TC-REVIEW-027 | 프레임목록 — 영상 미존재 | 없는 videoId | listFrames | NOT_FOUND(404) | unit | Med | ReviewService.java:335-339 |
| TC-REVIEW-028 | 프레임목록 — N+1 회피(2쿼리) | 다프레임 | listFrames | 프레임1회+라벨IN 1회, frameNo 순서 | integration | Low | ReviewService.java:335-372 |
| TC-REVIEW-029 | 목록 — REVIEWER 전용+status 필터 | REVIEWER | GET /v1/reviews?status= | 상태별 페이징(QueryDSL), 비REVIEWER 403 | integration | Med | ReviewService.java:103-149 · ReviewQueryRepository.java:109-129 |
| TC-REVIEW-030 | 이슈목록 — REVIEWER 전용 | REVIEWER | listIssues | 반려사유 목록 | unit | Low | ReviewService.java:378-383 |
| TC-REVIEW-035 | **라벨 0건 승인 차단(409 REVIEW_NO_LABEL)** (신규) | IN_REVIEW, 라벨 0건, 바디 없음 | approve | `ErrorCode.REVIEW_NO_LABEL`(409), 상태 IN_REVIEW 유지(전이 전 판정) | integration | **Critical** | ReviewService.java:543-565 |
| TC-REVIEW-036 | negative sample 승인 허용 (신규) | 라벨 0건 | approve `{"noLabelConfirmed":true}` | 200 APPROVED + `LsTaskEventLog.approveWithoutLabel`(사유 감사) | integration | High | ReviewService.java:477-486,556-564 |
| TC-REVIEW-037 | 라벨 있는데 noLabelConfirmed=true (신규) | 라벨 1건 이상 | approve `{"noLabelConfirmed":true}` | INVALID_INPUT(400) — 플래그 상시전송 클라이언트 차단 | security | High | ReviewService.java:545-553 |
| TC-REVIEW-038 | 게이트 순서 — 상태전이 검증이 라벨 게이트보다 먼저 (신규) | PENDING + 라벨 0건 | approve | 409 아니라 **400**(전이 불가) — 기존 오류 계약 보존 | unit | Med | ReviewService.java:471-478 |
| TC-REVIEW-039 | 승인 바디 선택(하위호환) (신규) | 라벨 존재, 바디 미첨부 | approve(body=null) | 기존과 동일 승인 200 | unit | Med | ReviewController.java:286-294 |
| TC-REVIEW-040 | **미등록 정렬 키 = 기본정렬 폴백(lenient 200)** (신규) | REVIEWER | `GET /v1/reviews?sort=filePath,desc` | **400 아님** — 200 + `UPD_DT DESC` 폴백 + WARN 로그. ★작업목록(strict 400)과 정책이 다르며 통일 금지 | security | **Critical** | ReviewController.java:95-103 · SortAllowlist.java:29-60 |
| TC-REVIEW-041 | 정렬 allowlist 매핑 (신규) | REVIEWER | `sort=submittedAt,desc` / `videoId` / `status` | 각각 `updDt`/`rawDataId`/`dataSttsCd` 로만 해석(임의 프로퍼티 미도달) | security | High | SortAllowlist.java:117-121(REVIEW) · ReviewQueryRepository.java:360-388 |
| TC-REVIEW-042 | PK tie-break 강제 (신규) | 동일 UPD_DT 다건 | 목록 2페이지 순회 | 마지막 정렬항에 `RAW_DATA_ID DESC` 자동 append → 페이지 경계 중복/누락 0 | integration | High | ReviewQueryRepository.java:360-388 |
| TC-REVIEW-043 | 화이트리스트 밖 status = 빈 결과 200 (신규) | REVIEWER | `status=PROCESSING` (또는 `ASSIGNED`/`FAILED`) | 400 아님 — **빈 결과 200**(교집합 공집합), 기존 계약 보존 | integration | High | ReviewSearchCondition.java:40-42(statusFilter) · ReviewQueryRepository.java:229-250 |
| TC-REVIEW-044 | 검수 워크플로 화이트리스트 상시 적용 (신규) | 배치/작업 상태 행 혼재 | `status` 미지정 목록 | `PENDING/IN_REVIEW/APPROVED/REJECTED` 4종만 노출(보안 경계, buildWhere 첫 줄) | security | **Critical** | ReviewQueryRepository.java:233-234 · ReviewRepository.java:33-37 |
| TC-REVIEW-045 | `q` 검색 DB WHERE 단계 적용 (신규) | 영상명·작업자명 부분일치 | `GET /v1/reviews?q=강남` | 반환 건수와 `totalElements` 가 필터 결과 기준으로 일치(메모리 후처리 금지), LIKE `\ % _` 이스케이프 | integration | **Critical** | ReviewQueryRepository.java:243-248,336-340(escapeLike) |
| TC-REVIEW-046 | 작업자명 검색 = 최신 배정 1건 기준 (신규) | 재배정 이력 다건 | `q=<과거 작업자명>` | 미매칭(표시 작업자와 동일 tie-break: REG_DT DESC → ASSIGNMENT_ID DESC) | integration | Med | ReviewQueryRepository.java:304-325 · ReviewService.java:221-230 |
| TC-REVIEW-047 | 영상명 공백 판정 통일 (신규) | CCTV_NM 이 탭/개행만 | `q=<VMS_CCTV_ID 일부>` | 표시 폴백과 동일 판정(`BlankTextPredicate`)으로 매칭 성립 | unit | Med | ReviewQueryRepository.java:261-284,331-333 |
| TC-REVIEW-048 | KPI 집계 — `GET /v1/reviews/summary` (신규) | REVIEWER | summary(q 지정, status 지정) | 5필드(total/pending/inReview/approved/rejected), **total=4종 합**, `q` 반영 · `status` 무시 · `sort` 미수신(400 아님) | integration | High | ReviewController.java:127-139 · ReviewSummaryResponse.java:61-69(of) |
| TC-REVIEW-049 | KPI 버킷 커버리지 fail-fast (신규) | 화이트리스트 확장 후 DTO 미갱신(내부 드리프트) | countByStatus | `bucketSum != total` → IllegalState(입력값 미노출), 조용한 과소집계 금지 | unit | Med | ReviewQueryRepository.java:186-215 · ReviewSummaryResponse.java:72-85(verifyMappedCoverage) |
| TC-REVIEW-050 | size 상한 (신규) | REVIEWER | `size=101` | INVALID_INPUT(400, max=100). ⚠ **하한은 미검증** — `size=0`·`size=-1`·`page=-1` 은 `PageRequest.of` 의 `IllegalArgumentException` 이 그대로 새어 **500**(3차 실측, D-ISSUE-03). 형제 목록 `/v1/tasks/board`·`/v1/videos` 는 같은 입력에 200 이라 비대칭 | unit | Med | ReviewController.java:47,92-94,101-102 |
| TC-REVIEW-051 | 검수취소 낙관적 잠금 409 (신규) | PENDING, REVIEWER 검수시작과 동시 경합 | cancelSubmit | 한쪽만 성공, 패자 CONFLICT(409) — flush 로 커밋 전 표면화. ⚠ **409 배선은 cancelSubmit·approve·reject 3경로 뿐** — `submit`(394-406)·`startReview`(444-452)는 flush+catch 가 없어 낙관적 잠금 패자가 **500** 이다(3차 실측, D-ISSUE-02). 이 케이스가 항상 409 인 이유는 경합 쌍에서 **cancelSubmit 이 먼저 flush 해 늘 패자 쪽**이기 때문이며, 두 `start` 를 동시 발사하면 패자는 500 이다 | integration | High | ReviewService.java:431-436 |
| TC-REVIEW-052 | event_annotation `cot` 배열/객체 양형 정규화 (신규) | 과거 동결본 `cot`=배열 | 조회/재직렬화(fromJson) | 500 아님 — 순번 1..n → `n단계` 키로 흡수, 신규 저장은 객체형 | unit | High | EventAnnotationPayload.java:CotDeserializer(120-142) |

## D-2. 검수 상태머신 (TC-REVIEW, 단위)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-REVIEW-031 | 허용 전이 전수 매트릭스 | — | ASSIGNED→PENDING, PENDING→IN_REVIEW, PENDING→ASSIGNED, IN_REVIEW→APPROVED, IN_REVIEW→REJECTED, REJECTED→PENDING, APPROVED→PENDING | 각각 통과(정본 7전이) | unit | High | ReviewStateMachine.java:43-51 |
| TC-REVIEW-032 | 미허용 전이 전수 | — | ASSIGNED→APPROVED, PENDING→REJECTED, REJECTED→APPROVED 등 | INVALID_INPUT(400) | unit | High | ReviewStateMachine.java:59-63 |
| TC-REVIEW-033 | APPROVED→IN_REVIEW/REJECTED 직행 | from=APPROVED | to=IN_REVIEW/REJECTED | CONFLICT(409) | unit | High | ReviewStateMachine.java:54-58 |
| TC-REVIEW-034 | 알 수 없는 from 상태 | from=COMPLETED/PROCESSING/**null** | verify | COMPLETED/PROCESSING → INVALID_INPUT(400). ⚠ **from=null 은 현행 NPE(500)** — `Map.of().get(null)` 가드 부재(D-ISSUE-03 미해소, 기대값은 400) | unit | Med | ReviewStateMachine.java:53-64 |

## D-3. 검수 배정 (TC-ASSIGN)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-ASSIGN-001 | 배정 정상 | REVIEWER, 유효 worker, **비APPROVED** | assign(rawDataIds, workerId) | LABELER INSERT+markAssigned+이벤트로그 | integration | **Critical** | AssignmentService.java:71-129 |
| TC-ASSIGN-002 | 배정 — 비REVIEWER | WORKER | assign | FORBIDDEN(403) | security | **Critical** | AssignmentService.java:73,668-678(requireReviewer, throw 675-677) |
| TC-ASSIGN-003 | 배정 — 미인증 actor null(NPE 가드) | actor=null | assign | UNAUTHORIZED(401), NPE 없음 | security | High | AssignmentService.java:672-674(requireReviewer) |
| TC-ASSIGN-004 | 배정 — 존재하지 않는 작업자 | workerId 미존재 | assign | INVALID_INPUT(400) | unit | High | AssignmentService.java:76-78 |
| TC-ASSIGN-005 | 배정 — 존재하지 않는 검수자 | reviewerId 미존재 | assign | INVALID_INPUT(400) | unit | Med | AssignmentService.java:79-81 |
| TC-ASSIGN-006 | 배정 — 중복 배정(UK 충돌) | 동일 worker 기배정 | assign | CONFLICT(409) | integration | High | AssignmentService.java:101-103 |
| TC-ASSIGN-007 | 배정 + REVIEWER 동시등록 | reviewerId 포함 | assign | REVIEWER row INSERT | integration | Med | AssignmentService.java:115-119,188-220 |
| TC-ASSIGN-008 | 배정 — REVIEWER 중복 등록 skip | reviewer 기등록 | assign | UK 충돌 격리, worker 배정 보존 | integration | Med | AssignmentService.java:201-219 |
| TC-ASSIGN-009 | 재배정 정상 | REVIEWER, 미완료 배정 | reassign(assignmentId, newWorker) | LsTaskAssignHistory+reassign 이벤트+prev.reassignTo | integration | High | AssignmentService.java:222-302 |
| TC-ASSIGN-010 | 재배정 — 완료(APPROVED) 차단 | APPROVED | reassign | ASSIGNMENT_ALREADY_COMPLETED(409) | security | **Critical** | AssignmentService.java:230-240 |
| TC-ASSIGN-011 | 재배정 — 배정 미존재 | 없는 assignmentId | reassign | NOT_FOUND(404) | unit | Med | AssignmentService.java:227-228 |
| TC-ASSIGN-012 | 재배정 — 동일 작업자 | newWorker==prev | reassign | INVALID_INPUT(400) | unit | Med | AssignmentService.java:258-260 |
| TC-ASSIGN-013 | 재배정 — 새 작업자 이미 배정(사전) | newWorker 기배정 | reassign | CONFLICT(409) | integration | Med | AssignmentService.java:262-275 |
| TC-ASSIGN-014 | **재배정 동시성 — @Version 직렬화** | 동시 PATCH ×4 | reassign | 성공 1건 + 나머지 CONFLICT(409), `LS_TASK_ASSIGN_HISTORY` **+1행만**(패자 tx 전체 롤백). V134 `LS_TASK_ASSIGNMENT.VER` | integration | **Critical** | AssignmentService.java:283-286,291-299(3차 근거 1행 정정 — 290은 직전 DataIntegrityViolationException catch 소속) |
| TC-ASSIGN-015 | 배정이력 — WORKER 본인만(IDOR) | WORKER, 타인 이력 | getHistory | FORBIDDEN(403) | security | **Critical** | AssignmentService.java:322-328 |
| TC-ASSIGN-016 | 배정이력 — REVIEWER 전체 | REVIEWER | getHistory | OCRN_DT ASC 통합, N+1 회피 | integration | Med | AssignmentService.java:315-361 |
| TC-ASSIGN-017 | 배정이력 — 잘못된 ID | id<=0/null | getHistory | INVALID_INPUT(400) | unit | Low | AssignmentService.java:316-320 |
| TC-ASSIGN-018 | 배정목록 — WORKER 본인만(param 무시) | WORKER, workerId 지정 | listAssignments | 본인 sub 강제(IDOR) | security | High | AssignmentService.java:470-482(scopeForActor, WORKER분기 475-477) |
| TC-ASSIGN-019 | 배정목록 — REVIEWER 필터/전체 | REVIEWER | listAssignments(workerId?) | 필터 또는 전체 LABELER | integration | Low | AssignmentService.java:470-482(scopeForActor, REVIEWER분기 478-480) |
| TC-ASSIGN-020 | 배정목록 — 기타 역할/미인증 | PORTAL/null | listAssignments | 403 / 401 | security | Med | AssignmentService.java:470-482(scopeForActor, null 401 471-473 · 기타역할 403 481) |
| TC-ASSIGN-021 | 상태 upsert 동시 INSERT 충돌 | 동일 rawDataId 동시 배정 | assign×2 | PK 제약→CONFLICT(재시도 가능) | integration | Med | AssignmentService.java:657-666(upsertDataStts) |
| TC-ASSIGN-022 | **APPROVED 영상 신규 배정 차단** (신규) | 검수완료(APPROVED) 영상 | POST /v1/assignments | **409 ASSIGNMENT_ALREADY_COMPLETED**, 상태 APPROVED 유지(구: 무검증 ASSIGNED 강등 — D-ISSUE-01) | security | **Critical** | AssignmentService.java:145-156(rejectApprovedTargets, throw 151-154) |
| TC-ASSIGN-023 | 배정 부분성공 금지 (신규) | rawDataIds 3건 중 1건 APPROVED | assign | 어떤 영상도 배정되지 않음(전체 실패), 상태 조회는 단일 IN 쿼리 | integration | High | AssignmentService.java:83(rejectApprovedTargets 선행 호출),88(배정 루프),145-156 |
| TC-ASSIGN-024 | 배정 중 승인 경합 → 409(500 아님) (신규) | 가드 통과 후 타 tx approve 커밋 | assign flush | OptimisticLockingFailure → CONFLICT(409) 국소 변환 | integration | High | AssignmentService.java:89-93(경합 구간),104-112(CONFLICT 변환) |
| TC-ASSIGN-025 | 재배정 상태행 공유잠금(FOR SHARE) (신규) | reassign 진행 중 타 tx approve | reassign | 가드 통과 후 승인 커밋으로 뒤집히지 않음(트랜잭션 종료까지 잠금 보유) | integration | High | AssignmentService.java:230-240 |
| TC-ASSIGN-026 | 배정 — IN_REVIEW 재배정 허용 여부 (신규) | IN_REVIEW | reassign | 현행 **허용**(APPROVED 만 차단) — D-ISSUE-05 미해소, 정책 확정 필요 | integration | Med | AssignmentService.java:235-240 |

## D-3a. 이벤트유형 필터 표시명 그룹핑 — 작업목록·배정목록 공용 (TC-ASSIGN) — 2026-08-05 신설

> **작업목록(`GET /v1/tasks/board`·`/tasks/board/event-types`·`/tasks/board/summary`, REVIEWER)과 배정목록(`GET /v1/assignments`·`/assignments/event-types`, WORKER) 이 같은 판정기 `EventTypeFilterSupport`(+ `EventTypeService.groupIndex`)를 공유**한다. 접기 규칙이 두 서비스에 복붙되면 한쪽만 갱신돼 역할에 따라 옵션이 달라지는 화면 분기가 생기므로, 이 절의 케이스는 **두 엔드포인트에 공통 적용**된다(개별 assertion 은 `EventTypeGroupFilterIT` 가 양쪽을 나란히 검증).
> 영상 목록(`/v1/videos`)의 동일 축 케이스는 [B-18](B-batch-deidentify.md) TC-VIDEO-006a·019~021 소관. 통계 이벤트 분포의 그룹 합산은 아래 **D-3b**(TC-STAT) 소관.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-ASSIGN-027 | 작업목록·배정목록 이벤트옵션에 같은 표시명 코드가 중복되지 않는다 (신설) | 침수 3종(같은 표시명, 대표 1 + 비대표 2) + 독립 표시명 1종 + 미등록 비규격 1종 | `GET /v1/tasks/board/event-types` · `GET /v1/assignments/event-types` | 옵션에 **대표코드 1건**만 노출(비대표 2종은 옵션에서 제거). 두 엔드포인트가 같은 결과 — 판정기가 갈라지면 역할별로 옵션이 달라진다 | integration | High | EventTypeGroupFilterIT.java:190-213 |
| TC-ASSIGN-028 | 미등록·비규격 코드는 옵션에서 제거되지 않고 원문으로 남으며 필터로 도달 가능하다 (신설) | 마스터 미등록 비규격 코드(예 `INTRUSION`류) 보유 작업 존재 | 옵션 조회 → 그 코드로 필터 | 옵션에 원문 코드 그대로 노출(접을 수 없다고 버리면 그 코드의 영상이 필터로 도달 불가능해진다) + 그 값으로 필터하면 실제로 1건 조회(옵션↔필터 왕복 성립) | integration | High | EventTypeFilterSupport.java:81-105 · EventTypeGroupFilterIT.java:215-230 |
| TC-ASSIGN-029 | 옵션 개수가 상한 이하면 truncated는 false — 접힌 뒤 판정 (신설) | 그룹핑 후 옵션 수가 상한 이하 | 옵션 조회 | `truncated=false`. 스캔 상한은 `max+1` 이 아니라 `max+(노출 그룹 멤버 코드 수)+1` — 접기 전에 자르면 잘린 구간의 그룹이 통째로 사라지고 truncated 판정도 부정확해진다 | integration | Med | EventTypeFilterSupport.java:57-71 · EventTypeGroupFilterIT.java:232-241 |
| TC-ASSIGN-030 | 대표코드로 필터하면 그룹 전체 작업이 조회된다 (신설) | 표시명이 같은 그룹 3건(대표 1 + 비대표 2) | `?eventTypeCd={대표코드}` | 3건 전부 조회. **옵션이 대표코드로 접혔는데 필터가 단일 코드 동등비교면 그룹 나머지가 사라진다** | integration | P0 | EventTypeFilterSupport.java:107-127 · EventTypeGroupFilterIT.java:245-254 |
| TC-ASSIGN-031 | 비대표코드로 필터해도 그룹 전체 작업이 조회된다 — 하위호환 (신설) | 그룹 비대표코드 보유 작업 | `?eventTypeCd={비대표코드}` | 그룹 전체(3건) 조회. **그룹 도입 이전에 만들어진 북마크가 줄어들면 하위호환 파손** | integration | P0 | EventTypeGroupFilterIT.java:256-266 |
| TC-ASSIGN-032 | 작업목록 KPI 집계도 그룹 전체를 센다 (신설) | 대표코드 필터 지정 | `GET /v1/tasks/board/summary?eventTypeCd={대표코드}` | KPI `total` = 그룹 전체 건수(3). 목록과 다른 집합을 세면 카드 숫자와 `totalElements` 가 갈라진다 | integration | High | EventTypeGroupFilterIT.java:268-279 |
| TC-ASSIGN-033 | 독립 표시명 코드는 자기 1건만 조회된다 (신설) | 그룹 없음(표시명이 유일) | `?eventTypeCd={독립코드}` | 1건만 조회(다른 그룹과 섞이지 않음) | integration | Med | EventTypeGroupFilterIT.java:281-288 |
| TC-ASSIGN-034 | WORKER는 그룹 필터로도 본인 배정분만 조회된다 — IDOR 방지 (신설) | 그룹 3종 중 1종은 타인 배정 | WORKER 토큰, `?eventTypeCd={대표코드 또는 비대표코드}` | **2건**(본인 배정만). 그룹 확장이 인가 축을 넘어서면 안 된다(CWE-639) — REVIEWER 는 같은 필터로 전체(3건) 조회 | security | **Critical** | EventTypeGroupFilterIT.java:292-305 |
| TC-ASSIGN-035 | 관리 화면에서 표시명을 바꾸면 그룹이 즉시 재구성된다 (신설) | 같은 표시명 2종이 옵션 1건으로 캐시된 상태 | 운영자가 한 유형의 표시명 정정(관리 API) | 다음 조회부터 옵션이 2건으로 즉시 분리(그룹 캐시가 무효화 대상에서 빠지면 TTL 동안 옛 그룹이 유지된다) + 필터 매칭도 자기 코드 1건으로 좁아짐 | integration | High | EventTypeCacheIT.java:239-272 |

## D-3b. 이벤트유형 통계 분포 그룹 합산 (TC-STAT) — 2026-08-05 신설

> `StatsService.buildDistribution` 이 `EventTypeService.filterOptions()`(표시명 그룹 옵션)를 그대로 순회해 각 그룹의 `memberCodes` 카운트를 합산한다 — [17 §17.0](../v2-wiki/17-statistics.md) 참조. 이 절이 이 카탈로그의 **첫 통계 BE 케이스**다(기존에 TC-STAT 프리픽스·통계 서비스 전용 클러스터가 없었다 — 갭이었음). 범위는 이번 그룹 합산 변경에 한정하고, `StatsService` 나머지(검수완료/전체 분리, `approved*` 필드 등)의 전면 카탈로그화는 별건이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-STAT-001 | 표시명이 같은 상세코드들의 영상은 대표코드 한 칸으로 합산된다 (신설) | 그룹 대표 2건 + 비대표 3건(합 5건) | 이벤트 분포 조회 | 대표코드 칸 1개에 `count=5`, `label`=표시명. `memberCodes` 합산이 빠지면 대표코드 자기 카운트(2)만 남는다 | integration | High | StatsService.java:150-170 · StatsEventTypeGroupDistributionIT.java:150-162 |
| TC-STAT-002 | 그룹 비대표코드는 별도 칸으로 노출되지 않는다 (신설) | 위와 동일 시드 | 이벤트 분포 조회 | 비대표코드 자체는 칸이 없고, 같은 표시명 칸은 그리드에 **1개만** 존재(접기가 풀리면 2번 뜬다) | integration | High | StatsEventTypeGroupDistributionIT.java:164-173 |
| TC-STAT-003 | 표시명이 다른 유형은 다른 칸으로 분리된다 (신설) | 다른 표시명 그룹 1건(1영상) | 이벤트 분포 조회 | 별도 칸에 `count=1`. 표시명을 무시하고 전부 한 덩어리로 묶으면 여기서 깨진다 | integration | Med | StatsEventTypeGroupDistributionIT.java:175-185 |
| TC-STAT-004 | 비수집 코드의 영상은 어떤 칸에도 합산되지 않는다 (신설) | 비수집(`CLCT_YN='N'`) 코드 영상 4건 | 이벤트 분포 조회 | 자기 칸이 없고 **다른 그룹의 memberCodes 로도 새어 들어가지 않는다**(그리드 총합으로 누수 탐지) | security | High | StatsEventTypeGroupDistributionIT.java:187-199 |
| TC-STAT-005 | 제외 대분류 코드의 영상도 어떤 칸에도 합산되지 않는다 (신설) | 제외 대분류(기본 `08`=배회) 코드 영상 존재 | 이벤트 분포 조회 | 자기 칸 없음 + 그 표시명의 칸도 그리드에 없음(`filterOptions()` 가 제외 대분류를 옵션에서 뺀다) | integration | Med | StatsEventTypeGroupDistributionIT.java:201-210 |

## D-4. 버전관리 스냅샷 (TC-VERSION)

> **화면 진입 경로 정정(2026-08-03, 커밋 `b27b3108`)** — 버전 전용 페이지 **SC-010 `/history/:videoId`(`HistoryPage`)가 삭제**됐다(영상 상세의 '버전관리로 이동' 버튼이 유일 진입점이었고 그 버튼을 없애 orphan 이 됨).
> **BE·기능은 무변경**이다 — `features/version/**` 과 아래 TC-VERSION·[TC-DIFF](#d-5-diff--rollback-tc-diff) 는 **전부 유효**하며, 화면 검증 시 진입 경로만
> **라벨링 캔버스(SC-005) 히스토리 인라인 패널**(`HistoryPanel` 의 '버전' 탭 → [TC-FE-087](H-frontend-e2e.md))로 읽는다. **기능 케이스를 페이지 삭제와 함께 폐기하지 말 것.**

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-VERSION-001 | 승인 스냅샷 생성 | 라벨 존재 프레임 다수 | commitApproved | 프레임별 active(SAVE_REASON=APPROVED)+VERSION_HASH(SHA-256) | integration | **Critical** | VersionService.java:163-211 |
| TC-VERSION-002 | 라벨 없는 프레임 스킵 | 일부 프레임 라벨 0 | commitApproved | 빈 프레임 스냅샷 미생성(created 미증가) | unit | High | VersionService.java:194-201 |
| TC-VERSION-003 | 멱등 — 무변경 재승인 | active 동일 해시 | commitApproved 재호출 | 새 버전 미생성(IDEMPOTENT) | integration | High | VersionService.java:292-313 |
| TC-VERSION-004 | 재승인 시 변경분 새 버전 적층 | 라벨 수정 후 재승인 | commitApproved | 새 active 누적, 기존 deactivate(비활성화 대상 = **앵커 이후 재조회한** ACTIVE 목록) | integration | High | VersionService.java:289-313,1065-1075 |
| TC-VERSION-005 | 프레임 없음 | 프레임 0 | commitApproved | CommitResult.EMPTY(0,0) | unit | Med | VersionService.java:174-176,220-225 |
| TC-VERSION-006 | 영상 미존재 | 없는 rawSn | commitApproved | NOT_FOUND(404) | unit | Med | VersionService.java:171 |
| TC-VERSION-007 | rawSn/actor null 가드 | null | commitApproved | IllegalArgument/UNAUTHORIZED(401) | unit | Med | VersionService.java:164-169 |
| TC-VERSION-008 | 대용량 라벨 1MB 초과 단순화 후 승인 성공 | 폴리곤 다수 | commitApproved | 단순화·10MB 한도, 승인 차단 안 됨 | integration | High | VersionService.java:92-103,1097- |
| TC-VERSION-009 | 10MB 초과 프레임 SKIPPED | 단순화 후에도 초과 | commitApproved | 해당 프레임만 스킵(skipped++)+ERROR, 전체 승인 유지 | integration | Med | VersionService.java:268-287 |
| TC-VERSION-010 | 동시 승인 스냅샷 직렬화(Race) | 동시 approve | commitApproved | ACTIVE 행 비관적 잠금(findActiveForUpdate) 직렬화 **+ 프레임 행 락(직렬화 앵커) 이후 ACTIVE 재조회**(2026-08-04, D-ISSUE-21 범위 확장 — ACTIVE 행 잠금만으로는 동시 롤백이 새로 활성화한 행을 못 봐 ACTIVE 2건이 남았다). 프레임당 순서 = VERSION 선취 → SRC 앵커 → VERSION 재조회. **5차**: 프레임당 쿼리 3회는 **유지**(선취는 잠금 순서 첫 간선이며 락 보유 시간을 줄이지 못함) 하되, 승인 트랜잭션이 영상 전 프레임의 앵커 락을 커밋까지 누적 보유하는 구간을 **elapsed 로그(INFO) + 3s 초과 WARN** 으로 관측 가능하게 함 | integration | High | VersionService.java:206-231,312-344 |
| TC-VERSION-011 | ~~비식별 신고 스냅샷~~ | 라벨 존재 | snapshotDeidentReport | **[폐기 2026-07-30]** 2026-07-27 정책 반전 — 신고가 라벨을 삭제하지 않으므로(라벨 보존 + 조회 게이트) `snapshotDeidentReport` 및 `SAVE_REASON='DEIDENT_REPORT'` 적재 경로가 **제거**됨(D-ISSUE-25: srcSn 스코프 복원 진입점 없는 write-only 이력이었음) | integration | — | VersionService.java:316-320(제거 사유 주석) |
| TC-VERSION-012 | ~~비식별 신고 — 라벨 0 스킵~~ | 라벨 없음 | snapshotDeidentReport | **[폐기 2026-07-30]** 위와 동일 — 메서드 자체가 존재하지 않음 | unit | — | VersionService.java:316-320 |
| TC-VERSION-013 | 버전목록 조회 IDOR | srcSn 소유 검증 | listVersions | accessGuard 통과만, active 표시 | security | Med | VersionService.java:328-339 |
| TC-VERSION-014 | 스냅샷에 PII/본문 로그 미출력 | 승인 경로 | 로그 검증 | 라벨 본문·PII 미출력(CWE-359), 식별자·건수만 | security | High | VersionService.java:209-210,268-287 |
| TC-VERSION-015 | **버전목록은 신고 구간에도 200**(의도적 게이트 미적용) (신규) | `DE_IDNTF_YN='F'` | GET /v1/frames/{srcSn}/versions | 412 아님 — 200. 응답(VersionItem)에 `LABEL_PAYLOAD` 없음(해시·시각·사유·작성자만) | security | High | VersionService.java:322-339 |
| TC-VERSION-016 | 레거시 rawSn 스코프 스냅샷 잔존 행 (신규) | `DATA_SRC_SN IS NULL` 구 행 존재 | listVersions(srcSn) | 조회 대상 아님(프레임 스코프만), 신규 적재 0건 | integration | Med | VersionService.java:316-320,330 |
| TC-VERSION-017 | 승인 스냅샷 원자성 (신규) | materialize 실패 | approve | APPROVED 전이·스냅샷·통합메타 동결이 **함께 롤백**(같은 트랜잭션) | integration | High | ReviewService.java:493-513 |

## D-5. diff / rollback (TC-DIFF)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-DIFF-001 | diff — ADDED/REMOVED/MODIFIED 분류 | 두 APPROVED 버전 | diff(fromHash,toHash) | 라벨 단위 정확 분류 | unit | High | VersionService.java:347-379 |
| TC-DIFF-002 | diff — SKELETON v 변경 감지 | 키포인트 v만 변경 | diff | MODIFIED 감지(삼중값) **+ 응답에 차이가 드러날 것** — `before != after` 이고 `type="SKELETON"`. ⚠ **현행 미해소**: `ShapeDto.fromPoints` 가 2-stride 로 평탄화해 v 를 버리고 `type="POLYGON"` 으로 내려 `before == after`(3차 D-ISSUE-22 = 1차 D-ISSUE-41 이월) | unit | Med | VersionService.java:1236-1253(readPoints, via parseLabelsById:1143-1171 ← computeLabelDiffs:1124-1137) · version/dto/LabelDiffDto.java:67-75(v 유실 지점) |
| TC-DIFF-003 | diff — 다른 프레임(srcSn) | from/to srcSn 상이 | diff | 빈 결과 | unit | Med | VersionService.java:372-374 |
| TC-DIFF-004 | diff — 해시 형식 위반 | 비-hex/>64/null | diff | INVALID_INPUT(400) | security | High | VersionService.java:1302-1314 |
| TC-DIFF-005 | diff — 존재하지 않는 해시 | 미존재 | diff | NOT_FOUND(404) | unit | Med | VersionService.java:1076-1082 |
| TC-DIFF-006 | diff — 접근권한(IDOR) | 미배정 WORKER | diff | accessGuard 403(양 버전). ⚠ **현행 미해소**: 해시 조회가 인가보다 앞서 존재 해시=403 / 미존재 해시=404 로 갈려 **버전 존재 여부 오라클**(CWE-209) 성립 — 3차 D-ISSUE-25(1차 D-ISSUE-42 이월) | security | High | VersionService.java:351-362 |
| TC-DIFF-007 | diff — 손상 JSON | 파싱 실패 | diff | 빈 리스트(장애 격리) | unit | Low | VersionService.java:1180-1193(특히 1129-1136) |
| TC-DIFF-008 | **rollback 정상 — 재활성 + 본문 복원** | active 아닌 대상 스냅샷 | rollback(hash,srcSn) | ①`LS_DATA_LBL` full-replace(**LBL_SN·AI메타·TRCK_ID 보존 복원**, 점유 PK 만 신규 발급) ②버전 행은 **대상 스냅샷 재활성**(새 행 적층 없음 — `SAVE_REASON='ROLLBACK'` 폐기) ③`LS_DATA_LBL_HSTRY` 에 롤백 이벤트(actor·시각·대상 해시) ④**`REG_USER_NO`(라벨 작성자) 도 보존**. ⚠ **④는 현행 미해소** — 스냅샷 페이로드에 `regUserNo` 가 없고 복원 INSERT 도 그 컬럼을 안 써서 값이 있던 라벨까지 NULL 로 덮인다(통계가 수동 라벨을 자동으로 오분류) — 3차 D-ISSUE-23(1차 D-ISSUE-43 이월). ※AI 메타 행의 `REG_ID` 가 롤백 수행자로 바뀌는 것은 계약대로(결함 아님), 라벨 속성값(`LS_DATA_LBL_ATTR_VAL`)은 스냅샷에 없어 삭제되는 것이 명시된 한계 | integration | **Critical** | VersionService.java:424-551,564-574 · batch/repository/LsDataLblRepositoryImpl.java:26-29(REG_USER_NO 누락 지점) |
| TC-DIFF-009 | rollback — 빈 스냅샷으로 복원 | 라벨 0 스냅샷 | rollback | 프레임 라벨 삭제(빈 상태) | unit | Med | VersionService.java:719-751(삭제),866-868(restore 빈 스냅샷 조기반환) |
| TC-DIFF-010 | rollback — 손상 스냅샷 전체 롤백 | 손상 JSON | rollback | INVALID_INPUT(400), 부분 적용 없음(교체 전 파싱) | unit | High | VersionService.java:455-456 |
| TC-DIFF-011 | rollback — 작업락 영상 차단 | LS_AUTH_WORK_LOCK 잠김 | rollback | CONFLICT(409) | integration | High | VersionService.java:441-444 |
| TC-DIFF-012 | rollback — APPROVED 영상 TASK_MODIFIED 발행 | APPROVED | rollback | `TaskModifiedEvent(LABEL_UPDATED, exportRegenerated=**true**)` → 디바운스 flush 가 export 전량 재생성 후 통지 | integration | High | VersionService.java:545-549 |
| TC-DIFF-013 | rollback — 미APPROVED 통지 미발행 | 미APPROVED | rollback | 미발행(export 재생성도 없음) | unit | Med | VersionService.java:545 |
| TC-DIFF-014 | **rollback 멱등 — 진짜 no-op** | active==대상 해시 **AND** 작업본 라벨 동일 | rollback | 기존 active 반환, **라벨 미재작성(LBL_SN 불변)** · 이력 미기록 · 통지/재생성 미발행 | integration | **Critical** | VersionService.java:510-527 |
| TC-DIFF-015 | rollback — 해시 동일 행 재활성 | UK(srcSn,hash) 존재 | rollback | 그 행 activate + 나머지 active 비활성(신규 행 0) | integration | Med | VersionService.java:564-574 |
| TC-DIFF-016 | rollback — 대상 버전 미존재 | 없는 hash | rollback | NOT_FOUND(404) | unit | Med | VersionService.java:434-435 |
| TC-DIFF-017 | rollback — actor null / IDOR | null / 미배정 WORKER | rollback | 401 / accessGuard 403 | security | High | VersionService.java:426-432 |
| TC-DIFF-018 | rollback — Race 잠금 순서 | 동시 롤백 | rollback | ACTIVE 잠금 → 라벨 교체 직렬화 **+ 종료 후 ACTIVE 행은 정확히 1건**. **2026-08-04 해소(D-ISSUE-21)** — 활성 목록 조회를 **프레임 행 락(직렬화 앵커) 이후로 이동**해 재조회한 값으로만 판정·비활성화한다(락 순서 VERSION→SRC→LBL 는 그대로: 선취 `:467` → 앵커 `:491` → **재조회 `:502`**). 구 동작(앵커 이전 1회 조회 → active 2건 잔존, 재현 2/2)은 폐기. 회귀 가드 = `VersionRollbackRestoreIT.동시_두_롤백_요청_후_ACTIVE_버전이_정확히_1건만_남는다`(pg_locks 로 잠금 대기를 관측해 결정적 재현 — 5차에서 관측 스코프를 `LS_LABEL_VERSION`·`LS_DATA_SRC` 로 한정). **5차 보강**: 이력(`LS_DATA_LBL_HSTRY`) **정확히 2건** 단언 추가(두 롤백이 각자 1회씩 실질 교체 — 중복·누락 기록 검출) | integration | High | VersionService.java:488-532,625-650 · VersionRollbackRestoreIT.java:466-491 |
| TC-DIFF-019 | rollback — SKELETON 삼중값 무손실 복원 | SKELETON 라벨 | rollback | v 보존(rawPointsJson) | unit | Med | VersionService.java:661-664,845-849,874-887 |
| TC-DIFF-020 | isCommittable — PORTAL 채널 배제 | PORTAL 토큰 | isCommittable | false. ⚠ **프로덕션 호출자 0건(dead code)** — D-ISSUE-28 미해소 | unit | Low | VersionService.java:1316-1318 |
| TC-DIFF-021 | **신고 구간 rollback 412** (신규) | `DE_IDNTF_YN='F'` (신고 또는 비식별 실패) | rollback | PRECONDITION_FAILED(412) — 작업락이 없는 배치 실패 경로도 차단(읽기·쓰기 비대칭 제거) | security | **Critical** | VersionService.java:446-451 |
| TC-DIFF-022 | **신고 구간 diff 412** (신규) | `DE_IDNTF_YN='F'` | diff | 412 — 좌표 전문(before/after)이 버전 비교로 새지 않음. 인가 이후 평가, 두 버전 동일 영상이면 1회 조회 | security | **Critical** | VersionService.java:364-370 |
| TC-DIFF-023 | `DATA_SRC_SN` NULL 버전 diff (신규) | 레거시 rawSn 스코프 스냅샷 해시 | diff | 500 아님 — **400**(프레임 단위 비교 대상 아님), 인가 검사 이전 조기 반환 | security | High | VersionService.java:354-359,390-395 |
| TC-DIFF-024 | 잉여 ACTIVE 자기치유 (신규) | active 행 2건 이상 | rollback(교체 경로 · **멱등 경로 모두**) | 정본 1건 외 전부 비활성 — 조기 반환 경로도 동일 처리. **2026-08-04 해소** — 자기치유가 순회하는 목록이 이제 **앵커 이후 재조회분**이라 동시 롤백에서도 잉여 active 가 남지 않는다(구 서술 "단일 스레드에서만 성립" 폐기 — TC-DIFF-018 과 동일 근인이 함께 닫힘) | integration | Med | VersionService.java:532-557,594-604,617-623 |
| TC-DIFF-025 | 프레임 락을 멱등 판정 **이전**에 취득 (신규) | rollback 판정 중 bulkUpsert 커밋 | rollback | "해시 일치 + 라벨 동일" 오판정 불가 — `lockAndReadLabelVersion` 이 판정~복원 구간을 직렬화. 락 순서 VERSION→SRC→LBL(ABBA 방지). ⚠ **2026-08-04 서술 정정**: 구 비고 "앵커 이후의 VERSION 재조회는 이미 선취한 행이라 새 간선을 만들지 않는다"는 **부정확** — 재조회는 선취 시점에 없던 새 ACTIVE 행을 반환할 수 있고 `activateRollbackTarget` 은 선취 대상 아닌 비활성 대상 행을 앵커 보유 상태에서 UPDATE 하므로 SRC→VERSION 잔여 간선이 남아 **이론적 3-트랜잭션 교착(40P01)이 가능**하다. 인지된 잔여 리스크이며 락 순서 규약 재설계는 **별도 이슈 이월**(동작 무변경 — 주석만 정정). 순서 가드 = `VersionServiceRollbackLockOrderTest`(InOrder: VERSION 선취 → SRC 앵커 → VERSION 재조회 → LBL). **5차**: 선취 제거(쿼리 3→2) 제안을 검토 후 **기각** — 선취는 규약의 첫 간선이고 락 보유 시간을 줄이지도 않는다(코드 주석에 근거 고정) | integration | High | VersionService.java:491-548,663-666 |
| TC-DIFF-027 | **동시 롤백 후 버전목록 isCurrent 1건** (신규) | 동시 롤백 2건 | listVersions(srcSn) | `GET /v1/frames/{srcSn}/versions` 의 `isCurrent:true` 가 정확히 1건 — DB ACTIVE 불변식이 API 응답 축에서도 성립(구 동작: 2건 true). **5차**: 이력 2건 단언 추가 | integration | High | VersionService.java:357-368 · VersionRollbackRestoreIT.java:493-513 |
| TC-DIFF-028 | **동시 검수승인 × 롤백 ACTIVE 1건** (신규) | APPROVED 스냅샷 커밋과 롤백이 동시(**롤백 선점 → 승인 대기**) | commitApproved ∥ rollback | 승인 경로도 같은 프레임 행 앵커로 직렬화 → ACTIVE 1건. 구 동작: 승인이 롤백의 재활성 행을 못 봐 새 스냅샷과 함께 **2건 잔존**(D-ISSUE-21 범위 확장분). **5차**: 이력 **1건**(승인은 라벨을 바꾸지 않아 이력 축 없음) 단언 추가 | integration | **Critical** | VersionService.java:312-347 · VersionRollbackRestoreIT.java:516-538 |
| TC-DIFF-029 | **동시 검수승인 선점 → 롤백 대기 ACTIVE 1건** (신규) | 승인이 먼저 프레임 앵커 선점, 롤백이 대기(**TC-DIFF-028 의 역순**) | commitApproved ∥ rollback | ACTIVE 1건 + **최종 정본 = 롤백 대상(v1)** + 라벨 본문도 v1 로 복원 + 이력 1건. 재조회는 **두 경로에 각각** 배선돼 한쪽만 회귀해도 반대 방향 케이스는 통과하므로 양방향을 모두 고정한다(이 케이스는 **롤백 쪽 재조회 누락**을 잡는다) | integration | **Critical** | VersionService.java:491-548 · VersionRollbackRestoreIT.java:540-576 |
| TC-DIFF-026 | 다중 매칭 해시 비결정 선택 (신규) | 서로 다른 프레임에 동일 payload 해시 | diff | 현행 `matches.get(0)` — **비결정적**(D-ISSUE-27 미해소). 기대: 결정적 선택 또는 명시 거부 | unit | Med | VersionService.java:1076-1082 |

## D-6. 관제 통지 (TC-NOTIFY)

> ★ 계약 전면 교체(2026-07): 구 단일 경로 `/api/v1/notify` + `{eventType, payload}` 2단 중첩 바디는 **폐기**됐다.
> 완료 = `POST {base}/api/data-set/v2/jobs/{job_id}/notify-completed`(API-251) · 수정 = `.../notify-updated`(API-285).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-NOTIFY-001 | **TASK_COMPLETED 발행 트리거 = export SUCCEEDED** | 승인 커밋 → export 성공 | ReviewApprovedEvent → DatasetExportBridge → export → DatasetExportCompletedEvent | `onExportCompleted` → `sendCompleted(rawSn)`. **승인 이벤트 직후 발행 아님**(관제가 구 버전 폴더를 픽업하지 않도록 순서 보장) | integration | **Critical** | ControlNotifyEventListener.java:38-41 · DatasetExportBridge.java:36-45 · AsyncDatasetExportRunner.java:67-76 |
| TC-NOTIFY-002 | 승인 롤백 시 통지 미발행 | 승인 tx 롤백 | — | AFTER_COMMIT 미호출 → export·통지 모두 미발생 | integration | High | DatasetExportBridge.java:36-37 |
| TC-NOTIFY-003 | **TASK_COMPLETED 페이로드 = 6필드 평면(snake_case)** | 승인+export 성공 | buildCompleted(rawSn) | `job_id`(=RAW_SN 문자열) · `event_type_cd`(LS_DATA_RAW) · `lclgv_cd` · `lclgv_nm`(MNG_EX_LOCAL_GOV 조인, 없으면 null) · `duration_sec` · `image_count`(=`COUNT(LS_DATA_SRC)` 실측). **상수 self-fill 0**(구 0/0/null 하드코딩 D-ISSUE-41 해소). 라벨·PII·토큰·원본 경로 없음. ⚠ `event_type_cd` 는 `toControlEventTypeCd`(139-141) 항등함수라 매핑 없이 pass-through — DB 에 `INTRUSION`·`EV02000201` 두 코드체계가 공존한 채 전송된다(D-ISSUE-63 이월 / UNCERTAINTIES #27, 관제 코드값 수령 전까지 현행 고정) | security | **Critical** | TaskCompletedPayload.java:24-32 · ControlNotifyPayloadFactory.java:78-92,139-141 |
| TC-NOTIFY-004 | 전송 실패 시 폴백 적재 | client 실패(5xx/타임아웃) | sendCompleted | enqueuePending(PENDING)+`completedFailed` metric | integration | High | ControlNotifyService.java:130-134 |
| TC-NOTIFY-005 | 성공 관찰행 적재 | client 성공 | sendCompleted | recordImmediateSuccess(**실제 전송된** eventType/payload 기준) | integration | Med | ControlNotifyService.java:126-129,318-325 |
| TC-NOTIFY-006 | 관찰행 적재 실패 시 통지 성공 유지 | recordSuccess 예외 | sendCompleted | 예외 삼킴+warn, 통지 성공 유지 | unit | Med | ControlNotifyService.java:318-325 |
| TC-NOTIFY-007 | **TASK_MODIFIED 페이로드 = 변경 파일명 목록** | 승인 후 수정 | buildModified / buildModifiedForAllFrames | `{job_id, changed_items:{images[],jsons[]}}` — 항목은 `{FRM_NO 4자리 zero-pad}.jpg/.json`(예: `0338.jpg`). **SRC_SN·좌표·메타 본문·절대경로 없음**. 두 리스트는 항상 non-null | security | **Critical** | TaskModifiedPayload.java:20-54 · ControlNotifyPayloadFactory.java:103-126 · ExportFileNaming.java:30-58 |
| TC-NOTIFY-008 | **디바운스 축적은 공유 DB(V144)** | 60s 윈도우 다변경 | accumulate×N | `LS_MON_NOTI_ACML` 에 (rawSn, srcSn↔changeType 페어, videoLevel, regen) 누적 — 노드가 죽어도 유실 없음 | integration | **Critical** | ControlNotifyDebouncer.java:195-197 · JpaControlNotifyDebounceStore.java:61-94 · V144__create_ls_mon_noti_acml.sql |
| TC-NOTIFY-009 | 셧다운 시 잔여 drain | 미만료 윈도우 | flushAll(@PreDestroy) | 스케줄러 정지 후 만료 무관 drain, 라운드 상한 20(종료 지연 방지), 남은 건 DB 잔존 | unit | Med | ControlNotifyDebouncer.java:93,211-219,244-252 |
| TC-NOTIFY-010 | **flush 전용 daemon 스케줄러** | — | 부트 | `@Scheduled`/`@EnableScheduling` 비의존 — 단일 데몬 스레드 `control-notify-debounce-flush` 가 10s 간격 tick. `authoring.dataset-export.regen-flush.enabled=false` 면 미기동(테스트 격리) | integration | **Critical** | ControlNotifyDebouncer.java:132-134,141,162-177 |
| TC-NOTIFY-011 | changeType 계약값 검증 | 발행 | — | LABEL_ADDED/UPDATED/DELETED/META_UPDATED 4종 상수(ChangeType.ALL) 정의만 존재. **런타임 검증기 없음(2026-08-03 재확인)** — 프로덕션 경로(`ControlNotifyDebouncer.send/summarizeChangeTypes`)는 값을 검증하지 않고 축적·flush 요약 로그에만 관측 반영, 계약은 javadoc(`LabelService.java:603`)·단위테스트로만 보증 | unit | High | ChangeType.java:18-30 · ControlNotifyDebouncer.java:334-369(검증 없음) · LabelService.java:603(javadoc 계약) |
| TC-NOTIFY-012 | 라벨 수정 통지 — APPROVED 후에만 발행 | bulkUpsert 검수 전 | 저장 | 통지 미발행 | unit | High | LabelService.java:412-426 |
| TC-NOTIFY-013 | 라벨 수정 — 무변경 통지 미발행 | changes 비어있음 | bulkUpsert | 통지·이력·버전 bump 미발생 | unit | Med | LabelService.java:402-413 |
| TC-NOTIFY-014 | **재export 7경로 TASK_MODIFIED(regen=true)** | APPROVED 영상 수정 | 라벨(LabelService)·트랙편집(TrackEditService)·트랙병합(TrackMergeService)·롤백(VersionService)·촬영환경(EnvironmentMetaService)·프레임설명(FrameDescriptionService)·개인정보메타(FramePrivacyMetaService) | 7경로 모두 `exportRegenerated=true` 발행 → 새 버전 폴더 `v{n+1}` 전량 재생성 후 통지 | integration | **Critical** | LabelService.java:423-424 · TrackEditService.java:326 · TrackMergeService.java:201 · VersionService.java:547-548 · EnvironmentMetaService.java:125-126 · FrameDescriptionService.java:60-61 · FramePrivacyMetaService.java:158-159,180-181 |
| TC-NOTIFY-015 | 폴백 적재 idempotency 중복 방지 | 동일 idmpKey 재적재 | enqueuePending | DataIntegrityViolation 삼킴, 멱등 | integration | High | ControlNotifyFallbackService.java:66-96 |
| TC-NOTIFY-016 | 폴백 큐 깊이 상한(DoS) | active>=10000 | enqueuePending | IllegalState 거부+metric(CWE-770) | security | Med | ControlNotifyFallbackService.java:36,75-82 |
| TC-NOTIFY-017 | 폴백 enabled=false 게이트 | 비활성 | enqueue/record | Optional.empty | unit | Med | ControlNotifyFallbackService.java:71-73,113-115 |
| TC-NOTIFY-018 | 재시도 claim 원자 CAS(멀티인스턴스) | 동시 claim | claimForRetry | updated!=1→empty(1인스턴스만) | integration | High | ControlNotifyFallbackService.java:136-143 |
| TC-NOTIFY-019 | 재시도 백오프 스케줄 | 실패 count<max | failAndSchedule | PENDING+2^n분(cap60) | unit | High | LsControlNotifyFallback.java:194-213(백오프 공식 208) |
| TC-NOTIFY-020 | dead-letter 전이 | count>max(5) | failAndSchedule | DEAD_LETTER+dlqDt, nextRtry null | unit | High | LsControlNotifyFallback.java:201-207 |
| TC-NOTIFY-021 | 재시도 잡 — due 항목 처리 | PENDING+nextRtry<=now | runOnce | claim→processOne→markSucceeded, batch 20 | integration | Med | ControlNotifyFallbackRetryJob.java:31,48-71 |
| TC-NOTIFY-022 | 재시도 잡 — 재실패 재스케줄 | processOne 예외 | runOnce | markFailedAndSchedule | integration | Med | ControlNotifyFallbackRetryJob.java:48-71(재실패 catch 62-65) |
| TC-NOTIFY-023 | 에러 메시지 sanitize(토큰/URL/제어문자) | 에러에 토큰/URL/제어문자 | failAndSchedule | 마스킹+제어문자 제거+truncate | security | High | LsControlNotifyFallback.java:246-253(sanitizeError) |
| TC-NOTIFY-024 | 재시도 성공 관찰행 멱등 | 동일 idmpKey 성공 | recordImmediateSuccess | UK 충돌 삼킴, 멱등 | integration | Med | ControlNotifyFallbackService.java:108-129 |
| TC-NOTIFY-025 | **토글 off 시 빈 등록 범위** | `authoring.control-notify.enabled` 미설정 | 컨텍스트 | Client/Service/PayloadFactory/EventListener/RetryJob/TaskQueryController **미생성**. 단 `ControlNotifyDebouncer`·`TaskModifiedAccumulateListener`·`DatasetExportBridge` 는 **항상 등록**(export 재생성은 토글 무관) | integration | **Critical** | ControlNotifyService.java:52-53 · ControlNotifyDebouncer.java:85-87 · TaskModifiedAccumulateListener.java:27-30 |
| TC-NOTIFY-032 | **통지 경로 계약** (신규) | 통지 활성 | sendTaskCompleted / sendTaskModified | `POST /api/data-set/v2/jobs/{jobId}/notify-completed` · `.../notify-updated`, `Content-Type: application/json`, 2xx(200/201/202) 모두 수용 | integration | **Critical** | ControlNotifyClient.java:54-58,77-108 |
| TC-NOTIFY-033 | 자기치유 — 409 completed→updated (신규) | 관제에 job_id 기등록 | dispatchCompleted | 409 수신 시 즉시 `buildModifiedForAllFrames` 로 updated 재전송, 반환 SendOutcome=TASK_MODIFIED, `selfHealCompletedToUpdated` metric | integration | **Critical** | ControlNotifyService.java:215-233 |
| TC-NOTIFY-034 | 자기치유 — 404 updated→completed (신규) | 선행 완료 통지 없음 | dispatchModified | 404 수신 시 completed 로 1회 폴백, `selfHealUpdatedToCompleted` metric | integration | High | ControlNotifyService.java:244-263 |
| TC-NOTIFY-035 | 자기치유 재귀 금지 (신규) | 전환된 통지도 4xx | dispatch* | 2단계에서 종료(무한 왕복 없음) → 폴백 큐로 | unit | High | ControlNotifyService.java:225-231,255-261 |
| TC-NOTIFY-036 | 4xx/5xx 분류 (신규) | 4xx / 5xx 응답 | doPost | 4xx=`ControlNotifyStatusException`(재시도·서킷 제외, 자기치유 발동) / 5xx=IllegalState(재시도·서킷 집계) | unit | High | ControlNotifyClient.java:110-120 |
| TC-NOTIFY-037 | job_id 형식 fail-closed (신규) | 폴백 큐 JSON 오염(`../`, 비숫자) | dispatch* | `\d{1,19}` 불일치 시 IllegalArgument — 경로 세그먼트 조작 차단(CWE-22/88) | security | **Critical** | ControlNotifyService.java:66-73,275-279 |
| TC-NOTIFY-038 | 페이로드 조립 실패 → 통지 유실 금지 (신규) | buildCompleted 예외(DB 장애) | sendCompleted | 통지 폐기 금지 — `PAYLOAD_REBUILD_REQUIRED` 로 큐잉 후 재시도 시점에 **재조립** | integration | **Critical** | ControlNotifyService.java:112-123 · ControlNotifyFallbackRetryJob.java:85-101 |
| TC-NOTIFY-039 | 조립 실패 시 regen 플래그 보존 (신규) | buildModified 예외 | sendModified(regen=false) | REBUILD_REQUIRED 아님 — **빈 changed_items 로 확정 적재**(재시도가 전 프레임을 헛 발송하지 않음). regen=true 면 REBUILD_REQUIRED | unit | High | ControlNotifyService.java:170-188 |
| TC-NOTIFY-040 | **export 실패 시 통지 보류** (신규) | doExport 예외/게이트 차단 | runApprovalAsync · runReExportThenNotify | `DatasetExportCompletedEvent` 미발행 · 통지 콜백 미실행 — 관제가 구 버전 폴더를 픽업하지 않음. ⚠ 판정 단일 원천은 `DatasetExportOutcome.notifiable()`(예외 없는 실패 4종 포함), `null` 도 fail-closed | integration | **Critical** | AsyncDatasetExportRunner.java:67-76,90-106,134-149 · DatasetExportOutcome.java |
| TC-NOTIFY-041 | 실패 export 회수 후 통지 재개 (신규) | FAILED 최신 export, 유예 경과 | DatasetExportFailureRecoverer.recover | 조건부 UPDATE 클레임(RTY_NMTM) 후 `runApprovalAsync` → 성공 시 완료 이벤트 재발행(통지 유실 아니라 **지연**). ⚠ **`NO_INPUT` 종결은 회수 대상이 아니다** — `DatasetExportTxService.loadPreparation`(101-106)이 export 행 자체를 만들지 않아 `findRetryableFailedAnchors`(FAILED 만 스캔)가 못 집고, 디바운스 윈도우는 이미 `complete()` 로 삭제돼 TASK_MODIFIED 가 영구 유실된다(3차 실증, D-ISSUE-41) | integration | **Critical** | DatasetExportFailureRecoverer.java:119-170 · DatasetExportTxService.java:101-106 |
| TC-NOTIFY-042 | 회수기 — 신고 구간은 재시도 예산 미소모 (신규) | `DE_IDNTF_YN='F'` | recover | 클레임 **이전**에 skip(`RTY_NMTM` 미증가) + INFO 로그 — 신고 장기화로 상한 소진되지 않음 | integration | High | DatasetExportFailureRecoverer.java:141-144 |
| TC-NOTIFY-043 | **재export 트리거는 통지 토글과 무관** (신규) | `authoring.control-notify.enabled=false`(dev/stg/prd 기본) | APPROVED 영상 라벨 수정 | 축적 리스너·디바운서 동작 → **export 새 버전 재생성 발생**(통지만 생략, notifyService=null) | integration | **Critical** | TaskModifiedAccumulateListener.java:27-37 · ControlNotifyDebouncer.java:334-357 |
| TC-NOTIFY-044 | 크로스노드 1회 flush (신규) | 2노드가 같은 윈도우 후보 조회 | claim | 조건부 원자 UPDATE 로 **한 노드만** 스냅샷 획득 → export/통지 1회(구 인메모리: 2회) | integration | **Critical** | ControlNotifyDebouncer.java:259-271,290-318 · JpaControlNotifyDebounceStore.java:102-110 · LsMonNotiAcmlRepository.claimForFlush |
| TC-NOTIFY-045 | flush 실패 시 임차 회수 (신규) | send/재산출 위임 예외 | claimAndSendIsolated | `complete()` 미호출 → FLUSHING 잔존 → 임차(최소 60s, 기본 300s) 만료 후 재클레임. `dropped` metric+ERROR, 루프는 계속 | integration | High | ControlNotifyDebouncer.java:96(MIN_LEASE),290-318 |
| TC-NOTIFY-046 | 폴백 큐 적재 실패 격리 (신규) | 큐 만재 | enqueueQuietly | 예외 밖으로 전파 금지(디바운스 루프 보호) + `control.notify.dropped` 카운터 + ERROR | security | High | ControlNotifyService.java:281-309 |
| TC-NOTIFY-047 | `lclgv_nm` 조달 규칙 (신규) | 지자체 마스터 미존재 / `USE_YN<>'Y'` / 100자 초과 | buildCompleted | 각각 null · null(폐지 명칭 미전송) · 앞 100자 절단 | unit | Med | ControlNotifyPayloadFactory.java:65-66,150-174 |
| TC-NOTIFY-048 | changed_items 미해석 프레임 제외 (신규) | 타 영상 srcSn / 한쪽 벌만 보유 프레임 | buildModified | 해당 파일명 제외(관제 404 방지) + WARN(식별자만) + `unresolvedFrame` metric | security | High | ControlNotifyPayloadFactory.java:185-219 |
| TC-NOTIFY-049 | 전량 재생성 통지 대상 프레임 (신규) | 원천 이미지 미보유 프레임 혼재 | buildModifiedForAllFrames | `findExportableFrameNosByRawSn` 결과만 — writer 가 skip 할 프레임은 미포함 | unit | High | ControlNotifyPayloadFactory.java:120-126 |
| TC-NOTIFY-050 | 신고 해소 시 보류분 복구 (신규) | `'F'→'Y'` resolve, APPROVED 영상 | DeidentReportResolvedEvent(AFTER_COMMIT) | `runApprovalAsync` 재트리거 → export 재산출 + 보류됐던 완료 통지 재개(신고 차단은 export 행을 남기지 않아 회수기가 못 집는다 — **유일한 복구 경로**) | integration | **Critical** | DatasetExportBridge.java:81-88 |
| TC-NOTIFY-051 | 통지 요청 인증 헤더 (신규) | 통지 활성, 토큰 설정됨 | 목서버 수신 헤더 검사 | **[정정 2026-08-03, D-ISSUE-62 해소]** 인증 헤더 **부착됨** — `controlNotifyWebClient` 빈이 설정 토큰을 `x-access-token` 기본 헤더(defaultHeader)로 부착. 단 `enabled=true`인데 토큰이 공백이면 예외가 아니라 **WARN만**(fail-open) — 무인증 전송이 가능한 잔여 갭. **[3차 실증]** 로컬 실효값에 `CONTROL_NOTIFY_TOKEN` 이 없어 실제로 전 통지가 헤더 없이 나갔다(D-ISSUE-42) | integration | High | WebClientConfig.java:75-118(토큰 상수 76, 부착·경고 111-115) · ControlNotifyClient.java:90-108(요청별 헤더 미설정, 빈 기본헤더에 의존) |

## D-7. 관제 조회 API (TC-NOTIFY, 조회)

> ★ **UNCERTAINTIES #4 반전(2026-07)**: "본인 배정 검증 부재 = 의도된 광범위 허용" 은 더 이상 유효하지 않다.
> 세 경로 모두 진입부에서 `LabelAccessGuard.verifyRawAccess`(REVIEWER 전체 / WORKER 본인 LABELER 배정)를 적용한다(DEV_FIX H-1).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-NOTIFY-026 | 요약 조회 — REVIEWER/WORKER | 인증 + 접근권한 | GET /v1/tasks/{rawSn}/summary | 프레임/라벨/메타 카운트+상태+최종수정일 | integration | Med | TaskQueryController.java:66-73 · TaskQueryService.java:74-98 |
| TC-NOTIFY-027 | 라벨 조회 — 파일경로 미포함(Privacy) | 인증 | GET .../labels | LabelItem 에 filePath/deIdntf 경로 없음(CWE-359) | security | High | TaskLabelsResponse.java:1-21 · TaskQueryService.java:230-245(toLabelsResponse) |
| TC-NOTIFY-028 | **라벨 조회 — 페이징 + frameIds 상한** | frameIds 지정 | GET .../labels?frameIds=&page=&size= | `Page<TaskLabelsResponse>` (기본 20 / 상한 100 클램프), frameIds `@Size(max=100)` 초과 시 400 (CWE-770 — D-ISSUE-45 해소). ⚠ 400 응답 message 에 컨트롤러 메서드명(`getLabels.frameIds: …`)이 노출되는 D-ISSUE-24 는 **미해소 이월**(CWE-209, 3차 실측) | security | High | TaskQueryController.java:87-103 · TaskQueryService.java:54-57,107-110,202-208 |
| TC-NOTIFY-029 | **메타 조회 — 페이징** | 인증 | GET .../meta | META_SN/KEY/VL 목록, 기본 20 / 상한 100 | unit | Low | TaskQueryController.java:114-123 · TaskQueryService.java:147-150 |
| TC-NOTIFY-030 | 조회 — 영상 미존재 | 없는 rawSn | summary/labels/meta | NOT_FOUND(404) | unit | Med | TaskQueryService.java:225-228(findRawOrThrow) |
| TC-NOTIFY-031 | 조회 — 미인증/권한없음 | 토큰 없음/PORTAL | GET /v1/tasks/** | 401/403(@PreAuthorize). 토글 off 면 컨트롤러 부재로 404 | security | High | TaskQueryController.java:46,67,86,115 |
| TC-NOTIFY-052 | **rawSn 순회 IDOR 차단** (신규) | 배정 이력 없는 WORKER | GET /v1/tasks/{임의 rawSn}/summary\|labels\|meta | 403 — 역할만으로 통과 불가(`verifyRawAccess`). REVIEWER 는 통과 | security | **Critical** | TaskQueryController.java:71,96,121 |
| TC-NOTIFY-053 | 관제 라벨 조회 신고 게이트 412 (신규) | `DE_IDNTF_YN='F'` | GET /v1/tasks/{rawSn}/labels | **412** (좌표 전문 차단). 인가 **이후** 평가, 역할 무관. summary/meta 는 좌표 미포함이라 대상 아님 | security | **Critical** | TaskQueryController.java:97-101 |

## D-8. 데이터마트 View (TC-MARTVIEW)

> ★ 구속 정책(CLAUDE.md): **검수 완료·통지 건의 관제 접근은 어떤 사유로도 차단하지 않는다.** 비식별 신고 구간(`DE_IDNTF_YN='F'`)에도
> 뷰에서 행을 감추거나 경로를 NULL 로 비우지 않는다 — 뷰·통지 경계에는 신고 게이트를 **적용하지 않는 것이 정본**이며 결함으로 재분류하지 않는다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거 |
|----|---------|------|----------|---------|------|:--:|------|
| TC-MARTVIEW-001 | V_COMPLETED_VIDEO — APPROVED 게이트 | APPROVED/미APPROVED 혼재 | SELECT | APPROVED만(INNER JOIN + `s.DATA_STTS_CD='APPROVED'`) | integration | **Critical** | V138:80-101 |
| TC-MARTVIEW-002 | V_COMPLETED_VIDEO — export 경로/프레임수 | 최신 SUCCEEDED/PARTIAL export | SELECT | `EXPORT_PATH_NM`(**영상 루트** `{dirname(원본)}/{rawSn}`)·`FRAME_CNT`(LATERAL LIMIT 1, EXPORT_VER_NO DESC) | integration | High | **V160:84-99**(구 V138:76-90 — E-ISSUE-81로 대체) · DatasetExportTxService.java:174-180(EXPORT_PATH_NM),186-195(FRAME_CNT는 markSucceeded/markPartial 별도) — **[실동작]** rawSn=101(SUCCEEDED) `export_path_nm=/app/storage/raw/seed/101, frame_cnt=20` · rawSn=94(최신버전=PARTIAL) `export_path_nm=/app/storage/raw/autolabel-test/94, frame_cnt=10, export_stts_cd=PARTIAL` 확인 |
| TC-MARTVIEW-003 | V_COMPLETED_VIDEO — 미export null·1row | export 없음 | SELECT | 두 값 null, 영상당 1행 | integration | High | **V160:92-99**(구 V138:83-90) — **[실동작]** rawSn 15/8/9(export 0건) 두 컬럼 null 확인, 전체 뷰 `count(*)=count(distinct raw_sn)=16`으로 1행 불변 확인 |
| TC-MARTVIEW-004 | **V_COMPLETED_VIDEO — PARTIAL export 포함**(2026-08-02 정책 반전, E-ISSUE-81 해소) | 진행중/부분 | SELECT | ~~SUCCEEDED만 조인~~ → **SUCCEEDED + PARTIAL 조인**(FAILED/PENDING만 제외), 맨 끝 `EXPORT_STTS_CD` 컬럼으로 부분산출 식별 가능 | integration | Med | **V160:87,97**(V138 당시의 "SUCCEEDED만" 기대값은 2026-08-02 E-ISSUE-81 수정으로 폐기됨 — 카탈로그 드리프트 정정, 2026-08-04 3차 D-part5) — **[실동작]** rawSn=94: export 이력(v1 SUCCEEDED→v2 PARTIAL→v3 FAILED→v4 PARTIAL→v5 FAILED→v6 PARTIAL) 중 뷰는 최신 v6(PARTIAL)을 노출, FAILED(v3/v5)는 건너뜀 — `pg_get_viewdef` 로 라이브 정의가 V160과 100% 일치함도 확인 |
| TC-MARTVIEW-005 | V_COMPLETED_VIDEO — ACTIVE_YN 스냅샷 게이트 | 비활성 메타 | SELECT | `m.ACTIVE_YN='Y'` 만 | unit | Med | V138:100 |
| TC-MARTVIEW-006 | **V_COMPLETED_FRAME — 동일경로 행 fail-closed 제외** | 원본==비식별 경로인 결함 행 | SELECT | 해당 행 제외(D-ISSUE-46). ⚠ 한쪽이 NULL/공백인 **결측 행은 통과**(증강 파생·RAW only 추출 전량 소실 방지) | security | **Critical** | V133:40-67 |
| TC-MARTVIEW-007 | V_COMPLETED_FRAME — DESCRIPTION 노출 | FRM_EXPLN 존재 | SELECT | DESCRIPTION 컬럼(끝 추가), 출력 컬럼은 V104/V107 과 1:1 | unit | Low | V133:41-50 |
| TC-MARTVIEW-008 | **V_COMPLETED_LABEL_CHANGE — 건수만 노출** | LS_DATA_LBL_HSTRY 존재 | SELECT | `LBL_HSTRY_SN/RAW_SN/SRC_SN/ADD_CNT/MDFCN_CNT/DEL_CNT/REG_ID/REG_DT` — **`CHG_DTL_CN`(좌표 본문) 미노출**(V137, D-ISSUE-47 해소) | security | **Critical** | V137:22- · V139:25-43 |
| TC-MARTVIEW-009 | V_COMPLETED_LABEL_CHANGE — APPROVED 게이트 | 미APPROVED 변경점 | SELECT | 제외(EXISTS APPROVED) | integration | High | V139:38-43 |
| TC-MARTVIEW-010 | V_COMPLETED_META — RVW_STTS_CD APPROVED만 | 승인/미승인 | SELECT | `mrev.RVW_STTS_CD='APPROVED'` + 영상 APPROVED | integration | High | V107:124-146 |
| TC-MARTVIEW-011 | V_COMPLETED_META — video.* 기술메타 제외 | META_KEY `video.*` | SELECT | 시계열/VLM 메타만(`NOT LIKE 'video.%'`) | unit | Med | V107:140 |
| TC-MARTVIEW-012 | 라벨 내용 뷰 제거 확인 | — | SELECT V_COMPLETED_LABEL(_ATTR) | 뷰 부재(계약변경) | integration | Med | V114:30-31 |
| TC-MARTVIEW-013 | 뷰 멱등성(CREATE OR REPLACE) | Flyway 재실행 | 마이그레이션 | 재실행 안전, 컬럼 순서/타입 보존(REPLACE 는 끝 추가만 허용) | integration | Low | V138:28-31 · V139:12-15 · **V160:35-37**(V138 정의 100% 보존 + 끝에 1컬럼만 append, 같은 원칙 재확인) — **[실동작]** 3차 stack-bringup에서 V159~V163 5건 연속 Flyway 적용 성공 확인 + `\d v_completed_video` 로 V160 SELECT 컬럼 순서·타입과 라이브 뷰가 정확히 일치함을 재확인(REPLACE 안전성 실증) |
| TC-MARTVIEW-014 | 승인→materialize→rollback 정합 | 승인 후 롤백 | 통합 시나리오 | 뷰·스냅샷 정합(롤백은 재활성이므로 활성 스냅샷 1건 유지). **전용 IT 존재 확인(2026-08-03 재확인 — D-ISSUE-50 해소)**: 강제 materialize 실패 시 `LS_RAW_DATA_STATUS`/`LS_LABEL_VERSION`/`LS_DATASET_VIDEO_META`/`LS_META_REPL_OUTBOX` 동시 롤백 검증 | integration | Med | DatasetMaterializeApproveRollbackIT.java(159줄, dataset/service/) |
| TC-MARTVIEW-015 | **비식별 영상 경로 노출(V138)** (신규) | 비식별 SUCCEEDED procLog 존재 | SELECT | 맨 끝 컬럼 `DE_IDNTF_FILE_PATH_NM` = 적재값 **원문 그대로**(치환·조합 금지, 파일명은 mock=`deidentified.mp4` / KPST=`{stem}-mask{ext}` 로 다름). 최신 1건(REQ_DT DESC, PROC_LOG_SN DESC), 미완료면 null·행 보존 | integration | **Critical** | V138:78-99 |
| TC-MARTVIEW-016 | **파생영상 ORIGINAL_VIDEO_PATH = NULL** (신규) | `ORGNL_RAW_SN` non-null(증강·해상도 파생) | SELECT | `ORIGINAL_VIDEO_PATH` NULL(동결 시 `RAW_FILE_PATH_NM=null`) — 파생엔 원본이 없다. 관제는 `DE_IDNTF_FILE_PATH_NM` 으로 픽업. **관제 협의 대상 BREAKING** | integration | **Critical** | DatasetVideoMetaSnapshotService.java:129-138,164**(구 123-132,158 — 2026-08-05 실측 드리프트 정정)** · V138:50 — **[실동작]** rawSn=18(orgnl_raw_sn=4) `original_video_path IS NULL` 확인(라이브 `raw_file_path_nm` 빈 문자열→NULL 정규화 포함) |
| TC-MARTVIEW-017 | **변경 0건 행 제외(V139)** (신규) | 롤백 감사(델타 0) 이력 — 구 예시 '개인정보 리셋 감사' 는 2026-08-04 리셋 폐기(`5cf4f778`)로 **신규 발생 0**, 과거 행에만 존재하므로 예시에서 제외 | SELECT V_COMPLETED_LABEL_CHANGE | `ADD_CNT+MDFCN_CNT+DEL_CNT > 0` 인 행만 — 테이블 행은 감사 근거로 보존, 노출면만 축소 | integration | High | V139:37 |
| TC-MARTVIEW-018 | **신고 구간에도 뷰 노출 유지**(확정 정책) (신규) | APPROVED 영상이 `DE_IDNTF_YN='F'` | SELECT V_COMPLETED_VIDEO / _FRAME | 행이 사라지지 않고 `EXPORT_PATH_NM`·`DEIDENTIFIED_PATH` 도 NULL 로 비워지지 않음. `DE_IDNTF_YN` 컬럼으로 관제가 자체 판단 가능. **결함으로 재분류 금지** | integration | **Critical** | V138:49,80-101 (CLAUDE.md "검수 완료·통지 건 관제 접근 보장") |
| TC-MARTVIEW-019 | 촬영환경 self-fill 제거 — 미입력은 NULL (신규) | 수동 입력 없는 영상 승인 | SELECT DAY_NGT_CD/SESN_CD/WTHR_NM | `SHT_DT` 규칙 파생(NGT/SUMMER) **없음** — null(미상) 동결. 공백은 null 정규화 | integration | **Critical** | DatasetVideoMetaSnapshotService.java:106-122 · V145 |
| TC-MARTVIEW-020 | 레거시 파생 동결값 정정 백필 (신규) | 라이브 raw NULL + 활성 스냅샷 non-null(=파생 증명) | correctDerivedShootingEnvironment | 대상만 null 재동결(날씨·역방향 제외), 1건마다 `TaskModifiedEvent(regen=true)` → export 재생성, 재실행 no-op | integration | High | DatasetVideoMetaBackfillService.java:156-231 · DatasetVideoMetaEnvCorrectionTx.java:68-83 |
| TC-MARTVIEW-021 | 백필 dev API 노출 통제 (신규) | prd / dev·stg | `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets`(dry-run) · `POST .../shooting-env-corrections` | prd 는 빈 미등록(404), 그 외 REVIEWER 만 호출 가능(403 otherwise). 쿼리파라미터 행위분기(`?dryRun=`) 미사용 | security | High | DatasetVideoMetaBackfillDevController.java:64-97 |
| TC-MARTVIEW-022 | 백필 폭주 방지 (신규) | 대량 대상 | correct 1회 실행 | `maxPerRun` 상한 + 배치 페이징, 시작/잔여 건수 로그, 잔여는 다음 실행에서 이어서 처리 | integration | Med | DatasetVideoMetaBackfillService.java:182-200 |
| TC-MARTVIEW-023 | 뷰 필터 반증 데이터 (신규) | `ACTIVE_YN='N'` 스냅샷 1건 + `META_KEY='video.codec'` 1건 시드 | 4뷰 SELECT | 두 반례 모두 0행(게이트 실효 확인 — D-ISSUE-49 커버리지 갭 해소용 시드 요구) | integration | Med | V138:100 · V107:140 |

> **불확실 항목**: TASK_COMPLETED idempotency 경계는 **관제 응답이 진실원**으로 확정(로컬 플래그 없음, 409/404 자기치유).
> 잔여: 관제 `event_type_cd` 8대 코드값 목록 미수령(현재 보유값 그대로 전송) · 통지 인증 헤더(`x-access-token`) 미배선 → [UNCERTAINTIES.md](UNCERTAINTIES.md)
