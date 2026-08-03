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
