# B 클러스터 part4 — B-12 재처리/재시도 큐 + B-13 KPST 비식별 위탁·폴링

> 회차: **2-1차** (2026-07-31) · 담당 범위: `docs/test-cases/B-batch-deidentify.md` **B-12(TC-BATCH-150~168, 19건)** + **B-13(TC-DEID-060~093, 34건)** = **53건**
> 이슈 ID 대역: **B-ISSUE-66~85**
> 판정 근거: 기동 중인 로컬 풀스택(backend `:18081/api`, mock-server `:9400`, PostgreSQL `public` 스키마) 위에서 **실 HTTP 요청 · DB 상태 · 컨테이너 로그** 관측 + 코드 정적 대조. **빌드/테스트 미실행**(프롬프트 금지).

## 0. 이번 파트에서 확보한 실동작 근거 (공통)

| # | 근거 | 출처 |
|:--:|------|------|
| E1 | KPST 위탁 논블로킹 제출 ACK — `[KpstDeid] submitted rawSn=4 prjId=1` / `rawSn=5 prjId=2` / `rawSn=6 prjId=3` (thread `kpst-submit-1`, 03:01:50) | backend 로그 |
| E2 | mock-server 실경유 — `[MOCK][KPST] project created prj_id=1 name=raw4` + `[MOCK][KPST] watermark burned … file=clip-9101-mask.mp4` + `production completed prj_id=1 files=1` | mock-server 로그 |
| E3 | 폴링 2틱 — 1틱(03:01:51) `polling targets count=3` (진행중 → 시도 증가), 2틱(03:02:21) `poll completed rawSn=4 prjId=1 datasetId=1` | backend 로그 |
| E4 | procLog 최종 상태 — `poll_stts_cd=DOWNLOADED / proc_stts_cd=SUCCEEDED / de_idntf_pjt_id=1 / poll_atmpt_cnt=1 / de_idntf_file_path_nm=/app/storage/raw/seed/4/deid/clip-9101-mask.mp4 / orgnl_file_path_nm=./storage/raw/seed/clip-9101.mp4` | DB `ls_deident_proc_log` |
| E5 | **fileName→산출물명 계약** — 원본 `clip-9101.mp4` → 회수 `clip-9101-mask.mp4`(`{stem}-mask{ext}`), 1차 경로 적중(폴백 WARN 0건) | E2+E4 대조 |
| E6 | **원본 가드/원본 보존** — 위탁·완료 후에도 원본 `clip-9101.mp4`(20,590B) 그대로 존재, 비식별본(50,854B)은 별도 `…/4/deid/` 하위 | 컨테이너 `stat`/`ls` |
| E7 | 산출물 시그니처 — 비식별본 선두 `00 00 00 20 66 74 79 70 69 73 6f 6d`(`ftyp isom`), 50,854B ≥ 512B | 컨테이너 `od -c` |
| E8 | **위탁 전 원본 실재 가드 실발화** — `[KpstDeid] submit rejected — source video missing rawSn=1/2/3`(03:00:47, 경로 원문 미노출) → 해당 3건 `de_ident_yn='F'` + `data_stts_cd=FAILED`(pipeline-drive.md 기록) | backend 로그 |
| E9 | stale 회수 스윕 기동 — `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000 staleTimeoutMinutes=180`(전용 데몬 스레드 `batch-retry-stale-reclaim` 활동 관측) | backend 로그 |
| E10 | 재시도 등록 실발화 — `[BatchRetry] enqueued rawSn=9901 attempt=1 delaySec=60` + DB 행 `stts_cd=PENDING, rty_nmtm=1, max_rty_nmtm=3` | backend 로그 + DB |
| E11 | 재처리 API 실응답 — rawSn=0→**400**, rawSn=99999→**404**, rawSn=4(COMPLETED)→**409**, rawSn=9901(FAILED)→**200 `{"stage":"FAILED"}`** + `[BatchReprocess] manual retry claimed rawSn=9901` | curl + backend 로그 |
| E12 | **원자 클레임 직렬 근사(단일 프로세스 2회 연속 실행)** — 아래 §0-1 | psql |

### 0-1. 원자 클레임/회수 SQL 직렬 근사 결과 (2노드 동시성 근사)

프로덕션 쿼리와 **동일 술어**를 psql 로 2회 연속 실행해 "두 번째는 0행"을 실측했다(임시 행 `raw_sn=9901` 사용, 검증 후 삭제 완료 — 잔여 데이터 없음).

| 대상 | 쿼리(술어) | 1회차 | 2회차 | 판정 |
|---|---|:--:|:--:|:--:|
| `claimAtomically` (TC-BATCH-159) | `SET STTS_CD='RETRYING' WHERE BAT_RTY_SN=? AND STTS_CD='PENDING'` | **1행** | **0행** | 직렬화 확인 |
| `reclaimStaleRetrying` (TC-BATCH-164) | `… WHERE STTS_CD='RETRYING' AND MDFCN_DT<=cutoff AND RTY_NMTM<MAX` | **1행**(PENDING 복귀, `RTY_NMTM 1→2`) | **0행** | 확인 |
| 정상 처리중 RETRYING 오회수 방지 (TC-BATCH-164) | `MDFCN_DT=now` 인 행에 동일 UPDATE | **0행** | — | 확인 |
| `exhaustStaleRetrying` (TC-BATCH-165) | `RTY_NMTM=3(=MAX)` 인 stale RETRYING | reclaim **0행** / exhaust **1행**(`EXHAUSTED`, `RTY_PRNMNT_DT=NULL`) | — | 확인 |
| `claimForPoll` (TC-DEID-085) | `WHERE POLL_STTS_CD IN('WAITING','POLLING') AND (POLL_LAST_DT IS NULL OR POLL_LAST_DT<leaseCutoff)` | **1행** | **0행** | 확인 |
| `claimForPoll` 리스 만료 재클레임 (TC-DEID-087) | `POLL_LAST_DT` 를 30s 전으로 되돌린 뒤 재실행(리스 25s) | **1행** | — | 확인 |
| `claimForPoll` fail-closed (TC-DEID-086) | `POLL_STTS_CD='DOWNLOADED'` / `'FAILED'` 로 바꾼 뒤 실행 | **0행** / **0행** | — | 확인 |
| `claimDownloadCompletion` (TC-DEID-088) | `SET POLL_STTS_CD='DOWNLOADED' … WHERE POLL_STTS_CD IN('WAITING','POLLING')` | **1행** | **0행** | 확인 |

> ⚠ **한계 명시**: 본 환경은 backend 단일 인스턴스라 **실제 2노드 동시 발화는 재현 불가**하다. 위는 "같은 술어를 두 번 실행하면 두 번째가 0행"이라는 **조건부 UPDATE(CAS)의 직렬 근사**이며, PostgreSQL 의 행 락 + 갱신 후 WHERE 재평가 규약에 근거해 동시 실행에서도 동일 결과가 보장된다. 실제 2노드 실증은 이번 회차 BLOCKED(→ B-ISSUE-70 참조).

---

