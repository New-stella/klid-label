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
 * {@link ExternalAugmentClient} 구현체 활성 조건 + 기동 로깅 가드.
 *
 * <h3>★ 지금 고정하는 것은 「구현체가 하나뿐이고 조건이 없다」는 사실이다 (2026-09-03)</h3>
 * <p>구 형태는 {@code authoring.augment.external.mode} 값으로 http/noop 구현체를 <b>골랐다</b>.
 * 그 <b>미연동 모드 토글 축이 폐기</b>되면서 no-op 구현체와 조건부 활성이 함께 사라졌고, 이제
 * <b>연동이 유일한 형상</b>이다. 「아직 연동 안 됨」은 위탁 주소 미주입으로만 표현된다.
 *
 * <p>⚠ <b>키 자체의 부재</b>(yml 선언 · 코드 참조)는 {@code architecture/ConfigPropertyKeyGuardTest}
 * 의 제거 키 목록이 고정한다 — 여기서 다시 훑지 않는다(두 곳에서 세면 한쪽만 갱신된다). 조건부
 * 애노테이션이 다시 붙으면 그쪽이 먼저 빨개진다.
 */
class ExternalAugmentClientBeanConditionTest {

    /** 폐기된 미연동 모드 토글 키 — 넣어도 빈 배선이 달라지지 않아야 한다. */
    private static final String RETIRED_MODE_KEY = "authoring.augment.external.mode";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestBeans.class);

    @Test
    @DisplayName("★설정과_무관하게_HttpExternalAugmentClient_가_유일한_구현체로_활성이다")
    void httpIsTheOnlyImplementation() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(HttpExternalAugmentClient.class));
        // 폐기된 키를 넣어도 아무 일도 일어나지 않는다 — 읽는 자리가 없다.
        runner.withPropertyValues(RETIRED_MODE_KEY + "=noop")
                .run(ctx -> assertThat(ctx).hasSingleBean(HttpExternalAugmentClient.class));
        runner.withPropertyValues(RETIRED_MODE_KEY + "=dev")
                .run(ctx -> assertThat(ctx).hasSingleBean(HttpExternalAugmentClient.class));
    }

    @Test
    @DisplayName("기동시_활성_ExternalAugmentClient_구현체명이_INFO_로_로깅됨")
    void logsActiveImplementationOnStartup() {
        Logger logger = (Logger) LoggerFactory.getLogger(ActiveAugmentClientLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner.run(ctx -> ctx.getBean(ActiveAugmentClientLogger.class).logActiveClient());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("INFO");
            assertThat(event.getFormattedMessage())
                    .contains("active ExternalAugmentClient")
                    .contains("HttpExternalAugmentClient");
        });
    }

    /** 클라이언트 빈이 필요로 하는 협력자만 스텁으로 제공한다. */
    @Configuration(proxyBeanMethods = false)
    @Import({HttpExternalAugmentClient.class, ActiveAugmentClientLogger.class})
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
