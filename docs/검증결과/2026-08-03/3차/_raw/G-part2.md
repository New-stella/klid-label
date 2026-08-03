# G 클러스터 part2 — G-10. BE ↔ 외부 벤더 실배선 계약 (TC-AIMOCK-34~46, 13건)

- 담당 파일: `docs/test-cases/G-ai-server.md` §G-10 (검증 착수 시점 217행~파일끝 — 병렬 에이전트의 상위 절 추가로 검증 중 227행~로 이동)
- 대상: `VlmUrlPolicy` / `ProfileGatedUrlPolicy` / `ExternalUrlPolicy` / `DeidentifyHealthIndicator` / `VlmClient` / `VlmTimeseriesStep`
- 환경: `_raw/stack-bringup.md` 실효 배선 확인분 위에서 검증. backend 컨테이너 실효 env 재실측 —
  `SPRING_PROFILES_ACTIVE=local` · `ENV` **미설정** · `VLM_SERVICE_URL=http://klid-mock-server:9400` ·
  `VLM_ALLOW_INSECURE_URL=true` · `VLM_CLIENT_ENABLED=true` · `KPST_DEID_BASE_URL=http://klid-mock-server:9400` ·
  `KPST_DEID_ENABLED=true` · `DEIDENTIFY_MOCK_MODE=false`
- 실동작 도구: ①REVIEWER JWT 발급 후 `/api/actuator/health` 상세 조회(`show-details: when-authorized`)
  ②`docker pause klid-mock-server` 장애 주입 ③운영 이미지(`klid-backend:latest`)로 **일회용 컨테이너 기동 프로브**
  (klid-net 참여, `SPRING_QUARTZ_AUTO_STARTUP=false`, 부팅 완주가 예상되는 케이스는 DB 호스트를 무효화해 공유 데이터 무접촉)
  ④mock-server / backend 컨테이너 로그 ⑤PostgreSQL(`klid_system`) 직접 조회
- **프로덕션 코드·설정 무수정.** 카탈로그 정정만 담당 라인범위 안에서 수행(5건).

---

## 판정 요약

| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| 13 | 12 | 0 | 1 | 0 | 0 | 0 |

---

## 케이스별 판정

