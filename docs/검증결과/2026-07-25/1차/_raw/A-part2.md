# A-auth-common 하이브리드 전수 검증 — A-3 / A-4 / A-5 (docs/test-cases/A-auth-common.md:77~133)

> 검증일 2026-07-25 · 방식: 근거코드 정적 대조 + 기존 자동테스트 매핑
> **런타임 baseline 미생성** (`docs/검증결과/2026-07-25/1차/_raw/test-baseline.md` 부재) → 실행결과 없이 정적 판정
> 사전 확정: UNCERTAINTIES.md #1/#3/#4 반영 (A-3~A-5 직접 해당 항목 없음. #6 RoleHierarchy 는 A-2 소관)

---

## A-3. 스트림 서명 필터 (StreamSignatureFilter)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-STREAM-001 | 유효 서명 → CHANNEL_INTERNAL+STREAM_SIGNED | PASS | StreamSignatureFilter.java:96-126 일치. authorities 2종(45,55행 상수) + TokenClaims(sub=`stream-signed`, role=null, channel=INTERNAL) 설정 확인 | StreamSignedUrlControllerTest.stream_validSignature_200 / stream_validSignature_range_206 / stream_userBound_validU_200 | SecurityConfig:121-122 `CHANNEL_INTERNAL` 요구를 이 authority 로 통과 |
| TC-STREAM-002 | 서명 없으면 미개입 → 401 | PASS | :96-102 일치. sig/exp 둘 중 하나라도 null/blank 면 컨텍스트 미설정 후 패스스루 → SecurityConfig:121 에서 401 | StreamSignedUrlControllerTest.stream_noSignature_noHeader_401 | fail-closed 방향 정상 |
| TC-STREAM-003 | 만료/변조 거부 + sig 전문 로그 미노출 | PASS | :127-130 일치. `log.debug("[StreamSign] invalid signature rawSn={}", rawSn)` — sig/exp/u 어느 것도 미출력. 만료 판정은 StreamUrlSigner.java:122-126(`exp < now`) | StreamSignedUrlControllerTest.stream_expiredSignature_401 / stream_tamperedSignature_401 / stream_signatureForOtherVideo_401 | CWE-532 방어 확인 |
| TC-STREAM-004 | u(userNo) 변조 시 서명 불일치 거부 | **PARTIAL** | :112-116 일치 — u 변조 시 서명 불일치 **맞음**. 그러나 근거 주석(112-113행)·VideoStreamService.java:134-136 이 주장하는 "타 사용자가 URL 을 그대로 재사용해도 통과 못 함"은 **거짓** (u 가 URL 쿼리에 그대로 실림, VideoStreamService.java:138-140) | StreamSignedUrlControllerTest.stream_tamperedUser_401 / issueStreamUrl_includesUserBinding | → **A-ISSUE-11** |
| TC-STREAM-005 | 선인증 컨텍스트 있으면 서명 검증 스킵 | PASS | :83-88 일치. 반증 시도(익명토큰 오탐): 필터는 SecurityConfig.java:135 `addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class)` 로 **AnonymousAuthenticationFilter 앞**에 위치 → `isAuthenticated()==true` 인 익명토큰이 서명검증을 무력화하는 경로 없음 | StreamSignedUrlControllerTest.stream_authorizationHeader_stillWorks_200 | 순서 회귀 시 스트림 전면 401 이 되므로 회귀 방지 테스트 가치 높음 |
| TC-STREAM-006 | GET 아니거나 스트림 경로 아니면 미동작 | PASS | :72-77 일치 (메서드 검사 → 경로 정규식) | 직접 테스트 없음 (간접: 다른 API 테스트 전반이 미개입 전제로 통과) | 커버리지 갭(LOW) |
| TC-STREAM-007 | rawSn 정규식 1~18자리 숫자만 | PASS | :42 `^/v1/videos/(\d{1,18})/stream$` + :104-110 parseLong 방어. 19자리/비숫자는 shouldNotFilter=true → 인증컨텍스트 미설정 → **401(fail-closed)** | 직접 테스트 없음 | 미매칭이 "패스스루"이나 결과는 거부 방향이라 안전 |

---

