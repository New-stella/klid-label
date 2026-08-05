# A클러스터 결과 — 테스트케이스 전수 검증 3차 (2026-08-03)

## 0. 판정 집계 요약 (5개 파트 합산)

| 파트 | 담당 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| part1 | A-1 인증(JWT, 34) + A-3 스트림 서명(12) | 46 | 44 | 0 | 2 | 0 | 0 | 0 |
| part2 | A-2 인가(32) + A-5 권한 자가부여(21) | 53 | 46 | 0 | 0 | 7 | 0 | 0 |
| part3 | A-4 웹훅 인증 필터(45, 폐기 1건 제외) | 45 | 45 | 0 | 0 | 0 | 0 | 0 |
| part4 | A-6 추적ID·공통응답·예외·공통유틸(42) | 42 | 42 | 0 | 0 | 0 | 0 | 0 |
| part5 | A-7 듀얼DS·캐시·설정·기동가드·Actuator·Resilience4j(49) | 49 | 49 | 0 | 0 | 0 | 0 | 0 |
| **합계** | | **235** | **226** | **0** | **2** | **7** | **0** | **0** |

- **PASS율**: 226/235 = 96.2%
- **신규 결함(FAIL)**: 0건. PARTIAL 2건(part1, A-ISSUE-01 관련 TC-AUTH-016 계열 — 부분 성립)·BLOCKED 7건(part2, 검증 스택 `ADMIN_CLAIM_PASSWORD_HASH` 공란으로 role-claim 성공 경로 라이브 미확인, A-ISSUE-21 참조)은 전부 아래 이슈 대장에 기록됨.
- **카탈로그 정정 총 16건**: part1 1건(TC-AUTH-005) + part2 1건(TC-AUTHZ-013 근거) + part3 2건(TC-HMAC-022 근거 + A-4 머리말 신설) + part4 0건 + part5 12건(TC-DS-004·TC-CACHE-002/003·TC-SYSCFG-001~009) = **16건**. `git diff docs/test-cases/A-auth-common.md` 로 실제 반영 확인 완료(전건 근거 `file:line` 드리프트 정정 — 정책 변경 아님. 단 TC-AUTH-005는 2026-08-02 신설 게이트(exp 부재 거부) 반영에 따른 기대결과 자체 갱신으로 예외).
- **신규 이슈 16건** 전부 `ISSUES.md` "## A클러스터"에 심각도순 기록 (CRITICAL 0 · HIGH 0 · MEDIUM 5 · LOW 11).

---

## 원본 파트 병합 (part1~part5, 내용 그대로)

---

# A-part1.md

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


---

# A-part2.md

# A 클러스터 part2 — A-2 인가(32) + A-5 권한 자가부여(21) = 53건

- **회차**: 2026-08-03 3차 · **담당 범위**: `docs/test-cases/A-auth-common.md` 55~94행(A-2) + 165~190행(A-5)
- **검증 방식**: 실동작 우선. 풀스택(`_raw/stack-bringup.md` 기준, backend `localhost:18081`, context-path `/api`) 위에서 직접 요청.
- **토큰 조달**: dev 엔드포인트가 아니라 **컨테이너 실효 `JWT_SECRET` 으로 직접 HS256 서명**해 발급(`sub`/`channel`/`iss` 를 자유롭게 조합해야 반증이 가능하므로). 실계정 = 1001 REVIEWER · 2001/2002 WORKER · 3001 PORTAL_USER, 무권한 = 9001~9165·9999(LS_USER_ROLE 미등록).
- **판정 집계**: 총 53 — **PASS 46 · FAIL 0 · PARTIAL 0 · BLOCKED 7 · N/A 0 · 확인필요 0**
- **이슈 ID**: `A-ISSUE-21` ~ `A-ISSUE-27` (⚠ **3차 part2 전용 번호**로, 2026-08-01 1차의 동명 ID 와는 별개다. 각 블록에 1차 원본 ID 를 명시했다.)
- ⚠ **빌드/테스트 미실행**(지시). 3차 `_raw/test-baseline.md` 가 아직 없어 자동테스트 **통과 여부는 미대조**이며, 아래 "테스트 커버" 는 `파일:라인` 존재 확인까지다.

---

## 1. A-2. 인가 (SecurityConfig · 채널 격리 · 화이트리스트) — 32건

