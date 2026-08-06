# B 클러스터 part1 (B-1~B-3) 2차 검증 결과

> 대상: `docs/test-cases/B-batch-deidentify.md` 의 B-1(25) · B-2(8) · B-3(16) = **49건**
> 검증 일시: 2026-07-31 03:08~03:20 KST · 환경: backend `localhost:18081`(HEAD `ca3c712b` 재빌드 이미지, `SPRING_PROFILES_ACTIVE=local`) · mock-server `:9400` · postgres `:5432`(스키마 `public`)
> 취소선(`~~폐기~~`) 행: 본 3개 섹션에는 **0건**(`grep -c '~~'` = 0) — 집계 제외분 없음.
> 컨테이너 재시작·재빌드·빌드/테스트 실행 **0회**. 소스/설정/테스트 파일 수정 **0건**(본 문서 1개만 신규 작성).
> DB 는 실동작 검증을 위해 **관제 소유 픽스처(`MNG_CLIP_MASTER`/`MNG_CLIP_EVNT_LST`)만 임시 삽입 후 전량 삭제**했다(§ "수동 개입" 참조). `LS_*` 상태를 `UPDATE` 로 위조한 구간은 없다.

## 집계

| 판정 | B-1 | B-2 | B-3 | 합계 |
|---|--:|--:|--:|--:|
| PASS | 23 | 7 | 15 | **45** |
| FAIL | 1 | 0 | 0 | **1** |
| PARTIAL | 1 | 1 | 1 | **3** |
| BLOCKED | 0 | 0 | 0 | 0 |
| N/A | 0 | 0 | 0 | 0 |
| 확인필요 | 0 | 0 | 0 | 0 |
| **계** | **25** | **8** | **16** | **49** |

- 근거 유형: **[실동작] 21건** / **[정적] 28건**(다수는 실동작 근거를 병행 보유).
- 신규 이슈 **5건**(FAIL 1 · PARTIAL 3 · 부수 발견 1). 1차 이월 미해소 **2건**.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| B-ISSUE-01(1차) | KPST 위탁 경로에 원본 실재 검증 부재 → 원본 없는 영상이 'Y' 승인 | **해소** | `KpstDeidentService.java:286` 이 `verifySourceOrFail` 호출, `:446-464` 이 원본 부재 시 `recordDeidentFailure(rawSn, "KPST_SOURCE_MISSING", "source not found")`(REQUIRES_NEW) 후 거부. [실동작] rawSn 127/128/139~142 전부 `DE_IDENT_YN='F'` + `LS_DEIDENT_PROC_LOG.PROC_STTS_CD='FAILED'`, `MARKING_READY` 미전이, mock-server 인바운드 **0건**(외부 미접촉). ※산출물 무결성(`isUsableDeidFile`) 축은 B-13 소관 — 본 파트 밖 |
| B-ISSUE-02(1차) | Quartz 클러스터링 기본 false | **해소(본 파트 밖)** | UNCERTAINTIES #8 확정 — stg/prd 기본 `true` + `QuartzClusteringGuard` fail-closed. 판정 대상은 B-14. 본 파트는 TC-BATCH-016 의 caveat 문구가 이미 이를 반영 |
| B-ISSUE-03(1차) | 배치 상태 전이 상태머신 미검증 | **대상 밖** | B-4(`BatchOrchestrator`) 소관 |
| B-ISSUE-04(1차) | 스캔 매 tick 전량조회 + 미적재 필터 없음 + 클립당 2 쿼리 | **해소** | `MngClipMasterRepository.java:48-57`(NOT EXISTS + Pageable + PK 정렬) · `TrainingVideoIngestService.java:62,112-128`(limit 100, IN 조회 1회). [실동작] 전량 적재 상태에서 scan → 후보 **0건**(1차의 `scanned=3 ingested=0` 무한반복 소멸) |
| B-ISSUE-05(1차) | mock 복사 IOException 분기 전용 테스트 부재 | **미해소** | `grep -n IOException src/test/.../DeidentifyStep*.java` → 주입 케이스 여전히 0건 → B-ISSUE-05(2차) |
| B-ISSUE-06(1차) | `loadRaw` 자기호출로 `@Transactional` 미적용 | **미해소** | `AsyncDeidentifyRunner.java:79` 이 `:112` 를 `this` 로 직접 호출(프록시 우회). `BatchOrchestrator.java:104→:149` 동일 → B-ISSUE-04(2차) |

