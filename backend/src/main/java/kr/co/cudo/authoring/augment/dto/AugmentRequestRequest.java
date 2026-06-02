package kr.co.cudo.authoring.augment.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 외부 SFR-07 증강 시스템 요청 본문.
 *
 * <p>검수 완료된 영상(LsRawDataStatus.dataSttsCd='APPROVED')만 증강 요청 가능하며,
 * 본 검증은 Service 레이어에서 수행한다. DTO 단계에서는 형식·범위만 검증한다.
 *
 * <p>해상도 변경(RESOLUTION)은 저작도구가 직접 수행하므로(RQ-SFR-06-03) 외부 증강 위탁
 * 유형에서 제외한다. 외부 증강은 날씨·계절·시간 3종(WINTER/NIGHT/RAIN)만 위탁한다.
 *
 * @param videoIds 검수 완료된 영상 ID 목록 (1~100개, 양수)
 * @param types    요청 증강 유형 — WINTER / NIGHT / RAIN
 */
public record AugmentRequestRequest(
        @NotEmpty(message = "videoIds 는 필수이며 비어있을 수 없습니다.")
        @Size(max = 100, message = "videoIds 는 한 번에 최대 100개까지 요청할 수 있습니다.")
        List<@NotNull @Positive Long> videoIds,

        @NotEmpty(message = "types 는 필수이며 비어있을 수 없습니다.")
        @Size(max = 3, message = "types 는 최대 3종까지만 허용됩니다.")
        List<@NotNull AugmentTypeCode> types
) {
    /**
     * 증강 유형 enum — DTO 바인딩 단계에서 잘못된 값 차단 (CWE-20 Input Validation).
     * Jackson 이 enum 매칭 실패 시 400 응답이 자동 반환된다.
     */
    public enum AugmentTypeCode {
        WINTER, NIGHT, RAIN
    }
}
