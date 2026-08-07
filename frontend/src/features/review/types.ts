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
  /**
   * Phase 7b — 검수 승인 이후 라벨/메타가 수정되어 재검토가 필요한가(BE V177 REVLT_YN).
   * `true` 면 이미 승인(COMPLETED)된 영상이라도 다시 확인 후 재승인해야 한다 — 필터·정렬
   * 축이 아니라 **표시 전용**이다(목록에 새 축을 만들지 않는다).
   */
  needsRecheck: boolean;
}

export interface ReviewIssue {
  id: number;
  frameId: number;
  description: string;
  createdAt: string;
}

/**
 * BE `GET /v1/reviews` 가 수용하는 **검수 상태 코드**.
 *
 * ★ FE {@link ReviewStatus} 와 **다른 값**이다(응답은 FE 코드, 요청은 BE 코드).
 * 화이트리스트 밖 값은 400 이 아니라 **빈 결과 200** 이라 오타가 "검수요청이 0건" 으로 위장된다 —
 * 역매핑은 `api.ts` 의 {@link REVIEW_STATUS_TO_BE} 한 곳에서만 한다.
 */
export type ReviewStatusParam = 'PENDING' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED';

/**
 * `GET /v1/reviews` 쿼리 파라미터.
 *
 * `status` 는 **FE 코드**로 담고 전송 직전 `api.ts` 가 BE 코드로 역매핑한다 — 화면·URL·쿼리키가
 * 모두 한 가지 표기(FE 코드)만 쓰게 해서 두 표기가 섞이는 것을 막는다.
 * 빈 값은 키째 생략한다(`compactParams`).
 */
export interface ReviewListParams {
  page?: number;
  size?: number;
  /** `"{key},{dir}"` — BE allowlist(submittedAt|updDt|videoId|status) 밖이면 조용히 기본 정렬 폴백. */
  sort?: string;
  /** 영상명·작업자명 부분일치 (BE `@Size(max=100)`). */
  q?: string;
  /** FE 상태 코드. 전송 시 BE 코드로 역매핑된다. */
  status?: ReviewStatus;
}

/**
 * `GET /v1/reviews/summary` 파라미터 — **`q` 전용**.
 *
 * ★ {@link ReviewListParams} 에서 파생시키지 않는다. 파생하면 `status` 가 optional 로 새어 들어와
 * "이미 status 로 좁혀진 집합" 위에서 세게 되고, 그러면 카드 하나만 값을 갖는다(BE 도 무시하지만
 * 타입 단계에서 애초에 넣을 수 없게 막는다).
 */
export interface ReviewSummaryParams {
  q?: string;
}

/**
 * `GET /v1/reviews/summary` 응답 — **필터 결과 전체 기준** 집계(현재 페이지가 아니다).
 *
 * 불변식: `total === pending + inReview + approved + rejected`.
 * 필드명은 BE 코드축(PENDING/IN_REVIEW/APPROVED/REJECTED)을 따른다.
 */
export interface ReviewSummary {
  total: number;
  pending: number;
  inReview: number;
  approved: number;
  rejected: number;
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
  /**
   * D-ISSUE-04 / H6 — 라벨이 1건도 없는 영상(negative sample)임을 검수자가 <b>명시적으로 확인</b>했는가.
   *
   * BE 는 라벨 0건 영상의 승인을 기본 409 로 차단한다(빈 스냅샷·빈 export 방지). 객체가 실제로 없는
   * 정상 영상까지 막으면 검수자가 더미 라벨을 넣도록 유도되므로, 확인한 경우에만 통과시킨다.
   * <b>상시 전송 금지</b>: 라벨이 있는 영상에 true 를 보내면 BE 가 400 으로 거부한다. 이 값은 409 를
   * 받은 뒤 사용자가 확인 다이얼로그에서 동의했을 때만 1회 실린다.
   */
  noLabelConfirmed?: boolean;
}

// ─────────────────────────────────────────────────────────────────
// Phase 2 — 이슈 스레드 (검수자↔작업자 양방향 소통)
// BE: IssueThreadResponse / IssueCommentResponse 1:1 미러.
// 상수는 as const (enum 금지 — frontend-coding-style).
// ─────────────────────────────────────────────────────────────────

export const ISSUE_TYPE = {
  REJECTION: 'REJECTION',
  INQUIRY: 'INQUIRY',
} as const;
export type IssueType = (typeof ISSUE_TYPE)[keyof typeof ISSUE_TYPE];

