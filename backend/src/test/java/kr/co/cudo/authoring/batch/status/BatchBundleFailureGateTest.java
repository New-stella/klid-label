package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「그 작업 묶음이 실패한 상태인가」 판정 단위 테스트. [@design ADR-050] [@design API-198]
 *
 * <p>이 판정이 느슨해지면 <b>정상 진행 중인 영상을 근거 없이 건너뛰는 길</b>이 열려 건너뛰기 게이트의
 * 존재 이유가 사라진다. 반대로 지나치게 좁으면 실제로 실패한 영상을 건너뛸 수 없어 고착된다.
 */
class BatchBundleFailureGateTest {

    private static final long RAW_SN = 7L;

    private BatchStatusService batchStatusService;
    private BatchBundleFailureGate gate;

    @BeforeEach
    void setUp() {
        batchStatusService = mock(BatchStatusService.class);
        gate = new BatchBundleFailureGate(batchStatusService);
    }

    @Test
    @DisplayName("아무_실패_기록도_없으면_실패_상태가_아니다")
    void noFailureRecordMeansNotFailed() {
        assertThat(gate.hasFailed(RAW_SN, BatchStageBundle.VLM)).isFalse();
        assertThat(gate.hasFailed(RAW_SN, BatchStageBundle.AUTOLABEL)).isFalse();
    }

    @Test
    @DisplayName("★오토라벨은_진행_축_실패로_판정한다")
    void autolabelUsesProgressAxis() {
        // 오토라벨 스텝은 예외를 던져 파이프라인을 세우므로 진행 행에 FAILED 가 남는다.
        when(batchStatusService.isBundleProgressFailed(RAW_SN, BatchStageBundle.AUTOLABEL)).thenReturn(true);

        assertThat(gate.hasFailed(RAW_SN, BatchStageBundle.AUTOLABEL)).isTrue();
    }

    @Test
    @DisplayName("★★오토라벨은_시계열_위탁_실패_감사행을_보지_않는다_축을_섞지_않는다")
    void autolabelDoesNotReadVlmSkipRows() {
        when(batchStatusService.isStageSkippedWithAnyReason(anyLong(), any(), anyCollection()))
                .thenReturn(true);

        assertThat(gate.hasFailed(RAW_SN, BatchStageBundle.AUTOLABEL)).isFalse();
        verify(batchStatusService, never())
                .isStageSkippedWithAnyReason(anyLong(), any(), anyCollection());
    }

    /**
     * ★★시계열은 진행 축으로 <b>잡히지 않는다</b> — 위탁 제출은 실패해도 예외가 위로 올라가지 않아
     * 파이프라인이 그대로 완주하고 진행 행이 {@code FAILED} 가 되지 않는다. 이 축이 없으면 시계열
     * 건너뛰기 버튼이 <b>영영 뜨지 않는다</b>.
     */
    @Test
    @DisplayName("★★시계열은_위탁_확정실패_감사행으로_판정한다_진행축만으로는_영영_안_잡힌다")
    void vlmUsesSubmitFailureAuditRow() {
        when(batchStatusService.isStageSkippedWithAnyReason(
                RAW_SN, BatchStage.VLM, BatchBundleFailureGate.VLM_FAILURE_SKIP_REASONS))
                .thenReturn(true);

        assertThat(gate.hasFailed(RAW_SN, BatchStageBundle.VLM)).isTrue();
    }

    @Test
    @DisplayName("시계열도_진행_축_실패면_실패다_두_축은_OR다")
    void vlmAlsoAcceptsProgressFailure() {
        when(batchStatusService.isBundleProgressFailed(RAW_SN, BatchStageBundle.VLM)).thenReturn(true);

        assertThat(gate.hasFailed(RAW_SN, BatchStageBundle.VLM)).isTrue();
        // 진행 축에서 이미 참이면 감사 행을 읽지 않는다(왕복 절약).
        verify(batchStatusService, never())
                .isStageSkippedWithAnyReason(anyLong(), any(), anyCollection());
    }

    /**
     * ★<b>실패 사유 목록이 이 게이트의 안전선</b>이다. 비식별 신고 보류처럼 스스로 재개되는 사유를
     * 실패로 읽으면 정상 영상을 건너뛰는 길이 다시 열린다.
     */
    @Test
    @DisplayName("★보류_사유는_실패_목록에_들어가지_않는다_정상영상_건너뛰기_통로가_다시_열린다")
    void withheldReasonsAreNotFailures() {
        assertThat(BatchBundleFailureGate.VLM_FAILURE_SKIP_REASONS)
                .containsExactly(
                        VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED,
                        VlmTimeseriesStep.SKIP_REASON_ACK_MISSING,
                        VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING)
                .doesNotContain(
                        VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT,
                        VlmTimeseriesStep.SKIP_REASON_EVENT_TYPE_MISSING,
                        VlmTimeseriesStep.SKIP_REASON_EVENT_TYPE_UNSUPPORTED);
    }

