package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cudo.authoring.controlnotify.fallback.LsControlNotifyFallbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 -- ControlNotifyMetrics 단위 테스트.
 */
class ControlNotifyMetricsTest {

    private MeterRegistry registry;
    private LsControlNotifyFallbackRepository fallbackRepository;
    private ControlNotifyMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        fallbackRepository = mock(LsControlNotifyFallbackRepository.class);
        when(fallbackRepository.countByStatusIn(anyCollection())).thenReturn(0L);
        metrics = new ControlNotifyMetrics(registry, fallbackRepository);
    }

    @Test
    @DisplayName("completedSuccess_카운터_증가_확인")
    void completedSuccess_counter_increments() {
        // given
        double before = registry.counter("control.notify.completed.success").count();

        // when
        metrics.incrementCompletedSuccess();
        metrics.incrementCompletedSuccess();

        // then
        double after = registry.counter("control.notify.completed.success").count();
        assertThat(after - before).isEqualTo(2.0);
    }

    @Test
    @DisplayName("completedFailed_카운터_증가_확인")
    void completedFailed_counter_increments() {
        // given
        double before = registry.counter("control.notify.completed.failed").count();

        // when
        metrics.incrementCompletedFailed();

        // then
        double after = registry.counter("control.notify.completed.failed").count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("modifiedSuccess_카운터_증가_확인")
    void modifiedSuccess_counter_increments() {
        // given
        double before = registry.counter("control.notify.modified.success").count();

        // when
        metrics.incrementModifiedSuccess();
        metrics.incrementModifiedSuccess();
        metrics.incrementModifiedSuccess();

        // then
        double after = registry.counter("control.notify.modified.success").count();
        assertThat(after - before).isEqualTo(3.0);
    }

    @Test
    @DisplayName("debounceFlush_카운터_증가_확인")
    void debounceFlush_counter_increments() {
        // given
        double before = registry.counter("control.notify.debounce.flush").count();

        // when
        metrics.incrementDebounceFlush();

        // then
        double after = registry.counter("control.notify.debounce.flush").count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("폴백큐_깊이_게이지_정확성")
    @SuppressWarnings("unchecked")
    void fallback_depth_gauge_reflects_repository() {
        // given -- 리포지토리가 5를 반환하도록 설정
        when(fallbackRepository.countByStatusIn(anyCollection())).thenReturn(5L);

        // when
        double depth = registry.get("control.notify.fallback.depth").gauge().value();

        // then
        assertThat(depth).isEqualTo(5.0);
    }
}