## 1. B-12. 재처리 / 재시도 큐 (2노드 안전 · stale 회수) — 19건

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-BATCH-150 | PASS | [실동작] | `POST /v1/videos/0/batch/retry` → **400** `{"errorCode":"INVALID_INPUT","message":"retryBatch.rawSn: rawSn 은 1 이상이어야 합니다."}` — `@Min` 이 선차단(케이스 기대와 일치). 서비스 가드 `BatchReprocessService.java:66-69`(방어심도) 정적 확인. |
| TC-BATCH-151 | PASS | [실동작] | `POST /v1/videos/99999/batch/retry` → **404** `{"errorCode":"NOT_FOUND"}`. `BatchReprocessService.java:71-73` |
| TC-BATCH-152 | PASS | [실동작] | FAILED 임시행(9901) → **200** `{"rawSn":9901,"stage":"FAILED"}` + 로그 `[BatchReprocess] manual retry claimed rawSn=9901` → `MarkingLoadStep` 진입(=`orchestrator.process` 호출됨) → 마킹 0건으로 파이프라인 실패. `BatchReprocessService.java:77-87` |
| TC-BATCH-153 | PASS | [실동작] | rawSn=4(`data_stts_cd=COMPLETED`) → **409** `{"errorCode":"CONFLICT","message":"배치가 실패(FAILED)한 영상만 재처리할 수 있으며…"}`. 클레임 실패 → 이중 실행 차단 확인. |
| TC-BATCH-154 | PASS | [실동작]+[정적] | `tryClaimReprocessFromFailed` 가 ①`videoRepository.claimReprocessFromFailed`(LS_DATA_RAW) 성공 시 즉시 true, ②실패 시에만 `rawDataStatusRepository.claimReprocessFromFailed` — 둘 다 조건부 UPDATE. 실동작: 9901 은 `LS_RAW_DATA_STATUS` 행이 없는데도 클레임 성공(→ raw 우선 경로 확증) + 로그 `[BatchTransition] raw data status not found rawSn=9901`. **근거 라인 드리프트**: 카탈로그 `334-362` → 실제 `BatchTransitionService.java:334-347`. |
| TC-BATCH-155 | PASS | [실동작] | 파이프라인 실패 직후 로그 `[BatchRetry] enqueued rawSn=9901 attempt=1 delaySec=60` + DB 행 `stts_cd=PENDING, rty_nmtm=1, max_rty_nmtm=3, rty_prnmnt_dt=reg_dt+60s`. `insertIfAbsent`(RTY_NMTM=0 INSERT) → `findByRawSnForUpdate` → `incrementAttempt()`=1 경로 일치. `BatchRetryQueue.java:66-92` |
| TC-BATCH-156 | PASS | [정적] | `LsBatRtyWtngRepository.insertIfAbsent` = `INSERT … ON CONFLICT (RAW_SN) DO NOTHING`(native, 파라미터 바인딩) → **예외 없이 0행**으로 흡수 → 이어서 `SELECT … FOR UPDATE` 로 증가 직렬화. 같은 tx 안에서 UK 위반을 잡아 재시도하는 코드가 없어 **PG tx abort 함정 회피**. 테스트커버: `BatchRetryQueueIT#동시_최초등록시_UK위반이_전파되지_않는다`. ⚠ 실제 2노드 동시성은 재현 불가(B-ISSUE-70). |
| TC-BATCH-157 | PASS | [실동작]+[정적] | 실측 `attempt=1 → delaySec=60`. 식 `shift=Math.min(attempt-1,30); delaySec=initialDelaySec*(1L<<shift)`(`BatchRetryQueue.java:85-88`) → 60/120/240… + shift 30 캡(overflow 방지, 최대 ~34h). |
| TC-BATCH-158 | PASS | [정적] | `attempt > maxAttempts` → `entry.markExhausted()`(`sttsCd=EXHAUSTED`, `rtyPrnmntDt=null`) + `save` + `return false` — **삭제 아님(이력 보존)**. `BatchRetryQueue.java:78-84` / `LsBatRtyWtng#markExhausted`. 테스트커버: `BatchRetryQueueIT#최대시도_초과시_소진마킹되고_재폴링되지_않는다`. |
| TC-BATCH-159 | PASS | [실동작] | §0-1 — `PENDING→RETRYING` CAS 2회 실행 → **1행/0행**. `pollReady` 는 후보 10건(`CLAIM_CANDIDATES`)을 순회하며 첫 성공만 반환(`BatchRetryQueue.java:101-112`). 노드 사멸분은 stale 스윕이 회수(TC-BATCH-164 확인) → **B-ISSUE-83 해소 유지**. |
| TC-BATCH-160 | PASS | [실동작]+[정적] | 재시도 항목이 **DB 테이블 `LS_BAT_RTY_WTNG`** 에 영속됨을 실측(행 조회). 코드에 인메모리 큐 없음(`BatchRetryQueue` 필드 = repository + 설정값 2개뿐). 등록은 HTTP 스레드, 폴링은 Quartz 스레드로 **분리된 실행 주체가 DB 를 통해 인계**됨을 로그로 확인. |
| TC-BATCH-161 | PASS | [실동작]+[정적] | 2차 수동 재처리 시 기존 PENDING 행이 제거되고 다시 `attempt=1` 로 재등록됨(실측) → `clearIfIdle` 발화 확인. SQL 술어 `DELETE … WHERE RAW_SN=? AND STTS_CD <> 'RETRYING'`(`LsBatRtyWtngRepository:151-153`)로 **RETRYING(자동 폴러 부기) 보존**. **근거 라인 드리프트**: 카탈로그 `141-147` → 실제 `BatchRetryQueue.java:140-146`. |
| TC-BATCH-162 | PASS | [정적] | `clear(rawSn)` → `repository.deleteByRawSn`(무조건 삭제). 성공 경로 호출처 `BatchOrchestrator.java:133`(`retryQueue.clear(rawSn)`) 확인. `BatchRetryQueue.java:125-131` |
| TC-BATCH-163 | PASS | [정적] | `stage == SKIPPED` → `transitionService.releaseReprocessClaim(rawSn)`(PROCESSING→FAILED 조건부 UPDATE, raw·work 두 컬럼 모두 시도) → `CustomException(CONFLICT)`. 보상 없으면 stage 영구 PROCESSING 고착. `BatchReprocessService.java:89-100` · `BatchTransitionService.java:364-386`. 테스트커버: `BatchReprocessServiceTest#배치재처리_SKIPPED면_클레임을_보상롤백하고_409로_거부한다` / `#배치재처리_정상완료시에는_보상롤백을_하지_않는다`. ⚠ 실동작 재현엔 "검수 소유 작업상태" 영상이 필요해 이번엔 정적 판정(임시행엔 작업상태 행 없음). |
| TC-BATCH-164 | PASS | [실동작] | §0-1 — stale RETRYING(`MDFCN_DT` 60s 전, cutoff=now-30s)에 회수 UPDATE 2회 → **1행/0행**, `STTS_CD PENDING` 복귀 + **`RTY_NMTM 1→2`(죽은 시도 1회 계상)**. `MDFCN_DT=now` 인 정상 처리중 행은 **0행**(오회수 없음). UPDATE 술어에 cutoff 를 다시 실어 fail-safe 재판정(`LsBatRtyWtngRepository:113-121`). |
| TC-BATCH-165 | PASS | [실동작] | §0-1 — `RTY_NMTM=3 (=MAX_RTY_NMTM)` 인 stale RETRYING: reclaim **0행**(`RTY_NMTM<MAX` 위반) → exhaust **1행** → `EXHAUSTED` + `RTY_PRNMNT_DT=NULL`, `RTY_NMTM` 유지. 두 쿼리 술어가 상호배타라 한 행이 양쪽에 걸리지 않음. `LsBatRtyWtngRepository:132-138` |
| TC-BATCH-166 | PASS | [정적]+[실동작] | 생성자 `staleTimeoutMinutes < 1 ? DEFAULT(180) : Math.max(MIN_STALE_TIMEOUT_MINUTES(30), 설정값)` — **0/음수→180, 5→30 으로 clamp**. `BatchRetryStaleReclaimSweeper.java:64,94-96`(카탈로그 `64,96` 과 일치). 기동 로그 `staleTimeoutMinutes=180` 로 적용값 실측. |
| TC-BATCH-167 | PASS | [실동작] | `@Value("${authoring.batch.retry.stale-reclaim.enabled:true}")`(`:84`) 기본 true, `!enabled` 면 `start()` 가 즉시 반환해 미등록(`:102-105`). `@Scheduled`/`@EnableScheduling` **미사용** — `Executors.newSingleThreadScheduledExecutor` + 데몬 스레드 `batch-retry-stale-reclaim`(`:106-111`). 실측: 기동 로그 `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000` + 해당 이름의 스레드 활동 관측. 남의 토글(`authoring.batch.enabled` 등) 참조 0건. |
| TC-BATCH-168 | PASS | [정적] | `run()` 이 `catch (Throwable e)` 로 삼키고 `log.error("… reason={}", e.getClass().getSimpleName())` — **클래스명만**(CWE-209) 후 `return 0`. `RuntimeException` 이 아닌 `Throwable` 이라 Error 계열도 `scheduleWithFixedDelay` 영구 정지를 유발하지 않음. `BatchRetryStaleReclaimSweeper.java:142-157`(카탈로그 `142-156`, 1줄 드리프트). |

