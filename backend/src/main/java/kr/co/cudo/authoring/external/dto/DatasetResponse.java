package kr.co.cudo.authoring.external.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Phase 4 — 외부 학습데이터 API 응답 DTO.
 *
 * <p>{@code merge=false}: {@link #rawMeta()} / {@link #deidMeta()} 분리, {@link #meta()} 는 null.<br>
 * {@code merge=true}:  {@link #meta()} 채움 (RAW 우선, DEID 보조), 분리 필드는 null.<br>
 * null 필드는 직렬화에서 생략되어 두 모드의 응답 스키마가 명확히 구분된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "외부 학습데이터 API 응답 — merge 파라미터에 따라 meta 필드 구성 변경")
public record DatasetResponse(
        @Schema(description = "영상 메타 (사용자 식별 정보 미포함)") VideoInfo video,
        @Schema(description = "원본 메타 (merge=false 일 때만)") Map<String, String> rawMeta,
        @Schema(description = "비식별 메타 (merge=false 일 때만)") Map<String, String> deidMeta,
        @Schema(description = "병합 메타 (merge=true 일 때만, RAW 우선)") Map<String, String> meta,
        @Schema(description = "병합 여부 플래그") Boolean merged,
        @Schema(description = "프레임 + 라벨 목록 (RAW 프레임 기준 1벌)") List<FrameLabelInfo> labels
) {

    /** merge=false 분리 모드 응답. */
    public static DatasetResponse separated(VideoInfo video,
                                            Map<String, String> rawMeta,
                                            Map<String, String> deidMeta,
                                            List<FrameLabelInfo> labels) {
        return new DatasetResponse(video, rawMeta, deidMeta, null, Boolean.FALSE, labels);
    }

    /** merge=true 병합 모드 응답. */
    public static DatasetResponse merged(VideoInfo video,
                                         Map<String, String> merged,
                                         List<FrameLabelInfo> labels) {
        return new DatasetResponse(video, null, null, merged, Boolean.TRUE, labels);
    }
}
