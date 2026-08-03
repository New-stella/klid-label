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
