# D 클러스터 part4 — D-2. 검수 상태머신(단위) + D-3. 검수 배정(TC-ASSIGN) + D-4. 버전관리 스냅샷(TC-VERSION)

> 담당 범위: `docs/test-cases/D-review-version-notify.md` **69~134행**(D-2: TC-REVIEW-031~034 4건,
> D-3: TC-ASSIGN-001~026 26건, D-4: TC-VERSION-001~017 17건 = 총 47건).
> 검증일 2026-08-04 KST(3차) · 스택: `_raw/stack-bringup.md` 기준 재빌드 HEAD 이미지(Flyway v163) ·
> 공용 정상 데이터 `_raw/pipeline-drive.md`(rawSn=101, assignmentId=74)는 조회 참고용으로만 사용.
> 지시에 따라 **빌드/테스트 실행 금지** — 정적 대조(코드 Read/Grep) + 기존 단위/통합테스트 존재 확인 위주로 검증했다.
> 프로덕션 코드는 수정하지 않았다. 카탈로그(담당 라인범위 내) 정정 1건은 아래 "카탈로그 정정" 절 참조.

## 검증 방법 요약

- `ReviewStateMachine.java`(전체 65행) · `AssignmentService.java`(전체 687행) · `VersionService.java`(1150행대 발췌: 83-330, 1000-1060)를 전문 Read 하여 카탈로그의 모든 `file:line` 참조를 실제 코드와 1:1 대조.
- 각 케이스마다 대응 단위/통합 테스트를 Grep 으로 확인(`ReviewStateMachineTest` · `AssignmentServiceTest` · `AssignmentReassignConcurrencyIT` · `AssignmentOptimisticLockConflictIT` · `VersionServiceTest`) — 존재 여부와 테스트 내용이 케이스 단언과 일치하는지 대조.
- 이전 회차(1차 `2026-07-25/1차/ISSUES.md`, 2차 통합 `2026-08-01/1차/ISSUES.md`)의 D-ISSUE-03/05/06/07/21/22/81 을 현재 코드로 재확인 — 전부 **코드 변경 없이 그대로 재현**됨을 확인(카탈로그 자체가 일부는 이미 "미해소" 로 명시하고 있어 새 발견이 아니라 재확인).

---

## 판정 결과 — D-2. 검수 상태머신

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-REVIEW-031 | 허용 전이 전수 매트릭스 | PASS | [정적+단위] `ReviewStateMachine.java:43-51` `ALLOWED` 맵 실측 일치(라인 드리프트 없음). 7개 정본 전이 중 5건은 `allowedTransitions()`, 나머지 2건(PENDING→ASSIGNED, APPROVED→PENDING)은 `pendingToAssignedCancelSubmitAllowed`·`approvedToPendingReReviewAllowed` 로 분산 커버 — 7건 전부 테스트로 확인됨 |
| TC-REVIEW-032 | 미허용 전이 전수 | PASS | [정적+단위] `:59-63` 라인 정확 일치. `pendingToApprovedRejected` 테스트가 대표 사례(PENDING→APPROVED) 확인. `allowed==null`(맵 미등록 키, 예: COMPLETED/PROCESSING) 분기도 코드상 동일 경로로 400 |
| TC-REVIEW-033 | APPROVED→IN_REVIEW/REJECTED 직행 | PASS | [정적+단위] `:54-58` 라인 정확 일치. `approvedNonPendingIsConflict` 테스트가 두 케이스(IN_REVIEW/REJECTED) 모두 CONFLICT(409) 확인 |
| TC-REVIEW-034 | 알 수 없는 from 상태 | **PARTIAL** | [정적] `:53-64` 라인 정확 일치. COMPLETED/PROCESSING → `ALLOWED.get(from)`이 null 반환 → INVALID_INPUT(400) 확인(PASS 부분). **from=null → `Map.of()`(`ImmutableCollections.MapN`)의 `get(null)`이 내부적으로 키 해싱을 시도하며 NullPointerException**(카탈로그 서술과 일치, 여전히 미수정) → 처리되지 않으면 500. `ReviewStateMachineTest`에 null/COMPLETED/PROCESSING 케이스 전무(회귀 가드 없음). → **D-ISSUE-61**(1차 D-ISSUE-03 이월, 미해소) |

