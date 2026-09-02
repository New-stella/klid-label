# A 클러스터 part1 — A-1 인증(JWT) 34건 + A-2 인가 32건 = 66건

- **회차**: 2026-07-31 / 2-1차
- **대상 파일**: `docs/test-cases/A-auth-common.md` §A-1(line 15~53) · §A-2(line 54~92)
- **검증 방식**: 기동된 로컬 풀스택(backend `http://localhost:18081/api`)에 실제 HTTP 요청 + PostgreSQL 직접 조회 + 컨테이너 로그 관찰. 코드는 반증 대조용으로만 Read/Grep.
- **환경 전제**: `_raw/stack-bringup.md` 기준 5개 서비스 healthy, 외부 연동 전부 `klid-mock-server:9400` 지향, 내부 목모드 우회 없음. 프로파일 `local`, `authoring.dev.login.enabled=true`, DB 스키마 `public`.
- **baseline 대조**: `_raw/test-baseline.md` **미비**(파일 부재) → 자동테스트 통과 여부는 대조 불가. 테스트 파일·메서드 매핑만 기록.
- **토큰 발급 방법**: backend 컨테이너 실효 `JWT_SECRET` 을 읽어 HS256 JWT 를 직접 서명 발급(스크래치패드 스크립트). `/v1/dev/tokens` 를 쓰지 않고 클레임을 자유 조작해 **경계·null·예외 경로를 실제로 반증**했다.
- **DB 쓰기 테스트**: 병렬 에이전트 충돌 방지를 위해 신규 리소스는 `user_no=987654`(A-part1 전용 고유값) 사용. 검증 후 삭제 완료(잔여 0행 확인). `ls_user_role` 1002 행은 REVIEWER→WORKER→REVIEWER 로 즉시 원복 확인.

## 판정 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| A-1 인증(JWT) | 34 | 33 | 0 | 1 | 0 | 0 | 0 |
| A-2 인가 | 32 | 30 | 0 | 1 | 0 | 0 | 1 |
| **합계** | **66** | **63** | **0** | **2** | **0** | **0** | **1** |

> ⚠ PASS 63건은 **모두 반증 시도를 거친 뒤의 판정**이다. 케이스 표의 기대결과 자체는 대부분 충족하나,
> **기대결과가 다루지 않는 축**에서 신규 결함 4건(A-ISSUE-25~28)이 나왔다. 아래 이슈 상세 참조.

---

