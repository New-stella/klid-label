package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AsyncDeidentifyRunner 단위 테스트 (Phase 2 — 적재 직후 선두 비식별).
 *
 * <p>검증:
 * <ul>
 *   <li>적재 시 비식별을 무조건 실행 (ANONY 포함 — 게이팅 폐지).</li>
 *   <li>비식별 성공 시 LS_DATA_RAW → MARKING_READY 전이.</li>
 *   <li>비식별 실패 시 MARKING_READY 미전이 + 재시도 큐 미사용 (예외 삼킴).</li>
 *   <li>raw 미존재 시 무처리(WARN return).</li>
 * </ul>
 */
class AsyncDeidentifyRunnerTest {

    private VideoRepository videoRepository;
    private BatchTransitionService batchTransitionService;
    private BatchStep deidStep;
    private BatchPipeline preMarkingPipeline;
    private AsyncDeidentifyRunner runner;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        batchTransitionService = mock(BatchTransitionService.class);
        deidStep = mock(BatchStep.class);
        when(deidStep.stage()).thenReturn(BatchStage.DEIDENTIFY);
        preMarkingPipeline = new BatchPipeline(List.of(deidStep));
        runner = new AsyncDeidentifyRunner(preMarkingPipeline, batchTransitionService, videoRepository);
    }

    private LsDataRaw raw(String prvcTypeCd) {
        return LsDataRaw.createFromIngest(
                "clip", "cctv-1", "EVT", "GOV", prvcTypeCd, "raw/path.mp4", null, 60);
    }

    @Test
    @DisplayName("적재시_비식별_무조건_실행 — ANONY 도 비식별 단계 실행")
    void anonyAlsoDeidentified() {
        when(videoRepository.findById(1L)).thenReturn(Optional.of(raw(LsDataRaw.PRVC_TYPE_ANONY)));

        runner.runAsync(1L);

        verify(deidStep).execute(any());
    }

    @Test
    @DisplayName("비식별_성공시_MARKING_READY_전이")
    void successTransitionsMarkingReady() {
        when(videoRepository.findById(2L)).thenReturn(Optional.of(raw(LsDataRaw.PRVC_TYPE_PRVC)));

        runner.runAsync(2L);

        verify(deidStep).execute(any());
        verify(batchTransitionService).markRawDataMarkingReady(eq(2L));
    }

    @Test
    @DisplayName("비식별_실패시_MARKING_READY_미전이_그리고_재시도큐_미사용")
    void failureNoMarkingReadyNoRetryQueue() {
        when(videoRepository.findById(3L)).thenReturn(Optional.of(raw(LsDataRaw.PRVC_TYPE_PRVC)));
        doThrow(new RuntimeException("deident 5xx")).when(deidStep).execute(any());

        // 예외를 삼켜야 함 (@Async) — 호출이 throw 하지 않음
        runner.runAsync(3L);

        verify(batchTransitionService, never()).markRawDataMarkingReady(any());
        // AsyncDeidentifyRunner 는 재시도 큐(BatchRetryQueue) 의존성을 갖지 않는다 — 생성자 미주입으로 구조적 차단.
    }

    @Test
    @DisplayName("raw_없으면_무처리")
    void rawNotFoundNoOp() {
        when(videoRepository.findById(99L)).thenReturn(Optional.empty());

        runner.runAsync(99L);

        verify(deidStep, never()).execute(any());
        verify(batchTransitionService, never()).markRawDataMarkingReady(any());
    }
}
