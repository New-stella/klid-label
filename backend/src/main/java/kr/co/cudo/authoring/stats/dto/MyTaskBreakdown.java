package kr.co.cudo.authoring.stats.dto;

/**
 * WORKER 본인 작업 현황 분해. 4개 상태 카운트.
 *
 * <p>인증되지 않거나 WORKER 가 아닌 호출자(REVIEWER 등)인 경우에도 동일 구조로 0 만 반환한다 (null 금지).
 */
public record MyTaskBreakdown(long pendingCount,
                              long inProgressCount,
                              long reviewPendingCount,
                              long rejectedCount) {

    public static MyTaskBreakdown empty() {
        return new MyTaskBreakdown(0, 0, 0, 0);
    }
}
