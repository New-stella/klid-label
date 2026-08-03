# G 클러스터 검증 결과 — part4 (G-5 VLM verify-objects 14건 + G-10 BE↔벤더 실배선 13건 = 27건)

> 검증일 2026-08-02 · 회차 2차 · 담당 범위: `docs/test-cases/G-ai-server.md` §G-5, §G-10
> 코드 기준: worktree `qa-0801` · **수정 없음(검증 전용)**

## 검증 환경 (실측)

| 항목 | 실측값 |
|---|---|
| 컨테이너 | `klid-postgres` · `klid-backend`(18081→8080) · `klid-ai-server`(19300→9300) · `klid-mock-server`(9400) · `klid-frontend` 전부 Up(healthy) 19h |
| backend 실효 env | `SPRING_PROFILES_ACTIVE=local` · `VLM_CLIENT_ENABLED=true` · `VLM_SERVICE_URL=http://klid-mock-server:9400` · `VLM_ALLOW_INSECURE_URL=true` · `KPST_DEID_ENABLED=true` · `KPST_DEID_BASE_URL=http://klid-mock-server:9400` · `DEIDENTIFY_MOCK_MODE=false` · `AI_SERVER_URL=http://klid-ai-server:9300` |
| ai-server 실효 env | `AI_MOCK_MODE=false`, `AI_DEVICE=cpu` (→ VLM/YOLOX 가중치 부재로 `mock_reason=weights_missing`) |
| 네트워크 | backend=172.20.0.5 · mock-server=172.20.0.4 |
| 외부 연동 self-fill 여부 | **없음** — describe→callback 왕복이 mock-server 로그로 실측됨(아래 TC-AIMOCK-45) |

**내부 목 모드 우회 없음 확인**: `DEIDENTIFY_MOCK_MODE=false` 이며 KPST 위탁·VLM describe 모두 실제 HTTP 로 mock-server 를 경유한다. ai-server 는 `AI_MOCK_MODE=false` 이나 **가중치 파일 부재**로 mock 응답(=설계상 정상, TC-AIVLM-03 참조).

---

## G-5. VLM — ai-server 자체 `/infer/vlm/verify-objects` (14건)

