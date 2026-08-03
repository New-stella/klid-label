# B 클러스터 part1 — B-2 선두 비식별 브릿지+러너 / B-3 DeidentifyStep / B-4 BatchOrchestrator 상태 전이

- 검증일: 2026-08-03 (3차)
- 대상: `docs/test-cases/B-batch-deidentify.md` **B-2**(TC-DEID-001~008, 8건) + **B-3**(TC-DEID-010~025, 16건) + **B-4**(TC-BATCH-030~045, 16건) = **40건**
- 검증 워크트리: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0803` (branch `qa-0803`)
- 스택: `_raw/stack-bringup.md` 기준 재빌드 완료본(Flyway V163). `DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400` — **비식별은 mock-server(KPST 대역) 실왕복 경로**
- 코드/설정 파일 **일절 수정 없음**. 카탈로그(`B-batch-deidentify.md`) 담당 라인범위(50~104행) 정정 8건만 수행 — §"카탈로그 정정" 참조

## 0. 이번 회차에 실제로 구동한 시나리오 (실동작 근거의 원천)

코드/설정 변경 없이 **신규 영상 3건**을 관제 인입 → 선두 비식별 → 마킹 → 배치까지 실제로 태웠다.
(`rawSn=101` 은 다른 클러스터 공용 데이터라 **읽지도 바꾸지도 않았다**.)

| 검체 | vms_clip_id | 원본 | 용도 |
|---|---|---|---|
| **rawSn=102** | `QA3RD-B2-DEID-001` | `/app/storage/raw/seed/clip-9102.mp4` (정상 mp4) | B-2 정상 흐름 · B-4 진입 가드 매트릭스 |
| **rawSn=108** | `QA3RD-B4-RACE-001` | `/app/storage/raw/seed/clip-9103.mp4` (정상 mp4) | B-4 동시성 반증 · 작업상태 row 부재 |
| **rawSn=114** | `QA3RD-B3-BADSRC-001` | `qa3-badclip.mp4` (11바이트 텍스트, 비-비디오) | 비식별 fail-closed 반증 |

수행 방법: `LS_DATA_INGEST` INSERT(관제가 하는 행위 대행) → `POST /v1/dev/batch/scan` → 이후 전 구간은 백엔드가 자동.
토큰: `POST /v1/dev/tokens` (REVIEWER, userNo=1001 시드 계정).

### 0-1. 인입 → 선두 비식별 실왕복 (backend 로그 원문)

```
00:25:14.489 [http-nio-8080-exec-5] TrainingVideoIngestTx  - [TrainingIngest] ingested rcptnSn=2 rawSn=102
00:25:14.492 [http-nio-8080-exec-5] IngestDeidentifyBridge - [IngestDeidentifyBridge] video ingested rawSn=102 — triggering deidentify
00:25:14.492 [batch-async-1]        AsyncDeidentifyRunner  - [AsyncDeidentifyRunner] starting deidentify rawSn=102
00:25:14.496 [batch-async-1]        AsyncDeidentifyRunner  - [AsyncDeidentifyRunner] deidentify submitted (deferred) rawSn=102 — MARKING_READY 는 폴링 완료 시 전이
00:25:14.500 [kpst-submit-1]        KpstDeidentTxService   - [KpstDeid] submitted rawSn=102 prjId=2
00:25:50.117 [Scheduler_Worker-2]   KpstDeidentTxService   - [KpstDeid] completed rawSn=102
```

mock-server(:9400) 인바운드:
```
15:25:14,498 [MOCK][KPST] project created prj_id=2 name=raw102 creator=authoring
INFO: 172.20.0.5:36508 - "POST /project HTTP/1.1" 200 OK
15:25:14,645 [MOCK][KPST] watermark burned — 'MOCK 비식별 완료' 우측하단, 길이·무결성 검증 통과 file=clip-9102-mask.mp4
INFO: 172.20.0.5:36514 - "GET /retrieve_progress HTTP/1.1" 200 OK
```

DB 상태 전이(실측 폴링):
```
t+0s   raw_sn=102  data_stts_cd=PENDING        de_ident_yn=N     ← 제출 직후, 미전이
t+35s  raw_sn=102  data_stts_cd=MARKING_READY  de_ident_yn=Y     ← 폴링 완료가 단일 전이 지점
```

### 0-2. self-fill 반증 (핵심 관심사)

산출물이 **우리가 만든 것이 아니라 외부(mock KPST)가 만든 것**임을 파일 실측으로 확인:

```
$ docker exec klid-backend md5sum /app/storage/raw/seed/clip-9102.mp4 /app/storage/raw/seed/102/deid/*.mp4
ce714a1d4512a650720e18e2391fbadd  /app/storage/raw/seed/clip-9102.mp4          (20,590 bytes)
c786236386a7e27df86ca56cd1f84d35  /app/storage/raw/seed/102/deid/clip-9102-mask.mp4 (50,854 bytes)
```

- 파일명이 mock 자체복사 상수 `deidentified.mp4` 가 **아니라** KPST 규약 `{stem}-mask{ext}` → mock-mode 자체복사 경로를 타지 않았음이 증명된다.
- 해시·크기가 원본과 다름(워터마크 재인코딩) → 원본 복사(self-fill) 아님.
- `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/102/deid/clip-9102-mask.mp4` — 조합·추측이 아니라 응답값 기록(VERIFY-PROMPT §11 함정 회피 확인).

**판정: self-fill 결함 0건.**

### 0-3. fail-closed 반증 (rawSn=114, 비-비디오 원본)

```
$ psql -c "SELECT raw_sn, data_stts_cd, de_ident_yn FROM ls_data_raw WHERE raw_sn=114"
 114 | PENDING | F
$ psql -c "SELECT proc_stts_cd, err_cd, err_msg_cn FROM ls_deident_proc_log WHERE data_raw_sn=114"
 FAILED | DEIDENT_INCOMPLETE | deid file invalid
$ psql -c "SELECT * FROM ls_bat_rty_wtng WHERE raw_sn=114"   → 0 rows
```

- 산출물이 유효한 비디오가 아니면 **`'Y'` 위장 없이 `'F'`** 로 마감. `DATA_STTS_CD` 는 `PENDING` 유지 → **MARKING_READY 로 절대 전이하지 않음**(마킹 조기 진입 차단).
- **재시도 큐 행이 생성되지 않음** — TC-DEID-006 의 "`BatchRetryQueue` 의존 자체 없음" 이 실동작으로 확인됨(설계 결정 3).

---

## 1. B-2. 선두 비식별 브릿지 + 러너 (8건)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-001 | PASS | [실동작] 적재 커밋(`ingested rawSn=102` @00:25:14.489) **직후** 같은 http 스레드에서 `[IngestDeidentifyBridge] video ingested rawSn=102 — triggering deidentify`(@.492) → `[batch-async-1] AsyncDeidentifyRunner starting`(@.492). rawSn=102/108/114 3검체 모두 동일 순서 재현. 근거 `IngestDeidentifyBridge.java:26-31`(카탈로그 26-30, +1 드리프트 정정) |
| TC-DEID-002 | PASS | [정적] `IngestDeidentifyBridge.java:26` `@TransactionalEventListener(phase = AFTER_COMMIT)` — `fallbackExecution` 미지정 = 기본 `false`. 롤백 시 AFTER_COMMIT 동기화 콜백 자체가 호출되지 않는다(Spring 계약). ⚠ **실동작 반증 미수행** — 적재 tx 를 publish 이후 시점에 롤백시킬 진입점이 코드에 없어 재현 불가하고, 회귀 테스트도 부재(`IngestDeidentifyBridgeTest` 는 위임 1건뿐) → **B-ISSUE-05** 로 기록 |
| TC-DEID-003 | PASS | [정적] `AsyncDeidentifyRunner.java:79-83` `loadRaw(...).orElse(null)` → null 이면 WARN `raw not found rawSn={} — skip` + `return`(파이프라인 진입 전). 테스트 `AsyncDeidentifyRunnerTest:132 raw_없으면_무처리` (baseline 실패 0건) |
| TC-DEID-004 | PASS | [정적] `AsyncDeidentifyRunner.java:92-94` `if (ctx.isDeidentCompleted()) batchTransitionService.markRawDataMarkingReady(rawSn)`. ⚠ 이 환경은 `DEIDENTIFY_MOCK_MODE=false`(의도적 — self-fill 우회 금지 정책)라 mock 동기완료 분기를 라이브로 태울 수 없음. 테스트 `AsyncDeidentifyRunnerTest:71 mock_모드_비식별_동기완료시_run직후_MARKING_READY로_전이` + IT `DeidentifyStepExecutePersistenceIntegrationTest:67` 로 대체 |
| TC-DEID-005 | PASS | [실동작] rawSn=102/108/114 전건에서 제출 직후 `data_stts_cd=PENDING / de_ident_yn=N` 유지(로그 `deidentify submitted (deferred) … MARKING_READY 는 폴링 완료 시 전이`). 폴링 완료(`[KpstDeid] completed rawSn=102`) 이후에만 `MARKING_READY/Y` 로 전이 → **완료 폴링이 단일 전이 지점**임이 실증. 반증: 실패 검체 114 는 폴링이 실패로 끝나 `PENDING/F` 에 머무름(전이 없음) |
| TC-DEID-006 | PASS | [실동작+정적] ①재시도 큐 미사용 — 비식별 실패한 rawSn=114 에 `ls_bat_rty_wtng` 행 0건(실측). 생성자(`AsyncDeidentifyRunner.java:67-74`)가 pipeline/transitionService/videoRepository 3개만 받아 `BatchRetryQueue` 를 **구조적으로** 주입받지 않음. ②예외 삼킴 — `:99-106` catch(RuntimeException) → WARN(`cause=` 예외 클래스명만, 원문·경로 미노출). 테스트 `AsyncDeidentifyRunnerTest:119`. ⚠ Phase C-2 이후 이 catch 에 도달하는 실패는 **제출 이전**뿐(제출 이후는 `KpstSubmitOutcomeRecorder`/폴링이 별도 REQUIRES_NEW 로 `'F'` 기록) — 라이브 114 도 폴링 경로로 종결돼 catch 는 미도달. 카탈로그에 이 범위 축소를 명시(정정) |
| TC-DEID-007 | PASS | [정적] `AsyncDeidentifyRunner.java:112-117` `if (rawSn == null) return Optional.empty()`. ⚠ 부수 발견: 이 메서드의 `@Transactional(REQUIRES_NEW, readOnly)` 는 **`protected` + 자기호출(:79)** 이라 무효(1차 B-ISSUE-05 미해소) → **B-ISSUE-02** 로 이월 |
| TC-DEID-008 | PASS | [실동작+정적] `BatchPipelineConfig.java:50-52` `new BatchPipeline(List.of(deid))` — 정확 일치. 실동작 교차확인: 적재~비식별 구간 로그에 `MarkingLoadStep`/`VlmTimeseriesStep`/`FfmpegFrameExtractor` 가 **한 줄도 없고** 오직 `AsyncDeidentifyRunner`→KPST submit 만 발생. 테스트 `BatchPipelineConfigTest:59 선두_비식별_파이프라인은_DEIDENTIFY_단계만` |

**B-2 소계: PASS 8 / FAIL 0 / PARTIAL 0**

---

## 2. B-3. DeidentifyStep (16건)

> ⚠ 환경 제약(결함 아님): 이 스택은 정책상 `DEIDENTIFY_MOCK_MODE=false` 다(내부 self-fill 우회 금지 — VERIFY-PROMPT §1-2). 따라서 **mock 경로(TC-DEID-010~015, 018~021)는 라이브로 태울 수 없다.** 설정을 켜는 것은 §10-1 금지사항(검증 대상 동작을 바꿈)이라 수행하지 않았고, 대신 **코드 정적 대조 + 해당 경로 전용 테스트(baseline 실패 0건)** 로 판정했다. KPST 경로(016)와 execute 브릿지(023/024)는 라이브로 검증했다.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-010 | PASS | [정적] `DeidentifyStep.java:182-190`(`@PostConstruct initBasePath` → `if (mockMode) assertMockAllowedProfile()`) + `:205-221`. allowlist 상수 `ALLOWED_MOCK_PROFILES = Set.of("local","dev","stg")`(`:95`) — prd 는 `activeHasDisallowed` 로 `IllegalStateException`. 테스트 5건(`prd_프로파일에서_mock활성시_부팅거부`, `prd와_dev가_섞인_active프로파일이면_거부`, `local과_prd가_섞인…`, `비표준_환경라벨(production)이면_거부`, `ENV가_PRD_대문자여도_거부`) |
| TC-DEID-011 | PASS | [정적] `:210` `boolean noActiveProfile = active.length == 0` → `:216-221` throw. 메시지에 `activeProfiles`/`ENV` 만 노출(PII·경로 없음, CWE-209 준수). 테스트 `active프로파일_미설정이면_거부` |
| TC-DEID-012 | PASS | [정적] `:223-227` `localActive` 아니면 WARN 1줄. dev/stg 는 `ALLOWED` 라 throw 를 통과. 테스트 3건(dev/stg/local 허용) + `dev_프로파일에서_mock활성_부팅시_비식별경고_WARN로그를_1줄_남긴다` |
| TC-DEID-013 | PASS | [정적] `:213-214` `envNormalized = env.trim()` → `.toLowerCase(Locale.ROOT)` allowlist 대조 → `:216-221` throw. 반증 케이스 커버: 대문자 `PRD`·공백 포함 ` prd `·비표준 `production` 전부 거부(테스트 3건). ENV 미설정/blank 는 허용(프로파일만으로 판정) — fail-open 아님(프로파일 축이 별도로 fail-closed) |
| TC-DEID-014 | PASS | [정적] `:270-272` `if (raw == null) throw new CustomException(INVALID_INPUT, "raw 가 null 입니다.")`. 테스트 `raw_null이면_INVALID_INPUT` |
| TC-DEID-015 | PASS | [정적] `:275-277` mock 분기가 KPST 분기(`:280`)보다 **앞**. `return DeidentResult.completed(runMock(raw))` — 외부 클라이언트 호출 없음. 테스트 `mock모드_원본존재시_외부호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다` |
| TC-DEID-016 | PASS | [실동작] `:280-283` `kpstDeidentService.submit(raw); return DeidentResult.deferred();`. 라이브: rawSn=102/108/114 전건이 이 분기로 흘러 mock-server `POST /project` 200 왕복(로그 원문 §0-1) + 러너가 `deferred` 로 인지해 미전이. 원본 실재 검증은 `KpstDeidentService.java:286 verifySourceOrFail` 이 submit 내부에서 수행함을 코드로 확인 |
| TC-DEID-017 | PASS | [정적] `:284-288` — mock 도 KPST 도 아니면 `ERROR` 로그(rawSn·플래그만) 후 `CustomException(INTERNAL_ERROR, "비식별 경로가 구성되지 않았습니다.")` 고정 메시지. 레거시 폴백 분기 부재 확인(메서드 전체 3분기뿐). 테스트 2건(`KPST_disabled_이고_mock도_아니면_레거시폴백_없이_설정오류_예외`, `kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외`) |
| TC-DEID-018 | PASS | [정적] `:309-314` `if (source == null \|\| !Files.isRegularFile(source))` → `batchTransitionService.recordDeidentFailure(...)`(**별도 빈**의 REQUIRES_NEW, `BatchTransitionService.java:217-235`) 후 `EXTERNAL_API_ERROR` throw → `runMock` 의 REQUIRES_NEW 는 롤백되지만 `'F'` 는 독립 커밋. MARKING_READY 미전이(러너가 예외를 받아 전이 스킵). IT `DeidentifyStepFailurePersistenceIntegrationTest:67,86` |
| TC-DEID-019 | PASS | [정적] `:330-335` `catch (IOException e)` → `recordDeidentFailure(rawSn, MOCK_ERROR_CODE, e.getClass().getSimpleName())` + `INTERNAL_ERROR`. 예외 원문·경로 미로깅(CWE-209). ⚠ **copy IOException 전용 테스트는 부재**(원본부재·base거부 경로만 IT 보유) — 기능 결함 아닌 커버리지 갭이므로 이슈 미기록, 비고로만 남김 |
| TC-DEID-020 | PASS | [정적] `:345-358` `managed.markDeidentified("Y")` + `procLog.succeed(target)` + `workLockService.releaseRaw`(잠금 시) + `deidentReportService.resolveOpenReports` + `notificationService.notifyReviewersOnLockRelease` + `:363` `streamMetaCacheEvictor.evictAfterCommit`. 테스트 `mock모드_재비식별_잠금영상_성공시_releaseRaw_+_resolveOpenReports_+_알림`, `mock모드_비식별완료시_stream-meta_캐시가_실제로_무효화된다`. 교차확인: KPST 경로도 동일 부수효과를 라이브에서 관측(`[Notification] lock-released rawSn=102`) |
| TC-DEID-021 | PASS | [정적] `:385-398 copyAtomically` — `createDirectories` → **쓰기 직전 base 재계산·실경로 재검증**(`verifyRealPathUnder`, TOCTOU/CWE-367·59 방어) → tmp 복사 → `:401-407 moveAtomically`(ATOMIC_MOVE+REPLACE_EXISTING, `AtomicMoveNotSupportedException` 시 replace 폴백) → `finally Files.deleteIfExists(tmp)`. 테스트 `mock모드_재실행시_atomic_move로_멱등하게_덮어쓰고_손상되지_않는다`. 라이브 부수확인: KPST 경로에서도 `.mock-tmp` 임시 디렉터리가 `drwx------` 로 생성돼 잔여물이 남지 않음 |
| TC-DEID-022 | PASS | [정적] **카탈로그 기대값이 코드와 불일치했고 이번 회차에 정정함**(§카탈로그 정정 ①). 실제: `VideoArtifactRootResolver.resolveUnder(:424-441)` 가 **세그먼트 위반 → `INVALID_INPUT`(:432)** / **base 이탈·실경로 이탈 → `FORBIDDEN`(:438,:441)** 2축. 양쪽 다 기본 루트 폴백 없이 fail-secure 이므로 **코드 결함 아님**. `resolveSafeTargetPath(:418-421)` 가 넘기는 세그먼트는 `rawSn:Long` 과 상수 `deidentified.mp4` 뿐이라 실제 도달 불가한 방어심도 분기라는 카탈로그의 판단은 정확. → 1차 **B-ISSUE-25 해소**(카탈로그 정정으로) |
| TC-DEID-023 | PASS | [실동작+정적] `:239-250 execute` 에 `@Transactional` **없음**(확인) + `:245-246` `selfProvider.getObject().run(ctx.getRaw())` 프록시 경유. 라이브: 무트랜잭션 호출자(`AsyncDeidentifyRunner.runAsync`)에서 실행돼 `LS_DEIDENT_PROC_LOG` REQUESTED 행이 **실제로 커밋**됨(rawSn=102 procLogSn=68) — 프록시가 REQUIRES_NEW 를 실제로 열었다는 증거. IT `DeidentifyStepExecutePersistenceIntegrationTest` |
| TC-DEID-024 | PASS | [실동작] `:249` `ctx.markDeidentCompleted(result.completed())`. 라이브: KPST 경로에서 `completed()=false` 가 브릿지돼 러너가 `deidentify submitted (deferred)` 분기로 감(로그). 반대 축(=true)은 `AsyncDeidentifyRunnerTest:106 execute가_완료신호를_안주면_기본_false라_MARKING_READY_미전이` 로 기본값 방어까지 커버 |
| TC-DEID-025 | PASS | [정적] `BatchStepTransactionBoundaryTest.java:53` `BOUNDARY_EXEMPT = Set.of(DeidentifyStep.class, MarkingLoadStep.class)`, `:88-90` 에서 continue. 면제 사유는 `DeidentifyStep.java:116-126`(`selfProvider` javadoc)에 명문화됨. 가드 자체는 나머지 스텝에 `REQUIRES_NEW` 를 강제(`:97-100`) |

**B-3 소계: PASS 16 / FAIL 0 / PARTIAL 0** (mock 경로 9건은 `[정적]` 근거 — 사유는 절 머리말)

---

## 3. B-4. BatchOrchestrator 상태 전이 (16건)

### 3-0. 진입 가드 매트릭스 — 라이브 반증 실행 결과

동일 영상(rawSn=102)의 작업상태만 바꿔가며 `POST /v1/dev/batch/trigger?rawSn=102` 를 5회 발사한 실측:

| 작업상태 입력 | 응답 finalStage | 소요 | LS_DATA_RAW | 작업상태 | 프레임수 |
|---|---|---|---|---|---|
| PENDING | **SKIPPED** | 1ms | COMPLETED(불변) | PENDING(불변) | 2(불변) |
| IN_REVIEW | **SKIPPED** | 2ms | COMPLETED(불변) | IN_REVIEW(불변) | 2(불변) |
| APPROVED | **SKIPPED** | 2ms | COMPLETED(불변) | APPROVED(불변) | 2(불변) |
| REJECTED | **SKIPPED** | 2ms | COMPLETED(불변) | REJECTED(불변) | 2(불변) |
| **ASSIGNED** | FAILED | 79ms | **FAILED** | **FAILED** | 2 |

- SKIPPED 4건은 **1~2ms** 에 반환됐고 로그에 `MarkingLoadStep`·`VlmTimeseriesStep`·`FfmpegFrameExtractor` 가 **한 줄도 없다** → "step 을 한 건도 실행하지 않음" 이 실증됨(외부 VLM 호출 0건도 mock-server 로그로 확인).
- ASSIGNED 는 차단되지 않고 파이프라인이 실제 실행됨 → `REVIEW_OWNED_STATUSES` 4종 정의가 실동작과 일치.
- 백엔드 로그: `[BatchTransition] work status transition skipped (review-owned) rawSn=102 current=APPROVED target=PROCESSING` → `[BatchOrchestrator] skipped — review-owned work status rawSn=102`

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-030 | PASS | [정적+실동작] `BatchOrchestrator.java:101-103` `if (rawSn == null) throw INVALID_INPUT`. 컨트롤러 경유로는 `@Min(1)` 이 앞서 막음(라이브: `rawSn=0` → 400 `trigger.rawSn: must be greater than or equal to 1`, 파라미터 생략 → 400 `필수 파라미터가 누락되었습니다: rawSn`) → null 도달 자체가 REST 로는 불가하나 내부 호출자(`AsyncBatchRunner`/Quartz) 대비 방어는 유효 |
| TC-BATCH-031 | PASS | [정적] `:149-153` `videoRepository.findById(rawSn).orElseThrow(NOT_FOUND)`. 라이브에서는 컨트롤러 자체 사전조회가 먼저 404 를 냄(`rawSn=99999999` → 404 `rawSn=99999999 영상이 존재하지 않습니다.`)이라 orchestrator 분기는 그늘에 가림. ⚠ 부수 발견: `loadRaw` 가 `protected` + 자기호출(`:104`)이라 `@Transactional(REQUIRES_NEW, readOnly)` 무효(1차 B-ISSUE-24 미해소) → **B-ISSUE-03** 이월 |
| TC-BATCH-032 | PASS | [실동작] rawSn=102/108 정상 완주. 로그: `marking check count=1` → `VlmTimeseries describe submit` → `FrameExtract frames=2` → `Yolo saved labels` → `Sam2 saved polygons` → `Interpolation` → `[BatchOrchestrator] completed rawSn=102`. DB: `LS_DATA_RAW=COMPLETED`, `LS_RAW_DATA_STATUS=ASSIGNED`, `ls_bat_rty_wtng` 행 삭제(=`retryQueue.clear`) |
| TC-BATCH-033 | PASS | [실동작] 프레임 재추출 시 `uk_ls_data_src_raw_frame` 위반 → `DataIntegrityViolationException`. 로그: `[BatchRetry] enqueued rawSn=102 attempt=1 delaySec=60` → `[BatchOrchestrator] failed rawSn=102 willRetry=true cause=DataIntegrityViolationException`. DB: `ls_bat_rty_wtng`(bat_rty_sn=63, rty_nmtm=1, max=3, PENDING, rty_prnmnt_dt=+60s) + `LS_DATA_RAW=FAILED` + 작업상태 `FAILED` |
| TC-BATCH-034 | PASS | [실동작] rawSn=108 동시 5요청 중 4건 실패로 attempt 1→2→3→4 소진: `[BatchRetry] max attempts exceeded -- exhausted rawSn=108 attempt=4 max=3` → `[BatchOrchestrator] failed rawSn=108 willRetry=false` → 응답 `finalStage=FAILED` |
| TC-BATCH-035 | **N/A** | [정적] 토글을 넘기는 오버로드 `process(Long, Map)` 의 **프로덕션·dev 호출자 0건**(`grep -rn "orchestrator.process("` → 5곳 전부 `process(rawSn)` 단일 인자). REST/Quartz 어느 진입점으로도 태울 수 없어 실동작 검증 대상이 존재하지 않는다. 1차 **B-ISSUE-23 미해소** → **B-ISSUE-04** 이월. 카탈로그에 도달불가 표기 추가(정정 ③) |
| TC-BATCH-036 | PASS | [정적+실동작] `BatchContext.java:77-84 isStageEnabled` — 키 미존재/명시 null → `true`. `:70-72` 생성자가 `stageToggles == null` 이면 `Map.of()` 로 정규화. 라이브: `process(rawSn)`(=토글 null) 경로에서 MARKING/VLM/FRAME/YOLO/SAM2/INTERPOLATE 6단계 전부 실행 로그 확인. 근거 라인 78-85 → **77-84 로 정정**(정정 ②) |
| TC-BATCH-037 | PASS | [실동작] **두 테이블 책임 분리의 직접 증거.** rawSn=102 배치 완료 시 `LS_DATA_RAW.DATA_STTS_CD=COMPLETED` / `LS_RAW_DATA_STATUS.DATA_STTS_CD=ASSIGNED`(ver=2). ver 궤적 0(BATCH_QUEUED insert)→1(PROCESSING)→2(ASSIGNED) 가 `markRawDataCompleted` 가 COMPLETED 로 점프하지 않았음을 증명 |
| TC-BATCH-038 | PASS | [실동작] 위 037 로 배치 완료가 작업상태를 `ASSIGNED` 로 되돌리는 것이 확인됐고, 그 상태에서 `ASSIGNED→PENDING`(검수 제출)이 정상 동작함은 동일 스택·동일 회차의 `_raw/pipeline-drive.md §1`(rawSn=101: 배정 ASSIGNED(ver3) → WORKER 제출 PENDING(ver4) → IN_REVIEW(ver5) → APPROVED(ver6))로 실증. 작업 `COMPLETED` 는 `ReviewService.approve` 전용 경로임을 코드로 재확인 |
| TC-BATCH-039 | PASS | [실동작] rawSn=102(ASSIGNED 입력) 실패 실행 후 `LS_DATA_RAW=FAILED` + 작업상태 `FAILED` 동시 전이 확인 — `MARKING_READY` 고착 없음. `BatchTransitionService.java:184-197`(정확 일치) |
| TC-BATCH-040 | PASS | [실동작] rawSn=102 정상 배치에서 작업상태 row 가 `BATCH_QUEUED`(ver0)로 생성된 뒤 **ver1=PROCESSING**, ver2=ASSIGNED 로 2회 UPDATE 됨 → `markRawDataProcessingBlocked` 가 false 를 반환하며 두 컬럼을 함께 PROCESSING 으로 전이했음을 ver 궤적으로 증명(동기 구간이라 중간 상태 직접 관측은 불가, 낙관적 버전이 대체 증거). 근거 라인 110-124 → **110-122 로 정정** |
| TC-BATCH-041 | PASS | [실동작] rawSn=108 의 `ls_raw_data_status` 행을 삭제 후 트리거 → 로그 `[BatchTransition] raw data status not found rawSn=108 target=PROCESSING` … `target=ASSIGNED` **2회 WARN**, 예외 없이 파이프라인 완주(`finalStage=COMPLETED`, 프레임 2건 재생성). 작업상태 row 는 끝까지 0건 유지(파생 RAW 형상 재현) |
| TC-BATCH-042 | PASS | [실동작] §3-0 매트릭스 4행. SKIPPED + 1~2ms + step 로그 0줄 + `LS_DATA_RAW` 불변 + 프레임 불변 + 외부 호출 0건 전부 확인 |
| TC-BATCH-043 | PASS | [실동작+정적] `BatchTransitionService.java:77-81` 4종 상수 확인 + §3-0 5행 매트릭스가 **PENDING/IN_REVIEW/APPROVED/REJECTED 만 차단되고 ASSIGNED 는 통과**함을 실측으로 증명(집합 경계 반증 완료) |
| TC-BATCH-044 | PASS | [정적] `:151-154`(markRawDataCompleted) · `:189-193`(markRawDataFailed) 둘 다 `if (transitionRawDataStatus(...)) return;` 로 `LS_DATA_RAW` 미변경. ⚠ 진입 가드(`:112-115`)가 먼저 SKIPPED 로 끊어 `process()` 경로에서는 **도달 불가한 방어심도**이며, 코드 주석도 그렇게 명시(`:190`). §3-0 매트릭스가 이를 뒷받침(APPROVED 입력 시 `LS_DATA_RAW` 불변) |
| TC-BATCH-045 | **PARTIAL** | [실동작] 진입점 축은 충족 — `grep` 으로 5개 호출자(`BatchDevTriggerController:119`, `BatchRetryQuartzJob:55`, `AsyncBatchRunner:25`, `BatchQuartzJob:51`, `BatchReprocessService:87`)가 **전부 `process(Long)` 단일 관문**을 지남을 확인. **그러나 가드가 원자 클레임이 아니라 동시 실행 축에서는 "무증상 오염 차단"이 성립하지 않는다** — 동일 rawSn 동시 5요청이 전부 가드를 통과해 파이프라인 5벌이 병렬 실행됐다(§4 B-ISSUE-01). 1차 **B-ISSUE-22 미해소** |

**B-4 소계: PASS 14 / PARTIAL 1 / N/A 1 / FAIL 0**

---

## 4. 이슈 기록

### [B-ISSUE-01] TC-BATCH-045 — `BatchOrchestrator.process()` 진입 가드가 원자 클레임이 아니어서 동일 rawSn 동시 실행이 전혀 차단되지 않는다 (1차 B-ISSUE-22 미해소 · 심각도 상향)

- **심각도**: **HIGH** *(1차 MEDIUM → 상향. 근거: 이번 회차에 ①외부 벤더 5중 위탁 ②재시도 예산 즉시 소진 ③종단 상태 비결정성 3가지 신규 실증)*
- **기대 동작(기대효과)**: `BatchOrchestrator` 클래스 Javadoc 이 **"영상 단위 직렬 호출 보장 — `process(Long)` 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다"**(`BatchOrchestrator.java:50-51`)고 단언하고, TC-BATCH-045 는 진입 가드를 "APPROVED 영상에 AUTO 라벨이 새로 적재되는 무증상 오염 차단" 관문으로 규정한다. 파이프라인은 프레임 추출·AUTO 라벨 적재·**외부 VLM 위탁** 같은 비멱등 부수효과를 수행하므로, 같은 rawSn 에 두 실행이 겹치면 안 된다. 2노드 Active-Active 형상에서는 JVM 락이 방어가 되지 않아 DB 수준 클레임이 필요하다(`CLAUDE.md`: "Quartz 클러스터링은 트리거 중복만 막고 잡 내부 레이스는 원자 클레임이 별도로 막는다").
- **현재 동작(이슈 내용)**: 진입부의 조건부 UPDATE 는 **"검수 소유 상태인가"만** 판정하고 `PROCESSING` 은 차단 집합에 없어, **이미 실행 중인 영상도 그대로 통과**한다.
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
  **라이브 실측 (2026-08-03, rawSn=108 · 마킹 1건 보유 · 프레임 사전 삭제 후 동시 5요청)**:
  ```
  응답: COMPLETED 1건 / FAILED 4건  (409·SKIPPED 0건)

  backend 로그 (같은 밀리초에 5개 스레드가 동시 진입)
  00:30:12.329 [exec-6]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.330 [exec-8]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.332 [exec-7]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.332 [exec-10] MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.333 [exec-5]  MarkingLoadStep - marking check rawSn=108 count=1
  → ★외부 VLM 5중 위탁 (서로 다른 request_id 5개, mock-server 가 5회 수락)
  00:30:12.334 VlmTimeseriesStep - describe submit rawSn=108 request_id=55763134-…
  00:30:12.334 VlmTimeseriesStep - describe submit rawSn=108 request_id=94981639-…
  00:30:12.336 VlmTimeseriesStep - describe submit rawSn=108 request_id=3478cb28-…
  00:30:12.337 VlmTimeseriesStep - describe submit rawSn=108 request_id=51813285-…
  00:30:12.338 VlmTimeseriesStep - describe submit rawSn=108 request_id=92c7134a-…
  → ★재시도 예산이 단 1회 사고로 전량 소진
  00:30:12.469 BatchRetryQueue - enqueued rawSn=108 attempt=1 delaySec=60
  00:30:12.470 BatchRetryQueue - enqueued rawSn=108 attempt=2 delaySec=120
  00:30:12.472 BatchRetryQueue - enqueued rawSn=108 attempt=3 delaySec=240
  00:30:12.473 BatchRetryQueue - max attempts exceeded -- exhausted rawSn=108 attempt=4 max=3

  DB: ls_raw_data_status.ver  2 → 12   (=5×PROCESSING + 5×종단 = 10회 UPDATE)
  ```
  - **① 외부 벤더 5중 위탁** — 1차에서는 검체에 마킹이 없어 관측되지 않았던 신규 피해다. 동일 영상에 대해 VLM `describe` 가 5회 나가고 5개 콜백이 되돌아와 `LS_DATA_META` 를 순서 없이 덮어쓴다(로그: `result applied … updated=1` ×5). 외부 과금·자원 낭비 + 마지막 콜백이 이기는 비결정 결과.
  - **② 재시도 예산 즉시 소진** — 동시 실패 4건이 같은 `rty_nmtm` 카운터를 경쟁 증가시켜 **단일 사고로 `max-attempts=3` 가 그 자리에서 EXHAUSTED** 된다. 이후 진짜 재시도가 필요한 실패에 재시도가 남아 있지 않다.
  - **③ 종단 상태 비결정** — 이번엔 우연히 COMPLETED 스레드가 마지막에 끝나 `LS_DATA_RAW=COMPLETED` 로 남았지만, 순서가 뒤집히면 파이프라인이 성공했는데도 `FAILED` 로 마감된다(마지막 쓰기 승).
  - 개별 진입점의 클레임(`tryClaimBatchQueued` / 재시도 큐 CAS / `tryClaimReprocessFromFailed`)은 **서로 다른 락**이라 진입점이 다르면 교차 방어가 되지 않고, dev 트리거의 `PROCESSING` 사전 검사(`BatchDevTriggerController.java:83-86`)는 read-then-act TOCTOU 다(위 실측에서 5건 전부 통과).
- **재현/확인 경로**:
  ```bash
  # 마킹이 있는 영상(rawSn=N)에 대해
  psql -c "DELETE FROM ls_data_src WHERE raw_sn=N;"      # 재추출 가능 상태로
  for i in 1 2 3 4 5; do (curl -sX POST ".../v1/dev/batch/trigger?rawSn=N" -H "Authorization: Bearer $TOK") & done; wait
  psql -c "SELECT ver FROM ls_raw_data_status WHERE raw_data_id=N;"   # 10 증가
  docker logs klid-backend | grep "describe submit rawSn=N" | wc -l   # 5
  ```
- **영향**: 데이터 정합(프레임·AUTO 라벨 중복 적재, 상태 전이 경합) · **외부 연동 계약 위반**(동일 작업 다중 위탁) · 가용성(재시도 예산 소진) · CWE-362(Race Condition) · CWE-1223(비원자 check-then-act) · CWE-770(외부 호출 무제한). 2노드 Active-Active + Quartz 클러스터링 형상에서 정확히 미방어인 구간.
- **수정 방향(제안)**: `markRawDataProcessingBlocked` 를 **"진입 클레임"** 으로 승격 — `LS_DATA_RAW.DATA_STTS_CD` 가 `PROCESSING` 이 **아닐 때만** `PROCESSING` 으로 바꾸는 조건부 UPDATE(영향 행수 1 인 호출만 진행, 0 이면 `SKIPPED`). 정상 종료 3경로(COMPLETED/FAILED/예외)에서 해제 보장 + **stale `PROCESSING` 회수 스윕**(`BatchRetryStaleReclaimSweeper` 동일 패턴, 노드 사멸 시 영구 고착 방지) 동반 필요. ⚠ 구현은 하지 않음.

### [B-ISSUE-02] TC-DEID-007 — `AsyncDeidentifyRunner.loadRaw` 의 `@Transactional(REQUIRES_NEW, readOnly)` 가 `protected`+자기호출로 무효 (1차 B-ISSUE-05 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 근거와 코드 주석이 "REQUIRES_NEW readOnly 로 영상 메타를 조회한다"(`AsyncDeidentifyRunner.java:109`)고 선언하면 실제로 그 경계가 적용돼야 한다. `CLAUDE.md` 도 "자기호출로 프록시를 우회하지 말 것"을 별도 구속 규칙으로 두고 있다(프레임 이미지 서빙 절).
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 무변경.
  ```java
  // AsyncDeidentifyRunner.java:79
  LsDataRaw raw = loadRaw(rawSn).orElse(null);        // ← this.loadRaw (프록시 미경유)
  // :110-112
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  두 가지 이유로 동시에 무효다 — ①자기호출(AOP 프록시 우회) ②`AnnotationTransactionAttributeSource` 기본 `publicMethodsOnly=true` 라 `protected` 는 어드바이스 대상 아님.
- **재현/확인 경로**: 정적. 런타임은 `logging.level.org.springframework.transaction=TRACE` 로 적재 트리거 시 `loadRaw` 구간에 `Creating new transaction` 이 없음을 확인(코드 수정 금지라 이번 회차 미수행).
- **영향**: 현재 실피해 없음(`@Async` 라 앰비언트 tx 없음 + `SimpleJpaRepository` 자체 readOnly tx + `LsDataRaw` 는 연관 매핑 없는 평면 엔티티). 위험은 미래 — pre-marking 파이프라인이 확장돼 `runAsync` 가 트랜잭션 안에서 호출되거나 `LsDataRaw` 에 연관이 붙는 순간 전제가 조용히 깨진다. 주석이 사실과 달라 후속 개발자를 오도한다.
- **수정 방향(제안)**: 조회를 별도 빈(`…LookupService`)의 `public @Transactional(readOnly)` 로 분리하거나 `DeidentifyStep` 이 이미 쓰는 `ObjectProvider<Self>` 패턴 적용. 둘 다 아니면 어노테이션을 제거하고 javadoc 을 실제 동작에 맞춰 정정(선언만 남기는 것이 가장 나쁘다). 재발 방지로 "`@Transactional` 이 `private`/`protected` 에 붙으면 실패"하는 정적 가드 추가. ⚠ 구현은 하지 않음.

### [B-ISSUE-03] TC-BATCH-031 — `BatchOrchestrator.loadRaw` 도 동일한 `protected`+자기호출 무효 패턴 (1차 B-ISSUE-24 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: `BatchOrchestrator.process` Javadoc 이 *"process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), 영상 메타 조회를 위한 짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리"*(`BatchOrchestrator.java:83-84`)라고 선언한다.
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 무변경.
  ```java
  // BatchOrchestrator.java:104
  LsDataRaw raw = loadRaw(rawSn);          // ← 자기호출(프록시 우회)
  // :147-149
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected LsDataRaw loadRaw(Long rawSn) { ... }   // ← protected + self-invoke = 사문화
  ```
  동일 패턴이 `AsyncDeidentifyRunner.java:110-117`(B-ISSUE-02), `DevPipelineRunner.java` 에도 있으며 주석에 *"BatchOrchestrator.loadRaw 패턴"* 이라 적혀 **의도적으로 복제 전파**됐다.
- **재현/확인 경로**: 정적(위 grep). 라이브 rawSn=102/108 정상 완주로 현재 기능 영향이 없음은 확인됨.
- **영향**: 현재 기능 영향 없음. 위험은 미래(연관 매핑 추가 시 `LazyInitializationException`, 조회 추가 시 커넥션 왕복 2회) + 주석-구현 불일치.
- **수정 방향(제안)**: B-ISSUE-02 와 동일 처방. 세 곳을 한 번에 정리하고 정적 가드로 재발 차단. ⚠ 구현은 하지 않음.

### [B-ISSUE-04] TC-BATCH-035 — stage 토글 오버로드 `process(Long, Map)` 의 호출자가 여전히 0건(도달 불가 경로) (1차 B-ISSUE-23 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: Javadoc 이 *"토글 대상 단계(FRAME_EXTRACT/YOLO/SAM2)가 off 면 … dev 단일 파이프라인 수렴 경로에서 사용한다"*(`BatchOrchestrator.java:91-99`)라고 용도를 명시하고, TC-BATCH-035 는 그 시나리오를 검증 대상으로 잡는다.
- **현재 동작(이슈 내용)**: 프로덕션·dev 통틀어 이 오버로드를 호출하는 코드가 **한 곳도 없다**(1차 이후 무변경).
  ```
  $ grep -rn "orchestrator.process(" backend/src/main/java
  BatchDevTriggerController.java:119:  orchestrator.process(rawSn);
  BatchRetryQuartzJob.java:55:         orchestrator.process(rawSn);
  AsyncBatchRunner.java:25:            orchestrator.process(rawSn);
  BatchQuartzJob.java:51:              orchestrator.process(rawSn);
  BatchReprocessService.java:87:       orchestrator.process(rawSn);
  ```
  `BatchContext.stageToggles`·`BatchStep.isEnabled` 오버라이드 3종·단위테스트(`BatchOrchestratorTest`, `BatchContextToggleTest`)만 남아 커버리지 지표상으로는 드러나지 않는다.
- **재현/확인 경로**: 위 grep. `BatchDevTriggerController.trigger` 는 `rawSn` 만 받는다(`:76-78`) — REST 로 토글을 넘길 수 없다.
- **영향**: 기능 영향 없음. 테스트만 존재하는 미사용 분기라 "지원되는 기능"으로 오인될 수 있고, TC-BATCH-035 는 실동작 검증 대상이 없다(이번 회차 **N/A**).
- **수정 방향(제안)**: ①dev 토글이 필요하면 `BatchDevTriggerController.trigger` 에 optional `stages` 파라미터를 붙여 진입점 복구(`@Profile("!prd")` 안이라 노출 위험 없음), 또는 ②불필요하면 오버로드·`stageToggles`·3개 `isEnabled` 오버라이드·관련 테스트를 함께 제거하고 카탈로그에서 TC-BATCH-035/036 을 폐기 표기. ⚠ 구현은 하지 않음.

### [B-ISSUE-05] TC-DEID-002 — 적재 롤백 시 비식별 미트리거를 보증하는 회귀 테스트·재현 경로가 없다

- **심각도**: LOW (커버리지 갭 — 현재 동작 결함 아님)
- **기대 동작(기대효과)**: TC-DEID-002 는 "적재 tx 롤백 → 리스너 미호출"을 P1 로 규정한다. 이 단언이 깨지면 **DB 에 없는 영상에 대해 외부 KPST 위탁이 나가고** 원장(`LS_DEIDENT_PROC_LOG`)에 고아 행이 쌓인다.
- **현재 동작(이슈 내용)**: 동작 자체는 Spring 계약으로 보장된다(`IngestDeidentifyBridge.java:26` — `fallbackExecution` 미지정 = 기본 `false` → 롤백 시 AFTER_COMMIT 콜백 미실행).
  ```java
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onVideoIngested(VideoIngestedEvent event) { ... }
  ```
  그러나 ①이를 고정하는 테스트가 없고(`IngestDeidentifyBridgeTest` 는 `VideoIngestedEvent_수신_시_AsyncDeidentifyRunner_위임` 1건뿐 — 리스너를 직접 호출하므로 트랜잭션 축을 전혀 검증하지 않는다) ②`fallbackExecution = true` 를 실수로 추가하거나 이벤트 발행이 tx 밖으로 옮겨져도 **아무 테스트도 실패하지 않는다**. 이번 회차에도 publish 이후 롤백을 유발할 진입점이 코드에 없어 라이브 반증을 수행하지 못했다.
- **재현/확인 경로**: 현재로선 없음. 검증하려면 `@SpringBootTest` + `TestTransaction`/의도적 예외로 `TrainingVideoIngestTx.ingestOne` 을 롤백시키고 `AsyncDeidentifyRunner` 스파이가 **0회 호출**됨을 단언하는 IT 가 필요하다.
- **영향**: 회귀 감지 불가(잠재). 실제 유출 시 외부 벤더에 존재하지 않는 영상이 위탁된다.
- **수정 방향(제안)**: `IngestDeidentifyBridgeTest` 에 트랜잭션 축 IT 1건 추가 — ①커밋 시 1회 호출 ②롤백 시 0회 호출 ③무트랜잭션 publish 시 0회 호출. 동일 패턴이 `MarkingBatchBridge`·`DatasetExportBridge` 등 AFTER_COMMIT 브릿지 전반에 적용 가능. ⚠ 구현은 하지 않음.

---

## 5. 카탈로그 정정 (담당 라인범위 50~104행, 총 8건)

| # | 대상 | 정정 내용 | 사유 |
|:--:|---|---|---|
| ① | **TC-DEID-022** | 기대결과를 *"base 이탈 → `INVALID_INPUT`"* 단일 축 → **2축**(세그먼트 위반 `INVALID_INPUT` / base·실경로 이탈 **`FORBIDDEN`**)으로 재기술. 근거를 `DeidentifyStep.java:408-420` → `DeidentifyStep.java:418-421 · VideoArtifactRootResolver.java:424-441` 로 교체 | **기대값이 코드와 불일치**(1차 B-ISSUE-25). 그대로 두면 차기 회차가 "FORBIDDEN 이 떴으니 FAIL" 로 오판 |
| ② | **TC-BATCH-036** | 근거 `BatchContext.java:78-85` → **`:77-84`** | 라인 드리프트(isStageEnabled 실제 위치) |
| ③ | **TC-BATCH-035** | 기대결과에 "⚠ **실동작 검증 불가** — `process(Long, Map)` 호출자 0건(1차 B-ISSUE-23 미해소)" 명시 | 전제가 도달 불가한데 표기가 없어 매 회차 실동작 시도가 반복됨 |
| ④ | **TC-DEID-025** | 근거 `BatchStepTransactionBoundaryTest.java:BOUNDARY_EXEMPT` → **`:53,88-90`** + 상수 실값 `Set.of(DeidentifyStep.class, MarkingLoadStep.class)` 명기 | 앵커만 있어 대조 비용이 큼 + 면제 대상이 2종임이 드러나지 않음 |
| ⑤ | **TC-DEID-001** | 근거 `:26-30` → **`:26-31 · AsyncConfig.java:35-50`**, 기대결과에 "리스너는 적재 스레드 동기 실행 / 비식별만 `batchAsyncExecutor`(core2·max4·queue50·CallerRunsPolicy)로 이관, 큐 포화 시 역압으로 적재 스레드가 직접 실행(의도된 설계)" 추가 | 라인 드리프트 + 실행 스레드 모델이 케이스에 없어 "비동기니까 적재가 안 막힌다"는 오독 소지 |
| ⑥ | **TC-DEID-006** | 기대결과에 "⚠ 이 catch 에 도달하는 실패는 Phase C-2 이후 **제출 이전**뿐(제출 이후는 `KpstSubmitOutcomeRecorder`/폴링이 별도 REQUIRES_NEW 로 `'F'` 기록)" 추가. 근거에 `:29-37`(실패 정책 javadoc) 병기 | Phase C-2 논블로킹 전환으로 케이스의 검증 범위가 실제로 좁아졌는데 표기 미반영 |
| ⑦ | **TC-BATCH-037/038/040/042** | 근거 라인 정정 — 037/038 `BatchTransitionService:146-160`/`147-160` → **`:146-158`**, 040 `:110-124` → **`:110-122`**, 042 `BatchOrchestrator:110-118` → **`:112-115`** / `BatchTransitionService:110-124` → **`:110-122`** | 메서드 실제 종료 라인과 불일치(각 ±2) |
| ⑧ | **TC-BATCH-045** | 기대결과에 "⚠ 이 가드는 클레임이 아니다 — `PROCESSING` 이 차단 집합 밖이라 동일 rawSn 동시 실행을 막지 못한다(3차 실측: 동시 5요청 → 파이프라인 5벌 + 외부 VLM 5중 위탁). '무증상 오염 차단'은 순차 경로에서만 성립(B-ISSUE-22 미해소)" 추가. 근거 `:110-118` → **`:112-115`** | 케이스가 가드를 무조건적 관문으로 단언해 **거짓 PASS 를 유도**하고 있었음 |

> 폐기(취소선) 처리한 케이스는 없다. 신규 케이스 추가도 없다(범위 밖).

---

## 6. 이전 회차 이슈 대조

| 이슈 | 내용 | 이번 회차 상태 |
|---|---|:--|
| **B-ISSUE-22**(1차) | `process()` 동시 중복 실행 미차단 | ❌ **미해소 — 심각도 상향(MEDIUM→HIGH)**. 외부 VLM 5중 위탁·재시도 예산 즉시 소진·종단 상태 비결정 3건 신규 실증 → **B-ISSUE-01** 로 이월 |
| **B-ISSUE-05**(1차) | `AsyncDeidentifyRunner.loadRaw` 자기호출 | ❌ 미해소(코드 무변경) → **B-ISSUE-02** 로 이월 |
| **B-ISSUE-21**(1차) | B-3 근거 라인 +10 일괄 드리프트 | ✅ **해소** — B-3 16건 중 15건이 정확히 일치(TC-DEID-010~021,023~024). 잔여는 TC-DEID-022 한 건뿐이며 이번 정정 ①로 처리 |
| **B-ISSUE-23**(1차) | `process(Long,Map)` 호출자 0건 | ❌ 미해소(grep 결과 동일) → **B-ISSUE-04** 로 이월. 카탈로그에 도달불가 표기 추가(정정 ③) |
| **B-ISSUE-24**(1차) | `BatchOrchestrator.loadRaw` 자기호출 | ❌ 미해소(코드 무변경) → **B-ISSUE-03** 으로 이월 |
| **B-ISSUE-25**(1차) | TC-DEID-022 기대 ErrorCode 불일치 | ✅ **해소** — 이번 회차 카탈로그 정정 ①로 2축 기술로 교체 |
| **2차 타겟재검증** | 마킹 영구고착 / 재처리 원자클레임 / 스트리밍 심링크 PII | ✅ **여전히 유효** — 이번 담당 범위와 직접 겹치지 않으나 교차 관측으로 회귀 없음 확인: ①마킹 고착 — rawSn=102/108 의 배치 skip 4회에서 마킹이 `SKIPPED` 종결되어 재마킹 409 없음 ②재처리 클레임 — `tryClaimReprocessFromFailed` 의 0행 원인 재판정 로직 코드상 존치(`BatchTransitionService.java:327-341` javadoc + 구현) ③스트리밍 — 이번 회차 미터치(B-10 담당 범위) |
| B-ISSUE-06/07(1차) | scan tick readOnly tx 커넥션 점유 / `PROCESSING` 좀비 | 담당 범위 밖(B-1). 단 B-ISSUE-06 은 **이번 회차 CallerRunsPolicy 관측과 결합 시 악화 가능** — 큐 포화 시 비식별이 scan tick 스레드에서 실행돼 외부 tx 점유 시간이 늘어난다(사실만 기록) |

---

## 7. 이번 회차가 남긴 테스트 데이터

| 테이블 | 추가/변경 | 비고 |
|---|---|---|
| `ls_data_ingest` | `rcptn_sn=2,8,14` (`QA3RD-B2-DEID-001`/`QA3RD-B4-RACE-001`/`QA3RD-B3-BADSRC-001`) | 전부 DONE/종결 |
| `ls_data_raw` | `raw_sn=102`(COMPLETED/Y), `108`(COMPLETED/Y), `114`(PENDING/**F** — 의도된 실패 검체) | `evnt_type_cd='INTRUSION'` 은 102/108 만 수동 세팅(관제 인입 컬럼 부재 갭, 기존 사항) |
| `ls_raw_data_status` | `raw_data_id=102`(FAILED), `108` **행 없음**(TC-BATCH-041 실험으로 삭제) | |
| `ls_data_src` | 102: 2건, 108: 2건 | |
| `ls_bat_rty_wtng` | `bat_rty_sn=63`(raw_sn=102, PENDING attempt=1) | 60초 뒤 자동 재시도 예정 |
| 파일시스템 | `/app/storage/raw/seed/qa3-badclip.mp4`(11B), `102/deid/`, `108/deid/`, `114/…` | |

> **rawSn=101 은 조회조차 하지 않았다**(다른 클러스터 공용 데이터 보호).
