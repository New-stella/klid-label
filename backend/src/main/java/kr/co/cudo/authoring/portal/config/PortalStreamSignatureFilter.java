package kr.co.cudo.authoring.portal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.service.PortalStreamUrlSigner;
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
 * 포털 업로드 영상 스트림의 <b>단기 서명 인증</b> 필터.
 *
 * <p>재생 요소는 인증 헤더를 붙이지 못하므로, 발급 창구가 내준 짧은 유효시간 서명 질의
 * ({@code ?exp=…&u=…&sig=…})가 유효하면 그 스트림 창구에 한해 접근을 허용한다.
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@code GET /v1/portal/uploads/{uldSn}/stream} <b>한 경로에만</b> 적용(그 외는 패스스루).</li>
 *   <li>이미 인증된 컨텍스트(토큰 헤더 경로)가 있으면 개입하지 않는다 — 헤더 경로가 우선이다.</li>
 *   <li>유효 서명 → 포털 채널 권한 + 서명 전용 권한만 부여한 인증 컨텍스트를 세운다.</li>
 *   <li>서명 없음·만료·변조 → 컨텍스트 미설정 → 보안 체인이 401 (fail-closed).</li>
 * </ul>
 *
 * <h3>★ 역할 권한을 부여하지 않는다</h3>
 * <p>{@code ROLE_PORTAL_USER} 를 주면 이 컨텍스트가 포털 API 전체로 확대되어, 재생 주소 하나가
 * 자산 삭제·라벨 저장 창구까지 여는 열쇠가 된다. 그래서 <b>전용 권한 하나만</b> 주고 그 권한을
 * 받아들이는 자리는 스트림 창구 한 곳뿐이다(관제 채널의 같은 성격 필터와 동일한 골격).
 *
 * <p>이 권한은 <b>오직 이 필터</b>가 유효 서명 검증을 통과했을 때만 부여한다. 토큰 발급 경로는
 * 역할·채널 권한만 부여하므로 사용자가 어떤 토큰으로도 합성할 수 없다(CWE-863).
 *
 * <h3>소유자 판정은 그대로 남는다</h3>
 * <p>principal 의 주체를 서명이 덮는 {@code u} 로 채우므로, 스트림 창구의 소유자 스코프 조회가
 * 서명 경로에도 <b>그대로</b> 적용된다. 서명을 얻은 사람은 발급 시점에 이미 소유자로 판정된
 * 사람이므로 최종 접근 가능 주체가 헤더 경로와 같다.
 *
 * <h3>로그</h3>
 * <p>서명 질의 전문을 로그·오류에 출력하지 않는다(CWE-532). 자산 식별자만 남긴다.
 *
 * @design API-238
 * @design API-239
 */
@Slf4j
@Component
public class PortalStreamSignatureFilter extends OncePerRequestFilter {

    /** {@code /v1/portal/uploads/{uldSn}/stream} 매칭 — 자산 식별자 캡처. */
    private static final Pattern STREAM_PATH =
            Pattern.compile("^/v1/portal/uploads/(\\d{1,18})/stream$");

    /** 서명 인증 시 부여하는 채널 권한 — 포털 API 채널 격리 통과용. */
    private static final String PORTAL_CHANNEL_AUTHORITY = "CHANNEL_" + Channel.PORTAL.name();

    /**
     * 서명 인증 전용 권한 — 스트림 창구 인가 판정의 단일 출처. 이 필터만 부여한다.
     */
    public static final String AUTHORITY_PORTAL_STREAM_SIGNED = "PORTAL_STREAM_SIGNED";

    private final PortalStreamUrlSigner signer;

    public PortalStreamSignatureFilter(PortalStreamUrlSigner signer) {
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
            chain.doFilter(request, response);
            return;
        }
        long uldSn;
        try {
            uldSn = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            chain.doFilter(request, response);
            return;
        }
        String userNo = request.getParameter("u");
        if (signer.verify(uldSn, exp, sig, userNo)) {
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(PORTAL_CHANNEL_AUTHORITY),
                    new SimpleGrantedAuthority(AUTHORITY_PORTAL_STREAM_SIGNED));
            // 주체를 서명이 덮는 값으로 채워 소유자 스코프 조회가 서명 경로에도 그대로 걸리게 한다.
            TokenClaims claims = new TokenClaims(userNo, Role.PORTAL_USER, Channel.PORTAL, null);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(claims, null, authorities));
        } else {
            log.debug("[PortalStreamSign] invalid signature uldSn={}", uldSn);
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
