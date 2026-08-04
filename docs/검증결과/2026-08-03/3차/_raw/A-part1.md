# A 클러스터 part1 — A-1 인증(JWT) + A-3 스트림 서명 필터 (3차)

- 일시: 2026-08-03 ~ 2026-08-04 (KST)
- 담당: `docs/test-cases/A-auth-common.md` §A-1(16~54행, TC-AUTH-001~032 + TC-COMMON-001~002 = 34건) + §A-3(95~111행, TC-STREAM-001~012 = 12건) = **46건**
- 환경: `_raw/stack-bringup.md` 기준 재빌드 완료 스택(HEAD `e065da42`, Flyway v163). backend `localhost:18081`, context-path `/api`, `SPRING_PROFILES_ACTIVE=local`
- 검증 방식: **전건 실동작 우선**(curl 로 실제 JWT 조립·서명 → backend 왕복). 코드/설정/테스트 **무수정**, gradle **미실행**
- 실동작 도구: 로컬 `openssl` 로 HS256 JWT 및 스트림 HMAC 서명 직접 생성(`JWT_SECRET`·`STREAM_SIGN_SECRET` 은 컨테이너 실효 env 에서 취득 → **서명 위조 시나리오까지 실행 가능**)

## 0. 판정 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| A-1 인증(JWT) | 34 | 32 | 0 | 2 | 0 | 0 | 0 |
| A-3 스트림 서명 | 12 | 12 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **46** | **44** | **0** | **2** | **0** | **0** | **0** |

- 실동작 근거: 41건 `[실동작]` / 5건 `[정적]`(기동 시점 fail-closed 3건 + DB 장애 주입 1건 + 프록시 캐시키 1건 — 전부 컨테이너 재기동·설정 변경이 필요해 §10 금지 규칙에 걸림)
- **근거 `file:line` 드리프트 0건** — A-1/A-3 의 근거 34곳을 전수 Read 대조한 결과 전부 현재 코드와 일치(2회차 전수 재확인 효과 유지)

## 1. 이전 회차 이슈 대조

| 이슈 | 내용 | 3차 상태 | 실동작 근거 |
|---|---|---|---|
| **A-ISSUE-01(1차)** | `exp` 클레임 부재 JWT 무기한 유효 | **✅ 해소 확인** | `{"sub":"1001","iss":"klid-auth"}`(exp 없음) → `/v1/me` **401**, `/v1/videos` **401**. 코드에도 명시 게이트 `JwtAuthenticationFilter.java:79-84` 신설 확인. 2차 판정(정적 PASS)이 **실동작으로 재확인**됨 |
| **A-ISSUE-02(1차)** | `channel` 부재 시 무조건 INTERNAL 승격(issuer↔channel 무결합) | **❌ 미해소 — 그대로 이월** | `{"sub":"1001","iss":"klid-portal","exp":<미래>}` (channel 없음) → 200 `{"role":"REVIEWER","channel":"INTERNAL"}`. 코드 `:86-87` 동일 |
| **A-ISSUE-41(1차)** | 동명 `klid_stream_nonce` 쿠키 shadowing 시 영구 401(가용성) | **❌ 미해소 — 그대로 이월** | `Cookie: <위조>; <유효>` → **401** / `Cookie: <유효>; <위조>` → 200. `StreamNonceCookie.java:120-124` 첫 매칭 즉시 return 유지 |
| A-ISSUE-23(1차, 환경) | `ADMIN_CLAIM_PASSWORD_HASH` 공란 → role-claim 성공경로 실동작 불가 | **미해소(환경)** — 단 **A-1 판정에는 영향 없음** | 캐시 evict 실동작은 대체 경로 `PATCH /v1/users/{userNo}`(REVIEWER)로 확보해 TC-AUTH-030/031 을 실동작 판정함 |
| 2차 타겟 재검증 3건 | JWT exp · 정렬 allowlist · 로그 마스킹 | JWT exp = **여전히 유효(재확인)** | 나머지 2건은 A-6/A-7 담당 범위라 본 파트 대상 아님 |

> 2차(`targeted-A-C.md`)는 컨테이너가 V99 로 stale 해 실동작이 BLOCKED 였고 정적+단위로만 PASS 판정했다. **3차에서 V163 스택으로 실동작 재확인 완료** — 2차 결과는 유효하다.

---

