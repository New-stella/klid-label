package kr.co.cudo.authoring.dataset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Phase 3 — 프레임 개인정보 메타 벌크 저장(PUT {@code /v1/frames/privacy-meta}) 단일 항목.
 *
 * <p>대량성(MED#6) — 영상당 프레임 수백~수천개를 한 요청으로 저장한다. 각 항목은 {@code srcSn} 로 프레임을
 * 지정하며 서비스가 항목마다 {@code verifyAndGet} 인가(IDOR 방어)를 수행한다(요청 신뢰 금지).
 *
 * <p>검증은 단건 {@link FramePrivacyMetaUpdateRequest} 와 동일 — 3필드 {@code @Pattern("^[YN]$")} 화이트리스트,
 * null=미판정/삭제(전체 교체).
 *
 * @param srcSn           프레임 PK
 * @param anonymity       익명여부(Y/N, null=삭제)
 * @param pseudonymity    가명여부(Y/N, null=삭제)
 * @param privacyIncluded 개인정보 포함여부(Y/N, null=삭제)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FramePrivacyBulkItem(
        @NotNull Long srcSn,
        @Pattern(regexp = "^[YN]$", message = "anonymity 는 Y 또는 N 이어야 합니다.") String anonymity,
        @Pattern(regexp = "^[YN]$", message = "pseudonymity 는 Y 또는 N 이어야 합니다.") String pseudonymity,
        @Pattern(regexp = "^[YN]$", message = "privacyIncluded 는 Y 또는 N 이어야 합니다.") String privacyIncluded
) {
}
