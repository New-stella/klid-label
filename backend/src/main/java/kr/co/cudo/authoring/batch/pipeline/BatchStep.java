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

    /**
     * 이 단계가 주어진 컨텍스트에서 실행 대상인지 여부 (Phase 3 — 조건부 step 일반화).
     *
     * <p>기본은 항상 {@code true} — 프로덕션 경로({@code process(rawSn)}, 선두 비식별, 마킹 브릿지)는
     * 토글 없는 컨텍스트를 사용하므로 모든 단계가 enabled 다(동작 100% 보존). 토글 대상 단계
     * (FRAME_EXTRACT/YOLO/SAM2)만 {@code ctx.isStageEnabled(stage())} 로 오버라이드한다.
     * 오케스트레이터는 {@code false} 인 단계의 stage 마킹/execute 를 모두 건너뛴다.
     */
    default boolean isEnabled(BatchContext ctx) {
        return true;
    }
}
