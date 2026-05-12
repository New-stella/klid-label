package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.test.dto.BatchTriggerResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BatchDevTriggerControllerTest {

    @Mock
    private BatchOrchestrator orchestrator;
    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private BatchDevTriggerController controller;

    @Test
    @DisplayName("존재하지_않는_rawSn_트리거시_404_반환")
    void trigger_notFound_throwsNotFound() {
        given(videoRepository.findById(9999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> controller.trigger(9999L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(orchestrator, never()).process(anyLong());
    }

    @Test
    @DisplayName("이미_PROCESSING_상태인_rawSn_트리거시_409_반환")
    void trigger_alreadyProcessing_throwsConflict() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(raw.getDataSttsCd()).willReturn("PROCESSING");
        given(videoRepository.findById(9026L)).willReturn(Optional.of(raw));

        assertThatThrownBy(() -> controller.trigger(9026L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(orchestrator, never()).process(anyLong());
    }

    @Test
    @DisplayName("정상_rawSn_트리거시_BatchOrchestrator_호출_및_200_반환")
    void trigger_success_invokesOrchestrator() {
        LsDataRaw raw = mock(LsDataRaw.class);
        given(raw.getDataSttsCd()).willReturn("PENDING");
        given(videoRepository.findById(9031L)).willReturn(Optional.of(raw));
        given(orchestrator.process(9031L)).willReturn(BatchStage.COMPLETED);

        ResponseEntity<ApiResponse<BatchTriggerResponse>> response = controller.trigger(9031L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<BatchTriggerResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        BatchTriggerResponse data = body.data();
        assertThat(data).isNotNull();
        assertThat(data.rawSn()).isEqualTo(9031L);
        assertThat(data.finalStage()).isEqualTo("COMPLETED");
        assertThat(data.success()).isTrue();
        assertThat(data.errorMessage()).isNull();
        verify(orchestrator).process(9031L);
    }

    @Test
    @DisplayName("triggerNext_PENDING_없으면_204_반환")
    void triggerNext_noPending_returnsNoContent() {
        given(videoRepository.findFirstByDataSttsCdOrderByRegDtAsc("PENDING"))
                .willReturn(Optional.empty());

        ResponseEntity<ApiResponse<BatchTriggerResponse>> response = controller.triggerNext();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        verify(orchestrator, never()).process(anyLong());
    }

    @Test
    @DisplayName("triggerNext_PENDING_있으면_가장_오래된_것_처리")
    void triggerNext_pendingExists_processesOldest() {
        LsDataRaw oldest = mock(LsDataRaw.class);
        given(oldest.getRawSn()).willReturn(9031L);
        given(videoRepository.findFirstByDataSttsCdOrderByRegDtAsc("PENDING"))
                .willReturn(Optional.of(oldest));
        given(orchestrator.process(9031L)).willReturn(BatchStage.COMPLETED);

        ResponseEntity<ApiResponse<BatchTriggerResponse>> response = controller.triggerNext();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BatchTriggerResponse data = response.getBody().data();
        assertThat(data.rawSn()).isEqualTo(9031L);
        assertThat(data.success()).isTrue();
        verify(orchestrator).process(9031L);
    }

    @Test
    @DisplayName("listPending_PENDING_rawSn_목록_반환")
    void listPending_returnsRawSnList() {
        LsDataRaw a = mock(LsDataRaw.class);
        LsDataRaw b = mock(LsDataRaw.class);
        given(a.getRawSn()).willReturn(101L);
        given(b.getRawSn()).willReturn(102L);
        given(videoRepository.findAllByDataSttsCd("PENDING")).willReturn(List.of(a, b));

        ApiResponse<List<Long>> response = controller.listPending();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsExactly(101L, 102L);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