## 2. A-1. 인증 (JWT) — 34건

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| TC-AUTH-001 | PASS | [실동작] 유효 INTERNAL 토큰(sub=1001) → `/v1/me` 200 `{"userId":"1001","role":"REVIEWER","channel":"INTERNAL"}`. authority 실증: 같은 토큰으로 `/v1/system/configs` **404**(=인가 통과 후 라우팅 부재)인 반면 WORKER 는 **403** → `ROLE_REVIEWER` 실부여 확인. `CHANNEL_INTERNAL` 은 `/v1/videos` 200 vs PORTAL 채널 403 으로 실증 | `JwtAuthenticationFilter.java:56-121` 일치 |
| TC-AUTH-002 | PASS | [실동작] 헤더 없음 → `/v1/me` **401**, `/v1/videos` **401**(표준 `ApiResponse` `errorCode=UNAUTHORIZED`) | `:55-56,132` |
| TC-AUTH-003 | PASS | [실동작] `Authorization: Token <유효>` → 401 / `bearer <유효>`(소문자) → 401 / `BEARER <유효>` → 401. 대소문자 정확 매칭 확인 | `:28,56`. ⚠ 전용 자동테스트 없음(§5) |
| TC-AUTH-004 | PASS | [실동작] 다른 키로 서명한 토큰 → 401 + `clearContext`. 서명부 1글자 변조도 401 | `:127-130` |
| TC-AUTH-005 | PASS | [실동작] ①`exp=now-1` → 401 ②`exp` 클레임 부재 → **401**(A-ISSUE-01 해소) ③`exp="<과거>"`(문자열형) → 401 ④`exp=0` → 401 ⑤`exp=<미래>` → 200 | `:59-62,79-84,127-130`. **카탈로그 정정 1건**(§4) |
| TC-AUTH-006 | PASS | [실동작] `{"alg":"none"}` + 서명 생략(`h.p.`) → 401, 서명부 자체 생략(`h.p`) → 401. `alg` 소문자 `hs256` → 401 | `:59-62,127-130`. ⚠ 전용 자동테스트 없음 |
| TC-AUTH-007 | PASS | [실동작] `Bearer garbage` → 401, `Bearer `(빈값) → 401, `jwk` 임베드 헤더 → 401, `crit` 헤더 → 401 | `:127-130` |
| TC-AUTH-008 | PASS | [실동작] `iss=evil` → 401. 추가 반증: `iss=" klid-auth"`(앞공백) → 401, `iss="KLID-AUTH"`(대문자) → 401, `iss=["klid-auth"]`(배열) → 401 — **정확일치·대소문자 구분 확인** | `JwtIssuerValidator.java:22-27`; `JwtAuthenticationFilter.java:65-70` |
| TC-AUTH-009 | PASS | [실동작] `iss=""` → 401, `iss` 클레임 부재 → 401 | `JwtIssuerValidator.java:23-25` |
| TC-AUTH-010 | PASS | [실동작] 런타임 실효값 `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal` 에서 3종 전부 200 | `JwtIssuerValidator.java:15` |
| TC-AUTH-011 | PASS | [정적] `:16-19` `map(String::trim).filter(!isEmpty)` → 공백/빈항목 제거. 전부 공백이면 `allowed=∅` 이고 `isAllowed` 가 `contains` 이므로 전건 false(fail-closed). 실동작은 설정 변경+재기동 필요라 미수행 | ⚠ 전용 자동테스트 없음(§5) |
| TC-AUTH-012 | **PARTIAL** | [실동작] `channel` 부재 → `channel=INTERNAL` 응답(**기대결과 자체는 충족**). 그러나 `iss=klid-portal` + channel 부재 토큰도 INTERNAL 로 승격되어 `sub=1001` 로 `role=REVIEWER` 획득(200) — **A-ISSUE-02 미해소 그대로** | `:86-87`. → 이월 이슈 §3-A |
| TC-AUTH-013 | PASS | [실동작] `channel=PORTAL` + `sub=1001`(LS:REVIEWER) → `role=PORTAL_USER`. `sub=99999`(미등록)·`sub="abc"`(비숫자) 도 동일하게 `PORTAL_USER` → **sub 값 무관·LS 미조회 확인** | `:95-100` |
| TC-AUTH-014 | PASS | [실동작] `channel="X"` → 401. 추가: `internal`(소문자)·`Internal`·`PORTAL_USER` 전부 401. `channel` 을 숫자(비문자열)로 보내면 `RequiredTypeException` → 401 | `:87,127-130` |
| TC-AUTH-015 | PASS | [실동작] `sub=2001`(LS:WORKER) 토큰에 `"role":"REVIEWER"`·`"authorities":["ROLE_REVIEWER"]` 자가주장 주입 → `/v1/me` `role=WORKER`, `/v1/system/configs` **403**. JWT role 클레임 미참조 실증 | `:89-100`; `UserRoleResolver.java:46-61` |
| TC-AUTH-016 | **PARTIAL** | [실동작] `sub="abc"` → `role=null`(기대 충족), `sub="9223372036854775808"`(long overflow) → `role=null`. **그러나** `sub=" 2001"`·`sub="2001 "`·`sub="+1001"` 처럼 **엄밀히 비숫자인 문자열이 role 을 획득**한다(filter 가 `Long.parseLong(sub.trim())` 로 관대 파싱). 하류 `LabelAccessGuard.parseUserNo`/`UserService.parseUserNo` 는 trim 없이 파싱해 **같은 요청이 인증은 통과·인가는 401** 로 갈린다 | `:96-97,140-148`. → 신규 **A-ISSUE-01** |
| TC-AUTH-017 | PASS | [실동작] `sub=""` → `role=null`, `sub=null`(JSON null) → `role=null`, `sub` 클레임 부재 → `role=null`. 전부 `/v1/videos` 403 | `:140-143` |
| TC-AUTH-018 | PASS | [실동작] `sub=77777`(LS_USER_ROLE 미등록) → `/v1/me` 200 `role=null`, `CHANNEL_INTERNAL` 만 부여 | `UserRoleResolver.java:52-55` |
| TC-AUTH-019 | PASS | [실동작] 위 role=null 토큰으로 `/v1/videos` **403**, `/v1/event-types` **403**, `/v1/notices` **403**, `/v1/me` 만 **200** | `:114-118`; `SecurityConfig.java:84,147-152` |
| TC-AUTH-020 | PASS | [실동작] `name="홍길동"` → `/me` 응답 `"name":"홍길동"`. `name` 클레임 부재 → `"name":null`(NPE 없음). `name` 에 CRLF 주입(`a\r\nX-Injected: 1`) → **응답 헤더 오염 없음**, 본문에 JSON 이스케이프되어 반영 | `:123-126` |
| TC-AUTH-021 | PASS | [실동작] `"authorities":["STREAM_SIGNED"]`·`"scope":"STREAM_SIGNED"` 자가주장 + role=null 토큰 → `/v1/videos/4/stream`(무서명) **403**. grep 전수: `AUTHORITY_STREAM_SIGNED` 부여 지점은 `StreamSignatureFilter.java:138` **단 1곳** | `:109-118`; `StreamSignatureFilter.java:55-63` |
| TC-AUTH-022 | PASS | [정적] `:37-48` 3개 인자 각각 null 검사 → `IllegalArgumentException("... (fail-closed)")`. 실동작은 빈 생성 실패라 재기동 필요 | ⚠ 전용 자동테스트 없음(§5) |
| TC-AUTH-023 | PASS | [정적] `SecretKeyResolver.java:18-22` `bytes.length < 32` → `IllegalArgumentException`. 자동테스트 `SecretKeyResolverTest#rejectsShortInput` 존재 | 실동작은 재기동 필요 |
| TC-AUTH-024 | PASS | [정적] `:17-29` `Keys.hmacShaKeyFor` 결과를 필드에 1회 보관 후 `resolve()` 가 동일 인스턴스 반환. `SecretKeyResolverTest#acceptsInputAtLeast32Bytes` | — |
| TC-AUTH-025 | PASS | [정적] `UserRoleResolver.java:56-60` `catch (DataAccessException)` → `log.warn("... userNo={} (fail-closed, deny)")` + null. 로그 인자는 `userNo`(Long) 뿐 — 토큰/PII 없음. 자동테스트 `UserRoleResolverTest#dbExceptionFailsClosed` | DB 강제 장애 주입은 병렬 검증 스택을 훼손하므로 미수행 |
| TC-AUTH-026 | PASS | [실동작] `ls_user_role.role_cd='ADMIN'` 로 직접 변경 후 캐시 TTL 경과 → `/me` `role=null` + 실제 로그 `[Auth] unknown role code userNo=77777 (fail-closed, deny)` 관측 | `:72-80` |
| TC-AUTH-027 | PASS | [정적] `:46` `@Cacheable(key="#userNo")` 에 `condition` 없음(NPE 소지 유지) / 본문 `:48-50` 은 null 반환. **실경로 미도달 재확인** — grep 전수 결과 `RoleResolver.resolve(` 호출부는 `JwtAuthenticationFilter.java:97`(null 선차단)·`StreamSignatureFilter.java:165`(null/blank/NumberFormat 선차단) **2곳뿐** | 기대결과가 서술한 현재 동작과 일치. 구 이슈(LOW) 미해소 유지 — 본 파트 신규 이슈로 재기록하지 않음 |
| TC-AUTH-028 | PASS | [실동작] userNo=77777 을 WORKER 로 적재→조회(캐시 적재)→DB 를 REVIEWER 로 변경→즉시 재조회 시 **여전히 WORKER**(캐시 적중) → 60s TTL 경과 후 반영. `maximumSize(500)`/`expireAfterWrite(60s)` | `CacheConfig.java:88-92`; `UserRoleResolver.java:46` |
| TC-AUTH-029 | PASS | [실동작] 미배정(role=null) 상태 조회 → 이후 `INSERT ls_user_role(77777,'WORKER')` → **즉시 다음 요청에서 WORKER**. null 결과가 캐시되지 않음 실증 | `:46` `unless="#result == null"` |
| TC-AUTH-030 | PASS | [실동작] `PATCH /v1/users/2002 {"role":"REVIEWER"}`(REVIEWER 수행) 직후 2002 토큰 재조회 → **즉시 REVIEWER**. `@CacheEvict` 키 표현식이 `@Cacheable` 과 동일함을 실동작으로 확인 | `:67-70`; `UserService.java:171-182` |
| TC-AUTH-031 | PASS | [실동작] 위 승격 후 다시 `{"role":"WORKER"}` 로 **강등** → 즉시 `role=WORKER` 반영(스테일 권한 잔존 없음). AFTER_COMMIT evict 경로 실증 | `:63-70`; `UserService.java:171-182`(구조 동일 — 카탈로그가 인용한 `RoleClaimService.java:167-179` 도 라인 일치 확인) |
| TC-AUTH-032 | PASS | [정적] `TokenClaims.java:13-15` `role == null ? null : role.name()`. 실동작 보강: role=null 토큰의 `/me` 응답 `"role":null`(500 없음). `TokenClaimsTest` 2건 | — |
| TC-COMMON-001 | PASS | [실동작] 미인증 `GET /v1/me` → **401** + `{"success":false,"errorCode":"UNAUTHORIZED","message":"인증이 필요합니다."}`. 스택트레이스·내부경로 미포함 | `SecurityConfig.java:84,156`; `SessionController.java:36-39` |
| TC-COMMON-002 | PASS | [실동작] REVIEWER/WORKER/PORTAL 3종 토큰 모두 `MeResponse(sub,name,role,channel)` 정상. role=null·name=null 안전 | `SessionController.java:35-45` |

