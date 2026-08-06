# B 클러스터 (배치 파이프라인/비식별화) — 2차 검증 결과

> 330건 · 기준 실동작(HEAD ca3c712b + mock-server 실왕복 + 파이프라인 실구동) · 2026-07-31


---

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

---

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

---

# B 클러스터 part3 (B-7·B-8) 2차 검증 결과

> 대상: `docs/test-cases/B-batch-deidentify.md` §B-7(TC-VLM-001~025·030~040, 36건) · §B-8(TC-BATCH-100~118, 19건) = **55건**
> 환경: backend `localhost:18081` (이미지 `bdc64ea2ac26` = HEAD `ca3c712b` 재빌드본) · mock-server `:9400` · postgres 스키마 `public`
> 실측 시각: 2026-07-31 03:17~03:27 KST (= mock 로그 UTC 18:17~18:27). `~~취소선~~` 폐기 행 **0건**(집계 제외 대상 없음).
> ⚠ 다른 에이전트가 같은 스택에서 rawSn 134~143 을 동시 구동 중이었다. 본 검증의 모든 실동작 근거는 **내가 생성한 rawSn=144**(관제 클립 `DEV-CLIP-9301`) 또는 **내 고유 request_id**(`6af6d189…` / `407da499…` / `d44875d4…`)로 식별된 것만 인용했다.

---

## 집계

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-7 VLM 위탁·콜백·보류/재개 | 36 | 31 | **1** | 4 | 0 | 0 | 0 |
| B-8 FfmpegFrameExtractor | 19 | 19 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **55** | **50** | **1** | **4** | 0 | 0 | 0 |

- 근거 확인 방식: **[실동작] 21건 · [정적] 34건**.
- **근거 `file:line` 드리프트: B-7 24건 / B-8 2건**(아래 별도 절). B-7 은 `862ca6d8`(외부연동 논블로킹화, Phase C-1)로 `VlmTimeseriesStep` 이 전면 재작성돼 케이스 표의 라인이 **거의 전부** 무효다.
- self-fill 확증 사례 **0건**(아래 절).

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|:--:|
| **B-ISSUE-21** | VLM 이 로컬 목업을 구조적으로 경유 불가(`VLM_CLIENT_ENABLED=false` + `WebClientConfig` HTTPS-only/사설IP 차단으로 배선 시 부팅 크래시) → TC-VLM 전반 실동작 검증 불가 | **✅ 해소.** `VLM_CLIENT_ENABLED=true` · `VLM_SERVICE_URL=http://klid-mock-server:9400`(prop `vlm.client.url`) · `VLM_ALLOW_INSECURE_URL=true`(`application.yml:626-632`) 로 부팅 정상. 내 요청 3건이 backend 컨테이너 IP `172.18.0.5` 에서 mock `POST /v1/videovlm/describe` → 200 → 역방향 콜백 200 까지 완주 | 해소 |
| **B-ISSUE-22** | 동일 rawSn 에 마킹 N건 생성 가능, VLM 은 첫 건만 위탁 → 나머지 영구 PENDING | **✅ 해소.** 실측 `ls_marking` 은 rawSn 당 1행(4/126·5/132·6/133·7/135…), `VlmResultService.markingsInScope`(:186-208)가 `ACTIVE_STATUSES`(PENDING+VLM_REQUESTED)로 넓어져 선행 레이스도 닫힘 | 해소 |
| **B-ISSUE-24** | VLM skip(disabled)이 DB 에 무흔적 → 사후 재처리 대상 식별 불가 | **✅ 해소.** `BatchStatusService.recordVlmSkipped`(:58-62)+`createSkipped` 로 `LS_BATCH_PROC_LOG(PROC_STEP_CD='VLM', PROC_STTS_CD='SKIPPED', ERR_MSG_CN=사유)` 적재. 실측: rawSn=144 행 sn=19, rawSn=128 행 sn=8~11,13 | 해소 |
| **B-ISSUE-25** | VLM 콜백 무인증 + rate limit 미적용 + IP allowlist TODO 미이행 | **✅ 해소(3계층).** `VlmResultController` 주석대로 ①IP allowlist(`webhook.vlm.allowed-ip-cidrs`, `application.yml:671`) ②`HmacWebhookFilter` 무서명 가드(size cap + rate limit) ③발급 게이트. TODO 주석 제거됨. ⚠ dev 실효값 `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none` 이라 이 환경에선 IP 층이 사실상 무제한(운영 형상 별건) | 해소 |
| **B-ISSUE-27** | TC-VLM-010 "45s" 근거 불일치 + TC-VLM-013/024/025 테스트 부재 | **🔶 부분.** 근거 불일치는 더 커졌다(45s 블록 자체가 삭제 — B-ISSUE-42). 커버 갭 4건은 **그대로 미해소**(B-ISSUE-46) | 미해소 |
| **B-ISSUE-41(1차)** | 07-22 이전 레거시 프레임 `DE_IDNTF_SRC_FILE_PATH_NM` NULL 백필 미이행 | 스코프 밖(카탈로그도 "—"). 신규 추출분은 전부 non-null 실측(126·132·133·129~131) | 이월 |
| **E-ISSUE-41(1차)** | 해상도 파생 orgnl/deid 프레임 경로 **동일** → 2벌 바이트 동일 | B-8 축에서는 재발 없음 — `frames/raw/{rawSn}` vs `frames/deid/{rawSn}` 분기 + md5 상이 실측(아래 절) | 해소(B-8 축) |
| **UNCERTAINTIES #13** | VLM 타임아웃 이중 구조(블록 45s ↔ 실효 10s) | **재정정 필요.** 블록 45s 는 코드에 더 이상 없다 — 단일 실효 타임아웃 `vlm.client.timeout-seconds=10`(`VlmClient.java:71,108`)만 존재(B-ISSUE-42) | 갱신 필요 |
| **test-baseline 스킵 5건** | 전부 "라이브 목 서버 실연동 IT — KPST/VLM 왕복" 조건부 SKIP | **이번에도 미실행.** 즉 `VlmClient`↔벤더 계약(describe 요청/응답 스키마·request_id echo·4xx 비재시도)의 **자동 회귀 가드는 2차에도 0건**이다. 본 검증의 왕복 근거는 전부 수동 실동작이며, 자동화된 계약 가드는 여전히 부재 | 미해소 |

---

## ★ VLM 신고 게이트 진입점별 실측

> 게이트 위치: `VlmTimeseriesStep.doSubmit()` **:303-307** — `resolveDeidentifiedPath`(:309) **이전**, `existsById`(:276) **이후**. `run`/`runWithMarking` 이 공통으로 부르는 private 본체라 **어느 진입점도 우회 불가**.

| 진입점 | 신고중 호출 | 기대 | 실측 | 판정 |
|---|---|---|---|:--:|
| `execute(ctx)` (오케스트레이터/Quartz) | `POST /v1/dev/batch/trigger?rawSn=144` (144 `DE_IDENT_YN='F'`) | 외부 호출 0 · SKIPPED · 사유 적재 | mock `videovlm/describe` 누계 **13→13(불변)** · `ls_batch_proc_log` sn=19 `VLM/SKIPPED/비식별 누락 신고 구간…` · backend WARN `withheld — deident report open rawSn=144` | **PASS** |
| `execute(ctx)` (별건 재현) | `trigger?rawSn=128` (128 `'F'`) | 동상 | mock 누계 **9→9(불변)** · sn=13 SKIPPED 적재 | **PASS** |
| `runWithMarking(rawSn, marking)` | (직접 호출 API 없음 — 재개 러너 `VlmWithheldResumeRunner:71` 이 프록시 경유 호출) | 스텝 안 게이트로 차단 | 게이트가 `doSubmit` 공통 본체(:303)에 있어 이 경로도 통과 필수 — 코드상 우회 경로 0 | **PASS**(정적) |
| `run(rawSn)` (재개 러너 :69) | 신고 해소 직후 재개 | 게이트 재판정 후 진행 | 03:26:33 resume → describe submit `d44875d4…` → mock 누계 **15→16** → 콜백 200 → 메타 15건. 게이트 재판정 통과 확인 | **PASS**(실동작) |
| `BatchReprocessService.retry` / Quartz 재큐 | (오케스트레이터 경유 = 위 1행과 동일 경로) | 동상 | 동상 | **PASS**(정적) |

**보류가 실패가 아님(TC-VLM-032) 실측**: rawSn=144 의 `LS_BATCH_PROC_LOG` 에서 VLM SKIPPED 행 `RTRY_NMTM=0`, 진행 행(FRAME_EXTRACT)만 재시도 카운트 증가. 트리거 응답에도 VLM 관련 예외 없음(파이프라인은 VLM 을 지나 FRAME_EXTRACT 까지 진행한 뒤 "마킹 데이터가 없습니다"로 실패 — VLM 이 원인이 아님).

---

## ★ 프레임 2벌 실측 (원본 ≠ 비식별)

기준 영상 rawSn=126 (`LS_DATA_SRC` 65/66/67). 비식별 입력 영상은 **co-locate** 위치
`/app/storage/raw/seed/126/deid/sample-cctv-1080p-mask.mp4` — `STORAGE_DEIDENTIFIED_PATH=/app/storage/deidentified` **밖**이다. 즉 구 `deidentified-path` 단독 판정이었다면 이 영상은 신뢰불가로 거부돼 비식별 벌이 결손됐을 형상이며, 실제로 2벌이 나온 것이 **TC-BATCH-113 회귀 방지의 직접 증거**다.

| frm_no | `SRC_FILE_PATH_NM` (원본) | md5 | `DE_IDNTF_SRC_FILE_PATH_NM` (비식별) | md5 | 동일? |
|--:|---|---|---|---|:--:|
| 0 | `/app/storage/raw/frames/raw/126/frame-0.jpg` | `c155a39c37409fdee2aec1a423c011f5` | `/app/storage/deidentified/frames/deid/126/frame-0.jpg` | `064376b24753aaf61f950a89f66b0e96` | **아니오** |
| 1 | `…/frames/raw/126/frame-1.jpg` | `ec0a85e7457d538c340ee04ac5e64118` | `…/frames/deid/126/frame-1.jpg` | `d3cdcc46de5d07c6c78d063c32fd6be5` | **아니오** |
| 2 | `…/frames/raw/126/frame-2.jpg` | `7f05666339f083e10104722270a7ffcd` | `…/frames/deid/126/frame-2.jpg` | `57576ea03c17ef38cf1e7325771558fe` | **아니오** |

- 경로 분기: `{base}/frames/**raw**/{rawSn}` vs `{base}/frames/**deid**/{rawSn}` (`FfmpegFrameExtractor.resolveSafeOutputDir:377-384`) — 두 base 가 같아도 충돌 없음. **1차 E-41 형태(`dst, dst` 로 두 컬럼 동일)의 재발 없음.**
- export 산출물도 동일: `126/v2/orgnl/0000.jpg`(`c155a39c…`) ≠ `126/v2/deid/0000.jpg`(`064376b2…`).
- 이력(TC-BATCH-112): `LS_DATA_SRC_HSTRY` src 65/66/67 각 **`CREATED` + `DEID_ATTACHED` 2건** 실측.
- 다른 영상 실측도 동일: 132(6프레임)·133(6프레임)·파생 129/130/131 전부 `DE_IDNTF_SRC_FILE_PATH_NM` non-null. **PII fail-open(원본을 비식별본으로 적재) 사례 0건.**

---

## B-7 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-VLM-001 | Step: 마킹 있으면 runWithMarking(최신 1건) | PASS | [정적] `VlmTimeseriesStep.java:218-227` + `BatchStepExecuteTest#VLM_execute_마킹있으면_runWithMarking_호출_stage_VLM` | 근거 드리프트(doc 135-142). `ls_marking` 실측 rawSn 당 1행 |
| TC-VLM-002 | Step: 마킹 없으면 run | PASS | [실동작] rawSn=144 트리거 로그 `describe submit rawSn=144 … hasMarking=false` + `:224-226` | 드리프트(doc 139-141) |
| TC-VLM-003 | enabled=false → SKIPPED + DB 기록 | PASS | [정적] `:269-273` → `BatchStatusService.recordVlmSkipped:58-62` · `VlmTimeseriesStepTest#VLM_비활성일_때_LS_BATCH_PROC_LOG_에_VLM_SKIPPED_행이_사유와_함께_남는다` | 이 환경은 `enabled=true` 라 라이브 재현 불가(컨테이너 재기동 금지). 드리프트(doc 182-186·57-64) |
| TC-VLM-004 | rawSn null → INVALID_INPUT | PASS | [정적] `:261-263`(enabled 체크보다 앞) + 테스트 존재 | 드리프트(doc 174-176) |
| TC-VLM-005 | 영상 미존재 → NOT_FOUND | PASS | [정적] `:276-278`(enabled 체크 이후 ✔) + 테스트 존재 | 드리프트(doc 189-191) |
| TC-VLM-006 | 비식별 경로 없음 → fail-closed | PASS | [실동작] mock 로그 `media duration probed file=sample-cctv-1080p-**mask**.mp4` = 비식별본만 전달 · [정적] `resolveDeidentifiedPath:390-400`(원본 경로 전송 코드 부재) | 드리프트(doc 280-290) |
| TC-VLM-007 | recordIssued 실패 → describe 미호출 | PASS | [정적] `:318-325` try/catch → `EXTERNAL_API_ERROR` abort(제출 코드 :361 이전) + 테스트 존재 | 드리프트(doc 231-238) |
| TC-VLM-008 | recordIssued 독립 커밋(REQUIRES_NEW) | PASS | [정적] `PersistentWebhookIdempotencyLedger.java:57-58` `@Transactional(REQUIRES_NEW)` · [실동작] ledger 행 `6af6d189…/VLM/PROCESSED/144` 생성 | 드리프트(doc 232) |
| TC-VLM-009 | describe 응답 null → EXTERNAL_API_ERROR | **PARTIAL** | [정적] 빈 응답 가드는 `:364-365 switchIfEmpty(Mono.error(EXTERNAL_API_ERROR))` 로 존치하나, **호출자에게 전파되지 않고** `:369-370` 비동기 `onSubmitFailed` 로 흡수 | **B-ISSUE-43** — 논블로킹 전환 부수효과, 케이스 기대값 무효 |
| TC-VLM-010 | 블록 45s ↔ 실효 10s 이중 구조 | **PARTIAL** | [정적] 실효 10s 는 성립(`VlmClient.java:71,108` + `application.yml:633`). **`BLOCK_TIMEOUT=45s`·`.block(...)` 은 코드에 존재하지 않음**(grep 0건, 주석 문구로만 잔존 `:70,348`) | **B-ISSUE-42** — UNCERTAINTIES #13 의 "이중 구조" 전제 자체가 갱신 대상 |
| TC-VLM-011 | 위탁 성공 시 마킹 PENDING→VLM_REQUESTED | **PARTIAL** | [정적] 전이는 존재하나 시점이 반전 — **제출 전 선커밋**(`:337` → `VlmMarkingTxService.persistVlmRequested`, REQUIRES_NEW 별도 빈). 성공 여부와 무관하게 먼저 전이 | **B-ISSUE-44** — 콜백 선행 레이스 폐쇄를 위한 의도된 변경. 기대값("위탁 성공 시") 무효 |
| TC-VLM-012 | retry — 이미 전이된 마킹 no-op | PASS | [정적] `VlmMarkingTxService.persistVlmRequested` → `LsMarking.markVlmRequested()` PENDING 한정 · `VlmTimeseriesStepMarkingTest#retry_재실행_시_이미_VLM_COMPLETED_마킹은_VLM_REQUESTED로_역행하지_않는다` | 드리프트(doc 300-308) |
| TC-VLM-013 | 콜백 URL 고정 base(SSRF 차단) | PASS | [실동작] mock 로그 `describe accepted … callback_url=http://klid-backend:8080/api/v1/vlm/callback`(= `WEBHOOK_CALLBACK_BASE_URL` 고정값) · [정적] `resolveCallbackUrl:403-411`(사용자 입력 미반영) | 드리프트(doc 311-319). 전용 단위테스트는 여전히 부재(B-ISSUE-46) |
| TC-VLM-014 | 미발급 request_id → UNAUTHORIZED | PASS | [실동작] `POST /v1/vlm/callback` `request_id=11111111-2222-4333-8444-555555555555` → **401** `{"errorCode":"UNAUTHORIZED","message":"발급되지 않은 request_id 입니다."}` | |
| TC-VLM-015 | 이미 PROCESSED → 멱등 스킵 | PASS | [실동작] `8cc179fb…`(rawSn=126, PROCESSED) 재전송 → **200 `applied:false`**, `select count(*) … meta_vl like '%REPLAY%'` = **0** (오염 없음) | |
| TC-VLM-016 | rawSn 매핑 없음 → UNAUTHORIZED | PASS | [정적] `VlmResultService.java:83-88` + `VlmResultServiceTest#발급됐으나_rawSn_매핑_없으면_UNAUTHORIZED_거부` | 라이브 재현 불가(해당 원장 행이 전부 PROCESSED 라 :77 멱등 스킵이 선행) |
| TC-VLM-017 | 미지 status → INVALID_INPUT(멱등 미마킹) | PASS | [실동작] `status="done"` → **400** `status 는 completed\|failed 중 하나여야 합니다`(DTO `@Pattern` 1차) · [정적] 서비스 2차 방어 `:94-102`(PROCESSED 미마킹) | |
| TC-VLM-018 | failed → error 기록+VLM_FAILED+멱등 | PASS | [정적] `:105-109` + `handleFailed:210-232` + `VlmResultServiceMarkingTest#failed_콜백_수신시_VLM_REQUESTED_마킹을_VLM_FAILED로_전이_고착해제` | 드리프트(doc 168-186 → 210-232) |
| TC-VLM-019 | completed+영상 미존재 → NOT_FOUND | PASS | [정적] `:112-116` | **전용 테스트 없음**(B-ISSUE-46) |
| TC-VLM-020 | 한 콜백 내 중복 metaKey → INVALID_INPUT | PASS | [정적] `dedupSegments:238-250` + `VlmResultServiceTest#한_콜백_내_중복_구간_metaKey는_400_거부_및_조용한_덮어쓰기_없음` | 드리프트(doc 192-204 → 238-250) |
| TC-VLM-021 | META upsert — 신규만 검수큐 PENDING | PASS | [실동작] 144 1회차 `new=15 updated=0` → `ls_data_meta_review` 15행 `PENDING/VLM/AI_SERVER`. 2회차 재위탁 `new=0 updated=15` → 검수행 **15 유지(중복 0)** | 케이스 기대 그대로 |
| TC-VLM-022 | 마킹 VLM_REQUESTED→VLM_COMPLETED | PASS | [실동작] `ls_marking` 4/126·5/132·6/133 전부 `VLM_COMPLETED` · [정적] `:158-161` + `VlmResultServiceMarkingTest` | 144 는 마킹 없어 `markingsTransitioned=0`(정상) |
| TC-VLM-023 | 원자성 — 중간 실패 시 PROCESSED 롤백 | PASS | [정적] 단일 `@Transactional`(`:63`) + `markProcessedInTx`(REQUIRED, ledger `:115-116`) + `VlmResultServiceTest#검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능` | |
| TC-VLM-024 | 동일 request_id 동시 콜백 직렬화 | PASS | [정적] `lookupForProcessing`(`:69`, ledger `:136-137` 비관적 락) | **동시성 IT 없음**(증강 채널엔 `AugmentCallbackIdempotencyIT`·`GenAiCallbackRollupConcurrencyIT` 가 있는데 VLM 만 비대칭) — B-ISSUE-46 |
| TC-VLM-025 | 로그 마스킹(CR/LF/tab) | PASS | [정적] `VlmResultService.safe():252-255` `[\r\n\t]→_` · `VlmClient.safeForLog():176-179` | **전용 테스트 없음**(B-ISSUE-46) |
| TC-VLM-030 | 콜백 무인증 — isIssued 게이트 차단 | PASS | [실동작] 무토큰·무서명 콜백이 필터를 통과해 컨트롤러에 도달하나 발급 게이트에서 **401** | ⚠ 이 환경 `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none`, rate limit 은 실패 5회/분 기준이라 400/401 40연발로는 미발화(관측). A 클러스터 축 |
| TC-VLM-031 | ★신고 구간 위탁 보류 — 경로 해석 직전 게이트 | PASS | [실동작] 144·128 양쪽 mock describe 누계 불변 + SKIPPED 사유 적재 · [정적] `:303-307`(→ `:309 resolveDeidentifiedPath` 이전) | 드리프트(doc 216-220,222) |
| TC-VLM-032 | 보류는 실패가 아니다 | PASS | [실동작] VLM SKIPPED 행 `RTRY_NMTM=0`, 예외 미발생, 파이프라인이 다음 단계까지 진행 · [정적] `:303-307` 은 예외 대신 `skipped` 반환 | 드리프트(doc 206-220) |
| TC-VLM-033 | 게이트 조회 실패는 fail-closed | PASS | [정적] `:303` 에 try/catch 없음 → `DeidentReportGate:66-71` 예외 그대로 전파(주석 `:63-64` 도 동일 규약 명시) | 드리프트(doc 215-216) |
| TC-VLM-034 | 해소 시 재위탁 배선 | **FAIL** | [실동작] 배선·이벤트는 발화하나(`VlmResumeBridge:34-39` → `resumeAsync`) **멱등 조건이 항상 참이라 재위탁이 실행되지 않음**: `resume skipped — timeseries meta already present rawSn=144 count=6`(count=6 은 ingest 시점 `video.*` 기술메타) | **B-ISSUE-41 (CRITICAL)** — A/B 대조로 확증 |
| TC-VLM-035 | 재위탁 멱등: 시계열 메타 ≥1건이면 skip | **PARTIAL** | [실동작] skip 동작 자체는 성립하나 판정이 **시계열 메타로 한정되지 않음**(`VlmWithheldResumeRunner:94` → `LsDataMetaRepository.countByRawSn:29` = `RAW_SN` 전건 카운트) | 동일 이슈 **B-ISSUE-41** |
| TC-VLM-036 | 재위탁 조건: 보류 사유가 신고일 때만 | PASS | [정적] `RESUMABLE_SKIP_REASONS`(`:150-152`)에 `SKIP_REASON_DISABLED` **미포함** → 재개 대상 아님 · `BatchStatusService.isStageSkippedWithAnyReason:114-119` | 드리프트: 단일 상수 → **4종 목록**(신고보류·제출실패·ACK미수신·콜백미수신)으로 확장. 케이스의 "단일 원천은 SKIP_REASON_DEIDENT_REPORT" 서술 갱신 필요 |
| TC-VLM-037 | 재위탁 실패는 삼킴(best-effort) | PASS | [정적] `VlmWithheldResumeRunner:73-78` `catch(RuntimeException) → log.warn(클래스명만)` + `VlmWithheldResumeRunnerTest#재위탁_실패는_삼켜서_해소_트랜잭션에_영향을_주지_않는다` | 드리프트(doc 71-76) |
| TC-VLM-038 | recordVlmTimeseriesResult = REQUIRES_NEW | PASS | [정적] `BatchStatusService.java:149-156` `@Transactional(REQUIRES_NEW)` · [실동작] `ls_batch_proc_log` `RESP_PAYLOAD_CN={"requestId":…,"status":…}` 적재(sn 4/5/6) | 드리프트(doc 109-116). 호출자가 `VlmSubmitOutcomeRecorder.onAccepted`(파이프라인 스레드 밖)로 바뀌어 REQUIRES_NEW 필요성은 오히려 강해짐 |
| TC-VLM-039 | recordVlmSkipped 는 REQUIRED 유지(의도) | PASS | [정적] `:58-62` REQUIRED 유지 + 신규 `recordVlmSkippedInNewTx:78-82`(REQUIRES_NEW)를 **별도 메서드로 분리**, 공통 로직은 비트랜잭션 private 헬퍼 `saveVlmSkipRow:85-88` | 드리프트(doc 57-64,104-108). 자기호출 회피 설계 확인 |
| TC-VLM-040 | VLM 스텝 트랜잭션 경계 = execute | PASS | [정적] `:218-220` `@Transactional(REQUIRES_NEW)` 쓰기가능, `:223/:225` 는 자기호출이라 중첩 없음 · `BatchStepExecuteTest` 2건 | 드리프트(doc 133-142,150-168). ⚠ 프록시 경유(재개 러너)로 `run()` 이 직접 호출되면 `readOnly=true` 경계가 개시된다 — **B-ISSUE-45** 참조 |

---

