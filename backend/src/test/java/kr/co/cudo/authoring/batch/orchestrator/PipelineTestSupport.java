package kr.co.cudo.authoring.batch.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.pipeline.MarkingLoadStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;

import java.util.List;

/**
 * orchestrator 단위 테스트 공용 헬퍼 — mock typed-step 들을 실제 단계 순서대로 묶은
 * {@link BatchPipeline} 을 구성한다.
 *
 * <p>핵심: 각 단계 어댑터의 {@code execute(ctx)} 는 리팩토링 이전 orchestrator 가 수행하던
 * 책임(typed 메서드 호출 + ctx 읽기/쓰기 + FRAME_EXTRACT 가드)을 그대로 재현한다.
 * 따라서 기존 테스트의 {@code verify(yoloStep).run(...)} / {@code extractByMarks(...)} /
 * 힌트 전달 단언이 변경 없이 동작하며, 단계 실행 순서/예외 전파/상태 전이가 보존된다.
 *
 * <p>MARKING 단계는 실제 {@link MarkingLoadStep} (mock repo + 실 ObjectMapper) 를 사용해
 * marks 파싱 동작까지 실제 코드 경로로 검증한다.
 */
final class PipelineTestSupport {

    private PipelineTestSupport() {
    }

    static BatchPipeline pipeline(LsMarkingRepository markingRepository,
                                  VlmTimeseriesStep vlm,
                                  FfmpegFrameExtractor frame,
                                  YoloAutolabelStep yolo,
                                  Sam2SegmentStep sam2,
                                  TrackInterpolationStep interp) {
        MarkingLoadStep markingLoad = new MarkingLoadStep(markingRepository, new ObjectMapper());

        BatchStep vlmStep = adapter(BatchStage.VLM, ctx -> {
            if (!ctx.getMarkings().isEmpty()) {
                vlm.runWithMarking(ctx.getRawSn(), ctx.getMarkings().get(0));
            } else {
                vlm.run(ctx.getRawSn());
            }
        });

        BatchStep frameStep = adapter(BatchStage.FRAME_EXTRACT, ctx -> {
            if (ctx.getMarks().isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "마킹 데이터가 없습니다. rawSn=" + ctx.getRawSn());
            }
            List<LsDataSrc> frames = frame.extractByMarks(ctx.getRaw(), ctx.getMarks());
            if (frames.isEmpty()) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "프레임 추출 결과가 0건입니다 rawSn=" + ctx.getRawSn());
            }
        });

        BatchStep yoloStep = adapter(BatchStage.YOLO, ctx -> ctx.setHints(yolo.run(ctx.getRawSn())));
        BatchStep sam2Step = adapter(BatchStage.SAM2, ctx -> sam2.run(ctx.getRawSn(), ctx.getHints()));
        BatchStep interpStep = adapter(BatchStage.INTERPOLATE, ctx -> interp.run(ctx.getRawSn()));

        return new BatchPipeline(List.of(markingLoad, vlmStep, frameStep, yoloStep, sam2Step, interpStep));
    }

    private interface StepBody {
        void run(BatchContext ctx);
    }

    private static BatchStep adapter(BatchStage stage, StepBody body) {
        return new BatchStep() {
            @Override
            public BatchStage stage() {
                return stage;
            }

            @Override
            public void execute(BatchContext ctx) {
                body.run(ctx);
            }
        };
    }
}
