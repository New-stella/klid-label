# G 클러스터 part3 (G-7~G-10) 2차 검증 결과

> 대상: `docs/test-cases/G-ai-server.md` §G-7 KPST 비식별 목업(8) · §G-8 VLM 벤더 목업(4) ·
> §G-9 생성형 AI(genai) 증강 목업(21) · §G-10 BE↔외부 벤더 실배선 계약(13) = **46건**
> 폐기(`~~취소선~~`) 행 **0건**(해당 구간에 없음 → 집계 제외분 없음).
> 실행 환경: backend `localhost:18081`(HEAD `ca3c712b` 재빌드본, 02:42 KST) · mock-server `:9400` ·
> ai-server `:19300` · postgres(`public` 스키마).
> 본 에이전트 요청은 `g3v-*` 접두(project_name / request_id / Idempotency-Key)로 식별 가능하게 남겼다.
> 검증 중 **어떤 파일도 수정하지 않았고 빌드/테스트를 실행하지 않았다**(테스트는 파일:메서드명만 대조).

## 집계

| 판정 | 건수 | 비율 |
|------|---:|---:|
| PASS | 43 | 93.5% |
| PARTIAL | 3 | 6.5% |
| FAIL | 0 | 0% |
| BLOCKED | 0 | 0% |
| N/A | 0 | 0% |
| 확인필요 | 0 | 0% |
| **합계** | **46** | 100% |

| 절 | 건수 | PASS | PARTIAL | 실동작 검증 비율 |
|----|---:|---:|---:|---|
| G-7 (KPST 목업) | 8 | 6 | 2 | 7/8 실동작 |
| G-8 (VLM 목업) | 4 | 4 | 0 | 4/4 실동작 |
| G-9 (genai 목업) | 21 | 21 | 0 | 17/21 실동작 |
| G-10 (BE 실배선) | 13 | 12 | 1 | 4/13 실동작(나머지 정적+테스트 대조) |

**핵심 결론**
1. **외부연동 3종(KPST·VLM·genai)이 전부 mock-server 실왕복으로 동작하는 것을 실증**했다. 특히 1차에서
   미구현으로 판정됐던 **증강(genai) 위탁이 `HttpExternalAugmentClient` → mock `POST /api/genai/jobs`
   → webhook 4회 → 파생영상 생성까지 완주**한 로그가 남아 있다(§1차 이슈 대조).
2. **self-fill 경로는 발견되지 않았다.** 내부 목모드 플래그(`DEIDENTIFY_MOCK_MODE`,
   `authoring.augment.external.mode=noop`)는 모두 실효 off 이고, 1차 결함이던 자체 콜백 시뮬레이터
   (`DevAugmentCallbackSimulator`)는 코드베이스에서 제거됐다(grep 0건).
3. **위조 request_id 콜백은 VLM·genai 양쪽 모두 401 로 fail-closed**(실동작 확인).
4. 결함성 발견 3건은 전부 **문서/기대값 스테일 또는 목 서버 내부 상태 비대칭**이며 운영 경로 차단 결함은 없다.
   다만 **BE 가 벤더 `checksum` 을 선언만 하고 검증하지 않는** 무결성 갭(G-ISSUE-44)은 별도 조치 대상이다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 판정 | 2차 실측 | 결론 |
|------|------|------|------|
| **B-ISSUE-21** — VLM 외부 연동이 로컬 목업을 구조적으로 경유할 수 없어 실동작 검증 불가(WebClientConfig HTTPS-only 부팅 크래시) | 검증 불가 | `VLM_CLIENT_ENABLED=true` · `VLM_SERVICE_URL=http://klid-mock-server:9400` · `VLM_ALLOW_INSECURE_URL=true` 로 **기동 성공**, mock 로그에 `POST /v1/videovlm/describe` → `POST http://klid-backend:8080/api/v1/vlm/callback 200` 왕복 실증. 완화 경로는 `ProfileGatedUrlPolicy`(프로파일 allowlist + `ENV` 표식 + `@PostConstruct` assert)로 격리 | **✅ 해소** |
| **ENV-ISSUE-01 / E-ISSUE-02** — 증강 외부 연동 미구현(Noop 로그만, self-fill 의심) | 결함 확정 | `ActiveAugmentClientLogger` 부팅 로그 `active ExternalAugmentClient=HttpExternalAugmentClient`. 02:54:36~45 에 **backend→mock 실왕복 완주**(mock: `job accepted job_id=635750cf… request_id=AUG-fff3e013…-1 mode=I2I inputs=3`, webhook 4회 200 → BE `job succeeded outputCount=3` → `new video created rawSn=129 orgnlRawSn=126` → `AugmentFrameProducer ingested rawSn=129 frames=3 (external outputs)`) | **✅ 해소** — 산출물이 외부 응답 경로에서 옴(self-fill 아님) |
| **E-ISSUE-03** — `application-local.yml` 키 오중첩으로 dev 콜백 시뮬레이터 영구 비활성 | 결함 | 그 시뮬레이터(`DevAugmentCallbackSimulator`) 자체가 **삭제**됨(main 코드 grep 0건, 주석 참조만 잔존: `application-local.yml:49`). 오중첩 대상 키가 소멸 | **✅ 소멸(원인 제거)** — "고쳐짐"이 아니라 "그 경로가 없어짐" |
| 참고 — `WEBHOOK_HMAC_SECRET_AUGMENT`(1차 E-ISSUE-04) | 빈 값 → 401 | 값은 존재하나 `WebhookProtectedPaths.SIGNATURE_REQUIRED` 가 비어 **서명 필수 경로 0개**. genai 콜백은 벤더 계약상 무서명이고 대신 **발급 게이트(ledger)** 가 401 을 낸다(실동작 확인) | 무효화(구조 변경) |

---

## ★ 외부연동 계약 3자 대조표 (벤더규격 / mock-server / BE DTO)

### (A) genai 위탁 요청 — `POST /api/genai/jobs`

| 필드 | 명세서 v1.1 = mock `JobSubmitRequest`(schemas/genai.py:108-125) | BE `GenAiJobSubmitRequest`(dto:25-34) | 판정 |
|------|------|------|------|
| `request_id` | 필수 str(1~64) | ✅ 송신 | 일치 |
| `request_channel` | 필수 enum CONTROL/AUTHORING/PORTAL | ✅ `AUTHORING` 고정 | 일치 |
| `request_user_id` | 선택 str(64) | ✅ | 일치 |
| `evnt_type` | 필수 str(1~20) | ✅ | 일치 |
| `operation_type` | 필수 enum | ✅ `AUGMENT` 고정 | 일치 |
| `generation_mode` | 필수 enum | ✅ `I2I` 고정 | 일치 |
| `input_files[].sequence` / `.file_path` | 필수 | ✅ `GenAiInputFile(sequence,file_path)` | 일치 |
| `input_files[].checksum` | 선택 str(100) | **미송신(DTO 미선언)** | **갭(경미)** — 우리가 보내는 입력의 무결성 토큰 미제공 |
| `input_files[].source_file_id` | 선택 str(64) | **미송신** | 갭(경미) — 결과 짝짓기를 *순서*에만 의존하게 만드는 원인 |
| `prompt` | **필수** dict | ✅ `AugmentPrompts.of(augType)` | 일치 |
| `model_version_id` / `parameter_set_id` | 선택 | **미송신(DTO 미선언)** | 갭(경미, 현 스코프에서 불필요) |
| `callback_url` | 선택 str(500) | ✅ | 일치 |
| 헤더 `Idempotency-Key` | 선택(64) | ✅ `request_id` 와 동일 값 | 일치 |

