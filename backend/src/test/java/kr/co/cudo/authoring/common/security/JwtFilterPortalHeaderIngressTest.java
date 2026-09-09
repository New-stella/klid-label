package kr.co.cudo.authoring.common.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import kr.co.cudo.authoring.auth.jwt.JwtIssuerValidator;
import kr.co.cudo.authoring.common.logging.LogMaskingPatterns;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 전용 인계 헤더({@code x-access-token}) inbound 수용 (@design INT-013 · ADR-012).
 *
 * <p>포털 채널은 저작도구 프론트를 Host 화면 안에서 실행하는 임베딩이라 같은 출처 브라우저 저장소로
 * 토큰을 넘겨받는 전제가 성립하지 않는다. Host 가 주입한 인계 창구에서 얻은 토큰을 <b>전용 헤더</b>로
 * 싣는다(Bearer 스킴 미사용). 이 시험이 고정하는 것은 셋이다.
 *
 * <ol>
 *   <li><b>인계 자리는 둘, 검증 경로는 하나</b> — 어느 헤더로 들어와도 같은 서명·발급자·만료 검증을
 *       탄다. 인증 입구가 느는 변경이라 이것이 최우선이다.</li>
 *   <li><b>충돌은 fail-closed</b> — 값이 다르면 거부, 같으면 통과.</li>
 *   <li><b>채널 판정은 클레임이 소유한다</b> — 헤더로 채널을 추정하지 않는다.</li>
 * </ol>
 *
 * <p>필터를 목 협력자로 직접 조립해 DB 없이 헤더 축만 본다. 실제 인가까지의 end-to-end(401/200)는
 * {@code JwtAuthenticationFilterTest}(Testcontainers)가 별도로 고정한다.
 */
class JwtFilterPortalHeaderIngressTest {

    private static final String PORTAL_HEADER = JwtAuthenticationFilter.PORTAL_TOKEN_HEADER;

    private SecretKey key;
    private UserRoleResolver userRoleResolver;
    private AutoWorkerRegistrar autoWorkerRegistrar;
    private ControlUserProvisioner controlUserProvisioner;
    private LastLoginRecorder lastLoginRecorder;
    private JwtIssuerValidator issuerValidator;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        key = randomKey();

        userRoleResolver = mock(UserRoleResolver.class);
        autoWorkerRegistrar = mock(AutoWorkerRegistrar.class);
        controlUserProvisioner = mock(ControlUserProvisioner.class);
        lastLoginRecorder = mock(LastLoginRecorder.class);
        issuerValidator = mock(JwtIssuerValidator.class);
        when(issuerValidator.isAllowed(any())).thenReturn(true);
        // 래퍼 반환 목은 미스텁 시 0L 을 돌려준다 — principal.sub 가 "0" 으로 정규화되므로 명시 스텁.
        when(controlUserProvisioner.provision(anyString(), any())).thenReturn(null);
        when(userRoleResolver.resolve(anyLong())).thenReturn(Role.REVIEWER);

