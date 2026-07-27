# B. 배치 파이프라인 / 비식별화 — 1차 검증 결과

> 검증일 2026-07-25 · 기준: **실동작**(풀스택+목업서버 기동, 파이프라인 rawSn=26 완주 시나리오 위에서 판정)
> 환경 근거: `_raw/stack-bringup.md` · `_raw/pipeline-drive.md` · `_raw/test-baseline.md`

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

# B 클러스터 Part2 — B-5/B-6/B-7 실동작 전수 검증 (2026-07-25 1차)

> 대상: `docs/test-cases/B-batch-deidentify.md` 78~149행 (TC-BATCH-050~089, TC-VLM-001~030)
> 스택: klid-postgres / klid-backend(:18081→8080) / klid-ai-server / klid-mock-server(:9400) / klid-frontend 전부 기동
> DB 스키마: `public` (klid_system, user=klid_user)
> 토큰: `POST /v1/dev/tokens` — ★함정: INTERNAL 채널 인가역할은 **토큰 role 클레임이 아니라 `LS_USER_ROLE` 조회**가 출처.
> 실재 userNo(1001=REVIEWER, 2001/2002=WORKER, 3001=PORTAL_USER)로 발급하지 않으면 전 API 403 (초기 오진 → 정정 후 재실행)
> ★VLM 전제: `VLM_CLIENT_ENABLED=false` (backend 컨테이너 env 실측). `_raw/vlm-wiring.md` 실증 결과 —
> `WebClientConfig.vlmWebClient` 의 **HTTPS-only + 사설IP 차단 가드** 때문에 로컬 mock 배선이 **원천 불가**(부트 크래시).
> mock-server 24h 인바운드 로그에 `videovlm` 요청 **0건**, `ls_webhook_idempotency` **0행** → **VLM 외부연동 전 구간 미경유**.

## B-5. 마킹 완료 브릿지 (동시성·가드)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-050 | 브릿지: 영상 미존재 → skip | PASS | 정적 `MarkingBatchBridge.java:62-66` findById empty → warn+return. 라인 일치 | MarkingBatchBridgeTest#onMarkingCompleted_noRawVideo_skips | 실동작 재현 불가(이벤트는 마킹 생성 시에만 발행되고 마킹은 영상 존재를 요구) — 논리적 도달 불가 방어층 |
| TC-BATCH-051 | PROCESSING/COMPLETED 재트리거 차단 | PASS | 정적 :45-46,:72-76 `SKIP_BATCH_STAGES`. **실동작 간접**: rawSn 19(COMPLETED)·26(COMPLETED) 마킹 요청 → 412 로 상위 가드가 선차단(이벤트 미발행) | MarkingBatchBridgeTest#onMarkingCompleted_rawDataCompleted_skips, #_rawDataProcessing_skips | 2중 방어. HTTP 경로로는 브릿지 가드에 도달 자체가 안 됨 |
| TC-BATCH-052 | 비식별 미완료 트리거 차단 | PASS | 정적 :80-84 `DEIDENTIFIED.equals` 부정. **실동작 간접**: rawSn 9(deIdntfYn='F', MARKING_READY) 마킹 → 412 PRECONDITION_FAILED | MarkingBatchBridgeTest#_notDeidentified_skips, #_deidentFailed_skips | 우회 경로 탐색 결과 없음 — 마킹 생성 API 가 유일한 이벤트 발행처(`MarkingService.java:187` 단일) |
| TC-BATCH-053 | tx1 claim 성공 → 트리거 | **PASS(LIVE)** | rawSn 24 에 배정 생성(status row=ASSIGNED, ver=1) → WORKER 2001 AUTO 마킹 201 → 로그 `enqueued rawSn=24` **1회**, `AsyncBatchRunner starting batch rawSn=24` **1회**. status ver 1→3 | BatchTransitionServiceTest#tryClaimBatchQueued_affectedOne_returnsTrue_andPersists | tx1(조건부 UPDATE) 경로 실측 확인 |
| TC-BATCH-054 | 미배정 REVIEWER — row 부재 시 생성 | **PASS(LIVE)** | rawSn 15/16 은 `ls_raw_data_status` row **부재** 상태 → REVIEWER 직접 마킹 201 → row 생성 + `enqueued` + 배치 기동. 마킹 전 `select … where raw_data_id in (15,16)` = 0행 → 마킹 후 1행 | MarkingBatchBridgeTest#_unassignedRowAbsent_tx2Creates_triggers | FIX B(고착 제거) 실동작 입증 |
| TC-BATCH-055 | 동시 row 생성 경쟁 — 1건만 | **PASS(LIVE)** | rawSn 16 에 3 동시 POST → 3건 모두 201, 브릿지 3회 진입. 로그: `enqueued rawSn=16` **1회**, `concurrent row creation rawSn=16 — skipping` **2회** → `batch already claimed/in-progress` 2회. 배치 스레드는 `batch-async-2` **단 1개** | BatchTransitionServiceRowCreationIT#concurrentRowCreation_exactlyOneClaims_noExceptionPropagates | PG unique 위반 → 별도 REQUIRES_NEW tx 밖에서 catch 하는 패턴이 실제로 작동(같은 tx 재시도 회피) |
| TC-BATCH-056 | 동시 2 이벤트 중 1건만 BATCH_QUEUED | PASS | **실동작(위 3동시)에서 최종 불변식(배치 1회) 입증**. 단 실측 경로는 tx2(row 생성 경합)였고, 케이스가 지목한 tx1 조건부 UPDATE 직렬화(`BatchTransitionService.java:187-195`)는 로컬 데이터상 재현 불가(MARKING_READY 영상에 status row 가 없었음) | MarkingBatchBridgeTest#_concurrentDoubleMarking_triggersOnce (tx1 경로) | 근거 라인 일치 |
| TC-BATCH-057 | 이미 claimed면 skip | **PASS(LIVE)** | 로그 `[MarkingBatchBridge] batch already claimed/in-progress rawSn=16 — skipping` ×2 (`:106-109`) | MarkingBatchBridgeTest#_bothClaimFalse_skips | |
| TC-BATCH-058 | tryCreateBatchQueuedRow: row 존재 → false 멱등 | PASS | 정적 `BatchTransitionService.java:239-241` existsById → false | BatchTransitionServiceTest#tryCreateBatchQueuedRow_existingRow_false | 케이스 근거 238-241 중 238은 주석 — 실질 일치 |
| TC-BATCH-059 | 할당형 PK saveAndFlush 즉시 flush | PASS | 정적 :242-247 `saveAndFlush`+예외 미포착 전파. **실동작**: rawSn16 경합에서 `DataIntegrityViolationException` 이 브릿지까지 전파돼 catch 됨(로그) | BatchTransitionServiceTest#_concurrentInsert_propagatesException + RowCreationIT | |
| TC-BATCH-060 | 로그 인젝션 방어(CWE-117) | PASS | 정적 :120-122 `replace("\n","").replace("\r","")`, 적용처 :74(dataSttsCd) :82(deIdntfYn) 2곳. 그 외 로그 인자는 `rawSn`(Long)이라 주입 불가 | **없음** | 회귀 테스트 부재 → [B-ISSUE-26]. Tab(`\t`)은 미제거(로그 1행 유지에는 무해) |

## B-6. MarkingService (자동/수동, 경계값, 인가)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-070 | actor null → UNAUTHORIZED | **PASS(LIVE)** | 무토큰 `POST /v1/videos/15/markings` → **401** `{"errorCode":"UNAUTHORIZED"}` | MarkingControllerTest#미인증_요청_401 | SecurityConfig 단계에서 차단(프로브 이전) |
| TC-BATCH-071 | REVIEWER 전체 허용 | **PASS(LIVE)** | REVIEWER(1001)가 **배정 없는** rawSn 15/16/25 에 마킹 접근 성공(15/16=201, 25=검증거부 400) `MarkingGuards.java:53-55` | MarkingServiceTest#비식별_완료_영상_마킹생성_정상_가드_통과 | |
| TC-BATCH-072 | 미배정 WORKER → FORBIDDEN(존재 미노출) | **PASS(LIVE)** | WORKER 2001 → rawSn 15: **403** "본인에게 배정된 영상의 마킹만…" / **미존재 99999 도 403**(NOT_FOUND 아님) → 리소스 존재 미노출 확인 | MarkingControllerTest#I4_타인배정_영상…403, MarkingServiceTest#I4_미배정_WORKER_FORBIDDEN | 반증 시도(존재/미존재 응답 차이) → 차이 없음 |
| TC-BATCH-073 | 인가 우선 — 미배정 시 ffprobe 미실행 | PASS | 정적 `MarkingService.java:99-108`(precheck → AUTO 만 resolve). 실동작 간접: 미배정 WORKER AUTO 요청이 즉시 403(수 ms) | MarkingServiceOrchestrationTest#미배정_WORKER_AUTO요청_프로브_미트리거되고_FORBIDDEN 외 2건 | ffprobe 호출 여부는 HTTP 로 직접 관측 불가 → 단위테스트 위임 |
| TC-BATCH-074 | 영상 미존재 → NOT_FOUND | **PASS(LIVE)** | REVIEWER + 99999 → **404** "영상을 찾을 수 없습니다." (`MarkingGuards.java:77-79`) | MarkingControllerTest#POST_미존재_영상_마킹_404 | 인가 통과자에게만 404 노출 = 설계대로 |
| TC-BATCH-075 | 비식별 미완료 → 412 | **PASS(LIVE)** | rawSn 9(deIdntfYn='F') → **412** "비식별이 완료된 영상에서만 마킹할 수 있습니다." | MarkingServiceTest#비식별_미완료_영상…PRECONDITION_FAILED | |
| TC-BATCH-076 | MARKING_READY 아님 → 412 | **PASS(LIVE)** | rawSn 19/26(COMPLETED) → **412** "이미 처리된 영상은 재마킹할 수 없습니다." | MarkingServiceTest#이미_COMPLETED…, #이미_PROCESSING… | 배치 FAILED 영상(15/16/24)도 동일 412 → 재마킹 불가(재처리 큐 소관) |
| TC-BATCH-077 | 이벤트유형 미지정 → INVALID_INPUT | PASS | 정적 `MarkingGuards.java:88-92` | MarkingServiceTest#이벤트유형_null…, #이벤트유형_blank…, MarkingControllerTest#POST_이벤트유형_미지정_400 | 로컬 전 영상이 `evnt_type_cd` 보유 → live 재현 불가 |
| TC-BATCH-078 | AUTO intervalFrames null/≤0 → 400 | **PASS(LIVE)** | rawSn15: null→400, 0→400, -5→400 모두 "자동 모드에서 intervalFrames 는 1 이상이어야 합니다."(`MarkingService.java:162-164`) | MarkingServiceTest#자동모드_intervalFrames_0이하_INVALID_INPUT | ★상한 미검증 발견 → [B-ISSUE-23] |
| TC-BATCH-079 | durationSec null/≤0 backstop | PASS | 정적 :219-223 | MarkingServiceTest#자동마킹_주입_durationSec_null…, #…0이하…, #FIX_A_외부해석이_전부_실패해_null이_주입되면_backstop | live 는 VDO_LEN_SEC 이 항상 존재해 1단 폴백에서 해결 |
| TC-BATCH-080 | AUTO 정상 marks(실 fps, off-by-one) | **PASS(LIVE)** | rawSn 24(dur=30s, fps=30, interval=30) → `json_array_length(mark_cn)=**30**`, 마지막 원소 `{"frameIndex":870,"timestamp":"00:29"}` → totalFrames=900 미포함(off-by-one 정확), step 30 정확 | MarkingServiceTest#자동마킹_끝경계프레임_미포함_off_by_one_검증 외 5건 | rawSn16(dur=113,fps=30,interval=600) → 0/600/…/3000 (3390 미만) 도 정합 |
| TC-BATCH-081 | 분수 fps(29.97) 반올림 | PARTIAL | 정적 `MarkingService.java:226` `Math.round(durationSec*fps)` 확인. 그러나 **generateAutoMarks 에 29.97 을 넣는 테스트가 없음**(fps 25/30/60 만). 29.97 파싱은 VideoFpsResolverTest#분수fps_29.97_정확파싱 이 별도 커버 | VideoFpsResolverTest#분수fps_29.97_정확파싱 (파싱만) | 커버리지 갭 → [B-ISSUE-27] |
| TC-BATCH-082 | fps pin 저장(TOCTOU 제거) | **PASS(LIVE)** | `ls_marking.fps` 컬럼 실측 — markingSn 24/25/26/27/30 모두 `30` 저장. `MarkingService.java:157,180-182` createAuto/createManual 에 fps 전달 | MarkingServiceTest#자동마킹_해석한실fps25가_마킹레코드에_pin…, #수동마킹도…pin된다 | |
| TC-BATCH-083 | MANUAL marks 비면 400 | **PASS(LIVE)** | rawSn 25(MARKING_READY): `marks:[]`→400, `marks` 생략→400 "수동 모드에서 marks 는 필수입니다."(`:169-172`) | **없음**(서비스 단위테스트 미확인) | 거부 케이스라 대상 영상 상태 불변(재확인함) |
| TC-BATCH-084 | mode 미지 → 400 | **PASS(LIVE)** | rawSn25: `"X"`→400, `"auto"`(소문자)→400, `" AUTO "`(공백)→400 모두 "mode 는 AUTO 또는 MANUAL 이어야 합니다."(`:174-175`). 별도로 `""`→400 (@NotBlank) | 없음 | 대소문자·trim 미허용 = fail-closed |
| TC-BATCH-085 | 생성 성공 → MarkingCompletedEvent 발행 | **PASS(LIVE)** | 201 직후 로그 `[Marking] created rawSn=24 …` → 동일 스레드 `[MarkingBatchBridge] handling marking completed rawSn=24` (`:187`) | MarkingServiceTest#마킹_생성시_MarkingCompletedEvent_발행 | |
| TC-BATCH-086 | 이벤트명 자동소싱 = evntTypeCd | **PASS(LIVE)** | rawSn24 `evnt_type_cd='INTRUSION'` → 응답 `eventName:"INTRUSION"`, rawSn15/16 `EV03000101` → 동일 (`:148`) | 없음(응답 단언으로 간접) | |
| TC-BATCH-087 | persist self 프록시(AFTER_COMMIT 보존) | **PASS(LIVE)** | AFTER_COMMIT 리스너가 **실제로 발화**(위 로그) = persist 가 프록시 경유 트랜잭션에서 커밋됐다는 실증. self 자기호출이었다면 트랜잭션 부재로 이벤트가 즉시/미발화 | MarkingServiceOrchestrationTest(오케스트레이션), VlmMarkingTransitionPersistenceIntegrationTest(유사 패턴) | |
| TC-BATCH-088 | durationSec 3단 폴백 | PASS | 정적 `VideoDurationResolver.java:74-97` 3단 + null. live 는 1단(VDO_LEN_SEC)만 사용 | VideoDurationResolverTest 8건(3단 전부 + 경계), VideoDurationResolverTxIsolationIT#커넥션0 | |
| TC-BATCH-089 | 컨트롤러: WORKER/REVIEWER만 | **PASS(LIVE)** | PORTAL_USER(3001) 토큰 → **403** `{"message":"권한이 없습니다."}` (`MarkingController.java:51`). WORKER 배정자 201, REVIEWER 201 | MarkingControllerTest#I4_본인배정_영상_마킹생성_WORKER_201 | 채널 격리도 동시 확인(CHANNEL_PORTAL → /v1/videos/** 거부) |

## B-7. VLM 위탁 Step + 콜백 수신

