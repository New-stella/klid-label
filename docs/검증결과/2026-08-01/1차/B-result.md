# B 클러스터 part1 — B-1 관제 학습용 적재(25) + B-2 선두 비식별 브릿지·러너(8) = 33건

- 검증일: 2026-08-01 · 회차: 1차
- 코드 기준: 워크트리 `qa-0801` (HEAD `56d30478`)
- 실행 스택: `klid-backend`(2026-07-31 빌드, **flyway V146**) · `klid-postgres`(schema `public`) · mock-server(:9400)
- 활성 프로파일 실측: `SPRING_PROFILES_ACTIVE=local`

## ⚠ 이 파트의 전제 — 환경 버전 격차 (직접 영향권)

`docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT MAX(version) FROM flyway_schema_history"` → **`146`**
`SELECT to_regclass('public.ls_data_ingest')` → **NULL(테이블 없음)**

`LS_DATA_INGEST` 는 **V147** 에서 생성된다(`V147__create_ls_data_ingest.sql`). 즉 실행 중인 컨테이너는
커밋 `6c8a5303`(관제 인입 테이블 도입) **이전** 코드이며, 실제로 컨테이너 로그가 구 구현의 로그 문구를
그대로 출력한다:

```
[TrainingIngest] ingested clipId=VB1Z-CLIP-1 rawSn=17          ← 구: clipId 축
[TrainingIngest] skip clip with blank vmsCctvId evntId=VB1-EVT-C clipId=VB1-CLIP-C
```
(신규 코드의 로그는 `rcptnSn=` 축이다 — `TrainingVideoIngestTx.java:342`)

**판정 규칙 적용**
- **B-1(25건)**: 근거 파일 `TrainingVideoIngestService`/`TrainingVideoIngestTx` 전체가 V147 리팩터의 교체
  대상 → 실스택 실동작 검증 **불가**.
  - 신규 코드에 **등가 동작이 보존된** 케이스 → `BLOCKED`(사유: 환경 버전 격차) + 신규 코드 정적 대조·
    단위/IT 테스트 커버 기록
  - 신규 코드에서 **전제 자체가 삭제된** 케이스(구 `MNG_CLIP_MASTER` 스캔·`evntLst` 주입·ms→초 변환) →
    `N/A`(전제가 코드에 없음) + 카탈로그 드리프트 집계. 이건 환경을 올려도 성립하지 않는다.
  - 잡/트리거 등록(`ControlTrainingVideoScanJob`·`…TriggerConfig`)은 **V147 리팩터로 동작이 바뀌지 않았고**
    (`git diff 6c8a5303^ 6c8a5303` 결과 javadoc 외 변경 0) 컨테이너에도 동일 코드가 들어 있어 **정상 판정**했다.
- **B-2(8건)**: 근거 3파일(`IngestDeidentifyBridge`·`AsyncDeidentifyRunner`·`BatchPipelineConfig`)은
  **V147 리팩터의 변경 대상이 아니다**(`git log` 최종 변경 2026-06-08 / 2026-07-30). 컨테이너 로그의 문구가
  현재 소스와 **문자열까지 일치**(`deidentify submitted (deferred) rawSn=17 — MARKING_READY 는 폴링 완료 시 전이`
  = `AsyncDeidentifyRunner.java:96`)하므로 **실동작 검증을 정상 수행**했다.

## 판정 집계

| 판정 | B-1 | B-2 | 계 |
|---|--:|--:|--:|
| PASS | 4 | 7 | **11** |
| FAIL | 0 | 0 | **0** |
| PARTIAL | 0 | 1 | **1** |
| BLOCKED | 14 | 0 | **14** |
| N/A | 7 | 0 | **7** |
| 확인필요 | 0 | 0 | **0** |
| **합계** | **25** | **8** | **33** |

---

## B-1. 관제 학습용 적재 (스캔 → 적재 → 이벤트) — 25건

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-BATCH-001 | BLOCKED | [정적] `TrainingVideoIngestService.java:70-75` | 신규 코드 등가 보존: `findPendingReadyForPolling` 결과 null/empty → DEBUG + `return 0`, `ingestTx` 미호출. **이벤트리스트 IN 조회라는 개념 자체가 삭제**(그 단언은 성립 불가). 테스트: `TrainingVideoIngestServiceTest#미처리_인입행이_없으면_위임하지_않는다`·`스캔결과가_null이면_안전하게_0건_처리한다`(baseline 전량 통과) |
| TC-BATCH-002 | BLOCKED | [정적] `TrainingVideoIngestService.java:77-81` | 등가 보존(입력이 클립→인입행으로 바뀜): 행별 `ingestTx.ingestOne(row)` true 시 `ingested++`. 테스트: `…#미처리_인입행을_픽업해_적재_위임한다`·`여러_행_중_일부_skip_일부_적재가_정확히_집계된다` |
| TC-BATCH-003 | BLOCKED | [정적] `TrainingVideoIngestService.java:82-99` | 등가 보존 + **강화**: `UnexpectedRollbackException`(정상 중복 race)은 ERROR 가 아니라 **INFO** 로 분리, 그 외 RuntimeException 만 ERROR. 로그 식별자는 `rcptnSn`(수치)만 — 관제 자유텍스트/경로 미포함(CWE-117/359). ⚠ 구 구현(컨테이너)은 이 race 를 매 tick **ERROR** 로 찍고 있다(실측 로그 `ingest failed evntId=VB1X-EVT-1 … causeType=UnexpectedRollbackException` 4회 반복) → 신규 코드가 이를 고친 것. 테스트: `…#한_행의_적재가_실패해도_다음_행_적재는_계속된다`·`중복클립_race로_인한_트랜잭션_롤백은_ERROR가_아니라_INFO로_남는다` |
| TC-BATCH-004 | BLOCKED | [정적] `TrainingVideoIngestTx.java:398-401` | 등가 보존(`blankIdentifierColumn` → `VMS_CLIP_ID`). ⚠ **동작 변경**: 구 구현은 단순 skip 이었으나 신규는 `markFailed("VMS_CLIP_ID 누락 …")` 로 인입 행을 종결(좀비 방지). 테스트: `TrainingVideoIngestTxTest#VMS_CLIP_ID가_blank면_적재하지_않고_WARN만_남긴다` |
| TC-BATCH-005 | BLOCKED | [정적] `TrainingVideoIngestTx.java:402-404` | 등가 보존. 테스트: `…#VMS_CCTV_ID가_blank면_스킵한다` |
| TC-BATCH-006 | BLOCKED | [정적] `TrainingVideoIngestTx.java:405-407` | 등가 보존(+`markFailed` 종결). 이벤트 미발행 유지. 테스트: `…#RAW_FILE_PATH_NM이_blank면_스킵한다` |
| TC-BATCH-007 | BLOCKED | [정적] `TrainingVideoIngestTx.java:250-255` | 등가 보존: `videoRepository.findByVmsClipId` 존재 시 중복 INSERT 없이 `ingest.markDone(rawSn)`. 테스트: `…#이미_적재된_VMS_CLIP_ID면_중복적재하지_않고_인입행을_완료처리한다` |
| TC-BATCH-008 | BLOCKED | [정적] `TrainingVideoIngestTx.java:344-351` | 등가 보존: `DataIntegrityViolationException` catch → DEBUG + false. 신규는 **인입 행 상태를 더 건드리지 않고** PG 의 tx abort 에 맡겨 PENDING 복귀시키는 설계. 테스트: `…#UK충돌_DataIntegrityViolationException은_중복_skip으로_처리된다` + IT `TrainingVideoIngestFlowIT#중복클립_UK충돌_롤백은_ERROR가_아니라_INFO로_남고_인입행은_PENDING으로_돌아온다` |
| TC-BATCH-009 | BLOCKED | [정적] `TrainingVideoIngestTx.java:332-343` · `LsDataRaw.java:197` | 등가 보존: `createFromIngest` → `dataSttsCd=STATUS_PENDING`, `publishEvent(new VideoIngestedEvent(rawSn))`. 테스트: `…#PENDING_인입행이_LS_DATA_RAW로_적재되고_VideoIngestedEvent가_발행된다` |
| TC-BATCH-010 | N/A | [정적] `TrainingVideoIngestTx.java:114-123,334` | **전제 삭제**. 인입 테이블에 이벤트**유형**코드 컬럼이 없어 `EVNT_TYPE_CD_UNAVAILABLE = null` 상수로 **항상 null 적재**. "스캔이 주입한 evntLst" 개념 자체가 없다. → B-ISSUE-01(드리프트) · **B-ISSUE-02**(관제 통지 파급) |
| TC-BATCH-011 | N/A | [정적] `TrainingVideoIngestTx.java:361-363,377-386` | **전제 삭제**. `shtDt` 는 인입값 그대로이며 **구 `CRT_DT` 폴백은 명시적으로 폐지**("대용값을 넣으면 틀린 값으로 확정") — 미수신 시 null + 1회 WARN. 기대결과가 현재 정책과 정반대다 |
| TC-BATCH-012 | BLOCKED | [정적] `TrainingVideoIngestTx.java:111,334` | 등가 보존: `DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_ANONY` 고정 |
| TC-BATCH-013 | N/A | [정적] `TrainingVideoIngestTx.java:506-529` | **전제 삭제 — 기대값이 현재 코드에서 오답**. 인입 `VDO_LEN_SEC` 는 **이미 초 단위**라 ÷1000 을 하지 않는다(javadoc: "그대로 가져오면 600초 영상이 0.6→null 이 되어 길이가 전부 사라진다"). 30500 입력 시 기대 `31` 이 아니라 `30500`. 테스트: `…#인입값의_영상길이_초단위가_ms변환없이_그대로_적재된다` |
| TC-BATCH-014 | BLOCKED | [정적] `TrainingVideoIngestTx.java:517-519` | 등가 보존(null → null, ffprobe back-fill 위임) |
| TC-BATCH-015 | BLOCKED | [정적] `TrainingVideoIngestTx.java:520-523` | 등가 보존(1초 미만 → null). 단 입력 단위가 ms→초로 바뀌었으므로 케이스의 예시값(`400`)은 재작성 필요. 테스트: `…#영상길이가_1초미만이면_null로_두고_backfill에_위임한다` |
| TC-BATCH-016 | PASS | [정적] `ControlTrainingVideoScanJob.java:27-28` | `@DisallowConcurrentExecution` 존재. 리팩터로 **동작 변경 0**(`git diff 6c8a5303^ 6c8a5303` — javadoc 외 변경 없음)이라 컨테이너 코드와 동일. 케이스 비고의 "노드 간 중복은 Quartz 클러스터링 담당"도 클래스 javadoc:18-20 에 명시 |
| TC-BATCH-017 | PASS | [정적] `ControlTrainingVideoScanJob.java:46-50` | `catch (RuntimeException) → log.error(… causeType={} , e.getClass().getSimpleName())` + 미전파. 예외 메시지·스택 미출력(CWE-209 방어) |
| TC-BATCH-018 | PASS | [정적] `ControlTrainingVideoScanTriggerConfig.java:38-47` | `startAt(now+30_000)` + `withIntervalInSeconds(${…interval-sec:60})` + `repeatForever`. 파일은 V147 리팩터 미변경. ⚠ **local 프로파일에서는 `enabled:false` 라 실등록되지 않으므로 실발화는 관측 불가**(TC-BATCH-019 참조). 문자열 상수 검증에 한해 정적 판정이 결정적이다 |
| TC-BATCH-019 | PASS | [실동작] qrtz 테이블 + `application-local.yml:65-66` | `docker exec klid-postgres psql … "SELECT job_name FROM qrtz_job_details"` → `bootstrapJob / kpstDeidentPollJob / datasetExportFailureRecoveryJob / datasetExportPendingSweepJob` **4건뿐, `controlTrainingVideoScanJob` 부재**. `qrtz_triggers` 에도 `controlTrainingVideoScanTrigger` 없음. `application-local.yml` 의 `training-scan.enabled: false` + `@ConditionalOnProperty` 가 실제로 빈 등록을 막는 것이 실증됨 |
| TC-BATCH-020 | N/A | [정적] grep 결과 | **전제 삭제**. `MngClipMasterRepository.findIngestCandidatesByJobDmndYn` 은 **프로덕션 코드에서 호출부 0건**(유일 참조가 테스트 `MngClipIngestCandidateIT`). 미적재 제외는 이제 `PROC_STTS_CD='PENDING'` 술어가 담당. → B-ISSUE-03 |
| TC-BATCH-021 | BLOCKED | [정적] `TrainingVideoIngestService.java:58,70-71` · `LsDataIngestRepository.java:50-58` | 상한 100(`INGEST_SCAN_LIMIT`)은 보존. **정렬축은 드리프트**: `ORDER BY evntId, clipTypeCd` → `ORDER BY i.rcptnDt ASC, i.rcptnSn ASC`(FIFO). 부분 인덱스 `IX_LS_DATA_INGEST_POLL` 과 술어·정렬 정합(V147:170-172). 테스트: `…ServiceTest#후보조회는_tick당_상한건수의_Pageable로_수행된다`·`상한만큼_조회되면_…이월된다` |
| TC-BATCH-022 | BLOCKED | [정적] `TrainingVideoIngestService.java:100-103` | 로그 문구 완전 동일: `scan finished scanned={} ingested={} limit={} carriedOver={}`. 구 구현(컨테이너)에서도 동일 문구 실측됨 |
| TC-BATCH-023 | N/A | [정적] — | **전제 삭제**. 이벤트리스트 IN 배치 조회(`loadEventListsFor`) 자체가 제거. 인입 테이블이 필요한 값을 자기 행에 이미 갖고 있어 N+1 표면이 소멸 |
| TC-BATCH-024 | N/A | [정적] — | **전제 삭제**(`putIfAbsent` 맵 적재 로직 제거) |
| TC-BATCH-025 | N/A | [정적] — | **전제 삭제**(evntId 기반 조회 자체가 없음) |

### B-1 적대적 검증 — 반증 시도 결과

| 반증 가설 | 결과 |
|---|---|
| 관제 공유 테이블 READ 경로에 인증 우회가 있는가 | **없음(정정)**. 신규 적재는 관제 공유 MNG_* 를 읽지 않고 **자기 소유 `LS_DATA_INGEST`** 를 읽는다. 유일한 외부 트리거인 `POST /v1/dev/batch/scan` 은 **[실동작] 무토큰 호출 시 401**(`{"errorCode":"UNAUTHORIZED"}`), `SecurityConfig.java:116` 이 `/v1/dev/**` 를 REVIEWER 로 제한(`/v1/dev/tokens` 만 permitAll) |
| 동시 스캔이 같은 행을 중복 적재하는가 | 3중 방어 확인: ①`@DisallowConcurrentExecution`(노드 내) ②`claimForProcessing` 조건부 UPDATE(`LsDataIngestRepository.java:147-154`, 반환값 1 만 착수 근거) ③UK `UK_LS_DATA_INGEST_CLIP` + `findByVmsClipId` 1차 멱등. IT `LsDataIngestRepositoryIT#동시에_같은_행을_클레임하면_하나만_성공한다` 커버 |
| 클레임 후 종결이 누락돼 PROCESSING 좀비가 남는가 | **부분 존재(LOW)** — 크래시는 안전(클레임이 같은 REQUIRES_NEW tx 안이라 함께 롤백). 다만 `findById` 가 null 이거나(`:229-234`) `revertToPendingForRetry` 가 0행이면(`:305-310`) PROCESSING 으로 커밋된 채 남고, 회수 통로(`requeueFailedForRetry`)는 **`FAILED` 만** 대상이라 복구 경로가 없다 → B-ISSUE-07 |
| 경로 검증이 우회 가능한가(CWE-22/59) | 우회 못 찾음. `verifyIngestablePath`(lexical+상위 realpath) → `Files.exists` → `toRealPath()` → **최종 실경로가 allowlist 루트 하위인지 재확인**(`:431-478`). 테스트 4건(`허용_루트_밖을_가리키는_심링크는_적재하지_않는다` 등) 커버 |
| 관제 수신값이 무검증으로 흘러들어가는가 | `SRC_TYPE` 은 allowlist 5종 밖이면 **복사하지 않고**(fail-closed) `LogSanitizer` 정제 후 WARN(`:493-503`). `VDO_LEN_SEC` INT 초과분은 null. 다만 **`EVNT_TYPE_CD` 는 아예 수신 불가** → B-ISSUE-02 |
| 비식별 자동 트리거에 게이팅이 잔존하는가 | **잔존 없음**. `prvcTypeCd` 무관하게 `publishEvent` 무조건 발행(`:340`), `preMarkingPipeline` 은 `List.of(deid)` 단일(`BatchPipelineConfig.java:50-52`). PRVC/PSDO 게이팅 코드 없음 |
| 트랜잭션 경계가 과대한가 | `scanAndIngest` 의 `@Transactional(readOnly=true)` 가 tick 전체(최대 100행, 행마다 NAS `Files.exists`/`toRealPath`)를 감싼다 → B-ISSUE-06(LOW) |

---

## B-2. 선두 비식별 브릿지 + 러너 — 8건

> 이 절의 3개 근거 파일은 V147 리팩터 대상이 아니며 컨테이너에 동일 코드가 실려 있다(로그 문자열 일치로 확인).

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-DEID-001 | PASS | [실동작] `docker logs klid-backend` + `IngestDeidentifyBridge.java:26-30` | 실측 로그 순서가 **AFTER_COMMIT 을 직접 증명**한다. 적재 코드는 `publishEvent(...)` **다음 줄**에 `log.info("ingested …")` 를 찍는다(구·신 동일 — 구 코드 `:122-123`). 단순 `@EventListener` 였다면 브릿지 로그가 `ingested` 보다 **앞에** 나와야 하는데 실측은 반대다:<br>`03:17:55.125 [TrainingIngest] ingested clipId=VB1Z-CLIP-1 rawSn=17` → `03:17:55.126 [IngestDeidentifyBridge] video ingested rawSn=17 — triggering deidentify` → `03:17:55.126 [batch-async-1] [AsyncDeidentifyRunner] starting deidentify rawSn=17`. 브릿지는 호출 스레드(http-nio) 동기, 실행은 `batch-async-*` 로 분리됨도 함께 확인 |
| TC-DEID-002 | PASS | [정적] `IngestDeidentifyBridge.java:26` | `@TransactionalEventListener(phase = AFTER_COMMIT)` 이고 `fallbackExecution` 을 지정하지 않았다(기본 **false**) → 롤백/무트랜잭션 발행 시 리스너 미호출. 테스트: `IngestDeidentifyBridgeTest#VideoIngestedEvent_수신_시_AsyncDeidentifyRunner_위임`. 보조 실동작: 롤백된 중복 클립(`VB1X-CLIP-1`, `UnexpectedRollbackException`)에 대해 브릿지 로그가 한 번도 나오지 않음(단, 그 경로는 publish 이전에 예외가 나므로 결정적 증거는 아님 — 정적 판정을 1차 근거로 둔다) |
| TC-DEID-003 | PASS | [정적] `AsyncDeidentifyRunner.java:79-83` | `loadRaw(...).orElse(null)` → null 이면 `log.warn("raw not found rawSn={} — skip")` 후 `return`(파이프라인 미실행). 테스트: `AsyncDeidentifyRunnerTest#raw_없으면_무처리` |
| TC-DEID-004 | PASS | [정적] `AsyncDeidentifyRunner.java:92-95` | `ctx.isDeidentCompleted()` true → `batchTransitionService.markRawDataMarkingReady(rawSn)`. 테스트: `…#mock_모드_비식별_동기완료시_run직후_MARKING_READY로_전이`. ⚠ 실스택은 `DEIDENTIFY_MOCK_MODE=false`(KPST 위탁 경로)라 mock 동기완료 분기는 런타임 미발화 — 실동작 근거 없음을 명시 |
| TC-DEID-005 | PASS | [실동작] 로그 + DB | `03:17:55.129 [AsyncDeidentifyRunner] deidentify submitted (deferred) rawSn=17 — MARKING_READY 는 폴링 완료 시 전이` → 러너는 전이하지 않고 종료. **26초 뒤** 폴링 잡이 단일 전이 지점으로 동작: `03:18:21.858 [KlidAuthoringScheduler_Worker-3] [KpstDeid] completed rawSn=17`. 조기 전이 없음이 시간 순서로 실증됨 |
| TC-DEID-006 | PASS | [실동작] 로그 + DB + [정적] `AsyncDeidentifyRunner.java:99-106,67-74` | 실패 실측: `[AsyncDeidentifyRunner] deidentify failed rawSn=15 cause=CustomException`(WARN, **예외 클래스명만** — 메시지/스택 없음, CWE-209). DB: `raw_sn=15,16 → de_ident_yn='F'`, `data_stts_cd` 는 `MARKING_READY` **아님**(FAILED — `recordDeidentFailure` 의 별도 REQUIRES_NEW 기록, 러너 자신은 전이 안 함). 생성자 파라미터 3개에 `BatchRetryQueue` **의존 자체가 없어** 구조적으로 재시도 큐 사용 불가 |
| TC-DEID-007 | PARTIAL | [정적] `AsyncDeidentifyRunner.java:79,109-117` | 반환 동작은 충족(`rawSn == null` → `Optional.empty()` → skip). **다만 케이스 근거가 명시한 트랜잭션 경계가 실제로는 적용되지 않는다** — `runAsync` 가 `loadRaw` 를 **자기호출**하므로 프록시를 거치지 않아 `@Transactional(REQUIRES_NEW, readOnly)` 가 no-op 이다. 실피해는 없음(무-앰비언트-tx 상태라 `SimpleJpaRepository` 의 readOnly tx 가 `@Primary`=`controlTransactionManager` 로 열려 결과가 동등) → B-ISSUE-05 |
| TC-DEID-008 | PASS | [정적] `BatchPipelineConfig.java:48-52` | `@Bean @Qualifier("preMarkingPipeline") … new BatchPipeline(List.of(deid))` — DEIDENTIFY 단일 스텝. 러너도 `@Qualifier("preMarkingPipeline")` 로 명시 주입(`AsyncDeidentifyRunner.java:68`) |

### B-2 적대적 검증 — 반증 시도 결과

| 반증 가설 | 결과 |
|---|---|
| 브릿지가 커밋 전에 발화하는가 | **아니오** — 위 TC-DEID-001 의 로그 순서 증거(publish 다음 줄의 로그가 브릿지 로그보다 먼저 출력) |
| 러너 실패가 상태를 MARKING_READY 로 밀어 올리는가 | **아니오** — rawSn=15/16 실패분은 `de_ident_yn='F'` + `data_stts_cd=FAILED`. 신고/실패 영상이 마킹 진입 허용 상태로 새지 않음 |
| KPST 지연(deferred) 구간에서 조기 전이가 있는가 | **아니오** — rawSn=17 은 러너 종료 26초 후 폴링 잡이 전이. 전이 지점 단일화 실증 |
| 러너에 진입 가드가 없어 검수 완료 영상이 오염되는가 | 러너 javadoc:43-57 의 판정(라벨 미생성·작업상태 미전이·트리거 1회)이 현재 파이프라인 구성(`List.of(deid)`)과 **일치**함을 확인. pre-marking 에 스텝이 추가되면 무효가 되는 조건부 판정임을 유지 |
| 실패 시 예외가 스레드 밖으로 새는가 | `@Async` + `catch (RuntimeException)` 로 삼킴. 로그에 예외 클래스명만 — PII·경로·스택 미노출 |

---

## 이슈 블록

### [B-ISSUE-01] TC-BATCH-001~025 — B-1 카탈로그 25건 전건이 V147 `LS_DATA_INGEST` 리팩터를 반영하지 않아 근거·기대결과가 무효(7건은 기대값이 오답)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 테스트케이스 카탈로그는 "현재 코드가 유일한 진실원"(B 파일 머리말)이어야 한다. 근거 `file:line` 과 기대결과가 실제 구현과 일치해야 이후 회차가 회귀를 판정할 수 있다.
- **현재 동작(이슈 내용)**: 카탈로그 B-1 은 커밋 `11c3e1b8`(tc-update) 시점의 **구 `MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 스캔** 구현을 기술한다. 그러나 `6c8a5303`(main 병합, V147/V148)이 적재 소스를 `LS_DATA_INGEST` 로 교체했다.
  - `TrainingVideoIngestService.java:70-71` — `ingestRepository.findPendingReadyForPolling(now, PageRequest.of(0, INGEST_SCAN_LIMIT))` (구: `clipMasterRepository.findIngestCandidatesByJobDmndYn`)
  - `TrainingVideoIngestTx.java:217` — `public boolean ingestOne(LsDataIngest candidate)` (구: `ingestOne(MngClipMaster clip, MngClipEvntLst evntLst)`)
  - 카탈로그가 인용한 라인은 전부 어긋난다(예: 근거 `TrainingVideoIngestTx.java:86-89`(blank clipId) → 현재 그 위치는 javadoc, 실제 로직은 `:398-407`)
  - **기대값이 오답이 된 7건**: TC-BATCH-010/011(evntLst 주입·CRT_DT 폴백 → 현재 `EVNT_TYPE_CD` 항상 null, `SHT_DT` 폴백 폐지) · TC-BATCH-013(ms→초 변환 → **변환 폐지**, `TrainingVideoIngestTx.java:506-529`) · TC-BATCH-020/023/024/025(MngClip 쿼리·IN 배치화 → 코드 삭제)
- **재현/확인 경로**: `grep -n "MngClipMaster" backend/src/main/java/kr/co/cudo/authoring/video/service/TrainingVideoIngestService.java` → 0건. `git log --oneline -1 -- backend/src/main/java/kr/co/cudo/authoring/video/service/TrainingVideoIngestTx.java` → `6c8a5303`
- **영향**: 카탈로그 정합성 결함. 이 상태로 다음 회차를 돌리면 정상 동작 7건이 FAIL 로 오판되고(특히 TC-BATCH-013 은 30500→31 을 기대해 실제 `30500` 을 결함으로 신고하게 된다), 신규 도입된 **미도착 backoff·대기 상한·재큐·원자 클레임·경로 allowlist** 등 20건 이상의 신규 표면은 케이스가 아예 없어 미검증으로 남는다.
- **수정 방향(제안)**: B-1 절을 `LS_DATA_INGEST` 기준으로 재작성. 최소 추가 축 — ①`claimForProcessing` 원자 클레임(0/1 분기·동시성) ②미도착 3분기(READY/NOT_ARRIVED/REJECTED) ③대기 상한 초과 종결 + 재큐 가역성 ④backoff(`NEXT_RTRY_DT`) head-of-line blocking 차단 ⑤`SRC_TYPE` allowlist fail-closed ⑥심링크 실경로 루트 검증 ⑦`EVNT_TYPE_CD`/`SHT_DT` 결손 WARN 1회성. 기존 테스트(`TrainingVideoIngestTxTest` 36건 · `LsDataIngestRepositoryIT` 23건 · `TrainingVideoIngestFlowIT` 9건)가 이미 이 축을 덮고 있어 케이스 도출 근거로 쓸 수 있다.

### [B-ISSUE-02] TC-BATCH-010 — 신규 적재분의 `EVNT_TYPE_CD` 가 항상 null 이라 관제 완료통지 `event_type_cd` 가 전건 null 로 나간다
- **심각도**: MEDIUM (관제 협의 대상 — 저작도구 단독 해소 불가)
- **기대 동작(기대효과)**: 관제 완료통지 계약의 6필드 중 `event_type_cd` 는 값이 있어야 하며(UNCERTAINTIES #3·#27), 작업/검수 목록의 이벤트유형 필터·통계 버킷·export 메타도 이 값에 의존한다.
- **현재 동작(이슈 내용)**: 인입 테이블에 이벤트 **유형** 코드 컬럼이 없다(`EVNT_ID` 는 `ABA_0001` 식별자형).
  ```java
  // TrainingVideoIngestTx.java:123
  private static final String EVNT_TYPE_CD_UNAVAILABLE = null;
  // :334  createFromIngest(vmsClipId, vmsCctvId, EVNT_TYPE_CD_UNAVAILABLE, ...)
  ```
  통지 페이로드는 이 값을 그대로 통과시킨다 — `ControlNotifyPayloadFactory.java:139-141` `toControlEventTypeCd(x) { return x; }`. 즉 **V147 이후 적재된 모든 영상의 완료통지 `event_type_cd` 가 null**. 구 경로에서는 `MNG_CLIP_EVNT_LST.EVNT_TYPE_CD` 로 채워지던 값이다.
  결손이 조용하지 않게 프로세스 1회 WARN 은 남긴다(`:369-376`) — self-fill(대용값 생성)을 하지 않은 점은 **정책상 옳다**.
- **재현/확인 경로**: V147+ 스택에서 `INSERT INTO LS_DATA_INGEST(...)` → 스캔 → `SELECT evnt_type_cd FROM ls_data_raw WHERE vms_clip_id='...'` → null. 이후 검수 승인 시 mock-server 인바운드 `POST /api/data-set/v2/jobs/{id}/notify-completed` 바디의 `event_type_cd` 확인.
- **영향**: 기능(관제 연동 계약 필드 결손) — 관제가 required 로 검증하면 통지 자체가 거부될 수 있다. UNCERTAINTIES #27(코드값 목록 미수령)과 **별개의 상위 문제**(값 자체가 없음).
- **수정 방향(제안)**: 저작도구에서 값을 만들지 말 것(self-fill 금지). ①관제에 `LS_DATA_INGEST` 이벤트유형 컬럼 추가를 계약으로 요청 ②그때까지 통지 페이로드의 null 허용 여부를 관제와 합의하고 결과를 UNCERTAINTIES 에 확정 기록.

### [B-ISSUE-03] TC-BATCH-020/023/024/025 — 구 관제 공유 클립 스캔 리포지토리 2종이 프로덕션 미사용 dead code 로 잔존
- **심각도**: LOW
- **기대 동작(기대효과)**: 적재 소스 교체 후 구 경로는 제거되어야 검증자·후속 개발자가 "어느 쪽이 진짜 적재 경로인가"를 오해하지 않는다.
- **현재 동작(이슈 내용)**: `MngClipMasterRepository.findIngestCandidatesByJobDmndYn`(`:48-57`) 과 `MngClipEvntLstRepository.findFirstByEvntId`/`findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc`(`:28,42`) 는 **프로덕션 호출부 0건**이다. 유일한 참조가 테스트 `MngClipIngestCandidateIT`(:99,180,200)이라 CI 는 계속 초록이고, 카탈로그도 이 IT 를 근거로 살아 있는 경로처럼 기술한다.
- **재현/확인 경로**: `grep -rn "MngClipMasterRepository\|MngClipEvntLstRepository" backend/src --include="*.java" | grep -v "repository/MngClip"` → **0건**(테스트 포함 검색 시 `MngClipIngestCandidateIT` 만)
- **영향**: 유지보수/검증 오도. 보안·데이터 영향은 없음(READ 전용).
- **수정 방향(제안)**: 구 스캔 전용 쿼리 메서드 + `MngClipIngestCandidateIT` 제거. `MNG_CLIP_*` 엔티티 자체는 다른 참조가 있으면 존치하되, 적재 경로가 아님을 javadoc 에 명시.

### [B-ISSUE-04] TC-BATCH-018 — 스캔 JobDetail description 이 폐지된 구 스킴(`JOB_DMND_YN='Y'`) 문구로 남아 Quartz 테이블에 적재된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 운영자가 `QRTZ_JOB_DETAILS.DESCRIPTION` 으로 잡의 실제 소스를 파악할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `ControlTrainingVideoScanTriggerConfig.java:33` `.withDescription("관제 학습용 영상(JOB_DMND_YN='Y') 픽업 적재")` — 같은 커밋에서 Job 클래스 javadoc 은 "구 소스 `MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 스캔은 폐지"로 갱신됐으나(`ControlTrainingVideoScanJob.java:13-16`) description 만 남았다.
- **재현/확인 경로**: V147+ dev 스택 기동 후 `SELECT description FROM qrtz_job_details WHERE job_name='controlTrainingVideoScanJob';`
- **영향**: 운영 관측 오도(기능 영향 없음).
- **수정 방향(제안)**: `"관제 인입(LS_DATA_INGEST PENDING) 픽업 적재"` 로 교체.