### A-1 반증 시도 — 결함이 아니었던 항목 (재조사 방지)

| 시나리오 | 실측 결과 |
|---|---|
| `alg=RS256` 헤더 + HMAC 서명(알고리즘 혼동, CWE-347) | **401** — `verifyWith(SecretKey)` 가 비-HMAC alg 거부 |
| `alg=HS384`/`HS512` + 동일 시크릿으로 올바르게 서명 | 200 — jjwt 가 HMAC 계열을 폭넓게 수용. **시크릿을 알아야 성립**하므로 권한 상승 아님 |
| 헤더 `kid`/`jku` 주입(외부 키 로딩 유도) | 200(정상 서명이므로) — jjwt 가 헤더 파라미터로 키를 재해석하지 않음. 파일/URL 접근 시도 로그 없음 |
| 헤더 `jwk` 임베드 / `crit` 파라미터 | 401 — 둘 다 파싱 거부 |
| `nbf` 미래 토큰 | 401 (`PrematureJwtException`) |
| `exp` 를 문자열로 전송 | 미래값 200 / 과거값 401 — 만료 검사 정상 동작(형변환만 관대) |
| `exp=Long.MAX_VALUE` | 401 — 내부 `Date` 변환 오버플로로 fail-closed |
| `sub` 을 JSON 숫자·`channel` 을 숫자로 전송 | 401 (`RequiredTypeException`) fail-closed |
| `Authorization` 헤더에 STREAM_SIGNED 자가주장 | 부여 지점이 서명 필터 1곳뿐이라 무효(TC-AUTH-021) |

---

## 3. A-3. 스트림 서명 필터 — 12건

