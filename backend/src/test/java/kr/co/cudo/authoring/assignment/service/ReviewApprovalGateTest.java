package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7a-1 — {@link ReviewApprovalGate} 단위 테스트(Mockito).
 *
 * <p>판정 지점 단일화(구 14곳 복제 {@code isReviewApproved})와 재검토 표시 세우기/해제의 멱등성을
 * 검증한다. {@code markNeedsRecheck}/{@code clearNeedsRecheck} 는 {@code LsRawDataStatus} 실 엔티티를
 * 사용해 엔티티 레벨 멱등(값 비교 없이 재대입해도 관측 가능한 부작용이 없음)까지 확인한다.
 */
class ReviewApprovalGateTest {

    private LsRawDataStatusRepository repository;
    private ReviewApprovalGate gate;

    @BeforeEach
    void setUp() {
        repository = mock(LsRawDataStatusRepository.class);
        gate = new ReviewApprovalGate(repository);
    }

    @Test
    @DisplayName("APPROVED_상태면_isApproved_는_true다")
    void isApproved_true_whenApproved() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of(status));

        assertThat(gate.isApproved(1L)).isTrue();
    }

    @Test
    @DisplayName("APPROVED_아니면_isApproved_는_false다")
    void isApproved_false_whenNotApproved() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        status.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of(status));

        assertThat(gate.isApproved(1L)).isFalse();
    }

    @Test
    @DisplayName("상태row가_없으면_미검수로_간주하여_false다")
    void isApproved_false_whenNoStatusRow() {
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of());

        assertThat(gate.isApproved(1L)).isFalse();
    }

    @Test
    @DisplayName("isApprovedCached_는_같은_rawSn을_반복_판정할_때_리포지토리를_1회만_조회한다")
    void isApprovedCached_avoidsRepeatedQuery() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of(status));
        Map<Long, Boolean> cache = new HashMap<>();

        boolean first = gate.isApprovedCached(1L, cache);
        boolean second = gate.isApprovedCached(1L, cache);
        boolean third = gate.isApprovedCached(1L, cache);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isTrue();
        verify(repository, times(1)).findByRawDataIdIn(List.of(1L));
    }

    @Test
    @DisplayName("markNeedsRecheck_는_대상행의_REVLT_YN을_Y로_세운다")
    void markNeedsRecheck_setsFlagOnEntity() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(status));

        gate.markNeedsRecheck(1L);

        assertThat(status.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("markNeedsRecheck_는_멱등이다 — 이미_Y인_행에_다시_호출해도_Y")
    void markNeedsRecheck_isIdempotent() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        status.markNeedsRecheck();
        when(repository.findById(1L)).thenReturn(Optional.of(status));

        gate.markNeedsRecheck(1L);
        gate.markNeedsRecheck(1L);

        assertThat(status.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("markNeedsRecheck_는_rawSn이_null이면_리포지토리를_전혀_건드리지_않는다")
    void markNeedsRecheck_noopWhenRawSnNull() {
        gate.markNeedsRecheck(null);

        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("markNeedsRecheck_는_대상행이_없으면_예외없이_no_op이다")
    void markNeedsRecheck_noopWhenRowMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        gate.markNeedsRecheck(99L);

        verify(repository, times(1)).findById(99L);
    }

    @Test
    @DisplayName("clearNeedsRecheck_는_대상행의_REVLT_YN을_N으로_되돌린다")
    void clearNeedsRecheck_clearsFlagOnEntity() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        status.markNeedsRecheck();
        when(repository.findById(1L)).thenReturn(Optional.of(status));

        gate.clearNeedsRecheck(1L);

        assertThat(status.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("clearNeedsRecheck_는_멱등이다 — 이미_N인_행에_호출해도_N")
    void clearNeedsRecheck_isIdempotent() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(status));

        gate.clearNeedsRecheck(1L);

        assertThat(status.needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("needsRecheck_조회는_행이_없으면_false다")
    void needsRecheck_falseWhenNoRow() {
        when(repository.findById(anyLong())).thenReturn(Optional.empty());

        assertThat(gate.needsRecheck(1L)).isFalse();
    }

    @Test
    @DisplayName("needsRecheck_조회는_rawSn이_null이면_false다")
    void needsRecheck_falseWhenNullRawSn() {
        assertThat(gate.needsRecheck(null)).isFalse();
    }
}
