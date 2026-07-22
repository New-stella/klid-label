package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * SAM2 클릭/박스 분할 요청 (Phase 4 — RQ-SFR-08-02).
 *
 * <p>사용자가 캔버스에서 클릭(포인트) 또는 드래그(박스)로 객체를 지목하면 BE 가 ai-server
 * {@code POST /infer/sam2/segment} 로 프록시하여 폴리곤 + 신뢰도를 받는다.
 *
 * <ul>
 *   <li>srcSn  : 대상 프레임 PK (path 와 일치해야 함 — CWE-345 는 컨트롤러에서 검증)</li>
 *   <li>points : [[x, y], ...] 클릭 좌표. 클릭 프롬프트일 때만 사용 (CWE-770 상한 100)</li>
 *   <li>box    : [x1, y1, x2, y2] 박스 좌표. 박스 프롬프트일 때만 사용</li>
 * </ul>
 *
 * <p>입력 검증(CWE-20): points/box 는 <b>정확히 하나만</b> 제공해야 한다. 둘 다 비거나 둘 다
 * 존재하면 400. (ai-server 스키마는 수정하지 않고 BE 에서 배타 차단한다.)
 *
 * <p><b>경계 세밀함 per-request override(FEAT-007)</b>: {@code simplifyTolerance}(px)는 <b>선택적</b>이다.
 * null(미지정)이면 서비스가 시스템설정({@code POLYGON_SIMPLIFY_TOLERANCE})→상수 폴백을 그대로 사용한다(무회귀).
 * 지정 시 해당 요청에 한해 응답 폴리곤 단순화 epsilon 으로 사용한다. 범위 0.0~50.0 은 {@code @DecimalMin/@DecimalMax}
 * 로 컨트롤러 {@code @Valid} 단계에서 검증(CWE-20).
 */
public record Sam2SegmentRequest(
        @NotNull Long srcSn,
        @Size(max = 100) List<List<Double>> points,
        @Size(min = 4, max = 4) List<Double> box,
        @DecimalMin(value = "0.0", message = "경계 세밀함은 0.0 이상이어야 합니다.")
        @DecimalMax(value = "50.0", message = "경계 세밀함은 50.0 이하여야 합니다.")
        Double simplifyTolerance
) {

    /** 하위호환 — simplifyTolerance 미지정(시스템설정 사용) 3-arg 생성자. */
    public Sam2SegmentRequest(Long srcSn, List<List<Double>> points, List<Double> box) {
        this(srcSn, points, box, null);
    }

    /** points/box 배타 검증 — 둘 다 null/빈값이거나 둘 다 존재하면 위반(400). */
    @AssertTrue(message = "points 또는 box 중 정확히 하나만 제공해야 합니다.")
    public boolean isExactlyOnePrompt() {
        boolean hasPoints = points != null && !points.isEmpty();
        boolean hasBox = box != null && !box.isEmpty();
        return hasPoints ^ hasBox;
    }
}
