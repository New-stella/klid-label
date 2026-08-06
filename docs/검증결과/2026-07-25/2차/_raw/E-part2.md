# E 클러스터 part2 (E-3·E-3B) 2차 검증 결과

> 대상: `docs/test-cases/E-augment-resolution-export-meta.md`
> §E-3 증강 결과 웹훅 `POST /v1/genai/callback` (29행) · §E-3B 증강 외부 위탁 (22행) = **51행**
> 환경: 로컬 도커 스택 · backend `localhost:18081`(HEAD `ca3c712b`) · mock `:9400` · PG `:5432`(스키마 `public`)
> 검증 일시: 2026-07-31 03:53 ~ 04:05 KST · backend 재기동 0회

## 집계

| 구분 | 행 수 | 폐기(집계 제외) | 검증 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| E-3 (증강 웹훅) | 29 | 5 | 24 | 23 | 1 | 0 | 0 | 0 | 0 |
| E-3B (외부 위탁) | 22 | 0 | 22 | 21 | 1 | 0 | 0 | 0 | 0 |
| **합계** | **51** | **5** | **46** | **44** | **2** | **0** | **0** | **0** | **0** |

> 폐기 5건(`~~취소선~~`): TC-AUG-054(augType 대조 제거) · 056(신고 'F' 게이트 폐기 — ★확정 정책) ·
> 058(`raw_file_path_nm` 필드 삭제) · 059(`AugmentResultRequest` 제거) · 062(동상). 집계 제외.

**신규 이슈 5건** — HIGH 1 / MEDIUM 1 / LOW 3.
실동작 판정 비율: E-3 24건 중 **20건**, E-3B 22건 중 **9건**(나머지는 250장 분할·4xx 강제·noop 모드 등
현 형상에서 물리적으로 재현 불가 → `[정적]`+테스트 커버).

### 이번 회차 핵심

1. **1차 CRITICAL 4건(E-ISSUE-01/04/05/32)은 전부 해소**됐다. 특히 1차 FAIL 이던 **TC-AUG-053(동시/오배송
   UNIQUE 흡수)** 은 이번에 **PostgreSQL 실 DB 에서 409 로 정상 종결**(500 아님, 트랜잭션 오염 없음)을
   실측 확인했다 — 1차 위양성(Mockito 스텁으로 25P02 미재현)의 실체가 코드 수정으로 실제 닫혔다.
2. **외부 산출물 반입은 self-fill 이 아니다** — 파생 프레임 md5 가 mock 산출 파일과 **바이트 동일**,
   파생 비디오 md5 가 부모 비식별 영상과 **바이트 동일**(경로는 `LS_DEIDENT_PROC_LOG` 값 읽기).
3. **신규 HIGH `E-ISSUE-21`** — 벤더 산출 경로의 **읽기 허용 루트에 우리 원본(비-비식별) 저장소가 포함**돼,
   콜백 하나로 **원본 PII 프레임이 파생영상의 "비식별 프레임"(`DE_IDNTF_YN='Y'`)으로 반입**된다.
   실증: rawSn **167** 의 비식별 프레임 md5 = rawSn 152 **원본 프레임** md5(`fae1cc00…`).
   G-ISSUE-44(checksum 미검증)와 결합해 내용 진정성 방어가 0이다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| **E-ISSUE-01** (CRITICAL) | HMAC 웹훅 필터 경로 변형 우회 | 신 계약은 무서명 3계층(IP allowlist → rate limit·size cap → **request_id 발급 게이트**)으로 전면 교체. `WebhookGateInterceptor` 가 "필터 통과 증거" 재확인. A-part2 가 경로 변형 25종 전건 401 실측 | **✅ 해소(구조 교체)** |
| **E-ISSUE-04** (CRITICAL) | 시크릿 빈 값 → 정상 콜백 전건 401 | `WEBHOOK_HMAC_SECRET_AUGMENT` 는 존재하나 `SIGNATURE_REQUIRED = List.of()` 라 서명 요구 경로 0개. genai 콜백은 계약상 무서명이며 **정상 콜백이 200 으로 완주**(실측 rawSn 159·172·175 생성) | **✅ 무효화(전제 소멸)** |
| **E-ISSUE-05** (CRITICAL) | UNIQUE 위반 후 멱등 흡수가 PG 에서 500 | **쓰기 이전 선점 검사**(`requireJobIdNotOwnedByOtherAug`)로 회피. 실측: 다른 증강(aug 30)이 보유한 `job_id` 로 aug 39 롤업 → **409 `이미 다른 증강 결과에 인계된 작업 ID 입니다.`**, job 10 `RECEIVED` 유지 · aug 39 `PENDING` 유지(트랜잭션 롤백 정상) | **✅ 해소(실동작)** |
| **E-ISSUE-32** (MEDIUM) | `markDeadLetter()` 프로덕션 호출자 0건 | `AugmentResultService.markProcessingFailure(:317-320)` 가 `incrementRetryCount()+markDeadLetter()` 를 실패 인계 단일 깔때기에서 호출. 실측 dead-letter 기록 **5건**(aug 35·38·50·52·39·53·56) | **✅ 해소(실동작)** |
| TC-AUG-053 (1차 FAIL) | 동시/오배송 UNIQUE 흡수 실패 | 위 E-ISSUE-05 + **동시 콜백 4발 병렬** 실측(1×`applied:true` + 3×`applied:false`, 파생영상 **정확히 1건**=rawSn 172) | **✅ 해소(실동작)** |
| — (신규) | `markAccepted()` 프로덕션 호출자 0건 | 같은 dead-code 형태가 **`AugmentJobRecorder.markAccepted`(:60-62)** 에 재발 → `E-ISSUE-23` | ⚠ 신규 |

---

## ★ 콜백 이상 시나리오 실측

> 모두 `POST http://localhost:18081/api/v1/genai/callback` 실호출. 동시 검증 중인 타 에이전트와 구분하려고
> 요청 ID 를 전부 내가 발급받은 `AUG-…` 청크 키 또는 `e3-` 접두 위조값으로 썼다.

