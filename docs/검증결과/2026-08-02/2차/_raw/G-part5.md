# G-part5 — 외부 벤더 목업 계약 검증 (G-7 KPST · G-8 VLM · G-9 genai, 33건)

- **대상**: `docs/test-cases/G-ai-server.md` §G-7(8) + §G-8(4) + §G-9(21) = **33건**
- **검증일**: 2026-08-02
- **검증 방식**: mock-server(`127.0.0.1:9400`, 컨테이너 `klid-mock-server`) **실동작 호출 26건** + 정적 대조 7건
- **런타임 실효 환경변수**(docker inspect 실측):
  `MOCK_OUTPUT_BASE=/app/storage/raw,/app/storage/deidentified` · `MOCK_INPUT_BASE=/app/storage` ·
  `MOCK_CALLBACK_ALLOWED_HOSTS=klid-backend,localhost,127.0.0.1` ·
  `MOCK_GENAI_INPUT_BASE=/app/storage` · `MOCK_GENAI_OUTPUT_BASE=/app/genai-out`
  (`MOCK_GENAI_EVENT_TYPES`·`MOCK_GENAI_STATUS_SYNC_URL`·`MOCK_GENAI_CALLBACK_PATH_PREFIXES` 미설정)
- **BE 실경유 증거**: `GET /api/genai/_mock/jobs` 에 BE 발급 `request_id=AUG-{uuid}-1` 형태 job 다수(SUCCEEDED) — genai 경로는 self-fill 아님이 실측 확인됨.
- **벤더 계약 원문 대조 자료**:
  - VLM = `docs/video_vlm_api_ v2.0.1.docx` (IntelliVIX 원본 docx **본문 추출해 전문 대조**)
  - KPST = `docs/v2-wiki/22-deid-solution-api.md` (KPST 『비식별화 솔루션 API 연동 방안』 v1.0 전사본)
  - genai = 「생성형 AI API 연동명세서 v1.1」 원문은 저장소 부재 → `docs/v2-wiki/14-augmentation.md`(INT-001/019/020/029/030/031 매핑) + 코드 주석의 §번호 참조로 간접 대조
- ⚠ **환경 부작용 고지**: G-ISSUE-82 반증 과정에서 VLM 콜백이 목 서버 자신의 `POST /api/genai/_mock/reset` 을 호출해 **genai 인메모리 job 저장소가 1회 초기화**됐다(로그 `[MOCK][GENAI] store reset`, 09:19:32). 이는 취약점의 실증 결과이며 BE DB 에는 영향 없다. 이후 회차에서 genai `_mock/jobs` 이력이 비어 있는 이유가 이것이다.

---

## 판정 요약

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| G-7 KPST | 8 | 7 | 0 | 1 | 0 | 0 | 0 |
| G-8 VLM | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| G-9 genai | 21 | 21 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **33** | **31** | **1** | **1** | **0** | **0** | **0** |

---

## G-7. KPST 비식별 벤더 목업 (8건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-01 | PASS | [실동작] | `GET /` → `HTTP/1.1 200` · `content-type: text/plain; charset=utf-8` · body `Connect`. KPST 명세 §22.3.1(`"Connect"` text/plain 200) 정합. ⚠근거 드리프트: `deid.py:101-105` → 실제 `deid.py:116-119` |
| TC-AIMOCK-02 | PASS | [실동작] | `GET /health` → `200 {"status":"ok"}`. ⚠근거 드리프트: `main.py:94-96` → 실제 `main.py:103-105`. 카탈로그 주석대로 KPST 벤더 계약 아님(명세 13종에 `/health` 없음) — 표기 정확 |
| TC-AIMOCK-03 | PASS | [실동작] | `POST /project`(input_path=`/app/storage/raw/seed/`, files=`["clip-9101.mp4"]`) 후 `GET /retrieve_progress` 응답 `dsStatus[0].fileName = "/app/storage/raw/seed/clip-9101.mp4"` = **input_path + 원본 basename**(산출물명 아님). `retrieve_report`·`manual_deid_info`·`retrieve_job_logs` 도 동일 값 사용해 일관. ⚠근거 드리프트: `deid.py:76-98`→`86-112`, `deid_sim.py:210-217`→`272-279`(현 210-217 은 placeholder 폐기 주석) |
| TC-AIMOCK-04 | PASS | [실동작] | 완료 후 `ls /app/storage/deidentified/videos/qa-g5-p1/` → **`clip-9101-mask.mp4`**(50,854B, 타임스탬프 세그먼트 없음). KPST 실서버 계약(`{원본stem}-mask{ext}`) 정합. ⚠근거 드리프트: `deid_sim.py:162-165,193-207` → 실제 `MASK_SUFFIX`/`mask_name_from` 는 `225,255-269` |
| TC-AIMOCK-05 | PASS | [정적] | `deid_sim.produce_deid_outputs` 진입부: `if not output_base:` → `_base_unset_warned` 1회 WARN 후 `return ProductionOutcome(written=[])` (실패 아님 = 성공 no-op, 응답 200 유지). 테스트 커버: `tests/test_deid_output.py:354 test_output_base_미설정이면_파일이_생기지않는다_failclosed`. 런타임에 `MOCK_OUTPUT_BASE` 가 설정돼 있어 실동작 재현은 불가(설정 변경 = 환경 개조라 미수행). ⚠근거 드리프트: `deid_sim.py:409-444` → 실제 `1704-1714` |
| TC-AIMOCK-06 | PASS | [실동작] | `export_path=/tmp/qa-esc/`(output_base 밖) 요청 → 응답 `200 {"result":"success","prj_id":5}` 유지, 컨테이너 내 `/tmp/qa-esc` **미생성**, 로그 `[MOCK][KPST] deid output dir rejected export_path=/tmp/qa-esc/ output_base=/app/storage/raw,/app/storage/deidentified` + `production failed reason=OUTPUT_DIR_REJECTED`, 이후 `procState=99`/`prjState=5` 로 노출(관측 불가하게 실패하지 않음 — 케이스 요구 충족 + 더 강하게 드러냄) |
| TC-AIMOCK-07 | **PARTIAL** | [실동작] | 보안 의도(임의 파일 미열람·디스크 고갈 차단)는 **완전 충족**이나 **기대결과 문구가 stale**. `input_path=/etc/`, `files=["hosts.mp4"]` → 응답 200, 이후 `procState=99`, `export_path` 디렉터리는 **완전히 비어 있음(placeholder 파일 0건)**. 로그: `deid source rejected(boundary) — 허용 입력 루트 밖 요청이라 원본을 읽지 않고 산출 실패로 종결` + `placeholder 로 최종 이름을 선점하지 않는다`. 코드 주석(`deid_sim.py:210-217`, `config.py:74-85`, `deid.py:239-242`)이 "placeholder 안 = 폐기(CWE-345 위장 산출물)"를 명시. → **G-ISSUE-81** |
| TC-AIMOCK-08 | PASS | [실동작] | 기존 산출물(`clip-9101-mask.mp4`, size 50854 / mtime 1785662040) 있는 export_path 로 새 프로젝트 재실행 → **size·mtime 완전 동일**, 로그 `[MOCK][KPST] deid output exists — skip(no-overwrite) file=clip-9101-mask.mp4`, `procState=2`(멱등 완료). ⚠근거 드리프트: `deid_sim.py:328-346,368-373` → 실제 `_copy_no_overwrite` 439~ / `_write_one_output` 1523~ |

