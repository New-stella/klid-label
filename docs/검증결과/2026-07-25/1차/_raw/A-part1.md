# A-auth-common 1부 검증결과 (A-1 인증 / A-2 인가)

> 대상: `docs/test-cases/A-auth-common.md` 5~76행 (TC-AUTH-001~032, TC-COMMON-001~002, TC-AUTHZ-001~023, TC-CORS-001~003, TC-ROLE-001~002) — 총 60건
> 방식: 근거 file:line 정적 대조 + 기존 자동테스트 존재 여부 Grep
> 테스트 baseline: **미생성** (`docs/검증결과/2026-07-25/1차/_raw/test-baseline.md` 부재) → 실행결과 미참조, 정적 판정 + 테스트 존재여부만 기록
> 검증 시각 기준 커밋: main (27b6bb0d)

---

## A-1. 인증 (JWT)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-AUTH-001 | 유효 INTERNAL JWT → 인증 컨텍스트 | PASS | JwtAuthenticationFilter.java:59-106 확인 — parseSignedClaims→issuer검증→channel→role(LS)→TokenClaims + `ROLE_*`/`CHANNEL_INTERNAL` authority 부여 후 setAuthentication(106) | JwtAuthenticationFilterTest#validJwtSetsContext | 라인 일치 |
| TC-AUTH-002 | 헤더 없으면 익명 통과 | PASS | :55-56 헤더 null 이면 try 블록 진입 안 함, :117 chain.doFilter 만 실행 → 컨텍스트 미설정 | JwtAuthenticationFilterTest#noJwtReturns401 | STATELESS라 스레드 컨텍스트 누수 없음 |
| TC-AUTH-003 | Bearer 접두사 없으면 파싱 미시도 | PASS | :56 `header.startsWith("Bearer ")` — 대소문자 구분·공백 포함 정확 매칭. `Token abc`/`bearer x` 모두 스킵(fail-closed) | 없음 | 소문자 `bearer` 도 무시됨 → 401 (fail-closed 방향이라 결함 아님) |
| TC-AUTH-004 | 서명 불일치 거부+clearContext | PASS | :112-115 `catch (JwtException \| IllegalArgumentException)` → clearContext. SignatureException ⊂ JwtException | JwtAuthenticationFilterTest#invalidSignatureReturns401 | |
| TC-AUTH-005 | 만료 토큰 거부 | PASS | :59-62 parseSignedClaims → ExpiredJwtException(⊂JwtException) → :112 catch. clock skew 미설정(=0)이라 exp<now 즉시 거부 | JwtAuthenticationFilterTest#expiredJwtReturns401 | |
| TC-AUTH-006 | alg=none 거부 | PASS | :62 `parseSignedClaims()` 사용 — unsecured JWT 는 UnsupportedJwtException(⊂JwtException) → 무권한. `parseClaimsJwt`/`parseUnsecured` 경로 코드 내 부재(Grep 확인) | 없음 | 전용 회귀테스트 부재 — 추가 권장 |
| TC-AUTH-007 | malformed JWT 거부 | PASS | :112 MalformedJwtException ⊂ JwtException → clearContext | 없음 | |
| TC-AUTH-008 | 미허용 issuer 거부 | PASS | JwtIssuerValidator.java:22-27 `allowed.contains(issuer)`; filter :65-70 false 시 clearContext 후 doFilter(early return) | JwtIssuerValidatorTest#rejectsUnknownIssuer, SecurityConfigRoleTest#unknownIssuerReturns401 | |
| TC-AUTH-009 | issuer null/blank 거부 | PASS | JwtIssuerValidator.java:23-25 `issuer == null \|\| issuer.isBlank()` → false | JwtIssuerValidatorTest#rejectsUnknownIssuer (null/"" 단언 포함) | |
| TC-AUTH-010 | 허용 issuer 기본값 | PASS | JwtIssuerValidator.java:15 기본값 `klid,klid-portal,klid-auth`; application.yml:129 는 `${JWT_ALLOWED_ISSUERS:klid-auth,klid,klid-portal}` — 원소 집합 동일 | JwtIssuerValidatorTest#acceptsAllowedIssuers | 코드 기본값과 yml 기본값 **순서만** 다름(집합 동일, 무해). DevTokenService 는 목록 첫 원소를 iss 로 사용하므로 순서에 의존 |
| TC-AUTH-011 | issuer 목록 trim/빈항목 제거 | PASS | :16-19 `map(String::trim).filter(!isEmpty)` → `" klid , , x "` → {klid,x}. Spring 의 콤마 변환은 trim 안 하므로 코드 trim 이 필수 방어로 동작 | 없음 | 목록이 전부 공백이면 allowed=∅ → 전건 거부(fail-closed) |
| TC-AUTH-012 | channel 부재 시 INTERNAL | PASS | :72-73 `channelStr == null ? Channel.INTERNAL : valueOf` | SecurityConfigChannelTest#noChannelClaimTreatedAsInternal | |
| TC-AUTH-013 | PORTAL 채널은 role=PORTAL_USER 고정 | PASS | :84-86 else 분기 — LS 미조회, `Role.PORTAL_USER` 하드 고정. sub 값과 무관 | SecurityConfigChannelTest#portalChannelAllowedOnPortal | 포털 role 은 DB 근거 없이 부여되나 채널 격리(SecurityConfig:116-117)로 내부 리소스 차단 |
| TC-AUTH-014 | 알 수 없는 channel 거부 | PASS | :73 `Channel.valueOf("X")` → IllegalArgumentException → :112 catch(IllegalArgumentException 포함) → clearContext | JwtAuthenticationFilterTest#invalidChannelClaimReturns401 | |
| TC-AUTH-015 | INTERNAL 인가역할은 LS_USER_ROLE 출처 | PASS | :80-86 role 계산에 `body.get("role")` 미사용(Grep 상 filter 내 role 클레임 참조 0건). UserRoleResolver.java:47-61 이 유일 출처 | UserRoleResolverTest#resolvesRoleFromLs, JwtAuthenticationFilterTest#controlRoleCodeEnumMismatchReturns403 | |
| TC-AUTH-016 | sub 비숫자면 무권한 | PASS | :82-83 + :125-134 parseUserNo — NumberFormatException 삼켜 null 반환 → role=null | JwtAuthenticationFilterTest#blankSubFailsClosed (blank 만 커버) | 비숫자 sub 전용 케이스는 테스트 부재 |
| TC-AUTH-017 | sub null/blank 무권한 | PASS | :126-128 `sub == null \|\| sub.isBlank()` → null | JwtAuthenticationFilterTest#blankSubFailsClosed | |
| TC-AUTH-018 | LS 매핑 없으면 role=null | PASS | UserRoleResolver.java:52-55 `findByUserNo(...).orElse(null)` | UserRoleResolverTest#noLsRoleReturnsNull | |
| TC-AUTH-019 | role=null 도 CHANNEL authority 부여 | PASS | :99-103 role null 이면 ROLE_* 생략, CHANNEL_* 은 무조건 add | WriteEndpointAuthorizationGuardTest(쓰기 가드 회귀 방지) | **잔존 위험 → [A-ISSUE-02]**: role=null 이 `/v1/**`(CHANNEL_INTERNAL 만 요구) 읽기 전건 통과 |
| TC-AUTH-020 | name 클레임 request attribute 전달 | PASS | :108-111 `request.setAttribute(AUTH_NAME_ATTR, name)`; SessionController:40-41 이 읽음 | 없음 | name null 이면 attribute 미설정 → /me name=null (NPE 없음) |
| TC-AUTH-021 | 사용자 토큰은 STREAM_SIGNED 획득 불가 | PASS | JwtAuthenticationFilter:99-103 이 ROLE_*/CHANNEL_* 만 add — `AUTHORITY_STREAM_SIGNED` 문자열 부여처는 StreamSignatureFilter 단독(Grep 확인). StreamSignatureFilter.java:47-55 주석과 정합 | 없음(A-3 범위) | StreamSignatureFilter javadoc:24 "JwtAuthenticationFilter 보다 앞에 등록" 은 실제(SecurityConfig:135 addFilterAfter)와 **반대** → [A-ISSUE-04] 문서 드리프트 |
| TC-AUTH-022 | 생성자 인자 null 이면 생성 거부 | PASS | :37-48 3개 인자 각각 null 체크 후 IllegalArgumentException | 없음 | |
| TC-AUTH-023 | 시크릿 32B 미만 부팅 거부 | PASS | SecretKeyResolver.java:18-22 `bytes.length < 32` → IllegalArgumentException → 빈 생성 실패 = 기동 실패 | SecretKeyResolverTest#rejectsShortInput | application-local.yml:43 기본 시크릿(42B) 하드코딩은 local 프로파일 한정·env override 존재 → 결함 아님 |
| TC-AUTH-024 | 32B 이상 HS256 키 생성 | PASS | :19-28 `Keys.hmacShaKeyFor` 로 1회 생성 후 필드 반환 → 동일 인스턴스 | SecretKeyResolverTest#acceptsInputAtLeast32Bytes | |
| TC-AUTH-025 | DB 장애 시 null(fail-closed) | PASS | UserRoleResolver.java:56-60 `catch (DataAccessException)` → warn 후 null. 로그에 토큰/PII 없이 userNo 만 | UserRoleResolverTest#dbExceptionFailsClosed | DataAccessException 이 아닌 RuntimeException 은 필터 catch(JwtException\|IllegalArgumentException) 도 안 잡아 500 → 권한 부여는 없으므로 여전히 fail-closed |
| TC-AUTH-026 | ROLE_CD enum 미존재 시 null | PASS | :73-79 `Role.valueOf` IllegalArgumentException catch → null | UserRoleResolverTest#unknownRoleCodeFailsClosed | |
| TC-AUTH-027 | userNo=null 이면 null | PARTIAL | :48-50 메서드 내 null 가드는 존재하나, `@Cacheable(key="#userNo")`(:46)가 **먼저** 평가돼 Caffeine null 키로 NPE 발생 가능 → 가드 도달 불가 | UserRoleResolverTest#nullUserNoReturnsNull — **프록시 없는 `new UserRoleResolver(repo)`** 로만 검증(:29-32) | → [A-ISSUE-01] (실경로 미도달 = LOW) |
| TC-AUTH-028 | 역할 조회 userNo 캐시 | PASS | CacheConfig.java:67-71 `userRole` 캐시(max 500, expireAfterWrite 60s) + UserRoleResolver:46 `@Cacheable(cacheNames=userRole, key=#userNo)` | UserRoleCacheEvictIT(캐시 존재 전제) | 캐시 적중 횟수 직접 단언 테스트는 없음 |
| TC-AUTH-029 | null 결과 캐시 미저장 | PASS | UserRoleResolver:46 `unless = "#result == null"`; CacheConfig:32-35 주석 동일 정책 | UserRoleCacheEvictIT#roleChangeEvictsCacheAfterCommit | |
| TC-AUTH-030 | evict(userNo) 무효화 | PASS | :67-70 `@CacheEvict(cacheNames=userRole, key="#userNo")` — @Cacheable 과 키 표현식 동일(Long userNo) | UserRoleCacheEvictIT | |
| TC-AUTH-031 | 역할 변경 후 강등 즉시 반영 | PASS | :63-70 + 호출부 UserService.java:171-182 `TransactionSynchronizationManager` AFTER_COMMIT 등록(동기화 비활성 시 즉시 evict fallback), RoleClaimService.java:158-169 동일 패턴 | UserRoleCacheEvictIT | |
| TC-AUTH-032 | roleName() null 안전 | PASS | TokenClaims.java:13-15 `role == null ? null : role.name()` | 없음 | |
| TC-COMMON-001 | /me 미인증 401 | PASS | SecurityConfig:121-122 `/v1/**` → CHANNEL_INTERNAL 요구 → 익명은 ExceptionTranslation → :126 entryPoint 401. 컨트롤러 도달 시에도 SessionController.java:36-39 이 UNAUTHORIZED | 없음(직접) / SwaggerAuthIntegrationTest#issuedTokenPassesAuthLayer 간접 | |
| TC-COMMON-002 | /me sub/name/role/channel 반환 | PASS | SessionController.java:40-44 — role/channel null 안전 처리 포함 | 없음 | |

