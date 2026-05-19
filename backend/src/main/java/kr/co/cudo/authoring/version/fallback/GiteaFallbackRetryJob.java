package kr.co.cudo.authoring.version.fallback;

import io.github.resilience4j.reactor.retry.RetryOperator;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 3 — Gitea fallback 큐 재시도 Job.
 *
 * <p>{@link GiteaFallbackQueueService} 적재 항목 중 {@code STATUS=PENDING} +
 * {@code NEXT_RETRY_AT <= now} 인 항목을 polling 하여 {@link GiteaClient} 로 직접 재호출.
 *
 * <p>활성 조건: {@code gitea.fallback.enabled=true} + Spring Scheduling 활성.
 * dev 환경(enabled=false) 에서는 빈 자체가 등록되지 않으므로 동작 영향 없음.
 *
 * <p>{@link RetryOperator} 미적용 — Quartz/Scheduler 가 외부 재시도 컨트롤러 역할.
 *
 * <p>S-5 방어: claimForRetry 가 RETRYING 으로 마킹 후 처리. 다중 인스턴스에서도 UNIQUE
 * idempotencyKey + STATUS 전이로 동일 항목 중복 처리 차단.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gitea.fallback.enabled", havingValue = "true")
@RequiredArgsConstructor
public class GiteaFallbackRetryJob {

    /** 한 번의 polling 에서 처리할 최대 개수 — 운영 부하 컨트롤. */
    private static final int BATCH_SIZE = 20;

    private final LsGiteaFallbackQueueRepository repository;
    private final GiteaFallbackQueueService queueService;
    private final GiteaClient giteaClient;

    @Value("${authoring.integration.gitea.repo}")
    private String repo;

    /** 1분 간격 polling. */
    @Scheduled(fixedDelayString = "${gitea.fallback.retry.interval-ms:60000}",
               initialDelayString = "${gitea.fallback.retry.initial-delay-ms:30000}")
    public void run() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("[GiteaFallback] retry job failed reason={}", e.getClass().getSimpleName());
        }
    }

    public int runOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<LsGiteaFallbackQueue> due = repository
                .findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        LsGiteaFallbackQueue.STATUS_PENDING, now, PageRequest.of(0, BATCH_SIZE));
        int processed = 0;
        for (LsGiteaFallbackQueue snapshot : due) {
            // S-5: RETRYING 으로 마킹 (다른 인스턴스가 중복 처리하지 못하도록)
            var claimed = queueService.claimForRetry(snapshot.getQueueSn());
            if (claimed.isEmpty()) continue;
            LsGiteaFallbackQueue item = claimed.get();
            try {
                processOne(item);
                queueService.markSucceeded(item.getQueueSn());
                processed++;
            } catch (RuntimeException e) {
                queueService.markFailedAndSchedule(item.getQueueSn(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (processed > 0) {
            log.info("[GiteaFallback] retry processed count={}", processed);
        }
        return processed;
    }

    /** 큐 항목을 실제 Gitea 로 재호출. */
    private void processOne(LsGiteaFallbackQueue item) {
        if (!LsGiteaFallbackQueue.OP_PUT.equals(item.getOperation())) {
            throw new IllegalStateException("지원하지 않는 OPERATION: " + item.getOperation());
        }
        String branch = item.getBranch() == null ? "main" : item.getBranch();
        CommitResponse resp = giteaClient.createOrUpdateFile(
                        repo, item.getPath(), item.getContentBase64() == null ? "" : item.getContentBase64(),
                        item.getCommitMessage() == null ? "fallback retry" : item.getCommitMessage(),
                        item.getAuthor() == null ? "system" : item.getAuthor(),
                        branch)
                .block(GiteaClient.BLOCK_TIMEOUT);
        if (resp == null) {
            throw new IllegalStateException("Gitea response was null");
        }
    }
}
