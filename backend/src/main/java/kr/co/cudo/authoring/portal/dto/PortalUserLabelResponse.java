package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;

import java.time.LocalDateTime;

/**
 * 포털 사용자 작업 라벨 1건 — 저장 성공 응답과 본인 라벨 목록 조회가 <b>같은 형태</b>로 쓴다.
 *
 * <p>{@code labelId}·{@code trackId} 는 <b>요청에 실려 온 값이 아니라 실제로 적재된 값</b>이다.
 * 활성 라벨 마스터에 실재하지 않는 참조는 그 값만 비워서 저장하므로 그 경우 응답에도 비어 있다 —
 * 호출측이 "요청값이 그대로 돌아온다"고 읽으면 안 된다.
 *
 * @design API-082, API-083
 */
public record PortalUserLabelResponse(
        Long userLblSn,
        Long sourceRawSn,
        Long sourceSrcSn,
        String lblTypeCd,
        String label,
        String points,
        Long labelId,
        String trackId,
        LocalDateTime createdAt
) {
    public static PortalUserLabelResponse from(LsPortalUserLabel e) {
        return new PortalUserLabelResponse(
                e.getUserLblSn(), e.getSrcRawSn(), e.getSrcDataSrcSn(),
                e.getLblTypeCd(), e.getLabelNm(), e.getPointCn(),
                e.getLabelId(), e.getTrackId(), e.getRegDt());
    }
}
