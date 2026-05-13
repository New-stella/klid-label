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
 * 라벨 원천 코드 — BE LS_DATA_LBL.LBL_SRC_CD 와 1:1.
 * - INTERPOLATED: TrackInterpolationStep 이 같은 trackId 의 detection 키프레임 사이를 선형 보간하여 생성한 BBOX (Phase 4)
 * - null/undefined: 일반 detection (YOLO/SAM2/MANUAL) — 외곽선 실선
 */
export const LabelSrcCd = {
  INTERPOLATED: 'INTERPOLATED',
} as const;
export type LabelSrcCd = (typeof LabelSrcCd)[keyof typeof LabelSrcCd];

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
  /**
   * 트래커 부여 객체 ID (BE LS_DATA_LBL.TRACK_ID, VARCHAR(64) nullable).
   * - Phase 5: YOLO BoT-SORT track 결과 (문자열로 직렬화된 정수)
   * - 동일 trackId → 라벨 패널/캔버스에서 같은 색상으로 시각화 (utils/trackColor)
   * - null/undefined 가능 (저신뢰 detection / 수동 라벨 / legacy row)
   */
  trackId?: string | null;
  /**
   * 라벨 원천 코드 (BE LS_DATA_LBL.LBL_SRC_CD, VARCHAR nullable).
   * - 'INTERPOLATED': TrackInterpolationStep 보간 결과 (Phase 4) — 캔버스 점선 + 트리 🔗 아이콘
   * - null/undefined: 일반 detection (실선 + 🤖/✏️)
   */
  lblSrcCd?: LabelSrcCd | null;
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

/**
 * 동일 영상 내 형제 프레임 식별자 (BE LabelResponse.siblings 와 1:1).
 * 라벨링 캔버스 하단 타임라인 및 이전/다음 프레임 이동에 사용.
 */
export interface SiblingFrame {
  srcSn: number;
  frameNo: number;
}

export interface LabelsResponse {
  frameNo: number;
  srcSn: number;
  /** LS_DATA_RAW.RAW_SN — 현재 프레임이 속한 영상 PK */
  videoId?: number;
  /** 동일 영상의 모든 프레임 (FRAME_NO ASC). 단일 프레임 응답에도 포함됨 */
  siblings: SiblingFrame[];
  labels: Label[];
}
