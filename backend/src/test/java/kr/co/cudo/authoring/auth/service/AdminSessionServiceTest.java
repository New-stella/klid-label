package kr.co.cudo.authoring.auth.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.dto.AdminSessionRequest;
import kr.co.cudo.authoring.auth.dto.AdminSessionResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 단기 유효창 개시 (R11).
 *
 * <p>기존 자산(관리자 공유 패스워드 해시 · 속도 제한)을 <b>재사용</b>하되 진입점만 분리한 것이
 * 실제로 그렇게 동작하는지, 그리고 <b>패스워드가 어디에도 새지 않는지</b>를 고정한다.
 */
class AdminSessionServiceTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "admin-session-service-test-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private String adminPlaintext;
    private AdminSessionService service;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        byte[] pw = new byte[24];
        new SecureRandom().nextBytes(pw);
        adminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pw);

        // 검증기 하나를 두 곳에 물린다 — 실제 배선과 같다. 유효창 서명 키가 현재 자격에서 파생되므로
        // 서로 다른 자격을 가진 검증기를 물리면 발급한 토큰을 자기 자신도 검증하지 못한다.
        AdminPasswordVerifier verifier =
                new AdminPasswordVerifier(new BCryptPasswordEncoder(12).encode(adminPlaintext));
        service = new AdminSessionService(
                verifier,
                new RoleClaimRateLimiter(null, 5, 50),
                new AdminSessionTokenService(RESOLVER, verifier, 10));

        logAppender = new ListAppender<>();
        logAppender.start();
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(AdminSessionService.class).addAppender(logAppender);
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(AdminSessionService.class).setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(AdminSessionService.class).detachAppender(logAppender);
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1001", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
    }

    private TokenClaims worker() {
        return new TokenClaims("2002", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("패스워드가_맞으면_토큰과_만료시각을_받는다")
    void issuesTokenOnCorrectPassword() {
        AdminSessionResponse res = service.open(new AdminSessionRequest(adminPlaintext), reviewer());

        assertThat(res.token()).isNotBlank();
        assertThat(res.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("패스워드가_틀리면_401")
    void rejectsWrongPassword() {
        assertThatThrownBy(() -> service.open(new AdminSessionRequest("wrong-password"), reviewer()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("미인증이면_401")
    void rejectsUnauthenticated() {
        assertThatThrownBy(() -> service.open(new AdminSessionRequest(adminPlaintext), null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("REVIEWER가_아니면_403 — 패스워드를_보기도_전에_거절한다")
    void rejectsNonReviewer() {
        assertThatThrownBy(() -> service.open(new AdminSessionRequest(adminPlaintext), worker()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★속도_제한을_넘기면_429 — 기존_자가부여와_같은_억제기를_쓴다 (CWE-307)")
    void rateLimitsRepeatedAttempts() {
        // 계정 축 임계 5회/분. 6번째부터 429.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.open(new AdminSessionRequest("wrong"), reviewer()))
                    .isInstanceOf(CustomException.class);
        }

        assertThatThrownBy(() -> service.open(new AdminSessionRequest("wrong"), reviewer()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("★패스워드가_응답에도_로그에도_남지_않는다 (CWE-522/532)")
    void neverLeaksPassword() {
        // 성공 경로
        AdminSessionResponse res = service.open(new AdminSessionRequest(adminPlaintext), reviewer());
        assertThat(res.toString()).doesNotContain(adminPlaintext);
        assertThat(res.token()).doesNotContain(adminPlaintext);

        // 실패 경로 — 여기가 더 흔한 유출 지점이다(사유를 자세히 적다가 값을 싣는다).
        assertThatThrownBy(() -> service.open(new AdminSessionRequest("some-wrong-secret"), reviewer()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("some-wrong-secret"));

        String logs = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs).doesNotContain(adminPlaintext);
        assertThat(logs).doesNotContain("some-wrong-secret");
        assertThat(logs).doesNotContain(res.token());
    }

    @Test
    @DisplayName("해시_미설정이면_항상_401 — 기능이_열리지_않는다 (fail-closed)")
    void deniesWhenHashNotConfigured() {
        AdminPasswordVerifier unconfiguredVerifier = new AdminPasswordVerifier("");
        AdminSessionService unconfigured = new AdminSessionService(
                unconfiguredVerifier,
                new RoleClaimRateLimiter(null, 5, 50),
                new AdminSessionTokenService(RESOLVER, unconfiguredVerifier, 10));

        assertThatThrownBy(() -> unconfigured.open(new AdminSessionRequest(adminPlaintext), reviewer()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("평문_해시가_설정되면_기동_시점에_거절한다 (CWE-256)")
    void rejectsPlaintextHashAtBoot() {
        assertThatThrownBy(() -> new AdminPasswordVerifier("plaintext-not-bcrypt"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AdminPasswordVerifier("")).doesNotThrowAnyException();
    }
}
