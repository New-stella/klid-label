package kr.co.cudo.authoring.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.video.service.StreamUrlSigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 영상 스트림 단기 서명 URL 인증 필터.
 *
 * <p>&lt;video&gt; 엘리먼트는 Authorization 헤더를 못 붙이므로, BE 가 발급한 짧은 TTL 서명 쿼리
 * ({@code ?exp=...&sig=...}) 가 유효하면 스트림 접근을 허용한다. JwtAuthenticationFilter 보다 앞에 등록되며,
 * 이미 인증된 컨텍스트(Authorization 헤더 경로)가 있으면 개입하지 않는다.
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@code GET /v1/videos/{rawSn}/stream} 경로에만 적용 (그 외는 패스스루).</li>
 *   <li>유효 서명 → CHANNEL_INTERNAL authority 를 가진 인증 컨텍스트 설정 (SecurityConfig 의 /v1/** 채널 격리 통과).</li>
 *   <li>서명 없음/만료/변조 → 컨텍스트 미설정 → 보안 체인에서 401 (fail-closed).</li>
 * </ul>
 *
 * <h3>로그 마스킹</h3>
 * sig 쿼리 전문을 로그/에러에 출력하지 않는다 (CWE-532). rawSn 만 로깅.
 */
@Slf4j
@Component
public class StreamSignatureFilter extends OncePerRequestFilter {

    /** {@code /v1/videos/{rawSn}/stream} 매칭 — rawSn 캡처. */
    private static final Pattern STREAM_PATH = Pattern.compile("^/v1/videos/(\\d{1,18})/stream$");

    /** 서명 인증 시 부여하는 합성 authority — 채널 격리(/v1/**) 통과용. 역할 권한은 부여하지 않는다. */
    private static final String STREAM_CHANNEL_AUTHORITY = "CHANNEL_" + Channel.INTERNAL.name();

    /**
     * 서명 인증 시 부여하는 전용 authority — {@code /stream} 인가 판정의 단일 출처.
     *
     * <p>이 권한은 <b>오직 본 필터</b>(유효 HMAC 서명 검증 통과 시)만 부여한다. JWT 발급 경로
     * ({@link JwtAuthenticationFilter})는 {@code ROLE_*}/{@code CHANNEL_*} authority 만 부여하므로
     * 사용자는 어떤 토큰으로도 {@code STREAM_SIGNED} 를 절대 합성할 수 없다. 따라서 {@code /stream}
     * 인가를 sub(subject) 값 비교가 아닌 이 권한 보유 여부로 판정하면 sub-스푸핑 의존이 제거된다(CWE-863).
     */
    public static final String AUTHORITY_STREAM_SIGNED = "STREAM_SIGNED";

    /**
     * 서명 인증 컨텍스트의 합성 principal subject (식별/로깅용).
     * <p>서명 URL 은 role-gated {@code /stream-url} 발급을 거친 정당 경로이므로 역할 권한 없이도 스트림을 허용해야 한다.
     * 인가 판정은 더 이상 이 값에 의존하지 않으며({@link #AUTHORITY_STREAM_SIGNED} 권한으로 전환), 합성 principal
     * 의 식별자로만 유지한다.
     */
    public static final String STREAM_SIGNED_PRINCIPAL = "stream-signed";

    private final StreamUrlSigner signer;

    public StreamSignatureFilter(StreamUrlSigner signer) {
        this.signer = signer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !STREAM_PATH.matcher(stripContext(request)).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 이미 인증된 컨텍스트(JWT 헤더 경로 등)면 서명 검증을 건너뛴다.
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        Matcher m = STREAM_PATH.matcher(stripContext(request));
        if (!m.matches()) {
            chain.doFilter(request, response);
            return;
        }

        String sig = request.getParameter("sig");
        String exp = request.getParameter("exp");
        if (sig == null || sig.isBlank() || exp == null || exp.isBlank()) {
            // 서명 쿼리 없음 → 개입하지 않음 (Authorization 헤더로 인증되거나 보안 체인이 401 처리).
            chain.doFilter(request, response);
            return;
        }

        long rawSn;
        try {
            rawSn = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            chain.doFilter(request, response);
            return;
        }

        // CWE-284 — 발급 시 userNo 를 서명 입력에 바인딩했으므로, 쿼리의 u 를 그대로 서명 입력으로 사용해 검증한다.
        // u 가 변조되면 서명 불일치로 거부된다(타 사용자가 URL 을 그대로 재사용해도 통과 못 함).
        // u 미존재(레거시 URL)면 빈 문자열로 검증 — signer 가 null/"" 를 동일하게 정규화한다.
        String userNo = request.getParameter("u");
        if (signer.verify(rawSn, exp, sig, userNo)) {
            // CHANNEL_INTERNAL: /v1/** 채널 격리 통과용. STREAM_SIGNED: /stream 인가 전용 권한
            // (이 권한은 본 필터만 부여 — 사용자 토큰으로는 합성 불가).
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(STREAM_CHANNEL_AUTHORITY),
                    new SimpleGrantedAuthority(AUTHORITY_STREAM_SIGNED));
            // principal 은 합성 식별자 — 역할/채널 클레임은 channel(INTERNAL)만 부여.
            TokenClaims claims = new TokenClaims(STREAM_SIGNED_PRINCIPAL, null, Channel.INTERNAL, null);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(claims, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else {
            // 만료/변조 → 컨텍스트 미설정 → 보안 체인 401 (fail-closed). sig 전문 미출력 (CWE-532).
            log.debug("[StreamSign] invalid signature rawSn={}", rawSn);
        }
        chain.doFilter(request, response);
    }

    private String stripContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (uri == null) {
            return "";
        }
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }
}