> ★공통 제약: `VLM_CLIENT_ENABLED=false` + `WebClientConfig` HTTPS-only 가드로 **목업 경유 실동작 검증 자체가 불가**([B-ISSUE-21]).
> 아래 "PASS(정적)"는 코드+단위/통합테스트 기반이며 **외부 벤더 실왕복은 미확인**임을 명시한다.

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-VLM-001 | 마킹 있으면 runWithMarking(first) | PASS(정적) | `VlmTimeseriesStep.java:104-111` `markings.get(0)`. **live 로 다중 마킹 상황 실재 확인**(rawSn16 `marking check count=3`) → 2·3번째 마킹은 위탁 대상에서 누락 | VlmTimeseriesStepMarkingTest#runWithMarking_… | 다중 마킹 누락 → [B-ISSUE-22] |
| TC-VLM-002 | 마킹 없으면 run | PASS(정적) | :108-109 | VlmTimeseriesStepMarkingTest#runWithMarking_null_마킹시_기존_run_호출과_동일 | |
| TC-VLM-003 | enabled=false → 즉시 SKIPPED, 외부호출 0 | **PASS(LIVE)** | 배치 로그 `[Batch][VlmTimeseries] skipped (disabled) rawSn=15/16/24` (`:147-151`). **mock-server 24h 인바운드 `videovlm` 0건**, `ls_webhook_idempotency` **0행**(등록도 안 함) | VlmTimeseriesStepTest#enabled_false_시_외부_호출_0건_등록_0건_SKIPPED_반환, #_recordVlmTimeseriesResult_미호출 | ★단, DB에 skip 흔적 0 → [B-ISSUE-24] |
| TC-VLM-004 | rawSn null → INVALID_INPUT | PASS(정적) | :143-145 | VlmTimeseriesStepTest#rawSn_null_시_INVALID_INPUT | |
| TC-VLM-005 | 영상 미존재 → NOT_FOUND | PASS(정적) | :153-156 (enabled 체크 **이후**) | VlmTimeseriesStepTest#영상_미존재_시_NOT_FOUND_예외_등록_미수행 | |
| TC-VLM-006 | 비식별 경로 없음 → fail-closed | PASS(정적) | :214-224 최신 성공 procLog 의 `DE_IDNTF_FILE_PATH_NM` 만 사용, 없으면 EXTERNAL_API_ERROR. 원본 경로 전송 코드 없음(Grep 확인) | VlmTimeseriesStepTest#비식별_경로_없으면_원본_미전송_fail_closed | 개인정보 보호 핵심 가드 |
| TC-VLM-007 | request_id 등록 실패 → describe 미호출 | PASS(정적) | :166-173 try/catch → EXTERNAL_API_ERROR abort (describe 이전) | VlmTimeseriesStepTest#recordIssued가_describe_호출_전에_수행됨_등록실패시_describe_미호출 | |
| TC-VLM-008 | recordIssued 독립 커밋(REQUIRES_NEW) | PASS(정적) | :163-167 + `WebhookIdempotencyLedger.recordIssued` REQUIRES_NEW | VlmTimeseriesStepTest#위탁_성공_시_recordIssued로_… | live 미검증(ledger 0행) |
| TC-VLM-009 | describe 응답 null → EXTERNAL_API_ERROR | PASS(정적) | :182-185 | VlmTimeseriesStepTest#빈_응답_시_EXTERNAL_API_ERROR | |
| TC-VLM-010 | 45s 블록 타임아웃 | 확인필요 | 정적 :74 `BLOCK_TIMEOUT=45s`, :181 `.block(BLOCK_TIMEOUT)`. **그러나 실효 타임아웃은 `VlmClient` 의 `vlm.client.timeout-seconds=10`**(`VlmClient.java:71,76,108` `.timeout(timeout)`) → 45s block 은 도달 불가 상한. 케이스 문구 "45s 타임아웃"은 실효값과 불일치 | **없음**(타임아웃 테스트 부재) | [B-ISSUE-27]. Retry(exp backoff) 누적 시에만 45s 가 의미 |
| TC-VLM-011 | 위탁 성공 시 마킹 PENDING→VLM_REQUESTED | PASS(정적) | :196,:234-242 `markVlmRequested()` true 시에만 save | VlmTimeseriesStepMarkingTest#runWithMarking_마킹_상태_VLM_REQUESTED_전이, VlmMarkingTransitionPersistenceIntegrationTest | **live 반증**: DB `ls_marking` 전 10행이 `PENDING` — 위탁이 한 번도 안 일어남을 확증(self-fill 없음 ✓) |
| TC-VLM-012 | retry — 이미 전이된 마킹 no-op | PASS(정적) | :234-241 transitioned=false → save 미호출 | VlmTimeseriesStepMarkingTest#retry_재실행_시_이미_VLM_COMPLETED…역행하지_않는다, PersistenceIT#retry… | |
| TC-VLM-013 | 콜백 URL 고정 base(SSRF 차단) | PASS(정적) | :244-253 `callbackBaseUrl`(@Value `authoring.webhook.callback-base-url`) + 상수 `HmacWebhookFilter.PATH_VLM` 결합. 요청 DTO·사용자 입력이 URL 구성에 개입하는 경로 없음 | 없음(전용 테스트 부재) | [B-ISSUE-27] 커버리지 |
| TC-VLM-014 | 미발급 request_id → UNAUTHORIZED | **PASS(LIVE)** | `POST /v1/vlm/callback {"request_id":"forged-abc-123","status":"completed",…}` → **401** "발급되지 않은 request_id 입니다."(`VlmResultService.java:69-74`) | VlmResultServiceTest#미발급_request_id_콜백은_UNAUTHORIZED_거부, VlmDescribeCallbackFlowIntegrationTest#미등록_request_id_콜백은_401 | 위조 콜백 8종 전부 401 |
| TC-VLM-015 | 이미 PROCESSED → 멱등 스킵 | PASS(정적) | :77-80 | VlmResultServiceTest#동일_request_id_재수신_시_멱등_스킵 | live 도달 불가(발급 ledger 0행) |
| TC-VLM-016 | rawSn 매핑 없음 → UNAUTHORIZED | PASS(정적) | :83-88 | VlmResultServiceTest#발급됐으나_rawSn_매핑_없으면_UNAUTHORIZED_거부 | 동상 |
| TC-VLM-017 | 미지 status → INVALID_INPUT(멱등 미마킹) | **PASS(LIVE)** | `status:"done"` → **400** "status 는 completed\|failed 중 하나여야 합니다."(DTO `@Pattern` 1차). 서비스 2차 방어 :94-102 는 정적 확인 | VlmResultServiceTest#미지_status_콜백은_INVALID_INPUT_거부되고_completed_오처리_및_멱등마킹_안함 | DTO 가 먼저 잡아 서비스 분기는 HTTP 로 도달 불가(2중 방어) |
| TC-VLM-018 | failed → error 기록+VLM_FAILED+멱등 | PASS(정적) | :105-109,:168-186. live 는 401 게이트에서 차단(정상) | VlmResultServiceMarkingTest#failed_콜백_수신시_VLM_REQUESTED_마킹을_VLM_FAILED로_전이_고착해제 | 코드 주석에 "자동 복구 잡 미구현" 명시(기지의 갭) |
| TC-VLM-019 | completed+영상 미존재 → NOT_FOUND | PASS(정적) | :112-116 | 없음(직접 테스트 미확인) | |
| TC-VLM-020 | 한 콜백 내 중복 metaKey → INVALID_INPUT | PASS(정적) | :192-204 `putIfAbsent` → 400. **live 시도**: dup metaKey 콜백 → 401(발급 게이트가 선행) → dedup 로직 미도달 | VlmResultServiceTest#한_콜백_내_중복_구간_metaKey는_400_거부 | live 재현 불가는 게이트 설계상 정상 |
| TC-VLM-021 | META upsert — 신규만 검수큐 PENDING | PASS(정적) | :123-150 | VlmResultServiceTest#배치_내_동일_영상_기존_meta는_값갱신만_신규_meta만_검수큐_진입 | live: `ls_data_meta` 1행(EXTERNAL 수기), VLM 유래 0행 → **self-fill 없음 확증** |
| TC-VLM-022 | 마킹 VLM_REQUESTED→VLM_COMPLETED | PASS(정적) | :152-157 | VlmResultServiceMarkingTest 3건 | |
| TC-VLM-023 | 원자성 — 중간 실패 시 PROCESSED 롤백 | PASS(정적) | :63 단일 `@Transactional` + :160 `markProcessedInTx`(REQUIRED, 마지막) | VlmResultServiceTest#검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능 | |
| TC-VLM-024 | 동일 request_id 동시 콜백 직렬화 | PARTIAL | 정적 :69 `lookupForProcessing`(비관적 락) 확인. **동시성 테스트 부재**(멱등 스킵은 순차 테스트만). live 도 발급 ledger 0행이라 재현 불가 | VlmResultServiceTest#동일_request_id_재수신_시_멱등_스킵(순차) | [B-ISSUE-27] — B-5 는 동시성 IT 가 있는데 콜백 측은 없음(비대칭) |
| TC-VLM-025 | 로그 마스킹(CR/LF/tab) | PASS(정적) | :48,:206-209 `[\r\n\t]` → `_` 치환, 전 로그 인자에 `safe()` 적용 확인 | 없음 | request_id 는 DTO `@Pattern ^[A-Za-z0-9_-]+$` 로 1차 차단(live 확인: `abc\ndef` → 400) |
| TC-VLM-030 | 콜백 무인증(HMAC 없음) — isIssued 게이트 차단 | PARTIAL | **live**: HMAC 헤더 없이 `POST /v1/vlm/callback` 도달 → 서비스 발급 게이트에서 401. `HmacWebhookFilter.java:105,150-153` VLM 은 `sizeCapOnlyPaths`(HMAC 미적용, 본문 4MB 캡만). request_id 는 UUIDv4 라 추측 불가 → 주입 차단은 성립 | 없음(컨트롤러 테스트 파일 부재) | ★그러나 **rate limit 미적용**(`isRateLimited` 는 HMAC 분기 :163-169 에만) + 컨트롤러 :22 "IP allowlist 재검토" TODO 미이행 → [B-ISSUE-25]. 위조 15연타 전부 401, 429 없음(실측) |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-21] TC-VLM-001~030 전반 — VLM 외부 연동이 로컬 목업을 구조적으로 경유할 수 없어 실동작 검증 불가
- **심각도**: HIGH
- **기대 동작(기대효과)**: 검증 대전제("외부 연동은 전부 목업서버가 대행")대로 `vlm.client.url` 을 `klid-mock-server:9400` 으로 지정하면 describe 위탁 → mock 콜백 왕복이 실동작으로 확인돼야 한다.
- **현재 동작(이슈 내용)**:
  - backend 실효 env `VLM_CLIENT_ENABLED=false` (컨테이너 env 실측) → `VlmTimeseriesStep.java:148-151` 즉시 SKIPPED.
  - 활성화 시도는 `_raw/vlm-wiring.md` 실증대로 **부트 크래시**: `WebClientConfig.java:130 validateExternalUrl` → `vlm.client.url 은 HTTPS 스키마만 허용됩니다 (현재: http)`. https 로 우회해도 docker 브리지 사설IP(172.18.0.4)가 SSRF 가드(:150-155)에 걸림. mock-server 는 TLS 미지원.
  - 실증: `docker logs klid-mock-server --since 24h | grep videovlm` → **0건**. `select * from ls_webhook_idempotency` → **0행**. `ls_marking` 전 10행 `stts_cd=PENDING`(VLM_REQUESTED 전이 0건). `ls_data_meta` 의 유일 1행은 수기 EXTERNAL 메타.
  - KPST(비식별)는 `KpstWebClientConfig` 가 http+사설IP 를 명시 허용해 정상 경유(mock 로그에 `/project`, `/retrieve_progress` 인바운드 확인) → **두 외부 클라이언트의 보안 정책이 비대칭**.
- **재현/확인 경로**:
  ```
  docker exec klid-backend env | grep VLM_CLIENT_ENABLED       # → false
  docker logs klid-mock-server --since 24h | grep -i videovlm  # → 0건
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select count(*) from ls_webhook_idempotency;"  # → 0
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,count(*) from ls_marking group by 1;"  # → PENDING만
  ```
- **영향**: TC-VLM-005~012, 015, 016, 018~024 (18건)의 **실왕복 검증 부재**. 벤더 계약(IntelliVIX v2.0.1) 정합은 코드/단위테스트 신뢰에만 의존. self-fill 은 **없음**(메타·마킹 전이 0건으로 확증) — 이 점은 오히려 fail-closed 로 정상.
- **수정 방향(제안)**: `WebClientConfig.vlmWebClient` 의 검증을 `KpstWebClientConfig` 와 동일하게 스킴 분기(http 허용+WARN)하거나 local 프로파일 한정 완화. (구현 금지 — vlm-wiring.md 의 동일 제안과 중복 계상)

### [B-ISSUE-22] TC-VLM-001 — 동일 rawSn 에 마킹 N건 생성 가능, VLM 은 첫 건만 위탁 → 나머지 마킹 영구 PENDING
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 영상 1건당 마킹은 1건으로 수렴하거나, 다중 마킹이 허용된다면 전 건이 파이프라인 상태 머신을 완주해야 한다.
- **현재 동작(이슈 내용)**: `MarkingService.create` 에 중복 마킹 방지 가드가 없다(`ls_marking` 에 rawSn UNIQUE 없음 — `\d ls_marking` 실측 인덱스는 PK + `idx_lm_raw` 비유니크). 동시 3요청 → `markingSn 25,26,27` 3행 생성(전부 201). 배치는 1회만 돌고(`MarkingLoadStep … count=3`), `VlmTimeseriesStep.java:107` 이 `markings.get(0)` 만 위탁 → 나머지 2건은 어떤 전이도 받지 못한다.
  - 실측: `select marking_sn,raw_sn,stts_cd from ls_marking where raw_sn=16;` → 25/26/27 모두 `PENDING`.
- **재현/확인 경로**: MARKING_READY 영상에 `POST /v1/videos/{rawSn}/markings` 를 3회 동시 실행 → 201 ×3, `ls_marking` 3행, 배치 1회.
  ```
  for i in 1 2 3; do curl -s -X POST .../v1/videos/16/markings -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":600}' & done; wait
  ```
- **영향**: 시계열 메타가 일부 마킹 기준으로만 생성됨. 프레임 추출도 어떤 marks 를 쓰는지 비결정적(순서 의존). 고아 마킹 누적으로 상태 조회/통계 왜곡.
- **수정 방향(제안)**: ① rawSn 당 활성 마킹 1건 제약(부분 유니크 인덱스 또는 서비스 가드 409) 또는 ② 배치가 전 마킹을 순회 위탁. 브릿지의 배치 1회 보장과 별개 축이므로 마킹 생성 측에서 막는 편이 blast-radius 가 작다.

### [B-ISSUE-23] TC-BATCH-078 — AUTO intervalFrames 상한 미검증 → 과대값이 marks 1건으로 퇴화
- **심각도**: LOW
- **기대 동작(기대효과)**: `intervalFrames` 가 `totalFrames` 를 초과하면 자동 마킹이 의미를 잃으므로 400 으로 거부하거나 최소 marks 수를 보장한다.
- **현재 동작(이슈 내용)**: `MarkingService.java:162-164` 는 하한(≥1)만 검증한다. `intervalFrames=999999999` 요청이 **201** 로 통과하고 `marks` 는 `[{"frameIndex":0,"timestamp":"00:00"}]` 단 1건 → 이후 프레임 추출도 1프레임만 산출.
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:18081/api/v1/videos/15/markings -H "Authorization: Bearer $RT" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":999999999}'
  # → 201, marks 1건 (실측: markingSn=24)
  ```
- **영향**: 오조작/오입력 시 학습데이터 프레임이 1장만 생성되는데 상태는 정상 완료로 흐른다. `durationSec≤0` 은 backstop 으로 막으면서 반대편 경계는 무방비(비대칭).
- **수정 방향(제안)**: `@Max` 또는 서비스에서 `intervalFrames < totalFrames` 검증, 혹은 산출 marks 가 1건이면 경고/거부.

### [B-ISSUE-24] TC-VLM-003 — VLM 단계 skip(disabled)이 DB에 무흔적 → 시계열 메타 누락의 사후 추적 불가
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 파이프라인 단계가 건너뛰어졌다면 `LS_BATCH_PROC_LOG` 에 SKIPPED 로 남아, 완료된 영상에 시계열 메타가 없는 이유를 사후 추적할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.java:147-151` 이 로그만 남기고 즉시 반환하며 `batchStatusService.recordVlmTimeseriesResult` 를 호출하지 않는다(단위테스트 `#enabled_false_시_BatchStatusService_recordVlmTimeseriesResult_미호출` 로 의도 확정). 실측 `ls_batch_proc_log` 의 rawSn 16/24 행에 `proc_step_cd` 가 `FRAME_EXTRACT` 만 있고 **VLM 단계 행은 아예 없음**.
- **재현/확인 경로**:
  ```
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select proc_step_cd, proc_stts_cd from ls_batch_proc_log where data_raw_sn=24;"   # VLM 행 없음
  docker logs klid-backend | grep "VlmTimeseries] skipped"                                 # 로그에만 존재
  ```
- **영향**: 운영에서 VLM 비활성/장애 구간에 처리된 영상들이 "메타 없음 + 무기록"으로 남아, 사후 재처리 대상 식별이 로그 보존기간에 종속된다.
- **수정 방향(제안)**: disabled 경로에서도 `LS_BATCH_PROC_LOG` 에 `proc_step_cd=VLM, proc_stts_cd=SKIPPED` 1행 기록(또는 영상 단위 skip 플래그).

