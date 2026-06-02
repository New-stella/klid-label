package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 2 — 라벨 좌표 스케일 유틸 (RESOLUTION 증강 지원).
 *
 * <p>RESOLUTION(해상도 변경) 증강 시 원본 영상을 단일 배율(factor)로 리사이즈하면서
 * 라벨 좌표(절대 픽셀)를 동일 factor 로 스케일한다. 종횡비 보존 배율이므로
 * scaleX == scaleY == factor 인 단일 팩터다 (RQ-SFR-07-02).
 *
 * <p>POINT_CN 포맷은 LBL_TYPE_CD 가 아니라 <b>JSON 구조</b>로 판별한다. BBOX 는 출처에 따라
 * flat/nested 두 포맷이 실재하기 때문이다:
 * <ul>
 *   <li>flat 배열 {@code [x1,y1,x2,y2]} — YoloAutolabelStep(자동 BBOX) 출력</li>
 *   <li>nested 배열 {@code [[x,y],[x,y],...]} — LabelService/LabelPointSerializer(수동 BBOX)
 *       및 POLYGON/SEGMENT/TRACK 출력</li>
 * </ul>
 * 루트 배열의 첫 요소가 숫자면 flat, 배열이면 nested 로 라우팅하여 입력과 동일한 포맷으로
 * 재직렬화한다(flat→flat, nested→nested). 감지 패턴은 TrackInterpolationStep.parseBbox 와 동일.
 * 따라서 {@code lblTypeCd} 는 본 계산에 영향을 주지 않으며 호환 위해 시그니처만 유지한다.
 *
 * <p>빈 배열 {@code []} 은 미검출(좌표 없음)의 정상 입력으로 간주하여 그대로 {@code []} 를
 * 반환한다(예외 아님).
 *
 * <p>스케일 후 모든 좌표는 {@code [0,maxW] × [0,maxH]} 안으로 클램프된다.
 *
 * <p>보안:
 * <ul>
 *   <li>CWE-20 입력 검증 — 배열 구조/길이/숫자 타입 검증. 깨진 입력은 fail-safe(예외).</li>
 *   <li>CWE-502 역직렬화 — Jackson 안전 모드(readTree)만 사용. enableDefaultTyping /
 *       {@code @JsonTypeInfo(use=CLASS)} 등 다형성 역직렬화 미사용.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LabelCoordinateScaler {

    private final ObjectMapper objectMapper;

    /**
     * 라벨 좌표 JSON 을 factor 로 스케일하고 클램프하여 동일 포맷으로 재직렬화한다.
     *
     * @param pointCnJson POINT_CN 원본 JSON (절대 픽셀 좌표)
     * @param lblTypeCd   호환용 라벨 타입 코드. 포맷 판별은 JSON 구조로 하므로 미사용.
     * @param factor      종횡비 보존 단일 배율 (scaleX==scaleY)
     * @param maxW        타겟 가로 (x 클램프 상한)
     * @param maxH        타겟 세로 (y 클램프 상한)
     * @return 스케일·클램프된 좌표 JSON (입력과 동일 포맷). 빈 배열 입력은 {@code []} 반환.
     * @throws CustomException 입력이 null/빈문자/파싱 불가/구조 위반일 때 (ErrorCode.INVALID_INPUT)
     */
    public String scale(String pointCnJson, String lblTypeCd, double factor, int maxW, int maxH) {
        if (pointCnJson == null || pointCnJson.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "POINT_CN 이 비어 있습니다.");
        }

        JsonNode root = parse(pointCnJson);
        if (!root.isArray()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 배열이어야 합니다.");
        }

        ArrayNode arr = (ArrayNode) root;
        // 빈 배열은 미검출(좌표 없음)의 정상 입력 — 그대로 [] 반환.
        if (arr.isEmpty()) {
            return serialize(arr);
        }

        // 포맷 자동 감지: 첫 요소가 숫자면 flat, 배열이면 nested.
        // (TrackInterpolationStep.parseBbox 와 동일한 감지 패턴 — BBOX flat/nested 이원화 대응.)
        JsonNode first = arr.get(0);
        JsonNode scaled = first.isNumber()
                ? scaleFlat(arr, factor, maxW, maxH)
                : scaleNested(arr, factor, maxW, maxH);

        return serialize(scaled);
    }

    private ArrayNode scaleFlat(ArrayNode flat, double factor, int maxW, int maxH) {
        if (flat.size() % 2 != 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "BBOX flat 좌표 배열은 짝수 길이여야 합니다: " + flat.size());
        }
        ArrayNode out = objectMapper.createArrayNode();
        for (int i = 0; i < flat.size(); i += 2) {
            JsonNode x = flat.get(i);
            JsonNode y = flat.get(i + 1);
            if (!x.isNumber() || !y.isNumber()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "좌표 요소는 숫자여야 합니다.");
            }
            out.add(scaleAxis(x.asDouble(), factor, maxW));
            out.add(scaleAxis(y.asDouble(), factor, maxH));
        }
        return out;
    }

    private ArrayNode scaleNested(ArrayNode nested, double factor, int maxW, int maxH) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode pair : nested) {
            if (!pair.isArray() || pair.size() != 2) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "좌표는 [x, y] 형태여야 합니다.");
            }
            JsonNode x = pair.get(0);
            JsonNode y = pair.get(1);
            if (!x.isNumber() || !y.isNumber()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "좌표 요소는 숫자여야 합니다.");
            }
            ArrayNode point = objectMapper.createArrayNode();
            point.add(scaleAxis(x.asDouble(), factor, maxW));
            point.add(scaleAxis(y.asDouble(), factor, maxH));
            out.add(point);
        }
        return out;
    }

    /** 한 축 좌표를 factor 로 스케일 → 반올림 → [0, max] 클램프. */
    private long scaleAxis(double value, double factor, int max) {
        long scaled = Math.round(value * factor);
        if (scaled < 0) {
            return 0;
        }
        return Math.min(scaled, max);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "POINT_CN JSON 파싱에 실패했습니다.", e);
        }
    }

    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "좌표 직렬화에 실패했습니다.", e);
        }
    }
}
