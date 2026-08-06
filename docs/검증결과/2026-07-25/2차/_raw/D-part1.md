# D 클러스터 part1 (D-1·D-2) 2차 검증 결과

> 대상: `docs/test-cases/D-review-version-notify.md` **D-1. 검수 워크플로우(TC-REVIEW-001~030, 035~052 · 48건)** + **D-2. 검수 상태머신(TC-REVIEW-031~034 · 4건)** = **52건**
> 취소선(폐기) 행: **D-1/D-2 구간 0건** — 집계 제외 대상 없음(48+4=52 전건 검증).
> 실행: 2026-07-31 03:35~03:47 KST · backend `localhost:18081`(이미지 HEAD `ca3c712b` 재빌드본) · postgres `public` 스키마 · mock-server `:9400`
> 소스/설정/테스트 **무수정**, 빌드·테스트 **미실행**, backend 컨테이너 **재기동 안 함**.

## 집계

| 판정 | 건수 | 비율 |
|:--:|--:|--:|
| PASS | **49** | 94.2% |
| PARTIAL | **3** | 5.8% |
| FAIL | 0 | — |
| BLOCKED | 0 | — |
| N/A | 0 | — |
| 확인필요 | 0 | — |
| **합계** | **52** | |

| 구간 | 총 | PASS | PARTIAL |
|---|--:|--:|--:|
| D-1 (48) | 48 | 46 | 2 (TC-REVIEW-047 · 050) |
| D-2 (4) | 4 | 3 | 1 (TC-REVIEW-034) |

- **실동작 판정 43건 / 정적·테스트 대조 판정 9건**(내부 드리프트·10MB 초과 페이로드·미존재 데이터처럼 실동작 유발이 구조적으로 불가한 케이스).
- 신규 이슈 **5건**(HIGH 1 · MEDIUM 2 · LOW 2). 근거 드리프트 **3건**.
- 상태머신·동시성·인가는 **반증 시나리오를 실제로 던져도 전부 방어**됐다(불법 전이 11종 차단, 승인 10병렬 → 1건만 성공, 승인 vs 반려 혼합 6병렬 → 1건만 성공, 부수효과 중복 0).

### 사용·변경한 데이터 (다른 에이전트 주의)

| rawSn | 착수 시 | 종료 시 | 비고 |
|---:|---|---|---|
| **126** | APPROVED / export v1·v2 SUCCEEDED | **불변** | 조회만 함(목록·정렬 대상). **E 클러스터 참조 데이터 보존 확인** |
| **133** | ASSIGNED / `DE_IDENT_YN='F'` OPEN | **불변** | 게이트 대조 목적 조회만. 신고 상태 유지 |
| 129·130·131(파생) | 상태행 없음 | **불변** | 승인·반려 시도 전부 404(상태행 미생성) — 데이터 변화 없음 |
| **132** | PENDING(REVIEW_PENDING) | **APPROVED** | ⚠ 프롬프트 허용 범위. 동시 승인 경합 검증에 사용 → 이제 "검수 대기 실데이터"가 아니다. 대기건이 필요하면 새 영상을 만들 것 |
| **146** | ASSIGNED(라벨 13) | **APPROVED**(export v3) | 본 검증의 주 워크플로 영상. 상태 전이 20여 회, event_annotation 1건 생성 |
| **151** | PENDING(라벨 0) | **APPROVED** | negative sample 승인(TC-036) 대상 |
| 128·134·147 | — | **불변** | 불법 전이 시도만(전부 400, 상태 불변) |

- 임시로 postgres `log_statement='all'` 을 약 5초간 켰다가 `ALTER SYSTEM RESET` 으로 원복했다(TC-REVIEW-028 쿼리 계수용). `pg_stat_statements` 확장은 생성 시도 후 즉시 `DROP` 했다.
- `LS_DATA_RAW.DATA_STTS_CD` 는 어떤 케이스에서도 직접 UPDATE 하지 않았다. 모든 상태 전이는 정규 API 경유다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | **2차 실측** | 판정 |
|---|---|---|:--:|
| **D-ISSUE-03** | `ReviewStateMachine.verify(null, …)` 가 400 아닌 NPE(500) | **미해소.** `ReviewStateMachine.java:53-64` 에 여전히 null 가드 없음. `ALLOWED` 는 `Map.of(...)`(`ImmutableCollections.MapN`)라 `:59 ALLOWED.get(null)` 이 `probe()` 에서 NPE → `:60` 의 `allowed == null` 분기 미도달. `ReviewStateMachineTest`(7 케이스) 에도 null/COMPLETED/PROCESSING 전용 케이스 **없음**. HTTP 도달 경로는 여전히 0(컬럼 `nullable=false`) | **이월(D-ISSUE-04)** |
| **D-ISSUE-04** | 라벨 0건·프레임 부재 영상도 승인되어 데이터마트에 빈 레코드 노출 | **✅ 해소.** `ReviewService.resolveNoLabelApproval`(543-565) 신설 — 라벨 0건 + 확인없음 → **409 `REVIEW_NO_LABEL`**(실측), 상태 IN_REVIEW 유지. 검수자 명시 확인(`noLabelConfirmed=true`) 시에만 200 + `LsTaskEventLog.approveWithoutLabel`(실측 `rsn='라벨 없음 확인 승인(negative sample)'`). 라벨 있는데 확인 플래그 보내면 **400**(플래그 상시화 차단). 전이 검증이 라벨 게이트보다 **먼저**라 PENDING+라벨0 은 409 아닌 **400** 유지 | **해소** |
| **D-ISSUE-05** | IN_REVIEW 상태 재배정 허용 | **미해소(D-3 소관).** `AssignmentService.reassign`(:202) 가드는 여전히 `STTS_APPROVED` 단일 값만 본다 → IN_REVIEW/PENDING 재배정 허용. 본 파트(D-1/D-2)에는 대응 케이스가 없어 정적 재확인만 기록 | **이월(D-3)** |
| (참고) D-ISSUE-01 | 배정이 APPROVED 영상을 무검증 강등 | **✅ 해소(D-3 소관).** `AssignmentService.rejectApprovedTargets`(141-149) 신설 — `ASSIGNMENT_ALREADY_COMPLETED`(409). 신규 배정·재배정 두 경로 동일 에러코드 | 해소 |
| (참고) D-ISSUE-02 | 동시 재배정 미직렬화 | D-3 소관 — 본 파트 범위 밖(미검증) | — |
| UNCERTAINTIES **★2** | `/v1/reviews*` = lenient 200 폴백 | **정책대로 동작 확인.** 미등록 키 5종(`filePath`/`rawFilePathNm`/`id`/`; DROP TABLE …`/`1=1`) 전부 **200 + `UPD_DT DESC` 폴백 + WARN 로그**. 정렬 항목 5개·40개도 **200 폴백**. ⚠ **통일 제안 없음** | 확정 정책 준수 |
| UNCERTAINTIES #26 | 관제 통지에 `x-access-token` 미부착 | D-part2(TC-NOTIFY) 소관 — 본 파트 미검증 | — |

---

## ★상태전이 실측표

> 전부 실제 HTTP 호출. 시작상태는 호출 직전 `LS_RAW_DATA_STATUS.DATA_STTS_CD` DB 실측값.

### 허용 전이 7종 (정본) — 전부 실동작 성공

| # | 시작상태 | 요청 | 역할 | 기대 | **실측** | 부수효과(실측) |
|:--:|---|---|:--:|:--:|:--:|---|
| 1 | ASSIGNED | `POST /reviews/146/submit` | WORKER(2001) | 200 PENDING | **200 PENDING** | `LS_TASK_EVENT_LOG` evnt_id=22 `SUBMIT` actor=2001, ver 3→4 |
| 2 | PENDING | `POST /reviews/146/start` | REVIEWER(1001) | 200 IN_REVIEW | **200 IN_REVIEW** | ver 6→7 (이벤트로그 없음 — 설계상 start 는 미기록) |
| 3 | PENDING | `POST /reviews/146/cancel-submit` | WORKER(2001) | 200 ASSIGNED | **200 ASSIGNED** | evnt_id=23 `CANCEL_SUBMIT`, `rsn=NULL`(PII 미포함), ver 4→5 |
| 4 | IN_REVIEW | `POST /reviews/146/approve` | REVIEWER(1001) | 200 APPROVED | **200 APPROVED** | 아래 "승인 부수효과" 참조 |
| 5 | IN_REVIEW | `POST /reviews/146/reject` | REVIEWER(1001) | 200 REJECTED | **200 REJECTED** | `LS_DATA_ISSUE` sn=2 `REJECTION/RESOLVED/reported=1001` + evnt_id=25 `REJECT`(rsn 포함) |
| 6 | REJECTED | `POST /reviews/146/submit` | WORKER(2001) | 200 PENDING | **200 PENDING** | evnt_id=26 `SUBMIT`, ver 8→9 |
| 7 | APPROVED | `POST /reviews/146/submit` | WORKER(2001) | 200 PENDING(동일 rawSn) | **200 PENDING** | rawSn 불변(146), `LS_LABEL_VERSION` 건수 불변(2건, ver_no 최대 1) = **버전업 아님** |

