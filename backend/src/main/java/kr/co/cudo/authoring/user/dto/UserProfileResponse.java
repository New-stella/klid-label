package kr.co.cudo.authoring.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.user.entity.LsAcntUser;

/**
 * 사용자 프로필 응답 — 본인 조회({@code GET /v1/users/me})와 관리 단건 조회
 * ({@code GET /v1/users/{userNo}})가 함께 쓴다.
 *
 * <p>★ 표시 이름({@code userNm})과 인가에 쓰는 역할({@code role})의 진실원은 <b>저작도구가 보관한
 * 값</b>이며 인계 토큰의 이름·역할 클레임은 보조다 — {@code sub} 클레임은 누구의 정보인지 식별하는
 * 데만 쓰고, 실리는 이름은 사용자 마스터의 보관값이라 토큰이 이름을 싣지 않아도 채워진다.
 *
 * <p>역할이 아직 부여되지 않았으면 {@code role} 은 {@code null}(미배정)이며 기본값을 부여하지 않는다.
 *
 * @design API-003
 * @design API-005
 */
public record UserProfileResponse(
        @Schema(description = "사용자 PK", example = "1001")
        Long userNo,

        @Schema(description = "로그인 ID", example = "worker01")
        String userId,

        @Schema(description = "사용자 이름 — 저작도구가 보관한 값이 진실원이다", example = "홍길동")
        String userNm,

        @Schema(description = "이메일. 인계 토큰에 이메일이 실려 오지 않아 그 경로로 진입한 사용자는 비어 있다",
                nullable = true)
        String userEmail,

        @Schema(description = "권한 코드. 인가에 쓰는 값의 진실원은 저작도구가 보관한 역할이며 인계 토큰의 "
                + "역할 클레임으로 정하지 않는다. 아직 역할이 부여되지 않았으면 null(미배정)이고 "
                + "기본값을 부여하지 않는다.",
                allowableValues = {"ADMIN", "REVIEWER", "WORKER", "PORTAL_USER"},
                nullable = true, example = "WORKER")
        String role,

        @Schema(description = "진입 채널. 피조회 사용자의 채널 컨텍스트가 없는 관리 조회에서는 빈 값이다",
                allowableValues = {"INTERNAL", "PORTAL", ""}, example = "INTERNAL")
        String channel
) {
    public static UserProfileResponse of(LsAcntUser user, String role, String channel) {
        return new UserProfileResponse(
                user.getUserNo(),
                user.getUserId(),
                user.getUserNm(),
                user.getUserEmlAddr(),
                role,
                channel
        );
    }
}
