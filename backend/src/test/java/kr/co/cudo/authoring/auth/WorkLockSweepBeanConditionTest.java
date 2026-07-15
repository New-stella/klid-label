package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.auth.scheduler.WorkLockSweepConfig;
import kr.co.cudo.authoring.auth.scheduler.WorkLockSweepJob;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R4 — 만료 작업락 sweeper 활성 조건(@ConditionalOnProperty) 검증.
 *
 * <p>{@code authoring.work-lock.sweep.enabled=true} 일 때만 {@link WorkLockSweepJob}/{@link WorkLockSweepConfig}
 * 빈이 등록되어 스케줄러가 기동한다. 미설정/false 면 미등록(테스트·로컬 기본 비활성)이다.
 * 선례({@code DevAugmentCallbackSimulatorBeanConditionTest}) 패턴으로 컨텍스트 러너에서 조건을 실증한다.
 */
class WorkLockSweepBeanConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestBeans.class);

    @Test
    @DisplayName("sweep_비활성_프로퍼티면_스케줄러_미기동")
    void sweep_비활성_프로퍼티면_스케줄러_미기동() {
        runner.withPropertyValues("authoring.work-lock.sweep.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(WorkLockSweepJob.class);
                    assertThat(ctx).doesNotHaveBean(WorkLockSweepConfig.class);
                });
    }

    @Test
    @DisplayName("sweep_프로퍼티_미설정이면_스케줄러_미기동")
    void sweep_프로퍼티_미설정이면_스케줄러_미기동() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(WorkLockSweepJob.class);
            assertThat(ctx).doesNotHaveBean(WorkLockSweepConfig.class);
        });
    }

    @Test
    @DisplayName("sweep_활성_프로퍼티면_스케줄러_빈이_등록된다")
    void sweep_활성_프로퍼티면_스케줄러_빈이_등록된다() {
        runner.withPropertyValues("authoring.work-lock.sweep.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WorkLockSweepJob.class);
                    assertThat(ctx).hasSingleBean(WorkLockSweepConfig.class);
                });
    }

    @Configuration
    @Import({WorkLockSweepJob.class, WorkLockSweepConfig.class})
    static class TestBeans {
        @Bean
        WorkLockService workLockService() {
            return mock(WorkLockService.class);
        }
    }
}
