# E 클러스터 part2 — E-3 증강 결과 웹훅 / E-3B 증강 외부 위탁 (3차, 2026-08-03)

- 담당 범위: `docs/test-cases/E-augment-resolution-export-meta.md` **E-3**(74~108행, TC-AUG-050~078 29건) + **E-3B**(110~137행, TC-AUG-100~121 22건) = **51건**
  - ⚠ 지시서의 라인범위(69~133)는 드리프트다. 실제 섹션 헤딩은 E-3=74행 / E-3B=110행 / E-4=139행이며, 두 절의 케이스 합계는 지시서와 동일한 **51건**이다. TC-AUG-040~043(69~73행)은 E-2(증강 검수) 소속이라 part1 담당으로 두고 손대지 않았다.
- 폐기 5건(TC-AUG-054·056·058·059·062)은 통과율 분모에서 제외 → **검증 대상 46건**
- 검증 방식: 풀스택 실동작 우선(backend :18081 · mock-server :9400 · PostgreSQL). 빌드/테스트 미실행.
- 근거 스택: `_raw/stack-bringup.md`(HEAD `e065da42` 재빌드 후 기동, 외부연동 4종 mock-server 실배선) · `_raw/pipeline-drive.md`(rawSn=101 정상 파이프라인) · `_raw/test-baseline.md`(backend/frontend/ai-server 실패 0건)

## 판정 집계

| 판정 | 건수 |
|---|---:|
| PASS | 42 |
| FAIL | 0 |
| PARTIAL | 4 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **검증 대상 계** | **46** |
| (폐기 — 분모 제외) | 5 |

PASS율 42/46 = **91.3%**. 실동작 근거 케이스 **28건**([실동작] 표기), 나머지는 정적 대조 + 기존 자동테스트 대조.

---

## 0. 이번 회차에 실제로 구동한 것 (근거의 출처)

### 0-1. 정상 왕복 1회전 — 실제 증강 요청 → mock 위탁 → 웹훅 → 파생영상 생성

```bash
# REVIEWER 토큰 발급 후
POST /api/v1/augments/request
  {"videoIds":[101],"types":["WINTER"],
   "prompt":{"time":"NIGHT","season":"WINTER","weather":"RAIN","terrain":"ROAD","severity":"HIGH"}}
→ 200 {"jobId":1785768859946,"createdCount":1}
```

mock-server 인바운드(실경유 확인):

```
2026-08-03 16:40:15,335 [MOCK][GENAI] job accepted job_id=2a2fb36ef4084e168c80e22883aed5fe
                                       request_id=AUG-284edfa4-6118-4be9-8f7f-d07e74172310-1 mode=I2I inputs=10
INFO: 172.20.0.5:45850 - "POST /api/genai/jobs HTTP/1.1" 202 Accepted
... [MOCK][GENAI] webhook sent url=http://klid-backend:8080/api/v1/genai/callback status=200 job_id=2a2f...
```

DB 실측 (self-fill 아님 — 모든 값이 mock 응답에서 옴):

```
ls_data_aug     : data_aug_sn=52 src_sn=468 WINTER ACCEPTED otsd_job_id=2a2fb36ef4084e168c80e22883aed5fe new_raw_sn=158
ls_data_aug_job : aug_job_sn=13 job_seq=1 idmp_key=AUG-284edfa4-...-1 otsd_job_id=2a2f... SUCCEEDED tot_nocs=10
ls_data_aug_job_file : 10행, rslt_file_path_nm=/app/genai-out/genai/2a2fb36e.../001_frame-0_genai.jpg …
ls_data_raw     : raw_sn=158 orgnl_raw_sn=101 vms_clip_id=QA3RD-PIPELINE-DRIVE-001_AUG_WINTER_52
                  raw_file_path_nm=/app/storage/deidentified/videos/augment/101/158/WINTER.mp4
ls_data_src     : 567~576, src_file_path_nm=NULL / de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/158/frame-N.jpg
```

파일시스템:

```
/app/storage/raw/seed/101/deid/clip-9101-mask.mp4                     50854 bytes   (LS_DEIDENT_PROC_LOG 기록값)
/app/storage/deidentified/videos/augment/101/158/WINTER.mp4           50854 bytes   (바이트 동일 사본)
/app/storage/deidentified/frames/deid/158/frame-0.jpg                 13164 bytes   (외부 산출물 반입분)
```

### 0-2. ★ 반증 시도 3종 (지시서 필수 항목)

| 반증 항목 | 결과 | 근거 |
|---|---|---|
| **파생 깊이 1 고정** — 파생본 재증강 | ✅ 정책대로 차단 | `POST /v1/augments/request {"videoIds":[158],...}` → **400** `{"skippedVideoIds":[158],"message":"파생 영상은 증강 요청 대상이 아닙니다."}` |
| **신고 구간(`'F'`) 부모의 증강 콜백 인계** — "원본 신고와 무관" 정책 | ✅ 정책대로 **파생 생성됨** | 부모 rawSn=94(`de_ident_yn='F'`, 6프레임)에 aug 880017 심고 SUCCEEDED 콜백 → 200 `applied:true`, aug ACCEPTED, 신규 `raw_sn=169 orgnl_raw_sn=94` 생성. TC-AUG-056 폐기 판정이 실동작으로 확인됨 |
| **self-fill (외부 응답 없이 값 자체생성)** | ✅ 없음 | ①`otsd_job_id` = mock 202 응답값 ②`rslt_file_path_nm` = 콜백 `results[].output_file_path` 원문 ③파생 프레임 = `/app/genai-out/…` 외부 산출물 반입 사본 ④위탁 0건이면 아무 값도 안 생김(아래 TC-AUG-105) ⑤유일한 상수 채움은 `evnt_type` 미상 시 `"ETC"`(계약 필수 필드, TC-AUG-111 로 명시된 의도된 동작) |

---

