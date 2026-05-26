package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 3 -- ControlNotifyDebouncer 단위 테스트.
 */
class ControlNotifyDebouncerTest {

    private ControlNotifyService notifyService;
    private ControlNotifyMetrics metrics;
    private ControlNotifyDebouncer debouncer;

    @BeforeEach
    void setUp() {
        notifyService = mock(ControlNotifyService.class);
        metrics = mock(ControlNotifyMetrics.class);
        debouncer = new ControlNotifyDebouncer(notifyService, 60L, metrics);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, ControlNotifyDebouncer.DebouncedWindow> getWindows() {
        try {
            Field f = ControlNotifyDebouncer.class.getDeclaredField("windows");
            f.setAccessible(true);
            return (ConcurrentHashMap<Long, ControlNotifyDebouncer.DebouncedWindow>) f.get(debouncer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("accumulate_같은_rawSn_3회_호출시_윈도우_1개")
    void accumulate_sameRawSn_singleWindow() {
        // given / when
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 2L, "LABEL_UPDATED", 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 3L, "META_UPDATED", 10L));

        // then
        assertThat(getWindows()).hasSize(1);
        assertThat(getWindows().get(100L).getFrameIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("accumulate_다른_rawSn_은_별도_윈도우")
    void accumulate_differentRawSn_separateWindows() {
        // given / when
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, "META_UPDATED", 10L));

        // then
        assertThat(getWindows()).hasSize(2);
        assertThat(getWindows()).containsKeys(100L, 200L);
    }

    @Test
    @DisplayName("flushExpiredWindows_60초_경과_윈도우만_flush")
    void flushExpiredWindows_onlyExpired() {
        // given -- 직접 윈도우에 만료된 항목 삽입
        ConcurrentHashMap<Long, ControlNotifyDebouncer.DebouncedWindow> windows = getWindows();
        ControlNotifyDebouncer.DebouncedWindow expired = createWindowWithCreatedAt(
                System.currentTimeMillis() - 70_000L, List.of(1L), List.of("LABEL_ADDED"));
        ControlNotifyDebouncer.DebouncedWindow fresh = createWindowWithCreatedAt(
                System.currentTimeMillis(), List.of(2L), List.of("META_UPDATED"));
        windows.put(100L, expired);
        windows.put(200L, fresh);

        // when
        debouncer.flushExpiredWindows();

        // then -- 만료된 100L 만 flush
        verify(notifyService, times(1)).sendModified(eq(100L), anyList(), anyList());
        verify(notifyService, never()).sendModified(eq(200L), anyList(), anyList());
        assertThat(windows).hasSize(1).containsKey(200L);
    }

    @Test
    @DisplayName("flushExpiredWindows_미경과_윈도우는_유지")
    void flushExpiredWindows_freshWindowsKept() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L));

        // when
        debouncer.flushExpiredWindows();

        // then
        verify(notifyService, never()).sendModified(any(), anyList(), anyList());
        assertThat(getWindows()).hasSize(1);
    }

    @Test
    @DisplayName("flushAll_모든_윈도우_즉시_flush")
    void flushAll_flushesEverything() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, "META_UPDATED", 10L));

        // when
        debouncer.flushAll();

        // then
        verify(notifyService, times(1)).sendModified(eq(100L), anyList(), anyList());
        verify(notifyService, times(1)).sendModified(eq(200L), anyList(), anyList());
        assertThat(getWindows()).isEmpty();
    }

    @Test
    @DisplayName("DebouncedWindow_frameIds_중복_제거")
    void debouncedWindow_deduplicatesFrameIds() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, "LABEL_UPDATED", 10L));  // 같은 srcSn

        // then
        ControlNotifyDebouncer.DebouncedWindow window = getWindows().get(100L);
        assertThat(window.getFrameIds()).hasSize(1).containsExactly(1L);
        assertThat(window.getChangeTypes()).hasSize(2).contains("LABEL_ADDED", "LABEL_UPDATED");
    }

    // --- Phase 5: 메트릭 호출 검증 ---

    @Test
    @DisplayName("flushExpiredWindows_실행시_metrics_debounceFlush_호출됨")
    void flushExpiredWindows_incrementsDebounceFlushMetric() {
        // given -- 만료된 윈도우 2개 삽입
        ConcurrentHashMap<Long, ControlNotifyDebouncer.DebouncedWindow> windows = getWindows();
        ControlNotifyDebouncer.DebouncedWindow expired1 = createWindowWithCreatedAt(
                System.currentTimeMillis() - 70_000L, List.of(1L), List.of("LABEL_ADDED"));
        ControlNotifyDebouncer.DebouncedWindow expired2 = createWindowWithCreatedAt(
                System.currentTimeMillis() - 70_000L, List.of(2L), List.of("META_UPDATED"));
        windows.put(100L, expired1);
        windows.put(200L, expired2);

        // when
        debouncer.flushExpiredWindows();

        // then -- flush 된 윈도우 수만큼 debounceFlush 호출
        verify(metrics, times(2)).incrementDebounceFlush();
    }

    @Test
    @DisplayName("flushExpiredWindows_미만료시_metrics_debounceFlush_미호출")
    void flushExpiredWindows_noExpired_noMetric() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L));

        // when
        debouncer.flushExpiredWindows();

        // then
        verify(metrics, never()).incrementDebounceFlush();
    }

    /**
     * 테스트 헬퍼 -- createdAt 을 지정하여 DebouncedWindow 생성.
     */
    private ControlNotifyDebouncer.DebouncedWindow createWindowWithCreatedAt(
            long createdAt, List<Long> frameIds, List<String> changeTypes) {
        ControlNotifyDebouncer.DebouncedWindow window = new ControlNotifyDebouncer.DebouncedWindow();
        // createdAt 을 리플렉션으로 변경
        try {
            Field f = ControlNotifyDebouncer.DebouncedWindow.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(window, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < frameIds.size(); i++) {
            window.addFrame(frameIds.get(i), changeTypes.get(i));
        }
        return window;
    }
}