전 케이스를 **기동 중인 ai-server(:19300)에 실제 HTTP 요청**으로 판정했다.

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AIVLM-01 | PASS | [실동작] | `POST /infer/vlm/verify-objects` `expected_label="person"` → `{"results":[{"obj_id":"o1","expected_label":"person","verified":true,"confidence":0.92}],"mock":true,"source":"mock","mock_reason":"weights_missing"}` HTTP 200. 코드 `ai-server/app/routers/vlm.py:89-104` 일치(근거 라인 정확) |
| TC-AIVLM-02 | PASS | [실동작] | `"unicorn"` → `verified:false, confidence:0.18` HTTP 200. `vlm.py:93-100` 일치 |
| TC-AIVLM-03 | PASS | [실동작] | `AI_MOCK_MODE=false` 인데도 응답이 `mock:true source:"mock" mock_reason:"weights_missing"` — 실 VLM 미구현이 응답에 명시된다. `vlm.py:38-55` 는 `_should_mock()` 분기 없이 **무조건** `_mock_verify` 를 반환(항상 mock). reason ∈ {env_mock, weights_missing, not_implemented} 화이트리스트도 `vlm.py:63-69` 로 확인. UNCERTAINTIES #13 의 "이 엔드포인트 한정" 서술과 정합 |
| TC-AIVLM-04 | PASS | [실동작] | `"PERSON"` → `verified:true 0.92` (`obj.expected_label.lower()` `vlm.py:93`). 응답의 `expected_label` 은 입력 원문("PERSON") 그대로 반사 |
| TC-AIVLM-05 | PASS | [실동작] | `objects:[]` → HTTP **400** `{"error_code":"VALIDATION_ERROR","message":"List should have at least 1 item after validation, not 0"}`. `schemas.py:207` `min_length=1` (근거 라인 정확) |
| TC-AIVLM-06 | PASS | [실동작] | `bbox:[1,2,3]` → HTTP **400** "List should have at least 4 items". `schemas.py:200` (정확) |
| TC-AIVLM-07 | PASS | [실동작] | `obj_id` 누락 → HTTP **400** "Field required". `schemas.py:195-200` (정확) |
| TC-AIVLM-08 | PASS | [실동작] | `image_b64:"!!!notb64!!!"` → HTTP **400** `{"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}`. `vlm.py:45` → `image_utils._decode_raw` `validate=True` |
| TC-AIVLM-09 | PASS | [실동작] | 14MB base64 페이로드 → HTTP **413** `{"error_code":"IMAGE_TOO_LARGE","message":"이미지 크기 한도 초과 (max 10MB)"}`. `image_utils.py:42-43`(정확) + 선행 길이 추정 컷 `:34-35`. ⚠ 케이스 전제 "1MB" 는 `tests/conftest.py:18 MAX_IMAGE_SIZE_MB=1` 기준이고 런타임 컨테이너는 기본 10MB — **413 동작 자체는 동일** |
| TC-AIVLM-10 | PASS | [실동작] | 최상위 추가필드(`"bogus":1`) → 400, objects 원소 내 추가필드(`"zz":2`) → 400 둘 다 "Extra inputs are not permitted". 근거 라인은 드리프트(아래 G-ISSUE-63) |
| TC-AIVLM-11 | PASS | [실동작] | `[dog, unicorn, car]` 순서 입력 → 응답 `results` 가 `z(dog,true) → a(unicorn,false) → m(car,true)` 로 **입력 순서 그대로**, obj_id/expected_label 반사. `vlm.py:91-101` |
| TC-AIVLM-12 | PASS | [실동작] | `POST /infer/vlm/video-meta` → HTTP **404** `{"detail":"Not Found"}`. 라우터에 verify-objects 만 등록(`vlm.py:38`). 테스트 `tests/test_vlm.py:52 test_vlm_router_video_meta_endpoint_removed` 커버 |
| TC-AIVLM-13 | PASS | [실동작] | `docker logs klid-ai-server | grep -c "[VLM][MOCK]"` = **1** — 12회 verify-objects 호출 동안 WARN 1회만. `vlm.py:72-80` 전역 플래그(단일 uvicorn 프로세스 `Started server process [1]` 확인) |
| TC-AIVLM-14 | PASS | [정적] | `AiServerClient.java:91` `.uri("/infer/vlm/verify-objects")` (base=`aiServerWebClient`←`AI_SERVER_URL:9300`) vs `VlmClient.java:53` `DESCRIBE_PATH="/v1/videovlm/describe"` (base=`vlmWebClient`←`vlm.client.url:9400`). 클라이언트 클래스·경로·베이스URL 전부 상이. **역방향 반증**: `grep -rn "infer/vlm" backend/src frontend/src` → BE 클라이언트 3곳뿐, FE 0건. 추가 실측 — ai-server 접근 로그의 verify-objects 12건이 **전부 172.20.0.1(호스트=검증자 curl)** 이고 backend(172.20.0.5)발 0건 → **프로덕션 호출부 자체가 없다**(별건 관측, G-ISSUE-62) |

**G-5 소계**: PASS 14 / FAIL 0 / PARTIAL 0 / N/A 0.

---

