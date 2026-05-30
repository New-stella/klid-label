package kr.co.cudo.authoring.controlnotify.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 3 -- 관제서버 통지 fallback 큐 재시도 Job.
 *
 * <p>{@link ControlNotifyFallbackService} 적재 항목 중 {@code STATUS=PENDING} +
 * {@code NEXT_RETRY_AT <= now} 인 항목을 polling 하여 {@link ControlNotifyClient} 로 재호출.
 *
 * <p>활성 조건: {@code authoring.control-notify.enabled=true}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ControlNotifyFallbackRetryJob {

    private static final int BATCH_SIZE = 20;

    private final LsControlNotifyFallbackRepository repository;
    private final ControlNotifyFallbackService fallbackService;
    private final ControlNotifyClient client;

    /** 5분 간격 polling. */
    @Scheduled(fixedDelayString = "${authoring.control-notify.retry.interval-ms:300000}",
               initialDelayString = "${authoring.control-notify.retry.initial-delay-ms:60000}")
    public void run() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("[ControlNotifyFallback] retry job failed reason={}", e.getClass().getSimpleName());
        }
    }

    public int runOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<LsControlNotifyFallback> due = repository
                .findBySttsCdAndNextRtryDtLessThanEqualOrderByNextRtryDtAsc(
                        LsControlNotifyFallback.STATUS_PENDING, now, PageRequest.of(0, BATCH_SIZE));
        int processed = 0;
        for (LsControlNotifyFallback snapshot : due) {
            var claimed = fallbackService.claimForRetry(snapshot.getQueueSn());
            if (claimed.isEmpty()) continue;
            LsControlNotifyFallback item = claimed.get();
            try {
                processOne(item);
                fallbackService.markSucceeded(item.getQueueSn());
                processed++;
            } catch (RuntimeException e) {
                fallbackService.markFailedAndSchedule(item.getQueueSn(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (processed > 0) {
            log.info("[ControlNotifyFallback] retry processed count={}", processed);
        }
        return processed;
    }

    /** 큐 항목의 eventType 에 따라 적절한 Client 메서드로 재호출. */
    private void processOne(LsControlNotifyFallback item) {
        String eventType = item.getEventTypeCd();
        if ("TASK_COMPLETED".equals(eventType)) {
            TaskCompletedPayload payload = deserialize(item.getPayloadCn(), TaskCompletedPayload.class);
            client.sendTaskCompleted(payload).block(ControlNotifyClient.BLOCK_TIMEOUT);
        } else if ("TASK_MODIFIED".equals(eventType)) {
            TaskModifiedPayload payload = deserialize(item.getPayloadCn(), TaskModifiedPayload.class);
            client.sendTaskModified(payload).block(ControlNotifyClient.BLOCK_TIMEOUT);
        } else {
            throw new IllegalStateException("지원하지 않는 eventType: " + eventType);
        }
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("fallback payload 역직렬화 실패: " + e.getMessage(), e);
        }
    }
}