**B-12 집계: PASS 19 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**

### B-12 반증 시도 기록 (거짓 PASS 방지)

- **"성공 경로에서 RETRYING 이 고아로 남지 않는가"** → `BatchOrchestrator.java:133` 성공 시 `retryQueue.clear`, `:140` 실패 시 `enqueueIfRetryable` → `LsBatRtyWtng#scheduleNext` 가 `sttsCd = STATUS_PENDING` 으로 되돌림(실측: 2차 재처리 후 행이 PENDING). `BatchRetryQuartzJob` 은 SKIPPED 시 `clear`, 영구실패(NOT_FOUND) 시 `clear`, 그 외 예외 시 `enqueueIfRetryable` 재무장 — **RETRYING 잔존 경로는 "노드 사멸"뿐**이며 그것을 stale 스윕이 덮는다. 논리 폐곡선 확인.
- **"수동 재처리가 재시도 상한을 무력화하지 않는가"** → 실측상 2차 수동 재처리 후 `rty_nmtm` 이 다시 1 로 리셋된다(`clearIfIdle` 로 PENDING 행 삭제 → 다음 실패에서 신규 등록). 이는 `BatchRetryQueue#clearIfIdle` javadoc 의 **명시적 설계**("새 재시도 기회를 부여")이며 REVIEWER 수동 조작에 한정되므로 결함으로 보고하지 않는다.
- **`findStaleRetryingAnchors` 무인덱스 정렬(`ORDER BY MDFCN_DT`)** → `LsBatRtyWtngRepository:81-88` 에 **미추가 판단이 사전 문서화**되어 있고(RETRYING 은 과도 상태라 후보 집합이 작음, 운영 적체 관측 시 인덱스 추가), `LIMIT :limit` 로 상한이 있어 무제한 조회가 아니다 → 결함 아님(정보성).

---

## 2. B-13. KPST 비식별 위탁·폴링 (원본 가드 · 무결성 · 원자 클레임) — 34건

