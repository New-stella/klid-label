package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VLM describe 콜백 수신 서비스 단위 테스트 — 벤더 확정 계약(v2.0.1) 정합 + DEV_FIX(콜백 수신부).
 */
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

    /** saveAll 이 신규 엔티티에 metaSn 을 채워 반환하도록 흉내낸다(IDENTITY 전략 시뮬). */
    private void stubMetaSaveAll() {
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

    @Test
    @DisplayName("describe_콜백_completed_수신_시_구간별_시계열메타_적재_및_검수큐_진입")
    void completed_appliesSegmentMetaAndReviewQueue() {
        // given — (request_id, rawSn) 매핑을 발급 fixture 로 세팅
        ledger.recordIssued("REQ-1", LsWebhookIdempotency.CHANNEL_VLM, "EXT-V", 200L);
        when(videoRepository.existsById(200L)).thenReturn(true);
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(200L), any())).thenReturn(List.of());
        stubMetaSaveAll();

        VlmResultRequest req = new VlmResultRequest(
                "REQ-1", "completed",
                List.of(
                        new VlmResultRequest.Segment(0, 8, "사람이 도로를 무단횡단"),
                        new VlmResultRequest.Segment(8, 16, "차량이 정지선 침범")
                ),
                null);

        // when
        boolean applied = service.handle(req);

        // then — META_KEY 는 "{start_sec}-{end_sec}", META_VL 은 description (배치 saveAll)
        assertThat(applied).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataMeta>> metaCaptor = ArgumentCaptor.forClass(List.class);
        verify(metaRepository).saveAll(metaCaptor.capture());
        assertThat(metaCaptor.getValue())
                .extracting(LsDataMeta::getMetaKey)
                .containsExactly("0-8", "8-16");
        assertThat(metaCaptor.getValue())
                .extracting(LsDataMeta::getMetaVl)
                .containsExactly("사람이 도로를 무단횡단", "차량이 정지선 침범");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataMetaReview>> reviewCaptor = ArgumentCaptor.forClass(List.class);
        verify(reviewRepository).saveAll(reviewCaptor.capture());
        assertThat(reviewCaptor.getValue()).hasSize(2);
        assertThat(ledger.isProcessed("REQ-1")).isTrue();
    }

    @Test
    @DisplayName("describe_콜백_failed_수신_시_error_기록하고_적재_안함")
    void failed_recordsErrorWithoutAppend() {
        // given
        ledger.recordIssued("REQ-F", LsWebhookIdempotency.CHANNEL_VLM, "EXT-F", 300L);

        VlmResultRequest req = new VlmResultRequest(
                "REQ-F", "failed", null,
                new VlmResultRequest.VlmError("VLM_TIMEOUT", "분석 서버 응답 지연"));

        // when
        boolean applied = service.handle(req);

        // then — 멱등 마킹은 하되 적재/검수큐/영상조회 없음
        assertThat(applied).isTrue();
        verify(metaRepository, never()).saveAll(anyList());
        verify(reviewRepository, never()).saveAll(anyList());
        verify(videoRepository, never()).existsById(any());
        assertThat(ledger.isProcessed("REQ-F")).isTrue();
    }

    @Test
    @DisplayName("미발급_request_id_콜백은_UNAUTHORIZED_거부")
    void unknownRequestId_throws401() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-UNKNOWN", "completed",
                List.of(new VlmResultRequest.Segment(0, 8, "무단횡단")),
                null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(videoRepository, never()).existsById(any());
    }

    @Test
    @DisplayName("동일_request_id_재수신_시_멱등_스킵")
    void replay_returnsFalseWithoutSave() {
        ledger.recordIssued("REQ-DUP", LsWebhookIdempotency.CHANNEL_VLM, "EXT-D", 201L);
        when(videoRepository.existsById(201L)).thenReturn(true);
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(201L), any())).thenReturn(List.of());
        stubMetaSaveAll();

        VlmResultRequest req = new VlmResultRequest(
                "REQ-DUP", "completed",
                List.of(new VlmResultRequest.Segment(0, 4, "서술")),
                null);

        assertThat(service.handle(req)).isTrue();
        assertThat(service.handle(req)).isFalse(); // replay 멱등 스킵
    }

    @Test
    @DisplayName("발급됐으나_rawSn_매핑_없으면_UNAUTHORIZED_거부")
    void issuedButNoRawSnMapping_throws401() {
        // 3-인자 발급(rawSn 미설정) — describe 콜백은 rawSn 역조회 실패 시 무단 주입으로 간주
        ledger.recordIssued("REQ-NORAW", "EXT-N");

        VlmResultRequest req = new VlmResultRequest(
                "REQ-NORAW", "completed",
                List.of(new VlmResultRequest.Segment(0, 8, "서술")),
                null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("한_콜백_내_중복_구간_metaKey는_400_거부_및_조용한_덮어쓰기_없음")
    void duplicateSegmentMetaKey_throws400() {
        // given — 동일 (0,8) 구간 2건 (둘째가 첫째를 무성 덮어쓰기하는 사고 방지, #4)
        ledger.recordIssued("REQ-DUPKEY", LsWebhookIdempotency.CHANNEL_VLM, "EXT-DK", 202L);
        when(videoRepository.existsById(202L)).thenReturn(true);

        VlmResultRequest req = new VlmResultRequest(
                "REQ-DUPKEY", "completed",
                List.of(
                        new VlmResultRequest.Segment(0, 8, "첫 서술"),
                        new VlmResultRequest.Segment(0, 8, "둘째 서술(덮어쓰기 시도)")
                ),
                null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 적재/멱등마킹 모두 발생하지 않아야 함 (전체 롤백)
        verify(metaRepository, never()).saveAll(anyList());
        assertThat(ledger.isProcessed("REQ-DUPKEY")).isFalse();
    }

    @Test
    @DisplayName("검수큐_적재_중_예외_시_원장_PROCESSED_전이도_함께_롤백_재전송_복구가능")
    void sideEffectFailure_doesNotMarkProcessed() {
        // given — reviewRepository.saveAll 이 실패하는 상황(예: 제약 위반) 시뮬
        ledger.recordIssued("REQ-ATOMIC", LsWebhookIdempotency.CHANNEL_VLM, "EXT-A", 203L);
        when(videoRepository.existsById(203L)).thenReturn(true);
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(203L), any())).thenReturn(List.of());
        stubMetaSaveAll();
        when(reviewRepository.saveAll(anyList()))
                .thenThrow(new RuntimeException("검수큐 적재 실패"));

        VlmResultRequest req = new VlmResultRequest(
                "REQ-ATOMIC", "completed",
                List.of(new VlmResultRequest.Segment(0, 8, "서술")),
                null);

        // when / then — 예외 전파(트랜잭션 롤백) + 원장은 PROCESSED 로 남지 않아야 함
        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(RuntimeException.class);
        assertThat(ledger.isProcessed("REQ-ATOMIC")).isFalse();
    }

    @Test
    @DisplayName("미지_status_콜백은_INVALID_INPUT_거부되고_completed_오처리_및_멱등마킹_안함")
    void unknownStatus_rejectedWithoutMisprocessing() {
        // given — 발급됐으나 status 가 completed/failed 화이트리스트 밖(예: 오타/빈 의미값).
        //  현행 버그: "failed" 가 아니면 무조건 completed 로 처리 → 적재 + 조기 PROCESSED 마킹.
        ledger.recordIssued("REQ-BADSTS", LsWebhookIdempotency.CHANNEL_VLM, "EXT-BS", 205L);
        lenient().when(videoRepository.existsById(205L)).thenReturn(true);
        stubMetaSaveAll();

        VlmResultRequest req = new VlmResultRequest(
                "REQ-BADSTS", "processing",
                List.of(new VlmResultRequest.Segment(0, 8, "서술")),
                null);

        // when / then — 엄격 화이트리스트: 미지 status 는 INVALID_INPUT 거부
        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // completed 오처리 없음 + 멱등 미마킹(재전송 가능)
        verify(metaRepository, never()).saveAll(anyList());
        verify(reviewRepository, never()).saveAll(anyList());
        assertThat(ledger.isProcessed("REQ-BADSTS")).isFalse();
    }

    @Test
    @DisplayName("배치_내_동일_영상_기존_meta는_값갱신만_신규_meta만_검수큐_진입")
    void existingMeta_updatesValueWithoutDuplicateReview() {
        // given — "0-8" 은 이미 존재(기존 검수행 보유), "8-16" 은 신규
        ledger.recordIssued("REQ-MIX", LsWebhookIdempotency.CHANNEL_VLM, "EXT-MX", 204L);
        when(videoRepository.existsById(204L)).thenReturn(true);
        LsDataMeta existing = LsDataMeta.create(204L, "0-8", "이전 값");
        when(metaRepository.findByRawSnAndMetaKeyIn(eq(204L), any())).thenReturn(List.of(existing));
        stubMetaSaveAll();

        VlmResultRequest req = new VlmResultRequest(
                "REQ-MIX", "completed",
                List.of(
                        new VlmResultRequest.Segment(0, 8, "새 값"),
                        new VlmResultRequest.Segment(8, 16, "신규 구간")
                ),
                null);

        // when
        boolean applied = service.handle(req);

        // then — 기존 meta 값 갱신, 검수큐는 신규 1건만
        assertThat(applied).isTrue();
        assertThat(existing.getMetaVl()).isEqualTo("새 값");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataMetaReview>> reviewCaptor = ArgumentCaptor.forClass(List.class);
        verify(reviewRepository).saveAll(reviewCaptor.capture());
        assertThat(reviewCaptor.getValue()).hasSize(1);
    }
}
