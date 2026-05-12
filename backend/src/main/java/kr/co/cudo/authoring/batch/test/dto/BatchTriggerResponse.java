package kr.co.cudo.authoring.batch.test.dto;

/**
 * 배치 수동 트리거 응답 DTO (개발/검수 전용).
 *
 * @param rawSn         처리 대상 영상 SN
 * @param finalStage    {@link kr.co.cudo.authoring.batch.orchestrator.BatchStage#name()} 또는 "FAILED"
 * @param success       파이프라인 성공 여부
 * @param durationMs    파이프라인 소요 시간 (ms)
 * @param errorMessage  실패 시 예외 메시지 (nullable)
 */
public record BatchTriggerResponse(
        Long rawSn,
        String finalStage,
        boolean success,
        long durationMs,
        String errorMessage
) {
}
