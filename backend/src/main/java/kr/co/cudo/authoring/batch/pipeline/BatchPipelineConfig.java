package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 배치 파이프라인 단계 순서 정의 — ⭐ 순서 변경의 단일 지점.
 *
 * <p>post-marking 시퀀스의 단계 순서를 결정하는 유일한 위치다. 단계 순서를 바꾸려면
 * 아래 {@link #postMarkingPipeline} 의 {@code List.of(...)} 한 줄만 재배치하면 된다.
 * 오케스트레이터/스텝 구현은 일절 수정하지 않는다.
 *
 * <p>post-marking 순서: MARKING → VLM → FRAME_EXTRACT → YOLO → SAM2 → INTERPOLATE.
 * <p>pre-marking(선두) 순서: DEIDENTIFY (적재 직후 자동 비식별 — Phase 2).
 *
 * <p>빈이 2개({@code preMarkingPipeline}, {@code postMarkingPipeline}) 이므로 주입 측은
 * {@code @Qualifier} 로 명시 선택한다 (모호성 해소).
 */
@Configuration
public class BatchPipelineConfig {

    @Bean
    @Qualifier("postMarkingPipeline")
    public BatchPipeline postMarkingPipeline(
            MarkingLoadStep markingLoad,
            VlmTimeseriesStep vlm,
            FfmpegFrameExtractor frame,
            YoloAutolabelStep yolo,
            Sam2SegmentStep sam2,
            TrackInterpolationStep interp) {
        // 순서 변경은 이 List 한 줄만 수정.
        return new BatchPipeline(List.of(markingLoad, vlm, frame, yolo, sam2, interp));
    }

    /**
     * 선두(pre-marking) 비식별 파이프라인 — 적재 직후 자동 비식별 단일 단계 (Phase 2).
     * 비식별 대상은 ANONY 포함 전체 영상 무조건 (게이팅 폐지).
     */
    @Bean
    @Qualifier("preMarkingPipeline")
    public BatchPipeline preMarkingPipeline(DeidentifyStep deid) {
        return new BatchPipeline(List.of(deid));
    }
}