### (B) genai 위탁 응답(202) — mock `JobAcceptedResponse` ↔ BE `GenAiJobAcceptedResponse`

| 필드 | mock | BE DTO | 판정 |
|------|------|------|------|
| `request_id` / `job_id` / `status` / `received_at` | 4필드 전부 | 4필드 전부 선언 + `request_id` echo·`status=RECEIVED`·`job_id` non-blank 검증(HttpExternalAugmentClient:164-181) | **완전 일치** |

### (C) genai 결과 webhook — mock `build_webhook_payload`(genai_sim.py:480-495) ↔ BE `GenAiCallbackRequest`

| 필드 | mock 송신 | BE DTO 선언 | 판정 |
|------|------|------|------|
| `request_id` / `job_id` / `status` / `progress` / `current_step` / `updated_at` | ✅ | ✅ (status 는 `RUNNING\|SUCCEEDED\|FAILED` 화이트리스트) | 일치 |
| `error_code` / `error_message` | FAILED 시 | ✅ | 일치 |
| `results[].generated_data_id` / `.media_type` / `.output_file_path` | ✅ | ✅ | 일치 |
| `results[].checksum` | ✅ sha256 실제 계산값(genai_sim.py:451) | **선언은 있으나 어디서도 읽지 않음**(grep: `GenAiCallbackService`·`AugmentResultService` 에 checksum 사용 0건) | **갭 → G-ISSUE-44** |
| `results[].media_metadata`(`mime_type`,`size_bytes`) | ✅ (genai_sim.py:452-455) | **미선언 → Jackson `ignoreUnknown` 으로 조용히 폐기** | **갭 → G-ISSUE-44** |

### (D) VLM describe — 요청/응답/콜백

| 방향 | mock 스키마 | BE DTO | 판정 |
|------|------|------|------|
| 요청 | `request_id`(선택) · `media{type,source_type,path,frame_policy{mode,framerate,selected_frames},duration_sec*}` · `callback_url`(HttpUrl 필수) | `VlmTimeseriesRequest{request_id, media{type,source_type,path,frame_policy{mode,framerate,selected_frames}}, callback_url}` | 일치 (`duration_sec` 는 **목 전용 확장**으로 BE 미송신 — 목이 명시) |
| 동기 응답 | `{request_id, status:"accepted"}` | `VlmTimeseriesResponse{request_id,status}` + echo/accepted 검증 | 일치 |
| 결과 콜백(성공) | `{request_id, status:"completed", results:[{start_sec,end_sec,description}]}` | `VlmResultRequest{request_id,status(completed\|failed),results[Segment{start_sec,end_sec,description}],error}` | **완전 일치** |
| 결과 콜백(실패) | `{request_id, status:"failed", error:{code,message}}` | `VlmError{code,message}` + `@AssertTrue` 상호조건 | **완전 일치** |

### (E) KPST 진행조회 — mock `retrieve_progress` ↔ BE `KpstProgressResponse`

| 필드 | mock 송신 | BE DTO | 판정 |
|------|------|------|------|
| `result` / `data.prjCount` / `data.prjStatus[]` | ✅ | ✅ | 일치 |
| `prjStatus[].prjId,prjName,progressRate,dsCount,dsStatus[]` | ✅ | ✅ | 일치 |
| `prjStatus[].prjState` | ✅ (2/3/5) | **미선언** | 의도된 무시 — BE 는 `dsStatus[].procState` 단일 축으로 판정(`KpstDeidentService:73-98` 명시). 도메인이 다른 두 코드를 섞지 않는 것이 옳음 |
| `prjStatus[].createTime/createId/exportPath` | ✅ | **미선언** | 무해(요청 시 우리가 보낸 값) |
| `dsStatus[].dsId,fileName,procState,progressRate,totalFrame,startTime,endTime` | ✅ | ✅ 전부(숫자는 boxed) | 일치 |
| 실패 sentinel `procState=99` | ✅ (경계 위반·원본 미열람 시) | `PROC_STATE_TERMINAL_FAILED = {3,4,99}` 로 **즉시 'F' 종결** | **양방향 정합** — 목의 fail-fast 가 BE 에서 실제로 효과를 낸다 |

---

## ★ self-fill 점검 결과

| 점검 축 | 결과 | 근거 |
|------|------|------|
| 내부 목모드로 외부 호출 우회하는 플래그가 **실효 true** 인가 | **없음** | `docker exec klid-backend env`: `DEIDENTIFY_MOCK_MODE=false` · `KPST_DEID_ENABLED=true` · `VLM_CLIENT_ENABLED=true` · `AUGMENT_EXTERNAL_MODE=http`. `NoopExternalAugmentClient` 는 `havingValue="noop"` 이라 미생성(부팅 로그가 `HttpExternalAugmentClient` 활성 확인) |
| 외부 응답 없이 값을 자체 생성하는 코드 | **없음(제거 확인)** | `DevAugmentCallbackSimulator` main 코드 grep 0건. 비식별 산출물은 mock 이 실제로 쓴 파일을 BE 가 회수, 증강 프레임은 `AugmentFrameProducer … (external outputs)` 로 외부 산출 경로에서 반입 |
| 외부 실패 시 조용히 성공 처리(fail-open) | **없음** | ①KPST: `procState∈{3,4,99}` → 즉시 `'F'` ②VLM: 신고 구간·경로 부재 시 **fail-closed 보류/예외** ③genai: `results` 없는 SUCCEEDED → `INVALID_INPUT`, 위탁건수≠수신건수 → `ERR_RESULT_COUNT_MISMATCH` 로 job FAILED |
| 위조/미발급 request_id 주입 | **401 차단(실동작)** | `POST /api/v1/genai/callback`(request_id=`g3v-forged-0001`) → **401 `발급되지 않은 request_id 입니다.`** / `POST /api/v1/vlm/callback`(`g3v-forged-vlm-0001`) → **401 동일** |
| **G-part2 HIGH(=목 폴백 결과가 실AI 결과와 구분 불가)의 G-10 확산 여부** | **G-10 연동에는 해당 없음** | `mock`/`source`/`mock_reason` 은 **우리 ai-server 자체 확장 필드**이고, G-10 이 다루는 외부 벤더(KPST·IntelliVIX·생성형AI)는 계약에 그런 필드가 아예 없다. 재확인 결과 `YoloResponse`(26-28행)·`Sam2Response`(21-23행)는 3필드를 **선언하고 있고**, 누락은 `Sam2TrackResponse`·`VlmVerifyResponse` 두 DTO에 한정된다 — **part2 소관, 본 회차 중복 보고하지 않음** |
| 다만 **선언은 있으나 사용하지 않는** 외부 무결성 값 | **1건 발견** | genai `results[].checksum` (→ G-ISSUE-44) |

---

