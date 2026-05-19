package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;

/**
 * 외부 SFR-07 증강 요청 응답.
 *
 * @param jobId        외부 시스템 jobId (placeholder — 외부 미연동 단계에서 임시 발급)
 * @param requestedAt  요청 시각
 * @param videoCount   요청한 영상 개수 (distinct 후 카운트)
 * @param typeCount    요청한 증강 유형 개수 (distinct 후 카운트)
 */
public record AugmentRequestResponse(
        Long jobId,
        LocalDateTime requestedAt,
        int videoCount,
        int typeCount
) {
}
