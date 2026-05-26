package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.VlmResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.InMemoryWebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VlmResultServiceTest {

    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataMetaReviewRepository reviewRepository;
    @Mock VideoRepository videoRepository;
    @Mock LsMarkingRepository markingRepository;
    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();

    private VlmResultService service;

    @BeforeEach
    void setup() {
        service = new VlmResultService(
                metaRepository, reviewRepository, videoRepository, ledger, markingRepository);
        ledger.clear();
    }

    @Test
    @DisplayName("POST_v1_vlm_result_미발급_idempotencyKey_시_401")
    void unknownIdempotencyKey_throws401() {
        VlmResultRequest req = new VlmResultRequest(
                "K-UNK", "EXT-1", "SUCCESS", 100L,
                List.of(new VlmResultRequest.MetaItem("scene", "rainy")),
                null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("VlmResultService_적재_시_LS_DATA_META_VLM_레코드_생성됨")
    void appliesMetaAndReviewQueue() throws Exception {
        ledger.recordIssued("K-VLM", "EXT-V");
        LsDataRaw raw = newRaw(200L);
        when(videoRepository.findById(200L)).thenReturn(Optional.of(raw));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> {
            LsDataMeta m = inv.getArgument(0);
            // simulate ID generation
            Field f = LsDataMeta.class.getDeclaredField("metaSn");
            f.setAccessible(true);
            f.set(m, 999L);
            return m;
        });

        VlmResultRequest req = new VlmResultRequest(
                "K-VLM", "EXT-V", "SUCCESS", 200L,
                List.of(
                        new VlmResultRequest.MetaItem("scene", "rainy"),
                        new VlmResultRequest.MetaItem("density", "high")
                ),
                null);

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        verify(metaRepository, atLeastOnce()).save(any(LsDataMeta.class));
        verify(reviewRepository, atLeastOnce()).save(any(LsDataMetaReview.class));
        assertThat(ledger.isProcessed("K-VLM")).isTrue();
    }

    @Test
    @DisplayName("POST_v1_vlm_result_idempotencyKey_재인계_시_200_OK_멱등")
    void replay_returnsFalseWithoutSave() throws Exception {
        ledger.recordIssued("K-VLM-DUP", "EXT-VD");
        LsDataRaw raw = newRaw(201L);
        when(videoRepository.findById(201L)).thenReturn(Optional.of(raw));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> {
            LsDataMeta m = inv.getArgument(0);
            Field f = LsDataMeta.class.getDeclaredField("metaSn");
            f.setAccessible(true);
            f.set(m, 1000L);
            return m;
        });

        VlmResultRequest req = new VlmResultRequest(
                "K-VLM-DUP", "EXT-VD", "SUCCESS", 201L,
                List.of(new VlmResultRequest.MetaItem("k", "v")),
                null);

        assertThat(service.handle(req)).isTrue();
        assertThat(service.handle(req)).isFalse(); // replay 멱등 스킵
    }

    @Test
    @DisplayName("POST_v1_vlm_result_resultFilePath_사설망_URL_시_400_SSRF")
    void privateNetworkResultFilePath_blocked() {
        ledger.recordIssued("K-SSRF-V", "EXT-SV");
        VlmResultRequest req = new VlmResultRequest(
                "K-SSRF-V", "EXT-SV", "SUCCESS", 200L,
                List.of(new VlmResultRequest.MetaItem("k", "v")),
                "http://10.0.0.1/internal");

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).findById(any());
    }

    private LsDataRaw newRaw(Long rawSn) throws Exception {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-1", "EVT-1", "LCL-1",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 30);
        Field f = LsDataRaw.class.getDeclaredField("rawSn");
        f.setAccessible(true);
        f.set(raw, rawSn);
        return raw;
    }
}