## A-4. HMAC 웹훅 필터 (HmacWebhookFilter · HmacSigner)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-HMAC-001 | 유효 HMAC 콜백 통과 | PASS | HmacWebhookFilter.java:229-252 일치. canonical=`{timestamp}.{body}` (230행) → hmacSha256Hex → isEqual → `chain.doFilter(cached, ...)` | HmacWebhookFilterTest.validSignature_chainsThrough | 본문 캐싱 wrapper 로 컨트롤러 재읽기 보장 |
| TC-HMAC-002 | augment 시크릿 미설정 시 401(fail-closed) | PASS | :156-161 일치. `shouldNotFilter`(:138-141)가 빈 시크릿에도 경로를 잡아두므로 우회 불가 | HmacWebhookFilterTest.missingSecret_failsClosed | application.yml 기본값 빈 문자열 확인 |
| TC-HMAC-003 | 시크릿 32B 미만 부팅 차단 | PASS | :127-135 `BeanInitializationException`. 빈 시크릿은 예외에서 제외(=fail-closed 경로로 위임) | HmacWebhookFilterTest.shortSecret_failsBoot | |
| TC-HMAC-004 | 서명/timestamp 헤더 누락 401+실패카운트 | PASS | :184-192 일치 (`recordFailure` 호출 포함) | HmacWebhookFilterTest.augmentMissingHmacHeader_returns401 | |
| TC-HMAC-005 | 미지원 서명 접두사 401 | PASS | :193-198 일치 (`hmac-sha256=` prefix 검사 + recordFailure) | 직접 테스트 없음 | 커버리지 갭(LOW) |
| TC-HMAC-006 | timestamp 비숫자 401 | PASS | :200-208 일치 | 직접 테스트 없음 | 커버리지 갭(LOW) |
| TC-HMAC-007 | timestamp 윈도우(±5분) 밖 replay 401 | PASS | :209-216 일치. `windowSeconds` 는 :124 에서 `max(60, …)` 로 하한 클램프 | HmacWebhookFilterTest.augmentReplayTimestampExceeded_returns401 | **윈도우 *내* replay 는 미차단** → A-ISSUE-12 |
| TC-HMAC-008 | 서명 불일치 상수시간 비교 401 | PASS | :240-248 `MessageDigest.isEqual` 사용 확인. 반증 시도: `providedHex.toLowerCase()`(241행)의 Locale 의존성은 hex 문자(a-f)에 영향 없음 → timing/Locale 우회 없음 | HmacWebhookFilterTest.augmentInvalidSignature_returns401 | |
| TC-HMAC-009 | Content-Length 누락 401 | PASS | :171-177 일치 | HmacWebhookFilterTest.missingContentLength_returns401 | VLM 경로(411)와 상태코드 불일치 — 의도된 정책차(주석 :285) |
| TC-HMAC-010 | 본문 1MB 초과 413 | PASS | :178-182 일치 | HmacWebhookFilterTest.bodyOver1MB_returns413 | |
| TC-HMAC-011 | 위조 Content-Length/대용량 스트림 읽기상한 | PASS | :219-226 + :432-446 `readCapped` — 8KB 청크 누적이 상한 초과 즉시 중단(전량 버퍼링 없음) | HmacWebhookFilterTest.vlmBodySpoofedContentLength_returns413 / vlmBoundedRead_stopsAtCap (동일 코드경로) | augment 경로 위조 CL 직접 테스트는 없음 |
| TC-HMAC-012 | 실패 분당 5회 초과 IP 60초 backoff | PASS | :163-169 + :359-398. `failures.get() >= 5` 이므로 6회째 요청이 429 + `Retry-After: 60`(:329) | HmacWebhookFilterTest.rateLimit_after5Failures | 다중노드/LB 한계 → A-ISSUE-14, A-ISSUE-15 |
| TC-HMAC-013 | 성공 시 실패 카운터 리셋 | PASS | :250-251 `failureTrackers.remove(clientIp)` | 직접 테스트 없음 | |
| TC-HMAC-014 | failureTrackers hard cap(4096) evict | PASS | :383-397. 1차 만료항목 removeIf → 2차 windowStartMs 오름차순 정렬 후 초과분 evict | HmacWebhookFilterTest.failureTrackers_hardCap | 반증: evict 대상이 "가장 오래된"=만료임박 항목이라 차단중인 공격자 tracker 를 밀어내는 회피는 원격주소 위조가 전제 → 실효성 낮음 |
| TC-HMAC-015 | 로그 CR/LF/탭 치환 | PASS | :348-351 `LOG_UNSAFE=[\r\n\t]` → `_`. 사용자 제어 값(path, clientIp) 전부 `safe()` 경유 확인. 서명/timestamp 헤더 원문은 애초에 미로깅 | 직접 테스트 없음 | 기타 제어문자(ANSI ESC 등)는 미치환 — A-ISSUE-20 과 동종 |
| TC-HMAC-016 | VLM 콜백은 HMAC 없이 크기상한만 | PASS | :149-153 + :278-305. `sizeCapOnlyPaths={PATH_VLM}` → HMAC/timestamp/rate-limit 미적용 | HmacWebhookFilterTest.vlmPath_passesThroughWithoutHmac / vlmNormalBody_passesThrough | 벤더규격(무서명) 수용. 무단주입 차단은 VlmResultService request_id 게이트에 전가 — 본 파트 범위 밖(별도 검증 필요) |
| TC-HMAC-017 | VLM 콜백 chunked 411 | PASS | :286-290 `SC_LENGTH_REQUIRED` | HmacWebhookFilterTest.vlmChunkedNoContentLength_rejectedBeforeDeserialize | |
| TC-HMAC-018 | VLM 콜백 4MB 초과 413 | PASS | :291-303 (선언 CL + 실스트림 2단 캡) | HmacWebhookFilterTest.vlmBodyOverCap_returns413 | |
| TC-HMAC-019 | 미등록 경로는 필터 미동작 | PASS | :138-141 | HmacWebhookFilterTest.unrelatedPath_skipsFilter | 경로 **정확일치**라 인코딩 변형 우회 위험 → A-ISSUE-13 |
| TC-HMAC-020 | HmacSigner 와 필터가 동일 서명 규칙 공유 | PASS | :261-263 `hmacSha256Hex` → `HmacSigner.hex` 위임 확인. HmacSigner.java:40-51 | HmacSignerTest.producesLowercaseHmacSha256Hex + DevAugmentCallbackSimulatorTest / AugmentCallbackFlowIntegrationTest | 단일 진실원 구조 확인 |
| TC-HMAC-021 | 시크릿이 로그/예외에 미노출 | PASS | HmacSigner.java:46-50 — 예외 메시지에 algorithm 만. 필터 :235 `log.error(... path, e)` 도 시크릿 미포함 | 직접 테스트 없음 | |

