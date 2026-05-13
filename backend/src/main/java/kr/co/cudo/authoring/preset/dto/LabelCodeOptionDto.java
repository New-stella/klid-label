package kr.co.cudo.authoring.preset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 프리셋 라벨 코드 옵션 DTO (Controller·Service 공통, Phase 1).
 *
 * <p>각 항목은 {@code code} + BBOX/POLYGON 두 토글로 구성된다. Bean Validation
 * {@link AssertTrue} 로 두 옵션 모두 false 인 조합을 거부한다 (DB CHECK + 엔티티 가드와 함께 다중 보호).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelCodeOptionDto(
        @NotBlank(message = "라벨 코드는 필수입니다")
        @Size(max = 32, message = "라벨 코드는 32자 이하여야 합니다")
        String code,
        boolean bboxEnabled,
        boolean polygonEnabled
) {

    /**
     * 둘 다 false 인 옵션을 거부한다. Spring Validation 이
     * Controller {@code @Valid} 진입 시점에 평가한다.
     */
    @AssertTrue(message = "각 라벨은 BBOX 또는 POLYGON 중 최소 하나가 활성화되어야 합니다.")
    public boolean isAtLeastOneEnabled() {
        return bboxEnabled || polygonEnabled;
    }

    /** 레거시 {@code labelCodes} String 입력을 BBOX+POLYGON 모두 활성 옵션으로 변환. */
    public static LabelCodeOptionDto both(String code) {
        return new LabelCodeOptionDto(code, true, true);
    }
}
