package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.common.config.DeployFlavor;
import kr.co.cudo.authoring.common.config.DeployFlavorResolver;
import kr.co.cudo.authoring.user.service.AutoWorkerRegistrar;
import kr.co.cudo.authoring.user.service.ControlUserProvisioner;
import kr.co.cudo.authoring.user.service.LastLoginRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ★ 종류가 refresh 인 토큰은 인증에 쓰지 않는다 — 채널·인계 자리 무관 (@design ADR-063 ⑦ · AC-1017 · AC-1016).
 *
 * <p>관제 refresh 토큰은 access 와 <b>같은 비밀키</b>로 서명되므로 여기서도 같은 키로 두 종류를 만든다 —
 * 거부 원인이 종류 클레임 하나뿐임을 access 대조가 고정한다(대조가 없으면 모든 토큰을 막는 구현도 통과한다).
 */
class JwtFilterRefreshTokenTypeTest {

    private SecretKey key;
    private UserRoleResolver userRoleResolver;
    private AutoWorkerRegistrar autoWorkerRegistrar;
    private ControlUserProvisioner controlUserProvisioner;
    private LastLoginRecorder lastLoginRecorder;
    private JwtIssuerValidator issuerValidator;

    @BeforeEach
    void setUp() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        key = Keys.hmacShaKeyFor(Base64.getUrlEncoder().withoutPadding().encodeToString(random)
                .getBytes(StandardCharsets.UTF_8));
        userRoleResolver = mock(UserRoleResolver.class);
        autoWorkerRegistrar = mock(AutoWorkerRegistrar.class);
        controlUserProvisioner = mock(ControlUserProvisioner.class);
        lastLoginRecorder = mock(LastLoginRecorder.class);
        issuerValidator = mock(JwtIssuerValidator.class);
        when(issuerValidator.isAllowed(any())).thenReturn(true);
        when(controlUserProvisioner.provision(anyString(), any())).thenReturn(null);
        when(userRoleResolver.resolve(anyLong())).thenReturn(Role.REVIEWER);
        when(userRoleResolver.resolveUserNoByUserId(anyString())).thenReturn(null);
    }

    private JwtAuthenticationFilter filter(DeployFlavor flavor) {
        return new JwtAuthenticationFilter(() -> key, issuerValidator, userRoleResolver,
                lastLoginRecorder, autoWorkerRegistrar, controlUserProvisioner, DeployFlavorResolver.of(flavor));
    }

    /** 숫자 sub 내부 토큰 — 종류 클레임만 바꿔 가며 쓴다. */
    private String token(Consumer<JwtBuilder> customizer) {
        Instant now = Instant.now();
        JwtBuilder b = Jwts.builder()
                .subject("1")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(key);
        customizer.accept(b);
        return b.compact();
    }

    /** 실제 관제 토큰 모양 — sub=로그인 아이디 · userId/userNm · iss·channel 없음. */
    private String controlShapedToken(String type) {
        return token(b -> {
            b.subject("admin").claim("userId", "admin").claim("userNm", "관리자").claim("sessionId", "S-1");
            if (type != null) {
                b.claim("type", type);
            }
        });
    }

    private boolean run(DeployFlavor flavor, String authorization, String portalHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        if (portalHeader != null) {
            request.addHeader(JwtAuthenticationFilter.PORTAL_TOKEN_HEADER, portalHeader);
        }
        SecurityContextHolder.clearContext();
        filter(flavor).doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    // ─────────────────────────────── 거부 ───────────────────────────────

    @Test
    @DisplayName("★Bearer_로_실린_refresh_토큰은_인증되지_않고_부수쓰기도_남기지_않는다")
    void refreshViaBearerIsRejectedWithoutSideEffects() throws Exception {
        boolean authenticated = run(DeployFlavor.CONTROL,
                "Bearer " + token(b -> b.claim("type", "refresh").claim("channel", "INTERNAL")), null);

        assertThat(authenticated).isFalse();
        // 거부될 토큰이 역할 조회·자동 등록·접속 기록을 남기면 안 된다 — 판정이 그보다 앞에 있어야 한다.
        verifyNoInteractions(userRoleResolver, autoWorkerRegistrar, controlUserProvisioner, lastLoginRecorder);
    }

    @Test
    @DisplayName("★실제_관제_모양의_refresh_토큰도_거부되고_로컬_사용자를_발급하지_않는다")
    void controlShapedRefreshIsRejected() throws Exception {
        assertThat(run(DeployFlavor.CONTROL, "Bearer " + controlShapedToken("refresh"), null)).isFalse();
        verify(controlUserProvisioner, never()).provision(anyString(), any());
        verify(userRoleResolver, never()).resolveUserNoByUserId(anyString());
    }

    @Test
    @DisplayName("★포털_향에서_포털_전용_헤더로_실린_refresh_토큰도_거부된다")
    void refreshViaPortalHeaderInPortalFlavorIsRejected() throws Exception {
        assertThat(run(DeployFlavor.PORTAL, null, token(b -> b.claim("type", "refresh")))).isFalse();
    }

    @Test
    @DisplayName("관제_향에서_포털_전용_헤더로_실린_refresh_토큰도_거부된다")
    void refreshViaPortalHeaderInControlFlavorIsRejected() throws Exception {
        assertThat(run(DeployFlavor.CONTROL, null, token(b -> b.claim("type", "refresh")))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFRESH", "Refresh", " refresh "})
    @DisplayName("종류_값은_대소문자_앞뒤공백과_무관하게_거부된다")
    void refreshCaseInsensitive(String type) throws Exception {
        assertThat(run(DeployFlavor.CONTROL, "Bearer " + token(b -> b.claim("type", type)), null)).isFalse();
    }

    // ─────────────────────────────── ★ 대조 — 과잉 차단 없음 (AC-1016) ───────────────────────────────

    @Test
    @DisplayName("★같은_키의_access_토큰은_Bearer_와_포털_헤더_모두에서_통과한다")
    void accessSameKeyPasses() throws Exception {
        assertThat(run(DeployFlavor.CONTROL, "Bearer " + token(b -> b.claim("type", "access")), null)).isTrue();
        assertThat(run(DeployFlavor.PORTAL, null, token(b -> b.claim("type", "access")))).isTrue();
    }

    @Test
    @DisplayName("★실제_관제_모양의_access_토큰은_종전대로_식별_경로를_탄다")
    void controlShapedAccessPasses() throws Exception {
        when(userRoleResolver.resolveUserNoByUserId("admin")).thenReturn(9001L);
        when(userRoleResolver.resolve(9001L)).thenReturn(Role.ADMIN);

        assertThat(run(DeployFlavor.CONTROL, "Bearer " + controlShapedToken("access"), null)).isTrue();
        verify(userRoleResolver).resolveUserNoByUserId("admin");
    }

    @Test
    @DisplayName("★종류_클레임이_없는_토큰은_종전대로_통과한다_개발로그인_포털인계")
    void noTypeClaimPasses() throws Exception {
        assertThat(run(DeployFlavor.CONTROL, "Bearer " + token(b -> b.claim("channel", "INTERNAL")), null)).isTrue();
        assertThat(run(DeployFlavor.PORTAL, null, token(b -> { }))).isTrue();
    }

    @Test
    @DisplayName("문자열이_아닌_종류_값은_종전대로_수용한다_과잉차단_금지")
    void nonStringTypePasses() throws Exception {
        assertThat(run(DeployFlavor.CONTROL, "Bearer " + token(b -> b.claim("type", 1)), null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh_token", "refreshed", "access", "id"})
    @DisplayName("refresh_와_정확히_같지_않은_종류_값은_거부하지_않는다")
    void otherTypeValuesPass(String type) throws Exception {
        assertThat(run(DeployFlavor.CONTROL, "Bearer " + token(b -> b.claim("type", type)), null)).isTrue();
    }
}
