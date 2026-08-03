package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G-ISSUE-02 — YOLO 단계가 mock 응답으로 중단되면 <b>후속 단계가 강행되지 않는다</b>.
 *
 * <p>YOLO 가 SAM2 로 넘기는 것은 BBOX 힌트다. YOLO 가 실패했는데 SAM2/INTERPOLATE 가 계속 돌면
 * <b>힌트 0건</b>으로 세그멘테이션·보간이 수행되어, "모델이 없어서 라벨이 없는 것"과
 * "정말 객체가 없는 것"이 구분되지 않는 산출물이 남는다. 본 테스트는 예외 전파만으로
 * 파이프라인이 중단되는지(별도 배선이 필요한지)를 고정한다.
 */
class YoloMockGatePipelineAbortTest {

    /**
     * 실행 여부를 기록하는 최소 단계 구현 — Mockito 기본값(isEnabled=false)으로 인한 위양성 방지.
     *
     * <p><b>static 으로 만들지 말 것</b>: {@code BatchStepTransactionBoundaryTest} 는
     * {@code kr.co.cudo.authoring} 전체를 classpath 스캔해 <b>모든</b> {@link BatchStep} 구현에
     * {@code execute} 트랜잭션 경계를 요구한다. static 중첩 클래스는 스캐너가 "독립 구성요소"로 보아
     * 이 테스트 더미까지 검사 대상이 되어 아키텍처 가드가 깨진다(비-static 내부 클래스·익명 클래스는
     * 독립이 아니라 제외된다).
     */
    private final class RecordingStep implements BatchStep {
        private final BatchStage stage;
        private final List<BatchStage> executed;

        private RecordingStep(BatchStage stage, List<BatchStage> executed) {
            this.stage = stage;
            this.executed = executed;
        }

        @Override
        public BatchStage stage() {
            return stage;
        }

        @Override
        public void execute(BatchContext ctx) {
            executed.add(stage);
        }
    }

    @Test
    @DisplayName("YOLO_스텝_FAILED시_후속_SAM2_INTERPOLATE_스텝이_강행되지_않는다")
    void yoloFailureAbortsRemainingSteps() {
        // given — YOLO 가 배포환경 mock 차단으로 던지는 예외를 그대로 재현
        List<BatchStage> executed = new ArrayList<>();
        BatchStep yolo = new BatchStep() {
            @Override
            public BatchStage stage() {
                return BatchStage.YOLO;
            }

            @Override
            public void execute(BatchContext ctx) {
                executed.add(BatchStage.YOLO);
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "YOLOX 가중치 미배포(mockReason=weights_missing) — 배치 중단");
            }
        };
        BatchStep sam2 = new RecordingStep(BatchStage.SAM2, executed);
        BatchStep interp = new RecordingStep(BatchStage.INTERPOLATE, executed);

        BatchStatusService statusService = mock(BatchStatusService.class);
        BatchTransitionService transitionService = mock(BatchTransitionService.class);
        BatchRetryQueue retryQueue = mock(BatchRetryQueue.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        when(videoRepository.findById(9L)).thenReturn(Optional.of(mock(LsDataRaw.class)));
        when(transitionService.markRawDataProcessingBlocked(9L)).thenReturn(false);

        BatchOrchestrator orchestrator = new BatchOrchestrator(
                new BatchPipeline(List.of(yolo, sam2, interp)),
                statusService, transitionService, retryQueue, videoRepository);

        // when
        BatchStage result = orchestrator.process(9L);

        // then — YOLO 만 실행되고 파이프라인이 즉시 중단된다.
        assertThat(result).isEqualTo(BatchStage.FAILED);
        assertThat(executed).containsExactly(BatchStage.YOLO);
        verify(statusService).markFailed(eq(9L), any(RuntimeException.class));
        verify(transitionService).markRawDataFailed(9L);
        verify(statusService, org.mockito.Mockito.never()).markCompleted(any());
    }
}