## 1. A-1. 인증 (JWT) — 34건

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|------|------|
| TC-AUTH-001 | PASS | [실동작] `GET /v1/me` + `Bearer <sub=1001,iss=klid-auth>` → 200 `{"userId":"1001","role":"REVIEWER","channel":"INTERNAL"}`. authority 실효는 `/v1/videos` 200 · `/v1/portal/**` 403 로 교차 확인 | 근거 `JwtAuthenticationFilter.java:56-111` 정합 |
| TC-AUTH-002 | PASS | [실동작] 헤더 없음 → `/v1/me` 401 `UNAUTHORIZED`(entryPoint), `/actuator/health` 200(permitAll 정상 통과) | 익명 통과 후 인가에서만 차단됨 확인 |
| TC-AUTH-003 | PASS | [실동작] `Authorization: Token <jwt>` → 401 / `Authorization: bearer <jwt>`(소문자) → 401. 대소문자 정확 매칭(`BEARER_PREFIX="Bearer "`) | |
| TC-AUTH-004 | PASS | [실동작] 다른 키로 서명한 토큰 → 401. 컨테이너 로그 `[Auth] jwt validation failed message=JWT signature does not match locally computed signature` | 컨텍스트 클리어 확인(후속 401) |
| TC-AUTH-005 | PASS | [실동작] `exp=now-3600` → 401. **경계 반증**: `exp=now`(정확히 현재) → 401(clock skew 0), `exp=now+1` → 200 | |
| TC-AUTH-006 | PASS | [실동작] `alg=none` + 서명부 공백 → 401 | |
| TC-AUTH-007 | PASS | [실동작] `Bearer garbage` → 401, `Bearer aaa.bbb`(2파트) → 401 | ⚠ 실패 로그가 공격자 제어 문자열을 무살균 출력 → **A-ISSUE-26** |
| TC-AUTH-008 | PASS | [실동작] `iss=evil` → 401 | `JwtIssuerValidator.java:22-27` 정합 |
| TC-AUTH-009 | PASS | [실동작] `iss` 클레임 부재 → 401 (`issuer==null` → false) | `JwtIssuerValidator.java:23-25` 정합 |
| TC-AUTH-010 | PASS | [실동작] `klid` · `klid-portal` · `klid-auth` 3종 모두 200. [정적] 코드 기본값 `JwtIssuerValidator.java:15` 와 동일 3종 | local 은 `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal` 로 명시 주입 — 실동작은 "설정값", 기본값 일치는 정적 대조 |
| TC-AUTH-011 | PASS | [정적] `JwtIssuerValidator.java:16-19` `map(trim).filter(!isEmpty)` → 공백/빈항목 제거. 전부 공백이면 `allowed=∅` 이고 `isAllowed` 는 `contains` 이므로 전건 false(fail-closed) | 재기동 금지 제약으로 실동작 불가. 테스트 `auth/jwt/JwtIssuerValidatorTest.java` 2건 커버 |
| TC-AUTH-012 | PASS | [실동작] `channel` 클레임 미포함 토큰 → `/v1/me` `"channel":"INTERNAL"` + `/v1/videos` 200 | |
| TC-AUTH-013 | PASS | [실동작] `channel=PORTAL, sub=1001`(LS 상 REVIEWER) → `role":"PORTAL_USER"` — **LS 미조회 확인**. `sub=9999`(LS 미등록) 도 `PORTAL_USER` → sub 값 무관 확인 | |
| TC-AUTH-014 | PASS | [실동작] `channel="X"` → 401. **반증 추가**: `channel="internal"`(소문자) → 401, `channel=""` → 401 — `Channel.valueOf` 대소문자 엄격, fail-closed | |
| TC-AUTH-015 | PASS | [실동작] `sub=2001` + JWT `role=REVIEWER` 클레임 위장 → 응답 `role":"WORKER"` (LS_USER_ROLE 값). JWT role 클레임 **미참조** 확인 | 권한 상승 반증 성공 |
| TC-AUTH-016 | PASS | [실동작] `sub="abc"` → `/v1/me` `role:null`, `/v1/videos` 403 | `parseUserNo` NumberFormatException 미전파 |
| TC-AUTH-017 | PASS | [실동작] `sub` 부재 → `userId:null,role:null` / `sub="   "`(공백) → `role:null` | |
| TC-AUTH-018 | PASS | [실동작] `sub=999999`(LS 매핑 없음) → `role:null`, `/v1/videos` 403 · `/v1/me` 200 → CHANNEL authority 만 부여됨 확인 | |
| TC-AUTH-019 | PASS | [실동작] role=null INTERNAL → `GET /v1/videos` **403**(fail-open 아님) / `GET /v1/me` 200 | A-ISSUE-02(구) 해소 재확인 |
| TC-AUTH-020 | PASS | [실동작] `name:"테스터검수"` → `/v1/me` `"name":"테스터검수"`. `name` 클레임 없는 토큰 → `"name":null`, 500 없음 | |
| TC-AUTH-021 | PASS | [실동작] role=null Bearer 로 `/v1/videos/4/stream` → **403**(STREAM_SIGNED 미부여). 반대로 서명 URL + nonce 쿠키(Authorization 없음) → 200. Bearer 경로가 STREAM_SIGNED 를 주지 않음 실증 | CWE-863 반증 통과 |
| TC-AUTH-022 | PASS | [정적] `JwtAuthenticationFilter.java:37-48` 3개 인자 각각 null 검사 → `IllegalArgumentException` | 실동작 불가(빈 생성 시점) |
| TC-AUTH-023 | PASS | [정적] `SecretKeyResolver.java:19-21` `bytes.length < 32` → `IllegalArgumentException`. 테스트 `common/security/SecretKeyResolverTest.java` 커버 | 재기동 금지로 실동작 불가 |
| TC-AUTH-024 | PASS | [정적+실동작] 96자 hex 시크릿으로 기동 중이며 서명 검증 정상 동작(전 케이스). `key` 는 final 필드 단일 인스턴스(`SecretKeyResolver.java:17-29`) | |
| TC-AUTH-025 | PASS | [정적] `UserRoleResolver.java:56-60` `catch (DataAccessException)` → `return null` + `log.warn("... userNo={}")` (토큰/PII 미출력). 테스트 `UserRoleResolverTest#DB조회_예외시_fail_closed_null` | DB 장애 유발은 공유 스택 훼손이라 미수행 |
| TC-AUTH-026 | PASS | [실동작] `ls_user_role(987654,'ADMIN')` INSERT → `/v1/me` `role:null`, `/v1/videos` **403**. enum 미존재 코드 fail-closed 확인 후 행 삭제 | `UserRoleResolver.java:73-79` 정합 |
| TC-AUTH-027 | PARTIAL | [정적] `UserRoleResolver.java:46` `@Cacheable(key="#userNo", unless=...)` — **`condition` 여전히 부재**. 메서드 본문 `:48-49` 는 null 반환. 프로덕션 유일 호출자(`JwtAuthenticationFilter.java:82-83`)가 null 을 선차단해 실경로 미도달 | **이월 A-ISSUE-01 미해소** |
| TC-AUTH-028 | PASS | [실동작] `987654` WORKER 로 warm → DB 직접 `UPDATE ... REVIEWER` → 1초 후 `/v1/me` 여전히 `WORKER` = **캐시 적중 실증**. `CacheConfig.java:88-92` max 500 / TTL 60s | 근거 드리프트 3줄(85-89 → 88-92) |
| TC-AUTH-029 | PASS | [실동작] 미배정 상태 `/me`(role null) → `INSERT WORKER` → **1초 후 즉시 `WORKER`** 반영. null 미캐시 확인 | |
| TC-AUTH-030 | PASS | [실동작] `PATCH /v1/users/1002 {"role":"WORKER"}` 후 즉시 `/me` → `WORKER`. `@CacheEvict` 키 표현식 적중 | |
| TC-AUTH-031 | PASS | [실동작] REVIEWER→WORKER 강등이 **다음 요청에 즉시** 반영, REVIEWER 복구도 즉시 반영. `UserService.java:141-143` AFTER_COMMIT evict | ⚠ 앱 경로 밖(DB 직접) 회수는 최대 60s stale → **A-ISSUE-28** |
| TC-AUTH-032 | PASS | [실동작] role=null 토큰의 `/v1/me` 가 `"role":null` 반환, NPE/500 없음 | `TokenClaims.java:13-15` 정합 |
| TC-COMMON-001 | PASS | [실동작] 미인증 `GET /v1/me` → 401 + `{"success":false,"errorCode":"UNAUTHORIZED"}` | `SecurityConfig.java:84,156` 정합 |
| TC-COMMON-002 | PASS | [실동작] `{"userId","name","role","channel"}` 4필드 반환. role/channel null 안전 확인(TC-AUTH-032) | `SessionController.java:35-45` 정합 |

### A-1 기존 자동테스트 매핑 (baseline 미비 — 통과 여부 미대조)

