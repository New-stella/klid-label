package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 키포인트 라벨(SKELETON, 17-keypoint COCO 포즈) 전용 좌표 직렬화 헬퍼.
 *
 * <p>정규 포맷: {@code [[x, y, v], ...]} — 정확히 {@value #KEYPOINT_COUNT} 개의 삼중값.
 *
 * <p><b>격리 원칙(Critical)</b>: 기존 4타입(BBOX/POLYGON/SEGMENT/TRACK)의 2-튜플
 * {@link LabelPointSerializer} 경로는 이 클래스와 완전히 분리된다. SKELETON 만 {@code LBL_TYPE_CD}
 * 기반 type-route 로 이 경로를 탄다(회귀 격리).
 *
 * <p>입력 검증(CWE-20): 개수/삼중값 크기/가시성 범위는 상위 Service(validatePoints)에서 400 으로
 * 강제한다. 본 헬퍼는 파싱 시 형식 위반을 명확히 거부(fail-secure)하되, 예외 메시지에 원본 JSON
 * 전문을 노출하지 않는다(CWE-117/209).
 *
 * <p>JSON Injection/역직렬화 방어(CWE-502): Jackson 의 안전한 기본 모드만 사용.
 * enableDefaultTyping / {@code @JsonTypeInfo(use=CLASS)} 등 다형성 역직렬화 미사용.
 */
public final class KeypointSerializer {

    /** COCO-17 포즈 키포인트 개수 (고정). */
    public static final int KEYPOINT_COUNT = 17;

    /** 삼중값 원소 크기 [x, y, v]. */
    public static final int TRIPLET_SIZE = 3;

    /** 가시성 최소값 (0=미표기). */
    public static final int VISIBILITY_MIN = 0;

    /** 가시성 최대값 (2=가시). */
    public static final int VISIBILITY_MAX = 2;

    private KeypointSerializer() {
    }

    /**
     * KeypointPoint 리스트 → JSON 배열 문자열 {@code [[x,y,v], ...]}.
     * null/빈 입력은 {@code "[]"}.
     */
    public static String toJson(List<KeypointPoint> keypoints, ObjectMapper objectMapper) {
        if (keypoints == null || keypoints.isEmpty()) {
            return "[]";
        }
        List<List<Number>> nested = new ArrayList<>(keypoints.size());
        for (KeypointPoint kp : keypoints) {
            nested.add(List.of(kp.x(), kp.y(), kp.v()));
        }
        try {
            return objectMapper.writeValueAsString(nested);
        } catch (Exception e) {
            // CWE-117/209: 원본 좌표 전문 미노출.
            throw new IllegalStateException("키포인트 직렬화 실패", e);
        }
    }

    /**
     * JSON 배열 문자열 {@code [[x,y,v], ...]} → KeypointPoint 리스트.
     * null/빈 입력은 빈 리스트.
     *
     * <p>형식 위반(원소가 배열 아님/크기≠3/숫자 아님)은 {@link IllegalArgumentException}(fail-secure).
     * 개수(=17)·가시성 범위 검증은 상위 Service 책임이며 여기서는 형식만 보장한다.
     */
    public static List<KeypointPoint> fromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            // CWE-117: 예외 메시지에 원본 JSON 전문 미노출.
            throw new IllegalArgumentException("키포인트 역직렬화 실패", e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("키포인트는 배열이어야 합니다");
        }
        List<KeypointPoint> result = new ArrayList<>(root.size());
        for (JsonNode triplet : root) {
            if (!triplet.isArray() || triplet.size() != TRIPLET_SIZE) {
                throw new IllegalArgumentException("키포인트는 [x, y, v] 형태여야 합니다");
            }
            JsonNode x = triplet.get(0);
            JsonNode y = triplet.get(1);
            JsonNode v = triplet.get(2);
            if (!x.isNumber() || !y.isNumber() || !v.isNumber()) {
                throw new IllegalArgumentException("키포인트 요소는 숫자여야 합니다");
            }
            result.add(new KeypointPoint(x.asDouble(), y.asDouble(), v.asInt()));
        }
        return result;
    }
}
