package kr.co.cudo.authoring.dataset.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DatasetExportPendingSweeper} 단위 테스트(Mockito) — cutoff 계산·건수 반환 계약 검증.
 */
class DatasetExportPendingSweeperTest {

    private DatasetExportPendingSweeper newSweeper(DatasetExportTxService txService, int staleMinutes) throws Exception {
        DatasetExportPendingSweeper sweeper = new DatasetExportPendingSweeper(txService);
        Field f = DatasetExportPendingSweeper.class.getDeclaredField("staleMinutes");
        f.setAccessible(true);
        f.set(sweeper, staleMinutes);
        return sweeper;
    }

    @Test
    @DisplayName("sweep는_now에서_staleMinutes를_뺀_cutoff로_txService를_호출하고_건수를_반환한다")
    void sweepPassesCutoffAndReturnsCount() throws Exception {
        // given — staleMinutes=30, txService 가 4건 회수했다고 스텁
        DatasetExportTxService txService = mock(DatasetExportTxService.class);
        when(txService.sweepStalePending(any())).thenReturn(4);
        DatasetExportPendingSweeper sweeper = newSweeper(txService, 30);
        LocalDateTime before = LocalDateTime.now().minusMinutes(30);

        // when
        int swept = sweeper.sweep();
        LocalDateTime after = LocalDateTime.now().minusMinutes(30);

        // then — 반환 건수 전달 + cutoff 가 now-30m 근방(시간 허용오차)
        assertThat(swept).isEqualTo(4);
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(txService).sweepStalePending(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        assertThat(cutoff).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }

    @Test
    @DisplayName("staleMinutes_설정값이_cutoff에_반영된다 — 45분 설정 시 폴백 없이 cutoff는 now-45m 근방")
    void staleMinutesConfigReflectedInCutoff() throws Exception {
        // given — 정상값 staleMinutes=45
        DatasetExportTxService txService = mock(DatasetExportTxService.class);
        when(txService.sweepStalePending(any())).thenReturn(0);
        DatasetExportPendingSweeper sweeper = newSweeper(txService, 45);
        LocalDateTime before = LocalDateTime.now().minusMinutes(45);

        // when
        sweeper.sweep();
        LocalDateTime after = LocalDateTime.now().minusMinutes(45);

        // then — 폴백 안 하고 now-45m 근방
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(txService).sweepStalePending(captor.capture());
        assertThat(captor.getValue()).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }

    @Test
    @DisplayName("staleMinutes가_0이면_안전기본값30으로_폴백한다 — 진행 중 정상 PENDING 무차별 FAILED 방지")
    void zeroStaleMinutesFallsBackTo30() throws Exception {
        // given — 오설정 staleMinutes=0 (cutoff=now 가 되어 진행 중 PENDING 오분류 위험)
        DatasetExportTxService txService = mock(DatasetExportTxService.class);
        when(txService.sweepStalePending(any())).thenReturn(0);
        DatasetExportPendingSweeper sweeper = newSweeper(txService, 0);
        LocalDateTime before = LocalDateTime.now().minusMinutes(30);

        // when
        sweeper.sweep();
        LocalDateTime after = LocalDateTime.now().minusMinutes(30);

        // then — cutoff 가 now(폴백 전)가 아니라 now-30m 근방 + sweepStalePending 호출됨
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(txService).sweepStalePending(captor.capture());
        assertThat(captor.getValue()).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }

    @Test
    @DisplayName("staleMinutes가_음수여도_안전기본값30으로_폴백한다")
    void negativeStaleMinutesFallsBackTo30() throws Exception {
        // given — 오설정 staleMinutes=-5 (cutoff=미래가 되어 정상 PENDING 까지 회수 위험)
        DatasetExportTxService txService = mock(DatasetExportTxService.class);
        when(txService.sweepStalePending(any())).thenReturn(0);
        DatasetExportPendingSweeper sweeper = newSweeper(txService, -5);
        LocalDateTime before = LocalDateTime.now().minusMinutes(30);

        // when
        sweeper.sweep();
        LocalDateTime after = LocalDateTime.now().minusMinutes(30);

        // then — 미래가 아니라 now-30m 근방으로 폴백
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(txService).sweepStalePending(captor.capture());
        assertThat(captor.getValue()).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }
}
