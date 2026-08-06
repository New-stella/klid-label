# A 클러스터 part2 (A-3·A-4) 2차 검증 결과

> 대상: `docs/test-cases/A-auth-common.md` §A-3(TC-STREAM-001~012, 12건) · §A-4(TC-HMAC-001~046 중 폐기 1건 제외 45건)
> 판정 기준시각: **2026-07-31 02:45~03:00 KST**
> 판정 대상 이미지: `klid-backend:latest` `bdc64ea2ac26` = **HEAD `ca3c712b` 재빌드본**(2026-07-31 02:42 빌드, `_raw/backend-rebuild.md`)
> 방식: 실HTTP 프로브(로컬 스택 5서비스) + DB 직접 조회(`public.ls_whk_sign_use` / `ls_whk_fail_nmtm`) + 코드 정적 대조 + 테스트 자산 grep(**테스트 실행 없음**)
> 파일 수정 없음 — 본 결과 파일 1개만 생성

---

## 집계

| 구분 | 케이스 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기(집계 제외) |
|------|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| A-3 스트림 서명 필터 | 12 | 12 | 0 | 0 | 0 | 0 | 0 | 0 |
| A-4 웹훅 인증 필터 | 45 | 45 | 0 | 0 | 0 | 0 | 0 | 1 (TC-HMAC-001) |
| **합계** | **57** | **57** | **0** | **0** | **0** | **0** | **0** | **1** |

근거 확인 내역: **`[실동작]` 26건 / `[정적]` 31건**.
`[정적]` 이 31건인 주된 이유는 **서명 필수 웹훅 경로가 현재 0개**여서 HMAC 검증 분기 전체가 런타임에 도달하지 않기 때문이다(→ A-ISSUE-11). 기동 차단(BeanInitializationException) 계열은 컨테이너 재기동 금지 제약으로 정적 판정했다.

**근거 드리프트 0건** — A-3·A-4 전 케이스의 `file:line` 이 HEAD 소스와 일치했다(§근거 드리프트).

이슈: **7건**(CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 5). 전건 PASS 이지만 "검증 가능성·형상·커버리지" 축의 관찰 결과를 이슈로 등재했다.

---

## ★1차 CRITICAL 재현 결과

1차 `E-ISSUE-01`(=`A-ISSUE-13` 실증) 은 **무인증 `POST /api/v1/%61ug/callback` 이 필터를 스킵하고 컨트롤러에 도달**(증강행 4건 전이 + `raw_sn=29·31` 생성)한 CWE-436 결함이었다.
본 회차에서 **전 변형 재현 시도 → 전건 차단**. 추가로 `%61ug` 대상 컨트롤러(`/v1/aug/**`) 자체가 Phase 7-A2 에서 제거돼 **공격면이 소멸**했다.

### (1) 구 CRITICAL 경로 (`/v1/aug/**`)

| 경로 | 기대 | 실측 | 판정 |
|------|:--:|:--:|:--:|
| `POST /api/v1/aug/callback` | 401 | **401** `UNAUTHORIZED` | PASS |
| `POST /api/v1/%61ug/callback` (**1차 침투 경로**) | 401 | **401** `UNAUTHORIZED` | **PASS** |
| `POST /api/v1/a%75g/callback` | 401 | **401** | PASS |
| `POST /api/v1/aug/%63allback` | 401 | **401** | PASS |
| `POST /api/v1/%2561ug/callback` (이중 인코딩) | 401 | **401** | PASS |

### (2) 현행 보호 경로(무서명 가드 2종)의 경로 변형 — **"필터 스킵 + 컨트롤러 도달" 입력을 찾지 못함**

판정 기준: ①`400 INVALID_INPUT` = 필터 통과 + 컨트롤러 도달(**정상**, 필터가 적용된 증거) ②`401 인증이 필요합니다.` = Security 체인 차단(라우팅 미성립) ③`411/413` = **필터가 직접 거부**(가장 강한 필터 적용 증거).

| 경로 | 기대 | 실측 | 판정 |
|------|:--:|:--:|:--:|
| `/v1/genai/callback` (대조군) | 필터 적용 → 컨트롤러 | **400** INVALID_INPUT | PASS |
| `/v1/vlm/callback` (대조군) | 필터 적용 → 컨트롤러 | **400** INVALID_INPUT | PASS |
| `/v1/%76lm/callback` | 필터 적용 | **400** / chunked 시 **411**(필터 발행, 로그 `path=/api/v1/%76lm/callback`) | PASS |
| `/v1/vlm/%63allback` | 필터 적용 | **400** | PASS |
| `/v1/g%65nai/callback` | 필터 적용 | **400** / 2MB 시 **413**(필터 발행, 로그 `path=/api/v1/g%65nai/callback`) | PASS |
| `/v1/genai/%63allback` | 필터 적용 | **400** | PASS |
| `/v1/genai/callback/` (trailing slash) | 2xx 아님 | **401** (필터 적용 후 하류 401 — DB 카운터 증가 확인) | PASS |
| `//v1/genai/callback` | 2xx 아님 | **401** | PASS |
| `/v1//genai/callback` · `/v1/genai//callback` | 2xx 아님 | **401** | PASS |
| `/v1/./genai/callback` · `/v1/genai/./callback` | 2xx 아님 | **401** | PASS |
| `/v1/genai/x/../callback` | 2xx 아님 | **401** | PASS |
| `/v1/genai/%2e/callback` · `/v1/genai/%2e%2e/genai/callback` | 2xx 아님 | **401** | PASS |
| `/v1/genai;a=b/callback` · `/v1/genai/callback;a=b` | 2xx 아님 | **401** | PASS |
| `/v1/vlm/callback;jsessionid=x` | 2xx 아님 | **401** | PASS |
| `/v1/GENAI/callback` (대문자) | 2xx 아님 | **401** | PASS |
| `/v1/genai/callback%20` · `/v1/genai/callback.` | 2xx 아님 | **401** (필터 적용 후 하류 401) | PASS |
| `/v1/genai%2fcallback` (인코딩 슬래시) | 차단 | **400** (Tomcat/StrictHttpFirewall 선차단) | PASS |
| `/v1/genai/callback%00` (널바이트) | 차단 | **400** (선차단) | PASS |
| `/v1\genai/callback` (백슬래시) | 차단 | **400** (선차단) | PASS |
| `/v1/genai/callback%0d%0aINJECT` (CRLF) | 2xx 아님 | **401**, 로그 인젝션 미발생 | PASS |
| `/v1/%EF%BD%87enai/callback` (유니코드 전각 g) | 2xx 아님 | **401** | PASS |
| `GET /v1/vlm/callback` | 필터 적용 | **411** (메서드 무관 필터 적용) | PASS |
| `PUT /v1/genai/callback` | 필터 적용 → MVC 405 | **405** | PASS |