## 1. E-3. 증강 결과 웹훅 (TC-AUG-050~078)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-AUG-050 | PASS | [실동작] §0-1 전체. 200 `{applied:true,requestId}`, aug ACCEPTED, 신규 RAW 158(`ORGNL_RAW_SN=101`), `RAW_FILE_PATH_NM`=자기 비식별 사본 경로(부모 원본 경로 폴백 **없음**), AFTER_COMMIT `AsyncAugmentFrameRunner` 기동 로그 확인. 근거 `GenAiCallbackService.java:90-158`·`AugmentJobRollup.java:64-69`·`AugmentResultService.java:409-448` 전부 실제와 일치 |
| TC-AUG-051 | PASS | [실동작] aug 880007 job FAILED(`GEN_FAIL`) 콜백 → 롤업 `rollup failed (partial failure — fail-closed)`, aug **REJECTED + rtry_nmtm=1 + dead_letter_at=2026-08-04 01:42:11**, `createAugmentedVideo` 미호출(신규 RAW 0건) |
| TC-AUG-052 | PASS | [실동작] 이미 SUCCEEDED 인 job(13) 에 동일 SUCCEEDED 재전송 → 200 `{"applied":false}` + `duplicate callback absorbed … state=SUCCEEDED`, 중복 영상 0건 |
| TC-AUG-053 | PASS | [실동작] ② aug 880011(externalJobId null) 에 **다른 증강(880003)이 보유한** `job_id=e3jobB1` 로 SUCCEEDED → **409** `"이미 다른 증강 결과에 인계된 작업 ID 입니다."` + WARN `otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 … ownerDataAugSn=880003`. **쓰기 이전 선점검사**라 PG 25P02(500) 없음 → 1차 E-ISSUE-05 해소 확인. ① 자기 자신 재수신은 TC-AUG-052 경로로 흡수 |
| ~~TC-AUG-054~~ | (폐기) | 분모 제외 |
| TC-AUG-055 | PASS | [실동작] ① 미발급 `request_id` → **401** `"발급되지 않은 request_id 입니다."` + `HmacWebhookFilter - downstream auth rejected ip=… path=/api/v1/genai/callback status=401`(rate-limit 집계 배선 확인). ② "job 은 있으나 aug 행 없음 = 404" 는 **FK CASCADE 로 도달 불가**(아래 E-ISSUE-29, 카탈로그 정정 완료) |
| ~~TC-AUG-056~~ | (폐기) | 분모 제외 — 단 폐기 근거(신고 구간에도 파생 생성)를 §0-2 에서 실동작 반증으로 확인함 |
| TC-AUG-057 | **PARTIAL** | [정적] `ParentGate.fail("parent has no frames")` 분기는 존재하나 **라이브 도달 불가**(E-ISSUE-22). 커버는 mock 단위테스트 `AugmentResultServiceTest.부모_프레임이_없으면_실패로_확정되고_영상과_프레임러너_미트리거` |
| ~~TC-AUG-058~~ | (폐기) | 분모 제외 |
| ~~TC-AUG-059~~ | (폐기) | 분모 제외 |
| TC-AUG-060 | PASS | [실동작] `status=CANCELED` → **400** `"status: status 는 RUNNING\|SUCCEEDED\|FAILED 중 하나여야 합니다."` |
| TC-AUG-061 | PASS | [실동작] `request_id="bad id!"` → 400 `"request_id 는 영숫자/대시/언더스코어만 허용됩니다."` / `job_id=".."` → 400 `"job_id 형식이 올바르지 않습니다."`(점-전용 세그먼트 차단 `(?!\.+$)` 실효 확인, CWE-22) |
| ~~TC-AUG-062~~ | (폐기) | 분모 제외 |
| TC-AUG-063 | PASS | [실동작] 마지막 job(880002) SUCCEEDED 콜백을 **동시 3건** 발사 → `applied:true` **정확히 1건**, 나머지 2건 `applied:false`. `ls_data_aug 880001` → ACCEPTED, `new_raw_sn=164` **1건만** 생성. `findByDataAugSnForUpdate` 선잠금 직렬화가 실동작으로 확인됨(CWE-362) |
| TC-AUG-064 | PASS | [실동작] 부모 rawSn=50(`de_ident_yn='N'`) 의 aug 880005 SUCCEEDED → WARN `result failed — parent unavailable … reason=parent has no deident artifact`, aug **REJECTED + dead-letter**, 신규 RAW 0건. 판정기 `LsDataRaw.hasDeidentArtifact()` 단일 헬퍼 확인 |
| TC-AUG-065 | **PARTIAL** | [정적] `GenAiWebhookIpAllowlist.java:44-55,63-77` 이 미설정/`none` → `allowed=List.of()` → `isAllowed()` 항상 false(fail-closed) 확인. **라이브 미검증** — 현 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` 명시라 403 을 재현하려면 설정 변경 후 재기동이 필요(§10-1 코드/설정 불변 규칙). 필터가 이 경로에 실제로 걸린다는 사실은 401 로그의 `HmacWebhookFilter … path=/api/v1/genai/callback` 으로 확인(E-ISSUE-23) |
| TC-AUG-066 | PASS | [정적+실동작] `GenAiIntegrationWiringGuard.verify()` 가 `mode=http`(미설정 포함) + allowlist `none/빈값` 이면 `IllegalStateException` 으로 기동 실패. 현 형상(mode=http + allowlist 명시)에서 **기동이 성공한 것**이 가드 통과의 실동작 근거. 커버: `GenAiIntegrationWiringGuardTest` |
| TC-AUG-067 | PASS | [실동작] job 13(externalJobId=2a2f…)에 `job_id=otherjob123` → **409** `"job_id 가 일치하지 않습니다."` + WARN `job_id mismatch … expected=2a2f… received=otherjob123`. 상태 미변경 확인 |
| TC-AUG-068 | PASS | [실동작] job 880007 `status=RUNNING,progress=50,current_step=GEN` → 200 `applied:true`, `job_stts_cd=RUNNING`, 롤업 로그 없음(결과처리 미수행) |
| TC-AUG-069 | PASS | [실동작] `output_file_path=/etc/passwd` 및 `/app/genai-out/../../etc/passwd` 둘 다 **400** `"output_file_path 가 허용된 저장 경로가 아닙니다."`. WARN `output path rejected (outside readable roots) … code=FORBIDDEN`(경로 원문 미노출, CWE-209). **job 상태 미변경**(880003 이 RECEIVED 유지 → 재전송 여지 보존) DB 확인. 검증 위치가 `AugmentJobSuccessApplier.verifyOutputPaths`(웹훅/회수 공용)라는 3차 기전 정정도 실제와 일치 |
| TC-AUG-070 | PASS | [정적] `VideoArtifactRootResolver.buildReadableRoots`(148-177) 가 `쓰기 allowlist ∪ external-read-roots` 이고 기본값이 빈 문자열 → 미설정 시 읽기 범위 = 쓰기 allowlist. `rejectFilesystemRoots` 로 `/` 지정 시 기동 실패. 현 env `STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out` 는 명시 확장분 |
| TC-AUG-071 | PASS | [실동작] job 880003(위탁 파일 2건)에 results 1건 SUCCEEDED → job `FAILED / ERR_CD=RESULT_COUNT_MISMATCH / "위탁 2건 대비 수신 1건"`, 롤업 fail-closed 로 aug 880003 **REJECTED + dead-letter**. 대조 위치가 `AugmentJobSuccessApplier.applySucceeded`(101-121)라는 3차 정정도 일치 |
| TC-AUG-072 | PASS | [실동작] SUCCEEDED + results 없음 → **400** `"SUCCEEDED 결과에는 results 가 필요합니다."` — 카탈로그 기대문구와 **일치**. → **1차 E-ISSUE-22 해소 확인**. 메트릭 `reason=missing_results` 는 코드 확인(`AugmentMetrics.REASON_MISSING_RESULTS`) |
| TC-AUG-073 | PASS | [실동작] 2청크 중 1청크만 SUCCEEDED → 200 `{"applied":false}` + `rollup deferred dataAugSn=880001 pendingJobSeqs=[2]`. job 자체의 SUCCEEDED 갱신은 커밋됨(DB 확인) |
| TC-AUG-074 | PASS | [실동작] `mdfcn_dt` 를 2일 전으로 심은 job 880013 → 스윕 tick(01:44:20)에서 `claimExpired` 1행 클레임 → `FAILED / EXPIRED / "외부 응답 없음 — 무갱신 경과 임계 초과로 만료 종결…"` → 롤업 `rollup=APPLIED` → aug 880013 REJECTED+dead-letter. 같은 tick 에서 클레임 실패 건은 no-op(아래 E-ISSUE-25 참조) |
| TC-AUG-075 | PASS | [실동작] job 0건 + `reg_dt` 2일 전인 aug 880015 → `orphan pending augment reclaimed (job 0건 · 깨울 주체 없음) dataAugSn=880015 result=APPLIED` → REJECTED + dead-letter |
| TC-AUG-076 | PASS | [실동작] `status` 누락 → 400 `"status: must not be blank"`. `results` 100건 상한은 `@Size(max=100)` 정적 확인(`GenAiCallbackRequest.java:88`) |
| TC-AUG-077 | PASS | [실동작] `{"brand_new_field":123,"nested":{"a":1}}` 추가 전송 → 200 정상 수신. `@JsonIgnoreProperties(ignoreUnknown=true)` 최상위(41행)+`ResultItem`(102행) 양쪽 확인 |
| TC-AUG-078 | PASS | [실동작] REJECTED 인 aug 880009 에 성공 결과 도착 → 200 `applied:false` + **WARN** `success result discarded — aug already terminal(REJECTED) dataAugSn=880009 otsdJobId=e3jobE1 (강등/반려/취소 — 필요 시 재요청)` |

## 2. E-3B. 증강 외부 위탁 (TC-AUG-100~121)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-AUG-100 | **PARTIAL** | [정적] 상한 clamp(`CONTRACT_MAX_INPUT_FILES=100`, `clampChunkSize`)·`chunkRequestId={augIdmpKey}-{jobSeq}`·`partition` 확인, 라이브에서도 `request_id=AUG-…-1` 형식 실측. **분할 자체는 라이브 미검증** — DB 최대 프레임 수가 30이라 250장 시나리오를 만들 수 없다(E-ISSUE-24). 커버: `AugmentJobSubmitServiceTest.프레임_250장이면_100_100_50_으로_3개_job_으로_분할_위탁됨` 외 3건 |
| TC-AUG-101 | PASS | [실동작] mock 202 의 `job_id=2a2fb36ef4084e168c80e22883aed5fe` 가 그대로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 적재됨(우리가 만든 값 아님). 3축 검증(`validate` 546-560: request_id echo 일치 + `status=RECEIVED` + `GenAiContract.isValidJobId`) 코드 확인 |
| TC-AUG-102 | PASS | [정적] `IDEMPOTENCY_HEADER="Idempotency-Key"`(97행) → `.header(IDEMPOTENCY_HEADER, command.requestId())`(178행). mock 로그가 헤더를 출력하지 않아 값 자체는 정적 확인 |
| TC-AUG-103 | PASS | [실동작] 위탁 입력 경로를 mock 허용 루트(`MOCK_GENAI_INPUT_BASE=/app/storage`) 밖(`/app/genai-out/outside/frame-*.jpg`)으로 만든 영상 880200 요청 → mock `400 INVALID_PARAMETER`, backend job 14 → `FAILED / SUBMIT_FAILED / "생성형AI 위탁 4xx 응답(status=400)"`. **재시도 0회**(mock 로그 POST 1건만) → `NonRetryableExternalException` 실효. **벤더 응답 본문 원문 미노출**(상태코드만) 확인, CWE-209 |
| TC-AUG-104 | PASS | [정적] `issueAllChunks`(375-394) 가 전량 선기록 후 `Optional.empty()` 시 `failIssuedJobs(… ERR_ISSUE_RECORD_FAILED)` 로 앞 청크까지 종결하고 `SubmitOutcome.of(0)` 반환(236-240). 커버: `선기록_실패시_외부로_위탁하지_않는다` / `청크_3개중_2번째_선기록_실패시_전체가_실패로_종결된다` / `선기록은_첫_위탁보다_먼저_전량_수행된다` |
| TC-AUG-105 | PASS | [실동작] 프레임 2건 중 1건의 `DE_IDNTF_SRC_FILE_PATH_NM=NULL` 인 영상 880210 요청 → **mock 인바운드 0건**(`grep -c "genai jobs"` = 0), job 15 → `FAILED / DEID_PATH_MISSING / "비식별 프레임 경로가 없는 프레임이 있어 외부 위탁을 거부합니다. missingCount=1"`, WARN `위탁 거부 — 비식별 프레임 경로 부재 … missingCount=1`. **원본 경로(`SRC_FILE_PATH_NM`) 폴백 없음** 확인 |
| TC-AUG-106 | PASS | [정적] `submit()` 최상단(207-217)에서 `DeidentReportGate.isUnderDeidentReport` → `recordRejected(… ERR_DEID_REPORT_OPEN)` + `SubmitOutcome.of(0)` → 브릿지(124-126) 즉시 실패 롤업. 라이브로는 **요청 입구(`AugmentRequestService`)가 먼저 412 로 막아** 이 2차 게이트에 도달할 수 없다(동일 게이트의 라이브 차단은 `request blocked — deident report open` 로그로 관측). 커버: `비식별_신고중인_영상은_증강_위탁이_거부된다` / `비식별_신고중_영상의_프레임_경로가_외부로_전송되지_않는다` |
| TC-AUG-107 | PASS | [정적] `Mono.defer` 안 `index>0 && isUnderDeidentReport` → `abortRemainingChunks(… ERR_DEIDENT_REPORT)` + `SubmitAbortedException`(319-322, 406-415). `concatMap` 직렬화라 재판정이 청크마다 실행됨을 코드로 확인. 커버: `위탁_도중_비식별_신고가_확인되면_남은_청크를_중단하고_실패로_종결한다` |
| TC-AUG-108 | PASS | [실동작] 위 TC-AUG-103 에서 `SUBMIT_FAILED` + 사유가 실제로 기록됨. "3번째 청크 계속" 은 1청크 환경이라 unit(`분할_위탁_중_2번째_job_실패해도_3번째_가_계속_위탁되고_실패가_기록됨`)으로 대조. ⚠ 카탈로그 근거에 실제 기록 주체(`AugmentSubmitOutcomeRecorder.onSubmitFailed` → `AugmentJobRecorder.markSubmitFailed`)가 빠져 있어 **정정함**(E-ISSUE-28) |
| TC-AUG-109 | PASS | [실동작] TC-AUG-103/105 두 경우 모두 `dispatched=0` → `AugmentRequestBridge.rollUpFailureIfNothingInFlight` → aug 67·68 REJECTED + `rtry_nmtm=1` + `dead_letter_at` 기록. "미종결 job 이 있으면 롤업 안 함" 가드(143-145)는 정적 확인 |
| TC-AUG-110 | PASS | [정적+실동작] `AugmentJobSubmitService` 클래스·`submit()` 모두 **트랜잭션 애너테이션 없음** 확인(82-134·198-203 javadoc 이 실제 상태와 일치). 라이브에서 위탁 3건이 근접 시각(01:39:45 / 01:40:01 / 01:40:15)에 겹쳤으나 `CannotCreateTransactionException` 0건 — 데드락 미재현. 회귀 가드 `AugmentRequestServiceTest.외부_위탁_HTTP_왕복중에는_트랜잭션_동기화와_EntityManager를_잡지_않는다` 존재 |
| TC-AUG-111 | PASS | [정적] `EVNT_TYPE_FALLBACK="ETC"`(144) + `resolveEventType`(497-502). mock 로그가 `evnt_type` 을 출력하지 않아 값 자체는 정적. 커버: `이벤트유형이_없는_영상은_ETC_로_대체된다` |
| TC-AUG-112 | PASS | [실동작] 이번 회차 backend 로그 전수 확인 — 위탁/거부 로그가 `originAugSn`·`rawSn`·`inputCount`·`missingCount` 수준만 남기고 **파일 절대경로 0건**. `LOG_UNSAFE` 에 ` / ` 포함(106행), `logValue` 길이 상한 50 확인(CWE-117/209/770) |
| TC-AUG-113 | PASS | [정적] `NoopExternalAugmentClient` 는 `@ConditionalOnProperty(havingValue="noop")`, `HttpExternalAugmentClient` 는 `havingValue="http", matchIfMissing=true` → 상호배타. 만료 스윕은 자기 토글(`authoring.augment.job-expiry.enabled`)만 참조(47-52 javadoc + 86행 `@Value`). 현 형상은 `AUGMENT_EXTERNAL_MODE=http` |
| TC-AUG-114 | PASS | [실동작] 존재하지 않는 산출 경로(`/app/genai-out/genai/e3/002.jpg`)로 확정된 aug 880001 → Phase B 반입 실패 → `augment frame re-extraction failed rawSn=164`, 신규 RAW 164 `DATA_STTS_CD=FAILED`, 프레임 0건(all-or-nothing). 정상 경로(158)에서는 10건 전량 반입 성공 |
| TC-AUG-115 | PASS | [실동작+정적] 실패한 164 의 목적 디렉터리에 `.part` 잔존 0건, 성공한 158 은 완결 파일만 존재. `NOFOLLOW_LINKS` + atomic move 는 코드 확인 |
| TC-AUG-116 | PASS | [실동작] `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM=/app/storage/raw/seed/101/deid/clip-9101-mask.mp4`(KPST 규약 `{stem}-mask{ext}`) → 파생 사본 `/app/storage/deidentified/videos/augment/101/158/WINTER.mp4`. **양쪽 50854 bytes 동일** = 경로를 조합·추측하지 않고 원장 값을 읽어 실제 복사함 |
| TC-AUG-117 | PASS | [실동작] `ls_data_src` raw_sn=158 → `src_file_path_nm` **전건 NULL**, `de_idntf_src_file_path_nm`=`/app/storage/deidentified/frames/deid/158/frame-N.jpg`. 두 컬럼 동일값 0건 |
| TC-AUG-118 | PASS | [실동작] `ls_data_aug_lbl_map` (data_aug_sn=52) → `orgnl_data_lbl_sn=729 / data_lbl_sn=1268 / coord_recalc_yn='N' / scale_x=NULL / scale_y=NULL`. 대조군으로 해상도 파생(aug 66)은 `'Y' / 2.0 / 2.0` |
| TC-AUG-119 | PASS | [실동작] async 실패 시 ①`augment frame re-extraction failed rawSn=164` ②RAW 164 `FAILED` ③`[Augment][ExtractC] aug marked dead-letter after async extraction failure dataAugSn=880001 status=ACCEPTED` — `AUG_PROC_STTS_CD` **미변경(ACCEPTED)** + `rtry_nmtm=1` + `dead_letter_at` 기록 확인 |
| TC-AUG-120 | **PARTIAL** | [실동작] 주 단언(ffprobe 를 확정 블록 **밖** 별도 catch 로 기동, 사본 확정 이후에만 실행)은 PASS — `AsyncVideoMetaRunner` 가 Phase C 성공 이후에만 돌아 158 의 `video.*` 가 채워짐. **⚠ 주석 단언 "`video.*` 가 부모와 동일한 것이 정상" 은 실측 반증**(E-ISSUE-21): 101=640x480/30fps/10000ms(인입 선언값) vs 158=320x240/10.0fps/5000ms(ffprobe 실측). 카탈로그 정정함 |
| TC-AUG-121 | PASS | [정적] `AsyncAugmentFrameRunner.java:92-97` — Phase C `SKIPPED` 시 cleanup·FAILED 전이 모두 skip. 중복 트리거 패자 상황을 라이브로 유도할 수 없어 정적. 커버: `AsyncAugmentFrameRunnerTest` |

---

## 3. 이슈 대장

### [E-ISSUE-21] TC-AUG-120 — 파생영상의 `video.*` 기술메타가 부모와 다르다 (두 메타 소스가 충돌하고, 복사분이 즉시 덮어써진다)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 파생영상의 비디오 파일은 부모 비식별본의 **바이트 동일 사본**이므로, `LS_DATA_META` 의 `video.*`(`RESL`/`FPS`/`duration_ms`/`codec`/`bit_rate`)와 그로부터 동결되는 `LS_DATASET_VIDEO_META`·`V_COMPLETED_VIDEO` 값이 부모와 **일치**해야 한다는 것이 `CLAUDE.md`("★ 파생영상에는 '원본영상'이 없다" 절 — *"video.\* 기술메타는 부모와 동일한 것이 정상 … `DerivedMetaCopier` 의 부모 값 복사는 결함이 아니다"*)와 TC-AUG-120 ⚠ 주석의 명시 계약이다. 관제는 이 값으로 파생 1행을 UPSERT 하므로 어느 쪽이 진실인지 확정돼 있어야 한다.
- **현재 동작(이슈 내용)**: 두 기전이 순차로 겹쳐 **최종값이 항상 ffprobe 실측값**이 된다.
  1. `DerivedMetaCopier.java:29-30` — *"메타 값 전체 복사 — `video.*` 기술메타도 **포함**. 파생영상의 비디오 파일은 원본(비식별) 복사본이라 `video.*`={원본값}이 정합적이다(구 `isTechnicalKey` skip 해제)"* → 부모 값을 복사한다.
  2. `AsyncAugmentFrameRunner.java:106-121` — Phase C 확정 이후 `AsyncVideoMetaRunner` 를 기동해 **파생 사본을 ffprobe** 하고 같은 키를 덮어쓴다.
  3. 부모 쪽 `video.*` 는 `VideoMetaService.java:37-54` 규약상 **`LS_DATA_INGEST` 인입 선언값 우선**(`bit_rate` 만 ffprobe 전용)이다.
  → 인입 선언이 실제 파일과 다르면 부모≠파생이 **구조적으로** 발생한다.
- **재현/확인 경로** (본 검증 실측):
  ```sql
  SELECT raw_sn, meta_key, meta_vl FROM ls_data_meta
   WHERE raw_sn IN (101,158) AND meta_key LIKE 'video%' ORDER BY meta_key, raw_sn;
  -- 101 video.resolution 640x480   | 158 video.resolution 320x240
  -- 101 video.fps        30        | 158 video.fps        10.0
  -- 101 video.duration_ms 10000    | 158 video.duration_ms 5000
  -- 101 video.codec      H264      | 158 video.codec      h264
  SELECT wdth, vrtc, resl, fps, vdo_len_sec FROM ls_data_ingest WHERE raw_sn=101;  -- 640|480|640x480|30|10  ← 인입 선언
  ```
  ```bash
  # 파생 158 의 비디오는 101 비식별본의 바이트 동일 사본인데도 값이 다르다
  docker exec klid-mock-server ffprobe -v error -show_entries stream=width,height,r_frame_rate -of csv=p=0 \
    /app/storage/deidentified/videos/augment/101/158/WINTER.mp4   # 320,240,10/1
  docker exec klid-mock-server ffprobe ... /app/storage/raw/seed/clip-9101.mp4      # 320,240,10/1  (부모 실제 파일도 동일)
  ```
- **영향**: 데이터 정합 + 외부 계약. ①관제가 같은 영상 트리(부모/파생)에서 **서로 다른 해상도·fps·길이**를 받는다 — 파생 산출물이 부모의 사본이라는 계약과 모순되어 관제 측 정합 검사·통계가 어긋난다. ②`CLAUDE.md` 가 관제에 명시하라고 한 *"`RESL` 은 비디오 파일 기준"* 규칙이 부모 행에서는 **성립하지 않는다**(부모는 인입 선언값). ③`DerivedMetaCopier` 의 `video.*` 복사는 즉시 덮어써지는 **죽은 작업**이며, 주석은 그 사실을 반영하지 않아 다음 수정자가 "복사가 최종값"으로 오독한다. 보안 노출 없음.
- **수정 방향(제안)**: 정책을 **한 축으로 확정**한다. ⓐ"파생 = 사본 실측"으로 간다면 `DerivedMetaCopier` 의 `video.*` 복사를 제거(죽은 작업 정리)하고 `CLAUDE.md`·TC-AUG-120 서술을 "파생 사본을 실측한 값"으로 고친다. 이때 **부모 쪽도 비식별본 기준으로 재측정할지**를 함께 정해야 부모/파생 비교가 성립한다. ⓑ"파생 = 부모와 동일"로 간다면 파생 경로에서 `AsyncVideoMetaRunner` 기동을 빼고 복사값을 최종으로 둔다(단 인입 선언이 틀린 경우 오류가 파생으로 전파된다). 어느 쪽이든 **관제 협의 대상**이다. ⚠ 구현은 하지 않았다. 카탈로그 TC-AUG-120 의 ⚠ 주석은 이번 회차에 사실 기준으로 정정했다.

### [E-ISSUE-25] [이월·미해소] 만료 스윕의 클레임과 롤업이 같은 트랜잭션이라 특정 job 이 **영구 회수 불가**(15분마다 무한 실패 반복)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 만료 스윕은 "비종결 job 은 반드시 종결된다"를 보장하는 최후 회수 장치다(`LsDataAugJobRepository.claimExpired` javadoc·`GenAiCallbackService` 주석이 "무한 대기는 없다"를 계약으로 명시). 회수 자체가 반복 실패하면 dead-letter 로 종결되거나 최소한 격리돼야 한다.
- **현재 동작(이슈 내용)**: 1차(2026-08-01) E-ISSUE-25 가 **그대로 남아 있고 이번 회차에도 실제로 반복 중**이다. `AugmentJobExpiryTxService.java:62-88` 에서 클레임 UPDATE(71-72)와 롤업(84)이 같은 `@Transactional` 이라, 롤업이 던지면 클레임까지 롤백된다.
  ```java
  @Transactional("controlTransactionManager")
  public boolean expire(Long augJobSn, Long dataAugSn, LocalDateTime cutoff) {
      if (augRepository.findByDataAugSnForUpdate(dataAugSn).isEmpty()) { ... return false; }
      if (jobRepository.claimExpired(augJobSn, cutoff, ...) == 0) return false;   // ← 이 클레임이
      ...
      AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobIdOf(jobs)); // ← 여기서 throw 하면 함께 롤백
  ```
  실측(이번 회차 backend 로그, 15분 간격으로 **7회** 반복):
  ```
  2026-08-04 00:44:19.819 ERROR AugmentJobExpirySweeper - [Augment][Expiry] expire failed augJobSn=770004 reason=CustomException
  2026-08-04 00:59:19.811 ERROR ... augJobSn=770004
  2026-08-04 01:14:19.796 ERROR ... augJobSn=770004
  2026-08-04 01:29:20.100 ERROR ... augJobSn=770004
  ```
  ```
  aug_job_sn=770004 | data_aug_sn=770003 | job_stts_cd=RECEIVED | err_cd=(null)   ← 회수 안 됨
  data_aug_sn=770003 | aug_proc_stts_cd=PENDING | dead_letter_at=(null)           ← 증강도 PENDING 고착
  ```
  근본 데이터 이상도 그대로다 — `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 UNIQUE 가 없어 같은 값이 두 job 에 존재한다:
  ```sql
  SELECT otsd_job_id, count(*) FROM ls_data_aug_job WHERE otsd_job_id IS NOT NULL
   GROUP BY otsd_job_id HAVING count(*)>1;   -- qa0802jobD | 2
  ```
  그 결과 롤업이 `requireJobIdNotOwnedByOtherAug` 에서 409 를 던지고(`AugmentResultService.java:271-285`), 스윕은 `RuntimeException` 을 잡아 ERROR 만 남기고 넘어간다(`AugmentJobExpirySweeper.java:225-228`) → 다음 tick 후보 쿼리에 다시 잡힌다.
