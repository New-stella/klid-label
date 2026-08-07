package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * L6 — <b>지각 콜백이 새 PENDING 마킹을 전이시키지 않는다</b> 회귀 가드.
 *
 * <h3>막는 실패 모드</h3>
 * <p>콜백 선행 레이스(콜백이 ACK 보다 먼저 도착)를 닫으려고 전이 조회 범위를
 * {@link LsMarking#ACTIVE_STATUSES}(PENDING 포함)로 넓힌 부작용이 있다: 앞선 위탁이
 * {@code VLM_FAILED} 로 끝난 뒤 작업자가 <b>다시 마킹</b>하면 그 새 마킹(PENDING)이 활성 상태로 존재하는데,
 * 이때 옛 request 의 지각 콜백이 도착하면 <b>한 번도 위탁된 적 없는</b> 새 마킹을 {@code VLM_COMPLETED}
 * 로 올려버린다 — 그 마킹의 시계열 분석은 실제로 수행되지 않았다(허위 완료).
 *
 * <h3>판정</h3>
 * <p>원장의 <b>발급 시각</b>({@code Entry.issuedAt}) 이후에 생성된 {@code PENDING} 마킹만 제외한다.
 * {@code VLM_REQUESTED} 는 이 위탁이 올린 상태이므로 항상 전이 대상이고, 위탁 <b>전</b>에 이미 있던
 * PENDING 도 그대로 전이되어 레이스 해소는 유지된다.
 *
 * <h3>시간 의존 제거</h3>
 * <p>{@code Thread.sleep} 으로 "나중에 생긴 마킹"을 만들지 않는다. 원장에 기록된 발급 시각을 읽어
 * 마킹의 {@code REG_DT} 를 그 <b>앞/뒤로 명시 설정</b>하므로 결정적이다(분 경계 플레이키 없음).
 *
 * <h3>RED 실증</h3>
 * <p>{@code VlmResultService.markingsInScope} 의 {@code issuedAt} 비교(=
 * {@code isCreatedAfterSubmit} 필터)를 제거하면 {@link #callbackDoesNotTransitionMarkingCreatedAfterSubmit}
 * 와 {@link #failedCallbackDoesNotTransitionMarkingCreatedAfterSubmit} 가 RED 다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmResultServiceLateCallbackScopeTest {

    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataMetaReviewRepository reviewRepository;
    @Mock VideoRepository videoRepository;
    @Mock LsMarkingRepository markingRepository;
    @Mock ReviewApprovalGate approvalGate;
    @Mock ApplicationEventPublisher eventPublisher;

    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();
    private VlmResultService service;

    @BeforeEach
    void setUp() {
        service = new VlmResultService(
                metaRepository, reviewRepository, videoRepository, ledger, markingRepository,
                approvalGate, eventPublisher);
        ledger.clear();
    }

    private static final AtomicLong META_SN_SEQ = new AtomicLong(3000L);

    /** verify 규격 신규 적재 흐름 — 선행 조회는 empty, upsert 가 <b>삽입</b>을 보고한다. */
    private void stubCompletedFlow(Long rawSn) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        LsDataMeta saved = LsDataMeta.create(rawSn, VlmResultService.META_KEY_DESCRIPTION, "rainy");
        long metaSn = META_SN_SEQ.incrementAndGet();
        setField(saved, LsDataMeta.class, "metaSn", metaSn);
        when(metaRepository.findByRawSnAndMetaKey(rawSn, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.empty());
        when(metaRepository.upsertMetaReturning(
                eq(rawSn), eq(VlmResultService.META_KEY_DESCRIPTION), anyString()))
                .thenReturn(new LsDataMetaRepositoryCustom.MetaUpsertOutcome(metaSn, true));
    }

    /** 위탁 발급 — 원장에 (request_id → rawSn, issuedAt) 매핑이 남는다. */
    private LocalDateTime issue(String requestId, Long rawSn) {
        ledger.recordIssued(requestId, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn);
        return ledger.lookup(requestId).orElseThrow().issuedAt();
    }

    /** 지정한 생성시각을 가진 PENDING 마킹(작업자가 그 시각에 마킹을 만든 상황). */
    private LsMarking pendingMarkingCreatedAt(Long rawSn, LocalDateTime createdAt) {
        LsMarking m = LsMarking.createAuto(rawSn, "fire", 5, "/deid/clip.mp4",
                "[{\"frameIndex\":0}]", 1L);
        setField(m, LsMarking.class, "regDt", createdAt);
        return m;
    }

    private VlmResultRequest completed(String requestId) {
        return new VlmResultRequest(requestId, "completed",
                new VlmResultRequest.Results(new BigDecimal("0.8"), "rainy"), null);
    }

    // ───────────────────────── ① 위탁 이전 PENDING 은 전이된다 (레이스 해소 미회귀) ─────────────────────────

    @Test
    @DisplayName("위탁_발급_이전에_생성된_PENDING_마킹은_전이된다_콜백선행_레이스_해소_유지")
    void callbackTransitionsMarkingCreatedBeforeSubmit() {
        // given — 콜백이 ACK 보다 먼저 도착해 마킹이 아직 PENDING 인 정상 레이스 상황.
        LocalDateTime issuedAt = issue("K-L6-1", 700L);
        stubCompletedFlow(700L);
        LsMarking before = pendingMarkingCreatedAt(700L, issuedAt.minusMinutes(5));
        when(markingRepository.findByRawSnAndSttsCdIn(700L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(before));

        // when
        boolean applied = service.handle(completed("K-L6-1"));

        // then — 이 전이가 없으면 이후 스텝이 PENDING→VLM_REQUESTED 로 올려 영구 고착된다.
        assertThat(applied).isTrue();
        assertThat(before.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    // ───────────────────────── ② 위탁 이후 새로 생긴 PENDING 은 전이하지 않는다 (★L6 가드) ─────────────────────────

    @Test
    @DisplayName("위탁_발급_이후_새로_생긴_PENDING_마킹은_지각콜백이_전이시키지_않는다")
    void callbackDoesNotTransitionMarkingCreatedAfterSubmit() {
        // given — 앞선 위탁이 끝난 뒤 작업자가 다시 만든 마킹(= 아직 한 번도 위탁된 적 없다).
        LocalDateTime issuedAt = issue("K-L6-2", 701L);
        stubCompletedFlow(701L);
        LsMarking reMarked = pendingMarkingCreatedAt(701L, issuedAt.plusMinutes(5));
        when(markingRepository.findByRawSnAndSttsCdIn(701L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(reMarked));

        // when — 옛 request 의 지각 콜백이 도착한다.
        boolean applied = service.handle(completed("K-L6-2"));

        // then — 메타 적재는 정상 수행되지만 새 마킹은 손대지 않는다.
        //   ★ issuedAt 비교를 제거하면 여기서 RED — 분석되지 않은 마킹이 VLM_COMPLETED(허위 완료)가 된다.
        assertThat(applied).isTrue();
        assertThat(reMarked.getSttsCd())
                .as("위탁된 적 없는 마킹이 완료로 바뀌면 시계열 분석 없이 완료로 표시된다")
                .isEqualTo(LsMarking.STATUS_PENDING);
    }

    @Test
    @DisplayName("failed_지각콜백도_위탁_이후_새_PENDING_마킹을_실패로_만들지_않는다")
    void failedCallbackDoesNotTransitionMarkingCreatedAfterSubmit() {
        // given
        LocalDateTime issuedAt = issue("K-L6-3", 702L);
        LsMarking reMarked = pendingMarkingCreatedAt(702L, issuedAt.plusMinutes(5));
        when(markingRepository.findByRawSnAndSttsCdIn(702L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(reMarked));

        // when
        boolean applied = service.handle(new VlmResultRequest("K-L6-3", "failed", null,
                new VlmResultRequest.VlmError("INFERENCE_ERROR", "Video VLM inference failed")));

        // then — 아직 위탁도 안 된 새 작업이 실패로 보이면 안 된다.
        assertThat(applied).isTrue();
        assertThat(reMarked.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
    }

    @Test
    @DisplayName("같은_영상에_이전_PENDING과_이후_PENDING이_섞이면_이전_것만_전이된다")
    void onlyPreSubmitMarkingsAreTransitioned() {
        // given — 실제 운영 형태: 옛 마킹과 새 마킹이 같은 rawSn 에 함께 활성으로 존재한다.
        LocalDateTime issuedAt = issue("K-L6-4", 703L);
        stubCompletedFlow(703L);
        LsMarking before = pendingMarkingCreatedAt(703L, issuedAt.minusMinutes(1));
        LsMarking after = pendingMarkingCreatedAt(703L, issuedAt.plusMinutes(1));
        when(markingRepository.findByRawSnAndSttsCdIn(703L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(before, after));

        // when
        service.handle(completed("K-L6-4"));

        // then
        assertThat(before.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        assertThat(after.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
    }

    // ───────────────────────── ③ VLM_REQUESTED 는 항상 전이된다 ─────────────────────────

    @Test
    @DisplayName("VLM_REQUESTED_마킹은_생성시각과_무관하게_항상_전이된다_고착_방지")
    void vlmRequestedMarkingAlwaysTransitions() {
        // given — 생성시각이 발급 이후여도(시계 skew·재마킹 후 선커밋 등) 이 상태는 "이 위탁이 올린 것"이다.
        LocalDateTime issuedAt = issue("K-L6-5", 704L);
        stubCompletedFlow(704L);
        LsMarking requested = pendingMarkingCreatedAt(704L, issuedAt.plusMinutes(5));
        requested.markVlmRequested();
        when(markingRepository.findByRawSnAndSttsCdIn(704L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(requested));

        // when
        service.handle(completed("K-L6-5"));

        // then — 제외하면 VLM_REQUESTED 영구 고착(dead-lock)이 된다.
        assertThat(requested.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    @Test
    @DisplayName("failed_콜백에서도_VLM_REQUESTED_마킹은_항상_VLM_FAILED로_전이된다_고착해제")
    void vlmRequestedMarkingAlwaysTransitionsOnFailure() {
        LocalDateTime issuedAt = issue("K-L6-6", 705L);
        LsMarking requested = pendingMarkingCreatedAt(705L, issuedAt.plusMinutes(5));
        requested.markVlmRequested();
        when(markingRepository.findByRawSnAndSttsCdIn(705L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(requested));

        service.handle(new VlmResultRequest("K-L6-6", "failed", null,
                new VlmResultRequest.VlmError("INFERENCE_ERROR", "Video VLM inference failed")));

        assertThat(requested.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_FAILED);
    }

    private static void setField(Object target, Class<?> declaring, String name, Object value) {
        try {
            Field f = declaring.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
