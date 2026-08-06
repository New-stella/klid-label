# D 클러스터 (검수/버전관리/관제통지) — 2차 검증 결과

> 197건 · 기준 실동작(HEAD ca3c712b + CONTROL_NOTIFY_ENABLED=true 실왕복) · 2026-07-31


---

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

---

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

---

# D 클러스터 part3 (D-5·D-7) 2차 검증 결과

- 검증 일시: 2026-07-31 03:38~03:50 KST (호스트 UTC 2026-07-30 18:38~18:50)
- 대상: `docs/test-cases/D-review-version-notify.md` **D-5. diff / rollback (TC-DIFF, 26건)** + **D-7. 관제 조회 API (TC-NOTIFY 조회, 8건)** = **34건**
- 취소선(폐기) 행: **0건** (두 절 모두 폐기 행 없음 → 집계 제외분 없음)
- 환경: backend `localhost:18081` (이미지 `bdc64ea2ac26`, HEAD `ca3c712b` 재빌드본) · postgres `:5432` 스키마 `public`
- 소스/설정/테스트 파일 수정 0건. 빌드·테스트 실행 0건. 컨테이너 재기동 0건.

## 검증용 데이터 — ★본 검증이 새로 만든 영상 rawSn=156

기존 참조 영상(126 APPROVED·132·133)을 파괴하지 않기 위해 **전용 영상을 새로 완주시켜** 그 위에서 롤백을 실행했다.

| 단계 | 방법 | 결과 |
|---|---|---|
| 관제 클립 픽스처 | `INSERT mng_clip_master/mng_clip_evnt_lst ('DEV-CLIP-9601', 실파일 경로, JOB_DMND_YN='Y')` | 관제 학습용 설정 재현 (pipeline-drive §5 와 동일 종류의 개입) |
| 적재 | `POST /v1/dev/batch/scan` | **rawSn=156** |
| 비식별(KPST mock) | 적재 이벤트 자동 | `SUCCEEDED`, `DE_IDENT_YN=Y`, `/app/storage/raw/seed/156/deid/sample-cctv-1080p-mask.mp4` |
| 배정·마킹·배치 | `POST /v1/assignments` → `POST /v1/videos/156/markings {AUTO,900}` | 프레임 4 (srcSn **131~134**), AI 라벨 7건(YOLO/SAM2 + AI_INFO) |
| 수동 라벨 | `PUT /v1/frames/132/labels` | BBOX(420) + **SKELETON(421, 17 keypoint 삼중값)** |
| 검수 승인 | submit→start→approve | 버전 스냅샷 **3행**(sn 33=src132 / 34=src133 / 35=src134), export v1 SUCCEEDED, TASK_COMPLETED 발송 |
| 2차 승인 | 133 라벨 수정 후 재승인 | src133 **v2**(sn 36) 생성 → diff/rollback 대상 확보 |

**보호 대상 원상 확인(검증 종료 시점 실측)**: `rawSn=126` = APPROVED / 버전 3행 / 라벨 22건 / export 최대 v2 — **전부 착수 시점과 동일(무변경)**. `rawSn=133` = `DE_IDENT_YN='F'`, `LS_DEIDENT_REPORT` sn=3 **OPEN 유지**. `LS_DATA_LBL_AI_INFO` 전역 고아 **0건**.

**본 검증이 남긴 부수 상태(명시)**: ①rawSn **156**(APPROVED, 버전 5행, export v7까지) 신규 ②`MNG_CLIP_MASTER/EVNT_LST` `DEV-EVT-9601` 1쌍 ③156 비식별 산출물 파일 `touch`(신고 resolve 의 산출물 mtime 게이트 통과용, 내용 무변경) ④라벨마스터 1(person) 에 임시 속성 `attrId=2` 생성 후 **DELETE 로 원복 완료**. 156 의 신고는 접수→**resolve 완료**로 `'F'→'Y'` 원복했다.

---

## 집계

| 절 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| D-5 (TC-DIFF) | 26 | 24 | 0 | 2 | 0 | 0 | 0 |
| D-7 (TC-NOTIFY 조회) | 8 | 8 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **34** | **32** | **0** | **2** | **0** | **0** | **0** |

- 실동작 근거 22건 / 정적 근거 12건. 신규 이슈 **3건**(MEDIUM 2 · LOW 1). 근거 드리프트 **1건**.
- **1차의 핵심 판정("롤백 경로가 사실상 작동하지 않음")은 이번 회차에 실동작으로 뒤집혔다** — D-ISSUE-21/22/23/24/26 전부 해소를 실측 확인.

---

## 1차 이슈 해소 대조

| 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|:--:|
| **D-ISSUE-21** | `SAVE_REASON='ROLLBACK'` 적층 분기가 프로덕션 도달 불가(dead branch), DB ROLLBACK 행 0건, 롤백 행위가 감사추적에서 소실. 40자 가짜 해시 픽스처 테스트 2건이 위양성 | **해소.** 적층 분기 자체가 **제거**되고 "대상 행 재활성"이 정본으로 승격(`activateRollbackTarget` `VersionService.java:535-545`). 실행 로그 `[Version] rolled back (reactivated) srcSn=133 version=1 actor=1001`, 버전 행 수 불변(156: 4행→4행), sn34 `N→Y` / sn36 `Y→N`. **롤백 행위는 `LS_DATA_LBL_HSTRY` sn=59 에 기록** — `{"rollbackToVersionHash":"d7ae300c…","changes":[…]}`, `reg_id=1001`. 위양성 픽스처는 실 sha256 회귀가드로 교체됨(`VersionRollbackHistoryIT:160 rollbackWithRealSha256ReactivatesExistingRow`, `:185 unreachableRollbackStackingBranchRemoved`) | ✅ 해소 |
| **D-ISSUE-22** | 롤백이 `LBL_SN` 재발급 → 동일 좌표 v1↔v3 diff 가 REMOVED×4+ADDED×4 = 8건 오분류 | **해소.** src133 롤백 로그 `restored labels srcSn=133 deleted=3 created=3 **idPreserved=3**`, DB `lbl_sn` = **412/413/417**(스냅샷 원본 id 그대로, 삭제됐던 417 재사용). ★round-trip 실측: 롤백 후 재승인 시 `approved snapshot rawSn=156 frames=4 **created=0**` — 동일 페이로드가 동일 해시로 수렴해 **새 버전이 생기지 않는다**(오분류 8건의 근본 원인 소멸) | ✅ 해소 |
| **D-ISSUE-23** | AI 메타(`LS_DATA_LBL_AI_INFO`)·`TRCK_ID` 미복원 + 고아 잔존, 속성값 보유 프레임은 FK 위반 500 위험 | **대부분 해소.** 롤백 후 `LS_DATA_LBL_AI_INFO`(src133) = 412/YOLO/0.4600, 413/YOLO/0.4072, **417/SAM2/0.8258 복원**, 전역 고아 **0건**. 속성값 보유 프레임 롤백 실행 → **200, FK 위반 500 없음**. `TRCK_ID` 는 스냅샷→`RestoreRow` 로 전달되나(`:876`) **현재 DB 전체에 non-null `TRCK_ID` 가 1건뿐이고 API 로 부여할 수단이 없어 라이브 대조 불가**(정적+IT `VersionRollbackRestoreIT:365`). ⚠ 잔여: 속성값이 **조용히 삭제**된다(신규 D-ISSUE-42) | 🔶 부분 해소 |
| **D-ISSUE-24** | "멱등" 롤백도 라벨을 delete+insert 하고 active 스냅샷과 실 라벨 id 가 어긋남 | **해소.** active 해시 + 라벨 동일 상태에서 롤백 → 로그 `rollback no-op (already active and labels identical)`, `LBL_SN`·`point_cn` md5 불변, `LS_DATA_LBL_HSTRY` 미증가(1건 유지), 버전 행·통지·export 전부 미발생 | ✅ 해소 |
| **D-ISSUE-26** | `DATA_SRC_SN=NULL` 버전 해시로 diff → 미처리 500 | **해소(구조적).** ①`requireFrameScoped`(`:377-382`)가 **인가 검사 이전**에 400 으로 조기 반환 ②`LabelAccessGuard.verifyAndGet` 진입부에도 `srcSn==null` 가드 ③신고 스냅샷 생성 경로(`snapshotDeidentReport`) 자체가 정책 반전으로 제거되어 **DB 에 `DATA_SRC_SN IS NULL` 행 0건**(실측). 전용 테스트 `VersionServiceDiffNullSrcSnTest` 3건 | ✅ 해소 |
| **D-ISSUE-27** | `findByHashOrThrow.get(0)` 다중 해시 비결정 선택 | **미해소(이월, 카탈로그 기대와 일치).** `VersionService.java:1020-1026` 코드 무변경(`matches.get(0)`, 정렬 없음). DB 중복 해시 **0건**이라 실현 미발생. TC-DIFF-026 의 기대결과가 "현행 유지"이므로 케이스 자체는 PASS | ⏸ 이월 |
| (참고) D-ISSUE-25 | 신고 스냅샷 `DATA_SRC_SN=NULL` → list·rollback 404 | **전제 소멸.** 2026-07-27 정책 반전으로 신고가 라벨을 삭제하지 않고 스냅샷도 남기지 않는다(`VersionService.java:303-307` 주석 + `snapshotDeidentReport` 제거). 복원 대상 자체가 없어짐 | ✅ 무효화 |
| (참고) D-ISSUE-28 | `isCommittable` dead code | **미해소(이월).** main 참조 0건(정의부 1곳만), test 참조 3곳. TC-DIFF-020 기대결과가 "dead code 유지"라 케이스는 PASS | ⏸ 이월 |

---

## ★롤백 보존 복원 실측 (rawSn=156 / srcSn=133 · V1(sn34) 으로 롤백)

롤백 직전 상태 = V2(sn36) 승인본 = 412(좌표 수정본)·413·427(신규 수동), 417 은 삭제된 상태.

| 항목 | 롤백 전 | 롤백 후 | 보존/복원 여부 |
|---|---|---|:--:|
| `LS_DATA_LBL.LBL_SN` 집합 | 412, 413, **427** | **412, 413, 417** | ✅ 스냅샷 id 그대로(`idPreserved=3`). 신규 PK 재발급 0건 |
| 412 `POINT_CN` | `[[1300,300],[1450,430]]`(수정본) | `[[1396.0277047507818,318.31498156514033],[1474.282319510693,441.2779139426722]]` | ✅ 스냅샷 원본 좌표로 복원 |
| 413 `POINT_CN` md5 | `1cd8f665…` | `1cd8f665…` | ✅ 불변 |
| 417(삭제됐던 POLYGON) | 없음 | **재생성, 동일 `LBL_SN=417`** | ✅ 삭제됐던 PK 재사용 복원 |
| 427(스냅샷에 없는 신규) | 존재 | 삭제됨 | ✅ full-replace 정상 |
| AI 메타 `LBL_SRC_CD`/`CONF_SCORE`/`AUTO_LBL_YN` | 412=YOLO/0.4600/Y, 413=YOLO/0.4072/Y (417 행 없음) | 412=YOLO/0.4600/Y, 413=YOLO/0.4072/Y, **417=SAM2/0.82580/Y** | ✅ 복원 |
| `LS_DATA_LBL_AI_INFO` 고아 | 0 | **0** | ✅ 고아 미발생 |
| `TRCK_ID` | 전부 NULL | 전부 NULL | ⚠ **라이브 대조 불가**(DB 전역 non-null 1건, API 부여 수단 없음) — 정적/IT 근거만 |
| `LS_DATA_LBL_ATTR_VAL`(별도 실험, src132/lblSn 420) | 1행(`attrId=2, value='d5'`) | **0행** | ❌ **조용히 삭제**(D-ISSUE-42) — 라벨 자체는 동일 PK 로 살아남는데 속성값만 사라짐 |
| `LS_LABEL_VERSION` 행 수(rawSn 156) | 4 | **4** | ✅ 적층 없음. sn34 `N→Y`, sn36 `Y→N` |
| `LS_DATA_LBL_HSTRY`(src133) | 1행 | **2행**(sn59 = 롤백 이벤트, `rollbackToVersionHash`+actor 1001) | ✅ 감사 이력 기록 |
| SKELETON `v` 표현(src132/421, 별도 왕복) | `…,2],[…,2]` 전량 v=2 로 수정 | `[[100.0,200.0,0],[110.0,205.0,1],…,[260.0,280.0,1]]` | ✅ 삼중값 무손실 + **정수 표현 유지**(payload 의 `2.0` 으로 변질 안 됨) |
| 멱등 재롤백(동일 대상 2회차) | — | `rollback no-op` 로그, `LBL_SN`/이력/통지 전부 불변 | ✅ 진짜 no-op |

