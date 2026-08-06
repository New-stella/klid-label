# B 클러스터 part2 (B-4~B-6) 2차 검증 결과

- 대상: `docs/test-cases/B-batch-deidentify.md` 의 **B-4**(TC-BATCH-030~045, 16건) · **B-5**(050~062, 13건) · **B-6**(070~096, 27건) = **56건**
- 취소선(폐기) 행: **0건** (본 3개 섹션에는 폐기 케이스가 없다 — 집계 제외 대상 없음)
- 환경: backend `localhost:18081`(이미지 `bdc64ea2ac26` = HEAD `ca3c712b` 재빌드본) · mock-server `:9400` · postgres `:5432` 스키마 `public`
- 검증 시각: KST `2026-07-31 03:10 ~ 03:25`
- 소스/설정/테스트 파일 **수정 0건**, 빌드·테스트 **미실행**, 컨테이너 **재시작·재빌드 0회**
- 참조 데이터 카탈로그(`pipeline-drive.md` §3)의 rawSn **126·129~133·20011~20013 전부 무손상**(검증 종료 시점 재확인 완료)

## 0. 본 검증이 만든/변경한 데이터 (다른 에이전트 주의)

실동작 검증에 MARKING_READY 픽스처가 필요해 **관제 소유(MNG_*) 입력 픽스처**를 신규 시드하고 정규 API 경로로만 구동했다(`pipeline-drive.md` §5 와 동일 방식). `LS_*` 상태를 `UPDATE` 로 위조한 구간은 **없다**.

| 무엇 | 값 | 비고 |
|---|---|---|
| 신규 관제 클립 | `MNG_CLIP_MASTER` `DEV-CLIP-9201`~`9206` 6건 (+ `MNG_CLIP_EVNT_LST` 6건) | 실파일 `sample-cctv-1080p.mp4`(1920×1080 h264 112.7s), `VDO_LEN_SEC=113000`ms |
| 신규 영상 | rawSn **134~138, 143** | 전부 KPST(mock) 선두 비식별 SUCCEEDED |
| 최종 상태 | 134 `MARKING_READY/REJECTED`(★고착 — B-ISSUE-21) · 135 `COMPLETED/ASSIGNED`(3프레임) · 136 `COMPLETED/APPROVED`(12프레임/49라벨, **V_COMPLETED_VIDEO 신규 1행**) · 137 `COMPLETED/ASSIGNED`(1프레임) · 138 `COMPLETED/ASSIGNED`(6프레임) · 143 `COMPLETED/ASSIGNED`(3프레임) | |
| 기존 픽스처 변경 | **rawSn 128** — 배치 실패 검증용으로 `PENDING`→`FAILED`, 재시도큐 `EXHAUSTED`, 이후 배정+검수제출로 작업상태 `PENDING` | 동일 성격의 `rawSn 127`(`PENDING`/`F`)은 **원형 보존**했다 |
| 비파괴 프로브 | `ls_raw_data_status` IN_REVIEW/ASSIGNED 술어 검증은 `BEGIN … ROLLBACK` 안에서 수행 후 롤백 확인 | 영구 변경 0 |

> ⚠ 구동 중 다른 세션이 만든 rawSn **139~142**(`KPST_SOURCE_MISSING` 비식별 실패)가 관측됐다. 본 검증 산출물이 아니다.

---

## 1. 집계

| 섹션 | 대상 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-4 BatchOrchestrator 상태 전이 | 16 | 15 | 0 | 1 | 0 | 0 | 0 |
| B-5 마킹 완료 브릿지 | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| B-6 MarkingService | 27 | 27 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **56** | **55** | **0** | **1** | **0** | **0** | **0** |

- 근거 유형: **[실동작] 38건 / [정적]·단위테스트 대조 18건**
- 신규 이슈 **4건** (HIGH 1 · LOW 3) — 케이스 판정과 별개로 관측된 결함/갭
- 근거 `file:line` 드리프트 **9건**(전부 경미 — 메서드 경계 초과·오프바이-몇 줄)

> 케이스 56건 중 FAIL 0건이지만 **결함이 없다는 뜻이 아니다.** 케이스 표가 "현재 동작 고정"으로 적어 둔 항목(TC-BATCH-096 = B-ISSUE-23 미해소)과, **케이스가 커버하지 않는 조합**에서 실동작 결함 1건(B-ISSUE-21, HIGH)을 새로 발견했다. §5 참조.

---

## 2. 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|---|---|
| **B-ISSUE-03** | 배치 상태 전이에 상태머신 검증이 없어 종결 상태(APPROVED)도 무조건 덮어써짐 | **✅ 해소** | [실동작] `POST /v1/dev/batch/trigger` 를 **APPROVED(126 실데이터·20012 시드)·PENDING(20011)·REJECTED(20013)** 4건에 실행 → 전부 `finalStage=SKIPPED`, `LS_DATA_RAW`·`LS_RAW_DATA_STATUS`·`upd_dt` **전부 불변**, `ls_batch_proc_log` **0행**(step 0건). `V_COMPLETED_VIDEO` 에서 126 유지. IN_REVIEW 는 조건부 UPDATE 술어를 `BEGIN…ROLLBACK` 프로브로 검증(영향행수 0) |
| **B-ISSUE-23** | AUTO 마킹 `intervalFrames` 상한 미검증 | **⛔ 미해소 (현행 동작 고정 — 케이스 표와 일치)** | [실동작] rawSn 137 에 `intervalFrames=999999999` → **201**, `marks` 1건(`frameIndex=0`). 후속 배치가 **프레임 1장만** 추출(`ls_data_src(137)`=1). TC-BATCH-096 이 이 동작을 명시적으로 고정하고 있어 케이스 판정은 PASS |
| B-ISSUE-06 | `loadRaw` 자기호출로 `@Transactional(REQUIRES_NEW, readOnly)` 미적용 | **⛔ 미해소** | [정적] `BatchOrchestrator.java:104` 가 `loadRaw(rawSn)` 를 자기호출, 선언은 `:147-149`. 기능 영향은 없으나 애너테이션이 장식적 → **B-ISSUE-23(2차)** 로 재등록 |
| B-ISSUE-26 | CWE-117 sanitize 회귀 테스트 부재 | **⛔ 미해소** | `grep -niE "sanitize|CWE-117|\\\\n|\\\\r" MarkingBatchBridgeTest.java` → **0건**. 구현(`MarkingBatchBridge.java:166-168`)은 정상 |
| B-ISSUE-27(6) | `generateAutoMarks` 29.97 반올림 테스트 부재 | **⛔ 미해소** | `grep -rn "29.97" backend/src/test` → `VideoFpsResolverTest`·`VideoMetaServiceTest`·`BrampVideoProbeTest` 만. `MarkingServiceTest` 는 25/30/60 fps 만 |
| B-ISSUE-22 | rawSn 당 마킹 N건 → 고아 PENDING | **✅ 해소** | [실동작] 순차 재요청 **409**(rawSn 134) · 동시 3요청 **1×201 + 2×409**, `ls_marking(138)`=1행(rawSn 138). V142 부분 유니크 인덱스 + `requireNoActiveMarking` 2선 방어 실증 |
| D-01(1차) | 재배정엔 가드가 있는데 **신규 배정엔 없어** APPROVED 가 강등 | **✅ 해소(본 구간 확인 범위)** | [실동작] APPROVED(136)에 `POST /v1/assignments {workerId:2002}` → **409**, 작업상태 `APPROVED` 유지 |

