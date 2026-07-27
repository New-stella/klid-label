# B 클러스터 1부 — 배치 파이프라인 + 비식별화 (B-1 ~ B-4)

> 대상: `docs/test-cases/B-batch-deidentify.md` 5~77행 (53 케이스)
> 검증일: 2026-07-25 / 1차 / 실행 스택: klid-postgres · klid-backend(:18081) · klid-ai-server(:19300) · klid-mock-server(:9400) · klid-frontend(:13000)
> 런타임 프로파일: `SPRING_PROFILES_ACTIVE=local`, `DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_ENABLED=true`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400`
> DB 스키마: `public` (klid_at 아님)

## 검증 중 실제로 구동한 라이브 시나리오

`POST /api/v1/dev/batch/scan`(REVIEWER 토큰) 로 관제 학습용 픽업 적재를 실행해 **적재 → VideoIngestedEvent → 브릿지 → 비식별 러너 → KPST 위탁(목업서버) → 폴링 완료 → MARKING_READY** 전 구간이 실제 스택에서 1회 완주했다. 이어 마킹 완료가 배치를 트리거해 **FRAME_EXTRACT 실패 → FAILED 전이 + 재시도 큐 등록**까지 관측됐다. 아래 표의 "실동작" 근거는 모두 이 구동에서 나온 로그/DB/목업서버 인바운드 기록이다.

핵심 라이브 타임라인(backend 로그 + mock-server 로그 + DB):

```
10:39:34.191 [TrainingIngest] ingested clipId=DEV-CLIP-9101 rawSn=23   (24/25 동일)
10:39:34.196 [IngestDeidentifyBridge] video ingested rawSn=23 — triggering deidentify   ← AFTER_COMMIT
10:39:34.196 [AsyncDeidentifyRunner] starting deidentify rawSn=23                        ← batch-async-2 (@Async)
10:39:34.251 [KpstDeid] submitted rawSn=23 prjId=3                                       ← mock-server POST /project 200
10:39:34.263 [AsyncDeidentifyRunner] deidentify submitted (deferred) rawSn=23 — MARKING_READY 는 폴링 완료 시 전이
10:39:34.206 [TrainingIngest] scan finished scanned=3 ingested=3
10:40:02.338 [KpstDeid] completed rawSn=23 → de_ident_yn='Y', data_stts_cd=MARKING_READY
10:40:07.516 [TrainingIngest] scan finished scanned=3 ingested=0                          ← 멱등 재스캔
10:41:06.226 [AsyncBatchRunner] starting batch rawSn=23
10:41:06.258 [BatchOrchestrator] failed rawSn=23 willRetry=true cause=CustomException
             → LS_DATA_RAW=FAILED, LS_RAW_DATA_STATUS=FAILED, LS_BAT_RTY_WTNG rty_nmtm=1/3 PENDING