## G-7 결과표 (KPST 비식별 벤더 목업 — 8건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-01 | GET / → 200 "Connect" | PASS | [실동작] `curl :9400/` → `Connect`(200). [정적] `routers/deid.py:116-119` | — |
| TC-AIMOCK-02 | GET /health → 200 {status:ok} | PASS | [실동작] `{"status":"ok"}`. [정적] `app/main.py:103-105` | 근거 드리프트(카탈로그 `main.py:94-96` = 라우터 등록 구간) |
| TC-AIMOCK-03 | retrieve_progress `fileName` = 원본 입력파일 경로 | PASS | [실동작] `POST /project`(prj_id=5, name=`g3v-tc0304`, input_path=`/app/storage/raw/seed/`, files=[`sample-cctv-1080p.mp4`]) → `dsStatus[0].fileName` = **`/app/storage/raw/seed/sample-cctv-1080p.mp4`**(산출물명 아님). [정적] `deid.py:86-112` / `deid_sim.py:272-279` | 근거 드리프트 |
| TC-AIMOCK-04 | 산출물명 = `{원본stem}-mask{ext}` | PASS | [실동작] 완료 후 `export_path` 에 **`sample-cctv-1080p-mask.mp4`**(34,654,319B) 생성. mock 로그 `watermark burned … file=sample-cctv-1080p-mask.mp4` → `production completed prj_id=5 files=1`. 타임스탬프 세그먼트 없음. [정적] `deid_sim.py:220-269` | BE 실왕복분(rawSn=123)도 동일 규칙으로 `…/123/deid/sample-cctv-1080p-mask.mp4` 존재 |
| TC-AIMOCK-05 | MOCK_OUTPUT_BASE 미설정 → 산출물 미생성(fail-closed, WARN 1회) | **PARTIAL** | [정적] `deid_sim.py:1705-1713`(WARN 1회 + `ProductionOutcome(written=[])`). [테스트] `mock-server/tests/test_deid_output.py:354 test_output_base_미설정이면_파일이_생기지않는다_failclosed` | 기대결과 문언(미생성·WARN·응답 200)은 충족. **단 `failed=False` 로 반환돼 `procState=2`(완료)로 보고** — 형제 경로(`OUTPUT_DIR_REJECTED`→99)와 비대칭 → **G-ISSUE-41**. 현 배포는 값이 설정돼 있어 미도달 |
| TC-AIMOCK-06 | export_path 가 output_base 밖 → 파일 미생성 + WARN, 응답 200 유지 | PASS | [실동작] `export_path=/tmp/g3v-escape`(prj_id=7) → `POST /project` **200**, `/tmp/g3v-escape` **미생성**, WARN `deid output dir rejected export_path=/tmp/g3v-escape output_base=/app/storage/raw,/app/storage/deidentified` + `production failed reason=OUTPUT_DIR_REJECTED`, 이후 `procState=99`. [정적] `path_policy.py:54-82` / `deid_sim.py:1716-1725` | 근거 드리프트. 관측 불가하게 실패하지 않음 ✓ |
| TC-AIMOCK-07 | input_base 밖 원본은 복사하지 않고 **placeholder 로 대체** | **PARTIAL** | [실동작] `input_path=/etc/`,`files=[hostname]`(prj_id=8) → export 디렉터리 **빈 채로 생성**, placeholder 없음. WARN `deid source rejected(boundary) … 원본을 읽지 않고 산출 실패로 종결` + `production failed reason=OUTPUT_WRITE_FAILED` → `procState=99`. [정적] `deid_sim.py:334-353`(`_safe_source_path`) / `path_policy.py:133-145` / `deid_sim.py:212-218` 주석 "★ #3 — placeholder 산출물은 폐기됐다". [테스트] `test_deid_output.py:323 …placeholder를_쓰지않고_산출실패로_종결한다` | **보안 목적(임의 파일 노출·디스크 고갈 차단)은 달성**이며 현 동작이 더 엄격. 카탈로그 기대결과가 스테일 → **G-ISSUE-42** |
| TC-AIMOCK-08 | 기존 산출물 있으면 덮어쓰지 않음(O_EXCL, 멱등) | PASS | [실동작] 같은 `export_path` 로 `g3v-tc08-rerun`(prj_id=6) 재실행 → 산출물 **inode 245092 / mtime 1785434523 / size 34654319 전부 불변**, 로그 `deid output exists — skip(no-overwrite) file=sample-cctv-1080p-mask.mp4` + `production completed files=1`. [정적] `deid_sim.py:408-419`(`_create_exclusive`, `O_EXCL\|O_NOFOLLOW`) · `439-485`(`_copy_no_overwrite`) | 근거 드리프트 |

## G-8 결과표 (VLM 벤더 목업 — 4건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-09 | verify·describe 즉시 accepted + 지연 콜백 | PASS | [실동작] `describe`(request_id=`g3v-tc09-ok-0001`) → **200 `{"request_id":"g3v-tc09-ok-0001","status":"accepted"}`**, 약 2초 후(`callback_delay_seconds=2.0`) mock 로그 `callback sent url=… request_id=g3v-tc09-ok-0001`. `verify`(`g3v-tc09b-verify-0001`)도 동일. [정적] `routers/vlm.py:180-237` | — |
| TC-AIMOCK-10 | callback_url 허용 호스트 밖 → 400, outbound 미발사 | PASS | [실동작] `callback_url=http://evil.example.com:8080/steal` → **400 `callback_url host is not allowed`**, WARN `callback_url rejected(not allowed host) request_id=g3v-tc10-ssrf-0001 host=evil.example.com`, **해당 request_id 의 콜백 발사 로그 0건**. [정적] `vlm.py:151-169` / `url_guard.py:28-33` | allowlist=`klid-backend,localhost,127.0.0.1` |
| TC-AIMOCK-11 | `fail` 접두 request_id → 동기 accepted, 콜백만 failed | PASS | [실동작] `fail-g3v-tc11-0001` → **동기 200 accepted**, 콜백 발사 확인(`callback sent … request_id=fail-g3v-tc11-0001`). 콜백 페이로드 `{status:"failed", error:{code:"INFERENCE_ERROR",…}}` 는 [정적] `vlm_sim.py:329-335,338-350` + [테스트] `tests/test_vlm.py:217 test_describe_실패트리거도_동기_accepted이고_failed콜백_발사` | **기대결과의 "동기 202"는 오기** — 실제·규격 모두 200(TC-AIMOCK-09 와 모순) → 드리프트 표 |
| TC-AIMOCK-12 | GET /v1/videovlm/status → 200 | PASS | [실동작] `{"status":"ok","service":"videovlm"}` 200. [정적] `vlm.py:241-244` | 근거 드리프트(카탈로그 228-231) |

