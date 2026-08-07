package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepositoryCustom;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VLM <b>verify</b> 콜백 수신 서비스 단위 테스트 — 벤더 확정 계약(v2.0.1) 정합.
 *
 * <p>적재 규격(@req R4): {@code vlm.description}(검수큐 진입) + {@code vlm.accuracy}(화면 전용, 검수큐 미진입).
 * 재검수·통지(@req R13): 검수 완료(APPROVED) 영상의 서술이 <b>실제로 바뀐 경우에만</b> 검토행을 PENDING 으로
 * 되돌리고 {@code TASK_MODIFIED} 를 발행한다(동일값이면 no-op).
 */
@ExtendWith(MockitoExtension.class)
class VlmResultServiceTest {

    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataMetaReviewRepository reviewRepository;
    @Mock VideoRepository videoRepository;
    @Mock LsMarkingRepository markingRepository;
    @Mock ReviewApprovalGate approvalGate;
    @Mock ApplicationEventPublisher eventPublisher;
    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();

    private VlmResultService service;

    private final AtomicLong metaSnSeq = new AtomicLong(1000L);

    @BeforeEach
    void setup() {
        service = new VlmResultService(
                metaRepository, reviewRepository, videoRepository, ledger, markingRepository,
                approvalGate, eventPublisher);
        ledger.clear();
    }

    private static VlmResultRequest completed(String requestId, BigDecimal accuracy, String description) {
        return new VlmResultRequest(requestId, "completed",
                new VlmResultRequest.Results(accuracy, description), null);
    }

    private LsDataMeta metaRow(Long rawSn, String metaKey, String metaVl) {
        LsDataMeta m = LsDataMeta.create(rawSn, metaKey, metaVl);
        setField(m, LsDataMeta.class, "metaSn", metaSnSeq.incrementAndGet());
        return m;
    }