## 판정 결과 — D-3. 검수 배정

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-ASSIGN-001 | 배정 정상 | PASS | [정적+단위] `AssignmentService.java:71-129`(assign 메서드 시작~종료) 라인 정확 일치. `AssignmentServiceTest`(reviewerId 반영 등) + `AssignmentEventTypeOptionsTest` 등으로 부수효과(LABELER INSERT+markAssigned+이벤트로그) 확인 |
| TC-ASSIGN-002 | 배정 — 비REVIEWER | PASS | [정적] `:73`(requireReviewer 호출), `:668-678`(메서드 전체), throw는 `:675-677` 정확 일치 |
| TC-ASSIGN-003 | 배정 — actor null | PASS | [정적] `:672-674` 정확 일치(actor==null → UNAUTHORIZED, NPE 없음) |
| TC-ASSIGN-004 | 배정 — 존재하지 않는 작업자 | PASS | [정적] `:76-78` 정확 일치. 단, 존재 여부만 검사하고 **역할/활성 여부는 검증하지 않음**(카탈로그 이 케이스의 리터럴 기대결과는 충족되나 별도 교차 이슈 — D-ISSUE-63 참조) |
| TC-ASSIGN-005 | 배정 — 존재하지 않는 검수자 | PASS | [정적] `:79-81` 정확 일치 |
| TC-ASSIGN-006 | 배정 — 중복 배정(UK 충돌) | **PARTIAL** | [정적] `:101-103` 정확 일치, 실제 UK 충돌 시 409 반환은 케이스 리터럴 기대값 그대로 충족(PASS). 단 이 catch 가 **FK 위반·PK 충돌까지 동일 메시지로 오분류**하는 문제가 존재(1차 D-ISSUE-07 실측: 미존재 rawSn 배정 시도도 동일 409+"중복 배정" 메시지) → **D-ISSUE-62**(이월) |
| TC-ASSIGN-007 | 배정 + REVIEWER 동시등록 | PASS | [정적] `:115-119`(옵션 호출), `:188-220`(assignReviewers 메서드) 정확 일치 |
| TC-ASSIGN-008 | 배정 — REVIEWER 중복 등록 skip | PASS | [정적] `:201-219` 범위 내 UK 충돌 격리 로직(`catch DataIntegrityViolationException` → WARN, worker 배정 영향 없음) 확인 |
| TC-ASSIGN-009 | 재배정 정상 | PASS | [정적+단위] `:222-302`(reassign 메서드 시작~종료) 정확 일치. `AssignmentServiceTest`(`reassign_시_actor_sub와_시각이_HSTRY에_기록`, `reassign_시_REASSIGN_이벤트가_prev_subject_와_함께_누적`)로 확인 |
| TC-ASSIGN-010 | 재배정 — 완료(APPROVED) 차단 | PASS | [정적+단위] `:230-240` 정확 일치. `reassign_완료된_배정은_거부_ASSIGNMENT_ALREADY_COMPLETED` 테스트 확인 |
| TC-ASSIGN-011 | 재배정 — 배정 미존재 | PASS | [정적] `:227-228` 정확 일치 |
| TC-ASSIGN-012 | 재배정 — 동일 작업자 | PASS | [정적] `:258-260` 정확 일치 |
| TC-ASSIGN-013 | 재배정 — 새 작업자 이미 배정(사전) | PASS | [정적] `:262-275` 정확 일치 |
| TC-ASSIGN-014 | 재배정 동시성 — @Version 직렬화 | PASS | [정적+integration] `AssignmentReassignConcurrencyIT.concurrentReassignSerializesToSingleTransition` 4스레드 동시 재배정 → **성공 정확히 1건**, 나머지 CONFLICT/INVALID_INPUT, `LS_TASK_ASSIGN_HISTORY` 1행만 적재, REASSIGN 이벤트로그 1행만 확인(실제 PostgreSQL Testcontainer 기반 IT). ★근거 1행 드리프트 발견·정정(아래 카탈로그 정정 참조) |
| TC-ASSIGN-015 | 배정이력 — WORKER 본인만(IDOR) | PASS | [정적] `:322-328` 정확 일치 |
| TC-ASSIGN-016 | 배정이력 — REVIEWER 전체 | PASS | [정적] `:315-361`(getHistory 전체) 정확 일치. userNo batch lookup(`findByUserNoIn` 1회 호출) 구조로 N+1 회피 확인 |
| TC-ASSIGN-017 | 배정이력 — 잘못된 ID | PASS | [정적] `:316-320` 정확 일치 |
| TC-ASSIGN-018 | 배정목록 — WORKER 본인만(param 무시) | PASS | [정적] `:470-482`(scopeForActor), WORKER 분기 `:475-477` 정확 일치 |
| TC-ASSIGN-019 | 배정목록 — REVIEWER 필터/전체 | PASS | [정적] REVIEWER 분기 `:478-480` 정확 일치 |
| TC-ASSIGN-020 | 배정목록 — 기타 역할/미인증 | PASS | [정적+단위] null 401 `:471-473`, 기타역할 403 `:481` 정확 일치. `listAssignments에_actor가_null이면_UNAUTHORIZED`·`PORTAL_USER_역할은_목록_조회시_403`·`역할_미배정_토큰도_목록_조회시_403` 테스트 확인 |
| TC-ASSIGN-021 | 상태 upsert 동시 INSERT 충돌 | **PARTIAL** | [정적] `:657-666`(upsertDataStts) 정확 일치. PK 제약 위반 시 CONFLICT(재시도 가능)라는 리터럴 기대는 충족(PASS)하나, 실제로는 TC-ASSIGN-006과 **동일한 오분류 catch**(`:101-103`)가 "PK 충돌"과 "UK 충돌(중복 배정)"을 구분 없이 "이미 동일 작업자에게 배정된 영상이 있습니다"로 안내 → 진단 혼선(D-ISSUE-62와 동일 근본원인, 중복 등록 아님) |
| TC-ASSIGN-022 | APPROVED 영상 신규 배정 차단 | PASS | [정적+단위] `:145-156`(rejectApprovedTargets), throw `:151-154` 정확 일치. `APPROVED_영상에_신규배정시_409_이고_상태가_APPROVED_로_유지됨` 테스트 확인 |
| TC-ASSIGN-023 | 배정 부분성공 금지 | PASS | [정적+단위] `:83`(rejectApprovedTargets 선행 호출), `:88`(배정 루프) 정확 일치. `배정목록에_APPROVED_가_1건_섞이면_전체실패하고_어떤_배정도_생성되지_않음` 테스트 확인 |
| TC-ASSIGN-024 | 배정 중 승인 경합 → 409 | PASS | [정적+integration] `:89-93`(경합 구간), `:104-112`(CONFLICT 변환) 정확 일치. `AssignmentOptimisticLockConflictIT.assignMapsOptimisticLockFailureToConflict` — `@SpyBean`으로 flush 시 `OptimisticLockingFailureException` 강제 발생 → 500이 아닌 CONFLICT(409) 결정적으로 확인 |
| TC-ASSIGN-025 | 재배정 상태행 공유잠금(FOR SHARE) | PASS | [정적+integration] `:230-240` 정확 일치. `AssignmentReassignConcurrencyIT.reassignGuardLocksStatusRowSerializingConcurrentApproval` — 별도 스레드가 `findByRawDataIdForShare` 잠금을 1초 보유하는 동안 동시 UPDATE가 **≥400ms 대기**함을 실측(잠금이 없으면 즉시 통과) — 공유잠금 실효 확인 |
| TC-ASSIGN-026 | IN_REVIEW 재배정 허용 여부 | **확인필요** | [정적] `:235-240` 정확 일치. 코드는 `STTS_APPROVED` 한 값만 차단하므로 IN_REVIEW 재배정은 현행 **허용**(카탈로그 자체 서술과 일치). 정책 미확정 상태 지속 → **D-ISSUE-64**(1차 D-ISSUE-05 이월, 확인필요) |

