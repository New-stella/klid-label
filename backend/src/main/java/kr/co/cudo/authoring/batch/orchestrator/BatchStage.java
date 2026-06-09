package kr.co.cudo.authoring.batch.orchestrator;

/**
 * 배치 파이프라인 단계 (V2.1 — 비식별 선두 이동 반영).
 * <p>post-marking 시퀀스 순서:
 * PENDING → MARKING (마킹 확인) → VLM (영상 단위 메타) → FRAME_EXTRACT → YOLO → SAM2 → INTERPOLATE → COMPLETED
 *                                                                          ↘ 실패 시 어디서든 → FAILED
 * <p>DEIDENTIFY 는 post-marking 시퀀스에서 제거됐다. 비식별은 적재 직후 "선두" 단계로 분리되며
 * (Phase 2), 본 enum 의 DEIDENTIFY 상수는 선두 비식별 단계 / 로그 / dev 토글 등에서 재사용하기
 * 위해 유지한다 — 삭제하지 말 것.
 */
public enum BatchStage {
    PENDING,
    /** Phase 3 신규: 마킹 데이터 확인 단계 (VLM 이전). */
    MARKING,
    /** V2 Phase 1 신규: 영상 단위 VLM 메타 추출 단계. */
    VLM,
    /** 적재 직후 선두 비식별 단계 (Phase 2). post-marking 시퀀스에서는 사용하지 않음. */
    DEIDENTIFY,
    FRAME_EXTRACT,
    YOLO,
    SAM2,
    /** 같은 trackId 의 누락 프레임 BBOX 를 선형 보간으로 채우는 단계. */
    INTERPOLATE,
    COMPLETED,
    FAILED
}