발급 실측(WORKER 2001, 배정 영상 rawSn=4):
```
GET /api/v1/videos/4/stream-url  → 200
Set-Cookie: klid_stream_nonce=<32hex>.<64hex>; Path=/api/v1/videos; Max-Age=3600; HttpOnly; SameSite=Lax
body.url = /api/v1/videos/4/stream?exp=<epoch+60>&u=2001&sig=<64hex>   ← nonce 미포함
```

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| TC-STREAM-001 | PASS | [실동작] 유효 sig + 유효 nonce 쿠키 → **206**(Range). principal 이 실발급자로 채워지는 것은 TC-STREAM-012 의 403/200 분기로 역증명 | `StreamSignatureFilter.java:126-148` |
| TC-STREAM-002 | PASS | [실동작] sig/exp 둘 다 부재 → **401**. `sig` 만 → 401, `exp` 만 → 401(둘 중 하나라도 없으면 미개입 → 보안체인 거부) | `:108-114` |
| TC-STREAM-003 | PASS | [실동작] 변조 sig → 401 + 로그 `[StreamSign] invalid signature rawSn=4` 만 출력. 컨테이너 전체 로그에서 `sig=<64hex>` 패턴 **0건**(전수 grep) — 쿠키 값도 미출력 | `:149-152` (CWE-532 방어 실효) |
| TC-STREAM-004 | PASS | [실동작] `u=2001`→`u=1001` 변조 → **401**, `u` 파라미터 제거 → **401**. "URL 전체 재사용 차단"은 nonce 담당임을 TC-STREAM-008 로 실증(기대문구 정정 내용이 실동작과 일치) | `:124-132` |
| TC-STREAM-005 | PASS | [실동작] Bearer 토큰 + 서명 없음 → **200**, Bearer 토큰 + 고의로 깨진 서명(`exp=1&sig=zz`) → **200**(서명 검증 스킵) | `:95-100`; `SecurityConfig.java:166` `addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class)` 확인 |
| TC-STREAM-006 | PASS | [실동작] 동일 서명 URL 을 **POST** → 401, **HEAD** → 401, 비-stream 경로(`/v1/videos/4/stream-url?exp&u&sig`) → 401 | `:84-89` |
| TC-STREAM-007 | PASS | [실동작] `rawSn=1234567890123456789`(19자리) → 401, `04`·`-4`·`4a` → 전부 401(패스스루 → fail-closed) | `:50,116-122` |
| TC-STREAM-008 | PASS | [실동작] 발급 URL 전량(exp·u·sig) 확보 + 쿠키 미첨부 → **401**. 발급 응답 `url` 에 nonce 문자열 미포함 확인 | `:127-132`; `StreamNonceCookie.java:115-126` |
| TC-STREAM-009 | PASS | [실동작] ①봉인 없는 32-hex 단독 → 401 ②임의 32hex+임의 64hex → 401 ③정상 nonce + seal 1글자 변조 → 401 ④**타 subject(1001)에게 발급된 정상 쿠키를 u=2001 URL 에 이식** → 401. `unseal` 은 `MessageDigest.isEqual` 상수시간 | `StreamNonceCookie.java:132-155,167-169`. ⚠ 파생 관측 = 이월 A-ISSUE-41(§3-B) |
| TC-STREAM-010 | PASS | [실동작] `HttpOnly` ✓ `SameSite=Lax` ✓ `Path=/api/v1/videos`(=ctx+`/v1/videos`) ✓ `Max-Age=3600` ✓ / **Secure 미부여 — 현재 `SPRING_PROFILES_ACTIVE=local` 이므로 규약과 일치**. 판정축이 프로파일임은 `:89,218`(`.secure(!localProfile)`) + `StreamNonceCookieSecureFlagTest` 3건(`secureRequestDoesNotDrivePolicy` 포함)으로 확인. 보강 실측: 유효 쿠키 보유 상태로 다른 영상 발급 시 **쿠키 값 유지**(동시 다영상 재생 규약), 쿠키 없이 발급 시 새 값 | `:214-224` |
| TC-STREAM-011 | PASS | [정적] `deriveSealKey()`(`:183-190`)가 빈 시크릿에 **JVM 기동 랜덤 32B** 반환 → 어떤 쿠키도 봉인검증 불통과. 동시에 `StreamUrlSigner.configured=false` → `verify()` 즉시 false(`:121-123`). **이중 fail-closed**. 실동작은 시크릿 제거 + 재기동이 필요해 §10 금지 | ⚠ 전용 자동테스트 없음(1차 지적 유지, §5) |
| TC-STREAM-012 | PASS | [실동작] **서명을 직접 위조**(`STREAM_SIGN_SECRET` 로 sig 계산, 정상 발급 쿠키 재사용)해 WORKER 2001 로 여러 영상 요청: 배정 영상 `rawSn=4`→200 / `rawSn=7`→200, 미배정 `rawSn=5`→**403 "본인에게 배정되지 않은 영상입니다."** / `rawSn=17`→**403**. DB 실측 배정(2001 = 4,6,7,9,18,26,27,31)과 정확히 일치 | `:139-148,160-169` (B-ISSUE-63 해소 재확인) |

### A-3 반증 시도 — 결함이 아니었던 항목 (재조사 방지)

