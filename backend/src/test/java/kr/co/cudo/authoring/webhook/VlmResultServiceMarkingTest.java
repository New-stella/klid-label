package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.marking.entity.LsMarking;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3: VlmResultService 마킹 상태 전이 테스트.
 *
 * <p>VLM 결과 수신 시 VLM_REQUESTED 상태의 마킹이 VLM_COMPLETED 로 전이되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class VlmResultServiceMarkingTest {

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

    private LsDataRaw newRaw(Long rawSn) throws Exception {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-1", "EVT-1", "LCL-1",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 30);
        Field f = LsDataRaw.class.getDeclaredField("rawSn");
        f.setAccessible(true);
        f.set(raw, rawSn);
        return raw;
    }

    private LsMarking createMarkingWithStatus(Long rawSn, String status) {
        LsMarking m = LsMarking.createAuto(rawSn, "fire", 5,
                "raw/path.mp4", "[{\"frameIndex\":0}]", 1L);
        if (LsMarking.STATUS_VLM_REQUESTED.equals(status)) {
            m.markVlmRequested();
        } else if (LsMarking.STATUS_VLM_COMPLETED.equals(status)) {
            m.markVlmRequested();
            m.markVlmCompleted();
        }
        return m;
    }

    @Test
    @DisplayName("VLM_결과_수신시_마킹_상태_VLM_COMPLETED_전이")
    void vlmResult_transitionsMarkingToCompleted() throws Exception {
        // given
        ledger.recordIssued("K-M1", "EXT-M1");
        LsDataRaw raw = newRaw(600L);
        when(videoRepository.findById(600L)).thenReturn(Optional.of(raw));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> {
            LsDataMeta m = inv.getArgument(0);
            Field f = LsDataMeta.class.getDeclaredField("metaSn");
            f.setAccessible(true);
            f.set(m, 999L);
            return m;
        });

        LsMarking marking = createMarkingWithStatus(600L, LsMarking.STATUS_VLM_REQUESTED);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);

        when(markingRepository.findByRawSnAndSttsCd(600L, LsMarking.STATUS_VLM_REQUESTED))
                .thenReturn(List.of(marking));

        VlmResultRequest req = new VlmResultRequest(
                "K-M1", "EXT-M1", "SUCCESS", 600L,
                List.of(new VlmResultRequest.MetaItem("scene", "rainy")),
                null);

        // when
        boolean applied = service.handle(req);

        // then
        assertThat(applied).isTrue();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    @Test
    @DisplayName("VLM_결과_수신시_VLM_REQUESTED_마킹만_전이")
    void vlmResult_onlyTransitionsVlmRequestedMarkings() throws Exception {
        // given
        ledger.recordIssued("K-M2", "EXT-M2");
        LsDataRaw raw = newRaw(601L);
        when(videoRepository.findById(601L)).thenReturn(Optional.of(raw));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> {
            LsDataMeta m = inv.getArgument(0);
            Field f = LsDataMeta.class.getDeclaredField("metaSn");
            f.setAccessible(true);
            f.set(m, 1000L);
            return m;
        });

        // PENDING 상태 마킹은 findByRawSnAndSttsCd 결과에 포함되지 않으므로 전이 대상 아님
        when(markingRepository.findByRawSnAndSttsCd(601L, LsMarking.STATUS_VLM_REQUESTED))
                .thenReturn(Collections.emptyList());

        VlmResultRequest req = new VlmResultRequest(
                "K-M2", "EXT-M2", "SUCCESS", 601L,
                List.of(new VlmResultRequest.MetaItem("density", "high")),
                null);

        // when
        boolean applied = service.handle(req);

        // then — 정상 적재되지만 마킹 전이 대상 없음 (PENDING 마킹은 쿼리 결과에 없음)
        assertThat(applied).isTrue();
        verify(markingRepository).findByRawSnAndSttsCd(601L, LsMarking.STATUS_VLM_REQUESTED);
    }

    @Test
    @DisplayName("마킹_없는_영상_VLM_결과_수신시_정상_동작")
    void vlmResult_noMarking_stillWorksNormally() throws Exception {
        // given
        ledger.recordIssued("K-M3", "EXT-M3");
        LsDataRaw raw = newRaw(602L);
        when(videoRepository.findById(602L)).thenReturn(Optional.of(raw));
        when(metaRepository.findByRawSnAndMetaKey(any(), any())).thenReturn(Optional.empty());
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> {
            LsDataMeta m = inv.getArgument(0);
            Field f = LsDataMeta.class.getDeclaredField("metaSn");
            f.setAccessible(true);
            f.set(m, 1001L);
            return m;
        });

        // 마킹이 전혀 없는 영상
        when(markingRepository.findByRawSnAndSttsCd(602L, LsMarking.STATUS_VLM_REQUESTED))
                .thenReturn(Collections.emptyList());

        VlmResultRequest req = new VlmResultRequest(
                "K-M3", "EXT-M3", "SUCCESS", 602L,
                List.of(new VlmResultRequest.MetaItem("weather", "clear")),
                null);

        // when
        boolean applied = service.handle(req);

        // then
        assertThat(applied).isTrue();
        verify(metaRepository).save(any(LsDataMeta.class));
        verify(reviewRepository).save(any(LsDataMetaReview.class));
    }
}