| ID | 판정 | 근거 확인 |
|----|:--:|-----------|
| TC-AUTHZ-001 | PASS | [실동작] `POST /v1/manage/labels`(WORKER) → **403** `{"errorCode":"FORBIDDEN"}`. GET 전용 매처 뒤 `/v1/manage/**` hasRole(REVIEWER) 낙하 확인(`SecurityConfig.java:126-127`). 테스트: `SecurityConfigRoleTest.java:41` |
| TC-AUTHZ-002 | PASS | [실동작] `GET /v1/manage/labels` → WORKER/PORTAL/role=null **전부 200 + 라벨 마스터 실데이터**. `SecurityConfig.java:126` `.authenticated()` 그대로 (→ A-ISSUE-23 노출 이슈 별도 기록) |
| TC-AUTHZ-003 | PASS | [실동작] `GET /v1/system/configs`(WORKER) → **403** / (REVIEWER) → 404(핸들러 부재 = 인가는 통과). `GET /v1/system/scheduler/health`(WORKER) → **403**. 근거 `SecurityConfig.java:128` |
| TC-AUTHZ-004 | PASS | [실동작] `GET /actuator/metrics` — WORKER **403** / REVIEWER **200**(메트릭 목록) / 익명 **401**. `/actuator/beans`·`/actuator/env`·`/actuator/prometheus` 익명 **401**. prd `include: health` 는 [정적] `application-prd.yml:93-100` |
| TC-AUTHZ-005 | PASS | [실동작] 익명 `GET /actuator/health` **200** `{"status":"UP","groups":[...]}` (details 없음) / REVIEWER 는 **components 상세 노출** → `show-details: when-authorized`(`application.yml:138-140`) 실동작 일치. `/actuator/info` 200, `/health` 200, `/actuator/health/liveness|readiness` 200 |
| TC-AUTHZ-006 | PASS | [실동작] `/v1/integration/x` — REVIEWER **403** / 익명 **401**. bare prefix `/v1/integration` 도 403. `SecurityConfig.java:120` |
| TC-AUTHZ-007 | PASS | [실동작] `/v1/export-api/x`·`/v1/export-api` — REVIEWER **403**. `SecurityConfig.java:121` |
| TC-AUTHZ-008 | PASS | [실동작] PORTAL 토큰 `GET /v1/portal/datamart/videos` → **200**(21건). `allOf(ROLE_PORTAL_USER, CHANNEL_PORTAL)` (`:134-135`). 테스트 `SecurityConfigChannelTest.java:58` |
| TC-AUTHZ-009 | PASS | [실동작] INTERNAL REVIEWER 토큰 `GET /v1/portal/datamart/videos` → **403**. 테스트 `SecurityConfigChannelTest.java:68` |
| TC-AUTHZ-010 | PASS | [실동작] PORTAL 토큰: `/v1/videos` 403 · `/v1/notices` 403 · `/v1/notices/1` 403 · `/v1/manage/configs`·`/presets`·`/manage/health` 403. **의도된 예외 2곳 실증** — `/v1/manage/labels(/**)` GET 200, `/v1/me` 200. `sub` 를 내부 userNo(1001)로 위장한 PORTAL 토큰도 role=PORTAL_USER 고정 → `/v1/videos` 403 (수직상승 불가) |
| TC-AUTHZ-011 | PASS | [실동작] `GET /v1/notices` — PORTAL_USER **403** / WORKER **200**. `SecurityConfig.java:132` |
| TC-AUTHZ-012 | PASS | [실동작] 익명 `POST /v1/dev/tokens` → **400**(바디 검증) = permitAll 매처 도달. local 프로파일 `authoring.dev.login.enabled=true`(`application-local.yml:104-108`). 프로파일별 실측: local `true` · dev `${DEV_LOGIN_ENABLED:true}` · **stg `false`** · prd 미정의(공통 기본 false) → 카탈로그 "local/dev 만 활성" 정확 |
| TC-AUTHZ-013 | PASS | [정적+실동작] 3중 차단 중 2층을 실동작 확인 — 익명 `POST /v1/dev/batch/run` **401**, WORKER **403**(`/v1/dev/**` hasRole(REVIEWER), `:116`). 3층째 `@ConditionalOnProperty(prefix="authoring.dev.login", name="enabled", havingValue="true")` = `DevTokenController.java:34` [정적]. enabled=false 분기는 local 프로파일이라 live 불가 → 정적 |
| TC-AUTHZ-014 | PASS | [실동작] WORKER `POST /v1/dev/autolabel-test` → **403**, `POST /v1/dev/batch/run` → **403** |
| TC-AUTHZ-015 | PASS | [실동작] 익명 `POST /v1/auth/role-claim` → **401**(permitAll `/v1/auth/**` 보다 먼저 매칭). `SecurityConfig.java:71,90` |
| TC-AUTHZ-016 | PASS | [실동작] 익명 `GET /v1/auth/anything`·`/v1/portal/auth/anything` → **404**(=Security 통과 후 MVC 미매핑). 401 아님 |
| TC-AUTHZ-017 | PASS | [실동작] 익명 `GET /swagger-ui/index.html` 200, `GET /v3/api-docs` 200(134 paths). prd 404 는 [정적] `application-prd.yml:86-90` |
| TC-AUTHZ-018 | PASS | [실동작] 익명 `/v1/videos` → 401 `{"success":false,"data":null,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — 스택/내부경로/서버헤더 없음. 응답에 `Server`·`X-Powered-By` 미부착 확인 |
| TC-AUTHZ-019 | PASS | [실동작] WORKER `/v1/system/configs` → 403 `{"...","errorCode":"FORBIDDEN"}` |
| TC-AUTHZ-020 | PASS | [실동작] 인증/미인증 응답 모두 `Set-Cookie: JSESSIONID` **0건**(grep -c = 0). (stream nonce 쿠키는 세션 아님) |
| TC-AUTHZ-021 | PASS | [실동작] HTTP 응답에 `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY` **항상** 부착(200/401/403 전부 확인). `Strict-Transport-Security` **미부착**(HTTP 요청이므로 기대결과와 일치). 기대결과에 이미 명시된 잔여 이슈는 A-ISSUE-25 로 이월. 테스트 `SecurityConfigTest.java:48`(HTTPS 분기) |
| TC-AUTHZ-022 | PASS | [실동작] CSRF 토큰 없이 `POST /v1/notices`(REVIEWER) → 400(검증) = CSRF 필터 부재. `GET /login` → 401(로그인 폼 없음), Basic 자격증명 무시(`-u` → 401), 응답에 `WWW-Authenticate` 없음 |
| TC-AUTHZ-023 | PASS | [실동작] WORKER `GET /v1/manage/labels` **200** — GET 매처가 REVIEWER 매처보다 앞(순서 역전 없음). ⚠ HEAD 는 GET 매처에 안 걸려 `/v1/manage/**` REVIEWER 로 낙하 → WORKER/PORTAL **403**(더 엄격, 결함 아님) |
| TC-AUTHZ-024 | PASS | [실동작] role=null INTERNAL(sub=9999): `/v1/videos` **403** · `/v1/event-types` **403** · `/v1/videos/901` **403**. 구 fail-open 재현 안 됨. 테스트 `InternalRoleGateMatcherTest.java:63` |
| TC-AUTHZ-025 | PASS | [실동작] role=null INTERNAL `GET /v1/me` → **200** `{"userId":"9999","role":null,"channel":"INTERNAL"}` — 본인 클레임 반향뿐, 업무 데이터 없음. PORTAL 채널도 200 |
| TC-AUTHZ-026 | PASS | [실동작] REVIEWER 로 `GET /v1/videos/906/stream-url` → 서명 URL + `Set-Cookie: klid_stream_nonce=...; HttpOnly; SameSite=Lax; Path=/api/v1/videos; Max-Age=3600`. **Authorization 헤더 없이** 쿠키+서명만으로 `GET .../stream` → **206**. 쿠키 없으면 401 |
| TC-AUTHZ-027 | PASS | [실동작] 익명 `POST /v1/vlm/callback`·`/v1/genai/callback` → **400**(바디 검증 = permitAll 도달) / `POST /v1/aug/callback`·`/v1/deidentify/result` → **401**(permitAll 대상 아님, 매처 제거 확인). 근거 `WebhookProtectedPaths.java:50,57` 정확 |
| TC-CORS-001 | PASS | [실동작] `Origin: http://evil.com` — preflight **403**, 단순 GET 도 **403** 이며 `Access-Control-Allow-Origin` 헤더 **미부착**(origin 반향 없음). 빈 설정(`List.of()`) 분기는 [정적] `SecurityConfig.java:186` |
| TC-CORS-002 | PASS | [정적+실동작] 실효 `CORS_ALLOWED_ORIGINS` 8개 콤마 목록이 그대로 허용됨(`http://localhost:13000` preflight 200). trim 로직은 [정적] `:181-184` |
| TC-CORS-003 | PASS | [실동작] preflight 응답: `Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`(6종) · `Allow-Headers` 요청분 그대로 반향(허용목록 8종 정적 확인) · `Expose-Headers` **8종**(X-Trace-Id, Upload-Offset, Upload-Length, Tus-Resumable, Tus-Version, Tus-Extension, Tus-Max-Size, Location) · `Allow-Credentials: true`, `*` 미사용 |
| TC-ROLE-001 | PASS | [정적+실동작] `RoleHierarchy.java:10-13` `fromHierarchy("")` 유지. **참조 코드 0건**(`grep -rn RoleHierarchy backend/src` → 정의 파일 외 매칭 없음). 실동작으로도 상속 부재 확인(REVIEWER→WORKER 하향 상속 없음: WORKER 는 REVIEWER 경로 전건 403). UNCERTAINTIES #6 **미해소 유지** → A-ISSUE-26 |
| TC-ROLE-002 | PASS | [실동작+정적] `Role`=REVIEWER/WORKER/PORTAL_USER 3값(`Role.java:3-7`), `Channel`=INTERNAL/PORTAL 2값(`Channel.java:3-6`). `channel:"EXTERNAL"` 토큰 → `/v1/me`·`/v1/videos` **401**(valueOf 예외 → 컨텍스트 미설정), `role:"ADMIN"` role-claim 요청 → **400** `INVALID_INPUT` |

### A-2 반증(우회) 시도 결과 — 전부 차단

| 우회 벡터 | 시도 | 결과 |
|---|---|---|
| 대소문자 | `/v1/System/configs`(WORKER) · `/v1/Integration/x`(REVIEWER) · `/v1/Portal/...`(INTERNAL) · `/V1/videos`(role=null) | 전부 **404**. Security 매처가 케이스 센시티브라 `/v1/**` 로 낙하하지만 **MVC 라우팅도 케이스 센시티브라 핸들러 미존재** → 실제 도달 0. 우회 불가 |
| trailing slash | `/v1/system/configs/`(WORKER) · `/v1/videos/`(role=null) | **403** 유지 |
| 퍼센트 인코딩 | `/v1/%73ystem/configs`(WORKER) | **403** (정규화 후 매칭) |
| 경로 순회 `..` | `/v1/manage/labels/../system/configs` · `/v1/manage/labels/../../videos` · `/actuator/health/../metrics` · `/v1/portal/../portal/...` · `/api/v1/x/../actuator/health` | 전부 **401** — 컨테이너/Security firewall 단계에서 거부되어 어떤 매처도 통과하지 못함(fail-closed). REVIEWER 정상 토큰으로도 401 |
| 세미콜론 path param | `/v1/system;a=b/configs` · `/v1/videos;a=1` | **401** (동일) |
| 중복 슬래시 | `//v1/system/configs` · `/v1//system/configs` · `/v1//portal/...` | **401** (동일) |
| bare prefix | `/v1/integration`·`/v1/export-api`·`/v1/dev`·`/v1/system`·`/v1/manage`·`/v1/portal`·`/actuator` | 전부 게이트 적용(403/401) — `/**` 매처가 0-세그먼트도 커버 |
| 매처 사각지대 | OpenAPI 134 경로 스윕 → `/v1` 밖 경로는 `/health` 1개뿐(permitAll 의도) | 사각 없음 |
| 서명 스트림 권한 확대 | 발급된 `exp/u/sig` 쿼리를 `/v1/videos`·`/v1/manage/labels`·`/v1/system/configs`·`/v1/videos/906/frames` 에 재부착(+nonce 쿠키) | 전부 **401** — `StreamSignatureFilter` 가 스트림 경로에만 컨텍스트를 세팅하고 ROLE_* 도 부여하지 않음(`:133-138`) |
| 클레임 위조 | INTERNAL 토큰에 `role:"REVIEWER"` 주입(실제 LS=WORKER) | `/v1/me` role=WORKER, `/v1/system/**` **403** — JWT role 클레임 미참조 확인 |
| 수직 상승(업무 API) | WORKER 로 `POST /v1/assignments`(정상 바디) · `PATCH /v1/assignments/1` · `POST /v1/reviews/906/approve|reject` · `GET /v1/users(/{no})` · `PATCH /v1/users/1001` · `POST /v1/control-ingests/requeue` · `GET /v1/stats/overall` · `GET /v1/tasks/board` | 전부 **403** |
| 수평(IDOR) | WORKER(2001) → `GET /v1/assignments/{67,59,54,73}/history`(타 사용자 배정) | **403** "본인 배정 이력만 조회할 수 있습니다" |
| 목록 스코프 | WORKER `GET /v1/assignments?size=50` · `GET /v1/stats/worker?userNo=2002` | 각각 본인(workerId=2001)만 반환 / 파라미터 무시하고 본인 통계 반환 — 누수 없음 |
| 채널 위장 | `channel` 클레임 **제거** + `iss=klid-portal` + `sub=2001(WORKER)` | **CHANNEL_INTERNAL 승격 → `/v1/videos` 200** ← 유일하게 통과한 벡터. **A-ISSUE-24** 참조(1차 A-ISSUE-02 미해소 이월) |

---

## 2. A-5. 권한 자가부여 (RoleClaimService · RoleClaimRateLimiter) — 21건

> ⚠ **실행 스택의 `ADMIN_CLAIM_PASSWORD_HASH` 가 여전히 빈 문자열**(`docker exec klid-backend printenv` 확인)이라 pw 게이트 이후 로직에 **어떤 요청도 도달하지 못한다**. 1차(2026-08-01) A-ISSUE-23 의 환경 준비 권고가 반영되지 않았다 → 7건 BLOCKED, **A-ISSUE-21** 로 이월.

| ID | 판정 | 근거 확인 |
|----|:--:|-----------|
| TC-CLAIM-001 | BLOCKED | [실동작] pw 게이트(`RoleClaimService.java:137`)가 빈 해시로 전건 401 → upsert/토큰발급 도달 불가. 사유·해소책 A-ISSUE-21. 단위테스트 `RoleClaimServiceTest.java:107` 존재(통과여부 미대조) |
| TC-CLAIM-002 | PASS | [실동작] role=PORTAL_USER + 유효길이 pw → **400** `"PORTAL_USER 역할은 본 API 로 부여할 수 없습니다."`(`:111-113`). ⚠ pw 가 4자 미만이면 `@Size(4,100)` 바디검증이 먼저 400 을 내 이 분기에 도달하지 않는다(관측 주의) |
| TC-CLAIM-003 | PASS | [실동작] WORKER(2001)·REVIEWER(1001) 토큰 → **409** `"이미 권한이 부여된 사용자입니다."`(`:127-128`) |
| TC-CLAIM-004 | PASS | [실동작] PORTAL 채널 토큰(3001) → **409**(deny-by-default). SecurityConfig 는 `authenticated()` 뿐(`:71`)이므로 서비스단이 유일 방어선임도 확인(익명은 401) |
| TC-CLAIM-005 | BLOCKED | [실동작] 틀린 pw → 401 이 나오긴 하나, 빈 해시라 `adminPasswordHash.isEmpty()` 단락 평가에서 종결되어 **`BCryptPasswordEncoder.matches` 상수시간 경로 자체에 도달하지 않는다**. A-ISSUE-21 |
| TC-CLAIM-006 | PASS | [실동작] 임의 pw(`admin1234`)·로컬 yml 기본해시에 대응하는 평문 후보 모두 **401** `"관리자 패스워드가 일치하지 않습니다."` — 빈 해시 fail-closed 실증(`:137`) |
| TC-CLAIM-007 | PASS | [정적] `isBcryptHash`(`:101-103`) prefix `$2a$/$2b$/$2y$` 검사 + 불일치 시 `IllegalStateException`(`:92-94`). 기동 거부는 설정 변경이 필요해 live 불가. 단위테스트 `RoleClaimServiceTest.java:319` |
| TC-CLAIM-008 | PASS | [실동작] 동일 sub(9003) 연속 호출 → **1~5회 401 / 6·7회 429**. 검사 순서도 실증 — REVIEWER 요청(403)·409 요청은 `ls_authrt_grant_atmpt` 에 ACCOUNT 행을 만들지 않음 = 화이트리스트·채널게이트가 rate limit **앞**(`:118→:127→:133`) |
| TC-CLAIM-009 | BLOCKED | [실동작] pw 게이트 이전 종결. `:160-161` 이중 게이트 미도달. A-ISSUE-21 |
| TC-CLAIM-010 | BLOCKED | 〃 (`:153-155` NOT_FOUND 미도달) |
| TC-CLAIM-011 | BLOCKED | 〃 (`:168-180` AFTER_COMMIT evict 미도달) |
| TC-CLAIM-012 | BLOCKED | 〃 (`:61` TTL 3600s, `:195-208` 토큰 발급 미도달) |
| TC-CLAIM-013 | PASS | [실동작] 실패 로그 원문 `[RoleClaim] denied userNo=9149 role=WORKER reason=invalid_password` — **adminPassword 평문 미출력**, sub 32자 절단·`[\r\n\t]`→`_`(`:119-120,140-141,211-218`). rate-limit 로그도 식별자 미노출 |
| TC-CLAIM-014 | BLOCKED | [실동작] sub="abc" 요청이 400 이 아니라 **401**(pw 게이트가 앞). 기대한 `:147-151` 분기 미도달. A-ISSUE-21 |
| TC-CLAIM-015 | PASS | [실동작] role=REVIEWER + 유효 pw → **403** `"해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요."`(`:118-123`, `allowedClaimRoles()` `:227-229`). **쿼터 미소모 실증** — 해당 sub(9002)에 ACCOUNT 시도행 0건. **1차 A-ISSUE-17(CWE-269 공유 정적 패스워드 REVIEWER 자가부여) 해소 확인** |
| TC-CLAIM-016 | PASS | [실동작] 서로 다른 60개 sub 로 1분 내 호출 → **50회까지 401, 51회째부터 429**. `ls_authrt_grant_atmpt` GLOBAL 버킷 `atmpt_nmtm=61`. 교차계정 증폭 차단됨(`RoleClaimRateLimiter.java:81-82,108-109`) |
| TC-CLAIM-017 | PASS | [정적] `Math.max(local, shared)`(`:116-127`) + `JdbcRoleClaimAttemptStore.java:80-84` 가 `DataAccessException` → `UNAVAILABLE(-1)` 반환. DB 장애 주입은 스택 파괴라 미수행. 단위테스트 `RoleClaimRateLimiterTest.java:95` |
| TC-CLAIM-018 | PASS | [실동작] **UTC 고정 실증** — 요청 시각 KST 2026-08-04 00:08/00:09 인데 `ls_authrt_grant_atmpt.bgng_dt` 는 `2026-08-03 15:08/15:09`(UTC, 분 절단). `RoleClaimRateLimiter.java:105-106` |
| TC-CLAIM-019 | PASS | [정적] Caffeine `expireAfterWrite(10분)` + `maximumSize(10_000)`(`:60-64,91-95`). 단위테스트 `RoleClaimRateLimiterTest.java:152`(가상 Ticker) |
| TC-CLAIM-020 | PASS | [정적] `seCd + '\0' + idntfr + '\0' + windowStart`(`:113`) — 축·식별자·창 3분할, 런타임 동작 불변 |
| TC-CLAIM-021 | PASS | [실동작] 기동 로그 `[RoleClaim] attempt purge job scheduled intervalMs=600000` 확인. 데몬 스레드명 `role-claim-attempt-purge`(`RoleClaimAttemptPurgeJob.java:62-68`), 하한 60s clamp·초기지연 300s(`:47-52`), `catch (Throwable)` 로 스케줄러 사망 차단(`:91`) |

---

## 3. 카탈로그 정정 (담당 라인범위 내)

| # | 위치 | 이전 | 이후 | 근거 |
|:--:|---|---|---|---|
| 1 | 71행 `TC-AUTHZ-013` 근거 | `SecurityConfig.java:52-53,103,116; application.yml:456-458` | `SecurityConfig.java:52-53,103,116; application.yml:462-464; DevTokenController.java:34` | `application.yml:456-458` 은 **주석 3줄**만 가리키고 실제 프로퍼티(`authoring.dev.login.enabled: ${DEV_LOGIN_ENABLED:false}`)는 **462~464행**이다(실측). 또한 기대결과가 말하는 "3중 차단"의 3번째 층(`@ConditionalOnProperty`) 근거가 어디에도 없어 `DevTokenController.java:34` 를 추가했다. **정책 변경 아님 — 근거 드리프트 정정** |

> 그 외 담당 범위 53건의 근거 `file:line` 은 **전수 재확인 결과 드리프트 없음**(A-2 32건 · A-5 21건). 특히 회차2에서 갱신된 `SecurityConfig.java` 라인(71/84/85-90/100-102/116/118/120/121/126-128/132/134-135/147-152/156-157/178-200/239-244), `RoleClaimService.java`(87-97/101-103/111-123/127-133/137-143/147-155/160-161/168-180/195-208/211-218/227-229), `RoleClaimRateLimiter.java`(60-64/81-82/91-95/104-128), `RoleClaimAttemptPurgeJob.java`(47-49/62-68/86-94), `StreamSignatureFilter.java:136-138`, `WebhookProtectedPaths.java:50,57`, `RoleHierarchy.java:10-13`, `Role.java:3-7`, `Channel.java:3-6`, `application.yml:138-140`, `application-prd.yml:86-90,93-100` 을 개별 확인했다.

---

## 4. 이전 회차 이슈 대조 (담당 범위)

| 1차(2026-08-01) ID | 내용 | 3차 상태 | 근거 |
|---|---|:--:|---|
| **A-ISSUE-17**(2026-07-25 1차) | 공유 정적 패스워드로 **REVIEWER 자가부여**(CWE-269/1392/778) | **해소** | `allowedClaimRoles()`(WORKER 단일)가 `claim()` **최상단**에서 강제(`:118-123`). 실동작 403. 잔여(감사 테이블·pw 회전 정책 부재)는 A-ISSUE-27 |
| **A-ISSUE-18**(2026-07-25 1차) | rate limit 이 JVM 로컬 `ConcurrentHashMap`, 교차계정·다중노드 우회, 맵 무한증가 | **해소** | 계정+전역 2축, 공유 저장소(`ls_authrt_grant_atmpt`) 합산, Caffeine TTL/maximumSize. 실동작 5/분·50/분 확인 |
| A-ISSUE-02 | `channel` 클레임 부재 시 무조건 INTERNAL 승격 (issuer↔채널 무결합) | **미해소** | 실동작 재현. → **A-ISSUE-24** |
| A-ISSUE-06 / UNCERTAINTIES #28 | HSTS 가 앱·edge 어디에서도 미부여 | **미해소** | HTTP 응답에 `Strict-Transport-Security` 없음(재확인). → **A-ISSUE-25** |
| A-ISSUE-21 | role-claim 전역 축(50/분)을 단일 계정이 소진 → 전원 온보딩 차단 | **미해소** | 60회 소진 후 신규 계정 첫 시도 429 재현. → **A-ISSUE-22** |
| A-ISSUE-22 | `/v1/manage/labels/**` GET 예외가 PORTAL·role=null 에게 라벨 마스터/COCO 매핑/속성정의 노출 | **미해소** | 3경로 전부 200 재현. → **A-ISSUE-23** |
| A-ISSUE-23 | 검증 스택 `ADMIN_CLAIM_PASSWORD_HASH` 공란 → role-claim 성공 경로 검증 불가 | **미해소** | `printenv` 빈 값 재확인. → **A-ISSUE-21** |
| UNCERTAINTIES #6 | `RoleHierarchy` 빈 계층 + 참조 0건 | **미해소** | → **A-ISSUE-26** |
| A-ISSUE-71(참고, A-6 소관) | `@Valid` 바디 검증이 `@PreAuthorize` 보다 먼저 실행 → 권한 없는 사용자에게 입력 스키마 노출 | **미해소**(범위 밖, 사실만 기록) | WORKER `POST /v1/assignments {}` → **400 + 필수필드 목록**, 정상 바디로는 403. `AssignmentController.java:67` `@PreAuthorize("hasRole('REVIEWER')")` 존재하나 검증이 선행 |

---

## 5. 이슈 상세 (§7 6요소)

### [A-ISSUE-21] TC-CLAIM-001/005/009/010/011/012/014 — 검증 스택 `ADMIN_CLAIM_PASSWORD_HASH` 공란이 3회차째 유지되어 role-claim 성공 경로가 여전히 미검증
- **심각도**: LOW *(코드 결함 아님 — 검증 환경 구성 이슈. 단 1차 권고 후 2회차가 지나도록 반영되지 않아 승격 없이 재기록)*
- **기대 동작(기대효과)**: 권한 자가부여의 성공 경로(WORKER 부여 → `LS_USER_ROLE` upsert → AFTER_COMMIT 캐시 evict → 새 INTERNAL 토큰 발급)는 **수직 권한 상승과 직결된 유일한 온보딩 경로**이므로 VERIFY-PROMPT §1(실동작 기준)에 따라 실제 요청으로 확인돼야 한다.
- **현재 동작(이슈 내용)**: 실행 중 backend 의 실효 환경변수가 빈 문자열이라 `RoleClaimService` 가 해시를 `""` 로 유지하고 모든 요청을 pw 게이트에서 종결한다.
  ```
  $ docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH
  (빈 줄)
  ```
  ```java
  // RoleClaimService.java:137-143
  if (adminPasswordHash.isEmpty() ||
          !passwordEncoder.matches(req.adminPassword(), adminPasswordHash)) {
      log.warn("[RoleClaim] denied userNo={} role={} reason=invalid_password", ...);
      throw new CustomException(ErrorCode.UNAUTHORIZED, "관리자 패스워드가 일치하지 않습니다.");
  ```
  `application-local.yml:35` 의 기본 해시는 **환경변수가 "존재하되 빈 값"이라 적용되지 않는다**(Spring 은 이를 `""` 로 해석). 결과적으로 pw 이후 5개 분기(사용자 404 · LS 이중게이트 409 · sub 파싱 400 · upsert · evict · 토큰발급)가 전부 도달 불가다. 부수적으로 **`BCryptPasswordEncoder.matches` 상수시간 비교 경로(TC-CLAIM-005)도 단락 평가로 건너뛴다**.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH   # → 빈 값
  T=<role=null INTERNAL JWT>
  curl -s -X POST -H "Content-Type: application/json" -H "Authorization: Bearer $T" \
    -d '{"role":"WORKER","adminPassword":"admin1234"}' http://localhost:18081/api/v1/auth/role-claim
  # → 401 (어떤 평문으로도 동일)
  ```
- **영향**: 검증 커버리지 — A-5 21건 중 **7건이 정적+단위테스트 근거에 머문다**. 운영 관점으로는 이 설정이 그대로 배포되면 role-claim 온보딩이 **조용히 항상 실패**한다(기동 경고 없음).
- **수정 방향(제안)**: (구현하지 않음) ①다음 회차 스택 기동 시 `docker-compose.local.yml` 에 로컬 전용 BCrypt(cost 12) 해시를 주입해 성공 경로를 live 대상에 포함 ②코드 측으로는 `RoleClaimService` 생성자에서 공란 감지 시 기동 WARN(`DevToggleStartupWarner` 동형)을 남겨 fail-closed 는 유지하되 **무음 상태를 제거**.

### [A-ISSUE-22] TC-CLAIM-016 — role-claim 전역 축(50회/분)을 단일 계정이 소진해 전 사용자 온보딩을 차단할 수 있다(미해소 이월)
- **심각도**: MEDIUM *(케이스 판정은 PASS — "교차계정 증폭 차단"이라는 기대결과 자체는 정확히 동작. 그 설계의 반대편 실패 모드)*
- **기대 동작(기대효과)**: 전역 축은 계정 A/B/C 를 번갈아 쓰는 무차별 대입 증폭 차단이 목적이며, 동시에 **정상 신규 사용자의 온보딩은 가용해야 한다** — role-claim 은 role=null 사용자가 시스템에 진입하는 유일한 경로다(TC-AUTHZ-024/025).
- **현재 동작(이슈 내용)**: 전역 축은 엔드포인트 단일 카운터(`GLOBAL_IDNTFR="GLOBAL"`)이고 **거부된 시도에도 계속 증가**한다(증가 후 판정).
  ```java
  // RoleClaimRateLimiter.java:112-127
  int local = localCounters.get(cacheKey, k -> new AtomicInteger()).incrementAndGet();
  int shared = sharedStore == null ? UNAVAILABLE : sharedStore.recordAttempt(...);
  int effective = Math.max(local, shared);
  if (effective > limit) { log.warn(...); throw new CustomException(ErrorCode.TOO_MANY_REQUESTS, ...); }
  ```
  실측(3차): 1분 내 60회 호출 후 **전혀 다른 신규 계정(sub=9999001)의 첫 시도가 429**. 로그는 `axis=GLOBAL attempts=51..61 limit=50` 으로 임계 초과 후에도 상승했고 DB `atmpt_nmtm=61` 로 확정됐다. 추가 관측 2건: ①**429 응답에 `Retry-After` 헤더가 없다**(정상 사용자가 재시도 시점을 알 수 없음) ②`rate-limited axis=GLOBAL` 로그에 **소진 주체 식별자가 없어** 운영자가 원인 계정을 특정할 수 없다.
- **재현/확인 경로**:
  ```bash
  # 서로 다른 sub 60개(또는 동일 sub)로 1분 내 호출 — pw 는 틀려도 무방(rate limit 이 pw 앞)
  for t in $(cat toks.txt | head -60); do curl -s -o /dev/null -w "%{http_code} " -X POST \
    -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
    -d '{"role":"WORKER","adminPassword":"xxxx"}' http://localhost:18081/api/v1/auth/role-claim; done
  # → 50회 401, 51회째부터 429. 이후 신규 계정 첫 시도도 429
  ```
  ```sql
  select atmpt_se_cd, atmpt_idntfr, bgng_dt, atmpt_nmtm from ls_authrt_grant_atmpt
   where atmpt_se_cd='GLOBAL' order by bgng_dt desc limit 3;   -- 51 초과 후에도 61까지 증가
  ```
- **영향**: 가용성 — 신규/역할미배정 사용자 온보딩 전면 차단(CWE-770, OWASP API4:2023). 인증된 저권한 계정 1개면 성립. 보안 강도(무차별 대입 억제)는 손상되지 않는다.
- **수정 방향(제안)**: (구현하지 않음) ①임계 초과가 확정된 축은 카운터를 더 올리지 않고 즉시 거절(고착·DB 쓰기 증폭 동시 완화) ②전역 축을 차단이 아니라 경보+지수 백오프로 낮추고 실제 차단은 계정 축이 담당 ③`ATMPT_IDNTFR` 별 상위 소비자를 집계해 소진 계정만 장기 차단 + 로그에 축별 상위 소비자 노출 ④최소한 429 에 `Retry-After: 60` 부착(`HmacWebhookFilter` 는 이미 그렇게 한다 — 동일 프로젝트 내 비대칭).

### [A-ISSUE-23] TC-AUTHZ-002 / TC-AUTHZ-010 / TC-AUTHZ-024 — `/v1/manage/labels/**` GET 예외가 PORTAL 채널·역할미배정에게 라벨 마스터·COCO 검출 매핑·속성 정의를 노출(미해소 이월)
- **심각도**: LOW *(케이스 판정은 PASS — 카탈로그가 "의도된 예외"로 명시하고 실동작이 일치. 예외의 정당화 근거가 `/v1/me` 대비 약하다는 점을 재기록)*
- **기대 동작(기대효과)**: 채널 격리(R5-1)와 role=null fail-open 차단(A-ISSUE-02 수정)의 예외는 **업무 데이터가 없는 경로로 한정**되어야 한다. `SecurityConfig.java:72-83` 이 `/v1/me` 예외를 정당화한 근거가 정확히 그것이다.
- **현재 동작(이슈 내용)**: `/v1/manage/labels` 와 하위 전체가 GET 에 한해 `authenticated()` 다.
  ```java
  // SecurityConfig.java:126
  .requestMatchers(HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  ```
  3차 실측 — PORTAL 채널 토큰·role=null INTERNAL 토큰·WORKER 모두 아래 3경로가 **200 + 실데이터**:
  `GET /v1/manage/labels`(라벨 마스터 전량 `labelId/name/color/type/dtctTypeCd/useYn`) · `GET /v1/manage/labels/detect-candidates`(**COCO 검출 클래스 매핑** — 포털은 ADR-013 상 오토라벨링 미제공이라 불필요) · `GET /v1/manage/labels/{labelId}/attrs`(속성 정의). 같은 `/v1/manage` 하위의 `configs`·`presets`·`health` 는 정상적으로 403 이라 **노출 범위는 labels 3경로로 한정**됨을 재확인했다.
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $T_PORTAL" http://localhost:18081/api/v1/manage/labels/detect-candidates
  curl -s -H "Authorization: Bearer $T_NOROLE" http://localhost:18081/api/v1/manage/labels/1/attrs
  # → 둘 다 200 + 실데이터
  ```
- **영향**: 정보 노출(CWE-200) · 불완전한 채널 격리(CWE-863 부분). 유출물은 PII 가 아닌 라벨 taxonomy·AI 검출 클래스 매핑·속성 스키마.
- **수정 방향(제안)**: (구현하지 않음) ①`detect-candidates` 를 `hasAnyRole(REVIEWER, WORKER)` 로 분리(포털에 불필요) ②와일드카드 대신 **필요한 GET 서브리소스만 명시 매처로 나열**(신규 GET 이 자동으로 예외에 편입되는 구조 제거) ③role=null 은 예외에서 제외(채널 조건만 완화) ④`/v1/me` 예외 주석과 같은 불변식 주석을 이 매처에도 명시.

### [A-ISSUE-24] TC-AUTHZ-009 / TC-AUTHZ-010 — `channel` 클레임을 생략한 포털 발급 토큰이 INTERNAL 로 승격되어 채널 격리를 우회(미해소 이월, 1차 A-ISSUE-02)
- **심각도**: MEDIUM *(케이스 판정은 PASS — 카탈로그가 기술한 `CHANNEL_PORTAL` 토큰 시나리오는 정확히 403. 그 전제를 무너뜨리는 인접 경로를 기록)*
- **기대 동작(기대효과)**: R5-1 채널 격리는 "외부(포털) 채널 토큰이 내부 업무 API 에 도달하지 못한다"를 보장해야 한다. 두 채널이 **동일 시크릿·동일 issuer allowlist** 를 공유하므로(CLAUDE.md "인증·진입"), 채널 판정은 클레임 유무가 아니라 **발급자와 결합**돼야 한다.
- **현재 동작(이슈 내용)**: `channel` 클레임이 없으면 발급 issuer 와 무관하게 INTERNAL 로 기본값 처리된다(`JwtAuthenticationFilter.java:86-87`, `SecurityConfig.java:141-146` 주석이 "기존 내부 사용자 토큰 호환"으로 의도 명시). 실측:
  ```
  iss=klid-portal, channel 클레임 없음, sub=2001(LS_USER_ROLE=WORKER)
   → GET /v1/me  : {"role":"WORKER","channel":"INTERNAL"}
   → GET /v1/videos : 200   (내부 업무 API 통과)
  iss=klid-portal, channel 없음, sub=1001(REVIEWER)
   → GET /actuator/metrics : 200
  ```
  **완화 요인(실측)**: 역할은 여전히 `LS_USER_ROLE` 에서 오므로, 실제 포털 사용자(sub=3001, LS=PORTAL_USER)가 같은 짓을 하면 `CHANNEL_INTERNAL + ROLE_PORTAL_USER` 가 되어 `/v1/**` 403 이다(자기 포털 API 도 403 이 되어 오히려 기능만 깨진다). 따라서 성립 조건은 **"sub 를 내부 userNo 로 지정한 토큰을 얻을 수 있는가"** = 포털 발급 서버의 오작동·침해 또는 공유 시크릿 유출이다.
- **재현/확인 경로**:
  ```bash
  # JWT_SECRET 으로 {"iss":"klid-portal","sub":"2001"} (channel 클레임 없음) 서명
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" http://localhost:18081/api/v1/videos   # → 200
  ```
- **영향**: 보안 — 불완전한 채널 격리(CWE-863) · 신뢰 경계 혼동(CWE-501). 단독 취약점이 아니라 "포털 발급 서버 침해 시 저작도구 내부 API 까지 즉시 확산"이라는 **폭발 반경 확대** 성격이다.
- **수정 방향(제안)**: (구현하지 않음) ①`channel` 을 **필수 클레임으로 승격**하고 미보유 토큰을 거부(하위호환이 필요하면 이행 기간 동안 WARN 후 차단) ②또는 issuer→channel 매핑표(`klid-portal → PORTAL`)를 두고 클레임과 불일치 시 거부 ③장기적으로 채널별 서명 키 분리. ⚠ ①은 기존 관제 토큰 호환을 깨뜨릴 수 있어 관제팀 협의 대상.

### [A-ISSUE-25] TC-AUTHZ-021 — HSTS 가 앱에서도 edge 에서도 실제로 부여되지 않는다(미해소 이월, 1차 A-ISSUE-06 / UNCERTAINTIES #28)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 운영 도메인 응답에 `Strict-Transport-Security` 가 부착돼 SSL stripping·평문 다운그레이드를 차단해야 한다(security.md "Security 응답 헤더 필수").
- **현재 동작(이슈 내용)**: `SecurityConfig.java:64-67` 에 `httpStrictTransportSecurity(includeSubDomains, maxAge=31536000)` 가 설정돼 있으나 Spring Security 의 `SecureRequestMatcher` 때문에 **HTTPS 요청에만** 부착된다. 3차 실측에서 HTTP 응답(200·401·403 전부)에 헤더가 없음을 재확인했고, `nosniff`·`X-Frame-Options: DENY` 는 정상 부착됐다. 로컬 스택에 TLS 종단이 없어 **운영 edge(nginx/Caddy) 부여 여부는 이 회차에서도 확인 불가**.
- **재현/확인 경로**:
  ```bash
  curl -s -D - -o /dev/null -H "Authorization: Bearer $T_REV" http://localhost:18081/api/v1/videos | grep -i strict
  # → 출력 없음 (nosniff / X-Frame-Options 는 출력됨)
  ```
- **영향**: 보안 — 전송 계층 다운그레이드 방어 부재(OWASP A02:2025 Security Misconfiguration).
- **수정 방향(제안)**: (구현하지 않음) ①운영 edge(nginx/Caddy) 설정에 HSTS 를 명시하고 그 설정을 `deploy/onprem/` 문서에 고정 ②또는 앱이 `X-Forwarded-Proto` 를 신뢰하도록 `server.forward-headers-strategy` 를 켜서 프록시 뒤 HTTPS 요청을 인식하게 함(신뢰 프록시 CIDR 전제 필요) ③어느 쪽이든 **배포 체크리스트에 실응답 헤더 확인 항목**을 넣어 "설정은 있는데 안 나감"이 재발하지 않게 함.

### [A-ISSUE-26] TC-ROLE-001 — `RoleHierarchy` 빈이 빈 계층 + 참조 0건으로 사실상 no-op(UNCERTAINTIES #6 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 등록된 스프링 빈은 실제 인가 판정에 관여하거나, 관여하지 않는다면 제거돼 **"역할 상속이 설정돼 있다"는 오독**을 만들지 않아야 한다.
- **현재 동작(이슈 내용)**:
  ```java
  // RoleHierarchy.java:10-13
  @Bean
  public org.springframework.security.access.hierarchicalroles.RoleHierarchy authoringRoleHierarchy() {
      return RoleHierarchyImpl.fromHierarchy("");     // 상속 규칙 0건
  }
  ```
  `grep -rn RoleHierarchy backend/src` 결과 **정의 파일 외 참조 0건**(3차 재확인). 실동작으로도 상속 부재가 확인된다 — WORKER 는 `/v1/system/**`·`/v1/manage/**`(쓰기)·`/actuator/**`·`/v1/users/**`·`/v1/reviews/*/approve` 전건 403.
- **재현/확인 경로**: `grep -rn 'RoleHierarchy' backend/src` → 1개 파일만 매칭.
- **영향**: 유지보수 — 후속 개발자가 "역할 계층이 이미 배선돼 있다"고 오해해 상속 문자열만 채우면 **아무 효과 없이 통과**한다(무음 실패).
- **수정 방향(제안)**: (구현하지 않음) 정책 결정 필요 — ①빈을 제거하고 "명시 역할 나열" 정책을 주석으로 고정, 또는 ②실제로 계층을 쓸 계획이면 `@EnableMethodSecurity` 표현식 핸들러·`authorizeHttpRequests` 에 배선하고 회귀 테스트를 추가. 현 상태(정의만 존재)는 둘 다 아니다.

### [A-ISSUE-27] TC-CLAIM-015 — REVIEWER 상승은 막혔으나 공유 정적 패스워드 자체의 잔여 통제(감사 원장·회전·만료)가 여전히 없다
- **심각도**: LOW *(케이스 판정은 PASS — 1차 A-ISSUE-17 의 핵심 요구 ①②는 해소됨. 미이행 항목 ③만 잔존)*
- **기대 동작(기대효과)**: 1차 A-ISSUE-17 수정 제안 ③ — 공유 패스워드 기반 자가부여를 유지한다면 **부여 이력 전용 원장**(요청자·역할·시각·IP·요청ID)과 **패스워드 회전·만료 정책**이 있어야 한다(CWE-1392 Use of Shared Credentials, CWE-778 Insufficient Logging).
- **현재 동작(이슈 내용)**: 부여 성공의 유일한 흔적은 애플리케이션 로그 1줄이다.
  ```java
  // RoleClaimService.java:185
  log.info("[RoleClaim] granted userNo={} role={}", userNo, req.role().name());
  ```
  `ls_authrt_grant_atmpt` 는 **rate limit 카운터 전용**(축/식별자/버킷/횟수)이라 감사 원장이 아니고 IP·요청ID·성공 여부를 담지 않는다. 패스워드는 `authoring.auth.admin-claim-password-hash` 단일 값으로 회전 주기·만료·직전 값 무효화 개념이 없다.
- **재현/확인 경로**: `\d ls_authrt_grant_atmpt` → 컬럼 `atmpt_se_cd/atmpt_idntfr/bgng_dt/atmpt_nmtm/expd_dt` 뿐. `grep -rn "granted userNo" backend/src/main` → 로그 1곳.
- **영향**: 감사·사후추적 — 공유 패스워드가 유출돼 다수 계정이 WORKER 를 자가부여해도 **누가·언제·어디서 받았는지 DB 로 조회할 수 없다**(로그 보존기간에 의존). WORKER 는 라벨 수정·검수 제출 권한이라 데이터 무결성 영향이 있다.
- **수정 방향(제안)**: (구현하지 않음) ①부여 성공 시 전용 이력 테이블에 (userNo, role, 시각, clientIp, traceId, 결과) 적재 — 표준용어·표준도메인 준수 필요 ②패스워드 회전 절차와 만료 주기를 운영 문서(`deploy/onprem/docs/`)에 고정 ③선택적으로 부여 발생 시 기존 REVIEWER 통지.

---

## 6. 참고 — 판정에 반영하지 않은 관측

- **경로 조작 요청의 응답 코드가 401 이다**: `..`·`;`·`//`·`/./` 를 포함한 요청은 유효한 REVIEWER 토큰을 붙여도 401 `UNAUTHORIZED` 로 종결된다(표준 `ApiResponse`, 스택트레이스 없음). **거부 자체는 fail-closed 로 정상**이나, 의미상 400(Bad Request)이 적절한 요청이 401 로 나가 클라이언트가 "토큰 문제"로 오진할 수 있다. 1차 A-ISSUE-64(`RequestRejectedException` 500 오분류)와 같은 계열이며 A-6 소관이라 이슈로 올리지 않고 사실만 기록한다.
- **`GET /v1/assignments` 는 `@PreAuthorize("isAuthenticated()")`** (`AssignmentController.java:113`)라 WORKER 도 200 이지만, 실측상 **본인 배정만** 반환되고 타인 배정 이력은 403 이다. CLAUDE.md 의 "배정 이력 조회 권한은 REVIEWER" 서술과 코드가 완전히 일치하지는 않으나 실데이터 누수는 없다 — D 클러스터(TC-ASSIGN-*) 소관이라 여기서 판정하지 않는다.
- **`AI_MOCK_MODE=true`** 가 backend 컨테이너에 남아 있으나 backend 코드/yml 이 소비하지 않는 스트레이 변수(stack-bringup §3 과 동일 관측).