---

## 3. ★상태전이 실측표

`P()` = `docker exec klid-postgres psql -U klid_user -d klid_system`. `LS_RAW_DATA_STATUS` 는 `raw_data_id` 로 조인(컬럼명 함정).

| # | 시나리오(실행 명령) | LS_DATA_RAW.DATA_STTS_CD | LS_RAW_DATA_STATUS.DATA_STTS_CD | 기대 | 실측 | 판정 |
|--:|---|---|---|---|---|:--:|
| 1 | `dev/batch/trigger?rawSn=20013` (work=REJECTED) | `COMPLETED` → `COMPLETED` | `REJECTED` → `REJECTED` (`upd_dt` 불변) | SKIPPED + 양쪽 불변 | `finalStage=SKIPPED`, batch log 0행 | **PASS** |
| 2 | `dev/batch/trigger?rawSn=126` (work=APPROVED, 실데이터) | `COMPLETED` → `COMPLETED` | `APPROVED` → `APPROVED` | 동상 | 동상 + 라벨 22건 불변 | **PASS** |
| 3 | `dev/batch/trigger?rawSn=20011` (work=PENDING) | `COMPLETED` → `COMPLETED` | `PENDING` → `PENDING` | 동상 | 동상 | **PASS** |
| 4 | `dev/batch/trigger?rawSn=20012` (work=APPROVED) | `COMPLETED` → `COMPLETED` | `APPROVED` → `APPROVED` | 동상 | 동상 | **PASS** |
| 5 | SQL 프로브(롤백) `IN_REVIEW` 에 배치 술어 UPDATE | — | `IN_REVIEW` (UPDATE **0행**) | 차단 | `UPDATE 0` | **PASS** |
| 6 | SQL 프로브(롤백) `ASSIGNED` 에 배치 술어 UPDATE | — | `ASSIGNED` → `PROCESSING` (UPDATE **1행**) | 통과(ASSIGNED 는 차단집합 제외) | `UPDATE 1` | **PASS** |
| 7 | 마킹 완료 → 배치 시작 (rawSn 143, 배정 선행) | `MARKING_READY` → `PROCESSING` | `ASSIGNED` → `PROCESSING` (`ver` 1→2) | 두 컬럼 동시 PROCESSING | 일치 | **PASS** |
| 8 | 배치 완료 (rawSn 135/136/138/143) | `PROCESSING` → **`COMPLETED`** | `PROCESSING` → **`ASSIGNED`**(복귀) | 두 테이블 책임 분리 | 4건 전부 일치 | **PASS** |
| 9 | 배치 완료 후 검수 제출 (rawSn 136) | `COMPLETED` 불변 | `ASSIGNED` → `PENDING` (200) | 상태머신 허용 | 일치 | **PASS** |
| 10 | 배치 실패 (rawSn 128, 마킹 0건 → FRAME_EXTRACT INVALID_INPUT) | `PENDING` → **`FAILED`** | (row 없음) → WARN 후 진행 | MARKING_READY 고착 금지 + row 부재 graceful | 일치 | **PASS** |
| 11 | 재시도 소진 (128 × 4회) | `FAILED` 유지 | — | 3회까지 `willRetry=true`, 4회차 `false` | `ls_bat_rty_wtng` `rty_nmtm` 1→2→3→**4/EXHAUSTED**, 로그 `willRetry=false` | **PASS** |
| 12 | 미배정 REVIEWER 직접 마킹 (rawSn 135, 작업상태 row **부재**) | `MARKING_READY` → `PROCESSING` → `COMPLETED` | **(없음) → 신규 생성 → PROCESSING → ASSIGNED** | tx2 `tryCreateBatchQueuedRow` 가 row 생성 | 일치(`ver`=1로 신규 INSERT) | **PASS** |
| 13 | 검수소유(work=PENDING) + stage=MARKING_READY 에서 마킹 (rawSn 134) | `MARKING_READY` **불변** | `PENDING` **불변** | 브리지 입구 차단 + 사유 응답 | 201 / `batchTriggered=false` / 사유 문구 / batch log 0행 | **PASS** |
| 14 | 수동 재처리 클레임 보상 롤백 (rawSn 128, stage=FAILED + work=PENDING) | `FAILED` → (claim)`PROCESSING` → **`FAILED` 복원** | `PENDING` 불변 | H10 보상 롤백 + 409 | 로그 `reprocess claim compensated (PROCESSING->FAILED)`, 응답 409 | **PASS** |
| 15 | APPROVED 영상에 신규 배정 (rawSn 136) | `COMPLETED` 불변 | `APPROVED` **불변** | 강등 없음 | 409 | **PASS** |

---

## 4. 결과표

