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
import java.time.Instant;
import java.util.List;
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
 * 외부 증강 벤더 주소를 저장 창구가 받는다 — <b>세 자리를 함께 채워야 성립한다</b>.
 * [@design ADR-046] [@design API-069] [@design SCREEN-042] [@design AC-1072]
 *
 * <h3>왜 세 자리인가 — 하나만 채우면 다른 자리에서 다른 코드로 거부된다</h3>
 * <ul>
 *   <li>{@link ConfigKeys#ALLOWED} — 없으면 저장 요청 자체가 <b>400</b>(허용되지 않은 키).</li>
 *   <li>{@link ConfigKeys#DECLARED_TYPE} — 없으면 시드 행이 없어 최초 저장이 <b>404</b>.</li>
 *   <li>{@link IntegrationEndpoint} — 없으면 <b>주소 형식 검증을 안 타고</b>, 더 중요하게는
 *       <b>관리자 유효창을 요구하지 않는다</b>. 이건 기능이 아니라 <b>인가</b>가 조용히 약해지는
 *       축이라, 다른 둘과 달리 «안 되는 것»이 아니라 «되면 안 되는 것이 된다».</li>
 * </ul>
 *
 * <p>세 자리를 각각 무는 시험을 남긴다 — 하나를 빼는 변이를 심으면 각각 <b>다른 지점</b>에서 RED 가
 * 되어야 한다. 통과 여부만 보는 시험 하나로는 어느 자리가 빠졌는지 드러나지 않는다.
 */
class AugmentEndpointConfigKeyTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "augment-endpoint-key-test-signing-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private static final String AUGMENT_KEY = ConfigKeys.AUGMENT_EXTERNAL_BASE_URL;
    private static final String VENDOR_URL = "https://augment.example-vendor.net";

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
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(LsSystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private String validWindow() {
        return tokenService.issue(admin.sub(), Instant.now()).token();
    }

    // ───────────────── 자리 ① 허용 키 목록 ─────────────────

    @Test
    @DisplayName("★자리①_허용_키_목록에_증강_주소_키가_있다 — 없으면_저장_요청이_400으로_거부된다")
    void augmentKeyIsWhitelisted() {
        assertThat(ConfigKeys.ALLOWED).contains(AUGMENT_KEY);
    }

    @Test
    @DisplayName("★자리①_대조 — 목록_밖의_키는_유효창이_있어도_400이다")
    void unlistedKeyIsRejectedEvenWithWindow() {
        assertThatThrownBy(() -> service.update("authoring.augment.external.rogue-url",
                VENDOR_URL, admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    // ───────────────── 자리 ② 선언 타입 맵 ─────────────────

    @Test
    @DisplayName("★자리②_선언_타입_맵에_증강_주소_키가_STRING으로_있다 — 없으면_최초_저장이_404다")
    void augmentKeyHasDeclaredType() {
        assertThat(ConfigKeys.DECLARED_TYPE).containsEntry(AUGMENT_KEY, "STRING");
    }

    @Test
    @DisplayName("★자리②가_실제로_행을_만든다 — 시드하지_않는_설계라_최초_저장이_행을_새로_만든다")
    void firstSaveCreatesRowWithDeclaredType() {
        service.update(AUGMENT_KEY, VENDOR_URL, admin, validWindow());

        ArgumentCaptor<LsSystemConfig> saved = ArgumentCaptor.forClass(LsSystemConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getConfigKey()).isEqualTo(AUGMENT_KEY);
        assertThat(saved.getValue().getConfigTypeCd()).isEqualTo("STRING");
    }

    @Test
    @DisplayName("★자리②_대조 — 선언_타입이_없는_키는_행이_없으면_여전히_404다")
    void keyWithoutDeclaredTypeStillNotFound() {
        assertThat(ConfigKeys.DECLARED_TYPE).doesNotContainKey(ConfigKeys.BATCH_CONCURRENCY);

        assertThatThrownBy(() -> service.update(ConfigKeys.BATCH_CONCURRENCY, "3", admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ───────────────── 자리 ③ 연동 대상 열거 (인가 축) ─────────────────

    @Test
    @DisplayName("★자리③_연동_대상_열거에_증강이_있다 — 없으면_유효창_없이_바뀐다(인가가_약해진다)")
    void augmentIsRegisteredAsIntegrationEndpoint() {
        assertThat(IntegrationEndpoint.isEndpointKey(AUGMENT_KEY)).isTrue();
        assertThat(IntegrationEndpoint.CONFIG_KEYS).contains(AUGMENT_KEY);
        assertThat(IntegrationEndpoint.byConfigKey(AUGMENT_KEY))
                .contains(IntegrationEndpoint.AUGMENT);
    }

    @Test
    @DisplayName("★자리③_인가 — 유효창_없이_증강_주소를_저장하면_403이고_행이_생기지_않는다")
    void augmentSaveRequiresAdminWindow() {
        assertThatThrownBy(() -> service.update(AUGMENT_KEY, VENDOR_URL, admin, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    @Test
    @DisplayName("★자리③_인가 — 남의_유효창으로는_증강_주소를_저장하지_못한다")
    void augmentSaveRejectsOtherUsersWindow() {
        String othersWindow = tokenService.issue("9999", Instant.now()).token();

        assertThatThrownBy(() -> service.update(AUGMENT_KEY, VENDOR_URL, admin, othersWindow))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    @Test
    @DisplayName("★자리③_형식_검증 — 유효창이_있어도_스킴_위반은_400이다")
    void augmentSaveStillValidatesScheme() {
        assertThatThrownBy(() -> service.update(AUGMENT_KEY, "ftp://augment.example-vendor.net",
                admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    /**
     * ★ 대역 판정 축은 다른 연동과 <b>같다</b> — 사설 대역도 저장된다. 되살리면 배포 기본값 자체가
     * 내부망 주소라 정당한 연동이 막힌다(확정 정책의 「되돌리기 금지」).
     */
    @Test
    @DisplayName("★증강_주소도_내부망_대역이_저장된다 — 대역_차단은_이_축에도_없다")
    void augmentAcceptsPrivateNetworkAddress() {
        assertThatCode(() -> service.update(AUGMENT_KEY, "http://10.0.0.9:9400", admin, validWindow()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★증강_주소에_자격증명을_담은_표기는_400이다 — 감사_로그에_평문이_남는_것을_막는다")
    void augmentRejectsUserInfoInUrl() {
        assertThatThrownBy(() -> service.update(AUGMENT_KEY, "http://admin:s3cr3t@vendor.example.net",
                admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    // ───────────────── 셋이 모두 있어야 저장이 성립한다 ─────────────────

    @Test
    @DisplayName("★세_자리가_모두_채워져야_저장이_성공한다 — 유효창+형식+행생성이_한_번에_성립한다")
    void savesOnlyWhenAllThreeSlotsAreFilled() {
        assertThat(ConfigKeys.ALLOWED).contains(AUGMENT_KEY);                  // ①
        assertThat(ConfigKeys.DECLARED_TYPE).containsKey(AUGMENT_KEY);         // ②
        assertThat(IntegrationEndpoint.isEndpointKey(AUGMENT_KEY)).isTrue();   // ③

        assertThatCode(() -> service.update(AUGMENT_KEY, VENDOR_URL, admin, validWindow()))
                .doesNotThrowAnyException();
        verify(repository).save(any(LsSystemConfig.class));
    }

    // ───────────────── 비어 있는 것이 정상 상태다 ─────────────────

    /**
     * ★ 「비어 있음이 정상」은 <b>행이 없는 상태</b>를 말한다 — 목록에 나타나지 않고, 그 부재가 오류로
     * 취급되지도 않으며, 다른 키 저장을 막지도 않는다. 미리 채우면 연동된 것으로 판정돼 아무도 받지
     * 않는 주소로 위탁이 나가고 그 실패가 벤더 장애처럼 보인다.
     */
    @Test
    @DisplayName("★증강_행이_없는_것이_정상이다 — 목록에_나타나지_않고_그_부재가_오류가_아니다")
    void absentAugmentRowIsTheNormalState() {
        LsSystemConfig other = LsSystemConfig.create(
                ConfigKeys.BATCH_INTERVAL_SEC, "60", "NUMBER", null, "seed");
        when(repository.findAll()).thenReturn(List.of(other));

        assertThat(service.listAll())
                .extracting("configKey")
                .doesNotContain(AUGMENT_KEY);
    }

    @Test
    @DisplayName("★증강을_저장하지_않아도_다른_연동_주소는_저장된다 — 빈_증강_칸이_다른_저장을_막지_않는다")
    void emptyAugmentDoesNotBlockOtherEndpointSaves() {
        assertThatCode(() -> service.update(ConfigKeys.CONTROL_NOTIFY_URL,
                "https://control.example-vendor.net", admin, validWindow()))
                .doesNotThrowAnyException();
    }

    /**
     * ⚠ <b>「행이 없음」과 「빈 값 저장」은 다른 것이다.</b> 빈 문자열 PUT 은 다른 연동 주소와 똑같이
     * 400 이다(확정 사양의 「저장 값에 남는 검증 — 빈 값 거부」). 화면은 빈 칸을 <b>전송하지 않는</b>
     * 방식으로 「미연동」을 표현하며, 빈 값을 저장해 행을 만들지 않는다.
     */
    @Test
    @DisplayName("★빈_값_저장은_여전히_400이다 — 「행이_없음」과_「빈_값_저장」은_다른_것이다")
    void blankValueIsStillRejected() {
        assertThatThrownBy(() -> service.update(AUGMENT_KEY, "   ", admin, validWindow()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
        verify(repository, never()).save(any(LsSystemConfig.class));
    }

    // ───────────────── 열거는 화면 칸 목록과 같은 집합이 아니다 ─────────────────

    /**
     * ★ 화면에서 빠진 두 키(AI 추론 · 외부 시계열 분석 벤더)도 <b>이 열거에는 남는다</b> —
     * 배포 설정값이 장비 원장의 씨앗으로 계속 저장될 수 있어, 빼면 그 경로가 형식 검증도 유효창도
     * 없이 저장된다. <b>두 집합을 같게 만들려는 다음 사람을 막는 시험이다.</b>
     */
    @Test
    @DisplayName("★화면에서_빠진_추론·시계열_키도_판정_대상에는_남는다 — 화면_칸과_같은_집합이_아니다")
    void endpointEnumIsNotTheSameSetAsScreenFields() {
        assertThat(IntegrationEndpoint.isEndpointKey(ConfigKeys.INTEGRATION_AI_SERVER_BASE_URL))
                .as("추론 키를 열거에서 빼면 씨앗 저장 경로가 유효창 없이 열린다")
                .isTrue();
        assertThat(IntegrationEndpoint.isEndpointKey(ConfigKeys.VLM_CLIENT_URL))
                .as("시계열 키를 열거에서 빼면 씨앗 저장 경로가 유효창 없이 열린다")
                .isTrue();

        assertThatThrownBy(() -> service.update(ConfigKeys.VLM_CLIENT_URL,
                "https://vlm.example-vendor.net", admin, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
