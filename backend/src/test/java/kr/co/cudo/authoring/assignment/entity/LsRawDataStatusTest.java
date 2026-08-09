package kr.co.cudo.authoring.assignment.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LsRawDataStatus 엔티티 단위 테스트.
 */
class LsRawDataStatusTest {

    @Test
    @DisplayName("markBatchQueued_호출시_BATCH_QUEUED_전이")
    void markBatchQueued() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);
        stts.markAssigned();

        // when
        stts.markBatchQueued();

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        assertThat(stts.getUpdDt()).isNotNull();
    }

    @Test
    @DisplayName("initial_생성시_PENDING_상태")
    void initialStatus() {
        // given / when
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_PENDING);
    }

    @Test
    @DisplayName("markAssigned_호출시_ASSIGNED_전이")
    void markAssigned() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);

        // when
        stts.markAssigned();

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
    }

    // ---------- Phase 7a-1 — 재검토여부(REVLT_YN) ----------

    @Test
    @DisplayName("initial_생성시_재검토여부는_N이다 — 신규행은_항상_재검토_불요로_시작한다")
    void initial_needsRecheckIsFalseByDefault() {
        // given / when
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);

        // then
        assertThat(stts.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("markNeedsRecheck_호출시_재검토_필요로_표시된다")
    void markNeedsRecheck_setsTrue() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);

        // when
        stts.markNeedsRecheck();

        // then
        assertThat(stts.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("markNeedsRecheck_는_멱등이다 — 이미_Y여도_반복_호출해도_Y")
    void markNeedsRecheck_isIdempotent() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);
        stts.markNeedsRecheck();

        // when — 두 번째 호출
        stts.markNeedsRecheck();

        // then
        assertThat(stts.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("clearNeedsRecheck_호출시_재검토_표시가_해제된다")
    void clearNeedsRecheck_setsFalse() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);
        stts.markNeedsRecheck();

        // when
        stts.clearNeedsRecheck();

        // then
        assertThat(stts.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("clearNeedsRecheck_는_멱등이다 — 이미_N인_상태에서_호출해도_N")
    void clearNeedsRecheck_isIdempotent() {
        // given — 신규 행은 이미 N
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);

        // when
        stts.clearNeedsRecheck();

        // then
        assertThat(stts.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("재검토표시는_검수_워크플로우_상태전이와_독립이다 — transitionTo가_REVLT_YN을_건드리지_않는다")
    void needsRecheckFlag_isIndependentOfWorkflowTransition() {
        // given
        LsRawDataStatus stts = LsRawDataStatus.initial(1L);
        stts.markNeedsRecheck();

        // when — 검수 워크플로우 상태만 바뀜(예: 승인)
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);

        // then — 재검토 표시는 그대로 유지된다(다른 축)
        assertThat(stts.needsRecheck()).isTrue();
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_APPROVED);
    }
}
