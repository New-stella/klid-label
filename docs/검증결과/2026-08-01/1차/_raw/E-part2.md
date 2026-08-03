# E 클러스터 part2 — 증강 결과 웹훅(E-3) + 증강 외부 위탁(E-3B)

- **담당 범위**: `docs/test-cases/E-augment-resolution-export-meta.md` § **E-3**(TC-AUG-050~078, 29행) + § **E-3B**(TC-AUG-100~121, 22행) = **51행**
- **검증 일자**: 2026-08-02 (회차 폴더는 2026-08-01/1차)
- **검증 기준 커밋**: `56d30478` (워킹트리 qa-0801)
- **이슈 ID 범위**: E-ISSUE-21 ~ E-ISSUE-40

## 0. 환경 실측 — stack-bringup.md 의 "구버전 스택" 단서는 **무효**(스택이 최신으로 갱신됨)

`stack-bringup.md` 말미는 "klid-backend 가 2026-07-31 빌드(Flyway V146)라 증강 폐기/복구 등 V153~V158 기능은 BLOCKED" 로 기록돼 있으나, **본 검증 시점의 실측은 다르다**:

| 항목 | 실측 | 명령 |
|---|---|---|
| backend jar | `/app/app.jar` **Aug 1 14:05** 빌드 | `docker exec klid-backend ls -la /app/app.jar` |
| Flyway | **V158** 까지 success (`add ls data aug dscd lookup index`) | `select version,description,success from flyway_schema_history order by installed_rank desc` |
| V153+ 신규 API 노출 | `/v1/augments/{id}/cancel`·`/progress`·`/restore` 전부 존재 | `GET /api/v3/api-docs` |
| 신규 소스 반영 | `AugmentJobSuccessApplier`·`AugmentSubmitOutcomeRecorder` 등 Phase C-3 빈이 런타임에서 동작(로그 `augment-submit-1` 스레드 관측) | `docker logs klid-backend` |

→ **E-3/E-3B 전 범위를 실동작으로 검증했다.** BLOCKED 는 0건이다.

### 외부 연동 실경유 확인 (self-fill 아님)

```
AUGMENT_EXTERNAL_MODE=http                       (noop 아님 — 실 HTTP 위탁)
AUGMENT_API_BASE_URL=http://klid-mock-server:9400
WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0         (명시 — fail-closed 기본값을 의도적으로 열어 둠)
STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out       (읽기 축 별도 설정)
STORAGE_RAW_MOUNT_ROOTS=/app/storage/raw,/app/storage/deidentified
```

실측 왕복 로그(mock-server):
```
[MOCK][GENAI] job accepted job_id=9d4e286c28d4483595383617f1ef5b06 request_id=AUG-5c247d0a-...-1 mode=I2I inputs=5
POST /api/genai/jobs HTTP/1.1 202 Accepted
POST http://klid-backend:8080/api/v1/genai/callback "HTTP/1.1 200"   ← 4회(RUNNING×3 + SUCCEEDED)
```
→ 증강 위탁·콜백이 **전부 mock-server(:9400) 를 실제로 경유**했고, 파생 프레임 바이트가 목업 산출물과 **완전 동일**(아래 TC-AUG-114 근거)하므로 **self-fill 결함 0건**이다.

### 정상 시나리오 실구동 (본 검증의 기준 데이터)

`POST /v1/augments/request {videoIds:[26], types:["WINTER"], prompt:{...}}` (REVIEWER) → 200
→ `LS_DATA_AUG` #23 PENDING → 위탁 1청크(`request_id=AUG-5c247d0a-...-1`) → mock 202(`job_id=9d4e...`)
→ 콜백 RUNNING×3 → SUCCEEDED → 롤업 → **aug #23 ACCEPTED, `NEW_RAW_SN=82`**
→ 파생 `LS_DATA_RAW` 82 (`ORGNL_RAW_SN=26`, `AUG_TYPE_CD=WINTER`) + 프레임 5건 + 비디오 복사본 생성 완료.

---

## 1. 판정 요약

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기(분모 제외) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| E-3 (TC-AUG-050~078) | 29 | 23 | 0 | 1 | 0 | 0 | 0 | **5** |
| E-3B (TC-AUG-100~121) | 22 | 22 | 0 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **51** | **45** | **0** | **1** | **0** | **0** | **0** | **5** |

- 유효 분모 46건 기준 PASS율 **97.8%** (PASS 45 / PARTIAL 1)
- **실동작[실동작] 판정 26건**, 정적+단위테스트 근거[정적] 20건
- **근거 file:line 드리프트 13건** — Phase C-3(논블로킹 제출) 리팩터 + `AugmentJobSuccessApplier` 분리로 카탈로그의 근거 위치가 어긋났다(§4 참조). 동작 결함은 아니나 카탈로그 정합성 결함으로 집계한다.
- **self-fill 결함 0건** — 파생 프레임 바이트가 목업 산출물과 완전 일치, 외부 `job_id` 를 자체 발급하지 않음, `Idempotency-Key` 실전송을 목서버 원장으로 역검증.
- **케이스 판정과 별개로 발견한 결함 2건**(E-ISSUE-25 만료 회수 영구 고착 / E-ISSUE-26 앱·DB 시계 9시간 불일치) — 어느 케이스의 기대결과에도 없던 축이라 반증 과정에서 드러났다.

---

