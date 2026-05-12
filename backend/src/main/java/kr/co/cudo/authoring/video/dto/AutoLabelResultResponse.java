package kr.co.cudo.authoring.video.dto;

import java.util.List;

/**
 * 영상별 오토라벨 결과 응답.
 * FE FrameLabels 인터페이스에 매핑된다 — videoId + objects 리스트.
 *
 * 라벨 색상/표시명 코드 테이블은 V1 시점 미존재 — labelCode 를 labelName 으로 fallback,
 * color 는 기본값(#3B82F6) 사용. 추후 LsPresetDtl 등 연계 시 확장.
 */
public record AutoLabelResultResponse(
        Long videoId,
        List<LabelObjectDto> objects
) {

    public record LabelObjectDto(
            String id,
            String labelCode,
            String labelName,
            String color,
            Double confidence,
            String createdBy
    ) {}
}
