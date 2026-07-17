package kr.co.cudo.authoring.label.dto;

/**
 * R4 — 트랙 삭제 결과. 지정 프레임({@code fromFrameNo}) 이후 해당 트랙의 라벨을 전부 삭제한다.
 *
 * @param rawSn        영상 PK
 * @param trackId      삭제 대상 트랙 ID
 * @param fromFrameNo  삭제 시작 프레임 번호(이 값 이상 프레임만 삭제, 이전 프레임은 불변)
 * @param deletedCount 실제 삭제된 라벨 수(범위 밖이면 0)
 */
public record TrackDeleteResponse(
        Long rawSn,
        String trackId,
        int fromFrameNo,
        int deletedCount
) {
}
