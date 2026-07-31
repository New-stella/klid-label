package kr.co.cudo.authoring.augment.dto;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 증강 결과 요약 응답.
 * - rejectReason 은 REJECTED 일 때만 채워진다.
 * - decisionUserNo / decisionAt 은 ACCEPTED 또는 REJECTED 일 때만 채워진다.
 *
 * <p>표준화 방침: 엔티티/DB 컬럼은 표준 약어(RJCT_RSN/DCSN_USER_NO/DCSN_DT/REG_DT)로 정합하되,
 * 본 응답 record 필드명(=API JSON 키)은 FE 계약 보존을 위해 기존 명칭을 유지한다.
 * from() 매핑에서 새 표준 게터(getRejectRsn/getRegDt 등)를 호출한다.
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
        LocalDateTime registeredAt,
        String prompt
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
                review == null ? null : review.getRejectRsn(),
                review == null ? null : review.getRvwId(),
                review == null ? null : review.getRvwDt(),
                e.getRegDt(),
                // 이 증강을 만들 때 외부로 보낸 생성 조건(prompt) 원문 — 같은 (영상 × 종류) 반복 요청이
                // 허용되므로(2026-07-31) 결과물끼리 구분하려면 조건이 결과 조회 경로에 도달해야 한다.
                // V147 이전 요청·해상도 파생(RESL_*)은 null.
                e.getPromptCn()
        );
    }
}