### G-7 부수 관찰 (케이스 밖)
- 산출 완료 후 `export_path` 하위에 빈 `.mock-tmp/`(0700) 디렉터리가 잔존한다. BE 폴백 스캔이 디렉터리를 파일로 오인하지 않는 한 무해하나 정리되지 않는다 → **G-ISSUE-88**.
- `GET /manual_deid_info` 가 KPST 명세 §22.4 의 "프로젝트 상태(state)=3 = 수동 비식별화 대상" 조건을 반영하지 않고 `db_save==1` 만으로 필터한다 → **G-ISSUE-86**.
- `/upload`(§22.3.2)·`/download`(§22.3.12) 미구현은 **의도된 스코프 제외**(공유 마운트 모델, `22-deid-solution-api.md` 명시) — 갭 아님.

---

## G-8. VLM 벤더 목업 (4건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-09 | PASS | [실동작] | `POST /v1/videovlm/verify` → `200 {"request_id":"qa-g5-001","status":"accepted"}`(벤더 v2.0.1 §2.1 "200 OK / {request_id,status:accepted}" 정합). 2초(`callback_delay_seconds`) 후 로그 `[MOCK][VLM] callback failed url=http://localhost:9999/cb type=ConnectError` → **콜백이 실제로 지연 발사**됨을 확인. `describe` 도 동일(`qa-g5-003`). 성공 페이로드 구조는 벤더 규격과 정합(verify=`results{accuracy,description}` 객체 / describe=`results[]{start_sec,end_sec,description}` 배열, `vlm_sim.build_verify_callback:261-267` / `build_describe_callback:270-279`) |
| TC-AIMOCK-10 | PASS | [실동작] | `callback_url=http://evil.example.com/cb` → `400 {"error_code":"VALIDATION_ERROR","message":"callback_url host is not allowed"}` + 로그 `callback_url rejected(not allowed host) host=evil.example.com`, outbound 로그 **0건**. 케이스 단언은 충족. 단 **호스트만** 검사하고 포트·경로·자기참조는 무제한 → 별건 **G-ISSUE-82** |
| TC-AIMOCK-11 | PASS | [실동작] | `request_id="fail-x"` → 동기 응답 **`200`** `{"request_id":"fail-x","status":"accepted"}`, 콜백만 failed. failed 페이로드는 `{"request_id","status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}`(`vlm_sim.py:329-335`)로 **벤더 v2.0.1 실패 콜백 규격(중첩 `error{code,message}`)과 정합** — 카탈로그의 "error_code/message" 표현이 평면 필드를 뜻한다면 오해 소지. ⚠카탈로그 기대결과 "동기 **202** accepted" 는 **오기**(벤더 규격·실동작 모두 200) → **G-ISSUE-83** |
| TC-AIMOCK-12 | **FAIL** | [실동작] | `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`. **벤더 v2.0.1 §2.7 규격은 `{"status":"ready"}`(요청 처리 가능) / `{"status":"busy"}`(작업 진행 중)** 이며 `service` 필드는 규격에 없다. 목업이 벤더 계약을 재현하지 않고 자체 형식을 반환하며, 카탈로그 기대결과가 그 드리프트를 그대로 정본화하고 있다 → **G-ISSUE-84**. ⚠근거 드리프트: `vlm.py:228-231` → 실제 `241-244` |

### G-8 부수 관찰
- VLM 요청 스키마가 벤더 규격의 **조건부 필수**를 검증하지 않는다(실동작 확인): `frame_policy.framerate` 누락(규격 Required=Y) → 200 / `source_type=path` 인데 `media.path` 누락(규격 필수) → 200 / `mode=frame_selected` 인데 `selected_frames` 누락(규격 필수) → 200. `selected_frames` 9개 → 422 는 정상 → **G-ISSUE-85**

---