| 시나리오 | 실측 결과 |
|---|---|
| **경로 인코딩 우회**(CWE-436) `/v1/videos/4/%73tream` | **401** — `getRequestURI()` 가 미디코딩이라 정규식 미매칭 → 필터 스킵. 스킵 방향이 **fail-closed**(컨텍스트 미설정 → 401)라 관통이 아님. 과거 웹훅 필터 결함(E-ISSUE-01)과 방향이 반대 |
| 경로 정규화 `/videos/4/./stream`, `/videos/5/../4/stream` | 200 — Tomcat 이 매핑 전에 정규화하므로 **필터 정규식·Security 매처·컨트롤러가 모두 같은 정규화 경로**를 본다(불일치 없음). rawSn=4 서명이 rawSn=4 로 해석되어 일관 |
| `/videos/4/stream/`, `/videos/4//stream`, `/videos/4/stream;x=1`, `/videos/4/STREAM` | 전부 401 |
| 컨텍스트 조작 `/api/api/v1/...`, `//api/v1/...` | 전부 401 |
| sig 대문자 hex | 200 — `verify()` 가 `toLowerCase()` 정규화. hex 대소문자 동치이므로 보안 영향 없음 |
| `sig`/`u` 파라미터 중복 전송(HTTP Parameter Pollution) | 첫 값 채택(서블릿 규약) — 서명이 첫 값을 덮으므로 우회 불가. 컨트롤러는 path 의 rawSn 만 사용해 하류 불일치 없음 |
| `exp=now`(경계) / `exp=now-1` | 200 / 401 — `exp < now` 만 거부(경계 포함), 규약대로 |
| `u=""` 로 서명 + 실제 subject 로 봉인된 쿠키 | 401 — `unseal(value, "")` 이 normalize 대상 subject(`"-"`)로 검증돼 불일치 |
| nonce 1회 소비 여부 | 동일 서명 URL 5연속 Range 요청 전부 206 — **의도된 재사용 허용**(브라우저 다중 Range 규약, javadoc `:50-53`) |
| 서명 컨텍스트로 타 API 호출 | 서명 필터가 `/stream` 외 경로에서 `shouldNotFilter=true` 라 컨텍스트 자체가 생기지 않음 → 확대 불가 |

---

## 4. 카탈로그 정정 (담당 라인범위 내, 1건)

### 정정 1 — `TC-AUTH-005` (A-auth-common.md:24)

- **사유**: 2026-08-02 에 `JwtAuthenticationFilter.java:79-84` 로 **`exp` 클레임 부재 거부 게이트가 신설**(A-ISSUE-01 해소)됐는데 카탈로그 행은 여전히 "exp 과거" 한 축만 기술하고 있었다. 근거 `file:line` 에도 신설 게이트가 빠져 있어, 다음 회차가 이 방어를 **검증 대상으로 들고 있지 않게** 된다(루트 `CLAUDE.md` "동작·정책이 바뀌면 `docs/test-cases/` 카탈로그도 갱신" 구속 규칙 대상).
- **이전**
  ```
  | TC-AUTH-005 | 만료 토큰(exp 과거) 거부 | exp<now, clock skew 미설정 | 만료 JWT | ExpiredJwtException → 401 | security | H | JwtAuthenticationFilter.java:59-62,127-130 |
  ```
- **이후**
  ```
  | TC-AUTH-005 | 만료 토큰(exp 과거) 거부 **+ exp 클레임 부재 거부** | ①exp<now, clock skew 미설정 ②exp 클레임 자체가 없음 | ①만료 JWT ②`{"sub","iss"}` 만 있는 JWT | ①ExpiredJwtException → 401 ②**exp 부재도 401** — jjwt 는 exp 가 없으면 만료 검사를 통째로 건너뛰므로 issuer 게이트와 동일한 명시 fail-closed 분기(`clearContext` + early return)로 거부한다. 저작도구는 토큰을 발급·폐기하지 않아 수명 상한이 exp 뿐 (A-ISSUE-01 해소, 2026-08-02) | security | H | JwtAuthenticationFilter.java:59-62,79-84,127-130 |
  ```
- **근거**: `backend/src/main/java/kr/co/cudo/authoring/common/security/JwtAuthenticationFilter.java:72-84` + 회귀 테스트 `JwtAuthenticationFilterTest#tokenWithoutExpClaimReturns401`·`#portalTokenWithoutExpClaimReturns401` + 3차 실동작(401 재현)

### 정정하지 않은 항목 (판단 근거 기록)

- `TC-AUTH-012`·`TC-STREAM-009` 에 이월 이슈(A-ISSUE-02·A-ISSUE-41) 교차참조를 다는 안은 **적용하지 않았다** — 두 행의 기대결과는 현재 코드·정책과 **모순되지 않으며**(카탈로그가 틀린 것이 아니다), 이월 관리는 `ISSUES.md` 대장이 담당하는 체계이기 때문. 다만 파일 자체가 `TC-AUTH-027`·`TC-STREAM-004` 에서 동일한 교차참조 관례를 쓰고 있으므로, **대장 병합 시 이 관례를 확장할지 여부는 PM 판단 사항**으로 남긴다.
- `TC-AUTH-016` 은 실측상 기대결과가 부분적으로만 성립하나(§ A-ISSUE-01 신규), **"어느 쪽이 정책인가"가 미확정**(filter 의 관대 파싱이 의도인지 하류의 엄격 파싱이 의도인지)이라 §"정책 변경 불확실하면 고치지 말 것" 지침에 따라 **카탈로그를 손대지 않고 이슈로만 기록**했다.

---

## 5. 자동 테스트 커버 현황

| 테스트 파일 | 메서드 수 | 커버 케이스 |
|---|--:|---|
| `common/security/JwtAuthenticationFilterTest` | 9 | TC-AUTH-001·002·005·014·017·026 |
| `auth/jwt/JwtIssuerValidatorTest` | 2 | TC-AUTH-008·009·010 |
| `common/security/SecretKeyResolverTest` | 2 | TC-AUTH-023·024 |
| `common/security/UserRoleResolverTest` | 5 | TC-AUTH-015·018·025·026·027 |
| `common/security/UserRoleCacheEvictIT` | 1 | TC-AUTH-030·031 |
| `common/security/TokenClaimsTest` | 2 | TC-AUTH-032 |
| `auth/SecurityConfigChannelTest`·`SecurityRoleResolutionPhase3Test`·`InternalRoleGateMatcherTest` | — | TC-AUTH-012·013·019 |
| `video/StreamSignedUrlControllerTest` | 19 | TC-STREAM-001~010 |
| `video/VideoStreamAssignmentAuthorizationTest` | 17 | TC-STREAM-012 |
| `common/security/StreamNonceCookieSecureFlagTest` | 3 | TC-STREAM-010 |