| # | 시나리오 | 요청 | 기대 | 실측 | DB 결과 |
|---|---|---|---|---|---|
| 1 | 위조 request_id | `request_id=e3-forged-0001` | 401 | **401** `발급되지 않은 request_id 입니다.` + 필터 `downstream auth rejected … status=401`(rate limit 집계) | 변화 0 |
| 2 | 미지 필드 추가 | `brand_new_field`,`another` | 수신 성공 | **401**(= 역직렬화 통과 후 발급 게이트에서 거부 → 미지 필드 무시 확인) | 변화 0 |
| 3 | status 화이트리스트 | `CANCELED` / `succeeded` | 400 | **400** `status 는 RUNNING\|SUCCEEDED\|FAILED 중 하나여야 합니다.` | 변화 0 |
| 4 | request_id 형식·길이 | `"e3 bad!"` / 129자 | 400 | **400** 패턴 위반 / `size must be between 0 and 128` | 변화 0 |
| 5 | job_id 형식·길이 | `job/../x` / 201자 | 400 | **400** 패턴 위반 / `size must be between 0 and 200` | 변화 0 |
| 6 | 필수 누락 | `{}` / 전부 blank | 400 | **400** `requestId/jobId/status: must not be blank` | 변화 0 |
| 7 | results 101건 | 101개 배열 | 400 | **400** `results 는 100건을 초과할 수 없습니다.` | 변화 0 |
| 8 | media_type 위반 | `AUDIO` | 400 | **400** `results[0].mediaType: media_type 은 IMAGE\|VIDEO 중 하나여야 합니다.` | 변화 0 |
| 9 | progress 범위 | `101` | 400 | **400** `must be less than or equal to 100` | 변화 0 |
| 10 | **job_id 오배송** | 발급 request_id + `job_id=e3-wrong-job-id` | 409 | **409** `job_id 가 일치하지 않습니다.` + WARN(expected/received 로그) | job 1 상태·mdfcn_dt 불변 |
| 11 | **replay(동일 콜백 2회)** | 종결 job 에 동일 SUCCEEDED | 200 `applied:false` | **200** `{"applied":false}` + `duplicate callback absorbed … state=SUCCEEDED` | 불변 |
| 12 | replay — FAILED 상태로 | 종결 job 에 FAILED | 200 `applied:false` | **200** 동일 | 불변 |
| 13 | **진행률 역전** | RUNNING 50 → RUNNING 10 | (계약상 무순서 허용) | **200 both, `applied:true`** — 순서 검증 없음(설계상 상태만 갱신) | `JOB_STTS_CD=RUNNING` |
| 14 | SUCCEEDED + results 없음/빈배열 | `results` 누락 / `[]` | 400 | **400** `SUCCEEDED 콜백에는 results 가 필요합니다.` + WARN + metric | job 상태 불변(`RUNNING` 유지) |
| 15 | output 경로 밖 | `/etc/passwd` | 400 | **400** `output_file_path 가 허용된 저장 경로가 아닙니다.` + WARN(경로 원문 미노출) | job 상태 불변 |
| 16 | output 경로순회 | `/app/genai-out/../etc/passwd` | 400 | **400** 동일 | 불변 |
| 17 | **위탁 3건 vs 수신 1건** | job 9(tot_nocs=3)에 results 1건 | job FAILED + 실패 롤업 | **200 `applied:true`**, job 9 `FAILED/RESULT_COUNT_MISMATCH` "위탁 3건 대비 수신 1건" | aug 38 `REJECTED`, `RTRY_NMTM=1`, `DEAD_LETTER_AT` 기록, 신규 영상 0 |
| 18 | **FAILED 상태 콜백** | `error_code=MODEL_EXECUTION_FAILED`, message 에 개행 포함 | 실패 롤업 | **200**, job 14 FAILED, 로그 `message=vendor side failure_line2`(CR/LF sanitize) | aug 50 `REJECTED`+dead-letter, 신규 영상 0 |
| 19 | **부모 비식별 미완료('N')** | 콜백 직전 부모 152 를 `'N'` 로 주입(직후 원복) | REJECTED+dead-letter | **200**, WARN `result failed — parent unavailable … reason=parent has no deident artifact` | aug 53 `REJECTED`+dead-letter, 신규 영상 0 |
| 20 | **부모 신고 구간('F')** | 콜백 직전 부모 152 를 `'F'` 로 주입(직후 원복) | **파생 생성**(★확정 정책) | **200 `applied:true`** | aug 55 `ACCEPTED`, **파생 rawSn 175 생성** — 정책대로 |
| 21 | **동시 콜백 4발 병렬** | 동일 SUCCEEDED × 4 (job 13) | 롤업 1회 | **200×4** — `applied:true` 1건 + `applied:false` 3건 | aug 49 `ACCEPTED`, 파생 **rawSn 172 단 1건** |
| 22 | **오배송(타 증강 보유 job_id)** | job 10 의 `otsd_job_id` 를 aug 30 소유값으로 주입 후 콜백 | 409(쓰기 이전) | **409** `이미 다른 증강 결과에 인계된 작업 ID 입니다.` + WARN `ownerDataAugSn=30` | job 10 `RECEIVED` · aug 39 `PENDING` (**500 아님, tx 오염 없음**) |
| 23 | **REJECTED 증강에 성공 결과 도착** | aug 37 반려 후 SUCCEEDED 콜백 | 200 `applied:false` + WARN | **200 `applied:false`** + WARN `success result discarded — aug already terminal(REJECTED) dataAugSn=37 …` | aug 37 `REJECTED` 유지, `otsd_job_id` 미적재 |
| 24 | ACK 미수신 job 에 임의 job_id | job 4(`otsd_job_id` null)에 `job_id=anything-goes` | — | **200 `applied:false`** — 오배송 검사가 **스킵**됨(`externalJobId != null` 가드) | 불변 → `E-ISSUE-24` |
| 25 | **만료 스윕(비종결 job)** | job 16 의 `mdfcn_dt` 를 10h 전으로 주입 | 조건부 UPDATE 클레임 → EXPIRED | 04:03:31 tick: `job expired (non-terminal reclaimed) augJobSn=16 dataAugSn=52 rollup=APPLIED`, `count=1 candidates=1` | job 16 `FAILED/EXPIRED`, aug 52 `REJECTED`+dead-letter |
| 26 | **고아 PENDING 스윕(job 0건)** | aug 39 의 job 행 삭제 + `reg_dt` 10h 전 주입 | REJECTED+dead-letter | 동 tick: `orphan pending augment reclaimed (job 0건 · 깨울 주체 없음) dataAugSn=39 result=APPLIED` | aug 39 `REJECTED`+dead-letter |
| 27 | 위탁 0건(비식별 경로 부재) | rawSn 20012(deid 경로 없음) 증강 요청 | 위탁 0 + 즉시 실패 롤업 | mock job 수 **20→20**(HTTP 0건), `위탁 거부 — 비식별 프레임 경로 부재 … missingCount=1` → `no job accepted — rolled up to REJECTED` | job 19 `FAILED/DEID_PATH_MISSING`, aug 56 `REJECTED`+dead-letter |

**정상 완주 기준선(TC-AUG-050)**: rawSn 146 RAIN 요청 → mock `job accepted … inputs=3` → webhook 10/50/90/100
→ `job succeeded outputCount=3` → **파생 rawSn 159**(`ORGNL_RAW_SN=146`, `RAW_FILE_PATH_NM=/app/storage/deidentified/videos/augment/146/159/RAIN.mp4`)
→ `ExtractA plan ready (external outputs)` → `ExtractB deid video copied` → `ingested frames=3 (external outputs)`
→ `ExtractC persisted frames=3 labels=13 metas=21 metaReviews=15`.

---

## ★ checksum 미검증 파급 실측 (G-ISSUE-44 의 E 클러스터 확증)

> G-part3 가 "BE 가 `results[].checksum` 을 선언만 하고 검증 0건"을 정적으로 확인했다. E-3 에서 **실제 파급**을 재현했다.

| 실험 | 투입 | 결과 |
|---|---|---|
| **A. 타 작업 산출물 치환(형식 불일치)** | job 6(aug 35, 부모 152, 1프레임)에 `output_file_path=/app/storage/raw/seed/146/deid/sample-cctv-1080p-mask.mp4`(다른 영상의 **MP4**), checksum **미첨부** | **200 `applied:true`** → job 6 SUCCEEDED → aug 35 ACCEPTED → 파생 rawSn 165 생성. Phase B 의 **해상도 실측 3중 검증에서 거부** → rawSn 165 `FAILED`, aug 35 dead-letter. **경로/내용 진정성이 아니라 "이미지로 읽히는가" 만이 최종 방어선**이다 |
| **B. 원본(비-비식별) 프레임 치환(형식 일치)** | job 7(aug 36, 부모 152)에 `output_file_path=/app/storage/raw/frames/raw/152/frame-0.jpg`(**마스킹 전 원본**), `checksum="0000…0000"`(명백한 위조값) | **200 `applied:true`** → 전 단계 통과 → **파생 rawSn 167 생성, `DATA_STTS_CD=COMPLETED`, `DE_IDNTF_YN='Y'`**. `LS_DATA_SRC(167).DE_IDNTF_SRC_FILE_PATH_NM = /app/storage/deidentified/frames/deid/167/frame-0.jpg` 의 실제 내용 md5 = **`fae1cc00a40111d8869d4fecc200dd5c` = rawSn 152 의 원본 프레임 md5**(비식별본은 `e0df599d…` 로 상이) → **`E-ISSUE-21`** |
| **C. checksum 대조 여부** | 위 B 의 위조 checksum | 어떤 로그·메트릭·상태 변화도 없음. `grep -n "checksum" GenAiCallbackService.java AugmentResultService.java AugmentFrameProducer.java` → **소비 지점 0건**(DTO 선언 `GenAiCallbackRequest.java:114-116` 뿐) → **`E-ISSUE-22`** |
| **D. 순서 의존 짝짓기의 실제 성립성** | `LS_DATA_AUG_JOB_FILE`(FILE_SEQ 오름차순) ↔ `results[]` 순서 대응 | 실측: job 5 의 3개 파일이 `001_frame-0/002_frame-1/003_frame-2` 순으로 되붙음. **방어는 "건수 일치" 하나뿐**(TC-AUG-071). 건수가 같고 순서만 뒤바뀐 페이로드는 **검출 수단이 전혀 없다** — 프레임 N 에 프레임 M 의 증강본이 붙어도 라벨 좌표는 그대로 복사되므로 학습데이터가 조용히 오손된다 |

