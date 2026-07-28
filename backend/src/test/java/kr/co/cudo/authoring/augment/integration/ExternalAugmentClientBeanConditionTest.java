package kr.co.cudo.authoring.augment.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExternalAugmentClient} 구현체 활성 조건 + 기동 로깅 가드 — Phase 7-A1 (E-ISSUE-03).
 *
 * <p>과거 {@code NoopExternalAugmentClient} 가 {@code @Primary} + {@code matchIfMissing=true} 라
 * 전 환경에서 외부 호출이 나가지 않았다. 이제 <b>기본은 http</b>, noop 은 명시 전용이다.
 */
class ExternalAugmentClientBeanConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestBeans.class);

    @Test
    @DisplayName("mode_미설정_기본에서는_HttpExternalAugmentClient_가_활성이다")
    void httpIsDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(HttpExternalAugmentClient.class);
            assertThat(ctx).doesNotHaveBean(NoopExternalAugmentClient.class);
        });
    }

    @Test
    @DisplayName("mode_http_에서는_HttpExternalAugmentClient_가_활성이다")
    void httpWhenExplicit() {
        runner.withPropertyValues("authoring.augment.external.mode=http")
                .run(ctx -> assertThat(ctx).hasSingleBean(HttpExternalAugmentClient.class));
    }

    @Test
    @DisplayName("mode_noop_에서는_Noop_만_활성이고_Http_는_비활성이다")
    void noopWhenExplicit() {
        runner.withPropertyValues("authoring.augment.external.mode=noop").run(ctx -> {
            assertThat(ctx).hasSingleBean(NoopExternalAugmentClient.class);
            assertThat(ctx).doesNotHaveBean(HttpExternalAugmentClient.class);
        });
    }

    @Test
    @DisplayName("mode_dev_는_더_이상_지원되지_않아_Http_도_Noop_도_활성되지_않는다")
    void neitherWhenDev() {
        runner.withPropertyValues("authoring.augment.external.mode=dev").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(HttpExternalAugmentClient.class);
            assertThat(ctx).doesNotHaveBean(NoopExternalAugmentClient.class);
        });
    }

    @Test
    @DisplayName("기동시_활성_ExternalAugmentClient_구현체명이_INFO_로_로깅됨")
    void logsActiveImplementationOnStartup() {
        Logger logger = (Logger) LoggerFactory.getLogger(ActiveAugmentClientLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ActiveAugmentClientLogger(new NoopExternalAugmentClient()).logActiveClient();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("INFO");
            assertThat(event.getFormattedMessage())
                    .contains("active ExternalAugmentClient")
                    .contains("NoopExternalAugmentClient");
        });
    }

    /** 클라이언트 빈이 필요로 하는 협력자만 스텁으로 제공한다. */
    @Configuration(proxyBeanMethods = false)
    @Import({HttpExternalAugmentClient.class, NoopExternalAugmentClient.class})
    static class TestBeans {

        @Bean(name = "augmentApiWebClient")
        WebClient augmentApiWebClient() {
            return WebClient.builder().baseUrl("http://localhost:9400").build();
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }

        @Bean
        RetryRegistry retryRegistry() {
            return RetryRegistry.ofDefaults();
        }
    }
}