        filter = new JwtAuthenticationFilter(() -> key, issuerValidator,
                userRoleResolver, lastLoginRecorder, autoWorkerRegistrar, controlUserProvisioner,
                new PortalSystemSubjectPolicy(""));
    }

    private static SecretKey randomKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 유효한 INTERNAL 토큰(sub=1 숫자) — 헤더 축만 보기 위해 나머지는 전부 정상값으로 고정한다. */
    private String internalToken() {
        return token(t -> { });
    }

    private String token(java.util.function.Consumer<JwtBuilder> customizer) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .subject("1")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key);
        customizer.accept(builder);
        return builder.compact();
    }

    /** 헤더 이름·값 쌍을 그대로 실어 필터를 1회 돌린다(값이 null 이면 그 헤더는 붙이지 않는다). */
    private void doFilter(String authorization, String portalHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        if (portalHeader != null) {
            request.addHeader(PORTAL_HEADER, portalHeader);
        }
        SecurityContextHolder.clearContext();
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }

    private boolean authenticated() {
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    /** 인증 principal(TokenClaims)의 주체 식별자(sub). 미인증이면 null. */
    private String principalSub() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            return null;
        }
        return claims.sub();
    }

    private boolean hasAuthority(String authority) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }

    // ────────────────────────────── 수용기준: 두 자리 모두 통한다 ──────────────────────────────

    @Test
    @DisplayName("★전용헤더만_실린_요청이_인증을_통과한다 — 포털_인계_수용")
    void portalHeaderAloneAuthenticates() throws Exception {
        doFilter(null, internalToken());

        assertThat(authenticated()).isTrue();
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isTrue();
    }

    @Test
    @DisplayName("Authorization_Bearer만_실린_요청은_종전과_동일하게_통과한다 — 관제_회귀0")
    void bearerAloneStillAuthenticates() throws Exception {
        doFilter("Bearer " + internalToken(), null);

        assertThat(authenticated()).isTrue();
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isTrue();
    }

    @Test
    @DisplayName("전용헤더는_Bearer_접두를_요구하지_않는다 — 접두를_붙이면_오히려_거부된다")
    void portalHeaderRejectsBearerPrefix() throws Exception {
        // 계약이 Bearer 스킴 미사용이다. 접두가 붙어 오면 그 문자열 전체가 토큰이 되어 파싱에서
        //   거부된다 — 조용히 벗겨 주지 않는다(관대 처리는 계약을 두 갈래로 만든다).
        doFilter(null, "Bearer " + internalToken());

        assertThat(authenticated()).isFalse();
    }

    // ────────────────────────────── 수용기준: 충돌 정책 ──────────────────────────────

    @Test
    @DisplayName("★두_헤더가_함께_오고_값이_다르면_거부한다 — 모호한_인증상태_fail_closed")
    void conflictingHeadersAreRejected() throws Exception {
        // 두 토큰 모두 <그 자체로는 유효>하다 — 그런데도 채택하지 않는 것이 이 정책의 핵심이다.
        String a = token(b -> b.subject("1"));
        String b = token(t -> t.subject("2"));
        assertThat(a).isNotEqualTo(b);

        doFilter("Bearer " + a, b);

        assertThat(authenticated()).isFalse();
        // 어느 쪽도 채택하지 않았다 — 역할 해석 자체에 도달하지 않는다.
        verify(userRoleResolver, never()).resolve(anyLong());
        verify(lastLoginRecorder, never()).record(any());
    }

    @Test
    @DisplayName("★두_헤더가_같은_값이면_통과한다 — 무해한_중복은_가외로_막지_않는다")
    void identicalHeadersPass() throws Exception {
        String token = internalToken();

        doFilter("Bearer " + token, token);

        assertThat(authenticated()).isTrue();
        assertThat(hasAuthority("ROLE_" + Role.REVIEWER.name())).isTrue();
    }

    @Test
    @DisplayName("충돌_판정은_Bearer_접두를_벗긴_토큰값으로_한다 — 원문_헤더_비교가_아니다")
    void conflictComparesTokenNotRawHeader() throws Exception {
        // "Bearer X" 와 "X" 는 원문으로는 다르지만 <같은 토큰>이다. 원문끼리 비교하면 정상 요청이
        //   전량 충돌로 판정돼 포털+관제 병행 형상이 통째로 막힌다.
        String token = internalToken();

        doFilter("Bearer " + token, token);

        assertThat(authenticated()).isTrue();
    }

    // ────────────────────────────── 수용기준: 검증을 건너뛰는 조합이 없다 ──────────────────────────────

    @Test
    @DisplayName("★★서명_위조_토큰은_전용헤더로_와도_거부된다 — 검증_공유")
    void forgedSignatureRejectedOnPortalHeader() throws Exception {
        SecretKey other = randomKey();
        Instant now = Instant.now();
        String forged = Jwts.builder()
                .subject("1")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(other)
                .compact();

        doFilter(null, forged);

        assertThat(authenticated()).isFalse();
        verify(userRoleResolver, never()).resolve(anyLong());
    }

    @Test
    @DisplayName("★★만료_토큰은_전용헤더로_와도_거부된다 — 검증_공유")
    void expiredTokenRejectedOnPortalHeader() throws Exception {
        Instant now = Instant.now();
        String expired = Jwts.builder()
                .subject("1")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key)
                .compact();

        doFilter(null, expired);

        assertThat(authenticated()).isFalse();
    }

    @Test
    @DisplayName("★★exp_없는_토큰은_전용헤더로_와도_거부된다 — A-ISSUE-01_게이트_공유")
    void tokenWithoutExpRejectedOnPortalHeader() throws Exception {
        String noExp = Jwts.builder()
                .subject("1")
                .claim("channel", "INTERNAL")
                .issuedAt(Date.from(Instant.now()))
                .signWith(key)
                .compact();

        doFilter(null, noExp);

        assertThat(authenticated()).isFalse();
    }

    @Test
    @DisplayName("★★허용목록_밖_발급자는_전용헤더로_와도_거부된다 — issuer_게이트_공유")
    void unknownIssuerRejectedOnPortalHeader() throws Exception {
        when(issuerValidator.isAllowed(any())).thenReturn(false);

        doFilter(null, token(b -> b.issuer("evil-issuer")));

        assertThat(authenticated()).isFalse();
        verify(userRoleResolver, never()).resolve(anyLong());
    }

    // ────────────────────────────── 수용기준: 빈 값·공백은 「없음」 ──────────────────────────────

    @Test
    @DisplayName("공백만_실린_전용헤더는_없음과_같다 — 단독이면_미인증")
    void blankPortalHeaderIsAbsent() throws Exception {
        doFilter(null, "   ");

        assertThat(authenticated()).isFalse();
    }

    @Test
    @DisplayName("공백만_실린_전용헤더는_충돌을_만들지_않는다 — Authorization으로_통과")
    void blankPortalHeaderDoesNotConflict() throws Exception {
        doFilter("Bearer " + internalToken(), "  ");

        assertThat(authenticated()).isTrue();
    }

    @Test
    @DisplayName("공백만_실린_Authorization은_없음과_같다 — 전용헤더로_통과")
    void blankBearerDoesNotConflict() throws Exception {
        doFilter("Bearer    ", internalToken());

        assertThat(authenticated()).isTrue();
    }

    @Test
    @DisplayName("Bearer_접두가_없는_Authorization은_종전대로_무시된다 — 회귀0")
    void authorizationWithoutBearerPrefixIgnored() throws Exception {
        String token = internalToken();

        doFilter(token, null);

        assertThat(authenticated()).isFalse();
    }

    // ────────────────── 다듬기(trim)의 적용 범위 — 「비교」에만, 파싱 값은 원문 ──────────────────

    /**
     * {@code Authorization} 단독 경로에서 <b>종전(HEAD)과 동일하게 거부되어야 하는</b> 형태들.
     *
     * <p>{@code {T}} 자리에 유효 토큰이 들어간다. {@link String#trim()} 은 U+0020 <b>이하 전 문자</b>를
     * 벗기므로, 파싱 값에 다듬기를 걸면 아래가 전부 <b>거부 → 통과</b>로 뒤집힌다(회귀). 권한이 오르지는
     * 않지만 자격증명 문자열 해석이 앞단 구성요소와 갈라져 해석 차이 표면(CWE-436)이 생긴다.
     */
    private static final String[] PADDED_AUTHORIZATION_FORMS = {
            "Bearer  {T}",    // 공백 2개 — 값 <내부>라 앞단 OWS 제거를 통과해 서버까지 확실히 도달한다
            "Bearer {T} ",    // 뒤 공백
            "Bearer \t{T}",   // 탭 (앞)
            "Bearer {T}\t",   // 탭 (뒤)
            "Bearer {T}\r",   // CR
            "Bearer \u0001{T}", // C0 제어문자 (앞)
            "Bearer {T}\u001F", // C0 제어문자 (뒤)
    };

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    @DisplayName("★Authorization_단독은_여분공백_제어문자를_종전대로_거부한다 — 관제_경로_회귀0")
    void paddedAuthorizationAloneStillRejected(int form) throws Exception {
        // 다듬기를 파싱 값에까지 걸면 이 7형이 전부 <통과>로 뒤집힌다. 그것이 이 시험이 막는 회귀다.
        //   ※ 실제 Tomcat 은 값 앞뒤 OWS 를 벗기고 일부 제어문자를 400 으로 막을 수 있어 일부 형태는
        //     서버까지 도달하지 않을 수 있다. 그러나 form 0(공백 2개)은 값 <내부>라 그대로 전달되므로
        //     최소 한 형태는 확실히 도달하며, 그것만으로 회귀가 성립한다.
        String header = PADDED_AUTHORIZATION_FORMS[form].replace("{T}", internalToken());

        doFilter(header, null);

        assertThat(authenticated())
                .as("Authorization 단독 경로는 파싱 값을 다듬지 않는다: %s",
                        PADDED_AUTHORIZATION_FORMS[form])
                .isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    @DisplayName("★★Authorization_단독의_파싱값은_HEAD모델과_바이트동일하다 — 해석차이_표면_0 (CWE-436)")
    void authorizationAloneTokenIsByteIdenticalToHeadModel(int form) {
        // 위 시험은 <결과>(미인증)를 보고, 이 시험은 <파서에 넘어가는 값 자체>를 본다.
        //   HEAD 모델 = header.substring("Bearer ".length()) — 그 값과 바이트 단위로 같으면
        //   Authorization 단독 경로의 해석은 정의상 갈릴 수 없다(결과 비교보다 강한 증거다).
        String header = PADDED_AUTHORIZATION_FORMS[form].replace("{T}", internalToken());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", header);

        String headModel = header.substring("Bearer ".length());

        assertThat(JwtAuthenticationFilter.extractToken(request).token())
                .as("Authorization 단독 파싱값은 Bearer 를 벗긴 원문 그대로여야 한다: %s",
                        PADDED_AUTHORIZATION_FORMS[form])
                .isEqualTo(headModel);
    }

    @Test
    @DisplayName("★두_헤더_병존시에는_다듬어_비교해_거짓충돌을_만들지_않는다 — 새_조합이라_회귀가_아니다")
    void paddedHeadersCompareTrimmedWhenBothPresent() throws Exception {
        // "Bearer  X"(여분 공백)와 "X" 는 <같은 토큰>이다. 원문끼리 비교하면 정상 요청이 충돌로
        //   거부된다. 그래서 <병존할 때만> 다듬어 비교하고 그 값을 파서에 넘긴다.
        // ⚠ 이 조합(두 헤더가 함께 옴)은 전용 헤더가 생기기 전에는 <존재하지 않던 새 조합>이라,
        //   여기서 다듬은 값을 쓰는 것은 위 단독 경로의 회귀와 성격이 다르다.
        String token = internalToken();

        doFilter("Bearer  " + token + " ", " " + token);

        assertThat(authenticated()).isTrue();
    }

    // ────────────────────────────── 알려진 사각 — 동작을 「고정」만 한다 ──────────────────────────────

    @Test
    @DisplayName("⚠사각_같은_헤더가_두_번_실리면_첫_값만_본다 — 충돌_판정에_닿지_않는다(동작_고정)")
    void duplicateHeaderOccurrencesUseFirstValueOnly() throws Exception {
        // getHeader 는 첫 값만 돌려주므로 두 번째 값은 무시되고 <충돌로 판정되지 않는다>.
        //   Authorization 도 종전부터 동일해 새로 생긴 위험은 아니나, 「모호한 인증 상태를 거부한다」는
        //   선언이 이 형태에서는 성립하지 않는다. 동작을 넓히지 않고 <있는 그대로> 고정해,
        //   다음 감사가 결함으로 재발견하지 않게 한다(배포 형상의 프록시 헤더 병합 정책 확인 대상).
        String first = token(b -> b.subject("1"));
        String second = token(b -> b.subject("2"));
        assertThat(first).isNotEqualTo(second);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(PORTAL_HEADER, first);
        request.addHeader(PORTAL_HEADER, second);
        SecurityContextHolder.clearContext();
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(authenticated()).isTrue();
        assertThat(principalSub()).isEqualTo("1");
    }

    @Test
    @DisplayName("⚠같은_토큰이라도_전용헤더에_Bearer접두를_붙이면_충돌로_거부된다 — 계약의_귀결(동작_고정)")
    void sameTokenWithBearerPrefixOnPortalHeaderConflicts() throws Exception {
        // 전용 헤더는 Bearer 스킴을 쓰지 않는다. 그래서 양쪽에 "Bearer T" 를 그대로 복사하면
        //   Authorization 은 "T", 전용 헤더는 "Bearer T" 로 해석돼 <다른 값>이 되어 거부된다.
        //   프론트 전환 때 "두 헤더에 같은 문자열을 넣으면 된다"고 오해하기 쉬운 지점이라 고정한다.
        String header = "Bearer " + internalToken();

        doFilter(header, header);

        assertThat(authenticated()).isFalse();
    }

    // ────────────────────────────── 채널 판정은 클레임이 소유 ──────────────────────────────

    @Test
    @DisplayName("★전용헤더로_와도_channel_클레임이_INTERNAL이면_INTERNAL이다 — 헤더로_채널을_추정하지_않는다")
    void portalHeaderDoesNotImplyPortalChannel() throws Exception {
        doFilter(null, internalToken());

        assertThat(hasAuthority("CHANNEL_" + Channel.INTERNAL.name())).isTrue();
        assertThat(hasAuthority("CHANNEL_" + Channel.PORTAL.name())).isFalse();
    }

    @Test
    @DisplayName("★Authorization으로_와도_channel_클레임이_PORTAL이면_PORTAL이다 — 대칭")
    void bearerHeaderDoesNotImplyInternalChannel() throws Exception {
        doFilter("Bearer " + token(b -> b.subject("alice").claim("channel", "PORTAL")), null);

        assertThat(hasAuthority("CHANNEL_" + Channel.PORTAL.name())).isTrue();
        assertThat(hasAuthority("ROLE_" + Role.PORTAL_USER.name())).isTrue();
    }

    @Test
    @DisplayName("channel_클레임이_없으면_전용헤더로_와도_INTERNAL_기본이다 — 현행_동작_보존")
    void missingChannelClaimStaysInternalOnPortalHeader() throws Exception {
        Instant now = Instant.now();
        String noChannel = Jwts.builder()
                .subject("1")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key)
                .compact();

        doFilter(null, noChannel);

        assertThat(hasAuthority("CHANNEL_" + Channel.INTERNAL.name())).isTrue();
    }

    // ────────────────────────────── 로그 가림 ──────────────────────────────

    @Test
    @DisplayName("★전용헤더_값은_로그에서_가려진다 — 받는_쪽이_늘면_찍힐_자리도_는다 (CWE-532)")
    void portalHeaderIsMaskedInLogs() throws Exception {
        String token = internalToken();

        String masked = LogMaskingPatterns.mask(PORTAL_HEADER + ": " + token);

        assertThat(masked).doesNotContain(token);
    }
}
