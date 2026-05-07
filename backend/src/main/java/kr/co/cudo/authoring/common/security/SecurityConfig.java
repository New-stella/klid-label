package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.auth.m2m.M2mTokenAuthenticationFilter;
import kr.co.cudo.authoring.auth.m2m.M2mTokenValidator;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
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
    private final M2mTokenValidator m2mTokenValidator;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    CorsConfigurationSource corsConfigurationSource) throws Exception {
        M2mTokenAuthenticationFilter m2mFilter = new M2mTokenAuthenticationFilter(m2mTokenValidator);
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(keyResolver, issuerValidator);

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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/actuator/health", "/actuator/health/**",
                                "/actuator/info",
                                "/swagger-ui/**", "/v3/api-docs/**",
                                "/v1/auth/**", "/v1/portal/auth/**").permitAll()
                        // Phase 12 — actuator metrics/prometheus 는 REVIEWER 만 (운영 prd 는 노출 자체 차단)
                        .requestMatchers("/actuator/**").hasRole(Role.REVIEWER.name())
                        .requestMatchers("/v1/integration/control/**").hasAuthority(M2mTokenAuthenticationFilter.M2M_AUTHORITY)
                        .requestMatchers("/v1/integration/**").denyAll()
                        .requestMatchers("/v1/manage/**").hasRole(Role.REVIEWER.name())
                        .requestMatchers("/v1/system/**").hasRole(Role.REVIEWER.name())
                        .requestMatchers("/v1/portal/**").hasRole(Role.PORTAL_USER.name())
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN))
                )
                .addFilterBefore(m2mFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
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
                "Authorization", "X-M2M-Token", "X-Trace-Id",
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

    private void writeError(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, ErrorCode code) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code)));
    }
}
