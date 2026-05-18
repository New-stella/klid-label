package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.label.dto.LabelResponse;

import java.util.List;

/**
 * SCR-REVIEW-002 검수 화면 — 프레임 단건 메타 + 라벨 묶음.
 *
 * @param srcSn    프레임 PK (LS_DATA_SRC.SRC_SN)
 * @param frameNo  프레임 번호 (0-base)
 * @param imageUrl 프레임 이미지 다운로드 URL — {@code /api/v1/videos/{rawSn}/frames/{frameNo}/image}
 *                 (server.servlet.context-path=/api 적용 — FE 는 absolute path 로 사용 가능)
 * @param labels   해당 프레임에 속한 라벨 목록 (auto + manual, lblSn 오름차순)
 */
public record FrameDetailResponse(
        Long srcSn,
        Integer frameNo,
        String imageUrl,
        List<LabelResponse.Item> labels
) {
}
