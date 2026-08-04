# A 클러스터 part3 — A-4. 웹훅 인증 필터 (HmacWebhookFilter · 무서명 가드 · WebhookGate)

- **회차**: 2026-08-03 3차 · **대상 파일**: `docs/test-cases/A-auth-common.md` 112~164행(편집 후 116~168행)
- **대상 케이스**: 46행 중 **폐기 1건(TC-HMAC-001) 제외 → 45건 판정**
- **환경**: `_raw/stack-bringup.md` 기준 풀스택 기동(HEAD 재빌드본, Flyway v163). backend `localhost:18081`(context-path `/api`).
- **실효 설정(실측 `docker exec klid-backend env`)**: `WEBHOOK_HMAC_SECRET_AUGMENT`=64자 난수hex · `WEBHOOK_TRUSTED_PROXY_CIDRS=none` · `WEBHOOK_VLM_ALLOWED_IP_CIDRS=none`(=미적용/전면 허용) · `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0`(전면 허용) · 프로파일 `local`
- **판정 집계**: PASS 45 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0 (+ 폐기 1 = 분모 제외)
- **이슈**: 이월 미해소 2건(A-ISSUE-41·42) + 관측/확인필요 1건(A-ISSUE-43). **신규 결함(FAIL) 0건.**
- **근거 드리프트**: **0건** (46행의 `file:line` 인용 전수 대조 완료)
- **카탈로그 정정**: 2건 (아래 §4)

> ⚠ 본 문서는 검증 전용이다. 프로덕션 코드·테스트·설정은 일절 수정하지 않았다. 빌드/테스트도 실행하지 않았다.
> 카탈로그(`A-auth-common.md`) 만 담당 라인범위(112~164) 안에서 정정했다.

---

## 0. ★ 이번 회차 핵심 — URL 인코딩 우회(CWE-436) 재확인 결과

### 0-1. HMAC 필터 인코딩 우회(E-ISSUE-01) — **해소 실증**

1차에서 "정적 대조만으로는 전부 PASS 였던" 대표 사례다. 이번엔 **실요청으로 반증을 시도**했고, 우회는 **재현되지 않았다**.

```bash
# (1) 정상 경로와 인코딩 3변형 — 동일 본문(정상 CL, 소형 JSON)
POST /api/v1/vlm/callback        → 400  {"message":"status: must not be blank"}   ← 필터 통과 후 컨트롤러
POST /api/v1/%76lm/callback      → 400  (동일)                                     ← 필터 적용됨
POST /api/v1/vlm/%63allback      → 400  (동일)                                     ← 필터 적용됨
POST /api/v1/g%65nai/callback    → 400  {"message":"jobId/status must not be blank"}← 필터 적용됨
```

400 만으로는 "필터가 돌았는지"를 단정할 수 없으므로 **가드가 실제로 걸리는지**를 결정적으로 확인했다
(가드는 필터 안에만 있고 컨트롤러에는 없다):

```bash
# (2) chunked(Content-Length 부재) — 필터가 적용됐다면 411, 스킵됐다면 컨트롤러 도달
printf '{"a":1}' | curl -X POST .../api/v1/vlm/callback   -H 'Transfer-Encoding: chunked' --data-binary @-
  → 411 {"message":"Content-Length 헤더가 필요합니다. (chunked 전송은 허용되지 않습니다)"}
printf '{"a":1}' | curl -X POST .../api/v1/%76lm/callback -H 'Transfer-Encoding: chunked' --data-binary @-
  → 411 (동일)   ← ★ 인코딩 변형에도 가드가 그대로 걸린다
```

backend 로그도 두 요청 모두 필터가 처리했음을 보여준다(경로는 **원시 URI**로만 로깅, 판정은 정규화 경로):

```
00:05:08.950 WARN HmacWebhookFilter - [Webhook] unsigned callback missing/chunked Content-Length path=/api/v1/vlm/callback
00:05:08.972 WARN HmacWebhookFilter - [Webhook] unsigned callback missing/chunked Content-Length path=/api/v1/%76lm/callback
```

근본 원인 수정은 `WebhookProtectedPaths.pathWithinApplication()`(:260-279)이 **자체 디코딩을 만들지 않고**
MVC 라우팅과 같은 `ServletRequestPathUtils.parseAndCache(...)` + `PathPattern` 을 쓰는 것이다.
`RequestPath.parse(uri, contextPath)`(서블릿 접두 미반영) 를 쓰지 않는 이유까지 주석으로 못박혀 있고,
`WebhookProtectedPathsServletPrefixTest`(4건)가 `spring.mvc.servlet.path` 도입 시의 재발을 가드한다.

### 0-2. 경로 정규화 변형 10종 — 전부 fail-closed

```
POST /api/v1/%2576lm/callback        → 401  (이중 인코딩: 세그먼트 "%76lm" → 매칭 없음, 컨트롤러 미도달)
POST /api/v1/xx/%2e%2e/vlm/callback  → 401
POST /api/v1/vlm/callback/           → 401  (필터는 /v1/vlm/** 로 적용됨 → 하류 Security 401)
POST /api/v1/vlm/callback;a=b        → 401  (path param 제거 후 필터 적용 → 하류 Security 401)
POST /api/v1//vlm/callback           → 401
POST /api/v1/vlm;a=b/callback        → 401
POST /api/v1/VLM/callback            → 401  (대소문자 구분 — 필터 미적용 + 컨트롤러 미도달)
POST /api/v1/vlm%2Fcallback          → 400  (StrictHttpFirewall/Tomcat 선차단)
POST /api/v1/vlm/callback%20         → 401
POST /api/v1/./vlm/callback          → 401
```

**컨트롤러에 도달한 변형은 0건**이다. 즉 "필터 스킵 + 컨트롤러 도달" 이라는 E-ISSUE-01 의 형태는 사라졌다.

### 0-3. 유사 필터 전수 스윕 — "A만 고친 게 아닌가" 확인

`backend/src/main/java` 에서 `OncePerRequestFilter`/`Filter` 구현 중 경로 판정에 원시 URI 를 쓰는 곳을 전수 조사했다.

