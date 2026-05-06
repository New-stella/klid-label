package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;

import java.time.LocalDateTime;

public record ReviewResponse(
        Long pjtId,
        Long videoId,
        String dataSttsCd,
        Long version,
        LocalDateTime updDt
) {
    public static ReviewResponse from(LsPjtDataStts stts) {
        return new ReviewResponse(
                stts.getId().getPjtId(),
                stts.getId().getRawDataId(),
                stts.getDataSttsCd(),
                stts.getVersion(),
                stts.getUpdDt()
        );
    }
}