> ⚠ 실험 B 의 파생 rawSn 167 은 **의도적으로 만든 오염 데이터**다. 후속 회차에서 마트뷰/export 검증에
> 쓰이지 않도록 주의(부모 152, `AUG_TYPE_CD=NIGHT`, `data_aug_sn=36`).

---

## E-3 결과표 (증강 결과 웹훅 — 29행 / 검증 24)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-AUG-050 | 전 job SUCCEEDED 롤업 → 신규 증강영상 생성 | PASS | [실동작] rawSn 146 RAIN 실왕복 완주 → 파생 **rawSn 159**(`ORGNL_RAW_SN=146`, 동기 커밋 시 `dataStts=PENDING` 로그, `RAW_FILE_PATH_NM=…/videos/augment/146/159/RAIN.mp4`), AFTER_COMMIT `AsyncAugmentFrameRunner` 기동 로그. [정적] `GenAiCallbackService.java:97-164`·`AugmentJobRollup.java:64-70`·`AugmentResultService.java:405-436` [테스트] `GenAiCallbackServiceTest:233`·`AugmentResultServiceTest:339` | 근거 1줄 드리프트(96→97) |
| TC-AUG-051 | 실패 롤업 REJECTED·영상 미생성 + dead-letter | PASS | [실동작] 시나리오 18 — job 14 FAILED → aug 50 `REJECTED`/`RTRY_NMTM=1`/`DEAD_LETTER_AT=04:01:42`, 신규 영상 0. [정적] `AugmentJobRollup.java:72-77`·`AugmentResultService.java:226-232,317-320` [테스트] `AugmentResultServiceTest:161` | — |
| TC-AUG-052 | 재전송 멱등(1차 앵커) non-PENDING skip | PASS | [실동작] 시나리오 11·23 — 200 `{applied:false}`, 중복 영상 0. [정적] `AugmentResultService.java:186-202` [테스트] `AugmentResultServiceTest:180,224` | `AugmentApplyResult.DUPLICATE` 실제 라인 23(카탈로그 22) |
| TC-AUG-053 | otsd_job_id 선점 — 재수신 200 / 타 증강 409 | PASS | [실동작] ① 시나리오 11 → 200 `applied:false` ② 시나리오 22 → **409**, job/aug 상태 불변(**500·25P02 미발생**). [정적] `AugmentResultService.java:204-207,267-302` [테스트] `AugmentResultServiceTest:256,282` | **1차 FAIL 해소 확증** |
| ~~TC-AUG-054~~ | ~~augType 불일치 차단~~ | — | 폐기(2026-07-30) | 집계 제외 |
| TC-AUG-055 | 미발급 request_id 401 / aug 행 부재 404 | PASS | [실동작] ① `e3-forged-0001` → **401** + `HmacWebhookFilter … downstream auth rejected status=401`(rate limit 집계 실측). [정적] `GenAiCallbackService.java:100-111` [테스트] `GenAiCallbackServiceTest:136` | ② "job 은 있으나 aug 행 없음=404" 는 `fk_ldaj_data_aug ON DELETE CASCADE` 때문에 **스키마상 도달 불가한 방어 코드** — 실동작 재현 대상 아님 |
| ~~TC-AUG-056~~ | ~~부모 'F' PII 게이트~~ | — | 폐기(★확정 정책) | 시나리오 20 이 정책 준수(파생 생성)를 **역으로 실증** |
| TC-AUG-057 | 부모 프레임 0건 = 실패 확정 | PASS | [정적] `AugmentResultService.java:389-392`(`parent has no frames` → FAIL) + `:213-219`(FAIL→REJECTED+dead-letter). 동일 `evaluateParentGate` 의 형제 분기(`parent has no deident artifact`)는 **시나리오 19 로 실동작 확인**. [테스트] `AugmentResultServiceTest:611` | 정상 흐름에서는 위탁측(`DEID_PATH_MISSING`)이 먼저 막아 콜백 도달 불가 = 다층 방어 |
| ~~TC-AUG-058~~ | ~~rawFilePathNm SSRF~~ | — | 폐기 | — |
| ~~TC-AUG-059~~ | ~~augTypeCd 화이트리스트~~ | — | 폐기 | — |
| TC-AUG-060 | status 화이트리스트 | PASS | [실동작] 시나리오 3 — `CANCELED`·`succeeded` 모두 400. [정적] `GenAiCallbackRequest.java:60-64` | — |
| TC-AUG-061 | request_id / job_id 패턴·길이 | PASS | [실동작] 시나리오 4·5 — 패턴 위반·129자·201자 전건 400. [정적] `GenAiCallbackRequest.java:44-58` | — |
| ~~TC-AUG-062~~ | ~~dataAugSn null·rawFilePathNm>1000~~ | — | 폐기 | — |
| TC-AUG-063 | 동시 콜백 직렬화(FOR UPDATE) | PASS | [실동작] 시나리오 21 — 4발 병렬 → `applied:true` 1 + `false` 3, 파생영상 정확히 1건(rawSn 172). [정적] `GenAiCallbackService.java:108-114`(job 갱신 **전** aug 잠금)·`AugmentResultService.java:182-184` [테스트] `GenAiCallbackRollupConcurrencyIT:119` | — |
| TC-AUG-064 | 부모 비식별 미완('N') = 실패 확정 | PASS | [실동작] 시나리오 19 — WARN `parent has no deident artifact`, aug 53 REJECTED+dead-letter, 영상 0건. [정적] `AugmentResultService.java:375-393`(`LsDataRaw.hasDeidentArtifact()` 단일 헬퍼) [테스트] `AugmentResultServiceTest:531` | — |
| TC-AUG-065 | IP allowlist 미설정 = 전면 차단 | PASS | [정적] `GenAiWebhookIpAllowlist.java:46-47,63-66` — `isNone()`(빈값·`none`) → `allowed=List.of()` → `isAllowed()` 무조건 false. 배선 `HmacWebhookFilter.java:487`. [실동작] 부팅 로그 `생성형 AI 콜백 IP allowlist 활성 count=1`(현 형상 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` = 전면 허용 **명시**) | 403 분기 실동작 재현은 설정 변경→**backend 재기동 필요(금지 규칙)** 이라 미수행 |
| TC-AUG-066 | 위탁↔수신 배선 짝 기동 가드 | PASS | [실동작] 부팅 로그 `[GenAi] 위탁(http) ↔ 콜백 IP allowlist 짝 확인 완료`(통과 분기 실증). [정적] `GenAiIntegrationWiringGuard.java:65-76`(`IllegalStateException` fail 분기) | fail 분기는 순수함수 `verify()` 라 단위 검증 대상 |
| TC-AUG-067 | job_id 오배송 차단 | PASS | [실동작] 시나리오 10 — **409** `job_id 가 일치하지 않습니다.`, 상태 미변경, WARN 에 expected/received 기록. [정적] `GenAiCallbackService.java:121-126` [테스트] `GenAiCallbackServiceTest:326` | ⚠ ACK 미수신 job 은 스킵 → `E-ISSUE-24` |
| TC-AUG-068 | RUNNING 진행 갱신 = 롤업 없음 | PASS | [실동작] 시나리오 13 — `JOB_STTS_CD=RUNNING`, 200 `applied:true`, 롤업 로그 0. 실왕복에서도 10/50/90 3회 관측. [정적] `GenAiCallbackService.java:136-142` | 진행률 **순서 검증 없음**(설계상 상태만 반영) |
| TC-AUG-069 | output_file_path 허용 밖 = 400·상태 미변경 | PASS | [실동작] 시나리오 15·16 — 400 `output_file_path 가 허용된 저장 경로가 아닙니다.`(경로 원문 미노출), job 상태 `RUNNING` 유지 = **재전송 여지 보존**, WARN 기록. [정적] `GenAiCallbackService.java:214-237`·`VideoArtifactRootResolver.java:373-374` [테스트] `GenAiCallbackServiceTest:286` | 메트릭 `augment.callback.rejected{reason=output_path}` 는 코드 확인(엔드포인트 미노출로 값 미확인) |
| TC-AUG-070 | 읽기 루트 기본 빈값 = 쓰기축과 동일 fail-closed | **PARTIAL** | [실동작] 읽기 루트 = **쓰기 allowlist ∪ external-read-roots** 동작 확인 — `/app/storage/raw/…`(쓰기 allowlist) 경로가 **200 수용**됨. [정적] `VideoArtifactRootResolver.java:159-177` | 합집합 동작 자체는 기대와 일치하나, **쓰기 allowlist 에 원본(비-비식별) 저장소가 들어 있어** "쓰기 base 를 넓히지 않고 PII 격리 축을 지킨다"는 설계 목표가 **읽기 축에서 무너진다** → `E-ISSUE-21` |
| TC-AUG-071 | 위탁 건수 ↔ 수신 건수 불일치 = job FAILED | PASS | [실동작] 시나리오 17 — job 9 `FAILED/RESULT_COUNT_MISMATCH` "위탁 3건 대비 수신 1건" → aug 38 REJECTED+dead-letter, 영상 0건. [정적] `GenAiCallbackService.java:175-193`·`LsDataAugJob.java:74` [테스트] `GenAiCallbackServiceTest:200` | 순서 뒤바뀜은 이 방어로 검출 불가 → `E-ISSUE-22` |
| TC-AUG-072 | SUCCEEDED 인데 results 없음 = 400 | PASS | [실동작] 시나리오 14 — 누락·빈배열 모두 400 `SUCCEEDED 콜백에는 results 가 필요합니다.` + WARN `succeeded without results`. [정적] `GenAiCallbackService.java:214-221` [테스트] `GenAiCallbackServiceTest:311` | — |
| TC-AUG-073 | 롤업 보류 = applied:false | PASS | [실동작] 위탁 시퀀스 종료 롤업에서 `rollup deferred dataAugSn=30 pendingJobSeqs=[1]` 관측(DEFERRED 반환 실증). [정적] `GenAiCallbackService.java:155-163`(롤업 결과 그대로 회신) + `AugmentApplyResult.java:26,29-30`(`applied()` = APPLIED 만) [테스트] `GenAiCallbackServiceTest:346,364` | 웹훅 응답 `applied:false` 의 2청크 실동작 재현은 **불가** — 최대 프레임 12장 < 청크 상한 100, `max-input-files` 변경은 재기동 필요 |
| TC-AUG-074 | 비종결 job 만료 스윕 회수 | PASS | [실동작] 시나리오 25 — 04:03:31 tick 에서 `job expired (non-terminal reclaimed) augJobSn=16 … rollup=APPLIED`, `count=1 candidates=1 idleTimeoutMinutes=360`. job 16 `FAILED/EXPIRED` + 사유문 적재. [정적] `AugmentJobExpirySweeper.java:206-232`·`AugmentJobExpiryTxService.java:63-88`(`claimExpired` 조건부 UPDATE) | 부팅 로그 `sweep scheduled intervalMs=900000 idleTimeoutMinutes=360` |
| TC-AUG-075 | job 0건 고아 PENDING 증강 회수 | PASS | [실동작] 시나리오 26(내 주입) + **03:48:31 tick 의 타 에이전트 데이터 자연 회수 3건**(`dataAugSn=12,14,15`). 신고 구간 제외 술어 없음도 코드 확인. [정적] `AugmentJobExpirySweeper.java:174-198`·`AugmentJobExpiryTxService.java:106-122` | — |
| TC-AUG-076 | 필수 필드 누락 400 | PASS | [실동작] 시나리오 6·7 — blank 3종 400, results 101건 400. [정적] `GenAiCallbackRequest.java:44-83` | — |
| TC-AUG-077 | 미지 필드 무시 | PASS | [실동작] 시나리오 2 — 미지 필드 2개 포함 페이로드가 **역직렬화·검증 통과**(400 아님) 후 발급 게이트 401 도달. [정적] `GenAiCallbackRequest.java:41,95` | — |
| TC-AUG-078 | 강등된 PENDING 의 성공 콜백 폐기 관측 | PASS | [실동작] 시나리오 23 — 반려 후 성공 콜백 → 200 `applied:false` + **WARN `success result discarded — aug already terminal(REJECTED) dataAugSn=37 otsdJobId=… (요청 중 강등/반려 추정 — 필요 시 재요청)`** 문구 그대로. [정적] `AugmentResultService.java:188-196` | 계약 문구 완전 일치 |

---

## E-3B 결과표 (증강 외부 위탁 — 22행)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|---|---|
| TC-AUG-100 | 100장 청크 분할 위탁 | PASS | [정적] `AugmentJobSubmitService.java:141`(`CONTRACT_MAX_INPUT_FILES=100`)·`:180-185`(`clampChunkSize` — 설정이 100 초과면 100 으로 clamp)·`:504-510`(`partition`)·`:516-518`(`chunkRequestId = {augIdmpKey}-{jobSeq}`). 설정 `application.yml:276 max-input-files: ${AUGMENT_MAX_INPUT_FILES:100}` [테스트] `AugmentJobSubmitServiceTest:104`(250장→100/100/50)·`:269`(clamp) [실동작] 청크키 형식 `AUG-{uuid}-1` 실측(job 1~19 전건) | **근거 드리프트** — 카탈로그 `:112,143-148,385-399` ↔ 실제 `:141,180-185,504-518`. 실동작 분할 재현은 최대 12프레임이라 불가 |
| TC-AUG-101 | job_id 는 외부가 발급 | **PARTIAL** | [실동작] mock 202 의 `job_id` 가 `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 적재됨(job 1·5·6~18 전건, 예: `bb65f8da…`). [정적] 3축 검증 `HttpExternalAugmentClient.java:164-181`(request_id echo / `status=RECEIVED` / job_id non-blank → `EXTERNAL_API_ERROR`) [테스트] `AugmentJobSubmitServiceTest:191` | 카탈로그가 지목한 **`markAccepted(외부 job_id)` 는 프로덕션 호출자 0건(dead code)** — 실제 경로는 `AugmentSubmitOutcomeRecorder.onAccepted → AugmentJobRecorder.markSubmitAccepted`(조건부 원자 UPDATE) → `E-ISSUE-23` |
| TC-AUG-102 | Idempotency-Key 헤더 | PASS | [실동작] mock 에 `Idempotency-Key: AUG-699af0c0-…-1` + **다른 request_id** 로 직접 제출 → mock 이 **기존 job `d6ead814…` 를 그대로 반환**(202, `request_id` 도 원본값) = BE 가 그 헤더를 실제로 보냈음을 역증명. [정적] `HttpExternalAugmentClient.java:58,114` | 근거 드리프트(`:57-58,99` → `:58,114`) |
| TC-AUG-103 | 4xx = 비재시도 | PASS | [정적] `HttpExternalAugmentClient.java:118`(`onStatus(is4xxClientError)`)·`:151-157`(`releaseBody()` 로 본문 소비·해제 후 `NonRetryableExternalException`, 상태코드만 로그 — 본문 원문 미노출 CWE-209). 연산자 순서상 Retry/CircuitBreaker 에서 제외됨 [테스트] `HttpExternalAugmentClientTest` | 실동작 4xx 강제 불가 — mock 이 400 을 내는 조건(경로 밖 입력·중복 sequence 등)을 BE 가 만들어낼 경로가 없음. 근거 드리프트(`:102-103,134-140`) |
| TC-AUG-104 | 전량 선기록 후 위탁 | PASS | [정적] `AugmentJobSubmitService.java:236-240`(`issueAllChunks` 실패 시 `SubmitOutcome.of(0)` 즉시 반환)·`:375-394`(실패 시 이미 선기록된 앞 청크를 `ERR_ISSUE_RECORD_FAILED` 로 종결) [테스트] `AugmentJobSubmitServiceTest:284,297,323,346` | 근거 드리프트(`:198-260` → `:236-240,375-394`) |
| TC-AUG-105 | 비식별 경로 부재 = 위탁 거부 | PASS | [실동작] 시나리오 27 — rawSn 20012 요청 시 **mock job 수 20→20(HTTP 0건)**, job 19 `FAILED/DEID_PATH_MISSING` "…missingCount=1", 원본 경로 폴백 0. 타 에이전트 생성 job 4(aug 28)도 동일 결과. [정적] `AugmentJobSubmitService.java:219-230,471-494` [테스트] `AugmentJobSubmitServiceTest:203,218,239` | 근거 드리프트(`:182-193,352-375`) |
| TC-AUG-106 | 위탁 전 신고 = 거부(보류 아님) | PASS | [정적] `AugmentJobSubmitService.java:208-217` — 프레임 경로 조회 **이전** `DeidentReportGate` 단일 원천 호출 → `recordRejected(ERR_DEID_REPORT_OPEN)` + `SubmitOutcome.of(0)`; `AugmentRequestBridge.java:124`(`requiresFailureRollup`) → 즉시 실패 롤업. `WITHHELD_*`·`onDeidentReportResolved` grep 0건(폐기 확인) [테스트] `AugmentJobSubmitServiceTest:372,391` | 실동작 단독 재현 불가 — 동일 `'F'` 신호를 요청 입구(`AugmentRequestService` 412)가 먼저 잡아 전송 게이트까지 도달시킬 방법이 없음(다층 방어) |
| TC-AUG-107 | 위탁 도중 신고 관측 = 남은 청크 중단 | PASS | [정적] `AugmentJobSubmitService.java:314-322`(`Mono.defer` 안에서 **청크마다 무잠금 재판정**, `index>0`)·`:406-415`(`abortRemainingChunks` → `ERR_DEIDENT_REPORT` terminal 종결)·`:268-286`(`concatMap` 직렬화 — 병렬 발사 금지) [테스트] `AugmentJobSubmitServiceTest:407` | 2청크 이상 구성 불가로 실동작 미재현. 근거 드리프트(`:207-219,262-281`) |
| TC-AUG-108 | 청크 위탁 실패 건별 격리 | PASS | [정적] `AugmentJobSubmitService.java:339-354`(`onErrorResume` → `onSubmitFailed` 기록 후 `Mono.empty()` = 다음 청크 계속) [테스트] `AugmentJobSubmitServiceTest:169` | 근거 드리프트(`:324-344`) |
| TC-AUG-109 | 위탁 0건 = 즉시 실패 롤업 | PASS | [실동작] 시나리오 27 — `[Augment] no job accepted — rolled up to REJECTED originAugSn=56` → aug 56 REJECTED + `RTRY_NMTM=1` + dead-letter. [정적] `AugmentJobSubmitService.java:444-453`(`requiresFailureRollup`)·`AugmentRequestBridge.java:124,130-149`(미종결 job 이 있으면 롤업 금지) [테스트] `AugmentRequestBridgeTest`·`AugmentJobSubmitServiceTest:437` | 근거 드리프트(`:304-314` → `:444-453`) |
| TC-AUG-110 | ★커넥션 풀 데드락 회귀 가드 | PASS | [정적] `AugmentJobSubmitService` 클래스·`submit()` 에 **트랜잭션 애너테이션 부재** 확인(`@Transactional` grep 0건), 클래스 javadoc `:82-134` 가 `readOnly`·`NOT_SUPPORTED` 양쪽 실측 근거 보존 [테스트] `AugmentRequestServiceTest:361-382` — `isSynchronizationActive()` + `getResource(controlEmf)` **두 축** 관측(카탈로그 지적대로 `isActualTransactionActive()` 미사용) [실동작] 04:01:05~06 에 **위탁 4건 연속**, 03:57:20 에 **7건 연속** — Hikari 타임아웃·`CannotCreateTransactionException` 0건 | 근거 드리프트(`:53-106,160-166` → `:82-134,207`) |
| TC-AUG-111 | evnt_type 폴백 | PASS | [정적] `AugmentJobSubmitService.java:144`(`EVNT_TYPE_FALLBACK="ETC"`)·`:497-502`(`resolveEventType` — null/blank 시 폴백) [테스트] `AugmentJobSubmitServiceTest:255` | 실동작 재현 불가 — `EVNT_TYPE_CD` 가 null 인 영상(150)은 프레임 0건이라 위탁 자체가 불가. 근거 드리프트(`:115,377-383`) |
| TC-AUG-112 | 위탁 로그에 절대경로 미출력 | PASS | [실동작] 위탁 로그 전건이 `originAugSn/augType/jobSeq/inputCount` 수준(`genai submit originAugSn=30 augType=RAIN jobSeq=1/1 inputCount=3`) — 파일 절대경로 0건. 개행 포함 외부 메시지가 `vendor side failure_line2` 로 sanitize 됨(시나리오 18). [정적] `HttpExternalAugmentClient.java:183-186`·`AugmentJobSubmitService.java:521-524`·`GenAiCallbackService.java:240-249` | 근거 드리프트(`:45,166-169`·`:401-405`) |
| TC-AUG-113 | `mode=noop` 시 위탁 미수행 | PASS | [정적] `HttpExternalAugmentClient.java:50-51`(`havingValue="http", matchIfMissing=true`) ↔ `NoopExternalAugmentClient.java:27`(`havingValue="noop"`) 상호배타. 만료 스윕은 자기 토글만 봄(`AugmentJobExpirySweeper.java:47-52,86`) [실동작] 현 형상은 `AUGMENT_EXTERNAL_MODE=http` + 부팅 로그 `active ExternalAugmentClient=HttpExternalAugmentClient` [테스트] `ExternalAugmentClientBeanConditionTest`·`NoopExternalAugmentClientTest` | noop 전환은 재기동 필요로 미검증 |
| TC-AUG-114 | 외부 산출 프레임 실반입 3중 검증 | PASS | [실동작] 실험 A — 다른 영상의 **MP4** 를 산출물로 위장 제출 → Phase B **거부** → rawSn 165 `FAILED`, `augment frame re-extraction failed … cause=CustomException`. [정적] `AugmentFrameProducer.java:123-199`(`verifyExternalReadablePath` 171 / `isRegularFile` 134 / 해상도 대조 154-158 → all-or-nothing) [테스트] `AugmentFrameProducerTest` | ⚠ **해상도가 같은 파일은 전부 통과** — 실험 B 로 원본 PII 프레임 반입 성립(`E-ISSUE-21`) |
| TC-AUG-115 | 부분 쓰기 방지(.part → atomic move) | PASS | [정적] `AugmentFrameProducer.java:55`(`PART_SUFFIX=".part"`)·`:224-238`(`copyAtomically` — `Files.newInputStream(src, NOFOLLOW_LINKS)` 로 열고 `Files.move(..., ATOMIC_MOVE)`) [실동작] 산출 디렉터리 `frames/deid/159|167|172|175` 에 `.part` 잔존 0건 | 중단 시나리오 자체는 미주입 |
| TC-AUG-116 | 파생 비디오 = 부모 비식별본 실제 복사 | PASS | [실동작] `md5 /app/storage/raw/seed/146/deid/sample-cctv-1080p-mask.mp4` = `md5 /app/storage/deidentified/videos/augment/146/159/RAIN.mp4` = **`1c2d2b2c39a5bae1d71ace03d4a83c69`**(34,654,319B). 소스 경로는 `LS_DEIDENT_PROC_LOG(146).DE_IDNTF_FILE_PATH_NM` 값 그대로 — **조합·추측 아님**(mock 형상인데 파일명이 `deidentified.mp4` 가 아니라 `…-mask.mp4` 임에도 정확히 찾음). [정적] `AugmentFrameProducer.java:107-119`·`AugmentExtractSnapshot.java:164-165,245-255` | 원본 폴백 0 |
| TC-AUG-117 | 파생 프레임은 비식별 컬럼에만 적재 | PASS | [실동작] `LS_DATA_SRC(159)` 3행 — `SRC_FILE_PATH_NM` **전부 null**, `DE_IDNTF_SRC_FILE_PATH_NM=/app/storage/deidentified/frames/deid/159/frame-{0,1,2}.jpg`(두 컬럼 동일값 0건). rawSn 167·172·175 도 동일. [정적] `AugmentExtractPersist.java:101-115` | — |
| TC-AUG-118 | 증강 라벨 복사는 좌표 그대로 | PASS | [실동작] `LS_DATA_AUG_LBL_MAP(data_aug_sn=30)` 13행 전건 `COORD_RECALC_YN='N'`, `SCALE_X/SCALE_Y` null, `ORGNL_DATA_LBL_SN`↔`DATA_LBL_SN` 매핑 존재. 로그 `ExtractC persisted … labels=13`. [정적] `AugmentExtractPersist.java:117-135,227-236` | — |
| TC-AUG-119 | async 추출 실패 = RAW FAILED + aug dead-letter | PASS | [실동작] 실험 A — `augment frame re-extraction failed rawSn=165 dataAugSn=35 cause=CustomException` → `ExtractC aug marked dead-letter after async extraction failure dataAugSn=35 status=ACCEPTED` → DB: rawSn 165 `DATA_STTS_CD=FAILED`·`DE_IDNTF_YN='N'`, aug 35 `RTRY_NMTM=1`·`DEAD_LETTER_AT` 기록, **`AUG_PROC_STTS_CD` 는 ACCEPTED 로 미변경**(멱등 앵커 무충돌 — 계약대로). [정적] `AsyncAugmentFrameRunner.java:133-159`·`AugmentExtractPersist.java:186-203` | 계약 3요소 모두 실동작 확인 |
| TC-AUG-120 | ffprobe 는 확정 성공 이후에만 | PASS | [정적] `AsyncAugmentFrameRunner.java:106-121` — 확정 블록 **밖** 별도 catch 에서 `AsyncVideoMetaRunner` 기동; `AugmentResultService.java:444-448` 주석이 콜백 시점 병렬 기동 금지를 명시하고 실제 트리거는 프레임 러너 1곳뿐 [실동작] 로그 순서 `ExtractC persisted` → 이후 메타 러너 [테스트] `AugmentResultServiceTest:579` | — |
| TC-AUG-121 | Phase C SKIPPED = cleanup 금지 | PASS | [정적] `AsyncAugmentFrameRunner.java:92-97` — `Result.SKIPPED` 시 cleanup·FAILED 전이 모두 skip(주석: 파일이 승자와 동일 경로) [테스트] `AsyncAugmentFrameRunnerTest` | 중복 트리거 레이스 실동작 재현 불가 |

---

## 근거 드리프트 / self-fill 점검

### 근거 file:line 드리프트 — **12건 (전부 E-3B)**

Phase C-3 논블로킹 제출 전환으로 `AugmentJobSubmitService`(555줄)·`HttpExternalAugmentClient`(188줄) 가
전면 재작성되면서 카탈로그 라인 참조가 무효화됐다. **E-3(웹훅 계열) 참조는 실질 정확**(1~2줄 오차만).

| TC | 카탈로그 근거 | 실제 위치 |
|---|---|---|
| TC-AUG-100 | `AugmentJobSubmitService.java:112,143-148,385-399` | `:141`(상한) · `:180-185`(clamp) · `:504-510`(partition) · `:516-518`(chunkRequestId) |
| TC-AUG-101 | `HttpExternalAugmentClient.java:90-164` | `:104-128`(requestAugment) · `:164-181`(validate 3축) |
| TC-AUG-102 | `HttpExternalAugmentClient.java:57-58,99` | `:58`(헤더 상수) · `:114`(헤더 세팅) |
| TC-AUG-103 | `HttpExternalAugmentClient.java:102-103,134-140` | `:118`(onStatus) · `:151-157`(toNonRetryable4xx) |
| TC-AUG-104 | `AugmentJobSubmitService.java:198-260` | `:236-240` · `:375-394`(issueAllChunks) |
| TC-AUG-105 | `AugmentJobSubmitService.java:182-193,352-375` | `:219-230` · `:471-494`(resolveDeidInputFiles) |
| TC-AUG-106 | `AugmentJobSubmitService.java:170-180` / `AugmentRequestBridge.java:116-118` | `:208-217` / `:124` |
| TC-AUG-107 | `AugmentJobSubmitService.java:207-219,262-281` | `:314-322`(재판정) · `:406-415`(abortRemaining) |
| TC-AUG-108 | `AugmentJobSubmitService.java:324-344` | `:339-354` |
| TC-AUG-109 | `AugmentJobSubmitService.java:304-314` / `AugmentRequestBridge.java:132-147` | `:444-453` / `:130-149` |
| TC-AUG-110 | `AugmentJobSubmitService.java:53-106,160-166` | `:82-134`(클래스 javadoc) · `:207`(submit) |
| TC-AUG-111 | `AugmentJobSubmitService.java:115,377-383` | `:144` · `:497-502` |
| TC-AUG-112 | `HttpExternalAugmentClient.java:45,166-169` / `AugmentJobSubmitService.java:401-405` | `:183-186` / `:521-524` |

경미(1~2줄): TC-AUG-050(`96`→`97`) · TC-AUG-052(`AugmentApplyResult:22`→`23`) · TC-AUG-065(`:34-73`→`:34-77`).

### self-fill 점검 — **위반 0건**

| 점검 항목 | 결과 | 근거 |
|---|---|---|
| 외부 응답 없이 산출물을 자체 생성하는가 | **없음** | 파생 프레임 md5 `064376b24753aaf61f950a89f66b0e96` = mock 산출 파일 `/app/genai-out/genai/bb65f8da…/001_frame-0_genai.jpg` 와 **바이트 동일**. 로그도 `ingested rawSn=159 frames=3 (external outputs)` |
| 파생 비디오를 임의 생성/재인코딩하는가 | **없음** | 부모 비식별 영상과 md5 동일(단순 복사) |
| 외부 실패 시 조용히 성공 처리(fail-open) 하는가 | **없음** | `results` 없는 SUCCEEDED → 400 / 건수 불일치 → job FAILED / 부모 미비식별 → REJECTED / 위탁 0건 → 즉시 REJECTED — 전부 실동작 확인 |
| mock 을 멈췄을 때 스스로 성공 처리하는가 | **없음** | mock job 을 CANCELED 로 동결한 job 6~18 은 **콜백을 내가 보내기 전까지 `RECEIVED` 로 그대로 정체**(자체 진행 0). 만료 스윕만이 회수 |
| 벤더 산출물의 **내용**을 검증하는가 | **하지 않음** | `checksum` 소비 지점 0건 → `E-ISSUE-22`(G-ISSUE-44 파급) |

### 검증 중 생성/변경된 데이터 (후속 회차 참고)

- 신규 증강 `LS_DATA_AUG`: 30·35·36·37·38·39·40·41·49·50·51·52·53·55·56
- 신규 `LS_DATA_AUG_JOB`: 5~19 (job 10 은 TC-AUG-075 검증을 위해 **삭제**)
- 신규 파생 `LS_DATA_RAW`: **159**(146/RAIN 정상) · **162**(156/WINTER) · **163**(153/RAIN) · **165**(152/WINTER, 의도적 FAILED)
  · **167**(152/NIGHT, ⚠ **원본 PII 프레임 오염 — 실험 B 산물**) · **172**(156/NIGHT) · **175**(152/RAIN, 신고 구간 생성 확인용)
- 일시 주입 후 원복: `ls_data_raw(152).de_ident_yn` `'N'`/`'F'` → **`'Y'` 복구 완료**
- 잔존 비종결: job 15(aug 51, `RECEIVED`) — 6시간 후 만료 스윕이 회수 예정

---

## 이슈 상세

### [E-ISSUE-21] TC-AUG-070 / 연관 TC-AUG-069·TC-AUG-114 — 벤더 산출 경로의 읽기 허용 루트에 **원본(비-비식별) 저장소**가 포함돼, 콜백 1건으로 원본 PII 프레임이 파생영상의 "비식별 프레임"으로 반입된다

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: 벤더가 콜백으로 건네는 `results[].output_file_path` 는 **벤더 자신의 산출 트리**
  (`authoring.storage.external-read-roots`, 현 형상 `/app/genai-out`)만 가리킬 수 있어야 한다.
  `VideoArtifactRootResolver` 클래스 주석이 스스로 이 목적을 "쓰기 allowlist 에 추가하면 PII 격리 축
  (원본/비식별 산출 위치 통제)이 흐려진다" 로 명시하고 있다.
- **현재 동작(이슈 내용)**: 읽기 루트가 **쓰기 allowlist ∪ external-read-roots** 로 계산되는데,
  쓰기 allowlist 에 **원본 저장소**가 그대로 들어 있다.

  `common/storage/VideoArtifactRootResolver.java:159-161`
  ```java
  private static List<Path> buildReadableRoots(List<Path> writeRoots, String configured) {
      Set<Path> roots = new LinkedHashSet<>(writeRoots);   // ← 원본 저장소 포함
  ```
  런타임 형상: `STORAGE_RAW_MOUNT_ROOTS=/app/storage/raw,/app/storage/deidentified`,
  `STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out` → 읽기 루트에 **`/app/storage/raw`**(마스킹 전 원본 프레임·영상)가 포함된다.

  `AugmentFrameProducer` 의 3중 검증(허용 루트 / 정규 파일·size>0 / **부모 비식별 프레임과 해상도 동일**)은
  원본 프레임을 걸러내지 못한다 — 원본과 비식별본은 **같은 영상에서 뽑은 같은 해상도**이기 때문이다.

  **실동작 재현 결과(실험 B)**: job 7(aug 36, 부모 rawSn 152)에
  `output_file_path=/app/storage/raw/frames/raw/152/frame-0.jpg`(마스킹 전 원본) + 위조 checksum 을 제출 →
  **200 `applied:true`** → 파생 **rawSn 167** 생성(`DATA_STTS_CD=COMPLETED`, `DE_IDNTF_YN='Y'`)
  → `LS_DATA_SRC(167).DE_IDNTF_SRC_FILE_PATH_NM` 파일의 실제 내용이 **원본 프레임과 md5 동일**.

  | 파일 | md5 |
  |---|---|
  | rawSn 152 **원본** `/app/storage/raw/frames/raw/152/frame-0.jpg` | `fae1cc00a40111d8869d4fecc200dd5c` |
  | rawSn 152 **비식별본** `/app/storage/deidentified/frames/deid/152/frame-0.jpg` | `e0df599df62cba67952a6530fc70423c` |
  | **파생 rawSn 167 의 "비식별 프레임"** `/app/storage/deidentified/frames/deid/167/frame-0.jpg` | **`fae1cc00a40111d8869d4fecc200dd5c`** ← 원본과 동일 |

- **재현/확인 경로**:
  ```bash
  # 1) 증강 요청 → mock job 을 즉시 취소해 우리 job 을 RECEIVED 로 동결
  curl -s -X POST http://localhost:18081/api/v1/augments/request -H "Authorization: Bearer $TOK" \
       -H 'Content-Type: application/json' -d '{"videoIds":[152],"types":["NIGHT"]}'
  curl -s -X POST http://localhost:9400/api/genai/jobs/{mockJobId}/cancel \
       -H 'Content-Type: application/json' -d '{"requested_by":"probe"}'
  # 2) 원본 프레임 경로를 산출물로 위장 제출
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' -d '{
    "request_id":"{issuedChunkKey}","job_id":"{mockJobId}","status":"SUCCEEDED",
    "results":[{"generated_data_id":"forged-1","media_type":"IMAGE",
                "output_file_path":"/app/storage/raw/frames/raw/152/frame-0.jpg",
                "checksum":"0000000000000000000000000000000000000000000000000000000000000000"}]}'
  # 3) 결과 확인
  docker exec klid-backend md5sum /app/storage/raw/frames/raw/152/frame-0.jpg \
       /app/storage/deidentified/frames/deid/167/frame-0.jpg     # → 동일 해시
  ```
- **영향**: **CWE-345**(Insufficient Verification of Data Authenticity) + **CWE-359**(Privacy Violation)
  + **CWE-501**(Trust Boundary Violation). 마스킹 전 PII 프레임이 `DE_IDNTF_YN='Y'` 파생영상의 비식별
  산출물로 확정되어 ①라벨링 캔버스 서빙(`/v1/frames/{srcSn}/deid-image`) ②검수 ③데이터셋 export
  ④데이터마트 뷰(`V_COMPLETED_FRAME.DEIDENTIFIED_PATH`) 로 전파된다. 파생영상은 **비식별 신고 체계
  바깥**(★확정 정책)이라 이 오염을 신고로 되돌릴 경로도 없다.
  공격자 = 벤더(또는 벤더 침해자). 발급 `request_id` 를 아는 주체가 정확히 벤더이므로 발급 게이트(401)는
  이 위협을 막지 못하며, IP allowlist 도 벤더 대역을 허용해야 하므로 동일하다.
  현 dev 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` 이라 **네트워크 도달 가능한 누구나** 해당한다.
- **수정 방향(제안)**: ①`verifyExternalReadablePath` 의 기준을 `external-read-roots` **단독**으로 좁힌다
  (쓰기 allowlist 와 합집합하지 않는다 — 벤더 산출물이 우리 저장소 안에 있을 이유가 없다).
  미설정 시 전건 400 은 fail-closed 로 오히려 올바른 동작이다. ②그래도 합집합이 필요하다면 최소한
  **원본 저장소(`raw-path`)를 읽기 루트에서 제외**한다. ③`Phase B` 반입 검증에 "산출 경로가 우리 저장소
  서브트리가 아닐 것" 불변식을 추가한다(`StorageSubtreePolicy` 재사용).
  ⚠ **구현하지 않는다** — 읽기 축 축소는 KPST/실벤더 공유마운트 형상과 함께 결정해야 한다.

---

### [E-ISSUE-22] TC-AUG-069·TC-AUG-071 — 벤더 산출물 `checksum` 미검증이 E-3 반입 경로에서 실제 파급을 낳는다 (G-ISSUE-44 의 E 클러스터 확증)

- **심각도**: **MEDIUM**
- **기대 동작(기대효과)**: 벤더가 `results[].checksum`(SHA-256)을 주는 이유는 **그 파일을 파생 프레임으로
  반입하기 전에 무결성을 확인**하라는 것이다. 반입 경로가 공유 NAS 라 전송 중단·부분 기록·교체가 실재한다.
- **현재 동작(이슈 내용)**: `GenAiCallbackRequest.ResultItem.checksum`(`webhook/dto/GenAiCallbackRequest.java:114-116`)
  은 **선언만** 되어 있고 소비 지점이 0건이다.

  `webhook/service/GenAiCallbackService.java:222-235`
  ```java
  List<String> paths = new ArrayList<>(results.size());
  for (GenAiCallbackRequest.ResultItem item : results) {
      try { artifactRootResolver.verifyExternalReadablePath(item.outputFilePath()); }
      ...
      paths.add(item.outputFilePath());     // checksum 미사용
  }
  ```
  검증: `grep -n "checksum\|sha256\|mediaMetadata" GenAiCallbackService.java AugmentResultService.java AugmentFrameProducer.java` → **0건**.

  **실동작**: 실험 B 에서 `checksum:"0000…0000"`(64자리 0)을 실었으나 어떤 경고·거부·메트릭도 발생하지 않고
  200 으로 반입 완료. 즉 **checksum 은 존재 여부조차 관측되지 않는다**.

  더불어 짝짓기가 **순서 의존**이다 — `GenAiCallbackService.applySucceeded(:186-189)` 가
  `LS_DATA_AUG_JOB_FILE`(FILE_SEQ 오름차순)과 `results[]` 를 인덱스로 대응시키고, 유일한 방어가
  **건수 일치**(TC-AUG-071)다. 우리가 `input_files[].checksum`·`source_file_id` 를 **송신하지 않으므로**
  (G-part3 3자 대조표 A) 결과에 되짚을 식별자도 없다. 건수가 같고 순서만 어긋난 페이로드는
  **검출 수단이 전혀 없으며**, 라벨 좌표는 그대로 복사되므로 프레임 N 에 프레임 M 의 증강본이 붙어도
  파이프라인 어디에서도 드러나지 않는다.
- **재현/확인 경로**: E-ISSUE-21 의 재현 스크립트(2단계에서 `checksum` 값을 임의로 넣어도 결과 동일).
  ```bash
  cd backend && grep -rn "checksum" src/main/java/kr/co/cudo/authoring/webhook/ | grep -v "/dto/"   # → 0건
  ```
- **영향**: CWE-345. 손상·부분 기록·교체된 산출물이 학습데이터로 확정된다. E-ISSUE-21 과 결합하면
  "경로도 신뢰, 내용도 미검증" 이라 반입 경로 전체에 진정성 방어가 없다.
- **수정 방향(제안)**: ①`applySucceeded` 의 경로 되붙이기 직전에 `checksum` 이 있으면 파일 해시와 대조하고
  불일치 시 `ERR_RESULT_CHECKSUM_MISMATCH` 로 job FAILED(기존 `ERR_RESULT_COUNT_MISMATCH` 와 동일 fail-closed 규약).
  ②`media_metadata.size_bytes` 를 DTO 에 선언해 실파일과 대조. ③중기적으로 `input_files[].source_file_id` 를
  송신하고 결과의 대응 필드로 짝지어 순서 의존을 제거. ⚠ **구현하지 않는다**(계약 변경 = 벤더 협의 대상).

---

### [E-ISSUE-23] TC-AUG-101 — `AugmentJobRecorder.markAccepted()` 프로덕션 호출자 0건 (구 E-ISSUE-32 와 동형의 dead code)

- **심각도**: **LOW**
- **기대 동작(기대효과)**: 카탈로그 TC-AUG-101 의 기대결과는 "응답 검증 3축 통과 시에만
  `markAccepted(외부 job_id)`" 다. 즉 그 메서드가 ACK 기록의 진입점이어야 한다.
- **현재 동작(이슈 내용)**: Phase C-3 논블로킹 전환에서 ACK 기록이
  `AugmentSubmitOutcomeRecorder.onAccepted(:45-49) → AugmentJobRecorder.markSubmitAccepted(:73-76)`
  (조건부 원자 UPDATE `claimSubmitAck`, 술어 `JOB_STTS_CD IN ('RECEIVED','RUNNING')`)로 이관됐는데
  **구 메서드가 그대로 남았다.**

  `augment/service/AugmentJobRecorder.java:59-62`
  ```java
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
  public void markAccepted(Long augJobSn, String externalJobId) {
      jobRepository.findById(augJobSn).ifPresent(job -> job.markAccepted(externalJobId));
  }
  ```
  호출자 grep 결과 — `src/main/java` **0건**, `src/test/java` 5건(전부 테스트 픽스처가 엔티티 메서드를 직접 호출).
  이 메서드는 **무조건 덮어쓰기**라 콜백이 ACK 보다 먼저 도착한 경우 SUCCEEDED job 을 RECEIVED 로
  강등시킨다 — `markSubmitAccepted` 의 javadoc 이 정확히 그 위험 때문에 조건부 UPDATE 를 도입했다고 적고 있다.
  즉 **되살리면 안 되는 구현이 살아 있는 상태**이며, 1차 E-ISSUE-32(`markDeadLetter` 호출자 0건)와 같은 패턴이다.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "markAccepted(" src/main/java   # → 정의 2곳뿐, 호출 0건
  ```
- **영향**: 기능 결함은 없다(현재 아무도 부르지 않으므로). 다만 ①카탈로그·리뷰어가 죽은 경로를 근거로
  삼게 되고(TC-AUG-101 기대결과가 실제로 이 메서드를 지목한다) ②향후 누군가 "간단한 쪽"을 호출해
  ACK↔콜백 레이스 강등을 재도입할 여지가 남는다.
- **수정 방향(제안)**: `AugmentJobRecorder.markAccepted` 를 삭제하고 테스트 픽스처는
  `LsDataAugJob.markAccepted`(엔티티 메서드)를 직접 쓰게 둔다. 카탈로그 TC-AUG-101 근거를
  `HttpExternalAugmentClient.java:164-181` + `AugmentJobRecorder.java:73-76` 으로 정정한다.
  ⚠ **구현하지 않는다**.

---

### [E-ISSUE-24] TC-AUG-067 — ACK 미수신 job 은 `job_id` 오배송 검사가 스킵되고 **첫 콜백이 실은 임의 job_id 가 그대로 앵커로 적재**된다

- **심각도**: **LOW**
- **기대 동작(기대효과)**: TC-AUG-067 은 "202 로 받아 둔 `externalJobId` 와 콜백 `job_id` 가 다르면 409" 다.
  오배송 방어의 근거는 "외부가 발급한 job_id 만 신뢰한다" 이다.
- **현재 동작(이슈 내용)**: 검사가 `externalJobId != null` 로 게이팅돼 있어, **ACK 가 아직 기록되지 않은
  job**(논블로킹 제출에서 콜백이 ACK 보다 먼저 도착하는 정상 구간, 또는 ACK 자체가 유실된 구간)은
  어떤 `job_id` 든 통과한다.

  `webhook/service/GenAiCallbackService.java:122-126`
  ```java
  if (target.getExternalJobId() != null && !target.getExternalJobId().equals(req.jobId())) {
      ... throw new CustomException(ErrorCode.CONFLICT, "job_id 가 일치하지 않습니다.");
  }
  ```
  그리고 이어지는 `target.markRunning(req.jobId())` / `markSucceeded(req.jobId())` 가 그 값을 그대로
  `OTSD_JOB_ID` 에 적재하므로, **첫 콜백이 실은 값이 사후 앵커가 된다.**

  **실동작(시나리오 24)**: `OTSD_JOB_ID` 가 비어 있는 job 4 에 `job_id=anything-goes` 를 보내면
  400/409 없이 **200** 으로 처리된다(해당 job 이 이미 terminal 이라 상태 변화는 없었다).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' \
    -d '{"request_id":"AUG-271a61ab-79b3-41e0-a446-36397bfb3ab0","job_id":"anything-goes","status":"SUCCEEDED",
         "results":[{"generated_data_id":"g1","media_type":"IMAGE","output_file_path":"/app/genai-out/x.jpg"}]}'
  # → HTTP 200 (job_id 불일치 검사 미수행)
  ```
- **영향**: 실질 위험은 낮다 — 진입에는 우리가 발급한 128비트급 `request_id`(UUID 기반) 가 필요하고,
  그 값을 아는 주체는 벤더뿐이다. 다만 ①"발급 request_id + 임의 job_id" 조합으로 **다른 증강이 나중에
  쓸 job_id 를 선점**해 그 증강을 409 로 영구 고착시키는 경합이 이론적으로 가능하고
  ②`OTSD_JOB_ID` 가 "외부가 발급한 식별자" 라는 계약이 데이터 수준에서 보장되지 않는다.
- **수정 방향(제안)**: ACK 미수신 구간에서는 `job_id` 를 앵커로 승격하지 말고 별도 컬럼(또는 `null` 유지)에
  보관한 뒤, `claimSubmitAck` 가 도착했을 때 대조해 불일치면 job 을 FAILED 로 종결한다.
  ⚠ **구현하지 않는다**.

---

### [E-ISSUE-25] TC-AUG-068 — RUNNING 진행 콜백에 **순서 검증이 없다**(progress 역전 수용)

- **심각도**: **LOW**
- **기대 동작(기대효과)**: 카탈로그 TC-AUG-068 은 "job `JOB_STTS_CD=RUNNING` 갱신, 200 `applied:true`,
  결과 처리·롤업 미수행" 만 요구하므로 **케이스 자체는 PASS** 다. 다만 계약(v1.1)이 진행 웹훅을
  10 → 50 → 90 순으로 정의하고 있어, 네트워크 재정렬·재시도로 역순 도착 시의 규약이 정의돼 있지 않다.
- **현재 동작(이슈 내용)**: `GenAiCallbackService.java:136-142` 는 `progress` 를 **로그로만** 쓰고
  저장·비교하지 않는다(엔티티에 progress 컬럼 없음). 실측: 같은 job 에 `progress=50` → `progress=10` 을
  연속 전송해도 둘 다 200 `applied:true` 로 수용된다.
- **재현/확인 경로**: 시나리오 13(위 표) 참조.
- **영향**: 현재는 무해하다 — 진행률을 어디에도 저장하지 않으므로 역전이 상태를 왜곡하지 않는다.
  다만 향후 진행률을 UI/집계에 노출하면 그대로 역행 표시가 된다. **기록 목적의 관측 항목**이다.
- **수정 방향(제안)**: 진행률을 노출할 계획이 생기면 `LS_DATA_AUG_JOB` 에 `PRGS_RT` 를 추가하고
  단조 증가 조건부 UPDATE 로만 갱신한다(표준용어 확인 선행). ⚠ **구현하지 않는다**.