| ID | 판정 | 근거 확인 |
|----|:--:|----------|
| TC-AIMOCK-34 | PASS | [실동작] REVIEWER 토큰으로 `/api/actuator/health` → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`. 동시에 mock-server 로그에 backend(172.20.0.5)발 **`"GET / HTTP/1.1" 200 OK`** 기록 — `/health` 가 아니라 **루트(`/`)** 를 핑함이 실증됨. 코드 `DeidentifyHealthIndicator.java:95-100`(`.uri("/")`) · UP 반환 `:101-104`. 테스트 `DeidentifyHealthIndicatorTest.java:88 실모드_핑은_벤더가_제공하는_루트경로로_요청한다`(baseline 실패 0건) |
| TC-AIMOCK-35 | PASS | [정적] `DeidentifyHealthIndicator.java:78-83` — `if (mockMode)` 가 **분기 최상단**이라 `kpstWebClient` 존재 여부와 무관하게 핑 없이 `UP/mode=mock`. 테스트 `DeidentifyHealthIndicatorTest.java:58 mock모드_활성시_외부핑없이_UP_mock` + `:184 kpst비활성이면 WebClient빈 없이도 기동`. 실동작 미확인 사유: 실효 env 가 `DEIDENTIFY_MOCK_MODE=false` 이고 이를 켜려면 공유 스택 재기동이 필요(§10 코드/설정 무수정 준수) |
| TC-AIMOCK-36 | PASS | [정적] `:85-91` — `!kpstEnabled \|\| kpstWebClient == null` → `DOWN` + `mode=unconfigured` + `error=NoDeidentifyPathConfigured`(fail-closed, 핑 없음). 테스트 `DeidentifyHealthIndicatorTest.java:121 mock도_KPST위탁도_아니면_핑없이_DOWN_설정오류` |
| TC-AIMOCK-37 | PASS | [실동작] `docker pause klid-mock-server` 로 장애 주입 → `/api/actuator/health` **HTTP 503**, `deidentifyHealth:{"status":"DOWN","details":{"service":"deidentify","mode":"kpst","error":"ReactiveException"}}`. **예외 클래스 simpleName 만** 노출되고 스택트레이스·주소·내부 경로 없음(CWE-209 충족, `:105-112`). `unpause` 후 즉시 UP 복귀 확인. 원인 구분 정보가 뭉개지는 관측성 잔여는 G-ISSUE-25 |
| TC-AIMOCK-38 | PASS | [실동작] 운영 backend 기동 로그에 `[VlmUrl] 평문/사설 URL 완화가 활성화됨 — 개발 프로파일 전용. property=vlm.client.allow-insecure-url` + `[ExternalUrl] 평문 HTTP 전송 — 내부망 격리 전제. property=vlm.client.url host=klid-mock-server:9400`. **평문 http + 컨테이너 사설IP(172.20.0.x) URL 로 기동이 성공했다는 사실 자체**가 relaxed(`ExternalUrlPolicy.internalNetwork`, `ProfileGatedUrlPolicy.java:70,133-136`) 적용의 증거 — strict 였다면 빈 생성 실패로 기동 불가. 이후 describe 왕복 실성립(TC-AIMOCK-45) |
| TC-AIMOCK-39 | PASS | [실동작] 일회용 컨테이너 프로브 **2축 모두 fail-closed 실증**. ①ENV 축: 프로파일은 `local` 그대로 두고 `ENV=prd` 만 추가 → `exit=1`, `IllegalStateException: vlm.client.allow-insecure-url=true 는 [local, dev] 프로파일에서만 허용됩니다 (현재 활성 프로파일=[local], ENV=prd)` (`ProfileGatedUrlPolicy.verifyRelaxationScope(ProfileGatedUrlPolicy.java:93)`). ②프로파일 혼합 축: `SPRING_PROFILES_ACTIVE=local,stg`(ENV 미설정) → `exit=1`, `... (현재 활성 프로파일=[local, stg], ENV=null)`. 두 경우 모두 `vlmUrlPolicy` 빈 init 실패가 `vlmWebClient→vlmClient→vlmTimeseriesStep→…` 로 전파되어 **컨텍스트 refresh 자체가 취소**됨 |
| TC-AIMOCK-40 | PARTIAL | [실동작] 케이스가 명시한 대역은 실제로 차단됨 — relaxed 유지 상태에서 `VLM_SERVICE_URL=http://169.254.169.254:9400` → `exit=1`, `IllegalStateException: vlm.client.url 이 링크로컬/클라우드 메타데이터 대역을 가리킵니다: 169.254.169.254 → 169.254.169.254. 내부망(개발) 정책에서도 차단됩니다`(`ExternalUrlPolicy.java:143-153`). **그러나 같은 정책이 선언한 목적("IMDS 는 어떤 환경에서도 정상 위탁 대상이 아니다", `ExternalUrlPolicy.java:29-37` 보안 메모)을 우회하는 대역이 relaxed·strict 양쪽에서 통과** — AWS IPv6 IMDS `fd00:ec2::254`(ULA, 링크로컬 아님) 와 CGNAT `100.100.100.200`(100.64/10) 이 기동을 통과함을 프로브로 실증. 방어 불완전 → PARTIAL. 상세 **G-ISSUE-21** |
| TC-AIMOCK-41 | PASS | [실동작] 양방향 실증. ①relaxed + 미해석 호스트: `VLM_SERVICE_URL=http://no-such-host-xyzzy:9400` → `[ExternalUrl] 평문 HTTP 전송 … host=no-such-host-xyzzy:9400` WARN 후 `Started AuthoringApplication` (= `check()` 가 끝까지 통과. `resolveQuietly` 가 `null` 반환 시 조용히 통과, `ExternalUrlPolicy.java:144-147,156-166`). ②strict(완화 플래그 off) + `https://no-such-host-xyzzy` → `exit=1`, `IllegalStateException: vlm.client.url 호스트를 해석할 수 없습니다: no-such-host-xyzzy`(`:171-175`) |
| TC-AIMOCK-42 | PASS | [실동작+정적] 정상 경로 실동작: mock-server `[MOCK][VLM] describe accepted request_id=3aa3c19b-…` ↔ backend `[Batch][VlmTimeseries] accepted rawSn=115 request_id=3aa3c19b-… status=accepted`(`VlmSubmitOutcomeRecorder`) — `POST /v1/videovlm/describe` 경로·request_id echo 일치·status="accepted" 3요소가 실왕복으로 성립. 검증 실패 분기는 `VlmClient.java:154-169`(echo 불일치·status≠accepted → `CustomException(EXTERNAL_API_ERROR)`), 테스트 `VlmClientTest.java:141 requestIdEchoMismatchRejected`·`:156 nonAcceptedStatusRejected`(baseline 실패 0건). 실동작 부정 재현은 목업에 응답 조작 훅이 없어(`mock-server/app/routers/vlm.py:200-236` — echo 는 항상 요청값) 미수행 |
| TC-AIMOCK-43 | PASS | [정적] `VlmClient.java:106` `.onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)` 가 `.bodyToMono` **앞**에 있어 4xx 는 본문 소비 후 `NonRetryableExternalException`(`:121-127`, 상태코드만 기록 — 응답 본문 미노출). 설정 실측: `application.yml:538-545` circuitbreaker `vlmClient.ignore-exceptions` + **`:612-618` retry `vlmClient.ignore-exceptions`** 양쪽에 동일 예외 등록 — 재시도·서킷 집계 모두 제외. 테스트 `VlmClientTest.java:221 badRequest400NotRetried`·`:236 unprocessable422NotRetried`. ※ 카탈로그 근거가 circuitbreaker 만 가리키고 retry 쪽을 빠뜨려 정정함 |
| TC-AIMOCK-44 | PASS | [실동작] 45s 계층 부재 재확인 — `grep -rn BLOCK_TIMEOUT backend/src/main/java` 결과가 `ControlNotifyClient.java:52`(15s) + `ControlNotifyService` 4개 호출부뿐, **VLM 경로 0건**. 단일 타임아웃은 `VlmClient.java:71,76,108`(`timeout-seconds:10` → `.timeout(timeout)`). 논블로킹 제출은 `VlmTimeseriesStep.java:366 .subscribe(...)` → `:381 return VlmTimeseriesResponse.submitted(requestId)`. **무신호 회수도 실동작 확인** — backend 로그 `[Vlm][Reclaim] pending VLM submit reclaimed=2 ackWindowMin=30 callbackWindowMin=360`, DB `ls_batch_proc_log` 에 `VLM/SKIPPED/"VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개"` **5건**. 두 창의 판정 근거인 원장 전이도 실재(`PersistentWebhookIdempotencyLedger.recordAckReceived:78-84` 조건부 UPDATE, `ls_webhook_idempotency` 에 ISSUED/PROCESSED/FAILED 행 관측) |
| TC-AIMOCK-45 | PASS | [실동작] local 실왕복: mock-server `POST /v1/videovlm/describe 200 OK` ↔ backend `accepted rawSn=115` ↔ 콜백 수신 `[Webhook][Vlm] result applied request_id=3aa3c19b-… rawSn=115 new=1 markingsTransitioned=1`. **self-fill 아님**(값이 외부 콜백에서 옴). 공통 기본 SKIPPED 는 `VlmClient.java:94-97` + `application.yml:704 enabled: ${VLM_CLIENT_ENABLED:false}`, local 기본 true 는 `application-local.yml:150`. 테스트 `VlmClientTest.java:171 enabledFalseReturnsSkippedWithoutCall` |
| TC-AIMOCK-46 | PASS | [실동작] backend 로그 `[Batch][VlmTimeseries] withheld — deident report open rawSn=105`, DB `ls_batch_proc_log` 에 `VLM/SKIPPED/"비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"` **10건** 적재. 해당 시각 mock-server 인바운드 describe 0건(외부 호출 없음). 게이트가 `VlmTimeseriesStep.java:303-307` 로 **경로 해석(`:309 resolveDeidentifiedPath`) 직전**·`doSubmit` 내부(오케스트레이터 아님)에 위치해 public 진입점 우회 불가. 재위탁 배선 `VlmWithheldResumeRunnerTest.java:81,93,109,119,137,153`(6케이스, baseline 실패 0건) |

