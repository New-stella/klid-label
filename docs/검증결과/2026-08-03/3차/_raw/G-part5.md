# G-part5 — 외부 벤더 목업 계약 검증 (G-7 KPST · G-8 VLM · G-9 genai, 33건) — 3차 회차

- **대상**: `docs/test-cases/G-ai-server.md` §G-7(8) + §G-8(4) + §G-9(21) = **33건**(`TC-AIMOCK-01~33`)
- **검증일**: 2026-08-03(3차)
- **검증 스택**: `docker ps` → `klid-mock-server Up 3 hours (healthy)`(재빌드 후 컨테이너, `_raw/stack-bringup.md` §0 참조 — HEAD `e065da42` 코드와 일치)
- **검증 방식**: mock-server(`127.0.0.1:9400`) **실동작 호출 27건**(신규 프로젝트/잡 생성 포함) + 정적 대조 6건(env 변경 없이는 재현 불가한 fail-closed 케이스: 05, 22, 26, 28, 29 static + 09 콜백 발사는 로그로 실동작 확인)
- **회귀 전제 확인(Critical)**: `git log --oneline --since="2026-08-02" -- mock-server/` → **커밋 0건**. 즉 2차(2026-08-02) 검증 이후 mock-server 코드는 전혀 변경되지 않았다 — 이번 회차는 ①2차에서 PASS 였던 31건이 여전히 실동작으로 재현되는지 ②2차에서 지적한 결함(FAIL 1건 + 부수 이슈 6건)이 실제로 고쳐졌는지(코드 커밋 0건이므로 **고쳐지지 않았을 것으로 예상**되고, 실측으로 그 예상을 반증/확증)를 검증하는 데 집중했다.
- **런타임 실효 환경변수**(docker inspect 실측, 2차와 동일): `MOCK_OUTPUT_BASE=/app/storage/raw,/app/storage/deidentified` · `MOCK_INPUT_BASE=/app/storage` · `MOCK_CALLBACK_ALLOWED_HOSTS=klid-backend,localhost,127.0.0.1` · `MOCK_GENAI_INPUT_BASE=/app/storage` · `MOCK_GENAI_OUTPUT_BASE=/app/genai-out`
- ⚠ **환경 부작용 고지(2차와 동일 종류 재발)**: G-ISSUE-82(구 G-ISSUE-82) 반증용 VLM SSRF 콜백 테스트가 이번 회차에도 `POST /api/genai/_mock/reset` 을 트리거해 genai 인메모리 job 저장소가 재초기화됐다(17:34:52). 코드가 안 고쳐진 이상 검증할 때마다 재발한다 — 근본 수정 전까지는 **G/genai 관련 다른 클러스터 검증 시 이 부작용을 인지**할 것.

---

## 판정 요약

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| G-7 KPST | 8 | 8 | 0 | 0 | 0 | 0 | 0 |
| G-8 VLM | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| G-9 genai | 21 | 21 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **33** | **32** | **1** | **0** | **0** | **0** | **0** |

> 2차 대비: TC-AIMOCK-07(당시 PARTIAL, 카탈로그 stale 기대결과)은 **2차~3차 사이 카탈로그가 이미 정정**돼 이번 회차엔 PASS. TC-AIMOCK-12(FAIL)는 **코드가 안 고쳐져 이번에도 FAIL** — 단 카탈로그 기대결과 자체가 구현을 베낀 문제였던 부분은 이번 회차에 정정(아래 "카탈로그 정정" 참조).

---

## G-7. KPST 비식별 벤더 목업 (8건, 전부 실동작 재확인)

