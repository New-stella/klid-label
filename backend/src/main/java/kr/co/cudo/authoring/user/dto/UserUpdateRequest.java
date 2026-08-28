package kr.co.cudo.authoring.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * 사용자 수정 요청 — REVIEWER 의 사용자 관리 화면 (역할 변경).
 *
 * <p>역할 분리 리팩토링 Phase 2 — 활성/비활성(useYn) 쓰기는 관제 소유 컬럼이라 본 도구에서 제거했고
 * (FE 토글 제거는 Phase 4), 본 요청은 저작도구 소유 역할({@code LS_USER_ROLE}) 변경만 담당한다.
 *
 * <p>보안:
 * <ul>
 *   <li>{@code role} 은 선택(null 허용) — null 이면 역할을 변경하지 않는다.</li>
 *   <li>{@link Pattern} 화이트리스트 정규식으로 허용 값 외 입력을 400 으로 차단한다 (Mass Assignment 방어).
 *       역할은 인가 핵심 값이므로 ADMIN/REVIEWER/WORKER/PORTAL_USER 외 입력을 거절한다.
 *       ★목록 대조를 유지한다 — 저장 계층이 외부 문자열을 그대로 받지 않게 하는 자리이며,
 *       화면이 고를 수 있는 값을 제한하는 것과 별개로 서버가 다시 판정한다(@design AC-057).</li>
 *   <li>{@code ADMIN} 으로의 승격·강등은 이 창구에서 가능하지만, <b>마지막 남은 ADMIN 을 내리는
 *       요청은 409</b> 로 거절된다(@design AC-124). 그 판정은 서비스가 소유한다.</li>
 *   <li>Entity 직접 바인딩 금지 — 본 record 만 Controller {@code @RequestBody} 에 노출한다.</li>
 * </ul>
 */
public record UserUpdateRequest(
        @Schema(description = "역할 코드 (선택, null 이면 역할 미변경)",
                allowableValues = {"ADMIN", "REVIEWER", "WORKER", "PORTAL_USER"}, nullable = true)
        @Pattern(regexp = "^(ADMIN|REVIEWER|WORKER|PORTAL_USER)$",
                message = "role은 ADMIN/REVIEWER/WORKER/PORTAL_USER만 허용됩니다.")
        String role
) {
}
