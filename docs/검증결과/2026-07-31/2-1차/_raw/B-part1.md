# B 클러스터 part1 — B-1 적재 / B-2 선두비식별 브릿지·러너 / B-3 DeidentifyStep / B-4 BatchOrchestrator 상태전이

- 회차: **2-1차** (2026-07-31)
- 담당 범위: `docs/test-cases/B-batch-deidentify.md` **B-1(25) + B-2(8) + B-3(16) + B-4(16) = 65건**
- 이슈 ID 대역: **B-ISSUE-01 ~ B-ISSUE-20** (사용: 01~07)
- 판정 근거 환경: backend `http://localhost:18081/api` · mock-server `:9400` · PostgreSQL(`public` 스키마) — 전부 기동 상태에서 실측
- 참조: `_raw/stack-bringup.md`, `_raw/pipeline-drive.md`

## 0. 이번 파트 실구동 요약 (본 파트가 직접 만든 근거)

| # | 실구동 | 방법 | 결과 |
|---|---|---|---|
| D1 | 후보 0건 스캔 | `POST /v1/dev/batch/scan` | `data:0` + 로그 `no training-designated clips to scan` |
| D2 | 가드/매핑 스캔 (9클립) | `MNG_CLIP_MASTER` 에 `VB1-EVT-A~I` 시딩 후 scan | `scanned=7 ingested=5`, rawSn 7~11 적재 |
| D3 | tick 상한/정렬 | `VB1L-EVT-001~105`(105건) 시딩 후 scan | `scanned=100 ingested=0 limit=100 carriedOver=true`, PK 오름차순 `VB1L-EVT-098` 에서 정확히 절단 |
| D4 | 적재 부분실패 격리 | `file_path` 622자(→`RAW_FILE_PATH_NM varchar(500)` 초과) 클립 + 정상 클립 | 앞 건 `UnexpectedRollbackException` ERROR, 뒤 건 rawSn=15 정상 적재 |
| D5 | 멱등 1차 | 동일 `CLIP_ID` 를 갖는 서로 다른 PK 2행 시딩 후 scan | 1건 적재(rawSn=16) + `clip already ingested — skip` |
| D6 | 이벤트리스트 IN 배치화 | scan 중 `org.hibernate.SQL` DEBUG 로그 관찰 | `MNG_CLIP_EVNT_LST … EVNT_ID in (?, ?, ?) order by EVNT_ID, EVNT_TYPE_CD` **1회** + master 쿼리에 `offset ? rows fetch first ? rows only` |
| D7 | 선두 비식별 배선 | 적재 AFTER_COMMIT | `IngestDeidentifyBridge`(http 스레드) → `AsyncDeidentifyRunner`(`batch-async-N`) → `KpstDeidentTxService`(mock-server 실경유, `prjId=4`) |
| D8 | KPST 지연→폴링 완료 | rawSn=7 30초 폴링 | `de_ident_yn 'N'→'Y'`, `data_stts_cd PENDING→MARKING_READY` |
| D9 | 정상 배치 완료 | rawSn=7/17 배정→AUTO 마킹→배치 | `LS_DATA_RAW=COMPLETED` + `LS_RAW_DATA_STATUS=ASSIGNED` |
| D10 | 두 컬럼 동시 PROCESSING | rawSn=17 배치 중 DB 폴링 25회 | `PROCESSING\|PROCESSING` 11샘플 → `COMPLETED\|ASSIGNED` 14샘플 |
| D11 | 검수 소유 진입 가드 | rawSn=7(작업상태 PENDING)에 `POST /v1/dev/batch/trigger` | `finalStage=SKIPPED` elapsed=3ms, `LS_DATA_RAW` COMPLETED 유지, `ver` 불변, `LS_DATA_SRC` 10건 불변 |
| D12 | 배치 실패 전이 | rawSn=15(상태row 없음) / rawSn=16(상태row 있음) trigger | 15: raw=FAILED + WARN `raw data status not found` / 16: raw=FAILED **+ work=FAILED** |
| D13 | 검수 제출 회귀 | 배치완료(work=ASSIGNED) 직후 `POST /v1/reviews/7/submit`(WORKER) | 200 `dataSttsCd: PENDING` — ASSIGNED→PENDING 정상 |
| D14 | 스캔 잡 미등록 | `qrtz_job_details` / `qrtz_triggers` 조회 | `controlTrainingVideoScanJob` **부재** (`application-local.yml:63-66 enabled:false`) |
| D15 | 로그 인젝션 반증 | `EVNT_ID` 에 `chr(10)+FORGED-LOG-LINE-INJECTED` 주입 후 scan | backend 로그에 **타임스탬프/레벨 없는 위조 라인 1줄 출력** (CWE-117 재현) |

> 시딩 데이터(`MNG_CLIP_MASTER`/`MNG_CLIP_EVNT_LST` 의 `VB1*` 17행)는 검증 종료 후 전량 DELETE 하여 원복했다.
> 코드·설정·다른 결과 파일은 일절 수정하지 않았고, 빌드/테스트도 실행하지 않았다.

### ★ self-fill 점검 결과 — **본 파트 범위에서 발견 0건**
비식별은 전 건 `KPST_DEID_BASE_URL=http://klid-mock-server:9400` 실경유(`[KpstDeid] submitted rawSn=7 prjId=4`, mock 측 project/production 로그, `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM=/app/storage/raw/seed/7/deid/clip-9101-mask.mp4`)이며, 외부 응답 없이 값을 자체 생성한 경로는 없다.
`DEIDENTIFY_MOCK_MODE=false` 실효값 확인(§stack-bringup) — 내부 목 우회 없음. 다만 그 결과 **B-3 의 runMock 계열(TC-DEID-015·018~021)은 이번 형상에서 실행되지 않아 정적+단위테스트 근거로 판정**했다.

---