```

## B-1. 관제 학습용 적재 (스캔 → 적재 → 이벤트)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-001 | 스캔: 학습용 지정 클립 없음 | PASS | 정적 `TrainingVideoIngestService.java:53-57` — null/empty 모두 0 반환. 리포지토리 쿼리(`MngClipMasterRepository:30-35`)가 `jobDmndYn=:Y AND filePath IS NOT NULL AND TRIM<>''` 로 후보를 좁힘 | `TrainingVideoIngestServiceTest#doesNotDelegateForNonTrainingClips`, `#nullScanResultIsHandledSafely` | 외부 쓰기 없음(REQUIRES_NEW 진입 자체 없음) |
| TC-BATCH-002 | 스캔: 신규 클립 N건 적재 카운트 | PASS | **실동작** — `scan finished scanned=3 ingested=3`, `ls_data_raw` rawSn 23/24/25 신규 3행. 클립별 `ingestOne` INFO 3줄 | `TrainingVideoIngestServiceTest#delegatesIngestForTrainingDesignatedClip`, `#mixedResultsCountedCorrectly` | 성능 갭은 B-ISSUE-04 |
| TC-BATCH-003 | 스캔: 1건 실패가 다른 클립 막지 않음 | PASS | 정적 `TrainingVideoIngestService.java:60-69` — 클립별 try/catch(RuntimeException) + REQUIRES_NEW 경계 분리(`TrainingVideoIngestTx.java:78`) | `TrainingVideoIngestServiceTest#partialFailureDoesNotBlockOtherClips` | 로그에 evntId/clipId만(PII 미출력) 확인 |
| TC-BATCH-004 | 적재: clipId blank → skip | PASS | 정적 `TrainingVideoIngestTx.java:82-85` | `TrainingVideoIngestTxTest#skipsClipWithNullClipId` | |
| TC-BATCH-005 | 적재: vmsCctvId blank → skip | PASS | 정적 `TrainingVideoIngestTx.java:88-92`. DB 실측 `ls_data_raw.vms_cctv_id NOT NULL` 확인 | `#skipsClipWithNullVmsCctvId`, `#skipsClipWithBlankVmsCctvId` | |
| TC-BATCH-006 | 적재: filePath blank → skip | PASS | 정적 `TrainingVideoIngestTx.java:94-98` (2차 방어 — 1차는 리포지토리 쿼리에서 이미 제외) | `#skipsClipWithBlankFilePath`, `#skipsClipWithNullFilePath` | 이벤트 미발행 = save 자체 미수행이므로 성립 |
| TC-BATCH-007 | 적재: 멱등 1차 — 이미 적재 clipId skip | PASS | **실동작** — 2회차 스캔 `scanned=3 ingested=0`, `POST /v1/dev/batch/scan` 응답 `data:0`, 중복 INSERT 0 | `#skipsAlreadyIngestedClipByClipId` | |
| TC-BATCH-008 | 적재: 멱등 2차 — 동시 race UK 위반 흡수 | PASS | 정적 `TrainingVideoIngestTx.java:122-126`. DB 실측 UK `uk_ls_data_raw_vms_clip(vms_clip_id)` 존재 | `#treatsUniqueViolationAsDuplicateSkip` | 예외 미전파(false 반환) |
| TC-BATCH-009 | 적재: 정상 → PENDING + 이벤트 발행 | PASS | **실동작** — 적재 직후 `data_stts_cd=PENDING`, 같은 http 스레드에서 `[IngestDeidentifyBridge] video ingested rawSn=23` | `#publishesEventOnIngest` | |
| TC-BATCH-010 | 적재 매핑: evntTypeCd EVNT_ID 조인 | PASS | **실동작** — `mng_clip_evnt_lst(DEV-EVT-9101, INTRUSION, 2026-07-25 10:36:03.236202)` → `ls_data_raw(23).evnt_type_cd=INTRUSION`, `sht_dt=2026-07-25 10:36:03.236202` 완전 일치(CRT_DT 폴백 아님) | `#mapsEvntTypeAndShtDtFromEvntLst` | |
| TC-BATCH-011 | 적재 매핑: 이벤트리스트 미매칭 폴백 | PASS | 정적 `TrainingVideoIngestTx.java:105-109` | `#fallsBackWhenEvntLstNotMatched` | |
| TC-BATCH-012 | 적재 매핑: prvcTypeCd=ANONY 고정 | PASS | **실동작** — 23/24/25 전부 `prvc_type_cd=ANONY`. 정적 `:64, :115` | (표 전반 검증에 포함) | |
| TC-BATCH-013 | durationSec 변환: ms→초 반올림 | PASS | **실동작** — `mng_clip_master.vdo_len_sec=30000`(ms) → `ls_data_raw.vdo_len_sec=30`(초) | `#convertsVdoLenMillisToSeconds`, `#roundsHalfSecondUpToOne` | |
| TC-BATCH-014 | durationSec 변환: null → null | PASS | 정적 `:142-143` | `#keepsDurationNullWhenVdoLenNull` | |
| TC-BATCH-015 | durationSec 변환: 1초 미만 → null | PASS | 정적 `:145-146` | `#keepsDurationNullForSubSecondThatRoundsToZero`, `#keepsDurationNullWhenVdoLenZero` | |
| TC-BATCH-016 | 스캔 잡: 동시 tick 차단 | **PARTIAL** | 정적 `ControlTrainingVideoScanJob.java:21-22` 애너테이션 존재 ✅. 그러나 `application.yml:72 org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}` + `deploy/onprem/config/backend/env.template:175 QUARTZ_CLUSTERED=false` → **2노드 Active-Active에서 두 노드 동시 발화 미차단** | `ControlTrainingVideoScanJobTest#hasDisallowConcurrentExecutionAnnotation` | **B-ISSUE-02** / UNCERTAINTY #8 확정 |
| TC-BATCH-017 | 스캔 잡: 예기치 못한 실패 안전망 | PASS | 정적 `ControlTrainingVideoScanJob.java:40-44` — catch→ERROR, 예외 미전파(misfire 방지) | `ControlTrainingVideoScanJobTest#scanFailureIsAbsorbed` | 로그에 예외 클래스명만(CWE-209 준수) |
| TC-BATCH-018 | 스캔 트리거: 60초 간격 등록 | PASS(정적) | 정적 `ControlTrainingVideoScanTriggerConfig.java:37-48` — `startAt(now+30_000ms)` + `withIntervalInSeconds(60 기본)` + `repeatForever` | — | 로컬은 잡 비활성(아래)이라 런타임 확인 불가 |
| TC-BATCH-019 | 스캔 트리거: enabled=false 미등록 | PASS | **실동작** — `application-local.yml:52-55 authoring.control.training-scan.enabled=false` 상태에서 `qrtz_job_details` 실측 결과 등록 잡 3건(`datasetExportPendingSweepJob`/`kpstDeidentPollJob`/`bootstrapJob`)뿐, `controlTrainingVideoScanJob` **부재** | — | `@ConditionalOnProperty` 정상 동작 확증 |

## B-2. 선두 비식별 브릿지 + 러너 (VideoIngested → Deidentify)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-DEID-001 | 브릿지: AFTER_COMMIT에서만 발화 | PASS | **실동작** — `ingested clipId=... rawSn=23`(10:39:34.191, ingestOne REQUIRES_NEW 커밋) **직후** 같은 http 스레드에서 `[IngestDeidentifyBridge] ... rawSn=23`(.196), 이어 `batch-async-2` 로 러너 이관. 클립 3건 모두 커밋→발화 순서 유지 | `IngestDeidentifyBridgeTest#onVideoIngested_delegatesToRunner` | 정적 `IngestDeidentifyBridge.java:26-31` |
| TC-DEID-002 | 브릿지: 적재 롤백 시 비식별 미트리거 | PASS(정적) | `@TransactionalEventListener(phase=AFTER_COMMIT)` (`:26`) — `fallbackExecution` 기본 false 라 롤백/무트랜잭션 발행 시 리스너 미호출 | — | 롤백 강제 주입 경로가 없어 실동작 미검증(스프링 계약 근거) |
| TC-DEID-003 | 러너: raw 미존재 skip | PASS | 정적 `AsyncDeidentifyRunner.java:58-62` | `AsyncDeidentifyRunnerTest#rawNotFoundNoOp` | |
| TC-DEID-004 | 러너: 동기 완료(mock) → MARKING_READY | PASS | 정적 `:71-73` | `AsyncDeidentifyRunnerTest#mockSyncCompletedTransitionsMarkingReady` | 런타임 `mockMode=false` 라 실동작 미해당(설계상 정상) |
| TC-DEID-005 | 러너: KPST 지연(deferred) → 미전이 | PASS | **실동작** — 제출 직후 로그 `deidentify submitted (deferred) rawSn=23 — MARKING_READY 는 폴링 완료 시 전이`, 이 시점 DB `data_stts_cd=PENDING` 유지. 28초 뒤 폴링(`[KpstDeid] completed rawSn=23`)이 MARKING_READY 로 단일 전이 | `#kpstDeferredDoesNotTransitionMarkingReady`, `#executeWithoutCompletionSignalDoesNotTransition` | 조기 전이 차단 확증 |
| TC-DEID-006 | 러너: 실패 시 예외 삼킴 + 재시도 큐 미사용 | PASS | 정적 `:78-85` + 생성자(`:46-53`)에 `BatchRetryQueue` 의존 자체가 없어 구조적으로 enqueue 불가 | `#failureNoMarkingReadyNoRetryQueue` | |
| TC-DEID-007 | 러너: rawSn null → loadRaw empty | PASS | 정적 `:91-95` (null 가드가 메서드 본문 내부라 프록시 여부와 무관하게 동작) | — | `loadRaw` 자기호출로 `@Transactional` 미적용 — B-ISSUE-06 |