---

## D-5 결과표 (TC-DIFF)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-DIFF-001 | diff — ADDED/REMOVED/MODIFIED 분류 | PASS | [실동작] `GET /v1/versions/{V2}/diff?compareWith={V1}` 200 → `MODIFIED objectId=412` + `REMOVED 417` + `ADDED 427` 3건 정확 분류 · [정적] `VersionService.java:334-366`, `:1199-1234` | `frameId`=frameNo(2) 로 채워짐 |
| TC-DIFF-002 | diff — SKELETON v 변경 감지 | **PARTIAL** | [실동작] v 만 바꾼 두 APPROVED 버전 diff → **MODIFIED 감지 성공**(`readPoints` 삼중값 비교, `:1180-1197`). 그러나 응답 `before`/`after` 가 **완전히 동일**하고 `type:"POLYGON"` 으로 나감 | **D-ISSUE-41** — 감지는 되나 무엇이 바뀌었는지 표시 불가 |
| TC-DIFF-003 | diff — 다른 프레임(srcSn) | PASS | [실동작] src132 버전 vs src134 버전 → 200 `data:[]` · [정적] `:359-361` | |
| TC-DIFF-004 | diff — 해시 형식 위반 | PASS | [실동작] 비-hex `zzzz`→400 / 65자→400 / rollback 경로도 400 · [정적] `validateHash :1246-1258` | `compareWith` 누락도 400(`INVALID_INPUT`) |
| TC-DIFF-005 | diff — 존재하지 않는 해시 | PASS | [실동작] 64자 `aaa…` → **404** `"to 버전을 찾을 수 없습니다."`(1차 500 아님) · [정적] `:1020-1026` | |
| TC-DIFF-006 | diff — 접근권한(IDOR) | PASS | [실동작] 미배정 WORKER(2002) **403** / 배정 WORKER(2001) 200 / PORTAL **403** / 무토큰 **401** · [정적] `:348-349` | 양 버전 각각 `verifyAndGet` |
| TC-DIFF-007 | diff — 손상 JSON → 빈 리스트 | PASS | [정적] `computeLabelDiffs :1124-1137` 이 `parseLabelsById` 의 `IllegalStateException` 을 잡아 `List.of()` 반환 + WARN(예외 종류만) | 라이브 재현 불가(손상 payload 는 DB 직접 UPDATE 로만 생성 가능 — 파괴적이라 미실행) |
| TC-DIFF-008 | **rollback 정상 — 재활성 + 본문 복원** | PASS | [실동작] 위 "★롤백 보존 복원 실측" 표 전체(LBL_SN 3/3 보존 · AI 메타 복원 · 적층 0 · HSTRY 롤백 이벤트) · [정적] `:410-522, 535-545` | 카탈로그 기대 ①②③ 전부 충족 |
| TC-DIFF-009 | rollback — 빈 스냅샷으로 복원 | PASS | [정적] `parseSnapshotLabels :579-582`(blank→`List.of()`) → `replaceFrameLabels :679-695` 삭제만 수행 → `restore :865-868` 조기 반환 | 라이브 재현 불가 — `commitApproved` 가 라벨 0건 프레임을 스킵해 **빈 스냅샷이 생성되지 않는다**. 다만 "스냅샷에 없는 라벨(427) 삭제"는 실동작 확인됨 |
| TC-DIFF-010 | rollback — 손상 스냅샷 전체 롤백 | PASS | [정적] `:441-442` — `parseSnapshotLabels` 가 **라벨 교체보다 먼저** 실행되어 400 시 부분 적용 불가. SKELETON `points` 누락도 `canonicalKeypointJson :818-831` 이 400 fail-closed · [테스트] `VersionServiceTest:501`, `VersionServiceRollbackSkeletonTest:213` | 라이브 재현 불가(동일 사유) |
| TC-DIFF-011 | rollback — 작업락 영상 차단 | PASS | [실동작] 156 에 비식별 신고 접수(작업락 획득) 후 rollback → **409** `"작업이 잠긴 영상은 롤백할 수 없습니다."` · [정적] `:427-430` | |
| TC-DIFF-012 | rollback — APPROVED TASK_MODIFIED 발행 | PASS | [실동작] APPROVED 상태 롤백 → 60s 디바운스 후 `[ControlNotifyDebounce] flush rawSn=156 **regen=true** frames=133=[LABEL_UPDATED],132=[LABEL_UPDATED]` → `export succeeded rawSn=156 version=5` → `[ControlNotify] TASK_MODIFIED sent rawSn=156 frames=2 reExport=true` · [정적] `:516-520` | mock-server 202 수신, `LS_CONTROL_NOTIFY_FALLBACK` SUCCEEDED |
| TC-DIFF-013 | rollback — 미APPROVED 통지 미발행 | PASS | [실동작] 156 을 submit 으로 PENDING 전이 후 라벨 수정→롤백 → 이후 3분간 flush 로그 0건, `LS_DATASET_EXPORT` 5행 유지, 통지행 5건 유지 · [정적] `:516` | |
| TC-DIFF-014 | **rollback 멱등 — 진짜 no-op** | PASS | [실동작] active 해시 + 라벨 동일 상태 롤백 → `rollback no-op (already active and labels identical)`, `LBL_SN`·md5 불변, HSTRY 미증가, 통지/export 미발생 · [정적] `:481-498` | SKELETON 프레임에서도 동일 확인 |
| TC-DIFF-015 | rollback — 해시 동일 행 재활성 | PASS | [실동작] 롤백 후 `LS_LABEL_VERSION`(rawSn 156) 행 수 4 유지, sn34 `actvtn_yn N→Y` / sn36 `Y→N` · [정적] `:535-545` | 신규 행 0 |
| TC-DIFF-016 | rollback — 대상 버전 미존재 | PASS | [실동작] 미존재 해시 **404** / **타 프레임 해시로 롤백도 404**(`findByDataSrcSnAndVersionHash` 스코프) · [정적] `:420-421` | 미존재 srcSn → 404, srcSn 누락 → 400 |
| TC-DIFF-017 | rollback — actor null / IDOR | PASS | [실동작] 무토큰 **401** / 미배정 WORKER **403** / PORTAL **403** · [정적] `:412-418` | |
| TC-DIFF-018 | rollback — Race 잠금 순서 | PASS | [정적] `findActiveForUpdate :448-449`(VERSION 비관적 락) → `lockAndReadLabelVersion :472`(SRC) → `replaceFrameLabels :503`(LBL). 락 순서 VERSION→SRC→LBL 이 DAG · [테스트] `VersionServiceRollbackLockOrderTest:141` | 동시 롤백 부하 실측은 미수행(단일 세션 검증) |
| TC-DIFF-019 | rollback — SKELETON 삼중값 무손실 복원 | PASS | [실동작] v 를 전량 2 로 바꾼 뒤 롤백 → `POINT_CN` 이 `[[100.0,200.0,0],…,[260.0,280.0,1]]` 로 **v 순열 그대로 + 정수 표현** 복원 · [정적] `canonicalKeypointJson :800-831` | payload 의 `2.0` 로 변질되지 않음 |
| TC-DIFF-020 | isCommittable — PORTAL 채널 배제 | PASS | [정적] `:1260-1262` 로직 정상. `grep isCommittable backend/src/main` = **정의부 1곳뿐(호출자 0건)**, test 3곳 → **D-ISSUE-28 미해소 유지** = 카탈로그 기대와 일치 | 실제 PORTAL 차단은 `@PreAuthorize` 가 담당(실측 403) |
| TC-DIFF-021 | **신고 구간 rollback 412** | **PARTIAL** | [실동작] 신고 구간 rollback → **409(CONFLICT)**. 신고가 작업락을 함께 잡으므로 `:427-430` 락 검사가 `:432-437` 게이트보다 **먼저** 걸린다 · [정적] 412 게이트 자체는 `:437` 에 존재 | **D-ISSUE-43** — 차단은 되나 코드가 412 가 아님. "작업락 없는 배치 실패 경로"는 재현 수단이 없음(비식별은 파이프라인 선두라 프레임 보유 상태에서 `'F'` 가 되는 경로 부재) |
| TC-DIFF-022 | **신고 구간 diff 412** | PASS | [실동작] 156 신고 중 diff → **412** `"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."`, resolve 직후 동일 요청 **200** · [정적] `:351-357`(인가 이후 평가, 동일 rawSn 이면 1회 조회) | |
| TC-DIFF-023 | `DATA_SRC_SN` NULL 버전 diff | PASS | [정적] `requireFrameScoped :377-382` 가 **인가 이전** 400 조기 반환, `LabelAccessGuard.verifyAndGet` null 가드 이중 · [DB] `select count(*) … data_src_sn is null` = **0** · [테스트] `VersionServiceDiffNullSrcSnTest` 3건 | 신규 적재 경로가 제거돼 라이브 재현 불가 |
| TC-DIFF-024 | 잉여 ACTIVE 자기치유 | PASS | [정적] `deactivateOthers` 가 교체 경로(`:540`)와 **멱등 조기 반환 경로(`:493`)** 양쪽에서 호출 · [테스트] `VersionServiceRollbackIdempotencyTest:272` | active 2건 상태는 DB 직접 UPDATE 없이 만들 수 없어 라이브 미재현 |
| TC-DIFF-025 | 프레임 락을 멱등 판정 **이전** 취득 | PASS | [정적] `:472` `lockAndReadLabelVersion`(스칼라 FOR UPDATE, bump 아님) 이 `:486` 멱등 루프보다 앞 · [테스트] `VersionServiceRollbackIdempotencyTest:252` | no-op 경로에서 `LBL_VER` 미증가도 실측 일치(멱등 롤백 후 라벨셋 버전 불변) |
| TC-DIFF-026 | 다중 매칭 해시 비결정 선택 | PASS | [정적] `:1020-1026` `matches.get(0)` 정렬 없음 — **현행 유지**(카탈로그 기대와 동일) · [DB] 중복 해시 **0건** | D-ISSUE-27 이월 유지 |

---

## D-7 결과표 (TC-NOTIFY, 조회)