## 1. B-1. 관제 학습용 적재 (스캔 → 적재 → 이벤트) — 25건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-001 | PASS | [실동작] 후보 3건이 모두 기적재 상태에서 `POST /v1/dev/batch/scan` → `{"data":0}`, 로그 `[TrainingIngest] no training-designated clips to scan`(DEBUG). 조기 return(`TrainingVideoIngestService.java:80-83`)이라 `loadEventListsFor`·`ingestOne` 모두 미호출 — hibernate SQL 로그에도 `MNG_CLIP_EVNT_LST` 조회 없음 |
| TC-BATCH-002 | PASS | [실동작] 후보 7건 중 유효 5건 → `{"data":5}`, `ingested clipId=VB1-CLIP-A rawSn=7` 외 4건. 로그 `scan finished scanned=7 ingested=5` |
| TC-BATCH-003 | PARTIAL | [실동작] 622자 `file_path` 클립에서 `ERROR [TrainingIngest] ingest failed evntId=VB1X-EVT-1 clipId=VB1X-CLIP-1 causeType=UnexpectedRollbackException` 후 다음 클립 `VB1X-CLIP-2 rawSn=15` 정상 적재 — **격리 자체는 성립**. 단 `evntId`/`clipId` 가 sanitize 없이 로그에 나가 CR/LF 주입 시 로그 라인 위조가 실제로 재현됨(D15) → **B-ISSUE-03** |
| TC-BATCH-004 | PASS | [실동작] `clip_id=''` → `WARN skip clip with blank clipId evntId=VB1-EVT-B`, `LS_DATA_RAW` 미생성. `TrainingVideoIngestTx.java:86-89` |
| TC-BATCH-005 | PASS | [실동작] `vms_cctv_id=''` → `WARN skip clip with blank vmsCctvId evntId=VB1-EVT-C clipId=VB1-CLIP-C`, 미적재(NOT NULL 위반 사전 차단). `:92-96` |
| TC-BATCH-006 | PASS | [실동작] `file_path='   '` 클립(`VB1-EVT-D`)은 `scanned=7` 에 아예 포함되지 않음 — 후보 쿼리 `TRIM(c.filePath) <> ''`(MngClipMasterRepository:52) 가 1차 차단. `ingestOne` 의 `:98-102` 가드는 도달 불가한 방어심도로 잔존. 기대결과(미적재·이벤트 미발행)는 충족 |
| TC-BATCH-007 | PASS | [실동작] 동일 `CLIP_ID`(`VB1Y-CLIP-DUP`) 2행을 한 스캔 배치에 투입 → 1건 적재(rawSn=16) 후 `DEBUG clip already ingested — skip clipId=VB1Y-CLIP-DUP`, `ingested=1`. 후보 쿼리 NOT EXISTS 와 별개로 사전 조회 가드가 실제 발화함을 확인 |
| TC-BATCH-008 | PARTIAL | [실동작] `DataIntegrityViolationException` catch(`:125-129`) 는 발화하나 **예외가 미전파되지 않는다** — 622자 경로 위반 재현에서 `DEBUG duplicate ingest race — skip` 직후 `ERROR … causeType=UnexpectedRollbackException` 이 호출자까지 전파됨. 또한 UK 위반이 아닌 위반(길이 초과)이 "duplicate race" 로 오분류됨 → **B-ISSUE-01** |
| TC-BATCH-009 | PASS | [실동작] rawSn=7~11 `data_stts_cd=PENDING` 로 INSERT + 즉시 `[IngestDeidentifyBridge] video ingested rawSn=7 — triggering deidentify` (이벤트 발행 확인) |
| TC-BATCH-010 | PASS | [실동작] `VB1-EVT-A` 는 `MNG_CLIP_EVNT_LST(LOITERING, 2021-05-06 07:08:09)` 보유 → rawSn=7 `evnt_type_cd=LOITERING`, `sht_dt=2021-05-06 07:08:09`(클립 `crt_dt=2020-01-02` 아님). 클립별 개별 조회는 hibernate 로그상 0회 |
| TC-BATCH-011 | PASS | [실동작] 이벤트리스트 미보유 `VB1-EVT-E/F/G` → rawSn=8/9/10 `evnt_type_cd=NULL`, `sht_dt=2020-01-02 03:04:05` = `CRT_DT` 폴백 |
| TC-BATCH-012 | PASS | [실동작] 적재 5건 전부 `prvc_type_cd=ANONY`. `TrainingVideoIngestTx.java:67,118` |
| TC-BATCH-013 | PASS | [실동작] `VDO_LEN_SEC=30500` → rawSn=11 `vdo_len_sec=31` (반올림). `:144-150` |
| TC-BATCH-014 | PASS | [실동작] `VDO_LEN_SEC=NULL` → rawSn=9 `vdo_len_sec=NULL` (ffprobe 위임) |
| TC-BATCH-015 | PASS | [실동작] `VDO_LEN_SEC=400` → rawSn=10 `vdo_len_sec=NULL` (0 영속 안 함) |
| TC-BATCH-016 | PASS | [정적] `ControlTrainingVideoScanJob.java:21-22` 에 `@DisallowConcurrentExecution` 존재. 노드 간 중복은 Quartz 클러스터링 축(별건)이라는 비고도 코드/UNCERTAINTIES #8 과 정합. ※local 은 잡 미등록이라 실발화 관찰 불가 |
| TC-BATCH-017 | PASS | [정적] `:40-43` `catch (RuntimeException e)` → `log.error(... causeType={}, e.getClass().getSimpleName())`, 예외 미전파(misfire 방지). 예외 객체/스택 미출력(CWE-209 준수) |
| TC-BATCH-018 | PASS | [정적] `ControlTrainingVideoScanTriggerConfig.java:38-47` `startAt(now+30_000L)` + `withIntervalInSeconds(intervalSec)` + `repeatForever`, 기본값 `application.yml:401-403 interval-sec: ${TRAINING_SCAN_INTERVAL_SEC:60}`. ※local 프로파일이 잡을 끄므로 실발화는 관찰 불가(TC-BATCH-019 의 역상) |
| TC-BATCH-019 | PASS | [실동작] `application-local.yml:63-66 enabled: false` 형상에서 `qrtz_job_details` 4행(`bootstrapJob`/`kpstDeidentPollJob`/`datasetExport*`)에 스캔 잡 **부재**, `qrtz_triggers` 3행에도 `controlTrainingVideoScanTrigger` 부재 — 잡/트리거 빈 미등록 확인 |
| TC-BATCH-020 | PASS | [실동작] `job_dmnd_yn='Y'` + 유효 경로인 기적재 클립 3건(`DEV-CLIP-9101~9103`)이 후보에서 전량 제외되어 `scanned` 에 미포함(D1). 구 동작(`scanned=3 ingested=0` 반복)이 사라짐 |
| TC-BATCH-021 | PARTIAL | [실동작] 후보 107건(105+잔여2) 투입 → `scanned=100`, PK 오름차순으로 `VB1-EVT-B`,`VB1-EVT-C`,`VB1L-EVT-001~098` 정확히 절단(로그 98건 집계), master SQL 에 `offset ? rows fetch first ? rows only`. 상한·정렬은 성립. 단 **"잔여분은 다음 tick 이 이어서 처리"는 성립하지 않는 경우가 있다** — 영구 skip 클립이 PK 앞단을 점유하면 커서가 전진하지 못한다(실측: `VB1-EVT-B/C` 가 모든 후속 스캔에 반복 등장) → **B-ISSUE-02** |
| TC-BATCH-022 | PASS | [실동작] 상한 도달 시 `scan finished scanned=100 ingested=0 limit=100 carriedOver=true`, 미도달 시 `carriedOver=false` — 로그 문구·필드 4종 모두 일치 |
| TC-BATCH-023 | PASS | [실동작] hibernate SQL 로그에 `select mcel1_0.EVNT_ID, mcel1_0.EVNT_TYPE_CD, mcel1_0.SHT_DT from MNG_CLIP_EVNT_LST mcel1_0 where mcel1_0.EVNT_ID in (?, ?, ?) order by mcel1_0.EVNT_ID, mcel1_0.EVNT_TYPE_CD` **1회**(후보 3건/서로 다른 evntId 3개). [정적] `findFirstByEvntId` 는 **프로덕션 호출자 0건**(잔여 참조는 javadoc·테스트·seed 주석뿐) |
| TC-BATCH-024 | PASS | [실동작] `VB1-EVT-H` 에 `(FALLDOWN, 07:08:09)`·`(ZZ-SECOND, 09:09:09)` 2행 시딩 → rawSn=11 `evnt_type_cd=FALLDOWN`, `sht_dt=07:08:09` — PK 오름차순 첫 행만 `putIfAbsent`(`:124-127`) |
| TC-BATCH-025 | PASS | [정적] `TrainingVideoIngestService.java:113-121` — `StringUtils.hasText` 필터 후 `evntIds.isEmpty()` 면 `Map.of()` 즉시 반환, 리포지토리 미호출 |

