package kr.co.cudo.authoring.dataset.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 영상 단위 촬영환경(날씨·시간대·계절) 조회 응답.
 *
 * <p>각 값은 <b>수동 저장값이 있으면 그 값</b>, 없으면 촬영일시(SHT_DT) 파생값이다. 화면이 "작업자가
 * 확정한 값"과 "자동 프리필"을 구분할 수 있도록 항목별 출처({@link #SOURCE_MANUAL}/{@link #SOURCE_DERIVED})
 * 를 함께 반환한다. 값이 없으면(날씨 미입력·촬영일시 미상) 값·출처 모두 null.
 */
@Schema(description = "영상 촬영환경 메타(수동값 우선, 없으면 촬영일시 파생 프리필)")
public record EnvironmentMetaResponse(
        @Schema(description = "영상 PK") Long rawSn,
        @Schema(description = "날씨(맑음/흐림/비/눈/안개)") String weather,
        @Schema(description = "시간대 코드(DAY/NGT)") String timeOfDay,
        @Schema(description = "계절 코드(SPRING/SUMMER/FALL/WINTER)") String season,
        @Schema(description = "날씨 출처(MANUAL/DERIVED)") String weatherSource,
        @Schema(description = "시간대 출처(MANUAL/DERIVED)") String timeOfDaySource,
        @Schema(description = "계절 출처(MANUAL/DERIVED)") String seasonSource
) {

    /** 작업자가 수동 저장한 값. */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 촬영일시(SHT_DT)로부터 자동 파생한 프리필 값. */
    public static final String SOURCE_DERIVED = "DERIVED";
}