## G-9 결과표 (생성형 AI 증강 벤더 목업 — 21건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-13 | 유효 요청 → 202 RECEIVED | PASS | [실동작] `g3v-tc13-0001`(I2I, input 1건) → **202** `{request_id,job_id:7dd1b472…,status:"RECEIVED",received_at}`. [정적] `routers/augment.py:263-310` [테스트] `test_genai_jobs.py:100` | — |
| TC-AIMOCK-14 | I2I·I2V 인데 input_files 없음 → 400 REQUIRED_FIELD_MISSING | PASS | [실동작] **400** `{"error_code":"REQUIRED_FIELD_MISSING","message":"input_files 는 I2I·I2V 에서 1건 이상 필요합니다"}`. [정적] `augment.py:220-226` [테스트] `test_genai_jobs.py:113,123` | — |
| TC-AIMOCK-15 | T2I·T2V 는 input_files 없어도 202 | PASS | [실동작] `g3v-tc15`(T2I) → **202 RECEIVED**(job c30728cf…). [정적] `schemas/genai.py:75-87` [테스트] `test_genai_jobs.py:131` | — |
| TC-AIMOCK-16 | sequence 중복 → 400 INVALID_PARAMETER | PASS | [실동작] sequence `[1,1]` → **400 `input_files[].sequence 는 중복될 수 없습니다`**. [정적] `augment.py:228-232` [테스트] `test_genai_jobs.py:139` | — |
| TC-AIMOCK-17 | 단계별 webhook(10/50/90) + 완료(100) 에만 results | PASS | [실동작·BE 왕복] mock→BE 4회 200: BE 로그 `running … progress=10 step=PREPROCESS` → `progress=50 INFERENCE` → `progress=90 POSTPROCESS` → `job succeeded … outputCount=3`. mock 로그 `webhook sent url=http://klid-backend:8080/api/v1/genai/callback status=200 job_id=635750cf… attempt=1` ×4. [정적] `genai_sim.py:81-86,480-495,583-648` [테스트] `test_genai_webhook.py:98` | 진행 webhook 에 results 미포함(BE 가 RUNNING 분기에서 결과 처리 안 함) ✓ |
| TC-AIMOCK-18 | results 는 SUCCEEDED 에서만, 그 외 409 | PASS | [실동작] 신규 job `9dbd9b94…` 접수 직후 `GET …/results` → **409 `{"error_code":"STATE_CONFLICT","message":"결과는 SUCCEEDED 상태에서만 조회할 수 있습니다"}`**. 완료 후 동일 EP 는 200. [정적] `augment.py:334-349` [테스트] `test_genai_jobs.py:228` | — |
| TC-AIMOCK-19 | output_file_path 실재 + sha256 일치 | PASS | [실동작] 응답 `output_file_path=/app/genai-out/genai/7dd1b472…/001_frame-1_genai.jpg`, `checksum=cc5d12a2af114391d70fd2799cfd46ec065b7a1c57858b55039604a8dbdf8a9b`. 컨테이너 `sha256sum` 결과 **동일 해시**, 파일 260,360B 실존. [정적] `genai_sim.py:380-458` [테스트] `test_genai_jobs.py:191` | 이 checksum 을 **BE 는 검증하지 않는다** → G-ISSUE-44 |
| TC-AIMOCK-20 | cancel — 종결 상태는 409 | PASS | [실동작] SUCCEEDED job 취소 → **409 `이미 종료된 작업은 취소할 수 없습니다`**. RUNNING/RECEIVED 는 200 CANCELED(아래 27). [정적] `augment.py:358-386` [테스트] `test_genai_jobs.py:245,271` | — |
| TC-AIMOCK-21 | Idempotency-Key 동일 재요청 → 동일 job_id / 65자는 400 | PASS | [실동작] 같은 키 `g3v-idem-key-001` 로 **다른 request_id**(`g3v-tc21-different`)를 보내도 응답이 **최초 job 그대로**(`request_id=g3v-tc13-0001`, `job_id=7dd1b472…`, 동일 `received_at`). 65자 키 → **400 `Idempotency-Key 는 64자 이하여야 합니다`**. [정적] `augment.py:163-175,276-303` [테스트] `test_genai_jobs.py:312,329,337` | — |
| TC-AIMOCK-22 | OUTPUT_BASE 미설정 → FAILED(RESULT_SAVE_FAILED) | PASS | [정적] `genai_sim.py:396-400`(`raise JobExecutionError(RESULT_SAVE_FAILED)`) → `run_job` except → `_fail_job` → FAILED webhook. [테스트] `test_genai_webhook.py:360 test_HIGH3_출력base_미설정이면_FAILED_RESULT_SAVE_FAILED` | 런타임 env 는 `/app/genai-out` 설정돼 있어 실동작 재현 불가(컨테이너 재기동 금지) — **KPST 쪽 동일 상황(TC-05)과 달리 이쪽은 fail-closed 가 상태에도 반영됨** |
| TC-AIMOCK-23 | file_path 허용 루트 밖/상대경로/base 미설정 → 400 | PASS | [실동작] 3종 전부 **400 `input_files[].file_path 는 허용된 루트의 절대경로여야 합니다`**: ①`relative/x.jpg` ②`/etc/passwd` ③`/app/storage/../etc/passwd`(경로순회). [정적] `genai_sim.py:223-250` [테스트] `test_genai_webhook.py:313,327` · `test_genai_security_hardening.py:303,311` | — |
| TC-AIMOCK-24 | callback_url SSRF: host[:port] allowlist + 경로접두사 + 자기참조 차단 | PASS | [실동작] 3종 전부 **400 `callback_url 이 허용되지 않습니다(…목 자신은 금지)`**: ①`http://evil.example.com:8080/cb`(비허용 호스트) ②`http://localhost:9400/api/genai/_mock/reset`(**자기참조**) ③`http://klid-backend:9999/cb`(허용 호스트 + **비허용 포트**). WARN `callback_url rejected(url guard)` 기록. [정적] `genai_sim.py:161-220` [테스트] `test_genai_security_hardening.py:159-241` | 포트 구분 allowlist 동작 실증 |
| TC-AIMOCK-25 | 본문·prompt 크기 상한 초과 → 413/400 | PASS | [실동작] ①body 1.2MB(>1MiB) → **413 `GA-MEDIA-001` `요청 본문 크기가 허용 한도를 초과했습니다`** ②prompt 100KB(>64KiB, body 는 1MiB 미만) → **400 `INVALID_METADATA` `prompt 크기가 허용 한도를 초과했습니다`**. [정적] `augment.py:72-119,208-217` [테스트] `test_genai_security_hardening.py:242-303` | 두 상한이 각각 독립적으로 발화 확인 |
| TC-AIMOCK-26 | 처리 시점 재검증(TOCTOU) — 접수 후 심링크 치환 시 FAILED, 유출 없음 | PASS | [정적] `genai_sim.py:288-361`(`_open_source_nofollow` — `O_NOFOLLOW` + 열린 **fd 기준 fstat** 재확인) · `461-476`(`_revalidate_source` — 처리 시점 base 재검증 → `MODEL_EXECUTION_FAILED`). [테스트] `test_genai_security_hardening.py:98 test_F1_접수후_입력파일이_base밖_심볼릭링크로_바뀌면_유출되지_않고_FAILED` · `:129` · `:329(F7 크기 증가)` | 실동작 재현은 공유 볼륨에 심링크를 **직접 생성**해야 해서 미수행(파일 수정 금지 원칙 + 동시 구동 중인 다른 에이전트 파이프라인 오염 위험) |
| TC-AIMOCK-27 | 취소 확정 시 산출물 정리(고아 없음) | PASS | [실동작] RECEIVED 상태 job `9dbd9b94…` 취소 → **200 CANCELED**, `/app/genai-out/genai/` 에 **해당 job 디렉터리 미생성**(고아 0). [정적] `genai_sim.py:364-378`(`discard_results`) + `run_job` 의 `CancelledError`/종결 분기. [테스트] `test_genai_security_hardening.py:409 test_F11_취소된_작업의_산출물은_남지_않는다` | 산출물 **생성 이후** 취소 분기는 타이밍상 실동작 재현 불가 → 테스트 커버로 판정 |
| TC-AIMOCK-28 | genai_event_types 화이트리스트 밖 evnt_type 거부 | PASS | [실동작] 현 배포는 `MOCK_GENAI_EVENT_TYPES` **미설정** → 규격대로 검증 생략(임의 `evnt_type="WINTER"` 202 수락). 화이트리스트 분기는 [정적] `augment.py:178-184` + [테스트] `test_genai_jobs.py:397 test_허용목록_밖_evnt_type은_UNSUPPORTED_EVENT_TYPE` | 두 분기 모두 확인 |
| TC-AIMOCK-29 | 작업 저장소 상한 초과 시 FIFO 만료 | PASS | [정적] `config.py:192-199`(`genai_max_jobs` 기본 1000, FIFO 만료 명시). [테스트] `test_genai_security_hardening.py:270 test_F3_잡_수_상한을_넘으면_오래된_작업부터_만료된다` | 근거 드리프트(카탈로그 `config.py:179-186` = `genai_max_body_bytes`) |
| TC-AIMOCK-30 | 목 전용 보조 EP 정상 동작(명세서 밖) | PASS | [실동작] `GET /api/genai/_mock/jobs` → 200, 등록 job 전량 + `active_tasks`. `POST /_mock/jobs/{id}/status-sync` → 200(아래 31). [정적] `augment.py:389-441` [테스트] `test_genai_jobs.py:444` | — |
| TC-AIMOCK-31 | status-sync 는 수동 트리거, 대상 미설정 시 비활성 | PASS | [실동작] **`{"job_id":"7dd1b472…","sent":false,"target":null,"reason":"MOCK_GENAI_STATUS_SYNC_URL 미설정 … status-sync 비활성"}`**(200). 자동 발신 흔적 0건. [정적] `genai_sim.py:563-579` [테스트] `test_genai_webhook.py:183,203,228` | — |
| TC-AIMOCK-32 | 인증(401/403) 의도적 미구현 — 갭 아님 | PASS | [실동작] 본 회차 genai 요청 **전건이 무인증**이며 정상 202/400/409 응답. [정적] `augment.py:20`("인증 … 이번 스코프에서 의도적으로 미구현") · `schemas/genai.py:8-9` | 설계 의도대로 |
| TC-AIMOCK-33 | 방어 로그가 `docker logs` 로 관측 가능(stdout basicConfig) | PASS | [실동작] 본 회차에 유발한 WARN 이 전부 `docker logs klid-mock-server` 에 노출: `deid output dir rejected` · `deid source rejected(boundary)` · `callback_url rejected(not allowed host)` · `input path rejected(path guard)` · `[MOCK][GENAI] api error code=INVALID_PARAMETER`. [정적] `main.py:32-46` | 관측성 회귀 없음 |

