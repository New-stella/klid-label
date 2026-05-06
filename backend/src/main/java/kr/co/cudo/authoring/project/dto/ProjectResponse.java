package kr.co.cudo.authoring.project.dto;

import kr.co.cudo.authoring.project.entity.LsPjt;

import java.time.LocalDateTime;

public record ProjectResponse(
        Long pjtId,
        String pjtNm,
        String pjtDesc,
        String pjtSttsCd,
        Long regUserNo,
        LocalDateTime regDt
) {
    public static ProjectResponse from(LsPjt p) {
        return new ProjectResponse(
                p.getPjtId(),
                p.getPjtNm(),
                p.getPjtDesc(),
                p.getPjtSttsCd(),
                p.getRegUserNo(),
                p.getRegDt()
        );
    }
}
