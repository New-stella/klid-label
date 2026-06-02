package kr.co.cudo.authoring.batch.test.dto;

import java.util.List;

/**
 * 오토라벨링 테스트 트리거 응답 DTO.
 * 외부 의존(비식별/버전관리/VLM) 없이 YOLO → SAM2 만 실행한 결과.
 */
public record AutolabelRunResponse(
        Long rawSn,
        long framesFound,
        boolean frameExtracted,
        int yoloLabels,
        int sam2Labels,
        long durationMs,
        List<String> skipped
) {
}
