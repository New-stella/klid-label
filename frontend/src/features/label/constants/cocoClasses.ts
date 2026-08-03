// COCO 80 검출 클래스 목록 — 라벨 마스터(LS_LABEL) COCO 매핑 select 옵션 소스.
//
// ★ 진실원은 BE(CocoClasses.LABELS / ai-server COCO_ID2LABEL, id 0~79). 이 상수는 라벨 관리
//   화면의 select 옵션 표시용이며, 값(id)은 BE allowlist 와 문자열이 정확히 일치해야 한다
//   (불일치 매핑은 BE 가 400 으로 거부). 표시 label 은 한글이 있으면 병기, 없으면 영문 그대로.
//
// AI 탐지 팝업의 후보 소스 역할은 이 상수가 아니라 BE 후보 조회 API(useDetectCandidates)가 담당한다.
// 이 상수는 "라벨↔COCO 매핑 지정" 입력 옵션에만 쓰인다.

export interface CocoClass {
  /** COCO 영문 클래스명 — BE 로 저장/전송하는 매핑 값. */
  id: string;
  /** select 표시용 라벨(한글 병기 우선, 없으면 영문). */
  label: string;
}

/**
 * CCTV 관제 관련 이동체 등 일부에 한글 병기(표시 편의). 없으면 영문 id 표시.
 *
 * ★ 라벨명 한글 우선 표시(2026-08-03 확정)의 사전 원본이기도 하다 — `utils/labelDisplayName`
 *   가 이 상수를 읽는다. 항목을 늘리는 것은 표시 정책 변경이므로 사용자 확인이 필요한 별건이다
 *   (현재 14건, 미등재 COCO 클래스·커스텀 라벨은 영문 그대로 표시되는 것이 정상 동작).
 */
export const COCO_LABEL_KO: Readonly<Record<string, string>> = {
  person: '사람',
  bicycle: '자전거',
  car: '자동차',
  motorcycle: '오토바이',
  bus: '버스',
  truck: '트럭',
  train: '기차',
  boat: '보트',
  'traffic light': '신호등',
  'fire hydrant': '소화전',
  'stop sign': '정지 표지판',
  bird: '새',
  cat: '고양이',
  dog: '개',
};

/** BE CocoClasses.LABELS 와 동일 순서·문자열(id 0~79). */
const COCO_IDS: readonly string[] = [
  'person', 'bicycle', 'car', 'motorcycle', 'airplane',
  'bus', 'train', 'truck', 'boat', 'traffic light',
  'fire hydrant', 'stop sign', 'parking meter', 'bench',
  'bird', 'cat', 'dog', 'horse', 'sheep', 'cow',
  'elephant', 'bear', 'zebra', 'giraffe', 'backpack',
  'umbrella', 'handbag', 'tie', 'suitcase', 'frisbee',
  'skis', 'snowboard', 'sports ball', 'kite',
  'baseball bat', 'baseball glove', 'skateboard', 'surfboard',
  'tennis racket', 'bottle', 'wine glass', 'cup', 'fork',
  'knife', 'spoon', 'bowl', 'banana', 'apple',
  'sandwich', 'orange', 'broccoli', 'carrot', 'hot dog',
  'pizza', 'donut', 'cake', 'chair', 'couch',
  'potted plant', 'bed', 'dining table', 'toilet', 'tv',
  'laptop', 'mouse', 'remote', 'keyboard', 'cell phone',
  'microwave', 'oven', 'toaster', 'sink', 'refrigerator',
  'book', 'clock', 'vase', 'scissors', 'teddy bear',
  'hair drier', 'toothbrush',
] as const;

export const COCO_CLASSES: readonly CocoClass[] = COCO_IDS.map((id) => ({
  id,
  label: COCO_LABEL_KO[id] ? `${COCO_LABEL_KO[id]} (${id})` : id,
}));

/** 매핑 값이 COCO allowlist 에 포함되는지(FE 사전판단 — 최종 판정은 BE). */
export function isCocoClass(id: string | null | undefined): boolean {
  if (id == null) return false;
  return COCO_IDS.includes(id.trim());
}