**결론: A-ISSUE-13 / E-ISSUE-01 은 HEAD 재빌드본에서 재현되지 않는다.** 2단 게이트(`WebhookProtectedPaths` PathPattern allowlist + `WebhookGateInterceptor` 증거 래퍼)와 `SIGNATURE_REQUIRED` 목록 비움(경로 제거)이 함께 작용한다.

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 상태 | 2차 실측 | 판정 |
|---|---|---|:--:|
| **E-ISSUE-01** HMAC 필터 경로 우회 (CRITICAL) | `%61ug` 무인증 관통, 증강행 전이 + rawSn 생성 | 위 표 전 변형 차단. 필터 판정이 MVC 와 동일 `RequestPath`+`PathPattern`(`WebhookProtectedPaths:253-269`), 판정 불가=보호(fail-closed). 구 `/v1/aug/**` 컨트롤러·DTO 제거로 공격면 소멸 | **해소** |
| **A-ISSUE-13** raw URI 정확일치 판정 | 정적 성립·실증 미완 | 동상. 추가로 `WebhookPathBypassSecurityIT`(11건, 실 `springSecurityFilterChain`+DispatcherServlet)로 회귀 고정 | **해소** |
| **E-ISSUE-04** 빈 시크릿 → 정상 콜백 전건 401 | `.env` 빈 값이 yml 기본값을 덮어 401 | `WEBHOOK_HMAC_SECRET_AUGMENT` = 32B 난수(비-placeholder). 빈 값·32B 미만·커밋 placeholder·미해석 `?…` 4축 기동 차단 구현(`HmacWebhookFilter:214-245`). 정상 무서명 콜백은 실왕복 200 확인(mock→backend VLM 콜백 `result applied request_id=8cc179fb… rawSn=126`) | **해소**(단 성격 변화 — §A-ISSUE-11) |
| **A-ISSUE-12** replay 방어가 시간창뿐 | 윈도우 내 무제한 재전송 | nonce 1회성 소비(`LS_WHK_SIGN_USE`, 키=**정규화 경로**+ts+sig SHA-256) + 미래 skew 30s 비대칭 창 구현. **단 서명 필수 경로 0개라 런타임 미도달**(nonce 테이블 실측 **0행**) | **해소(코드)** / 실동작 미검증 |
| **A-ISSUE-14** rate limit 이 JVM-local | 2노드에서 임계 2배 | 공유 DB 집계(`LS_WHK_FAIL_NMTM`, 현재+직전 버킷 조회) + `adoptRemoteBlock` 승계 구현. **실동작: 401×5 → 429×10, 공유 카운터 정확히 5 에서 정지, `Retry-After: 60`** | **해소**(2노드 승계는 단위테스트 커버) |
| **A-ISSUE-15** clientIp = remoteAddr 전용 | 프록시 뒤 자기유발 DoS | `ClientIpResolver` 신뢰 프록시 CIDR 기반 XFF 파싱 + prd/stg 미설정 기동 차단. **실동작: XFF 회전 15연타(`203.0.113.1~15`)에도 401×5→429 이고 카운터에 위조 IP 미적재** | **해소** |
| **B-ISSUE-25** VLM 콜백 무인증 + rate limit 부재 | 위조 request_id 15연타 전부 401(429 없음) | **실동작: 401×5 → 429×10.** 1차 재현 명령 그대로 실행해 대조. IP allowlist·본문 4MB 캡·request_id 발급 게이트 3계층 동작 | **해소** |
| Phase1 잔여 ① `adoptRemoteBlock` 차단 W+180s | MEDIUM | `WebhookRateLimiter:161-181` 이 각인 만료를 **원격 버킷 시작+60s** 로 산출하고, 지평 경과 시 각인하지 않음(R-5) | **해소** |
| Phase1 잔여 ② `JdbcWebhookGuardStoreIT` `assumeTrue` 초록 skip | MEDIUM | `JdbcWebhookGuardStoreIT.java:171` `Assumptions.assumeTrue` **잔존** | **미해소**(→ A-ISSUE-17) |
| Phase1 잔여 ③ `ClientIpResolver.isProxyAware()` 죽은 API | LOW | 프로덕션 호출처 **0건**(테스트 1건만) **잔존** | **미해소**(→ A-ISSUE-15) |

### 1차 정정 사항 대조 — `HmacWebhookFilterTest` 위양성 위험

> 1차 지적: "17건 존재하나 경로변형 케이스 0건 + 전부 `MockHttpServletRequest` 라 판정 방식을 바꾸면 전건 GREEN 이 되는 위양성 위험".

**구조적으로 개선됨.** `HmacWebhookFilterTest` 는 **63건**으로 확장됐고(여전히 `MockHttpServletRequest` 기반), 그 위양성을 정면으로 겨냥한 **`WebhookPathBypassSecurityIT`(11건)** 가 신설됐다 — `@SpringBootTest`+`@AutoConfigureMockMvc` 로 **실 `springSecurityFilterChain` + 실 DispatcherServlet 라우팅**을 태우고, 파일 javadoc 에 *"필터 단위테스트로 갈음 금지 … MockHttpServletRequest 기반 단위테스트는 필터가 통째로 무력화돼도 GREEN 이 될 수 있다(위양성, S-21)"* 를 명시했다. 커버 변형: 퍼센트 인코딩 · 이중 인코딩 · 대문자 · trailing slash · 이중 슬래시 · 세미콜론 · 경로 순회 · 후행 점 · 널바이트 + **DB 무변경 단언** + StrictHttpFirewall 기본값 유지 단언 + XFF 회전 rate limit + 인코딩 변형 replay.
**잔존 위양성 표면**: nonce/replay/503/해제 4종(TC-HMAC-025~028)은 서명 필수 경로가 0개라 IT 를 만들 수 없어 여전히 단위테스트 단독 커버다(→ A-ISSUE-17).

---

