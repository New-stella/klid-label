# A 클러스터 검증 결과 — part3 (A-3 스트림 서명 필터 · A-4 웹훅 인증 필터)

- 담당 구간: `docs/test-cases/A-auth-common.md` §A-3 (TC-STREAM-001~012, 12건) + §A-4 (TC-HMAC-001~046, 46건) = **58건**
  (그중 `TC-HMAC-001` 은 **폐기** 케이스라 검증 대상 제외 → **실검증 57건**)
- 검증 일시: 2026-08-01
- 검증 방식: **실동작 우선**(실행 중 스택에 curl 직접 요청 · DB 조회 · 컨테이너 로그 · `/actuator/prometheus` 실측) + 정적 대조 + 자동테스트 커버 대조
- 판정 요약: **PASS 56 · PARTIAL 1 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0** (폐기 1 제외)
- 신규 이슈: **A-ISSUE-41 ~ A-ISSUE-43 (모두 LOW)**

---

## 0. 환경 게이트 · 버전 격차 판정

| 항목 | 실측 |
|---|---|
| 스택 | `klid-backend`(:18081→8080, context-path `/api`) · `klid-postgres` · `klid-mock-server` · `klid-ai-server` · `klid-frontend` 전부 healthy |
| 활성 프로파일 | `SPRING_PROFILES_ACTIVE=local` |
| 웹훅/스트림 실효 환경변수 | `WEBHOOK_HMAC_SECRET_AUGMENT=68e759de…`(64B, placeholder 아님) · `WEBHOOK_TRUSTED_PROXY_CIDRS=none` · `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none` · `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0` · `STREAM_SIGN_SECRET=b49f3ef9…`(64B) |
| 가드 테이블 | `public.ls_whk_sign_use` · `public.ls_whk_fail_nmtm` 실재 |
| 기동 로그 | `[Webhook] 생성형 AI 콜백 IP allowlist 활성 count=1` · `[Webhook] guard purge job scheduled intervalMs=600000` |

### ★ 버전 격차 영향 — **이 구간은 영향 없음 (BLOCKED 0건)**

컨테이너는 2026-07-31 빌드(flyway V146)지만, 담당 구간 소스는 **그 이후 단 1커밋도 변경되지 않았다**:

```
$ git log --oneline --since="2026-07-30" -- backend/src/main/java/kr/co/cudo/authoring/common/security/
(출력 없음)
```

즉 실행 중 바이너리 == 검증 대상 소스(qa-0801 워킹트리)이므로 A-3/A-4 전건 실동작 판정이 유효하다.
지시받은 **mock-server 코드 변경의 계약 영향**도 확인했다 — `/v1/vlm/callback`·`/v1/genai/callback` 인증 계약은 **무서명 3계층**(IP allowlist → rate limit + size cap → `request_id` 발급 게이트)이고, mock-server 는 이 계약의 *호출자*일 뿐 인증 헤더를 만들지 않는다. 실제 mock-server 발 콜백이 `POST /api/v1/vlm/callback 200` · `POST /api/v1/genai/callback 200` 으로 왕복 성공하고 있음이 `stack-bringup.md` 및 backend 로그에서 확인되어 **계약 파손 없음**.

### 실동작 검증에 사용한 클라이언트 IP (rate limit 격리용)

`webhook.trusted-proxy-cidrs=none` 이라 클라이언트 IP = `remoteAddr` 이므로, 분당 5회 임계에 서로 오염되지 않도록 **4개 소스 IP**를 나눠 썼다:
`192.168.65.1`(macOS 호스트) · `172.20.0.4`(klid-ai-server) · `172.20.0.6`(klid-frontend) · `0:0:0:0:0:0:0:1`(backend 자기 localhost).

---

## 1. A-3. 스트림 서명 필터 (StreamSignatureFilter · StreamNonceCookie) — 12건

### 실동작 근거 원문 (핵심)

발급(`GET /api/v1/videos/4/stream-url`, REVIEWER 1001 JWT):

```
{"success":true,"data":{"url":"/api/v1/videos/4/stream?exp=1785577978&u=1001&sig=59341984…","expiresAt":…,"ttlSeconds":60}}
Set-Cookie: klid_stream_nonce=e6e49d49…..43670c07…; Path=/api/v1/videos; Max-Age=3600;
            Expires=…; HttpOnly; SameSite=Lax
```

동일 URL 에 대한 변형 요청 응답:

| 조건 | 응답 |
|---|---|
| 유효 sig + 유효 nonce 쿠키 + Range | **206** |
| 유효 sig, **쿠키 없음** | **401** |
| sig/exp 없음 (쿠키만) | **401** |
| sig 첫 글자 변조 | **401** |
| `u=1001`→`u=2001` 변조 | **401** |
| 봉인 없는 32-hex 쿠키(`aaaa…`) | **401** |
| 정상 nonce + 잘못된 seal(64×`b`) | **401** |
| 1001 로 봉인된 쿠키 + `u=2002` 서명 | **401** |
| rawSn 19자리(`1234567890123456789`) | **401** |
| 동일 URL 에 **POST** | **401** |
| `Authorization: Bearer` + sig 없음 | **206** |
| 경로 인코딩 변형 `/v1/videos/5/%73tream` | **401** (필터 미매칭 → 컨텍스트 미설정 → fail-closed) |

backend 로그(sig 전문 미노출 확인):

```
DEBUG k.c.c.a.c.s.StreamSignatureFilter - [StreamSign] invalid signature rawSn=4
```

