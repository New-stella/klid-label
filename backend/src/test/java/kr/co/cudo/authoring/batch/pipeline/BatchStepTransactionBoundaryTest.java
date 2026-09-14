package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결함#1 드리프트 가드 — <b>모든</b> {@link BatchStep} 구현의 {@code execute(BatchContext)} 에
 * 트랜잭션 경계가 존재하는지 정적으로 단언한다(신규 단계가 경계를 빠뜨리는 것을 차단).
 *
 * <h3>왜 필요한가</h3>
 * <p>오케스트레이터({@code BatchOrchestrator.process})와 선두 비식별 러너
 * ({@code AsyncDeidentifyRunner.runAsync})는 <b>둘 다 트랜잭션이 없다</b>. 따라서 스텝의
 * {@code execute} 가 경계를 갖지 않으면 그 단계 전체가 <b>무-트랜잭션</b>으로 실행되고,
 * {@code @Modifying} 벌크 DML 은 즉시 실패하며(실측: YOLO 단계 전량 FAILED) dirty-update 는
 * 조용히 유실된다. 과거 이 결함은 {@code execute} 가 {@code @Transactional} 이 붙은
 * {@code run()} 을 <b>자기호출</b>(프록시 우회)하면서 6개 단계 중 5개에 동시에 존재했다.
 *
 * <h3>중첩 금지</h3>
 * <p>경계는 {@code execute} <b>한 곳</b>에만 둔다. 내부 typed 메서드({@code run}/{@code extractByMarks})의
 * {@code @Transactional(REQUIRES_NEW)} 는 <b>직접 호출(dev 트리거 등)</b> 진입점을 위한 것이며,
 * {@code execute} 에서는 자기호출이라 어드바이스가 걸리지 않아 중첩되지 않는다.
 */
class BatchStepTransactionBoundaryTest {

    /** 스캔 기준 패키지 — 신규 단계가 다른 도메인 패키지에 생겨도 걸리도록 루트로 둔다. */
    private static final String BASE_PACKAGE = "kr.co.cudo.authoring";

    /**
     * {@code execute} 에 애노테이션이 <b>없어야</b> 정상인 단계 — 사유가 명확한 것만 여기 둔다.
     *
     * <ul>
     *   <li>{@link DeidentifyStep} — {@code execute} → 프록시 → {@code run()}(트랜잭션 경계 없음, 출처유형
     *       제외 분기가 먼저) → 프록시 → {@code submitToExternal()}({@code REQUIRES_NEW}) 사슬로 외부 위탁
     *       분기의 경계를 얻는다. {@code execute} 에 애노테이션을 <b>추가하면 외부 위탁 분기에서
     *       REQUIRES_NEW 가 두 번 열리고</b>(중첩) 제외 분기의 대용량 원본 복사가 트랜잭션 안에서 돈다.
     *       제외 분기의 실동작은 {@code DeidentifyStepKpstDisabledIntegrationTest} 가 실 DB 로 확인한다.</li>
     *   <li>{@link MarkingLoadStep} — 마킹 조회 + JSON 파싱만 수행하고 DML 이 없다(쓰기 0건).</li>
     * </ul>
     */
    private static final Set<Class<?>> BOUNDARY_EXEMPT = Set.of(DeidentifyStep.class, MarkingLoadStep.class);

    private static List<Class<?>> stepClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BatchStep.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents(BASE_PACKAGE);
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition def : candidates) {
            try {
                classes.add(Class.forName(def.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new AssertionError("BatchStep 구현 로드 실패: " + def.getBeanClassName(), e);
            }
        }
        return classes;
    }

    private static Method executeMethod(Class<?> stepClass) {
        try {
            return stepClass.getDeclaredMethod("execute", BatchContext.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(stepClass.getSimpleName()
                    + " 가 execute(BatchContext) 를 직접 선언하지 않는다 — 트랜잭션 경계 확인 불가", e);
        }
    }

    @Test
    @DisplayName("결함1_모든_BatchStep_구현의_execute에_REQUIRES_NEW_트랜잭션_경계가_있다")
    void everyStepExecuteHasRequiresNewBoundary() {
        List<Class<?>> steps = stepClasses();
        // 스캐너가 아무것도 못 찾으면 단언이 공허해진다 — 실제 단계 수(6) 이상을 먼저 확인한다.
        assertThat(steps).as("스캔된 BatchStep 구현").hasSizeGreaterThanOrEqualTo(6);

        for (Class<?> step : steps) {
            if (BOUNDARY_EXEMPT.contains(step)) {
                continue;
            }
            Transactional tx = AnnotatedElementUtils.findMergedAnnotation(
                    executeMethod(step), Transactional.class);
            assertThat(tx)
                    .as("%s.execute 에 트랜잭션 경계가 없다 — 무-트랜잭션 오케스트레이터에서 "
                            + "@Modifying DML 이 실패하고 dirty-update 가 유실된다", step.getSimpleName())
                    .isNotNull();
            assertThat(tx.propagation())
                    .as("%s.execute 는 스텝별 독립 트랜잭션(REQUIRES_NEW)이어야 한다 — "
                            + "한 단계 실패가 앞 단계 결과를 롤백하지 않는 현 설계", step.getSimpleName())
                    .isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(tx.value())
                    .as("%s.execute 는 control 데이터소스 트랜잭션 매니저를 명시해야 한다", step.getSimpleName())
                    .isEqualTo("controlTransactionManager");
        }
    }

    @Test
    @DisplayName("결함1_자기참조_프록시로_경계를_얻는_단계는_execute에_애노테이션이_없어야_한다_중첩방지")
    void selfProxyStepsMustNotAnnotateExecute() {
        // DeidentifyStep 은 execute → 프록시 → run()(트랜잭션 경계 없음, 출처유형 제외 분기가 먼저) → 프록시
        // → submitToExternal()(REQUIRES_NEW) 로 경계를 얻는다. execute 에 @Transactional 을 덧붙이면
        // 외부 위탁 분기에서 REQUIRES_NEW 가 두 번 열리고(외부 tx 정지 + 신규 tx) 제외 분기의 복사가 tx 안에서 돈다.
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(
                executeMethod(DeidentifyStep.class), Transactional.class);
        assertThat(tx)
                .as("DeidentifyStep.execute 는 자기참조 프록시로 경계를 얻는다 — 애노테이션 병행 시 중첩")
                .isNull();
    }
}
