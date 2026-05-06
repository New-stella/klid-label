package kr.co.cudo.authoring.batch.orchestrator;

/**
 * 배치 파이프라인 단계 (Phase 5).
 * PENDING → FRAME_EXTRACT → DEIDENTIFY (조건부) → YOLO → SAM2 → VLM_VERIFY → COMPLETED
 *                                                              ↘ 실패 시 어디서든 → FAILED
 */
public enum BatchStage {
    PENDING,
    FRAME_EXTRACT,
    DEIDENTIFY,
    YOLO,
    SAM2,
    VLM_VERIFY,
    COMPLETED,
    FAILED
}
