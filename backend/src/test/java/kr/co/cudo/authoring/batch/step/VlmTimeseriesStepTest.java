package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VlmTimeseriesStep 단위 테스트 — Phase 1 (HIGH 시나리오 S-1/S-4 방어).
 *
 * <p>본 Step 은 외부 VLM 서비스에 시계열 메타 분석을 비동기 위탁한다.
 *  - enabled=false → 외부 호출 0건 + SKIPPED 반환 (NO-OP).
 *  - enabled=true  → VlmClient.submitTimeseries 1회 호출 + 영상 메타 조회.
 *  - 외부 호출 실패 → CustomException(EXTERNAL_API_ERROR) 전파.
 */
class VlmTimeseriesStepTest {

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private BatchStatusService batchStatusService;
    private ObjectMapper objectMapper;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        objectMapper = new ObjectMapper();
        step = new VlmTimeseriesStep(vlmClient, videoRepository, batchStatusService, objectMapper);
    }

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        return raw;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("VlmTimeseriesStep_enabled_false_시_외부_호출_0건_SKIPPED_반환")
    void enabledFalseNoOp() {
        // given
        when(vlmClient.isEnabled()).thenReturn(false);

        // when
        VlmTimeseriesResponse resp = step.run(100L);

        // then — VlmClient.submitTimeseries 호출 안 됨, videoRepository 도 조회 안 됨
        verify(vlmClient, never()).submitTimeseries(any());
        verify(videoRepository, never()).findById(any());
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("VlmTimeseriesStep_enabled_true_시_VlmClient_submit_정확히_1회_호출")
    void enabledTrueInvokesClient() {
        // given
        newRaw(200L);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("job-200", "key-200", "ACCEPTED")));

        // when
        VlmTimeseriesResponse resp = step.run(200L);

        // then
        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient, times(1)).submitTimeseries(captor.capture());
        VlmTimeseriesRequest req = captor.getValue();
        assertThat(req.rawSn()).isEqualTo(200L);
        assertThat(req.videoUri()).isEqualTo("raw/path.mp4");
        // DEV_FIX-1: idempotencyKey 발급 책임은 VlmClient 단일화. Step 은 null 전달.
        assertThat(req.idempotencyKey()).isNull();
        assertThat(resp.externalJobId()).isEqualTo("job-200");
    }

    @Test
    @DisplayName("VlmTimeseriesStep_외부_호출_실패_시_EXTERNAL_API_ERROR_예외_전파")
    void clientFailureWrappedAsCustomException() {
        // given
        newRaw(201L);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("vlm down")));

        // when / then
        assertThatThrownBy(() -> step.run(201L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("VLM 위탁");
    }

    @Test
    @DisplayName("VlmTimeseriesStep_rawSn_null_시_INVALID_INPUT_예외")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class);
        verify(vlmClient, never()).submitTimeseries(any());
    }

    @Test
    @DisplayName("VlmTimeseriesStep_enabled_true_영상_미존재_시_NOT_FOUND_예외")
    void videoNotFoundRejected() {
        when(vlmClient.isEnabled()).thenReturn(true);
        when(videoRepository.findById(202L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> step.run(202L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("영상");
        verify(vlmClient, never()).submitTimeseries(any());
    }

    @Test
    @DisplayName("VlmTimeseriesStep_enabled_true_빈_응답_시_EXTERNAL_API_ERROR")
    void emptyResponseRejected() {
        newRaw(203L);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class))).thenReturn(Mono.empty());

        assertThatThrownBy(() -> step.run(203L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("응답");
    }

    // ---- DEV_FIX-1 차 보강 ----

    @Test
    @DisplayName("VlmTimeseriesStep_enabled_true_시_externalJobId_LS_BATCH_PROC_LOG에_기록")
    void externalJobIdPersistedToBatchProcLog() {
        // given
        newRaw(300L);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("job-XYZ-300", "key-300", "ACCEPTED")));

        // when
        VlmTimeseriesResponse resp = step.run(300L);

        // then — BatchStatusService 가 externalJobId 가 포함된 JSON 페이로드로 호출됨
        assertThat(resp.externalJobId()).isEqualTo("job-XYZ-300");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(batchStatusService, times(1))
                .recordVlmTimeseriesResult(eq(300L), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("job-XYZ-300");
        assertThat(payload).contains("ACCEPTED");
    }

    @Test
    @DisplayName("VlmTimeseriesStep_enabled_false_시_BatchStatusService_recordVlmTimeseriesResult_미호출")
    void noPersistWhenDisabled() {
        when(vlmClient.isEnabled()).thenReturn(false);

        step.run(301L);

        verify(batchStatusService, never()).recordVlmTimeseriesResult(any(), any());
    }
}
