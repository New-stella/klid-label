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

/**
 * 오토라벨 요약 응답.
 *
 * hotfix(W-6): BE 는 V1.7 placeholder 응답을 반환할 수 있다
 *  ({@code GET /v1/videos/{rawSn}/auto-summary} — VideoController.autoSummary).
 *
 * 두 가지 응답 형태 모두 안전하게 처리할 수 있도록 placeholder 전용 필드를 optional 로 추가하고,
 * 풀 응답 전용 필드(buckets/classDistribution/...) 도 optional 로 노출한다. 화면 측은
 * {@code buckets} 또는 {@code classDistribution} 의 존재 여부로 placeholder 인지 분기한다.
 *
 *  - placeholder 응답: {@code { videoId, yoloObjectCount, sam2TrackCount, vlmVerifiedCount, metaCount, status:"PENDING", message }}
 *  - 풀 응답:           {@code { videoId, totalFrames, totalLabels, averageConfidence, buckets, classDistribution, lowConfidenceFrames, ... }}
 */
export interface AutoLabelSummary {
  videoId: number;
  // 풀 응답 (BE 미구현 시 누락)
  totalFrames?: number;
  totalLabels?: number;
  averageConfidence?: number; // 0~1
  vlmVerifiedCount?: number;
  vlmRejectedCount?: number;
  buckets?: ConfidenceBucket[];
  classDistribution?: ClassDistribution[];
  lowConfidenceFrames?: LowConfidenceFrame[];
  // placeholder 응답 (V1.7 외부 메타 연동 전)
  yoloObjectCount?: number;
  sam2TrackCount?: number;
  metaCount?: number;
  /** "PENDING" — 외부 시계열 메타 추출 시스템 연동 전 placeholder 표식 */
  status?: 'PENDING' | string;
  /** 사용자 노출 메시지 (placeholder 일 때만 채워짐) */
  message?: string;
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