## B-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-100 | execute: marks 비면 INVALID_INPUT | PASS | [실동작] rawSn=144/128 트리거 → `ls_batch_proc_log` `FRAME_EXTRACT/FAILED/마킹 데이터가 없습니다. rawSn=144` · [정적] `FfmpegFrameExtractor.java:133-136` | 근거 라인 일치 |
| TC-BATCH-101 | execute: 추출 0건 → INTERNAL_ERROR | PASS | [정적] `:144-147` + `BatchStepExecuteTest#FRAME_execute_추출결과_0건이면_INTERNAL_ERROR` | 일치 |
| TC-BATCH-102 | 영상 메타 blank → INVALID_INPUT | PASS | [정적] `:177-179` | 일치 |
| TC-BATCH-103 | 비식별 미완료 → INVALID_INPUT | PASS | [정적] `:185-188` `!"Y".equals(deIdntfYn)` + `FfmpegFrameExtractorTest#비식별_미완료_영상은_프레임추출에서_차단된다` | 일치. `'F'`(신고/실패)도 여기서 차단 — 신고 구간 배치는 VLM 보류 후 이 단계에서 FAILED 로 끝난다(설계 정합) |
| TC-BATCH-104 | 원본 미존재 → INVALID_INPUT | PASS | [정적] `:190-193` | 일치 |
| TC-BATCH-105 | ★비식별 경로가 허용 base 전부의 밖 → RAW only(fail-closed) | PASS | [정적] `:206-213` WARN 에 `rawSn` 만 출력(경로 원문 미노출, CWE-209) + 원본 폴백 코드 부재 · 판정은 `isUnderAllowedDeidBase:316-333` → `readableDeidVideoBases` · `FfmpegFrameExtractorTest#MEDsec_비식별경로가_base밖이면_fail_closed로_RAW만추출_비식별경로_미저장`, `#결함2_경로순회…`, `#결함2_다른영상의_co_locate_디렉터리_경로는_거부된다_rawSn_교차차단` | 일치 |
| TC-BATCH-106 | 비식별 경로 null/파일 부재 → RAW only | PASS | [정적] `:216-225`(파일 부재 `maskName` 해시로 마스킹 / 경로 null 분기 별도) + `#V2_마킹_비식별_영상_없으면_RAW_만_graceful` | 일치 |
| TC-BATCH-107 | 정상: 2벌 추출+deid 경로 INSERT 시점 저장 | PASS | [실동작] 126/132/133 전 프레임 `DE_IDNTF_SRC_FILE_PATH_NM` non-null, export v1/v2 `deid/` 벌 실재 · [정적] `:260-271` 비식별 프레임을 `create()` **이전에** 기록하고 6-arg `LsDataSrc.create(...)` 에 deidPath 포함 · `FfmpegFrameExtractorDeidPersistIT` 2건 | 일치. 1차 "export 항상 PARTIAL" 회귀 없음 |
| TC-BATCH-108 | seekMillis=round(frameIndex×1000/fps), pin fps 우선 | PASS | [정적] `:248 Math.round(mark.frameIndex()*1000.0/fps)` · `:234 effectiveFps(pinnedFps,…)` · `:393-398` · 테스트 6건(`M3_seekMillis…`, `TOCTOU_마킹이pin한30fps…`, `60fps_마킹pin…`) · [실동작] `ls_marking.fps=29.97002997002997` 가 pin 값으로 저장돼 있음 | 경미 드리프트(doc 266 → 실제 248) |
| TC-BATCH-109 | pin fps null/비정상 → resolveFps 폴백 | PASS | [정적] `:393-398`(null/NaN/Inf/≤0 → `fpsResolver.resolveFps`, 미상 시 30.0) + `#마킹pin이_null이면_resolveFps로_폴백한다_하위호환` | 일치 |
| TC-BATCH-110 | 출력 경로 순회 방어(CWE-22) | PASS | [정적] `resolveSafeOutputDir:377-384` normalize + `startsWith(base)` + `#경로순회_가드_raw와_deid_세그먼트_모두_base_하위로_정규화되어_통과한다` | 일치 |
| TC-BATCH-111 | frames raw/deid 서브세그먼트 분기(충돌 없음) | PASS | [실동작] `/app/storage/raw/frames/raw/126/…` vs `/app/storage/deidentified/frames/deid/126/…`, md5 상이 · [정적] `:377-384` `FrameKind` 세그먼트 · `#동일_base_주입돼도_원본과_비식별_프레임_경로가_달라_디스크_덮어쓰기_없음` | 일치 |
| TC-BATCH-112 | 이력: deid 채운 경우 CREATED+DEID_ATTACHED 2건 | PASS | [실동작] `ls_data_src_hstry` src 65/66/67 각 2행(`CREATED`,`DEID_ATTACHED`) · [정적] `:272-276` | 일치 |
| TC-BATCH-113 | ★co-locate 산출 비식별 영상 채택 | PASS | [실동작] 126 의 비식별 입력이 `/app/storage/raw/seed/126/deid/…-mask.mp4`(deid base 밖)인데도 비식별 벌 3장 실재 + `DE_IDNTF_SRC_FILE_PATH_NM` 채워짐 · [정적] `:316-333` → `VideoArtifactRootResolver.readableDeidVideoBases:337-346` · `#결함2_원본옆_co_locate_비식별영상도_채택되어_비식별프레임경로가_저장된다` | 구 결함(항상 RAW only) 회귀 없음 |
| TC-BATCH-114 | 구 위치도 계속 허용 — 2-way allowlist | PASS | [정적] `readableDeidVideoBases:337-346` 가 `deidentifiedBase` ∪ `readableDeidVideoDirs:308-320`(구 `{deid_base}/videos/{rawSn}` + co-locate) 를 모두 반환, 전략 플래그와 무관 · `#CWE59보강_후에도_심링크아닌_정상_co_locate_비식별영상은_여전히_채택된다` | 실동작 사례는 이 환경에 없음(파생 129~131 의 비식별 영상만 구 위치에 있으나 그 프레임은 프레임추출기가 아닌 파생 확정 경로 산출물) |
| TC-BATCH-115 | 심링크 방어: 대상 파일이 원본을 가리키면 거부 | PASS | [정적] `underBaseWithRealPath:356-367` — `verifyRealPathUnder` 실패를 **예외가 아니라 false** 로 접어 기존 RAW only 분기 흡수 · `VideoArtifactRootResolver.verifyRealPathUnder:458-466` · `#CWE59_co_locate_허용경로의_심링크가_원본영상을_가리키면_거부되고_RAW만_추출된다` | 일치 |
| TC-BATCH-116 | 심링크 방어: 중간 세그먼트 심링크도 거부 | PASS | [정적] `realOrNearest:474-497`(대상 부재 시 최근접 실재 조상 `toRealPath()` 후 잔여 세그먼트 재부착, 해석 불가는 FORBIDDEN) · `#CWE59_경로중간_세그먼트가_심링크로_base밖을_가리키면_거부되고_RAW만_추출된다` | 일치 |
| TC-BATCH-117 | 리졸버 미주입(단위 수동 생성) → 구 동작 | PASS | [정적] `:316-319` `artifactRootResolver == null` → `underBaseWithRealPath(deidPath, baseDeidPath)` 단독 판정(허용 범위 축소 = fail-secure) | 일치 |
| TC-BATCH-118 | 후보 도출 예외 시 구 동작 폴백 | PASS | [정적] `:320-326` `catch(RuntimeException)` → 구 동작 · 리졸버측도 이중 폴백(`:340-344`, `:314-318`) | 일치 |

---

## 근거 드리프트 / self-fill 점검

### 근거 `file:line` 드리프트

| 대상 | 건수 | 원인 | 조치 필요 |
|---|--:|---|---|
| `VlmTimeseriesStep.java` (TC-VLM-001~013, 031~033, 040) | **17** | `862ca6d8`(Phase C-1 논블로킹 제출) 로 파일이 178줄 → 417줄로 재작성. 케이스가 가리키는 라인이 전부 다른 코드 | 카탈로그 근거 일괄 갱신 |
| `BatchStatusService.java` (TC-VLM-003, 036, 038, 039) | **4** | `recordVlmSkippedInNewTx` · `isStageSkippedWithAnyReason` 신설로 라인 이동 | 갱신 |
| `VlmWithheldResumeRunner.java` (TC-VLM-034~037) | **3** | 재개 사유 4종화로 라인 이동(54-70→57-79, 79-93→88-101, 80-84→88-90) | 갱신 |
| `VlmResultService.java` (TC-VLM-018, 020, 022) | **3**(경미) | `markingsInScope`/`isCreatedAfterSubmit` 신설로 `handleFailed`·`dedupSegments` 가 뒤로 밀림 | 갱신 |
| `FfmpegFrameExtractor.java` (TC-BATCH-108) | **1**(경미) | seekMillis 계산 라인 266→248 | 갱신 |
| `VideoArtifactRootResolver.java` (TC-BATCH-114) | **1**(경미) | `readableDeidVideoDirs` 308-327→308-320, `readableDeidVideoBases` 337-348→337-346 | 갱신 |

> **의미 있는 드리프트(단순 라인 이동이 아닌 것)**: TC-VLM-009(예외 전파 → 비동기 흡수) · TC-VLM-010(45s 블록 삭제) · TC-VLM-011(전이 시점 반전) · TC-VLM-036(사유 단일 → 4종). 이 4건은 라인만 고쳐선 안 되고 **기대결과 문장 자체**를 갱신해야 한다.

### self-fill 점검

| 점검 항목 | 결과 | 근거 |
|---|:--:|---|
| VLM 시계열 메타를 BE 가 자체 생성하는가 | **아니오** | rawSn=144 에서 A/B 확증 — 위탁 보류(mock 미호출) 구간엔 시계열 메타 **0건**, 콜백 수신 직후에만 15건 생성. `VlmResultService` 는 `req.results()` 외 소스가 없다(`:129-140`) |
| 위탁 media.path 가 원본으로 대체되는가 | **아니오** | mock 프로브 로그가 `sample-cctv-1080p-**mask**.mp4`(비식별본). `resolveDeidentifiedPath:390-400` 에 원본 폴백 코드 자체가 없음 |
| 목 응답 문구가 실제로 외부에서 온 것인가 | **예** | 적재된 메타 문안이 mock 시뮬레이터 템플릿(`- 장소: … - 날씨: … - 상황: … - 환경: … - 심각성: N/10점`)이며 BE 코드에 이 문자열 0건 |
| 프레임 비식별 벌이 원본 복사인가 | **아니오** | md5 3쌍 전부 상이(mock KPST 가 워터마크를 실제로 합성한 `-mask.mp4` 에서 추출) |
| 위양성 테스트 | **1건 발견** | `VlmWithheldResumeRunnerTest#resumesWithheldSubmit` 이 `countByRawSn`=0 을 스텁 — 프로덕션에서 성립 불가능한 전제(B-ISSUE-41) |

### 검증 중 발생시킨 환경 변화 (정직 기록)

| # | 무엇을 | 왜 | 되돌림 |
|---|---|---|---|
| 1 | `mng_clip_master`/`mng_clip_evnt_lst` 에 `DEV-CLIP-9301` 1건 INSERT | 다른 에이전트가 쓰는 126~143 을 건드리지 않고 전용 검증 영상을 확보 (pipeline-drive §5 와 동일 방식, 관제 소유 입력 픽스처) | 유지(추가분) — 적재 결과가 **rawSn=144** |
| 2 | rawSn=144 로 배치 트리거 5회 · 비식별 신고 2회 · resolve 2회 | 게이트 보류·재개 경로 실동작 확인 | 최종 상태: `DATA_STTS_CD=FAILED`(마킹 없어 FRAME_EXTRACT 실패), `DE_IDENT_YN='Y'`, 시계열 메타 15건 |
| 3 | rawSn=144 의 `ls_data_meta` 21행 + `ls_data_meta_review` 15행 DELETE | **B-ISSUE-41 의 A/B 대조 실험** — "메타 0건일 때 재개가 실제로 동작하는가"를 분리 검증하기 위함. 삭제 대상은 **내가 만든 영상의 데이터만** | 이후 재개로 시계열 15건 재생성(`video.*` 6건은 미복구) |
| 4 | `docker exec klid-backend touch …/144/deid/…-mask.mp4` | resolve 가드(`DeidentReportService:568-580` — 비식별 산출물 mtime > 신고시각)를 통과시키기 위한 "외부 재비식별" 모사 | 내 영상 파일 1개 mtime 만 변경 |
| 5 | `/v1/vlm/callback` 보안 프로브 4회(미발급/재전송/미지 status/비UUID) | 전부 거부 경로 — DB 오염 0건 확인함 | 해당 없음 |

- **컨테이너 재시작·재빌드 0회. 소스/설정/테스트 파일 수정 0건. 빌드·테스트 실행 0회.** 신규 작성 파일은 본 문서 1개.

---

## 이슈 상세

### [B-ISSUE-41] TC-VLM-034 / TC-VLM-035 — 보류된 VLM 위탁의 **재개가 영구 no-op**: 멱등 조건이 시계열 메타가 아니라 `LS_DATA_META` **전건**을 세어 ingest 시점 `video.*` 기술메타에 항상 걸린다

- **심각도**: **CRITICAL** (데이터 영구 결손 + 유일 복구 경로 소실)
- **기대 동작(기대효과)**: `CLAUDE.md` 구속 정책 — "보류는 스스로 재개되지 않으므로 해소 시 `DeidentGateReopenedEvent` → `VlmResumeBridge` → `VlmWithheldResumeRunner` 가 이 SKIPPED 기록을 근거로 **재위탁**한다(멱등 조건: **시계열 메타 0건**). 이 배선이 없으면 시계열 메타가 영구 결손된다."
- **현재 동작(이슈 내용)**: 멱등 조건이 "시계열 메타 0건"이 아니라 **"그 영상의 `LS_DATA_META` 행이 0건"** 으로 구현돼 있다.

  `backend/src/main/java/kr/co/cudo/authoring/batch/runner/VlmWithheldResumeRunner.java:88-101`
  ```java
  private boolean isWithheld(Long rawSn) {
      boolean withheldLogged = batchStatusService.isStageSkippedWithAnyReason(
              rawSn, BatchStage.VLM, VlmTimeseriesStep.RESUMABLE_SKIP_REASONS);
      if (!withheldLogged) return false;
      long metaCount = metaRepository.countByRawSn(rawSn);   // ← 전건 카운트
      if (metaCount > 0) {
          log.info("[VlmResume] resume skipped — timeseries meta already present rawSn={} count={}", rawSn, metaCount);
          return false;
      }
      return true;
  }
  ```
  `LsDataMetaRepository.java:29` 의 `long countByRawSn(Long rawSn)` 에는 `META_KEY` 필터가 없다. 그런데 같은 `LS_DATA_META` 테이블에는 **적재(ingest) 시점에 `VideoMetaService`(`:62,72-77` — `video.fps/codec/bit_rate/duration_ms/filesize/resolution`)가 ffprobe 실측 6건을 AFTER_COMMIT @Async 로 먼저 써 넣는다.** 즉 **정상 적재된 모든 영상은 VLM 이 한 번도 돌기 전에 이미 `countByRawSn >= 1`** 이다 → `isWithheld` 는 언제나 false → **재위탁이 실행되지 않는다.**

  **실동작 A/B 확증 (rawSn=144, 내가 생성한 전용 영상)**

  | | 조건 | resolve 후 로그 | mock `videovlm/describe` 누계 | 시계열 메타 |
  |---|---|---|---|---|
  | **A** (실환경 그대로) | `LS_DATA_META` = `video.*` 6건 | `[VlmResume] resume skipped — timeseries meta already present rawSn=144 **count=6**` | 13 → **13 (불변)** | **0건 (영구 결손)** |
  | **B** (메타 0으로 비운 대조군) | `LS_DATA_META` = 0건 | `[VlmResume] deident report resolved — resuming withheld VLM submit rawSn=144` → `describe submit … d44875d4…` | 15 → **16 (재위탁 발생)** | **15건 생성** |

  A 와 B 의 유일한 차이는 `video.*` 6건의 존재 여부다. 재개 메커니즘 자체(브릿지·이벤트·러너·게이트 재판정)는 **전부 정상 동작**하며, 막고 있는 것은 이 카운트 범위 하나다.

  **테스트가 잡지 못한 이유(위양성)**: `backend/src/test/java/kr/co/cudo/authoring/batch/runner/VlmWithheldResumeRunnerTest.java`
  ```java
  @DisplayName("신고_보류됐던_VLM_위탁은_해제_후_재위탁된다")
  void resumesWithheldSubmit() {
      stubWithheld(true);
      when(metaRepository.countByRawSn(RAW_SN)).thenReturn(0L);   // ← 프로덕션에서 성립 불가능한 전제
      ...
      verify(vlmTimeseriesStep).run(RAW_SN);
  }
  ```
  Mockito 스텁이 DB 현실(ffprobe 메타 선적재)을 재현하지 않아 GREEN 이다. `LsDataMetaRepository` 를 진짜로 붙인 IT 는 없다.
- **재현/확인 경로**:
  ```bash
  BASE=http://localhost:18081/api; RT=<REVIEWER 토큰>
  # 1) 비식별 완료(MARKING_READY) 영상 하나를 신고 → 'F'
  curl -s -X POST $BASE/v1/videos/144/deident-report -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"reason":"..."}'
  # 2) 배치 트리거 → VLM 보류(SKIPPED) 기록, 외부 호출 0
  curl -s -X POST "$BASE/v1/dev/batch/trigger?rawSn=144" -H "Authorization: Bearer $RT"
  docker logs klid-mock-server | grep -c videovlm/describe      # 증가 없음
  # 3) 해소 → 재개가 skip 되는 것을 확인
  docker exec klid-backend touch /app/storage/raw/seed/144/deid/sample-cctv-1080p-mask.mp4
  curl -s -X POST $BASE/v1/deident-reports/5/resolve -H "Authorization: Bearer $RT" \
       -H 'Content-Type: application/json' -d '{"note":"..."}'
  docker logs klid-backend --since 30s | grep VlmResume
  #   → [VlmResume] resume skipped — timeseries meta already present rawSn=144 count=6
  ```
  ```sql
  -- 어떤 영상이 이 조건에 걸리는가 = ingest 되어 video.* 메타를 가진 전부
  select raw_sn, count(*) filter (where meta_key like 'video.%') as tech,
         count(*) filter (where meta_key not like 'video.%') as timeseries
  from ls_data_meta group by raw_sn order by raw_sn;
  ```
- **영향**:
  1. **비식별 누락 신고 구간에 배치가 돈 영상은 시계열 메타(`LS_DATA_META`)가 영구 결손**된다. 보류는 실패 행을 남기지 않으므로 `BatchRetryQueue`·실패 회수기 어느 쪽도 집지 않고(설계상 그렇게 만든 것), 재개가 유일한 복구 경로인데 그 경로가 닫혀 있다.
  2. 파급은 신고 경로에 국한되지 않는다 — `RESUMABLE_SKIP_REASONS`(`VlmTimeseriesStep:150-152`)의 **나머지 3종**(비동기 제출 실패 `SKIP_REASON_SUBMIT_FAILED`, ACK 미수신 `SKIP_REASON_ACK_MISSING`, 콜백 창 만료 `SKIP_REASON_CALLBACK_MISSING`)도 **같은 `isWithheld` 를 통과해야 재개**되므로, Phase C-1 논블로킹 전환이 도입한 **미결 회수(`VlmSubmitPendingSweeper`) 전체가 함께 무력화**된다. 즉 "ACK 를 못 받았거나 콜백이 안 온 위탁"도 회수되지 않는다.
  3. 결손된 메타는 `LS_DATA_META_REVIEW` 검수큐에도 진입하지 않아 REVIEWER 가 "메타가 없다"는 사실 자체를 화면에서 알 수 없고, 검수 승인 시 export `event` 블록과 `V_COMPLETED_META` 도 비어 나간다.
  4. 자동 회귀 가드는 위양성이라 이 상태가 조용히 유지된다.
- **수정 방향(제안)**: `countByRawSn` 대신 **시계열 메타만 세는 카운트**를 쓴다 — 예: `LsDataMetaRepository` 에 `countByRawSnAndMetaKeyNotStartingWith(rawSn, VideoMetaService.KEY_PREFIX)` 또는 `countTimeseriesByRawSn`(구간키 패턴 `^\d+-\d+$` / `META_KEY NOT LIKE 'video.%'`) 를 신설하고 `VlmWithheldResumeRunner:94` 를 그 쪽으로 바꾼다. 판정 축의 단일 원천은 `VideoMetaService.KEY_PREFIX`(이미 public 상수)를 재사용해 문자열을 재구현하지 않는다. 회귀 가드는 **Mockito 스텁이 아니라 실제 리포지토리를 붙인 IT**(ingest 메타 6건이 있는 상태에서 재개가 발생하는지)로 세운다. ⚠ **구현하지 않는다.**

### [B-ISSUE-42] TC-VLM-010 — `BLOCK_TIMEOUT=45s` 가 코드에서 사라졌다(논블로킹 전환). 케이스·UNCERTAINTIES #13 의 "이중 구조" 전제가 무효

- **심각도**: MEDIUM (문서·기대값 정합 / 판정 기준 오염)
- **기대 동작(기대효과)**: 케이스와 `UNCERTAINTIES.md #13` 은 "`BLOCK_TIMEOUT=45s` 는 재시도 누적 상한, 단일 호출 실효 타임아웃은 `vlm.client.timeout-seconds`(10s) — **둘 다 초과 시 EXTERNAL_API_ERROR**"를 기대한다.
- **현재 동작(이슈 내용)**: Phase C-1(`862ca6d8`)에서 `.block(45s)` 가 **제거**됐다. `VlmTimeseriesStep` 에 `BLOCK_TIMEOUT` 상수도, `block(` 호출도 없다(grep 0건 — 주석 `:70`, `:348` 에 "구 코드는 `.block(45s)`" 라는 서술로만 잔존).
  ```java
  // VlmTimeseriesStep.java:361-377 — subscribe 만 하고 즉시 반환
  vlmClient.submitTimeseries(req)
          .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
          .subscribe(resp -> …onAccepted(…), err -> …onSubmitFailed(…));
  return VlmTimeseriesResponse.submitted(requestId);   // ACK 도 기다리지 않는다
  ```
  현재 유일한 시간 제약은 `VlmClient.java:71`(`vlm.client.timeout-seconds:10`, `application.yml:633` 실값 10) 이 `:108 .timeout(timeout)` 으로 거는 **10초**이고, 그 위에 Retry(max-attempts 3, wait 1s, ×2 backoff)·CircuitBreaker 가 얹힌다(`application.yml:500-507,547-553`). 파이프라인 스레드는 **어느 값에도 블록되지 않는다.**
  4xx 비재시도는 `:106 onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)` → `NonRetryableExternalException` 이 retry/CB `ignore-exceptions` 에 등록돼 정상 배선(케이스 표엔 없으나 확인함).
- **재현/확인 경로**:
  ```bash
  grep -rn "BLOCK_TIMEOUT\|\.block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java   # 0건(주석 제외)
  grep -n "timeout-seconds" backend/src/main/resources/application.yml                                              # 633: timeout-seconds: 10
  ```
- **영향**: 검증자가 "45s 안에 안 끝나면 EXTERNAL_API_ERROR"를 기준으로 판정하면 **존재하지 않는 동작을 근거로 PASS/FAIL 을 매긴다.** 또 운영 관점에서 "느린 벤더가 배치 스레드를 45초 붙잡는다"는 리스크 서술도 더는 사실이 아니다(대신 새 리스크는 "ACK 를 못 받은 위탁의 회수" 쪽으로 옮겨갔고 그 회수가 B-ISSUE-41 로 막혀 있다).
- **수정 방향(제안)**: TC-VLM-010 의 기대결과를 "**단일 실효 타임아웃 10s**(`vlm.client.timeout-seconds`) + Retry 3회/exp backoff + CircuitBreaker. 초과·실패는 호출자에게 전파되지 않고 `VlmSubmitOutcomeRecorder.onSubmitFailed` → `SKIP_REASON_SUBMIT_FAILED` 감사 행으로 기록"으로 재작성하고, `UNCERTAINTIES.md #13` 의 "이중 구조" 문구를 폐기한다. ⚠ **구현하지 않는다.**

### [B-ISSUE-43] TC-VLM-009 — describe 실패·빈 응답이 **호출자에게 전파되지 않아** 배치는 성공으로 마감된다(논블로킹 전환의 관측 가능성 손실)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스 — "describe 응답 null → `EXTERNAL_API_ERROR`".
- **현재 동작(이슈 내용)**: 가드 자체는 살아 있으나 **예외가 비동기 경로로 흡수**된다.
  ```java
  // VlmTimeseriesStep.java:362-377
  .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, "VLM describe 응답이 비어있습니다 rawSn=" + rawSn)))
  .subscribe(resp -> …,
             err  -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                        () -> outcomeRecorder.onSubmitFailed(rawSn, markingSn, err)));
  …
  return VlmTimeseriesResponse.submitted(requestId);
  ```
  `doSubmit` 은 항상 `submitted` 를 돌려주므로 `BatchOrchestrator` 는 VLM 단계를 **성공으로 간주**하고 파이프라인을 계속 진행해 `COMPLETED` 로 마감한다. 실패 사실은 `VlmSubmitOutcomeRecorder.onSubmitFailed` 가 `LS_BATCH_PROC_LOG` 에 `VLM/SKIPPED/SKIP_REASON_SUBMIT_FAILED` 감사 행으로만 남긴다(상태 강등은 의도적으로 금지 — 주석 "★ 상태 강등 금지").
  설계 의도(지각 실패가 완료된 파이프라인을 FAILED 로 역행시키지 않게)는 타당하나, **회수 경로가 B-ISSUE-41 로 막혀 있어** 현재는 "실패했고, 기록만 남았고, 아무도 다시 시도하지 않는다"가 된다.
- **재현/확인 경로**: mock 을 오류 응답으로 만들 수 없어 이번 회차에 실동작 재현은 못 했다(컨테이너 재기동 금지). 정적으로는 위 스니펫 + `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다` / `#외부_호출_실패는_파이프라인_스레드로_전파되지_않고_완료핸들러에_위임된다` 가 이 동작을 **기대값으로 고정**하고 있다.
- **영향**: (a) 케이스 표의 "EXTERNAL_API_ERROR" 기대값이 무효. (b) 외부 VLM 이 계속 실패해도 배치는 전건 `COMPLETED` 로 보이고 시계열 메타만 조용히 비는 상태가 된다 — 운영 알림·대시보드가 `LS_BATCH_PROC_LOG` 의 `SKIPPED` 행을 별도로 보지 않는 한 무증상이다(CWE-778 계열 관측 실패).
- **수정 방향(제안)**: ①TC-VLM-009 기대값을 "`EXTERNAL_API_ERROR` 가 `onSubmitFailed` 로 흡수되어 `SKIP_REASON_SUBMIT_FAILED` 감사 행이 남는다"로 갱신. ②운영 축에서는 `LS_BATCH_PROC_LOG(VLM, SKIPPED)` 잔존 건수를 메트릭/알림으로 노출(파이프라인 상태를 강등하지 않으면서 가시화). ⚠ **구현하지 않는다.**

### [B-ISSUE-44] TC-VLM-011 — 마킹 `PENDING→VLM_REQUESTED` 전이 시점이 "위탁 성공 후"에서 "**제출 전 선커밋**"으로 반전됐다(기대값 무효)

- **심각도**: LOW (동작은 의도된 개선, 문서 정합 문제)
- **기대 동작(기대효과)**: 케이스 — "위탁 **성공 시** 마킹 PENDING→VLM_REQUESTED, `persistMarkingTransition` 후 save".
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep:337` 이 **제출 이전에** 별도 빈을 프록시 경유로 호출해 독립 커밋한다.
  ```java
  markingTxService.persistVlmRequested(marking);   // REQUIRES_NEW, 제출 전 선커밋
  Long markingSn = marking == null ? null : marking.getMarkingSn();
  …
  vlmClient.submitTimeseries(req).subscribe(...);  // 이후에 제출
  ```
  `persistMarkingTransition` 메서드는 존재하지 않고 `VlmMarkingTxService.persistVlmRequested`(REQUIRES_NEW) / `markVlmFailedIfRequested`(제출 확정 실패 시 보상)로 분리됐다. 근거는 **콜백 선행 레이스 폐쇄** — 논블로킹 제출에서 벤더 콜백이 ACK 보다 먼저 도착하면 수신부가 전이 대상을 못 찾고, 그 뒤 스텝이 올려버려 `VLM_REQUESTED` 영구 고착이 된다. 수신부도 `markingsInScope`(`VlmResultService:186-208`)로 `ACTIVE_STATUSES` 를 보게 넓혀 **양단 방어**를 이룬다.
- **재현/확인 경로**: `VlmTimeseriesStepNonBlockingTest#상관키_등록과_마킹_VLM_REQUESTED_전이는_외부_제출보다_먼저_수행된다`.
- **영향**: 케이스 기대값이 현재 코드와 어긋난다. 또 "제출이 확정 실패하면 마킹이 `VLM_REQUESTED` 로 잠깐 올라갔다가 `VLM_FAILED` 로 보상 전이"라는 **중간 상태가 새로 생겼다** — 이 사이에 화면이 마킹을 조회하면 실제로는 위탁되지 않은 건이 "요청됨"으로 보인다(짧은 창).
- **수정 방향(제안)**: TC-VLM-011 을 "제출 **전** 선커밋(REQUIRES_NEW, `VlmMarkingTxService`)" 으로 재작성하고, 실패 보상 전이(`markVlmFailedIfRequested`)를 별도 케이스로 신설. ⚠ **구현하지 않는다.**

### [B-ISSUE-45] TC-VLM-040 / TC-VLM-003 관련 — 재개 경로가 프록시로 부르는 `run()` 은 `readOnly=true` 트랜잭션이라, 그 안에서 보류를 **다시 기록**하려 하면 실패한다 (미재현·정적)