## B-3. DeidentifyStep (mock / KPST / 설정오류)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-DEID-010 | mock 부트 게이트: prd 차단 | PASS | 정적 `DeidentifyStep.java:150-152, 168-184` — allowlist(local/dev/stg) fail-closed | `DeidentifyStepTest#prd_프로파일에서_mock활성시_부팅거부`, `#prd와_dev가_섞인_active프로파일이면_거부`, `#local과_prd가_섞인_active프로파일이면_거부` | |
| TC-DEID-011 | mock 부트 게이트: 프로파일 없음 거부 | PASS | 정적 `:173, :179` (`noActiveProfile` → throw) | `#active프로파일_미설정이면_거부` | |
| TC-DEID-012 | mock 부트 게이트: dev/stg 허용 + 비-local WARN | PASS | 정적 `:186-190` | `#dev_프로파일에서_mock활성시_부팅허용`, `#stg_프로파일에서_mock활성시_부팅허용`, `#dev_프로파일에서_mock활성_부팅시_비식별경고_WARN로그를_1줄_남긴다` | |
| TC-DEID-013 | mock 부트 게이트: ENV=prod 거부 | PASS | 정적 `:170-177` (trim + 소문자 정규화 후 allowlist 대조) | `#ENV가_prd면_mock활성시_부팅거부`, `#ENV가_PRD_대문자여도_거부`, `#ENV가_공백포함_prd여도_거부`, `#비표준_환경라벨(production)이면_거부` | |
| TC-DEID-014 | run: raw null → INVALID_INPUT | PASS | 정적 `:231-233` | `DeidentifyStepTest#raw_null이면_INVALID_INPUT` | |
| TC-DEID-015 | run: mock 경로 → completed | PASS | 정적 `:236-238` (KPST 분기보다 앞 — 외부 미접촉) | `#mock모드_원본존재시_외부호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다` | 런타임 mockMode=false |
| TC-DEID-016 | run: KPST 위탁 → deferred | **PARTIAL** | **실동작** — `:241-244` 경로로 mock-server `POST /project` 200 ×3 (prjId 3/4/5 외부 응답값 수신, self-fill 아님), `DeidentResult.deferred()` 반환 확인 ✅. **그러나 원본 파일 존재 검증이 없어**, 존재하지 않는 원본(`./storage/raw/seed/clip-9101.mp4`)이 그대로 위탁되고 18바이트 스텁이 '비식별 완료(Y)'로 승인됨 | `DeidentifyStepTest#KPST_enabled시_..._즉시_Y전이하지_않는다`, `#ANONY_영상도_KPST_위탁_무조건_비식별` | **B-ISSUE-01** |
| TC-DEID-017 | run: mock아님 + KPST 미주입 → 설정오류 | PASS | 정적 `:245-249` — 레거시 폴백 없음, 고정 메시지(CWE-209) | `#KPST_disabled_이고_mock도_아니면_레거시폴백_없이_설정오류_예외`, `#kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외`, `DeidentifyStepKpstDisabledIntegrationTest` | |
| TC-DEID-018 | runMock: 원본 부재 → 'F' 별도 커밋 | PASS | 정적 `:270-275` → `BatchTransitionService.recordDeidentFailure`(별도 빈 REQUIRES_NEW, `:151-169`) → EXTERNAL_API_ERROR | `DeidentifyStepFailurePersistenceIntegrationTest#mock_원본부재시_DE_IDNTF_YN이_F로_DB에_영속된다`, `#mock_실패시_procLog에_FAIL_기록이_DB에_영속된다`, `DeidentifyStepTest#mock모드_원본부재시_성공위장없이_recordDeidentFailure_+_MARKING_READY_미전이` | KPST 경로에는 동일 가드 없음(B-ISSUE-01) |
| TC-DEID-019 | runMock: 복사 IOException → 'F' | **PARTIAL** | 정적 `:278-286` 로 코드는 정확(recordDeidentFailure + INTERNAL_ERROR). **전용 테스트 부재** — IOException 주입 케이스가 3개 테스트 파일 어디에도 없음 | 없음 | **B-ISSUE-05**(커버리지 갭) |
| TC-DEID-020 | runMock: 성공 → 'Y'+procLog+부수효과 | PASS | 정적 `:288-303` — Y 전이 + procLog.succeed + 락 해제 + OPEN 신고 RESOLVED + 알림, 전부 `run()` 의 REQUIRES_NEW 단일 트랜잭션 내 | `DeidentifyStepTest#mock모드_재비식별_잠금영상_성공시_releaseRaw_+_resolveOpenReports_+_알림`, `DeidentifyStepExecutePersistenceIntegrationTest` | |
| TC-DEID-021 | runMock: atomic move 멱등 | PASS | 정적 `:319-338` — tmp 복사 → `ATOMIC_MOVE`(+REPLACE_EXISTING), 불가 환경 replace 폴백, finally 에서 tmp 정리 | `#mock모드_재실행시_atomic_move로_멱등하게_덮어쓰고_손상되지_않는다` | |
| TC-DEID-022 | 출력 경로 순회 방어(CWE-22) | PASS | 정적 `:344-355` — 가드 존재. 단 target 은 `base/videos/{rawSn:Long}/deidentified.mp4` 로만 구성돼 **사용자 입력이 경로에 도달하지 않음** → base 이탈이 구조적으로 발생 불가(방어심도용 dead branch) | 없음 | 결함 아님. KPST 경로의 외부 fileName 순회 방어는 `KpstDeidentService.sanitizeFileName:513-534` 가 별도 담당 |
| TC-DEID-023 | execute: self 프록시 REQUIRES_NEW 적용 | PASS | 정적 `:203-213` — `selfProvider.getObject()` 로 프록시 경유(자기호출 회피), 단위테스트용 `this` 폴백 | `DeidentifyStepExecutePersistenceIntegrationTest#execute_경유_mock비식별이_DB에_영속 — deIdntfYn=Y + procLog SUCCEEDED + MARKING_READY 함께 커밋` | |
| TC-DEID-024 | execute: completed를 ctx 브릿지 | PASS | **실동작(간접)** — KPST 위탁 시 `deferred` → `ctx.markDeidentCompleted(false)` → 러너가 MARKING_READY 미전이(로그·DB로 확인). 정적 `:212` + `BatchContext.java:119-127` | `DeidentifyStepTest#execute_ctx_는_run_raw_에_위임` | |