### [B-ISSUE-25] TC-VLM-030 — VLM 콜백이 무인증 + rate limit 미적용 + IP allowlist TODO 미이행
- **심각도**: MEDIUM (CWE-770 Unrestricted Resource Consumption / CWE-307 brute force 방어 부재)
- **기대 동작(기대효과)**: 인증 없이 공개된 엔드포인트는 최소한 요청 빈도 제한(또는 IP allowlist)으로 보호돼야 한다. 동일 필터가 augment 경로에는 이미 이 방어를 적용하고 있다.
- **현재 동작(이슈 내용)**: `HmacWebhookFilter.java:150-153` 이 `/v1/vlm/callback` 을 `sizeCapOnlyPaths` 로 분기해 **본문 4MB 캡만** 적용하고 즉시 체인으로 넘긴다. `isRateLimited()` 호출은 HMAC 분기(:163-169)에만 있어 VLM 경로는 무제한이다. `VlmResultController.java:22` 의 `// TODO(보안): 실운영 전 IP allowlist 재검토` 는 미이행. 콜백 1건당 `lookupForProcessing`(비관적 락 SELECT)이 무조건 실행된다.
- **재현/확인 경로**:
  ```
  for i in $(seq 1 15); do curl -s -o /dev/null -w "%{http_code} " -X POST \
    http://localhost:18081/api/v1/vlm/callback -H 'Content-Type: application/json' \
    -d "{\"request_id\":\"forged-rl-$i\",\"status\":\"failed\",\"error\":{\"code\":\"E\",\"message\":\"m\"}}"; done
  # 실측 → 401 401 401 ... (15회 전부, 429 없음)
  ```
- **영향**: 인증 없이 DB 락 SELECT 를 유발하는 pre-auth DoS 표면. 데이터 주입 자체는 UUIDv4 request_id 게이트로 차단되므로 **무결성 침해는 아님**(위조 8종 전부 401 확인).
- **수정 방향(제안)**: `handleSizeCapOnly` 에도 IP 기반 rate limit 적용, 그리고 prd 프로파일에서 벤더 IP allowlist 필터 추가(TODO 종결).

### [B-ISSUE-26] TC-BATCH-060 — 로그 인젝션(CWE-117) sanitize 회귀 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: 보안 계층(sanitize) 은 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: `MarkingBatchBridge.java:120-122` 의 `sanitize()` 는 구현돼 있으나 `MarkingBatchBridgeTest` 12개 케이스 중 CR/LF 를 주입해 검증하는 테스트가 없다(`grep -niE "sanitize|CWE-117"` 0건). 누군가 로그 문장을 리팩터링하며 `sanitize()` 를 빼도 테스트가 잡지 못한다.
- **재현/확인 경로**: `grep -c "sanitize" backend/src/test/java/kr/co/cudo/authoring/marking/listener/MarkingBatchBridgeTest.java` → 0
- **영향**: 방어 로직의 조용한 소실 위험. 현재 코드 자체는 정상(탭 미제거이나 로그 1행 파괴는 아님).
- **수정 방향(제안)**: `dataSttsCd="PROC\nING"` 같은 값으로 로그 캡처 단언 테스트 1건 추가.

### [B-ISSUE-27] TC-VLM-010/013/024/025, TC-BATCH-081 — 테스트 커버리지 갭 + "45s 타임아웃" 근거 불일치
- **심각도**: LOW (단, TC-VLM-010 근거 불일치는 **확인필요**)
- **기대 동작(기대효과)**: 케이스가 지목한 동작(타임아웃 실효값, SSRF 고정 base, 콜백 동시성, 로그 마스킹, 분수 fps 반올림)이 테스트로 고정돼 있어야 한다.
- **현재 동작(이슈 내용)**:
  1. **TC-VLM-010 근거 불일치**: 케이스는 "45s 블록 타임아웃"이나, 실효 타임아웃은 `VlmClient.java:71,76` 의 `vlm.client.timeout-seconds:10` → `:108 .timeout(timeout)` 이다. `VlmTimeseriesStep.java:74` 의 45s `block()` 은 Retry(exp backoff) 누적을 감안한 상한이며 단일 호출로는 도달하지 않는다. `application.yml:384 timeout-seconds: 10` 실측. → UNCERTAINTIES #13 의 "VLM 45s" 전제와 코드가 어긋남.
  2. 타임아웃 동작 테스트 **없음**.
  3. TC-VLM-013(콜백 URL 고정 base) 전용 테스트 **없음**.
  4. TC-VLM-024(동시 콜백 직렬화) 동시성 IT **없음** — B-5 브릿지는 동시성 IT 가 있는데 콜백 측만 비대칭.
  5. TC-VLM-025 로그 마스킹 테스트 **없음**.
  6. TC-BATCH-081: `generateAutoMarks` 에 29.97 을 넣는 테스트 없음(25/30/60 만).
  7. `VlmResultControllerTest` 파일 자체가 부재.
- **재현/확인 경로**: `grep -rn "BLOCK_TIMEOUT\|resolveCallbackUrl\|29.97" backend/src/test/` → 해당 단언 0건
- **영향**: 회귀 감지 공백. 특히 45s/10s 이중 타임아웃은 운영 타임아웃 산정 문서(D4/연동규격서)와 드리프트할 소지.
- **수정 방향(제안)**: 케이스 문구를 "블록 상한 45s / 실효 클라이언트 타임아웃 10s"로 분리 기술하고, 위 5종 테스트 보강.

### [B-ISSUE-28] (참고 기록) 마킹 단계 rawSn 비식별 신고 미구현 — UNCERTAINTIES #2 확정
- **심각도**: MEDIUM (미구현 갭 — 케이스 표 대상 아님, 기록 목적)
- **기대 동작(기대효과)**: CLAUDE.md "마킹 단계(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`)" 가 존재해야 함(문서상 planned).
- **현재 동작(이슈 내용)**: 구현된 것은 라벨링 단계(`DeidentReportController.java:91` `POST /v1/labels/{srcSn}/deident-report`)뿐. rawSn 경로는 **컨트롤러 자체가 없음**.
- **재현/확인 경로**: `curl -X POST .../v1/videos/25/deident-report -H "Authorization: Bearer $RT" -d '{"reason":"test"}'` → **404**
- **영향**: 마킹 화면에서 비식별 누락을 발견해도 신고 경로가 없어 라벨링 단계까지 진행해야 신고 가능.
- **수정 방향(제안)**: 정책 확정 후 rawSn 기준 신고 엔드포인트 추가(작업락 + 영상 단위 처리).

---

## 요약
- 총 **57건** (B-5: 11 / B-6: 20 / B-7: 26)
- **PASS 53** (그중 **실동작 확인 PASS 21건** — LIVE 표기) / **FAIL 0** / **PARTIAL 3** (TC-BATCH-081, TC-VLM-024, TC-VLM-030) / **BLOCKED 0** / **N/A 0** / **확인필요 1** (TC-VLM-010)
- **근거 라인 드리프트: 1건** (TC-VLM-030 근거 `VlmResultController.java:18-40` → 실제 16-41, 경미)
- **self-fill 결함: 0건** — VLM 미경유 상태에서 `ls_data_meta`(VLM 유래) 0행 · `ls_marking` 전 행 PENDING · `ls_webhook_idempotency` 0행으로 **저작도구가 외부 응답 없이 값을 채우지 않음을 실측 확증**
- 신규 이슈 **8건**: HIGH 1(B-ISSUE-21) / MEDIUM 4(22, 24, 25, 28) / LOW 3(23, 26, 27)
- **핵심 반증 결과**: B-5 동시성(3동시 마킹 → 배치 정확히 1회, PG unique 위반의 별도-tx catch 패턴 실동작 확인)과 B-6 인가/프리컨디션(401/403/404/412 전 경계 실측)은 **반증 시도에도 무너지지 않음**. 반면 B-7 은 외부 미경유로 실동작 확인이 불가한 구조적 제약이 최대 리스크.

# B-8/B-9 검증 결과 (2026-07-25 1차)

> 검증 중 실제로 별도 세션(pipeline-drive)이 `rawSn=26`(vms_clip_id=DRIVE-CLIP-0725A)을 라이브로
> 구동해, 본 검증 세션이 그 파이프라인(YOLO→SAM2→INTERPOLATE→프레임 2벌)의 **실시간 실행·완료를
> DB/로그로 직접 관측**했다. 아래 "실동작" 근거는 대부분 이 rawSn=26 라이브 구동 + 기존 완료 영상
> (rawSn 13/14/17, 4~13 legacy)의 실제 DB 상태를 함께 사용했다.

## B-8. FfmpegFrameExtractor

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-100 | execute: marks 비면 INVALID_INPUT | PASS | 정적: FfmpegFrameExtractor.java:119-122 일치 | BatchStepExecuteTest#FRAME_execute_marks비면_INVALID_INPUT_extractByMarks_미호출 | |
| TC-BATCH-101 | execute: 추출 0건 → INTERNAL_ERROR | PASS | 정적: :130-133 일치 | BatchStepExecuteTest#FRAME_execute_추출결과_0건이면_INTERNAL_ERROR | |
| TC-BATCH-102 | extractByMarks: 영상 메타 blank → INVALID_INPUT | PASS | 정적: :163-165 일치 | FfmpegFrameExtractorTest | |
| TC-BATCH-103 | extractByMarks: 비식별 미완료 → INVALID_INPUT | PASS | 정적: :170-174 일치 + 실동작: DB `ls_data_raw.de_ident_yn≠'Y'` 영상은 프레임 0건(추출 자체 미도달) | FfmpegFrameExtractorTest#extractByMarks_notDeidentified_rejected | |
| TC-BATCH-104 | extractByMarks: 원본 미존재 → INVALID_INPUT | PASS | 정적: :175-179 일치 | FfmpegFrameExtractorTest | |
| TC-BATCH-105 | 비식별 경로 base 이탈 → RAW only(fail-closed) | PASS | 정적: :190-196 일치. **실동작 우연 검증**: `ls_deident_proc_log`의 rawSn 4~8 `de_idntf_file_path_nm`이 호스트 경로(`/Users/ck/Documents/.../backend/storage/...`, 컨테이너 base `/app/storage/deidentified` 밖)로 기록된 레거시 행이 실제로 `ls_data_src.de_idntf_src_file_path_nm` 전량 NULL(RAW only)로 처리돼 있어, fail-closed 가드가 실제로 발동한 사례를 실DB에서 확인 | FfmpegFrameExtractorTest#extractByMarks_deidPathOutsideBase_failClosedRawOnly | 가드 실동작 증거는 의도적 침투테스트가 아니라 우연히 발견된 legacy 데이터지만, 코드 경로 자체는 동일 |
| TC-BATCH-106 | 비식별 경로 null/파일 부재 → RAW only | PASS | 정적: :197-209 일치 | FfmpegFrameExtractorTest#extractByMarks_noDeidVideo_rawOnly 등 | |
| TC-BATCH-107 | 정상: 2벌 추출+deid 경로 INSERT 시점 저장 | PASS | 정적: :240-260 (6-arg `LsDataSrc.create` 이전에 deid 프레임 write→경로 확보) 일치. **실동작**: rawSn=26 라이브 구동 결과 `ls_data_src` 16 frames / deid_frames=16 / raw_frames=16 완전 일치(0건 NULL). rawSn 14/17도 동일(24/24, 24/24). PARTIAL 회귀 미재현 — 07-22 수정(frame-extract-deid-path-null-bug) 정상 유지 확인 | FfmpegFrameExtractorDeidPersistIT#execute_persistsDeidFramePathToDatabase | ★rawSn 11/12/13(07-06~07-15 생성, 수정 이전 레거시)은 디스크엔 deid 프레임 파일이 실재하나 DB `de_idntf_src_file_path_nm`은 전량 NULL — 구버전 self-invocation 버그의 잔존 흔적(수정 후 재실행 안 됨, 메모리 `frame-extract-deid-path-null-bug`의 "기존 NULL 백필은 스코프 밖" 그대로). export 재조회 시 rawSn=13은 실제로 `export_stts_cd=PARTIAL` 확인(신규 rawSn 14/17은 SUCCEEDED) |
| TC-BATCH-108 | seekMillis=round(frameIndex×1000/fps), pin fps 우선 | PASS | 정적: :218,232,297-302 일치 | FfmpegFrameExtractorTest#M3_seekMillis_실fps25_정확_frameIndex50이_2000ms 등 | |
| TC-BATCH-109 | pin fps null/비정상 → resolveFps 폴백 | PASS | 정적: :297-302 일치 | FfmpegFrameExtractorTest#마킹pin이_null이면_resolveFps로_폴백한다 | |
| TC-BATCH-110 | 출력 경로 순회 방어(CWE-22) | PASS | 정적: :281-288 일치 | FfmpegFrameExtractorTest#resolveSafeOutputDir_normalRawSn_passesGuardAndExtracts | |
| TC-BATCH-111 | frames raw/deid 서브세그먼트 분기(충돌 없음) | PASS | 정적: :281-288 일치. **실동작**: 컨테이너 내 `/app/storage/deidentified/frames/deid/{rawSn}`·`/app/storage/raw/frames/raw/{rawSn}` 양쪽 디렉토리 실존, base가 동일(`STORAGE_RAW_PATH`)해도 raw/deid 파일 충돌 없음(각기 다른 파일 크기·내용) | FfmpegFrameExtractorTest#extractByMarks_sameBase_rawAndDeidPathsDoNotCollide | |
| TC-BATCH-112 | 이력: deid 채운 경우 CREATED+DEID_ATTACHED 2건 | PASS | 정적: :256-260 일치 | FfmpegFrameExtractorTest (hstry 검증 포함) | |

