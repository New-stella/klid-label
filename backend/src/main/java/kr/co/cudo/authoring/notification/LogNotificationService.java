package kr.co.cudo.authoring.notification;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Phase 3 — 로그 기반 NotificationService 구현체 (placeholder).
 *
 * <p>보안:
 * <ul>
 *   <li><b>Log Injection (CWE-117)</b>: reason 은 사용자 입력 — 개행 문자(\r,\n) 제거 후 로깅.</li>
 *   <li><b>Privacy Violation (CWE-359)</b>: reason 평문에 개인정보가 섞일 수 있음 — 길이 상한 200자 절단.</li>
 * </ul>
 */
@Slf4j
@Service
public class LogNotificationService implements NotificationService {

    /** Privacy — 로그에 노출하는 reason 최대 길이. */
    static final int LOG_REASON_MAX = 200;

    @Override
    public void notifyReviewersOnDeidentReport(LsDataRaw raw, Long reporterNo, String reason) {
        if (raw == null) {
            return;
        }
        log.info("[Notification] deident-report rawSn={} reporterNo={} reason={}",
                raw.getRawSn(), reporterNo, sanitize(reason));
    }

    @Override
    public void notifyReviewersOnLockRelease(LsDataRaw raw) {
        if (raw == null) {
            return;
        }
        log.info("[Notification] lock-released rawSn={}", raw.getRawSn());
    }

    /** CWE-117 / CWE-359 방어 — 개행 제거 + 길이 절단. */
    static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        String cleaned = input.replaceAll("[\\r\\n\\t]", " ");
        if (cleaned.length() > LOG_REASON_MAX) {
            cleaned = cleaned.substring(0, LOG_REASON_MAX) + "...(truncated)";
        }
        return cleaned;
    }
}