- **재현/확인 경로**: 위 SQL 로 중복 `OTSD_JOB_ID` 확인 후 `docker logs klid-backend | grep "Expiry] expire failed"`. 신규 재현은 1차 이슈 블록의 INSERT 스크립트 그대로.
- **영향**: 기능/데이터 정합(liveness). 그 증강은 사람이 DB 를 손대기 전까지 PENDING 에서 못 나오고, 15분마다 실패 트랜잭션 1건 + ERROR 로그를 영구 생성한다. 같은 구조상 롤업이 던지는 **모든** 예외(`DataIntegrityViolationException` 포함)가 동일 결과를 낳는다. 보안 노출 없음.
- **수정 방향(제안)**: 1차 제안 그대로 유효 — ①`expire()` 에서 클레임 커밋과 롤업을 분리(클레임을 `REQUIRES_NEW` 로 먼저 커밋하면 롤업이 실패해도 job 은 종결로 남아 다음 tick 이 재선정하지 않는다) ②또는 회수 실패 횟수를 누적해 임계 초과 시 dead-letter ③부수적으로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` UNIQUE 검토(단 벤더가 같은 job_id 를 중복 반환하면 위탁이 깨지므로 트레이드오프 확인 필요). ⚠ 구현하지 않았다.

### [E-ISSUE-22] TC-AUG-057 — "부모 프레임 0건" 분기가 라이브 도달 불가능한 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 케이스는 실동작으로 검증 가능해야 한다. 불가능하면 그 사실이 케이스에 명시돼 회차마다 "확인 못 함"이 반복되지 않아야 한다(1차 E-ISSUE-21 과 동일 유형).
- **현재 동작(이슈 내용)**: `AugmentResultService.evaluateParentGate`(379-398)는 부모를 origin 프레임에서 역산한다.
  ```java
  LsDataSrc originSrc = srcRepository.findById(aug.getSrcSn()).orElse(null);      // 없으면 "origin frame not found"
  LsDataRaw parentRaw = videoRepository.findByRawSnForUpdate(originSrc.getRawSn()).orElse(null);
  ...
  if (srcRepository.findByRawSnOrderByFrameNoAsc(parentRaw.getRawSn()).isEmpty()) return ParentGate.fail("parent has no frames");
  ```
  `findByRawSnOrderByFrameNoAsc` 는 필터 없는 파생 쿼리(`LsDataSrcRepository.java:17`)이므로, `originSrc` 가 존재하는 한 그 행 자신이 최소 1건 반환된다 → 이 분기는 실행될 수 없다. 실제로 `de_ident_yn='Y'` 이면서 프레임 0건인 영상(raw_sn 64~69)이 DB 에 존재하지만, 그 영상에 매달 origin 프레임 자체가 없으므로 aug 행을 만들 수 없다.
- **재현/확인 경로**: `SELECT r.raw_sn FROM ls_data_raw r WHERE r.de_ident_yn='Y' AND NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.raw_sn=r.raw_sn);` → 프레임 0건 영상은 있으나 `LS_DATA_AUG.SRC_SN` 이 가리킬 프레임이 없다.
- **영향**: 기능/보안 영향 없음(fail-closed 다층 방어로는 정당). **카탈로그의 검증 불가 항목**이라 회차마다 판정이 흔들린다.
- **수정 방향(제안)**: 코드는 그대로 둔다. 카탈로그 기대결과에 "라이브 도달 불가 — mock 단위테스트(`AugmentResultServiceTest.부모_프레임이_없으면_실패로_확정되고_영상과_프레임러너_미트리거`)로만 검증"을 명시한다 → **이번 회차에 정정 반영함**.

### [E-ISSUE-29] TC-AUG-055 ② — "job 은 있으나 aug 행 없음 = 404" 도 도달 불가 (1차 E-ISSUE-21 카탈로그 미반영)
- **심각도**: LOW
- **기대 동작(기대효과)**: 1차(2026-08-01)에 이미 "FK CASCADE 로 도달 불가 — 방어 코드로만 존치" 로 카탈로그를 강등하라는 수정 방향이 나왔다. 카탈로그는 회차 간 대조의 입력 문서이므로 반영돼야 한다.
- **현재 동작(이슈 내용)**: 3차 카탈로그(2026-08-03 근거 전수 재확인 회차)에도 여전히 `② 404` 가 검증 대상처럼 남아 있었다. 실제 스키마:
  ```
  "fk_ldaj_data_aug" FOREIGN KEY (data_aug_sn) REFERENCES ls_data_aug(data_aug_sn) ON DELETE CASCADE
  ```
  aug 행이 지워지면 job 행도 같이 지워지므로 `GenAiCallbackService.java:103-105` 의 `orElseThrow`(404)는 실행될 수 없다.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "\d ls_data_aug_job"` → FK CASCADE 확인.