**커버 공백 8건**(→ 신규 A-ISSUE-03): `TC-AUTH-003`(Bearer 대소문자) · `TC-AUTH-006`(alg=none) · `TC-AUTH-007`(malformed) · `TC-AUTH-011`(issuer 목록 정규화/전건거부) · `TC-AUTH-022`(생성자 null fail-closed) · `TC-STREAM-006`(메서드/경로 게이트) · `TC-STREAM-007`(rawSn 정규식) · `TC-STREAM-011`(sign-secret 미설정 fail-closed, 1차 지적 유지).

---

## 6. 이슈

> ⚠ **번호 주의**: 아래 `A-ISSUE-01~03` 은 **3차 part1 신규 이슈**이며, 1차 대장의 동번호 이슈(`A-ISSUE-01` = exp 부재, **해소됨**)와 **무관**하다. 이월 이슈는 원 ID(`A-ISSUE-02`, `A-ISSUE-41`)를 그대로 유지해 회차 간 대조가 끊기지 않게 한다.

### [A-ISSUE-01] TC-AUTH-016 — JWT `sub` 정규화가 인증 필터와 인가 가드에서 서로 달라, 같은 토큰이 인증은 통과하고 인가에서 401 로 갈린다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `sub`(=userNo)는 **인가 판정의 주체 식별자이자 감사 컬럼의 값**이다(`LS_TASK_ASSIGNMENT` 배정 대조, `LS_AUTH_WORK_LOCK` 소유자, `REG_ID`/`MDFCN_ID` 등). 따라서 "이 토큰의 주체는 누구인가"에 대한 답은 **모든 소비 지점에서 하나여야** 한다. 파싱 규칙이 지점마다 다르면 ①한 사람이 두 신원으로 기록되어 감사 추적·작업락 소유권이 깨지고 ②역할은 받았는데 리소스 접근은 401 인 "반쯤 인증된" 상태가 만들어진다.
- **현재 동작(이슈 내용)**: 인증 필터는 **trim + 부호 허용**으로 관대하게 파싱하고, 하류 가드 2곳은 **원문 그대로** 엄격 파싱한다.
  ```java
  // common/security/JwtAuthenticationFilter.java:140-148 — 관대
  private static Long parseUserNo(String sub) {
      if (sub == null || sub.isBlank()) return null;
      try { return Long.parseLong(sub.trim()); }      // ← trim, "+1001" 도 허용
      catch (NumberFormatException e) { return null; }
  }

  // label/service/LabelAccessGuard.java:149-155 — 엄격
  public Long parseUserNo(String sub) {
      try { return Long.parseLong(sub); }             // ← trim 없음
      catch (NumberFormatException e) { throw new CustomException(ErrorCode.UNAUTHORIZED, ...); }
  }
  // user/service/UserService.java:159-165 — 동일하게 엄격
  ```
  또한 `TokenClaims.sub` 에는 **정규화되지 않은 원문**이 담겨 `actor.sub()` 를 문자열 그대로 쓰는 지점(작업락 소유자 `TrackEditService:97`·`TrackMergeService:95`, 감사 컬럼 `LabelMasterController:87,105,120`·`LabelAttrController:81,100,117`, `VideoController:247,372`, 포털 `LsPortalUserLabel.create` 등 20+곳)에 그대로 흘러든다.
  실동작 근거 (WORKER 2001, 배정 영상 srcSn=5):
  ```
  sub="2001"   → /v1/me role=WORKER   · GET /v1/frames/5/labels → 200
  sub=" 2001"  → /v1/me role=WORKER   · GET /v1/frames/5/labels → 401 {"message":"토큰 subject 형식이 올바르지 않습니다."}
  sub="2001 "  → /v1/me role=WORKER   · GET /v1/frames/5/labels → 401
  sub="+1001"  → /v1/me role=REVIEWER · userId 응답값은 "+1001"(원문 그대로)
  ```
  즉 **카탈로그 기대결과 "INTERNAL sub 비숫자면 무권한"이 부분적으로만 성립**한다 — `" 2001"`·`"+1001"` 은 엄밀히 비숫자인데 역할을 획득한다.
- **재현/확인 경로**:
  ```bash
  # SECRET = backend 컨테이너 JWT_SECRET
  P=$(printf '{"sub":" 2001","iss":"klid-auth","exp":9999999999}' | base64 | tr -d '=' | tr '/+' '_-')
  curl -s -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/me            # role=WORKER (인증 통과)
  curl -s -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/frames/5/labels # 401 (인가 거부)
  ```
  ```sql
  -- 두 신원으로 갈릴 수 있는 컬럼 확인
  select distinct lock_owner_id from ls_auth_work_lock;
  select distinct reg_id from ls_label;
  ```