### 4-1. B-4. BatchOrchestrator 상태 전이 (16건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-030 | process: rawSn null → INVALID_INPUT | PASS | [실동작] `dev/batch/trigger` 파라미터 생략 → 400 `필수 파라미터가 누락되었습니다: rawSn`, `rawSn=0` → 400 `must be greater than or equal to 1`. [정적] `BatchOrchestrator.java:101-103` + unit `rawSn_null이면_INVALID_INPUT` | HTTP 진입점은 `@Min(1)` 로 선차단되어 서비스 null 분기는 단위테스트로만 도달 |
| TC-BATCH-031 | process: 영상 미존재 → NOT_FOUND | PASS | [실동작] `rawSn=888888` → **404** `rawSn=888888 영상이 존재하지 않습니다.` / `POST /v1/videos/888888/batch/retry` → 404. [정적] `loadRaw` `:149-153` | ⚠ `loadRaw` 는 **자기호출**이라 `@Transactional(REQUIRES_NEW, readOnly)` 가 발효되지 않는다(1차 B-ISSUE-06 미해소) → **B-ISSUE-23** |
| TC-BATCH-032 | process: 정상 전체 → COMPLETED | PASS | [실동작] rawSn **135·136·137·138·143** 5건 완주. 로그 `[BatchOrchestrator] completed rawSn=…`, `markRawDataCompleted`+`markCompleted`+`retryQueue.clear` 결과가 DB 에 반영(`ls_bat_rty_wtng` 잔여 0) | |
| TC-BATCH-033 | process: 단계 실패 → FAILED+재시도 큐 | PASS | [실동작] rawSn 128 → `FRAME_EXTRACT/FAILED` (`마킹 데이터가 없습니다. rawSn=128`), `LS_DATA_RAW=FAILED`, `ls_bat_rty_wtng` 신규 1행 `PENDING/rty_nmtm=1`, 로그 `willRetry=true` | |
| TC-BATCH-034 | process: 재시도 소진 후 FAILED 고정 | PASS | [실동작] 128 4회 연속 트리거 → `rty_nmtm` 1→2→3→**4**, `stts_cd=EXHAUSTED`, 로그 `max attempts exceeded … max=3` + `willRetry=false`, 반환 `FAILED` | 지수백오프 60/120/240s 로그로 확인 |
| TC-BATCH-035 | process: disabled stage skip(dev 토글) | **PARTIAL** | [정적] `BatchOrchestrator.java:120-124` + `isEnabled` 오버라이드 3종(Yolo:135 / Sam2:120 / Ffmpeg:107) + unit 2건(`Phase3_process_toggles_YOLO_off…`, `…FRAME_off…`). **[실동작] 검증 불가** — `process(Long, Map)` 오버로드를 호출하는 **프로덕션 코드가 0건**(호출자 5곳 전부 `process(Long)`=토글 null) | 단위 계층은 정상. integration 계층 도달 경로 부재 → **B-ISSUE-22** |
| TC-BATCH-036 | process: 토글 없음 → 전 stage enabled | PASS | [실동작] rawSn 136(토글 없는 기본 경로) → 프레임 **12** · VLM 시계열 메타 **15**(`LS_DATA_META` 21건 중 `video.*` 기술메타 6건 제외) · 라벨 **49** = MARKING→VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE 전 단계 실행. [정적] `BatchContext.java:78-85` | |
| TC-BATCH-037 | 두 테이블 분리: 배치완료 시 작업상태 ASSIGNED 복귀 | PASS | [실동작] 135/136/138/143 4건 모두 `LS_DATA_RAW=COMPLETED` ∧ `LS_RAW_DATA_STATUS=ASSIGNED`. [정적] `BatchTransitionService.java:146-158` | 근거 라인 `146-160` 은 메서드 끝(158) 초과 — 드리프트(경미) |
| TC-BATCH-038 | 작업상태 COMPLETED 점프 시 검수제출 차단 회귀 방지 | PASS | [실동작] rawSn 136: 배치 완료 후 `ASSIGNED` → `POST /v1/reviews/136/submit` **200** → `PENDING`. 작업상태 `COMPLETED` 는 `ReviewService.approve` 에서만 전이됨을 136 승인으로 재확인 | |
| TC-BATCH-039 | 배치실패: MARKING_READY 고착 방지 | PASS | [실동작] rawSn 128 `LS_DATA_RAW` `PENDING`→`FAILED`. 작업상태 FAILED 전이는 128 에 row 가 없어 미관측 → unit `markRawDataFailed_검수소유상태_제외_조건부UPDATE로_FAILED_전이`·`배치_실패_시_dataSttsCd_가_FAILED_로_전이된다` 로 커버 | |
| TC-BATCH-040 | 배치시작: 두 컬럼 PROCESSING 동시 전이 | PASS | [실동작] rawSn 143(배정 선행 → work=ASSIGNED): 마킹 직후 `LS_DATA_RAW=PROCESSING` ∧ `LS_RAW_DATA_STATUS=PROCESSING`(`ver` 1→2 = `UPDATE VERSIONED` 반영). 차단 아님(=`false` 반환) | |
| TC-BATCH-041 | 상태 row 부재 시 WARN(진행 계속) | PASS | [실동작] rawSn 128 로그 `[BatchTransition] raw data status not found rawSn=128 target=PROCESSING` / `… target=FAILED` 후 파이프라인 계속 진행. 파생 RAW(129~131)는 작업상태 row 자체가 없음도 재확인 | 근거 `:168-181` → 실제 `markRawDataMarkingReady` 는 `167-175` (드리프트) |
| TC-BATCH-042 | ★진입 가드: 검수 소유 상태면 SKIPPED + step 0건 | PASS | [실동작] §3 실측표 #1~#4 — 4개 상태 전부 `SKIPPED`, `ls_batch_proc_log` **0행**, `LS_DATA_RAW`·`upd_dt` 불변 | B-ISSUE-03 해소의 핵심 증거 |
| TC-BATCH-043 | 진입 가드 집합 = REVIEW_OWNED_STATUSES 4종 | PASS | [실동작] PENDING·APPROVED·REJECTED 는 API 로, IN_REVIEW 는 SQL 술어 롤백 프로브로 **UPDATE 0행** 확인. ASSIGNED 는 동일 프로브에서 **UPDATE 1행**(제외 확인). [정적] `BatchTransitionService.java:77-81` | 근거 라인 정확 |
| TC-BATCH-044 | 완료/실패 전이도 검수 소유면 LS_DATA_RAW 미변경 | PASS | [정적] `:151-154`(completed) · `:189-193`(failed) 조기 return + unit `검수소유상태면_markRawDataFailed도_LS_DATA_RAW를_FAILED로_바꾸지_않는다` | 진입 가드가 먼저 발화해 이 경로는 **런타임 도달 불가**(의도된 fail-closed 이중화). 근거 `:154-158,192-196` 은 실제 위치보다 3줄 뒤 — 드리프트 |
| TC-BATCH-045 | 진입 가드는 전 진입점 공통 관문 | PASS | [실동작] ①dev 트리거(SKIPPED) ②마킹 브리지(rawSn 134 — `batchTriggered=false`) ③수동 재처리(409 + 보상 롤백) 3경로. [정적] ④`BatchQuartzJob:51` ⑤`BatchRetryQuartzJob:54` 도 동일 `orchestrator.process(rawSn)` 경유 + 각자 SKIPPED 후처리(큐 소실 WARN / 재시도 엔트리 clear) | 5개 진입점 전수 확인 |

