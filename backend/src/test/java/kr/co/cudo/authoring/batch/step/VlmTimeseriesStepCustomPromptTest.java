package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import kr.co.cudo.authoring.aiserver.service.AiSrvrSelector;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.evntanno.service.MarkingSelectedQuestionReader;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionResponse;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★ 추가 질문 축 전환(→ {@code custom}) 계약 고정 — 질문 문구 조달·전송·보관.
 *
 * <p>여기서 고정하는 것은 넷이다:
 * <ol>
 *   <li><b>{@code prompt} 가 실제로 채워진다</b> — 조달은 판정기 한 곳에 위임하고, 마킹이 고른
 *       질문 일련번호는 기존 읽기 경로에서 가져온다(판정 <b>복제 금지</b>).</li>
 *   <li><b>보낸 문구가 원장에 그대로 보관된다</b> — 콜백이 재조달하지 않는 근거.
 *       재조달하면 질문 목록 전체 교체(정상 동선) 사이에 보낸 값과 기록된 값이 갈린다.</li>
 *   <li><b>{@code event_type} 2순위</b> — 관제 인입값이 없으면 마킹이 고른 유형을 쓰고,
 *       인입값이 있으면 마킹 선택값을 쓰지 않는다. 둘 다 없으면 {@code null} 그대로.</li>
 *   <li><b>축 사이 격리</b> — 추가 질문 축은 이벤트 유형을 싣지 않고, 두 축의 상관키는 다르다.</li>
 * </ol>
 */
class VlmTimeseriesStepCustomPromptTest {

    private static final Long RAW_SN = 900L;
    private static final String QUESTION = "화재가 발생했는가? 그렇게 판단한 근거를 서술하라.";

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private IngestSourceRepository ingestSourceRepository;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private VerificationEventQuestionResolver questionResolver;
    private MarkingSelectedQuestionReader questionReader;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        questionResolver = mock(VerificationEventQuestionResolver.class);
        questionReader = mock(MarkingSelectedQuestionReader.class);
        step = new VlmTimeseriesStep(vlmClient, videoRepository, ingestSourceRepository,
                mock(BatchStatusService.class), ledger, deidentProcLogRepository,
                mock(DeidentReportGate.class), mock(VlmMarkingTxService.class),
                mock(VlmSubmitOutcomeRecorder.class), mock(VlmTimeseriesMetaPresence.class),
                new ObjectMapper(), Schedulers.immediate(), mock(VlmDefaultSkipMarker.class),
                mock(AiSrvrSelector.class), questionResolver, questionReader);