    /**
     * 신규 적재 시나리오 — 선행 조회는 empty(R13 이전값 없음)이고 <b>upsert 가 삽입을 보고</b>한다.
     * 검수큐 생성용 metaSn 은 재조회가 아니라 upsert 반환값에서 온다.
     */
    private LsDataMeta stubNewDescription(Long rawSn) {
        LsDataMeta saved = metaRow(rawSn, VlmResultService.META_KEY_DESCRIPTION, "any");
        when(metaRepository.findByRawSnAndMetaKey(rawSn, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.empty());
        stubUpsertOutcome(rawSn, saved.getMetaSn(), true);
        return saved;
    }

    // ───────────────────────── 적재 (@req R4) ─────────────────────────

    @Test
    @DisplayName("verify_완료콜백이면_description과_accuracy가_적재된다")
    void completed_persistsDescriptionAndAccuracy() {
        // given
        ledger.recordIssued("REQ-1", LsWebhookIdempotency.CHANNEL_VLM, "EXT-V", 200L);
        when(videoRepository.existsById(200L)).thenReturn(true);
        stubNewDescription(200L);

        // when
        boolean applied = service.handle(
                completed("REQ-1", new BigDecimal("0.8"), "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다."));

        // then
        assertThat(applied).isTrue();
        verify(metaRepository).upsertMetaReturning(200L, VlmResultService.META_KEY_DESCRIPTION,
                "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다.");
        verify(metaRepository).upsertMeta(200L, VlmResultService.META_KEY_ACCURACY, "0.8");
        assertThat(ledger.isProcessed("REQ-1")).isTrue();
    }

    @Test
    @DisplayName("검수큐에는_description_행만_진입한다")
    void reviewQueue_onlyDescriptionRow() {
        // given
        ledger.recordIssued("REQ-Q", LsWebhookIdempotency.CHANNEL_VLM, "EXT-Q", 210L);
        when(videoRepository.existsById(210L)).thenReturn(true);
        LsDataMeta saved = stubNewDescription(210L);

        // when
        service.handle(completed("REQ-Q", new BigDecimal("0.8"), "서술"));

        // then — accuracy 행은 검수큐에 딸려 들어가지 않는다(화이트리스트, @req R12)
        ArgumentCaptor<LsDataMetaReview> captor = ArgumentCaptor.forClass(LsDataMetaReview.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(captor.getValue().getDataMetaSn()).isEqualTo(saved.getMetaSn());
        assertThat(captor.getValue().getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);
        assertThat(captor.getValue().getMetaTypeCd()).isEqualTo(LsDataMetaReview.META_TYPE_VLM);
    }

    @Test
    @DisplayName("accuracy가_없으면_accuracy_행을_만들지_않는다")
    void accuracyAbsent_noAccuracyRow() {
        // given
        ledger.recordIssued("REQ-NA", LsWebhookIdempotency.CHANNEL_VLM, "EXT-NA", 211L);
        when(videoRepository.existsById(211L)).thenReturn(true);
        stubNewDescription(211L);

        // when
        service.handle(completed("REQ-NA", null, "서술"));

        // then — 빈 문자열·placeholder 저장 금지
        verify(metaRepository).upsertMetaReturning(211L, VlmResultService.META_KEY_DESCRIPTION, "서술");
        verify(metaRepository, never())
                .upsertMeta(eq(211L), eq(VlmResultService.META_KEY_ACCURACY), anyString());
    }

    @Test
    @DisplayName("accuracy가_0이나_1이면_정상_적재된다")
    void accuracyBoundary_persisted() {
        ledger.recordIssued("REQ-B0", LsWebhookIdempotency.CHANNEL_VLM, "EXT-B0", 212L);
        when(videoRepository.existsById(212L)).thenReturn(true);
        stubNewDescription(212L);

        service.handle(completed("REQ-B0", BigDecimal.ZERO, "서술"));

        verify(metaRepository).upsertMeta(212L, VlmResultService.META_KEY_ACCURACY, "0");
    }

    @Test
    @DisplayName("레거시_구간키_행이_있어도_새_키로_적재되고_기존_행은_남는다")
    void legacySegmentRows_preserved() {
        // given — 구 describe 규격으로 적재된 "0-8"/"8-16" 행이 이미 있는 영상
        ledger.recordIssued("REQ-LEG", LsWebhookIdempotency.CHANNEL_VLM, "EXT-LG", 213L);
        when(videoRepository.existsById(213L)).thenReturn(true);
        stubNewDescription(213L);

        // when
        service.handle(completed("REQ-LEG", new BigDecimal("0.8"), "서술"));

        // then — 레거시 키는 조회·수정·삭제 대상이 아니다(마이그레이션 금지)
        verify(metaRepository, never()).delete(any());
        verify(metaRepository, never()).deleteAll(anyList());
        verify(metaRepository, never()).upsertMeta(eq(213L), eq("0-8"), anyString());
        verify(metaRepository, never()).upsertMeta(eq(213L), eq("8-16"), anyString());
    }

    // ───────────────────────── 게이트·멱등 (회귀) ─────────────────────────

    @Test
    @DisplayName("failed_콜백이면_적재없이_error만_기록된다")
    void failed_recordsErrorWithoutAppend() {
        ledger.recordIssued("REQ-F", LsWebhookIdempotency.CHANNEL_VLM, "EXT-F", 300L);

        boolean applied = service.handle(new VlmResultRequest(
                "REQ-F", "failed", null,
                new VlmResultRequest.VlmError("INFERENCE_ERROR", "Video VLM inference failed")));

        assertThat(applied).isTrue();
        verify(metaRepository, never()).upsertMeta(any(), anyString(), anyString());
        verify(metaRepository, never()).upsertMetaReturning(any(), anyString(), anyString());
        verify(reviewRepository, never()).save(any());
        verify(videoRepository, never()).existsById(any());
        assertThat(ledger.isProcessed("REQ-F")).isTrue();
    }

    @Test
    @DisplayName("미발급_request_id_콜백은_UNAUTHORIZED_거부")
    void unknownRequestId_throws401() {
        assertThatThrownBy(() -> service.handle(
                completed("REQ-UNKNOWN", new BigDecimal("0.8"), "서술")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(videoRepository, never()).existsById(any());
    }

    @Test
    @DisplayName("동일_request_id_재수신은_멱등_스킵된다")
    void replay_returnsFalseWithoutSave() {
        ledger.recordIssued("REQ-DUP", LsWebhookIdempotency.CHANNEL_VLM, "EXT-D", 201L);
        when(videoRepository.existsById(201L)).thenReturn(true);
        stubNewDescription(201L);

        assertThat(service.handle(completed("REQ-DUP", new BigDecimal("0.8"), "서술"))).isTrue();
        assertThat(service.handle(completed("REQ-DUP", new BigDecimal("0.8"), "서술"))).isFalse();
    }

    @Test
    @DisplayName("동시_콜백은_원장_비관적_락으로_직렬화된다")
    void concurrentCallbacks_serializedByPessimisticLedgerLock() {
        // given — 락 없는 조회(lookup)가 아니라 비관적 락 조회(lookupForProcessing)를 써야 직렬화된다.
        WebhookIdempotencyLedger lockingLedger = org.mockito.Mockito.mock(WebhookIdempotencyLedger.class);
        VlmResultService svc = new VlmResultService(
                metaRepository, reviewRepository, videoRepository, lockingLedger, markingRepository,
                approvalGate, eventPublisher);
        when(lockingLedger.lookupForProcessing("REQ-LOCK")).thenReturn(Optional.of(
                new WebhookIdempotencyLedger.Entry(
                        WebhookIdempotencyLedger.State.ISSUED, "EXT-L", 214L, null)));
        when(videoRepository.existsById(214L)).thenReturn(true);
        stubNewDescription(214L);

        // when
        svc.handle(completed("REQ-LOCK", new BigDecimal("0.8"), "서술"));

        // then
        verify(lockingLedger).lookupForProcessing("REQ-LOCK");
        verify(lockingLedger, never()).lookup(anyString());
    }

    @Test
    @DisplayName("발급됐으나_rawSn_매핑_없으면_UNAUTHORIZED_거부")
    void issuedButNoRawSnMapping_throws401() {
        ledger.recordIssued("REQ-NORAW", "EXT-N");

        assertThatThrownBy(() -> service.handle(
                completed("REQ-NORAW", new BigDecimal("0.8"), "서술")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("미지_status_콜백은_INVALID_INPUT_거부되고_멱등마킹도_안한다")
    void unknownStatus_rejectedWithoutMisprocessing() {
        ledger.recordIssued("REQ-BADSTS", LsWebhookIdempotency.CHANNEL_VLM, "EXT-BS", 205L);
        lenient().when(videoRepository.existsById(205L)).thenReturn(true);

        assertThatThrownBy(() -> service.handle(new VlmResultRequest(
                "REQ-BADSTS", "processing",
                new VlmResultRequest.Results(new BigDecimal("0.8"), "서술"), null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(metaRepository, never()).upsertMeta(any(), anyString(), anyString());
        verify(metaRepository, never()).upsertMetaReturning(any(), anyString(), anyString());
        assertThat(ledger.isProcessed("REQ-BADSTS")).isFalse();
    }

    @Test
    @DisplayName("검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백된다")
    void sideEffectFailure_doesNotMarkProcessed() {
        ledger.recordIssued("REQ-ATOMIC", LsWebhookIdempotency.CHANNEL_VLM, "EXT-A", 203L);
        when(videoRepository.existsById(203L)).thenReturn(true);
        stubNewDescription(203L);
        when(reviewRepository.save(any())).thenThrow(new RuntimeException("검수큐 적재 실패"));

        assertThatThrownBy(() -> service.handle(completed("REQ-ATOMIC", new BigDecimal("0.8"), "서술")))
                .isInstanceOf(RuntimeException.class);
        assertThat(ledger.isProcessed("REQ-ATOMIC")).isFalse();
    }

    @Test
    @DisplayName("completed인데_영상이_없으면_NOT_FOUND")
    void completedButVideoMissing_throws404() {
        ledger.recordIssued("REQ-NOVID", LsWebhookIdempotency.CHANNEL_VLM, "EXT-NV", 209L);
        when(videoRepository.existsById(209L)).thenReturn(false);

        assertThatThrownBy(() -> service.handle(completed("REQ-NOVID", new BigDecimal("0.8"), "서술")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ───────────────────────── 재검수 + 통지 (@req R13) ─────────────────────────

    /** 기존 description 행이 있는 상태(재위탁 수신) 스텁 — upsert 는 <b>갱신</b>을 보고한다. */
    private LsDataMeta stubExistingDescription(Long rawSn, String previousValue) {
        LsDataMeta existing = metaRow(rawSn, VlmResultService.META_KEY_DESCRIPTION, previousValue);
        when(metaRepository.findByRawSnAndMetaKey(rawSn, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.of(existing));
        stubUpsertOutcome(rawSn, existing.getMetaSn(), false);
        return existing;
    }

    private void stubVideoApproved(Long rawSn, boolean approved) {
        when(approvalGate.isApproved(rawSn)).thenReturn(approved);
    }

    private LsDataMetaReview approvedReviewOf(LsDataMeta meta, Long rawSn) {
        LsDataMetaReview review = LsDataMetaReview.createAuto(
                meta.getMetaSn(), rawSn, null,
                LsDataMetaReview.META_TYPE_VLM, LsDataMetaReview.SRC_AI_SERVER,
                LsDataMetaReview.STTS_PENDING);
        review.approve("1", java.time.LocalDateTime.now());
        return review;
    }

    @Test
    @DisplayName("승인된_영상의_서술이_바뀌면_검토상태가_PENDING으로_되돌아간다")
    void approvedVideoDescriptionChanged_reopensReview() {
        // given — 검수 완료된 영상에 새 서술이 도착
        ledger.recordIssued("REQ-R13A", LsWebhookIdempotency.CHANNEL_VLM, "EXT-R1", 400L);
        when(videoRepository.existsById(400L)).thenReturn(true);
        LsDataMeta existing = stubExistingDescription(400L, "이전 서술");
        stubVideoApproved(400L, true);
        LsDataMetaReview review = approvedReviewOf(existing, 400L);
        when(reviewRepository.findByDataMetaSnIn(List.of(existing.getMetaSn())))
                .thenReturn(List.of(review));

        // when
        service.handle(completed("REQ-R13A", new BigDecimal("0.9"), "새 서술"));

        // then — REVIEWER 승인 없이 새 서술이 관제로 나가지 않도록 재승인을 강제한다
        assertThat(review.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);
        assertThat(review.getRvwDt()).isNull();
        verify(reviewRepository, never()).save(any()); // 신규 검수행 생성 아님
    }

    @Test
    @DisplayName("승인된_영상의_서술이_바뀌면_TASK_MODIFIED가_발행된다")
    void approvedVideoDescriptionChanged_publishesTaskModified() {
        ledger.recordIssued("REQ-R13B", LsWebhookIdempotency.CHANNEL_VLM, "EXT-R2", 401L);
        when(videoRepository.existsById(401L)).thenReturn(true);
        LsDataMeta existing = stubExistingDescription(401L, "이전 서술");
        stubVideoApproved(401L, true);
        when(reviewRepository.findByDataMetaSnIn(List.of(existing.getMetaSn())))
                .thenReturn(List.of(approvedReviewOf(existing, 401L)));

        service.handle(completed("REQ-R13B", null, "새 서술"));

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().rawSn()).isEqualTo(401L);
        assertThat(captor.getValue().srcSn()).isNull();          // 영상 단위 변경
        assertThat(captor.getValue().changeType()).isEqualTo(ChangeType.META_UPDATED);
        // @req R10 — 서술은 이제 export JSON(video.vd_description) 의 입력이다. regen=false 로 두면
        //   통지만 나가고 산출 폴더는 옛 서술로 남는다("저장은 됐는데 산출물이 안 바뀐다").
        assertThat(captor.getValue().exportRegenerated()).isTrue();
        // Phase 7a-1 — exclude: R13 은 항목 단위(LsDataMetaReview) 재검토 축을 이미 갖고 있어
        //   영상 단위 재검토 표시(needsRecheck) 대상이 아니다.
        assertThat(captor.getValue().needsRecheck()).isFalse();
    }

    @Test
    @DisplayName("서술이_바뀌면_재승인시_산출물이_갱신된다")
    void 서술이_바뀌면_재승인시_산출물이_갱신된다() {
        // given — 승인 완료 영상에 새 서술이 도착(재위탁 결과)
        ledger.recordIssued("REQ-R10A", LsWebhookIdempotency.CHANNEL_VLM, "EXT-R10", 404L);
        when(videoRepository.existsById(404L)).thenReturn(true);
        LsDataMeta existing = stubExistingDescription(404L, "이전 서술");
        stubVideoApproved(404L, true);
        when(reviewRepository.findByDataMetaSnIn(List.of(existing.getMetaSn())))
                .thenReturn(List.of(approvedReviewOf(existing, 404L)));

        // when
        service.handle(completed("REQ-R10A", null, "갱신된 서술"));

        // then — 디바운스 flush 가 export 를 전량 재생성한 뒤 통지하도록 regen=true 로 발행한다.
        //   (해시 편입만으로는 부족하다 — 재생성 트리거 자체가 없으면 아무 일도 일어나지 않는다.)
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().exportRegenerated()).isTrue();
    }

    @Test
    @DisplayName("서술이_동일하면_재검수도_통지도_하지_않는다")
    void sameDescription_isNoOp() {
        ledger.recordIssued("REQ-R13C", LsWebhookIdempotency.CHANNEL_VLM, "EXT-R3", 402L);
        when(videoRepository.existsById(402L)).thenReturn(true);
        LsDataMeta existing = stubExistingDescription(402L, "동일 서술");

        service.handle(completed("REQ-R13C", new BigDecimal("0.8"), "동일 서술"));

        // 값은 멱등 upsert 되지만 재검수·통지는 없다(폭주 방지)
        verify(metaRepository).upsertMetaReturning(402L, VlmResultService.META_KEY_DESCRIPTION, "동일 서술");
        verify(reviewRepository, never()).findByDataMetaSnIn(anyList());
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
        assertThat(existing.getMetaVl()).isEqualTo("동일 서술");
    }

    @Test
    @DisplayName("미승인_영상의_재수신은_재검수_대상이_아니다")
    void notApprovedVideo_isNotRecheckTarget() {
        ledger.recordIssued("REQ-R13D", LsWebhookIdempotency.CHANNEL_VLM, "EXT-R4", 403L);
        when(videoRepository.existsById(403L)).thenReturn(true);
        stubExistingDescription(403L, "이전 서술");
        stubVideoApproved(403L, false);

        service.handle(completed("REQ-R13D", new BigDecimal("0.8"), "새 서술"));

        verify(reviewRepository, never()).findByDataMetaSnIn(anyList());
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("신규_적재는_재검수_대상이_아니다_이미_PENDING으로_새로_들어간다")
    void newDescription_isNotRecheckTarget() {
        ledger.recordIssued("REQ-R13E", LsWebhookIdempotency.CHANNEL_VLM, "EXT-R5", 404L);
        when(videoRepository.existsById(404L)).thenReturn(true);
        stubNewDescription(404L);

        service.handle(completed("REQ-R13E", new BigDecimal("0.8"), "서술"));

        verify(reviewRepository).save(any());
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    // ──────────── 재위탁 동시 콜백 · 검수행 단일 생성 (@req R4, CWE-362) ────────────

    /**
     * upsert 반환값 스텁 — <b>삽입/갱신 판정의 단일 원천</b>. 실제 구현은 {@code ON CONFLICT ... RETURNING}
     * 이 한 문장 안에서 판정하므로 동시 실행에서도 정확히 한 쪽만 {@code inserted=true} 를 받는다.
     */
    private void stubUpsertOutcome(Long rawSn, Long metaSn, boolean inserted) {
        when(metaRepository.upsertMetaReturning(
                eq(rawSn), eq(VlmResultService.META_KEY_DESCRIPTION), anyString()))
                .thenReturn(new LsDataMetaRepositoryCustom.MetaUpsertOutcome(metaSn, inserted));
    }

    @Test
    @DisplayName("재위탁_동시_콜백에도_검수행은_1건만_생성된다")
    void retriedConcurrentCallbacks_createSingleReviewRow() {
        // given — 재위탁으로 request_id 가 서로 다른 두 콜백(원장 비관적 락이 걸리지 않는 축).
        //   둘 다 upsert 이전 SELECT 로는 "없음"을 관측하지만, DB 는 한 쪽만 삽입하고 다른 쪽은 갱신한다.
        ledger.recordIssued("REQ-RT1", LsWebhookIdempotency.CHANNEL_VLM, "EXT-RT1", 500L);
        ledger.recordIssued("REQ-RT2", LsWebhookIdempotency.CHANNEL_VLM, "EXT-RT2", 500L);
        when(videoRepository.existsById(500L)).thenReturn(true);
        when(metaRepository.findByRawSnAndMetaKey(500L, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.empty());
        when(metaRepository.upsertMetaReturning(
                eq(500L), eq(VlmResultService.META_KEY_DESCRIPTION), anyString()))
                .thenReturn(new LsDataMetaRepositoryCustom.MetaUpsertOutcome(7001L, true))
                .thenReturn(new LsDataMetaRepositoryCustom.MetaUpsertOutcome(7001L, false));
        stubVideoApproved(500L, false);

        // when — 두 콜백이 순차 커밋(동시 실행의 관측 가능한 결과와 동치)
        service.handle(completed("REQ-RT1", new BigDecimal("0.8"), "서술"));
        service.handle(completed("REQ-RT2", new BigDecimal("0.8"), "서술"));

        // then — 검수큐 PENDING 행은 1건뿐. 2건이면 승인 시 둘 다 APPROVED 가 되어
        //   메타 단위 뷰(V_COMPLETED_META)로 같은 메타가 관제에 2건 나간다.
        verify(reviewRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("갱신_판정은_별도_조회가_아니라_upsert_반환값을_따른다")
    void insertJudgementFollowsUpsertReturnValue_notPrecedingSelect() {
        // given(A) — 선행 SELECT 는 "없음"(신규로 보임)인데 upsert 는 갱신이었다(경합에서 진 쪽)
        ledger.recordIssued("REQ-J1", LsWebhookIdempotency.CHANNEL_VLM, "EXT-J1", 501L);
        when(videoRepository.existsById(501L)).thenReturn(true);
        when(metaRepository.findByRawSnAndMetaKey(501L, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.empty());
        stubUpsertOutcome(501L, 7010L, false);
        stubVideoApproved(501L, false);

        // when
        service.handle(completed("REQ-J1", null, "서술"));

        // then — 선행 SELECT 를 믿었다면 여기서 중복 검수행이 생긴다
        verify(reviewRepository, never()).save(any());

        // given(B) — 반대 방향: 선행 SELECT 는 "있음"인데 upsert 는 삽입이었다(앞 트랜잭션 롤백 등)
        ledger.recordIssued("REQ-J2", LsWebhookIdempotency.CHANNEL_VLM, "EXT-J2", 502L);
        when(videoRepository.existsById(502L)).thenReturn(true);
        LsDataMeta stale = metaRow(502L, VlmResultService.META_KEY_DESCRIPTION, "옛 서술");
        when(metaRepository.findByRawSnAndMetaKey(502L, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.of(stale));
        stubUpsertOutcome(502L, 7011L, true);

        // when
        service.handle(completed("REQ-J2", null, "서술"));

        // then — 삽입이면 검수행을 만든다. 그 metaSn 도 upsert 가 돌려준 값이어야 한다(재조회 아님).
        ArgumentCaptor<LsDataMetaReview> captor = ArgumentCaptor.forClass(LsDataMetaReview.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().getDataMetaSn()).isEqualTo(7011L);
        // 삽입 경로는 R13(재검수) 대상이 아니다 — 이미 PENDING 으로 새로 들어간다
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("경합으로_이전값을_모르면_보수적으로_재검수한다")
    void lostRaceWithUnknownPreviousValue_rechecksConservatively() {
        // given — 갱신(inserted=false)인데 선행 SELECT 가 empty 라 "옛 값"을 알 수 없는 경합 상황.
        //   승인 완료된 영상이므로 승인 없이 새 서술이 관제로 나가는 것을 막아야 한다.
        ledger.recordIssued("REQ-RACE", LsWebhookIdempotency.CHANNEL_VLM, "EXT-RC", 503L);
        when(videoRepository.existsById(503L)).thenReturn(true);
        when(metaRepository.findByRawSnAndMetaKey(503L, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.empty());
        stubUpsertOutcome(503L, 7020L, false);
        stubVideoApproved(503L, true);
        LsDataMeta metaOfRow = metaRow(503L, VlmResultService.META_KEY_DESCRIPTION, "무관");
        setField(metaOfRow, LsDataMeta.class, "metaSn", 7020L);
        LsDataMetaReview review = approvedReviewOf(metaOfRow, 503L);
        when(reviewRepository.findByDataMetaSnIn(List.of(7020L))).thenReturn(List.of(review));

        // when
        service.handle(completed("REQ-RACE", null, "새 서술"));

        // then — 값이 실제로 바뀌었는지 알 수 없으면 "바뀐 것으로" 본다(fail-safe).
        //   재검토 1회가 미승인 서술의 관제 유출보다 안전하다.
        assertThat(review.getRvwSttsCd()).isEqualTo(LsDataMetaReview.STTS_PENDING);
        verify(eventPublisher).publishEvent(any(TaskModifiedEvent.class));
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