새 프로젝트(`qa3-g5-p1`, `qa3-g5-p1b`, `qa3-g5-esc`, `qa3-g5-badinput`)를 실제로 생성해 확인.

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-01 | PASS | [실동작] | `GET /` → `200` body `Connect`. 근거 `deid.py:119`(`return "Connect"`) 정확 |
| TC-AIMOCK-02 | PASS | [실동작] | `GET /health` → `200 {"status":"ok"}`. 근거 `main.py:104` 정확 |
| TC-AIMOCK-03 | PASS | [실동작] | `POST /project`(input_path=`/app/storage/raw/autolabel-test/`, files=`[af5780ea….mp4]`) → `retrieve_progress` 응답 `dsStatus[0].fileName="/app/storage/raw/autolabel-test/af5780ea….mp4"` = input_path+원본 basename(산출물명 아님). 정합 |
| TC-AIMOCK-04 | PASS | [실동작] | 완료 후 `af5780ea…-mask.mp4`(50,854B) 생성 확인 — `{stem}-mask{ext}`, 타임스탬프 세그먼트 없음. 근거 `deid_sim.py:223(MASK_SUFFIX),255(mask_name_from),269` 정확 |
| TC-AIMOCK-05 | PASS | [정적] | `deid_sim.py:1706-1714` — `if not output_base:` → WARN 1회 + `ProductionOutcome(written=[])`(실패 아닌 no-op). 런타임엔 base 설정돼 있어 실동작 재현 불가(환경 개조 미수행, VERIFY-PROMPT §10 예외 범위 밖) |
| TC-AIMOCK-06 | PASS | [실동작] | `export_path=/tmp/qa3-esc/`(output_base 밖) → 응답 `200 {"result":"success","prj_id":18}` 유지, 컨테이너 내 `/tmp/qa3-esc` **미생성**, 로그 `deid output dir rejected … reason=OUTPUT_DIR_REJECTED` |
| TC-AIMOCK-07 | **PASS**(2차 PARTIAL→해소) | [실동작] | `input_path=/etc/`, `files=["hosts.mp4"]` → `procState=99`, export 디렉터리 **완전히 빈 상태**(placeholder 파일 0건). 카탈로그 기대결과가 2차~3차 사이 "18바이트 placeholder" 구 정책에서 "산출 실패 종결"로 이미 정정돼 있어(현재 175행) 실동작과 일치 |
| TC-AIMOCK-08 | PASS | [실동작] | 기존 산출물(`af5780ea…-mask.mp4`, 50854B) 있는 동일 export_path 로 재실행(`prj_id:17`) → 파일 **크기 동일 유지**, 로그 `deid output exists — skip(no-overwrite)` |

### G-7 부수 관찰 — 코드 레벨, 2차 이후 미해결 재확인
- `.mock-tmp/` 빈 디렉터리가 산출 완료 후에도 export_path 에 잔존(TC-AIMOCK-04 확인 시 `qa3-g5-p1/` 하위에서도 동일 관측) → **G-ISSUE-85**(2차 G-ISSUE-88 승계, 코드 미변경)
- `GET /manual_deid_info` 가 `db_save==1` 만으로 필터, KPST §22.4 "프로젝트 상태=3(수동대상)" 축 미반영 — 코드 미변경으로 동일 → **G-ISSUE-84**(2차 G-ISSUE-86 승계)

---

## G-8. VLM 벤더 목업 (4건)

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-09 | PASS | [실동작] | `POST /v1/videovlm/verify`(callback_url=`http://klid-backend:8080/...`) → `200 {"request_id":...,"status":"accepted"}`, 2초 후 실제 콜백 발사(로그로 확인, `describe` 도 동일 패턴 — 4단계 webhook 관측은 G-9 TC-AIMOCK-17 절 참조) |
| TC-AIMOCK-10 | PASS | [실동작] | `callback_url=http://127.0.0.1:9400/api/genai/_mock/reset`(허용 호스트 `127.0.0.1`) → **접수는 성공**(허용 호스트라 400 케이스 자체는 별도 외부호스트로 확인 필요) — 재확인을 위해 임의 외부호스트(`evil.example.com`류)로도 별도 호출해 `400 VALIDATION_ERROR` 확인(2차와 동일 코드, `url_guard.is_allowed_callback` 완전일치 로직 불변). 케이스가 요구하는 "허용 밖 호스트 → 400" 자체는 PASS. ⚠단 "허용 호스트"의 정의가 호스트만이라 `127.0.0.1`(자기 자신)도 포함돼 버리는 심층 결함은 별도 — 아래 G-ISSUE-82 |
| TC-AIMOCK-11 | PASS(카탈로그 정정 후) | [실동작] | `request_id="fail-qa3"` → 동기 `200 {"request_id":"fail-qa3","status":"accepted"}`(202 아님) 확인. 카탈로그가 2차 G-ISSUE-83 지적(동기 202는 오기) 이후에도 **미수정 상태였음을 이번 회차에 재발견** → 본 회차에 직접 정정(아래 "카탈로그 정정" 참조), 정정 후 실동작과 일치해 PASS |
| TC-AIMOCK-12 | **FAIL**(2차 G-ISSUE-84 승계, 코드 미변경) | [실동작] | `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`. 벤더 IntelliVIX v2.0.1 §2.7 규격은 `{"status":"ready"}`/`{"status":"busy"}`(service 필드 없음). 카탈로그가 구현을 그대로 베껴 "기대결과"로 정본화하던 문제는 이번 회차에 벤더 규격 기준으로 정정(아래 참조)했으나, **코드 자체는 여전히 규격 위반** → FAIL 판정 유지 |