---

## 이전 회차(2026-08-02 2차) 이슈 대조

| 이전 이슈 | 상태 | 근거 |
|---|---|---|
| **G-ISSUE-61** TC-AIMOCK-44 "45s+10s 2계층" 전제가 코드에 없음 | **✅ 해소** | 카탈로그 기대결과가 논블로킹 제출 서술로 교체됨(현 TC-AIMOCK-44). 코드 실측도 일치 — `BLOCK_TIMEOUT` 은 `ControlNotifyClient` 전용 15s 로만 잔존 |
| **G-ISSUE-63** 근거 드리프트 4건 중 G-10 소관 3건 | **🔶 부분 해소 후 재드리프트** | TC-AIMOCK-46(`:113,303-306`)은 정정값이 유효. **TC-AIMOCK-43**(정정값 `application.yml:532-539` → 실제 `538-545`)·**TC-AIMOCK-45**(정정값 `application-local.yml:147` → 실제 `150`)는 그 사이 코드 라인이 다시 밀려 재드리프트. 이번 회차에 재정정 |
| **G-ISSUE-65** TC-AIMOCK-40 외부 URL 대역 검사가 첫 해석 주소만 검사 | **❌ 미해소(이월)** | `ExternalUrlPolicy.java:162 return InetAddress.getByName(normalized);`(relaxed) · `:172 addr = InetAddress.getByName(host);`(strict) 그대로. `getAllByName` 미도입 → G-ISSUE-22 로 이월 |

