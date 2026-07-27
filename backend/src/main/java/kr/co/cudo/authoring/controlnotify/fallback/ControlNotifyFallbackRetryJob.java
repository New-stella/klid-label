package kr.co.cudo.authoring.controlnotify.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
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
 * {@code NEXT_RETRY_AT <= now} 인 항목을 polling 하여 {@link ControlNotifyService} 로 재전송.
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
    private final ControlNotifyService notifyService;

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

    /**
     * 큐 항목의 eventType 에 따라 재전송.
     *
     * <p>즉시 전송 경로와 <b>동일한 자기치유 규칙</b>({@code ControlNotifyService.dispatch*})을 태운다 —
     * Client 를 직접 호출하면 재시도 때 만난 409/404 가 영원히 실패로 남아 dead-letter 로 쌓인다.
     *
     * <p><b>페이로드 미보유 항목(A-1)</b>: 페이로드 조립 실패로 큐에 들어온 항목은 본문이 비어 있다
     * ({@link LsControlNotifyFallback#PAYLOAD_REBUILD_REQUIRED}). 이때는 {@code null} 을 넘겨
     * dispatch 가 {@code ControlNotifyPayloadFactory} 로 <b>재조립</b>하게 한다 — 조립 실패의 원인
     * (커넥션 고갈·락 타임아웃)은 대개 일시적이라 재시도가 유효하다. 재조립도 실패하면 예외가 나
     * 상위 루프가 백오프 재시도/dead-letter 로 격리한다.
     */
    private void processOne(LsControlNotifyFallback item) {
        String eventType = item.getEventTypeCd();
        boolean rebuild = LsControlNotifyFallback.isPayloadRebuildRequired(item.getPayloadCn());
        if ("TASK_COMPLETED".equals(eventType)) {
            TaskCompletedPayload payload = rebuild
                    ? null
                    : deserialize(item.getPayloadCn(), TaskCompletedPayload.class);
            notifyService.dispatchCompleted(payload, item.getRawSn());
        } else if ("TASK_MODIFIED".equals(eventType)) {
            TaskModifiedPayload payload = rebuild
                    ? null
                    : deserialize(item.getPayloadCn(), TaskModifiedPayload.class);
            notifyService.dispatchModified(payload, item.getRawSn());
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
