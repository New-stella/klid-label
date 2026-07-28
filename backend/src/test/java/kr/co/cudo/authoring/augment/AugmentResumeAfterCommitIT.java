package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>AFTER_COMMIT 재개 배선</b> 통합 테스트 (실 스프링 컨텍스트 + 실 DB) — Phase 8 DEV_FIX HIGH-1.
 *
 * <h3>왜 목 호출로는 이 결함을 잡을 수 없었나</h3>
 * <p>기존 테스트({@code AugmentFailureVisibilityTest})는 리스너 메서드를 <b>트랜잭션 밖에서 목
 * 리포지토리로 직접</b> 호출했다. 그래서 "AFTER_COMMIT 콜백 안에서 {@code PROPAGATION_REQUIRED} 를
 * 부르면 <b>이미 커밋된</b> 트랜잭션에 참여해 ①뒤따르는 커밋이 없고 ②{@code FOR UPDATE} 잠금 조회가
 * {@code TransactionRequiredException} 으로 튄다" 는 실제 실행 의미론을 <b>전혀 재현하지 못했고</b>,
 * 그 예외를 리스너의 건별 catch 가 삼켜 보류분이 조용히 PENDING 에 영구 고착됐다.
 *
 * <p>따라서 이 테스트는 <b>실제 트랜잭션 안에서 이벤트를 발행하고 커밋시켜</b> 스프링이
 * {@code TransactionSynchronization.afterCommit} 을 태우게 한다({@link TransactionTemplate}).
 * 단언 대상은 인메모리 객체 상태가 아니라 <b>커밋된 DB 행</b>이다 — "참여만 하고 커밋되지 않는"
 * 실패 모드는 DB 를 다시 읽어야만 드러나기 때문이다.
 *
 * <p>리스너는 {@code @Async} 라 발행 스레드와 분리되므로 {@link Awaitility} 로 커밋 결과를 기다린다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AugmentResumeAfterCommitIT {

    /** 파생 산출물 base — 경로 계산만 하고 파일은 async 러너가 다룬다(실패해도 재개 판정과 무관). */
    private static final Path TMP_ROOT = createTempRoot();

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("augresume-store");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.deidentified-path", () -> TMP_ROOT.resolve("deid").toString());
        registry.add("authoring.storage.raw-mount-roots", () -> "/storage," + TMP_ROOT);
    }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private kr.co.cudo.authoring.augment.listener.AugmentRequestBridge bridge;
    @Autowired @Qualifier("batchAsyncExecutor") private Executor batchAsyncExecutor;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository augJobRepository;
    @Autowired private LsDataAugJobFileRepository augJobFileRepository;

    private TransactionTemplate txTemplate;

    @Autowired
    void setTxTemplate(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ─── 시드 ─────────────────────────────────────────────────

    private record Seed(Long parentRawSn, Long srcSn, Long dataAugSn, String requestId) { }

    /**
     * 부모(비식별 완료 'Y') + 프레임 1건 + PENDING 외부 증강 1건.
     *
     * @param deidFramePath 프레임의 비식별 경로. {@code null} 이면 위탁이 fail-closed 로 거부된다.
     */
    private Seed seed(String suffix, String deidFramePath) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "ARAC-" + suffix + "-" + UUID.randomUUID(), "CCTV-ARAC", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/ARAC-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        LsDataSrc frame = srcRepository.save(LsDataSrc.create(
                parent.getRawSn(), 0, 0L, parent.getRawSn() + "/f0.jpg",
                deidFramePath, LocalDateTime.now()));

        String requestId = "ARAC-K-" + suffix + "-" + UUID.randomUUID();
        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame.getSrcSn(), LsDataAug.AUG_WINTER, "1", requestId, null));
        return new Seed(parent.getRawSn(), frame.getSrcSn(), aug.getDataAugSn(), requestId);
    }

    /** 위탁·수신은 끝났고(전 job SUCCEEDED) 부모 'F' 때문에 결과 인계만 보류됐던 상태를 만든다. */
    private void seedSucceededJob(Seed s) {
        LsDataAugJob job = LsDataAugJob.createIssued(s.dataAugSn(), 1, s.requestId(), 1);
        job.markSucceeded("J-" + s.requestId());
        job = augJobRepository.save(job);
        augJobFileRepository.save(LsDataAugJobFile.issued(job.getAugJobSn(), 1, s.srcSn()));
    }

    /** 트랜잭션 <b>안에서</b> 이벤트를 발행하고 커밋한다 — 이래야 AFTER_COMMIT 이 실제로 태워진다. */
    private void publishAndCommit(Object event) {
        txTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private String augStatusOf(Long dataAugSn) {
        return augRepository.findById(dataAugSn).map(LsDataAug::getAugProcSttsCd).orElse(null);
    }

    private Optional<LsDataRaw> childOf(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .findFirst();
    }

    private static void awaitUntil(java.util.concurrent.Callable<Boolean> condition) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(condition);
    }

    // ─── ① 보류 재개 (HIGH-1 핵심) ────────────────────────────

    /**
     * 시나리오: 부모 {@code 'F'} → 콜백 롤업이 {@code WITHHELD_PARENT_NOT_DEIDENTIFIED}(PENDING 유지)
     * → REVIEWER 가 신고 해소 → {@code DeidentReportResolvedEvent}(AFTER_COMMIT) → 결과 인계 재개.
     *
     * <p>구 배선에서는 재개가 커밋 스레드에서 돌아 잠금 조회가 튀고 그 예외가 삼켜져 <b>DB 는 PENDING
     * 그대로</b>였다 — 그래서 단언 대상이 반드시 재조회한 DB 행이어야 한다.
     */
    @Test
    @DisplayName("AFTER_COMMIT_재개_리스너가_실제_스프링_컨텍스트에서_보류분을_재처리한다")
    void afterCommitListenerResumesWithheldResultInRealContext() {
        // given: 결과 인계 보류 상태(전 job SUCCEEDED · 증강 PENDING). 해소 후이므로 부모는 'Y'.
        Seed s = seed("RESUME", "/storage/deid/f0.jpg");
        seedSucceededJob(s);
        assertThat(augStatusOf(s.dataAugSn())).isEqualTo(LsDataAug.STTS_PENDING);

        // when: 신고 해소 트랜잭션이 커밋되며 AFTER_COMMIT 리스너가 태워진다(목 호출 아님).
        publishAndCommit(new DeidentReportResolvedEvent(s.parentRawSn(), false));

        // then: 재개 결과가 <커밋되어> 관측된다. 참여만 하고 커밋이 없으면 영원히 PENDING 이다.
        awaitUntil(() -> LsDataAug.STTS_ACCEPTED.equals(augStatusOf(s.dataAugSn())));
        assertThat(childOf(s.parentRawSn()))
                .as("결과 인계가 실제로 다시 태워졌다면 파생 영상 행이 생성·커밋된다")
                .isPresent();
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getRetryCount())
                .as("재개 시도(REQUIRES_NEW)도 함께 커밋돼야 운영이 시도 횟수를 본다")
                .isPositive();
    }

    /**
     * MEDIUM-1 — 보류 재개는 검수 상태와 무관해야 한다. 이 테스트가 {@code reviewApproved=false} 로
     * 발행하는 것 자체가 그 증거다(구 구현은 미승인 영상에 이벤트를 아예 발행하지 않아, 증강 요청 후
     * {@code APPROVED → PENDING} 재제출된 영상의 보류가 영구화됐다).
     *
     * <h3>적대검증 2차 LOW-2 — 단언을 {@code REQUIRED} 경로 결과로 바꿔 변별력을 준다</h3>
     * <p>구 단언({@code retryCount>0} · job 행 존재)은 각각 {@code recordResumeAttempt} 와
     * {@code AugmentJobRecorder} 로 <b>둘 다 {@code REQUIRES_NEW}</b> 경로였다. 그래서 재개가 커밋
     * 스레드에서 동기 실행돼도(=HIGH-1 회귀) 독립 커밋되어 <b>GREEN 이 유지</b>됐다 — 방어를 되돌려도
     * 통과하는 비변별 단언이었다. 위탁 0건 롤업 결과({@code REJECTED} + dead-letter)는
     * {@code AugmentResultService.handle}(REQUIRED)가 <b>실제로 커밋해야만</b> 생기는 상태이므로
     * 그것을 주 단언으로 삼는다.
     */
    @Test
    @DisplayName("보류_해제_이벤트가_보류_복구에_도달한다")
    void resolvedEventReachesWithheldRecovery() {
        // given: 위탁 전 보류(job 행 0건) — 프레임 비식별 경로가 없어 재위탁은 fail-closed 로 거부된다.
        Seed s = seed("REACH", null);

        // when: 미승인(APPROVED 아님) 영상의 해소 이벤트
        publishAndCommit(new DeidentReportResolvedEvent(s.parentRawSn(), false));

        // then ①(변별 단언): 위탁 0건 → 실패 롤업이 REQUIRED 인계로 <커밋>되어야 종결이 관측된다.
        awaitUntil(() -> LsDataAug.STTS_REJECTED.equals(augStatusOf(s.dataAugSn())));
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().isProcessingFailed())
                .as("REQUIRED 인계가 커밋되지 않으면 PENDING 그대로 남아 집계가 실패를 못 본다")
                .isTrue();

        // then ②: 재개 경로 <도달> 흔적(REQUIRES_NEW 축) — 도달성 검증으로서의 가치는 유지한다.
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getRetryCount()).isPositive();
        assertThat(augJobRepository.findByDataAugSnOrderByJobSeqAsc(s.dataAugSn()))
                .as("재개가 위탁을 실제로 시도했다면 fail-closed 거부 사유가 job 행으로 남는다")
                .isNotEmpty();
    }

    // ─── ①-B MEDIUM-1: 스레드 가정에 의존하지 않는 커밋 보장 ───

    /**
     * 적대검증 2차 MEDIUM-1 — {@code @Async} 는 "다른 스레드" 를 <b>보장하지 않는다</b>.
     *
     * <p>{@code batchAsyncExecutor} 의 거부 정책은 {@link ThreadPoolExecutor.CallerRunsPolicy} 라,
     * 풀이 포화되면 <b>호출 스레드가 직접</b> 리스너 본문을 실행한다. 그 호출 스레드는 곧
     * {@code TransactionSynchronization.afterCommit} 문맥(원 트랜잭션 리소스가 아직 바인딩된 상태)이므로
     * 인계가 {@code REQUIRED} 로 이미 커밋된 트랜잭션에 참여해 <b>커밋 없이 사라진다</b>.
     *
     * <p>이 테스트는 스레드풀 상태에 의존하지 않고 그 상황을 <b>직접</b> 재현한다 — {@code @Async}
     * 프록시를 벗긴 대상 빈을 afterCommit 콜백 안에서 <b>동기 호출</b>한다. 따라서 배선이
     * "다른 스레드" 가정에 기대고 있으면 반드시 RED 다.
     */
    @Test
    @DisplayName("afterCommit_스레드에서_동기_실행돼도_인계가_커밋된다")
    void handOffCommitsEvenWhenListenerRunsOnCommitThread() {
        // given: 결과 인계 보류(전 job SUCCEEDED · 증강 PENDING)
        Seed s = seed("SYNCTX", "/storage/deid/f0.jpg");
        seedSucceededJob(s);
        // @Async 프록시를 벗겨 "커밋 스레드에서 동기 실행" 을 강제한다(CallerRunsPolicy 와 동일 문맥).
        var target = AopTestUtils.<kr.co.cudo.authoring.augment.listener.AugmentRequestBridge>getTargetObject(bridge);
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        // when: afterCommit 콜백 안에서 리스너 본문을 그대로 실행
        txTemplate.executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ranOn.set(Thread.currentThread());
                        target.onDeidentReportResolved(
                                new DeidentReportResolvedEvent(s.parentRawSn(), false));
                    }
                }));

        // then: 커밋 스레드에서 돌았는데도 인계가 <커밋>되어 관측된다.
        assertThat(ranOn.get())
                .as("이 테스트의 전제 — 재개가 커밋 스레드에서 동기 실행됐다")
                .isSameAs(Thread.currentThread());
        assertThat(augStatusOf(s.dataAugSn()))
                .as("스레드 가정에 기대면 REQUIRED 인계가 커밋되지 않아 PENDING 이 남는다")
                .isEqualTo(LsDataAug.STTS_ACCEPTED);
    }

    /**
     * 같은 결함을 <b>실제 거부 정책 발동</b>으로 재현한다 — 풀(core 2 / max 4 / queue 50)을 가득 채운 뒤
     * 이벤트를 커밋하면 리스너 제출이 거부되어 {@code CallerRunsPolicy} 가 커밋 스레드에서 인라인 실행한다.
     *
     * <p>단언을 <b>대기 없이 즉시</b> 한다: 풀이 포화라 비동기 실행은 아직 시작조차 못 하므로,
     * 즉시 관측되는 종결은 "인라인 실행 + 독립 커밋" 이 성립했다는 증거다(고정 sleep 없음).
     */
    @Test
    @DisplayName("스레드풀_포화시에도_보류_재개가_유실되지_않는다")
    void withheldResumeSurvivesExecutorSaturation() throws Exception {
        // given: 결과 인계 보류 1건
        Seed s = seed("SATUR", "/storage/deid/f0.jpg");
        seedSucceededJob(s);

        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) batchAsyncExecutor).getThreadPoolExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(Math.min(pool.getMaximumPoolSize(), 4));
        int capacity = pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity();
        try {
            for (int i = 0; i < capacity; i++) {
                pool.execute(() -> {
                    blocked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(blocked.await(10, TimeUnit.SECONDS))
                    .as("풀 스레드가 모두 점유돼야 거부 정책이 발동한다")
                    .isTrue();
            assertThat(pool.getQueue().remainingCapacity())
                    .as("큐까지 가득 차야 다음 제출이 거부된다")
                    .isZero();

            // when: 이 시점의 제출은 거부 → CallerRunsPolicy 가 커밋 스레드에서 인라인 실행한다.
            publishAndCommit(new DeidentReportResolvedEvent(s.parentRawSn(), false));

            // then: 대기 없이 즉시 종결이 관측된다(비동기였다면 큐에 갇혀 아직 PENDING 이다).
            assertThat(augStatusOf(s.dataAugSn()))
                    .as("풀 포화 시 인계가 유실되면 보류분이 조용히 PENDING 에 영구 고착된다")
                    .isEqualTo(LsDataAug.STTS_ACCEPTED);
        } finally {
            release.countDown();
        }
    }

    // ─── ② 위탁 0건 실패 롤업도 AFTER_COMMIT 에서 커밋돼야 한다 ──

    /**
     * 같은 함정의 이웃 경로: 원 요청 AFTER_COMMIT 리스너의 <b>위탁 0건 즉시 실패 롤업</b>도
     * {@code findByDataAugSnForUpdate} 로 시작한다. 커밋 스레드에서 돌면 예외가 나고 삼켜져
     * "콜백은 영영 안 오는데 롤업도 안 된" PENDING 고착이 그대로 남는다.
     */
    @Test
    @DisplayName("위탁_0건_실패_롤업이_AFTER_COMMIT_에서_실제로_커밋된다")
    void failureRollupCommitsFromAfterCommitListener() {
        // given: 비식별 프레임 경로 부재 → PII fail-closed 로 한 건도 위탁되지 않는다.
        Seed s = seed("ROLLUP", null);

        // when: 증강 요청 트랜잭션이 커밋되며 위탁 리스너가 태워진다.
        publishAndCommit(new AugmentRequestedItemEvent(
                s.dataAugSn(), s.parentRawSn(), LsDataAug.AUG_WINTER, s.requestId(),
                "http://localhost:8080/api/v1/genai/callback", "1"));

        // then: 실패 롤업이 커밋되어 집계에 드러난다(REJECTED + 처리 실패 마커).
        awaitUntil(() -> LsDataAug.STTS_REJECTED.equals(augStatusOf(s.dataAugSn())));
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().isProcessingFailed())
                .as("롤업이 커밋되지 않으면 실패한 증강이 집계에서 '완료' 로 보인다")
                .isTrue();
    }
}
