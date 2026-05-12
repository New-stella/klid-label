package kr.co.cudo.authoring.assignment.dto;

import java.time.LocalDateTime;

/**
 * 배정 이력 응답 — `LS_PJT_USER_AUTHRT_HSTRY` 한 row 매핑.
 *
 * <p>FE 타임라인 표시용: prev/new 작업자 이름과 변경 시각, 변경 사유(추후 확장 — 현재는 null).
 * 사용자 입력으로 동적 SQL을 구성하지 않으며, prev/new 이름은 {@code MNG_ACCT_USER}에서 조회된 정보만 노출.
 */
public record AssignmentHistoryResponse(
        Long hstrySn,
        String chgTypeCd,
        Long prevUserNo,
        String prevUserName,
        Long newUserNo,
        String newUserName,
        String reason,
        LocalDateTime chgDt
) {
}
