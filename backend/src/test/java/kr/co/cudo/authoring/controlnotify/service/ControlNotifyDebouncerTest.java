package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.controlnotify.debounce.FakeControlNotifyDebounceStore;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ControlNotifyDebouncer 단위 테스트.
 *
 * <p>회귀 방어 대상: D-ISSUE-42(페어링) · D-ISSUE-43(srcSn=null 유실) · S6(중복 flush).
 */
class ControlNotifyDebouncerTest {

    private ControlNotifyService notifyService;
    private ControlNotifyMetrics metrics;
    private AsyncDatasetExportRunner exportRunner;
    private FakeControlNotifyDebounceStore store;
    private ControlNotifyDebouncer debouncer;

    @BeforeEach
    void setUp() {
        notifyService = mock(ControlNotifyService.class);
        metrics = mock(ControlNotifyMetrics.class);
        exportRunner = mock(AsyncDatasetExportRunner.class);
        store = new FakeControlNotifyDebounceStore();
        // 전용 flush 스케줄러 비활성(false) — 단위 테스트는 flushExpiredWindows()/flushAll() 을 직접 호출한다.
        //   스케줄러 tick 배선은 ControlNotifyDebounceFlushSchedulerTest 가 별도 검증한다.
        //   Phase 9-C: 윈도우 저장소는 인메모리 페이크로 주입한다(실 DB 정합은 크로스노드 IT 가 검증).
        debouncer = new ControlNotifyDebouncer(store, notifyService, 60L, metrics, exportRunner,
                false, 10_000L, 300L, 100, true);
    }

    /** 만료 재현 — 구 구현의 {@code DebouncedWindow.createdAt} 되감기를 저장소 헬퍼로 대체한다. */
    private void expire(Long rawSn) {
        store.expire(rawSn);
    }

