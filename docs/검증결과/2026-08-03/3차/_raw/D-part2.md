# D 클러스터 part2 — D-5. diff / rollback (TC-DIFF) 전수 검증

- **회차**: 2026-08-03 3차
- **대상 파일/섹션**: `docs/test-cases/D-review-version-notify.md` → `## D-5. diff / rollback (TC-DIFF)` (135~165행)
- **케이스 수**: 26건 (TC-DIFF-001~026, 폐기 0건)
- **판정 집계**: **PASS 19 · PARTIAL 5 · FAIL 2 · BLOCKED 0 · N/A 0 · 확인필요 0** (PASS율 73.1%, PASS+PARTIAL 92.3%)
- **검증 방식**: 전건 **실동작**(풀스택 `docker compose` 기동분 위에서 실제 HTTP 호출 + PostgreSQL 상태 조회 + backend/mock-server 로그) + 근거 `file:line` 정적 대조 병행

## 검증 환경·데이터

`_raw/stack-bringup.md` 기동분(backend `localhost:18081/api`, DB `klid_system`@`public`, mock-server :9400) 사용.
`_raw/pipeline-drive.md` 의 rawSn=101 은 **조회만** 하고 변형하지 않았다. diff/rollback 은 상태 변형이 필수라
**기존 검증용 영상 rawSn=27**(APPROVED, WORKER=2001 LABELER 배정 / REVIEWER=1001, 프레임 srcSn 301~305)에서 수행했다.

**정상 시나리오 실구동(라벨 수정 → 재검수 → 재승인으로 버전 적층)**:

| 버전 | ver_no | versionHash(prefix) | 내용 | 생성 경로 |
|---|:--:|---|---|---|
| v1 | 1 | `5b5765de…` | BBOX 727 `[[10,10],[101,101]]` | (기존 시드) |
| v2 | 2 | `738935bd…` | BBOX 728 `[[10,10],[102,102]]` | (기존 시드) |
| v3 | 3 | `ca03614f…` | 727 확대 + POLYGON 1259 + SKELETON 1260(v 전부 2) | `PUT /v1/frames/301/labels` → submit → start → **approve** |
| v4 | 4 | `a0dcd35e…` | v3 에서 **SKELETON 1260 의 v 만 2→0** | 동일 |
| v6 | 6 | `a146e906…` | v4 + AI 라벨 1261(`AUTO_YOLO`, conf 0.91, `TRCK_ID=TRK-QA-1`) | 동일 |
| v7 | 7 | `ff050b52…` | v6 에서 1259 삭제 + 1261 좌표 변경 | 동일 |

`submit 200 → start 200 → approve 200` 전 구간 실호출, 승인 시 `[Version] approved snapshot rawSn=27 frames=5 created=1` 로그로 스냅샷 생성 확인.
합성 데이터(손상 페이로드·`DATA_SRC_SN` NULL·동일 해시 중복·빈 스냅샷 행)는 `lbl_version_sn` 990101/990102/990103/990104 로 넣고 **검증 후 전부 삭제**했다.
프로덕션 코드·설정·마이그레이션은 일절 수정하지 않았다(DB 데이터 조작만).

---

## 판정표

