package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.listener.AugmentRequestBridge;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.augment.service.AugmentCallbackUrlResolver;
import kr.co.cudo.authoring.augment.service.AugmentJobSubmitService;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentJobRollup;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 증강 <b>실패 가시성</b> 배선 테스트 (Phase 8-B — E-ISSUE-06 / E-06 파생 / E-ISSUE-11).
 *
 * <h3>왜 픽스처 직접 호출이 아니라 배선 경로로 검증하는가</h3>
 * <p>{@code markDeadLetter()}/{@code incrementRetryCount()} 는 정의만 있고 프로덕션 호출자가 0건이었는데,
 * 기존 테스트가 픽스처에서 직접 호출해 GREEN 을 만들어 <b>위양성</b>이었다. 그래서 이 클래스는 실제
 * 컴포넌트({@link AugmentJobRollup} → {@link AugmentResultService} → {@link AugmentReviewService},
 * {@link AugmentRequestBridge})를 그대로 연결하고 <b>입력만 목킹</b>한다. 엔티티 메서드를 직접 부르지 않는다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentFailureVisibilityTest {

    private static final String DEID_BASE = "/storage/deidentified";

    @Mock private LsDataAugRepository augRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private AsyncAugmentFrameRunner asyncAugmentFrameRunner;
    @Mock private LsDataAugJobRepository jobRepository;
    @Mock private LsDataAugRvwRepository reviewRepository;
    @Mock private ExternalAugmentClient externalClient;
    @Mock private AugmentJobSubmitService jobSubmitService;
    @Mock private AugmentMetrics metrics;
    @Mock private AugmentCallbackUrlResolver callbackUrlResolver;
    @Mock private kr.co.cudo.authoring.webhook.service.AugmentJobIdOwnerLookup jobIdOwnerLookup;
    /** 조상 체인 신고 판정 — 기본 stub 은 "신고 없음"(false). */
    @Mock private kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate;

    private AugmentResultService resultService;
    private AugmentJobRollup rollup;
    private AugmentReviewService reviewService;
    private AugmentRequestBridge bridge;

    private final AtomicLong rawSnSeq = new AtomicLong(9000);

    @BeforeEach
    void setUp() {
        resultService = new AugmentResultService(augRepository, videoRepository, srcRepository,
                asyncAugmentFrameRunner, allowedStorageResolver(), jobIdOwnerLookup, deidentReportGate);
        ReflectionTestUtils.setField(resultService, "storageDeidentifiedPath", DEID_BASE);
        rollup = new AugmentJobRollup(resultService);
        reviewService = new AugmentReviewService(augRepository, reviewRepository, srcRepository,
                videoRepository, externalClient);
        bridge = new AugmentRequestBridge(jobSubmitService, metrics, resultService,
                augRepository, jobRepository, callbackUrlResolver);

        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw raw = inv.getArgument(0);
            setField(raw, "rawSn", rawSnSeq.incrementAndGet());
            return raw;
        });
        when(callbackUrlResolver.resolve()).thenReturn("https://authoring.internal/v1/genai/callback");
    }

    private static VideoArtifactRootResolver allowedStorageResolver() {
        return new VideoArtifactRootResolver(
                "/storage", "", "/storage/raw", DEID_BASE, "/storage/labeling",
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
    }

    // ─── 픽스처 ───────────────────────────────────────────────

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PENDING 외부 증강 1건 — 대표프레임 srcSn 과 함께 등록된다. */
    private LsDataAug pendingAug(long dataAugSn, long srcSn, String augType) {
        LsDataAug aug = LsDataAug.createRequested(srcSn, augType, "1", "K-" + dataAugSn, null);
        setField(aug, "dataAugSn", dataAugSn);
        when(augRepository.findByDataAugSnForUpdate(dataAugSn)).thenReturn(Optional.of(aug));
        return aug;
    }

    /** 부모 영상(비식별 완료 'Y') + 프레임 1건을 시드하고 조회를 연결한다. */
    private LsDataRaw parentChain(long rawSn, long srcSn, String deIdntfYn) {
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 60);
        setField(parent, "rawSn", rawSn);
        parent.markDeidentified(deIdntfYn);
        LsDataSrc src = LsDataSrc.create(rawSn, 0, rawSn + "/f0.jpg", null);
        setField(src, "srcSn", srcSn);
        when(srcRepository.findById(srcSn)).thenReturn(Optional.of(src));
        when(videoRepository.findByRawSnForUpdate(rawSn)).thenReturn(Optional.of(parent));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)).thenReturn(List.of(src));
        return parent;
    }

    private static LsDataAugJob succeededJob(long dataAugSn, int seq) {
        LsDataAugJob job = LsDataAugJob.createIssued(dataAugSn, seq, "K-" + dataAugSn + "-" + seq, 1);
        job.markSucceeded("J-" + seq);
        return job;
    }

    private static LsDataAugJob failedJob(long dataAugSn, int seq, String errorCode) {
        LsDataAugJob job = LsDataAugJob.createIssued(dataAugSn, seq, "K-" + dataAugSn + "-" + seq, 1);
        job.markFailed(errorCode, "외부 처리 실패");
        return job;
    }

    /** 그룹 집계 진입 — jobId(=원본 RAW_SN)로 증강 row 를 찾는 경로를 연결한다. */
    private String aggregate(long rawSn, LsDataAug... group) {
        when(augRepository.findByOriginalRawSn(rawSn)).thenReturn(List.of(group));
        return reviewService.aggregateResultStatus(rawSn);
    }

    // ─── ① dead-letter 배선 (E-ISSUE-06) ─────────────────────

    @Test
    @DisplayName("영구_실패시_DEAD_LETTER_AT_이_배선_경로를_통해_기록된다")
    void permanentFailureRecordsDeadLetterThroughWiring() {
        // given: 1건 성공 + 1건 실패 = 부분 실패(fail-closed)
        LsDataAug aug = pendingAug(801L, 8010L, LsDataAug.AUG_WINTER);
        assertThat(aug.getDeadLetterAt()).as("사전 조건 — 픽스처는 dead-letter 를 찍지 않는다").isNull();

        // when: 프로덕션 롤업 → 인계 서비스 (엔티티 메서드 직접 호출 없음)
        AugmentApplyResult result = rollup.rollUpIfAllTerminal(801L,
                List.of(succeededJob(801L, 1), failedJob(801L, 2, "MODEL_EXECUTION_FAILED")), "J-1");

        // then
        assertThat(result).isEqualTo(AugmentApplyResult.APPLIED);
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.getDeadLetterAt())
                .as("실패 경로에 dead-letter 배선이 없으면 실패가 어디에도 드러나지 않는다")
                .isNotNull();
        assertThat(aug.isProcessingFailed()).isTrue();
    }

    @Test
    @DisplayName("재시도_횟수가_실제로_누적된다")
    void retryCountAccumulatesThroughResumeWiring() {
        // given: 위탁 전 보류(job 0건) 상태의 증강 — 신고 해소 트리거가 재개 대상으로 집는다
        LsDataAug aug = pendingAug(802L, 8020L, LsDataAug.AUG_NIGHT);
        when(augRepository.findByOriginalRawSn(802000L)).thenReturn(List.of(aug));
        when(jobRepository.findByDataAugSnOrderByJobSeqAsc(802L)).thenReturn(List.of());
        // 재개해도 여전히 정책 보류(= 실패 아님) → 상태는 PENDING 유지, 시도 횟수만 쌓인다
        when(jobSubmitService.submit(any(AugmentRequestedItemEvent.class)))
                .thenReturn(new AugmentJobSubmitService.SubmitOutcome(0, true));
        assertThat(aug.getRetryCount()).isZero();

        // when: 신고 해소가 두 번 일어났다(다시 신고 → 다시 해소)
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(802000L, true));
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(802000L, true));

        // then
        assertThat(aug.getRetryCount())
                .as("재개 시도가 누적돼야 운영이 '몇 번이나 다시 시도했는지' 를 본다")
                .isEqualTo(2);
        assertThat(aug.getDeadLetterAt())
                .as("보류 재개는 실패가 아니므로 dead-letter 를 찍지 않는다")
                .isNull();
    }

    // ─── ② 집계 판정 축 (E-06 파생) ───────────────────────────

    @Test
    @DisplayName("롤업_REJECTED_인_증강이_집계에서_COMPLETED_로_보이지_않는다")
    void rollUpRejectedIsNotAggregatedAsCompleted() {
        // given: 실패 롤업으로 REJECTED(terminal)가 된 증강
        LsDataAug aug = pendingAug(803L, 8030L, LsDataAug.AUG_RAIN);
        rollup.rollUpIfAllTerminal(803L, List.of(failedJob(803L, 1, "MODEL_EXECUTION_FAILED")), "J-1");
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);

        // when
        String status = aggregate(803000L, aug);

        // then: REJECTED 는 terminal 이라 구 규칙("전부 종료 → COMPLETED")이 실패를 완료로 둔갑시켰다
        assertThat(status)
                .as("실패한 증강이 화면·통계에서 '완료' 로 보이면 안 된다")
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("만료_종결된_증강이_실패로_집계된다")
    void expiredAugmentIsAggregatedAsFailed() {
        // given: 만료 스윕이 회수한 job(FAILED/EXPIRED) → 롤업 실패 확정
        LsDataAug aug = pendingAug(804L, 8040L, LsDataAug.AUG_WINTER);
        rollup.rollUpIfAllTerminal(804L,
                List.of(failedJob(804L, 1, LsDataAugJob.ERR_EXPIRED)), "J-1");

        // when / then
        assertThat(aggregate(804000L, aug)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("검수결과_축과_외부처리_축이_섞이지_않는다")
    void reviewAxisAndProcessingAxisStaySeparate() {
        // given ① REVIEWER 반려(검수 결과 축) — 처리 실패가 아니다
        LsDataAug reviewerRejected = pendingAug(805L, 8050L, LsDataAug.AUG_NIGHT);
        reviewerRejected.applyReviewStatus(LsDataAug.STTS_REJECTED);

        // given ② 외부 처리 실패 롤업(외부 처리 축) — 같은 REJECTED 값이지만 성격이 다르다
        LsDataAug processingFailed = pendingAug(806L, 8060L, LsDataAug.AUG_NIGHT);
        rollup.rollUpIfAllTerminal(806L, List.of(failedJob(806L, 1, "SUBMIT_FAILED")), "J-1");

        // then: 상태 문자열은 같고, 구분은 처리 실패 전용 마커로만 성립한다
        assertThat(reviewerRejected.getAugProcSttsCd())
                .isEqualTo(processingFailed.getAugProcSttsCd());
        assertThat(reviewerRejected.isProcessingFailed()).isFalse();
        assertThat(processingFailed.isProcessingFailed()).isTrue();

        assertThat(aggregate(805000L, reviewerRejected))
                .as("정상 반려는 검수가 끝난 것이므로 COMPLETED 다(장애로 오탐하면 안 된다)")
                .isEqualTo("COMPLETED");
        assertThat(aggregate(806000L, processingFailed)).isEqualTo("FAILED");
    }

    // ─── ③ PII 보류 + 재개 (E-ISSUE-11) ───────────────────────

    @Test
    @DisplayName("비식별_신고_해소시_보류됐던_증강이_재처리된다")
    void withheldResultIsResumedOnDeidentReportResolved() {
        // given: 위탁·수신은 끝났고(전 job SUCCEEDED) 부모 'F' 로 인계가 보류됐던 증강.
        //        해소 후이므로 부모는 이제 'Y' 다.
        LsDataAug aug = pendingAug(807L, 8070L, LsDataAug.AUG_WINTER);
        parentChain(807000L, 8070L, "Y");
        when(augRepository.findByOriginalRawSn(807000L)).thenReturn(List.of(aug));
        when(jobRepository.findByDataAugSnOrderByJobSeqAsc(807L))
                .thenReturn(List.of(succeededJob(807L, 1)));

        // when: Phase 7 이 만든 기존 재개 배선(DeidentReportResolvedEvent)을 그대로 탄다
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(807000L, true));

        // then: 재위탁이 아니라 <결과 인계>만 다시 태워 증강본이 생성된다
        verify(jobSubmitService, never()).submit(any(AugmentRequestedItemEvent.class));
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository).save(any(LsDataRaw.class));
        assertThat(aug.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("보류_상태는_만료_스윕이_회수하지_않는다")
    void withheldAugmentIsNotReclaimedByExpirySweep() {
        // given: PII 보류로 PENDING 에 남은 증강. 그 위탁 job 은 전부 SUCCEEDED(=terminal)다.
        LsDataAug aug = pendingAug(808L, 8080L, LsDataAug.AUG_WINTER);
        parentChain(808000L, 8080L, "F");
        List<LsDataAugJob> jobs = List.of(succeededJob(808L, 1), succeededJob(808L, 2));

        AugmentApplyResult result = rollup.rollUpIfAllTerminal(808L, jobs, "J-1");
        assertThat(result.withheld()).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);

        // then: 만료 스윕의 회수 대상은 <비종결 job>(RECEIVED/RUNNING)뿐이다. 보류된 증강의 job 은
        //       모두 terminal 이라 후보 자체가 아니며, 보류가 실패로 회수되지 않는다.
        assertThat(jobs).allMatch(LsDataAugJob::isTerminal);
        assertThat(aug.getDeadLetterAt())
                .as("보류는 정상 대기다 — 실패로 오인해 dead-letter 를 찍으면 재개가 불가능해진다")
                .isNull();
    }
}