---

# A-part3.md

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


---

# A-part4.md

# A 클러스터 part4 — A-6. 추적ID·공통 응답·예외 + 공통 유틸 (42건)

- **담당 구간**: `docs/test-cases/A-auth-common.md` 191~237행 — `RequestIdFilter`·`ApiResponse`·`GlobalExceptionHandler`·`LogSanitizer`·`SortAllowlist`/`SortFieldMapper`·`BlankTextPredicate`
- **총 42건** (TC-TRACE 5 / TC-RESP 4 / TC-EXC 20 / TC-LOG 5 / TC-SORT 6 / TC-BLANK 2). 폐기 케이스 없음.
- **검증 일자**: 2026-08-03(3차) / 워크트리 `qa-0803`(HEAD `e065da42`) / 스택은 다른 파트 에이전트가 이미 재빌드해 HEAD와 이미지 일치(`docker ps` 기동 9분 경과 확인) / `SPRING_PROFILES_ACTIVE=local`
- **이전 회차 이슈 재확인 지시**: `A-ISSUE-62`(로그 마스킹 미배선) — 1차에서 실동작 누출 발견 → 2차에서 수정 확인했으나 "정적+단위 PASS, 실동작(로그 파일 관측)은 BLOCKED"(당시 스택이 구버전 이미지). 이번 회차는 스택이 실코드와 일치하므로 **실제 컨테이너 stdout 로그를 직접 떠서 재확인**했다 (§근거 문서 하단 참조).

