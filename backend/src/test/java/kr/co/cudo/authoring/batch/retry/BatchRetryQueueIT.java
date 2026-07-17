package kr.co.cudo.authoring.batch.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 — DB 영속 재시도 큐 {@link BatchRetryQueue} 실 DB(PostgreSQL Testcontainer) 통합 테스트.
 *
 * <p>기존 in-memory 큐는 실패 등록 노드 ≠ 재시도 발화 노드일 때(2노드 Active-Active) 재시도가
 * 유실됐다. 본 IT 는 다음을 실 DB 에서 고정한다.
 * <ol>
 *   <li>DB 영속 회수: 한 인스턴스가 등록한 항목을 다른 인스턴스(=다른 노드)가 폴링해 회수한다.</li>
 *   <li>동시 폴링 직렬화: 동일 항목을 두 노드가 동시 폴링해도 한쪽만 클레임한다(조건부 원자 UPDATE).</li>
 *   <li>소진: 최대 시도 초과 시 EXHAUSTED 마킹(삭제 아님) + 더 이상 폴링되지 않는다.</li>
 * </ol>
 *
 * <p>{@code initialDelaySec=0} 으로 설정해 enqueue 즉시 폴링 가능(NEXT_RTRY_DT=now)하도록 한다.
 * Postgres 컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 */
@SpringBootTest(properties = {
        "authoring.batch.retry.max-attempts=3",
        "authoring.batch.retry.initial-delay-sec=0"
})
@ActiveProfiles("local")
class BatchRetryQueueIT {

    // 노드 A/B 를 시뮬레이션: 동일한 프록시 빈의 각 호출은 @Transactional(REQUIRES_NEW) 로 독립 커밋되며
    // 공유 DB(LS_BAT_RTY_WTNG) 만을 상태 원천으로 삼는다 — 인스턴스별 in-memory 상태가 없다.
    // 따라서 "한 노드가 등록 → 다른 노드가 회수"는 등록 커밋 후 별도 트랜잭션의 폴링으로 재현된다.
    @Autowired
    private BatchRetryQueue queue;

    @Autowired
    private LsBatRtyWtngRepository repository;

    private final TransactionTemplate txTemplate;

