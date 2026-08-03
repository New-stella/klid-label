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
