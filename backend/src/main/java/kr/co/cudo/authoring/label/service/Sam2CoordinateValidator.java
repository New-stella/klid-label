package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.util.List;

/**
 * SAM2 좌표 입력/응답 검증 공용 유틸 (CWE-20).
 *
 * <p><b>왜 별도 유틸인가</b>: 검증 규칙이 {@link Sam2TrackService} 안에만 private 으로 있어
 * {@link Sam2SegmentService} 는 같은 규칙을 갖지 못했다. 그 결과 segment 경로는 잘못된 좌표를
 * 그대로 ai-server 로 보냈고, ai-server 가 낸 400 이 BE 에서 502(EXTERNAL_API_ERROR)로 승격돼
 * 사용자에게 "외부 시스템 오류"로 나갔다(실제 원인은 클라이언트 입력 오류). 규칙을 한 곳으로
 * 모아 두 경로가 갈라지지(drift) 않게 한다.
 *
 * <p>검증 규칙 — 좌표는 {@code [x, y]} 두 값이며 각 값은 <b>유한한 0 이상의 수</b>여야 한다.
 * 유한성 검사가 특히 중요하다: {@code NaN}/{@code Infinity} 는 JSON 으로 직렬화되지 않아
 * ai-server 스키마·응답 어디선가 조용히 {@code null} 로 바뀌어 흘러다닌다.
 */
public final class Sam2CoordinateValidator {

    private Sam2CoordinateValidator() {
        // 유틸 클래스 — 인스턴스화 금지
    }

    /**
     * 폴리곤/좌표 목록 검증 — 각 원소가 {@code [x, y]} 이고 모두 유한한 0 이상인지.
     *
     * @param polygon   검증 대상 (null/빈 값이면 위반)
     * @param fieldName 오류 메시지에 쓸 필드명
     */
    public static void validatePolygon(List<List<Double>> polygon, String fieldName) {
        if (polygon == null || polygon.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, fieldName + " 가 비어있습니다.");
        }
        for (List<Double> pair : polygon) {
            if (pair == null || pair.size() != 2) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        fieldName + " 좌표는 [x, y] 두 값이어야 합니다.");
            }
            requireFiniteNonNegative(pair.get(0), fieldName);
            requireFiniteNonNegative(pair.get(1), fieldName);
        }
    }

    /**
     * 평면 좌표 배열 검증 — {@code box = [x1, y1, x2, y2]} 처럼 고정 길이 수치 배열.
     *
     * @param coords         검증 대상 (null/빈 값이면 위반)
     * @param expectedLength 기대 길이
     * @param fieldName      오류 메시지에 쓸 필드명
     */
    public static void validateCoords(List<Double> coords, int expectedLength, String fieldName) {
        if (coords == null || coords.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, fieldName + " 가 비어있습니다.");
        }
        if (coords.size() != expectedLength) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    fieldName + " 좌표는 " + expectedLength + "개여야 합니다.");
        }
        for (Double v : coords) {
            requireFiniteNonNegative(v, fieldName);
        }
    }

    private static void requireFiniteNonNegative(Double value, String fieldName) {
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    fieldName + " 좌표는 유한한 0 이상의 수여야 합니다.");
        }
    }
}