## B-4. BatchOrchestrator 상태 전이 (정상/실패)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-030 | process: rawSn null → INVALID_INPUT | PASS | 정적 `BatchOrchestrator.java:101-103`. **실동작(경계)** — `POST /v1/dev/batch/trigger?rawSn=0` → HTTP 400 `INVALID_INPUT`(@Min(1)) | `BatchOrchestratorTest#rawSn_null이면_INVALID_INPUT` | |
| TC-BATCH-031 | process: 영상 미존재 → NOT_FOUND | PASS | **실동작** — `POST /v1/dev/batch/trigger?rawSn=999999` → HTTP 404 `{"errorCode":"NOT_FOUND"}`. 정적 `:141-144` (orchestrator 자체 loadRaw 도 동일 예외) | — | |
| TC-BATCH-032 | process: 정상 전체 → COMPLETED | PASS | 정적 `:107-127` (markProcessing→step 루프→markRawDataCompleted→markCompleted→retryQueue.clear). **DB 이력** — rawSn 12·17 `ls_batch_proc_log.proc_step_cd=COMPLETED/proc_stts_cd=COMPLETED` | `BatchOrchestratorTest#YOLO_단계_정상_처리시_COMPLETED_상태_전이`, `#성공_시_재시도큐_clear_호출`, `BatchOrchestratorStatusTransitionTest#process_완료시_..._COMPLETED_영속` | 이번 회차 라이브 성공 완주는 원본 파일 부재로 미발생 |
| TC-BATCH-033 | process: 단계 실패 → FAILED + 재시도 큐 | PASS | **실동작** — rawSn=23: `[BatchOrchestrator] failed rawSn=23 willRetry=true cause=CustomException`, `ls_batch_proc_log(13, FRAME_EXTRACT, FAILED, err=CustomException)`, `ls_bat_rty_wtng(bat_rty_sn=4, raw_sn=23, rty_nmtm=1, max=3, PENDING, rty_prnmnt_dt=+60s)` | `BatchOrchestratorTest#BatchOrchestrator_VLM_META_실패_시_..._FAILED_마킹`, `#INTERPOLATE_단계_실패시_FAILED_고정`, `BatchOrchestratorStatusTransitionTest#process_실패시_..._FAILED_영속` | 정적 `:128-136` |
| TC-BATCH-034 | process: 재시도 소진 후 FAILED 고정 | PASS | 정적 `:132-135` (`willRetry=false` 로그 후 FAILED 반환) | `BatchOrchestratorTest#최대_3회_재시도_후_FAILED_상태_고정_큐_재등록_거부` | 실측 큐 `max_rty_nmtm=3` 일치 |
| TC-BATCH-035 | process: disabled stage skip(dev 토글) | PASS | 정적 `:111-119` — `step.isEnabled(ctx)` false 면 `markStage`+`execute` 둘 다 skip | `BatchOrchestratorTest#Phase3_process_toggles_YOLO_off면_yoloStep_미호출_markStage_미호출_나머지는_정상`, `#Phase3_process_toggles_FRAME_off면_프레임추출_skip` | |
| TC-BATCH-036 | process: 토글 없음 → 전 stage enabled | PASS | 정적 `BatchContext.java:78-85` — 키 미존재/값 null 모두 true 로 정규화, 생성자에서 null→`Map.of()` | `#Phase3_process_단일인자는_전부_enabled_회귀보존`, `#Phase3_process_빈_toggles는_전부_enabled` | |
| TC-BATCH-037 | 두 테이블 분리: 배치완료 시 작업상태 ASSIGNED 복귀 | **PARTIAL** | 정적 `BatchTransitionService.java:87-96` — `LS_DATA_RAW→COMPLETED`, `LS_RAW_DATA_STATUS→ASSIGNED` ✅. **DB 이력** rawSn 11·12 (`ls_data_raw=COMPLETED` ↔ `status=ASSIGNED`) 로 책임분리 성립 확인. **그러나 `transitionTo`/`changeStatus` 가 무검증 setter 라 APPROVED 등 종결 상태도 무조건 덮어씀 | `BatchTransitionServiceTest#markRawDataCompleted_작업상태는_ASSIGNED복귀_배치단계만_LS_DATA_RAW_COMPLETED` | **B-ISSUE-03** |
| TC-BATCH-038 | 작업상태 COMPLETED 점프 시 검수제출 차단 회귀 방지 | PASS | 정적 `:92`(ASSIGNED 복귀로 COMPLETED 점프 차단) — 근거 `70-85`는 javadoc 구간(드리프트) | `BatchTransitionServiceTest#배치완료_후_검수제출_ASSIGNED에서_PENDING_상태머신_허용` | |
| TC-BATCH-039 | 배치실패: MARKING_READY 고착 방지 | PASS | **실동작** — rawSn=23 배치 실패 직후 `ls_data_raw.data_stts_cd=FAILED`(직전 MARKING_READY 였음), `ls_raw_data_status=FAILED`. rawSn 15·16 도 동일 패턴 | `BatchTransitionServiceTest#배치_실패_시_dataSttsCd_가_FAILED_로_전이된다(MARKING_READY_고착_금지)`, `#markRawDataFailed_상태_FAILED_전이_후_save_명시호출` | 정적 `:122-131` |
| TC-BATCH-040 | 배치시작: 두 컬럼 PROCESSING 동시 전이 | PASS | 정적 `:54-63` — 같은 REQUIRES_NEW 트랜잭션 안에서 작업상태 + LS_DATA_RAW 동시 전이 | `BatchTransitionServiceTest#markRawDataProcessing_상태_PROCESSING_전이_후_save_명시호출`, `#마킹완료로_배치가_시작되면_LsDataRaw_dataSttsCd_가_PROCESSING_으로_전이된다` | |
| TC-BATCH-041 | markStage: row 부재 시 WARN(진행 계속) | PASS | 정적 — raw row 부재 WARN `:60-62`(근거 라인 일치), 작업상태 row 부재 WARN 은 `transitionRawDataStatus:287-293`. 어느 쪽도 예외를 던지지 않아 배치 진행 계속. **실동작** — rawSn 24·25 는 `ls_raw_data_status` row 자체가 없는 상태로 파이프라인이 정상 진행됨 | `BatchTransitionServiceTest#status_row_없으면_save_미호출_예외없이_통과`, `#markRawDataMarkingReady_raw_row_없으면_graceful_예외없이_통과` | 케이스명 "markStage"는 `BatchStatusService.markStage` 를 가리키나 근거는 BatchTransitionService — 명칭 드리프트 |