---

## A-5. 권한 자가부여 (RoleClaimService)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-CLAIM-001 | 무권한 INTERNAL 이 관리자 pw 로 WORKER 획득 | PASS | RoleClaimService.java:106-184 일치 (게이트 → rate limit → pw → 사용자조회 → LS 중복검사 → upsert → evict → 토큰발급) | RoleClaimServiceTest.claimWorkerSuccess / claimReviewerSuccess | 기능은 정상. 설계 자체의 상승 위험 → **A-ISSUE-17** |
| TC-CLAIM-002 | PORTAL_USER 역할 요청 400 | PASS | :112-115 `INVALID_INPUT`. 반증(role=null NPE): RoleClaimRequest 의 `@NotNull` + Controller `@Valid` 로 서비스 진입 전 400 | RoleClaimServiceTest.portalUserRoleRejected | |
| TC-CLAIM-003 | 이미 역할 보유자 409 | PASS | :119-121 `actor.role() != null` → CONFLICT | RoleClaimServiceTest.alreadyWorkerReturns409 / alreadyReviewerReturns409 | |
| TC-CLAIM-004 | PORTAL 채널 교차채널 자가부여 차단 | PASS | :119-121 `actor.channel() != Channel.INTERNAL` 동시 검사(deny-by-default). SecurityConfig:71 이 role-claim 을 `authenticated()` 로만 잡아 채널격리(:121-122)를 우회하므로 **서비스단 방어가 유일 방어선인데 존재함** | RoleClaimServiceTest.portalChannelActorReturns409 | |
| TC-CLAIM-005 | 잘못된 pw 401(상수시간) | PASS | :126-135 `BCryptPasswordEncoder.matches`(상수시간) | RoleClaimServiceTest.wrongPasswordReturns401 | |
| TC-CLAIM-006 | admin pw 해시 미설정 시 항상 401 | PASS | :88-91(빈문자 유지) + :128(`isEmpty()` 명시 차단). application.yml:122 기본값 빈 문자열 확인 | RoleClaimServiceTest.emptyAdminHashAlwaysReturns401 | |
| TC-CLAIM-007 | admin pw 가 BCrypt 아니면 부팅 거부 | PASS | :92-98 + :102-104 `IllegalStateException`. 반증: prefix 만 검사하므로 `$2a$` + 쓰레기값은 부팅 통과 → 이후 matches=false 로 **항상 401(fail-closed)** → 안전 방향 | RoleClaimServiceTest.rejectNonBcryptHashAtBoot | |
| TC-CLAIM-008 | 시도 5회/분 초과 429 (pw 검증 전 차단) | PASS | :123-124 가 :128 pw 검증보다 **앞** 확인. :205-219 sliding window | RoleClaimServiceTest.rateLimitAfterFiveAttempts / rateLimitPerCaller | 노드별 in-memory·계정별 한정 → **A-ISSUE-18** |
| TC-CLAIM-009 | LS_USER_ROLE 이미 매핑 있으면 409 (stale JWT) | PASS | :150-153. 반증: UserRoleResolver 가 DB장애/미지원 ROLE_CD 로 null 을 반환해 1차 게이트를 통과해도 이 2차 게이트가 잡음 → 상승 불가 | RoleClaimServiceTest.alreadyHasLsRoleReturns409 | 이중 게이트 정상 |
| TC-CLAIM-010 | 사용자 미존재 404 | PASS | :144-147 | RoleClaimServiceTest.missingUserReturns404 | |
| TC-CLAIM-011 | 부여 성공 후 AFTER_COMMIT 캐시 evict | PASS | :160-170 (동기화 비활성 시 즉시 evict 폴백 포함) | RoleClaimServiceTest.claimSuccessEvictsCache | |
| TC-CLAIM-012 | 발급 토큰 HS256/issuer/channel=INTERNAL/exp 1h | PASS | :186-199. issuer 기본 `klid-auth` ∈ JwtIssuerValidator 기본 allowlist(`klid,klid-portal,klid-auth`) → 자기발급 토큰이 자기 필터를 통과 | RoleClaimServiceTest.issuedTokenHasOneHourTtl | `JWT_ISSUER` 커스텀 시 `authoring.jwt.allowed-issuers` 동반 변경 필요(설정 결합 리스크, LOW) |
| TC-CLAIM-013 | 로그에 pw 평문/userNo CRLF 미노출 | PASS | :131(실패 로그에 role/sub 만) + :221-229 sanitize(32자 절단 + `[\r\n\t]`→`_`). `req.adminPassword()` 는 어디에도 미출력 | 직접 테스트 없음 | 기타 제어문자 미치환 → A-ISSUE-20 |
| TC-CLAIM-014 | sub 비숫자면 400 | PASS | :137-142 `INVALID_INPUT` | RoleClaimServiceTest.nonNumericSubjectReturns400 | pw 검증 **후** 파싱이라 정보노출 순서상 오히려 안전 |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [A-ISSUE-11] TC-STREAM-004 — 서명 URL 의 userNo 바인딩이 "URL 재사용 차단"을 실제로 제공하지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 코드 주석이 명시한 대로 "타 사용자가 서명 URL 을 그대로 재사용해도 통과하지 못함"이 성립해야 한다. 서명 URL 은 비식별 영상 원본 스트림에 대한 **무인증 접근권**이므로, URL 유출(브라우저 히스토리·Referer·프록시 로그·화면 공유) 시 소유자 외 재생이 차단돼야 개인정보 노출을 막을 수 있다.
- **현재 동작(이슈 내용)**: `userNo` 를 서명 입력에 넣으면서 **동시에 URL 쿼리에도 노출**한다. 검증기는 쿼리의 `u` 를 그대로 서명 입력으로 되먹임하므로, URL 전체를 복사하면 누구든 TTL(기본 60초, 최대 600초) 동안 통과한다. 서버측에 "요청자 == u" 를 대조할 주체가 없다(무인증 경로).
  ```java
  // VideoStreamService.java:134-140
  // CWE-284 — userNo 를 서명 입력에 바인딩하고 URL 쿼리 u={userNo} 에도 포함한다.
  // ... (타 사용자가 URL 을 그대로 재사용해도 통과하지 못함)   ← 성립하지 않음
  String u = userNo == null ? "" : userNo;
  String url = "/api/v1/videos/" + rawSn + "/stream?exp=" + params.exp() + "&u=" + u + "&sig=" + params.sig();
  ```
  ```java
  // StreamSignatureFilter.java:115-116
  String userNo = request.getParameter("u");
  if (signer.verify(rawSn, exp, sig, userNo)) {   // u 를 검증 주체가 아니라 입력으로 사용
  ```
  또한 `StreamUrlSigner.java:30-31` javadoc 은 "URL 에는 포함하지 않으며 … 묶는 데 쓰지 않는다"라고 **정반대로** 기술돼 있어 문서/구현 드리프트도 존재한다.
