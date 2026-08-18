package kr.co.cudo.authoring.portal.dto;

import java.util.List;

/**
 * 포털 데이터마트 작업 데이터 ZIP 다운로드의 {@code {rawSn}/labels.json} 본문. @design API-203, AC-035
 *
 * <p>프레임마다 "본인 저장분이 있으면 그것, 없으면 데이터마트 원본"으로 확정된 라벨을 담는다 —
 * 판정은 {@code PortalLabelService.mergeFrameItems} 한 곳에서만 한다(여기서 재유도하지 않는다).
 * 따라서 이 문서에는 <b>호출자 본인의 저장분 외 다른 사용자의 작업 라벨이 들어가지 않는다</b>.
 *
 * <p>항목 shape 은 프레임 라벨 조회({@link PortalFrameLabelsResponse.Item})와 동일하다 — 같은 좌표
 * 계약이므로 사본 record 를 만들지 않는다.
 */
public record PortalDatamartDownloadResponse(
        Long rawSn,
        List<Frame> frames
) {

    /** 프레임 1건 + 그 프레임에 확정된 라벨 목록. */
    public record Frame(
            Long srcSn,
            Long frameNo,
            List<PortalFrameLabelsResponse.Item> labels
    ) {}
}
