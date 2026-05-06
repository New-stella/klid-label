package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

public record VideoIngestResponse(
        Long rawSn,
        String vmsClipId,
        String prvcYn,
        String dataSttsCd,
        Long queSn,
        boolean created
) {
    public static VideoIngestResponse of(LsDataRaw raw, Long queSn, boolean created) {
        return new VideoIngestResponse(
                raw.getRawSn(),
                raw.getVmsClipId(),
                raw.getPrvcYn(),
                raw.getDataSttsCd(),
                queSn,
                created
        );
    }
}