### 판정표

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| TC-STREAM-001 | PASS | [실동작] 유효 sig+쿠키 → **206**. principal 이 실발급자로 채워짐은 TC-STREAM-012 의 403/206 분기로 역증명(`u=2002`→WORKER 로 재조회되어 미배정 403, 배정본은 206) | `StreamSignatureFilter.java:126-148` 일치 |
| TC-STREAM-002 | PASS | [실동작] sig/exp 부재 → **401** (필터 미개입 + 보안체인 거부) | `:108-114` |
| TC-STREAM-003 | PASS | [실동작] 변조 sig → 401 + 로그 `[StreamSign] invalid signature rawSn=4` — **sig 전문·쿠키값 미출력**(CWE-532 방어 실효) | `:149-152` |
| TC-STREAM-004 | PASS | [실동작] `u` 만 변조 → **401**. "URL 전체 재사용 차단"은 nonce 쿠키 담당임도 TC-STREAM-008 로 실증 | `:124-132` |
| TC-STREAM-005 | PASS | [실동작] Bearer 토큰 + sig 없음 → **206**(서명검증 스킵). `SecurityConfig.java:166` `addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class)` 확인 | 익명 필터보다 앞 배치 확인 |
| TC-STREAM-006 | PASS | [실동작] 동일 URL POST → 401(미개입), 비-stream 경로(`/v1/videos/4`)에 서명 부착 → 401 | `:84-89` |
| TC-STREAM-007 | PASS | [실동작] 19자리 rawSn → **401** (정규식 `\d{1,18}` 미매칭 → 패스스루 → fail-closed) | `:50,116-122` |
| TC-STREAM-008 | PASS | [실동작] URL 전량 확보 + 쿠키 없음 → **401**. 발급 응답 `url` 에 nonce 미포함 확인 | `:127-132`, `StreamNonceCookie.java:98-126` |
| TC-STREAM-009 | PASS | [실동작] ①봉인 없는 임의 32-hex ②nonce+오류 seal ③타 subject 봉인 쿠키 **3종 전부 401**. `unseal` 은 `MessageDigest.isEqual` 상수시간 비교 | `StreamNonceCookie.java:132-155,167-169`. ⚠ 파생 관측 → **A-ISSUE-41** |
| TC-STREAM-010 | PASS | [실동작] `HttpOnly` ✓ `SameSite=Lax` ✓ `Path=/api/v1/videos`(=ctx+/v1/videos) ✓ `Max-Age=3600` ✓ / **Secure 미부여**(현재 `local` 프로파일 — 기대 규약과 일치). 판정축이 `request.isSecure()` 가 아니라 프로파일임은 `:89,218` + `StreamNonceCookieSecureFlagTest`(3건, prd 평문HTTP 에도 Secure 부여) | `StreamNonceCookie.java:214-224` |
| TC-STREAM-011 | PASS | [정적] `deriveSealKey()` 가 빈 시크릿에 **JVM 기동 랜덤 32B** 반환 → 어떤 쿠키도 봉인검증 불통과. 동시에 `StreamUrlSigner.configured=false` → `verify()` 가 즉시 false. **이중 fail-closed**. ⚠ 실동작은 컨테이너 재기동이 필요해 미수행(설정 변경 금지 규칙) | `StreamNonceCookie.java:183-190`, `StreamUrlSigner.verify()` |
| TC-STREAM-012 | PASS | [실동작] **서명 자체를 위조**(`STREAM_SIGN_SECRET` 로 sig + seal 직접 계산)해 미배정 WORKER 2002 로 rawSn=4 요청 → **403 `본인에게 배정되지 않은 영상입니다.`**. 동일 방식 REVIEWER 1001 → 206. 서명 컨텍스트로 `/v1/videos/4`(타 API) 호출 → **401**(ROLE_* 미부여로 확대 불가) | `:139-148,160-169` + `LabelAccessGuard.verifyRawAccess` |

> ⚠ **오판 방지 기록**: 최초에 "미배정 WORKER"로 `2002 × rawSn=5` 를 골라 206 을 받고 IDOR 로 의심했으나, `ls_task_assignment` 를 **LIMIT 없이** 재조회한 결과 `assignment_id=7 | 2002 | 5 | LABELER` 로 **실제 배정된 조합**이었다. 진짜 미배정 조합(`2002 × rawSn=4`)으로 재시험해 403 을 확인했다. B-ISSUE-63 은 **해소 상태 유지**.

---

## 2. A-4. 웹훅 인증 필터 (HmacWebhookFilter · 무서명 가드 · WebhookGate) — 46건

### 실동작 근거 원문 (핵심)

**① 퍼센트 인코딩 경로 변형이 필터에 걸리는가 (CRITICAL 회귀 가드, TC-HMAC-022)** — chunked 전송으로 프로빙(필터가 적용돼야만 411 이 나옴):

```
POST /api/v1/vlm/callback        (chunked) → 411 LENGTH_REQUIRED
POST /api/v1/%76lm/callback      (chunked) → 411 LENGTH_REQUIRED   ← 필터 적용됨
POST /api/v1/vlm/%63allback      (chunked) → 411 LENGTH_REQUIRED   ← 필터 적용됨
POST /api/v1/g%65nai/callback    (chunked) → 411 LENGTH_REQUIRED   ← 필터 적용됨(rate limit 소진 시 429)
```

backend 로그가 **원시 URI 를 그대로(sanitize 후) 남기면서도 판정은 정규화 경로로** 했음을 보여준다:

```
WARN HmacWebhookFilter - [Webhook] unsigned callback missing/chunked Content-Length path=/api/v1/%76lm/callback
WARN HmacWebhookFilter - [Webhook] unsigned callback missing/chunked Content-Length path=/api/v1/vlm/%63allback
```

**② 반대 방향(오탐) 확인 — 허용 경로가 과잉 매칭되지 않는가**:

| 요청 | 응답 | 해석 |
|---|:--:|---|
| `/v1/VLM/callback` (대문자) | **401** | PathPattern 대소문자 구분 → 필터 미적용, `/v1/**` 인증 요구로 fail-closed |
| `/v1/vlm/callback/` (후행 슬래시) | **411** | 필터는 적용(`/v1/vlm/**`), MVC 라우팅은 별개 |
| `/v1/%2576lm/callback` (이중 인코딩) | **401** | 디코딩 1회 후 `%76lm` ≠ `vlm` → 미적용 + fail-closed |
| `/v1/vlm/callback%00` | **400** | Tomcat 선차단 |
| `/v1/vlm/call%zzback`, `/v1/vlm/callback%` | **400** | Tomcat 이 URI 파싱 단계에서 선차단 → "경로 판정 불가 → 401" 분기는 HTTP 로 도달 불가(더 엄격) |