## G-10 결과표 (BE ↔ 외부 벤더 실배선 계약 — 13건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-AIMOCK-34 | ★비식별 헬스체크는 벤더 루트(`/`)를 핑한다 | PASS | [실동작] mock 로그에 backend 컨테이너 IP(`172.18.0.5`)발 **`"GET / HTTP/1.1" 200 OK`** 가 주기적으로 기록(`/health` 는 `ControlNotifyHealthIndicator` 몫 — `ControlNotifyHealthIndicator.java:39`). BE `/actuator/health` → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`. [정적] `DeidentifyHealthIndicator.java:92-104` [테스트] `DeidentifyHealthIndicatorTest:88 실모드_핑은_벤더가_제공하는_루트경로로_요청한다` | 카탈로그 §G-6 주의문(두 `/health` 혼동 금지)과 정합 |
| TC-AIMOCK-35 | mock-mode=true → 외부 핑 없이 UP(mode=mock) | PASS | [정적] `DeidentifyHealthIndicator.java:77-83` [테스트] `DeidentifyHealthIndicatorTest:58 mock모드_활성시_외부핑없이_UP_mock` | 현 배포 `DEIDENTIFY_MOCK_MODE=false` 라 실동작 미재현(컨테이너 재기동 금지). **이 플래그가 실효 false 인 것이 대전제 #2 준수의 근거** |
| TC-AIMOCK-36 | 둘 다 off → DOWN(unconfigured, fail-closed) | PASS | [정적] `DeidentifyHealthIndicator.java:84-91` [테스트] `DeidentifyHealthIndicatorTest:121 mock도_KPST위탁도_아니면_핑없이_DOWN_설정오류` · `:184` | 동일(설정 변경 필요) |
| TC-AIMOCK-37 | 핑 예외 시 DOWN, 예외 simpleName 만 노출(CWE-209) | PASS | [정적] `DeidentifyHealthIndicator.java:105-112`(`e.getClass().getSimpleName()`) [테스트] `DeidentifyHealthIndicatorTest:104 실모드_서버타임아웃시_DOWN` | 스택트레이스·주소 미노출 확인 |
| TC-AIMOCK-38 | VlmUrlPolicy relaxed — local + 완화플래그 + 배포표식 없음일 때만 | PASS | [실동작] `VLM_ALLOW_INSECURE_URL=true`·local 프로파일에서 **평문 http + 사설 IP 목업으로 기동 성공 + 실왕복**(mock describe 200 ×N). [정적] `ProfileGatedUrlPolicy.java:121-133,145-151` / `ExternalUrlPolicy.java:80-82` [테스트] `VlmUrlPolicyTest:38,49` | — |
| TC-AIMOCK-39 | 허용 프로파일 밖/ENV=stg\|prd 면 기동 실패(fail-closed) | PASS | [정적] `ProfileGatedUrlPolicy.java:78-91`(`@PostConstruct` → `IllegalStateException`), allowlist `containsAll` + `ENV` 독립 축(`DEPLOYED_ENV_MARKERS`) [테스트] `VlmUrlPolicyBootGuardTest:22 prd_프로파일에서_완화_플래그가_켜져있으면_컨텍스트_기동이_실패한다` · `VlmUrlPolicyTest:78,97,136` | 혼합 프로파일·오타·미지정 자동 엄격 |
| TC-AIMOCK-40 | relaxed 에서도 링크로컬/메타데이터 대역 거부(CWE-918) | PASS | [정적] `ExternalUrlPolicy.java:143-153`(`isLinkLocalAddress()` + `169.254.` 접두) [테스트] `VlmUrlPolicyTest:160 링크로컬_메타데이터_대역은_완화_프로파일에서도_차단된다` | — |
| TC-AIMOCK-41 | relaxed 는 DNS 해석 실패 통과 / strict 는 거부 | PASS | [정적] `ExternalUrlPolicy.java:143-153`(`resolveQuietly` → null 이면 return) vs `169-181`(`UnknownHostException` → `IllegalStateException`) [테스트] `VlmUrlPolicyTest:178 해석되지_않는_컨테이너명은_완화_프로파일에서_계속_허용된다` | 컨테이너 서비스명 기동 보장 |
| TC-AIMOCK-42 | describe request_id echo 불일치/status≠accepted → EXTERNAL_API_ERROR | PASS | [정적] `VlmClient.java:154-169`(`validateResponse`) [테스트] `VlmClientTest:140 응답_request_id_echo_불일치_시_EXTERNAL_API_ERROR` · `:155 응답_status_accepted_아니면_EXTERNAL_API_ERROR` | ⚠ **동작 변경(Phase C-1)**: 논블로킹 제출로 바뀌어 이 예외는 스텝을 FAILED 시키지 않고 `VlmSubmitOutcomeRecorder.onSubmitFailed` → `SKIP_REASON_SUBMIT_FAILED` 감사행 + 재개(`VlmWithheldResumeRunner`)로 흐른다. 목이 echo 를 그대로 돌려주므로 실동작 주입은 불가 |
| TC-AIMOCK-43 | 4xx 비재시도(NonRetryableExternalException, 서킷 ignore) | PASS | [정적] `VlmClient.java:106,121-127` + `application.yml:500-507`(circuitbreaker `vlmClient.ignore-exceptions`) · `547-553`(retry `vlmClient.ignore-exceptions`) [테스트] `VlmClientTest:220 V1_400_형식오류는_재시도없이_1회요청_비재시도예외전파` · `:235(422)` | 근거 드리프트(카탈로그 `application.yml:512-520`) |
| TC-AIMOCK-44 | 타임아웃 2계층 — WebClient 10s 안쪽에 배치 블록 45s | **PARTIAL** | [정적] WebClient 10s 는 성립: `VlmClient.java:71-76,108`(`vlm.client.timeout-seconds:10`, `application.yml:633`). **그러나 45s 블록 계층은 존재하지 않는다** — `VlmTimeseriesStep` 은 Phase C-1 에서 논블로킹 제출(`subscribe`)로 전환됐고 `BLOCK_TIMEOUT`/`.block(...)` 이 전무하다(grep: `VlmTimeseriesStep`·`VlmClient` 에 0건, `45` 는 349행 **과거 서술 주석**뿐). 카탈로그 근거 `VlmTimeseriesStep.java:78` 은 현재 **javadoc 본문** | 실효 타임아웃 = **10s 단일** + retry 3회(exp backoff). UNCERTAINTIES **#13("블록 상한 45s ↔ 실효 10s")도 스테일** → **G-ISSUE-43** |
| TC-AIMOCK-45 | enabled=false(공통 기본) → SKIPPED / local 기본 true → 실 왕복 | PASS | [실동작] local: `VLM_CLIENT_ENABLED=true`(`application-local.yml:147`) → mock 로그 `POST /v1/videovlm/describe 200`(172.18.0.5) → `POST http://klid-backend:8080/api/v1/vlm/callback 200` **왕복 2회 실증**. [정적] 공통 `application.yml:626`(`${VLM_CLIENT_ENABLED:false}`) + `VlmClient.java:94-97` [테스트] `VlmClientTest:170 enabled_false_시_외부_호출_없이_SKIPPED_반환_NO_OP` · `VlmTimeseriesStepTest:97,332` | — |
| TC-AIMOCK-46 | 신고 구간(`DE_IDNTF_YN='F'`)이면 전송 직전 SKIPPED 보류 + 해소 시 재위탁 | PASS | [정적] `VlmTimeseriesStep.java:303-307`(경로 해석 **직전** `deidentReportGate.isUnderDeidentReport` → `recordVlmSkipped(SKIP_REASON_DEIDENT_REPORT)` + SKIPPED 반환, 외부 호출 0건) · `113`(재개 배선 키 상수) · `150-152`(`RESUMABLE_SKIP_REASONS`) · `VlmResumeBridge`/`VlmWithheldResumeRunner` 실재 [테스트] `VlmTimeseriesStepTest:113 비식별_신고_구간_영상은_외부_VLM_호출_0건이고_보류로_기록된다` · `:137` · `VlmWithheldResumeRunnerTest:67,105,143` | 근거 드리프트(카탈로그 `60-65,95,216-220`). 실동작 재현은 영상에 신고를 걸어야 해 다른 에이전트 파이프라인 오염 위험 → 미수행 |