## 2. E-3. 증강 결과 웹훅 — `POST /v1/genai/callback` (29행)

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AUG-050 | PASS | [실동작] | 실요청→목서버 위탁→콜백 SUCCEEDED 롤업으로 `LS_DATA_AUG` #23 `ACCEPTED`, 신규 `LS_DATA_RAW` **82** 생성(`ORGNL_RAW_SN=26`). `RAW_FILE_PATH_NM=/app/storage/deidentified/videos/augment/26/82/WINTER.mp4` = **자기 비식별 사본 경로**(부모 원본 경로 폴백 없음). AFTER_COMMIT `AsyncAugmentFrameRunner` 기동 로그 `starting augment frame re-extraction rawSn=82` 확인. ⚠ 관측 시점 `DATA_STTS_CD=COMPLETED`/`DE_IDNTF_YN='Y'` 인데, 이는 케이스가 적은 동기 커밋 시점 값(PENDING/'N')이 async 확정으로 정상 전이된 결과다(코드 주석·`AugmentExtractPersist` 계약과 일치) |
| TC-AUG-051 | PASS | [실동작] | job 2건 중 seq1=FAILED, seq2 성공 콜백 → 롤업 `rollup failed (partial failure — fail-closed) dataAugSn=770001 jobCount=2 failedJobSeqs=[1]` → aug **REJECTED + `RTRY_NMTM`=1 + `DEAD_LETTER_AT`=2026-08-02 08:48:32**, `NEW_RAW_SN` null(영상 미생성) |
| TC-AUG-052 | PASS | [실동작] | ①job 레벨 멱등: 종결(SUCCEEDED) job 재전송 → `200 {"applied":false}` ②aug 레벨 1차 앵커: 종결 aug 에 전 job 성공 롤업 → `200 {"applied":false}`, 중복 영상 0건 |
| TC-AUG-053 | PASS | [실동작] | ② **다른 증강 보유** → `409 CONFLICT "이미 다른 증강 결과에 인계된 작업 ID 입니다."`. 로그 `otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 dataAugSn=770003 ownerDataAugSn=770001`. **쓰기 이전** 선점검사라 500(PG 25P02) 아님 — E-ISSUE-05 해소 확인. ① 자기 자신 보유 재수신은 1차 앵커가 200 으로 흡수(위 052) |
| ~~TC-AUG-054~~ | — | — | **폐기(분모 제외)** |
| TC-AUG-055 | **PARTIAL** | [실동작]+[정적] | ① 미발급 request_id → **401** `"발급되지 않은 request_id 입니다."` 실동작 확인 ② **`job 은 있으나 aug 행 없음 = 404` 분기는 실행 불가능한 dead branch** — `fk_ldaj_data_aug ... ON DELETE CASCADE` 로 aug 삭제 시 job 이 함께 삭제되므로 그 상태가 성립하지 않는다 → **E-ISSUE-21** |
| ~~TC-AUG-056~~ | — | — | **폐기(분모 제외)** — 정책 반전(★1) |
| TC-AUG-057 | PASS | [실동작] | 부모 판정 실패(`origin frame not found`) → `ParentGate.FAIL` → aug **REJECTED + `RTRY_NMTM`=1 + `DEAD_LETTER_AT`**, 파생영상 0건. 로그 `result failed — parent unavailable dataAugSn=770006 reason=origin frame not found`. ※ "프레임 0건" 그 자체는 srcSn 이 실재하면 성립할 수 없어(프레임이 있어야 srcSn 이 있다) 같은 `FAIL` 분기의 도달 가능한 형태로 검증했다 |
| ~~TC-AUG-058~~ | — | — | **폐기(분모 제외)** |
| ~~TC-AUG-059~~ | — | — | **폐기(분모 제외)** |
| TC-AUG-060 | PASS | [실동작] | `status:"CANCELED"` → `400 "status 는 RUNNING\|SUCCEEDED\|FAILED 중 하나여야 합니다."` |
| TC-AUG-061 | PASS | [실동작] | `request_id:"AUG-bad!key"` → 400 / `job_id:".."` → 400(`^(?!\.+$)[A-Za-z0-9_.:-]+$` — 카탈로그 표기보다 **강한** 점-전용 세그먼트 차단이 추가돼 있다, CWE-22) |
| ~~TC-AUG-062~~ | — | — | **폐기(분모 제외)** |
| TC-AUG-063 | PASS | [실동작] | 마지막 job 콜백 **동시 3건** 발사 → 응답 `applied:true` **정확히 1건** + `applied:false` 2건. `LS_DATA_RAW where ORGNL_RAW_SN=26` 5→**6건**(증가 1) — 증강영상 정확히 1건. `findByDataAugSnForUpdate` 선잠금 + PENDING 앵커 이중 방어 실증 |
| TC-AUG-064 | PASS | [실동작] | 부모 `DE_IDNTF_YN='N'`(raw 50) → `ParentGate.FAIL` → aug REJECTED + dead-letter, 파생 0건. 로그 `reason=parent has no deident artifact`. 판정 단일 헬퍼 `LsDataRaw.hasDeidentArtifact()`(`LsDataRaw.java:444-446` — `"Y"\|"F"` 통과) 확인 |
| TC-AUG-065 | PASS | [정적] | `GenAiWebhookIpAllowlist.java:44-77` — 미설정/`none` → `allowed` 빈 리스트 → `isAllowed()` **항상 false**(fail-closed, VLM 과 반대). 단위테스트 4건 전량 통과(baseline). ※ 런타임 실효값이 `0.0.0.0/0` 이라 live 403 재현은 설정 변경이 필요해 수행하지 않음(§10 절대규칙) |
| TC-AUG-066 | PASS | [정적] | `GenAiIntegrationWiringGuard.java:65-76` `verify()` — `mode=http` + allowlist `none`/빈값 → `IllegalStateException`(기동 실패). 단위테스트 6건(문서 docker 형상·env.template 회귀 포함) 통과 |
| TC-AUG-067 | PASS | [실동작] | 보유 `externalJobId`≠콜백 `job_id` → `409 "job_id 가 일치하지 않습니다."`, job 상태 미변경 |
| TC-AUG-068 | PASS | [실동작] | `status:"RUNNING", progress:50` → `200 {"applied":true}`, job `JOB_STTS_CD` `RECEIVED→RUNNING`, aug 는 PENDING 유지(결과처리·롤업 미수행) |
| TC-AUG-069 | PASS | [실동작] | `/etc/passwd` · `/app/genai-out/../../etc/passwd` · **`/app/genai-outEVIL/x.jpg`(접두사 형제)** · **상대경로 `genai-out/../../etc/passwd`** 4형태 전부 `400 "output_file_path 가 허용된 저장 경로가 아닙니다."` + **job 상태 `RECEIVED` 그대로**(재전송 여지 보존) + 응답에 경로 원문 미노출. 메트릭 `AugmentMetrics.REASON_OUTPUT_PATH`(`AugmentMetrics.java:26`) 배선 확인. ⚠ **근거 드리프트**(§4) |
| TC-AUG-070 | PASS | [정적]+[실동작] | `VideoArtifactRootResolver.java:100,108` — `external-read-roots` 기본 빈값 → `readableRoots = allowedRoots`(쓰기 축과 동일, fail-closed). 실효값은 `/app/genai-out` 로 **명시** 확장돼 있고, 그 밖 경로는 위 069 실동작대로 전부 400 |
| TC-AUG-071 | PASS | [실동작] | 위탁 `LS_DATA_AUG_JOB_FILE` 2건 vs `results` 1건 → job `FAILED` / `ERR_CD=RESULT_COUNT_MISMATCH` / `ERR_MSG_CN="위탁 2건 대비 수신 1건"`. 이어진 롤업이 부분실패 규칙으로 증강도 실패 확정(위 051) |
| TC-AUG-072 | PASS | [실동작] | `results` 필드 부재 · `results:[]` 양쪽 **400**, 확정 안 됨. ⚠ 메시지 문구가 카탈로그 기대값과 다름(`"SUCCEEDED 콜백에는 results 가 필요합니다."` → 실제 `"SUCCEEDED 결과에는 results 가 필요합니다."`) → **E-ISSUE-22**(LOW, 카탈로그 정정 대상) |
| TC-AUG-073 | PASS | [실동작] | 2청크 중 1청크만 종결 → `200 {"applied":false}` + 로그 `rollup deferred dataAugSn=910001 pendingJobSeqs=[2]`. job 상태 갱신(FAILED)은 그대로 커밋됨 |
| TC-AUG-074 | PASS | [실동작] | 임계 초과 비종결 job 을 만료 스윕(15분 주기, 09:00:20 tick)이 회수: `claimExpired` 1행 클레임 → job 770011 `FAILED`/`ERR_CD=EXPIRED`/`ERR_MSG_CN="외부 응답 없음 — 무갱신 경과 임계 초과로 만료 종결(…)"` → 전 job 종결이므로 롤업 → aug 770011 `REJECTED` + `RTRY_NMTM`=1 + `DEAD_LETTER_AT`. 로그 `job expired (non-terminal reclaimed) augJobSn=770011 dataAugSn=770011 rollup=APPLIED` / `expired non-terminal jobs count=1 candidates=2`. 후보 2건 중 클레임·확정된 것은 1건뿐(no-op 격리 동작) — 나머지 1건의 실패는 **E-ISSUE-25** 로 별도 기록. 메트릭은 `countJobExpiredAfterCommit()`(`AugmentJobExpiryTxService.java:73`)로 **커밋 이후에만** 증가 |
| TC-AUG-075 | PASS | [실동작] | job 행 0건 + 임계 초과 PENDING 증강(770009) → `expireOrphanPending` → `REJECTED` + `RTRY_NMTM`=1 + `DEAD_LETTER_AT=2026-08-02 09:00:20`. 로그 `orphan pending augment reclaimed (job 0건 · 깨울 주체 없음) dataAugSn=770009 result=APPLIED`. 신고 구간 제외 술어가 없음은 `LsDataAugRepository.findOrphanPendingAugSns`(:125-128) + 단위테스트 `고아_증강_후보는_외부증강_PENDING_조건으로만_조회된다(신고구간_제외_없음)` 로 확인 |
| TC-AUG-076 | PASS | [실동작] | `request_id`/`job_id`/`status` blank → 400(`@NotBlank` 6건 동시 위반 메시지) / `results` **101건** → `400 "results 는 100건을 초과할 수 없습니다."` |
| TC-AUG-077 | PASS | [실동작] | 최상위 `brand_new_field` + `results[].future` 추가 → **200 정상 수신**(`@JsonIgnoreProperties(ignoreUnknown=true)`, `GenAiCallbackRequest.java:41,102`) |
| TC-AUG-078 | PASS | [실동작] | aug=REJECTED 인데 전 job 성공 롤업 → `200 {"applied":false}` + **WARN** `success result discarded — aug already terminal(REJECTED) dataAugSn=770005 otsdJobId=qa0802jobF (강등/반려/취소 — 필요 시 재요청)`. 파생영상 0건 |

