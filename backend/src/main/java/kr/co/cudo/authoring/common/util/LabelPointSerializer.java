package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6 — LS_DATA_LBL.POINTS_JSON 직렬화 헬퍼.
 *
 * 정규형: [[x, y], [x, y], ...] 의 중첩 배열.
 * - JSON Injection 방어: Jackson 의 안전한 기본 모드만 사용.
 *   enableDefaultTyping / @JsonTypeInfo(use=CLASS) 등 다형성 역직렬화 미사용 (CWE-502).
 */
public final class LabelPointSerializer {

    private static final TypeReference<List<List<Double>>> LIST_OF_PAIRS = new TypeReference<>() {};

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
        List<List<Double>> nested;
        try {
            nested = objectMapper.readValue(json, LIST_OF_PAIRS);
        } catch (Exception e) {
            throw new IllegalArgumentException("좌표 역직렬화 실패: " + e.getMessage(), e);
        }
        List<Point> result = new ArrayList<>(nested.size());
        for (List<Double> pair : nested) {
            if (pair.size() != 2) {
                throw new IllegalArgumentException("좌표는 [x, y] 형태여야 합니다: " + pair);
            }
            result.add(new Point(pair.get(0), pair.get(1)));
        }
        return result;
    }
}
