package kr.co.cudo.authoring.user.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 사용자 수정 요청 — REVIEWER 의 사용자 관리 화면 (활성/비활성, 역할 변경).
 *
 * <p>보안:
 * <ul>
 *   <li>두 필드 모두 선택(null 허용) — null 이면 해당 필드는 변경하지 않는다.</li>
 *   <li>{@link Pattern} 화이트리스트 정규식으로 허용 값 외 입력을 400 으로 차단한다 (Mass Assignment 방어).</li>
 *   <li>Entity 직접 바인딩 금지 — 본 record 만 Controller {@code @RequestBody} 에 노출한다.</li>
 * </ul>
 */
public record UserUpdateRequest(
        @Pattern(regexp = "^[YN]$", message = "useYn은 Y 또는 N만 허용됩니다.")
        String useYn,

        @Pattern(regexp = "^(REVIEWER|WORKER|PORTAL_USER)$", message = "role은 REVIEWER/WORKER/PORTAL_USER만 허용됩니다.")
        String role
) {
}
