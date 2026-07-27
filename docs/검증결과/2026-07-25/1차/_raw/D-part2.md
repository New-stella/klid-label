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
