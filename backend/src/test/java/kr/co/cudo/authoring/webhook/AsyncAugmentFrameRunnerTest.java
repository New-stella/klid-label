package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner;
import kr.co.cudo.authoring.webhook.service.AugmentFrameExtractionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 11 — 증강 프레임 재추출 비동기 러너 단위 테스트.
 *
 * <p>성공 시 추출 서비스에 위임하고, 실패 시 markRawDataFailed(별도 커밋)로 FAILED 전이 후 예외를
 * 삼켜 콜백/파이프라인을 중단하지 않음을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AsyncAugmentFrameRunnerTest {

    @Mock AugmentFrameExtractionService extractionService;
    @Mock BatchTransitionService batchTransitionService;

    @InjectMocks AsyncAugmentFrameRunner runner;

    @Test
    @DisplayName("성공시_추출서비스에_위임하고_FAILED전이_안함")
    void success_delegatesAndDoesNotFail() {
        runner.runAsync(9001L, 20L);

        verify(extractionService, times(1)).extractAndCopy(9001L, 20L);
        verify(batchTransitionService, never()).markRawDataFailed(9001L);
    }

    @Test
    @DisplayName("추출실패시_markRawDataFailed_호출_후_예외_삼킨다")
    void failure_marksFailedAndSwallows() {
        doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "추출 실패"))
                .when(extractionService).extractAndCopy(9002L, 21L);

        // @Async graceful — 예외가 밖으로 전파되지 않아야 한다(콜백 200 유지).
        assertThatCode(() -> runner.runAsync(9002L, 21L)).doesNotThrowAnyException();

        verify(batchTransitionService, times(1)).markRawDataFailed(eq(9002L));
    }
}