> ⚠ **선행 고지**: `KpstDeidentService.java` 가 **Phase C-2(논블로킹 제출)** 개편으로 750줄대 → **979줄**로 커져, 이 절 다수 케이스의 근거 `file:line` 이 전면 드리프트했다(→ **B-ISSUE-68**). 아래 "상세" 칸에는 **실제 라인**을 병기한다.

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-DEID-060 | PASS | [실동작] | 위탁 = ①`verifySourceOrFail` → ②`txService.issueSubmitLedger`(**선커밋**: `POLL_STTS=WAITING`, `prjId=null`) → ③`createProject` 논블로킹 구독 → ④ACK 시 `recordSubmitAck`(`claimSubmitAck`)로 `DE_IDNTF_PJT_ID` 기록. 실측: 로그 `[KpstDeid] submitted rawSn=4 prjId=1`, DB `poll_stts_cd` 진입 + `de_idntf_pjt_id=1`, **위탁 시점 `DE_IDNTF_YN` 미전이**(03:01:50 위탁 → 03:02:21 완료 시점에야 'Y'). 실제 라인 `KpstDeidentService.java:279-306` · `KpstDeidentTxService.java:83-113`(카탈로그 `218-278`). ⚠ 케이스가 지목한 `markKpstSubmitted(prjId)` 는 **프로덕션 호출처 0건**(엔티티+테스트에만 잔존) — 기능 동등하나 표기 무효(B-ISSUE-66). |
| TC-DEID-061 | PASS | [정적] | `buildProjectRequest`: `Paths.get(rawFilePathNm).getParent() == null` → `CustomException(INVALID_INPUT, "원본 파일 경로의 부모 디렉터리를 확인할 수 없습니다.")`. 경로 원문 미노출. 실제 `KpstDeidentService.java:318-323`(카탈로그 `235-241`). |
| TC-DEID-062 | PARTIAL | [정적] | **'F' 마킹은 성립, 동기 예외 전파는 조건부**로 축소됨. ①제출 **이전** 사전조건 실패(경로 손상·export 디렉터리 생성 실패) → `txService.failSubmit(SUBMIT_FAILED_CODE)`(REQUIRES_NEW 'F' 커밋) + `EXTERNAL_API_ERROR` **동기 전파**(`:294-303`) → 케이스 기대와 일치. ②그러나 **`createProject` 자체의 예외**는 Phase C-2 논블로킹화로 `subscribe(err → outcomeRecorder.onSubmitFailed)` 경로를 타 **호출자에게 예외가 전파되지 않는다**(`:421-431`). 'F' 마킹·원장 FAILED 는 유지. → **B-ISSUE-66** |
| TC-DEID-063 | PASS | [정적]+[실동작] | `cleanExportDir`(`:488-527`, 카탈로그 `342-390`): ①`exportDir.normalize()` 가 **리졸버로 재계산한** `deidVideoDir(rawSn, rawFilePathNm)` 와 `equals` 일 때만 진행(불일치 시 WARN 후 return), ②`Files.list`(비재귀) + `isRegularFile(NOFOLLOW_LINKS)` 필터 → **바로 아래 정규파일만 삭제**, ③심링크·하위 디렉터리 미추종, ④미존재/비디렉터리 no-op, ⑤IOException 은 클래스명만 로깅 후 위탁 진행. 실동작 방증: 실제 export 디렉터리에 하위 디렉터리 `.mock-tmp/` 가 **삭제되지 않고 잔존**(비재귀·디렉터리 미삭제 확인). |
| TC-DEID-064 | PASS | [실동작] | 2틱째(03:02:21) `allDatasetsCompleted`(AND) → `downloadResult` → `isUsableDeidFile` → `finishDownloadAndComplete` → 로그 `[KpstDeid] completed rawSn=4` + `poll completed rawSn=4 prjId=1 datasetId=1`, DB `poll_stts_cd=DOWNLOADED/proc_stts_cd=SUCCEEDED`, raw `de_ident_yn=Y`. `allDatasetsCompleted` 는 `allMatch(procState==2)` + null 은 미완료 취급(`:966-978`). 실제 `:600-664`(카탈로그 `438-490`). |
| TC-DEID-065 | PASS | [정적] | `anyDatasetFailed(progress)` 호출이 **`allDatasetsCompleted` 보다 앞**(`:587` vs `:600`) → 하나라도 3/4/99 면 즉시 종결. REDEIDENT 는 `failRedeidentCompletion`(락 해제 포함), 비-REDEIDENT 는 `failPolling` — 타임아웃(180분) 대기 없음. 실제 `:585-598`(카탈로그 `425-435`). |
| TC-DEID-066 | PASS | [정적] | `PROC_STATE_ERROR = 99` 가 `PROC_STATE_TERMINAL_FAILED = Set.of(3,4,99)` 에 포함(`:94-98`, 카탈로그 `92-95`). null/0/1 및 미지 코드는 진행중 유지(타임아웃 바운드). |
| TC-DEID-067 | PARTIAL | [정적] | **기대결과가 현행 구현과 불일치.** `prjId == null` 경로는 더 이상 `markTimeoutIfExpired` 를 부르지 않는다. Phase C-2 에서 "ACK 대기" 구간으로 재정의되어 ①`withinSubmitAckGrace`(기본 180s) 안이면 **아무것도 하지 않고 skip**(외부 호출 0, 시도 카운터 미소모), ②유예 초과면 `txService.failSubmit(ACK_MISSING_CODE)` 로 회수(`:541-561`, 카탈로그 `395-399`). **"외부 미호출"** 단언은 충족, **"markTimeoutIfExpired 호출"** 단언은 불성립. 무기한 stuck 차단이라는 원 의도는 ACK_MISSING 회수가 대체 충족. → **B-ISSUE-67** |
| TC-DEID-068 | PASS | [정적] | `retrieveProgress` catch 블록에서 **반드시** `txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes)` 호출 후 `[KpstDeid] poll retrieveProgress failed … errType={} timedOut={}`(예외 클래스명만, CWE-209). 5xx/`CallNotPermittedException`(서킷오픈) 모두 이 경로. 실제 `:562-576`(카탈로그 `401-415`). |
| TC-DEID-069 | PASS | [정적] | `downloadResult` 가 던지는 RuntimeException(빈/불량 fileName, 모호 다중 산출물)을 **완료 분기 안에서** catch → REDEIDENT 면 `failRedeidentCompletion`(락 해제 포함), 아니면 `failPolling` — 어느 쪽도 예외를 `pollOne` 밖으로 흘리지 않아 "잡이 swallow → 무한 재폴링" 이 차단됨. 실제 `:611-625`(카탈로그 `445-462`). |
| TC-DEID-070 | PASS | [정적] | `isUsableDeidFile` false 면(유예 재확인 실패 포함) `failRedeidentCompletion`/`failPolling` 으로 **'F' 종결, Y 전이 금지**. 방어심도로 `KpstDeidentTxService#verifyDeidFile`(`:432-441`)이 `finishDownloadAndComplete` 선두에서 한 번 더 판정해 콜백 경로 우회로도 막음. 실제 `:626-648`(카탈로그 `464-486`). |
| TC-DEID-071 | PASS | [실동작] | 1틱째(03:01:51, 아직 미완료) → `recordPollingProgress(procLogSn, ds.dsId())` + `markTimeoutIfExpired`. 실측 DB `poll_atmpt_cnt=1`(정확히 1틱분 증가) + 완료 후에도 1 유지. 실제 `:665-669`(카탈로그 `505-506`). |
| TC-DEID-072 | PASS | [실동작] | **함정 계약 정합 확인.** mock/실서버 `retrieve_progress.fileName` = **원본 입력 절대경로**(DB `orgnl_file_path_nm=./storage/raw/seed/clip-9101.mp4`), 산출물은 `export_path` 의 `{stem}-mask{ext}` → 실제 회수 `…/4/deid/clip-9101-mask.mp4`(DB 실측). `sanitizeFileName`(basename 추출, `:869-890`) → `toMaskName`(`:812-822`, `lastIndexOf('.')` 기준 stem/ext 분리, 확장자 없으면 `{name}-mask`, 이미 `-mask` 로 끝나면 **재부여 안 함**). **완료를 'F' 로 오종결하지 않음**을 실동작으로 확증. 카탈로그 `636-655,693-720` → 드리프트. |
| TC-DEID-073 | PASS | [정적] | `scanSingleUsable`(`:833-857`): 1개→`only.startsWith(dir.normalize())` 단언 후 반환 / 0개→`null`(호출측이 1차 경로로 종결) / **2개↑→`INVALID_INPUT`**(모호 → 호출측 terminal). 디렉터리 미존재·IOException 은 `null`. 카탈로그 `657-690` → 드리프트. |
| TC-DEID-074 | PASS | [정적] | `sanitizeFileName`(`:869-890`): null/blank→`INVALID_INPUT`, `InvalidPathException`(NUL바이트 등)→`INVALID_INPUT` **정규화**(입력 원문 포함 메시지 차단), `getFileName()` 으로 basename 추출(=순회 제거), 추출 결과에 `/`·`\`·`..` 잔존 시 거부. 예외 메시지에 외부 원문 미노출(CWE-209/CWE-22). 반증 시도: `"..//x.mp4"`→basename `x.mp4` 안전 / `"C:\a\x.mp4"`→`\` 잔존→거부 / `"/"`→basename null→blank→거부 — **경로 탈출 성립 케이스 미발견**. 카탈로그 `693-720` → 실제와 동일 구간 아님(드리프트). |
| TC-DEID-075 | PASS | [실동작] | 대상 0건 틱마다 `log.debug("[KpstDeidPoll] no pending poll target — skipping tick")` 후 즉시 반환(외부 호출 없음). 실측: 03:02:51~03:10:21 사이 전 틱이 해당 DEBUG 만 남기고 mock-server 인바운드 0건. `KpstDeidentPollJob.java:93-98`(카탈로그와 **정확히 일치**). |
| TC-DEID-076 | PASS | [정적] | for 루프 내부 `try { … } catch (RuntimeException e) { log.warn("… errType={}", e.getClass().getSimpleName()); }` → 다음 건 계속. rawSn/prjId/예외 클래스명만 출력(CWE-209). `KpstDeidentPollJob.java:110-114`(일치). |
| TC-DEID-077 | PASS | [정적]+[실동작] | `@DisallowConcurrentExecution`(`KpstDeidentPollJob.java:43`) = **같은 스케줄러 인스턴스** 직렬화. 노드 간 중복은 **리스 기반 원자 클레임**(`tryClaimPoll`, `:104-108`)이 Quartz 설정과 무관하게 차단 — §0-1 에서 클레임 술어 1행/0행 실측. 클래스 javadoc `:26-40` 에 이 역할 분담이 명시(일치). |
| TC-DEID-078 | PASS | [실동작] | 폴링 대상은 매 틱 `procLogRepository.findByPollSttsCdIn(POLL_TARGET_STATUSES, pollPage())` **DB 조회**(`KpstDeidentPollJob.java:93-94` · `LsDeidentProcLogRepository.java:55` — 카탈로그와 일치). 인메모리 상태 필드 0개. 실측: 위탁 직후 다른 스레드(Quartz worker)가 DB 에서 3건을 집어 `polling targets count=3` — 프로세스 재기동 없이도 **상태가 DB 에만 있음**을 방증. |
| TC-DEID-079 | PASS | [정적] | `KpstDeidentService#completeDeidentification`(`:898-911`)이 예외를 catch → `if (!isRedeidentLog(rawSn)) txService.markRawDeidentFailed(rawSn)`. `markRawDeidentFailed` 는 별도 빈의 **`REQUIRES_NEW`**(`KpstDeidentTxService.java:269-273`)라 메인 tx 롤백에 휩쓸리지 않음. 카탈로그 `722-746`/`116-120` → 드리프트. |
| TC-DEID-080 | PASS | [정적] | `@ConditionalOnProperty(prefix="kpst.deid", name="enabled", havingValue="true")` 3곳 확인 — `KpstDeidentService.java:69` · `KpstDeidentTxService.java:37` · `KpstDeidentPollTriggerConfig.java:21`(JobDetail·Trigger 빈 포함). 토글 off 면 서비스·Tx서비스·트리거 전부 미등록. 테스트커버 `DeidentifyStepKpstDisabledIntegrationTest`. 실환경은 `KPST_DEID_ENABLED=true` 라 off 상태 실관측은 불가(정적 판정). |
| TC-DEID-081 | PASS | [실동작] | **실발화 확인.** `verifySourceOrFail`(`:446-466`)이 `Files.isRegularFile` 로 원본 실재를 확인 → 부재 시 `batchTransitionService.recordDeidentFailure(rawSn, "KPST_SOURCE_MISSING", "source not found")`(**REQUIRES_NEW 로 'F' 커밋** + FAIL procLog 신규 저장, `BatchTransitionService.java:217-235`) 후 `INVALID_INPUT`. 호출 위치가 `issueSubmitLedger`·`createProject` **이전**(`:286`)이라 외부 미호출. 실측 로그 `[KpstDeid] submit rejected — source video missing rawSn=1/2/3` — **경로 원문 없음**(rawSn 만). 해당 3건은 `de_ident_yn='F'`+`FAILED` 로 남고 `MARKING_READY` 미전이 → 구 결함(18B 스텁으로 'Y') **회귀 없음**. 카탈로그 `225,300-320` → 드리프트. |
| TC-DEID-082 | PASS | [정적] | `if (!verifySourceExists) { log.warn("[KpstDeid] source existence guard disabled — 원본 미검증 위탁 rawSn={}", rawSn); return; }` — 영상당 1줄 WARN 후 통과(`:447-451`). 기본값 `true`(fail-closed, `@Value("${kpst.deid.verify-source-exists:true}")` `:184-185`). 카탈로그 `302-305` → 드리프트. |
| TC-DEID-083 | PASS | [실동작]+[정적] | `isUsableDeidFile` → `DeidentArtifactIntegrity.isValidVideoArtifact`(`:692-694`) **단일 원천**. 판정 3중: ①`isRegularFile(NOFOLLOW_LINKS)` ②`size >= MIN_VIDEO_BYTES(512)` ③컨테이너 시그니처(ISO-BMFF box allowlist / EBML / RIFF / MPEG 스타트코드 / **MPEG-TS 는 188B 간격 0x47 3회 연속** / FLV / OggS / ASF). 18B 텍스트 스텁·`"GET /…"` 로그파일 오판 모두 차단. 실측: 실제 산출물 선두 `ftyp isom` + 50,854B → 통과. `"junk"` 박스 제외(오판 표면 축소) 확인. `DeidentArtifactIntegrity.java:43-206`(카탈로그 `43-108`, 파일이 더 김). |
| TC-DEID-084 | PASS | [정적] | `recheckAfterGrace`(`:707-730`): `resultRecheckDelayMs <= 0 \|\| !fileExists(candidate)` → **즉시 null(유예 없음)** = "파일이 아예 없으면 즉시 종결". 후보 존재 시 `Math.min(resultRecheckDelayMs, MAX_RESULT_RECHECK_DELAY_MS=5_000)` 만큼 sleep 후 `downloadResult` 재산출 → **1회만** 재판정. 호출은 `:626-633` 에서 1회. 카탈로그 `464-486,531-556` → 드리프트. |
| TC-DEID-085 | PASS | [실동작] | §0-1 — `claimForPoll` 술어(`POLL_STTS_CD IN('WAITING','POLLING')` + `POLL_LAST_DT IS NULL OR < leaseCutoff`) 2회 실행 → **1행/0행**. 잡은 매 틱 `leaseCutoff = now - leaseSeconds()` 를 계산해 건별 `tryClaimPoll` 성공분만 폴링(`KpstDeidentPollJob.java:99-108`). `FOR UPDATE SKIP LOCKED` 미채택 사유(외부 HTTP·파일 I/O 가 tx 밖)가 `LsDeidentProcLogRepository.java:57-92` 에 명문화 — **근거 라인 3곳 모두 카탈로그와 일치**. → **UNCERTAINTIES #21 해소 상태 유지 재확인** |
| TC-DEID-086 | PASS | [실동작] | §0-1 — `POLL_STTS_CD` 를 `DOWNLOADED` / `FAILED` 로 바꾼 뒤 클레임 UPDATE → **각각 0행**(fail-closed). 술어 위치 `LsDeidentProcLogRepository.java:86-88`(카탈로그 `84-89`, 2줄 이내 드리프트). |
| TC-DEID-087 | PASS | [실동작]+[정적] | `leaseSeconds() = Math.max(MIN_LEASE_SEC(1), pollIntervalSec - LEASE_SLACK_SEC(5))` → 기본 30s 주기에서 **25s 리스**(`KpstDeidentPollJob.java:125-128`). 실측: 03:01:51 클레임 → 30초 뒤 03:02:21 틱에서 `POLL_LAST_DT(03:01:51) < leaseCutoff(03:01:56)` 로 **재클레임 성공**(단일 노드 매 틱 폴링 유지). §0-1 리스 만료 재클레임 1행. 크래시 시 리스 만료 자동 회수(별도 잠금 컬럼·회수 잡 없음) 확인. 경계 한계(HTTP 지연 누적 시 1틱 skip)는 코드 주석 `:63-70` 에 사전 문서화됨. 카탈로그 `57-74,118-124` 중 후자는 `pollPage()` 구간(경미 드리프트). |
| TC-DEID-088 | PASS | [실동작] | §0-1 — `claimDownloadCompletion`(`WHERE POLL_STTS_CD IN('WAITING','POLLING')`) 2회 → **1행/0행**. `finishDownloadAndComplete` 는 `!= 1` 이면 `[KpstDeid] completion already applied — skip duplicate` 로 **즉시 반환**하여 프레임 attach·Y 전이·락 해제·알림을 건너뜀 → **프레임 이중 attach 창 없음**. `LsDeidentProcLogRepository.java:94-125`(일치) · 실제 후처리 `KpstDeidentTxService.java:206-223`(카탈로그 `78-95` → 드리프트). → **UNCERTAINTIES #21 해소 상태 유지 재확인** |
| TC-DEID-089 | PASS | [정적] | `finishDownloadAndComplete` 는 단일 `REQUIRES_NEW` 안에서 `claimDownloadCompletion`(클레임) → `applyCompletion`(후처리)을 수행하므로, 후처리 예외 시 **클레임 UPDATE 도 함께 롤백** → `POLL_STTS_CD` 가 WAITING/POLLING 으로 남아 재폴링 대상 유지(fail-safe). 비-REDEIDENT 는 예외 재전파(`KpstDeidentService.java:663`), REDEIDENT 는 `failRedeidentCompletion`(별도 REQUIRES_NEW)로 terminal 종결해 무한 재폴링/영구잠금을 끊음. 실제 `KpstDeidentTxService.java:206-223`(카탈로그 `78-95` → 드리프트). |
| TC-DEID-090 | PASS | [실동작] | `pollPage()`: `size = pollBatchSize < 1 ? DEFAULT_BATCH_SIZE(200) : pollBatchSize`, 정렬 `Sort.Order.asc("pollLastDt").nullsFirst()` **+ `procLogSn` 오름차순**(동률 결정성) — 미폴링 우선 → 오래 대기 순(기아 방지). `KpstDeidentPollJob.java:54-55,88-89,118-123`(카탈로그 `54-55,88-89,118-124` 와 사실상 일치). 실측: `polling targets count=3` 로 상한 내 조회, 클레임된 건은 `POLL_LAST_DT` 갱신으로 후순위 이동. |
| TC-DEID-091 | PASS | [실동작]+[정적] | `recoveryDirs`(`:797-802`): `LinkedHashSet` 에 ①`artifactRootResolver.deidVideoDirQuietly(rawSn, orgnlFilePathNm)`(**Optional — 도출 실패해도 예외 없이 건너뜀**) ②`baseDeidentifiedPath/videos/{rawSn}` 순으로 담아 `List.copyOf` → **신 위치 우선 + 구 위치 폴백**, 신 위치 실패해도 구 위치 시도 지속. 실측: 신 위치(co-locate `…/seed/4/deid/`)에서 1차 적중. 카탈로그 `621-634` → 드리프트. |
| TC-DEID-092 | PASS | [정적] | 폴백 스캔 회수 성공 시 `log.warn("[KpstDeid] primary mask path miss — recovered by fallback scan rawSn={}", rawSn)` — **경로/파일명 원문 미노출, rawSn 만**(`:782-786`). 계약 드리프트가 무음 통과하지 않음(B-ISSUE-84 관측성 유지). 실동작 방증(음성 대조): 이번 구동은 1차 경로가 적중해 이 WARN 이 **0건**이며, 실제 회수 경로가 `{stem}-mask{ext}` 규칙과 일치함을 DB 로 확인. 카탈로그 `601-616` → 드리프트. |
| TC-DEID-093 | PASS | [정적] | `DeidentFrameAttacher#isUsable(Path)` → `DeidentArtifactIntegrity.isValidVideoArtifact(video.toString())`(`DeidentFrameAttacher.java:152-161`), 호출 지점 `:99`. 폴링(`KpstDeidentService#isUsableDeidFile`)·완료 게이트(`KpstDeidentTxService#verifyDeidFile`)·attach 3경로가 **동일 단일 원천**을 사용 — 판정이 두 벌로 갈라지지 않음. 카탈로그 `73-93` → 실제 `152-161`(드리프트). |

