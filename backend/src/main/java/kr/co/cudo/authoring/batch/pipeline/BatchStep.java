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
 *
 * <h2>트랜잭션 경계 규약 (Critical — DEV_FIX)</h2>
 * <p>호출자({@code BatchOrchestrator.process}, {@code AsyncDeidentifyRunner.runAsync})는 <b>둘 다
 * 트랜잭션이 없다</b>. 따라서 DB 를 쓰는 단계는 <b>{@code execute} 에</b>
 * {@code @Transactional(value = "controlTransactionManager", propagation = REQUIRES_NEW)} 를 선언해야
 * 한다 — 호출자가 주입받은 <b>빈(프록시)</b> 를 통해 호출하므로 이 위치에서만 어드바이스가 발효된다.
 *
 * <p>typed 메서드에만 애노테이션을 두고 {@code execute} 가 그것을 <b>자기호출</b>하면 프록시를 우회해
 * <b>트랜잭션이 열리지 않는다</b>. 그 상태에서는 {@code @Modifying} 벌크 DML 이
 * "Executing an update/delete query" 로 실패하고 dirty-update 는 조용히 유실된다(로컬 실기동에서
 * YOLO 단계 전량 FAILED 로 실측).
 *
 * <p>반대로 {@code execute} 와 typed 메서드를 <b>둘 다 프록시 경유</b>로 REQUIRES_NEW 로 만들면
 * 트랜잭션이 2회 열린다(중첩). 정석은 "경계는 {@code execute} 한 곳, 내부 위임은 자기호출" 이며,
 * typed 메서드의 REQUIRES_NEW 는 <b>직접 호출 진입점</b>(dev 트리거·재처리 등)을 위해 남겨둔다.
 * 이 규약은 {@code BatchStepTransactionBoundaryTest} 가 정적으로 강제한다.
 */
public interface BatchStep {

    /** 상태 마킹/로깅용 단계 식별자. */
    BatchStage stage();

    /** 컨텍스트를 읽고/쓰며 단계를 수행한다. 실패 시 RuntimeException 전파. */
    void execute(BatchContext ctx);

    /**
     * 이 단계가 주어진 컨텍스트에서 실행 대상인지 여부 (Phase 3 — 조건부 step 일반화).
     *
     * <p>판정은 <b>컨텍스트의 stage 토글 단 하나</b>({@code ctx.isStageEnabled(stage())})다. 토글이 없는
     * 컨텍스트(프로덕션 기본 경로 — {@code process(rawSn)}, 선두 비식별, 마킹 브릿지, 자동 재시도)는
     * 모든 단계가 enabled 이므로 <b>동작이 그대로 보존</b>된다. 오케스트레이터는 {@code false} 인 단계의
     * stage 마킹과 execute 를 모두 건너뛴다.
     *
     * <h3>★왜 전 단계가 토글을 따르는가 (구 동작 — FRAME_EXTRACT/YOLO/SAM2 3종만 반영 — 폐기)</h3>
     * <p>단계 지목 재수행([@design API-201])이 「그 단계만 / 그 단계부터 끝까지」라는 <b>범위</b>를
     * 오케스트레이터에 전달하는 축이 이 토글이다. 3종만 토글을 따르면 VLM·INTERPOLATE 가 범위를
     * 무시하고 실행되어, <b>「그 단계만」이 트랙 보간을 돌려버린다</b> — 이 기능이 막으려던 바로 그
     * 데이터 파괴다. 따라서 토글 해석을 단계마다 다르게 두지 않는다.
     *
     * <p>⚠ 이 축은 <b>이미 검증된 값만</b> 받는다는 전제 위에 있다. 요청이 임의 조합을 넣으면 앞 단계를
     * 건너뛴 채 뒤 단계만 돌아 전제 없는 산출물이 생기므로, 대상 단계·범위 검증은 <b>입구</b>
     * ({@code BatchStageRerunService})에서 끝내고 여기까지 내려오지 않게 한다.
     */
    default boolean isEnabled(BatchContext ctx) {
        return ctx == null || ctx.isStageEnabled(stage());
    }
}