| 필터 | 경로 판정 방식 | 상태 |
|---|---|---|
| `common/security/HmacWebhookFilter` (+`WebhookProtectedPaths`) | `RequestPath`+`PathPattern` (MVC 동일) | **수정 완료** — 위 0-1 실증 |
| `portal/config/PortalLabelBodySizeFilter` | `RequestPath`+`PathPattern` (:77-88 `LABEL_PUT_PATTERNS`, trailing-slash 패턴 별도 등록, :180-213 `pathWithinApp`) | **수정 완료(2차 F-ISSUE-64 대응)** — 동일 규약 채택, javadoc 에 2차 실증 사례까지 인용. 라이브 최종 확인은 PORTAL 토큰이 필요해 **F 클러스터 담당**(본 파트 범위 밖) |
| `common/security/StreamSignatureFilter` (:172 `stripContext` → 정규식 `^/v1/videos/(\d{1,18})/stream$`) | **원시 URI 정규식 (미변경)** | **결함 아님** — 이 필터는 *권한을 부여하는* grant-type 이라 미매칭 시 **인증 컨텍스트가 안 생겨 보안체인 401** 로 떨어진다(fail-closed). 인코딩 변형으로 "필터를 스킵" 시켜도 얻는 것이 없다. (A-3 TC-STREAM-007 이 이미 이 동작을 고정) |

**결론**: *제한(restrict)* 성격의 필터 2개는 모두 근본 수정(MVC 동일 규약)이 적용됐고, 남은 원시-URI 필터 1개는 grant-type/fail-closed 라 같은 계열의 우회가 성립하지 않는다. **"A만 고치고 유사 필터가 남은" 상태는 아니다.**

### 0-4. replay·상수시간 비교·무서명 fail-open 점검

| 축 | 결과 |
|---|---|
| 상수시간 비교 | `MessageDigest.isEqual(providedBytes, computedBytes)` (`HmacWebhookFilter.java:391`). 직접 `equals`/`compareTo` 사용 0건 |
| replay 방지 | ①timestamp 창(과거 300s / **미래 30s 비대칭**, :360) ②**서명 nonce 1회성 소비**(:398-415, 키 = SHA-256(**정규화 경로**, ts, sig) :584-596) → 재전송 **409**. nonce 소비는 **서명 검증 성공 이후**(pre-auth write DoS 차단) |
| 무서명 요청 fail-open | 없음. `shouldNotFilter`(:254-263)는 예외 시 **false(=보호)** 고정, `requiresSignature`(WebhookProtectedPaths:128-134)는 판정 불가 시 **true** 고정. catch 후 통과시키는 분기 0건 |
| 무서명 경로의 최종 인증 | `VlmResultService` request_id 발급 게이트 — **실동작 확인**: 유효 스키마 + 미발급 id → `401 {"message":"발급되지 않은 request_id 입니다."}` |

---

## 1. 케이스별 판정

> 판정 셀은 6토큰만 사용. 실동작/정적 구분은 `근거 확인` 컬럼의 `[실동작]`/`[정적]` 접두로 표기.