## 판정 결과 — D-4. 버전관리 스냅샷

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-VERSION-001 | 승인 스냅샷 생성 | PASS | [정적+단위] `VersionService.java:159-207`(실제 158-208) 근접 일치. `검수_승인시_영상_프레임의_현재_라벨로_DB_스냅샷_APPROVED_버전_생성` 테스트 확인. `pipeline-drive.md` 실동작(rawSn=101)에서도 `LS_LABEL_VERSION`에 `SAVE_REASON=APPROVED, ACTVTN_YN=Y, VERSION_HASH` 실제 생성 확인됨(교차검증) |
| TC-VERSION-002 | 라벨 없는 프레임 스킵 | PASS | [정적+단위] `:190-197` 정확 일치. `HIGH_영상_다중_프레임_각_프레임마다_스냅샷_생성_라벨_없는_프레임은_스킵` 테스트 확인 |
| TC-VERSION-003 | 멱등 — 무변경 재승인 | PASS | [정적+단위] `:288-300` 정확 일치. `HIGH_멱등_재승인_수정_없이_재승인시_동일_payload_중복_버전_미생성` 테스트 확인 |
| TC-VERSION-004 | 재승인 시 변경분 새 버전 적층 | PASS | [정적+단위] 동일 범위. `HIGH_수정_후_재승인시_새_active_버전_생성_이전_active_deactivate` 테스트 확인 |
| TC-VERSION-005 | 프레임 없음 | PASS | [정적+단위] `:170-172`(실제 170-173), `:216-221`(CommitResult.EMPTY) 근접 일치. `프레임이_없는_영상_승인시_스냅샷_0건_생성_예외없음` 테스트 확인 |
| TC-VERSION-006 | 영상 미존재 | PASS | [정적+단위] `:167` 정확 일치. `존재하지_않는_영상_승인_스냅샷_시도시_NOT_FOUND` 테스트 확인 |
| TC-VERSION-007 | rawSn/actor null 가드 | **PARTIAL** | [정적] `:160-165` 정확 일치. actor==null → `CustomException(UNAUTHORIZED)` 정상(401) 확인(PASS 부분). **rawSn==null → 순수 `IllegalArgumentException`** 이며 `GlobalExceptionHandler`에 전용 핸들러가 없어(`ExceptionHandler` 전수 Grep 확인 — CustomException/MethodArgumentNotValidException/ConstraintViolationException/HttpMessageNotReadableException/MissingServletRequestParameterException/MethodArgumentTypeMismatchException/DataIntegrityViolationException/AccessDeniedException/AuthenticationException/MaxUploadSizeExceededException/NoResourceFoundException/HttpRequestMethodNotSupportedException/Exception 뿐) 제네릭 `Exception.class` 핸들러로 떨어져 **500**이 된다. `VersionServiceTest`에 `commitApproved(null,...)` 테스트 전무. → **D-ISSUE-65**(1차 D-ISSUE-81 이월, 미해소) |
| TC-VERSION-008 | 대용량 라벨 1MB 초과 단순화 후 승인 성공 | PASS | [정적+단위] `:90-101`(MAX_PAYLOAD_BYTES/MAX_DEIDENT_PAYLOAD_BYTES 상수), `:1041-`(serializeSnapshotWithSimplification) 정확 일치. `BE_4_대용량_폴리곤_라벨_검수승인_1MB_초과여도_단순화로_정상_스냅샷_생성` 테스트 확인 |
| TC-VERSION-009 | 10MB 초과 프레임 SKIPPED | PASS | [정적] `:264-283`(실제 274-284, snapshotFrameOnApprove의 catch 블록) 근접 일치. 단순화 후에도 초과 시 SKIPPED 반환 + 승인 유지 로직 확인 |
| TC-VERSION-010 | 동시 승인 스냅샷 직렬화(Race) | PASS | [정적] `:288-300`(findActiveForUpdate 비관적 잠금) 정확 일치 |
| TC-VERSION-011 | (폐기) 비식별 신고 스냅샷 | N/A | [정적] `:303-307`(D-25 제거 사유 주석) 정확 일치. 메서드 자체 없음 확인(카탈로그 폐기 표기와 일치) |
| TC-VERSION-012 | (폐기) 비식별 신고 — 라벨 0 스킵 | N/A | [정적] 위와 동일 |
| TC-VERSION-013 | 버전목록 조회 IDOR | PASS | [정적+단위] `:315-326`(listVersions) 정확 일치. `listVersions_미배정_WORKER_접근시_FORBIDDEN` 테스트 확인 |
| TC-VERSION-014 | 스냅샷에 PII/본문 로그 미출력 | PASS | [정적] `:205-206`(log.info — rawSn/frames/created/skipped/actor만, 라벨 본문 없음), `:264-283`(catch 블록 — `e.getMessage()`만 로깅, 페이로드 미출력) 정확 일치 |
| TC-VERSION-015 | 버전목록은 신고 구간에도 200 | PASS | [정적] `:309-326`(listVersions 및 javadoc — 의도적으로 게이트 미적용 서술) 정확 일치. `VersionItem` 응답에 `LABEL_PAYLOAD` 필드 없음 확인 |
| TC-VERSION-016 | 레거시 rawSn 스코프 스냅샷 잔존 행 | PASS | [정적] `:303-307`, `:317`(findByDataSrcSnOrderByRegDtDesc — srcSn 스코프만 조회) 정확 일치 |
| TC-VERSION-017 | 승인 스냅샷 원자성 | PASS | [정적] `ReviewService.java:493-513` 정확 일치. `approve()` 단일 `@Transactional("controlTransactionManager")` 메서드 안에서 `stateMachine.verify`→`stts.transitionTo`→`versionService.commitApproved`→`evntAnnoReviewService.autoApproveOnVideoApproval`→`metaService.autoApproveOnVideoApproval`→`datasetVideoMetaSnapshotService.materialize` 전부 호출됨을 확인. 각 하위 서비스 메서드(`materialize`·`autoApproveOnVideoApproval` 등) 전수 Grep 결과 **REQUIRES_NEW 등 별도 전파 속성 없이 전부 기본(REQUIRED)** 이므로 하나라도 런타임 예외를 던지면 같은 트랜잭션이 전체 롤백됨(코드 구조상 원자성 보장). 전용 IT 테스트는 없으나(코드 구조 기반 PASS) |