## G-10. BE ↔ 외부 벤더 실배선 계약 (13건)

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|:--:|---|---|
| TC-AIMOCK-34 | PASS | [실동작] | mock-server 접근 로그에 **`172.20.0.5:53934 - "GET / HTTP/1.1" 200 OK`**(총 25건) — backend 가 벤더 루트를 핑한다. `DeidentifyHealthIndicator.java:92-104` `kpstWebClient.get().uri("/")` (근거 라인 정확), 성공 시 `mode=kpst` UP. 집계 `/api/actuator/health` = `{"status":"UP"}`. 테스트 `DeidentifyHealthIndicatorTest:89 실모드_핑은_벤더가_제공하는_루트경로로_요청한다` 커버. ⚠ 참고: `retrieve().toBodilessEntity()` 라 실벤더 루트가 401/403/404 를 주면 DOWN 오탐 — 기대결과("200 대이면 UP")와는 정합 |
| TC-AIMOCK-35 | PASS | [정적] | `DeidentifyHealthIndicator.java:77-83` — `mockMode` 분기가 **최우선**이라 핑 없이 `UP/mode=mock`. 실행 경로 우선순위와 일치 확인(`DeidentifyStep.java:44-45,256-257` ①mockMode ②KPST ③거부) → 헬스↔실행 판정 축 동일. 테스트 `DeidentifyHealthIndicatorTest:59` 커버. (런타임은 `DEIDENTIFY_MOCK_MODE=false` 라 설정 변경 없이는 실동작 재현 불가 — 설정 수정 금지 규칙 준수) |
| TC-AIMOCK-36 | PASS | [정적] | `:84-91` `if (!kpstEnabled \|\| kpstWebClient == null)` → `DOWN, mode=unconfigured, error=NoDeidentifyPathConfigured` (문자열 일치). 테스트 `:122 mock도_KPST위탁도_아니면_핑없이_DOWN_설정오류` 커버 |
| TC-AIMOCK-37 | PASS | [정적] | `:105-112` catch 에서 `e.getClass().getSimpleName()` 만 노출 — 스택트레이스·URL·내부경로 미노출(CWE-209). 테스트 `:105 실모드_서버타임아웃시_DOWN` 커버 |
| TC-AIMOCK-38 | PASS | [실동작] | 기동 로그 실측: `WARN [VlmUrl] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용. property=vlm.client.allow-insecure-url` + `WARN [ExternalUrl] 평문 HTTP 전송 — 내부망 격리 전제. property=vlm.client.url host=klid-mock-server:9400` → local + flag=true 에서 relaxed(`ExternalUrlPolicy.internalNetwork`, `:80-82`) 적용되어 평문 http·사설 IP 목업으로 정상 기동. `ProfileGatedUrlPolicy.java:121-133`(check/policy) `:145-151`(profileAllowsRelaxation) 근거 라인 정확 |
| TC-AIMOCK-39 | PASS | [정적] | `ProfileGatedUrlPolicy.java:78-91` `@PostConstruct verifyRelaxationScope()` — allowlist `{local,dev}` `containsAll`(혼합·미지정 자동 엄격) + `ENV∈{stg,prd}` 독립축 우선 거부 → `IllegalStateException` 기동 중단. **토글(enabled)과 무관하게** 검사(:74-76 주석 및 코드 확인). 테스트 3종 커버: `VlmUrlPolicyBootGuardTest:23 prd_프로파일에서_완화_플래그가_켜져있으면_컨텍스트_기동이_실패한다`, `VlmUrlPolicyTest:79/:98/:137` |
| TC-AIMOCK-40 | PASS | [정적] | `ExternalUrlPolicy.java:143-153 rejectMetadataRangeIfResolvable` — relaxed 경로에서도 `isLinkLocalAddress()`(fe80::/10 포함) 또는 `169.254.` 접두면 `IllegalStateException`. 테스트 `VlmUrlPolicyTest:161 링크로컬_메타데이터_대역은_완화_프로파일에서도_차단된다`. ⚠ 한계 1건 기록(G-ISSUE-65: `InetAddress.getByName` 단일 주소만 검사) |
| TC-AIMOCK-41 | PASS | [정적] | relaxed: `:144-147` 해석 실패 → `return`(통과) / strict: `:169-175` `UnknownHostException` → `IllegalStateException`. 테스트 `VlmUrlPolicyTest:179 해석되지_않는_컨테이너명은_완화_프로파일에서_계속_허용된다`. [실동작] 보강 — 컨테이너 내부에서 `klid-mock-server` 해석 가능한 상태로 relaxed 기동 성공 |
| TC-AIMOCK-42 | PASS | [정적] | `VlmClient.java:100-112` 파이프라인 + `:154-169 validateResponse` — ①`request_id` echo 불일치 ②`status != "accepted"` 각각 `CustomException(EXTERNAL_API_ERROR)`. 근거 라인 정확(88-112, 154-169). 테스트 `VlmClientTest:141 응답_request_id_echo_불일치_시_EXTERNAL_API_ERROR` / `:156 응답_status_accepted_아니면_EXTERNAL_API_ERROR` 커버. ⚠ 실동작 재현 불가 — mock-server(`app/routers/vlm.py:172-178 _resolve_request_id`)가 요청 request_id 를 항상 echo 하고 응답 조작 훅이 없다. ⚠ 참고: 검증 실패 예외는 `ignore-exceptions` 대상이 아니라 **재시도 3회** 대상(동일 request_id 재위탁) — 계약 위반은 아님 |
| TC-AIMOCK-43 | PASS | [정적] | `VlmClient.java:106` `.onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)` → `:121-127` 본문 release 후 `NonRetryableExternalException`(상태코드만, CWE-209). 설정 실측: circuitbreaker `application.yml:532-539 ignore-exceptions: NonRetryableExternalException`, retry `:606-612` 동일 등록 → 재시도·서킷 failure 집계 양쪽에서 제외. 테스트 `VlmClientTest:221 V1_400_형식오류는_재시도없이_1회요청` / `:236 V1_422...` 커버. 근거 라인 드리프트(G-ISSUE-63) |
| TC-AIMOCK-44 | N/A | [정적] | **케이스 전제(45s 블록 계층)가 코드에 없다.** `VlmTimeseriesStep` 은 Phase C-1 논블로킹 전환으로 `.block(45s)` 를 폐지하고 `:366 .subscribe(...)` 로 즉시 반환한다 — `BLOCK_TIMEOUT` 상수 부재, 45s 는 `:70,:348-349` **주석(구 코드 설명)** 에만 남아 있다. 근거로 지정된 `VlmTimeseriesStep.java:78` 은 현재 javadoc 라인. 현존 타임아웃은 `vlm.client.timeout-seconds:10`(`application.yml:709`) → `VlmClient.java:76,108` **단일 계층**. 미구현 갭이 아니라 **의도된 개선**(테스트 `VlmTimeseriesStepNonBlockingTest:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다` 가 반대 방향을 강제)이므로 FAIL 승격하지 않고 카탈로그 정정 대상으로 기록(G-ISSUE-61) |
| TC-AIMOCK-45 | PASS | [실동작] | **self-fill 없음 실증.** backend 로그 `[Vlm] describe submit request_id=847751b3-…` → mock-server 로그 `POST /v1/videovlm/describe 200` + `[MOCK][VLM] describe accepted request_id=847751b3-… callback_url=http://klid-backend:8080/api/v1/vlm/callback` → `POST http://klid-backend:8080/api/v1/vlm/callback "HTTP/1.1 200"` → backend `[Webhook][Vlm] result applied request_id=847751b3-… rawSn=80 new=1 markingsTransitioned=1`. 왕복 3건 관측(rawSn 80·81·94). 공통 기본값은 `application.yml:702 enabled: ${VLM_CLIENT_ENABLED:false}` → `VlmClient.java:94-97` 즉시 SKIPPED(근거 라인 정확), local 은 `application-local.yml:147 enabled: ${VLM_CLIENT_ENABLED:true}`(근거 표기 150 은 드리프트) |
| TC-AIMOCK-46 | PASS | [정적] | `VlmTimeseriesStep.java:303-306` — `deidentReportGate.isUnderDeidentReport(rawSn)` 이 참이면 `recordVlmSkipped(rawSn, SKIP_REASON_DEIDENT_REPORT)` 후 `skipped(null)` 반환. **게이트 위치가 `resolveDeidentifiedPath(rawSn)`(:308) 직전**이라 외부 전송 0건 확정이고, `run`/`runWithMarking` 공용 본체 `doSubmit` 안이라 dev 트리거 등 public 진입점도 우회 불가(:238,:254). 사유 상수 `:113`. DB 적재 실측: `SELECT proc_step_cd, proc_stts_cd, count(*) FROM ls_batch_proc_log WHERE proc_step_cd LIKE '%VLM%'` → `VLM/SKIPPED 10건`, `VLM/FAILED 17건` — SKIPPED 기록 경로가 실제로 동작함(현 데이터셋에 신고 사유 행은 없음). 재개 배선 `VlmWithheldResumeRunner` 존재. 테스트 `VlmTimeseriesStepTest:113 비식별_신고_구간_영상은_외부_VLM_호출_0건이고_보류로_기록된다` / `:137 신고가_해소되면_같은_영상의_VLM_위탁이_재개된다` + `VlmWithheldResumeRunnerTest` 커버. 근거 라인 드리프트(G-ISSUE-63) |