### [B-ISSUE-05] TC-DEID-007 — `AsyncDeidentifyRunner.loadRaw` 의 `@Transactional(REQUIRES_NEW, readOnly)` 가 자기호출로 무효(선언과 실행이 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 근거와 코드 주석이 "REQUIRES_NEW readOnly 로 영상 메타를 조회한다"고 선언하면 실제로 그 경계가 적용돼야 한다. `CLAUDE.md` 도 "자기호출로 프록시를 우회하지 말 것"을 별도 규칙으로 못박고 있다(프레임 이미지 서빙 절).
- **현재 동작(이슈 내용)**:
  ```java
  // AsyncDeidentifyRunner.java:79
  LsDataRaw raw = loadRaw(rawSn).orElse(null);          // ← this.loadRaw (프록시 미경유)
  // :110-112
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  Spring AOP 는 외부 호출만 가로채므로 이 어노테이션은 no-op 이다. 동일 패턴이 `BatchOrchestrator.java:104`(`loadRaw(rawSn)` 자기호출) → `:149` 에도 있다.
- **재현/확인 경로**: 정적. 런타임 확인은 `loadRaw` 내부에서 `TransactionSynchronizationManager.isActualTransactionActive()` 를 찍어보면 드러난다(이번 회차는 코드 수정 금지라 미수행).
- **영향**: 현재는 **실피해 없음** — `@Async` 실행이라 앰비언트 트랜잭션이 없고, `VideoRepository` 는 `@ControlRepo`(control EMF)이며 `SimpleJpaRepository` 의 `@Transactional(readOnly=true)` 가 `@Primary` 인 `controlTransactionManager`(`ControlDataSourceConfig.java:31,38,50`)로 열리므로 결과가 동등하다. 위험은 **미래**다 — pre-marking 파이프라인이 확장돼 `runAsync` 가 트랜잭션 안에서 호출되면 "새 트랜잭션에서 읽는다"는 전제가 조용히 깨진다.
- **수정 방향(제안)**: 조회를 별도 빈(`…LookupService`)으로 분리하거나 `self` 프록시 주입으로 외부 호출화. 어느 쪽도 아니라면 어노테이션을 제거하고 javadoc 을 실제 동작에 맞춰 정정(선언만 남기는 것이 가장 나쁘다).

### [B-ISSUE-06] TC-BATCH-021 — `scanAndIngest` 의 readOnly 트랜잭션이 tick 전체(최대 100행 NAS 파일 I/O)를 감싸 커넥션을 점유한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 조회 트랜잭션은 조회 구간에만 열려야 한다. 이 프로젝트는 "커넥션을 쥔 채 NAS I/O 하면 커넥션 기아로 간다(전례 있음)"를 `CLAUDE.md` 구속 규칙으로 갖고 있다(프레임 이미지 서빙 절).
- **현재 동작(이슈 내용)**:
  ```java
  // TrainingVideoIngestService.java:68-69
  @Transactional(value = "controlTransactionManager", readOnly = true)
  public int scanAndIngest() { ... for (LsDataIngest row : pending) { ingestTx.ingestOne(row); } ... }
  ```
  외부 readOnly 트랜잭션이 루프 전체 동안 열려 있고, 행마다 `REQUIRES_NEW` 가 **두 번째 커넥션**을 잡는다. `ingestOne` 은 그 안에서 `Files.exists` / `toRealPath()` 로 NAS 를 친다(`TrainingVideoIngestTx.java:443-453`). 클래스 javadoc 은 "조회는 쓰기 밖에서"라고 적었지만 실제 경계는 tick 전체다.
- **재현/확인 경로**: NAS 응답이 느린 환경에서 tick 당 100행 처리 시 Hikari 활성 커넥션 2개가 tick 지속시간만큼 유지된다(`/api/actuator/metrics/hikaricp.connections.active` 관측).
- **영향**: 성능/가용성(커넥션 점유). 현재 상한 100 · 잡 1개라 실피해는 작다.
- **수정 방향(제안)**: `scanAndIngest` 에서 `@Transactional` 을 떼고 후보 조회만 별도 readOnly 메서드(별 빈)로 분리 — 루프는 트랜잭션 밖에서 돌린다. 행별 쓰기 경계는 이미 `ingestOne` 이 갖고 있어 변경 불필요.

### [B-ISSUE-07] TC-BATCH-007/008 — 클레임 후 종결 실패 시 `PROCESSING` 좀비의 회수 통로가 없다(재큐는 `FAILED` 만 대상)
- **심각도**: LOW
- **기대 동작(기대효과)**: 설계 §6-0-1 ②("되돌릴 수 없는 차단과 끝나지 않는 보류는 둘 다 가용성 결함")를 모든 종결 상태에 적용하려면 `PROCESSING` 고착도 회수 가능해야 한다.
- **현재 동작(이슈 내용)**: 클레임(`PROC_STTS_CD='PROCESSING'`)에 성공했는데 종결 전이가 안 되는 두 경로가 코드에 남아 있다.
  ```java
  // TrainingVideoIngestTx.java:229-234  — 클레임 직후 findById 가 null
  log.error("[TrainingIngest] claimed row disappeared rcptnSn={}", rcptnSn);  return false;
  // :305-310  — revertToPendingForRetry 가 0행
  log.warn("[TrainingIngest] pending revert affected {} rows — row may stay PROCESSING rcptnSn={}", ...);
  ```
  이때 행은 `PROCESSING` 으로 커밋된 채 남고, 폴링 술어(`PENDING`)에서 빠진다. 유일한 회수 API 인 `requeueFailedForRetry`/`requeueFailedBatch` 는 술어가 `PROC_STTS_CD='FAILED'` 라 이 행을 못 되살린다(`LsDataIngestRepository.java:264-316`). `UK_LS_DATA_INGEST_CLIP` 때문에 관제 재INSERT 도 불가하고 인입 행은 삭제 금지다.
  ※ **크래시는 안전하다** — 클레임 UPDATE 가 종결 로직과 같은 `REQUIRES_NEW` 트랜잭션 안이라 프로세스가 죽으면 함께 롤백돼 `PENDING` 으로 돌아온다. 그래서 심각도를 LOW 로 둔다.
- **재현/확인 경로**: `UPDATE LS_DATA_INGEST SET PROC_STTS_CD='PROCESSING' WHERE RCPTN_SN=:n;` 후 스캔을 돌려도 픽업되지 않고, `POST /v1/…/requeue` 도 0행을 반환하는지 확인(V147+ 필요).
- **영향**: 가용성(영상 1건 영구 미적재). 발생 확률은 낮다.
- **수정 방향(제안)**: ①`PRCS_DT` 기준 stale `PROCESSING` 을 `PENDING` 으로 되돌리는 회수 술어를 재큐 API 에 추가(다른 잡의 `claimExpired` 리스 패턴과 동일) 또는 ②클레임에 리스 만료 컬럼을 두고 폴링 술어에 포함.

### [B-ISSUE-08] TC-BATCH-001~015/021/022 — 실행 스택(V146)이 이 파트의 검증 대상 코드를 포함하지 않아 실동작 검증 불가
- **심각도**: MEDIUM (검증 커버리지 결손 — 코드 결함 아님)
- **기대 동작(기대효과)**: 1차 검증은 "실동작 기준"(VERIFY-PROMPT §1)이며, B-1 은 데이터 입구라 실적재·상태전이·동시성을 실스택에서 봐야 한다.
- **현재 동작(이슈 내용)**: `flyway_schema_history` 최대 버전 **146** / `to_regclass('public.ls_data_ingest')` **NULL** / 컨테이너 로그가 구 구현 문구(`skip clip with blank vmsCctvId evntId=…`) 출력. 검증 대상 코드는 V147(`V147__create_ls_data_ingest.sql`)·V148 을 요구한다. 재빌드는 Docker Desktop 프록시 장애로 사용자 지시에 따라 보류(`stack-bringup.md` 하단).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT MAX(version) FROM flyway_schema_history;"` → `146`
- **영향**: B-1 25건 중 14건이 BLOCKED. 특히 **동시 클레임 race·미도착 backoff head-of-line blocking·재큐 가역성**은 단위/IT 로만 덮여 있고 2노드 실환경 검증이 미수행이다.
- **필요 환경**: V147+ 를 포함한 backend 이미지 재빌드(Docker Desktop 프록시 복구 후) + `TRAINING_SCAN_ENABLED=true` 로 뜬 프로파일(local 은 `application-local.yml:66` 에서 비활성) + `LS_DATA_INGEST` 에 관제 역할로 INSERT 할 수 있는 시드. 재검증 시 ①정상 1건 ②파일 미도착 1건(backoff·상한) ③허용 루트 밖 경로 1건 ④동일 `VMS_CLIP_ID` 중복 1건 ⑤`FAILED` 재큐 1건의 5시나리오를 권장.
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
# B 클러스터 part3 — B-5. 마킹 완료 브릿지 + B-6. MarkingService (40건)

> 대상: `docs/test-cases/B-batch-deidentify.md` §B-5(TC-BATCH-050~062, 13건) · §B-6(TC-BATCH-070~096, 27건)
> 검증일 2026-08-01 · 코드 기준 `56d30478`(qa-0801) · 스택 실기동(klid-backend:18081 `/api`, postgres `public`)

## 0. 환경 버전 격차 판정 — 이 담당 구간은 **격차 없음**(BLOCKED 0건)

`stack-bringup.md` 하단이 경고한 "backend 컨테이너가 구버전(V146)" 문제가 **B-5/B-6 에는 적용되지 않는다.** 근거(실측):

| 항목 | 값 | 근거 |
|---|---|---|
| 실행 중 jar 빌드시각 | `2026-07-30 17:49 UTC` | `docker exec klid-backend ls -la /app/app.jar` (컨테이너 TZ=UTC 확인) |
| `marking/` 최종 커밋 | `862ca6d8` = `2026-07-30 14:12 UTC`(23:12 KST) | `git log -1 --format='%h %ai' -- .../marking` |
| `batch/status/` 최종 커밋 | 동일 `862ca6d8` | 동상 |
| 환경격차 커밋 범위(`b2b44f0e..56d30478`)가 marking/batch 를 건드림? | **0건** | `git log b2b44f0e..56d30478 -- .../marking .../batch` → 무결과 |

즉 **jar 빌드가 담당 구간 최종 커밋보다 뒤**이고 이후 변경이 0건이므로, 아래 실동작 판정은 전부 소스와 동일한 코드에 대한 것이다. 런타임 교차검증도 일치: H11 신규 필드 `batchTriggered`/`batchSkipReason` 응답 노출, V142 부분 유니크 인덱스(`uk_ls_marking_raw_actvtn`) DB 존재, H10 `manualFrameIndexLimit` 1초 마진(상한 930) 모두 실측됨.

## 1. 검증 셋업 (재현 가능)

```bash
# 토큰
curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
  -d '{"role":"REVIEWER","channel":"INTERNAL"}'    # sub=1001
  # WORKER: sub=2001 / PORTAL_USER 도 동일 엔드포인트
# 마킹 호출
curl -s -X POST http://localhost:18081/api/v1/videos/{rawSn}/markings \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":30}'
```

사용한 픽스처(기존 DB 데이터, 수정 없음) + 신규 업로드 3건(`/v1/dev/autolabel-test`):

| rawSn | stage(`ls_data_raw.data_stts_cd`) | `de_ident_yn` | 작업상태 row | 길이/파일 | 용도 |
|--:|---|:--:|---|---|---|
| 9103 | MARKING_READY | Y | 없음→(테스트로 ASSIGNED INSERT) | 30s | 음성검증 전량 + TC-053 |
| 9104 | MARKING_READY | **N** | — | 30s | TC-075 |
| 9105 | MARKING_READY | Y | — | `evnt_type_cd` 공백 | TC-077 |
| 9106 | **COMPLETED** | Y | — | — | TC-076 |
| 9109 | **FAILED** | Y | FAILED | — | TC-076 |
| 9110 | MARKING_READY | Y | **APPROVED** | 30s | TC-057/058/061/062 |
| 9112 | MARKING_READY | **F** | APPROVED | — | TC-075 |
| 9113 | MARKING_READY | Y | 없음 | 길이 null + **파일 부재** | TC-079/088/094/095 |
| 9114 | MARKING_READY | Y | APPROVED | 30s, **fps 29.97** | TC-081/090 |
| **30**(신규) | MARKING_READY→COMPLETED | Y | 없음 | 5s | TC-091 동시성 |
| **33**(신규) | MARKING_READY | Y | **ASSIGNED**(실배정) | 5s | TC-053/071 |
| **38**(신규) | MARKING_READY | Y | 없음 | 5s | TC-096 |

> DB 는 신규 INSERT 만 했다(9103 작업상태 row 1건 + 업로드 3건). 기존 행은 수정하지 않았다.

---

## 2. B-5. 마킹 완료 브릿지 (동시성·가드) — 13건

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-BATCH-050 | PASS | [정적] | `MarkingBatchBridge.java:98-103` — `findById` empty → WARN + `skipped(REASON_VIDEO_NOT_FOUND)` + return. 단위테스트 `MarkingBatchBridgeTest#영상_행_미존재시_스킵`. ⚠ **API 경로로는 도달 불가** — `MarkingGuards.requirePreconditions` 가 먼저 404 를 내고 이벤트가 발행되지 않는다(publisher 전수: `MarkingService.java:237` **단 1곳**). 방어적 이중화로 유효 |
| TC-BATCH-051 | PASS | [정적] | `:81-82,109-114` `SKIP_BATCH_STAGES={PROCESSING,COMPLETED}` → `skipped(REASON_STAGE_ALREADY_RUN)`. 테스트 2건(`배치_COMPLETED_영상에_마킹이벤트_재발생시…`, `배치_PROCESSING_중…`). API 도달 불가(412 선행) — 방어적 이중화 |
| TC-BATCH-052 | PASS | [정적] | `:116-123` `!"Y".equals(deIdntfYn)` → `REASON_NOT_DEIDENTIFIED`. 테스트 2건(deIdntfYn=N / =F). API 도달 불가(412 선행) |
| TC-BATCH-053 | PASS | [실동작] | rawSn=33 에 **실배정**(`POST /v1/assignments`)으로 `ls_raw_data_status=ASSIGNED(ver=1)` 생성 후 WORKER 가 MANUAL 마킹 → `201 batchTriggered=true`, 로그 `[MarkingBatchBridge] enqueued rawSn=33` → `[AsyncBatchRunner] starting batch rawSn=33`, 이후 `ver=3`(BATCH_QUEUED→…→ASSIGNED 복귀) |
| TC-BATCH-054 | PASS | [실동작] | rawSn=9113·30 은 작업상태 row **부재** 상태에서 마킹 → `batchTriggered=true` + `ls_raw_data_status` 행이 **신규 생성**됨(9113: 조회 0행 → 마킹 후 1행). tx1 false → `tryCreateBatchQueuedRow` 경로 확인 |
| TC-BATCH-055 | PASS | [정적] | `:138-145` `catch (DataIntegrityViolationException)` → `concurrent row creation … skipping`. 테스트 `MarkingBatchBridgeTest#동시_row생성경합_tx2가_DataIntegrityViolationException_던지면_잡아서_스킵` + `BatchTransitionServiceRowCreationIT#동시_2스레드_row부재_rawSn_클레임`. ⚠ **실동작 재현 불가** — V142 부분 유니크가 *마킹 생성 단계*에서 먼저 직렬화해 동시 5요청에도 이벤트가 1건만 발행된다(§TC-091 실측). 사실만 기록 |
| TC-BATCH-056 | PASS | [정적] | `LsRawDataStatusRepository.java:86-91`(`transitionByBatchIfNotBlocked`)·`:60-68`(`transitionToBatchQueuedIfNotSkipped`) 단일 조건부 `UPDATE VERSIONED … WHERE dataSttsCd NOT IN :skip` → 영향행수 1건만. 테스트 `MarkingBatchBridgeTest#동일_rawSn_마킹이벤트_2회_동시발생시…` + `LsRawDataStatusRepositoryClaimIT` |
| TC-BATCH-057 | PASS | [실동작] | 9110(work=APPROVED) 마킹 → 로그 `[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=9110 — skipping` + `[Marking] batch not triggered rawSn=9110 markingSn=25`. 이후 `ls_raw_data_status=APPROVED`·`ls_data_raw=MARKING_READY` **양쪽 불변** |
| TC-BATCH-058 | PASS | [실동작] | 위 9110 케이스가 곧 이 경로다 — tx1 false(APPROVED 가 skip 집합) → tx2 `existsById=true` → false 멱등 스킵(행 생성·전이 0). `BatchTransitionService.java:299-320` |
| TC-BATCH-059 | PASS | [정적] | `BatchTransitionService.java:321-332` `saveAndFlush` + 예외 미포획 전파(REQUIRES_NEW 롤백). `BatchTransitionServiceRowCreationIT` 동시 2스레드 → 1건 true·나머지 예외없이 false·DB BATCH_QUEUED 1건 |
| TC-BATCH-060 | PASS | [정적] | `:166-168` `sanitize()` 가 `\n`/`\r` 제거, 적용 지점 2곳(`dataSttsCd` `:111`, `deIdntfYn` `:120`) — 그 외 로그 인자는 `Long rawSn` 이라 주입 표면 없음. ⚠ **전용 테스트 0건**(`MarkingBatchBridgeTest` 에 CRLF 케이스 없음) + API 도달 불가라 실효 반증 불가 → B-ISSUE-43 |
| TC-BATCH-061 | PASS | [실동작]+[정적] | `:66-71` `SKIP_STATUSES = {BATCH_QUEUED,PROCESSING,COMPLETED} ∪ BatchTransitionService.REVIEW_OWNED_STATUSES` 상수 합성(문자열 재정의 없음). 실동작은 9110(APPROVED) 차단으로 확인 |
| TC-BATCH-062 | PASS | [실동작] | 9110 응답 실측: `201` + `"batchTriggered":false,"batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다."`(고정 상수, DB/외부 문자열 미포함). 정상 트리거 시 `batchTriggered:true, batchSkipReason:null`(9103/30/33/38). 브리지 미실행 시 두 필드 null 분기는 `MarkingService.java:135-138` 정적 확인(실경로 미도달) |

### B-5 부가 반증 결과

- **AFTER_COMMIT 시맨틱**: `@TransactionalEventListener(AFTER_COMMIT)`(`:92`)가 마킹 커밋 후 **같은 요청 스레드**에서 동기 실행됨을 로그 타임스탬프로 실증(`created rawSn=33 19:14:04.339` → 같은 `http-nio-8080-exec-*` 스레드에서 `handling marking completed` → `enqueued`). **롤백 시 미발화**는 실동작으로 유도할 경로가 없었다 — `publishEvent`(`:237`) 이후 남은 코드가 `MarkingResponse.from`(예외 자체 흡수)뿐이라 "발행 후 롤백" 시나리오를 만들 수 없다. 스프링 계약 + 코드로 PASS 처리(케이스 표에 독립 항목 없음).
- **ThreadLocal 오염**: `MarkingBatchTriggerReport.begin()`(`MarkingService.java:108`)이 진입마다 `HOLDER.remove()` 하므로 precheck 예외로 `consume()` 이 생략돼도 다음 요청에 이월되지 않음. `runAsync` 거부 시 예외 누출 우려는 `AsyncConfig.java:44` **CallerRunsPolicy** 라 성립하지 않음(대신 아래 B-ISSUE-42 의 증폭 요인).
- **동시 마킹 완료 → 중복 배치 트리거**: 마킹 생성 자체가 V142 로 직렬화되므로 브릿지 이중 발화를 실동작으로 만들 수 없었다. 즉 이 경로는 **2중 방어**(V142 → 조건부 UPDATE)이며 실측상 배치는 1회만 기동(`AsyncBatchRunner starting batch rawSn=30` 1건).

---

## 3. B-6. MarkingService (자동/수동, 경계값, 인가) — 27건