| ID | 판정 | 근거 확인 | 실측 결과 |
|----|:----:|---|---|
| TC-DIFF-001 | PASS | [실동작] `GET /v1/versions/{H3}/diff?compareWith={H1}` 200 · `VersionService.java:334-366` | `MODIFIED objectId=727`(before `10,10,101,101` → after `10,10,120,120`) + `ADDED 1259` + `ADDED 1260`. v2→v3 비교는 `REMOVED 728` + `ADDED 727/1259/1260` — ADDED/REMOVED/MODIFIED 3분류 모두 실증 |
| TC-DIFF-002 | **FAIL** | [실동작] v3→v4(키포인트 v 만 2→0) diff 200 · `VersionService.java:1180-1197` · `version/dto/LabelDiffDto.java:68-75` | **감지는 성립**(`MODIFIED objectId=1260`) 하나 응답이 `type:"POLYGON"` + 2-stride 평탄배열이라 **`before.points == after.points`(파이썬 비교 True)**. v 가 렌더링에서 탈락해 "무엇이 바뀌었는지"가 보이지 않음 → **3차 D-ISSUE-22**(1차 D-ISSUE-41 이월·미해소) |
| TC-DIFF-003 | PASS | [실동작] `diff(from=src302 v1, to=src301 v3)` 200 `data:[]` · `:359-361` | 다른 프레임 조합은 빈 결과. 예외 아님 |
| TC-DIFF-004 | PASS | [실동작] · `:1246-1258` | 비-hex `zz!!`/`zzzz` → **400** `잘못된 버전 해시 형식입니다.`, 128자(64 초과) → **400**, `compareWith=`(빈값) → **400**, `compareWith` 누락 → 400 `필수 파라미터가 누락되었습니다: compareWith` |
| TC-DIFF-005 | PASS | [실동작] · `:1020-1026` | 미존재 해시 `deadbeef` → **404** `to 버전을 찾을 수 없습니다.` / from 측도 `from 버전을 찾을 수 없습니다.` |
| TC-DIFF-006 | PARTIAL | [실동작] 미배정 WORKER(2002) → **403** · `:348-349` | 인가는 양 버전 모두 `accessGuard.verifyAndGet` 로 정상 차단(PORTAL 토큰은 `@PreAuthorize` 403). **다만 해시 조회가 인가보다 앞서** 존재 해시=403 / 미존재 해시=404 로 갈려 **버전 존재 여부 오라클**이 성립(CWE-209) → **3차 D-ISSUE-25**(1차 D-ISSUE-42 이월·미해소) |
| TC-DIFF-007 | PASS | [실동작] 손상 payload(`{"items":[{"id":1,`) 행과 diff → 200 `data:[]` · `:1124-1137` | 예외 전파 없이 빈 리스트(장애 격리). 로그도 `reason=` 클래스명만(본문 미출력) |
| TC-DIFF-008 | PARTIAL | [실동작] `POST /v1/versions/{H5}/rollback {"srcSn":301}` 200 · `:410-522,535-545` | ①**본문 복원 성립** — 삭제됐던 1259 가 **같은 LBL_SN 1259 로 부활**, 1261 좌표 `[[31,31],[61,61]]`→`[[30,30],[60,60]]`, **`TRCK_ID='TRK-QA-1'` 보존**, AI 메타(`lbl_src_cd=YOLO`,`conf_score=0.91`,`auto_lbl_yn=Y`) 재적재. 로그 `rollback restored labels srcSn=301 deleted=3 created=4 idPreserved=4` ②**버전 행 적층 0** — `lbl_version_sn=26`(v6) 재활성 + 27 비활성, 행 수 불변, **전 DB `SAVE_REASON_CD='ROLLBACK'` 0건** ③`LS_DATA_LBL_HSTRY` 175행에 `reg_id=1001`·`reg_dt`·`chg_dtl_cn.rollbackToVersionHash=a146e906…`+changes(ADDED 1259 / UPDATED 1261) 기록. ④PK 점유 폴백도 실증(1259 를 src303 으로 옮긴 뒤 롤백 → `rollback lblSn conflict — reassigned new ids count=1`, 신규 PK 1262, `idPreserved=3`). **결함**: 복원된 라벨 전건의 `REG_USER_NO` 가 NULL 로 소실(1260·1261 은 롤백 전 2001 이었음) → **3차 D-ISSUE-23**(1차 D-ISSUE-43 이월·미해소) |
| TC-DIFF-009 | PASS | [실동작] `{"items":[]}` 스냅샷 행으로 롤백 200 · `:663-695`, `:866-868` | `ls_data_lbl` 0건 · `ls_data_lbl_ai_info` 0건으로 정리. 이후 H5 재롤백으로 4건(727/1259/1260/1261) **전부 원 LBL_SN 그대로** 복구됨 |
| TC-DIFF-010 | PASS | [실동작] 손상 스냅샷 롤백 → **400** `손상된 버전 스냅샷이라 롤백할 수 없습니다.` · `:441-442` | 호출 후 `ls_data_lbl`(727/1259/1260) · 버전 active 플래그 **전혀 변화 없음** = 부분 적용 0 |
| TC-DIFF-011 | PASS | [실동작] `LS_AUTH_WORK_LOCK` 에 `LCK_STTS_CD='LOCKED'`(raw 27) 삽입 후 롤백 → **409** `작업이 잠긴 영상은 롤백할 수 없습니다.` · `:427-430` | 같은 상태에서 `diff` 는 200(읽기는 작업락 대상 아님 — 의도된 비대칭) |
| TC-DIFF-012 | PASS | [실동작] APPROVED 영상 롤백 → 60초 디바운스 flush → export → 통지 | `[ControlNotifyDebounce] flush rawSn=27 regen=true frames=301=[LABEL_UPDATED]` → `AsyncDatasetExportRunner … re-export(+notify) forceRegenerate=true` → `export succeeded rawSn=27 version=17 written=10` → `TASK_MODIFIED sent rawSn=27 frames=1 reExport=true` → mock-server `POST /api/data-set/v2/jobs/27/notify-updated 202`. **export 성공 후 통지** 순서도 함께 확인 |
| TC-DIFF-013 | PASS | [실동작] `ls_raw_data_status.data_stts_cd='ASSIGNED'` 로 내린 뒤 실질 롤백 4회 · `:516` | 이후 **90초 이상 경과해도 `ControlNotifyDebounce flush rawSn=27` 미발생**(마지막 flush 01:28:05 고정), export 재생성 로그도 0건. 미APPROVED 는 통지·재생성 모두 미발행 |
| TC-DIFF-014 | PASS | [실동작] 동일 해시 재롤백 200 · `:481-498` | `LBL_SN` 4건 및 `POINT_CN` 완전 불변, `ls_data_lbl_hstry` 건수 11→11, `ls_data_lbl_ai_info` 최대 PK 36→36(재삽입 없음), 로그 `rollback no-op (already active and labels identical)`. 통지/재생성도 미발행 |
| TC-DIFF-015 | PASS | [실동작] · `:535-545` | 롤백 전후 `ls_label_version` 행 수 불변(8→8), 대상 행 `actvtn_yn='N'→'Y'`, 직전 active 행 `'Y'→'N'`. **신규 행 0** |
| TC-DIFF-016 | PASS | [실동작] · `:420-421` | 없는 해시 → **404** `롤백 대상 버전을 찾을 수 없습니다.` / **다른 프레임 소속 해시 + srcSn=301** 도 404(srcSn 스코프 단건 조회라 교차 롤백 불가) |
| TC-DIFF-017 | PASS | [실동작] · `:412-418` | 토큰 없음 → **401** `인증이 필요합니다.` / 미배정 WORKER(2002) → **403** `본인에게 배정되지 않은 영상입니다.` / PORTAL → 403 / `srcSn` 누락 → 400 |
| TC-DIFF-018 | PARTIAL | [실동작] 동시 롤백 2~3건 · `:444-449` | **라벨 교체 자체는 직렬화됨**(전건 200, 데드락·부분적용·PK 충돌 0, 최종 라벨셋은 마지막 커밋 버전과 일관). **그러나 ACTIVE 단일성 불변식이 깨진다** — 정확히 1건만 active 인 상태에서 서로 다른 해시로 2건 동시 롤백 시 **active 2건 잔존**(재현 2/2), `GET /v1/frames/301/versions` 가 `isCurrent:true` 를 2건 반환 → **3차 D-ISSUE-21(신규)** |
| TC-DIFF-019 | PASS | [실동작] v4(첫 키포인트 v=0) → H3(v3, v 전부 2) 롤백 · `:605-608,789-793,818-831` | `POINT_CN` 이 `[[10.0,20.0,2],…]` 17점으로 무손실 복원. **v 가 `2.0` 이 아닌 정수 `2`** 로 정규화돼 DB 표현과 동일(왕복 무손실) |
| TC-DIFF-020 | PARTIAL | [정적] `:1260-1262` + 전 소스 grep | `isCommittable` 은 PORTAL 채널에 false 를 돌려주나 **프로덕션 호출자 0건**(`VersionServiceTest:313-317` 테스트만 참조). 실제 PORTAL 차단은 컨트롤러 `@PreAuthorize` 가 담당(실측 403 확인) → **3차 D-ISSUE-26**(1차 D-ISSUE-28 이월·미해소) |
| TC-DIFF-021 | PASS | [실동작] `de_ident_yn='F'`(배치 실패 경로 모사) 후 롤백 → **412** · `:432-437` | `PRECONDITION_FAILED` / `비식별 재처리 대기 중인 영상입니다.` **작업락이 전혀 없는 상태**에서도 차단됨(읽기·쓰기 비대칭 제거 확인). `'Y'` 복원 시 즉시 재개 |
| TC-DIFF-022 | PASS | [실동작] `de_ident_yn='F'` 에서 diff → **412** · `:351-357` | 좌표 전문 미유출. **인가 이후 평가** 실증 — 같은 조건에서 미배정 WORKER 는 **403**(412 아님)이라 게이트가 인가 우회 채널이 되지 않음. 대조로 `GET /v1/frames/301/versions` 는 **200**(TC-VERSION-015 정책 일치), `GET /v1/frames/301/labels` 는 412 |
| TC-DIFF-023 | PASS | [실동작] `DATA_SRC_SN IS NULL` 레거시 행 삽입 후 diff · `:341-346,377-382` | to 측 → **400** `to 버전은 프레임 단위 비교 대상이 아닙니다.` / from 측 → 400(`from …`) / **미배정 WORKER 도 동일 400**(인가 검사 이전 조기 반환) → 500 없음. 같은 행으로 rollback 은 404(srcSn 스코프) |
| TC-DIFF-024 | PARTIAL | [실동작] · `:486-498,535-545` | **단일 스레드에서는 양 경로 모두 자기치유 성립** — active 3건(21·22·26)을 심고 ①멱등 경로 롤백 → 26 만 남고 22·27 비활성 ②교체 경로 롤백(H3) → 21 만 남고 22·26·27 비활성. **그러나 동시 롤백 시 잉여 active 가 정리되지 않고 2건 잔존**(TC-DIFF-018 동일 근인) → **3차 D-ISSUE-21** |
| TC-DIFF-025 | PASS | [실동작 보조 + 정적] `:451-473`, `LsDataSrcRepository.java:251-253`(`SELECT LBL_VER … FOR UPDATE`) | 멱등 롤백과 `PUT /v1/frames/301/labels` 를 3라운드 동시 발사 → 결과가 항상 두 직렬화 중 하나(①롤백 no-op 후 저장 반영 ②저장 후 롤백 교체)로 수렴, **"라벨은 상대 트랜잭션 값인데 롤백은 no-op 성공"** 조합은 0회. 락 순서 VERSION→SRC→LBL 은 코드·주석에서 확인 |
| TC-DIFF-026 | **FAIL** | [실동작] `:1020-1026`, `LsLabelVersionRepository.java:47`(ORDER BY 없음) | src303 에 H5 와 동일 해시·다른 payload 행을 추가 → 동일 diff 요청 5회 모두 `items=4` 정상 → `UPDATE` 로 heap 순서 반전(ctid `(5,3)`→`(5,7)`) 후 **같은 요청이 3회 모두 `items=0`(변경 없음)** 반환. 오류 없이 결과만 뒤바뀜 → **3차 D-ISSUE-24**(1차 D-ISSUE-27/45 이월·미해소) |