- **영향**: 기능/보안 영향 없음. 카탈로그 정합성 + 회차 간 판정 재현성.
- **수정 방향(제안)**: TC-AUG-055 기대결과에 ②의 도달 불가 사유를 명시 → **이번 회차에 정정 반영함**.

### [E-ISSUE-28] TC-AUG-108 — 근거 `file:line` 이 실제 실패 기록 주체를 가리키지 않는다 (근거 드리프트)
- **심각도**: LOW
- **기대 동작(기대효과)**: `근거(file:line)` 를 열면 기대결과의 단언(여기서는 "job `FAILED(SUBMIT_FAILED)` + 사유 기록")을 곧바로 대조할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 근거가 `AugmentJobSubmitService.java:339-354` 뿐이었는데, 그 구간은 `onErrorResume` 에서 **핸들러에 위임**만 한다.
  ```java
  outcomeRecorder.onSubmitFailed(event.originAugSn(), augJobSn, jobSeq, jobCount, err);
  ```
  실제 `SUBMIT_FAILED` 마킹은 `AugmentSubmitOutcomeRecorder.java:67-72` → `AugmentJobRecorder.java:84 markSubmitFailed`(조건부 원자 UPDATE — 지각 신호가 콜백 상태를 강등하지 못한다)에 있다. Phase C-3 논블로킹 전환 때 이관된 것이 반영되지 않았다.
