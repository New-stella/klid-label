package kr.co.cudo.authoring.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * 사용자 수정 요청 — 관리자의 사용자 관리 화면 (역할 · 표시 이름 변경).
 *
 * <p>역할 분리 리팩토링 Phase 2 — 활성/비활성(useYn) 쓰기는 관제 소유 컬럼이라 본 도구에서 제거했고
 * (FE 토글 제거는 Phase 4), 본 요청이 쓰는 것은 저작도구 소유 역할({@code LS_USER_ROLE})과 사용자
 * 마스터의 <b>표시 이름</b>({@code LS_ACNT_USER.USER_NM}) 둘이다.
 *
 * <p><b>두 축은 서로 독립이다</b> — 각각 선택이며 보내지 않은 축은 바꾸지 않는다. 이름만 보낸 요청은
 * 역할 축의 판정(마지막 관리자 보호)을 타지 않는다.
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
 *   <li>★{@code userNm} 에는 <b>선언적 검증(@Size/@NotBlank)을 걸지 않는다</b> — 판정 대상이 원문이
 *       아니라 <b>공백·제어문자를 걷어낸 정규화 결과</b>라서다(@design API-004). 선언적 검증은 원문을
 *       보므로 공백뿐인 이름을 통과시키고, 반대로 앞뒤 공백 때문에 정상 이름을 거절한다. 정규화 후
 *       판정은 서비스가 소유하며 거부는 400 이다. 부수 효과로 거부 응답이 필드 경로를 담지 않는다 —
 *       선언적 검증의 기본 응답은 {@code "userNm: ..."} 처럼 경로를 싣는다.</li>
 *   <li>Entity 직접 바인딩 금지 — 본 record 만 Controller {@code @RequestBody} 에 노출한다.
 *       주체(actor/mdfrId) 필드를 추가하지 말 것 — 바디 값은 위조 가능하다.</li>
 * </ul>
 *
 * @design API-004
 * @design AC-1018
 * @design AC-1019
 */
public record UserUpdateRequest(
        @Schema(description = "역할 코드 (선택, null 이면 역할 미변경)",
                allowableValues = {"ADMIN", "REVIEWER", "WORKER", "PORTAL_USER"}, nullable = true)
        @Pattern(regexp = "^(ADMIN|REVIEWER|WORKER|PORTAL_USER)$",
                message = "role은 ADMIN/REVIEWER/WORKER/PORTAL_USER만 허용됩니다.")
        String role,

        @Schema(description = "사용자 표시 이름 (선택, null 이면 이름 미변경). 저장 직전 공백·제어문자를 "
                + "걷어낸 값으로 판정하며, 그 뒤 남는 것이 없거나 저장 폭을 넘으면 400 이다 — 잘라 담지 "
                + "않는다. 같은 이름을 다시 보내면 아무것도 바꾸지 않는다.",
                example = "홍길동", nullable = true)
        String userNm
) {
}