**B-13 집계: PASS 32 / FAIL 0 / PARTIAL 2 / BLOCKED 0 / N/A 0 / 확인필요 0**

### B-13 반증 시도 기록 (거짓 PASS 방지)

- **self-fill 탐색** → 비식별 산출물 경로·파일명·`prjId`·`datasetId`가 모두 **mock-server 응답 기원**임을 로그 페어로 확인(E1~E5). 저작도구가 자체 생성한 값은 `project_name = "raw"+rawSn`(우리 소관 입력)과 `export_path` 디렉터리(우리 소관, 파일명은 KPST 결정)뿐. **self-fill 결함 0건.**
- **거짓 완료('Y' 위장) 경로 탐색** → ①위탁 전 원본 실재 가드(실발화 확인) ②`isUsableDeidFile`(폴링) ③`verifyDeidFile`(Y 전이 직전, 콜백 포함) ④`DeidentFrameAttacher.isUsable` — 4중이며 모두 동일 판정기. 우회 가능한 진입점 미발견.
- **경로 순회(CWE-22) 반증** → `sanitizeFileName` 에 `"..//x"`·`"C:\a\x"`·`"/"`·`"."` 등 변형을 대입 추적했으나 탈출 성립 없음. 회수 경로는 `resolveUnder`/`startsWith(dir)` 로 재단언.
- **삭제 가드 반증** → `cleanExportDir` 이 "설정 루트 하위 어디든"이 아니라 **리졸버 재계산 경로와 `equals`** 일 때만 동작하고 비재귀·NOFOLLOW 라, 원본 디렉터리(`…/seed/`)나 프레임 경로(`…/frames/`)를 건드릴 수 없음. 실측상 원본 3개 파일 전부 보존(E6).
- **2노드 동시성** → 단일 노드라 실증 불가. 조건부 UPDATE 8종을 §0-1 직렬 근사로 전수 실행해 "두 번째 0행"을 확인했다(→ B-ISSUE-70 에 한계 명시).

