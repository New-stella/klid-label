package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;

/**
 * 프레임 설명(NIA image.description) 조회/저장 응답.
 *
 * @param srcSn       프레임 PK
 * @param description 프레임 설명(자연어). 미입력/삭제 시 null.
 */
public record FrameDescriptionResponse(Long srcSn, String description) {

    public static FrameDescriptionResponse from(LsDataSrc src) {
        return new FrameDescriptionResponse(src.getSrcSn(), src.getFrmExpln());
    }
}
