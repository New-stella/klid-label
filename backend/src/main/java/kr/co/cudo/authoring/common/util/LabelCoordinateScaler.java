package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 라벨 좌표(POINT_CN) 순수 스케일 유틸 — Phase 1 (해상도 파생영상).
 *
 * <p>원본 영상의 라벨을 저/고해상도 파생영상으로 복사할 때, 좌표를 해상도 비율로 스케일한다.
 * 축소({@code scale<1})와 확대({@code scale>1})를 동일 로직으로 처리한다. 외부 상태·DB·IO 없는
 * 순수 함수(정적 메서드)다.
 *
 * <h3>지원 포맷 (실제 저장 포맷)</h3>
 * {@link LabelPointSerializer}/{@link KeypointSerializer} 가 읽는 포맷과 동일하게 분기한다:
 * <ul>
 *   <li>정규 nested {@code [[x,y], ...]} — write-time 정규화 포맷</li>
 *   <li>객체 배열 {@code [{"x":..,"y":..}, ...]} — 레거시</li>
 *   <li>평탄 1차원 {@code [x1,y1,x2,y2, ...]} — 레거시/BBOX 4-요소</li>
 *   <li>SKELETON 삼중값 {@code [[x,y,v], ...]} — 17-keypoint. x·y 만 스케일, 가시성 {@code v} 는 불변</li>
 * </ul>
 * BBOX 는 대각/사각 코너 좌표로 저장되므로 코너 x 를 scaleX, y 를 scaleY 로 곱하면
 * 폭(w)·높이(h)가 자동으로 비율대로 스케일된다(별도 w/h 처리 불필요).
 *
 * <h3>정밀도/반올림</h3>
 * 좌표 원소의 JSON 숫자 타입을 보존한다:
 * <ul>
 *   <li>정수 픽셀 좌표(정수 노드) → {@code Math.round} 로 정수 유지(비정수 방지)</li>
 *   <li>실수 좌표(실수 노드) → 곱만 적용해 정밀도 보존</li>
 * </ul>
 * 결과가 음수가 되지 않는 것은 배율이 항상 양수임을 진입부 가드({@code scaleX>0 && scaleY>0})로
 * 강제하기 때문이다(원본 좌표가 음수가 아니라는 전제 하에). 다만 정수 BBOX 의 두 코너를 각각 독립
 * 반올림하므로 폭(w)/높이(h)에 최대 ±1px 오차가 발생할 수 있다(예: 코너 {@code x} 가 각각
 * {@code x0.5→+1}, {@code x1.4→+0} 으로 갈리는 경우). 실수 좌표는 이 오차가 없다.
 * 기존 {@link LabelPointSerializer#fromJson}(모든 값을 {@code double} 로 변환)을 경유하면 정수/실수
 * 구분과 원본 포맷이 소실되어 위 정밀도 규칙을 지킬 수 없으므로, 여기서는 JSON 노드 수준으로 스케일한다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>Jackson 안전 기본 모드만 사용(enableDefaultTyping/@JsonTypeInfo(use=CLASS) 금지 — CWE-502).</li>
 *   <li>기형/악의적 입력은 통제불가 크래시가 아닌 {@link IllegalArgumentException}(fail-secure)으로 거부.</li>
 *   <li>배율이 0/음수/비유한({@code NaN}/{@code Infinity})이면 진입부에서 거부해 비표준 JSON 토큰
 *       (예: {@code "NaN"}) 전파를 차단(CWE-20 fail-secure).</li>
 *   <li>예외 메시지에 좌표 원문·배율 원문을 노출하지 않는다(CWE-117/209).</li>
 * </ul>
 */
public final class LabelCoordinateScaler {

    /** SKELETON 라벨 타입 코드 (LsDataLbl.TYPE_SKELETON 과 동일 — util 계층 자립을 위해 로컬 상수). */
    private static final String LBL_TYPE_SKELETON = "SKELETON";

    private static final String OBJ_KEY_X = "x";
    private static final String OBJ_KEY_Y = "y";

    /** 유효 배율 하한(초과, 미포함) — 0/음수 배율은 fail-secure 로 거부(CWE-20). */
    private static final double MIN_SCALE_EXCLUSIVE = 0.0d;

    /** ObjectMapper 는 설정 후 스레드-세이프. 읽기/쓰기만 하므로 정적 인스턴스 재사용. */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private LabelCoordinateScaler() {
    }

    /**
     * POINT_CN 좌표를 {@code scaleX}(x축)·{@code scaleY}(y축) 비율로 스케일한 JSON 문자열을 반환한다.
     *
     * @param pointCn  원본 좌표 JSON. {@code null}/공백/{@code "[]"} 는 그대로 반환(예외 없음)
     * @param lblTypeCd 라벨 타입 코드. {@code SKELETON} 이면 삼중값 경로(가시성 불변)
     * @param scaleX   x축 배율(양수 필수 — 0/음수/비유한 시 예외)
     * @param scaleY   y축 배율(양수 필수 — 0/음수/비유한 시 예외)
     * @return 스케일된 좌표 JSON (입력 포맷/숫자 타입 보존). 입력이 {@code null} 이면 {@code null}
     * @throws IllegalArgumentException 배율이 0/음수/비유한이거나 기형 JSON/인식 불가 포맷
     *                                  (fail-secure, 원문 미노출)
     */
    public static String scalePointCn(String pointCn, String lblTypeCd, double scaleX, double scaleY) {
        return scalePointCn(pointCn, lblTypeCd, scaleX, scaleY, 0d, 0d);
    }

    /**
     * 좌표를 배율 + <b>오프셋</b>(레터박스 패딩 시작점)으로 변환한다 — {@code x' = x*scaleX + offsetX}.
     *
     * <p>G-1 — 종횡비 보존(레터박스) 리스케일에서는 이미지가 목표 프레임 안 {@code (offsetX, offsetY)} 에
     * 그려지므로, 라벨 좌표도 <b>단순 배율이 아니라</b> 오프셋을 함께 반영해야 그림 위에 정확히 얹힌다.
     * BBOX·POLYGON·세그멘테이션·키포인트(SKELETON) 전 종류에 동일하게 적용된다(가시성 {@code v} 는 불변).
     *
     * @param offsetX x축 오프셋(px, 음수 아님 — 유한 실수)
     * @param offsetY y축 오프셋(px, 음수 아님 — 유한 실수)
     */
    public static String scalePointCn(String pointCn, String lblTypeCd, double scaleX, double scaleY,
                                      double offsetX, double offsetY) {
        validateScale(scaleX, scaleY);
        validateOffset(offsetX, offsetY);
        if (pointCn == null) {
            return null;
        }
        String trimmed = pointCn.trim();
        if (trimmed.isEmpty() || "[]".equals(trimmed)) {
            return pointCn;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(pointCn);
        } catch (Exception e) {
            // CWE-117/209: 예외 메시지에 원본 JSON 전문 미노출.
            throw new IllegalArgumentException("좌표 역직렬화 실패");
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("좌표는 배열이어야 합니다");
        }
        if (root.isEmpty()) {
            return pointCn;
        }

        ArrayNode result;
        if (LBL_TYPE_SKELETON.equals(lblTypeCd)) {
            result = scaleTriplets(root, scaleX, scaleY, offsetX, offsetY);
        } else {
            JsonNode first = root.get(0);
            if (first.isArray()) {
                result = scaleNestedPairs(root, scaleX, scaleY, offsetX, offsetY);
            } else if (first.isObject()) {
                result = scaleObjectPairs(root, scaleX, scaleY, offsetX, offsetY);
            } else if (first.isNumber()) {
                result = scaleFlat(root, scaleX, scaleY, offsetX, offsetY);
            } else {
                throw new IllegalArgumentException("좌표 형식을 인식할 수 없습니다");
            }
        }

        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("좌표 직렬화 실패");
        }
    }

    /**
     * 배율 입력 가드(CWE-20 fail-secure). 0/음수/비유한({@code NaN}/{@code Infinity}) 배율은
     * NaN·Infinity 가 좌표에 전파돼 비표준 JSON 토큰을 생성하기 전에 거부한다.
     * 예외 메시지에 배율 원문을 노출하지 않는다(CWE-117/209).
     */
    private static void validateScale(double scaleX, double scaleY) {
        if (!isValidScale(scaleX) || !isValidScale(scaleY)) {
            throw new IllegalArgumentException("배율은 양의 유한 실수여야 합니다");
        }
    }

    /**
     * 오프셋 입력 가드(CWE-20 fail-secure) — 비유한/음수 오프셋은 좌표를 프레임 밖으로 밀어내거나
     * 비표준 JSON 토큰을 만든다. 원문은 노출하지 않는다.
     */
    private static void validateOffset(double offsetX, double offsetY) {
        if (!isValidOffset(offsetX) || !isValidOffset(offsetY)) {
            throw new IllegalArgumentException("오프셋은 0 이상의 유한 실수여야 합니다");
        }
    }

    private static boolean isValidOffset(double offset) {
        return Double.isFinite(offset) && offset >= 0d;
    }

    private static boolean isValidScale(double scale) {
        return Double.isFinite(scale) && scale > MIN_SCALE_EXCLUSIVE;
    }

    /** 정규 nested {@code [[x,y], ...]} — 각 쌍의 x 는 scaleX, y 는 scaleY. */
    private static ArrayNode scaleNestedPairs(JsonNode root, double scaleX, double scaleY,
                                              double offsetX, double offsetY) {
        ArrayNode out = NODES.arrayNode(root.size());
        for (JsonNode pair : root) {
            if (!pair.isArray() || pair.size() != 2) {
                throw new IllegalArgumentException("좌표는 [x, y] 형태여야 합니다");
            }
            ArrayNode scaledPair = NODES.arrayNode(2);
            scaledPair.add(scaleNumber(pair.get(0), scaleX, offsetX));
            scaledPair.add(scaleNumber(pair.get(1), scaleY, offsetY));
            out.add(scaledPair);
        }
        return out;
    }

    /** 객체 배열 {@code [{"x":..,"y":..}, ...]} — x/y 만 스케일, 그 외 필드 보존. */
    private static ArrayNode scaleObjectPairs(JsonNode root, double scaleX, double scaleY,
                                              double offsetX, double offsetY) {
        ArrayNode out = NODES.arrayNode(root.size());
        for (JsonNode obj : root) {
            if (!obj.isObject() || !obj.has(OBJ_KEY_X) || !obj.has(OBJ_KEY_Y)) {
                throw new IllegalArgumentException("좌표 객체는 {x,y} 형태여야 합니다");
            }
            ObjectNode scaledObj = NODES.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (OBJ_KEY_X.equals(key)) {
                    scaledObj.set(OBJ_KEY_X, scaleNumber(field.getValue(), scaleX, offsetX));
                } else if (OBJ_KEY_Y.equals(key)) {
                    scaledObj.set(OBJ_KEY_Y, scaleNumber(field.getValue(), scaleY, offsetY));
                } else {
                    scaledObj.set(key, field.getValue());
                }
            }
            out.add(scaledObj);
        }
        return out;
    }

    /** 평탄 1차원 {@code [x1,y1,x2,y2, ...]} — 짝수 인덱스 scaleX, 홀수 인덱스 scaleY. */
    private static ArrayNode scaleFlat(JsonNode root, double scaleX, double scaleY,
                                       double offsetX, double offsetY) {
        if (root.size() % 2 != 0) {
            throw new IllegalArgumentException("평탄 좌표 배열은 짝수 길이여야 합니다");
        }
        ArrayNode out = NODES.arrayNode(root.size());
        for (int i = 0; i < root.size(); i++) {
            boolean isX = (i % 2 == 0);
            out.add(scaleNumber(root.get(i), isX ? scaleX : scaleY, isX ? offsetX : offsetY));
        }
        return out;
    }

    /** SKELETON 삼중값 {@code [[x,y,v], ...]} — x/y 만 스케일, 가시성 v 는 그대로 보존. */
    private static ArrayNode scaleTriplets(JsonNode root, double scaleX, double scaleY,
                                           double offsetX, double offsetY) {
        ArrayNode out = NODES.arrayNode(root.size());
        for (JsonNode triplet : root) {
            if (!triplet.isArray() || triplet.size() != 3) {
                throw new IllegalArgumentException("키포인트는 [x, y, v] 형태여야 합니다");
            }
            ArrayNode scaled = NODES.arrayNode(3);
            scaled.add(scaleNumber(triplet.get(0), scaleX, offsetX));
            scaled.add(scaleNumber(triplet.get(1), scaleY, offsetY));
            JsonNode v = triplet.get(2);
            if (!v.isNumber()) {
                throw new IllegalArgumentException("키포인트 가시성은 숫자여야 합니다");
            }
            scaled.add(v);
            out.add(scaled);
        }
        return out;
    }

    /**
     * 좌표 숫자 한 개를 스케일한다. 정수 노드는 {@code Math.round} 로 정수 유지, 실수 노드는 정밀도 보존.
     */
    private static JsonNode scaleNumber(JsonNode node, double scale, double offset) {
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("좌표 요소는 숫자여야 합니다");
        }
        double transformed = node.asDouble() * scale + offset;
        if (node.isIntegralNumber()) {
            return NODES.numberNode(Math.round(transformed));
        }
        return NODES.numberNode(transformed);
    }
}
