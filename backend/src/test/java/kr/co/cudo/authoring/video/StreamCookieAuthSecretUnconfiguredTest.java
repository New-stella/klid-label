package kr.co.cudo.authoring.video;

import jakarta.servlet.http.Cookie;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.StreamNonceCookie;
import kr.co.cudo.authoring.common.security.StreamSignatureFilter;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.video.service.StreamUrlSigner;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 서버 비밀({@code authoring.stream.sign-secret}) 미설정 배포에서는 재생 인증 쿠키가 있어도
 * 재생 인증 컨텍스트를 세우지 않는다 (ADR-071 · fail-closed).
 *
 * <p>비밀이 비면 쿠키 봉인 키가 <b>노드마다 다른 JVM 임시 키</b>라 봉인이 신뢰 근거가 되지 못한다. 그런데 그 노드가
 * 봉인한 쿠키는 그 노드에서는 봉인 검증을 <b>통과한다</b> — 그래서 필터가 비밀 설정 여부를 따로 보지 않으면
 * 쿠키 단독 판정이 열린다. 이 시험은 바로 그 조합(같은 노드가 봉인한 쿠키 + 비밀 미설정)을 만든다.
 *
 * <h3>설정 덮어쓰기가 실제로 적용됐는지 함께 단언한다</h3>
 * <p>설정값을 바꾸는 시험은 덮어쓰기가 조용히 무시되면 아무것도 지키지 않고 초록이 된다. 그래서 구성요소를
 * 직접 만들어 비밀을 비우고, 그 상태가 실제로 성립함을 ①서명기 {@code isConfigured()==false}
 * ②발급 창구(서비스)가 503 인 것으로 먼저 확인한다. 또 <b>양성 대조</b>(비밀 설정 시 같은 요청이 인증됨)를 둬
 * 「필터가 애초에 아무것도 안 해서 통과」하는 공허를 막는다.
 *
 * <p>여기서 컨텍스트 미설정은 보안 체인에서 401 이 된다 — 그 연결은 {@code StreamSignedUrlControllerTest}
 * (쿠키 없음·봉인 불일치 → 401)가 고정한다.
 */
@DisplayName("재생 인증 쿠키 — 서버 비밀 미설정이면 인증하지 않는다(fail-closed)")
class StreamCookieAuthSecretUnconfiguredTest {

    private static final String CONFIGURED_SECRET = "unit-test-stream-sign-secret-32bytes-or-more!!";
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String USER = "1";

    private final UserRoleResolver roleResolver = mock(UserRoleResolver.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(roleResolver.resolve(1L)).thenReturn(Role.REVIEWER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 같은 노드(같은 쿠키 봉인기)가 봉인한 쿠키를 싣고 u 만 있는 재생 요청. */
    private MockHttpServletRequest streamRequest(StreamNonceCookie cookieSealer) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/videos/5/stream");
        req.setParameter("u", USER);
        req.setCookies(new Cookie(StreamNonceCookie.COOKIE_NAME, cookieSealer.seal(NONCE, USER)));
        return req;
    }

    private Authentication runFilter(StreamSignatureFilter filter, MockHttpServletRequest req) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("필터는 요청을 막지 않고 체인에 넘긴다(판정은 보안 체인)").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("★비밀_미설정이면_그_노드가_봉인한_쿠키라도_인증_컨텍스트를_세우지_않는다")
    void secretUnconfigured_sameNodeSealedCookie_notAuthenticated() throws Exception {
        StreamUrlSigner signer = new StreamUrlSigner("", 60);
        StreamNonceCookie cookie = new StreamNonceCookie("", false);

        // 전제 ① — 덮어쓰기가 실제로 적용됐다: 서명기가 미설정 상태다.
        assertThat(signer.isConfigured()).isFalse();
        // 전제 ② — 같은 노드의 봉인은 그 노드에서 통과한다(그래서 필터의 비밀 확인이 필요하다).
        assertThat(cookie.read(streamRequest(cookie), USER)).isEqualTo(NONCE);

        Authentication auth = runFilter(new StreamSignatureFilter(signer, cookie, roleResolver),
                streamRequest(cookie));

        assertThat(auth).isNull();
    }

    @Test
    @DisplayName("비밀_미설정이면_발급_창구도_503이다 — 덮어쓰기_적용_확인")
    void secretUnconfigured_issueEndpointIs503() {
        StreamUrlSigner signer = new StreamUrlSigner("", 60);
        VideoRepository repo = mock(VideoRepository.class);
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-5", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "clip_5.mp4", LocalDateTime.now(), 60);
        raw.markDeidentified("Y");
        when(repo.findById(5L)).thenReturn(Optional.of(raw));
        VideoStreamService service = new VideoStreamService(repo, signer, null, null, new DeidentReportGate(repo));

        assertThatThrownBy(() -> service.issueSignedUrl(5L, USER, NONCE))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("양성대조_비밀이_설정되면_같은_요청이_STREAM_SIGNED로_인증된다")
    void secretConfigured_sameRequest_authenticated() throws Exception {
        StreamUrlSigner signer = new StreamUrlSigner(CONFIGURED_SECRET, 60);
        StreamNonceCookie cookie = new StreamNonceCookie(CONFIGURED_SECRET, false);
        assertThat(signer.isConfigured()).isTrue();

        Authentication auth = runFilter(new StreamSignatureFilter(signer, cookie, roleResolver),
                streamRequest(cookie));

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .contains(StreamSignatureFilter.AUTHORITY_STREAM_SIGNED);
    }
}