## G-9. 생성형 AI(genai) 증강 벤더 목업 (21건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-13 | PASS | [실동작] | `POST /api/genai/jobs` → `202` `{"request_id":"qa-g5-j1","job_id":"8aff3d68…","status":"RECEIVED","received_at":"2026-08-02T09:16:51+00:00"}` — job_id 는 **목이 발급**(`uuid4().hex`, `augment.py:277`), 4필드 모두 존재 |
| TC-AIMOCK-14 | PASS | [실동작] | `generation_mode=I2I` + `input_files=[]` → `400 {"code":"REQUIRED_FIELD_MISSING","message":"input_files 는 I2I·I2V 에서 1건 이상 필요합니다"}` |
| TC-AIMOCK-15 | PASS | [실동작] | `generation_mode=T2I` + input_files 미지정 → `202 RECEIVED` (위 j1) |
| TC-AIMOCK-16 | PASS | [실동작] | `sequence=[1,1]` → `400 {"code":"INVALID_PARAMETER","message":"input_files[].sequence 는 중복될 수 없습니다"}` |
| TC-AIMOCK-17 | PASS | [실동작] | `callback_url=http://klid-backend:8080/api/v1/genai/callback` 지정 job 진행 시 mock 로그에 **4회 webhook 발사** 관측(09:17:44 → 09:17:46/47 → 09:17:49 → 09:17:52), 각 회 최대 2회 재시도(`genai_webhook_max_attempts=2`) 후 `give-up`. 중간 GET 상태로 `progress=90/current_step=POSTPROCESS`(=90→POSTPROCESS) 확인, 완료 payload 에 `results` 포함(`build_webhook_payload:649-650` — `if job.results`). `PROGRESS_STEPS=((10,PREPROCESS),(50,INFERENCE),(90,POSTPROCESS))` `genai_sim.py:93-97`. ⚠근거 드리프트: `81-86,480-495,583-648` → 실제 `93-97,761-826,639-654` |
| TC-AIMOCK-18 | PASS | [실동작] | RECEIVED/RUNNING 중 `GET …/results` → `409 {"code":"STATE_CONFLICT","message":"결과는 SUCCEEDED 상태에서만 조회할 수 있습니다"}`. SUCCEEDED 후 동일 호출 → 200 |
| TC-AIMOCK-19 | PASS | [실동작] | I2I(원본 `/app/storage/raw/seed/clip-9101.mp4`) 결과 `output_file_path=/app/genai-out/genai/bade5ef…/001_clip-9101_genai.png`. 컨테이너 내 파일 **실존**(20,590B = 원본과 동일 크기 → 복사됨), `sha256sum` = `52c61f9e…42a6` = 응답 `checksum` **완전 일치**. T2I(원본 없음) 는 21B placeholder + 체크섬 일치(`9416c143…a4f0`) |
| TC-AIMOCK-20 | PASS | [실동작] | RUNNING 중 cancel → `200 {"status":"CANCELED","canceled_at":…}`. SUCCEEDED job cancel → `409 STATE_CONFLICT`. `requested_by` 누락 → `400 REQUIRED_FIELD_MISSING` |
| TC-AIMOCK-21 | PASS | [실동작] | `Idempotency-Key: qa-g5-idem-1` 로 **본문이 다른**(request_id/mode 상이) 재요청 → 동일 `job_id=bade5ef6d944473699b6c557b2aaad04` + 저장된 request_id echo, 로그 `idempotent replay key=qa-g5-idem-1`. 65자 키 → `400 {"code":"INVALID_PARAMETER","message":"Idempotency-Key 는 64자 이하여야 합니다"}` |
| TC-AIMOCK-22 | PASS | [정적] | `genai_sim.build_results:555-559` — `if not output_base: raise JobExecutionError(RESULT_SAVE_FAILED)` → `_process_job:830-831` catch → `_fail_job` → FAILED 전이 + `emit_webhook`(payload 에 `error_code`/`error_message` 포함, `639-654`). 테스트: `tests/test_genai_webhook.py:360 test_HIGH3_출력base_미설정이면_FAILED_RESULT_SAVE_FAILED`. 런타임 env 가 설정돼 있어 실동작 재현 불가(설정 개조 미수행). ⚠근거 드리프트: `396-400`→`555-559` |
| TC-AIMOCK-23 | PASS | [실동작] | 상대경로 `../../etc/passwd` → `400 INVALID_PARAMETER`, 루트 밖 절대경로 `/etc/passwd` → `400 INVALID_PARAMETER`(둘 다 `"허용된 루트의 절대경로여야 합니다"` + 로그 `input path rejected(path guard)`). base 미설정 fail-closed 는 `resolve_input_path:395-396` `if not input_base: return None`(정적) + 테스트 `test_genai_security_hardening.py:303 F6`. ⚠근거 드리프트: `223-250`→`382-409` |
| TC-AIMOCK-24 | PASS | [실동작] | 허용목록 밖 `http://evil.example.com/cb` → `400 INVALID_PARAMETER`, **자기참조** `http://localhost:9400/api/genai/jobs` → `400`(=`_is_self_target` 동작 확인). 두 건 모두 로그 `callback_url rejected(url guard)` + outbound 0건. host:port 분리·경로접두사는 `is_allowed_url:328-376` 정적 + 테스트 F2(`159-241`). ⚠근거 드리프트: `161-220`→`328-376` |
| TC-AIMOCK-25 | PASS | [실동작] | 본문 1,200,159B(>1MiB) → **`413` `{"code":"GA-MEDIA-001","message":"요청 본문 크기가 허용 한도를 초과했습니다"}`**. 본문 70,160B(<1MiB) + prompt 70KB(>64KiB) → **`400` `{"code":"INVALID_METADATA","message":"prompt 크기가 허용 한도를 초과했습니다"}`. 두 상한이 별도 축으로 동작 확인 |
| TC-AIMOCK-26 | PASS | [정적] | 처리 시점 재검증 3중: ①`_revalidate_source:620-635` 가 `resolve_input_path` 를 **다시** 호출 + `is_file()` 확인 → 실패 시 `MODEL_EXECUTION_FAILED` ②`_open_source_nofollow:447-467` `os.open(O_RDONLY|O_NOFOLLOW)` + **fd 기준 `fstat`** 로 정규파일/크기 재확인 ③`_write_output:517-520` 실패 시 부분 산출물 `unlink`. 테스트: `test_genai_security_hardening.py:98 F1_접수후_입력파일이_base밖_심볼릭링크로_바뀌면_유출되지_않고_FAILED`, `:129`, `:329 F7`. ⚠근거 드리프트: `288-361,461-476`→`447-467,620-635` |
| TC-AIMOCK-27 | PASS | [실동작] | RUNNING 중 cancel 확정 후 `ls /app/genai-out/genai/271b870b…` → **`No such file or directory`**(산출물·디렉터리 모두 없음). 코드: `discard_results:523-536`(파일 unlink + 빈 부모 rmdir), 호출 지점 `_process_job:806-808`(CancelledError) / `:820-826`(종결 선점). 테스트 `test_genai_security_hardening.py:409 F11`. ⚠근거 드리프트: `364-378`→`523-536` |
| TC-AIMOCK-28 | PASS | [정적] | `augment.py:178-184 _check_event_type` — `allowed = settings.genai_event_types_set()`, `if allowed and evnt_type not in allowed: 400 UNSUPPORTED_EVENT_TYPE`. **근거 라인 정확**. 런타임 `MOCK_GENAI_EVENT_TYPES` 미설정 → 미강제(설계대로, 실동작으로 `evnt_type=WINTER` 202 확인). 테스트 `test_genai_jobs.py:397` |
| TC-AIMOCK-29 | PASS | [정적] | `config.py:203-210 genai_max_jobs(default 1000)` + `state.py:495-512 GenAiJobStore` — "보관 작업 수가 max_jobs 를 넘으면 가장 오래된 작업부터 만료(FIFO)". 테스트 `test_genai_security_hardening.py:270 F3_잡_수_상한을_넘으면_오래된_작업부터_만료된다`. ⚠근거 드리프트: `config.py:179-186` → 실제 `203-210`(179-186 은 `genai_webhook_max_attempts`) |
| TC-AIMOCK-30 | PASS | [실동작] | `GET /api/genai/_mock/jobs` → jobs[] + `active_tasks` + `queue` 반환(BE 발급 `AUG-*` job 다수 관측), `POST /_mock/reset` → `{"result":"success"}`, `POST /_mock/jobs/{id}/status-sync` → 200. 세 EP 모두 `/api/genai/_mock/` 하위로 명세 계약 경로와 분리 |
| TC-AIMOCK-31 | PASS | [실동작] | `MOCK_GENAI_STATUS_SYNC_URL` 미설정 상태에서 `POST /_mock/jobs/{id}/status-sync` → `200 {"job_id":…,"sent":false,"target":null,"reason":"MOCK_GENAI_STATUS_SYNC_URL 미설정 또는 허용되지 않는 대상 — status-sync 비활성"}`. **자동 발신 로그 0건**(전체 세션 동안 status-sync outbound 없음). ⚠근거 드리프트: `563-579`→`722-740` |
| TC-AIMOCK-32 | PASS | [실동작] | 세션 전체에서 **어떤 인증 헤더도 없이** 모든 genai EP 가 정상 응답(202/200/400/409/404). `augment.py:20` + `schemas/genai.py:8-9` 가 "인증은 이번 스코프에서 의도적 미구현"을 명시 — 설계 의도대로 |
| TC-AIMOCK-33 | PASS | [실동작] | `docker logs klid-mock-server` 에 방어 WARN 이 **포맷 갖춰 stdout 노출**됨: `[MOCK][KPST] deid output dir rejected …`, `[MOCK][KPST] deid source rejected(boundary) …`, `[MOCK][VLM] callback_url rejected(not allowed host) …`, `[MOCK][GENAI] callback_url rejected(url guard) …`, `[MOCK][GENAI] webhook give-up …`. `main.py:32-48 configure_logging()` 근거 라인 **정확** |