---

## 이슈 상세

### [B-ISSUE-01] TC-DEID-016 — KPST 위탁 경로에 원본 존재 검증이 없고 산출물 무결성이 "존재+>0바이트"뿐 → 원본이 없는 영상이 18바이트 텍스트 스텁으로 "비식별 완료(Y)" 승인

- **심각도**: HIGH
- **기대 동작(기대효과)**: mock 경로가 명시적으로 보장하는 원칙(`DeidentifyStep` 클래스 주석 "정합성(HIGH-2): 원본 부재 시 'Y' 위장 금지 — 'F' 마킹 + 실패(MARKING_READY 미전이)")이 **운영 실경로인 KPST 위탁에도 동일하게** 적용되어야 한다. 비식별 완료('Y' + MARKING_READY)는 "원본이 실제로 존재하고, 그 원본을 비식별한 유효한 영상 산출물이 회수됐다"를 뜻해야 한다. 이 신호를 근거로 마킹 화면이 비식별본을 서빙하고 파이프라인이 진행되므로, 여기서 거짓 'Y'가 나면 후속 전 단계가 오염된다.
- **현재 동작(이슈 내용)**:
  1. `KpstDeidentService.submit`(`backend/src/main/java/kr/co/cudo/authoring/batch/service/KpstDeidentService.java:161-218`)은 `rawFilePathNm` 의 **blank 여부와 부모 디렉터리 유무만** 검사하고 파일 실재는 확인하지 않는다.
     ```java
     if (rawFilePathNm == null || rawFilePathNm.isBlank()) { throw ... }   // :174
     Path fullPath = Paths.get(rawFilePathNm);
     Path parent = fullPath.getParent();                                    // :177-178
     // ← Files.isRegularFile(fullPath) 검사 없음 (mock 경로 :270 에는 존재)
     ```
  2. 완료 판정 시 산출물 검증은 `isUsableDeidFile`(`:391-402`)의 **"정규 파일이고 size>0"** 이 전부다.
     ```java
     return Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0;   // :398
     ```
  - **실동작 증거(라이브)**: 원본 `./storage/raw/seed/clip-9101.mp4` 는 컨테이너에 **존재하지 않는다**(`docker exec klid-backend ls /app/storage/raw/seed` → `No such file or directory`). 그럼에도
    - `POST /project` 위탁 성공(prjId=3), 폴링 1회 만에 `[KpstDeid] poll completed rawSn=23`,
    - DB: `ls_data_raw(23).de_ident_yn='Y'`, `data_stts_cd='MARKING_READY'`, `ls_deident_proc_log(23).proc_stts_cd='SUCCEEDED'`, `poll_stts_cd='DOWNLOADED'`, `de_idntf_file_path_nm=/app/storage/deidentified/videos/23/clip-9101_202607250139_mask.mp4`,
    - 그 "비식별 영상"의 실체는 **18바이트 텍스트 파일**: `cat` 결과 `MOCK_DEIDENTIFIED`.
  - 원본 부재는 **두 단계 뒤에야** 드러난다: `ls_batch_proc_log(13, FRAME_EXTRACT, FAILED, err_msg_cn='원본 영상을 찾을 수 없습니다: rawSn=23')`. 즉 비식별 단계는 실패를 잡지 못하고 성공 신호를 흘려보냈다.
- **재현/확인 경로**:
  ```bash
  # 1) 원본 부재 확인
  docker exec klid-backend sh -c 'ls -la /app/storage/raw/seed/ ; ls -la /app/storage/deidentified/videos/23'
  docker exec klid-backend sh -c 'cat /app/storage/deidentified/videos/23/*_mask.mp4'   # → MOCK_DEIDENTIFIED (18B)
  # 2) 그럼에도 비식별 완료로 전이됐음
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, data_stts_cd, de_ident_yn FROM public.ls_data_raw WHERE raw_sn=23;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT proc_stts_cd, poll_stts_cd, de_idntf_file_path_nm, orgnl_file_path_nm FROM public.ls_deident_proc_log WHERE data_raw_sn=23;"
  # 3) 재현(신규): mng_clip_master 에 존재하지 않는 file_path 로 JOB_DMND_YN='Y' 클립 추가 후
  curl -X POST 'http://localhost:18081/api/v1/dev/batch/scan' -H "Authorization: Bearer <REVIEWER dev token>"
  ```
- **영향**: 데이터 정합 + 개인정보 보호. ① 비식별 산출물이 유효 영상인지 검증하지 않으므로 KPST가 `procState=2`를 주면서 잘린/빈 껍데기 파일을 남긴 실패 모드에서 `DE_IDNTF_YN='Y'`가 커밋되고 마킹 단계가 열린다(스트리밍 `GET /v1/videos/{rawSn}/stream` 은 "비식별 완료" 신호를 신뢰). ② 원본 부재를 비식별 단계가 잡지 못해 실패 위치가 FRAME_EXTRACT 로 밀려 원인 추적이 어려워지고, 배치 재시도(3회)가 무의미하게 소모된다. CWE-345(불충분한 데이터 진정성 검증) / CWE-754(비정상 조건 부적절 검사).
- **수정 방향(제안)**: (구현 금지)
  1. `submit()` 앞단에 mock 경로와 동일한 원본 실재 가드 추가 — `Files.isRegularFile(fullPath)` 실패 시 `BatchTransitionService.recordDeidentFailure(rawSn, ...)`(별도 REQUIRES_NEW)로 'F' 커밋 후 거부. 공유 마운트가 보이지 않는 배포에서는 프로퍼티로 가드를 끌 수 있게 하되 기본은 켬(fail-closed).
  2. `isUsableDeidFile` 에 최소 무결성 기준 추가 — 최소 바이트 임계값 + 컨테이너 시그니처(ftyp box) 또는 ffprobe 스트림 확인. 최소한 "원본 대비 터무니없이 작은 산출물" 거부.
  3. 두 검증 모두 `TC-DEID-018`(mock)과 대칭인 KPST 케이스로 테스트 추가.

