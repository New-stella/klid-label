package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.auth.AdminSessionTestSupport;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.support.TestAiWaitBudgetPolicies;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointUrlValidator;
import kr.co.cudo.authoring.sysconfig.entity.LsSystemConfig;
import kr.co.cudo.authoring.sysconfig.repository.LsSystemConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★ MED — <b>override 가 없는 정상 상태에서도 리졸버 캐시가 채워진다</b>.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>연동 주소 4종은 <b>행이 없는 것이 정상 상태</b>다(시드하지 않는다). 그런데 리졸버가 쓰던
 * {@code getString} 은 그때 {@code NOT_FOUND} 를 던지고 <b>Spring 캐시는 예외를 캐시하지 않는다</b> —
 * 즉 캐시 엔트리가 한 번도 만들어지지 않아 <b>외부 호출마다 DB 왕복 + 예외 생성</b>이 반복됐다.
 * "Caffeine TTL 60s 가 이미 막는다"는 주석의 근거가 정상 상태에서 성립하지 않았던 것이다.
 * 영향 경로에는 라벨링 캔버스의 온라인 오토라벨·SAM2 처럼 <b>사용자 클릭당 발생하는 대화형 핫패스</b>가 있다.
 *
 * <p>동시에 <b>즉시 반영(R11 의 핵심 요구)이 깨지지 않아야 한다</b> — 캐시 키를 늘렸으니 저장 시
 * 무효화가 그 키까지 덮는지 확인한다({@code allEntries=true} 라 자동으로 덮이지만, 그 사실을
 * 여기서 고정한다).
 *
 * <p>캐시 AOP 가 붙은 <b>프록시</b>가 있어야 의미가 있으므로 실제 스프링 컨텍스트를 띄운다
 * (트랜잭션은 {@code @EnableTransactionManagement} 가 없어 무동작 — 이 테스트의 관심사가 아니다).
 */
class SystemConfigOverrideCacheTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "override-cache-test-signing-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static final String ENDPOINT_KEY = ConfigKeys.INTEGRATION_AI_SERVER_BASE_URL;

    private AnnotationConfigApplicationContext context;
    private LsSystemConfigRepository repository;
    private SystemConfigService service;
    private IntegrationEndpointResolver resolver;
    private AdminSessionTokenService tokenService;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        repository = mock(LsSystemConfigRepository.class);
        when(repository.save(any(LsSystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        tokenService = AdminSessionTestSupport.tokenService(() -> KEY, 10);

        context = new AnnotationConfigApplicationContext();
        context.register(CacheConfig.class);
        context.registerBean(SystemConfigService.class, () -> new SystemConfigService(
                repository, new ObjectMapper(), new AdminSessionGate(tokenService),
                new IntegrationEndpointUrlValidator(),
                new DeidentifyEndpointTrustGuard(new MockEnvironment()),
                TestAiWaitBudgetPolicies.production()));
        context.refresh();

        service = context.getBean(SystemConfigService.class);
        resolver = new IntegrationEndpointResolver(providerOf(service));
        reviewer = new TokenClaims("5001", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private ObjectProvider<SystemConfigService> providerOf(SystemConfigService bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private void rowAbsent() {
        when(repository.findByConfigKey(ENDPOINT_KEY)).thenReturn(Optional.empty());
    }

    private void rowWith(String value) {
        when(repository.findByConfigKey(ENDPOINT_KEY)).thenReturn(
                Optional.of(LsSystemConfig.create(ENDPOINT_KEY, value, "STRING", null, "seed")));
    }

    @Test
    @DisplayName("★override_가_없어도_반복_호출은_DB를_한_번만_친다 — 부재도_캐시된다")
    void absentOverrideIsCached() {
        rowAbsent();

        for (int i = 0; i < 5; i++) {
            assertThat(resolver.resolve(IntegrationEndpoint.AI_SERVER, "http://boot-default:9300"))
                    .isEqualTo("http://boot-default:9300");
        }

        verify(repository, times(1)).findByConfigKey(ENDPOINT_KEY);
    }

    @Test
    @DisplayName("override_가_있어도_반복_호출은_DB를_한_번만_친다")
    void presentOverrideIsCached() {
        rowWith("http://configured:9300");

        for (int i = 0; i < 5; i++) {
            assertThat(resolver.resolve(IntegrationEndpoint.AI_SERVER, "http://boot-default:9300"))
                    .isEqualTo("http://configured:9300");
        }

        verify(repository, times(1)).findByConfigKey(ENDPOINT_KEY);
    }

    @Test
    @DisplayName("★저장하면_즉시_반영된다 — 캐시_키를_늘려도_무효화가_덮는다 (R11 핵심 요구)")
    void saveInvalidatesTheNewCacheKey() {
        // given — 부재 상태를 먼저 캐시에 채운다(이 캐시가 남아 있으면 저장이 반영되지 않는다)
        rowAbsent();
        assertThat(resolver.resolve(IntegrationEndpoint.AI_SERVER, "http://boot-default:9300"))
                .isEqualTo("http://boot-default:9300");

        // when — 운영자가 새 주소를 저장한다
        String token = tokenService.issue(reviewer.sub(), Instant.now()).token();
        service.update(ENDPOINT_KEY, "http://configured:9300", reviewer, token);
        rowWith("http://configured:9300");
        clearInvocations(repository);

        // then — 재기동 없이 다음 조회부터 새 주소가 나오고, 그 뒤로는 다시 캐시된다
        assertThat(resolver.resolve(IntegrationEndpoint.AI_SERVER, "http://boot-default:9300"))
                .isEqualTo("http://configured:9300");
        assertThat(resolver.resolve(IntegrationEndpoint.AI_SERVER, "http://boot-default:9300"))
                .isEqualTo("http://configured:9300");
        verify(repository, times(1)).findByConfigKey(ENDPOINT_KEY);
    }

    @Test
    @DisplayName("빈_문자열_값은_override_없음으로_본다 — 기존_동작_보존")
    void blankValueIsTreatedAsAbsent() {
        rowWith("   ");

        assertThat(resolver.resolve(IntegrationEndpoint.AI_SERVER, "http://boot-default:9300"))
                .isEqualTo("http://boot-default:9300");
        assertThat(resolver.hasOverride(IntegrationEndpoint.AI_SERVER)).isFalse();
    }
}