### 불허 전이 — 전부 차단 (상태 불변 DB 확인)

| 시작상태 | 요청 | 역할 | 기대 | **실측** | 메시지 |
|---|---|:--:|:--:|:--:|---|
| ASSIGNED(147) | approve | REVIEWER | 400 | **400 INVALID_INPUT** | `허용되지 않은 상태 전이입니다 (ASSIGNED → APPROVED).` |
| ASSIGNED(147) | start | REVIEWER | 400 | **400** | `(ASSIGNED → IN_REVIEW)` |
| ASSIGNED(147) | reject | REVIEWER | 400 | **400** | `(ASSIGNED → REJECTED)` |
| PENDING(128) | approve | REVIEWER | 400 | **400** | `(PENDING → APPROVED)` |
| PENDING(128) | reject | REVIEWER | 400 | **400** | `(PENDING → REJECTED)` |
| IN_REVIEW(146) | submit | WORKER | 400 | **400** | `(IN_REVIEW → PENDING)` |
| IN_REVIEW(146) | cancel-submit | WORKER | 400 | **400** | `(IN_REVIEW → ASSIGNED)` |
| REJECTED(134) | approve | REVIEWER | 400 | **400** | `(REJECTED → APPROVED)` |
| REJECTED(134) | start | REVIEWER | 400 | **400** | `(REJECTED → IN_REVIEW)` |
| REJECTED(134) | reject(재반려) | REVIEWER | 400 | **400** | `(REJECTED → REJECTED)` |
| **APPROVED(146)** | approve | REVIEWER | **409** | **409 CONFLICT** | `이미 APPROVED 된 영상은 재검수 재제출(PENDING) 외 상태 변경이 불가합니다.` |
| **APPROVED(146)** | start | REVIEWER | **409** | **409** | 동상 |
| **APPROVED(146)** | reject | REVIEWER | **409** | **409** | 동상 |
| **APPROVED(146)** | cancel-submit | WORKER | **409** | **409** | 동상 |
| COMPLETED / PROCESSING | 전 요청 | — | 400 | **정적 성립·실동작 도달 불가** | `ALLOWED` 맵 미등록 키 → `INVALID_INPUT`. `LS_RAW_DATA_STATUS` 에 `COMPLETED` 를 쓰는 코드 경로 **0건**(전수 확인: `transitionTo` 호출자 5곳 전부 `ReviewService`, `BatchTransitionService` 는 완료 시 **ASSIGNED 복귀**(`:152`)·`COMPLETED` 는 `LS_DATA_RAW` 에만 기록) |
| **null** | verify(null, PENDING) | — | 400 | **NPE → 500 (미해소)** | D-ISSUE-04 |

### 인가·소유권 (전부 실동작)

| 시작상태 | 요청 | 역할 | 기대 | **실측** | 메시지 |
|---|---|:--:|:--:|:--:|---|
| PENDING | approve | WORKER(2001) | 403 | **403** | `권한이 없습니다.`(@PreAuthorize) |
| PENDING | start | WORKER(2001) | 403 | **403** | 동상 |
| ASSIGNED | submit | REVIEWER(1001) | 403 | **403** | 동상 (서비스층 "WORKER 권한이 필요합니다" 는 2중 방어라 HTTP 미도달) |
| ASSIGNED | submit | **미인증** | 401 | **401** | `인증이 필요합니다.` |
| ASSIGNED | submit | WORKER(2002, 미배정) | 403 | **403** | `본인에게 배정되지 않은 영상입니다.`(IDOR) |
| — | `GET /reviews/132` | WORKER(2002, 미배정) | 403 | **403** | IDOR 차단 |
| — | `GET /reviews/132` | PORTAL_USER | 403 | **403** | 채널 격리 |
| — | `GET /reviews` | WORKER / PORTAL / 미인증 | 403/403/401 | **403 / 403 / 401** | — |
| — | `GET /reviews/132` · `/frames` | **REVIEWER(1002, 미배정)** | 200(현재 정책) | **200 / 200** | UNCERTAINTIES 확정 — REVIEWER 광범위 허용(`ReviewControllerTest:REVIEWER는_본인_검수자_미배정_영상도_검수_가능_현재정책`) |
| — | 변조 토큰 / `Bearer` 누락 | — | 401 | **401 / 401** | — |

### 동시성 (반증 시나리오)

| 시나리오 | 대상 | 결과 | 부수효과 중복 여부 |
|---|---|---|---|
| **승인 10병렬**(REVIEWER 1001×5 + 1002×5) | 132 (IN_REVIEW, 라벨 28) | **200 ×1 / 409 ×9** (`다른 검수자가 먼저 처리했습니다.`) | `LS_TASK_EVENT_LOG` `APPROVE` **정확히 1건** · `LS_LABEL_VERSION` **6행/6 distinct src** · `LS_DATASET_EXPORT` **1행**(v1 SUCCEEDED, frame_cnt=12) · `LS_CONTROL_NOTIFY_FALLBACK` **1행**(TASK_COMPLETED/SUCCESS) — **중복 0** |
| **승인 3 + 반려 3 혼합 병렬**(서로 다른 REVIEWER) | 146 (IN_REVIEW) | **approve 200 ×1 / approve 409 ×2 / reject 409 ×3** | 30초 창 내 `APPROVE` 1건 · `REJECT` 0건 · `LS_DATA_ISSUE` 신규 0행(반려 tx 전건 롤백) · export 1행 · 통지 1행 |
| **cancel-submit vs start 경합 ×3라운드** | 146 (PENDING) | 매 라운드 **cancel 409 / start 200** | `[Review] optimistic lock conflict on cancelSubmit` WARN ×3, 상태는 매번 IN_REVIEW 1회만 전이 |

낙관적 잠금 실효 근거: `LsRawDataStatus.java:53-55 @Version` + `ReviewService` 의 `reviewRepository.flush()` 3곳(:431-436 / :487-492 / :591-596). backend 로그에 `StaleStateException: Batch update returned unexpected row count from update [0]` 이 패자 수만큼 기록됨(응답에는 미노출).

### 승인 부수효과 (TC-REVIEW-011·015·016 실측 — rawSn 146, 03:42:16)

| # | 부수효과 | 실측 | 순서(로그 타임스탬프) |
|:--:|---|---|---|
| 1 | 상태 전이 APPROVED | `LS_RAW_DATA_STATUS` APPROVED, ver++ | — |
| 2 | 이벤트로그 | evnt_id=75 `APPROVE` actor=1001 | — |
| 3 | **버전 스냅샷** | `LS_LABEL_VERSION` 2행(프레임별) `ver_no=1` `SAVE_REASON_CD=APPROVED` `ACTVTN_YN=Y` `VERSION_HASH`(SHA-256) — 초회 승인 시 생성. 재승인 시 `created=0`(동일 페이로드 → 동일 해시, 중복 적층 없음) | `03:42:16.592` |
| 4 | **event_annotation 자동 확정** | `LS_EVNT_ANNO_REVIEW` rvw_sn=1 `AUTO_GENERATED → APPROVED`, `rvw_id=1001` | `03:42:16.593` (materialize **직전**) |
| 5 | **시계열 메타 검토행 자동 확정** | `LS_DATA_META_REVIEW`(rawSn=146) **15건 전부 APPROVED** | 같은 tx |
| 6 | **materialize(포털향 동결)** | `[Dataset] materialized rawSn=146 hashPrefix=0bef2d8c inserted=true`. `LS_DATASET_VIDEO_META.EVNT_ANNO_CN` 에 event_annotation JSON 실제 동결 확인 | `03:42:16.606` |
| 7 | `ReviewApprovedEvent` | `[DatasetExportBridge] review approved rawSn=146 — triggering dataset export (force regenerate)` | `03:42:16.608` (AFTER_COMMIT) |
| 8 | **export 재생성** | `LS_DATASET_EXPORT` sn=13 `export_ver_no=2` **SUCCEEDED** frame_cnt=6 (orgnl 3 + deid 3) | `03:42:16.615~621` |
| 9 | **관제 통지** | `LS_CONTROL_NOTIFY_FALLBACK` sn=15 `SUCCEEDED/SUCCESS`, mock 실왕복 | `03:42:16.633` |