또한 `/v1/vlm/**`·`/v1/genai/**` 하위에 **콜백 2종 외 다른 컨트롤러가 없음**을 확인(`grep RequestMapping("/v1/vlm|/v1/genai"` → `VlmResultController` · `GenAiCallbackController` 뿐) — allowlist 접두 패턴이 무관한 엔드포인트를 잘못 삼키지 않는다.

**③ 본문 상한 비대칭 (TC-HMAC-018 / -035)**:

```
POST /v1/genai/callback  CL=1,572,864 → 413 "Webhook 본문은 1024KB 를 초과할 수 없습니다."
POST /v1/vlm/callback    CL=5,242,880 → 413 "Webhook 본문은 4096KB 를 초과할 수 없습니다."
POST /v1/vlm/callback    CL=2,097,152 → 400 (INVALID_INPUT — size cap 통과해 컨트롤러 도달)
```

**④ 무서명 경로 최종 관문 = request_id 발급 게이트 (TC-HMAC-016 / -033)**:

```
POST /v1/vlm/callback   {"request_id":"FORGED-QA-0801-C","status":"completed","results":[…]} → 401
POST /v1/genai/callback {"request_id":"FORGED-QA-0801-B",…}                                   → 401
로그: [Webhook][Vlm] unknown request_id=FORGED-QA-0801-C
      [Webhook] downstream auth rejected ip=172.20.0.4 path=/api/v1/vlm/callback status=401
```

**⑤ rate limit 실동작 (TC-HMAC-012 / -032 / -034)** — 소스 IP 별 6연타:

```
[backend localhost, IPv6 ::1 → genai allowlist(0.0.0.0/0) 밖]
 req1..5 → 403(ip_not_allowed)   req6 → 429 Retry-After: 60   req7 → 429 Retry-After: 60

[macOS 호스트 192.168.65.1, 매 요청 X-Forwarded-For 를 203.0.113.1..6 으로 회전]
 req1..5 → 401(downstream)       req6 → 429 Retry-After: 60   req7 → 429 Retry-After: 60
```

공유 집계 테이블 실측 — **XFF 위조값이 키로 적재되지 않고 remoteAddr 로 수렴**:

```sql
select call_ip_addr, bgng_dt, fail_nmtm from ls_whk_fail_nmtm order by bgng_dt desc;
 172.20.0.4      | 2026-08-01 18:50:00 | 2
 0:0:0:0:0:0:0:1 | 2026-08-01 18:50:00 | 5
 192.168.65.1    | 2026-08-01 18:50:00 | 5    ← 203.0.113.x 는 한 행도 없음
```

**⑥ nonce 테이블 pre-auth write 없음 (TC-HMAC-026)** — 위 모든 실패 프로빙(수십 건) 이후:

```sql
select count(*) from ls_whk_sign_use;  →  0
```

**⑦ 메트릭 태그 저카디널리티 (TC-HMAC-043)** — `GET /api/actuator/prometheus` (REVIEWER 토큰, 200):

```
webhook_auth_failed_total{path="genai",reason="downstream_unauthorized"} 6.0
webhook_auth_failed_total{path="genai",reason="ip_not_allowed"} 5.0
webhook_auth_failed_total{path="vlm",reason="downstream_unauthorized"} 12.0
```
→ 원시 URI(`/api/v1/%76lm/callback` 등)를 수십 회 던졌음에도 **`path` 태그는 `vlm`/`genai` 상수 2종뿐**. Meter 폭주 없음.

**⑧ 필터 단일 실행 (TC-HMAC-044)** — 요청 1건당 필터 WARN 로그가 **정확히 1줄**(이중 등록 시 2줄). `WebhookGateConfig.java:42-49` 의 `setEnabled(false)` 와 정합.

