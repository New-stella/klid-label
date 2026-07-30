package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C-2 — KPST 논블로킹 제출의 완료 핸들러 단위 테스트.
 *
 * <p>본 빈은 <b>전용 풀 스레드</b>(ambient tx 없음)에서 실행되므로 DB 쓰기를 직접 하지 않고
 * {@link KpstDeidentTxService}(REQUIRES_NEW 별도 빈)에 프록시 경유로 위임하는지, 그리고 기록 실패를
 * 밖으로 던지지 않는지를 검증한다.
 */
class KpstSubmitOutcomeRecorderTest {

    private KpstDeidentTxService txService;
    private KpstSubmitOutcomeRecorder recorder;

    @BeforeEach
    void setUp() {
        txService = mock(KpstDeidentTxService.class);
        recorder = new KpstSubmitOutcomeRecorder(txService);
    }

    @Test
    @DisplayName("ACK수신시_prjId를_원장에_기록위임한다")
    void onAcceptedRecordsPrjId() {
        // when
        recorder.onAccepted(9001L, 1L, new KpstProjectResponse("success", 101L));

        // then — 상태 전이는 REQUIRES_NEW 별도 빈이 수행한다(핸들러 자체는 트랜잭션을 열지 않는다).
        verify(txService).recordSubmitAck(1L, 9001L, 101L);
        verify(txService, never()).failSubmit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("응답에_prjId가_없으면_수락으로_보지않고_실패로_종결한다")
    void onAcceptedWithoutPrjIdIsFailure() {
        // given — 응답은 왔으나 프로젝트 ID 가 없으면 폴링이 불가능하다(구 동기 가드와 동일 판정).
        recorder.onAccepted(9001L, 1L, new KpstProjectResponse("success", null));

        // then
        verify(txService).failSubmit(1L, 9001L, KpstDeidentService.SUBMIT_FAILED_CODE, "empty prjId");
        verify(txService, never()).recordSubmitAck(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("제출_확정실패는_SUBMIT_FAILED_코드로_원장을_종결위임한다")
    void onSubmitFailedTerminatesLedger() {
        // when
        recorder.onSubmitFailed(9001L, 1L, new CustomException(ErrorCode.EXTERNAL_API_ERROR, "boom"));

        // then — ACK 미수신 회수(ACK_MISSING)와 구분되는 코드로 기록한다.
        verify(txService).failSubmit(eq(1L), eq(9001L),
                eq(KpstDeidentService.SUBMIT_FAILED_CODE), eq("CustomException"));
    }

    @Test
    @DisplayName("기록실패는_밖으로_던지지_않는다_비동기라_받을_곳이_없다")
    void recordingFailureIsSwallowed() {
        // given — 기록 트랜잭션이 실패(DB 장애 등).
        when(txService.recordSubmitAck(anyLong(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("db down"));
        when(txService.failSubmit(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        // when / then — 예외 전파 금지. 회수는 폴러의 ACK 대기 유예 만료가 담당한다.
        assertThatCode(() -> recorder.onAccepted(9001L, 1L, new KpstProjectResponse("success", 101L)))
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.onSubmitFailed(9001L, 1L, new RuntimeException("x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cause가_null이어도_기록에_실패하지_않는다")
    void nullCauseIsRecordedAsUnknown() {
        recorder.onSubmitFailed(9001L, 1L, null);
        verify(txService).failSubmit(1L, 9001L, KpstDeidentService.SUBMIT_FAILED_CODE, "unknown");
    }
}
