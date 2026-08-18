package kr.co.cudo.authoring.support;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;

import java.time.Duration;

/**
 * 테스트용 {@link AiWaitBudgetPolicy} 조립기.
 *
 * <p>대기 예산과 무관한 테스트가 {@code SystemConfigService} 를 직접 생성할 때 쓴다 — 그 테스트들이
 * 재시도 설정을 직접 조립하면 무엇이 요점인지 흐려지고, 예산 형상이 바뀔 때마다 함께 손봐야 한다.
 *
 * <p>⚠ 여기 값은 <b>운영 형상의 사본</b>이라 예산 자체를 검증하는 테스트에는 쓰지 않는다. 그런
 * 테스트는 재시도 설정을 스스로 조립해 "설정을 바꾸면 예산이 따라 움직이는가"를 확인해야 한다.
 */
public final class TestAiWaitBudgetPolicies {

    private TestAiWaitBudgetPolicies() {
    }

    /** {@code resilience4j.retry.instances.ai} 와 같은 형상(3회 · 1s · ×2). */
    public static AiWaitBudgetPolicy production() {
        return new AiWaitBudgetPolicy(RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofSeconds(1), 2.0))
                .build()));
    }
}
