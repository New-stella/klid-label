package kr.co.cudo.authoring.user.dto;

import kr.co.cudo.authoring.user.entity.MngAcctUser;

import java.time.LocalDateTime;

/**
 * 사용자 마스터 목록(/v1/users) 응답 — REVIEWER 의 사용자 관리 화면 전용.
 * 비밀번호 해시 등 민감 필드는 절대 포함하지 않는다 (Mass Assignment 방어).
 */
public record UserSummaryResponse(
        Long userNo,
        String userId,
        String userNm,
        String userEmail,
        String useYn,
        LocalDateTime regDt
) {
    public static UserSummaryResponse from(MngAcctUser user) {
        return new UserSummaryResponse(
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmail(),
                user.getUseYn(),
                user.getRegDt()
        );
    }
}
