package kr.co.cudo.authoring.auth.m2m;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 경로별 M2M 토큰 인증 필터.
 *
 * <p>Phase 4 — scope 분리: {@code /v1/integration/control/**} 는 CONTROL 토큰,
 * {@code /v1/export-api/**} 는 LEARNING_DATA 토큰만 허용. 한쪽 토큰으로 다른 경로를
 * 호출하면 즉시 401 종결.
 *
 * <p>매칭 경로에서 토큰 누락/검증 실패 시 SecurityContext 초기화 후 401 종결하여
 * 후속 JwtAuthenticationFilter 의 우회를 차단한다.
 */
@Slf4j
@RequiredArgsConstructor
public class M2mTokenAuthenticationFilter extends OncePerRequestFilter {

    /** 관제서버 통합 호출 권한 — {@code /v1/integration/control/**}. */
    public static final String M2M_AUTHORITY = "M2M_CONTROL";
    /** Phase 4 — 외부 학습데이터 API 권한 — {@code /v1/export-api/**}. */
    public static final String M2M_AUTHORITY_LEARNING_DATA = "M2M_LEARNING_DATA";

    public static final String M2M_PRINCIPAL = "control-server";
    /** Phase 4 — LEARNING_DATA scope principal. */
    public static final String M2M_PRINCIPAL_LEARNING_DATA = "learning-data-consumer";

    public static final String M2M_HEADER = "X-M2M-Token";

    private static final String CONTROL_PATH_PREFIX = "/api/v1/integration/control/";
    private static final String CONTROL_PATH_PREFIX_NO_CTX = "/v1/integration/control/";
    private static final String EXPORT_API_PATH_PREFIX = "/api/v1/export-api/";
    private static final String EXPORT_API_PATH_PREFIX_NO_CTX = "/v1/export-api/";

    private final M2mTokenValidator validator;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        MatchedScope matched = match(request);
        if (matched == null) {
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader(M2M_HEADER);
        if (token != null && validator.isValid(token, matched.scope)) {
            M2mAuthentication auth = new M2mAuthentication(
                    matched.principal,
                    List.of(new SimpleGrantedAuthority(matched.authority))
            );
            auth.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
            return;
        }

        // 매칭 경로는 토큰 누락/무효 시 즉시 401 종결.
        // 이후 체인의 JwtAuthenticationFilter 가 SecurityContext 를 재설정하여 우회하지 못하도록 함.
        log.debug("[M2M] missing or invalid m2m token (scope={}, token=***)", matched.scope);
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private MatchedScope match(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return null;
        if (uri.startsWith(CONTROL_PATH_PREFIX) || uri.startsWith(CONTROL_PATH_PREFIX_NO_CTX)) {
            return new MatchedScope(M2mTokenValidator.Scope.CONTROL, M2M_AUTHORITY, M2M_PRINCIPAL);
        }
        if (uri.startsWith(EXPORT_API_PATH_PREFIX) || uri.startsWith(EXPORT_API_PATH_PREFIX_NO_CTX)) {
            return new MatchedScope(M2mTokenValidator.Scope.LEARNING_DATA,
                    M2M_AUTHORITY_LEARNING_DATA, M2M_PRINCIPAL_LEARNING_DATA);
        }
        return null;
    }

    private record MatchedScope(M2mTokenValidator.Scope scope, String authority, String principal) {}

    private static final class M2mAuthentication extends AbstractAuthenticationToken {
        private final Object principal;

        M2mAuthentication(Object principal, List<SimpleGrantedAuthority> authorities) {
            super(authorities);
            this.principal = principal;
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