### 4-2. B-5. 마킹 완료 브릿지 (13건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-050 | 브릿지: 영상 미존재 → skip | PASS | [정적] `MarkingBatchBridge.java:98-103` + unit `영상_행_미존재시_스킵` | API 로는 마킹 자체가 404 라 도달 불가(리스너 단위 테스트가 정당한 계층) |
| TC-BATCH-051 | 브릿지: PROCESSING/COMPLETED 재트리거 차단 | PASS | [정적] `:81-82`(SKIP_BATCH_STAGES) `:109-114` + unit 2건(`배치_COMPLETED_영상에_마킹이벤트_재발생시…`, `배치_PROCESSING_중…`) | 마킹 프리컨디션(`MARKING_READY` 요구)이 선차단해 **API 경유 도달 불가** — 방어적 이중화 |
| TC-BATCH-052 | 브릿지: 비식별 미완료 트리거 차단 | PASS | [정적] `:116-123` + `MarkingBatchTriggerReport.REASON_NOT_DEIDENTIFIED` + unit 2건(deIdntfYn=N / =F) | 동상 도달 불가(프리컨디션 412 가 선차단 — [실동작] rawSn 127·134 로 412 확인) |
| TC-BATCH-053 | 브릿지: tx1 claim 성공 → 트리거 | PASS | [실동작] rawSn 143 — 배정으로 작업상태 row(ASSIGNED) 선생성 후 마킹 → `batchTriggered=true`, 로그 `enqueued rawSn=143`, `AsyncBatchRunner starting`, work `ver` 1→2 | tx1(조건부 UPDATE) 경로 실증 |
| TC-BATCH-054 | 브릿지: 미배정 REVIEWER — row 부재 시 생성 | PASS | [실동작] rawSn 135 — 작업상태 row **없음** 상태에서 REVIEWER 마킹 → row 신규 생성(`ver`=1) → PROCESSING → 배치 완료 후 ASSIGNED | tx2(`tryCreateBatchQueuedRow`) 경로 실증 |
| TC-BATCH-055 | 브릿지: 동시 노드 row 생성 경쟁 — 1건만 | PASS | [정적] `:139-145` catch(DataIntegrityViolationException) → `concurrent row creation … skipping` + IT `동시_2스레드_row부재_rawSn_클레임 — 정확히1건만_true…` | 2노드 재현은 단일 컨테이너 환경상 불가 → IT 로 대조 |
| TC-BATCH-056 | 브릿지: 동시 2 이벤트 중 1건만 BATCH_QUEUED | PASS | [실동작] rawSn 138 동시 3요청 → 마킹 1건만 생성되고 브리지 로그 `enqueued rawSn=138` **1회만**. [정적] `transitionToBatchQueuedIfNotSkipped` 단일 조건부 UPDATE(`LsRawDataStatusRepository:63-68`) | 근거 `:253-296` 은 `tryClaimBatchQueued`(253-261) 범위를 초과 — 드리프트 |
| TC-BATCH-057 | 브릿지: 이미 claimed면 skip | PASS | [실동작] rawSn 134 로그 `[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=134 — skipping` | |
| TC-BATCH-058 | tryCreateBatchQueuedRow: row 존재 → false 멱등 | PASS | [정적] `:304-307` `existsById` 조기 false + unit `tryCreateRow_row가_이미_존재하면_false_생성안함` | 근거 `:299-320` 은 메서드 끝(314) 초과 — 드리프트 |
| TC-BATCH-059 | 할당형 PK saveAndFlush 즉시 flush | PASS | [정적] `:312` `saveAndFlush` + catch 없음(전파) + unit `tryCreateRow_동시노드가_먼저insert_PK충돌시_DataIntegrityViolationException을_그대로_전파` | 근거 `:299-332` 는 메서드 끝(314) 을 18줄 초과 — 드리프트 |
| TC-BATCH-060 | 로그 인젝션 방어(CWE-117) | PASS | [정적] `MarkingBatchBridge.java:166-168` `replace("\n","").replace("\r","")` 존재, 적용 지점 `:111`(dataSttsCd) `:120`(deIdntfYn) | ⚠ **회귀 테스트 0건**(1차 B-ISSUE-26 미해소). 실제 주입 표면은 사실상 0(`de_ident_yn`=`varchar(1)`, `data_stts_cd` 는 상수 집합 매칭 성공 시에만 로깅) |
| TC-BATCH-061 | 브릿지 skip 집합이 진입 가드와 동일 상수 공유 | PASS | [실동작] rawSn 134(work=PENDING) → 입구에서 차단되어 `BATCH_QUEUED` 로 덮이지 않음(작업상태 `PENDING` 그대로). [정적] `:66-71` `Stream.concat(…, REVIEW_OWNED_STATUSES.stream())` | 입구·본체·출구 동일 기준 확인 |
| TC-BATCH-062 | 미트리거 사유가 마킹 응답에 실린다 | PASS | [실동작] rawSn 134 → **201** + `"batchTriggered":false, "batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다."`. 정상 경로(135/136/137/138/143)는 `true`/`null` | 사유 문구는 고정 상수만(CWE-209/117 준수) |

