package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NIA COCO 확장 {@code video} 블록 (xlsx v1.3).
 *
 * <p>미보유 필드(pixel, cctv_height, cctv_azimuth, og_cd, cto, vqa, event_log 등)는 null 이지만
 * {@link JsonInclude.Include#ALWAYS} 로 키를 유지한다(관제 데이터마트 스키마 정합).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NiaVideo(
        @JsonProperty("id") String id,
        @JsonProperty("filename") String filename,
        @JsonProperty("orign_filename") String orignFilename,
        @JsonProperty("date_created") String dateCreated,
        @JsonProperty("type") String type,
        @JsonProperty("format") String format,
        @JsonProperty("filesize") Long filesize,
        @JsonProperty("location") String location,
        @JsonProperty("license_id") String licenseId,
        @JsonProperty("length") String length,
        @JsonProperty("fps") String fps,
        @JsonProperty("frames") Integer frames,
        @JsonProperty("aspect_ratio") String aspectRatio,
        @JsonProperty("width") Integer width,
        @JsonProperty("height") Integer height,
        @JsonProperty("resolution") String resolution,
        @JsonProperty("bit") String bit,
        @JsonProperty("pixel") String pixel,
        @JsonProperty("weather") String weather,
        @JsonProperty("coordinates") String coordinates,
        @JsonProperty("og_cd") String ogCd,
        @JsonProperty("cctv_name") String cctvName,
        @JsonProperty("cctv_height") Double cctvHeight,
        @JsonProperty("cctv_azimuth") Integer cctvAzimuth,
        @JsonProperty("cctv_mng_no") String cctvMngNo,
        @JsonProperty("anonymity") String anonymity,
        @JsonProperty("pseudonymity") String pseudonymity,
        @JsonProperty("privacy_included") String privacyIncluded,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_name") String eventName,
        @JsonProperty("time_of_day") String timeOfDay,
        @JsonProperty("season") String season,
        @JsonProperty("cto") String cto,
        @JsonProperty("vqa") String vqa,
        @JsonProperty("event_log") String eventLog
) {
}
