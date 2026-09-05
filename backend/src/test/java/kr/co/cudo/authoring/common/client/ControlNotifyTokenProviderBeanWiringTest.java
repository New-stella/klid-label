package kr.co.cudo.authoring.common.client;

import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회귀 가드 — {@link ControlNotifyTokenProvider} 가 <b>Spring 자동주입</b>으로 실제 생성된다.
 *
 * <h3>왜 필요한가 (2026-09-05 전체 회귀 중단 사고)</h3>
 * 발급기에 생성자가 2개(ObjectProvider 주입용 · 테스트용)인데 어느 것에도 {@code @Autowired} 가 없으면,
 * Spring 이 생성자를 고르지 못하고 no-arg 생성자를 찾다 {@code BeanCreationException} 으로
 * <b>ApplicationContext 가 통째로 실패</b>한다(당시 2,876건 중단). 국소 슬라이스 테스트는 두 번째
 * 생성자를 {@code new} 로 직접 불러 통과했을 뿐 자동주입 경로를 검증하지 못했다.
 *
 * <p>이 시험은 <b>컨텍스트가 실제로 뜨는지</b>({@link ApplicationContextRunner})로 그 회귀를 막는다 —
 * {@code @Autowired} 가 빠지면 {@code hasNotFailed()} 가 깨진다.
 */
class ControlNotifyTokenProviderBeanWiringTest {

    private static final String SECRET_91B =
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890";
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET_91B.getBytes(StandardCharsets.UTF_8));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // @Value("${...:default}") 기본값 해석용.
            .withBean(PropertySourcesPlaceholderConfigurer.class)
            // ObjectProvider<JwtKeyResolver> 가 이 빈으로 해석된다(실환경 SecretKeyResolver 대역).
            .withBean(JwtKeyResolver.class, () -> (JwtKeyResolver) () -> key)
            // ★ 클래스만 등록 = Spring 이 생성자를 스스로 골라 인스턴스화 — 사고가 났던 바로 그 경로.
            .withBean(ControlNotifyTokenProvider.class);

    @Test
    @DisplayName("Autowired_생성자로_스프링이_빈을_생성한다 — 컨텍스트_로딩_성공")
    void beanIsWiredViaSpring() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ControlNotifyTokenProvider provider = ctx.getBean(ControlNotifyTokenProvider.class);
            assertThat(provider.canIssue()).isTrue();
            assertThat(provider.issue()).isNotNull();
        });
    }
}