## B-9. YOLO / SAM2 / Interpolate (오토라벨 단계)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-120 | YOLO: rawSn null → INVALID_INPUT | PASS | 정적: YoloAutolabelStep.java:148-150 일치 | YoloAutolabelStepTest#nullRawSnRejected | |
| TC-BATCH-121 | YOLO: 프레임별 순차 track(clipId 격리, 0=리셋) | PASS | 정적: :164-181,164,174-232 일치. **실동작**: docker logs `[Batch][Yolo] ... rawSn=26 clipId=26 ... frames=16` — clipId=rawSn 문자열, frame 0..15 순차 호출 확인(ai-server 실추론, mock 아님 — AI_MOCK_MODE=false) | YoloAutolabelStepTest#frameIndexAccumulatesFromZero, #predictYoloTrackCalledAndTrackIdPersisted | |
| TC-BATCH-122 | YOLO: ai-server 호출 실패 → EXTERNAL_API_ERROR | PASS | 정적: :184-188 일치 | YoloAutolabelStepTest#externalErrorWrapped | 실장애 재현은 미실시(정적+테스트로 충분 판단) |
| TC-BATCH-123 | YOLO: mock 응답 감지 WARN(CRLF 살균) | PASS | 정적: :193-205 일치 — AI_MOCK_MODE=false 실추론 환경이라 실동작에서는 mock 분기 미도달(정상, ai-server가 실제 mock 아님을 응답 필드로 알림) | YoloAutolabelStepTest#mockResponseTriggersWarnLog, #realResponseNoWarnLog | |
| TC-BATCH-124 | YOLO: DTCT_TYPE_CD 축 매핑 | PASS | 정적: :213-229 일치. **실동작**: rawSn=26 실행결과 `ls_data_lbl_ai_info(lbl_src_cd=YOLO)` 64건, `ls_data_lbl`에 `lbl_id=1(person)`·`lbl_id=5(bus)` 실제 매핑됨(`ls_label.dtct_type_cd`='person'/'bus'와 일치) | YoloAutolabelStepTest#yoloLabelIdMappedFromMaster, #detectionMatchesDtctTypeAxisForLabelId | |
| TC-BATCH-125 | YOLO: 프리셋 토글 필터(미매핑 fail-safe) | PASS | 정적: :286-296 일치. 실동작 로그에 `preset=(none)`으로 rawSn=26 이벤트타입(EV02000201) 미매핑 확인되었고 BOTH(전체통과)로 정상 동작(64건 전부 저장) | YoloAutolabelStepTest#unmappedLabelDefaultsToBothFailSafe | |
| TC-BATCH-126 | YOLO: 이미지 경로 순회 방어(CWE-22) | PASS | 정적: :302-307 일치 | (readImageAsBase64 경로가드 — YoloAutolabelStepTest 내 경로관련 케이스로 간접 커버) | |
| TC-BATCH-127 | YOLO: conf/imgsz/iou 설정 fail-safe | PASS | 정적: :244-272 일치. 실동작 로그 `conf=0.25 imgsz=1280 iou=0.5`(SystemConfig 값 실제 적용 확인, DEFAULT 0.4 아님 — 운영 조정값이 실제 ai-server 호출에 반영됨) | YoloAutolabelStepTest#systemConfigValuesPassedToAiServer, #fallbackDefaultsWhenSystemConfigMissing | |
| TC-BATCH-128 | SAM2: (srcSn,label,trackId) dedup DB BBOX 우선 | PASS | 정적: Sam2SegmentStep.java:212-228 일치. **실동작**: rawSn=26 YOLO 64건 BBOX → SAM2 호출/저장 62건(dedup으로 자연 감소, 트랙 반복 프레임이 동일 trackId로 합쳐지지 않고 프레임별 별도 처리되어 거의 1:1에 가까움 — dedup 로직이 실제로 라벨+trackId 키로 동작 중임을 카운트 차이로 방증) | Sam2SegmentStepTest#dedup_은_label_과_trackId_조합_기준 | |
| TC-BATCH-129 | SAM2: polygon=false 라벨 skip | PASS | 정적: :167-173 일치 | Sam2SegmentStepTest#polygonEnabled_false_라벨은_SAM2_호출_안_함 | |
| TC-BATCH-130 | SAM2: 응답 폴리곤 상한 초과 → 단순화 | PASS | 정적: :325-343 일치. 실동작 로그에서 관측된 폴리곤 점수(points=237~830) 전부 1000점 cap 미만이라 실제 simplify 트리거는 미관측(정상 — 실 SAM2 CPU 추론 결과가 애초에 상한 이내) | Sam2SegmentStepTest#Sam2Step_4192점_응답_폴리곤은_저장전_1000점_이하로_simplify | |
| TC-BATCH-131 | SAM2: 호출 실패 → EXTERNAL_API_ERROR | PASS | 정적: :197-205 일치 | Sam2SegmentStepTest (외부 오류 래핑 케이스 — DisplayName 목록상 명시적 실패 테스트 미확인, YOLO 대칭 패턴으로 코드는 확인됨) | 이 항목만 전용 실패주입 테스트 미확인(확인필요 낮음, 코드 자체는 YOLO와 동일 패턴이라 신뢰도 높음) |
| TC-BATCH-132 | Interpolate: 프레임/후보 없음 → 0 | PASS | 정적: :131-134(frames empty),154-157(candidates empty) 일치 | TrackInterpolationStepTest#프레임_없는_영상은_no_op, #trackId_있는_BBOX_없으면_no_op | |
| TC-BATCH-133 | Interpolate: 재실행 멱등 stale 선삭제 | PASS | 정적: :144-151 일치 | TrackInterpolationStepTest#재실행_idempotency_기존_보간row_삭제후_재삽입 | |
| TC-BATCH-134 | Interpolate: 트랙 부분실패 격리 | PASS | 정적: :164-175(try/catch per trackId) 일치 | TrackInterpolationStepTest#한트랙_예외_다른트랙_보간은_저장됨 | |
| TC-BATCH-135 | Interpolate: 혼재 타입 트랙 skip | PASS | 정적: :310-315 일치 | TrackInterpolationStepTest#트랙내_타입혼재_안전_skip | |
| TC-BATCH-136 | Interpolate: BBOX flat/nested 양포맷 | PASS | 정적: :398-438 일치 | TrackInterpolationStepTest#nested_BBOX_좌표_포맷도_파싱_지원 | |
| TC-BATCH-137 | Interpolate 단건: from+to stale 삭제 | PASS | 정적: :225-285 일치 | TrackInterpolationSingleTrackIntegrationTest#머지후_fromTrackId_기존보간산출물_고아_0 등 | |
| TC-BATCH-138 | MarkingLoadStep: 마킹 로드+최신 markCn 파싱 | PASS | 정적: MarkingLoadStep.java:50-58 일치. 실동작: rawSn=26 파이프라인이 마킹 로드→marks 파싱→FRAME_EXTRACT로 정상 이어짐(전체 체인 실행 완료로 간접 확인) | MarkingLoadStepTest#마킹_있으면_markings_로드 | |
| TC-BATCH-139 | MarkingLoadStep: markCn 파싱 실패 → INTERNAL_ERROR | PASS | 정적: :60-66 일치 | MarkingLoadStepTest#markCn_이_잘못된_JSON_이면_INTERNAL_ERROR | |
| TC-BATCH-140 | 파이프라인 순서 검증 | PASS | 정적: BatchPipelineConfig.java:31-42(`List.of(markingLoad, vlm, frame, yolo, sam2, interp)`) 일치. **실동작**: rawSn=26 `ls_batch_proc_log.proc_step_cd`가 시점별로 SAM2→...→COMPLETED로 진행 관측(YOLO→SAM2→INTERPOLATE 순서 실제 준수, ai_info 카운트가 YOLO 64→SAM2 39→62→INTERPOLATE 5로 시간순 증가하며 정합) | BatchPipelineConfigTest#파이프라인_순서는_MARKING_VLM_FRAME_YOLO_SAM2_INTERPOLATE | |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-41] TC-BATCH-107 관련 — 07-22 수정 이전 레거시 프레임(rawSn 11/12/13)의 DB NULL 방치로 export가 여전히 PARTIAL
- **심각도**: LOW (신규 결함 아님 — 이미 알려진 갭의 실측 재확인)
- **기대 동작(기대효과)**: 프레임 2벌(원본+비식별)이 추출된 영상은 검수 export가 SUCCEEDED로 산출되어야 한다.
- **현재 동작(이슈 내용)**: `ls_data_src`에서 rawSn 11/12/13은 디스크에 비식별 프레임 파일이 실재함(`docker exec klid-backend ls /app/storage/deidentified/frames/deid/{11,12,13}` 확인)에도 `de_idntf_src_file_path_nm` 컬럼이 전량 NULL. 07-22 수정(6-arg `LsDataSrc.create`로 INSERT 시점에 deid 경로 포함)은 코드상 정상 반영돼 있고 신규 rawSn(14,17,26 등)은 정상이지만, 수정 이전에 생성된 행은 백필되지 않아 export 재조회 시 rawSn=13은 `ls_dataset_export.export_stts_cd='PARTIAL'`로 실측됨.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT raw_sn, count(*), count(de_idntf_src_file_path_nm) FROM public.ls_data_src WHERE raw_sn IN (11,12,13) GROUP BY raw_sn;"`
- **영향**: 07-22 이전 생성된 소수의 레거시 영상만 영향(신규 파이프라인은 정상). 데이터마트 반출 시 해당 영상만 비식별 프레임 누락으로 표시.
- **수정 방향(제안)**: 1회성 백필 배치(레거시 `ls_deident_proc_log.de_idntf_file_path_nm` 기준으로 `ls_data_src.de_idntf_src_file_path_nm` UPDATE) — 메모리 `frame-extract-deid-path-null-bug`에 이미 "스코프 밖"으로 기록된 사항이라 이번 회차에서는 정보 기록만.

### [B-ISSUE-42] YOLO/SAM2 배치 저장이 루프 내 개별 save() — IDENTITY 전략이 hibernate.jdbc.batch_size 설정을 무력화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `rules/performance.md`("대량 처리: 1건씩 save 금지 → saveAll() 사용", "hibernate.jdbc.batch_size: 50")에 따라 프레임×검출 단위의 대량 INSERT는 배치로 묶여야 한다.
- **현재 동작(이슈 내용)**:
  - `YoloLabelPersister.persistBbox`(`backend/src/main/java/kr/co/cudo/authoring/batch/step/YoloLabelPersister.java:58~`)가 `YoloAutolabelStep.java:220-224` 루프(프레임×검출) 안에서 매 detection마다 `lblRepository.save()` + `aiInfoRepository.save()`를 개별 호출.
  - `Sam2SegmentStep.java:186-189`도 동일하게 SegmentJob 루프 안에서 `lblRepository.save()` + `aiInfoRepository.save()` 개별 호출.
  - `TrackInterpolationStep.java:178,184`만 `saveAll()` 사용(상대적으로 낫지만 근본 해결은 아님 — 아래 참고).
  - `LsDataLbl`/`LsDataLblAiInfo` 엔티티는 `@GeneratedValue(strategy = GenerationType.IDENTITY)`(`LsDataLbl.java:55`) — Hibernate는 IDENTITY 전략에서 **PK를 즉시 알아야 하므로 JDBC 배치를 구조적으로 비활성화**한다. 즉 `application.yml:33-36`의 `hibernate.jdbc.batch_size=50`/`order_inserts=true` 설정은 이 엔티티들에는 **효과가 없다**(saveAll()로 바꿔도 동일 — IDENTITY 전략 자체가 원인).
  - 실측: rawSn=26 라이브 구동에서 YOLO 64건, SAM2 62건 각각 실제로는 128/124회의 개별 INSERT(라벨+AI_INFO)로 실행됨(로그 타임스탬프 간격 상 SAM2는 프레임당 순차 호출·저장이 관측됨 — SAM2 CPU 추론 자체가 지배적 비용이라 즉각적 성능 문제로 체감되진 않으나, SFR-16(이미지 10만장) 규모에서는 누적 INSERT 왕복이 유의미해질 수 있음).
- **재현/확인 경로**: `grep -n "GenerationType.IDENTITY" backend/src/main/java/kr/co/cudo/authoring/batch/entity/LsDataLbl.java` + `grep -n "batch_size" backend/src/main/resources/application.yml`
- **영향**: 성능(대량 처리 시 배치 INSERT 미적용) — 기능 정확성에는 영향 없음. CWE 해당 없음(성능 규칙 위반).
- **수정 방향(제안)**: (a) `LsDataLbl`/`LsDataLblAiInfo`를 시퀀스 기반 PK(`GenerationType.SEQUENCE` + `allocationSize`)로 전환해 실제 JDBC 배치를 활성화하거나, (b) 프레임 단위로 라벨을 모아 `saveAll()` 일괄 호출로 리팩터링(단, (a) 없이는 배치 효과 제한적).

## 요약

- 총 34건 / PASS 33 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 1(TC-BATCH-131 — 전용 실패주입 테스트 미확인, 코드 패턴은 YOLO와 동일해 신뢰도 높음)
- 근거 라인 드리프트: 0건 (문서상 file:line 전건이 실제 코드와 정확히 일치 — ±1~3라인 이내 오차만, 실질 드리프트 아님)
- self-fill 결함: 0건 (rawSn=26 라이브 파이프라인으로 YOLO/SAM2/INTERPOLATE 전 단계가 실제 ai-server 호출·실제 DB 저장으로 end-to-end 확인됨 — self-fill 의심 없음)
- 신규 이슈 2건(B-ISSUE-41 LOW/기지 갭 재확인, B-ISSUE-42 MEDIUM/성능)

# B-10 / B-11 검증 결과 (docs/test-cases/B-batch-deidentify.md:194-241)

> 검증일 2026-07-25 · 로컬 풀스택 실동작 기준(klid-backend :18081, klid-postgres, klid-mock-server)
> DB 스키마는 `klid_at` 아닌 **`public`**.
> ⚠ **환경 주의**: 실행 중 `klid-backend` jar 는 `V129` 시점 빌드(Jul 23 17:10)로 **HEAD(27b6bb0d) 미반영**. 상세는 [B-ISSUE-62].
> ⚠ **본 검증이 남긴 상태 변경(API 경유)**: `rawSn=12` 에 신고 1건(rprtSn=6) 등록 → `DE_IDENT_YN='F'` + 작업락 LOCKED. 라벨 0건 영상을 골라 라벨 파괴는 없음. resolve 는 산출물 미검증(fail-closed)으로 거부되어 상태 유지 중.

## B-10. 비디오 스트리밍 (Range, 비식별본만 서빙)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-STREAM-B01 | 비식별 미완료 → NOT_FOUND(원본 차단) | PASS | **실동작**: `GET /v1/videos/{22,20}/stream`(`DE_IDENT_YN='N'`) → **404**. 신고 직후 `rawSn=12`('F') → **404**. 정적 `VideoStreamService.java:156-160` | VideoStreamServiceTest:121 `비식별_미완료시_NOT_FOUND` | 원본 바이트 누출 0건 |
| TC-STREAM-B02 | 영상 미존재 → NOT_FOUND | PASS | **실동작**: `rawSn=9999` → **404**. 정적 `:314-316` | VideoStreamServiceTest:156 | |
| TC-STREAM-B03 | `deIdntfYn='F'/'N'` → null→NOT_FOUND | PASS | **실동작**: 'N'(22,20,21)→404, 'F'(12, 신고 후)→404. 정적 `:318-323` | VideoStreamServiceTest:476,493 | 신고본(노출본) 재서빙 차단 실증 |
| TC-STREAM-B04 | 비식별 경로 base 이탈 → FORBIDDEN(CWE-22) | PASS | **실동작**: `rawSn={15,16,18,19}` → **403**(경로가 `/app/storage/raw/...`). 정적 `:266-267,345-357` | VideoStreamServiceTest:172 | 가드는 정상 작동. 단 이 403 이 **해상도 파생영상 재생 전면 차단**을 유발 → [B-ISSUE-61] |
| TC-STREAM-B05 | 비식별 파일 부재 → NOT_FOUND | PASS(정적/테스트) | 정적 `:270-273`. 실동작 케이스 부재 — `DE_IDENT_YN='Y'` 13건 전부 파일 실존(컨테이너 `ls` 확인) | VideoStreamServiceTest:138 | |
| TC-STREAM-B06 | Range 없음 → 200 전체+Accept-Ranges | PASS | **실동작**: Range 미지정 → **200**, `Accept-Ranges: bytes`, `Content-Length: 18` | VideoStreamControllerTest:90 | 200 응답에도 `Content-Range` 가 함께 붙음(컨버터 동작) — 무해 |
| TC-STREAM-B07 | Range 유효 → 206 + 청크 상한 | PASS | **실동작**(28MB 파일 rawSn=12): `bytes=0-` → 206 `bytes 0-8388607/28220079`, `bytes=0-27000000` → 동일 8MB 상한, `bytes=20000000-` → `20000000-28220078` | VideoStreamControllerTest:99,111 | |
| TC-STREAM-B08 | Range 문법 오류 → 416 | PASS | **실동작**: `bytes=999-0` → **416** `Content-Range: bytes */18`; `bytes=abc` → **416** | VideoStreamServiceTest:399 | fail-secure |
| TC-STREAM-B09 | start≥total → 416 | PASS | **실동작**: `bytes=99999999999-` → **416** `bytes */18` | VideoStreamServiceTest:375 | |
| TC-STREAM-B10 | 청크 상한 미설정/<1MB → 8MB | PASS | **실동작**: 기본 설정에서 `bytes=0-` 반환 8,388,608B = 8MB 확인. 정적 `:223-228` | VideoStreamServiceTest:289,313 | |
| TC-STREAM-B11 | >64MB → 64MB 클램프(CWE-190) | PASS(정적/테스트) | 정적 `:64,224-227` (`Math.min(streamChunkSize, MAX_CHUNK_SIZE)`) | VideoStreamServiceTest:337,350 | 런타임 설정 변경 불가로 live 미실행 |
| TC-STREAM-B12 | 서명 URL: 비식별 무효 → NOT_FOUND | PASS | **실동작**: `GET /v1/videos/22/stream-url` → **404**; 신고 직후 `12/stream-url` → **404**. 정적 `:119-125` | — | |
| TC-STREAM-B13 | 시크릿 미설정 → 503 | PASS(정적/테스트) | 정적 `:127-133`. 로컬은 `STREAM_SIGN_SECRET` 설정됨(64자) → live 미실행 | VideoStreamServiceTest:507 | |
| TC-STREAM-B14 | userNo 바인딩(재사용 차단) | PASS | **실동작 반증 8종 전부 차단**: u 변조(2001)→401 / u 누락→401 / sig 변조→401 / exp 연장(+3600)→401 / 타 rawSn(24) 재사용→401 / 파라미터 중복 `u=9999&u=1001`→401. 통과(정상 동치)만 200: u URL 인코딩(`%31%30%30%31`), sig 대문자, `exp=+…`, `rawSn=025` | StreamSignedUrlControllerTest:190,202,178,153,165 | 인코딩/정규화 우회 없음 — A 클러스터의 HMAC 인코딩 우회와 동종 결함 **미발견** |
| TC-STREAM-B15 | 스트림 인가: STREAM_SIGNED 또는 REVIEWER/WORKER | **PARTIAL** | **실동작**: 미인증→**401**, PORTAL_USER(PORTAL 채널)→**403**, 유효 서명(Authorization 없음)→**200**. 정적 `VideoController.java:214-225` … 그러나 **배정 무관 WORKER(2002, 배정 0건)가 임의 영상 스트리밍 206** | StreamSignedUrlControllerTest:122,129,229 | 역할/채널 게이트는 설계대로 동작. 영상 단위 인가 부재 → [B-ISSUE-63] |
| TC-STREAM-B16 | 스트림 메타 캐시: null 미캐싱 | PASS | 정적 `:257` `@Cacheable(unless="#result == null")` — 예외·null 미캐싱. 간접 실동작: 'F' 전이 후 즉시 404, 이전 200 결과가 고정되지 않음 | — | |
| TC-STREAM-B17 | 캐시 무효화: 신고('F') 후 즉시 | PASS | **실동작(핵심)**: 신고 직전 `rawSn=12` 스트림 **206**(캐시 warm) → `POST /v1/labels/350/deident-report` **201** → 동일 요청 즉시 **404**. 정적 `DeidentReportService.java:166-168` + `StreamMetaCacheEvictor.evictAfterCommit` | — | 옛 노출본 재서빙 창 **미관측** |

