package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrSelector;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★ <b>배선 시험</b> — 고른 장비가 (1)위탁 원장에 남고 (2)실제 위탁 호출의 목적지가 된다.
 * [@design ADR-057] [@design ERD-021]
 *
 * <p>이 둘이 갈리면 <b>오류가 아니라 조용한 어긋남</b>이다: 원장에는 「A 로 보냈다」가 남고 요청은
 * 다른 곳으로 가며, 그 원장은 다음 배분의 <b>입력</b>이라 어긋남이 누적된다.
 *
 * <p><b>mutation 확인</b>: 스텝에서 {@code srvrId} 를 {@code recordIssued} 에 넘기지 않거나
 * {@code srvrAddr} 를 {@code submitDescribe} 에 넘기지 않으면 이 시험이 실패한다.
 */
class VlmTimeseriesStepNodeSelectionTest {

    private static final Long RAW_SN = 900L;
    private static final String NODE_ID = "ts02";
    private static final String NODE_ADDR = "http://ts02.internal:9500";

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private IngestSourceRepository ingestSourceRepository;
    private BatchStatusService batchStatusService;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private DeidentReportGate deidentReportGate;
    private VlmMarkingTxService markingTxService;
    private VlmSubmitOutcomeRecorder outcomeRecorder;
    private VlmTimeseriesMetaPresence timeseriesMetaPresence;
    private AiSrvrSelector selector;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        markingTxService = mock(VlmMarkingTxService.class);
        outcomeRecorder = mock(VlmSubmitOutcomeRecorder.class);
        timeseriesMetaPresence = mock(VlmTimeseriesMetaPresence.class);
        selector = mock(AiSrvrSelector.class);

        step = new VlmTimeseriesStep(vlmClient, videoRepository, ingestSourceRepository,
                batchStatusService, ledger, deidentProcLogRepository, deidentReportGate,
                markingTxService, outcomeRecorder, timeseriesMetaPresence,
                new ObjectMapper(), Schedulers.immediate(),
                mock(VlmDefaultSkipMarker.class), selector);