### 4-3. B-6. MarkingService (27건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-070 | 인가: actor null → UNAUTHORIZED | PASS | [실동작] 무토큰 `POST /v1/videos/126/markings` → **401** `UNAUTHORIZED` | 프로브 이전 거부 |
| TC-BATCH-071 | 인가: REVIEWER 전체 허용 | PASS | [실동작] REVIEWER 가 **미배정** 영상 135·136·137·138 에 마킹 성공(201) | |
| TC-BATCH-072 | 인가: 미배정 WORKER → FORBIDDEN(존재 미노출) | PASS | [실동작] 존재 영상 134(미배정) → **403** / 미존재 999999 → **403**(NOT_FOUND 아님). 대조군: 배정 영상 143 → 201 | 인가가 존재 확인보다 먼저 평가됨 확인 |
| TC-BATCH-073 | 인가 우선: 미배정 시 ffprobe 미실행 | PASS | [정적] `MarkingService.java:112` `precheckReader.precheck` 가 `:123` `durationResolver.resolveDurationSec` 보다 **앞**. + unit `미배정_WORKER_AUTO요청_프로브_미트리거되고_FORBIDDEN` | 프로브 실행 여부는 런타임 관측 불가 → 호출 순서 + 단위테스트로 판정 |
| TC-BATCH-074 | 프리컨디션: 영상 미존재 → NOT_FOUND | PASS | [실동작] REVIEWER + 999999 → **404** `영상을 찾을 수 없습니다.` (`MarkingGuards.java:80-82`) | |
| TC-BATCH-075 | 프리컨디션: 비식별 미완료 → 412 | PASS | [실동작] rawSn 127(`de_ident_yn='F'`) → **412**, rawSn 134(당시 `'N'`) → **412** | 근거 `:83-86` 정확 |
| TC-BATCH-076 | 프리컨디션: MARKING_READY 아님 → 412 | PASS | [실동작] rawSn 126(`COMPLETED`) → **412** `이미 처리된 영상은 재마킹할 수 없습니다.` | 근거 `:87-90` 정확 |
| TC-BATCH-077 | 프리컨디션: 이벤트유형 미지정 → INVALID_INPUT | PASS | [정적] `MarkingGuards.java:91-95` + unit 2건(null/blank) + `MarkingControllerTest.POST_이벤트유형_미지정_영상_400` | 실환경에 `EVNT_TYPE_CD` null 영상이 없어 실동작 미검증 |
| TC-BATCH-078 | AUTO: intervalFrames null/≤0 → INVALID_INPUT | PASS | [실동작] null·0·-5 3종 전부 **400** `자동 모드에서 intervalFrames 는 1 이상이어야 합니다.` | 근거 `:196-198` 정확 |
| TC-BATCH-079 | AUTO: durationSec null/≤0 → INVALID_INPUT(backstop) | PASS | [정적] `MarkingService.java:269-273` + unit 4건(`자동마킹_주입_durationSec_null…`, `…0이하면…`, `FIX_A_…backstop_INVALID_INPUT` 등) | 실환경 영상은 전부 `VDO_LEN_SEC` 보유라 backstop 미도달 |
| TC-BATCH-080 | AUTO: 정상 marks(실 fps, off-by-one) | PASS | [실동작] rawSn 136: `durationSec=113`, `fps=29.97002997`, `totalFrames=round(3386.61)=3387` → marks **12건** `0,300,…,3300`. **3600 은 미포함**(3387 초과) = `frameIndex < totalFrames` 확인. 타임스탬프 `300/29.97=10.01`→`00:10` | 30fps 가정이었다면 3390 프레임·타임스탬프 `00:10`(동일) — 아래 081 이 구분자 |
| TC-BATCH-081 | AUTO: 분수 fps(29.97) 반올림 | PASS | [실동작] MANUAL 상한 오류 문구 `허용 상한 **3417** 프레임 미만` = `round(113×29.97002997)+ceil(29.97)` = `3387+30`. **절단(3386+30=3416)과 값이 갈리므로 `Math.round` 실사용이 확정**. 30fps 폴백이었다면 3420 | ⚠ 단위테스트에 29.97 케이스 여전히 부재(1차 B-ISSUE-27-6 미해소) |
| TC-BATCH-082 | AUTO/MANUAL: fps pin 저장(TOCTOU 제거) | PASS | [실동작] `ls_marking.fps` = `29.97002997002997` — MANUAL(markingSn 7) · AUTO(8·9·10·11·13) 전건 저장 | 근거 `:191,216-218` 정확 |
| TC-BATCH-083 | MANUAL: marks 비면 INVALID_INPUT | PASS | [실동작] `marks` 생략 / `[]` 둘 다 **400** `수동 모드에서 marks 는 필수입니다.` | |
| TC-BATCH-084 | mode 미지 → INVALID_INPUT | PASS | [실동작] `"X"`·`"auto"`·`" AUTO "` 3종 전부 **400** — 대소문자·trim 미허용(fail-closed) 확인 | |
| TC-BATCH-085 | 생성 성공 → MarkingCompletedEvent 발행 | PASS | [실동작] 201 직후 동일 요청 스레드 로그 `[Marking] created rawSn=135, …` → `[MarkingBatchBridge] handling marking completed rawSn=135` | |
| TC-BATCH-086 | 이벤트명 자동소싱 = evntTypeCd | PASS | [실동작] 응답 `eventName="INTRUSION"` = `LS_DATA_RAW.EVNT_TYPE_CD` (`ls_marking.evnt_nm`도 동일) | 요청에 이벤트명을 보내지 않았음 |
| TC-BATCH-087 | persist self 프록시(AFTER_COMMIT 브릿지 보존) | PASS | [실동작] 브리지가 **커밋 이후·같은 http 스레드**에서 실행(`http-nio-8080-exec-1` 로그 연속) → `@Lazy self` 프록시가 실제로 트랜잭션 경계를 만들었음이 확인됨(자기호출이면 트랜잭션이 없어 AFTER_COMMIT 이 발화하지 않는다) | 이 프로젝트 실사고(프록시 우회) 대조 항목 |
| TC-BATCH-088 | durationSec 3단 폴백(VDO_LEN→메타→ffprobe) | PASS | [정적] `VideoDurationResolver.java:74-95`, `@Transactional(NOT_SUPPORTED)` 로 커넥션 미보유. 폴백 3단 구현 확인 | 실환경은 1단(VDO_LEN_SEC=113)에서 해결돼 2·3단 미도달 |
| TC-BATCH-089 | 컨트롤러: WORKER/REVIEWER만 생성 | PASS | [실동작] PORTAL_USER 토큰 → **403** `권한이 없습니다.` [정적] `MarkingController.java:51 @PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` | ⚠ 마킹 테스트군에 PORTAL 관련 단언 0건(`grep PORTAL marking/` → 0) |
| TC-BATCH-090 | ★rawSn 당 활성 마킹 1건 — 순차 재요청 409 | PASS | [실동작] rawSn 134 — 1회차 201(배치 미트리거로 마킹이 PENDING 유지) → 2회차 **409** `이미 진행 중인 마킹이 있습니다.`, `ls_marking(134)`=1행 | 정상 경로에서는 브리지가 즉시 stage 를 PROCESSING 으로 올려 2회차가 412 가 된다 — 409 를 관측하려면 브리지 skip 상황이 필요 |
| TC-BATCH-091 | 동시 마킹 — V142 부분 유니크 위반이 409 | PASS | [실동작] rawSn 138 동시 3요청 → **201 ×1 / 409 ×2**, `ls_marking(138)`=**1행**, 500 없음·부분 저장 없음. 로그 `[Marking] concurrent duplicate rejected rawSn=138`(DataIntegrityViolation 경로) 1건 + 사전조회 경로 1건 | 2선 방어(사전조회 + V142) 모두 실동작 확인 |
| TC-BATCH-092 | MANUAL: 요청 내 중복 frameIndex → 400 | PASS | [실동작] `[{10},{10}]` → **400** `중복된 마킹 시점입니다: frameIndex=10` | |
| TC-BATCH-093 | MANUAL: frameIndex 상한 = round(dur×fps)+ceil(fps) | PASS | [실동작] `frameIndex=999999999` → **400** `… (허용 상한 3417 프레임 미만)`. 하한: `-5` → 400(`MarkItem` Bean Validation) | 1초 마진 존재하나 과대값은 여전히 거부 — 케이스 기대와 일치 |
| TC-BATCH-094 | MANUAL: 길이 미상이면 상한 검증만 skip | PASS | [정적] `MarkingService.java:317-321` WARN + return(중복·하한은 위에서 이미 수행) + unit `영상길이를_알_수_없으면_상한만_스킵하고_하한과_중복은_그대로_400` | 실환경에 길이 미상 영상 없음 |
| TC-BATCH-095 | MANUAL 길이 해석은 프로브 없이 | PASS | [정적] `MarkingService.java:124-126` `resolveDurationSecWithoutProbe` 분기 + `VideoDurationResolver:110-124`(프로브 호출부 없음) + unit `MANUAL_모드는_사전확인통과해도_프로브_미트리거하고_마킹성공` | 구조적으로 프로브 불가(메서드에 `videoProbe` 호출 없음) |
| TC-BATCH-096 | AUTO intervalFrames 상한은 여전히 미검증 | PASS | [실동작] rawSn 137 `intervalFrames=999999999` → **201**, `marks`=`[{"frameIndex":0,"timestamp":"00:00"}]` 1건 → 배치 결과 `ls_data_src(137)`=**1프레임 · 라벨 0건**(대조: interval=300 인 136 은 12프레임/49라벨). 하한만 검증됨 | **케이스가 현재 동작을 고정**하므로 PASS. 1차 **B-ISSUE-23 미해소** 상태 그대로 |

