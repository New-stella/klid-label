// Phase 4 — YOLO 오토라벨 클래스 필터 대상 목록 (R3 AC3).
//
// ★ 클래스 식별자 매핑 (코드 검증 기반 확정):
// ai-server 는 detection.label(COCO 영문명) 기준으로 필터한다
// (app/routers/yolo.py `_apply_class_filter`, `Detection.label in set(req.classes)`).
// BE 는 이 영문 라벨을 그대로 ai-server 로 전달하고, 저장 시 라벨 마스터 name 과 대소문자 무시
// 매칭(`findLabelIdByName`)한다. 라벨 마스터(useLabelMasters)는 사용자가 생성하는 한글 명칭이라
// COCO 영문명과 1:1 매핑이 보장되지 않으므로, 필터 값(id)은 ai-server 가 실제로 판별하는
// COCO 영문명으로 고정한다. 표시 label 만 한글로 제공한다.
//
// 대상은 CCTV 관제 관련 이동체(사람/자전거/자동차/오토바이/버스/트럭 — CLAUDE.md YOLO 고정 클래스).

export interface YoloClass {
  /** ai-server 로 전송하는 COCO 영문 라벨(필터 값). */
  id: string;
  /** 팝업 표시용 한글 명칭. */
  label: string;
}

export const YOLO_CLASSES: readonly YoloClass[] = [
  { id: 'person', label: '사람' },
  { id: 'bicycle', label: '자전거' },
  { id: 'car', label: '자동차' },
  { id: 'motorcycle', label: '오토바이' },
  { id: 'bus', label: '버스' },
  { id: 'truck', label: '트럭' },
] as const;