**G-10 소계**: PASS 12 / FAIL 0 / PARTIAL 0 / N/A 1.

---

## 판정 집계 (part4 합계 27건)

| 판정 | 건수 |
|---|--:|
| PASS | 26 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 1 (TC-AIMOCK-44) |
| 확인필요 | 0 |

**self-fill 결함**: 0건. VLM 시계열은 BE→mock-server 실 HTTP 왕복 + 콜백 수신으로 값이 조달됨을 로그로 실증했고(TC-AIMOCK-45), 비식별 헬스도 실제 벤더 루트 핑을 수행한다(TC-AIMOCK-34). ai-server `verify-objects` 의 고정 응답은 **응답에 `mock:true/source:"mock"/mock_reason` 을 명시**하는 설계된 미구현 표식이라 self-fill(외부 응답을 흉내 내 숨기는 자체 채움)에 해당하지 않는다.

---

## 이슈 대장

### [G-ISSUE-61] TC-AIMOCK-44 — VLM 타임아웃 "2계층(45s 블록 + 10s WebClient)" 전제가 코드에 없음 (논블로킹 전환으로 폐지)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 "WebClient 자체 10s(`vlm.client.timeout-seconds`) 안쪽에 배치 오케스트레이션 블록 45s(`BLOCK_TIMEOUT`)" 라는 **2계층 타임아웃**이 존재하고, describe 무응답 시 10s 가 먼저 발화하며 스텝 상한이 45s 임을 보장해야 한다고 기술한다. 외부 지연이 파이프라인 스레드를 무한 점유하지 않도록 하는 안전장치의 존재 확인이 목적이다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep` 은 Phase C-1 에서 **논블로킹 제출**로 전환되어 `.block(45s)` 와 `BLOCK_TIMEOUT` 상수가 **삭제**됐다. 45s 는 폐지된 구현을 설명하는 주석에만 남는다.
  - `backend/.../batch/step/VlmTimeseriesStep.java:76` (javadoc) — `<li><b>제출</b> — {@code subscribe} 만 하고 즉시 반환({@code status="submitted"}).</li>`
  - `VlmTimeseriesStep.java:348-349` (주석) — `구 코드는 .block(45s) 로 파이프라인 스레드(batch-async- / Quartz 워커 …)를 최대 45초 붙잡았다.`
  - `VlmTimeseriesStep.java:366` — `.subscribe(`
  - 현존 타임아웃 1계층: `VlmClient.java:76` `this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));` ← `application.yml:709 timeout-seconds: 10`, 적용 지점 `VlmClient.java:108 .timeout(timeout)`
  - 케이스가 지정한 근거 `VlmTimeseriesStep.java:78` 은 현재 javadoc 라인(코드 아님)