---

## 5. 근거 드리프트

케이스 표의 `근거(file:line)` 와 HEAD `ca3c712b` 실제 위치의 차이. **전부 경미**(판정에 영향 없음)이나 다음 최신화에서 정정 권장.

| ID | 표기 근거 | 실제 위치 | 차이 |
|---|---|---|---|
| TC-BATCH-037 | `BatchTransitionService.java:146-160` | `146-158` (`markRawDataCompleted`) | 끝 2줄 초과 |
| TC-BATCH-038 | `:147-160` | `146-158` | 동상 |
| TC-BATCH-040 | `:110-124` | `110-122` (`markRawDataProcessingBlocked`) | 끝 2줄 초과 |
| TC-BATCH-041 | `:120-123,168-181` | `120`(WARN) · `167-175`(`markRawDataMarkingReady`) | 두 번째 범위 시작 1줄·끝 6줄 어긋남 |
| TC-BATCH-044 | `:154-158,192-196` | `151-154`(completed 조기 return) · `189-193`(failed) | 각 3줄 뒤로 밀림 |
| TC-BATCH-056 | `:253-296` | `253-261` (`tryClaimBatchQueued`) | 다음 메서드 Javadoc 까지 포함 |
| TC-BATCH-058 | `:299-320` | `299-314` (`tryCreateBatchQueuedRow`) | 끝 6줄 초과 |
| TC-BATCH-059 | `:299-332` | `299-314` | 끝 18줄 초과 |
| TC-BATCH-090 | `MarkingService.java:177` | `:178` (`requireNoActiveMarking` 호출) | 1줄 |

> 정확 일치 확인: TC-BATCH-030(`101-103`) · 043(`77-81`) · 060(`166-168`) · 075(`83-86`) · 076(`87-90`) · 077(`91-95`) · 078(`196-198`) · 085(`237`) · 086(`182`) · 092(`208`).

---

## 6. 이슈 상세

> **번호 체계**: 본 파일은 2차 part2 이며 지시에 따라 `B-ISSUE-21` 부터 채번한다. 1차(`docs/검증결과/2026-07-25/1차/ISSUES.md`)의 동명 ID(B-ISSUE-21~28)와는 **별개**다.

### [B-ISSUE-21] TC-BATCH-042 / TC-BATCH-061 / TC-BATCH-090 교차 — 검수 소유 상태 + `stage=MARKING_READY` 조합에서 영상이 **영구 고착**된다 (복구 경로 0)

- **심각도**: **HIGH** (영상 1건이 파이프라인에서 영구 이탈, 운영 복구 수단 없음)
- **기대 동작(기대효과)**: 진입 가드(B-ISSUE-03 수정)는 "검수 결과를 배치가 덮어쓰지 못하게" 하는 것이지, 영상을 되살릴 수 없는 상태로 만드는 것이 아니다. 어떤 상태 조합이든 운영자가 되돌릴 수 있는 경로(재마킹 · 재처리 · 배치 재트리거 중 최소 1개)가 남아 있어야 한다.
- **현재 동작(이슈 내용)**: `LS_DATA_RAW.DATA_STTS_CD='MARKING_READY'` 인데 `LS_RAW_DATA_STATUS.DATA_STTS_CD` 가 검수 소유 상태(PENDING/IN_REVIEW/APPROVED/REJECTED)가 되면, **마킹은 201 로 수락되지만 배치는 영원히 트리거되지 않고, 그 마킹이 활성으로 남아 재마킹까지 409 로 막힌다.** 세 방어가 서로를 잠근다.
  1. 브리지 입구 — `MarkingBatchBridge.java:66-71` `SKIP_STATUSES` 가 `REVIEW_OWNED_STATUSES` 를 포함 → `tryClaimBatchQueued` 실패, `tryCreateBatchQueuedRow` 도 row 존재로 false → `:150-153` 스킵.
  2. 오케스트레이터 본체 — `BatchOrchestrator.java:112-115` 진입 가드가 `SKIPPED` 반환(dev 트리거·Quartz 큐·재시도 잡 전부 동일).
  3. 마킹 재시도 — `MarkingGuards.java:109-114` `requireNoActiveMarking` 이 409. `LsMarking.ACTIVE_STATUSES = [PENDING, VLM_REQUESTED]` 인데, 이 마킹을 `VLM_FAILED`(종결)로 내려 줄 주체는 **VLM 단계뿐이고 그 단계가 돌지 않는다**.
  ```
  # 실측 (rawSn=134)
  stage=MARKING_READY  work=REJECTED  ls_marking(134)={markingSn:10, stts_cd:PENDING}
  POST /v1/videos/134/markings          → 409 CONFLICT "이미 진행 중인 마킹이 있습니다."
  POST /v1/dev/batch/trigger?rawSn=134  → 200 finalStage=SKIPPED (step 0건)
  POST /v1/videos/134/batch/retry       → 409 CONFLICT "배치가 실패(FAILED)한 영상만 재처리할 수 있으며…"
  ```
