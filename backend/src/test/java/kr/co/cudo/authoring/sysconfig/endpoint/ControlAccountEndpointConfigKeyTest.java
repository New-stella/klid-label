package kr.co.cudo.authoring.sysconfig.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.AdminSessionTestSupport;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.support.TestAiWaitBudgetPolicies;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.dto.ConfigResponse;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관제 계정 창구 주소를 저장 창구가 받는다 — 관제 통지 수신처와 <b>별개 키</b>로.
 * [@design ADR-046] [@design API-069] [@design AC-1072] [@design AC-1074]
 *
 * <h3>세 자리를 각각 문다</h3>
 * <ul>
 *   <li>{@link ConfigKeys#ALLOWED} — 없으면 저장 요청이 <b>400</b>(허용되지 않은 키).</li>
 *   <li>{@link ConfigKeys#DECLARED_TYPE} — 없으면 시드 행이 없어 최초 저장이 <b>404</b>.</li>
 *   <li>{@link IntegrationEndpoint} — 없으면 주소 형식 검증도 <b>관리자 유효창</b>도 걸리지 않는다
 *       (기능이 아니라 인가가 조용히 약해지는 축).</li>
 * </ul>
 *
 * <p>상태코드는 {@link ErrorCode} 로 단언한다 — FORBIDDEN=403 · INVALID_INPUT=400 · NOT_FOUND=404 매핑은
 * 공통 예외 처리기가 소유하며 이 창구 전 키에 공통이다.
 */
class ControlAccountEndpointConfigKeyTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "control-account-endpoint-key-test-signing-0123456789".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private static final String ACCOUNT_KEY = ConfigKeys.CONTROL_ACCOUNT_URL;
    private static final String CONTROL_URL = "http://10.177.22.90:8080";

    private LsSystemConfigRepository repository;
    private AdminSessionTokenService tokenService;
    private SystemConfigService service;
    private TokenClaims admin;

    @BeforeEach
    void setUp() {
        repository = mock(LsSystemConfigRepository.class);
        tokenService = AdminSessionTestSupport.tokenService(RESOLVER, 10);
        service = new SystemConfigService(repository, new ObjectMapper(),
                new AdminSessionGate(tokenService), new IntegrationEndpointUrlValidator(),
                new DeidentifyEndpointTrustGuard(new MockEnvironment()),
                TestAiWaitBudgetPolicies.production());
        admin = new TokenClaims("2001", Role.ADMIN, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
        // 시드하지 않는 설계 — 모든 키의 행이 없는 상태에서 시작한다.
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(LsSystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private String validWindow() {
        return tokenService.issue(admin.sub(), Instant.now()).token();
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    // ───────────────── 키 이름 = 애플리케이션 속성명 ─────────────────

    /**
     * 키를 속성명과 다르게 만들면 「설정 키 ↔ 속성명」 매핑표가 두 번째 진실원이 된다.
     * 리터럴로 단언하는 것은 의도다 — 상수 값이 바뀌면 이 시험이 깨져야 한다.
     */
    @Test
    @DisplayName("★설정_키는_애플리케이션_속성명_그대로다 — authoring.control-account.url")
    void keyIsApplicationPropertyName() {
        assertThat(ACCOUNT_KEY).isEqualTo("authoring.control-account.url");
        assertThat(ACCOUNT_KEY).isNotEqualTo(ConfigKeys.CONTROL_NOTIFY_URL);
    }

    // ───────────────── 세 자리 ─────────────────

    @Test
    @DisplayName("★자리①_허용_키_목록에_관제_계정_창구_키가_있다 — 없으면_400")
    void accountKeyIsWhitelisted() {
        assertThat(ConfigKeys.ALLOWED).contains(ACCOUNT_KEY);
    }

    @Test
    @DisplayName("★자리②_선언_타입_맵에_STRING으로_있다 — 없으면_최초_저장이_404")
    void accountKeyHasDeclaredType() {
        assertThat(ConfigKeys.DECLARED_TYPE).containsEntry(ACCOUNT_KEY, "STRING");
    }

    @Test
    @DisplayName("★자리③_연동_대상_열거에_관제_계정_창구가_통지와_별개_값으로_있다")
    void accountIsRegisteredAsSeparateEndpoint() {
        assertThat(IntegrationEndpoint.isEndpointKey(ACCOUNT_KEY)).isTrue();
        assertThat(IntegrationEndpoint.CONFIG_KEYS).contains(ACCOUNT_KEY);
        assertThat(IntegrationEndpoint.byConfigKey(ACCOUNT_KEY))
                .contains(IntegrationEndpoint.CONTROL_ACCOUNT);
        assertThat(IntegrationEndpoint.CONTROL_ACCOUNT).isNotSameAs(IntegrationEndpoint.CONTROL_NOTIFY);
        assertThat(IntegrationEndpoint.CONTROL_ACCOUNT.displayName()).isEqualTo("관제 계정 창구");
        // 통지 수신처는 제 키를 그대로 가진다(불변).
        assertThat(IntegrationEndpoint.byConfigKey(ConfigKeys.CONTROL_NOTIFY_URL))
                .contains(IntegrationEndpoint.CONTROL_NOTIFY);
    }

    // ───────────────── 수용기준 ① 유효창으로 저장 200 · ④ 행이 없어도 최초 저장 ─────────────────

    @Test
    @DisplayName("★AC①④_유효창이_있으면_저장되고_행이_없던_상태에서_STRING_행이_새로_생긴다(404_아님)")
    void firstSaveWithWindowCreatesRow() {
        ConfigResponse res = service.update(ACCOUNT_KEY, CONTROL_URL, admin, validWindow());

        assertThat(res.configKey()).isEqualTo(ACCOUNT_KEY);
        assertThat(res.configVl()).isEqualTo(CONTROL_URL);

        ArgumentCaptor<LsSystemConfig> saved = ArgumentCaptor.forClass(LsSystemConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getConfigKey()).isEqualTo(ACCOUNT_KEY);
        assertThat(saved.getValue().getConfigTypeCd()).isEqualTo("STRING");
    }

    // ───────────────── 수용기준 ② 유효창 없음·만료·타인 403 ─────────────────

    @Test
    @DisplayName("★AC②_유효창_없이_저장하면_403이고_행이_생기지_않는다")
    void saveWithoutWindowIsForbidden() {
        assertThatThrownBy(() -> service.update(ACCOUNT_KEY, CONTROL_URL, admin, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    @Test
    @DisplayName("★AC②_만료된_유효창으로_저장하면_403이다")
    void saveWithExpiredWindowIsForbidden() {
        String expired = tokenService.issue(admin.sub(),
                Instant.now().minus(tokenService.ttl()).minus(Duration.ofMinutes(1))).token();

        assertThatThrownBy(() -> service.update(ACCOUNT_KEY, CONTROL_URL, admin, expired))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    @Test
    @DisplayName("★AC②_남의_유효창으로는_저장하지_못한다")
    void saveWithOthersWindowIsForbidden() {
        String othersWindow = tokenService.issue("9999", Instant.now()).token();

        assertThatThrownBy(() -> service.update(ACCOUNT_KEY, CONTROL_URL, admin, othersWindow))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    // ───────────────── 수용기준 ③ 주소 값 위반 400 ─────────────────

    @Test
    @DisplayName("★AC③_유효창이_있어도_http·https가_아닌_스킴은_400이다")
    void schemeViolationIsBadRequest() {
        assertThatThrownBy(() -> service.update(ACCOUNT_KEY, "ftp://10.177.22.90:8080", admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    @Test
    @DisplayName("★AC③_자격증명을_담은_표기는_400이다")
    void userInfoIsBadRequest() {
        assertThatThrownBy(() -> service.update(ACCOUNT_KEY, "http://admin:s3cr3t@control.example.net",
                admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    /** 「비어 있음」은 행이 없는 상태이지 빈 문자열 저장이 아니다 — 빈 값은 다른 연동 주소와 똑같이 400. */
    @Test
    @DisplayName("★빈_값_저장은_400이다 — 「행이_없음」과_「빈_값_저장」은_다르다")
    void blankValueIsBadRequest() {
        assertThatThrownBy(() -> service.update(ACCOUNT_KEY, "   ", admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    /** 대역 차단은 이 축에도 없다 — 관제는 내부망에 있다(ADR-046 「되돌리기 금지」). */
    @Test
    @DisplayName("★내부망_대역_주소가_저장된다 — 대역_차단은_이_축에도_없다")
    void privateNetworkAddressIsAccepted() {
        assertThatCode(() -> service.update(ACCOUNT_KEY, "http://192.168.10.5:8080", admin, validWindow()))
                .doesNotThrowAnyException();
    }

    // ───────────────── 관제 통지 수신처와 별개 ─────────────────

    @Test
    @DisplayName("★관제_계정_창구를_저장해도_관제_통지_수신처_행은_건드리지_않는다")
    void savingAccountDoesNotTouchNotifyKey() {
        service.update(ACCOUNT_KEY, CONTROL_URL, admin, validWindow());

        verify(repository, never()).findByConfigKey(ConfigKeys.CONTROL_NOTIFY_URL);
        ArgumentCaptor<LsSystemConfig> saved = ArgumentCaptor.forClass(LsSystemConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getConfigKey()).isNotEqualTo(ConfigKeys.CONTROL_NOTIFY_URL);
    }

    @Test
    @DisplayName("★관제_통지_수신처를_저장해도_관제_계정_창구_행은_건드리지_않는다")
    void savingNotifyDoesNotTouchAccountKey() {
        service.update(ConfigKeys.CONTROL_NOTIFY_URL, CONTROL_URL, admin, validWindow());

        verify(repository, never()).findByConfigKey(ACCOUNT_KEY);
        ArgumentCaptor<LsSystemConfig> saved = ArgumentCaptor.forClass(LsSystemConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getConfigKey()).isEqualTo(ConfigKeys.CONTROL_NOTIFY_URL);
    }
}
