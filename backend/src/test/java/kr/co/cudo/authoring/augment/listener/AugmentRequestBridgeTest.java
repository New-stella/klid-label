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
        verify(augmentResultService).handleInNewTransaction(captor.capture());
        assertThat(captor.getValue().dataAugSn()).isEqualTo(7L);
        assertThat(captor.getValue().success()).isFalse();
    }

    @Test
    @DisplayName("정책_보류는_실패롤업하지_않는다")
    void withheldIsNotRolledUpAsFailure() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0, true));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handleInNewTransaction(any());
    }

    @Test
    @DisplayName("한_건이라도_수락되면_실패롤업하지_않는다")
    void acceptedJobsDeferRollupToCallback() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(2, false));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handleInNewTransaction(any());
    }

    @Test
    @DisplayName("미종결_job_이_남아있으면_실패롤업을_건너뛴다")
    void skipsRollupWhenInFlightJobExists() {
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0, false));
        LsDataAugJob inFlight = LsDataAugJob.createIssued(7L, 1, "AUG-key-1", 100);
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(7L)).willReturn(List.of(inFlight));

        bridge.onAugmentRequested(event());

        verify(augmentResultService, never()).handleInNewTransaction(any());
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
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, true));

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
        verify(augmentResultService, never()).handleInNewTransaction(any());
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

        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, true));

        verify(jobSubmitService, never()).submit(any());
    }

    @Test
    @DisplayName("해상도_파생은_외부_위탁_대상이_아니라_재개하지_않는다")
    void doesNotResumeResolutionDerivative() {
        LsDataAug resolution = LsDataAug.createRequested(70L, LsDataAug.AUG_RESL_720P, "1", "AUG-key", null);
        ReflectionTestUtils.setField(resolution, "dataAugSn", 8L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(resolution));

        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, true));

        verify(jobSubmitService, never()).submit(any());
    }

    // ─── Phase 8 DEV_FIX HIGH-1 — 재개 실패 관측 가능성 ─────────────

    /**
     * 구 구현은 재개 실패를 {@code log.warn} 한 줄로 삼켰다. 재개는 보류분의 <b>유일한</b> 복구
     * 경로라, 여기서 조용히 사라지면 그 증강이 PENDING 에 영구 고착돼도 아무 신호가 남지 않는다
     * (본 DEV_FIX 가 잡은 AFTER_COMMIT 결함이 오래 숨어 있던 이유).
     */
    @Test
    @DisplayName("위탁_보류_재개_실패가_조용히_삼켜지지_않고_사유가_남는다")
    void submitResumeFailureIsObservable() {
        // given — 보류 상태(job 0건)인데 재개 시도가 예외로 터진다.
        LsDataAug withheld = LsDataAug.createRequested(70L, LsDataAug.AUG_WINTER, "1", "AUG-key", null);
        ReflectionTestUtils.setField(withheld, "dataAugSn", 7L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(withheld));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("no transaction is in progress"))
                .given(augmentResultService).recordResumeAttempt(7L);

        // when — 건별 격리는 유지된다(리스너가 죽지 않는다).
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, true));

        // then — 실패가 메트릭 축으로 드러난다.
        verify(metrics).resumeFailed(AugmentMetrics.STAGE_SUBMIT);
    }

    @Test
    @DisplayName("결과_인계_보류_재개_실패가_조용히_삼켜지지_않고_사유가_남는다")
    void resultResumeFailureIsObservable() {
        // given — 전 job SUCCEEDED 인데 증강은 PENDING(=결과 인계 보류). 인계 재시도가 터진다.
        LsDataAug withheld = LsDataAug.createRequested(70L, LsDataAug.AUG_NIGHT, "1", "AUG-key", null);
        ReflectionTestUtils.setField(withheld, "dataAugSn", 9L);
        LsDataAugJob succeeded = LsDataAugJob.createIssued(9L, 1, "AUG-key-1", 1);
        succeeded.markSucceeded("J-1");
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(withheld));
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(9L)).willReturn(List.of(succeeded));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("no transaction is in progress"))
                .given(augmentResultService).handleInNewTransaction(any(AugmentOutcome.class));

        // when
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, true));

        // then
        verify(metrics).resumeFailed(AugmentMetrics.STAGE_RESULT);
    }

    /**
     * MEDIUM-1 — 보류 재개는 <b>검수 상태와 무관</b>하다. export 복구용 APPROVED 게이트를 이 경로에도
     * 걸면, 증강 요청 후 {@code APPROVED → PENDING}(재검수 재제출) 된 영상의 보류가 영구화된다.
     */
    @Test
    @DisplayName("보류_해제_이벤트가_미승인_영상에서도_보류_복구에_도달한다")
    void resumeReachesWithheldEvenWhenNotApproved() {
        LsDataAug withheld = LsDataAug.createRequested(70L, LsDataAug.AUG_WINTER, "1", "AUG-key", null);
        ReflectionTestUtils.setField(withheld, "dataAugSn", 7L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(withheld));
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(3, false));

        // when — reviewApproved=false (재검수 재제출로 APPROVED 를 벗어난 영상)
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, false));

        // then — 재개는 그대로 수행된다.
        verify(jobSubmitService).submit(any(AugmentRequestedItemEvent.class));
    }

    // ─── 적대검증 2차 LOW-1 — 판별 단계도 건별 격리 ─────────────

    /**
     * 구 구현은 보류 유형 <b>판별</b>({@code jobRepository.findByDataAugSnOrderByJobSeqAsc})이
     * try 밖이었다. 그래서 후보 3건 중 2번째 판별이 DB 예외로 터지면 <b>3번째 후보가 통째로 스킵</b>되고
     * 메트릭에도 잡히지 않았다 — 리스너 주석이 주장하는 "건별 격리" 가 판별 단계에는 없었다.
     */
    @Test
    @DisplayName("판별_실패가_한_건이면_나머지_후보는_계속_처리된다")
    void classificationFailureDoesNotSkipRemainingCandidates() {
        // given — 후보 3건. 2번째의 판별 조회만 터진다.
        LsDataAug first = withheldAug(1L);
        LsDataAug broken = withheldAug(2L);
        LsDataAug last = withheldAug(3L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(first, broken, last));
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(2L))
                .willThrow(new IllegalStateException("connection closed"));
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(1, false));

        // when
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, false));

        // then — 1번·3번 후보는 정상 재개된다(2번 실패가 뒤를 막지 않는다).
        ArgumentCaptor<AugmentRequestedItemEvent> captor =
                ArgumentCaptor.forClass(AugmentRequestedItemEvent.class);
        verify(jobSubmitService, org.mockito.Mockito.times(2)).submit(captor.capture());
        assertThat(captor.getAllValues()).extracting(AugmentRequestedItemEvent::originAugSn)
                .containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("판별_단계_실패도_메트릭에_집계된다")
    void classificationFailureIsCounted() {
        // given
        LsDataAug broken = withheldAug(2L);
        given(augRepository.findByOriginalRawSn(700L)).willReturn(List.of(broken));
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(2L))
                .willThrow(new IllegalStateException("connection closed"));

        // when
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(700L, false));

        // then — 재개되지 못한 건이 관측된다(구 구현은 Spring 기본 핸들러 ERROR 로그뿐이었다).
        verify(metrics).resumeFailed(AugmentMetrics.STAGE_CLASSIFY);
    }

    private LsDataAug withheldAug(Long dataAugSn) {
        LsDataAug aug = LsDataAug.createRequested(70L, LsDataAug.AUG_WINTER, "1", "AUG-key", null);
        ReflectionTestUtils.setField(aug, "dataAugSn", dataAugSn);
        return aug;
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
        given(jobSubmitService.submit(any())).willReturn(new AugmentJobSubmitService.SubmitOutcome(0, false));

        bridge.onAugmentRequested(event());

        verify(augmentResultService).handleInNewTransaction(any());
        verify(augmentResultService, never()).handle(any());
        assertThat(AugmentResultService.class.getDeclaredMethod("handleInNewTransaction", AugmentOutcome.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                .as("진입점이 REQUIRED 로 되돌아가면 커밋 스레드 실행 시 인계가 유실된다")
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    /**
     * HIGH-1 회귀 가드 — 두 AFTER_COMMIT 리스너는 반드시 {@code @Async} 로 <b>다른 스레드</b>에
     * 넘겨야 한다. AFTER_COMMIT 스레드에는 이미 커밋된 트랜잭션이 바인딩돼 있어, 거기서
     * {@code REQUIRED} 서비스를 부르면 뒤따르는 커밋이 없고 {@code FOR UPDATE} 잠금 조회가
     * {@code TransactionRequiredException} 으로 튄다(= 보류분 조용한 영구 고착).
     */
    @Test
    @DisplayName("AFTER_COMMIT_리스너는_Async_로_트랜잭션_경계_밖에서_실행된다")
    void afterCommitListenersAreAsync() throws Exception {
        assertThat(AugmentRequestBridge.class
                .getDeclaredMethod("onDeidentReportResolved", DeidentReportResolvedEvent.class)
                .isAnnotationPresent(org.springframework.scheduling.annotation.Async.class))
                .as("재개 리스너가 커밋 스레드에서 돌면 REQUIRED 인계가 커밋되지 않는다")
                .isTrue();
        assertThat(AugmentRequestBridge.class
                .getDeclaredMethod("onAugmentRequested", AugmentRequestedItemEvent.class)
                .isAnnotationPresent(org.springframework.scheduling.annotation.Async.class))
                .as("위탁 0건 실패 롤업도 같은 함정에 빠진다(FOR UPDATE 로 시작)")
                .isTrue();
    }
}