export const ISSUE_STATUS = {
  OPEN: 'OPEN',
  ANSWERED: 'ANSWERED',
  RESOLVED: 'RESOLVED',
} as const;
export type IssueStatus = (typeof ISSUE_STATUS)[keyof typeof ISSUE_STATUS];

/** BE IssueCommentResponse 미러. */
export interface IssueComment {
  commentSn: number;
  authorNo: string;
  /** 작성자 이름. 사용자 마스터에 없거나 사번이 숫자가 아니면 null — 화면은 사번으로 폴백한다. */
  authorName?: string | null;
  authorRoleCd: string;
  content: string;
  regDt: string;
}

/** BE IssueThreadResponse 미러. comments 는 REG_DT asc. */
export interface IssueThread {
  issueSn: number;
  issueTypeCd: IssueType;
  issueSttsCd: IssueStatus;
  srcSn: number | null;
  reason: string;
  reportedUserNo: string | null;
  /** 스레드 작성자 이름. 미해석 시 null — 화면은 사번으로 폴백한다. */
  reportedUserName?: string | null;
  regDt: string;
  comments: IssueComment[];
}

/** 문의(INQUIRY) 등록 요청 — BE POST /videos/{rawSn}/issues. */
export interface CreateInquiryRequest {
  content: string;
  srcSn?: number;
}

/** 댓글 추가 요청 — BE POST /issues/{issueSn}/comments. */
export interface AddIssueCommentRequest {
  content: string;
}

// SCR-REVIEW-002 Phase 2 — 프레임/라벨 응답 타입 (BE FrameListResponse alias)
/**
 * 라벨 형태 코드 — BE `LsDataLbl.TYPE_*`.
 *
 * `SKELETON` 은 COCO-17 키포인트 포즈다. 이 값이 유니온에 없던 동안 `LabelCanvas` 의
 * `switch` 가 `default` 로 빠져 **키포인트 라벨이 검수 화면에서 통째로 보이지 않았고**,
 * 색상 판정 어댑터도 `LabelItem` 대신 구조 타입으로 우회해야 했다. 값을 빼지 말 것 —
 * 빼면 `ObjectListPanel` 의 `Record<LabelType, string>` 뱃지 표가 컴파일 단계에서 막는다.
 */
export type LabelType = 'BBOX' | 'POLYGON' | 'SEGMENT' | 'TRACK' | 'SKELETON';

/**
 * 검수 화면의 라벨 1건 — BE `LabelResponse.Item` 미러(검수 응답 `FrameDetailResponse.labels` 가
 * 라벨링과 **같은 DTO** 를 쓴다).
 *
 * ★아래 마스터/트랙 필드는 **런타임에 실제로 실려 오는 값**이다. 선언이 없던 동안 화면이 이 값을
 *  직접 읽지 못해 어댑터·캐스팅으로 우회했고, 라벨 표시 색상이 마스터가 아닌 폴백으로 떨어졌다.
 *
 * - `labelId`  : 라벨 마스터(LS_LABEL) PK. **null 일 수 있다** — 마스터에 연결되지 않은 라벨이
 *                실재하며(BE `Item.from` 이 `lsLabel == null` 이면 세 값을 모두 null 로 둔다),
 *                non-null 로 단정하면 색상·라벨명 판정이 잘못된 가정 위에 서게 된다.
 * - `labelName`: 마스터 라벨명. 마스터 미연결이면 null → 화면은 `label`(LS_DATA_LBL 텍스트) 폴백.
 * - `color`    : 마스터 색상(COLR_VL). 미연결이면 null. 검수 조회 경로는 마스터 맵 없이 빌드해
 *                실제로 **항상 null** 이며, 색은 `labelId` 로 마스터를 lookup 해 정한다.
 * - `trackId`  : 트랙 식별자. 트랙에 속하지 않은 라벨은 null.
 * - `lblSrcCd` : 라벨 생성 출처 코드. 수동 라벨(AI 정보 행 없음)이면 null.
 *
 * 세 축 모두 **optional + nullable** 인 것은 BE 가 값을 안 주기 때문이 아니라, 값이 없을 때
 * `null` 로 실려 오고 컨텍스트 없는 레거시 빌드 경로는 키 자체가 빠질 수 있기 때문이다.
 */
export interface LabelItem {
  id: number;
  lblTypeCd: LabelType;
  label: string;
  points: number[][]; // [[x,y], ...] — SKELETON 은 17×[x,y,v] 삼중값
  autoLblYn: 'Y' | 'N';
  confScore: number | null;
  labelId?: number | null;
  labelName?: string | null;
  color?: string | null;
  trackId?: string | null;
  lblSrcCd?: string | null;
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