### 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| D-2 (검수 상태머신) | 4 | 3 | 0 | 1 | 0 | 0 | 0 |
| D-3 (검수 배정) | 26 | 23 | 0 | 2 | 0 | 0 | 1 |
| D-4 (버전관리 스냅샷) | 17 | 14 | 0 | 1 | 0 | 2 | 0 |
| **합계** | **47** | **40** | **0** | **4** | **0** | **2** | **1** |

> PARTIAL 4건 = TC-REVIEW-034(D-ISSUE-61) · TC-ASSIGN-006(D-ISSUE-62) · TC-ASSIGN-021(D-ISSUE-62 동일 근본원인) · TC-VERSION-007(D-ISSUE-65). 확인필요 1건 = TC-ASSIGN-026(D-ISSUE-64). N/A 2건 = TC-VERSION-011/012(2026-07-30 폐기 확정, 검증 대상 아님— 분모 제외 시 실질 45건 중 PASS 40·PARTIAL 4·확인필요 1).

> ⚠ "PASS 다수"를 그대로 신뢰하지 말 것. D-2/D-3/D-4는 **1~2차 회차에서 이미 발견되고도 계속 미해소인 결함 5건**을 그대로 승계한다(D-ISSUE-61/62/63/64/65). 카탈로그 자체가 이미 "미해소" 주석을 달고 있어 이번 회차는 "재확인"의 성격이 강하지만, 실제 코드가 변경되지 않았음을 파일 Read로 직접 재확인했다.

