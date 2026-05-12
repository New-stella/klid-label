package kr.co.cudo.authoring.review.dto;

import java.util.List;

/**
 * SCR-REVIEW-002 검수 화면 — 영상(videoId=rawSn) 의 모든 프레임 + 라벨 일괄 응답.
 *
 * @param videoId     영상 PK (LS_DATA_RAW.RAW_SN)
 * @param totalFrames 프레임 총 개수 (frames.size() 와 동일)
 * @param frames      프레임 목록 (frameNo 오름차순)
 */
public record FrameListResponse(
        Long videoId,
        int totalFrames,
        List<FrameDetailResponse> frames
) {
}