> **★통지 순서 역전 없음(반증 확인)**: 3회 승인 전부 `export succeeded`(…615/…621/…477) → `TASK_* sent`(…633/…498) 순으로 관측됐다. 초회 승인(rawSn 132)도 export `03:37:30.4xx` → 통지 `03:37:30.509`. `export 실패 시 통지 보류` 는 D-part2(TC-NOTIFY) 소관이라 본 파트에서는 성공 경로만 확인.
> 부기: 재승인 시 mock 이 `TASK_COMPLETED` 를 conflict 로 응답해 `completed conflicted -> resend as updated` 로 `TASK_MODIFIED` 재전송됐다(로그 `actual=TASK_MODIFIED`). D-part2 확인 사항.

---

## D-1 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-REVIEW-001 | 제출 정상(ASSIGNED→PENDING) | PASS | **[실동작]** `POST /v1/reviews/146/submit`(W1) → 200 `dataSttsCd=PENDING`. `LS_TASK_EVENT_LOG` evnt_id=22 `SUBMIT` actor=2001. 근거 `ReviewService.java:394-406` 일치 | 테스트 `ReviewControllerTest:배정된_WORKER의_submit는_PENDING으로_전이` |
| TC-REVIEW-002 | 재제출(REJECTED→PENDING) | PASS | **[실동작]** 146 REJECTED → submit 200 PENDING(ver 8→9). `ReviewStateMachine.java:48` 일치 | `ReviewStateMachineTest:허용_전이_검증` |
| TC-REVIEW-003 | 재검수 재제출(APPROVED→PENDING) | PASS | **[실동작]** 146 APPROVED → submit 200 PENDING. rawSn 동일, `LS_LABEL_VERSION` 건수·최대 ver_no 불변(2건/1) = 버전업 아님. `ReviewStateMachine.java:50,55` 일치 | `ReviewControllerTest:재검수_APPROVED_영상의_배정WORKER_submit는_200_PENDING_전이` |
| TC-REVIEW-004 | 제출 차단 — 배치완료 COMPLETED 점프 방지 | PASS | **[정적]** `ReviewStateMachine.java:43-51` `ALLOWED` 에 `COMPLETED` 키 없음 → `:59-63` INVALID_INPUT. **실동작 도달 불가**이며 그 자체가 방어 근거다 — `BatchTransitionService:152` 가 배치 완료 시 작업상태를 **ASSIGNED 로 복귀**시키고 `COMPLETED` 는 `LS_DATA_RAW` 에만 기록. `LS_RAW_DATA_STATUS` 실측 분포도 APPROVED/ASSIGNED/PENDING/REJECTED 4종뿐 | 전용 단위 케이스 없음(D-ISSUE-04 와 동일 공백) |
| TC-REVIEW-005 | 제출 차단 — IN_REVIEW에서 제출 | PASS | **[실동작]** 146 IN_REVIEW → submit **400** `허용되지 않은 상태 전이입니다 (IN_REVIEW → PENDING).` | `ReviewStateMachineTest` |
| TC-REVIEW-006 | 제출 — 타 WORKER 배정(IDOR) | PASS | **[실동작]** W2(2002) → 146 submit **403** `본인에게 배정되지 않은 영상입니다.` 근거 `ReviewService.java:614-627`(케이스 표기 `614-631` 은 다음 메서드 javadoc 까지 포함 — 경미 드리프트) | `ReviewControllerTest:미배정_WORKER가_submit_시도시_403_IDOR` |
| TC-REVIEW-007 | 제출 — REVIEWER가 submit 호출 | PASS | **[실동작]** R1 submit → **403**. 메시지는 `ReviewController.java:221 @PreAuthorize("hasRole('WORKER')")` 의 일반 문구(`권한이 없습니다.`)이며 서비스층 문구(`:618-620`)는 2중 방어라 미도달 — 1차와 동일 | `ReviewControllerTest:REVIEWER가_cancel_submit_호출시_403` |
| TC-REVIEW-008 | 제출 — 미인증 | PASS | **[실동작]** 토큰 없이 submit → **401 UNAUTHORIZED**. `ReviewService.java:615-617` 일치 | `ReviewControllerTest:미인증_cancel_submit_호출시_401` |
| TC-REVIEW-009 | 검수시작 정상(PENDING→IN_REVIEW) | PASS | **[실동작]** `POST /v1/reviews/146/start`(R1) → 200 `IN_REVIEW`(status 표시 `REVIEWING`). `ReviewService.java:444-452` 일치 | `ReviewControllerTest` |
| TC-REVIEW-010 | 검수시작 — 비REVIEWER | PASS | **[실동작]** W1 start → **403**. `ReviewService.java:652-659` 일치 | `ReviewControllerTest` |
| TC-REVIEW-011 | 승인 정상(IN_REVIEW→APPROVED) | PASS | **[실동작]** 146·132·151 3회 승인. 부수효과 9종 전부 실측(위 "승인 부수효과" 표) — 스냅샷·evntAnno 자동승인·meta 15건 자동승인·materialize·`ReviewApprovedEvent`·export SUCCEEDED·통지 SUCCESS. `ReviewService.java:467-518` 일치 | `ReviewServiceEventPublishTest` 5건 |
| TC-REVIEW-012 | 승인 — PENDING에서 직행 차단 | PASS | **[실동작]** 146 PENDING → approve **400** `(PENDING → APPROVED)` | `ReviewControllerTest:PENDING에서_APPROVED_직접_전이는_불가` |
| TC-REVIEW-013 | 승인 — 이미 APPROVED 재승인 시도 | PASS | **[실동작]** 146 APPROVED → approve **409 CONFLICT**. `ReviewStateMachine.java:54-58` 일치 | `ReviewControllerTest:중복_승인_시도시_409_CONFLICT` |
| TC-REVIEW-014 | 승인 — 비REVIEWER | PASS | **[실동작]** W1 approve → **403**. `ReviewService.java:469` + `:652-659` | `ReviewControllerTest:WORKER가_approve_호출시_403` |
| TC-REVIEW-015 | 승인 시 event_annotation 자동 APPROVED 동결 | PASS | **[실동작]** `PUT /v1/videos/146/event-annotation` 로 `evnt_anno_sn=1`(`AUTO_GENERATED`) 생성 → approve 후 `LS_EVNT_ANNO_REVIEW` `APPROVED/rvw_id=1001/03:42:16.593`. 로그 순서 `auto-approved(…593)` → `materialized(…606)` 로 **materialize 직전 호출** 확인. `LS_DATASET_VIDEO_META.EVNT_ANNO_CN` 에 동결 JSON 실재. `ReviewService.java:502-506` 일치 | `ReviewServiceEventPublishTest:event_annotation_자동승인이_materialize_직전에_호출됨` |
| TC-REVIEW-016 | 승인 시 시계열 메타 검토행 자동 APPROVED | PASS | **[실동작]** 승인 후 `LS_DATA_META_REVIEW`(rawSn=146) **15건 전부 APPROVED**. `ReviewService.java:507-510` 일치 | `ReviewServiceEventPublishTest:시계열메타_검토행_자동확정이_materialize_직전에_호출됨` |
| TC-REVIEW-017 | 승인 — 스냅샷 스킵 발생 시 WARN(승인 성공) | PASS | **[정적]** `ReviewService.java:496-501` — `commit.hasSkips()` 시 `rawSn/skippedFrames/actor` 만 WARN(라벨 본문·PII 미출력, CWE-209/359). 10MB 초과 프레임 보유 데이터가 없어 실동작 미유발(정상). 실측 로그는 `created=n skipped=0` | `ReviewServiceEventPublishTest:M2_스냅샷_스킵_발생시_WARN…` / `M2_스냅샷_스킵_없으면_WARN_미발생` |
| TC-REVIEW-018 | 반려 정상(IN_REVIEW→REJECTED) | PASS | **[실동작]** reject(사유) → 200 REJECTED + `LS_DATA_ISSUE` sn=2 (`REJECTION`/`RESOLVED`/`reported_user_no=1001`) + evnt_id=25 `REJECT`(rsn 포함). `ReviewService.java:570-599` 일치 | `ReviewControllerTest:반려시_LS_DATA_ISSUE_생성_+_상태_REJECTED` |
| TC-REVIEW-019 | 반려 — 사유 누락 | PASS | **[실동작]** `{"reason":""}` → **400** `reason: 반려 사유는 필수입니다.` / `{"reason":"   "}` → 400 / `{"reason":null}` → 400 / 바디 없음 → 400. 1001자 → 400 `최대 1000자`. `RejectRequest.java` `@NotBlank`+`@Size(max=1000)` 일치 | `ReviewControllerTest:반려_사유_누락시_INVALID_INPUT_400` |
| TC-REVIEW-020 | 반려 — 재반려 시 parent 이슈 연결 | PASS | **[실동작]** 2차 반려 → `LS_DATA_ISSUE` sn=3 `up_data_issue_sn=2` 계층 연결. `ReviewService.java:576-584` 일치 | `IssueThreadRepositoryTest` |
| TC-REVIEW-021 | 검수취소 정상(PENDING→ASSIGNED) | PASS | **[실동작]** 146 PENDING → cancel-submit(W1) 200 ASSIGNED + evnt_id=23 `CANCEL_SUBMIT` **`rsn=NULL`(PII 미포함)**. `ReviewService.java:421-439` 일치 | `ReviewControllerTest:REVIEW_PENDING서_본인WORKER_취소_200_ASSIGNED복귀` |
| TC-REVIEW-022 | 검수취소 — IN_REVIEW 차단 | PASS | **[실동작]** **400** `(IN_REVIEW → ASSIGNED)`. `ReviewStateMachine.java:46,60-63` | `ReviewControllerTest:IN_REVIEW서_취소시_400` |
| TC-REVIEW-023 | 검수취소 — APPROVED 차단 | PASS | **[실동작]** **409 CONFLICT** `이미 APPROVED 된 영상은…`. `ReviewStateMachine.java:54-58` | `ReviewControllerTest:APPROVED서_취소시_409_CONFLICT` |
| TC-REVIEW-024 | 검수취소 — 타 WORKER(IDOR) | PASS | **[실동작]** W2 cancel-submit → **403** `본인에게 배정되지 않은 영상입니다.` `ReviewService.java:423`+`614-627` | `ReviewControllerTest:타WORKER_취소시_403_IDOR` |
| TC-REVIEW-025 | 상세조회 — WORKER 본인 배정만 | PASS | **[실동작]** W1(2001, 본인 배정) `GET /v1/reviews/132` → **200**. `ReviewService.java:633-650` 일치 | `ReviewControllerTest:본인_배정_WORKER의_GET_reviews_단건은_200` |
| TC-REVIEW-026 | 상세조회 — WORKER 타인 배정 차단(IDOR) | PASS | **[실동작]** W2 → **403** / PORTAL_USER → **403** / 미인증 → **401** (3채널 확인). `ReviewService.java:640-649` | `ReviewControllerTest:타인_배정_WORKER의_GET_reviews_단건은_403_IDOR` |
| TC-REVIEW-027 | 프레임목록 — 영상 미존재 | PASS | **[실동작]** `GET /v1/reviews/999999/frames` → **404** `영상을 찾을 수 없습니다.` `ReviewService.java:335-339` 일치. 부가: `videoId=abc`·`99999999999999999999` → 400(형식), `-1` → 404 | `ReviewFramesControllerTest:videoId_미존재_시_404` |
| TC-REVIEW-028 | 프레임목록 — N+1 회피(2쿼리) | PASS | **[실동작]** postgres `log_statement='all'` 실측(5초 창) — `GET /v1/reviews/132/frames`(6프레임·30라벨) 처리 시 SQL **3건**: `LS_DATA_RAW`(존재확인)×1 + `LS_DATA_SRC`×1 + `LS_DATA_LBL`(IN)×1. **라벨 쿼리 1회** = TC 기대(프레임1+라벨IN1) 충족. 응답 `frameNo` 0~5 오름차순 확인 | `ReviewFramesControllerTest:프레임_N개_라벨_M개_단일_쿼리_확인_N1_회피` |
| TC-REVIEW-029 | 목록 — REVIEWER 전용+status 필터 | PASS | **[실동작]** `GET /v1/reviews?size=100` → total 10(APPROVED 5·PENDING 3·REJECTED 2). status별: PENDING 3 / IN_REVIEW 0 / APPROVED 5 / REJECTED 2 = **합 10 일치**, 각 결과의 `dataSttsCd` 집합이 요청값 단일. 비REVIEWER 403 / 미인증 401. `ReviewService.java:103-149` · `ReviewQueryRepository.java:109-129` 일치 | `ReviewListWhitelistFilterIT` 4건 · `ReviewListFilterSortTest` |
| TC-REVIEW-030 | 이슈목록 — REVIEWER 전용 | PASS | **[실동작]** `GET /v1/reviews/146/issues` → 200, `reg_dt DESC`, `upDataIssueSn` 계층 포함. WORKER **403**. 미존재 영상은 200 + 빈 배열. `ReviewService.java:378-383` 일치 | `IssueControllerTest` |
| TC-REVIEW-035 | 라벨 0건 승인 차단(409 REVIEW_NO_LABEL) | PASS | **[실동작]** rawSn 151(프레임 0·라벨 0) IN_REVIEW → approve(바디 없음) → **409 `REVIEW_NO_LABEL`** `라벨이 없는 영상입니다…`, 상태 **IN_REVIEW 유지**(전이 전 판정). `ReviewService.java:543-565` 일치 | `ReviewApproveLabelGateIT:라벨_0건_영상_승인시_409_이고_상태가_유지됨` |
| TC-REVIEW-036 | negative sample 승인 허용 | PASS | **[실동작]** 151 approve `{"noLabelConfirmed":true}` → **200 APPROVED** + `LS_TASK_EVENT_LOG` evnt_id=35 `APPROVE` `rsn='라벨 없음 확인 승인(negative sample)'`. `ReviewService.java:477-486,556-564` 일치 | `ReviewApproveLabelGateIT:검수자가_라벨없음을_명시_확인하면…` |
| TC-REVIEW-037 | 라벨 있는데 noLabelConfirmed=true | PASS | **[실동작]** 146(라벨 13) approve `{"noLabelConfirmed":true}` → **400 INVALID_INPUT** `라벨이 있는 영상입니다. 최신 상태를 다시 확인한 뒤 승인하세요.`, 상태 IN_REVIEW 유지. `ReviewService.java:545-553` 일치 | `ReviewApproveLabelGateIT:라벨이_있는_영상에_라벨없음_확인을_보내면_400…` |
| TC-REVIEW-038 | 게이트 순서 — 상태전이 검증이 라벨 게이트보다 먼저 | PASS | **[실동작]** 151(PENDING·라벨 0) approve → **400**(`PENDING → APPROVED`) — 409 아님. 기존 오류 계약 보존 확인. `ReviewService.java:471-478` 일치 | `ReviewApproveLabelGateIT` |
| TC-REVIEW-039 | 승인 바디 선택(하위호환) | PASS | **[실동작]** 바디 미첨부 approve → 200 APPROVED(기존과 동일). 알 수 없는 키 첨부도 정상 파싱(`@JsonIgnoreProperties` 계열). `ReviewController.java:286-294` 일치 | `ReviewApproveLabelGateIT:확인_플래그_null_false_는_미확인으로_취급…` |
| TC-REVIEW-040 | **미등록 정렬 키 = 기본정렬 폴백(lenient 200)** | PASS | **[실동작]** `sort=filePath,desc`·`rawFilePathNm,asc`·`id,desc`·`; DROP TABLE ls_data_raw;--,desc`·`1=1` **5종 전부 200** + 결과 순서가 `UPD_DT DESC` 기본정렬과 동일. backend 로그 `[Sort] unsupported sort key ignored key=…` WARN 5건(`LogSanitizer` 정제 확인). **400 아님 — ★2 확정 정책 준수**. `ReviewController.java:95-103` · `SortAllowlist.resolveLenient(213-239)` | ⚠ **통일 제안 없음**. `ReviewListFilterSortTest:검수목록_미등록_정렬키는_400이_아니라_기본정렬로_폴백된다` + `작업목록_미등록_정렬키는_여전히_400이다` · `ListApiBackwardCompatibilityIT` |
| TC-REVIEW-041 | 정렬 allowlist 매핑 | PASS | **[실동작]** `submittedAt,desc/asc` → `UPD_DT` 기준 정렬 확인 · `videoId,asc/desc` → `RAW_DATA_ID` · `status,asc` → `DATA_STTS_CD`(APPROVED→IN_REVIEW→PENDING→REJECTED 사전순) · `updDt,asc` → submittedAt,asc 와 동일 결과. 임의 프로퍼티 미도달(`SortAllowlist.REVIEW` 4키 → 엔티티 3필드, `ReviewQueryRepository.java:360-388` switch 이중 방어) | `SortAllowlistTest` |
| TC-REVIEW-042 | PK tie-break 강제 | PASS | **[실동작]** `UPD_DT` 가 동일한 3행(20011/20012/20013, `2026-07-30 11:23:34.783487`) 존재 상태에서 `size=1` 10페이지 순회 → `146 132 151 128 136 134 126 20013 20012 20011` = **10건 distinct 10, 중복·누락 0**. tie 구간이 `RAW_DATA_ID DESC` 로 결정적. `ReviewQueryRepository.java:360-388` 일치 | `ReviewListFilterSortTest:동일_제출일_다수_행에서_페이지경계_행이_중복되거나_누락되지_않는다` |
| TC-REVIEW-043 | 화이트리스트 밖 status = 빈 결과 200 | PASS | **[실동작]** `PROCESSING`/`ASSIGNED`(DB 실재 45행)/`FAILED`/`COMPLETED`/`BATCH_QUEUED`/`pending`(소문자)/`"  "`(공백) **전부 200 + totalElements=0**. 400 아님. `ReviewSearchCondition.statusFilter` (정규화 없음) · `ReviewQueryRepository.java:229-250` 일치 | `ReviewListWhitelistFilterIT:화이트리스트_밖_상태_PROCESSING_지정시_빈결과로_누출을_차단한다` |
| TC-REVIEW-044 | 검수 워크플로 화이트리스트 상시 적용 | PASS | **[실동작]** `status` 미지정 목록 = 10건, 전부 `PENDING/IN_REVIEW/APPROVED/REJECTED`. DB 의 `ASSIGNED` 45행은 **어떤 조합에서도 0건 노출**(status 지정·q 검색·정렬 변경 전부). SQL 조각 주입(`status=PENDING' OR '1'='1`)도 200+0건. `ReviewQueryRepository.java:233-234`(buildWhere 첫 줄) · `ReviewRepository.java:33-37` 일치 | `ReviewListWhitelistFilterIT` · `ReviewListFilterSortTest:검수대상_아닌_배치상태_영상은_검색어와_매치돼도_반환되지_않는다` |
| TC-REVIEW-045 | `q` 검색 DB WHERE 단계 적용 | PASS | **[실동작]** `q=강남` → total 8 / content 8 (**일치**), `q=최라벨` → 9/9, `q=정작업` → 1/1, `q=zzz…` → 0/0. LIKE 이스케이프: `%`·`_`·`%%`·`\`·`강%남` **전부 0건**(와일드카드 무력화 성립). `q=' OR 1=1--` → 200/0건. 101자 → **400**, 100자 → 200. `ReviewQueryRepository.java:243-248,335-340` 일치 | `ReviewListFilterSortTest` 4건(총건수·이스케이프·인젝션·과대길이) |
| TC-REVIEW-046 | 작업자명 검색 = 최신 배정 1건 기준 | PASS | **[실동작]** rawSn 146 에 배정 2건(sn=53 user 2001 `03:28:37` / sn=62 user 2002 `03:38:44`) 존재. `q=최라벨`(과거 작업자) → 결과 9건에 **146 미포함**, `q=정작업`(최신) → **146 단건**. 목록 표시 `workerName` 도 `정작업` 으로 동일 tie-break. `ReviewQueryRepository.java:304-325` · `ReviewService.java:221-230` 일치 | `ReviewListFilterSortTest:동일_REG_DT_재배정에서_표시되는_작업자와_검색되는_작업자가_동일하다` |
| TC-REVIEW-047 | 영상명 공백 판정 통일 | **PARTIAL** | **[정적]** `ReviewQueryRepository.blankAsJava(331-333)` → `BlankTextPredicate.isBlankAsJava` 위임 확인, 표시측 `ReviewService.lookupCctvNames(183-185)` 는 `isBlank()` — 동일 판정 원천. **실동작 미검증** — `MNG_RESOURCE_CCTV` 14행 전부 정상 CCTV 명(`CCTV-강남구-001` 등)이라 탭/개행만인 데이터가 없다. 데이터를 만들면 다른 에이전트 검증에 영향이 가므로 생성하지 않았다 | `ReviewListFilterSortTest:공백문자만_있는_CCTV명은_표시된_VMS_ID_로_검색된다` 로 커버됨 |
| TC-REVIEW-048 | KPI 집계 — `GET /v1/reviews/summary` | PASS | **[실동작]** `{"total":10,"pending":3,"inReview":0,"approved":5,"rejected":2}` — **total=4종 합(10)** 이고 목록 `totalElements`(10)와 일치. `status=PENDING/APPROVED/PROCESSING` 전부 **무시**(동일 응답). `sort=filePath,desc` 붙여도 **200**(400 아님). `q=강남` 반영(total 8) / `q=zzz` → 전 필드 0. 비REVIEWER 403. `ReviewController.java:127-139` · `ReviewSummaryResponse.of` 일치 | `ReviewSummaryTest` 10건 |
| TC-REVIEW-049 | KPI 버킷 커버리지 fail-fast | PASS | **[정적]** `ReviewQueryRepository.java:186-215` — `bucketSum != total` 이면 `IllegalStateException`, 메시지에 입력값 미포함(CWE-209). `ReviewSummaryResponse.verifyMappedCoverage` 가 `MAPPED_STATUSES` 밖 상태에 건수가 있으면 fail-fast. **내부 드리프트(화이트리스트 확장 후 DTO 미갱신)라 실동작 유발 불가** — 소스 수정 없이는 재현할 수 없다 | `ReviewSummaryResponseTest` 5건(`매핑되지_않은_상태에_건수가_있으면_조용히_누락하지_않고_실패한다`) |
| TC-REVIEW-050 | size 상한 | **PARTIAL** | **[실동작]** 상한은 정상 — `size=101`·`1000` → **400** `size 한도 초과 (max=100)`, `size=100` → 200. `ReviewController.java:47,92-94` 일치. ⚠ **하한 미검증** — `size=0`·`size=-1`·`page=-1` 이 **500 INTERNAL_ERROR**(`PageRequest.of` 의 `IllegalArgumentException` 미처리). `size=abc`·`page=abc` 는 400 정상 | **D-ISSUE-02** |
| TC-REVIEW-051 | 검수취소 낙관적 잠금 409 | PASS | **[실동작]** PENDING 상태에서 `cancel-submit`(W1) 과 `start`(R1) 동시 발사 ×3라운드 → 매번 **cancel 409 / start 200**, 상태는 IN_REVIEW 1회만 전이. 로그 `[Review] optimistic lock conflict on cancelSubmit videoId=146 actor=2001` ×3. `ReviewService.java:431-436`(flush 로 커밋 전 표면화) 일치 | `ReviewCancelSubmitConcurrencyIT:취소와_검수시작_경합시_하나만_성공` |
| TC-REVIEW-052 | event_annotation `cot` 배열/객체 양형 정규화 | PASS | **[실동작]** `PUT /v1/videos/146/event-annotation` 에 `"cot":["객체 식별","행동 추론","결론"]`(과거 배열형) → **200**, 응답·재조회(GET) 모두 `{"1단계":"객체 식별","2단계":"행동 추론","3단계":"결론"}` 로 흡수(500 아님, 순서 보존). 스칼라 `"cot":"단일문자열"` → `{"1단계":"단일문자열"}`. 신규 저장은 객체형. `event_class` 누락 → 400 | 근거 라인 드리프트(아래) · `EventAnnotationPayloadTest:하위호환_과거_배열cot_동결본_역직렬화시_예외없이_객체로_흡수` |

