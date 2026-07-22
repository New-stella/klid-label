package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRITICAL 재설계 검증 — 미배정 직접 마킹 경로의 <b>신규 row 생성 경합</b>을 실 DB(PostgreSQL
 * Testcontainer)로 고정한다.
 *
 * <p>Mockito 단위 테스트({@code BatchTransitionServiceTest})는 {@code saveAndFlush} 의 flush 타이밍이나
 * PostgreSQL 의 unique 위반 → 트랜잭션 abort 를 재현하지 못한다. 본 IT 는 {@link MarkingBatchBridge}
 * 가 사용하는 <b>정확히 동일한 2단계 클레임 시퀀스</b>(tx1 {@link BatchTransitionService#tryClaimBatchQueued}
 * → false 면 tx2 {@link BatchTransitionService#tryCreateBatchQueuedRow}, DIV 는 catch 후 skip)를 2 스레드로
 * 동시에 돌려 다음을 단언한다.
 *
 * <ol>
 *   <li>작업 상태 row 가 <b>없는</b> rawSn 에 두 스레드가 동시에 클레임을 시도하면 <b>정확히 1건만</b>
 *       트리거 권한(true)을 얻고, 나머지는 <b>조용히 false</b>(예외 미전파)로 skip 한다.</li>
 *   <li>경합 후 DB 에는 해당 rawSn row 가 <b>정확히 1건</b>, 상태 {@code BATCH_QUEUED} 로 남는다.</li>
 * </ol>
 *
 * <p>이 IT 가 실패하면(예: 둘 다 true, 또는 DataIntegrityViolationException 이 스레드 밖으로 전파, 또는
 * "current transaction is aborted" 로 재시도 실패) 재설계가 회귀한 것이다. 컨테이너는 {@code spring.factories}
 * 의 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class BatchTransitionServiceRowCreationIT {

    private static final Set<String> SKIP = Set.of(
            LsRawDataStatus.STTS_BATCH_QUEUED,
            LsRawDataStatus.STTS_PROCESSING,
            LsRawDataStatus.STTS_COMPLETED);

    @Autowired
    private LsRawDataStatusRepository repository;

    @Autowired
    private BatchTransitionService batchTransitionService;

    private final TransactionTemplate txTemplate;

    BatchTransitionServiceRowCreationIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    /**
     * {@link MarkingBatchBridge#onMarkingCompleted} 의 클레임 시퀀스와 동일한 로직.
     * 각 서비스 메서드는 REQUIRES_NEW 로 독립 커밋되므로 여기엔 활성 tx 가 없어도 된다.
     */
    private boolean bridgeClaim(long rawSn) {
        boolean claimed = batchTransitionService.tryClaimBatchQueued(rawSn, SKIP);
        if (!claimed) {
            try {
                claimed = batchTransitionService.tryCreateBatchQueuedRow(rawSn);
            } catch (DataIntegrityViolationException e) {
                claimed = false; // 동시 노드가 먼저 생성 → 조용히 skip.
            }
        }
        return claimed;
    }

    private long countRows(long rawSn) {
        return txTemplate.execute(s -> repository.existsById(rawSn) ? 1L : 0L);
    }

    private String reloadStatus(long rawSn) {
        return txTemplate.execute(s -> repository.findById(rawSn).orElseThrow().getDataSttsCd());
    }

    @Test
    @DisplayName("동시_2스레드_row부재_rawSn_클레임 — 정확히1건만_true_나머지는_예외없이_false_DB엔_BATCH_QUEUED_1건")
    void concurrentRowCreation_exactlyOneClaims_noExceptionPropagates() throws Exception {
        // given — 작업 상태 row 가 전혀 없는 rawSn (미배정 REVIEWER 직접 마킹 코호트)
        long rawSn = System.nanoTime();
        assertThat(countRows(rawSn)).isZero();

        // when — 두 스레드가 거의 동시에 브리지 클레임 시퀀스를 실행
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger trueCount = new AtomicInteger();
        AtomicInteger exceptionCount = new AtomicInteger();
        try {
            Future<Boolean> a = pool.submit(() -> awaitAndClaim(start, rawSn, exceptionCount));
            Future<Boolean> b = pool.submit(() -> awaitAndClaim(start, rawSn, exceptionCount));
            start.countDown();
            if (Boolean.TRUE.equals(a.get(30, TimeUnit.SECONDS))) {
                trueCount.incrementAndGet();
            }
            if (Boolean.TRUE.equals(b.get(30, TimeUnit.SECONDS))) {
                trueCount.incrementAndGet();
            }
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 1건만 트리거 권한 획득, 예외는 스레드 밖으로 전파되지 않음, DB 엔 BATCH_QUEUED 1건
        assertThat(trueCount.get()).isEqualTo(1);
        assertThat(exceptionCount.get()).isZero();
        assertThat(countRows(rawSn)).isEqualTo(1L);
        assertThat(reloadStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
    }

    private boolean awaitAndClaim(CountDownLatch start, long rawSn, AtomicInteger exceptionCount)
            throws InterruptedException {
        start.await();
        try {
            return bridgeClaim(rawSn);
        } catch (RuntimeException e) {
            // bridgeClaim 이 DIV 를 삼키므로 여기 도달하면 재설계 회귀(예외 전파).
            exceptionCount.incrementAndGet();
            return false;
        }
    }
}
