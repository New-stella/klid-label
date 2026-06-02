package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3: VlmTimeseriesStep.runWithMarking() 테스트.
 *
 * <p>마킹 데이터를 포함한 VLM 위탁 요청이 올바르게 전송되고,
 * 마킹 상태가 VLM_REQUESTED 로 전이되는지 검증한다.
 */
class VlmTimeseriesStepMarkingTest {

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

    private LsMarking newMarking(Long rawSn) {
        return LsMarking.createAuto(rawSn, "fire", 5,
                "raw/path.mp4", "[{\"frameIndex\":0,\"timestamp\":0.0}]", 1L);
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
    @DisplayName("runWithMarking_마킹_데이터_포함_VLM_요청")
    void runWithMarking_includesMarkingData() {
        // given
        newRaw(400L);
        LsMarking marking = newMarking(400L);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("job-400", "key-400", "ACCEPTED")));

        // when
        VlmTimeseriesResponse resp = step.runWithMarking(400L, marking);

        // then
        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(captor.capture());
        VlmTimeseriesRequest req = captor.getValue();
        assertThat(req.rawSn()).isEqualTo(400L);
        assertThat(req.eventName()).isEqualTo("fire");
        assertThat(req.marks()).isEqualTo("[{\"frameIndex\":0,\"timestamp\":0.0}]");
        assertThat(resp.status()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("runWithMarking_마킹_상태_VLM_REQUESTED_전이")
    void runWithMarking_transitionsMarkingStatus() {
        // given
        newRaw(401L);
        LsMarking marking = newMarking(401L);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);

        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("job-401", "key-401", "ACCEPTED")));

        // when
        step.runWithMarking(401L, marking);

        // then
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);
    }

    @Test
    @DisplayName("runWithMarking_VLM_disabled시_SKIP")
    void runWithMarking_disabled_skips() {
        // given
        when(vlmClient.isEnabled()).thenReturn(false);
        LsMarking marking = newMarking(402L);

        // when
        VlmTimeseriesResponse resp = step.runWithMarking(402L, marking);

        // then
        assertThat(resp.status()).isEqualTo("SKIPPED");
        verify(vlmClient, never()).submitTimeseries(any());
        // disabled 시에는 마킹 상태 전이하지 않음
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
    }

    @Test
    @DisplayName("runWithMarking_null_마킹시_기존_run_호출과_동일")
    void runWithMarking_nullMarking_fallsBackToRun() {
        // given
        newRaw(403L);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("job-403", "key-403", "ACCEPTED")));

        // when
        VlmTimeseriesResponse resp = step.runWithMarking(403L, null);

        // then — null 마킹이면 eventName, marks 가 null 인 요청 (기존 run과 동일)
        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(captor.capture());
        VlmTimeseriesRequest req = captor.getValue();
        assertThat(req.eventName()).isNull();
        assertThat(req.marks()).isNull();
        assertThat(resp.status()).isEqualTo("ACCEPTED");
    }
}