- **재현/확인 경로**: `GET /v1/videos/{rawSn}/stream-url` 로 URL 발급 → 응답 URL 전체(`exp`,`u`,`sig` 포함)를 Authorization 헤더 없는 다른 클라이언트로 요청 → TTL 내 200/206 재생됨. (`u` 만 바꾸면 401 — 그래서 TC 의 문자적 단언은 통과)
- **영향**: 보안 — CWE-294(Authentication Bypass by Capture-replay), CWE-598(민감정보 쿼리스트링 노출). 실효 보호는 "짧은 TTL"뿐이며 `u` 바인딩은 방어 기여가 사실상 0. 비식별 영상이라 잔여위험은 제한적이나 원본 영상 스트림 정책과 충돌.
- **수정 방향(제안)**: ① 최소 조치 — 잘못된 주석 3곳(`StreamSignatureFilter.java:112-113`, `VideoStreamService.java:134-136`, `StreamUrlSigner.java:30-31`) 을 실제 보증 수준으로 정정하고 TC-STREAM-004 기대결과를 "u 변조 거부"로만 한정. ② 실보호가 필요하면 `u` 를 URL 에서 제거하고 요청 컨텍스트(쿠키/세션/헤더) 로 대조하거나, 서명에 클라이언트 바인딩 요소(단기 HttpOnly 쿠키 nonce) 를 추가 + 1회용 소비(nonce 캐시) 도입.