---

## 3. E-3B. 증강 외부 위탁 (22행)

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AUG-100 | PASS | [정적]+[실동작] | 상한 상수 `CONTRACT_MAX_INPUT_FILES=100`(`AugmentJobSubmitService.java:141`) + `clampChunkSize`(180-185)가 설정값을 **100 초과로 올릴 수 없게** 고정. 청크 `request_id = {augIdmpKey}-{jobSeq}`(`chunkRequestId`, 516-518) — 실동작에서 `AUG-5c247d0a-...-1` 로 확인. 250장→3분할은 단위테스트 `프레임_250장이면_100_100_50_으로_3개_job_으로_분할_위탁됨` 통과(실환경 최대 프레임이 30장이라 live 다중청크는 미구성) |
| TC-AUG-101 | PASS | [실동작]+[정적] | 외부(mock)가 202 로 발급한 `job_id=9d4e286c28d4483595383617f1ef5b06` 이 `LS_DATA_AUG_JOB.OTSD_JOB_ID`·`LS_DATA_AUG.OTSD_JOB_ID` 에 그대로 적재됨(자체 발급 아님). 응답 검증 3축은 `HttpExternalAugmentClient.validate()` 546-560 — request_id echo 동등비교 + `status=RECEIVED` + `GenAiContract.isValidJobId` (non-blank 를 넘어 **형식까지** 검증). 단위테스트 3건 통과 |
| TC-AUG-102 | PASS | [실동작] | **역검증**: backend 가 쓴 `request_id` 를 `Idempotency-Key` 로 목서버에 직접 재전송 → `[MOCK][GENAI] idempotent replay job_id=9d4e286c... key=AUG-5c247d0a-...-1` + 동일 `job_id` 반환(신규 job 미생성). 목서버 멱등 원장에 그 키가 있다는 것은 **backend 가 실제로 그 헤더를 보냈다**는 뜻이다 |
| TC-AUG-103 | PASS | [정적] | `HttpExternalAugmentClient.java:182-183` `onStatus(is4xxClientError → toNonRetryable4xx)`; 526-534 `releaseBody()` 로 본문 소비·해제(누수 방지) 후 **상태코드만** 실은 `NonRetryableExternalException`(응답 본문 원문 미노출, CWE-209). Retry/CircuitBreaker `ignore-exceptions` 등록. 단위테스트 `4xx_응답은_재시도하지_않는다`/`5xx_응답은_재시도한다`/`연결실패는_재시도한다` 통과. 추가로 디코딩 실패(`isDecodingFailure`, 452-463)도 재시도·서킷 집계에서 제외 |
| TC-AUG-104 | PASS | [정적] | `issueAllChunks`(375-394) 가 **전 청크를 위탁 전에** 선기록하고, 하나라도 실패하면 `failIssuedJobs(..., ERR_ISSUE_RECORD_FAILED, ...)` 로 이미 기록된 앞 청크까지 terminal 종결 후 `Optional.empty()` 반환 → `submit()` 237-240 이 **한 건도 위탁하지 않고** `SubmitOutcome.of(0)`. 단위테스트 4건 통과. ⚠ 근거 드리프트 |
| TC-AUG-105 | PASS | [정적] | `resolveDeidInputFiles`(471-494) — `DE_IDNTF_SRC_FILE_PATH_NM` 이 null/blank 인 프레임이 1건이라도 있으면 `DeidPathMissingException` → `recordRejected(..., ERR_DEID_PATH_MISSING, ...)` + `SubmitOutcome.of(0)`. **원본 경로 폴백 코드 자체가 없다**. 단위테스트 `비식별_경로가_null_인_프레임이_있으면_요청이_거부되고_원본경로가_외부로_나가지_않음`·`빈문자열도_fail_closed`·`input_files_의_모든_file_path_가_비식별_경로다` 통과 |
| TC-AUG-106 | PASS | [정적] | `submit()` **최상단**(209-217)에서 `deidentReportGate.isUnderDeidentReport(rawSn)` → `recordRejected(..., ERR_DEID_REPORT_OPEN, ...)` + `SubmitOutcome.of(0)` → 브릿지(`AugmentRequestBridge.java:124-126`)가 즉시 실패 롤업(REJECTED). **프레임 경로 조회 이전** 차단이 코드 순서로 보장됨. `WITHHELD_*` 는 `AugmentApplyResult` 에서 완전히 제거됨(enum 3값만 존재). 단위테스트 2건 통과 |
| TC-AUG-107 | PASS | [정적] | `submitChunkAsync`(311-357)의 `Mono.defer` 안에서 `index>0` 일 때마다 **무잠금 재판정** → 관측 시 `abortRemainingChunks`(406-415)가 남은 job 을 `FAILED/ERR_DEIDENT_REPORT` 로 종결(비종결 방치 시 롤업 영구 보류 방지). `concatMap` 직렬화(268-299)가 이 방어의 전제이며 `AugmentSubmitSerializationGuardTest.실제_스케줄러에서도_청크는_동시에_나가지_않는다_concatMap_직렬_보장` 로 고정 |
| TC-AUG-108 | PASS | [정적] | `onErrorResume`(339-354) 가 청크 실패를 흡수하고 `outcomeRecorder.onSubmitFailed(...)` 로 `FAILED/SUBMIT_FAILED` 기록 후 `Mono.empty()` → **다음 청크 계속**. 집계 판정은 수신부 롤업 책임. 단위테스트 `분할_위탁_중_2번째_job_실패해도_3번째_가_계속_위탁되고_실패가_기록됨` 통과. ⚠ 근거 드리프트(기록 주체가 `AugmentSubmitOutcomeRecorder` 로 분리) |
| TC-AUG-109 | PASS | [정적] | `SubmitOutcome.requiresFailureRollup()`(451-453) → `AugmentRequestBridge.rollUpFailureIfNothingInFlight`(140-155): **미종결 job 이 하나라도 있으면 롤업하지 않고 return**(동시 재요청 보호), 아니면 `handleInNewTransaction(failed)` 로 REJECTED + dead-letter. Phase C-3 로 "개시 후 전 청크 실패" 는 `AugmentSubmitRollupTxService` 로 이관돼 사각지대 없음(`AugmentAsyncSubmitBoundaryIT` 8건). ⚠ 근거 드리프트 |
| TC-AUG-110 | PASS | [정적] | `AugmentJobSubmitService` 클래스에 **트랜잭션 애너테이션 없음**(136-138) — `@Transactional(readOnly)`·`NOT_SUPPORTED` 재부착 금지 사유가 클래스 Javadoc 82-134 에 실측 근거와 함께 명시. 회귀 가드 `AugmentRequestServiceTest.외부_위탁_HTTP_왕복중에는_트랜잭션_동기화와_EntityManager를_잡지_않는다`(381-)가 관측축을 `isSynchronizationActive()` + `getResource(controlEmf)` 로 두어 `isActualTransactionActive()` 위양성 함정을 회피 — 카탈로그의 ⚠ 경고와 정확히 일치. ⚠ 근거 드리프트 |
| TC-AUG-111 | PASS | [정적] | `EVNT_TYPE_FALLBACK="ETC"`(144) + `resolveEventType`(497-502)가 null/blank 를 `ETC` 로 대체. 단위테스트 `이벤트유형이_없는_영상은_ETC_로_대체된다` 통과 |
| TC-AUG-112 | PASS | [정적]+[실동작] | 위탁 로그(`HttpExternalAugmentClient.java:173-175`)는 `originAugSn/augType/jobSeq/inputCount` 만 — **파일 절대경로 없음**. sanitize 는 CR/LF/TAB 에 더해 `U+2028/U+2029` 까지(106) + 길이 상한 50(`logValue`, 574-581, CWE-770). 실동작 로그 `[Augment] genai submit originAugSn=... inputCount=5` 로 경로 미노출 확인. 거부 로그(`AugmentJobSubmitService` 214-215, 227-228)도 개수만 기록 |
| TC-AUG-113 | PASS | [정적] | `NoopExternalAugmentClient` `@ConditionalOnProperty(havingValue="noop")`(30) ↔ `HttpExternalAugmentClient` `havingValue="http", matchIfMissing=true`(58-59) — 상호배타. 만료 스윕은 자기 토글(`authoring.augment.job-expiry.enabled`)만 보므로(`AugmentJobExpirySweeper.java:86,103-108`) noop 환경에서도 후보 0건으로 무해. 단위테스트 `ExternalAugmentClientBeanConditionTest`·`NoopExternalAugmentClientTest`·`스윕이_남의_스케줄러_활성원에_의존하지_않는다` 통과 |
| TC-AUG-114 | PASS | [실동작]+[정적] | **①읽기 허용 루트**: `verifyUnderAllowedRoots`(`AugmentFrameProducer.java:171`)가 lexical + `toRealPath()` 양쪽 검증(CWE-59) **②정규 파일 + size>0**: 134-150 **③해상도 동일**: 152-158 (다르면 CONFLICT). 하나라도 어긋나면 all-or-nothing 실패. 단위테스트 8건 통과. **실동작 결정적 근거** — 파생 프레임과 목서버 산출물의 바이트 크기가 **완전 일치**: `/app/genai-out/genai/9d4e.../001_frame-0_genai.jpg` 13164B ↔ `/app/storage/deidentified/frames/deid/82/frame-0.jpg` 13164B (5장 전부 일치) → **외부 산출물 실반입, self-fill 아님** |
| TC-AUG-115 | PASS | [정적] | `PART_SUFFIX=".part"`(55) + `copyAtomically`(219-233): 소스를 `Files.newInputStream(src, NOFOLLOW_LINKS)` 로 열고(CWE-59/367) `.part` 에 쓴 뒤 `Files.move(..., ATOMIC_MOVE)`. 단위테스트 `프레임_반입_중_실패해도_반쯤_채워진_프레임셋이_남지_않는다`·`허용루트_안의_심링크가_밖을_가리켜도_반영되지_않는다` 통과 |
| TC-AUG-116 | PASS | [실동작] | 파생 비디오 `/app/storage/deidentified/videos/augment/26/82/WINTER.mp4` **50,854B** = 부모 비식별 영상 `/app/storage/raw/autolabel-test/26/deid/95b05485-...-mask.mp4` **50,854B** → **실제 복사** 확인. 소스 경로는 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 값을 읽어 정함(`AugmentExtractSnapshot`) — 파일명이 KPST 규약 `{stem}-mask.mp4` 라 조합·추측으로는 나올 수 없는 값이다. 소스 부재 시 NOT_FOUND(원본 폴백 없음)는 `copyDeidVideo`(108-119) |
| TC-AUG-117 | PASS | [실동작] | 파생 raw 82 의 5개 프레임 전부 `SRC_FILE_PATH_NM` **null(공백)**, `DE_IDNTF_SRC_FILE_PATH_NM=/app/storage/deidentified/frames/deid/82/frame-N.jpg`. 두 컬럼 동일값 아님 → 마트 뷰 "두 경로 상이" 불변식 유지 |
| TC-AUG-118 | PASS | [실동작]+[정적] | 증강 파생 라벨맵 실측: `data_aug_sn=1/2/14` 전부 `COORD_RECALC_YN='N'`, `SCALE_X/Y` null. (대조군: 해상도 파생 `data_aug_sn=5/6/7` 은 `'Y'` + `SCALE 4.5/3.0/2.0`) — 축이 정확히 갈린다. 단위테스트 `라벨_복사시_LS_DATA_AUG_LBL_MAP이_COORD_RECALC_N으로_저장된다` 통과. 해상도 불일치는 TC-AUG-114 가 선차단 |
| TC-AUG-119 | PASS | [실동작] | 존재하지 않는 `output_file_path` 로 Phase B 를 실패시킴 → ①`cleanup` 확인: `videos/augment/26/` 에 실패분(96·99) 디렉터리 **미잔존**, `frames/deid/` 에도 96·99 없음 ②`markRawDataFailed`: `LS_DATA_RAW` 99 `DATA_STTS_CD=FAILED` ③`markAugProcessingFailed`: aug 770010 `DEAD_LETTER_AT=2026-08-02 08:53:41` 이면서 `AUG_PROC_STTS_CD=ACCEPTED` **미변경**(멱등 앵커 무충돌) — 케이스 기대와 정확히 일치. 로그 `augment frame re-extraction failed rawSn=99 dataAugSn=770010 cause=CustomException` |
| TC-AUG-120 | PASS | [실동작] | 파생 raw 82 의 `LS_DATA_META`: `video.filesize=50854`(= 복사된 사본 크기), `video.resolution=320x240`, `video.fps=10.0`, `video.codec=h264`, `video.bit_rate=79688`, `video.duration_ms=5000` → **사본 확정(Phase C) 이후** 그 사본을 probe 한 값. `AsyncAugmentFrameRunner.java:106-121` 이 확정 블록 **밖** 별도 catch 로 기동(레이스 제거·메타 실패가 확정을 되돌리지 않음). 단위테스트 `사본_생성_전에는_probe_하지_않는다`·`기술메타_트리거_실패가_확정된_파생본을_되돌리지_않는다` 등 4건 통과 |
| TC-AUG-121 | PASS | [정적] | `AsyncAugmentFrameRunner.java:92-97` — Phase C `SKIPPED`(중복 트리거 패자)면 cleanup·FAILED 전이 모두 skip(파일이 승자와 동일 경로). 단위테스트 `PhaseC가_SKIPPED_중복트리거패자면_cleanup도_FAILED전이도_하지않는다`·`중복트리거_패자면_기술메타_추출도_트리거하지_않는다` 통과 |