---

## 이슈

### [G-ISSUE-81] TC-AIMOCK-07 — 기대결과가 폐기된 구 정책(18바이트 placeholder)을 그대로 정본화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과는 현재 코드의 확정 정책과 일치해야 한다. 불일치하면 다음 회차 검증자가 "placeholder 가 없다"를 **결함(FAIL)** 으로 오판하거나, 반대로 정책을 되돌리는 수정을 유발한다(이 저장소는 실제로 placeholder 를 만들었다가 되돌린 이력이 있다).
- **현재 동작(이슈 내용)**: 카탈로그는 `기대결과 = target 파일이 원본 복사가 아닌 18바이트 placeholder` 라고 적었으나, 코드는 **placeholder 를 명시적으로 폐기**했다.
  ```
  # mock-server/app/services/deid_sim.py:210-217
  # ★ #3 — <b>placeholder 산출물은 폐기됐다</b>. 원본을 읽지 못하는 경우(...) 구 구현은 18바이트
  #   스텁을 <b>최종 경로</b>에 쓰고 완료(procState=2)로 보고했다. ... 지금은 <b>산출 실패
  #   (procState=99)</b> 로 종결한다 ... 위장 산출물이 되기 때문이다(CWE-345).
  ```
  실동작(2026-08-02 09:15): `input_path=/etc/` 요청 → export 디렉터리 **완전히 빈 상태**, `procState=99`, 로그 `placeholder 로 최종 이름을 선점하지 않는다`.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/project -H 'Content-Type: application/json' \
    -d '{"project_name":"chk","creator":"qa","export_path":"/app/storage/deidentified/videos/chk/","input_path":"/etc/","files":["hosts.mp4"]}'
  sleep 12
  curl -s -X GET http://localhost:9400/retrieve_progress -H 'Content-Type: application/json' \
    -d '{"reqUserId":"qa","prjName":"chk"}'      # procState=99
  docker exec klid-mock-server ls -la /app/storage/deidentified/videos/chk   # 빈 디렉터리
  ```
- **영향**: 기능/보안 영향 없음(현 동작이 더 안전). **카탈로그 정합성 결함** — 회차 간 판정 재현성을 깨뜨린다.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` TC-AIMOCK-07 의 케이스명·기대결과를 "허용 루트 밖 input_path 는 원본을 읽지 않고 **산출물을 만들지 않은 채 procState=99 로 실패 종결**(placeholder 로 최종 이름 선점 금지 — CWE-345)" 로 정정. 근거 `file:line` 도 `deid_sim.py:210-217, 334-353, 1704~` 로 갱신. **코드는 그대로 둔다.**

