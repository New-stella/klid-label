package kr.co.cudo.authoring.version.async;

import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.service.GiteaPathPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Phase 8 — Gitea 커밋 fallback 재시도 잡.
 *
 * <p>5분 간격으로 큐를 polling 하여, 한 번에 큐 크기만큼 처리.
 * 성공 시: 가장 최신 PENDING history 의 hash 를 채움.
 * 실패 시: requeueIfRetryable 로 큐 끝에 재배치 (최대 5회 후 폐기).
 *
 * <p>환경: Spring {@code @Scheduled} 사용. {@code @EnableScheduling} 활성화 + property 활성화 시 동작.
 * 비활성 환경(local/test)에서는 빈 자체가 등록되지 않으므로 영향 없음. 운영은 Quartz 잡으로 전환 가능.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "authoring.gitea.retry.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class GiteaCommitRetryJob {

    private final GiteaCommitFallbackQueue queue;
    private final GiteaClient giteaClient;
    private final GiteaPathPolicy pathPolicy;
    private final LsDataLblHstryRepository historyRepository;

    @Value("${authoring.integration.gitea.repo}")
    private String repo;

    /** 5분(300_000ms) 간격으로 큐 polling. local 프로파일 비활성화는 application-local.yml 에서 별도 처리 가능. */
    @Scheduled(fixedDelayString = "${authoring.gitea.retry.interval-ms:300000}",
               initialDelayString = "${authoring.gitea.retry.initial-delay-ms:60000}")
    public void run() {
        int budget = queue.size(); // poll 무한루프 방지 — 현재 스냅샷 만큼만
        int processed = 0;
        for (int i = 0; i < budget; i++) {
            Optional<GiteaCommitFallbackQueue.RetryItem> opt = queue.poll();
            if (opt.isEmpty()) break;
            GiteaCommitFallbackQueue.RetryItem item = opt.get();
            try {
                processOne(item);
                processed++;
            } catch (Exception e) {
                log.warn("[Version] gitea retry failed srcSn={} attempt={} reason={}",
                        item.getSrcSn(), item.retryCount() + 1, e.getClass().getSimpleName());
                queue.requeueIfRetryable(item);
            }
        }
        if (processed > 0) {
            log.info("[Version] gitea retry processed count={} remaining={}", processed, queue.size());
        }
    }

    /**
     * 큐 항목 1건 처리. 별도 트랜잭션 — history UPDATE 가 즉시 커밋되도록.
     */
    @Transactional("controlTransactionManager")
    public void processOne(GiteaCommitFallbackQueue.RetryItem item) {
        String labelsJson = item.getLabelsJson();
        String contentBase64 = Base64.getEncoder().encodeToString(
                (labelsJson == null ? "" : labelsJson).getBytes(StandardCharsets.UTF_8));
        String message = "label update by " + item.getUserNo() + " (retry)";
        CommitResponse resp = giteaClient
                .createOrUpdateFile(repo, pathPolicy.path(item.getSrcSn()), contentBase64,
                        message, item.getUserNo(), "main")
                .block(GiteaClient.BLOCK_TIMEOUT);
        String sha = resp == null ? "" : resp.sha();

        // 가장 최근 PENDING history 한 건의 hash 를 채움 (없으면 신규 record)
        var latest = historyRepository.findBySrcSnOrderByRegisteredAtDesc(item.getSrcSn());
        LsDataLblHstry pending = latest.stream()
                .filter(h -> h.getGiteaCmtHash() == null)
                .findFirst()
                .orElse(null);
        if (pending != null) {
            pending.fillGiteaHash(sha);
            historyRepository.save(pending);
        } else {
            historyRepository.save(LsDataLblHstry.create(item.getSrcSn(), sha, item.getUserNo(), labelsJson));
        }
        log.info("[Version] gitea retry success srcSn={} sha={}", item.getSrcSn(), sha);
    }
}