---

## 4. 근거(file:line) 드리프트 — 카탈로그 정합성 결함 13건

2026-07-30 카탈로그 작성 이후 **Phase C-3(논블로킹 제출 전환)** 과 **`AugmentJobSuccessApplier` 분리(S15)** 로 구현이 이동했다. 동작은 전부 케이스 기대와 일치하므로 판정은 PASS 지만, 근거 위치가 어긋나 다음 회차 대조가 불가능하다.

| TC | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-AUG-069 | `GenAiCallbackService.java:214-237` | `AugmentJobSuccessApplier.java:64-86` (`verifyOutputPaths`) |
| TC-AUG-071 | `GenAiCallbackService.java:175-193` | `AugmentJobSuccessApplier.java:101-121` (`applySucceeded`) |
| TC-AUG-072 | `GenAiCallbackService.java:214-221` | `AugmentJobSuccessApplier.java:64-70` |
| TC-AUG-050 | `GenAiCallbackService.java:96-164` | `GenAiCallbackService.java:90-158`(handle) + `AugmentResultService.java:409-452` |
| TC-AUG-051 | `AugmentResultService.java:226-232,317-320` | `AugmentResultService.java:230-236, 321-324` |
| TC-AUG-053 | `AugmentResultService.java:204-207,267-302` | `AugmentResultService.java:208-211, 271-306` |
| TC-AUG-063 | `GenAiCallbackService.java:108-114,37-43` | `GenAiCallbackService.java:102-114` |
| TC-AUG-073 | `GenAiCallbackService.java:155-163` | `GenAiCallbackService.java:149-157` |
| TC-AUG-100 | `AugmentJobSubmitService.java:112,143-148,385-399` | `:141, 180-185, 504-518` |
| TC-AUG-104 | `AugmentJobSubmitService.java:198-260` | `:236-241, 375-394` (`issueAllChunks`) |
| TC-AUG-106 | `AugmentJobSubmitService.java:170-180` | `:207-217` |
| TC-AUG-107 | `AugmentJobSubmitService.java:207-219,262-281` | `:311-357`(defer 재판정) + `:406-415`(abort) |
| TC-AUG-108 | `AugmentJobSubmitService.java:324-344` | `:339-354` + `AugmentSubmitOutcomeRecorder.onSubmitFailed` |
| TC-AUG-109 | `AugmentJobSubmitService.java:304-314` | `:444-453`(`SubmitOutcome`) + `AugmentSubmitRollupTxService` |
| TC-AUG-110 | `AugmentJobSubmitService.java:53-106,160-166` | `:82-138`(Javadoc) + `:136-138`(무애너테이션) |