| ID | 판정 | 근거 확인 |
|----|:--:|-----------|
| ~~TC-HMAC-001~~ | — | **[폐기]** 판정 대상 아님(분모 제외). `/v1/aug/callback` 컨트롤러·DTO·시뮬레이터 제거 확인 — `grep -rn "/v1/aug"` 결과 프로덕션 라우팅 0건, `SIGNATURE_REQUIRED = List.of()`(WebhookProtectedPaths:88) |
| TC-HMAC-002 | PASS | [정적] `HmacWebhookFilter.java:214-224`(서명 필수 경로 0개면 미설정 허용 → 기동 성공) + `:311-318`(런타임 `secret_missing` 401). **[실동작 보강]** 이 분기의 전제인 "경로 판정 불가 요청"은 네트워크로 만들 수 없음을 실증 — `…/callback%`·`/%zz`·`/%00x`·`/..%2fcallback` 4종 전부 **Tomcat 400 선차단**(필터 미도달). 단위: `서명필수_경로가_없으면_HMAC_시크릿_없이도_기동한다` / `경로_판정_불가_요청은_여전히_401_이다` |
| TC-HMAC-003 | PASS | [정적] `:240-245` `bytes < MIN_SECRET_BYTES(32) → BeanInitializationException`, 경로 유무와 무관(:214 인자 분리). 단위 `시크릿_32바이트_미만_시_부팅_실패`. 라이브는 스택 재기동/설정 변경이 필요해 미수행(§10-1 준수) |
| TC-HMAC-004 | PASS | [정적] `:335-340` — 헤더 null/blank → `rateLimiter.recordFailure(clientIp)` 후 `fail(..., "missing_signature", ...)` → 401. 순서(집계 먼저) 확인 |
| TC-HMAC-005 | PASS | [정적] `:341-346` `!signature.startsWith("hmac-sha256=")` → recordFailure + `unsupported_algorithm` 401 |
| TC-HMAC-006 | PASS | [정적] `:349-357` `Long.parseLong` NumberFormatException → recordFailure + `timestamp_malformed` 401 |
| TC-HMAC-007 | PASS | [정적] `:360` `ageMs > windowSeconds*1000L \|\| ageMs < -FUTURE_SKEW_MS` — **`Math.abs` 대칭 아님**을 코드로 확인. `FUTURE_SKEW_MS = 30_000L`(:133), window 하한 clamp 60s(:194). 단위 `미래_timestamp_는_시계오차_범위를_넘으면_401` |
| TC-HMAC-008 | PASS | [정적] `:391` `MessageDigest.isEqual(providedBytes, computedBytes)` — 조기반환 비교 없음. 불일치 시 recordFailure + `signature_mismatch` 401(:392-395) |
| TC-HMAC-009 | PASS | [정적] `:321-326` 서명 경로 `contentLength < 0` → **401 `missing_content_length`**. **[실동작]** 무서명 경로의 비대칭(**411**)은 직접 확인(`/v1/vlm/callback` chunked → 411 LENGTH_REQUIRED). 두 경로의 코드가 실제로 다른 헬퍼(`fail` vs `writeLengthRequired`)를 씀 |
| TC-HMAC-010 | PASS | [정적] `:98` `MAX_WEBHOOK_BODY_BYTES = 1MB`, `:327-331` 초과 시 `writePayloadTooLarge` 413(집계 없음 — 카탈로그도 집계를 주장하지 않음) |
| TC-HMAC-011 | PASS | [정적] `:368-375`(스트림 캡 예외 → 413) + `:726-740` `readCapped` 8KB 청크 누적, 상한 초과 **즉시 throw**(전량 버퍼링 없음). 단위 `Content_Length_위조해도_스트림_상한_초과시_413`·`실제_스트림은_상한까지만_읽고_중단_bounded_read`. **[실동작 보강]** 무서명 경로 5MB → 0.1s 내 413(파싱 전 거부) |
| TC-HMAC-012 | PASS | **[실동작]** 깨끗한 IP(172.20.0.2, ai-server 컨테이너)에서 411 유발 7연타 → `411 411 411 411 411 429 429` — **5회 허용 / 6회째 429**. 응답 헤더 `Retry-After: 60` 확인. 공유 2단도 실측: `ls_whk_fail_nmtm` 에 `(172.20.0.2, 00:06, 5)` 적재 후 더 이상 증가 안 함(차단 후 쓰기 중단, :129-134). 또 로컬 트래커가 **분 버킷 경계를 넘어** 누적됨을 관측(00:04 2건 + 00:05 3건 = 로컬 5 → 429) — `WebhookRateLimiter:226-237` 의 "첫 실패로부터 60초" 창과 일치 |
| TC-HMAC-013 | PASS | **[실동작]** "무서명 경로의 하류 200/비인증 응답으로는 reset 하지 않는다" 를 직접 확인 — 위조 401×4 → **400(검증오류) 1회 삽입** → 위조 401×1 → **6번째 429**. 카운터가 지워지지 않았다. [정적] reset 은 `:417`(서명 검증 성공 직후) 단 1곳, `countDownstreamAuthOutcome`(:556-564)은 401/403 만 집계하고 reset 호출 없음. 공유 **현재+직전 버킷 동시 삭제**는 `WebhookRateLimiter:207-214` |
| TC-HMAC-014 | PASS | [정적] `WebhookRateLimiter:74` cap 4096, `:256-273` ①만료 항목 `removeIf` ②그래도 초과 시 `windowStartMs` 오름차순 정렬 후 초과분 evict. 단위 `FailureTracker_4096_초과_시_hard_cap_적용`. 4096 IP 확보 불가로 라이브 미수행 |
| TC-HMAC-015 | PASS | **[실동작]** 260자 경로(`/v1/vlm/ZZZ…`)로 요청 → 로그가 정확히 200자 + `...` 로 절단됨을 실로그로 확인. [정적] `WebhookProtectedPaths:100-103,227-233` `[\r\n\t]`→`_`. 원시 URI 는 **퍼센트 디코딩 전** 값이라 raw CR/LF 가 실릴 수 없음도 확인(`path=/api/v1/vlm/callback%20` — `%20` 미디코딩 상태로 로깅) |
| TC-HMAC-016 | PASS | **[실동작]** 최종 게이트 확인 — 스키마 유효 + 미발급 request_id → `401 발급되지 않은 request_id 입니다.`(`VlmResultService:69-73`). [정적] 가드 순서 `:470-533` = ①`rateLimiter.isLimited`(480) ②IP allowlist(486-500) ③Content-Length(502-518) ④스트림 size cap(519-527). HMAC/timestamp/nonce 분기 미진입 |
| TC-HMAC-017 | PASS | **[실동작]** chunked → `411 LENGTH_REQUIRED` + 실패 집계(뒤이어 6회째 429 로 확인). [정적] `:506-511` |
| TC-HMAC-018 | PASS | **[실동작]** vlm 5MB → `413 "Webhook 본문은 4096KB 를 초과할 수 없습니다."`, vlm 1.2MB → 통과(하류 400). [정적] `:119` `MAX_VLM_BODY_BYTES=4MB`, `:512-527` 선언 CL + 실스트림 2단 캡 |
| TC-HMAC-019 | PASS | **[실동작]** 미등록 경로 5종(`/v1/VLM/callback`, `/v1/%2576lm/callback`, `/v1/xx/%2e%2e/vlm/callback`, `/v1//vlm/callback`, `/v1/./vlm/callback`)에 대해 backend 로그에 `[Webhook]` 라인 **0건**(= shouldNotFilter=true) 이면서 컨트롤러 도달도 0건(Security 401). [정적] `:254-263` catch → `return false`(보호), `WebhookProtectedPaths:155-161` path==null → true |
| TC-HMAC-020 | PASS | [정적] `:574-576` `hmacSha256Hex` → `HmacSigner.hex` 위임(단일 진실원). `HmacSigner.java:42-53` 에 Mac 계산이 1벌만 존재하고 필터에 중복 구현 없음 |
| TC-HMAC-021 | PASS | **[실동작]** `docker logs klid-backend \| grep -c "68e759de6247"`(실효 시크릿 앞 12자) → **0**. [정적] `HmacSigner.java:48-52` 예외 메시지에 `algorithm=HmacSHA256` 만 포함, 시크릿·메시지 미포함 |
| TC-HMAC-022 | PASS | **[실동작] ★** 위 §0-1. `/v1/%76lm/callback`·`/v1/vlm/%63allback`·`/v1/g%65nai/callback` 3변형 모두 **가드 적용**(chunked→411, 정상 본문→가드 통과 후 컨트롤러 400). 로그로 필터 진입 확인. **E-ISSUE-01(CWE-436→CWE-288) 회귀 없음** |
| TC-HMAC-023 | PASS | [정적] `WebhookGateInterceptor.java:54-60` `WebUtils.getNativeRequest(request, WebhookGuardedRequest.class)==null → 401` + `preHandle` 은 `@RequestBody` 역직렬화 이전. 단위 7건(`필터_증거_래퍼가_없는_요청은_401_컨트롤러_미도달` 등). 라이브에서는 필터가 항상 선행하므로 마커 부재 상태를 만들 수 없음(FORWARD dispatch 등 앱 내부 경로에서만 성립) |
| TC-HMAC-024 | PASS | [정적] `:51-53` `DispatcherType.ASYNC → return true`. 단위 `ASYNC_재디스패치는…401_이_아님` + `REQUEST_디스패치는_증거_래퍼가_없으면_여전히_401_ASYNC_예외가_우회로가_아님`(우회로 아님을 별도 고정) |
| TC-HMAC-025 | PASS | [정적] `:398-415` 최초 통과 / 이후 **409 CONFLICT**, `nonceHash(path.canonical(), ts, sig)`(:401,584-596) 가 **정규화 경로** 사용 → 인코딩 변형이 한 키로 수렴. **[실동작 보강]** `ls_whk_sign_use` **0행** — 서명 경로 트래픽이 실제로 발생하지 않음(§0 헤더 참조). 단위 `nonce_키는_원시URI가_아니라_정규화_경로로_계산됨` |
| TC-HMAC-026 | PASS | [정적] `:398-401` nonce 소비가 서명 검증(391) **이후**. **[실동작 보강]** 위조/무인증 요청 60여 회를 흘린 뒤에도 `ls_whk_sign_use` 0행 → pre-auth write 부재. 단위 `서명_검증_실패_요청은_nonce_테이블에_행을_쓰지_않음_pre_auth_write_DoS` |
| TC-HMAC-027 | PASS | [정적] `:404-409` `WebhookGuardUnavailableException` → `writeServiceUnavailable` (`:647-652` 503 + `Retry-After: 30`). 예외는 `JdbcWebhookGuardStore:109-111`(`consume` 의 DataAccessException)에서만 발생 = 401 로 오분류되지 않음. 단위 `nonce_저장소_장애시_fail_closed_503_이며_통과하지_않음` |
| TC-HMAC-028 | PASS | [정적] `:418-431` `committed = response.getStatus() < 500` + `finally` 에서 `releaseNonce`. `:435-443` release 실패는 로깅만(요청 결과 불변). 비동기 반환 시 무력화된다는 전제도 주석에 명시. 단위 3건 |
| TC-HMAC-029 | PASS | [정적] `:157-166` placeholder 4종 집합 + `:234-239` non-local 프로파일 기동 차단. 실효 시크릿은 64자 난수라 해당 없음. 단위 `리포에_커밋된_공개_placeholder_시크릿은_local_이_아닌_프로파일에서_부팅_실패` / `local_프로파일에서는_placeholder_시크릿으로_기동_가능` |
| TC-HMAC-030 | PASS | [정적] `:227-233` `secret.startsWith("?")` → BeanInitializationException. `application.yml:737` 은 규약대로 `${WEBHOOK_HMAC_SECRET_AUGMENT:}`(`:?` 아님) 사용. 단위 `해석되지_않은_placeholder_기본값_물음표_시크릿은_부팅_실패` |
| TC-HMAC-031 | PASS | [정적] `GenAiWebhookIpAllowlist:58-66` — 빈 값/`none` 모두 `allowed=List.of()` → `isAllowed` **항상 false**(fail-closed), `application.yml:742` 기본값도 `none`. 반대편 `WebhookIpAllowlist:81-84` 는 `allowed.isEmpty() → true`(전면 허용) — **비대칭이 코드로 확인**. **[실동작 보강]** 현 환경은 vlm=`none`(허용) / genai=`0.0.0.0/0`(허용) 라 403 을 만들 수 없어 fail-closed 절반은 정적·단위(`genai_allowlist_미설정이면_전면_차단되어_403`, `GenAiWebhookIpAllowlistTest`)로 판정. vlm 미설정=전면 허용 쪽은 실동작 확인(무서명 콜백이 403 없이 통과) |
| TC-HMAC-032 | PASS | [정적] `:486-500` 403 분기가 `rateLimiter.recordFailure` + `countAuthFailure(tag,"ip_not_allowed")` 를 **둘 다** 호출. 단위 `allowlist_밖_IP_의_403_이_rate_limit_에_집계된다` |
| TC-HMAC-033 | PASS | **[실동작]** 위조 request_id 15연타(호스트 IP) → `401 401 401 401 401 429 429 429 429 429 429 429 429 429 429`. 중간에 **비-401 응답(400)을 끼워도 카운터가 지워지지 않음**(TC-HMAC-013 실험) → "위조 4회 + 알려진 id 1회" 회피 불성립. [정적] `:543-564` |
| TC-HMAC-034 | PASS | **[실동작]** 매 요청 `X-Forwarded-For` 를 `203.0.113.1~15` 로 회전시킨 15연타 → `401×5 → 429×10`(우회 실패). `ls_whk_fail_nmtm` 에 **203.0.113.x 가 단 한 행도 없음**(위조 IP 미적재, 집계는 실제 remoteAddr 172.20.0.4 로 수렴). [정적] `:552-555` + `ClientIpResolver:139-145` — `isClientIpAttributable` 류 런타임 비활성 게이트 **부재 확인**(`grep` 0건) |
| TC-HMAC-035 | PASS | **[실동작]** genai 1.2MB → `413 "1024KB 를 초과할 수 없습니다"` / genai 0.9MB → 통과(하류 400) / **vlm 1.2MB → 통과**(하류 400) → 두 상한이 실제로 다름을 교차 확인. [정적] `:121-130`, `:476` |
| TC-HMAC-036 | PASS | **[실동작]** trusted-proxy=`none` 상태에서 XFF 부착 15연타 → XFF 폐기·remoteAddr 로 집계(TC-HMAC-034 근거와 동일). WARN 은 프로세스당 **1회만** 출력 확인(`grep -c` = 1). [정적] `ClientIpResolver:139-149,207-217`. ⚠ 다만 `none` **명시** 상태인데도 "비어 있습니다 … 'none' 을 명시하세요" 문구가 나감 → **A-ISSUE-42(이월)** |
| TC-HMAC-037 | PASS | [정적] `ClientIpResolver:150-167` — `hops` 를 오른쪽부터 순회, `!isValidIp` 면 remoteAddr 폴백(:158-161), `!isTrusted` 인 최초 값 채택(:162-164). 단위 `신뢰프록시_뒤에서는_XFF_클라이언트별로_rate_limit_이_독립_적용`. 라이브는 신뢰 CIDR 설정 변경이 필요해 미수행(§10-1) |
| TC-HMAC-038 | PASS | [정적] `:70-74` `MAX_XFF_LENGTH=1024`/`MAX_XFF_HOPS=20`, `:147`(길이 초과 시 헤더 무시) `:153`(`.limit(20)`) |
| TC-HMAC-039 | PASS | [정적] `:126-138` `!isValidIp(remoteAddr)` → `UNKNOWN` 반환 + 1회 WARN(:229-236), `isValidIp` 는 `WebhookCidrParser.isIpLiteral` 구조 검증(:193-198). `isTrusted`(:170-171)·`WebhookIpAllowlist`/`GenAiWebhookIpAllowlist` 모두 sentinel 에 매칭되지 않음. "XFF 우회 차단 통제가 아님" 이라는 카탈로그 단서도 주석과 일치 |
| TC-HMAC-040 | PASS | [정적] `ClientIpResolver:93-102` + `WebhookConfigProfiles.java:32`(`{prd,stg}`)·`:38-44`. `none` 명시는 `isNoProxy`(:239-241)로 통과. 단위 `prd_에서_신뢰프록시_CIDR_미설정이면_기동_실패`·`stg_에서도…기동_실패`·`prd_에서_프록시_없음을_none_으로_명시하면_기동_가능` |
| TC-HMAC-041 | PASS | [정적] `WebhookIpAllowlist:46-56`(미설정 시 BeanInitializationException) + `:59-61`(`none` 허용) + `:64-67`(none 명시 시 WARN 1회). 단위 `prd_에서_VLM_IP_allowlist_미설정이면_기동_실패` |
| TC-HMAC-042 | PASS | [정적] `ClientIpResolver:105-107` / `WebhookIpAllowlist:59-61` / `GenAiWebhookIpAllowlist:46-48` 이 모두 `WebhookCidrParser.parseStrict` 사용(조용한 skip 없음). 단위 `신뢰프록시_CIDR_오타는_조용히_무시되지_않고_기동이_차단되며_사유가_메시지에_드러난다`·`VLM_allowlist_CIDR_오타는…` |
| TC-HMAC-043 | PASS | **[실동작]** 5개 상이 URI(`/v1/vlm/AAAA0001`, `…AAAA0002`, `/v1/genai/BBBB0003`, 260자 경로, `/v1/vlm/callback`)로 실패 유발 후 `/actuator/prometheus` 확인 → 시리즈가 정확히 2개뿐:<br>`webhook_auth_failed_total{path="genai",reason="downstream_unauthorized"} 1.0`<br>`webhook_auth_failed_total{path="vlm",reason="downstream_unauthorized"} 16.0`<br>원시 URI 태그 **0건**(Meter 무한 생성 없음). [정적] `:136,610-622` + `WebhookProtectedPaths:73-75,198-207`. ⚠ 다만 411/413 사유가 이 지표에 **아예 없음** → **A-ISSUE-41(이월)** |
| TC-HMAC-044 | PASS | **[실동작]** `/actuator/prometheus` 에 `spring_security_filterchains_seconds_count{…reached_filter_name="HmacWebhookFilter",spring_security_filterchain_position="7",spring_security_filterchain_size="15"}` **1계열만** 존재. 요청 1건당 `[Webhook]` 로그가 정확히 1줄(중복 실행 흔적 없음). [정적] `WebhookGateConfig.java:42-49` `setEnabled(false)`, `SecurityConfig.java:162` `addFilterBefore(hmacWebhookFilter, …)` |
| TC-HMAC-045 | PASS | **[실동작]** 기동 로그 `[Webhook] guard purge job scheduled intervalMs=600000`. [정적] `WebhookGuardPurgeJob.java:45-54`(clamp `Math.max(60_000, interval)`, `initialDelay` 기본 300000) `:62-68`(데몬 단일 스레드 executor, `scheduleWithFixedDelay`) `:84-96`(`Throwable` 까지 삼켜 스케줄러 사망 방지). 조건부 DELETE 는 `JdbcWebhookGuardStore.java:72,88`(`EXPD_DT < CURRENT_TIMESTAMP`) → 2노드 동시 실행 무해. 통합테스트 `JdbcWebhookGuardStoreIT` 8건 |
| TC-HMAC-046 | PASS | [정적] **비대칭 확인** — nonce: `JdbcWebhookGuardStore:109-111` 이 예외를 `WebhookGuardUnavailableException` 으로 승격 → 필터 `:404-409` **503 거부**(fail-closed). rate limit: `:157-169` `currentFailures` 가 `UNAVAILABLE(-1)` 반환 → `WebhookRateLimiter:137-140` 이 `!= UNAVAILABLE` 조건으로 차단 판정을 건너뜀 = **fail-open**. `recordFailure`(:140-144)·`resetFailures`(:151-154)도 로깅 후 계속. 단위 `rate_limit_공유저장소_장애시_fail_open_으로_정상_콜백이_통과` |

