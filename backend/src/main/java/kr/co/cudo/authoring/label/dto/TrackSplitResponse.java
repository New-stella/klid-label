package kr.co.cudo.authoring.label.dto;

/**
 * R5 — 트랙 split(분할) 결과.
 *
 * @param rawSn           영상 PK
 * @param originalTrackId 분할 전(유지되는) 트랙 ID
 * @param newTrackId      분할로 새로 부여된 트랙 ID(영상 내 유니크 = max 정수 트랙ID + 1)
 * @param atFrameNo       분할 기준 프레임 번호
 * @param movedCount      새 트랙으로 이동된 원 키프레임 라벨 수(경계 밖이면 0)
 */
public record TrackSplitResponse(
        Long rawSn,
        String originalTrackId,
        String newTrackId,
        int atFrameNo,
        int movedCount
) {
}