→ **E-ISSUE-23**

---

## 5. 이슈

### [E-ISSUE-21] TC-AUG-055 — "job 은 있으나 aug 행 없음 = 404" 분기가 도달 불가능한 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 위탁 job 원장(`LS_DATA_AUG_JOB`)에 키는 있는데 대상 증강 행이 사라진 상태에서 콜백이 오면 404 로 종결해, NPE/500 대신 명확한 실패를 회신해야 한다.
- **현재 동작(이슈 내용)**: 그 상태 자체가 스키마상 성립하지 않는다.
  ```
  Foreign-key constraints:
    "fk_ldaj_data_aug" FOREIGN KEY (data_aug_sn) REFERENCES ls_data_aug(data_aug_sn) ON DELETE CASCADE
  ```
  aug 행이 지워지면 job 행도 같이 지워지므로 `GenAiCallbackService.java:103-105`
  ```java
  augRepository.findByDataAugSnForUpdate(dataAugSn)
          .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 행을 찾을 수 없습니다: dataAugSn=" + dataAugSn));
  ```
  의 `orElseThrow` 는 실행될 수 없다(다층 방어로서는 정당하나 **케이스로는 검증 불가**).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "\d ls_data_aug_job"` → FK CASCADE 확인. `DELETE FROM ls_data_aug WHERE data_aug_sn=N` 후 `SELECT * FROM ls_data_aug_job WHERE data_aug_sn=N` → 0행.
- **영향**: 기능/보안 영향 없음. **테스트케이스 카탈로그의 검증 불가 항목**이라 다음 회차에도 "확인 못 함" 이 반복된다.
- **수정 방향(제안)**: 코드는 그대로 둔다(fail-closed 다층 방어). **카탈로그**에서 TC-AUG-055 를 ①401(도달 가능)만 남기고 ②404 는 "FK CASCADE 로 도달 불가 — 방어 코드로만 존치" 비고로 강등한다.

### [E-ISSUE-22] TC-AUG-072 — 카탈로그 기대 메시지와 실제 응답 문구 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 기대결과의 문구가 실제 응답과 일치해야 회차 간 대조가 성립한다.
- **현재 동작(이슈 내용)**: 카탈로그 기대 `400("SUCCEEDED 콜백에는 results 가 필요합니다.")` ↔ 실제 응답
  ```json
  {"success":false,"message":"SUCCEEDED 결과에는 results 가 필요합니다.","errorCode":"INVALID_INPUT"}
  ```
  `AugmentJobSuccessApplier.java:69` — 웹훅/결과조회 회수 **공용** 원천으로 분리되면서 "콜백" → "결과" 로 일반화된 것으로 보인다(코드가 의도적이며 정확하다).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' \
    -d '{"request_id":"<발급된키>","job_id":"<보유jobid>","status":"SUCCEEDED"}'
  ```