---

## 이전 회차 이슈 대조

| 이전 이슈 | 대상 | 3차 상태 |
|---|---|---|
| **D-ISSUE-03**(1차) / 재확인(2차 통합본) — `ReviewStateMachine.verify(null,…)` NPE 500 | TC-REVIEW-034 | **미해소 이월.** 코드 변경 없음(`ReviewStateMachine.java:43-63` 그대로). `ReviewStateMachineTest`에 null 케이스 회귀 가드 없음. → 본 회차 **D-ISSUE-61** |
| **D-ISSUE-05**(1차) / **D-ISSUE-26**(카탈로그 자체 표기, 동일 이슈) — IN_REVIEW 영상 재배정 허용 | TC-ASSIGN-026 | **미해소 이월(정책 미확정).** `AssignmentService.java:235-240` 그대로 `STTS_APPROVED`만 차단. → 본 회차 **D-ISSUE-64** |
| **D-ISSUE-06**(1차) / **D-ISSUE-22**(2차 통합본) — 배정 대상 역할·활성 여부 미검증 | TC-ASSIGN-001/004/005/007/009 | **미해소 이월.** `:76-81`(assign), `:255-257`(reassign) 모두 `userRepository.findByUserNo`로 존재 여부만 확인, 역할(`LS_USER_ROLE`)·활성(`USE_YN`) 조인 없음. `AssignmentServiceTest`에 역할 검증 관련 테스트 전무(Grep 확인). → 본 회차 **D-ISSUE-63** |
| **D-ISSUE-07**(1차) / **D-ISSUE-21**(2차 통합본) — FK/PK 위반을 "중복 배정 409"로 오분류 | TC-ASSIGN-006/021 | **미해소 이월.** `:101-103` catch 블록 변경 없음(`DataIntegrityViolationException` 전체를 동일 메시지로 처리). → 본 회차 **D-ISSUE-62** |
| **D-ISSUE-81**(2차 통합본) — `commitApproved`의 `rawSn==null` IllegalArgumentException → 미매핑 500 | TC-VERSION-007 | **미해소 이월.** `VersionService.java:160-162` 그대로, `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러 여전히 없음(전수 Grep 재확인). → 본 회차 **D-ISSUE-65** |
| **D-ISSUE-23**(2차 통합본) — D-3 전 26건 근거 file:line 전면 드리프트 | D-3 전체 | **✅ 해소 확인.** 26건 전수 재대조 결과 25건 정확 일치, 1건(TC-ASSIGN-014)만 1행 오차(290 vs 291) — 카탈로그 changelog(3회차, "AssignmentService.java(D-3, 22건) 하방 이동")가 이미 대부분 정정한 상태였음을 재확인. 잔여 1행 오차는 본 회차에서 정정 완료(아래 카탈로그 정정 참조) |

---

## 카탈로그 정정 (본 회차, 담당 라인범위 69~134 안에서만 수행)

| # | 대상 | 정정 내용 |
|---|---|---|
| 1 | TC-ASSIGN-014 | 근거 `AssignmentService.java:283-286,290-299` → `:283-286,291-299`(라인 290은 직전 `DataIntegrityViolationException` catch의 throw 메시지 계속행이며, `OptimisticLockingFailureException` catch는 291행부터 시작 — 1행 오차 정정) |

> 그 외 D-2(4건)·D-4(17건)·D-3 나머지(25건)의 `file:line`은 전수 재대조 결과 실제 코드와 정확히 일치하여 정정 불필요.
> 프로덕션 코드·테스트·마이그레이션은 일절 수정하지 않았다.

---

## 이슈 대장

### [D-ISSUE-61] TC-REVIEW-034 — `ReviewStateMachine.verify(null, to)`는 여전히 NPE(500) — 400 미보장 (1차 D-ISSUE-03 이월, 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 상태머신은 어떤 입력에도 fail-closed 로 동작해 알 수 없는/누락된 출발 상태를 `INVALID_INPUT(400)`으로 거부해야 한다. 카탈로그(TC-REVIEW-034)도 이를 "기대값은 400"으로 명시한다.
- **현재 동작(이슈 내용)**: `ReviewStateMachine.java:43-51`의 `ALLOWED`는 `Map.of(...)`(JDK `ImmutableCollections.MapN`)이다. `:59` `ALLOWED.get(from)`에서 `from=null`이면 내부적으로 키 해시를 계산하려다 `NullPointerException`이 발생하며, `:60-63`의 `allowed == null` 분기(400 처리)에 도달하지 못한다.
  ```java
  // ReviewStateMachine.java:53-64
  public void verify(String from, String to) {
      if (LsRawDataStatus.STTS_APPROVED.equals(from) && !LsRawDataStatus.STTS_PENDING.equals(to)) {
          throw new CustomException(ErrorCode.CONFLICT, ...);
      }
      Set<String> allowed = ALLOWED.get(from);   // from==null → NPE
      if (allowed == null || !allowed.contains(to)) {
          throw new CustomException(ErrorCode.INVALID_INPUT, ...);
      }
  }
  ```
  COMPLETED/PROCESSING(맵 미등록 키)은 `ALLOWED.get(...)`이 정상적으로 `null`을 반환해 400이 되므로 이 두 값은 정상 동작한다 — 오직 `from=null`만 NPE.
- **재현/확인 경로**: HTTP로는 도달 불가(`LS_RAW_DATA_STATUS.DATA_STTS_CD`는 NOT NULL이고 모든 `transitionTo` 호출자가 null을 넣지 않음). 단위 호출 `new ReviewStateMachine().verify(null, "PENDING")`으로만 재현. `ReviewStateMachineTest.java`(전체 8개 테스트 확인) 전수 Grep 결과 null 케이스 테스트 없음.
- **영향**: 현재 실경로 미도달이라 운영 영향 없음(이론적 결함). 향후 상태 컬럼이 nullable로 바뀌거나 이 클래스가 다른 컨텍스트(외부 인입 등)에서 재사용되면 500 + 예외 처리 미흡(OWASP A10:2025)이 표면화된다.
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null) throw new CustomException(INVALID_INPUT, ...)` 가드 추가 + `ReviewStateMachineTest`에 null 케이스 회귀 테스트 신설. ⚠ 구현하지 않음.