---

## 2. B-2. 선두 비식별 브릿지 + 러너 — 8건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-001 | PASS | [실동작] `03:13:14.468 INFO TrainingVideoIngestTx - ingested clipId=VB1-CLIP-A rawSn=7` → **같은 ms** `INFO IngestDeidentifyBridge - video ingested rawSn=7 — triggering deidentify`(동일 http 스레드) → `03:13:14.468 [batch-async-2] AsyncDeidentifyRunner - starting deidentify rawSn=7`. `ingestOne` 이 REQUIRES_NEW 이므로 내부 tx 커밋 시점 발화가 확인됨 |
| TC-DEID-002 | PASS | [실동작] 반증 시도 — `VB1X-CLIP-1`(622자 경로)은 `ingestOne` tx 가 롤백됐고, 해당 clipId 에 대한 `IngestDeidentifyBridge` 로그가 **전혀 없으며** `LS_DATA_RAW` 행도 없음. [정적] `IngestDeidentifyBridge.java:26` 는 `fallbackExecution` 미지정(기본 false) |
| TC-DEID-003 | PASS | [정적] `AsyncDeidentifyRunner.java:79-83`(카탈로그 73-77 → 드리프트) `loadRaw(...).orElse(null)` → `WARN raw not found rawSn={} — skip` 후 `return`, 파이프라인 미실행 |
| TC-DEID-004 | PASS | [정적] `:92-94` `if (ctx.isDeidentCompleted()) batchTransitionService.markRawDataMarkingReady(rawSn)`. ※본 형상은 `DEIDENTIFY_MOCK_MODE=false` 라 동기완료 분기가 실행되지 않음 — mock 완료 경로는 `DeidentifyStepExecutePersistenceIntegrationTest` 가 실 DB 로 커버 |
| TC-DEID-005 | PASS | [실동작] rawSn=7 `deidentify submitted (deferred) … MARKING_READY 는 폴링 완료 시 전이` 로그 + 직후 DB `data_stts_cd=PENDING` 유지 → 30초 뒤 `KpstDeidentPollJob` 완료 후에야 `MARKING_READY`. 조기 전이 없음(단일 전이 지점) |
| TC-DEID-006 | PASS | [실동작] rawSn=8/9/10/11 `WARN [AsyncDeidentifyRunner] deidentify failed rawSn=8 cause=CustomException` — 예외 클래스명만, 스택/메시지 미출력, MARKING_READY 미전이(`PENDING` 유지). [정적] 생성자(`:67-74`)에 `BatchRetryQueue` 의존 자체가 없음 |
| TC-DEID-007 | PASS | [정적] `:112-117` `if (rawSn == null) return Optional.empty()` → 호출부에서 skip. ※`loadRaw` 자기호출로 선언된 `@Transactional(REQUIRES_NEW)` 는 무효 — 기능 영향 없음(읽기 전용) 이나 **B-ISSUE-06** 로 기록 |
| TC-DEID-008 | PASS | [정적] `BatchPipelineConfig.java:48-52` `preMarkingPipeline(DeidentifyStep deid) → new BatchPipeline(List.of(deid))` — 선두 파이프라인은 DEIDENTIFY 1스텝. [실동작] rawSn=7~11 러너 로그에 다른 stage 실행 흔적 없음 |

---

## 3. B-3. DeidentifyStep (mock / KPST / 설정오류) — 16건