## D-2 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-REVIEW-031 | 허용 전이 전수 매트릭스 | PASS | **[실동작]** 정본 7전이를 **전부 HTTP 로 실행** — ASSIGNED→PENDING(200) · PENDING→IN_REVIEW(200) · PENDING→ASSIGNED(200) · IN_REVIEW→APPROVED(200) · IN_REVIEW→REJECTED(200) · REJECTED→PENDING(200) · APPROVED→PENDING(200). 매 전이마다 DB `DATA_STTS_CD`·`VER` 증가 확인. `ReviewStateMachine.java:43-51` 일치 | `ReviewStateMachineTest` 7건 |
| TC-REVIEW-032 | 미허용 전이 전수 | PASS | **[실동작]** ASSIGNED→{APPROVED, IN_REVIEW, REJECTED} · PENDING→{APPROVED, REJECTED} · IN_REVIEW→{PENDING, ASSIGNED} · REJECTED→{APPROVED, IN_REVIEW, REJECTED} **10종 전부 400 INVALID_INPUT** + 대상 4영상(128/134/146/147) 상태 **불변** DB 확인. `ReviewStateMachine.java:60-63`(케이스 표기 `59-63` 은 1행 오프셋) | `ReviewStateMachineTest` |
| TC-REVIEW-033 | APPROVED→IN_REVIEW/REJECTED 직행 | PASS | **[실동작]** APPROVED(146)에서 start·reject·approve·cancel-submit **4종 전부 409 CONFLICT** + 동일 메시지. 상태 APPROVED 유지. `ReviewStateMachine.java:54-58` 일치 | `ReviewStateMachineTest:APPROVED에서_PENDING_외_직행은_409_CONFLICT` |
| TC-REVIEW-034 | 알 수 없는 from 상태 | **PARTIAL** | **[정적]** `COMPLETED`/`PROCESSING` → `ALLOWED` 미등록 키 → `INVALID_INPUT(400)` 성립(맵 구조상 확정). **`from=null` 은 여전히 400 이 아니다** — `ReviewStateMachine.java:59 ALLOWED.get(from)` 의 `ALLOWED` 가 `Map.of(...)`(`ImmutableCollections.MapN`)라 `get(null)` 이 `probe()` 에서 **NPE → 500**, `:60` 의 `allowed == null` 분기에 도달하지 못한다. `verify` 진입부 null 가드 **여전히 없음** | **D-ISSUE-04 (1차 D-ISSUE-03 미해소 이월)** |

