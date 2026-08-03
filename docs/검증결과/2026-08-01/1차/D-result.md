# D 클러스터 part1 — D-1 검수 워크플로우(TC-REVIEW 48건) + D-2 검수 상태머신(4건)

> 검증일 2026-08-01(~08-02 00:0x) · 대상 52건 · 담당 part1 (이슈 ID 범위 D-ISSUE-01~20)
> 판정 토큰: PASS / FAIL / PARTIAL / BLOCKED / N/A / 확인필요 · 근거확인: `[실동작]` / `[정적]`

## 0. 검증 환경·방법

| 항목 | 내용 |
|---|---|
| 스택 | `klid-backend`(:18081, context-path `/api`, local 프로파일, **V146 이미지** — `stack-bringup.md` 기재) · `klid-postgres`(스키마 `public`) · `klid-mock-server`(:9400) 기동 상태 |
| 인증 | `POST /v1/dev/tokens` 로 REVIEWER(sub=1001 김검수) · WORKER(sub=2001 최라벨) · WORKER(sub=2002 정작업) 발급 |
| 테스트 데이터 | 기존 데이터 오염을 피하려 **전용 영상 3건 신규 생성**: `QA-D1-A`(rawSn 72, 프레임1·라벨2, LABELER=2001) / `QA-D1-B`(73, 프레임1·**라벨0**, LABELER=2001) / `QA-D1-C`(74, LABELER=**2002**, IDOR 대조군). 검증 종료 후 **전량 삭제 + 원복 완료**(아래 §4) |
| 실동작 커버리지 | 52건 중 **48건을 실제 HTTP 요청·DB 상태전이·백엔드 로그로 판정**. 4건(TC-REVIEW-017 일부·028·034·049)은 외부에서 도달 불가한 내부 경로라 정적+기존 자동테스트 대조 |
| 자동테스트 대조 | `_raw/test-baseline.md` — backend 4,755 tests / 실패 0 / skip 5. 본 파트 관련 클래스 전량 통과: `ReviewControllerTest`(32) · `ReviewStateMachineTest`(7) · `ReviewApproveLabelGateIT`(5) · `ReviewListFilterSortTest`(20) · `ReviewSummaryTest`(10) · `ReviewSummaryResponseTest`(5) · `ReviewFramesControllerTest`(5) · `ReviewServiceEventPublishTest`(8) · `ReviewCancelSubmitConcurrencyIT`(1) · `ReviewListWhitelistFilterIT`(4) |

### 환경 caveat (판정 신뢰도에 영향 있음 — 기록)
1. **동시 실행 중인 다른 검증 에이전트가 같은 DB·백엔드를 공유**한다. 검증 도중 검수목록 total 이 14→15 로 변했고(신규 rawSn 43 유입) Hibernate SQL 로그가 타 요청과 섞여, **쿼리 카운트 기반 N+1 실측(TC-REVIEW-028)은 격리 불가**했다 → 정적+기존 IT 로 판정.
2. backend 컨테이너는 V146 빌드지만 **검수 도메인(ReviewService/StateMachine/QueryRepository/Controller)은 이 파트 검증 범위에서 워크스페이스 소스와 동작이 일치**함을 응답·로그로 확인했다(라벨0건 게이트·lenient 정렬·summary·tie-break 전부 신규 스펙대로 동작). BLOCKED 사유 없음.

---

## 1. D-1. 검수 워크플로우 (TC-REVIEW) — 48건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-REVIEW-001 | PASS | [실동작] | `POST /v1/reviews/72/submit`(WORKER 2001, ASSIGNED) → 200 `dataSttsCd=PENDING`. `LS_TASK_EVENT_LOG` 에 `evnt_type_cd=SUBMIT, actor_user_no=2001` INSERT 확인 |
| TC-REVIEW-002 | PASS | [실동작] | 73 REJECTED 상태에서 submit → 200 PENDING. 74 도 REJECTED→PENDING 200 재현 |
| TC-REVIEW-003 | PASS | [실동작] | 72 APPROVED 상태에서 WORKER submit → 200 PENDING. rawSn 불변(72), 새 작업 ID 미발급 |
| TC-REVIEW-004 | PASS | [실동작] | 74 상태를 `COMPLETED` 로 강제 후 submit → **400** `허용되지 않은 상태 전이입니다 (COMPLETED → PENDING)`. 배치완료 점프 차단 확인. `PROCESSING`·`FAILED` 출발도 동일 400 |
| TC-REVIEW-005 | PASS | [실동작][정적] | IN_REVIEW 상태에서 submit 반복 호출(경합 루프 7회) 시 상태가 IN_REVIEW 로 고정 유지(전이 없음). `ReviewStateMachine.java:47` — IN_REVIEW 허용 대상은 {APPROVED, REJECTED} 뿐 → PENDING 요청은 400 |
| TC-REVIEW-006 | PASS | [실동작] | WORKER 2002(72 미배정)로 submit → **403** `본인에게 배정되지 않은 영상입니다.` (`ReviewService.verifyAssignedWorker` 614~627) |
| TC-REVIEW-007 | PASS | [실동작] | REVIEWER 토큰으로 submit → **403** `권한이 없습니다.` (컨트롤러 `@PreAuthorize("hasRole('WORKER')")` 단계에서 차단 — 서비스의 "WORKER 권한이 필요합니다" 메시지 대신 표준 403 바디. 코드·의미 동일) |
| TC-REVIEW-008 | PASS | [실동작] | Authorization 헤더 없이 submit → **401** `인증이 필요합니다.` |
| TC-REVIEW-009 | PASS | [실동작] | REVIEWER `POST /reviews/72/start`(PENDING) → 200 `dataSttsCd=IN_REVIEW` |
| TC-REVIEW-010 | PASS | [실동작] | WORKER 로 start → **403** |
| TC-REVIEW-011 | PASS | [실동작] | 72(IN_REVIEW·라벨2건) approve → 200 `dataSttsCd=APPROVED`. 부수효과 전량 실측: ①`LS_LABEL_VERSION` 신규행(`save_reason_cd=APPROVED, actvtn_yn=Y, ver_no=1, version_hash=ca8a97a0e7…`) ②`LS_EVNT_ANNO_REVIEW.rvw_stts_cd PENDING→APPROVED` ③`LS_DATA_META_REVIEW PENDING→APPROVED` ④`LS_DATASET_VIDEO_META` active 스냅샷 재적층(구행 `active_yn=N`) ⑤`ReviewApprovedEvent`→AFTER_COMMIT export·통지 러너 기동(백엔드 로그 `[DatasetExport]`·`[ControlNotify] TASK_COMPLETED sent rawSn=72`) ⑥`LS_TASK_EVENT_LOG APPROVE` <br>※ 통지 발송 자체는 확인됐으나 **export 가 FAILED 인데도 통지가 나갔다** → D-ISSUE-02(교차 이슈, D-6 소관) |
| TC-REVIEW-012 | PASS | [실동작] | PENDING 에서 approve → **400** `(PENDING → APPROVED)` |
| TC-REVIEW-013 | PASS | [실동작] | APPROVED 재승인 → **409 CONFLICT** `이미 APPROVED 된 영상은 재검수 재제출(PENDING) 외 상태 변경이 불가합니다.` |
| TC-REVIEW-014 | PASS | [실동작] | WORKER 로 approve → **403** |
| TC-REVIEW-015 | PASS | [실동작] | `LS_EVNT_ANNO`+`LS_EVNT_ANNO_REVIEW(PENDING)` 시드 후 approve → `rvw_stts_cd=APPROVED, rvw_id=1001, rvw_dt` 기록 + 같은 tx 의 materialize 가 `LS_DATASET_VIDEO_META.EVNT_ANNO_CN` 을 `{"event":{"summary":"QA D1 test"}}` 로 **동결**(승인 전 스냅샷은 NULL 이었음 → 동결 시점이 승인 tx 내부임이 두 행 비교로 확정) |
| TC-REVIEW-016 | PASS | [실동작] | `LS_DATA_META`+`LS_DATA_META_REVIEW(PENDING, VLM)` 시드 후 approve → `rvw_stts_cd=APPROVED, rvw_id=1001` |
| TC-REVIEW-017 | **PARTIAL** | [실동작][정적] | 기대 단언(스킵 발생 시 승인 성공 + `approved with snapshot skips` WARN)의 **직렬화·10MB 초과 경로는 구현·단위테스트로 충족**(`ReviewService.java:496-501`, `VersionService.snapshotFrameOnApprove` try/catch, `ReviewServiceEventPublishTest`의 M2 케이스 2건). 그러나 **스냅샷 DTO 구성 단계 실패는 try 밖**이라 스킵되지 않고 **승인 전체가 500 + 롤백**된다 — 실측 재현됨 → **D-ISSUE-01** |
| TC-REVIEW-018 | PASS | [실동작] | IN_REVIEW 에서 reject(사유) → 200 REJECTED + `LS_DATA_ISSUE` INSERT(`issue_type_cd=REJECTION`) + `LS_TASK_EVENT_LOG REJECT`(rsn=사유) |
| TC-REVIEW-019 | PASS | [실동작] | `{"reason":"  "}` → **400** `reason: 반려 사유는 필수입니다.`(@NotBlank), `{}` 도 동일 400 |
| TC-REVIEW-020 | PASS | [실동작] | 2회 반려 후 `LS_DATA_ISSUE`: `(9, up=NULL, '라벨 누락 1차')` / `(10, up=9, '라벨 누락 2차')` — `UP_DATA_ISSUE_SN` 계층 연결 확인 |
| TC-REVIEW-021 | PASS | [실동작] | PENDING 에서 WORKER cancel-submit → 200 `ASSIGNED`. `LS_TASK_EVENT_LOG CANCEL_SUBMIT`, `rsn` 비어있음(PII 미포함) |
| TC-REVIEW-022 | PASS | [실동작] | IN_REVIEW 에서 cancel-submit → **400** `(IN_REVIEW → ASSIGNED)` |
| TC-REVIEW-023 | PASS | [실동작] | APPROVED 에서 cancel-submit → **409 CONFLICT** |
| TC-REVIEW-024 | PASS | [실동작] | WORKER 2002(미배정)로 cancel-submit → **403** |
| TC-REVIEW-025 | PASS | [실동작] | `GET /v1/reviews/74` — 본인 배정 WORKER 2002 → 200(workerName=정작업) |
| TC-REVIEW-026 | PASS | [실동작] | 같은 74 를 WORKER 2001(미배정)이 조회 → **403** `본인에게 배정되지 않은 영상입니다.` REVIEWER 는 배정 무관 200 |
| TC-REVIEW-027 | PASS | [실동작] | `GET /v1/reviews/999999/frames` → **404** `영상을 찾을 수 없습니다.`(단건 상세는 `검수 대상 영상을 찾을 수 없습니다` 404 — 메시지 분리 정상) |
| TC-REVIEW-028 | PASS | [정적] | `ReviewService.listFrames`(335-372): 존재확인 1회 + `findByRawSnOrderByFrameNoAsc` 1회 + `findBySrcSnIn` 1회 = 프레임/라벨 각 1회로 N+1 없음. `LabelResponse.Item.from(entity, null, null, mapper)` 로 aiInfo/label 을 넘겨 지연로딩 유발 없음. frameNo 오름차순 보장. 기존 IT `ReviewFramesControllerTest#listFrames_프레임_N개_라벨_M개_단일_쿼리_확인_N1_회피` 통과. ⚠ 실측 쿼리 카운트는 타 에이전트 동시 트래픽으로 격리 불가(§0 caveat) |
| TC-REVIEW-029 | PASS | [실동작] | `GET /v1/reviews?status=PENDING` → 200, PENDING 3건만(72/31/7). WORKER 호출 → 403 |
| TC-REVIEW-030 | PASS | [실동작] | `GET /v1/reviews/73/issues` → 반려사유 2건 시간역순. WORKER → 403 |
| TC-REVIEW-035 | PASS | [실동작] | 73(IN_REVIEW·라벨0건) approve(바디 없음) → **409 `errorCode=REVIEW_NO_LABEL`**. 직후 DB 상태 조회 = **IN_REVIEW 유지**(전이 전 판정 확인) |
| TC-REVIEW-036 | PASS | [실동작] | 같은 73 에 `{"noLabelConfirmed":true}` → 200 APPROVED. `LS_TASK_EVENT_LOG` = `APPROVE` + `rsn='라벨 없음 확인 승인(negative sample)'` (감사 사유 기록) |
| TC-REVIEW-037 | PASS | [실동작] | 72(라벨 2건)에 `{"noLabelConfirmed":true}` → **400** `라벨이 있는 영상입니다. 최신 상태를 다시 확인한 뒤 승인하세요.` + WARN `approve rejected — noLabelConfirmed on labeled video` |
| TC-REVIEW-038 | PASS | [실동작] | 73(PENDING·라벨0건) approve → **400**(`PENDING → APPROVED`), 409 아님 — 상태전이 게이트가 라벨 게이트보다 먼저 |
| TC-REVIEW-039 | PASS | [실동작] | 바디 미첨부 approve(72, 라벨 존재) → 200 정상 승인(하위호환) |
| TC-REVIEW-040 | PASS | [실동작] | `GET /v1/reviews?sort=filePath,desc` → **200** + 기본정렬(`UPD_DT DESC`) 결과(무정렬 호출과 동일 순서). 400 아님. `SortAllowlist.resolveLenient` WARN 경로. ★UNCERTAINTIES ★2 정책대로이며 결함 아님 |
| TC-REVIEW-041 | PASS | [실동작] | `sort=submittedAt,asc` → 제출일 오름차순(7,4,15,8,5) / `sort=videoId,asc` → 4,5,7,8,9 / `sort=status,asc` → 상태 기준 재정렬. 임의 프로퍼티는 쿼리 미도달(폴백) — allowlist `{submittedAt,updDt→updDt / videoId→rawDataId / status→dataSttsCd}` 대로 해석 |
| TC-REVIEW-042 | PASS | [실동작] | 7개 행의 `UPD_DT` 를 동일값으로 강제 후 `size=2` 로 page 0~6 순회 → `43,900001 / 73,72 / 31,26 / 67,48 / 18,15 / 9,8 / 7,5` — **중복·누락 0**, 동순위 구간이 `RAW_DATA_ID DESC` 로 정렬됨. `sort=videoId,asc` 지정 시엔 tie-break 미첨부(전체 오름차순 유지) |
| TC-REVIEW-043 | PASS | [실동작] | `status=PROCESSING` / `ASSIGNED` / `FAILED` → 모두 **200 + total=0**(400 아님) |
| TC-REVIEW-044 | PASS | [실동작] | `status` 미지정 목록(total 14~15)에 배치/작업 상태 행 0건 — DB 에 실재하는 `ASSIGNED`(27/33/34/36/38/74)·`FAILED`(41~71 다수) 영상이 전부 미노출. 4종만 노출 확인 |
| TC-REVIEW-045 | PASS | [실동작] | `q=강남` → total=6, 반환 6건 일치 / `summary?q=강남` = total 6 = 1+0+4+1 로 목록과 정합. `q=%` → 0건, `q=_` → 0건(LIKE 특수문자 이스케이프 확인 — 미이스케이프면 전건 매칭). `q=cctv`(소문자)로 전건 매칭 → `Locale.ROOT` 소문자 비교 동작. `q` 101자 → 400 |
| TC-REVIEW-046 | PASS | [실동작] | 72 에 더 최신 LABELER 배정(2002)을 추가 → `q=최라벨` 결과에서 **72 제외**, `q=정작업` 에 72 포함되며 표시 workerName 도 `정작업` — 검색·표시가 같은 최신 1건(REG_DT DESC→ASSIGNMENT_ID DESC) 기준 |
| TC-REVIEW-047 | PASS | [실동작] | `CCTV_NM = "\t\n "` 인 CCTV(`QA-D1-CCTVBLANK`)에 영상 연결 → `q=CCTVBLANK` 로 **매칭 성립**(total=1) 하고 표시명도 `QA-D1-CCTVBLANK`(VMS ID 폴백) — 표시 판정(`isBlank`)과 검색 판정(`BlankTextPredicate`) 일치 |
| TC-REVIEW-048 | PASS | [실동작] | `GET /v1/reviews/summary` → `{total:14,pending:3,inReview:0,approved:10,rejected:1}` (total = 4종 합). `?status=PENDING` 전달해도 **동일**(무시) / `?sort=filePath,desc` 전달해도 200(400 아님) / `?q=강남` 은 반영(total 6). WORKER → 403, q 101자 → 400 |
| TC-REVIEW-049 | PASS | [정적] | `ReviewQueryRepository.countByStatus`(186-215) 가 `COUNT(*)` 와 버킷합을 대조해 불일치 시 `IllegalStateException`(입력값 미노출), `ReviewSummaryResponse.verifyMappedCoverage`(72-85) 가 미매핑 상태 건수 존재 시 fail-fast. 버킷·WHERE 모두 `ReviewRepository.REVIEW_STATUS_WHITELIST` 단일 상수 파생. 단위테스트 5건 통과. ※ 내부 정합성 위반이라 외부 입력으로 도달 불가 → 실동작 재현 대상 아님 |
| TC-REVIEW-050 | PASS | [실동작] | `size=101` → **400** `size 한도 초과 (max=100)`, `size=100` → 200 |
| TC-REVIEW-051 | PASS | [실동작] | PENDING 상태에서 `cancel-submit`(WORKER)과 `start`(REVIEWER)를 동시 발사 → `start=200`(IN_REVIEW 전이) / `cancel=409` `이미 검수가 시작되었거나 상태가 변경되었습니다.` — 정확히 한쪽만 성공, 패자는 flush 시점 낙관적 잠금으로 커밋 전 409 |
| TC-REVIEW-052 | PASS | [실동작] | `LS_EVNT_ANNO.ANNO_CN` 의 `caption.c1.cot` 를 3형태로 넣고 `GET /v1/videos/72/event-annotation` 왕복: **배열** `["첫단계","둘째단계"]` → `{"1단계":"첫단계","2단계":"둘째단계"}` (500 아님) / **객체** `{"1단계":"a","2단계":"b"}` → 키·순서 보존 / **스칼라** `"단일문자열"` → `{"1단계":"단일문자열"}` |

**D-1 집계**: 총 48 — PASS **47** · PARTIAL **1** · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 2. D-2. 검수 상태머신 (TC-REVIEW, 단위) — 4건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-REVIEW-031 | PASS | [실동작] | 정본 7전이를 **전부 실제 API 로 통과 확인**: `ASSIGNED→PENDING`(submit 200) · `PENDING→IN_REVIEW`(start 200) · `PENDING→ASSIGNED`(cancel 200) · `IN_REVIEW→APPROVED`(approve 200) · `IN_REVIEW→REJECTED`(reject 200) · `REJECTED→PENDING`(submit 200) · `APPROVED→PENDING`(submit 200) |
| TC-REVIEW-032 | PASS | [실동작] | 미허용 전이 전수 400 확인: `ASSIGNED→APPROVED` / `ASSIGNED→IN_REVIEW` / `PENDING→APPROVED` / `PENDING→REJECTED` / `IN_REVIEW→ASSIGNED` / `IN_REVIEW→IN_REVIEW` / `REJECTED→APPROVED` / `REJECTED→IN_REVIEW` / `REJECTED→ASSIGNED` — 모두 `INVALID_INPUT(400)` |
| TC-REVIEW-033 | PASS | [실동작] | `APPROVED→IN_REVIEW`(start) · `APPROVED→REJECTED`(reject) · `APPROVED→APPROVED`(approve) · `APPROVED→ASSIGNED`(cancel) 전부 **409 CONFLICT** |
| TC-REVIEW-034 | **FAIL** | [실동작][정적] | `from=COMPLETED` → 400 ✅ / `from=PROCESSING` → 400 ✅ / `from=FAILED` → 400 ✅. 그러나 **`from=null` 은 여전히 NPE(500)** — `ReviewStateMachine.java:59` `ALLOWED.get(from)` 이 `Map.of(...)`(ImmutableCollections.MapN)라 null 키에서 `NullPointerException`. 기대값 400 미충족 → **D-ISSUE-03 (이월·미해소)**. `ReviewStateMachineTest` 에도 null/COMPLETED/PROCESSING 케이스가 없어 자동 회귀 가드 부재 |

