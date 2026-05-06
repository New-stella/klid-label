package kr.co.cudo.authoring.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final int MAX_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String trace = sanitize(request.getHeader(HEADER));
        if (trace.isEmpty()) {
            trace = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        MDC.put(MDC_KEY, trace);
        response.setHeader(HEADER, trace);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * X-Trace-Id 헤더 값을 sanitize한다.
     * - null/blank 처리
     * - 제어 문자(\r, \n, \0 등) 제거 — CRLF 주입 방어 (CWE-113)
     * - 영숫자 + 하이픈만 허용
     * - 64자로 제한
     */
    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw
                .replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("[^A-Za-z0-9-]", "");
        if (cleaned.length() > MAX_LEN) {
            cleaned = cleaned.substring(0, MAX_LEN);
        }
        return cleaned;
    }
}