> 본 형상은 `mockMode=false` + `kpst.deid.enabled=true` 라 **KPST 위탁 경로만 실동작**한다.
> mock 경로(015·018~021)와 부트 게이트(010~013)는 컨테이너 재기동/프로파일 변경 없이는 실행 불가이므로
> 코드 정적 대조 + 기존 단위/통합 테스트 인벤토리로 판정했다(테스트 실행은 금지 범위라 미수행).

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-010 | PASS | [정적] `DeidentifyStep.java:187-189` `if (mockMode) assertMockAllowedProfile()`, `:205-221` allowlist `Set.of("local","dev","stg")` 외 프로파일 혼입 시 `IllegalStateException`. 테스트 `DeidentifyStepTest#prd_프로파일에서_mock활성시_부팅거부`, `#local과_prd가_섞인_active프로파일이면_거부` |
| TC-DEID-011 | PASS | [정적] `:210,216` `noActiveProfile = active.length == 0` → 거부(fail-closed). 테스트 `#active프로파일_미설정이면_거부` |
| TC-DEID-012 | PASS | [정적] `:223-227` `localActive` 아니면 WARN 1줄 후 통과. 테스트 `#dev_프로파일에서_mock활성시_부팅허용`, `#stg_…허용`, `#dev_프로파일_mock활성_부팅시_WARN로그_1줄` |
| TC-DEID-013 | PASS | [정적] `:207-214` `env.trim()` + `toLowerCase(Locale.ROOT)` 정규화 후 allowlist 대조. 테스트 `#ENV가_prd면…거부`, `#ENV가_PRD_대문자여도_거부`, `#ENV가_공백포함_prd여도_거부`, `#비표준_환경라벨_production이면_거부` |
| TC-DEID-014 | PASS | [정적] `:270-272` `raw == null` → `CustomException(INVALID_INPUT)`. 테스트 `#raw_null이면_INVALID_INPUT` |
| TC-DEID-015 | PASS | [정적] `:275-277` mock 분기가 KPST 분기(`:280`)보다 **앞**이라 외부 미접촉. 테스트 `#mock모드_원본존재시_외부호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다` |
| TC-DEID-016 | PASS | [실동작] rawSn=7 `[KpstDeid] submitted rawSn=7 prjId=4` + 러너 `deferred` 반환 경로 확인. 원본 실재 검증이 submit 내부에서 수행됨도 실증 — rawSn=8~11 은 `[KpstDeid] submit rejected — source video missing` + `deident failure recorded errCd=KPST_SOURCE_MISSING`(TC-DEID-081 계열) |
| TC-DEID-017 | PASS | [정적] `:284-288` 로그 후 `CustomException(INTERNAL_ERROR, "비식별 경로가 구성되지 않았습니다.")` 고정 메시지, 레거시 폴백 분기 없음. 테스트 `#KPST_disabled_이고_mock도_아니면_레거시폴백_없이_설정오류_예외(INTERNAL_ERROR)`, `#kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외`, `DeidentifyStepKpstDisabledIntegrationTest` |
| TC-DEID-018 | PASS | [정적] `:307-314` 원본 부재 시 `batchTransitionService.recordDeidentFailure(...)`(별도 빈 REQUIRES_NEW, `BatchTransitionService.java:217-235`) → `EXTERNAL_API_ERROR` throw, MARKING_READY 미전이. 테스트 `#mock모드_원본부재시_성공위장없이_recordDeidentFailure_+_MARKING_READY_미전이`, `DeidentifyStepFailurePersistenceIntegrationTest`. **동형 경로가 KPST 에서 실동작 검증됨**(D2: `de_ident_yn='F'` 커밋 + FAIL procLog 4행) |
| TC-DEID-019 | PASS | [정적] `:330-335` `catch (IOException)` → `recordDeidentFailure(MOCK_ERROR_CODE, e.getClass().getSimpleName())` + `INTERNAL_ERROR`. 예외 원문 미노출 |
| TC-DEID-020 | PASS | [정적] `:345-365` `markDeidentified("Y")` + `procLog.succeed(target)` + `workLockService.releaseRaw` + `deidentReportService.resolveOpenReports` + `notificationService.notifyReviewersOnLockRelease` + `streamMetaCacheEvictor.evictAfterCommit`. 테스트 `#mock모드_재비식별_잠금영상_성공시_releaseRaw_+_resolveOpenReports_+_알림`, `#mock모드_비식별완료시_stream-meta_캐시가_실제로_무효화된다` |
| TC-DEID-021 | PASS | [정적] `:385-398` tmp 복사 → `moveAtomically`(`:401-407` ATOMIC_MOVE+REPLACE_EXISTING, `AtomicMoveNotSupportedException` 시 replace 폴백) → `finally Files.deleteIfExists(tmp)`. 테스트 `#mock모드_재실행시_atomic_move로_멱등하게_덮어쓰고_손상되지_않는다` |
| TC-DEID-022 | PARTIAL | [정적] 방어는 존재하고 fail-secure 지만 **기대결과가 실제와 다르다** — `resolveSafeTargetPath`(`:418-421`)→`VideoArtifactRootResolver.resolveUnder`(:424-441)는 base 이탈 시 `ErrorCode.FORBIDDEN`("허용되지 않은 산출 경로입니다")을 던진다. `INVALID_INPUT` 은 base null·세그먼트 부적합(`..`/구분자)에만 해당. 또한 target 형태도 `{base}/videos/{rawSn}/…` 가 아니라 co-locate 기본에서 `{dirname(원본)}/{rawSn}/deid/deidentified.mp4` → **B-ISSUE-04** |
| TC-DEID-023 | PASS | [정적] `:239-250` `execute` 에 `@Transactional` **없음**, `selfProvider.getObject().run(ctx.getRaw())` 프록시 경유(테스트는 `this` 폴백). 회귀 가드 `BatchStepTransactionBoundaryTest#selfProxyStepsMustNotAnnotateExecute`, 실 DB 보증 `DeidentifyStepExecutePersistenceIntegrationTest` |
| TC-DEID-024 | PASS | [정적] `:249` `ctx.markDeidentCompleted(result.completed())`. [실동작] KPST 위탁이 `deferred()`(completed=false) 를 실어 러너가 MARKING_READY 를 건너뛴 것으로 브릿지 동작 확인(rawSn=7) |
| TC-DEID-025 | PASS | [정적] `BatchStepTransactionBoundaryTest.java:53` `BOUNDARY_EXEMPT = Set.of(DeidentifyStep.class, MarkingLoadStep.class)` + 면제 사유(자기참조 프록시, 중첩 REQUIRES_NEW 방지)가 javadoc `:44-53` 에 명문화 |

---

