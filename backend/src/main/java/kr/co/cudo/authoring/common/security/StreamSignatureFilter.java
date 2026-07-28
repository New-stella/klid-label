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
 * ({@code ?exp=...&u=...&sig=...}) 와 발급 시 내려준 nonce 쿠키가 함께 유효하면 스트림 접근을 허용한다.
 * <b>JwtAuthenticationFilter 뒤에 등록</b>되어 Authorization 헤더 경로가 우선되고, 이미 인증된 컨텍스트가
 * 있으면 개입하지 않는다 (A-ISSUE-04 — 구 javadoc 의 "앞에 등록" 서술은 사실과 반대였다).
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@code GET /v1/videos/{rawSn}/stream} 경로에만 적용 (그 외는 패스스루).</li>
 *   <li>유효 서명 + 유효 nonce 쿠키 → CHANNEL_INTERNAL + STREAM_SIGNED authority 인증 컨텍스트 설정.</li>
 *   <li>서명 없음/만료/변조/쿠키 부재 → 컨텍스트 미설정 → 보안 체인에서 401 (fail-closed).</li>
 * </ul>
 *
 * <h3>principal 은 실제 발급자 (B-ISSUE-63)</h3>
 * <p>과거에는 principal 의 sub 가 상수({@code "stream-signed"}), role 이 {@code null} 이라 컨트롤러에서
 * 영상 단위 인가({@code LabelAccessGuard.verifyRawAccess})를 적용할 수 없었다. 이제 쿼리 {@code u}(서명이
 * 덮는 값)를 sub 로, {@link UserRoleResolver} 로 재조회한 역할을 role 로 채워 <b>서명 경로에도 배정 인가가
 * 실제로 걸리게</b> 한다. 단, {@code ROLE_*} authority 는 부여하지 않는다 — 서명 컨텍스트가 다른 API 로
 * 확대되면 안 되기 때문이다(권한 확대 방지).
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
     * 서명 인증 컨텍스트의 합성 principal subject (식별/로깅용) — {@code u} 파라미터가 없는 경우의 폴백.
     * <p>인가 판정은 이 값에 의존하지 않는다({@link #AUTHORITY_STREAM_SIGNED} 권한으로 판정).
     */
    public static final String STREAM_SIGNED_PRINCIPAL = "stream-signed";

    private final StreamUrlSigner signer;
    private final StreamNonceCookie nonceCookie;
    private final UserRoleResolver userRoleResolver;

    public StreamSignatureFilter(StreamUrlSigner signer,
                                 StreamNonceCookie nonceCookie,
                                 UserRoleResolver userRoleResolver) {
        this.signer = signer;
        this.nonceCookie = nonceCookie;
        this.userRoleResolver = userRoleResolver;
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

        // CWE-284 — u 는 서명이 덮는 값이라 변조 시 서명 불일치로 거부된다. 다만 u 는 URL 에 노출되므로
        // 그것만으로는 URL 전체 복사(capture-replay)를 막지 못한다 — 실제 재사용 차단은 nonce 쿠키가 한다.
        String userNo = request.getParameter("u");
        // A-ISSUE-11 — 발급 시 내려간 HttpOnly 쿠키의 nonce 를 서명 입력으로 재구성한다.
        // 쿠키가 없으면(=URL 만 확보한 제3자) verify 가 false → 컨텍스트 미설정 → 401 (fail-closed).
        // DEV_FIX M-1 — 봉인 검증에 u(서명이 덮는 값)를 함께 넣는다. 서버가 발급하지 않았거나 다른
        // 사용자에게 발급된 쿠키는 여기서 null 이 되어 서명 재구성이 실패한다(nonce fixation 차단).
        String nonce = nonceCookie.read(request, userNo);
        if (signer.verify(rawSn, exp, sig, userNo, nonce)) {
            // CHANNEL_INTERNAL: /v1/** 채널 격리 통과용. STREAM_SIGNED: /stream 인가 전용 권한
            // (이 권한은 본 필터만 부여 — 사용자 토큰으로는 합성 불가).
            // ROLE_* 는 의도적으로 부여하지 않는다 — 서명 컨텍스트가 다른 API 로 확대되면 안 된다.
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(STREAM_CHANNEL_AUTHORITY),
                    new SimpleGrantedAuthority(AUTHORITY_STREAM_SIGNED));
            // B-ISSUE-63 — principal 에 실제 발급자(sub)와 재조회한 역할을 채워 컨트롤러의 영상 단위
            // 인가(LabelAccessGuard.verifyRawAccess)가 서명 경로에도 실제로 적용되게 한다.
            TokenClaims claims = new TokenClaims(
                    (userNo == null || userNo.isBlank()) ? STREAM_SIGNED_PRINCIPAL : userNo,
                    resolveRole(userNo),
                    Channel.INTERNAL,
                    null);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(claims, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else {
            // 만료/변조 → 컨텍스트 미설정 → 보안 체인 401 (fail-closed). sig 전문 미출력 (CWE-532).
            log.debug("[StreamSign] invalid signature rawSn={}", rawSn);
        }
        chain.doFilter(request, response);
    }

    /**
     * 서명에 바인딩된 발급자(sub)의 저작도구 역할을 재조회한다 (fail-closed).
     * <p>비숫자/누락 sub 는 {@code null}(무권한) — 그 경우 컨트롤러의 영상 단위 인가가 거절한다.
     */
    private Role resolveRole(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return null;
        }
        try {
            return userRoleResolver.resolve(Long.parseLong(userNo.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
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