    @Test
    @DisplayName("null_입력은_실패로_보지_않는다_fail_closed")
    void nullInputIsNotFailure() {
        assertThat(gate.hasFailed(null, BatchStageBundle.VLM)).isFalse();
        assertThat(gate.hasFailed(RAW_SN, null)).isFalse();
        verify(batchStatusService, never()).isBundleProgressFailed(any(), any());
    }

    /* ========== failedBundles — 영상 상세의 실패 묶음 목록 [@design API-043] ========== */

    /**
     * ★위탁 확정 실패 세 사유가 모두 목록에 잡혀야 한다 — 시계열 위탁은 논블로킹이라 실패해도 배치
     * 상태가 완료로 남고 단계 실패 표시도 서지 않으므로, 이 목록이 화면의 <b>유일한</b> 실패 신호다.
     */
    @Test
    @DisplayName("★★위탁_확정_실패_세_사유_각각에서_시계열이_실패_목록에_담긴다")
    void vlmSubmitFailuresAppearInFailedBundles() {
        for (String reason : List.of(
                VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED,
                VlmTimeseriesStep.SKIP_REASON_ACK_MISSING,
                VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING)) {
            BatchStatusService status = mock(BatchStatusService.class);
            when(status.isStageSkippedWithAnyReason(eq(RAW_SN), eq(BatchStage.VLM), anyCollection()))
                    .thenAnswer(inv -> ((java.util.Collection<?>) inv.getArgument(2)).contains(reason));

            assertThat(new BatchBundleFailureGate(status).failedBundles(RAW_SN))
                    .as("사유=%s", reason)
                    .containsExactly(BatchStageBundle.VLM.name());
        }
    }

    @Test
    @DisplayName("★오토라벨_진행_실패도_실패_목록에_담긴다")
    void autolabelProgressFailureAppearsInFailedBundles() {
        when(batchStatusService.isBundleProgressFailed(RAW_SN, BatchStageBundle.AUTOLABEL)).thenReturn(true);

        assertThat(gate.failedBundles(RAW_SN)).containsExactly(BatchStageBundle.AUTOLABEL.name());
    }

    /**
     * ★순서는 묶음 선언 순서(VLM → AUTOLABEL) 고정 — 실행마다 흔들리면 화면이 깜빡인다
     * ({@code manuallySkippedBundles}·{@code clearedBundles} 와 같은 관례).
     */
    @Test
    @DisplayName("★두_묶음이_모두_실패면_선언_순서로_고정_반환한다")
    void bothBundlesKeepDeclarationOrder() {
        when(batchStatusService.isBundleProgressFailed(RAW_SN, BatchStageBundle.VLM)).thenReturn(true);
        when(batchStatusService.isBundleProgressFailed(RAW_SN, BatchStageBundle.AUTOLABEL)).thenReturn(true);

        assertThat(gate.failedBundles(RAW_SN))
                .containsExactly(BatchStageBundle.VLM.name(), BatchStageBundle.AUTOLABEL.name());
    }

    @Test
    @DisplayName("실패가_없으면_빈_배열이다_null_아님")
    void noFailureYieldsEmptyList() {
        assertThat(gate.failedBundles(RAW_SN)).isEmpty();
        assertThat(gate.failedBundles(null)).isEmpty();
    }

    /**
     * ★재개되는 <b>보류</b>는 실패가 아니다 — 그 행을 실패로 읽으면 정상 영상을 건너뛰는 길이 다시
     * 열려 이 게이트의 존재 이유가 사라진다(목록 필터·화면 버튼이 함께 잘못 뜬다).
     */
    @Test
    @DisplayName("★★스스로_재개되는_보류_사유는_실패_목록에_담기지_않는다")
    void resumableWithholdIsNotFailure() {
        when(batchStatusService.isStageSkippedWithAnyReason(eq(RAW_SN), eq(BatchStage.VLM), anyCollection()))
                .thenAnswer(inv -> ((java.util.Collection<?>) inv.getArgument(2))
                        .contains(VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT));

        assertThat(gate.failedBundles(RAW_SN)).isEmpty();
    }

    /**
     * ★사유 문자열은 <b>단일 원천</b>이어야 한다 — 목록 필터가 같은 축으로 DB 술어를 만들 때 이 창구를
     * 쓴다. 상수를 소비자 쪽에 복제하면 사유가 하나 늘 때 한쪽만 갱신돼 조용히 갈린다.
     */
    @Test
    @DisplayName("★실패_사유_목록_접근자는_판정에_쓰는_그_목록을_그대로_돌려준다")
    void reasonAccessorExposesTheSameList() {
        assertThat(BatchBundleFailureGate.vlmFailureSkipReasons())
                .containsExactly(
                        VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED,
                        VlmTimeseriesStep.SKIP_REASON_ACK_MISSING,
                        VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING);
    }

    @Test
    @DisplayName("판정에_넘기는_묶음은_요청_값이_아니라_해석된_상수다")
    void judgesWithParsedBundleConstant() {
        gate.hasFailed(RAW_SN, BatchStageBundle.VLM);

        verify(batchStatusService).isBundleProgressFailed(eq(RAW_SN), eq(BatchStageBundle.VLM));
    }
}
