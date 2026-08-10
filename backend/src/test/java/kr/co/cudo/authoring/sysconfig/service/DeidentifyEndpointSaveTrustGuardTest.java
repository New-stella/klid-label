package kr.co.cudo.authoring.sysconfig.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
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
 * ★ HIGH — <b>목/시뮬레이터 비식별 서버 가드가 화면 저장으로 우회되지 않는다</b>.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>{@link DeidentifyEndpointTrustGuard} 는 위조 비식별본(원본을 그대로 복사한 "비식별본")이
 * 학습데이터·외부 통지로 흘러가는 것을 prd 에서 fail-closed 로 막는다. 그런데 그 판정은
 * <b>기동 시 {@code @Value} 배포값 1회</b>였고, R11 로 주소가 <b>호출 시점</b>에 정해지게 되면서
 * 가드가 보는 값과 실제 값이 갈렸다 — <b>운영 화면에서 목 주소를 저장하면 게이트가 통째로 우회</b>되고
 * 저장 시 재평가도 WARN 도 없었다.
 *
 * <p>판정 축은 <b>알려진 목/시뮬레이터 호스트명</b>이지 IP 대역이 아니다(대역 차단은 폐지된 정책이며
 * 여기서 되살리지 않는다 — 아래 {@code 사설망_주소는_운영에서도_저장된다} 가 그것을 못박는다).
 *
 * <p><b>mutation 확인</b>: {@code SystemConfigService.doUpdate} 의
 * {@code deidentifyEndpointTrustGuard.verifyForSave(value)} 한 줄을 지우면
 * {@code 운영에서_목_주소_저장은_400} 이 실패한다.
 */
class DeidentifyEndpointSaveTrustGuardTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "deident-save-guard-test-signing-key-0123456789ab".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private static final String DEID_KEY = ConfigKeys.KPST_DEID_BASE_URL;

    private LsSystemConfigRepository repository;
    private AdminSessionTokenService tokenService;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        repository = mock(LsSystemConfigRepository.class);
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(LsSystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        tokenService = new AdminSessionTokenService(RESOLVER, 10);
        reviewer = new TokenClaims("7001", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
    }

    /** 프로파일만 다른 서비스 인스턴스 — 판정 강도가 프로파일로 갈리는지 보기 위해. */
    private SystemConfigService serviceOn(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new SystemConfigService(repository, new ObjectMapper(), tokenService,
                new IntegrationEndpointUrlValidator(),
                new DeidentifyEndpointTrustGuard(environment));
    }

    private String validToken() {
        return tokenService.issue(reviewer.sub(), Instant.now()).token();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "http://klid-mock-server:9400",
            "http://mock-server:9400",
            "http://kpst-mock-01:9989",     // 호스트명에 mock 토큰이 포함된 시뮬레이터
            "http://localhost:9201",
            "https://127.0.0.1:9201",
            "http://[::1]:9201"
    })
    @DisplayName("★운영에서_목_주소_저장은_400 — 기동_가드와_같은_강도다")
    void mockAddressRejectedOnProduction(String url) {
        SystemConfigService service = serviceOn("prd");

        assertThatThrownBy(() -> service.update(DEID_KEY, url, reviewer, validToken()))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("★거부_메시지에_입력_호스트를_싣지_않는다 (CWE-209/117)")
    void rejectionDoesNotEchoHost() {
        SystemConfigService service = serviceOn("prd");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.update(DEID_KEY, "http://klid-mock-server:9400", reviewer, validToken()));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain("klid-mock-server");
    }

    @Test
    @DisplayName("dev에서는_목_주소가_저장된다 — 목_연동이_dev의_정상_경로다")
    void mockAddressAcceptedOnDev() {
        SystemConfigService service = serviceOn("dev");

        assertThatCode(() -> service.update(DEID_KEY, "http://klid-mock-server:9400", reviewer, validToken()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("운영에서_벤더_실주소는_저장된다 — deny-known-mock_이지_allowlist_가_아니다")
    void vendorAddressAcceptedOnProduction() {
        SystemConfigService service = serviceOn("prd");

        assertThatCode(() -> service.update(DEID_KEY, "https://kpst.vendor.example:9989", reviewer, validToken()))
                .doesNotThrowAnyException();
    }

    /**
     * ★ 확정 정책 회귀 가드 — <b>대역 차단은 폐지됐다</b>(2026-08-10 사용자 확정). 이 가드의 축은
     * 호스트명이지 IP 대역이 아니므로, 사설망 주소는 운영에서도 그대로 저장돼야 한다.
     * 이 테스트가 실패한다면 대역 판정이 되살아난 것이다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"http://10.0.0.5:9989", "https://192.168.1.10", "http://172.16.0.1:9989"})
    @DisplayName("★사설망_주소는_운영에서도_저장된다 — 대역_판정을_되살리지_않는다")
    void privateNetworkAddressAcceptedOnProduction(String url) {
        SystemConfigService service = serviceOn("prd");

        assertThatCode(() -> service.update(DEID_KEY, url, reviewer, validToken()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★언더스코어_벤더_호스트는_운영에서도_저장된다 — 도커_서비스명을_막지_않는다")
    void underscoreVendorHostAcceptedOnProduction() {
        SystemConfigService service = serviceOn("prd");

        assertThatCode(() -> service.update(DEID_KEY, "http://kpst_deid:9201", reviewer, validToken()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★언더스코어라도_목_호스트면_운영에서_여전히_400 — 폴백이_강도를_낮추지_않는다")
    void underscoreMockHostStillRejectedOnProduction() {
        SystemConfigService service = serviceOn("prd");

        assertThatThrownBy(() -> service.update(DEID_KEY, "http://klid_mock_server:9400", reviewer, validToken()))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("비식별_외의_연동_주소는_이_가드를_타지_않는다 — 목_서버_연동이_정상인_대상들이다")
    void otherEndpointsAreNotTrustGated() {
        SystemConfigService service = serviceOn("prd");

        for (IntegrationEndpoint endpoint : IntegrationEndpoint.values()) {
            if (endpoint == IntegrationEndpoint.DEIDENTIFY) {
                continue;
            }
            assertThatCode(() -> service.update(endpoint.configKey(),
                    "http://klid-mock-server:9400", reviewer, validToken()))
                    .as("%s 는 비식별 신뢰 가드의 대상이 아니다", endpoint.name())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("★인증_검사가_신뢰_판정보다_먼저다 — 토큰_없이는_주소_피드백도_주지_않는다")
    void authorizationPrecedesTrustJudgement() {
        SystemConfigService service = serviceOn("prd");

        assertThatThrownBy(() -> service.update(DEID_KEY, "http://klid-mock-server:9400", reviewer, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