- **재현/확인 경로**: `grep -n "BLOCK_TIMEOUT\|block(" backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java` → 매치 0건(주석의 `.block(45s)` 문자열만). 반대 방향을 강제하는 회귀 가드: `backend/src/test/java/.../batch/step/VlmTimeseriesStepNonBlockingTest.java:106 ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다`
- **영향**: 코드 결함 아님(논블로킹 전환은 배치 풀 고갈을 막는 의도된 개선). 다만 **카탈로그가 사문화된 기대값을 들고 있어** 다음 회차에서 "45s 계층이 없다 → FAIL" 로 오판정하거나, 반대로 45s 를 되살리는 회귀 수정을 유발할 수 있다. UNCERTAINTIES #13 의 "타임아웃은 이중 구조 — 블록 상한 45s ↔ 실효 10s" 서술도 같은 이유로 낡았다.
- **수정 방향(제안)**: 코드 수정 없음. `docs/test-cases/G-ai-server.md` TC-AIMOCK-44 의 기대결과를 "타임아웃은 `vlm.client.timeout-seconds`(기본 10s) **단일 출처**이며, 스텝은 ACK 를 기다리지 않고 즉시 반환한다(미회신은 `VlmSubmitPendingSweeper` `staleTimeoutMinutes=30` 이 회수)" 로 교체하고 근거를 `VlmClient.java:76,108` + `VlmTimeseriesStep.java:366` 으로 갱신. `UNCERTAINTIES.md` #13 의 이중구조 문구도 동일 정정.

### [G-ISSUE-62] TC-AIVLM-14 파생 — `AiServerClient.verifyObjects` 프로덕션 호출부 0건 (G-5 엔드포인트 전체가 미사용 표면)
- **심각도**: LOW
- **기대 동작(기대효과)**: ai-server `/infer/vlm/verify-objects` 는 "YOLO/SAM2 검출 결과의 라벨 정합성 검증" 목적으로 노출된 엔드포인트이고, BE 는 `AiServerClient.verifyObjects` 로 이를 호출하는 것이 카탈로그 §G-5 전제다. 노출된 추론 표면은 실제로 사용되거나, 사용되지 않으면 제거되어야 한다(미사용 API 표면 = OWASP API9:2023 Improper Inventory Management).
- **현재 동작(이슈 내용)**: 클라이언트 메서드는 존재하나 **`src/main` 어디에서도 호출되지 않는다.**
  - `backend/.../common/client/AiServerClient.java:89-98` — `public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) { return webClient.post().uri("/infer/vlm/verify-objects") … }`
  - `grep -rn "verifyObjects" backend/src/main backend/src/test` → `src/main` 은 정의부 1건뿐, 호출부 0건. `src/test` 도 실호출 없이 `AiInferenceDeidentReportGateTest.java:71` 주석 언급 1건
  - `grep -rn "infer/vlm" backend/src frontend/src` → BE 3건(정의/주석/DTO 주석), FE 0건
  - [실동작] ai-server 접근 로그 집계: `POST /infer/vlm/verify-objects` 12건이 **전부 172.20.0.1(호스트 = 본 검증자 curl)** 이고 backend(172.20.0.5) 발신 0건. 같은 로그에서 `POST /infer/sam2/track` 138건 · `/infer/yolo/predict` 23건 등 실제 사용 엔드포인트는 backend 발신이 관측됨