### 판정표

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| ~~TC-HMAC-001~~ | — | — | **폐기 케이스**(검증 대상 아님). `/v1/aug/callback` 컨트롤러·DTO 부재 재확인 |
| TC-HMAC-002 | PASS | [정적] `HmacWebhookFilter.java:214-224`(경로 0개면 미설정 허용, 기동 성공) + `:311-318`(런타임 `secret_missing` 401). 실기동이 시크릿 없이 정상 부팅한 것은 아니나(현재 값 설정됨) 로직 분기 명확. 테스트: `HmacWebhookFilterTest` "서명필수_경로가_없으면_HMAC_시크릿_없이도_기동한다" / "경로_판정_불가_요청은_여전히_401_이다" | 판정 불가 요청은 Tomcat 400 선차단이라 HTTP 도달 불가(더 엄격) |
| TC-HMAC-003 | PASS | [정적] `:240-245` `bytes < 32 → BeanInitializationException`, 경로 유무와 무관. 테스트 "시크릿_32바이트_미만_시_부팅_실패" | |
| TC-HMAC-004 | PASS | [정적] `:333-340` `missing_signature` 401 + `recordFailure`. 테스트 "보호대상_경로에서_서명이_없으면_401_이며_필터가_스킵되지_않는다" | |
| TC-HMAC-005 | PASS | [정적] `:341-346` `unsupported_algorithm` 401 + recordFailure | |
| TC-HMAC-006 | PASS | [정적] `:349-357` `timestamp_malformed` 401 + recordFailure | |
| TC-HMAC-007 | PASS | [정적] `:360` `ageMs > windowSeconds*1000 \|\| ageMs < -FUTURE_SKEW_MS(30s)` — **비대칭 확인**(`Math.abs` 아님). 테스트 "미래_timestamp_는_시계오차_범위를_넘으면_401" | 구 ±5분 대칭 폐기 확인 |
| TC-HMAC-008 | PASS | [정적] `:391` `MessageDigest.isEqual(providedBytes, computedBytes)` — **상수시간 비교**. hex 문자열을 바이트로 비교하므로 길이 불일치도 안전 처리. 테스트 "HMAC_시그니처_틀리면_401" | 타이밍 공격 방어 실효 |
| TC-HMAC-009 | PASS | [정적] 서명 경로 `:321-326` → **401** `missing_content_length` / [실동작] 무서명 경로 → **411** — 정책차 실측 확인 | |
| TC-HMAC-010 | PASS | [정적] `:98,327-331` 선언 CL > 1MB → 413 | 서명 경로는 HTTP 도달 불가라 정적 |
| TC-HMAC-011 | PASS | [정적] `:368-375` + `readCapped` `:726-740` — 8KB 청크 누적, 상한 초과 **즉시 중단**(전량 버퍼링 없음). 테스트 "Content_Length_위조해도_스트림_상한_초과시_413", "실제_스트림은_상한까지만_읽고_중단_bounded_read" | |
| TC-HMAC-012 | PASS | [실동작] 실패 5회 → 6회째 **429 + `Retry-After: 60`**(2개 IP 에서 각각 재현). 공유 DB 행(`fail_nmtm=5`) 실측 — **로컬+공유 2단 동작 확인**. `:305-309,666-672`, `WebhookRateLimiter.java:68-71,95-114` | |
| TC-HMAC-013 | PASS | [정적] `reset()` 호출처는 `:417`(**서명 검증 성공 직후**) 1곳뿐. `WebhookRateLimiter.java:207-214` 가 현재+직전 버킷 모두 삭제(조회 범위와 대칭). 무서명 경로 200 은 `countDownstreamAuthOutcome` 이 조기 return 만 하고 reset 미호출(`:557-559`). 테스트 "알려진_request_id_의_200_을_끼워넣어도_실패카운터가_초기화되지_않아_차단이_유지된다" | |
| TC-HMAC-014 | PASS | [정적] `WebhookRateLimiter.java:74,256-273` — 만료 removeIf → 그래도 초과 시 `windowStartMs` 오름차순 evict. 테스트 "FailureTracker_4096_초과_시_hard_cap_적용" | |
| TC-HMAC-015 | PASS | [정적+실동작] `WebhookProtectedPaths.java:100-103,227-233` `[\r\n\t]→_` + 200자 절단. 실로그에서 원시 URI 가 한 줄로만 출력됨 확인 | CR/LF 주입은 Tomcat 이 URI 단계에서 400 선차단 |
| TC-HMAC-016 | PASS | [실동작] 무서명 VLM 콜백이 HMAC/timestamp/nonce 없이 컨트롤러 도달 → 최종 `request_id` 게이트가 **401**. 가드 순서 `:480-527`(rate limit → IP → CL → size cap) 정적 확인 | |
| TC-HMAC-017 | PASS | [실동작] chunked VLM 콜백 → **411 LENGTH_REQUIRED** + `recordFailure`(DB 카운터 증가 확인) | ⚠ 메트릭 미기록 → **A-ISSUE-43** |
| TC-HMAC-018 | PASS | [실동작] CL=5MB → **413**(선언 CL 단계). 2MB 는 통과해 400 도달(캡이 4MB 임을 양방향 확인). 스트림 2단 캡은 `:521` `new CachedBodyHttpServletRequest(request, maxBytes, false)` | |
| TC-HMAC-019 | PASS | [실동작] 대문자·이중인코딩 변형 → 필터 미적용 후 **fail-closed 401**. [정적] `:254-263` catch 시 `return false`(=보호), `WebhookProtectedPaths.java:155-161` 판정 불가 시 `true` | |
| TC-HMAC-020 | PASS | [정적] `:574-576` `hmacSha256Hex → HmacSigner.hex` 위임(단일 진실원), `HmacSigner.java:42-53` | |
| TC-HMAC-021 | PASS | [정적] `HmacSigner.java:48-52` — 예외 메시지에 `algorithm=HmacSHA256` 만, 시크릿 평문 없음 | |
| TC-HMAC-022 | PASS | [실동작] `/v1/%76lm/callback` · `/v1/vlm/%63allback` · `/v1/g%65nai/callback` **3종 모두 411**(=필터 적용). 컨트롤러 무인증 관통 재현 불가. `WebhookProtectedPaths.java:253-269` | **CRITICAL 회귀 가드 유효** |
| TC-HMAC-023 | PASS | [정적] `WebhookGateInterceptor.java:54-60` — 래퍼 부재 시 `preHandle` 401(=`@RequestBody` 역직렬화 이전). 테스트 `WebhookGateInterceptorTest` 7건(“필터_증거_래퍼가_없는_요청은_401_컨트롤러_미도달”, “VLM_경로도_증거_래퍼가_없으면_401”) | 필터가 항상 선행하므로 HTTP 로는 재현 불가(=설계 의도) |
| TC-HMAC-024 | PASS | [정적] `:51-53` `DispatcherType.ASYNC` 조기 통과. 테스트 "ASYNC_재디스패치는…401_이_아님" + "REQUEST_디스패치는_증거_래퍼가_없으면_여전히_401_ASYNC_예외가_우회로가_아님" | |
| TC-HMAC-025 | PASS | [정적] `:398-415` 최초 200 / 이후 **409**, `nonceHash(path.canonical(), ts, sig)` `:584-596` 가 **정규화 경로** 사용. 테스트 "동일_서명_재전송시…replay_로_흡수", "nonce_키는_원시URI가_아니라_정규화_경로로_계산됨" | |
| TC-HMAC-026 | PASS | [실동작+정적] 위조/실패 프로빙 수십 건 후 `select count(*) from ls_whk_sign_use` → **0**. 코드상 nonce 소비는 `:398` 서명 검증 성공 이후에만 | pre-auth write DoS 차단 실증 |
| TC-HMAC-027 | PASS | [정적] `:404-409` `WebhookGuardUnavailableException` → `writeServiceUnavailable` `:647-652`(503 + `Retry-After: 30`). 테스트 "nonce_저장소_장애시_fail_closed_503_이며_통과하지_않음" | |
| TC-HMAC-028 | PASS | [정적] `:418-443` `committed = status < 500`, `finally { if(!committed) releaseNonce(...) }`, release 실패는 요청 결과 불변. 테스트 3건 | |
| TC-HMAC-029 | PASS | [정적] `:157-166` placeholder 4종 + `:234-239` non-local 프로파일 기동 차단. 실효 시크릿은 64B 난수라 해당 없음. 테스트 "리포에_커밋된_공개_placeholder_시크릿은_local_이_아닌_프로파일에서_부팅_실패" | |
| TC-HMAC-030 | PASS | [정적] `:227-233` `secret.startsWith("?")` → 기동 차단. 테스트 "해석되지_않은_placeholder_기본값_물음표_시크릿은_부팅_실패" | |
| TC-HMAC-031 | PASS | [정적] `GenAiWebhookIpAllowlist.java:44-77` — 빈 값/`none` 모두 `allowed.isEmpty()` → `isAllowed()` **항상 false**(fail-closed). `WebhookIpAllowlist.java:80-98` 는 반대로 비면 **항상 true**(의도된 비대칭). 테스트 `GenAiWebhookIpAllowlistTest` 4건. [실동작 보강] 현재 `0.0.0.0/0` 설정에서 **IPv6 발신(::1)이 403** 으로 거부됨 — allowlist 가 실제로 게이트로 작동함을 확인 | |
| TC-HMAC-032 | PASS | [실동작] 비허용 IP 403 ×5 → **6회째 429**. 메트릭 `webhook_auth_failed_total{path="genai",reason="ip_not_allowed"} 5.0`. `:486-500` | |
| TC-HMAC-033 | PASS | [실동작] 위조 `request_id` 401 ×5 → 6회째 429, 메트릭 `reason="downstream_unauthorized"`. [정적] `:556-564` 2xx 는 조기 return(=reset 안 함) | |
| TC-HMAC-034 | PASS | [실동작] **XFF 를 매 요청 회전(203.0.113.1~6)해도 6회째 429**. 공유 카운터에 위조 IP 행 0건, `192.168.65.1` 로만 5 적재. `isClientIpAttributable` 류 게이트 부재 확인(`ClientIpResolver.java:55-61` 주석 + 코드 전수 확인) | 헤더 한 줄로 통제 무력화 불가 |
| TC-HMAC-035 | PASS | [실동작] genai CL=1.5MB → **413 "1024KB"**, VLM 은 같은 크기 통과(4MB 캡) — 상한 비대칭 실증. `:121-130,476` | |
| TC-HMAC-036 | **PARTIAL** | [실동작] XFF 폐기 + remoteAddr 채택 ✓ / 프로세스당 1회 WARN ✓. **다만 `webhook.trusted-proxy-cidrs=none` 을 명시했는데도 "비어 있습니다 … 'none' 을 명시하세요" WARN 이 출력**됨(운영자가 이미 요구대로 설정한 상태에서 설정 누락 경보) | → **A-ISSUE-42** |
| TC-HMAC-037 | PASS | [정적] `ClientIpResolver.java:150-167` 우측부터 신뢰 홉 제거 → 최초 비신뢰 값 채택, 비정상 값 시 remoteAddr 폴백. 테스트 "신뢰프록시_뒤에서는_XFF_클라이언트별로_rate_limit_이_독립_적용" | 현 환경은 `none` 이라 실동작 미도달 |
| TC-HMAC-038 | PASS | [정적] `:71,74`(`MAX_XFF_LENGTH=1024`, `MAX_XFF_HOPS=20`) + `:147`(길이 초과 시 헤더 무시) + `:153`(`.limit(20)`) | |
| TC-HMAC-039 | PASS | [정적] `:126-138` 비-IP remoteAddr → `unknown` sentinel + `:229-236` 1회 WARN. `isValidIp` 가 `WebhookCidrParser.isIpLiteral` 구조 검증 사용(DNS 조회 유입 차단). 테스트 3건 | |
| TC-HMAC-040 | PASS | [정적] `:93-107` + `WebhookConfigProfiles.java:32,38-44`(`{prd, stg}`). 테스트 "prd_에서_신뢰프록시_CIDR_미설정이면_기동_실패", "stg_에서도…기동_실패", "none_으로_명시하면_기동_가능" | 현 프로파일 local 이라 미적용이 정상 |
| TC-HMAC-041 | PASS | [정적] `WebhookIpAllowlist.java:46-61` 동일 구조. 테스트 "prd_에서_VLM_IP_allowlist_미설정이면_기동_실패" | |
| TC-HMAC-042 | PASS | [정적] `WebhookCidrParser.parseStrict` → 형식 오류 시 `BeanInitializationException`(프리픽스 비숫자·주소폭 초과·호스트명 전부). 테스트 "신뢰프록시_CIDR_오타는…기동이_차단되며_사유가_메시지에_드러난다", "VLM_allowlist_CIDR_오타는_전면허용으로_떨어지지_않고…" | |
| TC-HMAC-043 | PASS | [실동작] `/actuator/prometheus` 실측 — 태그가 `path∈{vlm,genai}` · `reason` 상수뿐. 원시 URI 변형 다수 투입에도 Meter 증식 0. `:136,610-622`, `WebhookProtectedPaths.java:73-75,198-207` | 관련 관측 → **A-ISSUE-43** |
| TC-HMAC-044 | PASS | [실동작+정적] 요청 1건당 필터 로그 정확히 1줄(이중 실행 없음). `WebhookGateConfig.java:42-49` `setEnabled(false)`, `SecurityConfig.java:162` 단일 등록. 추가 확인: `WebSecurityCustomizer`/`web.ignoring`/추가 `SecurityFilterChain` **부재**(전 소스 grep) | |
| TC-HMAC-045 | PASS | [실동작] 기동 로그 `[Webhook] guard purge job scheduled intervalMs=600000`. [정적] `WebhookGuardPurgeJob.java:36-56` 데몬 executor + `Math.max(60_000, …)` clamp + 초기지연 300000. `JdbcWebhookGuardStore.java:72,88` 조건부 DELETE(2노드 무해) | |
| TC-HMAC-046 | PASS | [정적] nonce = `:404-409` **503 fail-closed** / rate limit = `WebhookRateLimiter.java:137-140` + `JdbcWebhookGuardStore.java:139-143` **`UNAVAILABLE` fail-open**. 테스트 "nonce_저장소_장애시_fail_closed_503", "rate_limit_공유저장소_장애시_fail_open_으로_정상_콜백이_통과" | 비대칭 의도 확인 |