### G-8 부수 관찰 — 코드 레벨, 2차 이후 미해결 재확인
- VLM 요청 스키마가 벤더 §3.1/§3.2 조건부 필수(`media.path`(source_type=path 시 필수), `frame_policy.framerate`(Required=Y), `frame_policy.selected_frames`(mode=frame_selected 시 필수))를 강제하지 않음(`schemas/vlm.py` 전부 Optional, 코드 미변경 확인) → **G-ISSUE-83**(2차 G-ISSUE-85 승계)
- VLM 콜백 SSRF 가드가 genai 가드(호스트:포트+경로접두사+자기참조 차단, `genai_sim.is_allowed_url`)보다 약함(`url_guard.is_allowed_callback` 은 호스트 완전일치만) — 실제로 `callback_url=http://127.0.0.1:9400/api/genai/_mock/reset` 1회 호출로 **genai job 저장소가 재차 초기화**됨(재현: 17:34:52 로그 `[MOCK][GENAI] store reset`). genai 쪽은 동일 URL 을 자기참조로 **차단**함을 대조 확인(`{"code":"INVALID_PARAMETER",...,"목 자신은 금지"}`, 400) → 방어 비대칭 재확인 → **G-ISSUE-82**(2차 G-ISSUE-82 승계, 코드 미변경)

---

## G-9. 생성형 AI(genai) 증강 벤더 목업 (21건, 대부분 실동작 재확인)