- **심각도**: LOW (현재는 B-ISSUE-41 때문에 도달 자체가 불가 — 41 을 고치면 표면화)
- **기대 동작(기대효과)**: 어느 진입점으로 들어와도 VLM 보류/비활성 사유는 `LS_BATCH_PROC_LOG` 에 남아야 한다(B-ISSUE-24 해소의 전제).
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.run` 은 `@Transactional(REQUIRES_NEW, **readOnly = true**)`(`:236-239`) 다. `execute` 경로에서는 자기호출이라 이 속성이 무시되고 `execute` 의 쓰기 가능 경계를 쓰지만, **`VlmWithheldResumeRunner:69` 는 프록시 경유로 `run` 을 직접 호출**하므로 readOnly 경계가 실제로 개시된다. 그 안에서 게이트가 여전히 닫혀 있거나 `enabled=false` 면
  `:271` / `:305` 의 `batchStatusService.recordVlmSkipped(...)`(REQUIRED, `BatchStatusService:58-62`)가 **readOnly 트랜잭션에 참여**해 `LsBatchProcLog`(`@GeneratedValue(IDENTITY)`, `LsBatchProcLog.java:25-28`) INSERT 를 시도한다. `HibernateJpaDialect` 가 readOnly 를 JDBC 커넥션에 전파하므로 PostgreSQL 은 `cannot execute INSERT in a read-only transaction`(25006)로 거부한다. 그 예외는 `resumeAsync` 의 `catch(RuntimeException)`(`:73-78`)이 삼켜 WARN 한 줄만 남긴다.
  같은 경로의 다른 쓰기(`ledger.recordIssued`, `markingTxService.persistVlmRequested`, `recordVlmSkippedInNewTx`)는 전부 `REQUIRES_NEW` 별도 빈이라 영향이 없다 — **문제는 REQUIRED 인 `recordVlmSkipped` 하나**다.
- **재현/확인 경로**: 이번 회차 **미재현**. 재개가 실제로 발생한 유일한 케이스(B 대조군)는 게이트가 열려 제출 경로로 갔기 때문에 이 분기를 타지 않았다. 재현하려면 "메타 0건 + 보류 기록 有 + resolve 직후 다시 `'F'`" 또는 `vlm.client.enabled=false` 상태에서 재개를 발화시켜야 한다.
- **영향**: B-ISSUE-41 을 고쳐 재개가 살아난 뒤, **재개했는데 여전히 보류해야 하는 경우의 감사 행이 유실**된다(무한 재개 시도 + 무기록). 보안 영향은 없다(외부 전송은 여전히 차단됨).
- **수정 방향(제안)**: `run()` 의 `readOnly=true` 를 제거하거나(주석 `:213-216` 이 "이 경로엔 dirty 엔티티가 없다"고 하나 `recordVlmSkipped` 는 새 엔티티 INSERT 다), `recordVlmSkipped` 호출을 `recordVlmSkippedInNewTx`(이미 존재, `:78-82`)로 통일한다. 후자는 커넥션 점유가 늘어 주석에 적힌 커넥션 기아 우려가 있으므로 **전자(readOnly 제거)가 안전**하다. 회귀 가드는 ambient tx 없는 IT 로 세운다. ⚠ **구현하지 않는다.**

### [B-ISSUE-46] TC-VLM-013 / 019 / 024 / 025 — 1차 B-ISSUE-27 의 테스트 커버 갭이 **그대로 이월**, 게다가 벤더 왕복 계약 IT 는 2차에도 SKIP

- **심각도**: LOW (품질 게이트 신뢰도)
- **기대 동작(기대효과)**: P0/P1 로 분류된 보안·동시성 케이스는 자동 회귀 가드를 보유해야 한다.
- **현재 동작(이슈 내용)**:
  | 케이스 | 우선 | 커버 상태 |
  |---|:--:|---|
  | TC-VLM-013 콜백 URL 고정 base(SSRF) | P1 | 부분 — `VlmTimeseriesStepTest:177` 이 `endsWith("/v1/vlm/callback")` 만 단언. **base 가 사용자 입력에 오염되지 않음**을 단언하는 테스트 없음 |
  | TC-VLM-019 completed + 영상 미존재 → NOT_FOUND | P1 | **없음**(`VlmResultServiceTest` 에 해당 시나리오 부재) |
  | TC-VLM-024 동일 request_id 동시 콜백 직렬화 | **P0** | **없음.** 증강 채널엔 `AugmentCallbackIdempotencyIT`·`GenAiCallbackRollupConcurrencyIT`·`AugmentDeidentConcurrencyIT` 가 있는데 VLM 만 비대칭 |
  | TC-VLM-025 로그 마스킹(CR/LF/tab) | P1 | **없음**(`safe()`/`safeForLog()` 전용 테스트 0건) |

  추가로 `test-baseline.md §5` 의 **backend 스킵 5건이 전부 "라이브 목 서버 실연동 IT — KPST/VLM 클라이언트 왕복"** 이며, 그중 `VLM describe 는 accepted 와 request_id echo 를 반환한다` 가 포함된다. 즉 `VlmClient` 의 벤더 계약 정합(요청 스키마·`accepted` 화이트리스트·request_id echo 검증 `:154-169`)은 **1차·2차 모두 자동 실행 0회**다. 본 검증이 수동으로 왕복을 확인했으나 CI 회귀 가드는 여전히 없다.
- **재현/확인 경로**:
  ```bash
  grep -rn "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/webhook/VlmResultServiceTest.java
  ls backend/src/test/java/kr/co/cudo/authoring/webhook/ | grep -i concurren   # Augment 계열만 존재
  ```
- **영향**: TC-VLM-024 는 P0(동시 콜백 → 검수큐 중복 적재/META race)인데 비관적 락 배선이 깨져도 CI 가 잡지 못한다. 라이브 목서버 IT 스킵은 벤더 계약 드리프트를 무증상으로 만든다.
- **수정 방향(제안)**: ①`VlmCallbackIdempotencyIT`(동일 request_id 2스레드 동시 POST → 1건만 `applied:true`, 검수행 N건 유지)를 증강 IT 패턴 그대로 신설. ②`VlmResultServiceTest` 에 영상 미존재/로그 마스킹 케이스 추가. ③`resolveCallbackUrl` 이 설정 base 만 쓰고 요청값을 반영하지 않음을 단언하는 단위테스트. ④라이브 목서버 IT 를 CI 에서 mock-server 를 띄우고 실행하는 프로파일로 승격(현재는 조건부 SKIP 이라 영구 미실행). ⚠ **구현하지 않는다.**

---

## 부기 — 결함으로 보고하지 않은 관측 사항

| 관측 | 왜 결함이 아닌가 |
|---|---|
| `DeidentReportGate` 가 `DE_IDNTF_YN='F'` 단일 컬럼만 보고 `ORGNL_RAW_SN` 을 보지 않음 | `UNCERTAINTIES.md ★1` 확정 정책(조상/자손 전파는 4라운드 후 철회). 재보고 금지 대상 |
| 128(KPST 실패로 `'F'`)에 "비식별 누락 **신고** 구간" 사유가 찍힘 | `CLAUDE.md` 가 명시한 `'F'` 의 의미 이중성(신고 / 비식별 API 실패). 게이트는 의도적으로 둘을 같게 취급한다 |
| `readableDeidVideoBases` 가 `deidentifiedBase` **전체**를 base 로 포함해 구 위치 축에서는 rawSn 교차 차단이 없음 | TC-BATCH-114 가 요구하는 "구 위치 계속 허용"의 직접 결과. 경로 출처는 해당 rawSn 의 `LS_DEIDENT_PROC_LOG` self-lookup 이라 오염에 DB 쓰기 권한이 필요하고, co-locate 축은 교차 차단 테스트가 존재 |
| 신고 구간 영상의 배치가 FRAME_EXTRACT 에서 `INVALID_INPUT` → FAILED 로 끝남 | TC-BATCH-103 의 기대값 그대로(비식별 선행 가드). "보류" 규약은 **외부 전송(VLM)** 에만 적용된다는 것이 `CLAUDE.md` 규정 |
| dev 환경 `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none` | 운영 형상(stg/prd)에서 벤더 대역을 지정하는 설정 축. A 클러스터 환경 검증 스코프 |

---

# B 클러스터 part4 (B-9·B-10) 2차 검증 결과

> 대상: `docs/test-cases/B-batch-deidentify.md` §B-9(30건) · §B-10(22건) = **52건**
> 검증 시각: 2026-07-31 03:17~03:30 KST · backend `localhost:18081`(HEAD `ca3c712b` 재빌드본) · ai-server `:19300` · mock-server `:9400` · postgres `public` 스키마
> 참조 데이터: `pipeline-drive.md` §3 (rawSn 126 완주 / 129 증강파생 / 130·131 해상도파생 / 132 검수대기 / 133 신고 OPEN / 127·128 비식별 실패)
> ⚠ 두 섹션에 `~~취소선~~` 폐기 행은 **0건** — 52건 전량이 검증 대상이다(집계 제외분 없음).
> ⚠ 검증 중 다른 에이전트가 rawSn 134~144 를 계속 생성했다. 아래 수치·로그는 조회 시점 스냅샷이다.
> ⚠ 컨테이너 재시작·재빌드·빌드/테스트 실행 0건. 소스/설정 수정 0건(본 파일 1개만 신규 작성).

## 집계

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-9 (오토라벨·좌표 정규화) | 30 | 29 | 0 | 1 | 0 | 0 | 0 |
| B-10 (비디오 스트리밍) | 22 | 22 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **52** | **51** | **0** | **1** | **0** | **0** | **0** |

근거 구성: **실동작 판정 24건** · 정적+기존테스트 판정 28건.
신규 이슈 **4건**(PARTIAL 1건 + 정보성 3건). 근거 드리프트 **15건**(전부 라인/경로/기대값 표기 문제, 동작 결함 아님).

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **1차 B-ISSUE-61** — 해상도 파생영상 스트리밍 전면 403 | 파생 비식별본이 raw base 밑에 기록되는데 가드는 deid base 만 허용 | **해소** | [실동작] 파생 3건 전부 206: 증강 `129`·해상도 `130`(720P)·`131`(480P), `Range: bytes=0-99`. DB 실측 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 이 **`/app/storage/deidentified/videos/{augment,resolution}/126/{129,130,131}/*.mp4`** 로 **deid base 하위로 이동**(1차 수정방향 ①정공법 채택). 여기에 2-way allowlist(`VideoArtifactRootResolver.readableDeidVideoBases`)가 더해졌다. ⚠ 1차가 경고한 "raw base 통째 허용" 우회는 **채택되지 않았다** — 허용 co-locate 디렉터리는 `dirname(원본)/{rawSn}/deid` 로 좁혀져 있어 원본(`/app/storage/raw/seed/sample-cctv-1080p.mp4`)·export 원본 프레임(`.../126/v1/orgnl/`)은 범위 밖이다 |
| **1차 B-ISSUE-63** — `/stream` 영상 단위 배정 인가 부재(IDOR) | 역할만 검사, rawSn 소유/배정 검증 없음. 미배정 WORKER(2002) → 206 | **해소 (A-part1 결론과 일치 — 재현 불가 확인)** | ★아래 별도 절 참조 |
| **1차 B-ISSUE-42** — 오토라벨 일괄저장(루프 내 개별 save) | IDENTITY PK 라 `batch_size` 무효 | **부분 해소 유지** | `AutoLabelBatchPersister.saveAll`(프레임 단위 라벨 saveAll 1회 + AI메타 saveAll 1회)이 YOLO(`YoloAutolabelStep.java:328-329`)·SAM2(`Sam2SegmentStep.java:207-208`) 양쪽에 배선됨. 그러나 `LsDataLbl.java:55`·`LsDataLblAiInfo.java:30` 은 **여전히 `GenerationType.IDENTITY`** → JDBC 배치는 구조적으로 비활성. 헬퍼 javadoc 이 이 한계를 명시(`AutoLabelBatchPersister.java:22-29`). 즉 **왕복 감소만 달성, INSERT 묶음은 미달** = 1차 기록 그대로 |

### ★ B-ISSUE-63 교차 확인 결론 — **해소됐다. A-part1 이 재현하지 못한 것이 맞다.**

1차 이슈가 지목한 지점(`VideoController.java:220` 의 역할 전용 `@PreAuthorize`)에 **영상 단위 인가가 추가 배선**됐다.
- `VideoController.java:244` `@PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")` — 역할 게이트는 그대로.
- `VideoController.java:253` **`labelAccessGuard.verifyRawAccess(rawSn, actor);`** ← 신규(주석에 `B-ISSUE-63` 명시). `/stream-url` 도 동일(`:210`), 형제 우회 경로 3곳(`GET /{rawSn}`:135 · `/{rawSn}/labels/auto`:156 · `/{rawSn}/frames/{frameNo}/image`:302)까지 함께 잠갔다(주석 `DEV_FIX H-1`).
- `VideoStreamService.java:140-141,156-157` javadoc 도 "컨트롤러가 `verifyRawAccess` 로 영상 단위 인가를 먼저 강제한다"로 갱신.

실측(전건 curl):

| 케이스 | 토큰 | 대상 | 실측 |
|---|---|---|:--:|
| 미배정 WORKER → 스트리밍 | worker2(2002, `ls_task_assignment` 에 126 없음) | `GET /v1/videos/126/stream` | **403** |
| 미배정 WORKER → 서명 URL | worker2 | `GET /v1/videos/126/stream-url` | **403** |
| 미배정 WORKER → 파생영상 | worker2 | `GET /v1/videos/129/stream` | **403** |
| 배정 WORKER → 스트리밍 | worker1(2001, 126 배정) | `GET /v1/videos/126/stream` (Range) | **206** |
| REVIEWER → 배정 무관 | reviewer1 | 126/129/130/131 | **200/206** |
| PORTAL_USER | portal1 | 126 | **403** |
| 무토큰 | — | 126 | **401** |

**추가 반증(열거 오라클 점검)**: 미배정 WORKER 가 *존재하는* 영상(126)과 *존재하지 않는* 영상(999999)을 요청했을 때 **둘 다 403 + 동일 본문**(`{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`)이다 → 응답 코드로 영상 존재 여부를 관측할 수 없다(CWE-209 회피). 신고 구간 영상(133)도 미배정 WORKER 에게는 403(게이트 이전에 인가가 끝남)이라 신고 상태도 새지 않는다.

회귀 가드: `video/VideoStreamAssignmentAuthorizationTest.java` 17건(미배정 403 / 배정 206 / REVIEWER 무관 / 서명경로 3건 포함).

---

## ★ 스트리밍 게이트 실측

전건 `curl` 실행(2026-07-31 03:17~03:29 KST). `Cache-Control` 은 응답 헤더 원문.

| rawSn | DB 상태 | 역할 | 요청 | 기대 | **실측 코드** | Cache-Control |
|---:|---|---|---|:--:|:--:|---|
| 126 | `Y` / procLog SUCCEEDED / co-locate 경로 | REVIEWER | `/stream` (Range 없음) | 200 | **200** | `no-store` |
| 126 | 〃 | REVIEWER | `/stream` `Range: bytes=0-` | 206 | **206** (`Content-Range: bytes 0-8388607/34654319`) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=0-0` | 206 | **206** (`0-0/34654319`, len 1) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=-100` (suffix) | 206 | **206** (`34654219-34654318/…`) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=999-0` (역전) | 416 | **416** (`Content-Range: bytes */34654319`) | `no-cache, no-store, …` |
| 126 | 〃 | REVIEWER | `Range: bytes=abc` / `byte=0-10` / `bytes=5-2` / `bytes=-0` | 416 | **416** ×4 | 〃 |
| 126 | 〃 | REVIEWER | `Range: bytes=34654319-` (=total) | 416 | **416** | 〃 |
| 126 | 〃 | REVIEWER | `Range: bytes=99999999999-` | 416 | **416** | 〃 |
| 126 | 〃 | REVIEWER | `Range: bytes=0-10, 20-30` (다중) | — | **206** (첫 range 만, `0-10`) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=` (빈 값) | — | **200** 전체 | `no-store` |
| **127** | **`F`**(KPST 실패) / procLog FAILED | REVIEWER | `/stream` | 404 | **404** | — |
| **133** | **`F`**(신고 OPEN) / **procLog SUCCEEDED + 파일 실재** | REVIEWER | `/stream` | 404 | **404** | — |
| 133 | 〃 | REVIEWER | `/stream-url` | 404 | **404** | — |
| 133 | 〃 | WORKER(배정자) | `/stream` | 404 | **404** | — |
| 20031 | `Y` / **procLog 없음**(seed 합성) | REVIEWER | `/stream` | 404 | **404** | — |
| 999999 | 미존재 | REVIEWER | `/stream` · `/stream-url` | 404 | **404** ×2 | — |
| **129** | `Y` / 증강 파생(`ORGNL_RAW_SN=126`) | REVIEWER | `/stream` Range | 206 | **206** | `no-store` |
| **130 / 131** | `Y` / 해상도 파생 720P·480P | REVIEWER | `/stream` Range | 206 | **206** ×2 | `no-store` |

**게이트 발화가 매 요청 일어나는 증거**(backend 로그) — 03:00·03:09·03:17 세 시점 모두 동일 WARN 이 재출력됐다. 즉 `stream-meta` 캐시가 채워져 있어도 게이트를 건너뛰지 않는다:
```
03:00:33 WARN [VideoStream] blocked — deident report open on this video rawSn=133
03:09:54 WARN [VideoStream] deident not valid rawSn=133 — refusing signed url
03:17:36 WARN [VideoStream] blocked — deident report open on this video rawSn=133   (×2)
03:17:36 WARN [VideoStream] blocked — deident report open on this video rawSn=127
03:17:36 WARN [VideoStream] deidentify not completed rawSn=20031 — refusing raw exposure
```
메시지가 갈리는 것도 코드 순서와 정합한다 — `/stream` 은 `requireNotUnderDeidentReport`(`VideoStreamService.java:221`)가 먼저, `/stream-url` 은 `!"Y".equals(deIdntfYn)` 인라인 검사(`:174-177`)가 먼저다.

**원본 유출 반증 (CWE-359)** — 실측 3축 모두 차단 확인:
1. `133` 은 **성공 procLog + 비식별 파일이 실재**(`/app/storage/raw/seed/133/deid/sample-cctv-1080p-mask.mp4`)하는데도 404 → `procLog` 만으로 서빙하지 않고 `DE_IDNTF_YN` 게이트가 앞선다.
2. 허용 base 는 `deidentified-path` ∪ `{deid_base}/videos/{rawSn}` ∪ `dirname(원본)/{rawSn}/deid` 세 갈래뿐(`VideoArtifactRootResolver.java:308-320,332-341`). 실측 co-locate 값 `/app/storage/raw/seed/126/deid/` 는 통과하지만 **같은 디렉터리 상위의 원본 `/app/storage/raw/seed/sample-cctv-1080p.mp4`, export 원본 프레임 `/app/storage/raw/seed/126/v1/orgnl/`** 는 어느 base 에도 속하지 않는다 → `resolveSafe` 404.
3. 스트리밍 경로에는 **원본 폴백 분기가 존재하지 않는다** — `resolveDeidLocation` 이 null 이면 곧바로 NOT_FOUND 이며 `RAW_FILE_PATH_NM` 은 co-locate base **도출**에만 쓰이고 서빙 대상이 되지 않는다(`VideoStreamService.java:409-428`).

---