## A-3 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| TC-STREAM-001 | 유효 서명 + 유효 nonce 쿠키 → CHANNEL_INTERNAL+STREAM_SIGNED | PASS | [실동작] | `GET /v1/videos/126/stream?exp&u=1001&sig` + 발급 쿠키 → **206**, `Accept-Ranges: bytes`, `Content-Range: bytes 0-99/34654319`. principal 에 실 sub·재조회 역할이 채워짐은 TC-STREAM-012 로 확증 |
| TC-STREAM-002 | 서명 없으면 미개입 → 401 | PASS | [실동작] | `GET /v1/videos/126/stream`(sig/exp 없음, 헤더 없음) → **401** |
| TC-STREAM-003 | 만료/변조 서명 거부 + sig 전문 로그 미노출 | PASS | [실동작] | 변조 sig → **401**. 로그 실측 `[StreamSign] invalid signature rawSn=126` — **sig·exp·u 미출력**(CWE-532 준수) |
| TC-STREAM-004 | u 변조 시 서명 불일치 거부 | PASS | [실동작] | `u=1001`→`u=2001` 치환 → **401** |
| TC-STREAM-005 | 선인증 컨텍스트 있으면 서명 검증 스킵 | PASS | [실동작] | `Authorization: Bearer <REVIEWER>` + 서명 쿼리 없음 → **206**. `SecurityConfig:166` `addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class)` 실측 일치(부팅 로그 체인 순서 `HmacWebhookFilter → JwtAuthenticationFilter → StreamSignatureFilter → …AnonymousAuthenticationFilter`) |
| TC-STREAM-006 | GET 아니거나 스트림 경로 아니면 미동작 | PASS | [정적] | `StreamSignatureFilter:84-89` 확인. `POST`(서명 URL) → **401** 관측(보안체인 차단, 필터 미개입과 정합) |
| TC-STREAM-007 | rawSn 정규식 1~18자리 숫자만 | PASS | [실동작] | 19자리 `1234567890123456789` → **401**(패스스루 후 fail-closed) |
| TC-STREAM-008 | **쿠키 없이 URL 전체 재사용 시 401** | PASS | [실동작] | 발급 URL 전체(exp·u·sig) 보유 + 쿠키 미부착 → **401**. 발급 응답 URL 에 nonce 미포함 확인 |
| TC-STREAM-009 | 봉인 없는/타 subject 쿠키 채택 불가 | PASS | [실동작] | ①봉인 없는 32-hex `aaaa…` → **401** ②임의 `{32hex}.{64hex}` 위조 봉인 → **401** ③화이트박스로 `sealKey=HMAC(signSecret,"klid-stream-nonce-seal")` 를 재현해 **타 subject(2002)로 봉인한 쿠키**를 만들면 채택됨을 확인(=봉인이 subject 바인딩) |
| TC-STREAM-010 | nonce 쿠키 속성 | PASS | [실동작] | 실측 `Set-Cookie: klid_stream_nonce=…; Path=/api/v1/videos; Max-Age=3600; Expires=…; HttpOnly; SameSite=Lax`. **Secure 없음 = local 프로파일**(판정축이 프로파일임은 `StreamNonceCookie:86-89,214-224` + `StreamNonceCookieSecureFlagTest` 3건) |
| TC-STREAM-011 | sign-secret 미설정 시 봉인키 기동 랜덤 → 전건 불통과 | PASS | [정적] | `StreamNonceCookie:183-190` 확인(미설정 시 `SecureRandom` 32B ephemeral). 재기동 금지로 실동작 미검증 + **전용 회귀 테스트 부재**(→ A-ISSUE-16) |
| TC-STREAM-012 | 서명 경로에도 영상 단위 인가 적용 (B-ISSUE-63 해소) | PASS | [실동작] | 시크릿 보유 화이트박스로 **미배정 WORKER(2002) 의 u 로 유효 서명 URL + 유효 봉인 쿠키**를 생성 → `raw=126` **403** `"본인에게 배정되지 않은 영상입니다."` / `raw=20012` **403** / 미존재 사용자(999999) **403** `"라벨 접근 권한이 없습니다."` / 배정 WORKER(2001) **206**. 배정 상태: `ls_task_assignment` 46행 `usr_no=2001, raw_sn=126` |

---

## A-4 결과표

