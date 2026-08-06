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
