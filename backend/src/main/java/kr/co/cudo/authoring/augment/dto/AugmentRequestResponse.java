package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;

/**
 * 외부 SFR-07 증강 요청 응답.
 *
 * <h3>왜 {@code createdCount} 가 필요한가 (E-ISSUE-09)</h3>
 * <p>구 응답은 <b>요청한 개수를 그대로 되돌려주는 echo</b> 였다. 그래서 프레임 부재 등으로 PENDING
 * 행이 하나도 생기지 않아도 호출자(관리 화면 REVIEWER)는 200 + videoCount=1 을 보고 접수된 줄
 * 알았다(silent no-op). {@code createdCount} 는 <b>실제로 적재된 증강 행 수</b>이며, 0 건이면 서비스가
 * 4xx 로 종결하므로 이 값이 0 인 성공 응답은 존재하지 않는다.
 *
 * @param jobId        외부 시스템 jobId (placeholder — 외부 미연동 단계에서 임시 발급)
 * @param requestedAt  요청 시각
 * @param videoCount   요청한 영상 개수 (단건 계약이므로 항상 1)
 * @param typeCount    요청한 증강 유형 개수 (단건 계약이므로 항상 1)
 * @param createdCount 실제 적재된 PENDING 증강 행 수 (요청 echo 가 아님)
 */
public record AugmentRequestResponse(
        Long jobId,
        LocalDateTime requestedAt,
        int videoCount,
        int typeCount,
        int createdCount
) {
}
