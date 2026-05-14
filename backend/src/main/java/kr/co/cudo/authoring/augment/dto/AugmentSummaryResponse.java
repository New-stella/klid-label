package kr.co.cudo.authoring.augment.dto;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 증강 결과 요약 응답.
 * - rejectReason 은 REJECTED 일 때만 채워진다.
 * - decisionUserNo / decisionAt 은 ACCEPTED 또는 REJECTED 일 때만 채워진다.
 */
public record AugmentSummaryResponse(
        Long dataAugSn,
        Long srcSn,
        String augTypeCd,
        String augProcSttsCd,
        BigDecimal lblIntgrtPct,
        String rejectReason,
        String decisionUserNo,
        LocalDateTime decisionAt,
        LocalDateTime registeredAt
) {

    public static AugmentSummaryResponse from(LsDataAug e) {
        return from(e, null);
    }

    public static AugmentSummaryResponse from(LsDataAug e, LsDataAugRvw review) {
        return new AugmentSummaryResponse(
                e.getDataAugSn(),
                e.getSrcSn(),
                e.getAugTypeCd(),
                review == null ? e.getAugProcSttsCd() : review.getRvwSttsCd(),
                review == null ? null : review.getLblIntgrtPct(),
                review == null ? null : review.getRejectReason(),
                review == null ? null : review.getRvwId(),
                review == null ? null : review.getRvwDt(),
                e.getRegisteredAt()
        );
    }
}