- **영향**: 기능 영향 없음. 카탈로그 정합성.
- **수정 방향(제안)**: 카탈로그 TC-AUG-072 기대 문구를 `"SUCCEEDED 결과에는 results 가 필요합니다."` 로 정정.

### [E-ISSUE-23] E-3/E-3B 근거 file:line 드리프트 13건 (Phase C-3 + AugmentJobSuccessApplier 분리 미반영)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 케이스의 `근거(file:line)` 는 다음 회차 검증자가 곧바로 열어 대조할 수 있어야 한다. 카탈로그는 검증 산출물의 입력 문서다.
- **현재 동작(이슈 내용)**: §4 표의 13건이 어긋난다. 대표 2건:
  - TC-AUG-069/071/072 의 근거 `GenAiCallbackService.java:214-237` 에는 이제 그 로직이 없다. `GenAiCallbackService` 는 172-175 에서 `successApplier` 에 위임만 한다.
    ```java
    private void applySucceeded(LsDataAugJob target, GenAiCallbackRequest req, String requestId) {
        List<String> outputs = successApplier.verifyOutputPaths(outputFilePathsOf(req), requestId);
        successApplier.applySucceeded(target, req.jobId(), outputs, requestId);
    }
    ```
  - TC-AUG-100/104/106~110 의 근거는 **동기 `.block()` 제출** 시절 라인이다. 현재는 `Flux.concatMap` 기반 논블로킹 제출(`dispatchChunks` 268-299, `submitChunkAsync` 311-357)로 전면 교체됐고 결과 기록은 `AugmentSubmitOutcomeRecorder`/`AugmentSubmitRollupTxService` 로 이관됐다.
- **재현/확인 경로**: 각 케이스의 근거 경로를 Read 후 §4 표의 실제 위치와 대조.
- **영향**: 기능 영향 없음. 다음 회차 검증 비용 증가 + 오판(코드가 사라진 것으로 오인) 위험.
- **수정 방향(제안)**: `docs/test-cases/E-augment-resolution-export-meta.md` 의 E-3/E-3B 근거 컬럼을 §4 표대로 갱신. 아울러 **신규 빈 3종**(`AugmentJobSuccessApplier`·`AugmentSubmitOutcomeRecorder`·`AugmentSubmitRollupTxService`)에 대한 케이스 신설을 검토(현재 `AugmentAsyncSubmitBoundaryIT` 8건이 커버하는 "지각 ACK 가 콜백 상태를 강등하지 않는다" 축은 카탈로그에 대응 케이스가 없다).