---

## 2. 이슈

### [A-ISSUE-41] TC-HMAC-017 / TC-HMAC-018 / TC-HMAC-043 — 무서명 경로의 411/413 이 rate limit 은 올리면서 `webhook.auth.failed` 메트릭은 올리지 않음 (관측 사각) 〔1차 A-ISSUE-43 **이월 · 미해소**〕

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.auth.failed` 는 웹훅 가드가 요청을 거절한 사유를 운영자가 관측·알람하는 단일 지표다. **rate limit 카운터를 올리는(=429 backoff 를 유발할 수 있는) 모든 거절 사유**는 이 지표에도 남아야, "왜 정상 벤더 콜백이 429 를 맞고 있는가"를 메트릭만으로 추적할 수 있다.
- **현재 동작(이슈 내용)**: 무서명 경로의 `411`(Content-Length 누락/chunked)·`413`(본문 상한 초과)은 `rateLimiter.recordFailure(clientIp)` 는 호출하지만 `countAuthFailure(...)` 를 호출하지 않는다. `writeLengthRequired`/`writePayloadTooLarge` 가 `fail()`(:604-608, 메트릭 증가 지점)을 경유하지 않기 때문이다. 서명 경로의 413(:327-331)도 동일.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/HmacWebhookFilter.java:506-518`
  ```java
  if (contentLength < 0) {
      rateLimiter.recordFailure(clientIp);
      writeLengthRequired(response);            // ← countAuthFailure 없음
      return;
  }
  if (contentLength > maxBytes) {
      rateLimiter.recordFailure(clientIp);
      writePayloadTooLarge(response, maxBytes); // ← countAuthFailure 없음
      return;
  }
  ```
  **3차 실측 재확인** — 이번 회차에 411 을 14회, 413 을 5회 실제로 발생시킨 뒤 `/actuator/prometheus`:
  ```
  webhook_auth_failed_total{path="genai",reason="downstream_unauthorized"} 1.0
  webhook_auth_failed_total{path="vlm",reason="downstream_unauthorized"} 16.0
  ```
  → `reason="length_required"` / `"payload_too_large"` 시리즈가 **여전히 존재하지 않는다**. 같은 요청들이 `ls_whk_fail_nmtm` 은 정상적으로 올렸다(172.20.0.2 버킷 5 도달 → 6회째 429 관측).