## 4. B-4. BatchOrchestrator 상태 전이 — 16건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-030 | PASS | [정적] `BatchOrchestrator.java:101-103` `rawSn == null` → `CustomException(INVALID_INPUT)`, `loadRaw` 이전. ※REST 표면은 `@RequestParam @Min(1)` 이라 null 투입 경로가 없어 실동작 재현 불가 |
| TC-BATCH-031 | PASS | [실동작] `POST /v1/dev/batch/trigger?rawSn=987654` → HTTP 404 `{"errorCode":"NOT_FOUND"}`. [정적] 오케스트레이터 자체도 `:149-153` `orElseThrow(NOT_FOUND)` — 큐/재시도 진입점에서 raw 가 삭제된 경우 동일 코드 |
| TC-BATCH-032 | PASS | [실동작] rawSn=7 배정→AUTO 마킹 → `MarkingLoad → VLM(submit accepted) → FrameExtract(frames=10) → YOLO → SAM2 → Interpolation` 전 단계 실행 후 `[BatchOrchestrator] completed rawSn=7`, DB `raw=COMPLETED / work=ASSIGNED`, 재시도 큐에 rawSn=7 미등록(clear) |
| TC-BATCH-033 | PASS | [실동작] rawSn=15 trigger → `finalStage=FAILED`, 로그 `[BatchRetry] enqueued rawSn=15 attempt=1 delaySec=60` + `[BatchOrchestrator] failed rawSn=15 willRetry=true cause=CustomException`, DB `LS_DATA_RAW=FAILED`, `ls_bat_rty_wtng` 신규행(`rty_nmtm=1, max=3, PENDING`) |
| TC-BATCH-034 | PASS | [정적] `:140-143` `willRetry` 값과 무관하게 `return BatchStage.FAILED` — 소진(`enqueueIfRetryable=false`, `BatchRetryQueue.java:78-82 attempt > maxAttempts`) 시에도 FAILED 고정. ※local 은 `authoring.batch.enabled=false` 로 `batchRetryJob` 미등록(`qrtz_job_details` 확인)이라 소진까지의 실주행은 불가 |
| TC-BATCH-035 | PARTIAL | [정적] 스킵 로직 자체(`:120-124` + `FfmpegFrameExtractor:107`/`YoloAutolabelStep:135`/`Sam2SegmentStep:120` 의 `isEnabled` 오버라이드)는 정상. 그러나 **`process(Long, Map)` 을 호출하는 프로덕션 코드가 0건**(모든 진입점이 `process(rawSn)` → `stageToggles=null`)이라 토글은 도달 불가한 API다 → **B-ISSUE-05** |
| TC-BATCH-036 | PASS | [실동작] rawSn=7/17 은 토글 없는 `process(rawSn)` 경로로 6단계 전부 실행됨(스킵 로그 0건). [정적] `BatchContext.java:78-85` 키 미존재/null → true |
| TC-BATCH-037 | PASS | [실동작] rawSn=7·17 배치 완료 직후 `LS_DATA_RAW.data_stts_cd=COMPLETED` **AND** `LS_RAW_DATA_STATUS.data_stts_cd=ASSIGNED` — 두 테이블 책임 분리가 실제로 성립 |
| TC-BATCH-038 | PASS | [실동작] 배치 완료(work=ASSIGNED) 상태에서 WORKER 토큰으로 `POST /v1/reviews/7/submit` → 200, 응답 `status=REVIEW_PENDING / dataSttsCd=PENDING`, DB `ls_raw_data_status=PENDING`. 배치가 작업상태를 COMPLETED 로 점프시키지 않아 제출이 막히지 않음(회귀 방지 확인) |
| TC-BATCH-039 | PASS | [실동작] 상태 row 가 **있는** rawSn=16 실패 → `LS_DATA_RAW=FAILED` **AND** `LS_RAW_DATA_STATUS=FAILED`. MARKING_READY 고착 없음 |
| TC-BATCH-040 | PASS | [실동작] rawSn=17 배치 구간을 25회 폴링 → `PROCESSING\|PROCESSING` 11샘플 관측 후 `COMPLETED\|ASSIGNED` 로 수렴. `markRawDataProcessingBlocked` 가 false(차단 아님)를 반환하며 두 컬럼을 동시에 전이 |
| TC-BATCH-041 | PASS | [실동작] 상태 row 없는 rawSn=15 → `WARN [BatchTransition] raw data status not found rawSn=15 target=PROCESSING` / `… target=FAILED` 후 예외 없이 파이프라인 계속 진행(FrameExtract 까지 도달 후 정상 실패 처리) |
| TC-BATCH-042 | PASS | [실동작] 작업상태 `PENDING`(검수 소유)인 rawSn=7 에 trigger → `WARN work status transition skipped (review-owned) rawSn=7 current=PENDING target=PROCESSING` + `WARN skipped — review-owned work status` + 응답 `finalStage=SKIPPED, durationMs=3`. **단계 로그 0건**, `LS_DATA_RAW=COMPLETED` 불변, `ls_raw_data_status.ver` 불변(4), `LS_DATA_SRC` 10건 불변 |
| TC-BATCH-043 | PASS | [정적] `BatchTransitionService.java:77-81` `REVIEW_OWNED_STATUSES = {PENDING, IN_REVIEW, APPROVED, REJECTED}` — ASSIGNED 제외. [실동작] 반증 확인 — ASSIGNED 상태의 rawSn=7·17 은 가드에 걸리지 않고 배치가 완주했고, PENDING 으로 바뀐 직후에는 SKIPPED 됨 |
| TC-BATCH-044 | PASS | [정적] `markRawDataCompleted:152-154` / `markRawDataFailed:191-193` 모두 `transitionRawDataStatus(...) == true` 면 `LS_DATA_RAW` 접근 전 즉시 return. ※진입 가드가 선행 차단하므로 이 분기는 "가드 통과 후 상태가 바뀐" 레이스에서만 도달 — 결정적 재현 불가(fail-closed 이중 방어로 존치) |
| TC-BATCH-045 | PASS | [실동작] dev 트리거 경로에서 가드 발화 확인(D11). [정적] `process(rawSn)` 를 부르는 진입점 전수 — `BatchDevTriggerController:119`, `AsyncBatchRunner:25`(마킹 브리지), `BatchQuartzJob:51`, `BatchRetryQuartzJob:55`, `BatchReprocessService:87` — 5개 모두 동일 `process()` 를 경유하므로 가드가 공통 관문. 특히 `BatchReprocessService:95-101` 은 SKIPPED 수신 시 `releaseReprocessClaim` 보상 롤백 + 409 반환까지 이행 |

---

## 5. 판정 집계

| 판정 | B-1 | B-2 | B-3 | B-4 | 합계 |
|---|--:|--:|--:|--:|--:|
| PASS | 22 | 8 | 15 | 15 | **60** |
| FAIL | 0 | 0 | 0 | 0 | **0** |
| PARTIAL | 3 | 0 | 1 | 1 | **5** |
| BLOCKED | 0 | 0 | 0 | 0 | **0** |
| N/A | 0 | 0 | 0 | 0 | **0** |
| 확인필요 | 0 | 0 | 0 | 0 | **0** |
| **합계** | **25** | **8** | **16** | **16** | **65** |

- 실동작 기반 판정: 40건 / 정적 기반 판정: 25건 (부트 게이트·mock 전용 경로·null 파라미터처럼 재기동·설정 변경 없이는 도달 불가한 구간)
- **self-fill 결함 0건**

---

## 6. 이슈 대장

