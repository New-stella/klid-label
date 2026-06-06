// 오토라벨링 결과 + 시계열 메타 도메인 타입.
// V2.0 — VLM 시계열 메타: enum 기반 환경/이벤트 메타 → 자연어 vlmText 단일 필드로 개편.

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

export interface StateChange {
  /** 상태 변화 발생 frameNo */
  frameNo: number;
  fromState: string;
  toState: string;
  detectedAt: string; // ISO8601
}

/**
 * BE 실제 응답 항목 (SoT) — {@code GET /v1/frames/{srcSn}/meta} → {@code MetaResponse.Item}.
 * BE 는 영상(rawSn) 단위 시계열 K/V 목록을 반환한다 (metaKey=정렬키/프레임인덱스, metaVal=VLM 텍스트).
 * R7-2: 과거 FE 가 기대하던 {@code {vlmText, stateChanges, imageUrl, frameNo}} 형태는 BE 가 생성한 적이 없다.
 */
export interface MetaItem {
  metaSn: number;
  metaKey: string;
  metaVal: string;
}

/**
 * 화면 표시용 메타 모델 (BE {@link MetaItem} 목록을 어댑터에서 변환).
 *
 * R7-2: BE 가 SoT 이므로 {@code items} 가 원본이며, {@code vlmText}/{@code stateChanges} 는
 * 어댑터가 안전 기본값과 함께 파생한다(items 0건이면 vlmText=''·stateChanges=[]).
 * imageUrl/frameNo 등은 BE 메타가 제공하지 않으므로 optional 이다 (없으면 화면에서 숨김).
 */
export interface FrameMeta {
  /** BE 원본 K/V 목록 (round-trip 시 metaKey 보존용). 0건이면 빈 배열. */
  items: MetaItem[];
  srcSn?: number;
  frameNo?: number;
  imageUrl?: string;
  imageWidth?: number;
  imageHeight?: number;
  /** items 의 metaVal 을 metaKey 오름차순으로 결합한 VLM 시계열 텍스트 (없으면 ''). */
  vlmText: string;
  /** BE 메타에 상태변화 데이터가 없으므로 항상 [] (옵셔널 가드 — 크래시 방지). */
  stateChanges: StateChange[];
}

/**
 * 메타 수정 요청 — BE {@code MetaUpdateRequest} 와 정렬 ({@code items:[{metaKey, metaVal}]}).
 * BE 는 기존 metaKey 의 값만 수정 가능(key 추가/삭제 불가)하므로 원본 metaKey 를 그대로 보낸다.
 */
export interface FrameMetaUpdateRequest {
  items: Array<{ metaKey: string; metaVal: string }>;
}