### [D-ISSUE-62] TC-ASSIGN-006 / TC-ASSIGN-021 — 배정 API가 FK 위반·PK 충돌을 전부 "중복 배정 409"로 오분류 (1차 D-ISSUE-07 / 2차 D-ISSUE-21 이월, 미해소)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 존재하지 않는 영상(rawSn)에 대한 배정 시도는 404/400 계열이어야 하고, 상태행 PK 충돌(동시 배정 경합)은 실제 원인에 맞는 메시지를 안내해야 한다. 서로 다른 원인을 같은 메시지로 뭉개면 운영자가 실제로 존재하지 않는 "중복 배정"을 찾아 헤맨다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:101-103`의 `catch (DataIntegrityViolationException)`이 UK 충돌·FK 위반·PK 충돌을 구분 없이 삼켜 동일 문구로 409를 낸다.
  ```java
  // AssignmentService.java:101-103
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  }
  ```
  `assign()` 진입부(`:76-84`)는 workerId/reviewerId 존재만 검증하고 `rawDataIds` 자체의 존재 여부는 검증하지 않으므로, 존재하지 않는 rawSn을 배정 시도하면 INSERT 시 FK 위반이 나고 위 catch가 이를 "중복 배정"으로 안내한다. `upsertDataStts`(`:663-666`)의 동시 INSERT PK 충돌(TC-ASSIGN-021, 서로 다른 작업자 동시 배정 경합)도 같은 경로로 유입되어 동일하게 오분류된다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[999999]}'
  # → 409 {"errorCode":"CONFLICT","message":"이미 동일 작업자에게 배정된 영상이 있습니다."}
  # DB 확인: select count(*) from ls_data_raw where raw_sn=999999;  -- 0
  ```
- **영향**: 기능/운영. API 계약과 어긋난 에러 메시지(리소스 없음=404 원칙 미준수), 진단 혼선. 데이터 정합은 트랜잭션 롤백으로 보존되어 보안 영향은 없음.
- **수정 방향(제안)**: `assign()` 진입부에 `videoRepository.findAllById(rawDataIds)`로 존재 검증(단일 IN 쿼리)을 선행 추가해 미존재 시 404/400을 먼저 던진다. `catch (DataIntegrityViolationException)`에서 제약명(`uk_...` vs `fk_...` vs `..._pkey`)으로 분기하거나, 상태행 upsert를 조건부 INSERT로 분리해 UK 충돌만 "중복 배정" 문구를 쓰게 한다. ⚠ 구현하지 않음.

### [D-ISSUE-63] TC-ASSIGN-001 / TC-ASSIGN-004 / TC-ASSIGN-005 / TC-ASSIGN-007 / TC-ASSIGN-009 — 배정 대상 사용자의 역할·활성 여부를 검증하지 않는다 (1차 D-ISSUE-06 / 2차 D-ISSUE-22 이월, 미해소)

- **심각도**: MEDIUM (CWE-863 Incorrect Authorization)
- **기대 동작(기대효과)**: `CLAUDE.md` "작업 배정" 규칙 — "REVIEWER가 WORKER에게 배정". `LS_TASK_ASSIGNMENT.TASK_TYPE_CD='LABELER'` 행의 `USER_NO`는 WORKER 역할 + 활성(`USE_YN='Y'`) 사용자여야 하고, `'REVIEWER'` 행은 REVIEWER여야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:76-81`(assign)·`:255-257`(reassign)이 `userRepository.findByUserNo(...)`로 **존재 여부만** 확인한다.
  ```java
  // AssignmentService.java:76-81
  if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
  }
  if (req.reviewerId() != null && userRepository.findByUserNo(req.reviewerId()).isEmpty()) { ... }
  ```
  `findByUserNo`는 `USE_YN` 필터도, `LS_USER_ROLE` 조인도 없다. 실제로 작업자 선택 드롭다운을 채우는 `UserRepository.findAllWorkersWithTaskCount()`는 `LsUserRole.roleCd='WORKER' AND u.useYn='Y'`를 이미 걸고 있어(FE 목록은 필터링됨), API는 무검증인 전형적 FE-BE 비대칭이다.