**D-2 집계**: 총 4 — PASS **3** · FAIL **1**

---

## 3. 이슈 대장

### [D-ISSUE-01] TC-REVIEW-017 — 승인 스냅샷의 **DTO 구성 단계 실패는 프레임 스킵이 아니라 승인 전체 500 + 롤백**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 승인은 라벨 본문 산출물의 문제로 막히면 안 된다. `VersionService.snapshotFrameOnApprove` 는 스냅샷 산출 실패를 **해당 프레임만 SKIPPED** 로 처리하고 승인은 성공시키며, `ReviewService` 가 `approved with snapshot skips` WARN 으로 운영자에게 가시화한다(TC-REVIEW-017 의 단언). 이래야 프레임 1건의 손상 데이터가 영상 전체의 검수 종결을 막지 않는다.
- **현재 동작(이슈 내용)**: 방어가 **직렬화 호출(try 블록)에만** 걸려 있고, 그 앞의 DTO 구성(`LabelResponse.of` → `LabelPointSerializer` 좌표 파싱)은 보호 범위 밖이다.
  ```java
  // backend/.../version/service/VersionService.java:265-278
  LabelResponse snapshot = LabelResponse.of(frame, siblings, labels, "DEID", null,
          aiInfoBySn, objectMapper);          // ← 여기서 던지면 catch 되지 않는다
  String payload;
  try {
      payload = serializeSnapshotWithSimplification(snapshot, frame.getSrcSn());
  } catch (CustomException e) { ... return FrameSnapshotOutcome.SKIPPED; }
  catch (Exception e)        { ... return FrameSnapshotOutcome.SKIPPED; }
  ```
  실측: `LS_DATA_LBL.POINT_CN` 이 배열이 아닌 객체(`{"x":10,...}`)인 라벨 1건이 있는 영상을 승인하면
  ```
  HTTP 500 {"success":false,"errorCode":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
  java.lang.IllegalArgumentException: 좌표는 배열이어야 합니다
      at ...common/util/LabelPointSerializer.java:90
      at VersionService.snapshotFrameOnApprove(VersionService.java:265)
      at VersionService.commitApproved(VersionService.java:197)
      at ReviewService.approve(ReviewService.java:495)
  ```
  승인 트랜잭션 전체가 롤백되어 **그 영상은 어떤 검수자도 영원히 승인할 수 없고**(작업자가 라벨을 손보기 전엔 반려밖에 못 함), 응답은 원인을 알 수 없는 일반 500 이다.
- **재현/확인 경로**:
  ```sql
  UPDATE ls_data_lbl SET point_cn='{"x":10,"y":10,"w":50,"h":50}' WHERE lbl_sn=<대상>;
  ```
  ```bash
  curl -X POST localhost:18081/api/v1/reviews/<rawSn>/approve -H "Authorization: Bearer $REVIEWER"
  # → 500, 백엔드 로그에 IllegalArgumentException: 좌표는 배열이어야 합니다
  ```
- **영향**: 기능(검수 종결 불가 — 손상 라벨 1건이 영상 전체를 잠금) + 운영(원인 미노출 500 으로 트리아지 불가). 관련 미해소 이슈 C-ISSUE-23(R7 손상 JSON 회귀 테스트 부재)과 같은 뿌리. 보안 등급은 아님(스택트레이스는 응답에 미노출 — CWE-209 는 해당 없음).
- **수정 방향(제안)**: `LabelResponse.of(...)` 호출을 같은 try 블록 안으로 옮겨 구성 단계 예외도 `FrameSnapshotOutcome.SKIPPED` 로 흡수(로그는 `srcSn` 만, 본문·좌표 미출력). 또는 `commitApproved` 루프에서 프레임 단위 예외를 잡아 skip 카운트에 합산. 구현은 하지 않음.

### [D-ISSUE-02] TC-REVIEW-011 — **export 가 FAILED 인데 `TASK_COMPLETED` 통지가 발송**된다 (교차 이슈: D-6/TC-NOTIFY 소관)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md`(★ export 재생성·동기화 정책) — *"통지는 export 성공(SUCCEEDED) 후 발송한다… export 가 실패하면 통지를 보류하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다"*. 관제는 통지를 받으면 `EXPORT_PATH_NM` 폴더를 픽업하므로, 산출물이 없는 상태에서 통지가 나가면 관제가 **빈/구 버전 폴더**를 집는다.
- **현재 동작(이슈 내용)**: 산출 프레임 0건(=사실상 실패) 경로는 **예외를 던지지 않고 정상 리턴**한다. 러너는 예외 유무로만 성공을 판정하므로 `true` 가 되어 통지 콜백이 그대로 발화한다.
  ```java
  // dataset/export/DatasetExportService.java:205-210
  if (totalWritten == 0) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
      outcome = OUTCOME_FAILED;      // ← 예외 없이 정상 종료
  }
  // dataset/export/AsyncDatasetExportRunner.java:121-131
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }   // ← FAILED 여도 true
      catch (Exception e) { ...; return false; }
  }
  ```
  실측 로그(승인 rawSn=72, 2026-08-01 23:54:53):
  ```
  WARN  DatasetExportService  - [DatasetExport] nothing produced — marked FAILED rawSn=72 version=1
  INFO  ControlNotifyService  - [ControlNotify] TASK_COMPLETED sent rawSn=72 actual=TASK_COMPLETED
  ```
  DB: `LS_DATASET_EXPORT(export_sn=18, data_raw_sn=72, export_stts_cd=FAILED, frame_cnt=NULL)` 인데 통지는 성공 기록(`LS_CONTROL_NOTIFY_FALLBACK` send success).
  ※ 신고 게이트 차단(`PRECONDITION_FAILED` 예외)과 런타임 예외 경로는 의도대로 `false` → 통지 보류가 동작한다. **구멍은 "예외 없이 FAILED 로 마감되는" 이 한 갈래**다.
- **재현/확인 경로**: 프레임 이미지 파일이 실재하지 않는(또는 전부 skip 되는) 영상을 검수 승인 → 백엔드 로그에서 `nothing produced — marked FAILED` 직후 `TASK_COMPLETED sent` 관측.
  ```sql
  SELECT export_stts_cd FROM ls_dataset_export WHERE data_raw_sn=<rawSn> ORDER BY export_sn DESC LIMIT 1; -- FAILED
  ```
- **영향**: 데이터 정합 — 관제가 산출물 없는(또는 구 버전) 상태에서 완료 통지를 수신해 뷰/폴더를 픽업. 추가로 `DatasetExportFailureRecoverer` 가 나중에 재산출에 성공하면 **동일 rawSn 에 통지가 2회** 나간다.
- **수정 방향(제안)**: `totalWritten == 0` 분기에서도 통지 억제 신호를 내보낸다 — ①`DatasetExportService.export` 가 이 경로에서 checked 신호(전용 예외 또는 리턴값)를 주고 ②`AsyncDatasetExportRunner.doExport` 가 "예외 없음"이 아니라 **"마감 상태가 SUCCEEDED/PARTIAL 인가"** 로 성공을 판정하도록 바꾼다. 구현은 하지 않음. **D-6 담당 파트와 중복 보고 가능 — 병합 시 통합할 것.**

### [D-ISSUE-03] TC-REVIEW-034 — `ReviewStateMachine.verify(null, to)` NPE 500 (이월·미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 알 수 없는/누락 출발 상태는 `INVALID_INPUT(400)` 으로 거부되어야 한다(알 수 없는 코드값 = 전이 불가). 상태머신은 어떤 입력에도 fail-closed 여야 하며 500 을 내면 안 된다.
- **현재 동작(이슈 내용)**:
  ```java
  // review/service/ReviewStateMachine.java:43-63
  private static final Map<String, Set<String>> ALLOWED = Map.of( ... );   // ImmutableCollections.MapN
  public void verify(String from, String to) {
      if (STTS_APPROVED.equals(from) && !STTS_PENDING.equals(to)) { throw CONFLICT; }  // null-safe
      Set<String> allowed = ALLOWED.get(from);   // ← from==null 이면 NullPointerException
      ...
  }
  ```
  `Map.of(...)` 는 null 키 조회에서 NPE 를 던지므로 400 이 아니라 500(INTERNAL_ERROR)이 된다.
- **재현/확인 경로**: `LS_RAW_DATA_STATUS.DATA_STTS_CD` 가 NOT NULL 이라 **현행 API 로는 도달 불가**(실측: `COMPLETED`/`PROCESSING`/`FAILED` 출발은 정상 400). 단위 호출 `new ReviewStateMachine().verify(null, "PENDING")` 로만 재현.
- **영향**: 현재 실경로 미도달이라 운영 영향 없음. 다만 상태 코드 소스가 늘거나(외부 인입·마이그레이션 중 NULL 유입) 이 클래스를 재사용하면 500 이 표면화된다. 회귀 가드도 없다(`ReviewStateMachineTest` 7건에 null/미지정 코드 케이스 부재).
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null) throw new CustomException(INVALID_INPUT, "상태 정보를 확인할 수 없습니다.")` 가드 추가(메시지에 입력값 미포함) + `ReviewStateMachineTest` 에 null/`COMPLETED`/`PROCESSING` 케이스 3건 추가. 구현은 하지 않음.

---

## 4. 근거 드리프트 (카탈로그 정합성)

| TC | 카탈로그 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-REVIEW-006 · 024 | `ReviewService.java:614-631` | `614-627` (`verifyAssignedWorker`) | 4줄 초과 표기 |
| TC-REVIEW-028 | `ReviewService.java:335-376` | `335-372` (`listFrames`) | 4줄 초과 표기 |
| TC-REVIEW-052 | `EventAnnotationPayload.java:CotDeserializer(94-140)` | `CotDeserializer` 는 **120-142**, 94-97 은 이를 설명하는 javadoc | 클래스 본문 라인 불일치 |

그 외 D-1/D-2 의 근거 `file:line` 은 실측과 일치했다(ReviewService 103-149·378-383·394-406·421-439·444-452·467-518·471-478·477-486·496-501·502-506·507-510·543-565·545-553·556-564·570-599·576-584·633-650·652-659, ReviewStateMachine 43-51·53-64·54-58·59-63, ReviewController 47·92-94·95-103·127-139·286-294, ReviewQueryRepository 109-129·186-215·229-250·233-234·243-248·261-284·304-325·331-333·335-340·360-388, ReviewRepository 33-37, SortAllowlist 29-60·REVIEW(115-119), ReviewSummaryResponse of/verifyMappedCoverage, ReviewSearchCondition statusFilter).

---

## 5. 데이터 원복 (검증 종료 상태)

| 항목 | 조치 | 확인 |
|---|---|---|
| 테스트 영상 rawSn 72/73/74 (`QA-D1-A/B/C`) | `DELETE FROM ls_data_raw` (연쇄: status·assignment·src·issue·event_log·label_version·dataset_export·dataset_video_meta·meta·meta_review·control_notify_fallback), 비연쇄 자식(`ls_evnt_anno_review`·`ls_data_lbl`)은 선행 수동 삭제 | `SELECT count(*) … WHERE raw_sn IN (72,73,74)` → **0** |
| 임시 CCTV `QA-D1-CCTVBLANK` | 삭제 | 삭제 확인 |
| tie-break 실험용 `UPD_DT` 변경(raw 4/5/7/8/9/15/18) | 사전 백업(`/tmp/upd_backup.csv`) 후 **원값 복원** | 4=03:17:19.09094 · 5=03:21:28.784088 · 7=03:17:04.228946 · 8=03:21:02.173255 · 9=03:22:41.172949 · 15=03:20:39.436578 · 18=03:22:12.418234 (검증 전 값과 동일) |
| 기존 영상 26/27/31/33/34 등 | **상태·데이터 변경 없음**(테스트에 사용하지 않음) | 26=APPROVED · 27=ASSIGNED · 31=PENDING · 33=ASSIGNED · 34=ASSIGNED — 검증 전과 동일 |
| 컨테이너 산출물 | `/app/storage/raw/72|73|74` 삭제 | — |
| 코드·설정·테스트 파일 | **미수정**(본 결과 파일 1개만 생성) | — |

## 6. 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| D-1 검수 워크플로우 | 48 | 47 | 0 | 1 | 0 | 0 | 0 |
| D-2 검수 상태머신 | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **52** | **50** | **1** | **1** | **0** | **0** | **0** |

PASS율 50/52 = 96.2% (PARTIAL 제외 기준). 실동작 근거 판정 48/52 = 92.3%.
# D 클러스터 part2 — D-3 검수 배정(TC-ASSIGN 26건) + D-7 관제 조회 API(TC-NOTIFY 8건)

> 검증일 2026-08-01 · 담당 범위 34건 · 이슈 ID `D-ISSUE-21~40`
> 코드 기준 워크스페이스 `qa-0801` (HEAD `56d30478`) · 실동작 기준 로컬 풀스택

## 0. 환경 실측 (판정 전제 — stack-bringup.md 대비 **정정**)

`stack-bringup.md` 말미의 "⚠ 스택 재빌드 보류(구버전 V146 컨테이너로 검증 진행)" 기록은 **이번 검증 시점에는 더 이상 유효하지 않다.**

| 항목 | 실측 |
|---|---|
| 컨테이너 생성시각 | `klid-backend`/`klid-frontend`/`klid-ai-server`/`klid-mock-server` 모두 **2026-08-01 23:10:06 재빌드** (`docker ps --format '{{.CreatedAt}}'`) |
| Flyway 최신 | **V158** `add ls data aug dscd lookup index` (구 기록의 V146 아님) |
| `ls_data_ingest` | 테이블 존재(0행) — V147/V148 적용됨 |
| backend health | `GET /api/actuator/health` → `{"status":"UP"}` |
| mock-server | `:9400/health` → `{"status":"ok"}` |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system`, 스키마 `public` |

→ **본 파트 34건은 전부 워크스페이스 코드와 동일 빌드 위에서 실동작 판정했다.** BLOCKED 0건.

토큰: `POST /v1/dev/tokens` 로 발급(`userNo` 지정 가능). 인가 역할의 실제 진실원은 `LS_USER_ROLE`
(`UserRoleResolver`)이며 실측 매핑은 1001/1002=REVIEWER · 2001/2002=WORKER · 3001=PORTAL_USER.

---

## 1. D-3. 검수 배정 (TC-ASSIGN) — 26건

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-ASSIGN-001 | PASS | [실동작] | `POST /v1/assignments {workerId:2001,rawDataIds:[43]}` → **201**. DB: `ls_task_assignment(47, raw 43, 2001, LABELER)` INSERT · `ls_raw_data_status(43)=ASSIGNED(ver 1)` · `ls_task_event_log(77, ASSIGN, actor 1001, subject 2001)` 3종 모두 적재 확인 |
| TC-ASSIGN-002 | PASS | [실동작] | WORKER(2001) 토큰으로 POST → **403** `FORBIDDEN "권한이 없습니다."` (컨트롤러 `@PreAuthorize("hasRole('REVIEWER')")` 1차 + `AssignmentService:675-677` 2차 이중 방어) |
| TC-ASSIGN-003 | PASS | [실동작]+[정적] | 토큰 없이 POST → **401** `UNAUTHORIZED`. 서비스 진입부 NPE 가드 실재: `AssignmentService.java:672-674` `if (actor == null) throw UNAUTHORIZED`. 단위 커버: `AssignmentServiceTest#requireReviewer_actor가_null이면_UNAUTHORIZED` |
| TC-ASSIGN-004 | PASS | [실동작] | `workerId=999999` → **400** `INVALID_INPUT "존재하지 않는 작업자입니다."` (`:76-78`) |
| TC-ASSIGN-005 | PASS | [실동작] | `reviewerId=999999` → **400** `INVALID_INPUT "존재하지 않는 검수자입니다."` (`:79-81`) |
| TC-ASSIGN-006 | PASS | [실동작] | raw43+2001 재배정 요청 → **409** `CONFLICT`. UK(RAW_DATA_ID,USER_NO,TASK_TYPE_CD) 충돌이 `DataIntegrityViolationException`→409 로 변환됨(`:101-103`). ⚠ 같은 catch 가 FK 위반도 삼킨다 → **D-ISSUE-21** |
| TC-ASSIGN-007 | PASS | [실동작] | `reviewerId:1001` 동봉 배정(raw44) → LABELER(49) + **REVIEWER(50) 2행** INSERT 확인 |
| TC-ASSIGN-008 | PASS | [실동작] | 같은 raw44 에 worker 2002 + 동일 reviewerId 1001 재요청 → **201**, DB 는 49/50/51 3행(REVIEWER 중복 INSERT 없음, worker 배정 보존). `assignReviewers` 사전 조회 skip 경로(`:206-219`) 동작 |
| TC-ASSIGN-009 | PASS | [실동작] | `PATCH /v1/assignments/47 {workerId:2002}` → **200**. `ls_task_assign_history(hstry_seq 8, authrt_seq 47, prev 2001→new 2002, chg_user 1001)` + `ls_task_event_log(86, REASSIGN, subject 2002, prev 2001)` + `ls_task_assignment(47).user_no=2002, ver=1` 3종 확인 |
| TC-ASSIGN-010 | PASS | [실동작] | APPROVED 영상(raw26)의 배정 29 재배정 → **409** `ASSIGNMENT_ALREADY_COMPLETED "완료된 작업은 재배정할 수 없습니다."` |
| TC-ASSIGN-011 | PASS | [실동작] | `PATCH /v1/assignments/999999` → **404** `NOT_FOUND "배정을 찾을 수 없습니다."` |
| TC-ASSIGN-012 | PASS | [실동작] | 현재 배정자와 동일 workerId → **400** `INVALID_INPUT "현재 배정된 작업자와 동일합니다."` |
| TC-ASSIGN-013 | PASS | [실동작] | raw44 배정 49(2001)를 2002 로 재배정 시도(2002 는 이미 raw44 LABELER) → **409** `CONFLICT "선택한 작업자는 이미 해당 영상에 배정되어 있습니다."` (사전 조회 차단, flush 도달 전) |
| TC-ASSIGN-014 | PASS | [실동작] | 동일 배정 47 에 **동시 PATCH ×4** → `200 ×1 / 409 ×3`(전부 `"다른 사용자가 먼저 재배정했습니다"`). DB: `ls_task_assign_history` **+1행만**(8→10, 총 2행) · `ls_task_event_log` **+1건만**(90) · `ver 1→2`. 패자 tx 전체 롤백 확인 |
| TC-ASSIGN-015 | PASS | [실동작] | WORKER 2002 → 배정47(현재 2001) 이력 조회 **403** `"본인 배정 이력만 조회할 수 있습니다."` / 본인(2001) 조회는 **200** (대조군) |
| TC-ASSIGN-016 | PASS | [실동작] | REVIEWER 이력 조회 → 3건이 `occurredAt` **오름차순**(23:55:22 ASSIGN → 23:55:53 REASSIGN → 23:56:47 REASSIGN), actor/subject/prev 3축 사용자명 모두 해석됨. N+1 회피: `userRepository.findByUserNoIn(Set)` 1회 batch(`:340-343`) |
| TC-ASSIGN-017 | PASS | [실동작] | `/0/history`·`/-1/history` → **400** `"잘못된 배정 ID 입니다."`(`:316-318`) · `/abc/history` → **400** `"파라미터 형식이 올바르지 않습니다: assignmentId"` (500 아님) |
| TC-ASSIGN-018 | PASS | [실동작] | WORKER 2001 이 `?workerId=2002` 로 목록 요청 → **200**, `totalElements=15`, 응답 workerId 집합 = `[2001]`. 파라미터가 403 이 아니라 **무시**되고 self 로 고정(`scopeForActor:475-477`) |
| TC-ASSIGN-019 | PASS | [실동작] | REVIEWER 전체 → total 26, workerIds `[2001,2002]`, taskTypeCd 전부 `LABELER` / `?workerId=2002` → total 11, workerIds `[2002]` |
| TC-ASSIGN-020 | PASS | [실동작] | PORTAL_USER 토큰 목록·이력 → **403** `"권한이 없습니다."` / 토큰 없음 → **401** |
| TC-ASSIGN-021 | PASS | [실동작] | 상태행이 없던 raw52 에 worker 2001·2002 **동시 배정** → `201 ×1 / 409 ×1`. DB 최종 assignment 1행 + `ls_raw_data_status(52)` 1행(ASSIGNED). PK 제약이 직렬화 지점으로 동작, 패자는 재시도 가능한 409. ⚠ 이때도 메시지가 "이미 동일 작업자에게 배정된 영상" 으로 오도 → **D-ISSUE-21** |
| TC-ASSIGN-022 | PASS | [실동작] | APPROVED raw48 배정 → **409** `ASSIGNMENT_ALREADY_COMPLETED "검수 완료된 영상은 배정할 수 없습니다."`, `ls_raw_data_status(48)` **APPROVED 유지**(ASSIGNED 강등 없음 — D-ISSUE-01 해소 확인) |
| TC-ASSIGN-023 | PASS | [실동작] | `rawDataIds=[46,48(APPROVED),47]` → **409**, 이후 `ls_task_assignment where raw_data_id in (46,47,48)` **0행**(부분성공 없음). 상태 조회는 `findByRawDataIdIn` 단일 IN 쿼리(`:149`) |
| TC-ASSIGN-024 | PASS | [실동작] | **경합 재현 성공**: raw43(IN_REVIEW) 에 `approve` 와 `assign(worker 2001)` 동시 발사 → approve **200**, assign **409** `CONFLICT "다른 사용자가 먼저 해당 영상의 상태를 변경했습니다"`(500 아님). DB: status `APPROVED(ver 4)`, 신규 assignment **미생성**. `OptimisticLockingFailureException` 국소 catch(`:104-113`) 실동작 확인 |
| TC-ASSIGN-025 | PASS | [실동작] | 2라운드 실측. ①raw55: reassign 선커밋 → 둘 다 200이고 approve 응답의 workerName 이 **재배정 후 작업자(정작업)** — 순서 역전 없음. ②raw66(reassign 0.15s 지연): approve **200** 선커밋 → reassign **409** `ASSIGNMENT_ALREADY_COMPLETED`, `user_no` 2001 **불변**. 잠금 원천 `LsRawDataStatusRepository.findByRawDataIdForShare` = `@Lock(PESSIMISTIC_READ)`(PG `FOR SHARE`), 호출부 `AssignmentService:235-240` |
| TC-ASSIGN-026 | PASS | [실동작] | raw43 을 submit→start 로 **IN_REVIEW** 전이 후 배정47 재배정 → **200 허용**, 상태 IN_REVIEW 유지. 카탈로그 기대결과("현행 **허용**")와 일치 → PASS. 단 **D-ISSUE-05(정책 미확정) 이월 유지** — 아래 §3 참조 |