## B-11. 비식별 누락 신고 (라벨링 단계 srcSn, resolve)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-DEID-030 | reason blank → INVALID_INPUT | PASS | **실동작**: `{"reason":""}`·`{"reason":"  "}` → **400** `INVALID_INPUT` | DeidentReportControllerTest:189,201 / ServiceTest:486 | @Valid 단계에서 선차단 |
| TC-DEID-031 | WORKER 본인 배정만(IDOR) | PASS | **실동작**: WORKER(2002, 미배정)가 srcSn=350 신고 → **403** "본인에게 배정되지 않은 영상입니다." PORTAL_USER → 403, 미인증 → 401. 정적 `:116-117` | ControllerTest:139 | |
| TC-DEID-032 | 부모 RAW PESSIMISTIC_WRITE 락(PII TOCTOU) | PASS(정적/테스트) | 정적 `:120-128` `videoRepository.findByRawSnForUpdate` + 대칭 게이트 `ResolutionReservationPersister.java:63-74` | AugmentDeidentConcurrencyIT | 동시 증강 콜백 재현은 live 불가 |
| TC-DEID-033 | 이미 잠금 → CONFLICT | PASS | **실동작**: 잠금 상태 rawSn=8(srcSn=4) 신고 → **409**. 정적 `:131-134` | ControllerTest:168 / ServiceTest:452 | |
| TC-DEID-034 | 전체 라벨 스냅샷+삭제+'F' | PASS(정적/테스트) | **실동작**(라벨 0건 rawSn=12): 201 + `DE_IDENT_YN='F'` + `LS_AUTH_WORK_LOCK` LOCKED(owner 2001) DB 확인. 라벨 보유 영상 파괴 회피로 스냅샷/삭제 경로는 정적 `:140-164` + IT 로 확인 | ServiceTest:172,220,287 / ResetIT:67 | 스냅샷·삭제는 단일 tx(부분 실패 시 전체 롤백) |
| TC-DEID-035 | 개인정보 3필드 리셋(stale PII 방지) | **BLOCKED** | **실행 중 jar 에 코드 부재** — `grep -a resetPrivacyMetaByRawSn /app/app.jar` → 0건, DB 에 `ls_data_src.anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 컬럼 없음(V130 미적용, flyway 최신 129). HEAD 소스에는 구현됨(`:151-155`, `LsDataSrcRepository:70-72`) | ServiceTest:199 / ResetIT:67 | 환경 stale — [B-ISSUE-62] |
| TC-DEID-036 | 락 UNIQUE 위반 → CONFLICT(동시) | PASS | **실동작**: 동일 영상 2차 신고(srcSn=351) → **409**. 정적 `:159-163` | ServiceTest:431 | 선점검(`:131-134`)이 먼저 걸림 — UNIQUE 경로는 unit 커버 |
| TC-DEID-037 | APPROVED → TASK_MODIFIED(LABEL_DELETED) | PASS(정적/테스트) | 정적 `:144-148` (`snapshotted && isReviewApproved` 조건). live 대상(rawSn 13/14/17/19, APPROVED+라벨 보유)은 파괴 회피로 미실행 | ServiceTest:391,413 | |
| TC-DEID-038 | 라벨 0건이면 스킵 | PASS | **실동작**: rawSn=12(라벨 0건) 신고 201, 라벨/이력 생성 없음. 정적 `:140-149` | ServiceTest:261,354 / VersionServiceDeidentSnapshotTest:117 | |
| TC-DEID-039 | resolve: actor null → UNAUTHORIZED | PASS | **실동작**: 무토큰 resolve → **401**. 정적 `:193-195` | ServiceTest:831 / ControllerTest:294 | |
| TC-DEID-040 | resolve: 신고 없음 → NOT_FOUND | PASS | **실동작**: `rprtSn=999999` → **404** "신고를 찾을 수 없습니다." | ServiceTest:840 | |
| TC-DEID-041 | resolve: OPEN 아님 → CONFLICT | PASS | **실동작**: `rprtSn=1`(RESOLVED) → **409** "이미 처리된 신고입니다." | ServiceTest:560 / ControllerTest:258 | |
| TC-DEID-042 | 산출물 미검증 → CONFLICT(fail-closed) | PASS | **실동작**: `rprtSn=2`(OPEN, rawSn=8) REVIEWER resolve → **409**. 직후 DB 확인 — report OPEN 유지 / `DE_IDENT_YN='F'` 유지 / lock LOCKED 유지 (롤백 확인). 정적 `:207-211,332-376` | ServiceTest:666,691 | 실제 재비식별 없이 'Y' 복원되는 경로 **미발견** |
| TC-DEID-043 | resolve 성공 → RESOLVED+락해제+'F'→'Y' | PASS(정적/테스트) | 정적 `:213-230`. live 는 파일 mtime 조작(파일 수정)이 필요해 제약상 미실행 | ServiceTest:544,604,622,709,768,798 | |
| TC-DEID-044 | verifyArtifact: 경로/파일 부재 → 거부 | PASS(정적/테스트) | 정적 `:332-351` | ServiceTest:666,691 | |
| TC-DEID-045 | 시간조건(신고 후 재비식별) 미충족 → 거부 | PASS | **실동작**: `rprtSn=6`(신고 2026-07-25 10:47) resolve → **409**. 해당 비식별본 mtime = 2026-07-15 → 옛 비식별본 배제 실증. `rprtSn=2` 도 동일. 정적 `:353-375` | ServiceTest:735,768,798 | 스큐 관용 60초 |
| TC-DEID-046 | 배치단계 역행 안 함(CWE-664) | PASS | **실동작**: rawSn=12 신고 후 `DATA_STTS_CD` = `COMPLETED` 유지(변경 없음). 정적 `:222-230` | ServiceTest:642 | |
| TC-DEID-047 | resolveOpenReports(자동) 일괄 RESOLVED | PASS(정적/테스트) | 정적 `:278-294` | ServiceTest:855,874 | `opens` 비어도 `releaseRaw`·evict 무조건 호출 — 멱등이라 무해 |
| TC-DEID-048 | 목록: status allowlist 밖 → 400 | PASS | **실동작**: `status=X` → **400**, `status=OPEN' OR 1=1--` → **400**(SQLi 차단), 무지정 → 200(기본 OPEN), WORKER → 403, 미인증 → 401. 정적 `:260-273` + Controller `@Pattern` | ControllerTest:353,361,322,372 | 이중 방어(@Pattern + 서비스 정규화) |
| TC-DEID-049 | 컨트롤러: srcSn 신고(구현됨) | PASS | **실동작**: `POST /v1/labels/350/deident-report` (WORKER 본인 배정) → **201** `data=6`. 정적 `DeidentReportController.java:91-99` | ControllerTest:113,156 | 마킹단계 rawSn 신고는 미구현 → [B-ISSUE-64] |
| TC-DEID-050 | 컨트롤러: resolve — WORKER 본인/REVIEWER 전체 | PASS | **실동작**: WORKER 본인배정 → 409(산출물 게이트, 인가는 통과) / WORKER 타인 → **403** / REVIEWER → 409(동일 게이트) / RESOLVED 재호출 → 409. 정적 `:114-121` | ControllerTest:241,273,284 | 200 성공 경로는 TC-DEID-043 와 동일 제약으로 정적 커버 |

---

## 이슈 상세 (FAIL / PARTIAL / BLOCKED / 확인필요 전건)

### [B-ISSUE-61] TC-STREAM-B04 — 해상도 파생영상 스트리밍이 전면 403(FORBIDDEN)으로 차단됨
- **심각도**: HIGH (기능 차단, 보안 회귀 위험 동반)
- **기대 동작(기대효과)**: 해상도 파생영상(`AUG_TYPE_CD=RESL_*`)도 파생영상으로서 마킹/라벨링/검수 화면에서 재생되어야 한다(CLAUDE.md — 파생영상은 기존 RAW_SN 파이프라인을 그대로 탄다).
- **현재 동작(이슈 내용)**: 파생영상의 비식별 경로가 **raw 저장소 밑**에 기록되는데, 스트리밍 가드는 **deidentified 저장소 base 만** 허용한다.
  - `backend/.../video/service/ResolutionReservationPersister.java:82-84`
    ```java
    Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
    String derivativeVideoPath = resolveSafeDir(base,
        "resolution/" + parent.getRawSn() + "/" + preset.name() + "/video/" + preset.name() + ".mp4").toString();
    ```
  - `backend/.../video/service/ResolutionPersistService.java:143-146` — 이 경로를 그대로 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 으로 기록(`procLog.succeed(videoDst)`).
  - `backend/.../video/service/VideoStreamService.java:266-267` — `baseDir = Paths.get(deidentifiedPath)` 로만 검증 → `resolveSafe` 가 `:353-354` 에서 FORBIDDEN.
  - 런타임 env: `STORAGE_RAW_PATH=/app/storage/raw`, `STORAGE_DEIDENTIFIED_PATH=/app/storage/deidentified` (서로 다름).
  - **실측**: `DE_IDENT_YN='Y'` 인 파생 4건(rawSn 15,16,18,19) 전부 403. 비파생 Y 영상 9건은 전부 206.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT r.raw_sn, r.de_ident_yn, l.de_idntf_file_path_nm FROM public.ls_data_raw r
     JOIN public.ls_deident_proc_log l ON l.data_raw_sn=r.raw_sn AND l.proc_stts_cd='SUCCEEDED'
     WHERE r.orgnl_raw_sn IS NOT NULL;"
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $RT" \
    -H 'Range: bytes=0-9' http://localhost:18081/api/v1/videos/18/stream   # -> 403
  ```
- **영향**: SFR-06-03 해상도 파생 결과물을 화면에서 확인·마킹·검수 불가. 더 위험한 것은 **성급한 수정 방향**이다 — 가드에 rawPath base 를 통째로 추가(과거 export 경로에서 실제로 채택된 우회, memory `cudo246-realtest-issue-batch` ★E)하면 `/app/storage/raw` 아래 **진짜 원본**까지 스트리밍 허용 범위에 들어와 PII 유출(CWE-22/CWE-359)로 전환된다.
- **수정 방향(제안)**: ① 파생영상 비디오 산출물을 **deidentified base 하위**(`{deidPath}/videos/{newRawSn}/…`)에 생성하도록 `ResolutionReservationPersister` 경로 기준을 바꾸는 것이 정공법. ② 불가피하게 raw base 를 허용해야 한다면 base 전체가 아니라 **`{rawPath}/resolution/` 서브트리로 한정한 allowlist** 로 좁히고, 원본 파일 경로(`RAW_FILE_PATH_NM`)와의 동일성 검사를 추가해 원본 직접 서빙을 차단할 것.

### [B-ISSUE-62] TC-DEID-035 — 실행 중 백엔드가 HEAD 미반영(V130 미적용)이라 개인정보 3필드 리셋을 실동작 검증 불가
- **심각도**: MEDIUM (검증 환경 결함 — 기능 결함 아님)
- **기대 동작(기대효과)**: 실동작 검증 대상 스택은 HEAD 소스와 동일 빌드여야 한다.
- **현재 동작(이슈 내용)**:
  - 실행 jar `/app/app.jar` 타임스탬프 `Jul 23 17:10`, 내장 마이그레이션 최대 = `V129__ls_label_add_dtct_type` (`V130__add_manual_env_privacy_meta` 부재).
  - DB `public.flyway_schema_history` 최신 = 129. `public.ls_data_src` 에 `anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 컬럼 **없음**.
  - `grep -a resetPrivacyMetaByRawSn /app/app.jar` → 0건 (HEAD `27b6bb0d` 에서 추가된 `DeidentReportService:151-155` 코드가 배포본에 없음).
  - HEAD 커밋 `27b6bb0d` 는 `DeidentReportService.java`·`LsDataSrc.java`·`LsDataSrcRepository.java` 를 변경했으므로 **B-11 의 5-1 단계는 실행 중 백엔드에 존재하지 않는다**. (반면 `VideoStreamService`/`StreamUrlSigner`/`StreamSignatureFilter`/`VideoController` 는 해당 커밋 미변경 → B-10 live 결과는 HEAD 와 동치.)
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend sh -c "ls -l /app/app.jar; grep -a -o 'V1[23][0-9]__[a-z_]*' /app/app.jar | sort -u | tail -3"
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "\d public.ls_data_src" | grep -i incl_yn
  ```
- **영향**: TC-DEID-035 실동작 검증 불가(BLOCKED). 그 외 이번 회차의 다른 B-11 케이스는 변경 범위 밖 코드라 결과 유효하나, **후속 클러스터(특히 메타 수동입력·export 관련)는 stale 스택에서 위양성/위음성이 날 수 있음**.
- **수정 방향(제안)**: 검증 착수 전 `docker compose -f docker-compose.yml -f docker-compose.local.yml build klid-backend && up -d` 로 HEAD 재빌드 후 flyway 가 V130 을 적용했는지(`flyway_schema_history` 최신 버전) 확인하는 절차를 검증 프로토콜에 고정.

### [B-ISSUE-63] TC-STREAM-B15 — `/stream` 에 영상 단위 인가가 없어 배정되지 않은 WORKER 가 임의 영상을 재생할 수 있음
- **심각도**: MEDIUM (확인필요 — 의도된 광범위 허용인지 정책 확인 필요)
- **기대 동작(기대효과)**: 라벨링/검수 흐름과 동일하게 WORKER 는 **본인 배정 영상**만 열람 가능해야 한다(`LabelAccessGuard.verifyRawAccess` 와 동일 기준). 비식별본이라도 영상 열람은 개인정보 인접 자산이다.
- **현재 동작(이슈 내용)**: `backend/.../video/controller/VideoController.java:220`
  ```java
  @PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")
  ```
  역할만 검사하고 `rawSn` 소유/배정 검증이 없다. `VideoStreamService.stream()` 내부에도 배정 검사 없음.
  - **실측**: 배정 이력이 전혀 없는 WORKER(sub=2002) 토큰으로 `rawSn=17`(다른 사용자 2001 배정) 스트리밍 → **206**. 배정 없는 25/24/23 도 206.
  - 서명 URL 경로도 동일 — `/stream-url` 은 role-gated 이므로 임의 WORKER 가 임의 rawSn 의 서명 URL 을 발급받을 수 있다(`VideoStreamService.java:114-141` 에 배정 검사 없음).
- **재현/확인 경로**:
  ```bash
  # 배정 0건 WORKER 토큰 발급
  curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"role":"WORKER","channel":"INTERNAL","userNo":"2002"}'
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $W2" \
    -H 'Range: bytes=0-100' http://localhost:18081/api/v1/videos/17/stream   # -> 206
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT user_no, raw_data_id, task_type_cd FROM public.ls_task_assignment WHERE user_no=2002;"  # -> 0 rows
  ```
- **영향**: 수평 권한 상승(IDOR, CWE-639 / OWASP API1:2023). 서빙 대상이 비식별본이라 원본 PII 직접 유출은 아니므로 CRITICAL 은 아니나, 내부 사용자 간 데이터 격리가 없다. `UNCERTAINTIES.md #4`(관제 조회 API 광범위 허용은 의도됨)와 동일한 정책 판단이 스트리밍에도 적용되는지 **확인 필요**.
- **수정 방향(제안)**: 의도된 허용이면 UNCERTAINTIES 에 명시 확정. 아니면 `/stream`·`/stream-url` 진입부에 `LabelAccessGuard.verifyRawAccess(rawSn, actor)` 를 적용(REVIEWER 전체 허용 / WORKER 본인 배정) 하고, 서명 URL 은 발급 시점 인가 결과가 서명에 이미 바인딩(userNo)되므로 추가 비용 없음.