    BatchRetryQueueIT(@Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @org.junit.jupiter.api.BeforeEach
    void cleanQueue() {
        // 공유 테이블 오염 차단 — 폴링이 다른 테스트의 잔존 PENDING 행을 회수하지 않도록 초기화.
        txTemplate.executeWithoutResult(s -> repository.deleteAllInBatch());
    }

    private long uniqueRawSn() {
        return System.nanoTime();
    }

    @Test
    @DisplayName("재시도큐_등록후_다른_노드_폴링이_항목을_회수한다")
    void enqueuedOnOneNode_recoveredByAnotherNode() {
        // given — 노드 A 가 실패 항목 등록 후 커밋 (initialDelaySec=0 → 즉시 폴링 가능).
        long rawSn = uniqueRawSn();
        assertThat(queue.enqueueIfRetryable(rawSn)).isTrue();

        // when — 다른 노드(등록과 무관한 독립 REQUIRES_NEW 트랜잭션, 공유 DB)가 폴링.
        Optional<Long> picked = queue.pollReady();

        // then — DB 영속이므로 등록 노드와 다른 트랜잭션에서 정확히 회수한다(in-memory 였다면 유실).
        assertThat(picked).contains(rawSn);
    }

    @Test
    @DisplayName("재시도_동시_폴링시_한_노드만_클레임한다")
    void concurrentPoll_onlyOneNodeClaims() throws Exception {
        // given — 폴링 가능한 항목 1건.
        long rawSn = uniqueRawSn();
        assertThat(queue.enqueueIfRetryable(rawSn)).isTrue();

        // when — 두 노드가 거의 동시에 폴링.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        // 두 스레드가 동일 프록시 빈을 호출하지만 각 pollReady 는 독립 REQUIRES_NEW 트랜잭션이라
        // 서로 다른 노드의 동시 폴링과 동치다(공유 DB 의 조건부 원자 UPDATE 로 직렬화).
        AtomicInteger claimedCount = new AtomicInteger();
        try {
            Future<Optional<Long>> a = pool.submit(() -> { start.await(); return queue.pollReady(); });
            Future<Optional<Long>> b = pool.submit(() -> { start.await(); return queue.pollReady(); });
            start.countDown();
            if (a.get(30, TimeUnit.SECONDS).isPresent()) claimedCount.incrementAndGet();
            if (b.get(30, TimeUnit.SECONDS).isPresent()) claimedCount.incrementAndGet();
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 한 노드만 클레임(조건부 원자 UPDATE 로 직렬화). 이중 재시도 없음.
        assertThat(claimedCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("최대시도_초과시_소진마킹되고_재폴링되지_않는다")
    void maxAttemptsExceeded_exhaustedAndNotPolled() {
        // given — max-attempts=3. 3회까지 재시도 등록 허용.
        long rawSn = uniqueRawSn();
        assertThat(queue.enqueueIfRetryable(rawSn)).isTrue(); // attempt 1
        assertThat(queue.enqueueIfRetryable(rawSn)).isTrue(); // attempt 2
        assertThat(queue.enqueueIfRetryable(rawSn)).isTrue(); // attempt 3

        // when — 4번째 등록 → 거부 + 소진 마킹.
        boolean fourth = queue.enqueueIfRetryable(rawSn);

        // then — 등록 거부.
        assertThat(fourth).isFalse();
        // 행은 삭제되지 않고 EXHAUSTED 로 이력 보존.
        LsBatRtyWtng row = txTemplate.execute(s -> repository.findByRawSn(rawSn).orElseThrow());
        assertThat(row.getSttsCd()).isEqualTo(LsBatRtyWtng.STATUS_EXHAUSTED);
        assertThat(row.getRtyNmtm()).isEqualTo(4);
        // 소진 항목은 더 이상 폴링되지 않는다.
        assertThat(queue.pollReady()).isEmpty();
    }

    @Test
    @DisplayName("동시_최초등록시_UK위반이_전파되지_않는다")
    void concurrentFirstEnqueue_noUniqueViolationPropagated() throws Exception {
        // given — 아직 큐에 없는 rawSn 을 두 노드가 거의 동시에 최초 등록(find-or-create 경쟁).
        long rawSn = uniqueRawSn();

        // when — 두 스레드가 동시에 enqueueIfRetryable. UK_LBRW_RAW_SN 경쟁 발생 지점.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a = pool.submit(() -> { start.await(); return queue.enqueueIfRetryable(rawSn); });
            Future<Boolean> b = pool.submit(() -> { start.await(); return queue.enqueueIfRetryable(rawSn); });
            start.countDown();
            // then — 둘 다 예외 없이 정상 반환(DataIntegrityViolationException 미전파, ON CONFLICT + FOR UPDATE).
            assertThat(a.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 영상 1건 = 1행 멱등 유지 + FOR UPDATE 직렬화로 두 증가가 모두 반영(RTY_NMTM=2).
        LsBatRtyWtng row = txTemplate.execute(s -> repository.findByRawSn(rawSn).orElseThrow());
        assertThat(row.getRtyNmtm()).isEqualTo(2);
    }

    @Test
    @DisplayName("성공_clear시_항목이_제거된다")
    void clearRemovesEntry() {
        long rawSn = uniqueRawSn();
        assertThat(queue.enqueueIfRetryable(rawSn)).isTrue();
        assertThat(queue.retryCount(rawSn)).isEqualTo(1);

        queue.clear(rawSn);

        assertThat(queue.retryCount(rawSn)).isZero();
        Optional<LsBatRtyWtng> after = txTemplate.execute(s -> repository.findByRawSn(rawSn));
        assertThat(after).isEmpty();
    }
}
