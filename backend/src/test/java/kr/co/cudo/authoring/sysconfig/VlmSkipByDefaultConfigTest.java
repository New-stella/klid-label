package kr.co.cudo.authoring.sysconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointUrlValidator;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시계열 위탁 <b>전체 건너뛰기</b> 설정 — 키 등록과 «사유 없이는 켤 수 없다» 규칙. [@design ADR-050]
 *
 * <h3>왜 사유가 필수인가</h3>
 * <p>사람이 누르는 단건 건너뛰기가 사유를 필수로 두는 것과 같은 축이다. 건너뛴 이유가 남지 않으면
 * 그 영상의 시계열이 왜 비어 있는지 나중에 되짚을 수 없다. 저장 API 에 일괄 저장이 없어 키별로
 * 개별 저장되므로, <b>스위치를 켜는 저장 시점에 저장된 사유 값을 읽어</b> 판정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmSkipByDefaultConfigTest {

    private static final String SWITCH_KEY = ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT;
    private static final String REASON_KEY = ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON;

    @Mock
    private LsSystemConfigRepository repository;

    private SystemConfigService service;

    private final TokenClaims reviewer =
            new TokenClaims("reviewer-1", Role.REVIEWER, Channel.INTERNAL, null);

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(repository, new ObjectMapper(),
                new AdminSessionGate(mock(AdminSessionTokenService.class)),
                mock(IntegrationEndpointUrlValidator.class),
                mock(DeidentifyEndpointTrustGuard.class),
                new AiWaitBudgetPolicy(RetryRegistry.of(RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofSeconds(1), 2.0))
                        .build())));
        when(repository.save(any(LsSystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByConfigKey(any())).thenReturn(Optional.empty());
    }

    private LsSystemConfig row(String key, String value, String type) {
        LsSystemConfig cfg = LsSystemConfig.create(key, value, type, null, "SYSTEM");
        when(repository.findByConfigKey(key)).thenReturn(Optional.of(cfg));
        return cfg;
    }

    /* ================= 키 등록 ================= */

    @Test
    @DisplayName("두_키가_화이트리스트에_등록돼_있다")
    void keysAreAllowed() {
        assertThat(ConfigKeys.ALLOWED).contains(SWITCH_KEY, REASON_KEY);
    }

    @Test
    @DisplayName("두_키가_DECLARED_TYPE_에_등록돼_있다_시드_없이_최초_저장이_가능해야_한다")
    void keysHaveDeclaredType() {
        // 시드 행을 만들지 않는 설계이므로, 등록이 빠지면 «한 번도 저장할 수 없는» 키가 된다.
        assertThat(ConfigKeys.DECLARED_TYPE).containsKey(SWITCH_KEY);
        assertThat(ConfigKeys.DECLARED_TYPE).containsKey(REASON_KEY);
    }

    @Test
    @DisplayName("★사유_키_이름은_스위치_키의_접두가_아니다_스칼라와_접두_충돌_회피")
    void reasonKeyIsNotAPrefixOfTheSwitchKey() {
        // 점 표기(`.reason`)를 쓰면 스칼라 키가 동시에 접두가 되어 설정 트리에서 충돌한다.
        assertThat(REASON_KEY).isEqualTo(SWITCH_KEY + "-reason");
        assertThat(REASON_KEY).doesNotStartWith(SWITCH_KEY + ".");
    }

    /* ================= 사유 없이는 켤 수 없다 ================= */

    @Test
    @DisplayName("★사유가_저장돼_있지_않으면_전체_건너뛰기를_켤_수_없다_400")
    void cannotEnableWithoutStoredReason() {
        row(SWITCH_KEY, "false", "BOOLEAN");
        // 사유 행 없음 (findByConfigKey → empty)

        assertThatThrownBy(() -> service.update(SWITCH_KEY, "true", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("★사유가_공백뿐이면_전체_건너뛰기를_켤_수_없다_400")
    void cannotEnableWithBlankReason() {
        row(SWITCH_KEY, "false", "BOOLEAN");
        row(REASON_KEY, "   ", "STRING");

        assertThatThrownBy(() -> service.update(SWITCH_KEY, "true", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("★거부되면_기존_값이_그대로_유지된다")
    void rejectionLeavesExistingValueUntouched() {
        LsSystemConfig cfg = row(SWITCH_KEY, "false", "BOOLEAN");

        assertThatThrownBy(() -> service.update(SWITCH_KEY, "true", reviewer))
                .isInstanceOf(CustomException.class);

        assertThat(cfg.getConfigVl()).isEqualTo("false");
    }

    @Test
    @DisplayName("사유가_저장돼_있으면_전체_건너뛰기가_켜진다")
    void enablesWhenReasonIsPresent() {
        LsSystemConfig cfg = row(SWITCH_KEY, "false", "BOOLEAN");
        row(REASON_KEY, "외부 시계열 벤더 미연동 구간", "STRING");

        service.update(SWITCH_KEY, "true", reviewer);

        assertThat(cfg.getConfigVl()).isEqualTo("true");
    }

    @Test
    @DisplayName("스위치를_끄는_저장은_사유가_없어도_통과한다")
    void disablingNeedsNoReason() {
        LsSystemConfig cfg = row(SWITCH_KEY, "true", "BOOLEAN");

        service.update(SWITCH_KEY, "false", reviewer);

        assertThat(cfg.getConfigVl()).isEqualTo("false");
    }

    @Test
    @DisplayName("사유_자체의_저장은_스위치_상태와_무관하게_통과한다")
    void reasonCanBeSavedIndependently() {
        LsSystemConfig cfg = row(REASON_KEY, "옛 사유", "STRING");

        service.update(REASON_KEY, "새 사유", reviewer);

        assertThat(cfg.getConfigVl()).isEqualTo("새 사유");
    }

    /* ========== 켜져 있는 동안에는 사유를 비울 수 없다 (반대 방향 쓰기) ========== */

    /**
     * ★★한쪽만 막으면 이 설계의 존재 이유가 절반만 달성된다.
     *
     * <p>켜는 검사({@code validateVlmSkipByDefault})는 <b>켜는 쓰기</b>만 본다. 그래서 켠 뒤에 사유를
     * 빈 값으로 저장하는 요청은 아무 판정도 만나지 않았고, 그러면 이후 자동 표식이 전부 「사유 미입력」
     * 로 남아 <b>건너뛴 이유를 나중에 되짚을 수 없다</b>. 화면이 막더라도 API 를 직접 부르면 열리므로
     * 판정은 서버가 가져야 한다.
     */
    @Test
    @DisplayName("★★스위치가_켜져_있으면_사유를_빈_값으로_저장할_수_없다_400")
    void cannotBlankReasonWhileSwitchIsOn() {
        row(SWITCH_KEY, "true", "BOOLEAN");
        LsSystemConfig reason = row(REASON_KEY, "벤더 미연동", "STRING");

        assertThatThrownBy(() -> service.update(REASON_KEY, "", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        // 거부되면 기존 사유가 그대로 남는다.
        assertThat(reason.getConfigVl()).isEqualTo("벤더 미연동");
    }

    @Test
    @DisplayName("★공백뿐인_사유도_켜져_있는_동안에는_거부된다")
    void whitespaceOnlyReasonIsRejectedWhileSwitchIsOn() {
        row(SWITCH_KEY, "true", "BOOLEAN");
        row(REASON_KEY, "벤더 미연동", "STRING");

        assertThatThrownBy(() -> service.update(REASON_KEY, "   ", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /**
     * ★<b>되돌리는 길은 막지 않는다</b> — 끄고 나면 사유는 언제든 비울 수 있다. 그래서 화면은
     * 끌 때는 스위치를 먼저, 켤 때는 사유를 먼저 저장한다. 이 가드가 없으면 두 검사 사이에 갇혀
     * 사유를 영원히 비울 수 없는 상태가 만들어진다.
     */
    @Test
    @DisplayName("★★스위치를_끄고_나면_사유를_비울_수_있다_되돌리는_길")
    void reasonCanBeClearedOnceSwitchIsOff() {
        row(SWITCH_KEY, "false", "BOOLEAN");
        LsSystemConfig reason = row(REASON_KEY, "벤더 미연동", "STRING");

        service.update(REASON_KEY, "", reviewer);

        assertThat(reason.getConfigVl()).isEmpty();
    }

    /**
     * 스위치 행이 아예 없으면 <b>꺼짐</b>이다(행이 없는 것이 정상 상태). 그 상태에서 사유를 비우는 것은
     * 막지 않는다 — 여기서 fail-closed 로 막으면 아직 한 번도 켠 적 없는 프로젝트가 사유 칸을
     * 비울 수 없게 된다.
     */
    @Test
    @DisplayName("스위치_행이_없으면_꺼짐이므로_사유를_비울_수_있다")
    void reasonCanBeClearedWhenSwitchRowAbsent() {
        LsSystemConfig reason = row(REASON_KEY, "옛 사유", "STRING");

        service.update(REASON_KEY, "", reviewer);

        assertThat(reason.getConfigVl()).isEmpty();
    }

    @Test
    @DisplayName("켜져_있어도_비어_있지_않은_사유로_바꾸는_것은_통과한다")
    void reasonCanBeChangedWhileSwitchIsOn() {
        row(SWITCH_KEY, "true", "BOOLEAN");
        LsSystemConfig reason = row(REASON_KEY, "옛 사유", "STRING");

        service.update(REASON_KEY, "새 사유", reviewer);

        assertThat(reason.getConfigVl()).isEqualTo("새 사유");
    }

    @Test
    @DisplayName("스위치_값은_true_false_외에는_거부된다")
    void switchAcceptsOnlyBooleanLiterals() {
        row(SWITCH_KEY, "false", "BOOLEAN");
        row(REASON_KEY, "사유", "STRING");

        assertThatThrownBy(() -> service.update(SWITCH_KEY, "예", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
