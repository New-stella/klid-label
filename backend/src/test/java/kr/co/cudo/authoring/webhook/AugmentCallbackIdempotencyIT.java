package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentOutcome;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 증강 결과 인계 <b>멱등 계약</b> 통합 테스트 (실 DB, PostgreSQL Testcontainer) — Phase 8-C / E-ISSUE-05.
 *
 * <h3>왜 실 DB 여야 하는가 (AC-10)</h3>
 * <p>기존 방어는 {@code AugmentResultServiceTest} 의 Mockito 스텁으로만 검증돼 있었다. 스텁은
 * {@code DataIntegrityViolationException} 을 "던지는 흉내" 만 낼 뿐, 이 결함의 본체인 <b>PostgreSQL
 * 의 aborted transaction(25P02)</b> — 제약 위반 이후 같은 트랜잭션의 모든 쿼리가 거부되는 동작 — 을
 * 전혀 재현하지 못한다(위양성 GREEN). {@code AugmentDeidentConcurrencyIT} 도 서로 <b>다른</b>
 * {@code otsd_job_id} 를 쓰기 때문에 UNIQUE({@code uk_aug_external_job_id}) 경로를 발동시키지 않는다.
 * 그래서 <b>같은 job_id 충돌을 실 DB 로 재현하는 통합테스트가 0건</b>이었다.
 *
 * <h3>고정하는 계약</h3>
 * <ul>
 *   <li><b>재수신 = 200 no-op</b> — 같은 증강이 같은 job_id 로 다시 오면 상태를 바꾸지 않고 흡수한다
 *       ({@link AugmentApplyResult#DUPLICATE} = {@code applied:false}). 외부는 웹훅을 재시도하므로
 *       이는 <b>정상 시나리오</b>이며 오류(409)로 회신하면 인계 실패로 오해된다.</li>
 *   <li><b>진짜 충돌 = 409</b> — 다른 증강이 이미 그 job_id 를 보유한 오배송만 409 다.</li>
 *   <li><b>어떤 경우에도 500 이 나오지 않는다</b> — UNIQUE 위반 이후 같은 트랜잭션에서 재조회하면
 *       25P02 로 500 이 된다. 그 경로가 남아 있으면 아래 동시성 테스트가 CONFLICT 가 아닌 예외로
 *       실패한다.</li>
 * </ul>
 *
 * <p>공유 Testcontainers PG 를 쓰므로 시드는 {@code AUGIDEM-} 고유 clipId 로 만들고 단언은 시드한
 * parentRawSn 파생으로만 좁힌다(다른 통합테스트 데이터 오염 방지).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 콜백 픽스처 경로(/storage/augment/*.mp4)가 적재 시점 allowlist 검증을 통과하도록 명시.
        "authoring.storage.raw-mount-roots=/storage"
})
class AugmentCallbackIdempotencyIT {

    @Autowired private AugmentResultService service;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository
            deidentProcLogRepositoryForFixture;
    @Autowired private PlatformTransactionManager controlTransactionManager;

    private record Seed(Long parentRawSn, Long srcSn, Long dataAugSn) { }

    /** 부모(비식별 완료 'Y') + 프레임 1건 + PENDING 증강 1건. */
    private Seed seed(String suffix) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGIDEM-" + suffix + "-" + UUID.randomUUID(), "CCTV-AUGIDEM", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/AUGIDEM-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        seedParentDeidVideo(parent.getRawSn(), parent.getRawFilePathNm());
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, parent.getRawSn() + "/f0.jpg", null));
        return new Seed(parent.getRawSn(), frame.getSrcSn(),
                newAug(frame.getSrcSn(), LsDataAug.AUG_WINTER, suffix));
    }

    /** 같은 부모(=같은 대표프레임)에 다른 종류의 PENDING 증강을 하나 더 만든다. */
    private Long newAug(Long srcSn, String augType, String suffix) {
        return augRepository.save(LsDataAug.createRequested(
                srcSn, augType, "1", "AUGIDEM-K-" + augType + "-" + suffix + "-" + UUID.randomUUID(),
                null)).getDataAugSn();
    }

    private long countChildren(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .count();
    }

    private LsDataAug reload(Long dataAugSn) {
        return augRepository.findById(dataAugSn).orElseThrow();
    }

    private static String jobId(String suffix) {
        return "AUGIDEM-J-" + suffix + "-" + UUID.randomUUID();
    }

    // ─── ① 재수신 = 200 no-op ─────────────────────────────────

    @Test
    @DisplayName("같은_증강의_콜백_재수신은_200_no_op_이다")
    void sameAugmentRedeliveryIsAbsorbed() {
        Seed s = seed("REDELIVER");
        String job = jobId("REDELIVER");
        AugmentOutcome outcome = AugmentOutcome.succeeded(s.dataAugSn(), job);

        assertThat(service.handle(outcome)).isEqualTo(AugmentApplyResult.APPLIED);

        // 재수신 — 외부 재시도(최대 2회)로 같은 페이로드가 다시 온다. 오류가 아니라 흡수여야 한다.
        AugmentApplyResult second = service.handle(outcome);

        assertThat(second).isEqualTo(AugmentApplyResult.DUPLICATE);
        assertThat(second.applied()).as("재수신은 applied:false 로 200 회신된다").isFalse();
        assertThat(reload(s.dataAugSn()).getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(reload(s.dataAugSn()).getExternalJobId()).isEqualTo(job);
        assertThat(countChildren(s.parentRawSn()))
                .as("재수신이 증강 영상을 한 번 더 만들면 안 된다").isEqualTo(1);
    }

    // ─── ② 진짜 선점 충돌 = 409 ──────────────────────────────

    @Test
    @DisplayName("다른_증강이_같은_job_id_를_선점하면_409")
    void otherAugmentOwningJobIdIsConflict() {
        Seed winner = seed("OWNER");
        Seed loser = seed("MISDELIVERY");
        String job = jobId("OWNER");

        assertThat(service.handle(AugmentOutcome.succeeded(winner.dataAugSn(), job)))
                .isEqualTo(AugmentApplyResult.APPLIED);

        // 다른 증강에 같은 job_id 가 실려 오면(오배송) 흡수하면 안 된다 — 409.
        assertThatThrownBy(() -> service.handle(AugmentOutcome.succeeded(loser.dataAugSn(), job)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 충돌은 쓰기 이전에 종결된다 — 대상 증강은 손상되지 않고 PENDING 으로 남아 재인계가 가능하다.
        assertThat(reload(loser.dataAugSn()).getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(countChildren(loser.parentRawSn())).isZero();
        assertThat(countChildren(winner.parentRawSn())).isEqualTo(1);
    }

    // ─── ③ AC-10: 동일 콜백 동시 2건 ─────────────────────────

    @Test
    @DisplayName("동일_콜백_동시_2건_수신시_1건만_반영되고_500_이_발생하지_않는다")
    void concurrentIdenticalCallbacksApplyExactlyOnce() throws Exception {
        Seed s = seed("SAMEJOB");
        AugmentOutcome outcome = AugmentOutcome.succeeded(s.dataAugSn(), jobId("SAMEJOB"));

        List<AugmentApplyResult> results = runConcurrently(outcome, outcome);

        // 한쪽만 반영되고 다른 쪽은 멱등 흡수 — 예외(500) 없이 끝난다.
        assertThat(results).containsExactlyInAnyOrder(
                AugmentApplyResult.APPLIED, AugmentApplyResult.DUPLICATE);
        assertThat(reload(s.dataAugSn()).getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(countChildren(s.parentRawSn()))
                .as("동시 재수신이 증강 영상을 이중 생성하면 안 된다").isEqualTo(1);
    }

    // ─── ④ PG 25P02 회귀 가드 ───────────────────────────────

    /**
     * <b>PostgreSQL 25P02 회귀 가드 (결정적)</b> — 실 DB 가 아니면 재현되지 않는 결함이다.
     *
     * <p>{@code handle} 은 웹훅 트랜잭션({@code GenAiCallbackService.handle})에 <b>조인</b>돼 실행된다.
     * 여기서 {@code uk_aug_external_job_id} UNIQUE 위반이 나면 PostgreSQL 이 그 트랜잭션을 abort 로
     * 만들어 <b>이후 모든 쿼리를 25P02 로 거부</b>한다(= 500). 그래서 오배송 충돌은 <b>쓰기 이전</b>에
     * 종결돼야 하며, 그러면 호출자 트랜잭션이 오염되지 않는다.
     *
     * <p>본 테스트는 그것을 직접 관측한다 — 바깥 트랜잭션 안에서 오배송 콜백을 처리해 409 를 받은 뒤
     * <b>같은 트랜잭션에서 후속 조회</b>를 시도한다. 충돌 판정이 UNIQUE 위반에 의존하도록 되돌아가면
     * 이 후속 조회가 25P02 로 실패한다(RED).
     */
    @Test
    @DisplayName("UNIQUE_충돌_후에도_같은_트랜잭션의_후속_쿼리가_실패하지_않는다")
    void conflictDoesNotPoisonCallerTransaction() {
        Seed winner = seed("PG25P02-OWNER");
        Seed loser = seed("PG25P02-LOSER");
        String job = jobId("PG25P02");
        assertThat(service.handle(AugmentOutcome.succeeded(winner.dataAugSn(), job)))
                .isEqualTo(AugmentApplyResult.APPLIED);

        TransactionTemplate outer = new TransactionTemplate(controlTransactionManager);
        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> service.handle(AugmentOutcome.succeeded(loser.dataAugSn(), job)))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);

            // 트랜잭션이 abort 됐다면(=위반을 일으킨 뒤 판정했다면) 이 조회가 25P02 로 실패한다.
            // 1차 캐시로 흡수되지 않도록 <반드시 SQL 이 나가는> 집계 쿼리를 쓴다.
            assertThat(augRepository.count())
                    .as("충돌 판정은 쓰기 이전에 끝나야 호출자 트랜잭션이 살아남는다")
                    .isPositive();
            status.setRollbackOnly();
        });

        assertThat(reload(loser.dataAugSn()).getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(countChildren(loser.parentRawSn())).isZero();
    }

    private Void invoke(AugmentOutcome outcome, CountDownLatch started,
                        List<AugmentApplyResult> results, AtomicReference<Throwable> error) {
        started.countDown();
        try {
            AugmentApplyResult result = service.handle(outcome);
            synchronized (results) {
                results.add(result);
            }
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        }
        return null;
    }

    /** 두 콜백을 같은 순간에 출발시켜 실행하고 결과(예외는 실패)로 회수한다. */
    private List<AugmentApplyResult> runConcurrently(AugmentOutcome first, AugmentOutcome second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<AugmentApplyResult> results = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            Future<?> a = pool.submit(() -> gated(first, ready, start, results, error));
            Future<?> b = pool.submit(() -> gated(second, ready, start, results, error));
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(error.get()).as("동시 재수신은 예외 없이 직렬화돼야 한다(500 금지)").isNull();
        return results;
    }

    private Void gated(AugmentOutcome outcome, CountDownLatch ready, CountDownLatch start,
                       List<AugmentApplyResult> results, AtomicReference<Throwable> error) {
        try {
            ready.countDown();
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return invoke(outcome, new CountDownLatch(1), results, error);
    }

    /**
     * 부모 <b>비식별 영상 산출물</b> 처리 이력 — 파생 복사 원본 경로의 진실원(V28/ADR-058).
     *
     * <p>흡수 이전 부모 게이트는 {@code DE_IDENT_YN} <b>플래그</b>만 봤고 실제 경로는 Phase A 에서야
     * 확인했다. 지금은 게이트가 <b>경로를 직접 조달</b>하므로(「비식별이 끝났나」는 조건이 아니라
     * 결과였다) 플래그만 세운 부모로는 통과하지 않는다 — 관제 경로가 <b>더 엄격해진</b> 지점이다.
     * 경로는 상대값으로 둔다: co-locate 디렉터리와 비식별 저장소 서브트리 <b>양쪽</b>에서 해석된다.
     */
    private void seedParentDeidVideo(Long parentRawSn, String orgnlFilePath) {
        var procLog = kr.co.cudo.authoring.batch.entity.LsDeidentProcLog.request(
                parentRawSn, null, orgnlFilePath, "test");
        procLog.succeed("videos/" + parentRawSn + "/deidentified.mp4");
        deidentProcLogRepositoryForFixture.saveAndFlush(procLog);
    }

}