---

## 이슈

### [G-ISSUE-21] TC-AIMOCK-40 — 클라우드 메타데이터/내부 대역 차단이 IPv4 링크로컬에만 걸려 있어 AWS IPv6 IMDS·CGNAT 대역이 strict 정책에서도 통과한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ExternalUrlPolicy` 의 선언된 목적은 두 가지다 — ①relaxed 에서도 **"IMDS 는 어떤 환경에서도 정상 위탁 대상이 될 수 없다"** (`ExternalUrlPolicy.java:29-37` 보안 메모, `:136-137` javadoc) ②strict 에서 **loopback/사설/링크로컬/메타데이터 대역 전면 차단**(CWE-918 SSRF, `:168`). 외부 연동 base-url 이 클라우드 메타데이터 엔드포인트를 가리키면 어느 정책에서도 기동이 거부돼야 한다.
- **현재 동작(이슈 내용)**: 두 판정 모두 **Java `InetAddress` 의 IPv4 중심 술어**에만 의존해, IPv4 링크로컬(169.254/16)과 IPv6 링크로컬(fe80::/10)만 잡는다.
  ```java
  // ExternalUrlPolicy.java:148-152 (relaxed)
  if (resolved.isLinkLocalAddress() || resolved.getHostAddress().startsWith("169.254.")) { throw ... }
  // ExternalUrlPolicy.java:176-187 (strict)
  if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
      || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) { throw ... }
  String ip = addr.getHostAddress();
  if (ip.startsWith("169.254.")) { throw ... }
  ```
  `Inet6Address.isSiteLocalAddress()` 는 **deprecated 된 `fec0::/10`** 만 판정하고 실제 IPv6 사설 대역인 **ULA `fc00::/7` 은 판정하지 않는다.** `isSiteLocalAddress()`(IPv4)도 10/8·172.16/12·192.168/16 만 보고 **CGNAT `100.64/10`** 은 보지 않는다. 그 결과:
  - **AWS IPv6 IMDS `fd00:ec2::254`** (ULA) — relaxed·strict 양쪽 통과
  - **`100.100.100.200`** (Alibaba Cloud 메타데이터 서버, CGNAT 대역) — strict 통과
  [실동작] 운영 이미지 프로브 3회(모두 DB 무효화로 공유 데이터 무접촉):
  ```
  relaxed + VLM_SERVICE_URL=http://[fd00:ec2::254]:9400
    → [ExternalUrl] 평문 HTTP 전송 … host=[fd00:ec2::254]:9400 → Started AuthoringApplication (통과)
  strict(VLM_ALLOW_INSECURE_URL=false) + https://[fd00:ec2::254]
    → 거부 로그 없음 → Started AuthoringApplication (통과)
  strict + https://100.100.100.200
    → 거부 로그 없음 → Started AuthoringApplication (통과)
  대조군: relaxed + http://169.254.169.254:9400 → exit=1 "링크로컬/클라우드 메타데이터 대역을 가리킵니다" (차단)
  ```
