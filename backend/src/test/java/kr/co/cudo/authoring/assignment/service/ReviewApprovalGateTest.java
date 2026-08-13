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
    private kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository videoMetaRepository;
    private kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository taskEventLogRepository;

    private ReviewApprovalGate gate;

    @BeforeEach
    void setUp() {
        repository = mock(LsRawDataStatusRepository.class);
        videoMetaRepository = mock(
                kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository.class);
        taskEventLogRepository = mock(
                kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository.class);
        gate = new ReviewApprovalGate(repository, videoMetaRepository, taskEventLogRepository);
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

    // ───────────── P2b: "한번이라도 검수 완료" 판정 (이력 축) ─────────────

    @Test
    @DisplayName("지금_승인_상태면_이력_판정도_true다")
    void hasEverApproved_true_whenCurrentlyApproved() {
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of(status));

        assertThat(gate.hasEverApproved(1L)).isTrue();
        // 가장 흔한 경우를 먼저 끊어 불필요한 조회를 하지 않는다.
        verify(videoMetaRepository, never()).existsByRawSn(anyLong());
    }

    @Test
    @DisplayName("재제출로_상태가_내려가도_승인_스냅샷이_있으면_true다 — 실증된_구멍")
    void hasEverApproved_true_whenSnapshotExists() {
        // given — ReviewStateMachine 이 APPROVED → PENDING 을 허용해 현재 상태는 PENDING 이다.
        LsRawDataStatus status = LsRawDataStatus.initial(1L);
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of(status));
        // 승인 동결 스냅샷은 append-only 라 "있었다"가 지워지지 않는다.
        when(videoMetaRepository.existsByRawSn(1L)).thenReturn(true);

        assertThat(gate.hasEverApproved(1L)).isTrue();
        assertThat(gate.isApproved(1L)).as("현재 상태 판정으로는 뚫린다").isFalse();
    }

    @Test
    @DisplayName("승인_스냅샷이_없어도_승인_감사가_있으면_차단한다 — V97_이전_영상_fail_closed")
    void hasEverApproved_true_whenAuditExists() {
        // given — LS_DATASET_VIDEO_META 는 V97 신설이라 그 이전 승인 + 백필 이전 재제출 영상은 행이 0건다.
        //   그 false negative 는 곧 게이트가 열리는 방향이라 감사 로그가 뒤를 받친다(OR).
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of(LsRawDataStatus.initial(1L)));
        when(videoMetaRepository.existsByRawSn(1L)).thenReturn(false);
        when(taskEventLogRepository.existsByRawDataIdAndEventTypeCd(
                1L, kr.co.cudo.authoring.assignment.entity.LsTaskEventLog.EVENT_APPROVE)).thenReturn(true);

        assertThat(gate.hasEverApproved(1L)).isTrue();
    }

    @Test
    @DisplayName("승인_이력이_전혀_없으면_false다 — 과잉_차단_방지")
    void hasEverApproved_false_whenNeverApproved() {
        when(repository.findByRawDataIdIn(List.of(1L))).thenReturn(List.of(LsRawDataStatus.initial(1L)));
        when(videoMetaRepository.existsByRawSn(1L)).thenReturn(false);
        when(taskEventLogRepository.existsByRawDataIdAndEventTypeCd(
                1L, kr.co.cudo.authoring.assignment.entity.LsTaskEventLog.EVENT_APPROVE)).thenReturn(false);

        assertThat(gate.hasEverApproved(1L)).isFalse();
    }

    @Test
    @DisplayName("rawSn_이_null_이면_false다")
    void hasEverApproved_false_whenNull() {
        assertThat(gate.hasEverApproved(null)).isFalse();
    }
}
