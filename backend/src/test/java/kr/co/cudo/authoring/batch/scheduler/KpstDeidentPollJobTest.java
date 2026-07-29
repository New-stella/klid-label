package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.service.KpstDeidentTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / UC018 — KpstDeidentPollJob 단위 테스트.
 *
 * <p>DB 조회 기반 폴링 재개·건별 격리·빈 리스트 no-op + Phase 9-B 원자 클레임/틱 상한 검증.
 */
class KpstDeidentPollJobTest {

    private LsDeidentProcLogRepository procLogRepository;
    private KpstDeidentService kpstDeidentService;
    private KpstDeidentTxService kpstDeidentTxService;
    private KpstDeidentPollJob job;

    @BeforeEach
    void setUp() throws Exception {
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        kpstDeidentService = mock(KpstDeidentService.class);
        kpstDeidentTxService = mock(KpstDeidentTxService.class);
        job = new KpstDeidentPollJob();
        setField(job, "procLogRepository", procLogRepository);
        setField(job, "kpstDeidentService", kpstDeidentService);
        setField(job, "kpstDeidentTxService", kpstDeidentTxService);
        setField(job, "pollIntervalSec", 30);
        setField(job, "pollBatchSize", 200);
        // 기본은 클레임 성공(단일 노드 동작 = 기존 동작 유지).
        when(kpstDeidentTxService.tryClaimPoll(anyLong(), any(LocalDateTime.class))).thenReturn(true);
    }

    private LsDeidentProcLog procLog(long rawSn) {
        LsDeidentProcLog log = LsDeidentProcLog.request(rawSn, null, "/raw/x.mp4", "batch");
        setField(log, "procLogSn", rawSn);
        log.markKpstSubmitted(rawSn + 100, null);
        return log;
    }

    @Test
    @DisplayName("WAITING_POLLING_상태를_DB조회로_폴링재개한다")
    void pollsWaitingAndPollingFromDb() {
        LsDeidentProcLog a = procLog(1L);
        LsDeidentProcLog b = procLog(2L);
        when(procLogRepository.findByPollSttsCdIn(anyList(), any(Pageable.class))).thenReturn(List.of(a, b));

        job.execute(null);

        // findByPollSttsCdIn 대상이 WAITING/POLLING 임을 검증
        verify(procLogRepository).findByPollSttsCdIn(
                eq(List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING)),
                any(Pageable.class));
        verify(kpstDeidentService).pollOne(a);
        verify(kpstDeidentService).pollOne(b);
    }

    @Test
    @DisplayName("대상이_없으면_외부호출없이_즉시종료한다")
    void emptyTargetsNoOp() {
        when(procLogRepository.findByPollSttsCdIn(anyList(), any(Pageable.class))).thenReturn(List.of());

        job.execute(null);

        verify(kpstDeidentService, never()).pollOne(any());
    }

    @Test
    @DisplayName("한_작업_폴링실패가_다른_작업을_막지_않는다")
    void perClipFailureIsolated() {
        LsDeidentProcLog a = procLog(1L);
        LsDeidentProcLog b = procLog(2L);
        when(procLogRepository.findByPollSttsCdIn(anyList(), any(Pageable.class))).thenReturn(List.of(a, b));
        // 첫 건은 예외, 둘째 건은 정상
        doThrow(new RuntimeException("boom")).when(kpstDeidentService).pollOne(a);
        doNothing().when(kpstDeidentService).pollOne(b);

        job.execute(null);

        // a 가 실패해도 b 는 폴링됨
        verify(kpstDeidentService, times(1)).pollOne(a);
        verify(kpstDeidentService, times(1)).pollOne(b);
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("폴링_조회에_상한이_적용된다 — 무제한 조회 금지")
    void pollQueryIsBounded() {
        when(procLogRepository.findByPollSttsCdIn(anyList(), any(Pageable.class))).thenReturn(List.of());

        job.execute(null);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(procLogRepository).findByPollSttsCdIn(anyList(), page.capture());
        assertThat(page.getValue().isPaged()).isTrue();
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
        assertThat(page.getValue().getSort().isSorted())
                .as("기아 방지 — 오래 대기한 건 우선 정렬").isTrue();
    }

    @Test
    @DisplayName("클레임에_실패한_건은_폴링하지_않는다 — 2노드 중복 폴링 차단")
    void skipsTargetsClaimedByAnotherNode() {
        LsDeidentProcLog mine = procLog(1L);
        LsDeidentProcLog others = procLog(2L);
        when(procLogRepository.findByPollSttsCdIn(anyList(), any(Pageable.class)))
                .thenReturn(List.of(mine, others));
        when(kpstDeidentTxService.tryClaimPoll(eq(1L), any(LocalDateTime.class))).thenReturn(true);
        when(kpstDeidentTxService.tryClaimPoll(eq(2L), any(LocalDateTime.class))).thenReturn(false);

        job.execute(null);

        verify(kpstDeidentService).pollOne(mine);
        verify(kpstDeidentService, never()).pollOne(others);
    }

    @Test
    @DisplayName("클레임_리스는_폴링주기보다_짧다 — 단일노드가_자기_리스에_막히지_않는다")
    void claimLeaseIsShorterThanPollInterval() {
        LsDeidentProcLog a = procLog(1L);
        when(procLogRepository.findByPollSttsCdIn(anyList(), any(Pageable.class))).thenReturn(List.of(a));

        LocalDateTime before = LocalDateTime.now();
        job.execute(null);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(kpstDeidentTxService).tryClaimPoll(eq(1L), cutoff.capture());
        // cutoff = now - lease. lease < pollIntervalSec(30) 이어야 다음 틱에서 다시 클레임된다.
        assertThat(cutoff.getValue()).isAfter(before.minusSeconds(30));
        assertThat(cutoff.getValue()).isBefore(before);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