---

## 근거 드리프트

| TC | 카탈로그 표기 | 실제 | 영향 |
|---|---|---|---|
| TC-REVIEW-052 | `EventAnnotationPayload.java:CotDeserializer(94-140)` | **`120-142`** (`static final class CotDeserializer` = 120행, `deserialize` = 122행, 클래스 종료 = 142행). 94-108 구간은 `MAPPER` 상수·`CaptionCandidate` javadoc | 중 — 라인 점프 시 다른 코드가 열린다 |
| TC-REVIEW-006 · 024 | `ReviewService.java:614-631` | **`614-627`**(`verifyAssignedWorker` 본문). 628-631 은 다음 메서드 javadoc | 경미 |
| TC-REVIEW-028 | `ReviewService.java:335-376` | **`335-372`**(`listFrames` 본문) | 경미 |
| TC-REVIEW-032 · 005 · 012 등 | `ReviewStateMachine.java:59-63` | 실제 거부 분기는 **`60-63`**(`:59` 는 `ALLOWED.get(from)` 조회) | 경미 |
| TC-REVIEW-040 | `SortAllowlist.java:29-60` | 해당 구간은 모드 비교표 javadoc이며 lenient 구현은 **`213-239`**, `REVIEW` allowlist 는 **`115-119`** | 경미(의도적 정책 인용으로 보임) |