---

## 3. 집계

| 절 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-12 재처리/재시도 큐 | 19 | 19 | 0 | 0 | 0 | 0 | 0 |
| B-13 KPST 위탁·폴링 | 34 | 32 | 0 | 2 | 0 | 0 | 0 |
| **합계** | **53** | **51** | **0** | **2** | **0** | **0** | **0** |

- **self-fill 결함: 0건** (KPST 연동 전 구간 mock-server 실경유 확인)
- **보안 결함(CWE) 신규 발견: 0건**
- 확정 정책 재확인: **UNCERTAINTIES #21(`tryClaimPoll` + `claimDownloadCompletion`) 해소 상태 유지** — 실동작으로 재확증(TC-DEID-085/088).
- 알려진 함정 재확인: **KPST `fileName`=원본 입력경로 / 결과물=`{stem}-mask{ext}`** 매핑이 코드·실동작 모두 정합. 완료를 `'F'` 로 오종결하는 회귀 없음(TC-DEID-072).
- 알려진 함정 재확인: **PostgreSQL unique 위반 tx abort** — 재시도 큐 등록이 `ON CONFLICT DO NOTHING` 원자 upsert + `REQUIRES_NEW` 로 격리되어 있어 같은 tx 내 예외 재시도 경로 없음(TC-BATCH-156).

