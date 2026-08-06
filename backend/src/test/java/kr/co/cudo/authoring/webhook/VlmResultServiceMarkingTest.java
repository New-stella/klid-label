package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepositoryCustom;
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
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock LsRawDataStatusRepository rawDataStatusRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();

    private VlmResultService service;

    @BeforeEach
    void setup() {
        service = new VlmResultService(
                metaRepository, reviewRepository, videoRepository, ledger, markingRepository,
                rawDataStatusRepository, eventPublisher);
        ledger.clear();
    }

    private static final AtomicLong META_SN_SEQ = new AtomicLong(1000L);

    /** verify 규격 신규 적재 흐름 — 선행 조회는 empty, upsert 가 <b>삽입</b>을 보고한다. */
    private void stubCompletedFlow(Long rawSn) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        LsDataMeta saved = LsDataMeta.create(rawSn, VlmResultService.META_KEY_DESCRIPTION, "서술");
        long metaSn = META_SN_SEQ.incrementAndGet();
        try {
            Field f = LsDataMeta.class.getDeclaredField("metaSn");
            f.setAccessible(true);
            f.set(saved, metaSn);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        when(metaRepository.findByRawSnAndMetaKey(rawSn, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.empty());
        when(metaRepository.upsertMetaReturning(
                eq(rawSn), eq(VlmResultService.META_KEY_DESCRIPTION), anyString()))
                .thenReturn(new LsDataMetaRepositoryCustom.MetaUpsertOutcome(metaSn, true));
    }

    private static VlmResultRequest completed(String requestId, String description) {
        return new VlmResultRequest(requestId, "completed",
                new VlmResultRequest.Results(new BigDecimal("0.8"), description), null);
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

        when(markingRepository.findByRawSnAndSttsCdIn(600L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(marking));

        VlmResultRequest req = completed("K-M1", "rainy");

        // when
        boolean applied = service.handle(req);

        // then
        assertThat(applied).isTrue();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    /**
     * Phase C-1 — 조회 축이 {@code VLM_REQUESTED} 단독에서 {@link LsMarking#ACTIVE_STATUSES}
     * (PENDING + VLM_REQUESTED)로 넓어졌다. 논블로킹 제출로 콜백이 ACK 보다 먼저 커밋될 수 있어,
     * 마킹이 아직 PENDING 이어도 완료 전이가 성립해야 영구 고착(VLM_REQUESTED)이 생기지 않는다.
     * 종결 상태(VLM_COMPLETED/VLM_FAILED)는 여전히 제외라 역행은 일어나지 않는다.
     */
    @Test
    @DisplayName("VLM_결과_수신시_미종결_ACTIVE_마킹만_전이한다")
    void vlmResult_onlyTransitionsActiveMarkings() {
        // given
        ledger.recordIssued("K-M2", LsWebhookIdempotency.CHANNEL_VLM, "EXT-M2", 601L);
        stubCompletedFlow(601L);

        // 종결(VLM_COMPLETED/VLM_FAILED) 마킹은 ACTIVE_STATUSES 조회 결과에 포함되지 않으므로 전이 대상 아님
        when(markingRepository.findByRawSnAndSttsCdIn(601L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(Collections.emptyList());

        VlmResultRequest req = completed("K-M2", "high");

        // when
        boolean applied = service.handle(req);

        // then — 정상 적재되지만 마킹 전이 대상 없음 (PENDING 마킹은 쿼리 결과에 없음)
        assertThat(applied).isTrue();
        verify(markingRepository).findByRawSnAndSttsCdIn(601L, LsMarking.ACTIVE_STATUSES);
    }

    @Test
    @DisplayName("마킹_없는_영상_VLM_결과_수신시_정상_동작")
    void vlmResult_noMarking_stillWorksNormally() {
        // given
        ledger.recordIssued("K-M3", LsWebhookIdempotency.CHANNEL_VLM, "EXT-M3", 602L);
        stubCompletedFlow(602L);

        // 마킹이 전혀 없는 영상
        when(markingRepository.findByRawSnAndSttsCdIn(602L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(Collections.emptyList());

        VlmResultRequest req = completed("K-M3", "clear");

        // when
        boolean applied = service.handle(req);

        // then
        assertThat(applied).isTrue();
        verify(metaRepository).upsertMetaReturning(602L, VlmResultService.META_KEY_DESCRIPTION, "clear");
        verify(reviewRepository).save(any());
    }

    @Test
    @DisplayName("failed_콜백_수신시_VLM_REQUESTED_마킹을_VLM_FAILED로_전이_고착해제")
    void failedCallback_transitionsMarkingToFailed() {
        // given — VLM_REQUESTED 에 머물던 마킹이 실패 콜백으로 dead-lock 되지 않아야 함(#5)
        ledger.recordIssued("K-M4", LsWebhookIdempotency.CHANNEL_VLM, "EXT-M4", 603L);
        LsMarking marking = createMarkingWithStatus(603L, LsMarking.STATUS_VLM_REQUESTED);
        when(markingRepository.findByRawSnAndSttsCdIn(603L, LsMarking.ACTIVE_STATUSES))
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