## B-9 결과표 (YOLO / SAM2 / Interpolate · 좌표 정규화)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-120 | YOLO: rawSn null → INVALID_INPUT | PASS | [정적] `YoloAutolabelStep.java:169-171` + `YoloAutolabelStepTest:415 rawSn_null이면_INVALID_INPUT` | |
| TC-BATCH-121 | YOLO: 프레임별 순차 track(clipId 격리, 0=리셋) | **PARTIAL** | [실동작] 배치 로그 `[Batch][Yolo] saved labels rawSn=132 clipId=132 …` / `rawSn=136 clipId=136` — clipId=rawSn 확인. [정적] `:185`(clipId) `:200`(frameIndex 0-base) `:208-211`(요청 적재) `:330`(frameIndex++), `findByRawSnOrderByFrameNoAsc` 순서 보장 + `YoloAutolabelStepTest:750` | ★요청 계약은 충족하나 **track 호출의 산출(track_id)이 실환경에서 전량 null** — DB `LS_DATA_LBL.TRCK_ID` non-null **1/298**. → **B-ISSUE-61**, 부수로 **B-ISSUE-62** |
| TC-BATCH-122 | YOLO: ai-server 호출 실패 → EXTERNAL_API_ERROR | PASS | [정적] `:212-216` + `YoloAutolabelStepTest:424` | |
| TC-BATCH-123 | YOLO: mock 응답 감지 WARN(CRLF 살균) | PASS | [정적] `:221-233` (`LogSanitizer.sanitize(resp.source()/mockReason())`) + 테스트 `:493`(WARN 출력·파이프라인 계속) `:521`(정상 응답 시 WARN 없음) | 실환경은 ai-server `AI_MOCK_MODE=false` 라 mock 응답 미발생 |
| TC-BATCH-124 | YOLO: DTCT_TYPE_CD 축 매핑 | PASS | [실동작] `LS_DATA_LBL` 실측 — YOLO 저장분 `person→LBL_ID=1`, `car→2`, `truck→6` 로 마스터 PK 귀속(미매칭 없음). [정적] `:277` `findLabelIdByDtctType(...).orElse(null)` + 테스트 `:862/:880/:1005/:1029` | |
| TC-BATCH-125 | YOLO: 프리셋 토글 필터(미매핑 fail-safe) | PASS | [실동작] 로그 `preset=(none)` 인데 검출 전량 저장(`yoloCount=18 bboxSaved=18`) = BOTH fail-safe. [정적] `:400-410` + 테스트 `:545/:840` | |
| TC-BATCH-126 | YOLO: 이미지 경로 순회 방어(CWE-22) | PASS | [정적] `:427-432` — `baseRawPath.resolve(rel).normalize()` 후 `startsWith(baseRawPath)` 위반 시 `INVALID_INPUT`(경로 원문 미노출). SAM2 도 동일(`Sam2SegmentStep.java:314-319`) | ⚠ 전용 회귀 테스트 **부재**(`batch/step/` 내 traversal 테스트는 Deident/Ffmpeg 쪽만) + lexical 검증이라 심링크 미고려 → **B-ISSUE-64**(정보) |
| TC-BATCH-127 | YOLO: conf/imgsz/iou 설정 fail-safe | PASS | [실동작] 로그 `conf=0.25 imgsz=1280 iou=0.5` — conf 가 코드 기본값 0.4 가 아닌 **SystemConfig 실값 0.25** 로 요청에 반영됨. [정적] `:179-181,358-386` + 테스트 `:658`(전달) `:682`(미설정 기본값) `:703`(조회 실패 폴백) | `imgsz` 는 ai-server 에서 무효(640 고정, UNCERTAINTIES #14 미해소) — BE 전달 자체는 확인됨 |
| TC-BATCH-128 | SAM2: (srcSn,label,trackId) dedup DB BBOX 우선 | PASS | [정적] `Sam2SegmentStep.java:241-257` — `LinkedHashMap` 에 DB BBOX 선등록 후 hint 는 `putIfAbsent` + 테스트 `:330/:409/:436/:461` | 실환경은 trackId 전량 null 이라 label 단위 dedup 으로 fallback(B-ISSUE-61) |
| TC-BATCH-129 | SAM2: polygon=false 라벨 skip | PASS | [정적] `:182-188`(WARN skip) + 테스트 `:273` | |
| TC-BATCH-130 | SAM2: 응답 폴리곤 상한 초과 → 단순화 | PASS | [실동작] `LS_DATA_LBL` POLYGON 5건 정상 저장(126: lblSn 129~133). [정적] `:196` + `capPolygon :365-383`(`PolygonSimplifier.simplifyToMax(pts,1.0,MAX_POINTS_PER_LABEL)`) + 테스트 `:243`(4192점→1000 이하) | |
| TC-BATCH-131 | SAM2: 호출 실패 → EXTERNAL_API_ERROR | PASS | [정적] `:226-234` | |
| TC-BATCH-132 | Interpolate: 프레임/후보 없음 → 0 | PASS | [실동작] 배치 로그 `[Batch][Interpolation] no interpolation candidates rawSn=126/132/133/135/136/137/138/143` — 예외 없이 0 반환. [정적] `:142-146`(frames empty) `:176-180`(candidates empty) + 테스트 `:113/:125` | ★실환경에서 **이 분기만** 타고 있다(B-ISSUE-61) |
| TC-BATCH-133 | Interpolate: 재실행 멱등 stale 선삭제 | PASS | [정적] `TrackInterpolationStep.java:157-172` — `findInterpolatedLblSnsByRawSn` → 프레임 bump(락 선점) → `aiInfoRepository.deleteByDataLblSnIn` → `lblRepository.deleteAllByIdInBatch` (자식→부모) + 테스트 `:476` | 실환경 미도달(보간 후보 0건, B-ISSUE-61) |
| TC-BATCH-134 | Interpolate: 트랙 부분실패 격리 | PASS | [정적] `:192-197` 트랙 단위 try/catch + WARN(무시 아님) + 테스트 `:450` | 동상 |
| TC-BATCH-135 | Interpolate: 혼재 타입 트랙 skip | PASS | [정적] `:353` `distinct().size()>1` → WARN skip + 테스트 `:433` + 단일트랙 IT `:251` | 동상 |
| TC-BATCH-136 | Interpolate: BBOX flat/nested 양포맷 | PASS | [정적] `:441 parseBbox`(`LabelPointSerializer.fromJson` 3변종 흡수) + 테스트 `:494` | |
| TC-BATCH-137 | Interpolate 단건: from+to stale 삭제 | PASS | [정적] `:259-328 interpolateSingleTrackTouched` — stale 대상이 `List.of(fromTrackId, toTrackId)` 양쪽(`:276-277`), `@Transactional` 미부착으로 caller tx 참여 + **예외 미포획(전파)**(`:311-313` 주석 명시) + IT `TrackInterpolationSingleTrackIntegrationTest:229/:268/:300` | 근거 드리프트: 메서드명·라인(아래 절) |
| TC-BATCH-138 | MarkingLoadStep: 마킹 로드+최신 markCn 파싱 | PASS | [실동작] 배치 로그 `[BatchOrchestrator] marking check rawSn=… count=…` 후 프레임 추출 정상. [정적] `batch/pipeline/MarkingLoadStep.java:52-62` + 테스트 `MarkingLoadStepTest:48/:66` | 근거 경로 드리프트 |
| TC-BATCH-139 | MarkingLoadStep: markCn 파싱 실패 → INTERNAL_ERROR | PASS | [정적] `:64-70` + 테스트 `:78` | |
| TC-BATCH-140 | 파이프라인 순서 검증 | PASS | [정적] `BatchPipelineConfig.java:32-43` `List.of(markingLoad, vlm, frame, yolo, sam2, interp)` + pre-marking `List.of(deid)` + 테스트 `BatchPipelineConfigTest:29/:59` | |
| TC-BATCH-141 | ★좌표 정규화 단일 규칙 — clamp | PASS | **[실동작] 결정적 증거** — `LS_DATA_LBL` lblSn=123(src 67, car, YOLO) `POINT_CN` 이 `[[0.0,683.089…],[396.114…,1017.736…]]` 로 **x1 이 정확히 0.0**(모델 음수 출력이 하한 clamp 됨). 온라인 경로도 동일 유틸 경유(`AutolabelOnlineService.java:256 normalizeDetections`). [정적] `DetectionBoxNormalizer.java:50-69` + `DetectionBoxNormalizerTest` 11건 | ★3 정책 그대로: AI 응답=clamp / 사용자 저장=400 거부 (통일 제안 없음) |
| TC-BATCH-142 | 유한성 가드가 clamp **이전** | PASS | [정적] `DetectionBoxNormalizer.java:54-58` — 개수 검사(`:51-53`) 직후, clamp(`:59-64`) **이전** 에 `!Double.isFinite(v)` → `IllegalArgumentException` + 테스트 `:115 NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지` `:130` | |
| TC-BATCH-143 | 퇴화 박스는 예외가 아니라 스킵 | PASS | [정적] `DetectionBoxNormalizer.java:65-67` `x2<=x1 \|\| y2<=y1 → Optional.empty()`, 호출부 `YoloAutolabelStep.java:263-268` `droppedDegenerate++ → continue` + 테스트 `:89/:99`(유틸) `:259`(배치 나머지 저장) | 실측 로그 `droppedDegenerate=0`(실모델이 퇴화 미출력) |
| TC-BATCH-144 | 형식 위반도 검출 단위 드롭 | PASS | [정적] `YoloAutolabelStep.java:259-275` — `IllegalArgumentException` 을 **검출 루프 안에서** catch → `droppedMalformed++ → continue`(영상 전체 실패 아님). 온라인 all-or-nothing 400 은 별도 유지(`:257-258` 주석) + 테스트 `:281/:309` | 실측 로그 `droppedMalformed=0` |
| TC-BATCH-145 | SAM box 프롬프트가 clamp 좌표를 공유 | PASS | [정적] `:316-325` — `hints.add(new BbHint(..., points, ...))` 의 `points` 가 `:259-269` 에서 정규화된 값(원본 `d.points()` 아님) + 테스트 `:356 폴리곤전용_프리셋의_hint좌표는_이미지_경계로_clamp된_값이다` | |
| TC-BATCH-146 | 퇴화 시 bbox·polygon 동시 스킵 | PASS | [정적] `:259-275` 의 `continue` 가 `toggle.bbox()`(`:280`)·`toggle.polygon()`(`:316`) **양쪽 앞**에 위치 + 테스트 `:332/:379` | |
| TC-BATCH-147 | 해상도 측정 불가 시 상한 생략(fail-open) | PASS | [정적] `DetectionBoxNormalizer.java:72-77 upperBound` — bounds null/길이≠2/0이하 → `Double.MAX_VALUE`(상한 없음), 하한 0 만 적용. 호출부 `YoloAutolabelStep.java:236-237` `frameBoundsResolver.resolve(src).orElse(null)` + 테스트 `:77/:138` | `FrameBoundsResolver.java:76-93` 도 실패를 캐시하지 않아 복구 시 되살아남 |
| TC-BATCH-148 | 검출 0건 프레임은 해상도 해석 자체를 안 함 | PASS | [정적] `YoloAutolabelStep.java:236-237` `resp.detections().isEmpty() ? null : frameBoundsResolver.resolve(...)` — 삼항 단축평가로 resolver 미호출 | 전용 단위 테스트 부재(`YoloAutolabelStepTest` 는 resolver 를 항상 stub) |
| TC-BATCH-149 | 라벨셋 버전 bump 범위 = 라벨이 실제 생성된 프레임만 | PASS | **[실동작] 결정적 증거** — rawSn 136(12프레임): 라벨 0건인 `src 93(frm 0)`·`src 96(frm 3)` 의 `LBL_VER=0`, 라벨 보유 10프레임은 `LBL_VER=2`(YOLO+SAM2 각 1회). rawSn 137(`yoloCount=0`)은 유일 프레임 `LBL_VER=0`. [정적] `:189-190,343-345 bumpLabelVersionIn(labeledFrames)` + `LsDataSrcRepository.java:292-295` | 구 `bumpLabelVersionByRawSn` 는 잔존하나 이 경로에서 미사용 |

---

## B-10 결과표 (비디오 스트리밍 — Range · 비식별본만 서빙)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-STREAM-B01 | 비식별 미완료 → NOT_FOUND(원본 차단) | PASS | [실동작] `20031`(`Y` 이나 성공 procLog 없음) → **404** + 로그 `deidentify not completed rawSn=20031 — refusing raw exposure`. `127`(`F`/FAILED) → **404**. [정적] `VideoStreamService.java:226-230` | |
| TC-STREAM-B02 | 영상 미존재 → NOT_FOUND | PASS | [실동작] `999999` `/stream`·`/stream-url` 모두 **404**. [정적] `:411-412` + 테스트 `VideoStreamServiceTest:164` | |
| TC-STREAM-B03 | deIdntfYn='F'/'N' → null(신고본/미수행 거부) | PASS | [실동작] `133` — **성공 procLog + 비식별 파일 실재**인데 **404**(`DE_IDNTF_YN='F'`). `127`(F) 404. [정적] `:414-419` + 테스트 `:534/:617` | procLog 우선 서빙이 아님이 실증됨 |
| TC-STREAM-B04 | 비식별 경로가 허용 base 전부의 밖 → FORBIDDEN(CWE-22) | PASS | [정적] `:357`(2-way allowlist 주입) `:451-461`(base 후보) `:470-485 resolveSafe` — 어느 base 에도 없으면 거부 + 테스트 `:180/:752 S6_구위치도_신위치도_아닌_제3의_경로는_차단된다` | ★**기대값 드리프트** — 현 코드는 FORBIDDEN 이 아니라 **NOT_FOUND 로 정규화**(`:466-468` "S7 … 구 FORBIDDEN"). 존재/권한 구분 노출 회피(CWE-209)를 위한 **의도된 강화**라 차단 자체는 성립 → PASS |
| TC-STREAM-B05 | 비식별 파일 부재 → NOT_FOUND(캐시 안 됨) | PASS | [정적] `:360-363` `Files.exists/isRegularFile` 위반 → 예외(= `@Cacheable` 미저장) + 테스트 `:146 비식별_파일이_물리적으로_없으면_NOT_FOUND` | 실환경에 "procLog SUCCEEDED + 파일 부재" 케이스가 없어 live 재현 불가(mock/KPST 산출물 전건 실재 확인) |
| TC-STREAM-B06 | Range 없음 → 200 전체+Accept-Ranges | PASS | [실동작] `200` + `Accept-Ranges: bytes` + `Content-Length: 34654319`(전체) | 200 응답에 `Content-Range` 도 함께 실린다 → **B-ISSUE-63**(정보, RFC 7233 비정합) |
| TC-STREAM-B07 | Range 유효 → 206 Partial+청크 상한 | PASS | [실동작] `bytes=0-` → **206**, `Content-Range: bytes 0-8388607/34654319`, `Content-Length: 8388608` = `min(start+chunk-1, rangeEnd)` 적용. `bytes=0-0`→`0-0`(1B), `bytes=-100`→ 말미 100B. [정적] `:247-272` | |
| TC-STREAM-B08 | Range 문법 오류 → 416 | PASS | [실동작] `bytes=999-0`·`bytes=abc`·`byte=0-10`·`bytes=5-2`·`bytes=-0` **전부 416** + `Content-Range: bytes */34654319`. [정적] `:239-245,433-439` | `bytes=`(빈 값)는 Spring `getRange()` 가 빈 목록을 돌려줘 **200 전체**(Range 무시) — RFC 상 허용 동작 |
| TC-STREAM-B09 | Range start≥total → 416 | PASS | [실동작] `bytes=34654319-`(=total)·`bytes=99999999999-` → **416** + 로그 `range out of bounds`. [정적] `:251-256` | |
| TC-STREAM-B10 | 청크 상한: 미설정/<1MB → 8MB | PASS | [실동작] env 에 `authoring.storage.stream-chunk-size` 미설정 → 실응답 청크 **8,388,608B**. [정적] `:314-319` + 테스트 `:347`(0 이하 폴백) `:371`(음수 폴백) | |
| TC-STREAM-B11 | 청크 상한: >64MB → 64MB(오버플로 방지) | PASS | [정적] `:64 MAX_CHUNK_SIZE=67_108_864` + `:318 Math.min(...)` + 테스트 `:395`(클램프) `:408 비정상_대형_chunkSize에도_long오버플로_없이_안전서빙` | 실환경 설정이 기본값이라 live 불가 |
| TC-STREAM-B12 | 서명 URL: 비식별 무효 → NOT_FOUND | PASS | [실동작] `133`(신고 F) `/stream-url` → **404** + 로그 `deident not valid rawSn=133 — refusing signed url`. `999999` → 404. [정적] `:174-177`(인라인) + `:189`(게이트 이중) | |
| TC-STREAM-B13 | 서명 URL: 시크릿 미설정 → 503 | PASS | [정적] `:191-197` `!streamUrlSigner.isConfigured() → SERVICE_UNAVAILABLE`(fail-closed, 내부정보 미노출) + `StreamUrlSigner.java:69-84`(미설정 시 `configured=false`, 32B 미만이면 **부팅 차단**) + 테스트 `:631` | 실환경 `STREAM_SIGN_SECRET` 설정(64자) — live 불가 |
| TC-STREAM-B14 | 서명 URL: userNo 바인딩(재사용 차단) | PASS | [실동작] 발급 URL `?exp=…&u=1001&sig=…` + `Set-Cookie: klid_stream_nonce=…; Path=/api/v1/videos; HttpOnly; SameSite=Lax`. 변조 실측 — **쿠키 있음+원본=200 / 쿠키 없음=401 / sig 1자 변조=401 / sig 삭제=401 / u→2002=401 / exp 미래로 위조=401 / exp 과거=401 / rawSn 126→132=401**. [정적] `:202-206` + `StreamUrlSigner.sign/verify`(canonical `{rawSn}.{exp}.{userNo}.{nonce}`, `MessageDigest.isEqual` 상수시간) + `StreamSignedUrlControllerTest` 19건 | sig 대문자화·URL 퍼센트 인코딩은 200 이나 **같은 값의 다른 표기**일 뿐 우회가 아님(`verify` 가 `toLowerCase()` 정규화, 서버 디코딩) |
| TC-STREAM-B15 | 스트림 인가: STREAM_SIGNED 또는 REVIEWER/WORKER | PASS | [실동작] 위 "B-ISSUE-63 교차 확인" 표 전건. [정적] `VideoController.java:244`(역할·서명) + **`:253 labelAccessGuard.verifyRawAccess`** + 테스트 `VideoStreamAssignmentAuthorizationTest` 17건 | ★**비고 드리프트** — 케이스표의 "⚠ 영상 단위 배정 검증은 없다(B-ISSUE-63 미해소 — 현재 동작 고정)"는 **폐기된 서술**이다. 현재는 배정 검증이 있고 미배정 WORKER 는 403 |
| TC-STREAM-B16 | 스트림 메타 캐시: null 미캐싱 | PASS | [정적] `:348 @Cacheable(cacheNames="stream-meta", key="#rawSn", unless="#result == null")` — 비식별 미완료(null)는 캐시되지 않아 stale 404 고정 없음. 예외(FORBIDDEN/NOT_FOUND)도 캐시 대상 아님. 비식별 완료 시 `DeidentifyStep.java:363 evictAfterCommit` 이 추가 무효화 | [실동작 간접] `20031` 반복 요청이 매번 `resolveStreamMeta` 에 도달(로그 `deidentify not completed` 재출력) |
| TC-STREAM-B17 | 스트림 메타 캐시 무효화: 신고('F') 후 즉시 | PASS | [정적] `label/service/DeidentReportService.java:244-248 streamMetaCacheEvictor.evictAfterCommit(rawSn)`(신고) `:395`(resolve) `:454` + `common/cache/StreamMetaCacheEvictor.java:73-92`(`afterCommit` 동기화, 롤백 시 미실행) + IT `DeidentReportStreamGateIT:226` | 대상은 **그 영상 하나** — 주석 `:246-247` 이 파생 캐시 미변경을 확정 정책으로 명시(★1 정합) |
| TC-STREAM-B18 | ★게이트 뒤 미디어 응답은 `Cache-Control: no-store` | PASS | **[실동작]** 200(Range 없음)·206(`bytes=0-`, `bytes=0-0`, `bytes=-100`, 다중 range) **전 응답 헤더가 `Cache-Control: no-store`**. [정적] `:271,281`(양 경로 `.cacheControl(noStoreForGatedMedia())`) `:302-304` + 테스트 `:222/:246` | `max-age` 잔존 0건 — 재생 중 신고 접수 시 클라이언트 캐시 우회(CWE-359/525) 경로 없음 |
| TC-STREAM-B19 | 신고 게이트가 캐시 **앞**(매 요청)에서 평가 | PASS | **[실동작]** 게이트 WARN 이 03:00·03:09·03:17(×2) 매 요청 재출력 = 캐시 히트가 게이트를 건너뛰지 않음. [정적] `:221 requireNotUnderDeidentReport(rawSn)` 이 `:226 resolveStreamMetaCached(rawSn)` **앞**. [테스트] `DeidentReportStreamGateIT:226 캐시가_먼저_채워진_뒤_신고해도_스트리밍이_차단된다` | |
| TC-STREAM-B20 | 비식별 base 2-way allowlist | PASS | **[실동작] 결정적 증거** — rawSn 126 의 `DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/126/deid/sample-cctv-1080p-mask.mp4` 는 `STORAGE_DEIDENTIFIED_PATH=/app/storage/deidentified` **밖**(co-locate)인데 **200/206 정상 서빙**. 동시에 파생 129~131 은 deid base 하위 경로로 서빙 → 구·신 두 위치 모두 통과. [정적] `:357,451-461` → `VideoArtifactRootResolver.java:332-341 readableDeidVideoBases`(프레임 추출기와 **동일 판정 축**) | 허용 폭 확인: co-locate 는 `dirname(원본)/{rawSn}/deid` **한 디렉터리**로 한정 — raw base 통째 허용 아님 |
| TC-STREAM-B21 | ★신고 구간 스트리밍만 404(412 아님) | PASS | [실동작] `133` `/stream`·`/stream-url` **404**(본 검증) ↔ 같은 영상 `GET /v1/frames/78/labels` **412**(pipeline-drive §1-20 실측). [정적] `:134-138` javadoc 이 CWE-209 오라클 회피를 명시, `:146` `ErrorCode.NOT_FOUND` | 확정 정책 — 비대칭을 결함으로 보고하지 않음 |
| TC-STREAM-B22 | ★파생영상은 자기 rawSn 게이트만 판정 | PASS | [정적] `DeidentReportGate.java:66-71` — `findDeIdntfYnByRawSn(rawSn)` 단일 컬럼, `ORGNL_RAW_SN` 미조회(javadoc `:23-37` 이 조상/자손 전파 폐기를 명시) + 테스트 `DeidentReportGateTest:88` · `VideoStreamServiceTest:558/:583` · `DeidentReportStreamGateIT:194`. [실동작 부분] 파생 129/130/131 → 206 | "부모가 신고 중"인 조합은 실데이터로 조성하지 않았다 — 133(신고 OPEN)에 파생이 없고, 파생 생성 API 는 APPROVED/신고 조건상 133 에 적용 불가하며, 126 에 신고를 걸면 다른 에이전트의 참조 데이터를 훼손하므로 **의도적으로 미수행** |

---

## 근거 드리프트

동작 결함이 아니라 **케이스표의 표기(라인/경로/메서드명/기대값)가 현행 코드와 어긋난** 건이다.

| ID | 표기된 근거·기대 | 실제 | 성격 |
|---|---|---|---|
| TC-STREAM-B04 | 기대결과 **FORBIDDEN** | `VideoStreamService.java:466-485` — **NOT_FOUND 로 정규화**("S7 … 구 FORBIDDEN") | ★기대값 드리프트(의도된 보안 강화) |
| TC-STREAM-B15 | 비고 "⚠ 영상 단위 배정 검증은 없다(B-ISSUE-63 미해소 — 현재 동작 고정)" / 근거 `VideoController.java:@PreAuthorize(stream)` | `VideoController.java:244`(@PreAuthorize) + **`:253 labelAccessGuard.verifyRawAccess`** — 배정 검증 존재 | ★비고 무효(해소 반영 필요) |
| TC-STREAM-B17 | `DeidentReportService.java:244-250` | `label/service/DeidentReportService.java:244-248`(패키지 경로 미기재) | 라인·경로 |
| TC-BATCH-137 | `TrackInterpolationStep.java:247-330`, 메서드 `interpolateSingleTrack` | `:259-328`, 메서드 **`interpolateSingleTrackTouched`**(`TouchedFrames` 반환) | 메서드명·라인 |
| TC-BATCH-138 | `MarkingLoadStep.java:50-58` | `batch/pipeline/MarkingLoadStep.java:52-62` | 라인·경로 |
| TC-BATCH-139 | `MarkingLoadStep.java:60-65` | `batch/pipeline/MarkingLoadStep.java:64-70` | 라인·경로 |
| TC-BATCH-140 | `BatchPipelineConfig.java:33-41` | `:32-43` | 라인 |
| TC-BATCH-121 | `:185,201-211` | `:185`(clipId) `:200`(frameIndex 초기화) `:208-211`(요청) `:330`(증가) | 라인 |
| TC-BATCH-125 | `:240-243,400` | `:240-244`, `:400-410` | 라인 |
| TC-BATCH-126 | `:427` | `:427-432` | 라인 |
| TC-BATCH-130 | `:196,365-380` | `:196`, `:365-383` | 라인 |
| TC-BATCH-131 | `:228-232` | `:226-234` | 라인 |
| TC-BATCH-132 | `:131-157` | `:142-146`(frames) + `:176-180`(candidates) | 라인 |
| TC-BATCH-133 | `:168` | `:157-172`(bump→AI_INFO→LBL 삭제 블록 전체) | 라인 |
| TC-BATCH-134 | `:190` | `:192-197` | 라인 |

### 참고 관측 (판정에 영향 없음)

- **온라인 오토라벨 응답의 `savedCount`** — `POST /v1/frames/66/autolabel` 응답이 `{"detectedCount":6,"savedCount":6}` 인데 **DB 는 전혀 변하지 않는다**(실측: `ls_data_lbl` 298→298, `ls_data_lbl_ai_info` 168→168, 모든 항목 `lblSn:null`). 필드명이 "저장됐다"로 읽혀 2경로 분리(온라인=미저장) 검증자를 오도할 수 있다. 실동작은 정상.
- **다중 Range** `bytes=0-10, 20-30` → `multipart/byteranges` 가 아니라 **첫 range 만 단일 206**. 널리 쓰이는 단순화이며 케이스 대상 아님.

---

## 이슈 상세

### [B-ISSUE-61] TC-BATCH-121 — 배치 오토라벨의 `TRCK_ID` 가 실환경에서 전량 NULL → 트랙 보간 단계가 항상 0건 산출

- **심각도**: MEDIUM (기능 미달 · 테스트 GREEN 뒤에 가려진 런타임 무산출)
- **기대 동작(기대효과)**: `predictYoloTrack` 은 `clipId`(영상 격리)·`frameIndex`(0=리셋)로 트래커 상태를 유지해 **같은 객체에 동일 `track_id`** 를 부여하고, 그 값이 `LS_DATA_LBL.TRCK_ID` 에 적재되어야 한다. 그래야 `TrackInterpolationStep`(`findAutoBboxWithTrackId` → trackId 그룹핑)이 키프레임 사이를 보간하고, SAM2 dedup 축(`(srcSn,label,trackId)`)·트랙 편집/병합 UI 가 성립한다(CVAT 트랙 보간 포팅 · SFR-08-01).
- **현재 동작(이슈 내용)**:
  - DB 실측 — `select count(*) from ls_data_lbl where trck_id is not null` = **1 / 298**(그 1건도 srcSn=63 의 레거시 `0`). 파이프라인이 완주한 rawSn 126·132·133·135·136·137·138·143 의 YOLO 저장분은 **전부 NULL**.
  - 그 결과 보간 단계가 매번 후보 0건으로 끝난다 — backend 로그:
    ```
    [Batch][Interpolation] no interpolation candidates rawSn=126
    [Batch][Interpolation] no interpolation candidates rawSn=132 / 133 / 135 / 136 / 137 / 138 / 143
    ```
    `select count(*) from ls_data_lbl_ai_info where lbl_src_cd='INTERPOLATE'` = **0**(YOLO 108 · SAM2 60).
  - BE 측 배선은 정상이다 — `YoloLabelPersister.java:82-84` 가 `trackId` 를 문자열로 변환해 `LsDataLbl.createAutoBbox(..., trackIdStr)` 로 넘긴다. 즉 **ai-server 가 `track_id: null` 을 돌려준다.**
  - ai-server 로그가 원인 축을 보여준다 — `app/models/bytetrack_util.py`:
    ```
    WARNING:app.models.bytetrack_util:[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5 — 누락분 track_id=None 유지
    ```
    `_apply_bytetrack` 은 `det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None`(`bytetrack_util.py:88-90`) 로 **-1(미확정 트랙)을 None 으로 정규화**한다. 프레임 추출은 마킹 간격 기반(`intervalFrames=300` ≈ 10초)이라 인접 추출 프레임 사이에 IoU 연관이 성립하지 않고, ByteTrack 이 트랙을 "확정" 상태로 올리지 못해 매 프레임이 미확정 → None 이 된다.
  - 단위 테스트는 통과한다 — `YoloAutolabelStepTest:727 YoloStep_predictYoloTrack_을_호출하고_trackId_를_LsDataLbl_에_저장`, `TrackInterpolationStepTest` 19건이 모두 **trackId 가 부여된 픽스처**를 전제로 하기 때문이다. 실파이프라인에서 전제가 성립하지 않는다는 사실은 어떤 테스트도 관측하지 않는다(memory `tests-green-runtime-broken-selfcall-tx` 와 동형 패턴).
- **재현/확인 경로**:
  ```sql
  select count(*) from ls_data_lbl where trck_id is not null;                       -- 1
  select lbl_src_cd, count(*) from ls_data_lbl_ai_info group by 1;                  -- YOLO/SAM2 만, INTERPOLATE 0
  ```
  ```bash
  docker logs klid-backend --since 60m 2>&1 | grep "\[Batch\]\[Interpolation\]"     # 전건 "no interpolation candidates"
  docker logs klid-ai-server --since 30m 2>&1 | grep ByteTrack                      # tracker_id 길이 불일치
  ```
- **영향**: ①트랙 보간(TC-BATCH-132~137)·트랙 편집/병합/분할(`TrackEditService`·`TrackMergeService`)이 실데이터에서 대상 0건으로 무의미 ②SAM2 dedup 이 `(label, trackId=null)` = 라벨 단위로 축소되어 같은 프레임의 서로 다른 동종 객체가 1건으로 합쳐짐(rawSn 126 실측: YOLO car 3건 → SAM2 car 폴리곤 1건) ③학습데이터셋에 객체 연속성 정보가 결손. 보안 영향 없음.
- **수정 방향(제안)**: (a) 프레임 추출 간격과 트래킹 전제를 정합시킨다 — 추적이 목적이면 마킹 구간 내 **연속 프레임**을 별도로 뽑아 트래커에 먹이거나, (b) ai-server 가 미확정 트랙에도 잠정 ID 를 부여(`min_hits=1` 상당)하도록 `ByteTrackTracker` 파라미터를 노출하거나, (c) 현재 샘플링 정책에서는 트랙 축이 성립하지 않음을 인정하고 보간 단계를 `isEnabled` 조건부로 낮춘다. ⚠ **구현하지 않는다.**

### [B-ISSUE-62] TC-BATCH-121 — ai-server ByteTrack 이 필터된 `tracker_id` 를 위치(zip)로 매칭해 `track_id` 오귀속 가능

- **심각도**: MEDIUM (잠재적 데이터 오염 — 현재는 B-ISSUE-61 때문에 표면화되지 않음)
- **기대 동작(기대효과)**: 트래커가 돌려준 `tracker_id` 는 **그 id 가 부여된 검출** 에 붙어야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:84-90`
  ```python
  tracked = tracker.update(sv_dets)
  tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
  if len(tracker_ids) != len(dets):
      logger.warning("[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d — 누락분 track_id=None 유지", ...)
  for det, tid in zip(dets, tracker_ids):  # 짧은 쪽 길이만큼만 매핑
  ```
  `tracker.update()` 는 **연관에 성공한 검출만 남긴 `sv.Detections`** 를 반환할 수 있는데(로그가 실제로 `dets=6 / tracker_ids=5`, `dets=3 / tracker_ids=2`, `dets=8 / tracker_ids=7` 로 매 호출 불일치를 보고한다), 코드는 원본 `dets` 와 **위치 순서로 zip** 한다. 필터링으로 인덱스가 밀리면 **5번째 트랙 id 가 원본 6개 중 앞 5개에 순서대로 붙어** 다른 객체에 귀속된다. 경고는 "누락분은 None 유지"라고만 말하고 **정렬 대응이 보장되지 않는다는 사실은 다루지 않는다**.
- **재현/확인 경로**: `docker logs klid-ai-server 2>&1 | grep "tracker_id 길이 불일치"` — 관측된 전 호출에서 발생. 단정하려면 `tracked.xyxy` 와 `dets[i].points` 를 대조해야 하는데 현재 코드가 그 대조를 하지 않는다.
- **영향**: `track_id` 가 실제로 부여되기 시작하면(B-ISSUE-61 해소 시) 보간이 **서로 다른 객체를 한 트랙으로 잇는** 오보간 산출물을 만들 수 있다(CWE 해당 없음 — 데이터 정확성).
- **수정 방향(제안)**: `tracker.update()` 결과를 위치가 아니라 **좌표(xyxy) 동치 또는 인덱스 맵**으로 원본 검출에 되매핑하고, 되매핑에 실패한 검출만 `None` 으로 남긴다. ⚠ **구현하지 않는다.**

### [B-ISSUE-63] TC-STREAM-B06 — Range 없는 200 응답에도 `Content-Range` 헤더가 부여됨 (RFC 7233 비정합, 정보)

- **심각도**: LOW (정보 — 케이스 기대결과는 충족)
- **기대 동작(기대효과)**: `Content-Range` 는 206(또는 416) 응답의 헤더다. 200 전체 응답에는 의미가 없다(RFC 7233 §4.2).
- **현재 동작(이슈 내용)**: [실동작]
  ```
  GET /api/v1/videos/126/stream   (Range 헤더 없음)
  HTTP/1.1 200
  Accept-Ranges: bytes
  Cache-Control: no-store
  Content-Range: bytes 0-34654318/34654319     ← 200 인데 부여됨
  Content-Length: 34654319
  ```
  원인은 200 경로도 `ResourceRegion` 을 반환하기 때문이다(`VideoStreamService.java:276-282`) — `ResourceRegionHttpMessageConverter` 가 write 시점에 `Content-Range` 를 직접 add 한다(`:262-265` 주석이 206 경로에서 이 동작을 이미 설명하고 있다).
- **재현/확인 경로**: `curl -s -D - -o /dev/null -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/126/stream`
- **영향**: 엄격한 중간 프록시/캐시가 200+`Content-Range` 를 부분 응답으로 오인할 여지. 브라우저 재생은 정상(실측). 보안 영향 없음.
- **수정 방향(제안)**: Range 부재 경로는 `ResourceRegion` 대신 `Resource`(또는 `ResponseEntity<Resource>`)를 반환하거나, 200 응답에서 `Content-Range` 를 제거한다. ⚠ **구현하지 않는다.**

### [B-ISSUE-64] TC-BATCH-126 — 배치 오토라벨 이미지 읽기가 lexical 경로 검증뿐이고 전용 회귀 테스트가 없다 (정보)

- **심각도**: LOW (정보 — 현재 입력원이 내부 DB 값이라 악용 경로 미확인)
- **기대 동작(기대효과)**: 프레임 이미지 open 은 `StorageSubtreePolicy`/`openNoFollow`(`NOFOLLOW_LINKS`) 계열의 realpath 기반 단일 판정기를 쓰는 것이 이 리포의 확립된 규약이다(CLAUDE.md "비식별 프레임을 파일로 여는 4경로는 단일 규약", CWE-59/367).
- **현재 동작(이슈 내용)**: `YoloAutolabelStep.java:427-440`(및 동일 코드 `Sam2SegmentStep.java:314-327`)
  ```java
  Path imagePath = baseRawPath.resolve(relativePath).normalize();
  if (!imagePath.startsWith(baseRawPath)) { throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로 범위 초과"); }
  byte[] bytes = Files.readAllBytes(imagePath);
  ```
  ①`normalize()` 후 `startsWith` 는 **lexical** 판정이라 base 하위의 심링크가 밖을 가리키면 통과한다(검증 경로 == open 경로이므로 TOCTOU 창은 좁으나 심링크 자체는 걸러지지 않는다). ②`backend/src/test/java/kr/co/cudo/authoring/batch/step/` 에 이 두 클래스의 **경로 순회 회귀 테스트가 없다**(traversal 테스트는 `DeidentFrameAttacherTest:511`·`FfmpegFrameExtractorTest:265`·`DeidentifyStepFailurePersistenceIntegrationTest:161` 뿐).
- **재현/확인 경로**:
  ```bash
  grep -rn "이미지 경로 범위 초과" backend/src/main/java   # 2곳(Yolo·Sam2)
  grep -rn "Traversal\|\.\./" backend/src/test/java/kr/co/cudo/authoring/batch/step/  # Yolo/Sam2 테스트 0건
  ```
- **영향**: 현재 `LS_DATA_SRC.SRC_FILE_PATH_NM` 은 프레임 추출기가 쓴 내부 값이고 증강 반입 경로는 `VideoArtifactRootResolver.verifyIngestablePath` 로 별도 검증되므로 실악용 경로는 확인되지 않았다. 리스크는 **가드가 다른 축으로 분화되어 규약 갱신 시 조용히 뒤처지는 것**(memory `state-gate-single-entry-point-rule` 6번째 축과 동형).
- **수정 방향(제안)**: 두 스텝의 `readImageAsBase64` 를 공용 판정기(`StorageSubtreePolicy` + `openNoFollow`)로 통일하고, base 이탈·심링크 이탈 각각의 회귀 테스트를 추가한다. ⚠ **구현하지 않는다.**

---

# B 클러스터 part5 (B-11·B-12) 2차 검증 결과

- 대상: `docs/test-cases/B-batch-deidentify.md` §B-11(비식별 누락 신고) · §B-12(재처리/재시도 큐)
- 검증 시각: 2026-07-31 03:20~03:35 KST · backend `localhost:18081` (HEAD `ca3c712b`, 이미지 `bdc64ea2ac26`)
- 실행 프로파일 **`local`** (`SPRING_PROFILES_ACTIVE=local`, `authoring.batch.enabled=false`) · 스키마 `public`
- 참조 데이터: `pipeline-drive.md` §3 카탈로그 (rawSn 126 APPROVED / 129·130·131 파생 / 132 검수대기 / **133 비식별 신고 OPEN**)
- **컨테이너 재기동·재빌드·빌드/테스트 실행 0건.** 소스·설정·테스트 파일 수정 0건(본 문서 1개만 신규 작성)
- **rawSn 133 / rprtSn 3 은 OPEN 그대로 유지**했다. resolve 는 호출했으나 **산출물 미검증 409(fail-closed)** 로 롤백되어 상태 변화가 없음을 DB 로 재확인(`OPEN` + lock `LOCKED` + `DE_IDENT_YN='F'`). resolve 성공 경로는 **다른 에이전트가 rawSn 144 에 남긴 실행 흔적(rprtSn 4/5)** 을 DB·로그로 실측 인용했다.

## 집계

| 섹션 | 표 행 | 폐기(제외) | 검증 대상 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| B-11 비식별 누락 신고 | 30 | 2 | **28** | 27 | 1 | 0 | 0 | 0 | 0 |
| B-12 재처리/재시도 큐 | 19 | 0 | **19** | 19 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **49** | **2** | **47** | **46** | **1** | **0** | **0** | **0** | **0** |

> 폐기(집계 제외) 2건 — `TC-DEID-034`(전체 라벨 스냅샷+삭제) · `TC-DEID-038`(라벨 0건 스냅샷 스킵). 둘 다 2026-07-27 정책 반전(라벨 보존·스냅샷 미생성)으로 무효.

**케이스 표 밖에서 발견한 게이트 미배선 3건**(B-ISSUE-81~83)은 위 집계에 포함되지 않는다. 그중 B-ISSUE-81 은 `TC-DEID-035` 의 명시 목적("stale PII 방지")을 무력화하므로 해당 케이스를 PARTIAL 로 낮췄다.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **B-ISSUE-28** | 마킹 단계 rawSn 신고 미구현(참고 기록) | **해소** | [실동작] `POST /v1/videos/133/deident-report` → **409**(중복 신고), `/v1/videos/129/…` → **412**(파생), `/v1/videos/9999999/…` → **404**. 1차의 "엔드포인트 자체 부재 404" 아님. `DeidentReportController.java:122` |
| **B-ISSUE-64** | 마킹 단계 rawSn 신고 미구현(설계 갭) | **해소** | 동상. `DeidentReportService.reportByVideo:147` → 공용 `doReport:169` 로 수렴 |
| **B-ISSUE-83** | 재시도 큐 `RETRYING` 클레임 후 노드 사멸 시 영구 유실 | **해소** | `BatchRetryQueue.sweepStaleRetrying:174-193` + `LsBatRtyWtngRepository.findStaleRetryingAnchors/reclaimStaleRetrying/exhaustStaleRetrying` 신설. [실동작] 기동 로그 `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000 staleTimeoutMinutes=180` + 전용 데몬 스레드 `batch-retry-stale-reclaim` 15분 주기 tick 관측 |
| **B-ISSUE-85** | local 프로파일에서 재시도 폴러 미등록 → 적재만 되고 발화 0건 | **미해소(환경)** | `application-local.yml:61-62 batch.enabled=false` → `BatchRetryTriggerConfig`(`@ConditionalOnProperty authoring.batch.enabled`) 미등록. [실동작] `ls_bat_rty_wtng` 에 rawSn=144 `PENDING`(rtyNmtm=3) 행이 도래 후에도 미발화. → B-ISSUE-86 으로 이월 |
| B-ISSUE-84 | mock `fileName` 계약 드리프트(B-13 소관) | 참고 | 본 part 범위 밖 |
| B-ISSUE-63 | `/stream` 영상 단위 배정 인가 부재(B-10 소관) | 참고 | [실동작] 미배정 WORKER(2099)의 `GET /v1/videos/132/stream` → **403**. B-10 담당이 재판정할 것 |

## ★신고 게이트 전 경로 실측표

대상 = **rawSn 133**(신고 OPEN, `DE_IDNTF_YN='F'`, 프레임 srcSn 78·80·82·84·86·88) / 대조군 = **rawSn 132**(신고 없음, srcSn 77) / 토큰 = REVIEWER(1001) 달리 표기 없으면.

| # | 엔드포인트 | 기대 | 실측(133) | 대조군(132) | Cache-Control | 판정 |
|--:|---|:--:|:--:|:--:|---|:--:|
| 1 | `GET /v1/frames/{srcSn}/labels` | 412 | **412** | 200 | `no-store`(Security 기본) | PASS |
| 2 | `GET /v1/frames/{srcSn}/label-history` | 412 | **412** | — | `no-store` | PASS |
| 3 | `GET /v1/frames/{srcSn}/image` | 412 | **412** | 200 | 200 응답 `Cache-Control: no-store` | PASS |
| 4 | `GET /v1/frames/{srcSn}/deid-image` | 412 | **412** | 200 | 200 응답 `no-store` | PASS |
| 5 | `GET /v1/videos/{rawSn}/frames/{frameNo}/image` | 412 | **412** | — | `no-store` | PASS |
| 6 | `GET /v1/videos/{rawSn}/stream` | **404** | **404** | 206 | 206 응답 `Cache-Control: no-store` + `Accept-Ranges: bytes` | PASS |
| 7 | `GET /v1/videos/{rawSn}/stream-url` | **404** | **404** | 200 | `no-store` | PASS |
| 8 | `GET /v1/tasks/{rawSn}/labels`(관제 조회) | 412 | **412** | 200 | `no-store` | PASS |
| 9 | `GET /v1/portal/frames/{srcSn}/labels` | 412 | 403(선행 게이트) | 403 | — | PASS(주1) |
| 10 | `GET /v1/portal/frames/{srcSn}/image` | 412 | 403(선행 게이트) | 403 | 코드상 `CacheControl.noStore()` (`PortalLabelService:494`) | PASS(주1) |
| 11 | `PUT /v1/frames/{srcSn}/labels` | 409 | **409** "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다." | — | — | PASS |
| 12 | `POST /v1/frames/{srcSn}/autolabel`(온라인 AI 탐지) | 차단 | **409**(작업락이 신고 게이트보다 앞) | 200 | — | PASS(주2) |
| 13 | 버전 diff / rollback | 412 | 미도달(주3) | — | — | PASS[정적] |
| 14 | 데이터셋 export | skip(보류) | 미도달(주3) | — | — | PASS[정적] |
| 15 | **인가 우선 순서 확인** — 미배정 WORKER(2099)로 #1~#8 전건 | 403 | **전건 403**(412/404 로 갈리지 않음) | 전건 403 | — | PASS |
| ⚠16 | `GET /v1/videos/{rawSn}/labels/auto` | (미명시) | **200** — 라벨 클래스·신뢰도 28건 노출 | 200 | — | **미배선 → B-ISSUE-83** |
| ⚠17 | `GET /v1/videos/{rawSn}/auto-summary` | (미명시) | **200** — 총 28라벨·클래스분포·저신뢰 프레임 목록 | 200 | — | **미배선 → B-ISSUE-83** |
| ⚠18 | `PUT /v1/frames/{srcSn}/privacy-meta` | (미명시) | **200** — 신고가 리셋한 3필드를 즉시 재설정 성공 | 200 | — | **미배선 → B-ISSUE-81** |
| ⚠19 | `PUT /v1/frames/{srcSn}/description` | (미명시) | **200** — 작업락 무시 | 200 | — | **미배선 → B-ISSUE-82** |
| 20 | `GET /v1/frames/{srcSn}/versions` | (미명시) | 200(빈 배열 — 133 은 스냅샷 0건) | — | — | 정보 |
| 21 | `GET /v1/tasks/{rawSn}/summary` · `/meta` | (미명시) | 200(라벨 본문 없음 — 계약상 메타만) | — | — | 정보 |

- 주1) 포털 경로는 **데이터마트 노출(APPROVED) 검사가 신고 게이트보다 앞**이라 132·133 모두 403 으로 수렴한다(`PortalLabelService.isExposedToDatamart`). 신고 게이트 자체는 `PortalLabelService:267,443` 에 배선돼 있고 `DeidentReportGateCoverageIT`("신고_상태에서_포털_라벨_조회와_이미지_서빙이_차단된다")가 커버한다.
- 주2) `AutolabelOnlineService:415` 에 신고 게이트(412)가 배선돼 있으나 **작업락 409 가 선행**한다. 신고는 항상 작업락을 동반하므로 실사용상 412 에 도달하지 않는다(둘 다 차단이므로 누수 아님).
- 주3) rawSn 133 은 승인 이력이 없어 `LS_LABEL_VERSION`·`LS_DATASET_EXPORT` 행이 0건이라 실호출로 게이트에 도달하지 못한다. `VersionService:354,356,437` · `DatasetExportService:136` · `DatasetExportTxService:226`(잠금 재판정) 정적 확인 + `DeidentReportGateCoverageIT` 커버.
- **⚠16~19 는 케이스 표에 없는 형제 엔드포인트**다. 프롬프트 지시("목록에 없는 형제 엔드포인트도 찾아서 던져라")에 따라 추가 사냥한 결과이며, 확정 정책(★1 파생 무관·★2 관제 접근 보장)과 무관한 별개 표면이다.

## B-11 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-DEID-030 | 신고: reason blank → INVALID_INPUT | PASS | [실동작] `{"reason":""}`·`{}` 둘 다 **400** `INVALID_INPUT` "reason: 신고 사유는 필수입니다." (`@Valid` 선차단). 서비스 `requireReason:158-162` 이중 방어 | 두 진입점 공통 |
| TC-DEID-031 | 신고: WORKER 본인 배정만(IDOR) | PASS | [실동작] 미배정 WORKER(2099) `POST /v1/labels/78/deident-report` → **403**, PORTAL_USER → **403**. [정적] `DeidentReportService:122`→`LabelAccessGuard.verifyAndGet` | rawSn 경로도 동일(403) |
| TC-DEID-032 | 신고: 부모 RAW PESSIMISTIC_WRITE 락(PII TOCTOU) | PASS | [정적] `DeidentReportService:178` `videoRepository.findByRawSnForUpdate` · `VideoRepository:37-39` `@Lock(PESSIMISTIC_WRITE)` + JPQL. 서비스가 `@Transactional`(readOnly 아님, :83) 안이라 커밋까지 락 유지 | |
| TC-DEID-033 | 신고: 이미 잠금 → CONFLICT | PASS | [실동작] 133 재신고 srcSn/rawSn 양쪽 **409** "이미 비식별 재처리 중인 영상입니다."(`:189-191`). DB `ls_auth_work_lock` sn=3 `LOCKED` 유지 | |
| ~~TC-DEID-034~~ | ~~전체 라벨 스냅샷+삭제+'F'~~ | — | **폐기 2026-07-30** (집계 제외) | 대체 TC-DEID-051 |
| TC-DEID-035 | 신고: 개인정보 3필드 리셋(stale PII 방지) | **PARTIAL** | [실동작] 리셋 자체는 **정상** — 로그 `privacyReset=6`, DB `ls_data_src(raw_sn=133)` 6행 `anony/psdo/prvc_incl_yn` 전부 NULL. [정적] `:218-219` 벌크 JPQL(`LsDataSrcRepository:199-201`, 파라미터 바인딩). **그러나 "stale PII 방지" 목적은 성립하지 않는다** — `PUT /v1/frames/78/privacy-meta` 가 신고 구간에도 200 으로 3필드를 즉시 재설정한다(실측) | **B-ISSUE-81** |
| TC-DEID-036 | 신고: 락 UNIQUE 위반 → CONFLICT(동시) | PASS | [정적] `:237-241` `DataIntegrityViolationException`→`ErrorCode.CONFLICT`. 테스트 `DeidentReportServiceTest:524`("동시_신고_unique_위반시_409") | |
| TC-DEID-037 | 신고: APPROVED → TASK_MODIFIED(META_UPDATED) | PASS | [정적] `:230-233` `isReviewApproved` → `TaskModifiedEvent(rawSn, srcSnForNotify, ChangeType.META_UPDATED, reporterNo)`. 테스트 `:487`("APPROVED_영상_신고시_TASK_MODIFIED_통지_발행")·`:508`(미승인 미발행)·`:394`(rawSn 경로도 발행) | 실동작 미수행 — APPROVED 대상 126 을 신고하면 완주 기준 영상이 오염되어 회피 |
| ~~TC-DEID-038~~ | ~~라벨 0건이면 스냅샷 스킵~~ | — | **폐기 2026-07-30** (집계 제외) | |
| TC-DEID-039 | resolve: actor null → UNAUTHORIZED | PASS | [실동작] 토큰 없이 `POST /v1/deident-reports/3/resolve` → **401**. [정적] `:354-356` | |
| TC-DEID-040 | resolve: 신고 없음 → NOT_FOUND | PASS | [실동작] `rprtSn=999999` → **404** "신고를 찾을 수 없습니다."(`:357-358`) | |
| TC-DEID-041 | resolve: OPEN 아님 → CONFLICT | PASS | [실동작] 이미 RESOLVED 인 `rprtSn=4` → **409** "이미 처리된 신고입니다."(`:364-366`) | |
| TC-DEID-042 | resolve: 산출물 미검증 → CONFLICT(fail-closed) | PASS | [실동작] `rprtSn=3`(REVIEWER) → **409** "비식별 산출물이 확인되지 않습니다…". 호출 **후** DB 재확인: report `OPEN` · lock `LOCKED` · `DE_IDENT_YN='F'` **전부 유지**(트랜잭션 롤백). 로그 `resolve blocked — deident artifact not verified rawSn=133`(경로 원문 미노출) | |
| TC-DEID-043 | resolve: 성공 → RESOLVED+락해제+'F'→'Y' | PASS | [실동작] 타 에이전트 실행분 rawSn **144** 실측 — `ls_deident_report` sn=4 `RESOLVED`(resolved_dt 03:20:38) · `ls_auth_work_lock` sn=4 `RELEASED`/`MANUAL_DEIDENT_DONE` · `ls_data_raw(144).de_ident_yn='Y'` · 로그 `resolved-manually rprtSn=4 rawSn=144 actor=1001`. [정적] `:374-401` | 보존 라벨 재사용은 `DeidentReportLabelPreservationIT:167` 커버 |
| TC-DEID-044 | verifyArtifact: 경로 없음/파일 부재 → 거부 | PASS | [정적] `:544-555` — 최신 SUCCESS procLog 부재 → 거부 / `DE_IDNTF_FILE_PATH_NM` null·blank → 거부 / `DeidentArtifactIntegrity.isValidVideoArtifact` false → 거부. 테스트 `:802`·`:827`·`:876`(18B 스텁)·`:906`(시그니처 없음) | |
| TC-DEID-045 | verifyArtifact: 시간조건(신고 후 재비식별) 미충족 | PASS | [실동작] rawSn 133 — 신고 03:00:33 / procLog `rspns_dt` 02:57:32(=신고 이전) / 비식별 파일 mtime 02:57:26 KST(`17:57:26 UTC`) < 신고−60s(02:59:33) → 두 조건 모두 false → **409**. [정적] `:557-580` 스큐 60s(`CLOCK_SKEW_TOLERANCE_SECONDS:504`). 테스트 `:993`·`:1025`·`:1055` | 이 케이스가 rprtSn 3 을 OPEN 으로 유지시킨 장치다 |
| TC-DEID-046 | resolve: 배치단계 역행 안 함(CWE-664) | PASS | [정적] `:384-391` — `markDeidentified("Y")` 만 수행, `DATA_STTS_CD` 미변경(주석에 CWE-664 명시). 테스트 `:778`("APPROVED_영상_신고_해소시_배치단계가_되감기지_않는다") | rawSn 144 실측도 `data_stts_cd=FAILED` 유지(resolve 가 건드리지 않음) |
| TC-DEID-047 | resolveOpenReports(자동): OPEN 일괄 RESOLVED | PASS | [정적] `:443-462` — resolve + `releaseRaw(rawSn,"system","DEIDENT_SUCCEEDED")` + `streamMetaCacheEvictor.evictAfterCommit`. 호출부 2곳 `DeidentifyStep:354` · `KpstDeidentTxService:358`. 테스트 `:1111`·`:1130`(null 안전) | `opens.isEmpty()` 면 복구 이벤트 미발행(멱등) |
| TC-DEID-048 | 신고 목록: status allowlist 밖 → 400 | PASS | [실동작] `?status=X` · `?status=OPEN' OR 1=1--` **400**. `?status=OPEN`·`RESOLVED` 200. WORKER → 403 | ⚠ `?status=open`(소문자)도 **400** — 컨트롤러 `@Pattern`(`:74`)이 대문자만 허용하므로 서비스 `normalizeStatus` 의 `toUpperCase`(`:430`)는 HTTP 경로에서 도달 불가. "이중 방어"는 성립하나 관대성 방향이 다름(정보) |
| TC-DEID-049 | 컨트롤러: 라벨링 단계 srcSn 신고 | PASS | [실동작] `pipeline-drive.md` §1-20 — `POST /v1/labels/78/deident-report` → **201**, `ls_deident_report` sn=3 생성(rawSn 133, reporter 2001). 본 검증에서 재호출 시 409(중복)로 진입 확인 | |
| TC-DEID-050 | 컨트롤러: resolve — WORKER 본인/REVIEWER 전체 | PASS | [실동작] 200=rprtSn 4(REVIEWER, rawSn 144) / 403=미배정 WORKER(2099) rprtSn 3 / 409=rprtSn 3(산출물 미검증)·rprtSn 4(이미 처리) | |
| TC-DEID-051 | ★라벨 보존 — 신고 후 라벨·이력 불변 | PASS | [실동작] rawSn 133 — `ls_data_lbl` **28행 유지**(`pipeline-drive.md` 기록치와 동일), `ls_label_version` 신규 **0행**, 로그 `labelsPreserved=true`. [정적] `:197-205` 주석(구 스냅샷·삭제 로직 완전 제거). IT `DeidentReportLabelPreservationIT:138` | 라벨셋 버전 bump 도 없음 |
| TC-DEID-052 | 개인정보 3필드 리셋의 행 단위 감사 | PASS | [정적] `:218-227` — 리셋 **직전** `findSrcSnsWithPrivacyMeta`(`LsDataSrcRepository:212-215`, non-null 필터)로 대상 확정 → `LsDataLblHstry.recordPrivacyMetaResetEvent`(:132) 프레임당 1행. 델타 0건이라 V139 필터로 `V_COMPLETED_LABEL_CHANGE` 미노출. IT `DeidentReportServiceResetIT:127` | [실동작] rawSn 133 은 신고 시점 3필드가 이미 전부 NULL 이라 감사 대상 0건(`privacyResetAudited=0`) — 감사행 생성은 미관측. 리셋 벌크는 6행 갱신(`privacyReset=6`)이라 두 수치의 의미 차이가 설계대로 나타남 |
| TC-DEID-053 | ★파생영상 신고는 412 로 거부 | PASS | [실동작] `POST /v1/labels/68/deident-report`(파생 129 프레임) · `POST /v1/videos/129/deident-report` · `POST /v1/videos/130/deident-report`(해상도 파생) **전부 412**. 응답 문구에 **부모 rawSn 없음**·원본 유도 없음. `ls_deident_report` 신규 행 0. 로그 `WARN … rejected — derivative video is out of the report workflow rawSn=129 orgnlRawSn=126 reason=파생 신고 테스트`(LogSanitizer 정제, REVIEWER 알림 없음). [정적] `:182-183`,`:295-306` | 확정 정책 ★1 정합 |
| TC-DEID-054 | 비식별 미수행('N'/null) 신고 412 | PASS | [정적] `:185-186`,`:329-337` — 판정을 `LsDataRaw.hasDeidentArtifact()` 단일 원천에 위임하므로 `'F'`(신고·실패)는 **통과**해 기존 409 경로로 흘러간다(`:326-327` 주석 명시). 테스트 `:246`·`:273`, 컨트롤러 테스트 `:308` | [실동작] 불가 — 현 DB 에 `DE_IDENT_YN` 이 `'N'`/null 인 영상이 **0건**(전체 Y 47 / F 3) |
| TC-DEID-055 | ★마킹 단계 rawSn 신고 신설 | PASS | [실동작] `POST /v1/videos/{rawSn}/deident-report` 존재 확인 — 133→409, 129/130→412, 9999999→**404**(엔드포인트 부재 404 아님, 본문 `"영상을 찾을 수 없습니다."`), 미배정 WORKER→403. 1차 B-ISSUE-28/64 해소 | `Controller:122-129` · `Service:147` |
| TC-DEID-056 | rawSn·srcSn 두 경로가 동일 본체(doReport) | PASS | [정적] `report:118` · `reportByVideo:147` → 둘 다 `doReport:169` 로 수렴. 인가축(`verifyAndGet` vs `verifyRawAccess`)·통지 `srcSn`(null) 두 가지만 다름. [실동작] 두 경로 응답코드 일치 — 파생 412·중복 409·미인가 403·미존재 404·사유누락 400 | |
| TC-DEID-057 | resolve 성공 시 `DeidentGateReopenedEvent` 항상 발행 | PASS | [정적] `publishResolvedForExportRecovery:487-495` — `DeidentGateReopenedEvent` 는 무조건(`:491`), 승인 여부 판정 밖. 테스트 `:679`("미승인_영상_해소시_게이트_재개방만_발행되고_export_재산출은_없다 — VLM 보류 재개 경로 보존") | |
| TC-DEID-058 | resolve 성공 + APPROVED 일 때만 `DeidentReportResolvedEvent` | PASS | [정적] `:492-494` `if (isReviewApproved(rawSn))` 안에서만 발행. 테스트 `:659`(승인=자기 rawSn 만 재트리거)·`:679`(미승인 미발행) | |
| TC-DEID-059 | ★게이트 판정 범위 = 자기 rawSn 행 하나 | PASS | [정적] `DeidentReportGate:66-71` — `videoRepository.findDeIdntfYnByRawSn`(`VideoRepository:50-51` 단일 컬럼 projection) 1회 조회, `ORGNL_RAW_SN` 미참조. 잠금판정 `:93-100` 도 자기 행 1개만. 테스트 `DeidentReportGateTest:88`("★원본이_신고중이어도_파생영상은_막히지_않는다") · `DeidentReportStreamGateIT:194`. [실동작] 파생 129 의 `GET /v1/frames/68/labels` 200 · `GET /v1/videos/129/stream-url` 200 | 조상/자손 전파 코드 **부재** 재확인(grep 0건) — 확정 정책 ★1 정합 |

## B-12 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-150 | 재처리: rawSn null → INVALID_INPUT | PASS | [실동작] `POST /v1/videos/0/batch/retry` → **400** "retryBatch.rawSn: rawSn 은 1 이상이어야 합니다."(`@Min`). [정적] 서비스 방어심도 `BatchReprocessService:67-69` | |
| TC-BATCH-151 | 재처리: 영상 미존재 → NOT_FOUND | PASS | [실동작] `rawSn=9999999` → **404** "영상을 찾을 수 없습니다."(`:71-73`) | |
| TC-BATCH-152 | 재처리: FAILED→PROCESSING 원자 클레임 성공 | PASS | [실동작] rawSn **128**(raw FAILED) 재처리 → 클레임 성공(=이어지는 SKIPPED 보상 경로에 도달했다는 것이 클레임 성공의 증거) → `clearIfIdle` → `orchestrator.process`(`:77-87`). 테스트 `BatchReprocessServiceTest:64`·컨트롤러 `:117` | 완전 성공 경로(파이프라인 완주)는 타 에이전트 데이터 오염 우려로 미수행 |
| TC-BATCH-153 | 재처리: FAILED 아님/이미 클레임 → CONFLICT | PASS | [실동작] rawSn 20035(COMPLETED) → **409** "배치가 실패(FAILED)한 영상만 재처리할 수 있으며, 이미 재처리가 진행 중일 수 있습니다."(`:77-81`). WORKER → 403 | |
| TC-BATCH-154 | 재처리 클레임: raw 우선, 없으면 status FAILED→PROCESSING | PASS | [정적] `BatchTransitionService:335-349` — `videoRepository.claimReprocessFromFailed` 우선, 1행 아니면 `rawDataStatusRepository.claimReprocessFromFailed`. 둘 다 조건부 UPDATE, `REQUIRES_NEW` 즉시 커밋 | **테스트 커버 부재** — `claimReprocessFromFailed` 를 직접 단언하는 테스트 0건(상위 서비스에서 mock 으로만) → **B-ISSUE-85** |
| TC-BATCH-155 | 재시도 등록: 최초 실패 PENDING(ON CONFLICT DO NOTHING) | PASS | [정적] `BatchRetryQueue:71-75` `insertIfAbsent`(native `ON CONFLICT (RAW_SN) DO NOTHING`) → `findByRawSnForUpdate`(PESSIMISTIC_WRITE). [실동작] 로그 `[BatchRetry] enqueued rawSn=128 attempt=1 delaySec=60` (RTY_NMTM=1). IT `BatchRetryQueueIT:90` | |
| TC-BATCH-156 | 재시도 등록: 동시 최초 실패 UK 경쟁 흡수 | PASS | [정적] `:72-74` — 원자 upsert 로 PG tx abort 함정 회피(같은 tx 내 예외 재시도 없음). IT `BatchRetryQueueIT:153`("동시_최초등록시_UK위반이_전파되지_않는다") | |
| TC-BATCH-157 | 재시도: 지수백오프(60,120,240…) shift 30 캡 | PASS | [실동작] 로그 3연속 `delaySec=60` → `120` → `240`(rawSn 128, 03:13:27). [정적] `:85-88` `shift = min(attempt-1, 30)`, `delaySec = initialDelaySec * (1L << shift)` | |
| TC-BATCH-158 | 재시도: max 초과 → EXHAUSTED false | PASS | [실동작] 로그 `WARN [BatchRetry] max attempts exceeded -- exhausted rawSn=128 attempt=4 max=3`, DB 행이 **삭제되지 않고** `STTS_CD='EXHAUSTED'` 로 관측됨(`3|128|5|3|EXHAUSTED`). [정적] `:78-84` | ⚠ 소진 후 재호출도 `incrementAttempt()` 를 먼저 하므로 `RTY_NMTM` 이 max 를 넘어 계속 증가한다(실측 5>3). 매회 `markExhausted` 이므로 동작상 무해하나 카운터 의미가 "시도 횟수"에서 이탈(정보) |
| TC-BATCH-159 | 폴링 클레임: 2노드 동시 폴링 직렬화 | PASS | [정적] `:100-112` `pollReady` — 후보 10건 순회하며 `claimAtomically`(`LsBatRtyWtngRepository` `UPDATE … SET sttsCd='RETRYING' WHERE batRtySn=? AND sttsCd='PENDING'`) 영향행수 1인 첫 건만 반환. IT `BatchRetryQueueIT:104`("재시도_동시_폴링시_한_노드만_클레임한다"). 클레임 후 사멸분은 TC-BATCH-164 스윕이 회수 | [실동작] 불가 — local `batch.enabled=false` 로 `BatchRetryTriggerConfig` 미등록(B-ISSUE-86). `ls_bat_rty_wtng` rawSn=144 PENDING 행이 도래 후에도 미발화 |
| TC-BATCH-160 | 재시도 등록 노드 ≠ 발화 노드(DB 영속) | PASS | [정적] `BatchRetryQueue` 전 메서드가 `LsBatRtyWtngRepository`(테이블 `LS_BAT_RTY_WTNG`)만 사용 — **인메모리 맵 필드 0개**(클래스 필드는 repository/maxAttempts/initialDelaySec 뿐, `:40-42`). 전 메서드 `REQUIRES_NEW` 즉시 커밋. IT `BatchRetryQueueIT:90`("재시도큐_등록후_다른_노드_폴링이_항목을_회수한다") | |
| TC-BATCH-161 | clearIfIdle: RETRYING 보존, PENDING/EXHAUSTED만 삭제 | PASS | [정적] `:140-146` → `deleteIdleByRawSn` = `DELETE … WHERE rawSn=? AND sttsCd <> 'RETRYING'`. `BatchReprocessServiceTest:60,76,95`(클레임 성공 시에만 호출) | 리포지토리 쿼리 자체를 단언하는 테스트는 없음 |
| TC-BATCH-162 | clear: 성공 시 전체 삭제 | PASS | [정적] `:125-131` `deleteByRawSn`(상태 무관). IT `BatchRetryQueueIT:178` | |
| TC-BATCH-163 | 재처리 진입 가드 SKIPPED → 보상 롤백 + 409 | PASS | [실동작] ★ rawSn **128**(raw `FAILED` + 작업상태 `PENDING`=검수 소유) 재처리 → **409** "검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다." + 호출 후 DB 재확인 `ls_data_raw(128).data_stts_cd='FAILED'` **복귀**(PROCESSING 고착 없음). [정적] `BatchReprocessService:89-101` · `BatchTransitionService.releaseReprocessClaim:365-379`(두 컬럼 모두 조건부 되돌림). IT `BatchReviewOwnedEntryGuardIT:187` | 재호출도 계속 409 로 재현 가능(고착 없음 확인) |
| TC-BATCH-164 | ★stale RETRYING 회수 — PENDING 복귀 | PASS | [정적] `:173-193` → `findStaleRetryingAnchors`(`STTS_CD='RETRYING' AND MDFCN_DT<=cutoff`, `LIMIT`) → `reclaimStaleRetrying`(같은 조건을 UPDATE 에 재탑재 + `RTY_NMTM+1` + `RTY_NMTM < MAX_RTY_NMTM`). IT `BatchRetryStaleReclaimIT:114`·`:133`(정상 처리중 미회수)·`:173`(2노드 동시 1회만) | 1차 B-ISSUE-83 해소 |
| TC-BATCH-165 | stale 회수: 상한 도달분은 EXHAUSTED 종결 | PASS | [정적] `:187-190` `exhaustStaleRetrying`(`RTY_NMTM >= MAX_RTY_NMTM`) — reclaim 과 조건 상호배타. IT `BatchRetryStaleReclaimIT:151`("재시도_상한을_넘긴_항목은_무한_부활하지_않는다") | 근거 라인 `186-191` → 실제 `187-190` |
| TC-BATCH-166 | stale 임계 하한 clamp 30분 | PASS | [정적] `BatchRetryStaleReclaimSweeper:64` `MIN_STALE_TIMEOUT_MINUTES=30`, `:94-97` `staleTimeoutMinutes<1 ? 180 : max(30, v)`. [실동작] 기동 로그 `staleTimeoutMinutes=180`(기본값 적용) | **전용 테스트 부재** — `staleTimeoutMinutes()` 접근자를 단언하는 테스트는 `VlmSubmitPendingSweeperTest` 뿐(다른 클래스) → **B-ISSUE-84** |
| TC-BATCH-167 | stale 스윕은 자기 토글만 본다 + 전용 daemon executor | PASS | [실동작] ★ 현 환경은 `authoring.batch.enabled=false`(local) 인데도 스윕이 기동됨 — 로그 `[BatchRetry][Reclaim] stale reclaim scheduled …` + 전용 데몬 스레드 `batch-retry-stale-reclaim` 의 15분 주기 tick 2회 이상 관측(02:48/03:03/03:18). [정적] `:84` `@Value("${authoring.batch.retry.stale-reclaim.enabled:true}")`, `:100-114` `@PostConstruct` + `Executors.newSingleThreadScheduledExecutor`(daemon), `@Scheduled`/`@EnableScheduling` 미사용 | **전용 테스트 부재**(`isScheduled()` 단언은 `AugmentJobExpirySweeperTest` 뿐) → B-ISSUE-84 |
| TC-BATCH-168 | stale 스윕 예외는 삼킴(Throwable) | PASS | [정적] `:142-157` `try { … } catch (Throwable e) { log.error("… reason={}", e.getClass().getSimpleName()); return 0; }` — Error 계열까지 포착, 클래스명만 노출(CWE-209) | **전용 테스트 부재** → B-ISSUE-84 |

## 근거 드리프트

케이스 표의 `근거(file:line)` 가 실제 코드와 어긋난 건. **전부 ±수 라인의 근접 드리프트**이며 지목 대상 로직이 바뀐 건은 없다(위치 이동만).

| ID | 표 기재 | 실측 | 성격 |
|---|---|---|---|
| TC-DEID-031 | `DeidentReportService.java:118-133` | `report()` = 118-126 (인가 위임 122) | 범위 초과 |
| TC-DEID-032 | `:178-180` | 178-179 | ±1 |
| TC-DEID-033 | `:188-191` | 189-191 | ±1 |
| TC-DEID-037 | `:230-234` | 230-233 | ±1 |
| TC-DEID-039 | `:353-360` | 353-356 | ±4 |
| TC-DEID-040 | `:353-365` | 357-358 | 범위 |
| TC-DEID-041 | `:365-370` | 364-366 | ±4 |
| TC-DEID-042 | `:372,542-590` | 372 ✓ / `verifyDeidentArtifact` 542-581 | ±9 |
| TC-DEID-043 | `:372-400` | 374-401 | ±2 |
| TC-DEID-044 | `:542-570` | 544-555 | ±15 |
| TC-DEID-045 | `:542-590` | 시간조건 557-580 | ±10 |
| TC-DEID-046 | `:380-400` | 384-391 | ±9 |
| TC-DEID-047 | `:443-470` | 443-462 | ±8 |
| TC-DEID-048 | `:418-440` | `listReports` 417-423 / `normalizeStatus` 426-438 | 범위 |
| TC-DEID-051 | `:196-204` | 197-205(주석 블록) | ±1 |
| TC-DEID-053 | `:182-183,295-310` | 182-183 ✓ / 295-306 | ±4 |
| TC-DEID-054 | `:185-186,329-340` | 185-186 ✓ / 329-337 | ±3 |
| TC-DEID-057 | `:480-491` | `publishResolvedForExportRecovery` 487-495(발행 491) | ±7 |
| TC-DEID-058 | `:483-493` | 492-494 | ±9 |
| TC-DEID-059 | `DeidentReportGate.java:12-39,66` | javadoc 8-47 / `isUnderDeidentReport` 66 ✓ | 범위 |
| TC-BATCH-154 | `BatchTransitionService.java:334-362` | `tryClaimReprocessFromFailed` 335-350 | 범위 |
| TC-BATCH-163 | `BatchTransitionService.java:364-380` | `releaseReprocessClaim` 365-379 | ±1 |
| TC-BATCH-165 | `BatchRetryQueue.java:186-191` | 187-190 | ±1 |
| TC-BATCH-160 | `BatchRetryQueue.java:16-34` | 클래스 javadoc 14-32 | ±2 |
| TC-BATCH-167 | `BatchRetryStaleReclaimSweeper.java:42-50,84,102-110` | javadoc 42-52 / `:84` ✓ / `start()` 100-114 | ±4 |

## 이슈 상세

### [B-ISSUE-81] TC-DEID-035 / TC-DEID-052 — 프레임 개인정보 3필드 수정 API 가 신고 게이트·작업락 어디에도 배선되지 않아, 신고가 리셋한 PII 표기를 신고 구간에 즉시 되돌릴 수 있다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 비식별 누락 신고는 "그 판정은 비식별이 잘못된 영상에서 내려진 것이므로 재판정 대상"이라는 이유로 개인정보 3필드(익명/가명/PII 포함여부)를 NULL 로 리셋한다(CLAUDE.md · TC-DEID-035 "stale PII 방지"). 따라서 신고~해소 구간에는 **그 값을 다시 채울 수 없어야** 리셋이 의미를 갖는다. 같은 구간의 라벨 저장이 작업락 409 로 막히는 것과 동일한 취급이어야 한다.
- **현재 동작(이슈 내용)**: `PUT /v1/frames/{srcSn}/privacy-meta` 는 인가만 검사하고 **신고 게이트도 작업락도 보지 않는다.**
  ```java
  // backend/src/main/java/kr/co/cudo/authoring/dataset/service/FramePrivacyMetaService.java:74-75
  public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
      LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만. requireNotUnderDeidentReport / workLock 없음
  ```
  같은 클래스의 `updateBulk`(`:100-122`)도 `verifyRawAccess` 만 수행한다. 비교 대상인 `LabelService.getByFrame:193` · `getHistory:482` 는 `accessGuard.requireNotUnderDeidentReport(...)` 를 인가 직후에 호출한다.
- **재현/확인 경로** (rawSn 133 = 신고 OPEN, srcSn 78. 실제로 수행했고 즉시 NULL 로 원복함):
  ```bash
  # 신고 직후 상태: 3필드 전부 NULL
  docker exec klid-postgres psql -U klid_user -d klid_system -At -F'|' \
    -c "select src_sn,anony_incl_yn,psdo_incl_yn,prvc_incl_yn from ls_data_src where src_sn=78;"   # 78|||

  curl -s -X PUT http://localhost:18081/api/v1/frames/78/privacy-meta \
    -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}'
  # -> 200 {"success":true,"data":{"srcSn":78,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}}
  # DB -> 78|Y|N|N   (신고가 지운 값이 되살아남)
  ```
  대조: 같은 프레임의 `PUT /v1/frames/78/labels` 는 **409** "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."
- **영향**: CWE-359(민감정보 노출) / CWE-863(부정확한 인가). ①신고의 보안 부수효과가 무효화되어 "개인정보 없음(`prvcInclYn='N'`)" 같은 **stale 오표기가 신고 구간에 재삽입**될 수 있고, 그 값은 export JSON·데이터마트로 그대로 나간다. ②`LS_DATA_LBL_HSTRY` 감사(TC-DEID-052)는 **리셋만** 기록하고 이 재설정은 기록하지 않아, "누가 언제 PII 표기를 되돌렸는가"가 추적되지 않는다(OWASP A09). ③작업락이 걸린 영상에 쓰기가 통과하므로 락의 계약도 부분적으로 깨진다.
- **수정 방향(제안)**: `FramePrivacyMetaService.update`/`updateBulk` 진입부(인가 **이후**)에 `accessGuard.requireNotUnderDeidentReport(src.getRawSn())` 를 추가하고, 라벨 저장과 동일하게 작업락 409 가드를 적용한다. 판정은 반드시 `DeidentReportGate` 단일 원천을 재사용할 것(호출처마다 `"F".equals(...)` 를 재구현하지 않는다). ⚠ **구현하지 않는다.**

### [B-ISSUE-82] B-11 인접 — 프레임 설명 수정 API 가 작업락·신고 게이트 미배선으로 신고 구간에도 200 으로 기록된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 신고 구간(`DE_IDNTF_YN='F'` + 작업락 LOCKED)에는 그 영상에 대한 작업 산출물 수정이 차단되어야 한다(CLAUDE.md "저장·수정은 기존 작업락으로 409 차단"). 프레임 설명은 승인 후 수정 시 **export 전량 재생성 트리거**로 명시된 산출 대상이다(CLAUDE.md ★export 재생성 정책, `FrameDescriptionService`).
- **현재 동작(이슈 내용)**:
  ```java
  // backend/src/main/java/kr/co/cudo/authoring/label/service/FrameDescriptionService.java:45-46
  public FrameDescriptionResponse update(Long srcSn, String description, TokenClaims actor) {
      LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만
  ```
  신고 게이트·작업락 검사가 없다.
- **재현/확인 경로** (수행 후 `description:null` 로 원복함):
  ```bash
  curl -s -X PUT http://localhost:18081/api/v1/frames/78/description \
    -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"description":"gate probe"}'
  # -> 200 {"success":true,"data":{"srcSn":78,"description":"gate probe"}}
  ```
- **영향**: 잠긴 영상에 대한 작업이 계속되어 락 계약이 깨진다. 승인된 영상이라면 재export 브리지를 깨우는데, export 자체는 신고 게이트로 skip 되므로 **파일은 갱신되지 않은 채 트리거만 소모**된다(관제 통지도 보류). PII 직접 노출은 아니라 MEDIUM.
- **수정 방향(제안)**: `FrameDescriptionService.update` 에 `requireNotUnderDeidentReport` + 작업락 가드 추가. 조회(`get`)까지 막을지는 정책 결정 대상(설명 텍스트는 좌표·픽셀이 아니므로 조회 차단은 불필요하다는 판단도 가능). ⚠ **구현하지 않는다.**

### [B-ISSUE-83] B-11 인접 — `GET /v1/videos/{rawSn}/labels/auto` · `/auto-summary` 가 신고 구간에도 라벨 메타를 노출한다(게이트 미배선 형제 경로)
- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간에는 라벨 조회 계열이 412 로 차단된다(`GET /v1/frames/{srcSn}/labels`·`label-history`·관제 `tasks/{rawSn}/labels` 전부 배선됨).
- **현재 동작(이슈 내용)**: 같은 영상의 **영상 단위 라벨 조회 2종**에는 게이트가 없다.
  ```
  GET /v1/videos/133/labels/auto   -> 200  data.objects[28]  {id,labelCode,labelName,color,confidence,createdBy}
  GET /v1/videos/133/auto-summary  -> 200  totalFrames=6 totalLabels=28 classDistribution=[car 11, truck 7, …]
                                           lowConfidenceFrames[].thumbnailUrl="/v1/frames/80/image"
  ```
  `grep -rn "requireNotUnderDeidentReport\|isUnderDeidentReport" VideoController/관련 서비스` → 해당 두 핸들러 경로에 0건.
- **영향**: 응답 DTO에 **좌표(`pointCn`)가 없어** PII 위치 특정 정보는 나가지 않고, 참조된 `thumbnailUrl` 도 호출하면 412 로 막힌다. 따라서 실질 노출은 "어떤 클래스가 몇 개 있는가"에 그친다. 다만 **차단 범위 목록이 엔드포인트 단위로 열거된 정책**에서 형제 경로만 빠진 형태라, 향후 이 DTO 에 좌표/썸네일 바이트가 추가되면 조용히 누수로 승격된다(이 리포의 반복 사고 패턴 — "컨트롤러가 정책 판정을 복제·누락").
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/133/labels/auto | head -c 300
  curl -s -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/133/auto-summary | head -c 300
  ```
- **수정 방향(제안)**: 두 핸들러의 서비스 진입부(인가 이후)에 `requireNotUnderDeidentReport(rawSn)` 을 추가하거나, "좌표 없는 집계는 게이트 대상 아님"을 `UNCERTAINTIES.md` 확정 정책으로 명문화해 판정 기준을 고정한다(둘 중 하나는 필요 — 현재는 의도인지 누락인지 문서로 판별 불가). ⚠ **구현하지 않는다.**

### [B-ISSUE-84] TC-BATCH-166 / 167 / 168 — stale 회수 스윕의 clamp·토글 독립·예외 격리에 전용 테스트가 없다
- **심각도**: LOW (커버리지 갭 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 케이스 3건이 각각 단언 대상(`MIN_STALE_TIMEOUT_MINUTES` 하한 clamp / 자기 토글 off 시 미등록 / `Throwable` 삼킴)을 회귀 테스트로 고정한다. 특히 clamp 는 "과소 설정이 정상 처리 중 항목을 뺏어가 이중 처리 전면화"를 막는 fail-safe 라 회귀 시 영향이 크다.
- **현재 동작(이슈 내용)**: `grep -rn "BatchRetryStaleReclaimSweeper" backend/src/test/` → **0건**. `BatchRetryStaleReclaimIT`(4건)는 `BatchRetryQueue.sweepStaleRetrying` 만 직접 호출하고 스위퍼 빈은 거치지 않는다. 동형 자산(`AugmentJobExpirySweeperTest` 의 `isScheduled()` 단언, `VlmSubmitPendingSweeperTest:121` 의 `staleTimeoutMinutes()` 하한 단언)이 존재하므로 패턴은 이미 있다.
- **재현/확인 경로**:
  ```bash
  grep -rn "BatchRetryStaleReclaimSweeper\|MIN_STALE" backend/src/test/java   # 0건
  grep -rn "isScheduled\|staleTimeoutMinutes" backend/src/test/java           # Augment/Vlm 스위퍼만 매칭
  ```
- **영향**: clamp 상수를 낮추거나 `@PostConstruct` 등록 조건을 바꿔도 테스트가 초록으로 통과한다. 현재 구현 자체는 정상(정적 확인 + 실기동 로그로 확인).
- **수정 방향(제안)**: `VlmSubmitPendingSweeperTest`/`AugmentJobExpirySweeperTest` 와 동형으로 `BatchRetryStaleReclaimSweeperTest` 신설 — ①`stale-timeout-minutes=0/-1/5` 로 생성 시 `staleTimeoutMinutes() >= 30` ②`enabled=false` 면 `isScheduled()==false` ③`retryQueue.sweepStaleRetrying` 가 `Error` 를 던져도 `run()` 이 0 을 반환. ⚠ **구현하지 않는다.**

### [B-ISSUE-85] TC-BATCH-154 — 재처리 클레임의 2컬럼(raw 우선 → work 폴백) 조건부 UPDATE 에 직접 테스트가 없다
- **심각도**: LOW (커버리지 갭)
- **기대 동작(기대효과)**: `tryClaimReprocessFromFailed` 가 ①`LS_DATA_RAW` FAILED→PROCESSING 을 우선 클레임하고 ②실패 시 `LS_RAW_DATA_STATUS` FAILED→PROCESSING 을 클레임한다는 두 갈래가 회귀 테스트로 고정된다.
- **현재 동작(이슈 내용)**: `grep -rn "claimReprocessFromFailed" backend/src/test/java` → **0건**. `BatchReprocessServiceTest` 는 `transitionService.tryClaimReprocessFromFailed(...)` 를 Mockito 로 stub 할 뿐 실제 UPDATE 를 실행하지 않는다. `BatchReviewOwnedEntryGuardIT:225` 는 `releaseReprocessClaim` 을 **정리 목적**으로만 호출한다.
- **재현/확인 경로**: 위 grep. 실동작으로는 raw 경로(rawSn 128, raw=FAILED)만 확인되었고 **work 폴백 경로는 미검증**이다(raw≠FAILED + work=FAILED 인 영상이 현 DB 에 없음).
- **영향**: 두 컬럼 중 한쪽 술어가 바뀌어도 감지되지 않는다. 이중 실행 차단(CWE-362)의 최종 방어라 회귀 시 영향이 크다.
- **수정 방향(제안)**: Testcontainers IT 로 ①raw=FAILED/work=null → true·raw 가 PROCESSING 으로 전이 ②raw=COMPLETED/work=FAILED → true·work 만 전이 ③둘 다 아님 → false·전이 0건 ④동시 2호출 시 1건만 true 를 단언. ⚠ **구현하지 않는다.**

### [B-ISSUE-86] TC-BATCH-159 / 160 — local 프로파일에서 재시도 폴러가 미등록이라 큐의 등록→발화 전 사이클을 실동작으로 검증할 수 없다 (1차 B-ISSUE-85 이월)
- **심각도**: LOW (환경 구성 — dev/stg/prd 무영향)
- **기대 동작(기대효과)**: 검증 환경에서 `enqueueIfRetryable` → `pollReady`(CAS 클레임) → `orchestrator.process` 전 사이클과 2노드 클레임 직렬화를 관측할 수 있다.
- **현재 동작(이슈 내용)**: `BatchRetryTriggerConfig:19` 가 `@ConditionalOnProperty(prefix="authoring.batch", name="enabled", havingValue="true", matchIfMissing=true)` 인데 `application-local.yml:61-62` 가 `batch.enabled: false` → Quartz 트리거 미등록. 반면 배치 본체는 `MarkingBatchBridge → AsyncBatchRunner`(@Async) 로 구동되어 **재시도 적재는 계속 일어난다.**
  ```
  ls_bat_rty_wtng: 8|144|3|3|PENDING|…    ← 도래했으나 발화 0건
  기동 로그: Scheduler KlidAuthoringScheduler_$_NON_CLUSTERED started  (batchRetryTrigger 등록 로그 없음)
  ```
  런타임 실측 `SPRING_PROFILES_ACTIVE=local`, `BATCH_ENABLED` 미설정.
- **영향**: TC-BATCH-159/160 이 IT·정적 근거로만 판정된다(본 리포트도 그렇게 처리). 1차 대비 상태 변화 없음. 단 **stale 회수 스윕은 자기 토글이라 이 영향을 받지 않는다**(TC-BATCH-167 이 실동작으로 확인) — 무관 토글 종속 회피 설계가 실제로 작동함이 확인된 셈이다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -E "SPRING_PROFILES_ACTIVE|BATCH_ENABLED"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select * from ls_bat_rty_wtng;"
  docker logs klid-backend 2>&1 | grep -i "batchRetryTrigger"    # 0건
  ```
- **수정 방향(제안)**: 검증용 프로파일에서만 `BATCH_ENABLED=true` + 짧은 `BATCH_RETRY_INTERVAL_SEC` 로 기동하거나, 폴링 1 tick 을 강제하는 dev 트리거(`POST /v1/dev/batch/retry-tick`)를 두어 큐 사이클을 관측 가능하게 한다. ⚠ **구현하지 않는다.**

---

## 검증 중 발생시킨 상태 변화(전량 명시)

| 대상 | 무엇을 | 원복 여부 |
|---|---|---|
| `ls_data_src(78).anony/psdo/prvc_incl_yn` | B-ISSUE-81 재현을 위해 `Y/N/N` 로 설정 | **원복 완료** — 동일 API 로 3필드 전부 NULL 재설정, DB 확인 `78\|\|\|` |
| `ls_data_src(78)` 프레임 설명 | B-ISSUE-82 재현을 위해 `"gate probe"` 기록 | **원복 완료** — `description:null` 로 재설정, 응답 확인 |
| `ls_data_raw(128).data_stts_cd` | TC-BATCH-163 재현 — 클레임으로 `FAILED→PROCESSING`, 보상 롤백으로 복귀 | **자동 원복**(보상 롤백) — 최종 `FAILED` 확인 |
| `ls_deident_report(3)` / `ls_auth_work_lock(3)` / `ls_data_raw(133)` | resolve 409(fail-closed) 호출 | **변화 없음** — `OPEN` / `LOCKED` / `'F'` 유지 확인 |

그 외 `UPDATE`/`DELETE` 직접 실행 0건. 파생 412·중복 409·403·404·400 응답 경로는 정의상 상태를 만들지 않으며, `ls_deident_report` 신규 행이 생기지 않았음을 확인했다(sn 3·4·5 만 존재, 4·5 는 타 에이전트 생성분).

---

# B 클러스터 part6 (B-13~B-17) 2차 검증 결과

> 대상: `docs/test-cases/B-batch-deidentify.md` 의 B-13(34) · B-14(10) · B-15(11) · B-16(6) · B-17(8) = **69건**
> 취소선(폐기) 행: **0건** (본 5개 섹션에는 폐기 표기가 없다 — 집계 제외 대상 없음)
> 환경: backend `localhost:18081`(HEAD `ca3c712b`, 컨테이너 기동 `2026-07-30T17:43:08Z`, restarts=0) · mock-server `:9400` · postgres `:5432` 스키마 `public`
> 수행 시각: KST 2026-07-31 03:20~03:35 (mock 로그는 UTC = KST-9h)
> **파일 수정 0건 / 빌드·테스트 실행 0건 / backend 재기동 0건** (검증 중 소스·설정·테스트 무수정, 본 문서 1개만 신규 작성)
> ⚠ 검증 중 다른 에이전트가 같은 스택에서 파이프라인을 구동 중이었다(rawSn 145~150 신규 적재). 아래 실동작 증거는 **호출자 IP `172.18.0.5`(backend 컨테이너)** 를 확인한 것만 인용했다.

---

## 집계

| 섹션 | 케이스 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| B-13 KPST 위탁·폴링 | 34 | 31 | 0 | 3 | 0 | 0 | 0 |
| B-14 Quartz/인프라/헬스 | 10 | 10 | 0 | 0 | 0 | 0 | 0 |
| B-15 배치 스텝 tx 경계 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| B-16 오토라벨 일괄저장 | 6 | 6 | 0 | 0 | 0 | 0 | 0 |
| B-17 LS_DATA_RAW FK(V146) | 8 | 7 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **69** | **65** | **0** | **4** | **0** | **0** | **0** |

- 실동작 판정 **28건** / 정적 판정 **41건**. BLOCKED 0건(재기동이 필요한 단 하나의 분기 — Quartz 기동 거부 — 는 부팅 로그로 배선을 실측하고 거부 분기만 코드 조건 + 컨텍스트 refresh 테스트로 판정했다. 아래 TC-BATCH-173 비고).
- **신규 결함 1건(HIGH)**: `ls_data_raw` 직접 자식은 V146 로 CASCADE 가 완비됐으나 **`ls_data_src` 를 참조하는 손자 테이블(라벨 포함) 14종에 FK 가 0건** → 부모 삭제 시 라벨 고아가 실제로 늘어난다(실측 64 → 86).
- 나머지 3건(PARTIAL)은 **KPST 논블로킹화(commit `862ca6d8`)가 카탈로그 최신화(`119ea148`) 이후에 머지**되어 케이스 기대값이 낡은 것으로, 코드 쪽이 의도적 개선이다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **B-ISSUE-81** | Quartz `isClustered` 전 프로파일 false + 온프렘 템플릿도 false → 2노드 중복 발화 | `application-stg.yml:11`·`application-prd.yml:13` = `${QUARTZ_CLUSTERED:true}`, 공통 `application.yml:95` = `:false`(local/dev), `deploy/onprem/config/backend/env.template:226` = `true`. `QuartzClusteringGuard`(`@PostConstruct`)가 allowlist(`containsAll`) + `ENV` 독립축으로 fail-closed. 부팅 로그 실측 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]` → 가드가 실제로 배선·실행됨 | **해소** |
| **B-ISSUE-82** | 폴링 대상 조회에 원자 클레임 없음 + `finishDownloadAndComplete` 멱등 가드 없음 + 상한 없음 | 3축 모두 해소 — ①`claimForPoll`(리스 조건부 UPDATE, `LsDeidentProcLogRepository:81-92`) ②`claimDownloadCompletion`(완료 전이 자체가 클레임, `:112-125`) ③`findByPollSttsCdIn(statuses, Pageable)` 무제한 오버로드 제거(`:55`). **2세션 동시 UPDATE 실측 1행/0행**, 완료 클레임 재호출 실측 1행/0행 | **해소** |
| **B-ISSUE-42** | YOLO/SAM2 루프 내 개별 `save()`, IDENTITY PK 로 JDBC 배치 무력 | 개별 `save()` **0건**(YOLO/SAM2 모두 `AutoLabelBatchPersister.saveAll` 1회씩). `LsDataLbl:55`·`LsDataLblAiInfo:30` 은 여전히 `GenerationType.IDENTITY` → JDBC 배치는 비활성 유지 | **부분 해소(현상 유지)** — TC-BATCH-196 이 이 현상을 기대값으로 고정 |
| PIPE-ISSUE-01 (2차 파이프라인 구동) | `dev-seed.sql:52` 가 부팅마다 `DEV-CLIP-%` 삭제, 자식 `LS_DATA_LBL` 은 FK 부재로 고아 | V146 은 **`ls_data_raw` 직접 자식만** 다룬다. `ls_data_lbl.src_sn → ls_data_src` FK 는 **여전히 없다**(전수 조회 결과 `ls_data_src` 를 참조하는 FK **0건**). 고아 라벨 64행 그대로 잔존 | **미해소** → B-ISSUE-101 |

---

## ★ `ls_data_raw` 참조 FK 전수표 (`pg_constraint` 실측)

`SELECT ... FROM pg_constraint WHERE contype='f' AND confrelid='ls_data_raw'::regclass` → **28행**
(= V146 `child_specs` 27 + V146 4)절이 CASCADE 로 재생성한 `FK_LS_EVNT_ANNO_RAW` 1)

| # | 자식 테이블 | 컬럼 | FK | ON DELETE | 고아 위험 |
|---:|---|---|:--:|---|---|
| 1 | ls_bat_rty_wtng | raw_sn | O | CASCADE | 없음 |
| 2 | ls_batch_proc_log | data_raw_sn | O | CASCADE | 없음 |
| 3 | ls_data_lbl_ai_info | data_raw_sn | O | CASCADE | 없음 |
| 4 | ls_data_meta | raw_sn | O | CASCADE | 없음 |
| 5 | ls_data_src | raw_sn | O | CASCADE | 없음(단 **손자 유발** — 아래) |
| 6 | ls_deident_proc_log | data_raw_sn | O | CASCADE | 없음 |
| 7 | ls_marking | raw_sn | O | CASCADE | 없음 |
| 8 | ls_auth_work_lock | data_raw_sn | O | CASCADE | 없음 |
| 9 | ls_data_issue | data_raw_sn | O | CASCADE | 없음 |
| 10 | ls_data_meta_review | data_raw_sn | O | CASCADE | 없음 |
| 11 | ls_deident_report | data_raw_sn | O | CASCADE | 없음 |
| 12 | ls_raw_data_enrollment | raw_data_id | O | CASCADE | 없음 |
| 13 | ls_raw_data_status | raw_data_id | O | CASCADE | 없음 |
| 14 | ls_task_assign_history | raw_data_id | O | CASCADE | 없음 |
| 15 | ls_task_assignment | raw_data_id | O | CASCADE | 없음 |
| 16 | ls_task_event_log | raw_data_id | O | CASCADE | 없음 |
| 17 | ls_data_aug_rvw | data_raw_sn | O | CASCADE | 없음 |
| 18 | ls_data_raw_hstry | raw_sn | O | CASCADE | 없음 |
| 19 | ls_dataset_export | data_raw_sn | O | CASCADE | 없음 |
| 20 | ls_dataset_video_meta | raw_sn | O | CASCADE | 없음 |
| 21 | ls_label_version | data_raw_sn | O | CASCADE | 없음 |
| 22 | ls_control_notify_fallback | raw_sn | O | CASCADE | 없음 |
| 23 | ls_meta_repl_outbox | raw_sn | O | CASCADE | 없음 |
| 24 | ls_mon_noti_acml | raw_sn | O | CASCADE | 없음 |
| 25 | ls_portal_user_label | src_raw_sn | O | CASCADE | 없음 |
| 26 | ls_tus_upload | raw_sn | O | **SET NULL** | 없음(원장 보존, 의도) |
| 27 | ls_webhook_idempotency | raw_sn | O | **SET NULL** | 없음(원장 보존, 의도) |
| 28 | ls_evnt_anno | raw_sn | O | CASCADE | 없음(V146 4절이 NO ACTION→CASCADE 통일) |

**FK 가 없는 `raw_sn` 계열 컬럼 (전수)** — 전부 V146 이 명시 제외한 것이며 정상:

| 테이블/뷰 | 컬럼 | 제외 사유(V146:17-26) |
|---|---|---|
| ls_data_raw | raw_sn | 자기 PK |
| ls_data_raw | orgnl_raw_sn | 파생 계보 self-reference — 자동복구가 위험(파생본이 '원본'으로 승격) |
| ls_dataset_video_meta | orgnl_raw_sn | 승인 시점 **동결값** |
| mng_clip_schedule_que | raw_sn | **MNG_\* 관제 소유** — 선승인 필요 |
| v_completed_{video,frame,meta,label_change} | raw_sn / orgnl_raw_sn | 뷰 |

### ★ 진짜 구멍 — `ls_data_src` 를 참조하는 FK 는 **0건**

`SELECT ... WHERE confrelid='ls_data_src'::regclass` → **0행**. `src_sn`/`data_src_sn` 컬럼을 가진 테이블 **14종**(`ls_data_lbl` · `ls_data_lbl_ai_info` · `ls_data_lbl_hstry` · `ls_label_version` · `ls_data_aug` · `ls_data_aug_job_file` · `ls_data_aug_rvw` · `ls_data_issue` · `ls_data_meta_review` · `ls_auth_work_lock` · `ls_batch_proc_log` · `ls_data_src_hstry` · `ls_data_src` 자기참조)이 전부 무보호다.

**실증(트랜잭션 내 삭제 후 ROLLBACK — 데이터 무변경 확인 완료)**

```
BEGIN;
 before_src=3  before_lbl=22  before_orphan=64
 DELETE FROM ls_data_raw WHERE raw_sn=126;   -- DELETE 1
 after_src=0   after_status=0  after_export=0   ← 직접 자식은 CASCADE 정상
 after_orphan=86                               ← 고아 라벨 +22 (구조적 불가능 아님)
ROLLBACK;  → rollback_check_raw=1, rollback_check_src=3 (원상복구 확인)
```

---

## ★ 트랜잭션 경계 정적 스캔 결과

`BatchStep` 구현 **7종 전수**(`grep -rn "execute(BatchContext"`), `@Transactional` 실측:

| 빈 | `execute(BatchContext)` 위치 | 경계 | 내부 위임 | 자기호출? | 판정 |
|---|---|---|---|:--:|---|
| YoloAutolabelStep | `:156` | `@Transactional(controlTransactionManager, REQUIRES_NEW)` `:155` | `run(rawSn)` `:168` | 예(의도) | 정상 |
| Sam2SegmentStep | `:135` | REQUIRES_NEW `:134` | `run(rawSn, hints)` `:147` | 예(의도) | 정상 |
| TrackInterpolationStep | `:105` | REQUIRES_NEW `:104` | `run(rawSn)` `:116` | 예(의도) | 정상 |
| VlmTimeseriesStep | `:220` | REQUIRES_NEW `:219` — **readOnly 아님** | `runWithMarking` `:253` / `run` `:237`(readOnly) | 예(의도) | 정상 |
| FfmpegFrameExtractor | `:130` | REQUIRES_NEW `:129` | `extractByMarks` `:161`/`:176` | 예(의도) | 정상 |
| DeidentifyStep | `:240` | **무애노테이션(면제)** | `run(raw)` `:269` 를 **ObjectProvider 자기참조 프록시**로 호출 `:241` | 아니오(프록시) | 정상(중첩 방지) |
| MarkingLoadStep | `:50` | **무애노테이션(면제)** | 조회+JSON 파싱만, DML 0건 | — | 정상 |

- 1차 실사고 지점 `FfmpegFrameExtractor.execute()` 는 **경계가 복원**돼 있다(`:129`).
- 정적 드리프트 가드 `BatchStepTransactionBoundaryTest`(`:37-118`) 실재 — 클래스패스 스캔(`kr.co.cudo.authoring`) + 스캔 결과 ≥6 단언 + `execute` **미선언 시 `AssertionError`**(`:71-78`) + `propagation`·`value` 까지 단언. `DeidentifyStep` 에 애노테이션을 붙이면 **실패하는** 역방향 테스트(`:107-117`)까지 있다.
- 리포지토리 규약 확인: `LsDataSrcRepository.bumpLabelVersionIn`(`:292-295`)·`bumpLabelVersionByRawSn`(`:305-308`) 에 `@Transactional` **없음**(경계는 스텝이 제공) ✓
- **잘못된 자기호출 잔존 없음**: `KpstDeidentService.pollOne`/`completeDeidentification` 은 상태 전이를 전부 `txService.*`(별도 빈, REQUIRES_NEW) 로 cross-bean 위임한다. `DeidentFrameAttacher` 는 self-invoke 오버로드를 제거하고 3-arg 단일 진입점만 `@Transactional` 을 갖는다(`:93-94`).
  - ⚠ 다만 `KpstDeidentService:145-147` javadoc 이 *"`submit(...)` 자체가 REQUIRES_NEW 라"* 라고 적혀 있는데 **현재 `submit` 에는 `@Transactional` 이 없다**(논블로킹화 잔재). 실패 흔적은 `batchTransitionService.recordDeidentFailure`/`txService.failSubmit`(둘 다 cross-bean REQUIRES_NEW)로 커밋되므로 **동작상 문제는 없고 주석만 낡았다** — 결함으로 계수하지 않음.

---

## B-13 결과표 (KPST 비식별 위탁·폴링) — 34건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-DEID-060 | 위탁: createProject → procLog WAITING, 미전이 | PASS | [실동작] mock `POST /project` → `prj_id=20/21 name=raw149/raw150`(172.18.0.5). 같은 시각 `ls_deident_proc_log` 139/140 = `POLL_STTS=WAITING→POLLING`, `ls_data_raw(149,150).de_ident_yn='N'`·`data_stts_cd=PENDING` 유지. backend 로그 `[KpstDeid] submitted rawSn=149 prjId=20`(스레드 `kpst-submit-1`) | Phase C-2 로 **선커밋(`markKpstSubmitPending`) + 비동기 ACK(`claimSubmitAck`)** 로 분리됨. 케이스가 적은 `markKpstSubmitted(prjId)` 동기 호출은 없으나 결과(WAITING·prjId 기록·미전이)는 동일 |
| TC-DEID-061 | 위탁: 원본경로 부모 없음 → INVALID_INPUT(CWE-22) | **PARTIAL** | [정적] 가드는 존재(`KpstDeidentService:319-323` `parent==null` → `INVALID_INPUT`)하나 호출측 `:294-303` 이 **모든 `RuntimeException` 을 `EXTERNAL_API_ERROR`(502)로 재포장** → 관측 결과는 400 이 아님 | B-ISSUE-102 |
| TC-DEID-062 | 위탁: 실패 → 'F' 마킹+EXTERNAL_API_ERROR | **PARTIAL** | [정적] 논블로킹화로 `createProject` 실패는 **동기 예외를 던지지 않는다**(`:421-431` subscribe → `KpstSubmitOutcomeRecorder.onSubmitFailed` → `txService.failSubmit` = procLog FAILED + `'F'`). 동기 `EXTERNAL_API_ERROR` 는 **제출 이전 사전조건 실패**에만 남음(`:302`) | B-ISSUE-103 (카탈로그 기대값이 낡음) |
| TC-DEID-063 | 위탁: export 디렉터리 정리(stale 오회수 방지) | PASS | [정적] `cleanExportDir :488-527` — ①리졸버 **재계산 경로와 `equals`** 일 때만 진행(`:494-502`) ②`Files.list` 비재귀(`:508`) ③`isRegularFile(NOFOLLOW_LINKS)` 로 심링크·디렉터리 제외(`:511`) ④미존재/비디렉터리 no-op(`:504`) ⑤IOException 은 경로 미노출 WARN 후 위탁 진행 | 4중 가드 전부 확인 |
| TC-DEID-064 | 폴링: procState=2 전체완료(AND) → 다운로드+완료 | PASS | [실동작] procLog 135~138(rawSn 145~148) `WAITING→POLLING→DOWNLOADED/SUCCEEDED`, `ls_data_raw.de_ident_yn N→Y`·`data_stts_cd=MARKING_READY`. mock `GET /retrieve_progress` 반복 + `production completed prj_id=16/17`. [정적] `allDatasetsCompleted :966-978` 은 `allMatch(procState==2)` | AND 판정 확인 |
| TC-DEID-065 | 폴링: 터미널 실패(3/4/99) 우선 → 즉시 'F' | PASS | [정적] `anyDatasetFailed` 호출 `:587` 이 `allDatasetsCompleted` 호출 `:600` **앞**. REDEIDENT 는 `failRedeidentCompletion`(락 해제 포함), 그 외 `failPolling` | 타임아웃 대기 없이 종결 |
| TC-DEID-066 | 폴링: procState=99(오류 sentinel) 종결 | PASS | [정적] `PROC_STATE_TERMINAL_FAILED = {3,4,99}` `:94-98` | |
| TC-DEID-067 | 폴링: prjId null(위탁 미완) → 타임아웃 검사만 | **PARTIAL** | [정적] `:541-561` — 현재는 **ACK 대기 유예 skip**(외부 호출 0건·카운터 미소모 ✓)이고, 유예 초과 시 `failSubmit(ACK_MISSING_CODE)` 로 회수한다. `markTimeoutIfExpired` 는 **호출하지 않는다**(코드가 "구 코드처럼 부르면 예산만 소모"라고 명시) | B-ISSUE-104. "외부 미호출"은 충족, "markTimeoutIfExpired"는 의도적으로 폐기 |
| TC-DEID-068 | 폴링: retrieveProgress 예외 → 타임아웃 평가 | PASS | [정적] `:562-576` catch 안에서 `markTimeoutIfExpired` 호출 + 클래스명만 로그(CWE-209) | 무기한 stuck 차단 확인 |
| TC-DEID-069 | 폴링: 완료지만 fileName bad → 터미널 처리 | PASS | [정적] `:611-625` REDEIDENT=`failRedeidentCompletion`(락 해제) / 비REDEIDENT=`failPolling` | |
| TC-DEID-070 | 폴링: 산출물 무결성 실패 → 'F' | PASS | [정적] `:626-648` — `isUsableDeidFile` false → 유예 재확인 → 여전히 false 면 `failPolling`/`failRedeidentCompletion`. **'Y' 전이 경로 없음**(fail-open 아님) | |
| TC-DEID-071 | 폴링: 진행중 → 시도 증가+타임아웃 검사 | PASS | [실동작] `poll_atmpt_cnt` 관측 1→2→4→5 (30s 주기), `poll_last_dt` 매 틱 갱신. [정적] `:666-668` | |
| TC-DEID-072 | fileName 회수: {stem}-mask{ext} 변환(실측 계약) | PASS | [실동작] `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 8건 전부 `.../{rawSn}/deid/sample-cctv-1080p-mask.mp4`, 디스크 실재(126: 34,654,319B). [정적] `downloadResult :764-791`, `toMaskName :812-822`(이미 `-mask` 면 재부여 안 함) | `deidentified.mp4` 조합 아님 — 응답 기반 회수 확인 |
| TC-DEID-073 | fileName 회수 폴백: 단일 산출물 스캔 | PASS | [정적] `scanSingleUsable :833-857` — 1개=회수(base 하위 단언 `:847`) / 0개=null / 2개↑=`INVALID_INPUT`(모호) | |
| TC-DEID-074 | sanitizeFileName: basename만(CWE-22) | PASS | [정적] `:869-890` — `Paths.get` → `getFileName()`, `InvalidPathException`(NUL 등) 정규화, `/`·`\`·`..` 잔존 거부, **원문 미노출** | |
| TC-DEID-075 | 폴링 잡: 대상 없으면 noop | PASS | [정적] `KpstDeidentPollJob:95-98` DEBUG 후 return. [테스트] `KpstDeidentPollJobTest:대상이_없으면_외부호출없이_즉시종료한다` | |
| TC-DEID-076 | 폴링 잡: 건별 try/catch 격리 | PASS | [정적] `:110-114` 클래스명만. [테스트] `KpstDeidentPollJobTest:한_작업_폴링실패가_다른_작업을_막지_않는다` | |
| TC-DEID-077 | 폴링 잡: 동시 실행 금지는 같은 노드 한정 | PASS | [실동작] `qrtz_job_details.kpstDeidentPollJob.is_nonconcurrent='t'`. 노드 간 방어는 원자 클레임(TC-DEID-085)이 담당 — 본 검증에서 실증됨 | 1차 B-ISSUE-82 대체 방어 확인 |
| TC-DEID-078 | 폴링 잡: 재기동 복원(DB 조회) | PASS | [실동작] backend 로그 `[KpstDeidPoll] polling targets count=6` (인메모리 상태 없이 DB `findByPollSttsCdIn(statuses, page)` 로 재조회). [정적] `:93-94` · `LsDeidentProcLogRepository:55` | |
| TC-DEID-079 | completeDeidentification: 파일무효 F-마킹 보정 | PASS | [정적] `KpstDeidentService:898-911`(catch → `isRedeidentLog` 아니면 `txService.markRawDeidentFailed`) · `KpstDeidentTxService:269-273`(REQUIRES_NEW) | 메인 tx 롤백에 안 휩쓸림 |
| TC-DEID-080 | KPST 조건부 빈: kpst.deid.enabled=false 미등록 | PASS | [정적] `@ConditionalOnProperty` 3곳 — `KpstDeidentService:69` · `KpstDeidentTxService:37` · `KpstDeidentPollTriggerConfig:20`. `application.yml:462` = `${KPST_DEID_ENABLED:true}` | 로컬은 true(빈 활성) — false 형상은 `DeidentifyStepKpstDisabledIntegrationTest` 커버 |
| TC-DEID-081 | ★위탁 전 원본 실재 가드 | PASS | [실동작] rawSn 127/128 = `ls_deident_proc_log` 117/118 `PROC_STTS=FAILED, ERR_CD=KPST_SOURCE_MISSING`(**커밋됨**), `ls_data_raw.de_ident_yn='F'`, `data_stts_cd` 는 MARKING_READY 미전이. mock 로그에 raw127/raw128 프로젝트 생성 **없음**(createProject 미호출). [정적] `:286`(호출) · `:446-466`(`verifySourceOrFail`, `recordDeidentFailure` REQUIRES_NEW 후 `INVALID_INPUT`) — 로그·예외에 경로 원문 없음 | 구 결함(18B 스텁 'Y') 회귀 없음 |
| TC-DEID-082 | 원본 가드 비활성 시 침묵 금지 | PASS | [정적] `:447-451` WARN 1줄(rawSn 만) 후 통과 | 런타임은 `verify-source-exists=true`(기본)라 이 분기 미실행 |
| TC-DEID-083 | ★산출물 무결성 판정 단일 원천 | PASS | [정적] `KpstDeidentService:692-694` → `DeidentArtifactIntegrity.isValidVideoArtifact`(`:87-110`) = 정규파일(NOFOLLOW) + `MIN_VIDEO_BYTES=512`(`:48`) + 컨테이너 시그니처. [테스트] `DeidentArtifactIntegrityTest` **11건**(18B placeholder 거부·심링크 거부·MPEG-TS 오판 거부·`junk` 박스 제외 등) | 구 "존재+>0바이트" 폐기 확인 |
| TC-DEID-084 | 무결성 실패 시 1회 유예 재확인 | PASS | [정적] `recheckAfterGrace :707-730` — `fileExists` false(=파일 자체 부재)면 **유예 없이 즉시 null**(`:709`), 있으면 `Math.min(설정, MAX_RESULT_RECHECK_DELAY_MS=5000)` 대기 후 **1회** 재산출·재판정 | clamp 상한 확인(`:138`, `:713`) |
| TC-DEID-085 | ★폴링 대상 원자 클레임(리스) | PASS | [실동작] **2세션 동시 실행 재현** — 세션A `UPDATE 1` / 세션B `UPDATE 0`(A 커밋 대기 후 갱신본으로 WHERE 재평가). 쿼리는 `claimForPoll`(`LsDeidentProcLogRepository:81-92`) 원문 그대로. 잡 배선 `KpstDeidentPollJob:101-109`, tx `KpstDeidentTxService:60-66`(REQUIRES_NEW) | `FOR UPDATE SKIP LOCKED` 미채택 사유(외부 I/O 가 tx 밖)도 주석에 명시 |
| TC-DEID-086 | 클레임 술어 fail-closed | PASS | [실동작] 같은 행을 `POLL_STTS_CD='FAILED'` 로 전이시킨 뒤 클레임 시도 → **0행**. 술어 `POLL_STTS_CD IN ('WAITING','POLLING')`(`:87`) | |
| TC-DEID-087 | 리스 길이 = 폴링 주기 − 5s(하한 1s) | PASS | [실동작] `poll-interval-sec=30` → 리스 25s, 단일 노드가 **매 틱(30s) 재클레임** 관측(`poll_last_dt` 03:24:02 → 03:24:32, `poll_atmpt_cnt` +1). [정적] `KpstDeidentPollJob:72-74, 126-128` | |
| TC-DEID-088 | ★완료 전이 자체가 클레임 | PASS | [실동작] `claimDownloadCompletion` SQL 을 같은 tx 에서 2회 실행 → **1행 / 0행**(ROLLBACK). 후처리(프레임 attach·Y 전이·락 해제·알림)는 1행 얻은 호출만 진입(`KpstDeidentTxService:209-213`) | 프레임 이중 attach 창 없음 |
| TC-DEID-089 | 완료 후처리 실패 시 클레임도 롤백 | PASS | [정적] 클레임 UPDATE 가 완료 트랜잭션(`finishDownloadAndComplete`, REQUIRES_NEW `:206`) **안**에 있어 후처리 예외 시 함께 롤백 → 재폴링 대상 유지 | |
| TC-DEID-090 | 틱당 대상 상한 + 기아 방지 정렬 | PASS | [정적] `pollPage() :119-123` — `poll-batch-size` 기본 200(`application.yml:475`), `<1` 이면 `DEFAULT_BATCH_SIZE`, `Sort.asc(pollLastDt).nullsFirst()` + `procLogSn asc`. [실동작] `polling targets count=6` | 무제한 오버로드 자체가 리포지토리에 없음 |
| TC-DEID-091 | 회수 디렉터리 2-way | PASS | [정적] `recoveryDirs :797-802` — `deidVideoDirQuietly`(신, Optional) 우선 + `{deid_base}/videos/{rawSn}`(구) 항상 추가, `LinkedHashSet` 중복 제거 | 신 위치 도출 실패해도 구 위치 시도 확인 |
| TC-DEID-092 | 1차 mask 경로 miss → 폴백 회수 시 WARN | PASS | [정적] `:785` `primary mask path miss — recovered by fallback scan rawSn={}`(경로 원문 없음) | |
| TC-DEID-093 | DeidentFrameAttacher 도 동일 무결성 판정 사용 | PASS | [정적] `DeidentFrameAttacher:160-162` `isUsable()` → `DeidentArtifactIntegrity.isValidVideoArtifact`, 호출 `:99` | 판정 두 벌 없음 |

---

## B-14 결과표 (Quartz 클러스터링 / 인프라 / 헬스) — 10건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-170 | Quartz JobStore = controlDataSource(PG) | PASS | [실동작] `qrtz_*` 테이블이 `klid_system` 에 실재하고 `qrtz_triggers` 3행(`kpstDeidentPollTrigger`=ACQUIRED, `datasetExportPendingSweepTrigger`·`datasetExportFailureRecoveryTrigger`=WAITING). [정적] `QuartzConfig:30-37` `setDataSource(controlDataSource)` · `application.yml:78,85-86` `job-store-type: jdbc` + `PostgreSQLDelegate` + `useProperties:'true'` | |
| TC-BATCH-171 | ★2노드 A-A: 동일 잡 중복 발화 방지 | PASS | [정적] `application-stg.yml:11` · `application-prd.yml:13` = `${QUARTZ_CLUSTERED:true}` / 공통 `application.yml:95` = `:false` / `env.template:226`=true. [테스트] `QuartzClusteringConfigGuardTest`(yml·템플릿 5건) | 로컬은 단일 노드 형상이라 `isClustered=false`·`instance_name=NON_CLUSTERED` 가 **정상**(UNCERTAINTIES #8) — 결함 아님 |
| TC-BATCH-172 | AsyncBatchRunner: 예외 삼킴(@Async) | PASS | [정적] `AsyncBatchRunner:21-35` — `@Async("batchAsyncExecutor")`, catch 에서 ERROR 로그 후 정상 종료. `SKIPPED` 는 WARN 으로 별도 노출 | |
| TC-BATCH-173 | ★클러스터링 fail-closed — 기동 거부 | PASS | [실동작] 부팅 로그 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]`(02:43:31, `QuartzClusteringGuard`) → `@PostConstruct` 가 실제 배선·실행됨을 확인. [정적] `:64-75`(check) → `:85-95`(verify) 가 `IllegalStateException` 을 던짐(WARN 아님) | ⚠ **거부 분기 자체는 재기동이 필요해 실행하지 않았다**(임무 규칙: backend 재기동 금지). `QuartzClusteringBootGuardTest` 가 컨텍스트 refresh 로 5건 커버(prd/stg/ENV=prd 기동 실패, local/dev 정상) |
| TC-BATCH-174 | 판정은 allowlist `containsAll` | PASS | [정적] `SINGLE_NODE_PROFILES={local,dev}` `:53`, `singleNodeAllowed :98-103` = `!activeProfiles.isEmpty() && SINGLE_NODE_PROFILES.containsAll(activeProfiles)` → `local,prd`·`prd1`·`LOCAL`·미지정 전부 거부. [테스트] `QuartzClusteringGuardTest:프로파일이_혼합되거나_오타면_엄격하게_판정한다` | denylist 아님 확인 |
| TC-BATCH-175 | ENV 배포 표식이 독립 축 | PASS | [정적] `DEPLOYED_ENV_MARKERS={stg,prd}` `:56`, `deployedEnvMarker != null` 이면 즉시 거부(`:99-101`, `:106-112`, trim+lowercase). [테스트] `QuartzClusteringBootGuardTest:dev_프로파일이라도_배포표식_ENV가_prd면_기동이_실패한다` | 프로파일 하향 우회 불가 |
| TC-BATCH-176 | 값 출처는 Quartz 실 프로퍼티 | PASS | [정적] `KEY_CLUSTERED = "spring.quartz.properties.org.quartz.jobStore.isClustered"` `:50` 을 `environment.getProperty(...)` 로 직접 읽음 `:66` | 별도 플래그 발명 없음 |
| TC-BATCH-177 | 클러스터링 ≠ 잡 내부 레이스 방어 | PASS | [실동작] 로컬은 `isClustered=false` 인데도 **원자 클레임이 동시 2세션을 1/0 으로 분리**(TC-DEID-085/088 실증) → 두 방어가 독립임을 실측. [정적] `QuartzClusteringGuard:40-41` javadoc | 상호 대체 불가 확인 |
| TC-BATCH-178 | ⚠ batchAsyncExecutor 는 공유 싱글턴 | PASS | [정적] `AsyncConfig:35-51` core2/max4/queue50/CallerRunsPolicy. `@Async("batchAsyncExecutor")` 사용처 **9빈** — `AsyncDeidentifyRunner` · `AsyncBatchRunner` · `AsyncAugmentFrameRunner` · `AsyncDatasetExportRunner`(3메서드) · `AsyncResolutionRunner` · `AugmentRequestBridge` · `AsyncVideoMetaRunner` · `VlmWithheldResumeRunner` · `DevPipelineRunner` | 케이스 서술(비식별·배치·증강·export·해상도·VLM재개·영상메타)과 정확히 일치 |
| TC-BATCH-179 | 비식별 헬스체크 3분기 · root 핑 | PASS | [실동작] `GET /api/actuator/health`(REVIEWER) → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`. 같은 시각 mock 인바운드 `172.18.0.5 - "GET / HTTP/1.1" 200 OK`(`/health` 아님). [정적] 3분기 `:78-112`, 예외는 `e.getClass().getSimpleName()` 만 | mock 분기(mode=mock)·unconfigured(DOWN) 는 정적 확인 |

---

## B-15 결과표 (배치 스텝 트랜잭션 경계) — 11건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-180 | YOLO execute 경계 — 프록시 호출 시 트랜잭션 개시 | PASS | [실동작] rawSn 126 전 프레임 `ls_data_src.lbl_ver=2`(65/66/67) — `bumpLabelVersionIn`(`@Modifying`) 이 무-트랜잭션이면 "Executing an update/delete query" 로 실패했을 것. [정적] `YoloAutolabelStep:155-158`. [테스트] `YoloStepTransactionBoundaryIntegrationTest`(**`@Transactional` 미부착** `@SpringBootTest`) | 1차 실사고 회귀 없음 |
| TC-BATCH-181 | SAM2 execute 경계 | PASS | [정적] `Sam2SegmentStep:134-136` REQUIRES_NEW. [실동작] 폴리곤 라벨이 실제 커밋됨(126 라벨 19건 중 SAM2 분) | |
| TC-BATCH-182 | TrackInterpolation execute 경계 | PASS | [정적] `TrackInterpolationStep:104-106` REQUIRES_NEW | stale 삭제+재생성이 한 tx |
| TC-BATCH-183 | VLM execute 경계는 쓰기 가능(readOnly 아님) | PASS | [정적] `VlmTimeseriesStep:219-227` — `@Transactional(controlTransactionManager, REQUIRES_NEW)` **readOnly 없음**. `run` 만 `readOnly=true`(`:236`), `runWithMarking` 은 쓰기(`:252`). [실동작] `ls_marking(126).status=VLM_COMPLETED` 전이 저장됨 | 상한 규약 충족 |
| TC-BATCH-184 | FRAME_EXTRACT execute 경계 | PASS | [실동작] `LS_DATA_SRC` 126: 3행 INSERT(원본·비식별 2벌 경로 상이). [정적] `FfmpegFrameExtractor:129-131` | 1차 실사고 지점 복원 확인 |
| TC-BATCH-185 | 내부 위임은 자기호출 — 중첩 없음 | PASS | [정적] `FfmpegFrameExtractor:120-128` javadoc + `:143` `extractByMarks(...)` **`this.` 자기호출** → 어드바이스 미적용 → 스텝 1건=tx 1건. VLM/YOLO/SAM2/보간 동일 패턴 | 프록시 경유로 바꾸면 REQUIRES_NEW 2회 |
| TC-BATCH-186 | typed 메서드의 REQUIRES_NEW 는 보존 | PASS | [정적] `YoloAutolabelStep:167-168` · `Sam2SegmentStep:146-147` · `TrackInterpolationStep:115-116` · `VlmTimeseriesStep:236-237,252-253` · `FfmpegFrameExtractor:160-161,175-176` 전부 애노테이션 유지 | dev 트리거 직접 호출 진입점 보호 |
| TC-BATCH-187 | DeidentifyStep 면제(자기참조 프록시) | PASS | [정적] `DeidentifyStep.execute :240` 무애노테이션 + `:241` ObjectProvider 프록시로 `run()` 호출. `BOUNDARY_EXEMPT`(`BatchStepTransactionBoundaryTest:53`) 에 등재. **역방향 테스트**(`:107-117`)가 애노테이션 추가를 실패시킴 | 중첩 방지 확인 |
| TC-BATCH-188 | MarkingLoadStep 면제(DML 0건) | PASS | [정적] `MarkingLoadStep:50` 무애노테이션, `BOUNDARY_EXEMPT` 등재 | |
| TC-BATCH-189 | ★정적 드리프트 가드 — 신규 스텝 경계 누락 차단 | PASS | [정적] `BatchStepTransactionBoundaryTest:37-118` — `ClassPathScanningCandidateComponentProvider` + `AssignableTypeFilter(BatchStep)` 로 `kr.co.cudo.authoring` 전수 스캔(`:55-69`), 스캔 결과 **≥6 단언**(`:85`, 공허 단언 방지), `execute` **미선언 시 `AssertionError`**(`:71-78`), `propagation`·`value` 까지 단언 | 실행 결과는 `test-baseline.md`(backend 4,367 / 실패 0)로 대조 — **본 검증에서 테스트 실행 안 함** |
| TC-BATCH-190 | `bumpLabelVersionIn` 에 `@Transactional` 미부착 | PASS | [정적] `LsDataSrcRepository:283-295` — 규약 주석 + `@Modifying`+`@Query` 만, `@Transactional` 없음. 동일 규약 `bumpLabelVersionByRawSn:305-308` | |

---

## B-16 결과표 (오토라벨 일괄저장) — 6건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-191 | 프레임 단위 2단 saveAll(라벨→AI메타) | PASS | [정적] `AutoLabelBatchPersister:61-85` = `lblRepository.saveAll` 1회(`:72`) + `aiInfoRepository.saveAll` 1회(`:83`). 호출부 `YoloAutolabelStep:328` · `Sam2SegmentStep:207`. **`lblRepository.save(` / `aiInfoRepository.save(` 개별 호출 grep 0건**(Yolo·Sam2·YoloLabelPersister·TrackInterpolation 전부) | [실동작] 126 오토라벨 19건 정상 적재 |
| TC-BATCH-192 | AI 메타는 저장된 라벨의 자기 lblSn/srcSn 사용 | PASS | [정적] `:78-82` — `saved.get(i)` 에서 `getLblSn()`/`getSrcSn()` 직접 읽어 `LsDataLblAiInfo.create` | 인덱스 대응 계약 주석 `:30-34` |
| TC-BATCH-193 | size 불일치 즉시 실패 | PASS | [정적] `:73-76` `saved == null \|\| saved.size() != pending.size()` → `INTERNAL_ERROR("자동 라벨 일괄 저장 결과 개수 불일치")` | 조용한 오염 대신 실패 |
| TC-BATCH-194 | pending 비면 리포지토리 미호출 | PASS | [정적] `:65-67` early return 0 | |
| TC-BATCH-195 | 신뢰도는 PendingLabel 로 별도 전달 | PASS | [정적] `record PendingLabel(LsDataLbl label, BigDecimal score)` `:47`, AI 메타에 `pending.get(i).score()`(원본) 사용 `:81` — 엔티티 `clampScore` 보정값 되읽기 아님 | |
| TC-BATCH-196 | IDENTITY PK 유지 — 실제 JDBC 배치는 여전히 비활성 | PASS | [정적] `LsDataLbl:55` · `LsDataLblAiInfo:30` 모두 `@GeneratedValue(strategy = GenerationType.IDENTITY)` → `hibernate.jdbc.batch_size` 무효. 클래스 주석 `:22-28` 이 이 한계를 명시 | B-ISSUE-42 **부분 해소** 상태를 기대값으로 고정한 케이스 — 현상 일치 |

---

## B-17 결과표 (LS_DATA_RAW 참조 무결성 FK · V146) — 8건

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-200 | FK 27개 생성(CASCADE 25 / SET NULL 2) | PASS | [실동작] `pg_constraint` 조회 = **28행**(V146 `child_specs` 27 + `FK_LS_EVNT_ANNO_RAW`). 27건 내역: 배치 7 · 작업/검수 9 · 버전/증강/데이터셋 5 · 관제 3 · 포털 1 · 원장/세션 2 = 25 CASCADE + 2 SET NULL. 명명 규칙 `fk_{테이블}_raw` 전건 일치. `flyway_schema_history` V146 `success=t` | 위 전수표 참조 |
| TC-BATCH-201 | 부모 삭제 시 자식 CASCADE | **PARTIAL** | [실동작] 직접 자식은 정상 CASCADE(`ls_data_src` 3→0, `ls_raw_data_status`·`ls_dataset_export` 0). 그러나 **고아 라벨 64 → 86 (+22)** — `ls_data_lbl.src_sn → ls_data_src` FK 부재. "고아가 구조적으로 불가능" 미성립 | **B-ISSUE-101** |
| TC-BATCH-202 | 원장·세션은 SET NULL | PASS | [실동작] `fk_ls_tus_upload_raw`·`fk_ls_webhook_idempotency_raw` = `confdeltype='n'`(SET NULL). [정적] V146:73-74, 근거 주석 `:147-150` | 행 보존 확인 |
| TC-BATCH-203 | ★뷰 공급 7테이블 고아 시 중단 | PASS | [정적] `view_feed_tables` 7종(`:77-85` = `ls_dataset_video_meta`·`ls_raw_data_status`·`ls_dataset_export`·`ls_deident_proc_log`·`ls_data_src`·`ls_data_meta`·`ls_data_meta_review`), `RAISE EXCEPTION`(`:112-117`)이 **삭제 전 1패스**에서 발생. [테스트] `LsDataRawOrphanCleanupIT:데이터마트_뷰_공급_테이블에_고아가_있으면_자동삭제하지_않고_중단한다` | 현재 DB 고아 0건이라 분기 미발현 |
| TC-BATCH-204 | 한 테이블 고아 1000건 초과 시 중단 | PASS | [정적] `max_orphans CONSTANT BIGINT := 1000`(`:87`), `IF cnt > max_orphans THEN RAISE EXCEPTION`(`:107-110`) — **뷰 공급 검사보다 앞**에서 평가 | 테스트 미커버(해당 IT 2건은 뷰 공급 분기만) |
| TC-BATCH-205 | MNG_* · ORGNL_RAW_SN 제외 | PASS | [실동작] `mng_clip_schedule_que.raw_sn` FK 없음, `ls_data_raw.orgnl_raw_sn`·`ls_dataset_video_meta.orgnl_raw_sn` FK 없음(전수 조회 확인). [정적] V146:17-26 제외 근거 | 관제 선승인 경계 준수 |
| TC-BATCH-206 | 3패스 구조(실태조사 → 고아 정리 → FK 생성) | PASS | [정적] 1패스 `:98-119`(`RAISE NOTICE 'V146 고아 발견'` + 2종 EXCEPTION) → 2패스 `:126-143`(SET NULL 은 UPDATE, 나머지 DELETE + `RAISE NOTICE`) → 3패스 `:152-162`(멱등 ADD CONSTRAINT). [실동작] 27 spec 전건 고아 재조사 결과 **TOTAL ORPHANS=0** | |
| TC-BATCH-207 | 멱등 — 재실행 시 기존 FK 재생성 안 함 | PASS | [실동작] 3패스 로직(`NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname=fk)`)을 트랜잭션 안에서 재실행 → `recreated=0` 후 ROLLBACK, 오류 0건 | |

---

## 근거 드리프트

카탈로그 최신화 커밋 `119ea148`(07-30) **이후**에 `862ca6d8`(외부연동 제출 3곳 논블로킹화)·`46f47cee` 등이 머지되어 `KpstDeidentService` 가 약 150줄 늘었다. B-13 의 `file:line` 은 그 이전 스냅샷 기준이라 **대부분 어긋난다**(동작 판정에는 영향 없음 — 코드는 Grep 으로 재추적했다).

**명확한 드리프트 29건** (5줄 이상 어긋나거나 다른 구문을 가리키는 것만 계수)

| TC | 카탈로그 표기 | 실제 위치 |
|---|---|---|
| TC-DEID-060 | KpstDeidentService.java:218-278 | `:247-306`(submit) |
| TC-DEID-061 | :235-241 | `:318-323`(parent null) |
| TC-DEID-062 | :279-283 | 동기분 `:294-303` / 비동기 실패는 `KpstSubmitOutcomeRecorder` |
| TC-DEID-063 | :342-390 | `:488-527`(cleanExportDir) |
| TC-DEID-064 | :438-490 | `:600-664` |
| TC-DEID-065 | :425-435 | `:587-598` |
| TC-DEID-067 | :395-399 | `:541-561` |
| TC-DEID-068 | :401-415 | `:562-576` |
| TC-DEID-069 | :445-462 | `:611-625` |
| TC-DEID-070 | :464-486 | `:626-648` |
| TC-DEID-071 | :505-506 | `:666-668` |
| TC-DEID-072 | :636-655,693-720 | `:764-791`(downloadResult), `:812-822`(toMaskName) |
| TC-DEID-073 | :657-690 | `:833-857` |
| TC-DEID-074 | :693-720 | `:869-890` |
| TC-DEID-079 | :722-746 · KpstDeidentTxService:116-120 | `:898-911` · TxService `:269-273` |
| TC-DEID-081 | :225,300-320 | `:286` · `:446-466` |
| TC-DEID-082 | :302-305 | `:447-451` |
| TC-DEID-083 | :516-518 | `:692-694` (DeidentArtifactIntegrity 43-108 → `:47-110`, 경미) |
| TC-DEID-084 | :464-486,531-556 | `:626-633`, `:707-730` |
| TC-DEID-088 | KpstDeidentTxService:78-95 | `:195-223` |
| TC-DEID-089 | KpstDeidentTxService:78-95 | `:206-223` |
| TC-DEID-091 | :621-634 | `:797-802` |
| TC-DEID-092 | :601-616 | `:782-786` |
| TC-DEID-093 | DeidentFrameAttacher.java:73-93 | `:99`(호출) · `:152-162`(isUsable) |
| TC-BATCH-179 | DeidentifyHealthIndicator.java:14-118 | 파일 전체가 **114줄** — 클래스 `:41-113`, health() `:75-113` |
| TC-BATCH-183 | VlmTimeseriesStep.java:128-135 | `:219-227`(execute) |
| TC-BATCH-186 | VlmTimeseriesStep.java:150-166 | `:236-257` |
| TC-BATCH-206 | V146...sql:96-145 | `:95-162` |
| TC-BATCH-207 | V146...sql:118-145 | `:152-162` |

**정확했던 근거(참고)**: TC-DEID-085/086/087/090(`KpstDeidentPollJob`·`LsDeidentProcLogRepository` 전건 일치), TC-DEID-075~078, TC-BATCH-171·174·175·176·189·190·191~196·200·201·202·204·205, B-15 의 YOLO/SAM2/보간/FRAME_EXTRACT 경계 라인.
**경미 오차(1~4줄, 미계수)**: TC-DEID-066(92-95→94-98) · TC-DEID-080(67→69) · TC-DEID-086(84-89→86-88) · TC-BATCH-170(31-38→30-37) · TC-BATCH-172(22-34→21-35) · TC-BATCH-178(32-47→35-51) · TC-BATCH-187(232-241→236-241) · TC-BATCH-202(71-73→73-74) · TC-BATCH-203(76-84,111-114→77-85,112-117).

---

## 이슈 상세

### [B-ISSUE-101] TC-BATCH-201 — `ls_data_src` 를 참조하는 손자 테이블에 FK 가 0건이라 부모 영상 삭제 시 라벨이 고아로 남는다

- **심각도**: HIGH (데이터 정합 — 조용히 누적되고 집계·마이그레이션·통계를 틀리게 만든다)
- **기대 동작(기대효과)**: TC-BATCH-201 — *"자식 행이 함께 삭제되어 **고아가 구조적으로 불가능**해진다."* V146 의 선언 목적(`V146__add_ls_data_raw_child_fk.sql:5-7`)도 *"영상 원본 행이 사라져도 자식(마킹·프레임·상태·비식별로그·작업배정 등)이 고아로 잔존했다 … 고아는 조용히 집계·뷰·export 를 틀리게 만드는 유형"* 이다.
- **현재 동작(이슈 내용)**: V146 은 **`ls_data_raw` 를 직접 참조하는 27개 테이블만** 다룬다. 그런데 라벨 도메인의 실제 부모는 `ls_data_src`(프레임)이고, **`ls_data_src` 를 참조하는 FK 는 DB 전체에 0건**이다.

  ```sql
  -- 0행
  SELECT cl.relname, con.conname FROM pg_constraint con
    JOIN pg_class rf ON rf.oid=con.confrelid JOIN pg_class cl ON cl.oid=con.conrelid
   WHERE con.contype='f' AND rf.relname='ls_data_src';
  ```

  `src_sn`/`data_src_sn` 컬럼을 보유하고도 FK 가 없는 테이블 **14종**: `ls_data_lbl` · `ls_data_lbl_ai_info` · `ls_data_lbl_hstry` · `ls_label_version` · `ls_data_aug` · `ls_data_aug_job_file` · `ls_data_aug_rvw` · `ls_data_issue` · `ls_data_meta_review` · `ls_auth_work_lock` · `ls_batch_proc_log` · `ls_data_src_hstry` · `ls_data_src`(자기참조).

  따라서 `DELETE FROM ls_data_raw` → `ls_data_src` 는 CASCADE 로 사라지지만 **그 프레임에 달린 라벨은 그대로 남는다.**
- **재현/확인 경로** (실행 완료 — 트랜잭션 ROLLBACK 으로 데이터 무변경 확인):

  ```sql
  BEGIN;
    SELECT count(*) FROM ls_data_lbl l
      WHERE NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn=l.src_sn);   -- 64
    DELETE FROM ls_data_raw WHERE raw_sn=126;                                    -- DELETE 1
    SELECT count(*) FROM ls_data_src WHERE raw_sn=126;                           -- 0  (CASCADE 정상)
    SELECT count(*) FROM ls_data_lbl l
      WHERE NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn=l.src_sn);   -- 86 (+22 고아)
  ROLLBACK;
  ```

  현행 DB 의 고아 라벨 **64행**은 `PIPE-ISSUE-01`(`backend/src/main/resources/db/seed/dev-seed.sql:52` 의 `DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'`)이 부팅마다 남긴 실물이며, V146 적용 이후에도 **줄지 않았다**(V146 의 고아 정리는 `ls_data_raw` 직접 자식만 스캔한다 — 27 spec 재조사 결과 `TOTAL ORPHANS=0`).