---

## 4. 이슈 기록

### [B-ISSUE-66] TC-DEID-062 (부수: TC-DEID-060) — Phase C-2 논블로킹 제출로 "createProject 예외 동기 전파" 계약이 소멸했고 카탈로그 기대결과가 무효화됨
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그 TC-DEID-062 는 "위탁 실패(createProject 예외) → `procLog.fail` + `markDeidentified("F")` + **예외 전파**(클래스명만)"를 단언한다. 호출자(적재 배치/재비식별 요청)가 위탁 실패를 **동기적으로** 인지해 자기 흐름을 중단할 수 있어야 한다는 취지다. TC-DEID-060 은 `markKpstSubmitted(prjId)` 호출을 단언한다.
- **현재 동작(이슈 내용)**: 제출이 논블로킹(Phase C-2)으로 바뀌어 `createProject` 의 실패는 구독 콜백으로만 도달한다.
  ```java
  // KpstDeidentService.java:421-431
  private void subscribeSubmit(Long rawSn, Long procLogSn, KpstProjectRequest projectReq) {
      try {
          kpstClient.createProject(projectReq)
                  .subscribe(resp -> SubmitSignalDispatch.run(..., () -> outcomeRecorder.onAccepted(...)),
                             err  -> SubmitSignalDispatch.run(..., () -> outcomeRecorder.onSubmitFailed(...)));
      } catch (RuntimeException e) { outcomeRecorder.onSubmitFailed(rawSn, procLogSn, e); }
  }
  ```
  즉 `submit()` 은 예외 없이 반환하고, 'F' 마킹·원장 FAILED 는 `KpstDeidentTxService#failSubmit`(`:163-184`)이 비동기로 커밋한다. **동기 `EXTERNAL_API_ERROR` 전파가 남는 것은 제출 이전 사전조건 실패뿐**(`:294-303`). 또한 TC-DEID-060 이 지목한 `LsDeidentProcLog#markKpstSubmitted` 는 **프로덕션 호출처 0건**(엔티티 정의 + 테스트 5개 파일에만 잔존)이며 실제 ACK 기록은 `claimSubmitAck` 조건부 UPDATE 가 담당한다.
- **재현/확인 경로**: `grep -rn "markKpstSubmitted" backend/src/main` → 엔티티 정의 1건뿐(호출 0). `KpstDeidentService.java:279-306`(submit) 및 `:421-431`(subscribeSubmit) 정독.
- **영향**: 기능 결함 아님('F' 마킹·원장 종결·락 해제는 모두 보존되고 오히려 스레드 점유가 제거됨). 다만 **카탈로그 기대결과가 현행 계약과 어긋나** 다음 회차 검증자가 "예외가 안 온다 = 결함"으로 오판할 위험이 있고, `markKpstSubmitted` 는 dead method 로 남아 있다.
- **수정 방향(제안)**: ①`docs/test-cases/B-batch-deidentify.md` TC-DEID-062 기대결과를 "사전조건 실패=동기 `EXTERNAL_API_ERROR` 전파 / `createProject` 실패=비동기 `failSubmit`('F'+원장 FAILED+REDEIDENT 락 해제), 동기 전파 없음"으로 분리 기술, TC-DEID-060 의 `markKpstSubmitted(prjId)` 표기를 `issueSubmitLedger` + `claimSubmitAck` 로 교체. ②`LsDeidentProcLog#markKpstSubmitted` 의 존치/삭제를 별도 정리 사이클에서 결정(테스트 5파일 동반 정리 필요).

### [B-ISSUE-67] TC-DEID-067 — `prjId==null` 폴링 경로가 `markTimeoutIfExpired` 가 아니라 "ACK 유예 → ACK_MISSING 회수"로 대체됨 (기대결과 무효)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그는 "prjId null(위탁 미완) → `markTimeoutIfExpired` 만 수행, 외부 미호출"을 단언한다. 취지는 **prjId 없는 건이 무기한 stuck 되지 않게 한다**는 것.
- **현재 동작(이슈 내용)**:
  ```java
  // KpstDeidentService.java:541-561 (pollOne)
  if (prjId == null) {
      if (withinSubmitAckGrace(procLog)) {          // 기본 180s
          log.debug("[KpstDeid] skip poll — awaiting submit ack rawSn={}", rawSn);
          return;                                    // 외부 호출 0건, 시도 카운터 미소모
      }
      boolean reclaimed = txService.failSubmit(procLogSn, rawSn, ACK_MISSING_CODE, "submit ack not received");
      log.warn("[KpstDeid] submit ack missing — reclaimed rawSn={} applied={}", rawSn, reclaimed);
      return;
  }
  ```
  `markTimeoutIfExpired` 호출은 **없다**. "외부 미호출" 단언만 성립한다.
- **재현/확인 경로**: `KpstDeidentService.java:537-561` 정독. 동작 확인은 `kpst.deid.submit-ack-grace-sec` 를 짧게 두고 ACK 를 유실시키면 `KPST_ACK_MISSING` 원장이 남는다(테스트커버 `KpstSubmitAsyncCommitIT#ACK가_끝내_오지_않으면_폴러가_유예만료후_ACK_MISSING으로_회수한다`).
- **영향**: 기능상 원 취지(무기한 stuck 차단)는 **더 정확하게** 충족된다 — 구 방식은 ACK 대기 구간에서 시도 카운터/타임아웃 예산을 헛되이 소모했다. 문서 정합 이슈에 한정.
- **수정 방향(제안)**: TC-DEID-067 기대결과를 "유예 안=완전 no-op(외부 호출 0·카운터 미소모) / 유예 초과=`failSubmit(ACK_MISSING_CODE)` 회수"로 갱신하고, 근거를 `KpstDeidentService.java:541-561` + `withinSubmitAckGrace(:679-684)` 로 교체.

