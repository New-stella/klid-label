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
 * 영상 스트림 재생 인증 필터 — <b>재생 인증 쿠키 하나로 판정한다</b>.
 *
 * <p>&lt;video&gt; 엘리먼트는 Authorization 헤더를 못 붙이므로, 발급 창구({@code /stream-url})가 내려준
 * 봉인 쿠키({@link StreamNonceCookie})가 쿼리 {@code u} 의 발급자에게 봉인된 것이면 스트림 접근을 허용한다.
 *
 * <h3>주소의 만료·서명은 판정에 쓰지 않는다</h3>
 * <p>구 동작은 짧은 TTL 서명({@code exp}·{@code sig})과 쿠키를 함께 요구했다. 재생 중 부분 요청이 이어지는데
 * 서명(기본 60초)이 쿠키(1시간)보다 먼저 만료돼 재생이 끊기고, 화면이 주소를 재발급하며 재생 위치가 0 으로
 * 돌아갔다. 쿠키가 이미 "URL 만 가진 제3자 차단"과 "발급자 결속"을 하고 있으므로 판정을 쿠키 하나로 모은다.
 * 주소에 비밀이 없으므로 주소 유출·복사만으로는 재생할 수 없다.
 *
 * <p>{@code u} 는 주소에 노출되는 값이지만 <b>쿠키 봉인이 그 값을 검증</b>한다 — 봉인은 서버 비밀과 발급자로
 * 계산되므로, {@code u} 를 바꾸거나 다른 사용자에게 발급된 쿠키를 쓰면 봉인 검증에 실패한다. 따라서 봉인 검증을
 * 통과했다는 것은 "서버가 이 {@code u} 에게 발급한 쿠키" 라는 뜻이고, 그 {@code u} 가 곧 발급자다.
 * 발급 수단({@code StreamUrlSigner})은 걷어내지 않는다 — 판정에 쓰지 않을 뿐이다. 다만 그 수단의 <b>서버 비밀이
 * 설정되지 않은 배포에서는 개입하지 않는다</b> — 그 경우 쿠키 봉인 키가 노드마다 다른 임시 키라 봉인의 의미가
 * 없고, 발급 창구도 503 으로 닫혀 있어야 하는 형상이기 때문이다(fail-closed).
 *
 * @design ADR-071
 * @design API-084
 * <p><b>JwtAuthenticationFilter 뒤에 등록</b>되어 Authorization 헤더 경로가 우선되고, 이미 인증된 컨텍스트가
 * 있으면 개입하지 않는다 (A-ISSUE-04 — 구 javadoc 의 "앞에 등록" 서술은 사실과 반대였다).
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@code GET /v1/videos/{rawSn}/stream} 경로에만 적용 (그 외는 패스스루).</li>
 *   <li>{@code u} 에게 봉인된 유효 쿠키 → CHANNEL_INTERNAL + STREAM_SIGNED authority 인증 컨텍스트 설정.</li>
 *   <li>쿠키 부재 / 봉인 불일치(조작·타인 쿠키) / {@code u} 누락 → 컨텍스트 미설정 → 보안 체인에서 401 (fail-closed).</li>
 * </ul>
 *
 * <h3>principal 은 실제 발급자 (B-ISSUE-63)</h3>
 * <p>과거에는 principal 의 sub 가 상수({@code "stream-signed"}), role 이 {@code null} 이라 컨트롤러에서
 * 영상 단위 인가({@code LabelAccessGuard.verifyRawAccess})를 적용할 수 없었다. 이제 쿼리 {@code u}(쿠키 봉인이
 * 검증한 값)를 sub 로, {@link UserRoleResolver} 로 재조회한 역할을 role 로 채워 <b>쿠키 경로에도 배정 인가가
 * 실제로 걸리게</b> 한다. 쿠키가 유효해도 권한 밖 영상은 컨트롤러 진입부에서 거부된다. 단, {@code ROLE_*} authority 는 부여하지 않는다 — 서명 컨텍스트가 다른 API 로
 * 확대되면 안 되기 때문이다(권한 확대 방지).
 *
 * <h3>로그 마스킹</h3>
 * 쿠키 값·sig 쿼리 전문을 로그/에러에 출력하지 않는다 (CWE-532). rawSn 만 로깅.
 */
@Slf4j
@Component
public class StreamSignatureFilter extends OncePerRequestFilter {