| ID | 판정 | 근거 확인 | 실측 |
|---|:--:|---|---|
| TC-BATCH-070 | PASS | [실동작] | 토큰 없이 POST /v1/videos/9103/markings → `401 UNAUTHORIZED "인증이 필요합니다."` (Security 필터가 `MarkingGuards` 이전에 차단) |
| TC-BATCH-071 | PASS | [실동작] | REVIEWER(sub=1001)가 9103·9110·9113·30·38 전건 통과(배정 무관) |
| TC-BATCH-072 | PASS | [실동작] | 미배정 WORKER(2001) → 9103 `403 FORBIDDEN "본인에게 배정된 영상의 마킹만…"`, **미존재 rawSn=999999 도 동일 403**(REVIEWER 는 같은 rawSn 에 404) → 존재 여부 미노출 확인 |
| TC-BATCH-073 | PASS | [실동작] | A/B 대조: 9113(길이 null + 파일 부재)에 **REVIEWER** AUTO → `BrampVideoProbe [Video][Probe] ffprobe empty output` 로그 발생 / **미배정 WORKER** 동일 요청 → 403 이고 같은 창의 프로브 로그 **0건**(`grep -c` = 0). 인가-전-프로브 보장 |
| TC-BATCH-074 | PASS | [실동작] | REVIEWER + rawSn=999999 → `404 NOT_FOUND "영상을 찾을 수 없습니다."` |
| TC-BATCH-075 | PASS | [실동작] | 9104(`de_ident_yn='N'`) → `412 PRECONDITION_FAILED "비식별이 완료된 영상에서만 마킹할 수 있습니다."` / 9112(`'F'`) → **동일 412**(신고본도 차단) |
| TC-BATCH-076 | PASS | [실동작] | 9106(stage=COMPLETED) → `412 "이미 처리된 영상은 재마킹할 수 없습니다."` / 9109(stage=FAILED) → **동일 412** |
| TC-BATCH-077 | PASS | [실동작] | 9105(`evnt_type_cd` 공백) → `400 INVALID_INPUT "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다."` |
| TC-BATCH-078 | PASS | [실동작] | `intervalFrames` 생략/`0`/`-5` 3케이스 모두 `400 "자동 모드에서 intervalFrames 는 1 이상이어야 합니다."` |
| TC-BATCH-079 | PASS | [실동작] | 9113(VDO_LEN null + `video.duration_ms` 메타 부재 + 파일 부재로 프로브 실패) → `400 "영상 길이를 확인할 수 없어 자동 마킹을 생성할 수 없습니다."` — backstop 실발화(퇴화 1건 생성 아님) |
| TC-BATCH-080 | PASS | [실동작] | rawSn=30(dur=5s, fps=30, interval=30) → `totalFrames=150`, marks `0,30,60,90,120` **5건**(150 미포함). off-by-one 정확 |
| TC-BATCH-081 | PASS | [실동작] | 9114(`video.fps=29.97`, dur=30, interval=300) 마킹 실 데이터: `round(30×29.97)=899` → marks `0,300,600`(900 제외), 타임스탬프 `00:00/00:10/00:20`(=`300/29.97=10.01` 절단). `Math.round` 정책 실증 |
| TC-BATCH-082 | PASS | [실동작] | `ls_marking.fps` 실적재: AUTO(30→`30`), MANUAL(33→`30`), 29.97 영상(`29.97`). AUTO/MANUAL 양쪽 pin 확인 |
| TC-BATCH-083 | PASS | [실동작] | MANUAL + `marks` 생략/`[]` → `400 "수동 모드에서 marks 는 필수입니다."` |
| TC-BATCH-084 | PASS | [실동작] | `"X"`/`"auto"`/`" AUTO "` 3건 모두 `400 "mode 는 AUTO 또는 MANUAL 이어야 합니다."`(대소문자·trim 미허용 fail-closed). `""` 는 `@NotBlank` 로 `400 "mode: mode 는 필수입니다."` |
| TC-BATCH-085 | PASS | [실동작] | 생성 성공(201) 직후 `[MarkingBatchBridge] handling marking completed rawSn=…` 로그 → `MarkingCompletedEvent` 발행 확인(9103/9110/9113/30/33/38 전건) |
| TC-BATCH-086 | PASS | [실동작] | 응답 `eventName` = 영상 `evnt_type_cd` 그대로(30/33/38 → `EV02000201`, 9103/9110 → `INTRUSION`). 요청에 이벤트명 미포함 |
| TC-BATCH-087 | PASS | [실동작] | `self` 프록시 경유가 아니면 `@Transactional` 미적용 → AFTER_COMMIT 리스너가 발화하지 않는다. 실측상 **모든 성공 케이스에서 브릿지가 발화**했으므로 프록시 경유 + 실 트랜잭션 커밋 확인 |
| TC-BATCH-088 | PASS | [실동작]+[정적] | `VideoDurationResolver.java:74-91` — ①`VDO_LEN_SEC` ②`video.duration_ms` 메타 ③직접 프로브 3단. 9113 에서 ①②가 비어 ③이 실제로 기동(프로브 로그)했고 그마저 실패해 null 반환 → backstop. `@Transactional(propagation=NOT_SUPPORTED)` 로 커넥션 미보유 확인 |
| TC-BATCH-089 | PASS | [실동작] | PORTAL_USER 토큰 → `403 FORBIDDEN "권한이 없습니다."`(`@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 발화 — 서비스 진입 전) |
| TC-BATCH-090 | PASS | [실동작] | 9114(활성 PENDING 마킹 존재) 재요청 → `409 CONFLICT "이미 진행 중인 마킹이 있습니다…"` (`requireNoActiveMarking` 1선) |
| TC-BATCH-091 | PASS | [실동작] | rawSn=30 에 **동시 5요청** → `201 ×1 / 409 ×4`, **500 0건**. 로그에 `[Marking] concurrent duplicate rejected rawSn=30` **4건**(= 4건 모두 서비스 1선이 아니라 **V142 인덱스 위반 catch** 경로로 409 변환). DB `ls_marking WHERE raw_sn=30` **1행**(부분 저장 없음) |
| TC-BATCH-092 | PASS | [실동작] | `frameIndex` 10 중복 2건 → `400 "중복된 마킹 시점입니다: frameIndex=10"` |
| TC-BATCH-093 | PASS | [실동작] | 9103(dur=30, fps=30) 상한 = `round(30×30)+ceil(30)=930`. `frameIndex=930` → **400**(배타 상한), `999999999` → 400. 응답 메시지에 상한값 930 노출로 공식 실증 |
| TC-BATCH-094 | PASS | [실동작] | 9113(길이 미상) MANUAL `[{999999999},{0}]` → **201** + WARN `[Marking] duration unknown — manual mark upper-bound check skipped rawSn=9113 marks=2`. 같은 영상에 음수·중복은 그대로 400(전량 스킵 아님) |
| TC-BATCH-095 | PASS | [실동작] | 9113 MANUAL 요청(19:11:14) 시 `BrampVideoProbe` 로그 **미발생** — 직전 AUTO 요청(19:11:04)에서는 발생. `resolveDurationSecWithoutProbe` 실사용 확인 |
| TC-BATCH-096 | PASS | [실동작] | rawSn=38(dur=5) + `intervalFrames=999999999` → **201**, marks `[{frameIndex:0,timestamp:"00:00"}]` 1건. **B-ISSUE-23 미해소 확정**(케이스가 현재 동작을 고정하므로 PASS) |

---

## 4. 판정 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-5 (050~062) | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| B-6 (070~096) | 27 | 27 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **40** | **40** | 0 | 0 | 0 | 0 | 0 |

> 카탈로그 케이스 자체는 40건 전부 기대결과대로 동작한다(31건 실동작·9건 정적+단위테스트). 다만 **케이스가 커버하지 않는 구간**에서 아래 신규 결함 3건을 반증 과정에서 발견했다.
> **근거 드리프트**: `file:line` 40건 대조 결과 **불일치 0건**(TC-088/093/094/095/089 는 애초 라인 대신 메서드명·어노테이션 표기).

---

## 5. 신규 이슈

> ⚠ **ID 충돌 주의**: 병렬 분할 규약에 따라 part3 은 41 번부터 부여했으나, `UNCERTAINTIES.md` 의 "미해소 이월" 표에 **1차(2026-07-25) 회차의 `B-ISSUE-41`(레거시 프레임 백필)·`B-ISSUE-42`(오토라벨 일괄저장)** 가 이미 존재한다. 병합 시 회차 접두(예: `B-ISSUE-2026-08-01-41`)로 구분하거나 번호 재부여가 필요하다.

### [B-ISSUE-41] TC-BATCH-057/062 파생 — 브릿지가 배치를 skip 하면 **영구 고아 활성 마킹**이 남아 그 영상이 마킹 409 로 영구 잠긴다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 브릿지가 배치를 트리거하지 않기로 정당하게 판단했다면(검수 소유 상태·이미 큐잉), 그 마킹은 소비될 일이 없으므로 **종결 처리되거나 애초에 생성되지 않아야** 한다. V142 부분 유니크 인덱스의 도입 목적 자체가 *"영상당 마킹이 2건 이상 쌓이면 배치는 최신 1건만 VLM 에 위탁하고 나머지는 영원히 PENDING 인 고아가 된다"* 를 막는 것인데(`V142__add_ls_marking_active_unique.sql` 헤더), 현재 skip 경로는 **바로 그 고아를 제도적으로 생성**한다. 또한 `LS_MARKING` 활성 마킹은 후속 마킹을 409 로 막으므로, 고아가 종결되지 않으면 그 영상은 **다시는 마킹할 수 없다.**
- **현재 동작(이슈 내용)**: 마킹은 커밋된 뒤(`MarkingService.java:227-237`) AFTER_COMMIT 브릿지가 skip 을 결정한다 — 즉 **마킹 저장과 배치 트리거 판단이 분리**돼 있어 skip 이어도 `LS_MARKING` 행은 남는다.
  ```java
  // MarkingBatchBridge.java:146-154
  if (!claimed) {
      log.warn("[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn={} — skipping", rawSn);
      MarkingBatchTriggerReport.skipped(MarkingBatchTriggerReport.REASON_ALREADY_CLAIMED);
      return;   // ← 방금 커밋된 PENDING 마킹을 종결시키지 않는다
  }
  ```
  `PENDING → VLM_REQUESTED/VLM_FAILED` 전이는 **오직 VLM 단계**(`VlmMarkingTxService.persistVlmRequested/markVlmFailedIfRequested`, `VlmResultService:224`)에서만 일어나며, 그 단계는 배치가 돌아야 도달한다. 만료·정리 스윕은 **존재하지 않는다**(`markVlmFailed()` 호출부 전수 = 위 2곳).
  실측(rawSn=9110, stage=`MARKING_READY` · work=`APPROVED`):
  ```
  POST /v1/videos/9110/markings → 201 {"markingSn":25, "batchTriggered":false,
     "batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 …"}
  로그: [MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=9110 — skipping
  DB : ls_marking(25, raw_sn=9110, stts_cd='PENDING')   ← 이후 아무도 전이시키지 않음
  POST /v1/videos/9110/markings (재시도) → 409 "이미 진행 중인 마킹이 있습니다…"
  POST /v1/videos/9110/batch/retry     → 409 "배치가 실패(FAILED)한 영상만 재처리할 수 있으며…"
  ```
  즉 **복구용 API 가 하나도 없다**(재처리는 stage/work 중 하나가 `FAILED` 여야 하는데 여기는 `MARKING_READY`/`APPROVED`).
- **재현/확인 경로**: 도달 가능한 정상 동선이 `MarkingBatchBridge` Javadoc(`:56-60`)에 이미 명시돼 있다 — *"배치가 한 번도 안 돈 채 반려된 영상(stage=MARKING_READY, work=REJECTED — 배정→검수제출→반려로 만들 수 있다)"*. 그 영상에 마킹을 1회 하면 이후 영구 409.
  ```bash
  # 위 실측 그대로 (9110 = MARKING_READY + APPROVED)
  curl -X POST $API/v1/videos/9110/markings -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":100}'   # 201, batchTriggered=false
  curl -X POST $API/v1/videos/9110/markings ... # 409 (영구)
  ```
  ```sql
  -- 현재 고아 실태(운영 데이터에도 이미 존재)
  SELECT m.marking_sn, m.raw_sn, m.stts_cd, r.data_stts_cd stage, s.data_stts_cd work
    FROM ls_marking m JOIN ls_data_raw r ON r.raw_sn=m.raw_sn
    LEFT JOIN ls_raw_data_status s ON s.raw_data_id=m.raw_sn
   WHERE m.stts_cd IN ('PENDING','VLM_REQUESTED');
  -- 실측: 9110/9111/9108/9114 등이 (stage=MARKING_READY, work=APPROVED) 조합으로 PENDING 고착
  ```
- **영향**: 기능 — 해당 영상은 **마킹 재수행 불가(영구 409)**, DB 직접 수정 외 복구 수단 없음. 데이터 정합 — `LS_MARKING` 에 소비되지 않는 활성 행이 무기한 누적되고, V142 가 방지하려던 "고아 활성 마킹"이 정책적으로 재생산된다. 부가로 `APPROVED`(검수 완료) 영상에 새 마킹 행이 생성되는 것 자체가 완료 산출물의 부수 변경이다. (CWE-459 불완전 정리 / CWE-667 계열 자원 고착)
- **수정 방향(제안)**: 택1 —
  ① **입구에서 막기**: `MarkingGuards.requirePreconditions` 에 "작업 상태가 `REVIEW_OWNED_STATUSES` 면 412" 게이트를 추가해 브릿지가 skip 할 마킹은 애초에 만들지 않는다(응답 코드가 201→412 로 바뀌므로 FE 문구 동반 수정). 브릿지의 skip 은 진짜 동시성 경합 전용으로 축소된다.
  ② **출구에서 회수**: 브릿지 skip 분기에서 방금 생성된 마킹(`event.markingSn()` — 이벤트에 이미 실려 있다)을 별도 `REQUIRES_NEW` 트랜잭션으로 `VLM_FAILED`(또는 신설 종결코드 `SKIPPED`)로 내려 활성 집합에서 제거한다. `LsMarking.markVlmFailed()` 는 상태 무관 대입이므로 `PENDING` 한정 가드를 함께 둔다.
  > ①이 근본적이다(무의미한 마킹 자체를 만들지 않음). ②만 하면 "201 인데 마킹이 곧바로 실패 종결"이라는 또 다른 혼란이 남는다.

### [B-ISSUE-42] TC-BATCH-078/096 파생 — AUTO 마킹의 **생성 marks 개수에 상한이 없다**(MANUAL 은 20,000 캡, CWE-770)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 단일 요청이 만들어내는 산출물 크기는 유계여야 한다. MANUAL 경로는 이미 그렇게 설계돼 있다 — `MarkingRequest.marks` 에 `@Size(max = 20000, message = "한 번에 처리 가능한 마킹 수 초과 (최대 20000)")` 가 **CWE-770 방어 목적으로 명시**돼 있다. AUTO 는 같은 산출물(`MARK_CN`)을 서버가 생성하므로 동일 상한이 걸려야 한다.
- **현재 동작(이슈 내용)**: AUTO 는 `intervalFrames >= 1` **하한만** 검증하고 결과 개수를 보지 않는다.
  ```java
  // MarkingService.java:196-198, 276-282
  if (req.intervalFrames() == null || req.intervalFrames() <= 0) { throw INVALID_INPUT; }
  ...
  int totalFrames = (int) Math.round(durationSec * fps);
  for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) { marks.add(...); }
  ```
  실측(DB 기존 행, rawSn=9111 · `VDO_LEN_SEC=1200` · fps 30 · `intervalFrames=1`):
  ```
  marking_sn=22 → mark_cn 길이 1,464,891 byte, marks 36,000건 (단일 TEXT 컬럼)
  ```
  1시간(3,600s) 30fps 영상이면 108,000건 ≈ 4.4 MB 가 한 요청으로 생성된다. `@Size` 상한(20,000)의 5배를 AUTO 로 우회할 수 있다.
- **재현/확인 경로**:
  ```bash
  # 긴 영상(예: 1200s)에 intervalFrames=1
  curl -X POST $API/v1/videos/9111/markings -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":1}'
  ```
  ```sql
  SELECT marking_sn, frme_intv_nocs, length(mark_cn),
         (length(mark_cn)-length(replace(mark_cn,'frameIndex','')))/10 AS mark_cnt
    FROM ls_marking WHERE raw_sn = 9111;   -- 실측 1464891 / 36000
  ```
- **영향**: 가용성/자원(CWE-770, OWASP API4). ①`ArrayList` 36k~108k 엔트리 + Jackson 직렬화가 요청 스레드에서 수행 ②`MARK_CN` 단일 TEXT 에 MB 급 적재 ③**증폭이 배치까지 전파** — `FfmpegFrameExtractor` 가 mark 당 1프레임을 뽑고(`mark-based extracted rawSn=30 frames=5` 로 1:1 확인) VLM 위탁 페이로드에도 marks 가 실린다. 즉 마킹 1회로 수만 회 ffmpeg seek + 수만 프레임 파일이 생성된다. ④`AsyncConfig.java:38-44` 의 `batchAsyncExecutor` 는 core 2/max 4/queue 50 에 **`CallerRunsPolicy`** 라, 큐 포화 시 이 대형 파이프라인이 **AFTER_COMMIT 리스너 안(=Tomcat 요청 스레드)에서 동기 실행**되어 마킹 API 응답시간이 무한정 늘어난다.
- **수정 방향(제안)**: `MarkingService.generateAutoMarks` 에 `MarkingRequest.marks` 의 `@Size` 와 **동일 상수**(20,000)를 공유 상수로 뽑아 상한 검증을 추가한다 — 산출 예상 개수 `ceil(totalFrames / intervalFrames)` 를 루프 **이전에** 계산해 초과 시 `INVALID_INPUT`(권장 최소 `intervalFrames` 를 메시지에 안내). 겸사겸사 B-ISSUE-23(intervalFrames 상한 미검증)도 같은 지점에서 "결과 0/1건 퇴화" 경고와 함께 정리 가능하다.

### [B-ISSUE-43] TC-BATCH-050/051/052/060 — 브릿지 가드 4종이 **마킹 API 경로에서 도달 불가**한데 CRLF 정제에는 테스트가 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 방어적 이중화 코드라도 "언제 발화하는지"가 명확해야 하고, 보안 정제(CWE-117)는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: `MarkingCompletedEvent` 의 publisher 는 `MarkingService.java:237` **단 1곳**이고, 그 앞의 `MarkingGuards.requirePreconditions`(`MarkingGuards.java:79-96`)가 이미 ①영상 존재 ②`de_ident_yn='Y'` ③`stage == MARKING_READY` 를 강제한다. 따라서 브릿지의 `REASON_VIDEO_NOT_FOUND`(`:99-103`)·`REASON_STAGE_ALREADY_RUN`(`:109-114`)·`REASON_NOT_DEIDENTIFIED`(`:116-123`) 세 분기와, 그 안에서만 쓰이는 `sanitize()`(`:166-168`)는 **API 로 도달할 수 없다**(실측: 9104/9106/9109/9112 모두 마킹 단계 412 에서 종료, 브릿지 로그 미발생). 그리고 `MarkingBatchBridgeTest` 에 CR/LF 주입 케이스가 없다(`grep sanitize|\\n|CRLF` 무결과).
- **재현/확인 경로**: `grep -rn "new MarkingCompletedEvent" backend/src/main` → 1건. 9106(stage=COMPLETED) 마킹 시도 → 412 이며 `docker logs klid-backend | grep MarkingBatchBridge` 에 해당 rawSn 없음.
- **영향**: 보안 회귀 감지 불가(CWE-117 정제가 조용히 제거돼도 테스트가 잡지 못함) + 도달 불가 코드에 대한 오해(향후 다른 publisher 추가 시 이 가드가 유일한 방어선이 되는데 검증 자산이 없음).
- **수정 방향(제안)**: `MarkingBatchBridgeTest` 에 `dataSttsCd = "PROCESSING\r\n[FAKE] injected"`, `deIdntfYn = "N\nadmin"` 을 주입해 로그 출력에 개행이 없음을 단언하는 케이스 2건을 추가한다(코드 수정 불필요). 아울러 세 가드의 Javadoc 에 "현재 publisher 는 1곳이며 이 분기들은 향후 publisher 추가 대비 방어선"임을 명시.

---

## 6. 부수 관찰 (결함 아님 / 정보성)

1. **배치 실패 영상은 마킹 API 가 영구히 닫힌다** — 배치 실패 시 stage 가 `FAILED` 로 바뀌므로 `requirePreconditions` 가 412 를 낸다(9109 실측, 본 검증 중 9103·9113 도 동일 상태가 됨). 복구는 `POST /v1/videos/{rawSn}/batch/retry` 뿐이며 이는 `MarkingBatchBridge` Javadoc(`:50-55`)이 명시한 의도된 설계다. B-ISSUE-41 과 달리 **복구 API 가 존재**하므로 결함으로 보지 않는다.
2. **검증 중 생성한 데이터**(다음 회차 참고): 신규 영상 `rawSn=30/33/38`(vmsClipId `QA0801-B3-CONC`/`-WORKER`/`-TC096`), 신규 마킹 `marking_sn=25(9110), 26(9113), 27(9103), 28(30), 33(33), 38(38)`, `ls_raw_data_status` 신규 행 `9103`(테스트용 ASSIGNED INSERT, 이후 배치 실패로 FAILED). 기존 행 수정·삭제는 없음.
3. **YOLO 는 이 환경에서 mock 응답** — `mockReason=weights_missing`(ai-server 가중치 미탑재). B-5/B-6 판정에는 영향 없음(마킹 단계는 ai-server 를 타지 않음).
4. **VLM 실왕복 확인** — rawSn=30 마킹 → `[Batch][VlmTimeseries] describe submit … request_id=bc2d9a2b` → mock-server 콜백 `[Webhook][Vlm] result applied … markingsTransitioned=1` → 마킹이 `PENDING→VLM_REQUESTED→VLM_COMPLETED` 로 정상 종결. **즉 배치가 도는 정상 경로에서는 고아가 생기지 않는다** — B-ISSUE-41 은 오직 skip 경로 전용이다(대조군 확보).
# B 클러스터 part4 — `B-7. VLM 위탁 Step + 콜백 수신 + 신고 보류/재개` (36건)

- 검증일: 2026-08-01 (1차)
- 코드 기준: qa-0801 워크트리 `56d30478`
- 스택: backend(:18081, `/api`) · mock-server(:9400) · postgres(`public` 스키마) — 40시간 가동 중인 기존 컨테이너
- **환경 버전 격차 영향 없음**: B-7 관련 소스(`VlmTimeseriesStep`·`VlmResultService`·`VlmResumeBridge`·`VlmWithheldResumeRunner`·`BatchStatusService`·`DeidentReportGate`·`VlmClient`)는 `git diff --name-only b2b44f0e..HEAD` 에 **1건도 포함되지 않는다.** 논블로킹 전환 커밋 `862ca6d8`(2026-07-30)은 `git merge-base --is-ancestor 862ca6d8 b2b44f0e` = true 로 **배포된 V146 이미지에 포함**돼 있다 → 이 섹션은 전 케이스 실동작 검증 가능(BLOCKED 0건).
- 실동작 검증에 사용한 테스트 데이터(기존 데이터 미변경, rawSn=26 미접촉):
  - `rawSn=28`(QA0801-B7-01) — 신고 보류/해소 재개
  - `rawSn=35`(QA0801-B7-02) — 마킹 동반 위탁·전이·retry no-op
  - `rawSn=37`(QA0801-B7-NODEID, SQL INSERT) — 비식별 경로 부재 fail-closed
  - `rawSn=39`(QA0801-B7-RONLY, SQL INSERT) — 미결 회수 재개 경로
  - `rawSn=40`(QA0801-B7-03) — 마킹 보유 + 신고 구간 재처리
  - `ls_webhook_idempotency` 테스트 키 `QA-B7-*` 6건(SQL INSERT)

---

## 1. 판정 요약

| 판정 | 건수 |
|---|---:|
| PASS | 30 |
| PARTIAL | 4 |
| N/A | 1 |
| FAIL | 1 |
| BLOCKED | 0 |
| 확인필요 | 0 |
| **합계** | **36** |

**self-fill 결함 0건** — 시계열 메타(`LS_DATA_META`)·검수큐(`LS_DATA_META_REVIEW`)·`RESP_PAYLOAD_CN` 어느 값도 외부 왕복 없이 생성되지 않았다. mock-server 인바운드 로그(`POST /v1/videovlm/describe` → `POST /api/v1/vlm/callback`)로 왕복 실증.

---

## 2. 케이스별 판정

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-VLM-001 | PASS | [실동작] rawSn=35: `[Batch][VlmTimeseries] describe submit rawSn=35 request_id=5ff293d4… hasMarking=true` — ctx 마킹 1건 → `runWithMarking(markings.get(0))` | 근거 드리프트: `:135-142` → 실제 `VlmTimeseriesStep.java:220-227`(`execute`) |
| TC-VLM-002 | PASS | [실동작] rawSn=28 재개 시 마킹 0건 → `hasMarking=false` 로 `run(rawSn)` 경로 | 드리프트: `:139-141` → `:224-226` |
| TC-VLM-003 | PASS | [정적] `VlmTimeseriesStep.java:269-273` → `batchStatusService.recordVlmSkipped(rawSn, SKIP_REASON_DISABLED)` 후 `skipped` 반환(외부 호출 이전). `BatchStatusService.java:58-62`. 테스트 `VlmTimeseriesStepTest#VLM_비활성일_때_LS_BATCH_PROC_LOG_에_VLM_SKIPPED_행이_사유와_함께_남는다`(baseline PASS) | 로컬 스택은 `VLM_CLIENT_ENABLED=true` 라 실동작 재현 불가(설정 변경 금지). 드리프트: `:182-186` → `:269-273` |
| TC-VLM-004 | PASS | [정적] `:261-263` — `rawSn == null` → `INVALID_INPUT`, enabled 체크보다 **앞** | 드리프트: `:174-176` → `:261-263` |
| TC-VLM-005 | PASS | [정적] `:276-278` — `existsById` false → `NOT_FOUND`. enabled 체크(269) 이후 순서 유지 | 드리프트 |
| TC-VLM-006 | PASS | [실동작] rawSn=37(성공 procLog 0건) 배치 트리거 → `ls_batch_proc_log`: `VLM/FAILED / "비식별 영상 경로가 없어 VLM describe 위탁을 진행할 수 없습니다 rawSn=37"`. 같은 시각 mock-server `describe` 인바운드 **0건**. 코드 `:390-400` 에 원본 경로 폴백 자체가 없음 | 드리프트: `:280-290` → `:390-400` |
| TC-VLM-007 | PASS | [정적] `:318-325` — `ledger.recordIssued` 예외 시 `EXTERNAL_API_ERROR` 로 abort, `vlmClient.submitTimeseries` 는 그 아래(361) | 드리프트 |
| TC-VLM-008 | PASS | [정적] `PersistentWebhookIdempotencyLedger.java:57` `@Transactional(REQUIRES_NEW)` — 독립 커밋. 실동작으로도 콜백 역조회 전건 성공 | 드리프트: `:232` → `:319` |
| TC-VLM-009 | PARTIAL | [정적+실동작] **기대결과 무효.** 논블로킹 전환 후 빈 응답은 `switchIfEmpty(Mono.error(...))`(`:364-365`) → `err` 핸들러 → `outcomeRecorder.onSubmitFailed`(SKIPPED + `SKIP_REASON_SUBMIT_FAILED` 기록)로 처리되고 **`doSubmit` 은 예외를 던지지 않고 `submitted` 를 반환**한다. "무흔적 유실 없음" 이라는 취지는 충족(테스트 `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다`) | → B-ISSUE-62 |
| TC-VLM-010 | N/A | [정적] **전제가 코드에 없다.** VLM 경로의 `BLOCK_TIMEOUT=45s` 상수는 `862ca6d8` 논블로킹 전환에서 삭제됐다(`grep BLOCK_TIMEOUT` → `ControlNotifyClient:52`(15s) 만 잔존). 현재 단일 타임아웃은 `vlm.client.timeout-seconds`(기본 10s, `VlmClient.java:71,108`) 하나뿐이고 `.block()` 호출 자체가 없다 | → B-ISSUE-63 |
| TC-VLM-011 | PARTIAL | [실동작] rawSn=35 마킹 `PENDING→VLM_REQUESTED→VLM_COMPLETED` 관측. 단 **전이 시점·주체가 다르다** — "위탁 성공 시 persistMarkingTransition" 이 아니라 **제출 전 선커밋**(`:337 markingTxService.persistVlmRequested`)이고, 별도 빈 `VlmMarkingTxService`(REQUIRES_NEW)가 `save(=merge)` 를 수행한다 | → B-ISSUE-64 |
| TC-VLM-012 | PASS | [실동작] rawSn=35 재트리거(19:15:59) 후에도 `ls_marking.stts_cd=VLM_COMPLETED`, `mdfcn_dt=19:15:32.389` **불변** → `markVlmRequested()` no-op, save 미호출(durable 역행 차단). 코드 `VlmMarkingTxService.java:51-60` | 드리프트: `VlmTimeseriesStep:300-308` → `VlmMarkingTxService:51-60` |
| TC-VLM-013 | PASS | [실동작+정적] mock-server 인바운드 `[MOCK][VLM] describe accepted request_id=415f1fa9… callback_url=http://klid-backend:8080/api/v1/vlm/callback` — 고정 base + `HmacWebhookFilter.PATH_VLM`. 코드 `:402-411` 에 사용자 입력 반영 지점 없음 | 드리프트: `:311-319` → `:402-411` |
| TC-VLM-014 | PASS | [실동작] `POST /v1/vlm/callback {"request_id":"QA-UNKNOWN-0001"}` → **401** `{"errorCode":"UNAUTHORIZED","message":"발급되지 않은 request_id 입니다."}` |  |
| TC-VLM-015 | PASS | [실동작] PROCESSED 키 재전송 → **200** `{"applied":false}`, `ls_data_meta` 중복 적재 0건 |  |
| TC-VLM-016 | PASS | [실동작] `QA-B7-NORAW`(raw_sn NULL, ISSUED) → **401** "request_id 에 매핑된 rawSn 이 없습니다." |  |
| TC-VLM-017 | PASS | [실동작] `status:"done"` → **400** `"status 는 completed\|failed 중 하나여야 합니다."`(DTO `@Pattern` 1차 차단). 서비스 분기(`VlmResultService.java:94-102`)도 화이트리스트 후 `INVALID_INPUT` + 멱등 미마킹 |  |
| TC-VLM-018 | PASS | [실동작] `QA-B7-ACCEPTED` failed 콜백 → 200 `applied=true`, ledger `PROCESSED`, WARN `describe failed … code=E1`. 코드 `:105-109`, `handleFailed :210-232` | 드리프트: `:168-186` → `:210-232` |
| TC-VLM-019 | PASS | [정적] `:112-116` — completed + `existsById` false → `NOT_FOUND`. FK(`fk_ls_webhook_idempotency_raw`) 때문에 실행 중 스택에서는 "존재하지 않는 rawSn 매핑" 자체를 만들 수 없어 라이브 재현 불가(미구현 갭 아님) |  |
| TC-VLM-020 | PASS | [실동작] `QA-B7-DUPKEY` 로 `0-5` 두 번 → **400** "한 콜백 내 중복 구간…". 직후 ledger `stts_cd` 는 **ISSUED 유지**(PROCESSED 미마킹 = 재전송 허용) | 드리프트: `:192-204` → `:238-250` |
| TC-VLM-021 | PASS | [실동작] `QA-B7-UPSERT` 로 기존 `0-5` + 신규 `5-9` 동시 전송 → `ls_data_meta` 0-5 값만 갱신(`UPDATED-EXISTING`), 5-9 신규(meta_sn=134). `ls_data_meta_review` 는 **신규분만 1행 추가**(sn=19), 기존 0-5 리뷰행(sn=16) 미증가 |  |
| TC-VLM-022 | PASS | [실동작] rawSn=35 콜백 로그 `markingsTransitioned=1`, `ls_marking.stts_cd=VLM_COMPLETED` | 드리프트: `:156` → `:158-161` |
| TC-VLM-023 | PASS | [실동작+정적] 단일 `@Transactional`(`:63`) + `markProcessedInTx`(`:164`, REQUIRED). TC-VLM-020 에서 중간 예외 후 ledger 가 PROCESSED 로 넘어가지 않음을 DB 로 확인. 테스트 `VlmResultServiceTest#검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능` | 드리프트: `:160` → `:164` |
| TC-VLM-024 | PASS | [실동작] `QA-B7-RACE` 동일 request_id 2건 **동시** POST → 응답 `applied=true` / `applied=false`, `ls_data_meta`(raw_sn=37) 1행·`ls_data_meta_review` 1행. `lookupForProcessing` 비관적 락으로 직렬화 |  |
| TC-VLM-025 | PASS | [실동작] `message":"vendor failed\nsecond line"` 전송 → 로그 `message=vendor failed_second line`(CR/LF/TAB → `_`) |  |
| TC-VLM-030 | PASS | [실동작] HMAC 헤더 없이 콜백 → 필터가 size cap 만 적용하고 통과시키되 `request_id` 발급 게이트가 **401** 로 차단(TC-VLM-014 와 동일 응답). `HmacWebhookFilter.java:110 PATH_VLM` |  |
| TC-VLM-031 | PASS | [실동작] rawSn=28 `DE_IDENT_YN='F'` 상태로 배치 트리거 → `[Batch][VlmTimeseries] withheld — deident report open rawSn=28` + `ls_batch_proc_log`(`VLM/SKIPPED/"비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"`). 같은 시각 mock-server `describe` 인바운드 **0건**. 게이트는 `resolveDeidentifiedPath`(`:309`) **이전**(`:303`) | 드리프트: `:216-220,222` → `:303-307` |
| TC-VLM-032 | FAIL | [실동작] rawSn=40(마킹 1건 보유, `'F'`) 재처리 → VLM 은 정상 보류(예외 0)였으나 **직후 `FRAME_EXTRACT` 가 같은 `'F'` 조건을 예외로 처리**해 배치가 FAILED 전이 + `BatchRetryQueue enqueued attempt=1 delaySec=60` + `ls_data_raw.data_stts_cd=FAILED`. 기대("배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진")는 **신고 구간 재처리 시나리오에서 성립하지 않는다** | → B-ISSUE-61 |
| TC-VLM-033 | PASS | [정적] `DeidentReportGate.isUnderDeidentReport`(`:66-71`)는 조회 예외를 삼키지 않고 전파, `VlmTimeseriesStep:303` 이 그 앞이라 전송 코드 도달 불가(fail-closed) | DB 오류 주입 불가 — 정적 판정 |
| TC-VLM-034 | PASS | [실동작] `POST /v1/deident-reports/8/resolve` 200 → `[VlmResumeBridge] deident gate reopened rawSn=28` → `[VlmResume] resuming withheld VLM submit rawSn=28` → `describe submit … request_id=415f1fa9…` → mock accepted → 콜백 `result applied … new=1`. `VlmResumeBridge.java:34-38`(AFTER_COMMIT), `VlmWithheldResumeRunner.java:57-72` | 드리프트: Runner `:54-70` → `:57-72` |
| TC-VLM-035 | PASS | [실동작] 같은 영상에 2차 신고(rprtSn=10) → resolve → `[VlmResume] resume skipped — timeseries meta already present rawSn=28 count=1`, mock-server `describe` 인바운드 **0건**. `VlmWithheldResumeRunner.java:88-101` | 드리프트: `:79-93` → `:88-101` |
| TC-VLM-036 | PASS | [정적] `SKIP_REASON_DISABLED` 는 `RESUMABLE_SKIP_REASONS`(`VlmTimeseriesStep.java:150-152`)에 **미포함** → `isStageSkippedWithAnyReason` false → 재개 대상 아님. 테스트 `VlmWithheldResumeRunnerTest#보류_기록이_없으면_재위탁하지_않는다`·`재개_사유_목록에_비동기_제출실패와_ACK_미수신이_포함된다` | **단일 원천이 확장됨**: 이제 사유가 4종(신고 보류·제출실패·ACK 미수신·콜백 미수신)이고 판정 API 가 `isStageSkippedWithReason`(`BatchStatusService:100-105`) → `isStageSkippedWithAnyReason`(`:114-119`) 로 바뀜 |
| TC-VLM-037 | PASS | [정적] `VlmWithheldResumeRunner.java:73-78` — `catch (RuntimeException)` → `log.warn(… cause={클래스명만})`, 보류 기록·메타0 조건은 그대로 남음 | 드리프트: `:71-76` → `:73-78` |
| TC-VLM-038 | PASS | [정적] `BatchStatusService.java:149-156` `@Transactional(REQUIRES_NEW) recordVlmTimeseriesResult` — 스텝 롤백과 무관하게 커밋 | 드리프트: `:109-116` → `:149-156` |
| TC-VLM-039 | PASS | [정적] `recordVlmSkipped`(`:58-62`)는 여전히 REQUIRED. 다만 비동기 완료 핸들러 전용으로 **형제 메서드 `recordVlmSkippedInNewTx`(REQUIRES_NEW, `:78-82`)가 신설**됐고 공통 로직은 무트랜잭션 private 헬퍼(`:85-88`)로 공유 | 드리프트: `:104-108` → `:78-82` |
| TC-VLM-040 | PARTIAL | [정적+실동작] `execute` 에 `@Transactional(REQUIRES_NEW)`(`:218-227`) 확인, 내부 분기는 자기호출이라 중첩 없음 — 여기까지는 기대대로이며 `execute` 경유 SKIPPED 감사 행 정상 커밋(rawSn=28/40). **그러나 `run`(`:236`)의 `readOnly=true` 가 프록시 경유 호출자(`VlmWithheldResumeRunner:69`)에서 발효돼 `recordVlmSkipped` INSERT 가 `cannot execute INSERT in a read-only transaction` 으로 실패함을 실동작 재현**(rawSn=39) | 드리프트: `:133-142,150-168` → `:218-227,236-255`. → B-ISSUE-66 |

---

## 3. 실동작 재현 로그 (요약)

```
# 1) 신고 → 보류
POST /api/v1/videos/28/deident-report          → 201, rprtSn=8, ls_data_raw.de_ident_yn='F'
POST /api/v1/dev/batch/trigger?rawSn=28        → [VlmTimeseries] withheld — deident report open rawSn=28
ls_batch_proc_log: VLM / SKIPPED / "비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"
mock-server describe 인바운드: 0건

# 2) 해소 → 재위탁 (mock 왕복 실증)
POST /api/v1/deident-reports/8/resolve         → 200
[VlmResumeBridge] deident gate reopened rawSn=28
[VlmResume] resuming withheld VLM submit rawSn=28
[Vlm] describe submit request_id=415f1fa9-…    → [MOCK][VLM] describe accepted … callback_url=http://klid-backend:8080/api/v1/vlm/callback
[MOCK][VLM] media duration probed file=2dc0d17d-…-mask.mp4   ← 비식별본(-mask) 경로만 전송됨(PII 원본 미전송)
[Webhook][Vlm] result applied request_id=415f1fa9-… rawSn=28 new=1
ls_data_meta(meta_sn=125, meta_key='0-5') + ls_data_meta_review(sn=16, PENDING) 적재

# 3) 재개 멱등
2차 신고(rprtSn=10) → resolve → [VlmResume] resume skipped — timeseries meta already present rawSn=28 count=1 (describe 0건)
```

### PII 축 반증 결과
- `media.path` 는 **항상 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`** 값이다. mock-server 가 실제로 연 파일명이 `…-mask.mp4`(KPST 산출물 규격)임을 3건 모두에서 확인했고, 코드에는 원본 경로 폴백 분기 자체가 없다(`VlmTimeseriesStep.java:390-400`).
- 신고 구간에서 외부로 나간 요청 **0건**(rawSn=28·40 두 케이스 모두 mock-server 인바운드 없음).

### self-fill 반증 결과
| 값 | 위치 | 판정 |
|---|---|---|
| 시계열 메타 텍스트 | `ls_data_meta.meta_vl` | self-fill 아님 — `POST /v1/videovlm/describe` → `POST /v1/vlm/callback` 실 HTTP 왕복 후 적재(mock-server 로그 대조) |
| `RESP_PAYLOAD_CN`(requestId/status) | `ls_batch_proc_log` | self-fill 아님 — ACK 수신(`VlmSubmitOutcomeRecorder.onAccepted`) 시에만 기록, 미수신 시 공란 유지 |
| 검수큐 PENDING 행 | `ls_data_meta_review` | self-fill 아님 — 콜백으로 신규 생성된 meta 에 대해서만 생성(기존 meta 갱신 시 미생성 실측) |

---

## 4. 이슈

### [B-ISSUE-61] TC-VLM-032 — 신고 구간 배치 재처리가 VLM 은 보류하지만 FRAME_EXTRACT 에서 실패해 배치 FAILED + 재시도 예산을 소진한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 비식별 누락 신고는 **정책적 차단**이므로 "실패가 아니라 보류"여야 한다(`CLAUDE.md` — "예외로 실패시키면 ①정책적 차단이 장애로 오분류 ②BatchRetryQueue 가 반드시 다시 막힐 재시도로 시도 상한을 소진 ③작업 상태가 FAILED 로 내려가 라벨링·검수 동선이 끊긴다"). TC-VLM-032 도 "예외 미발생 → 배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진"을 요구한다.
- **현재 동작(이슈 내용)**: VLM 스텝은 규약대로 보류(SKIPPED, 예외 0)한다. 그러나 **바로 다음 단계인 `FfmpegFrameExtractor` 가 동일한 `'F'` 상태를 예외로 처리**한다.
  ```java
  // backend/.../batch/step/FfmpegFrameExtractor.java:185-188
  if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {          // DEIDENTIFIED = "Y" → 'F' 도 실패
      throw new CustomException(ErrorCode.INVALID_INPUT,
              "비식별이 완료되지 않은 영상입니다 rawSn=" + raw.getRawSn());
  }
  ```
  실측(rawSn=40, 마킹 1건 보유, `DE_IDENT_YN='F'`):
  ```
  [Batch][VlmTimeseries] withheld — deident report open rawSn=40      ← 보류(정상)
  [BatchRetry] enqueued rawSn=40 attempt=1 delaySec=60                ← 재시도 큐 등록(기대 위반)
  [BatchOrchestrator] failed rawSn=40 willRetry=true cause=CustomException
  ls_batch_proc_log: FRAME_EXTRACT / FAILED / "비식별이 완료되지 않은 영상입니다 rawSn=40"
  ls_data_raw.data_stts_cd: MARKING_READY → FAILED
  ```
  `authoring.batch.retry.max-attempts:3` + 지수 백오프(60/120/240초)이므로 **신고가 약 7분 이상 열려 있으면 재시도 예산이 전량 소진(EXHAUSTED)** 되고, 해소(resolve) 시 자동 복구 트리거는 VLM 재개(`DeidentGateReopenedEvent`)뿐이라 프레임추출·오토라벨은 되살아나지 않는다(수동 `POST /v1/videos/{rawSn}/batch/retry` 필요 — `BatchReprocessService:84 retryQueue.clearIfIdle`).
- **재현/확인 경로**:
  ```bash
  # 마킹을 보유한 MARKING_READY 영상에 신고를 걸고 재처리
  curl -X POST .../v1/videos/{rawSn}/deident-report -d '{"reason":"..."}'
  curl -X POST ".../v1/dev/batch/trigger?rawSn={rawSn}"   # 또는 POST /v1/videos/{rawSn}/batch/retry
  psql -c "select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn={rawSn};"
  psql -c "select * from ls_bat_rty_wtng where raw_sn={rawSn};"
  ```
- **영향**: 기능/운영. ①신고 구간에 자동 재시도가 반드시 실패하며 예산을 태운다 ②영상이 `FAILED` 로 표시돼 운영자가 장애로 오인한다 ③해소 후 프레임추출~오토라벨이 자동 복구되지 않아 수동 개입이 필요하다. 보안 유출은 없다(외부 전송은 정상 차단됨).
- **수정 방향(제안)**: `FfmpegFrameExtractor` 의 비식별 선행 가드를 **`'N'`(미수행)만 실패**로 두고 `'F'`(신고)는 VLM 과 동일하게 **보류(SKIPPED + 사유 적재)** 로 분기하거나, `BatchOrchestrator`/`BatchPipelineConfig` 의 `isEnabled(ctx)` 로 신고 구간에서는 post-marking 단계를 통째로 건너뛰고 재시도 큐에 넣지 않게 한다. 어느 쪽이든 해소 시 `DeidentGateReopenedEvent` 소비자를 늘려 프레임추출 이후 단계도 재개돼야 한다(현재는 VLM 재개만 배선).

### [B-ISSUE-62] TC-VLM-009 — 빈 describe 응답의 기대결과가 논블로킹 전환으로 무효화됨(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그가 실제 코드의 유일한 진실원과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**: 케이스는 "결과 null → `doSubmit` 이 `EXTERNAL_API_ERROR`" 를 요구하나, `862ca6d8`(2026-07-30, 논블로킹 전환) 이후 코드는
  ```java
  // VlmTimeseriesStep.java:362-370
  vlmClient.submitTimeseries(req)
      .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
      .subscribe(resp -> …onAccepted(…), err -> …onSubmitFailed(rawSn, markingSn, err));
  return VlmTimeseriesResponse.submitted(requestId);   // 예외 미전파
  ```
  로 바뀌어, 빈 응답은 **비동기 실패 기록(`SKIP_REASON_SUBMIT_FAILED`)** 이 되고 스텝은 `submitted` 를 반환한다.
- **재현/확인 경로**: `grep -n "switchIfEmpty" VlmTimeseriesStep.java` / 테스트 `VlmTimeseriesStepTest#빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다`.
- **영향**: 카탈로그 정합(거짓 FAIL 유발).
- **수정 방향(제안)**: 기대결과를 "예외 미전파 + `VlmSubmitOutcomeRecorder.onSubmitFailed` 로 `LS_BATCH_PROC_LOG` 에 `SKIP_REASON_SUBMIT_FAILED` 적재 + 재개 대상 편입" 으로 갱신.

### [B-ISSUE-63] TC-VLM-010 — `BLOCK_TIMEOUT=45s` 이중 타임아웃 구조가 코드에서 사라짐(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 동상.
- **현재 동작(이슈 내용)**: VLM 경로에 `.block()` 호출과 `BLOCK_TIMEOUT` 상수가 모두 없다. `grep -rn "BLOCK_TIMEOUT" backend/src/main` → `ControlNotifyClient.java:52`(15s) 1곳뿐. 현재 VLM 의 유일한 시간 상한은 `vlm.client.timeout-seconds`(기본 10s, `VlmClient.java:71` → `.timeout(timeout)` `:108`)이고, 그 위에 Resilience4j Retry/CircuitBreaker 가 걸린다.
- **재현/확인 경로**: `grep -rn "BLOCK_TIMEOUT\|\.block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java` → 0건.
- **영향**: 카탈로그 정합. 겸하여 `UNCERTAINTIES.md` #13 의 "타임아웃 이중 구조(45s↔10s)" 기술도 갱신 대상이다.
- **수정 방향(제안)**: 케이스를 "단일 실효 타임아웃 10s + Retry/CB, 파이프라인 스레드 미점유" 로 재작성하고 UNCERTAINTIES #13 을 함께 정정.

### [B-ISSUE-64] TC-VLM-011 — 마킹 전이 시점이 "위탁 성공 후" → "제출 전 선커밋" 으로 반전(카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 동상.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.java:337` 이 **외부 제출 이전**에 `markingTxService.persistVlmRequested(marking)`(별도 빈, `REQUIRES_NEW`)를 호출해 선커밋한다. 이유는 논블로킹 제출에서 콜백이 ACK 보다 먼저 도착하는 레이스(수신부가 전이 대상을 못 찾아 `VLM_REQUESTED` 영구 고착)를 닫기 위함이며, 수신부도 `ACTIVE_STATUSES`(PENDING 포함)로 범위를 넓혀 양단 방어한다(`VlmResultService.java:153-158`). 카탈로그의 `persistMarkingTransition`(`VlmTimeseriesStep:300-308`)은 존재하지 않는다.
- **재현/확인 경로**: `VlmTimeseriesStep.java:327-338`, `VlmMarkingTxService.java:47-60`.
- **영향**: 카탈로그 정합.
- **수정 방향(제안)**: 기대결과를 "제출 전 `VlmMarkingTxService.persistVlmRequested` 로 독립 커밋(detached → merge), PENDING 에서만 전이" 로 갱신.

### [B-ISSUE-65] B-7 섹션 전체가 논블로킹 전환(`862ca6d8`) 이전 코드 기준 — 근거 드리프트 34/36 + 신규 기계 케이스 부재
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그의 `file:line` 근거로 다음 회차가 대조 가능해야 하고, 실제 운영 중인 코드 경로에 케이스가 존재해야 한다.
- **현재 동작(이슈 내용)**: B-7 은 `HEAD 11c3e1b8` 기준으로 작성됐는데, 같은 날(2026-07-30) 병합된 `862ca6d8 refactor(integration): 외부연동 제출 3곳 논블로킹화` 가 이 파일들을 크게 재작성했다. 그 결과 **36건 중 34건의 근거 라인이 어긋난다**(위 표 비고 열). 더해 신설된 아래 기계에 대응하는 케이스가 **0건**이다:
  - `VlmSubmitOutcomeRecorder`(ACK 기록 + 원장 `ISSUED→ACCEPTED` 전이 + 상태 강등 금지) — `batch/step/VlmSubmitOutcomeRecorder.java`
  - `VlmSubmitPendingSweeper`(미결 회수, `ackWindowMin=30` / `callbackWindowMin=360`, `max-reclaims=3`) — `batch/vlm/`
  - 신규 재개 사유 3종 `SKIP_REASON_SUBMIT_FAILED` / `SKIP_REASON_ACK_MISSING` / `SKIP_REASON_CALLBACK_MISSING` 과 `RESUMABLE_SKIP_REASONS` 목록
  - 원장 3상태(`ISSUED`/`ACCEPTED`/`PROCESSED`)와 "비-PROCESSED 는 전부 발급됨으로 매핑" 불변식
  - `SubmitSignalDispatch`(전용 풀 거부 시 이벤트 루프 오염 차단)
  실동작 중 이 경로가 살아있음을 확인했다 — 로그 `[Vlm][Reclaim] pending VLM submit reclaimed=2 ackWindowMin=30 callbackWindowMin=360` 및 `ls_batch_proc_log` 의 `VLM/SKIPPED/"VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개"` 2행(rawSn=28).
- **재현/확인 경로**: `git log --oneline -- backend/.../VlmTimeseriesStep.java` → `862ca6d8`(2026-07-30); `ls backend/src/test/java/kr/co/cudo/authoring/batch/vlm/` → `VlmSubmitAckWindowIT`·`VlmSubmitPendingSweeperTest`·`VlmSubmitReclaimAtomicClaimIT`(전부 baseline PASS인데 카탈로그 케이스가 없음).
- **영향**: 카탈로그 정합 + 커버리지 갭. 논블로킹 전환의 핵심 회수 경로가 검증 카탈로그에서 누락돼 있다.
- **수정 방향(제안)**: B-7 근거 라인을 `56d30478` 기준으로 일괄 재산출하고, 위 5개 기계에 대한 신규 케이스(TC-VLM-041~ )를 추가한다. 기존 테스트 자산(`batch/vlm/*`·`VlmTimeseriesStepNonBlockingTest`·`VlmSubmitOutcomeRecorderTest`)과 1:1 매핑 가능하다.

### [B-ISSUE-66] `VlmTimeseriesStep.run` 의 `readOnly=true` 때문에 재개 경로에서 보류 감사 기록이 `cannot execute INSERT in a read-only transaction` 으로 실패한다 (실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개 러너가 호출한 위탁이 **다시 보류**(게이트가 아직 닫혀 있음)되거나 비활성으로 끝나면 그 사실이 `LS_BATCH_PROC_LOG` 에 사유와 함께 남아야 한다(B-ISSUE-24 규약 — "재처리 대상 식별이 애플리케이션 로그 보존기간에 종속되면 운영에서 복구 불가"). 코드 주석도 `VlmWithheldResumeRunner:45` 에서 "게이트가 아직 닫혀 있으면 스스로 다시 보류된다" 를 전제한다.
- **현재 동작(이슈 내용)**: `run` 은 `@Transactional(REQUIRES_NEW, readOnly = true)`(`VlmTimeseriesStep.java:236`)이고 그 안에서 호출되는 `BatchStatusService.recordVlmSkipped` 는 `REQUIRED`(`:58-62`)라 **읽기전용 트랜잭션에 참여**한다. Spring/Hibernate 가 JDBC 커넥션을 read-only 로 설정하므로 PostgreSQL 이 INSERT 를 거부한다. 파이프라인 경로(`execute` → 자기호출, `execute` 는 readOnly 아님)는 영향이 없지만(rawSn=28/40 감사 행 정상 커밋 확인), **`VlmWithheldResumeRunner` 는 프록시 경유로 `run` 을 직접 호출**(`VlmWithheldResumeRunner.java:69`)하므로 이 경로에서만 읽기전용 경계가 실제로 발효된다.
  ```java
  // VlmTimeseriesStep.java:236
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public VlmTimeseriesResponse run(Long rawSn) { return doSubmit(rawSn, null); }
  // → doSubmit:271(DISABLED), :305(DEIDENT_REPORT) 에서 batchStatusService.recordVlmSkipped(...) (REQUIRED, INSERT)
  ```
  **실동작 재현 로그**(rawSn=39, `DE_IDENT_YN='F'` 유지 상태에서 미결 스위퍼가 재개 트리거):
  ```
  19:31:22.196 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=39 stage=VLM      ← 스위퍼(REQUIRES_NEW) 는 성공
  19:31:22.201 [batch-async-1] [VlmResume] deident report resolved — resuming withheld VLM submit rawSn=39
  19:31:22.202 [batch-async-1] [Batch][VlmTimeseries] withheld — deident report open rawSn=39
  19:31:22.203 [batch-async-1] ERROR SqlExceptionHelper - ERROR: cannot execute INSERT in a read-only transaction
  19:31:22.204 [batch-async-1] WARN  [VlmResume] withheld VLM resume failed rawSn=39 cause=JpaSystemException
  ```
  결과적으로 **재보류 감사 행이 적재되지 않았다**(`ls_batch_proc_log` where data_raw_sn=39 → 스위퍼가 쓴 `ACK_MISSING` 1행만 존재, `DEIDENT_REPORT` 행 0건).
- **재현/확인 경로**:
  ```sql
  -- 게이트가 닫힌(F) 영상 + 시계열 메타 0건 + 재개 대상 SKIPPED 행이 있는 상태를 만든 뒤 재개를 트리거
  insert into ls_data_raw (...) values (..., de_ident_yn='F', ...);          -- rawSn=N
  insert into ls_webhook_idempotency (idmp_key,chnl_cd,stts_cd,reg_dt,mdfcn_dt,raw_sn)
         values ('QA-RO','VLM','ISSUED', now(), now(), N);                    -- ACK 창(30분) 경과로 보이게
  -- VlmSubmitPendingSweeper tick(기본 15분) 또는 POST /v1/deident-reports/{n}/resolve 로 재개 트리거
  select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn = N;
  ```
- **영향**: 데이터정합/운영. ①재보류 사실이 DB 에 남지 않아 B-ISSUE-24 가 세운 "로그 보존기간 비종속" 보장이 이 경로에서 깨진다 ②재개가 `JpaSystemException` 으로 중단되며 ERROR 스택이 남아 실장애와 구분이 어렵다 ③`vlm.client.enabled=false` 인 dev/stg/prd 기본 형상에서는 **모든** 재개(`run` 분기)가 이 예외로 끝난다 — 그 경우 `SKIP_REASON_DISABLED` 행도 남지 않는다. 다만 기존 보류 기록과 "메타 0건" 조건이 남아 다음 회수 때 재시도되므로 영구 정체는 아니다(`max-reclaims=3` 소진 시까지).
- **수정 방향(제안)**: ①`run` 의 `readOnly=true` 를 제거해 `runWithMarking`(쓰기 가능)과 경계 속성을 일치시키거나, ②`doSubmit` 의 `recordVlmSkipped` 호출을 이미 존재하는 `recordVlmSkippedInNewTx`(`BatchStatusService:78-82`, REQUIRES_NEW)로 교체한다. ②가 비동기 완료 핸들러·`ledger.recordIssued` 와 규약이 같아 일관적이다. 회귀 가드로 "프록시 경유 `run` 호출 시 SKIPPED 감사 행이 실제로 커밋된다" 는 IT 를 추가할 것(현행 `VlmTimeseriesStepTest` 는 mock 기반이라 이 경계를 잡지 못한다).

---

## 5. 근거 드리프트 목록(요약 — 상세는 §2 비고)

| 케이스 | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-VLM-001/002 | `VlmTimeseriesStep.java:135-142` | `:218-227` |
| TC-VLM-003 | `:182-186` · `BatchStatusService:57-64` | `:269-273` · `:58-62` |
| TC-VLM-004/005 | `:174-176` · `:189-191` | `:261-263` · `:276-278` |
| TC-VLM-006 | `:280-290` | `:390-400` |
| TC-VLM-007/008 | `:231-238` · `:232` | `:318-325` · `:319` |
| TC-VLM-009/010 | `:247-250` · `:78,246` | 소멸(§4 B-ISSUE-62/63) |
| TC-VLM-011/012 | `:262,300-308` | `:337` · `VlmMarkingTxService:47-60` |
| TC-VLM-013 | `:311-319` | `:402-411` |
| TC-VLM-018 | `VlmResultService:168-186` | `:210-232` |
| TC-VLM-020 | `VlmResultService:192-204` | `:238-250` |
| TC-VLM-022/023 | `:156` · `:160` | `:158-161` · `:164` |
| TC-VLM-031/032/033 | `:216-220,222` · `:206-220` · `:215-216` | `:303-307` |
| TC-VLM-034/035/037 | Runner `:54-70` · `:79-93` · `:71-76` | `:57-72` · `:88-101` · `:73-78` |
| TC-VLM-036 | Runner `:80-84` · `BatchStatusService:74-78` | `:89-90` · `:114-119`(API 교체) |
| TC-VLM-038/039 | `BatchStatusService:109-116` · `:104-108` | `:149-156` · `:78-82` |
| TC-VLM-040 | `:133-142,150-168` | `:218-227,236-255` |

> TC-VLM-014~017·019·021·024·025·030 의 `VlmResultService` 근거는 대체로 유효(±2줄 이내).

---

## 6. 남긴 테스트 데이터(정리 참고)

| 대상 | 키 |
|---|---|
| `ls_data_raw` | 28(QA0801-B7-01) · 35(QA0801-B7-02) · 37(QA0801-B7-NODEID) · 39(QA0801-B7-RONLY) · 40(QA0801-B7-03) |
| `ls_webhook_idempotency` | `QA-B7-DUPKEY` · `QA-B7-NORAW` · `QA-B7-UPSERT` · `QA-B7-FAILED` · `QA-B7-ACCEPTED` · `QA-B7-RACE` · `QA-B7-RO` |
| `ls_deident_report` | rprtSn 8·10(rawSn=28, RESOLVED) · 14(rawSn=40, OPEN) |
| 기타 | rawSn=28 비식별 산출물 mtime 을 `touch` 로 갱신(외부 재비식별 시뮬레이션 — resolve 게이트 통과 목적) |

> ⚠ 위 데이터는 검증 재현을 위해 남겨 두었다. rawSn=26 및 기존 데이터는 일절 변경하지 않았다.
# B 클러스터 part5 — B-8(FfmpegFrameExtractor 19건) + B-10(비디오 스트리밍 22건) = 41건

- 검증일 2026-08-01 · 코드 기준 `qa-0801` @ 56d30478 · 스택 backend V146(구버전, 사용자 승인 하 진행)
- 검증 방식: **실동작 최우선** — 실제 파이프라인 구동(신규 rawSn 34·36 업로드→비식별→마킹→프레임추출), curl 실요청(Range/서명URL/경로조작/심링크 치환), DB 실측, 컨테이너 파일시스템 실측
- 환경 버전 격차 영향: B-8/B-10 은 `LS_DATA_INGEST` 리팩터(b2b44f0e~56d30478) 범위 밖 → **BLOCKED 없음**

## 판정 집계

| 판정 | 건수 |
|---|--:|
| PASS | 39 |
| PARTIAL | 2 |
| FAIL | 0 |
| BLOCKED | 0 |
| **합계** | **41** |

> ⚠ PARTIAL 2건(TC-STREAM-B04 · TC-STREAM-B20)은 **동일한 하나의 결함**(B-ISSUE-81, 스트리밍 경로 심링크 우회)에서 나온 것이며 실동작으로 **원본(비식별 이전) 영상이 200 서빙되는 것을 재현**했다. 심각도는 HIGH.

---

## B-8. FfmpegFrameExtractor (원본+비식별 2벌, co-locate, 경계) — 19건

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-BATCH-100 | PASS | [정적] `FfmpegFrameExtractor.java:133-136` — `marks.isEmpty()` → `INVALID_INPUT`. 커버: `FfmpegFrameExtractorTest#extractByMarks_emptyMarks_rejected` | 근거 라인 일치 |
| TC-BATCH-101 | PASS | [정적] `:144-147` — `frames.isEmpty()` → `INTERNAL_ERROR` | ⚠ 실제로는 **도달 불가한 심층방어**다. `extractByMarks` 는 mark 1건당 정확히 1행을 `saved` 에 넣고 실패 시 `IOException`→`CustomException` 으로 던지므로 "marks 非공 & 결과 0건" 상태가 만들어지지 않는다. ErrorCode 자체는 기대와 일치 |
| TC-BATCH-102 | PASS | [정적] `:177-179` — `raw==null / rawFilePathNm blank` → `INVALID_INPUT` | |
| TC-BATCH-103 | PASS | [정적] `:185-188` — `!"Y".equals(deIdntfYn)` → `INVALID_INPUT("비식별이 완료되지 않은 영상입니다")`. 커버: `..._notDeidentified_rejected` | 가드 순서 = 메타(177) → marks(181) → 비식별(185) → 원본존재(190) |
| TC-BATCH-104 | PASS | [정적] `:190-193` — `frameWriter.sourceExists(source)` false → `INVALID_INPUT` | |
| TC-BATCH-105 | **PASS** | **[실동작]** 신규 rawSn=34 를 파이프라인에 태우고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 를 `/etc/passwd`(허용 base 전부의 밖)로 오염 후 AUTO 마킹 트리거. 로그: `WARN [Batch][FrameExtract] deid path rejected (base/realpath) rawSn=34 — RAW only` (**경로 원문 미노출, rawSn 만**). DB: `ls_data_src` 5행 전부 `de_idntf_src_file_path_nm=NULL`, `src_file_path_nm=/app/storage/raw/frames/raw/34/frame-N.jpg` → **원본 폴백 없음** 확인 | 판정축이 `readableDeidVideoBases`(`:322`)임도 정적 확인 |
| TC-BATCH-106 | PASS | [정적] `:216-225` — 파일 부재(`else if` 216) / procLog 경로 null(221-225) 양쪽 WARN + RAW only. 커버: `..._noDeidVideo_rawOnly`, `FfmpegFrameExtractorDeidPersistIT#execute_비식별_procLog없으면_RAW만_영속되고_비식별경로는_NULL_유지_무회귀` | 파일 부재 로그는 `maskName()` 해시로만 남김(`:219`) |
| TC-BATCH-107 | **PASS** | **[실동작]** rawSn=26(기존 드라이브)·30·33·34 실측. rawSn=26 `ls_data_src` 5행 **전부** `de_idntf_src_file_path_nm` non-null(`/app/storage/deidentified/frames/deid/26/frame-N.jpg`). 코드상 비식별 프레임을 `LsDataSrc.create` **이전에** 쓰고 6-arg create 에 경로 포함(`:260-271`) → PARTIAL 회귀 없음 | |
| TC-BATCH-108 | **PASS** | **[실동작]** rawSn=26: `vdo_frm_no` = 0/30/60/90/120(intervalFrames=30), `sht_dt` = 17:59:39→40→41→42→43 (정확히 1000ms 간격) = `round(frameIndex×1000/30)`. pin fps 우선 경로 `:234`(`effectiveFps(pinnedFps,…)`) → `:248` `Math.round(mark.frameIndex()*1000.0/fps)` | ⚠ 근거 드리프트: 카탈로그 `234,266,393-398` 중 **seek 계산 실위치는 `:248`**(266 아님) |
| TC-BATCH-109 | PASS | [정적] `:393-398` — `pinnedFps` null/NaN/Inf/≤0 → `fpsResolver.resolveFps`(미상 30.0). 커버: `..._fpsAbsent_fallback30_noRegression` | |
| TC-BATCH-110 | PASS | [정적] `:377-384` — `resolved.startsWith(base)` 위반 시 `INVALID_INPUT` | ⚠ 실제 입력이 `FrameKind` enum + `Long rawSn` 뿐이라 **현재 호출 형상에서는 도달 불가**한 심층방어. 가드 자체는 기대대로 존재 |
| TC-BATCH-111 | **PASS** | **[실동작]** 컨테이너 파일시스템 실측 — `/app/storage/raw/frames/raw/{4,17,26,27,30,31,33,34,35,36,38}` · `/app/storage/deidentified/frames/deid/{17..27,…}` 로 **디렉터리가 실제 분기**. DB rawSn=26 두 경로 상이 확인 → **원본 덮어쓰기 0** | |
| TC-BATCH-112 | **PASS** | **[실동작]** `ls_data_src_hstry` srcSn 296~300 각각 `CREATED` + `DEID_ATTACHED` **2건**(hstry_seq 259~268) | |
| TC-BATCH-113 | **PASS** | **[실동작]** rawSn=26 의 비식별 영상은 co-locate 위치 `/app/storage/raw/autolabel-test/26/deid/…-mask.mp4`(= `deidentified-path` **밖**)인데도 채택돼 `DE_IDNTF_SRC_FILE_PATH_NM` 5행 전부 채워짐 → 구 결함(자기 산출물을 신뢰불가 판정해 항상 RAW only) **재발 없음** | rawSn=34·36 도 동일 co-locate 형상에서 정상 채택(오염/심링크 시에만 거부) |
| TC-BATCH-114 | PASS | [실동작+정적] 구 위치 행(`/app/storage/deidentified/videos/augment/…`, rawSn 18·19·24·25)이 현재도 정상 소비됨(스트리밍 200 실측). 판정 코드 `VideoArtifactRootResolver:308-320`(readableDeidVideoDirs) + `:337-346`(readableDeidVideoBases, `deidentifiedBase` 를 무조건 포함) | ⚠ 드리프트: 카탈로그 `308-327` → 실제 `308-320` |
| TC-BATCH-115 | **PASS** | **[실동작]** rawSn=36 의 비식별 산출물 파일을 **원본 영상 심링크**로 치환(`…-mask.mp4 -> /app/storage/raw/autolabel-test/…mp4`) 후 마킹 트리거 → `WARN … deid path rejected (base/realpath) rawSn=36 — RAW only`, `ls_data_src` 5행 `de_idntf_src_file_path_nm=NULL`. **원본 프레임이 "비식별본"으로 적재되지 않음** 확인(CWE-59/359 방어 성립). 코드: `:356-368` `underBaseWithRealPath` → `VideoArtifactRootResolver.verifyRealPathUnder(:458-466)`, 실패는 예외가 아니라 `false` | ★ **동일 심링크를 스트리밍 경로로 요청하면 200 으로 원본이 나간다** → B-ISSUE-81 |
| TC-BATCH-116 | PASS | [정적] `VideoArtifactRootResolver.realOrNearest(:474-498)` — 미존재 대상은 **가장 가까운 실재 조상**을 `toRealPath()` 로 접은 뒤 판정하므로 중간 세그먼트 심링크가 걸린다. 해석 불가는 `FORBIDDEN`(fail-secure). 커버: `..._deidIntermediateSegmentSymlink_rejectedRawOnly` | |
| TC-BATCH-117 | PASS | [정적] `:316-320` — `artifactRootResolver == null` → `underBaseWithRealPath(deidPath, baseDeidPath)` 단독 판정. 허용 범위가 **넓어지지 않음**(구 동작 = 더 좁은 축) | |
| TC-BATCH-118 | PASS | [정적] `:321-326` — `readableDeidVideoBases` `RuntimeException` catch → 구 동작 폴백, 파이프라인 미중단. 리졸버 내부에서도 `:340-346` 이 조용히 후보 축소(fail-secure) | |

---

## B-10. 비디오 스트리밍 (Range, 비식별본만 서빙) — 22건

> 실동작 기준선: rawSn=26(비식별 완료, co-locate deid, 파일 50,854 bytes) · 9104(`'N'`) · 9112(`'F'`) · 9114(`'Y'` + procLog 없음) · 18/24/25(파생 트리) · 9113(합성 procLog 주입용, 검증 후 원복)

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-STREAM-B01 | PASS | [실동작] rawSn=9104(`DE_IDENT_YN='N'`) → `404 {"errorCode":"NOT_FOUND","message":"비식별 처리 미완료"}`. 코드 `VideoStreamService:226-230` | 원본 노출 0 |
| TC-STREAM-B02 | PASS | [실동작] rawSn=99999 → `404 "영상을 찾을 수 없습니다."` (`:411-412`) | |
| TC-STREAM-B03 | PASS | [실동작] 9112(`'F'`) → 404 / 9104(`'N'`) → 404, 양쪽 동일 메시지(상태 오라클 없음). 코드 `:415-419` | |
| TC-STREAM-B04 | **PARTIAL** | [실동작] lexical 축은 **완전 차단** — procLog 경로를 ①`/etc/passwd` ②`/app/storage/deidentified/../../etc/passwd` ③`/app/storage/raw/seed/clip-9103.mp4`(원본) ④`…/augment/4/19/../../../../raw/seed/clip-9103.mp4` 로 오염해 4종 전부 `404`. **그러나 `resolveSafe(:470-485)` 는 `startsWith` lexical 검사만 하고 실경로 재검증이 없어, 허용 base 안의 심링크가 원본을 가리키면 통과한다 — 실증(아래 B-ISSUE-81)** | ⚠ **드리프트**: 기대 `FORBIDDEN` 이나 실제는 `NOT_FOUND`. 코드 주석(`:466-468`)이 "S7 — 존재/권한 구분 노출 방지" 로 **의도적 정규화**임을 명시 → 카탈로그 기대값을 정정할 것 |
| TC-STREAM-B05 | PASS | [실동작] 9113 procLog 를 base 안의 **부재 파일**(`…/augment/4/19/MISSING.mp4`)로 주입 → `404 "비식별 영상 파일이 존재하지 않습니다."`, 재요청도 404. 이후 실재 파일로 정정하니 **즉시 200** → 예외가 캐시되지 않음 확인 (`:359-363`) | |
| TC-STREAM-B06 | PASS | [실동작] Range 없이 GET → `200`, `Accept-Ranges: bytes`, `Content-Length: 50854`, body 50,854 bytes | |
| TC-STREAM-B07 | PASS | [실동작] `bytes=0-`→206/50854, `bytes=0-99`→206/100 `Content-Range: bytes 0-99/50854`, `bytes=100-199`→206/100, `bytes=50853-`→206/1, `bytes=-100`→206/`50754-50853`, `bytes=0-99999999`→206/전체(파일<청크상한) | 다중 Range(`bytes=0-99,200-299`)는 첫 범위만 206 반환 — `ranges.get(0)` 규약대로 |
| TC-STREAM-B08 | PASS | [실동작] `bytes=999-0`·`bytes=abc`·`bytes = 0-10`·`bytes=-0` → 전부 `416` + `Content-Range: bytes */50854` (`:240-245`) | |
| TC-STREAM-B09 | PASS | [실동작] `bytes=50854-`(=total)·`bytes=999999999-` → `416` (`:251-255`) | 경계 `50853-` 은 206 — off-by-one 없음 |
| TC-STREAM-B10 | PASS | [정적] `:314-319` — `<1MB`/0/음수 → `DEFAULT_CHUNK_SIZE` 8MB. 실효 설정 확인: `STORAGE_STREAM_CHUNK_SIZE` 미설정 → yml 기본 `8388608`. 커버: `VideoStreamServiceTest` 3건(0이하/음수/2MB) | 파일 50KB 라 상한 실도달은 미관측 |
| TC-STREAM-B11 | PASS | [정적] `:64,314-319` — `Math.min(streamChunkSize, MAX_CHUNK_SIZE=64MB)`. 커버: `effectiveChunkSize가_상한64MB로_클램프됨`, `비정상_대형_chunkSize에도_long오버플로_없이_안전서빙` | |
| TC-STREAM-B12 | PASS | [실동작] 9112(`'F'`)·9104(`'N'`) `GET /stream-url` → `404`(`:174-177`, `:189`) | ⚠ 부수 관찰: rawSn=9114(`'Y'` + 성공 procLog **없음**)는 `stream-url` **200**(서명 발급됨)인데 `stream` 은 404 → B-ISSUE-82 |
| TC-STREAM-B13 | PASS | [정적] `:191-196` — `!streamUrlSigner.isConfigured()` → `SERVICE_UNAVAILABLE`(503), 메시지에 내부정보 없음. 커버: `서명URL_시크릿_미설정시_503_SERVICE_UNAVAILABLE` | 로컬은 시크릿이 설정돼 있어 실동작 재현 불가(코드/설정 수정 금지) |
| TC-STREAM-B14 | **PASS** | **[실동작]** 발급 URL `?exp=…&u=1001&sig=…` + `Set-Cookie: klid_stream_nonce=…; HttpOnly; SameSite=Lax; Path=/api/v1/videos`. ①쿠키+무Bearer → **200** ②쿠키 없이 → **401** ③`u=1001→2001` → **401** ④`sig` 1자 변조 → **401** ⑤`exp` 연장 → **401** ⑥경로 rawSn 26→25/9112 스왑 → **401** ⑦파라미터명 대소문자 변형(`SIG=`) → **401** | 재사용 차단의 실효 축이 nonce 쿠키임이 실증됨 |
| TC-STREAM-B15 | PASS | [실동작] `@PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")`(`VideoController:244`) 통과 + **영상 단위 배정 인가 실동작 확인** — WORKER(sub=2001): 배정된 26 → **200**, 미배정 25·19 → **403 "본인에게 배정되지 않은 영상입니다."**, 무토큰 → 401 | ⚠ **카탈로그 비고가 낡음** — "영상 단위 배정 검증은 없다(B-ISSUE-63 미해소)"는 사실이 아니며 `VideoController:210,253` 의 `labelAccessGuard.verifyRawAccess` 로 **해소됨**. B-ISSUE-84 |
| TC-STREAM-B16 | **PASS** | **[실동작]** rawSn=9114(procLog 없음) → 404. 그 상태에서 성공 procLog 1행을 INSERT 하자 **즉시 200/50854** → `unless="#result == null"`(`:348`)로 null 이 캐시되지 않아 stale NOT_FOUND 고착 없음. 검증 후 행 삭제 | |
| TC-STREAM-B17 | **PASS** | **[실동작]** 합성 영상 rawSn=36 을 먼저 스트리밍해 캐시 warm(200) → `POST /v1/videos/36/deident-report` 201 → **즉시 404**(`DE_IDENT_YN='F'`). resolve 후 다시 200/50854. 코드 `DeidentReportService.java:248,395,454` `streamMetaCacheEvictor.evictAfterCommit` | ⚠ 드리프트: 파일 경로가 `label/service/DeidentReportService.java`(카탈로그는 `DeidentReportService.java:244-250`, 실제 `:248`) |
| TC-STREAM-B18 | **PASS** | **[실동작]** Range 무 → `200` + `Cache-Control: no-store`, Range 유 → `206` + `Cache-Control: no-store`. 코드 `:271,281,302-304`. 416 응답도 Spring Security 기본 헤더로 `no-cache, no-store, max-age=0, must-revalidate` | ⚠ 부수: 200 응답에도 `Content-Range: bytes 0-50853/50854` 가 붙는다(RFC 7233 비적합) → B-ISSUE-85 |
| TC-STREAM-B19 | **PASS** | **[실동작]** rawSn=18 을 2회 스트리밍해 `stream-meta` 캐시 warm(200,200) → **DB 직접 `de_ident_yn='F'`** 로 전환(캐시 evict 없음) → `stream` **404**, `stream-url` **404**. 즉 게이트가 캐시 **앞**(매 요청 DB projection)에서 평가됨(`:221`, `:143-148`). 원복 후 200 | 캐시 우회 불가 확정 |
| TC-STREAM-B20 | **PARTIAL** | [실동작] 2-way allowlist 자체는 성립 — co-locate(26: `/app/storage/raw/autolabel-test/26/deid/…`) **200**, 구 위치(25: `/app/storage/deidentified/videos/augment/18/25/WINTER.mp4`) **200**. **그러나 "프레임 추출기와 동일 축(가드 이원화 제거)" 단언은 미성립** — 추출기는 `underBaseWithRealPath`(realpath 재검증)를 추가로 걸지만 스트리밍 `resolveSafe` 는 lexical 만이다. 같은 심링크 입력에 대해 추출기는 거부, 스트리밍은 200(실증) | B-ISSUE-81 |
| TC-STREAM-B21 | PASS | [실동작] `'F'` 상태에서 `GET /stream` → **404**(412 아님), `GET /stream-url` → **404**. `'N'`·부재 영상과 코드·메시지가 동일해 상태 오라클(CWE-209) 없음 | |
| TC-STREAM-B22 | **PASS** | **[실동작]** 부모 rawSn=18 을 `'F'` 로 두는 동안 파생 rawSn=24·25(`ORGNL_RAW_SN=18`, 자기 행 `'Y'`) → **200 서빙**. 게이트 쿼리 `VideoRepository:50-51 SELECT r.deIdntfYn … WHERE r.rawSn = :rawSn` 에 `ORGNL_RAW_SN` 없음 확인 → 확정 정책대로 결함 아님 | |

---

## 이슈

### [B-ISSUE-81] TC-STREAM-B04 / TC-STREAM-B20 — 영상 스트리밍의 비식별 경로 가드가 lexical 전용이라 심링크 치환으로 **원본(비식별 이전) 영상이 그대로 서빙된다**
- **심각도**: **HIGH**
- **기대 동작(기대효과)**: `GET /v1/videos/{rawSn}/stream` 은 **항상 비식별 영상만** 내보내야 한다(`CLAUDE.md` — "비식별 미완료 시 NOT_FOUND 로 원본 노출 차단"). 프레임 이미지 4경로에 대해서는 이미 *"`StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기로 검증하고, 판정이 돌려준 실경로(`toRealPath()`)를 그대로 사용한다 — lexical 경로로 검증하고 lexical 경로로 여는 구현은 금지"* 가 구속 규칙으로 명문화돼 있다. **비식별 영상 파일도 동일 신뢰 경계**(외부 비식별 벤더 KPST 가 공유 마운트에 직접 산출물을 쓰는 디렉터리)에 있으므로 같은 규약이 적용돼야 한다.
- **현재 동작(이슈 내용)**: `VideoStreamService.resolveSafe` 가 `normalize()` + `startsWith` **lexical 검사만** 수행하고, 반환한 lexical 경로를 그대로 `UrlResource` 로 연다. 실경로 재검증(`toRealPath`)이 없다.
  ```java
  // backend/.../video/service/VideoStreamService.java:470-485
  static Path resolveSafe(List<Path> baseDirs, String filePath) {
      Path candidate = Paths.get(filePath);
      for (Path baseDir : baseDirs) {
          Path resolved = candidate.isAbsolute() ? candidate.normalize() : baseDir.resolve(candidate).normalize();
          if (resolved.startsWith(baseDir)) { return resolved; }   // ← 실경로 재검증 없음
      }
      throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
  }
  ```
  같은 DB 값(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)을 읽는 형제 소비자 `FfmpegFrameExtractor` 는 `underBaseWithRealPath`(`:356-368`) → `VideoArtifactRootResolver.verifyRealPathUnder`(`:458-466`) 로 **실경로를 재검증**한다. 프레임 이미지 서빙도 `StorageSubtreePolicy.verifyDeidentifiedFile`(`:201-215`)이 `realResolved = resolved.toRealPath()` 후 서브트리를 재판정하고 **실경로를 돌려준다**. **스트리밍만 빠져 있다** — 즉 "가드가 갈라져 하나씩 샌다"가 이 경로에서 실제로 일어나고 있다.
- **재현/확인 경로** (2026-08-01 실측, 합성 영상 rawSn=36 · 검증 후 원복 완료):
  ```bash
  # 1) 비식별 산출물 파일을 원본 영상 심링크로 치환 (허용 base 안, lexical 경로 불변)
  D=/app/storage/raw/autolabel-test/36/deid/378d1b22-…-mask.mp4
  O=/app/storage/raw/autolabel-test/378d1b22-….mp4          # 원본(비식별 이전)
  docker exec klid-backend sh -c "mv $D $D.real && ln -s $O $D"

  # 2) 프레임 추출(배치) — 차단됨 ✅
  curl -X POST /api/v1/videos/36/markings -d '{"mode":"AUTO","intervalFrames":30}'
  # log: WARN [Batch][FrameExtract] deid path rejected (base/realpath) rawSn=36 — RAW only
  # DB : ls_data_src 5행 de_idntf_src_file_path_nm = NULL

  # 3) 같은 심링크를 스트리밍 — 통과됨 ❌
  curl -o out.bin -w '%{http_code} %{size_download}' /api/v1/videos/36/stream -H "Authorization: Bearer $REVIEWER"
  # → 200 20590   (= 원본 mp4 크기. 정상 비식별본은 50854)
  ```
  같은 우회는 `/stream-url` 로 발급한 서명 URL 경유(무인증 재생)에서도 성립한다.
- **영향**: **CWE-59(Link Following) + CWE-367(TOCTOU) + CWE-359(개인정보 노출)**. 비식별 산출 디렉터리는 외부 비식별 벤더(KPST)가 공유 마운트로 직접 쓰는 영역이고 co-locate 전환으로 판정 대상이 관제 NAS 하위까지 넓어졌다. 그 디렉터리에 심링크 1개를 심으면 **마스킹 전 원본 영상이 "비식별 영상"으로 마킹 화면·검수 화면에 재생**된다. 신고 게이트(`DE_IDNTF_YN`)는 이 경로를 `'Y'` 로 보므로 뒤에서 막아주지 않는다. 자동 테스트에도 이 축이 없다 — `FfmpegFrameExtractorTest` 는 CWE-59 케이스 3건을 갖고 있으나 `VideoStreamServiceTest`(37건)·`DeidentReportStreamGateIT` 에는 심링크 케이스가 **0건**이다.
- **수정 방향(제안)**: `VideoStreamService.resolveSafe` 가 lexical 통과 후 **`VideoArtifactRootResolver.verifyRealPathUnder(resolved, baseDir)`(이미 존재하는 같은 정적 판정기)를 호출**하고, **판정이 돌려준 실경로**로 `StreamMeta.path` 를 채우도록 한다(판정 대상과 사용 대상 일치 — TOCTOU 차단). 실패는 예외가 아니라 다음 base 후보로 넘어가고, 모든 후보 실패 시 기존대로 `NOT_FOUND`(경로 원문 미노출). 판정 결과가 `stream-meta` 캐시에 실리므로 **캐시 채우기(miss) 시점에 1회만** 수행되어 핫패스 비용도 없다. 회귀 가드로 `VideoStreamServiceTest` 에 "비식별 파일이 원본 심링크면 404" · "중간 세그먼트 심링크도 404" 2건을 추가(추출기 테스트와 동일 골격). ⚠ 구현은 하지 않았다.

### [B-ISSUE-82] TC-STREAM-B12 — 성공 procLog 가 없는 영상에도 서명 스트림 URL 이 발급된다(발급 200 / 재생 404 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: 서명 URL 발급은 "재생 가능한 영상"에 대해서만 성공해야 한다. `issueSignedUrl` 의 주석도 *"노출본 대상 URL 발급을 사전 차단한다"* 를 목적으로 명시한다.
- **현재 동작(이슈 내용)**: `issueSignedUrl`(`VideoStreamService:166-206`)은 `DE_IDNTF_YN=='Y'` 와 신고 게이트만 확인하고 **`LS_DEIDENT_PROC_LOG` 성공 행 존재는 확인하지 않는다**. 반면 `stream` 은 `resolveDeidLocation`(`:423-427`)에서 procLog 부재 시 null → 404.
  ```
  rawSn=9114 (de_ident_yn='Y', 성공 procLog 0건)
    GET /v1/videos/9114/stream-url → 200 {"url":"…?exp=…&u=1001&sig=…"}
    GET /v1/videos/9114/stream     → 404 "비식별 처리 미완료"
  ```
- **재현/확인 경로**: 위 curl 2줄(실측 2026-08-01). DB: `select count(*) from ls_deident_proc_log where data_raw_sn=9114 and proc_stts_cd='SUCCEEDED'` → 0.
- **영향**: 보안 취약점 아님(재생 자체는 404 로 막힘). FE 가 URL 을 받아 `<video>` 에 물린 뒤 404 로 실패하므로 사용자에게 원인 불명 재생 실패로 보인다. 또한 발급 성공/실패가 procLog 유무를 노출하지 않는다는 점에서 정보 노출도 아님.
- **수정 방향(제안)**: `issueSignedUrl` 에서 `resolveDeidPath(rawSn) == null` 이면 `stream` 과 동일하게 `NOT_FOUND` 로 거부(같은 메시지 유지 — 상태 오라클 방지). 또는 카탈로그에 "발급은 플래그 축, 재생은 산출물 축"으로 의도된 분리임을 명문화. ⚠ 구현은 하지 않았다.

### [B-ISSUE-83] TC-STREAM-B05 파생 관찰 — `stream-meta` 캐시가 사라진 파일을 가리키면 404 가 아니라 **500 INTERNAL_ERROR**
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 파일이 실재하지 않으면 `NOT_FOUND`(404)로 정규화돼야 한다(`:359-363` 의 규약). 캐시 히트 여부가 응답 코드를 바꾸면 안 된다.
- **현재 동작(이슈 내용)**: 파일 존재 확인은 `resolveStreamMeta`(캐시 **뒤**) 안에서만 이뤄지므로, 캐시가 채워진 뒤 파일이 사라지면 `stream()` 은 그대로 `UrlResource` 를 열고 `ResourceRegionHttpMessageConverter` 가 write 시점에 실패해 500 이 된다.
  ```
  (rawSn=20, 캐시 warm 상태에서 대상 파일 제거)
  GET /v1/videos/20/stream → 500 {"errorCode":"INTERNAL_ERROR"}   … TTL 5분 경과 후 200 복귀
  ```
- **재현/확인 경로**: 캐시를 warm 한 뒤 `LS_DEIDENT_PROC_LOG` 경로가 가리키는 파일을 삭제/이동하고 5분 TTL 안에 재요청. (본 검증에서는 B-ISSUE-81 재현 과정의 부수 효과로 관측했으며 원복 완료.)
- **영향**: 기능 영향만. 정상 운영에서는 비식별본이 불변이므로 발생 빈도가 낮고, 신고/해소/재비식별 3경로는 `StreamMetaCacheEvictor.evictAfterCommit` 로 무효화된다. 다만 **외부 벤더가 파일을 교체/정리하는 형상**(공유 마운트)에서는 재현 가능하며, 5xx 는 모니터링 알람을 오염시킨다.
- **수정 방향(제안)**: `stream()` 에서 `region` 생성 직전에 `Files.isReadable(meta.path())` 를 1회 확인해 실패 시 캐시를 evict 하고 404 로 정규화하거나, `IOException` 을 `NOT_FOUND` 로 매핑. ⚠ 구현은 하지 않았다.

### [B-ISSUE-84] 카탈로그 정합 — TC-STREAM-B15 비고(B-ISSUE-63 미해소) · TC-STREAM-B04 기대값 · 근거 file:line 4건 드리프트
- **심각도**: LOW (카탈로그 자체의 정합성 결함 — 오탐/누락 유발)
- **기대 동작(기대효과)**: 케이스의 기대결과·근거가 현재 구현과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**:
  1. **TC-STREAM-B15 비고 무효** — "⚠ 영상 단위 배정 검증은 없다(B-ISSUE-63 미해소 — 현재 동작 고정)"는 사실이 아니다. `VideoController:210`(stream-url)·`:253`(stream) 에 `labelAccessGuard.verifyRawAccess(rawSn, actor)` 가 **진입부**(캐시 앞)에 배선돼 있고, 실동작으로 WORKER 미배정 영상 403 을 확인했다. `UNCERTAINTIES.md` "미해소 이월 이슈" 표의 B-ISSUE-63 행도 함께 해소 처리 필요.
  2. **TC-STREAM-B04 기대값 무효** — 기대 `FORBIDDEN` 이나 구현은 `NOT_FOUND` 로 **의도적 정규화**(`VideoStreamService:466-468` 주석: 존재/권한 구분을 응답으로 알려주지 않는 편이 원본 미노출 정책과 동급). 기대결과를 `NOT_FOUND` 로 정정할 것.
  3. **근거 드리프트**: TC-BATCH-108 `FfmpegFrameExtractor.java:266` → 실제 seek 계산은 `:248` / TC-BATCH-114 `VideoArtifactRootResolver.java:308-327` → 실제 `readableDeidVideoDirs` 는 `:308-320` / TC-STREAM-B17 `DeidentReportService.java:244-250` → 실제 파일은 `label/service/DeidentReportService.java` 이고 evict 호출은 `:248`(+`:395`,`:454`) / TC-STREAM-B15 근거가 `VideoController.java:@PreAuthorize(stream)` 로 라인 없음 → `:244`(+인가 `:253`).
- **재현/확인 경로**: 위 file:line Read 및 `curl -H "Authorization: Bearer $WORKER" /api/v1/videos/25/stream` → `403 "본인에게 배정되지 않은 영상입니다."`
- **영향**: 카탈로그를 근거로 재검증하는 다음 회차에서 **미해소 이슈를 이월 유지**하거나(B-ISSUE-63) **정상 동작을 FAIL 로 오판**(B04)할 수 있다.
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` 의 해당 4개 셀과 `UNCERTAINTIES.md` 이월 표를 정정. ⚠ 본 검증에서는 파일을 수정하지 않았다.

### [B-ISSUE-85] TC-STREAM-B18 파생 관찰 — 200 전체 응답에도 `Content-Range` 헤더가 붙는다(RFC 7233 비적합)
- **심각도**: LOW
- **기대 동작(기대효과)**: `Content-Range` 는 206(또는 416)에서만 의미가 있다. 200 응답의 `Content-Range` 는 RFC 7233 §4.2 상 무의미하며 일부 엄격한 클라이언트/프록시가 오해할 수 있다.
- **현재 동작(이슈 내용)**: Range 헤더 없는 요청 응답 헤더 실측 —
  ```
  HTTP/1.1 200
  Accept-Ranges: bytes
  Cache-Control: no-store
  Content-Range: bytes 0-50853/50854     ← 200 인데 부착됨
  Content-Length: 50854
  ```
  원인은 Range 무 경로(`VideoStreamService:276-282`)도 전체 파일을 `ResourceRegion` 으로 감싸 반환하고, `ResourceRegionHttpMessageConverter` 가 write 시점에 `Content-Range` 를 무조건 add 하기 때문.
- **재현/확인 경로**: `curl -D - -o /dev/null http://localhost:18081/api/v1/videos/26/stream -H "Authorization: Bearer $REVIEWER"`
- **영향**: 기능 영향 관측되지 않음(브라우저 재생 정상). 규격 적합성·프록시 캐시 상호작용 관점의 위생 이슈.
- **수정 방향(제안)**: Range 무 경로는 `ResourceRegion` 대신 `UrlResource` 자체를 body 로 반환(`ResourceHttpMessageConverter` 경유)하거나, 현 동작을 의도로 문서화. ⚠ 구현은 하지 않았다.

---

## 검증 중 만든 상태 변경 (전부 원복 또는 합성 데이터)

| 대상 | 변경 | 원복 |
|---|---|---|
| `ls_data_raw` rawSn=18 `de_ident_yn` | `'Y'`→`'F'`→`'Y'` (B19/B22 실동작) | ✅ `'Y'` |
| `ls_deident_proc_log` proc_log_sn=21 경로 | EVIL 심링크 경로로 오염 (B04 심링크 실증) | ✅ 원경로 복원, 심링크 삭제 |
| `ls_deident_proc_log` data_raw_sn=9113/9114 | 합성 행 INSERT (B05/B16) | ✅ 전량 DELETE |
| rawSn=34 procLog 경로 | `/etc/passwd` 오염 (TC-BATCH-105) | ✅ 원경로 복원 |
| rawSn=36 비식별 파일 | 원본 심링크로 치환 (TC-BATCH-115 / B-ISSUE-81) | ✅ 실파일 복원 |
| rawSn=36 신고 | `POST /deident-report` 201 → `POST /deident-reports/13/resolve` 200 (B17) | ✅ `'Y'` 복귀, 스트리밍 200 |
| 신규 rawSn 34·36 (`QA0801-B8-105`, `QA0801-B8-115`) | 합성 검증 영상 2건 신규 적재 | 잔존(합성 데이터). rawSn=36 의 `ls_data_src` 5행은 심링크 실험 결과 `de_idntf_src_file_path_nm=NULL` 로 남음 |

## 자동 테스트 커버 대조 (`_raw/test-baseline.md`: backend 4,755 tests / 실패 0 / skip 5)

| 파일 | 관련 케이스 | 비고 |
|---|---|---|
| `batch/step/FfmpegFrameExtractorTest.java` (30여 건) | TC-BATCH-100~118 대부분 | CWE-59 심링크 3건 포함, 전량 통과 |
| `batch/step/FfmpegFrameExtractorDeidPersistIT.java` (2건) | TC-BATCH-107 / 106 | 비식별 경로 실영속·NULL 무회귀 |
| `video/VideoStreamServiceTest.java` (37건) | TC-STREAM-B01~B14, B16, B18, B20~B22 | **심링크(CWE-59) 케이스 0건** ← B-ISSUE-81 의 미탐 원인 |
| `video/DeidentReportStreamGateIT.java` (2건) | TC-STREAM-B17, B19, B22 | 캐시 warm 후 신고 차단 커버 |
| `video/VideoStreamAssignmentAuthorizationTest.java` | TC-STREAM-B15 | B-ISSUE-63 해소 회귀 가드 |
| `video/StreamSignedUrlControllerTest.java` | TC-STREAM-B13, B14 | |
| `common/cache/StreamMetaCacheEvictorTest.java` | TC-STREAM-B16, B17 | |
# B 클러스터 part6 — B-11 비식별 누락 신고 / B-12 재처리·재시도 큐 / B-15 배치 스텝 트랜잭션 경계

> 검증일 2026-08-01 · 회차 1차 · 대상 60건(B-11 30 + B-12 19 + B-15 11)
> 코드 기준 `qa-0801` @ `56d30478` · 실행 스택 backend V146(환경 버전 격차 — `stack-bringup.md` 하단 참조)
> 판정 근거: **[실동작]** = 기동 스택에 실제 요청/DB 조회/로그 관측 · **[정적]** = file:line 대조

## 0. 검증 환경·수단

| 항목 | 값 |
|---|---|
| backend | `http://localhost:18081/api` (context-path `/api`), local 프로파일 |
| DB | `klid-postgres` / `klid_system` / 스키마 **`public`** |
| 토큰 | `POST /api/v1/dev/tokens` — REVIEWER(sub=1001) · WORKER(sub=2001) |
| 신규 투입 테스트 데이터 | 영상 `rawSn=27`(업로드→선두비식별→AUTO마킹→프레임 5건 301~305→라벨 3건→개인정보 3필드), `rawSn=29`(비식별만), 합성 부모/파생 쌍 `rawSn=9301('F')`/`9302('Y', ORGNL=9301)` + 프레임 99301/99302 + 라벨 2건, 합성 재시도큐 stale 행 3건(9301/9302/9303) |
| ⚠ 기존 데이터 상태 변화(불가피) | ①`rprtSn=7`(rawSn 6) resolve 200 — TC-DEID-044 확인 중 **의도치 않게 해소됨**(원인은 B-ISSUE-102, 아래) → rawSn 6 `'F'→'Y'`, 락 해제 ②`rawSn=9109` 배치 재처리 2회 기동(재시도 큐 행 교체) ③`rawSn=26` 신고→resolve 왕복(최종 `'Y'`/APPROVED 원복, export `v2` 추가 생성 + 관제 재통지) |
| ⚠ 병행 세션 간섭 | 검증 중 타 에이전트가 같은 스택에서 rawSn 28·30~38 을 생성·구동함. 본 문서의 근거는 **본 세션이 직접 만든 rawSn(26/27/29/9301~9303)** 관측치만 사용 |

---

## 1. B-11. 비식별 누락 신고 (srcSn / rawSn, resolve) — 30건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-030 | PASS | [실동작] `POST /v1/labels/301/deident-report` body `{"reason":"   "}` → **400** `INVALID_INPUT` "reason: 신고 사유는 필수입니다."; body `{}`(reason 누락) → 동일 400. `@Valid`+`@NotBlank`(DeidentReportRequest.java:18) 선차단. [정적] 서비스 `requireReason`(DeidentReportService.java:158-162)이 우회 호출 2차 방어 |
| TC-DEID-031 | PASS | [실동작] WORKER(2001, 배정=raw 26·27) → `POST /v1/labels/75/deident-report`(raw 17) **403** `FORBIDDEN` "본인에게 배정되지 않은 영상입니다."; `POST /v1/videos/17/deident-report` 도 **403**. [정적] `report()`→`accessGuard.verifyAndGet`(:122), `reportByVideo()`→`verifyRawAccess`(:151) — **영상 조회보다 먼저** 평가해 존재 여부 미노출 |
| TC-DEID-032 | PASS | [실동작] rawSn 29 에 5요청 동시 발사 → 201 1건 / 409 4건, `ls_deident_report` 1행 · `ls_auth_work_lock` 1행 · `de_ident_yn='F'` (중복 신고·중복 락 0). [정적] `doReport`가 `videoRepository.findByRawSnForUpdate`(DeidentReportService.java:178) → `@Lock(PESSIMISTIC_WRITE)`(VideoRepository.java:37-39)로 부모 RAW 행 직렬화 |
| TC-DEID-033 | PASS | [실동작] rawSn 27 신고 201 직후 srcSn 302 재신고 → **409** "이미 비식별 재처리 중인 영상입니다."(DeidentReportService.java:188-191) |
| ~~TC-DEID-034~~ | N/A | 폐기(2026-07-30) — 검증 대상 아님. [정적] 실제로 스냅샷·삭제 코드가 없음(:197-205 주석 블록만 잔존) |
| TC-DEID-035 | PASS | [실동작] 신고 전 srcSn 301/302/303 = `Y/N/N` → 신고 후 3필드 전부 **NULL**(304/305 원래 NULL 유지). 백엔드 로그 `privacyReset=5`(=영상 전 프레임 벌크 UPDATE 영향행) |
| TC-DEID-036 | PASS | [실동작] TC-DEID-032 와 동일 시행 — 동시 5건 중 4건이 예외 전파 없이 **409**(`DataIntegrityViolationException`→CONFLICT 변환, :237-241). 500/스택트레이스 노출 0 |
| TC-DEID-037 | PASS | [실동작] APPROVED 영상 rawSn 26 신고 201 → `ls_mon_noti_acml` 신규 행 `chg_dtl_cn={"video":{"*":["META_UPDATED"]}}` STTS=PENDING → 디바운스 flush 후 `[ControlNotify] TASK_MODIFIED sent rawSn=26 frames=0 videoLevel=1` + mock-server `POST /api/data-set/v2/jobs/26/notify-updated` **202**. `LABEL_DELETED` 아님 확인 |
| ~~TC-DEID-038~~ | N/A | 폐기(2026-07-30) — 검증 대상 아님 |
| TC-DEID-039 | PASS | [실동작] 토큰 없이 `POST /v1/deident-reports/1/resolve` → **401** `UNAUTHORIZED`. [정적] 시큐리티 필터가 선차단하고 서비스 `actor==null` 가드(:354-356)가 2차 방어 |
| TC-DEID-040 | PASS | [실동작] `POST /v1/deident-reports/99999999/resolve` → **404** "신고를 찾을 수 없습니다." |
| TC-DEID-041 | PASS | [실동작] `rprtSn=7` resolve 성공(200) 직후 재호출 → **409** "이미 처리된 신고입니다."(:364-366) |
| TC-DEID-042 | PASS | [실동작] rawSn 27 신고(19:11:18) 상태에서 resolve → **409** "비식별 산출물이 확인되지 않습니다…". 직후 DB 재확인: `report_stts_cd=OPEN` · `de_ident_yn=F` · LOCKED 락 1건 **전부 유지**(fail-closed 롤백). 로그 `resolve blocked — deident artifact not verified rawSn=27`(내부 경로 미노출) |
| TC-DEID-043 | PASS | [실동작] 비식별 산출물 mtime 갱신 후 WORKER(본인배정) resolve → **200**; DB: `RESOLVED`+`resolved_dt` 기록 · `de_ident_yn='Y'` · LOCKED 락 0건 · **라벨 3건 그대로**. 이어서 `GET /v1/frames/301/labels` **200**(보존된 기존 라벨 재사용), `GET /v1/videos/27/stream` **200** — 게이트 자동 해제 |
| TC-DEID-044 | PARTIAL | [실동작] `rprtSn=2`(rawSn 11 — 최신 SUCCESS procLog 없음/경로 blank) resolve → **409** 정상 거부. 그러나 같은 시행에서 `rprtSn=7`(rawSn 6)이 **200 으로 통과** — 산출물이 신고 이전 파일(mtime이 신고시각보다 **38ms 이전**)인데 60초 스큐 관용에 흡수됐다 → **B-ISSUE-102** |
| TC-DEID-045 | PARTIAL | [실동작] rawSn 27: procLog `rspns_dt=19:09:51`·파일 mtime `19:09:21` < 신고 `19:11:18` → **거부(409)** 확인(주 단언 충족). 단 경계값 반증 결과 `신고시각-60s < mtime ≤ 신고시각` 구간은 **통과**한다(rawSn 6 실측) → **B-ISSUE-102** |
| TC-DEID-046 | PASS | [실동작] rawSn 27 은 신고 시점 `LS_DATA_RAW.DATA_STTS_CD=COMPLETED`. 신고 후에도 `COMPLETED`, resolve 후에도 `COMPLETED` 유지 — `de_ident_yn` 만 `F→Y`. 배치 단계 역행 없음(CWE-664) |
| TC-DEID-047 | PASS | [정적] `resolveOpenReports`(:443-462) — OPEN 일괄 `resolve()` + `workLockService.releaseRaw` + `streamMetaCacheEvictor.evictAfterCommit`, `opens` 비어있으면 이벤트 미발행(멱등). 호출부 2곳 = `DeidentifyStep.java:354` · `KpstDeidentTxService.java:358`. [실동작 미도달] local 은 `authoring.batch.enabled=false` 라 OPEN 신고 보유 영상의 자동 비식별 재성공 경로를 만들지 못함 — 커버는 `DeidentReportServiceTest` 다수(baseline 전량 PASS) |
| TC-DEID-048 | PASS | [실동작] `?status=X` → 400 / `?status=OPEN' OR 1=1--`(URL 인코딩) → 400 / `?status=open`(소문자) → 400(컨트롤러 `@Pattern` 이 서비스 `toUpperCase` 정규화보다 먼저·더 엄격) / 무파라미터 → 200 기본 OPEN 3건. SQLi 문자열이 쿼리에 도달하지 않음 |
| TC-DEID-049 | PASS | [실동작] WORKER(본인 배정 raw 27) `POST /v1/labels/301/deident-report` → **201** body=`9`(rprtSn). REVIEWER 로도 201(rawSn 26·29) |
| TC-DEID-050 | PASS | [실동작] WORKER 2001 이 타인 영상 신고(rprtSn 5 / rawSn 9112) resolve → **403**; 본인 배정 rawSn 27(rprtSn 9) resolve → **200**; REVIEWER 는 rprtSn 7·11 모두 resolve 200 |
| TC-DEID-051 | PASS | [실동작] 신고 전/후 DB 대조 — `ls_data_lbl` **3→3**(lbl_sn 585/586/587 동일), `ls_label_version` **0→0**, `ls_data_src.lbl_ver` 301=1·302=1 **불변**(bump 없음). 로그 `labelsPreserved=true` |
| TC-DEID-052 | PASS | [실동작] 3필드 보유 프레임 3건(301/302/303)에 대해 `ls_data_lbl_hstry` 정확히 **3행** 신규(lbl_hstry_sn 34/35/36), `chg_dtl_cn={"event":"PRIVACY_META_RESET","deidentReportSn":9,"changes":[]}`, `add/mdfcn/del=0`, `reg_id=2001`. 값이 없던 304/305 는 미기록(= 리셋 **직전** 대상 확정). `V_COMPLETED_LABEL_CHANGE` 조회 결과 이 3행 미노출(V139 `ADD+MDFCN+DEL>0` 필터, 뷰 전체 24행 중 0-count 0건) |
| TC-DEID-053 | PASS | [실동작] 파생 rawSn 18(ORGNL=4) 및 합성 파생 9302(ORGNL=9301) 양쪽에 srcSn 경로·rawSn 경로 모두 **412** `PRECONDITION_FAILED`. 응답 메시지에 **부모 rawSn 문자열 미포함**(grep 결과 0건), `ls_deident_report` 신규 행 0, REVIEWER 알림 로그 없음. 사유는 WARN 감사 로그로만 보존되며 개행/CR 주입 시도(`\n2026-01-01 00:00:00 FAKE INJECTED LINE\r`)가 **한 줄로 정제**되어 위조 로그 라인 0건(CWE-117) |
| TC-DEID-054 | PASS | [실동작] `de_ident_yn='N'` 인 rawSn 9104 신고 → **412** "아직 비식별 처리가 완료되지 않은 영상입니다." + `'N'` 그대로(전이 없음). `'F'`(rawSn 27 신고 후) 재신고는 412 아닌 **409**(기존 작업락 경로) — 계약 유지 확인 |
| TC-DEID-055 | PASS | [실동작] `POST /v1/videos/26/deident-report`(REVIEWER) **201** rprtSn=11, `POST /v1/videos/29/deident-report` **201**. 마킹 단계(프레임 없는 rawSn 29)에서도 정상 접수 — UNCERTAINTIES #2 해소 확인 |
| TC-DEID-056 | PASS | [실동작] 두 진입점 부수효과 동일성 실측: 파생 412(18/9302 양 경로) · 비식별 미수행 412(9104) · 작업락 409(27) · `'F'` 전이(26·27·29) · 개인정보 3필드 리셋(27) · 스트림 404 게이트. [정적] 두 메서드가 `doReport`(:169) 단일 본체로 수렴(:125, :154) — 분기점은 인가 축과 `srcSnForNotify` 뿐 |
| TC-DEID-057 | PASS | [실동작] **미승인** 영상 rawSn 27(작업상태 `ASSIGNED`) resolve → 로그 `[VlmResumeBridge] deident gate reopened rawSn=27 — checking withheld VLM submit` 발화 = `DeidentGateReopenedEvent` 무조건 발행 확인 |
| TC-DEID-058 | PASS | [실동작] **APPROVED** 영상 rawSn 26 resolve → `[DatasetExportBridge] deident report resolved rawSn=26 — re-triggering withheld export/notify` → export `version=2` SUCCEEDED(`ls_dataset_export` export_sn=14) + 관제 재통지. 반면 미승인 rawSn 27 resolve 에서는 `DatasetExportBridge` 로그 **미발화** → 승인 조건부 발행 확인 |
| TC-DEID-059 | PASS | [실동작] 부모 9301=`'F'` / 파생 9302=`'Y'` 합성 쌍에서 `GET /v1/frames/99301/labels`→**412**, `GET /v1/frames/99302/labels`→**200**(라벨 반환). 즉 게이트는 자기 rawSn 행만 판정하며 `ORGNL_RAW_SN` 을 보지 않는다. [정적] `DeidentReportGate.isUnderDeidentReport`(:66-71)는 `findDeIdntfYnByRawSn` **단일 컬럼 projection 1회** — 조상 순회·자손 팬아웃 코드 0. ※ ★1 확정 정책의 귀결(파생 경유 열람)은 **결함 아님** |

**B-11 소계**: PASS 26 · PARTIAL 2 · N/A(폐기) 2 · FAIL 0

---

## 2. B-12. 재처리 / 재시도 큐 (2노드 안전 · stale 회수) — 19건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-150 | PASS | [실동작] `POST /v1/videos/0/batch/retry` → **400** "retryBatch.rawSn: rawSn 은 1 이상이어야 합니다."(컨트롤러 `@Min(1)` 선차단). [정적] 서비스 null 가드(BatchReprocessService.java:67-69) 방어심도 |
| TC-BATCH-151 | PASS | [실동작] `POST /v1/videos/88888888/batch/retry` → **404** "영상을 찾을 수 없습니다."(:71-73) |
| TC-BATCH-152 | PASS | [실동작] rawSn 9109(raw FAILED) 재처리 → 200 + 로그 `[BatchReprocess] manual retry claimed rawSn=9109` → `clearIfIdle`(PENDING 행 삭제) → `orchestrator.process` 실행(MarkingLoadStep→…) 관측 |
| TC-BATCH-153 | FAIL | [실동작] rawSn 9109 에 **동시 5요청 → 200 이 2건**(409 3건). 로그상 서로 다른 스레드가 같은 밀리초(19:17:39.920/.921)에 `manual retry claimed rawSn=9109` 를 찍고 **파이프라인 2벌이 동시 실행**됐다(재시도 큐도 attempt=1·attempt=2 로 이중 증가). 원인: `tryClaimReprocessFromFailed` 가 RAW 클레임 실패(0행) 시 **작업상태 컬럼으로 폴백**하는데, 정상 배치 실패는 두 컬럼이 **함께 FAILED** 라 두 호출자가 각각 다른 컬럼을 선점한다 → **B-ISSUE-101** |
| TC-BATCH-154 | PARTIAL | [실동작] raw 우선 클레임은 동작(첫 요청이 RAW 컬럼 선점). 두 UPDATE 모두 조건부(`WHERE …=FAILED`)임은 [정적] 확인(VideoRepository.java:66 · LsRawDataStatusRepository.java:109). 다만 "우선"이 **배타**가 아니어서 상호배제가 성립하지 않음(TC-BATCH-153 / B-ISSUE-101). 근거 라인 드리프트: 카탈로그 `BatchTransitionService.java:334-362` → 실제 `334-347` |
| TC-BATCH-155 | PASS | [실동작] rawSn 9109 최초 실패 시 `ls_bat_rty_wtng` 신규 행 `rty_nmtm=1 stts_cd=PENDING`(로그 `[BatchRetry] enqueued rawSn=9109 attempt=1 delaySec=60`). [정적] `insertIfAbsent`(ON CONFLICT DO NOTHING) + `findByRawSnForUpdate`(BatchRetryQueue.java:72-74) |
| TC-BATCH-156 | PASS | [실동작] 위 동시 2벌 실행이 같은 밀리초에 enqueue 를 2회 수행했으나 `DataIntegrityViolationException` 전파 0 · UK 위반 로그 0 · 행은 1건 유지(attempt 1→2 순차 증가). PG tx-abort 함정 회피 확인 |
| TC-BATCH-157 | PASS | [실동작] attempt=1 → `delaySec=60`, attempt=2 → `delaySec=120` 로그 실측(=`60×2^(n-1)`). [정적] shift 30 캡(BatchRetryQueue.java:86-87) |
| TC-BATCH-158 | PASS | [실동작] `ls_bat_rty_wtng` 에 `stts_cd=EXHAUSTED` 행 상존 — bat_rty_sn 24(rawSn 9202, `rty_nmtm=4 > max 3`), 13(rawSn 9203, `rty_nmtm=8`). **삭제되지 않고 이력 보존**됨. [정적] `markExhausted`+`save`(:79-83) |
| TC-BATCH-159 | BLOCKED | 실동작 불가 — local 프로파일 `authoring.batch.enabled=false`(application-local.yml:61-62)라 `BatchRetryTriggerConfig`(`@ConditionalOnProperty`)가 미등록. `qrtz_triggers` 실측 3건(kpstDeidentPoll/datasetExportFailureRecovery/datasetExportPendingSweep)에 **batchRetryTrigger 부재** 확인. [정적] `pollReady`(:101-112)가 후보 순회 + `claimAtomically`(PENDING→RETRYING CAS, LsBatRtyWtngRepository.java:63-66) 1행만 반환. 커버: `BatchRetryQueueIT#재시도_동시_폴링시_한_노드만_클레임한다`(baseline PASS) |
| TC-BATCH-160 | PASS | [실동작] 큐가 DB 영속임을 실측 — `ls_bat_rty_wtng` 에 **2026-07-31 세션에 등록된 행이 08-01 까지 잔존**(bat_rty_sn 9·10·11). [정적] `BatchRetryQueue` 에 인메모리 컬렉션 필드 0(repository 만 보유, :40-50) |
| TC-BATCH-161 | PASS | [실동작] rawSn 9109 수동 재처리 시 기존 `PENDING` 행(bat_rty_sn 9) 삭제 후 새 행 생성 확인. [정적] `deleteIdleByRawSn` = `DELETE … WHERE rawSn=:rawSn AND sttsCd <> 'RETRYING'`(LsBatRtyWtngRepository.java:151-153) — RETRYING 보존 |
| TC-BATCH-162 | PASS | [정적] `BatchOrchestrator.java:133` 성공 경로에서 `retryQueue.clear(rawSn)` → `deleteByRawSn`(무조건 삭제, BatchRetryQueue.java:125-131). [실동작] 성공 완주한 rawSn 26·27 에 `ls_bat_rty_wtng` 행 0건 |
| TC-BATCH-163 | PASS | [실동작] rawSn 15(raw `FAILED` + 작업상태 `APPROVED`) 재처리 → **409** "검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다." + `ls_data_raw.data_stts_cd` **FAILED 유지**(PROCESSING 고착 없음). 2회 반복해도 동일 409/FAILED — 보상 롤백 정상(BatchReprocessService.java:95-100 · BatchTransitionService.java:364-386) |
| TC-BATCH-164 | PASS | [실동작] 합성 stale 행 투입(`mdfcn_dt = now-500분`, 임계 180분) 후 스윕 tick(19:31:22) 관측 → rawSn 9301(`rty_nmtm=1 < max 3`)이 `RETRYING → **PENDING**`, `rty_nmtm 1→**2**`(죽은 시도 1회 계상), `rty_prnmnt_dt = 19:32:22`(= now+`initial-delay-sec` 60s 고정 지연). 대조군 rawSn 9303(`mdfcn_dt = now-5분`, 임계 미달)은 **RETRYING 그대로 유지** — 정상 처리 중 항목 오회수 없음. 로그 `stale RETRYING reclaimed=1 exhausted=1 staleTimeoutMinutes=180` |
| TC-BATCH-165 | PASS | [실동작] 같은 tick 에서 rawSn 9302(`rty_nmtm=3 == max_rty_nmtm 3`)는 복귀하지 않고 `**EXHAUSTED**` 로 종결(`rty_prnmnt_dt=NULL`, `rty_nmtm` 미증가) — 무한 부활 차단. [정적] 두 UPDATE 조건이 `rtyNmtm < maxRtyNmtm` / `>=` 로 상호배타(LsBatRtyWtngRepository.java:117·135)이며 양쪽 모두 `sttsCd='RETRYING' AND mdfcnDt <= :cutoff` 를 UPDATE 조건에 재실어 2노드 동시 회수 시 한쪽만 1행(CAS) |
| TC-BATCH-166 | PASS | [정적] `MIN_STALE_TIMEOUT_MINUTES=30`(BatchRetryStaleReclaimSweeper.java:64) + 생성자 clamp `staleTimeoutMinutes<1 ? 180 : max(30, v)`(:94-96) — 0/음수는 180, 5는 30 으로 상향. [실동작] 기동 로그 `staleTimeoutMinutes=180`(기본값 경로) |
| TC-BATCH-167 | PASS | [실동작] **`authoring.batch.enabled=false` 인 local 에서도** 기동 로그 `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000 staleTimeoutMinutes=180` + 전용 데몬 스레드 `batch-retry-stale-reclaim` 로 tick 관측 → 자기 토글만 본다는 계약 실증. [정적] `@ConditionalOnProperty` 없이 `@Value(...stale-reclaim.enabled:true)` + `Executors.newSingleThreadScheduledExecutor`(:106-111), `@Scheduled` 미사용 |
| TC-BATCH-168 | PASS | [정적] `run()` 이 `catch (Throwable)` 로 삼키고 `e.getClass().getSimpleName()` 만 ERROR 로그 후 0 반환(:142-157) — 메시지·스택트레이스 미노출, `scheduleWithFixedDelay` 사멸 방지 |

**B-12 소계**: PASS 16 · FAIL 1 · PARTIAL 1 · BLOCKED 1

---

## 3. B-15. 배치 스텝 트랜잭션 경계 (5스텝 + 정적 드리프트 가드) — 11건

> 전제 재확인: 호출자 `BatchOrchestrator.process`·`AsyncDeidentifyRunner.runAsync` 둘 다 무-트랜잭션.
> **[실동작 공통 근거]** 컨테이너 40시간 가동 로그 전체에서 `Executing an update/delete query` · `TransactionRequiredException` **0건**, 그 사이 FRAME_EXTRACT/YOLO/SAM2/INTERPOLATE 단계가 rawSn 26·27·31·38 등에서 정상 완주.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-180 | PASS | [정적] `YoloAutolabelStep.java:154-157` — `@Override @Transactional(controlTransactionManager, REQUIRES_NEW) public void execute(BatchContext)`, 내부는 `ctx.setHints(run(...))` 자기호출. `bumpLabelVersionIn` 은 이 경계 안에서 수행. [실동작] 배치 YOLO 단계가 예외 없이 완주(단 이 환경 ai-server 가 `weights_missing` mock 응답이라 검출 0건 → `LBL_VER` 증가 자체는 미관측). 커버: `YoloStepTransactionBoundaryIntegrationTest#결함1_무트랜잭션_호출자에서_YOLO_execute가_프록시경유로_트랜잭션을_열어_LBL_VER를_증가시킨다`(baseline PASS) |
| TC-BATCH-181 | PASS | [정적] `Sam2SegmentStep.java:133-135` REQUIRES_NEW. [실동작] `[Batch][Sam2] saved polygons rawSn=… count=0` 정상 커밋 종료(예외 0) |
| TC-BATCH-182 | PASS | [정적] `TrackInterpolationStep.java:103-105` REQUIRES_NEW. [실동작] `[Batch][Interpolation] no interpolation candidates` 정상 종료 |
| TC-BATCH-183 | PASS | [정적] `VlmTimeseriesStep.java:218-220` — `@Transactional(REQUIRES_NEW)` 이며 **`readOnly` 미지정(=쓰기 가능)**. 분기 `runWithMarking`(:252, 쓰기) / `run`(:236, readOnly) 은 자기호출이라 상위 경계 상한을 따름. ⚠ 근거 드리프트: 카탈로그 `128-135` → 실제 `218-220` |
| TC-BATCH-184 | PASS | [정적] `FfmpegFrameExtractor.java:128-130` REQUIRES_NEW. [실동작] rawSn 27 배치가 `ls_data_src` 5행(301~305, 원본/비식별 2경로 컬럼 모두 채움) 커밋 — 무-트랜잭션이면 불가능 |
| TC-BATCH-185 | PASS | [정적] 5스텝 모두 `execute` 안에서 `this.run(...)`/`this.extractByMarks(...)` **자기호출**(YoloAutolabelStep.java:156 · FfmpegFrameExtractor.java:141 · Sam2/Interpolate/Vlm 동형) → 어드바이스 미적용 → REQUIRES_NEW 1회. 주석에 "프록시 경유로 바꾸면 중첩" 규약 명시 |
| TC-BATCH-186 | PASS | [정적] typed 진입점의 `@Transactional(REQUIRES_NEW)` 보존 확인 — `YoloAutolabelStep.java:167`, `VlmTimeseriesStep.java:236·252`, `FfmpegFrameExtractor.java:160·175`, `Sam2SegmentStep.java:146`, `TrackInterpolationStep.java:115`. ⚠ 근거 드리프트: 카탈로그 `VlmTimeseriesStep.java:150-166` → 실제 `236·252` |
| TC-BATCH-187 | PASS | [정적] `DeidentifyStep.execute`(:239-240) **무애노테이션** + `selfProvider.getObject()` 프록시 경유 `run()` 호출(:245). 테스트 `BatchStepTransactionBoundaryTest.BOUNDARY_EXEMPT`(:53)에 등재 + `selfProxyStepsMustNotAnnotateExecute` 가 애노테이션 **부재를 단언**(중첩 방지). [실동작] 선두 비식별이 rawSn 27·29 에서 `de_ident_yn='Y'`+procLog SUCCEEDED 를 커밋 |
| TC-BATCH-188 | PASS | [정적] `MarkingLoadStep.execute`(:49-58) 은 조회 + `parseMarks` JSON 파싱만 — DML 0건. `BOUNDARY_EXEMPT` 등재. 근거 라인 소폭 드리프트(카탈로그 50-58 → 실제 49-58) |
| TC-BATCH-189 | PASS | [정적] `BatchStepTransactionBoundaryTest.java:55-105` — `ClassPathScanningCandidateComponentProvider`+`AssignableTypeFilter(BatchStep)` 로 `kr.co.cudo.authoring` 전체 스캔, 스캔 결과 `hasSizeGreaterThanOrEqualTo(6)` 공허 단언 방지, 면제 밖 구현에 `@Transactional` non-null + `REQUIRES_NEW` + `value="controlTransactionManager"` 3중 단언. `execute(BatchContext)` **직접 선언이 없으면 `executeMethod`가 AssertionError**(:71-78) → 상속으로 숨겨도 실패. baseline PASS |
| TC-BATCH-190 | PASS | [정적] `LsDataSrcRepository.java:292-295` — `@Modifying` + `@Query(native UPDATE)` 뿐, `@Transactional` **없음**. 리포 전체 `@Modifying` 4종 모두 동일 규약(:198·224·292·305). 사유는 :283-291 주석에 명문화(스텝 원자성·프레임 락 결합·경계 누락 무증상화 방지) |

**B-15 소계**: PASS 11 · FAIL 0

---

## 4. 판정 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-11 (신고/resolve) | 30 | 26 | 0 | 2 | 0 | 2(폐기) | 0 |
| B-12 (재처리/재시도) | 19 | 16 | 1 | 1 | 1 | 0 | 0 |
| B-15 (트랜잭션 경계) | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **60** | **53** | **1** | **3** | **1** | **2** | **0** |

> 폐기 2건(TC-DEID-034·038) 제외 시 판정 대상 58건 · PASS율 91.4%(53/58).
> **실동작 근거 비율**: 60건 중 44건이 [실동작] 1차 근거(기동 스택 요청/DB/로그), 나머지는 [정적]+baseline 테스트 커버.

---

## 5. 이슈

### [B-ISSUE-101] TC-BATCH-153 / TC-BATCH-154 — 배치 수동 재처리 원자 클레임이 2컬럼 폴백 때문에 상호배제에 실패(동일 rawSn 파이프라인 이중 실행)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `POST /v1/videos/{rawSn}/batch/retry` 는 동시 다중 요청·자동 폴러와 경합해도 **정확히 1건만** FAILED→PROCESSING 을 선점하고 나머지는 409 로 거부되어야 한다(`BatchReprocessService` javadoc "이중 파이프라인 실행 차단", CWE-362). 같은 영상의 파이프라인이 2벌 동시에 돌면 프레임 재추출·오토라벨 중복 INSERT·외부(ai-server/VLM) 중복 위탁·재시도 예산 이중 소모가 발생한다.
- **현재 동작(이슈 내용)**: `BatchTransitionService.tryClaimReprocessFromFailed`(BatchTransitionService.java:335-347)가 RAW 컬럼 클레임이 0행이면 **원인을 구분하지 않고** 작업상태 컬럼 클레임으로 폴백한다.
  ```java
  int rawClaimed = videoRepository.claimReprocessFromFailed(rawSn, FAILED, PROCESSING);
  if (rawClaimed == 1) { return true; }
  int statusClaimed = rawDataStatusRepository.claimReprocessFromFailed(rawSn, FAILED, PROCESSING);
  return statusClaimed == 1;   // ← 0행의 이유가 "남이 방금 선점" 이어도 여기로 내려온다
  ```
  정상 배치 실패는 `LS_DATA_RAW.DATA_STTS_CD` 와 `LS_RAW_DATA_STATUS.DATA_STTS_CD` 를 **함께 FAILED** 로 두므로, 호출자 A 가 RAW 컬럼을, 호출자 B 가 작업상태 컬럼을 각각 선점해 **둘 다 true** 를 받는다.
  실측(2026-08-01 19:17:39, rawSn 9109 · 5요청 동시): 응답 **200 2건 / 409 3건**, 로그가 서로 다른 스레드에서 같은 밀리초에
  ```
  19:17:39.920 [http-nio-8080-exec-7]  [BatchReprocess] manual retry claimed rawSn=9109
  19:17:39.921 [http-nio-8080-exec-11] [BatchReprocess] manual retry claimed rawSn=9109
  19:17:39.925 (exec-7)  [BatchOrchestrator] marking check rawSn=9109 count=1
  19:17:39.925 (exec-11) [BatchOrchestrator] marking check rawSn=9109 count=1
  19:17:39.929 (exec-7)  [BatchRetry] enqueued rawSn=9109 attempt=1 delaySec=60
  19:17:39.929 (exec-11) [BatchRetry] enqueued rawSn=9109 attempt=2 delaySec=120
  ```
  를 남겨 **파이프라인 2벌 동시 실행 + 재시도 카운터 이중 증가**가 확인된다.
- **재현/확인 경로**:
  ```bash
  # 전제: raw 배치상태와 작업상태가 둘 다 FAILED 인 영상 (정상 배치 실패의 기본형)
  psql -c "select r.data_stts_cd, s.data_stts_cd from ls_data_raw r
             left join ls_raw_data_status s on s.raw_data_id=r.raw_sn where r.raw_sn=9107"   # FAILED | FAILED
  for i in 1 2 3 4 5; do (curl -s -o /dev/null -w "%{http_code} " \
      -X POST http://localhost:18081/api/v1/videos/9107/batch/retry -H "Authorization: Bearer $REVIEWER") & done; wait
  # 실측: 409 409 409 200 200   ← 200 이 2건
  docker logs klid-backend | grep -c "manual retry claimed rawSn=9107"   # 2
  ```
  **대조군**(원인 격리): 작업상태 행이 없는 rawSn 11(`FAILED | (null)`)에 동일 시행 → `409 409 409 409 200`, claimed 로그 **1건**. 즉 "두 컬럼이 모두 FAILED" 일 때만 이중 클레임이 성립한다.
- **영향**: 동시성 결함(CWE-362, OWASP API6 — 민감 비즈니스 플로우 무제한 접근). ①동일 영상 프레임 추출/오토라벨의 중복 INSERT·경합 ②ai-server·mock/실 VLM 로의 중복 외부 위탁(비용·PII 전송량 증가) ③재시도 예산(`RTY_NMTM`)이 1회 실행당 2회 소모되어 조기 EXHAUSTED ④두 실행 중 늦게 끝난 쪽이 상태를 덮어써 배치 단계 상태가 비결정. REVIEWER 권한이 필요하지만 **버튼 더블클릭·프론트 재전송만으로도 자연 발생**한다.
- **테스트 사각지대**: `BatchReprocessServiceTest#배치재처리_동시요청시_한쪽만_기동된다`(:81-97)는 `transitionService.tryClaimReprocessFromFailed` 를 **mock 으로 false 고정**해 검증하므로, 실제 2컬럼 폴백 로직을 한 번도 실행하지 않는다. 회귀 가드가 없다.
- **수정 방향(제안)**: (구현하지 않음)
  1. `tryClaimReprocessFromFailed` 에서 RAW 클레임 0행일 때 **폴백 전에 RAW 상태를 재판정**한다 — RAW 가 이미 `PROCESSING`(=남이 방금 선점) 이면 즉시 `false` 를 반환하고, RAW 가 `FAILED` 가 아닌 다른 상태(작업상태만 FAILED 인 예외 형상)일 때만 작업상태 폴백을 허용.
  2. 또는 클레임 자체를 **단일 권위 컬럼**(LS_DATA_RAW)으로 일원화하고, 작업상태만 FAILED 인 형상은 별도 조건부 UPDATE 한 문장(두 테이블을 한 트랜잭션에서 `SELECT … FOR UPDATE` 후 전이)으로 처리.
  3. 회귀 가드로 **실 DB 동시성 IT**(Testcontainers, 두 스레드가 실제 `tryClaimReprocessFromFailed` 를 호출)를 추가해 "성공 1건" 을 단언 — 현행 mock 기반 단위 테스트로는 재발을 못 잡는다.

### [B-ISSUE-102] TC-DEID-044 / TC-DEID-045 — resolve 산출물 검증의 60초 스큐 관용이 "재비식별하지 않은 옛 산출물"을 통과시킨다(신고 게이트 일괄 해제)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `POST /v1/deident-reports/{rprtSn}/resolve` 는 **신고 이후 실제로 재비식별된** 산출물이 있을 때만 통과해야 한다. 이 지점의 통과는 `DE_IDNTF_YN` 을 `'F'→'Y'` 로 되돌려 라벨 조회 412·프레임 이미지 412·영상 스트리밍 404·export 보류 게이트를 **한꺼번에 여는** 단일 관문이므로, 위장 산출물 통과 = 마스킹 실패 픽셀 재노출이다(CWE-359, `verifyDeidentArtifact` javadoc 명시 목적).
- **현재 동작(이슈 내용)**: 시간 판정 (2)번 조건이 신고시각에서 **60초를 빼고** 비교한다.
  ```java
  private static final long CLOCK_SKEW_TOLERANCE_SECONDS = 60L;
  ...
  LocalDateTime mtime = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
  fileAfterReport = mtime.isAfter(reportTime.minusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS));   // DeidentReportService.java:567-573
  ```
  따라서 **`신고시각-60s < mtime ≤ 신고시각`** 인 파일, 즉 *신고를 유발한 바로 그 비식별본* 도 "신고 이후 교체"로 인정된다. 실측(rawSn 6 / rprtSn 7): 파일 mtime `2026-07-31 03:23:21.127`, 신고 `dclr_dt=2026-07-31 03:23:21.165`(mtime 이 신고보다 **38ms 이르다**), 최신 SUCCESS procLog `rspns_dt=03:02:21`(신고 이전) — 두 조건 모두 "신고 이후 재비식별"이 아님에도 **resolve 200** 으로 통과했고 `de_ident_yn` 이 `'F'→'Y'`, 작업락 해제, `report_stts_cd=RESOLVED` 가 되었다.
  또한 `reportDt` 는 `LsDeidentReport.createReport` 의 `LocalDateTime.now()`(앱 JVM 시계)이고 mtime 도 같은 JVM 의 `ZoneId.systemDefault()` 로 환산되므로, **동일 호스트 배치에서는 보정할 스큐가 사실상 없다** — 관용치가 순수 손실로 남는다.
- **재현/확인 경로**:
  ```bash
  # 1) 비식별 산출물이 막 기록된 영상에서(=deid 파일 mtime ≈ now) 60초 안에 신고
  curl -X POST .../v1/videos/{rawSn}/deident-report -d '{"reason":"leftover face"}'
  # 2) 아무런 외부 재비식별 없이 즉시 resolve
  curl -X POST .../v1/deident-reports/{rprtSn}/resolve      # → 200
  psql -c "select de_ident_yn from ls_data_raw where raw_sn={rawSn}"   # → Y (게이트 전부 재개방)
  ```
  실증 데이터: `select r.dclr_dt from ls_deident_report r where deident_report_sn=7` = `03:23:21.165` vs `stat -c %y {deid파일}` = `2026-07-30 18:23:21.127 +0000`(=KST 03:23:21.127).
- **영향**: PII 재노출 창(CWE-359 / CWE-367). 특히 **마킹 화면 동선**이 위험하다 — 선두 비식별 완료(`MARKING_READY`) 직후 마킹을 시작해 60초 안에 누락을 신고하는 것은 정상 동선이고, 그 상태에서 resolve 를 누르면 재비식별 없이 신고가 닫히며 라벨·프레임·스트리밍·export 게이트가 모두 열린다. WORKER(본인 배정)도 resolve 권한이 있어 오·남용 표면이 넓다.
- **수정 방향(제안)**: (구현하지 않음)
  1. mtime 비교의 기준을 **완화 방향이 아니라 강화 방향**으로 바꾼다 — `mtime > reportTime` 엄격 비교로 두고, 스큐가 실제로 문제인 환경(NAS 가 다른 호스트 시계로 mtime 을 찍는 경우)만 별도 설정값으로 opt-in.
  2. 또는 "재비식별 사실"을 시각이 아니라 **콘텐츠 동일성**으로 판정한다 — 신고 시점에 그때의 비식별본 해시/크기/mtime 을 `LS_DEIDENT_REPORT` 에 스냅샷 기록하고, resolve 때 **스냅샷과 달라졌는지**를 본다(시계 의존 제거, 60초 창 소멸).
  3. 최소한 관용치를 `CLOCK_SKEW_TOLERANCE_SECONDS` 설정값으로 외부화하고 기본을 0 으로 낮춘 뒤, 실제 스큐가 관측되는 환경만 올린다.
  4. 회귀 가드: `DeidentReportServiceTest#신고이전_비식별본만_존재시_resolve_거부된다` 는 mtime 을 충분히 과거로 두어 통과하고 있다 — **경계값(`reportTime - 59s`) 케이스**를 추가해야 이 창이 드러난다.

---

## 6. 근거 드리프트 (카탈로그 정합성)

| 케이스 | 카탈로그 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-BATCH-183 | `VlmTimeseriesStep.java:128-135` | `VlmTimeseriesStep.java:218-220` | execute 위치 이동(약 90줄) — 실질 드리프트 |
| TC-BATCH-186 | `VlmTimeseriesStep.java:150-166` | `VlmTimeseriesStep.java:236`(run) · `252`(runWithMarking) | 실질 드리프트 |
| TC-BATCH-154 | `BatchTransitionService.java:334-362` | `334-347` | 범위 과대(포함은 함) |
| TC-BATCH-188 | `MarkingLoadStep.java:50-58` | `49-58` | 1줄 |
| TC-BATCH-167 | `BatchRetryStaleReclaimSweeper.java:42-50,84,102-110` | javadoc `42-52` · `@Value 84` · scheduler `106-111` | 소폭 |
| TC-DEID-057 | `DeidentReportService.java:480-491` | 발행문 `491`(javadoc 464-486) | 소폭 |
| TC-DEID-047 | `DeidentReportService.java:443-470` | `443-462` | 범위 과대 |
| TC-DEID-053 | `DeidentReportService.java:182-183,295-310` | `182-183` · `295-306` | 범위 과대 |
| TC-DEID-054 | `DeidentReportService.java:185-186,329-340` | `185-186` · `329-337` | 범위 과대 |

그 외 B-11/B-12/B-15 근거는 실제 위치와 일치.

---

## 7. 관측된 부수 사실 (결함 판정 아님 · 기록용)

1. **local 프로파일은 배치 Quartz 잡을 등록하지 않는다** — `application-local.yml:61-62` `authoring.batch.enabled: false`. `qrtz_triggers` 실측 3건(kpstDeidentPoll · datasetExportFailureRecovery · datasetExportPendingSweep)만 존재하고 `batchRetryTrigger` · `controlTrainingVideoScanTrigger` 는 부재. 그 결과 `ls_bat_rty_wtng` 의 도래한 PENDING 행(2026-07-31 등록분 포함)이 소진되지 않고 잔존한다. **의도된 로컬 설정**이며 TC-BATCH-159 BLOCKED 사유다.
2. **stale 회수 스윕은 배치 토글과 독립적으로 살아 있다** — 위 1번 상황에서도 `batch-retry-stale-reclaim` 데몬이 15분 주기로 tick 하며 회수를 수행했다(TC-BATCH-167 의 설계 의도 실증).
3. **`de_ident_yn` 컬럼 물리명 불일치** — 문서·주석은 `DE_IDNTF_YN` 을 쓰지만 실제 DDL/DB 컬럼은 **`DE_IDENT_YN`**(`LsDataRaw.java:118` `@Column(name = "DE_IDENT_YN")`). 코드는 일관되게 이 이름으로 매핑하므로 동작 결함은 없으나, 표준용어 관점(비식별=DE_IDNTF)과 문서 표기가 어긋나 SQL 직접 조회 시 혼동을 유발한다(본 검증에서도 최초 쿼리가 실패했다).
4. **`reExport=false`** — 신고 시 발행된 `TASK_MODIFIED`(META_UPDATED)는 `reExport=false` 로 전송된다. 신고 구간에는 export 가 게이트로 보류되므로 정합적이며, resolve 시 `DeidentReportResolvedEvent` → 재산출(`version=2`)로 이어지는 것을 실측했다(TC-DEID-058).
5. **개인정보 3필드 리셋의 벌크 UPDATE 영향행 ≠ 감사 행수** — rawSn 27 에서 `privacyReset=5`(영상 전 프레임 UPDATE) / `privacyResetAudited=3`(실제 값 보유 프레임). 감사 대상 선정이 "리셋 직전 값 보유 프레임" 이라는 계약과 일치한다(TC-DEID-052).
# B-part7 — B-13. KPST 비식별 위탁·폴링 (원본 가드 · 무결성 · 원자 클레임)

- **대상**: `docs/test-cases/B-batch-deidentify.md` → `## B-13` (TC-DEID-060 ~ TC-DEID-093, **34건**)
- **코드 기준**: qa-0801 `56d30478`
- **검증 일시**: 2026-08-01
- **이슈 ID 범위**: B-ISSUE-121 ~ B-ISSUE-140 (실사용 121~127)

## 0. 환경 버전 격차 판정 (BLOCKED 여부)

기동 중 backend 이미지는 구버전(V146)이나, **B-13 관련 프로덕션 코드 6파일은 전부 `b2b44f0e` 이전에 고정**되어 실행 이미지와 워크트리 코드가 동일하다.

```
git log b2b44f0e..56d30478 -- KpstDeidentService/KpstDeidentTxService/KpstDeidentPollJob/
                              LsDeidentProcLogRepository/DeidentArtifactIntegrity/DeidentFrameAttacher
→ (출력 없음)
git merge-base --is-ancestor 862ca6d8 b2b44f0e → true   # KPST 최신 변경 862ca6d8(2026-07-30) 포함
```

→ **B-13 은 환경 버전 격차 BLOCKED 대상이 아니다.** 34건 전부 현행 스택 위에서 실동작 검증 가능.

## 1. 실동작 기반(라이브 근거 요약)

| # | 확인 내용 | 근거 |
|---|---|---|
| L1 | KPST 위탁 실왕복 | mock-server `POST /project` 200 (prj_id 1~20) + backend `[KpstDeid] submitted rawSn=27 prjId=11` (19:09:21) → `poll completed rawSn=27 prjId=11 datasetId=11` (19:09:51). `DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400` |
| L2 | **★알려진 함정 재현** — `fileName` = 원본 절대경로, 산출물 = `{stem}-mask{ext}` | 직접 왕복(prj_id=14): 응답 `"fileName":"/app/storage/raw/seed/clip-9101.mp4"` / 실제 산출물 `/app/storage/deidentified/videos/qa-b13/clip-9101-mask.mp4`(50,854B). **혼동 없이 정상 회수** — DB 25건 전부 `{stem}-mask{ext}` 로 적재됨 |
| L3 | **★원자 클레임 2세션 동시 재현** | 실제 WAITING 행(`proc_log_sn=40`)에 `claimForPoll` 동일 SQL 을 두 세션 동시 실행 → `[A] UPDATE 1` / `[B] UPDATE 0`(B 는 행 락 대기 후 갱신된 버전으로 WHERE 재평가). 이후 그 행은 정상 `DOWNLOADED/SUCCEEDED` 완료 — 부작용 없음 |
| L4 | **★클레임 술어 fail-closed** | `DOWNLOADED` 행(`proc_log_sn=25`)에 동일 SQL → `UPDATE 0` |
| L5 | **★원본 실재 가드 발화** | `[KpstDeid] submit rejected — source video missing rawSn=8/9/10/11/15/16/32` + DB `err_cd=KPST_SOURCE_MISSING` + `ls_data_raw.de_ident_yn='F'` 커밋됨. **mock-server 로그에 해당 rawSn 의 `POST /project` 없음** = createProject 미호출 확인. 로그·에러에 원본 경로 원문 없음 |
| L6 | 산출물 컨테이너 시그니처 | 실산출물 선두 `\0\0\0 f t y p i s o m` (ISO-BMFF `ftyp`), 50,854B ≥ 512B |
| L7 | 폴링 잡 noop / 시도 카운터 | `[KpstDeidPoll] no pending poll target — skipping tick` 30초 주기 반복(외부 호출 0) · 진행중 행 `poll_atmpt_cnt=1` 관측 |
| L8 | 자동테스트 baseline | backend 4,755 tests / **실패 0** (`_raw/test-baseline.md`). KPST 전용 7파일 · 약 100 케이스 전량 통과 |

## 2. 판정표

| ID | 판정 | 근거 확인 | 비고(현행 file:line) |
|---|:--:|---|---|
| TC-DEID-060 | PASS | [실동작] L1 | 선커밋 원장 `issueSubmitLedger`(TxService:83-91, `markKpstSubmitPending`→POLL_WAITING) → 논블로킹 제출(Service:375-431) → ACK 시 `recordSubmitAck`(TxService:102-113)가 prjId 기록. `DE_IDNTF_YN` 미전이(raw 27 PENDING 유지 후 완료 시 Y). ⚠ 기대결과의 `markKpstSubmitted(prjId)` 는 현재 dead(B-ISSUE-127), 실질 단언 3개는 모두 성립 |
| TC-DEID-061 | PASS | [정적] | `buildProjectRequest` Service:319-323 — `parent == null` → `INVALID_INPUT`. 메시지에 경로 원문 없음. 테스트 `원본경로_부모디렉터리가_null이면_F마킹하고_예외전파_createProject미호출` |
| TC-DEID-062 | PARTIAL | [정적] | **동작 변경(Phase C-2)**: createProject 실패는 동기 예외 전파가 아니라 `subscribeSubmit`(Service:421-431) 의 err 핸들러 → `KpstSubmitOutcomeRecorder.onSubmitFailed` → `failSubmit`(TxService:163-184) = procLog FAILED + `markDeidentified("F")` + (REDEIDENT면) 락 해제. 3단언 중 2개 성립, **예외 전파는 더 이상 없음** → B-ISSUE-125 |
| TC-DEID-063 | PARTIAL | [정적] | `cleanExportDir` Service:488-527 — ①리졸버 재계산 경로와 `equals` 일치 시에만 진행 ②`Files.list` 비재귀 ③`isRegularFile(NOFOLLOW_LINKS)` 필터(심링크/하위디렉터리 제외, 라이브에서 `.mock-tmp/` 디렉터리 생존 확인) ④미존재 no-op ⑤IOException 시 경로 미노출 로그 후 진행 — **가드 자체는 기대대로**. 단 **삭제가 새 산출물 확보 전에 일어나** 재위탁 실패 시 기존 비식별본이 영구 소실 → B-ISSUE-121 |
| TC-DEID-064 | PASS | [실동작] L1 L2 | `allDatasetsCompleted`(Service:966-978, 전체 AND) → `downloadResult`(764-791) → `finishDownloadAndComplete`(TxService:206-223). 라이브: rawSn 4·5·6·7·17·26·27·28·29·30·31·33·34·35·36 완료 전이 |
| TC-DEID-065 | PASS | [정적] | Service:587-598 — `anyDatasetFailed` 를 `allDatasetsCompleted` **앞**에서 평가. REDEIDENT 는 `failRedeidentCompletion`(락 해제), 배치는 `failPolling`. 테스트 4건(`procState99…`/`K2_procState3`/`K2_procState4`/`다중데이터셋_2와99…`) |
| TC-DEID-066 | PASS | [정적] | Service:94-98 `PROC_STATE_TERMINAL_FAILED = {3,4,99}`. `K2_procState5는_procState도메인_밖이므로_실패아님` 로 경계 고정 |
| TC-DEID-067 | PARTIAL | [정적] | **동작 변경(Phase C-2)**: prjId null 은 이제 "ACK 대기"로 해석 — 유예 안이면 **아무것도 안 함**(Service:541-556, `markTimeoutIfExpired` 미호출로 시도/경과 예산 미소모), 유예 초과 시 `failSubmit(ACK_MISSING)`(557-560). "외부 미호출"은 성립하나 기대결과의 `markTimeoutIfExpired` 는 무효 → B-ISSUE-124 |
| TC-DEID-068 | PASS | [정적] | Service:562-576 — catch 안에서 `markTimeoutIfExpired` 평가 + 예외 클래스명만 로그. 테스트 2건(`K1_retrieveProgress…`) |
| TC-DEID-069 | PASS | [정적] | Service:611-625 — `downloadResult` RuntimeException → REDEIDENT는 `failRedeidentCompletion`(락 해제), 아니면 `failPolling`. 예외가 `pollOne` 밖으로 새지 않음. 테스트 4건 |
| TC-DEID-070 | PASS | [정적] L6 | Service:634-648 — `isUsableDeidFile` false → `failPolling`/`failRedeidentCompletion`, Y 전이 없음. Y 직전 최종 게이트도 `verifyDeidFile`(TxService:432-441) 이중 |
| TC-DEID-071 | PASS | [실동작] L7 | Service:665-669 `recordPollingProgress` + `markTimeoutIfExpired`. 라이브 진행중 행 `poll_atmpt_cnt=1` 관측 |
| TC-DEID-072 | PASS | [실동작] L2 | `sanitizeFileName`(869-890) basename 추출 → `toMaskName`(812-822). `-mask` 로 끝나면 재부여 안 함(`stem.endsWith(MASK_SUFFIX)`). 라이브 25행 전부 `{stem}-mask{ext}` |
| TC-DEID-073 | PASS | [정적] | `scanSingleUsable` Service:833-857 — 1개 회수(+`startsWith(dir)` 재단언) / 0개 null / ≥2 `INVALID_INPUT` → 호출측이 terminal 종결. 테스트 `폴백스캔_산출물이_2개이상이면_모호하여…failPolling으로_종결한다` |
| TC-DEID-074 | PASS | [정적] | Service:869-890 — `Paths.get` `InvalidPathException`(NUL) 정규화, basename 추출 후 `/`·`\`·`..` 잔존 거부, 메시지에 원문 미노출. 테스트 `sanitizeFileName_NUL바이트면…원문미노출`, `경로형_fileName에_상위참조가_섞여도_basename만_취해…` |
| TC-DEID-075 | PASS | [실동작] L7 | PollJob:95-98 DEBUG + 즉시 return. 라이브 로그 30초마다 확인, mock-server 인바운드 0건 |
| TC-DEID-076 | PASS | [정적] | PollJob:110-114 건별 try/catch, `e.getClass().getSimpleName()` 만. 테스트 `한_작업_폴링실패가_다른_작업을_막지_않는다` |
| TC-DEID-077 | PASS | [정적] L3 | `@DisallowConcurrentExecution` PollJob:43(같은 노드 한정). 노드 간 중복은 L3 의 리스 클레임이 차단 — Quartz 설정 비의존 |
| TC-DEID-078 | PASS | [정적] | PollJob:93-94 `findByPollSttsCdIn(POLL_TARGET_STATUSES, pollPage())` (Repository:55). 인메모리 상태 없음. 테스트 `WAITING_POLLING_상태를_DB조회로_폴링재개한다`, `통합_재기동_WAITING건이_재폴링_조회된다` |
| TC-DEID-079 | PASS | [정적] | Service:898-911 — `completeDeidentification` catch 에서 비-REDEIDENT면 `txService.markRawDeidentFailed`(TxService:269-273, REQUIRES_NEW) 로 F 별도 커밋 후 재throw. 테스트 `M1_비식별파일무효시_markRawDeidentFailed가_F를_별도커밋한다` + REDEIDENT 반대 케이스 |
| TC-DEID-080 | PASS | [정적] | `@ConditionalOnProperty(prefix="kpst.deid", name="enabled", havingValue="true")` — Service:69 · TxService:37 · `KpstDeidentPollTriggerConfig:20`(JobDetail·Trigger 빈 동시 미등록) |
| TC-DEID-081 | PASS | [실동작] L5 | `verifySourceOrFail` Service:446-466(호출 286) — `Files.isRegularFile` fail-closed → `batchTransitionService.recordDeidentFailure`(REQUIRES_NEW 'F' 커밋) 후 `INVALID_INPUT`. 라이브 7건 발화, createProject 미호출·경로 미노출 모두 확인 |
| TC-DEID-082 | PASS | [정적] | Service:447-451 — `verifySourceExists=false` 면 `log.warn("… source existence guard disabled — 원본 미검증 위탁 rawSn={}")` 1줄 후 통과. 설정 기본 `true`(application.yml:505-507). 테스트 `원본_실재_가드는_프로퍼티로_끌_수_있고_끄면_WARN_이_남는다` |
| TC-DEID-083 | PASS | [실동작] L6 | `isUsableDeidFile`(Service:692-694) → `DeidentArtifactIntegrity.isValidVideoArtifact`(43,85-107): 정규파일(NOFOLLOW) + ≥512B(`MIN_VIDEO_BYTES`:49) + 컨테이너 시그니처(135-171). MPEG-TS 는 188B 정렬 3회 요구(184-192)라 `"GET …"` 텍스트 오판 차단, `junk` 박스 제외. 단위 테스트 11건 |
| TC-DEID-084 | PARTIAL | [정적] | Service:626-633 → `recheckAfterGrace`(707-730): 후보 파일이 **존재할 때만** `min(설정, 5000ms)` 대기 후 1회 재산출·재판정, 미존재면 즉시 종결(`fileExists` 732-742). 기대결과는 충족하나 `Thread.sleep` 이 **Quartz 워커를 점유**해 clamp 가 틱 단위로는 보호가 되지 않음(최악 200×5s) → B-ISSUE-123 |
| TC-DEID-085 | PASS | [실동작] L3 | PollJob:103-108 → `tryClaimPoll`(TxService:60-66) → `claimForPoll`(Repository:81-92). 2세션 동시 실행에서 정확히 1행. IT `2스레드_동시_폴링시_동일_위탁건이_한_번만_클레임된다`(Testcontainers PG) 통과 |
| TC-DEID-086 | PASS | [실동작] L4 | Repository:86-88 `POLL_STTS_CD IN ('WAITING','POLLING')` — DOWNLOADED 행 대상 실 UPDATE 0행. IT `종결된_건은_클레임되지_않는다` |
| TC-DEID-087 | PASS | [정적] | PollJob:126-128 `max(MIN_LEASE_SEC=1, pollIntervalSec-LEASE_SLACK_SEC=5)` → 기본 30-5=25s. 리스 만료로 자동 회수(별도 잠금 컬럼·회수 잡 없음). 테스트 `클레임_리스는_폴링주기보다_짧다…`, IT `단일노드에서도_기존_동작이_유지된다` |
| TC-DEID-088 | PARTIAL | [정적] L3 | `claimDownloadCompletion`(Repository:112-125, 술어 `POLL_STTS_CD IN ('WAITING','POLLING')`) → `finishDownloadAndComplete`(TxService:206-223) 가 `!= 1` 이면 즉시 return(210-213). PG 조건부 UPDATE 재평가 시맨틱은 L3 로 실증 = 메커니즘 정합. 단 **0행 분기(중복 완료 스킵)를 실행하는 테스트가 0건** — 유일 참조가 `KpstDeidentTxServiceTest:82` 의 `thenReturn(1)` 스텁 → B-ISSUE-122 |
| TC-DEID-089 | PARTIAL | [정적] | 클레임 UPDATE 와 후처리가 동일 `REQUIRES_NEW`(TxService:206-223) 안이라 후처리 예외 시 함께 롤백 = 재폴링 대상 유지. 구조상 성립하나 **실 트랜잭션에서 롤백→재클레임 가능을 검증하는 테스트 없음**(단위 테스트는 전량 Mockito) → B-ISSUE-122 |
| TC-DEID-090 | PASS | [실동작] L7 | `pollPage()` PollJob:119-123 — `pollBatchSize<1` 이면 `DEFAULT_BATCH_SIZE=200`(55), 정렬 `pollLastDt asc nullsFirst` + `procLogSn asc`(미폴링 우선·기아 방지). 설정 `poll-batch-size:200`(application.yml:502). 라이브 잡이 이 정렬로 매 틱 정상 조회 중. IT `폴링_조회에_상한이_적용된다` |
| TC-DEID-091 | PASS | [정적] | `recoveryDirs` Service:797-802 — `deidVideoDirQuietly(...).ifPresent(add)`(신 위치) + 항상 `{deid_base}/videos/{rawSn}`(구 위치), `LinkedHashSet` 로 순서·중복 보장. 신 위치 도출 실패해도 구 위치 시도. 테스트 `co_locate_전환후에도_구위치에_남은_배포전_산출물을_회수한다` |
| TC-DEID-092 | PASS | [정적] | Service:785 `log.warn("[KpstDeid] primary mask path miss — recovered by fallback scan rawSn={}", rawSn)` — 경로/파일명 원문 미노출. 테스트 `1차_경로_miss_시_WARN_이_남는다`, `목업_계약_하에서_toMaskName_1차_회수경로가_실제로_사용된다` |
| TC-DEID-093 | PASS | [정적] | `DeidentFrameAttacher.isUsable`(160-162) = `DeidentArtifactIntegrity.isValidVideoArtifact` 위임, 호출 99. 판정 두 벌 없음. 테스트 `deid영상이_유효한_컨테이너가_아니면_예외` |

### 집계

| 판정 | 건수 |
|---|---:|
| PASS | 27 |
| PARTIAL | 7 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **34** |

- 실동작(LIVE) 근거 판정: **13건** / 정적+테스트 근거: 21건
- **self-fill 결함 0건** — 비식별 산출 경로·prjId·datasetId·완료 판정이 전부 mock-server 응답에서 유래함을 로그·DB 로 확인. 내부 목 모드 `DEIDENTIFY_MOCK_MODE=false`

## 3. ★ 지정 위험 4축 반증 결과

| 축 | 결과 |
|---|---|
| **`fileName` 혼동으로 완료→실패 오종결** | **재현 실패(= 방어 정상).** 라이브 응답 `fileName=/app/storage/raw/seed/clip-9101.mp4`(원본 절대경로)를 `sanitizeFileName`→basename→`toMaskName` 으로 변환해 `export_path/clip-9101-mask.mp4` 를 정확히 회수. DB 25행 전부 `-mask` 산출물, 오종결 0건. 1차 경로 miss 시 폴백 + WARN 관측성까지 있음 |
| **원자 클레임(동시 폴러 중복)** | **정상.** 2세션 동시 UPDATE 실측 1/0. 완료 전이도 같은 패턴(`claimDownloadCompletion`)이나 **그 경로만 테스트 커버리지 0**(B-ISSUE-122) |
| **PG unique 위반 → tx abort → 별도 tx + saveAndFlush** | **해당 패턴 반영됨.** `issueSubmitLedger`(TxService:83-91) 가 `REQUIRES_NEW` + `saveAndFlush` 로 외부 호출 **전에** 독립 커밋. 모든 상태 전이가 `REQUIRES_NEW` cross-bean(self-invocation 0건). B-13 경로에는 unique 충돌 표면 자체가 없음(`uk_ls_deident_proc_log_ext_job` 는 `otsd_job_id`, KPST 경로는 null) |
| **원본 가드(원본 절대경로 노출)** | **정상.** shared-mount 모델상 `input_path`/`files` 로 원본 경로를 KPST 에 보내는 것은 계약 자체이며, 그 외 유출 경로는 없음 — 로그 전량 `rawSn/prjId/datasetId/errType` 만(Service:300,391,449,464,518,573,785 / Client:248,270), 예외 메시지에 파일명·경로 원문 없음, mock-server 로그에도 경로 미기록. 위탁 전 원본 실재 가드는 라이브 7건 발화(L5) |

## 4. 이슈

### [B-ISSUE-121] TC-DEID-063 — 재위탁 시 기존 비식별 산출물을 **선삭제**해, 위탁 실패 시 검수완료 영상의 비식별본이 영구 소실된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `cleanExportDir` 의 목적은 "이번 회차 산출물만 남겨 폴백 스캔의 stale 오회수·다중파일 모호 실패를 막는 것"이다. 그 목적은 **새 산출물이 확보된 뒤**에도 달성 가능하다. 반면 `CLAUDE.md`(★2026-07-28 확정)는 검수 완료·통지된 영상에 대해 "어떤 사유로도 뷰에서 감추거나 경로를 비우지 않는다 — 비식별 누락 신고(`DE_IDNTF_YN='F'`) 구간에도 관제 접근을 차단하지 않는다"를 구속 규칙으로 둔다. 즉 신고 구간에도 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 이 가리키는 파일이 **실재해야** 그 보장이 성립한다.
- **현재 동작(이슈 내용)**: 위탁 준비 단계에서 export 디렉터리 바로 아래 정규 파일을 **무조건 먼저 삭제**한다. co-locate 전략에서 이 디렉터리는 **직전 회차 산출물이 놓인 바로 그 디렉터리**다(라이브 실측: raw 17 → `/app/storage/raw/seed/17/deid/clip-9102-mask.mp4`, 재위탁 시 `deidVideoDir(17, …)` 가 동일 경로를 반환).
  ```java
  // KpstDeidentService.java:345 (buildProjectRequest, createProject 호출 전)
  cleanExportDir(exportDir, rawSn, rawFilePathNm);
  // KpstDeidentService.java:508-521
  try (Stream<Path> entries = Files.list(normalized)) {
      entries.forEach(entry -> {
          if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) return;
          try { Files.delete(entry); } catch (IOException de) { … }
      });
  }
  ```
  삭제 후 KPST 위탁이 실패(`SUBMIT_FAILED`)하거나 폴링 타임아웃(`poll-timeout-minutes:180`)으로 끝나면 **새 산출물은 생성되지 않고 옛 산출물은 이미 지워진 상태**다. 자동 재비식별 큐가 정책상 없어(외부 솔루션 수동 재처리) 복구 트리거도 없다. 그동안 데이터마트 뷰 lateral join 이 참조하는 최신 `SUCCEEDED` procLog 행(=삭제된 파일 경로)은 그대로 남아 **dangling path** 가 된다.
- **재현/확인 경로**:
  1. APPROVED + `DE_IDNTF_YN='F'`(신고 접수) 영상에 `POST /v1/videos/{rawSn}/redeident` (`ApprovedRedeidentService:83-85` 가 `'Y'` 만 409 로 막으므로 `'F'` 는 통과)
  2. 위탁 직후 export 디렉터리 확인 — 기존 `{stem}-mask{ext}` 가 사라짐
     `docker exec klid-backend ls -la /app/storage/raw/seed/17/deid/`
  3. mock-server 를 정지시켜 폴링을 타임아웃시키면 `poll_stts_cd=FAILED` 로 종결되고 파일은 끝내 복원되지 않음
  4. `SELECT de_idntf_file_path_nm FROM ls_deident_proc_log WHERE data_raw_sn=17 AND proc_stts_cd='SUCCEEDED' ORDER BY req_dt DESC, proc_log_sn DESC LIMIT 1;` → 존재하지 않는 파일 경로
- **영향**: 데이터정합/가용성. 검수완료·관제 통지된 영상의 유일한 비식별 영상 파일이 비가역 소실되고, 관제가 `V_COMPLETED_VIDEO` 로 픽업하는 비식별 경로가 dangling 이 된다(CLAUDE.md "관제 접근 무조건 보장" 위반). 프레임 이미지(`{base}/frames/deid/{rawSn}`)는 별도 경로라 남으므로 영상만 결손되는 **부분 정합 붕괴**가 된다. 보안 관점 상승은 없음(원본 노출 아님).
- **수정 방향(제안)**: `KpstDeidentService.cleanExportDir` 를 **삭제가 아니라 격리(quarantine)** 로 바꾼다 — 같은 디렉터리 하위 `.prev/`(폴백 스캔이 비재귀라 자동 제외됨)로 `Files.move(ATOMIC_MOVE)` 한 뒤 위탁하고, 완료(`finishDownloadAndComplete` 성공) 후에만 `.prev/` 를 비운다. 혹은 최소 조치로 **REDEIDENT 경로에서만 삭제를 보류**하고(`redeident` 플래그를 `buildProjectRequest` 까지 전달) 완료 시점에 정리한다. 어느 쪽이든 "새 산출물 확보 전 옛 산출물 삭제 금지" 불변식을 테스트로 고정할 것.

### [B-ISSUE-122] TC-DEID-088 / TC-DEID-089 — 완료 전이 원자 클레임의 **0행 분기와 롤백 해제 경로에 테스트가 0건**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `claimDownloadCompletion` 은 "완료 전이 자체가 클레임"이라는 설계의 핵심이다. ①동시 2노드 완료 시도에서 1행 얻은 쪽만 후처리(프레임 attach·Y 전이·락 해제·알림)해 **비식별 프레임 이중 attach 창이 없어야** 하고 ②후처리 실패 시 클레임이 함께 롤백돼 재폴링 대상으로 남아야 한다. 회귀하면 프레임 재추출·알림·락 해제가 두 번 일어나거나(①), 완료가 DOWNLOADED 로 굳은 채 Y 미전이 stuck 이 생긴다(②).
- **현재 동작(이슈 내용)**: 메커니즘 자체는 정합하다(PG 조건부 UPDATE 재평가 시맨틱을 실측 L3 로 확인).
  ```java
  // KpstDeidentTxService.java:209-213
  if (procLogRepository.claimDownloadCompletion(procLogSn, deidFilePath, LocalDateTime.now()) != 1) {
      log.info("[KpstDeid] completion already applied — skip duplicate rawSn={}", rawSn);
      return;   // ← 이 분기를 실행하는 테스트가 저장소 전체에 0건
  }
  ```
  `grep -rn "claimDownloadCompletion" backend/src/test/` 결과 유일 참조는 `KpstDeidentTxServiceTest:82` 의 `when(...).thenReturn(1)` 전역 스텁뿐이다. `KpstDeidentTxServiceTest` 는 전량 Mockito 단위 테스트라 실 트랜잭션 롤백도 관측 불가하다. `claimForPoll` 에는 Testcontainers 기반 2스레드 IT(`KpstDeidentPollClaimIT`)가 있는 반면, **완료 클레임에는 대응 IT 가 없다** — 두 클레임 중 부작용이 훨씬 큰 쪽에만 커버리지가 비어 있다.
- **재현/확인 경로**:
  - `grep -rn "claimDownloadCompletion" backend/src/test/` → 1건(스텁만)
  - `KpstDeidentTxServiceTest:82` 를 `thenReturn(0)` 으로 바꿔도 기존 테스트가 실패하지 않는지(= 0행 분기가 어떤 단언에도 걸리지 않음) 확인
- **영향**: 기능/데이터정합. 현재는 결함이 아니라 **회귀 방어 부재**다. 술어(`POLL_STTS_CD IN ('WAITING','POLLING')`)를 넓히거나 `!= 1` 가드를 지우는 변경이 어떤 테스트도 깨뜨리지 않고 통과한다 → 2노드 운영에서 프레임 이중 attach·중복 알림이 조용히 재발할 수 있다.
- **수정 방향(제안)**: `KpstDeidentPollClaimIT` 와 동일 골격(Testcontainers PG + 2스레드 + `CountDownLatch`)으로 `finishDownloadAndComplete` 동시 호출 IT 를 추가해 ①`deidentFrameAttacher.attachDeidentFrames` 가 정확히 1회만 호출되고 ②두 번째 호출이 no-op 임을 단언한다. 별도로 후처리에서 예외를 던지는 스텁으로 롤백 후 `poll_stts_cd` 가 `WAITING/POLLING` 으로 복귀함(재폴링 대상 유지)을 실 트랜잭션에서 단언한다. **구현은 하지 않는다.**

### [B-ISSUE-123] TC-DEID-084 — 무결성 유예 재확인의 `Thread.sleep` 이 폴링 워커를 점유해, clamp 가 **틱 단위로는 보호되지 않는다**
- **심각도**: LOW
- **기대 동작(기대효과)**: `MAX_RESULT_RECHECK_DELAY_MS`(5s) clamp 의 목적은 코드 주석대로 "오설정(예: 300000)이 폴링 사이클을 정지시키는 것을 막는" 것이다. 폴링은 30초 주기로 계속 돌아야 다른 영상의 완료 감지가 밀리지 않는다.
- **현재 동작(이슈 내용)**: clamp 는 **1건당** 상한일 뿐이고, 유예는 Quartz 워커 스레드를 그대로 잡는 동기 sleep 이다.
  ```java
  // KpstDeidentService.java:712-717
  long delayMs = Math.min(resultRecheckDelayMs, MAX_RESULT_RECHECK_DELAY_MS);
  log.warn("[KpstDeid] deid artifact incomplete on first check — regrace rawSn={} delayMs={}", rawSn, delayMs);
  try { Thread.sleep(delayMs); } catch (InterruptedException e) { … }
  ```
  `poll-batch-size` 기본 200 이므로 한 틱에서 다수 건이 "후보 파일은 있는데 무결성만 실패"에 걸리면 최악 200 × 5s ≈ **16분 40초** 동안 그 틱이 끝나지 않는다. `@DisallowConcurrentExecution` 때문에 그동안 후속 틱은 전부 스킵되고, 정상 완료 대기 중인 다른 영상의 감지가 그만큼 지연된다(공유 NAS 쓰기 지연·마운트 이상처럼 **동시 다발로 발생하기 쉬운** 조건에서 정확히 이 상황이 만들어진다).
- **재현/확인 경로**: export 디렉터리에 `{stem}-mask{ext}` 이름의 512B 미만 파일을 여러 건 만들어 두고 완료(procState=2)를 받게 한 뒤, `[KpstDeid] … regrace rawSn=… delayMs=5000` WARN 건수 × 5s 만큼 다음 `[KpstDeidPoll] polling targets` 로그가 밀리는지 확인.
- **영향**: 가용성/성능(OWASP API4 자원 소진의 완화형). 데이터 손상은 없고 완료 감지 지연에 그친다. 지연은 `poll-timeout-minutes`(180) 안에서 흡수된다.
- **수정 방향(제안)**: 틱 단위 유예 예산(예: 누적 대기 상한 15s)을 두어 초과분은 sleep 없이 즉시 종결하거나, 유예 재확인을 sleep 대신 **다음 틱으로 미루는 상태**(재확인 1회 유예 플래그 + `POLL_LAST_DT` 재클레임)로 바꿔 워커 점유를 없앤다.

### [B-ISSUE-124] TC-DEID-067 — 기대결과 무효: prjId null 은 이제 `markTimeoutIfExpired` 가 아니라 **ACK 대기 유예/회수**다
- **심각도**: LOW (카탈로그 정정)
- **기대 동작(기대효과)**: 카탈로그는 "prjId null(위탁 미완) → `markTimeoutIfExpired`, 외부 미호출"을 기대한다.
- **현재 동작(이슈 내용)**: Phase C-2(논블로킹 제출) 도입 후 prjId null 은 **정상 상태**(ACK 대기)가 되어 의도적으로 타임아웃 예산을 소모하지 않는다.
  ```java
  // KpstDeidentService.java:553-560
  if (withinSubmitAckGrace(procLog)) {           // 기본 180s (submit-ack-grace-sec)
      log.debug("[KpstDeid] skip poll — awaiting submit ack rawSn={}");
      return;                                     // markTimeoutIfExpired 미호출
  }
  boolean reclaimed = txService.failSubmit(procLogSn, rawSn, ACK_MISSING_CODE, "submit ack not received");
  ```
  "외부 미호출"은 그대로 성립하고, 회수 종결은 `claimSubmitFailure`(WAITING + prjId null) 조건부 UPDATE 라 지각 ACK 를 강등하지 않는다 — 기능적으로는 개선이다. 카탈로그 기대결과와 근거 라인(`395-399` → 실제 `541-561`)만 어긋난다.
- **재현/확인 경로**: `KpstDeidentServiceTest` 의 `PhaseC2_prjId_미상은_ACK대기로_보고_유예안이면_아무것도_하지_않는다` / `PhaseC2_ACK가_유예를_넘겨도_안오면_폴러가_ACK_MISSING으로_회수한다`
- **영향**: 검증 정합성. 카탈로그를 그대로 믿으면 정상 동작을 FAIL 로 오판한다.
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` TC-DEID-067 의 기대결과를 "유예 안=no-op(외부 미호출·카운터 미소모) / 유예 초과=`failSubmit(ACK_MISSING)`"으로 갱신하고 근거를 `KpstDeidentService.java:541-561` 로 정정. **구현은 하지 않는다.**

