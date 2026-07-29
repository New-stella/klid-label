package kr.co.cudo.authoring.controlnotify.debounce;

import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyDebouncer;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import kr.co.cudo.authoring.controlnotify.service.FrameChangeSet;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 9-C — 관제 수정 통지 디바운스의 <b>크로스노드 정합</b> 통합 테스트
 * (실 DB, PostgreSQL Testcontainer).
 *
 * <p>배경: 배포 토폴로지는 2노드 Active-Active 인데 디바운스 윈도우가 JVM 로컬
 * {@code ConcurrentHashMap} 이었다. 그래서 같은 영상의 수정이 양 노드에 나뉘어 축적되면 각자 자기
 * 몫만 flush 해 <b>export 재생성·관제 통지가 2회</b> 나갔고(관제가 같은 영상을 두 버전으로 픽업),
 * 노드가 flush 전에 죽으면 <b>축적분이 통째로 유실</b>됐다.
 *
 * <p>두 "노드"는 <b>같은 DB 저장소를 공유하는 서로 다른 {@link ControlNotifyDebouncer} 인스턴스</b>로
 * 재현한다(각자 자기 통지 서비스 목을 갖는다 — 어느 쪽이 보냈는지 구분 가능). 전용 flush 스케줄러는
 * 꺼서(false) tick 이 아니라 명시 호출로만 flush 되게 한다(결정성).
 */
@SpringBootTest
@ActiveProfiles("local")
class ControlNotifyDebounceCrossNodeIT {

    /** 윈도우 만료를 기다리지 않도록 windowSec=0 (축적 즉시 flush 대상). */
    private static final long IMMEDIATE_WINDOW_SEC = 0L;
    /** 임차 300초 — 정상 발송 중인 윈도우를 다른 노드가 뺏지 않는 운영 기본값. */
    private static final long LEASE_SEC = 300L;

    @Autowired
    private ControlNotifyDebounceStore store;

    @Autowired
    private LsMonNotiAcmlRepository repository;

    /** 이 테스트가 쓰는 rawSn — 다른 테스트/잔여 행과 섞이지 않도록 유일값을 쓴다. */
    private final AtomicLong rawSnSeq = new AtomicLong(System.nanoTime());

