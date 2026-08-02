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