> ⚠ 본 표의 `[정적]` 다수는 **서명 필수 경로가 0개**(`WebhookProtectedPaths.SIGNATURE_REQUIRED = List.of()`)여서 HMAC 검증 분기(TC-HMAC-002·004~014·020·025~028)가 런타임에 도달하지 않기 때문이다. 이 구조적 제약 자체를 A-ISSUE-11 로 등재했다.

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|----------|:--:|------|------|
| ~~TC-HMAC-001~~ | ~~유효 HMAC 콜백 통과~~ | — | — | **[폐기 2026-07-30]** 집계 제외 |
| TC-HMAC-002 | 시크릿 미설정 + 경로 판정 불가 → 401 `secret_missing` | PASS | [정적] | `HmacWebhookFilter:214-224`(경로 0개면 미설정 허용) + `:311-318`(런타임 401). 현 형상은 시크릿 설정 상태 + 판정 불가 URI 를 컨테이너가 먼저 400 으로 끊어 실동작 재현 불가. 테스트 `HmacWebhookFilterTest#emptySecret_bootsWhenNoSignatureRequiredPaths`·`#unresolvablePath_stillReturns401_withoutSecret` |
| TC-HMAC-003 | 시크릿 32B 미만 부팅 차단 | PASS | [정적] | `:240-245`. 재기동 금지 → `#shortSecret_failsBoot` |
| TC-HMAC-004 | 서명/timestamp 헤더 누락 401 + recordFailure | PASS | [정적] | `:333-340` 일치. `#protectedPath_withoutSignature_returns401_andIsNotSkipped` |
| TC-HMAC-005 | 미지원 서명 접두사 401 | PASS | [정적] | `:341-346` 일치 |
| TC-HMAC-006 | timestamp 비숫자 401 | PASS | [정적] | `:349-357` 일치 |
| TC-HMAC-007 | 과거 300s / **미래 30s 비대칭** | PASS | [정적] | `:132-133 FUTURE_SKEW_MS=30_000L`, `:358-365` `ageMs > window*1000 \|\| ageMs < -FUTURE_SKEW_MS`. `Math.abs` 제거 확인. `#augmentReplayTimestampExceeded_returns401`·`#futureTimestampBeyondSkew_returns401` |
| TC-HMAC-008 | 상수시간 비교 401 (CWE-208) | PASS | [정적] | `:389-396` `MessageDigest.isEqual(providedBytes, computedBytes)` — `equals` 아님 |
| TC-HMAC-009 | Content-Length 누락 401(서명 경로) / 411(무서명) 정책차 | PASS | [정적] | `:321-326`(401) vs `:506-511`(411). 무서명 411 은 실동작 확인(TC-HMAC-017) |
| TC-HMAC-010 | 서명 경로 본문 1MB 초과 413 | PASS | [정적] | `:98 MAX_WEBHOOK_BODY_BYTES`, `:327-331` |
| TC-HMAC-011 | 위조 Content-Length / 대용량 스트림 읽기상한 | PASS | [정적] | `:368-375` + `:726-740 readCapped`(8KB 청크, 상한 초과 즉시 throw — 전량 버퍼링 없음) |
| TC-HMAC-012 | 실패 5회 초과 60초 backoff (로컬+공유 2단) | PASS | **[실동작]** | 위조 `request_id` 15연타 → **401×5 → 429×10**, 응답 헤더 `Retry-After: 60`. DB `ls_whk_fail_nmtm` 버킷 `02:51:00` = **정확히 5**(차단 후 공유 쓰기 중단 확인). `:305-309`·`WebhookRateLimiter:95-114` |
| TC-HMAC-013 | 카운터 reset 은 **서명 검증 성공** 시에만 | PASS | [정적] | `:417`(서명 성공 직후만 `reset`), `WebhookRateLimiter:207-214`(현재+직전 버킷 대칭 삭제). 무서명 2xx 에는 reset 호출부 없음(`countDownstreamAuthOutcome` 는 401/403 만 처리) |
| TC-HMAC-014 | 로컬 트래커 hard cap(4096) evict | PASS | [정적] | `WebhookRateLimiter:74,256-273`(만료 removeIf → 오래된 순 evict) |
| TC-HMAC-015 | 로그 CR/LF/탭 치환 + 200자 절단 | PASS | [정적] | `WebhookProtectedPaths:100-103,227-233`. 실측 로그에서 `path=/api/v1/genai/callback%20`·`%76lm` 등이 **1행으로 안전 출력**되고 CRLF 경로 요청 시 로그 인젝션 미발생 |
| TC-HMAC-016 | VLM 무서명 — 가드 순서 rate limit→IP→CL→size cap | PASS | **[실동작]** | 서명 헤더 없이 통과해 컨트롤러 도달(400/401). 순서 확증: backoff 상태에서는 chunked 요청도 **411 이 아니라 429** 가 우선 반환됨. 최종 인증은 `VlmResultService` request_id 게이트(미발급 → 401) 실측 |
| TC-HMAC-017 | VLM 콜백 chunked 411 + recordFailure | PASS | **[실동작]** | `Transfer-Encoding: chunked` → **411** `"Content-Length 헤더가 필요합니다. (chunked 전송은 허용되지 않습니다)"` + DB 카운터 `02:50:00` 버킷 +1 확인 |
| TC-HMAC-018 | VLM 4MB 초과 413 | PASS | **[실동작]** | 5MB 본문 → **413** `"Webhook 본문은 4096KB 를 초과할 수 없습니다."` / 2MB 본문은 통과(400 검증오류) |
| TC-HMAC-019 | 미등록 경로 필터 미동작 — 판정 불가는 적용 | PASS | **[실동작]**+[정적] | `/v1/aug/**`·`/v1/./genai/…` 등은 필터 로그 0건(=shouldNotFilter) / `/v1/vlm/**`·`/v1/genai/**` 는 로그 발생. fail-closed 분기는 `:254-263`·`WebhookProtectedPaths:155-161` 정적 |
| TC-HMAC-020 | HmacSigner 와 필터가 동일 서명 규칙 공유 | PASS | [정적] | `:574-576` → `HmacSigner.hex`(`:42-53`) 단일 위임 |
| TC-HMAC-021 | 시크릿이 로그/예외에 미노출 | PASS | [정적] | `HmacSigner:48-52` 예외 메시지 `"HMAC 서명 계산 실패 (algorithm=HmacSHA256)"` — 시크릿·메시지 본문 미포함 |
| TC-HMAC-022 | 퍼센트 인코딩 경로 변형도 필터 적용 (**CRITICAL 회귀 가드**) | PASS | **[실동작]** | `/v1/%76lm/callback` + chunked → **411**, `/v1/g%65nai/callback` + 2MB → **413**, 두 건 모두 **필터 로그가 인코딩 경로 그대로** 남음 = 필터가 직접 발행. `/v1/vlm/%63allback`·`/v1/genai/%63allback` 도 컨트롤러 도달(400) |
| TC-HMAC-023 | 필터 우회 라우팅은 인터셉터가 401 | PASS | [정적] | `WebhookGateInterceptor:54-60`(`@RequestBody` 역직렬화 전 preHandle). 런타임 재현 불가 — 우회 입력을 찾지 못했다(위 CRITICAL 표). `WebhookGateInterceptorTest` 7건 |
| TC-HMAC-024 | ASYNC 재디스패치는 2단 게이트 통과 | PASS | [정적] | `:51-53`. `#asyncDispatch_isNotBlocked` + `#requestDispatchWithoutMarker_stillBlocked`(예외가 우회로가 아님) |
| TC-HMAC-025 | 서명 nonce 1회성 소비 → 재전송 409 | PASS | [정적] | `:398-415`·`:584-596`(키 = **정규화 경로**+ts+sig SHA-256). **서명 필수 경로 0개라 런타임 미도달**(→ A-ISSUE-11). `#replayWithinWindow_returns409_not401`·`#nonceKey_usesCanonicalPath` |
| TC-HMAC-026 | nonce 소비는 서명 검증 **성공 이후**에만 | PASS | **[실동작]**+[정적] | 코드 `:59-62,398-401`. **DB 실측: 본 회차 전 프로브(무인증·위조 60여 건) 후에도 `ls_whk_sign_use` 행수 = 0** → pre-auth write 0건 |
| TC-HMAC-027 | nonce 저장소 장애 시 503 + `Retry-After: 30` | PASS | [정적] | `:404-409`·`:647-652`. `#nonceStoreUnavailable_failsClosed` |
| TC-HMAC-028 | 하류 5xx 시 nonce 예약 해제 | PASS | [정적] | `:418-443`(`committed = status < 500`, finally 에서 release / release 실패는 결과 불변). `#downstreamServerError_releasesNonce…`·`#downstreamException_releasesNonce` |
| TC-HMAC-029 | 커밋된 공개 placeholder 시크릿은 local 외 기동 차단 | PASS | [정적] | `:157-166 KNOWN_PLACEHOLDER_SECRETS`(4종)·`:234-239`. 현 env 는 32B 난수라 미해당 |
| TC-HMAC-030 | `?`로 시작하는 미해석 placeholder 기동 차단 | PASS | [정적] | `:227-233`. `#unresolvedPlaceholderSecret_failsBoot` |
| TC-HMAC-031 | genai IP allowlist 미설정 = **전면 403**(fail-closed) | PASS | [정적] | `GenAiWebhookIpAllowlist:44-77`(빈 값·`none` → `allowed.isEmpty()` → 항상 false) vs `WebhookIpAllowlist:80-98`(빈 값 → 항상 true) — **의도된 비대칭 확인**. `GenAiWebhookIpAllowlistTest` 4건. ⚠ **현 배포 형상은 `0.0.0.0/0` 명시**라 런타임 403 유발 불가(→ A-ISSUE-13) |
| TC-HMAC-032 | 비허용 IP 403 도 실패 집계 | PASS | [정적] | `:486-500`(`recordFailure` + `countAuthFailure("ip_not_allowed")` 후 403). 현 형상 전면 허용이라 실동작 불가. `#genAiCallbackFromDisallowedIp_isCountedInRateLimit` |
| TC-HMAC-033 | 무서명 하류 401/403 집계, 2xx 는 reset 안 함 | PASS | **[실동작]**+[정적] | 위조 request_id 15연타 → **401×5 → 429×10** 실측. "2xx 미reset" 은 `:543-564` 에 reset 호출부가 없음으로 정적 확인 |
| TC-HMAC-034 | 집계를 끄는 런타임 분기가 없다 (XFF) | PASS | **[실동작]** | 청정 버킷에서 **매 요청 XFF 회전(`203.0.113.1~15`)** → **401×5 → 429×10**. DB 카운터에 `203.0.113.*` 행 **0건**, `172.217.221.84`(remoteAddr) 로만 5건 집계 = 헤더로 카운터를 갈라칠 수 없음 |
| TC-HMAC-035 | genai 본문 상한 1MB (VLM 4MB 와 다름) | PASS | **[실동작]** | genai 2MB → **413** `"본문은 1024KB 를 초과할 수 없습니다."` / 동일 2MB 가 VLM 에서는 통과 → 비대칭 실증 |
| TC-HMAC-036 | 신뢰 프록시 밖이면 XFF 폐기 | PASS | **[실동작]** | `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 상태에서 XFF 부착 요청의 집계 키가 remoteAddr. 1회 WARN 제한은 `ClientIpResolver:207-217` 정적 |
| TC-HMAC-037 | 신뢰 홉 우측부터 벗기고 최초 비신뢰 값 채택 | PASS | [정적] | `ClientIpResolver:150-167`(역순 순회, 비정상 값 만나면 remoteAddr 폴백). 현 형상 `none` 이라 실동작 불가. `#trustedProxyXff_isolatesClients` |
| TC-HMAC-038 | XFF 파싱 상한(길이 1024 · 홉 20) | PASS | [정적] | `:70-74 MAX_XFF_LENGTH/MAX_XFF_HOPS`, `:147`(길이 초과 시 헤더 무시), `:153 .limit(MAX_XFF_HOPS)` |
| TC-HMAC-039 | remoteAddr 비-IP 리터럴 → `unknown` sentinel | PASS | [정적] | `:126-138`·`:193-198 isValidIp→WebhookCidrParser.isIpLiteral`·`:229-236`(1회 WARN). `#nonLiteralRemoteAddr_isNotTrusted`·`#hostnameLikeXffValue_isRejected` |
| TC-HMAC-040 | prd/stg trusted-proxy-cidrs 미설정 기동 차단 | PASS | [정적] | `ClientIpResolver:93-107` + `WebhookConfigProfiles:32,38-44`(`{prd,stg}`). `#prdWithoutTrustedProxyCidrs_failsBoot`·`#stgWithoutWebhookGuardConfig_failsBoot`·`#localAndDevProfiles_bootWithoutExplicitConfig`. ⚠ 판정축이 프로파일 단일 (→ A-ISSUE-12) |
| TC-HMAC-041 | prd/stg vlm allowed-ip-cidrs 미설정 기동 차단 | PASS | [정적] | `WebhookIpAllowlist:46-61`. `#prdWithoutVlmAllowlist_failsBoot`. ⚠ 동일(→ A-ISSUE-12) |
| TC-HMAC-042 | CIDR 형식 오류는 기동 차단 | PASS | [정적] | `WebhookCidrParser.parseStrict`(프리픽스 비숫자·주소폭 초과·호스트명 전부 `BeanInitializationException`). `#invalidTrustedProxyCidr_failsBoot`·`#invalidVlmAllowlistCidr_failsBoot`·`GenAiWebhookIpAllowlistTest#malformedCidrFailsStartup` |
| TC-HMAC-043 | 인증 실패 메트릭 태그는 저카디널리티 상수뿐 | PASS | **[실동작]** | `GET /actuator/metrics/webhook.auth.failed` → `availableTags: reason=[downstream_unauthorized], path=[vlm, genai]`, COUNT 14. **원시 URI 태그 0건**(인코딩 변형·임의 경로 프로브를 다수 던진 뒤 실측) |
| TC-HMAC-044 | 필터 실행 경로는 Security 체인 1개로 고정 | PASS | **[실동작]** | 부팅 로그 `Filter hmacWebhookFilterRegistration was not registered (disabled)` + `DefaultSecurityFilterChain` 목록에 `HmacWebhookFilter` **1회만** 등장 |
| TC-HMAC-045 | 가드 만료행 주기 정리 | PASS | **[실동작]**+[정적] | 부팅 로그 `[Webhook] guard purge job scheduled intervalMs=600000`. SQL 은 조건부 DELETE (`JdbcWebhookGuardStore:72,88` `WHERE EXPD_DT < CURRENT_TIMESTAMP`) → 2노드 동시 무해. 최소 60s clamp·초기지연 5분 `WebhookGuardPurgeJob:45-54` |
| TC-HMAC-046 | 저장소 장애 정책 비대칭 (nonce=fail-closed / rate limit=fail-open) | PASS | [정적] | `:404-409`(503) vs `WebhookRateLimiter:137-140`·`JdbcWebhookGuardStore:139-143`(`UNAVAILABLE` → 로컬만으로 계속). `#nonceStoreUnavailable_failsClosed`·`#sharedRateLimitStoreDown_failsOpen` |

