# A. 인증/권한 + 공통 인프라 — 1차 검증 결과

> 검증일 2026-07-25 · 기준: 정적 코드 대조 + 자동테스트 baseline (스택 기동 전 선행 검증)
> ⚠ 이후 확정된 '실동작 기준'에 따라 런타임 의존 항목은 스택 기동 후 보완 검증 대상

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
## A-6. 추적ID·공통 응답·예외

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-TRACE-001 | X-Trace-Id 없으면 12자리 생성해 MDC/응답 설정 | PASS | RequestIdFilter.java:28-33 일치 | RequestIdFilterTest#generatesNewTraceId | - |
| TC-TRACE-002 | 제공 traceId CRLF 제거(CWE-113) | PASS | RequestIdFilter.java:48-58(sanitize, 종료 `}`는 59) 일치 | RequestIdFilterTest#sanitizesCrlfInjection | 근거 1줄 오차(58→실제 58 endpoint, 59 닫는 괄호) — 무시 가능 |
| TC-TRACE-003 | 영숫자·하이픈 외 제거+64자 절단 | PASS | RequestIdFilter.java:52-57 일치 | RequestIdFilterTest#truncatesOversizedTraceId | - |
| TC-TRACE-004 | 요청 종료 후 MDC 제거(스레드 누수 방지) | PASS | RequestIdFilter.java:36-38 일치 | RequestIdFilterTest#generatesNewTraceId(MDC null 단언 포함) | - |
| TC-TRACE-005 | RequestIdFilter 최우선순위 | PASS | RequestIdFilter.java:17 `@Order(Ordered.HIGHEST_PRECEDENCE)` 일치 | 전용 테스트 없음(정적 확인) | - |
| TC-RESP-001 | ApiResponse.ok success=true,errorCode=null | PASS | ApiResponse.java:9-11 일치 | 전용 단위테스트 없음, 다수 컨트롤러 테스트에서 간접 검증 | - |
| TC-RESP-002 | ApiResponse.error(code) success=false+name()+기본msg | PASS | ApiResponse.java:21-23 일치 | SystemConfigControllerTest#workerCannotUpdateConfig 등 다수 | - |
| TC-RESP-003 | error(code,message,data)로 부가 data 포함 | PASS | ApiResponse.java:29-31 일치, GlobalExceptionHandler.java:28-32 details!=null 분기 | 전용 테스트 없음(간접 추정) | data 파라미터 사용 실사례(NOT_REVIEWED 등)는 A-6 범위 밖 컨트롤러 |
| TC-RESP-004 | JsonInclude.ALWAYS null 필드도 직렬화 | PASS | ApiResponse.java:6 일치, 전역 Jackson 설정 충돌 없음 확인 | 직접 직렬화 단위테스트 없음(정적 확인) | 전역 ObjectMapper에 SerializationInclusion 재정의 없음 확인 완료 |
| TC-EXC-001 | CustomException이 ErrorCode status/message 매핑 | PASS | GlobalExceptionHandler.java:24-33 일치 | GlobalExceptionHandlerTest#customExceptionReturnsErrorCode | - |
| TC-EXC-002 | details 있으면 응답 data 포함 | PASS | GlobalExceptionHandler.java:28-32 일치 | 전용 테스트 없음(간접 추정) | - |
| TC-EXC-003 | @Valid 바디 실패 400+필드별 메시지 | PASS | GlobalExceptionHandler.java:35-43 일치 | GlobalExceptionHandlerTest#validationFailureReturnsInvalidInput | - |
| TC-EXC-004 | ConstraintViolation 400 정규화 | PASS | GlobalExceptionHandler.java:49-57 일치 | 전용 테스트 없음(코드 확인, 단순 매핑) | 커버리지 갭 — 회귀 시 조기 발견 어려움 |
| TC-EXC-005 | 잘못된 JSON/enum 역직렬화 400(파서상세 비노출) | PASS | GlobalExceptionHandler.java:64-69 일치, 로그도 클래스명만 기록(원문 비노출) | 전용 테스트 없음(코드 확인) | 로그도 getMostSpecificCause 클래스 simple name만 기록 — 이중 방어 양호 |
| TC-EXC-006 | 필수 RequestParam 누락 400 | PASS | GlobalExceptionHandler.java:76-81 일치 | 전용 테스트 없음(코드 확인) | 커버리지 갭 |
| TC-EXC-007 | 파라미터 타입 불일치 400(원문 비노출) | PASS | GlobalExceptionHandler.java:88-93 일치, 파라미터명만 노출·값/타입 비노출 확인 | 전용 테스트 없음(코드 확인) | 커버리지 갭 |
| TC-EXC-008 | 작업락 partial unique 위반만 409 | PASS | GlobalExceptionHandler.java:141-145,168-171 일치 | GlobalExceptionHandlerDataIntegrityTest#dataIntegrityBranching(work_lock 케이스) | - |
| TC-EXC-009 | 프리셋 labelId/라벨명 CI/검출유형 유니크 위반 각 409 | PASS | GlobalExceptionHandler.java:146-160 일치(3개 분기 모두 확인) | GlobalExceptionHandlerDataIntegrityTest#dataIntegrityBranching(preset/label_name_ci 케이스) — 검출유형(dtctType) 분기는 코드 확인만, 전용 파라미터화 케이스 없음 | isLabelDtctTypeUnique 분기 자체 단위테스트 부재(경미) |
| TC-EXC-010 | 판별 불가 무결성 위반은 500 노출(fail-closed) | PASS | GlobalExceptionHandler.java:161-166 일치 | GlobalExceptionHandlerDataIntegrityTest#dataIntegrityBranching(FK위반/제약명null 케이스) | fail-closed 정책 실증 — 조용한 409 흡수 없음 확인 |
| TC-EXC-011 | 제약명 Hibernate→SQL 메시지 순 추출 | PASS | GlobalExceptionHandler.java:193-209 일치 | GlobalExceptionHandlerDataIntegrityTest(제약명 있는/null 케이스 모두 커버) | - |
| TC-EXC-012 | 무결성 위반 응답에 제약명/SQL/스택 미노출 | PASS | GlobalExceptionHandler.java:135-136(정책 주석, 실제 강제 코드는 138-166) | GlobalExceptionHandlerDataIntegrityTest#responseHasNoConstraintNameOrSql(제약명/SQL/SQLState/원문 미노출 전수 검증) | 근거 라인이 주석(정책 설명)이라 실제 강제 코드(138-166)와 분리 — 경미한 근거 위치 드리프트 |
| TC-EXC-013 | AccessDeniedException 403 매핑 | PASS | GlobalExceptionHandler.java:211-216 일치 | 전용 테스트 없음(코드 확인, 단순 매핑) | - |
| TC-EXC-014 | AuthenticationException 401 매핑 | PASS | GlobalExceptionHandler.java:218-223 일치 | 전용 테스트 없음(코드 확인, 단순 매핑) | - |
| TC-EXC-015 | 미매핑 경로(NoResourceFound) 404 정규화 | PASS | GlobalExceptionHandler.java:241-246 일치 | GlobalExceptionHandlerTest#noResourceFoundReturnsNotFound | - |
| TC-EXC-016 | multipart 크기 초과 413 정규화 | PASS | GlobalExceptionHandler.java:234-239 일치 | MaxUploadSizeExceededMappingTest#MaxUploadSizeExceededException_표준_응답_매핑 | - |
| TC-EXC-017 | 미처리 Exception 500+고정메시지(스택 비노출) | PASS | GlobalExceptionHandler.java:248-253 일치, log.error에는 스택 기록(서버 로그 한정)·응답 body는 고정 메시지만 | GlobalExceptionHandlerTest#unhandledExceptionReturnsInternalError | 응답과 서버 로그 분리 확인 — 스택은 로그 파일에만, 클라이언트 응답엔 미노출 |