- **재현/확인 경로**:
  ```bash
  # 실행 중 backend 의 env 를 복제해 URL 만 바꾼 일회용 컨테이너로 재현(공유 DB 무접촉)
  docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -v '^$' > /tmp/be.env
  printf 'SPRING_QUARTZ_AUTO_STARTUP=false\nCONTROL_DB_HOST=no-such-db-host\nPORTAL_DB_HOST=no-such-db-host\n' >> /tmp/be.env
  sed -i '' 's|^VLM_SERVICE_URL=.*|VLM_SERVICE_URL=https://[fd00:ec2::254]|' /tmp/be.env
  sed -i '' 's|^VLM_ALLOW_INSECURE_URL=.*|VLM_ALLOW_INSECURE_URL=false|' /tmp/be.env
  docker run --rm --name klid-ssrf-probe --network klid-net --env-file /tmp/be.env klid-backend:latest 2>&1 | grep -E "IllegalState|Started Authoring"
  ```
- **영향**: **CWE-918 (SSRF)**. 트리거 조건은 "운영자가 base-url 환경변수를 그 값으로 지정" 이라 신뢰 경계 안쪽이고 사용자 입력 경유 경로가 아니므로 즉시 착취 가능한 취약점은 아니다. 다만 ①이 정책의 존재 이유가 **설정 사고(오타·복붙·공격자 제어 설정 주입)로 IMDS 를 치는 것을 기동 시점에 막는 것**인데 IPv6 환경의 AWS IMDS 에 대해서는 그 방어가 **성립하지 않는다**(자격증명 탈취로 직결되는 대역) ②KPST·증강·VLM 3개 연동이 같은 판정기를 공유하므로 갭도 3개 연동에 공통이다 ③IPv6 듀얼스택 배포로 전환하면 노출면이 그대로 활성화된다.
- **수정 방향(제안)**: `ExternalUrlPolicy` 의 대역 판정을 **명시적 CIDR 매칭 헬퍼**로 교체한다 — IPv4: `169.254/16`·`127/8`·`10/8`·`172.16/12`·`192.168/16`·**`100.64/10`**·`0/8`, IPv6: `::1`·`fe80::/10`·**`fc00::/7`**·`fec0::/10`(+ IPv4-mapped 는 언랩 후 IPv4 규칙 적용). relaxed 는 그중 **메타데이터·링크로컬 집합**(`169.254/16`, `fe80::/10`, `fd00:ec2::254`)만, strict 는 전체를 거부. `Inet6Address.isSiteLocalAddress()` 에 의존하지 말 것(deprecated 대역만 판정). 회귀 가드는 `VlmUrlPolicyTest.metadataRangeRejectedEvenWhenRelaxed`(현 `:160`) 옆에 IPv6 ULA·CGNAT 케이스를 추가.

### [G-ISSUE-22] TC-AIMOCK-40/41 — (2차 G-ISSUE-65 이월) 호스트의 첫 번째 해석 주소만 대역 검사
- **심각도**: LOW
- **기대 동작(기대효과)**: 호스트명이 복수 주소로 해석될 때 **하나라도 위험 대역이면 거부**되어야 한다. 실제 커넥션이 어느 주소로 갈지는 JDK/OS 의 주소 선택에 달려 있어, 첫 주소만 검사하면 검사 대상과 접속 대상이 달라질 수 있다.
- **현재 동작(이슈 내용)**: 2차 지적 이후 코드 변경 없음.
  ```java
  // ExternalUrlPolicy.java:161-165 (relaxed)
  try { return InetAddress.getByName(normalized); } catch (UnknownHostException | SecurityException e) { return null; }
  // ExternalUrlPolicy.java:170-175 (strict)
  try { addr = InetAddress.getByName(host); } catch (UnknownHostException e) { throw new IllegalStateException(...); }
  ```
  `:148`·`:176-187` 이 그 **단일** `addr` 만 판정한다. 부수적으로 TC-AIMOCK-41 에서 실증한 "relaxed 는 해석 실패를 통과" 규약과 결합하면, 기동 시점에 NXDOMAIN 이던 호스트가 이후 IMDS 로 해석돼도 재검증 지점이 없다(DNS rebinding TOCTOU — 현 설계가 방어 대상으로 선언하지는 않음).