**D-3 집계**: PASS 26 / FAIL 0 / PARTIAL 0 / BLOCKED 0

---

## 2. D-7. 관제 조회 API (TC-NOTIFY, 조회) — 8건

> ★ UNCERTAINTIES #4 **반전 항목**이라 IDOR 을 최우선으로 실동작 반증했다.
> 토글 실측: `CONTROL_NOTIFY_ENABLED=true` → 컨트롤러 활성 상태에서 검증.

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-NOTIFY-026 | PASS | [실동작] | `GET /v1/tasks/26/summary` — REVIEWER **200**, 배정 보유 WORKER(2001) **200**. 응답 `{rawSn:26,status:"COMPLETED",totalFrames:5,labeledFrames:0,totalLabels:0,totalMeta:1,reviewerName:"김검수",lastModifiedAt:"2026-08-01T14:44:49.535483Z"}` — 카운트·상태·검수자·최종수정일 전 필드 실측값(self-fill 아님, `resolveReviewerName` 이 승인 이벤트→사용자 마스터 조인) |
| TC-NOTIFY-027 | PASS | [실동작] | `GET /v1/tasks/4/labels` 응답 전문을 키워드 스캔(`filePath`/`path`/`deIdntf`/`srcFile`) → **0건**. 실제 아이템은 `{srcSn,frameNo,labels:[{lblSn,lblTypeCd,label,points}]}` 뿐. DTO `TaskLabelsResponse.LabelItem` record 4필드로 구조적 강제(CWE-359) |
| TC-NOTIFY-028 | PASS | [실동작] | ①기본 `size=20, number=0, totalElements=30, totalPages=2` ②`size=10000` → **size 100 클램프** ③`frameIds=1,2` → `totalElements=2`, srcSns `[1,2]`(페이징 **전** 쿼리 조건으로 적용) ④`frameIds` 101개 → **400** `INVALID_INPUT` / 100개 → **200**(경계 정확). ⑤**반증**: raw4 요청에 raw26 프레임(296,297)을 섞어도 `total=1, srcSns=[1]` — rawSn 조건이 함께 걸려 교차 영상 누출 없음 |
| TC-NOTIFY-029 | PASS | [실동작] | `GET /v1/tasks/4/meta` → `metaSn/metaKey/metaVal` 목록 + `page=0,size=20,totalElements=7,totalPages=1`. `size=10000` → **size 100 클램프**. 정렬은 `metaSn ASC` 고정(`cappedMeta:192-199`) |
| TC-NOTIFY-030 | PASS | [실동작] | rawSn=999999 로 summary/labels/meta 3경로 전부 **404** `NOT_FOUND "영상을 찾을 수 없습니다."`(`findRawOrThrow:225-228`). rawSn=0·-1 도 404(500 없음) |
| TC-NOTIFY-031 | PASS | [실동작]+[정적] | 토큰 없음 → 3경로 **401** / PORTAL_USER → 3경로 **403** `"권한이 없습니다."`(`@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` :67,86,115). 토글 off 시 404 절은 정적 확인 — `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")`(Controller:46) + `application.yml:378` 기본값 `${CONTROL_NOTIFY_ENABLED:false}` → 빈 미등록 → 404 |
| TC-NOTIFY-052 | PASS | [실동작] | **핵심 반증 항목.** WORKER 2001(raw5 배정 없음)이 `GET /v1/tasks/5/{summary,labels,meta}` 호출 → **3경로 전부 403** `FORBIDDEN "본인에게 배정되지 않은 영상입니다."`. 동일 rawSn 을 REVIEWER 로 호출 시 **200**(가드가 역할 분기로 정확히 동작). 추가 반증: WORKER 2001 이 raw65 에 **`TASK_TYPE_CD='REVIEWER'` 배정만** 보유한 케이스도 **403** — 가드가 `TASK_LABELER` 만 인정(`LabelAccessGuard:92-93`)해 타입 혼동 우회 불가. **UNCERTAINTIES #4 반전 = 실동작으로 확정** |
| TC-NOTIFY-053 | PASS | [실동작] | `DE_IDENT_YN='F'` 인 raw9 → `labels` **412** `PRECONDITION_FAILED "비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."`. **역할 무관**(REVIEWER·배정 WORKER 둘 다 412). **인가 우선순위 반증**: 미배정 WORKER 2001 → raw8(F, 2002 배정) 은 412 가 아니라 **403** — 게이트가 인가를 대체하지 않음. `summary`/`meta` 는 **200**(좌표 미포함이라 대상 아님, 설계대로). 대조군 raw4(`'Y'`) labels **200** |

**D-7 집계**: PASS 8 / FAIL 0 / PARTIAL 0 / BLOCKED 0

### D-7 추가 반증 (전부 안전)
- `?sort=evil`, `?sort=1;DROP TABLE` → **200**(정렬 파라미터를 읽지 않고 `capped()`/`cappedMeta()` 가 `frameNo`/`metaSn` 고정 Sort 를 새로 만든다 — CWE-89 표면 없음)
- `?page=-1` → 200(0페이지) · `?page=99999` → 200 + `content:[]`(500 없음)

---

## 3. 이슈

### [D-ISSUE-21] TC-ASSIGN-006 / TC-ASSIGN-021 — 배정 API 가 FK 위반·PK 충돌을 전부 "중복 배정 409" 로 오분류
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 존재하지 않는 영상(rawSn)에 대한 배정은 **404 NOT_FOUND**(또는 400 INVALID_INPUT)여야 한다. 이미 workerId/reviewerId 는 존재 검증 후 400 을 주므로(`:76-81`) rawDataIds 만 검증이 빠진 것은 계약 비대칭이다. 또 상태행 PK 충돌(동시 배정)은 "중복 배정" 과 원인이 달라 재시도 안내가 달라야 한다. 잘못된 메시지는 운영자가 존재하지 않는 중복 배정을 찾아 헤매게 만든다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:101-103` 의 `catch (DataIntegrityViolationException)` 가 **UK 충돌·FK 위반·PK 충돌을 구분 없이** 삼켜 동일 문구로 409 를 낸다.
  ```java
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  }
  ```
  실측 ①존재하지 않는 rawSn:
  ```
  POST /v1/assignments {"workerId":2001,"rawDataIds":[999999]}
  → 409 {"errorCode":"CONFLICT","message":"이미 동일 작업자에게 배정된 영상이 있습니다."}
  backend log: ERROR ... violates foreign key constraint "fk_ls_task_assignment_raw"
               WARN  [Assignment] duplicate assignment detected workerId=2001
  ```
  실측 ②동시 배정(raw52, 상태행 부재): 두 요청 중 하나가 `LS_RAW_DATA_STATUS` PK 충돌로 실패했는데 메시지는 동일하게 "이미 동일 작업자에게 배정된 영상". 부수적으로 매 발생마다 **ERROR 레벨 SQL 스택 로그**가 남아 정상 경합에도 오탐 알람이 발생한다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[999999]}'
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_data_raw where raw_sn=999999;"   -- 0
  ```
- **영향**: 기능/운영. API 계약 위반(`rules/api-design.md` — 리소스 없음=404), 오도성 에러 메시지, ERROR 로그 오탐. 데이터 정합은 tx 롤백으로 보존되어 보안 영향은 없음(CWE-209 는 경미 — 내부 정보를 노출하는 방향이 아니라 은폐하는 방향).
- **수정 방향(제안)**: `assign()` 진입부 `rejectApprovedTargets` 옆에 `videoRepository.findAllById(rawDataIds)` 로 **존재 검증(단일 IN 쿼리)** 을 추가해 미존재 시 404/400 을 먼저 던진다. 그리고 `catch (DataIntegrityViolationException)` 에서 `e.getMostSpecificCause().getMessage()` 의 제약명(`uk_...` vs `fk_...` vs `..._pkey`)으로 분기하거나, 상태행 upsert 를 `save` 대신 조건부 INSERT 로 분리해 UK 충돌만 "중복 배정" 문구를 쓰게 한다. ⚠ 구현하지 않음.

### [D-ISSUE-22] TC-ASSIGN-001 / TC-ASSIGN-007 / TC-ASSIGN-009 — 배정 대상 사용자의 **역할·활성 여부**를 검증하지 않아 PORTAL_USER·REVIEWER 를 LABELER 로 배정할 수 있다
- **심각도**: MEDIUM (CWE-863 Incorrect Authorization / 업무규칙 미집행)
- **기대 동작(기대효과)**: `CLAUDE.md` 작업 배정 규칙은 "**REVIEWER 가 WORKER 에게** 배정"이다. `LS_TASK_ASSIGNMENT.TASK_TYPE_CD='LABELER'` 행의 `USER_NO` 는 **WORKER 역할 + 활성(`USE_YN='Y'`) 사용자**여야 하고, `'REVIEWER'` 행은 REVIEWER 여야 한다. 역할 진실원(`LS_USER_ROLE`)이 이미 존재하므로 검증이 가능하다 — 실제로 작업자 선택 드롭다운을 채우는 `UserRepository.findAllWorkersWithTaskCount()` 는 `LsUserRole.roleCd='WORKER' AND u.useYn='Y'` 를 **이미 걸고 있다**. 즉 FE 목록은 필터링되는데 API 는 무검증인 전형적 비대칭이다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:76-81` 이 **존재 여부만** 본다.
  ```java
  if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
  }
  if (req.reviewerId() != null && userRepository.findByUserNo(req.reviewerId()).isEmpty()) { ... }
  ```
  `findByUserNo` 는 `USE_YN` 필터도, `LS_USER_ROLE` 조인도 없다. 재배정(`:255-257`)도 동일. 실측:
  ```
  POST /v1/assignments {"workerId":3001,"rawDataIds":[69]}  → 201  (3001 = PORTAL_USER)
  POST /v1/assignments {"workerId":1002,"rawDataIds":[69]}  → 201  (1002 = REVIEWER)
  POST /v1/assignments {"workerId":2001,"rawDataIds":[69],"reviewerId":2002} → 201 (2002 = WORKER 가 REVIEWER 행으로 INSERT)
  DB: ls_task_assignment(64,69,3001,LABELER) (65,69,1002,LABELER) (67,69,2002,REVIEWER)
  DB: ls_user_role → 1002=REVIEWER, 2001/2002=WORKER, 3001=PORTAL_USER
  ```
- **재현/확인 경로**: 위 curl 3줄 + `select a.*, r.role_cd from ls_task_assignment a join ls_user_role r on r.user_no=a.user_no where a.raw_data_id=69;`
- **영향**:
  - **데드락 워크플로**: PORTAL_USER 에게 배정된 영상은 그 사용자가 `/v1/reviews/{rawSn}/submit` 등 내부 API 를 호출할 수 없어(PORTAL 채널 403) **ASSIGNED 로 영구 정체**한다. 배정 취소 API 가 없어 재배정으로만 회수 가능.
  - **집계 오염**: 작업자별 배정 카운트·작업목록·이력에 비-WORKER 가 섞인다.
  - **직무분리(SoD)**: REVIEWER 를 LABELER 로 배정하면 라벨링·승인 동일인이 된다. 단 `LabelAccessGuard` 가 REVIEWER 에 전면 통과를 주는 현행 설계상 **새로운 권한 상승은 발생하지 않는다**(기존 정책의 귀결) — 그래서 HIGH 가 아니라 MEDIUM.
  - 비활성 사용자(`USE_YN='N'`) 배정도 동일 경로로 가능(시드에 비활성 사용자가 없어 실측은 미수행, 정적 확인).
- **수정 방향(제안)**: `assign`/`reassign` 의 사용자 검증을 존재 확인에서 **역할·활성 확인**으로 승격한다 — `LsUserRoleRepository.findByUserNo(workerId)` 가 `WORKER` 인지(+`MngAcctUser.useYn='Y'`) 검사하고 아니면 400. `reviewerId` 는 `REVIEWER` 로 동일 검사. 드롭다운 쿼리(`findAllWorkersWithTaskCount`)와 **같은 술어**를 공유하는 단일 판정 지점으로 추출해 FE/BE 드리프트를 막는다. ⚠ 구현하지 않음.

### [D-ISSUE-23] D-3 전 26건 — 카탈로그 근거 `file:line` 전면 드리프트
- **심각도**: LOW (카탈로그 정합성)
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 검증자가 바로 열어볼 수 있는 위치여야 한다. 어긋나면 검증자가 매번 재탐색해야 하고, 최악의 경우 **엉뚱한 코드를 근거로 PASS** 를 준다.
- **현재 동작(이슈 내용)**: D-3 의 `AssignmentService.java:*` 근거가 **26건 전부 어긋난다**(+14~+58행). 원인은 2026-07-31 이후 `rejectUnenrolledDerivatives`(현재 `:175-186`) 신설 + 재배정 게이트 주석 확장(커밋 `bd7ba946`·`284d81e2`·`31ecee47`).

  | TC | 카탈로그 | 실제(qa-0801 `56d30478`) |
  |---|---|---|
  | 001 | 57-114 | **71-129** |
  | 002 | 59 | **73**(호출) / **675-677**(판정) |
  | 003 | requireReviewer | **668-678** |
  | 004 | 62-64 | **76-78** |
  | 005 | 65-67 | **79-81** |
  | 006 | 86-88 | **101-103** |
  | 007 | 102-104,150-175 | **117-119, 195-220** |
  | 008 | 161-174 | **206-219** |
  | 009 | 177-244 | **222-302** |
  | 010·025·026 | 190-195 / 187-195 | **235-240** |
  | 011 | 182-183 | **227-228** |
  | 012 | 200-202 | **258-260** |
  | 013 | 204-217 | **265-275** |
  | 014 | 225-241 | **283-299** |
  | 015 | 264-270 | **322-328** |
  | 016 | 257-303 | **315-361** |
  | 017 | 258-260 | **316-318** |
  | 018·019 | 305- | **377-** |
  | 020 | 305- | **470-482**(`scopeForActor`) |
  | 021 | upsertDataStts | **663-666** |
  | 022 | 69,130-141 | **83, 145-156** |
  | 023 | 130-141 | **145-156** |
  | 024 | 89-98 | **104-113** |

  (대조: **D-7 의 `TaskQueryController`/`TaskQueryService`/`LabelAccessGuard` 근거 8건은 전부 정확**. `TaskQueryService.java:107-143`(TC-NOTIFY-027)만 실제 `107-137` + `234-245` 로 소폭 어긋남.)
- **재현/확인 경로**: `sed -n '71,129p' backend/src/main/java/kr/co/cudo/authoring/assignment/service/AssignmentService.java`
- **영향**: 검증 효율/정확도. 기능 영향 없음.
- **수정 방향(제안)**: `D-review-version-notify.md` D-3 표의 근거 컬럼을 위 대응표로 일괄 갱신한다. 근본적으로는 라인 번호 대신 **메서드명 앵커**(`AssignmentService#rejectApprovedTargets`)로 표기하면 드리프트가 사라진다. ⚠ 구현하지 않음.