⚠ 이번 회차 최초 시도 시 `request_channel`(필수 필드, `schemas/genai.py:114`)를 누락해 전건 400 `REQUIRED_FIELD_MISSING`을 받았다 — 이는 **카탈로그·코드 결함이 아니라 검증 테스트 페이로드 누락**이었음(테스트 픽스처 `test_genai_jobs.py:70` 대조로 확인·정정 후 재시도). 확증편향 반대 방향 오류(거짓 FAIL 자가발견)로 기록.

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-AIMOCK-13 | PASS | [실동작] | 정상 요청(T2I) → `202 {request_id,job_id,status:"RECEIVED",received_at}` |
| TC-AIMOCK-14 | PASS | [실동작] | `generation_mode=I2I`+`input_files=[]` → `400 REQUIRED_FIELD_MISSING`("input_files 는 I2I·I2V 에서 1건 이상 필요") |
| TC-AIMOCK-15 | PASS | [실동작] | `generation_mode=T2I`+input_files 미지정 → `202 RECEIVED` |
| TC-AIMOCK-16 | PASS | [실동작] | `sequence=[1,1]` 중복 → `400 INVALID_PARAMETER`("sequence 는 중복될 수 없습니다") |
| TC-AIMOCK-17 | PASS | [실동작] | `callback_url=http://klid-backend:8080/...` 지정 job 진행 시 로그에 **4단계 webhook 발사**(PREPROCESS→INFERENCE→POSTPROCESS→COMPLETED, 17:38:36~46) 확인, 각 최대 2회 재시도 후 give-up(실 backend 가 401/429 로 거부 — mock 발사 자체 확인이 목적이라 무관) |
| TC-AIMOCK-18 | PASS | [실동작] | RECEIVED 직후(비SUCCEEDED) `GET .../results` → `409 STATE_CONFLICT` |
| TC-AIMOCK-19 | PASS | [실동작] | T2I 완료(SUCCEEDED, 6초 소요) → `output_file_path` 실존 파일(21B placeholder, T2I=원본 없음) + `sha256sum` 완전 일치 |
| TC-AIMOCK-20 | PASS | [실동작] | RUNNING 중 cancel → `200 CANCELED`. SUCCEEDED 후 cancel → `409 STATE_CONFLICT`("이미 종료된 작업은 취소할 수 없습니다") |
| TC-AIMOCK-21 | PASS | [실동작] | `Idempotency-Key` 동일 키로 **본문이 다른**(request_id/evnt_type 상이) 2차 요청 → 완전 동일 `job_id` 반환(1차 요청 request_id 로 echo) |
| TC-AIMOCK-22 | PASS | [정적] | `genai_sim.py:555-559` `if not output_base: raise JobExecutionError(RESULT_SAVE_FAILED)` — 런타임 env 설정돼 있어 실동작 재현 불가 |
| TC-AIMOCK-23 | PASS | [실동작] | `file_path=/etc/passwd`(루트 밖 절대경로) → `400 INVALID_PARAMETER`("허용된 루트의 절대경로여야 합니다") |
| TC-AIMOCK-24 | PASS | [실동작] | `callback_url=http://localhost:9400/api/genai/_mock/reset`(자기참조) → `400 INVALID_PARAMETER`("목 자신은 금지") — VLM(TC-AIMOCK-10/G-ISSUE-82)과 달리 **자기참조를 실제로 차단**함을 대조 확인 |
| TC-AIMOCK-25 | PASS | [실동작] | 본문 1,300,224B(>1MiB) → `413 GA-MEDIA-001`("요청 본문 크기가 허용 한도를 초과") |
| TC-AIMOCK-26 | PASS | [정적] | `genai_sim.py:447-467(_open_source_nofollow, O_NOFOLLOW+fd기준 fstat), 620-635(_revalidate_source)` — TOCTOU 재검증 로직 확인, 테스트 `test_genai_security_hardening.py::F1` 커버. 실측 재현(심볼릭링크 교체)은 파괴적이라 미수행(2차와 동일 판단) |
| TC-AIMOCK-27 | PASS | [실동작] | RUNNING 중 cancel 후 `ls /app/genai-out/genai/{jobId}/` → **`No such file or directory`**(산출물·디렉터리 모두 정리됨) |
| TC-AIMOCK-28 | PASS | [정적+실동작] | `MOCK_GENAI_EVENT_TYPES` 미설정 상태 확인 → 미강제(실동작으로 `evnt_type=WINTER`/`NIGHT` 둘 다 202 확인, 설계대로) |
| TC-AIMOCK-29 | PASS | [정적] | `config.py:202-208(genai_max_jobs, default 1000)` — FIFO 만료 로직 정적 확인, `max_jobs` 낮춰 재현하려면 컨테이너 env 변경 필요(환경 개조 미수행) |
| TC-AIMOCK-30 | PASS | [실동작] | `GET /_mock/jobs` → jobs[]+active_tasks+queue 반환(BE 세션 이력 없음, 이번 회차 신규 잡만 관측 — 재빌드로 이력 리셋됨), `POST /_mock/jobs/{id}/status-sync` 정상 동작 |
| TC-AIMOCK-31 | PASS | [실동작] | `MOCK_GENAI_STATUS_SYNC_URL` 미설정 상태에서 실제 job 대상 status-sync 호출 → `{"sent":false,"target":null,"reason":"... 비활성"}` |
| TC-AIMOCK-32 | PASS | [실동작] | 이번 회차 모든 genai 호출(13건 이상) **무인증 헤더로 정상 처리**됨(202/400/409/413) |
| TC-AIMOCK-33 | PASS | [실동작] | `docker logs` 에 `[MOCK]` 접두 로그 다수 stdout 노출 확인(최근 60초간 10건) |

---

## 이슈

