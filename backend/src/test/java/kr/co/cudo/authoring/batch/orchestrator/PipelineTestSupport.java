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

        BatchStep yoloStep = adapter(BatchStage.YOLO, ctx -> ctx.setHints(yolo.run(ctx.getRawSn())));
        BatchStep sam2Step = adapter(BatchStage.SAM2, ctx -> sam2.run(ctx.getRawSn(), ctx.getHints()));
        BatchStep interpStep = adapter(BatchStage.INTERPOLATE, ctx -> interp.run(ctx.getRawSn()));

        return new BatchPipeline(List.of(markingLoad, vlmStep, frameStepToggleable(frame), yoloStep, sam2Step, interpStep));
    }

    /** FRAME_EXTRACT 어댑터 — 실제 step 의 execute 가드(marks 비었음 / 추출 0건)를 그대로 재현한다. */
    private static BatchStep frameStepToggleable(FfmpegFrameExtractor frame) {
        return adapter(BatchStage.FRAME_EXTRACT, ctx -> {
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
    }

    private interface StepBody {
        void run(BatchContext ctx);
    }

    /**
     * 단계 어댑터 — {@code isEnabled} 는 <b>재정의하지 않는다</b>. 프로덕션과 동일하게 인터페이스 기본
     * 구현({@code ctx.isStageEnabled(stage())})이 판정하므로, 전 단계가 같은 규칙으로 토글을 따른다.
     *
     * <p>구 지원 코드는 FRAME_EXTRACT/YOLO/SAM2 용 "토글 가능 어댑터"를 따로 뒀는데, 프로덕션이 3종
     * 오버라이드를 걷어내고 기본 구현으로 통일하면서 두 어댑터가 같아졌다 — 테스트 하네스가 프로덕션과
     * 다른 판정을 갖고 있으면 「그 단계만」 범위 검증이 하네스 특성만 확인하게 된다.
     */
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
