package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
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
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HmacWebhookFilter hmacWebhookFilter;
    private final StreamSignatureFilter streamSignatureFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    CorsConfigurationSource corsConfigurationSource) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(keyResolver, issuerValidator, userRoleResolver);

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
                    // /v1/auth/role-claim 은 인증된 사용자만 호출 가능 — role 부여 endpoint.
                    // permitAll 매처보다 먼저 매칭되도록 위에 둔다.
                    auth.requestMatchers("/v1/auth/role-claim").authenticated();
                    auth.requestMatchers("/health", "/actuator/health", "/actuator/health/**",
                                    "/actuator/info",
                                    // springdoc 표준 진입 URL /swagger-ui.html 은 /swagger-ui/index.html 로
                                    // 리다이렉트되기 전 Security 필터가 먼저 평가하므로 명시 허용 필요.
                                    "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                    "/v1/auth/**", "/v1/portal/auth/**").permitAll();
                    // 외부 시스템 결과 수신 webhook — VLM·증강 2종.
                    // JWT 인증을 우회하고 HmacWebhookFilter 가 시그니처 검증을 단독 수행한다.
                    // 시크릿 미설정 시 fail-closed 로 401 (HmacWebhookFilter 내부).
                    // (UC018 — 비식별은 KPST 폴링으로 단일화되어 /v1/deidentify/result 콜백 경로를 제거함.)
                    auth.requestMatchers(
                            "/v1/vlm/result",
                            "/v1/augments/result").permitAll();
                    if (devTokenEndpointEnabled) {
                        // ⚠ 개발/검수 전용 — prd 에서는 절대 활성화되지 않음.
                        // - /v1/dev/tokens: 부트스트랩 토큰 발급 → permitAll (로컬 인증 불가 방지, 토큰 진입점).
                        //   prd 노출은 @ConditionalOnProperty(authoring.dev.login.enabled) (DevTokenController/Service 빈 부재)
                        //   + devTokenEndpointEnabled(permitAll 매처 부재) 이중 차단으로 보호. 기본 false(fail-closed).
                        // (DEV_FIX CWE-862: /v1/dev/batch/** 는 permitAll 제거 — 아래 /v1/dev/** REVIEWER
                        //  가드가 적용되어 dev/stg/local 에서도 인증 없이 파이프라인/스캔 트리거 불가.
                        //  Phase 3: /v1/dev/autolabel/** 은퇴, dev 업로드 /v1/dev/autolabel-test 도 동일 REVIEWER 가드.)
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
                            // Phase 1 (CVAT-Like 라벨 풀): 라벨 마스터 조회는 WORKER/PORTAL_USER 도 허용.
                            // Phase 3 — 라벨 속성 정의 조회(GET /v1/manage/labels/{labelId}/attrs) 도 동일 정책 적용 →
                            // 와일드카드 /v1/manage/labels/** 로 확장. POST/PUT/DELETE 는 메서드 @PreAuthorize 로 REVIEWER 강제.
                            // 매처 순서 — REVIEWER 매처보다 앞에 위치해야 함.
                            .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
                            .requestMatchers("/v1/manage/**").hasRole(Role.REVIEWER.name())
                            .requestMatchers("/v1/system/**").hasRole(Role.REVIEWER.name())
                            // 게시판(공지) — REVIEWER/WORKER 만. PORTAL_USER 차단.
                            // /v1/** (authenticated) 보다 위에 두어 PORTAL_USER 통과를 막는다.
                            // 쓰기 핸들러는 메서드 @PreAuthorize 로 REVIEWER 강제.
                            .requestMatchers("/v1/notices", "/v1/notices/**").hasAnyRole(Role.REVIEWER.name(), Role.WORKER.name())
                            // R5-1: 채널 격리 — 포털 API 는 PORTAL 채널 토큰만 (CHANNEL_PORTAL + PORTAL_USER role).
                            .requestMatchers("/v1/portal/**")
                                .access(allOf("ROLE_" + Role.PORTAL_USER.name(), "CHANNEL_" + Channel.PORTAL.name()))
                            // R5-1: 그 외 모든 내부 /v1/** API 는 INTERNAL 채널 토큰만.
                            // channel 클레임 없는 토큰은 JwtAuthenticationFilter 에서 INTERNAL 로 기본값 처리되므로
                            // 기존 내부 사용자 토큰 호환(fail-closed: 무클레임=INTERNAL → 내부 허용, 외부 노출 없음).
                            .requestMatchers("/v1/**")
                                .access(hasAuthority("CHANNEL_" + Channel.INTERNAL.name()))
                            .anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN))
                )
                // Phase 2 — HmacWebhookFilter 를 JWT 필터보다 먼저 등록.
                // /v1/*/result 경로는 HmacWebhookFilter 가 단독 인증, 그 외 경로는 shouldNotFilter() 로 우회.
                .addFilterBefore(hmacWebhookFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // 영상 스트림 단기 서명 URL 인증 — JWT 필터 뒤에 두어, Authorization 헤더 경로가 우선되고
                // 헤더가 없을 때만 서명 쿼리(exp/sig)를 검증한다 (fail-closed).
                .addFilterAfter(streamSignatureFilter, JwtAuthenticationFilter.class);
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

    /** 단일 권한(authority) 요구 — 채널 격리용. */
    private static AuthorizationManager<RequestAuthorizationContext> hasAuthority(String authority) {
        return AuthorityAuthorizationManager.hasAuthority(authority);
    }

    /** 모든 권한(authority) 동시 요구 — role + channel 결합 강제용. */
    @SafeVarargs
    private static AuthorizationManager<RequestAuthorizationContext> allOf(String... authorities) {
        @SuppressWarnings("unchecked")
        AuthorizationManager<RequestAuthorizationContext>[] managers =
                java.util.Arrays.stream(authorities)
                        .map(SecurityConfig::hasAuthority)
                        .toArray(AuthorizationManager[]::new);
        return AuthorizationManagers.allOf(managers);
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, ErrorCode code) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code)));
    }
}
