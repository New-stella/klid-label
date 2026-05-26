package kr.co.cudo.authoring.controlnotify.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.controlnotify.fallback.ControlNotifyFallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3 -- 관제서버 outbound 통지 오케스트레이션.
 *
 * <p>이벤트 수신 -> Client 전송 -> 실패 시 폴백 큐 적재.
 */
@Service
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ControlNotifyService {

    private final ControlNotifyClient client;
    private final ControlNotifyFallbackService fallbackService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        MAPPER.findAndRegisterModules();
    }

    /**
     * 검수 완료 시 즉시 전송.
     */
    public void sendCompleted(ReviewApprovedEvent event) {
        TaskCompletedPayload payload = buildCompletedPayload(event);
        try {
            client.sendTaskCompleted(payload).block(ControlNotifyClient.BLOCK_TIMEOUT);
            log.info("[ControlNotify] TASK_COMPLETED sent rawSn={}", event.rawSn());
        } catch (Exception e) {
            log.warn("[ControlNotify] TASK_COMPLETED failed rawSn={} reason={}",
                    event.rawSn(), e.getClass().getSimpleName());
            fallbackService.enqueuePending(payload.requestId(), "TASK_COMPLETED",
                    event.rawSn(), serializePayload(payload));
        }
    }

    /**
     * 디바운스 윈도우 flush 후 전송.
     */
    public void sendModified(Long rawSn, List<Long> frameIds, List<String> changeTypes) {
        TaskModifiedPayload payload = buildModifiedPayload(rawSn, frameIds, changeTypes);
        try {
            client.sendTaskModified(payload).block(ControlNotifyClient.BLOCK_TIMEOUT);
            log.info("[ControlNotify] TASK_MODIFIED sent rawSn={} frames={}", rawSn, frameIds.size());
        } catch (Exception e) {
            log.warn("[ControlNotify] TASK_MODIFIED failed rawSn={}", rawSn);
            fallbackService.enqueuePending(payload.requestId(), "TASK_MODIFIED",
                    rawSn, serializePayload(payload));
        }
    }

    private TaskCompletedPayload buildCompletedPayload(ReviewApprovedEvent event) {
        return new TaskCompletedPayload(
                "TASK_COMPLETED",
                event.rawSn(),
                null,  // reviewerName 은 추후 lookup 가능하나 현 단계에서는 미포함
                event.approvedAt(),
                0,     // totalFrames 는 추후 lookup
                0,     // labeledFrames 는 추후 lookup
                UUID.randomUUID().toString()
        );
    }

    private TaskModifiedPayload buildModifiedPayload(Long rawSn, List<Long> frameIds,
                                                      List<String> changeTypes) {
        return new TaskModifiedPayload(
                "TASK_MODIFIED",
                rawSn,
                Instant.now(),
                frameIds,
                changeTypes,
                UUID.randomUUID().toString()
        );
    }

    private String serializePayload(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("[ControlNotify] payload serialize failed", e);
            return "{}";
        }
    }
}