### 근거 드리프트

**0건.** TC-DIFF-001~026 의 근거 `file:line` 26건 전부(`VersionService.java` 334-366 / 359-361 / 1246-1258 / 1020-1026 / 348-349 / 1124-1137 / 410-522 / 535-545 / 663-695 / 866-868 / 441-442 / 427-430 / 516-520 / 481-498 / 420-421 / 412-418 / 444-449 / 605-608 / 789-793 / 818-831 / 1260-1262 / 432-437 / 351-357 / 341-346 / 377-382 / 486-498 / 451-473 / 1180-1197 / 1143-1171)가 현재 소스와 정확히 일치했다. D-3 에서 보고된 전면 드리프트(1차 D-ISSUE-23)와 달리 D-5 는 정합.

### 1차(2026-08-01) 이슈 해소 여부 대조

| 1차 이슈 | 대상 | 3차 상태 |
|---|---|---|
| D-ISSUE-41 (SKELETON diff v 유실·POLYGON 왜곡) | TC-DIFF-002 | **미해소** — `LabelDiffDto.java:68-75` 그대로. 3차 D-ISSUE-22 로 이월 |
| D-ISSUE-42 (인가 전 해시 조회 오라클) | TC-DIFF-006 | **미해소** — `diff` 진입 순서 불변. 3차 D-ISSUE-25 로 이월 |
| D-ISSUE-43 (롤백 시 REG_USER_NO 소실) | TC-DIFF-008 | **미해소** — `LsDataLblRepositoryImpl.INSERT_SQL` 에 `REG_USER_NO` 없음, 실측 재현. 3차 D-ISSUE-23 로 이월 |
| D-ISSUE-44 (export FAILED 인데 통지 발송) | TC-DIFF-012 | **✅ 해소** — `DatasetExportService.export` 가 `DatasetExportOutcome` 를 반환하고 `AsyncDatasetExportRunner.doExport` 가 `outcome.notifiable()` 로만 판정(fail-closed, null 도 미통지). 코드 주석에 D-ISSUE-61 로 명시. 이번 롤백 경로 실측에서도 export SUCCEEDED 후에만 통지 |
| D-ISSUE-45 / D-ISSUE-27 (동일 해시 다중 매칭 비결정) | TC-DIFF-026 | **미해소** — 재현 성공. 3차 D-ISSUE-24 로 이월 |
| D-ISSUE-28 (isCommittable dead code) | TC-DIFF-020 | **미해소** — 프로덕션 호출자 여전히 0건. 3차 D-ISSUE-26 으로 이월 |
| D-ISSUE-25 (신고 rawSn 스코프 스냅샷 write-only) | TC-VERSION-011/012(타 담당) | (참고) `VersionService.java:303-307` 에 제거 사유 주석만 남고 메서드 부재 — 이번에 삽입한 `DATA_SRC_SN IS NULL` 레거시 행이 diff 400 으로 안전 처리됨을 확인 |