- **재현/확인 경로**: `grep -n "onSubmitFailed\|markSubmitFailed" backend/src/main/java/kr/co/cudo/authoring/augment/service/AugmentSubmitOutcomeRecorder.java`
- **영향**: 기능 영향 없음. 다음 회차 검증 비용 + 오판(로직이 사라진 것으로 오인) 위험.
- **수정 방향(제안)**: 근거 컬럼에 두 파일을 추가 → **이번 회차에 정정 반영함**. (그 외 E-3/E-3B 근거 라인은 **전건 실제와 일치** — 1차 E-ISSUE-23(13건 드리프트)은 3차 카탈로그 최신화로 해소됐다.)

### [E-ISSUE-23] TC-AUG-065 — IP allowlist fail-closed(403)를 현재 환경에서 실동작 검증할 수 없다
- **심각도**: LOW (환경 제약)
- **기대 동작(기대효과)**: `webhook.genai.allowed-ip-cidrs` 미설정/`none` 이면 콜백이 전건 403 이어야 한다(VLM 과 반대로 fail-closed). 이는 무서명 웹훅의 1계층 방어라 실동작 확인이 바람직하다.
- **현재 동작(이슈 내용)**: 코드는 명확하다 — `GenAiWebhookIpAllowlist.java:44-55` 가 빈값/`none` 이면 `allowed=List.of()` 로 두고 `isAllowed()`(63-77)가 **항상 false** 를 돌려준다. 그러나 로컬 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0`(전면 허용 명시)이라 403 경로가 실행되지 않는다. 설정 변경 + 재기동은 §10-1(검증 중 코드·설정 불변) 위반이라 시도하지 않았다.
  다만 **필터가 이 경로에 실제로 배선돼 있다**는 사실은 확인했다:
  ```
  WARN k.c.c.a.c.security.HmacWebhookFilter - [Webhook] downstream auth rejected ip=172.20.0.1 path=/api/v1/genai/callback status=401
  ```
- **재현/확인 경로**: `docker exec klid-backend env | grep WEBHOOK_GENAI_ALLOWED_IP_CIDRS` → `0.0.0.0/0`.
- **영향**: 검증 커버리지 공백. 기능 영향 없음(커버: `GenAiWebhookIpAllowlistTest`).
- **수정 방향(제안)**: 다음 회차에 **allowlist 를 `none` 으로 둔 임시 프로파일**(별도 compose override)로 기동해 403 을 1회 실측하고, 그 뒤 원복한다. 코드 수정 불요.

### [E-ISSUE-24] TC-AUG-100 — 100장 청크 분할을 실동작으로 검증할 데이터가 없다 (최대 프레임 30)
- **심각도**: LOW (환경 제약)
- **기대 동작(기대효과)**: 프레임 250장 영상이 3청크(100/100/50)로 분할되고 `LS_DATA_AUG_JOB` 3행 + `LS_DATA_AUG_JOB_FILE` 250행이 생겨야 한다. 분할은 외부 계약(§4.1 input_files 상한 100)의 핵심이라 실왕복 확인 가치가 높다.
- **현재 동작(이슈 내용)**: DB 최다 프레임 영상이 30건이라(아래 쿼리) 라이브에서는 **항상 1청크**만 나간다. 실측한 위탁은 전부 `inputs=10`.
  ```sql
  SELECT r.raw_sn, count(s.src_sn) FROM ls_data_raw r JOIN ls_data_src s ON s.raw_sn=r.raw_sn
   GROUP BY r.raw_sn ORDER BY 2 DESC LIMIT 3;   -- 18|30, 4|30, 75|30
  ```
  상한 자체(`clampChunkSize` → `Math.min(configured, 100)`)와 `request_id={augIdmpKey}-{jobSeq}` 형식은 정적·라이브 모두 확인됐다.
- **재현/확인 경로**: 위 SQL.
- **영향**: 검증 커버리지 공백. 기능 영향 없음(커버: `AugmentJobSubmitServiceTest` 분할 4건).
- **수정 방향(제안)**: 다음 회차 §3-3 파이프라인 구동 시 **프레임 250장 이상 영상 1건**을 시드에 포함하거나(마킹 간격을 촘촘히), 30초 이상 영상을 사용해 분할 왕복을 1회 실측한다.

### [E-ISSUE-26] [이월·미해소] 앱(KST)과 DB DEFAULT(UTC)의 9시간 시계 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 만료 스윕은 `LocalDateTime.now() - idleTimeoutMinutes` 를 `MDFCN_DT`/`REG_DT` 와 직접 비교하므로 두 값이 같은 시계여야 임계(기본 360분)가 의미를 갖는다.
- **현재 동작(이슈 내용)**: 1차 E-ISSUE-26 그대로다. 이번 회차 실측:
  ```
  DB : show timezone → Etc/UTC ;  current_timestamp → 2026-08-03 16:41:21+00
  APP: 같은 순간 JPA 가 적재한 ls_data_aug_job.reg_dt → 2026-08-04 01:40:15   (Δ = 9h)
  ```
  본 검증에서도 이 함정을 우회하기 위해 시드 INSERT 를 전부 `now() at time zone 'Asia/Seoul'` 로 명시해야 했다(컬럼 DEFAULT 로 두면 생성 즉시 만료 대상이 된다).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "show timezone; select current_timestamp;"` + `docker exec klid-backend env | grep -i tz` / `Dockerfile:42` `-Duser.timezone=Asia/Seoul`.
