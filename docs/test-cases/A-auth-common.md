# A. 인증/권한 + 공통 인프라 — 테스트 케이스

> **235 케이스**(실측) · 계층: unit / integration / security · [← README](README.md)
>
> 섹션별: A-1 34 · A-2 32 · A-3 12 · A-4 46 · A-5 21 · A-6 39 · A-7 49 (회차 2 실측 232 + 신규 3 = **235**)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 125 | 77 | 1 | 1차 검증(2026-07-25) 이후 Phase 1~10 반영. **웹훅 인증 우회(CRITICAL) 차단 후 계약 전면 교체** — 서명 필수 경로 0개(`/v1/aug/callback` 제거), 무서명 2경로(`/v1/vlm/callback`·`/v1/genai/callback`) + PathPattern allowlist + 2단 게이트 + nonce replay 차단(409) + 노드 공유 rate limit. **인가 표면 상향** — `/v1/**` 가 채널만 요구 → 채널+역할(REVIEWER\|WORKER\|STREAM_SIGNED) 결합(A-ISSUE-02 해소), `/v1/me` 온보딩 예외. **서명 스트림 nonce 쿠키 바인딩**(A-ISSUE-11 해소). **REVIEWER 자가부여 제거**(A-ISSUE-17 해소) + role-claim 계정·전역 2축 공유 rate limit. **기동 fail-closed 가드 7종 신설**. 공통 유틸 `LogSanitizer`(유니코드 라인 구분자 보강)·`SortAllowlist`/`SortFieldMapper`·`BlankTextPredicate` 케이스 신설. 설정 바인딩 정합(prd hikari 20/5·multipart 21MB/1100MB) 회귀 가드 반영. 비식별 헬스체크 판정축(KPST root 핑) 반영. 기존 156행 중 **30행은 현재 코드와 재대조 후 무수정 유지**(정정 125 + 폐기 1 + 유지 30). |
| 2 | 2026-08-03 | 40 | 3 | 0 | **근거 `file:line` 전수 재확인 회차** — 232행(헤더 표기 233 은 실측 오차) 전수 대조. 라인 드리프트 37건 정정(`JwtAuthenticationFilter`/`CacheConfig`/`SortAllowlist`/`GlobalExceptionHandler`/`QuartzClusteringGuard`/`ProfileGatedUrlPolicy` 등 Map 신설·핸들러 추가로 인한 위치 이동) / 기대결과 실질 정정 3건(`TC-CACHE-005` 해상도 백필 폐지로 "기동 시 검증됨" 폐기, `TC-RES-001` Retry+CB 적용 메서드 4→5개, `TC-RES-003` 존재하지 않는 `PortalSam2Service` 서술을 `AutolabelOnlineService` 공유 `aiOnline` Bulkhead로 정정) / 폐기 0건 / UNRESOLVED 0건. |

> **판정 기준**: 프로젝트 루트 `CLAUDE.md` 의 ★ 구속 정책 + 현재 코드. 폐기 케이스는 행을 남기고 `~~취소선~~` + `**[폐기 2026-07-30]**` 로 표기한다(1차 검증 결과 문서가 TC ID 를 참조).

## A-1. 인증 (JWT)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| TC-AUTH-001 | 유효한 INTERNAL JWT로 인증 컨텍스트 설정 | 서명/issuer/exp 유효, sub=숫자 userNo, LS_USER_ROLE 매핑 존재 | `Authorization: Bearer <valid>` | SecurityContext에 TokenClaims(sub·role·channel=INTERNAL) + `ROLE_*`,`CHANNEL_INTERNAL` authority | integration | H | JwtAuthenticationFilter.java:56-121 |
| TC-AUTH-002 | Authorization 헤더 없으면 익명 통과 | 필터 등록됨 | 헤더 없음 | 컨텍스트 미설정, 보호 리소스는 후속 401 | unit | H | JwtAuthenticationFilter.java:55-56,132 |
| TC-AUTH-003 | Bearer 접두사 없으면 파싱 미시도 | - | `Authorization: Token abc` / `bearer x`(소문자) | 파싱 스킵(대소문자 정확 매칭) | unit | M | JwtAuthenticationFilter.java:28,56 |
| TC-AUTH-004 | 서명 불일치 토큰 거부+컨텍스트 클리어 | 다른 키 서명 | 변조 서명 | JwtException catch → clearContext | security | H | JwtAuthenticationFilter.java:127-130 |
| TC-AUTH-005 | 만료 토큰(exp 과거) 거부 | exp<now, clock skew 미설정 | 만료 JWT | ExpiredJwtException → 401 | security | H | JwtAuthenticationFilter.java:59-62,127-130 |
| TC-AUTH-006 | alg=none 토큰 거부 | unsigned JWT | parseSignedClaims | UnsupportedJwtException → 무권한 | security | H | JwtAuthenticationFilter.java:59-62,127-130 |
| TC-AUTH-007 | malformed JWT 거부 | 임의 문자열 | `Bearer garbage` | Malformed catch → 무권한 | security | H | JwtAuthenticationFilter.java:127-130 |
| TC-AUTH-008 | 미허용 issuer 거부 | iss=미허용 | iss="evil" | isAllowed=false → clearContext + early return | security | H | JwtIssuerValidator.java:22-27; JwtAuthenticationFilter.java:65-70 |
| TC-AUTH-009 | issuer null/blank 거부 | iss 부재 | iss=null | isAllowed=false | security | H | JwtIssuerValidator.java:23-25 |
| TC-AUTH-010 | 허용 issuer 기본값(klid/klid-portal/klid-auth) | 설정 미지정 | 각 기본 issuer | 모두 허용 | unit | M | JwtIssuerValidator.java:15 |
| TC-AUTH-011 | issuer 목록 공백/빈항목 제거 | `" klid , , x "` | 설정 주입 | {klid,x}. 전부 공백이면 allowed=∅ → 전건 거부(fail-closed) | unit | L | JwtIssuerValidator.java:16-19 |
| TC-AUTH-012 | channel 부재 시 INTERNAL 기본 | channel 부재 | 내부 토큰 | channel=INTERNAL | unit | H | JwtAuthenticationFilter.java:86-87 |
| TC-AUTH-013 | channel=PORTAL은 role=PORTAL_USER 고정 | channel=PORTAL | 포털 토큰 | LS 미조회, role=PORTAL_USER (sub 값 무관) | unit | H | JwtAuthenticationFilter.java:95-100 |
| TC-AUTH-014 | 알 수 없는 channel 거부 | channel="X" | 잘못된 채널 | Channel.valueOf throws → catch → 무권한 | security | M | JwtAuthenticationFilter.java:87,127-130 |
| TC-AUTH-015 | INTERNAL 인가 역할은 LS_USER_ROLE에서 | JWT role=관제역할, LS=WORKER | 관제 토큰 | authority=ROLE_WORKER (JWT role 클레임 미참조) | integration | H | JwtAuthenticationFilter.java:89-100; UserRoleResolver.java:46-61 |
| TC-AUTH-016 | INTERNAL sub 비숫자면 무권한 | sub="abc" | 비숫자 sub | parseUserNo=null → role=null | security | H | JwtAuthenticationFilter.java:96-97,140-148 |
| TC-AUTH-017 | INTERNAL sub null/blank 무권한 | sub 부재 | - | role=null | unit | M | JwtAuthenticationFilter.java:140-143 |
| TC-AUTH-018 | LS_USER_ROLE 매핑 없으면 role=null | findByUserNo 빈 결과 | 매핑 없는 userNo | ROLE 미부여, CHANNEL만 | integration | H | UserRoleResolver.java:52-55 |
| TC-AUTH-019 | role=null 토큰은 CHANNEL authority만 → 업무 API 403 | role=null | INTERNAL 토큰으로 `GET /v1/videos` | `CHANNEL_INTERNAL` 만 부여되고 `/v1/**` 는 채널+역할 결합을 요구하므로 **403**(A-ISSUE-02 해소). `/v1/me` 만 예외 통과 | integration | H | JwtAuthenticationFilter.java:114-118; SecurityConfig.java:84,147-152 |
| TC-AUTH-020 | name 클레임 request attribute 전달 | name="홍길동" | 유효 토큰 | AUTH_NAME_ATTR 세팅 → /me 노출. name null 이면 attribute 미설정(NPE 없음) | unit | L | JwtAuthenticationFilter.java:123-126 |
| TC-AUTH-021 | 사용자 토큰은 STREAM_SIGNED authority 획득 불가 | 유효 토큰(sub 위장) | Bearer 경로 | ROLE_*/CHANNEL_*만 부여 → `/stream` 인가 획득 불가(CWE-863) | security | H | JwtAuthenticationFilter.java:109-118; StreamSignatureFilter.java:55-63 |
| TC-AUTH-022 | 생성자 인자 null이면 필터 생성 거부(fail-closed) | 인자 null | new Filter(null,...) | IllegalArgumentException | unit | M | JwtAuthenticationFilter.java:37-48 |
| TC-AUTH-023 | JWT 시크릿 32B 미만이면 부팅 거부 | secret<32B | 기동 | IllegalArgumentException → 빈 생성 실패 | security | H | SecretKeyResolver.java:18-22 |
| TC-AUTH-024 | 32B 이상 시크릿 HS256 키 생성 | secret≥32B | 기동 | 동일 SecretKey 인스턴스 반환 | unit | M | SecretKeyResolver.java:17-29 |
| TC-AUTH-025 | UserRoleResolver DB 장애 시 null(fail-closed) | DataAccessException | resolve | catch→null, 로그에 userNo만 | security | H | UserRoleResolver.java:56-60 |
| TC-AUTH-026 | ROLE_CD가 enum에 없으면 null | ROLE_CD="ADMIN" | resolve | IllegalArgument catch→null | security | H | UserRoleResolver.java:72-80 |
| TC-AUTH-027 | userNo=null 이면 무권한 — 단 프록시 경유는 캐시키 NPE | - | resolve(null) | 메서드 본문은 null 반환. **`@Cacheable(key="#userNo")` 에 `condition` 이 없어 프록시 경유 시 캐시 키 null 로 NPE**(A-ISSUE-01 미해소). 프로덕션 유일 호출자는 filter 가 null 을 선차단해 실경로 미도달 | unit | M | UserRoleResolver.java:46-50; JwtAuthenticationFilter.java:96-97 |
| TC-AUTH-028 | 역할 조회 userNo 캐시 | 반복 호출 | resolve x2 | `userRole` 캐시(max 500, TTL 60s) 적중 | integration | M | CacheConfig.java:88-92; UserRoleResolver.java:46 |
| TC-AUTH-029 | null 결과 캐시 미저장 | 미배정→부여 | resolve,부여,resolve | `unless="#result==null"` → 부여 후 즉시 반영 | integration | H | UserRoleResolver.java:46 |
| TC-AUTH-030 | evict(userNo) 캐시 무효화 | 캐시 존재 | evict 후 resolve | `@CacheEvict` 키 표현식이 `@Cacheable` 과 동일 → DB 재조회 | integration | M | UserRoleResolver.java:67-70 |
| TC-AUTH-031 | 역할 변경 후 강등 즉시 반영 | REVIEWER 캐시 | 변경+AFTER_COMMIT evict | 다음 요청 새 역할 | integration | H | UserRoleResolver.java:63-70; RoleClaimService.java:167-179 |
| TC-AUTH-032 | roleName() role null이면 NPE 없이 null | role=null | roleName() | null | unit | M | TokenClaims.java:13-15 |
| TC-COMMON-001 | /me 미인증 시 401 | 미인증 | GET /v1/me | `/v1/me` 는 `.authenticated()` → 익명은 entryPoint 401. 컨트롤러 도달 시에도 UNAUTHORIZED | integration | H | SecurityConfig.java:84,156; SessionController.java:36-39 |
| TC-COMMON-002 | /me sub/name/role/channel 반환 | 유효 토큰 | GET /v1/me | MeResponse (role/channel null 안전) | integration | M | SessionController.java:35-45 |

