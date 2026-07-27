package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * MED-1 / LOW-2(Phase 5C) — export 재생성 flush <b>스케줄 발화 배선</b> 검증.
 *
 * <h3>이 테스트가 잡는 것</h3>
 * {@link ControlNotifyDebouncerTest} 의 재생성 테스트들은 {@code flushExpiredWindows()} 를 <b>직접 호출</b>해서,
 * flush 를 실제로 발화시키는 스케줄러 배선을 통째로 제거해도 통과한다. 이 테스트는 그 사각을 메운다 —
 * {@link ControlNotifyDebouncer} 가 <b>자신이 소유한 전용 스케줄러</b>로 flush 를 실제로 tick 하는지를 단언한다.
 *
 * <p>구 구현은 flush 를 {@code @Scheduled} 로 걸어 {@code @EnableScheduling}(무관한 토글 3곳으로만 켜짐)에
 * 의존했고, 셋을 모두 끈 형상에서 flush 가 영영 발화하지 않았다(HIGH-E 잔여). 이제 flush 는 전용 데몬
 * 스케줄러로 자가 발화하므로 그 토글들과 <b>독립</b>이다 — 이 테스트는 무관한 스케줄러 토글을 전혀 켜지 않고도
 * flush 가 tick 함을 보인다.
 *
 * <p><b>MED-1 수정을 되돌리면(전용 스케줄러 제거 → {@code @Scheduled} 회귀)</b> 첫 테스트가 실패한다:
 * {@code @EnableScheduling} 이 없는 이 테스트 컨텍스트에서는 flush 가 절대 tick 하지 않아
 * {@code runReExportThenNotify} 가 호출되지 않기 때문이다.
 *
 * <p>실제 대기 시간은 하드코딩 sleep 이 아니라 {@link Awaitility} 의 상한 폴링으로 처리한다.
 */
class ControlNotifyDebounceFlushSchedulerTest {

    @Test
    @DisplayName("전용_flush_스케줄러가_무관한_토글_없이도_flush를_tick해_재생성을_트리거한다 (MED-1)")
    void dedicatedSchedulerTicksFlushAndTriggersReExport() {
        // given — 통지 토글 off 형상(notifyService/metrics=null) + 전용 flush 스케줄러 활성(true) + 짧은 간격.
        //         windowSec=0 이라 축적 즉시 만료로 간주되어 다음 tick 에서 flush 된다.
        AsyncDatasetExportRunner exportRunner = mock(AsyncDatasetExportRunner.class);
        ControlNotifyDebouncer debouncer =
                new ControlNotifyDebouncer(null, 0L, null, exportRunner, true, 20L);
        try {
            debouncer.startFlushScheduler();
            // 전용 스케줄러가 실제로 기동됐는지(배선) — 결정론적 확인.
            assertThat(schedulerOf(debouncer)).isNotNull();

            // when — 재생성 동반 수정을 축적한다(승인 후 촬영환경 수정 형상). 어떤 @EnableScheduling 도 없다.
            debouncer.accumulate(new TaskModifiedEvent(100L, null, ChangeType.META_UPDATED, 10L, true));

            // then — 전용 스케줄러가 flush 를 tick 해 export 재생성이 트리거된다(통지 콜백은 null — 토글 off).
            Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() ->
                            verify(exportRunner).runReExportThenNotify(eq(100L), eq(true), any()));
        } finally {
            debouncer.flushAll(); // 전용 스케줄러 정지 + drain
        }
    }

    @Test
    @DisplayName("regenFlush_토글이_false면_전용_스케줄러가_기동하지_않는다 (테스트_격리_형상)")
    void schedulerNotStartedWhenDisabled() {
        // given — 테스트 격리 형상: flushSchedulerEnabled=false.
        AsyncDatasetExportRunner exportRunner = mock(AsyncDatasetExportRunner.class);
        ControlNotifyMetrics metrics = mock(ControlNotifyMetrics.class);
        ControlNotifyService notifyService = mock(ControlNotifyService.class);
        ControlNotifyDebouncer debouncer =
                new ControlNotifyDebouncer(notifyService, 0L, metrics, exportRunner, false, 20L);

        // when
        debouncer.startFlushScheduler();

        // then — 스케줄러가 생성되지 않는다(결정론적). flush 는 테스트가 직접 호출해야만 동작한다.
        assertThat(schedulerOf(debouncer)).isNull();
        debouncer.accumulate(new TaskModifiedEvent(100L, null, ChangeType.META_UPDATED, 10L, true));
        // 스케줄러가 없으므로 tick 이 없다 — 직접 호출 전까지 재생성이 트리거되지 않는다(결정론적).
        verify(exportRunner, org.mockito.Mockito.never())
                .runReExportThenNotify(any(), anyBoolean(), any());
    }

    private static ScheduledExecutorService schedulerOf(ControlNotifyDebouncer target) {
        try {
            Field f = ControlNotifyDebouncer.class.getDeclaredField("flushScheduler");
            f.setAccessible(true);
            return (ScheduledExecutorService) f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