- **영향**:
  - 라벨 통계·품질검사·버전 스냅샷 집계가 삭제된 영상의 라벨을 계속 센다.
  - `LBL_SN` 을 참조하는 12+ 모듈(증강 라벨맵·해상도 파생·포털·export 해시·버전 롤백의 LBL_SN 보존 복원)이 존재하지 않는 프레임을 가리키는 행을 만날 수 있다.
  - 장차 `ls_data_src` 에 FK 를 추가하려 할 때 고아가 누적된 만큼 마이그레이션이 중단(V146 과 같은 fail-closed)되어 배포가 막힌다 — 지금 64행이 매 부팅 증가한다.
  - CWE 해당 없음(무결성/데이터 품질). 보안 영향은 없다.
- **수정 방향(제안)**: ① V146 과 같은 3패스 구조로 `ls_data_src` 자식 FK(`ON DELETE CASCADE`) 마이그레이션을 별건 신설하되, 뷰 공급 테이블(`ls_data_lbl_hstry` → `V_COMPLETED_LABEL_CHANGE`, `ls_data_meta_review` → `V_COMPLETED_META`)은 V146 과 동일하게 **고아 발견 시 중단**. ② 선행 조건으로 `PIPE-ISSUE-01`(부팅 시드 전삭제)을 먼저 없애야 고아가 다시 쌓이지 않는다. ⚠ **구현하지 않는다.**

