package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;

/**
 * 배치 파이프라인의 단일 단계를 나타내는 균일 인터페이스.
 *
 * <p>각 단계는 자신이 속한 {@link BatchStage} 를 알려주고({@link #stage()}),
 * {@link BatchContext} 를 읽고/써서 실행({@link #execute(BatchContext)})한다.
 * 오케스트레이터는 단계의 구체 타입을 몰라도 stage 마킹 + execute 만 균일하게 호출한다.
 *
 * <p>구현 단계는 기존 typed 메서드(run/extractByMarks 등)를 보존하고
 * {@code execute} 가 그 메서드에 위임한다 — 기존 단위테스트 영향 최소화.
 */
public interface BatchStep {

    /** 상태 마킹/로깅용 단계 식별자. */
    BatchStage stage();

    /** 컨텍스트를 읽고/쓰며 단계를 수행한다. 실패 시 RuntimeException 전파. */
    void execute(BatchContext ctx);
}
