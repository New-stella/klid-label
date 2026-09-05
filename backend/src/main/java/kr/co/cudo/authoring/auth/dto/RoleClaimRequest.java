package kr.co.cudo.authoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.common.security.Role;

/**
 * 관리자 부트스트랩(역할 자가 부여) 요청 DTO — 인증은 되었으나 role 클레임이 없는 사용자가
 * 관리자 패스워드와 함께 본인에게 ADMIN 역할을 부여한다.
 *
 * <p>★ {@code role} 은 <b>결과를 바꾸지 못한다</b>(ADR-055). 부여 역할은 ADMIN 고정이며 이 필드는
 * {@code PORTAL_USER} 를 400 으로 걸러내는 용도로만 남아 있다(별도 채널임을 요청자에게 알린다).
 * 하위호환을 위해 필수로 유지한다 — 구 클라이언트가 보내던 {@code WORKER}/{@code REVIEWER} 도
 * 그대로 수용되며 ADMIN 이 부여된다.
 *
 * <p>보안:
 * <ul>
 *   <li>{@code adminPassword} 평문은 본 DTO 의 LIFE-CYCLE 동안만 메모리에 존재해야 하며
 *       절대 로그/응답/예외 메시지에 노출되어서는 안 된다 (CWE-256/532).</li>
 *   <li>{@code role} 은 enum 타입 자체로 파싱 단계 화이트리스트가 강제됨. {@code PORTAL_USER} 는
 *       별도 채널(포털 서버)에서만 발급되므로 본 API 에서는 서비스 단에서 거절한다. ★인가에
 *       쓰이는 값이 아니다 — 부여 역할은 서버가 고정하므로 이 필드로는 권한을 올릴 수 없다
 *       (CWE-269 표면 제거).</li>
 *   <li><b>★{@code userNo} 필드를 두지 않는다 (CWE-915 Mass Assignment / CWE-639 IDOR)</b> —
 *       사용자 식별은 <b>JWT subject 에서만</b> 취한다. 바디로 받으면 남의 사용자 행을 만들거나
 *       표시명을 바꿀 수 있다. 같은 이유로 권한 승격에 쓰일 수 있는 어떤 필드도 두지 않는다
 *       (부여 역할은 서버 고정값 + 화이트리스트 + 관리자 패스워드 + 관리자 0명 조건으로만
 *       결정된다).</li>
 *   <li>{@code userId}/{@code userNm} 은 <b>표시용</b>이며 위조해도 <b>자기 행</b>의 표시 이름만
 *       바뀐다. 그래도 길이 상한을 걸고(아래 {@code @Size}) 서비스가 제어문자 제거·공백 정규화를
 *       한다 (CWE-117 / CWE-20).</li>
 * </ul>
 *
 * <p><b>하위호환</b>: {@code userId}·{@code userNm} 은 <b>선택</b> 필드다. 구 FE(인계값을 안 보내는
 * 버전)나 직접 호출도 그대로 성공해야 하며, 그 경우 표시 정보는 미수신으로 남는다(기존 값 보존).
 */
@Schema(description = "관리자 부트스트랩 요청 — 인증된 사용자가 관리자 패스워드와 함께 본인에게 ADMIN 역할을 부여한다. 관리자가 0명일 때만 열린다.")
public record RoleClaimRequest(
        @Schema(description = "요청 역할 — 결과에 영향을 주지 않는다(부여 역할은 ADMIN 고정). PORTAL_USER 만 400 으로 거절된다.",
                example = "ADMIN", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "role 은 필수입니다.")
        Role role,

        @Schema(description = "관리자 공유 패스워드 (BCrypt 비교)",
                example = "admin1234", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "adminPassword 는 필수입니다.")
        @Size(min = 4, max = 100, message = "adminPassword 는 4~100자여야 합니다.")
        String adminPassword,

        @Schema(description = "관제서버가 localStorage 로 인계한 사용자 아이디 (표시용, 선택)",
                example = "sjs123", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 20, message = "userId 는 20자 이하여야 합니다.")
        String userId,

        @Schema(description = "관제서버가 localStorage 로 인계한 사용자 이름 (표시용, 선택)",
                example = "신재석", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 100, message = "userNm 은 100자 이하여야 합니다.")
        String userNm
) {
    /** 표시 정보 없이(구 FE·직접 호출) 만드는 하위호환 생성자. */
    public RoleClaimRequest(Role role, String adminPassword) {
        this(role, adminPassword, null, null);
    }
}