### [B-ISSUE-64] 마킹 단계 rawSn 비식별 신고 미구현 (설계 대비 갭)
- **심각도**: LOW (기록 목적 — `UNCERTAINTIES.md #2` 확정에 따라 갭으로 기록)
- **기대 동작(기대효과)**: CLAUDE.md — 비식별 누락 신고는 **마킹 단계(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`)** 와 라벨링 단계(srcSn) 양쪽에서 가능해야 한다(마킹 단계는 "설계 타깃/planned" 로 표기됨).
- **현재 동작(이슈 내용)**: `POST /v1/videos/13/deident-report` → **404**. `grep -rn "videos/{rawSn}/deident-report" backend/src/main/java` → 0건. 신고 엔드포인트는 `DeidentReportController.java:91`(srcSn) 하나뿐.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18081/api/v1/videos/13/deident-report \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{"reason":"x"}'   # -> 404
  ```
- **영향**: 마킹 화면(프레임 추출 이전 단계)에서 비식별 누락을 발견해도 신고 경로가 없어 작업자가 그대로 마킹을 진행하게 된다. 프레임 추출 후(srcSn 존재) 단계에서만 신고 가능.
- **수정 방향(제안)**: 구현 필요 여부를 R1 v1.14 대비로 확정한 뒤, 구현 시 `DeidentReportService.report` 를 rawSn 진입 오버로드로 분리(현재는 srcSn→rawSn 해석에 `LabelAccessGuard.verifyAndGet(srcSn)` 의존).

### [B-ISSUE-65] 증강 파생영상(WINTER/NIGHT/RAIN)이 `DE_IDENT_YN='N'` 으로 고착되어 스트리밍 불가
- **심각도**: LOW (기지의 planned 갭 재확인)
- **기대 동작(기대효과)**: 증강 파생영상도 비식별 원본 복사본이므로 재생 가능해야 한다.
- **현재 동작(이슈 내용)**: rawSn 20/21/22 (`ORGNL_RAW_SN=13`, 외부 증강 파생) 전부 `DE_IDENT_YN='N'`, `DATA_STTS_CD='FAILED'` → 스트리밍 **404**. CLAUDE.md 기재대로 "증강 적재 경로는 `VideoIngestedEvent` 미발행 — 선두 비식별 자동화 미연동(planned)" 상태가 데이터로 확인됨.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, orgnl_raw_sn, de_ident_yn, data_stts_cd FROM public.ls_data_raw WHERE orgnl_raw_sn=13;"
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/20/stream  # -> 404
  ```
- **영향**: 증강 결과 검수(SCR-AUG-002) 화면에서 영상 재생 불가. 단 fail-closed 방향(원본 노출 없음)이라 보안 위험은 없음.
- **수정 방향(제안)**: 증강 적재 경로에도 `VideoIngestedEvent` 발행 또는 파생 확정 시 `markDeidentified("Y")`(해상도 파생 `ResolutionPersistService:141` 과 동일 처리) 적용 여부를 결정.

---

## 부수 관찰 (결함 아님 / 참고)

- **과거 데이터 잔존**: rprtSn=1(rawSn=4)은 `RESOLVED` + 작업락 `RELEASED(MANUAL_DEIDENT_DONE)` 인데 `LS_DATA_RAW.DE_IDENT_YN` 이 여전히 `'F'`. 해당 resolve 는 2026-07-15 로, `'F'→'Y'` 복원 코드(`DeidentReportService:226-230`)가 도입되기 이전 데이터로 보임. 현행 코드 기준 회귀 아님.
- **`resolveOpenReports`**: OPEN 신고가 0건이어도 `workLockService.releaseRaw(...)` 와 캐시 evict 를 무조건 호출(`:287-289`). 멱등이라 무해하나 의도 확인 시 참고.
- **200 응답의 `Content-Range`**: Range 미지정 200 응답에도 `Content-Range: bytes 0-17/18` 이 붙는다(`ResourceRegionHttpMessageConverter` 동작). RFC 상 200 에 `Content-Range` 는 무의미하나 클라이언트 영향 미관측.

## 근거 라인 드리프트

**0건.** B-10/B-11 의 근거 `file:line` 38개 참조를 HEAD 소스와 전수 대조한 결과 모두 일치(`VideoStreamService.java`, `VideoController.java`, `DeidentReportService.java`, `DeidentReportController.java`).
단 `TC-STREAM-B16` 의 `VideoStreamService.java:252` 는 실코드가 아닌 javadoc 라인이고 실제 `unless` 절은 `:257` — 표기 자체는 `252,257` 로 되어 있어 드리프트로 계수하지 않음.

## 요약

- 총 **38건** / PASS **36** / FAIL **0** / PARTIAL **1**(TC-STREAM-B15) / BLOCKED **1**(TC-DEID-035, 환경 stale) / N/A 0 / 확인필요 0
  - PASS 36 중 **실동작 직접 확인 25건**, 정적+기존 테스트 커버 11건(런타임 설정 변경·파일 mtime 조작·라벨 파괴가 필요해 live 미실행)
- 근거 라인 드리프트: **0건**
- **PII 유출 경로 미발견** — 원본 노출 우회(비식별 미완료/'F'/'N'), 경로 이탈(CWE-22), 서명 우회(u·exp·sig·rawSn 변조 및 인코딩/중복 파라미터 8종), Range 경계 초과, resolve 무검증 통과를 모두 반증 시도했으나 전부 fail-closed 로 차단됨.
- 신규 이슈 **5건**: HIGH 1([B-ISSUE-61] 해상도 파생 스트리밍 403 전면 차단), MEDIUM 2([B-ISSUE-62] 검증 스택 stale, [B-ISSUE-63] `/stream` 영상 단위 인가 부재), LOW 2([B-ISSUE-64] 마킹단계 신고 미구현, [B-ISSUE-65] 증강 파생 `DE_IDENT_YN='N'` 고착)

# B-batch-deidentify 검증 (Part 5) — B-12 / B-13 / B-14

- 대상: `docs/test-cases/B-batch-deidentify.md` 242~294행 (TC-BATCH-150~162, TC-DEID-060~080, TC-BATCH-170~172)
- 검증일: 2026-07-25 / 1차
- 스택: klid-backend(:18081, SPRING_PROFILES_ACTIVE=local, KPST_DEID_ENABLED=true → `http://klid-mock-server:9400`) · klid-postgres(`klid_system`/`public`) · klid-mock-server(:9400)
- 실동작 관측 구간: backend 로그 10:36~10:46, mock 로그 동시간대, DB(`ls_deident_proc_log`·`ls_bat_rty_wtng`·`ls_data_raw`·`qrtz_*`) 실조회, `POST /v1/videos/{rawSn}/batch/retry` 실 curl

> **환경 제약(판정에 반영)**: `application-local.yml:50` `authoring.batch.enabled=false` → Quartz `batchPipelineJob`·`batchRetryJob`·`controlTrainingVideoScanJob` **트리거 미등록**(DB `qrtz_job_details` 3행: bootstrapJob / kpstDeidentPollJob / datasetExportPendingSweepJob). 배치 본체는 `MarkingBatchBridge`→`AsyncBatchRunner`(@Async)로 구동되므로 **재시도 큐 적재는 실동작 관측 가능**하나 **재시도 폴러 발화는 로컬에서 관측 불가**(→ 정적+IT 판정).

---

## B-12. 재처리 / 재시도 큐 (2노드 안전)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-150 | 재처리: rawSn null → INVALID_INPUT | PASS | 정적 `BatchReprocessService.java:58-60` ✓ / **실동작**: `POST /v1/videos/0/batch/retry` → **400** `retryBatch.rawSn: rawSn 은 1 이상이어야 합니다.`(컨트롤러 `@Min` 선행 차단, 서비스 null 가드는 방어심도) | — | HTTP 경로에서 null 은 도달 불가(PathVariable). 서비스 가드 유지 타당 |
| TC-BATCH-151 | 재처리: 영상 미존재 → NOT_FOUND | PASS | 정적 `:61-64` ✓ / **실동작**: rawSn=999999 → **404** `{"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}` | `BatchReprocessServiceTest:99` 존재하지_않는_영상_재처리시_NOT_FOUND_404 | |
| TC-BATCH-152 | 재처리: FAILED→PROCESSING 원자 클레임 성공 | PASS | 정적 `:66-79` ✓ → `tryClaimReprocessFromFailed`(REQUIRES_NEW 조건부 UPDATE) → `clearIfIdle` → `orchestrator.process` 순서 확인 | `BatchReprocessServiceTest:64` 배치재처리API_성공시_재배치가_기동된다 / `:76` verify(clearIfIdle) | 성공 경로 실행은 **상태 변경 유발**이라 실동작 미수행(제약 준수). 부정 경로 2건은 실동작 확인 |
| TC-BATCH-153 | 재처리: FAILED 아님/이미 클레임 → CONFLICT | PASS | 정적 `:68-72` ✓ / **실동작**: rawSn=17(COMPLETED) → **409** `CONFLICT` "배치가 실패(FAILED)한 영상만…" | `BatchReprocessServiceTest:47`, `:81` 배치재처리_동시요청시_한쪽만_기동된다 | 무인증 401 도 실측(`@PreAuthorize("hasRole('REVIEWER')")` `BatchReprocessController.java:50`) |
| TC-BATCH-154 | 재처리 클레임: raw 우선, 없으면 status FAILED→PROCESSING | PASS | 정적 `BatchTransitionService.java:268-281` ✓ — `videoRepository.claimReprocessFromFailed` 1 이면 즉시 true, 아니면 `rawDataStatusRepository.claimReprocessFromFailed` | `BatchReprocessServiceTest:81` (간접) | 두 리포지토리 모두 `claimReprocessFromFailed` 조건부 UPDATE 보유 확인 |
| TC-BATCH-155 | 재시도 등록: 최초 실패 PENDING(ON CONFLICT DO NOTHING) | **PASS (실동작)** | 정적 `BatchRetryQueue.java:66-92` ✓ + `LsBatRtyWtngRepository.java:44-49` `INSERT … ON CONFLICT (RAW_SN) DO NOTHING` / **실동작**: 로그 `[BatchRetry] enqueued rawSn=15 attempt=1 delaySec=60` → DB `ls_bat_rty_wtng` 4행 `rty_nmtm=1, stts_cd=PENDING` | `BatchRetryQueueIT:70`, `:158` | 실 DB 행 확인(rawSn 15/16/23/24) |
| TC-BATCH-156 | 재시도 등록: 동시 최초 실패 UK 경쟁 흡수 | PASS | 정적 `:71-74` ✓ — ① `insertIfAbsent`(원자 upsert) → ② `findByRawSnForUpdate`(`@Lock(PESSIMISTIC_WRITE)` = SELECT … FOR UPDATE) 2단 패턴. **PG unique 위반이 tx 전체를 abort 시키는 함정을 회피**(같은 tx 내 예외 재시도 없음, `REQUIRES_NEW` 즉시 커밋) | `BatchRetryQueueIT:133` 동시_최초등록시_UK위반이_전파되지_않는다 | 반증 시도: 예외 catch-후-재조회 패턴이 아님을 확인 → 함정 미해당 |
| TC-BATCH-157 | 재시도: 지수백오프(60,120,240…) shift 30 캡 | **PASS (실동작)** | 정적 `:85-88` ✓ `shift=min(attempt-1,30)`, `delay=initial×2^shift` / **실동작**: reg_dt `10:40:18.018` → rty_prnmnt_dt `10:41:18.028` = **정확히 60s**(attempt=1) | `BatchRetryQueueIT` (간접) | attempt≥2 백오프는 로컬 폴러 미발화로 미관측(정적 판정) |
| TC-BATCH-158 | 재시도: max 초과 → EXHAUSTED false | PASS | 정적 `:78-84` ✓ — `attempt>maxAttempts` → `markExhausted()`(삭제 아님, 이력 보존) + false. `pollReady` 는 PENDING 만 조회 → EXHAUSTED 는 dead-letter 로 영구 제외 | `BatchRetryQueueIT:111` 최대시도_초과시_소진마킹되고_재폴링되지_않는다 | `authoring.batch.retry.max-attempts` 기본 3 (application.yml:187) |
| TC-BATCH-159 | 폴링 클레임: 2노드 동시 폴링 직렬화 | **PARTIAL** | 정적 `:100-112` ✓ — `claimAtomically`(`UPDATE … SET STTS='RETRYING' WHERE STTS='PENDING'`) CAS, 영향행수 1 만 반환. 후보 10건 순회로 경쟁 패배 시 다음 후보. **클레임 자체는 원자적** / ⚠ **RETRYING 고아 행 복구 경로 없음** → B-ISSUE-83 | `BatchRetryQueueIT:84` 재시도_동시_폴링시_한_노드만_클레임한다 | 로컬 폴러 미등록으로 실발화 미관측 |
| TC-BATCH-160 | 재시도 등록 노드 ≠ 발화 노드(DB 영속) | PASS | 정적 `:16-31` Javadoc + `LsBatRtyWtng` 엔티티(`LS_BAT_RTY_WTNG` 영속) ✓ — 인메모리 `ConcurrentHashMap` 제거 확인. DB 실행 `ls_bat_rty_wtng` 행 실존 | `BatchRetryQueueIT:70` 재시도큐_등록후_다른_노드_폴링이_항목을_회수한다 | |
| TC-BATCH-161 | clearIfIdle: RETRYING 보존, PENDING/EXHAUSTED만 삭제 | PASS | 정적 `:133-146` + `LsBatRtyWtngRepository.java:79-81` `DELETE … WHERE RAW_SN=:rawSn AND STTS_CD <> 'RETRYING'` ✓ | `BatchReprocessServiceTest:60/76/95` verify clearIfIdle 호출/미호출 | |
| TC-BATCH-162 | clear: 성공 시 전체 삭제 | PASS | 정적 `:124-131` `deleteByRawSn` ✓ / 호출부 `BatchOrchestrator.java:125`(성공 시) 확인. **실동작**: rawSn=26 COMPLETED 후 `ls_bat_rty_wtng` 에 26 행 없음 | `BatchRetryQueueIT:158` 성공_clear시_항목이_제거된다 | 성공 시 카운터 리셋 = 의도된 정책 |

---

## B-13. KPST 비식별 폴링 (완료감지·타임아웃)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-DEID-060 | 위탁: createProject → procLog WAITING, 미전이 | **PASS (실동작)** | 정적 `KpstDeidentService.java:161-210` ✓ / **실동작**: mock `POST /project` 4회 → 로그 `[KpstDeid] submitted rawSn=23 prjId=3` … `rawSn=26 prjId=6`, 다음 tick `polling targets count=3` (= WAITING 조회됨) | `KpstDeidentServiceTest:127/147/164/184` | self-fill 없음 — prjId 는 mock 응답값(1~6 순증) |
| TC-DEID-061 | 위탁: 원본경로 부모 없음 → INVALID_INPUT(CWE-22) | PASS | 정적 `:177-182` ✓ | `KpstDeidentServiceTest:216` 원본경로_부모디렉터리가_null이면_F마킹하고_예외전파_createProject미호출 | |
| TC-DEID-062 | 위탁: 실패 → 'F' 마킹+EXTERNAL_API_ERROR | PASS | 정적 `:211-217` ✓ — `procLog.fail` + `markDeidentified("F")` + `EXTERNAL_API_ERROR` 전파, CWE-209 준수(클래스명만) | `KpstDeidentServiceTest:202` | |
| TC-DEID-063 | 위탁: export 디렉터리 정리(stale 오회수 방지) | PASS | 정적 `:238-269` ✓ — base 하위 단언 → `Files.list`(비재귀) → `isRegularFile(NOFOLLOW_LINKS)` 만 삭제 | `KpstDeidentServiceTest:234/255/298` (stale 정리·심링크 미삭제·빈디렉터리 noop 3건) | |
| TC-DEID-064 | 폴링: procState=2 전체완료(AND) → 다운로드+완료 | **PASS (실동작)** | 정적 `:326-369`(AND 판정 `allDatasetsCompleted:610-622`) ✓ / **실동작**: mock `GET /retrieve_progress` → `[KpstDeid] poll completed rawSn=23 prjId=3 datasetId=3`(24/25/26 동일) → DB `poll_stts_cd=DOWNLOADED, proc_stts_cd=SUCCEEDED`, `ls_data_raw.de_ident_yn='Y'` | `KpstDeidentServiceTest:593`, `KpstDeidentPollIntegrationTest:152` | **회귀 없음**: 과거 완료를 `'F'` 로 오종결하던 결함 재발 안 함(4건 모두 SUCCEEDED/Y) |
| TC-DEID-065 | 폴링: 터미널 실패(3/4/99) 우선 → 즉시 'F' | PASS | 정적 `:313-323` ✓ — `anyDatasetFailed` 를 `allDatasetsCompleted` **앞에** 호출(우선순위 확인) | `KpstDeidentServiceTest:617/636/655/693` (99·3·4·혼합) | |
| TC-DEID-066 | 폴링: procState=99(오류 sentinel) 종결 | PASS | 정적 `:87-91` (문서 `88-91` 대비 1행 오차) ✓ `PROC_STATE_TERMINAL_FAILED={3,4,99}` | `KpstDeidentServiceTest:617`, `:674`(5는 도메인 밖 → 실패 아님) | |
| TC-DEID-067 | 폴링: prjId null(위탁 미완) → 타임아웃 검사만 | PASS | 정적 `:283-287` ✓ | `KpstDeidentServiceTest:767` prjId_미상이면_타임아웃검사만_수행하고_외부호출하지_않는다 | |
| TC-DEID-068 | 폴링: retrieveProgress 예외 → 타임아웃 평가 | PASS | 정적 `:288-302` ✓ — K1 수정(예외 경로에서도 `markTimeoutIfExpired`) 확인 | `KpstDeidentServiceTest:782`, `:802` (경과 전 skip / 초과 시 F) | 무기한 stuck 차단 |
| TC-DEID-069 | 폴링: 완료지만 fileName bad → 터미널 처리 | PASS | 정적 `:337-351` ✓ — `downloadResult` 예외를 `pollOne` 내부에서 잡아 REDEIDENT/비REDEIDENT 분기 terminal | `KpstDeidentServiceTest:349/366/384/398` | |
| TC-DEID-070 | 폴링: 완료지만 산출물 0바이트/미존재 → 'F' | PASS | 정적 `:352-366` ✓ (`isUsableDeidFile:391-402`, NOFOLLOW_LINKS) / **실동작 이력**: `ls_deident_proc_log` proc_log_sn=13 `err_cd=DEIDENT_INCOMPLETE, poll_stts_cd=FAILED, attempt=201` — 'Y' 위장 없음 | `KpstDeidentServiceTest:455/472/490/510/531` | |
| TC-DEID-071 | 폴링: 진행중 → 시도 증가+타임아웃 검사 | PASS | 정적 `:383-387` ✓ / **실동작 이력**: proc_log_sn=12 `poll_atmpt_cnt=240`(=`poll-max-attempts` 기본값) `err_cd=DEIDENT_TIMEOUT` → 시도 카운터 실증가 확인 | `KpstDeidentServiceTest:737/752`, `KpstDeidentTxServiceTest:189/202/218` | |
| TC-DEID-072 | fileName 회수: {stem}-mask{ext} 변환(실측 계약) | **PARTIAL** | 정적 `:424-459` ✓ (`toMaskName`, `MASK_SUFFIX="-mask"`) / ⚠ **실동작으로는 미검증** — mock 이 `fileName=ds.name`(= 산출물 basename `{stem}_{yyyyMMddHHmm}_mask{ext}`)을 반환해 **실서버 계약(fileName=원본 입력 절대경로, 산출물=`{stem}-mask{ext}`)과 불일치**. 1차 경로 `clip-9101_202607250139_mask-mask.mp4` 는 항상 miss → 폴백으로만 회수됨 → **B-ISSUE-84** | `KpstDeidentServiceTest:314` toMaskName_…, `:896` 경로형_fileName이면_basename추출후_mask접미사경로로_회수한다 | 단위 테스트로는 실계약 커버됨. 회귀(과거 'F' 오종결)의 E2E 감시망만 공백 |
| TC-DEID-073 | fileName 회수 폴백: 단일 산출물 스캔 | **PASS (실동작)** | 정적 `:470-492` ✓ / **실동작**: `/app/storage/deidentified/videos/23/` 에 산출물 1개 → `scanSingleUsable` 회수 → DB `de_idntf_file_path_nm=/app/storage/deidentified/videos/23/clip-9101_202607250139_mask.mp4` | `KpstDeidentServiceTest:916`(1개), `:933`(2개↑ 모호 → failPolling) | 0개→null·2개↑→INVALID 규칙 정적 확인 |
| TC-DEID-074 | sanitizeFileName: basename만(CWE-22) | PASS | 정적 `:513-534` ✓ — `Paths.get`→`getFileName()`, `/`·`\`·`..` 잔존 거부, `InvalidPathException`→INVALID_INPUT 정규화(원문 미노출) | `KpstDeidentServiceTest:415`(NUL), `:950`(상위참조 섞임) | |
| TC-DEID-075 | 폴링 잡: 대상 없으면 noop | **PASS (실동작)** | 정적 `KpstDeidentPollJob.java:50-54` ✓ / **실동작**: 10:36:32~10:39:32 매 30s `[KpstDeidPoll] no pending poll target — skipping tick` DEBUG, 동시간대 mock 로그에 `retrieve_progress` **0건** | `KpstDeidentPollJobTest:67` 대상이_없으면_외부호출없이_즉시종료한다 | 외부 미호출 실증 |
| TC-DEID-076 | 폴링 잡: 건별 try/catch 격리 | PASS | 정적 `:56-64` ✓ | `KpstDeidentPollJobTest:77` 한_작업_폴링실패가_다른_작업을_막지_않는다 | |
| TC-DEID-077 | 폴링 잡: 동시 실행 금지 | **PARTIAL** | 정적 `:31-32` `@DisallowConcurrentExecution` ✓ / **실동작**: DB `qrtz_job_details.is_nonconcurrent='t'`(kpstDeidentPollJob) — **단일 노드 직렬화 확인** / ⚠ 2노드 A-A 에서는 클러스터링 OFF(→B-ISSUE-81) + 폴링 대상 조회(`findByPollSttsCdIn`)에 **원자 클레임 없음** → 중복 폴링·중복 완료 가능 → **B-ISSUE-82** | `KpstDeidentPollJobTest` | |
| TC-DEID-078 | 폴링 잡: 재기동 복원(DB 조회) | PASS | 정적 `:38-50` + `LsDeidentProcLogRepository.java:47 findByPollSttsCdIn` ✓ (인메모리 상태 없음) / **실동작**: backend 재기동(Up 6m) 이후 tick 이 DB 에서 대상 조회 재개(`polling targets count=3`) | `KpstDeidentPollJobTest:51`, `KpstDeidentPollIntegrationTest:230` 통합_재기동_WAITING건이_재폴링_조회된다 | |
| TC-DEID-079 | completeDeidentification: 파일무효 F-마킹 보정 | PASS | 정적 `KpstDeidentService.java:542-555` + `KpstDeidentTxService.java:116-120 markRawDeidentFailed`(별도 REQUIRES_NEW) ✓ — 롤백에 F-마킹이 휩쓸리지 않음 | `KpstDeidentServiceTest:828/843`, `KpstDeidentTxServiceTest:444` | |
| TC-DEID-080 | KPST 조건부 빈: kpst.deid.enabled=false 미등록 | PASS | 정적 `:62 @ConditionalOnProperty(prefix="kpst.deid", name="enabled", havingValue="true")` ✓ (`KpstDeidentTxService:39`, `KpstDeidentPollTriggerConfig:20` 동일 게이팅) / **실동작**: 컨테이너 `KPST_DEID_ENABLED=true` → 빈·트리거 등록 확인(`qrtz_triggers.kpstDeidentPollTrigger`) | — | false 케이스는 재기동 필요라 미실증(정적) |

---

## B-14. Quartz 클러스터링 / 인프라

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-BATCH-170 | Quartz JobStore = controlDataSource(PG) | **PASS (실동작)** | 정적 `QuartzConfig.java:30-37` ✓ (`SchedulerFactoryBeanCustomizer` + `@Qualifier("controlDataSource")`, `DataSourceAutoConfiguration` exclude 확인 `application.yml:74-77`) / **실동작**: `klid_system` DB 에 `qrtz_*` 11개 테이블 실존, `qrtz_job_details` 3행·`qrtz_triggers` 2행 등록, `driverDelegateClass=PostgreSQLDelegate`(`application.yml:66`), `useProperties=true` | `SchedulerHealthControllerTest:50` bootstrapJobRegistered | |
| TC-BATCH-171 | 2노드 A-A: 동일 잡 중복 실행 방지 | **PARTIAL** | ⚠ **실동작 반증**: `qrtz_scheduler_state` **0행**, `qrtz_fired_triggers.instance_name = "NON_CLUSTERED"` → **런타임 클러스터링 비활성 확정**. 설정 `application.yml:72 org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}` — **기본값 false**, 4개 프로파일 yml 어디에도 override 없음, 온프렘 배포 템플릿 `deploy/onprem/config/backend/env.template:175 QUARTZ_CLUSTERED=false`. `qrtz_locks` 는 2행(TRIGGER_ACCESS/STATE_ACCESS) 존재하나 non-clustered 모드에서는 클러스터 조율에 쓰이지 않음 → **B-ISSUE-81** | — | 근거 라인 드리프트 1건(문서 `QuartzConfig.java:31-37` → 실제 `application.yml:69-73`) |
| TC-BATCH-172 | AsyncBatchRunner: 예외 삼킴(@Async) | PASS | 정적 `AsyncBatchRunner.java:20-28` ✓ `try{process}catch(Exception){log.error}` — 재던짐 없음 / **실동작**: `[AsyncBatchRunner] starting batch rawSn=15/16/23/24/26` (`batch-async-1/2` 스레드) 관측. 예외 삼킴 분기는 `BatchOrchestrator` 가 내부에서 catch 하므로 미도달(로그 `[BatchOrchestrator] failed … willRetry=true` 로 정상 종료) | **없음** (다른 테스트에서 mock 으로만 사용) | 전용 단위테스트 부재 |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [B-ISSUE-81] TC-BATCH-171 — Quartz 클러스터링이 기본 비활성이며 온프렘 배포 템플릿도 false → 2노드 Active-Active 시 스케줄러 잡 중복 실행
- **심각도**: HIGH (운영/구성)
- **기대 동작(기대효과)**: CLAUDE.md "배치 성능" — *"주 서버 2노드 Active-Active 이중화 … **Quartz 클러스터링 적용**(PostgreSQL JobStore 락으로 잡 중복 방지)"*. 2노드 동시 기동 시 `kpstDeidentPollJob`·`batchPipelineJob`·`batchRetryJob`·`controlTrainingVideoScanJob`·`datasetExportPendingSweepJob` 각 트리거가 **정확히 1회만** 발화해야 한다.
- **현재 동작(이슈 내용)**:
  - `backend/src/main/resources/application.yml:69-73`
    ```yaml
    # 이중화(HA) 노드만 true — local/dev 단일 인스턴스는 false 유지.
    org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}
    org.quartz.jobStore.clusterCheckinInterval: ${QUARTZ_CHECKIN_MS:20000}
    ```
    → **기본값 false**. `application-{local,dev,stg,prd}.yml` 어디에도 `QUARTZ_CLUSTERED` override 없음(grep 0건).
  - `deploy/onprem/config/backend/env.template:175` → `QUARTZ_CLUSTERED=false` (배포 산출물 기본값도 false)
  - **런타임 실측**: `qrtz_scheduler_state` 0행 (클러스터 모드에서만 체크인 행이 생성됨) · `qrtz_fired_triggers.instance_name = "NON_CLUSTERED"`
  - 결과: 2노드를 띄우면서 두 노드 모두 `QUARTZ_CLUSTERED=true` 를 **명시 설정하지 않으면** 두 노드가 서로를 모른 채 동일 트리거를 각자 발화한다. `@DisallowConcurrentExecution` 은 **JVM/스케줄러 인스턴스 내부**에서만 유효하므로 노드 간 중복을 막지 못한다.
  - 파급: 관제 학습영상 스캔 이중 적재, KPST 폴링 이중 완료 처리(→ B-ISSUE-82), 배치 재시도 이중 실행, export sweep 이중 쓰기.
  - 문서 충돌: `CLAUDE.md`("클러스터링 적용") vs `docs/design/D5-아키텍처설계서.md:126`("기본은 단일 인스턴스, 이중화 시 옵션 지원") — **D5 가 코드와 일치**, CLAUDE.md 서술이 앞서감.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select * from qrtz_scheduler_state;"          # 0 rows
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select instance_name from qrtz_fired_triggers;" # NON_CLUSTERED
  grep -rn "QUARTZ_CLUSTERED" backend/src/main/resources deploy/onprem/config/backend/env.template
  ```