## A-7. 듀얼 DS·캐시·시스템설정·프로파일·Actuator·Resilience4j

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-DS-001 | Control DataSource/EMF/TxManager @Primary | PASS | ControlDataSourceConfig.java:31-56 일치(31,38,50 각 @Primary) | 전용 단위테스트 없음, IT 다수가 control TxManager로 간접 구동(예: LsLabelPresetRepositoryTest 등) | - |
| TC-DS-002 | @ControlRepo만 control EMF 라우팅 | PASS | ControlDataSourceConfig.java:19-28(@Configuration+@EnableJpaRepositories, includeFilter=ControlRepo) 일치 | 다수 control 레포지토리 IT 간접 커버 | 저장소 58개 전수 grep 결과 @ControlRepo/@PortalRepo 누락 0건(잘못된 EM 주입 여지 없음, fail-closed 구조) |
| TC-DS-003 | @PortalRepo는 portal EMF 라우팅(비-Primary) | PASS | PortalDataSourceConfig.java:18-27 일치, @Primary 없음 확인 | LsPortalUldRepositoryIT 등 간접 커버 | - |
| TC-DS-004 | 서비스 Tx가 controlTransactionManager 바인딩 | PASS | SystemConfigService.java:38,91 일치 | SystemConfigControllerTest(update/getInt 왕복으로 간접 실증) | - |
| TC-CACHE-001 | 4개 캐시 개별 TTL 등록 | PASS | CacheConfig.java:47-76 일치(60s/5m/6h/60s 값 전부 확인) | 전용 단위테스트 없음(정적 확인) | - |
| TC-CACHE-002 | sysconfig getInt/getString 60s 캐시 | PARTIAL | SystemConfigService.java:47-48 `@Cacheable` 존재 확인 | SystemConfigControllerTest#reviewerUpdatesAndCacheInvalidates(무효화 후 신규값 반영만 검증) | 캐시 "적중"(2번째 호출이 DB 재조회 안 함) 자체를 증명하는 테스트 없음 — repository mock 호출횟수 검증 부재. `@EnableCaching`+어노테이션 존재로 정상 동작 개연성 높으나 회귀(예: self-invocation, 프록시 미적용) 조기 발견 못 함 → A-ISSUE-22 |
| TC-CACHE-003 | update 시 sysconfig 캐시 전체 무효화 | PASS | SystemConfigService.java:90-91 일치 | SystemConfigControllerTest#reviewerUpdatesAndCacheInvalidates | 실제 DB 갱신 후 getInt 재조회로 신규값 확인 — 실질 검증 |
| TC-CACHE-004 | userRole null 결과 미저장 | PASS(근거 드리프트) | 근거로 지목된 CacheConfig.java:32-35는 **주석**(설명)이며, 실제 강제 코드는 `UserRoleResolver.java:46` `@Cacheable(..., unless="#result == null")` | UserRoleResolverTest(LS_역할없는_사용자는_무권한_null 등), UserRoleCacheEvictIT | 근거 파일 위치 드리프트(카탈로그가 comment만 인용, 실제 어노테이션은 별도 클래스) — 동작 자체는 정상, fail-closed(DataAccessException도 null) 확인 |
| TC-SYSCFG-001 | 화이트리스트 외 키 조회/갱신 거부 | PASS | SystemConfigService.java:93-98 일치, CWE-117 방어 주석 확인(사용자 입력 키 미노출) | SystemConfigControllerTest#SystemConfig_화이트리스트_외_키_업데이트시_INVALID_INPUT | 클래스 상단 주석("화이트리스트 4개 키")은 ConfigKeys.ALLOWED 실제 8개와 불일치(문서 drift, 동작에는 영향 없음) |
| TC-SYSCFG-002 | REVIEWER 아니면 update FORBIDDEN(이중 검증) | PASS | SystemConfigService.java:166-170 일치 | SystemConfigControllerTest#workerCannotUpdateConfig | - |
| TC-SYSCFG-003 | actor=null이면 update FORBIDDEN | PASS | SystemConfigService.java:166-169 일치(`actor == null \|\| actor.role() != Role.REVIEWER`) | 전용 테스트 없음(코드 확인만) | 인증 우회 방어 핵심 분기인데 자동 회귀테스트 부재 → A-ISSUE-23에 포함 |
| TC-SYSCFG-004 | NUMBER 키 정수+키별 범위 검증 | PASS | SystemConfigService.java:132-145 일치 | SystemConfigControllerTest#SystemConfig_BATCH_CONCURRENCY_범위_초과시_INVALID_INPUT_400 | - |
| TC-SYSCFG-005 | DECIMAL NaN/Infinity/범위밖 거부 | PASS(부분 커버) | SystemConfigService.java:147-164 일치, isNaN/isInfinite 명시 체크 확인 | PolygonSimplifyConfigTest(범위/비숫자만 커버, NaN/Infinity 문자열 입력 테스트는 없음) | Double.parseDouble("NaN")은 예외 없이 NaN 반환하므로 isNaN 분기가 실질 방어선인데 이 경로 전용 테스트 부재 → A-ISSUE-23 |
| TC-SYSCFG-006 | BOOLEAN true/false만 | PASS | SystemConfigService.java:120-125 일치 | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-SYSCFG-007 | 미지원 CONFIG_TYPE 거부 | PASS | SystemConfigService.java:127-128 일치 | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-SYSCFG-008 | getInt 타입/값 불일치 각 예외 | PASS | SystemConfigService.java:49-61 일치(NUMBER 아니면 INVALID_INPUT, 파싱 실패시 INTERNAL_ERROR) | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-SYSCFG-009 | 없는 설정 키 NOT_FOUND | PASS | SystemConfigService.java:110-113 일치 | 전용 테스트 없음(코드 확인) | A-ISSUE-23에 포함 |
| TC-PROF-001 | local 프로파일이 ENV=prd에서 부팅 차단 | PARTIAL | LocalProfileGuard.java:30-43 일치(ENV dev/stg/prd + local profile → IllegalStateException) | **테스트 파일 자체 없음**(find 결과 0건) | System.getenv 모킹 라이브러리(system-stubs 등) 미도입 — CWE-798 방어 핵심 가드가 회귀 테스트 무방비 → A-ISSUE-21 |
| TC-PROF-002 | ENV 미설정/local이면 local 허용 | PARTIAL | LocalProfileGuard.java:34-37 일치 | 테스트 없음 | A-ISSUE-21에 포함 |
| TC-PROF-003 | local 아니면 즉시 통과 | PARTIAL | LocalProfileGuard.java:31-32 일치 | 테스트 없음 | A-ISSUE-21에 포함 |
| TC-ACT-001 | prd는 actuator health만 노출 | PASS | application-prd.yml:39-43 일치(include: health, show-details: never) | 설정 파일 정적 검증(부팅 테스트로 exposure 확인하는 전용 테스트는 없음) | - |
| TC-ACT-002 | 기본은 ACTUATOR_EXPOSE로 노출 제어 | PASS | application.yml:90-94 일치 | 정적 검증 | - |
| TC-RES-001 | 외부 호출 Retry+CircuitBreaker 순차 적용 | PASS | AiServerClient.java:46-47,63-64 일치(predictYolo/predictYoloTrack 모두 Retry→CB 순서) | AiServerClientTest#AiServerClient_타임아웃_시_CircuitBreaker_OPEN_전환 | 나머지 2개 메서드(segment/track, 67-76·78-87)도 동일 패턴이나 전용 언급은 predictYolo류만 카탈로그에 인용 |
| TC-RES-002 | 5개 CircuitBreaker 빈 등록 | PASS | Resilience4jConfig.java:13-47 일치(deid/ai/vlmClient/controlNotify/kpstDeid 정확히 5개) | 전용 빈카운트 테스트 없음(정적 확인) | - |
| TC-RES-003 | aiOnline/portalSam2 Bulkhead 초과 시 429 | PASS | Resilience4jConfig.java:55-69(빈 등록만) — 실제 429 매핑은 AutolabelOnlineService.java:450,480 및 PortalSam2Service.java:192 catch(BulkheadFullException) | AutolabelPolygonServiceTest#폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다 | 근거 라인이 빈 등록 지점(Resilience4jConfig)만 가리키고 실제 429 전환 로직은 별도 서비스 클래스 — 근거 위치 드리프트(동작은 정상, 테스트로 실증됨) |
| TC-RES-004 | NonRetryableExternalException 재시도/CB 제외 | PASS | NonRetryableExternalException.java:23-30(마커 클래스 정의) — 실제 제외 설정은 application.yml:290-291,305-306,322-323,333-334 `ignore-exceptions` | KpstDeidentifyClientTest(K3_createProject_409/400 재시도없이 등), VlmClientTest(V1_400/422 재시도없이) | 근거가 클래스 정의만 가리키고 실제 제외 메커니즘은 yml 설정 — 근거 위치 드리프트. "ai"/"deid" CB 인스턴스에는 ignore-exceptions 없음(해당 클라이언트가 이 예외를 던지지 않으므로 정상) |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [A-ISSUE-21] TC-PROF-001~003 — LocalProfileGuard 자동 회귀 테스트 전무
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ENV=dev/stg/prd` 환경에서 `spring.profiles.active=local`로 잘못 배포되면 부팅을 즉시 거부해(CWE-798 fallback 비밀번호 노출 차단) 운영 환경에 개발용 기본값이 노출되는 사고를 막아야 한다. 이 가드는 배포 안전의 마지막 방어선이므로 회귀 시 조기 발견이 중요하다.
- **현재 동작(이슈 내용)**: 코드(`LocalProfileGuard.java:29-43`)는 올바르게 구현되어 있으나, `find src/test -iname "*LocalProfileGuard*"` 결과 0건 — 전용 테스트 파일이 아예 없다. `build.gradle`에도 `System.getenv` 모킹용 라이브러리(system-stubs, junit-pioneer 등)가 없어 표준적인 JUnit 방식으로는 이 클래스를 테스트하기 어렵다.
  ```java
  @PostConstruct
  void verify() {
      if (!env.acceptsProfiles(Profiles.of("local"))) { return; }
      String envName = System.getenv("ENV");
      if (envName == null || envName.isBlank() || "local".equalsIgnoreCase(envName)) { return; }
      String lower = envName.toLowerCase();
      if (lower.equals("dev") || lower.equals("stg") || lower.equals("prd")) {
          throw new IllegalStateException(...);
      }
  }
  ```
- **재현/확인 경로**: 향후 리팩터링(예: 조건문 부호 실수, `equalsIgnoreCase` 제거 등)이 발생해도 CI가 이를 잡아내지 못하고 그대로 머지될 수 있다.
- **영향**: 보안 — CWE-798(하드코딩된 자격증명) 방어 가드의 무결성이 코드 리뷰에만 의존. 실제 결함은 아직 없음(정적 검토 결과 PASS)이나 회귀 방지망이 없다.
- **수정 방향(제안)**: `system-stubs-jupiter` 또는 리플렉션 기반 환경변수 오버라이드로 `LocalProfileGuardTest` 신설 — ENV=prd+active=local→예외, ENV=null+active=local→통과, active=dev→즉시 통과 3케이스 최소 커버.

### [A-ISSUE-22] TC-CACHE-002 — sysconfig 캐시 "적중" 자체를 증명하는 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: `SystemConfigService.getInt/getString`가 60초 TTL 캐시로 응답해 반복 호출 시 두 번째 이후 호출은 DB(Control DS)를 재조회하지 않아야 한다(핫 패스 가속 목적).
- **현재 동작(이슈 내용)**: `@Cacheable(cacheNames = "sysconfig", key = "'int:' + #key")` 어노테이션은 존재하나(`SystemConfigService.java:47-48`), 이를 검증하는 테스트는 `SystemConfigControllerTest#reviewerUpdatesAndCacheInvalidates` 하나뿐이며 이 테스트조차 "무효화 후 새 값 반영"만 확인할 뿐, "무효화 전 반복 호출이 DB를 재조회하지 않는지"는 검증하지 않는다.
- **재현/확인 경로**: repository를 스파이/목으로 감싸 `getInt` 2회 연속 호출 후 `verify(repository, times(1)).findByConfigKey(...)`으로 확인하는 테스트가 없어, 프록시 미적용(self-invocation)이나 캐시 키 설계 오류가 발생해도 테스트가 잡아내지 못한다.
- **영향**: 기능 성능 특성(캐시 가속) 회귀를 조기에 잡지 못함. 보안/데이터 정합성 영향은 없음.
- **수정 방향(제안)**: `@SpyBean LsSystemConfigRepository`로 감싸 `getInt` 연속 2회 호출 후 `verify(times(1))`로 캐시 히트를 직접 증명하는 단위/통합 테스트 추가.