### [A-ISSUE-12] TC-HMAC-007 — HMAC 콜백의 replay 방지가 시간 윈도우뿐 (윈도우 내 무제한 재전송)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 클래스 javadoc(`HmacWebhookFilter.java:53`)이 "replay 방지"를 통제 항목으로 선언한다. 캡처된 유효 콜백이 재전송되면 거부되어 증강 결과가 중복 반영되지 않아야 한다.
- **현재 동작(이슈 내용)**: 검증은 `|now - ts| <= 300s` 와 서명 일치만 본다. nonce/`X-Request-Id` 중복 소비 캐시가 없어, 유효 요청을 그대로 캡처하면 **최대 5분(설정에 따라 그 이상) 동안 무한 재전송이 모두 통과**한다.
  ```java
  // HmacWebhookFilter.java:209-216
  long diffMs = Math.abs(nowMs - tsMs);
  if (diffMs > windowSeconds * 1000L) { ... 401 ... }
  // 이후 서명 일치 시 그대로 chain.doFilter — 사용된 서명의 재사용 기록 없음
  ```
  덧붙여 `Math.abs` 라 **미래 timestamp(+5분)** 도 허용되어 유효창이 실질 10분이다.
- **재현/확인 경로**: 정상 `POST /v1/aug/callback` 요청 1건을 캡처 → 동일 헤더/본문으로 5분 내 N회 재전송 → 전부 필터 통과.
- **영향**: 보안/데이터정합 — CWE-294. 실피해 여부는 `AugmentRequestService` 의 콜백 멱등성에 전적으로 의존한다(본 파트 범위 밖, **E 클러스터에서 반드시 교차확인 필요**). 멱등하지 않으면 증강 결과 중복 적재·상태 중복 전이.
- **수정 방향(제안)**: ① 서명값(또는 `X-Request-Id`) 을 윈도우 길이만큼 TTL 캐시에 기록해 중복 시 401/409. ② `Math.abs` 대신 미래 허용치를 별도 소폭(예: +30s)으로 축소. ③ 최소한 서비스단 멱등 보증을 문서/테스트로 고정하고 필터 javadoc 의 "replay 방지" 표현을 "재사용 창 제한"으로 정정.

### [A-ISSUE-13] TC-HMAC-019 — 경로 정확일치(raw URI) 기반 필터 적용 vs 디코딩 경로 기반 라우팅 → HMAC 우회 가능성
- **심각도**: HIGH (**런타임 확인 필요** — 정적 분석상 성립, 실증 미완)
- **기대 동작(기대효과)**: `/v1/aug/callback` 으로 라우팅되는 **모든** 요청은 예외 없이 HMAC 검증을 거쳐야 한다. 해당 경로는 `SecurityConfig.java:82-84` 에서 `permitAll` 이므로, HMAC 필터가 유일한 인증 수단이다.
- **현재 동작(이슈 내용)**: 필터 적용 여부를 `HttpServletRequest.getRequestURI()`(퍼센트 디코딩 **전** 원문) 의 **문자열 정확일치**로 판정한다.
  ```java
  // HmacWebhookFilter.java:138-141
  protected boolean shouldNotFilter(HttpServletRequest request) {
      String path = stripContext(request);                       // getRequestURI() 원문
      return !pathToSecret.containsKey(path) && !sizeCapOnlyPaths.contains(path);
  }
  ```
  반면 Spring Security 매처와 Spring MVC 핸들러 매핑은 **디코딩된 경로**를 사용한다. 따라서 `/v1/%61ug/callback`(a→%61) 같은 변형은 `shouldNotFilter=true`(필터 스킵) 이면서 `permitAll` 매칭 + 컨트롤러 라우팅이 성립할 수 있다. StrictHttpFirewall 기본값은 `%2f`/`%2e`/`%25`/`;` 만 차단하므로 `%61` 은 통과한다. 동일 논리로 VLM 경로의 본문 크기 캡(4MB) 도 우회 가능하다.
- **재현/확인 경로**: `curl -X POST 'http://<host>/api/v1/%61ug/callback' -H 'Content-Type: application/json' -d '{...}'` (서명 헤더 없음). 401 이 아니라 컨트롤러 응답이 오면 우회 성립. 대소문자·`//` 중복 슬래시·경로 파라미터(`;`) 변형도 함께 확인.
- **영향**: 보안 — CWE-436(Interpretation Conflict) / CWE-288(대체 경로를 통한 인증 우회) / CWE-863. 성립 시 **외부 증강 결과 콜백을 무인증 주입** 가능(임의 rawSn 상태 전이·라벨 매핑 오염). VLM 경로 우회 시 CWE-770 pre-auth DoS.
- **수정 방향(제안)**: `shouldNotFilter` 를 raw URI 문자열 비교 대신 Spring Security 와 **동일한 매처**(`PathPatternRequestMatcher`/`AntPathRequestMatcher`) 또는 `UrlPathHelper.getPathWithinApplication()` 기반 정규화 경로로 교체. 병행으로 StrictHttpFirewall 커스터마이즈(퍼센트 인코딩 알파벳 차단)와 우회 시도 회귀테스트(인코딩·대소문자·중복슬래시·trailing slash) 추가. 동일 결함 패턴이 `StreamSignatureFilter.java:76`(정규식 + raw URI) 에도 있으나 그쪽은 스킵=거부 방향이라 무해.