- **재현/확인 경로**:
  ```bash
  # 411 유발 (5회 미만으로 유지)
  printf '{"a":1}' | curl -s -o /dev/null -w "%{http_code}\n" -X POST \
    http://localhost:18081/api/v1/vlm/callback -H 'Content-Type: application/json' \
    -H 'Transfer-Encoding: chunked' --data-binary @-        # → 411
  # 메트릭 확인 (REVIEWER 토큰 필요 — userNo 는 LS_USER_ROLE 에 REVIEWER 로 있는 값, 예: 1001)
  curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"userNo":1001,"role":"REVIEWER","channel":"INTERNAL"}' -o /tmp/t.json
  curl -s -H "Authorization: Bearer $(python3 -c "import json;print(json.load(open('/tmp/t.json'))['data']['token'])")" \
    http://localhost:18081/api/actuator/prometheus | grep webhook_auth_failed
  # → length_required / payload_too_large 계열 시리즈 없음
  ```
- **영향**: 보안 통제 자체는 정상 동작(차단·집계 모두 유효). 영향은 **관측/알람** — chunked·대용량 본문 플러딩으로 벤더 IP 가 429 backoff 에 빠져 **정상 콜백이 유실되는 상황**이 발생해도 `webhook.auth.failed` 알람이 전혀 울리지 않아 원인 추적이 로그 grep 에 의존한다. CWE-778(Insufficient Logging) 계열.
- **수정 방향(제안)**: `handleGuardOnly` 의 411/413 분기(및 서명 경로 413 분기 `:327-331`)에서 `countAuthFailure(path.tag(), "length_required")` / `"payload_too_large"` 를 호출한다 — reason 은 **상수 문자열**이라 태그 카디널리티(TC-HMAC-043)를 해치지 않는다. 더 견고하게는 `recordFailure` + 메트릭 증가를 한 헬퍼(`countedFailure(path, reason)`)로 묶어 두 축이 구조적으로 어긋나지 못하게 한다. ⚠ 구현은 하지 않음.

