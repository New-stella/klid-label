package kr.co.cudo.authoring.video.event;

/**
 * 영상 적재 완료 이벤트 (Phase 2 — 배치 파이프라인 재정렬).
 *
 * <p>{@code LS_DATA_RAW} 영속 직후 발행되어 <b>선두 비식별</b>을 트리거한다. 적재된 모든 영상은
 * (ANONY 포함) 무조건 비식별 대상이며, {@code IngestDeidentifyBridge} 가 본 이벤트를
 * {@code AFTER_COMMIT} 으로 수신해 {@code AsyncDeidentifyRunner} 로 비동기 비식별을 시작한다.
 *
 * <p>흐름: 적재 PENDING → (자동 비식별 성공) MARKING_READY → (마킹완료→배치) COMPLETED.
 *
 * @param rawSn 적재된 영상 PK (LS_DATA_RAW.RAW_SN)
 */
public record VideoIngestedEvent(Long rawSn) {
}
