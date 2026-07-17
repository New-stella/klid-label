package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

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
 *  - classes         : (Phase 4) 검출 대상 클래스 라벨(COCO 영문명) 화이트리스트. null 이면 전체 검출(미필터).
 *                      null 시 {@code @JsonInclude(NON_NULL)} 로 직렬화에서 제외되어 ai-server 기본값(None=전체)과 정합.
 *
 * <p>BE 호출자는 {@code clipId = String.valueOf(rawSn)} 또는 video meta id 등 영상 고유값을 사용한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record YoloTrackRequest(
        @JsonProperty("image_b64") String imageB64,
        @JsonProperty("clip_id") String clipId,
        @JsonProperty("frame_index") int frameIndex,
        @JsonProperty("conf_threshold") double confThreshold,
        @JsonProperty("imgsz") Integer imgsz,
        @JsonProperty("iou") Double iou,
        @JsonProperty("classes") List<String> classes
) {

    /** 하위호환 — classes 미지정(전체 검출) 6-arg 생성자. 배치 경로 및 기존 호출자 유지. */
    public YoloTrackRequest(String imageB64, String clipId, int frameIndex,
                            double confThreshold, Integer imgsz, Double iou) {
        this(imageB64, clipId, frameIndex, confThreshold, imgsz, iou, null);
    }
}
