package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.VlmResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.InMemoryWebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VlmResultService 마킹 상태 전이 테스트.
 *
 * <p>VLM 결과 수신 시 VLM_REQUESTED 상태의 마킹이 VLM_COMPLETED 로 전이되고,
 * failed 콜백 시 VLM_FAILED 로 전이(고착 해제, DEV_FIX #5)되는지 검증한다.
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

    private void stubCompletedFlow(Long rawSn) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(rawSn), any())).thenReturn(List.of());
        AtomicLong seq = new AtomicLong(1000L);
        lenient().when(metaRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<LsDataMeta> arg = inv.getArgument(0);
            for (LsDataMeta m : arg) {
                if (m.getMetaSn() == null) {
                    Field f = LsDataMeta.class.getDeclaredField("metaSn");
                    f.setAccessible(true);
                    f.set(m, seq.incrementAndGet());
                }
            }
            return arg;
        });
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
    void vlmResult_transitionsMarkingToCompleted() {
        // given
        ledger.recordIssued("K-M1", LsWebhookIdempotency.CHANNEL_VLM, "EXT-M1", 600L);
        stubCompletedFlow(600L);

        LsMarking marking = createMarkingWithStatus(600L, LsMarking.STATUS_VLM_REQUESTED);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);

        when(markingRepository.findByRawSnAndSttsCd(600L, LsMarking.STATUS_VLM_REQUESTED))
                .thenReturn(List.of(marking));

        VlmResultRequest req = new VlmResultRequest(
                "K-M1", "completed",
                List.of(new VlmResultRequest.Segment(0, 8, "rainy")),
                null);

        // when
        boolean applied = service.handle(req);

        // then
        assertThat(applied).isTrue();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    @Test
    @DisplayName("VLM_결과_수신시_VLM_REQUESTED_마킹만_전이")
    void vlmResult_onlyTransitionsVlmRequestedMarkings() {
        // given
        ledger.recordIssued("K-M2", LsWebhookIdempotency.CHANNEL_VLM, "EXT-M2", 601L);
        stubCompletedFlow(601L);

        // PENDING 상태 마킹은 findByRawSnAndSttsCd 결과에 포함되지 않으므로 전이 대상 아님
        when(markingRepository.findByRawSnAndSttsCd(601L, LsMarking.STATUS_VLM_REQUESTED))
                .thenReturn(Collections.emptyList());

        VlmResultRequest req = new VlmResultRequest(
                "K-M2", "completed",
                List.of(new VlmResultRequest.Segment(8, 16, "high")),
                null);

        // when
        boolean applied = service.handle(req);

        // then — 정상 적재되지만 마킹 전이 대상 없음 (PENDING 마킹은 쿼리 결과에 없음)
        assertThat(applied).isTrue();
        verify(markingRepository).findByRawSnAndSttsCd(601L, LsMarking.STATUS_VLM_REQUESTED);
    }

    @Test
    @DisplayName("마킹_없는_영상_VLM_결과_수신시_정상_동작")
    void vlmResult_noMarking_stillWorksNormally() {
        // given
        ledger.recordIssued("K-M3", LsWebhookIdempotency.CHANNEL_VLM, "EXT-M3", 602L);
        stubCompletedFlow(602L);

        // 마킹이 전혀 없는 영상
        when(markingRepository.findByRawSnAndSttsCd(602L, LsMarking.STATUS_VLM_REQUESTED))
                .thenReturn(Collections.emptyList());

        VlmResultRequest req = new VlmResultRequest(
                "K-M3", "completed",
                List.of(new VlmResultRequest.Segment(0, 4, "clear")),
                null);

        // when
        boolean applied = service.handle(req);

        // then
        assertThat(applied).isTrue();
        verify(metaRepository).saveAll(anyList());
        verify(reviewRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("failed_콜백_수신시_VLM_REQUESTED_마킹을_VLM_FAILED로_전이_고착해제")
    void failedCallback_transitionsMarkingToFailed() {
        // given — VLM_REQUESTED 에 머물던 마킹이 실패 콜백으로 dead-lock 되지 않아야 함(#5)
        ledger.recordIssued("K-M4", LsWebhookIdempotency.CHANNEL_VLM, "EXT-M4", 603L);
        LsMarking marking = createMarkingWithStatus(603L, LsMarking.STATUS_VLM_REQUESTED);
        when(markingRepository.findByRawSnAndSttsCd(603L, LsMarking.STATUS_VLM_REQUESTED))
                .thenReturn(List.of(marking));

        VlmResultRequest req = new VlmResultRequest(
                "K-M4", "failed", null,
                new VlmResultRequest.VlmError("VLM_TIMEOUT", "분석 지연"));

        // when
        boolean applied = service.handle(req);

        // then — 마킹은 VLM_REQUESTED 고착이 아니라 VLM_FAILED 종료 상태
        assertThat(applied).isTrue();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_FAILED);
        assertThat(ledger.isProcessed("K-M4")).isTrue();
    }
}