### [A-ISSUE-14] TC-HMAC-012 — 웹훅 rate limit 이 JVM-local 이라 2노드 Active-Active 에서 임계가 2배·재기동 시 소실
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: "인증 실패 분당 5회 초과 시 60초 backoff"(CWE-307 방어)가 **시스템 전체 기준**으로 보장돼야 한다. 배포 토폴로지는 주 서버 2노드 Active-Active(CLAUDE.md 확정)다.
- **현재 동작(이슈 내용)**: 카운터가 인스턴스 로컬 `ConcurrentHashMap` 이다.
  ```java
  // HmacWebhookFilter.java:112
  private final Map<String, FailureTracker> failureTrackers = new ConcurrentHashMap<>();
  ```
  LB 라운드로빈이면 실제 허용 시도는 **분당 10회**가 되고, 앱 재기동/배포마다 backoff 상태가 초기화된다. `windowStartMs` 가 최초 실패 시각 고정이라 지속 공격 시 60초마다 새 창이 열린다.
- **재현/확인 경로**: 2노드 환경에서 잘못된 서명으로 10회 연속 호출 → 429 가 6회째가 아니라 11회째부터 발생.
- **영향**: 보안(무차별 대입 완화 효과 약화). HMAC-SHA256 시크릿(≥32B) 자체가 대상이라 실질 크래킹 위험은 낮으므로 MEDIUM.
- **수정 방향(제안)**: 공유 저장소(DB/Redis) 기반 카운터로 승격하거나, LB/게이트웨이 계층에서 웹훅 경로 rate limit 을 강제하고 앱 카운터는 보조로 명시. 최소 조치로 javadoc 에 "노드 로컬" 한계를 명기하고 운영 문서에 반영.

### [A-ISSUE-15] TC-HMAC-012 — clientIp 를 remoteAddr 로만 산출 → 리버스 프록시 뒤에서 rate limit 이 전체 콜백 차단(가용성)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: rate limit 은 **공격 출처만** 격리해야 하며, 정상 벤더 콜백의 가용성을 해쳐서는 안 된다.
- **현재 동작(이슈 내용)**: 프록시 헤더를 전혀 보지 않고 `getRemoteAddr()` 만 쓴다.
  ```java
  // HmacWebhookFilter.java:353-357
  private String clientIp(HttpServletRequest request) {
      String addr = request.getRemoteAddr();
      return addr == null ? "unknown" : addr;
  }
  ```
  LB/Nginx 뒤에 배포되면 모든 요청의 remoteAddr 이 **동일한 프록시 IP** 로 수렴한다. 임의의 실패 5회가 누적되면 **정상 벤더 콜백까지 60초 동안 429** 로 막힌다(자기유발 DoS). 반대로 다중 IP 공격자는 각각 5회씩 소진해 사실상 무제한 시도한다.
- **재현/확인 경로**: 프록시 경유 환경에서 잘못된 서명 5회 → 직후 정상 서명 콜백이 429.
- **영향**: 가용성/데이터 유실(증강 결과 콜백 누락) + 보안 통제 실효 저하. CWE-348(Use of Less Trusted Source) 인접.
- **수정 방향(제안)**: `ForwardedHeaderFilter` 또는 신뢰 프록시 목록 기반 `X-Forwarded-For` 최우측 신뢰 홉 파싱을 도입하되, 신뢰 프록시 밖의 헤더는 무시(위조 방지). 프록시 IP 는 rate limit 키에서 제외하거나 화이트리스트 처리. 벤더 IP allowlist 병행 시 본 이슈 대부분 해소.

### [A-ISSUE-16] TC-HMAC-001/008 — 서명 대상이 raw 바이트가 아닌 UTF-8 디코딩 문자열
- **심각도**: LOW
- **기대 동작(기대효과)**: 송신측이 서명한 바이트열과 수신측이 검증하는 바이트열이 항상 동일해야 한다.
- **현재 동작(이슈 내용)**: 본문을 문자열로 디코딩했다가 `HmacSigner` 내부에서 다시 인코딩한다.
  ```java
  // HmacWebhookFilter.java:230
  String canonical = timestamp + "." + new String(body, StandardCharsets.UTF_8);
  // HmacSigner.java:44
  byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
  ```
  본문이 유효 UTF-8 이 아니면 U+FFFD 치환으로 라운드트립이 깨져 정상 서명도 401 이 된다(거부 방향이라 안전하나 원인 진단이 어렵다).
- **재현/확인 경로**: 비-UTF-8 바이트(예: latin-1 인코딩 한글)를 포함한 본문에 올바른 HMAC 을 붙여 전송 → 401.
- **영향**: 기능(외부 벤더 인코딩 상이 시 원인불명 401). 보안 영향 없음(fail-closed).
- **수정 방향(제안)**: HMAC 입력을 `byte[]` 로 다루도록 `HmacSigner.hex(String secret, byte[] message)` 오버로드 추가 후 `timestamp.getBytes(UTF_8) + '.' + body` 로 계산. 또는 "본문은 UTF-8 JSON" 을 규격 문서·연동규격서에 명시.