---

## 근거 드리프트

**0건.** A-3 12건 · A-4 45건 전 케이스의 `file:line` 을 HEAD 소스와 대조했고 전부 일치했다. 표본 검증 내역:

| 케이스 | 카탈로그 근거 | HEAD 실측 | 일치 |
|---|---|---|:--:|
| TC-HMAC-002 | `HmacWebhookFilter.java:214-224,311-318` | `ensureSecretConfigured` 214~, `secret missing` 311-318 | ✓ |
| TC-HMAC-007 | `HmacWebhookFilter.java:132-133,358-365` | `FUTURE_SKEW_MS` 133, 윈도우 판정 358-365 | ✓ |
| TC-HMAC-011 | `HmacWebhookFilter.java:368-375,726-740` | 본문 캐싱 368-375, `readCapped` 726-740 | ✓ |
| TC-HMAC-035 | `HmacWebhookFilter.java:121-130,476` | `MAX_GENAI_BODY_BYTES` javadoc 121-130, `maxBytes` 선택 476 | ✓ |
| TC-HMAC-045 | `WebhookGuardPurgeJob.java:36-56`; `JdbcWebhookGuardStore.java:72,88` | 클래스 36 / start 56, `PURGE_NONCE` 72 · `PURGE_FAILURE` 88 | ✓ |
| TC-STREAM-010 | `StreamNonceCookie.java:214-224` | `buildCookie` 214-224 | ✓ |
| TC-STREAM-012 | `StreamSignatureFilter.java:139-148,160-169` | principal 구성 139-148, `resolveRole` 160-169 | ✓ |