## A-2. 인가 (SecurityConfig · 채널 격리 · 화이트리스트)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| TC-AUTHZ-001 | REVIEWER 아니면 /v1/manage/** 쓰기 403 | WORKER | POST /v1/manage/labels | 403 (GET 전용 매처 뒤 `/v1/manage/**` hasRole(REVIEWER) 로 낙하) | security | H | SecurityConfig.java:126-127 |
| TC-AUTHZ-002 | /v1/manage/labels GET은 임의 역할 허용 | 인증 임의 역할 | GET /v1/manage/labels(/**) | 200 (`authenticated()` — 채널·역할 무검사) | integration | M | SecurityConfig.java:126 |
| TC-AUTHZ-003 | /v1/system/**은 REVIEWER만 | WORKER | GET /v1/system/... | 403 | security | H | SecurityConfig.java:128 |
| TC-AUTHZ-004 | /actuator/**(health/info 제외) REVIEWER만 | WORKER | GET /actuator/metrics | 403. prd 는 exposure=health 로 노출 자체 차단 | security | H | SecurityConfig.java:118; application-prd.yml:93-100 |
| TC-AUTHZ-005 | /health, /actuator/health(/**), /actuator/info permitAll | 미인증 | GET /actuator/health | 200. show-details=when-authorized(공통)/never(prd) | integration | M | SecurityConfig.java:85-90; application.yml:138-140 |
| TC-AUTHZ-006 | /v1/integration/** 무조건 거부(deprecated) | REVIEWER여도 | 임의 요청 | denyAll → 인증자 403 / 익명 401 | security | H | SecurityConfig.java:120 |
| TC-AUTHZ-007 | /v1/export-api/** 무조건 거부 | 임의 인증 | 임의 요청 | denyAll → 403 | security | H | SecurityConfig.java:121 |
| TC-AUTHZ-008 | 포털 API는 PORTAL 채널+역할 둘 다 필요 | CHANNEL_PORTAL+ROLE_PORTAL_USER | GET /v1/portal/** | 200 (`allOf` AND 결합) | security | H | SecurityConfig.java:134-135 |
| TC-AUTHZ-009 | INTERNAL 채널로 포털 API 접근 거부 | CHANNEL_INTERNAL | GET /v1/portal/** | 403 | security | H | SecurityConfig.java:134-135 |
| TC-AUTHZ-010 | PORTAL 채널로 내부 /v1/** 접근 거부 | CHANNEL_PORTAL | GET /v1/videos | 403. **의도된 예외 2곳**: `GET /v1/manage/labels/**`(TC-AUTHZ-002)·`/v1/me`(TC-AUTHZ-025)는 `authenticated()` 라 채널 무관 통과 | security | H | SecurityConfig.java:147-152,126,84 |
| TC-AUTHZ-011 | /v1/notices REVIEWER/WORKER만, PORTAL_USER 차단 | PORTAL_USER | GET /v1/notices | 403 (`/v1/**` 매처보다 먼저 배치) | security | H | SecurityConfig.java:132 |
| TC-AUTHZ-012 | /v1/dev/tokens dev-login 활성 시만 permitAll | authoring.dev.login.enabled=true | POST /v1/dev/tokens | permitAll 매처 추가(REVIEWER 가드보다 앞). local/dev 만 활성 | integration | M | SecurityConfig.java:52-53,103-113 |
| TC-AUTHZ-013 | dev-login 비활성 시 /v1/dev/** 가드(fail-closed) | enabled=false(기본) | POST /v1/dev/tokens | 매처 부재 + `/v1/dev/**` REVIEWER 가드 + `@ConditionalOnProperty` 빈 부재 3중 차단 → 401/403 | security | H | SecurityConfig.java:52-53,103,116; application.yml:456-458 |
| TC-AUTHZ-014 | /v1/dev/**(tokens 외) REVIEWER만 | WORKER | POST /v1/dev/autolabel-test | 403 | security | H | SecurityConfig.java:116 |
| TC-AUTHZ-015 | /v1/auth/role-claim authenticated만 | 미인증 | POST /v1/auth/role-claim | 401 (permitAll 매처보다 **먼저** 등록) | security | H | SecurityConfig.java:71,90 |
| TC-AUTHZ-016 | /v1/auth/**, /v1/portal/auth/** permitAll | 미인증 | 진입 endpoint | 통과 (`/v1/portal/**` 채널 매처보다 앞) | integration | M | SecurityConfig.java:90 |
| TC-AUTHZ-017 | swagger/api-docs permitAll(비운영 한정) | 미인증 | GET /swagger-ui/** | 통과. **prd 는 springdoc 비활성이라 404** | integration | L | SecurityConfig.java:87-90; application-prd.yml:86-90 |
| TC-AUTHZ-018 | 인증 실패 시 표준 ApiResponse(UNAUTHORIZED) JSON | 미인증 보호리소스 | 요청 | 401 + errorCode=UNAUTHORIZED, 스택/내부경로 미포함 | integration | H | SecurityConfig.java:156,239-244 |
| TC-AUTHZ-019 | 인가 실패 시 표준 ApiResponse(FORBIDDEN) JSON | 역할 부족 | 요청 | 403 + errorCode=FORBIDDEN | integration | H | SecurityConfig.java:157,239-244 |
| TC-AUTHZ-020 | 세션 STATELESS(세션 미생성) | 임의 요청 | - | JSESSIONID 미발급 | integration | M | SecurityConfig.java:60 |
| TC-AUTHZ-021 | 보안 헤더 — nosniff/X-Frame-Options 는 전 응답, **HSTS 는 HTTPS 요청에만** | 임의 응답 | HTTP 요청 / HTTPS(`.secure(true)`) 요청 | `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY` 는 항상. `Strict-Transport-Security` 는 Spring Security `SecureRequestMatcher` 때문에 **HTTPS 요청에만** 부착(A-ISSUE-06). ⚠ 운영 edge(nginx/Caddy)에서도 HSTS 미부여 상태 — 잔여 이슈 | security | M | SecurityConfig.java:61-67 |
| TC-AUTHZ-022 | CSRF/formLogin/httpBasic 비활성 | - | - | 해당 필터 부재 | integration | L | SecurityConfig.java:56,58,59 |
| TC-AUTHZ-023 | 매처 순서상 manage/labels GET이 REVIEWER 매처보다 먼저 | WORKER | GET /v1/manage/labels | 200(순서 역전 회귀 방지) | security | M | SecurityConfig.java:126-127 |
| TC-AUTHZ-024 | 역할 미배정(role=null) INTERNAL 은 업무 조회 API 전건 403 (신규) | LS_USER_ROLE 미등록 userNo 의 유효 토큰 | GET /v1/videos · /v1/event-types · /v1/videos/{rawSn} | **403** — `/v1/**` 가 `CHANNEL_INTERNAL` **AND** (`ROLE_REVIEWER`\|`ROLE_WORKER`\|`STREAM_SIGNED`) 결합을 요구(구 fail-open 해소) | security | H | SecurityConfig.java:147-152 |
| TC-AUTHZ-025 | /v1/me 는 역할 미배정도 200 — 온보딩 예외 (신규) | role=null INTERNAL / PORTAL 채널 | GET /v1/me | 200. 응답은 본인 토큰 클레임 반향(sub/role/channel)뿐이며 업무 데이터 없음. **의도된 예외** — /me 응답에 업무 데이터를 추가하면 채널 게이트 재검토 필요 | security | H | SecurityConfig.java:72-84 |
| TC-AUTHZ-026 | STREAM_SIGNED 단독 authority 로 /v1/** 통과 (신규) | 유효 서명+nonce 쿠키(ROLE_* 없음) | GET /v1/videos/{rawSn}/stream | `CHANNEL_INTERNAL`+`STREAM_SIGNED` 조합이 `anyOf` 를 만족해 인가 통과. 영상 단위 인가는 컨트롤러 `LabelAccessGuard` 가 별도 강제 | security | H | SecurityConfig.java:147-152; StreamSignatureFilter.java:136-138 |
| TC-AUTHZ-027 | 웹훅 permitAll 경로는 무서명 2종뿐 (신규) | - | POST /v1/vlm/callback · /v1/genai/callback | permitAll(필터가 단독 인증). **구 `/v1/aug/callback`·`/v1/deidentify/result` 매처는 제거됨** — 해당 경로 요청은 permitAll 대상이 아님 | security | H | SecurityConfig.java:100-102; WebhookProtectedPaths.java:50,57 |

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| TC-CORS-001 | CORS origin 미설정 시 외부 origin 차단 | allowed-origins="" | Origin: evil.com | `setAllowedOrigins(List.of())` → 허용 안 됨 | security | H | SecurityConfig.java:178-187 |
| TC-CORS-002 | 콤마 origin 목록 trim 허용 | "a.com, b.com " | 설정 | {a.com,b.com} | unit | M | SecurityConfig.java:180-184 |
| TC-CORS-003 | 허용 메서드/헤더/노출헤더/credentials 구성 | - | preflight | 메서드 6종·요청헤더 8종·노출헤더 8종 명시 + allowCredentials=true (`*` 미사용) | integration | M | SecurityConfig.java:188-200 |
| TC-ROLE-001 | RoleHierarchy 빈 계층(상속 없음) | - | 기동 | `fromHierarchy("")` — 상속 0건이며 이 빈을 참조하는 코드도 0건(사실상 no-op). 명시 역할 나열로만 매칭 (→ UNCERTAINTIES #6 미해소) | unit | M | RoleHierarchy.java:10-13 |
| TC-ROLE-002 | Role/Channel enum 정의값만 | - | valueOf | 3역할/2채널, 그외 IllegalArgument | unit | L | Role.java:3-7; Channel.java:3-6 |

## A-3. 스트림 서명 필터 (StreamSignatureFilter · StreamNonceCookie)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| TC-STREAM-001 | 유효 서명 + 유효 nonce 쿠키로 접근 시 CHANNEL_INTERNAL+STREAM_SIGNED | 발급 URL + 발급 시 내려간 HttpOnly nonce 쿠키 보유 | GET stream?exp&u&sig (+쿠키) | 컨텍스트 설정(authority 2종), principal=**실제 발급자 sub** + 재조회 역할 | security | H | StreamSignatureFilter.java:126-148 |
| TC-STREAM-002 | 서명 없으면 미개입 → 401 | sig/exp 부재 | 스트림 요청 | 컨텍스트 미설정 → 보안체인 401 | security | H | StreamSignatureFilter.java:108-114 |
| TC-STREAM-003 | 만료/변조 서명 거부+sig 전문 로그 미노출 | verify=false | 잘못된 sig | rawSn만 debug 로깅(CWE-532) | security | H | StreamSignatureFilter.java:149-152 |
| TC-STREAM-004 | u(userNo) 변조 시 서명 불일치 거부 | 타 사용자 u | URL 의 u 만 변경 | verify=false → 401. ⚠ **"URL 전체 재사용 차단"은 u 가 아니라 nonce 쿠키가 담당**(TC-STREAM-008) — 구 주석/기대문구 정정(A-ISSUE-11) | security | H | StreamSignatureFilter.java:124-132 |
| TC-STREAM-005 | 선인증 컨텍스트 있으면 서명 검증 스킵 | JWT 선인증 | 스트림 요청 | 서명 검증 스킵, 통과. 필터는 `addFilterAfter(JwtAuthenticationFilter)` 로 AnonymousAuthenticationFilter 앞 | unit | M | StreamSignatureFilter.java:95-100; SecurityConfig.java:166 |
| TC-STREAM-006 | GET 아니거나 스트림 경로 아니면 미동작 | POST/다른 경로 | - | shouldNotFilter=true | unit | M | StreamSignatureFilter.java:84-89 |
| TC-STREAM-007 | rawSn 정규식 1~18자리 숫자만 | 19자리/비숫자 | 경로 | 미매칭 → 패스스루 → 401(fail-closed) | unit | L | StreamSignatureFilter.java:50,116-122 |
| TC-STREAM-008 | **쿠키 없이 URL 전체 재사용 시 401** (신규) | 발급 URL(exp·u·sig) 전체 확보, 쿠키 없음 | 다른 클라이언트에서 GET stream | `nonceCookie.read()` = null → 서명 재구성 실패 → verify=false → **401**. URL 에는 nonce 가 실리지 않는다 | security | H | StreamSignatureFilter.java:127-132; StreamNonceCookie.java:115-126 |
| TC-STREAM-009 | 봉인 없는/타 subject 쿠키는 채택 불가(nonce fixation 차단) (신규) | 공격자가 심은 임의 32-hex 쿠키 또는 타 사용자 발급 쿠키 | GET stream | `unseal` 이 `{nonce}.{HMAC(sealKey, subject+'.'+nonce)}` 를 상수시간 검증 → 불일치 시 null → 401 | security | H | StreamNonceCookie.java:132-155,167-169 |
| TC-STREAM-010 | nonce 쿠키 속성 (신규) | 발급 응답 | GET /v1/videos/{rawSn}/stream-url | `Set-Cookie: klid_stream_nonce=...; HttpOnly; SameSite=Lax; Path={ctx}/v1/videos; Max-Age=3600` + **Secure(local 프로파일 제외)**. 판정축은 프로파일이며 `request.isSecure()` 아님 | security | H | StreamNonceCookie.java:214-224 |
| TC-STREAM-011 | sign-secret 미설정 시 봉인키가 기동 랜덤 → 어떤 쿠키도 불통과 (신규) | `authoring.stream.sign-secret` 빈 값 | 임의 쿠키 | JVM 기동 시 랜덤 32B 파생키 → 봉인 검증 전건 실패(fail-closed). 서명 발급/검증 자체도 비활성 | security | M | StreamNonceCookie.java:183-190 |
| TC-STREAM-012 | 서명 경로에도 영상 단위 인가가 적용된다 (신규) | 미배정 WORKER 의 u 로 발급된 서명 URL | GET /v1/videos/{타 영상}/stream | principal 에 실제 sub·재조회 역할이 채워져 컨트롤러 `LabelAccessGuard.verifyRawAccess` 가 **403**(B-ISSUE-63 해소). ROLE_* authority 는 부여하지 않아 타 API 로 확대 불가 | security | H | StreamSignatureFilter.java:139-148,160-169 |

## A-4. 웹훅 인증 필터 (HmacWebhookFilter · 무서명 가드 · WebhookGate)

> **★ 계약 전면 교체 (Phase 1 + Phase 7-A2)**: 서명 필수 경로는 **현재 0개**다(`SIGNATURE_REQUIRED = List.of()`). 유일한 서명 필수 웹훅이던 `/v1/aug/callback` 이 제거되면서, 보호 경로는 **무서명 가드 전용 2종**(`/v1/vlm/callback`, `/v1/genai/callback`)뿐이다. 서명 검증 분기는 **경로 판정 불가(fail-closed) 요청**에만 도달한다. 필터 적용 판정은 MVC 와 동일한 `RequestPath`+`PathPattern` allowlist 이고, 컨트롤러 진입 직전 `WebhookGateInterceptor` 가 통과 증거를 재확인한다(2단 게이트).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| ~~TC-HMAC-001~~ | ~~유효 HMAC 콜백 통과~~ | ~~시크릿 설정,올바른 서명~~ | ~~POST /v1/aug/callback~~ | **[폐기 2026-07-30]** 서명 필수 경로가 0개가 되고 `/v1/aug/callback` 컨트롤러·DTO·시뮬레이터가 제거됨(Phase 7-A2). 증강 결과 수신은 무서명 `/v1/genai/callback` 으로 대체 → TC-AUTHZ-027(permitAll 경로 2종) · TC-HMAC-031(genai IP allowlist fail-closed) · TC-HMAC-035(본문 1MB 상한) 참조 | security | — | WebhookProtectedPaths.java:81-88 |
| TC-HMAC-002 | 시크릿 미설정 상태에서 **경로 판정 불가** 요청은 401 | 서명 필수 경로 0개 + 시크릿 미설정(기동 허용) | URI 파싱이 깨지는 요청 | `requiresSignature=true`(fail-closed) → 시크릿 없음 → 401 `secret_missing`. **기동은 막지 않는다**(구 "미설정=기동 차단" 무조건 규칙 정정) | security | H | HmacWebhookFilter.java:214-224,311-318 |
| TC-HMAC-003 | 시크릿 32B 미만이면 부팅 차단 | secret 설정됨 & <32B | 기동 | BeanInitializationException (경로 유무 무관) | security | H | HmacWebhookFilter.java:240-245 |
| TC-HMAC-004 | 서명/timestamp 헤더 누락 401+실패카운트 | 판정 불가 경로 | 헤더 없음 | 401 `missing_signature` + `rateLimiter.recordFailure` | security | H | HmacWebhookFilter.java:333-340 |
| TC-HMAC-005 | 미지원 서명 접두사 401 | `hmac-sha256=` 아님 | 콜백 | 401 `unsupported_algorithm` + recordFailure | security | M | HmacWebhookFilter.java:341-346 |
| TC-HMAC-006 | timestamp 비숫자 401 | X-Timestamp="abc" | 콜백 | 401 `timestamp_malformed` + recordFailure | security | M | HmacWebhookFilter.java:349-357 |
| TC-HMAC-007 | timestamp 윈도우 — 과거 300s / **미래 30s 비대칭** | 기본 window 300s | ts = now-301s / now+31s | 둘 다 401 `timestamp_window`. **구 `Math.abs` ±5분 대칭(유효창 10분)은 폐기** — 미래는 시계 오차분 `FUTURE_SKEW_MS=30s` 만 허용 | security | H | HmacWebhookFilter.java:132-133,358-365 |
| TC-HMAC-008 | 서명 불일치 상수시간 비교 401(timing 방어) | 잘못된 서명 | 콜백 | `MessageDigest.isEqual` → 401 `signature_mismatch` | security | H | HmacWebhookFilter.java:389-396 |
| TC-HMAC-009 | Content-Length 누락 401(서명 경로) | length=-1 | 콜백 | 401 `missing_content_length`. ⚠ 무서명 경로는 **411**(정책차) | security | M | HmacWebhookFilter.java:321-326 |
| TC-HMAC-010 | 서명 경로 본문 1MB 초과 413 | length>1MB | 콜백 | 413 | security | H | HmacWebhookFilter.java:98,327-331 |
| TC-HMAC-011 | 위조 Content-Length/대용량 스트림 읽기상한 차단 | 실제>상한 | 콜백 | `readCapped` 8KB 청크 누적이 상한 초과 즉시 중단(전량 버퍼링 없음) → 413 | security | H | HmacWebhookFilter.java:368-375,726-740 |
| TC-HMAC-012 | 인증 실패 분당 5회 초과 시 60초 backoff | 반복 실패 | 6회째 | 429 + `Retry-After: 60`. **로컬 캐시 + 공유 DB 집계 2단** — 로컬 미차단이면 반드시 공유(현재+직전 버킷)를 조회 | security | H | HmacWebhookFilter.java:305-309,666-672; WebhookRateLimiter.java:68-71,95-114 |
| TC-HMAC-013 | 카운터 reset 은 **서명 검증 성공** 시에만 | 실패 누적 후 유효 서명 통과 | 유효 콜백 | 로컬 트래커 제거 + 공유 **현재·직전 버킷 둘 다** 삭제(조회 범위와 대칭). 무서명 경로의 하류 200 으로는 **reset 하지 않는다** | unit | H | HmacWebhookFilter.java:417; WebhookRateLimiter.java:207-214 |
| TC-HMAC-014 | 로컬 트래커 hard cap(4096) 초과 시 evict | 다수 IP 실패 | cap 초과 | 만료 항목 removeIf → 그래도 초과 시 오래된 순 evict | security | M | WebhookRateLimiter.java:74,256-273 |
| TC-HMAC-015 | 로그 CR/LF/탭 치환 + 200자 절단(Log Injection 방어) | 제어문자 포함 경로/IP | 로깅 | `[\r\n\t]`→`_`, 200자 초과 시 절단 + `...` | security | M | WebhookProtectedPaths.java:100-103,227-233 |
| TC-HMAC-016 | VLM 콜백은 무서명 — 가드 순서 rate limit→IP→CL→size cap | /v1/vlm/callback | 무서명 콜백 | HMAC/timestamp/nonce 미적용. 최종 인증은 `VlmResultService` 의 request_id 발급 게이트 | integration | M | HmacWebhookFilter.java:470-533 |
| TC-HMAC-017 | VLM 콜백 chunked면 411 | length=-1 | VLM 콜백 | 411 LENGTH_REQUIRED + recordFailure | security | M | HmacWebhookFilter.java:506-511 |
| TC-HMAC-018 | VLM 콜백 4MB 초과 413 | length>4MB | VLM 콜백 | 413 (선언 CL + 실스트림 2단 캡) | security | M | HmacWebhookFilter.java:119,512-527 |
| TC-HMAC-019 | 미등록 경로는 필터 미동작 — 단 판정 불가는 **적용** | 임의 경로 / URI 파싱 실패 | - | allowlist(PathPattern) 밖이면 shouldNotFilter=true. 판정 예외 시 **false(=보호)** 로 고정 | unit | H | HmacWebhookFilter.java:254-263; WebhookProtectedPaths.java:155-161 |
| TC-HMAC-020 | HmacSigner와 필터가 동일 서명 규칙 공유 | - | sign/verify | `hmacSha256Hex` → `HmacSigner.hex` 위임(단일 진실원) | unit | H | HmacWebhookFilter.java:574-576; HmacSigner.java:42-53 |
| TC-HMAC-021 | 시크릿이 로그/예외에 미노출 | 계산 실패 | - | 예외 메시지에 algorithm 만 | security | M | HmacSigner.java:48-52 |
| TC-HMAC-022 | 퍼센트 인코딩 경로 변형도 필터 적용 (신규 · CRITICAL 회귀 가드) | 무인증 | POST `/v1/%76lm/callback`, `/v1/g%65nai/callback`, `/v1/vlm/%63allback` | 정규화 경로가 allowlist 에 매칭돼 **필터 적용** → 403/411/401 (구 결함: 문자열 정확일치라 필터 스킵 + 컨트롤러 도달 = 무인증 관통, E-ISSUE-01) | security | H | WebhookProtectedPaths.java:260-279 |
| TC-HMAC-023 | 필터를 우회해 라우팅된 요청은 인터셉터가 401 (신규) | 증거 래퍼(`WebhookGuardedRequest`) 부재 | 웹훅 경로 컨트롤러 진입 시도 | preHandle 이 401 — `@RequestBody` 역직렬화 **이전**이라 파싱 비용도 유발하지 않음 | security | H | WebhookGateInterceptor.java:54-60 |
| TC-HMAC-024 | ASYNC 재디스패치는 2단 게이트 통과 (신규) | 최초 디스패치에서 게이트 통과한 요청 | `DispatcherType.ASYNC` | 통과(래퍼 소실로 정상 콜백이 401 되던 것 차단). 디스패치 타입은 컨테이너가 정하므로 위조 불가 | integration | M | WebhookGateInterceptor.java:51-53 |
| TC-HMAC-025 | 서명 nonce 1회성 소비 → 재전송 409 (신규) | 서명 검증 통과 요청 1건 | 동일 헤더/본문 재전송(경로 인코딩 변형 포함) | 최초 200, 이후 **409 CONFLICT**(중복 흡수). nonce 키는 (**정규화 경로**, timestamp, 서명) SHA-256 이라 인코딩 변형이 하나로 수렴 | security | H | HmacWebhookFilter.java:398-415,584-596 |
| TC-HMAC-026 | nonce 소비는 서명 검증 **성공 이후**에만 (신규) | 서명 불일치 요청 반복 | 위조 서명 N회 | `LS_WHK_SIGN_USE` 에 행이 생기지 않는다(pre-auth write DoS 차단) | security | H | HmacWebhookFilter.java:59-62,398-401 |
| TC-HMAC-027 | nonce 저장소 장애 시 503 (신규) | `WebhookGuardUnavailableException` | 콜백 | 503 + `Retry-After: 30` (fail-closed — 401 로 오분류하지 않아 벤더 재시도 가능) | security | H | HmacWebhookFilter.java:404-409,647-652 |
| TC-HMAC-028 | 하류 5xx 시 nonce 예약 해제 (신규) | 컨트롤러가 500 반환 | 동일 서명 재전송 | 최초 요청에서 nonce release → 재전송이 409 가 아니라 재처리됨(결과 영구 유실 차단). release 실패는 요청 결과를 바꾸지 않음 | integration | H | HmacWebhookFilter.java:418-443 |
| TC-HMAC-029 | 커밋된 공개 placeholder 시크릿은 local 외 기동 차단 (신규) | `local-augment-callback-hmac-change-me-32bytes-min` 등 | dev/stg/prd 기동 | BeanInitializationException. local 프로파일만 허용 | security | H | HmacWebhookFilter.java:157-166,234-239 |
| TC-HMAC-030 | `?`로 시작하는 미해석 placeholder 시크릿 기동 차단 (신규) | yml 에 `${VAR:?msg}` 사용 | 기동 | Spring 은 `"?msg"` 를 기본값으로 주입 → 32B 통과하므로 별도 차단. BeanInitializationException | security | H | HmacWebhookFilter.java:227-233 |
| TC-HMAC-031 | genai IP allowlist 미설정 = **전면 403**(fail-closed) (신규) | `webhook.genai.allowed-ip-cidrs` 미설정/`none` | POST /v1/genai/callback | 403 + recordFailure. **VLM 은 반대로 미설정=전면 허용** — 의도된 비대칭(신규 연동은 조용한 전면 허용을 물려받지 않음) | security | H | GenAiWebhookIpAllowlist.java:44-77; WebhookIpAllowlist.java:80-98 |
| TC-HMAC-032 | 비허용 IP 403 도 실패 집계 (신규) | allowlist 밖 IP | 반복 요청 | `recordFailure` + `webhook.auth.failed{reason=ip_not_allowed}` → 임계 초과 시 429 | security | H | HmacWebhookFilter.java:486-500 |
| TC-HMAC-033 | 무서명 경로 하류 401/403 도 실패 집계, 2xx 는 reset 안 함 (신규) | 위조 request_id 반복 | 15연타 | 401×5 → 429×이후. "위조 4회 + 알려진 id 1회(200)" 로 카운터를 지울 수 없다 | security | H | HmacWebhookFilter.java:543-564 |
| TC-HMAC-034 | 집계를 끄는 런타임 분기가 없다 (신규) | 공격자 제어 `X-Forwarded-For` 부착 | XFF 회전 15연타 | 401×5 → 429. **헤더 존재로 통제를 끄는 게이트(구 `isClientIpAttributable`)는 제거됨**(R-1) | security | H | HmacWebhookFilter.java:552-555; ClientIpResolver.java:55-61 |
| TC-HMAC-035 | genai 본문 상한은 1MB (VLM 4MB 와 다름) (신규) | genai 콜백 | length>1MB | 413. 계약상 results[] 100건 ≈ 100KB → 10배 헤드룸 | security | M | HmacWebhookFilter.java:121-130,476 |
| TC-HMAC-036 | ClientIpResolver — 신뢰 프록시 밖이면 XFF 폐기 (신규) | trusted-proxy-cidrs 미설정/불일치 | XFF 부착 요청 | remoteAddr 채택, XFF 무시. 미설정 상태 XFF 관측은 **프로세스당 1회만** WARN | security | H | ClientIpResolver.java:139-149,207-217 |
| TC-HMAC-037 | ClientIpResolver — 신뢰 홉 우측부터 벗기고 최초 비신뢰 값 채택 (신규) | remoteAddr ∈ 신뢰 CIDR | `XFF: 1.2.3.4, 10.0.0.9` | `1.2.3.4` 채택. 비정상 값 만나면 remoteAddr 로 폴백 | security | H | ClientIpResolver.java:150-167 |
| TC-HMAC-038 | XFF 파싱 상한(길이 1024 · 홉 20) (신규) | 과대 헤더 | 초과 XFF | 길이 초과 시 헤더 무시, 홉은 20개까지만 파싱(CPU/메모리 소모 차단) | security | M | ClientIpResolver.java:70-74,147-154 |
| TC-HMAC-039 | remoteAddr 이 IP 리터럴이 아니면 `unknown` sentinel (신규) | 상위 래퍼가 remoteAddr 오염 | 비-IP remoteAddr | `unknown` 반환 + 프로세스당 1회 WARN. 어떤 CIDR 에도 매칭되지 않고 DNS 조회 유입도 차단. ⚠ XFF 우회 자체를 막는 통제는 아님 | security | M | ClientIpResolver.java:126-138,193-198,229-236 |
| TC-HMAC-040 | prd/stg 에서 trusted-proxy-cidrs 미설정 시 기동 차단 (신규) | prd 또는 stg 프로파일 | 값 미설정 | BeanInitializationException. `none` 명시는 허용(프록시 없음을 의식적으로 선택) | security | H | ClientIpResolver.java:93-107; WebhookConfigProfiles.java:32,38-44 |
| TC-HMAC-041 | prd/stg 에서 vlm allowed-ip-cidrs 미설정 시 기동 차단 (신규) | prd 또는 stg 프로파일 | 값 미설정 | BeanInitializationException. `none` 명시는 허용(2계층으로 운영) | security | H | WebhookIpAllowlist.java:46-61 |
| TC-HMAC-042 | CIDR 형식 오류는 무시하지 않고 기동 차단 (신규) | `10.0.0.0/8x` 등 오타 | 기동 | `WebhookCidrParser.parseStrict` 예외 → 기동 실패. 조용히 버리면 allowlist 가 비어 전면 허용/전면 무시로 떨어짐 | security | H | ClientIpResolver.java:103-107; WebhookIpAllowlist.java:57-61 |
| TC-HMAC-043 | 인증 실패 메트릭 태그는 저카디널리티 상수뿐 (신규) | 임의 경로로 실패 유발 | `/v1/vlm/AAAA0001..` 반복 | `webhook.auth.failed{path∈{vlm,genai,other}, reason=상수}` — 원시 URI 는 태그가 되지 않는다(Meter 무한 생성 차단, CWE-770) | security | M | HmacWebhookFilter.java:136,610-622; WebhookProtectedPaths.java:73-75,198-207 |
| TC-HMAC-044 | 필터 실행 경로는 Security 체인 1개로 고정 (신규) | `@Component` 자동 등록 | 기동 | `FilterRegistrationBean.setEnabled(false)` 로 서블릿 자동 등록 비활성 → 이중 실행 없음 | integration | M | WebhookGateConfig.java:42-49; SecurityConfig.java:162 |
| TC-HMAC-045 | 가드 만료행 주기 정리 (신규) | 기본 활성 | 기동 후 | 전용 데몬 executor 가 `LS_WHK_SIGN_USE`·`LS_WHK_FAIL_NMTM` 만료행을 조건부 DELETE(기본 10분 간격, 최소 60s clamp, 초기 지연 5분). 2노드 동시 실행 무해 | integration | M | WebhookGuardPurgeJob.java:36-56; JdbcWebhookGuardStore.java:72,88 |
| TC-HMAC-046 | 저장소 장애 정책 비대칭 — nonce=fail-closed / rate limit=fail-open (신규) | 공유 DB 장애 | 콜백 | nonce 조회 실패 → **503 거부**. rate limit 조회 실패(`UNAVAILABLE`) → 로컬 카운터만으로 계속 동작(정상 벤더 콜백을 막지 않음) | security | H | HmacWebhookFilter.java:404-409; WebhookRateLimiter.java:137-140; JdbcWebhookGuardStore.java:157-169 |

## A-5. 권한 자가부여 (RoleClaimService · RoleClaimRateLimiter)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| TC-CLAIM-001 | 무권한 INTERNAL이 관리자 pw로 **WORKER** 역할 획득 | role=null,INTERNAL | POST role-claim(WORKER,pw) | 200, 새 토큰, LS_USER_ROLE upsert. **REVIEWER 는 자가부여 불가**(TC-CLAIM-015) | integration | H | RoleClaimService.java:105-193 |
| TC-CLAIM-002 | PORTAL_USER 역할 요청 400 거부 | - | role=PORTAL_USER | INVALID_INPUT | security | H | RoleClaimService.java:111-114 |
| TC-CLAIM-003 | 이미 역할 보유자 409 거부 | actor.role!=null | 요청 | CONFLICT | security | H | RoleClaimService.java:127-129 |
| TC-CLAIM-004 | PORTAL 채널 교차채널 자가부여 차단 | channel=PORTAL | 요청 | CONFLICT(deny-by-default). SecurityConfig 가 role-claim 을 `authenticated()` 로만 잡으므로 **서비스단이 유일 방어선** | security | H | RoleClaimService.java:127-129; SecurityConfig.java:71 |
| TC-CLAIM-005 | 잘못된 관리자 pw 401(상수시간) | 틀린 pw | 요청 | UNAUTHORIZED (`BCryptPasswordEncoder.matches`) | security | H | RoleClaimService.java:137-144 |
| TC-CLAIM-006 | admin pw 해시 미설정 시 항상 401(fail-closed) | hash="" | 요청 | UNAUTHORIZED (`isEmpty()` 명시 차단) | security | H | RoleClaimService.java:87-97,137 |
| TC-CLAIM-007 | admin pw가 BCrypt 아니면 부팅 거부 | 평문 주입 | 기동 | IllegalStateException (prefix `$2a$/$2b$/$2y$` 검사) | security | H | RoleClaimService.java:92-95,101-103 |
| TC-CLAIM-008 | 계정 축 5회/분 초과 429 (pw 검증 **전** 차단) | 6회째 | 요청 | TOO_MANY_REQUESTS. 검사 순서 = **역할 화이트리스트 → 채널/역할 게이트 → rate limit → pw** | security | H | RoleClaimService.java:118-133; RoleClaimRateLimiter.java:104-128 |
| TC-CLAIM-009 | LS_USER_ROLE 이미 매핑 있으면(stale JWT) 409 | role=null이나 LS 존재 | 요청 | CONFLICT (이중 게이트) | security | H | RoleClaimService.java:159-162 |
| TC-CLAIM-010 | 사용자 미존재 404 | userNo 없음 | 요청 | NOT_FOUND | integration | M | RoleClaimService.java:153-156 |
| TC-CLAIM-011 | 부여 성공 후 AFTER_COMMIT 캐시 evict | - | 요청 | afterCommit evict(동기화 비활성 시 즉시 evict 폴백) | integration | M | RoleClaimService.java:167-179 |
| TC-CLAIM-012 | 발급 토큰 HS256/issuer/channel=INTERNAL/exp 1h | 성공 | 토큰 검사 | 클레임 정합. issuer 기본 `klid-auth` ∈ allowlist | integration | M | RoleClaimService.java:61,195-208 |
| TC-CLAIM-013 | 로그에 pw 평문/userNo CRLF 미노출 | 제어문자 sub | 실패 로깅 | 32자 절단 + `[\r\n\t]`→`_`. `adminPassword` 는 어디에도 미출력 | security | M | RoleClaimService.java:119-120,140-141,211-218 |
| TC-CLAIM-014 | sub 비숫자면 400 | sub="abc" | 요청 | INVALID_INPUT (pw 검증 후 파싱이라 정보노출 순서상 안전) | security | M | RoleClaimService.java:146-151 |
| TC-CLAIM-015 | **REVIEWER 자가부여는 403 거부** (신규) | role=null INTERNAL, 올바른 관리자 pw | POST role-claim(REVIEWER,pw) | **403** "해당 역할은 자가 부여할 수 없습니다" — `allowedClaimRoles()`(WORKER 단일)가 `claim()` **최상단**에서 강제됨(A-ISSUE-17 해소). rate limit 보다 앞이라 잘못된 role 시도가 정상 사용자 쿼터를 소모하지 않는다 | security | H | RoleClaimService.java:115-123,227-229 |
| TC-CLAIM-016 | 전역 축 50회/분 초과 시 429 (교차계정 증폭 차단) (신규) | 무권한 계정 A/B/C 번갈아 호출 | 전역 51회째 | TOO_MANY_REQUESTS. 계정 축(5/분)만으로는 실효 시도량이 계정 수 배로 늘어남 | security | H | RoleClaimRateLimiter.java:81-82,108-109 |
| TC-CLAIM-017 | 공유 저장소 장애여도 로컬 카운터가 임계 강제 (신규) | `RoleClaimAttemptStore.UNAVAILABLE`(-1) | 반복 호출 | `max(local, shared)` 로 판정 → 완전 fail-open 없음 | security | H | RoleClaimRateLimiter.java:116-127; JdbcRoleClaimAttemptStore.java:79-83 |
| TC-CLAIM-018 | 버킷 시각은 UTC 고정(노드 TZ 불일치 방지) (신규) | 2노드 TZ 상이 | 동시 호출 | `LocalDateTime.now(ZoneOffset.UTC).truncatedTo(MINUTES)` — 같은 순간의 요청이 동일 공유 행(PK 일부)을 갱신 | integration | H | RoleClaimRateLimiter.java:105-106 |
| TC-CLAIM-019 | 로컬 카운터 회수 — Caffeine TTL 10분 + maximumSize 10,000 (신규) | 다수 sub 호출 | 윈도우 경과 | 엔트리 자동 회수(구 `ConcurrentHashMap` 영구 잔존 = CWE-770 해소) | unit | M | RoleClaimRateLimiter.java:60-64,91-95 |
| TC-CLAIM-020 | 캐시 키 구분자는 NUL(`'\0'`) — 축·식별자·창 분리 (신규) | `sub` 에 구분자 유사 문자열 포함 | consumeAxis | `SE_CD + '\0' + IDNTFR + '\0' + windowStart` 로 키 충돌 없음. 소스 표현이 리터럴 NUL → `'\0'` 이스케이프로 바뀌었을 뿐 **런타임 동작 불변**(a18c646f) | unit | M | RoleClaimRateLimiter.java:113 |
| TC-CLAIM-021 | 시도 기록 만료행 주기 정리 (신규) | 기본 활성 | 기동 후 | 전용 데몬 스레드(`role-claim-attempt-purge`)가 만료행 삭제(기본 10분 간격·초기 지연 5분). 실패해도 예외 승격 없음 | integration | M | RoleClaimAttemptPurgeJob.java:47-49,62-68,86-94 |

## A-6. 추적ID·공통 응답·예외 + 공통 유틸 (RequestIdFilter · ApiResponse · GlobalExceptionHandler · LogSanitizer · SortAllowlist · BlankTextPredicate)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| TC-TRACE-001 | X-Trace-Id 없으면 12자리 생성해 MDC/응답 설정 | 헤더 없음 | 요청 | MDC+응답 헤더 | unit | H | RequestIdFilter.java:28-33 |
| TC-TRACE-002 | 제공 traceId CRLF 제거(CWE-113) | `\r\n주입` | 헤더 | sanitize | security | H | RequestIdFilter.java:48-59 |
| TC-TRACE-003 | 영숫자·하이픈 외 제거+64자 절단 | 특수문자/장문 | 헤더 | 화이트리스트 필터 + truncate | unit | M | RequestIdFilter.java:52-57 |
| TC-TRACE-004 | 요청 종료 후 MDC 제거(스레드 누수 방지) | 완료 | finally | MDC.remove | unit | M | RequestIdFilter.java:34-38 |
| TC-TRACE-005 | RequestIdFilter 최우선순위 | - | - | HIGHEST_PRECEDENCE | unit | L | RequestIdFilter.java:17 |
| TC-RESP-001 | ApiResponse.ok success=true,errorCode=null | - | ok(data) | {true,data,null,null} | unit | M | ApiResponse.java:9-11 |
| TC-RESP-002 | ApiResponse.error(code) success=false+name()+기본msg | - | error(FORBIDDEN) | {false,null,msg,"FORBIDDEN"} | unit | H | ApiResponse.java:21-23 |
| TC-RESP-003 | error(code,message,data)로 부가 data 포함 | - | NOT_REVIEWED+ids | data 포함 | unit | M | ApiResponse.java:29-31 |
| TC-RESP-004 | JsonInclude.ALWAYS null 필드도 직렬화 | data=null | 직렬화 | data 키 유지(전역 ObjectMapper 재정의 없음) | unit | M | ApiResponse.java:6 |
| TC-EXC-001 | CustomException이 ErrorCode status/message 매핑 | - | throw | 해당 status+error | integration | H | GlobalExceptionHandler.java:24-33 |
| TC-EXC-002 | details 있으면 응답 data 포함 | details!=null | NOT_REVIEWED | data 포함 | integration | M | GlobalExceptionHandler.java:28-32 |
| TC-EXC-003 | @Valid 바디 실패 400+필드별 메시지 | 검증 위반 | 요청 | INVALID_INPUT,field:msg | integration | H | GlobalExceptionHandler.java:35-43 |
| TC-EXC-004 | ConstraintViolation 400 정규화 | @Min 위반 | 요청 | INVALID_INPUT | integration | M | GlobalExceptionHandler.java:49-57 |
| TC-EXC-005 | 잘못된 JSON/enum 역직렬화 400(파서상세 비노출) | 깨진 JSON | 요청 | INVALID_INPUT, 로그도 cause 클래스명만 | security | H | GlobalExceptionHandler.java:64-69 |
| TC-EXC-006 | 필수 RequestParam 누락 400 | param 누락 | 요청 | INVALID_INPUT+param명 | integration | M | GlobalExceptionHandler.java:76-81 |
| TC-EXC-007 | 파라미터 타입 불일치 400(원문 비노출) | Long 자리 문자열 | 요청 | INVALID_INPUT, 파라미터명만 노출 | security | M | GlobalExceptionHandler.java:88-93 |
| TC-EXC-008 | 작업락 partial unique 위반만 409 | ux_ls_auth_work_lock_raw_active | 동시 락 INSERT | CONFLICT | integration | H | GlobalExceptionHandler.java:141-145,168-171 |
| TC-EXC-009 | 프리셋 labelId/라벨명 CI/검출유형 유니크 위반 각 409 | 각 인덱스명 | 충돌 | CONFLICT + 각 메시지 3종 | integration | M | GlobalExceptionHandler.java:146-160 |
| TC-EXC-010 | 판별 불가 무결성 위반은 500 노출(fail-closed) | FK/NOT NULL/제약명 null | 위반 | INTERNAL_ERROR (조용한 409 흡수 금지) | security | H | GlobalExceptionHandler.java:161-166 |
| TC-EXC-011 | 제약명 Hibernate→SQL 메시지 순 추출 | 원인 체인 | - | 정확 제약명 | unit | M | GlobalExceptionHandler.java:193-209 |
| TC-EXC-012 | 무결성 위반 응답에 제약명/SQL/스택 미노출 | 위반 | 응답 | ErrorCode 고정 메시지만 (강제 코드는 138-166, 정책 서술은 127-137 주석) | security | H | GlobalExceptionHandler.java:138-166 |
| TC-EXC-013 | AccessDeniedException 403 매핑 | - | throw | FORBIDDEN | integration | M | GlobalExceptionHandler.java:211-216 |
| TC-EXC-014 | AuthenticationException 401 매핑 | - | throw | UNAUTHORIZED | integration | M | GlobalExceptionHandler.java:218-223 |
| TC-EXC-015 | 미매핑 경로(NoResourceFound) 404 정규화 | 없는 API | 요청 | NOT_FOUND | integration | M | GlobalExceptionHandler.java:241-246 |
| TC-EXC-016 | multipart 크기 초과 413 정규화 | 대용량 업로드 | 요청 | PAYLOAD_TOO_LARGE (내부 한도 수치 비노출) | integration | M | GlobalExceptionHandler.java:234-239 |
| TC-EXC-017 | 미처리 Exception 500+고정메시지(스택 비노출) | 임의 예외 | 요청 | INTERNAL_ERROR. 스택은 서버 로그에만 | security | H | GlobalExceptionHandler.java:273-278 |
| TC-EXC-018 | 미지원 HTTP 메서드 405 매핑 (신규) | 존재하는 경로, 다른 메서드 | 예: GET 전용 경로에 POST | METHOD_NOT_ALLOWED(405). 핸들러 신설 이전에는 `handleUnknown` 으로 흘러 **500** 이었음 | integration | M | GlobalExceptionHandler.java:261-272 |
| TC-EXC-019 | 405 응답 `Allow` 헤더 필수 (신규) | 위 동일 | 지원 메서드 집합이 예외에 담김 | 응답에 `Allow: {지원메서드}` 부착(RFC 9110 필수). 집합이 null/빈이면 헤더 생략(NPE 없음) | integration | M | GlobalExceptionHandler.java:266-269 |
| TC-EXC-020 | 405 로그에 경로 원문 미노출 (신규) | 식별자·쿼리스트링 포함 URL | 미지원 메서드 요청 | WARN 로그에 **메서드명만** 기록(CWE-117/209 — 경로 원문 미기록) | security | M | GlobalExceptionHandler.java:264 |
| TC-LOG-001 | LogSanitizer — C0/DEL/C1 제어문자 제거 (신규) | 외부 입력 로깅 | `\r`,`\n`,`\t`,`\0`,``,``(NEL) | 모두 제거(제거, 치환 아님). `Character.CONTROL` 판정 | security | H | LogSanitizer.java:88-100 |
| TC-LOG-002 | LogSanitizer — **유니코드 라인 구분자 제거** (신규) | 평문 로그 레이아웃(local) | `U+2028`(LINE SEPARATOR)·`U+2029`(PARAGRAPH SEPARATOR) 포함 입력 | 제거됨 — 구 구현은 통과시켜 가짜 로그 라인 위조 가능(CWE-117). JSON 인코더 환경(dev/stg/prd)과 결과 통일 | security | H | LogSanitizer.java:78-99 |
| TC-LOG-003 | LogSanitizer — 가시 문자·일반 공백 보존 (신규) | 한글/공백 포함 | "가 나 다" | 그대로 보존(`U+0020` 은 `Zs` 라 대상 아님) | unit | M | LogSanitizer.java:86,93-95 |
| TC-LOG-004 | LogSanitizer — 길이 상한 + 절단 표기 (신규) | 200자 초과 | 장문 입력 | 200자에서 끊고 `...(truncated)` 접미. `sanitize(s, n)` 로 상한 지정 가능, `n<1` 이면 기본값 | unit | M | LogSanitizer.java:25,28,51-72 |
| TC-LOG-005 | LogSanitizer — null 은 `(null)` (신규) | - | sanitize(null) | `"(null)"` (NPE 없음) | unit | L | LogSanitizer.java:27,52-54 |
| TC-SORT-001 | SortAllowlist strict — 미등록 정렬 키 400 (신규) | 작업목록 계열 | `?sort=secretField,desc` | INVALID_INPUT(400) "지원하지 않는 정렬 기준입니다." — **입력값·내부 필드명 미노출**(CWE-209) | security | H | SortAllowlist.java:262-285 |
| TC-SORT-002 | SortAllowlist lenient — 미등록 키는 무시하고 기본 정렬 폴백 (신규) | 검수목록·영상목록 계열 | `?sort=secretField,desc` | **200 + fallback 정렬** + WARN. 남는 허용 키가 있으면 그것만 적용. ★ strict/lenient 차이는 "변경 전 그 엔드포인트가 200 이었는가" 기준이며 **통일 금지** | security | H | SortAllowlist.java:312-338; SortFieldMapper.java:57-60 |
| TC-SORT-003 | 정렬 항목 개수 상한 = allowlist 고유 엔티티 필드 수 (신규) | 반복 `sort` 파라미터 | 상한+1개 지정 | strict=400 "정렬 기준이 너무 많습니다."(개수·상한 미노출) / lenient=**전체를 fallback 으로 폴백**(부분 적용 금지). 쿼리 플랜 캐시 오염 차단(CWE-770) | security | H | SortAllowlist.java:266,271-274,321-326,346-348 |
| TC-SORT-004 | 같은 엔티티 필드 중복 지정 시 첫 지정만 적용 (신규) | alias 키 중복 | `?sort=capturedAt,asc&sort=shtDt,desc` | 둘 다 `shtDt` 로 매핑되므로 **첫 지정(asc)만** 살아남음 | unit | M | SortAllowlist.java:135-142,333-335 |
| TC-SORT-005 | 폴백 WARN 로그의 입력 키는 LogSanitizer 로 정제 (신규) | 정렬 키에 개행 주입 | `?sort=a%0Afake,desc` | WARN 메시지의 key 가 정제·64자 절단(CWE-117) | security | M | SortAllowlist.java:68,322-324,329-330 |
| TC-SORT-006 | 컨트롤러 allowlist 사본 금지 — `SortAllowlist.VIDEO` 단일 진실원 (신규) | `/v1/videos` | 정렬 요청 | `SortFieldMapper` 가 `SortAllowlist.resolveLenient` 에 위임하고 allowlist 는 상수 1벌. 사본을 두면 테스트가 사본만 검증해 드리프트 | unit | M | SortAllowlist.java:123-134; SortFieldMapper.java:29-31,57-70 |
| TC-BLANK-001 | BlankTextPredicate — Java isBlank()와 동치 판정 (신규) | `CCTV_NM='\t'` 인 행 | 목록 검색(영상명) | SQL 판정이 Java `isBlank()` 와 동일 → 화면 폴백값(`VMS_CCTV_ID`)으로 검색해도 결과가 나온다(구 `trim(col)<>''` 불일치 해소) | integration | H | BlankTextPredicate.java:60-66 |
| TC-BLANK-002 | BlankTextPredicate — 제거 문자는 파라미터 바인딩(CWE-89) (신규) | - | 식 생성 | 공백류 목록을 `Character.isWhitespace` 에서 **생성**하고 HQL `replace` 파라미터로 바인딩(쿼리 문자열 연결 없음) | security | H | BlankTextPredicate.java:49,62-65,68-76 |

## A-7. 듀얼 데이터소스 · 캐시 · 시스템설정 · 기동 가드 · 설정 바인딩 · Actuator/헬스 · Resilience4j

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|----------|------|-----------|----------|------|:--:|-----------------|
| TC-DS-001 | Control DataSource/EMF/TxManager @Primary | - | 기동 | control 빈 우선 | integration | M | ControlDataSourceConfig.java:31-55 |
| TC-DS-002 | @ControlRepo만 control EMF 라우팅 | includeFilter | - | controlTransactionManager | integration | H | ControlDataSourceConfig.java:19-28 |
| TC-DS-003 | @PortalRepo는 portal EMF 라우팅(비-Primary) | - | - | portalTransactionManager, @Primary 없음 | integration | H | PortalDataSourceConfig.java:18-28,47-50 |
| TC-DS-004 | 서비스 Tx가 controlTransactionManager 바인딩 | - | @Transactional("controlTransactionManager") | control DS 커밋/롤백 | integration | M | SystemConfigService.java:38,91 |
| TC-CACHE-001 | 4개 캐시 개별 TTL 등록 | - | 기동 | sysconfig 60s(max100) / stream-meta 5m(max200) / eventType 6h(max50) / userRole 60s(max500) | unit | M | CacheConfig.java:53-56,70-96 |
| TC-CACHE-002 | sysconfig getInt/getString 60s 캐시 | 반복 조회 | getInt x2 | 2번째 미조회(캐시 적중) | integration | M | SystemConfigService.java:47-48,80-82 |
| TC-CACHE-003 | update 시 sysconfig 캐시 전체 무효화 | 캐시 존재 | update | allEntries evict → 다음 조회 새 값 | integration | H | SystemConfigService.java:90-91 |
| TC-CACHE-004 | userRole null 결과 미저장 | 미배정 | resolve | `unless="#result==null"` 로 미저장 (근거는 CacheConfig 주석이 아니라 어노테이션) | integration | H | UserRoleResolver.java:46 |
| TC-CACHE-005 | stream-meta TTL 은 파일 유예삭제의 하한 (신규) | 2노드 Active-Active | 경로 이관 | **[기대결과 정정 2026-08-03]** 프로세스 로컬 Caffeine 이라 evict 가 타 노드에 전파되지 않음 → 파일 유예 삭제 흐름은 TTL(5분)보다 긴 유예를 두어야 한다는 **설계 규칙(클래스 주석)만 존재**. 이를 기동 시 강제 검증하던 `ResolutionBackfillService` 는 2026-07-30 제거됨(해상도 파생 저장소 이관 백필 폐지, 대상 소진) — **현재 이 규칙을 쓰는 흐름도, 강제하는 검증도 없다**. 신규 파일 이관 흐름 도입 시 유예 검증 재도입 필요(구 "기동 시 검증됨" 폐기) | integration | M | CacheConfig.java:29-39,58-66 |
| TC-SYSCFG-001 | 화이트리스트 외 키 조회/갱신 거부 | 미허용 키 | update | INVALID_INPUT, 입력 키를 메시지에 넣지 않음(CWE-117) | security | H | SystemConfigService.java:94-98 |
| TC-SYSCFG-002 | REVIEWER 아니면 update FORBIDDEN(이중 검증) | WORKER | update | FORBIDDEN | security | H | SystemConfigService.java:93,166-170 |
| TC-SYSCFG-003 | actor=null이면 update FORBIDDEN | - | update(...,null) | FORBIDDEN | security | H | SystemConfigService.java:166-169 |
| TC-SYSCFG-004 | NUMBER 키 정수+키별 범위 검증 | 범위 밖 | update | INVALID_INPUT | integration | M | SystemConfigService.java:132-145 |
| TC-SYSCFG-005 | DECIMAL NaN/Infinity/범위밖 거부 | `"NaN"`/`"Infinity"`/범위 밖 | update | INVALID_INPUT (`Double.parseDouble("NaN")` 은 예외를 던지지 않으므로 isNaN/isInfinite 가 실질 방어선) | integration | M | SystemConfigService.java:147-164 |
| TC-SYSCFG-006 | BOOLEAN true/false만 | "yes" | update | INVALID_INPUT | unit | L | SystemConfigService.java:116-125 |
| TC-SYSCFG-007 | 미지원 CONFIG_TYPE 거부 | 미지원 타입 | update | INVALID_INPUT | unit | L | SystemConfigService.java:127-128 |
| TC-SYSCFG-008 | getInt 타입/값 불일치 각 예외 | 불일치 | getInt | 타입 불일치=INVALID_INPUT / 파싱 실패=INTERNAL_ERROR | unit | M | SystemConfigService.java:49-61 |
| TC-SYSCFG-009 | 없는 설정 키 NOT_FOUND | 없는 키 | loadOrThrow | NOT_FOUND | unit | M | SystemConfigService.java:110-113 |
| TC-PROF-001 | local 프로파일이 ENV=dev/stg/prd에서 부팅 차단 | active=local,ENV=prd | 기동 | IllegalStateException | security | H | LocalProfileGuard.java:43-55 |
| TC-PROF-002 | ENV 미설정/local이면 local 허용 | ENV=null/blank/local | 기동 | 통과 | unit | M | LocalProfileGuard.java:47-49 |
| TC-PROF-003 | local 아니면 즉시 통과 | active=dev | 기동 | early return | unit | L | LocalProfileGuard.java:44-46 |
| TC-PROF-004 | DevProfileGuard — dev 프로파일 + ENV=stg/prd 기동 거부 (신규) | active=dev | ENV=prd / ENV=stg | IllegalStateException(인증 없는 dev 토큰 발급 경로 차단). ENV 미설정/blank/local/dev/미지 라벨(qa 등)은 통과 — LocalProfileGuard 와 동일 강도 | security | H | DevProfileGuard.java:49-62 |
| TC-PROF-005 | DevToggleProfileGuard — prd/stg 에서 dev 로그인 활성 시 기동 거부 (신규) | active=prd 또는 stg | `AUTHORING_DEV_LOGIN_ENABLED=true` | IllegalStateException. **yml 리터럴 `false` 는 환경변수 하나로 덮이므로 부팅 fail-fast 가 유일한 강제 수단**(A-ISSUE-05 해소). dev 업로드 토글은 대상 아님 | security | H | DevToggleProfileGuard.java:37,42-65 |
| TC-PROF-006 | ForwardedHeadersConfigGuard — 3키 설정 시 **전 프로파일** 기동 거부 (신규) | 임의 프로파일 | `server.forward-headers-strategy` / `server.tomcat.remoteip.remote-ip-header` / `...protocol-header` 중 하나라도 비어있지 않은 값 | IllegalStateException. `Environment` 로 판정하므로 yml(공통/프로파일)·**환경변수(`SERVER_FORWARD_HEADERS_STRATEGY`)**·시스템 프로퍼티·커맨드라인 전 축이 덮인다. 빈 값은 미설정으로 간주 | security | H | ForwardedHeadersConfigGuard.java:49-66,82-113 |
| TC-PROF-007 | DeidentifyEndpointTrustGuard — 신뢰 불가 비식별 엔드포인트 프로파일별 처리 (신규) | mock-mode=true 또는 base-url 호스트가 목/루프백(`klid-mock-server`,`localhost`,`127.0.0.1`,`::1`,`0.0.0.0`, `mock` 토큰 포함) | prd / stg / dev / local 기동 | prd(또는 `ENV=prd`)=**기동 거부**, stg·dev=WARN, local=무음. 판정은 이 클래스 **한 곳**에서만 수행 | security | H | DeidentifyEndpointTrustGuard.java:58-62,82-127,188-198 |
| TC-PROF-008 | DeidentifyEndpointTrustGuard — 호스트 파싱 불가 시 fail-secure + authority 폴백 (신규) | `http://kpst_deid:9201`(언더스코어) / 값 미설정 | 기동 | `URI.getHost()` null 이면 authority 폴백으로 호스트 추출해 **동일 deny 판정**을 태운다(운영 전체 부팅 거부 회피). 폴백도 실패하면 "판정 불가 → 비신뢰" | security | M | DeidentifyEndpointTrustGuard.java:106-123,139-177 |
| TC-PROF-009 | QuartzClusteringGuard — stg/prd 클러스터링 강제 (신규) | `spring.quartz.properties.org.quartz.jobStore.isClustered` | stg/prd 에서 `QUARTZ_CLUSTERED=false` 또는 미설정 상속 | **기동 거부**. prd yml 기본값은 `true`. ⚠ 클러스터링은 **트리거 중복 발화만** 막고 잡 내부 레이스는 각 잡의 원자 클레임이 별도로 막는다 | security | H | QuartzClusteringGuard.java:50-56,64-95; application-stg.yml:11; application-prd.yml:13 |
| TC-PROF-010 | QuartzClusteringGuard — allowlist `containsAll` + ENV 독립 축 (신규) | 프로파일 조합 | `local,prd` 혼합 / `LOCAL`(대문자) / `prd1`(오타) / 미지정(default) / `SPRING_PROFILES_ACTIVE=dev`+`ENV=prd` | 모두 **엄격**(기동 거부). 단일 노드 인정은 활성 프로파일이 **전부** {local,dev} 이고 ENV 배포 표식이 없을 때만 | security | H | QuartzClusteringGuard.java:101-103; DeployedEnvironmentDetector.java:63-79 |
| TC-PROF-011 | ProfileGatedUrlPolicy — 완화 플래그는 local/dev + ENV 표식 없음일 때만 인정 (신규) | `vlm.client.allow-insecure-url=true` | stg/prd/미지정 프로파일 또는 `ENV=stg\|prd` | `@PostConstruct` 에서 **기동 실패**. 연동 활성 토글(enabled/mode)과 무관하게 검사 — 운영 파일에 완화 값이 있다는 사실 자체가 사고 신호 | security | H | ProfileGatedUrlPolicy.java:45-51,78-94,138-156 |
| TC-PROF-012 | ProfileGatedUrlPolicy — 기동 assert 를 우회해도 판정은 완화되지 않음 (신규) | assert 통과 상태 | `check(baseUrl)` | `policy()` 가 다시 프로파일·ENV 를 확인해 strict/relaxed 를 고름(이중 방어) | security | M | ProfileGatedUrlPolicy.java:124-136 |
| TC-PROF-013 | ExternalUrlPolicy strict — https 전용 + 사설/루프백 차단 (신규) | VLM 운영 정책 | `http://...` / `10.0.0.5` / DNS 해석 실패 | 각각 IllegalStateException(CWE-319 / CWE-918). **해석 실패도 거부**(DNS rebinding 대응). 예외 메시지에 baseUrl 원문 미노출(CWE-209) | security | H | ExternalUrlPolicy.java:90-133,168-188 |
| TC-PROF-014 | ExternalUrlPolicy 완화 경로도 링크로컬/메타데이터 대역 거부 (신규) | local/dev 완화 | `http://169.254.169.254/` | 거부. 단 **해석 실패는 통과**(컨테이너 서비스명이 도커 밖에서 안 풀려도 기동 보장) — "해석되면 검사, 안 되면 통과" | security | H | ExternalUrlPolicy.java:123-127,143-166 |
| TC-PROF-015 | ExternalUrlPolicy — placeholder/예제 호스트는 어떤 정책에서도 차단 (신규) | 미설정 배포 | `https://your-vlm-service/`, `example.com`, `changeme` | IllegalStateException(운영 미설정 배포 방지) | security | M | ExternalUrlPolicy.java:43-47,117-122 |
| TC-PROF-016 | 평문 http 엔드포인트에 토큰 설정 시 경고(값 미출력) (신규) | `vlm.client.url=http://...` + token | 기동 | WARN + `tokenLength=N` 만 로깅(CWE-532). 거부하지는 않음 | security | M | ProfileGatedUrlPolicy.java:107-116; WebClientConfig.java:67-70 |
| TC-PROF-017 | GenAiIntegrationWiringGuard — 위탁↔콜백 배선 짝 강제 (신규) | `authoring.augment.external.mode` 미설정 또는 `http` | `webhook.genai.allowed-ip-cidrs` 미설정/`none` | **기동 거부** — 위탁은 나가는데 콜백이 전건 403 이라 증강이 PENDING 영구 고착. 해제는 대역 명시 또는 `mode=noop` | security | H | GenAiIntegrationWiringGuard.java:35-39,52-85 |
| TC-CFG-001 | prd hikari 풀은 prefix 직하(평면) 바인딩으로 20/5 발효 (신규) | `spring.datasource.{control,portal}` | prd 프로파일 바인딩 | `maximum-pool-size=20`, `minimum-idle=5`. **`hikari:` 중첩 키는 unknown field 로 조용히 무시**되어 기본 10 으로 동작했던 것 정정. 회귀 가드 `DataSourcePoolBindingTest` | integration | H | application-prd.yml:2-5,14-28; ControlDataSourceConfig.java:32-36 |
| TC-CFG-002 | multipart 한도 — prd 21MB/1100MB, 그 외 500MB/1200MB (신규) | 프로파일별 | 바인딩 검증 | prd 는 공통값보다 좁게 조인다(dev 업로드 경로 부재). prd 블록 삭제 시 공통값 상속으로 조용히 상향 → `ConfigProfileDriftGuardTest` 가 차단 | security | H | application-prd.yml:29-39; application.yml:14,28-29 |
| TC-CFG-003 | dev 프로파일에 내부 IP 리터럴 기본값 없음 (신규) | application-dev.yml | 정적 검사 | 하드코딩 IP 0건(`ConfigProfileDriftGuardTest`) | security | M | ConfigProfileDriftGuardTest.java:87-105 |
| TC-CFG-004 | dev 토글 override 가능 / stg dev 로그인은 리터럴 false (신규) | 프로파일별 | `DEV_LOGIN_ENABLED` 등 | dev 는 `${DEV_LOGIN_ENABLED:true}` 로 override 가능, stg 는 리터럴 `false`(+TC-PROF-005 기동 가드) | security | H | application-dev.yml:23-28; application-stg.yml:51-56 |
| TC-CFG-005 | ENV 배포 표식이 온프렘 템플릿에 주입된다 (신규) | 온프렘 배포 | `deploy/onprem/config/backend/env.template` | `ENV=prd` 존재 → `QuartzClusteringGuard`/`DevProfileGuard`/`ProfileGatedUrlPolicy` 의 ENV 축이 실제로 발화. 누락 시 세 가드의 두 번째 방어축이 무력 | integration | H | deploy/onprem/config/backend/env.template:25 |
| TC-ACT-001 | prd는 actuator health만 노출 | prd | 설정 | metrics/prometheus 미노출 + show-details=never | security | H | application-prd.yml:93-100 |
| TC-ACT-002 | 기본은 ACTUATOR_EXPOSE로 노출 제어 | 기본값 | - | health,info,metrics,prometheus / show-details=when-authorized | integration | M | application.yml:133-140 |
| TC-HEALTH-001 | 비식별 헬스 — mock 모드는 **핑 없이** UP (신규) | `authoring.integration.deidentify.mock-mode=true` | GET /actuator/health | UP + `mode=mock`, 외부 호출 0건(자체 복사가 정상 형상) | integration | M | DeidentifyHealthIndicator.java:77-83 |
| TC-HEALTH-002 | 비식별 헬스 — KPST 형상은 **root(`/`)** 를 핑 (신규) | `kpst.deid.enabled=true` + `kpstDeidWebClient` 존재 | GET /actuator/health | `kpst.deid.base-url` 의 `/` 를 2초 타임아웃으로 핑 → 성공 UP(`mode=kpst`) / 예외 DOWN(예외 **클래스명만** 노출). **`/health` 경로가 아니다** — KPST 벤더는 제공하지 않음. 판정축도 구 `deidentifyWebClient` 가 아니라 실제 위탁 클라이언트 | integration | H | DeidentifyHealthIndicator.java:44,70-73,92-112 |
| TC-HEALTH-003 | 비식별 헬스 — 미구성은 DOWN(fail-closed) (신규) | mock 도 아니고 `kpst.deid.enabled=false` 또는 WebClient 부재 | GET /actuator/health | DOWN + `mode=unconfigured`, `error=NoDeidentifyPathConfigured` (실행 경로가 거부하는 상태와 동일 판정) | integration | H | DeidentifyHealthIndicator.java:84-91 |
| TC-RES-001 | 외부 호출 Retry+CircuitBreaker 순차 적용 | - | AiServerClient 호출 | Retry→CB 순서 적용(**[기대결과 정정 2026-08-03]** 현재 5개 메서드 `predictYolo`·`predictYoloTrack`·`segment`·`track`·`verifyObjects` 동일 패턴 — 구 "4개"는 `track`/`verifyObjects` 추가 전 카운트, 구 `extractVideoMeta` 는 이관되어 제거됨) | integration | H | AiServerClient.java:46-47,63-64 |
| TC-RES-002 | 5개 CircuitBreaker 빈 등록 | - | 기동 | deid/ai/vlmClient/controlNotify/kpstDeid | integration | M | Resilience4jConfig.java:13-47 |
| TC-RES-003 | aiOnline Bulkhead 초과 시 429 (YOLO·SAM 온라인 공유) | 동시 초과 | `AutolabelOnlineService` 온라인 AI 탐지(YOLO)/분할(SAM) 호출 | `BulkheadFullException` → 429. **[기대결과 정정 2026-08-03]** 등록된 Bulkhead 빈은 `aiOnlineBulkhead` 1개뿐이며 YOLO(`callYolo`)·SAM(`callSam`) 온라인 호출이 이 빈 하나를 공유한다(빈 정의 Resilience4jConfig.java:55-58, 코드 실측). **`PortalSam2Service` 는 레포에 존재하지 않는다** — 포털은 ADR-013(오토라벨링·SAM2 미제공)이라 별도 포털 SAM2 bulkhead 자체가 없다(구 "portalSam2" 서술 폐기) | integration | M | Resilience4jConfig.java:49-58; AutolabelOnlineService.java:501-505,531-535 |
| TC-RES-004 | NonRetryableExternalException 재시도/CB 제외 | 결정적 실패(4xx) | 외부 호출 | 재시도 없이 전파. 실제 제외는 application.yml `ignore-exceptions` 설정 | integration | M | NonRetryableExternalException.java:3-9; application.yml(resilience4j `ignore-exceptions`) |

> **불확실 항목**: #6 RoleHierarchy 빈 계층 정책(미해소 — 빈이 어디에도 미배선) → [UNCERTAINTIES.md](UNCERTAINTIES.md)
> #7 Logback 마스킹 레이아웃 실체는 **해소**(2026-07-25 Phase 2): 죽은 `conversionRule` 제거 후 `LayoutWrappingEncoder`+`MaskingPatternLayout` 로 배선, `LocalLogMaskingIT` 가 appender 종단 캡처로 검증. dev/stg/prd 는 `MaskingJsonValueMasker` 경로.
