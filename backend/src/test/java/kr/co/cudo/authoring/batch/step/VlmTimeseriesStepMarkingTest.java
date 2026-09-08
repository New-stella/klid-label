package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * VlmTimeseriesStep.runWithMarking() 테스트 — describe 규격(v2.0.1) + 논블로킹 제출(Phase C-1) 정합.
 *
 * <p>describe 규격상 eventName/marks 는 요청 바디에 포함하지 않으나, <b>제출 직전</b> 마킹 상태를
 * VLM_REQUESTED 로 전이·선커밋한다(상태 머신 유지 + 콜백 선행 레이스 폐쇄).
 *
 * <p>{@link VlmMarkingTxService} 는 실제 구현을 쓰되 리포지토리만 mock 한다 — 전이 판정 규칙
 * (PENDING 에서만 전이 · 종결 상태 역행 금지)이 이 테스트의 검증 대상이기 때문이다.
 */
class VlmTimeseriesStepMarkingTest {

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private IngestSourceRepository ingestSourceRepository;
    private BatchStatusService batchStatusService;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private LsMarkingRepository markingRepository;
    private DeidentReportGate deidentReportGate;
    private VlmSubmitOutcomeRecorder outcomeRecorder;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        outcomeRecorder = mock(VlmSubmitOutcomeRecorder.class);
        step = new VlmTimeseriesStep(vlmClient, videoRepository, ingestSourceRepository,
                batchStatusService, ledger, deidentProcLogRepository, deidentReportGate,
                new VlmMarkingTxService(markingRepository), outcomeRecorder,
                mock(kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence.class),
                new ObjectMapper(), Schedulers.immediate(),
                mock(kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker.class),
                mock(kr.co.cudo.authoring.aiserver.service.AiSrvrSelector.class),
                mock(kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver.class),
                mock(kr.co.cudo.authoring.evntanno.service.MarkingSelectedQuestionReader.class));
            // 추가 질문 축은 기본적으로 <b>신호 없음</b>으로 둔다 — 이 클래스의 단정은 묘사 축을
        // 대상으로 하므로, 두 축이 모두 완료 신호를 내면 핸들러 호출 횟수가 두 배가 되어
        // 무엇을 검증하는 테스트인지가 흐려진다. 추가 질문 축은 전용 테스트가 따로 본다.
        lenient().when(vlmClient.submitCustom(any(VlmTimeseriesRequest.class), any()))
                .thenReturn(Mono.never());
}

    private void seed(Long rawSn) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        // 관제 인입 검증이벤트유형 — Phase 2 이후 위탁의 사전 조건(@req R6).
        IngestSourceRow source = mock(IngestSourceRow.class);
        lenient().when(source.getVrfcEvntTypeCd()).thenReturn("fire");
        lenient().when(ingestSourceRepository.findSourceMeta(rawSn)).thenReturn(source);
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn("/data/deid/" + rawSn + ".mp4");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(plog));
    }

    private LsMarking newMarking(Long rawSn) {
        return LsMarking.createAuto(rawSn, 5, "[{\"frameIndex\":0,\"timestamp\":0.0}]", "1");
    }

    private void stubAccepted() {
        when(vlmClient.submitDescribe(any(VlmTimeseriesRequest.class), any()))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("echo", "accepted")));
    }

    @Test
    @DisplayName("runWithMarking_verify_위탁_비식별경로_전송")
    void runWithMarking_sendsDeidPath() {
        seed(400L);
        LsMarking marking = newMarking(400L);
        stubAccepted();

        VlmTimeseriesResponse resp = step.runWithMarking(400L, marking);

        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitDescribe(captor.capture(), any());
        VlmTimeseriesRequest req = captor.getValue();
        assertThat(req.media().path()).isEqualTo("/data/deid/400.mp4");
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
    }

    @Test
    @DisplayName("runWithMarking_마킹_상태_VLM_REQUESTED_전이")
    void runWithMarking_transitionsMarkingStatus() {
        seed(401L);
        LsMarking marking = newMarking(401L);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        stubAccepted();

        step.runWithMarking(401L, marking);

        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);
    }

    /**
     * ★ 뒤집힌 단언이다 — 구 기대값은 "설정 토글이 꺼져 있으면 SKIPPED 이고 마킹 전이도 없다" 였다.
     *
     * <p>그 토글은 폐지됐다(ADR-049). 사전 조건이 안 맞으면 위탁은 <b>조용히 건너뛰지 않고 실패</b>한다.
     * 다만 <b>마킹 상태를 올리지 않는 것</b>은 변하지 않는다 — 위탁이 나가지도 않았는데 선커밋으로
     * {@code VLM_REQUESTED} 만 남으면 사유 없는 고착이 되고 미결 스위퍼가 그것을 "ACK 미수신" 으로
     * 오인해 회수를 반복한다. 그 불변식이 이 테스트의 본체다.
     *
     * <p>⚠ 운영 지침: 벤더 미연동 구간에는 <b>먼저 스킵</b>한다. 스킵 없이 돌려 실패시키면 마킹이
     * {@code VLM_FAILED}(종결)로 가고, 이후 재수행해도 그 표시는 되돌아오지 않는다.
     */
    @Test
    @DisplayName("사전조건_미충족이면_실패하되_마킹_전이는_하지_않는다_사유없는_고착_방지")
    void runWithMarking_precondition_failure_doesNotTransition() {
        // given — 영상이 존재하지 않는다(기존 게이트). 구 코드라면 토글 분기가 먼저 삼켰을 자리다.
        when(videoRepository.existsById(402L)).thenReturn(false);
        LsMarking marking = newMarking(402L);

        // when / then
        assertThatThrownBy(() -> step.runWithMarking(402L, marking))
                .as("조용한 건너뛰기 분기가 되살아나면 예외 대신 SKIPPED 가 반환된다")
                .isInstanceOf(CustomException.class);

        VlmSubmitAssertions.neverSubmitted(vlmClient);
        assertThat(marking.getSttsCd())
                .as("위탁이 나가지 않았는데 상태를 올리면 사유 없는 고착이 된다")
                .isEqualTo(LsMarking.STATUS_PENDING);
        verifyNoInteractions(markingRepository);
    }

    @Test
    @DisplayName("retry_재실행_시_이미_VLM_COMPLETED_마킹은_VLM_REQUESTED로_역행하지_않는다")
    void runWithMarking_alreadyCompleted_doesNotRegress() {
        seed(404L);
        LsMarking marking = newMarking(404L);
        // (b)까지 세팅: PENDING → VLM_REQUESTED → VLM_COMPLETED
        marking.markVlmRequested();
        marking.markVlmCompleted();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        stubAccepted();

        // when — retry(BatchRetryQuartzJob → orchestrator.process) 로 파이프라인 재실행
        step.runWithMarking(404L, marking);

        // then — 마킹 상태는 역행하지 않고 VLM_COMPLETED 유지, 불필요한 save 도 발생하지 않음
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("runWithMarking_null_마킹시_기존_run_호출과_동일")
    void runWithMarking_nullMarking_fallsBackToRun() {
        seed(403L);
        stubAccepted();

        VlmTimeseriesResponse resp = step.runWithMarking(403L, null);

        verify(vlmClient).submitDescribe(any(VlmTimeseriesRequest.class), any());
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
    }
}