- **재현/확인 경로** (실행 순서 그대로 재현됨):
  ```bash
  # 1) MARKING_READY 영상(비식별 완료)을 배정하고, 배치 전에 검수 제출한다
  curl -X POST $BASE/v1/assignments -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[134]}'
  curl -X POST $BASE/v1/reviews/134/submit -H "Authorization: Bearer $WT"   # work: ASSIGNED→PENDING
  # 2) 그 뒤 마킹하면 201 이지만 배치는 시작되지 않는다
  curl -X POST $BASE/v1/videos/134/markings -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":600}'
  #    → 201 {"batchTriggered":false,"batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 …"}
  # 3) 반려해도(→REJECTED) 복구되지 않는다 — 위 3개 호출 전부 막힘
  ```
  ```sql
  select r.raw_sn, r.data_stts_cd stage, s.data_stts_cd work,
         (select count(*) from ls_data_src d where d.raw_sn=r.raw_sn) frames
    from ls_data_raw r join ls_raw_data_status s on s.raw_data_id=r.raw_sn
   where r.data_stts_cd='MARKING_READY'
     and s.data_stts_cd in ('PENDING','IN_REVIEW','APPROVED','REJECTED');
  ```
- **영향**:
  - 해당 영상은 **프레임 0건 · 라벨 0건 · 시계열 메타 0건**인 채로 검수 워크플로우 안에 남는다. REVIEWER 가 이를 승인하면 **내용이 없는 영상이 "검수 완료(작업 종결)"** 로 처리되고 `TASK_COMPLETED` 통지·export 대상이 된다(`V_COMPLETED_VIDEO` 는 `LS_DATASET_VIDEO_META`+`LS_RAW_DATA_STATUS='APPROVED'` 기준이라 승인만 되면 노출 경로가 열린다).
  - 반려해도 상태가 `REJECTED`(여전히 차단 집합)라 되돌아오지 않는다 — **단방향 함정**이다.
  - 유발 조건은 "배치 완료 전에 검수 제출"이며, `ReviewService.submit` 에 배치 단계 선행 조건이 없어 **정상 권한만으로 도달 가능**하다(WORKER 1회 호출).
  - 브리지 Javadoc(`MarkingBatchBridge.java:47-64`)은 "배치가 돌았던 영상의 재마킹은 프리컨디션(`MARKING_READY` 요구)에서 막히므로 REJECTED 유지는 무해하다"고 도달성 분석을 남겼으나, **배치가 한 번도 안 돈 채 검수 소유 상태가 된 조합**(같은 Javadoc 이 "도달 가능"이라 인정한 케이스)에 대해서는 회복 경로를 남기지 않았다.
- **수정 방향(제안)**: ① `ReviewService.submit` 에 배치 단계 선행 조건(`LS_DATA_RAW.DATA_STTS_CD='COMPLETED'`)을 두어 애초에 이 조합이 생기지 않게 하거나, ② 마킹 API 가 브리지 skip 을 관측하면(현재 이미 `batchTriggered=false` 로 알고 있다) 방금 만든 마킹을 종결(`VLM_FAILED`) 처리해 409 함정을 남기지 않거나, ③ `stage=MARKING_READY` 인 영상에 한해 수동 재처리(`/batch/retry`)를 허용하고 그 경로에서만 작업 상태를 `ASSIGNED` 로 되돌리는 관리자 복구 API 를 둔다. ⚠ **구현하지 않는다.**

### [B-ISSUE-22] TC-BATCH-035 — `BatchOrchestrator.process(Long, Map)` stage 토글 오버로드에 **프로덕션 호출자가 0건**

- **심각도**: LOW (죽은 API 표면 · 검증 불가)
- **기대 동작(기대효과)**: 케이스가 `integration` 계층으로 분류돼 있으므로 dev 경로에서 실제로 토글을 주입해 단계 skip 을 관측할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `orchestrator.process(` 호출자 전수(5곳) — `BatchDevTriggerController.java:119` · `BatchRetryQuartzJob.java:55` · `AsyncBatchRunner.java:25` · `BatchReprocessService.java:87` · `BatchQuartzJob.java:51` — 이 **전부 1인자 오버로드**를 쓰고, 그 오버로드는 `BatchOrchestrator.java:87` 에서 `process(rawSn, null)` 로 위임한다. 즉 `stageToggles` 가 non-null 이 되는 런타임 경로가 없다. 소비자는 테스트 5곳뿐이다.
  ```java
  // BatchOrchestrator.java:86-88
  public BatchStage process(Long rawSn) {
      return process(rawSn, null);
  }
  ```
- **재현/확인 경로**: `grep -rn "orchestrator.process" backend/src/main` → 5건 전부 1인자. `grep -rn "process(.*,.*)" backend/src/main .../BatchOrchestrator.java` → 자기 위임 1건뿐.
- **영향**: 기능 결함은 아니다(`isEnabled` 기본값 `true` 라 프로덕션 동작은 100% 보존). 다만 ①`BatchStep.isEnabled` 오버라이드 3종(`YoloAutolabelStep:135`·`Sam2SegmentStep:120`·`FfmpegFrameExtractor:107`)과 `BatchContext.stageToggles` 전체가 테스트 전용 코드로 남아 있고, ②TC-BATCH-035 를 명세대로 실동작 검증할 수단이 없다.
- **수정 방향(제안)**: dev 트리거(`/v1/dev/batch/trigger`)에 선택적 `stages` 파라미터를 붙여 실제로 사용하거나, 사용 계획이 없으면 오버로드·토글·`isEnabled` 오버라이드를 제거한다. ⚠ **구현하지 않는다.**

### [B-ISSUE-23] TC-BATCH-031 — `BatchOrchestrator.loadRaw` 가 자기호출이라 `@Transactional(REQUIRES_NEW, readOnly)` 가 발효되지 않는다 (1차 B-ISSUE-06 미해소)

