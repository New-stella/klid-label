package kr.co.cudo.authoring.batch.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quartz 스케줄러 헬스 정보 조회.
 * - REVIEWER 전용 엔드포인트(SchedulerHealthController) 또는 actuator 통합 시 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerHealthService {

    private final Scheduler scheduler;

    public Map<String, Object> getHealth() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            info.put("schedulerName", scheduler.getSchedulerName());
            info.put("started", scheduler.isStarted());
            info.put("inStandbyMode", scheduler.isInStandbyMode());
            info.put("shutdown", scheduler.isShutdown());
            info.put("jobCount", scheduler.getJobKeys(org.quartz.impl.matchers.GroupMatcher.anyJobGroup()).size());
            info.put("status", scheduler.isStarted() && !scheduler.isShutdown() ? "UP" : "DOWN");
        } catch (SchedulerException e) {
            log.warn("[Scheduler] failed to read scheduler metadata message={}", e.getMessage());
            info.put("status", "DOWN");
            info.put("error", "scheduler-metadata-unavailable");
        }
        return info;
    }
}