---

## 근거 드리프트

카탈로그 `근거(file:line)` 가 현재 소스와 어긋난 항목(**총 10건**). 판정에는 영향 없으나 다음 최신화에서 정정 필요.

| TC | 카탈로그 근거 | 실제 위치 | 유형 |
|----|------|------|------|
| TC-AIMOCK-01 | `mock-server/app/routers/deid.py:101-105` | `deid.py:116-119`(`connect()`) | 행 이동 |
| TC-AIMOCK-02 | `mock-server/app/main.py:94-96` | `main.py:103-105`(`@app.get("/health")`) — 94-96 은 라우터 등록 | 행 이동 |
| TC-AIMOCK-03 | `deid.py:76-98` / `deid_sim.py:210-217` | `deid.py:86-112`(`_build_ds_status`) / `deid_sim.py:272-279`(`source_path_of`) | 행 이동 |
| TC-AIMOCK-04 | `deid_sim.py:162-165,193-207` | `deid_sim.py:220-269`(`MASK_SUFFIX`·`mask_name_from`) | 행 이동 |
| TC-AIMOCK-05 | `deid_sim.py:409-444` | `deid_sim.py:1705-1713` — 409-444 는 현재 `_create_exclusive`/`_copy_limited` | 대폭 이동(파일 1,700행+ 로 성장) |
| TC-AIMOCK-06 | `deid_sim.py:248-276,446-453` | `path_policy.py:54-82`(`resolve_output_dir`) + `deid_sim.py:1716-1725` | 모듈 분리(F-7 단일 원천화) |
| TC-AIMOCK-07 | `deid_sim.py:279-326,359-406` | `deid_sim.py:334-353`(`_safe_source_path`) + `path_policy.py:133-145` + `deid_sim.py:1560-1575` | 모듈 분리 + **기대값 자체 변경**(G-ISSUE-42) |
| TC-AIMOCK-08 | `deid_sim.py:328-346,368-373` | `deid_sim.py:408-419`(`_create_exclusive`) · `439-485`(`_copy_no_overwrite`) | 행 이동 |
| TC-AIMOCK-12 | `routers/vlm.py:228-231` | `vlm.py:241-244`(`status_check`) | 행 이동 |
| TC-AIMOCK-29 | `config.py:179-186` | `config.py:192-199`(`genai_max_jobs`) — 179-186 은 `genai_max_body_bytes` | **다른 설정을 가리킴** |
| TC-AIMOCK-43 | `application.yml:512-520` | `application.yml:500-507`(circuitbreaker) + `547-553`(retry) | 행 이동 |
| TC-AIMOCK-44 | `VlmTimeseriesStep.java:78` | 현재 **javadoc 본문**(코드 아님). 대응 코드 자체가 제거됨 | **근거 소멸**(G-ISSUE-43) |
| TC-AIMOCK-46 | `VlmTimeseriesStep.java:60-65,95,216-220` | `VlmTimeseriesStep.java:303-307`(게이트) · `113`(사유 상수) · `150-152` | 대폭 이동 |

기대값 자체가 어긋난 항목(근거 위치와 별개):