## B-1 결과표 (관제 학습용 적재)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-001 | 스캔: 적재 후보 0건 | PASS | [실동작] `POST /v1/dev/batch/scan` → `data=0`, DEBUG `no training-designated clips to scan`(03:11:35). `scan finished` 로그 미출력 = `loadEventListsFor`/`ingestOne` 미도달. [정적] `TrainingVideoIngestService.java:80-83` | 테스트 `TrainingVideoIngestServiceTest#nullScanResultIsHandledSafely` |
| TC-BATCH-002 | 스캔: 신규 클립 N건 적재 카운트 | PASS | [실동작] 픽스처 4건 투입 → `data=4`, `scan finished scanned=4 ingested=4`, 클립별 `ingested clipId=VB4-CLIP-0N rawSn=139~142` 4줄. [정적] `:85-98` | 테스트 `delegatesIngestForTrainingDesignatedClip` / `mixedResultsCountedCorrectly` |
| TC-BATCH-003 | 스캔: 1건 적재 실패가 다른 클립 막지 않음 | PASS | [실동작] 적재 실패 클립 → `ERROR [TrainingIngest] ingest failed evntId=VB3-EVT-01 clipId=VB3-CLIP-01 causeType=UnexpectedRollbackException` 후 루프 계속해 `scan finished` 도달. 로그에 파일경로·PII 미출력 확인. [정적] `:92-97` | 테스트 `partialFailureDoesNotBlockOtherClips` |
| TC-BATCH-004 | 적재: clipId blank → skip | PASS | [실동작] `CLIP_ID=NULL` 픽스처 → `WARN skip clip with blank clipId evntId=VB1-EVT-01`, `LS_DATA_RAW` 신규 0행. [정적] `TrainingVideoIngestTx.java:86-89` | 테스트 `skipsClipWithNullClipId` |
| TC-BATCH-005 | 적재: vmsCctvId blank → skip | PASS | [실동작] `VMS_CCTV_ID=NULL` 픽스처 → `WARN skip clip with blank vmsCctvId evntId=VB1-EVT-02 clipId=VB1-CLIP-02`, 미적재. [정적] `:92-96` | 테스트 `skipsClipWithNullVmsCctvId` / `skipsClipWithBlankVmsCctvId` |
| TC-BATCH-006 | 적재: filePath blank → skip | PASS | [정적] `:98-102`. [실동작] `FILE_PATH='   '`·`NULL` 픽스처 2건은 **후보 쿼리 단계에서 이미 배제**(`scanned=2`, 4건 아님) → Tx 가드는 도달 불가한 방어심도 | 테스트 `skipsClipWithBlankFilePath` / `skipsClipWithNullFilePath` |
| TC-BATCH-007 | 적재: 멱등 1차 — 이미 적재 clipId skip | PASS | [정적] `:104-107`. NOT EXISTS 선필터로 통상 도달 불가, 2노드 조회~적재 race 시 1차 방어로 유효 | 테스트 `skipsAlreadyIngestedClipByClipId` |
| TC-BATCH-008 | 적재: 멱등 2차 — 동시 race UK 위반 흡수 | FAIL | [실동작] `DataIntegrityViolationException` 발생 시 catch 블록은 타지만(`DEBUG duplicate ingest race — skip`) **REQUIRES_NEW 커밋에서 `UnexpectedRollbackException` 이 호출자로 전파**됨 → 기대결과 "예외 미전파" 불성립 | **B-ISSUE-01**. 부수: 비-중복 제약위반도 "duplicate" 로 오분류 |
| TC-BATCH-009 | 적재: 정상 → PENDING + 이벤트 발행 | PASS | [실동작] 139~142 `DATA_STTS_CD=PENDING`, `DE_IDENT_YN=N`(적재 직후) + `IngestDeidentifyBridge video ingested rawSn=139~142` 4줄. [정적] `:116-124` | 테스트 `publishesEventOnIngest` |
| TC-BATCH-010 | 적재 매핑: evntTypeCd 는 스캔 주입 evntLst 에서 | PASS | [실동작] rawSn 142: `evnt_type_cd='AAA_FIRST'`, `sht_dt=2021-05-05 05:05:05`(evntLst.SHT_DT) ≠ `crt_dt=2020-01-04`. 실데이터 126/133 도 `INTRUSION`/`LOITERING`(evntLst 유래). [정적] `:109-112` | 클립별 개별 조회 없음(TC-BATCH-023 참조) |
| TC-BATCH-011 | 적재 매핑: evntLst=null 폴백 | PASS | [실동작] evntLst 미보유 클립 139/140/141 → `evnt_type_cd=NULL`, `sht_dt` = 각 클립 `CRT_DT`(2020-01-01/02/03) 그대로. [정적] `:109-112` | 테스트 `fallsBackWhenEvntLstNotMatched` |
| TC-BATCH-012 | 적재 매핑: prvcTypeCd=ANONY 고정 | PASS | [실동작] 적재 전 행 `prvc_type_cd=ANONY`(126~133, 139~142). [정적] `:67,118` | 전체 비식별 정책 정합 |
| TC-BATCH-013 | durationSec 변환: ms→초 반올림 | PASS | [실동작] 30500→**31**, 500→**1**, 30000→30, 113000→113. [정적] `:144-150` | 테스트 `convertsVdoLenMillisToSeconds` / `roundsHalfSecondUpToOne` |
| TC-BATCH-014 | durationSec 변환: null → null | PASS | [실동작] `VDO_LEN_SEC=NULL` → rawSn 139 `vdo_len_sec` NULL. [정적] `:145-147` | 테스트 `keepsDurationNullWhenVdoLenNull` |
| TC-BATCH-015 | durationSec 변환: 1초 미만 → null | PASS | [실동작] 400ms → rawSn 140 `vdo_len_sec` NULL(0 아님). [정적] `:148-149` | 테스트 `keepsDurationNullForSubSecondThatRoundsToZero` |
| TC-BATCH-016 | 스캔 잡: 동일 인스턴스 내 동시 tick 차단 | PASS | [정적] `ControlTrainingVideoScanJob.java:21-22` `@DisallowConcurrentExecution` | 런타임 미관측(local 프로파일에서 잡 미등록). 노드 간 중복은 클러스터링 축(B-14) |
| TC-BATCH-017 | 스캔 잡: 예기치 못한 실패 안전망 | PASS | [정적] `:40-44` `catch(RuntimeException)` → ERROR(`causeType` = 예외 클래스명만), 미전파 | 테스트 `scanFailureIsAbsorbed` |
| TC-BATCH-018 | 스캔 트리거: 60초 간격 등록 | PASS | [정적] `ControlTrainingVideoScanTriggerConfig.java:37-48` — `startAt(now+30_000L)` + `withIntervalInSeconds(intervalSec)`(`:25-26` 기본 60) + `repeatForever` | local 프로파일 비활성이라 런타임 미관측 |
| TC-BATCH-019 | 스캔 트리거: enabled=false 미등록 | PASS | [실동작] `application-local.yml:64-66` `training-scan.enabled=false` → `qrtz_job_details` 에 `controlTrainingVideoScanJob` **부재**(등록 잡은 `kpstDeidentPollJob`/`datasetExportPendingSweepJob`/`datasetExportFailureRecoveryJob`/`bootstrapJob` 4종). [정적] `:21-22` `@ConditionalOnProperty` | |
| TC-BATCH-020 | 후보 쿼리: 적재된 클립 NOT EXISTS 제외 | PASS | [실동작] `MNG_CLIP_MASTER` 5건 전량 `JOB_DMND_YN='Y'`·실경로 보유이나 전부 적재 완료 상태 → scan 결과 **후보 0건**. [정적] `MngClipMasterRepository.java:48-57` | 1차 B-ISSUE-04 핵심 해소 |
| TC-BATCH-021 | 후보 쿼리: tick 상한 100건 + PK 오름차순 고정 | PARTIAL | [실동작] 후보 103건 투입 → `scanned=100`(상한 준수), 마지막 처리 클립 `VB2-EVT-198` = PK 오름차순 100번째(결정적 정렬 확인). **그러나 "잔여분은 다음 tick 이 이어서 처리"는 가드-스킵/적재실패 후보에서 불성립** — `LS_DATA_RAW` 행이 안 생겨 NOT EXISTS 로 빠지지 않고 tick 예산을 영구 점유 | **B-ISSUE-02** |
| TC-BATCH-022 | 후보 쿼리: 상한 도달 시 이월 로그 | PASS | [실동작] `scan finished scanned=100 ingested=0 limit=100 carriedOver=true`(상한 도달) / `... scanned=2 ... carriedOver=false`(미도달) 양쪽 확인. [정적] `:100-102` | |
| TC-BATCH-023 | 이벤트리스트: IN 조회 1회로 배치화 | PASS | [정적] `TrainingVideoIngestService.java:112-128` 이 `findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc`(`MngClipEvntLstRepository.java:42`) 1회만 호출, `TrainingVideoIngestTx` 는 결과를 파라미터로만 수신(리포지토리 의존 없음) | 테스트 `eventListIsLoadedWithSingleInQuery`(IN 1회 + `findFirstByEvntId` 0회) · `MngClipIngestCandidateIT`. 런타임 쿼리 계수는 `pg_stat_statements` 미설치로 불가 |
| TC-BATCH-024 | 이벤트리스트: 복합 PK 다행 시 첫 행 채택 | PASS | [실동작] `VB4-EVT-04` 에 `AAA_FIRST`/`ZZZ_LAST` 2행 투입 → 적재 rawSn 142 가 `AAA_FIRST` + 그 행의 `SHT_DT` 채택. [정적] `:124-127` `putIfAbsent` | 테스트 `duplicateEventListRowsCollapseToFirst` |
| TC-BATCH-025 | 이벤트리스트: evntId 전부 blank 면 조회 0회 | PASS | [정적] `:113-121` — `evntIds.isEmpty()` 시 `Map.of()` early return, 리포지토리 미호출 | 전용 테스트 없음(커버리지 갭, 기능 결함 아님) |

