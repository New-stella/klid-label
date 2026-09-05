package kr.co.cudo.authoring.observability.health;

import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.service.AiSrvrRegistry;
import kr.co.cudo.authoring.common.client.VlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * VlmHealthIndicator 빈 등록 조건 회귀 가드.
 *
 * <p>연동 주소가 없으면 핑할 대상 자체가 없으므로 <b>DOWN 이 아니라 부재</b>여야 한다. 미연동인데
 * 등록해서 DOWN 을 내면 집계 {@code /actuator/health} 가 상시 DOWN 이 되며, 그것이 관제 헬스
 * 인디케이터를 제거하게 만든 사고의 형태다.
 *
 * <p>★ 이 가드가 잡는 함정: {@code application.yml} 은 {@code vlm.client.url: ${VLM_SERVICE_URL:}}
 * 로 <b>키를 항상 정의</b>한다. 즉 미주입 상태의 실제 형상은 "키 없음"이 아니라 <b>"키가 있고 값이
 * 빈 문자열"</b> 이다. {@code @ConditionalOnProperty(name = "vlm.client.url")} 는 그 형상을
 * <b>매칭시켜</b>(값이 "false" 가 아니므로) 빈을 등록해 버린다. 조건을 그 애너테이션으로
 * "단순화"하면 아래 첫 케이스가 실패한다.
 */
class VlmHealthIndicatorRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(StubVlmClientConfig.class, VlmHealthIndicator.class);

    @Test
    @DisplayName("연동주소가_빈문자열이면_빈이_등록되지_않는다")
    void indicator_not_registered_when_url_is_blank() {
        // given — 배포 기본 형상: 키는 정의돼 있고 값만 비어 있다.
        runner.withPropertyValues("vlm.client.url=")
                .run(context -> assertThat(context).doesNotHaveBean(VlmHealthIndicator.class));
    }

    @Test
    @DisplayName("연동주소가_공백뿐이어도_빈이_등록되지_않는다")
    void indicator_not_registered_when_url_is_whitespace() {
        runner.withPropertyValues("vlm.client.url=   ")
                .run(context -> assertThat(context).doesNotHaveBean(VlmHealthIndicator.class));
    }

    @Test
    @DisplayName("연동주소가_미정의여도_빈이_등록되지_않는다")
    void indicator_not_registered_when_url_is_absent() {
        runner.run(context -> assertThat(context).doesNotHaveBean(VlmHealthIndicator.class));
    }

    @Test
    @DisplayName("연동주소가_주입되면_빈이_등록된다")
    void indicator_registered_when_url_is_present() {
        runner.withPropertyValues("vlm.client.url=http://vlm.example.internal:9400")
                .run(context -> assertThat(context).hasSingleBean(VlmHealthIndicator.class));
    }

    @Test
    @DisplayName("연동주소에_작은따옴표가_있어도_기동이_실패하지_않는다")
    void indicator_registered_when_url_contains_single_quote() {
        // given — 판정을 문자열 표현식(SpEL)으로 하면 여기서 기동이 통째로 죽는다.
        //   Environment 가 placeholder 를 <b>표현식 파싱 이전에</b> 치환하므로 값의 작은따옴표가
        //   표현식의 문자열 종결자로 먹혀 SpelParseException(EL1046E) 이 나고 컨텍스트가 실패한다.
        //   조건은 문자열 파싱이 없는 판정기여야 한다.
        runner.withPropertyValues("vlm.client.url=http://vlm.example.internal:9400/a'b")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VlmHealthIndicator.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubVlmClientConfig {

        /**
         * 원장 캐시 스텁 — 이 인디케이터는 등록된 시계열 노드를 <b>상세에만</b> 싣는다(판정 축은
         * 여전히 연동 클라이언트다). 등록 조건 검증에는 쓰이지 않으므로 빈 원장이면 충분하다.
         */
        @Bean
        AiSrvrRegistry aiSrvrRegistry() {
            return new AiSrvrRegistry(mock(LsAiSrvrRepository.class));
        }
        /** 등록 조건만 검증하므로 호출되지 않는다 — 실제 핑 동작은 VlmHealthIndicatorTest 가 본다. */
        @Bean
        VlmClient vlmClient() {
            return mock(VlmClient.class);
        }
    }
}