---

## A-2. 인가 (SecurityConfig · 채널 격리 · 화이트리스트)

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-AUTHZ-001 | REVIEWER 아니면 /v1/manage/** 쓰기 403 | PASS | SecurityConfig.java:108 은 **GET 한정** 매처이므로 POST 는 :109 `/v1/manage/**` hasRole(REVIEWER) 로 낙하 | LabelMasterControllerTest#POST_WORKER_403 | |
| TC-AUTHZ-002 | /v1/manage/labels GET 임의 역할 허용 | PASS | :108 `requestMatchers(GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()` | LabelMasterControllerTest#getLabels_WORKER_200, LabelAttrControllerTest | `authenticated()` 는 role=null·PORTAL 채널도 통과 → [A-ISSUE-03] 참조 |
| TC-AUTHZ-003 | /v1/system/** REVIEWER만 | PASS | :110 hasRole(REVIEWER) | 없음(직접) | |
| TC-AUTHZ-004 | /actuator/**(health/info 제외) REVIEWER만 | PASS | :100 hasRole(REVIEWER), 예외는 :72-73 에서 선매칭 | ActuatorSecurityTest#metricsForbiddenForWorker, #metricsRequiresAuth | prd 는 exposure=health 로 추가 차단(application-prd.yml:39-43) |
| TC-AUTHZ-005 | /actuator/health,info permitAll | PASS | :72-73 `/health`,`/actuator/health`,`/actuator/health/**`,`/actuator/info` permitAll | ActuatorSecurityTest#healthIsPublic | show-details: when-authorized(app.yml:97)/never(prd) → 상세 누출 없음 |
| TC-AUTHZ-006 | /v1/integration/** denyAll | PASS | :102 `.denyAll()` — 인증 여부 무관 거부(인증자=403, 익명=401) | SecurityConfigTest#legacyIntegrationPathIsProtected(익명만) | REVIEWER 인증 상태 403 단언 테스트는 부재 |
| TC-AUTHZ-007 | /v1/export-api/** denyAll | PASS | :103 `.denyAll()` | SecurityConfigTest#m2mEndpointIsProtected(익명만) | 동상 |
| TC-AUTHZ-008 | 포털 API 는 채널+역할 둘 다 | PASS | :116-117 `allOf(ROLE_PORTAL_USER, CHANNEL_PORTAL)` — AuthorizationManagers.allOf(:182-189) AND 결합 | SecurityConfigChannelTest#portalChannelAllowedOnPortal | |
| TC-AUTHZ-009 | INTERNAL 채널로 포털 API 거부 | PASS | :116-117 CHANNEL_PORTAL 미보유 → 거부 | SecurityConfigChannelTest#internalChannelForbiddenOnPortal | |
| TC-AUTHZ-010 | PORTAL 채널로 내부 /v1/** 거부 | PASS | :121-122 `hasAuthority(CHANNEL_INTERNAL)` | SecurityConfigChannelTest#portalChannelForbiddenOnInternalVideos, #portalChannelForbiddenOnManage | **예외 존재**: :108 GET `/v1/manage/labels/**` 는 채널 무관 `authenticated()` → PORTAL 토큰 통과. 의도된 설계(주석 :104-107)이나 케이스 문구의 "전건 거부"와는 불일치 → [A-ISSUE-03] |
| TC-AUTHZ-011 | /v1/notices REVIEWER/WORKER만 | PASS | :114 `hasAnyRole(REVIEWER, WORKER)` 가 :121 `/v1/**` 보다 먼저 배치 → PORTAL_USER 403 | NoticeControllerTest#PORTAL_USER가_공지_목록_조회시_403 | |
| TC-AUTHZ-012 | /v1/dev/tokens dev-login 활성 시만 permitAll | PASS | :52-53 프로퍼티 판정, :85-95 조건부 permitAll 매처(:98 REVIEWER 가드보다 앞) | SwaggerAuthIntegrationTest#devTokenIssueReturns201WithToken | **확인필요 → [A-ISSUE-05]**: dev/stg 프로파일에서 enabled=true 라 미인증 REVIEWER 토큰 발급 가능 |
| TC-AUTHZ-013 | 비활성 시 /v1/dev/** fail-closed | PASS | 기본값 `false`(:53, application.yml:232 `${DEV_LOGIN_ENABLED:false}`), prd 미override → 매처 부재 + :98 REVIEWER 가드 + `@ConditionalOnProperty`(DevTokenController:34, DevTokenService:41) 빈 부재 3중 차단 | 없음(직접) | |
| TC-AUTHZ-014 | /v1/dev/**(tokens 외) REVIEWER만 | PASS | :98 `hasRole(REVIEWER)` — /v1/dev/autolabel-test 포함 | 없음(직접) | |
| TC-AUTHZ-015 | /v1/auth/role-claim authenticated | PASS | :71 이 :77 `/v1/auth/**` permitAll 보다 **먼저** 등록 → 우선 매칭 | 없음 (RoleClaimServiceTest 는 서비스 레이어) | 매처 순서 역전 회귀 방지 테스트 부재(HIGH 케이스인데 미커버) |
| TC-AUTHZ-016 | /v1/auth/**, /v1/portal/auth/** permitAll | PASS | :77 — `/v1/portal/auth/**` 가 :116 `/v1/portal/**` 보다 먼저라 채널 요구 미적용 | 없음 | |
| TC-AUTHZ-017 | swagger/api-docs permitAll | PASS | :74-76 `/swagger-ui.html`,`/swagger-ui/**`,`/v3/api-docs/**` | SwaggerAuthIntegrationTest#swaggerUiHtmlIsAccessibleWithoutAuth, #openApiDocExposesBearerAuthScheme | |
| TC-AUTHZ-018 | 인증 실패 표준 JSON(UNAUTHORIZED) | PASS | :126 entryPoint → :191-196 writeError(401, JSON, UTF-8) + ApiResponse.error → `errorCode="UNAUTHORIZED"`(ApiResponse.java:22-23, ErrorCode:7) | JwtAuthenticationFilterTest#noJwtReturns401 | 스택트레이스·내부경로 미포함 확인 |
| TC-AUTHZ-019 | 인가 실패 표준 JSON(FORBIDDEN) | PASS | :127 accessDeniedHandler → :191-196, ErrorCode:8 FORBIDDEN | SecurityConfigRoleTest#workerForbiddenOnManage 등 | |
| TC-AUTHZ-020 | 세션 STATELESS | PASS | :60 `SessionCreationPolicy.STATELESS` | 없음 | JSESSIONID 부재 단언 테스트 없음 |
| TC-AUTHZ-021 | 보안 헤더 3종 | PARTIAL | :61-67 contentTypeOptions/frameOptions.deny 는 무조건 부착. **HSTS 는 Spring Security 기본 `SecureRequestMatcher` 로 HTTPS 요청에만 부착** → 평문 HTTP 응답엔 미부착 | SecurityConfigTest#hstsHeaderPresentOnSecureRequest (`.secure(true)` 필수) | → [A-ISSUE-06] (기대문구 "임의 응답에 3개 존재"는 성립 안 함) |
| TC-AUTHZ-022 | CSRF/formLogin/httpBasic 비활성 | PASS | :56,58,59 각각 `AbstractHttpConfigurer::disable` | 없음 | STATELESS + Bearer 전용이라 CSRF 비활성 타당 |
| TC-AUTHZ-023 | 매처 순서(labels GET 이 REVIEWER 매처보다 앞) | PASS | :107 주석 + :108(GET labels) → :109(manage/** REVIEWER) 순서 확인 | LabelMasterControllerTest#getLabels_WORKER_200 (순서 역전 시 403으로 실패 → 사실상 회귀 감지) | |
| TC-CORS-001 | origin 미설정 시 외부 origin 차단 | PASS | :147 기본값 `""` → :154-156 `setAllowedOrigins(List.of())` → 모든 Origin 불허 | 없음 | CORS 관련 테스트 0건 (Grep: Access-Control-Allow 단언 없음) |
| TC-CORS-002 | 콤마 목록 trim | PASS | :149-153 `split(",").map(trim).filter(!isEmpty)` | 없음 | |
| TC-CORS-003 | 메서드/헤더/노출헤더/credentials | PASS | :157-169 메서드 6종·요청헤더 8종·노출헤더 8종 명시 + `setAllowCredentials(true)`. `*` 미사용이라 credentials 와 충돌 없음 | 없음 | allowedHeaders 가 allowlist 라 신규 커스텀 헤더 추가 시 preflight 실패 주의 |
| TC-ROLE-001 | RoleHierarchy 빈 계층 | PASS(사실기록)·확인필요 | RoleHierarchy.java:11-13 `RoleHierarchyImpl.fromHierarchy("")` — 상속 관계 0건. 추가로 이 빈을 참조/주입하는 코드가 **전 소스에 0건**(Grep) 이라 `authorizeHttpRequests.hasRole()` 판정에도 미적용 → 사실상 no-op 빈 | 없음 | UNCERTAINTIES #6 — 정책 확정 필요 → [A-ISSUE-07] |
| TC-ROLE-002 | Role/Channel enum 정의값만 | PASS | Role.java:3-7 (REVIEWER/WORKER/PORTAL_USER), Channel.java:3-6 (INTERNAL/PORTAL). valueOf 미정의값은 IllegalArgumentException → filter:112 에서 무권한 처리 | JwtAuthenticationFilterTest#invalidChannelClaimReturns401 | |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [A-ISSUE-01] TC-AUTH-027 — `@Cacheable` 이 null 가드보다 먼저 평가돼 `resolve(null)` 이 NPE 경로
- **심각도**: LOW
- **기대 동작(기대효과)**: `UserRoleResolver.resolve(null)` 은 어떤 호출 경로에서도 예외 없이 `null`(무권한)을 반환해야 한다. fail-closed 계약이 방어적으로 성립해야 향후 호출자가 추가돼도 안전.
- **현재 동작(이슈 내용)**: 스프링 프록시를 통한 호출에서는 `@Cacheable(key = "#userNo")` 가 먼저 평가돼 캐시 키가 `null` 이 되고, Caffeine 은 null 키를 허용하지 않으므로 NPE 가 발생해 메서드 본문의 null 가드에 도달하지 못한다.
  ```java
  // UserRoleResolver.java:46-50
  @Cacheable(cacheNames = CacheConfig.CACHE_USER_ROLE, key = "#userNo", unless = "#result == null")
  public Role resolve(Long userNo) {
      if (userNo == null) {   // ← 프록시 경유 시 도달 불가
          return null;
  ```
  기존 테스트는 `new UserRoleResolver(repository)`(UserRoleResolverTest.java:29-32)로 **프록시 없이** 호출하므로 이 차이를 잡아내지 못한다.
- **재현/확인 경로**: 스프링 컨텍스트에서 주입받은 `UserRoleResolver` 빈에 `resolve(null)` 호출. 현재 프로덕션 유일 호출자(JwtAuthenticationFilter:82-83)는 `parseUserNo` 로 null 을 선차단하므로 **실경로 미도달**.
- **영향**: 현재 노출 없음. 향후 다른 호출자가 null 을 넘기면 필터 catch 범위(JwtException|IllegalArgumentException) 밖의 NPE → 500. 권한 상승은 아니라 보안 영향은 없음.
- **수정 방향(제안)**: ① `condition = "#userNo != null"` 를 `@Cacheable` 에 추가하거나, ② null 가드를 호출자 계약으로 문서화하고 파라미터를 `@NonNull` 로 명시. 겸하여 프록시 경유 테스트(@SpringBootTest) 1건 추가.

### [A-ISSUE-02] TC-AUTH-019 — role=null(LS 미배정) INTERNAL 사용자가 내부 조회 API 전건 통과
- **심각도**: MEDIUM (확인필요 — 의도된 설계일 가능성 높음)
- **기대 동작(기대효과)**: LS_USER_ROLE 에 역할이 배정되지 않은 내부 사용자는 "무권한" 이어야 하며, 최소한 학습데이터/영상/라벨 등 업무 데이터 조회는 차단되는 것이 fail-closed 원칙에 부합.
- **현재 동작(이슈 내용)**: role 이 null 이어도 채널 authority 는 무조건 부여된다.
  ```java
  // JwtAuthenticationFilter.java:99-103
  List<SimpleGrantedAuthority> authorities = new ArrayList<>();
  if (role != null) { authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name())); }
  authorities.add(new SimpleGrantedAuthority("CHANNEL_" + channel.name()));
  ```
  그리고 SecurityConfig.java:121-122 의 포괄 매처는 `hasAuthority("CHANNEL_INTERNAL")` 만 요구한다. 결과적으로 역할 미배정 사용자도 `GET /v1/videos`, `/v1/labels`, `/v1/frames`, `/v1/reviews` … 조회를 수행할 수 있다. 이 상태는 이미 인지되어 있으며(WriteEndpointAuthorizationGuardTest 클래스 javadoc), **쓰기(POST/PUT/PATCH/DELETE)만** `@PreAuthorize` 아키텍처 테스트로 강제되고 읽기는 강제 대상이 아니다.
- **재현/확인 경로**: 유효 서명·허용 issuer·`sub=<LS_USER_ROLE 미등록 userNo>` 토큰으로 `GET /v1/videos` 호출 → 200.
- **영향**: 관제 SSO 로 토큰을 받을 수 있는 모든 내부 계정(저작도구 미배정 포함)이 라벨링 대상 영상 메타·프레임 목록을 조회 가능. 비식별 영상 스트림/이미지 서빙 경로까지 영향 범위인지 별도 확인 필요.
- **수정 방향(제안)**: 정책 확정 후 ① `/v1/**` 매처를 `hasAnyRole(REVIEWER, WORKER)` + 채널 결합(`allOf`)으로 상향하거나, ② baseline 역할(예: `ROLE_ASSIGNED`) 개념을 도입. 어느 쪽이든 permitAll 목록·포털 경로 예외를 함께 재검토. (본 검증 범위에서는 구현하지 않음)

### [A-ISSUE-03] TC-AUTHZ-010 / TC-AUTHZ-002 — GET `/v1/manage/labels/**` 가 채널 격리 예외
- **심각도**: LOW (확인필요)
- **기대 동작(기대효과)**: "PORTAL 채널 토큰은 내부 `/v1/**` 에 접근 불가" 라는 채널 격리 불변식이 예외 없이 성립.
- **현재 동작(이슈 내용)**:
  ```java
  // SecurityConfig.java:108
  .requestMatchers(HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  ```
  `authenticated()` 는 채널·역할을 모두 보지 않으므로 CHANNEL_PORTAL 토큰과 role=null INTERNAL 토큰 모두 통과한다. 주석(:104-106)에 "라벨 마스터 조회는 WORKER/PORTAL_USER 도 허용"이라 명시되어 **의도된 예외**로 보인다.
- **재현/확인 경로**: PORTAL 채널 토큰으로 `GET /v1/manage/labels` → 200 (기존 테스트 `portalChannelForbiddenOnManage` 는 `/v1/manage/test` 를 쓰므로 이 예외를 못 잡음).
- **영향**: 노출 데이터는 라벨 클래스 마스터(라벨명/색상/형태)로 민감도 낮음. 다만 문서(CLAUDE.md "포털은 데이터마트 영상 선택 + 본인 자산 업로드 전용")와 대조 시 노출 범위 확인 필요.
- **수정 방향(제안)**: 의도라면 테스트 케이스 TC-AUTHZ-010 문구에 예외를 명시하고 포털 전용 라벨 마스터 조회 엔드포인트 분리를 검토. 의도가 아니면 `.access(allOf(...))` 로 채널 결합.

### [A-ISSUE-04] TC-AUTH-021 — StreamSignatureFilter javadoc 의 필터 등록 순서 서술이 실제와 반대
- **심각도**: LOW (문서 드리프트, 동작 정상)
- **기대 동작(기대효과)**: 소스 주석이 실제 필터 체인 순서와 일치해야 후속 변경 시 오판을 막는다.
- **현재 동작(이슈 내용)**: `StreamSignatureFilter.java:24` — "JwtAuthenticationFilter 보다 앞에 등록되며". 실제 등록은 뒤:
  ```java
  // SecurityConfig.java:133-135
  // 영상 스트림 단기 서명 URL 인증 — JWT 필터 뒤에 두어 …
  .addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class);
  ```
- **재현/확인 경로**: 두 파일 대조.
- **영향**: 기능 영향 없음(실제 동작은 SecurityConfig 주석대로 Authorization 헤더 우선). 유지보수 오해 위험만.
- **수정 방향(제안)**: StreamSignatureFilter 클래스 javadoc 문구를 "JwtAuthenticationFilter 뒤에 등록"으로 정정.

### [A-ISSUE-05] TC-AUTHZ-012 — dev 토큰 발급 엔드포인트가 dev/stg 에서 미인증 permitAll (임의 userNo 지정 가능)
- **심각도**: MEDIUM (확인필요 — 운영 노출 범위에 따라 HIGH)
- **기대 동작(기대효과)**: 인증 우회로 임의 권한 토큰을 만들 수 있는 경로는 접근 통제된 환경에서만 노출되어야 한다.
- **현재 동작(이슈 내용)**: `authoring.dev.login.enabled=true` 이면 `/v1/dev/tokens` 가 permitAll 이고(SecurityConfig:85-95), 컨트롤러는 인증 없이 임의 role/channel/userNo 로 서명 토큰을 발급한다.
  ```java
  // DevTokenService.java:51-53
  private static final String DEFAULT_USER_NO_REVIEWER = "1001";   // dev-seed 의 REVIEWER
  ...
  String userNo = resolveUserNo(req.userNo(), role);  // 요청자가 sub 를 임의 지정 가능
  ```
  INTERNAL 채널의 실제 인가 역할은 LS_USER_ROLE 에서 오므로, `sub=1001`(시드 REVIEWER)로 발급하면 **미인증 사용자가 REVIEWER 권한**을 획득한다. 활성 프로파일: local(application-local.yml:80-81), dev(application-dev.yml:24-25), **stg(application-stg.yml:24-25)**. prd 는 미설정 → false.
- **재현/확인 경로**: stg/dev 에 네트워크 도달 가능한 클라이언트에서 `POST /v1/dev/tokens {"role":"REVIEWER","channel":"INTERNAL","userNo":"1001"}` → 201 + 토큰 → 그 토큰으로 `/v1/manage/**` 접근.
- **영향**: stg = 온프렘 개발서버(메모리 `env-deploy-mapping`)라면 사내망 한정이라도 인증 우회 경로. prd 는 영향 없음.
- **수정 방향(제안)**: ① stg 에서 `authoring.dev.login.enabled=false` 로 내리거나, ② 발급 대상을 고정 시드 계정으로 제한 + IP allowlist/사전공유 헤더 추가. 정책 결정 필요(구현은 본 검증 범위 밖).

### [A-ISSUE-06] TC-AUTHZ-021 — HSTS 헤더는 HTTPS 요청에만 부착됨 (기대문구와 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: 테스트 케이스는 "임의 응답에 X-Frame-Options / X-Content-Type-Options / Strict-Transport-Security 3개 헤더 존재"를 단언.
- **현재 동작(이슈 내용)**: SecurityConfig.java:64-66 은 HSTS 를 설정하지만 Spring Security `HstsHeaderWriter` 의 기본 requestMatcher 가 secure 요청 한정이라 평문 HTTP 응답에는 헤더가 없다. 기존 테스트도 이를 전제로 `.secure(true)` 를 명시한다.
  ```java
  // SecurityConfigTest.java:49-51
  mockMvc.perform(get("/health").secure(true))
  ```
- **재현/확인 경로**: `.secure(true)` 없이 MockMvc 요청 → `Strict-Transport-Security` null.
- **영향**: 운영은 TLS 종단(리버스 프록시) 뒤이므로 실제 브라우저 응답에 헤더가 붙는지는 프록시의 `X-Forwarded-Proto` 전달 + `server.forward-headers-strategy` 설정에 의존 → **런타임 확인 필요(BLOCKED 성격)**.
- **수정 방향(제안)**: 테스트 케이스 기대문구를 "HTTPS 요청 시 3종" 으로 정정하고, 별도로 프록시 환경에서 `forward-headers-strategy=framework` 여부를 점검(설정 부재 시 HSTS 미부착 가능).

### [A-ISSUE-07] TC-ROLE-001 — RoleHierarchy 빈이 빈 문자열 계층 + 어디에도 미배선
- **심각도**: LOW (확인필요 — UNCERTAINTIES #6)
- **기대 동작(기대효과)**: 역할 상속 정책이 의도적으로 "없음"이라면 그 사실이 명문화되어야 하고, 빈이 존재한다면 실제 인가 판정에 연결되어야 한다.
- **현재 동작(이슈 내용)**:
  ```java
  // RoleHierarchy.java:11-13
  public RoleHierarchy authoringRoleHierarchy() {
      return RoleHierarchyImpl.fromHierarchy("");
  }
  ```
  계층 문자열이 비어 있어 REVIEWER 가 WORKER 권한을 상속하지 않는다(그래서 SecurityConfig:114 처럼 `hasAnyRole(REVIEWER, WORKER)` 로 매번 나열). 또한 이 빈을 참조하는 코드가 main/test 전체에 0건이라 `authorizeHttpRequests().hasRole()` 판정에도 관여하지 않는다(사실상 no-op).
- **재현/확인 경로**: Grep `RoleHierarchy` → 정의 파일 외 참조 0건. 역할 나열 매처(:114) 존재가 상속 부재의 방증.
- **영향**: 현재 인가 동작에 영향 없음. 향후 계층 문자열만 채우면 "설정했는데 안 먹는" 혼선 위험.
- **수정 방향(제안)**: 정책 확정 후 ① 상속 불필요면 빈 제거 + 나열 방식 문서화, ② 상속 필요면 계층 문자열 정의 + `AuthorityAuthorizationManager`/`MethodSecurityExpressionHandler` 에 명시 배선 및 회귀 테스트 추가.

---

## 요약

- **총 60건 / PASS 58 / FAIL 0 / PARTIAL 2 / BLOCKED 0 / N/A 0**
  - PARTIAL: TC-AUTH-027(캐시 null 키), TC-AUTHZ-021(HSTS HTTPS 한정)
  - 확인필요(판정은 PASS 이나 정책 확정 필요): TC-AUTH-019, TC-AUTHZ-002/010, TC-AUTHZ-012, TC-ROLE-001
- **근거 라인 드리프트: 0건** — A-1/A-2 전 케이스의 `근거(file:line)` 가 현재 소스와 일치(JwtAuthenticationFilter, JwtIssuerValidator, UserRoleResolver, SecretKeyResolver, CacheConfig, TokenClaims, SessionController, SecurityConfig, RoleHierarchy, Role, Channel, StreamSignatureFilter 전부 확인).
  - 단, **소스 주석 드리프트 1건**: StreamSignatureFilter.java:24 필터 등록 순서 서술 ↔ SecurityConfig.java:135 실제 등록 ([A-ISSUE-04]).
- **테스트 baseline**: 미생성 → 실행 결과 미반영(정적 판정). 아래는 자동 테스트가 **전혀 없는** 케이스(회귀 취약):
  - TC-AUTH-003/006/007/011/016(비숫자 sub)/020/021/022/032, TC-COMMON-001/002
  - TC-AUTHZ-003/013/014/015/016/020/022, TC-CORS-001/002/003, TC-ROLE-001
  - 특히 우선순위 H 이면서 미커버: TC-AUTH-006(alg=none), TC-AUTH-007(malformed), TC-AUTHZ-013(dev fail-closed), TC-AUTHZ-014, TC-AUTHZ-015(role-claim 매처 순서), TC-CORS-001