## B-2 결과표 (선두 비식별 브릿지 + 러너)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-DEID-001 | 브릿지: AFTER_COMMIT에서만 발화 | PASS | [실동작] `02:48:48.594 [http-nio-8080-exec-7] IngestDeidentifyBridge video ingested rawSn=126` → 즉시 `[batch-async-2] AsyncDeidentifyRunner starting deidentify rawSn=126`. 126/127/128 의 브릿지 로그가 `.594/.598/.601` 로 **클립별로 끼어 발화** = 각 `ingestOne`(REQUIRES_NEW) 커밋 시점 발화이지 스캔 전체 종료 시점이 아님. [정적] `IngestDeidentifyBridge.java:26-30` | 리스너 동기 + 실행은 `@Async` 별도 스레드 = 설계대로 |
| TC-DEID-002 | 브릿지: 적재 롤백 시 비식별 미트리거 | PASS | [정적] `:26` — `@TransactionalEventListener(phase = AFTER_COMMIT)` 에 `fallbackExecution` 미지정(기본 false) | 테스트 `IngestDeidentifyBridgeTest` |
| TC-DEID-003 | 러너: raw 미존재 skip | PASS | [정적] `AsyncDeidentifyRunner.java:80-83` WARN 후 return(파이프라인 미실행) | 테스트 `AsyncDeidentifyRunnerTest#raw_없으면_무처리` |
| TC-DEID-004 | 러너: 동기 완료(mock) → MARKING_READY 전이 | PASS | [정적] `:92-94` `ctx.isDeidentCompleted()` 시 `markRawDataMarkingReady` | 실동작 미관측(`DEIDENTIFY_MOCK_MODE=false`). 테스트 `mock_모드_비식별_동기완료시_run직후_MARKING_READY로_전이` + `DeidentifyStepExecutePersistenceIntegrationTest`(실 DB 커밋 검증) |
| TC-DEID-005 | 러너: KPST 지연(deferred) → 미전이 | PASS | [실동작] `02:48:48.641 deidentify submitted (deferred) rawSn=126 — MARKING_READY 는 폴링 완료 시 전이` → 전이는 `02:49:32.794 [KpstDeid] completed rawSn=126` 이후에만 발생. 132/133 동일. [정적] `:95-98` | 조기 전이 없음 확인 |
| TC-DEID-006 | 러너: 실패 시 예외 삼킴 + 재시도 큐 미사용 | PASS | [실동작] `WARN deidentify failed rawSn=127 cause=CustomException`(예외 클래스명만, 경로·PII 없음) ×6건(127/128/139~142), `MARKING_READY` 미전이, 그럼에도 `DE_IDENT_YN='F'` 는 별도 커밋으로 영속. [정적] `:99-106` + 생성자(`:67-74`)에 `BatchRetryQueue` 의존 **0** | 테스트 `비식별_실패시_MARKING_READY_미전이_그리고_재시도큐_미사용` |
| TC-DEID-007 | 러너: rawSn null → loadRaw empty | PARTIAL | [정적] `:112-115` 기능(null→`Optional.empty`→skip)은 충족. 그러나 `:79` 가 `loadRaw(rawSn)` 를 **자기호출**해 선언된 `@Transactional(REQUIRES_NEW, readOnly)`(`:110-111`)가 적용되지 않음 | **B-ISSUE-04**(1차 B-ISSUE-06 미해소). 전용 테스트도 없음 |
| TC-DEID-008 | 러너: preMarkingPipeline 은 DEIDENTIFY 1스텝 | PASS | [정적] `BatchPipelineConfig.java:48-52` `new BatchPipeline(List.of(deid))` | 테스트 `BatchPipelineConfigTest#선두_비식별_파이프라인은_DEIDENTIFY_단계만` |

## B-3 결과표 (DeidentifyStep)

