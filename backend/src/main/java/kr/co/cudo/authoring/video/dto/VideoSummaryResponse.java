package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.time.LocalDateTime;

public record VideoSummaryResponse(
        Long rawSn,
        String vmsClipId,
        String vmsCctvId,
        String evntTypeCd,
        String prvcTypeCd,
        String prvcYn,
        String dataSttsCd,
        Integer durationSec,
        LocalDateTime regDt
) {
    public static VideoSummaryResponse from(LsDataRaw e) {
        return new VideoSummaryResponse(
                e.getRawSn(),
                e.getVmsClipId(),
                e.getVmsCctvId(),
                e.getEvntTypeCd(),
                e.getPrvcTypeCd(),
                e.getPrvcYn(),
                e.getDataSttsCd(),
                e.getDurationSec(),
                e.getRegDt()
        );
    }
}
