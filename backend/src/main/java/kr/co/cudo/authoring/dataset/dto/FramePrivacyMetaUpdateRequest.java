package kr.co.cudo.authoring.dataset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Phase 3 — 프레임 개인정보 메타(익명/가명/개인정보 포함여부) 저장/수정 요청.
 *
 * <p><b>전체 교체(PUT) 계약</b> — 3필드를 항상 함께 전송한다. 생략(null)한 필드는 수동값이 삭제되어 조회 시
 * 파생값(프리필)으로 폴백한다. 파생 상태로 유지할 필드는 <b>null 로 전송</b>한다(조회 프리필값을 그대로
 * 되돌려보내면 수동값으로 승격됨).
 *
 * <p>보안:
 * <ul>
 *   <li><b>입력검증(CWE-20)</b>: 각 필드는 {@code @Pattern("\\A[YN]\\z")} 화이트리스트 — CHAR(1) 오염·자유텍스트
 *       (XSS 표면) 차단. 비우려면 null(필드 생략), 빈 문자열은 400 으로 거부.
 *       <b>{@code ^...$} 가 아닌 이유</b>: Java 정규식의 {@code $} 는 후행 개행 앞에서도 매치돼
 *       {@code "Y\n"} 이 통과한다(CRLF 주입 표면). {@code \A}/{@code \z} 는 입력 전체의 시작·끝만 매치한다.
 *       (2026-08-03 DEV_FIX 2차 — 애노테이션은 이미 {@code \A[YN]\z} 인데 이 문서만 구 표기로 남아 있었다.)</li>
 *   <li><b>Mass Assignment(CWE-915)</b>: Entity 직접 바인딩 금지 — 허용 필드만 명시. 미지 필드는 무시.</li>
 *   <li>path 의 {@code srcSn} 과 body 의 {@code srcSn} 불일치는 컨트롤러에서 400 으로 거부(CWE-345).</li>
 * </ul>
 *
 * @param srcSn           프레임 PK (path 와 일치해야 함)
 * @param anonymity       익명여부(Y/N, null=미판정/삭제)
 * @param pseudonymity    가명여부(Y/N, null=미판정/삭제)
 * @param privacyIncluded 개인정보 포함여부(Y/N, null=미판정/삭제)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FramePrivacyMetaUpdateRequest(
        @NotNull Long srcSn,
        @Pattern(regexp = "\\A[YN]\\z", message = "anonymity 는 Y 또는 N 이어야 합니다.") String anonymity,
        @Pattern(regexp = "\\A[YN]\\z", message = "pseudonymity 는 Y 또는 N 이어야 합니다.") String pseudonymity,
        @Pattern(regexp = "\\A[YN]\\z", message = "privacyIncluded 는 Y 또는 N 이어야 합니다.") String privacyIncluded
) {
}