> 본 환경은 `DEIDENTIFY_MOCK_MODE=false` / `KPST_DEID_ENABLED=true` 이므로 mock 분기(TC-DEID-010~015, 018~022)는 **실동작 재현 불가**하다. 토글 변경은 컨테이너 재기동을 수반해 금지 지시에 위배되므로 정적 + 기존 테스트 자산 대조로 판정했다. 이는 "환경 안 띄워서 못 봄"이 아니라 판정 근거가 존재하는 상태이므로 BLOCKED 로 올리지 않았다.

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-DEID-010 | mock-mode 부트 게이트: prd 차단 | PASS | [정적] `DeidentifyStep.java:95` `ALLOWED_MOCK_PROFILES={local,dev,stg}`, `:182-190` `@PostConstruct`→`assertMockAllowedProfile`, `:216-221` allowlist 미수렴 시 `IllegalStateException` | 테스트 `prd_프로파일에서_mock활성시_부팅거부` / `prd와_dev가_섞인_active프로파일이면_거부` |
| TC-DEID-011 | mock-mode 부트 게이트: 프로파일 없음 거부 | PASS | [정적] `:210` `noActiveProfile` → `:216` 거부(fail-closed) | 테스트 `active프로파일_미설정이면_거부` |
| TC-DEID-012 | mock-mode 부트 게이트: dev/stg 허용 + 비-local WARN | PASS | [정적] `:223-227` `localActive` 아니면 WARN 1줄 | 테스트 `dev/stg_프로파일에서_mock활성시_부팅허용` · `dev_프로파일에서_mock활성_부팅시_비식별경고_WARN로그를_1줄_남긴다` |
| TC-DEID-013 | mock-mode 부트 게이트: ENV=prod 거부 | PASS | [정적] `:207-208` trim → `:213-214` `toLowerCase(Locale.ROOT)` 후 allowlist 대조 | 테스트 `ENV가_prd면_거부` / `ENV가_PRD_대문자여도_거부` / `ENV가_공백포함_prd여도_거부` / `비표준_환경라벨(production)이면_거부` |
| TC-DEID-014 | run: raw null → INVALID_INPUT | PASS | [정적] `:270-272` | 테스트 `raw_null이면_INVALID_INPUT` |
| TC-DEID-015 | run: mock 경로 → completed | PASS | [정적] `:275-277` — mock 분기가 KPST 분기(`:280`)보다 앞이라 외부 미접촉 | 테스트 `mock모드_원본존재시_외부호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다` |
| TC-DEID-016 | run: KPST 위탁 → deferred | PASS | [실동작] `:280-283` 경로로 126/132/133 이 `[KpstDeid] submitted rawSn=… prjId=2/3/4` + mock-server `POST /project` 200 실왕복. 원본 부재(127/128/139~142)는 `submit` 내부 `verifySourceOrFail`(`KpstDeidentService.java:286,446-464`)이 `recordDeidentFailure`+거부 → **mock 인바운드 0건**(제출 전 차단) | 1차 B-ISSUE-01 해소 실증 |
| TC-DEID-017 | run: mock아님 + KPST 미주입 → 설정오류 | PASS | [정적] `:284-288` `INTERNAL_ERROR` + 고정 메시지(내부 경로 미노출), 레거시 폴백 없음 | 테스트 `KPST_disabled_이고_mock도_아니면…INTERNAL_ERROR` · `kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외` |
| TC-DEID-018 | runMock: 원본 부재 → 'F' 별도 커밋 | PASS | [정적] `:309-314` `batchTransitionService.recordDeidentFailure`(별도 빈 REQUIRES_NEW) + `EXTERNAL_API_ERROR`. [실동작·동등 메커니즘] KPST 경로에서 동일 `recordDeidentFailure` 가 `run()` 롤백과 독립 커밋됨을 실증(127/128 `'F'`+`FAILED` 영속, `MARKING_READY` 미전이) | 테스트 `mock모드_원본부재시_성공위장없이_recordDeidentFailure_+_MARKING_READY_미전이` · `DeidentifyStepFailurePersistenceIntegrationTest`(실 DB) |
| TC-DEID-019 | runMock: 복사 IOException → 'F' | PARTIAL | [정적] `:330-335` 코드는 정확(`recordDeidentFailure` + `INTERNAL_ERROR`). **회귀 테스트 부재** — `DeidentifyStep*` 테스트 어디에도 copy IOException 주입 케이스 없음 | **B-ISSUE-05**(1차 B-ISSUE-05 미해소) |
| TC-DEID-020 | runMock: 성공 → 'Y'+procLog+부수효과 | PASS | [정적] `:345-365` — `markDeidentified("Y")` · `procLog.succeed(target)` · `releaseRaw` · `resolveOpenReports` · `notifyReviewersOnLockRelease` (+ `streamMetaCacheEvictor.evictAfterCommit` 추가 배선) | 테스트 3건(성공 경로 / 잠금영상 부수효과 / 캐시 무효화) |
| TC-DEID-021 | runMock: atomic move 멱등 | PASS | [정적] `:385-398` tmp 복사(`REPLACE_EXISTING`) → `:401-407` `ATOMIC_MOVE`(+`REPLACE_EXISTING`), `AtomicMoveNotSupportedException` 시 replace 폴백, `finally { deleteIfExists(tmp) }` | 테스트 `mock모드_재실행시_atomic_move로_멱등하게_덮어쓰고_손상되지_않는다` |
| TC-DEID-022 | 출력 경로 순회 방어(CWE-22) | PASS | [정적] `:418-421` → `VideoArtifactRootResolver.resolveUnder(:424-446)` 세그먼트 위반 `INVALID_INPUT` · base 이탈/심링크 `FORBIDDEN`, 추가로 `:388-389` 쓰기 직전 `verifyRealPathUnder`(TOCTOU 재검증). 기대결과의 "INVALID_INPUT"보다 강화된 상태 | 테스트 `S1_허용마운트루트_밖…F로_마감` / `S2_상위참조…차단`. 에러코드 차이는 근거 드리프트로만 기록 |
| TC-DEID-023 | execute: self 프록시로 REQUIRES_NEW 획득 | PASS | [정적] `:239-250` — `execute` 에 `@Transactional` **없음**, `selfProvider.getObject().run(ctx.getRaw())`(provider null 시 `this` 폴백) | 테스트 `BatchStepTransactionBoundaryTest#selfProxyStepsMustNotAnnotateExecute` |
| TC-DEID-024 | execute: completed를 ctx 브릿지 | PASS | [정적] `:249` `ctx.markDeidentCompleted(result.completed())` | [실동작] KPST 경로에서 `deferred` → 러너가 전이를 건너뛴 것으로 간접 확인 |
| TC-DEID-025 | DeidentifyStep 은 경계 가드의 명시 면제 대상 | PASS | [정적] `BatchStepTransactionBoundaryTest.java:53` `BOUNDARY_EXEMPT = Set.of(DeidentifyStep.class, MarkingLoadStep.class)` + `:42-51` 면제 사유 javadoc 명문화, `:88` 스킵 로직 | 가드는 최소 6개 스텝 스캔을 선단언해 공허 통과 방지 |

