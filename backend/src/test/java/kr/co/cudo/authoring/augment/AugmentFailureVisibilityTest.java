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
    @Mock private kr.co.cudo.authoring.augment.service.AugmentDiscardService discardService;
    @Mock private AugmentJobSubmitService jobSubmitService;
    @Mock private AugmentMetrics metrics;
    @Mock private AugmentCallbackUrlResolver callbackUrlResolver;
    @Mock private kr.co.cudo.authoring.webhook.service.AugmentJobIdOwnerLookup jobIdOwnerLookup;
    /** 조상 체인 신고 판정 — 기본 stub 은 "신고 없음"(false). */
    @Mock private kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate;
    /** 복사 원본 경로 조달의 진실원(V28/ADR-058) — 부모 게이트는 플래그가 아니라 이 경로를 본다. */
    @Mock private kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository deidentProcLogRepository;

    private AugmentResultService resultService;
    private AugmentJobRollup rollup;
    private AugmentReviewService reviewService;
    private AugmentRequestBridge bridge;

    private final AtomicLong rawSnSeq = new AtomicLong(9000);

    @BeforeEach
    void setUp() {
        var sourceResolver = new kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver(
                deidentProcLogRepository, allowedStorageResolver(), null);
        ReflectionTestUtils.setField(sourceResolver, "storageDeidentifiedPath", DEID_BASE);
        resultService = new AugmentResultService(augRepository, videoRepository, srcRepository,
                asyncAugmentFrameRunner, allowedStorageResolver(), jobIdOwnerLookup, sourceResolver);
        // 부모에 복사할 비식별 영상 경로가 적재된 정상 상태(관제).
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    Long rawSn = inv.getArgument(0);
                    var procLog = kr.co.cudo.authoring.batch.entity.LsDeidentProcLog.request(
                            rawSn, null, "/storage/raw/" + rawSn + ".mp4", "test");
                    procLog.succeed(DEID_BASE + "/videos/" + rawSn + "/deidentified.mp4");
                    return java.util.Optional.of(procLog);
                });
        ReflectionTestUtils.setField(resultService, "storageDeidentifiedPath", DEID_BASE);
        rollup = new AugmentJobRollup(resultService);
        reviewService = new AugmentReviewService(augRepository, reviewRepository, srcRepository,
                videoRepository, externalClient, discardService);
        bridge = new AugmentRequestBridge(jobSubmitService, metrics, resultService, jobRepository);

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
        reviewerRejected.applyGenerationResult(LsDataAug.STTS_REJECTED);

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

    // ─── ③ 부모 판정 (E-ISSUE-11) — 신고는 파생 생성을 막지 않는다 ───

    /**
     * ★ 2026-07-29 확정 — "파생영상은 비식별 신고 체계 바깥" 정책과 대칭이다. 부모가 신고 구간
     * ({@code DE_IDNTF_YN='F'})이어도 증강 콜백이 도착하면 <b>파생을 생성</b>한다. 신고가 막는 것은
     * 외부 위탁(요청·전송)뿐이며, 여기서 보류하면 재개 트리거가 없어 PENDING 영구 고착이 된다.
     */
    @Test
    @DisplayName("부모가_비식별_신고_구간이어도_증강_콜백은_파생영상을_생성한다")
    void reportedParentStillProducesDerivative() {
        // given: 전 job SUCCEEDED. 부모는 신고 구간('F') — 비식별 산출물 자체는 존재한다.
        LsDataAug aug = pendingAug(807L, 8070L, LsDataAug.AUG_WINTER);
        parentChain(807000L, 8070L, "F");
        List<LsDataAugJob> jobs = List.of(succeededJob(807L, 1));

        // when
        AugmentApplyResult result = rollup.rollUpIfAllTerminal(807L, jobs, "J-1");

        // then: 보류가 아니라 정상 인계 — 파생 RAW 가 실제로 생성된다.
        assertThat(result).isEqualTo(AugmentApplyResult.APPLIED);
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository).save(any(LsDataRaw.class));
        assertThat(aug.getDeadLetterAt()).isNull();
    }

    /**
     * 반면 부모가 <b>비식별 미완료</b>({@code 'N'})면 복사할 비식별 영상 파일이 없어 파생 생성이
     * 물리적으로 불가능하다. 재개 트리거도 없으므로 보류가 아니라 <b>실패로 확정</b>한다.
     */
    /**
     * ★ 판정 축이 <b>플래그에서 경로 조달로</b> 바뀌었다(2026-09-02 확정 · V28/ADR-058) — 구 이름
     * 「부모가 비식별 미완료면」의 조건은 <b>결과였지 전제가 아니었다</b>. 관제 자산에서는 결과가 같다.
     */
    @Test
    @DisplayName("복사할_원본_영상_경로를_못_구하면_보류가_아니라_실패로_확정된다")
    void notDeidentifiedParentFailsInsteadOfWithholding() {
        LsDataAug aug = pendingAug(808L, 8080L, LsDataAug.AUG_WINTER);
        parentChain(808000L, 8080L, "N");
        // 비식별을 수행하지 않았으니 성공 처리 이력이 없다 = 복사할 파일이 없다.
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(808000L))
                .thenReturn(java.util.Optional.empty());
        List<LsDataAugJob> jobs = List.of(succeededJob(808L, 1));

        AugmentApplyResult result = rollup.rollUpIfAllTerminal(808L, jobs, "J-1");

        assertThat(result).isEqualTo(AugmentApplyResult.APPLIED);
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.isProcessingFailed())
                .as("재개 트리거 없는 보류는 PENDING 영구 고착이므로 실패로 드러낸다")
                .isTrue();
        verify(videoRepository, never()).save(any(LsDataRaw.class));
    }
}
