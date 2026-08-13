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
    @Autowired kr.co.cudo.authoring.video.repository.VideoRepository videoRepository;
    /** 미결 회수의 프로덕션 경로 — 리포지토리 {@code @Modifying} 직접 호출 대신 이 빈의 트랜잭션을 쓴다. */
    @Autowired kr.co.cudo.authoring.batch.vlm.VlmSubmitReclaimTxService reclaimTxService;

    @BeforeEach
    void cleanup() {
        repository.deleteAllInBatch();
    }

    /**
     * 대상 영상 시드 — {@code LS_WEBHOOK_IDEMPOTENCY.RAW_SN} 은 {@code LS_DATA_RAW} 를 참조하는 FK(V146)라
     * 실재하는 영상이 있어야 원장 행을 넣을 수 있다({@code VlmSubmitReclaimAtomicClaimIT} 와 동일 패턴).
     */
    private Long seedVideo() {
        return videoRepository.save(kr.co.cudo.authoring.video.entity.LsDataRaw.createFromIngest(
                "LEDGERCH-" + UUID.randomUUID(), "CCTV-LEDGERCH", "EVT", "11680",
                kr.co.cudo.authoring.video.entity.LsDataRaw.PRVC_TYPE_ANONY,
                "/var/raw/ledgerch.mp4", java.time.LocalDateTime.now(), 30)).getRawSn();
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
                .filteredOn(e -> e.getIdmpKey().equals(key))
                .hasSize(1);
    }

    /**
     * ★ 미결 위탁 판정의 <b>채널 격리</b> (@req R1) — 배치 재실행의 중복 위탁 차단 게이트가
     * <b>남의 채널 미결에 걸려 정상 위탁을 막지 않는지</b> 고정한다.
     *
     * <p>같은 {@code rawSn} 에 비식별(DEIDENTIFY) 채널 미결이 있는 것은 흔한 정상 상태다. 판정이 채널을
     * 무시하면 그 영상의 VLM 위탁이 <b>영구히 막혀</b> 시계열 메타가 무증상 결손된다(게이트가 fail-closed
     * 방향으로 과하게 닫히는 사고). 상태 축도 함께 고정한다 — {@code PROCESSED}/{@code FAILED} 는 미결이
     * 아니어서 재위탁을 막지 않아야 하며, 특히 {@code FAILED} 를 미결로 보면 미결 스위퍼가 회수한 뒤의
     * 재개가 영구히 막힌다.
     *
     * <p>⚠ {@code InMemoryWebhookIdempotencyLedger} 는 상태 모델에 채널이 없어 이 격리를 하지 않는다.
     * 그 구현은 {@code @ConditionalOnProperty(authoring.webhook.idempotency.in-memory=true)} 라
     * <b>운영 도달 불가</b>(단위 테스트 전용)이므로 고치지 않았다 — 판정의 진실원은 이 영속 구현이다.
     */
    @Test
    @DisplayName("미결_위탁_판정은_채널을_격리한다_다른_채널_미결이_VLM_위탁을_막지_않는다")
    void hasOutstandingSubmitIsolatesChannel() {
        long rawSn = seedVideo();
        String deidKey = "K-DEID-" + UUID.randomUUID();
        String vlmKey = "K-VLM-" + UUID.randomUUID();

        // given — 같은 영상에 <b>비식별 채널</b> 미결(ISSUED)만 있다.
        ledger.recordIssued(deidKey, LsWebhookIdempotency.CHANNEL_DEIDENTIFY, null, rawSn);

        // then — VLM 게이트는 통과해야 한다(남의 채널 미결에 걸리지 않는다).
        assertThat(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, rawSn))
                .as("다른 채널 미결이 VLM 위탁을 막으면 시계열 메타가 영구 결손된다")
                .isFalse();
        // 대칭 — 비식별 채널로 물으면 미결이다.
        assertThat(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_DEIDENTIFY, rawSn)).isTrue();

        // when — 같은 영상에 VLM 미결이 생기면 그때는 막는다(중복 위탁 차단).
        ledger.recordIssued(vlmKey, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn);
        assertThat(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, rawSn)).isTrue();

        // when — ACK 수신(ACCEPTED)도 여전히 미결이다(결과 콜백 대기 중).
        ledger.recordAckReceived(vlmKey);
        assertThat(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, rawSn)).isTrue();

        // when — 콜백 처리 완료(PROCESSED)면 미결이 아니다(재위탁을 막지 않는다).
        ledger.markProcessed(vlmKey, null);
        assertThat(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, rawSn))
                .as("PROCESSED 를 미결로 보면 정상 완료된 영상의 재처리가 막힌다")
                .isFalse();
    }

    /**
     * 미결 스위퍼가 회수 표식으로 남기는 {@code FAILED} 는 <b>미결이 아니다</b> (@req R1) —
     * 회수 후 재개({@code VlmWithheldResumeRunner})가 이 판정에 막히면 시계열 메타가 영구 결손된다.
     */
    @Test
    @DisplayName("스위퍼가_회수한_FAILED_는_미결이_아니어서_재개를_막지_않는다")
    void reclaimedFailedIsNotOutstanding() {
        long rawSn = seedVideo();
        String key = "K-VLM-RECLAIM-" + UUID.randomUUID();
        ledger.recordIssued(key, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn);
        assertThat(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, rawSn)).isTrue();

        // 미결 스위퍼의 <b>실제 회수 경로</b>로 클레임한다(ISSUED → FAILED). 리포지토리 @Modifying 을 직접
        // 부르지 않는 이유: 이 테스트는 실제 커밋 경합을 재현하려 ambient tx 를 두지 않으므로
        // (클래스에 @Transactional 미부착) 트랜잭션 경계를 가진 프로덕션 빈을 거쳐야 한다.
        boolean claimed = reclaimTxService.claim(key, java.time.LocalDateTime.now().plusMinutes(1));
        assertThat(claimed).isTrue();

        assertThat(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, rawSn))
                .as("회수 표식(FAILED)을 미결로 보면 재개가 영구히 막힌다")
                .isFalse();
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