### [B-ISSUE-01] TC-BATCH-008 — UK 위반 catch 가 예외를 못 막고, 비-UK 무결성 위반까지 "중복 race" 로 오분류
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 동시 race 로 `VMS_CLIP_ID` UK 가 충돌하면 `ingestOne` 이 `false` 를 반환하고 **예외를 호출자에 전파하지 않아야** 한다(카탈로그 기대결과 "catch-skip false, 예외 미전파"). 그래야 정상 race 가 ERROR 로그로 오염되지 않고, 진짜 적재 실패와 구분된다.
- **현재 동작(이슈 내용)**:
  ```java
  // TrainingVideoIngestTx.java:115-129 (@Transactional(REQUIRES_NEW))
  LsDataRaw saved = videoRepository.save(raw);      // IDENTITY PK → 즉시 INSERT
  eventPublisher.publishEvent(new VideoIngestedEvent(saved.getRawSn()));
  return true;
  } catch (DataIntegrityViolationException e) {
      log.debug("[TrainingIngest] duplicate ingest race — skip clipId={}", vmsClipId);
      return false;   // ← 여기서 false 를 반환해도 REQUIRES_NEW 커밋이 롤백된다
  }
  ```
  PostgreSQL 은 무결성 위반 시 **트랜잭션 전체를 abort** 하고 Hibernate 는 세션을 rollback-only 로 마킹하므로, `catch` 후 `return false` 를 해도 REQUIRES_NEW 경계에서 `UnexpectedRollbackException` 이 던져져 호출자까지 전파된다. 실측(622자 `FILE_PATH` 로 `RAW_FILE_PATH_NM varchar(500)` 을 위반시켜 재현):
  ```
  DEBUG TrainingVideoIngestTx  - [TrainingIngest] duplicate ingest race — skip clipId=VB1X-CLIP-1
  ERROR TrainingVideoIngestService - [TrainingIngest] ingest failed evntId=VB1X-EVT-1 clipId=VB1X-CLIP-1 causeType=UnexpectedRollbackException
  ```
  추가로 `catch (DataIntegrityViolationException)` 이 **UK 위반 외의 모든 무결성 위반**(길이 초과·NOT NULL·FK)까지 잡아 "duplicate ingest race" 라는 **사실과 다른 DEBUG 로그**를 남긴다(CWE-396 over-broad catch). 운영자는 스키마 불일치(관제 `FILE_PATH varchar(1000)` ↔ 저작도구 `RAW_FILE_PATH_NM varchar(500)`)로 인한 영구 적재 실패를 무해한 race 로 오독하게 된다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, vms_cctv_id, lclgv_cd, file_path, vdo_len_sec, crt_dt, job_dmnd_yn)
  VALUES ('X-1','ORIGINAL','X-CLIP-1','CCTV-X','11110','/nas/'||repeat('p',600)||'.mp4',30000,now(),'Y');
  ```
  ```bash
  curl -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REVIEWER_JWT"
  # backend 로그에서 duplicate-race DEBUG + UnexpectedRollbackException ERROR 동시 관측
  ```
- **영향**: ①기능 — 예외 미전파 계약이 깨져 정상 동시 race 가 매 tick ERROR 로 기록(알림 피로). ②데이터 정합 — 길이 초과 클립이 "중복" 으로 위장돼 영구 미적재가 은폐된다(관제 NAS 절대경로가 길면 실환경에서 현실적). ③CWE-396(Declaration of Catch for Generic Exception 계열, 과대범위 catch).
- **수정 방향(제안)**:
  ① `catch` 를 UK 위반으로 좁힌다 — `DataIntegrityViolationException` 의 root cause 가 `ConstraintViolationException` 이고 제약명이 `uk_ls_data_raw_vms_clip` 일 때만 skip 처리, 그 외는 원인 코드를 담아 그대로 전파.
  ② `false` 반환이 실제로 커밋되게 하려면 UK 판정을 `saveAndFlush` + `Savepoint`(`REQUIRES_NEW` 중첩) 로 감싸거나, 아예 예외를 전파시키고 **호출자(`scanAndIngest`)에서 UK 원인을 식별해 DEBUG 로 낮추는** 쪽으로 계약을 바꾸고 카탈로그 기대결과도 정정한다.
  ③ 별건으로 `RAW_FILE_PATH_NM` 길이(500)를 관제 `FILE_PATH`(1000)에 맞추거나, `ingestOne` 에 사전 길이 가드를 추가한다.

### [B-ISSUE-02] TC-BATCH-021 — 영구 미적재 클립이 PK 앞단을 점유하면 tick 상한 100 이 신규 클립을 굶긴다(기아)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 후보가 상한을 넘어도 "잔여분은 다음 tick 이 이어서 처리" 되어야 한다. 근거 javadoc(`MngClipMasterRepository.java:38`, `TrainingVideoIngestService.java:59-61`)도 "적재된 클립은 다음 조회에서 NOT EXISTS 로 빠지므로 **커서가 전진한다**" 고 단언한다.
- **현재 동작(이슈 내용)**: 커서 전진의 전제는 "후보는 결국 적재된다" 인데, **적재되지 않고도 후보로 남는 클립이 3종** 존재한다.
  ```java
  // TrainingVideoIngestTx.java:86-107 — skip 시 아무 상태도 남기지 않는다(false 반환뿐)
  if (!StringUtils.hasText(vmsClipId))         { log.warn(...); return false; }  // blank CLIP_ID
  if (!StringUtils.hasText(clip.getVmsCctvId())){ log.warn(...); return false; }  // blank VMS_CCTV_ID
  // + 적재 자체가 영구 실패하는 클립(B-ISSUE-01 의 길이 초과 등)
  ```
  이들은 `LS_DATA_RAW` 행을 만들지 않으므로 후보 쿼리의 `NOT EXISTS` 를 영원히 통과하고, `ORDER BY evntId, clipTypeCd` 상 앞자리면 매 tick 상한 100 슬롯을 계속 점유한다. 실측: `VB1-EVT-B`(blank clipId)·`VB1-EVT-C`(blank cctv) 는 이후 실행한 **모든** 스캔(4회)에 매번 등장했고, 105건 투입 시 두 건이 앞자리를 차지해 `VB1L-EVT-099~105` 가 절단됐다.
  ```
  scan finished scanned=100 ingested=0 limit=100 carriedOver=true   ← 매 tick 100건 재조회 + 0건 적재
  ```
  60초 주기이므로 이런 클립이 100건 누적되면 **신규 학습용 지정 영상이 영구히 적재되지 않으며**, 상태 어디에도 흔적이 없어 무증상이다(관제 공유 DB 에 매 분 100행 SELECT 부하도 상시 지속).
- **재현/확인 경로**:
  ```sql
  -- blank CLIP_ID 클립을 PK 앞자리로 100건 시딩
  INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, vms_cctv_id, lclgv_cd, file_path, vdo_len_sec, crt_dt, job_dmnd_yn)
  SELECT 'AAA-'||lpad(i::text,3,'0'),'ORIGINAL','','CCTV','11110','/nas/x.mp4',30000,now(),'Y' FROM generate_series(1,100) i;
  -- 이후 정상 클립을 'ZZZ-1' 로 추가
  ```
  `POST /v1/dev/batch/scan` 을 몇 번 반복해도 `ZZZ-1` 은 영원히 적재되지 않는다.
- **영향**: 기능/가용성 — 데이터 입구 전면 정지(무증상). B-ISSUE-01 과 결합하면 관제 NAS 경로가 긴 사이트에서 자연 발생 가능. 관제 공유 DB 에 대한 상시 100행/분 조회 부하(DoS 성격).
- **수정 방향(제안)**: skip/영구실패 클립을 **후보 집합에서 제외할 수 있는 흔적**을 남긴다. 예: ①`LS_INGEST_SKIP`(clipId, 사유, 최초/최종 관측일시) 테이블을 두고 후보 쿼리에 `NOT EXISTS` 조건 추가, ②또는 후보 쿼리 자체에 `CLIP_ID`/`VMS_CCTV_ID` 비공백 조건을 `FILE_PATH` 와 동일하게 추가(1차 방어)하고 그래도 남는 영구 실패는 ①로 흡수, ③커서를 마지막 처리 PK 로 이어가는 keyset 페이징으로 전환. 아울러 skip 로그를 tick 마다 반복 출력하지 않도록 억제(첫 관측 시 1회).

### [B-ISSUE-03] TC-BATCH-003/004/005 — 적재 스캔 로그에 Log Injection(CWE-117) 방어 부재
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 외부(관제 공유 DB·VMS) 유래 문자열을 로그에 실을 때는 CR/LF 를 제거해 로그 레코드 위조를 막아야 한다. 동일 클러스터의 형제 경로는 이미 이 방어를 갖고 있다 — `MarkingBatchBridge.java:166-168 sanitize()` (TC-BATCH-060).
- **현재 동작(이슈 내용)**: `EVNT_ID`/`CLIP_ID` 를 원문 그대로 출력한다.
  ```java
  // TrainingVideoIngestTx.java:87,93-94,99-100,105,123,127
  log.warn("[TrainingIngest] skip clip with blank clipId evntId={}", clip.getEvntId());
  log.warn("[TrainingIngest] skip clip with blank vmsCctvId evntId={} clipId={}", clip.getEvntId(), vmsClipId);
  // TrainingVideoIngestService.java:95-96
  log.error("[TrainingIngest] ingest failed evntId={} clipId={} causeType={}",
            clip.getEvntId(), clip.getClipId(), e.getClass().getSimpleName());
  ```
  실측 재현 — `EVNT_ID` 에 개행을 넣고 스캔하면 **타임스탬프·레벨·로거가 없는 위조 라인**이 그대로 기록된다:
  ```
  klid-backend | 2026-07-31 03:21:03.927 [http-nio-8080-exec-9] WARN ... - [TrainingIngest] skip clip with blank clipId evntId=VB1W-EVT-1
  klid-backend | FORGED-LOG-LINE-INJECTED          ← 위조 라인
  ```
- **재현/확인 경로**:
  ```sql
  INSERT INTO mng_clip_master (evnt_id, clip_type_cd, clip_id, vms_cctv_id, lclgv_cd, file_path, vdo_len_sec, crt_dt, job_dmnd_yn)
  VALUES ('E-1'||chr(10)||'FORGED-LOG-LINE-INJECTED','ORIGINAL','','CCTV','11110','/nas/x.mp4',30000,now(),'Y');
  ```
  ```bash
  curl -X POST http://localhost:18081/api/v1/dev/batch/scan -H "Authorization: Bearer $REVIEWER_JWT"
  docker compose logs klid-backend --tail 50 | grep -A1 'blank clipId'
  ```
- **영향**: 보안 — **CWE-117 (Improper Output Neutralization for Logs)**. 로그 수집기(JSON/라인 파서)에 가짜 이벤트를 주입해 감사 추적 위조·경보 은폐가 가능하다. 입력원이 관제 공유 DB(타 팀 소유, VMS 장비 메타 유래)라 저작도구가 값 무결성을 통제하지 못한다.
- **수정 방향(제안)**: `MarkingBatchBridge.sanitize` 와 동일한 유틸(공통 `common/logging` 으로 승격 권장)을 `TrainingVideoIngestTx`·`TrainingVideoIngestService` 의 모든 `evntId`/`clipId` 인자에 적용한다. 길이 상한(예: 128자) 절단도 함께 두면 로그 폭주 방어가 된다. 정적 가드 테스트(어떤 로그 호출도 미가공 MNG 문자열을 받지 않는지)를 추가하면 회귀를 막을 수 있다.

### [B-ISSUE-04] TC-DEID-022 — 경로 순회 방어의 기대 예외코드/경로 형태가 실제와 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 base 이탈 시 `INVALID_INPUT`, target 형태를 `{base}/videos/{rawSn:Long}/…` 로 명시한다. 검증자가 이 기대로 단언을 작성하면 실제 동작과 어긋나 오탐/누락이 생긴다.
- **현재 동작(이슈 내용)**: 방어 자체는 정상(fail-secure)이지만 코드가 던지는 것은 `FORBIDDEN` 이다.
  ```java
  // VideoArtifactRootResolver.java:424-441 (resolveUnder)
  if (base == null)                       throw new CustomException(ErrorCode.INVALID_INPUT, "기준 경로가 없습니다.");
  if (segment 부적합(".."/구분자))          throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 경로 세그먼트입니다.");
  if (!normalized.startsWith(base))        throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다."); // ← base 이탈
  if (!realPath.startsWith(base))          throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다."); // ← 심링크
  ```
  또한 mock target 디렉터리는 co-locate 기본 전략에서 `{dirname(RAW_FILE_PATH_NM)}/{rawSn}/deid/` 이고(`DeidentifyStep.java:418-421` → `artifactRootResolver.deidVideoDir`), `{deid_base}/videos/{rawSn}/` 는 `labeling-root` 롤백 전략에서만 쓰인다(`VideoArtifactRootResolver.java:281-286`). 근거 라인도 `408-420` → 실제 `418-421` 로 드리프트.
- **재현/확인 경로**: `VideoArtifactRootResolver.resolveUnder(Paths.get("/a/b"), "..", "x")` 는 `INVALID_INPUT`, 심링크로 base 밖을 가리키는 target 은 `FORBIDDEN`. 실제 mock 경로는 파일명이 고정 상수(`deidentified.mp4`)라 사용자 입력이 섞이지 않아 도달 불가한 방어심도 분기임은 맞다.
- **영향**: 검증 정확도(카탈로그 정합) — 코드 결함 아님. 다만 `INVALID_INPUT` 기대로 테스트를 작성하면 실패하거나, 반대로 통과 여부를 오판할 수 있다.
- **수정 방향(제안)**: 카탈로그 TC-DEID-022 의 기대결과를 `FORBIDDEN(base 이탈·심링크) / INVALID_INPUT(base null·세그먼트 부적합)` 2분기로 정정하고, 경로 형태를 `co-locate: {dirname(원본)}/{rawSn}/deid/deidentified.mp4` 로 갱신. 근거 라인 `DeidentifyStep.java:418-421` + `VideoArtifactRootResolver.java:424-441` 로 교체.

### [B-ISSUE-05] TC-BATCH-035 — stage 토글(`process(rawSn, stageToggles)`)에 프로덕션 producer 가 없어 도달 불가한 API
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스와 코드 주석은 "dev 단일 파이프라인 수렴 경로에서 사용한다"(`BatchOrchestrator.java:95`)고 명시한다. 즉 어딘가에서 토글 맵을 만들어 넘겨야 한다.
- **현재 동작(이슈 내용)**: `process(Long, Map<String,Boolean>)` 을 호출하는 프로덕션 코드가 **0건**이다. 전 진입점이 `process(rawSn)` → `process(rawSn, null)` 이라 `stageToggles` 는 항상 빈 맵이고, `FfmpegFrameExtractor#isEnabled`·`YoloAutolabelStep#isEnabled`·`Sam2SegmentStep#isEnabled` 의 `ctx.isStageEnabled(stage())` 는 언제나 `true` 를 돌려준다.
  ```
  $ grep -rn "\.process(" backend/src/main/java | grep -i orchestr
  BatchDevTriggerController.java:119:  orchestrator.process(rawSn);
  BatchRetryQuartzJob.java:55:         orchestrator.process(rawSn);
  AsyncBatchRunner.java:25:            orchestrator.process(rawSn);
  BatchQuartzJob.java:51:              orchestrator.process(rawSn);
  BatchReprocessService.java:87:       orchestrator.process(rawSn);
  # stageToggles 를 생성/전달하는 코드: 없음
  ```