- **재현/확인 경로**: `grep -n "getByName" backend/src/main/java/kr/co/cudo/authoring/common/config/ExternalUrlPolicy.java` → `:162`, `:172` (`getAllByName` 0건).
- **영향**: CWE-918 잔여 표면. 트리거 조건이 설정값(환경변수)이라 신뢰 경계 안쪽이며 실착취 가능성은 낮다.
- **수정 방향(제안)**: `InetAddress.getAllByName(host)` 로 바꿔 해석된 전 주소에 대역 검사를 수행(하나라도 위험 대역이면 거부). relaxed 의 "해석 실패는 통과" 규약은 `getAllByName` 도 `UnknownHostException` 을 던지므로 동일 catch 로 유지 가능. **G-ISSUE-21 과 같은 메서드를 고치는 작업이므로 한 번에 처리할 것.**

### [G-ISSUE-23] TC-AIMOCK-38/39/43/44/45 — 근거 `file:line` 드리프트 5건 (카탈로그 정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 `file:line` 은 판정자가 즉시 대조할 수 있는 실제 위치여야 한다. 어긋나면 엉뚱한 라인을 읽고 거짓 PASS/FAIL 이 난다.
- **현재 동작(이슈 내용)**: 아래 5건이 실제 위치와 불일치. **2차에서 정정한 2건(TC-AIMOCK-43·45)이 그 사이 코드 라인 이동으로 재드리프트**한 것이 포함된다.
  | ID | 카탈로그(정정 전) | 실제 위치 |
  |---|---|---|
  | TC-AIMOCK-38 | `ProfileGatedUrlPolicy.java:121-133,145-151` | `:124-136`(`check`/`policy`) · `:148-151`(`profileAllowsRelaxation`). `:121-123` 은 javadoc |
  | TC-AIMOCK-39 | `ProfileGatedUrlPolicy.java:78-91` | `:81-94`(`verifyRelaxationScope`, throw 는 `:90-93` — 프로브 스택트레이스가 `ProfileGatedUrlPolicy.java:93` 으로 확정). 판정 규칙 본체는 `DeployedEnvironmentDetector.java:63-70` |
  | TC-AIMOCK-43 | `application.yml:532-539` (2차 정정값) | circuitbreaker `vlmClient` 는 `:538-545`, **retry `vlmClient.ignore-exceptions` 는 `:612-618`**(2차 정정값이 retry 쪽을 누락) |
  | TC-AIMOCK-44 | `VlmTimeseriesStep.java` docstring `340-357` / `VlmSubmitPendingSweeper.java:118-124` | 폐지 주석은 `:346-357`(`:340-341` 은 `VlmTimeseriesRequest req = …` 코드). 스위퍼 두 임계 `@Value` 는 `:121-122`, cutoff 적용은 `:192-197` |
  | TC-AIMOCK-45 | `application-local.yml:147` (2차 정정값) | `:150 enabled: ${VLM_CLIENT_ENABLED:true}` (`:151` url, `:152` allow-insecure-url). 공통 기본값은 `application.yml:704` |
  정확했던 근거(대조 완료): `DeidentifyHealthIndicator.java:77-83`/`84-91`/`92-104`/`105-112`, `ExternalUrlPolicy.java:80-82`/`143-153`/`169-181`, `VlmClient.java:71-76`/`88-112`/`94-97`/`106`/`121-127`/`154-169`, `VlmTimeseriesStep.java:60-63`/`303-307`.
- **재현/확인 경로**: `sed -n '81,94p;124,136p;148,151p' backend/src/main/java/kr/co/cudo/authoring/common/config/ProfileGatedUrlPolicy.java` · `sed -n '538,545p;612,618p;704p' backend/src/main/resources/application.yml` · `sed -n '150,152p' backend/src/main/resources/application-local.yml` · `sed -n '121,122p;192,197p' backend/src/main/java/kr/co/cudo/authoring/batch/vlm/VlmSubmitPendingSweeper.java`
- **영향**: 검증 효율·신뢰도 저하(카탈로그 자체의 정합성 결함). 제품 동작 영향 없음. **2회 연속 재드리프트가 관측됐다** — 절대 라인 표기는 코드가 움직일 때마다 낡는다.
- **수정 방향(제안)**: 이번 회차에 5건 모두 카탈로그에 **직접 정정 반영 완료**(담당 라인범위 내 Edit). 재발 방지로는 근거 컬럼에 **라인 대신 심볼명**(`ProfileGatedUrlPolicy#verifyRelaxationScope`, `application.yml resilience4j.retry.instances.vlmClient`)을 병기하는 표기 규약을 검토할 것.