- **영향**: CWE-362 (Race Condition) — 노드 간 잡 중복 실행. 데이터 이중 적재/이중 상태전이. 배포 구성 실수 1건으로 조용히 발현되며 로그만으로는 감지 어려움.
- **수정 방향(제안)**: ① 온프렘 2노드 배포 프로파일에서 `QUARTZ_CLUSTERED=true` 를 **기본값으로** 하거나, 기동 시 "노드 수 ≥ 2인데 isClustered=false" 를 감지해 **fail-closed 부트 거부 또는 WARN 배너**(`DevToggleStartupWarner` 패턴 재사용). ② `env.template` 주석에 "2노드면 반드시 true" 를 체크리스트 항목으로 승격. ③ CLAUDE.md 를 D5 서술("옵션 지원")에 맞춰 정정하거나 반대로 기본값을 true 로 뒤집기 — 어느 쪽이든 **문서·기본값 단일화** 필요. ④ 클러스터 모드 필수 조건인 노드 클럭 동기화(NTP) 를 배포 체크리스트에 명시.

### [B-ISSUE-82] TC-DEID-077 — KPST 폴링 대상 조회에 원자 클레임이 없어, 비클러스터 2노드에서 동일 위탁 건 중복 폴링·중복 완료 처리 가능
- **심각도**: HIGH (B-ISSUE-81 이 선행 조건)
- **기대 동작(기대효과)**: 동일 `procLog` 를 두 노드가 동시에 완료 처리하지 않는다(중복 다운로드/전이 방지 — `KpstDeidentPollJob` Javadoc `:23` 이 명시한 방어 목표).
- **현재 동작(이슈 내용)**:
  - `KpstDeidentPollJob.java:50`
    ```java
    List<LsDeidentProcLog> targets = procLogRepository.findByPollSttsCdIn(POLL_TARGET_STATUSES);
    ```
    `LsDeidentProcLogRepository.java:47` — **평범한 파생 쿼리**. `BatchRetryQueue.claimAtomically`(PENDING→RETRYING CAS)에 해당하는 **소유권 클레임이 없다**. `POLLING` 상태는 "처리중" 이 아니라 단순 진행 표시라 다른 노드도 그대로 재조회한다.
  - `KpstDeidentTxService.finishDownloadAndComplete:59-70` 에도 멱등 가드(예: `poll_stts_cd='WAITING'/'POLLING'` 조건부 UPDATE)가 없어, 두 노드가 동시에 완료를 커밋하면 `markDownloaded`·`applyCompletion`(REDEIDENT 시 `deidentFrameAttacher.attachDeidentFrames`)이 **두 번** 수행된다.
  - 단일 노드에서는 `@DisallowConcurrentExecution`(DB `is_nonconcurrent='t'` 실확인)이 막으므로 현행 로컬/단일 배포에서는 발현하지 않음.
  - 부가: `findByPollSttsCdIn` 에 페이징/상한이 없어 대기 건이 누적되면 전건 로드(CWE-770 소지, LOW).
- **재현/확인 경로**:
  ```bash
  grep -n "findByPollSttsCdIn" backend/src/main/java/kr/co/cudo/authoring/batch/repository/LsDeidentProcLogRepository.java
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select proc_log_sn,poll_stts_cd from ls_deident_proc_log where poll_stts_cd in ('WAITING','POLLING');"
  # 2노드 재현: QUARTZ_CLUSTERED 미설정 상태로 backend 2 인스턴스 기동 → 동일 procLogSn 에 대해 [KpstDeid] poll completed 로그 2회
  ```
