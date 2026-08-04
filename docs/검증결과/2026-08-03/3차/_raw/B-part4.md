# B 클러스터 part4 — B-12 재처리/재시도 큐 · B-13 KPST 위탁·폴링 · B-14 Quartz/인프라/헬스

- **회차**: 2026-08-03 3차 · **담당 범위**: `docs/test-cases/B-batch-deidentify.md` 317~394행
- **케이스 수**: **63건**(B-12 19 · B-13 34 · B-14 10, 폐기 0건 — `grep -cE '^\| *~*TC-'` 실측)
- **판정 집계**: **PASS 61 · PARTIAL 2 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0**
- **이슈**: `B-ISSUE-61` ~ `B-ISSUE-65` (5건 — MEDIUM 2 · LOW 3)
- **카탈로그 정정**: 6셀(기대값 오류 2 · 근거 드리프트 3 · 계약 보강 1)

---

## 0. 검증 환경 · 실동작 수행 내역

| 항목 | 실측 |
|---|---|
| 스택 | `stack-bringup.md` 기준 5컨테이너 healthy(HEAD `e065da42` 재빌드분). backend `localhost:18081/api`, DB `klid_system`(스키마 `public`) |
| 공용 정상 데이터 | `pipeline-drive.md` rawSn=101 (상태 변경 없음 — 본 검증은 **rawSn 46·47·15·49·58·96 및 procLog 8** 만 사용) |
| 인증 | `POST /v1/dev/tokens` REVIEWER(userNo=1001) JWT |
| **live 재현 수행** | ① 재처리 동시 5요청(rawSn=46) ② 백오프/소진(rawSn=47) ③ SKIPPED 보상(rawSn=15) ④ stale 스윕 tick 관측(rawSn 46·49·58) ⑤ 클레임 CAS 동시 2트랜잭션(retry queue · procLog) ⑥ `ON CONFLICT DO NOTHING` 동시 최초 INSERT(rawSn=96) ⑦ KPST 폴링 실왕복(mock-server 로그 + `LS_DEIDENT_PROC_LOG`) ⑧ `/actuator/health` deidentify |
| 상태 원복 | 전건 원복 완료(§6) |

### ⚠ 이 스택의 구조적 제약 (판정에 반영)

- **`authoring.batch.enabled=false`(application-local.yml:62)** → `BatchTriggerConfig`·`BatchRetryTriggerConfig` 미등록. `qrtz_job_details` 실측 결과 등록 잡은 `bootstrapJob`·`kpstDeidentPollJob`·`datasetExportFailureRecoveryJob`·`datasetExportPendingSweepJob`·`controlTrainingVideoScanJob` 5개뿐이고 **`batchRetryJob`/`batchPipelineJob` 은 없다**. 의도된 local 격리이며 결함 아님.
  → 재시도 **폴러 발화 자체**는 이 스택에서 재현 불가하므로, `pollReady()` 의 핵심인 **조건부 원자 UPDATE(CAS)** 를 동일 술어의 SQL 로 **동시 2트랜잭션 실동작 재현**해 대체 검증했다(§1 TC-BATCH-159).
- **stale 회수 스윕(`BatchRetryStaleReclaimSweeper`)은 Quartz 가 아닌 전용 데몬 스케줄러**라 batch 토글과 무관하게 **실제로 돌고 있다** — 기동 로그 + `batch-retry-stale-reclaim` 스레드 + 15분 tick 실관측으로 확인(TC-BATCH-164·165·167 은 완전 실동작 판정).
- **stg/prd 프로파일 전제 케이스**(TC-BATCH-171·173·174·175)는 local 스택에서 실기동 불가 → 지시대로 **정적 코드 판정 + 로컬 실효값 관측**으로 판정했다(`QuartzClusteringGuard` 기동 로그 실관측).

---