---

## 3. 반증 시도 요약 (확증편향 차단 — 시도했으나 결함이 아니었던 것)

| # | 반증 가설 | 실측 결과 | 결론 |
|---|---|---|---|
| 1 | HMAC 비교가 `String.equals` 라 타이밍 공격 가능 | `MessageDigest.isEqual` 사용(`:391`), 스트림 서명도 동일(`StreamUrlSigner`), 쿠키 봉인도 동일(`StreamNonceCookie:154`) | 결함 아님 |
| 2 | URL 인코딩으로 웹훅 필터 스킵(E-ISSUE-01 재발) | `%76lm`·`%63allback`·`g%65nai` 3종 전부 411(필터 적용). 이중인코딩·대문자는 필터 미적용이지만 `/v1/**` 인증으로 401 fail-closed | 결함 아님 |
| 3 | 스트림 필터도 원시 URI 매칭이라 `%73tream` 로 우회 | `/v1/videos/5/%73tream` → **401**(컨텍스트 미설정). 서명 인증이 *부여되지 않는* 방향의 미스매치라 fail-closed | 결함 아님 |
| 4 | nonce replay 방지가 실제로는 안 걸린다 | 서명 필수 경로가 0개라 HTTP 로 도달 불가. 대신 **nonce 테이블 0행**(pre-auth write 없음)을 실측하고, 정규화 경로 키·409 응답·5xx 해제를 단위테스트 6건으로 대조 | 결함 아님(경로 부재는 의도) |
| 5 | XFF 를 붙이면 rate limit 이 꺼진다(구 `isClientIpAttributable` 잔존) | XFF 회전 6연타 → 429. 공유 테이블에 위조 IP 미적재 | 결함 아님(R-1 제거 확인) |
| 6 | 무서명 allowlist 접두(`/v1/vlm/**`)가 엉뚱한 엔드포인트를 삼킨다 | 해당 접두 아래 컨트롤러는 콜백 2종뿐 | 결함 아님 |
| 7 | Security 체인이 2벌이거나 `web.ignoring` 으로 필터가 건너뛰어진다 | 체인 1개, ignoring 없음, 서블릿 자동등록 비활성 | 결함 아님 |
| 8 | 서명 스트림 컨텍스트(`STREAM_SIGNED`)로 다른 `/v1/**` API 접근 가능 | `/v1/videos/4` 에 서명 부착 → 401(필터가 stream 경로에만 컨텍스트 설정) | 결함 아님 |
| 9 | 미배정 WORKER 가 서명 URL 로 임의 영상 스트리밍(B-ISSUE-63 미해소) | 서명을 직접 위조해 시험 → **403**. (최초 오판은 배정표 LIMIT 누락 탓, §1 기록) | 결함 아님 |
| 10 | `0.0.0.0/0` 설정이 IPv6 도 허용해 fail-closed 가 무의미 | IPv6 발신(`::1`) → **403**. 즉 `0.0.0.0/0` 은 IPv4 전용이며 IPv6 은 별도 `::/0` 명시 필요 — 클래스 javadoc 이 그렇게 안내함 | 결함 아님(문서 정합) |

