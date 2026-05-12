package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6 — LS_DATA_LBL.POINTS_JSON 직렬화 헬퍼.
 *
 * <p>정규(write) 포맷: {@code [[x, y], [x, y], ...]}.
 *
 * <p>읽기 시 다음 레거시/외부 포맷도 정규형으로 자동 변환하여 호환한다:
 * <ul>
 *   <li>{@code [[x, y], ...]} — 정규</li>
 *   <li>{@code [{"x":..,"y":..}, ...]} — 객체 배열 (오토라벨 batch 일부)</li>
 *   <li>{@code [x1, y1, x2, y2, ...]} — 평탄 1차원 (짝수 길이, BBOX 4-요소 포함)</li>
 * </ul>
 *
 * <p>JSON Injection 방어: Jackson 의 안전한 기본 모드만 사용.
 * enableDefaultTyping / @JsonTypeInfo(use=CLASS) 등 다형성 역직렬화 미사용 (CWE-502).
 */
public final class LabelPointSerializer {

    private LabelPointSerializer() {}

    /** Point 리스트 → JSON 배열 문자열. null 입력은 "[]". */
    public static String toJson(List<Point> points, ObjectMapper objectMapper) {
        if (points == null || points.isEmpty()) {
            return "[]";
        }
        List<List<Double>> nested = new ArrayList<>(points.size());
        for (Point p : points) {
            nested.add(List.of(p.x(), p.y()));
        }
        try {
            return objectMapper.writeValueAsString(nested);
        } catch (Exception e) {
            throw new IllegalStateException("좌표 직렬화 실패", e);
        }
    }

    /** JSON 배열 문자열 → Point 리스트. null/빈 입력은 빈 리스트. */
    public static List<Point> fromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("좌표 역직렬화 실패: " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("좌표는 배열이어야 합니다: " + json);
        }
        if (root.isEmpty()) {
            return List.of();
        }
        JsonNode first = root.get(0);

        List<Point> result = new ArrayList<>();
        // 정규형 [[x,y], ...]
        if (first.isArray()) {
            for (JsonNode pair : root) {
                if (!pair.isArray() || pair.size() != 2) {
                    throw new IllegalArgumentException("좌표는 [x, y] 형태여야 합니다: " + pair);
                }
                result.add(new Point(pair.get(0).asDouble(), pair.get(1).asDouble()));
            }
            return result;
        }
        // 객체 배열 [{"x":..,"y":..}, ...]
        if (first.isObject() && first.has("x") && first.has("y")) {
            for (JsonNode obj : root) {
                if (!obj.isObject() || !obj.has("x") || !obj.has("y")) {
                    throw new IllegalArgumentException("좌표 객체는 {x,y} 형태여야 합니다: " + obj);
                }
                result.add(new Point(obj.get("x").asDouble(), obj.get("y").asDouble()));
            }
            return result;
        }
        // 평탄 1차원 [x1,y1,x2,y2,...]
        if (first.isNumber()) {
            if (root.size() % 2 != 0) {
                throw new IllegalArgumentException("평탄 좌표 배열은 짝수 길이여야 합니다: " + root.size());
            }
            for (int i = 0; i < root.size(); i += 2) {
                JsonNode x = root.get(i);
                JsonNode y = root.get(i + 1);
                if (!x.isNumber() || !y.isNumber()) {
                    throw new IllegalArgumentException("평탄 좌표 요소는 숫자여야 합니다");
                }
                result.add(new Point(x.asDouble(), y.asDouble()));
            }
            return result;
        }
        throw new IllegalArgumentException("좌표 형식을 인식할 수 없습니다: " + json);
    }
}
