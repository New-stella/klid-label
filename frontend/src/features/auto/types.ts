// 오토라벨링 결과 + 시계열 메타 도메인 타입.
// V1.7 — VLM 객체 검증은 저작도구 책임, 환경/이벤트 메타는 외부 시스템 생성 → 검토·수정만.

export interface ConfidenceBucket {
  /** 90+ / 70-90 / under-70 */
  bucket: 'high' | 'mid' | 'low';
  count: number;
  ratio: number; // 0~1
}

export interface ClassDistribution {
  classId: number;
  className: string;
  count: number;
}

export interface LowConfidenceFrame {
  srcSn: number;
  frameNo: number;
  confidence: number; // 0~1
  thumbnailUrl: string;
}

export interface AutoLabelSummary {
  videoId: number;
  totalFrames: number;
  totalLabels: number;
  averageConfidence: number; // 0~1
  vlmVerifiedCount: number;
  vlmRejectedCount: number;
  buckets: ConfidenceBucket[];
  classDistribution: ClassDistribution[];
  lowConfidenceFrames: LowConfidenceFrame[];
}

export interface VlmVerification {
  /** YOLO/SAM2 객체별 VLM 재검증 결과 */
  objectId: number;
  className: string;
  yoloConfidence: number;
  vlmAgree: boolean;
  vlmReason?: string;
}

export interface EnvMeta {
  weather?: 'CLEAR' | 'RAIN' | 'SNOW' | 'CLOUDY' | 'FOG' | null;
  timeOfDay?: 'DAY' | 'NIGHT' | 'DAWN' | 'DUSK' | null;
  illumination?: 'LOW' | 'MID' | 'HIGH' | null;
}

export interface EventMeta {
  eventTypeCd?: string | null;
  intensity?: 'LOW' | 'MID' | 'HIGH' | null;
  description?: string | null;
}

export interface StateChange {
  /** 상태 변화 발생 frameNo */
  frameNo: number;
  fromState: string;
  toState: string;
  detectedAt: string; // ISO8601
}

export interface FrameMeta {
  srcSn: number;
  frameNo: number;
  imageUrl: string;
  imageWidth: number;
  imageHeight: number;
  vlmVerifications: VlmVerification[];
  envMeta: EnvMeta;
  eventMeta: EventMeta;
  stateChanges: StateChange[];
}

export interface FrameMetaUpdateRequest {
  envMeta: EnvMeta;
  eventMeta: EventMeta;
}