### [E-ISSUE-24] `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 데이터 존재 — 파생 생성 게이트가 대소문자 민감
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 산출물 보유 판정(`LsDataRaw.hasDeidentArtifact()`)은 파생 생성(증강·해상도)의 단일 진실원이므로, 컬럼 값 도메인이 `Y`/`F`/`N` 으로 닫혀 있어야 판정이 결정적이다.
- **현재 동작(이슈 내용)**: DB 에 `de_ident_yn='y'`(소문자) 행이 1건 있다.
  ```
  raw_sn=68 | de_ident_yn = y | data_stts_cd = MARKING_READY
  ```
  판정기는 대소문자 민감이다 (`LsDataRaw.java:444-446`):
  ```java
  public boolean hasDeidentArtifact() {
      return "Y".equals(this.deIdntfYn) || "F".equals(this.deIdntfYn);
  }
  ```
  즉 `'y'` 행은 **비식별 산출물 없음**으로 판정돼 증강 콜백이 오면 `ParentGate.FAIL`(REJECTED + dead-letter)로 끝난다. 방향은 fail-closed(안전)이지만, 운영자는 "비식별 완료된 영상인데 증강이 계속 실패" 로 보인다. 쓰기 경로(`markDeidentified`)는 `Y/F/N` 만 허용하므로 이 값은 **코드 외 경로(수동 SQL·검증 세션 등)** 로 들어온 것이다.
- **재현/확인 경로**:
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE de_ident_yn NOT IN ('Y','F','N');
  ```
- **영향**: 데이터 정합. 보안 노출 없음(안전한 방향으로 실패). 다만 컬럼에 DB 레벨 도메인 제약이 없어 같은 오염이 재발할 수 있다.
- **수정 방향(제안)**: ①오염 행 정정(`UPDATE ... SET de_ident_yn='Y' WHERE de_ident_yn='y'`) ②`LS_DATA_RAW.DE_IDNTF_YN` 에 `CHECK (DE_IDNTF_YN IN ('Y','F','N'))` 제약 추가 검토(Flyway). ⚠ 본 검증에서는 **수정하지 않았다**.

### [E-ISSUE-25] 만료 스윕의 롤업이 예외로 끝나면 클레임까지 롤백돼 그 job 이 **영구 회수 불가**(15분마다 무한 재시도)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 만료 스윕은 "비종결 job 은 반드시 종결된다"를 보장하는 최후 회수 장치다. `LsDataAugJobRepository.claimExpired` Javadoc·`GenAiCallbackService` 주석 모두 "재시도가 소진돼도 만료 스윕이 회수하므로 **무한 대기는 없다**"를 계약으로 명시한다. 따라서 회수 자체가 반복 실패하면 그 사실이 종결(dead-letter)되거나 최소한 격리돼야 한다.
- **현재 동작(이슈 내용)**: `AugmentJobExpiryTxService.expire`(63-89)는 **클레임 UPDATE 와 롤업이 같은 트랜잭션**이다.
  ```java
  @Transactional("controlTransactionManager")
  public boolean expire(Long augJobSn, Long dataAugSn, LocalDateTime cutoff) {
      ...
      if (jobRepository.claimExpired(augJobSn, cutoff, LsDataAugJob.ERR_EXPIRED, EXPIRY_REASON, LocalDateTime.now()) == 0) return false;
      ...
      AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobIdOf(jobs)); // ← 여기서 throw 하면
      ...                                                                                              //   위 클레임까지 롤백된다
  ```
  `rollUpIfAllTerminal` → `AugmentResultService.handle` 은 `requireJobIdNotOwnedByOtherAug`(271-285)에서 **다른 증강이 같은 `otsd_job_id` 를 보유하면 `CONFLICT`(409)를 던진다**. 스윕은 `RuntimeException` 을 잡아 ERROR 로그만 남기고 다음 후보로 넘어가므로(`AugmentJobExpirySweeper.java:219-224`) job 상태는 `RECEIVED` 그대로 → **다음 tick 의 후보 쿼리(`findExpirableAnchors`, `MDFCN_DT < cutoff`)에 다시 잡혀 같은 예외를 영구 반복**한다. 회수 실패에 대한 재시도 상한·dead-letter·별도 알림이 없다.
  실측(09:00:20 tick):
  ```
  WARN  AugmentResultService  otsd_job_id 선점 충돌(다른 증강 보유) — 409 종결 dataAugSn=770003 ownerDataAugSn=770001 otsdJobId=qa0802jobD
  ERROR AugmentJobExpirySweeper  [Augment][Expiry] expire failed augJobSn=770004 reason=CustomException
  ```
  ```
  aug_job_sn=770004 | job_stts_cd=RECEIVED | err_cd=(null)   ← 클레임이 롤백돼 회수 안 됨
  data_aug_sn=770003 | aug_proc_stts_cd=PENDING | dead_letter_at=(null)  ← 증강도 PENDING 영구 고착
  ```
- **재현/확인 경로**: 서로 다른 증강 2건의 job 이 **같은 `OTSD_JOB_ID`** 를 갖게 만든 뒤 한쪽을 만료시킨다. `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에는 UNIQUE 가 없고(`idx_ldaj_otsd_job_id` 는 비유니크) `LS_DATA_AUG.OTSD_JOB_ID` 에만 UNIQUE(`uk_aug_external_job_id`)가 있어 이 상태가 스키마상 허용된다.
  ```sql
  -- 재현 데이터(본 검증에서 실제로 사용)
  INSERT INTO ls_data_aug_job(aug_job_sn,data_aug_sn,job_seq,idmp_key,otsd_job_id,job_stts_cd,tot_nocs)
  VALUES (770004, 770003, 1, 'QA0802-E2-C-1', 'qa0802jobD', 'RECEIVED', 1);  -- 'qa0802jobD' 는 aug 770001 이 이미 보유
  UPDATE ls_data_aug_job SET mdfcn_dt = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE aug_job_sn=770004;
  -- 다음 스윕 tick(최대 15분) 후 → ERROR expire failed, 상태 변화 없음. 이후 매 tick 반복.
  ```