| 파일 | 메서드/DisplayName | 커버 TC |
|---|---|---|
| `common/security/JwtAuthenticationFilterTest.java` | 유효한_JWT로_요청시_LS역할_REVIEWER로_해석되어_보호엔드포인트_200 / JWT_없이_보호된_API_호출시_401_반환 / 유효하지않은_channel_클레임은_401 / LS에_관제역할코드_사용자는_enum불일치_403 / 공백_sub는_fail_closed_403 / 만료된_JWT로_요청시_401_반환 / 잘못된_서명의_JWT는_401_반환 | 001,002,005,014,017,026 |
| `common/security/UserRoleResolverTest.java` | INTERNAL_사용자_역할이_LS_USER_ROLE에서_해석된다 / LS_역할없는_사용자는_무권한_null / DB조회_예외시_fail_closed_null / 비정상_ROLE_CD는_무권한_null / userNo_null이면_무권한_null | 015,018,025,026,027 |
| `common/security/SecretKeyResolverTest.java` | JWT_시크릿이_32바이트_미만이면_IllegalArgumentException / 32바이트_이상이면_정상_생성 | 023,024 |
| `common/security/UserRoleCacheEvictIT.java` | 역할_변경후_AFTER_COMMIT_evict로_즉시_반영 | 030,031 |
| `auth/jwt/JwtIssuerValidatorTest.java` | allowed_issuers에_없는_iss는_검증_실패 / klid_및_klid_portal_iss는_검증_통과 | 008,010,011 |
| `auth/SecurityRoleResolutionPhase3Test.java` | 관제역할만_실린_JWT여도_LS매핑있으면_REVIEWER_인가 / LS_역할없는_INTERNAL_사용자는_보호엔드포인트_403 / 비숫자_sub는_fail_closed_403 / PORTAL_채널은_LS없이_PORTAL_USER로_인가 | 013,015,016,018,019 |
| `auth/InternalRoleGateMatcherTest.java` | role_null_INTERNAL_사용자는_내부_조회_API_403 / role_null_사용자도_me_조회는_가능_온보딩_보존 | 019 |
| **미커버(테스트 부재)** | exp 없는 토큰 수락 여부 / alg=none / issuer 전부 공백 fail-closed / `@Cacheable` null 키 프록시 경로 | 006,011,027 + A-ISSUE-25 |

---

## 2. A-2. 인가 (SecurityConfig · 채널 격리 · 화이트리스트) — 32건

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|------|------|
| TC-AUTHZ-001 | PASS | [실동작] WORKER `POST /v1/manage/labels` → 403 `FORBIDDEN` | GET 매처 뒤로 낙하 확인 |
| TC-AUTHZ-002 | PASS | [실동작] `GET /v1/manage/labels` — WORKER 200 / PORTAL 200 / role=null 200 / REVIEWER 200. `/v1/manage/labels/1/attrs` 도 200 | `authenticated()` — 채널·역할 무검사 실증. 문서상 **의도된 예외** |
| TC-AUTHZ-003 | PASS | [실동작] WORKER `GET /v1/system/settings` → 403 / REVIEWER → 404(핸들러 없음 = 인가는 통과) | |
| TC-AUTHZ-004 | PASS | [실동작] WORKER `GET /actuator/metrics` 403, REVIEWER 200. [정적] `application-prd.yml:96-97` `exposure.include: health` | |
| TC-AUTHZ-005 | PASS | [실동작] 익명 `GET /actuator/health` 200 · `/actuator/info` 200 · `/health` 200. 반증: `/actuator/prometheus` 401, `/actuator/env` 401 | `application.yml:140` show-details=when-authorized |
| TC-AUTHZ-006 | PASS | [실동작] REVIEWER `GET /v1/integration/anything` → 403 / 익명 → 401 | denyAll 실증 |
| TC-AUTHZ-007 | PASS | [실동작] REVIEWER `/v1/export-api/anything` → 403 / 익명 401 | |
| TC-AUTHZ-008 | PASS | [실동작] PORTAL 토큰 `GET /v1/portal/datamart/videos` → 200(실데이터 1건) | allOf AND 결합 |
| TC-AUTHZ-009 | PASS | [실동작] INTERNAL REVIEWER 로 동일 경로 → 403 | |
| TC-AUTHZ-010 | PASS | [실동작] PORTAL `GET /v1/videos` → 403. 의도된 예외 2곳 실측 확인: `GET /v1/manage/labels` 200 · `GET /v1/me` 200 | |
| TC-AUTHZ-011 | PASS | [실동작] PORTAL_USER `/v1/notices` 403 / WORKER 200 / role=null 403 | 매처 선순위 확인 |
| TC-AUTHZ-012 | PASS | [실동작] 익명 `POST /v1/dev/tokens` → **400 INVALID_INPUT**(= 인증 통과 후 컨트롤러 도달) → permitAll 매처 활성 확인 | local `authoring.dev.login.enabled=true` |
| TC-AUTHZ-013 | PASS | [정적] `SecurityConfig.java:52-53,103,116` 조건부 매처 + `/v1/dev/**` REVIEWER 가드 + `@ConditionalOnProperty` 3중. local 은 true 라 실동작 반증 불가 | **근거 드리프트**: `application.yml:437-441` → 실제 **424-428** |
| TC-AUTHZ-014 | PASS | [실동작] WORKER `POST /v1/dev/autolabel-test` → 403 / 익명 → 401 | |
| TC-AUTHZ-015 | PASS | [실동작] 익명 `POST /v1/auth/role-claim` → 401(permitAll 미적용). **우회 반증**: `/v1/auth/role-%63laim` 도 401(디코딩 후 매칭), `/v1/auth/ROLE-CLAIM`·`/v1/auth/role-claim/`·`%20` 접미 → 404 | 인코딩 우회 차단 확인 |
| TC-AUTHZ-016 | PASS | [실동작] 익명 `GET /v1/auth/whatever` · `/v1/portal/auth/whatever` → **404**(= 인가 통과 후 핸들러 부재) | permitAll 실증 |
| TC-AUTHZ-017 | PASS | [실동작] 익명 `/swagger-ui/index.html` 200 · `/v3/api-docs` 200 · `/swagger-ui.html` 302. [정적] `application-prd.yml:86-90` springdoc 비활성 | |
| TC-AUTHZ-018 | PASS | [실동작] 401 본문 `{"success":false,"data":null,"message":"인증이 필요합니다.","errorCode":"UNAUTHORIZED"}` — 스택/내부경로 없음 | |
| TC-AUTHZ-019 | PASS | [실동작] 403 본문 `errorCode":"FORBIDDEN"` | |
| TC-AUTHZ-020 | PASS | [실동작] `GET /v1/videos` 응답에 `Set-Cookie` 없음(JSESSIONID 미발급) | `/stream-url` 의 `klid_stream_nonce` 는 세션 쿠키 아님 |
| TC-AUTHZ-021 | PARTIAL | [실동작] 200/401/403 전 응답에 `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY` 부착 확인. **HSTS 는 평문 요청은 물론 `X-Forwarded-Proto: https` 를 붙여도 미부착**. `application.yml:106-125` 가 `forward-headers-strategy` 를 정책적으로 **금지**하므로 앱이 HTTPS 를 인지할 경로 자체가 없음 | **이월 A-ISSUE-06 / UNCERTAINTIES #28 미해소** |
| TC-AUTHZ-022 | PASS | [실동작] `-u user:pass`(Basic) → 401 JSON(WWW-Authenticate 없음) / `GET /login` → 401(폼 로그인 페이지 없음) / 익명 `POST /v1/notices` → 401(CSRF 403 아님) | |
| TC-AUTHZ-023 | PASS | [실동작] WORKER `GET /v1/manage/labels` 200 — 매처 순서 유지. **반증**: `HEAD/OPTIONS/PUT/DELETE/PATCH` 전부 403, `X-HTTP-Method-Override: GET` + POST → 403, `?_method=GET` + POST → 403 (메서드 오버라이드 우회 불가) | |
| TC-AUTHZ-024 | PASS | [실동작] role=null INTERNAL → `/v1/videos` 403 · `/v1/event-types` 403 · `/v1/videos/4` 403 | fail-open 해소 재확인 |
| TC-AUTHZ-025 | PASS | [실동작] role=null INTERNAL `/v1/me` 200(클레임 반향만) / PORTAL 채널 `/v1/me` 200 | 업무 데이터 미포함 확인 |
| TC-AUTHZ-026 | PASS | [실동작] WORKER 로 `GET /v1/videos/4/stream-url` → 서명 URL + `Set-Cookie: klid_stream_nonce=...; HttpOnly`. 그 URL 을 **Authorization 없이** 쿠키만 붙여 호출 → **200**(영상 바이트). 반증: 쿠키 제거 → 401, sig 1자 변조 → 401, 같은 서명으로 `rawSn=5` → 401 | A-ISSUE-11(nonce 쿠키 바인딩) 해소 확인 |
| TC-AUTHZ-027 | PASS | [실동작] 익명 `POST /v1/vlm/callback`·`/v1/genai/callback` → **429**(웹훅 필터의 rate limit 도달 = permitAll 통과 후 필터 단독 인증). 구 경로 `POST /v1/aug/callback`·`/v1/deidentify/result` → **401**(permitAll 대상 아님) | `WebhookProtectedPaths.java:50,57` 정합 |
| TC-CORS-001 | PASS | [정적] `SecurityConfig.java:185-187` blank 이면 `setAllowedOrigins(List.of())`. [실동작] 미등록 origin `http://evil.com` preflight → **403, `Access-Control-Allow-Origin` 헤더 없음**. `null` origin → 403, `http://localhost:13000.evil.com` → 401 | 재기동 금지로 "설정 공백" 전제 자체는 정적 |
| TC-CORS-002 | PASS | [정적] `SecurityConfig.java:180-184` `split(",")→trim→filter(!isEmpty)`. [실동작] local 다중 origin 목록 중 `http://localhost:13000` 정상 허용 | |
| TC-CORS-003 | PASS | [실동작] preflight 응답: `Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`(6종) · `Allow-Headers: authorization, content-type, x-trace-id`(요청분 에코, 선언 8종) · `Expose-Headers` 8종 · `Allow-Credentials: true`. `*` 미사용 확인 | 참고: `http://LOCALHOST:13000`(대문자 호스트)도 허용 — Spring 표준 대소문자 무시 매칭이며 RFC 상 정상, 결함 아님 |
| TC-ROLE-001 | 확인필요 | [정적] `RoleHierarchy.java:10-13` `fromHierarchy("")` 유지. **`authoringRoleHierarchy`/`RoleHierarchy` 참조 코드 main+test 통틀어 0건** (Grep 실측) → 사실상 no-op | **UNCERTAINTIES #6 미해소.** 빈 제거 vs 배선 정책 확정 필요 |
| TC-ROLE-002 | PASS | [정적] `Role.java:3-7`(3역할) · `Channel.java:3-6`(2채널). [실동작] `channel="X"`·`"internal"`·`""` 전부 401(valueOf IllegalArgument fail-closed), role 은 `ADMIN` 코드 삽입 시 null | |

