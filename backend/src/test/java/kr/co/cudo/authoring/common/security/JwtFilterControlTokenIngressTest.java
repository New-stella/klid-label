package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.JwtBuilder;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관제 인계 JWT 진입 수용 — <b>식별 축(userId 클레임)과 이름 폴백(userNm)</b>을 고정한다
 * (@design ADR-063 · UC-041 · AC-1016).
 *
 * <p>필터를 목 협력자로 직접 조립해, DB 없이 "어떤 클레임을 어느 조회로 잇는가"와 "자동 등록에
 * 어떤 이름을 넘기는가"를 호출 인자로 본다. 실제 조회·인가의 end-to-end 는
 * {@code JwtAuthenticationFilterTest}(Testcontainers)가 별도로 고정한다.
 */
class JwtFilterControlTokenIngressTest {

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

    /** subject·userId·name·userNm 를 선택적으로 실은 INTERNAL 토큰으로 필터를 1회 돌린다. */
    private void doFilter(String subject, String userId, String name, String userNm) throws Exception {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .subject(subject)
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key);
        if (userId != null) {
            builder.claim("userId", userId);
        }
        if (name != null) {
            builder.claim("name", name);
        }
        if (userNm != null) {
            builder.claim("userNm", userNm);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + builder.compact());
        SecurityContextHolder.clearContext();
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }

    private boolean hasAuthority(String authority) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }

    /** 인증 principal(TokenClaims)의 주체 식별자(sub). 미인증이면 null. */
    private String principalSub() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            return null;
        }
        return claims.sub();
    }

    @Test
    @DisplayName("비숫자_sub는_userId클레임으로_USER_ID를_조회해_역할을_해석한다")
    void nonNumericSubResolvesViaUserId() throws Exception {
        // 관제 인계: sub="admin"(비숫자) → userId 클레임으로 조회한다(@design ADR-063 · UC-041).
        when(userRoleResolver.resolveUserNoByUserId("ctrl-admin")).thenReturn(880001L);
        when(userRoleResolver.resolve(880001L)).thenReturn(Role.REVIEWER);

        doFilter("admin", "ctrl-admin", null, null);

        verify(userRoleResolver).resolveUserNoByUserId("ctrl-admin");
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isTrue();
        // 자동등록은 숫자 sub 전용이다 — userId 조회 경로는 삽입할 userNo 를 지어내지 않는다.
        verify(autoWorkerRegistrar, never()).registerAsWorker(anyLong(), any());
    }

    @Test
    @DisplayName("비숫자_sub_userId가_다중_매칭이면_발급없이_무권한이고_자동등록도_없다")
    void nonNumericSubFailsClosedWhenUserIdAmbiguous() throws Exception {
        // repo 가 다중/0건을 empty 로 축약해 resolveUserNoByUserId 가 null 을 돌려준다(CWE-639).
        //   0건이면 발급으로 이어지지만, 다중 매칭이면 프로비저너가 발급 없이 null 을 돌려
        //   fail-closed 로 닫는다(AC-1017). 여기서는 그 다중 경로를 고정한다.
        when(userRoleResolver.resolveUserNoByUserId("dup")).thenReturn(null);
        when(controlUserProvisioner.provision("dup", null)).thenReturn(null);

        doFilter("admin", "dup", null, null);

        assertThat(hasAuthority("CHANNEL_" + Channel.INTERNAL.name())).isTrue();
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isFalse();
        assertThat(hasAuthority("ROLE_" + Role.WORKER.name())).isFalse();
        // 프로비저너는 호출되지만 발급하지 않는다 → userNo null → 역할 조회·자동등록 없음(fail-closed).
        verify(controlUserProvisioner).provision("dup", null);
        verify(userRoleResolver, never()).resolve(anyLong());
        verify(autoWorkerRegistrar, never()).registerAsWorker(anyLong(), any());
    }

    @Test
    @DisplayName("비숫자_sub_userId_0건이면_userNo를_발급받아_역할해석과_principal정규화가_이어진다")
    void nonNumericSubZeroMatchProvisionsUserNo() throws Exception {
        // @design ADR-063 ⑤ · UC-041 step5 · AC-1016 — 0건이면 resolveUserNoByUserId 는 null 이지만
        //   프로비저너가 disjoint 상위 userNo 를 발급하고, 그 userNo 로 역할 조회(발급 직후 무권한)와
        //   principal 정규화(role-claim 식별 성립)가 이어진다.
        when(userRoleResolver.resolveUserNoByUserId("newbie")).thenReturn(null);
        when(controlUserProvisioner.provision("newbie", null)).thenReturn(9_000_000_005L);
        when(userRoleResolver.resolve(9_000_000_005L)).thenReturn(null);

        doFilter("newbie-login", "newbie", null, null);

        verify(controlUserProvisioner).provision("newbie", null);
        // 발급 userNo 로 역할 조회가 이어진다(무권한이지만 조회는 발생 = 발급이 식별을 이었다).
        verify(userRoleResolver).resolve(9_000_000_005L);
        // 발급 userNo 로 principal 정규화가 이어진다 — 다운스트림 parseLong 이 성립한다.
        assertThat(principalSub()).isEqualTo("9000000005");
        assertThat(principalSub()).isNotEqualTo("newbie-login");
        // 자동등록(WORKER)은 발급 진입자 대상이 아니다 — 숫자 sub 전용이라 호출조차 없다.
        verify(autoWorkerRegistrar, never()).registerAsWorker(anyLong(), any());
    }

    @Test
    @DisplayName("숫자_sub는_userId조회를_타지_않는다_기존_경로_회귀0")
    void numericSubNeverTouchesUserIdLookup() throws Exception {
        when(userRoleResolver.resolve(7L)).thenReturn(Role.REVIEWER);

        doFilter("7", "ignored-user-id", null, null);

        // userId 클레임이 실려 있어도 숫자 sub 면 조회를 타지 않는다(기존 경로 무변경).
        verify(userRoleResolver, never()).resolveUserNoByUserId(anyString());
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isTrue();
    }

    @Test
    @DisplayName("name이_없으면_userNm으로_자동등록_표시명을_채운다")
    void nameFallsBackToUserNmForAutoRegistration() throws Exception {
        // @design ADR-063 ③ — 관제 토큰은 name 이 없고 이름을 userNm 에 넣는다. 숫자 sub + 무역할
        //   진입자가 자동 등록될 때 그 값이 표시명으로 넘어가야 한다.
        when(userRoleResolver.resolve(4242L)).thenReturn(null);
        when(autoWorkerRegistrar.registerAsWorker(4242L, "홍길동")).thenReturn(Role.WORKER);

        doFilter("4242", null, null, "홍길동");

        verify(autoWorkerRegistrar).registerAsWorker(4242L, "홍길동");
        assertThat(hasAuthority("ROLE_" + Role.WORKER.name())).isTrue();
    }

    @Test
    @DisplayName("name이_있으면_userNm이_아니라_name으로_등록한다_내부토큰_회귀0")
    void nameClaimTakesPrecedenceOverUserNm() throws Exception {
        when(userRoleResolver.resolve(4242L)).thenReturn(null);
        when(autoWorkerRegistrar.registerAsWorker(4242L, "직접이름")).thenReturn(Role.WORKER);

        doFilter("4242", null, "직접이름", "폴백이름");

        verify(autoWorkerRegistrar).registerAsWorker(4242L, "직접이름");
        verify(autoWorkerRegistrar, never()).registerAsWorker(4242L, "폴백이름");
    }

    // ── principal 신원 정규화 (@design ADR-063 결정④ · UC-041 step4 · AC-1016) ──────────────

    @Test
    @DisplayName("비숫자_sub가_userId로_유일해석되면_principal_sub가_해석된_userNo로_정규화된다")
    void nonNumericSubNormalizedToResolvedUserNo() throws Exception {
        // AC-1016 then[6] / AC 1·2 — sub="admin"(로그인 ID)가 userId 유일 매칭으로 880001 을
        //   해석하면, principal 의 sub 는 더 이상 "admin" 이 아니라 해석된 userNo 문자열이다.
        //   그래야 다운스트림 parseUserNo·문자열 직접사용이 실제 userNo 를 본다.
        when(userRoleResolver.resolveUserNoByUserId("ctrl-admin")).thenReturn(880001L);
        when(userRoleResolver.resolve(880001L)).thenReturn(Role.REVIEWER);

        doFilter("admin", "ctrl-admin", null, null);

        assertThat(principalSub()).isEqualTo("880001");
        assertThat(principalSub()).isNotEqualTo("admin");
    }

    @Test
    @DisplayName("비숫자_sub가_다중또는0건_매칭이면_principal_sub는_raw유지_fail_closed")
    void ambiguousUserIdKeepsRawPrincipalSub() throws Exception {
        // AC 3 — 해석 실패(userNo==null)면 정규화하지 않고 raw sub 를 유지한다. 이 raw 는
        //   다운스트림 parseUserNo 에서 null 로 떨어져 기존 fail-closed 가 그대로 성립한다.
        //   다중 매칭 경로라 프로비저너도 발급 없이 null 을 돌린다(AC-1017).
        when(userRoleResolver.resolveUserNoByUserId("dup")).thenReturn(null);
        when(controlUserProvisioner.provision("dup", null)).thenReturn(null);

        doFilter("admin", "dup", null, null);

        assertThat(principalSub()).isEqualTo("admin");
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isFalse();
    }

    @Test
    @DisplayName("숫자_sub는_principal_sub가_원문_그대로다_zero_padded도_보존_회귀0")
    void numericSubPrincipalUnchanged() throws Exception {
        // AC 4 — 숫자 sub 경로는 정규화하지 않는다. 특히 zero-padded("00123")가
        //   String.valueOf(123)="123" 으로 바뀌면 per-user 스코프 키가 어긋나므로 원문 보존이
        //   회귀 0 의 핵심이다.
        when(userRoleResolver.resolve(123L)).thenReturn(Role.WORKER);

        doFilter("00123", "ignored-user-id", null, null);

        assertThat(principalSub()).isEqualTo("00123");
        // 그럼에도 역할 조회는 파싱된 123L 로 정상 수행된다(식별과 principal 문자열은 별개 축).
        verify(userRoleResolver).resolve(123L);
    }
}
