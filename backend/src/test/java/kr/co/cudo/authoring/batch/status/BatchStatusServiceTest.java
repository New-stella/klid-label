package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.dto.BatchStageProgress;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchStatusServiceTest {

    @Mock
    private LsBatchProcLogRepository repository;

    @InjectMocks
    private BatchStatusService svc;

    // ──────────────────────────────────────────────
    // markStage
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("markStage_신규_rawSn이면_create_후_save")
    void markStage_신규_rawSn이면_create_후_save() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        svc.markStage(1L, BatchStage.YOLO);

        verify(repository).findById(1L);
        verify(repository).save(any(LsBatchProcLog.class));
    }

    @Test
    @DisplayName("markStage_기존_rawSn이면_updateStage_후_save")
    void markStage_기존_rawSn이면_updateStage_후_save() {
        LsBatchProcLog existing = LsBatchProcLog.create(2L, BatchStage.FRAME_EXTRACT);
        when(repository.findById(2L)).thenReturn(Optional.of(existing));

        svc.markStage(2L, BatchStage.YOLO);

        assertThat(existing.getStageCd()).isEqualTo(BatchStage.YOLO.name());
        verify(repository).save(existing);
    }

    // ──────────────────────────────────────────────
    // markFailed
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("markFailed_기존_행이면_retryCnt_1_증가")
    void markFailed_기존_행이면_retryCnt_1_증가() {
        LsBatchProcLog existing = LsBatchProcLog.create(3L, BatchStage.YOLO);
        when(repository.findById(3L)).thenReturn(Optional.of(existing));

        svc.markFailed(3L, new IllegalStateException("boom"));

        assertThat(existing.getStageCd()).isEqualTo(BatchStage.FAILED.name());
        assertThat(existing.getRetryCnt()).isEqualTo(1);
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("markFailed_신규_행이면_retryCnt_0_유지")
    void markFailed_신규_행이면_retryCnt_0_유지() {
        when(repository.findById(4L)).thenReturn(Optional.empty());

        svc.markFailed(4L, new RuntimeException("new failure"));

        verify(repository).save(argThat(log ->
                log.getStageCd().equals(BatchStage.FAILED.name()) &&
                log.getRetryCnt() == 0
        ));
    }

    // ──────────────────────────────────────────────
    // currentStage
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("currentStage_DB없으면_PENDING_반환")
    void currentStage_DB없으면_PENDING_반환() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        BatchStage result = svc.currentStage(99L);

        assertThat(result).isEqualTo(BatchStage.PENDING);
    }

    // ──────────────────────────────────────────────
    // recent
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("recent_limit_적용_확인")
    void recent_limit_적용_확인() {
        LsBatchProcLog log1 = LsBatchProcLog.create(10L, BatchStage.YOLO);
        LsBatchProcLog log2 = LsBatchProcLog.create(11L, BatchStage.SAM2);
        LsBatchProcLog log3 = LsBatchProcLog.create(12L, BatchStage.COMPLETED);
        when(repository.findTop100ByOrderByUpdatedAtDesc()).thenReturn(List.of(log1, log2, log3));

        List<BatchStageProgress> result = svc.recent(2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).rawSn()).isEqualTo(10L);
        assertThat(result.get(1).rawSn()).isEqualTo(11L);
    }

    // ──────────────────────────────────────────────
    // null 방어
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("rawSn_null이면_markStage_무시")
    void rawSn_null이면_markStage_무시() {
        svc.markStage(null, BatchStage.YOLO);

        verify(repository, never()).findById(any());
        verify(repository, never()).save(any());
    }
}