### [B-ISSUE-102] TC-DEID-061 — 위탁 사전조건 위반(CWE-22 경로 가드 포함)이 `INVALID_INPUT`(400) 대신 `EXTERNAL_API_ERROR`(502) 로 마스킹된다

- **심각도**: LOW (오류 분류 정확도 — 실경로 도달 가능성이 낮다)
- **기대 동작(기대효과)**: TC-DEID-061 — 원본 경로에 부모 디렉터리가 없으면 `INVALID_INPUT`. `ErrorCode.INVALID_INPUT` = `HttpStatus.BAD_REQUEST`(`ErrorCode.java:6`). 입력 문제이므로 400 이어야 하고, 외부 API 장애(502)와 구분돼야 운영이 원인을 가른다.
- **현재 동작(이슈 내용)**: 가드 자체는 존재하나 호출측이 **모든 `RuntimeException` 을 재포장**한다.

  ```java
  // KpstDeidentService.java:291-303
  try {
      projectReq = buildProjectRequest(raw, rawSn);          // 내부에서 INVALID_INPUT / INTERNAL_ERROR 를 던짐
  } catch (RuntimeException e) {
      txService.failSubmit(procLogSn, rawSn, SUBMIT_FAILED_CODE, e.getClass().getSimpleName());
      log.error("[KpstDeid] submit prepare failed rawSn={} errType={}", rawSn, e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 위탁 실패", e);   // ← 502 로 덮임
  }
  ```

  `buildProjectRequest` 안의 `INVALID_INPUT` 3종(빈 경로 `:315-317`, 부모 없음 `:320-323`, 디렉터리 생성 실패 `INTERNAL_ERROR` `:333-336`)이 전부 502 로 나온다. 대비되게 **원본 실재 가드**(TC-DEID-081)는 `try` **이전**(`:286`)에 있어 `INVALID_INPUT` 이 그대로 보존된다 — 같은 성격의 두 가드가 서로 다른 코드로 나가는 비대칭이다.
