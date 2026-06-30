package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_NAME_ATTR = "kr.co.cudo.authoring.auth.name";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtKeyResolver keyResolver;
    private final JwtIssuerValidator issuerValidator;
    private final UserRoleResolver userRoleResolver;

    public JwtAuthenticationFilter(JwtKeyResolver keyResolver,
                                   JwtIssuerValidator issuerValidator,
                                   UserRoleResolver userRoleResolver) {
        if (keyResolver == null) {
            throw new IllegalArgumentException("keyResolver must not be null (fail-closed)");
        }
        if (issuerValidator == null) {
            throw new IllegalArgumentException("issuerValidator must not be null (fail-closed)");
        }
        if (userRoleResolver == null) {
            throw new IllegalArgumentException("userRoleResolver must not be null (fail-closed)");
        }
        this.keyResolver = keyResolver;
        this.issuerValidator = issuerValidator;
        this.userRoleResolver = userRoleResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Jws<Claims> jws = Jwts.parser()
                        .verifyWith(keyResolver.resolve())
                        .build()
                        .parseSignedClaims(token);
                Claims body = jws.getPayload();

                if (!issuerValidator.isAllowed(body.getIssuer())) {
                    log.debug("[Auth] rejected unknown issuer");
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }

                String channelStr = body.get("channel", String.class);
                Channel channel = channelStr == null ? Channel.INTERNAL : Channel.valueOf(channelStr);

                // 역할 분리 Phase 3 — 인가 역할 출처를 JWT role 클레임 → 저작도구 소유 LS_USER_ROLE 로 전환.
                //   * INTERNAL: sub(userNo)로 LS 조회(UserRoleResolver, 캐시+fail-closed). 비숫자/누락 sub 는
                //     filter 가 null 로 선처리(캐시키 일관성: resolve/evict 모두 userNo Long 키).
                //   * PORTAL: LS 미조회, role=PORTAL_USER 고정. 채널 격리는 SecurityConfig 가 강제.
                // JWT 의 role 클레임(관제 역할 LEARN_MANAGER 등)은 더 이상 인가에 사용하지 않는다.
                Role role;
                if (channel == Channel.INTERNAL) {
                    Long userNo = parseUserNo(body.getSubject());
                    role = userNo == null ? null : userRoleResolver.resolve(userNo);
                } else {
                    role = Role.PORTAL_USER;
                }
                TokenClaims claims = new TokenClaims(
                        body.getSubject(),
                        role,
                        channel,
                        body.getExpiration() == null ? null : Instant.ofEpochMilli(body.getExpiration().getTime())
                );

                // R5-1: ROLE_* + CHANNEL_* 권한 부여 → SecurityConfig 가 채널 격리를 인가 단계에서 강제.
                // channel 클레임 없는 토큰은 위에서 INTERNAL 로 기본값 처리(fail-closed: 내부 사용자 호환).
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                if (role != null) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
                }
                authorities.add(new SimpleGrantedAuthority("CHANNEL_" + channel.name()));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(claims, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);

                String name = body.get("name", String.class);
                if (name != null) {
                    request.setAttribute(AUTH_NAME_ATTR, name);
                }
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("[Auth] jwt validation failed message={}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * JWT subject(sub)를 userNo(Long)로 파싱한다. fail-closed — null/공백/비숫자면 null 반환해
     * 무권한으로 흐른다(NumberFormatException 미전파). resolve/evict 캐시키 일관성을 위해 파싱은
     * filter 에서 선처리한다.
     */
    private static Long parseUserNo(String sub) {
        if (sub == null || sub.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sub.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