### [A-ISSUE-42] TC-HMAC-036 — `trusted-proxy-cidrs=none` 을 명시해도 "설정이 비어 있다"고 경고 (오해 유발 운영 신호) 〔1차 A-ISSUE-42 **이월 · 미해소**〕

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.trusted-proxy-cidrs` 는 "빈 값(미설정)"과 "`none`(프록시 없음을 **의식적으로** 선택)"을 구분하는 것이 설계 규약이다(`ClientIpResolver.NO_PROXY` 주석 `:67-68`, `WebhookIpAllowlist:62-67` 은 실제로 두 경우 로그를 구분한다). `none` 을 명시한 운영자에게는 "설정 누락" 경고가 나가면 안 된다 — 이미 규약대로 설정한 사람이 조치를 찾게 되고, 반대로 **진짜 누락 환경의 경고가 노이즈에 묻힌다**.
- **현재 동작(이슈 내용)**: 판정이 `trustedProxies.isEmpty()` 하나뿐이라 `none` 명시와 미설정이 **같은 상태로 접힌다**. `isNoProxy` 결과가 필드로 보존되지 않는다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/webhook/ClientIpResolver.java:105-107, 207-216`
  ```java
  this.trustedProxies = isNoProxy(trustedProxyCidrs)
          ? List.of()                                  // ← "none 이었다" 는 사실이 소실
          : WebhookCidrParser.parseStrict(...);
  ...
  private void warnIfUnconfiguredProxy(HttpServletRequest request) {
      if (!trustedProxies.isEmpty() || unconfiguredProxyWarned.get()) return;   // ← none 도 여기 걸림
      ... log.warn("... webhook.trusted-proxy-cidrs 가 비어 있습니다 ... '{}' 을 명시하세요.", NO_PROXY);
  }
  ```
  **3차 실측 재확인** — `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 인 현재 스택에서 XFF 부착 요청 직후:
  ```
  2026-08-04 00:09:37.714 WARN k.c.c.a.c.s.webhook.ClientIpResolver -
    [Webhook] X-Forwarded-For 가 관측됐으나 webhook.trusted-proxy-cidrs 가 비어 있습니다.
    XFF 는 폐기하고 remoteAddr 로 집계합니다. ... 직접 노출이면 'none' 을 명시하세요. (이 경고는 프로세스당 1회만 출력)
  ```
  → 이미 `none` 인데 "`none` 을 명시하세요"라고 안내한다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep TRUSTED_PROXY          # → WEBHOOK_TRUSTED_PROXY_CIDRS=none
  curl -s -o /dev/null -X POST http://localhost:18081/api/v1/vlm/callback \
    -H 'Content-Type: application/json' -H 'X-Forwarded-For: 203.0.113.9' --data '{"a":1}'
  docker logs klid-backend 2>&1 | grep "trusted-proxy-cidrs 가 비어"
  ```
- **영향**: 보안 통제 자체는 정상(XFF 폐기 + remoteAddr 집계가 기대대로 동작함을 이번 회차에 실증). 영향은 **운영 관측 품질** — 정상 설정에 대한 오경보로 진짜 설정 누락(프록시 뒤 배포인데 미설정)을 놓칠 수 있다. `WebhookIpAllowlist` 와의 **정책 표현 비대칭**이기도 하다.
- **수정 방향(제안)**: `ClientIpResolver` 에 `private final boolean explicitNoProxy = isNoProxy(trustedProxyCidrs);` 를 보존하고 `warnIfUnconfiguredProxy` 조건에 `&& !explicitNoProxy` 를 추가한다(또는 `none` 명시일 때 기동 시 INFO 1회만 남기는 `WebhookIpAllowlist:64-67` 방식으로 통일). ⚠ 구현은 하지 않음.

### [A-ISSUE-43] TC-HMAC-002 / 004~011 / 020 / 025~028 — HMAC **서명 검증 경로 전체가 네트워크로 도달 불가**(라이브 무검증 구간) 〔3차 신규 · 관측/확인필요〕