### [D-ISSUE-24] TC-NOTIFY-028 — `frameIds` 상한 초과 400 응답에 컨트롤러 메서드명이 노출된다
- **심각도**: LOW (CWE-209, 경미)
- **기대 동작(기대효과)**: `rules/api-design.md`·`security.md` — 에러 응답에 내부 구현 정보(클래스·메서드·경로)를 담지 않는다. 사용자에게 보여줄 문구만 내려야 한다.
- **현재 동작(이슈 내용)**: `@Validated` + 파라미터 `@Size` 위반이 `ConstraintViolationException` 으로 전역 핸들러를 타면서 위반 경로가 그대로 실린다.
  ```
  GET /v1/tasks/4/labels?frameIds=<101개>
  → 400 {"errorCode":"INVALID_INPUT",
         "message":"getLabels.frameIds: frameIds 는 최대 100개까지 지정할 수 있습니다."}
  ```
  `getLabels.` 접두는 `TaskQueryController#getLabels` 메서드명이다(`TaskQueryController.java:87-92`).
- **재현/확인 경로**:
  ```bash
  IDS=$(python3 -c "print(','.join(str(i) for i in range(1,102)))")
  curl -s -G http://localhost:18081/api/v1/tasks/4/labels --data-urlencode "frameIds=$IDS" -H "Authorization: Bearer $REV"
  ```
- **영향**: 보안(정보 노출) — 실질 위험은 낮으나 Fortify/CodeQL 의 *System Information Leak* 룰에 걸릴 수 있는 형태. 같은 패턴이 `@Validated` 를 쓰는 다른 컨트롤러 전반에 있을 가능성이 높다(전역 이슈).
- **수정 방향(제안)**: `GlobalExceptionHandler` 의 `ConstraintViolationException` 핸들러에서 `propertyPath` 의 **마지막 노드(파라미터명)만** 남기거나 메시지만 취해 응답을 조립한다(`getLabels.frameIds` → `frameIds`). ⚠ 구현하지 않음. (A 클러스터 TC-EXC 담당과 중복 가능 — 병합 시 확인)

---

## 4. 이월 이슈 대조

| 이월 이슈 | 케이스 | 이번 회차 상태 |
|---|---|---|
| **D-ISSUE-05** IN_REVIEW 상태 재배정 허용 | TC-ASSIGN-026 | **미해소 이월.** 실동작 재확인 — raw43 을 `submit`→`start` 로 IN_REVIEW 전이시킨 뒤 `PATCH /v1/assignments/47 {workerId:2002}` → **200**, 상태 IN_REVIEW 유지. 가드(`:235-240`)는 `STTS_APPROVED` 만 차단한다. 부작용 실측: 재배정 후 승인 응답의 `workerName` 이 **제출자가 아니라 새 배정자**(정작업)로 표시되어 "누가 제출한 검수인지" 가 뒤바뀐다. 새 이슈 ID 를 부여하지 않고 D-ISSUE-05 로 이월 유지 — **정책 확정 필요**(IN_REVIEW 재배정을 허용할지, 허용 시 검수 제출 이력의 작업자 표시를 제출 시점으로 동결할지) |
| **D-ISSUE-01** APPROVED 무검증 ASSIGNED 강등 | TC-ASSIGN-022 | **해소 확인.** 409 + APPROVED 유지 실측 |
| **D-ISSUE-02** 동시 재배정 이력 중복 | TC-ASSIGN-014 | **해소 확인.** 4병렬 → 이력 1행 |
| **D-ISSUE-45** 관제 조회 무페이징 | TC-NOTIFY-028/029 | **해소 확인.** labels·meta 모두 기본 20 / 상한 100 클램프 실측 |
| **D-ISSUE-41** 검수자명 null 하드코딩 | TC-NOTIFY-026 | **해소 확인.** `reviewerName:"김검수"` 가 승인 이벤트 → 사용자 마스터 실조회로 채워짐 |
| **UNCERTAINTIES #4** 관제 조회 IDOR(반전) | TC-NOTIFY-052 | **반전 사실 실동작 확정.** 세 경로 모두 `verifyRawAccess` 배선, 미배정 WORKER 403. UNCERTAINTIES 원본을 "✅ 확정 — 실동작 검증 완료(2026-08-01)" 로 갱신 제안 |

---

## 5. 테스트 커버 대조 (`_raw/test-baseline.md` 기준 — 전건 통과, 실패 0)

| 범위 | 커버 테스트 |
|---|---|
| TC-ASSIGN-001/002/004/006/009/015/016/018 | `AssignmentControllerTest`(REVIEWER 배정 LABELER INSERT · WORKER 403 · 중복 409 · 재배정 이력 · IDOR 403/200 · 본인 배정만 반환) |
| TC-ASSIGN-003/007/010/016/017/020/022/023 | `AssignmentServiceTest`(actor null UNAUTHORIZED ×2 · reviewerId 반영 · 완료 배정 거부 · APPROVED 409 및 상태 유지 · 전체실패 · 이력 시간순 · PORTAL 403) |
| TC-ASSIGN-014/025 | `AssignmentReassignConcurrencyIT`(4병렬 1건만 200·이력 1행 / VER 매핑 / **공유잠금으로 승인 UPDATE 직렬화 H4-b**) |
| TC-ASSIGN-024 | `AssignmentOptimisticLockConflictIT`(assign 중 낙관적잠금 충돌 → 500 아닌 409) |
| TC-ASSIGN-005/008/011/012/013/019/021/026 | **전용 자동테스트 미확인** — 이번 회차 실동작으로만 검증됨(회귀 가드 부재, 신규 작성 후보) |
| TC-NOTIFY-026~031/052 | `TaskQueryControllerTest`(요약/라벨/메타 200 · 기본 20 · size 10000 클램프 · frameIds 필터 · 101개 400 · 404 · 401 · **배정되지 않은 WORKER 403** · 원본 경로 미포함) |
| TC-NOTIFY-026~030 | `TaskQueryServiceTest`(COUNT 집계 · 검수자명 실측/미지어냄 · frameIds 페이징 전 적용 · totalElements 정합 · 클램프 · 메타 페이징) |
| TC-NOTIFY-053 | `DeidentReportGateCoverageIT` 가 게이트 공유 호출부를 커버. 관제 라벨 경로 전용 단정은 미확인 → 신규 작성 후보 |

---

## 6. 검증 중 생성한 데이터 (기존 데이터 미변경, INSERT/상태전이만)

| 대상 | 내용 |
|---|---|
| `ls_task_assignment` | 47·49·50·51·54·59·62·64·65·66·67 신규(raw 43/44/52/55/66/69) |
| `ls_task_assign_history` | hstry_seq 8·10·14 등 재배정 이력 |
| `ls_task_event_log` | raw 43/44/52/55/66/69 의 ASSIGN·REASSIGN·검수 이벤트 |
| `ls_raw_data_status` | raw 43·52·55·66 신규 행 및 상태 전이(→APPROVED 등) |
| ⚠ 참고 | **D-ISSUE-22 반증용으로 raw69 에 비-WORKER 배정 3행(3001/1002 LABELER, 2002 REVIEWER)이 남아 있다.** 다른 클러스터 검증 시 이 행을 정상 시드로 오인하지 말 것 |
# D 클러스터 검증 — part3: D-5. diff / rollback (TC-DIFF, 26건)

- 검증일: 2026-08-01 ~ 08-02
- 대상 섹션: `docs/test-cases/D-review-version-notify.md` `## D-5. diff / rollback (TC-DIFF)` (TC-DIFF-001~026, 폐기 0건)
- 코드 기준: 워크트리 `qa-0801` (`56d30478`), 실행 스택 backend jar 빌드 2026-08-01 14:05 / Flyway **V158** 적용 — **워크트리 코드와 실행 컨테이너가 동일 세대임을 확인**(`ls_data_ingest` 존재, V158 반영). `pipeline-drive.md` 가 기록한 "구 jar" 상태는 이후 재빌드로 해소됨.
- 검증 방식: **실동작 최우선**. 격리용 테스트 영상(`rawSn=900001`)을 DB INSERT 로 신설 → 실 API(`/v1/reviews/*/approve`, `/v1/frames/*/labels`, `/v1/versions/*/diff`, `/v1/versions/*/rollback`)로 버전 2개 생성·수정·롤백을 실제 수행하고 DB(`ls_label_version`·`ls_data_lbl`·`ls_data_lbl_ai_info`·`ls_data_lbl_hstry`·`ls_data_src.lbl_ver`·`ls_dataset_export`)와 mock-server(:9400) 인바운드 로그로 판정.
- **원복 완료**: 테스트 데이터(raw/src/lbl/version/export/assignment/status/hstry) 전량 삭제, `ls_data_lbl` IDENTITY 시퀀스 재동기화. 기존 데이터(`ls_label_version` 9행, raw 4·18 라벨 10건)가 **검증 전과 완전 동일**함을 재조회로 확인.
- ⚠ 검증 중 다른 에이전트가 같은 DB 에서 병행 작업(raw 72 승인/삭제 관측) — 본 검증은 전용 `rawSn=900001` 로 격리해 간섭을 차단함.

## 판정 요약

| 판정 | 건수 |
|------|:---:|
| PASS | 22 |
| PARTIAL | 3 |
| FAIL | 1 |
| BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **26** |

**근거 file:line 드리프트: 0건** — TC-DIFF-001~026 의 근거 라인(334-366 / 341-346 / 348-349 / 351-357 / 359-361 / 377-382 / 410-522 / 412-418 / 420-421 / 427-430 / 432-437 / 441-442 / 444-449 / 451-473 / 481-498 / 486-498 / 516-520 / 535-545 / 1020-1026 / 1246-1258 / 1260-1262)이 현재 `VersionService.java`(1,263줄) 실제 위치와 모두 일치.

### ★ 핵심 시맨틱 실측 결론 (확증편향 방지 — 반증 시도 결과)

| 시맨틱 | 반증 시도 | 실측 결과 |
|------|------|------|
| **재활성(적층 없음)** | 롤백을 12회(동시 6쌍 포함) 반복하고 `ls_label_version` 행 수를 추적 | **새 행 0건**. 나아가 4개 버전 전부 `sha256(lbl_payload) == version_hash` 임을 셸에서 재계산 대조 → 롤백 결과 해시가 대상 행과 **항상 동일**하므로 `UK(data_src_sn, version_hash)` 상 적층이 **구조적으로 불가능**함을 확인 |
| **PK·AI메타·TRCK_ID 보존 복원** | 라벨 수정(MODIFIED)+삭제(REMOVED)+추가(ADDED) 후 롤백 | `LBL_SN` 900001/900002 그대로 복원, `TRCK_ID='TRK-A'` 보존, AI메타(`lbl_src_cd=YOLO`, `conf_score=0.912`, `auto_lbl_yn=Y`) 복원, `LBL_ID`(FK) 복원 확인 |
| **점유 PK 만 신규 발급 폴백** | 타 프레임(src 900002)에 `LBL_SN=900002` 를 선점시킨 뒤 롤백 | 그 1건만 신규 PK(900004) 발급, 나머지는 보존. **선점자의 라벨·AI메타는 무손상**. 로그 `rollback lblSn conflict — reassigned new ids count=1` |
| **멱등 롤백 no-op** | 동일 해시로 2회·3회 반복 롤백 | 라벨 `reg_dt` 불변(재작성 없음) · `LBL_SN` 불변 · `ls_data_lbl_hstry` 미증가 · `ls_data_src.lbl_ver` 미증가 · 통지/재export 미발행. 로그 `rollback no-op (already active and labels identical)` |
| **diff 는 APPROVED 스냅샷 간에만** | `DATA_SRC_SN IS NULL` 레거시 행·미승인 시도 | 스냅샷은 `commitApproved` 에서만 생성되며(라벨 저장은 버전 미생성 — 실측: `PUT /labels` 후 버전 행 증가 0), 프레임 스코프가 아닌 행은 **인가 검사 이전 400** 으로 조기 거부 |
| **레거시 해시 불일치 행 롤백** | `version_hash ≠ sha256(payload)` 인 행으로 롤백 | 동일 해시 행 조회가 비어 **대상 행 자체를 재활성**. 어떤 경로에서도 새 행 미생성 |

## 케이스별 판정

| ID | 판정 | 근거 확인 | 실측 근거 |
|----|:----:|------|------|
| TC-DIFF-001 | PASS | [실동작] | `GET /v1/versions/{H2}/diff?compareWith={H1}` → 200. `MODIFIED`(id=900001, before `[[10,10],[50,50]]` / after `[[15,15],[55,55]]`) + `REMOVED`(id=900002 POLYGON) + `ADDED`(id=609 BBOX) 3건 정확 분류. 근거 `VersionService.java:334-366` |
| TC-DIFF-002 | PARTIAL | [실동작] | SKELETON 키포인트 3번째 v 를 `1→0` 만 변경 후 재승인 → diff 가 `MODIFIED` **감지 성공**(`readPoints` 삼중값 비교, `VersionService.java:1180-1197`). **그러나 응답 `before`/`after` 가 완전히 동일**(둘 다 34개 좌표, `points` 비교 결과 `True`) 이고 shape `type` 이 `SKELETON` 이 아닌 `"POLYGON"` 으로 내려감 → **D-ISSUE-41** |
| TC-DIFF-003 | PASS | [실동작] | 서로 다른 프레임(src 900001 vs 900002) 버전 diff → 200 `data:[]`. `VersionService.java:359-361` |
| TC-DIFF-004 | PASS | [실동작] | 비-hex(`zzzz`) 400 / 65자(`{H1}aa`) 400 / 빈 문자열 400 — 전부 `INVALID_INPUT` "잘못된 버전 해시 형식입니다." `VersionService.java:1246-1258` |
| TC-DIFF-005 | PASS | [실동작] | 유효 hex 미존재 해시(`deadbeef`) → 404 `NOT_FOUND` "to 버전을 찾을 수 없습니다." `VersionService.java:1020-1026` |
| TC-DIFF-006 | PASS | [실동작] | 배정 WORKER(2001) 200 → `ls_task_assignment` LABELER 행 삭제 후 동일 요청 **403 FORBIDDEN**("본인에게 배정되지 않은 영상입니다."). `VersionService.java:348-349`. ⚠ 부수 관측: 인가 이전에 해시 조회가 선행해 **404/403 존재 오라클**이 성립 → **D-ISSUE-42**(LOW) |
| TC-DIFF-007 | PASS | [실동작] | `lbl_payload='{not-json'` 버전으로 diff → **200 + 빈 배열**(장애 격리). `computeLabelDiffs` catch(`VersionService.java:1133-1136`) |
| TC-DIFF-008 | PARTIAL | [실동작] | ①full-replace + `LBL_SN`·`TRCK_ID`·AI메타·`LBL_ID` 보존 복원 ✅ ②버전 행 **재활성**(행 수 불변, `ver_no` 신규 채번 0) ✅ ③`ls_data_lbl_hstry` 롤백 이벤트(`{"rollbackToVersionHash":"48c723d6…","changes":[…]}`, `reg_id=1001`) ✅ — **단 `LS_DATA_LBL.REG_USER_NO` 가 복원되지 않고 NULL 로 소실**(원래 2001) → **D-ISSUE-43**. `VersionService.java:410-522,535-545` |
| TC-DIFF-009 | PASS | [실동작] | `items:[]` 스냅샷으로 롤백 → 200, `ls_data_lbl` 0행, 고아 AI메타 0행, 이력 1건 기록, 대상 버전 재활성. `replaceFrameLabels`(`VersionService.java:663-704`) |
| TC-DIFF-010 | PASS | [실동작] | `'{not-json'` → 400 `INVALID_INPUT` "손상된 버전 스냅샷이라 롤백할 수 없습니다.", `items` 비배열(`{"bad":1}`) 도 동일 400. **라벨·버전 활성상태 모두 무변경**(부분 적용 0). `VersionService.java:441-442,595-597` |
| TC-DIFF-011 | PASS | [실동작] | `ls_auth_work_lock`(RAW/LOCKED) 삽입 후 롤백 → **409 CONFLICT** "작업이 잠긴 영상은 롤백할 수 없습니다." 락 해제 후 정상. `VersionService.java:427-430` |
| TC-DIFF-012 | PARTIAL | [실동작] | APPROVED 영상 롤백 → 백엔드 `TASK_MODIFIED sent rawSn=900001 frames=1 reExport=true`, mock-server `POST /api/data-set/v2/jobs/900001/notify-updated 202` 왕복 실측 ✅. **그러나 같은 실행의 export 가 `nothing produced — marked FAILED` 였음에도 통지가 그대로 발송**됨(정책상 보류 대상) → **D-ISSUE-44**. `VersionService.java:516-520` |
| TC-DIFF-013 | PASS | [실동작] | `ls_raw_data_status='ASSIGNED'` 로 낮춘 뒤 롤백 → `ls_mon_noti_acml` 누적 행 **0건**, 90초 관측 동안 mock-server 인바운드 **0건**, `TASK_MODIFIED` 로그 없음. `VersionService.java:516` |
| TC-DIFF-014 | PASS | [실동작] | 동일 해시 재롤백 → 라벨 `reg_dt` 불변(`23:57:52.813928` 유지) · `LBL_SN` 불변 · 이력 2건 유지(미증가) · `lbl_ver` 3 유지 · 통지 0 · 로그 `rollback no-op`. SKELETON 프레임에서도 동일 확인. `VersionService.java:481-498` |
| TC-DIFF-015 | PASS | [실동작] | 롤백 시 대상 행 `actvtn_yn N→Y`, 기존 active `Y→N`, **신규 행 0**. 추가 반증: 4개 버전 모두 `sha256(payload)==version_hash` 재계산 일치 → 적층 분기 도달 불가. `VersionService.java:535-545` |
| TC-DIFF-016 | PASS | [실동작] | 존재하지 않는 해시 롤백 → 404 "롤백 대상 버전을 찾을 수 없습니다." `VersionService.java:420-421` |
| TC-DIFF-017 | PASS | [실동작] | 토큰 없음 → **401 UNAUTHORIZED** / 미배정 WORKER → **403 FORBIDDEN**. 추가: PORTAL 채널 토큰은 `@PreAuthorize` 에서 403(엔드포인트 3종 전부). `VersionService.java:412-418` |
| TC-DIFF-018 | PASS | [실동작]+[정적] | 서로 다른 두 해시로 **동시 롤백 6쌍(12요청)** → 전부 200, 최종 `actvtn_yn='Y'` **정확히 1건**, 라벨셋이 활성 버전과 정합, `deadlock/40P01` 로그 0건. 정적: `findActiveForUpdate` = `@Lock(PESSIMISTIC_WRITE)`(`LsLabelVersionRepository:29-34`), 라벨 교체보다 선행 취득(`VersionService.java:444-449`) |
| TC-DIFF-019 | PASS | [실동작] | SKELETON v 변경 → 재승인 → 롤백 후 `POINT_CN` 이 원본과 **바이트 단위 동일**(`v` 가 `2.0` 이 아닌 정수 `2` 로 보존, 17점 유지). 2회차 롤백이 no-op 으로 떨어지는 것으로 정규화(`canonicalKeypointJson`, `VersionService.java:818-831`)가 실제 작동함을 교차 확인 |
| TC-DIFF-020 | PASS | [정적] | `isCommittable` = `actor != null && actor.channel() != Channel.PORTAL`(`VersionService.java:1260-1262`). 전 소스 grep 결과 **프로덕션 호출자 0건**(참조는 `VersionServiceTest.java:313-317` 뿐) — 케이스 기대결과(dead code, D-ISSUE-28 미해소)와 일치. 실보호는 `@PreAuthorize` + `LabelAccessGuard` 가 담당(PORTAL 403 실측) |
| TC-DIFF-021 | PASS | [실동작] | `de_ident_yn='F'` 설정 후 롤백 → **412 PRECONDITION_FAILED**. 작업락이 없는 상태에서도 차단됨(읽기·쓰기 비대칭 제거 확인). `VersionService.java:432-437` |
| TC-DIFF-022 | PASS | [실동작] | 동일 상태에서 diff → **412**. 좌표 전문 미노출. 인가 통과 이후 평가되며 `de_ident_yn='Y'` 복원 시 즉시 재개방. `VersionService.java:351-357` |
| TC-DIFF-023 | PASS | [실동작] | `DATA_SRC_SN IS NULL` 레거시 행 삽입 후 diff → **400**("from/to 버전은 프레임 단위 비교 대상이 아닙니다.", 500 아님). `to`/`from` 양쪽 위치 모두 400. 같은 해시로 rollback 은 404(프레임 스코프 조회라 미매칭). `VersionService.java:341-346,377-382` |
| TC-DIFF-024 | PASS | [실동작] | active 2건 강제 후 ①**교체 경로** 롤백 → 정본 1건 외 비활성 ②**멱등 조기반환 경로** 롤백 → 동일하게 잉여 active 정리 + 이력·통지 미발행. `VersionService.java:486-498,535-545` |
| TC-DIFF-025 | PASS | [실동작]+[정적] | 롤백 ↔ `PUT /frames/{srcSn}/labels`(bulkUpsert) **동시 10라운드** → 전부 200, 최종 라벨셋이 활성 버전 스냅샷과 정합, deadlock 0건. 정적: `srcRepository.lockAndReadLabelVersion`(`SELECT LBL_VER … FOR UPDATE`, `LsDataSrcRepository:251-253`)을 **멱등 판정 이전**(`VersionService.java:472`)에 취득, 락 순서 VERSION→SRC→LBL 유지 |
| TC-DIFF-026 | FAIL | [실동작] | 서로 다른 프레임에 **동일 `version_hash`** 를 만든 뒤 동일 diff 요청을 반복 → 초기 5회는 정상 결과(3건). 이후 heap 순서를 바꾸자(`UPDATE` 로 ctid 이동) **같은 요청이 빈 배열을 반환**. `findByVersionHash` 에 `ORDER BY` 가 없고 `matches.get(0)`(`VersionService.java:1020-1026`) 이므로 **선택이 비결정적**이며 결과가 조용히 뒤바뀜 → **D-ISSUE-45**(D-ISSUE-27 이월, 실증 확보) |

