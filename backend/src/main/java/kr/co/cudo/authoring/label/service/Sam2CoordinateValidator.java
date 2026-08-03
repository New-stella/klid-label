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
 *
 * <p><b>요청 축 / 응답 축 분리 (Critical)</b>: 기본 규칙({@link #validatePolygon})에는
 * <b>최소 정점 수를 넣지 않는다</b> — 이 규칙은 요청 검증에도 쓰이며 SAM2 클릭 프롬프트
 * ({@code Sam2SegmentService} 의 {@code points})는 <b>1 점이 정상 입력</b>이기 때문이다.
 * 폐곡선을 요구하는 것은 <b>ai-server 가 돌려준 폴리곤</b>뿐이므로 그 규칙은
 * {@link #validateResponseMinPoints} 로 분리하고 위반 시 {@link ErrorCode#EXTERNAL_API_ERROR}(502)
 * 를 낸다(클라이언트가 잘못 보낸 것이 아니라 외부 시스템이 잘못 준 것).
 */
public final class Sam2CoordinateValidator {

    /** 폐곡선 폴리곤 최소 정점 수 — 응답 폴리곤에만 적용(요청 클릭 프롬프트는 1 점이 정상). */
    public static final int MIN_POLYGON_POINTS = 3;

    private Sam2CoordinateValidator() {
        // 유틸 클래스 — 인스턴스화 금지
    }

    /**
     * <b>외부(ai-server) 응답</b> 폴리곤 최소 정점 수 검증 (CWE-20) — 위반 시 502.
     *
     * <p>퇴화 폴리곤(1~2 점)은 면적이 없어 라벨로 성립하지 않는데, 단순화(Douglas-Peucker)는
     * 결과가 3 점 미만이면 <b>원본을 그대로 반환</b>하므로 뒤에서 걸러지지 않는다. 여기서 막지
     * 않으면 퇴화 폴리곤이 응답에 실려 클라이언트가 라벨로 저장한다.
     *
     * <p><b>요청 좌표에는 쓰지 말 것</b> — SAM2 클릭 프롬프트는 1 점이 정상이다.
     *
     * @param polygon   ai-server 가 반환한 폴리곤
     * @param fieldName 오류 메시지에 쓸 필드명
     */
    public static void validateResponseMinPoints(List<List<Double>> polygon, String fieldName) {
        if (polygon == null || polygon.size() < MIN_POLYGON_POINTS) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    fieldName + " 정점이 " + MIN_POLYGON_POINTS + "개 미만입니다.");
        }
    }

    /**
     * 폴리곤/좌표 목록 검증 — 각 원소가 {@code [x, y]} 이고 모두 유한한 0 이상인지.
     *
     * <p><b>최소 정점 수는 검사하지 않는다</b>(요청 클릭 프롬프트 1 점 허용). 응답 폴리곤의
     * 폐곡선 요건은 {@link #validateResponseMinPoints} 가 담당한다.
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