---

## 4. 이슈 블록

> 이번 구간에서 **FAIL 은 0건**이다. 아래 3건은 ①PARTIAL 1건(A-ISSUE-42) ②PASS 케이스에서 실동작 반증 중 파생 관측된 LOW 결함 2건(A-ISSUE-41 / -43)이다. 셋 다 인증 우회가 아니라 **가용성·운영 관측** 축이다.

### [A-ISSUE-41] TC-STREAM-009 — 동명 쿠키 shadowing 시 스트림 nonce 검증이 영구 401(가용성 DoS)

- **심각도**: LOW
- **기대 동작(기대효과)**: `klid_stream_nonce` 쿠키는 서버 봉인(HMAC) 검증을 통과한 값만 채택하고, 그렇지 않은 값은 폐기한다. 요청에 **같은 이름의 쿠키가 여러 개** 실려 오더라도 그중 서버가 발급한 유효 값이 하나라도 있으면 그것을 채택해 정상 재생이 유지돼야 한다(봉인 검증이 이미 위조 값을 걸러내므로, 위조 값의 존재가 정상 값을 가릴 이유가 없다).
- **현재 동작(이슈 내용)**: `StreamNonceCookie.read()` 가 이름이 일치하는 **첫 번째** 쿠키에서 곧바로 `return` 한다 — 그 값이 봉인 검증에 실패해도 뒤의 유효 쿠키를 보지 않는다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/StreamNonceCookie.java:115-126`
  ```java
  for (Cookie cookie : cookies) {
      if (COOKIE_NAME.equals(cookie.getName())) {
          return unseal(cookie.getValue(), subject);   // ← 첫 매칭에서 즉시 반환
      }
  }
  return null;
  ```
  실동작(동일 서명 URL, 쿠키 순서만 변경):
  ```
  Cookie: klid_stream_nonce=<유효>                                        → 206
  Cookie: klid_stream_nonce=deadbeef…(위조); klid_stream_nonce=<유효>     → 401   ← 정상 값이 가려짐
  Cookie: klid_stream_nonce=<유효>; klid_stream_nonce=deadbeef…(위조)     → 206
  ```
  `read()` 는 발급 경로(`resolveOrIssue`)도 공유하므로, 위조 쿠키가 앞에 오는 한 **발급 때마다 새 nonce 를 만들고 재생 때마다 401** 이 반복되어 피해자 계정의 영상 재생이 지속 차단된다.
- **재현/확인 경로**:
  ```bash
  # 1) 서명 URL + 정상 nonce 쿠키 발급
  curl -s -D h -H "Authorization: Bearer $TOKEN" \
    "http://localhost:18081/api/v1/videos/4/stream-url"
  # 2) 위조 쿠키를 앞에 두고 재생
  curl -s -o /dev/null -w "%{http_code}\n" -H "Range: bytes=0-99" \
    -H "Cookie: klid_stream_nonce=deadbeefdeadbeefdeadbeefdeadbeef.$(printf 'a%.0s' $(seq 64)); klid_stream_nonce=$VALID" \
    "http://localhost:18081$URL"     # → 401
  ```
  현실적 전제: 공격자가 같은 호스트에 대해 **더 긴(또는 같은) Path** 로 동명 쿠키를 심을 수 있어야 한다(RFC 6265 §5.4 는 path 길이 내림차순 전송). 본 프로젝트는 CLAUDE.md 상 **관제서버와 동일 도메인 운영**이라 인접 앱의 XSS·related-domain 경로로 쿠키 주입 표면이 존재한다.
- **영향**: 인증 우회는 아니다(위조 쿠키로는 여전히 통과 불가 — 기밀성 유지). 영향은 **가용성**으로, 특정 사용자의 영상 스트리밍이 쿠키를 수동 삭제할 때까지 지속 실패한다. CWE-1289(Improper Validation of Unsafe Equivalence in Input) / CWE-565 계열, 가용성 축 CWE-400 인접.
- **수정 방향(제안)**: `StreamNonceCookie.read()` 의 루프를 "첫 매칭 반환"에서 **"매칭 전부를 순회하며 unseal 성공값이 나오면 반환, 끝까지 실패면 null"** 로 바꾼다(값 개수 상한을 두어 CPU 소모 방지). 봉인 검증이 이미 안전 판정이므로 다중 후보 허용에 따른 보안 약화가 없다. ⚠ 구현은 하지 않음.

### [A-ISSUE-42] TC-HMAC-036 — `trusted-proxy-cidrs=none` 을 명시해도 "설정이 비어 있다"고 경고(오해 유발 운영 신호)

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.trusted-proxy-cidrs` 는 "빈 값(미설정)"과 "`none`(프록시 없음을 **의식적으로** 선택)"을 구분하는 것이 설계 규약이다(`ClientIpResolver.NO_PROXY` 주석, `WebhookIpAllowlist` 는 실제로 두 경우 로그를 구분한다). 따라서 `none` 을 명시한 운영자에게는 "설정 누락" 경고가 나가면 안 된다 — 경고가 나가면 이미 규약대로 설정한 사람이 다시 손댈 것이 없는데도 조치를 찾게 되고, 반대로 **진짜 누락 환경의 경고가 노이즈에 묻힌다**.
- **현재 동작(이슈 내용)**: 판정이 `trustedProxies.isEmpty()` 하나뿐이라 `none` 명시와 미설정이 **같은 상태로 접힌다**. 실효 환경변수가 `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 인 현재 스택에서 다음 WARN 이 실제로 출력됐다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/webhook/ClientIpResolver.java:105-110, 207-217`
  ```java
  this.trustedProxies = isNoProxy(trustedProxyCidrs) ? List.of() : WebhookCidrParser.parseStrict(...);
  // ... isNoProxy 였다는 사실이 필드로 보존되지 않는다
  private void warnIfUnconfiguredProxy(HttpServletRequest request) {
      if (!trustedProxies.isEmpty() || unconfiguredProxyWarned.get()) return;
      ...
      log.warn("[Webhook] X-Forwarded-For 가 관측됐으나 webhook.trusted-proxy-cidrs 가 비어 있습니다. ... 직접 노출이면 '{}' 을 명시하세요.", NO_PROXY);
  }
  ```
  실로그(klid-backend, `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 상태):
  ```
  2026-07-31 03:17:40.209 WARN k.c.c.a.c.s.webhook.ClientIpResolver -
    [Webhook] X-Forwarded-For 가 관측됐으나 webhook.trusted-proxy-cidrs 가 비어 있습니다.
    XFF 는 폐기하고 remoteAddr 로 집계합니다. ... 직접 노출이면 'none' 을 명시하세요. (이 경고는 프로세스당 1회만 출력)
  ```
  → 이미 `none` 인데 "`none` 을 명시하세요"라고 안내한다.
- **재현/확인 경로**:
  ```bash
  docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep TRUSTED_PROXY
  #  WEBHOOK_TRUSTED_PROXY_CIDRS=none
  docker logs klid-backend 2>&1 | grep "trusted-proxy-cidrs 가 비어"
  ```
  (XFF 헤더가 붙은 웹훅 요청이 1회 이상 들어오면 발화)
- **영향**: 보안 통제 자체는 정상(XFF 폐기 + remoteAddr 집계는 기대대로 동작하며 A-ISSUE 아님). 영향은 **운영 관측 품질** — 정상 설정에 대한 오경보로 인해 진짜 설정 누락(local/dev 에서 프록시 뒤 배포 등)을 놓칠 수 있다. CWE-1295(Debug Messages Revealing Unnecessary Information) 계열이라기보다 운영 신호 품질 결함이며, `WebhookIpAllowlist` 와의 **정책 표현 비대칭**이기도 하다.
- **수정 방향(제안)**: `ClientIpResolver` 에 `private final boolean explicitNoProxy = isNoProxy(trustedProxyCidrs);` 를 보존하고 `warnIfUnconfiguredProxy` 의 조건에 `&& !explicitNoProxy` 를 추가한다(또는 `none` 명시일 때는 기동 시 INFO 1회만 남기는 `WebhookIpAllowlist:64-67` 방식과 통일). ⚠ 구현은 하지 않음.

### [A-ISSUE-43] TC-HMAC-017/018/043 — 무서명 경로의 411/413 이 rate limit 은 올리면서 `webhook.auth.failed` 메트릭은 올리지 않음(관측 사각)

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.auth.failed` 는 웹훅 가드가 요청을 거절한 사유를 운영자가 관측·알람하는 단일 지표다. **rate limit 카운터를 올리는(=429 backoff 를 유발할 수 있는) 모든 거절 사유**는 이 지표에도 남아야, "왜 정상 벤더 콜백이 429 를 맞고 있는가"를 메트릭만으로 추적할 수 있다.
- **현재 동작(이슈 내용)**: 무서명 경로에서 `411`(Content-Length 누락/chunked)과 `413`(본문 상한 초과)은 `rateLimiter.recordFailure(clientIp)` 는 호출하지만 `countAuthFailure(...)` 를 호출하지 않는다 — `writeLengthRequired`/`writePayloadTooLarge` 는 `fail()` 을 경유하지 않기 때문이다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/HmacWebhookFilter.java:506-527`
  ```java
  if (contentLength < 0) {
      rateLimiter.recordFailure(clientIp);
      writeLengthRequired(response);          // ← countAuthFailure 없음
      return;
  }
  if (contentLength > maxBytes) {
      rateLimiter.recordFailure(clientIp);
      writePayloadTooLarge(response, maxBytes); // ← countAuthFailure 없음
      return;
  }
  ```
  (서명 필수 경로의 413 도 동일: `:327-331`)

  실측 — chunked 4회 + 413 2회를 실제로 발생시킨 뒤 `/actuator/prometheus`:
  ```
  webhook_auth_failed_total{path="genai",reason="downstream_unauthorized"} 6.0
  webhook_auth_failed_total{path="genai",reason="ip_not_allowed"} 5.0
  webhook_auth_failed_total{path="vlm",reason="downstream_unauthorized"} 12.0
  ```
  → `reason="length_required"` / `"payload_too_large"` 시리즈가 **아예 없다**. 반면 같은 요청들이 `ls_whk_fail_nmtm` 카운터는 정상적으로 올렸다(호스트 IP 기준 5 도달 → 429 관측).
- **재현/확인 경로**:
  ```bash
  # 411 유발
  printf '{"a":1}' | curl -s -o /dev/null -w "%{http_code}\n" -X POST \
    http://localhost:18081/api/v1/vlm/callback -H 'Content-Type: application/json' \
    -H 'Transfer-Encoding: chunked' --data-binary @-        # → 411
  # 메트릭 확인 (REVIEWER 토큰 필요)
  curl -s -H "Authorization: Bearer $REVIEWER_TOKEN" \
    http://localhost:18081/api/actuator/prometheus | grep webhook_auth_failed
  # → length_required 계열 시리즈 없음
  ```
- **영향**: 보안 통제 자체는 정상 동작(차단·집계 모두 유효). 영향은 **관측/알람** — chunked·대용량 본문 플러딩으로 벤더 IP 가 429 backoff 에 빠져 **정상 콜백이 유실되는 상황**이 발생해도 `webhook.auth.failed` 알람이 전혀 울리지 않아 원인 추적이 로그 grep 에 의존하게 된다. CWE-778(Insufficient Logging) 계열.
- **수정 방향(제안)**: `handleGuardOnly` 의 411/413 분기(및 서명 경로 413 분기)에서 `countAuthFailure(path.tag(), "length_required")` / `"payload_too_large"` 를 호출한다 — reason 은 기존과 동일하게 **상수 문자열**이라 태그 카디널리티(TC-HMAC-043)를 해치지 않는다. 또는 `recordFailure` 와 메트릭 증가를 한 헬퍼로 묶어 두 축이 구조적으로 어긋나지 못하게 한다. ⚠ 구현은 하지 않음.

---

## 5. 근거 드리프트 점검

케이스 표에 적힌 `file:line` **58건 전부를 실파일과 대조**했다. **드리프트 0건** — A-3(`StreamSignatureFilter` / `StreamNonceCookie` / `SecurityConfig:166`) · A-4(`HmacWebhookFilter` / `WebhookProtectedPaths` / `WebhookRateLimiter` / `ClientIpResolver` / `WebhookIpAllowlist` / `GenAiWebhookIpAllowlist` / `WebhookGateInterceptor` / `WebhookGateConfig` / `WebhookConfigProfiles` / `WebhookGuardPurgeJob` / `JdbcWebhookGuardStore` / `HmacSigner`) 전 인용 위치가 현재 소스와 일치한다.

## 6. 테스트 커버 대조

| 테스트 파일 | 건수 | 커버 케이스 |
|---|--:|---|
| `webhook/HmacWebhookFilterTest.java` | 63 | TC-HMAC-002~014, 016~021, 025~035, 037~043, 046 |
| `webhook/WebhookGateInterceptorTest.java` | 7 | TC-HMAC-023, 024 |
| `webhook/WebhookPathBypassSecurityIT.java` | 11 | TC-HMAC-022, 025, 033, 034 (+StrictHttpFirewall 선차단) |
| `webhook/WebhookRateLimiterTest.java` | 4 | TC-HMAC-012, 013 |
| `common/security/webhook/GenAiWebhookIpAllowlistTest.java` | 4 | TC-HMAC-031, 042 |
| `common/security/HmacSignerTest.java` | 1 | TC-HMAC-020, 021 |
| `video/StreamSignedUrlControllerTest.java` | 19 | TC-STREAM-001~010 |
| `video/VideoStreamAssignmentAuthorizationTest.java` | 17 | TC-STREAM-012 |
| `common/security/StreamNonceCookieSecureFlagTest.java` | 3 | TC-STREAM-010 |

`_raw/test-baseline.md` 대조: backend 4,755 tests / **실패 0** / skip 5 → 위 테스트 전건 통과 상태.

**커버 공백**: `TC-STREAM-011`(sign-secret 미설정 시 봉인키 랜덤 → 전건 불통과)에 대응하는 자동 테스트가 없다(`StreamNonceCookieSecureFlagTest` 는 Secure 플래그만 다룬다). 정적 논리는 명확하나 회귀 가드는 부재 — 카탈로그 후속 보완 후보로 기록한다.
