package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.test.dto.BatchTriggerResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock
    private TrainingVideoIngestService trainingVideoIngestService;

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
        given(videoRepository.findAllByDataSttsCd(eq("PENDING"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 20), 2));

        ApiResponse<Page<Long>> response = controller.listPending(0, 20);

        assertThat(response.success()).isTrue();
        assertThat(response.data().getContent()).containsExactly(101L, 102L);
        assertThat(response.data().getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("listPending_페이징은_저장소_계층에_위임된다_전량조회_아님")
    void listPending_delegatesPagingToRepository() {
        given(videoRepository.findAllByDataSttsCd(eq("PENDING"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

        controller.listPending(2, 50);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(videoRepository).findAllByDataSttsCd(eq("PENDING"), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
        // 오래된 순(다음 실행 대상 순) + PK 보조 정렬 — 페이지 경계 중복·누락 방지
        assertThat(captor.getValue().getSort().toString()).isEqualTo("regDt: ASC,rawSn: ASC");
    }

    @Test
    @DisplayName("scan_트리거시_TrainingVideoIngestService_scanAndIngest_호출후_적재건수_200_반환")
    void scan_invokesScanAndIngest_returnsIngestedCount() {
        given(trainingVideoIngestService.scanAndIngest()).willReturn(2);

        ApiResponse<Integer> response = controller.scan();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(2);
        verify(trainingVideoIngestService).scanAndIngest();
    }

    @Test
    @DisplayName("scan_트리거시_픽업_대상이_없으면_0건_200_반환")
    void scan_noCandidates_returnsZero() {
        given(trainingVideoIngestService.scanAndIngest()).willReturn(0);

        ApiResponse<Integer> response = controller.scan();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isZero();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