### [A-ISSUE-17] TC-CLAIM-001 — 공유 정적 패스워드만으로 임의 인증 사용자가 REVIEWER(최고권한) 자가부여
- **심각도**: HIGH (설계 정책 확인 필요 — 코드 결함이 아니라 **의도된 기능**이면 UNCERTAINTIES 로 승격 권고)
- **기대 동작(기대효과)**: 본 시스템에서 `REVIEWER` 는 사용자 관리·시스템 설정·작업 배정·검수 승인을 모두 보유한 **사실상 관리자**다(CLAUDE.md 역할표, ADMIN 통합). 최고권한 부여는 기존 관리자의 승인·감사 추적을 거쳐야 한다.
- **현재 동작(이슈 내용)**: 인증만 된(role 없는) INTERNAL 사용자가 **공유 정적 패스워드 1개**로 스스로 `REVIEWER` 를 부여받는다. 요청 role 은 사용자가 지정한다.
  ```java
  // RoleClaimService.java:112-121 — 거부는 PORTAL_USER / 이미 권한 보유 / 비-INTERNAL 뿐
  if (req.role() == Role.PORTAL_USER) { ... }
  if (actor.channel() != Channel.INTERNAL || actor.role() != null) { ... }
  // :156 — 사용자가 고른 역할을 그대로 부여
  lsUserRoleRepository.upsertRole(userNo, req.role().name());
  ```
  승인 워크플로·기존 REVIEWER 통지·부여 감사 테이블이 없고(로그 `[RoleClaim] granted` 1줄, :176), 패스워드 회전 정책·만료도 없다. `allowedClaimRoles()`(:239-241) 화이트리스트는 정의만 되고 `claim()` 에서 참조되지 않는다(현재는 enum 3값 구조상 결과가 같지만, Role enum 확장 시 즉시 취약해지는 잠재 결함).
- **재현/확인 경로**: 관제 JWT 로 인증된 임의 사용자(LS_USER_ROLE 미등록) → `POST /v1/auth/role-claim {"role":"REVIEWER","adminPassword":"<공유pw>"}` → 200 + REVIEWER 토큰. 공유 패스워드는 조직 내 전파·평문 공유 특성상 유출 표면이 넓다.
- **영향**: 보안 — CWE-269(Improper Privilege Management), CWE-1392(Use of Default/Shared Credentials), CWE-778(불충분한 감사 로깅). 성공 시 사용자 관리·시스템 설정·검수 승인 전권 획득.
- **수정 방향(제안)**: ① `claim()` 이 `allowedClaimRoles()` 를 실제로 참조하도록 연결(enum 확장 회귀 차단). ② `REVIEWER` 자가부여는 제거하고 WORKER 만 허용 + REVIEWER 는 기존 REVIEWER 의 `/manage` 승인 경로로만 부여. ③ 유지해야 한다면 부여 이력 전용 테이블(요청자·역할·시각·IP·요청ID) 적재 + 기존 REVIEWER 통지 + 패스워드 회전·만료 정책을 운영 문서에 고정.

### [A-ISSUE-18] TC-CLAIM-008 — 자가부여 rate limit 이 계정 단위 in-memory (교차계정·다중노드 우회) + attempts 맵 무한 증가
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 관리자 공유 패스워드에 대한 무차별 대입이 시스템 전체 기준으로 억제돼야 한다(javadoc 이 CWE-307 방어를 선언).
- **현재 동작(이슈 내용)**: 키가 호출자 `sub` 이고 저장소가 JVM 로컬이며, 성공 시도까지 카운트되고 **엔트리가 제거되지 않는다**.
  ```java
  // RoleClaimService.java:73
  private final ConcurrentHashMap<String, AttemptCounter> attempts = new ConcurrentHashMap<>();
  // :207-213 — 윈도우 만료 시 값만 교체, key 는 영구 잔존
  AttemptCounter counter = attempts.compute(key, (k, prev) -> { ... });
  int current = counter.count.incrementAndGet();
  ```
  → 실효 시도량 = (보유 계정 수) × 5회/분 × (노드 수). IP 단위 제한이 없어 동일 공격자가 여러 무권한 계정을 쓰면 선형 증폭된다. `attempts` 는 role-claim 을 호출한 모든 sub 를 영구 보관한다(유한하나 회수 없음).
- **재현/확인 경로**: 무권한 계정 A/B/C 토큰으로 번갈아 5회씩 호출 → 각 계정이 독립 429 창을 가짐(테스트 `rateLimitPerCaller` 가 이 동작을 고정). 2노드 환경이면 계정당 10회/분.
- **영향**: 보안 — CWE-307 부분 방어, CWE-770(비회수 맵). BCrypt cost 12 의 계산 비용이 실질 완화 요인이라 MEDIUM.
- **수정 방향(제안)**: 키를 `sub` + clientIp 조합으로 확장하고 **글로벌 실패 카운터**(전체 실패 N회/분 초과 시 엔드포인트 일시 차단) 를 추가. 윈도우 만료 엔트리 회수(사이즈 cap + evict, HmacWebhookFilter:383-397 패턴 재사용). 다중노드 대응은 A-ISSUE-14 와 동일 해법.