- **심각도**: LOW (동작 결함 아님 — 검증 가능성·회귀 탐지력의 문제)
- **기대 동작(기대효과)**: 카탈로그가 서명 경로에 13건의 보안 케이스(상수시간 비교·timestamp 창·nonce replay·503 fail-closed·5xx 시 nonce 해제 등)를 두고 있다면, 그 코드는 **실환경에서 한 번이라도 실행되는 경로**여야 회귀가 드러난다. 실행되지 않는 방어는 "있다고 믿는" 상태로 조용히 썩는다.
- **현재 동작(이슈 내용)**: Phase 7-A2 이후 `SIGNATURE_REQUIRED = List.of()`(`WebhookProtectedPaths.java:88`)라 서명 필수 경로가 0개다. 서명 분기(`HmacWebhookFilter.handleSignatureRequired`, :299-432)로 들어가는 유일한 조건은 `requiresSignature(request)==true`, 즉 `pathWithinApplication(request)==null`(경로 판정 불가, fail-closed)뿐이다.
  그런데 **경로 판정을 깨뜨리는 요청은 Tomcat 이 필터보다 먼저 400 으로 끊는다** — 3차 실측:
  ```
  POST /api/v1/vlm/callback%       → 400 (Tomcat HTML)
  POST /api/v1/vlm/%zz             → 400 (Tomcat HTML)
  POST /api/v1/vlm/%00x            → 400 (Tomcat HTML)
  POST /api/v1/vlm/..%2fcallback   → 400 (Tomcat HTML)
  ```
  교차 확인: 이번 회차 60여 건의 웹훅 요청 후에도 `SELECT count(*) FROM ls_whk_sign_use` → **0**(서명 nonce 가 한 번도 소비된 적 없음).
  또한 실효 설정된 `WEBHOOK_HMAC_SECRET_AUGMENT`(64자 난수)는 **어떤 정상 트래픽에서도 사용되지 않는다**.
  단위 테스트도 이 사실을 인정하고 있다 — `HmacWebhookFilterTest:1577-1582` 가 `getRequestURI()` 가 예외를 던지는 `MockHttpServletRequest` 를 만들어야만 이 분기에 들어간다.
- **재현/확인 경로**:
  ```bash
  for P in "/v1/vlm/callback%" "/v1/vlm/%zz" "/v1/vlm/%00x" "/v1/vlm/..%2fcallback"; do
    curl -s -o /dev/null -w "$P → %{http_code}\n" -X POST --path-as-is \
      "http://localhost:18081/api$P" -H 'Content-Type: application/json' --data '{"a":1}'
  done   # 전부 400 (필터 로그 [Webhook] 미출력)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT count(*) FROM ls_whk_sign_use;"  # → 0
  ```
- **영향**: 보안 약화는 **아니다**(오히려 Tomcat 이 1차 방어선으로 동작). 영향은 **검증 가능성** — ①TC-HMAC-002/004~011/020/025~028 13건이 매 회차 "정적+단위테스트로만" 판정될 수밖에 없어 회차별 신뢰 근거가 약하다 ②`HmacSigner`·nonce 저장소·`releaseNonce` 등의 회귀가 실환경에서 드러나지 않는다 ③운영이 의미 없는 시크릿을 관리·회전하게 된다(설정 위생).
- **수정 방향(제안)**: 셋 중 하나를 **정책으로 확정**할 것 (구현은 하지 않음).
  ① 서명 필수 웹훅을 하나라도 다시 등록할 계획이면 현 상태를 유지하고, 카탈로그에 "라이브 재현 불가" 를 명시(이번 회차에 §A-4 머리말로 반영함).
  ② 재등록 계획이 없으면 `SIGNATURE_REQUIRED`/`secretAugment`/nonce 저장소를 **폐지 대상으로 선언**하고 `LS_WHK_SIGN_USE`·`WEBHOOK_HMAC_SECRET_AUGMENT` 도 함께 정리한다(죽은 보안 설정 제거).
  ③ 유지하되 회귀 탐지력을 올리려면 `@Profile("local\|dev")` 서명 필수 테스트 엔드포인트(예: `/v1/dev/webhook/echo`)를 등록해 **서명 경로를 라이브로 태울 수 있게** 한다.
  ⚠ ②는 "철회된 정책 재시도" 이력이 있는 영역이므로 반드시 사용자 확정을 거칠 것.

---

## 3. 테스트 커버 대조

| 테스트 파일 | @DisplayName 수 | 커버 케이스 |
|---|--:|---|
| `webhook/HmacWebhookFilterTest.java` | 63 | TC-HMAC-002~014, 016~021, 025~035, 037~043, 046 |
| `webhook/WebhookGateInterceptorTest.java` | 7 | TC-HMAC-023, 024 |
| `webhook/WebhookPathBypassSecurityIT.java` | 10 | TC-HMAC-022, 025, 033, 034 (+`StrictHttpFirewall` 선차단 회귀 고정) |
| `webhook/WebhookRateLimiterTest.java` | 4 | TC-HMAC-012, 013 |
| `webhook/WebhookProtectedPathsServletPrefixTest.java` | 4 | TC-HMAC-019, 022 (servlet path prefix 도입 시 재발 가드) |
| `webhook/JdbcWebhookGuardStoreIT.java` | 8 | TC-HMAC-025~027, 045, 046 |
| `common/security/webhook/GenAiWebhookIpAllowlistTest.java` | — | TC-HMAC-031 |
| `common/security/HmacSignerTest.java` | — | TC-HMAC-020, 021 |

- 통과 여부는 `_raw/test-baseline.md` 대조(본 파트는 §10-2 에 따라 테스트를 실행하지 않음).
- **커버 공백**: 없음. 45건 전부 최소 1개 테스트에 대응된다.
- ⚠ 1차 기록은 `WebhookPathBypassSecurityIT` 11건이었으나 실측 **10건**이다(경미한 수치 드리프트, 케이스 커버에는 영향 없음).

---

## 4. 카탈로그 정정 (담당 라인범위 112~164 내)

| # | 대상 | 구 내용 | 정정 내용 | 사유 |
|:--:|------|---------|-----------|------|
| 1 | **TC-HMAC-022 기대결과** | "정규화 경로가 allowlist 에 매칭돼 **필터 적용** → 403/411/401" | "…**필터 가드가 적용**된다 — chunked→411 · 상한초과→413 · allowlist 밖→403 · 가드 통과 시 하류 응답(무효 본문 400 / 미발급 request_id 401). **판정 기준은 응답 코드가 아니라 가드가 걸렸는가**다" | 실동작에서 인코딩 변형 3종의 정상 응답은 **400**(가드 통과 후 컨트롤러)이었다. 구 문구의 `403/411/401` 열거만 보면 400 을 "우회"로 오판하기 쉽다 — 실제로 이 케이스의 판정축은 응답 코드가 아니라 가드 적용 여부다. 또 413 이 열거에서 빠져 있었다. |
| 2 | **A-4 머리말** | (없음) | "⚠ 서명 경로 케이스(TC-HMAC-002·004~011·020·025~028)는 **네트워크로 재현할 수 없다**" + Tomcat 400 선차단 실측 근거 + `ls_whk_sign_use` 0행 교차확인 + "정적+단위테스트로만 판정하며 라이브 미확인을 결함으로 기록하지 않는다" | 이 사실을 적어두지 않으면 매 회차 검증자가 같은 재현 시도를 반복하고, 라이브 미확인을 결함으로 오보고할 위험이 있다(CLAUDE.md "동작·정책이 바뀌면 테스트케이스 카탈로그도 갱신" 규칙). A-ISSUE-43 과 짝을 이룬다. |