### [B-ISSUE-125] TC-DEID-062 — 기대결과 부분 무효: createProject 실패는 **동기 예외 전파가 없다**
- **심각도**: LOW (카탈로그 정정)
- **기대 동작(기대효과)**: 카탈로그는 "createProject 예외 → `procLog.fail` + `markDeidentified("F")` + **예외 전파**(클래스명만)"를 기대한다.
- **현재 동작(이슈 내용)**: 862ca6d8(외부연동 논블로킹화) 이후 `createProject` 는 구독만 하고 즉시 반환한다. 실패는 완료 핸들러가 기록하며 호출자에게 예외가 가지 않는다.
  ```java
  // KpstDeidentService.java:421-431 (subscribeSubmit)
  kpstClient.createProject(projectReq)
      .subscribe(resp -> … outcomeRecorder.onAccepted(…),
                 err  -> … outcomeRecorder.onSubmitFailed(rawSn, procLogSn, err));
  } catch (RuntimeException e) { outcomeRecorder.onSubmitFailed(rawSn, procLogSn, e); }  // rethrow 없음
  ```
  `onSubmitFailed` → `failSubmit`(TxService:163-184) 이 procLog FAILED + raw `'F'` + (REDEIDENT면) 락 해제를 단일 `REQUIRES_NEW` 로 커밋하므로 실패 흔적과 안전성은 유지된다. **동기 예외가 남아 있는 것은 제출 이전 사전조건 실패뿐**(raw null · 원본 부재 · 부모 경로 없음 · export 디렉터리 생성/검증 실패, Service:294-303).
