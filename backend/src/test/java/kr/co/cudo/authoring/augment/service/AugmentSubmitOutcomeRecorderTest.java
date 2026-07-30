package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 증강 비동기 제출 완료 핸들러 테스트 — Phase C-3.
 *
 * <h3>고정하는 불변식</h3>
 * <ul>
 *   <li>기록은 <b>조건부 클레임</b> 을 통과할 때만 유효하고, 실패(0행)는 <b>정상</b>이라 예외가 아니다
 *       (지각 신호가 콜백이 올린 상태를 강등하지 못한다).</li>
 *   <li>기록 실패는 <b>삼킨다</b> — 비동기라 던져도 받을 곳이 없다. 회수는 기존 만료 스윕이 한다.</li>
 *   <li>이 빈은 <b>자기 {@code @Transactional} 을 갖지 않는다</b> — 트랜잭션은 전부 REQUIRES_NEW 별도
 *       빈에 프록시 경유로 위임한다(자기호출 경계 유실 사고 재발 방지).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentSubmitOutcomeRecorderTest {

    @Mock private AugmentJobRecorder jobRecorder;
    @Mock private AugmentSubmitRollupTxService rollupTxService;
    @Mock private AugmentMetrics metrics;

    private AugmentSubmitOutcomeRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AugmentSubmitOutcomeRecorder(jobRecorder, rollupTxService, metrics);
    }

    @Test
    @DisplayName("202_수락은_조건부_클레임으로_외부_job_id_를_적재한다")
    void recordsAckThroughConditionalClaim() {
        given(jobRecorder.markSubmitAccepted(anyLong(), anyString())).willReturn(true);

        recorder.onAccepted(7L, 1001L, "ext-job-1", 1, 2);

        verify(jobRecorder).markSubmitAccepted(1001L, "ext-job-1");
        verify(metrics).externalRequestSuccess();
    }

    @Test
    @DisplayName("콜백이_선점해_클레임에_실패해도_예외없이_흡수된다")
    void lateAckIsAbsorbed() {
        given(jobRecorder.markSubmitAccepted(anyLong(), anyString())).willReturn(false);

        assertThatCode(() -> recorder.onAccepted(7L, 1001L, "ext-job-1", 1, 2))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("제출_실패는_SUBMIT_FAILED_사유와_함께_종결_기록된다")
    void recordsSubmitFailure() {
        given(jobRecorder.markSubmitFailed(anyLong(), anyString(), anyString())).willReturn(true);

        recorder.onSubmitFailed(7L, 1002L, 2, 3, new IllegalStateException("외부 장애"));

        verify(jobRecorder).markSubmitFailed(
                eq(1002L), eq(LsDataAugJob.ERR_SUBMIT_FAILED), eq("외부 장애"));
        verify(metrics).externalRequestFailure();
    }

    @Test
    @DisplayName("기록_자체가_터져도_삼킨다_비동기라_던질_곳이_없다")
    void swallowsRecordingFailure() {
        given(jobRecorder.markSubmitFailed(anyLong(), anyString(), anyString()))
                .willThrow(new IllegalStateException("DB 순단(mock)"));

        assertThatCode(() -> recorder.onSubmitFailed(7L, 1002L, 1, 1, new RuntimeException("x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("시퀀스_종료시_종결판정을_별도_트랜잭션_빈에_위임한다")
    void delegatesRollupToTxBean() {
        recorder.onSubmitSequenceFinished(7L);

        verify(rollupTxService).rollUpIfAllTerminal(7L);
    }

    @Test
    @DisplayName("종결판정_실패도_삼킨다_회수는_기존_만료_스윕이_담당한다")
    void swallowsRollupFailure() {
        given(rollupTxService.rollUpIfAllTerminal(anyLong()))
                .willThrow(new IllegalStateException("잠금 경합(mock)"));

        assertThatCode(() -> recorder.onSubmitSequenceFinished(7L)).doesNotThrowAnyException();
    }

    /**
     * 완료 핸들러가 스스로 {@code @Transactional} 을 들면, 그 안에서의 협력자 호출이 자기호출처럼 보이는
     * 착시 + 중첩 tx 커넥션 점유를 유발한다 — 구조적으로 금지한다(C-1/C-2 와 동일 규약).
     */
    @Test
    @DisplayName("완료핸들러는_자기_Transactional_을_갖지_않는다")
    void handlerHasNoOwnTransaction() {
        assertThat(AugmentSubmitOutcomeRecorder.class.getAnnotation(Transactional.class)).isNull();
        for (Method m : AugmentSubmitOutcomeRecorder.class.getDeclaredMethods()) {
            assertThat(m.getAnnotation(Transactional.class))
                    .as("%s 에 @Transactional 이 있으면 안 된다(트랜잭션은 별도 빈에 위임)", m.getName())
                    .isNull();
        }
    }
}