## 1. B-12. 재처리 / 재시도 큐 (2노드 안전 · stale 회수) — 19건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-150 | PASS | [실동작] `POST /v1/videos/0/batch/retry` → **400**(`@Min(1)` 선차단). [정적] 서비스 null 가드 `BatchReprocessService.java:67-69` 존재(방어심도) — 근거 라인 일치 |
| TC-BATCH-151 | PASS | [실동작] `rawSn=999999` → **404** `{"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}`. `BatchReprocessService.java:71-73` 일치 |
| TC-BATCH-152 | PASS | [실동작] rawSn=46(raw FAILED/work FAILED) → **200** `{"rawSn":46,"stage":"FAILED"}`. 로그 `[BatchReprocess] manual retry claimed rawSn=46` → `MarkingLoadStep` → `BatchOrchestrator failed` 순서 관측. `:77-87` 일치 |
| TC-BATCH-153 | PASS | [실동작] **동시 5요청 → 200 정확히 1건 / 409 4건**(§5 재현). 409 메시지 = 기대 문구. `:77-81` 일치 |
| TC-BATCH-154 | PASS | [실동작] 409 4건 각각에 `[BatchTransition] reprocess claim rejected — raw stage owned by another caller rawSn=46` WARN — **RAW 0행 시 원인 재판정(PROCESSING/FAILED → 폴백 금지)** 경로가 실제로 탔다. [정적] `BatchTransitionService.java:352-374` 일치(2차 B-ISSUE-101 수정분 전수 재확인) |
| TC-BATCH-155 | PASS | [실동작] rawSn=46 최초 실패 → 신규 행 `bat_rty_sn=58, rty_nmtm=1, stts_cd=PENDING`. 로그 `[BatchRetry] enqueued rawSn=46 attempt=1 delaySec=60`. `BatchRetryQueue.java:67-92` 일치 |
| TC-BATCH-156 | PASS | [실동작] 동일 `RAW_SN`(96)에 **동시 2트랜잭션 `INSERT … ON CONFLICT (RAW_SN) DO NOTHING`** → A `INSERT 0 1` / B는 A 커밋까지 대기 후 **`INSERT 0 0`(예외 0건)**. §11 "PG unique 위반이 tx 전체를 abort" 함정 회피 실증. `:72-74` 일치 |
| TC-BATCH-157 | PASS | [실동작] `rty_nmtm=2` 시드 후 재실패 → `attempt=3 delaySec=**240**`, DB `rty_prnmnt_dt - mdfcn_dt = 240s`. 60→120→240 지수백오프 + shift 30 캡(`:85-88`) 일치 |
| TC-BATCH-158 | PASS | [실동작] `rty_nmtm=3`(=max) 시드 후 재실패 → `[BatchRetry] max attempts exceeded -- exhausted rawSn=47 attempt=4 max=3`, DB `stts_cd=EXHAUSTED, rty_nmtm=4, rty_prnmnt_dt=NULL`, **행 삭제 안 됨**(이력 보존). `:79-84` 일치 |
| TC-BATCH-159 | PASS | [실동작-대체] 폴러 자체는 local batch off 로 미등록(§0). `claimAtomically` 와 **동일 술어**를 동시 2트랜잭션으로 재현 → A `UPDATE 1` / B `UPDATE 0`(B는 행 락 대기 후 갱신된 버전으로 WHERE 재평가). `:101-112` 일치. 노드 사멸분 회수는 TC-BATCH-164 가 실동작으로 뒷받침 |
| TC-BATCH-160 | PASS | [실동작] `LS_BAT_RTY_WTNG` 에 **2026-08-01 등록분 12행이 08-03 컨테이너 재빌드·재기동 후에도 잔존**(인메모리 큐 부재의 직접 증거). 코드에도 `ConcurrentHashMap` 큐 없음(`:16-34`) |
| TC-BATCH-161 | PASS | [실동작] `stts_cd='RETRYING'` 시드 상태에서 수동 재처리 호출 → **행(bat_rty_sn=59) 보존**(삭제 안 됨) + 이후 enqueue 가 같은 행을 갱신. `deleteIdleByRawSn` 의 `sttsCd <> 'RETRYING'` 술어 일치(`:141-147`) |
| TC-BATCH-162 | PASS | [정적] `clear` → `deleteByRawSn`(`:126-131`). 호출부는 `BatchOrchestrator.java:133`(성공 경로) — 성공 시 전체 삭제 확인 |
| TC-BATCH-163 | PASS | [실동작] rawSn=15(raw **FAILED** / work **APPROVED**) → **409** `"검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다."`. 로그 3줄(`work status transition skipped (review-owned)` → `BatchOrchestrator skipped` → `reprocess claim compensated (PROCESSING->FAILED)`) 관측. **재호출도 409 이고 raw 는 FAILED 유지 → 영구 PROCESSING 고착 없음.** `:89-101` · `BatchTransitionService.java:391-413` 일치 |
| TC-BATCH-164 | **PARTIAL** | [실동작] 스윕 tick 에서 `[BatchRetry][Reclaim] stale RETRYING reclaimed=2 exhausted=1 staleTimeoutMinutes=180`, 대상 행이 `RETRYING → PENDING` + `RTY_NMTM+1` + `rty_prnmnt_dt=now+60s` 로 복귀. **회수 기전은 기대대로 동작.** ⚠ 그러나 기대결과의 "정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님"은 **무조건 성립하지 않는다** — 처리 중 `MDFCN_DT` heartbeat 부재 + `BatchOrchestrator.process` 의 rawSn 단위 재진입 가드 부재 → **B-ISSUE-61** |
| TC-BATCH-165 | PASS | [실동작] `rty_nmtm=3 = max_rty_nmtm` 인 stale 행(bat_rty_sn=48) → 복귀 아닌 **`EXHAUSTED`**(`exhausted=1`), `rty_prnmnt_dt=NULL`. `reclaimStaleRetrying`(`RTY_NMTM < MAX`)/`exhaustStaleRetrying`(`>=`) 상호배타 술어 확인 |
| TC-BATCH-166 | PASS | [정적] 클램프 존재·안전 방향 확인. **단 카탈로그 기대값이 코드와 달라 정정함**(§4-①) — `<1`(0/음수)은 30 이 아니라 `DEFAULT_STALE_TIMEOUT_MINUTES`(**180**) 폴백, `1~29` 만 30 clamp (`BatchRetryStaleReclaimSweeper.java:94-97`) |
| TC-BATCH-167 | PASS | [실동작] 기동 로그 `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000 staleTimeoutMinutes=180` + 15분 주기 tick 이 **`batch-retry-stale-reclaim` 데몬 스레드**에서 실행됨을 로그로 관측. 코드에 `@Scheduled`/`@EnableScheduling` 없음(`:42-50,84,102-110`), 자기 토글 `authoring.batch.retry.stale-reclaim.enabled`(기본 true)만 참조 — `authoring.batch.enabled=false` 인 local 에서도 정상 가동한 것이 토글 독립의 실증 |
| TC-BATCH-168 | PASS | [정적] `run()` 이 `catch (Throwable)` 로 삼키고 `log.error(... e.getClass().getSimpleName())` 후 0 반환 — Error 계열도 스케줄러를 죽이지 않음, 예외 원문/스택 미노출(CWE-209). `:142-156` 일치 |

---

## 2. B-13. KPST 비식별 위탁·폴링 — 34건