> 참고: `WebhookProtectedPaths.java:253-269`(TC-HMAC-022 근거)는 `pathWithinApplication` 본체 253-269 로 정확히 대응한다.

---

## 이슈 상세

### [A-ISSUE-11] TC-HMAC-002/004~014/020/025~028 — HMAC 서명 검증 분기 전체가 런타임 도달 불가(죽은 보안 표면)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그가 열거한 HMAC 검증 통제(헤더 검사·timestamp 윈도우·상수시간 비교·nonce 1회성 소비·replay 409·503 fail-closed·5xx 시 nonce 해제)는 **실제 요청 위에서 동작이 확인 가능**해야 한다.
- **현재 동작(이슈 내용)**: 서명 필수 경로 목록이 비어 있다.
  ```java
  // WebhookProtectedPaths.java:88
  private static final List<PathPattern> SIGNATURE_REQUIRED = List.of();
  // :128-134  판정 불가일 때만 true
  public static boolean requiresSignature(HttpServletRequest request) {
      PathContainer path = pathWithinApplication(request);
      if (path == null) return true;              // fail-closed
      return matchesAny(SIGNATURE_REQUIRED, path);
  }
  ```
  즉 `handleSignatureRequired`(`HmacWebhookFilter:299-432`)에 도달하는 유일한 조건은 **`RequestPath.parse` 실패(경로 판정 불가)** 인데, 그런 URI 는 Tomcat/StrictHttpFirewall 이 앞단에서 400 으로 끊어 실무상 도달하지 않는다. 실측 근거:
  - `ls_whk_sign_use` 행수 = **0**(스택 기동 이후 누적, 파이프라인 실구동 중인데도 0)
  - `webhook.auth.failed` 의 `reason` 태그가 **`downstream_unauthorized` 단일** — 서명 계열 사유(`missing_signature`/`signature_mismatch`/`timestamp_window`/`secret_missing`)가 한 번도 발생하지 않았다
  - `WEBHOOK_HMAC_SECRET_AUGMENT`(32B 난수)는 **어떤 요청도 검증하지 않는 dead config**
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -tAc \
    "SELECT count(*) FROM public.ls_whk_sign_use"            # → 0
  curl -s -H "Authorization: Bearer $RT" \
    localhost:18081/api/actuator/metrics/webhook.auth.failed  # reason=[downstream_unauthorized] 만
  grep -n "SIGNATURE_REQUIRED = List.of()" backend/src/main/java/.../WebhookProtectedPaths.java
  ```
- **영향**: 보안 결함이 아니라 **검증 가능성·유지보수 리스크**다. ①TC 15건이 단위테스트(`MockHttpServletRequest`) 단독 근거로만 남아 1차가 지적한 위양성 표면이 이 구간에 잔존한다 ②`LS_WHK_SIGN_USE` 테이블·purge SQL·nonce 해제 로직·`HmacSigner` 가 전부 미가동 코드로 유지된다 ③운영자가 "시크릿을 설정했으니 웹훅이 서명 검증된다"고 오독할 수 있다(실제 보호는 IP allowlist + rate limit + request_id 게이트 3계층뿐).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 선택지 ①현행 유지 + `WEBHOOK_HMAC_SECRET_AUGMENT` 를 배포 형상(`.env`/compose)에서 제거하고 "서명 필수 경로 0개" 를 운영 문서에 명시 ②장래 서명 웹훅이 생길 때까지 HMAC 분기를 유지하되, 관련 TC 의 기대결과에 "런타임 미도달 — 단위테스트 근거" 를 명기해 회차마다 재조사되지 않게 한다.

---

### [A-ISSUE-12] TC-HMAC-040/041 — 웹훅 보안 설정 기동 강제가 프로파일 축만 보고 `ENV` 배포 표식 축이 없다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md`("클러스터링은 stg/prd 에서 fail-closed") 가 못 박은 프로젝트 표준은 **프로파일 allowlist + `ENV` 배포 표식 독립 2축**이다 — `SPRING_PROFILES_ACTIVE=dev` 로 낮춰도 배포 환경에서는 우회되지 않아야 한다.
- **현재 동작(이슈 내용)**: 웹훅 가드만 프로파일 단일 축이다.
  ```java
  // WebhookConfigProfiles.java:38-44
  static boolean requiresExplicitConfig(Environment environment) {
      if (environment == null) return false;
      return List.of(environment.getActiveProfiles()).stream()
              .anyMatch(EXPLICIT_CONFIG_PROFILES::contains);   // {"prd","stg"} 뿐
  }
  ```
  같은 저장소의 동종 가드는 2축이다 — `QuartzClusteringGuard:55` *"배포 환경 표식(`ENV`) — `DevProfileGuard.DEPLOYED_ENVS` 와 동일 기준"*, `ProfileGatedUrlPolicy:49-52` `DEPLOYED_ENV_MARKERS = {"stg","prd"}`, `DevProfileGuard:42` `DEPLOYED_ENVS = {"stg","prd"}`. 즉 stg/prd 호스트에서 `SPRING_PROFILES_ACTIVE=dev` 로 기동하면 Quartz·외부 URL 정책은 기동을 막지만 **`webhook.trusted-proxy-cidrs`/`webhook.vlm.allowed-ip-cidrs` 는 조용히 빈 기본값**(XFF 전면 무시 + VLM allowlist 전면 허용)으로 떨어진다.
- **재현/확인 경로**:
  ```bash
  grep -rn "DEPLOYED_ENVS\|DEPLOYED_ENV_MARKERS" backend/src/main/java   # 3개 클래스에서 ENV 축 사용
  grep -n "getActiveProfiles" backend/src/main/java/.../WebhookConfigProfiles.java  # ENV 축 없음
  # 재현: ENV=prd SPRING_PROFILES_ACTIVE=dev 로 기동 → QuartzClusteringGuard 는 차단, 웹훅 가드는 통과
  ```
- **영향**: 보안 — CWE-1188(Insecure Default) / A-ISSUE-15 원상복귀 경로. 배포 실수 한 줄로 rate limit 귀속(XFF)과 VLM IP allowlist 두 통제가 동시에 무력화되는데 **기동은 성공**한다. 방어 정책 강도의 비대칭이며, 프로젝트가 이미 채택한 표준(2축)에서 이탈해 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `WebhookConfigProfiles.requiresExplicitConfig` 에 `DevProfileGuard.DEPLOYED_ENVS` 와 동일한 `ENV` 표식 축을 OR 결합(새 환경변수 발명 금지). 회귀 가드로 "`ENV=prd` + `SPRING_PROFILES_ACTIVE=dev` + 웹훅 설정 미지정 → 기동 실패" 테스트 1건 추가.