- **심각도**: LOW (현재 기능 영향 없음 · 장식적 애너테이션 = 향후 회귀 위험)
- **기대 동작(기대효과)**: `process()` 는 무-트랜잭션이므로 영상 메타 조회를 "짧은 readOnly REQUIRES_NEW"로 격리한다는 주석(`BatchOrchestrator.java:83-84`)대로 실제 트랜잭션이 열려야 한다.
- **현재 동작(이슈 내용)**: `:104` 가 `LsDataRaw raw = loadRaw(rawSn);` 로 **자기호출**하고 `:147-149` 의 선언은
  ```java
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected LsDataRaw loadRaw(Long rawSn) { … }
  ```
  Spring AOP 프록시를 우회하므로 이 애너테이션은 적용되지 않는다. 현재는 `videoRepository.findById` 가 Spring Data 의 자체 트랜잭션으로 동작해 결과가 같아 무해하다.
- **재현/확인 경로**: [실동작] 미존재 rawSn 조회는 여전히 NOT_FOUND 로 정상 동작(`888888` → 404) — 즉 **증상이 없어 테스트로 잡히지 않는다.** [정적] `BatchOrchestrator.java:104` vs `:147-149`.
- **영향**: 이 프로젝트는 동일 패턴(`@Transactional` 자기호출로 경계 유실)으로 **배치 전면 불통 사고**를 낸 전례가 있다(`tests-green-runtime-broken-selfcall-tx`). 여기서 `loadRaw` 에 쓰기·락·격리 요구가 추가되는 순간 조용히 깨진다.
- **수정 방향(제안)**: `loadRaw` 를 별도 빈(`BatchRawLoader`)으로 분리하거나, 애너테이션을 제거해 "트랜잭션 없음"을 사실대로 표기한다. 클래스패스 스캔 가드(자기호출 `@Transactional` 탐지)에 이 지점을 추가한다. ⚠ **구현하지 않는다.**

### [B-ISSUE-24] TC-BATCH-060 / TC-BATCH-081 / TC-BATCH-089 — 회귀 테스트 공백 3종 (1차 이월 2건 + 신규 1건)

- **심각도**: LOW (방어·계약의 조용한 소실 위험)
- **기대 동작(기대효과)**: 보안 방어(로그 sanitize)·경계 계산(분수 fps)·역할 인가(PORTAL 차단)는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**:
  1. **CWE-117 sanitize**(1차 B-ISSUE-26 이월) — `MarkingBatchBridge.java:166-168` 의 `sanitize()` 를 검증하는 테스트가 `MarkingBatchBridgeTest`(15케이스) 에 없다. `grep -niE "sanitize|CWE-117|\\n|\\r"` → **0건**. 누군가 로그 문장을 리팩터링하며 `sanitize()` 를 빼도 잡히지 않는다.
  2. **분수 fps 반올림**(1차 B-ISSUE-27-6 이월) — `MarkingServiceTest` 는 fps 25/30/60 만 검증한다. `generateAutoMarks`/`manualFrameIndexLimit` 의 `Math.round` 가 절단으로 바뀌어도(실측상 상한이 3417→3416 으로 1 줄어드는 회귀) 테스트가 잡지 못한다. `grep -rn "29.97" backend/src/test` → 마킹 테스트군 **0건**.
  3. **PORTAL_USER 마킹 차단**(신규) — `MarkingController.java:51` 의 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 를 PORTAL 역할로 검증하는 테스트가 없다. `grep -rn "PORTAL" backend/src/test/java/kr/co/cudo/authoring/marking/` → **0건**. 실동작으로는 403 확인됨.
- **재현/확인 경로**: 위 3개 grep.
- **영향**: 세 항목 모두 **현재 구현은 정상**이며 실동작으로 확인했다. 리팩터링 시 무증상 소실 위험만 남는다.
- **수정 방향(제안)**: ①`dataSttsCd="PROC\nING"` 로그 캡처 단언 1건 ②`generateAutoMarks(10, 30, 29.97)` + `manualFrameIndexLimit(113, 29.97)==3417` 단언 ③`MarkingControllerTest` 에 PORTAL 토큰 403 케이스 1건. ⚠ **구현하지 않는다.**

---

## 7. 부기 (판정 대상은 아니나 기록)

- **`@Async` 배치 풀 특성**: `AsyncConfig.java:37-48` — core 2 / max 4 / queue 50 / `CallerRunsPolicy`. 큐(50)가 차기 전에는 스레드가 2개를 넘지 않으므로 **실효 동시 배치 = 2**이며, 선두 비식별(`AsyncDeidentifyRunner`)과 같은 풀을 공유한다. 실측에서 영상 5건 동시 비식별이 2스레드로 직렬화돼 약 2분 걸렸다. 경계·역압은 정상 설계이며 결함이 아니다(Quartz 1건/분 목표 처리량과 정합). 예외 삼킴은 `AsyncBatchRunner:32-34` 에서 `log.error` 로 드러난다 — 무음 아님.
- **`ls_batch_proc_log` 는 단계 이력이 아니다**: `BatchStatusService.markStage:41-47` 이 최신 1행을 `updateStage` 로 **덮어쓴다**. 따라서 완주한 영상의 로그에는 `COMPLETED/COMPLETED` 1행만 남고 단계별 진행 이력은 조회되지 않는다(SKIPPED/FAILED 감사 행은 별도 적재). TC-BATCH-036 검증에 DB 로그를 쓸 수 없어 산출물(프레임 12 · 메타 21 · 라벨 49)로 판정했다.
- **PIPE-ISSUE-02 재확인**: 마킹 201 응답의 `videoPath` 가 여전히 **원본 경로**(`/app/storage/raw/seed/sample-cctv-1080p.mp4`)이며 `ls_marking.video_file_path_nm` 도 동일하다(rawSn 134~138·143 전건). `pipeline-drive.md` 의 기존 관측과 일치하므로 중복 채번하지 않는다.
- **self-fill 없음**: 본 구간에서 신규 발생한 외부 연동은 KPST 비식별 6건뿐이며 전부 mock-server 실왕복(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 에 mock 이 산출한 `-mask.mp4` 경로 기록)이다. VLM 도 rawSn 135~138·143 배치에서 실왕복해 시계열 메타가 채워졌다(각 **15건**, 별도로 적재 시 ffprobe 유래 `video.*` 6건). 배치가 돌지 않은 134 는 `video.*` 6건만 있고 시계열 메타 **0건** — 자체 생성이 아니라 실제 VLM 왕복 결과임을 보여주는 대조군이다. 값을 자체 생성한 지점은 발견되지 않았다.
