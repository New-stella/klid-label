package kr.co.cudo.authoring.user.dto;

import kr.co.cudo.authoring.user.entity.MngAcctUser;

import java.time.LocalDateTime;

/**
 * 사용자 마스터 목록(/v1/users) 응답 — REVIEWER 의 사용자 관리 화면 전용.
 * 비밀번호 해시 등 민감 필드는 절대 포함하지 않는다 (Mass Assignment 방어).
 *
 * <p>BE 원본 컬럼(userNo/userId/userNm/userEmail/useYn/regDt) 외에 FE 호환 alias 필드를 함께 노출해
 * 화면 측 필드명 매핑을 단순화한다 (Video/Review/Assignment alias 패턴 동일).
 * <ul>
 *   <li>{@code id}        = {@code userNo}</li>
 *   <li>{@code loginId}   = {@code userId}</li>
 *   <li>{@code name}      = {@code userNm}</li>
 *   <li>{@code email}     = {@code userEmail}</li>
 *   <li>{@code role}      = LS_USER_ROLE 의 ROLE_CD (LS 역할 없으면 null 미배정 — 기본값 부여 안 함)</li>
 *   <li>{@code active}    = ('Y' == useYn)</li>
 *   <li>{@code createdAt} = {@code regDt}</li>
 * </ul>
 */
public record UserSummaryResponse(
        // FE 호환 alias
        Long id,
        String loginId,
        String name,
        String email,
        String role,
        Boolean active,
        LocalDateTime createdAt,
        // BE 원본 필드 (호환 유지)
        Long userNo,
        String userId,
        String userNm,
        String userEmail,
        String useYn,
        LocalDateTime regDt
) {
    /** 단순 매핑 — 역할 미주입(null 미배정). */
    public static UserSummaryResponse from(MngAcctUser user) {
        return from(user, null);
    }

    /**
     * 보강 매핑 — 서비스 레이어에서 LS_USER_ROLE 권한 코드를 함께 주입.
     * LS 역할이 없으면 {@code roleCode} 는 null(미배정)이며 기본값을 부여하지 않는다.
     */
    public static UserSummaryResponse from(MngAcctUser user, String roleCode) {
        String resolvedRole = (roleCode != null && !roleCode.isBlank()) ? roleCode : null;
        boolean isActive = "Y".equals(user.getUseYn());
        return new UserSummaryResponse(
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmail(),
                resolvedRole,
                isActive,
                user.getRegDt(),
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmail(),
                user.getUseYn(),
                user.getRegDt()
        );
    }
}