`authoring.control-notify.enabled=true` 런타임 실효값 확인(`GET /v1/tasks/**` 200 응답) 하에 실측.

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-NOTIFY-026 | 요약 조회 — REVIEWER/WORKER | PASS | [실동작] REVIEWER 200 `{"rawSn":156,"status":"COMPLETED","totalFrames":4,"labeledFrames":3,"totalLabels":9,"totalMeta":21,"reviewerName":"김검수","lastModifiedAt":…}` / 배정 WORKER 200 · [정적] `TaskQueryController.java:66-73`, `TaskQueryService.java:74-` | `reviewerName` 이 DB 실측(승인 이벤트 → 사용자 마스터) — 하드코딩 아님 |
| TC-NOTIFY-027 | 라벨 조회 — 파일경로 미포함(Privacy) | PASS | [실동작] `GET /v1/tasks/156/labels` 응답 전문에 `filePath`/`deIdntf`/`/app/storage` 문자열 **0건**(grep). 항목은 `{lblSn,lblTypeCd,label,points}` 뿐 · [정적] `TaskQueryService.java:234-`(`toLabelsResponse`) | 근거 드리프트 1건(아래) |
| TC-NOTIFY-028 | **라벨 조회 — 페이징 + frameIds 상한** | PASS | [실동작] 기본 `size=20` / `size=10000` → **100 클램프** / `frameIds` 101개 → **400** `"frameIds 는 최대 100개까지…"` / 100개 → 200 / 유효 필터(133) → `totalElements=1`(필터 반영) · [정적] Controller `:87-103`, Service `:54-57,107-110,202-208` | D-ISSUE-45 해소 확인 |
| TC-NOTIFY-029 | 메타 조회 — 페이징 | PASS | [실동작] 기본 20 / `size=10000` → `size=100`, `totalElements=21` · [정적] Controller `:114-123`, Service `:147-150,192-200` | `metaSn` 오름차순 고정 |
| TC-NOTIFY-030 | 조회 — 영상 미존재 | PASS | [실동작] rawSn 999999 → summary/labels/meta **전부 404** `"영상을 찾을 수 없습니다."` · [정적] `findRawOrThrow :225-` | |
| TC-NOTIFY-031 | 조회 — 미인증/권한없음 | PASS | [실동작] 무토큰 **401** / PORTAL **403** `"권한이 없습니다."` · [정적] `TaskQueryController.java:46,67,86,115` | "토글 off → 404" 분기는 현 환경이 `enabled=true` 라 미검증(부기) |
| TC-NOTIFY-052 | **rawSn 순회 IDOR 차단** (★UNCERTAINTIES #4 반전) | PASS | [실동작] 배정 이력 없는 WORKER(2002) → `/summary` **403** · `/labels` **403** · `/meta` **403**(세 경로 전부). 배정 WORKER(2001)·REVIEWER 는 200. **미존재 rawSn + 미배정 WORKER 도 403**(404 존재 오라클 없음) · [정적] Controller `:71,96,121` `verifyRawAccess` | 1차 "의도된 광범위 허용" 지침 폐기가 코드·실동작 양쪽에서 반영됨 |
| TC-NOTIFY-053 | 관제 라벨 조회 신고 게이트 412 | PASS | [실동작] 156 신고 중 `/labels` **412**, 같은 시점 `/summary`·`/meta` **200**(좌표 미포함이라 대상 아님). 미배정 WORKER 는 같은 시점에도 **403**(→ **인가가 게이트보다 앞**, 상태 오라클 없음). resolve 후 `/labels` **200** 자동 해제 · [정적] Controller `:96-101` | 역할 무관(REVIEWER 도 412) |

---

## 근거 드리프트

| ID | 카탈로그 근거 | 실제 위치 | 성격 |
|----|---|---|---|
| TC-NOTIFY-027 | `TaskQueryService.java:107-143` | 파일경로 미노출이 실제로 결정되는 곳은 `toLabelsResponse`(**`:234-`**)와 `TaskLabelsResponse` DTO 필드 정의. `:107-143` 은 페이징·N+1 회피 로직 구간 | 경미(같은 파일, 인접 관심사) |

그 밖의 D-5 26건·D-7 7건 근거 `file:line` 은 **전부 현행 코드와 일치**(`VersionService.java` 1263줄 기준으로 `:334-366`·`:341-346`·`:348-349`·`:351-357`·`:359-361`·`:377-382`·`:410-522`·`:412-418`·`:420-421`·`:427-430`·`:432-437`·`:441-442`·`:444-449`·`:451-473`·`:481-498`·`:516-520`·`:535-545`·`:1020-1026`·`:1246-1258`·`:1260-1262` 확인).

---

## 이슈 상세

### [D-ISSUE-41] TC-DIFF-002 — SKELETON diff 의 `before`/`after` 가 가시성 `v` 를 버려 "무엇이 바뀌었는지" 표시할 수 없고, shape 타입도 `POLYGON` 으로 오표기된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 키포인트 `v`(가시성)만 바뀐 두 APPROVED 버전을 diff 하면 `MODIFIED` 로 분류되고, 응답의 `before`/`after` 가 **서로 다른 값**을 담아 검수자가 화면에서 변경 지점을 확인할 수 있어야 한다(v-blindness 수정의 취지). shape 타입도 실제 라벨 타입(SKELETON)을 반영해야 FE 가 올바른 렌더러를 고른다.
- **현재 동작(이슈 내용)**: 분류는 정상이나 **응답 페이로드가 v 를 버린다.**
  - `VersionService.readPoints`(`VersionService.java:1180-1197`)는 SKELETON 일 때 삼중값 `[x,y,v]` 를 읽어 **비교에는 반영**한다(그래서 MODIFIED 로 잡힘).
  - 그러나 응답 변환은 `LabelDiffDto.ShapeDto.fromPoints`(`LabelDiffDto.java:52-79`)로 가는데, 이 메서드는
    ```java
    List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
    for (List<Double> pt : points) {
        if (pt.size() >= 2) { flat.add(pt.get(0)); flat.add(pt.get(1)); }   // ← 3번째 원소(v) 폐기
    }
    return new ShapeDto("POLYGON", null, null, null, null, flat);            // ← 타입도 POLYGON 고정
    ```
  - 실측(rawSn 156 / srcSn 132 / lblSn 421, v 를 `[0,1,2,…]` → 전량 `2` 로 변경 후 재승인):
    ```
    {"type":"MODIFIED","frameId":1,"objectId":"421",
     "before":{"type":"POLYGON","points":[100.0,200.0,110.0,205.0, … ,260.0,280.0]},
     "after" :{"type":"POLYGON","points":[100.0,200.0,110.0,205.0, … ,260.0,280.0]}}
    ```
    → `before` 와 `after` 가 **바이트 단위로 동일**하다. 소비자 입장에서는 "MODIFIED 인데 아무것도 안 바뀐" 항목이 된다.
- **재현/확인 경로**:
  ```bash
  # 1) SKELETON 라벨 보유 프레임을 승인해 v1 스냅샷 생성
  # 2) v 만 바꿔 저장 → 재승인해 v2 스냅샷 생성
  curl -s "http://localhost:18081/api/v1/versions/$V2/diff?compareWith=$V1" -H "Authorization: Bearer $REV"
  # → type=MODIFIED 이나 before.points == after.points, type="POLYGON"
  ```
- **영향**: 데이터 손상은 없다(스냅샷·롤백은 v 를 무손실 보존). 다만 ①검수자가 diff 화면에서 키포인트 가시성 변경을 **식별할 수 없고** ②`type:"POLYGON"` 때문에 FE 가 SKELETON 전용 렌더링(관절 연결·가시성 색상)을 선택할 근거를 잃는다. SFR-08 "버전별 변경 내용 비교" 요건의 키포인트 라벨에 대한 실질 미충족.
- **수정 방향(제안)**: `ShapeDto` 에 SKELETON 분기를 추가해 삼중값을 보존(`type:"SKELETON"` + 3배수 평탄 배열 또는 `[[x,y,v],…]` 중첩)하고, FE `LabelDiff` 타입에 대응 렌더러를 추가한다. `readPoints` 는 이미 v 를 읽고 있으므로 변환부만 손대면 된다. ⚠ **구현하지 않는다.**

### [D-ISSUE-42] TC-DIFF-008 — 롤백이 **복원되는 `LBL_SN` 의 라벨 속성값(`LS_DATA_LBL_ATTR_VAL`)까지 무조건 삭제**한다 (AI 메타 처리와 비대칭)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 롤백은 "해당 스냅샷 시점의 라벨 상태로 되돌리는" 연산이다. `LBL_SN` 이 보존 복원되는(D-ISSUE-22 해소) 라벨은 **같은 라벨이 계속 존재하는 것**이므로, 스냅샷이 담지 않는 부수 데이터(속성값)는 **AI 메타와 동일하게 보존**되어야 한다. 최소한 소실 사실이 사용자에게 드러나야 한다.
- **현재 동작(이슈 내용)**: `VersionService.replaceFrameLabels`(`VersionService.java:679-695`)의 삭제 블록이 두 자식 테이블을 **다르게** 취급한다.
  ```java
  List<Long> delSns = existing.stream().map(LsDataLbl::getLblSn).toList();
  attrValRepository.deleteByLblSnIn(delSns);                                  // ← 전량 삭제 (restoredIds 미고려)
  List<Long> aiDropSns = delSns.stream().filter(id -> !restoredIds.contains(id)).toList();
  if (!aiDropSns.isEmpty()) { aiInfoRepository.deleteByDataLblSnIn(aiDropSns); }  // ← 복원 대상은 보존
  ```
  AI 메타는 `restoredIds`(= 다시 살아날 `LBL_SN`)를 제외하고 지우는데, 속성값은 제외 없이 전부 지운다. 자바독(`:655-658`)도 "속성값은 스냅샷 페이로드에 없으므로 복원되지 않고, FK 위반(500) 방지를 위해 삭제된다"고 **의도된 동작**으로 적고 있으나, 그 근거였던 FK 위반 위험은 **PK 보존 복원이 도입된 지금은 해당 라벨에 대해 성립하지 않는다**(행이 같은 PK 로 되살아난다).
- **재현/확인 경로**:
  ```bash
  # 라벨마스터 속성 생성 → 라벨(lblSn=420)에 속성값 부여
  curl -X POST .../v1/manage/labels/1/attrs   -d '{"name":"x","inputType":"TEXT"}'
  curl -X PUT  .../v1/labels/420/attrs        -d '{"values":[{"attrId":2,"value":"d5"}]}'
  # 라벨 좌표를 바꿔 롤백이 교체 경로를 타게 한 뒤 롤백
  curl -X POST .../v1/versions/$HASH/rollback -d '{"srcSn":132}'      # → 200 (FK 위반 500 없음 ✅)
  ```
  ```sql
  select count(*) from ls_data_lbl_attr_val;   -- 롤백 전 1 → 롤백 후 0
  select lbl_sn from ls_data_lbl where src_sn=132;  -- 420, 421 (라벨 자체는 동일 PK 로 생존)
  ```
- **영향**: **조용한 데이터 손실.** 라벨은 그대로 남아 있는데 그 라벨에 붙은 CVAT-Like 속성값(예: 차량 유형·가림 여부 등 작업자가 수기로 채운 값)만 사라지며, 응답·로그 어디에도 경고가 없다. 속성값 사용이 본격화되면 롤백 1회로 프레임 전체 속성 작업이 유실된다. 현재 운영 DB 의 속성값 보유 행이 적어 발현이 드물 뿐이다(본 검증에서 직접 만들어 재현). 가용성 이슈(FK 위반 500)는 없음 — 그 부분은 정상 해소 확인.
- **수정 방향(제안)**: `attrValRepository.deleteByLblSnIn(...)` 에도 `restoredIds` 제외 필터를 적용해 AI 메타와 규약을 맞추거나(권장), 그럴 수 없다면 삭제 건수를 WARN 감사 로그로 남기고 API 응답에 소실 건수를 포함한다. ⚠ **구현하지 않는다.**

### [D-ISSUE-43] TC-DIFF-021 — 신고 구간 rollback 의 실제 응답은 412 가 아니라 **409**(작업락이 신고 게이트보다 먼저 평가됨) — 케이스 기대값 정정 필요
- **심각도**: LOW (동작 결함 아님 — 차단 자체는 성립)
- **기대 동작(기대효과)**: 카탈로그 TC-DIFF-021 은 `DE_IDNTF_YN='F'`(신고 **또는** 비식별 실패) 영상의 rollback 을 **412(PRECONDITION_FAILED)** 로 규정한다.
- **현재 동작(이슈 내용)**: `VersionService.rollback` 은 두 가드를 **작업락 → 신고 게이트** 순으로 평가한다.
  ```java
  if (workLockService.isRawLocked(raw.getRawSn())) {                 // :427-430
      throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상은 롤백할 수 없습니다.");
  }
  accessGuard.requireNotUnderDeidentReport(raw.getRawSn());          // :432-437  ← 412
  ```
  비식별 누락 **신고**는 `'F'` 세팅과 **작업락 획득을 함께** 수행하므로, 신고 경로에서는 항상 `:427` 이 먼저 걸려 **409** 가 나간다. 실측:
  ```
  신고 접수(POST /v1/labels/133/deident-report) → 201, ls_data_raw(156).de_ident_yn='F'
  POST /v1/versions/{v1}/rollback {"srcSn":133}  → 409 {"errorCode":"CONFLICT","message":"작업이 잠긴 영상은 롤백할 수 없습니다."}
  같은 시점 GET /v1/versions/{v2}/diff           → 412 (게이트 정상)
  ```
  `:437` 의 412 가 실제로 노출되는 조건은 "락 없이 `'F'` 인 배치 실패 경로"인데, 비식별이 파이프라인 **선두**라 프레임·버전을 보유한 상태에서 `'F'` 가 되는 배치 경로가 현재 없어 **라이브 재현 수단이 없다**(rawSn 127/128 은 `'F'` 지만 프레임 0건).
- **재현/확인 경로**: 위 3줄 그대로.
- **영향**: 보안·데이터 영향 없음(어느 쪽이든 차단). 다만 ①테스트 카탈로그의 기대값이 실동작과 달라 후속 회차에서 반복 오판정될 소지가 있고 ②클라이언트가 "재비식별 대기" 와 "다른 작업이 잠금 중"을 응답 코드로 구분하지 못한다.
- **수정 방향(제안)**: (a) 카탈로그 TC-DIFF-021 기대값을 "신고 경로 = 409(작업락) / 락 없는 `'F'` = 412"로 정정하거나, (b) 코드에서 신고 게이트를 작업락 검사보다 **앞으로** 옮겨 `'F'` 축을 일관되게 412 로 노출한다(diff·라벨 조회와 코드 일치). ⚠ **구현하지 않는다.**

---

## 부기 — 판정에 영향을 준 환경 사실

1. **실행 이미지는 HEAD 정합**이다(`bdc64ea2ac26`, HEAD `ca3c712b` 재빌드본 — `backend-rebuild.md`). `stack-bringup.md` §2 의 "11개 커밋 뒤처짐" 제약은 본 검증에는 적용되지 않는다.
2. **외부 연동 self-fill 없음**: 본 구간이 경유한 외부 연동은 KPST 비식별(mock `POST /project`→`retrieve_progress`, 산출물 `-mask.mp4` 경로를 DB 가 읽어 기록)과 관제 통지(mock `POST /api/data-set/v2/jobs/156/notify-*` → 202, `LS_CONTROL_NOTIFY_FALLBACK` SUCCEEDED)이며 둘 다 **실왕복 관측**. 값 자체 생성 사례 없음.
3. **테스트 커버(실행 안 함, `test-baseline.md` 대조)**: backend 4,367 tests / 실패 0. D-5·D-7 관련 자산 = `VersionServiceTest`(31), `VersionControllerTest`(11), `VersionRollbackRestoreIT`(9), `VersionRollbackHistoryIT`(6), `VersionServiceRollbackIdempotencyTest`(7), `VersionServiceRollbackLockOrderTest`(4), `VersionServiceRollbackSkeletonTest`(3), `VersionServiceDiffNullSrcSnTest`(3), `VersionServiceKeypointSnapshotTest`(1), `TaskQueryControllerTest`(13), `TaskQueryServiceTest`(23). 1차에서 위양성으로 지목된 40자 가짜 해시 픽스처 2건은 실 sha256 기반 회귀가드로 교체됨을 소스에서 확인했다(테스트 GREEN 을 근거로 삼지 않고 실동작으로 재검증한 결과가 위 표다).
4. **다른 에이전트 동시 작업**: 검증 중 rawSn 145~155 및 `LS_LABEL_VERSION` sn 15~32·37~41 이 타 세션에 의해 생성됨을 관측했다. 본 결과의 모든 실측은 **rawSn 156 및 그 프레임(131~134)** 에 한정했다.

---

# D 클러스터 part4 (D-6·D-8) 2차 검증 결과

> 대상: `docs/test-cases/D-review-version-notify.md` 의 `## D-6. 관제 통지 (TC-NOTIFY)` 45건 + `## D-8. 데이터마트 View (TC-MARTVIEW)` 23건 = **68건**
> 환경: backend `localhost:18081`(HEAD `ca3c712b`) · mock `:9400` · postgres `:5432`(스키마 `public`) · `CONTROL_NOTIFY_ENABLED=true` / `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` (**1차의 `false` 와 달라 D-6 전 구간 실동작 가능**)
> 검증 일시: 2026-07-31 03:55 ~ 04:20 KST (mock 로그는 UTC = KST-9h)
> ⚠ 폐기(`~~취소선~~`) 행 없음 — D-6·D-8 두 섹션에 취소선 케이스는 존재하지 않는다(집계 68 = 표 행수 68).

## 검증용으로 신규 투입한 데이터 (다른 클러스터와 충돌 없음)

| 대상 | 내용 |
|---|---|
| `MNG_EX_LOCAL_GOV` | `41135`(경기도 성남시 분당구, `USE_YN='Y'`) · `41136`(폐지시 폐지구, `USE_YN='N'`) · `41137`(가×60 / 나×60 = 121자, `Y`) 3행 신규 |
| `MNG_CLIP_MASTER`/`MNG_CLIP_EVNT_LST` | `DEV-CLIP-9801`(FALLDOWN·41135·45s) · `9802`(LOITERING·41136·77s) · `9803`(FIRE·41137·99s) |
| 신규 영상 | **rawSn 168 / 169 / 170** (적재→비식별→마킹→배치→승인 완주). 170 의 해상도 파생 **179(720P) / 180(480P)** |
| 폴백 큐 주입행 | `VERIFY-D6-REBUILD-1`(빈 payload=재조립 유도) · `VERIFY-D6-BADJOB-1`(`job_id="../../etc/passwd"`) — 둘 다 종결(SUCCEEDED / DEAD_LETTER) |
| 목서버 상태 | `POST /api/data-set/v2/jobs/_reset` 1회 실행(04:06:40 KST) — 404 자기치유 실측용. 등록부는 이후 통지로 재구성됨 |

> 🚨 rawSn **126·133 은 손대지 않았다**. backend 재기동 없음. 소스/설정 무수정. 빌드·테스트 미실행.

---

## 집계

| 판정 | D-6 | D-8 | 합계 |
|---|---:|---:|---:|
| PASS | 45 | 21 | **66** |
| PARTIAL | 0 | 1 | **1** |
| 확인필요 | 0 | 1 | **1** |
| FAIL / BLOCKED / N/A | 0 | 0 | **0** |
| **계** | **45** | **23** | **68** |

- 근거 확인 방식: **[실동작] 41건 · [정적] 27건**(정적은 전건 `file:line` 실측 + 기존 테스트 커버 확인)
- **self-fill 0건** — 통지 6필드 전부 DB 실측임을 *값을 바꿔 payload 가 따라 변하는지*로 확증(아래 §통지 payload 실캡처)
- 신규 이슈 **2건** (MEDIUM 1 · LOW 1)

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 판정 | 근거 |
|---|---|---|---|
| **D-ISSUE-41** (CRITICAL) | `TASK_COMPLETED` payload 가 `totalFrames=0/labeledFrames=0/reviewerName=null` 상수 self-fill | **✅ 해소** | 계약 자체가 **6필드 평면 snake_case** 로 교체됐고(그 세 필드는 소멸 — UNCERTAINTIES #3), 6필드 전부 DB 실측. rawSn 168/169/170 을 서로 다른 DB 값으로 만들어 payload 가 **전 필드 따라 변함**을 확인 |
| **D-ISSUE-42** | `TASK_MODIFIED` 가 프레임↔변경종류 페어링 상실 | **✅ 해소** | `LS_MON_NOTI_ACML.CHG_DTL_CN` = `{"frames":{"184":["LABEL_ADDED"],"185":["LABEL_ADDED"],"186":["LABEL_ADDED","LABEL_DELETED"]}}` 실측 |
| **D-ISSUE-43** | 영상 단위(srcSn=null) 변경 통지가 디바운서 NPE 로 유실 | **✅ 해소** | 신고(META_UPDATED, 영상 단위) 축적·flush 실동작 확인(`flush rawSn=169 regen=true frames=179=[META_UPDATED]`). 축적 저장소가 인메모리→공유 DB(V144)로 이관 |
| **D-ISSUE-44** | `LABEL_ADDED` 가 발행되지 않는 dead 계약값 | **✅ 해소** | 축적행에 `LABEL_ADDED` 실관측(`LabelService:423-424` 가 변경 종류별 분리 발행) |
| **TC-NOTIFY-003 FAIL** | 위 self-fill | **✅ PASS** | §통지 payload 실캡처 |
| **TC-NOTIFY-008 FAIL** | 디바운스 실동작 미검증 | **✅ PASS** | 1초 내 3회 수정 → 축적행 **1건** → 약 64초 뒤 **TASK_MODIFIED 1회** |
| **TC-NOTIFY-014 FAIL** | 재export 트리거·regen 미검증 | **✅ PASS** | 라벨 수정 경로 실동작(export `v2` SUCCEEDED 04:04:58.143 → 통지 04:04:58.193) + 7경로 전부 `exportRegenerated=true` 코드 실측 |
| **D-ISSUE-45** | 관제 조회 API 페이징·상한 부재 | (D-7 소관 — part3) | — |
| **D-ISSUE-46** | `V_COMPLETED_FRAME` 이 원본 경로를 비식별 컬럼으로 노출 | **✅ 해소** | `select count(*) from v_completed_frame where original_path = deidentified_path` = **0**. V133 fail-closed 술어 실동작. 결측(파생 179 등 7행)은 정상 통과 |
| **D-ISSUE-47 / TC-MARTVIEW-008 FAIL** | `V_COMPLETED_LABEL_CHANGE` 가 라벨 본문(좌표) 노출 | **✅ 해소** | 뷰 출력 8컬럼(`LBL_HSTRY_SN/RAW_SN/SRC_SN/ADD_CNT/MDFCN_CNT/DEL_CNT/REG_ID/REG_DT`) — `CHG_DTL_CN` 부재 |
| **D-ISSUE-48** | 통지·조회가 런타임 비활성(`CONTROL_NOTIFY_ENABLED=false`)이라 실동작 미검증 | **✅ 해소** | 이번 회차 `true` + 목서버 관제 스텁(`/api/data-set/v2/jobs/**`) 배선 — D-6 45건 중 **28건을 실동작으로 확정** |
| **D-ISSUE-49** | 뷰 필터 반증 데이터 부재 | **✅ 해소** | `ACTIVE_YN='N'` 스냅샷 1건(rawSn 146) · `META_KEY LIKE 'video.%'` 230건 · `PARTIAL/FAILED` export 3건 · 변경 0건 이력 1건 · 비승인 이력 40건 — **모두 실재하며 4뷰에서 0행** |
| **D-ISSUE-50 / TC-MARTVIEW-014** | 승인→materialize→롤백 뷰 정합 IT 미비 | **❌ 미해소** | `DatasetMaterializeApproveRollbackIT` 는 존재하나 시나리오가 **"materialize 실패 시 approve 전체 롤백"** 하나뿐(`:129`). 라벨 버전 롤백 후 뷰·활성 스냅샷 정합 케이스 없음 → **D-ISSUE-62** |

---

## ★통지 payload 실캡처 (DB 실측 대조 · self-fill 여부)

캡처원: `LS_CONTROL_NOTIFY_FALLBACK.PAYLOAD_CN`(= 실제 전송 본문, `recordImmediateSuccess`) + `klid-mock-server` 인바운드 로그(경로·상태코드).

### TASK_COMPLETED — 6필드 전부 DB 실측 (상수 self-fill 0)

| rawSn | 전송 payload | DB 실측(`LS_DATA_RAW` / `COUNT(LS_DATA_SRC)` / `MNG_EX_LOCAL_GOV`) | 판정 |
|---:|---|---|---|
| 168 | `{"job_id":"168","event_type_cd":"FALLDOWN","lclgv_cd":"41135","lclgv_nm":"경기도 성남시 분당구","duration_sec":45,"image_count":2}` | FALLDOWN / 41135 / 45 / 2 / (41135 `USE_YN='Y'`) | 일치 |
| 169 | `{... "event_type_cd":"LOITERING","lclgv_cd":"41136","lclgv_nm":null,"duration_sec":77,"image_count":3}` | LOITERING / 41136 / 77 / 3 / (41136 **`USE_YN='N'`→ 폐지 명칭 미전송**) | 일치 |
| 170 | `{... "event_type_cd":"FIRE","lclgv_cd":"41137","lclgv_nm":"가…나…"(**length=100**),"duration_sec":99,"image_count":4}` | FIRE / 41137 / 99 / 4 / (조합 121자 → **100자 절단**) | 일치 |
| 126 | `{... "lclgv_cd":"11110","lclgv_nm":null, "duration_sec":30,"image_count":3}` | 11110 은 마스터 **미존재** → null(값 미생성) | 일치 |
| 156 | `event_type_cd:"FIRE"` / `image_count:4` | FIRE / 4 | 일치 |
| 151 | `image_count:0` | `COUNT(LS_DATA_SRC)=0` — **0 이 상수가 아니라 실측 0** | 일치 |
| 179(파생) | `event_type_cd:"FIRE","lclgv_cd":"41137","duration_sec":99,"image_count":4` | 부모(170) 승계값 그대로 DB 보유 | 일치 |

> **self-fill 반증 실험**: 지자체 마스터에 3가지 상태(정상/폐지/초과길이)를 심고, 이벤트유형·지자체코드·영상길이·프레임수가 서로 다른 영상 3건을 새로 만들어 승인했다. 6필드가 **영상마다 전부 다르게** 나갔고 특히 `lclgv_nm` 은 마스터 상태에 따라 값/`null`/절단으로 갈렸다 → **상수 self-fill 아님이 실증**. `duration_sec`/`image_count` 도 45·77·99 / 2·3·4 로 각각 DB 값과 1:1.

### TASK_MODIFIED — 파일명 목록만, 본문·식별자·경로 없음

| 발생 경로 | payload | 확인 |
|---|---|---|
| 재export 동반(regen=true, rawSn 170) | `{"job_id":"170","changed_items":{"images":["0000.jpg","0001.jpg","0002.jpg","0003.jpg"],"jsons":["0000.json",…]}}` | `FRM_NO` 4자리 zero-pad · **전 프레임** · `SRC_SN`·좌표·절대경로·토큰 **없음** |
| 재export 없는 메타 수정(rawSn 173, 타 에이전트 생성) | mock 로그 `images=0 jsons=1` | `buildModified` 는 **jsons 만** 싣는다(이미지 미재생성) |
| 조립 실패 대비(regen=false) | — | 정적: 빈 `changed_items` 로 확정 적재(`ControlNotifyService:181-186`) |

### 경로·상태코드 실왕복 (mock 인바운드)

```
POST /api/data-set/v2/jobs/168/notify-completed  -> 202   (image_count=2)
POST /api/data-set/v2/jobs/170/notify-completed  -> 202   (image_count=4)
POST /api/data-set/v2/jobs/152/notify-completed  -> 409   -> 즉시 /notify-updated -> 202   (409 자기치유)
POST /api/data-set/v2/jobs/170/notify-updated    -> 404   -> 즉시 /notify-completed -> 202 (404 자기치유)
POST /api/data-set/v2/jobs/168/notify-completed  -> 202   (폴백 큐 재조립 재전송)
```

### 순서 보장(export SUCCEEDED → 통지) 실측

| rawSn | export 행 커밋 | 통지 관찰행 | 간격 |
|---:|---|---|---|
| 169 | `export_sn=29 v1 SUCCEEDED 04:02:46.877147` | `queue_sn=32 04:02:46.902278` | +25ms |
| 170 | `export_sn=30 v1 SUCCEEDED 04:02:46.955751` | `queue_sn=33 04:02:46.982033` | +26ms |
| 170(수정) | `export_sn=33 v2 SUCCEEDED 04:04:58.143650` | `queue_sn=36 04:04:58.193896` | +50ms |
| 169(신고 해소 복구) | `export_sn=45 v2 SUCCEEDED 04:13:32.819` | `queue_sn=52 04:13:32.849` | +30ms |

**역전 0건.** 반대로 export 가 막힌 경우(신고 구간)는 통지가 **나가지 않았다**:
```
04:11:28.510 AsyncDatasetExportRunner - async re-export(+notify) starting rawSn=169 forceRegenerate=true
04:11:28.511 DatasetExportService     - export blocked — deident report open rawSn=169
04:11:28.511 AsyncDatasetExportRunner - async export failed rawSn=169 cause=CustomException
   → LS_CONTROL_NOTIFY_FALLBACK 신규행 0 · mock 인바운드 0
```

### 디바운스 (60s 윈도우 · 공유 DB)

| 시각 | 사건 |
|---|---|
| 04:03:53.98 ~ 04:03:54.09 | rawSn 170 의 프레임 3개(184/185/186)에 **연속 3회** 라벨 저장(총 1초) |
| 04:03:54 직후 | `LS_MON_NOTI_ACML` **1행만** 생성 (`uk_lmna_raw_pending` = `UNIQUE(raw_sn) WHERE stts_cd='PENDING'`), `EXPORT_RPRCS_YN='Y'`, `CHG_DTL_CN={"frames":{"184":["LABEL_ADDED"],"185":["LABEL_ADDED"],"186":["LABEL_ADDED","LABEL_DELETED"]}}` |
| 04:04:58 (**+64s**) | flush 1회 → export v2 → **TASK_MODIFIED 1건** (`queue_sn=36`) |

→ 3변경 = **통지 1회**. 윈도우 60s + tick 10s 와 정확히 부합.

### 폴백 큐 · 재시도 · dead-letter (주입 실동작)

| 주입행 | 결과 | 커버 케이스 |
|---|---|---|
| `VERIFY-D6-REBUILD-1` (payload `''` = `PAYLOAD_REBUILD_REQUIRED`, rawSn 168) | 04:09:36 재시도 잡이 claim → **DB 에서 재조립** → mock `notify-completed 202 (image_count=2)` → `SUCCEEDED/SUCCESS` | TC-NOTIFY-021·038 |
| `VERIFY-D6-BADJOB-1` (`job_id="../../etc/passwd"`) | 04:09:36 `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.` → **HTTP 요청 자체가 나가지 않음**(mock 로그 0) → `RTRY_NMTM 0→1`, `PENDING`, `NEXT_RTRY_DT=+2분`(2^1), `LAST_ERR` 정제됨 | TC-NOTIFY-037·019·022·023 |
| 위 행 `RTRY_NMTM=5` 로 조정 후 재발화 | 04:14:36 `RTRY_NMTM=6 > MAX=5` → **`DEAD_LETTER`** + `DLQ_DT` 기록 + `NEXT_RTRY_DT=null` | TC-NOTIFY-020 |

---

## ★뷰 4종 실측

기준 시각 04:15 KST. 뷰 목록(`pg_views`) = `v_completed_video` / `v_completed_frame` / `v_completed_label_change` / `v_completed_meta` **4종만**(라벨 본문 뷰 2종 부재 확인).

| 뷰 | 행수 | 주요 컬럼 실측 | 정책 정합 |
|---|---:|---|---|
| `V_COMPLETED_VIDEO` | 15 | `EXPORT_PATH_NM=/app/storage/raw/seed/{rawSn}` · `FRAME_CNT`(2벌 합) · `DE_IDNTF_FILE_PATH_NM=…/{rawSn}/deid/sample-cctv-1080p-mask.mp4`(KPST 규칙 원문) · `DAY_NGT_CD/SESN_CD/WTHR_NM` **전부 null** | APPROVED 전용(비APPROVED 조인 0) · **영상 1건=1행**(중복 0) · 미export(151·155) 두 값 null · `ACTIVE_YN='N'` 스냅샷(146) 제외 · `PARTIAL/FAILED` export(173 v4~v6) 건너뛰고 최신 SUCCEEDED(v8) 선택 |
| `V_COMPLETED_FRAME` | 52 | `ORIGINAL_PATH=/app/storage/raw/frames/raw/{rawSn}/…` vs `DEIDENTIFIED_PATH=/app/storage/deidentified/frames/deid/{rawSn}/…` · `DESCRIPTION` 컬럼(끝) 존재 | **동일 경로 행 0** (D-ISSUE-46) · 결측(파생 179 등 `ORIGINAL_PATH` null) **7행 정상 노출** |
| `V_COMPLETED_LABEL_CHANGE` | 22 | 출력 8컬럼 — `CHG_DTL_CN` **부재** | 변경 0건 행: 테이블 1건 존재 / **뷰 0행**(V139) · 비승인 이력 40건 존재 / **뷰 0행** |
| `V_COMPLETED_META` | 105 | `META_KEY` = `0-8`·`8-16`… (VLM 시계열), `RVW_STTS_CD='APPROVED'` | `video.*` 키 230건 실재 / **뷰 0행** · 비승인 메타 0행 |

### 파생영상 행 (TC-MARTVIEW-016 실동작)

rawSn **179**(170 의 720P 해상도 파생)을 배정→검수→승인해 실측:

| 컬럼 | 값 | 정책 |
|---|---|---|
| `ORGNL_RAW_SN` | 170 | 파생 표식 |
| `ORIGINAL_VIDEO_PATH` | **NULL** | 동결 시 `RAW_FILE_PATH_NM=null` — **파생엔 원본이 없다**(정상) |
| `DE_IDNTF_FILE_PATH_NM` | `/app/storage/deidentified/videos/resolution/170/179/RESL_720P.mp4` | 관제 픽업 경로(자기 비식별 사본, 부모 원본 폴백 없음) |
| `RESL`/`VDO_WDTH`/`VDO_HGT` | `1920x1080`/1920/1080 | **부모와 동일이 정상**(비디오 복사, 프레임만 리스케일) — 결함 아님 |
| `V_COMPLETED_FRAME`(179) | 4행, `ORIGINAL_PATH` null · `DEIDENTIFIED_PATH` 존재 | 결측 통과 정책(V133) |

### 뷰 멱등성 (TC-MARTVIEW-013 실동작)

V138·V139·V133 의 `CREATE OR REPLACE VIEW` 문을 그대로 재실행 → 3건 모두 성공, `information_schema.columns` 의 **컬럼명·순서·타입 문자열이 재실행 전후 완전 동일**(37컬럼), 행수 불변.

---

## D-6 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-NOTIFY-001 | TASK_COMPLETED 발행 트리거 = export SUCCEEDED | PASS | [실동작] export→통지 4쌍 전부 +25~50ms 순서 보장. `ControlNotifyEventListener.java:38-41` 이 `DatasetExportCompletedEvent` 소비 | 승인 이벤트 직후 발행 아님을 시각으로 확정 |
| TC-NOTIFY-002 | 승인 롤백 시 통지 미발행 | PASS | [정적] `DatasetExportBridge.java:36-37` `@TransactionalEventListener(AFTER_COMMIT)`. 테스트 `ReviewServiceEventPublishTest` | 롤백 유발은 소스 수정 필요라 미수행 |
| TC-NOTIFY-003 | TASK_COMPLETED 페이로드 = 6필드 평면(snake_case) | PASS | [실동작] 168/169/170/126/151/156/179 payload 실캡처, 6필드 전부 DB 1:1. `TaskCompletedPayload.java:26-32`(`@JsonProperty` 고정) | **D-ISSUE-41 해소 확증**. 라벨·PII·토큰·원본경로 없음 |
| TC-NOTIFY-004 | 전송 실패 시 폴백 적재 | PASS | [정적] `ControlNotifyService.java:130-134`. 테스트 `ControlNotifyServiceTest:101 TASK_COMPLETED_실패시_폴백큐_적재됨` | 목서버 중단은 타 에이전트 검증 간섭 위험이라 미유발 |
| TC-NOTIFY-005 | 성공 관찰행 적재(실제 전송분 기준) | PASS | [실동작] rawSn 152: 요청=completed·409 자기치유 → 관찰행 `TASK_MODIFIED`(queue 26~28). rawSn 170: 요청=modified·404 자기치유 → 관찰행 `TASK_COMPLETED`(queue 40) | `SendOutcome` 반환 계약이 실제로 동작 |
| TC-NOTIFY-006 | 관찰행 적재 실패 시 통지 성공 유지 | PASS | [정적] `ControlNotifyService.java:318-325` catch+warn. 테스트 `관찰_적재_실패해도_통지성공_metrics는_유지된다` | |
| TC-NOTIFY-007 | TASK_MODIFIED 페이로드 = 변경 파일명 목록 | PASS | [실동작] `{"job_id":"170","changed_items":{"images":["0000.jpg"…],"jsons":["0000.json"…]}}`. rawSn 173 은 `images=0 jsons=1`(비재생성 경로) | `SRC_SN`·좌표·메타본문·절대경로 없음. 두 리스트 항상 non-null |
| TC-NOTIFY-008 | 디바운스 축적은 공유 DB(V144) | PASS | [실동작] 1초 내 3변경 → `LS_MON_NOTI_ACML` 1행(rawSn·frames↔changeTypes·`EXPORT_RPRCS_YN='Y'`), 부분 유니크 `uk_lmna_raw_pending` DDL 확인 | **1차 FAIL 해소** |
| TC-NOTIFY-009 | 셧다운 시 잔여 drain | PASS | [정적] `ControlNotifyDebouncer.java:81(MAX_DRAIN_ROUNDS=20),191-204 @PreDestroy`. 테스트 `flushAll_모든_윈도우_즉시_flush` | backend 재기동 금지라 실동작 불가 |
| TC-NOTIFY-010 | flush 전용 daemon 스케줄러 | PASS | [실동작] 기동 로그 `flush scheduler started intervalMs=10000 leaseMs=300000`, flush 로그의 스레드명 `[control-notify-debounce-flush]` | `@EnableScheduling` 비의존 확인(`:142-157`) |
| TC-NOTIFY-011 | changeType 계약값 검증 | PASS | [실동작] 축적행에 `LABEL_ADDED`·`LABEL_DELETED`·`META_UPDATED` 관측, **payload 에는 미포함**. `ChangeType.java:16-30` 4종 | `LABEL_UPDATED` 는 코드 실측(`TrackMergeService:201` 등) |
| TC-NOTIFY-012 | 라벨 수정 통지 — APPROVED 후에만 발행 | PASS | [실동작] rawSn 180(ASSIGNED) 라벨 저장 200 → `LS_MON_NOTI_ACML` 0행 | `LabelService.java:414` `isReviewApproved` 게이트 |
| TC-NOTIFY-013 | 라벨 수정 — 무변경 통지 미발행 | PASS | [실동작] 기존 `id` 동반 동일 payload 재저장 → 이력 4→4 · `LBL_VER` 2→2 · 축적행 변화 0 | `id` 미동반 저장은 full-replace(삭제+추가)라 정상적으로 변경으로 집계됨 |
| TC-NOTIFY-014 | 재export 7경로 TASK_MODIFIED(regen=true) | PASS | [실동작] 라벨 경로: export v2 SUCCEEDED → 통지. [정적] 7경로 전부 `exportRegenerated=true` 실측 — `LabelService:423-424`·`TrackEditService:326`·`TrackMergeService:201`·`VersionService:518-519`·`EnvironmentMetaService:125-126`·`FrameDescriptionService:60-61`·`FramePrivacyMetaService:131-132,154-155` | **1차 FAIL 해소** |
| TC-NOTIFY-015 | 폴백 적재 idempotency 중복 방지 | PASS | [정적+DB] `uk_lcnf_idempotency UNIQUE(idmp_key)` DDL 실측 + `ControlNotifyFallbackService.java:91-95` catch. 테스트 `enqueuePending_중복_idempotencyKey_멱등_처리` | |
| TC-NOTIFY-016 | 폴백 큐 깊이 상한(DoS) | PASS | [정적] `ControlNotifyFallbackService.java:36 MAX_QUEUE_DEPTH=10000`, `:74-82` 거부+`control.notify.fallback.queue.full` | 1만행 적재는 미수행 |
| TC-NOTIFY-017 | 폴백 enabled=false 게이트 | PASS | [정적] `:71-73`, `:113-115` `Optional.empty()` | 런타임 토글 on |
| TC-NOTIFY-018 | 재시도 claim 원자 CAS(멀티인스턴스) | PASS | [정적] `:137-143` `claimAtomically` 조건부 UPDATE, `updated!=1 → empty`. 테스트 `claimForRetry_CAS_실패시_empty` | 단일 노드라 경합 실동작 불가 |
| TC-NOTIFY-019 | 재시도 백오프 스케줄 | PASS | [실동작] 1회 실패 → `PENDING` + `NEXT_RTRY_DT = 실패시각+2분`(2^1). `LsControlNotifyFallback.java:207-211` cap 60 | |
| TC-NOTIFY-020 | dead-letter 전이 | PASS | [실동작] `RTRY_NMTM=6 > MAX_RTRY_NMTM=5` → `DEAD_LETTER`, `DLQ_DT=04:14:36`, `NEXT_RTRY_DT=null` | |
| TC-NOTIFY-021 | 재시도 잡 — due 항목 처리 | PASS | [실동작] 주입행 2건을 04:09:36 tick 이 claim→처리(1 성공 / 1 실패격리). `ControlNotifyFallbackRetryJob.java:38-39` 5분 fixedDelay·batch 20 | |
| TC-NOTIFY-022 | 재시도 잡 — 재실패 재스케줄 | PASS | [실동작] `VERIFY-D6-BADJOB-1` → `markFailedAndSchedule` 경로로 `RTRY_NMTM` 증가·재스케줄 | |
| TC-NOTIFY-023 | 에러 메시지 sanitize(토큰/URL/제어문자) | PASS | [실동작] `LAST_ERR_MSG_CN="IllegalArgumentException: 허용되지 않는 job_id 형식입니다."` — 개행·URL·토큰 없음. [정적] `LsControlNotifyFallback.java:246-252` 3단 치환+1900자 truncate, 단위테스트 5건 | |
| TC-NOTIFY-024 | 재시도 성공 관찰행 멱등 | PASS | [정적] `:124-128` UK 충돌 삼킴. 테스트 `recordImmediateSuccess_중복키_충돌시_멱등_처리` | |
| TC-NOTIFY-025 | 토글 off 시 빈 등록 범위 | PASS | [정적] `@ConditionalOnProperty(authoring.control-notify.enabled)` 보유 빈 **10종** 실측(Client/Service/PayloadFactory/EventListener/RetryJob/SchedulingConfig/TaskQueryController/TaskQueryService/HealthIndicator/Metrics). 반면 `ControlNotifyDebouncer.java:73`·`TaskModifiedAccumulateListener.java:27`·`DatasetExportBridge.java:29-30(matchIfMissing=true)` 는 **무조건 등록** | 런타임이 on 이라 off 형상은 정적 판정 |
| TC-NOTIFY-032 | 통지 경로 계약 | PASS | [실동작] mock access log `POST /api/data-set/v2/jobs/{id}/notify-completed\|notify-updated` → 202 수용. `ControlNotifyClient.java:55-58,93` `Content-Type: application/json` | 200/201 수용은 `:96-99` 정적 |
| TC-NOTIFY-033 | 자기치유 — 409 completed→updated | PASS | [실동작] rawSn 146·152·156·173 에서 `409 Conflict` 직후 `notify-updated 202`, 관찰행이 `TASK_MODIFIED` 로 기록 | `ControlNotifyService.java:221-232` |
| TC-NOTIFY-034 | 자기치유 — 404 updated→completed | PASS | [실동작] 목 등록부 reset 후 rawSn 170 → `notify-updated 404` → `notify-completed 202`, 관찰행 `TASK_COMPLETED`(queue 40) | `:251-262` |
| TC-NOTIFY-035 | 자기치유 재귀 금지 | PASS | [정적] `:225-231`,`:255-261` 각 분기가 1회만 전환. 테스트 `폴백은_재귀하지_않는다`·`completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다` | |
| TC-NOTIFY-036 | 4xx/5xx 분류 | PASS | [실동작] 409/404 가 `ControlNotifyStatusException` 으로 전파돼 자기치유 발동(재시도 없이 즉시 전환). [정적] `ControlNotifyClient.java:115-120` 5xx=`IllegalStateException` | 테스트 `관제서버_500_응답시_Retry_3회_후_실패` |
| TC-NOTIFY-037 | job_id 형식 fail-closed | PASS | [실동작] `job_id="../../etc/passwd"` 주입 → `IllegalArgumentException`, **HTTP 요청 미발생**(mock 로그 0건) → 재시도 큐로 격리. `ControlNotifyService.java:73,275-279` | CWE-22/88 차단 실증 |
| TC-NOTIFY-038 | 페이로드 조립 실패 → 통지 유실 금지 | PASS | [실동작] 빈 payload(`PAYLOAD_REBUILD_REQUIRED`) 주입행이 재시도 시점에 **DB 재조립**되어 `image_count=2` 로 발송·`SUCCEEDED` | `:112-123` + `ControlNotifyFallbackRetryJob.java:85-101` |
| TC-NOTIFY-039 | 조립 실패 시 regen 플래그 보존 | PASS | [정적] `ControlNotifyService.java:170-188` regen=false→빈 `changed_items` 확정 적재 / regen=true→REBUILD_REQUIRED. 테스트 2건(MED2) | |
| TC-NOTIFY-040 | export 실패 시 통지 보류 | PASS | [실동작] rawSn 169 신고 구간에서 `export blocked → async export failed` 로그 후 **통지 큐·mock 인바운드 모두 0건**. `AsyncDatasetExportRunner.java:73-75,97-98` 성공시에만 이벤트/콜백 | 관제의 구버전 폴더 픽업 차단 확인 |
| TC-NOTIFY-041 | 실패 export 회수 후 통지 재개 | PASS | [정적] `DatasetExportFailureRecoverer.java:145-160` 조건부 UPDATE 클레임 후 `runApprovalAsync`(완료 이벤트 재발행). `DatasetExportFailureRecoveryIT` 4건 | 회수 유예(기본) 미경과라 실동작 미유발 |
| TC-NOTIFY-042 | 회수기 — 신고 구간은 재시도 예산 미소모 | PASS | [정적] `DatasetExportFailureRecoverer.java:142-146` — `deidentReportGate.isUnderDeidentReport` 가 **클레임 이전**에 continue, `deidentSkipped` INFO 로그 | |
| TC-NOTIFY-043 | 재export 트리거는 통지 토글과 무관 | PASS | [정적] `TaskModifiedAccumulateListener.java:27-37` 무조건 등록 + `ControlNotifyDebouncer` 의 `notifyService`/`metrics` `@Nullable`(`:92-97`), export 위임은 `:296-303` 무조건 | 런타임 토글 on 이라 off 형상 미실측. 테스트 `HIGH-E_통지_토글_off여도…`·`ControlNotifyDebounceCrossNodeIT:176` |
| TC-NOTIFY-044 | 크로스노드 1회 flush | PASS | [정적] `JpaControlNotifyDebounceStore.java:104-110` `claimForFlush` 조건부 UPDATE(`!=1 → empty`). `ControlNotifyDebounceCrossNodeIT:95 2노드에_축적된_디바운스가_한_번만_flush_된다` | 단일 노드라 실동작 불가 |
| TC-NOTIFY-045 | flush 실패 시 임차 회수 | PASS | [정적] `ControlNotifyDebouncer.java:84 MIN_LEASE_MILLIS=60000`, `:134 leaseMillis clamp`, `:242-270` complete 미호출 시 FLUSHING 잔존+`dropped`. 테스트 `flush_실패로_남은_윈도우는_임차_만료_후_재클레임되어_다시_발송된다` | 런타임 `leaseMs=300000` 로그로 설정값 확인 |
| TC-NOTIFY-046 | 폴백 큐 적재 실패 격리 | PASS | [정적] `ControlNotifyService.java:301-309` `enqueueQuietly` 예외 미전파 + `incrementDropped` + ERROR. 테스트 3건 | |
| TC-NOTIFY-047 | `lclgv_nm` 조달 규칙 | PASS | [실동작] 마스터 미존재(11110)→null · `USE_YN='N'`(41136)→null · 121자(41137)→**정확히 100자 절단**(`length()=100` 실측) | `ControlNotifyPayloadFactory.java:66,150-174` |
| TC-NOTIFY-048 | changed_items 미해석 프레임 제외 | PASS | [정적] `:185-219` 두 벌 보유 프레임만 해석, `unresolved` WARN(식별자만)+`incrementUnresolvedFrame`. 테스트 4건 | |
| TC-NOTIFY-049 | 전량 재생성 통지 대상 프레임 | PASS | [실동작+정적] rawSn 170 의 4프레임 전부 `changed_items` 에 실림(=exportable 4건과 일치). `:120-126` `findExportableFrameNosByRawSn` | |
| TC-NOTIFY-050 | 신고 해소 시 보류분 복구 | PASS | [실동작] rawSn 169 resolve → `DatasetExportBridge deident report resolved … re-triggering withheld export/notify` → export v2 SUCCEEDED → `TASK_COMPLETED sent rawSn=169` (queue 52) | `DatasetExportBridge.java:81-88`. 신고 구간 보류분의 **유일 복구 경로** 실증 |
| TC-NOTIFY-051 | 통지 요청 인증 헤더 | PASS | [정적] `WebClientConfig.java:78-81` `controlNotifyWebClient` 는 `baseUrl` 만 설정 — `defaultHeader` 없음. `ControlNotifyClient.java:90-94` 는 `Content-Type` 만 부여 | 기대결과(=미부착 연동 갭)와 일치. **관제 계약 `x-access-token` 미배선** — UNCERTAINTIES #26, 관제팀 협의 대상. 목서버가 헤더를 기록하지 않아 실왕복 확증은 불가 |

---

## D-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-MARTVIEW-001 | V_COMPLETED_VIDEO — APPROVED 게이트 | PASS | [실동작] 뷰 15행 전부 `REVIEW_STTS_CD='APPROVED'`. 비APPROVED 조인 결과 **0행**. 미승인 영상(20011 PENDING·20013 REJECTED·135/137/138 ASSIGNED 등) 미노출 | `V138:82,101` |
| TC-MARTVIEW-002 | V_COMPLETED_VIDEO — export 경로/프레임수 | PASS | [실동작] `EXPORT_PATH_NM=/app/storage/raw/seed/{rawSn}` = `dirname(원본)/{rawSn}` · `FRAME_CNT` 존재. `V138:83-90` LATERAL LIMIT 1 / `EXPORT_VER_NO DESC` | 173 은 v8(최신 SUCCEEDED) 선택 |
| TC-MARTVIEW-003 | V_COMPLETED_VIDEO — 미export null·1row | PASS | [실동작] rawSn 151·155 는 두 값 null 이면서 행 보존. `group by raw_sn having count(*)>1` = **0** | |
| TC-MARTVIEW-004 | V_COMPLETED_VIDEO — PARTIAL/PENDING export 제외 | PASS | [실동작] rawSn 173 은 `v4=PARTIAL·v5/v6=FAILED·v7/v8=SUCCEEDED` 보유 → 뷰는 `FRAME_CNT=6`(v8) 노출, PARTIAL 의 5 가 아님 | 반증 데이터 실재 |
| TC-MARTVIEW-005 | V_COMPLETED_VIDEO — ACTIVE_YN 스냅샷 게이트 | PASS | [실동작] rawSn 146 은 `LS_DATASET_VIDEO_META` 에 `ACTIVE_YN='N'`+`'Y'` 2행 보유 → 뷰에는 **1행만** | `V138:100` |
| TC-MARTVIEW-006 | V_COMPLETED_FRAME — 동일경로 행 fail-closed 제외 | PASS | [실동작] `original_path = deidentified_path` **0행**. 결측 행(파생 179 등 `ORIGINAL_PATH` null) 7행은 정상 통과·노출 | `V133:50-70`. **D-ISSUE-46 해소** |
| TC-MARTVIEW-007 | V_COMPLETED_FRAME — DESCRIPTION 노출 | PASS | [실동작] 출력 9컬럼 `SRC_SN/RAW_SN/FRAME_NO/ORIGINAL_PATH/DEIDENTIFIED_PATH/CAPTURED_AT/REG_DT/UPD_DT/DESCRIPTION`(끝) | `V133:41-49` |
| TC-MARTVIEW-008 | V_COMPLETED_LABEL_CHANGE — 건수만 노출 | PASS | [실동작] 출력 8컬럼, `CHG_DTL_CN` **부재**. `V139:25-37` | **1차 FAIL·D-ISSUE-47 해소** |
| TC-MARTVIEW-009 | V_COMPLETED_LABEL_CHANGE — APPROVED 게이트 | PASS | [실동작] 비승인 영상 소속 이력 **40행 실재** / 뷰 0행. 뷰 22행 전부 승인영상 | `V139:38-43` |
| TC-MARTVIEW-010 | V_COMPLETED_META — RVW_STTS_CD APPROVED만 | PASS | [실동작] 105행 전부 `RVW_STTS_CD='APPROVED'`, 비승인 0행 | `V107:124-146` |
| TC-MARTVIEW-011 | V_COMPLETED_META — video.* 기술메타 제외 | PASS | [실동작] `LS_DATA_META` 에 `video.%` **230행 실재**(승인영상 9건 × 5~6키) / 뷰 **0행** | `V107:140` |
| TC-MARTVIEW-012 | 라벨 내용 뷰 제거 확인 | PASS | [실동작] `pg_views` 에 `v_completed_video/_frame/_label_change/_meta` **4종만** — `V_COMPLETED_LABEL(_ATTR)` 부재 | `V114:30-31` |
| TC-MARTVIEW-013 | 뷰 멱등성(CREATE OR REPLACE) | PASS | [실동작] V138·V139·V133 의 `CREATE OR REPLACE VIEW` 재실행 3건 성공, 컬럼명·순서·타입 문자열 재실행 전후 **완전 동일**, 행수 불변 | |
| TC-MARTVIEW-014 | 승인→materialize→rollback 정합 | 확인필요 | [정적] `DatasetMaterializeApproveRollbackIT:129` 는 **"materialize 실패 시 approve 전체 롤백"** 시나리오뿐. 라벨 버전 롤백 후 뷰·활성 스냅샷 정합 케이스 없음. [실동작] rawSn 170 프레임의 활성 스냅샷이 1건뿐(승인 1회)이라 롤백 대상 다버전 미확보 | **D-ISSUE-50 미해소 확인 → D-ISSUE-62** |
| TC-MARTVIEW-015 | 비식별 영상 경로 노출(V138) | PASS | [실동작] `DE_IDNTF_FILE_PATH_NM=/app/storage/raw/seed/{rawSn}/deid/sample-cctv-1080p-mask.mp4` — KPST 규칙 `{stem}-mask{ext}` **적재값 원문**(조합·치환 흔적 없음). 미완료 영상은 null·행 보존(`V138:91-99` LATERAL) | 파생 179 는 자기 사본 경로 |
| TC-MARTVIEW-016 | 파생영상 ORIGINAL_VIDEO_PATH = NULL | PASS | [실동작] rawSn 179(`ORGNL_RAW_SN=170`) 승인 후 `ORIGINAL_VIDEO_PATH` **NULL**, `DE_IDNTF_FILE_PATH_NM` 로 픽업 가능 | 관제 협의 대상 BREAKING 확인 |
| TC-MARTVIEW-017 | 변경 0건 행 제외(V139) | PASS | [실동작] `LS_DATA_LBL_HSTRY` 에 델타 0 행 **1건 실재**(개인정보 리셋 감사) / 뷰 **0행** | `V139:37` |
| TC-MARTVIEW-018 | 신고 구간에도 뷰 노출 유지(확정 정책) | PARTIAL | [실동작] rawSn 169 를 `DE_IDNTF_YN='F'` 로 만든 뒤: 행 **유지** · `EXPORT_PATH_NM` **유지** · `DE_IDNTF_FILE_PATH_NM` **유지** · `V_COMPLETED_FRAME` 3행 유지 → **핵심 정책 충족**. 단 뷰의 `DE_IDNTF_YN` 이 **동결 스냅샷값 `'Y'`** 로 남아 신고를 반영하지 않음(라이브 `LS_DATA_RAW`='F') | 기대결과 후단 "`DE_IDNTF_YN` 컬럼으로 관제가 자체 판단 가능" 미성립 → **D-ISSUE-61**. 정책(행·경로 유지)은 결함으로 재분류하지 않음 |
| TC-MARTVIEW-019 | 촬영환경 self-fill 제거 — 미입력은 NULL | PASS | [실동작] 승인영상 9건 전부 `DAY_NGT_CD/SESN_CD/WTHR_NM` **null**. `SHT_DT` 기반 NGT/SUMMER 파생 흔적 0 | `DatasetVideoMetaSnapshotService.java:106-122` |
| TC-MARTVIEW-020 | 레거시 파생 동결값 정정 백필 | PASS | [실동작] `GET .../shooting-env-correction-targets` 200, 대상 **0건**(레거시 파생 동결 스냅샷 부재 = 정정 완료 상태). [정적] `DatasetVideoMetaBackfillService.java:182-200` 판별식 단일화·`DatasetVideoMetaEnvCorrectionTx.java:68-83` 건당 `TaskModifiedEvent(regen=true)`. IT 2종 | 대상 0건이라 재동결 본체는 정적 판정 |
| TC-MARTVIEW-021 | 백필 dev API 노출 통제 | PASS | [실동작] REVIEWER 200 / WORKER **403**. [정적] `@Profile("!prd")` + `@PreAuthorize("hasRole('REVIEWER')")` 이중, sub-resource 2개(`?dryRun=` 분기 없음) | prd 미등록은 프로파일 특성상 정적 |
| TC-MARTVIEW-022 | 백필 폭주 방지 | PASS | [정적] `DatasetVideoMetaBackfillService.java:184-200` `envCorrectionMaxPerRun` 상한 + `ENV_CORRECTION_BATCH_SIZE` 페이징 + 시작/잔여 건수 로그, 재실행 자연 no-op | |
| TC-MARTVIEW-023 | 뷰 필터 반증 데이터 | PASS | [실동작] `ACTIVE_YN='N'` 스냅샷 1건 · `META_KEY='video.*'` 230건 **모두 실재하며 4뷰 SELECT 결과 0행**. 추가 반례(PARTIAL/FAILED export 3건·비승인 이력 40건·델타0 이력 1건)도 전부 0행 | **D-ISSUE-49 커버리지 갭 해소** |

---

## 근거 드리프트

카탈로그 `근거` 의 `file:line` 이 **여전히 올바른 멤버를 가리키되 1~10행 어긋난** 건. 판정에는 영향 없으나 다음 최신화 때 정정 권장.

| ID | 카탈로그 근거 | 실측 위치 | 어긋남 |
|---|---|---|---|
| TC-NOTIFY-007 | `TaskModifiedPayload.java:20-54` | record 선언 `21-24`, `ChangedItems` `44-58` | 범위가 실제 두 블록과 반쯤 어긋남 |
| TC-NOTIFY-008 | `ControlNotifyDebouncer.java:174-176` | `accumulate` `175-177` | +1 |
| TC-NOTIFY-009 | `ControlNotifyDebouncer.java:80,190-203` | `MAX_DRAIN_ROUNDS` `81`, `flushAll` `191-204` | +1 |
| TC-NOTIFY-010 | `ControlNotifyDebouncer.java:100-103,141-166` | 필드 `101-104`, `startFlushScheduler` `142-157`, `flushExpiredWindowsSafely` `160-167` | +1 |
| TC-NOTIFY-011 | `ControlNotifyDebouncer.java:289-320` | flush 요약 로그 `292-295`, `summarizeChangeTypes` `312-321` | +1~3 |
| TC-NOTIFY-025 | `ControlNotifyDebouncer.java:72-74` | `@Component/@Slf4j/class` `73-75` | +1 |
| TC-NOTIFY-043 | `ControlNotifyDebouncer.java:285-308` | `send()` `286-309` | +1 |
| TC-NOTIFY-044/045 | `ControlNotifyDebouncer.java:241-269` / `:82-83` | `claimAndSendIsolated` `242-270` / `MIN_LEASE_MILLIS` `84`·`leaseMillis` `110` | +1, 필드 위치 상이 |
| TC-NOTIFY-013 | `LabelService.java:402-412` | 무변경 판정(`changes.isEmpty()`) `398-407` | -4 |
| TC-NOTIFY-012 | `LabelService.java:412-426` | 통지 게이트 `413-426` | +1 |
| TC-NOTIFY-041 | `DatasetExportFailureRecoverer.java:119-170` | `recover()` `119-169`, 클레임 `145-152` | 근사 일치 |
| TC-NOTIFY-042 | `DatasetExportFailureRecoverer.java:141-144` | 신고 스킵 `142-146` | +1 |
| TC-NOTIFY-051 | `WebClientConfig.java:76-80` | `controlNotifyWebClient` `77-82` | +1 |
| TC-NOTIFY-003 | `ControlNotifyPayloadFactory.java:78-92` · `TaskCompletedPayload.java:24-32` | `buildCompleted` `78-92` 일치 / record `26-32` | 후자 -2 |
| TC-MARTVIEW-008 | `V137:22-` | 실질 정의는 **V139** 가 최종(V137 은 중간 개정) | 최신 정의 파일 표기 권장 |

> D-6·D-8 범위에서 **기대결과 자체가 무효화된 근거 드리프트는 없다**(B/G 클러스터와 달리 계약 교체가 이미 카탈로그에 반영돼 있다).

---

## 이슈 상세

### [D-ISSUE-61] TC-MARTVIEW-018 — `V_COMPLETED_VIDEO.DE_IDNTF_YN` 이 동결 스냅샷값이라 비식별 신고 상태를 반영하지 못한다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 확정 정책상 신고 구간에도 행·경로를 유지하되, **관제가 자체 판단할 수 있도록 `DE_IDNTF_YN` 컬럼을 내보낸다**(카탈로그 TC-MARTVIEW-018 기대결과 후단 · `CLAUDE.md` "관제가 원하면 자체 판단도 가능하다"). 즉 이 컬럼은 관제가 "지금 이 영상이 신고 구간인가"를 판별하는 유일한 단서다.
- **현재 동작(이슈 내용)**: 뷰가 이 컬럼을 **라이브 `LS_DATA_RAW` 가 아니라 동결 스냅샷 `LS_DATASET_VIDEO_META` 에서 읽는다.**

  `backend/src/main/resources/db/migration/V138__expose_deident_video_path_in_completed_video_view.sql:49,80`
  ```sql
  m.DE_IDENT_YN      AS DE_IDNTF_YN,
  ...
  FROM LS_DATASET_VIDEO_META m
  INNER JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN   -- r 은 상태 컬럼에 쓰이지 않는다
  ```
  스냅샷은 **검수 승인 시점(materialize)** 에 동결되므로, 승인 이후 접수된 신고는 재동결이 일어나기 전까지 반영되지 않는다.

  실측(rawSn 169):
  ```
  04:11:27  POST /api/v1/labels/179/deident-report  -> 201 (rprtSn=12)
  live :  select de_ident_yn from ls_data_raw where raw_sn=169;              -> F
  frozen: select active_yn, de_ident_yn from ls_dataset_video_meta ...=169;  -> (N,'Y') (Y,'Y')
  view  : select de_idntf_yn from v_completed_video where raw_sn=169;        -> Y     ← 신고 미반영
  ```
  값이 **비결정적**이라는 점이 특히 문제다 — 같은 시점의 rawSn 173 은 신고 이후 재동결이 한 번 더 일어나 뷰가 `F` 를 보여준다. 즉 관제 입장에서 같은 컬럼이 어떤 영상에선 최신, 어떤 영상에선 승인 시점 값이다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report \
       -H "Authorization: Bearer $REVIEWER_OR_ASSIGNED_WORKER" \
       -H 'Content-Type: application/json' -d '{"reason":"..."}'
  ```
  ```sql
  SELECT r.de_ident_yn AS live, v.de_idntf_yn AS view_value
    FROM ls_data_raw r JOIN v_completed_video v ON v.raw_sn = r.raw_sn
   WHERE r.raw_sn = {rawSn};   -- live='F' 인데 view_value='Y'
  ```
- **영향**: 관제가 이 컬럼으로 "마스킹 실패 가능성 있는 산출물"을 스스로 걸러내려 하면 **걸러지지 않는다**(CWE-359 정보 노출 판단 근거 오류). 확정 정책상 저작도구가 뷰에서 차단하지 않는 대신 관제에게 판단 재료를 준다는 전제가 성립하지 않으므로, "잔여 누수"가 아니라 **계약 상 약속한 신호의 부재**다. 파일 접근 자체는 정책대로 허용이 정상이며 그 부분은 결함이 아니다.
- **수정 방향(제안)**: 셋 중 택1로 관제팀과 확정. ①`V138` 의 해당 컬럼을 `r.DE_IDENT_YN`(라이브 `LS_DATA_RAW`, 이미 INNER JOIN 되어 있어 추가 조인 불요)으로 전환 — 다만 `CREATE OR REPLACE` 는 기존 컬럼 타입 변경을 허용하므로 같은 타입이면 안전. ②컬럼 의미를 "승인 시점 값"으로 문서 고정하고 **라이브 신고 상태용 컬럼을 맨 끝에 신규 추가**(하위호환). ③이 컬럼으로 자체 판단하지 않기로 관제와 합의하고 카탈로그·`CLAUDE.md` 문구에서 "자체 판단 가능" 서술 삭제. ⚠ **구현하지 않는다.**

### [D-ISSUE-62] TC-MARTVIEW-014 — 승인→materialize→라벨 버전 롤백 후 뷰·활성 스냅샷 정합 시나리오가 여전히 미커버 (D-ISSUE-50 미해소)

- **심각도**: LOW (커버리지 갭 — 현재까지 관측된 오동작 없음)
- **기대 동작(기대효과)**: 검수 승인으로 동결된 스냅샷이 있는 상태에서 라벨 버전을 롤백(대상 스냅샷 재활성)했을 때, `V_COMPLETED_VIDEO`/`V_COMPLETED_LABEL_CHANGE` 가 정합을 유지하고 **활성 스냅샷이 1건으로 유지**되는지 전용 통합 테스트가 고정해야 한다.
- **현재 동작(이슈 내용)**: 카탈로그가 근거로 지목한 `backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java` 의 유일한 시나리오는
  ```java
  :129  @DisplayName("materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영")
  ```
  로, **"materialize 가 실패하면 승인 트랜잭션이 통째로 롤백된다"**를 검증한다. 케이스가 요구하는 **"승인 성공 → 이후 라벨 버전 롤백 → 뷰 정합"** 과 다른 시나리오다. `VersionService` 롤백 경로를 뷰와 함께 검증하는 IT 는 검색되지 않는다(`DatamartViewSlimIT`·`DatamartViewRebuildIT` 모두 롤백 미포함).
- **재현/확인 경로**:
  ```bash
  grep -rn "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/dataset/service/DatasetMaterializeApproveRollbackIT.java
  grep -rln "VersionService\|rollback" backend/src/test/java/kr/co/cudo/authoring/dataset/view/
  ```
  실동작 측면에서도 이번 회차에서는 확인 불가였다 — 신규 승인 영상(170)의 프레임별 활성 스냅샷이 `APPROVED` 1건뿐이라(`GET /v1/frames/186/versions` → 1건) 롤백 대상 다버전이 존재하지 않고, 다버전 확보에는 반려→재작업→재승인 사이클이 필요해 타 클러스터 참조 데이터를 건드리게 된다.
- **영향**: 롤백이 `LS_LABEL_VERSION` 재활성 방식으로 바뀐 뒤(`SAVE_REASON='ROLLBACK'` 적층 폐기) 뷰/동결 스냅샷과의 상호작용이 회귀 가드 없이 남아 있다. 롤백 시 활성 스냅샷이 2건이 되거나 `V_COMPLETED_LABEL_CHANGE` 에 팬텀 행이 생기는 회귀가 조용히 통과할 수 있다.
- **수정 방향(제안)**: `DatamartViewSlimIT` 에 시나리오 1건 추가 — ①승인(v1 스냅샷) ②라벨 수정 후 재검수·재승인(v2) ③`POST /v1/versions/{v1hash}/rollback` ④단언: `LS_LABEL_VERSION` 활성 1건 = v1 · `V_COMPLETED_VIDEO` 1행 유지 · `V_COMPLETED_LABEL_CHANGE` 에 델타 0 롤백 감사행 미노출. ⚠ **구현하지 않는다.**