### [G-ISSUE-24] TC-AIMOCK-34 — 벤더 루트 핑이 2xx 가 아니면 전부 DOWN 이라, 실 KPST 루트가 401/403/404 를 주면 상시 DOWN 오탐이 재발한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 이 인디케이터를 KPST 축으로 옮긴 취지 자체가 **"헬스가 위탁 대상이 아닌 주소를 쳐서 DOWN 오탐이 났다"는 사고의 재발 방지**다(`DeidentifyHealthIndicator.java:16-27` javadoc, cudo_246 실측). 헬스는 "위탁 경로가 살아있는가"를 판정해야지 "루트 경로에 200 을 주는 앱인가"를 판정해서는 안 된다.
- **현재 동작(이슈 내용)**: 판정이 `retrieve().toBodilessEntity()` 라 **모든 non-2xx 응답이 예외 → DOWN** 이다.
  ```java
  // DeidentifyHealthIndicator.java:95-104
  kpstWebClient.get().uri("/").retrieve().toBodilessEntity().timeout(PING_TIMEOUT).block();
  return Health.up()....withDetail("mode", "kpst").build();
  ```
  javadoc 은 `:24-27` 에서 "벤더가 보장하는 루트(`/`)만 친다" 고 전제하지만, **루트가 헬스 계약으로 명시된 것이 아니다**(KPST 규격에 헬스 엔드포인트가 없다는 것이 이 설계의 출발점). mock-server 는 루트가 200 이라 로컬에서는 가려진다(실측 `curl localhost:9400/` → 200). 실벤더 루트가 인증 요구(401/403)나 라우트 부재(404)를 반환하면 **비식별 위탁은 정상인데 헬스만 상시 DOWN** 이 되고, 집계 `/actuator/health` 가 DOWN 이면 배포 readiness·모니터링 알림이 통째로 물린다.
- **재현/확인 경로**: 로컬로는 재현 불가(목업 루트가 200). 실벤더 대조 필요. 코드상 판정 분기는 위 인용부이며, 로컬에서 non-2xx 시 DOWN 됨은 `docker pause` 프로브(TC-AIMOCK-37)로 간접 확인됨.
- **영향**: 가용성 오탐(운영). 보안 영향 없음. UNCERTAINTIES #13 의 "IntelliVIX/KPST 실서버 대조 미완" 과 같은 뿌리 — 실벤더 응답 계약 확인 전까지 확정 불가.
- **수정 방향(제안)**: ①KPST 벤더에 **루트 응답 계약(상태코드)** 을 서면 확인하고 그 값을 javadoc·케이스에 명시, 또는 ②`.exchangeToMono(...)` 로 바꿔 **"응답을 받았다"(=TCP+HTTP 왕복 성립)** 를 UP 조건으로 삼고 5xx·연결 실패만 DOWN 으로 판정(4xx 는 `details` 에 상태코드만 남기고 UP). ②가 "위탁 경로 생존" 이라는 헬스의 목적에 더 맞는다. 어느 쪽이든 **실벤더 확인 없이 임의 확정하지 말 것.**

### [G-ISSUE-25] TC-AIMOCK-37 — DOWN 상세의 `error` 가 Reactor 래퍼 클래스명으로 뭉개져 장애 원인을 구분할 수 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 헬스의 `error` 디테일은 CWE-209 를 지키면서(스택트레이스·주소·내부경로 미노출) **운영자가 1차 분류를 할 수 있어야** 한다 — 타임아웃인지, DNS 실패인지, 연결 거부인지, 인증 실패인지.
- **현재 동작(이슈 내용)**: `.block()` 이 checked 예외를 `reactor.core.Exceptions$ReactiveException` 으로 감싸므로 `e.getClass().getSimpleName()` 이 **원인이 아니라 래퍼 이름**을 낸다.
  ```java
  // DeidentifyHealthIndicator.java:105-112
  } catch (Exception e) {
      return Health.down()....withDetail("error", e.getClass().getSimpleName()).build();
  ```
  [실동작] `docker pause klid-mock-server` → `{"status":"DOWN","details":{"service":"deidentify","mode":"kpst","error":"ReactiveException"}}`. 동일 현상이 `controlNotifyHealth` 에서도 관측됨(`"error":"ReactiveException"`). 실제 원인(`TimeoutException`)은 응답 어디에도 없다.
