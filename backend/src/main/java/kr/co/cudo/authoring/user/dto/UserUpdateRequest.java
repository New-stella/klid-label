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
 *       역할은 인가 핵심 값이므로 REVIEWER/WORKER/PORTAL_USER 외 입력을 거절한다.</li>
 *   <li>Entity 직접 바인딩 금지 — 본 record 만 Controller {@code @RequestBody} 에 노출한다.</li>
 * </ul>
 */
public record UserUpdateRequest(
        @Schema(description = "역할 코드 (선택, null 이면 역할 미변경)",
                allowableValues = {"REVIEWER", "WORKER", "PORTAL_USER"}, nullable = true)
        @Pattern(regexp = "^(REVIEWER|WORKER|PORTAL_USER)$", message = "role은 REVIEWER/WORKER/PORTAL_USER만 허용됩니다.")
        String role
) {
}
