package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;

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

    /**
     * 공용 프레임 원장 행 → 포털 응답. 창구·화면의 이름({@code uldFrmeSn}·{@code uldSn})은 흡수 뒤에도
     * 그대로 둔다 — 개명하면 창구·화면·이벤트 계약이 동시에 깨지는데 얻는 동작이 없다.
     *
     * <p>프레임 순번은 원장이 더 넓은 자료형을 쓴다. 포털 응답 계약이 정수라 <b>좁혀서</b> 싣는다 —
     * 포털 영상 1건의 프레임 수는 상한(수천)이 있어 넘칠 수 없다.
     */
    public static PortalUploadFrameResponse from(LsDataSrc frme) {
        return new PortalUploadFrameResponse(
                frme.getSrcSn(),
                frme.getRawSn(),
                frme.getFrameNo() == null ? null : frme.getFrameNo().intValue(),
                frme.getRegDt());
    }
}