## 근거 드리프트

카탈로그 기준 커밋(`tc-update` / `11c3e1b8`)과 현재 `main` HEAD(`ca3c712b`) 사이의 라인 이동. **B-1 은 드리프트 0건**, B-2·B-3 에서 총 **20건**(전부 동일 파일 내 일괄 오프셋, 코드 의미 변경 없음).

| 파일 | 케이스 | 카탈로그 표기 | 실제(HEAD) | 오프셋 |
|---|---|---|---|:--:|
| `AsyncDeidentifyRunner.java` | TC-DEID-003 | 73-77 | **80-83** | +6 |
| | TC-DEID-004 | 86-89 | **92-94** | +5~6 |
| | TC-DEID-005 | 90-92 | **95-98** | +5~6 |
| | TC-DEID-006 | 94-100 | **99-106** | +5~6 |
| | TC-DEID-007 | 105-110 | **110-117** | +5~7 |
| `DeidentifyStep.java` | TC-DEID-010 | 87,178-215 | **95, 182-190, 205-228** | +8~13 |
| | TC-DEID-011 | 202,208 | **210, 216-221** | +8~13 |
| | TC-DEID-012 | 216-218 | **223-227** | +7~9 |
| | TC-DEID-013 | 205-208 | **207-208, 213-214** | +2~6 |
| | TC-DEID-014 | 259-262 | **270-272** | +10~11 |
| | TC-DEID-015 | 265-267 | **275-277** | +10 |
| | TC-DEID-016 | 269-273 | **280-283** | +10~11 |
| | TC-DEID-017 | 274-278 | **284-288** | +10 |
| | TC-DEID-018 | 296-306 | **309-314** | +8~13 |
| | TC-DEID-019 | 319-325 | **330-335** | +10~11 |
| | TC-DEID-020 | 319-350 | **345-365** | +15~26 |
| | TC-DEID-021 | 375-400 | **385-407** | +7~10 |
| | TC-DEID-022 | 408-420 | **418-421** | +1~10 |
| | TC-DEID-023 | 232-241 | **239-250** | +7~9 |
| | TC-DEID-024 | 241 | **249** | +8 |

기대결과 문구 드리프트(라인 아님) 2건 — 결함 아님, 카탈로그 갱신 대상:
- **TC-DEID-022**: 기대결과가 `target 이 {base}/videos/{rawSn:Long}/… 로만 구성` 이라 기술하나, 현재 기본 전략은 co-locate(`dirname(원본)/{rawSn}/deid/`)다. 또 base 이탈 시 실제 에러코드는 `INVALID_INPUT` 이 아니라 `FORBIDDEN`(세그먼트 위반만 `INVALID_INPUT`).
- **TC-DEID-020**: 부수효과 목록에 `streamMetaCacheEvictor.evictAfterCommit`(`:363`)이 누락돼 있다.

## self-fill 점검

**본 파트 범위(적재·선두 비식별)에서 self-fill 확증 사례 0건.**

| 항목 | 판정 | 근거 |
|---|:--:|---|
| 비식별 위탁이 mock-server 실경유인가 | 실경유 | `DEIDENTIFY_MOCK_MODE=false` + `KPST_DEID_ENABLED=true` + `KPST_DEID_BASE_URL=http://klid-mock-server:9400`. 126/132/133 이 `POST /project`(prj_id=2/3/4) → `GET /retrieve_progress` 폴링 왕복, 호출자 IP `172.18.0.5`(backend 컨테이너) |
| 비식별 산출 경로를 문자열로 조합했는가 | 조합 아님 | `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` = `…/126/deid/sample-cctv-1080p-**mask**.mp4`. mock 상수 `deidentified.mp4`(`DeidentifyStep.java:93`, mock 경로 전용)와 다른 `{stem}-mask{ext}` 형식 = KPST 응답값을 읽어 기록한 증거 |
| 외부 응답 없이 성공을 자체 채웠는가 | 아니오 | 원본 부재 6건(127/128/139~142)에서 **외부 호출 자체가 발생하지 않았고**(해당 시각 mock 인바운드 0건) `'F'`+`FAILED` 로 마감. 위장 `'Y'` 0건 |
| 내부 목모드 우회 플래그 | 없음(1건 dead) | backend env `AI_MOCK_MODE=true` 는 backend 코드에서 참조 0건(기존 기록과 동일). 비식별·적재 경로에 내부 목 분기 없음 |
| 적재 메타를 상수로 채웠는가 | 부분 상수(설계) | `prvcTypeCd=ANONY` 는 "전체 비식별" 정책상 의도된 고정값(`TrainingVideoIngestTx.java:67`)이며 CLAUDE.md 정합. 그 외 `evntTypeCd`/`shtDt`/`durationSec`/`lclgvCd` 는 전부 관제 `MNG_*` 실값 유래임을 실측 대조로 확인 |

## 수동 개입 (재현·감사용)

전부 **관제 소유 픽스처 테이블**(`MNG_CLIP_MASTER` / `MNG_CLIP_EVNT_LST`)의 임시 INSERT 이며, 검증 종료 후 **전량 DELETE 완료**(`mng_clip_master` 5행 → 실험 → 5행 복귀, 이후 타 에이전트가 `DEV-CLIP-920x` 5건 추가해 현재 10행). 부작용으로 생성된 `LS_DATA_RAW` 139~142 및 그 `LS_DEIDENT_PROC_LOG` 도 삭제해 `ls_data_raw` 총계 48행으로 원복. `LS_*` 상태 컬럼을 `UPDATE` 로 위조한 구간 없음.

| # | 픽스처 | 목적 | 정리 |
|---|---|---|---|
| 1 | `VB1-EVT-01~05`(clipId NULL / cctvId NULL / filePath 공백 / filePath NULL / jobDmndYn='N') | TC-BATCH-004·005·006 및 후보 필터 | DELETE 완료 |
| 2 | `VB2-EVT-101~201`(101건, cctvId NULL) | TC-BATCH-021·022 상한·정렬·이월 | DELETE 완료 |
| 3 | `VB3-EVT-01`(file_path 586자) | TC-BATCH-008 제약위반 흡수 반증 | DELETE 완료 |
| 4 | `VB4-EVT-01~04`(+ evntLst 2행) | TC-BATCH-009~015·024 매핑 | DELETE 완료(raw 139~142 포함) |

