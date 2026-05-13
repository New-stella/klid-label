package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ai-server YOLO Track 추론 요청 — Phase 3.
 *
 * <p>정합 대상: {@code POST /infer/yolo/track}
 *  - image_b64       : base64 인코딩 이미지(jpeg/png)
 *  - clip_id         : 영상 식별자 (트래커 상태 격리 기준 — A 영상의 track_id 가 B 영상에 누수 방지)
 *  - frame_index     : 영상 내 프레임 순서 (0 부터 시작). 0 이면 트래커 상태 리셋, 그 외엔 {@code persist=True}.
 *  - conf_threshold  : 신뢰도 임계값 (0.0 ~ 1.0)
 *  - imgsz           : 추론 입력 해상도(px)
 *  - iou             : NMS IoU 임계값 (0.0 ~ 1.0)
 *
 * <p>BE 호출자는 {@code clipId = String.valueOf(rawSn)} 또는 video meta id 등 영상 고유값을 사용한다.
 * 본 record 는 Phase 3 단계에서는 정의만 추가되고, Phase 4 에서 {@link kr.co.cudo.authoring.batch.step.YoloAutolabelStep}
 * 가 실제 호출 경로로 전환한다.
 */
public record YoloTrackRequest(
        @JsonProperty("image_b64") String imageB64,
        @JsonProperty("clip_id") String clipId,
        @JsonProperty("frame_index") int frameIndex,
        @JsonProperty("conf_threshold") double confThreshold,
        @JsonProperty("imgsz") Integer imgsz,
        @JsonProperty("iou") Double iou
) {
}
