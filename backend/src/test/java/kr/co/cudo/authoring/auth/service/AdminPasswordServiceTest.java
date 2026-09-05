package kr.co.cudo.authoring.auth.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.auth.dto.AdminPasswordChangeRequest;
import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.auth.repository.LsMngrPswdRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 관리자 패스워드 교체. [@design AC-121] [@design AC-123] [@design API-223]
 *
 * <p>이 파일이 지키는 것은 넷이다 — ①현재 패스워드 재확인이 <b>실제로 막는가</b> ②교체가 <b>그 전에
 * 발급된 유효창을 전부</b> 끊는가(방금 쓴 것도 포함) ③같은 값 재설정을 거부하는가 ④값이 응답·로그
 * 어디에도 남지 않는가.
 */
class AdminPasswordServiceTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "admin-password-service-test-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private static final String CURRENT = "current-admin-secret";
    private static final String NEXT = "next-admin-secret";

    private LsMngrPswd row;
    private AdminPasswordVerifier verifier;
    private AdminSessionTokenService tokenService;
    private AdminPasswordService service;
    private ListAppender<ILoggingEvent> logAppender;

    /** 이 창구의 정상 호출자 — 관리자다(ADR-055 · AC-072 · ROLE-004 SCREEN-041). */
    private static TokenClaims admin() {
        return new TokenClaims("1", Role.ADMIN, Channel.INTERNAL, null);
    }

    @BeforeEach
    void setUp() {
        row = LsMngrPswd.of(
                new BCryptPasswordEncoder(AdminPasswordVerifier.BCRYPT_COST).encode(CURRENT),
                "1", LocalDateTime.now());

        LsMngrPswdRepository repository = mock(LsMngrPswdRepository.class);
        // 저장은 같은 행 객체를 갱신한다 — 교체 직후 currentHash() 가 새 값을 돌려주는지 보기 위해서다.
        when(repository.findById(LsMngrPswd.SINGLE_ROW_SN)).thenAnswer(inv -> Optional.of(row));
        when(repository.save(any(LsMngrPswd.class))).thenAnswer(inv -> inv.getArgument(0));

        verifier = new AdminPasswordVerifier(repository, "");
        tokenService = new AdminSessionTokenService(RESOLVER, verifier, 10);
        service = new AdminPasswordService(verifier, new RoleClaimRateLimiter(null, 50, 500));

        logAppender = new ListAppender<>();
        logAppender.start();
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(AdminPasswordService.class).addAppender(logAppender);
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(AdminPasswordService.class).setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(AdminPasswordService.class).detachAppender(logAppender);
    }

    private String logs() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("현재_패스워드가_맞으면_바뀐다")
    void changesWithCorrectCurrentPassword() {
        service.change(new AdminPasswordChangeRequest(CURRENT, NEXT), admin());

        assertThat(verifier.matches(NEXT)).isTrue();
        assertThat(verifier.matches(CURRENT)).isFalse();
    }

    @Test
    @DisplayName("★현재_패스워드가_틀리면_401_이고_바뀌지_않는다 — 유효창_탈취가_자격_완전_탈취가_되지_않게")
    void rejectsWrongCurrentPassword() {
        assertThatThrownBy(() -> service.change(
                new AdminPasswordChangeRequest("wrong-current-secret", NEXT), admin()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));

        assertThat(verifier.matches(CURRENT)).isTrue();
        assertThat(verifier.matches(NEXT)).isFalse();
    }

    @Test
    @DisplayName("★교체하면_그_전에_발급된_유효창이_전부_무효가_된다 — 방금_쓴_것도_포함이며_예외_갈래가_없다")
    void changeInvalidatesEveryPreviouslyIssuedSession() {
        Instant now = Instant.parse("2026-08-27T10:00:00Z");
        String sessionUsedForThisChange = tokenService.issue("1", now).token();
        String someoneElsesSession = tokenService.issue("2", now).token();
        // 교체 직전까지는 둘 다 유효하다.
        assertThatCode(() -> tokenService.verify(sessionUsedForThisChange, "1", now.plusSeconds(60)))
                .doesNotThrowAnyException();

        service.change(new AdminPasswordChangeRequest(CURRENT, NEXT), admin());

        assertThatThrownBy(() -> tokenService.verify(sessionUsedForThisChange, "1", now.plusSeconds(60)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> tokenService.verify(someoneElsesSession, "2", now.plusSeconds(60)))
                .isInstanceOf(CustomException.class);

        // 새 자격으로 연 유효창은 정상 동작한다 — 무효화가 기능을 죽이는 것이 아니다.
        String reopened = tokenService.issue("1", now).token();
        assertThatCode(() -> tokenService.verify(reopened, "1", now.plusSeconds(60)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★새_값이_현재_값과_같으면_400 — 바뀌지도_않았는데_유효창만_전부_끊기는_일을_막는다")
    void rejectsUnchangedPassword() {
        assertThatThrownBy(() -> service.change(
                new AdminPasswordChangeRequest(CURRENT, CURRENT), admin()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));

        assertThat(verifier.matches(CURRENT)).isTrue();
    }

    @Test
    @DisplayName("★ADMIN이_아니면_403 — 유효창은_인가를_대체하지_않으며_검수자도_막힌다")
    void deniesNonAdmin() {
        // ★★이 서비스의 판정은 enum 동등 비교라 <역할 계층을 타지 않는다>. 컨트롤러가
        //   hasRole('ADMIN') 이어도 여기가 REVIEWER 로 남아 있으면 관리자가 서비스에서 403 을
        //   받는다(구현 당시 실제로 그렇게 났다). 두 값을 함께 고정한다.
        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, null);
        assertThatThrownBy(() -> service.change(new AdminPasswordChangeRequest(CURRENT, NEXT), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        TokenClaims worker = new TokenClaims("1", Role.WORKER, Channel.INTERNAL, null);
        assertThatThrownBy(() -> service.change(new AdminPasswordChangeRequest(CURRENT, NEXT), worker))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★속도_제한이_현재_패스워드_비교보다_앞이다 (CWE-307) — 무제한_대입_창이_되지_않게")
    void rateLimitConsumedBeforePasswordCompare() {
        // 분 버킷 경계를 넘으면 카운터가 리셋돼 두 번째 호출이 통과한다(알려진 플레이크).
        // 고정 시계를 물려 결정론화한다.
        AdminPasswordService limited = new AdminPasswordService(
                verifier,
                new RoleClaimRateLimiter(null, 1, 50,
                        java.time.Clock.fixed(Instant.parse("2026-08-27T10:00:00Z"), java.time.ZoneOffset.UTC)));

        // 첫 시도는 패스워드 비교까지 가 401.
        assertThatThrownBy(() -> limited.change(
                new AdminPasswordChangeRequest("wrong-current-secret", NEXT), admin()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));

        // 두 번째는 패스워드가 맞아도 비교 전에 막힌다.
        assertThatThrownBy(() -> limited.change(
                new AdminPasswordChangeRequest(CURRENT, NEXT), admin()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(verifier.matches(CURRENT)).isTrue();
    }

    @Test
    @DisplayName("★값도_해시도_로그에_남지_않는다 — 누가_언제_바꿨는지만_남는다 (CWE-532 · AC-123)")
    void neverLogsSecrets() {
        String before = verifier.currentHash();
        assertThatThrownBy(() -> service.change(
                new AdminPasswordChangeRequest("wrong-current-secret", NEXT), admin()))
                .isInstanceOf(CustomException.class);
        service.change(new AdminPasswordChangeRequest(CURRENT, NEXT), admin());

        String logs = logs();
        assertThat(logs).doesNotContain(CURRENT);
        assertThat(logs).doesNotContain(NEXT);
        assertThat(logs).doesNotContain("wrong-current-secret");
        assertThat(logs).doesNotContain(before);
        assertThat(logs).doesNotContain(verifier.currentHash());
        // 행위는 남는다 — 값을 남기지 않는 것과 행위를 남기지 않는 것은 다르다.
        assertThat(logs).contains("[AdminPassword] changed actor=1");
    }
}