- **영향**: 데이터 정합(시각 축). 앱 경로는 항상 `LocalDateTime.now()` 를 명시 대입하므로 현재 운영 흐름에서 관측된 오작동은 없다. 노출은 DB DEFAULT 에 의존하는 경로(Flyway 백필·운영 SQL·수동 INSERT)로 한정된다.
- **수정 방향(제안)**: ①DB 세션/컨테이너 TZ 를 `Asia/Seoul` 로 통일 ②또는 컬럼 DEFAULT 제거로 시각 기록 주체를 앱 하나로 못박음(권장) ③장기적으로 `timestamptz` 검토. ⚠ 변경하지 않았다.

### [E-ISSUE-27] [이월·미해소] `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 데이터 잔존 — 파생 생성 게이트가 대소문자 민감
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 산출물 보유 판정(`LsDataRaw.hasDeidentArtifact()`)이 증강·해상도 파생 생성의 단일 진실원이므로 컬럼 값 도메인이 `Y`/`F`/`N` 으로 닫혀 있어야 한다.
- **현재 동작(이슈 내용)**: 1차 E-ISSUE-24 그대로다.
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE de_ident_yn NOT IN ('Y','F','N');  -- 68 | y
  ```
  판정기는 `"Y".equals(...) || "F".equals(...)` 라 `'y'` 행은 "비식별 산출물 없음"으로 판정돼 증강 콜백이 오면 `ParentGate.FAIL`(REJECTED + dead-letter)로 끝난다. 방향은 fail-closed(안전)지만 운영자에게는 "비식별 완료된 영상인데 증강이 계속 실패"로 보인다. 컬럼에 DB 레벨 CHECK 제약이 없어 같은 오염이 재발할 수 있다.
- **재현/확인 경로**: 위 SQL.
- **영향**: 데이터 정합. 보안 노출 없음(안전한 방향으로 실패).
- **수정 방향(제안)**: ①오염 행 정정 ②`CHECK (DE_IDNTF_YN IN ('Y','F','N'))` 제약 추가 검토(Flyway). ⚠ 본 검증에서 수정하지 않았다.

---

## 4. 1차(2026-08-01) E-ISSUE 해소 대조 (본 담당 범위분)

| 1차 이슈 | 내용 | 3차 상태 |
|---|---|---|
| E-ISSUE-21 | TC-AUG-055 ② 404 dead branch | **미해소(카탈로그)** — 코드는 그대로 두는 것이 맞고, 카탈로그 반영이 누락돼 있었다. 이번 회차 정정 → 신규 E-ISSUE-29 로 기록 |
| E-ISSUE-22 | TC-AUG-072 기대 문구 불일치 | **✅ 해소** — 카탈로그가 `"SUCCEEDED 결과에는 results 가 필요합니다."` 로 정정돼 실제 응답과 일치(실동작 재확인) |
| E-ISSUE-23 | E-3/E-3B 근거 `file:line` 드리프트 13건 | **✅ 대부분 해소** — 3차 카탈로그 최신화로 E-3/E-3B 근거가 전건 실제와 일치. 잔여 1건(TC-AUG-108 기록 주체 누락)만 이번에 정정 → E-ISSUE-28 |
| E-ISSUE-24 | `DE_IDNTF_YN='y'` 오염 | **미해소** — 이월(E-ISSUE-27) |
| E-ISSUE-25 | 만료 스윕 클레임+롤업 동일 트랜잭션 → 영구 회수 불가 | **미해소 · 실측 재현** — 15분마다 `augJobSn=770004` 실패 반복 중. 이월(E-ISSUE-25 번호 유지) |
| E-ISSUE-26 | 앱(KST)↔DB(UTC) 9시간 드리프트 | **미해소** — 이월(E-ISSUE-26 번호 유지) |
| E-ISSUE-05 | 요청 응답 `jobId` ↔ 결과조회 `{jobId}` 값 공간 상이 | (E-1/E-2 범위 — part1 담당) 참고: 본 검증의 요청 응답도 `jobId=1785768859946` placeholder 였고 실제 결과는 `rawSn` 기준이었다 |

## 5. 카탈로그 정정 내역 (담당 라인범위 내 Edit, 프로덕션 코드 미수정)

| 케이스 | 정정 내용 |
|---|---|
| TC-AUG-055 | ②(404)에 "FK CASCADE 로 라이브 도달 불가 — 방어 코드로만 존치, 검증 대상은 ①만" 명시 + 근거에 FK 추가 |
| TC-AUG-057 | "라이브 도달 불가 — mock 단위테스트로만 검증 가능" 사유(부모를 origin 프레임에서 역산) + 커버 테스트명 + `LsDataSrcRepository.java:17` 근거 추가 |
| TC-AUG-108 | 실패 기록 주체 정정 — `AugmentSubmitOutcomeRecorder.java:67-72` · `AugmentJobRecorder.java:84` 추가, 사유 문구가 상태코드만 남긴다는 사실 추가 |
| TC-AUG-120 | ⚠ 주석 "`video.*` 가 부모와 동일한 것이 정상" → **실측 기반 정정**(부모=인입 선언값 우선 / 파생=ffprobe 실측, 실측 수치 병기, 근거 2개 추가) |

> `docs/test-cases/E-augment-resolution-export-meta.md` 이외의 파일은 수정하지 않았다(프로덕션·테스트·설정 전부).

## 6. 다른 파트에 넘길 관찰 (본 담당 범위 밖 — 이슈 미발행)

1. **rawSn=101 의 `video.*` 가 실제 파일과 다르다** — `LS_DATA_INGEST` 인입 선언(640x480/30fps/10s)이 실제 파일(320x240/10fps/5s)과 어긋난 채 그대로 메타가 됐다. `VideoMetaService` 의 "인입 값 우선" 정책상 **의도된 동작**이지만, 데이터마트 뷰로 나가는 값이 실측과 다르다는 점은 B/E-5(메타) 담당이 판단할 사안이다. `bit_rate` 만 ffprobe 전용이라 이 값(30624)만 실측이다.
2. **ACK 미수신(`OTSD_JOB_ID` null) job 은 콜백의 `job_id` 오배송 검사(TC-AUG-067)를 통과한다** — `GenAiCallbackService.java:116` 이 `target.getExternalJobId() != null` 일 때만 대조한다. Phase C-3 논블로킹 제출에서 콜백이 ACK 기록보다 먼저 도착할 수 있어 **의도된 설계**로 보이며(지각 ACK 는 `markSubmitFailed`/`markAccepted` 의 조건부 UPDATE 가 강등을 막는다), `request_id` 발급 게이트가 이미 대상을 인증하므로 결함으로 보지 않았다. 다만 계약 문서에 명시돼 있지 않다.
3. **다중 에이전트 간섭 관측** — 검증 중 `data_aug_sn` 47·48·49·50·51 과 `raw_sn` 153~157 이 다른 에이전트에 의해 생성/검수/폐기되는 것을 관측했다. 본 파트는 이를 피해 **880001~880017 / raw 880200·880210 / src 880200·880201·880210·880211** 대역만 사용했다(정상 왕복 1건만 자연 채번 — aug 52 / raw 158).

## 7. 본 검증이 남긴 테스트 데이터 (수정 금지 규칙에 따라 삭제하지 않고 기록만)

| 테이블 | 키 | 비고 |
|---|---|---|
| `ls_data_aug` | 880001·880003·880005·880007·880009·880011·880013·880015·880017 | 웹훅 시나리오용 (ACCEPTED/REJECTED/PENDING 혼재) |
| `ls_data_aug` | 52(정상 왕복 · `NEW_RAW_SN=158`), 67·68(위탁 실패 시나리오) | 52 는 정상 시나리오 산출물 — 유지 권장 |
| `ls_data_aug_job` | 880001~880013, 13·14·15 | |
| `ls_data_aug_job_file` | 위 job 들의 하위 행 | |
| `ls_data_raw` | **158**(정상 파생 · 유지 권장), 164·169(async 반입 실패로 `FAILED`), 880200·880210(위탁 실패 재현용 합성 영상) | 164·169 는 산출물 파일 없음 |
| `ls_raw_data_status` | 880200·880210 (`APPROVED`) | 합성 영상용 |
| `ls_data_src` | 567~576(raw 158), 880200·880201, 880210·880211 | |
| 파일시스템 | `/app/storage/deidentified/videos/augment/101/158/WINTER.mp4`, `/app/storage/deidentified/frames/deid/158/*.jpg` | 정상 산출물 |