- **재현/확인 경로**: `AssignmentServiceTest.java` 전수 Grep(30개 `@DisplayName` 확인) 결과 역할/활성 검증 테스트 없음(코드 정적 확인만으로 재현 가능 — 1차 회차 실측 재현 기록: REVIEWER 계정을 LABELER로 배정 시 201 성공).
- **영향**: ①PORTAL_USER/REVIEWER에게 배정된 영상은 그 사용자가 내부 워크플로 API를 호출할 수 없어 워크플로가 정체된다(배정 취소 API 부재 — 재배정으로만 회수) ②작업자별 배정 카운트·작업목록·이력에 비-WORKER가 섞여 집계가 오염된다.
- **수정 방향(제안)**: `assign`/`reassign`의 사용자 검증을 역할·활성 확인으로 승격 — `LsUserRoleRepository`로 workerId가 WORKER(+useYn='Y')인지, reviewerId가 REVIEWER인지 검사. 드롭다운 쿼리(`findAllWorkersWithTaskCount`)와 같은 술어를 공유하는 단일 판정 지점으로 추출. ⚠ 구현하지 않음.

### [D-ISSUE-64] TC-ASSIGN-026 — IN_REVIEW 영상 재배정 허용 여부 정책 미확정 (1차 D-ISSUE-05 이월, 확인필요)

