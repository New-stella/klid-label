package kr.co.cudo.authoring.batch.orchestrator;

/**
 * 배치 파이프라인 단계 (V2 — 파이프라인 재배치).
 * <p>V2 순서 (Phase 3 갱신):
 * PENDING → MARKING (마킹 확인) → VLM (영상 단위 메타) → DEIDENTIFY (조건부) → FRAME_EXTRACT → YOLO → SAM2 → INTERPOLATE → COMPLETED
 *                                                                                  ↘ 실패 시 어디서든 → FAILED
 * <p>VLM_VERIFY 는 기존 객체 검증 step 보존을 위해 enum 에 남겨두나, BatchOrchestrator 의 호출 시퀀스에는 더 이상 포함되지 않는다.
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
    /** @deprecated V2 에서 BatchOrchestrator 호출 제거. VlmObjectVerifyStep 코드는 보존 (Phase 5 cleanup). */
    @Deprecated
    VLM_VERIFY,
    COMPLETED,
    FAILED
}