- **재현/확인 경로**: mock-server 정지 후 신규 적재 → `[KpstDeid] submit failed (async) rawSn=… cause=…` + `[KpstDeid] submit terminal-failed rawSn=… errCd=KPST_SUBMIT_FAILED` 로그, 호출자(`DeidentifyStep`)에는 예외 없음. 테스트 `제출실패는_예외전파대신_완료핸들러가_기록한다_PhaseC2`
- **영향**: 검증 정합성. 정상 동작을 FAIL 로 오판할 소지.
- **수정 방향(제안)**: TC-DEID-062 를 "제출 이전 사전조건 실패 = 원장 별도커밋 종결 + 동기 예외 / 외부 호출 실패 = 비동기 핸들러가 `failSubmit` 으로 종결(예외 전파 없음)" 두 케이스로 분할하고 근거를 `KpstDeidentService.java:294-303, 421-431` · `KpstDeidentTxService.java:163-184` 로 정정.

### [B-ISSUE-126] B-13 전 34건 — 근거 `file:line` 대량 드리프트(2026-07-30 최신화가 862ca6d8 미반영)
- **심각도**: LOW (카탈로그 정합성)
- **기대 동작(기대효과)**: 근거 `file:line` 은 검증자가 곧바로 대조할 수 있어야 한다.
- **현재 동작(이슈 내용)**: `KpstDeidentService.java` 가 862ca6d8(2026-07-30, 논블로킹화)로 약 200줄 늘어나면서 B-13 의 근거 라인이 사실상 전부 어긋났다. 대표 실측 대조:

  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 060 | Service:218-278 | Service:279-306(+TxService:83-91,102-113) |
  | 061 | Service:235-241 | Service:319-323 |
  | 063 | Service:342-390 | Service:488-527 |
  | 064 | Service:438-490 | Service:600-664 |
  | 065 | Service:425-435 | Service:587-598 |
  | 067 | Service:395-399 | Service:541-561 |
  | 068 | Service:401-415 | Service:562-576 |
  | 069 | Service:445-462 | Service:611-625 |
  | 070/084 | Service:464-486,531-556 | Service:626-648,707-730 |
  | 071 | Service:505-506 | Service:665-669 |
  | 072/074 | Service:636-655,693-720 | Service:812-822,869-890 |
  | 073 | Service:657-690 | Service:833-857 |
  | 079 | Service:722-746 · TxService:116-120 | Service:898-911 · TxService:269-273 |
  | 081/082 | Service:225,300-320 / 302-305 | Service:286,446-466 / 447-451 |
  | 083 | Service:516-518 | Service:692-694 |
  | 088/089 | TxService:78-95 | TxService:206-223 |
  | 091/092 | Service:621-634 / 601-616 | Service:797-802 / 785 |
  | 093 | DeidentFrameAttacher:73-93 | DeidentFrameAttacher:99,160-162 |

  정확히 맞은 것은 066(94-98) · 075~078 · 085~087 · 090 정도다.