- **재현/확인 경로**: `ls_data_raw.raw_file_path_nm` 을 부모 없는 상대명(예: `video.mp4`)으로 두고 위탁. 단 `verifySourceOrFail` 이 먼저 파일 실재를 보므로, 이 분기에 도달하려면 프로세스 CWD 에 같은 이름의 파일이 실재해야 한다 → **실경로 도달 가능성 낮음**(그래서 LOW).
- **영향**: 운영 알림/대시보드에서 "입력 오류"가 "외부 비식별 서버 장애"로 분류된다. 또한 사전조건 실패인데 `ERR_CD=KPST_SUBMIT_FAILED`(외부 호출 실패 코드)로 원장에 남아 `KPST_SOURCE_MISSING` 과 달리 원인 식별이 안 된다. 보안 영향 없음(경로 원문은 노출되지 않는다).
- **수정 방향(제안)**: `catch (CustomException ce)` 를 분리해 사전조건 계열은 원래 `ErrorCode` 로 재전파하고(원장 코드도 `KPST_PREPARE_FAILED` 등으로 분리), 그 외만 `EXTERNAL_API_ERROR` 로 감싼다. ⚠ **구현하지 않는다.**

### [B-ISSUE-103] TC-DEID-062 — 논블로킹 제출 전환으로 `createProject` 실패의 **동기 예외 전파가 사라졌다**(케이스 기대값 무효)

