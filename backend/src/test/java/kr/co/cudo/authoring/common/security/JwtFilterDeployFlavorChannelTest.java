package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.common.config.DeployFlavor;
import kr.co.cudo.authoring.common.config.DeployFlavorResolver;
import kr.co.cudo.authoring.user.service.AutoWorkerRegistrar;
import kr.co.cudo.authoring.user.service.ControlUserProvisioner;
import kr.co.cudo.authoring.user.service.LastLoginRecorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>채널 판정 축이 배포 향인가</b> — 회귀 가드. [@design ADR-012] [@design INT-013] [@design AC-1103]
 *
 * <h2>왜 이 시험이 필요한가 (2026-09-10 실측 사고)</h2>
 * <p>포털 Host 안에서 저작도구 Remote 가 정상 로드됐는데 화면이 「접근 권한이 없습니다」로 막혔다.
 * 포털 인계 토큰에 {@code channel} 클레임이 없어(계약상 정상 — {@code INT-013} 「계약 경계」)
 * 관제 채널로 해석됐고, 그 결과 <b>포털 회원이 저작도구 내부 작업자로 자동 등록</b>됐다.
 *
 * <p>{@code ADR-012} 가 그 fail-open 을 미리 서술해 두었는데 <b>구현이 따라오지 않았다.</b>
 * {@code AC-1103} 은 <i>"채널 판정 축 결정의 전파 표식이 <b>네 차례 지워졌다</b>"</i> 고 적었다 —
 * 이 시험이 다섯 번째를 막는 장치다.
 *
 * <p>★<b>핵심 가드는 {@link PortalFlavor#포털_향에서는_관제_전용_협력자가_한_번도_불리지_않는다()}</b>
 * 다. 나머지가 다 맞아도 그 협력자들이 돌면 포털 회원이 내부 사용자 공간에 들어간다.
 */
@DisplayName("채널 판정 — 배포 향이 정한다 (ADR-012)")
class JwtFilterDeployFlavorChannelTest {

    private SecretKey key;
    private UserRoleResolver userRoleResolver;
    private AutoWorkerRegistrar autoWorkerRegistrar;
    private ControlUserProvisioner controlUserProvisioner;
    private LastLoginRecorder lastLoginRecorder;
    private JwtIssuerValidator issuerValidator;

    @BeforeEach
    void setUp() {
        key = randomKey();
        userRoleResolver = mock(UserRoleResolver.class);
        autoWorkerRegistrar = mock(AutoWorkerRegistrar.class);
        controlUserProvisioner = mock(ControlUserProvisioner.class);
        lastLoginRecorder = mock(LastLoginRecorder.class);
        issuerValidator = mock(JwtIssuerValidator.class);
        when(issuerValidator.isAllowed(any())).thenReturn(true);
        when(controlUserProvisioner.provision(anyString(), any())).thenReturn(null);
        when(userRoleResolver.resolve(anyLong())).thenReturn(Role.REVIEWER);
        SecurityContextHolder.clearContext();
    }

    // ───────────────────────── 포털 향 ─────────────────────────

    @Nested
    @DisplayName("포털 향 배포본")
    class PortalFlavor {

        @Test
        @DisplayName("channel 클레임이 없는 포털 인계 토큰을 PORTAL 로 해석한다")
        void 채널클레임이_없어도_포털로_해석한다() throws Exception {
            // 실측한 포털 토큰의 모양 — channel 도 iss 도 없고 role 은 포털 어휘다.
            TokenClaims claims = runPortalFlavor(portalShapedToken());

            assertThat(claims.channel()).isEqualTo(Channel.PORTAL);
            assertThat(claims.role()).isEqualTo(Role.PORTAL_USER);
        }

        @Test
        @DisplayName("★ channel:INTERNAL 이 실려 와도 PORTAL 로 «강제»한다 — 기본값 전환이 아니다")
        void 토큰에_내부채널이_실려도_포털로_강제한다() throws Exception {
            TokenClaims claims = runPortalFlavor(
                    token(t -> t.subject("5237").claim("channel", "INTERNAL")));

            // ADR-012: "토큰에 채널 값이 실려 있어도 그 값을 따르지 않는다(기본값 전환이 아니라 강제)"
            assertThat(claims.channel()).isEqualTo(Channel.PORTAL);
            assertThat(claims.role()).isEqualTo(Role.PORTAL_USER);
        }

        @Test
        @DisplayName("상대 시스템의 role 클레임을 인가에 쓰지 않는다 — ADMIN 이 실려도 PORTAL_USER")
        void 상대_역할클레임을_인가에_쓰지_않는다() throws Exception {
            TokenClaims claims = runPortalFlavor(
                    token(t -> t.subject("5237").claim("role", "ADMIN")));

            // INT-013: "상대 시스템의 역할(저작도구 인가 축에 쓰지 않는다)"
            assertThat(claims.role()).isEqualTo(Role.PORTAL_USER);
        }

        @Test
        @DisplayName("★★ 관제 전용 협력자가 한 번도 불리지 않는다 — 포털 회원이 내부 사용자로 등록되지 않는다")
        void 포털_향에서는_관제_전용_협력자가_한_번도_불리지_않는다() throws Exception {
            // 실측 사고의 재발 방지선. sub 가 «숫자»(포털 mbrId)라 종전 코드에서는
            // numericSub 경로가 열려 자동 등록기가 실제로 돌았다.
            runPortalFlavor(portalShapedToken());

            verify(userRoleResolver, never()).resolve(anyLong());
            verify(autoWorkerRegistrar, never()).registerAsWorker(anyLong(), any());
            verify(controlUserProvisioner, never()).provision(any(), any());
            verify(lastLoginRecorder, never()).record(any());
        }

        @Test
        @DisplayName("주체 식별자를 저작도구 사용자 번호로 정규화하지 않는다 — 토큰 원문 그대로")
        void 주체식별자를_정규화하지_않는다() throws Exception {
            // ADR-012: "포털 회원 식별자와 저작도구 사용자 번호는 서로 다른 체계"
            TokenClaims claims = runPortalFlavor(token(t -> t.subject("05237")));

            assertThat(claims.sub()).isEqualTo("05237");
        }

        @Test
        @DisplayName("CHANNEL_PORTAL 권한이 부여된다")
        void 채널권한이_포털로_부여된다() throws Exception {
            runPortalFlavor(portalShapedToken());

            assertThat(authorities()).contains("CHANNEL_PORTAL", "ROLE_PORTAL_USER");
        }
    }

    // ───────────────────────── 관제 향(종전 동작) ─────────────────────────

    @Nested
    @DisplayName("관제 향 배포본 — 종전 동작 무변경")
    class ControlFlavor {

        @Test
        @DisplayName("channel 클레임이 없으면 INTERNAL 로 본다 (ADR-063 — 관제 향에서는 지금도 참이다)")
        void 채널클레임_부재는_내부채널이다() throws Exception {
            TokenClaims claims = runControlFlavor(token(t -> t.subject("1")));

            assertThat(claims.channel()).isEqualTo(Channel.INTERNAL);
        }

        @Test
        @DisplayName("channel:PORTAL 이 실리면 그 값을 따른다")
        void 실린_채널값을_따른다() throws Exception {
            TokenClaims claims = runControlFlavor(
                    token(t -> t.subject("1").claim("channel", "PORTAL")));

            assertThat(claims.channel()).isEqualTo(Channel.PORTAL);
        }

        @Test
        @DisplayName("내부 채널이면 관제 전용 경로가 종전대로 돈다")
        void 관제_전용_경로가_종전대로_돈다() throws Exception {
            runControlFlavor(token(t -> t.subject("1")));

            verify(userRoleResolver).resolve(1L);
            verify(lastLoginRecorder).record(1L);
        }

        @Test
        @DisplayName("배포 향 «미선언» 이면 관제 향으로 떨어진다 (fail-closed)")
        void 미선언이면_관제향이다() throws Exception {
            TokenClaims claims = run(DeployFlavor.parseOrDefault(null), token(t -> t.subject("1")));

            assertThat(claims.channel()).isEqualTo(Channel.INTERNAL);
        }
    }

    // ───────────────────────── 배포 향 해석 ─────────────────────────

    @Nested
    @DisplayName("배포 향 해석")
    class FlavorParsing {

        @Test
        @DisplayName("미선언·공백·값역 밖은 모두 관제 향이다 (fail-closed)")
        void 모호한_값은_관제향이다() {
            assertThat(DeployFlavor.parseOrDefault(null)).isEqualTo(DeployFlavor.CONTROL);
            assertThat(DeployFlavor.parseOrDefault("")).isEqualTo(DeployFlavor.CONTROL);
            assertThat(DeployFlavor.parseOrDefault("   ")).isEqualTo(DeployFlavor.CONTROL);
            assertThat(DeployFlavor.parseOrDefault("Portal!")).isEqualTo(DeployFlavor.CONTROL);
            assertThat(DeployFlavor.parseOrDefault("internal")).isEqualTo(DeployFlavor.CONTROL);
        }

        @Test
        @DisplayName("대소문자·주변 공백을 무시한다 — 손으로 넣은 오타가 반대 채널을 만들지 않게")
        void 대소문자와_공백을_무시한다() {
            assertThat(DeployFlavor.parseOrDefault(" portal ")).isEqualTo(DeployFlavor.PORTAL);
            assertThat(DeployFlavor.parseOrDefault("PORTAL")).isEqualTo(DeployFlavor.PORTAL);
            assertThat(DeployFlavor.parseOrDefault("Control")).isEqualTo(DeployFlavor.CONTROL);
        }

        @Test
        @DisplayName("★ «미선언» 과 «값역 밖» 을 구분한다 — 오기가 조용히 묻히지 않게")
        void 미선언과_값역밖을_구분한다() {
            // 둘 다 CONTROL 로 떨어지지만 운영자에게는 다른 사실이다.
            // 미선언은 의도일 수 있고 값역 밖은 오기다 — 로그 문구가 갈려야 한다.
            assertThat(DeployFlavor.isOutOfRange(null)).isFalse();
            assertThat(DeployFlavor.isOutOfRange("")).isFalse();
            assertThat(DeployFlavor.isOutOfRange("portal")).isFalse();
            assertThat(DeployFlavor.isOutOfRange("Portal!")).isTrue();
            assertThat(DeployFlavor.isOutOfRange("internal")).isTrue();
        }

        @Test
        @DisplayName("값역이 프론트 빌드 채널과 같은 낱말이다 — internal 은 폐기값이라 쓰지 않는다")
        void 값역이_프론트와_같은_낱말이다() {
            Set<String> names = java.util.Arrays.stream(DeployFlavor.values())
                    .map(f -> f.name().toLowerCase())
                    .collect(Collectors.toSet());

            // frontend/src/lib/buildChannel.ts 의 BUILD_CHANNELS 와 같아야 한다.
            assertThat(names).containsExactlyInAnyOrder("control", "portal");
        }
    }

    // ───────────────────────── 도우미 ─────────────────────────

    /** 실측한 포털 인계 토큰의 모양 — channel·iss 없음, role 은 포털 어휘, sub 는 포털 회원번호. */
    private String portalShapedToken() {
        return token(t -> t.subject("5237")
                .claim("loginId", "qaadmin0903")
                .claim("role", "ADMIN")
                .claim("typ", "access"));
    }

    private TokenClaims runPortalFlavor(String jwt) throws Exception {
        return run(DeployFlavor.PORTAL, jwt);
    }

    private TokenClaims runControlFlavor(String jwt) throws Exception {
        return run(DeployFlavor.CONTROL, jwt);
    }

    private TokenClaims run(DeployFlavor flavor, String jwt) throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                () -> key, issuerValidator, userRoleResolver, lastLoginRecorder,
                autoWorkerRegistrar, controlUserProvisioner, DeployFlavorResolver.of(flavor));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).as("인증이 성립해야 판정을 볼 수 있다").isNotNull();
        return (TokenClaims) auth.getPrincipal();
    }

    private Set<String> authorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private String token(java.util.function.Consumer<JwtBuilder> customizer) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key);
        customizer.accept(builder);
        return builder.compact();
    }

    private static SecretKey randomKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
