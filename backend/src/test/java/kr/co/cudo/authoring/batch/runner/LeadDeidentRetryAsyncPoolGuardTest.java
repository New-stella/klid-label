package kr.co.cudo.authoring.batch.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선두 비식별 진입의 풀 배선을 애노테이션으로 결박한다 — 컨텍스트를 올리지 않는 순수 단위 시험.
 *
 * <p>적재 직후 자동 실행은 선두 비식별 풀(호출자 실행 정책), 사람이 누르는 배치 재시작은 수동 재기동 풀(포화 시
 * 거부)이다. 본체({@code runNow})에 비동기 속성이 붙으면 재시작 진입이 다시 선두 비식별 풀로 넘어간다.
 *
 * @design API-167
 * @design AC-1133
 */
class LeadDeidentRetryAsyncPoolGuardTest {

    @Test
    @DisplayName("★선두비식별_본체_runNow에는_비동기_속성이_없다")
    void runNow에는_Async없음() throws Exception {
        Method runNow = AsyncDeidentifyRunner.class.getMethod("runNow", Long.class);

        assertThat(runNow.getAnnotation(Async.class)).isNull();
        assertThat(AsyncDeidentifyRunner.class.getAnnotation(Async.class)).isNull();
    }

    @Test
    @DisplayName("적재직후_진입_runAsync는_선두비식별_풀을_쓴다")
    void runAsync는_선두비식별풀() throws Exception {
        Async async = AsyncDeidentifyRunner.class.getMethod("runAsync", Long.class).getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("batchAsyncExecutor");
    }

    @Test
    @DisplayName("★재시작_진입_runLeadDeidentRetryAsync는_수동재기동_풀을_쓴다")
    void 재시작진입은_수동재기동풀() throws Exception {
        Async async = AsyncBatchReprocessRunner.class
                .getMethod("runLeadDeidentRetryAsync", Long.class).getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("batchReprocessExecutor");
    }
}
