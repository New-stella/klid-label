package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 — BatchStatusService 회귀 보호.
 * V12 LS_BATCH_PROC_LOG 신스키마 기준 (BATCH_PROC_LOG_SN PK, JOB_ID, PROC_STEP_CD 등).
 */
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
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(1L, "SKIPPED")).thenReturn(Optional.empty());

        svc.markStage(1L, BatchStage.YOLO);

        verify(repository).findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(1L, "SKIPPED");
        verify(repository).save(argThat(log ->
                log.getRawSn().equals(1L) &&
                log.getStageCd().equals(BatchStage.YOLO.name())
        ));
    }

    @Test
    @DisplayName("markStage_기존_rawSn이면_updateStage_후_save")
    void markStage_기존_rawSn이면_updateStage_후_save() {
        LsBatchProcLog existing = LsBatchProcLog.create(2L, BatchStage.FRAME_EXTRACT);
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(2L, "SKIPPED")).thenReturn(Optional.of(existing));

        svc.markStage(2L, BatchStage.YOLO);

        assertThat(existing.getStageCd()).isEqualTo(BatchStage.YOLO.name());
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("markCompleted_COMPLETED_단계로_갱신")
    void markCompleted_COMPLETED_단계로_갱신() {
        LsBatchProcLog existing = LsBatchProcLog.create(3L, BatchStage.SAM2);
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(3L, "SKIPPED")).thenReturn(Optional.of(existing));

        svc.markCompleted(3L);

        assertThat(existing.getStageCd()).isEqualTo(BatchStage.COMPLETED.name());
        verify(repository).save(existing);
    }

    // ──────────────────────────────────────────────
    // markFailed
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("markFailed_기존_행이면_retryCnt_1_증가")
    void markFailed_기존_행이면_retryCnt_1_증가() {
        LsBatchProcLog existing = LsBatchProcLog.create(4L, BatchStage.YOLO);
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(4L, "SKIPPED")).thenReturn(Optional.of(existing));

        svc.markFailed(4L, new IllegalStateException("boom"));

        // 실패 단계(procStepCd)는 보존, 실패 여부는 procSttsCd='FAILED' 로 판정 (진행률 화면 표시용).
        assertThat(existing.getStageCd()).isEqualTo(BatchStage.YOLO.name());
        assertThat(existing.getProcSttsCd()).isEqualTo("FAILED");
        assertThat(existing.getRetryCnt()).isEqualTo(1);
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("markFailed_신규_행이면_retryCnt_0_유지")
    void markFailed_신규_행이면_retryCnt_0_유지() {
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(5L, "SKIPPED")).thenReturn(Optional.empty());

        svc.markFailed(5L, new RuntimeException("new failure"));

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
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(99L, "SKIPPED")).thenReturn(Optional.empty());

        BatchStage result = svc.currentStage(99L);

        assertThat(result).isEqualTo(BatchStage.PENDING);
    }

    @Test
    @DisplayName("currentStage_DB있으면_저장된_단계_반환")
    void currentStage_DB있으면_저장된_단계_반환() {
        LsBatchProcLog existing = LsBatchProcLog.create(6L, BatchStage.SAM2);
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(6L, "SKIPPED")).thenReturn(Optional.of(existing));

        BatchStage result = svc.currentStage(6L);

        assertThat(result).isEqualTo(BatchStage.SAM2);
    }

    // ──────────────────────────────────────────────
    // null 방어
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("rawSn_null이면_markStage_무시")
    void rawSn_null이면_markStage_무시() {
        svc.markStage(null, BatchStage.YOLO);

        verify(repository, never()).findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("rawSn_null이면_markFailed_무시")
    void rawSn_null이면_markFailed_무시() {
        svc.markFailed(null, new RuntimeException("boom"));

        verify(repository, never()).findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(any(), any());
        verify(repository, never()).save(any());
    }

    // ──────────────────────────────────────────────
    // recordVlmSkipped (B-ISSUE-24)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("recordVlmSkipped_는_VLM_SKIPPED_행을_사유와_함께_신규_적재한다")
    void recordVlmSkipped_appendsSkippedRow() {
        svc.recordVlmSkipped(7L, "vlm.client.enabled=false");

        // 진행 행을 갱신하는 것이 아니라 별도 감사 행을 적재해야 한다 — 갱신이면 다음 단계 전이가 덮어쓴다.
        verify(repository, never()).findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(any(), any());
        verify(repository).save(argThat(log ->
                log.getRawSn().equals(7L)
                        && log.getStageCd().equals(BatchStage.VLM.name())
                        && "SKIPPED".equals(log.getProcSttsCd())
                        && log.getErrMsg() != null
                        && log.getErrMsg().contains("vlm.client.enabled")
        ));
    }

    @Test
    @DisplayName("rawSn_null이면_recordVlmSkipped_무시")
    void recordVlmSkipped_nullRawSnIgnored() {
        svc.recordVlmSkipped(null, "reason");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("진행_상태_조회는_SKIPPED_감사행을_제외한다")
    void progressLookupExcludesSkippedRows() {
        // markStage/markFailed/currentStage/stagesFor 가 모두 SKIPPED 를 제외한 최신 행을 본다.
        when(repository.findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(8L, "SKIPPED"))
                .thenReturn(Optional.empty());

        svc.markStage(8L, BatchStage.FRAME_EXTRACT);

        verify(repository).findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(8L, "SKIPPED");
    }
}
