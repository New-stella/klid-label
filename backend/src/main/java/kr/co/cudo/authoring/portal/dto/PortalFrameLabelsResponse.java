package kr.co.cudo.authoring.portal.dto;

import java.util.List;

/**
 * R16 — 포털 프레임 라벨 Load 응답.
 *
 * <p>FE LabelingPage 가 내부 LabelsResponse 와 동일 shape 으로 소비할 수 있도록 정렬한다
 * ({@code frameNo / srcSn / videoId / siblings / labels}). 내부 전용 필드(frameImageType/lockSttsCd)는
 * 포털에서 의미 없으므로 미포함 — FE 가 'DEID'/null 로 고정 처리한다.
 *
 * <p>병합 정책: 본인 user-label 이 있으면 user-label 만, 없으면 datamart 원본 라벨을 초기값으로 반환한다.
 */
public record PortalFrameLabelsResponse(
        Integer frameNo,
        Long srcSn,
        Long videoId,
        List<Sibling> siblings,
        List<Item> labels
) {
    public record Sibling(Long srcSn, Integer frameNo) {}

    public record Item(
            Long id,
            String lblTypeCd,
            String label,
            List<List<Double>> points
    ) {}
}