- **재현/확인 경로**:
  ```bash
  docker pause klid-mock-server
  curl -s -H "Authorization: Bearer <REVIEWER JWT>" http://localhost:18081/api/actuator/health   # → error: ReactiveException
  docker unpause klid-mock-server
  ```
- **영향**: 관측성 저하(운영 트리아지 지연). 케이스의 기대결과("예외 simpleName 만, 스택트레이스 미노출")는 충족하므로 TC-AIMOCK-37 판정 자체는 PASS. 보안 영향 없음(오히려 과소 노출).
- **수정 방향(제안)**: `Exceptions.unwrap(e)` 로 원인을 벗긴 뒤 그 클래스의 simpleName 을 노출하거나, `WebClientResponseException` 이면 상태코드만 함께 남긴다(`error=TimeoutException` / `error=WebClientResponseException(404)`). 메시지 본문·주소는 계속 노출하지 않는다. `controlNotifyHealth` 등 동일 패턴 인디케이터도 함께 정비.

---

## 카탈로그 정정 내역 (담당 라인범위 내 Edit — 5건)

| 케이스 | 정정 전 | 정정 후 |
|---|---|---|
| TC-AIMOCK-38 | `ProfileGatedUrlPolicy.java:121-133,145-151` | `ProfileGatedUrlPolicy.java:124-136(check/policy),148-151(profileAllowsRelaxation)` |
| TC-AIMOCK-39 | `ProfileGatedUrlPolicy.java:78-91` | `ProfileGatedUrlPolicy.java:81-94(throw 90-93) / DeployedEnvironmentDetector.java:63-70` |
| TC-AIMOCK-43 | `application.yml:532-539` | `application.yml:538-545(circuitbreaker.vlmClient),612-618(retry.vlmClient)` |
| TC-AIMOCK-44 | `…docstring 340-357…` / `VlmSubmitPendingSweeper.java:118-124` | `…주석 346-357 · .subscribe 366 · submitted 반환 381…` / `VlmSubmitPendingSweeper.java:121-122(두 임계 @Value),192-197(두 cutoff 적용)` |
| TC-AIMOCK-45 | `application-local.yml:147` | `application.yml:704(공통 기본 false) / application-local.yml:150(local 기본 true)` |

기대결과·전제 문구는 이번 회차 실측과 모두 일치해 **문구 정정 0건**(TC-AIMOCK-44 는 2차 G-ISSUE-61 반영분이 이미 정확).

---

## 환경 조작·복원 기록 (다른 검증 에이전트 영향 여부)

| 조작 | 지속 시간 | 복원 확인 |
|---|---|---|
| `docker pause klid-mock-server` → `unpause` | 약 3초 | 직후 `/api/actuator/health` → `UP`(deidentify `mode=kpst`), `curl localhost:9400/health` → 200 |
| 일회용 backend 프로브 컨테이너 7개(`klid-guard-test`,`klid-p39b`,`klid-p40`,`klid-p40a/b/c`,`klid-p41a/b`) | 각 40~60초 | 전부 `docker rm -f` 로 제거 — `docker ps -a` 에 잔존 0건. Quartz 비활성(`SPRING_QUARTZ_AUTO_STARTUP=false`), 부팅 완주 예상 케이스는 DB 호스트 무효화로 공유 데이터 무접촉 |
| 본 스택 최종 상태 | — | `klid-backend/frontend/ai-server/mock-server/postgres` 전부 healthy, `/api/actuator/health` UP |

프로덕션 코드·설정·테스트 파일 수정 0건. 빌드/테스트 실행 0건(자동테스트 통과 여부는 `_raw/test-baseline.md` 대조).
