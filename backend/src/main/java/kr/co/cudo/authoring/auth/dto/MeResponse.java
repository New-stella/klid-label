package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 현재 세션 정보 응답.
 *
 * <p>★ {@code role} 과 {@code name} 의 진실원은 <b>저작도구가 보관한 값</b>이다 — 인계 토큰의
 * 클레임은 보조다. 자세한 근거는 {@code SessionController} javadoc 참조.
 *
 * @design API-006
 */
@Schema(description = "현재 세션 정보 — 주체 식별자·진입 채널 + 저작도구가 보관한 역할·이름")
public record MeResponse(
        @Schema(description = "JWT sub 클레임 (사용자 ID)", example = "1001")
        String userId,

        @Schema(description = "사용자 이름. 화면 상단이 표시하는 이름의 진실원이 이 응답이고 인계 토큰의 "
                + "이름 클레임은 보조라, 토큰에 이름이 실려 오지 않아도 저작도구가 보관한 이름을 싣는다. "
                + "저작도구도 이름을 모를 때만 null 이다.",
                nullable = true, example = "홍길동")
        String name,

        @Schema(description = "권한 코드. 인가에 쓰는 값의 진실원은 저작도구가 보관한 역할이며 인계 토큰의 "
                + "역할 클레임으로 정하지 않는다. 아직 역할이 부여되지 않았으면 null 이고, 이 null 은 "
                + "확인해서 알아낸 「역할 없음」이지 확인하지 못한 상태가 아니다.",
                allowableValues = {"ADMIN", "REVIEWER", "WORKER", "PORTAL_USER"},
                nullable = true, example = "WORKER")
        String role,

        @Schema(description = "진입 채널 (클레임 없으면 null)",
                allowableValues = {"INTERNAL", "PORTAL"}, nullable = true, example = "INTERNAL")
        String channel
) {
}