### [A-ISSUE-23] TC-SYSCFG-003/005/006/007/008/009 — SystemConfigService 방어 분기 다수가 자동 테스트 커버리지 밖
- **심각도**: LOW
- **기대 동작(기대효과)**: actor null 거부, DECIMAL NaN/Infinity 거부, BOOLEAN true/false 외 거부, 미지원 CONFIG_TYPE 거부, getInt 타입/파싱 불일치 예외, 없는 키 NOT_FOUND — 이 6개 방어 분기 모두 회귀 시 CI가 잡아야 한다.
- **현재 동작(이슈 내용)**: 코드 검토 결과 6개 분기 모두 논리적으로 올바르다(예: `Double.isNaN(v) || Double.isInfinite(v)` 명시 체크 — `Double.parseDouble("NaN")`은 예외를 던지지 않고 NaN을 반환하므로 이 체크가 실제 방어선인데, `PolygonSimplifyConfigTest`는 범위초과·비숫자 문자열만 테스트하고 `"NaN"`/`"Infinity"` 리터럴 입력 케이스는 없다). actor==null 분기(`SystemConfigService.java:166-169`)도 `WORKER` 역할 거부 테스트만 있고 `actor=null` 자체를 넣는 테스트는 없다.
- **재현/확인 경로**: 각 분기에 대한 파라미터화 테스트 부재 상태에서 조건식이 변경돼도 실패하는 테스트가 없다.
- **영향**: 기능/보안 방어 분기의 회귀 방지망 공백. 현재 코드는 PASS.
- **수정 방향(제안)**: `SystemConfigServiceTest`(단위, Mock repository)를 신설해 6개 분기를 `@ParameterizedTest`로 묶어 커버.