### A-2 기존 자동테스트 매핑 (baseline 미비 — 통과 여부 미대조)

| 파일 | 메서드/DisplayName | 커버 TC |
|---|---|---|
| `auth/SecurityConfigChannelTest.java` | PORTAL_채널_토큰으로_내부_영상_API_호출시_403 / PORTAL_채널_토큰으로_manage_API_호출시_403(`/v1/manage/test` 스텁) / PORTAL_채널_토큰으로_포털_API_호출시_접근_허용 / INTERNAL_채널_토큰으로_포털_API_호출시_403 / channel_클레임_없는_토큰은_INTERNAL_간주 | 008,009,010,012(A-1) |
| `auth/SecurityConfigRoleTest.java` | INTERNAL_채널_REVIEWER_토큰으로_manage_API_접근_가능 / WORKER_토큰으로_manage_API_호출시_403 / PORTAL_USER_토큰으로_내부_API_호출시_403 / iss_불일치_토큰_401 | 001,009,010 |
| `auth/InternalRoleGateMatcherTest.java` | role_null_INTERNAL_사용자는_내부_조회_API_403 / WORKER_는_내부_조회_API_정상_통과_회귀0 / role_null_사용자도_me_조회는_가능 / role_null_사용자도_role_claim_엔드포인트에는_도달 | 024,025,015 |
| **미커버(테스트 부재)** | 경로 정규화·인코딩 우회(`//`,`/./`,`/../`,`;param`,`%XX`) 회귀 가드 / 메서드 오버라이드 우회 / CORS 미등록 origin 차단 / 보안 헤더 부착 | 010,015,021,023,CORS-001 |

---

## 3. 반증(adversarial) 시도 로그 — "코드가 있으니 PASS" 방지

전부 **fail-closed** 로 확인됐다(우회 0건). 근거로 남긴다.

### 3-1. 경로 정규화·인코딩 우회 (CWE-436 — 1차 최우선순위 #1 의 A-2 유사면 반증)