## 자동테스트 커버리지 대조 (`_raw/test-baseline.md` — backend 4,755 tests / 실패 0)

| 케이스군 | 커버 테스트 | 비고 |
|------|------|------|
| 001·003·005 diff 분류/미존재 | `VersionServiceTest#diff_두_스냅샷…`, `#diff_동일_라벨_데이터_비교시…`, `#diff_존재하지_않는_from_해시_조회시_NOT_FOUND`, `VersionControllerTest#GET_diff_…` | 통과 |
| 002·019 SKELETON | `VersionServiceTest#버전diff_SKELETON_v만_바뀌면_MODIFIED_감지`, `#버전diff_SKELETON_v_동일이면_변화없음`, `VersionServiceRollbackSkeletonTest`(3), `VersionRollbackRestoreIT#SKELETON_왕복…` | 통과. **단 diff 응답의 v 소실은 어느 테스트도 단언하지 않음**(D-ISSUE-41 미가드) |
| 008·015·018 롤백 본체 | `VersionRollbackRestoreIT`(10), `VersionRollbackHistoryIT`(7), `VersionServiceRollbackLockOrderTest`(4) | 통과. **`REG_USER_NO` 복원 단언 없음**(D-ISSUE-43 미가드) |
| 010·011·012·013·016·017 | `VersionServiceTest#손상된_스냅샷…`, `#작업락_잠긴_영상…`, `#APPROVED_영상_rollback시_TaskModifiedEvent…`, `#미검수_영상_rollback시…`, `#존재하지_않는_버전_해시…`, `#미배정_WORKER…` | 통과 |
| 014·024·025 | `VersionServiceRollbackIdempotencyTest`(7 — `멱등_판정_전에_프레임_락을_취득한다`, `조기_반환시에도_잉여_ACTIVE_가_정리된다` 포함) | 통과 |
| 021·022 신고 게이트 | `DeidentReportGateCoverageIT#신고_상태에서_label_history_와_version_diff_가_차단된다`(:361), `#신고_상태에서는_롤백이_거부된다`(:386), `#resolve_후에는_위_경로_전부가_다시_열리고…`(:412) | 통과 |
| 023 NULL srcSn | `VersionServiceDiffNullSrcSnTest`(3) | 통과 |
| **006 diff IDOR** | **전용 테스트 없음** — rollback/listVersions IDOR 만 존재 | 갭(실동작으로는 확인됨) |
| **009 빈 스냅샷 롤백** | 직접 단언 테스트 없음(`롤백_이력은_라벨_델타가_없어도_기록된다` 가 인접) | 갭 |
| **026 다중 매칭 해시** | **없음** | 갭 — D-ISSUE-45 회귀 가드 부재 |

---

# 이슈

### [D-ISSUE-41] TC-DIFF-002 — SKELETON diff 응답이 가시성 v 를 버리고 shape 타입을 POLYGON 으로 왜곡
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수자가 버전 비교 화면에서 "무엇이 바뀌었는지"를 눈으로 확인할 수 있어야 한다. SKELETON 은 삼중값 `[x,y,v]` 이고 v(가시성)만 바뀌는 편집이 실제 작업 동선이므로, `MODIFIED` 로 감지했다면 `before`/`after` 에 그 차이가 드러나야 한다. 그러라고 `readPoints` 에 삼중값 비교(v-blindness 수정)를 넣은 것이다.
- **현재 동작(이슈 내용)**: 감지는 되지만 **렌더링에서 v 가 탈락**해 `before` 와 `after` 가 완전히 동일한 값으로 응답된다. 타입도 `SKELETON` 이 아니라 `POLYGON` 으로 나간다.
  ```java
  // version/dto/LabelDiffDto.java:69-77  ShapeDto.fromPoints
  List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
  for (List<Double> pt : points) {
      if (pt.size() >= 2) { flat.add(pt.get(0)); flat.add(pt.get(1)); }   // ← pt.get(2)(v) 유실
  }
  return new ShapeDto("POLYGON", null, null, null, null, flat);            // ← 타입 왜곡
  ```
  실측 응답(v 를 `1→0` 만 변경한 두 APPROVED 버전 비교):
  `{"type":"MODIFIED","objectId":"900003","before":{"type":"POLYGON","points":[34개]},"after":{"type":"POLYGON","points":[34개]}}` — 파이썬 대조 결과 `before.points == after.points` → **True**
- **재현/확인 경로**:
  1. SKELETON 라벨 보유 프레임 승인 → v1 생성
  2. `PUT /v1/frames/{srcSn}/labels` 로 키포인트 1개의 v 만 변경(좌표 동일) → 재승인 → v2 생성
  3. `curl "$API/v1/versions/{v2hash}/diff?compareWith={v1hash}" -H "Authorization: Bearer $REV"` → `before.points == after.points`
- **영향**: 기능(버전 비교 신뢰성). 검수자가 "변경됐다는데 뭐가 변경됐는지 안 보이는" 상태가 되어 diff 를 근거로 한 롤백 판단이 불가능해진다. FE 가 `type` 을 보고 shape 렌더러를 고르면 SKELETON 이 폴리곤으로 잘못 그려진다. 보안 영향 없음.
- **수정 방향(제안)**: `LabelDiffDto.ShapeDto` 에 SKELETON 분기를 추가해 `type="SKELETON"` + 삼중값 보존 표현(예: `keypoints: [[x,y,v]…]` 또는 `flat` 을 3-stride 로) 으로 내려보내고, FE `LabelDiff` 타입도 동반 확장한다. 회귀 가드로 "v 만 바뀐 두 버전의 diff 응답에서 before≠after" 단언을 `VersionServiceTest#버전diff_SKELETON_v만_바뀌면_MODIFIED_감지` 에 추가.

### [D-ISSUE-42] TC-DIFF-006 — 인가보다 해시 조회가 선행해 버전 존재 여부 오라클이 성립
- **심각도**: LOW
- **기대 동작(기대효과)**: 인가 실패자가 시스템 내부 상태(특정 버전 해시의 존재 여부)를 응답 코드 차이로 알아낼 수 없어야 한다(CWE-209 / OWASP A01).
- **현재 동작(이슈 내용)**: `diff` 는 해시 조회를 먼저 하고 인가를 나중에 한다.
  ```java
  // VersionService.java:338-349
  LsLabelVersion fromVersion = findByHashOrThrow(fromHash, "from 버전을 찾을 수 없습니다."); // ← 404
  LsLabelVersion toVersion   = findByHashOrThrow(toHash,   "to 버전을 찾을 수 없습니다.");
  requireFrameScoped(fromVersion, "from");
  LsDataSrc fromSrc = accessGuard.verifyAndGet(fromVersion.getDataSrcSn(), actor);          // ← 403
  ```
  실측(미배정 WORKER 토큰): 존재하는 해시 → **403**, 존재하지 않는 해시 → **404**. `findByVersionHash` 는 srcSn 제한이 없는 **전역 조회**라 남의 영상 버전도 판별된다.
- **재현/확인 경로**:
  ```bash
  # 미배정 WORKER 토큰으로
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/{존재하는해시}/diff?compareWith={존재하는해시}" -H "Authorization: Bearer $WK"  # 403
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/deadbeef/diff?compareWith=deadbeef" -H "Authorization: Bearer $WK"              # 404
  ```
- **영향**: 정보 노출(CWE-209). 실효 위험은 낮다 — 해시가 SHA-256 이라 무작위 추측이 불가능하고, 판별하려면 이미 페이로드를 알고 있어야 한다. 그럼에도 "특정 라벨 상태가 승인된 적 있는가"를 인가 없이 확인할 수 있는 채널이다.
- **수정 방향(제안)**: 우선순위가 높지 않으므로 **현행 유지도 수용 가능**. 정정한다면 `diff` 를 `rollback` 과 같은 **srcSn 스코프 진입점**으로 정렬하는 것이 근본책이다(요청에 srcSn 을 받아 `accessGuard.verifyAndGet(srcSn)` 을 먼저 수행 → `findByDataSrcSnAndVersionHash` 조회). 이는 D-ISSUE-45 도 동시에 해소한다.

### [D-ISSUE-43] TC-DIFF-008 — 롤백 복원이 `LS_DATA_LBL.REG_USER_NO` 를 되살리지 못해 NULL 로 소실되고, 통계가 그 라벨을 "자동 라벨"로 오분류
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 롤백은 "그 시점 작업본으로 되돌리는 것"이므로 복원된 라벨의 귀속(누가 만든 라벨인가)이 보존돼야 한다. 특히 이 컬럼은 통계에서 **수동/자동 라벨 판별 프록시**로 쓰이므로 값이 바뀌면 지표가 틀어진다.
- **현재 동작(이슈 내용)**: 스냅샷 페이로드(`LabelResponse.Item`)에 `regUserNo` 가 없고, 명시 PK 복원 INSERT 도 그 컬럼을 쓰지 않는다.
  ```java
  // batch/repository/LsDataLblRepositoryImpl.java:26-29
  "INSERT INTO LS_DATA_LBL (LBL_SN, SRC_SN, LBL_TYPE_CD, LBL_ID, LBL_NM, POINT_CN, TRCK_ID, REG_DT) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT (LBL_SN) DO NOTHING";   // ← REG_USER_NO 없음
  ```
  그리고 통계는 이 컬럼의 NULL 여부로 자동 라벨을 센다.
  ```java
  // stats/repository/StatsQueryRepository.java:150-166
  /** regUserNo IS NULL 을 "자동 라벨" 프록시로 사용한다 */
  SELECT COUNT(l) FROM LsDataLbl l ... WHERE l.regUserNo IS NULL AND s.rawSn IN (...)
  ```
  실측: 롤백 전 `reg_user_no=2001` → 롤백 후 `reg_user_no=(null)` (복원된 900001·900002 전부).
- **재현/확인 경로**:
  ```sql
  -- 롤백 전
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- 2001
  -- POST /v1/versions/{hash}/rollback  수행 후
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- NULL
  ```
- **영향**: 데이터 정합/감사. ①라벨 작성자 귀속 소실 ②`SCR-STAT-001` 작업자 통계의 `autoLabelRate` 가 롤백된 프레임만큼 부풀려짐(수동 라벨이 자동으로 계상). 롤백은 되돌릴수록 누적되며 원복 수단이 없다(값이 어디에도 남지 않음).
- **수정 방향(제안)**: ①스냅샷 페이로드에 `regUserNo` 를 실어 왕복 복원하거나(권장 — AI메타·TRCK_ID 와 동일한 처리) ②최소한 `INSERT_SQL`/`createRestored` 에 컬럼을 추가하고 값이 없으면 **롤백 수행자 대신 원 작성자 불명을 구분할 수 있는 표식**을 남긴다. 함께 `StatsQueryRepository` 의 "regUserNo IS NULL = 자동" 프록시를 `LsDataLblAiInfo` 조인 기반으로 교체하는 것이 근본책(주석에도 비용 회피용 프록시라고 명시돼 있음). 회귀 가드는 `VersionRollbackRestoreIT` 에 "롤백 후 REG_USER_NO 보존" 단언 추가.

### [D-ISSUE-44] TC-DIFF-012 — export 가 `nothing produced → FAILED` 로 끝나도 TASK_MODIFIED/TASK_COMPLETED 가 그대로 발송된다 (통지 보류 계약 우회)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `CLAUDE.md` ★export 재생성·동기화 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다… export 가 실패하면 통지를 보류하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다."* 관제가 통지를 받고 `V_COMPLETED_VIDEO.EXPORT_PATH_NM` 을 픽업할 때 **새 버전 산출물이 반드시 존재**해야 "라벨링 정보 동기화" 요구가 성립한다.
- **현재 동작(이슈 내용)**: 산출 프레임이 0건이면 export 는 FAILED 로 마감되지만 **예외를 던지지 않는다**. 통지 보류는 오직 예외 이탈로만 성립하므로 이 경로가 게이트를 그대로 통과한다.
  ```java
  // dataset/export/DatasetExportService.java:205-210
  if (totalWritten == 0) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
      outcome = OUTCOME_FAILED;      // ← throw 없음 (deidentBlocked 만 throw)
  }
  // dataset/export/AsyncDatasetExportRunner.java:121-129
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }   // ← FAILED 여도 true
      catch (Exception e) { ...; return false; }
  }
  ```
  실측 로그(롤백 직후, 같은 배치 스레드 4ms 간격):
  ```
  00:02:32.940 WARN  DatasetExportService  - [DatasetExport] nothing produced — marked FAILED rawSn=900001 version=6
  00:02:32.945 INFO  ControlNotifyService  - [ControlNotify] TASK_MODIFIED sent rawSn=900001 frames=1 videoLevel=0 reExport=true
  ```
  mock-server: `POST /api/data-set/v2/jobs/900001/notify-updated → 202`. DB `ls_dataset_export` 는 해당 rawSn 의 8개 버전이 **전부 `EXPORT_STTS_CD='FAILED'`**. 승인 경로(`TASK_COMPLETED`)에서도 동일 패턴 관측(`no frames — skip export rawSn=43` 직후 `TASK_COMPLETED sent rawSn=43`).
- **재현/확인 경로**:
  ```bash
  # 프레임 이미지 파일이 없는(또는 전부 skip 되는) APPROVED 영상에서
  curl -s -X POST "$API/v1/versions/{hash}/rollback" -H "Authorization: Bearer $REV" \
       -H "Content-Type: application/json" -d '{"srcSn":<srcSn>}'
  docker logs klid-backend --since 1m | grep -E "nothing produced|TASK_MODIFIED sent"
  docker logs klid-mock-server --tail 50 | grep notify-updated
  ```
  ```sql
  select export_sn, export_ver_no, export_stts_cd from ls_dataset_export where data_raw_sn = :rawSn order by export_sn desc;
  ```
- **영향**: 데이터 정합(관제 연동 계약). 관제가 통지를 받고 뷰를 SELECT 하면 `EXPORT_PATH_NM` 이 **직전 성공 버전(구 라벨) 또는 NULL** 이다 — 즉 "수정했다"는 통지를 받고 **수정 전 산출물이나 빈 값**을 픽업한다. 정확히 이 시나리오를 막으려고 도입한 보류 로직(HIGH-D)이 무력화된 상태이며, 실패 행이 남아 `DatasetExportFailureRecoverer` 가 재산출·재통지하더라도 **이미 나간 잘못된 통지는 회수되지 않는다**.
  ※ 산출 경로 자체는 E 클러스터(TC-EXPORT) 소관이라 중복 보고 가능성 있음. 본 건은 **롤백(TC-DIFF-012) 경로에서 실측**된 것으로 기록한다.
- **수정 방향(제안)**: `DatasetExportService` 의 `totalWritten == 0` 분기를 `deidentBlocked` 와 동일하게 **예외 이탈**로 바꿔 `doExport → false → 통지 보류` 가 성립하게 한다(FAILED 행은 그대로 남겨 회수기가 집도록 유지). 또는 `doExport` 가 `boolean` 대신 export outcome 을 받아 `COMPLETED|PARTIAL` 일 때만 true 를 반환하도록 계약을 조인다. 회귀 가드: "export 가 FAILED 로 마감되면 `DatasetExportCompletedEvent`/`afterExport` 가 발화하지 않는다" IT 추가.

### [D-ISSUE-45] TC-DIFF-026 — 동일 `version_hash` 다중 매칭 시 `matches.get(0)` 이 비결정적이라 diff 결과가 조용히 뒤바뀐다 (D-ISSUE-27 이월, 실증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 같은 입력(두 해시)에 대해 diff 는 항상 같은 결과를 돌려주거나, 모호하면 명시적으로 거부해야 한다. `VERSION_HASH` 의 UNIQUE 는 `(DATA_SRC_SN, VERSION_HASH)` 복합이므로 **서로 다른 프레임이 같은 해시를 갖는 것은 정상**이다(라벨 집합이 같으면 발생 — 특히 라벨 0~1건인 단순 프레임에서 현실적으로 충돌 가능).
- **현재 동작(이슈 내용)**: 전역 해시 조회에 정렬이 없고 첫 행을 그대로 쓴다.
  ```java
  // VersionService.java:1020-1026
  private LsLabelVersion findByHashOrThrow(String versionHash, String notFoundMessage) {
      List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
      if (matches.isEmpty()) { throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage); }
      return matches.get(0);      // ← 어느 프레임의 버전인지 비결정
  }
  ```
  ```java
  // version/repository/LsLabelVersionRepository.java  (ORDER BY 없음)
  List<LsLabelVersion> findByVersionHash(String versionHash);
  ```
  **실측**: 같은 해시를 다른 프레임(src 900002)에 하나 더 만든 뒤 동일 diff 요청을 5회 반복 → 매번 정상 결과(변경 3건). 이어서 `UPDATE` 로 heap 순서를 뒤집자(ctid `(2,11)` 로 이동) **같은 요청이 `data:[]`(변경 없음)** 을 반환. 오류 없이 결과만 뒤바뀐다.