---

## 이슈

### [D-ISSUE-21] TC-DIFF-018 / TC-DIFF-024 — 동시 롤백 시 ACTIVE 버전 행이 2건 남아 "현재 버전"이 다중 표시된다 (신규)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `LS_LABEL_VERSION` 은 `(rawSn, srcSn)` 당 **정본 active 1건**이어야 한다. `findActiveForUpdate` 가 List 를 돌려주고 `deactivateOthers` 자기치유가 존재하는 이유가 "부분 유니크가 없어 2건 이상 존재할 수 있으니 정리한다"는 것이고(TC-DIFF-024), 그 정리가 **동시 롤백이라는 바로 그 조건**에서 성립해야 한다. 그렇지 않으면 검수자가 버전 목록에서 어느 버전이 현재 작업본인지 판별할 수 없어 "버전 비교 및 복구" 요구가 성립하지 않는다.
- **현재 동작(이슈 내용)**: active 집합 조회가 **프레임 행 락 취득보다 앞서** 수행돼, 경쟁 트랜잭션이 그 사이 새로 활성화한 행을 목록에 담지 못한다. `activeYn = :activeYn` 술어로 `FOR UPDATE` 를 걸면 **스캔 시점에 `'Y'` 인 행만** 잠기므로, 상대가 `'N'→'Y'` 로 바꿀 행은 애초에 잠금 대상이 아니다(전형적 write skew).
  ```java
  // VersionService.java:448-449  ← 여기서 읽은 목록이 stale 이 된다
  List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
          raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);
  // VersionService.java:472  ← 실제 직렬화 앵커(프레임 행 락)는 그 '뒤'에 잡힌다
  srcRepository.lockAndReadLabelVersion(src.getSrcSn()) ...
  // VersionService.java:557-563  deactivateOthers 는 위 stale 목록만 순회한다
  ```
  ```java
  // version/repository/LsLabelVersionRepository.java:29-34
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from LsLabelVersion v where v.dataRawSn = :rawSn and v.dataSrcSn = :srcSn and v.activeYn = :activeYn")
  List<LsLabelVersion> findActiveForUpdate(...);
  ```
  **실측(2/2 재현)** — active 를 정확히 1건(`lbl_version_sn=26`, v6)으로 정규화한 뒤 서로 다른 해시로 동시 롤백 2건 발사:
  ```
  rb(H3)=200  rb(H4)=200
  select lbl_version_sn,ver_no,actvtn_yn from ls_label_version where data_src_sn=301 and actvtn_yn='Y';
   21|3|Y
   22|4|Y            ← active 2건
  GET /v1/frames/301/versions →
   [('ff050b5',False),('a146e90',False),('a0dcd35',True),('ca03614',True), …]   ← isCurrent 가 2건
  ```
  이때 실제 작업본 라벨은 v4 내용인데 v3 행도 `isCurrent=true` 로 노출된다(활성 포인터와 본문 불일치).
