package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;

import java.time.LocalDateTime;

/**
 * V107 포털 업로드 프레임 요약 응답 1행.
 *
 * <p>저장 경로(FILE_PATH_NM)는 노출하지 않는다(CWE-209) — 이미지 바이너리는
 * {@code GET /v1/portal/uploads/frames/{uldFrmeSn}/image} 로만 소유자 스코프 서빙한다.
 */
public record PortalUploadFrameResponse(
        Long uldFrmeSn,
        Long uldSn,
        Integer frmeNo,
        LocalDateTime regDt
) {

    public static PortalUploadFrameResponse from(LsPortalUldFrme frme) {
        return new PortalUploadFrameResponse(
                frme.getUldFrmeSn(),
                frme.getUldSn(),
                frme.getFrmeNo(),
                frme.getRegDt());
    }
}