### [G-ISSUE-81] TC-AIMOCK-12 — `/v1/videovlm/status` 가 IntelliVIX v2.0.1 §2.7 규격과 불일치 (2차 G-ISSUE-84 승계, 코드 미수정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 벤더 v2.0.1 §2.7(서버 상태 체크)은 `200 {"status":"ready"}`(처리 가능) / `200 {"status":"busy"}`(진행 중)만 규정하며 `service` 필드는 없다. 목업은 이 계약을 재현해야 향후 VLM 헬스 인디케이터를 로컬에서 검증할 수 있다.
- **현재 동작(이슈 내용)**: 코드가 2026-08-02(2차) 이후 전혀 변경되지 않아 동일하게 규격을 위반한다.
  ```python
  # mock-server/app/routers/vlm.py:242-244
  async def status_check() -> dict[str, str]:
      return {"status": "ok", "service": "videovlm"}
  ```
  실동작(2026-08-03 17:xx): `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`.
- **재현/확인 경로**: `curl -s http://localhost:9400/v1/videovlm/status`
- **영향**: 기능 영향 현재 없음(BE `VlmClient` 가 이 EP 를 호출하지 않음, grep 확인). 다만 목업=계약 정본 전제가 이 EP 에서 깨져 있고, 카탈로그가 구현을 그대로 베껴 결함을 은폐하고 있었다(이번 회차에 카탈로그는 정정, 코드는 미정정 — 아래 "카탈로그 정정" 참조).
- **수정 방향(제안)**: `vlm.py:status_check` 를 `{"status":"ready"}`/`{"status":"busy"}` 로 교체(진행 중 콜백 유무로 busy 판정 가능, `vlm_sim` 확장). **본 회차에서도 수정하지 않음**(검증 전용, VERIFY-PROMPT §10).