> ⚠ **미처리 — 상위 담당자 조치 필요**: `A-auth-common.md` 상단 **`## 변경 이력` 표(9~14행)에 3회차 행 추가**가 필요하나, 본 파트의 편집 허용 범위(112~164행) 밖이라 손대지 않았다. 추가할 내용: `| 3 | 2026-08-03 | 2 | 0 | 0 | A-4 웹훅 필터 구간 3차 실동작 재검증 — TC-HMAC-022 기대결과 정밀화(가드 적용 여부가 판정축), A-4 머리말에 "서명 경로 케이스는 라이브 재현 불가(Tomcat 400 선차단)" 명시. 근거 file:line 46행 전수 대조 드리프트 0건 |`

---

## 5. 근거 드리프트 점검

케이스 표의 `file:line` **46행 전건**을 실파일과 대조했다. → **드리프트 0건**.

대조 대상: `HmacWebhookFilter`(98·119·121-130·132-133·136·157-166·214-224·227-233·234-239·240-245·254-263·305-309·311-318·321-326·327-331·333-340·341-346·349-357·358-365·368-375·389-396·398-415·404-409·417·418-443·470-533·476·486-500·506-511·512-527·543-564·552-555·574-576·584-596·610-622·647-652·666-672·726-740) · `WebhookProtectedPaths`(73-75·81-88·100-103·155-161·198-207·227-233·260-279) · `WebhookRateLimiter`(68-71·74·95-114·137-140·207-214·256-273) · `ClientIpResolver`(55-61·70-74·93-107·126-138·139-149·147-154·150-167·193-198·207-217·229-236) · `WebhookIpAllowlist`(46-61·57-61·80-98) · `GenAiWebhookIpAllowlist`(44-77) · `WebhookGateInterceptor`(51-53·54-60) · `WebhookGateConfig`(42-49) · `WebhookConfigProfiles`(32·38-44) · `WebhookGuardPurgeJob`(36-56) · `JdbcWebhookGuardStore`(72·88·157-169) · `HmacSigner`(42-53·48-52) · `SecurityConfig`(162).

---

## 6. 이전 회차 이슈 해소 대조

| 이슈 | 원 회차 | 3차 상태 | 근거 |
|---|:--:|---|---|
| **E-ISSUE-01** — 웹훅 HMAC 필터 URL 퍼센트 인코딩 우회(CWE-436 → 무인증 관통) | 2026-07-25 1차 | **✅ 해소(실동작 실증)** | §0-1 — 인코딩 3변형 모두 가드 적용(411), 정규화 변형 10종 중 컨트롤러 도달 0건. 근본 수정(`RequestPath`+`PathPattern`) + 2단 게이트 + servlet-prefix 회귀 가드 |
| **F-ISSUE-64** — 포털 라벨 PUT 본문 상한 필터의 동일 계열 인코딩 우회 | 2026-08-02 2차 | **✅ 코드상 해소** (라이브 최종 확인은 F 담당) | §0-3 — `PortalLabelBodySizeFilter` 가 동일 규약(`ServletRequestPathUtils`+`PathPattern`, trailing-slash 패턴 별도 등록)으로 재작성됨. javadoc 이 2차 실증 사례를 인용 |
| **A-ISSUE-42** — `trusted-proxy-cidrs=none` 명시에도 "비어 있습니다" WARN | 2026-08-01 1차 | **❌ 미해소(이월)** | 본 문서 A-ISSUE-42 — 3차에도 동일 WARN 실로그 확인 |
| **A-ISSUE-43** — 무서명 411/413 이 `webhook.auth.failed` 미집계 | 2026-08-01 1차 | **❌ 미해소(이월)** | 본 문서 A-ISSUE-41 — 411×14·413×5 유발 후에도 해당 reason 시리즈 부재 |
| **A-ISSUE-41(1차)** — 스트림 nonce 쿠키 shadowing | 2026-08-01 1차 | 본 파트 범위 밖(A-3) | A-3 담당 파트에서 대조 |

> ⚠ 이슈 ID 주의: **1차의 A-ISSUE-41~43 과 본 3차의 A-ISSUE-41~43 은 서로 다른 항목**이다(회차별 번호 할당 규칙에 따름). 대응 관계는 위 표를 따른다.

---

## 7. 방법론 메모 (다음 회차 재현용)

- 웹훅 가드 실동작 검증은 **IP 버킷이 곧 rate limit 버킷**이다. 호스트에서 쏘면 전부 `172.20.0.1` 로 수렴해 5회면 429 로 막힌다. 컨테이너를 발신자로 쓰면 독립 버킷을 얻는다:
  `klid-ai-server`(172.20.0.2, `curl` 있음) · `klid-mock-server`(172.20.0.4, `python3` 있음) · 호스트(172.20.0.1). `klid-frontend` 는 curl/python 없음.
- 400(검증 오류)·429 는 실패 카운터를 **올리지 않는다**(401/403·411·413만 올림) — 테스트 설계 시 유용.
- 로컬 실패 트래커는 **첫 실패로부터 60초**라 분 버킷 경계를 넘어 누적된다. 버킷 수치(`ls_whk_fail_nmtm`)만 보고 "5 미만인데 왜 429" 라고 오판하지 말 것.
- 메트릭 확인용 REVIEWER 토큰은 `POST /v1/dev/tokens` 로 받되 **`userNo` 가 `LS_USER_ROLE` 에 실제 REVIEWER 로 있어야 한다**(예: 1001). 토큰 클레임의 role 만으로는 `/actuator/**` 가 403 이다(역할이 DB 에서 재해석됨).
- DB 접속: `docker exec klid-postgres psql -U klid_user -d klid_system`(`postgres` 롤 없음).