- **재현/확인 경로**: `grep -rn "stageToggles" backend/src/main/java` → `BatchContext`/`BatchOrchestrator` 선언부 외 결과 없음.
- **영향**: 유지보수 — 미사용 분기(BatchContext 토글 맵, 3개 step 의 `isEnabled` 오버라이드, 오케스트레이터 skip 루프)가 살아 있는 것처럼 문서화돼 있어 오해를 유발한다. 기능/보안 영향 없음.
- **수정 방향(제안)**: (a) dev 토글 진입점을 실제로 배선(예: `BatchDevTriggerController` 에 `stages` 쿼리 파라미터 추가)하거나, (b) 사용 계획이 없으면 오버로드·`isEnabled` 오버라이드·`BatchContext.stageToggles` 를 제거하고 카탈로그 TC-BATCH-035 를 폐기 처리한다. 어느 쪽이든 `BatchOrchestrator` javadoc 의 "dev 단일 파이프라인 수렴 경로에서 사용한다" 문구를 사실에 맞게 고친다.

### [B-ISSUE-06] TC-DEID-003/007·TC-BATCH-031 — `loadRaw` 자기호출로 선언된 `@Transactional(REQUIRES_NEW)` 가 무효
- **심각도**: LOW
- **기대 동작(기대효과)**: 두 클래스 모두 "영상 메타 조회를 위한 짧은 readOnly REQUIRES_NEW 트랜잭션" 을 얻는다고 javadoc 에 명시한다(`BatchOrchestrator.java:83-84`, `AsyncDeidentifyRunner.java:109`).
- **현재 동작(이슈 내용)**: `loadRaw` 는 `protected` 이고 같은 클래스의 `runAsync`/`process` 에서 **자기호출**되므로 Spring AOP 프록시를 우회한다 → 어드바이스 미적용, 선언한 트랜잭션이 열리지 않는다.
  ```java
  // AsyncDeidentifyRunner.java:79 / 110-117
  LsDataRaw raw = loadRaw(rawSn).orElse(null);              // ← this. 자기호출
  @Transactional(value="controlTransactionManager", readOnly=true, propagation=REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  같은 클래스의 `DeidentifyStep` 은 정확히 이 문제를 알고 `ObjectProvider<DeidentifyStep> selfProvider` 로 프록시를 얻는데(`DeidentifyStep.java:117-126,245`), `loadRaw` 두 곳은 그 처리가 없다.
- **재현/확인 경로**: 정적 — `grep -n "loadRaw(" backend/src/main/java/kr/co/cudo/authoring/batch/runner/AsyncDeidentifyRunner.java backend/src/main/java/kr/co/cudo/authoring/batch/orchestrator/BatchOrchestrator.java`. 런타임에서는 Spring Data 리포지토리가 자체 트랜잭션을 열어 조회 자체는 성공하므로 증상이 없다(실동작 rawSn=7~11 정상).
- **영향**: 현 시점 기능 영향 없음(읽기 전용 단건 조회, 반환 엔티티는 detached 이지만 `KpstDeidentService.submit` 은 getter 만 사용하고 `DeidentifyStep.runMock` 은 `findById` 로 재조회한다). 다만 ①"경계가 있다"는 잘못된 전제가 코드/주석에 남아 향후 이 자리에 지연로딩·dirty checking 을 추가하면 `LazyInitializationException`/변경 유실로 즉시 터진다. ②`DeidentifyStep` 과 같은 클래스 안에서 규약이 갈린다.
- **수정 방향(제안)**: `DeidentifyStep` 과 동일하게 `ObjectProvider<Self>` 로 프록시 경유 호출로 바꾸거나, `loadRaw` 를 별도 조회 전용 빈(예: `BatchRawLookup`)으로 분리한다. 어느 쪽도 부담스러우면 최소한 `@Transactional` 을 제거하고 javadoc 에서 "경계 없음(리포지토리 자체 트랜잭션)" 으로 정정해 오해를 없앤다.

### [B-ISSUE-07] 근거 `file:line` 드리프트 20건 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 근거 `file:line` 이 현재 코드 위치와 일치해 검증자가 바로 대조할 수 있어야 한다.
- **현재 동작(이슈 내용)**: B-2·B-3 전 구간이 체계적으로 밀려 있다(B-1·B-4 는 정확).
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | TC-DEID-003 | AsyncDeidentifyRunner.java:73-77 | 79-83 |
  | TC-DEID-004 | :86-89 | 92-94 |
  | TC-DEID-005 | :90-92 | 95-98 |
  | TC-DEID-006 | :94-100 | 99-106 |
  | TC-DEID-007 | :105-110 | 112-117 |
  | TC-DEID-010 | DeidentifyStep.java:87,178-215 | 156,182-190,205-228 |
  | TC-DEID-011 | :202,208 | 210,216-221 |
  | TC-DEID-012 | :216-218 | 223-227 |
  | TC-DEID-013 | :205-208 | 207-216 |
  | TC-DEID-014 | :259-262 | 270-272 |
  | TC-DEID-015 | :265-267 | 275-277 |
  | TC-DEID-016 | :269-273 | 280-283 |
  | TC-DEID-017 | :274-278 | 284-288 |
  | TC-DEID-018 | :296-306 | 302-314 |
  | TC-DEID-019 | :319-325 | 330-335 |
  | TC-DEID-020 | :319-350 | 345-365 |
  | TC-DEID-021 | :375-400 | 385-398 |
  | TC-DEID-022 | :408-420 | 418-421 |
  | TC-DEID-023 | :232-241 | 239-250 |
  | TC-DEID-024 | :241 | 249 |
- **재현/확인 경로**: 해당 파일 Read 후 대조.
- **영향**: 검증 효율/정확도. 코드 결함 아님.
- **수정 방향(제안)**: 카탈로그 B-2/B-3 절의 근거 컬럼을 위 표대로 일괄 정정한다(B-1·B-4 는 정정 불필요).

---

## 7. 환경 한계 기록 (다음 회차 참고 — BLOCKED 로 판정하진 않음)

| 항목 | 내용 | 영향받은 케이스 |
|---|---|---|
| local 프로파일이 스캔 잡을 끔 (`application-local.yml:63-66`) | `controlTrainingVideoScanJob`/Trigger 가 Quartz 에 미등록 → 60초 주기 발화·`@DisallowConcurrentExecution` 실동작 관찰 불가. 본 파트는 동일 서비스 메서드를 타는 `POST /v1/dev/batch/scan` 으로 대체 검증 | TC-BATCH-016·018 |
| local 프로파일이 배치 잡을 끔 (`authoring.batch.enabled=false`) | `batchRetryJob` 미등록 → `ls_bat_rty_wtng` 행이 실제로 재시도되지 않아 소진(EXHAUSTED) 도달 불가 | TC-BATCH-034 |
| `DEIDENTIFY_MOCK_MODE=false` (정상 형상 — self-fill 방지 목적) | `runMock` 계열·mock 부트 게이트가 실행되지 않음. 재기동/프로파일 변경이 금지 범위라 정적+단위테스트로 판정 | TC-DEID-004·010~015·018~021 |
| REST 표면이 null rawSn 을 허용하지 않음 (`@RequestParam @Min(1)`) | `process(null)` 도달 불가 | TC-BATCH-030 |
| 진입 가드가 선행 차단 | `markRawDataCompleted`/`markRawDataFailed` 의 검수소유 return 분기는 "가드 통과 후 상태 변경" 레이스에서만 도달 — 결정적 재현 불가 | TC-BATCH-044 |
| ai-server YOLO 가중치 부재 | `mockReason=weights_missing` 폴백(pipeline-drive §2 기재). 본 파트 판정에는 영향 없음(오토라벨 케이스는 다른 파트 소관) | — |