### [B-ISSUE-02] TC-BATCH-016 — Quartz 클러스터링이 기본 비활성 + 온프렘 배포 템플릿도 false → 2노드 Active-Active 에서 `@DisallowConcurrentExecution` 이 중복 발화를 막지 못함 (UNCERTAINTY #8 확정)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md "배치 성능"이 명시한 배포 토폴로지는 **주 서버 2노드 Active-Active + Quartz 클러스터링 적용(QRTZ_LOCKS 행 락으로 잡 중복 방지)** 이다. 관제 학습용 스캔(60초)·KPST 폴링(30초)·export sweep(600초) 트리거가 노드당 1회씩이 아니라 클러스터 전체에서 1회만 발화해야 중복 적재/중복 폴링이 발생하지 않는다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/resources/application.yml:72` — `org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}` (기본 **false**).
  - `deploy/onprem/config/backend/env.template:175` — 배포 템플릿의 기본값도 `QUARTZ_CLUSTERED=false` (주석에는 "2노드를 띄울 때만 두 노드 모두 true 로 설정" 이라고 안내만 있음).
  - `@DisallowConcurrentExecution`(`ControlTrainingVideoScanJob.java:21`, `BatchQuartzJob.java:27`, `BatchRetryQuartzJob.java:23`)은 **동일 스케줄러 인스턴스 내 동일 JobKey**만 직렬화한다. non-clustered JobStore 에서는 노드 간 조율이 없어 두 노드가 각자 트리거를 획득·발화한다.
  - 부수: `deploy/onprem/src/backend/src/main/resources/application.yml:48` 은 `isClustered: 'false'` 로 **하드코딩**(환경변수 미참조)돼 있어 `QUARTZ_CLUSTERED=true` 를 넣어도 무효다. 다만 이 트리는 2026-06-29 스냅샷(현행 backend yml 405행 vs 303행)이라 실제 빌드 소스인지 확인 필요.
- **재현/확인 경로**:
  ```bash
  grep -n 'isClustered' backend/src/main/resources/application.yml deploy/onprem/src/backend/src/main/resources/application.yml
  grep -n 'QUARTZ_CLUSTERED' deploy/onprem/config/backend/env.template
  # 런타임(단일 노드) 확인: 클러스터 락 테이블 사용 여부
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT * FROM public.qrtz_scheduler_state;"
  ```
- **영향**: 기능/데이터 정합. 2노드 운영 시 관제 학습용 스캔이 동시에 두 번 돌아 같은 클립을 경쟁 적재한다(멱등 2중 방어가 있어 중복 INSERT 는 막히나 UK 위반 롤백/로그 잡음 발생). KPST 폴링이 두 번 돌면 완료 처리(`finishDownloadAndComplete`)와 타임아웃 마킹이 경쟁하고, 배치 큐 dequeue 가 이중화되어 동일 rawSn 파이프라인 중복 실행 가능. `deploy/onprem/src` 하드코딩이 실제 빌드 경로라면 토글 자체가 무력.
- **수정 방향(제안)**: ① 배포 템플릿에서 HA 여부를 필수 선택 항목으로 승격(2노드 프로파일 기본 true), ② `deploy/onprem/src` 스냅샷 제거 또는 `${QUARTZ_CLUSTERED}` 로 동기화, ③ 기동 시 "노드 수 > 1 인데 isClustered=false" 를 감지할 방법이 없으므로 최소한 `isClustered=false` 일 때 WARN 1줄 + 운영 체크리스트(`cc-deploy-check`) 항목화.

### [B-ISSUE-03] TC-BATCH-037 — 배치 상태 전이에 상태머신 검증이 전혀 없어 종결 상태(APPROVED)도 무조건 덮어써짐

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `LS_RAW_DATA_STATUS.DATA_STTS_CD` 는 검수 워크플로우 상태이고 `COMPLETED`/`APPROVED` 는 종결 상태다. 배치 경로가 종결된 작업을 임의로 되돌리면 검수 결과가 소실되므로, 불법 전이는 차단(또는 최소한 거부·경고)돼야 한다.
- **현재 동작(이슈 내용)**: 두 엔티티 모두 **무조건 setter** 이고, 검증 책임이 서로에게 떠넘겨져 배치 경로에서는 아무도 검증하지 않는다.
  - `BatchTransitionService.java:30-31` 주석: *"전이 불가 상태(상태 머신 위반) 여부 검증은 `LsRawDataStatus` 의 책임이며, 본 서비스는 단순 갱신만 위임한다."*
  - `LsRawDataStatus.java:87-93`:
    ```java
    /** ... 전이 가능 여부 검증은 ReviewStateMachine 가 책임지며, 본 메서드는 단순 갱신만 수행. */
    public void transitionTo(String newStatus) { this.dataSttsCd = newStatus; this.updDt = LocalDateTime.now(); }
    ```
    → 배치 경로는 `ReviewStateMachine` 을 호출하지 않는다(`BatchTransitionService` 에 의존 없음).
  - `LsDataRaw.changeStatus` 도 blank 검사만 하고 전이 규칙 검증 없음(`LsDataRaw.java:354-359`).
  - 결과: `markRawDataProcessing`(`:59`) → `transitionRawDataStatus(rawSn, PROCESSING)`, `markRawDataCompleted`(`:92`) → `ASSIGNED` 가 현재 상태와 무관하게 적용된다. `APPROVED` 영상에 배치를 재실행하면 `APPROVED → PROCESSING → ASSIGNED` 로 검수 승인이 조용히 소실된다.
  - 도달 경로: `BatchDevTriggerController.trigger`(`:83-86`)는 `PROCESSING` 만 거부하므로 `COMPLETED/APPROVED` 영상에 대해 트리거가 통과한다(`@Profile("!prd")` 라 prd 미노출). `MarkingBatchBridge` 는 `LS_DATA_RAW ∈ {PROCESSING, COMPLETED}` 를 막아 정상 UX 는 보호되나, 이는 **다른 테이블**(LS_DATA_RAW) 기준 방어라 작업상태 종결 보호와 직결되지 않는다.
  - 참고: `tryClaimBatchQueued`(`:188-195`)와 `tryClaimReprocessFromFailed`(`:269-281`)만 조건부 UPDATE(check-and-set)로 상태를 검증한다 — 즉 **검증 가능한 패턴이 이미 코드베이스에 있는데** 완료/실패/처리중 전이에는 적용돼 있지 않다.