> 이 스택에서 KPST 는 **mock-server(:9400) 실HTTP 연동**이며(`DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400`), 검증 중 `POST /project` → `GET /retrieve_progress` → 산출물 회수가 **실제로 왕복**했다(§5-④). self-fill 0건.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-060 | PASS | [실동작] `LS_DEIDENT_PROC_LOG` 신규 행이 **`REQ_DT == REG_DT`(원장 선커밋 시각)** 로 먼저 생기고 `DE_IDNTF_PJT_ID` 는 이후 ACK 로 채워짐(proc_log 74~77 실측). mock 로그 `project created prj_id=N name=rawN`. [정적] `KpstDeidentService.java:258-273,288,375-431` · `KpstSubmitOutcomeRecorder.java:44-57` · `KpstDeidentTxService.java:102-113`(`recordSubmitAck` 실제 :103-115) 모두 일치. `markKpstSubmitted` 메서드 부재 확인 |
| TC-DEID-061 | PASS | [정적] `buildProjectRequest` 의 `parent == null` → `INVALID_INPUT`(`:319-323`), 외부 호출 이전 동기 실패. CWE-22 근거 주석 일치 |
| TC-DEID-062 | PASS | [정적] 사전조건 실패만 `submit()` 동기 전파(`:292-303`, `failSubmit` 선커밋 후 `EXTERNAL_API_ERROR`). 실제 `createProject` 실패는 `subscribeSubmit` 의 `err ->` 핸들러 → `onSubmitFailed`(`KpstSubmitOutcomeRecorder.java:69-73`) → `failSubmit`(`KpstDeidentTxService.java:164-`) 로 **비동기 처리, 호출자 무예외** |
| TC-DEID-063 | PASS | [정적] `cleanExportDir`(`:488-527`) — ①리졸버 재계산 경로와 `equals` 일 때만 진행 ②`Files.list` 비재귀 ③`isRegularFile(NOFOLLOW_LINKS)` 로 심링크·디렉터리 제외 ④미존재 no-op ⑤IOException 시 경로 원문 미노출 후 위탁 진행 — 5중 가드 전부 확인 |
| TC-DEID-064 | PASS | [실동작] mock 완료 응답 후 `poll_stts_cd=DOWNLOADED`, `proc_stts_cd=SUCCEEDED`, `de_idntf_file_path_nm` 적재(proc_log 74~77). [정적] `allDatasetsCompleted`(AND, `:966-978`) → `downloadResult` → `finishDownloadAndComplete`(`:600-664`) |
| TC-DEID-065 | PASS | [정적] `anyDatasetFailed`(`:587`)가 `allDatasetsCompleted`(`:600`) **앞에서** 평가. `PROC_STATE_TERMINAL_FAILED`(`:97`) = {3,4,99} → `failPolling`/`failRedeidentCompletion` 즉시 종결(타임아웃 대기 없음) |
| TC-DEID-066 | PASS | [정적] `PROC_STATE_TERMINAL_FAILED` 집합에 99 포함(`:97-98`), null procState 는 실패로 보지 않음(`:956`) |
| TC-DEID-067 | PASS | [정적] `prjId == null` 분기(`:541-560`)가 `markTimeoutIfExpired` 가 **아니라** `withinSubmitAckGrace`(`:679-684`) 판정 → 유예 내면 외부 호출·카운터 소모 0, 만료 시 `txService.failSubmit(..., ACK_MISSING_CODE, ...)`. 유예값·`REQ_DT` null 시 fail-closed(즉시 회수) 확인 |
| TC-DEID-068 | PASS | [정적] `retrieveProgress` catch 경로(`:565-575`)에서도 `markTimeoutIfExpired` 호출 → 무기한 stuck 차단. 로그에 예외 클래스명만(CWE-209) |
| TC-DEID-069 | PASS | [정적] `downloadResult` 예외 → REDEIDENT `failRedeidentCompletion`(락 해제 포함) / 비-REDEIDENT `failPolling` 분기(`:611-625`) |
| TC-DEID-070 | PASS | [정적] `isUsableDeidFile` false → `failPolling`/`failRedeidentCompletion`, **`Y` 전이 없음**(`:634-648`). `finishDownloadAndComplete` 도 진입 전 `verifyDeidFile` 재확인 |
| TC-DEID-071 | PASS | [실동작] `poll_atmpt_cnt` 증가 관측(진행중 건). [정적] `recordPollingProgress` + `markTimeoutIfExpired`(`:666-668`) |
| TC-DEID-072 | PASS | [실동작] **§11 함정 직접 반증** — `orgnl_file_path_nm=/app/storage/raw/seed/clip-9101.mp4`(원본 입력 경로) ↔ `de_idntf_file_path_nm=/app/storage/raw/seed/111/deid/clip-9101-**mask**.mp4`. mock-server 코드(`routers/deid.py:104` `"fileName": ds.name`, 주석 "실서버 계약대로 원본 입력파일 경로")로 계약 일치 확인. **완료를 실패로 오종결하는 혼동 없음.** `toMaskName` 재부여 방지(`:812-822`)도 확인 |
| TC-DEID-073 | PASS | [정적] `scanSingleUsable`(`:833-857`) — 1개=회수(+`startsWith(dir)` 재단언) / 0개=null / 2개↑=`INVALID_INPUT`(모호 → 호출측 terminal) |
| TC-DEID-074 | PASS | [정적] `sanitizeFileName`(`:869-890`) — `Paths.get` 파싱 실패(NUL 등)를 `INVALID_INPUT` 으로 정규화(원문 미노출), `getFileName()` basename 추출 후 `/`·`\`·`..` 잔존 재거부 |
| TC-DEID-075 | PASS | [실동작] 폴링 대상 0인 tick 에서 mock-server 인바운드 0건(로그 대조). [정적] `KpstDeidentPollJob.java:93-98` `targets.isEmpty()` → DEBUG 후 return |
| TC-DEID-076 | PASS | [정적] 건별 `try/catch (RuntimeException)`(`:110-114`), 로그에 `errType=클래스명`만 |
| TC-DEID-077 | PASS | [정적] `@DisallowConcurrentExecution`(`:43`) + 클래스 javadoc(`:26-40`)이 "같은 노드 한정"을 명시하고 노드 간 방어를 `tryClaimPoll` 로 위임. 실제 배선 일치 |
| TC-DEID-078 | PASS | [실동작] 컨테이너 재기동(08-03 23:54) 이후에도 폴링이 DB 조회로 재개되어 신규 건을 완료 처리(proc_log 71~77). [정적] `findByPollSttsCdIn(statuses, page)`(`:93-94` · `LsDeidentProcLogRepository.java:55`), 인메모리 상태 없음 |
| TC-DEID-079 | PASS | [정적] `completeDeidentification` catch 에서 비-REDEIDENT 한정 `txService.markRawDeidentFailed`(REQUIRES_NEW, `KpstDeidentTxService.java:270-273`) 후 예외 재전파 — 메인 tx 롤백에 휩쓸리지 않음 |
| TC-DEID-080 | PASS | [정적] `@ConditionalOnProperty(prefix="kpst.deid", name="enabled", havingValue="true")`(`:69`) — 서비스·`KpstSubmitOutcomeRecorder`(`:33`)·`KpstDeidentPollTriggerConfig`(`:20`) 동일 조건. ⚠ 기본값 비대칭은 **B-ISSUE-64**(LOW) |
| TC-DEID-081 | PASS | [정적] `submit` 선두 `verifySourceOrFail`(`:286`) → `recordDeidentFailure`(REQUIRES_NEW 'F' 커밋) 후 `INVALID_INPUT`(`:446-466`). `createProject` 는 원장 발급 이전이라 미호출. 로그·예외에 경로 원문 없음 |
| TC-DEID-082 | PASS | [정적] `verifySourceExists=false` 시 `log.warn("... source existence guard disabled — 원본 미검증 위탁 rawSn={}")` 1줄 후 통과(`:447-450`) |
| TC-DEID-083 | PASS | [정적] `isUsableDeidFile` → `DeidentArtifactIntegrity.isValidVideoArtifact`(`:692-694`). 판정 = `isRegularFile(NOFOLLOW_LINKS)` + `size >= MIN_VIDEO_BYTES(512)` + 컨테이너 시그니처(ISO-BMFF box allowlist 8종 / MPEG-TS sync 3회 / RIFF·Matroska 등) → **18B 텍스트 스텁은 크기 하한에서 이미 false**. 근거 라인 정정함(§4-③) |
| TC-DEID-084 | PASS | [정적] `recheckAfterGrace`(`:707-730`) — **후보 파일이 존재할 때만** 유예(`:709` `!fileExists(candidate)` → 즉시 null), `Math.min(resultRecheckDelayMs, MAX_RESULT_RECHECK_DELAY_MS=5_000)` 상한, 1회 재판정 |
| TC-DEID-085 | PASS | [실동작] `claimForPoll` 과 **동일 술어 SQL** 을 동시 2트랜잭션으로 재현: 리스 만료 상태 → **`UPDATE 1`**, 즉시 재클레임 → **`UPDATE 0`**(리스 유효). [정적] `KpstDeidentPollJob.java:101-109` · `LsDeidentProcLogRepository.java:57-92` · `KpstDeidentTxService.java:61-66`(REQUIRES_NEW) 일치. `FOR UPDATE SKIP LOCKED` 미채택 근거(외부 HTTP·파일 I/O 가 tx 밖) 주석 확인 |
| TC-DEID-086 | PASS | [실동작] `POLL_STTS_CD='DOWNLOADED'` 로 전이시킨 뒤 클레임 → **`UPDATE 0`**(fail-closed). `LsDeidentProcLogRepository.java:84-89`(`IN ('WAITING','POLLING')`) 일치 |
| TC-DEID-087 | PASS | [정적] `leaseSeconds() = max(1, pollIntervalSec - 5)`(`:125-128`), 기본 주기 30s(`application.yml:509`) → 리스 25s < 주기 → 단일 노드 매 틱 재클레임. 경계 한계(리스보다 긴 배치)도 javadoc(`:57-74`)에 명시 |
| TC-DEID-088 | PASS | [실동작] `claimDownloadCompletion` 술어로 DOWNLOADED 행 재호출 → **`UPDATE 0`**(멱등). [정적] `LsDeidentProcLogRepository.java:94-125` + `KpstDeidentTxService.java:207-` 가 0행 시 후처리 전체 skip → 프레임 이중 attach 창 없음 |
| TC-DEID-089 | PASS | [정적] `finishDownloadAndComplete` 가 단일 REQUIRES_NEW — 후처리(`applyCompletion`) 예외 시 클레임 UPDATE 도 함께 롤백되어 재폴링 대상 유지 |
| TC-DEID-090 | PASS | [정적] `pollPage()`(`:118-124`) — `pollBatchSize < 1` 이면 `DEFAULT_BATCH_SIZE(200)`, 정렬 `pollLastDt asc nullsFirst` + `procLogSn asc`(기아 방지·결정적). `application.yml:513` 기본 200 |
| TC-DEID-091 | PASS | [정적] `recoveryDirs`(`:797-802`) — `deidVideoDirQuietly`(신 위치, 실패해도 예외 없음) + `{deid_base}/videos/{rawSn}`(구 위치), `LinkedHashSet` 로 순서·중복 제거 |
| TC-DEID-092 | PASS | [정적] 폴백 회수 성공 시 `log.warn("[KpstDeid] primary mask path miss — recovered by fallback scan rawSn={}")`(`:785`) — rawSn 만 노출. [실동작] 이번 회차 mock 산출물은 1차 경로가 적중해 해당 WARN 미발생(정상) |
| TC-DEID-093 | PASS | [정적] `DeidentFrameAttacher.isUsable` → `DeidentArtifactIntegrity.isValidVideoArtifact`(`:151-162`) — KPST 회수 경로와 **동일 단일 판정기** |

---

## 3. B-14. Quartz 클러스터링 / 인프라 / 헬스 — 10건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-170 | PASS | [실동작] `QRTZ_*` 테이블이 `klid_system`(controlDataSource) 에 실재하고 잡 5개가 실제 등록·발화 중(`qrtz_job_details`/`qrtz_triggers` 조회). [정적] `QuartzConfig.java:30-37` `SchedulerFactoryBeanCustomizer` 로 `@Qualifier("controlDataSource")` 명시 주입 + `application.yml:85-86` `driverDelegateClass=PostgreSQLDelegate`, `useProperties='true'` |
| TC-BATCH-171 | PASS | [정적] 공통 `application.yml:95` = `${QUARTZ_CLUSTERED:false}`, `application-stg.yml:11` / `application-prd.yml:13` = `${QUARTZ_CLUSTERED:true}` — 근거 라인 3건 모두 **정확**(드리프트 없음). ⚠ 실제 2노드 중복 발화 억제는 stg/prd 실기동이 필요해 이 스택에서 재현 불가(§0 지시에 따른 정적 판정). 기대결과의 "클러스터링은 트리거 중복만 막는다" 단서는 TC-BATCH-177 로 별도 검증 |
| TC-BATCH-172 | PASS | [정적] `AsyncBatchRunner.runAsync`(`:21-35`) `catch (Exception)` → `log.error` 후 정상 종료. SKIPPED 도 WARN 으로 가시화(무증상 종료 방지) |
| TC-BATCH-173 | PASS | [정적] `QuartzClusteringGuard.check()`(`@PostConstruct`, `:63-74`) → `verify(...)`(`:84-94`)가 `IllegalStateException` throw → 기동 실패. 메시지에 원인·해제법 포함, WARN 아님. [실동작] local 기동 로그 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]` 로 allowlist 통과 경로 관측 |
| TC-BATCH-174 | PASS | [정적] `DeployedEnvironmentDetector.isDeployed`(`:63-70`) = `!NON_DEPLOYED_PROFILES.containsAll(activeProfiles)` + 빈 목록/null → 배포. **반증 4케이스 전부 엄격 확인**: `local,prd`→containsAll false→거부 / `prd1`→미포함→거부 / `LOCAL`→대소문자 불일치→거부 / 미지정→`isEmpty()`→거부. 근거 라인 정정함(§4-②) |
| TC-BATCH-175 | PASS | [정적] `deployedEnvMarker` 가 `ENV` 를 trim+lowercase 후 `{stg,prd}` 매칭, `isDeployed` 가 **프로파일보다 먼저**(`:64-66`) 평가 → `SPRING_PROFILES_ACTIVE=dev` + `ENV=prd` 는 거부. `QuartzClusteringGuard.java:30-32,84-103` 이 규칙을 복제하지 않고 detector 에 위임(드리프트 방지) |
| TC-BATCH-176 | PASS | [실동작] 기동 로그가 실효값 기반으로 분기(local=off). [정적] `environment.getProperty(KEY_CLUSTERED, Boolean.class, FALSE)`(`:49,65`) — Quartz 실 프로퍼티 키를 직접 읽어 별도 미러 상수를 두지 않음 → `QUARTZ_CLUSTERED` 변경이 즉시 반영, 설정 우회 불가 |
| TC-BATCH-177 | **PARTIAL** | [정적] 기대결과 자체("두 방어는 서로 대체하지 않는다")는 **성립**하며 KPST 폴링(`tryClaimPoll`)·재시도 큐(`claimAtomically`)·export sweep 모두 원자 클레임을 보유. ⚠ 그러나 **배치 파이프라인 진입점 5곳이 서로 다른 자원을 클레임**하고 `BatchOrchestrator.process(rawSn)` 자체에 rawSn 단위 재진입 가드가 없어 전 진입점에 걸친 상호배제는 성립하지 않는다 → **B-ISSUE-62** |
| TC-BATCH-178 | PASS | [실동작] `batch-async-1` 스레드에서 rawSn=102 배치가 실행되는 것을 로그로 관측(단일 공유 풀 확인). [정적] `AsyncConfig.java:35-51` core2/max4/queue50/`CallerRunsPolicy`, `@Async("batchAsyncExecutor")` 소비자 다수. 근거 라인 정정함(§4-④) |
| TC-BATCH-179 | PASS | [실동작] `GET /api/actuator/health`(인증) → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`, 동시에 **mock-server 로그에 `"GET / HTTP/1.1" 200 OK`**(루트 핑) 기록 — `/health` 가 아닌 **루트** 핑 계약 실증. [정적] 3분기(mock UP / kpst 핑 / 미구성 DOWN fail-closed) + catch 에서 `e.getClass().getSimpleName()` 만 노출(`:13-40,75-113`, 파일 총 114줄 — 카탈로그 비고 정확) |

---

## 4. 카탈로그 정정 (담당 라인범위 317~394 내, 6셀)

| # | 대상 | 구 내용 | 정정 내용 | 유형 |
|:--:|---|---|---|---|
| ① | TC-BATCH-166 | 기대결과 "`stale-timeout-minutes=0/음수/5` → **`MIN_STALE_TIMEOUT_MINUTES`(30) 로 clamp**", 근거 `:64,96` | **`<1`(0·음수) → `DEFAULT_STALE_TIMEOUT_MINUTES`(180) 폴백 / `1~29` → 30 clamp** 2단 구조로 정정, 근거 `:59,64,94-97` | **기대값 오류** |
| ② | TC-BATCH-174 | 근거 `QuartzClusteringGuard.java:53,97-103` | `:55,96-103`(`SINGLE_NODE_PROFILES` 는 55행, `singleNodeAllowed` 는 96-103행) | 근거 드리프트 |
| ③ | TC-DEID-083 | 근거 `DeidentArtifactIntegrity.java:43-108` | `:49,87-110`(`MIN_VIDEO_BYTES` 49행, `isValidVideoArtifact` 87-110행) | 근거 드리프트 |
| ④ | TC-BATCH-178 | 근거 `AsyncConfig.java:32-47` | `:35-51`(`batchAsyncExecutor` 빈 정의 실제 범위) | 근거 드리프트 |
| ⑤ | TC-BATCH-164 | 기대결과 "정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님"(무조건 단언) | "**처리 시간 < 임계(기본 180분)일 때만 성립**" 조건 명시 + heartbeat/재진입 가드 부재 경고(B-ISSUE-61) | **기대값 오류(조건 누락)** |
| ⑥ | TC-BATCH-177 | 기대결과에 진입점별 클레임 자원 차이 미기재 | "배치 파이프라인은 진입점마다 서로 다른 자원을 클레임 — 전 진입점 상호배제 미성립(B-ISSUE-62)" 단서 추가, 근거에 `BatchTransitionService.java:111-122` 병기 | 계약 보강 |

> 나머지 근거 라인(±1~2행 편차: TC-BATCH-158 `:79-84`, TC-BATCH-161 `:141-147`, TC-DEID-060 `KpstDeidentTxService:102-113`, TC-BATCH-170 `QuartzConfig:31-38`)은 **인용 범위 안에 실제 코드가 들어 있어** 정정하지 않았다.

---

## 5. 이슈

### [B-ISSUE-61] TC-BATCH-164 — stale RETRYING 회수에 heartbeat 가 없어, 임계(기본 180분)를 넘긴 **정상 처리 중** 항목이 오회수된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: stale 회수는 **죽은 노드가 남긴** RETRYING 만 되살려야 한다. 살아서 처리 중인 항목을 뺏으면 같은 rawSn 파이프라인이 두 노드에서 동시에 돈다(라벨 이중 적재·재시도 카운터 이중 증가·프레임 중복 추출). 카탈로그 기대결과도 "정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님"을 단언한다.
- **현재 동작(이슈 내용)**: 회수 대상 판정축은 `MDFCN_DT`(= `claimAtomically` 가 찍는 **클레임 시각**)뿐이고, **처리가 진행되는 동안 이 값을 갱신하는 heartbeat 가 없다.**
  ```java
  // batch/retry/LsBatRtyWtngRepository.java:90-98 (후보 조회)
  SELECT q.BAT_RTY_SN FROM LS_BAT_RTY_WTNG q
   WHERE q.STTS_CD = 'RETRYING' AND q.MDFCN_DT <= :cutoff   // ← 클레임 시각 하나로만 판정
   ORDER BY q.MDFCN_DT ASC LIMIT :limit
  // batch/retry/BatchRetryStaleReclaimSweeper.java:144
  LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleTimeoutMinutes); // 정적 180분
  ```
  즉 안전성은 **"배치 1건 최장 처리 시간 < 180분"이라는 정적 가정**에만 의존한다(코드 주석도 `:33-35` 에서 이 가정을 명시). 가정이 깨지면 회수가 일어나고, 그 뒤를 받쳐줄 2차 가드도 없다 — `BatchOrchestrator.process` 진입 가드 `markRawDataProcessingBlocked`(`BatchTransitionService.java:111-122`)는 **검수 소유 작업 상태(PENDING/IN_REVIEW/APPROVED/REJECTED)만** 차단하고, `LS_DATA_RAW.DATA_STTS_CD` 가 이미 `PROCESSING` 이어도 그대로 통과시킨다(`LsDataRaw.markProcessing` 도 상태 머신 거부 없음).
- **재현/확인 경로**:
  ```sql
  -- 처리 중(RETRYING)인 항목의 MDFCN_DT 가 임계를 넘는 상황을 재현
  UPDATE ls_bat_rty_wtng SET stts_cd='RETRYING', rty_nmtm=1,
         mdfcn_dt = now() - interval '200 minutes' WHERE raw_sn = <처리중 rawSn>;
  -- 다음 스윕 tick(기본 15분) 대기 → 로그:
  --   [BatchRetry][Reclaim] stale RETRYING reclaimed=N ...   (실측 2026-08-04 00:29:20, reclaimed=2)
  -- 결과: STTS_CD='PENDING', RTY_NMTM+1 → 다른 노드가 즉시 같은 rawSn 을 재클레임 가능
  ```
  (본 검증에서 회수 자체는 실동작으로 확인했고, "처리 중 heartbeat 부재"는 코드 전수 조회로 확인 — `MDFCN_DT` 를 갱신하는 지점은 `claimAtomically`·`scheduleNext`·`markExhausted`·`@PreUpdate` 뿐이며 스텝 진행 중 호출되는 경로가 없다.)
- **영향**: 데이터 정합(라벨/프레임 이중 생성) + 자원(동일 영상 2벌 추론). CWE-362(Race Condition). 발생 조건은 "1건 처리 > 180분"이라 상시적이지는 않으나, 장시간 영상 + GPU 경합 + 외부 비식별 지연이 겹치면 도달 가능하며 **발생 시 조용히 진행**된다(경고 로그 없음).
- **수정 방향(제안)**: ①스텝 경계마다 `MDFCN_DT` 를 갱신하는 heartbeat(조건부 UPDATE, `STTS_CD='RETRYING'` + 소유 노드 식별자 일치)를 추가하고 임계를 heartbeat 주기의 배수로 잡는다. 또는 ②`BatchOrchestrator.process` 진입부에 rawSn 단위 재진입 가드(B-ISSUE-62 와 공통 해법)를 두어 회수가 오작동해도 이중 실행이 성립하지 않게 한다. ⚠ 구현은 하지 않았다.

### [B-ISSUE-62] TC-BATCH-177 — 배치 파이프라인 진입점이 **서로 다른 자원**을 클레임해, rawSn 단위 상호배제가 전 진입점에 걸쳐 성립하지 않는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: "클러스터링은 트리거 중복 발화만 막고, 잡 내부 레이스는 원자 클레임이 막는다"가 성립하려면, **같은 rawSn 파이프라인을 두 주체가 동시에 실행할 수 없어야** 한다.
- **현재 동작(이슈 내용)**: `orchestrator.process(rawSn)` 진입점은 5곳이고 각자 **다른 자원**을 선점한다.
  | 진입점 | 클레임 자원 |
  |---|---|
  | `BatchReprocessService.retry` (수동) | `LS_DATA_RAW.DATA_STTS_CD` FAILED→PROCESSING |
  | `BatchRetryQuartzJob.execute` (자동 재시도) | `LS_BAT_RTY_WTNG` **큐 행** PENDING→RETRYING |
  | `MarkingBatchBridge`→`AsyncBatchRunner` | 작업상태 `BATCH_QUEUED`(`tryClaimBatchQueued`) |
  | `BatchQuartzJob` | 자체 큐 dequeue |
  | `BatchDevTriggerController` (dev) | 없음 |

  자원이 다르므로 서로를 배제하지 못한다. 구체적 창:
  1. 자동 폴러가 큐 행을 `RETRYING` 으로 커밋한 직후(`BatchRetryQuartzJob.java:48`) ~ `markRawDataProcessing` 커밋 전(`BatchOrchestrator.java:112`) 사이에 REVIEWER 가 수동 재처리를 호출하면, `LS_DATA_RAW` 는 아직 `FAILED` 라 **수동 클레임이 성공**한다. 수동 경로의 `retryQueue.clearIfIdle` 은 `RETRYING` 을 **보존**하므로(설계상 정당) 폴러도 계속 진행 → **같은 rawSn 파이프라인 2벌 동시 실행**.
  2. B-ISSUE-61 의 오회수가 발생한 경우에도 동일 결과.
- **재현/확인 경로**: 창이 수~수십 ms 라 수작업 재현은 비결정적이다. 코드 경로로 확정 가능하다 — `BatchReprocessService.retry`(`:77`, `:84`)와 `BatchRetryQuartzJob.execute`(`:48`, `:55`)가 각각 다른 테이블에 CAS 를 걸고, 공통 관문인 `markRawDataProcessingBlocked`(`BatchTransitionService.java:111-122`)는 `PROCESSING` 을 차단 사유로 보지 않는다:
  ```java
  if (transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_PROCESSING)) { return true; } // 검수 소유만 차단
  videoRepository.findById(rawSn).ifPresentOrElse(LsDataRaw::markProcessing, ...);      // PROCESSING→PROCESSING 허용
  return false;                                                                          // → 진행
  ```
- **영향**: 데이터 정합(AUTO 라벨·프레임 이중 적재, `LS_LABEL_VERSION`/이력 오염) + 외부 위탁 중복(VLM 제출 2회). CWE-362. 단일 노드에서도 발생 가능(Quartz 클러스터링으로는 못 막는 축).
- **수정 방향(제안)**: `BatchOrchestrator.process` 진입부에 **rawSn 단위 실행권 클레임**을 단일 관문으로 추가한다 — 예: `LS_DATA_RAW.DATA_STTS_CD` 를 `PROCESSING` 으로 바꾸는 조건부 UPDATE 를 "현재 PROCESSING 이 아닐 때만" 성립시키고(0행이면 SKIPPED 반환), 기존 진입점별 클레임은 그대로 두어 방어심도를 유지한다. ⚠ 기존 SKIPPED 보상 경로(`releaseReprocessClaim`)와의 상호작용 검토 필요. 구현은 하지 않았다.

### [B-ISSUE-63] JVM(Asia/Seoul)과 DB(UTC)가 같은 `timestamp without time zone` 컬럼에 **9시간 다른 시각**을 쓴다
- **심각도**: LOW (현재 활성 결함 아님 — 잠재)
- **기대 동작(기대효과)**: stale/lease/backoff 판정은 전부 시각 비교다. 같은 컬럼을 읽고 쓰는 모든 주체가 **동일 기준 시계**여야 판정이 성립한다.
- **현재 동작(이슈 내용)**: 컨테이너 OS·DB 는 UTC 인데 JVM 만 KST 다.
  ```
  backend 컨테이너: date → "Mon Aug  3 03:30:24 PM UTC 2026", TZ 미설정
  DB:               SHOW timezone → Etc/UTC ; now() → 2026-08-03 15:30:24+00
  앱이 쓴 값:        SELECT max(mdfcn_dt) FROM ls_bat_rty_wtng → 2026-08-04 00:29:32   ← +9h
  ```
  원인: `backend/Dockerfile:42` `-Duser.timezone=Asia/Seoul` + `application.yml:48` `jdbc.time_zone: Asia/Seoul`. 반면 `LS_BAT_RTY_WTNG.REG_DT/MDFCN_DT`·`LS_DEIDENT_PROC_LOG.REQ_DT/REG_DT` 는 **`DEFAULT CURRENT_TIMESTAMP`(UTC)** 이고, 네이티브 쿼리 `LsBatRtyWtngRepository.insertIfAbsent`(`:45-48`)도 `CURRENT_TIMESTAMP` 를 직접 쓴다.
  현재는 무해하다 — `insertIfAbsent` 직후 같은 트랜잭션에서 `incrementAttempt()`/`scheduleNext()` 가 JVM 시각으로 덮어쓰고, `LsDeidentProcLog` 의 `reqDt` 도 Java 에서 `LocalDateTime.now()` 로 명시 세팅된다(`:127`). 그래서 **DB 기본값이 살아남는 경로가 현재는 없다.**
- **재현/확인 경로**: 위 3줄. 검증 중 실제로 이 스큐 때문에 "방금 클레임한 대조군 행"이 스윕 대상이 되어 **검증자가 오판할 뻔했다**(psql `now()` 로 시드 → JVM 기준 9시간 과거로 해석됨).
- **영향**: 잠재 — DB 기본값/네이티브 `CURRENT_TIMESTAMP` 로 채워진 행이 하나라도 JVM 판정에 노출되면 **항상 임계 초과(9시간 과거)** 로 판정된다. stale 회수·ACK 유예(`withinSubmitAckGrace`)·폴링 리스 전부 같은 축이다. 운영 SQL 로 수동 점검·복구할 때도 오판 위험이 상시 존재한다.
- **수정 방향(제안)**: ①컨테이너 `TZ=Asia/Seoul` 을 함께 지정해 DB 세션·OS·JVM 기준을 맞추거나, ②시각 컬럼을 `timestamptz` 로 전환하거나, ③네이티브 쿼리·DDL 기본값의 `CURRENT_TIMESTAMP` 를 전부 파라미터 바인딩(JVM 시각)으로 통일한다. ⚠ ②는 마이그레이션 범위가 커 별도 판단 필요. 구현은 하지 않았다.

### [B-ISSUE-64] `kpst.deid.enabled` 기본값이 빈 조건과 헬스 인디케이터에서 **비대칭**이다
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 프로퍼티를 읽는 두 지점의 미설정 기본값이 같아야, 설정 누락 시 "빈은 없는데 헬스는 있다고 판단"하는 어긋남이 생기지 않는다.
- **현재 동작(이슈 내용)**:
  ```java
  // batch/service/KpstDeidentService.java:69  (KpstSubmitOutcomeRecorder:33, KpstDeidentPollTriggerConfig:20 동일)
  @ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")   // matchIfMissing 없음 → 미설정 = false
  // observability/health/DeidentifyHealthIndicator.java:67
  @Value("${kpst.deid.enabled:true}") private boolean kpstEnabled;                        // 미설정 = true
  ```
  `application.yml:500` 이 `${KPST_DEID_ENABLED:true}` 로 항상 값을 주입하므로 실환경에서는 드러나지 않는다.
- **재현/확인 경로**: `kpst.deid.enabled` 프로퍼티를 완전히 제거한 컨텍스트에서 기동 → 서비스/WebClient 빈 미등록 + 헬스는 `kpstEnabled=true` 로 판단. 결과는 `kpstWebClient == null` 분기로 흘러 **DOWN(mode=unconfigured)** 이라 fail-closed 는 지켜진다.
- **영향**: 기능 영향 없음(fail-closed 로 수렴). 다만 "헬스 판정이 실행 경로와 동일 기준"이라는 클래스 javadoc(`:29-31`)의 단언이 문자 그대로는 성립하지 않아, 향후 분기가 늘 때 어긋날 표면이 된다.
- **수정 방향(제안)**: `DeidentifyHealthIndicator` 의 기본값을 `${kpst.deid.enabled:false}` 로 맞추거나, 조건부 빈 쪽에 `matchIfMissing = true` 를 명시해 한쪽으로 통일. ⚠ 구현은 하지 않았다.

### [B-ISSUE-65] 카탈로그 정합 — 기대값 오류 2건 + 근거 드리프트 3건 + 계약 누락 1건
- **심각도**: LOW (카탈로그 자체의 정합성 결함 — 오탐/거짓 PASS 유발)
- **기대 동작(기대효과)**: 케이스의 기대결과·근거가 현재 구현과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**: §4 표의 6건. 특히 ①·⑤ 는 **기대값 자체가 코드와 다르다**:
  - TC-BATCH-166: `0`/음수 입력의 실제 결과는 30 이 아니라 **180**(`BatchRetryStaleReclaimSweeper.java:94-97` `staleTimeoutMinutes < 1 ? DEFAULT_STALE_TIMEOUT_MINUTES : Math.max(MIN_STALE_TIMEOUT_MINUTES, ...)`). 기대값대로 검증하면 정상 코드가 FAIL 로 오판된다.
  - TC-BATCH-164: "정상 처리 중 항목은 대상 아님"이 무조건 단언이라, B-ISSUE-61 의 실제 리스크가 카탈로그상 **검증 대상에서 빠져 있었다**.
- **재현/확인 경로**: §4 표의 file:line 을 Read 하여 대조.
- **영향**: 다음 회차에서 정상 동작을 FAIL 로 오판(①) 하거나, 실재하는 동시성 리스크를 PASS 로 덮을 수 있다(⑤).
- **수정 방향(제안)**: **본 검증에서 담당 라인범위(317~394) 내 6셀을 직접 정정 완료**(§4). 프로덕션 코드는 수정하지 않았다.

---

## 6. 검증 중 만든 상태 변경 (전부 원복)

| 대상 | 변경 | 원복 |
|---|---|---|
| rawSn=46 | 재처리 5회 호출 → 파이프라인 1회 실행(FAILED 유지) + 재시도 큐 행 신규 생성(`bat_rty_sn=58`) | 큐 행 **DELETE**. raw/work 상태는 원래대로 `FAILED/FAILED` |
| rawSn=47 | 재처리 3회 호출 + `rty_nmtm` 시드(2→3, 3→4) → 큐 행 `bat_rty_sn=59` EXHAUSTED | 큐 행 **DELETE**. raw 상태 `FAILED` 유지 |
| rawSn=15 | 재처리 2회 호출(둘 다 409, 보상 롤백 관측) | 상태 변경 없음(raw `FAILED` / work `APPROVED` 그대로 — 보상 롤백이 스스로 원복) |
| 큐 행 `bat_rty_sn=48`(rawSn=49) | stale 시드(RETRYING·`rty_nmtm=3`·`mdfcn_dt` 과거) → 스윕이 EXHAUSTED 종결 | 원래 값(`PENDING`, `rty_nmtm=1`, `rty_prnmnt_dt=2026-08-01 23:20:07.12056`, `mdfcn_dt=2026-08-01 23:19:07.120698`)으로 **UPDATE 복원** |
| 큐 행 `bat_rty_sn=49`(rawSn=58) | 대조군 시드 → 스윕이 PENDING 복귀시킴(TZ 스큐로 인한 검증자 시드 오류, B-ISSUE-63) + CAS 테스트로 RETRYING 전이 | 원래 값(`PENDING`, `rty_nmtm=1`, `rty_prnmnt_dt=2026-08-01 23:21:13.112377`, `mdfcn_dt=2026-08-01 23:20:13.112464`)으로 **UPDATE 복원** |
| rawSn=96 | `ON CONFLICT` 동시 INSERT 테스트로 큐 행 1건 생성 | **DELETE** 완료(원래 없었음) |
| `proc_log_sn=8` | 클레임 술어 테스트 — **`BEGIN … ROLLBACK`** 트랜잭션 안에서만 수행 | 커밋 없음(변경 0) |
| rawSn=999888 | FK 위반으로 INSERT 실패 | 잔재 없음 |

- **rawSn=101(공용 정상 데이터)은 일절 건드리지 않았다.** 프로덕션·테스트·설정 코드 수정 0건(카탈로그 `docs/test-cases/B-batch-deidentify.md` 의 담당 라인범위 6셀만 정정).

---

## 7. 이전 회차 이슈 대조

| 이슈 | 출처 | 이번 회차 전수 재확인 결과 |
|---|---|---|
| **B-ISSUE-101 재처리 원자 클레임(CWE-362)** | 2차 타겟재검증 PASS(`targeted-B-DE.md` §2) | **해소 유지 — 전수 실동작으로 재확인.** 동시 5요청 → 200 정확히 1건, 나머지 4건 409 + `raw stage owned by another caller` WARN. "①이 0행일 때 원인 재판정 → 폴백 금지" 로직이 실제로 발화. 2차의 정적 판정을 실동작이 뒷받침함 |
| **B-ISSUE-83 stale RETRYING 무음 중단** | 1차 → 수정분 | **해소 확인** — `BatchRetryStaleReclaimSweeper` 실가동(15분 tick), 회수 2건·종결 1건 실관측. 단 heartbeat 부재라는 **새 잔여 리스크**를 이번에 식별(B-ISSUE-61) |
| **B-ISSUE-82 KPST 폴링 원자 클레임** | 1차 → 수정분 | **해소 확인** — `claimForPoll`(리스) / `claimDownloadCompletion`(완료=클레임) 술어를 동시 트랜잭션으로 실동작 재현, 1/0 확인 |
| **B-ISSUE-84 폴백 회수 관측성** | 1차 | **해소 확인** — `primary mask path miss — recovered by fallback scan` WARN 배선(`:785`). 이번 회차 산출물은 1차 경로 적중이라 미발화(정상) |
| **B-ISSUE-81(#8 Quartz 클러스터링)** | 1차 UNCERTAINTIES #8 | **해소 유지** — 공통 false / stg·prd true / `QuartzClusteringGuard` fail-closed. 근거 라인 3건 전부 정확 |
| B-ISSUE-81(스트리밍 심링크)·82·85 | 1차 | 본 파트 담당 범위(B-12~14) 밖 — part 다른 담당이 다룸 |
