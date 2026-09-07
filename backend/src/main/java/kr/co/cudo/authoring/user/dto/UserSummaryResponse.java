package kr.co.cudo.authoring.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.user.entity.LsAcntUser;

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
 *   <li>{@code lastLoginAt} = {@code lastLgnDt} (최종로그인일시 — <b>미접속이면 null</b>)</li>
 * </ul>
 *
 * <p><b>★ {@code createdAt} 과 {@code lastLoginAt} 은 별개 축이며 서로를 대체하지 않는다.</b>
 * 등록일은 가입 이력이고 최종로그인일시는 휴면 계정 판단용이다. 값이 없을 때 등록일로 폴백하지
 * 않는다 — 과거 화면이 그 폴백을 넣어 <b>가입 시각을 "최근 로그인" 으로 표시</b>했고(거짓 표기),
 * 그것이 이 필드를 만든 이유다. 미접속은 {@code null} 로 내려보내고 표기는 화면이 담당한다.
 *
 * <h3>★ {@code role} 은 미배정이면 {@code null} 이다 — 기본값을 부여하지 않는다 (@design API-001)</h3>
 * <p>이 창구는 <b>한 응답에 여러 사용자의 역할</b>을 싣는 자리라, 역할이 아직 부여되지 않은 사용자를
 * 응답에서 피할 수 없다(대상자를 호출자가 고르지 않는다). 그 {@code null} 은 <b>확인해서 알아낸
 * 「역할 없음」</b>이지 확인하지 못한 상태가 아니며, 네 값 중 어느 것으로도 임의로 채우지 않는다.
 * ⚠ 「없으면 작업자」 같은 기본값 처리를 되살리지 말 것 — 사실과 다른 역할을 보이게 하던 결함이다.
 *
 * @design SCREEN-024
 * @design API-001
 */
public record UserSummaryResponse(
        // FE 호환 alias
        @Schema(description = "사용자 PK (userNo alias)", example = "1001")
        Long id,

        @Schema(description = "로그인 ID (userId alias)", example = "worker01")
        String loginId,

        @Schema(description = "사용자 이름 (userNm alias)", example = "홍길동")
        String name,

        @Schema(description = "이메일 (userEmail alias). 검색 매칭 축이 아니며, 인계 토큰에 이메일이 "
                + "실려 오지 않아 그 경로로 진입한 사용자는 비어 있다", nullable = true)
        String email,

        @Schema(description = "권한 코드. 인가에 쓰는 값의 진실원은 저작도구가 보관한 역할이다. "
                + "아직 역할이 부여되지 않은 사용자는 null 이며 네 값 중 하나로 임의로 채우지 않는다.",
                allowableValues = {"ADMIN", "REVIEWER", "WORKER", "PORTAL_USER"},
                nullable = true, example = "WORKER")
        String role,
        Boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        // BE 원본 필드 (호환 유지)
        Long userNo,
        String userId,
        String userNm,
        String userEmail,
        String useYn,
        LocalDateTime regDt,
        LocalDateTime lastLgnDt
) {
    /** 단순 매핑 — 역할 미주입(null 미배정). */
    public static UserSummaryResponse from(LsAcntUser user) {
        return from(user, null);
    }

    /**
     * 보강 매핑 — 서비스 레이어에서 LS_USER_ROLE 권한 코드를 함께 주입.
     * LS 역할이 없으면 {@code roleCode} 는 null(미배정)이며 기본값을 부여하지 않는다.
     */
    public static UserSummaryResponse from(LsAcntUser user, String roleCode) {
        String resolvedRole = (roleCode != null && !roleCode.isBlank()) ? roleCode : null;
        boolean isActive = "Y".equals(user.getUseYn());
        return new UserSummaryResponse(
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmlAddr(),
                resolvedRole,
                isActive,
                user.getRegDt(),
                user.getLastLgnDt(),
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmlAddr(),
                user.getUseYn(),
                user.getRegDt(),
                user.getLastLgnDt()
        );
    }
}
