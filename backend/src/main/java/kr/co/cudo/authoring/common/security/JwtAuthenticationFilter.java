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
import java.util.List;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_NAME_ATTR = "kr.co.cudo.authoring.auth.name";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtKeyResolver keyResolver;
    private final JwtIssuerValidator issuerValidator;

    public JwtAuthenticationFilter(JwtKeyResolver keyResolver, JwtIssuerValidator issuerValidator) {
        if (issuerValidator == null) {
            throw new IllegalArgumentException("issuerValidator must not be null (fail-closed)");
        }
        this.keyResolver = keyResolver;
        this.issuerValidator = issuerValidator;
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

                String roleStr = body.get("role", String.class);
                String channelStr = body.get("channel", String.class);
                Role role = roleStr == null ? null : Role.valueOf(roleStr);
                Channel channel = channelStr == null ? Channel.INTERNAL : Channel.valueOf(channelStr);
                TokenClaims claims = new TokenClaims(
                        body.getSubject(),
                        role,
                        channel,
                        body.getExpiration() == null ? null : Instant.ofEpochMilli(body.getExpiration().getTime())
                );

                // R5-1: ROLE_* + CHANNEL_* 권한 부여 → SecurityConfig 가 채널 격리를 인가 단계에서 강제.
                // channel 클레임 없는 토큰은 위에서 INTERNAL 로 기본값 처리(fail-closed: 내부 사용자 호환).
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
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
}