---

### [A-ISSUE-13] TC-HMAC-031/032/037/041 — 현 배포 형상이 무서명 콜백 2종의 IP allowlist 계층을 실효 0 으로 만든다

- **심각도**: LOW (local/dev 한정 · 의도된 완화일 가능성 높음)
- **기대 동작(기대효과)**: 무서명 웹훅(VLM·생성형 AI)의 3계층 방어 중 **1계층이 IP allowlist** 다. genai 는 fail-closed(미설정=전면 차단)로 설계돼 있다.
- **현재 동작(이슈 내용)**: 실행 중 컨테이너 env 실측
  ```
  WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0     # 전면 허용 (fail-closed 설계를 명시적으로 해제)
  WEBHOOK_VLM_ALLOWED_IP_CIDRS=none            # 미적용
  WEBHOOK_TRUSTED_PROXY_CIDRS=none             # XFF 전면 무시
  ```
  부팅 로그도 `[Webhook] 생성형 AI 콜백 IP allowlist 활성 count=1` 로 "활성" 이라 표시되나 그 1건이 `0.0.0.0/0` 이다. 결과적으로 두 콜백 경로 모두 **rate limit + request_id 게이트 2계층**으로만 보호된다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -E "WEBHOOK_(GENAI|VLM)_ALLOWED_IP_CIDRS|WEBHOOK_TRUSTED_PROXY_CIDRS"
  curl -i -X POST localhost:18081/api/v1/genai/callback -H 'Content-Type: application/json' -d '{}'  # 403 아닌 400
  ```
- **영향**: 코드 결함 아님(`GenAiWebhookIpAllowlist` 는 설계대로 동작). 다만 **TC-HMAC-031/032 의 403 경로를 실동작으로 검증할 수 없게** 만들고, 이 값이 stg/prd 로 전파되면 fail-closed 설계 의도가 무력화된다. `IpAddressMatcher` 는 IPv4 CIDR 만 등록돼 있어 IPv6 발신자는 여전히 차단된다는 점도 부수 확인.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** local/dev 는 현행 유지하되 ①로그 문구를 "전면 허용(0.0.0.0/0) 명시됨" 으로 구분 출력 ②stg/prd 배포 템플릿(`deploy/onprem/env.template`)에서 `0.0.0.0/0` 사용을 금지하는 체크를 두는 방안.

---

### [A-ISSUE-14] TC-HMAC-017/018/035/043 — 무서명 경로의 **필터 단계** 실패(411/413)가 `webhook.auth.failed` 메트릭에 집계되지 않는다

- **심각도**: LOW
- **기대 동작(기대효과)**: rate limit 에 집계되는 실패는 관측 지표에도 나타나야 운영자가 "왜 벤더가 429 를 받는가" 를 사유별로 판별할 수 있다.
- **현재 동작(이슈 내용)**: `handleGuardOnly` 는 411/413 에서 `rateLimiter.recordFailure(clientIp)` 만 호출하고 `countAuthFailure(...)` 를 호출하지 않는다.
  ```java
  // HmacWebhookFilter.java:506-518
  if (contentLength < 0) { ...; rateLimiter.recordFailure(clientIp); writeLengthRequired(response); return; }
  if (contentLength > maxBytes) { ...; rateLimiter.recordFailure(clientIp); writePayloadTooLarge(...); return; }
  ```
  반면 서명 경로는 `fail(...)`(`:604-608`)이 항상 `countAuthFailure` 를 부르고, 무서명 경로의 403(`:497`)·하류 401(`:563`)도 부른다. 실측으로도 411×3 · 413×2 를 발생시킨 뒤 메트릭 `reason` 태그는 `downstream_unauthorized` 뿐이었다.
- **재현/확인 경로**:
  ```bash
  curl -X POST localhost:18081/api/v1/vlm/callback -H 'Transfer-Encoding: chunked' -H 'Content-Type: application/json' -d '{}'   # 411
  curl -s -H "Authorization: Bearer $RT" localhost:18081/api/actuator/metrics/webhook.auth.failed   # reason 에 length_required/payload_too_large 없음
  ```
- **영향**: 관측성 비대칭. 보안 통제 자체는 정상(카운터는 증가). 429 원인 분석 시 "필터 단계에서 끊긴 트래픽" 이 메트릭에 보이지 않아 오진 가능.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `writeLengthRequired`/`writePayloadTooLarge` 직전에 `countAuthFailure(path.tag(), "length_required"|"payload_too_large")` 를 추가(태그는 기존과 동일한 저카디널리티 상수).

---

### [A-ISSUE-15] TC-STREAM-006/007 — `StreamSignatureFilter.shouldNotFilter` 가 여전히 raw URI 정규식이다(A-ISSUE-13 과 동일 패턴) + `ClientIpResolver.isProxyAware()` 죽은 API 잔존

- **심각도**: LOW
- **기대 동작(기대효과)**: 1차 A-ISSUE-13 의 교훈은 "필터 적용 판정과 MVC 라우팅이 **동일한 경로 표현**을 써야 한다" 였고, 웹훅 필터는 그대로 이행됐다(`RequestPath`+`PathPattern`).
- **현재 동작(이슈 내용)**: 스트림 필터는 미이행이다.
  ```java
  // StreamSignatureFilter.java:50,84-89,171-181
  private static final Pattern STREAM_PATH = Pattern.compile("^/v1/videos/(\\d{1,18})/stream$");
  protected boolean shouldNotFilter(HttpServletRequest request) {
      if (!"GET".equalsIgnoreCase(request.getMethod())) return true;
      return !STREAM_PATH.matcher(stripContext(request)).matches();   // getRequestURI() 원문
  }
  ```
  실동작 확인: `/v1/videos/126/%73tream?exp&u&sig` + 유효 쿠키 → **401**. 즉 **스킵 = 인증 미부여 = 거부** 방향이라 현재는 무해하다(1차 A-ISSUE-13 도 이 점을 명시했다).
  별건으로 Phase1 잔여 ③ `ClientIpResolver:114 isProxyAware()` 는 프로덕션 호출처 **0건**(테스트 1건만)으로 여전히 죽은 API 다 — "프록시 인지 여부로 보안 통제를 분기해도 된다" 는 폐기된 설계(R-1)를 재유입시킬 표면.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" -H "Cookie: klid_stream_nonce=..." \
    --path-as-is "http://localhost:18081/api/v1/videos/126/%73tream?exp=..&u=1001&sig=.."   # 401
  grep -rn "isProxyAware" backend/src   # main 1(정의) + test 1(단언), 호출처 0
  ```