| 시도 | 대상 매처 | 결과 |
|---|---|---|
| `/v1/%73ystem/settings` (WORKER) | `/v1/system/**` REVIEWER | **403** — 퍼센트 디코딩 후 매칭됨(우회 실패) |
| `/v1/%69ntegration/x` (REVIEWER) | `/v1/integration/**` denyAll | **403** — 디코딩 후 denyAll 적중 |
| `/v1/%76ideos` (PORTAL) | `/v1/**` 채널게이트 | **403** |
| `/v1/auth/role-%63laim` (익명) | `/v1/auth/role-claim` authenticated | **401** — permitAll(`/v1/auth/**`) 로 흘러가지 않음 |
| `/v1//system/settings`, `/v1/system//settings`, `/v1/./system/settings`, `/v1/foo/../system/settings`, `/v1/system/%2e/settings` | 〃 | **401** 전부(비정규 경로는 매칭 실패 후 fail-closed) |
| `/v1/system/settings;a=b`, `/v1/videos;x=1`, `/v1/integration/x;y=1` | 〃 | **401** 전부(path parameter 우회 실패) |
| `/actuator/health/../metrics`, `/actuator//metrics`, `/actuator/./metrics`, `/actuator/health/x/../../metrics` (익명) | `/actuator/**` REVIEWER | **401** 전부 |
| `/v1/manage/labels/../../system/settings` (WORKER) | GET 매처 오인 유도 | **401** |
| `/v1/SYSTEM/settings`, `/v1/INTEGRATION/x` | 대문자 | **404**(MVC 매핑 부재. Security 매처는 대소문자 구분) |
| `http://localhost:18081/v1/videos` (context-path 생략) | — | **404** |
| `/api/api/v1/videos` (context-path 중복) | — | **401** |

> ⚠ 다만 비정규 경로 401 응답에는 보안 헤더가 붙지 않는다 → **A-ISSUE-27**(LOW).

### 3-2. 권한 상승·인증 우회

| 시도 | 결과 |
|---|---|
| JWT `role=REVIEWER` 클레임 위장(sub=2001, LS=WORKER) | `role=WORKER` 로 해석 — 상승 실패 |
| `channel=PORTAL` 로 내부 API 접근 | 403 |
| role=null 토큰으로 업무 조회 3경로 | 전부 403 |
| Bearer 토큰으로 `/stream` STREAM_SIGNED 획득 | role=null 토큰 403 → 획득 불가 |
| 서명 URL 을 nonce 쿠키 없이 재사용(URL 유출 시나리오) | 401 |
| 서명 URL 의 rawSn 만 4→5 로 변경 | 401 |
| `X-HTTP-Method-Override: GET` / `?_method=GET` 으로 POST→GET 매처 우회 | 403 |
| 역할 자가부여 `POST /v1/auth/role-claim {"role":"REVIEWER"}` (role=null 사용자) | 400(adminPassword 필수) + `RoleClaimService.java:223-225` 가 REVIEWER 를 허용목록에서 제외 → **A-ISSUE-17 해소 재확인** |

### 3-3. 토큰 클레임 경계

| 시도 | 결과 |
|---|---|
| `exp` 클레임 **없는** 토큰 | **200 (무기한 수락)** → **A-ISSUE-25** |
| `exp = now`(정확히 현재) | 401 (skew 0) |
| `exp = now+1` | 200 |
| `exp = now + 10년` | 200 (수명 상한 없음) → A-ISSUE-25 |
| `channel="internal"`(소문자) / `""` | 401 |
| `sub` 부재 / 공백 / 비숫자 / 미등록 | 전부 role=null + 업무 API 403 |

---

## 4. 이월 이슈 대조 결과

| 이월 항목 | 출처 | 이번 회차 상태 | 근거 |
|---|---|:--:|---|
| **1차 SUMMARY #1 — HMAC 서명 필터 URL 인코딩 우회(CWE-436, `A-13`)** | 1차 최우선순위 | **본 담당 범위 밖(A-4)** — 재현 미시도. 단 지시대로 **A-2 인가/화이트리스트의 유사 경로 정규화 우회를 전수 반증**했고 **우회 0건**(§3-1, 21변형). 특히 퍼센트 인코딩(`%73`,`%69`,`%76`,`%63`)은 Security 매처가 디코딩 후 판정해 REVIEWER/denyAll 게이트가 그대로 적중, 비정규 경로(`//`,`/./`,`/../`,`;param`)는 전부 401 fail-closed | §3-1 |
| **A-ISSUE-01** — `UserRoleResolver.resolve(null)` 프록시 경유 캐시키 NPE | 1차 (TC-AUTH-027) | **미해소(그대로 이월)** | `UserRoleResolver.java:46` 에 `condition` 여전히 부재. 실경로는 `JwtAuthenticationFilter.java:82-83` 선차단으로 미도달 — 실동작 상 노출 0(LOW 유지) |
| **UNCERTAINTIES #6** — RoleHierarchy 빈 계층(`fromHierarchy("")`) | 1차 | **미해소(참조 0건 no-op 유지)** | `grep -r "RoleHierarchy\|authoringRoleHierarchy"` main+test 전체 → 정의 파일 외 **0건** |
| **UNCERTAINTIES #28 / A-ISSUE-06** — HSTS 가 앱·edge 어디서도 미부여 | 1차 신규(07-30) | **미해소 확인 + 근거 강화** | 평문 요청·`X-Forwarded-Proto: https` 위조 요청 모두 `Strict-Transport-Security` 헤더 없음. `application.yml:106-125` 가 `forward-headers-strategy` 를 **정책적으로 금지**(ForwardedHeadersConfigGuard 가 기동 차단)하므로 앱 레이어에서 HSTS 를 붙일 방법 자체가 없음 → **edge(nginx/Caddy) 부여가 유일 해법** |
| **A-ISSUE-02(구)** — `/v1/**` 채널만 요구하던 fail-open | 07-30 해소 주장 | **해소 재확인** | role=null INTERNAL 3경로 전부 403 (TC-AUTHZ-024) |
| **A-ISSUE-11(구)** — 서명 스트림 nonce 쿠키 바인딩 | 07-30 해소 주장 | **해소 재확인** | 쿠키 없는 서명 URL → 401 (TC-AUTHZ-026) |
| **A-ISSUE-17(구)** — REVIEWER 자가부여 | 07-30 해소 주장 | **해소 재확인** | `RoleClaimService.java:223-225` 허용목록 WORKER 단일 |