- **영향**: CWE-362. 중복 프레임 attach/캐시 무효화, 알림 이중 발송, `LS_DATA_SRC` 갱신 경쟁.
- **수정 방향(제안)**: `BatchRetryQueue` 와 동일 패턴 적용 — ① `POLL_CLAIMED`(또는 `POLLING` 의미 강화) 상태로 조건부 원자 UPDATE 클레임 후 그 건만 폴링, ② 또는 `finishDownloadAndComplete` 진입 시 `UPDATE … SET poll_stts_cd='DOWNLOADED' WHERE proc_log_sn=? AND poll_stts_cd IN ('WAITING','POLLING')` 영향행수 1 검사로 멱등 게이트. ③ 조회에 `Pageable` 상한 추가. (B-ISSUE-81 해결 시 위험은 크게 낮아지나, 클러스터링을 켜도 잡 자체는 한 노드에서만 돌 뿐이므로 ①/② 는 방어심도로 유효.)

### [B-ISSUE-83] TC-BATCH-159 — 재시도 큐 `RETRYING` 클레임 후 노드 사멸 시 복구 경로가 없어 해당 재시도가 영구 유실
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재시도 항목을 클레임한 노드가 처리 중 죽어도, 다른 노드/다음 tick 이 그 항목을 회수해 재시도를 이어간다(2노드 A-A 내구성 — 이 큐가 DB 로 옮겨진 애초 목적).
- **현재 동작(이슈 내용)**:
  - `BatchRetryQueue.pollReady:100-112` 가 `PENDING → RETRYING` 으로 CAS 클레임한다.
  - 이후 `RETRYING → PENDING` 복귀는 **오직** `BatchRetryQuartzJob.execute` 가 정상적으로 예외를 받아 `enqueueIfRetryable` 을 다시 부를 때만 일어난다(`BatchRetryQuartzJob.java:70`).
  - 프로세스 kill / OOM / 노드 장애로 그 사이가 끊기면 행은 `RETRYING` 으로 **영구 고착**된다:
    - `pollReady` 는 `STTS_CD='PENDING'` 만 조회 → 다시 안 잡힘
    - `clearIfIdle`(`LsBatRtyWtngRepository.java:80`)은 `STTS_CD <> 'RETRYING'` 이라 **의도적으로 보존** → 수동 재처리로도 리셋되지 않음
    - **stale RETRYING 을 되살리는 sweeper/타임아웃 잡이 코드베이스에 없음** (`grep -rn "RETRYING" backend/src/main/java` → `BatchRetryQueue`/엔티티 상수와 무관한 `controlnotify` 폴백만 매칭)
  - 결과: 해당 rawSn 은 `FAILED` 로 남고 자동 재시도가 무음 중단된다(REVIEWER 수동 재처리 전까지). 이는 in-memory 큐를 DB 로 옮기며 없앴다던 "재시도 무음 유실" 이 다른 형태로 잔존하는 것.
- **재현/확인 경로**:
  ```sql
  -- 고아 판별 쿼리 (현재 로컬엔 RETRYING 행 없음 — 폴러 미등록이라 클레임 자체가 미발생)
  select bat_rty_sn, raw_sn, rty_nmtm, stts_cd, mdfcn_dt
    from ls_bat_rty_wtng
   where stts_cd = 'RETRYING' and mdfcn_dt < now() - interval '30 minutes';
  ```
  재현: 폴러 활성 환경에서 `pollReady` 클레임 직후 backend 컨테이너 `docker kill` → 재기동 후 해당 행이 `RETRYING` 유지되고 재시도 미발화.
- **영향**: CWE-459 (Incomplete Cleanup) / 가용성. 실패 영상이 자동 복구 대상에서 조용히 이탈.
- **수정 방향(제안)**: ① `MDFCN_DT` 기준 stale 임계(예: 배치 최대 실행시간 ×2)를 넘긴 `RETRYING` 행을 조건부 UPDATE 로 `PENDING` 복귀시키는 sweeper 를 재시도 잡 앞단에 추가(`UPDATE … SET STTS_CD='PENDING' WHERE STTS_CD='RETRYING' AND MDFCN_DT < :cutoff`, 원자 CAS). ② 또는 `Quartz` `requestRecovery(true)` 를 재시도 잡에 부여해 노드 사망 시 복구 발화(단, 클러스터링 활성 전제). ③ `EXHAUSTED`/장기 `RETRYING` 깊이를 Micrometer 게이지로 노출(현재 `ControlNotifyMetrics` 만 유사 게이지 보유 — 배치 재시도 큐는 미계측).

### [B-ISSUE-84] TC-DEID-072 — mock-server 의 `retrieve_progress.fileName` 계약이 실서버와 달라, 1차 회수 경로(`{stem}-mask{ext}`)가 로컬 E2E 로 전혀 검증되지 않음
- **심각도**: MEDIUM (검증 커버리지 갭 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 목업 경유 E2E 가 실서버 계약을 재현해, 과거 회귀(“`fileName` 을 결과 파일명으로 오해 → 완료를 `'F'` 로 오종결”, 커밋 74be3a8/PR#27)가 다시 발생하면 로컬에서 잡힌다.
- **현재 동작(이슈 내용)**:
  - **실서버 계약(2026-07-21 curl/ll 실측)**: `fileName` = **원본 입력파일 절대경로**(예 `/nas-.../raw/001.mp4`), 산출물 = `export_path/001-mask.mp4` (`-` 하이픈).
  - **mock 구현**: `mock-server/app/routers/deid.py:86` `"fileName": ds.name` — `ds.name` 은 `services/deid_sim.py:157-159 MASK_SUFFIX="_mask"` 로 만든 **산출물 basename** `{stem}_{yyyyMMddHHmm}_mask{ext}`. 즉 (a) 경로가 아니라 basename 이고 (b) 접미사가 `_mask`(언더스코어)다.
  - **실동작 결과**: `sanitizeFileName("clip-9101_202607250139_mask.mp4")` → 동일 → `toMaskName` 이 `-mask` 로 끝나지 않는다고 판단(`KpstDeidentService.java:458`) → 1차 경로 `clip-9101_202607250139_mask-mask.mp4` (**존재하지 않음**) → `isUsableDeidFile` false → **폴백 `scanSingleUsable` 로만 회수**. DB 실적재 경로가 이를 증명한다.
  - 따라서 로컬 파이프라인이 아무리 돌아도 **`toMaskName` 정상 경로는 한 번도 실행되지 않으며**, 폴백을 제거하거나 export 디렉터리에 파일이 2개가 되는 순간 완료 회수가 깨진다(그때만 발현).
  - 단위 테스트(`KpstDeidentServiceTest:896` 경로형_fileName…, `:314` toMaskName_…)는 실계약을 커버하므로 코드 자체는 정상.
- **재현/확인 경로**:
  ```bash
  grep -n "fileName" mock-server/app/routers/deid.py                 # :86 "fileName": ds.name
  grep -n "MASK_SUFFIX" mock-server/app/services/deid_sim.py         # "_mask"
  grep -n "MASK_SUFFIX" backend/src/main/java/kr/co/cudo/authoring/batch/service/KpstDeidentService.java  # "-mask"
  docker exec klid-backend ls /app/storage/deidentified/videos/23/   # clip-9101_202607250139_mask.mp4
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select de_idntf_file_path_nm from ls_deident_proc_log where proc_log_sn=23;"
  ```
- **영향**: 회귀 감시 공백. "목업 경유 실동작 PASS" 가 실서버 정상 동작을 보증하지 못하는 구간이 생긴다(거짓 PASS 위험).
- **수정 방향(제안)**: mock `retrieve_progress` 의 `fileName` 을 **원본 입력 절대경로**(`input_path + 원본 basename`)로 바꾸고 산출물 파일명을 `{stem}-mask{ext}` 로 통일해 실계약을 재현. 타임스탬프 부착이 mock 고유 요구라면 별도 토글(`KPST_MOCK_FILENAME_CONTRACT=real|legacy`)로 두 계약을 모두 돌릴 수 있게 한다. 대안으로 폴백 회수 시 WARN 로그를 남겨 "1차 경로 miss" 를 운영에서 관측 가능하게 한다.

### [B-ISSUE-85] TC-BATCH-159/160 (참고) — local 프로파일에서 재시도 폴러가 미등록이라 적재된 재시도 항목이 발화되지 않음
- **심각도**: LOW (환경 구성 — dev/stg/prd 무영향)
- **기대 동작(기대효과)**: 검증 환경에서 재시도 큐의 등록→발화 전 사이클을 관측할 수 있다.
- **현재 동작(이슈 내용)**: `BatchRetryTriggerConfig.java:19` 가 `authoring.batch.enabled` 로 게이팅되는데 `application-local.yml:50` 이 `false`. 반면 배치 본체는 `MarkingBatchBridge → AsyncBatchRunner`(@Async) 로 구동되어 **실패 시 재시도 적재는 계속 일어난다**. 실측: `ls_bat_rty_wtng` 에 rawSn 15/16/23/24 4행이 `PENDING`, `rty_prnmnt_dt` 가 10:41~10:44 로 이미 도래했으나 검증 시각(10:49+)까지 발화 0건. 즉 로컬에서는 재시도 항목이 **적재만 되고 영구 대기**한다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select raw_sn,rty_nmtm,stts_cd,rty_prnmnt_dt from ls_bat_rty_wtng;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select job_name from qrtz_job_details;"  # batchRetryJob 없음
  ```
- **영향**: 검증 커버리지 한정(운영 영향 없음 — `application.yml:184 BATCH_ENABLED:true` 가 dev/stg/prd 기본).
- **수정 방향(제안)**: 재시도 폴러 토글을 배치 파이프라인 토글과 분리(`authoring.batch.retry.enabled`)하거나, 검증 회차에서 `BATCH_ENABLED=true` 로 임시 기동해 폴러 발화 구간을 실증한다.

---

## UNCERTAINTIES 확정 결과

### #8 Quartz 클러스터링 실제 활성 — **확정: 코드 지원 O · 기본값 false · 런타임 비활성**
- 설정 지점 존재: `backend/src/main/resources/application.yml:72` `org.quartz.jobStore.isClustered: ${QUARTZ_CLUSTERED:false}` (+ `:73 clusterCheckinInterval`). 즉 **"application.yml 에 isClustered 가 있는가" → 있다. 단 기본값은 false.**
- 프로파일 override: `application-{local,dev,stg,prd}.yml` 4개 파일 모두 `QUARTZ_CLUSTERED`/`quartz` 키 **0건**(grep 확인).
- 배포 산출물: `deploy/onprem/config/backend/env.template:175` → `QUARTZ_CLUSTERED=false` (주석 `:170` "이중화로 2노드를 띄울 때만 두 노드 모두 true").
- **런타임 실측(결정적 증거)**: `qrtz_scheduler_state` **0행**(클러스터 모드에서만 인스턴스 체크인 행 생성) · `qrtz_fired_triggers.instance_name = "NON_CLUSTERED"` · `org.quartz.scheduler.instanceId: AUTO`(`:64`) 임에도 AUTO 생성 ID 대신 NON_CLUSTERED 사용 → JobStore 가 비클러스터 모드로 동작 중.
- 부수 확정: `PostgreSQLDelegate`(`:66`) 실사용 ✓ · `useProperties=true`(→ BYTEA JobDataMap 회피) ✓ · `QRTZ_LOCKS` 2행(TRIGGER_ACCESS/STATE_ACCESS) 실존 ✓ · `misfireThreshold=60000`(`:68`), 등록 트리거 `misfire_instr=0`(SMART_POLICY) ✓ · 잡 중첩 방지 `@DisallowConcurrentExecution` 은 `BatchQuartzJob`/`BatchRetryQuartzJob`/`KpstDeidentPollJob`/`ControlTrainingVideoScanJob`/`DatasetExportPendingSweepJob` **5종 전부** 보유, DB `is_nonconcurrent='t'` 확인 ✓
- **판정 귀결**: TC-BATCH-171 = PARTIAL, B-ISSUE-81 발행. CLAUDE.md("클러스터링 적용")와 D5(`:126` "옵션 지원") 서술 충돌도 함께 확인 — **D5 가 코드 정본**.

### #21 KpstDeidentTxService 상태전이·락해제 원자성 — **확정: 트랜잭션 경계 설계는 의도적·정합, 단 폴링 대상 클레임만 공백**
- 모든 공개 메서드가 `@Transactional("controlTransactionManager", propagation=REQUIRES_NEW)` — self-invocation 프록시 미적용 함정을 **별도 빈(cross-bean) 분리로 회피**(클래스 Javadoc `:31-33` 이 명시, `BatchTransitionService` 와 동일 패턴).
- 원자화 범위:
  - `finishDownloadAndComplete:59-70` — `verifyDeidFile` + `markDownloaded` + `applyCompletion`(Y/MARKING_READY/락해제/신고해소/알림 또는 REDEIDENT 프레임 attach)을 **단일 tx** 로 묶음 → 구 2분리(DOWNLOADED→Y) 의 stuck 창 제거 확인.
  - `failRedeidentCompletion:99-109` / `markRawDeidentFailed:116-120` — 메인 tx **롤백 이후** 별도 REQUIRES_NEW 로 커밋되도록 호출부(`KpstDeidentService:340-351, 367-382, 542-555`)에서 분리 → "롤백에 F-마킹이 휩쓸리는 조용한 실패" 회피 확인.
  - 락 해제: 성공 경로(`applyBatchCompletion:201-203`, `applyRedeidentCompletion:249-251`)·후처리 실패 경로(`failRedeidentCompletion:104-106`)·타임아웃 경로(`markTimeoutIfExpired:154-156`) **3경로 모두** `isRawLocked` 가드 후 `releaseRaw` → REDEIDENT 작업락 영구 잔존 차단 확인. `failPolling:76-82` 만 락 해제 없음이나, 비-REDEIDENT 는 위탁 시 락을 잡지 않으므로 정합(주석 `KpstDeidentService:314-315` 와 코드 일치).
  - `markTimeoutIfExpired:136-160` — `POLL_DOWNLOADED` 이면 조기 return(완료건 오-F 방지) · 시도초과 OR 경과초과 OR 판정 · `fail()` 이 terminal 상태로 전이해 `findByPollSttsCdIn([WAITING,POLLING])` 재조회 대상에서 제외 → 무한 폴링 차단 확인.
- **잔여 공백 1건**: 폴링 대상 **선점(claim)** 만 원자성이 없음(`findByPollSttsCdIn` 평문 조회, `finishDownloadAndComplete` 멱등 가드 없음) → B-ISSUE-82. 단일 노드에서는 `@DisallowConcurrentExecution` 으로 무해.
- 테스트 커버리지: `KpstDeidentTxServiceTest` 22 케이스(Y전이/파일무효/타임아웃 REDEIDENT·비REDEIDENT/락해제/APPROVED 유지/회귀) — `_raw/test-baseline.md` 기준 전건 GREEN.

---

## 요약

- 총 **37건** / PASS **33** / FAIL **0** / PARTIAL **4** / BLOCKED **0** / N/A **0** / 확인필요 **0**
  - PARTIAL: TC-BATCH-159(재시도 RETRYING 고아) · TC-DEID-072(1차 회수 경로 E2E 미검증) · TC-DEID-077(2노드 중복 폴링) · TC-BATCH-171(클러스터링 비활성)
- **근거 라인 드리프트: 1건** — TC-BATCH-171 근거 `QuartzConfig.java:31-37` 은 클러스터링과 무관(DataSource 주입부). 실제 근거는 `application.yml:69-73`. (TC-DEID-066 `88-91`→실제 `87-91` 은 1행 오차로 비드리프트 처리)
- **self-fill 결함: 0건** — KPST prjId·datasetId·fileName·procState 전부 mock 응답 유래(mock 로그 `POST /project` 4건 / `GET /retrieve_progress` 4건 ↔ backend `submitted`/`poll completed` 로그·DB 적재값 1:1 대응). 비식별 산출물도 mock 이 생성한 실파일(18바이트) 기준으로 `isUsableDeidFile` 통과.
- **회귀 확인**: 과거 "KPST 완료를 `'F'` 로 오종결" 결함(PR#27) **재발 없음** — rawSn 23/24/25/26 4건 모두 `poll_stts_cd=DOWNLOADED, proc_stts_cd=SUCCEEDED, de_ident_yn='Y'`. 다만 회수는 폴백 경로로 이뤄져 1차 경로 감시망은 공백(B-ISSUE-84).
- **신규 이슈 5건**: B-ISSUE-81(HIGH) · 82(HIGH) · 83(MEDIUM) · 84(MEDIUM) · 85(LOW)
- **테스트 커버리지 공백**: `AsyncBatchRunner` 전용 단위테스트 부재(TC-BATCH-172) · `QuartzConfig` 클러스터링 설정 검증 테스트 부재(TC-BATCH-171) · `deleteIdleByRawSn`(RETRYING 보존) DB 레벨 IT 부재(TC-BATCH-161 은 mock verify 만)

