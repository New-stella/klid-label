package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.user.service.AutoWorkerRegistrar;
import kr.co.cudo.authoring.user.service.ControlUserProvisioner;
import kr.co.cudo.authoring.user.service.LastLoginRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 진입 시 작업자 자동 등록의 <b>호출 조건</b>을 고정한다 (@design AC-1016).
 *
 * <h3>왜 이 시험이 따로 필요한가</h3>
 * <p>"매 요청 쓰기가 아니다" 를 DB 로만 확인하면 놓친다 — 등록기가 매 요청 호출돼도 문장이
 * {@code DO NOTHING}·no-op 이라 <b>행이 바뀌지 않아 초록</b>이기 때문이다. 실제로 지켜야 하는 것은
 * "역할이 해석되면 <b>호출조차 하지 않는다</b>" 이고, 그것은 호출 여부로만 볼 수 있다.
 * 필터에서 {@code role == null} 게이트를 빼면 이 시험이 RED 다.
 */
class JwtFilterAutoRegisterWiringTest {

    private static final long USER_NO = 4242L;
    /** 인계 토큰의 이름 클레임 — 자동 등록이 사용자 마스터의 표시 이름으로 쓴다. */
    private static final String NAME = "인계이름";

    private SecretKey key;
    private UserRoleResolver userRoleResolver;
    private AutoWorkerRegistrar autoWorkerRegistrar;
    private ControlUserProvisioner controlUserProvisioner;
    private LastLoginRecorder lastLoginRecorder;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        userRoleResolver = mock(UserRoleResolver.class);
        autoWorkerRegistrar = mock(AutoWorkerRegistrar.class);
        controlUserProvisioner = mock(ControlUserProvisioner.class);
        lastLoginRecorder = mock(LastLoginRecorder.class);
        JwtIssuerValidator issuerValidator = mock(JwtIssuerValidator.class);
        when(issuerValidator.isAllowed(any())).thenReturn(true);

        filter = new JwtAuthenticationFilter(() -> key, issuerValidator,
                userRoleResolver, lastLoginRecorder, autoWorkerRegistrar, controlUserProvisioner);
    }

    private void doFilter(String subject, String channel) throws Exception {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(subject)
                .issuer("klid-auth")
                .claim("channel", channel)
                .claim("name", NAME)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        SecurityContextHolder.clearContext();
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }

    private boolean hasAuthority(String authority) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }

    @Test
    @DisplayName("★역할이_해석되면_자동_등록기를_호출조차_하지_않는다_사용자당_사실상_1회")
    void registrarNotCalledWhenRoleResolves() throws Exception {
        when(userRoleResolver.resolve(USER_NO)).thenReturn(Role.WORKER);

        doFilter(String.valueOf(USER_NO), "INTERNAL");

        verify(autoWorkerRegistrar, never()).registerAsWorker(anyLong(), any());
    }

    @Test
    @DisplayName("★역할이_없으면_등록기를_호출하고_그_요청부터_작업자로_인가된다")
    void registrarCalledAndGrantsWorkerForCurrentRequest() throws Exception {
        when(userRoleResolver.resolve(USER_NO)).thenReturn(null);
        when(autoWorkerRegistrar.registerAsWorker(USER_NO, NAME)).thenReturn(Role.WORKER);

        doFilter(String.valueOf(USER_NO), "INTERNAL");

        // ★이름 클레임이 등록기까지 전달돼야 한다 — 아니면 자동 등록 사용자가 영구 공란 이름으로
        //   남아 배정 대상 목록이 전원 공란으로 그려진다(역할 클레임이 부트스트랩 전용으로 닫히면서
        //   표시 정보를 채우던 유일한 경로가 여기로 옮겨왔다).
        verify(autoWorkerRegistrar).registerAsWorker(USER_NO, NAME);
        assertThat(hasAuthority("ROLE_" + Role.WORKER.name()))
                .as("등록에 성공했으면 그 요청도 작업자로 흐른다").isTrue();
    }

    @Test
    @DisplayName("★등록이_실패하면_요청은_죽지_않고_무권한으로_흐른다_fail_open")
    void registrationFailureFlowsUnauthorized() throws Exception {
        when(userRoleResolver.resolve(USER_NO)).thenReturn(null);
        when(autoWorkerRegistrar.registerAsWorker(USER_NO, NAME)).thenReturn(null);

        doFilter(String.valueOf(USER_NO), "INTERNAL");

        // 인증 자체는 성립하되 역할 권한이 없다 — 인가는 fail-closed 그대로다.
        assertThat(hasAuthority("CHANNEL_" + Channel.INTERNAL.name())).isTrue();
        assertThat(hasAuthority("ROLE_" + Role.WORKER.name())).isFalse();
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isFalse();
    }

    @Test
    @DisplayName("★포털_채널은_등록기를_호출하지_않는다")
    void portalChannelNeverCallsRegistrar() throws Exception {
        doFilter("portal-subject", "PORTAL");

        verify(autoWorkerRegistrar, never()).registerAsWorker(anyLong(), any());
        assertThat(hasAuthority("ROLE_" + Role.PORTAL_USER.name())).isTrue();
    }

    @Test
    @DisplayName("등록기_부재는_배선_버그로_즉시_드러난다")
    void missingRegistrarIsWiringBug() {
        assertThatThrownBy(() -> new JwtAuthenticationFilter(
                () -> key, mock(JwtIssuerValidator.class), userRoleResolver, lastLoginRecorder, null,
                controlUserProvisioner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("프로비저너_부재는_배선_버그로_즉시_드러난다")
    void missingProvisionerIsWiringBug() {
        assertThatThrownBy(() -> new JwtAuthenticationFilter(
                () -> key, mock(JwtIssuerValidator.class), userRoleResolver, lastLoginRecorder,
                autoWorkerRegistrar, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