---

## 5. 근거 드리프트 (카탈로그 정합성 결함)

| TC | 카탈로그 표기 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-AUTH-028 | `CacheConfig.java:85-89` | `CacheConfig.java:88-92` | userRole 캐시 블록 3줄 하향 이동 |
| TC-AUTHZ-013 | `application.yml:437-441` | `application.yml:424-428` | `authoring.dev.login.enabled` 블록 13줄 상향 이동 |
| TC-AUTH-023 | `SecretKeyResolver.java:18-22` | `SecretKeyResolver.java:19-21` | 범위가 1줄씩 넓음(허용 오차) |
| TC-AUTHZ-004 | `application-prd.yml:93-100` | `application-prd.yml:96-97` | 범위 내 포함(허용 오차) |

> 나머지 62건의 `file:line` 은 전부 정합(Read 대조 완료).

---

# 이슈 상세 (A-ISSUE-25 ~ A-ISSUE-28 + 이월 A-ISSUE-01/06)

> ID 는 담당 범위(01~30) 중 **1차에서 이미 사용된 01~24 와 충돌하지 않도록 25번부터** 부여했다.
> 이월 이슈는 원래 ID 를 유지한다.

### [A-ISSUE-25] TC-AUTH-005 (인접) — `exp` 클레임 없는 JWT 를 무기한 수락하고 토큰 최대 수명 상한이 없음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: JWT 검증기는 `exp` 를 **필수**로 요구해야 한다(`security.md` — "만료 시간(`exp`) 필수 설정", OWASP A07:2025 / CWE-613). 저작도구는 토큰을 발급하지 않고 관제·포털이 발급한 토큰을 **인계받아 검증만** 하므로, 발급측 실수·설정 오류·유출 토큰에 대한 최후 방어선이 검증기다. 만료 없는 토큰이 통과하면 **로그아웃·권한 회수·유출 대응이 원천적으로 불가능**하다.
- **현재 동작(이슈 내용)**: `JwtAuthenticationFilter.java:59-62` 의 파서에 `requireExpiration()`(또는 파싱 후 `exp` null 검사)이 없다.
  ```java
  Jws<Claims> jws = Jwts.parser()
          .verifyWith(keyResolver.resolve())
          .build()
          .parseSignedClaims(token);   // exp 없으면 만료 검사 자체가 수행되지 않음
  ```
  `TokenClaims.java:5` 는 `exp` 를 nullable 로 받고(`body.getExpiration()==null ? null : ...`), 이후 어디서도 null 을 거부하지 않는다. `grep -rn "requireExpiration\|clockSkew" backend/src/main/java` → **0건**. `Jwts.parser` 사용처는 이 1곳뿐이라 다른 방어 지점도 없다.
  **실측**: `exp` 없는 HS256 토큰(sub=1001) → `GET /v1/me` **200** `{"userId":"1001","role":"REVIEWER"}`. `exp = now+10년` 토큰도 **200**(수명 상한 없음).
- **재현/확인 경로**:
  ```bash
  # payload 에서 exp 만 제거한 HS256 토큰 (JWT_SECRET 은 backend 컨테이너 실효값)
  TOK=<{"sub":"1001","iss":"klid-auth","iat":<now>} 로 HS256 서명한 JWT>
  curl -s -H "Authorization: Bearer $TOK" http://localhost:18081/api/v1/me
  # → 200 {"success":true,"data":{"userId":"1001","role":"REVIEWER","channel":"INTERNAL"}}
  ```
- **영향**: 보안 — CWE-613(Insufficient Session Expiration), OWASP A07:2025. 발급측이 `exp` 를 빠뜨리거나 과도한 TTL 로 발급하면 저작도구 쪽에서 **영구 유효 세션**이 성립한다. 유출 시 리보케이션 수단이 없다(블랙리스트도 미구현). `security.md` 의 "만료 시간(exp) 필수 설정" 규칙 위반.
- **수정 방향(제안)**: `JwtAuthenticationFilter.java:59-62` 파서 빌드에 `.require("exp", ...)` 대신 파싱 후 `body.getExpiration() == null` 이면 **거부(clearContext + return)** 하는 명시 분기를 추가(JJWT 0.12 는 `requireExpiration()` 미제공). 추가로 `authoring.jwt.max-lifetime-seconds`(예: 86400) 를 두고 `exp - iat` 또는 `exp - now` 가 상한을 넘으면 거부하는 최대 수명 캡을 검토. 회귀 가드로 `JwtAuthenticationFilterTest` 에 "exp_없는_토큰은_401" / "과도한_수명_토큰은_401" 2건 추가.

---

### [A-ISSUE-26] TC-AUTH-007 — JWT 검증 실패 로그가 공격자 제어 문자열을 무살균 출력(Log Injection, CWE-117)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 사용자 입력이 로그에 실리면 개행(`\n`,`\r`) 및 유니코드 라인 구분자를 제거해야 한다(`security.md` — Log Injection CWE-117, `logging.md`). 이 저장소는 이미 공용 유틸 `common/util/LogSanitizer.java` 를 두고 **63곳**에서 적용하고 있으므로 인증 필터만 예외일 이유가 없다.
- **현재 동작(이슈 내용)**: `JwtAuthenticationFilter.java:112-115` 가 예외 메시지를 그대로 넘긴다.
  ```java
  } catch (JwtException | IllegalArgumentException e) {
      log.debug("[Auth] jwt validation failed message={}", e.getMessage());   // 무살균
      SecurityContextHolder.clearContext();
  }
  ```
  JJWT 의 `UnsupportedJwtException` 메시지는 **JWT 헤더의 `alg` 값을 그대로 인용**하므로 공격자가 개행을 넣으면 가짜 로그 라인을 주입할 수 있다.
  **실측 로그(klid-backend 컨테이너)**:
  ```
  2026-07-31 03:17:27.451 [http-nio-8080-exec-1] DEBUG [5dbf7cf6769a] k.c.c.a.c.s.JwtAuthenticationFilter - [Auth] jwt validation failed message=Unsupported signature algorithm 'HS256
  2026-07-31 03:00:00 ERROR [Auth] APART1-INJECT-ALG admin bypass'
  ```
  두 번째 줄이 **정상 로그 라인 형식으로 위조**되어 삽입됐다(타임스탬프·레벨·로거명까지 공격자 지정).