- **재현/확인 경로**:
  ```sql
  update ls_label_version set actvtn_yn='N' where data_src_sn=:srcSn;
  update ls_label_version set actvtn_yn='Y' where lbl_version_sn=:anyOne;
  ```
  ```bash
  ( curl -s -X POST "$API/v1/versions/$H_A/rollback" -H "Authorization: Bearer $REV" \
      -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}" & \
    curl -s -X POST "$API/v1/versions/$H_B/rollback" -H "Authorization: Bearer $REV" \
      -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}" & wait )
  ```
  ```sql
  select lbl_version_sn, ver_no, actvtn_yn from ls_label_version
   where data_src_sn=:srcSn and actvtn_yn='Y';     -- 2건이면 재현
  ```
- **영향**: 데이터 정합/기능(CWE-362 write skew). ①버전 목록(`VersionItem.isCurrent`)이 현재 버전을 2건 이상 표시해 검수자가 롤백 판단 근거를 잃는다 ②활성 행 중 하나가 실제 작업본과 다른 페이로드를 가리켜 "이 버전이 현재"라는 표시 자체가 거짓이 된다 ③같은 패턴이 `commitApproved`(`:288-300` → `saveActiveVersion:1008-1018`)에도 있어 **검수 승인과 롤백이 동시에 일어나도** 동일하게 잉여 active 가 남는다 ④다음 롤백/승인이 우연히 자기치유하기 전까지 상태가 지속된다(자동 회복 시점 보장 없음).
  ※ 라벨 본문 교체 자체는 프레임 행 락으로 직렬화되므로 **본문 유실·부분 적용은 없다**(TC-DIFF-025 는 정상).
- **수정 방향(제안)**: `srcRepository.lockAndReadLabelVersion(srcSn)` 으로 프레임 행 락을 잡은 **직후에 `findActiveForUpdate` 를 다시 실행**해 신선한 active 목록으로 `frameLabelsMatch` 판정과 `deactivateOthers` 를 수행한다(첫 조회는 기존 락 순서 VERSION→SRC 유지를 위해 남겨도 되고, 재조회분은 이미 락을 보유한 행이라 추가 대기가 없다). 대안은 `(DATA_SRC_SN) WHERE ACTVTN_YN='Y'` 부분 유니크 인덱스로 DB 가 불변식을 강제하는 것이나, 기존 잉여 active 데이터 정리가 선행돼야 한다. 회귀 가드: "서로 다른 해시로 동시 롤백 후 active 행 수 = 1" 단언을 `VersionRollbackRestoreIT` 에 추가(⚠ 이 케이스는 현재 **테스트 0건**이다 — `VersionServiceTest` 는 단일 스레드 mock 기반이라 이 조합을 만들 수 없다).