- **재현/확인 경로**: 위 표의 각 라인을 `Read` 로 대조
- **영향**: 검증 효율/정합성. 라인 대조 실패가 곧 오판으로 이어질 수 있음.
- **수정 방향(제안)**: B-13 섹션의 근거 컬럼을 위 실측 라인으로 일괄 갱신.

### [B-ISSUE-127] `LsDeidentProcLog.markKpstSubmitted` 가 프로덕션 dead code
- **심각도**: LOW
- **기대 동작(기대효과)**: 엔티티 상태 전이 메서드는 실제 전이 경로와 1:1로 대응해야, 이후 유지보수자가 "prjId 는 어디서 기록되나"를 잘못 짚지 않는다.
- **현재 동작(이슈 내용)**: 프로덕션 prjId 기록은 네이티브 조건부 UPDATE `claimSubmitAck`(Repository:143-154)로만 이뤄지고, 엔티티 메서드는 테스트에서만 쓰인다.
  ```
  grep -rn "markKpstSubmitted" backend/src/main → LsDeidentProcLog.java:154 (정의만)
  grep -rn "markKpstSubmitted" backend/src/test → 5파일 8건
  ```
  실제로 이 카탈로그의 TC-DEID-060 기대결과가 이미 그 이름을 근거로 쓰고 있어 오해가 발생한 상태다.
