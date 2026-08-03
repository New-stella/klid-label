# B 클러스터 검증 — part2 (B-3 DeidentifyStep · B-4 BatchOrchestrator 상태 전이)

- **대상**: `docs/test-cases/B-batch-deidentify.md` § **B-3**(TC-DEID-010~025, 16건) + § **B-4**(TC-BATCH-030~045, 16건) = **32건**
- **검증일**: 2026-08-01 / 1차
- **코드 기준**: 워크트리 `qa-0801` (56d30478)
- **실행 스택**: `klid-backend`(V146 빌드, `local` 프로파일) · `klid-mock-server:9400` · `klid-postgres`(`public` 스키마)

## 집계

| 판정 | 건수 |
|---|--:|
| PASS | 30 |
| PARTIAL | 2 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **32** |

> **환경 버전 격차 영향 없음**: 담당 구간(DeidentifyStep · BatchOrchestrator · BatchTransitionService)은 `LS_DATA_INGEST` 도입(커밋 6c8a5303 등, V147+) 이전부터 존재한 로직이며, 세 파일의 워크트리 소스와 실행 중 컨테이너 동작이 실측에서 전부 일치했다(SKIPPED 반환·보상 롤백·EXHAUSTED 전이 등 최신 DEV_FIX 동작이 전부 라이브에서 관측됨). 따라서 **BLOCKED 0건**.

---

## 이번 검증에서 만든 실동작 근거 (재현 가능)

### 1) 관제 적재 → 선두 비식별(KPST 실왕복) — B-3용
```bash
# 관제 공유 클립 2건 시드(정상 원본 1 / 원본 부재 1)
docker exec klid-postgres psql -U klid_user -d klid_system -c "
INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, lclgv_cd, file_nm, file_path, file_fmt,
                             vdo_len_sec, clip_stts_cd, crt_dt, job_dmnd_yn, vms_cctv_id)
VALUES ('QA0801-EVT-01','ORIGINAL','QA0801-CLIP-01','11110','clip-9103.mp4',
        './storage/raw/seed/clip-9103.mp4','mp4',5000,'mediainfo_complete',CURRENT_TIMESTAMP,'Y','CCTV-QA1'),
       ('QA0801-EVT-02','ORIGINAL','QA0801-CLIP-02','11110','missing.mp4',
        './storage/raw/seed/qa-missing-xyz.mp4','mp4',5000,'mediainfo_complete',CURRENT_TIMESTAMP,'Y','CCTV-QA2');"
curl -s -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REV"   # → data:2
```
결과(실측):

| rawSn | 원본 | `LS_DATA_RAW` | `DE_IDENT_YN` | `LS_DEIDENT_PROC_LOG` |
|--:|---|---|:--:|---|
| 31 | 실재 | `PENDING` → **`MARKING_READY`**(약 20~60s, 폴링 완료 시점) | `N` → **`Y`** | `proc_log_sn=30` `SUCCEEDED`/`DOWNLOADED`, `de_idntf_pjt_id=16`, `de_idntf_file_path_nm=/app/storage/raw/seed/31/deid/clip-9103-mask.mp4` |
| 32 | 부재 | `PENDING` **유지**(MARKING_READY 미전이) | **`F`** | `proc_log_sn=31` `FAILED`, `err_cd=KPST_SOURCE_MISSING`, `reg_id=batch-deident-fail` |

mock-server 로그(실왕복 실증):
```
[MOCK][KPST] project created prj_id=16 name=raw31 creator=authoring   ← POST /project 200
[MOCK][KPST] watermark burned ... file=clip-9103-mask.mp4
[MOCK][KPST] production completed prj_id=16 files=1                    ← GET /retrieve_progress 200
```
→ rawSn=32 는 **mock-server 에 아무 요청도 나가지 않았다**(project 생성 로그 없음) = 제출 이전 `verifySourceOrFail` 차단.
→ 산출 파일명이 `{stem}-mask.mp4`(KPST 명명)이고 mock 상수 `deidentified.mp4` 가 아니다 = **self-fill 아님**.

**self-fill 점검**: `SELECT reg_id, proc_stts_cd, count(*) FROM ls_deident_proc_log GROUP BY 1,2` →
`batch/SUCCEEDED 15` · `batch-deident-fail/FAILED 7` · `aug-frame-extract 5` · `resolution-derivative 3`.
**`batch-mock` 행 0건** — 이 DB 에서 `DeidentifyStep.runMock`(자체 복사 = self-fill) 이 실행된 적이 한 번도 없다. 로컬 스택 실효값 `DEIDENTIFY_MOCK_MODE=false`(stack-bringup §2)와 정합.

### 2) 진입 가드 매트릭스 — B-4용
검수 소유 4상태 + 대조군 2종을 신규 행으로 만들어 dev 트리거로 실사격.
```bash
docker exec klid-postgres psql -U klid_user -d klid_system -c "
INSERT INTO ls_data_raw (raw_sn,vms_clip_id,vms_cctv_id,prvc_type_cd,prvc_yn,de_ident_yn,
                         raw_file_path_nm,data_stts_cd,reg_dt,vdo_len_sec) VALUES
 (9201,'QA0801-B4-9201','CCTV-QA','ANONY','N','Y','./storage/raw/seed/clip-9103.mp4','MARKING_READY',CURRENT_TIMESTAMP,5), ... (9206까지);
INSERT INTO ls_raw_data_status (raw_data_id,data_stts_cd,stp_cycl,igi_cycl,upd_dt,ver) VALUES
 (9201,'APPROVED',0,0,CURRENT_TIMESTAMP,0),(9202,'ASSIGNED',...),(9204,'IN_REVIEW',...),
 (9205,'REJECTED',...),(9206,'PENDING',...);   -- 9203 은 작업상태 row 없음"
for R in 9201 9204 9205 9206 9202 9203; do
  curl -s -X POST "http://localhost:18081/api/v1/dev/batch/trigger?rawSn=$R" -H "Authorization: Bearer $REV"; done
```

