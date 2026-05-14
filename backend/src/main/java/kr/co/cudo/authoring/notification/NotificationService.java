package kr.co.cudo.authoring.notification;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

/**
 * Phase 3 — REVIEWER 알림 인터페이스 (현재는 로그 출력 수준).
 *
 * <p>외부 메일/슬랙/WS 채널은 향후 확장. 인터페이스만 도입하여 호출처가 결합되지 않도록 한다.
 */
public interface NotificationService {

    /**
     * 비식별 누락 신고 발생 시 REVIEWER 에게 알림.
     *
     * @param raw          신고된 영상
     * @param reporterNo   신고자 userNo
     * @param reason       신고 사유 (사용자 입력 — 로그 출력 시 sanitize 필수)
     */
    void notifyReviewersOnDeidentReport(LsDataRaw raw, Long reporterNo, String reason);

    /**
     * 재비식별 성공으로 영상 잠금이 해제되었을 때 REVIEWER 에게 알림.
     */
    void notifyReviewersOnLockRelease(LsDataRaw raw);
}
