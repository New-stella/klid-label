package kr.co.cudo.authoring.batch.dto;

import java.time.LocalDateTime;

/**
 * 단일 영상의 배치 단계 진행도 (응답 DTO).
 *  - rawSn: LS_DATA_RAW PK
 *  - stage: BatchStage 의 name() (PENDING / MARKING / VLM / DEIDENTIFY / FRAME_EXTRACT / YOLO / SAM2 / INTERPOLATE / COMPLETED / FAILED)
 *  - startedAt: 최초 markStage 시각
 *  - lastUpdatedAt: 마지막 단계 전이 시각
 *  - retryCount: 재시도 누적 횟수
 *  - errorMessage: 실패 시 예외 클래스명 (스택트레이스/내부경로 미노출 — 보안)
 */
public record BatchStageProgress(
        Long rawSn,
        String stage,
        LocalDateTime startedAt,
        LocalDateTime lastUpdatedAt,
        Integer retryCount,
        String errorMessage
) {
}