- **영향**: 기능/데이터 정합(liveness). 트리거는 **벤더가 서로 다른 `request_id` 에 같은 `job_id` 를 반환**하는 계약 이상이라 발생 확률은 낮지만, 발생하면 그 증강은 사람이 DB 를 직접 손대기 전까지 `PENDING` 에서 나오지 못하고 15분마다 실패 트랜잭션 1건 + ERROR 로그를 영구히 생성한다. 같은 구조상 롤업이 던지는 **모든** 예외(`DataIntegrityViolationException` → 409 포함)가 동일 결과를 낳는다. 보안 노출은 없다.
- **수정 방향(제안)**: ①`expire()` 에서 **클레임 커밋과 롤업을 분리**한다(클레임을 `REQUIRES_NEW` 로 먼저 커밋하면 롤업이 실패해도 job 은 종결 상태로 남아 다음 tick 이 재선정하지 않고, 롤업 재시도는 "전 job 종결" 경로가 다시 집는다). ②또는 회수 실패 횟수를 `LS_DATA_AUG_JOB` 에 누적해 임계 초과 시 dead-letter 로 종결한다. ③부수적으로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 UNIQUE 를 걸어 이 데이터 이상 자체를 입구에서 막는 안도 검토(단, 벤더 중복 반환 시 위탁이 깨지므로 트레이드오프 확인 필요).

### [E-ISSUE-26] 앱(KST)과 DB DEFAULT(UTC)가 같은 `timestamp without time zone` 컬럼에 **9시간 어긋난 시계**로 기록 — 만료 판정의 잠재 오작동
- **심각도**: LOW
- **기대 동작(기대효과)**: 만료 스윕은 `LocalDateTime.now() - idleTimeoutMinutes` 를 `MDFCN_DT`/`REG_DT` 와 직접 비교한다. 두 값이 같은 시계여야 임계(기본 360분)가 의미를 갖는다.
- **현재 동작(이슈 내용)**: 앱은 `-Duser.timezone=Asia/Seoul`(`backend/Dockerfile:42`) + `hibernate...time_zone: Asia/Seoul`(`application.yml:48`)이라 `LocalDateTime.now()` 가 **KST 벽시계**를 그대로 적재하는 반면, DB 세션은 `Etc/UTC` 라 컬럼 DEFAULT(`ls_data_aug_job.reg_dt/mdfcn_dt`, `ls_data_aug.reg_dt` 등 `DEFAULT CURRENT_TIMESTAMP`)로 채워진 값은 **UTC** 다.
  ```
  DB : show timezone → Etc/UTC ;  current_timestamp → 2026-08-02 00:01:50
  APP: 같은 순간 JPA 가 적재한 mdfcn_dt → 2026-08-02 09:00:20   (Δ = 9h)
  ```
  실측: DB DEFAULT 로 만들어진 job 행(`mdfcn_dt` = UTC now)이 **생성 직후** 스윕의 `MDFCN_DT < now(KST) - 360분` 조건을 만족해 즉시 만료됐다(`augJobSn=770011`). 앱 경로(`LsDataAugJob.createIssued` 161-168 등)는 항상 `LocalDateTime.now()` 를 명시 대입하므로 **현재 운영 흐름에서 관측된 오작동은 없다** — 노출은 DB DEFAULT 에 의존하는 경로(Flyway 백필·운영 SQL·본 검증 같은 수동 INSERT)로 한정된다.
- **재현/확인 경로**:
  ```sql
  -- DB 기본값으로 job 을 만들고 스윕 tick(최대 15분)을 기다린다 → 임계 미도달인데 EXPIRED 로 종결된다
  INSERT INTO ls_data_aug_job(aug_job_sn,data_aug_sn,job_seq,idmp_key,otsd_job_id,job_stts_cd,tot_nocs)
  VALUES (…, 'RECEIVED', 1);   -- reg_dt/mdfcn_dt 는 DEFAULT CURRENT_TIMESTAMP(UTC)
  ```
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c "show timezone;"   # Etc/UTC
  docker exec klid-backend env | grep JAVA_OPTS                                     # -Duser.timezone=Asia/Seoul
  ```
- **영향**: 데이터 정합(시각 축). 현재는 잠복이며, 백필 마이그레이션이 이 컬럼들을 DEFAULT 로 채우면 **아직 살아 있는 위탁 job 이 즉시 만료 종결**돼 증강이 실패 확정될 수 있다.
- **수정 방향(제안)**: ①DB 세션/컨테이너 TZ 를 `Asia/Seoul` 로 통일하거나 ②컬럼 DEFAULT 를 제거해 시각 기록 주체를 앱 하나로 못박는다(권장 — 시계 단일 원천). ③장기적으로는 `timestamptz` 전환 검토. ⚠ 본 검증에서는 **아무것도 변경하지 않았다**.

---

## 6. 다른 파트에 넘길 관찰 (본 담당 범위 밖 — 참고용, 이슈 미발행)

1. **E-1 계약 변경 미반영**: `POST /v1/augments/request` 가 `prompt`(5필드 전부 필수)를 **필수**로 요구한다(`AugmentRequestRequest.java:52-55`, 2026-07-31 신설). 카탈로그 TC-AUG-001 의 요청 예시 `{videoIds:[N], types:["WINTER"]}` 는 이제 **400** 이다(실측). E-1 담당이 반영해야 한다.
2. **V143 활성 유니크 인덱스 소멸**: `\d ls_data_aug` 에 `UK_LS_DATA_AUG_ACTVTN` 이 **없다**. Flyway V153 `add ls data aug prompt and drop active unique` 가 드롭했다. TC-AUG-012/013/014(중복 활성 요청 409 + DB 최종 방어)의 전제가 바뀌었으므로 E-1 담당이 재판정해야 한다.
3. **병렬 에이전트 간섭 주의**: 검증 중 `data_aug_sn` 900001~900004 가 다른 에이전트에 의해 생성/삭제되는 것을 관측했다. 본 파트는 이를 피해 **770001~770011 / `src_sn` 770100** 대역만 사용했다.

## 7. 본 검증이 남긴 테스트 데이터 (정리 대상)

수정 금지 규칙에 따라 **삭제하지 않고 기록만** 한다. 전부 INSERT 로 만든 검증 전용 행이다.

| 테이블 | 키 | 비고 |
|---|---|---|
| `ls_data_aug` | 770001, 770003, 770005~770011 | 검증용 aug (REJECTED/PENDING/ACCEPTED 혼재) |
| `ls_data_aug_job` | 770001~770004, 770005~770011 | 검증용 job |
| `ls_data_aug_job_file` | 770002~770011 | |
| `ls_data_src` | 770100 (`raw_sn=50`) | TC-AUG-064 부모 `'N'` 게이트 재현용 |
| `ls_data_raw` | 82(정상 파생·유지 권장), 96·99(async 실패로 FAILED) | 82 는 정상 시나리오 산출물 |
| `ls_data_aug` | 23 (`NEW_RAW_SN=82`) | 정상 시나리오 산출물 |