    @Test
    @DisplayName("accumulate_같은_rawSn_3회_호출시_윈도우_1개")
    void accumulate_sameRawSn_singleWindow() {
        // given / when
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 2L, ChangeType.LABEL_UPDATED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 3L, ChangeType.META_UPDATED, 10L));

        // then
        assertThat(store.openRawSns()).hasSize(1);
        assertThat(store.snapshot(100L).frameChanges())
                .extracting(FrameChangeSet::srcSn)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("accumulate_다른_rawSn_은_별도_윈도우")
    void accumulate_differentRawSn_separateWindows() {
        // given / when
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, ChangeType.META_UPDATED, 10L));

        // then
        assertThat(store.openRawSns()).hasSize(2);
        assertThat(store.openRawSns()).contains(100L, 200L);
    }

    @Test
    @DisplayName("TASK_MODIFIED_가_프레임ID와_변경종류를_페어로_전달")
    void framesAndChangeTypesArePaired() {
        // given — 프레임 A 는 라벨수정, 프레임 B 는 메타수정
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_UPDATED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 2L, ChangeType.META_UPDATED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        expire(100L);

        // when
        debouncer.flushExpiredWindows();

        // then — 평행 Set 이 아니라 (srcSn ↔ 변경종류) 페어로 전달되어야 한다.
        ArgumentCaptor<List<FrameChangeSet>> captor = frameChangeCaptor();
        verify(notifyService).sendModified(eq(100L), captor.capture(), anySet(), anyBoolean());

        List<FrameChangeSet> changes = captor.getValue();
        assertThat(changes).hasSize(2);
        assertThat(find(changes, 1L).changeTypes())
                .containsExactlyInAnyOrder(ChangeType.LABEL_UPDATED, ChangeType.LABEL_ADDED);
        assertThat(find(changes, 2L).changeTypes()).containsExactly(ChangeType.META_UPDATED);
    }

    @Test
    @DisplayName("srcSn_이_null_인_영상단위_변경통지가_디바운서에서_유실되지_않음")
    void nullSrcSnIsNotLost() {
        // given — 구 구현은 ConcurrentHashMap.newKeySet().add(null) 에서 NPE 를 던져 통지가 사라졌다.
        debouncer.accumulate(new TaskModifiedEvent(100L, null, ChangeType.META_UPDATED, 10L));

        // then — 윈도우가 생성되어야 한다.
        assertThat(store.openRawSns()).contains(100L);

        // when
        expire(100L);
        debouncer.flushExpiredWindows();

        // then — 영상 단위 변경으로 전달되고 통지가 발송된다.
        ArgumentCaptor<Set<String>> videoLevel = videoLevelCaptor();
        verify(notifyService).sendModified(eq(100L), anyList(), videoLevel.capture(), anyBoolean());
        assertThat(videoLevel.getValue()).containsExactly(ChangeType.META_UPDATED);
    }

    @Test
    @DisplayName("srcSn_이_null_이어도_프레임_변경_목록에_null_이_섞이지_않는다")
    void nullSrcSnNeverEntersFrameChanges() {
        // given — 영상 단위 + 프레임 단위 변경 혼재
        debouncer.accumulate(new TaskModifiedEvent(100L, null, ChangeType.META_UPDATED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 5L, ChangeType.LABEL_UPDATED, 10L));
        expire(100L);

        // when
        debouncer.flushExpiredWindows();

        // then — 프레임 목록에는 null srcSn 이 없어야 한다(파일명 "frame-null" 방지).
        ArgumentCaptor<List<FrameChangeSet>> captor = frameChangeCaptor();
        verify(notifyService).sendModified(eq(100L), captor.capture(), anySet(), anyBoolean());
        assertThat(captor.getValue()).extracting(FrameChangeSet::srcSn).containsExactly(5L);
        assertThat(captor.getValue()).extracting(FrameChangeSet::srcSn).doesNotContainNull();
    }

    @Test
    @DisplayName("flushExpiredWindows_60초_경과_윈도우만_flush")
    void flushExpiredWindows_onlyExpired() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, ChangeType.META_UPDATED, 10L));
        expire(100L);

        // when
        debouncer.flushExpiredWindows();

        // then
        verify(notifyService, times(1)).sendModified(eq(100L), anyList(), anySet(), anyBoolean());
        verify(notifyService, never()).sendModified(eq(200L), anyList(), anySet(), anyBoolean());
        assertThat(store.openRawSns()).containsExactly(200L);
    }

    @Test
    @DisplayName("flushExpiredWindows_미경과_윈도우는_유지")
    void flushExpiredWindows_freshWindowsKept() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));

        // when
        debouncer.flushExpiredWindows();

        // then
        verify(notifyService, never()).sendModified(any(), anyList(), anySet(), anyBoolean());
        assertThat(store.openRawSns()).hasSize(1);
    }

    @Test
    @DisplayName("flushAll_모든_윈도우_즉시_flush")
    void flushAll_flushesEverything() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, ChangeType.META_UPDATED, 10L));

        // when
        debouncer.flushAll();

        // then
        verify(notifyService, times(1)).sendModified(eq(100L), anyList(), anySet(), anyBoolean());
        verify(notifyService, times(1)).sendModified(eq(200L), anyList(), anySet(), anyBoolean());
        assertThat(store.totalRows()).isZero();
    }

    @Test
    @DisplayName("DebouncedWindow_frameIds_중복_제거")
    void debouncedWindow_deduplicatesFrameIds() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_UPDATED, 10L));

        // then — 프레임은 1건으로 합쳐지고 변경 종류는 둘 다 보존된다.
        List<FrameChangeSet> changes = store.snapshot(100L).frameChanges();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).srcSn()).isEqualTo(1L);
        assertThat(changes.get(0).changeTypes())
                .containsExactlyInAnyOrder(ChangeType.LABEL_ADDED, ChangeType.LABEL_UPDATED);
    }

    @Test
    @DisplayName("동시_flush_시_동일_rawSn_이_두_번_전송되지_않는다")
    void concurrentFlushSendsOnce() throws Exception {
        // given — 만료된 윈도우 1개에 대해 만료 스캔과 셧다운 flush 가 동시에 진입한다(S6).
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_UPDATED, 10L));
        expire(100L);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        // when — 절반은 만료 스캔, 절반은 @PreDestroy 플러시
        for (int i = 0; i < threads; i++) {
            final boolean expiredScan = (i % 2 == 0);
            pool.submit(() -> {
                try {
                    start.await();
                    if (expiredScan) {
                        debouncer.flushExpiredWindows();
                    } else {
                        debouncer.flushAll();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // then — 정확히 1회만 전송(관제 버전 증식 방지)
        verify(notifyService, times(1)).sendModified(eq(100L), anyList(), anySet(), anyBoolean());
        assertThat(store.totalRows()).isZero();
    }

    @Test
    @DisplayName("변경종류가_모두_null_이면_빈_변경셋으로_flush_되어도_예외가_없다")
    void allNullChangeTypesFlushWithoutException() {
        // given — changeType 이 null 인 이벤트만 들어온다(발행측 버그/부분 구현). 축적은 무시되지만
        //         윈도우 자체는 만들어지므로 flush 가 NPE 없이 빈 변경셋으로 나가야 한다.
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, null, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, null, null, 10L));
        expire(100L);

        // when
        debouncer.flushExpiredWindows();

        // then — 프레임 변경·영상 변경 모두 비어 있고 예외는 없다.
        ArgumentCaptor<List<FrameChangeSet>> frames = frameChangeCaptor();
        ArgumentCaptor<Set<String>> videoLevel = videoLevelCaptor();
        verify(notifyService).sendModified(eq(100L), frames.capture(), videoLevel.capture(), anyBoolean());
        assertThat(frames.getValue()).isEmpty();
        assertThat(videoLevel.getValue()).isEmpty();
    }

    // --- A-2: export 재생성 동반 여부를 이벤트가 실어 나른다 ---

    @Test
    @DisplayName("재export_없는_변경만_축적되면_exportRegenerated_false_로_전달된다")
    void windowWithoutReExportPropagatesFalse() {
        // given — 촬영환경 메타 수정 등: 통지는 나가지만 디스크 산출물은 그대로다.
        debouncer.accumulate(new TaskModifiedEvent(100L, null, ChangeType.META_UPDATED, 10L));
        expire(100L);

        // when
        debouncer.flushExpiredWindows();

        // then
        verify(notifyService).sendModified(eq(100L), anyList(), anySet(), eq(false));
    }

    @Test
    @DisplayName("재export_동반_변경이_섞이면_export를_먼저_마친_뒤_exportRegenerated_true_통지 (C-2)")
    void windowWithReExportExportsBeforeNotify() {
        // given — 같은 윈도우에 재생성 없는 변경과 재생성 동반 변경이 혼재한다.
        //         산출물이 전량 바뀌므로 OR 누적으로 재생성 동반(true)이 된다.
        debouncer.accumulate(new TaskModifiedEvent(100L, 5L, ChangeType.LABEL_UPDATED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(100L, null, ChangeType.META_UPDATED, 10L, true));
        expire(100L);

        // when
        debouncer.flushExpiredWindows();

        // then — 통지를 직접 보내지 않고, export(force=true) 를 먼저 마친 뒤 통지하도록 러너에 위임한다.
        verify(notifyService, never()).sendModified(any(), anyList(), anySet(), anyBoolean());
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(exportRunner).runReExportThenNotify(eq(100L), eq(true), callback.capture());

        // 러너가 export 를 마친 뒤 실행하는 콜백이 exportRegenerated=true 통지를 낸다.
        callback.getValue().run();
        verify(notifyService).sendModified(eq(100L), anyList(), anySet(), eq(true));
    }

    @Test
    @DisplayName("재export_없는_변경은_러너_위임_없이_즉시_통지된다")
    void windowWithoutReExportNotifiesDirectly() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 5L, ChangeType.LABEL_UPDATED, 10L));
        expire(100L);

        // when
        debouncer.flushExpiredWindows();

        // then — 디스크가 그대로이므로 재산출 위임 없이 즉시 통지(false).
        verify(exportRunner, never()).runReExportThenNotify(any(), anyBoolean(), any());
        verify(notifyService).sendModified(eq(100L), anyList(), anySet(), eq(false));
    }

    // --- HIGH-E(Phase 5C): 통지 토글 off(notifyService/metrics=null)에서도 export 재생성은 트리거된다 ---

    @Test
    @DisplayName("HIGH-E_통지_토글_off여도_승인후_수정_재생성_윈도우는_export를_트리거한다 (dev/stg/prd 형상)")
    void reExportFiresEvenWhenNotifyDisabled() {
        // given — 토글 off: ControlNotifyService/Metrics 빈이 없어 null 로 주입된 디바운서(항상 활성).
        FakeControlNotifyDebounceStore offStore = new FakeControlNotifyDebounceStore();
        ControlNotifyDebouncer noNotify = new ControlNotifyDebouncer(offStore, null, 60L, null, exportRunner,
                false, 10_000L, 300L, 100, true);
        noNotify.accumulate(new TaskModifiedEvent(100L, null, ChangeType.META_UPDATED, 10L, true));
        offStore.expire(100L);

        // when
        noNotify.flushExpiredWindows();

        // then — export(전량 재생성)는 통지 토글과 무관하게 트리거된다. 통지 콜백은 null(통지 미발송).
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(exportRunner).runReExportThenNotify(eq(100L), eq(true), callback.capture());
        assertThat(callback.getValue()).isNull();
        // 통지 서비스가 없으므로 sendModified 는 호출될 수 없다(주입 null). NPE 없이 완료됨을 확인.
    }

    @Test
    @DisplayName("HIGH-E_통지_토글_off이고_재생성_없는_수정은_export도_통지도_하지_않는다")
    void nonReExportDoesNothingWhenNotifyDisabled() {
        // given — 토글 off + 재생성 없는 메타 수정(디스크 무변경).
        FakeControlNotifyDebounceStore offStore = new FakeControlNotifyDebounceStore();
        ControlNotifyDebouncer noNotify = new ControlNotifyDebouncer(offStore, null, 60L, null, exportRunner,
                false, 10_000L, 300L, 100, true);
        noNotify.accumulate(new TaskModifiedEvent(100L, 5L, ChangeType.LABEL_UPDATED, 10L));
        offStore.expire(100L);

        // when — NPE 없이 완료되어야 한다(notifyService=null).
        noNotify.flushExpiredWindows();

        // then — 디스크 무변경 + 통지 off 이므로 export 도 통지도 없다.
        verify(exportRunner, never()).runReExportThenNotify(any(), anyBoolean(), any());
        assertThat(offStore.totalRows()).as("발송할 것이 없는 윈도우도 처리 후 제거된다").isZero();
    }

    // --- MED-3: 소실 윈도우를 dropped 로 계상 (debounce.flush 성공으로 세지 않는다) ---

    @Test
    @DisplayName("flush_중_통지가_예외로_소실되면_dropped_메트릭이_증가하고_debounceFlush로_세지_않는다")
    void isolatedFailureIncrementsDroppedNotFlush() {
        // given — 비재생성 윈도우 전송이 예외로 실패(윈도우는 이미 remove 되어 소실).
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_UPDATED, 10L));
        expire(100L);
        doThrow(new IllegalStateException("queue full"))
                .when(notifyService).sendModified(eq(100L), anyList(), anySet(), anyBoolean());

        // when
        debouncer.flushExpiredWindows();

        // then — 실제 드롭을 dropped 로 계상하고, flush 성공(debounceFlush)으로는 계상하지 않는다.
        verify(metrics).incrementDropped();
        verify(metrics, never()).incrementDebounceFlush();
        // Phase 9-C — 영구 소실이 아니라 <지연>이다: 윈도우가 FLUSHING 으로 남아 임차 만료 후 재클레임된다.
        assertThat(store.flushingRawSns()).containsExactly(100L);
    }

    // --- A-1: flush 루프 격리 (개별 윈도우 실패가 나머지를 죽이지 않는다) ---

    @Test
    @DisplayName("폴백큐가_가득_차도_디바운서_flush_루프가_중단되지_않고_나머지_윈도우를_처리한다")
    void queueFullDoesNotBreakFlushLoop() {
        // given — 첫 윈도우 전송이 큐 상한 초과(IllegalStateException)로 실패한다.
        //         claimAndSend 는 windows.remove 로 소유권을 먼저 가져가므로, 예외가 루프 밖으로 나가면
        //         이미 제거된 윈도우는 복구 불가로 사라지고 같은 tick 의 나머지도 스캔이 끊긴다.
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_UPDATED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, ChangeType.LABEL_UPDATED, 10L));
        expire(100L);
        expire(200L);
        doThrow(new IllegalStateException("queue full"))
                .when(notifyService).sendModified(eq(100L), anyList(), anySet(), anyBoolean());

        // when — 예외가 루프 밖으로 전파되지 않아야 한다.
        debouncer.flushExpiredWindows();

        // then — 실패한 윈도우 이후의 윈도우도 전송 시도된다.
        verify(notifyService, times(1)).sendModified(eq(200L), anyList(), anySet(), anyBoolean());
        // Phase 9-C — 실패한 윈도우는 저장소에 FLUSHING 으로 남아 임차 만료 후 재클레임된다(유실 아님).
        //   구 인메모리 구현은 여기서 윈도우가 사라져 통지가 영구 소실됐다.
        assertThat(store.flushingRawSns()).containsExactly(100L);
        assertThat(store.openRawSns()).isEmpty();
    }

    @Test
    @DisplayName("flush_중_한_윈도우가_실패해도_다음_윈도우는_전송된다")
    void oneWindowFailureDoesNotBlockOthers() {
        // given — 3개 윈도우 중 가운데가 임의 예외로 실패
        for (long rawSn : new long[]{100L, 200L, 300L}) {
            debouncer.accumulate(new TaskModifiedEvent(rawSn, 1L, ChangeType.LABEL_ADDED, 10L));
            expire(rawSn);
        }
        doThrow(new RuntimeException("boom"))
                .when(notifyService).sendModified(eq(200L), anyList(), anySet(), anyBoolean());

        // when
        debouncer.flushExpiredWindows();

        // then — 나머지 두 윈도우는 정상 전송된다.
        verify(notifyService, times(1)).sendModified(eq(100L), anyList(), anySet(), anyBoolean());
        verify(notifyService, times(1)).sendModified(eq(300L), anyList(), anySet(), anyBoolean());
        // 실패한 가운데 윈도우만 재시도 대상으로 보존된다(Phase 9-C).
        assertThat(store.flushingRawSns()).containsExactly(200L);
    }

    @Test
    @DisplayName("종료시_flushAll_도_개별_실패에_중단되지_않는다")
    void flushAllIsolatesFailures() {
        // given — @PreDestroy 경로에서 실패가 나면 남은 윈도우가 종료와 함께 전부 사라진다.
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, ChangeType.LABEL_ADDED, 10L));
        doThrow(new IllegalStateException("queue full"))
                .when(notifyService).sendModified(eq(100L), anyList(), anySet(), anyBoolean());

        // when
        debouncer.flushAll();

        // then
        verify(notifyService, times(1)).sendModified(eq(200L), anyList(), anySet(), anyBoolean());
        // 종료 경로에서도 실패분은 저장소에 남아 다른 노드/재기동 후 처리된다(Phase 9-C).
        assertThat(store.flushingRawSns()).containsExactly(100L);
    }

    // --- Phase 9-C: 크로스노드 디바운스 회귀 방어 ---

    @Test
    @DisplayName("재export_트리거는_control_notify_토글과_무관하게_동작한다")
    void reExportTriggerIsIndependentOfControlNotifyToggle() {
        // given — 운영 기본 형상(dev/stg/prd): authoring.control-notify.enabled=false 라
        //         ControlNotifyService/ControlNotifyMetrics 빈이 없어 null 로 주입된다.
        //         과거 이 결합 때문에 승인 후 수정의 export 재생성이 운영에서 전혀 돌지 않았다(HIGH-E).
        FakeControlNotifyDebounceStore offStore = new FakeControlNotifyDebounceStore();
        ControlNotifyDebouncer notifyOff = new ControlNotifyDebouncer(offStore, null, 60L, null, exportRunner,
                false, 10_000L, 300L, 100, true);
        notifyOff.accumulate(new TaskModifiedEvent(700L, 1L, ChangeType.LABEL_UPDATED, 10L, true));
        offStore.expire(700L);

        // when
        notifyOff.flushExpiredWindows();

        // then — 통지 토글이 꺼져 있어도 export 전량 재생성(force=true)은 그대로 위임된다.
        verify(exportRunner).runReExportThenNotify(eq(700L), eq(true), any());
        assertThat(offStore.totalRows()).as("재생성 위임까지 마친 윈도우는 제거된다").isZero();
    }

    @Test
    @DisplayName("통지는_export_성공_후에_발송된다")
    void notificationIsSentOnlyAfterExportCompletes() {
        // given — 재생성 동반 수정. export 가 @Async 라 통지가 앞서면 관제가 <구 버전 폴더>를 픽업한다.
        debouncer.accumulate(new TaskModifiedEvent(800L, 3L, ChangeType.LABEL_UPDATED, 10L, true));
        expire(800L);

        // when
        debouncer.flushExpiredWindows();

        // then — flush 시점에는 통지가 나가지 않고, 재산출 러너에 통지 콜백만 넘긴다.
        verify(notifyService, never()).sendModified(any(), anyList(), anySet(), anyBoolean());
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(exportRunner).runReExportThenNotify(eq(800L), eq(true), callback.capture());

        // and — 러너가 export 성공 후 실행하는 콜백에서 비로소 통지가 나간다.
        callback.getValue().run();
        verify(notifyService).sendModified(eq(800L), anyList(), anySet(), eq(true));
    }

    @Test
    @DisplayName("flush_실패로_남은_윈도우는_임차_만료_후_재클레임되어_다시_발송된다")
    void failedWindowIsRetriedAfterLeaseExpiry() {
        // given — 첫 flush 가 예외로 실패해 윈도우가 FLUSHING 으로 남는다(축적분 유실 아님).
        debouncer.accumulate(new TaskModifiedEvent(900L, 1L, ChangeType.LABEL_UPDATED, 10L));
        expire(900L);
        doThrow(new IllegalStateException("queue full"))
                .when(notifyService).sendModified(eq(900L), anyList(), anySet(), anyBoolean());
        debouncer.flushExpiredWindows();
        assertThat(store.flushingRawSns()).containsExactly(900L);

        // when — 임차가 만료되고(클레임 노드 사망 재현) 전송이 회복된 뒤 다시 flush 한다.
        store.expireLease(900L, java.time.Duration.ofMinutes(10));
        org.mockito.Mockito.doNothing()
                .when(notifyService).sendModified(eq(900L), anyList(), anySet(), anyBoolean());
        debouncer.flushExpiredWindows();

        // then — 재클레임되어 통지가 실제로 나가고 윈도우가 정리된다.
        verify(notifyService, times(2)).sendModified(eq(900L), anyList(), anySet(), anyBoolean());
        assertThat(store.totalRows()).isZero();
    }

    // --- Phase 5: 메트릭 호출 검증 ---

    @Test
    @DisplayName("flushExpiredWindows_실행시_metrics_debounceFlush_호출됨")
    void flushExpiredWindows_incrementsDebounceFlushMetric() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));
        debouncer.accumulate(new TaskModifiedEvent(200L, 2L, ChangeType.META_UPDATED, 10L));
        expire(100L);
        expire(200L);

        // when
        debouncer.flushExpiredWindows();

        // then
        verify(metrics, times(2)).incrementDebounceFlush();
    }

    @Test
    @DisplayName("flushExpiredWindows_미만료시_metrics_debounceFlush_미호출")
    void flushExpiredWindows_noExpired_noMetric() {
        // given
        debouncer.accumulate(new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L));

        // when
        debouncer.flushExpiredWindows();

        // then
        verify(metrics, never()).incrementDebounceFlush();
    }

    // --- 헬퍼 ---

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<FrameChangeSet>> frameChangeCaptor() {
        return ArgumentCaptor.forClass((Class<List<FrameChangeSet>>) (Class<?>) List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Set<String>> videoLevelCaptor() {
        return ArgumentCaptor.forClass((Class<Set<String>>) (Class<?>) Set.class);
    }

    private static FrameChangeSet find(List<FrameChangeSet> changes, Long srcSn) {
        return changes.stream()
                .filter(c -> srcSn.equals(c.srcSn()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("srcSn=" + srcSn + " 변경이 없습니다"));
    }
}