- **재현/확인 경로**: (상태 파괴가 있으므로 격리 환경에서만)
  ```bash
  # APPROVED 인 rawSn 확인
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT s.raw_data_id, s.data_stts_cd, r.data_stts_cd FROM public.ls_raw_data_status s JOIN public.ls_data_raw r ON r.raw_sn=s.raw_data_id WHERE s.data_stts_cd='APPROVED';"
  # dev 트리거(비운영) — PROCESSING 이 아니면 통과하여 APPROVED 가 ASSIGNED 로 강등됨
  curl -X POST 'http://localhost:18081/api/v1/dev/batch/trigger?rawSn=<APPROVED rawSn>' -H "Authorization: Bearer <token>"
  ```
- **영향**: 데이터 정합. 검수 승인 이력(작업 종결)이 배치 재실행 한 번으로 사라지고, `TASK_COMPLETED` 통지를 이미 보낸 작업이 다시 미완료 상태가 되어 관제와 상태가 어긋난다. 현재 확인된 도달 경로가 dev 전용 엔드포인트라 CRITICAL로 올리지는 않았으나, 방어가 계약(주석)상으로만 존재하고 실제로는 없다는 점이 문제다.
- **수정 방향(제안)**: ① `markRawDataProcessing`/`markRawDataCompleted`/`markRawDataFailed` 를 조건부 UPDATE(허용 선행 상태 화이트리스트)로 전환하고 영향 행수 0 이면 WARN + skip, ② 불가하면 최소한 `ReviewStateMachine` 검증을 배치 경로에서도 호출, ③ 두 엔티티 주석의 "검증은 X 책임" 문구를 실제 호출 위치와 일치하도록 정정.

### [B-ISSUE-04] TC-BATCH-002 — 학습용 클립 스캔이 매 tick 전체 조회(페이징·미적재 필터 없음) + 클립당 개별 조회 2회

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 60초마다 도는 스캔 잡이므로 후보 조회는 "아직 적재되지 않은 클립" 으로 좁혀지고 처리 건수 상한(배치 크기)이 있어야 한다. 프로젝트 규칙(`.claude/rules/performance.md`)도 "페이징 없는 목록 전체 조회 금지 / `findAll()` without Pageable 금지"를 명시하고, 목표 규모는 영상 5,000건(SFR-17)·이미지 10만장(SFR-16)이다.
- **현재 동작(이슈 내용)**:
  - `MngClipMasterRepository:29-35` — `SELECT c FROM MngClipMaster c WHERE c.jobDmndYn=:jobDmndYn AND c.filePath IS NOT NULL AND TRIM(c.filePath) <> ''` : **Pageable/limit 없음, 이미 적재된 클립을 제외하는 조건 없음**(`NOT EXISTS (LS_DATA_RAW)` 부재).
  - `TrainingVideoIngestService.scanAndIngest:53` 이 결과 전체를 `List` 로 메모리 적재.
  - 클립마다 `videoRepository.findByVmsClipId`(`TrainingVideoIngestTx:100`) + `clipEvntLstRepository.findFirstByEvntId`(`:105`) 를 개별 실행 → 후보 N건이면 매 tick 2N 쿼리.
  - **실동작 증거**: 클립 3건 전량이 이미 적재된 뒤에도 2회차 스캔 로그가 `scanned=3 ingested=0` — 즉 매 tick 전량 재조회 후 전량 skip 이 구조적으로 반복됨을 확인.
- **재현/확인 경로**:
  ```bash
  curl -X POST 'http://localhost:18081/api/v1/dev/batch/scan' -H "Authorization: Bearer <token>"   # → data:0
  docker logs klid-backend | grep 'scan finished'   # scanned=N ingested=0 이 매회 반복
  ```
- **영향**: 성능. 학습용 지정 클립이 수천 건 누적되면 60초마다 전량 SELECT + 수천 회 point lookup 이 관제 공유 DB(MNG_*)에 발생한다. 관제서버와 DB를 공유하므로 부하가 저작도구 밖으로 파급된다.
- **수정 방향(제안)**: ① 쿼리에 `AND NOT EXISTS (SELECT 1 FROM LsDataRaw r WHERE r.vmsClipId = c.clipId)` 추가(멱등 1차 조회도 함께 제거 가능), ② `Pageable`(예: tick 당 100건)로 상한, ③ `findFirstByEvntId` 는 후보 evntId 집합 IN 조회 1회로 배치화. 현행 이중 멱등 가드는 그대로 유지(race 방어).

### [B-ISSUE-05] TC-DEID-019 — mock 비식별 복사 IOException 분기(`'F'` 마킹)의 전용 테스트 부재

- **심각도**: LOW
- **기대 동작(기대효과)**: `runMock` 의 두 실패 분기(원본 부재 / 복사 IOException)는 모두 "성공 위장 금지 — 별도 커밋으로 `'F'` 기록" 이라는 동일 안전 계약을 가지므로, 원본 부재와 동등한 수준의 회귀 테스트가 있어야 한다.
- **현재 동작(이슈 내용)**: 코드는 정확하다 — `DeidentifyStep.java:278-286` 이 `catch (IOException)` 에서 `batchTransitionService.recordDeidentFailure(...)` 호출 후 `INTERNAL_ERROR` 를 던진다. 그러나 `DeidentifyStepTest` / `DeidentifyStepFailurePersistenceIntegrationTest` / `DeidentifyStepExecutePersistenceIntegrationTest` 어디에도 복사 IOException 을 주입하는 테스트가 없다(원본 부재 분기만 3개 테스트로 커버). 즉 `copyAtomically` 리팩토링 시 이 분기가 조용히 깨져도 감지되지 않는다.
- **재현/확인 경로**:
  ```bash
  grep -rn 'IOException' backend/src/test/java/kr/co/cudo/authoring/batch/step/    # → 주입 케이스 없음
  ```
