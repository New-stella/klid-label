package kr.co.cudo.authoring.batch.pipeline;

import java.util.List;

/**
 * 순서를 가진 배치 단계 모음.
 *
 * <p>{@link BatchPipelineConfig} 가 단일 지점에서 단계 순서를 정의해 생성하며,
 * {@code BatchOrchestrator} 는 본 record 의 {@link #steps()} 를 순서대로 실행한다.
 * 순서 변경은 {@link BatchPipelineConfig} 한 곳의 {@code List.of(...)} 만 고치면 된다.
 */
public record BatchPipeline(List<BatchStep> steps) {
}