- **재현/확인 경로**:
  ```bash
  # header JSON: {"alg":"HS256\n2026-07-31 03:00:00 ERROR [Auth] APART1-INJECT-ALG admin bypass","typ":"JWT"}
  # payload: {"sub":"1001"}, signature: 임의값
  curl -s -H "Authorization: Bearer <위 헤더로 만든 토큰>" http://localhost:18081/api/v1/me   # → 401
  docker compose logs klid-backend --since 20s | grep -A2 "jwt validation failed"
  ```
- **영향**: 보안 — CWE-117(Improper Output Neutralization for Logs). 감사 로그 위조·로그 분석 도구 오염·SIEM 오탐 유발. 노출 범위는 `kr.co.cudo.authoring` 로거가 **DEBUG 인 환경**으로 한정된다(`application-local.yml:171` DEBUG, `application-dev.yml:77` DEBUG / `application-stg.yml:85` INFO, `application-prd.yml:105` INFO) → **운영 노출은 없고 local·dev 노출**. 서명 검증이 메시지 인용보다 먼저 실패하는 경로(서명 불일치)에서는 재현되지 않고, **서명 검증 이전에 실패하는 헤더 파싱 경로**에서만 재현된다.
- **수정 방향(제안)**: `JwtAuthenticationFilter.java:113` 을 `log.debug("[Auth] jwt validation failed message={}", LogSanitizer.sanitize(e.getMessage()))` 로 교체(다른 63곳과 동일 패턴). 더 안전한 대안은 예외 메시지 자체를 버리고 `e.getClass().getSimpleName()` 만 남기는 것 — 검증 실패 사유 구분에는 예외 타입으로 충분하다. 회귀 가드로 `JwtAuthenticationFilterTest` 에 "alg_헤더_개행_주입시_로그에_개행_미출력" 추가.

---

### [A-ISSUE-27] TC-AUTHZ-021 — 비정규 경로 요청의 401 응답에 보안 응답 헤더(nosniff/X-Frame-Options)가 부착되지 않음
- **심각도**: LOW
- **기대 동작(기대효과)**: `SecurityConfig.java:61-67` 이 선언한 `X-Content-Type-Options: nosniff` · `X-Frame-Options: DENY` 는 **모든 응답**에 붙어야 한다(TC-AUTHZ-021 기대결과 "전 응답"). 에러 응답도 브라우저가 렌더링하므로 예외가 아니다.
- **현재 동작(이슈 내용)**: 정상 경로 응답에는 두 헤더가 붙지만, `//`·`/./`·`/../`·`;param` 같은 **비정규 경로** 요청의 401 응답에는 붙지 않는다(HeaderWriterFilter 가 실행되지 않는 에러 디스패치 경로로 추정).
  ```
  # /api/v1//videos (유효 REVIEWER Bearer 첨부)
  HTTP/1.1 401
  X-Trace-Id: b894e2c0a47e
  Content-Type: application/json;charset=UTF-8
  Content-Length: 95
  # ← X-Content-Type-Options / X-Frame-Options 없음

  # /api/v1/videos (정상)
  HTTP/1.1 200
  X-Content-Type-Options: nosniff
  X-Frame-Options: DENY
  ```
- **재현/확인 경로**:
  ```bash
  curl -sD- -o /dev/null --path-as-is -H "Authorization: Bearer <REVIEWER>" \
    "http://localhost:18081/api/v1//videos" | grep -iE "x-content-type|x-frame"   # 출력 없음
  curl -sD- -o /dev/null -H "Authorization: Bearer <REVIEWER>" \
    "http://localhost:18081/api/v1/videos"  | grep -iE "x-content-type|x-frame"   # 두 줄 출력
  ```
- **영향**: 보안(방어심층) — 인가 우회는 **아니다**(해당 요청은 전부 401/403 fail-closed 로 확인, §3-1). 다만 JSON 에러 본문에 대한 MIME sniffing / 프레이밍 방어가 이 경로에서만 빠져 정책이 비대칭이다. OWASP A02:2025(Security Misconfiguration) 범주.
- **수정 방향(제안)**: `RequestIdFilter` 같은 **보안 체인 앞단의 서블릿 필터**에서 두 헤더를 무조건 세팅하거나, `ErrorPage`/`ErrorController` 응답 경로에 동일 헤더를 부여하는 `OncePerRequestFilter` 를 추가한다. 어느 쪽이든 edge(nginx/Caddy)에서 일괄 부여하면 A-ISSUE-06(HSTS)와 함께 해결되므로 **edge 헤더 정책 수립 과제로 묶는 것을 권장**.

---

### [A-ISSUE-28] TC-AUTH-028 / TC-AUTH-031 — 앱 경로 밖에서 역할이 회수되면 최대 60초간 구 권한이 유지됨
- **심각도**: LOW
- **기대 동작(기대효과)**: 권한 강등·회수는 가급적 즉시 반영돼야 한다(CWE-613). 앱 내부 경로(`PATCH /v1/users/{userNo}`, `POST /v1/auth/role-claim`)는 AFTER_COMMIT evict 로 이 요건을 충족한다.
- **현재 동작(이슈 내용)**: `userRole` 캐시는 `CacheConfig.java:88-92` 의 `expireAfterWrite(60s)` 이고 evict 는 `UserService.java:141-143` · `RoleClaimService.java:168-179` **두 앱 경로에서만** 호출된다. DB 를 직접 변경하거나(운영 조치·데이터 패치) 향후 다른 시스템이 `LS_USER_ROLE` 을 쓰면 **최대 60초** 구 역할이 유효하다.
  **실측**: `ls_user_role(987654)` 를 WORKER 로 만들어 캐시를 데운 뒤 ①`UPDATE ... REVIEWER` → 1초 후 `/v1/me` 여전히 `WORKER` ②`DELETE FROM ls_user_role WHERE user_no=987654` → 1초 후 `/v1/me` **여전히 `WORKER`**(행이 없는데 권한 유지).