- **심각도**: LOW (정보 — 코드가 의도적 개선, 카탈로그가 낡음)
- **기대 동작(기대효과)**: TC-DEID-062 — `createProject` 예외 시 `procLog.fail` + `markDeidentified("F")` + **예외 전파**(클래스명만).
- **현재 동작(이슈 내용)**: `862ca6d8`(카탈로그 최신화 `119ea148` **이후** 머지) 이후 제출은 구독만 하고 즉시 반환한다.

  ```java
  // KpstDeidentService.java:421-431 — subscribeSubmit
  kpstClient.createProject(projectReq)
      .subscribe(resp -> ... outcomeRecorder.onAccepted(...),
                 err  -> ... outcomeRecorder.onSubmitFailed(rawSn, procLogSn, err));
  ```

  외부 호출 실패는 전용 풀(`kpstSubmitScheduler`)에서 `KpstSubmitOutcomeRecorder.onSubmitFailed` → `KpstDeidentTxService.failSubmit`(`:163-184`) 로 처리되어 **procLog FAILED + `DE_IDNTF_YN='F'` + (REDEIDENT면) 작업락 해제**가 커밋된다. 즉 *부수효과 2종은 충족*하고 **동기 예외 전파만 성립하지 않는다**. 동기 `EXTERNAL_API_ERROR` 는 제출 **이전** 사전조건 실패에만 남는다(`:302`).
  아무 신호도 오지 않는 경우(노드 사망 등)는 폴러가 ACK 유예 만료로 `ACK_MISSING_CODE` 회수한다(`:557-560`).
- **재현/확인 경로**: `KPST_DEID_BASE_URL` 을 닫힌 포트로 두고 적재 → 호출 응답은 성공(202/201)이고, 수 초 뒤 `ls_deident_proc_log.err_cd='KPST_SUBMIT_FAILED'` + `ls_data_raw.de_ident_yn='F'` 로 관측된다. 관련 테스트: `KpstSubmitOutcomeRecorderTest` · `KpstSubmitAsyncCommitIT` · `KpstSubmitSignalDispatchGuardTest`.
- **영향**: 없음(설계 의도 — 메모리 `external-integrations-must-be-async` 정책 준수). 다만 카탈로그 기대값을 고치지 않으면 다음 회차에서 같은 PARTIAL 이 반복된다.
- **수정 방향(제안)**: TC-DEID-062 의 기대결과를 "비동기 실패 신호 → `failSubmit`(procLog FAILED + `'F'` + REDEIDENT 락 해제) 커밋, **동기 예외 없음**"으로 개정하고, 동기 전파 케이스는 `TC-DEID-061`(사전조건) 쪽으로 분리. ⚠ **구현하지 않는다(문서 개정 제안).**

### [B-ISSUE-104] TC-DEID-067 — `prjId == null` 경로가 `markTimeoutIfExpired` 대신 **ACK 대기 유예 + 회수**로 대체되었다(케이스 기대값 무효)

- **심각도**: LOW (정보 — 코드가 의도적 개선, 카탈로그가 낡음)
- **기대 동작(기대효과)**: TC-DEID-067 — `prjId` 가 null 이면 `markTimeoutIfExpired` 만 수행하고 외부는 호출하지 않는다.
- **현재 동작(이슈 내용)**:

  ```java
  // KpstDeidentService.java:541-561 (pollOne)
  if (prjId == null) {
      if (withinSubmitAckGrace(procLog)) {          // REQ_DT + kpst.deid.submit-ack-grace-sec(기본 180)
          log.debug("[KpstDeid] skip poll — awaiting submit ack rawSn={}", rawSn);
          return;                                    // ← 외부 호출 0건, 시도 카운터 미소모
      }
      boolean reclaimed = txService.failSubmit(procLogSn, rawSn, ACK_MISSING_CODE, "submit ack not received");
      log.warn("[KpstDeid] submit ack missing — reclaimed rawSn={} applied={}", rawSn, reclaimed);
      return;
  }
  ```

  코드 주석이 사유를 명시한다 — *"구 코드처럼 `markTimeoutIfExpired` 를 부르면 시도 카운터/경과 타임아웃 예산만 헛되이 소모"*. 종결은 `claimSubmitFailure`(WAITING + prjId null) 조건부 UPDATE 라 **지각 ACK 를 강등하지 않는다**(`LsDeidentProcLogRepository:168-184`).
  → 케이스의 "외부 미호출"은 충족하나 "markTimeoutIfExpired"는 성립하지 않는다.
- **재현/확인 경로**: `ls_deident_proc_log` 에 `POLL_STTS_CD='WAITING'` + `DE_IDNTF_PJT_ID IS NULL` 행을 두고 폴링 틱 관측 → 유예 내에는 `poll_atmpt_cnt` 가 증가하지 않고 mock 인바운드도 없다. 본 검증에서 만든 합성 행(procLogSn=141)도 유예 구간이라 외부 호출 0건이었다(검증 후 삭제 완료).
- **영향**: 없음(개선). 유예 기본 180초는 클라이언트 타임아웃 45s × 재시도 3회 + 백오프 최악값(≈138s)을 덮도록 산정돼 있다(`:202-210`).
- **수정 방향(제안)**: TC-DEID-067 기대결과를 "ACK 유예 내 skip(외부 호출 0·카운터 미소모) / 유예 초과 시 `failSubmit(ACK_MISSING)` 회수"로 개정. ⚠ **구현하지 않는다(문서 개정 제안).**

---

## 부기 — 검증 중 관측된 참고 사항 (케이스 판정 대상 아님)

1. **`KpstDeidentService:145-147` 주석 낡음** — *"`submit(LsDataRaw, boolean)` 자체가 REQUIRES_NEW 라"* 라고 적혀 있으나 현재 `submit` 에는 `@Transactional` 이 없다. 실패 흔적 커밋은 `batchTransitionService.recordDeidentFailure` / `txService.failSubmit`(둘 다 cross-bean REQUIRES_NEW)가 담당하므로 **동작 결함은 없다**. 문서 정합만 필요.
2. **검증 환경은 단일 노드**라 `qrtz_scheduler_state` 0행 / `qrtz_fired_triggers.instance_name='NON_CLUSTERED'` 다. UNCERTAINTIES #8 확정대로 **정상**이며 결함으로 보고하지 않았다(1차 B-ISSUE-81 은 stg/prd 기본값 + 부트 가드로 해소).
3. **검증 중 사용한 DB 개입 2건은 모두 원복 완료**: ①`rawSn=126` 삭제 실증(BEGIN…ROLLBACK, 원복 확인 `raw=1/src=3`) ②원자 클레임 실증용 합성 `ls_deident_proc_log` 1행(procLogSn=141, `data_raw_sn=144`) 생성 후 `DELETE` (잔존 0 확인). 그 외 `UPDATE`/`INSERT` 없음.
4. **self-fill 없음** — KPST 비식별은 mock 실왕복이 backend 컨테이너 IP(`172.18.0.5`)에서 관측됐고(`POST /project` → `GET /retrieve_progress` → `production completed`), 산출 파일명이 우리가 조합할 수 없는 `{원본stem}-mask{ext}` 이며 디스크에 실물(34MB)이 있다. 헬스체크도 mock 루트(`GET /`)를 실제로 쳤다.
