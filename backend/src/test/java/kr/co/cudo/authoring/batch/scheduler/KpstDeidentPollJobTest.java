package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
 * <p>DB 조회 기반 폴링 재개·건별 격리·빈 리스트 no-op 검증.
 */
class KpstDeidentPollJobTest {

    private LsDeidentProcLogRepository procLogRepository;
    private KpstDeidentService kpstDeidentService;
    private KpstDeidentPollJob job;

    @BeforeEach
    void setUp() throws Exception {
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        kpstDeidentService = mock(KpstDeidentService.class);
        job = new KpstDeidentPollJob();
        setField(job, "procLogRepository", procLogRepository);
        setField(job, "kpstDeidentService", kpstDeidentService);
    }

    private LsDeidentProcLog procLog(long rawSn) {
        LsDeidentProcLog log = LsDeidentProcLog.request(rawSn, null, "/raw/x.mp4", "batch");
        log.markKpstSubmitted(rawSn + 100, null);
        return log;
    }

    @Test
    @DisplayName("WAITING_POLLING_상태를_DB조회로_폴링재개한다")
    void pollsWaitingAndPollingFromDb() {
        LsDeidentProcLog a = procLog(1L);
        LsDeidentProcLog b = procLog(2L);
        when(procLogRepository.findByPollSttsCdIn(anyList())).thenReturn(List.of(a, b));

        job.execute(null);

        // findByPollSttsCdIn 대상이 WAITING/POLLING 임을 검증
        verify(procLogRepository).findByPollSttsCdIn(
                List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING));
        verify(kpstDeidentService).pollOne(a);
        verify(kpstDeidentService).pollOne(b);
    }

    @Test
    @DisplayName("대상이_없으면_외부호출없이_즉시종료한다")
    void emptyTargetsNoOp() {
        when(procLogRepository.findByPollSttsCdIn(anyList())).thenReturn(List.of());

        job.execute(null);

        verify(kpstDeidentService, never()).pollOne(any());
    }

    @Test
    @DisplayName("한_작업_폴링실패가_다른_작업을_막지_않는다")
    void perClipFailureIsolated() {
        LsDeidentProcLog a = procLog(1L);
        LsDeidentProcLog b = procLog(2L);
        when(procLogRepository.findByPollSttsCdIn(anyList())).thenReturn(List.of(a, b));
        // 첫 건은 예외, 둘째 건은 정상
        doThrow(new RuntimeException("boom")).when(kpstDeidentService).pollOne(a);
        doNothing().when(kpstDeidentService).pollOne(b);

        job.execute(null);

        // a 가 실패해도 b 는 폴링됨
        verify(kpstDeidentService, times(1)).pollOne(a);
        verify(kpstDeidentService, times(1)).pollOne(b);
        assertThat(true).isTrue();
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