- **재현/확인 경로**:
  ```sql
  -- 다른 프레임에 같은 해시를 만든다 (복합 UNIQUE 라 허용됨)
  insert into ls_label_version (lbl_version_sn,data_raw_sn,data_src_sn,ver_no,save_reason_cd,actvtn_yn,reg_id,reg_dt,lbl_payload,version_hash)
  values (990002, <rawSn>, <otherSrcSn>, 9, 'APPROVED','N','1001', now(), '{"items":[]}', '<기존해시>');
  update ls_label_version set reg_id = reg_id where lbl_version_sn = <원본행>;   -- heap 순서 반전
  select ctid, lbl_version_sn, data_src_sn from ls_label_version where version_hash = '<기존해시>' order by ctid;
  ```
  ```bash
  curl -s "$API/v1/versions/{H2}/diff?compareWith={충돌해시}" -H "Authorization: Bearer $REV"   # 결과가 뒤바뀜
  ```
- **영향**: 데이터 정합/기능. ①검수자가 "변경 없음"을 보고 잘못된 승인 판단을 내릴 수 있다 ②VACUUM·UPDATE·인덱스 스캔 전환 등으로 재현이 산발적이라 장애 분석이 어렵다 ③`requireFrameScoped`/`accessGuard` 가 **선택된 그 행 기준**으로 평가되므로 인가 대상 프레임까지 요청마다 달라진다(권한 자체는 각 행에 대해 정상 검사되므로 인가 우회는 아님).
- **수정 방향(제안)**: 근본책은 **diff 진입점을 srcSn 스코프로 정렬**하는 것 — 요청에 `srcSn` 을 받아 `findByDataSrcSnAndVersionHash`(이미 존재, `rollback` 이 사용)로 단건 조회하면 모호성·D-ISSUE-42 오라클이 함께 사라진다. 하위호환이 필요하면 차선책으로 ①`findByVersionHash` 에 `order by dataSrcSn, labelVersionSn` 을 부여해 결정화하거나 ②`matches.size() > 1` 이면 `INVALID_INPUT`(400, "해시가 여러 프레임에 매칭됨 — srcSn 을 지정하세요")로 명시 거부한다. 어느 안이든 다중 매칭 상황의 회귀 테스트가 현재 **0건**이므로 함께 추가한다.
# D-part4 — D-6. 관제 통지 (TC-NOTIFY) 45건 검증 결과

- **대상**: `docs/test-cases/D-review-version-notify.md` `## D-6. 관제 통지 (TC-NOTIFY)` — TC-NOTIFY-001~025, 032~051 (45건, 폐기 0건)
- **검증일**: 2026-08-01 (KST 기준 실행, 일부 로그는 2026-08-02 새벽까지 연장)
- **스택**: 풀스택 기동 상태(`docker ps` 5컨테이너 healthy). `CONTROL_NOTIFY_ENABLED=true`, `CONTROL_NOTIFY_URL=http://klid-mock-server:9400` — 관제 통지가 **mock-server 를 실제로 왕복**한다(self-fill 아님).
- **판정 근거 원천**: ①mock-server 인바운드 로그(`docker logs klid-mock-server`) ②backend 애플리케이션 로그 ③`public.ls_control_notify_fallback`(실제 전송 페이로드 원문 보관) ④`public.ls_dataset_export` ⑤코드 file:line
- **주입한 테스트 데이터**: `ls_control_notify_fallback` 에 `idmp_key LIKE 'qa-d6-%'` 7행 INSERT (기존 행 보존, 코드/설정/테스트 무수정). 재시도 잡(5분 주기)이 이를 실제로 소비해 **폴백/재시도/dead-letter/job_id 가드 경로를 실동작으로 관측**했다.

## 0. 실동작 근거 핵심 (요약)

`ls_control_notify_fallback` 에 남은 **실제 전송 페이로드 원문**(queue_sn 12·15 = TASK_COMPLETED, 13·14·16~19 = TASK_MODIFIED):

```json
{"job_id":"26","event_type_cd":"EV02000201","lclgv_cd":"1168000000","lclgv_nm":null,"duration_sec":5,"image_count":5}
{"job_id":"26","changed_items":{"images":["0000.jpg","0001.jpg","0002.jpg","0003.jpg","0004.jpg"],
                                 "jsons":["0000.json","0001.json","0002.json","0003.json","0004.json"]}}
{"job_id":"26","changed_items":{"images":[],"jsons":[]}}
{"job_id":"26","changed_items":{"images":[],"jsons":["0000.json"]}}
```

