package kr.co.cudo.authoring.controlnotify.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7a-1 — {@link TaskModifiedEvent} 의 {@code needsRecheck} 축 하위호환 회귀 테스트.
 *
 * <p>이 단계는 순수 가산이다 — 기존 4-arg/5-arg 생성자를 쓰는 모든 발행처(변경하지 않은 발행처 포함)가
 * 여전히 컴파일되고, {@code needsRecheck} 는 반드시 {@code false} 로 시작해야 한다(그렇지 않으면 이번
 * 단계 이전에 존재하던 발행처의 동작이 조용히 바뀐다).
 */
class TaskModifiedEventTest {

    @Test
    @DisplayName("4_arg_생성자는_exportRegenerated_needsRecheck_모두_false다")
    void fourArgConstructor_defaultsBothFlagsFalse() {
        TaskModifiedEvent event = new TaskModifiedEvent(1L, 2L, ChangeType.LABEL_ADDED, 3L);

        assertThat(event.exportRegenerated()).isFalse();
        assertThat(event.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("5_arg_생성자는_needsRecheck_가_false다 — exportRegenerated_만_명시된다")
    void fiveArgConstructor_defaultsNeedsRecheckFalse() {
        TaskModifiedEvent trueExport = new TaskModifiedEvent(1L, 2L, ChangeType.LABEL_ADDED, 3L, true);
        TaskModifiedEvent falseExport = new TaskModifiedEvent(1L, 2L, ChangeType.LABEL_ADDED, 3L, false);

        assertThat(trueExport.exportRegenerated()).isTrue();
        assertThat(trueExport.needsRecheck()).isFalse();
        assertThat(falseExport.exportRegenerated()).isFalse();
        assertThat(falseExport.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("6_arg_canonical_생성자는_두_축을_독립적으로_싣는다")
    void sixArgConstructor_carriesBothAxesIndependently() {
        TaskModifiedEvent event = new TaskModifiedEvent(
                1L, 2L, ChangeType.META_UPDATED, 3L, false, true);

        assertThat(event.exportRegenerated()).isFalse();
        assertThat(event.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("두_축은_레코드_컴포넌트로_서로_독립이다 — 한쪽만_true인_조합도_유효하다")
    void axesAreIndependent() {
        TaskModifiedEvent bothFalse = new TaskModifiedEvent(1L, null, ChangeType.META_UPDATED, 1L, false, false);
        TaskModifiedEvent onlyExport = new TaskModifiedEvent(1L, null, ChangeType.META_UPDATED, 1L, true, false);
        TaskModifiedEvent onlyRecheck = new TaskModifiedEvent(1L, null, ChangeType.META_UPDATED, 1L, false, true);
        TaskModifiedEvent bothTrue = new TaskModifiedEvent(1L, null, ChangeType.META_UPDATED, 1L, true, true);

        assertThat(bothFalse.exportRegenerated()).isFalse();
        assertThat(bothFalse.needsRecheck()).isFalse();
        assertThat(onlyExport.exportRegenerated()).isTrue();
        assertThat(onlyExport.needsRecheck()).isFalse();
        assertThat(onlyRecheck.exportRegenerated()).isFalse();
        assertThat(onlyRecheck.needsRecheck()).isTrue();
        assertThat(bothTrue.exportRegenerated()).isTrue();
        assertThat(bothTrue.needsRecheck()).isTrue();
    }
}