### [D-ISSUE-22] TC-DIFF-002 — SKELETON diff 응답이 가시성 v 를 버리고 shape 타입을 POLYGON 으로 왜곡한다 (1차 D-ISSUE-41 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SKELETON 은 삼중값 `[x,y,v]` 이고 v(가시성)만 바꾸는 편집이 실제 작업 동선이다. `readPoints` 에 삼중값 비교를 넣어 `MODIFIED` 로 감지하도록 만든 이상, 응답 `before`/`after` 에도 그 차이가 드러나야 검수자가 diff 를 근거로 롤백을 판단할 수 있다.
- **현재 동작(이슈 내용)**: 감지는 되지만 렌더링에서 v 가 탈락하고 타입도 왜곡된다.
  ```java
  // version/dto/LabelDiffDto.java:67-75  ShapeDto.fromPoints
  List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
  for (List<Double> pt : points) {
      if (pt.size() >= 2) { flat.add(pt.get(0)); flat.add(pt.get(1)); }   // ← pt.get(2)(v) 유실
  }
  return new ShapeDto("POLYGON", null, null, null, null, flat);            // ← 타입 왜곡
  ```
  **실측**(v3→v4, 첫 키포인트의 v 만 `2→0`):
  `{"type":"MODIFIED","objectId":"1260","before":{"type":"POLYGON","points":[34개]},"after":{"type":"POLYGON","points":[34개]}}`
  → 파이썬 비교 `before == after` → **True**. 반면 롤백 복원 경로는 v 를 정상 보존한다(TC-DIFF-019 PASS) — **비교 화면만 눈이 먼 상태**.
- **재현/확인 경로**:
  ```bash
  # SKELETON 라벨의 키포인트 v 만 바꿔 재승인해 두 버전을 만든 뒤
  curl -s "$API/v1/versions/{H_new}/diff?compareWith={H_old}" -H "Authorization: Bearer $REV"
  # → before.points == after.points, type 은 양쪽 "POLYGON"
  ```
- **영향**: 기능(버전 비교 신뢰성). "변경됐다는데 뭐가 변경됐는지 안 보이는" 상태라 diff 를 근거로 한 복구 판단이 불가능하다. FE 가 `type` 으로 shape 렌더러를 고르면 SKELETON 이 폴리곤으로 잘못 그려진다. 보안 영향 없음.
- **수정 방향(제안)**: `LabelDiffDto.ShapeDto` 에 SKELETON 분기를 추가해 `type="SKELETON"` + 삼중값 보존 표현(3-stride flat 또는 `keypoints:[[x,y,v]…]`)으로 내려보내고 FE `LabelDiff` 타입을 동반 확장한다. 회귀 가드로 "v 만 바뀐 두 버전의 diff 응답에서 `before != after`" 단언 추가.

### [D-ISSUE-23] TC-DIFF-008 — 롤백 복원이 `LS_DATA_LBL.REG_USER_NO` 를 NULL 로 소실시켜 통계가 수동 라벨을 "자동 라벨"로 오분류한다 (1차 D-ISSUE-43 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 롤백은 "그 시점 작업본으로 되돌리는 것"이므로 복원된 라벨의 귀속(누가 만든 라벨인가)이 보존돼야 한다. 이 컬럼은 통계에서 **수동/자동 라벨 판별 프록시**로 쓰여 값이 바뀌면 지표가 틀어진다.
- **현재 동작(이슈 내용)**: 스냅샷 페이로드(`LabelResponse.Item`)에 `regUserNo` 가 없고, 명시 PK 복원 INSERT 도 그 컬럼을 쓰지 않는다.
  ```java
  // batch/repository/LsDataLblRepositoryImpl.java:26-29
  "INSERT INTO LS_DATA_LBL (LBL_SN, SRC_SN, LBL_TYPE_CD, LBL_ID, LBL_NM, POINT_CN, TRCK_ID, REG_DT) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT (LBL_SN) DO NOTHING";   // ← REG_USER_NO 없음
  ```
  **실측**(롤백 직전/직후, srcSn=301):
  ```
  before  1260|2001   1261|2001
  after   1260|(null) 1261|(null)     ← TRCK_ID·AI메타는 보존됐는데 작성자만 소실
  ```
  1차 실측(“복원된 900001·900002 전부 NULL”)과 동일 패턴이며, **값을 갖고 있던 라벨까지 NULL 로 덮인다**는 점이 이번에 추가로 확인됐다. 승인 스냅샷 payload 실측에도 `regUserNo` 키 자체가 없다(`id/lblTypeCd/label/labelId/points/autoLblYn/confScore/trackId/lblSrcCd` 만 존재).
- **재현/확인 경로**:
  ```sql
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- 2001
  ```
  ```bash
  curl -s -X POST "$API/v1/versions/{hash}/rollback" -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}"
  ```
  ```sql
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- NULL
  ```