### 문서 정합 (드리프트가 아니라 서술 차이 — 기록만)

- 위임 프롬프트/`CLAUDE.md` 는 *"REVIEWER 가 `APPROVED` 처리하면 `LsRawDataStatus.dataSttsCd` 가 **`COMPLETED`** 로 전이"* 라 적고 있으나, **실제로 영속되는 값은 `APPROVED`** 다. `COMPLETED` 는 DTO 표시 계층에서만 매핑된다(응답 실측: `"status":"COMPLETED","dataSttsCd":"APPROVED"`). `ReviewStateMachine.java:33-35` javadoc 이 *"검수 종결 시 영속되는 상태는 APPROVED 다. COMPLETED(STTS_COMPLETED)는 배치 파이프라인 상태이며 검수 종결값으로 쓰이지 않는다"* 로 **의도된 설계임을 명시**하고 있어 결함이 아니다. `CLAUDE.md` 서술을 코드에 맞추면 TC-REVIEW-004 의 오해 소지가 사라진다.
- `LS_TASK_EVENT_LOG` 에 `START_REVIEW` 이벤트가 없다(검수 시작만 이벤트 미기록). 카탈로그 기대값에도 없어 케이스 판정에는 영향 없으나, 감사 추적 관점의 공백으로 기록한다.

---

## 이슈 상세

### [D-ISSUE-01] TC-REVIEW-028(부가) — `GET /v1/reviews/{videoId}/frames` 가 비식별 신고 게이트를 우회해 라벨 본문을 벌크 노출

- **심각도**: **HIGH** (CWE-359 민감정보 노출 · 확정 정책 위반)
- **기대 동작(기대효과)**: `CLAUDE.md` — *"신고 구간 동안 **라벨 조회를 차단**한다(`DE_IDENT_YN='F'` 인 영상의 `GET /v1/frames/{srcSn}/labels` → 412, 역할 무관). **라벨 좌표가 PII 위치 특정 정보이므로 스트리밍만 막는 것으로는 부족**"*. 판정 원천은 `DeidentReportGate`(자기 rawSn 행 하나 — ★1 확정 정책 범위 그대로). 즉 **같은 영상의 라벨 좌표를 내보내는 모든 경로**가 412 여야 한다.
- **현재 동작(이슈 내용)**: `ReviewService.listFrames`(`review/service/ReviewService.java:335-372`)는 `requireReviewer(actor)` + 영상 존재 확인만 하고 **`DeidentReportGate` 를 호출하지 않는다.** `ReviewService` 전체에 `DeidentReportGate` import·주입이 **0건**(`grep -rn "DeidentReportGate" backend/src/main/java/kr/co/cudo/authoring` 결과에 `review/` 패키지 부재).
  ```java
  // ReviewService.java:335-349 (발췌)
  public FrameListResponse listFrames(Long videoId, TokenClaims actor) {
      requireReviewer(actor);
      videoRepository.findById(videoId)
              .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
      List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(videoId);
      ...
      List<LsDataLbl> allLabels = labelRepository.findBySrcSnIn(srcSns);   // ← 게이트 없이 라벨 본문 조회
  ```
  **실동작 대조 (rawSn=133, `LS_DATA_RAW.DE_IDENT_YN='F'`, `LS_DEIDENT_REPORT` OPEN)**:

  | 경로 | 결과 |
  |---|---|
  | `GET /v1/frames/78/labels` | **412** `비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다` |
  | `GET /v1/frames/80/labels` · `/82/labels` | **412** (동일) |
  | `GET /v1/frames/78/image` | **412** |
  | `GET /v1/videos/133/frames/0/image` | **412** |
  | **`GET /v1/reviews/133/frames`** | **200** — 6프레임 · **라벨 28건 전량**(`(78,0) (80,10) (82,4) (84,3) (86,9) (88,2)`) |

  반환 본문에 좌표가 그대로 실린다:
  ```json
  {"id":204,"lblTypeCd":"BBOX","label":"car","labelId":2,
   "points":[[0.0,683.0894050576364],[396.11424862685664,1017.736759187969]],
   "autoLblYn":"N","trackId":null}
  ```
  즉 **개별 조회로는 412 로 막히는 라벨 좌표를 이 엔드포인트 하나로 영상 전체분 한 번에 받아낼 수 있다.** 이미지 URL 은 게이트가 걸린 경로를 가리켜 실제 픽셀은 나가지 않지만(412), 게이트가 막으려던 "PII 위치 특정 정보"는 그대로 나간다.
