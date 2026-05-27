package kr.co.cudo.authoring.batch.orchestrator;

/**
 * 배치 파이프라인 단계 (V2 — 파이프라인 재배치).
 * <p>V2 순서 (Phase 3 갱신):
 * PENDING → MARKING (마킹 확인) → VLM (영상 단위 메타) → DEIDENTIFY (조건부) → FRAME_EXTRACT → YOLO → SAM2 → INTERPOLATE → COMPLETED
 *                                                                                  ↘ 실패 시 어디서든 → FAILED
 */
public enum BatchStage {
    PENDING,
    /** Phase 3 신규: 마킹 데이터 확인 단계 (VLM 이전). */
    MARKING,
    /** V2 Phase 1 신규: 영상 단위 VLM 메타 추출 단계. */
    VLM,
    DEIDENTIFY,
    FRAME_EXTRACT,
    YOLO,
    SAM2,
    /** 같은 trackId 의 누락 프레임 BBOX 를 선형 보간으로 채우는 단계. */
    INTERPOLATE,
    COMPLETED,
    FAILED
}