### [A-ISSUE-24] MaskingPatternLayout 이 local 프로파일 로그 파이프라인에 실제로는 연결되어 있지 않음 (UNCERTAINTIES #7 확정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md는 "로그에 개인정보·토큰 출력 금지 (Logback MaskingPatternLayout)"이라 명시하며, 모든 환경에서 password/token/secret/Authorization 값이 마스킹되어야 한다.
- **현재 동작(이슈 내용)**: `MaskingPatternLayout` 클래스는 실존하며(`backend/src/main/java/kr/co/cudo/authoring/common/logging/MaskingPatternLayout.java`) `logback-spring.xml`에 `<conversionRule conversionWord="mask" converterClass="...MaskingPatternLayout"/>`로 등록돼 있지만, **local 프로파일 appender의 실제 `<pattern>`은 `%mask`가 아니라 `%msg`를 사용**한다.
  ```xml
  <springProfile name="local">
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
          <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
              <pattern>%d{...} [%thread] %-5level [%X{traceId:-}] %logger{36} - %msg%n</pattern>
          </encoder>
      </appender>
  ```
  즉 `MaskingPatternLayout`은 어떤 활성 패턴에서도 참조되지 않는 사실상 죽은 코드다. `MaskingPatternLayoutTest`는 `layout.mask(...)`를 직접 호출해 마스킹 로직 자체는 검증하지만, 이 레이아웃이 실제 로그 출력 경로에 연결됐는지는 검증하지 않아 "테스트는 통과하는데 실제로는 미적용"이라는 확증편향 함정이 존재한다. 반면 dev/stg/prd 프로파일은 `LoggingEventCompositeJsonEncoder`의 `valueMasker`로 별도 클래스 `MaskingJsonValueMasker`를 사용해 실제로 마스킹이 적용된다(이쪽은 정상).
