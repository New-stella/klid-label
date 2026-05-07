// 라벨 도메인 타입 (BE OpenAPI alias — Phase 6에서 본격 동기화)

export const LabelSource = {
  AUTO_YOLO: 'AUTO_YOLO',
  AUTO_SAM2: 'AUTO_SAM2',
  MANUAL: 'MANUAL',
} as const;
export type LabelSource = (typeof LabelSource)[keyof typeof LabelSource];

export const ShapeType = {
  BBOX: 'BBOX',
  POLYGON: 'POLYGON',
  MASK: 'MASK',
} as const;
export type ShapeType = (typeof ShapeType)[keyof typeof ShapeType];

/**
 * BBox: [left, top, right, bottom] (이미지 픽셀 좌표).
 */
export interface BBoxShape {
  type: 'BBOX';
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/**
 * Polygon: [x1,y1,x2,y2,...] (이미지 픽셀 좌표).
 */
export interface PolygonShape {
  type: 'POLYGON';
  points: number[];
}

/**
 * Mask: RLE 또는 base64 — Phase 6에서 본격 정의.
 */
export interface MaskShape {
  type: 'MASK';
  rle?: string;
  width?: number;
  height?: number;
}

export type Shape = BBoxShape | PolygonShape | MaskShape;

export interface Label {
  id: string; // 클라이언트 임시 ID 또는 BE 발급 ID
  serverId?: number; // BE 저장 후 부여
  frameNo: number;
  classId: number;
  className: string;
  source: LabelSource;
  confidence?: number; // 오토라벨 신뢰도 (0~1)
  shape: Shape;
  trackId?: number; // SAM2 Track 연속 객체 ID (Phase 6)
}

/**
 * 라벨링 캔버스의 활성 도구.
 * - SELECT: 선택/이동/리사이즈
 * - BBOX: 2점 드래그로 박스 그리기
 * - POLYGON: 클릭으로 점 추가, 더블클릭으로 완료
 * - PAN: 캔버스 팬
 * - TRACK: SAM2 자동 추적 (Phase 6)
 */
export const ToolType = {
  SELECT: 'SELECT',
  BBOX: 'BBOX',
  POLYGON: 'POLYGON',
  PAN: 'PAN',
  TRACK: 'TRACK',
  MASK_BRUSH: 'MASK_BRUSH',
  MASK_ERASER: 'MASK_ERASER',
} as const;
export type ToolType = (typeof ToolType)[keyof typeof ToolType];

export interface FrameSummary {
  frameNo: number;
  srcSn: number; // BE LS_DATA_RAW.SRC_SN
  thumbnailUrl: string;
  imageUrl: string;
  imageWidth: number;
  imageHeight: number;
}

export interface LabelsResponse {
  frameNo: number;
  srcSn: number;
  labels: Label[];
}