    private final List<Long> usedRawSns = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        usedRawSns.forEach(rawSn -> repository.findByRawSn(rawSn).forEach(repository::delete));
        usedRawSns.clear();
    }

    private Long nextRawSn() {
        Long rawSn = rawSnSeq.incrementAndGet();
        usedRawSns.add(rawSn);
        return rawSn;
    }

    /** "노드" 1대 — 공유 DB 저장소 + 자기 통지 서비스/재산출 러너 목. */
    private ControlNotifyDebouncer node(ControlNotifyService notifyService, AsyncDatasetExportRunner runner) {
        return new ControlNotifyDebouncer(store, notifyService, IMMEDIATE_WINDOW_SEC, null, runner,
                false, 10_000L, LEASE_SEC, 100);
    }

    @Test
    @DisplayName("2노드에_축적된_디바운스가_한_번만_flush_된다")
    void windowsAccumulatedOnTwoNodesFlushExactlyOnce() throws Exception {
        // given — 같은 영상의 수정이 양 노드에 나뉘어 축적된다(노드 A: 프레임1, 노드 B: 프레임2 + 영상단위).
        Long rawSn = nextRawSn();
        ControlNotifyService notifyA = mock(ControlNotifyService.class);
        ControlNotifyService notifyB = mock(ControlNotifyService.class);
        ControlNotifyDebouncer nodeA = node(notifyA, mock(AsyncDatasetExportRunner.class));
        ControlNotifyDebouncer nodeB = node(notifyB, mock(AsyncDatasetExportRunner.class));

        nodeA.accumulate(new TaskModifiedEvent(rawSn, 1L, ChangeType.LABEL_UPDATED, 10L));
        nodeB.accumulate(new TaskModifiedEvent(rawSn, 2L, ChangeType.LABEL_ADDED, 10L));
        nodeB.accumulate(new TaskModifiedEvent(rawSn, null, ChangeType.META_UPDATED, 10L));

        // when — 두 노드가 거의 동시에 flush 한다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                start.await();
                nodeA.flushExpiredWindows();
                return null;
            });
            var b = pool.submit(() -> {
                start.await();
                nodeB.flushExpiredWindows();
                return null;
            });
            start.countDown();
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // then — 통지는 정확히 1회. 인메모리 윈도우였다면 양쪽이 각자 자기 몫을 보내 2회가 된다.
        int sentByA = countSent(notifyA, rawSn);
        int sentByB = countSent(notifyB, rawSn);
        assertThat(sentByA + sentByB).as("2노드가 각자 flush 하면 관제가 같은 영상을 두 번 픽업한다").isEqualTo(1);

        // and — 보낸 쪽 페이로드에 <양 노드의 축적분이 모두> 실린다(어느 한쪽 변경이 누락되면 안 된다).
        ControlNotifyService sender = sentByA == 1 ? notifyA : notifyB;
        ArgumentCaptor<List<FrameChangeSet>> frames = frameChangeCaptor();
        ArgumentCaptor<Set<String>> videoLevel = videoLevelCaptor();
        verify(sender).sendModified(eq(rawSn), frames.capture(), videoLevel.capture(), anyBoolean());
        assertThat(frames.getValue()).extracting(FrameChangeSet::srcSn).containsExactlyInAnyOrder(1L, 2L);
        assertThat(videoLevel.getValue()).containsExactly(ChangeType.META_UPDATED);

        // and — 발송이 끝난 윈도우는 저장소에서 사라진다.
        assertThat(rowCountOf(rawSn)).isZero();
    }

    @Test
    @DisplayName("flush_전_노드가_죽어도_축적분이_유실되지_않는다")
    void accumulationSurvivesNodeDeathBeforeFlush() {
        // given — 노드 A 가 수정을 축적한 직후 죽는다(flush 없이 인스턴스를 버린다).
        //         인메모리 윈도우였다면 이 시점에 축적분이 통째로 사라졌다.
        Long rawSn = nextRawSn();
        ControlNotifyService notifyA = mock(ControlNotifyService.class);
        ControlNotifyDebouncer deadNode = node(notifyA, mock(AsyncDatasetExportRunner.class));
        deadNode.accumulate(new TaskModifiedEvent(rawSn, 7L, ChangeType.LABEL_UPDATED, 10L));
        deadNode.accumulate(new TaskModifiedEvent(rawSn, null, ChangeType.META_UPDATED, 10L));

        // 축적분이 공유 DB 에 남아 있다(노드 사망과 무관).
        assertThat(rowCountOf(rawSn)).isEqualTo(1);

        // when — 살아남은 노드 B 가 flush 한다.
        ControlNotifyService notifyB = mock(ControlNotifyService.class);
        ControlNotifyDebouncer survivor = node(notifyB, mock(AsyncDatasetExportRunner.class));
        survivor.flushExpiredWindows();

        // then — 죽은 노드에 쌓였던 변경이 그대로 통지된다(유실 0).
        verify(notifyA, never()).sendModified(any(), anyList(), anySet(), anyBoolean());
        ArgumentCaptor<List<FrameChangeSet>> frames = frameChangeCaptor();
        ArgumentCaptor<Set<String>> videoLevel = videoLevelCaptor();
        verify(notifyB).sendModified(eq(rawSn), frames.capture(), videoLevel.capture(), eq(false));
        assertThat(frames.getValue()).extracting(FrameChangeSet::srcSn).containsExactly(7L);
        assertThat(videoLevel.getValue()).containsExactly(ChangeType.META_UPDATED);
        assertThat(rowCountOf(rawSn)).isZero();
    }

    @Test
    @DisplayName("재export_트리거는_control_notify_토글과_무관하게_동작한다 — 실DB 경로")
    void reExportTriggerIsIndependentOfControlNotifyToggleOnRealStore() {
        // given — 운영 기본 형상(dev/stg/prd): 통지 토글 off → ControlNotifyService 가 null 로 주입된다.
        Long rawSn = nextRawSn();
        AsyncDatasetExportRunner runner = mock(AsyncDatasetExportRunner.class);
        ControlNotifyDebouncer notifyOff = node(null, runner);
        notifyOff.accumulate(new TaskModifiedEvent(rawSn, 3L, ChangeType.LABEL_UPDATED, 10L, true));

        // when
        notifyOff.flushExpiredWindows();

        // then — 통지가 꺼져 있어도 export 전량 재생성은 위임된다(데이터마트 동기화 요구는 토글 무관).
        verify(runner).runReExportThenNotify(eq(rawSn), eq(true), any());
        assertThat(rowCountOf(rawSn)).isZero();
    }

    @Test
    @DisplayName("통지는_export_성공_후에_발송된다 — 실DB 경로")
    void notificationIsSentOnlyAfterExportCompletesOnRealStore() {
        // given — 재생성 동반 수정. export 가 @Async 라 통지가 앞서면 관제가 구 버전 폴더를 픽업한다.
        Long rawSn = nextRawSn();
        ControlNotifyService notifyService = mock(ControlNotifyService.class);
        AsyncDatasetExportRunner runner = mock(AsyncDatasetExportRunner.class);
        ControlNotifyDebouncer node = node(notifyService, runner);
        node.accumulate(new TaskModifiedEvent(rawSn, 4L, ChangeType.LABEL_UPDATED, 10L, true));

        // when
        node.flushExpiredWindows();

        // then — flush 시점에는 통지 없음. 러너가 export 를 마친 뒤 실행하는 콜백에서만 통지가 나간다.
        verify(notifyService, never()).sendModified(any(), anyList(), anySet(), anyBoolean());
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(runner).runReExportThenNotify(eq(rawSn), eq(true), callback.capture());
        callback.getValue().run();
        verify(notifyService).sendModified(eq(rawSn), anyList(), anySet(), eq(true));
    }

    /** 저장소에 남아 있는 해당 영상의 윈도우 행 수. */
    private long rowCountOf(Long rawSn) {
        return repository.findByRawSn(rawSn).size();
    }

    /** 해당 영상으로 실제 발송된 횟수(0/1/2 를 그대로 센다 — 이중 전송도 드러나야 한다). */
    private int countSent(ControlNotifyService notifyService, Long rawSn) {
        return (int) org.mockito.Mockito.mockingDetails(notifyService).getInvocations().stream()
                .filter(inv -> "sendModified".equals(inv.getMethod().getName()))
                .filter(inv -> rawSn.equals(inv.getArgument(0)))
                .count();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<FrameChangeSet>> frameChangeCaptor() {
        return ArgumentCaptor.forClass((Class<List<FrameChangeSet>>) (Class<?>) List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Set<String>> videoLevelCaptor() {
        return ArgumentCaptor.forClass((Class<Set<String>>) (Class<?>) Set.class);
    }
}