- **재현/확인 경로**: local 프로파일로 기동 후 `password=1234`, `Authorization: Bearer xxx` 등을 포함한 로그를 남기면 콘솔에 평문 그대로 출력됨(코드 확인, 런타임 미실행 확인은 BLOCKED).
- **영향**: 보안 — CWE-532(로그 파일에 민감정보 삽입). local 프로파일은 `LocalProfileGuard`가 ENV=dev/stg/prd 배포를 차단하므로 실제 운영 노출 경로는 아니나, 로컬 콘솔 로그가 CI 아티팩트·화면 공유·로그 수집기로 유출될 경우 토큰/비밀번호가 평문 노출될 수 있다. 또한 CLAUDE.md의 서술(마스킹이 전 환경 적용된다는 전제)과 실제 구현이 불일치한다.
- **수정 방향(제안)**: (a) local 패턴도 `%mask` conversion word를 사용하도록 변경하거나, (b) `MaskingPatternLayout`을 `<layout class="...MaskingPatternLayout">` 형태의 실제 Layout으로 인코더에 연결하거나, (c) 의도적으로 local은 마스킹 생략이 맞다면 죽은 `conversionRule`/클래스를 정리하고 CLAUDE.md 서술을 "dev/stg/prd만 마스킹"으로 정정. 어느 쪽이든 현재는 "만들어놓고 안 쓰는" 상태라 문서-코드 불일치 해소가 필요.

## 요약
- 총 48건 / PASS 43 / FAIL 0 / PARTIAL 5 / BLOCKED 0 / N/A 0
- 근거 라인 드리프트: 4건(TC-CACHE-004, TC-EXC-012, TC-RES-003, TC-RES-004 — 모두 "정책/구성 근거가 실제 강제 코드와 다른 파일·다른 라인에 위치", 동작 자체는 정상으로 확인됨)
