package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

/**
 * 배포 압축본의 <b>라벨 문서</b>를 읽는 최소 레코드 — 우리가 쓰는 칸만 담는다(ADR-068).
 *
 * <h3>왜 산출물 레코드를 쓰지 않는가</h3>
 * <p>우리 산출물 레코드({@code dataset/export/json} 의 NIA 문서)는 <b>우리가 내보내는 계약</b>이라
 * 남의 배포본을 읽는 자리에서 넓히면 계약면이 흔들린다. 그리고 개발망 실물(2026-09-16)은 칸 이름이
 * 달랐다 — 영상 파일명이 {@code file_name}(산출물은 {@code filename}), 프레임 번호가
 * {@code frame_no}(산출물은 {@code frame_num})이고, 분류 식별자 목록({@code categories})이 아예 없이
 * 어노테이션이 분류 <b>이름 문자열</b>({@code category})을 싣는다.
 *
 * <h3>★ 두 모양을 모두 읽는다 — 어느 쪽인지 문서가 스스로 말하지 않는다</h3>
 * <p>배포본에 실물 모양과 산출물 모양이 섞여 올 수 있으므로 칸마다 <b>실물 이름 → 산출물 이름</b>
 * 순으로 본다. 어느 쪽에도 값이 없으면 지어내지 않고 비운다(그 공백의 처리는 읽는 쪽이 정한다).
 *
 * <p>모르는 칸은 무시한다 — 우리가 쓰는 칸의 <b>형식</b>이 어긋날 때만 실패다.
 *
 * @design ADR-068
 * @design AC-1118
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortalDatasetDoc(
        @JsonProperty("video") Video video,
        @JsonProperty("image") Image image,
        @JsonProperty("annotations") List<Annotation> annotations,
        @JsonProperty("categories") List<Category> categories) {

    /** 영상 블록 — 영상 식별자(파일명)의 조달처다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Video(
            @JsonProperty("file_name") String fileName,
            @JsonProperty("filename") String filename,
            @JsonProperty("vdo_len_sec") Integer vdoLenSec,
            @JsonProperty("evnt_type_cd") String evntTypeCd,
            @JsonProperty("event_id") String eventId,
            @JsonProperty("fps") String fps,
            @JsonProperty("width") Integer width,
            @JsonProperty("height") Integer height) {

        /** 영상 파일명 — 실물 {@code file_name} 우선, 없으면 산출물 {@code filename}. */
        public String resolvedFileName() {
            return firstNonBlank(fileName, filename);
        }

        /** 검증 이벤트 유형 코드 — 실물 {@code evnt_type_cd} 우선, 없으면 산출물 {@code event_id}. */
        public String resolvedEventTypeCd() {
            return firstNonBlank(evntTypeCd, eventId);
        }
    }

    /** 이미지 블록. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(
            @JsonProperty("frame_no") Integer frameNo,
            @JsonProperty("frame_num") Integer frameNum,
            @JsonProperty("width") Integer width,
            @JsonProperty("height") Integer height,
            @JsonProperty("anonymity") String anonymity,
            @JsonProperty("pseudonymity") String pseudonymity,
            @JsonProperty("privacy_included") String privacyIncluded,
            @JsonProperty("description") String description) {
    }

    /**
     * 어노테이션 한 건.
     *
     * <p>{@code bbox} 는 {@code [x, y, w, h]} 다. 실물은 그 사실을 {@code bbox_format} 으로 명시해
     * 온다 — 다른 값이 오면 좌표 뜻이 달라지므로 <b>짐작해 읽지 않고</b> 읽는 쪽이 멈춘다.
     *
     * <p>분류는 두 모양이다 — 실물은 이름 문자열({@code category}), 산출물은 식별자
     * ({@code category_id}) + {@code categories} 목록.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Annotation(
            @JsonProperty("category") String category,
            @JsonProperty("category_id") String categoryId,
            @JsonProperty("track_id") String trackId,
            @JsonProperty("bbox") List<Number> bbox,
            @JsonProperty("bbox_format") String bboxFormat,
            @JsonProperty("polygon") List<List<Number>> polygon,
            @JsonProperty("keypoints") List<List<Number>> keypoints) {
    }

    /** 분류 목록 항목 — 산출물 모양에만 있다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name) {
    }

    /** 사각 박스 좌표 뜻이 {@code [x, y, w, h]} 라고 말하는 표기. */
    public static final String BBOX_FORMAT_XYWH = "xywh";

    /** 표기가 없거나 {@link #BBOX_FORMAT_XYWH} 일 때만 참 — 그 밖은 읽는 쪽이 멈춘다. */
    public static boolean isXywh(String bboxFormat) {
        return bboxFormat == null || bboxFormat.isBlank()
                || BBOX_FORMAT_XYWH.equals(bboxFormat.trim().toLowerCase(Locale.ROOT));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }
}