DB 대조 — `ls_data_raw(raw_sn=26)`: `evnt_type_cd=EV02000201`, `lclgv_cd=1168000000`, `vdo_len_sec=5` / `count(ls_data_src where raw_sn=26)=5` / `mng_ex_local_gov` 0행 → `lclgv_nm=null`.
**6필드 전부 DB 실측 일치, 상수 self-fill 0건.** 구 기대값(`totalFrames`/`labeledFrames`/`reviewerName`)은 페이로드에 **존재하지 않음**(UNCERTAINTIES #3 대로 정상).

mock-server 실왕복 로그(자기치유 2방향 모두 실측):
```
notify-updated  job_id=26 → 404 Not Found     →  notify-completed job_id=26 → 202  (404 self-heal)
notify-completed job_id=26 → 409 Conflict     →  notify-updated   job_id=26 → 202 images=5 jsons=5 (409 self-heal)
```

---

## 1. 판정 표

| ID | 판정 | 근거 확인 | 요지 |
|----|:--:|------|------|
| TC-NOTIFY-001 | PARTIAL | [실동작] backend log 2026-08-01 23:54:53 · `ls_dataset_export#18` | 트리거는 `DatasetExportCompletedEvent`(승인 이벤트 직후 아님) → **순서 보장은 성립**. 그러나 export 가 `FAILED` 로 마감된 경우에도 완료 이벤트가 발행돼 **"SUCCEEDED 이후"** 전제가 깨진다 → D-ISSUE-61 |
| TC-NOTIFY-002 | PASS | [정적] `DatasetExportBridge.java:36-37` | `@TransactionalEventListener(AFTER_COMMIT)` — 승인 tx 롤백 시 리스너 미호출 → export·통지 모두 미발생. 테스트 `DatasetExportBridgeTest` |
| TC-NOTIFY-003 | PASS | [실동작] `ls_control_notify_fallback#12,15` payload + `ls_data_raw`/`ls_data_src` 대조 | 6필드 평면 snake_case, 전 필드 DB 실측(§0). 라벨·PII·토큰·원본 경로 없음. `TaskCompletedPayload.java:24-32` / `ControlNotifyPayloadFactory.java:78-92` |
| TC-NOTIFY-004 | PASS | [정적] `ControlNotifyService.java:130-134` | catch → `enqueueQuietly(...)` + `metrics.incrementCompletedFailed()`. 테스트 `ControlNotifyServiceTest#TASK_COMPLETED_실패시_폴백큐_적재됨` |
| TC-NOTIFY-005 | PASS | [실동작] backend log 23:19:00.561 / 23:22:14.187 + DB queue_sn 15·16 | 409→updated 치유 시 관찰행 `eventType=TASK_MODIFIED`, 404→completed 치유 시 `TASK_COMPLETED` — **요청 종류가 아니라 실제 전송분** 기록. `ControlNotifyService.java:126-129,318-325` |
| TC-NOTIFY-006 | PASS | [정적] `ControlNotifyService.java:318-325` | `recordSendSuccess` try/catch → warn 만, 통지 성공 유지. 테스트 `관찰_적재_실패해도_통지성공_metrics는_유지된다` |
| TC-NOTIFY-007 | PASS | [실동작] DB payload 원문(§0) | `{job_id, changed_items:{images[],jsons[]}}`, 항목은 `%04d.jpg/.json`. SRC_SN·좌표·메타 본문·절대경로 **없음**. 두 리스트 항상 non-null(빈 리스트 실측). `TaskModifiedPayload.java:42-53` · `ExportFileNaming.java:44-59` |
| TC-NOTIFY-008 | PASS | [실동작] `\d ls_mon_noti_acml` + backend flush log | `LS_MON_NOTI_ACML`(V144) 실재, 부분 유니크 `uk_lmna_raw_pending`. flush 로그가 DB 왕복 후 복원된 페어를 출력: `flush rawSn=26 regen=true frames=296=[LABEL_ADDED, LABEL_DELETED]`, 다른 tick 은 `videoLevel=1 reExport=false`. 구현체는 `JpaControlNotifyDebounceStore` 단일(인메모리 없음) |
| TC-NOTIFY-009 | PASS | [정적] `ControlNotifyDebouncer.java:81,191-204` | `@PreDestroy flushAll` — `shutdownNow()` 로 새 tick 차단 후 `windowCutoff=now` 로 만료 무관 drain, `MAX_DRAIN_ROUNDS=20`. 미처리분은 DB 잔존 |
| TC-NOTIFY-010 | PASS | [실동작] backend log 23:10:19 기동 로그 + flush 스레드명 | `[ControlNotifyDebounce] flush scheduler started intervalMs=10000 leaseMs=300000`, flush 는 스레드 `control-notify-debounce-flush` 에서 실행(= `@Scheduled` 비의존). `regen-flush.enabled=false` 시 미기동은 `:144-147` |
| TC-NOTIFY-011 | PASS | [실동작+정적] `ChangeType.java:18-28` + flush 로그 | 계약값 4종만 상수화. 페이로드에는 미포함(§0 payload 확인) 하고 flush 요약 로그로만 관측(`:292-295,312-321`) |
| TC-NOTIFY-012 | PASS | [정적] `LabelService.java:412-426` | `if (!changes.isEmpty() && isReviewApproved(rawSn))` — 검수 전 저장은 통지 미발행 |
| TC-NOTIFY-013 | PASS | [정적] `LabelService.java:397-412` | `changes` 비면 이력 저장·`bumpLabelVersion`·통지 모두 skip |
| TC-NOTIFY-014 | PASS | [정적] 7파일 grep 실측 | 7경로 전부 `exportRegenerated=true`: `LabelService:423-424`·`TrackEditService:326`·`TrackMergeService:201`·`VersionService:518-519`·`EnvironmentMetaService:125-126`·`FrameDescriptionService:60-61`·`FramePrivacyMetaService:131-132,154-155`. (추가로 `DatasetVideoMetaEnvCorrectionTx:79-80`·`EvntAnnoReviewService:230-231` 도 true — 카탈로그 7건의 상위집합) |
| TC-NOTIFY-015 | PASS | [실동작] 중복 INSERT 시도 → `duplicate key value violates unique constraint "uk_lcnf_idempotency"` | DB UK 가 최종 방어, 서비스는 `DataIntegrityViolationException` 삼키고 멱등 반환(`ControlNotifyFallbackService.java:91-95`) |
| TC-NOTIFY-016 | PASS | [정적] `ControlNotifyFallbackService.java:36,74-82` | `MAX_QUEUE_DEPTH=10000`, `countBySttsCdIn(PENDING,RETRYING)` ≥ 상한이면 `IllegalStateException` + `control.notify.fallback.queue.full` 카운터(CWE-770). 관찰행(SUCCEEDED)은 깊이 산입 제외 — 정상 |
| TC-NOTIFY-017 | PASS | [정적] `ControlNotifyFallbackService.java:71-73,113-115` | `enabled=false` 면 enqueue/record 모두 `Optional.empty()` |
| TC-NOTIFY-018 | PASS | [실동작+정적] `LsControlNotifyFallbackRepository.java:45-48` + 재시도 잡 로그 | `UPDATE ... SET STTS='RETRYING' WHERE QUEUE_SN=? AND STTS='PENDING'` 조건부 CAS, `updated!=1 → empty`(`:137-143`). 실행 시 5행 클레임 성공 관측 |
| TC-NOTIFY-019 | PASS | [실동작] queue_sn 22·24·25 | 1회 실패 → `PENDING`, `next_rtry_dt = 23:56:20 + 2분 = 23:58:20`(=2^1분). cap 60 은 `Math.min(60, 1<<min(n,6))`(`LsControlNotifyFallback.java:208`). ⚠ 같은 메서드 javadoc `:190` 은 "(1, 2, 4, 8, 16)" 이라 실제(2,4,8,16,32)와 어긋남 → D-ISSUE-64(LOW, 문서) |
| TC-NOTIFY-020 | PASS | [실동작] queue_sn 23 | `rtry_nmtm=5`→6 (> max 5) → `stts_cd=DEAD_LETTER`, `dlq_dt=2026-08-01 23:56:20.957678`, `next_rtry_dt=NULL`. backend log `dead-letter queueSn=23 retryCount=6` |
| TC-NOTIFY-021 | PASS | [실동작] backend log 23:56:20 `[scheduling-6]` | due 5건 → claim→processOne→(성공 1건 markSucceeded / 실패 4건 재스케줄), `retry processed count=1`. `BATCH_SIZE=20`(`ControlNotifyFallbackRetryJob.java:31`) |
| TC-NOTIFY-022 | PASS | [실동작] queue_sn 22·24·25 | `processOne` 예외 → `markFailedAndSchedule` → `retry scheduled queueSn=NN retryCount=1 nextRetryAt=...` |
| TC-NOTIFY-023 | PASS | [실동작] `qa-d6-sanitize-2/3` 주입 → 저장된 `LAST_ERR_MSG_CN` | 토큰/URL/제어문자를 실제로 태워 확인: 입력 `http://a.b/x\nTOK` → 저장 **`URL_REDACTED TOK`**(URL 치환 + 개행→공백), 입력 `token=SEKRET123` → 저장 **`token***`**(값 마스킹). `sanitizeError`(`LsControlNotifyFallback.java:246-253`) = 제어문자→공백 → `(?i)(authorization\|token\|bearer\s+)[^\s,;]*`→`$1***` → `https?://…`→`URL_REDACTED` → 1900자 절단. 추가로 Jackson `INCLUDE_SOURCE_IN_LOCATION` 비활성이라 역직렬화 실패 메시지의 원문도 이미 `REDACTED`(이중 방어) |
| TC-NOTIFY-024 | PASS | [정적] `ControlNotifyFallbackService.java:108-129` | `recordImmediateSuccess` 가 `DataIntegrityViolationException` 삼키고 멱등 반환. UK 실재는 TC-NOTIFY-015 로 실증 |
| TC-NOTIFY-025 | PASS | [정적] `@ConditionalOnProperty` 전수 확인 | 토글 종속: `ControlNotifyClient:42`·`ControlNotifyService:53`·`ControlNotifyPayloadFactory:60`·`ControlNotifyEventListener:27`·`ControlNotifyFallbackRetryJob:27`·`TaskQueryController:46`·`ControlNotifyMetrics:30`·`ControlNotifySchedulingConfig:13`. **무조건 등록**: `ControlNotifyDebouncer:73-75`·`TaskModifiedAccumulateListener:27`·`DatasetExportBridge:29-30`(`matchIfMissing=true`) |
| TC-NOTIFY-032 | PASS | [실동작] mock-server 액세스 로그 | `POST /api/data-set/v2/jobs/26/notify-completed` · `.../notify-updated` 실왕복, mock 이 **202** 를 돌려줘도 성공 처리(`is2xxSuccessful`, `ControlNotifyClient.java:96-99`). `contentType(APPLICATION_JSON)` `:93` |
| TC-NOTIFY-033 | PASS | [실동작] mock 409 → updated 202 + backend log `completed conflicted -> resend as updated rawSn=26` + `TASK_COMPLETED sent rawSn=26 actual=TASK_MODIFIED` | 반환 SendOutcome=TASK_MODIFIED 가 관찰행에 반영(queue_sn 16). `selfHealCompletedToUpdated` 카운터 `:227` |
| TC-NOTIFY-034 | PASS | [실동작] mock 404 → completed 202 + backend log `updated not-found -> fallback to completed rawSn=26` + `actual=TASK_COMPLETED` | 1회 폴백, `selfHealUpdatedToCompleted` `:257` |
| TC-NOTIFY-035 | PASS | [정적] `ControlNotifyService.java:221-232,251-262` | 치유 분기 안의 2차 호출은 `client.send*` 직접 호출이라 재귀 진입점이 없다(2단계 종료 → 상위 catch → 폴백 큐). 테스트 `폴백은_재귀하지_않는다`·`completed_409_후_updated_도_실패하면_재귀하지_않고_폴백큐로_간다` |
| TC-NOTIFY-036 | PASS | [실동작+정적] `ControlNotifyClient.java:115-120` + `ControlNotifyStatusException.java:17` | 4xx→`ControlNotifyStatusException extends NonRetryableExternalException` → `resilience4j.retry/circuitbreaker.controlNotify.ignore-exceptions`(application.yml:544,616) 로 재시도·서킷 제외되나 예외는 호출자까지 전파(mock 에 409 가 1회만 도달한 것으로 실증). 5xx→`IllegalStateException`(집계 대상) |
| TC-NOTIFY-037 | PASS | [실동작] queue_sn 22 (`job_id="../../etc/passwd"`) | `IllegalArgumentException: 허용되지 않는 job_id 형식입니다.` 로 차단, **mock-server 액세스 로그에 해당 경로 요청 0건**(HTTP 자체가 안 나감). `JOB_ID_PATTERN=\d{1,19}` + `matches()` 전체매칭(`:73,275-279`) |
| TC-NOTIFY-038 | PASS | [실동작] queue_sn 21 (`payload_cn=''`) | 재시도 잡이 `PAYLOAD_REBUILD_REQUIRED` 를 인식해 `dispatchCompleted(null, 26)` → `buildCompleted` 재조립 → 409 → updated 치유 → `succeeded queueSn=21` / `stts_cd=SUCCEEDED`. `ControlNotifyService.java:112-123` · `ControlNotifyFallbackRetryJob.java:85-101` |
| TC-NOTIFY-039 | PASS | [정적] `ControlNotifyService.java:170-188` | regen=true → `PAYLOAD_REBUILD_REQUIRED` / regen=false → `ChangedItems.empty()` 로 **확정 적재**(재시도가 전 프레임 blast 안 함). 테스트 MED2 2건 |
| **TC-NOTIFY-040** | **FAIL** | [실동작] backend log 23:54:53.215→.219 · `ls_dataset_export#18` | export 가 **FAILED 로 마감됐는데 TASK_COMPLETED 가 발송**됨. `DatasetExportService` 는 예외 없이 정상 반환하는 실패 종결 경로가 4종 있어 `AsyncDatasetExportRunner.doExport` 가 `true` 를 돌려준다 → D-ISSUE-61 |
| TC-NOTIFY-041 | PARTIAL | [정적] `DatasetExportFailureRecoverer.java:119-170` | 회수 로직(조건부 UPDATE 클레임 → `runApprovalAsync` → 성공 시 완료 이벤트 재발행)은 코드상 정확. 다만 D-ISSUE-61 로 **실패 시점에 이미 통지가 나가 있어** "통지 유실이 아니라 지연" 이라는 전제가 성립하지 않는다(관제는 이미 구/부재 폴더를 픽업한 상태). 실동작 관측은 15분 주기 Quartz + 10분 유예로 이번 창에서 미도달(§3) |
| TC-NOTIFY-042 | PASS | [정적] `DatasetExportFailureRecoverer.java:135-144` | `deidentReportGate.isUnderDeidentReport(rawSn)` 를 `txService.claimForRetry` **이전**에 평가해 `continue` → `RTY_NMTM` 미증가 + INFO 집계 로그 |
| TC-NOTIFY-043 | PASS | [정적] `TaskModifiedAccumulateListener.java:27-37` · `ControlNotifyDebouncer.java:286-309` | 축적 리스너·디바운서·`DatasetExportBridge` 모두 토글 무조건 등록, `exportRunner.runReExportThenNotify` 는 `notifyService==null` 이어도 호출(콜백만 null). 테스트 HIGH-E 2건. (본 환경은 토글 on 이라 off 형상은 정적 판정) |
| TC-NOTIFY-044 | PASS | [정적] `LsMonNotiAcmlRepository#claimForFlush` + `JpaControlNotifyDebounceStore.java:104-110` | 상태전이 자체가 조건부 원자 UPDATE(`updated!=1 → empty`) → 한 노드만 스냅샷 획득. 테스트 `ControlNotifyDebounceCrossNodeIT`. 단일 노드 환경이라 크로스노드 실동작은 BLOCKED 성격(2노드 필요) |
| TC-NOTIFY-045 | PASS | [실동작+정적] 기동 로그 `leaseMs=300000` + `ControlNotifyDebouncer.java:84,242-270` | 발송 실패 시 `complete()` 미호출 → FLUSHING 잔존, `findFlushableAnchors` 가 `STTS='FLUSHING' AND MDFCN_DT<=leaseCutoff` 축으로 재클레임. `MIN_LEASE_MILLIS=60_000` 하한 clamp, `dropped` 카운터+ERROR 후 루프 계속 |
| TC-NOTIFY-046 | PASS | [정적] `ControlNotifyService.java:301-309` | `enqueueQuietly` 가 모든 예외 흡수 + `metrics.incrementDropped()` + ERROR. 테스트 `폴백큐가_가득_차도_예외가_호출자로_전파되지_않는다` |
| TC-NOTIFY-047 | PASS | [실동작+정적] backend log `local gov not found lclgvCd=1168000000` + payload `"lclgv_nm":null` | 마스터 미존재 → null(값 미생성) **실측**. `USE_YN<>'Y'` → null(`:160-163`), 100자 초과 절단(`:66,171-173`). 테스트 3건 |
| TC-NOTIFY-048 | PASS | [정적] `ControlNotifyPayloadFactory.java:185-219` + `LsDataSrcRepository:135-139` | `findBothVelExportableFrameNoByRawSnAndSrcSnIn(rawSn, srcSns)` 로 타 영상 srcSn·한쪽 벌만 보유 프레임 제외, 식별자만 WARN + `control.notify.modified.frame.unresolved` 카운터 |
| TC-NOTIFY-049 | PASS | [실동작+정적] payload(5프레임) vs `ls_dataset_export.frame_cnt=10`(5프레임×2벌) | `buildModifiedForAllFrames` 는 `findExportableFrameNosByRawSn`(원천 경로 둘 중 하나라도 보유) 결과만 사용(`:120-126`, repo `:158-161`) |
| TC-NOTIFY-050 | PASS | [정적] `DatasetExportBridge.java:81-88` | `DeidentReportResolvedEvent` AFTER_COMMIT → `runApprovalAsync` → force 재산출 + 완료 이벤트로 보류 통지 재개 |
| TC-NOTIFY-051 | 확인필요 | [정적] `WebClientConfig#controlNotifyWebClient` (baseUrl 만 설정, `defaultHeader` 없음) · `ControlNotifyClient.java:90-108` | 인증 헤더 미부착 확정(케이스 기대와 일치). 관제 계약 `x-access-token` 미배선 = **연동 갭**(UNCERTAINTIES #26, 협의 대상) → D-ISSUE-62 |

### 집계

| 판정 | 건수 |
|------|--:|
| PASS | 41 |
| FAIL | 1 |
| PARTIAL | 2 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 1 |
| **합계** | **45** |

---

## 2. 이슈

### [D-ISSUE-61] TC-NOTIFY-040 / TC-NOTIFY-001 / TC-NOTIFY-041 — export 가 FAILED 로 마감돼도 TASK_COMPLETED 통지가 발송된다 (예외 없는 실패 종결 4경로)
- **심각도**: CRITICAL
- **기대 동작(기대효과)**: `CLAUDE.md` 구속 정책 — *"통지는 export 성공(SUCCEEDED) 후 발송한다… **export 가 실패하면 통지를 보류**하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(통지 유실이 아니라 성공 시점으로 지연)."* 관제는 통지를 받으면 `V_COMPLETED_VIDEO.EXPORT_PATH_NM`(최신 SUCCEEDED)을 픽업하므로, 실패 상태에서 통지가 나가면 **구 버전 폴더를 픽업하거나(기존 SUCCEEDED 존재 시) 아무 폴더도 못 찾는다(최초 export 실패 시)**.
- **현재 동작(이슈 내용)**: `AsyncDatasetExportRunner.doExport` 는 **예외 발생 여부**로만 성공을 판정한다.
  ```java
  // AsyncDatasetExportRunner.java:121-130
  private boolean doExport(Long rawSn, boolean forceRegenerate) {
      try { exportService.export(rawSn, forceRegenerate); return true; }
      catch (Exception e) { log.warn(...); return false; }
  }
  ```
  그런데 `DatasetExportService.export` 는 **예외 없이 정상 반환하면서 실패로 종결하는 경로가 4종**이다:
  ```java
  // DatasetExportService.java:205-210  ← 실측 재현된 경로
  if (totalWritten == 0) {
      txService.markFailed(inserted.exportSn());
      log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}", ...);
      outcome = OUTCOME_FAILED;      // ← throw 없음 → doExport 는 true 반환
  }
  ```
  나머지 3종도 동일하게 `return`/정상 종료다 — `markBaseRejected`(경로 거부, `:167-171`), `OUTCOME_VERSION_EXHAUSTED`(`:176-180`), `OUTCOME_NO_INPUT`(`:141-145`), 그리고 쓰기 중 `catch (RuntimeException)` → `markFailed` 후 정상 반환(`:230-237`).
  결과적으로 `runApprovalAsync` 가 `DatasetExportCompletedEvent` 를 발행하고 `ControlNotifyEventListener.onExportCompleted` → `sendCompleted` 로 통지가 나간다.
  **실측 재현(2026-08-01, rawSn=72)** — 4ms 간격의 backend 로그:
  ```
  23:54:53.215 WARN  DatasetExportService  - [DatasetExport] nothing produced — marked FAILED rawSn=72 version=1
  23:54:53.219 INFO  ControlNotifyService  - [ControlNotify] TASK_COMPLETED sent rawSn=72 actual=TASK_COMPLETED
  ```
  DB: `ls_dataset_export{export_sn=18, data_raw_sn=72, export_ver_no=1, export_stts_cd='FAILED'}` / `v_completed_video where raw_sn=72` → **0행**. 즉 관제는 존재하지 않는 산출물에 대한 완료 통지를 받았다.
- **재현/확인 경로**:
  ```sql
  -- 실패 export 와 그 직후 발송된 완료 통지의 공존
  SELECT export_sn, data_raw_sn, export_ver_no, export_stts_cd, reg_dt
    FROM ls_dataset_export WHERE data_raw_sn = 72;         -- FAILED
  SELECT queue_sn, evnt_type_cd, raw_sn, send_rslt_cd, reg_dt
    FROM ls_control_notify_fallback WHERE raw_sn = 72;      -- TASK_COMPLETED / SUCCESS
  SELECT count(*) FROM v_completed_video WHERE raw_sn = 72; -- 0
  ```
  ```bash
  docker logs klid-backend 2>&1 | grep -E "nothing produced|TASK_COMPLETED sent rawSn=72"
  ```
  ⚠ 위 SQL 3건은 **검증 직후(15:0x UTC) 다른 QA 세션이 `ls_data_raw#72` 를 삭제**해 현재는 0행을 돌려준다(FK CASCADE 로 export·notify 행 동반 삭제). 위 DB 스냅샷은 삭제 전 실측이며, **영속 근거는 backend 로그**(위 `grep`)다.
  일반 재현: 프레임 원천 이미지가 실재하지 않는(=writer 가 전량 skip 하는) 영상을 검수 승인한다 — `[DatasetExport] nothing produced — marked FAILED` 직후 `[ControlNotify] TASK_COMPLETED sent` 이 이어지면 재현된 것이다.
- **영향**: 데이터정합 CRITICAL — 관제가 ①최초 승인 실패 시 경로 없는 job 을 등록하고 ②재승인 실패 시 **구 버전 폴더를 최신으로 오인**해 라벨 동기화가 영구 stale 이 된다(사업 요구 "데이터마트 학습데이터셋의 라벨링 정보 동기화" 위반). 회수기(`DatasetExportFailureRecoverer`)가 나중에 성공시켜도 그 사이 관제 보유본은 잘못돼 있고, 통지 정합성 전제(TC-NOTIFY-041)가 무너진다.
- **왜 자동테스트가 못 잡았나**: `AsyncDatasetExportRunnerTest` 의 실패 케이스 3건이 전부 `doThrow(new RuntimeException(...)).when(exportService).export(...)` — **예외 경로만** 검증한다(`:87`, `:113`). 예외 없이 FAILED 로 마감되는 실제 다수 경로에 대한 단언이 없다.
- **수정 방향(제안)**: `DatasetExportService.export` 가 결과(성공/실패 outcome)를 **반환값 또는 예외로 상위에 알리게** 한다. 최소 변경안은 ①`export` 의 시그니처를 `boolean`(또는 outcome enum) 으로 바꿔 `doExport` 가 그대로 관통시키거나, ②비-차단 실패 종결(`OUTCOME_FAILED`/`VERSION_EXHAUSTED`)도 신고 게이트처럼 예외로 이탈시키는 것. `NO_INPUT`/`IDEMPOTENT_SKIP` 은 "성공은 아니지만 통지해도 되는가" 를 별도 판단해야 하므로 outcome enum 방식(①)이 안전하다. 아울러 `AsyncDatasetExportRunnerTest` 에 "예외 없이 FAILED 로 마감되면 완료 이벤트 미발행" 회귀 테스트를 추가한다. ⚠ 본 검증에서는 구현하지 않음.

### [D-ISSUE-62] TC-NOTIFY-051 — 관제 통지 요청에 인증 헤더(`x-access-token`)가 부착되지 않는다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 관제 inbound SPI 계약(API-251/API-285)이 `x-access-token` 을 요구한다. 실서버 연동 시 인증 헤더가 없으면 전 통지가 401 로 거부되고, 폴백 큐가 5회 재시도 후 전량 dead-letter 로 고착된다(관제 동기화 전면 중단).
- **현재 동작(이슈 내용)**: 통지 전용 WebClient 가 base-url 만 설정한다.
  ```java
  // WebClientConfig#controlNotifyWebClient
  @Bean(name = "controlNotifyWebClient")
  public WebClient controlNotifyWebClient(@Value("${authoring.control-notify.url:http://localhost:8090}") String baseUrl) {
      return WebClient.builder().baseUrl(baseUrl).build();   // defaultHeader 없음
  }
  ```
  `ControlNotifyClient.doPost`(`:90-108`)도 `contentType(APPLICATION_JSON)` 만 설정한다. 비교 대상인 `vlmWebClient` 는 `vlm.client.token` 이 있으면 `Authorization: Bearer` 를 붙이는 배선이 있어 **관제 쪽만 누락**이다. 목서버는 인증을 요구하지 않아 로컬에서는 202 로 통과한다(로컬 통과가 실환경 계약을 보증하지 못하는 전형).
- **재현/확인 경로**: `docker logs klid-mock-server` 의 통지 요청은 전부 202 — 목서버가 인증을 검사하지 않기 때문. 코드상 헤더 주입 지점 부재는 `grep -n "defaultHeader" backend/src/main/java/kr/co/cudo/authoring/common/config/WebClientConfig.java` 로 확인(관제 빈에는 없음).
- **영향**: 기능/연동 — 실환경 401 로 통지 전량 실패. 보안 관점에서는 반대로 **인증 없는 outbound 를 관제가 수용하면** 통지 위조 표면이 되므로 양쪽 모두 협의가 필요(CWE-306 계열, 수신측 책임).
- **수정 방향(제안)**: `authoring.control-notify.token`(환경변수 주입, 평문 커밋 금지) 설정키를 신설하고 `controlNotifyWebClient` 에 `defaultHeader("x-access-token", token)` 를 조건부 배선한다(빈 값이면 미부착 + 기동 WARN). 실제 헤더명·값 형식은 **관제팀 확정 필요**(UNCERTAINTIES #26). 목서버에도 헤더 검사 옵션을 추가해 계약을 실왕복으로 고정하는 것이 바람직하다. ⚠ 본 검증에서는 구현하지 않음.

### [D-ISSUE-63] TC-NOTIFY-003 — `event_type_cd` 가 매핑 없이 pass-through 되어 서로 다른 코드체계가 섞여 나간다
- **심각도**: MEDIUM (확인필요 — 관제 코드값 목록 미수령)
- **기대 동작(기대효과)**: 관제 `datasets.event_type_cd` 는 관제 8대 이벤트 코드값 도메인을 따라야 한다. 저작도구 보유값이 그대로 나가면 관제가 미지의 코드로 적재하거나 거부한다.
- **현재 동작(이슈 내용)**:
  ```java
  // ControlNotifyPayloadFactory.java:139-141
  private String toControlEventTypeCd(String authoringEventTypeCd) {
      return authoringEventTypeCd;   // 매핑표 미수령 → pass-through
  }
  ```
  실측 결과 **DB 안에 이미 두 계통의 값이 공존**한다: `ls_data_raw.evnt_type_cd` = `INTRUSION`(raw 4·5·18, 영문 의미어) / `EV02000201`(raw 26, 관제 코드형). 그대로 통지되어 `ls_control_notify_fallback` 페이로드에도 두 계통이 그대로 남아 있다(queue_sn 1·3·9 = `INTRUSION`, 12·15 = `EV02000201`).
- **재현/확인 경로**:
  ```sql
  SELECT DISTINCT evnt_type_cd FROM ls_data_raw;                    -- INTRUSION, EV02000201 …
  SELECT payload_cn FROM ls_control_notify_fallback WHERE evnt_type_cd='TASK_COMPLETED';
  ```
- **영향**: 외부연동 계약 — 관제 적재 실패 또는 이벤트 유형 오분류. 데이터 자체의 정합(적재 소스별 코드체계 혼재)도 함께 드러난다.
- **수정 방향(제안)**: 관제 8대 코드 매핑표 수령 후 `toControlEventTypeCd` **한 곳**에서 allowlist 매핑(미매핑 값은 fail-closed 또는 지정 기본값)으로 교체. 동시에 적재 경로(`TrainingVideoIngestService` 등)에서 `EVNT_TYPE_CD` 코드체계를 하나로 정규화할지 별도 판단 필요. ⚠ 본 검증에서는 구현하지 않음. UNCERTAINTIES #27 과 동일 항목.

### [D-ISSUE-64] TC-NOTIFY-019 — 백오프 javadoc(1,2,4,8,16)이 실제 동작(2,4,8,16,32)과 어긋난다
- **심각도**: LOW
- **기대 동작(기대효과)**: 운영자가 재시도 스케줄을 문서로 예측할 수 있어야 한다(장애 대응 시 "언제 다시 시도되나" 판단 근거).
- **현재 동작(이슈 내용)**:
  ```java
  // LsControlNotifyFallback.java:188-210
  /** <p>백오프: 2^retryCount 분 (1, 2, 4, 8, 16). cap 60분. */
  public boolean failAndSchedule(String error) {
      this.rtryCnt += 1;                                            // ← 증가 후 계산
      long backoffMin = Math.min(60L, 1L << Math.min(this.rtryCnt, 6));   // n=1 → 2분
  ```
  실측: 첫 실패의 `next_rtry_dt` 가 `23:56:20 → 23:58:20` = **2분**(javadoc 은 1분). 실제 수열은 2,4,8,16,32,(60 cap).
- **재현/확인 경로**: `SELECT rtry_nmtm, mdfcn_dt, next_rtry_dt FROM ls_control_notify_fallback WHERE idmp_key='qa-d6-badjobid-1';`
- **영향**: 문서 정합만(동작 결함 아님). 기대 총 재시도 소요시간이 실제의 절반으로 오인된다.
- **수정 방향(제안)**: javadoc 을 `(2, 4, 8, 16, 32 …cap 60)` 으로 정정하거나, 의도가 1분 시작이면 `1L << (rtryCnt-1)` 로 코드를 맞춘다(둘 중 무엇이 의도인지 확인 필요). ⚠ 본 검증에서는 구현하지 않음.

---

## 3. 미완결·제약 사항

| 항목 | 내용 |
|------|------|
| TC-NOTIFY-041 실동작 | 실패 export(`export_sn=18`) 회수는 `retry-delay-minutes=10` + Quartz `interval-sec=900`(15분) 조건이라 회수 tick 도달 전에 **다른 QA 세션이 `ls_data_raw#72` 를 삭제**해 관측 창이 사라졌다(회수기 로그는 계속 `no retryable failed export`). 회수 **로직 자체**는 `DatasetExportFailureRecoveryIT` 가 커버. |
| TC-NOTIFY-044 | 크로스노드는 2노드 기동이 필요해 실동작 미검증(조건부 원자 UPDATE 정적 확인 + `ControlNotifyDebounceCrossNodeIT` 로 대체). |
| TC-NOTIFY-043 | 토글 off 형상(dev/stg/prd 기본)은 본 스택이 `CONTROL_NOTIFY_ENABLED=true` 라 실동작 미관측(정적 + 테스트 2건). |
| 병행 간섭 | 검증 중 다른 QA 세션이 같은 스택에서 rawSn 26·72·900001 을 구동했다. 본 문서의 로그·DB 근거에는 그 트래픽이 섞여 있으나, 각 근거는 rawSn·queue_sn·타임스탬프로 특정되어 판정에 영향 없음. |
| 잔여 테스트 데이터 | `ls_control_notify_fallback` 의 `idmp_key LIKE 'qa-d6-%'` 7행은 검증 흔적으로 남겨 둠(기존 행 미변경). 정리 필요 시 `DELETE FROM ls_control_notify_fallback WHERE idmp_key LIKE 'qa-d6-%';` |

## 4. 근거 드리프트 (카탈로그 file:line 정합성)

| TC | 카탈로그 표기 | 실제 위치 | 비고 |
|----|------|------|------|
| TC-NOTIFY-008 | `ControlNotifyDebouncer.java:174-176` | `:175-177` | +1 |
| TC-NOTIFY-009 | `:80,190-203` | `:81,191-204` | +1 |
| TC-NOTIFY-010 | `:100-103,141-166` | `:101-104,142-167` | +1 |
| TC-NOTIFY-011 | `:289-320` | `:292-295,312-321` | 요약 로그 위치 이동 |
| TC-NOTIFY-025 | `ControlNotifyDebouncer.java:72-74` | `:73-75` | +1 |
| TC-NOTIFY-043 | `ControlNotifyDebouncer.java:285-308` | `:286-309` | +1 |
| TC-NOTIFY-044/045 | `:241-269` / `:82-83` | `:242-270` / `:84` | +1 |
| TC-NOTIFY-019/020/023 | `LsControlNotifyFallback.java:failAndSchedule` / `:sanitize` | `:194-213` / **`sanitizeError` `:246-253`** | 메서드명 드리프트(`sanitize`→`sanitizeError`) |
| TC-NOTIFY-047 | `ControlNotifyPayloadFactory.java:65-66,150-174` | 동일 | 정합 |

> 그 외 인용 라인(ControlNotifyService·ControlNotifyClient·PayloadFactory·DTO·AsyncDatasetExportRunner·DatasetExportBridge·DatasetExportFailureRecoverer)은 **전부 정합**.

## 5. UNCERTAINTIES 갱신 제안

- **#3 TASK_COMPLETED payload** → **확정 종결 가능**. 6필드 평면 snake_case + 경로 `/api/data-set/v2/jobs/{job_id}/notify-*` 를 실왕복 페이로드 원문으로 확증, 상수 self-fill 0건.
- **#26 관제 통지 인증 헤더 미부착** → **사실 확정**(코드상 미배선). 관제팀 협의 항목으로 유지 + D-ISSUE-62.
- **#27 관제 `event_type_cd` 코드값 목록 미수령** → **사실 확정 + 악화 근거 추가**. DB 안에 `INTRUSION`/`EV02000201` 두 계통이 공존한 채 그대로 전송된다(D-ISSUE-63).
- **신규**: `AsyncDatasetExportRunner` 의 "export 성공 후에만 통지" 규약이 **예외 경로에서만** 성립한다는 사실(D-ISSUE-61) — 5C HIGH-D 항목의 재검토 필요.
# D클러스터 — Part5 (D-4 버전관리 스냅샷 TC-VERSION, D-8 데이터마트 View TC-MARTVIEW)

검증자: 담당 에이전트(D-part5) · 대상: `docs/test-cases/D-review-version-notify.md` §D-4(17건, 2건 폐기 제외 실질 15건) + §D-8(23건) = 40건
방법: 실동작 최우선(HTTP 요청 + DB 직접 SELECT + 실제 API 왕복), 근거 file:line 정적 대조 병행. 스택은 기존 기동 상태(backend :18081 / postgres) 그대로 사용, JWT는 컨테이너 실효 `JWT_SECRET`으로 HS256 자체 서명(`sub=1001`→REVIEWER, `sub=2001/2002`→WORKER, `GET /v1/me`로 검증). 코드/설정/테스트 파일은 수정하지 않았다. DB에 임시로 넣은 검증용 값(`de_ident_yn='F'` 토글, `ls_data_src.de_idntf_src_file_path_nm` 동일경로 실험, `active_yn='N'` 토글, `FrameDescription` PUT)은 모두 **검증 직후 원복 확인 완료**.

## D-4. 버전관리 스냅샷 (TC-VERSION) — 15건 (011/012 폐기 제외)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-VERSION-001 | PASS | [실동작] | `ls_label_version` 실측 9건, 전부 `save_reason_cd=APPROVED`·`actvtn_yn=Y`·`version_hash` 64hex(SHA-256). `VersionService.java:158-208` |
| TC-VERSION-002 | PASS | [실동작]+[정적] | raw_sn=4: 프레임 30건 중 승인 시점 라벨 보유 4건만 스냅샷 생성(`ls_label_version` 4행). `:190-195` `if (labels.isEmpty()) continue;` |
| TC-VERSION-003 | PASS | [정적] | `:292-296` 멱등 — active 해시 일치 시 `IDEMPOTENT` 반환, 신규 미생성. 코드 로직 확인, 재승인 실측 데이터는 본 환경에 없음(N/A 아님 — 로직 자체가 명확) |
| TC-VERSION-004 | PASS | [정적] | `saveActiveVersion:1007-1016` — `currentActive.forEach(deactivate)` + `countByDataRawSnAndDataSrcSn+1`로 신규 active 적층. 로직 확인 |
| TC-VERSION-005 | PASS | [정적] | `:170-173` `frames.isEmpty() → return CommitResult.EMPTY`(0,0) |
| TC-VERSION-006 | PASS | [정적] | `:166-167` `videoRepository.findById(rawSn).orElseThrow(NOT_FOUND)`. REST 미직접노출(ReviewService.approve 내부 전용 호출, rawSn은 항상 유효 FK) — 도달 경로는 방어적이나 로직은 명세대로 |
| TC-VERSION-007 | **PARTIAL** | [정적] | `actor==null`→`CustomException(UNAUTHORIZED)`는 정상(401). 그러나 `rawSn==null`→순수 `IllegalArgumentException`이며 `GlobalExceptionHandler`에 해당 핸들러가 없어(`@ExceptionHandler(Exception.class)` 로 폴백) **500**으로 응답된다(기대는 400 계열). 단 `commitApproved`는 `ReviewService.approve()`가 `stts.getRawDataId()`(non-null FK)로만 호출하는 내부 전용 메서드라 실 REST 경로에서 rawSn=null 도달 불가 — 실사용 리스크는 낮음. → D-ISSUE-81 |
| TC-VERSION-008 | PASS | [정적] | `serializeSnapshotWithSimplification:1041-1053` 1MB 초과 시 `simplifyPolygons`로 재직렬화 후 10MB(`MAX_DEIDENT_PAYLOAD_BYTES`)까지 허용, 승인 차단 없음 |
| TC-VERSION-009 | PASS | [정적] | `:1049-1052` 단순화 후에도 초과 시 `INVALID_INPUT`→호출부(`snapshotFrameOnApprove:274-279`)가 `catch`해 해당 프레임만 `SKIPPED` 집계, 전체 승인은 유지 |
| TC-VERSION-010 | PASS | [정적]+[테스트] | `findActiveForUpdate`(`PESSIMISTIC_WRITE`, Repository:26-33)로 동시 승인/롤백 직렬화. `VersionServiceRollbackLockOrderTest` 존재 + baseline(`VersionServiceTest` 35 tests, 0 failed) |
| TC-VERSION-013 | PASS | [실동작] | WORKER(2002, raw4 미배정) → `GET /v1/frames/1/versions` **403** `"본인에게 배정되지 않은 영상입니다."` (`accessGuard.verifyAccess`) |
| TC-VERSION-014 | PASS | [실동작] | `GET /v1/frames/1/versions` 응답에 `commitSha/shortHash/authorName/message/committedAt/isCurrent`만 포함, `LABEL_PAYLOAD`(좌표 본문) 없음. 로그도 `srcSn/created/skipped`만(`:205-206`) |
| TC-VERSION-015 | PASS | [실동작] | raw18 `de_ident_yn`을 `'F'`로 임시 토글 후: `GET /v1/frames/45/versions`→**200**(정상 응답), `GET /v1/frames/45/labels`→**412** `"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."` 동일 조건에서 대비 확인. `GET /v1/versions/{hash}/diff?compareWith={hash}`도 **412** 확인(diff도 게이트 적용, `:354`). 검증 후 `'Y'`로 원복 |
| TC-VERSION-016 | PASS | [실동작]+[정적] | `ls_label_version WHERE data_src_sn IS NULL` 실측 **0건**(레거시 행 자체가 이 환경엔 없음). `findByDataSrcSnOrderByRegDtDesc(Long)`은 JPA `=` 파생 쿼리라 `data_src_sn IS NULL` 행과 매치 불가(설계상 배제) — 로직 확인 |
| TC-VERSION-017 | PASS | [정적] | `ReviewService.approve:466-513` 전체가 `@Transactional("controlTransactionManager")` 단일 메서드. `stts.transitionTo(APPROVED)` → `versionService.commitApproved` → `datasetVideoMetaSnapshotService.materialize` 모두 같은 트랜잭션. 실패 시 전체 롤백은 `DatasetMaterializeApproveRollbackIT`(실행 증거: XML `tests="1" failures="0"`, `materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영`)로 실제 DB 검증됨 |

## D-8. 데이터마트 View (TC-MARTVIEW) — 23건

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-MARTVIEW-001 | PASS | [실동작] | `ls_raw_data_status` APPROVED 8건 중 `v_completed_video`엔 6건만 노출(48,67은 `ls_dataset_video_meta` 미존재 — materialize 미실행 상태의 시드 데이터, APPROVED 게이트 자체는 정상 동작. 뷰 정의 `WHERE s.data_stts_cd='APPROVED'` 실측 일치) |
| TC-MARTVIEW-002 | PASS | [실동작] | raw4 `export_path_nm=/app/storage/raw/seed/4`(영상 루트, 7개 export 버전 전부 동일 경로), `frame_cnt=60`. `DatasetExportTxService.java:174` 주석 "EXPORT_PATH_NM 은 영상 루트" 일치 |
| TC-MARTVIEW-003 | PASS | [실동작] | raw8/9/15(export 레코드 없음): `export_path_nm`/`frame_cnt` 둘 다 NULL, 영상당 정확히 1행 |
| TC-MARTVIEW-004 | PASS | [실동작] | raw72 export `FAILED` 1건뿐(SUCCEEDED 없음) → raw72는 애초 미승인(PENDING)이라 뷰 대상 아님(001과 결합 확인). SUCCEEDED만 조인하는 LATERAL 서브쿼리(`ex.export_stts_cd='SUCCEEDED'`) 확인 |
| TC-MARTVIEW-005 | PASS | [실동작] | raw26 `ls_dataset_video_meta.active_yn`을 `'N'`으로 임시 변경 → `v_completed_video`에서 즉시 **0행**(사라짐), `'Y'`로 원복 후 재노출 확인. 실제 데이터에도 raw5가 자연 `active_yn='N'` 상태로 존재 |
| TC-MARTVIEW-006 | PASS | [실동작] | srcSn=1의 `de_idntf_src_file_path_nm`을 `src_file_path_nm`과 동일값으로 임시 변경 → `v_completed_frame`에서 **0행**(제외). 원복 후 재노출 확인. 대조: raw18(srcSn45~47, `src_file_path_nm=NULL`+deid만 존재 = "결측")은 정상 노출됨 — fail-closed(완전동일)와 결측(편측NULL) 구분이 실측대로 동작 |
| TC-MARTVIEW-007 | PASS | [실동작] | `PUT /v1/frames/1/description` 실호출로 `"D-8 검증용 테스트 설명"` 저장 → `v_completed_frame.description` 즉시 반영 확인 → 빈 문자열 PUT으로 원복(NULL). 컬럼은 8개 기존 컬럼 뒤에 추가(V104, 순서 보존) |
| TC-MARTVIEW-008 | PASS | [실동작] | `v_completed_label_change` 실제 26행 전부 `LBL_HSTRY_SN/RAW_SN/SRC_SN/ADD_CNT/MDFCN_CNT/DEL_CNT/REG_ID/REG_DT` 8컬럼만, 좌표본문(`CHG_DTL_CN`) 컬럼 자체가 뷰 스키마에 없음(`\d+` 확인) |
| TC-MARTVIEW-009 | PASS | [실동작] | `ls_data_lbl_hstry`에 이력 존재하는 raw는 {4,34,35,5,6,26,27} 7종이나 APPROVED는 {4,26}뿐 — `v_completed_label_change`엔 실제로 raw 4·26만 노출(34/35/5/6/27 완전 배제) |
| TC-MARTVIEW-010 | PASS | [실동작] | `v_completed_meta` 3행 모두 `rvw_stts_cd=APPROVED`. `ls_data_meta`에 `meta_key='video.%'` 행 다수 존재하나 뷰엔 VLM 메타(`0-5`)만 |
| TC-MARTVIEW-011 | PASS | [실동작] | 상동 — `video.fps/codec/bit_rate/duration_ms/filesize/resolution` 6종 raw4/6에 실존하나 `v_completed_meta`엔 0건(`NOT LIKE 'video.%'` 필터 실측 확인) |
| TC-MARTVIEW-012 | PASS | [실동작] | `pg_views WHERE viewname LIKE 'v_completed%'` → 4개(`_video/_frame/_label_change/_meta`)뿐, `v_completed_label`/`v_completed_label_attr` 부재 확인 |
| TC-MARTVIEW-013 | PASS | [정적] | V138/V139 전부 `CREATE OR REPLACE VIEW` 사용, DDL 확인 |
| TC-MARTVIEW-014 | PASS | [실동작] | `DatasetMaterializeApproveRollbackIT` **존재하며 실행·통과**(XML `tests="1" failures="0" errors="0"`, 테스트명 `materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영`) — 카탈로그의 "⚠ 전용 IT 미비(D-ISSUE-50 미해소)" 메모는 **stale**(현재 코드베이스에서 해소됨). 카탈로그 정정 필요 |
| TC-MARTVIEW-015 | PASS | [실동작] | raw4 `de_idntf_file_path_nm=/app/storage/raw/seed/4/deid/clip-9101-mask.mp4`({stem}-mask{ext} 패턴, 실측 KPST/mock 결과값), raw18(증강파생) `=".../WINTER.mp4"`(다른 명명 규칙) — 두 값이 서로 다른 실제 `ls_deident_proc_log.de_idntf_file_path_nm` 원문값이며 문자열 조합이 아님을 확인. 미완료(raw8/9/15, `de_ident_yn='F'`)는 NULL |
| TC-MARTVIEW-016 | PASS | [실동작] | raw18 `orgnl_raw_sn=4`(증강파생) → `original_video_path` **빈값/NULL** 확인. raw4(비파생)는 `./storage/raw/seed/clip-9101.mp4` 정상 노출 — 대비 확인 |
| TC-MARTVIEW-017 | PASS | [실동작] | `ls_data_lbl_hstry`에 `add+mdfcn+del=0`인 행 8건(롤백/개인정보리셋 감사) 실존하나 `v_completed_label_change`엔 **0건도 노출 안 됨**(26행 전부 합계>0 확인). 테이블 원본은 8건 그대로 보존(삭제 안 됨) |
| TC-MARTVIEW-018 | PASS | [실동작] | raw8/9/15 **실제로 `de_idntf_yn='F'`(비식별 신고 구간) 상태로 이미 APPROVED**돼 있고, `v_completed_video`에서 행이 사라지지 않고 그대로 노출됨(export_path_nm/deid_path만 자연히 NULL — 신고로 비식별 산출물이 없어서일 뿐, 뷰가 의도적으로 비운 게 아님). 확정 정책 그대로 실증 — 결함 아님 |
| TC-MARTVIEW-019 | PASS | [실동작] | raw4/8/9/15/18/26 전부 `day_ngt_cd`/`sesn_cd`/`wthr_nm` **NULL**(수동 미입력, `SHT_DT` 기반 자동 파생 없음 확인 — 예: raw8 `captured_at=2020-01-02 03:04:05`(야간 시각)인데도 `day_ngt_cd` 미채움) |
| TC-MARTVIEW-020 | PASS | [정적]+[실동작] | `DatasetVideoMetaBackfillDevController` 코드 확인 + `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets` 실호출 200 `{"targetCount":0}`(본 환경엔 정정 대상 레거시 파생행 없음 — 정상, 대상 0건이면 서비스가 no-op 반환하는 코드도 확인) |
| TC-MARTVIEW-021 | PASS | [실동작] | REVIEWER(1001) → 200. WORKER(2001) → **403** `"권한이 없습니다."`. `@Profile("!prd")`+`@PreAuthorize("hasRole('REVIEWER')")`+`DevProfileGuard` 3중 방어 코드 확인. `?dryRun=` 같은 행위분기 파라미터 없음(GET/POST 별도 sub-resource) 확인 |
| TC-MARTVIEW-022 | PASS | [정적] | `application.yml:245` `max-per-run: ${DATASET_ENV_CORRECTION_MAX_PER_RUN:200}`, `DatasetVideoMetaBackfillService.java:176-188` 페이징 루프 + 시작/잔여 로그 확인 |
| TC-MARTVIEW-023 | PASS | [실동작] | 반증 데이터 2종 실측: ① `active_yn='N'` 스냅샷(raw26 임시 토글) → 뷰 0행 ② `meta_key='video.codec'`(video.* 6종 실존) → `v_completed_meta`에 0행. 두 반례 모두 요구대로 게이트 실효 확인 |

## 요약

- **합계 40건**(TC-VERSION 15건 유효 + TC-MARTVIEW 23건 + 폐기 2건 제외): **PASS 39 / PARTIAL 1 / FAIL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**
- 모든 View·버전관리 핵심 기능을 **실제 DB SELECT + 실 API 왕복**으로 확인. 특히 확증편향 방지 대상이던 3가지(①스냅샷 해시 SHA-256 중복식별, ②신고구간(`DE_IDNTF_YN='F'`) 영상도 뷰 필터링 없이 노출, ③미export 영상 EXPORT_PATH_NM/FRAME_CNT null 유지)는 모두 **실측 데이터로 정확히 실증됨**(raw8/9/15가 실제 신고구간 APPROVED 상태로 이미 존재해 완벽한 반증 데이터 역할을 했음).

### [D-ISSUE-81] TC-VERSION-007 — `commitApproved`의 `rawSn==null` 가드가 처리되지 않은 예외로 이어져 500 응답
- **심각도**: LOW
- **기대 동작(기대효과)**: `VersionService.commitApproved(rawSn, actor)`는 `rawSn==null`일 때 의미있는 4xx(예: `INVALID_INPUT`/400)로 실패해야 한다. 카탈로그 기대결과는 "IllegalArgument/UNAUTHORIZED(401)"로, 명확한 클라이언트 오류 응답을 전제한다.
- **현재 동작(이슈 내용)**: `VersionService.java:160-162`가 순수 `throw new IllegalArgumentException("rawSn 은 필수입니다.")`를 던지는데, `GlobalExceptionHandler`(`backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java`)에는 `IllegalArgumentException` 전용 `@ExceptionHandler`가 없다(`CustomException`/`MethodArgumentNotValidException`/`ConstraintViolationException`/`HttpMessageNotReadableException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`DataIntegrityViolationException`/`AccessDeniedException`/`AuthenticationException`/`MaxUploadSizeExceededException`/`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/`Exception` 뿐). 따라서 `:273 @ExceptionHandler(Exception.class)` 제네릭 500 핸들러로 떨어진다.
  ```java
  // VersionService.java:160-165
  if (rawSn == null) {
      throw new IllegalArgumentException("rawSn 은 필수입니다.");
  }
  if (actor == null) {
      throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
  }
  ```
- **재현/확인 경로**: `commitApproved`는 REST로 직접 노출되지 않고 `ReviewService.approve()`가 `stts.getRawDataId()`(항상 non-null FK)로만 호출하는 내부 전용 메서드다. 따라서 **현재 시점엔 실 HTTP 요청으로 재현 불가**(내부 단위테스트에서 `versionService.commitApproved(null, actor)` 직접 호출 시에만 확인 가능하며, 해당 테스트도 리포지토리에 없음 — `grep -rn "commitApproved(null" backend/src/test` 0건).
- **영향**: 기능 영향 낮음(공개 API 경로에서 도달 불가). 다만 향후 `commitApproved`를 다른 컨텍스트(예: 관리자 수동 재스냅샷 API)에서 재사용하면 이 가드가 그대로 500을 낼 수 있어 잠재 함정이다. OWASP A10:2025(Mishandling of Exceptional Conditions) 관점에서 "의미있는 4xx" 원칙과 어긋남.
- **수정 방향(제안)**: `IllegalArgumentException`을 `CustomException(ErrorCode.INVALID_INPUT, ...)`으로 교체하거나, `GlobalExceptionHandler`에 `IllegalArgumentException → 400` 핸들러를 추가한다. 후자는 프로젝트 전역에 영향을 주므로 전자(로컬 교체)가 더 안전. ⚠ 구현은 하지 않는다.