| TC | 카탈로그 기대값 | 실제 | 조치 |
|----|------|------|------|
| TC-AIMOCK-07 | "placeholder 18바이트로 대체" | placeholder 폐기 → **산출 실패(procState=99)** | G-ISSUE-42 |
| TC-AIMOCK-11 | "동기 **202** accepted" | 실제·규격 모두 **200**(TC-AIMOCK-09 는 200 으로 기술 — 카탈로그 내부 모순) | 기대값 200 으로 정정 |
| TC-AIMOCK-44 | "스텝은 45s 상한" | 45s 블록 계층 **부재**(논블로킹 전환) | G-ISSUE-43 (UNCERTAINTIES #13 도 동시 갱신) |

---

## 이슈 상세

### [G-ISSUE-41] TC-AIMOCK-05 — `MOCK_OUTPUT_BASE` 미설정 시 산출물 0건인데 목이 "완료(procState=2)"로 보고한다 (형제 경로와 비대칭)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목이 "완료"를 보고하는 것은 **BE 가 회수 가능한 산출물이 최종 경로에 실재한다**는 뜻이어야 한다. `deid_sim.py` 스스로 이 원칙을 명문화하고 있다 — `OutputWriteResult` docstring(`deid_sim.py:376-393`)은 "아무 파일도 남기지 않는데 성공으로 보고돼 `production=SUCCEEDED`/`files=0`/`procState=2`(진행률 100) 라는 **거짓 완료**가 나왔다"를 폐쇄 대상 결함으로 기술하고, 형제 경로인 `OUTPUT_DIR_REJECTED`(export_path 경계 위반)는 `failed=True` → `procState=99` 로 보고한다.
- **현재 동작(이슈 내용)**: 허용 루트 자체가 **미설정**인 경우만 예외적으로 `failed=False` 로 빠져나간다.

  `mock-server/app/services/deid_sim.py:1705-1713`
  ```python
  if not output_base:
      global _base_unset_warned
      if not _base_unset_warned:
          logger.warning(
              "[MOCK][KPST] MOCK_OUTPUT_BASE 미설정 — 더미 비식별 출력 파일 미생성"
              "(fail-closed). e2e 시 BE 의 STORAGE_RAW_MOUNT_ROOTS 와 같은 값(콤마 구분)으로 설정하세요.")
          _base_unset_warned = True
      return ProductionOutcome(written=written)      # ← failed 기본값 False
  ```
  `run_production`(`deid_sim.py:1953-1964`)이 `outcome.failed` 만 보므로 `PRODUCTION_SUCCEEDED` → `proc_state_for` → **`procState=2`**. 바로 아래 형제 분기(`deid_sim.py:1716-1725`)는 같은 "쓸 수 없음"인데 `failed=True, reason="OUTPUT_DIR_REJECTED"` → **99**.
- **재현/확인 경로**(`MOCK_OUTPUT_BASE=""` 로 mock 을 기동한 환경에서):
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' -d '{
    "project_name":"chk-nobase","creator":"qa","export_path":"/app/storage/raw/seed/x/deid",
    "input_path":"/app/storage/raw/seed/","files":["sample-cctv-1080p.mp4"],"is_img":0}'
  # 몇 초 뒤
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk-nobase"}'   # → dsStatus[].procState 관찰(현재 2 예상)
  ```
  대조군(현 배포에서 즉시 재현 가능): `export_path=/tmp/g3v-escape` → `procState=99`(본 회차 prj_id=7 실측).
- **영향**: 목이 완료를 보고하면 BE(`KpstDeidentService`)는 존재하지 않는 산출물을 회수하러 가 무결성 검증에서 탈락 → `DE_IDNTF_YN='F'` 로 종결하는데, 로그에는 "완료 후 회수 실패"만 남아 **원인(설정 누락)이 드러나지 않는다**. 게다가 `projectName`(=`raw{rawSn}`)이 이미 점유돼 재위탁이 409 로 막히는 조용한 고착이 된다 — 이 파일이 다른 경로들에서 제거하려 한 바로 그 실패 모드다. 스코프는 **목 서버 오설정 시에 한정**(운영 영향 없음), 다만 로컬·dev 검증 전체가 이 목을 경유하므로 디버깅 비용이 크다.
- **수정 방향(제안)**: `output_base` 미설정 분기도 `ProductionOutcome(written=[], failed=True, reason="OUTPUT_BASE_UNSET")` 로 통일한다. 단 **명시적 opt-out 인 `write_output_files=false`(`deid_sim.py:1931-1934`)와는 구분**해야 한다 — 그쪽은 "만들지 않기로 한 설정"이라 성공 no-op 이 옳고, 이쪽은 "설정 누락"이다. ⚠ **구현하지 않는다**(검증 회차 스코프 밖).

### [G-ISSUE-42] TC-AIMOCK-07 — 카탈로그 기대값(placeholder 대체)이 스테일: 현재는 산출 실패로 종결한다

- **심각도**: LOW (카탈로그 정합성 — 제품 결함 아님)
- **기대 동작(기대효과)**: 카탈로그 기대결과 = "target 파일이 원본 복사가 아닌 **18바이트 placeholder**".
- **현재 동작(이슈 내용)**: placeholder 산출은 **의도적으로 폐기**됐고 최종 경로를 선점하지 않은 채 산출 실패로 끝난다.

  `mock-server/app/services/deid_sim.py:212-218`
  ```
  ★ #3 — placeholder 산출물은 폐기됐다. 원본을 읽지 못하는 경우(부재/권한/허용 루트 밖/입력 마운트
    불일치) 구 구현은 18바이트 스텁을 최종 경로에 쓰고 완료(procState=2)로 보고했다. 그 파일은 BE
    무결성(≥512B + 컨테이너 시그니처)에서 탈락해 'F' 가 되는데, no-overwrite 라 그 이름은 이후 어떤
    재시도로도 대체되지 않는다(영구 고착). 지금은 산출 실패(procState=99) 로 종결한다 …
    유효 크기의 가짜 영상으로 대체하는 안은 채택하지 않았다: 읽지도 못한 원본을 '비식별 완료'로
    승인시키는 위장 산출물이 되기 때문이다(CWE-345).
  ```
  실측(본 회차 prj_id=8, `input_path=/etc/`): export 디렉터리는 **빈 채로 생성**, WARN 2줄 후 `production failed reason=OUTPUT_WRITE_FAILED` → `procState=99`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' -d '{
    "project_name":"chk-inescape","creator":"qa","export_path":"/app/storage/raw/seed/chk/deid",
    "input_path":"/etc/","files":["hostname"],"is_img":0}'
  docker exec klid-mock-server ls -la /app/storage/raw/seed/chk/deid   # → 빈 디렉터리
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk-inescape"}'                   # → procState 99
  ```
- **영향**: TC 의 보안 목적(허용 루트 밖 임의 파일 노출·디스크 고갈 차단)은 **더 강하게** 충족된다. 다만 기대값 그대로 자동검증을 짜면 위양성 FAIL 이 난다.
- **수정 방향(제안)**: 카탈로그 기대결과를 "원본을 읽지 않고 **산출 실패(procState=99)** 로 종결, 최종 경로 미선점(placeholder 없음)"으로 정정. 회귀 가드는 `mock-server/tests/test_deid_output.py:323` 이 이미 보유. ⚠ **구현하지 않는다**.

### [G-ISSUE-43] TC-AIMOCK-44 — VLM 타임아웃 "2계층(10s/45s)" 전제가 소멸했다(논블로킹 전환). 카탈로그·UNCERTAINTIES #13 동시 스테일

