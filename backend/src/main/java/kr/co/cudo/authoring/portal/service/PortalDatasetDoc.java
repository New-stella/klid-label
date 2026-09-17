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
 * <h3>★ 아는 모양을 <b>모두</b> 읽는다 — 어느 쪽인지 문서가 스스로 말하지 않는다</h3>
 * <p>문서 스키마가 <b>배포본마다 갈린다</b>(개발망 실물 넷으로 확인 · 2026-09-16). 어느 하나를 정본으로
 * 고르면 그 밖의 배포본이 통째로 실패하고, 실패 사유가 「구조가 다르다」뿐이라 무엇이 다른지도 드러나지
 * 않는다(ADR-068). 그래서 칸마다 <b>기존에 읽던 자리를 먼저 보고, 없을 때만</b> 새 자리를 본다:
 *
 * <table><caption>칸별 조달 순서</caption>
 *   <tr><th>칸</th><th>1순위(기존)</th><th>2순위(실물 5148~5150)</th></tr>
 *   <tr><td>프레임 번호</td><td>{@code image.frame_no}·{@code image.frame_num}</td>
 *       <td><b>최상위 {@code frame_no}</b></td></tr>
 *   <tr><td>크기</td><td>{@code image.width}/{@code height}</td>
 *       <td><b>{@code resolution: [w, h]}</b></td></tr>
 *   <tr><td>라벨 배열</td><td>{@code annotations}</td><td><b>{@code objects}</b></td></tr>
 *   <tr><td>분류 이름</td><td>{@code category}</td><td><b>{@code class}</b></td></tr>
 * </table>
 *
 * <p>어느 쪽에도 값이 없으면 지어내지 않고 비운다(그 공백의 처리는 읽는 쪽이 정한다).
 *
 * <p>⚠ 실물이 함께 싣는 {@code video_ts_ms}·{@code label}·{@code camera_id}·{@code captured_at}·
 * {@code confidence}·{@code lat}/{@code lon}·{@code source} 는 <b>읽지 않는다</b> — 지금 그 값이 앉을
 * 자리가 없다. 억지로 메타에 넣지 않는다(필요해지면 설계에 먼저 적는다).
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
        @JsonProperty("objects") List<Annotation> objects,
        @JsonProperty("categories") List<Category> categories,
        @JsonProperty("frame_no") Integer frameNo,
        @JsonProperty("resolution") List<Number> resolution) {

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
     * <p>분류는 세 모양이다 — 산출물은 식별자({@code category_id}) + {@code categories} 목록,
     * 5145 실물은 이름 문자열({@code category}), 5148~5150 실물은 이름 문자열 {@code class}.
     *
     * <p>⚠ {@code confidence} 는 읽지 않는다 — 우리가 등록하는 것은 <b>사람이 확정한 원본 라벨</b>이고
     * 그 자리에 AI 신뢰도를 앉히면 출처가 뒤섞인다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Annotation(
            @JsonProperty("category") String category,
            @JsonProperty("class") String className,
            @JsonProperty("category_id") String categoryId,
            @JsonProperty("track_id") String trackId,
            @JsonProperty("bbox") List<Number> bbox,
            @JsonProperty("bbox_format") String bboxFormat,
            @JsonProperty("polygon") List<List<Number>> polygon,
            @JsonProperty("keypoints") List<List<Number>> keypoints) {

        /** 분류 이름 — {@code category} 우선, 없으면 {@code class}. 식별자로 되짚지 않는다. */
        public String resolvedCategoryName() {
            return firstNonBlank(category, className);
        }
    }

    /** 분류 목록 항목 — 산출물 모양에만 있다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name) {
    }

    /**
     * 라벨 배열 — {@code annotations} 우선, <b>없거나 비어 있으면</b> {@code objects}.
     *
     * <p>둘 다 없으면 빈 목록이다. 라벨이 0건인 프레임은 <b>정상</b>이며(실물 5149 의 첫 프레임이
     * {@code "objects": []} 다) 실패가 아니다 — 라벨 없는 프레임도 등록되어야 그 영상을 열 수 있다.
     */
    public List<Annotation> resolvedAnnotations() {
        if (annotations != null && !annotations.isEmpty()) {
            return annotations;
        }
        return objects != null ? objects : List.of();
    }

    /** 프레임 번호 — {@code image.frame_no} 우선, 없으면 <b>최상위</b> {@code frame_no}. */
    public Integer resolvedFrameNo() {
        Integer fromImage = image == null ? null : image.frameNo();
        return fromImage != null ? fromImage : frameNo;
    }

    /** 가로 크기 — {@code image.width} 우선, 없으면 {@code resolution[0]}. */
    public Integer resolvedWidth() {
        Integer fromImage = image == null ? null : image.width();
        return fromImage != null ? fromImage : resolutionAt(0);
    }

    /** 세로 크기 — {@code image.height} 우선, 없으면 {@code resolution[1]}. */
    public Integer resolvedHeight() {
        Integer fromImage = image == null ? null : image.height();
        return fromImage != null ? fromImage : resolutionAt(1);
    }

    /** {@code resolution} 의 한 칸 — 길이가 모자라거나 값이 없으면 {@code null}(짐작하지 않는다). */
    private Integer resolutionAt(int index) {
        if (resolution == null || resolution.size() <= index) {
            return null;
        }
        Number n = resolution.get(index);
        return n == null ? null : n.intValue();
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