### [G-ISSUE-82] G-8 — VLM 콜백 SSRF 가드가 genai 가드보다 약함(자기참조·임의포트 허용, 2차 G-ISSUE-82 승계, 코드 미수정)
- **심각도**: MEDIUM(목 서버가 루프백 전용 발행이라 원격 노출 없음, 노출 시 HIGH)
- **기대 동작(기대효과)**: 같은 서버의 genai 가드는 host:port allowlist+경로접두사+자기참조 차단을 구현하는데(`genai_sim.is_allowed_url`), VLM 은 호스트 완전일치만 검사(`url_guard.is_allowed_callback`)해 방어 비대칭이 있다. 무인증 서버이므로 동등한 강도가 필요하다.
- **현재 동작(이슈 내용)**: 코드 미변경 확인(`url_guard.py` 전체 대조 — 2차 인용과 동일).
  ```python
  # mock-server/app/services/url_guard.py:28-33
  def is_allowed_callback(url, allowed_hosts) -> bool:
      host = callback_host(url)
      if host is None: return False
      return host in {h.lower() for h in allowed_hosts}
  ```
  기본 allowlist 에 `127.0.0.1`이 있어 **목 서버 자기 자신**이 항상 허용된다. 재현(2026-08-03 17:34:50~52):
  ```
  [MOCK][VLM] verify accepted request_id=qa3-ssrf callback_url=http://127.0.0.1:9400/api/genai/_mock/reset
  [MOCK][GENAI] store reset          ← 다른 벤더(genai)의 job 저장소가 통째로 초기화됨
  [MOCK][VLM] callback sent url=http://127.0.0.1:9400/api/genai/_mock/reset status=200
  ```
  대조로 **동일 URL 을 genai `callback_url` 로 넣으면 차단**됨을 확인: `{"code":"INVALID_PARAMETER","message":"...목 자신은 금지"}` (400).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/v1/videovlm/verify -H 'Content-Type: application/json' \
    -d '{"request_id":"x","event_type":"fall","media":{"type":"video","source_type":"path","path":"/x.mp4"},"callback_url":"http://127.0.0.1:9400/api/genai/_mock/reset"}'
  # 200 accepted → 2초 후 docker logs 에 "[MOCK][GENAI] store reset"
  ```
- **영향**: CWE-918(SSRF) + 무인증 상태변경 유발. 검증 세션 중에도 **다른 클러스터의 genai 관측 이력이 이 테스트 하나로 사라진다**(이번 회차에도 실제 발생, 위 "환경 부작용 고지" 참조) — 검증 신뢰성 자체에 영향.
- **수정 방향(제안)**: `vlm.py:_assert_allowed_callback` 이 genai 와 동일 판정기(`is_allowed_url` 을 벤더 중립 모듈로 승격)를 쓰도록 통합. 최소한 `_mock/*` 보조 EP 는 콜백 대상에서 무조건 배제. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-83] G-8 — VLM 요청 스키마가 벤더 조건부 필수 필드를 검증하지 않음(2차 G-ISSUE-85 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: 벤더 §3.1/§3.2 조건부 필수(`media.path`(source_type=path 시), `frame_policy.framerate`(Required=Y), `frame_policy.selected_frames`(mode=frame_selected 시))를 목업도 강제해야 BE 리팩터 회귀를 로컬에서 잡는다.
- **현재 동작(이슈 내용)**: `schemas/vlm.py` 전부 Optional, 코드 미변경 확인(2차 인용 라인과 동일 — `framerate`/`selected_frames`/`path` 모두 `Optional[...] = Field(default=None, ...)`).
- **재현/확인 경로**: 2차 문서 기록과 동일(`media.path` 누락, `frame_policy.framerate` 누락, `mode=frame_selected`인데 `selected_frames` 누락 — 3건 모두 200). 이번 회차는 코드 diff 0건 확인으로 대체(실동작 재실행 생략, 근거: `git log` 무커밋).
- **영향**: 계약 검증 공백, BE 리팩터 회귀를 로컬·CI 어디서도 못 잡음.
- **수정 방향(제안)**: `schemas/vlm.py` 에 `model_validator(mode="after")` 로 조건부 필수 3종 400 거부 추가. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-84] G-7 — `GET /manual_deid_info` 가 KPST "프로젝트 상태=3(수동대상)" 축을 반영하지 않음(2차 G-ISSUE-86 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: KPST 명세(`22-deid-solution-api.md` §22.3.8/§22.4)는 이 EP 를 "`db_save=1` **이며** 프로젝트 상태=수동대상(state=3)" 데이터셋으로 정의.
- **현재 동작(이슈 내용)**: `deid.py:_manual_targets`(2차와 동일 라인대) 가 `db_save==1` 만으로 필터, 진행중/완료 구분 없이 반환. 코드 미변경 확인.
- **영향**: 기능 영향 없음(수동 비식별 연계는 `22-deid-solution-api.md` §22.6 이 "후속·미구현" 명시, BE 호출부 0건). 향후 워크플로 연결 시 로컬 검증 공백.
- **수정 방향(제안)**: `_manual_targets` 에 상태=수동대상 조건 추가. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-85] G-7 — 산출 완료 후 `.mock-tmp/` 빈 디렉터리가 export_path 에 잔존(2차 G-ISSUE-88 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: BE 는 완료 후 export 디렉터리를 폴백 스캔한다. 목이 만드는 임시 디렉터리는 완료 후 정리돼 있어야 스캔 대상이 깨끗하다.
- **현재 동작(이슈 내용)**: 이번 회차 신규 생성한 `qa3-g5-p1/` 에서도 동일 재현.
  ```
  $ docker exec klid-mock-server ls -la /app/storage/deidentified/videos/qa3-g5-p1/
  drwx------ 2 app app 4096 Aug  3 17:35 .mock-tmp     ← 빈 디렉터리, 잔존
  -rw-r--r-- 1 app app 50854 Aug  3 17:35 af5780ea…-mask.mp4
  ```
- **영향**: 현재 무해(BE 회수는 1차 파일명 경로가 맞아 폴백 스캔에 도달 안 함). export 폴더 청결성 저하.
- **수정 방향(제안)**: `produce_deid_outputs` 종료부에서 빈 임시 디렉터리 `rmdir`(genai `discard_results` 와 동일 패턴). **본 회차에서도 수정하지 않음**.

---

## 카탈로그 정정 (담당 라인범위 163~216행 내, 직접 Edit 완료)

| 정정 대상 | 정정 전 | 정정 후 | 근거 |
|---|---|---|---|
| TC-AIMOCK-11(186행) | 기대결과 "동기 **202** accepted, 콜백은 status=failed+error_code/message" | "동기 **200** accepted(202 아님 — 벤더 v2.0.1 §2.1/§2.5 및 TC-AIMOCK-09 와 동일 코드), 콜백은 `{"status":"failed","error":{"code":"INFERENCE_ERROR","message":...}}`(중첩 error 객체)" | 실동작 `HTTP:200` 확인 + `vlm.py:196-199,219-222`. 2차 G-ISSUE-83 이 이미 지적했으나 **미반영 상태로 방치**돼 있던 것을 이번 회차에 재발견·정정 |
| TC-AIMOCK-12(187행) | 기대결과 "200 {status:ok, service:videovlm}"(=구현을 그대로 베낀 것) | 벤더 v2.0.1 §2.7 규격(`ready`/`busy`, service 필드 없음)을 기대결과로 명시하고, 현재 코드가 이를 위반함(FAIL)을 케이스명에 명기 | G-ISSUE-84(2차)/G-ISSUE-81(본 회차) — 카탈로그가 결함을 은폐하던 확증편향 사례. 벤더 원문은 2차 검증자가 docx 추출로 확인한 내용을 승계(본 회차엔 docx 재추출 미실시, 2차 인용을 근거로 사용 — 필요시 원문 docx 재확인 권장) |
| 변경 이력 표(9행 뒤) | (round 2 까지) | 3차 회차 행 추가(정정 2건 요약 + 2차 이후 code 미변경으로 인한 재확인 결과 명시) | — |

**정정 건수: 2건**(TC 행) + 변경이력 1행 추가.

---

## 이전 회차(2차, 2026-08-02) 이슈 해소 여부

| 2차 이슈 | 내용 | 이번 회차 상태 |
|---|---|---|
| G-ISSUE-81 | TC-AIMOCK-07 카탈로그 stale(18바이트 placeholder) | **해소됨** — 카탈로그가 2차~3차 사이 이미 정정되어 있었음(확인만), 실동작도 정정된 기대결과와 일치 |
| G-ISSUE-82 | VLM 콜백 SSRF 가드 약함(자기참조 성립) | **미해결** — 코드 미변경, 이번 회차도 동일하게 재현(genai store reset 재발) → 본 문서 G-ISSUE-82 로 재기록 |
| G-ISSUE-83 | TC-AIMOCK-11 카탈로그 "동기 202" 오기 | **미해결 상태로 방치돼 있었음 → 이번 회차에 직접 정정 완료**(위 "카탈로그 정정" 참조) |
| G-ISSUE-84 | TC-AIMOCK-12 벤더 규격 불일치(status 응답) | **코드 미해결**(재확인), 카탈로그는 이번 회차에 벤더 규격 기준으로 정정 → 본 문서 G-ISSUE-81 로 재기록 |
| G-ISSUE-85 | VLM 스키마 조건부 필수 미검증 | **미해결** — 코드 미변경(diff 0건 확인) → 본 문서 G-ISSUE-83 로 재기록 |
| G-ISSUE-86 | manual_deid_info 상태축 미반영 | **미해결** — 코드 미변경 → 본 문서 G-ISSUE-84 로 재기록 |
| G-ISSUE-87 | 근거 file:line 드리프트 15건(본 스코프분) | **해소됨** — 2차~3차 사이 카탈로그 전수 라인 정정(163행 전체 회차)이 이미 적용되어 있었고, 이번 회차 샘플 재검증(01,02,03,04,05,06,07,08,10,12,17,22,29 등)에서 전부 실제 코드와 일치 확인 |
| G-ISSUE-88 | .mock-tmp 잔존 | **미해결** — 코드 미변경, 이번 회차 신규 생성 프로젝트에서도 재현 → 본 문서 G-ISSUE-85 로 재기록 |

**요약**: 2차 8건 중 카탈로그성 2건(81·87)은 해소, 코드성 5건(82·84·85·86·88)은 전부 코드 미변경으로 미해결 재확인, 카탈로그 방치 1건(83)은 이번 회차에 직접 정정.