        when(videoRepository.existsById(RAW_SN)).thenReturn(true);
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn("/data/deid/" + RAW_SN + ".mp4");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(RAW_SN)).thenReturn(Optional.of(plog));
        IngestSourceRow source = mock(IngestSourceRow.class);
        lenient().when(source.getVrfcEvntTypeCd()).thenReturn("fire");
        lenient().when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(source);
        // 제출은 신호 없이 매달아 둔다 — 이 시험의 관심은 "어디로 보냈는가" 뿐이다.
        lenient().when(vlmClient.submitDescribe(any(VlmTimeseriesRequest.class), any()))
                .thenReturn(Mono.never());
        lenient().when(vlmClient.submitDescribeSub(any(VlmTimeseriesRequest.class), any()))
                .thenReturn(Mono.never());
        lenient().when(vlmClient.fetchStatus()).thenReturn(Mono.never());
    }

    private void chooses(String srvrId, String srvrAddr) {
        LsAiSrvr node = LsAiSrvr.register(srvrId, "gpu-node-2", srvrAddr,
                LsAiSrvr.SrvrType.TIMESERIES, LocalDateTime.now());
        when(selector.select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH))
                .thenReturn(Optional.of(node));
    }

    private void choosesNothing() {
        when(selector.select(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("★위탁시_선택한_장비가_원장에_기록된다")
    void 위탁시_선택한_장비가_원장에_기록된다() {
        // given
        chooses(NODE_ID, NODE_ADDR);

        // when
        VlmTimeseriesResponse resp = step.run(RAW_SN);

        // then — 두 창구 모두 「어느 장비로 보냈는가」를 달고 선커밋된다.
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM), isNull(),
                eq(RAW_SN), eq(NODE_ID));
        verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM_SUB), isNull(),
                eq(RAW_SN), eq(NODE_ID));
    }

    @Test
    @DisplayName("★고른_장비의_주소가_실제_위탁_호출에_실린다 — 기록과 목적지가 갈리지 않는다")
    void 고른_장비의_주소가_실제_위탁_호출에_실린다() {
        // given
        chooses(NODE_ID, NODE_ADDR);

        // when
        step.run(RAW_SN);

        // then
        verify(vlmClient).submitDescribe(any(VlmTimeseriesRequest.class), eq(NODE_ADDR));
        verify(vlmClient).submitDescribeSub(any(VlmTimeseriesRequest.class), eq(NODE_ADDR));
    }

    @Test
    @DisplayName("두_창구는_같은_장비로_나간다 — 한 영상의 위탁이 두 장비의 부하를 올리지 않는다")
    void 두_창구는_같은_장비로_나간다() {
        // given
        chooses(NODE_ID, NODE_ADDR);

        // when
        step.run(RAW_SN);

        // then — 창구마다 따로 고르면 원장에도 장비가 갈려 남아 부하 집계가 어긋난다.
        ArgumentCaptor<String> describeAddr = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subAddr = ArgumentCaptor.forClass(String.class);
        verify(vlmClient).submitDescribe(any(VlmTimeseriesRequest.class), describeAddr.capture());
        verify(vlmClient).submitDescribeSub(any(VlmTimeseriesRequest.class), subAddr.capture());
        // ★ 두 값이 "서로 같은지"만 보면 배선이 통째로 끊겨 양쪽 다 null 일 때도 null==null 로
        //   통과한다(약한 가드). 고른 장비의 주소라는 <b>기댓값</b>까지 단언한다.
        assertThat(describeAddr.getValue()).isEqualTo(NODE_ADDR);
        assertThat(subAddr.getValue()).isEqualTo(NODE_ADDR);
        assertThat(describeAddr.getValue()).isEqualTo(subAddr.getValue());
    }

    @Test
    @DisplayName("★장비를_고르지_못해도_위탁은_나가고_장비미상으로_기록된다")
    void 장비를_고르지_못해도_위탁은_나간다() {
        // given — 원장에 시계열 노드가 한 건도 없는 현재 형상.
        choosesNothing();

        // when
        VlmTimeseriesResponse resp = step.run(RAW_SN);

        // then — 여기서 실패시키면 「분산을 못 한다」가 「연동이 끊긴다」로 격상된다.
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM), isNull(),
                eq(RAW_SN), isNull());
        // 주소를 지어내지 않는다 — 배포 기본 주소로 그대로 나간다(기존 동작).
        verify(vlmClient).submitDescribe(any(VlmTimeseriesRequest.class), isNull());
        verify(vlmClient).submitDescribeSub(any(VlmTimeseriesRequest.class), isNull());
    }

    /**
     * ★ <b>고른 장비로 보낼 수 없으면 그 장비를 원장에 적지 않는다</b> — 선커밋 <b>이전</b>에 판정한다.
     *
     * <p>선택기가 이미 걸러 주지만(그쪽에 같은 술어의 시험이 있다) 이 판정이 스텝에도 있어야 하는
     * 이유는 두 가지다: ①선커밋 뒤에서 목적지 해석이 실패하면 상관키만 durable 하게 남는 고아 미결이
     * 된다 ②그 계약이 깨졌을 때 스텝은 「기록과 목적지가 갈리는 것」보다 「분산을 포기하는 것」을
     * 골라야 한다. 여기서 요구하는 것은 <b>원장이 정직할 것</b> 하나다 — 장비 미상으로 적었으면
     * 요청도 배포 기본 주소로 나가야 한다.
     */
    @Test
    @DisplayName("★보낼_수_없는_주소의_장비를_골라도_원장과_목적지가_갈리지_않는다")
    void 보낼_수_없는_주소면_장비미상으로_낮춘다() {
        // given — 원장 CHECK 는 NOT NULL 만 걸어 공백 주소가 통과한다.
        chooses(NODE_ID, "   ");

        // when
        VlmTimeseriesResponse resp = step.run(RAW_SN);

        // then — 위탁 자체는 나가되(연동이 끊기지 않는다) 기록과 목적지가 <b>둘 다</b> 장비 미상이다.
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM), isNull(),
                eq(RAW_SN), isNull());
        verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM_SUB), isNull(),
                eq(RAW_SN), isNull());
        verify(vlmClient).submitDescribe(any(VlmTimeseriesRequest.class), isNull());
        verify(vlmClient).submitDescribeSub(any(VlmTimeseriesRequest.class), isNull());
    }

    /**
     * ★ <b>제출 조립이 터져도 파이프라인 밖으로 새지 않는다</b>.
     *
     * <p>구 코드는 조립을 {@code submitOne} 의 <b>인자 자리</b>에서 했다. 메서드 인자는 호출 <b>전에</b>
     * 평가되므로 그 안의 {@code try/catch} 가 조립 실패를 잡지 못했고, 그 예외는 선커밋 뒤에서
     * {@code process()} 밖으로 새어 ①확정 실패 기록이 남지 않아 마킹이 {@code VLM_REQUESTED} 에
     * 고착되고 ②추가 질문 축은 시도조차 못 했는데 그 ISSUED 행은 커밋돼 <b>고아 미결</b>이 되며
     * ③배치 전체가 FAILED 로 마감됐다.
     */
    @Test
    @DisplayName("★제출_조립이_실패해도_예외가_파이프라인으로_새지_않고_확정_실패로_기록된다")
    void 제출_조립_실패는_확정_실패로_기록된다() {
        // given — 클라이언트가 Mono 를 만들기도 전에 동기 예외를 던진다(목적지 해석 실패 등).
        chooses(NODE_ID, NODE_ADDR);
        when(vlmClient.submitDescribe(any(VlmTimeseriesRequest.class), any()))
                .thenThrow(new NonRetryableExternalException("대상 장비의 주소로 목적지를 만들 수 없습니다."));

        // when — 예외가 밖으로 나가면 배치 전체가 FAILED 로 마감된다.
        VlmTimeseriesResponse resp = step.run(RAW_SN);

        // then — 기록만 남기고 진행한다. 다른 창구는 그대로 시도된다(고아 미결 방지).
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        verify(outcomeRecorder).onSubmitFailed(eq(RAW_SN), any(), any(Throwable.class));
        verify(vlmClient).submitDescribeSub(any(VlmTimeseriesRequest.class), eq(NODE_ADDR));
    }

    @Test
    @DisplayName("장비_선택은_시계열_축에서_고른다 — 추론 장비를 시계열 위탁에 쓰지 않는다")
    void 장비_선택은_시계열_축에서_고른다() {
        // given
        chooses(NODE_ID, NODE_ADDR);

        // when
        step.run(RAW_SN);

        // then — 축을 잘못 넘기면 부하의 성질이 다른 장비로 위탁이 나간다.
        verify(selector).select(LsAiSrvr.SrvrType.TIMESERIES, AiSrvrUsageType.BATCH);
    }
}
