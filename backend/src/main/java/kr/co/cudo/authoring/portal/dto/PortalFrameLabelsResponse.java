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
 *
 * @design API-110
 */
public record PortalFrameLabelsResponse(
        Integer frameNo,
        Long srcSn,
        Long videoId,
        List<Sibling> siblings,
        List<Item> labels
) {
    public record Sibling(Long srcSn, Integer frameNo) {}

    /**
     * 라벨 1건.
     *
     * <p>{@code labelId}·{@code trackId} 는 <b>화면에 표시하는 값이 아니다</b> — 데이터마트 원본이
     * 갖고 있던 마스터 연결·트랙 연결이 저장 왕복에서 끊기지 않도록 화면이 그대로 되돌려 보내는
     * 값이다. 이 응답이 두 값을 버리면 화면이 되돌려 보낼 값 자체가 없어져, 사용자가 그 프레임을
     * 수정해 저장하는 순간 연결이 끊긴다.
     *
     * <p>둘 다 비어 있을 수 있으며, <b>본인 저장분에서 왔든 데이터마트 원본에서 왔든 같은 형태</b>로
     * 담는다(화면이 출처로 분기하지 않게).
     */
    public record Item(
            Long id,
            String lblTypeCd,
            String label,
            List<List<Double>> points,
            Long labelId,
            String trackId
    ) {}
}