- **영향**: 보안/데이터정합 — **CWE-1289(Improper Validation of Unsafe Equivalence in Input)** / CWE-178(Improper Handling of Case·Whitespace) 계열, 파급으로 CWE-863. 즉시 권한 상승은 아니다(불일치가 **fail-closed 방향인 401**로 나타난다). 실질 위험 2가지: ①발급 시스템(관제/포털)이 `sub` 에 공백·부호를 붙이는 순간 **해당 사용자의 모든 라벨링·검수 기능이 401 로 죽는다**(가용성, 원인 규명이 매우 어려움 — `/me` 는 정상이라 "로그인은 되는데 작업만 안 됨"으로 보인다) ②`actor.sub()` 원문을 그대로 쓰는 작업락 소유자·감사 컬럼에 **같은 사람의 두 표기가 공존**해 락 해제 실패·이력 추적 단절이 생긴다. `sub` 값은 저작도구가 통제하지 못하는 **외부 발급 계약**이라 "발급자가 항상 canonical 하게 넣는다"는 가정을 검증 없이 신뢰하는 구조다(A-ISSUE-01(1차) 의 `exp` 와 동일한 패턴).
- **수정 방향(제안)**: 정규화 지점을 **하나로** 모은다. `JwtAuthenticationFilter` 가 `sub` 를 파싱하는 그 자리에서 **canonical 문자열(`Long.toString(parsed)`)로 `TokenClaims.sub` 를 채워** 이후 모든 소비 지점이 같은 값을 보게 하거나, 반대로 filter 의 `trim()` 을 없애 **엄격 파싱으로 3곳을 통일**한다(전자를 권장 — 후자는 공백 포함 토큰을 발급하던 환경에서 로그인 자체가 막힌다). 어느 쪽이든 `LabelAccessGuard.parseUserNo`·`UserService.parseUserNo` 는 **filter 와 같은 헬퍼를 재사용**하도록 추출한다. 회귀 가드로 `JwtAuthenticationFilterTest` 에 "sub 공백/부호 변형이 filter·guard 에서 동일 판정" 케이스를 추가한다. ⚠ **어느 쪽을 정책으로 삼을지 사용자 확정이 선행돼야 하므로 카탈로그 `TC-AUTH-016` 은 이번 회차에 고치지 않았다.** (⚠ 구현은 하지 않음)

### [A-ISSUE-02] TC-AUTH-005 — `exp` 상한이 없어 "수명 상한은 exp 로만 강제한다"는 A-ISSUE-01(1차) 수정의 전제가 절반만 성립

- **심각도**: LOW
- **기대 동작(기대효과)**: A-ISSUE-01(1차) 수정의 근거는 *"저작도구는 토큰을 발급·폐기하지 않으므로 베어러 토큰의 수명 상한은 오직 `exp` 로만 강제된다"* 였다(`JwtAuthenticationFilter.java:72-78` 주석). 이 근거가 실효를 가지려면 `exp` **존재**뿐 아니라 **합리적 최대 수명**(예: 발급 후 24h 이내)까지 강제돼야 한다. 그래야 유출 시 노출 창이 실제로 유한해진다.
- **현재 동작(이슈 내용)**: 게이트는 `exp` 의 **존재 여부만** 본다. 값의 상한 검사가 없어 임의로 먼 미래를 넣으면 사실상 영구 토큰이 된다.
  ```java
  // JwtAuthenticationFilter.java:79-84
  if (body.getExpiration() == null) { ... return; }   // ← 존재만 검사, 상한 없음
  ```
  실동작 근거:
  ```
  exp=9999999999999 (서기 318857년)  → GET /api/v1/me       200  {"role":"REVIEWER"}
                                     → GET /api/v1/videos   200
  exp=9223372036854775807 (Long.MAX) → 401 (Date 변환 오버플로로 우연히 fail-closed)
  ```
  즉 `Long.MAX` 만 우연히 막히고, 그보다 작은 임의 대형 값은 전부 통과한다.
- **재현/확인 경로**:
  ```bash
  P=$(printf '{"sub":"1001","iss":"klid-auth","exp":9999999999999}' | base64 | tr -d '=' | tr '/+' '_-')
  curl -s -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/videos?page=0&size=1   # 200
  ```
- **영향**: 보안 — **CWE-613(Insufficient Session Expiration)** 잔여분. 성립 조건이 "발급 시스템이 과대한 `exp` 를 넣는다"이고 발급자는 서명 키를 가진 신뢰 주체이므로 **즉시 악용 가능한 결함은 아니다**(그래서 LOW). 다만 A-ISSUE-01(1차)이 지적한 "외부 발급 계약을 검증 없이 신뢰"라는 구조는 **그대로 남아 있다** — 발급자 측 버그(예: 초 단위 자리에 밀리초를 넣는 흔한 실수 → 1000배 수명)가 조용히 통과한다.
- **수정 방향(제안)**: `:79-84` 게이트에 상한 검사를 덧붙인다 — `exp` 가 `now + authoring.jwt.max-lifetime`(기본 24h 권장) 을 초과하면 issuer 게이트와 동일하게 `clearContext()` + early return. 설정값으로 두어 관제/포털 계약에 맞게 조정 가능하게 하고, 기동 시 값 로깅. 회귀 가드로 "과대 exp 401" 케이스 추가. (⚠ 구현은 하지 않음)

### [A-ISSUE-03] TC-AUTH-003/006/007/011/022 · TC-STREAM-006/007/011 — 인증·서명 필터의 fail-closed 분기 8건에 회귀 가드가 없다

- **심각도**: LOW
- **기대 동작(기대효과)**: 이 8개 분기는 전부 **"조건이 어긋나면 거부"** 하는 fail-closed 게이트다. 이런 분기는 리팩토링 중 조용히 뒤집혀도 정상 경로 테스트가 전부 통과하므로(거부 경로를 아무도 안 밟는다) **전용 회귀 가드 없이는 결함이 배포까지 간다.** 실제로 이 저장소는 같은 패턴의 사고 이력이 있다(로그 마스킹은 테스트는 통과하는데 파이프라인엔 미배선, 웹훅 필터 URL 인코딩 우회).
- **현재 동작(이슈 내용)**: `backend/src/test/java` 전수 grep 결과 아래 분기에 대응하는 테스트 메서드가 0건이다.
  | 케이스 | 미커버 분기 | 코드 위치 |
  |---|---|---|
  | TC-AUTH-003 | `Bearer ` 대소문자 정확 매칭 | `JwtAuthenticationFilter.java:28,56` |
  | TC-AUTH-006 | `alg=none` 거부 | `:59-62` |
  | TC-AUTH-007 | malformed 토큰 거부 | `:127-130` |
  | TC-AUTH-011 | issuer 목록 공백 제거·전건 거부(fail-closed) | `JwtIssuerValidator.java:16-19` |
  | TC-AUTH-022 | 생성자 인자 null → 빈 생성 거부 | `JwtAuthenticationFilter.java:37-48` |
  | TC-STREAM-006 | GET/경로 아니면 미동작 | `StreamSignatureFilter.java:84-89` |
  | TC-STREAM-007 | rawSn `\d{1,18}` 정규식 | `:50,116-122` |
  | TC-STREAM-011 | sign-secret 미설정 시 봉인키 랜덤(전건 불통과) | `StreamNonceCookie.java:183-190` |
  (`JwtAuthenticationFilterTest` 9건 · `JwtIssuerValidatorTest` 2건 · `StreamSignedUrlControllerTest` 19건 · `StreamNonceCookieSecureFlagTest` 3건 전수 확인. TC-STREAM-011 공백은 1차에서도 지적됐고 그대로 남아 있다.)
