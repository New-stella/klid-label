package kr.co.cudo.authoring.sysconfig.service;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 포털 보존기간 설정 3키의 <b>등록</b>과 <b>하한</b> 검증. @design DFEAT-055
 *
 * <h3>이 파일이 막는 것</h3>
 * <p>이 3키는 <b>파괴적 삭제 배치</b>의 기준선(now - N일)이다. {@code SystemConfigService} 는 범위 맵에
 * 등록되지 않은 NUMBER 키를 <b>정수 파싱만 통과하면 무제한 허용</b>하므로
 * ({@code validateNumberRange} — 등록 누락 = 무검증), 화이트리스트에만 넣고 범위를 빠뜨리면
 * <b>0 이나 음수가 그대로 저장</b>된다. 0 은 "오늘 것까지 지운다"(방금 저장한 라벨이 다음 스윕에 소멸),
 * 음수는 커트라인이 미래가 되어 전량이 대상이다 — 어느 쪽도 되돌릴 수단이 없다.
 *
 * <p>그래서 "등록됐는가"(맵 단언)와 "실제로 거부되는가"(저장 경로 단언)를 <b>둘 다</b> 확인한다.
 * 맵만 보면 서비스가 그 맵을 읽지 않게 바뀌어도 통과하고, 저장 경로만 보면 세 키 중 하나가
 * 빠져도 통과한다.
 */
class PortalRetentionConfigKeyTest {

    /** 검증 대상 3키 — 하나라도 빠지면 그 축만 조용히 무검증이 된다. */
    private static final List<String> RETENTION_KEYS = List.of(
            ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS,
            ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS,
            ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS);

    /** 관리자 세션 서명 키 — 이 테스트는 연동 주소 키를 다루지 않으므로 값 자체는 의미가 없다. */
    private static final javax.crypto.SecretKey TEST_JWT_KEY = Keys.hmacShaKeyFor(
            "portal-retention-test-signing-key-0123456789ab".getBytes(StandardCharsets.UTF_8));

    private LsSystemConfigRepository repository;
    private SystemConfigService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        repository = mock(LsSystemConfigRepository.class);
        service = new SystemConfigService(repository, new ObjectMapper(),
                new AdminSessionGate(new AdminSessionTokenService(() -> TEST_JWT_KEY, 10)),
                new IntegrationEndpointUrlValidator(),
                new DeidentifyEndpointTrustGuard(new org.springframework.mock.env.MockEnvironment()),
                TestAiWaitBudgetPolicies.production());
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
        // V11 시드가 깔린 상태를 재현 — 3키 모두 NUMBER 행으로 존재한다.
        for (String key : RETENTION_KEYS) {
            when(repository.findByConfigKey(key)).thenReturn(Optional.of(
                    LsSystemConfig.create(key, "7", "NUMBER", "보존일수", "SYSTEM")));
        }
    }

    // ------------------------------------------------------------------ 등록

    @Test
    @DisplayName("보존기간_3키가_화이트리스트와_범위맵에_모두_등록돼_있다")
    void keysAreRegisteredInBothMaps() {
        assertThat(ConfigKeys.ALLOWED)
                .as("화이트리스트에 없으면 저장 자체가 400 이라 설정 화면에서 편집할 수 없다")
                .containsAll(RETENTION_KEYS);
        assertThat(ConfigKeys.NUMBER_RANGE.keySet())
                .as("범위 맵에 없으면 정수이기만 하면 무엇이든 통과한다(등록 누락 = 무검증)")
                .containsAll(RETENTION_KEYS);
    }

    @Test
    @DisplayName("보존기간_3키의_하한은_1이다_0과_음수는_즉시삭제라_값_자체를_막는다")
    void lowerBoundIsOne() {
        for (String key : RETENTION_KEYS) {
            int[] range = ConfigKeys.NUMBER_RANGE.get(key);
            assertThat(range).as("%s 범위가 등록돼 있어야 한다", key).isNotNull();
            assertThat(range[0]).as("%s 하한 — 0 이면 방금 저장한 데이터가 다음 스윕에 사라진다", key)
                    .isEqualTo(1);
            assertThat(range[1]).as("%s 상한은 하한보다 커야 한다", key).isGreaterThan(range[0]);
        }
    }

    // ------------------------------------------------------------------ 하한 위반 거부

    @ParameterizedTest(name = "보존일수 {0} 은 거부된다")
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("보존일수_0과_음수는_3키_모두에서_INVALID_INPUT")
    void zeroAndNegativeAreRejected(String value) {
        for (String key : RETENTION_KEYS) {
            assertThatThrownBy(() -> service.update(key, value, reviewer))
                    .as("%s = %s 가 저장되면 사용자 데이터가 즉시 삭제 대상이 된다", key, value)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("보존일수_경계값_1은_통과하고_상한초과는_거부된다")
    void boundaryValues() {
        for (String key : RETENTION_KEYS) {
            int max = ConfigKeys.NUMBER_RANGE.get(key)[1];

            assertThatCode(() -> service.update(key, "1", reviewer))
                    .as("%s — 하한 1 은 유효한 운영값이다(하루만 보관)", key)
                    .doesNotThrowAnyException();
            assertThatCode(() -> service.update(key, String.valueOf(max), reviewer))
                    .as("%s — 상한 자체는 통과해야 한다", key)
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> service.update(key, String.valueOf(max + 1), reviewer))
                    .as("%s — 상한 초과는 거부한다(오타로 들어온 값이 커트라인 계산을 넘치게 한다)", key)
                    .isInstanceOf(CustomException.class);
        }
    }

    @Test
    @DisplayName("보존일수에_숫자가_아닌_값은_INVALID_INPUT")
    void nonNumericIsRejected() {
        assertThatThrownBy(() ->
                service.update(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS, "7일", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("거부_메시지에_사용자_입력_원문이_들어가지_않는다_CWE117")
    void rejectionMessageOmitsRawInput() {
        String hostile = "-1\n[INJECTED] admin";
        assertThatThrownBy(() ->
                service.update(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS, hostile, reviewer))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining("INJECTED");
    }
}