- **심각도**: MEDIUM (문서·기대값 정합. 운영 동작은 개선된 상태)
- **기대 동작(기대효과)**: 카탈로그 = "WebClient 자체 10s(`vlm.client.timeout-seconds`) 안쪽에 배치 오케스트레이션 블록 45s(`BLOCK_TIMEOUT`)". UNCERTAINTIES #13 = "블록 상한 45s ↔ 실효 클라이언트 타임아웃 10s".
- **현재 동작(이슈 내용)**: **45s 블록 계층 자체가 없다.** `VlmTimeseriesStep` 은 Phase C-1 에서 논블로킹 제출로 전환됐다.

  `backend/.../batch/step/VlmTimeseriesStep.java:361-377` (발췌)
  ```java
  vlmClient.submitTimeseries(req)
      .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR, ...)))
      .subscribe(
          resp -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                  () -> outcomeRecorder.onAccepted(rawSn, requestId, resp)),
          err  -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                  () -> outcomeRecorder.onSubmitFailed(rawSn, markingSn, err)));
  ...
  return VlmTimeseriesResponse.submitted(requestId);   // ACK 도 기다리지 않는다
  ```
  검증: `grep -rn "BLOCK_TIMEOUT\|block(Duration"` → `VlmTimeseriesStep`·`VlmClient` **0건**(잔존은 `ControlNotifyClient.java:52` 의 별개 상수). `VlmTimeseriesStep.java` 안의 문자열 `45` 는 349행 **과거 동작을 설명하는 주석**뿐. 카탈로그가 가리키는 `VlmTimeseriesStep.java:78` 은 현재 javadoc 본문이다.
  따라서 실효 타임아웃은 **WebClient 10s 단일**(`VlmClient.java:71-76,108` + `application.yml:633 timeout-seconds: 10`) + `retry vlmClient`(max-attempts 3 · wait 1s · multiplier 2, `application.yml:547-553`)이며, 파이프라인 스레드는 **전혀 점유되지 않는다**.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "BLOCK_TIMEOUT\|\.block(" src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java \
      src/main/java/kr/co/cudo/authoring/common/client/VlmClient.java   # → 0건
  grep -n "timeout-seconds" src/main/resources/application.yml           # → 633: timeout-seconds: 10
  # 회귀 가드(실행하지 말 것 — 파일:메서드만 대조):
  # backend/src/test/.../VlmTimeseriesStepNonBlockingTest.java:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다
  ```
- **영향**: 문서 기준으로 "45s 안에 스텝이 실패한다"를 전제한 운영 판단·회귀 케이스가 어긋난다. 실패 경로도 바뀌었다 — 제출 실패는 **스텝 FAILED 가 아니라** `SKIP_REASON_SUBMIT_FAILED` 감사행 + `VlmSubmitPendingSweeper`/`VlmWithheldResumeRunner` 회수로 흐르므로, "45s 뒤 배치 FAILED"를 기다리는 관측은 영원히 오지 않는다.
- **수정 방향(제안)**: ①TC-AIMOCK-44 기대결과를 "실효 타임아웃 = WebClient 10s 단일 + retry 3회. 스텝은 ACK 를 기다리지 않으며(논블로킹) 미수신은 `SKIP_REASON_ACK_MISSING`/`SKIP_REASON_CALLBACK_MISSING` 로 회수" 로 교체하고 근거를 `VlmClient.java:71-76,108` / `VlmTimeseriesStep.java:361-381` 로 이동 ②UNCERTAINTIES #13 의 "블록 상한 45s" 서술 삭제(미확정은 IntelliVIX 실서버 대조만 남김). ⚠ **구현하지 않는다**.

### [G-ISSUE-44] (연관 TC-AIMOCK-19 / 3자 대조표 C) — BE 가 생성형 AI 산출물의 `checksum` 을 선언만 하고 검증하지 않는다 (`media_metadata` 는 아예 폐기)

- **심각도**: MEDIUM (CWE-345 데이터 진정성 검증 부재)
- **기대 동작(기대효과)**: 벤더가 `results[].checksum`(SHA-256)을 주는 이유는 **우리가 그 파일을 읽어 파생 프레임으로 반입하기 전에 무결성을 확인**하라는 것이다. 반입 경로는 NAS 공유 마운트라 전송 중단·부분 기록·교체가 실재하는 실패 모드다.
- **현재 동작(이슈 내용)**: 목(=명세서)은 실제 해시를 계산해 보낸다.

  `mock-server/app/services/genai_sim.py:446-457`
  ```python
  results.append({
      "generated_data_id": uuid.uuid4().hex,
      "media_type": media_type.value,
      "output_file_path": str(target),
      "checksum": _sha256_of(target),
      "media_metadata": {"mime_type": _MIME_BY_EXT.get(ext, "application/octet-stream"),
                         "size_bytes": target.stat().st_size},
  })
  ```
  BE `GenAiCallbackRequest.ResultItem`(`webhook/dto/GenAiCallbackRequest.java:96-117`)은 `checksum` 을 **선언만** 하고, `media_metadata` 는 **선언조차 없어** `@JsonIgnoreProperties(ignoreUnknown = true)` 로 조용히 버려진다. 소비 지점(`GenAiCallbackService.verifiedOutputPaths`, 214-237)은 `item.outputFilePath()` 만 쓴다:
  ```java
  for (GenAiCallbackRequest.ResultItem item : results) {
      artifactRootResolver.verifyExternalReadablePath(item.outputFilePath());  // 경로 경계만
      paths.add(item.outputFilePath());                                        // checksum 미사용
  }
  ```
  검증: `grep -n "checksum\|Checksum\|sha256\|mediaMetadata" GenAiCallbackService.java AugmentResultService.java` → **0건**.
- **재현/확인 경로**:
  ```bash
  cd backend && grep -rn "checksum" src/main/java/kr/co/cudo/authoring/webhook/ | grep -v "dto/"   # → 0건
  # 실동작 대조(본 회차 실측): mock 이 내려준 checksum 과 파일 해시는 일치하지만 BE 는 그 값을 읽지 않는다
  docker exec klid-mock-server sha256sum /app/genai-out/genai/<job_id>/001_frame-1_genai.jpg
  ```
- **영향**: 경로 경계(CWE-22)는 막지만 **내용 진정성은 확인하지 않는다**. 부분 기록·손상·교체된 산출물이 그대로 파생영상 프레임으로 확정되고(`AugmentFrameProducer`), 그 뒤에는 라벨링·검수·데이터마트까지 흘러간다. 같은 계약의 `input_files[].checksum`/`source_file_id` 도 **송신하지 않고 있어**(3자 대조표 A), 결과 짝짓기가 `LS_DATA_AUG_JOB_FILE` 의 **순서**에만 의존한다 — 순서가 어긋나면 다른 프레임에 남의 증강본이 붙는데(코드 주석도 이 위험을 명시), 그 방어가 "건수 일치" 하나뿐이다.
- **수정 방향(제안)**: ①`applySucceeded` 의 경로 되붙이기 직전에 `checksum` 이 존재하면 파일 해시와 대조하고 불일치 시 `ERR_RESULT_CHECKSUM_MISMATCH` 로 job FAILED(기존 `ERR_RESULT_COUNT_MISMATCH` 와 동일한 fail-closed 규약) ②`media_metadata` 를 DTO 에 선언해 최소한 `size_bytes` 를 실파일과 대조(선언 없이 버리면 벤더가 무엇을 보내는지 코드만 보고 알 수 없다) ③중기적으로 `input_files[].source_file_id` 를 송신하고 결과의 대응 필드로 짝짓기해 순서 의존을 제거. ⚠ **구현하지 않는다**(계약 변경은 벤더 협의 대상).
