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

@Slf4j
@RequiredArgsConstructor
public class M2mTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String M2M_AUTHORITY = "M2M_CONTROL";
    public static final String M2M_PRINCIPAL = "control-server";
    public static final String M2M_HEADER = "X-M2M-Token";
    private static final String PATH_PREFIX = "/api/v1/integration/control/";
    private static final String PATH_PREFIX_NO_CTX = "/v1/integration/control/";

    private final M2mTokenValidator validator;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!appliesTo(request)) {
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader(M2M_HEADER);
        if (token != null && validator.isValid(token)) {
            M2mAuthentication auth = new M2mAuthentication(
                    M2M_PRINCIPAL,
                    List.of(new SimpleGrantedAuthority(M2M_AUTHORITY))
            );
            auth.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
            return;
        }

        // /api/v1/integration/control/** 경로는 토큰 누락/무효 시 즉시 401 종결.
        // 이후 체인의 JwtAuthenticationFilter가 SecurityContext를 재설정하여 우회하지 못하도록 함.
        log.debug("[M2M] missing or invalid m2m token (token=***)");
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean appliesTo(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return false;
        return uri.startsWith(PATH_PREFIX) || uri.startsWith(PATH_PREFIX_NO_CTX);
    }

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