- **재현/확인 경로**:
  ```sql
  INSERT INTO ls_user_role(user_no,role_cd,reg_dt) VALUES(987654,'WORKER',now());
  ```
  ```bash
  curl -s -H "Authorization: Bearer <sub=987654>" http://localhost:18081/api/v1/me   # role=WORKER (캐시 적재)
  ```
  ```sql
  DELETE FROM ls_user_role WHERE user_no=987654;
  ```
  ```bash
  curl -s -H "Authorization: Bearer <sub=987654>" http://localhost:18081/api/v1/me   # 여전히 role=WORKER (≤60s)
  ```
- **영향**: 보안 — 권한 회수 지연 창(≤60초). 현재 `LS_USER_ROLE` 쓰기 주체는 저작도구 앱뿐이라(`grep LsUserRoleRepository` → `UserService`·`RoleClaimService`·`UserRoleResolver` 3곳) **실제 노출은 운영자의 DB 직접 조치 시나리오로 한정** → LOW. 다만 CLAUDE.md 상 사용자 계정은 관제서버(MNG_*) 소유이므로, 향후 관제 측이 역할을 회수하는 연동이 생기면 심각도가 올라간다.
- **수정 방향(제안)**: (a) 현 설계를 유지하되 **"LS_USER_ROLE 은 앱 경로로만 변경한다"** 를 운영 규약으로 문서화(`docs/v2-wiki`)하고 TTL 60s 를 SLA 로 명시, 또는 (b) TTL 을 15~30s 로 낮춰 지연 창 축소, 또는 (c) 강등·삭제 이벤트를 감지하는 짧은 주기 리프레시 잡을 두는 방안 검토. **구현은 하지 않는다.**

---

### [A-ISSUE-01] TC-AUTH-027 — `UserRoleResolver.resolve(null)` 프록시 경유 캐시 키 NPE (이월 · 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: `resolve(null)` 은 어떤 호출 경로에서도 `null`(무권한)을 반환해야 한다. 캐시 프록시가 먼저 NPE 를 던지면 fail-closed 가 아니라 **500** 이 된다.
- **현재 동작(이슈 내용)**: `UserRoleResolver.java:46` 의 `@Cacheable` 에 `condition` 이 **여전히 없다**.
  ```java
  @Cacheable(cacheNames = CacheConfig.CACHE_USER_ROLE, key = "#userNo", unless = "#result == null")
  public Role resolve(Long userNo) {
      if (userNo == null) { return null; }   // ← 본문은 안전하나 프록시가 먼저 실행됨
  ```
  Caffeine 캐시는 null 키를 허용하지 않으므로 프록시 경유 시 캐시 조회 단계에서 NPE 가 난다. 프로덕션 유일 호출자 `JwtAuthenticationFilter.java:82-83` 이 `parseUserNo` 로 null 을 **선차단**하므로 실경로에는 도달하지 않는다(이번 회차에도 `sub` 부재/공백/비숫자 토큰 전건이 401·403 으로 정상 처리됨 — 500 관측 0건).
- **재현/확인 경로**: HTTP 로는 재현 불가(선차단). 스프링 컨텍스트에서 프록시 빈에 `resolve(null)` 직접 호출 시 재현.
- **영향**: 기능(내구성) — 향후 새 호출자가 null 을 그대로 넘기면 401 대신 500 이 나가고 스택트레이스 유출 위험(CWE-209)이 생긴다. 현재 노출 0.
- **수정 방향(제안)**: `@Cacheable(..., condition = "#userNo != null")` 추가. 회귀 가드는 프록시 경유 테스트(`@SpringBootTest` + 캐시 활성)로 1건.

---

### [A-ISSUE-06] TC-AUTHZ-021 — HSTS 가 앱·edge 어디에서도 부여되지 않음 (이월 · 미해소 · UNCERTAINTIES #28)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: HTTPS 로 서비스되는 저작도구 응답에 `Strict-Transport-Security` 가 붙어 downgrade/SSL-strip 을 막아야 한다(`security.md` — Security 응답 헤더 필수).
- **현재 동작(이슈 내용)**: `SecurityConfig.java:64-66` 이 HSTS 를 선언하지만 Spring Security 의 `SecureRequestMatcher` 때문에 `request.isSecure()==true` 일 때만 부착된다. 그런데 `application.yml:106-125` 는 `server.forward-headers-strategy` 를 **정책적으로 금지**하고 `ForwardedHeadersConfigGuard` 가 설정 시 **기동을 거부**한다(웹훅 IP 신뢰축 오염 방지 — 의도된 설계). 결과적으로 프록시 뒤 앱은 요청이 HTTPS 였다는 사실을 알 수 없어 **HSTS 를 붙일 경로 자체가 없다**.
  **실측**: 평문 요청 → HSTS 없음. `X-Forwarded-Proto: https` 를 붙인 요청 → **여전히 HSTS 없음**(nosniff·X-Frame-Options 는 부착됨).
- **재현/확인 경로**:
  ```bash
  curl -sD- -o /dev/null -H "X-Forwarded-Proto: https" -H "Authorization: Bearer <REVIEWER>" \
    http://localhost:18081/api/v1/videos | grep -i strict-transport   # 출력 없음
  ```
- **영향**: 보안 — OWASP A02:2025. HSTS 부재 시 최초 접속 downgrade·중간자 공격 창이 남는다. `application.yml:124-125` 주석도 "edge 부여는 **후속 과제**(A-ISSUE-06 연관)" 로 인정하고 있다.
- **수정 방향(제안)**: 앱에서 해결하려 하지 말 것(`forward-headers-strategy` 재도입은 웹훅 IP 신뢰축을 깨뜨리므로 **금지**). **운영 edge(nginx/Caddy)에서 `Strict-Transport-Security: max-age=31536000; includeSubDomains` 를 부여**하고, 배포 설정(`deploy/onprem/**`)에 그 설정을 고정한 뒤 배포 점검 체크리스트에 편입한다. A-ISSUE-27(에러 응답 보안 헤더 누락)과 **같은 edge 헤더 정책 과제로 묶어 처리**하는 것을 권장. **구현은 하지 않는다.**