| rawSn | 작업상태(전) | 응답 `finalStage` | `LS_DATA_RAW`(후) | 작업상태(후)/`ver` | `LS_BATCH_PROC_LOG` | `LS_DATA_SRC` |
|--:|---|:--:|---|---|--:|--:|
| 9201 | APPROVED | **SKIPPED** | `MARKING_READY`(미변경) | APPROVED / **0** | 0 | 0 |
| 9204 | IN_REVIEW | **SKIPPED** | `MARKING_READY`(미변경) | IN_REVIEW / **0** | 0 | 0 |
| 9205 | REJECTED | **SKIPPED** | `MARKING_READY`(미변경) | REJECTED / **0** | 0 | 0 |
| 9206 | PENDING | **SKIPPED** | `MARKING_READY`(미변경) | PENDING / **0** | 0 | 0 |
| 9202 | ASSIGNED | FAILED | `FAILED` | FAILED / 2 | 1 | 0 |
| 9203 | (row 없음) | FAILED | `FAILED` | (row 없음) | 1 | 0 |

`ver=0` 유지 = `UPDATE VERSIONED` 가 한 번도 발화하지 않음 = 조건부 UPDATE 가 술어에서 걸러졌다는 직접 증거.

backend 로그:
```
WARN BatchTransitionService - [BatchTransition] work status transition skipped (review-owned) rawSn=9201 current=APPROVED target=PROCESSING
WARN BatchOrchestrator      - [BatchOrchestrator] skipped — review-owned work status rawSn=9201
WARN BatchTransitionService - [BatchTransition] raw data status not found rawSn=9203 target=PROCESSING   ← row 부재는 차단 아님
```

### 3) 정상 완주(두 테이블 책임 분리) — TC-BATCH-032/037/038
```bash
curl -s -X POST /api/v1/assignments -d '{"workerId":2001,"rawDataIds":[31],"reviewerId":1001}'   # work=ASSIGNED(ver=1)
curl -s -X POST /api/v1/videos/31/markings -d '{"mode":"AUTO","intervalFrames":30}'              # 마킹 완료 → 브리지 → 배치
```
결과: `LS_DATA_RAW.data_stts_cd=COMPLETED` · `LS_RAW_DATA_STATUS.data_stts_cd=**ASSIGNED**`(ver=3) · `LS_DATA_SRC` 2건.
이어서 `POST /api/v1/reviews/31/submit`(WORKER) → `dataSttsCd=PENDING`(ver=4) **정상 수락** — 배치 완료가 작업상태를 COMPLETED 로 점프시키지 않아 검수 제출이 막히지 않음.

### 4) 수동 재처리 경로의 가드 + 보상 롤백 — TC-BATCH-045
```bash
docker exec klid-postgres psql ... -c "UPDATE ls_raw_data_status SET data_stts_cd='APPROVED' WHERE raw_data_id=9202;"
curl -s -X POST http://localhost:18081/api/v1/videos/9202/batch/retry -H "Authorization: Bearer $REV"
# → {"success":false,"errorCode":"CONFLICT","message":"검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다."}
# LS_DATA_RAW: FAILED → (클레임)PROCESSING → (보상)FAILED  ← 고착 없음
```
로그: `reprocess claim compensated (PROCESSING->FAILED) rawSn=9202`.

### 5) 재시도 소진 — TC-BATCH-034
rawSn=9203 을 4회 트리거: `rty_nmtm` 1→2→3→**4**, `stts_cd` `PENDING`→**`EXHAUSTED`**(`max_rty_nmtm=3`, 삭제 아닌 이력 보존), 지수백오프 `delaySec=60→120→240` 로그 관측.

---

## B-3. DeidentifyStep (mock / KPST / 설정오류) — 16건

