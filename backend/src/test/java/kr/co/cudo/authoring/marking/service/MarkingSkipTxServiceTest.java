package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-ISSUE-41 — skip 된 마킹의 종결 처리 검증.
 *
 * <p>핵심 불변식 둘: ①{@code PENDING} 마킹은 {@code SKIPPED} 로 종결되어 활성 집합에서 빠진다
 * (그래야 재마킹 409 영구 잠금이 풀린다) ②{@code PENDING} 이 아닌 마킹은 <b>절대 덮지 않는다</b>
 * (진행 중인 VLM 사이클을 지우거나 종결 사실을 역행시키면 안 된다).
 */
@ExtendWith(MockitoExtension.class)
class MarkingSkipTxServiceTest {

    @Mock
    private LsMarkingRepository markingRepository;

    @InjectMocks
    private MarkingSkipTxService service;

    private LsMarking pendingMarking() {
        return LsMarking.createAuto(9110L, 100, "[]", "1001", 30.0);
    }

    @Test
    @DisplayName("PENDING_마킹은_SKIPPED로_종결되고_활성집합에서_빠진다")
    void terminatesPendingMarking() {
        // given
        LsMarking marking = pendingMarking();
        when(markingRepository.findById(25L)).thenReturn(Optional.of(marking));

        // when
        boolean terminated = service.terminateSkipped(25L, 9110L);

        // then — SKIPPED 는 활성(미종결) 상태가 아니므로 후속 마킹의 409 가드에 걸리지 않는다.
        assertThat(terminated).isTrue();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_SKIPPED);
        assertThat(LsMarking.ACTIVE_STATUSES).doesNotContain(LsMarking.STATUS_SKIPPED);
        verify(markingRepository).save(marking);
    }

    @Test
    @DisplayName("이미_VLM_REQUESTED인_마킹은_덮지_않는다 — 진행중_위탁_사이클_보호")
    void doesNotOverwriteInFlightMarking() {
        // given — 위탁이 이미 시작된 마킹
        LsMarking marking = pendingMarking();
        marking.markVlmRequested();
        when(markingRepository.findById(26L)).thenReturn(Optional.of(marking));

        // when
        boolean terminated = service.terminateSkipped(26L, 9110L);

        // then — no-op
        assertThat(terminated).isFalse();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("종결된_마킹은_덮지_않는다 — 종결_사실_역행_금지")
    void doesNotOverwriteTerminalMarking() {
        LsMarking marking = pendingMarking();
        marking.markVlmCompleted();
        when(markingRepository.findById(27L)).thenReturn(Optional.of(marking));

        assertThat(service.terminateSkipped(27L, 9110L)).isFalse();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("markingSn이_null이거나_행이_없으면_조용히_no_op — 마킹_API_응답에_영향_없음")
    void nullOrMissingIsNoOp() {
        assertThat(service.terminateSkipped(null, 9110L)).isFalse();

        when(markingRepository.findById(28L)).thenReturn(Optional.empty());
        assertThat(service.terminateSkipped(28L, 9110L)).isFalse();

        verify(markingRepository, never()).save(any());
    }
}
