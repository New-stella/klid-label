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
  KEYPOINT: 'KEYPOINT',
} as const;
export type ShapeType = (typeof ShapeType)[keyof typeof ShapeType];

/**
 * COCO-17 관절명 — BE `common/util/KeypointSkeleton.java` 의 KEYPOINT_NAMES 와
 * 값·순서 정확히 일치해야 한다(불일치 시 스켈레톤 렌더가 깨짐). 인덱스 0-based.
 */
export const KEYPOINT_NAMES = [
  'nose',
  'left_eye',
  'right_eye',
  'left_ear',
  'right_ear',
  'left_shoulder',
  'right_shoulder',
  'left_elbow',
  'right_elbow',
  'left_wrist',
  'right_wrist',
  'left_hip',
  'right_hip',
  'left_knee',
  'right_knee',
  'left_ankle',
  'right_ankle',
] as const;
export type KeypointName = (typeof KEYPOINT_NAMES)[number];

/**
 * COCO-17 스켈레톤 엣지 19쌍 — BE `KeypointSkeleton.SKELETON_EDGES` 와 일치.
 * 관절 번호는 **1-indexed**(keypoints 배열 접근 시 -1 필요).
 */
export const COCO_SKELETON = [
  [16, 14],
  [14, 12],
  [17, 15],
  [15, 13],
  [12, 13],
  [6, 12],
  [7, 13],
  [6, 7],
  [6, 8],
  [7, 9],
  [8, 10],
  [9, 11],
  [2, 3],
  [1, 2],
  [1, 3],
  [2, 4],
  [3, 5],
  [4, 6],
  [5, 7],
] as const;

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

/**
 * Keypoint(휴먼 포즈): COCO-17 관절을 삼중값 {x,y,v} 배열로 저장(정확히 17개).
 * - x,y: 이미지 픽셀 좌표
 * - v: 가시성 (0=미표기 / 1=비가시 / 2=가시). BE POINT_CN 삼중값과 1:1.
 * 인덱스 순서는 {@link KEYPOINT_NAMES}, 연결 토폴로지는 {@link COCO_SKELETON}(1-indexed).
 */
export interface KeypointShape {
  type: 'KEYPOINT';
  keypoints: { x: number; y: number; v: number }[];
}

export type Shape = BBoxShape | PolygonShape | MaskShape | KeypointShape;