## 이슈 상세

### [B-ISSUE-01] TC-BATCH-008 — UK 위반 catch-skip 이 실제로는 예외를 삼키지 못한다(REQUIRES_NEW 커밋에서 `UnexpectedRollbackException` 전파) + 비-중복 제약위반을 "duplicate ingest race" 로 오분류

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `TrainingVideoIngestTx.ingestOne` 의 이중 멱등 2차 방어는 "동시 race 로 UK(VMS_CLIP_ID)가 충돌하면 **정상 skip**(false 반환)으로 흡수하고 예외를 호출자로 전파하지 않는다"이다(케이스 기대결과 = `catch-skip false, 예외 미전파`, 코드 주석 `:126` "동시 race 의 중복 적재 — 정상 skip 처리"). 스캔 로그도 그 상황을 `DEBUG` 수준의 정상 흐름으로 남기도록 설계돼 있다.
- **현재 동작(이슈 내용)**:
  - `TrainingVideoIngestTx.java:82` 의 `@Transactional(propagation = REQUIRES_NEW)` 안에서 `:120` `videoRepository.save(raw)`(IDENTITY 채번 → 즉시 INSERT)가 제약을 위반하면 PostgreSQL 이 **트랜잭션 전체를 abort** 하고 Hibernate 가 세션을 rollback-only 로 마킹한다. `:125-129` 의 catch 는 실행되지만, 이후 REQUIRES_NEW 경계를 **커밋할 때** Spring 이 `UnexpectedRollbackException` 을 던진다.
    ```java
    } catch (DataIntegrityViolationException e) {          // :125
        log.debug("[TrainingIngest] duplicate ingest race — skip clipId={}", vmsClipId);
        return false;                                       // :128 ← 여기까지는 도달하지만
    }                                                       //        커밋에서 예외가 다시 난다
    ```
  - **실동작 증거**(`file_path` 586자 클립 1건 투입 → `raw_file_path_nm varchar(500)` 위반, UK 위반과 동일한 `DataIntegrityViolationException` 계열):
    ```
    03:15:45.899 ERROR o.h.e.jdbc.spi.SqlExceptionHelper - ERROR: value too long for type character varying(500)
    03:15:45.924 DEBUG TrainingVideoIngestTx  - [TrainingIngest] duplicate ingest race — skip clipId=VB3-CLIP-01
    03:15:45.925 ERROR TrainingVideoIngestService - [TrainingIngest] ingest failed evntId=VB3-EVT-01 clipId=VB3-CLIP-01 causeType=UnexpectedRollbackException
    03:15:45.925 INFO  TrainingVideoIngestService - [TrainingIngest] scan finished scanned=1 ingested=0 limit=100 carriedOver=false
    ```
    catch 블록이 실행됐음에도(`DEBUG` 줄) 예외가 호출자까지 올라가 `:92-97` 의 포괄 catch 가 `ERROR` 로 받아냈다.
  - 부수 결함: catch 가 `DataIntegrityViolationException` **전체**를 잡아 "duplicate ingest race" 로 단정한다. 위 사례처럼 **중복이 아닌** 데이터 오류(길이 초과·NOT NULL·CHECK)도 같은 문구로, 그것도 `DEBUG` 로 기록돼 원인이 은폐된다.
  - 테스트가 이를 못 잡는 이유: `TrainingVideoIngestTxTest#treatsUniqueViolationAsDuplicateSkip` 은 Mockito 로 리포지토리를 스텁해 예외만 던지므로 **실 트랜잭션 커밋 단계가 존재하지 않는다**(전형적 거짓 GREEN).
