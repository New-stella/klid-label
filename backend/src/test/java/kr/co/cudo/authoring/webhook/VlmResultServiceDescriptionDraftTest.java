package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
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
import kr.co.cudo.authoring.webhook.service.IsolatedTimeseriesDraftApplier;
import kr.co.cudo.authoring.webhook.service.TimeseriesResultApplier;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 묘사(describe) 콜백이 이벤트 어노테이션 초안까지 채우는지에 대한 회귀 가드.
 * [design: SEQ-023] [design: CDIAG-014]
 *
 * <p>고정하는 것은 <b>둘 다 순서·격리라 리팩토링으로 조용히 깨지는</b> 성질이다.
 * <ol>
 *   <li><b>부르는 자리</b> — 주 축(시계열 서술) 적재 <b>뒤</b>, 마킹 전이 <b>앞</b>. 초안 조달이 활성
 *       마킹에서 질문을 읽으므로 전이 뒤에 부르면 조달이 한 단계 어긋난다.</li>
 *   <li><b>실패 격리</b> — 초안 채움이 실패해도 서술 적재와 멱등 마킹은 살아남는다. 예외를 삼키는
 *       것만으로는 부족하고(합류한 트랜잭션은 rollback-only 로 표시된다) 별도 트랜잭션 경계가
 *       필요하다 — 그래서 전파 속성 자체를 구조로 고정한다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VlmResultServiceDescriptionDraftTest {

    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataMetaReviewRepository reviewRepository;
    @Mock VideoRepository videoRepository;
    @Mock LsMarkingRepository markingRepository;
    @Mock ReviewApprovalGate approvalGate;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TimeseriesResultApplier resultApplier;
    @Mock IsolatedTimeseriesDraftApplier isolatedDraftApplier;

    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();
    private static final AtomicLong META_SN_SEQ = new AtomicLong(5000L);

    private VlmResultService service;

    @BeforeEach
    void setup() {
        service = new VlmResultService(
                metaRepository, reviewRepository, videoRepository, ledger, markingRepository,
                approvalGate, eventPublisher, resultApplier, isolatedDraftApplier);
        ledger.clear();
    }

    private static VlmResultRequest completed(String requestId, String description) {
        return new VlmResultRequest(requestId, "completed",
                new VlmResultRequest.Results(description), null);
    }

    /** 신규 적재 흐름 — 선행 조회는 empty 이고 upsert 가 삽입을 보고한다. */
    private void stubNewDescription(Long rawSn) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        long metaSn = META_SN_SEQ.incrementAndGet();
        when(metaRepository.findByRawSnAndMetaKey(rawSn, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.empty());
        when(metaRepository.upsertMetaReturning(
                eq(rawSn), eq(VlmResultService.META_KEY_DESCRIPTION), anyString()))
                .thenReturn(new LsDataMetaRepositoryCustom.MetaUpsertOutcome(metaSn, true));
    }

    private LsMarking requestedMarking(Long rawSn) {
        LsMarking m = LsMarking.createAuto(rawSn, "fire", 5,
                "raw/path.mp4", "[{\"frameIndex\":0}]", 1L);
        m.markVlmRequested();
        return m;
    }

    // ───────────────────── AC1 · AC2 — 부르는가 / 언제 부르는가 ─────────────────────

    @Test
    @DisplayName("★묘사_콜백은_시계열_서술_적재와_어노테이션_초안_채움을_모두_수행한다")
    void describeCallback_persistsTimeseriesAndDraftsAnnotation() {
        // given
        ledger.recordIssued("REQ-D1", LsWebhookIdempotency.CHANNEL_VLM, "EXT-D1", 900L);
        stubNewDescription(900L);
        String description = "- 장소: 주택가 골목\n- 상황: 한 남성이 쓰러져 있습니다.";

        // when
        boolean applied = service.handle(completed("REQ-D1", description));

        // then — 주 축(시계열 서술)과 초안 축이 같은 결과로 둘 다 채워진다.
        assertThat(applied).isTrue();
        verify(metaRepository).upsertMetaReturning(
                900L, VlmResultService.META_KEY_DESCRIPTION, description);
        verify(isolatedDraftApplier).applyDescription(900L, description);
        // 추가 질문 축 계약은 묘사 콜백이 부르지 않는다(두 축이 같은 칸을 쓰면 한쪽이 유실된다).
        verify(resultApplier, never()).applySubDescription(anyLong(), anyString());
        // ★격리 경계를 우회해 협력자를 직접 부르면 위 전파 속성이 아무것도 막지 못한다.
        verify(resultApplier, never()).applyDescription(anyLong(), anyString());
    }

    @Test
    @DisplayName("★초안_채움은_시계열_적재_뒤_마킹_전이_앞에_일어난다")
    void draft_happensAfterPersist_andBeforeMarkingTransition() {
        // given — 위탁으로 VLM_REQUESTED 가 된 활성 마킹이 하나 있다.
        ledger.recordIssued("REQ-D2", LsWebhookIdempotency.CHANNEL_VLM, "EXT-D2", 901L);
        stubNewDescription(901L);
        LsMarking marking = requestedMarking(901L);
        when(markingRepository.findByRawSnAndSttsCdIn(901L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(marking));

        // 초안 조달은 "그 영상의 활성 마킹"에서 질문을 읽는다 — 부르는 시점에 마킹이 아직 활성이어야
        // 한다. 호출 순서만 보면 조달이 실제로 무엇을 관측하는지는 고정되지 않으므로 상태를 직접 본다.
        doAnswer(inv -> {
            assertThat(marking.getSttsCd())
                    .as("초안 조달 시점에 마킹은 아직 활성(위탁 중)이어야 한다")
                    .isEqualTo(LsMarking.STATUS_VLM_REQUESTED);
            return true;
        }).when(isolatedDraftApplier).applyDescription(eq(901L), anyString());

        // when
        service.handle(completed("REQ-D2", "- 상황: 연기가 보입니다."));

        // then — 순서: 시계열 upsert → 초안 → 마킹 조회·전이
        InOrder order = inOrder(metaRepository, isolatedDraftApplier, markingRepository);
        order.verify(metaRepository).upsertMetaReturning(
                eq(901L), eq(VlmResultService.META_KEY_DESCRIPTION), anyString());
        order.verify(isolatedDraftApplier).applyDescription(eq(901L), anyString());
        order.verify(markingRepository).findByRawSnAndSttsCdIn(901L, LsMarking.ACTIVE_STATUSES);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
    }

    // ───────────────────── AC3 — 추가 질문 축은 종전 그대로 ─────────────────────

    @Test
    @DisplayName("★추가질문_콜백은_묘사축_초안을_부르지_않고_마킹도_전이시키지_않는다")
    void subCallback_unchanged() {
        // given
        ledger.recordIssued("REQ-S1", LsWebhookIdempotency.CHANNEL_VLM_SUB, "EXT-S1", 902L);
        when(videoRepository.existsById(902L)).thenReturn(true);

        // when
        boolean applied = service.handle(completed("REQ-S1", "네, 근거는 ..."));

        // then
        assertThat(applied).isTrue();
        verify(resultApplier).applySubDescription(902L, "네, 근거는 ...");
        verify(isolatedDraftApplier, never()).applyDescription(anyLong(), anyString());
        verify(markingRepository, never()).findByRawSnAndSttsCdIn(anyLong(), anyList());
        verify(metaRepository, never()).upsertMetaReturning(anyLong(), anyString(), anyString());
    }

    // ───────────────────── AC4 · AC5 — 실패·미채움 격리 ─────────────────────

    @Test
    @DisplayName("★초안_채움이_예외를_던져도_시계열_적재와_멱등_마킹은_살아남는다")
    void draftFailure_doesNotRollBackMainAxis() {
        // given
        ledger.recordIssued("REQ-D3", LsWebhookIdempotency.CHANNEL_VLM, "EXT-D3", 903L);
        stubNewDescription(903L);
        LsMarking marking = requestedMarking(903L);
        when(markingRepository.findByRawSnAndSttsCdIn(903L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(marking));
        doThrow(new IllegalStateException("초안 저장 실패"))
                .when(isolatedDraftApplier).applyDescription(anyLong(), anyString());

        // when — 콜백은 실패하지 않는다.
        boolean applied = service.handle(completed("REQ-D3", "- 상황: 물이 차오릅니다."));

        // then — 주 축은 그대로 적재되고 흐름도 끝까지 간다.
        assertThat(applied).isTrue();
        verify(metaRepository).upsertMetaReturning(
                eq(903L), eq(VlmResultService.META_KEY_DESCRIPTION), anyString());
        verify(reviewRepository).save(org.mockito.ArgumentMatchers.any());
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        assertThat(ledger.isProcessed("REQ-D3")).isTrue();
    }

    @Test
    @DisplayName("초안을_채우지_못해_false를_돌려줘도_흐름은_정상_진행된다")
    void draftNotFilled_flowContinues() {
        // given — 사람이 이미 손댔거나 「상황」 줄이 없어 채우지 않은 경우(정상 반환값)
        ledger.recordIssued("REQ-D4", LsWebhookIdempotency.CHANNEL_VLM, "EXT-D4", 904L);
        stubNewDescription(904L);
        LsMarking marking = requestedMarking(904L);
        when(markingRepository.findByRawSnAndSttsCdIn(904L, LsMarking.ACTIVE_STATUSES))
                .thenReturn(List.of(marking));
        when(isolatedDraftApplier.applyDescription(anyLong(), anyString())).thenReturn(false);

        // when
        boolean applied = service.handle(completed("REQ-D4", "- 장소: 교차로"));

        // then
        assertThat(applied).isTrue();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        assertThat(ledger.isProcessed("REQ-D4")).isTrue();
    }

    // ───────────────────── 격리의 구조 고정 ─────────────────────

    @Test
    @DisplayName("★초안_반영은_새_트랜잭션에서_돈다_합류하면_예외를_삼켜도_커밋이_통째로_터진다")
    void draftBoundary_runsInNewTransaction() throws NoSuchMethodException {
        Method m = IsolatedTimeseriesDraftApplier.class
                .getMethod("applyDescription", Long.class, String.class);
        Transactional tx = m.getAnnotation(Transactional.class);

        assertThat(tx)
                .as("격리 경계에는 트랜잭션 경계가 있어야 한다")
                .isNotNull();
        assertThat(tx.propagation())
                .as("합류(REQUIRED)하면 협력자의 실패가 콜백 트랜잭션을 rollback-only 로 표시해 "
                        + "예외를 삼켜도 커밋 시점에 통째로 터진다")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