### [B-ISSUE-68] TC-DEID-060~093 다수 — `KpstDeidentService.java` 근거 `file:line` 전면 드리프트 (카탈로그 정합성 결함)
- **심각도**: MEDIUM (카탈로그 신뢰도)
- **기대 동작(기대효과)**: 케이스의 근거 `file:line` 은 클릭 즉시 해당 로직으로 이동해야 하며, 회차 간 회귀 대조의 기준점이 된다.
- **현재 동작(이슈 내용)**: Phase C-2(논블로킹 제출) 도입으로 `KpstDeidentService.java` 가 **979줄**로 커지면서 B-13 근거 라인이 대부분 어긋난다. 실측 대조표:

  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | TC-DEID-060 | `218-278` | `279-306` (+ `KpstDeidentTxService.java:83-113`) |
  | TC-DEID-061 | `235-241` | `318-323` |
  | TC-DEID-062 | `279-283` | `294-303`(동기) / `421-431`(비동기) |
  | TC-DEID-063 | `342-390` | `488-527` |
  | TC-DEID-064 | `438-490` | `600-664` |
  | TC-DEID-065 | `425-435` | `585-598` |
  | TC-DEID-066 | `92-95` | `94-98` |
  | TC-DEID-067 | `395-399` | `541-561` |
  | TC-DEID-068 | `401-415` | `562-576` |
  | TC-DEID-069 | `445-462` | `611-625` |
  | TC-DEID-070 | `464-486` | `626-648` |
  | TC-DEID-071 | `505-506` | `665-669` |
  | TC-DEID-072 | `636-655,693-720` | `812-822`(toMaskName), `869-890`(sanitize) |
  | TC-DEID-073 | `657-690` | `833-857` |
  | TC-DEID-074 | `693-720` | `869-890` |
  | TC-DEID-079 | `722-746` · `KpstDeidentTxService:116-120` | `898-911` · `KpstDeidentTxService:269-273` |
  | TC-DEID-080 | `67` | `69` |
  | TC-DEID-081 | `225,300-320` | `286`(호출), `446-466`(본체) |
  | TC-DEID-082 | `302-305` | `447-451` |
  | TC-DEID-083 | `516-518` · `DeidentArtifactIntegrity:43-108` | `692-694` · `43-206` |
  | TC-DEID-084 | `464-486,531-556` | `626-633`, `707-730` |
  | TC-DEID-088/089 | `KpstDeidentTxService:78-95` | `206-223` |
  | TC-DEID-091 | `621-634` | `797-802` |
  | TC-DEID-092 | `601-616` | `779-787` |
  | TC-DEID-093 | `DeidentFrameAttacher:73-93` | `152-161`(호출 `99`) |

  반면 `KpstDeidentPollJob.java`(TC-DEID-075/076/077/078/085/087/090)와 `LsDeidentProcLogRepository.java`(TC-DEID-085/086/088)의 라인은 **정확히 일치**한다.
- **재현/확인 경로**: 위 표의 카탈로그 라인을 그대로 열면 무관한 코드(주석/다른 메서드)가 나온다.
- **영향**: 검증 자동화·회귀 대조가 어긋나고, 다음 회차에 "코드가 사라졌다"는 오판을 유발할 수 있다. 기능 영향 없음.
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` §B-13 의 근거 컬럼을 위 표대로 일괄 치환. 향후 드리프트를 줄이려면 라인 대신 **메서드명**(`KpstDeidentService#downloadResult` 등)을 근거로 쓰는 표기 규약을 검토.

### [B-ISSUE-69] TC-BATCH-154 / 161 / 168 — B-12 근거 라인 경미 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 라인이 실제 코드 위치와 일치해야 한다.
- **현재 동작(이슈 내용)**: `TC-BATCH-154` 카탈로그 `BatchTransitionService.java:334-362` → 실제 메서드는 `334-347`(`362` 는 다른 메서드의 javadoc). `TC-BATCH-161` 카탈로그 `BatchRetryQueue.java:141-147` → 실제 `140-146`. `TC-BATCH-168` 카탈로그 `BatchRetryStaleReclaimSweeper.java:142-156` → 실제 `142-157`. `TC-BATCH-163` 의 `BatchTransitionService.java:364-380` → 실제 `364-386`. 나머지 B-12 근거(150·151·152·153·155~160·162·164~167)는 일치.
- **재현/확인 경로**: 해당 라인 직접 열람.
- **영향**: 문서 정합만. 기능 무관.
- **수정 방향(제안)**: 위 4건 라인 보정.

### [B-ISSUE-70] TC-BATCH-156/159/160/164/165 · TC-DEID-077/085/086/087/088 — 실제 2노드 Active-Active 동시성 실증 불가(검증 환경 한계)
- **심각도**: LOW (검증 커버리지 한계 기록 — 코드 결함 아님)
- **기대 동작(기대효과)**: 배포 토폴로지가 2노드 Active-Active(그리고 local/dev 는 `QUARTZ_CLUSTERED=false`)이므로, 동일 트리거가 양 노드에서 발화하는 상황에서 원자 클레임이 정확히 1개만 성립함을 **실제 두 프로세스로** 확인하는 것이 이상적이다.
- **현재 동작(이슈 내용)**: 이번 검증 스택은 `klid-backend` **단일 컨테이너**다(`docker compose ps` 기준). 따라서 동시 발화를 재현할 수 없어, 프로덕션과 **동일 술어의 조건부 UPDATE 를 단일 세션에서 2회 연속 실행**해 "두 번째 0행"을 확인하는 직렬 근사로 대체했다(§0-1, 8종 전수). 이는 PostgreSQL 이 UPDATE 시 행 락 획득 후 최신 버전으로 WHERE 를 재평가한다는 규약에 근거해 동시 실행 결과와 동치이지만, **경합 타이밍·데드락·리스 경계** 같은 진짜 동시성 현상은 관측되지 않았다.
- **재현/확인 경로**: `docker compose ps` → backend 1개. 2노드 실증을 하려면 compose 에 backend 레플리카를 추가하고(포트/Quartz 인스턴스명 분리) 동일 `procLogSn`/`batRtySn` 에 대한 동시 tick 을 유도해야 한다.
- **영향**: 해당 케이스들의 판정 신뢰도가 "DB 원자성 규약 + 직렬 근사 + 기존 IT(`BatchRetryQueueIT`, `BatchRetryStaleReclaimIT`, `KpstDeidentPollClaimIT`)" 수준에 머문다. 실환경 회귀(예: 술어에서 조건 1개가 빠지는 변경)는 이 방식으로도 잡히지만, 리스 경계 타이밍 결함은 놓칠 수 있다.
- **수정 방향(제안)**: 다음 회차 환경 준비 시 `docker-compose.local.yml` 에 backend 2번째 인스턴스를 옵션으로 추가(별도 `QUARTZ_INSTANCE_ID`, 동일 DB)해 §0-1 8종을 실제 2프로세스로 재실행하는 절차를 `VERIFY-PROMPT.md §3-1` 에 추가. 코드 수정은 불필요.

> B-ISSUE-71~85 는 **미사용**(이번 파트에서 추가 이슈 없음).

---

## 5. 검증 중 생성/정리한 임시 데이터 (부작용 없음 확인)

실동작 판정을 위해 임시 행 `ls_data_raw.raw_sn=9901`(`vms_clip_id='VERIFY-B4-TMP'`)과 그에 딸린 `ls_bat_rty_wtng` 1행·`ls_deident_proc_log(proc_log_sn=9901)` 1행을 생성했고, **검증 종료 후 `DELETE FROM ls_data_raw WHERE raw_sn=9901`(FK CASCADE)로 전부 제거**했다. 제거 후 재조회로 잔여 0건 확인. 기존 rawSn(4·5·6 및 타 에이전트 생성분)과 파일 시스템·설정·코드는 **일절 수정하지 않았다**.
