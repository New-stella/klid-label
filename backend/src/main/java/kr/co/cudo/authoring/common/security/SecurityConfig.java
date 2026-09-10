package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.user.service.AutoWorkerRegistrar;
import kr.co.cudo.authoring.user.service.LastLoginRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtKeyResolver keyResolver;
    private final JwtIssuerValidator issuerValidator;
    private final UserRoleResolver userRoleResolver;
    /** 최종로그인일시 기록기 — JWT 필터가 INTERNAL 요청마다 호출한다(@design SCREEN-024). */
    private final LastLoginRecorder lastLoginRecorder;
    /** 진입 시 작업자 자동 등록기 — JWT 필터가 역할 없는 INTERNAL 요청에만 호출한다(@design AC-1016). */
    private final AutoWorkerRegistrar autoWorkerRegistrar;
    /**
     * 관제 인계 미등록 진입자 로컬 식별 레코드 발급기 — JWT 필터가 INTERNAL 채널 + 비숫자 sub +
     * userId 조회 0건일 때만 호출한다(@design ADR-063 · UC-041 · AC-1016).
     */
    private final kr.co.cudo.authoring.user.service.ControlUserProvisioner controlUserProvisioner;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HmacWebhookFilter hmacWebhookFilter;
    private final StreamSignatureFilter streamSignatureFilter;
    /**
     * 포털 업로드 영상 스트림의 단기 서명 인증 필터 — 재생 요소가 인증 헤더를 싣지 못하는 제약
     * 때문에 채널을 가리지 않고 필요하다(@design API-239).
     */
    private final kr.co.cudo.authoring.portal.config.PortalStreamSignatureFilter portalStreamSignatureFilter;
    /**
     * 포털 서버간 정리 트리거 창구의 사전 공유 키 인증 필터 (@design INT-014 · API-244).
     * 그 창구는 사람이 아니라 포털 서버가 부르므로 토큰이 아니라 키로 가른다.
     */
    private final PortalSystemApiKeyFilter portalSystemApiKeyFilter;
    /**
     * 채널 판정기 — <b>배포 향</b>이 인계 토큰의 채널을 확정한다 (@design ADR-012).
     * 운영 배선이 이 빈을 반드시 필터에 넘겨야 한다 — 넘기지 않으면 필터가 관제 향으로 굳는다.
     */
    private final kr.co.cudo.authoring.common.config.DeployFlavorResolver deployFlavorResolver;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    CorsConfigurationSource corsConfigurationSource,
                                                    org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy)
            throws Exception {
        JwtAuthenticationFilter jwtFilter =
                new JwtAuthenticationFilter(keyResolver, issuerValidator, userRoleResolver,
                        lastLoginRecorder, autoWorkerRegistrar, controlUserProvisioner,
                        deployFlavorResolver);

        // 개발/검수 전용 토큰 발급 endpoint — authoring.dev.login.enabled=true 일 때만 permitAll 매처 추가.
        // 판정 소스를 프로파일에서 프로퍼티로 교체(DevTokenController/Service 의 @ConditionalOnProperty 와 정합).
        // 기본 false(fail-closed) — 미설정/false 면 매처 부재 + 빈 부재로 endpoint 미노출.
        boolean devTokenEndpointEnabled =
                Boolean.parseBoolean(environment.getProperty("authoring.dev.login.enabled", "false"));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentTypeOptions(c -> {})
                        .frameOptions(f -> f.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                )
                .authorizeHttpRequests(auth -> {
                    // /v1/auth/role-claim 계열은 인증된 사용자만 호출 가능 — 관리자 부트스트랩 창구
                    // (역할 부여)와 그 개폐 조회(/availability)다. permitAll 매처보다 먼저 매칭되도록 위에 둔다.
                    //
                    // ★ 하위 경로까지 함께 잡는다(@design API-245) — 아래 /v1/auth/** 가 permitAll 이라
                    //   부트스트랩 창구 밑에 경로를 하나 더 두는 순간 그것이 <조용히 공개>된다. 실제로
                    //   개폐 조회를 신설할 때 그 상태가 됐다(미인증자에게 관리자 존재 여부가 노출).
                    //   구 매처는 정확 경로 하나만 잡아 그 사고를 구조적으로 허용했다.
                    // ⚠ 컨트롤러의 @PreAuthorize("isAuthenticated()") 는 <함께> 유지한다. 그것만
                    //   남으면 미인증 요청이 permitAll 로 통과해 메서드 보안에서 거절되어 401 이 아니라
                    //   403 이 되고(AccessDeniedException → GlobalExceptionHandler), 필터 계층의
                    //   fail-closed 가 사라진다.
                    auth.requestMatchers("/v1/auth/role-claim", "/v1/auth/role-claim/**").authenticated();
                    // A-ISSUE-02 — /v1/** 매처를 역할 결합으로 상향하면서 남기는 **의도된 예외**.
                    // /v1/me 는 본인 토큰 클레임(sub/role/channel)만 반환하며 업무 데이터를 노출하지 않는다.
                    // 역할 미배정(role=null) 사용자가 "나는 무권한" 임을 확인하고 /role-claim 온보딩으로
                    // 진입하는 유일한 경로이므로 역할 게이트에서 제외한다.
                    //
                    // DEV_FIX M-4 — 이 예외가 `.authenticated()` 뿐이라 **PORTAL 채널 토큰도 도달**한다.
                    //   채널을 INTERNAL 로 좁히지 않는 것은 의도된 선택이다:
                    //   ① 응답이 호출자 <b>본인 토큰의 클레임 반향</b>(sub/role/channel)뿐이라 채널을 넘나드는
                    //      업무 데이터가 없다 — 포털 사용자가 자기 토큰 내용을 되받는 것 이상은 얻지 못한다.
                    //   ② 포털 FE 도 세션 유효성·역할 확인에 동일 엔드포인트를 쓰므로 채널을 좁히면 기능이 깨진다.
                    //   따라서 실피해가 없고, 여기에 업무 데이터를 추가하는 순간 이 근거가 무효가 되므로
                    //   /v1/me 응답 확장 시 반드시 채널 게이트를 재검토할 것.
                    auth.requestMatchers("/v1/me").authenticated();
                    auth.requestMatchers("/health", "/actuator/health", "/actuator/health/**",
                                    "/actuator/info",
                                    // springdoc 표준 진입 URL /swagger-ui.html 은 /swagger-ui/index.html 로
                                    // 리다이렉트되기 전 Security 필터가 먼저 평가하므로 명시 허용 필요.
                                    "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                    "/v1/auth/**", "/v1/portal/auth/**").permitAll();
                    // 외부 시스템 결과 수신 webhook — VLM·생성형 AI(증강) 2종.
                    // 둘 다 외부 계약상 **무서명** 규격이라 JWT 도 HMAC 도 요구하지 않는다. 대신
                    // HmacWebhookFilter 의 무서명 가드(IP allowlist·rate limit·본문 size cap)를 거치고,
                    // 최종 인증은 각 서비스의 request_id 발급 게이트가 담당한다.
                    // 필터 적용 판정은 WebhookProtectedPaths(= MVC 와 동일한 RequestPath/PathPattern)의
                    // allowlist 이며, 컨트롤러 진입 직전 WebhookGateInterceptor 가 통과 증거를 재확인한다
                    // (경로 인코딩 변형 우회 이중 차단 — E-ISSUE-01).
                    // (UC018 — 비식별은 KPST 폴링으로 단일화되어 /v1/deidentify/result 콜백 경로를 제거함.)
                    // (Phase 7-A2 — 구 증강 콜백 /v1/aug/callback(HMAC) 은 계약 불일치로 제거됨.)
                    auth.requestMatchers(
                            "/v1/vlm/callback",
                            "/v1/genai/callback").permitAll();
                    if (devTokenEndpointEnabled) {
                        // ⚠ 개발/검수 전용 — prd 에서는 절대 활성화되지 않음.
                        // - /v1/dev/tokens: 부트스트랩 토큰 발급 → permitAll (로컬 인증 불가 방지, 토큰 진입점).
                        //   prd 노출은 @ConditionalOnProperty(authoring.dev.login.enabled) (DevTokenController/Service 빈 부재)
                        //   + devTokenEndpointEnabled(permitAll 매처 부재) 이중 차단으로 보호. 기본 false(fail-closed).
                        // (DEV_FIX CWE-862: /v1/dev/batch/** 는 permitAll 제거 — 아래 /v1/dev/** REVIEWER
                        //  가드가 적용되어 dev/stg/local 에서도 인증 없이 파이프라인/스캔 트리거 불가.
                        //  Phase 3: /v1/dev/autolabel/** 은퇴, dev 업로드 /v1/dev/upload 도 동일 REVIEWER 가드.)
                        auth.requestMatchers(
                                "/v1/dev/tokens", "/v1/dev/tokens/**").permitAll();
                    }
                    auth
                            // HIGH-1 fix: /v1/dev/** (tokens 외) 는 REVIEWER 만 — 자동 라벨 삭제·재실행 차단.
                            .requestMatchers("/v1/dev/**").hasRole(Role.REVIEWER.name())
                            // Phase 12 — actuator metrics/prometheus 는 REVIEWER 만 (운영 prd 는 노출 자체 차단)
                            .requestMatchers("/actuator/**").hasRole(Role.REVIEWER.name())
                            // 외부 시스템(관제/학습데이터) 양방향 통합 deprecated — 모든 매처 거부.
                            .requestMatchers("/v1/integration/**").denyAll()
                            .requestMatchers("/v1/export-api/**").denyAll()
                            // 라벨 마스터 <읽기> 계약 — REVIEWER/WORKER/PORTAL_USER 세 역할만.
                            // [@design API-024] GET /v1/manage/labels
                            // [@design API-028] GET /v1/manage/labels/{labelId}/attrs
                            // [@design API-177] GET /v1/manage/labels/detect-candidates
                            // [@design ROLE-003] PORTAL_USER — 관리 화면 미접근 원칙은 유지하되, 라벨 마스터
                            //   조회만은 라벨링이 의존하는 <공용 읽기 계약>이라 이 역할에도 허용한다
                            //   (포털 업로드 라벨링 화면이 이 API 로 라벨 분류·표시명·색상을 그린다 —
                            //    빼면 그 화면이 깨진다).
                            //
                            // ★ .authenticated() 를 쓰면 안 된다 (CWE-862 fail-open) — JwtAuthenticationFilter 는
                            //   role != null 일 때만 ROLE_* 를 부여하므로, LS_USER_ROLE <미배정>(role=null)
                            //   사용자도 .authenticated() 를 통과했다. 아래 /v1/** 포괄 매처가 같은 이유로
                            //   역할 결합 상향(A-ISSUE-02)된 것과 동일한 축이며, 이 3경로만 그 상향에서
                            //   빠져 있었다. 세 역할을 명시해 role=null 만 차단한다.
                            //
                            // 쓰기(POST/PUT/DELETE)는 메서드 @PreAuthorize 로 REVIEWER 강제 — 이 매처는 GET 한정이다.
                            // 매처 순서 — 아래 /v1/manage/** REVIEWER 매처보다 <앞>에 있어야 한다(먼저 매칭되는 쪽이 이긴다).
                            .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**")
                                .hasAnyRole(Role.REVIEWER.name(), Role.WORKER.name(), Role.PORTAL_USER.name())
                            .requestMatchers("/v1/manage/**").hasRole(Role.REVIEWER.name())
                            .requestMatchers("/v1/system/**").hasRole(Role.REVIEWER.name())
                            // 게시판(공지) — REVIEWER/WORKER 만. PORTAL_USER 차단.
                            // /v1/** (authenticated) 보다 위에 두어 PORTAL_USER 통과를 막는다.
                            // 쓰기 핸들러는 메서드 @PreAuthorize 로 REVIEWER 강제.
                            .requestMatchers("/v1/notices", "/v1/notices/**").hasAnyRole(Role.REVIEWER.name(), Role.WORKER.name())
                            // ★ 포털 서버간 <정리 삭제 트리거> 창구 — 사전 공유 API 키 축
                            //   (@design INT-014 · API-244 · AC-1103).
                            //
                            // 인증 수단이 <창구마다 갈린다>. 이 접두 아래 창구는 사람이 아니라 포털
                            //   서버가 부르므로 저작도구 역할이 없고, 사전 공유 키가 통과 여부를 정한다.
                            //   그 키를 확인하는 것은 PortalSystemApiKeyFilter 이고, 이 매처는 그 필터가
                            //   세운 권한만 요구한다 — 키 비교를 여기서 다시 하지 않는다(판정이 갈린다).
                            //
                            // ★ 채널 권한을 함께 요구하지 않는 것은 의도다. 부르는 쪽은 토큰이 없어
                            //   CHANNEL_* 를 애초에 갖지 못한다. 함께 요구하면 <아무도 통과하지 못한다>.
                            //   대신 그 권한을 부여하는 자리가 위 필터 하나뿐이라 사용자가 스스로 얻을 수 없다.
                            //
                            // ★ 경로 접두가 갈린 것이 인가 축을 가르는 수단이다. /v1/portal-system/** 은
                            //   /v1/portal/** 패턴에 <걸리지 않는다>(접두가 다른 별개 경로다).
                            //
                            // ⚠ 매처는 <먼저 매칭되는 쪽이 이긴다>. 이 매처는 반드시 /v1/** 포괄 매처보다
                            //   앞에 있어야 한다 — 뒤에 있으면 CHANNEL_INTERNAL 요구에 걸려 전건 거부된다.
                            //
                            // ⚠ 이 접두 아래 <다른 창구>가 생길 때 주의할 것 — 일일 저작 집계 창구는
                            //   인증 수단이 <미확정>이다(API-243). 이 매처는 접두 전체를 덮으므로 그 창구가
                            //   생기면 인증 축이 확정될 때까지 이 키로 열린다. 그때 매처를 갈라야 한다.
                            .requestMatchers("/v1/portal-system/**")
                                .access(allOf(
                                        hasAuthority(roleHierarchy,
                                                PortalSystemApiKeyFilter.AUTHORITY_PORTAL_SYSTEM_API)))
                            // R5-1: 채널 격리 — 포털 API 는 PORTAL 채널 토큰만 (CHANNEL_PORTAL + PORTAL_USER role).
                            //
                            // PORTAL_STREAM_SIGNED 는 예외로 함께 허용한다 — 재생 요소가 인증 헤더를 싣지
                            // 못해 단기 서명으로 들어오는 경로이며, 그 컨텍스트에는 역할 권한을 부여하지
                            // 않는다(권한 확대 방지). 이 권한을 받아들이는 자리는 스트림 창구 한 곳뿐이고
                            // (@PreAuthorize), 나머지 포털 창구는 여전히 ROLE_PORTAL_USER 를 요구한다.
                            // 소유자 판정은 창구가 소유자 스코프 조회로 별도 강제한다.
                            .requestMatchers("/v1/portal/**")
                                .access(allOf(
                                        hasAuthority(roleHierarchy, "CHANNEL_" + Channel.PORTAL.name()),
                                        anyOf(roleHierarchy,
                                              "ROLE_" + Role.PORTAL_USER.name(),
                                              kr.co.cudo.authoring.portal.config.PortalStreamSignatureFilter
                                                      .AUTHORITY_PORTAL_STREAM_SIGNED)))
                            // R5-1: 그 외 모든 내부 /v1/** API 는 INTERNAL 채널 토큰만.
                            // channel 클레임 없는 토큰은 <관제 향 배포본에서> JwtAuthenticationFilter 가 INTERNAL 로
                            // 기본값 처리하므로 기존 내부 사용자 토큰 호환(fail-closed: 무클레임=INTERNAL → 내부 허용,
                            // 외부 노출 없음).
                            // ⚠ 그 근거는 <조건부>다 (@design ADR-012) — 채널 판정 축이 <배포 향>이며, 포털 향
                            //   배포본은 클레임을 읽지 않고 PORTAL 로 확정하므로 이 매처에 애초에 닿지 않는다.
                            //   판정 본체는 common/config/DeployFlavorResolver 한 곳이다.
                            //
                            // A-ISSUE-02 — 채널 authority 만 요구하던 것을 **역할 결합**으로 상향한다.
                            // 과거에는 LS_USER_ROLE 미배정(role=null) INTERNAL 사용자가 @PreAuthorize 가 없는
                            // 조회 엔드포인트(/v1/event-types 등)를 전건 통과했다(fail-open). 이제 저작도구
                            // 역할(REVIEWER/WORKER) 보유가 필수다.
                            // STREAM_SIGNED 는 예외로 함께 허용한다 — 서명 스트림 컨텍스트는 역할 authority 를
                            // 부여하지 않으며(권한 확대 방지), 이 권한은 StreamSignatureFilter 만 부여한다.
                            // 영상 단위 인가는 컨트롤러 진입부의 LabelAccessGuard 가 별도로 강제한다(B-ISSUE-63).
                            .requestMatchers("/v1/**")
                                .access(allOf(
                                        hasAuthority(roleHierarchy, "CHANNEL_" + Channel.INTERNAL.name()),
                                        anyOf(roleHierarchy,
                                              "ROLE_" + Role.REVIEWER.name(),
                                              "ROLE_" + Role.WORKER.name(),
                                              StreamSignatureFilter.AUTHORITY_STREAM_SIGNED)))
                            .anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN))
                )
                // HmacWebhookFilter 를 JWT 필터보다 먼저 등록. 웹훅 경로(WebhookProtectedPaths allowlist)는
                // 이 필터가 단독 인증하고, 그 외 경로는 shouldNotFilter() 로 우회한다.
                // 서블릿 컨테이너 자동 등록은 WebhookGateConfig 에서 비활성화해 실행 경로를 여기 1곳으로 고정한다.
                .addFilterBefore(hmacWebhookFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // 영상 스트림 단기 서명 URL 인증 — JWT 필터 뒤에 두어, Authorization 헤더 경로가 우선되고
                // 헤더가 없을 때만 서명 쿼리(exp/sig)를 검증한다 (fail-closed).
                .addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class)
                // 포털 업로드 영상 스트림 단기 서명 인증 — 같은 이유로 JWT 필터 뒤에 둔다(헤더 경로 우선).
                .addFilterAfter(portalStreamSignatureFilter, JwtAuthenticationFilter.class)
                // 포털 서버간 정리 트리거 창구의 사전 공유 키 인증 (@design API-244).
                //   이 창구 경로에만 반응하고 그 밖에는 즉시 통과시키므로 토큰 축 판정에 끼어들지 않는다.
                .addFilterAfter(portalSystemApiKeyFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /**
     * HIGH-5 fix (OWASP A06): CORS allowlist 명시.
     *
     * <p>운영(dev/stg/prd) 은 환경변수 {@code CORS_ALLOWED_ORIGINS} 로 도메인 명시 필수.
     * 기본값은 빈 문자열 → 외부 origin 차단 (same-origin 만 허용).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${authoring.cors.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList());
        } else {
            config.setAllowedOrigins(List.of());
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "X-Trace-Id",
                // R11 — 연동 주소 저장 시 관리자 단기 유효창 토큰을 싣는 헤더. 목록에 없으면 교차 출처
                // 형상에서 preflight 가 거절돼 저장이 브라우저에서 실패한다.
                // ★ 이름의 단일 진실원은 AdminSessionGate.HEADER 다 — 여기에 리터럴을 두면 이름이 바뀔 때
                //   허용 목록만 옛 이름으로 남아, 서버 로그에 아무것도 남기지 않고 조용히 막힌다.
                AdminSessionGate.HEADER,
                // 포털 채널 전용 인계 헤더 (@design INT-013). Host 화면 안에서 실행되는 임베딩이라
                // 토큰을 Authorization 이 아니라 이 헤더로 싣는다. CORS safelisted 헤더가 아니라서
                // 교차 출처에서는 반드시 preflight 를 유발하며, 목록에 없으면 브라우저가 요청 자체를
                // 막아 <서버 로그에 아무것도 남지 않는다>.
                // ※ 지금은 allowed-origins 기본값이 비어 교차 출처가 닫혀 있어 증상이 드러나지 않지만,
                //   API 주소를 절대 주소로 돌리는 순간 포털 인증이 전량 preflight 에서 막힌다.
                // ★ 이름의 단일 진실원은 JwtAuthenticationFilter.PORTAL_TOKEN_HEADER 다(리터럴 금지).
                JwtAuthenticationFilter.PORTAL_TOKEN_HEADER,
                "X-Tus-Resumable", "Upload-Length", "Upload-Offset", "Upload-Metadata",
                "Tus-Resumable", "Content-Type"
        ));
        config.setExposedHeaders(List.of(
                "X-Trace-Id",
                "Upload-Offset", "Upload-Length",
                "Tus-Resumable", "Tus-Version", "Tus-Extension", "Tus-Max-Size",
                "Location"
        ));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * 단일 권한(authority) 요구 — 채널 격리용.
     *
     * <p>★ <b>역할 계층을 반드시 넘긴다.</b> {@code hasRole(...)} 매처와 {@code @PreAuthorize} 는
     * Spring 이 {@code RoleHierarchy} 빈을 자동으로 물려주지만, 여기처럼 <b>직접 조립한</b>
     * 매니저에는 아무도 물려주지 않는다. 넘기지 않으면 관리자({@code ROLE_ADMIN})가 아래
     * {@code /v1/**} 포괄 매처의 {@code ROLE_REVIEWER} 요구를 통과하지 못해 <b>내부 API 전체에서
     * 403</b> 이 된다 — 계층을 두고도 관리자가 아무것도 못 하는 상태다.
     *
     * @design ADR-055
     */
    private static AuthorizationManager<RequestAuthorizationContext> hasAuthority(
            org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy,
            String authority) {
        AuthorityAuthorizationManager<RequestAuthorizationContext> manager =
                AuthorityAuthorizationManager.hasAuthority(authority);
        manager.setRoleHierarchy(roleHierarchy);
        return manager;
    }

    /** 모든 권한(authority) 동시 요구 — role + channel 결합 강제용. */
    private static AuthorizationManager<RequestAuthorizationContext> allOf(
            org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy,
            String... authorities) {
        @SuppressWarnings("unchecked")
        AuthorizationManager<RequestAuthorizationContext>[] managers =
                java.util.Arrays.stream(authorities)
                        .map(a -> hasAuthority(roleHierarchy, a))
                        .toArray(AuthorizationManager[]::new);
        return AuthorizationManagers.allOf(managers);
    }

    /** 매니저 결합 — 하위 조건(anyOf 등)을 다시 AND 로 묶기 위한 오버로드. */
    @SafeVarargs
    private static AuthorizationManager<RequestAuthorizationContext> allOf(
            AuthorizationManager<RequestAuthorizationContext>... managers) {
        return AuthorizationManagers.allOf(managers);
    }

    /** 하나 이상의 권한(authority) 보유 요구 — 역할 OR 서명 스트림 권한 결합용. */
    private static AuthorizationManager<RequestAuthorizationContext> anyOf(
            org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy,
            String... authorities) {
        @SuppressWarnings("unchecked")
        AuthorizationManager<RequestAuthorizationContext>[] managers =
                java.util.Arrays.stream(authorities)
                        .map(a -> hasAuthority(roleHierarchy, a))
                        .toArray(AuthorizationManager[]::new);
        return AuthorizationManagers.anyOf(managers);
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, ErrorCode code) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code)));
    }
}
