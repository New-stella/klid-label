package kr.co.cudo.authoring.marking.event;

/**
 * 마킹 완료 이벤트.
 * MarkingService.create() 에서 마킹 저장 후 발행되며,
 * MarkingBatchBridge 가 수신하여 배치 파이프라인을 트리거한다.
 *
 * @param rawSn     영상 PK
 * @param markingSn 마킹 PK
 */
public record MarkingCompletedEvent(Long rawSn, Long markingSn) {}
