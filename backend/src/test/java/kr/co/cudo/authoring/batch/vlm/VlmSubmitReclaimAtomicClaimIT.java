package kr.co.cudo.authoring.batch.vlm;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C-1 — 미결 위탁 회수의 <b>원자 클레임</b> 통합 검증 (CWE-362, 2노드 Active-Active).
 *
 * <p>배포 토폴로지가 2노드 Active-Active 이므로 두 노드의 스윕이 같은 미결 행을 동시에 집을 수 있다.
 * Quartz 클러스터링은 <b>트리거 중복만</b> 막고 잡 내부 레이스는 막지 못하므로, 조건부 UPDATE 가
 * 유일한 방어다. 클레임이 원자적이지 않으면 같은 영상이 두 번 재위탁되어 시계열 메타·검수큐가
 * 중복 적재된다.
 *
 * <p>ambient tx 없이(클래스에 {@code @Transactional} 미부착) 실제 커밋 경합을 재현한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class VlmSubmitReclaimAtomicClaimIT {

    @Autowired private VlmSubmitReclaimTxService reclaimTxService;
    @Autowired private LsWebhookIdempotencyRepository ledgerRepository;
    @Autowired private VideoRepository videoRepository;

    /**
     * 대상 영상 시드 — {@code LS_WEBHOOK_IDEMPOTENCY.RAW_SN} 은 {@code LS_DATA_RAW} 를 참조하는
     * FK(V146)라 실재하는 영상이 있어야 원장 행을 넣을 수 있다.
     */
    private Long seedVideo() {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "VLMRCL-" + UUID.randomUUID(), "CCTV-VLMRCL", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/vlmrcl.mp4", LocalDateTime.now(), 30)).getRawSn();
    }

    /** 회수 후보(ISSUED + rawSn 매핑) 1건 시드. cutoff 는 미래로 잡아 임계 조건을 항상 만족시킨다. */
    private String seedIssued(Long rawSn) {
        String key = "VLMRCL-" + UUID.randomUUID();
        ledgerRepository.saveAndFlush(
                LsWebhookIdempotency.issue(key, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn));
        return key;
    }

    @Test
    @DisplayName("두_노드가_동시에_회수해도_클레임은_정확히_한_번만_성공한다")
    void concurrentClaimSucceedsExactlyOnce() throws Exception {
        // given — 미결 행 1건 + 임계를 항상 만족하는 cutoff
        String key = seedIssued(seedVideo());
        LocalDateTime cutoff = LocalDateTime.now().plusMinutes(1);

        // when — 두 스레드(=두 노드)가 같은 순간에 같은 행을 클레임 시도
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicInteger wins = new AtomicInteger();
        try {
            Callable<Void> task = () -> {
                gate.await(5, TimeUnit.SECONDS);
                if (reclaimTxService.claim(key, cutoff)) {
                    wins.incrementAndGet();
                }
                return null;
            };
            List<Future<Void>> futures = pool.invokeAll(List.of(task, task));
            for (Future<Void> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 1회만 소유권 획득. 2 면 이중 재위탁(중복 메타·검수행), 0 이면 회수 불능.
        assertThat(wins.get()).isEqualTo(1);
        assertThat(ledgerRepository.findById(key).orElseThrow().getSttsCd())
                .isEqualTo(LsWebhookIdempotency.STATE_FAILED);
    }

    @Test
    @DisplayName("이미_회수된_행은_다시_클레임되지_않는다_멱등")
    void alreadyClaimedRowIsNotReclaimed() {
        String key = seedIssued(seedVideo());
        LocalDateTime cutoff = LocalDateTime.now().plusMinutes(1);

        assertThat(reclaimTxService.claim(key, cutoff)).isTrue();
        assertThat(reclaimTxService.claim(key, cutoff)).isFalse();
    }

    @Test
    @DisplayName("임계_이전_미결행은_회수_후보가_아니다_정상_ACK대기_위탁_보호")
    void freshIssuedRowIsNotACandidate() {
        String key = seedIssued(seedVideo());

        // 방금 발급된 행은 cutoff(과거 시점) 이후라 후보에 들어오면 안 된다.
        List<VlmSubmitReclaimTxService.Candidate> candidates =
                reclaimTxService.findCandidates(LocalDateTime.now().minusMinutes(30), 50);

        assertThat(candidates).noneMatch(c -> key.equals(c.idmpKey()));
        // 클레임도 임계 조건을 UPDATE 에 그대로 실어 fail-safe 로 재판정한다.
        assertThat(reclaimTxService.claim(key, LocalDateTime.now().minusMinutes(30))).isFalse();
    }

    @Test
    @DisplayName("회수_예산_초과_판정은_그_영상의_회수_이력_건수로_이뤄진다")
    void reclaimBudgetCountsPastReclaims() {
        Long rawSn = seedVideo();
        LocalDateTime cutoff = LocalDateTime.now().plusMinutes(1);
        reclaimTxService.claim(seedIssued(rawSn), cutoff);
        reclaimTxService.claim(seedIssued(rawSn), cutoff);

        assertThat(reclaimTxService.reclaimBudgetExceeded(rawSn, 3)).isFalse();
        assertThat(reclaimTxService.reclaimBudgetExceeded(rawSn, 2)).isTrue();
    }
}