        when(videoRepository.existsById(RAW_SN)).thenReturn(true);
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn("/data/deid/" + RAW_SN + ".mp4");
        lenient().when(deidentProcLogRepository.findLatestSuccessByDataRawSn(RAW_SN))
                .thenReturn(Optional.of(plog));
        lenient().when(vlmClient.submitDescribe(any(VlmTimeseriesRequest.class), any()))
                .thenAnswer(inv -> Mono.just(new VlmTimeseriesResponse(
                        ((VlmTimeseriesRequest) inv.getArgument(0)).requestId(), "accepted")));
        lenient().when(vlmClient.submitDescribeSub(any(VlmTimeseriesRequest.class), any()))
                .thenReturn(Mono.never());
    }

    /** 관제 인입값 스텁 — {@code null} 이면 「미수신 영상」. */
    private void ingestEventType(String value) {
        IngestSourceRow row = mock(IngestSourceRow.class);
        lenient().when(row.getVrfcEvntTypeCd()).thenReturn(value);
        lenient().when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(row);
    }

    private void resolverReturns(String text) {
        lenient().when(questionResolver.resolveQuestionText(any(), any()))
                .thenReturn(Optional.ofNullable(text));
    }

    private LsMarking marking(String selectedTypeCd) {
        LsMarking m = LsMarking.createManual(RAW_SN, "[{\"frameIndex\":3,\"timestamp\":\"00:01\"}]", "1");
        m.applySelectedEventType(selectedTypeCd);
        return m;
    }

    private VlmTimeseriesRequest captureCustom() {
        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitDescribeSub(captor.capture(), any());
        return captor.getValue();
    }

    private VlmTimeseriesRequest captureDescribe() {
        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitDescribe(captor.capture(), any());
        return captor.getValue();
    }

    // ───────────────────────── prompt 조달·전송 ─────────────────────────

    @Test
    @DisplayName("★마킹에서_고른_질문이_그대로_prompt로_나가고_이벤트유형은_싣지_않는다")
    void selectedQuestionBecomesPrompt() {
        ingestEventType("fire");
        when(questionReader.findSelectedQuestionSn(RAW_SN)).thenReturn(77L);
        resolverReturns(QUESTION);

        step.runWithMarking(RAW_SN, marking(null));

        VlmTimeseriesRequest custom = captureCustom();
        assertThat(custom.prompt()).isEqualTo(QUESTION);
        assertThat(custom.eventType()).isNull();
        // 묘사 축은 종전대로 이벤트 유형을 싣는다.
        assertThat(captureDescribe().eventType()).isEqualTo("fire");
    }

    @Test
    @DisplayName("★조달_판정을_복제하지_않는다_선택질문SN과_조달된_이벤트유형을_그대로_판정기에_넘긴다")
    void delegatesResolutionToSingleSourceOfTruth() {
        ingestEventType(null);
        when(questionReader.findSelectedQuestionSn(RAW_SN)).thenReturn(42L);
        resolverReturns(QUESTION);

        step.runWithMarking(RAW_SN, marking("fall"));

        // 판정기가 「소속 검증 → 아니면 첫 번째」를 소유한다. 배치는 두 입력을 잇기만 한다.
        verify(questionResolver).resolveQuestionText(eq(42L), eq("fall"));
        verify(questionReader).findSelectedQuestionSn(RAW_SN);
        // ★ 「첫 번째로 되돌린다」를 배치가 직접 부르면 그 순간 판정이 두 곳이 된다.
        //   되돌림은 resolve 계약 안에서만 일어나야 한다.
        verify(questionResolver, never()).firstQuestion(any());
    }

    @Test
    @DisplayName("★질문_카탈로그를_배치가_직접_읽지_않는다_판정_복제_금지의_구조적_가드")
    void batchDoesNotReachIntoQuestionCatalogue() {
        // 조달 판정은 판정기 한 곳이 소유한다. 배치가 질문 저장소를 직접 물면 「첫 번째 질문」
        // 해석이 이쪽에도 생겨, 화면이 보여준 질문과 산출물에 실린 질문이 조용히 어긋난다.
        assertThat(VlmTimeseriesStep.class.getDeclaredFields())
                .noneMatch(f -> f.getType().getName().startsWith("kr.co.cudo.authoring.sysconfig.repository")
                        || f.getType().getName().startsWith("kr.co.cudo.authoring.sysconfig.entity"));
    }

    @Test
    @DisplayName("★두_창구의_request_id는_서로_다르다")
    void twoChannelsUseDistinctRequestIds() {
        ingestEventType("fire");
        resolverReturns(QUESTION);

        step.runWithMarking(RAW_SN, marking(null));

        assertThat(captureCustom().requestId()).isNotEqualTo(captureDescribe().requestId());
    }

    @Test
    @DisplayName("★prompt가_상한을_넘으면_잘라_보내고_원장에도_같은_값이_남는다")
    void overLongPromptIsTruncatedConsistently() {
        ingestEventType("fire");
        resolverReturns("가".repeat(VlmTimeseriesRequest.MAX_PROMPT_LENGTH + 10));

        step.runWithMarking(RAW_SN, marking(null));

        String sent = captureCustom().prompt();
        assertThat(sent).hasSize(VlmTimeseriesRequest.MAX_PROMPT_LENGTH);
        assertThat(capturedLedgerQuestion()).isEqualTo(sent);
    }

    @Test
    @DisplayName("질문_조달이_비어도_추가질문축을_건너뛰지_않고_묘사축도_그대로_나간다")
    void emptyPromptDoesNotSkipEitherChannel() {
        ingestEventType("fire");
        resolverReturns(null);

        step.runWithMarking(RAW_SN, marking(null));

        // 「질문을 못 얻어 축을 건너뛴다」는 상태는 설계상 없다 — 지어내지 않고 그대로 보내
        // 벤더 응답이 판정하게 한다(event_type 과 같은 확정 관례).
        assertThat(captureCustom().prompt()).isNull();
        assertThat(captureDescribe().eventType()).isEqualTo("fire");
    }

    // ───────────────────────── 원장 보관 ─────────────────────────

    @Test
    @DisplayName("★보낸_질문_문구가_추가질문축_원장행에_그대로_보관된다_콜백이_재조달하지_않는_근거")
    void sentQuestionIsStoredOnLedgerRow() {
        ingestEventType("fire");
        resolverReturns(QUESTION);

        step.runWithMarking(RAW_SN, marking(null));

        assertThat(capturedLedgerQuestion()).isEqualTo(QUESTION);
        // 묘사 축 행은 질문을 갖지 않는다(5-인자 발급 — 종전 계약 그대로).
        verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM), isNull(),
                eq(RAW_SN), isNull());
    }

    /** 추가 질문 축 발급에 실린 질문 문구. 채널 문자열이 바뀌면 여기서 먼저 터진다(역조회 키 보호). */
    private String capturedLedgerQuestion() {
        ArgumentCaptor<String> qstn = ArgumentCaptor.forClass(String.class);
        verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM_SUB), isNull(),
                eq(RAW_SN), isNull(), qstn.capture());
        return qstn.getValue();
    }

    // ───────────────────────── event_type 조달 순서 ─────────────────────────

    @Test
    @DisplayName("★관제_인입값이_없으면_마킹에서_고른_검증이벤트유형을_묘사축에_싣는다")
    void markingSelectionFillsEventTypeWhenIngestMissing() {
        ingestEventType(null);
        resolverReturns(QUESTION);

        step.runWithMarking(RAW_SN, marking("flooding"));

        assertThat(captureDescribe().eventType()).isEqualTo("flooding");
    }

    @Test
    @DisplayName("★관제_인입값이_있으면_마킹_선택값을_쓰지_않는다")
    void ingestValueWinsOverMarkingSelection() {
        ingestEventType("fire");
        resolverReturns(QUESTION);

        step.runWithMarking(RAW_SN, marking("flooding"));

        assertThat(captureDescribe().eventType()).isEqualTo("fire");
    }

    @Test
    @DisplayName("둘_다_없으면_null_폴백이_유지된다_값을_지어내지_않는다")
    void nullFallbackIsPreserved() {
        ingestEventType(null);
        resolverReturns(QUESTION);

        step.runWithMarking(RAW_SN, marking(null));

        assertThat(captureDescribe().eventType()).isNull();
    }
}
