package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * V2.0 포털 사용자 라벨 저장 요청. 원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재.
 */
public record PortalUserLabelRequest(
        @NotNull(message = "sourceRawSn 은 필수입니다.") Long sourceRawSn,
        @NotNull(message = "sourceSrcSn 은 필수입니다.") Long sourceSrcSn,
        @NotBlank(message = "lblTypeCd 는 필수입니다.") @Size(max = 16) String lblTypeCd,
        @Size(max = 80) String label,
        // R17 이슈2 — points 누락 시 빈 라벨 row 가 생성되어 포털 로드/렌더 크래시를 유발.
        // @NotBlank 로 NULL/공백 저장을 1차 차단 (빈 좌표 JSON '[]' 는 서비스에서 추가 차단).
        // 3차 QA (CWE-770) — 길이 상한 추가. 아래 MAX_POINTS_LENGTH 주석 참조.
        @NotBlank(message = "points 는 필수입니다.")
        @Size(max = PortalUserLabelRequest.MAX_POINTS_LENGTH,
                message = "points 는 " + PortalUserLabelRequest.MAX_POINTS_LENGTH + "자 이하여야 합니다.")
        String points
) {

    /**
     * 좌표 JSON 문자열 최대 길이 (CWE-770).
     *
     * <p>{@code points} 는 타입 구조가 아닌 <b>문자열</b>로 받고 적재 컬럼
     * ({@code LS_PORTAL_USER_LABEL.POINT_CN})도 {@code TEXT} 라 상한이 어디에도 없었다.
     * 본문 크기 필터({@code PortalLabelBodySizeFilter})는 <b>파싱 전</b> 방어이고 이 상한은
     * <b>파싱 후 필드 단위</b> 방어라 서로 대체하지 않는다.
     *
     * <p>값 근거: 포털 라벨의 최대 좌표 수는 POLYGON 200 점
     * ({@code PortalUploadLabelService.POLYGON_MAX_POINTS})이고 좌표 1 점의 JSON 표현은 넉넉히
     * 잡아도 30자 내외라 정상 페이로드는 6KB 를 넘지 않는다. 64KB 는 그 10배 이상의 여유를 둔
     * 값으로, 정상 사용을 막지 않으면서 무제한 적재는 차단한다.
     *
     * <p>이 길이 상한은 <b>좌표 개수 상한을 대체하지 않는다</b> — 개수 상한(BBOX 2점 / POLYGON
     * 3~200점)은 {@code PortalLabelService.validatePointCount} 가 파싱 후 강제한다.
     */
    public static final int MAX_POINTS_LENGTH = 65_536;
}
