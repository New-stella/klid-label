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
  /**
   * R6(Phase 6-D): 이 메타의 검토행 PK(LS_DATA_META_REVIEW). 검토행이 없으면 null/undefined.
   * BE 승인/반려 API(/v1/meta/{metaReviewSn}/approve|reject) 의 경로 식별자이나,
   * FE 진입점은 없다(2026-08-03 확정) — 현재는 검수 화면 ReviewMetaPanel 의 식별 키로만 쓰인다.
   */
  dataMetaReviewSn?: number | null;
  /** 검토 상태(RVW_STTS_CD): AUTO_GENERATED/PENDING/APPROVED/REJECTED. 검토행 없으면 null. */
  reviewStatus?: string | null;
}

/**
 * 화면 표시용 메타 모델 (BE {@link MetaItem} 목록을 어댑터에서 변환).
 *
 * R7-2: BE 가 SoT 이므로 {@code items} 가 원본이며, {@code vlmText}/{@code stateChanges} 는
 * 어댑터가 안전 기본값과 함께 파생한다(items 0건이면 vlmText=''·stateChanges=[]).
 * imageUrl/frameNo 등은 BE 메타가 제공하지 않으므로 optional 이다 (없으면 화면에서 숨김).
 */
export interface FrameMeta {
  /**
   * 시계열 메타 K/V 목록 (round-trip 시 metaKey 보존용). 0건이면 빈 배열.
   *
   * 2026-08-03: 영상 기술메타({@code video.*})는 여기 포함되지 않는다 — {@link technicalMeta} 참조.
   */
  items: MetaItem[];
  /**
   * 영상 기술메타({@code video.fps}·{@code video.resolution} 등) — 읽기 전용 '영상 정보'.
   *
   * ffprobe/관제 인입이 채우고 BE {@code VideoMetaService} 가 소유하는 값이라 VLM 시계열 메타가
   * 아니다. 같은 테이블({@code LS_DATA_META})에 저장돼 한동안 '시계열 메타'로 잘못 표시됐다.
   * 편집 대상이 아니며(BE 가 수정 요청을 400 으로 거부) 화면에서도 읽기 전용으로만 노출한다.
   * 없으면 빈 배열.
   */
  technicalMeta: MetaItem[];
  /**
   * 화면 전용 <b>읽기 메타</b>(일치도 {@code vlm.accuracy} 등) — BE {@code MetaResponse.readOnlyMeta}.
   *
   * 외부 위탁이 산출한 참고값이라 사람이 산문으로 덮을 대상이 아니며, BE 가 수정 요청을 400 으로
   * 거부한다. 화면은 값만 보여준다. BE 가 fail-closed 로 항목을 늘릴 수 있으므로 미지의 키도
   * 일반적으로 렌더한다. 없으면 빈 배열. [req: R8]
   */
  readOnlyMeta: MetaItem[];
  /**
   * <b>이관 원문 메타</b> — BE {@code MetaResponse.importedMeta}. [design: API-066]
   *
   * 외부 산출물 이관이 저작도구 스키마에 착지할 컬럼이 없어 원문 그대로 보관한 값이다
   * (좌표·위치·카메라 설치 높이/방위/관리번호·데이터 출처·이벤트 기록·이벤트 상위 계층 이름·
   * 외부 영상 식별자·원천 축 개인정보 판정). 시계열 분석 결과가 아니므로 {@link items} 와 같은
   * 목록에 두지 않는다 — 섞이면 화면이 그것을 시계열 메타로 표시해 검토 대상이 아닌 값이
   * 검토 대상처럼 보인다.
   *
   * 편집 대상이 아니며 BE 가 수정 요청을 400 으로 거부한다. 이관으로 들어오지 않은 영상에서는
   * <b>빈 배열이 정상</b>이라 오류로 안내하지 않는다.
   *
   * ★분류의 소유자는 <b>서버</b>다 — 화면이 열쇠 접두({@code import.})를 파싱해 스스로 가르지
   * 않는다. 접두를 화면에 다시 선언하는 순간 두 번째 진실원이 된다.
   */
  importedMeta: MetaItem[];
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