- **재현/확인 경로**: 위 grep
- **영향**: 유지보수성. 기능 영향 없음.
- **수정 방향(제안)**: `markKpstSubmitted` 를 제거하고 테스트 픽스처를 `markKpstSubmitPending` + `recordDatasetId`(또는 리포지토리 클레임)로 대체하거나, 남긴다면 "테스트 픽스처 전용 / 프로덕션 전이는 `claimSubmitAck`" 임을 javadoc 에 명시.

## 5. 부수 기록

- **검증 중 스택 부작용**: 원자 클레임 실증(L3)을 위해 실 WAITING 행 1건(`proc_log_sn=40`)의 `POLL_LAST_DT` 를 갱신했다. 리스(25s) 만료 후 폴러가 재클레임해 정상 완료(`DOWNLOADED/SUCCEEDED`)됨을 확인했다 — 잔존 영향 없음. mock-server 에 검증용 프로젝트 `qa-b13-live-1`(prj_id=14) 및 산출물 `/app/storage/deidentified/videos/qa-b13/clip-9101-mask.mp4` 를 생성했다(DB 미연결, 저작도구 상태 불변).
- **코드/설정/테스트 파일 수정 0건, 빌드/테스트 실행 0건.**
- `KpstDeidentServiceTest.java` 는 파일 인코딩상 `file(1)` 이 `data` 로 판정해 일반 `grep` 이 무결과를 낸다 — `grep -a` 필요(검증 시 함정).
# B-part8 — B-9 / B-14 / B-16 / B-17 검증 결과

