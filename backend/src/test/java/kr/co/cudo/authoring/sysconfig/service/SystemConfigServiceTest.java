package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.auth.AdminSessionTestSupport;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.support.TestAiWaitBudgetPolicies;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointUrlValidator;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SystemConfigService 단위 테스트 — 이벤트 제외 대분류 코드(JSON 배열) 검증/조회 (Phase 1).
 *
 * <p>캐시/트랜잭션 AOP 없이 순수 검증 로직만 확인한다. 인가·캐시 무효화는
 * {@code SystemConfigControllerTest}(IT) 에서 검증한다.
 * @design AC-075
 */
class SystemConfigServiceTest {

    private static final String KEY = ConfigKeys.EVENT_EXCLUDED_CLASS_CODES;

    /** 관리자 세션 서명 키 — 이 테스트는 연동 주소 키를 다루지 않으므로 값 자체는 의미가 없다. */
    private static final javax.crypto.SecretKey TEST_JWT_KEY =
            Keys.hmacShaKeyFor("system-config-test-signing-key-0123456789abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private LsSystemConfigRepository repository;
    private SystemConfigService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        repository = mock(LsSystemConfigRepository.class);
        service = new SystemConfigService(repository, new ObjectMapper(),
                new AdminSessionGate(AdminSessionTestSupport.tokenService(() -> TEST_JWT_KEY, 10)),
                new IntegrationEndpointUrlValidator(),
                new DeidentifyEndpointTrustGuard(new org.springframework.mock.env.MockEnvironment()),
                TestAiWaitBudgetPolicies.production());
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private void seedExcludedConfig(String value) {
        LsSystemConfig cfg = LsSystemConfig.create(KEY, value, "JSON", "제외 대분류", "SYSTEM");
        when(repository.findByConfigKey(KEY)).thenReturn(Optional.of(cfg));
    }

    @Test
    @DisplayName("EVENT_EXCLUDED_CLASS_CODES_JSON배열_정상값_저장")
    void updateAcceptsValidJsonArray() {
        // given
        seedExcludedConfig("[\"08\"]");

        // when
        var response = service.update(KEY, "[\"08\",\"09\"]", reviewer);

        // then
        assertThat(response.configVl()).isEqualTo("[\"08\",\"09\"]");
    }

    @Test
    @DisplayName("EVENT_EXCLUDED_CLASS_CODES_빈배열_저장_허용")
    void updateAcceptsEmptyJsonArray() {
        // given
        seedExcludedConfig("[\"08\"]");

        // when
        var response = service.update(KEY, "[]", reviewer);

        // then
        assertThat(response.configVl()).isEqualTo("[]");
    }

    @Test
    @DisplayName("JSON_배열_형식_아니면_INVALID_INPUT")
    void updateRejectsNonArrayJson() {
        // given
        seedExcludedConfig("[\"08\"]");

        // when / then — 객체·비 JSON 문자열 모두 거부
        assertThatThrownBy(() -> service.update(KEY, "{\"a\":\"08\"}", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.update(KEY, "08,09", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("제외코드가_2자리_숫자_아니면_INVALID_INPUT")
    void updateRejectsMalformedClassCode() {
        // given
        seedExcludedConfig("[\"08\"]");

        // when / then — 1자리/3자리/문자/null 원소 모두 거부
        for (String bad : new String[]{"[\"8\"]", "[\"008\"]", "[\"ab\"]", "[\"08\",\"x9\"]", "[null]", "[8]"}) {
            assertThatThrownBy(() -> service.update(KEY, bad, reviewer))
                    .as("bad value=%s", bad)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("제외코드_20개_초과시_INVALID_INPUT")
    void updateRejectsTooManyClassCodes() {
        // given — 2자리 숫자 21개 (형식은 유효하나 개수 상한 초과, CWE-770)
        seedExcludedConfig("[\"08\"]");
        String tooMany = IntStream.rangeClosed(10, 30)
                .mapToObj(i -> "\"" + i + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        // when / then
        assertThatThrownBy(() -> service.update(KEY, tooMany, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("제외코드_정확히20개면_저장_성공")
    void updateAcceptsExactlyMaxClassCodes() {
        // given — 2자리 숫자 20개 (상한 경계값, `size() > MAX` 이므로 통과해야 한다)
        seedExcludedConfig("[\"08\"]");
        String exactlyMax = IntStream.rangeClosed(10, 29)
                .mapToObj(i -> "\"" + i + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        // when
        var response = service.update(KEY, exactlyMax, reviewer);

        // then — 경계값은 거부되지 않고 그대로 저장된다
        assertThat(response.configVl()).isEqualTo(exactlyMax);
    }

    @Test
    @DisplayName("getStringSet_JSON배열을_문자열집합으로_파싱한다")
    void getStringSetParsesJsonArray() {
        // given
        seedExcludedConfig("[\"08\",\"09\"]");

        // when
        Set<String> codes = service.getStringSet(KEY);

        // then
        assertThat(codes).containsExactlyInAnyOrder("08", "09");
    }

    @Test
    @DisplayName("getStringSet_CONFIG_TYPE이_JSON이_아니면_INVALID_INPUT")
    void getStringSetRejectsNonJsonType() {
        // given
        LsSystemConfig cfg = LsSystemConfig.create(KEY, "08", "STRING", "설명", "SYSTEM");
        when(repository.findByConfigKey(KEY)).thenReturn(Optional.of(cfg));

        // when / then
        assertThatThrownBy(() -> service.getStringSet(KEY))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("getStringSet_설정키가_없으면_NOT_FOUND")
    void getStringSetMissingKeyThrowsNotFound() {
        // given
        when(repository.findByConfigKey(KEY)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getStringSet(KEY))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("REVIEWER가_아니면_제외코드_설정_변경이_FORBIDDEN")
    void updateRejectsNonReviewer() {
        // given
        TokenClaims worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));

        // when / then
        assertThatThrownBy(() -> service.update(KEY, "[\"08\"]", worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ─────────────────── R9 비식별 옵션 3키 검증 ───────────────────

    /** 주어진 키를 해당 타입의 설정 행으로 심는다. */
    private void seedConfig(String key, String type, String value) {
        LsSystemConfig cfg = LsSystemConfig.create(key, value, type, "비식별 옵션", "SYSTEM");
        when(repository.findByConfigKey(key)).thenReturn(Optional.of(cfg));
    }

    private void assertRejected(String key, String value) {
        assertThatThrownBy(() -> service.update(key, value, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("R9_마스킹방식_0_2_3_은_통과한다")
    void maskingTypeAcceptsAllowedCodes() {
        // given
        seedConfig(ConfigKeys.KPST_DEID_MASKING_TYPE, "NUMBER", "0");

        // when / then — 0 색상 · 2 모자이크 · 3 블러
        for (String v : new String[]{"0", "2", "3"}) {
            assertThat(service.update(ConfigKeys.KPST_DEID_MASKING_TYPE, v, reviewer).configVl())
                    .isEqualTo(v);
        }
    }

    @Test
    @DisplayName("R9_마스킹방식_1은_거부된다_벤더_미할당_연속범위가_아니다")
    void maskingTypeRejectsUnassignedCode() {
        // given — [0,3] 연속 범위였다면 통과했을 값. 허용값 집합이라 거부돼야 한다.
        seedConfig(ConfigKeys.KPST_DEID_MASKING_TYPE, "NUMBER", "0");

        // when / then
        assertRejected(ConfigKeys.KPST_DEID_MASKING_TYPE, "1");
    }

    @Test
    @DisplayName("R9_마스킹방식_음수_4_비숫자는_거부된다")
    void maskingTypeRejectsOutOfSetValues() {
        // given
        seedConfig(ConfigKeys.KPST_DEID_MASKING_TYPE, "NUMBER", "0");

        // when / then
        assertRejected(ConfigKeys.KPST_DEID_MASKING_TYPE, "-1");
        assertRejected(ConfigKeys.KPST_DEID_MASKING_TYPE, "4");
        assertRejected(ConfigKeys.KPST_DEID_MASKING_TYPE, "블러");
    }

    @Test
    @DisplayName("R9_마스킹범위_0_5와_2_0은_통과하고_0_4와_2_1은_거부된다_경계")
    void maskingRangeBoundaries() {
        // given
        seedConfig(ConfigKeys.KPST_DEID_MASKING_RANGE, "DECIMAL", "1.0");

        // when / then — 경계 포함
        assertThat(service.update(ConfigKeys.KPST_DEID_MASKING_RANGE, "0.5", reviewer).configVl())
                .isEqualTo("0.5");
        assertThat(service.update(ConfigKeys.KPST_DEID_MASKING_RANGE, "2.0", reviewer).configVl())
                .isEqualTo("2.0");
        assertRejected(ConfigKeys.KPST_DEID_MASKING_RANGE, "0.4");
        assertRejected(ConfigKeys.KPST_DEID_MASKING_RANGE, "2.1");
    }

    @Test
    @DisplayName("R9_프레임저장여부_0_1은_통과하고_2는_거부된다")
    void dbSaveAcceptsOnlyZeroOne() {
        // given
        seedConfig(ConfigKeys.KPST_DEID_DB_SAVE, "NUMBER", "0");

        // when / then
        assertThat(service.update(ConfigKeys.KPST_DEID_DB_SAVE, "1", reviewer).configVl()).isEqualTo("1");
        assertThat(service.update(ConfigKeys.KPST_DEID_DB_SAVE, "0", reviewer).configVl()).isEqualTo("0");
        assertRejected(ConfigKeys.KPST_DEID_DB_SAVE, "2");
        assertRejected(ConfigKeys.KPST_DEID_DB_SAVE, "-1");
    }

    @Test
    @DisplayName("R9_거부_메시지에_사용자_입력_원문이_들어가지_않는다_CWE117")
    void rejectionMessageDoesNotEchoInput() {
        // given — 개행이 섞인 입력이 메시지·로그로 흘러가면 로그 인젝션이 된다.
        seedConfig(ConfigKeys.KPST_DEID_MASKING_TYPE, "NUMBER", "0");
        String malicious = "9\n[ERROR] injected";

        // when / then
        assertThatThrownBy(() -> service.update(ConfigKeys.KPST_DEID_MASKING_TYPE, malicious, reviewer))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining("injected");
    }
}
