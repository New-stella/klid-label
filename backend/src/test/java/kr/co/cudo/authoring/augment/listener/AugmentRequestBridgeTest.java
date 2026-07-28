package kr.co.cudo.authoring.augment.listener;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.service.AugmentCallbackUrlResolver;
import kr.co.cudo.authoring.augment.service.AugmentJobSubmitService;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AugmentRequestBridge} — DEV_FIX MEDIUM-4(위탁 0건 즉시 실패 롤업) + HIGH-2(보류 재개) 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentRequestBridgeTest {

    @Mock private AugmentJobSubmitService jobSubmitService;
    @Mock private AugmentMetrics metrics;
    @Mock private AugmentResultService augmentResultService;
    @Mock private LsDataAugRepository augRepository;
    @Mock private LsDataAugJobRepository jobRepository;

    private AugmentRequestBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new AugmentRequestBridge(jobSubmitService, metrics, augmentResultService,
                augRepository, jobRepository,
                new AugmentCallbackUrlResolver("http://localhost:8080/api"));
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(anyLong())).willReturn(List.of());
    }

    private AugmentRequestedItemEvent event() {
        return new AugmentRequestedItemEvent(
                7L, 700L, LsDataAug.AUG_WINTER, "AUG-key",
                "http://localhost:8080/api/v1/genai/callback", "1");
    }

    @Test
    @DisplayName("모든_청크_위탁이_실패하면_증강이_즉시_실패로_롤업된다")
    void rollsUpImmediatelyWhenNothingAccepted() {
        // given — 전 청크 실패(수락 0건). 콜백이 영영 오지 않으므로 PENDING 고착이 된다.
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0, false));

        // when
        bridge.onAugmentRequested(event());

        // then — 콜백을 기다리지 않고 즉시 실패 롤업(REJECTED 종결)
        ArgumentCaptor<AugmentOutcome> captor = ArgumentCaptor.forClass(AugmentOutcome.class);
        verify(augmentResultService).handle(captor.capture());
        assertThat(captor.getValue().dataAugSn()).isEqualTo(7L);
        assertThat(captor.getValue().success()).isFalse();
    }

    @Test
    @DisplayName("정책_보류는_실패롤업하지_않는다")
    void withheldIsNotRolledUpAsFailure() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0, true));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("한_건이라도_수락되면_실패롤업하지_않는다")
    void acceptedJobsDeferRollupToCallback() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(2, false));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("미종결_job_이_남아있으면_실패롤업을_건너뛴다")
    void skipsRollupWhenInFlightJobExists() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0, false));
        LsDataAugJob inFlight = LsDataAugJob.createIssued(7L, 1, "AUG-key-1", 100);
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(7L)).willReturn(List.of(inFlight));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("비식별_신고_해소시_보류됐던_증강_위탁이_재개된다")
    void resumesWithheldSubmitOnDeidentReportResolved() {
        // given — 보류 상태(PENDING · 외부 증강 유형 · 멱등키 보유 · job 행 0건)
        LsDataAug withheld = LsDataAug.createRequested(70L, LsDataAug.AUG_WINTER, "1", "AUG-key", null);
        ReflectionTestUtils.setField(withheld, "dataAugSn", 7L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(withheld));
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(3, false));

        // when
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L));

        // then — 같은 멱등키/콜백 URL 로 재위탁하고, ledger 키는 재발급하지 않는다(UNIQUE 충돌 방지).
        ArgumentCaptor<AugmentRequestedItemEvent> captor =
                ArgumentCaptor.forClass(AugmentRequestedItemEvent.class);
        verify(jobSubmitService).submit(captor.capture());
        assertThat(captor.getValue().originAugSn()).isEqualTo(7L);
        assertThat(captor.getValue().rawSn()).isEqualTo(700L);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("AUG-key");
        assertThat(captor.getValue().callbackUrl()).isEqualTo("http://localhost:8080/api/v1/genai/callback");
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
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(2, false));

        // when
        bridge.onAugmentRequested(event());

        // then — 원장 상태와 무관하게 위탁이 수행되고, 수락 건이 있으므로 실패 롤업도 없다.
        verify(jobSubmitService).submit(any());
        verify(augmentResultService, never()).handle(any());
        assertThat(AugmentRequestBridge.class.getDeclaredFields())
                .as("죽은 원장 협력자가 다시 주입되면 안 된다(실패가 위탁을 막는 구조 재발 방지)")
                .noneMatch(f -> f.getType().getSimpleName().contains("WebhookIdempotencyLedger"));
    }

    @Test
    @DisplayName("이미_위탁된_증강은_신고_해소시_재개하지_않는다")
    void doesNotResumeAlreadySubmittedAugment() {
        LsDataAug submitted = LsDataAug.createRequested(70L, LsDataAug.AUG_WINTER, "1", "AUG-key", null);
        ReflectionTestUtils.setField(submitted, "dataAugSn", 7L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(submitted));
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(7L))
                .willReturn(List.of(LsDataAugJob.createIssued(7L, 1, "AUG-key-1", 100)));

        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L));

        verify(jobSubmitService, never()).submit(any());
    }

    @Test
    @DisplayName("해상도_파생은_외부_위탁_대상이_아니라_재개하지_않는다")
    void doesNotResumeResolutionDerivative() {
        LsDataAug resolution = LsDataAug.createRequested(70L, LsDataAug.AUG_RESL_720P, "1", "AUG-key", null);
        ReflectionTestUtils.setField(resolution, "dataAugSn", 8L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(resolution));

        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L));

        verify(jobSubmitService, never()).submit(any());
    }
}