- **재현/확인 경로**:
  ```sql
  INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, lclgv_cd, file_path, vdo_len_sec, crt_dt, job_dmnd_yn, vms_cctv_id)
  VALUES ('VB3-EVT-01','ORIGINAL','VB3-CLIP-01','11110', '/app/storage/raw/seed/'||repeat('x',560)||'.mp4', 30000, now(), 'Y', 'CCTV-T9');
  ```
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REVIEWER_TOKEN"
  docker logs klid-backend --since 60s 2>&1 | grep -E "TrainingIngest|value too long"
  # 정리: DELETE FROM mng_clip_master WHERE evnt_id='VB3-EVT-01';
  ```
- **영향**: 기능 영향은 제한적이다 — 상위 루프(`TrainingVideoIngestService:92-97`)가 예외를 흡수해 스캔은 계속되고 중복 INSERT 도 실제로 막힌다(DB UK). 그러나 ① 2노드 Active-Active 정상 운영 중 발생하는 **정상적인 race 마다 `ERROR` 로그**가 쌓여 알람·로그 분석이 오염되고, ② `DataIntegrityViolationException` 을 전부 "중복"으로 라벨링해 **실제 스키마/데이터 오류가 `DEBUG` 로 묻힌다**(CWE-390 부적절한 예외 처리 / CWE-755). ③ 코드·주석·테스트가 모두 "흡수된다"고 주장해 후속 개발자가 오독한다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.**
  1. 중복 판정을 예외 타입이 아니라 **제약명**(`uk_ls_data_raw_vms_clip`)으로 좁히고, 그 외 `DataIntegrityViolationException` 은 `WARN`+원인 코드와 함께 그대로 전파.
  2. 흡수를 정말 원한다면 `ingestOne` 안이 아니라 **트랜잭션 경계 바깥**(스캔 루프)에서 `UnexpectedRollbackException`/제약 위반을 중복으로 식별해 `DEBUG` skip 처리 — PostgreSQL 은 tx abort 후 같은 tx 를 커밋할 수 없으므로 경계 안에서의 흡수는 원리적으로 불가.
  3. 회귀 테스트는 Mockito 가 아니라 **Testcontainers 실 DB + 실 트랜잭션 커밋**으로 작성(같은 `VMS_CLIP_ID` 2회 적재).

### [B-ISSUE-02] TC-BATCH-021 — 가드-스킵·적재실패 후보가 `NOT EXISTS` 필터에서 빠지지 않아 tick 예산(100건)을 영구 점유한다(신규 클립 기아)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `MngClipMasterRepository.java:38,41-42` javadoc — *"{@link Pageable} — tick 당 처리 상한. 잔여분은 다음 tick 이 이어서 처리한다(의도된 이월)"*, `TrainingVideoIngestService.java:59-61` — *"상한을 넘는 후보는 굶지 않고 다음 tick 에 이어서 처리된다(… 적재된 클립은 다음 조회에서 `NOT EXISTS` 로 빠지므로 **커서가 전진한다**)"*. 즉 매 tick 마다 후보 집합이 줄어 결국 모든 학습용 클립이 적재돼야 한다.
- **현재 동작(이슈 내용)**: 커서 전진의 전제는 "선택된 클립이 `LS_DATA_RAW` 행을 만든다"인데, 아래 세 부류는 **행을 만들지 않으면서 후보 조건은 계속 만족**한다.
  1. `CLIP_ID` blank → `TrainingVideoIngestTx.java:86-89` skip (`FILE_PATH` 는 유효하므로 후보 쿼리는 통과)
  2. `VMS_CCTV_ID` blank → `:92-96` skip
  3. 적재 자체가 실패하는 클립(B-ISSUE-01 의 제약 위반 등)
  이들은 `NOT EXISTS (SELECT 1 FROM LsDataRaw r WHERE r.vmsClipId = c.clipId)` 를 **영원히 만족**해 매 tick 재선택된다. 정렬이 `ORDER BY evntId ASC, clipTypeCd ASC` 로 고정이라, 이런 클립이 100건 누적되면 그보다 뒤 순번의 정상 클립은 **영구히 선택되지 않는다**.
  - **실동작 증거 A(예산 점유)**: 스킵 전용 후보 101건 + 기존 2건 = 103건 투입 →
    ```
    03:12:40 [TrainingIngest] scan finished scanned=100 ingested=0 limit=100 carriedOver=true
    (skip WARN 100줄, 마지막 = VB2-EVT-198)
    ```
    100건 전량이 스킵으로 소모되고 `ingested=0`. 이 상태에서 후속 tick 도 동일 100건을 다시 집는다(어떤 행도 생기지 않았으므로 후보 집합 불변).
  - **실동작 증거 B(비전진 반복)**: 적재 실패 클립 1건에 대해 scan 을 2회 연속 실행 → 두 번 모두 동일하게 `ingest failed evntId=VB3-EVT-01` ERROR 발생, `ls_data_raw` 는 계속 0행.
  - 관제 스키마상 `mng_clip_master.clip_id`·`vms_cctv_id` 는 **둘 다 nullable** 이므로(실측 `\d mng_clip_master`) 이 조건은 운영에서 실제로 발생 가능하다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, lclgv_cd, file_path, vdo_len_sec, crt_dt, job_dmnd_yn, vms_cctv_id)
  SELECT 'VB2-EVT-'||lpad(g::text,3,'0'), 'ORIGINAL', 'VB2-CLIP-'||lpad(g::text,3,'0'), '11110',
         '/app/storage/raw/seed/sample-cctv-1080p.mp4', 30000, now(), 'Y', NULL
  FROM generate_series(101,201) g;
  ```
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REVIEWER_TOKEN"   # → data:0
  docker logs klid-backend --since 40s 2>&1 | grep "scan finished"   # scanned=100 ingested=0 carriedOver=true
  # 재실행해도 동일 100건이 다시 선택됨(후보 집합 불변)
  # 정리: DELETE FROM mng_clip_master WHERE evnt_id LIKE 'VB2-EVT-%';
  ```
- **영향**: 기능/가용성. 관제가 학습용으로 설정한 정상 영상이 **무기한 적재되지 않는다**(사일런트 기아 — 알람 없음, `scan finished ingested=0` 만 반복). 목표 규모가 영상 5,000건(SFR-17)이라 불량 메타 클립이 100건만 누적돼도 파이프라인 입구가 완전히 막힌다. 동시에 매 tick `WARN`/`ERROR` 100줄이 관제 공유 DB 쿼리와 함께 반복된다. 1차 B-ISSUE-04 가 지적한 "매 tick 전량 재조회 후 전량 skip" 이 **상한 안에서 국소적으로 재발**한 형태다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.**
  1. 후보 쿼리에 정적으로 판별 가능한 배제 조건을 추가 — `AND c.clipId IS NOT NULL AND TRIM(c.clipId) <> '' AND c.vmsCctvId IS NOT NULL AND TRIM(c.vmsCctvId) <> ''`(`FILE_PATH` 와 동일 패턴). 그러면 세 부류 중 1·2 가 후보에서 사라져 커서가 전진한다.
  2. 정적 판별이 불가한 3(적재 실패)에는 **실패 기록 테이블 또는 마지막 시도 시각 컬럼**을 두고 백오프 재시도(예: 24h 후 재시도)로 예산 점유를 끊는다.
  3. `scanned`/`ingested`/`skipped` 를 분리 집계해 `skipped == scanned` 가 연속 N tick 지속되면 `WARN` 1줄로 기아를 관측 가능하게 한다.

### [B-ISSUE-03] TC-BATCH-002/009 부수 발견 — 관제 `MNG_CLIP_MASTER.FILE_PATH`(varchar 1000) vs `LS_DATA_RAW.RAW_FILE_PATH_NM`(varchar 500) 폭 축소로 500자 초과 경로 클립은 영구 적재 불가

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 저작도구는 관제 공유 테이블을 READ 해 적재하므로, 관제가 넣을 수 있는 값의 도메인을 수용할 수 있어야 한다. 수용 불가한 값은 **명확한 사유로 skip 되고 관측**돼야 하며, 침묵 실패로 무한 재시도되면 안 된다.
- **현재 동작(이슈 내용)**: 실측 스키마
  ```
  mng_clip_master.file_path        character varying(1000)  (관제 소유)
  ls_data_raw.raw_file_path_nm     character varying(500)   NOT NULL (저작도구 소유)
  ```
  586자 경로 클립 1건으로 scan 실행 시 `ERROR: value too long for type character varying(500)` → B-ISSUE-01 의 오분류 경로를 타 `DEBUG duplicate ingest race` 로 기록되고, `ERROR ... causeType=UnexpectedRollbackException` 으로 마감된다. 적재 행이 안 생기므로 B-ISSUE-02 에 따라 매 tick 무한 재시도된다.
- **재현/확인 경로**: B-ISSUE-01 의 재현 스크립트와 동일. 스키마 대조는
  ```sql
  select table_name, column_name, character_maximum_length from information_schema.columns
   where (table_name='mng_clip_master' and column_name='file_path')
      or (table_name='ls_data_raw' and column_name='raw_file_path_nm');
  ```
- **영향**: 데이터 정합/가용성. NAS 경로가 깊은 운영 환경에서 특정 영상이 원인 불명으로 영구 미적재된다. 로그가 "duplicate" 라고 말하므로 운영자가 원인에 도달하기 어렵다. 표준도메인 관점에서도 소유 테이블 간 동일 의미 컬럼의 길이가 다른 것은 감리 지적 대상이 될 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ① `RAW_FILE_PATH_NM` 을 관제와 동일 폭(1000)으로 확장(표준도메인 확인 후 Flyway 마이그레이션), 또는 ② 적재 전 길이 검증 가드를 `FILE_PATH` blank 가드 옆에 추가해 명시적 `WARN` skip + 후보 배제. ①과 ② 중 어느 쪽이든 B-ISSUE-01(오분류)·B-ISSUE-02(무한 재시도)와 함께 다뤄야 실효가 있다.

### [B-ISSUE-04] TC-DEID-007 — `AsyncDeidentifyRunner.loadRaw` 자기호출로 `@Transactional(REQUIRES_NEW, readOnly)` 가 적용되지 않는다 (1차 B-ISSUE-06 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 애너테이션이 붙어 있으면 그 경계가 실제로 열려야 한다. 이 프로젝트는 같은 패턴이 실제 결함으로 이어진 전례가 있어(`DeidentifyStep.execute` 가 `ObjectProvider` 자기참조 프록시를 도입한 이유, 프레임 추출 deid 경로 NULL 버그) `BatchStepTransactionBoundaryTest` 라는 정적 가드까지 두고 있다.
- **현재 동작(이슈 내용)**: 선언부에는 경계가 있으나 호출부가 `this` 직접 호출이라 Spring AOP 프록시를 우회한다.
  ```java
  // AsyncDeidentifyRunner.java:79
  LsDataRaw raw = loadRaw(rawSn).orElse(null);                   // ← 자기호출(프록시 우회)
  // AsyncDeidentifyRunner.java:110-112
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  동일 패턴이 `BatchOrchestrator.java:104 → :149` 에도 남아 있다. 현재는 리포지토리 기본 트랜잭션으로 조회가 수행돼 **기능상 정상**이며(실동작: rawSn 126/132/133/139~142 모두 정상 로드, 미존재 rawSn 은 skip), `DeidentifyStep.execute` 는 별도로 프록시 경유가 보장된다.
- **재현/확인 경로**: 정적 — `grep -n "loadRaw" backend/src/main/java/kr/co/cudo/authoring/batch/runner/AsyncDeidentifyRunner.java backend/src/main/java/kr/co/cudo/authoring/batch/orchestrator/BatchOrchestrator.java`
- **영향**: 현재 무해(readOnly 단건 조회). 다만 ① 반환 엔티티가 detached 이며 선언된 격리 보장이 실재하지 않고, ② 이 메서드에 잠금·격리수준 의존 로직이 추가되는 순간 즉시 결함이 된다. ③ `BatchStep` 에는 정적 가드가 있으나 **러너/오케스트레이터에는 동등한 가드가 없어** 같은 패턴이 재유입될 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `DeidentifyStep` 이 쓰는 `ObjectProvider<Self>` 프록시 경유 패턴을 적용하거나 조회를 별도 빈(`BatchRawLoader`)으로 분리. 최소 조치로는 애너테이션 제거 + "리포지토리 기본 트랜잭션 사용" 주석 명시. 아울러 `BatchStepTransactionBoundaryTest` 와 같은 정적 스캔을 러너/오케스트레이터의 `protected @Transactional` 자기호출로 확장하는 것을 검토.