- **재현/확인 경로**:
  - `grep -rn "verifyObjects" backend/src/main` (호출부 0건 확인)
  - `docker logs klid-ai-server 2>&1 | grep "verify-objects" | awk '{print $2}' | sort -u` (발신 IP 확인)
- **영향**: 기능 결함 아님. 다만 ①인증 없는 추론 표면이 사용처 없이 열려 있고(ai-server 는 무인증) ②G-5 14개 케이스가 **제품 동선에서 도달 불가능한 경로**를 검증 중이라 검증 리소스 배분이 왜곡된다. `AiInferenceDeidentReportGateTest.java:71` 주석은 "`verifyObjects` 처럼 같은 이미지를 운반하는 다른 메서드"가 비식별 신고 게이트를 우회할 수 있음을 지적하는데, 현재는 호출부가 없어 잠재 위험으로만 남아 있다 — 향후 배선 시 게이트 누락 위험(CWE-359).
- **수정 방향(제안)**: 정책 결정 필요 — ①실사용 계획이 없으면 `AiServerClient.verifyObjects` + `ai-server/app/routers/vlm.py` 라우트 + 관련 DTO/스키마를 제거(표면 축소), 또는 ②사용 계획이 있으면 배선 시 `encodeDeidentifiedFrameForInference` 경로 + 비식별 신고 게이트를 반드시 함께 적용. 어느 쪽이든 카탈로그 §G-5 에 "현재 프로덕션 호출부 0건" 을 명기해 검증자가 도달 불가 경로임을 알게 한다.

### [G-ISSUE-63] 근거 file:line 드리프트 4건 (카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거(file:line)` 는 판정자가 즉시 대조할 수 있는 실제 위치여야 한다. 어긋나면 검증자가 엉뚱한 라인을 읽고 거짓 PASS/FAIL 을 낸다.
- **현재 동작(이슈 내용)**: 아래 4건이 실제 위치와 불일치(각각 실측 확인).
  | ID | 카탈로그 표기 | 실제 위치 |
  |---|---|---|
  | TC-AIVLM-10 | `schemas.py:204,211` | 요청 모델 `extra="forbid"` 는 `schemas.py:196`(ObjectToVerify) · `:204`(VlmVerifyRequest). **`:211` 은 응답 모델 `ObjectVerification`** 이라 요청 거부 근거가 아님 |
  | TC-AIMOCK-43 | `application.yml:512-520` | 실제는 circuitbreaker `application.yml:532-539` + retry `:606-612`. 512-520 은 `kpst.deid.*` 폴링 설정 |
  | TC-AIMOCK-45 | `application-local.yml:150` | 실제는 `application-local.yml:147 enabled: ${VLM_CLIENT_ENABLED:true}`. 150 은 빈 줄 |
  | TC-AIMOCK-46 | `VlmTimeseriesStep.java:60-65,95,216-220` | 게이트 본체는 `:303-306`, 사유 상수는 `:113`. `:95` 는 `DEFAULT_FRAMERATE` 상수, `:216-220` 은 `execute(BatchContext)` javadoc |
  (별도로 TC-AIMOCK-44 의 `VlmTimeseriesStep.java:78` 은 G-ISSUE-61 에서 다룸)
- **재현/확인 경로**: `sed -n '196p;204p;211p' ai-server/app/schemas.py` · `sed -n '532,539p;606,612p' backend/src/main/resources/application.yml` · `sed -n '147p;150p' backend/src/main/resources/application-local.yml` · `sed -n '95p;303,306p' backend/src/main/java/kr/co/cudo/authoring/batch/step/VlmTimeseriesStep.java`
- **영향**: 검증 효율·신뢰도 저하(카탈로그 자체의 정합성 결함). 제품 동작 영향 없음.
- **수정 방향(제안)**: `docs/test-cases/G-ai-server.md` 의 해당 4행 근거 컬럼을 위 표의 "실제 위치" 로 교체.