- **영향**: 데이터 정합/감사. ①라벨 작성자 귀속 소실(원복 수단 없음 — 값이 어디에도 남지 않는다) ②`StatsQueryRepository`(`:150-166`)가 `regUserNo IS NULL` 을 자동 라벨 프록시로 쓰므로 작업자 통계의 자동라벨 비율이 롤백된 프레임만큼 부풀려진다. 롤백을 반복할수록 누적된다.
- **수정 방향(제안)**: 스냅샷 페이로드에 `regUserNo` 를 실어 왕복 복원하는 것이 권장안(AI 메타·`TRCK_ID` 와 동일 처리). 최소한 `INSERT_SQL`/`createRestored` 에 컬럼을 추가한다. 함께 통계의 "regUserNo IS NULL = 자동" 프록시를 `LsDataLblAiInfo` 조인 기반으로 교체하는 것이 근본책. 회귀 가드: `VersionRollbackRestoreIT` 에 "롤백 후 `REG_USER_NO` 보존" 단언 추가.

### [D-ISSUE-24] TC-DIFF-026 — 동일 `version_hash` 다중 매칭 시 `matches.get(0)` 이 비결정적이라 diff 결과가 조용히 뒤바뀐다 (1차 D-ISSUE-27/45 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 같은 입력(두 해시)에 대해 diff 는 항상 같은 결과를 돌려주거나, 모호하면 명시적으로 거부해야 한다. `VERSION_HASH` 의 UNIQUE 는 `(DATA_SRC_SN, VERSION_HASH)` 복합이므로 **서로 다른 프레임이 같은 해시를 갖는 것은 정상**이다(라벨 0~1건인 단순 프레임에서 현실적으로 충돌).
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
  // version/repository/LsLabelVersionRepository.java:47  (ORDER BY 없음)
  List<LsLabelVersion> findByVersionHash(String versionHash);
  ```
  **실측**: src303 에 H5 와 같은 해시(payload 는 `{"items":[]}`)를 추가 → 동일 diff 요청 5회 모두 `data` 4건(정상). 이어서 `UPDATE` 로 heap 순서를 반전(`ctid (5,3)→(5,7)`, src303 행이 앞으로) 하자 **같은 요청이 3회 모두 `data:[]`(변경 없음)** 을 반환. 오류 없이 결과만 뒤바뀐다.
- **재현/확인 경로**:
  ```sql
  insert into ls_label_version (lbl_version_sn,data_raw_sn,data_src_sn,ver_no,save_reason_cd,actvtn_yn,reg_id,reg_dt,lbl_payload,version_hash)
  values (990103, :rawSn, :otherSrcSn, 92, 'APPROVED','N','1001', now(), '{"items":[]}', '<기존해시>');
  update ls_label_version set reg_id = reg_id where lbl_version_sn = <원본행>;  -- heap 순서 반전
  select ctid, lbl_version_sn, data_src_sn from ls_label_version where version_hash='<기존해시>' order by ctid;
  ```
  ```bash
  curl -s "$API/v1/versions/{H_to}/diff?compareWith={충돌해시}" -H "Authorization: Bearer $REV"   # 결과가 뒤바뀜
  ```
- **영향**: 데이터 정합/기능. ①검수자가 "변경 없음"을 보고 잘못된 승인·복구 판단을 내린다 ②VACUUM·UPDATE·인덱스 스캔 전환으로 재현이 산발적이라 장애 분석이 어렵다 ③`requireFrameScoped`/`accessGuard` 가 **선택된 그 행 기준**으로 평가돼 인가 대상 프레임까지 요청마다 달라진다(권한 검사 자체는 각 행에 대해 정상 수행되므로 인가 우회는 아님).
- **수정 방향(제안)**: 근본책은 **diff 진입점을 srcSn 스코프로 정렬** — 요청에 `srcSn` 을 받아 `findByDataSrcSnAndVersionHash`(이미 존재, `rollback` 이 사용)로 단건 조회하면 모호성과 D-ISSUE-25 오라클이 함께 사라진다. 하위호환이 필요하면 차선책으로 ①`findByVersionHash` 에 `order by dataSrcSn, labelVersionSn` 부여 ②`matches.size() > 1` 이면 `INVALID_INPUT`(400, "해시가 여러 프레임에 매칭됨 — srcSn 을 지정하세요")로 명시 거부. 다중 매칭 회귀 테스트가 여전히 **0건**이므로 함께 추가한다.

### [D-ISSUE-25] TC-DIFF-006 — 인가보다 해시 조회가 선행해 버전 존재 여부 오라클이 성립한다 (1차 D-ISSUE-42 이월 · 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 인가 실패자가 시스템 내부 상태(특정 버전 해시의 존재 여부)를 응답 코드 차이로 알아낼 수 없어야 한다(CWE-209 / OWASP A01:2025).
- **현재 동작(이슈 내용)**: `diff` 는 전역 해시 조회를 먼저 하고 인가를 나중에 한다.
  ```java
  // VersionService.java:338-349
  LsLabelVersion fromVersion = findByHashOrThrow(fromHash, "from 버전을 찾을 수 없습니다."); // ← 404
  LsLabelVersion toVersion   = findByHashOrThrow(toHash,   "to 버전을 찾을 수 없습니다.");
  requireFrameScoped(fromVersion, "from");
  LsDataSrc fromSrc = accessGuard.verifyAndGet(fromVersion.getDataSrcSn(), actor);          // ← 403
  ```
  **실측**(미배정 WORKER=2002 토큰): 존재하는 해시 → **403** `본인에게 배정되지 않은 영상입니다.` / 존재하지 않는 해시 → **404** `from 버전을 찾을 수 없습니다.`
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/{존재해시}/diff?compareWith={존재해시}" -H "Authorization: Bearer $WK_UNASSIGNED"  # 403
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/deadbeef/diff?compareWith=deadbeef"     -H "Authorization: Bearer $WK_UNASSIGNED"  # 404
  ```