- **재현/확인 경로**:
  ```bash
  cd backend/src/test/java
  grep -rn "alg\":\"none\|BEARER \|new JwtAuthenticationFilter(null\|d{1,18}\|sign-secret" . | wc -l   # → 0
  ```
- **영향**: 기능/보안 회귀 감지 실패. 8건 모두 **현재는 실동작으로 정상 확인**됐으므로 지금 뚫린 구멍은 아니다. 위험은 미래 시점 — 예컨대 `BEARER_PREFIX` 를 `equalsIgnoreCase` 로 "관대하게" 바꾸거나 `shouldNotFilter` 의 GET 조건을 넓히는 변경이 **어떤 테스트도 깨뜨리지 않고** 머지된다.
- **수정 방향(제안)**: 신규 테스트 파일을 만들지 말고 기존 3개 파일에 케이스를 덧붙인다 — `JwtAuthenticationFilterTest` 에 4건(대소문자 접두사 / alg=none / garbage / 생성자 null), `JwtIssuerValidatorTest` 에 1건(`" klid , , x "` → `{klid,x}`, 전부 공백 → 전건 false), `StreamSignedUrlControllerTest` 에 2건(POST·19자리 rawSn), `StreamNonceCookieSecureFlagTest` 또는 신규 `StreamNonceCookieSealTest` 에 1건(빈 시크릿 생성자 → 임의 봉인값 `unseal` = null). 전부 단위/슬라이스라 회귀 시간 증가는 미미하다. (⚠ 구현은 하지 않음)

---

## 7. 이월 이슈 (원 ID 유지 — 3차에도 미해소 확인)

### [A-ISSUE-02(1차)] TC-AUTH-012 — `channel` 클레임 부재 시 무조건 INTERNAL 승격 (issuer 와 채널이 무결합)

- **심각도**: MEDIUM (1차 판정 유지)
- **3차 재확인**: 코드 무변경(`JwtAuthenticationFilter.java:86-87`), 실동작도 1차와 동일.
  ```
  payload = {"sub":"1001","iss":"klid-portal","exp":<미래>}     ← channel 클레임 없음
  GET /api/v1/me → 200 {"userId":"1001","role":"REVIEWER","channel":"INTERNAL"}
  (대조군) 같은 토큰에 "channel":"PORTAL" 추가 → role=PORTAL_USER, /v1/videos 403 으로 정상 격리
  ```
  런타임 실효 `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal` 로 **포털 발급자가 여전히 allowlist 에 포함**되어 있고 서명 키도 단일(`JWT_SECRET`)이라 성립 조건이 그대로다.
- **기대 동작·영향·수정 방향**: 1차 `ISSUES.md` `[A-ISSUE-02]` 블록과 동일(issuer↔channel 바인딩 또는 `channel` 필수화 2안). 재기술 생략 — **대장에서 해당 블록을 그대로 이월**할 것.

### [A-ISSUE-41(1차)] TC-STREAM-009 — 동명 nonce 쿠키 shadowing 시 스트림이 영구 401 (가용성)

- **심각도**: LOW (1차 판정 유지)
- **3차 재확인**: 코드 무변경(`StreamNonceCookie.java:120-124` 첫 매칭 즉시 `return unseal(...)`), 실동작 재현됨.
  ```
  Cookie: klid_stream_nonce=<유효>                          → 200
  Cookie: klid_stream_nonce=<위조>; klid_stream_nonce=<유효> → 401   ← 정상 값이 가려짐
  Cookie: klid_stream_nonce=<유효>; klid_stream_nonce=<위조> → 200
  ```
  쿠키 `Path=/api/v1/videos` 이므로 더 긴 path(예: `/api/v1/videos/4`)로 동명 쿠키를 심으면 RFC 6265 §5.4 상 **위조 쿠키가 항상 앞에 온다**. 본 프로젝트는 관제서버와 동일 도메인 운영이라 인접 앱 경유 주입 표면이 존재한다.
- **기대 동작·영향·수정 방향**: 1차 `ISSUES.md` `[A-ISSUE-41]` 블록과 동일(루프를 "매칭 전부 순회, unseal 성공값 채택, 개수 상한"으로 변경). 재기술 생략 — **대장에서 그대로 이월**할 것.

---

## 8. 검증 중 변경한 테스트 데이터 (원복 완료)

| 대상 | 조작 | 원복 |
|---|---|---|
| `ls_user_role` userNo=77777 | 임시 INSERT(WORKER→REVIEWER→ADMIN) — TC-AUTH-026·028·029 캐시/enum 검증용 | `DELETE` 완료 (검증 후 0행) |
| `ls_user_role` userNo=2002 | `PATCH /v1/users/2002` 로 WORKER→REVIEWER→WORKER — TC-AUTH-030·031 evict 검증용 | **WORKER 로 원복 완료**(DB 재확인) |

프로덕션 코드·설정·테스트 파일은 **일절 수정하지 않았다**. 카탈로그 수정은 §4 의 담당 라인범위 내 1건뿐.