- **★확정 정책과의 관계(오분류 아님)**: 이 건은 UNCERTAINTIES **★1**(조상/자손 전파 금지 · 파생 경유 열람은 결함 아님)과 **무관**하다. 133 은 파생영상이 아니라 **신고가 접수된 자기 자신**이며, `DeidentReportGate` 의 판정 범위(자기 rawSn 행)에 정확히 들어온다. 확정 정책이 "차단해야 한다"고 규정한 바로 그 대상이 한 엔드포인트에서만 새는 것이다. 학습된 반복 결함 패턴(**게이트를 호출처마다 배선하면 반드시 샌다**)의 재발이다.
- **재현/확인 경로**:
  ```bash
  BASE=http://localhost:18081/api
  # 신고 구간 영상(133)의 개별 라벨 조회 → 412
  curl -s -o /dev/null -w '%{http_code}\n' "$BASE/v1/frames/80/labels" -H "Authorization: Bearer $REVIEWER"
  # 같은 영상의 검수 프레임 목록 → 200 + 라벨 28건
  curl -s "$BASE/v1/reviews/133/frames" -H "Authorization: Bearer $REVIEWER" | head -c 400
  ```
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE raw_sn = 133;   -- 133|F
  SELECT s.src_sn, count(l.lbl_sn) FROM ls_data_src s
    LEFT JOIN ls_data_lbl l ON l.src_sn = s.src_sn
   WHERE s.raw_sn = 133 GROUP BY 1;                                  -- 28건
  ```
- **영향**: CWE-359(민감정보 노출) / CWE-863(불완전 인가). 도달성이 높다 — REVIEWER 는 신고 여부와 무관하게 전 영상을 조회할 수 있고(`ReviewController.java:179 @PreAuthorize("hasRole('REVIEWER')")`), 검수 화면이 이 엔드포인트를 상시 호출한다. 부가로 `GET /v1/reviews/{videoId}`(단건 상세)와 `GET /v1/reviews` 목록은 `labelCount`(개수)만 노출하므로 심각도가 낮지만 같은 미배선 구간이다.
- **수정 방향(제안)**: `ReviewService.listFrames` 진입부(인가 검사 **이후**)에 `deidentReportGate.isUnderDeidentReport(videoId)` 판정을 넣고 참이면 다른 라벨 경로와 동일하게 **412** 를 던진다. 겸해 `getDetail`/`list` 의 `labelCount` 노출 여부를 정책적으로 확정한다. 근본 처방은 "라벨 본문을 내보내는 서비스 메서드"를 열거해 게이트 적용을 회귀 테스트로 고정하는 것이다(클래스패스 스캔 가드 방식). ⚠ **구현하지 않는다.**

---

### [D-ISSUE-02] TC-REVIEW-050 — `size <= 0` / `page < 0` 이 400 이 아닌 **500**

- **심각도**: MEDIUM (API 계약 위반 · CWE-20 입력 검증 누락)
- **기대 동작(기대효과)**: `rules/api-design.md` — 입력값 검증 실패는 **400**. 페이징 파라미터는 사용자 제어 입력이므로 상·하한 모두 검증돼야 하며, 인증된 사용자가 유발 가능한 미처리 500 이 남아서는 안 된다(`security.md` — 예외 처리 시 fail-secure).
- **현재 동작(이슈 내용)**: `ReviewController.java:92-94` 는 **상한만** 검증한다.
  ```java
  if (size > MAX_PAGE_SIZE) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
  }
  Pageable pageable = PageRequest.of(page, size, ...);   // ← size<1 / page<0 이면 IllegalArgumentException
  ```
  `page`/`size` 는 `@RequestParam(defaultValue=…) int` 라 Bean Validation(`@Min`)이 붙어 있지 않고, `PageRequest.of` 가 던지는 `IllegalArgumentException` 은 `GlobalExceptionHandler` 의 `handleUnknown` 으로 떨어져 500 이 된다.

  **실측**

  | 요청 | 응답 |
  |---|---|
  | `size=100` | 200 |
  | `size=101` · `size=1000` | **400** `size 한도 초과 (max=100)` |
  | **`size=0`** | **500** `서버 내부 오류가 발생했습니다.` |
  | **`size=-1`** | **500** |
  | **`page=-1`** | **500** |
  | `page=99999` | 200(빈 결과) |
  | `size=abc` · `page=abc` | 400 `파라미터 형식이 올바르지 않습니다` |

  backend 로그: `java.lang.IllegalArgumentException: Page size must not be less than one` / `Page index must not be less than zero`.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:18081/api/v1/reviews?size=0"   -H "Authorization: Bearer $REVIEWER"  # 500
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:18081/api/v1/reviews?page=-1"  -H "Authorization: Bearer $REVIEWER"  # 500
  ```
- **영향**: 응답 본문에 스택트레이스·내부 경로는 **노출되지 않아**(`GlobalExceptionHandler` 가 일반화) CWE-209 는 성립하지 않는다. 다만 인증된 REVIEWER 누구나 유발 가능한 미처리 500 이며, 오류 모니터링·알럿 노이즈가 되고 FE 는 400/500 을 구분해 처리할 수 없다. `GET /v1/reviews/summary` 는 page/size 를 받지 않아 영향 없음.
- **수정 방향(제안)**: `size < 1 || page < 0` 을 상한 검증과 같은 블록에서 `INVALID_INPUT(400)` 으로 처리하거나, `@Min(0) int page` / `@Min(1) int size` 를 붙여 기존 `handleConstraintViolation` 경로에 태운다(컨트롤러에 `@Validated` 가 이미 있다). 같은 패턴이 다른 목록 API 에도 있는지는 A/D-part2 소관으로 별도 확인 권장. ⚠ **구현하지 않는다.**

---

### [D-ISSUE-03] TC-REVIEW-019(부가) — 지원하지 않는 `Content-Type` 요청이 415 가 아닌 **500**

- **심각도**: MEDIUM (API 계약 위반 · 크로스컷 — `GlobalExceptionHandler` 핸들러 누락)
- **기대 동작(기대효과)**: 지원하지 않는 미디어 타입은 **415 Unsupported Media Type**(또는 최소 400). `GlobalExceptionHandler` 는 이미 `HttpRequestMethodNotSupportedException` 을 `handleMethodNotSupported`(405 `METHOD_NOT_ALLOWED`)로 처리하고 있어, 같은 계열인 미디어 타입만 누락된 것은 일관성 결함이다.
- **현재 동작(이슈 내용)**: `GlobalExceptionHandler` 의 `@ExceptionHandler` 13종(`handleCustom`/`handleValidation`/`handleConstraintViolation`/`handleMessageNotReadable`/`handleMissingParam`/`handleTypeMismatch`/`handleDataIntegrityViolation`/`handleAccessDenied`/`handleAuth`/`handleMaxUploadSizeExceeded`/`handleNoResourceFound`/`handleMethodNotSupported`/`handleUnknown`) 중 **`HttpMediaTypeNotSupportedException` 핸들러가 없다** → `handleUnknown` → 500.
  ```
  2026-07-31 03:46:36.994 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'text/plain;charset=UTF-8' is not supported
  ```
  **실측 범위**

  | 요청 | 응답 |
  |---|---|
  | `POST /v1/reviews/134/reject` + `Content-Type: text/plain` | **500** |
  | `PUT /v1/videos/146/event-annotation` + `text/plain` | **500** |
  | `POST /v1/videos/146/event-annotation` + `text/plain` | 405(매핑 자체 없음 — 정상) |
  | `GET /v1/reviews/132/approve` / `DELETE /v1/reviews/132` / `PUT /…/submit` | **405** `허용되지 않은 요청 메서드입니다.` (정상) |
  | `POST /…/approve` + 깨진 JSON | 400 `요청 본문이 올바르지 않습니다.` (정상) |
  | `POST /…/approve` + `{"noLabelConfirmed":"yes"}` | 400 (정상) |

  즉 **`@RequestBody` 를 받는 모든 엔드포인트**가 대상이라 D-1 국한 결함이 아니다(A 클러스터 공통 인프라). D-1 에서는 `reject`(사유 필수 케이스 TC-REVIEW-019 의 인접 경로)로 실측돼 여기 기록한다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18081/api/v1/reviews/134/reject \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: text/plain' -d 'reason'   # 500
  ```
- **영향**: 응답 본문에 내부 정보 미노출(일반화됨). 데이터 손상·권한 우회 없음. 오류 처리 규약 위반 + 모니터링 노이즈. 인증 사용자면 누구나 유발 가능.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` → `415`(신규 `ErrorCode.UNSUPPORTED_MEDIA_TYPE`) 또는 기존 `INVALID_INPUT(400)` 매핑을 추가한다. 겸해 `HttpMediaTypeNotAcceptableException`(Accept 헤더)도 함께 처리 검토. ⚠ **구현하지 않는다.**