export interface Label {
  id: string; // 클라이언트 임시 ID 또는 BE 발급 ID
  serverId?: number; // BE 저장 후 부여
  frameNo: number;
  classId: number;
  className: string;
  /**
   * 라벨 마스터 ID (BE LS_DATA_LBL.LABEL_ID, LS_LABEL.LABEL_ID 와 1:1).
   * - Phase 2 (V32) 이후 BE LabelResponse.Item.labelId 로 함께 응답
   * - null/undefined: V32 매칭 실패 또는 legacy row — fallback 색상 사용
   * - 캔버스 색상 lookup 의 키로 사용 (LabelsLayer → useLabelMasters)
   */
  labelId?: number | null;
  /**
   * 라벨 마스터 색상 (BE LabelResponse.Item.color = LS_LABEL.color, #RRGGBB).
   * - Phase 2 (V32) 이후 BE 가 enrichment 하여 응답에 포함
   * - null/undefined: V32 매칭 실패 — labelId 로 LabelMaster lookup → 그 외 fallback
   */
  color?: string | null;
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
 * - SAM_SEGMENT: SAM2 클릭/박스 분할 — 클릭(포인트)/드래그(박스)로 객체 지목 → BE 프록시 폴리곤 (Phase 4)
 * - KEYPOINT: COCO-17 휴먼 포즈 — 17점 순차 클릭 배치 후 커밋 (Phase 3)
 */
export const ToolType = {
  SELECT: 'SELECT',
  BBOX: 'BBOX',
  POLYGON: 'POLYGON',
  PAN: 'PAN',
  TRACK: 'TRACK',
  MASK_BRUSH: 'MASK_BRUSH',
  MASK_ERASER: 'MASK_ERASER',
  SAM_SEGMENT: 'SAM_SEGMENT',
  KEYPOINT: 'KEYPOINT',
} as const;
export type ToolType = (typeof ToolType)[keyof typeof ToolType];

/**
 * 도구 표시명(한글) 단일 출처 — 툴바 버튼/툴팁과 라벨 선택 모달 안내 문구가 공유한다.
 * 화면마다 다른 이름을 쓰면 같은 도구가 다르게 불린다.
 * ⚠ FE 문구에 YOLO/SAM2 같은 기술 모델명을 노출하지 않는다(AI 탐지/분할/추적으로 표기).
 */
export const TOOL_DISPLAY_NAME: Record<ToolType, string> = {
  [ToolType.SELECT]: '선택',
  [ToolType.BBOX]: '바운딩 박스',
  [ToolType.POLYGON]: '폴리곤',
  [ToolType.PAN]: '화면 이동',
  [ToolType.TRACK]: 'AI 추적',
  [ToolType.MASK_BRUSH]: '마스크 브러시',
  [ToolType.MASK_ERASER]: '마스크 지우개',
  [ToolType.SAM_SEGMENT]: 'AI 분할',
  [ToolType.KEYPOINT]: '스켈레톤',
};

/**
 * 포털 모드에서 제외되는 도구 목록.
 *
 * ADR-013 — 포털(외부 채널)은 **오토라벨링·SAM2·VLM·버전관리·검수 미제공**이다. 포털 사용자에게는
 * 수동 라벨링(BBOX/POLYGON)만 제공하므로 SAM2 분할(SAM_SEGMENT)·SAM2 자동추적(TRACK)·
 * 스켈레톤(KEYPOINT)을 숨긴다. 서버의 포털 전용 SAM2 엔드포인트(`/v1/portal/frames/**`)도 제거돼
 * 있으므로(BE `PortalSam2RemovedTest`) 도구가 노출되면 사용자는 404 만 만난다.
 *
 * ⚠ 이 목록은 **UX 게이팅이지 신뢰 경계가 아니다** — devtools 로 채널 상태를 조작해도 서버에
 * 엔드포인트가 없어 무의미하다는 것이 실제 강제 수단이다.
 *
 * ⚠ **내부(INTERNAL) 채널은 영향받지 않는다** — SAM2 분할/추적은 SFR-08-01(VOS) 핵심 기능이며
 * 이 목록은 `portalMode` 일 때만 적용된다.
 *
 * YOLO 파이프라인 오토라벨은 도구가 아닌 액션이라 ToolBar 의 독립 `portalHidden` 플래그로 숨긴다
 * (본 목록과 무관). 툴바 숨김(ToolBar)과 단축키 게이팅(useLabelingShortcuts)의 단일 정책 소스.
 */
export const PORTAL_HIDDEN_TOOLS: readonly ToolType[] = [
  ToolType.SAM_SEGMENT,
  ToolType.TRACK,
  ToolType.KEYPOINT,
];

export interface FrameSummary {
  frameNo: number;
  srcSn: number; // BE LS_DATA_RAW.SRC_SN
  thumbnailUrl: string;
  imageUrl: string;
  /**
   * 프레임 네이티브 픽셀 크기(선택). BE 가 제공하지 않으면 undefined.
   * 캔버스 렌더 geometry 는 이 값이 아니라 로드된 이미지의 naturalWidth/Height(실측)를 사용한다
   * — 하드코딩/부정확한 힌트로 인한 좌표 어긋남·이미지 스트레치를 방지하기 위함.
   * (붙여넣기 좌표 clamp 등 보조 용도로만 남겨둠. 미확정이면 clamp 는 하한 0 만 적용.)
   */
  imageWidth?: number;
  imageHeight?: number;
}

/**
 * 동일 영상 내 형제 프레임 식별자 (BE LabelResponse.siblings 와 1:1).
 * 라벨링 캔버스 하단 타임라인 및 이전/다음 프레임 이동에 사용.
 */
export interface SiblingFrame {
  srcSn: number;
  frameNo: number;
  /**
   * 해당 프레임(srcSn)에 저장된 라벨(LS_DATA_LBL)이 1건 이상 존재하는지 여부.
   * BE LabelResponse.SiblingFrame.hasLabel 과 1:1 — 프레임 strip SAVED(연두) 상태 판정용.
   * 레거시/미주입 응답은 undefined → false 취급.
   */
  hasLabel?: boolean;
}

/**
 * 프레임 이미지 타입 (BE 응답 frameImageType 과 1:1).
 * - DEID: 비식별 처리된 프레임 (WORKER 기본/REVIEWER 기본)
 * - RAW: 원본 프레임 (REVIEWER + ?raw=true)
 */
export const FrameImageType = {
  DEID: 'DEID',
  RAW: 'RAW',
} as const;
export type FrameImageType = (typeof FrameImageType)[keyof typeof FrameImageType];

/**
 * 영상 잠금 상태 코드 (BE 응답 lockSttsCd 와 1:1).
 * - LOCKED_FOR_REDEIDENT: 비식별 누락 신고로 인한 재처리 대기 잠금 상태.
 *   잠금 동안 라벨 수정/저장 불가.
 */
export const LockSttsCd = {
  LOCKED_FOR_REDEIDENT: 'LOCKED_FOR_REDEIDENT',
} as const;
export type LockSttsCd = (typeof LockSttsCd)[keyof typeof LockSttsCd];

export interface LabelsResponse {
  frameNo: number;
  srcSn: number;
  /** LS_DATA_RAW.RAW_SN — 현재 프레임이 속한 영상 PK */
  videoId?: number;
  /**
   * 현재 프레임 이미지의 타입 (DEID/RAW).
   * - WORKER: 항상 DEID
   * - REVIEWER + ?raw=true: RAW
   * - REVIEWER 기본: DEID
   */
  frameImageType?: FrameImageType;
  /**
   * 영상 잠금 상태 코드.
   * - 'LOCKED_FOR_REDEIDENT': 비식별 재처리 대기 — 라벨 수정/저장 불가
   * - null/undefined: 정상 (편집 가능)
   */
  lockSttsCd?: LockSttsCd | string | null;
  /**
   * 프레임 라벨셋 버전 (BE LabelResponse.labelVersion — C-ISSUE-21).
   *
   * 저장(PUT) 시 이 값을 그대로 실어 보내면, 그사이 다른 사용자가 같은 프레임을 저장한 경우 BE 가
   * 409 로 거부한다. 저장 계약이 full-replace 라 버전을 보내지 않으면 내 화면에 없던 남의 라벨이
   * 조용히 삭제된다. 버전 개념이 없는 응답 경로(포털 등)는 null/undefined.
   */
  labelVersion?: number | null;
  /** 동일 영상의 모든 프레임 (FRAME_NO ASC). 단일 프레임 응답에도 포함됨 */
  siblings: SiblingFrame[];
  labels: Label[];
}