- **심각도**: LOW (확인필요 — 의도된 정책일 수 있음)
- **기대 동작(기대효과)**: 카탈로그가 이미 "현행 허용(APPROVED만 차단) — D-ISSUE-05 미해소, 정책 확정 필요"로 명시하고 있어, 이 항목은 결함이 아니라 **정책 확정 대상**이다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:235-240`의 가드는 `STTS_APPROVED` 한 값만 차단한다.
  ```java
  // AssignmentService.java:235-240
  dataSttsRepository.findByRawDataIdForShare(prev.getRawDataId()).ifPresent(stts -> {
      if (LsRawDataStatus.STTS_APPROVED.equals(stts.getDataSttsCd())) {
          throw new CustomException(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED, "완료된 작업은 재배정할 수 없습니다.");
      }
  });
  ```
  IN_REVIEW(검수 진행 중) 영상도 재배정 API가 통과시킨다 — 배정 작업자만 바뀌고 검수 상태(IN_REVIEW)는 그대로 유지된다.
- **재현/확인 경로**: 코드 정적 확인(1차 실측 기록: rawSn이 IN_REVIEW인 상태에서 `PATCH /v1/assignments/{id}` → 200 성공, 상태는 IN_REVIEW 유지, 기존 작업자만 교체됨).
- **영향**: 상태 일관성/운영 혼선(데이터 손상 없음). "검수 중인데 배정 작업자만 바뀐" 상태가 만들어질 수 있음.
- **수정 방향(제안)**: 가드를 `APPROVED` 단일 값에서 검수 진행 상태 집합(`PENDING`/`IN_REVIEW`/`APPROVED`)으로 확장할지, 아니면 현행(검수 중에도 작업자 교체 허용)이 의도된 정책인지 사용자 확정 필요. ⚠ 구현하지 않음.

### [D-ISSUE-65] TC-VERSION-007 — `commitApproved`의 `rawSn==null` 가드가 처리되지 않은 예외로 이어져 500 응답 (2차 D-ISSUE-81 이월, 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: `VersionService.commitApproved(rawSn, actor)`는 `rawSn==null`일 때 의미 있는 4xx로 실패해야 한다. 카탈로그 기대결과("IllegalArgument/UNAUTHORIZED(401)")도 명확한 클라이언트 오류 응답을 전제한다.
- **현재 동작(이슈 내용)**: `VersionService.java:160-162`가 순수 `IllegalArgumentException`을 던지는데, `GlobalExceptionHandler`에는 이를 처리하는 `@ExceptionHandler`가 없다(전수 Grep 재확인: `CustomException`/`MethodArgumentNotValidException`/`ConstraintViolationException`/`HttpMessageNotReadableException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`DataIntegrityViolationException`/`AccessDeniedException`/`AuthenticationException`/`MaxUploadSizeExceededException`/`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/`Exception` 뿐). 따라서 제네릭 `@ExceptionHandler(Exception.class)`로 떨어져 500이 된다.
  ```java
  // VersionService.java:160-165
  if (rawSn == null) {
      throw new IllegalArgumentException("rawSn 은 필수입니다.");
  }
  if (actor == null) {
      throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
  }
  ```
- **재현/확인 경로**: `commitApproved`는 REST로 직접 노출되지 않고 `ReviewService.approve()`가 `stts.getRawDataId()`(항상 non-null FK)로만 호출하는 내부 전용 메서드라 현재 HTTP 요청으로는 도달 불가. `VersionServiceTest.java` 전수 Grep 결과 `commitApproved(null, ...)` 직접 호출 테스트 없음.
- **영향**: 기능 영향 낮음(공개 API 경로 도달 불가). 향후 `commitApproved`가 다른 컨텍스트(관리자 수동 재스냅샷 API 등)에서 재사용되면 이 가드가 그대로 500을 낼 잠재 함정. OWASP A10:2025(Mishandling of Exceptional Conditions) 관점에서 "의미 있는 4xx" 원칙과 어긋남.
- **수정 방향(제안)**: `IllegalArgumentException`을 `CustomException(ErrorCode.INVALID_INPUT, ...)`으로 교체(로컬 교체가 전역 핸들러 신설보다 영향범위가 좁아 안전). ⚠ 구현하지 않음.
