package kr.co.cudo.authoring.webhook.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 보강 (DEV_FIX C-1/H-1) — PersistentWebhookIdempotencyLedger 영속 동작 검증.
 *
 * <p>운영 ledger 가 LS_WEBHOOK_IDEMPOTENCY 테이블에 ISSUED/PROCESSED 상태를 영속화하는지,
 * 그리고 동시 markProcessed 호출 시 UNIQUE 제약 위반을 멱등으로 흡수하는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PersistentWebhookIdempotencyLedgerTest {

    @Autowired PersistentWebhookIdempotencyLedger ledger;
    @Autowired LsWebhookIdempotencyRepository repository;

    @BeforeEach
    void cleanup() {
        repository.deleteAllInBatch();
    }

    @Test
    @DisplayName("PersistentWebhookIdempotencyLedger_재시작_시뮬레이션_시_상태_복원")
    void persistentStateRecoverable() {
        String key = "K-PERSIST-" + UUID.randomUUID();
        ledger.recordIssued(key, "EXT-PERSIST-1");
        ledger.markProcessed(key, "EXT-PERSIST-1");

        // "재시작" 시뮬레이션 — 새로운 ledger 인스턴스에서 동일 repository 로 조회
        PersistentWebhookIdempotencyLedger reloaded = new PersistentWebhookIdempotencyLedger(repository);

        assertThat(reloaded.isIssued(key)).isTrue();
        assertThat(reloaded.isProcessed(key)).isTrue();
    }

    @Test
    @DisplayName("PersistentWebhookIdempotencyLedger_동시_markProcessed_시_UNIQUE_제약_멱등_반환")
    void concurrentMarkProcessedIsIdempotent() throws InterruptedException {
        String key = "K-RACE-" + UUID.randomUUID();
        ledger.recordIssued(key, "EXT-RACE");

        int threadCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        ledger.markProcessed(key, "EXT-RACE");
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get())
                .as("동시 markProcessed 호출에서 예외가 발생하면 안 됨 (UNIQUE 제약 멱등 흡수)")
                .isZero();
        assertThat(ledger.isProcessed(key)).isTrue();
        assertThat(repository.findAll())
                .filteredOn(e -> e.getIdempotencyKey().equals(key))
                .hasSize(1);
    }

    @Test
    @DisplayName("PersistentWebhookIdempotencyLedger_clear_시_UnsupportedOperationException")
    void clearOnPersistentLedger_throwsUnsupported() {
        // DEV_FIX 2차 N-1 (CWE-732): 운영 ledger 는 다른 서비스가 실수로 clear() 를 호출해도
        // 데이터를 보호하기 위해 fail-secure 로 거부한다. 테스트는 InMemory 구현체 사용.
        String key = "K-PROTECT-" + UUID.randomUUID();
        ledger.recordIssued(key, "EXT-PROTECT");

        assertThatThrownBy(() -> ledger.clear())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("운영 영속 ledger");

        // 데이터는 보존되어야 함
        assertThat(ledger.isIssued(key)).isTrue();
    }
}