### [G-ISSUE-82] TC-AIMOCK-10 — VLM 콜백 SSRF 가드가 genai 가드보다 약해 목 서버 자기참조·임의 포트 POST 가 성립(실증)
- **심각도**: MEDIUM (목 서버가 루프백 전용 발행이라 원격 노출은 없음. 노출 시 HIGH)
- **기대 동작(기대효과)**: 목 서버는 무인증이므로 요청자 지정 `callback_url` 로의 서버측 outbound 는 **호스트뿐 아니라 포트·경로·자기참조**까지 좁혀야 한다. 같은 서버의 genai 가드(`genai_sim.is_allowed_url`)는 이미 `host:port` allowlist + 경로 접두사 + **자기참조 차단**을 구현하고 있으므로, VLM 만 약한 것은 방어 비대칭이다.
- **현재 동작(이슈 내용)**: VLM 은 `url_guard.is_allowed_callback` 만 사용하고 **호스트 완전일치만** 검사한다.
  ```python
  # mock-server/app/services/url_guard.py:7-9 (docstring)
  # 정책: 허용 호스트 목록(...)에 정확히 일치하는 호스트만 수락하고 ...
  # 포트/경로는 제한하지 않는다(콜백 수신 포트가 환경마다 다름).
  # :28-33
  def is_allowed_callback(url, allowed_hosts) -> bool:
      host = callback_host(url)
      if host is None: return False
      return host in {h.lower() for h in allowed_hosts}
  ```
  기본 allowlist 에 `localhost,127.0.0.1` 이 들어 있어 **목 서버 자신**이 항상 허용된다. 실증(2026-08-02 09:19:30~32, 컨테이너 로그):
  ```
  [MOCK][VLM] verify accepted request_id=qa-g5-ssrf1 callback_url=http://127.0.0.1:9400/api/genai/_mock/reset
  [MOCK][GENAI] store reset
  INFO: 127.0.0.1:52944 - "POST /api/genai/_mock/reset HTTP/1.1" 200 OK
  [MOCK][VLM] callback sent url=http://127.0.0.1:9400/api/genai/_mock/reset status=200
  ```
  → VLM 엔드포인트 1회 호출만으로 **다른 벤더(genai)의 작업 저장소가 전량 삭제**됐다. 또한 `http://klid-backend:5005/actuator` 처럼 **backend 컨테이너의 임의 포트**로도 POST 가 발사됐다(ConnectError 로그 = 실제 커넥션 시도).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/v1/videovlm/verify -H 'Content-Type: application/json' \
    -d '{"request_id":"x","event_type":"fall","media":{"type":"video","source_type":"path","path":"/x.mp4"},
         "callback_url":"http://127.0.0.1:9400/api/genai/_mock/reset"}'
  # 200 accepted → 2초 후 docker logs 에 "[MOCK][GENAI] store reset"
  ```
- **영향**: 보안 **CWE-918(SSRF)** + **CWE-352 유사(무인증 상태변경 트리거)**. 컨테이너 네트워크 안에서 임의 호스트:포트 POST 발판 + 목 서버 자체 상태 파괴. 검증 관점에서는 다른 회차의 genai 관측 이력이 조용히 사라져 **검증 결과 신뢰성**을 훼손한다.
- **수정 방향(제안)**: `app/routers/vlm.py:_assert_allowed_callback` 이 `url_guard` 대신 **genai 와 같은 판정기**(`genai_sim.is_allowed_url` 을 벤더 중립 모듈로 승격하거나 `url_guard` 에 `host:port` + 경로 접두사 + `_is_self_target` 을 이식)를 쓰도록 통합. 최소한 `_mock/*` 보조 EP 는 콜백 대상에서 무조건 배제. 아울러 `MOCK_CALLBACK_ALLOWED_HOSTS` 기본값에서 `localhost,127.0.0.1` 의 필요성을 재검토.

### [G-ISSUE-83] TC-AIMOCK-11 — 기대결과의 "동기 202" 는 오기(벤더 규격·실동작 모두 200)
- **심각도**: LOW
- **기대 동작(기대효과)**: VLM verify/describe 의 동기 응답 코드는 벤더 v2.0.1 §2.1/§2.5 가 **200 OK** 로 못박고 있고 BE `VlmClient` 도 그 전제로 동작한다. 카탈로그가 202 라고 적으면 다음 회차가 "202 가 아니니 FAIL" 로 오판할 수 있다.
- **현재 동작(이슈 내용)**: 카탈로그 TC-AIMOCK-11 기대결과 = `동기 202 accepted, 콜백은 status=failed+error_code/message`. 실동작은 **200**:
  ```
  $ curl -w "HTTP=%{http_code}" -X POST .../v1/videovlm/verify -d '{"request_id":"fail-x",...}'
  {"request_id":"fail-x","status":"accepted"}  HTTP=200
  ```
  같은 절의 TC-AIMOCK-09 는 "동기 200" 으로 적혀 있어 **절 내부에서도 자기모순**이다. 또 실패 콜백은 평면 `error_code`/`error_message` 가 아니라 벤더 규격대로 **중첩 `error{code,message}`** 다(`vlm_sim.py:329-335`).
- **재현/확인 경로**: 위 curl 1줄.
- **영향**: 카탈로그 정합성. 202 를 정본으로 착각해 목업을 202 로 "고치면" **벤더 계약을 깨는 회귀**가 된다.
- **수정 방향(제안)**: TC-AIMOCK-11 기대결과를 `동기 200 accepted, 콜백은 {"status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}` 로 정정.

### [G-ISSUE-84] TC-AIMOCK-12 — `/v1/videovlm/status` 응답이 IntelliVIX v2.0.1 규격(`ready`/`busy`)과 불일치하고 카탈로그가 그 드리프트를 정본화
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목업의 존재 이유는 **벤더 계약을 대신 재현**하는 것이다. 벤더 원문(`docs/video_vlm_api_ v2.0.1.docx` §2.7 "서버 상태 체크")은 다음을 규정한다.
  | 상황 | 응답 |
  |---|---|
  | 요청 처리 가능 | `200 {"status":"ready"}` |
  | 작업 진행 중 | `200 {"status":"busy"}` |
  `service` 필드는 규격에 없다. 즉 목업은 `ready`/`busy` 두 값을 재현해야 소비 측(향후 헬스 인디케이터·서킷 판정)이 로컬에서 실제로 검증된다.
- **현재 동작(이슈 내용)**:
  ```python
  # mock-server/app/routers/vlm.py:241-244
  @router.get("/v1/videovlm/status")
  async def status_check() -> dict[str, str]:
      return {"status": "ok", "service": "videovlm"}
  ```
  실동작: `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`. `ok` 는 규격 어휘가 아니며 `busy` 상태는 아예 재현 불가.
  카탈로그 TC-AIMOCK-12 기대결과가 `200 {status:ok, service:videovlm}` 으로 **목업 구현을 그대로 베껴** 계약 위반을 통과시키고 있다(확증편향 사례).
- **재현/확인 경로**: `curl -s http://localhost:9400/v1/videovlm/status` → `{"status":"ok","service":"videovlm"}` vs 벤더 문서 §2.7.
- **영향**: 기능 영향 **현재 없음**(BE 전수 grep 결과 `/v1/videovlm/status` 호출부 0건 — `VlmClient` 는 `DESCRIBE_PATH="/v1/videovlm/describe"` 만 사용). 그러나 ①"목업 = 계약 정본" 이라는 전제가 이 EP 에서 깨져 있고 ②향후 VLM 헬스 인디케이터를 `"ready"` 기준으로 붙이면 **로컬에선 전부 DOWN, 실벤더에선 UP** 이 되어 로컬 검증이 무의미해진다. mock-server `/health`(`{"status":"ok"}`)와 값이 같아 두 개념이 혼동되기도 쉽다.
- **수정 방향(제안)**: `vlm.py:status_check` 를 규격대로 `{"status": "ready"}` 로 바꾸고, 진행 중인 콜백/probe 태스크가 있을 때 `{"status":"busy"}` 를 반환하도록 선택적으로 확장(`vlm_sim.describe_probe_inflight()` 로 판정 가능). `service` 필드는 제거하거나 명시적으로 "목 전용 확장"임을 주석화. 카탈로그 TC-AIMOCK-12 기대결과도 `ready`/`busy` 로 정정. **본 회차에서는 수정하지 않음.**

### [G-ISSUE-85] G-8 — VLM 요청 스키마가 벤더 규격의 조건부 필수 필드를 검증하지 않아 계약 회귀를 못 잡는다
- **심각도**: LOW
- **기대 동작(기대효과)**: 목업이 벤더보다 관대하면 BE 가 규격 위반 요청을 보내도 로컬에서 200 이 나고, 실벤더 전환 시점에야 400/422 로 드러난다. 벤더 §3.1/§3.2 의 조건부 필수는 목업도 강제해야 한다.
  | 필드 | 벤더 규격 |
  |---|---|
  | `media.path` | `source_type=path` 인 경우 **필수** |
  | `frame_policy.framerate` | **Required = Y** |
  | `frame_policy.selected_frames` | `mode=frame_selected` 인 경우 **필수**(최대 8) |
- **현재 동작(이슈 내용)**: 셋 다 무조건 Optional 이라 누락돼도 접수된다.
  ```python
  # mock-server/app/schemas/vlm.py:42-46
  framerate: Optional[int] = Field(default=None, ge=1, le=240, ...)
  selected_frames: Optional[list[int]] = Field(default=None, max_length=8, ...)
  # :56
  path: Optional[str] = Field(default=None, ...)
  ```
  실동작(3건 모두 `200 accepted`): ①`frame_policy={"mode":"frame_interval"}`(framerate 없음) ②`media={"type":"video","source_type":"path"}`(path 없음) ③`frame_policy={"mode":"frame_selected","framerate":25}`(selected_frames 없음).
- **재현/확인 경로**:
  ```bash
  curl -s -w " %{http_code}\n" -X POST http://localhost:9400/v1/videovlm/describe -H 'Content-Type: application/json' \
    -d '{"request_id":"lax","media":{"type":"video","source_type":"path"},"callback_url":"http://localhost:1/cb"}'
  # {"request_id":"lax","status":"accepted"} 200   ← 벤더 규격상 path 필수
  ```
- **영향**: 계약 검증 공백. 현재 BE(`VlmTimeseriesRequest.ofFrameInterval`)는 항상 `framerate`·`path` 를 채우므로 즉시 장애는 없으나, BE 리팩터가 이를 빠뜨려도 **로컬·CI 어디서도 잡히지 않는다**.
- **수정 방향(제안)**: `schemas/vlm.py` 의 `Media`/`FramePolicy` 에 pydantic `model_validator(mode="after")` 를 추가해 위 3개 조건부 필수를 400(구조 오류) 으로 거부. G-8 에 회귀 케이스 3건 신설.

### [G-ISSUE-86] G-7 — `GET /manual_deid_info` 가 KPST 규격의 "프로젝트 상태=3(수동 대상)" 조건을 반영하지 않는다
- **심각도**: LOW
- **기대 동작(기대효과)**: KPST 명세(`docs/v2-wiki/22-deid-solution-api.md` §22.3.8 / §22.4)는 이 EP 를 "`db_save=1` **이며 프로젝트 상태가 수동 대상(state=3)** 인 데이터셋"으로 정의한다.
- **현재 동작(이슈 내용)**:
  ```python
  # mock-server/app/routers/deid.py:543-545
  def _manual_targets() -> list[Project]:
      """수동 비식별화 대상(db_save=1) 프로젝트 목록."""
      return [p for p in get_store().list_projects() if p.db_save == 1]
  ```
  실동작: `db_save=1` 로 만든 프로젝트가 **진행 중이든 완료든** 전부 반환된다(09:14 실측, 완료 직후 조회 시 1건 반환).
  더 나아가 목의 상태코드 정의(`deid_sim.py:56-60`)는 `prjState 3 = 완료` 인데 KPST 명세 §22.4 는 `프로젝트 상태 3 = 수동 비식별화 대상` 이라 **같은 값에 두 의미**가 붙어 있다.
- **재현/확인 경로**: `curl -s http://localhost:9400/manual_deid_info` — 완료(`prjState=3`)·진행중(`prjState=2`) 프로젝트가 구분 없이 나온다.
- **영향**: 기능 영향 없음(`22-deid-solution-api.md` §22.6 이 수동 비식별 연계를 "⏳ 후속·미구현"으로 명시, BE 호출부 0건). 다만 향후 수동 비식별 워크플로를 붙일 때 목업이 필터를 재현하지 않아 로컬 검증이 헛돌 수 있고, `prjState=3` 의미 충돌은 오독을 부른다.
- **수정 방향(제안)**: `_manual_targets` 에 `prj_state_for(...) == 수동대상` 조건 추가 또는 목이 수동 대상 상태를 별도 축으로 모델링. 최소한 `deid_sim.py:56` 의 상태코드 주석에 "KPST 명세 §22.4 의 '수동 대상 state=3' 과 값이 겹친다"는 경고를 남긴다.

### [G-ISSUE-87] G-7/G-8/G-9 — 근거 `file:line` 대량 드리프트(33건 중 15건)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 `file:line` 은 검증자가 곧바로 해당 코드로 점프하는 진입점이다. 어긋나면 매 회차마다 Grep 재탐색 비용이 들고, 최악에는 **엉뚱한 코드를 근거로 PASS** 를 찍는다(실제로 TC-AIMOCK-03 이 가리키는 `deid_sim.py:210-217` 에는 지금 placeholder **폐기** 주석이 있어, 그대로 읽으면 정반대 결론에 도달한다).
- **현재 동작(이슈 내용)**: 확인된 드리프트 15건.
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 01 | `deid.py:101-105` | `deid.py:116-119` |
  | 02 | `main.py:94-96` | `main.py:103-105` |
  | 03 | `deid.py:76-98` / `deid_sim.py:210-217` | `deid.py:86-112` / `deid_sim.py:272-279` |
  | 04 | `deid_sim.py:162-165,193-207` | `deid_sim.py:225,255-269` |
  | 05 | `deid_sim.py:409-444` | `deid_sim.py:1704-1714` |
  | 06 | `deid_sim.py:248-276,446-453` | `deid_sim.py:1716-1725` / `path_policy.resolve_output_dir` |
  | 07 | `deid_sim.py:279-326,359-406` | `deid_sim.py:334-353, 210-217` |
  | 08 | `deid_sim.py:328-346,368-373` | `deid_sim.py:439~, 1523~` |
  | 12 | `vlm.py:228-231` | `vlm.py:241-244` |
  | 17 | `genai_sim.py:81-86,480-495,583-648` | `genai_sim.py:93-97,761-826,639-654` |
  | 19 | `genai_sim.py:380-458` | `genai_sim.py:539-617` |
  | 22 | `genai_sim.py:396-400` | `genai_sim.py:555-559` |
  | 23 | `genai_sim.py:223-250` | `genai_sim.py:382-409` |
  | 24 | `genai_sim.py:161-220` | `genai_sim.py:328-376` |
  | 26 | `genai_sim.py:288-361,461-476` | `genai_sim.py:447-467,620-635` |
  | 27 | `genai_sim.py:364-378` | `genai_sim.py:523-536` |
  | 29 | `config.py:179-186` | `config.py:203-210` |
  | 31 | `genai_sim.py:563-579` | `genai_sim.py:722-740` |
  라우터(`augment.py`)·`main.py:32-48`·`augment.py:178-184` 등은 정확하다. 드리프트는 **크게 성장한 서비스 모듈**(`deid_sim.py` 1,988줄 / `genai_sim.py` 860줄)에 집중.
- **재현/확인 경로**: `sed -n '210,217p' mock-server/app/services/deid_sim.py` — TC-AIMOCK-03 이 "fileName=원본 경로"의 근거로 가리키는 자리에 placeholder 폐기 주석이 있다.
- **영향**: 카탈로그 정합성 + 검증 효율. 회차마다 반복 비용.
- **수정 방향(제안)**: `G-ai-server.md` §G-7~G-9 의 근거 컬럼을 위 표대로 일괄 갱신. 장기적으로는 라인 번호 대신 **심볼명**(`deid_sim.produce_deid_outputs`, `genai_sim.build_results` 등)으로 표기해 드리프트를 원천 차단.

### [G-ISSUE-88] G-7 — 산출 성공 후 `.mock-tmp/` 빈 디렉터리가 export_path 에 잔존
- **심각도**: LOW
- **기대 동작(기대효과)**: BE 는 완료 후 export 디렉터리를 **폴백 스캔**해 단일 산출물을 회수한다(`22-deid-solution-api.md`). 목이 만드는 임시 작업 디렉터리는 산출 완료 시 정리돼 스캔 대상이 깨끗해야 한다.
- **현재 동작(이슈 내용)**: 정상 산출 후에도 디렉터리가 남는다.
  ```
  $ docker exec klid-mock-server ls -la /app/storage/deidentified/videos/qa-g5-p1/
  drwx------ 2 app app  4096 Aug  2 09:14 .mock-tmp     ← 빈 디렉터리, 잔존
  -rw-r--r-- 1 app app 50854 Aug  2 09:14 clip-9101-mask.mp4
  ```
  `sweep_temp_dir`/`sweep_orphan_temp_files`(`deid_sim.py:1174,1227`)는 **임시 파일**을 지우지만 디렉터리 자체는 제거하지 않고, 산출 진입 시점(`produce_deid_outputs`)에만 sweep 한다.
- **재현/확인 경로**: 위 `ls -la` (정상 완료 프로젝트의 export_path).
- **영향**: 현재 무해(BE 회수는 1차 `{stem}-mask{ext}` 경로가 맞아 폴백 스캔에 도달하지 않으며, 폴백 스캔도 파일만 대상). 다만 export 폴더에 계약 밖 엔트리가 남아 관제/데이터마트 관점의 산출 폴더 청결성을 해친다.
- **수정 방향(제안)**: `produce_deid_outputs` 종료부에서 `TEMP_DIR_NAME` 디렉터리가 비어 있으면 `rmdir`(genai `discard_results:534-536` 와 동일 패턴). 실패는 무시(격리).

---

## 부록 — 확증편향 반증 시도 기록

이 클러스터는 "목업 자체의 품질"을 감사하므로, **목업 구현을 기준으로 삼지 않고 벤더 계약 원문을 기준**으로 대조했다.

| 반증 시도 | 결과 |
|---|---|
| 벤더 원문(`video_vlm_api_ v2.0.1.docx`) 본문을 추출해 목업과 필드 단위 대조 | `/status` 응답 어휘 불일치 발견(**G-ISSUE-84**), 조건부 필수 3건 미검증 발견(**G-ISSUE-85**) |
| 카탈로그 기대결과가 코드를 그대로 베낀 것인지 확인 | TC-AIMOCK-12 가 정확히 그 사례 — 계약 위반을 통과시키고 있었음 |
| 목업이 "보안 가드가 있다"고 주장하는 지점을 실제로 뚫어봄 | VLM 콜백 가드를 **자기참조로 우회해 genai 저장소 파괴 성공**(**G-ISSUE-82**) |
| 카탈로그 기대결과가 현 코드 정책과 반대인 곳 탐색 | TC-AIMOCK-07 placeholder(**G-ISSUE-81**), TC-AIMOCK-11 202(**G-ISSUE-83**) |
| PASS 근거로 인용한 라인이 실제로 그 코드인지 확인 | 15건 드리프트(**G-ISSUE-87**) — 그중 TC-AIMOCK-03 은 정반대 결론을 유도할 위치 |
| self-fill 여부(목 미경유 자체 채움) | genai `_mock/jobs` 에 BE 발급 `AUG-*` job 실존, KPST 산출물이 실제 파일로 존재, VLM 콜백 outbound 로그 존재 → **self-fill 징후 없음** |
| 경계 위반이 조용히 성공으로 보고되는지 | export 밖 → `procState=99`, input 밖 → `procState=99`, genai 출력 base 미설정 → FAILED. **거짓 완료 없음** |

### 실동작으로 확인된 응답 코드 매트릭스 (재현용)

| 요청 | 실측 |
|---|---|
| `GET /` | 200 `Connect` (text/plain) |
| `GET /health` | 200 `{"status":"ok"}` |
| `POST /project` 정상 | 200 `{"result":"success","prj_id":N}` |
| `POST /project` 중복 이름 | 409 `CONFLICT` |
| `POST /project` files 없음(is_img=0) | 400 `VALIDATION_ERROR` |
| `GET /retrieve_progress` reqUserId 누락 | 400 · 필터 0개 400 · 미존재 404 |
| `POST /v1/videovlm/verify` 정상 | **200** `{request_id,status:"accepted"}` |
| `…/verify` event_type 오류 | 422 · media 누락 400 · callback 호스트 위반 400 |
| `GET /v1/videovlm/status` | 200 `{"status":"ok","service":"videovlm"}` (규격은 `ready`/`busy`) |
| `POST /api/genai/jobs` 정상 | **202** `{request_id,job_id,status:"RECEIVED",received_at}` |
| genai 필수누락/중복seq/경로위반/콜백위반/64자초과 | 400 (각각 `REQUIRED_FIELD_MISSING`/`INVALID_PARAMETER`) |
| genai 본문>1MiB | 413 `GA-MEDIA-001` · prompt>64KiB 400 `INVALID_METADATA` |
| genai results(비SUCCEEDED) / cancel(종결) | 409 `STATE_CONFLICT` |
| genai 미존재 job | 404 `JOB_NOT_FOUND` |