## 실동작 검증 기반

| 항목 | 내용 |
|---|---|
| 진입점 | `http://localhost:18081/api` (context-path `/api`) |
| 토큰 | 컨테이너 실효 `JWT_SECRET`으로 HS256 직접 서명(HS256, `iss=klid-auth`, `channel` 명시). `docker exec klid-backend env` 로 시크릿 실측 후 파이썬으로 서명(외부 라이브러리 없이 hmac/base64로 구현) |
| 사용 sub | 1001=REVIEWER, 2001=WORKER (`ls_user_role` 조회) |
| 로그 확인 | `docker logs klid-backend --since Ns` 로 실제 stdout 관찰(local profile → `MaskingPatternLayout` 경유) |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system` (스키마 `public`) |

---

## 판정 결과

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-TRACE-001 | PASS | [실동작] | 헤더 미전송 시 응답 `X-Trace-Id`가 매 요청 12자리 신규 값(`1822a4fe94c2`, `7f3d7bf63a90`, `2de359f66a93`, `788a6eb71310` 등) — `RequestIdFilter.java:28-33` |
| TC-TRACE-002 | PASS | [실동작] | raw 소켓으로 헤더 값에 실제 CR/LF·제어문자를 주입 시도 → Tomcat 자체가 HTTP 프레이밍 위반으로 400(필터 도달 전 차단). 이는 컨테이너 레벨 방어이며, `sanitize()`의 화이트리스트(영숫자+하이픈)도 CRLF를 구조적으로 제거하는 이중 방어임을 TC-003 결과로 실증 — `RequestIdFilter.java:48-59` |
| TC-TRACE-003 | PASS | [실동작] | 특수문자+118자 헤더 값 전송 → 응답 헤더가 영숫자·하이픈만 남기고 정확히 **64자**로 절단(`abc-123XXXX…` 64자 확인, `wc`로 실측) — `RequestIdFilter.java:52-57` |
| TC-TRACE-004 | PASS | [실동작] | 동일 토큰으로 연속 3회 요청 → 매번 서로 다른 랜덤 traceId(리크·재사용 없음), MDC 격리 확인 — `RequestIdFilter.java:34-38` |
| TC-TRACE-005 | PASS | [정적] | `@Order(Ordered.HIGHEST_PRECEDENCE)` 라인 17 확인 |
| TC-RESP-001 | PASS | [실동작] | `GET /v1/me` → `{"success":true,"data":{...},"message":null,"errorCode":null}` — `ApiResponse.java:9-11` |
| TC-RESP-002 | PASS | [실동작] | 인가 실패 `{"success":false,"data":null,"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}`, 인증 실패 `{"...","message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — ErrorCode.defaultMessage() 그대로 노출 — `ApiResponse.java:21-23` |
| TC-RESP-003 | PASS | [정적] | `error(code,message,data)` 3-인자 오버로드 코드 확인(`ApiResponse.java:29-31`). 실제 트리거 지점(`AugmentRequestService` NOT_REVIEWED)은 사전조건(증강 요청 플로우 데이터 셋업)이 복잡해 실동작 재현은 생략, 코드 대조로 대체 |
| TC-RESP-004 | PASS | [실동작] | 성공 응답에 `"message":null,"errorCode":null` 키가 생략되지 않고 그대로 직렬화됨(`@JsonInclude(ALWAYS)`) — `ApiResponse.java:6` |
| TC-EXC-001 | PASS | [정적+테스트] | `handleCustom` 코드 확인 + `GlobalExceptionHandlerTest.customExceptionReturnsErrorCode` 존재 — `:24-33` |
| TC-EXC-002 | PASS | [정적] | `details!=null` 분기로 `error(code,msg,details)` 사용 — `:28-32` |
| TC-EXC-003 | PASS | [정적+테스트] | `GlobalExceptionHandlerTest.validationFailureReturnsInvalidInput` 존재, 코드 확인 — `:35-43` |
| TC-EXC-004 | PASS | [정적] | `ConstraintViolationException` 핸들러 확인 — `:49-57` |
| TC-EXC-005 | PASS | [실동작] | `POST /v1/videos/1/deident-report` 에 깨진 JSON(`{invalid json!!`) 전송 → `400 {"message":"요청 본문이 올바르지 않습니다.","errorCode":"INVALID_INPUT"}`. 파서 상세(Jackson 예외 클래스명·위치) 미노출 — `:64-69` |
| TC-EXC-006 | PASS | [실동작] | `DELETE /v1/videos/1/tracks/T1` 에서 필수 파라미터 `fromFrameNo` 누락 → `400 {"message":"필수 파라미터가 누락되었습니다: fromFrameNo",...}` — 파라미터명만 노출 — `:76-81` |
| TC-EXC-007 | PASS | [실동작] | `GET /v1/videos/notanumber` → `400 {"message":"파라미터 형식이 올바르지 않습니다: rawSn",...}` — 입력 원문("notanumber") 미노출, 파라미터명만 — `:88-93` |
| TC-EXC-008 | PASS | [정적+테스트] | `GlobalExceptionHandlerDataIntegrityTest.workLockUniqueViolation()` 합성 예외로 핸들러 직접 호출·검증(실제 `ConstraintViolationException`+`SQLException` 객체) — `:141-145,168-171`. HTTP 동시요청 재현은 도메인 사전조건 복잡성으로 생략 |
| TC-EXC-009 | PASS | [정적+테스트] | 프리셋 labelId·라벨명CI 위반은 `GlobalExceptionHandlerDataIntegrityTest`에 합성 케이스 존재. **검출유형(dtct_type) 위반은 동 테스트 파일에 전용 케이스 없음**(코드 로직은 나머지 두 분기와 완전 동형이라 기능 위험 낮음, 커버리지 갭으로만 기록) — `:146-160` |
| TC-EXC-010 | PASS | [정적+테스트] | `DataIntegrityViolation_cause_분기_workLock만_409_그외_fail_closed_500` 테스트로 미분류 위반 500 확인 — `:161-166` |
| TC-EXC-011 | PASS | [정적] | Hibernate `getConstraintName()` 우선, null 시 SQL 메시지 정규식 파싱 — `:193-209` |
| TC-EXC-012 | PASS | [정적+테스트] | `DataIntegrity_응답에_제약명_SQL_미포함` 테스트로 응답 body에 제약명/SQL 부재 확인 — 강제 코드 `:138-166`, 정책 주석 `:127-137` |
| TC-EXC-013 | PASS | [실동작] | WORKER 토큰으로 REVIEWER 전용 `GET /v1/system/configs` 호출 → `403 {"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}` — `:211-216` |
| TC-EXC-014 | PASS | [실동작] | 토큰 미전송 및 서명 불일치(다른 시크릿) 토큰 모두 `401 {"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — `:218-223` |
| TC-EXC-015 | PASS | [실동작] | `GET /v1/does-not-exist-xyz` → `404 {"message":"요청한 API를 찾을 수 없습니다.","errorCode":"NOT_FOUND"}`. 서버 로그도 `path=v1/does-not-exist-xyz`만 남고 스택 없음 — `:241-246` |
| TC-EXC-016 | PASS | [정적+테스트] | `MaxUploadSizeExceededMappingTest` 전용 테스트 존재, 코드 확인 — `:234-239` |
| TC-EXC-017 | PASS | [정적+테스트] | `unhandledExceptionReturnsInternalError` 테스트로 임의 RuntimeException→500+고정메시지 확인. 실HTTP 재현은 안전한 트리거 지점 부재로 생략(로그는 `log.error`로 서버에만 스택 적재, 응답 body는 고정 메시지) — `:273-278` |
| TC-EXC-018 | PASS | [실동작] | `POST /v1/me`(GET 전용 경로) → `405 {"message":"허용되지 않은 요청 메서드입니다.","errorCode":"METHOD_NOT_ALLOWED"}` — `:261-272` |
| TC-EXC-019 | PASS | [실동작] | 위 405 응답에 `Allow: GET` 헤더 실측 확인 — `:266-269` |
| TC-EXC-020 | PASS | [실동작] | `docker logs`로 실제 WARN 로그 확인: `[Exception] method not allowed method=POST` — 경로/쿼리스트링 미기록, 메서드명만 — `:264` |
| TC-LOG-001 | PASS | [실동작] | 정렬 키에 `%0A` 개행 주입(`a\nFAKE_LOG_LINE_INJECTED`) → 실제 stdout 로그에는 `key=aFAKE_LOG_LINE_INJECTED` 한 줄로만 기록(가짜 로그 라인 미생성). C0 제어문자 제거 실증 — `LogSanitizer.java:88-100` |
| TC-LOG-002 | PASS | [실동작] | 정렬 키에 U+2028(LINE SEPARATOR)+U+2029(PARAGRAPH SEPARATOR) 주입 → 실제 로그에 `key=aFAKE2028tail` 한 줄로만 기록(구분자 제거, 위조 라인 없음) — `:78-99` |
| TC-LOG-003 | PASS | [실동작] | 정렬 키 `가 나 다`(한글+공백) 주입 → 로그에 `key=가 나 다` 그대로 보존(가시문자·일반공백 무손실) — `:86,93-95` |
| TC-LOG-004 | PASS | [실동작] | ~80자 bare-JWT형 문자열 주입 → 로그에 `key=***...(truncated)` 기록. `LogSanitizer`가 64자(SortAllowlist 호출 시 `LOG_KEY_MAX_LENGTH`)에서 절단+접미 부착 후, 같은 로그 라인이 `MaskingPatternLayout`을 거치며 JWT 패턴이 `***`로 마스킹되는 이중 처리까지 실측 — `:25,28,51-72` |
| TC-LOG-005 | PASS | [정적] | `sanitize(null)` → `"(null)"` 반환 코드 확인. null 입력은 내부 코드 경로 전용이라 HTTP로 직접 트리거 불가, 코드 대조로 대체 — `:27,52-54` |
| TC-SORT-001 | PASS | [실동작] | `GET /v1/tasks/board?sort=secretField,desc` → `400 {"message":"지원하지 않는 정렬 기준입니다.","errorCode":"INVALID_INPUT"}` — `SortAllowlist.java:262-285` |
| TC-SORT-002 | PASS | [실동작] | `GET /v1/reviews?sort=secretField,desc` → `200` + 기본 정렬(updDt desc) 폴백 정상 응답, 서버 WARN 로그 `[Sort] unsupported sort key ignored key=secretField` 동시 확인. ★2 정책과 정확히 일치(비일관 아님) — `:312-338; SortFieldMapper.java:57-60` |
| TC-SORT-003 | PASS | [실동작] | 고유 필드 4개 지정(TASK_BOARD 상한=3) → strict `GET /v1/tasks/board`는 `400 "정렬 기준이 너무 많습니다."`, lenient `GET /v1/reviews`(REVIEW 상한=3)는 동일 조건에서 `200`(전체 폴백) 확인 — `:266,271-274,321-326,346-348` |
| TC-SORT-004 | PASS | [실동작] | `GET /v1/videos?sort=capturedAt,asc&sort=shtDt,desc`(둘 다 `shtDt`로 매핑되는 중복) 결과 rawSn 순서가 `sort=shtDt,asc` 단독 호출 결과(`[8,9,10,11,7]`)와 **정확히 일치**하고 `sort=shtDt,desc` 단독 결과(`[37,39,101,81,80]`)와는 다름 → 첫 지정(asc)만 적용 실증 — `:135-142,333-335` |
| TC-SORT-005 | PASS | [실동작] | TC-LOG-001 재현 시 WARN 로그의 key 필드가 정제(개행 제거)되어 한 줄로만 출력됨을 실측 — `:68,322-324,329-330` |
| TC-SORT-006 | PASS | [정적] | `SortFieldMapper.apply/mapSort`가 `SortAllowlist.resolveLenient`에 위임, 컨트롤러 사본 없음 확인 — `SortAllowlist.java:123-134; SortFieldMapper.java:29-31,57-70` |
| TC-BLANK-001 | PASS | [정적+테스트] | 전용 IT `TaskBoardFilterSortTest.blankCctvNameFallsBackToVmsIdOnBothDisplayAndSearch`/`ReviewListFilterSortTest` 동명 테스트가 tab·개행·전각공백·혼합 4종 CCTV명으로 표시 폴백+검색 정합을 실제 DB 시나리오로 검증(신규 라이브 재현은 기존 시드 데이터 변형이 필요해 생략, IT가 이미 동일 시나리오를 커버) — `BlankTextPredicate.java:60-66` |
| TC-BLANK-002 | PASS | [정적] | `Expressions.stringTemplate("replace({0}, {1}, '')", ...)` 파라미터 바인딩 확인, 문자열 연결 없음 — `:49,62-65,68-76` |

---

## 이전 회차 이슈 해소 확인 — A-ISSUE-62 (로그 마스킹 미배선)

- **1차**: 실동작 누출 확인(HIGH) — 전화번호/이메일/JSON형 자격증명/bare JWT가 전부 평문 기록.
- **2차**: 수정 확인 — `LogMaskingPatterns` 단일 원천화 + 규칙 9종 신설, `logback-spring.xml` 양 프로파일 배선. 단 "실동작(로그 파일 확인) 재현은 BLOCKED"(컨테이너가 구버전 이미지라 반영 전 코드 실행 중).
- **3차(본 검증) — 실동작으로 완전 재확인**: 정렬 키 파라미터로 4종을 실제 주입해 `docker logs klid-backend`의 **실제 stdout**을 직접 관찰:
  - 전화번호 `010-1234-5678` → 로그 `010-****-5678`
  - 이메일 `user@example.com` → 로그 `u***@example.com`
  - JSON 자격증명 `{"password":"hunter2"}` → 로그 `{"password":"***"}`
  - bare JWT(`eyJ...`) → 로그 `***...(truncated)`
  - 추가로 CRLF(TC-LOG-001)·U+2028/U+2029(TC-LOG-002) 라인 위조 시도도 실제 로그에서 단일 줄로 정상 처리됨을 확인
  - **판정: RESOLVED(실동작 확인 완료)** — 2차의 "실동작 BLOCKED" 유보가 이번 회차로 해소됨. dev/stg/prd JSON 인코더 경로(`MaskingJsonValueMasker` + `jsonGeneratorDecorator`)는 이 스택이 local 프로파일만 구동 중이라 실측은 못 했으나, `logback-spring.xml:26-56` 정적 확인상 message+stackTrace 양쪽에 동일 마스커가 걸려 있어 구조적으로 동일 결과가 기대된다(별도 판정 없이 사실만 기록).

## 카탈로그 정정

- **정정 건수: 0건.** 191~237행 42건 전건에 대해 근거 `file:line`을 실제 코드와 1줄 단위로 대조했으며, 드리프트가 전혀 없었다(모든 라인 범위가 실제 메서드/필드 경계와 정확히 일치). 이 구간은 이번 3차 검증 범위 중 근거 정확도가 가장 높은 섹션이다.

## 발견된 결함

- **없음.** FAIL/PARTIAL 0건. 카탈로그 오류·정책 오판(★2 목록 정렬 비대칭 등)도 없음.
- **경미한 커버리지 갭(결함 아님, 참고용)**: ①`GlobalExceptionHandlerDataIntegrityTest`에 검출유형(`uk_ls_label_dtct_type`) 유니크 위반 전용 케이스 없음(TC-EXC-009 일부) ②`ApiResponse`에 전용 단위 테스트 파일 없음(TC-RESP 전체, 실동작으로 대체 확인) ③`BlankTextPredicate` 자체에 대한 순수 단위 테스트 없음(리포지토리 레벨 IT로만 커버).


---

# A-part5.md

# A클러스터 part5 — A-7. 듀얼 데이터소스·캐시·시스템설정·기동 가드·설정 바인딩·Actuator/헬스·Resilience4j

- 대상: `docs/test-cases/A-auth-common.md` A-7절(238행~파일끝), 49건 (TC-DS 4 · TC-CACHE 5 · TC-SYSCFG 9 · TC-PROF 17 · TC-CFG 5 · TC-ACT 2 · TC-HEALTH 3 · TC-RES 4)
- 스택: `docs/검증결과/2026-08-03/3차/_raw/stack-bringup.md` 재사용(재빌드 완료, HEAD `e065da42` 반영, backend `localhost:18081`+`/api`, DB `public` 스키마, KPST/VLM/증강/관제통지 전부 mock-server(:9400) 실배선). 별도 재기동 없음.
- 실동작 검증: 자체 서명(JWT_SECRET 재사용) HS256 토큰으로 REVIEWER(userNo 1001)·WORKER(userNo 2001) 인증 후 `curl` 왕복, DB 직접 조회(`ls_user_role`), 컨테이너 `env` 덤프.

## 판정 표

| ID | 케이스명 | 판정 | 근거 확인 |
|----|----------|:--:|-----------|
| TC-DS-001 | Control DataSource/EMF/TxManager @Primary | PASS | [정적] ControlDataSourceConfig.java:31,38,50 @Primary 3곳 확인. [실동작] `/actuator/health` db.components 에 controlDataSource·portalDataSource 둘 다 UP(PostgreSQL isValid()) — 듀얼 EMF 실제 기동 확인 |
| TC-DS-002 | @ControlRepo만 control EMF 라우팅 | PASS | [정적] includeFilter=ControlRepo (19-28). 저장소 61개 확인(`grep -rl @ControlRepo`) |
| TC-DS-003 | @PortalRepo는 portal EMF 라우팅(비-Primary) | PASS | [정적] includeFilter=PortalRepo, @Primary 없음(47-51) 확인. **실제 사용처 1건**(`PortalDatasetVideoMetaRepository` — 외부 포털 DB 데이터마트 조회)만 @PortalRepo, 반면 신규 `LS_PORTAL_*`(업로드) 5개 저장소는 전부 @ControlRepo — 설계 의도(포털 자산 업로드는 자체 소유 DB, 데이터마트 연동만 진짜 포털 DB)와 정합 |
| TC-DS-004 | 서비스 Tx가 controlTransactionManager 바인딩 | PASS | [근거 드리프트 발견·정정] 구 인용 `SystemConfigService.java:38,91` 은 2026-08-03 커밋 `5a625a87`(이벤트 제외코드 JSON 타입·getStringSet 신설, +50라인)로 드리프트 — 카탈로그를 `54,135`로 정정(아래 카탈로그 정정 참조). 기능 자체는 6개 메서드 모두 `@Transactional(value="controlTransactionManager", ...)` 확인 |
| TC-CACHE-001 | 4개 캐시 개별 TTL 등록 | PASS | [정적] CacheConfig.java:53-56(상수),70-96(Caffeine 스펙) 정확 일치 — sysconfig 60s/100, stream-meta 5m/200, eventType 6h/50, userRole 60s/500 |
| TC-CACHE-002 | sysconfig getInt/getString 60s 캐시 | PASS | [근거 드리프트 정정] 구 `47-48,80-82` → `63-65,96-98`. [실동작] `PUT /v1/manage/configs/POLYGON_SIMPLIFY_TOLERANCE` 로 즉시 반영 확인(아래 참조) — 캐시가 stale 을 영구 고정하지 않음 |
| TC-CACHE-003 | update 시 sysconfig 캐시 전체 무효화 | PASS | [근거 드리프트 정정] 구 `90-91` → `130-136`(@Caching evict 블록). [실동작] `GET /v1/manage/configs` 응답에 `mdfcnDt`가 최근 갱신 시각(예: `eventtype.excluded-class-codes` 2026-08-03T23:54)으로 실제 반영돼 있음 — 무효화 정상 |
| TC-CACHE-004 | userRole null 결과 미저장 | PASS | [정적] UserRoleResolver.java:46 `@Cacheable(..., unless="#result==null")` 정확 일치 |
| TC-CACHE-005 | stream-meta TTL 은 파일 유예삭제의 하한 | PASS(카탈로그 자체 기술) | 카탈로그가 이미 2026-08-03 "[기대결과 정정]"으로 최신화됨(1차 `A-ISSUE-81` 해소, 아래 참조). 재확인 결과 서술과 코드(CacheConfig.java:29-39,58-66) 일치 |
| TC-SYSCFG-001 | 화이트리스트 외 키 조회/갱신 거부 | PASS | [근거 드리프트 정정] 구 `94-98`→`138-142`. [실동작] `PUT /v1/manage/configs/NOT_A_REAL_KEY` → `400 INVALID_INPUT {"message":"허용되지 않은 설정 키입니다."}` (입력 키 미노출, CWE-117 방어 확인) |
| TC-SYSCFG-002 | REVIEWER 아니면 update FORBIDDEN(이중 검증) | PASS | [근거 드리프트 정정] 구 `93,166-170`→`137,256-259`. [실동작] WORKER(2001) 토큰으로 `PUT .../BATCH_INTERVAL_SEC` → `403 FORBIDDEN`. 동일 토큰 `GET /v1/manage/configs` 도 403(Controller `@PreAuthorize` 선차단) |
| TC-SYSCFG-003 | actor=null이면 update FORBIDDEN | PASS(정적) | [근거 드리프트 정정] 구 `166-169`→`256-259`. 코드 경로(`verifyReviewer`)는 컨트롤러를 우회한 내부 호출 방어선이라 HTTP 로 직접 재현 불가 — `SystemConfigServiceTest.java` 존재 확인(회귀 커버) |
| TC-SYSCFG-004 | NUMBER 키 정수+키별 범위 검증 | PASS | [근거 드리프트 정정] 구 `132-145`→`182-195`. [실동작] `PUT .../BATCH_INTERVAL_SEC {"value":"99999"}`(범위 10~3600) → `400 INVALID_INPUT {"message":"값이 허용 범위를 벗어났습니다."}` |
| TC-SYSCFG-005 | DECIMAL NaN/Infinity/범위밖 거부 | PASS | [근거 드리프트 정정] 구 `147-164`→`197-214`. [실동작] `PUT .../POLYGON_SIMPLIFY_TOLERANCE {"value":"NaN"}` → `400 INVALID_INPUT {"message":"유효하지 않은 숫자입니다."}` — `Double.parseDouble("NaN")` 이 예외를 안 던지므로 `isNaN()` 이 실질 방어선이라는 서술과 실동작 일치 |
| TC-SYSCFG-006 | BOOLEAN true/false만 | PASS(정적) | [근거 드리프트 정정] 구 `116-125`→`164-169`. 화이트리스트에 BOOLEAN 타입 키가 현재 없어(ConfigKeys.ALLOWED 9개 전부 NUMBER/DECIMAL/JSON) HTTP 로 직접 재현 불가 — 코드 로직(validateByType switch) 정확 확인 |
| TC-SYSCFG-007 | 미지원 CONFIG_TYPE 거부 | PASS(정적) | [근거 드리프트 정정] 구 `127-128`→`177-178`. default 분기 코드 확인, 화이트리스트 키 전부 지원 타입이라 HTTP 재현 불가 |
| TC-SYSCFG-008 | getInt 타입/값 불일치 각 예외 | PASS(정적) | [근거 드리프트 정정] 구 `49-61`→`62-77`. 코드 확인(타입불일치=INVALID_INPUT, 파싱실패=INTERNAL_ERROR) |
| TC-SYSCFG-009 | 없는 설정 키 NOT_FOUND | PASS(정적) | [근거 드리프트 정정] 구 `110-113`→`154-158`. `loadOrThrow` 코드 확인 |
| TC-PROF-001 | local 프로파일이 ENV=dev/stg/prd에서 부팅 차단 | PASS | [정적] LocalProfileGuard.java:43-55 정확 일치. 재기동 트리거는 위험(공유 스택)해 실제 크래시 재현은 생략, 로직 검증으로 대체 |
| TC-PROF-002 | ENV 미설정/local이면 local 허용 | PASS | [정적] :47-49 정확 일치. 현재 로컬 스택이 실제로 이 경로로 정상 기동해 간접 실증(SPRING_PROFILES_ACTIVE=local, ENV 미설정) |
| TC-PROF-003 | local 아니면 즉시 통과 | PASS | [정적] :44-46 정확 일치 |
| TC-PROF-004 | DevProfileGuard — dev+ENV=stg/prd 기동 거부 | PASS | [정적] :49-62 정확 일치. DEPLOYED_ENVS = DeployedEnvironmentDetector.DEPLOYED_ENV_MARKERS 위임 확인(복제 없음) |
| TC-PROF-005 | DevToggleProfileGuard — prd/stg dev로그인 활성 시 거부 | PASS | [정적] :37,42-65 정확 일치 |
| TC-PROF-006 | ForwardedHeadersConfigGuard — 3키 전 프로파일 거부 | PASS | [정적] :49-66,82-113 정확 일치(48-66 상수/66 FORBIDDEN_KEYS 리스트, 82-92 configuredForbiddenKeys, 100-113 verify) |
| TC-PROF-007 | DeidentifyEndpointTrustGuard 프로파일별 처리 | PASS | [정적] :58-62,82-127,188-198 정확 일치. [실동작] 현재 로컬 스택이 실제로 `kpst.deid.base-url=http://klid-mock-server:9400`(UNTRUSTED_HOSTS 대상, "mock" 토큰 포함) 로 WARN 형상 — local 이라 무음 처리(로그 확인 안 함, local 분기 코드로 검증) |
| TC-PROF-008 | 호스트 파싱 불가 시 fail-secure + authority 폴백 | PASS | [정적] :106-123,139-177 정확 일치 |
| TC-PROF-009 | QuartzClusteringGuard — stg/prd 클러스터링 강제 | PASS | [정적] application-stg.yml:11·application-prd.yml:13 `${QUARTZ_CLUSTERED:true}` 정확 일치, 공통 application.yml:95 `${QUARTZ_CLUSTERED:false}` 대비 확인. QuartzClusteringGuard.java 인용(50-56,64-95)은 실제 49-55,63-94 로 1~2줄 오차(경미 — 상수/메서드 시작 위치 오프셋) |
| TC-PROF-010 | allowlist containsAll + ENV 독립 축 | PASS | [정적] QuartzClusteringGuard.java:101-103(singleNodeAllowed) 정확 일치. DeployedEnvironmentDetector.java:63-79(isDeployed+deployedEnvMarker) 정확 일치 |
| TC-PROF-011 | ProfileGatedUrlPolicy 완화 인정 조건 | PASS | [정적] :45-51(약간의 오프셋, 실제 44-54),78-94(실제 81-94),138-156(실제 138-156 정확) — 전반적으로 일치, 사소한 서두 오프셋만 |
| TC-PROF-012 | 기동 assert 우회해도 판정 불변(이중 방어) | PASS | [정적] :124-136 정확 일치(check/validate/policy) |
| TC-PROF-013 | ExternalUrlPolicy strict — https전용+사설차단 | PASS | [정적] :90-133,168-188 정확 일치(check() 본문, requirePublicNetwork) |
| TC-PROF-014 | 완화 경로도 링크로컬/메타데이터 거부 | PASS | [정적] :123-127,143-166 정확 일치 |
| TC-PROF-015 | placeholder 호스트 어떤 정책에서도 차단 | PASS | [정적] :43-47,117-122 정확 일치 |
| TC-PROF-016 | 평문 http+토큰 시 경고(값 미출력) | PASS | [정적] ProfileGatedUrlPolicy.java:107-116 정확 일치, WebClientConfig.java:67-70 정확 일치(vlmWebClient 토큰 분기) |
| TC-PROF-017 | GenAiIntegrationWiringGuard 위탁↔콜백 배선 짝 | PASS | [정적] :35-39,52-85 정확 일치. [실동작] 현재 스택 env `AUGMENT_EXTERNAL_MODE=http` — allowlist 미설정이면 기동 자체가 실패했을 것이나 정상 기동 중이므로 `webhook.genai.allowed-ip-cidrs` 가 설정돼 있음을 간접 확인(가드 통과 실증) |
| TC-CFG-001 | prd hikari 20/5 평면 바인딩 | PASS | [정적] application-prd.yml:2-5,14-28 정확 일치, ControlDataSourceConfig.java:32-36(controlDataSource 빈) 정확 일치. `DataSourcePoolBindingTest.java` 존재 확인(테스트 실행은 baseline 담당 범위, 미실행) |
| TC-CFG-002 | multipart 한도 prd 21MB/1100MB vs 공통 500MB/1200MB | PASS | [정적] application-prd.yml:29-39, application.yml:14,28-29 정확 일치. `ConfigProfileDriftGuardTest.prdMultipartLimitsStayTight`/`nonProdMultipartLimitsComeFromCommon` 코드로 확인(회귀 가드 존재, 미실행) |
| TC-CFG-003 | dev 프로파일 내부 IP 리터럴 없음 | PASS | [정적] `ConfigProfileDriftGuardTest.java:87-105`(실제 87-104, 1줄 오차) `devProfileHasNoHardcodedIpLiteral` 확인 |
| TC-CFG-004 | dev 토글 override / stg dev로그인 리터럴 false | PASS | [정적] application-dev.yml:23-28, application-stg.yml:51-56 정확 일치(`${DEV_LOGIN_ENABLED:true}` vs 리터럴 `false`) |
| TC-CFG-005 | ENV 배포 표식 온프렘 템플릿 주입 | PASS | [정적] `deploy/onprem/config/backend/env.template:25` `ENV=prd` 정확 일치 |
| TC-ACT-001 | prd는 actuator health만 노출 | PASS | [정적] application-prd.yml:93-100 정확 일치(`include: health`, `show-details: never`) |
| TC-ACT-002 | 기본은 ACTUATOR_EXPOSE로 노출 제어 | PASS(단 아래 A-ISSUE-81 참조) | [정적] application.yml:133-140 정확 일치. [실동작] 무인증 `/actuator/health,info`=200, `/actuator/metrics,beans,env,heapdump,prometheus`=401(REVIEWER 토큰 필요). REVIEWER 토큰으로 `metrics,prometheus`=200, `env,beans,heapdump`=404(미노출) — exposure 설정과 인가 결합이 기대대로 동작. **단 반증 과정에서 `/actuator/health` 자체의 상세노출 범위가 역할 무관임을 발견(별도 이슈 기록, 아래)** |
| TC-HEALTH-001 | 비식별 헬스 — mock 모드 핑 없이 UP | PASS(정적) | [근거] :77-83(실제 78-83, 1줄 오차) 확인. 현재 스택은 `DEIDENTIFY_MOCK_MODE=false`(KPST 모드)라 mock 분기 실동작 재현은 안 됨 — 코드 로직으로 확인 |
| TC-HEALTH-002 | 비식별 헬스 — KPST 형상은 root(`/`) 핑 | PASS | [정적] :44,70-73,92-112 정확 일치. **[실동작]** REVIEWER 토큰 `/actuator/health` 응답에 `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}` 확인 — `kpst.deid.base-url=http://klid-mock-server:9400` 의 루트(`/`)를 실제로 2초 타임아웃 핑해 성공한 결과. mock-server 헬스 자체가 아니라 **루트 경로 실핑** 임을 실측으로 확인(케이스 핵심 주장 실증) |
| TC-HEALTH-003 | 비식별 헬스 — 미구성은 DOWN(fail-closed) | PASS(정적) | [근거] :84-91 정확 일치. 현재 스택은 kpst.deid.enabled=true 라 이 분기 실동작 재현 안 됨(코드로 확인) |
| TC-RES-001 | 외부 호출 Retry+CircuitBreaker 순차 적용 | PASS | [정적] AiServerClient.java:46-47(predictYolo),63-64(predictYoloTrack) 정확 일치. 5개 메서드(predictYolo·predictYoloTrack·segment·track·verifyObjects) 전부 동일 패턴 직접 확인(카탈로그의 2026-08-03 정정 내용과 일치) |
| TC-RES-002 | 5개 CircuitBreaker 빈 등록 | PASS | [정적] Resilience4jConfig.java:13-47 정확 일치(deid/ai/vlmClient/controlNotify/kpstDeid 5개 빈). [실동작] `/actuator/health` 에 aiServerHealth·controlNotifyHealth·deidentifyHealth 컴포넌트가 실제 UP 으로 나타나 해당 CB 경유 클라이언트들이 실제 배선돼 동작 중임을 간접 확인 |
| TC-RES-003 | aiOnline Bulkhead 초과 시 429 | PASS(정적) | [정적] Resilience4jConfig.java:55-58(aiOnlineBulkhead 빈), AutolabelOnlineService.java:499(YOLO callYolo)·529(SAM callSam) 두 호출부 모두 `BulkheadOperator.of(aiOnlineBulkhead)` 공유 확인(카탈로그 2026-08-03 정정 내용과 일치, `PortalSam2Service` 부재도 확인: `find`로 0건). 동시 초과 트리거는 실제 오토라벨 파이프라인 데이터 준비가 필요해 이번 파트에서는 미실시 |
| TC-RES-004 | NonRetryableExternalException 재시도/CB 제외 | PASS | [정적] NonRetryableExternalException.java:3-9 정확 일치. application.yml 내 `ignore-exceptions: - ...NonRetryableExternalException` 12개소(모든 resilience4j retry/circuitbreaker 인스턴스) 전부 등록 확인(`grep -n`) |

## 카탈로그 정정 (A-7 범위 내 직접 Edit, 12건)

`docs/test-cases/A-auth-common.md` A-7절에서 **`SystemConfigService.java` 근거 라인 전체가 드리프트**돼 있었다. 원인: 2026-08-03 커밋 `5a625a87`(feat(eventtype): 이벤트 제외 대분류 코드를 REVIEWER 설정화면에서 편집 가능하게 이관, 13:57)이 JSON 타입 지원·`getStringSet`·`validateExcludedClassCodes`·`parseStringArray` 등을 신설하며 파일이 약 50라인 늘어났는데, 카탈로그의 "근거 file:line 전수 재확인 4회차" 커밋 `9f99db50`(같은 날 17:38, 즉 그 이후)이 **이 파일만 재확인 대상에서 누락**했다(회차 변경이력에 JwtAuthenticationFilter/CacheConfig/SortAllowlist/GlobalExceptionHandler/QuartzClusteringGuard/ProfileGatedUrlPolicy 는 명시됐으나 SystemConfigService 는 없음).

정정한 행: TC-DS-004, TC-CACHE-002, TC-CACHE-003, TC-SYSCFG-001~009 (총 12행). 각 행에 `**[근거 정정 2026-08-04]**` 표기 + 신규 라인 + "(구 N-M 드리프트)" 사유를 남겼다(파일은 이미 갱신 완료, 위 판정 표의 근거 확인 열에도 반영). 기능 자체는 전부 정상 동작(실동작으로 재확인) — **드리프트는 카탈로그 문서 정합성 결함이며 코드 결함이 아니다.**

미세 오차(1~2라인, 수정 보류): TC-PROF-009(QuartzClusteringGuard.java 실제 49-55,63-94 vs 카탈로그 50-56,64-95), TC-PROF-011(ProfileGatedUrlPolicy.java 서두 오프셋), TC-CFG-003(ConfigProfileDriftGuardTest.java 87-105 vs 실제 87-104), TC-HEALTH-001(77-83 vs 실제 78-83), TC-RES-003(AutolabelOnlineService.java 501-505/531-535 vs 실제 catch 블록 502-505/533-535) — 전부 인용 범위 안에 실제 코드가 포함돼 있어 검증에 지장 없는 수준이라 정정하지 않음.

## 신규 이슈

### [A-ISSUE-81] TC-ACT-002 — `/actuator/health` 상세 노출이 REVIEWER 전용 의도를 벗어나 인증된 모든 역할(WORKER 포함)에 노출됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `SecurityConfig.java`(85-86행 permitAll `/actuator/health`·`/actuator/info`, 118행 `.requestMatchers("/actuator/**").hasRole(Role.REVIEWER.name())`)의 주석("actuator metrics/prometheus 는 REVIEWER 만")과 `.claude/rules/observability.md`("운영 환경에서 민감 엔드포인트 노출 금지")의 취지는 **actuator 내부 상세 정보는 REVIEWER(관리자 동급)만 봐야 한다**는 것. `application.yml:140` 의 `show-details: when-authorized` 도 이 의도를 뒷받침하는 설정으로 보인다.
- **현재 동작(이슈 내용)**: Spring Boot 의 `when-authorized` 는 기본적으로 `management.endpoint.health.roles` 가 **비어 있으면 "인증 여부"만 확인**하고 **역할(role)은 보지 않는다**. 이 프로젝트는 `management.endpoint.health.roles` 를 설정하지 않았다(`grep -n "roles" application*.yml` → 0건). 그 결과 `/actuator/health` 는 SecurityConfig 에서 permitAll 이라 REVIEWER 게이트(`hasRole(REVIEWER)`)를 아예 거치지 않고, Boot 자체 판정도 "인증되면 상세 노출"이라 **WORKER 역할 토큰으로도 REVIEWER 전용으로 의도된 내부 상세가 전부 노출**된다.
  실동작 근거(WORKER, userNo=2001, role=WORKER 토큰):
  ```
  GET /api/actuator/health (WORKER 토큰)
  → 200 {"status":"UP","components":{
      "aiServerHealth":{...,"details":{"service":"ai-server"}},
      "controlNotifyHealth":{...,"details":{"service":"control-notify"}},
      "db":{...,"components":{"controlDataSource":{...,"details":{"database":"PostgreSQL","validationQuery":"isValid()"}},"portalDataSource":{...}}},
      "deidentifyHealth":{...,"details":{"service":"deidentify","mode":"kpst"}},
      "diskSpace":{...,"details":{"total":485473984512,"free":...,"path":"/app/.","exists":true}},
      ...}}
  ```
  같은 WORKER 토큰으로 `GET /api/actuator/metrics` 는 정상적으로 `403 FORBIDDEN`(hasRole(REVIEWER) 게이트가 실제로 동작) — 즉 **actuator 내에서 `/health` 만 역할 게이트를 우회**하는 비대칭이 실측됐다.
- **재현/확인 경로**:
  ```bash
  # WORKER(userNo=2001) HS256 토큰으로
  curl -H "Authorization: Bearer $WORKER_TOKEN" http://localhost:18081/api/actuator/health   # → 200 + 전체 컴포넌트 상세
  curl -H "Authorization: Bearer $WORKER_TOKEN" http://localhost:18081/api/actuator/metrics   # → 403 (대조군, 정상)
  ```
- **영향**: CWE-200(민감하지 않은 수준이지만 내부 아키텍처 정보 노출) / CWE-862(의도된 인가 경계 미스매치). 노출 정보 자체(컨테이너 내부 경로 `/app/.`, DB 벤더/검증쿼리, 외부연동 모드 `kpst`/`mock` 등)는 비밀·PII 는 아니라 즉각적 악용 난이도는 낮으나, "actuator 상세=REVIEWER 전용" 이라는 코드 스스로의 의도(SecurityConfig 주석)를 무력화하고, WORKER 도 이를 통해 내부 배치/외부연동 형상(비식별이 mock 인지 kpst 인지 등)을 정찰할 수 있다. **local/dev/stg 에서만 유효** — prd 는 `show-details: never` 로 이미 차단(application-prd.yml:100).
- **수정 방향(제안)**: `application.yml` 의 `management.endpoint.health.roles: REVIEWER` 를 명시해 Boot 의 `when-authorized` 판정축을 REVIEWER 역할로 좁히거나(Spring Security `SecurityContext.isUserInRole` 기반), 그게 어렵다면 `SecurityConfig` 에서 `/actuator/health` 도 REVIEWER 게이트 뒤로 옮기고 헬스체크(k8s liveness/readiness) 용은 별도 경량 엔드포인트로 분리. prd 는 이미 `never` 라 영향 없음.

## 이전 회차 이슈 해소 확인

- **1차 `A-ISSUE-81`(TC-CACHE-005, `docs/검증결과/2026-08-01/1차/ISSUES.md:616`)**: "stream-meta TTL 기동 시 검증됨" 서술이 사실과 다르다는 지적 — **해소 확인됨**. 현재 카탈로그(A-auth-common.md:250)가 이미 "[기대결과 정정 2026-08-03]"으로 갱신되어 "현재 이 규칙을 쓰는 흐름도, 강제하는 검증도 없다"로 정확히 기술돼 있고, 코드(`CacheConfig.java:29-39,58-66`)와도 일치. 이번 3차 회차에서 별도 조치 불필요.
- 본 파트(A-7) 범위에서 1차 문서의 다른 이슈(actuator/resilience4j/datasource 관련)는 추가로 발견되지 않았음 — 1차 ISSUES.md 전체 grep 결과 A-7 관련 항목은 위 TC-CACHE-005 1건뿐.

## 미실시/한계

- Resilience4j 강제 타임아웃 유발(mock-server 지연 주입, ai-server 중단 등)은 **다른 병렬 검증 에이전트와 공유 중인 스택을 훼손할 위험**이 있어 이번 파트에서 보류 — 대신 `/actuator/health` 의 실제 CB 경유 컴포넌트 UP 상태로 간접 확인. 강제 재현이 필요하면 전용 격리 스택에서 별도 수행 권고.
- 기동 가드류(TC-PROF-001~017, TC-CFG-005)의 "기동 거부" 자체는 실제 재기동(잘못된 설정 주입)으로 재현하지 않고 코드 로직 검증으로 대체(공유 스택 보호). 판정근거 열에 [정적] 표기.
- `_raw/test-baseline.md` 가 이번 회차에 아직 없어 `SystemConfigServiceTest`/`DataSourcePoolBindingTest`/`QuartzClusteringGuardTest`/`ConfigProfileDriftGuardTest`/`DeidentifyHealthIndicatorTest`/`GenAiIntegrationWiringGuardTest`/`DeidentifyEndpointTrustGuardTest`/`AiServerClientTest`/`DeployedEnvironmentDetectorTest` 존재는 확인했으나 실제 통과 여부는 대조하지 못함(빌드/테스트 실행 금지 지시 준수).