---

### [D-ISSUE-04] TC-REVIEW-034 — `ReviewStateMachine.verify(null, …)` 가 400 이 아닌 NPE(500) *(1차 D-ISSUE-03 미해소 이월)*

- **심각도**: LOW (현재 도달 불가 — 잠재 결함)
- **기대 동작(기대효과)**: 카탈로그 TC-REVIEW-034 는 `from = COMPLETED / PROCESSING / null` 전부 `INVALID_INPUT(400)` 을 기대한다. 상태 머신은 알 수 없는 입력에 fail-closed 로 400 을 던져야 하며 500(내부 오류) 경로를 열어서는 안 된다.
- **현재 동작(이슈 내용)**: `ReviewStateMachine.java:43-51` 의 `ALLOWED` 가 `Map.of(...)`(JDK `ImmutableCollections.MapN`) 이라, `:59 ALLOWED.get(from)` 에서 `from == null` 이면 `MapN.probe(null)` 이 `pk.hashCode()` 를 호출해 **NullPointerException** 이 나고 `:60-63` 의 `allowed == null` 분기에 도달하지 못한다.
  ```java
  public void verify(String from, String to) {
      if (LsRawDataStatus.STTS_APPROVED.equals(from) && !LsRawDataStatus.STTS_PENDING.equals(to)) { ... }
      Set<String> allowed = ALLOWED.get(from);        // ← from=null 이면 여기서 NPE
      if (allowed == null || !allowed.contains(to)) { throw new CustomException(INVALID_INPUT, ...); }
  }
  ```
  진입부 null 가드는 **여전히 없다**. `COMPLETED`/`PROCESSING` 은 맵 미등록 키라 정상적으로 400 을 던진다(이 두 값은 정상).
- **1차 대비 변화**: 없음. 코드 무변경이고, 1차 수정 제안이던 `ReviewStateMachineTest` 의 COMPLETED/PROCESSING/null 3케이스도 **추가되지 않았다**(현재 7 케이스 전부 정상 상태값 조합).
- **재현/확인 경로**: HTTP 로는 재현 불가 — `LS_RAW_DATA_STATUS.DATA_STTS_CD` 는 `nullable=false`(`LsRawDataStatus.java:37`)이고 `transitionTo`/`verify` 호출자 전수(`ReviewService.java:398,425,448,473,574`)에서 null 이 들어갈 경로가 없다. 단위 수준: `new ReviewStateMachine().verify(null, "PENDING")`.
- **영향**: 현재 도달 불가(이론적). 향후 상태 컬럼이 nullable 로 바뀌거나 외부 연동/DTO 에서 상태를 주입하면 500 경로가 열린다.
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null || to == null) throw new CustomException(INVALID_INPUT, "상태 값이 없습니다.");` 가드 추가 + `ReviewStateMachineTest` 에 `COMPLETED`/`PROCESSING`/`null` 3케이스 추가. ⚠ **구현하지 않는다.**

---

### [D-ISSUE-05] 파생영상(증강·해상도)의 검수 조작 차단이 **명시 가드가 아니라 상태행 부재에 의한 404** *(정보 제공)*

- **심각도**: LOW (현재 실동작 결함 없음 — 방어 근거의 취약성)
- **기대 동작(기대효과)**: `CLAUDE.md` — 파생영상(`ORGNL_RAW_SN` non-null)은 내부 생성물이며 라벨 검수 대상이 아니다.
- **현재 동작(이슈 내용)**: `ReviewService` 에는 **파생 여부(`ORGNL_RAW_SN`) 판정이 없다.** 파생 129/130/131 에 검수 조작이 막히는 유일한 이유는 `loadByVideoId`(`ReviewService.java:605-608`)가 `LS_RAW_DATA_STATUS` 행을 찾지 못해 404 를 던지기 때문이다.

  | 요청 | 실측 |
  |---|---|
  | `POST /v1/reviews/{129,130,131}/start` | **404** `검수 대상 영상을 찾을 수 없습니다.` |
  | `POST /v1/reviews/{129,130,131}/approve` | **404** (동일) |
  | `POST /v1/reviews/{129,130,131}/reject` | **404** (동일) |

  DB 확인: `LS_DATA_RAW.ORGNL_RAW_SN=126` 인 3건 모두 `LS_RAW_DATA_STATUS` 행 `(none)` (PIPE-ISSUE-04 와 동일 관측).
  그런데 `LS_RAW_DATA_STATUS` 행은 **배정 시점에 lazy 생성**된다(126 도 배정 때 생성됨). 즉 `POST /v1/assignments` 로 파생영상에 작업자를 배정하면 상태행이 생기고, 그 순간부터 이 404 방어가 사라져 파생영상도 승인·반려가 가능해진다. 실제로 `CLAUDE.md` 는 *"파생영상은 미검수(PENDING) 상태로 시작 → 작업자 배정 → 수정 → 검수"* 라고 파생 검수를 **허용**한다고 적고 있어, 두 서술(검수 차단 vs 검수 대상) 사이의 정책 확정이 필요하다.
- **범위 주의**: `CLAUDE.md` 의 *"해상도 파생은 … accept/reject(검수 승인·반려)는 차단"* 은 **증강 이력의 accept/reject(`LS_DATA_AUG.AUG_PROC_STTS_CD`)** 를 가리키며 그쪽은 E 클러스터(TC-AUG/TC-RESL) 소관이다. 본 항목은 **검수 워크플로(`/v1/reviews/*`)** 축의 관측이다.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18081/api/v1/reviews/129/approve -H "Authorization: Bearer $REVIEWER"  # 404
  ```
  ```sql
  SELECT r.raw_sn, r.orgnl_raw_sn, COALESCE(s.data_stts_cd,'(none)')
    FROM ls_data_raw r LEFT JOIN ls_raw_data_status s ON s.raw_data_id = r.raw_sn
   WHERE r.orgnl_raw_sn IS NOT NULL;   -- 129/130/131 → (none)
  ```
  ⚠ 파생영상에 실제로 배정을 걸면 다른 에이전트(E 클러스터)의 참조 데이터가 바뀌므로 **배정은 수행하지 않았다.** 위 서술은 코드 경로(`AssignmentService` 의 상태행 lazy upsert) 정적 확인에 근거한다.
- **영향**: 현재 데이터 상태에서는 실제 노출 없음. 정책이 "파생은 검수 대상 아님" 쪽이면 방어가 우발적(상태행 유무)이라 배정 한 번으로 무너지고, "파생도 검수 대상" 쪽이면 404 가 오히려 정상 동선을 막는 결함이다. 어느 쪽이든 **의도를 코드로 표현하고 있지 않다.**
- **수정 방향(제안)**: 정책 확정 후 ①검수 대상이 아니라면 `ReviewService` 진입부에 `ORGNL_RAW_SN != null` → 412/409 명시 거부 + 안내 문구, ②검수 대상이라면 파생 확정 시 `LS_RAW_DATA_STATUS` PENDING 행을 선생성(PIPE-ISSUE-04 제안과 동일). ⚠ **구현하지 않는다.**