### [A-ISSUE-19] A-3/A-4/A-5 공통 — application-local.yml 에 관리자 BCrypt 해시·스트림 서명키·웹훅 시크릿 기본값 하드코딩
- **심각도**: LOW
- **기대 동작(기대효과)**: 자격증명·서명키는 어떤 프로파일에서도 저장소에 커밋되지 않아야 한다(security.md "Password in Configuration File" 금지).
- **현재 동작(이슈 내용)**: `application-local.yml:41` `admin-claim-password-hash: ${ADMIN_CLAIM_PASSWORD_HASH:$2y$12$NxE8...}`, `:47` `sign-secret: ${STREAM_SIGN_SECRET:local-stream-sign-secret-change-me-32bytes-min}`, `:113` `augment: ${WEBHOOK_HMAC_SECRET_AUGMENT:local-augment-callback-hmac-change-me-32bytes-min}` — 모두 환경변수 미설정 시 커밋된 기본값으로 동작.
- **재현/확인 경로**: 저장소 열람만으로 로컬 환경의 관리자 해시·서명키 확보. `application.yml` 의 운영 기본값은 빈 문자열이라 운영 노출은 없음.
- **영향**: 보안 — CWE-798. `LocalProfileGuard`(ENV=prd 시 부팅 차단)로 운영 유입은 막혀 있어 실질 영향은 로컬/개발 한정.
- **수정 방향(제안)**: 기본값을 제거하고 `.env.example` + 로컬 실행 문서로 이관하거나, 최소한 세 값의 성격(공개된 개발용 더미, 재사용 금지)을 주석으로 명시.

### [A-ISSUE-20] TC-CLAIM-013 / TC-HMAC-015 — 로그 sanitize 가 `\r\n\t` 만 치환 (기타 제어문자·ANSI 이스케이프 잔존)
- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자 제어 값이 로그에 들어갈 때 로그 라인 위조·터미널 제어 시퀀스 주입이 불가능해야 한다(CWE-117).
- **현재 동작(이슈 내용)**: 두 sanitize 모두 화이트리스트가 아닌 3문자 블랙리스트다.
  ```java
  // RoleClaimService.java:227-228
  String trimmed = value.length() > 32 ? value.substring(0, 32) : value;
  return trimmed.replaceAll("[\\r\\n\\t]", "_");
  // HmacWebhookFilter.java:101,350
  private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");
  ```
  ``(ESC), ``, `` 등은 통과한다. 참고로 `RequestIdFilter`(A-6, TC-TRACE-003)는 영숫자·하이픈 화이트리스트를 쓰고 있어 프로젝트 내 정책이 일관되지 않다.
- **재현/확인 경로**: sub 또는 요청 경로에 ANSI 이스케이프를 포함시켜 실패 유도 후 로그 파일/터미널 뷰어에서 렌더링 확인.
- **영향**: 보안 — CWE-117 잔여 표면. 두 값 모두 사실상 서명된 JWT sub / URI 라 실현 난이도가 높아 LOW.
- **수정 방향(제안)**: `RequestIdFilter` 와 동일한 화이트리스트(`[^A-Za-z0-9._/-]` → `_`) 방식으로 통일하고, 공통 `LogSanitizer` 유틸로 추출해 3곳이 같은 규칙을 쓰게 한다.

---

## 요약

- **총 42건 / PASS 41 / FAIL 0 / PARTIAL 1 / BLOCKED 0 / N/A 0**
  - A-3: 7건 (PASS 6 / PARTIAL 1)
  - A-4: 21건 (PASS 21)
  - A-5: 14건 (PASS 14)
- **근거 라인 드리프트: 0건** — A-3·A-4·A-5 카탈로그의 `file:line` 42개 인용 전부 현재 코드와 일치
- **신규 이슈 10건**: HIGH 2 (A-ISSUE-13 HMAC 경로 인코딩 우회 *런타임 확인 필요*, A-ISSUE-17 REVIEWER 자가부여 설계) · MEDIUM 5 (11·12·14·15·18) · LOW 3 (16·19·20)
- **런타임 검증 대기**: A-ISSUE-13(퍼센트 인코딩 경로 우회 실증), A-ISSUE-12(콜백 멱등성 — E 클러스터 `AugmentRequestService` 교차확인)
- **자동테스트 커버리지 갭(정보)**: TC-STREAM-006/007, TC-HMAC-005/006/013/015/021, TC-CLAIM-013 — 코드상 정상이나 회귀 방지 테스트 부재
- **UNCERTAINTIES 승격 권고**: A-ISSUE-17(REVIEWER 자가부여가 확정 정책인지) · A-ISSUE-11(서명 URL 재사용 차단 요구수준)
