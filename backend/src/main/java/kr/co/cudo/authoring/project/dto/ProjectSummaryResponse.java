package kr.co.cudo.authoring.project.dto;

import kr.co.cudo.authoring.project.entity.LsPjt;

public record ProjectSummaryResponse(
        Long pjtId,
        String pjtNm,
        String pjtSttsCd
) {
    public static ProjectSummaryResponse from(LsPjt p) {
        return new ProjectSummaryResponse(p.getPjtId(), p.getPjtNm(), p.getPjtSttsCd());
    }
}