### [B-ISSUE-05] TC-DEID-019 — mock 비식별 복사 `IOException` 분기('F' 마킹)의 회귀 테스트가 여전히 없다 (1차 B-ISSUE-05 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: `runMock` 의 실패 분기 3종(원본 부재 / base 거부 / **복사 IOException**)은 모두 "성공 위장 금지 — 별도 커밋으로 `'F'` 기록" 이라는 동일 안전 계약을 가지므로 동등한 회귀 보호가 있어야 한다. 실제로 원본 부재(1건 이상)와 base 거부(S1/S2)는 테스트로 고정돼 있다.
- **현재 동작(이슈 내용)**: 코드는 정확하다.
  ```java
  // DeidentifyStep.java:330-335
  } catch (IOException e) {
      batchTransitionService.recordDeidentFailure(raw.getRawSn(), MOCK_ERROR_CODE, e.getClass().getSimpleName());
      log.error("[Batch][Deid][mock] copy failed rawSn={} errType={}", raw.getRawSn(), e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.INTERNAL_ERROR, "mock 비식별 복사 실패", e);
  }
  ```
  그러나 `DeidentifyStepTest` / `DeidentifyStepExecutePersistenceIntegrationTest` / `DeidentifyStepFailurePersistenceIntegrationTest` / `DeidentifyStepKpstDisabledIntegrationTest` 어디에도 `IOException` 을 주입하는 케이스가 없다(두 IT 의 `import java.io.IOException` 은 픽스처 셋업용). `copyAtomically`/`moveAtomically` 리팩토링 시 이 분기가 조용히 깨져도 감지되지 않는다.
- **재현/확인 경로**:
  ```bash
  grep -rn "IOException" backend/src/test/java/kr/co/cudo/authoring/batch/step/DeidentifyStep*.java
  grep -rn "복사 실패\|copy failed" backend/src/test/java/kr/co/cudo/authoring/batch/step/   # → 0건
  ```
- **영향**: 회귀 위험(테스트 커버리지 갭)이며 현재 기능 결함은 아니다. 다만 이 분기가 깨지면 "복사 실패인데 예외 없이 진행" 또는 "'F' 미기록" 으로 이어져 **비식별 성공 위장**이라는 HIGH 급 결과를 낳는 위치다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** target 부모 경로를 읽기 전용 디렉터리로 만들거나 target 이름과 동일한 디렉터리를 미리 생성해 `Files.copy`/`Files.move` 가 `IOException` 을 던지도록 유도하는 통합 테스트 1건 추가(원본 부재 테스트의 픽스처 재사용). 단언은 `DE_IDNTF_YN='F'` 영속 + `MARKING_READY` 미전이 + `INTERNAL_ERROR`.