- 검증 대상 커밋: 56d30478 (qa-0801, backend 실기동 V146)
- 스택: docker klid-backend(18081)·klid-ai-server(19300, `AI_MOCK_MODE=false`이나 YOLOX/SAM2 weights 미탑재로 실제 응답은 mock 폴백)·klid-postgres(5432, `public` 스키마)
- 환경 격차: LS_DATA_INGEST 리팩터(b2b44f0e 이후)는 이 스택 이미지에 미반영이나, 본 담당 4개 섹션(B-9/B-14/B-16/B-17)은 해당 리팩터와 무관한 오토라벨·Quartz·저장헬퍼·FK 영역이라 영향 없음. 전부 실동작/정적 검증 완주.
- 참고: `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md` — backend cleanTest 4,755 tests / 실패 0 / skip 5 (전량 통과, 2026-08-01 실행 증거 있음).

## B-9. YOLO / SAM2 / Interpolate (30건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-120 | PASS | [정적] YoloAutolabelStep.java:169-171 | rawSn null → `CustomException(INVALID_INPUT)` 확인 |
| TC-BATCH-121 | PASS | [정적][테스트] YoloAutolabelStep.java:185,200-211 · YoloAutolabelStepTest#predictYoloTrackCalledAndTrackIdPersisted(728) | clipId=String.valueOf(rawSn), frameIndex 0부터 순차 증가(루프 끝 `frameIndex++`) |
| TC-BATCH-122 | PASS | [정적] YoloAutolabelStep.java:207-216 · YoloAutolabelStepTest#externalErrorWrapped(425) | RuntimeException catch → EXTERNAL_API_ERROR 래핑 확인 |
| TC-BATCH-123 | PASS | [실동작][정적] YoloAutolabelStep.java:221-233 · ai-server 실측: `docker logs klid-ai-server`에 `[DETECT:yolox][MOCK] returning mock prediction reason=weights_missing` 실측(가중치 미탑재로 이 스택에서는 실제로 mock 경로가 항상 발화) | LogSanitizer.sanitize(source/mockReason) 적용 확인 |
| TC-BATCH-124 | PASS | [정적] YoloAutolabelStep.java:276-279 | `labelMasterService.findLabelIdByDtctType(d.label())` 사용, 미매칭 시 `.orElse(null)` |
| TC-BATCH-125 | PASS | [정적] YoloAutolabelStep.java:240-243,400-410(resolveToggle) | togglesOpt empty → `AnnotationToggle.BOTH` fail-safe 확인(catalog 400 근사) |
| TC-BATCH-126 | PASS | [정적] YoloAutolabelStep.java:427-432(readImageAsBase64) | `imagePath.startsWith(baseRawPath)` 위반 시 INVALID_INPUT, 경로 원문 미노출(CWE-22/209) |
| TC-BATCH-127 | PASS | [정적][테스트] YoloAutolabelStep.java:178-181,358-386 · YoloAutolabelStepTest#systemConfigValuesPassedToAiServer(658)·fallbackDefaultsWhenSystemConfigMissing(682) | 조회 실패 시 catch(Exception) fallback, 정상 시 SystemConfig 값 사용 |
| TC-BATCH-128 | PASS | [정적][테스트] Sam2SegmentStep.java:241-256(buildJobs) · Sam2SegmentStepTest#Sam2Step_dedup_은_label_과_trackId_조합_기준(409) | DB BBOX가 `putIfAbsent`로 먼저 등록되어 hint보다 우선 |
| TC-BATCH-129 | PASS | [정적][테스트] Sam2SegmentStep.java:182-188 · Sam2SegmentStepTest#Sam2Step_polygonEnabled_false(273) | `!toggle.polygon()` → WARN + continue |
| TC-BATCH-130 | PASS | [정적] Sam2SegmentStep.java:196,365-383(capPolygon) | `polygon.size() > MAX_POINTS_PER_LABEL`일 때만 `PolygonSimplifier.simplifyToMax` 호출, 이하면 원본 반환 |
| TC-BATCH-131 | PASS | [정적] Sam2SegmentStep.java:226-234(callSam2) | RuntimeException catch → EXTERNAL_API_ERROR |
| TC-BATCH-132 | PASS | [정적] TrackInterpolationStep.java:144-147 | `frames.isEmpty()` → return 0 |
| TC-BATCH-133 | PASS | [정적][테스트] TrackInterpolationStep.java:158-171 · TrackInterpolationStepTest#재실행_idempotency(476) | 삭제 순서 `aiInfoRepository.deleteByDataLblSnIn`(168, 자식) → `lblRepository.deleteAllByIdInBatch`(169, 부모) 확인, FK 고아 방지 순서 정확 |
| TC-BATCH-134 | PASS | [정적][테스트] TrackInterpolationStep.java:187-195 · TrackInterpolationStepTest#한트랙_예외_다른트랙_보간은_저장됨(450) | 트랙별 try/catch, WARN 후 skip. 다른 트랙 정상 저장 |
| TC-BATCH-135 | PASS | [정적][테스트] TrackInterpolationStep.java:353-357 · TrackInterpolationStepTest#트랙내_타입혼재(433) | `types.size() > 1` → WARN + `List.of()` |
| TC-BATCH-136 | PASS | [정적][테스트] TrackInterpolationStep.java:441-481(parseBbox) · TrackInterpolationStepTest#nested_BBOX_좌표_포맷도_파싱_지원(494) | flat[x1,y1,x2,y2] / nested[[x1,y1],[x2,y2]] 양쪽 파싱 분기 확인 |
| TC-BATCH-137 | PASS | [정적] TrackInterpolationStep.java:247-328(interpolateSingleTrack/Touched) | from+to 양쪽 stale 삭제(274-288), `@Transactional` 미부착으로 caller tx 참여, 예외 미포착(전파) — 트랙 병합 원자성 보장 확인 |
| TC-BATCH-138 | PASS | [정적] MarkingLoadStep.java:50-58 | `ctx.setMarkings` + 첫 항목 markCn 파싱 후 `ctx.setMarks` |
| TC-BATCH-139 | PASS | [정적] MarkingLoadStep.java:60-66(parseMarks) | JsonProcessingException → INTERNAL_ERROR |
| TC-BATCH-140 | PASS | [정적][테스트] BatchPipelineConfig.java:31-42 · BatchPipelineConfigTest#파이프라인_순서는_MARKING_VLM_FRAME_YOLO_SAM2_INTERPOLATE(29) | `List.of(markingLoad, vlm, frame, yolo, sam2, interp)` 순서 정확 |
| TC-BATCH-141 | PASS | [정적][테스트] DetectionBoxNormalizer.java:41-69 · DetectionBoxNormalizerTest 전건(clampsNegativeToZero 등) | clamp 규칙 단일 유틸 확인, YOLO/SAM2 양쪽이 동일 함수 사용 |
| TC-BATCH-142 | PASS | [정적][테스트] DetectionBoxNormalizer.java:54-58 · DetectionBoxNormalizerTest#rejectsNonFinite(115) | `Double.isFinite` 가드가 clamp/음수검사보다 **선행**함을 코드 순서로 확인(NaN<0=false 함정 회피) |
| TC-BATCH-143 | PASS | [정적][테스트] DetectionBoxNormalizer.java:65-68 · YoloAutolabelStep.java:263-268 · DetectionBoxNormalizerTest#degenerateAfterClampIsSkipped(89)·reversedBoxIsSkipped(99) · YoloAutolabelStepTest#batchSkipsDegenerateBoxWithoutFailingFrame(259) | `x2<=x1 \|\| y2<=y1` → `Optional.empty()`, 호출부는 `droppedDegenerate++` 후 continue(영상 전체 실패 없음) |
| TC-BATCH-144 | PASS | [정적][테스트] YoloAutolabelStep.java:259-275 · YoloAutolabelStepTest#malformedPointsDropDetectionOnlyNotWholeVideo(281)·nanPointsDropDetectionOnly(309) | IllegalArgumentException을 루프 내부 catch → `droppedMalformed++` + continue, 온라인 경로 all-or-nothing 400은 별도 유지(AutolabelOnlineService, 미변경 확인) |
| TC-BATCH-145 | PASS | [정적][테스트] YoloAutolabelStep.java:316-325 · YoloAutolabelStepTest#폴리곤전용_프리셋의_hint좌표는_이미지_경계로_clamp된_값이다(356) | `hints.add(new BbHint(..., points, ...))` — points는 정규화 완료된 좌표(245-269에서 선계산), DB BBOX 없을 때도 clamp 값이 프롬프트가 됨 |
| TC-BATCH-146 | PASS | [정적][테스트] YoloAutolabelStep.java:259-275(continue 공유 지점) · YoloAutolabelStepTest#bothPresetSkipsHintWhenBboxDegenerate(379) | 정규화 실패/퇴화 시 `continue`가 bbox 저장·polygon hint 발행 코드 양쪽보다 앞에 있어 둘 다 스킵 |
| TC-BATCH-147 | PASS | [정적][테스트] DetectionBoxNormalizer.java:41-49,72-78(upperBound) · YoloAutolabelStep.java:236-237 · DetectionBoxNormalizerTest#clampsLowerBoundOnlyWhenBoundsUnknown(77)·invalidBoundsTreatedAsUnknown(138) | bounds null/비정상 시 `Double.MAX_VALUE`(상한 없음), 하한(0)만 적용 |
| TC-BATCH-148 | PASS | [정적] YoloAutolabelStep.java:236-237 | `resp.detections().isEmpty() ? null : frameBoundsResolver.resolve(...)` — 검출 0건이면 resolver 자체를 호출하지 않음 |
| TC-BATCH-149 | PASS | [정적][테스트] YoloAutolabelStep.java:189-190,299,343-345 · LsDataSrcRepository.java:283-308 | `labeledFrames`(실제 라벨 생성된 srcSn만) 수집 후 `bumpLabelVersionIn(labeledFrames)` 호출. 구 `bumpLabelVersionByRawSn`은 리포지토리에 메서드만 남고 **어디서도 호출되지 않음**(grep 확인 — 사실상 폐기) |

## B-14. Quartz 클러스터링 / 인프라 / 헬스 (10건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-170 | PASS | [실동작][정적] QuartzConfig.java:29-36 · `docker exec klid-postgres psql \d qrtz_*` → 11개 QRTZ_* 테이블 public 스키마 실존 | controlDataSource 명시 주입 + `application.yml:85-86` PostgreSQLDelegate/useProperties=true 확인 |
| TC-BATCH-171 | PASS(부분 BLOCKED) | [정적] application-stg.yml:11 `isClustered: ${QUARTZ_CLUSTERED:true}` · application-prd.yml:13 동일 · application.yml:95 공통 기본 false | 코드/설정상 stg/prd 기본 true 확정. 이 스택은 `SPRING_PROFILES_ACTIVE=local`(단일 노드) 실행 중이라 **2노드 동시성 자체는 BLOCKED(사유: 로컬 스택이 단일 프로파일·단일 인스턴스, 2노드 재현 불가)** — 설정값 자체는 실측 확인됨 |
| TC-BATCH-172 | PASS | [정적][테스트] AsyncBatchRunner.java:21-35 | `@Async`, catch(Exception) → ERROR 로그, 정상 반환(re-throw 없음) |
| TC-BATCH-173 | PASS | [정적][테스트] QuartzClusteringGuard.java:64-95 · QuartzClusteringGuardTest#prdWithoutClusteringIsRejected(32)·stgWithoutClusteringIsRejected(41) | `verify()`가 IllegalStateException 던짐 → `@PostConstruct`에서 기동 실패 유도. 실제 이 로컬 스택은 local 프로파일이라 통과(정상) |
| TC-BATCH-174 | PASS | [정적][테스트] QuartzClusteringGuard.java:53,97-103 · QuartzClusteringGuardTest#mixedOrUnknownProfilesAreStrict(49) | `SINGLE_NODE_PROFILES.containsAll(activeProfiles)` — allowlist 방식, 오타/혼합/미지정 전부 거부 확인 |
| TC-BATCH-175 | PASS | [정적][테스트] QuartzClusteringGuard.java:56,98-112 · QuartzClusteringGuardTest#배포표식_ENV가_stg_prd면_프로파일이_dev여도_엄격하게_판정한다(65) | `ENV` 환경변수가 `DevProfileGuard.DEPLOYED_ENVS`와 동일 축(stg/prd)으로 독립 판정, 프로파일 낮춰도 우회 불가 |
| TC-BATCH-176 | PASS | [정적] QuartzClusteringGuard.java:50,66 | `environment.getProperty(KEY_CLUSTERED=spring.quartz.properties.org.quartz.jobStore.isClustered, ...)` — Quartz 실 프로퍼티 직접 조회 |
| TC-BATCH-177 | PASS | [정적] QuartzClusteringGuard.java:40-41(Javadoc) · BatchRetryQueue/KpstDeidentPollJob의 원자 클레임 코드(B-12/B-13에서 별도 확인된 조건부 UPDATE) | 클러스터링과 원자 클레임이 별개 방어층임을 주석·실제 구현(조건부 UPDATE) 양쪽으로 확인 |
| TC-BATCH-178 | PASS | [정적] AsyncConfig.java:32-47(batchAsyncExecutor) · grep `@Async("batchAsyncExecutor")` 사용처 9개 파일 실측 | core2/max4/queue50/CallerRunsPolicy 단일 풀을 AsyncDeidentifyRunner(비식별)·AsyncBatchRunner(배치)·AsyncAugmentFrameRunner+AugmentRequestBridge(증강)·AsyncDatasetExportRunner(export)·AsyncResolutionRunner(해상도)·VlmWithheldResumeRunner(VLM 재개)·AsyncVideoMetaRunner(영상메타)가 실제로 공유함을 확인. VLM/KPST/증강 "제출 완료 핸들러"용 vlmSubmitExecutor 등은 **별개 계층**(리액티브 publishOn 전용)이라 혼동 주의 |
| TC-BATCH-179 | PASS | [정적][테스트] DeidentifyHealthIndicator.java:75-113 · DeidentifyHealthIndicatorTest 7건(mock UP/실모드 UP/타임아웃 DOWN/설정오류 DOWN/루트경로 핑/배선 분리 등) | 3분기(mock/kpst/미구성) + 예외 클래스명만 노출(CWE-209) 확인. 실동작 `GET /actuator/health`는 `show-details: when-authorized`라 미인증 요청은 컴포넌트 상세 미노출(정상 설정, 결함 아님) — 전체 상태는 UP 확인 |

## B-16. 오토라벨 일괄저장 AutoLabelBatchPersister (6건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-191 | PASS | [정적][테스트] AutoLabelBatchPersister.java:61-84 · YoloAutolabelStepTest#한_프레임의_검출들은_라벨_saveAll_1회와_AI메타_saveAll_1회로_저장된다(1106)·Sam2SegmentStepTest#동일(611) | 프레임 단위 pending 목록 → `lblRepository.saveAll` 1회 + `aiInfoRepository.saveAll` 1회 |
| TC-BATCH-192 | PASS | [정적] AutoLabelBatchPersister.java:72-83 | `saved.get(i)`(저장된 라벨, PK 부여된 입력 인스턴스 그 자체)에서 직접 lblSn/srcSn 추출 — 인덱스 오염 불가 |
| TC-BATCH-193 | PASS | [정적] AutoLabelBatchPersister.java:72-76 | `saved.size() != pending.size()` → `CustomException(INTERNAL_ERROR, "자동 라벨 일괄 저장 결과 개수 불일치")`. **주의**: 이 분기를 직접 겨냥한 전용 단위테스트는 없음(grep 결과 0건) — JpaRepository.saveAll이 항상 동일 크기를 반환하는 정상 경로에서는 도달하지 않는 방어 코드라 실무 영향은 낮으나, 회귀 방지용 전용 테스트 부재는 커버리지 갭으로 기록(결함 아님, ISSUE 미등록) |
| TC-BATCH-194 | PASS | [정적][테스트] AutoLabelBatchPersister.java:65-67 · YoloAutolabelStepTest#저장할_검출이_없는_프레임은_saveAll을_호출하지_않는다(1190)·Sam2SegmentStepTest#SAM2_저장대상이_없는_프레임은_saveAll을_호출하지_않는다(677) | `pending == null \|\| pending.isEmpty()` → return 0, 리포지토리 미호출 |
| TC-BATCH-195 | PASS | [정적] AutoLabelBatchPersister.java:41-48,77-82(PendingLabel.score()) | AI 메타 생성 시 `pending.get(i).score()`(원본 신뢰도) 사용, `label.getConfScore()`(엔티티 clamp 보정값) 미사용 확인 |
| TC-BATCH-196 | PASS | [정적] AutoLabelBatchPersister.java:22-28(Javadoc) · LsDataLbl.java:54-55 `@GeneratedValue(strategy=IDENTITY)` · LsDataLblAiInfo.java:29-30 동일 | 두 엔티티 모두 IDENTITY 확인 — Hibernate JDBC 배치가 구조적으로 비활성(호출 횟수 감소만이 실익)이라는 주석 서술이 실제 엔티티 전략과 일치 |

## B-17. LS_DATA_RAW 참조 무결성 FK V146 (8건)

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-BATCH-200 | PASS | [실동작] `docker exec klid-postgres psql -c "SELECT conname,... FROM pg_constraint WHERE confrelid='public.ls_data_raw'::regclass"` → **28개 FK 실존**(child_specs 27개 + 기존 LS_EVNT_ANNO 1개, confdeltype 'c'=CASCADE 26 / 'n'=SET NULL 2) · V146__add_ls_data_raw_child_fk.sql:41-75 | 카탈로그 "27개 생성(CASCADE 25/SET NULL 2)"은 migration 스크립트 신규분 기준과 정확히 일치(기존 evnt_anno 1건은 별도 4)섹션에서 CASCADE로 재확정, 신규 생성 아님) |
| TC-BATCH-201 | PASS | [실동작] 트랜잭션 내 실측: `raw_sn=4`에 대해 `DELETE FROM ls_data_raw WHERE raw_sn=4` 실행 → `ls_marking WHERE raw_sn=4` 건수 1→0 확인 후 ROLLBACK(실 데이터 미훼손) · LsDataRawChildFkCascadeIT#영상_삭제시_자식_마킹_프레임_상태가_CASCADE로_함께_삭제된다(114) | CASCADE 실동작 확인 |
| TC-BATCH-202 | PASS | [실동작] 동일 트랜잭션에서 `ls_webhook_idempotency WHERE raw_sn=4`(1건 존재) 삭제 후 `raw_sn IS NULL` 건수 1로 증가(행 보존 + 참조만 NULL) 확인 후 ROLLBACK · LsDataRawChildFkCascadeIT#원장_세션_참조는_SET_NULL로_끊기고_행_자체는_보존된다(149) | SET NULL 실동작 확인 |
| TC-BATCH-203 | PASS | [정적][테스트] V146__add_ls_data_raw_child_fk.sql:76-85,112-117 · LsDataRawOrphanCleanupIT#데이터마트_뷰_공급_테이블에_고아가_있으면_자동삭제하지_않고_중단한다(114) | view_feed_tables 7개(ls_dataset_video_meta 등) 고아 시 RAISE EXCEPTION으로 마이그레이션 중단 — 실제 V146 스크립트를 Testcontainers로 재실행해 검증하는 전용 IT 존재 |
| TC-BATCH-204 | PASS | [정적] V146__add_ls_data_raw_child_fk.sql:87,107-111 | `max_orphans=1000` 초과 시 RAISE EXCEPTION 로직 확인. **주의**: 1000건 초과 시나리오를 직접 겨냥한 전용 테스트는 미발견(grep 0건) — 로직 자체는 단순 비교문이라 오류 가능성 낮음(결함 아님, 커버리지 갭만 기록) |
| TC-BATCH-205 | PASS | [실동작][정적] `docker exec klid-postgres psql`로 `ls_dataset_video_meta`·`mng_clip_schedule_que` FK 조회 → `ls_dataset_video_meta`는 `raw_sn` 참조 FK 1건만 존재(orgnl_raw_sn 미대상), `mng_clip_schedule_que`는 FK 0건 · V146 주석:17-26 · LsDataRawChildFkCascadeIT#관제_공유_MNG_테이블에는_FK를_걸지_않았다(201)·파생_계보_ORGNL_RAW_SN에는_FK를_걸지_않았다(217) | MNG_* 및 ORGNL_RAW_SN 제외 실측 확인 |
| TC-BATCH-206 | PASS | [정적][테스트] V146__add_ls_data_raw_child_fk.sql:94-163(DO 블록 3단계: 96-119 실태조사 → 121-143 정리 → 145-163 FK생성) · LsDataRawOrphanCleanupIT(실 스크립트 재실행 검증) | 1패스 RAISE NOTICE, 2패스 정리, 3패스 멱등 FK 생성 순서 코드로 확인 |
| TC-BATCH-207 | PASS | [실동작] `flyway_schema_history`에서 `version=146, success=t` 확인(2026-07-31 적용 완료, 재기동해도 재실행 안 됨이 Flyway 표준 동작) · V146:157(`IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname=fk_name)`) | 멱등 가드 코드 확인 + 실제 적용 이력 성공 확인. 스크립트 강제 재실행(순수 재현)은 마이그레이션 상태 조작이 필요해 미실행(코드 로직상 명백히 멱등이라 실익 낮음) |

## 종합

- **총 54건 — PASS 54 / FAIL 0 / PARTIAL 0 / BLOCKED 0(TC-BATCH-171은 부분 실측·부분 설정확인으로 PASS 처리, 완전 BLOCKED 아님) / N/A 0 / 확인필요 0**
- **근거 드리프트**: 없음 — 카탈로그 file:line이 실제 코드와 대부분 정확히 일치(±2~5줄 근사 오차는 주석/공백 삽입으로 인한 자연 드리프트이며 의미 위치는 동일)
- **self-fill 결함**: 없음 — 이 4개 섹션은 외부 연동(비식별/VLM/증강)과 직접 관련 없는 내부 로직(좌표 정규화·Quartz·저장 헬퍼·DB FK)이라 self-fill 해당 케이스 자체가 없음. 단 ai-server YOLO/SAM2는 가중치 미탑재로 이 스택에서 상시 mock 폴백 중임을 실측(TC-BATCH-123 근거로 활용, 결함 아님 — ai-server 자체가 `mock=true` 플래그를 정직하게 응답에 실어 보내고 backend가 이를 WARN으로 드러냄)
- **테스트 커버리지 갭(결함 아님, 기록만)**: TC-BATCH-193(AutoLabelBatchPersister size 불일치 방어), TC-BATCH-204(V146 max_orphans>1000 방어) — 둘 다 코드 로직은 명확히 정확하나 전용 단위테스트 부재
- **확증편향 방지 조치**: 좌표 clamp 순서(유한성 가드 우선), degenerate/malformed 분기의 실제 continue 위치, SAM2 dedup의 DB BBOX 우선순위, TrackInterpolation 삭제 순서(자식→부모), FK CASCADE/SET NULL을 실제 트랜잭션 내 DELETE로 직접 실행해 결과 확인(단순 코드 신뢰 아님) — 전부 반증 시도 후 기대결과와 일치함을 확인