- **영향**: 회귀 위험(테스트 커버리지 갭). 기능 결함은 아님.
- **수정 방향(제안)**: target 부모 경로를 읽기 전용 디렉터리/기존 파일로 만들어 `Files.createDirectories` 또는 `Files.copy` 가 실패하도록 유도하는 통합 테스트 1건 추가(원본 부재 테스트와 동일 픽스처 재사용).

### [B-ISSUE-06] TC-DEID-007 / TC-BATCH-031 — `loadRaw` 자기호출로 `@Transactional(REQUIRES_NEW, readOnly)` 가 적용되지 않음(장식적 애너테이션)

- **심각도**: LOW
- **기대 동작(기대효과)**: 애너테이션이 붙어 있으면 실제로 그 트랜잭션 경계가 열려야 한다. 붙어 있는데 적용되지 않는 상태는, 과거 이 프로젝트에서 실제 결함으로 이어진 패턴이다(`DeidentifyStep.execute` 가 `selfProvider` 를 도입한 이유, 그리고 프레임 추출 deid 경로 NULL 버그).
- **현재 동작(이슈 내용)**: 두 곳 모두 `protected` 메서드를 `this` 로 직접 호출해 Spring AOP 프록시를 우회한다.
  - `AsyncDeidentifyRunner.java:58` `LsDataRaw raw = loadRaw(rawSn)...` → 대상 `:89-96` `@Transactional(readOnly=true, REQUIRES_NEW) protected Optional<LsDataRaw> loadRaw(...)`
  - `BatchOrchestrator.java:104` `LsDataRaw raw = loadRaw(rawSn);` → 대상 `:139-145` 동일 패턴
  - 실제 조회는 리포지토리 기본 트랜잭션으로 수행돼 **기능상 정상 동작**한다(라이브 확인: rawSn 23/24/25 정상 로드, 미존재 rawSn=999999 는 404). 다만 결과 엔티티가 detached 로 반환되며, 애너테이션이 주는 격리 보장은 실제로 존재하지 않는다.
- **재현/확인 경로**: 정적 — 위 두 파일의 호출부/선언부 대조. (동일 클래스 내 self-invocation, `selfProvider` 같은 프록시 우회 회피 장치 없음)
- **영향**: 현재는 무해(readOnly 조회). 향후 이 메서드에 쓰기·잠금·격리수준 의존 로직이 추가되면 즉시 결함이 된다. 또한 코드 독자가 "REQUIRES_NEW 로 격리됨" 이라고 오독한다.
- **수정 방향(제안)**: `DeidentifyStep` 이 이미 쓰는 `ObjectProvider<Self>` 프록시 경유 패턴을 적용하거나, 조회를 별도 빈(예: `BatchRawLoader`)으로 분리. 최소 조치로는 애너테이션을 제거하고 "리포지토리 기본 트랜잭션 사용" 을 주석으로 명시.

---

## 사전 확정 항목 처리 결과

| # | 항목 | 이번 회차 확정 |
|---|------|----------------|
| UNCERTAINTY #2 | 마킹단계 rawSn 비식별 신고 미구현 | **미구현 갭으로 기록** — `grep` 결과 `POST /v1/videos/{rawSn}/deident-report` 컨트롤러 부재 확인. 본 1부(B-1~B-4) 케이스에는 해당 케이스가 없어 판정 대상 아님. B-5 이후 구간에서 재확인 필요 |
| UNCERTAINTY #8 | Quartz 클러스터링 실제 활성 여부 | **확정: 기본 비활성** — `application.yml:72 isClustered=${QUARTZ_CLUSTERED:false}`, 온프렘 템플릿도 `QUARTZ_CLUSTERED=false`. → TC-BATCH-016 PARTIAL + B-ISSUE-02 |
| UNCERTAINTY #21 | `KpstDeidentTxService` 상태전이·락해제 원자성 | **확정: 원자적** — `completeDeidentification`(`:171-179`)이 단일 `@Transactional(REQUIRES_NEW)` 안에서 `verifyDeidFile` → `applyBatchCompletion`(`:191-212`: `markDeidentified("Y")` + `markMarkingReady()` + `releaseRaw` + `resolveOpenReports` + `notify`)을 수행. 라이브 확인 — 10:40:02.338 동일 워커에서 `lock-released rawSn=23` → `completed rawSn=23` 연속 기록, DB 에 Y·MARKING_READY 함께 커밋됨. 결함 없음 |

## 요약

- **총 53건 / PASS 49 / FAIL 0 / PARTIAL 4 / BLOCKED 0 / N/A 0 / 확인필요 0**
  - PARTIAL: TC-BATCH-016(클러스터링 미활성), TC-BATCH-037(상태머신 검증 부재), TC-DEID-016(KPST 원본/산출물 검증 부재), TC-DEID-019(테스트 커버리지 부재)
- **self-fill 결함: 0건** — 비식별 전 구간이 mock-server(KPST)를 실제 경유함을 인바운드 로그(`POST /project` ×3, `GET /retrieve_progress` ×3)와 외부 발급 식별자(prjId 3/4/5, datasetId 3/4/5)로 확증. 저작도구가 값을 자체 생성한 지점 없음. (단 산출물 **내용** 검증 부재는 B-ISSUE-01)
- **근거 라인 드리프트: 4건**
  1. TC-BATCH-038 — `BatchTransitionService.java:70-85` 는 javadoc 구간, 실제 로직은 `:92`
  2. TC-BATCH-039 — `:117-131` 중 javadoc `:115-121`, 실제 로직 `:122-131`
  3. TC-BATCH-041 — 케이스명은 `markStage`(=`BatchStatusService`)인데 근거는 `BatchTransitionService.java:60-62`(`markRawDataProcessing` 의 raw row WARN). 작업상태 row 부재 WARN 은 `:287-293`
  4. `BatchOrchestrator.java:47` javadoc "본 process() 자체는 NOT_SUPPORTED" — 실제로는 `@Transactional` 애너테이션 자체가 없음(무트랜잭션). 동작은 동일하나 문서-코드 드리프트
- 그 외 B-1~B-4 근거 라인은 모두 실제 코드 위치와 일치.
