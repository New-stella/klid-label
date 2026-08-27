package kr.co.cudo.authoring.augment.listener;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.service.AugmentJobSubmitService;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.webhook.service.AugmentOutcome;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AugmentRequestBridge} — DEV_FIX MEDIUM-4(위탁 0건 즉시 실패 롤업) 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentRequestBridgeTest {

    /** 위탁 payload 의 prompt — 이 테스트의 관심사가 아니라 계약(필수 non-empty)을 채우는 고정값. */
    private static final java.util.Map<String, Object> MTDT = java.util.Map.of("time", "NIGHT", "season", "WINTER", "weather", "RAIN", "terrain", "ROAD", "severity", "HIGH");


    @Mock private AugmentJobSubmitService jobSubmitService;
    @Mock private AugmentMetrics metrics;
    @Mock private AugmentResultService augmentResultService;
    @Mock private LsDataAugJobRepository jobRepository;

    private AugmentRequestBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new AugmentRequestBridge(jobSubmitService, metrics, augmentResultService, jobRepository);
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(anyLong())).willReturn(List.of());
    }

    private AugmentRequestedItemEvent event() {
        return new AugmentRequestedItemEvent(
                7L, 700L, LsDataAug.AUG_WINTER, MTDT, null, "FLOOD", null, "AUG-key",
                "http://localhost:8080/api/v1/genai/callback", "1");
    }

    @Test
    @DisplayName("모든_청크_위탁이_실패하면_증강이_즉시_실패로_롤업된다")
    void rollsUpImmediatelyWhenNothingAccepted() {
        // given — 전 청크 실패(수락 0건). 콜백이 영영 오지 않으므로 PENDING 고착이 된다.
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0));

        // when
        bridge.onAugmentRequested(event());

        // then — 콜백을 기다리지 않고 즉시 실패 롤업(REJECTED 종결)
        ArgumentCaptor<AugmentOutcome> captor = ArgumentCaptor.forClass(AugmentOutcome.class);
        verify(augmentResultService).handleInNewTransaction(captor.capture());
        assertThat(captor.getValue().dataAugSn()).isEqualTo(7L);
        assertThat(captor.getValue().success()).isFalse();
    }

    /**
     * 2026-07-29 정책 — 비식별 누락 신고 구간 차단도 <b>거부(실패)</b> 다. 구 구현은 이 경우를 보류로
     * 남기고 신고 해소 시 재개했으나 재개 배선이 철회돼, 보류로 두면 PENDING 영구 고착이 된다.
     */
    @Test
    @DisplayName("신고_구간_거부도_수락0건이라_실패롤업된다")
    void deidentReportRejectionIsRolledUpAsFailure() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0));

        bridge.onAugmentRequested(event());

        verify(augmentResultService).handleInNewTransaction(any());
    }

    @Test
    @DisplayName("한_건이라도_수락되면_실패롤업하지_않는다")
    void acceptedJobsDeferRollupToCallback() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(2));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handleInNewTransaction(any());
    }

    @Test
    @DisplayName("미종결_job_이_남아있으면_실패롤업을_건너뛴다")
    void skipsRollupWhenInFlightJobExists() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0));
        LsDataAugJob inFlight = LsDataAugJob.createIssued(7L, 1, "AUG-key-1", 100);
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(7L)).willReturn(List.of(inFlight));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handleInNewTransaction(any());
    }

    /**
     * DEV_FIX MEDIUM — 죽은 원장(LS_WEBHOOK_IDEMPOTENCY / CHANNEL_AUGMENT) write 제거 회귀 가드.
     *
     * <p>과거 구조: 리스너가 원장 선기록에 성공해야만 {@code submit()} 을 호출했다. 그 원장은 어느 수신
     * 경로에서도 읽히지 않으므로(발급 게이트는 {@code LS_DATA_AUG_JOB.IDMP_KEY}), 원장 저장 장애가
     * 살아있는 기능인 외부 위탁을 통째로 막았다. 지금은 원장 협력자가 아예 없어야 하며(생성자 인자
     * 부재로 컴파일 타임에 고정), 위탁은 원장과 무관하게 항상 수행된다.
     */
    @Test
    @DisplayName("원장_기록_실패해도_외부_위탁은_계속_진행된다")
    void submitProceedsWithoutIdempotencyLedger() {
        // given — 원장 협력자가 존재하지 않는다(브리지 생성자에 없음). 위탁은 정상 수락.
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(2));

        // when
        bridge.onAugmentRequested(event());

        // then — 원장 상태와 무관하게 위탁이 수행되고, 수락 건이 있으므로 실패 롤업도 없다.
        verify(jobSubmitService).submit(any());
        verify(augmentResultService, never()).handleInNewTransaction(any());
        assertThat(AugmentRequestBridge.class.getDeclaredFields())
                .as("죽은 원장 협력자가 다시 주입되면 안 된다(실패가 위탁을 막는 구조 재발 방지)")
                .noneMatch(f -> f.getType().getSimpleName().contains("WebhookIdempotencyLedger"));
    }

    /**
     * 적대검증 2차 MEDIUM-1 회귀 가드 — 인계는 <b>스레드 가정에 의존하지 않는</b> 진입점으로만 나가야 한다.
     *
     * <p>{@code batchAsyncExecutor} 의 거부 정책이 {@code CallerRunsPolicy} 라 풀 포화 시 리스너 본문이
     * 커밋 스레드에서 실행된다. 그때 {@code handle}({@code REQUIRED})을 직접 부르면 이미 커밋된
     * 트랜잭션에 참여해 인계가 사라진다 — 진입점은 {@code handleInNewTransaction}(REQUIRES_NEW)여야 한다.
     */
    @Test
    @DisplayName("인계는_REQUIRES_NEW_진입점으로만_호출된다")
    void handOffUsesNewTransactionEntryPoint() throws Exception {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0));

        bridge.onAugmentRequested(event());

        verify(augmentResultService).handleInNewTransaction(any());
        verify(augmentResultService, never()).handle(any());
        assertThat(AugmentResultService.class.getDeclaredMethod("handleInNewTransaction", AugmentOutcome.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                .as("진입점이 REQUIRED 로 되돌아가면 커밋 스레드 실행 시 인계가 유실된다")
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    /**
     * HIGH-1 회귀 가드 — AFTER_COMMIT 리스너는 반드시 {@code @Async} 로 <b>다른 스레드</b>에
     * 넘겨야 한다. AFTER_COMMIT 스레드에는 이미 커밋된 트랜잭션이 바인딩돼 있어, 거기서
     * {@code REQUIRED} 서비스를 부르면 뒤따르는 커밋이 없고 {@code FOR UPDATE} 잠금 조회가
     * {@code TransactionRequiredException} 으로 튄다(= 실패 롤업 유실 → 조용한 PENDING 고착).
     */
    @Test
    @DisplayName("AFTER_COMMIT_리스너는_Async_로_트랜잭션_경계_밖에서_실행된다")
    void afterCommitListenersAreAsync() throws Exception {
        assertThat(AugmentRequestBridge.class
                .getDeclaredMethod("onAugmentRequested", AugmentRequestedItemEvent.class)
                .isAnnotationPresent(org.springframework.scheduling.annotation.Async.class))
                .as("위탁 0건 실패 롤업도 같은 함정에 빠진다(FOR UPDATE 로 시작)")
                .isTrue();
    }
}
