// 검수 도메인 타입 (BE OpenAPI alias)
//
// UI/UX §4-9 / §5.3 정합:
// - 상태 전이: REVIEW_PENDING → REVIEWING → COMPLETED / REJECTED → IN_PROGRESS(재작업)
// - 이슈는 프레임 단위로 누적 (캔버스 좌표 마커 사용 X)

export type ReviewStatus = 'REVIEW_PENDING' | 'REVIEWING' | 'COMPLETED' | 'REJECTED';

export interface Review {
  id: number;
  videoId: number;
  cctvName: string;
  workerId: number;
  workerName: string;
  submittedAt: string;
  labelCount: number;
  status: ReviewStatus;
  reviewerId?: number;
  // Phase 1 enrich — BE 가 EVNT_TYPE_CD 를 직접 응답 (null 가능)
  eventName?: string | null;
  eventTypeCd?: string | null;
}

export interface ReviewIssue {
  id: number;
  frameId: number;
  description: string;
  createdAt: string;
}

export interface ReviewListParams {
  page?: number;
  size?: number;
  sort?: string;
}

export interface AddIssueRequest {
  frameId: number;
  description: string;
}

export interface RejectRequest {
  reason: string;
}

export interface ApproveRequest {
  generalComment?: string;
}

// SCR-REVIEW-002 Phase 2 — 프레임/라벨 응답 타입 (BE FrameListResponse alias)
export type LabelType = 'BBOX' | 'POLYGON' | 'SEGMENT' | 'TRACK';

export interface LabelItem {
  id: number;
  lblTypeCd: LabelType;
  label: string;
  points: number[][]; // [[x,y], ...]
  autoLblYn: 'Y' | 'N';
  confScore: number | null;
}

export interface FrameDetail {
  srcSn: number;
  frameNo: number;
  imageUrl: string;
  labels: LabelItem[];
}

export interface FrameList {
  videoId: number;
  totalFrames: number;
  frames: FrameDetail[];
}
