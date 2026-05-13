package kr.co.cudo.authoring.batch.orchestrator;

/**
 * 배치 파이프라인 단계 (Phase 5 + Phase 3 트랙 보간 추가).
 * PENDING → FRAME_EXTRACT → DEIDENTIFY (조건부) → YOLO → SAM2 → INTERPOLATE → VLM_VERIFY → COMPLETED
 *                                                                            ↘ 실패 시 어디서든 → FAILED
 */
public enum BatchStage {
    PENDING,
    FRAME_EXTRACT,
    DEIDENTIFY,
    YOLO,
    SAM2,
    /** Phase 3: 같은 trackId 의 누락 프레임 BBOX 를 선형 보간으로 채우는 단계. */
    INTERPOLATE,
    VLM_VERIFY,
    COMPLETED,
    FAILED
}