### [G-ISSUE-64] ai-server 애플리케이션 INFO 로그가 전량 유실됨 (logging 미구성)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: ai-server 는 각 추론 라우터에서 진단 로그를 남기도록 작성돼 있다(예: 요청별 이미지 크기·객체 수). 장애 분석·계약 검증 시 "무엇이 어떤 입력으로 들어왔는가"를 사후 추적할 수 있어야 한다.
- **현재 동작(이슈 내용)**: **컨테이너 로그에 앱 INFO 가 단 1건도 없다.** ai-server 는 `logging.basicConfig`/`dictConfig`/uvicorn `--log-config` 를 어디에서도 설정하지 않아 앱 로거가 Python 기본 root level(WARNING)로 동작한다. 결과적으로 `logger.info(...)` 는 모두 버려지고 `logger.warning` 이상만 출력된다.
  - `ai-server/app/routers/vlm.py:46-51` — `logger.info("[VLM] verify-objects received image_size=%dx%d objects=%d", width, height, len(req.objects))`
  - `docker logs klid-ai-server | grep -c "verify-objects received"` → **0** (실제로는 12회 호출됨 — uvicorn access 로그로 확인)
  - 같은 로그에서 `WARNING:app.routers.vlm:[VLM][MOCK] …` 은 정상 출력 → 레벨 컷임이 확정
  - `grep -rn "basicConfig\|dictConfig\|log_level\|LOG_LEVEL" ai-server/app ai-server/Dockerfile*` → 매치 0건, 기동 커맨드 `["uvicorn","app.main:app","--host","0.0.0.0","--port","9300"]` 에도 로그 옵션 없음
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:19300/infer/vlm/verify-objects -H 'Content-Type: application/json' \
    -d '{"image_b64":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==","objects":[{"obj_id":"o1","expected_label":"person","bbox":[0,0,1,1]}]}'
  docker logs --since 1m klid-ai-server | grep "verify-objects received"   # 0건
  ```
- **영향**: 운영 관찰가능성 결손 — 추론 서버에서 장애·계약 불일치가 발생해도 요청 단위 컨텍스트(이미지 크기·객체 수·모델 로드 상태 등)를 사후 확인할 수 없다. `main.py:31-37` 의 startup 진단(`[AI] startup mock_mode=… device=… max_image_mb=…`)도 동일하게 유실돼 **실행 중 인스턴스의 mock 모드 여부를 로그로 확인할 수 없다**(본 검증에서도 `docker exec env` 로 우회해야 했다). rules/observability.md 의 traceId·구조화 로깅 원칙과도 어긋난다.
- **수정 방향(제안)**: `ai-server/app/main.py` 에 `logging.config.dictConfig`(또는 최소 `logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))`)를 앱 생성 전에 배선하고, 레벨을 `LOG_LEVEL` 환경변수로 노출(기본 INFO, prd 는 조정 가능). `RequestIdMiddleware` 가 이미 request-id 를 들고 있으므로 포매터에 함께 실어 BE 의 `X-Trace-Id` 와 상관관계를 맞춘다. 대안으로 uvicorn `--log-config` 파일 배선.

### [G-ISSUE-65] TC-AIMOCK-40 — 외부 URL 대역 검사가 호스트의 **첫 번째 해석 주소만** 검사 (다중 A 레코드 우회 여지)
- **심각도**: LOW
- **기대 동작(기대효과)**: relaxed 정책은 링크로컬/클라우드 메타데이터 대역(169.254.0.0/16 · fe80::/10)을, strict 정책은 loopback/사설/링크로컬 전부를 거부해야 한다(CWE-918 SSRF). 호스트명이 **여러 주소로 해석되는 경우에도** 위험 대역이 섞여 있으면 거부되는 것이 안전하다.
- **현재 동작(이슈 내용)**: 두 검사 모두 `InetAddress.getByName(host)` 를 써 **첫 번째 주소 1개만** 판정한다. 호스트명이 `[공인IP, 169.254.169.254]` 처럼 복수 레코드로 해석되고 첫 주소가 안전하면 검사를 통과하며, 실제 커넥션은 JDK/OS 의 주소 선택에 따라 위험 주소로 갈 수 있다.
  - `backend/.../common/config/ExternalUrlPolicy.java:161-165` — `return InetAddress.getByName(normalized);` (relaxed 경로)
  - `ExternalUrlPolicy.java:170-175` — `addr = InetAddress.getByName(host);` (strict 경로)
  - `:148` / `:176-177` 이 그 **단일** `addr` 만 검사
- **재현/확인 경로**: `vlm.client.url` 을 A 레코드가 2개(첫 번째 공인, 두 번째 169.254.x)인 호스트명으로 설정 → 기동이 통과. (환경 구성이 필요해 본 회차에서 실동작 재현은 하지 않음 — 정적 확인)
- **영향**: CWE-918(SSRF) 잔여 표면. 다만 **트리거 조건이 "운영자가 base-url 설정값을 그런 호스트명으로 지정" 이라 신뢰 경계 안쪽**이고, 값은 사용자 입력이 아니라 환경변수라 실착취 가능성은 낮다. 부수적으로 기동 시점 검사와 실제 커넥션 시점 해석이 분리돼 있어 DNS rebinding 에 대한 TOCTOU 여지도 동일하게 남는다(현 설계가 이를 방어 대상으로 선언하지 않음).
- **수정 방향(제안)**: `InetAddress.getAllByName(host)` 로 바꿔 **해석된 전 주소**에 대해 대역 검사를 수행(하나라도 위험 대역이면 거부). relaxed 경로의 "해석 실패는 통과" 규약은 그대로 유지(`getAllByName` 도 `UnknownHostException` 을 던지므로 동일 catch 로 처리 가능). 근본적 rebinding 방어가 필요해지면 커넥션 시점 IP 검증(커스텀 `AddressResolver`)을 별도 설계.

---

## 근거 드리프트 요약 (카탈로그 정정 입력)

| ID | 카탈로그 근거 | 실제 |
|---|---|---|
| TC-AIVLM-10 | `schemas.py:204,211` | `schemas.py:196,204` (211=응답모델) |
| TC-AIMOCK-43 | `application.yml:512-520` | `application.yml:532-539`(CB) + `:606-612`(retry) |
| TC-AIMOCK-44 | `VlmTimeseriesStep.java:78` | 45s 계층 폐지 — `VlmClient.java:76,108` 단일 계층 |
| TC-AIMOCK-45 | `application-local.yml:150` | `application-local.yml:147` |
| TC-AIMOCK-46 | `VlmTimeseriesStep.java:95,216-220` | `VlmTimeseriesStep.java:113,303-306` |

정확했던 근거(대조 완료): `routers/vlm.py:38-69/72-80/89-104/93/45`, `schemas.py:195-200/200/207`, `image_utils.py:42-43`, `AiServerClient.java:91`, `VlmClient.java:53,67,88-112,94-97,106,121-127,154-169`, `DeidentifyHealthIndicator.java:77-83/84-91/92-104/105-112`, `ProfileGatedUrlPolicy.java:78-91/121-133/145-151`, `ExternalUrlPolicy.java:80-82/143-153/169-181`, `test_vlm.py:52`.

## UNCERTAINTIES 갱신 제안

- **#13 (VLM 45s 타임아웃·콜백·IntelliVIX v2.0.1)**: "타임아웃은 이중 구조 — 블록 상한 45s ↔ 실효 10s" → **"단일 구조 — `vlm.client.timeout-seconds`(기본 10s) 단일 출처. 45s 블록 계층은 Phase C-1 논블로킹 전환으로 폐지"** 로 정정(G-ISSUE-61). describe→callback 실왕복은 본 회차에서 **재확인**(rawSn 80·81·94). 남은 미확정은 여전히 IntelliVIX 실서버 대조 1건.
- **#15 (실모델 테스트 게이팅)**: 런타임 실측으로 상태 확정 — 컨테이너는 `AI_MOCK_MODE=false` 이지만 **가중치 파일 부재**로 `mock_reason=weights_missing` 이 반환된다(`[YOLOX] weights not found — fallback to mock` 기동 로그). 즉 "환경변수는 실모델이나 실제로는 mock" 상태이며, 이를 구분할 수 있는 신호는 응답의 `mock_reason` 뿐이고 **INFO 로그로는 확인 불가**(G-ISSUE-64). 게이팅 정책 자체는 여전히 미확정.
