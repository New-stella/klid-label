package kr.co.cudo.authoring.sysconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointUrlValidator;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import kr.co.cudo.authoring.sysconfig.service.AiWaitBudgetProvider;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 대기 예산 절대 상한 설정 — <b>저장 시점 거부</b>와 <b>읽기 시점 끌어올림</b> 두 겹.
 *
 * <h3>왜 두 겹인가</h3>
 * <p>저장 검증만 두면, 검증 규칙이 나중에 바뀌어 <b>이미 저장된 값</b>이 하한 아래로 남는 순간
 * 설정 하나 때문에 정상 동작이 실패로 바뀐다. 그래서 읽는 쪽에서도 하한으로 끌어올린다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiWaitBudgetCeilingConfigTest {

    private static final String KEY = ConfigKeys.AI_WAIT_BUDGET_CEILING_SEC;

    @Mock
    private LsSystemConfigRepository repository;

    private AiWaitBudgetPolicy policy;
    private SystemConfigService service;

    private final TokenClaims reviewer = new TokenClaims(
            "reviewer-1", Role.REVIEWER, kr.co.cudo.authoring.common.security.Channel.INTERNAL, null);

    @BeforeEach
    void setUp() {
        policy = policy(3, Duration.ofSeconds(1), 2.0);
        service = new SystemConfigService(repository, new ObjectMapper(),
                new AdminSessionGate(mock(AdminSessionTokenService.class)),
                mock(IntegrationEndpointUrlValidator.class),
                mock(DeidentifyEndpointTrustGuard.class),
                policy);
        when(repository.save(any(LsSystemConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static AiWaitBudgetPolicy policy(int maxAttempts, Duration wait, double multiplier) {
        return new AiWaitBudgetPolicy(RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(wait, multiplier))
                .build()));
    }

    /* ================= 저장 시점 ================= */

    @Test
    @DisplayName("파생_하한보다_작은_절대_상한은_저장이_거부된다")
    void rejectsCeilingBelowDerivedFloor() {
        when(repository.findByConfigKey(KEY)).thenReturn(Optional.empty());
        int belowFloor = policy.minimumCeilingSeconds() - 1;

        assertThatThrownBy(() -> service.update(KEY, String.valueOf(belowFloor), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("파생_하한과_같은_값은_저장된다_경계_포함")
    void acceptsCeilingExactlyAtFloor() {
        when(repository.findByConfigKey(KEY)).thenReturn(Optional.empty());
        int floor = policy.minimumCeilingSeconds();

        assertThat(service.update(KEY, String.valueOf(floor), reviewer).configVl())
                .isEqualTo(String.valueOf(floor));
    }

    @Test
    @DisplayName("재시도_예산을_늘리면_거부되는_값의_범위도_함께_넓어진다")
    void floorFollowsRetryBudget() {
        // 파생이 아니라 상수였다면 이 테스트가 죽는다 — 하한이 예산 변화를 따라가는지가 요점이다.
        AiWaitBudgetPolicy widened = policy(4, Duration.ofSeconds(1), 2.0);
        SystemConfigService widenedService = new SystemConfigService(repository, new ObjectMapper(),
                new AdminSessionGate(mock(AdminSessionTokenService.class)),
                mock(IntegrationEndpointUrlValidator.class),
                mock(DeidentifyEndpointTrustGuard.class),
                widened);
        when(repository.findByConfigKey(KEY)).thenReturn(Optional.empty());

        int okUnderNarrowBudget = policy.minimumCeilingSeconds();      // 252 — 좁은 예산에선 통과값
        assertThat(widened.minimumCeilingSeconds()).isEqualTo(257);    // 넓어진 하한

        assertThatThrownBy(() ->
                widenedService.update(KEY, String.valueOf(okUnderNarrowBudget), reviewer))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("정적_상한을_넘는_값은_기존_범위_검증에서_거부된다")
    void rejectsAboveStaticMax() {
        when(repository.findByConfigKey(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(KEY,
                String.valueOf(AiWaitBudgetPolicy.MAX_CEILING_SEC + 1), reviewer))
                .isInstanceOf(CustomException.class);
    }

    /* ================= 읽기 시점 ================= */

    @Test
    @DisplayName("설정이_없으면_앞단_제한시간과_같은_기본_절대_상한을_쓴다")
    void fallsBackToDefaultWhenUnset() {
        SystemConfigService reader = mock(SystemConfigService.class);
        when(reader.findString(KEY)).thenReturn(Optional.empty());

        assertThat(new AiWaitBudgetProvider(reader, policy).ceilingSeconds())
                .isEqualTo(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC);
    }

    @Test
    @DisplayName("이미_저장된_값이_하한_아래면_읽을_때_하한으로_끌어올린다")
    void clampsPersistedValueBelowFloorOnRead() {
        SystemConfigService reader = mock(SystemConfigService.class);
        when(reader.findString(KEY)).thenReturn(Optional.of("30"));

        assertThat(new AiWaitBudgetProvider(reader, policy).ceilingSeconds())
                .isEqualTo(policy.minimumCeilingSeconds());
    }

    @Test
    @DisplayName("숫자로_읽히지_않는_값은_기본값으로_떨어진다")
    void fallsBackWhenUnparsable() {
        SystemConfigService reader = mock(SystemConfigService.class);
        when(reader.findString(anyString())).thenReturn(Optional.of("삼백"));

        assertThat(new AiWaitBudgetProvider(reader, policy).ceilingSeconds())
                .isEqualTo(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC);
    }

    @Test
    @DisplayName("하한_이상으로_저장된_값은_그대로_쓴다")
    void usesPersistedValueWhenAboveFloor() {
        SystemConfigService reader = mock(SystemConfigService.class);
        when(reader.findString(KEY)).thenReturn(Optional.of("600"));

        assertThat(new AiWaitBudgetProvider(reader, policy).ceilingSeconds()).isEqualTo(600);
    }
}
