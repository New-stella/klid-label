package kr.co.cudo.authoring.sysconfig.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointUrlValidator;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연동 주소 저장 게이트 (R11) — <b>REVIEWER 권한 위에 관리자 단기 유효창을 하나 더</b> 요구한다.
 *
 * <p>게이트는 컨트롤러가 아니라 서비스에 있다. 이 저장소는 "게이트를 호출처마다 배선하면 샌다"를
 * 반복해서 겪었으므로, 진입점이 늘어도 우회되지 않는 지점에 둔다.
 */
class IntegrationEndpointConfigGuardTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "endpoint-guard-test-signing-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private static final String ENDPOINT_KEY = ConfigKeys.INTEGRATION_AI_SERVER_BASE_URL;
    private static final String PUBLIC_URL = "https://8.8.8.8/";

    private LsSystemConfigRepository repository;
    private AdminSessionTokenService tokenService;
    private SystemConfigService service;
    private TokenClaims reviewer;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        repository = mock(LsSystemConfigRepository.class);
        tokenService = new AdminSessionTokenService(RESOLVER, 10);
        service = new SystemConfigService(repository, new ObjectMapper(),
                tokenService, new IntegrationEndpointUrlValidator(),
                new DeidentifyEndpointTrustGuard(new MockEnvironment()));
        reviewer = new TokenClaims("1001", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
        when(repository.save(any(LsSystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        logAppender = new ListAppender<>();
        logAppender.start();
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(SystemConfigService.class).addAppender(logAppender);
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(SystemConfigService.class).setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(SystemConfigService.class).detachAppender(logAppender);
    }

    private String validToken() {
        return tokenService.issue(reviewer.sub(), Instant.now()).token();
    }

    private void seedNumberKey(String key, String value) {
        when(repository.findByConfigKey(key))
                .thenReturn(Optional.of(LsSystemConfig.create(key, value, "NUMBER", null, "seed")));
    }

    private void endpointRowAbsent() {
        when(repository.findByConfigKey(ENDPOINT_KEY)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("★연동_주소_키를_토큰_없이_저장하면_403")
    void endpointKeyWithoutTokenIsForbidden() {
        endpointRowAbsent();

        assertThatThrownBy(() -> service.update(ENDPOINT_KEY, PUBLIC_URL, reviewer, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★만료된_토큰으로_저장하면_403")
    void endpointKeyWithExpiredTokenIsForbidden() {
        endpointRowAbsent();
        // 이미 만료된 시점에 발급된 토큰.
        String stale = tokenService.issue(reviewer.sub(),
                Instant.now().minus(Duration.ofHours(1))).token();

        assertThatThrownBy(() -> service.update(ENDPOINT_KEY, PUBLIC_URL, reviewer, stale))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★남의_토큰으로_저장하면_403")
    void endpointKeyWithOtherUsersTokenIsForbidden() {
        endpointRowAbsent();
        String othersToken = tokenService.issue("9999", Instant.now()).token();

        assertThatThrownBy(() -> service.update(ENDPOINT_KEY, PUBLIC_URL, reviewer, othersToken))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("유효한_토큰이면_저장된다 — 설정_행이_없어도(시드하지_않는다) 새로_만든다")
    void savesWithValidTokenCreatingRow() {
        endpointRowAbsent();

        assertThatCode(() -> service.update(ENDPOINT_KEY, PUBLIC_URL, reviewer, validToken()))
                .doesNotThrowAnyException();
    }

    /**
     * ★ <b>기대값이 뒤집힌 케이스</b> — 구 동작은 400(내부망 대역 차단)이었다. 대역 차단이 폐지되어
     * (2026-08-10 사용자 확정) 이제 <b>저장된다</b>. 이 연동들은 실제로 내부망에 있을 수 있다.
     */
    @Test
    @DisplayName("★내부망_주소도_저장된다 — 대역_차단_폐지(구 400 → 200)")
    void privateNetworkAddressIsAccepted() {
        endpointRowAbsent();

        assertThatCode(() -> service.update(ENDPOINT_KEY, "http://10.0.0.5", reviewer, validToken()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★스킴_위반은_토큰이_유효해도_여전히_400 — 남은_값_검증은_인증으로_면제되지_않는다")
    void schemeViolationRejectedEvenWithValidToken() {
        endpointRowAbsent();

        assertThatThrownBy(() -> service.update(ENDPOINT_KEY, "ftp://vendor.example.net", reviewer, validToken()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("★인증_검사가_값_검증보다_먼저다 — 토큰_없이는_형식_피드백도_주지_않는다")
    void authorizationPrecedesValueValidation() {
        endpointRowAbsent();

        // 값도 잘못됐고 토큰도 없다 → 403 이어야 한다(400 이면 미인증 상태로 형식 오라클이 열린다).
        assertThatThrownBy(() -> service.update(ENDPOINT_KEY, "ftp://x.example.net", reviewer, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★주소_외_설정_키는_토큰_없이도_저장된다 — 기존_계약_회귀_방지")
    void otherKeysUnaffected() {
        seedNumberKey(ConfigKeys.BATCH_INTERVAL_SEC, "60");

        assertThatCode(() -> service.update(ConfigKeys.BATCH_INTERVAL_SEC, "120", reviewer, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.update(ConfigKeys.BATCH_INTERVAL_SEC, "180", reviewer))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("주소_외_키는_행이_없으면_여전히_404 — 임의_키가_DB에_생기지_않는다")
    void unknownRowStillNotFoundForNonEndpointKeys() {
        when(repository.findByConfigKey(ConfigKeys.BATCH_CONCURRENCY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ConfigKeys.BATCH_CONCURRENCY, "3", reviewer, validToken()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("★화이트리스트_밖의_키는_토큰이_있어도_400")
    void unknownKeyRejected() {
        assertThatThrownBy(() -> service.update("some.rogue.key", PUBLIC_URL, reviewer, validToken()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("★감사 — 주소_변경은_로그에_남고_토큰은_남지_않는다")
    void auditsAddressChangeWithoutToken() {
        endpointRowAbsent();
        String token = validToken();

        service.update(ENDPOINT_KEY, PUBLIC_URL, reviewer, token);

        String logs = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs).contains("연동 주소 변경");
        assertThat(logs).contains(IntegrationEndpoint.AI_SERVER.name());
        assertThat(logs).contains(reviewer.sub());
        assertThat(logs).contains(PUBLIC_URL);       // 주소 값은 남긴다(운영 추적)
        assertThat(logs).doesNotContain(token);      // 자격증명은 남기지 않는다
    }

    @Test
    @DisplayName("연동_주소_4종이_모두_같은_게이트를_지난다 — 하나만_열려_있으면_우회된다")
    void allFourEndpointsGated() {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        for (IntegrationEndpoint endpoint : IntegrationEndpoint.values()) {
            assertThatThrownBy(() -> service.update(endpoint.configKey(), PUBLIC_URL, reviewer, null))
                    .as("%s 는 토큰 없이 저장되면 안 된다", endpoint.name())
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(ConfigKeys.ALLOWED).contains(endpoint.configKey());
        }
    }
}