- **영향**: 정보 노출(CWE-209). 실효 위험은 낮다 — 해시가 SHA-256 이라 무작위 추측이 불가능하고, 판별하려면 이미 페이로드를 알고 있어야 한다. 그럼에도 "특정 라벨 상태가 승인된 적 있는가"를 인가 없이 확인할 수 있는 채널이다.
- **수정 방향(제안)**: 우선순위가 낮아 **현행 유지도 수용 가능**. 정정한다면 `diff` 를 `rollback` 과 같은 **srcSn 스코프 진입점**으로 정렬하는 것이 근본책이며(요청에 `srcSn` → `accessGuard.verifyAndGet` 선행 → `findByDataSrcSnAndVersionHash`), 이는 D-ISSUE-24 도 동시에 해소한다.

### [D-ISSUE-26] TC-DIFF-020 — `isCommittable` 이 프로덕션 호출자 0건인 dead code 로 남아 있다 (1차 D-ISSUE-28 이월 · 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 정책 판정 메서드는 실제 판정 지점에 배선돼 있거나, 배선하지 않기로 했다면 제거돼야 한다. 남아 있으면 "PORTAL 은 이 함수로 차단된다"는 오해를 만들어 실제 차단 지점(`@PreAuthorize`)을 손댈 때 안전망이 있다고 착각하게 된다.
- **현재 동작(이슈 내용)**:
  ```java
  // VersionService.java:1260-1262
  public static boolean isCommittable(TokenClaims actor) {
      return actor != null && actor.channel() != Channel.PORTAL;
  }
  ```
  전 소스 `grep -rn "isCommittable" backend/src frontend/src` 결과: 정의 1건 + **테스트 참조 3건**(`VersionServiceTest.java:313-317`)뿐, 프로덕션 호출자 **0건**. 실제 PORTAL 차단은 `VersionController` 의 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 가 담당하며 실측 PORTAL 토큰 diff/rollback 모두 **403** `권한이 없습니다.` 로 거부됐다.
- **재현/확인 경로**: `grep -rn "isCommittable" backend/src frontend/src`
- **영향**: 코드 품질(도달 불가 코드) + 테스트가 실제로 보호하지 않는 것을 보호한다고 표시하는 **거짓 커버리지**. 기능·보안 영향은 없다(차단 자체는 성립).
- **수정 방향(제안)**: 둘 중 하나로 확정한다 — ①제거(+ 해당 단위테스트 제거, 채널 차단 검증은 컨트롤러 IT 로 이관) ②`commitApproved`/`rollback` 진입부에 실제 배선하고 PORTAL 요청에 대한 응답 코드를 확정(현행 403 유지 권장). ⚠ 본 검증에서는 구현하지 않는다.

---

## 부수 관측 (결함 아님 / 참고)

- **롤백 후 AI 메타 행의 `REG_ID` 가 롤백 수행자로 바뀐다**(2001 → 1001, `data_lbl_ai_info_sn` 도 재발급). `VersionService.replaceFrameLabels` javadoc 의 `@param actorId AI 메타 복원 행의 REG_ID (감사용)` 계약대로이며, `LBL_SRC_CD`·`CONF_SCORE`·`AUTO_LBL_YN` 값 자체는 보존된다 → 결함 아님.
- **`LS_DATA_LBL_ATTR_VAL`(라벨 속성값)은 롤백으로 복원되지 않고 삭제**된다 — 스냅샷 페이로드에 없기 때문(코드 javadoc 에 "한계"로 명시). 이번 검증 데이터에는 속성값이 없어 실측 영향은 없었으나, 속성값을 쓰는 운영 영상에서는 롤백이 속성값을 지운다는 점을 D-4/C 클러스터와 교차 확인할 필요가 있다.
- **검수 승인 시 `ver_no` 는 `countByDataRawSnAndDataSrcSn + 1`** 이라 합성/레거시 행이 섞이면 번호가 건너뛴다(이번 검증에서 3→4→6→7). 표시용 순번이며 식별자는 해시라 기능 영향은 없다.
- **`TASK_COMPLETED` 가 `completed conflicted -> resend as updated` 로 자동 전환**되는 동작을 재승인 경로에서 반복 관측(mock-server 가 이미 완료 통지를 받은 job 에 409 를 주고 backend 가 `notify-updated` 로 재전송). D-6(TC-NOTIFY) 담당 범위라 여기서는 사실만 기록.