    /** {@code /v1/videos/{rawSn}/stream} 매칭 — rawSn 캡처. */
    private static final Pattern STREAM_PATH = Pattern.compile("^/v1/videos/(\\d{1,18})/stream$");

    /** 쿠키 인증 시 부여하는 합성 authority — 채널 격리(/v1/**) 통과용. 역할 권한은 부여하지 않는다. */
    private static final String STREAM_CHANNEL_AUTHORITY = "CHANNEL_" + Channel.INTERNAL.name();

    /**
     * 재생 인증 쿠키 검증 시 부여하는 전용 authority — {@code /stream} 인가 판정의 단일 출처.
     * (이름은 구 서명 판정 시절의 것이며, 참조처가 여럿이라 유지한다.)
     *
     * <p>이 권한은 <b>오직 본 필터</b>(쿠키 봉인 검증 통과 시)만 부여한다. JWT 발급 경로
     * ({@link JwtAuthenticationFilter})는 {@code ROLE_*}/{@code CHANNEL_*} authority 만 부여하므로
     * 사용자는 어떤 토큰으로도 {@code STREAM_SIGNED} 를 절대 합성할 수 없다. 따라서 {@code /stream}
     * 인가를 sub(subject) 값 비교가 아닌 이 권한 보유 여부로 판정하면 sub-스푸핑 의존이 제거된다(CWE-863).
     */
    public static final String AUTHORITY_STREAM_SIGNED = "STREAM_SIGNED";

    /**
     * 구 서명 인증 컨텍스트의 합성 principal subject (식별/로깅용).
     * <p>쿠키 판정은 {@code u} 가 없으면 컨텍스트를 세우지 않으므로 본 필터는 더 이상 이 값을 쓰지 않는다.
     * 외부 참조(시험의 합성 컨텍스트) 호환을 위해 유지한다. 인가 판정은 이 값에 의존하지 않는다.
     */
    public static final String STREAM_SIGNED_PRINCIPAL = "stream-signed";

    /** 서버 비밀 설정 여부 판정에만 쓴다 — 재생 판정에 서명 검증을 쓰지 않는다. */
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
        // 이미 인증된 컨텍스트(JWT 헤더 경로 등)면 쿠키 판정을 건너뛴다.
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

        if (!signer.isConfigured()) {
            // 서버 비밀 미설정 — 봉인 키가 JVM 임시 키라 봉인이 신뢰 근거가 되지 못한다. 개입하지 않음 → 401.
            chain.doFilter(request, response);
            return;
        }

        String userNo = request.getParameter("u");
        if (userNo == null || userNo.isBlank()) {
            // 발급자 표지가 없으면 봉인을 검증할 수 없다 → 개입하지 않음 (헤더 인증이 없으면 보안 체인이 401).
            chain.doFilter(request, response);
            return;
        }

        // 봉인 검증 — 서버 비밀 + u 로 계산한 봉인과 쿠키의 봉인이 같아야 한다. 쿠키 부재·조작 값·다른
        // 사용자에게 발급된 쿠키는 여기서 null (fail-closed). 주소의 exp·sig 는 판정에 쓰지 않는다(ADR-071).
        String nonce = nonceCookie.read(request, userNo);
        if (nonce != null) {
            // CHANNEL_INTERNAL: /v1/** 채널 격리 통과용. STREAM_SIGNED: /stream 인가 전용 권한
            // (이 권한은 본 필터만 부여 — 사용자 토큰으로는 합성 불가).
            // ROLE_* 는 의도적으로 부여하지 않는다 — 쿠키 컨텍스트가 다른 API 로 확대되면 안 된다.
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(STREAM_CHANNEL_AUTHORITY),
                    new SimpleGrantedAuthority(AUTHORITY_STREAM_SIGNED));
            // B-ISSUE-63 — principal 에 발급자(sub)와 재조회한 역할을 채워 컨트롤러의 영상 단위
            // 인가(LabelAccessGuard.verifyRawAccess)가 쿠키 경로에도 실제로 적용되게 한다.
            TokenClaims claims = new TokenClaims(
                    userNo,
                    resolveRole(userNo),
                    Channel.INTERNAL,
                    null);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(claims, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else {
            // 쿠키 부재/봉인 불일치 → 컨텍스트 미설정 → 보안 체인 401 (fail-closed). 쿠키 값 미출력 (CWE-532).
            log.debug("[StreamAuth] stream cookie rejected rawSn={}", m.group(1));
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
