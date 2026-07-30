package kr.co.cudo.authoring.batch.retry;

import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-ISSUE-83 (Phase 9-C) — 배치 재시도 큐의 <b>stale RETRYING 회수</b> 통합 테스트
 * (실 DB, PostgreSQL Testcontainer).
 *
 * <p>배경: {@code RETRYING} 은 폴링 노드가 원자 클레임으로 찍은 "처리 중" 표시이고, {@code PENDING}
 * 복귀는 {@code BatchRetryQuartzJob#execute} 가 정상적으로 예외를 받을 때만 일어난다. 그 노드가
 * 처리 도중 죽으면(kill -9 · OOM · 순단) 아무도 되돌리지 않아 항목이 <b>영구 RETRYING</b> 으로 남고
 * 해당 영상의 재시도가 무음 중단된다(2노드 Active-Active 라 살아 있는 노드도 집지 못한다).
 *
 * <p>시간 경과는 <b>cutoff 주입</b>으로 재현한다(행 시각 조작·클럭 목킹 없음) — 운영 코드가 쓰는
 * {@code now - staleTimeoutMinutes} 에 대응한다. 동시성은 고정 sleep 이 아니라 래치로 동기화한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class BatchRetryStaleReclaimIT {

    @Autowired
    private BatchRetryQueue retryQueue;

    @Autowired
    private LsBatRtyWtngRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 픽스처 준비용 트랜잭션 — {@code @Modifying} 쿼리(claimAtomically)는 활성 트랜잭션을 요구한다.
     * 운영에서는 {@code BatchRetryQueue} 의 REQUIRES_NEW 가 그 역할을 하고, 여기서는 테스트가 직접 연다.
     */
    private TransactionTemplate tx;

    @Autowired
    void setTransactionManager(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    /** tick 당 회수 상한 — 운영 기본값과 동일. */
    private static final int BATCH_SIZE = 50;

    private final List<Long> createdSns = new ArrayList<>();
    /** 시드한 부모 영상 — V146 FK(LS_BAT_RTY_WTNG → LS_DATA_RAW) 충족용. */
    private final List<Long> seededRawSns = new ArrayList<>();

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(status ->
                createdSns.forEach(sn -> repository.findById(sn).ifPresent(repository::delete)));
        createdSns.clear();
        // 부모 삭제 = 남은 자식 CASCADE 삭제.
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    /**
     * 다른 테스트가 남긴 RETRYING 잔재를 미리 회수해 후보 집합을 이 테스트 행만으로 좁힌다
     * (회수 건수 단언의 결정성 확보). 테스트는 순차 실행이므로 잔재는 이미 종료된 테스트의 것이다.
     */
    private void drainPreexistingStale() {
        retryQueue.sweepStaleRetrying(LocalDateTime.now().plusMinutes(1), BATCH_SIZE);
    }

    /** RETRYING 상태의 재시도 항목 1건을 만든다(= 폴러가 클레임한 직후 상태). */
    private LsBatRtyWtng persistRetrying(int attempts, int maxAttempts) {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSns.add(rawSn);
        LsBatRtyWtng entry = LsBatRtyWtng.create(rawSn, maxAttempts);
        for (int i = 0; i < attempts; i++) {
            entry.incrementAttempt();
        }
        LsBatRtyWtng saved = tx.execute(status -> repository.saveAndFlush(entry));
        createdSns.add(saved.getBatRtySn());
        // 폴링 클레임과 동일 경로로 RETRYING 전이(테스트 전용 setter 를 두지 않는다).
        Integer claimed = tx.execute(status ->
                repository.claimAtomically(saved.getBatRtySn(), LocalDateTime.now()));
        assertThat(claimed).isEqualTo(1);
        return saved;
    }

    /** 저장 직후 행을 stale 로 보이게 하는 cutoff(= now + 여유). 운영 코드의 {@code now - 임계} 대응값. */
    private static LocalDateTime staleCutoff() {
        return LocalDateTime.now().plusMinutes(1);
    }

    private LsBatRtyWtng reload(Long batRtySn) {
        return tx.execute(status -> repository.findById(batRtySn).orElseThrow());
    }

    @Test
    @DisplayName("RETRYING_클레임_후_임계가_지나면_다른_노드가_회수한다")
    void staleRetryingIsReclaimedByAnotherNode() {
        // given — 노드 A 가 클레임한 뒤 죽어 RETRYING 으로 굳은 항목(재시도 1회 소비, 상한 3)
        drainPreexistingStale();
        LsBatRtyWtng stuck = persistRetrying(1, 3);

        // when — 임계가 지난 뒤 다른 노드가 스윕한다
        BatchRetryQueue.StaleReclaimResult result = retryQueue.sweepStaleRetrying(staleCutoff(), BATCH_SIZE);

        // then — PENDING 으로 복귀해 재시도가 재개된다. 죽은 시도는 1회로 계상된다(무한 부활 방지).
        assertThat(result.reclaimed()).isEqualTo(1);
        assertThat(result.exhausted()).isZero();
        LsBatRtyWtng reclaimed = reload(stuck.getBatRtySn());
        assertThat(reclaimed.getSttsCd()).isEqualTo(LsBatRtyWtng.STATUS_PENDING);
        assertThat(reclaimed.getRtyNmtm()).isEqualTo(2);
        assertThat(reclaimed.getRtyPrnmntDt()).as("다음 재시도 시각이 재무장돼야 폴러가 집는다").isNotNull();
    }

    @Test
    @DisplayName("정상_처리중인_RETRYING_은_회수되지_않는다")
    void freshRetryingIsNotReclaimed() {
        // given — 방금 클레임해 정상 처리 중인 항목(오회수하면 같은 영상이 두 노드에서 동시 처리된다)
        drainPreexistingStale();
        LsBatRtyWtng inFlight = persistRetrying(1, 3);

        // when — 임계를 넘지 않은 cutoff(= 클레임 시각 이전)로 스윕한다
        BatchRetryQueue.StaleReclaimResult result =
                retryQueue.sweepStaleRetrying(inFlight.getMdfcnDt().minusMinutes(1), BATCH_SIZE);

        // then — 손대지 않는다. RETRYING 유지 + 시도 횟수 불변.
        assertThat(result.total()).isZero();
        LsBatRtyWtng untouched = reload(inFlight.getBatRtySn());
        assertThat(untouched.getSttsCd()).isEqualTo(LsBatRtyWtng.STATUS_RETRYING);
        assertThat(untouched.getRtyNmtm()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도_상한을_넘긴_항목은_무한_부활하지_않는다")
    void exhaustedItemIsNotResurrected() {
        // given — 이미 상한(2회)을 모두 쓴 뒤 클레임 상태로 죽은 항목
        drainPreexistingStale();
        LsBatRtyWtng maxedOut = persistRetrying(2, 2);

        // when — 임계가 지나 스윕한다
        BatchRetryQueue.StaleReclaimResult first = retryQueue.sweepStaleRetrying(staleCutoff(), BATCH_SIZE);

        // then — PENDING 으로 되살리지 않고 EXHAUSTED 로 종결한다
        assertThat(first.exhausted()).isEqualTo(1);
        assertThat(first.reclaimed()).isZero();
        LsBatRtyWtng exhausted = reload(maxedOut.getBatRtySn());
        assertThat(exhausted.getSttsCd()).isEqualTo(LsBatRtyWtng.STATUS_EXHAUSTED);
        assertThat(exhausted.getRtyPrnmntDt()).as("재시도 예정 시각이 남으면 폴러가 다시 집는다").isNull();

        // and — 반복 스윕해도 다시 살아나지 않는다(멱등)
        assertThat(retryQueue.sweepStaleRetrying(staleCutoff(), BATCH_SIZE).total()).isZero();
        assertThat(reload(maxedOut.getBatRtySn()).getSttsCd()).isEqualTo(LsBatRtyWtng.STATUS_EXHAUSTED);
    }

    @Test
    @DisplayName("2노드_동시_회수에서_같은_행이_한_번만_PENDING_으로_복귀한다")
    void concurrentReclaimHappensExactlyOnce() throws Exception {
        // given — stale RETRYING 1건 (다른 테스트 잔여분은 사전 회수해 후보를 이 행으로 한정)
        drainPreexistingStale();
        LsBatRtyWtng stuck = persistRetrying(1, 3);
        LocalDateTime cutoff = staleCutoff();

        // when — 두 노드(스레드)가 거의 동시에 스윕한다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        int total;
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return retryQueue.sweepStaleRetrying(cutoff, BATCH_SIZE).total();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return retryQueue.sweepStaleRetrying(cutoff, BATCH_SIZE).total();
            });
            start.countDown();
            total = a.get(60, TimeUnit.SECONDS) + b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // then — 회수 성공 합이 정확히 1(= 한 노드만 클레임). 비원자면 2가 되고 시도 횟수가 이중 증가한다.
        assertThat(total).as("2노드가 같은 행을 각각 회수하면 이중 쓰기다").isEqualTo(1);
        LsBatRtyWtng reclaimed = reload(stuck.getBatRtySn());
        assertThat(reclaimed.getSttsCd()).isEqualTo(LsBatRtyWtng.STATUS_PENDING);
        assertThat(reclaimed.getRtyNmtm()).as("죽은 시도는 1회만 계상돼야 한다").isEqualTo(2);
    }
}