| ID | 판정 | 근거 확인 | 실제 file:line | 비고 |
|---|:--:|---|---|---|
| TC-DEID-010 | PASS | [정적] | `DeidentifyStep.java:182-190,205-221` | `mockMode` 일 때만 `assertMockAllowedProfile`. allowlist `Set.of("local","dev","stg")`(L95) 에 수렴하지 않으면 `IllegalStateException`. 커버: `DeidentifyStepTest#prd_프로파일에서_mock활성시_부팅거부`, `#prd와_dev가_섞인_active프로파일이면_거부`, `#local과_prd가_섞인_active프로파일이면_거부`(baseline 전량 통과). 반증 시도 — 혼합 프로파일·비표준 라벨(`production`)·대문자·공백 모두 거부되는 것을 테스트가 이미 커버 |
| TC-DEID-011 | PASS | [정적] | `DeidentifyStep.java:210,216-221` | `noActiveProfile = active.length == 0` → 3중 OR 로 예외. Spring 은 active 미설정 시 빈 배열을 반환하므로 default 프로파일만 있는 형상도 거부(fail-closed). 커버: `#active프로파일_미설정이면_거부` |
| TC-DEID-012 | PASS | [정적] | `DeidentifyStep.java:223-227` | `localActive` 아니면 WARN 1줄. 커버: `#dev_프로파일에서_mock활성시_부팅허용`, `#stg_프로파일에서_mock활성시_부팅허용`, `#dev_프로파일_mock활성_부팅시_WARN로그_1줄` |
| TC-DEID-013 | PASS | [정적] | `DeidentifyStep.java:207-214` | `env.trim()` + `toLowerCase(Locale.ROOT)` 후 allowlist 대조. `ENV` blank/미설정은 통과(프로파일로만 판정). 커버: `#ENV가_prd면_..._거부`, `#ENV가_PRD_대문자여도_거부`, `#ENV가_공백포함_prd여도_거부`, `#비표준_환경라벨_production이면_거부` |
| TC-DEID-014 | PASS | [정적] | `DeidentifyStep.java:270-272` | `raw == null` → `INVALID_INPUT`. 커버: `#raw_null이면_INVALID_INPUT` |
| TC-DEID-015 | PASS | [정적] | `DeidentifyStep.java:275-277` | `if (mockMode) return completed(runMock(raw));` 가 KPST 분기(L280)보다 **앞**이라 mock 활성 시 외부 미접촉. 커버: `#mock모드_원본존재시_외부호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다`. ⚠ 현 로컬 스택은 `DEIDENTIFY_MOCK_MODE=false` 라 이 분기가 **라이브 미도달**(그것이 정상 형상 — `batch-mock` procLog 0건으로 확인) |
| TC-DEID-016 | **PASS** | **[실동작]** | `DeidentifyStep.java:280-283` · `KpstDeidentService.java:286,446-466` | rawSn=31: `submit` → mock-server `POST /project`(prj_id=16) → `deferred` 반환 → **즉시 MARKING_READY 미전이**, 폴링 완료 후 `MARKING_READY`+`'Y'`. rawSn=32(원본 부재): `verifySourceOrFail` 이 **외부 호출 전** 차단 → `recordDeidentFailure`(별도 REQUIRES_NEW) 커밋 → `'F'` + `err_cd=KPST_SOURCE_MISSING`, mock-server 무접촉 |
| TC-DEID-017 | PASS | [정적] | `DeidentifyStep.java:286-288` | `kpstEnabled && kpstDeidentService != null` 미충족 시 고정 메시지 `"비식별 경로가 구성되지 않았습니다."` + `INTERNAL_ERROR`(레거시 폴백 없음, 내부 경로 미노출 — CWE-209). 커버: `#KPST_disabled_이고_mock도_아니면_레거시폴백_없이_설정오류_예외`, `#kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외`, `DeidentifyStepKpstDisabledIntegrationTest` |
| TC-DEID-018 | PASS | [정적] | `DeidentifyStep.java:309-314` | `source == null \|\| !Files.isRegularFile(source)` → `batchTransitionService.recordDeidentFailure(...)`(별도 빈 REQUIRES_NEW) 후 `EXTERNAL_API_ERROR` throw → MARKING_READY 미전이. **동형 경로의 라이브 증거**: KPST 축 rawSn=32 에서 `recordDeidentFailure` 가 호출측 롤백과 독립 커밋됨을 실측(`batch-deident-fail` procLog + `'F'`). 커버: `#mock모드_원본부재시_성공위장없이_recordDeidentFailure_+_MARKING_READY_미전이`, `DeidentifyStepFailurePersistenceIntegrationTest` |
| TC-DEID-019 | PASS | [정적] | `DeidentifyStep.java:330-335` | `catch (IOException)` → `recordDeidentFailure(MOCK_SOURCE_MISSING, e.getClass().getSimpleName())` + `INTERNAL_ERROR`. 예외 원문/경로 미노출. 추가 분기 2종 존재(카탈로그 미기재, 결함 아님): base 거부 `L319-327`, 쓰기 직전 TOCTOU 재검증 거부 `L336-343` — 둘 다 `MOCK_TARGET_BASE_REJECTED` 로 fail-secure |
| TC-DEID-020 | PASS | [정적] | `DeidentifyStep.java:345-365` | `markDeidentified("Y")` + `procLog.succeed(target)` + `workLockService.releaseRaw`(잠금 시) + `deidentReportService.resolveOpenReports` + `notificationService.notifyReviewersOnLockRelease` + `streamMetaCacheEvictor.evictAfterCommit`. 커버: `#mock모드_재비식별_잠금영상_성공시_releaseRaw_+_resolveOpenReports_+_알림`, `#mock모드_비식별완료시_stream-meta_캐시가_실제로_무효화된다` |
| TC-DEID-021 | PASS | [정적] | `DeidentifyStep.java:385-407` | `createDirectories` → `verifyRealPathUnder`(TOCTOU 재확인) → tmp 복사 → `ATOMIC_MOVE + REPLACE_EXISTING`, `AtomicMoveNotSupportedException` 시 replace 폴백, `finally deleteIfExists(tmp)`. 커버: `#mock모드_재실행시_atomic_move로_멱등하게_덮어쓰고_손상되지_않는다` |
| TC-DEID-022 | **PARTIAL** | [정적] | `DeidentifyStep.java:418-421` · `VideoArtifactRootResolver.java:424-444` | 방어 자체는 **fail-secure 로 정상 동작**(세그먼트 `..`/`/`/`\` → `INVALID_INPUT`, base 이탈 + 실경로 이탈 → **`FORBIDDEN`**). 다만 케이스 기대값 `INVALID_INPUT` 은 **base 이탈 경로의 실제 코드와 다르다**(→ B-ISSUE-25). 도달 불가 판단은 정확(세그먼트가 `rawSn:Long` 과 상수 `deidentified.mp4` 뿐) |
| TC-DEID-023 | PASS | [정적] | `DeidentifyStep.java:239-250` | `execute` 에 `@Transactional` **없음**(확인) + `selfProvider.getObject().run(...)` 프록시 경유, provider null 이면 `this` 폴백. `run` 만 `REQUIRES_NEW`(L268). 커버: `BatchStepTransactionBoundaryTest#결함1_자기참조_프록시로_경계를_얻는_단계는_execute에_애노테이션이_없어야_한다_중첩방지`, `DeidentifyStepExecutePersistenceIntegrationTest` |
| TC-DEID-024 | PASS | [정적] | `DeidentifyStep.java:249` · `BatchContext.java:125-127` | `ctx.markDeidentCompleted(result.completed())`. 소비처 `AsyncDeidentifyRunner:86-98` 이 `ctx.isDeidentCompleted()` 일 때만 `markRawDataMarkingReady` — 라이브 rawSn=31 에서 deferred → 즉시 미전이, 폴링 완료 후 전이로 **간접 실증** |
| TC-DEID-025 | PASS | [정적] | `BatchStepTransactionBoundaryTest.java:53,88-90,107-113` | `BOUNDARY_EXEMPT = Set.of(DeidentifyStep.class, MarkingLoadStep.class)` — 면제 등재 + 사유(자기참조 프록시)가 테스트 주석에 명문화, 역방향 단언(`execute` 에 애노테이션이 있으면 실패)까지 존재 |

## B-4. BatchOrchestrator 상태 전이 (정상/실패/진입 가드) — 16건

| ID | 판정 | 근거 확인 | 실제 file:line | 비고 |
|---|:--:|---|---|---|
| TC-BATCH-030 | PASS | [정적] | `BatchOrchestrator.java:101-103` | `rawSn == null` → `INVALID_INPUT`. HTTP 진입점은 `@Min(1)` 이 선차단(방어심도). 커버: `BatchOrchestratorTest#rawSn_null이면_INVALID_INPUT` |
| TC-BATCH-031 | PASS | [정적] | `BatchOrchestrator.java:149-153` | `findById` empty → `NOT_FOUND`. 라이브에서도 미존재 rawSn 은 dev 트리거가 404 로 선차단 |
| TC-BATCH-032 | **PASS** | **[실동작]** | `BatchOrchestrator.java:112-135` | rawSn=31 마킹 완료 → 가드 통과 → step 루프 → `markRawDataCompleted` + `markCompleted` + `retryQueue.clear` → `COMPLETED`. `LS_DATA_RAW=COMPLETED`, `LS_DATA_SRC` 2건 실측. rawSn=26(pipeline-drive) 도 동일 |
| TC-BATCH-033 | **PASS** | **[실동작]** | `BatchOrchestrator.java:136-144` | rawSn=9202/9203 — `markFailed` + `markRawDataFailed` + `enqueueIfRetryable`. 로그 `[BatchOrchestrator] failed rawSn=9202 willRetry=true cause=CustomException`, `LS_BAT_RTY_WTNG` 행 생성 |
| TC-BATCH-034 | **PASS** | **[실동작]** | `BatchOrchestrator.java:140-143` · `BatchRetryQueue.java:78-84` | rawSn=9203 4회 트리거 → `rty_nmtm=4 > max 3` → `stts_cd=EXHAUSTED`(행 삭제 아님), 응답 `finalStage=FAILED` 고정 |
| TC-BATCH-035 | **PARTIAL** | [정적] | `BatchOrchestrator.java:120-124` · `FfmpegFrameExtractor.java:107-109` | skip 로직 자체는 정상이고 단위테스트(`BatchOrchestratorTest#Phase3_process_toggles_FRAME_off면...`)가 커버. **다만 `process(Long, Map)` 오버로드의 프로덕션·dev 호출자가 0건**이라 이 경로는 실행 코드에서 도달 불가(→ B-ISSUE-23). Javadoc 이 말하는 "dev 단일 파이프라인 수렴 경로"는 `DevPipelineRunner` 에서 이미 폐지됨 |
| TC-BATCH-036 | PASS | [실동작] | `BatchContext.java:65-85` | `stageToggles == null → Map.of()`, `isStageEnabled` 키 미존재/값 null → `true`. 라이브 전 경로가 `process(rawSn)` → `BatchContext(rawSn, raw)` 이며 rawSn=31 에서 MARKING→VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE 전 단계 실행 확인 |
| TC-BATCH-037 | **PASS** | **[실동작]** | `BatchTransitionService.java:146-158` | rawSn=31 배치 완료 직후 `LS_DATA_RAW.data_stts_cd=COMPLETED` · `LS_RAW_DATA_STATUS.data_stts_cd=ASSIGNED`(ver=3). 두 테이블 책임 분리 확인 |
| TC-BATCH-038 | **PASS** | **[실동작]** | `BatchTransitionService.java:147-158` | 위 상태에서 `POST /v1/reviews/31/submit`(WORKER) → 200 `dataSttsCd=PENDING`(ver=4). 배치 완료가 작업상태를 COMPLETED 로 점프시켰다면 상태머신이 ASSIGNED→PENDING 을 거부했을 것 — 회귀 없음 |
| TC-BATCH-039 | **PASS** | **[실동작]** | `BatchTransitionService.java:184-197` | rawSn=9202 배치 실패 후 `LS_DATA_RAW=FAILED` + 작업상태 `FAILED`. MARKING_READY 고착 없음 |
| TC-BATCH-040 | **PASS** | **[실동작]** | `BatchTransitionService.java:110-122` | rawSn=9202(ASSIGNED, 검수 소유 아님) → 차단 안 됨(파이프라인 진입) + 작업상태 `ver` 이 0→2 로 2회 증가(PROCESSING → FAILED) = 두 컬럼 동시 전이 실증 |
| TC-BATCH-041 | **PASS** | **[실동작]** | `BatchTransitionService.java:118-122,167-175,409-419` | rawSn=9203(작업상태 row 부재) → 예외 없이 `WARN [BatchTransition] raw data status not found rawSn=9203 target=PROCESSING/FAILED` 후 진행, `LS_DATA_RAW` 는 정상 전이. `markRawDataMarkingReady` 도 동일 패턴(`ifPresentOrElse` WARN) |
| TC-BATCH-042 | **PASS** | **[실동작]** | `BatchOrchestrator.java:112-115` · `BatchTransitionService.java:110-122` | APPROVED/IN_REVIEW/REJECTED/PENDING 4종 전부 `finalStage=SKIPPED`, **`LS_BATCH_PROC_LOG` 0건 · `LS_DATA_SRC` 0건 = step 미실행**, `LS_DATA_RAW` 미변경, 작업상태 `ver` 미증가 |
| TC-BATCH-043 | **PASS** | **[실동작]** | `BatchTransitionService.java:77-81` | 상수 4종 확인 + 라이브 4종 전부 차단, **ASSIGNED(9202)는 통과**해 배치가 실제로 돌았다 = 집합 경계가 코드·동작 양면에서 일치. 커버: `BatchTransitionServiceTest#검수소유상태_차단집합은_PENDING_IN_REVIEW_APPROVED_REJECTED_4종이고_ASSIGNED는_제외된다` |
| TC-BATCH-044 | PASS | [정적] | `BatchTransitionService.java:152-154,191-193` | `markRawDataCompleted`/`markRawDataFailed` 둘 다 `if (transitionRawDataStatus(...)) return;` 로 즉시 반환 → `LS_DATA_RAW` 미변경(불일치쌍 금지). 진입 가드 때문에 정상 경로에서 도달 불가하나 fail-closed 로 존치. 커버: `BatchTransitionServiceTest#검수소유상태면_markRawDataFailed도_LS_DATA_RAW를_FAILED로_바꾸지_않는다`. **인접 라이브 근거**: 수동 재처리(클레임이 가드보다 먼저 `LS_DATA_RAW` 를 선점하는 유일 경로)에서 SKIPPED → `releaseReprocessClaim` 보상으로 `PROCESSING→FAILED` 복원 실측 |
| TC-BATCH-045 | **PASS** | **[실동작+정적]** | `BatchOrchestrator.java:112-115` | `orchestrator.process()` 호출자 **정확히 5곳**(전수: `BatchDevTriggerController:119` · `BatchRetryQuartzJob:55` · `AsyncBatchRunner:25`(마킹 브리지) · `BatchQuartzJob:51` · `BatchReprocessService:87`) — 전부 동일 가드를 통과. 라이브 실증 3/5: ①dev 트리거(9201/9204/9205/9206 SKIPPED) ②마킹 브리지(rawSn=31 정상 완주) ③수동 재처리(9202 → 409 + 보상 롤백). 나머지 2(Quartz 큐·재시도 잡)는 같은 메서드를 호출하므로 동형. ⚠ **주의(결함 아님)**: 개별 스텝 재호출 경로(`VlmWithheldResumeRunner:69-71`, `TrackEditService`/`TrackMergeService` 의 `trackInterpolationStep`)는 오케스트레이터를 경유하지 않아 이 가드 밖이다 — 둘 다 `CLAUDE.md` 가 명시 허용한 설계(신고 해소 시 VLM 재위탁 / 승인 후 트랙 편집 + 재export)이므로 본 케이스의 반례가 아님 |

---

## 이슈

### [B-ISSUE-21] TC-DEID-010~024 — B-3 섹션 근거 `file:line` 이 전 항목 드리프트(약 +10행)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 `근거(file:line)` 는 검증자가 즉시 해당 코드를 열어 대조할 수 있어야 한다. 회차 간 재검증에서 같은 위치를 다시 찾는 비용이 근거의 가치다.
- **현재 동작(이슈 내용)**: B-3 의 16건 중 **15건**(TC-DEID-010~024)의 라인 번호가 실제와 어긋난다. 오프셋이 대체로 +10 전후로 균일해 **주석 블록 추가에 의한 일괄 시프트**로 보인다.

  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 010 | `:87,178-215` | `:182-190,205-221`(+ allowlist 상수 `:95`) |
  | 011 | `:202,208` | `:210,216-221` |
  | 012 | `:216-218` | `:223-227` |
  | 013 | `:205-208` | `:207-214` |
  | 014 | `:259-262` | `:270-272` |
  | 015 | `:265-267` | `:275-277` |
  | 016 | `:269-273` | `:280-283` |
  | 017 | `:274-278` | `:286-288` |
  | 018 | `:296-306` | `:309-314` |
  | 019 | `:319-325` | `:330-335` |
  | 020 | `:319-350` | `:345-365` |
  | 021 | `:375-400` | `:385-407` |
  | 022 | `:408-420` | `:418-421` |
  | 023 | `:232-241` | `:239-250` |
  | 024 | `:241` | `:249` |

  (대조군: **B-4 의 16건은 라인 번호가 정확**하다 — `BatchOrchestrator.java:101-103`, `BatchTransitionService.java:77-81/184-197/110-124` 등 전부 일치. 즉 드리프트는 `DeidentifyStep.java` 한 파일에 국한된다.)
- **재현/확인 경로**: `sed -n '178,215p' backend/src/main/java/kr/co/cudo/authoring/batch/step/DeidentifyStep.java` → 카탈로그가 지목한 "mock 부트 게이트"가 아니라 필드 선언·생성자 영역이 나온다.
- **영향**: 기능 영향 없음. 카탈로그 자체의 정합성 결함(검증 비용 증가·오독 위험).
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` B-3 섹션의 근거 컬럼을 위 표의 "실제" 값으로 일괄 정정. 차기 최신화 시 `DeidentifyStep.java` 는 라인 대신 **메서드명 앵커**(예: `DeidentifyStep#assertMockAllowedProfile`)로 표기하면 재드리프트를 구조적으로 줄일 수 있다.

### [B-ISSUE-22] TC-BATCH-045 — 동일 rawSn 에 대한 `BatchOrchestrator.process()` 동시 중복 실행이 어디에서도 차단되지 않는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `BatchOrchestrator` 클래스 Javadoc 이 **"영상 단위 직렬 호출 보장 — `process(Long)` 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다"**(`BatchOrchestrator.java:50-51`)고 단언한다. 파이프라인은 프레임 추출·YOLO/SAM2 라벨 적재 등 **비멱등 INSERT** 를 수행하므로, 같은 rawSn 에 두 실행이 겹치면 프레임/AUTO 라벨이 중복 적재되고 상태 전이가 서로를 덮어쓴다. 2노드 Active-Active 형상에서는 JVM 락이 방어가 되지 않아 DB 수준의 클레임이 필요하다.
- **현재 동작(이슈 내용)**: `process()` 진입부에는 **검수 소유 상태 가드 하나뿐**이고, `PROCESSING` 은 `REVIEW_OWNED_STATUSES` 에 없으므로 이미 실행 중인 영상도 통과한다.
  ```java
  // BatchOrchestrator.java:112-115
  if (transitionService.markRawDataProcessingBlocked(rawSn)) {   // 검수 소유 4상태만 차단
      log.warn("[BatchOrchestrator] skipped — review-owned work status rawSn={}", rawSn);
      return BatchStage.SKIPPED;
  }
  ```
  ```java
  // BatchTransitionService.java:77-81  (PROCESSING·BATCH_QUEUED·ASSIGNED·FAILED 는 차단 대상 아님)
  public static final Set<String> REVIEW_OWNED_STATUSES = Set.of(
          STTS_PENDING, STTS_IN_REVIEW, STTS_APPROVED, STTS_REJECTED);
  ```
  개별 진입점의 클레임(`tryClaimBatchQueued` / 재시도 큐 `PENDING→RETRYING` CAS / `tryClaimReprocessFromFailed`)은 **서로 다른 락**이라 진입점이 다르면 교차 방어가 되지 않고, dev 트리거의 `PROCESSING` 사전 검사(`BatchDevTriggerController.java:84-87`)는 read-then-act 라 TOCTOU 다.

  **라이브 실측(동일 진입점 4-way 동시 발사)**:
  ```
  # 작업상태 row 부재(파생 RAW 형상)
  $ for i in 1 2 3 4; do (curl -sX POST ".../v1/dev/batch/trigger?rawSn=9203" -H "Auth...") & done
  → 4건 모두 200 / finalStage=FAILED  (409·SKIPPED 0건)
  # 작업상태 row 존재(ASSIGNED)
  $ for i in 1 2 3 4; do (curl -sX POST ".../v1/dev/batch/trigger?rawSn=9202" ...) & done
  → 4건 모두 200,  ls_raw_data_status.ver  2 → 10 (=4×PROCESSING + 4×FAILED, 8회 UPDATE)
  → backend 로그 "[BatchOrchestrator] marking check rawSn=9202" 4줄 = 파이프라인이 4회 병렬 실행됨
  ```
  이번 검체는 마킹이 없어 1단계에서 실패해 부수효과가 없었지만, **마킹이 있는 영상이면 프레임 추출·오토라벨 적재가 4중으로 수행**된다.
- **재현/확인 경로**: 위 셸 스니펫 그대로(마킹이 있는 rawSn 을 쓰면 `SELECT count(*) FROM ls_data_src WHERE raw_sn=?` 가 프레임 수의 배수로 늘어난다).
- **영향**: 데이터 정합(프레임·AUTO 라벨 중복 적재, 상태 전이 경합) · CWE-362(Race Condition) · CWE-1223(비원자 check-then-act). 2노드 Active-Active + Quartz 클러스터링 형상에서 **Quartz 는 트리거 중복 발화만 막고 잡 내부 레이스는 막지 않는다**(`CLAUDE.md` 명시)는 전제와 정확히 맞물리는 미방어 구간.
- **수정 방향(제안)**: `process()` 진입부의 `markRawDataProcessingBlocked` 를 **"진입 클레임"** 으로 승격하는 방향. 예 — `LS_DATA_RAW.DATA_STTS_CD` 를 `PROCESSING` 이 **아닐 때만** `PROCESSING` 으로 바꾸는 조건부 UPDATE(영향 행수 1 인 호출만 진행, 0 이면 `SKIPPED`)를 추가하고, 정상 종료 3경로(COMPLETED/FAILED/예외)에서 반드시 해제되도록 보장. 클레임 도입 시 **stale PROCESSING 회수 스윕**(`BatchRetryStaleReclaimSweeper` 와 동일 패턴)이 함께 필요하다 — 노드 사멸 시 영구 고착 위험. ⚠ 구현은 하지 않음.

### [B-ISSUE-23] TC-BATCH-035 — `process(Long, Map)` stage 토글 오버로드의 호출자가 0건(도달 불가 경로)
- **심각도**: LOW
- **기대 동작(기대효과)**: `BatchOrchestrator.process(Long, Map)` Javadoc 은 *"토글 대상 단계(FRAME_EXTRACT/YOLO/SAM2)가 off 면 … **dev 단일 파이프라인 수렴 경로에서 사용한다**"*(`BatchOrchestrator.java:91-99`)라고 용도를 명시한다. 케이스 TC-BATCH-035 도 이를 전제로 "dev 토글" 시나리오를 검증 대상으로 잡고 있다.
- **현재 동작(이슈 내용)**: 프로덕션·dev 를 통틀어 이 오버로드를 호출하는 코드가 **한 곳도 없다**.
  ```
  $ grep -rn "\.process(" backend/src/main/java/ | grep -i orchestrator
  BatchDevTriggerController.java:119:  orchestrator.process(rawSn);
  BatchRetryQuartzJob.java:55:         orchestrator.process(rawSn);
  AsyncBatchRunner.java:25:            orchestrator.process(rawSn);
  BatchQuartzJob.java:51:              orchestrator.process(rawSn);
  BatchReprocessService.java:87:       orchestrator.process(rawSn);
  ```
  과거 호출자였던 `DevPipelineRunner` 는 클래스 주석대로 *"합성 마킹 생성과 `BatchOrchestrator.process` 직접 호출은 폐지되었으며"*(`DevPipelineRunner.java:24-27`) 오버로드 사용을 접었고, 오버로드와 `BatchContext.stageToggles`·`BatchStep.isEnabled` 오버라이드 3종(`FfmpegFrameExtractor:107` / `YoloAutolabelStep:135` / `Sam2SegmentStep:120`)만 남았다. 단위테스트(`BatchOrchestratorTest:355-423`, `BatchContextToggleTest`)가 살아 있어 커버리지 지표상으로는 드러나지 않는다.
- **재현/확인 경로**: 위 grep. 실행 중 스택에서 stage 토글을 태울 수 있는 REST/Quartz 진입점이 존재하지 않는다(`BatchDevTriggerController` 는 `rawSn` 만 받는다).
- **영향**: 기능 영향 없음(프로덕션 경로 100% 보존이 설계 의도이므로 회귀도 아님). 다만 **테스트만 존재하는 미사용 분기**로 남아 유지보수 시 "지원되는 기능"으로 오인될 수 있고, 케이스 TC-BATCH-035 는 실동작으로 검증할 대상이 없다.
- **수정 방향(제안)**: 둘 중 하나로 정리 — ①dev 토글이 여전히 필요하면 `BatchDevTriggerController.trigger` 에 optional `stages` 파라미터를 붙여 진입점을 복구(`@Profile("!prd")` 안이므로 노출 위험 없음), ②불필요하면 오버로드·`BatchContext.stageToggles`·3개 `isEnabled` 오버라이드·관련 단위테스트를 함께 제거하고 카탈로그에서 TC-BATCH-035/036 을 폐기 표기. ⚠ 구현은 하지 않음.

### [B-ISSUE-24] TC-BATCH-031 — `protected @Transactional loadRaw` 3곳이 자기호출이라 애노테이션이 무효(의도-구현 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: `BatchOrchestrator.process` Javadoc 이 *"process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), **영상 메타 조회를 위한 짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리**"*(`BatchOrchestrator.java:83-84`)라고 선언한다. 즉 `loadRaw` 는 실제로 `REQUIRES_NEW readOnly` 경계를 열어야 한다.
- **현재 동작(이슈 내용)**: 애노테이션이 **두 가지 이유로 동시에 무효**다. ①`process()` 가 `this.loadRaw(rawSn)` 로 자기호출해 Spring AOP 프록시를 우회한다 ②`AnnotationTransactionAttributeSource` 는 기본이 `publicMethodsOnly=true` 라 **`protected` 메서드는 외부 호출이어도 어드바이스 대상이 아니다**.
  ```java
  // BatchOrchestrator.java:104
  LsDataRaw raw = loadRaw(rawSn);          // ← 자기호출(프록시 우회)
  ...
  // BatchOrchestrator.java:147-153
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected LsDataRaw loadRaw(Long rawSn) { ... }   // ← protected + self-invoke = 사문화
  ```
  동일 패턴이 `AsyncDeidentifyRunner.java:110-117`, `DevPipelineRunner.java:88-95` 에도 있다(주석에 *"BatchOrchestrator.loadRaw 패턴"* 이라 명시돼 **의도적으로 복제 전파**됐다).
  이 코드베이스는 같은 함정을 이미 두 번 크게 겪었다 — `DeidentifyStep` 의 `selfProvider` 도입(`DeidentifyStep.java:116-126`)과 `BatchTransitionService` 별도 빈 분리(`BatchTransitionService.java:24-29`)가 모두 이 문제의 사후 수정이다.
- **재현/확인 경로**: `logging.level.org.springframework.transaction=TRACE` 로 dev 트리거를 호출하면 `loadRaw` 구간에 `Creating new transaction` 로그가 없고, `SimpleJpaRepository.findById` 자체의 `readOnly` 트랜잭션만 열렸다 닫힌다.
- **영향**: **현재 기능 영향은 없다** — `SimpleJpaRepository` 가 자체 `@Transactional(readOnly=true)` 를 갖고 있어 조회는 정상 동작하고, `LsDataRaw` 는 연관 매핑(`@OneToMany`/`@ManyToOne`)이 전무한 평면 엔티티라 detached 상태로 스텝에 전달돼도 lazy 초기화 예외가 나지 않는다(실측: rawSn=31 정상 완주). 위험은 **미래**에 있다 — `LsDataRaw` 에 연관을 추가하거나 `loadRaw` 에 조회를 하나 더 붙이는 순간 경계가 없어 `LazyInitializationException` 또는 커넥션 2회 왕복이 조용히 생긴다. 또 주석이 사실과 달라 후속 개발자를 오도한다.
- **수정 방향(제안)**: 세 곳 모두 ①`loadRaw` 를 별도 조회 빈(예: `BatchRawLookupService`)의 `public @Transactional(readOnly)` 메서드로 이동해 프록시를 경유시키거나, ②`DeidentifyStep` 이 이미 쓰는 `ObjectProvider<Self>` 패턴을 적용. 정리 후 `BatchStepTransactionBoundaryTest` 와 같은 **정적 가드**(“`@Transactional` 이 `private`/`protected` 메서드에 붙어 있으면 실패”)를 추가하면 재발을 구조적으로 막을 수 있다. ⚠ 구현은 하지 않음.

### [B-ISSUE-25] TC-DEID-022 — 케이스 기대 ErrorCode(`INVALID_INPUT`)가 base 이탈 실제 코드(`FORBIDDEN`)와 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 *"출력 경로 순회 방어(CWE-22) — base 이탈 → **INVALID_INPUT**"* 을 단언한다. 회차 간 재검증에서 응답/예외 코드가 판정 기준이므로 기대값이 코드와 일치해야 한다.
- **현재 동작(이슈 내용)**: 방어는 정상 동작하나 **코드가 두 축으로 갈린다**.
  ```java
  // VideoArtifactRootResolver.java:424-444 (resolveUnder)
  if (segment == null || segment.isBlank()
          || segment.contains("/") || segment.contains("\\") || segment.contains("..")) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 경로 세그먼트입니다.");   // ① 세그먼트 축
  }
  ...
  if (!target.startsWith(base)) {
      throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");           // ② base 이탈 축
  }
  if (!realOrNearest(target).startsWith(realOrNearest(base))) {
      throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");           // ③ 실경로(심링크) 축
  }
  ```
  즉 `INVALID_INPUT` 은 **세그먼트 검증 전용**이고, 케이스가 지목한 **"base 이탈"은 `FORBIDDEN`** 이다. `coLocateVideoRoot` 의 원본 덮어쓰기 차단(`VideoArtifactRootResolver.java:253-255`)도 `FORBIDDEN` 이다. 케이스의 "실제 도달 불가한 방어심도 분기"라는 판단 자체는 정확하다 — `resolveSafeTargetPath` 가 넘기는 세그먼트는 `rawSn:Long` 과 상수 `deidentified.mp4` 뿐이다.
- **재현/확인 경로**: `VideoArtifactRootResolver.resolveUnder(Paths.get("/nas/deid"), "..", "etc")` → `INVALID_INPUT` / `resolveUnder` 에 base 밖으로 해석되는 심링크를 물리면 → `FORBIDDEN`.
- **영향**: 코드 결함 아님(양쪽 다 fail-secure). 카탈로그 기대값 정합성 문제이며, 그대로 두면 차기 회차에서 "FORBIDDEN 이 떴으니 FAIL" 이라는 오판을 유발할 수 있다.
- **수정 방향(제안)**: TC-DEID-022 기대결과를 *"세그먼트 위반 → `INVALID_INPUT` / base·실경로 이탈 → `FORBIDDEN` (둘 다 기본 루트 폴백 없이 fail-secure)"* 로 분리 기술. ⚠ 구현은 하지 않음.

---

## 참고 — 이번 검증이 남긴 테스트 데이터 (기존 데이터 보존, 신규 행만 추가)

| 테이블 | 추가 행 | 용도 |
|---|---|---|
| `mng_clip_master` | `QA0801-EVT-01`, `QA0801-EVT-02` | 관제 적재 스캔 트리거 |
| `ls_data_raw` | `raw_sn=31`(COMPLETED/Y), `32`(PENDING/F), `9201~9206` | B-3 KPST 실왕복 · B-4 진입 가드 매트릭스 |
| `ls_raw_data_status` | `31`(PENDING), `9201`(APPROVED), `9202`(FAILED), `9204`(IN_REVIEW), `9205`(REJECTED), `9206`(PENDING) | 상태 전이 검증 |
| `ls_bat_rty_wtng` | `raw_sn=9203`(EXHAUSTED) | 재시도 소진 검증 |

> 기존 행은 수정하지 않았다(9202 의 작업상태만 본 검증이 생성한 행 내에서 ASSIGNED↔APPROVED 로 조작). 코드·설정·테스트 파일 수정 0건, 빌드/테스트 실행 0건.
