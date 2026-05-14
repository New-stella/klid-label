package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Phase 3 — 비식별 누락 신고 요청 DTO.
 *
 * <p>보안:
 * <ul>
 *   <li><b>Input Validation (OWASP A08:2025)</b>: NotBlank + 1000자 상한 (CWE-770 DoS 방어).</li>
 *   <li><b>Mass Assignment 방어</b>: reason 만 노출 (status, reporterNo 등 서버 전용 필드 비공개).</li>
 * </ul>
 *
 * @param reason 신고 사유 (1~1000자)
 */
public record DeidentReportRequest(
        @NotBlank(message = "신고 사유는 필수입니다.")
        @Size(max = 1000, message = "신고 사유는 1000자 이하여야 합니다.")
        String reason
) {
}