- **영향**: 현재 무해(fail-closed 방향). 다만 ①동일 뿌리의 규칙이 코드베이스에 2벌로 남아 있고 ②장래 이 필터가 "인증 부여" 가 아니라 "차단" 역할을 겸하게 되면 즉시 우회 표면이 된다. `isProxyAware()` 는 오해 재유입 위험.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 스트림 필터도 `RequestPath`+`PathPattern` 으로 통일(웹훅과 같은 규칙 1벌)하고, `isProxyAware()` 는 삭제하거나 `@Deprecated` + javadoc 에 "보안 분기 금지" 를 명시.

---

### [A-ISSUE-16] TC-STREAM-011 — `authoring.stream.sign-secret` 미설정 시 봉인키 fail-closed 회귀 테스트 부재

- **심각도**: LOW
- **기대 동작(기대효과)**: "시크릿 미설정 → JVM 기동 랜덤 파생키 → 어떤 쿠키도 봉인 검증 통과 불가" 는 보안 통제이므로 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: 구현은 존재한다.
  ```java
  // StreamNonceCookie.java:183-190
  private static byte[] deriveSealKey(String signSecret) {
      if (signSecret == null || signSecret.isBlank()) {
          byte[] ephemeral = new byte[32];
          new SecureRandom().nextBytes(ephemeral);
          return ephemeral;                      // fail-closed
      }
      return hmac(signSecret.getBytes(StandardCharsets.UTF_8), SEAL_KEY_LABEL);
  }
  ```
  그러나 `StreamNonceCookieSecureFlagTest`(3건)는 Secure 플래그만 다루고, `StreamSignedUrlControllerTest`(20건)는 시크릿이 설정된 전제다. 빈 시크릿 경로를 검증하는 테스트가 없다.
- **재현/확인 경로**: `grep -rn "deriveSealKey\|ephemeral\|sign-secret" backend/src/test/java` → 봉인키 fail-closed 단언 0건
- **영향**: 회귀 감지 공백. 누군가 "미설정 시 상수 키" 로 바꿔도 테스트가 잡지 못한다(그 순간 임의 쿠키가 전건 채택돼 URL 유출 = 재생 가능 상태로 복귀).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** 빈 시크릿으로 `StreamNonceCookie` 를 2회 생성해 서로의 `seal()` 결과가 불일치함(=키가 인스턴스별 랜덤)을 단언하는 단위테스트 1건.

---

### [A-ISSUE-17] TC-HMAC-025~028 + Phase1 잔여 ② — nonce/replay 계열의 위양성 안전망 구멍이 잔존한다

- **심각도**: LOW
- **기대 동작(기대효과)**: 1차 교훈 3 *"새로 쓰는 테스트마다 '이 수정을 되돌리면 실패하는가' 를 실측한다"* 에 따라, 보안 통제는 되돌렸을 때 반드시 빨간불이 켜져야 한다.
- **현재 동작(이슈 내용)**: 두 갈래로 구멍이 남았다.
  1. TC-HMAC-025~028(nonce 1회성 소비 · pre-auth write 차단 · 503 fail-closed · 5xx 시 해제)의 유일한 근거가 `HmacWebhookFilterTest`(`MockHttpServletRequest` 사용 72회)다. 실 체인 IT 를 만들 수 없는 이유는 **서명 필수 경로가 0개**이기 때문(A-ISSUE-11)이며, `WebhookPathBypassSecurityIT` 의 replay 케이스는 nonce 가 아니라 genai **멱등 원장** 경로를 검증한다.
  2. Phase1 잔여 ② 그대로 — 시계 통일(R-6) 회귀를 지키는 테스트가 조건부 skip 가능하다.
     ```java
     // JdbcWebhookGuardStoreIT.java:171
     Assumptions.assumeTrue(  ...  );   // 초록 skip 가능
     ```
- **재현/확인 경로**:
  ```bash
  grep -c "MockHttpServletRequest" backend/src/test/java/.../HmacWebhookFilterTest.java   # 72
  grep -n "assumeTrue" backend/src/test/java/.../JdbcWebhookGuardStoreIT.java             # 171
  ```
- **영향**: 회귀 감지 신뢰도. 다만 해당 코드 경로가 현재 미가동(A-ISSUE-11)이라 실피해 가능성은 낮다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`assumeTrue` 를 제거하고 시계 차이를 테스트가 직접 주입(또는 skip 시 실패로 승격) ②A-ISSUE-11 의 방향이 "현행 유지" 로 확정되면, 해당 TC 들의 근거 컬럼에 "단위테스트 단독 — 실체인 IT 불가(경로 0개)" 를 명기해 매 회차 재조사를 방지.

---

## 판정 환경 메모 (다음 회차 참고)

1. **backend 이미지는 HEAD `ca3c712b` 재빌드본**이다. `_raw/stack-bringup.md` §2/§7-1 의 "11개 커밋 뒤처짐" 단서는 본 파트 판정에는 **적용되지 않는다**(`backend-rebuild.md` §7).
2. **DB 데이터가 stack-bringup 시점과 또 달라졌다** — rawSn 123/124/125 는 사라지고 **126(COMPLETED/비식별 Y/`ls_deident_proc_log` SUCCEEDED) · 127 · 128(PENDING/`F`)** 이 존재한다. 다른 에이전트가 동시에 파이프라인을 구동 중(검증 중 VLM 콜백 왕복·`ls_task_assignment` 46행 신규 배정 관측). **A-3 실동작 참조는 rawSn=126** 을 썼다.
3. **호스트발 요청의 `remoteAddr` 이 `172.217.221.84`** 로 관측된다(Docker Desktop NAT). 컨테이너발 요청은 `172.18.0.4` 로 정상 분리되며, rate limit 이 출처별로 독립 집계됨을 확인했다 — **앱 결함이 아니라 환경 특성**이다. 다만 이 값이 공유 카운터 테이블(`LS_WHK_FAIL_NMTM.CALL_IP_ADDR`)에 그대로 적재되므로, IP 기반 케이스를 판정할 때 이 주소를 "호스트 발신자" 로 읽을 것.
4. **rate limit 이 검증 자체를 방해한다** — 같은 출처에서 인증 실패 5회를 넘기면 60초 backoff 가 걸려 후속 프로브가 전부 429 가 된다. 무서명 경로 프로브는 **분당 4건 이하**로 나누거나, 청정 버킷(현재+직전 분에 실패 0건)을 확인하고 시작할 것. 검증 중 오판 1회를 이 이유로 폐기했다.
5. **본 회차가 남긴 부수 데이터**: `ls_whk_fail_nmtm` 에 `172.217.221.84`(버킷 02:49~02:55, 총 5행) + `172.18.0.4` 1행. 전부 TTL 10분 + `WebhookGuardPurgeJob`(10분 간격)으로 자동 소멸한다. `ls_whk_sign_use` 는 **0행 유지**. 프로덕션 데이터 오염 없음.
