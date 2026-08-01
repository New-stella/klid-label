import type { ResolutionPreset } from '@/features/video/types';

// 증강 도메인 타입 (BE OpenAPI alias) — UI/UX §4-12 정합.
//
// 활용 결정 상태:
// - PENDING: 채택/거부 액션 노출
// - ACCEPTED: 결정 일시 표시 (변경 불가)
// - REJECTED: 거부 사유 표시 (변경 불가)

// 외부 증강 위탁 3종(WINTER/NIGHT/RAIN)만 — 해상도(RESOLUTION)는 증강이 아니라
// 저작도구가 직접 수행하는 별도 기능이므로 증강 유형에서 제외한다(CLAUDE.md SFR-06-03).
// 해상도 변경은 데이터 증강 화면(/augment)의 해상도 변경 패널에서 별도 섹션으로 제공된다.
export const AugmentType = {
  WINTER: 'WINTER',
  NIGHT: 'NIGHT',
  RAIN: 'RAIN',
} as const;
export type AugmentType = (typeof AugmentType)[keyof typeof AugmentType];

// 통합 처리 종류 — 증강 화면(SCR-AUG-001)의 단일 선택 카드 모델.
// 증강 3종(WINTER/NIGHT/RAIN)에 더해 해상도 변경(RESOLUTION)을 같은 카드 그리드에서
// 라디오(단일 선택)로 고른다. RESOLUTION 은 증강 잡 경로가 아닌 저작도구 직접 수행
// 기능(SFR-06-03)이므로, 실행 분기는 isAugmentKind 타입가드로 좁혀 처리한다(Phase 2).
export const PROCESS_KINDS = ['WINTER', 'NIGHT', 'RAIN', 'RESOLUTION'] as const;
export type ProcessKind = (typeof PROCESS_KINDS)[number];

export const PROCESS_KIND_LABEL: Record<ProcessKind, string> = {
  WINTER: '겨울',
  NIGHT: '야간',
  RAIN: '우천',
  RESOLUTION: '해상도 변경',
};

export const PROCESS_KIND_ICON: Record<ProcessKind, string> = {
  WINTER: '❄️',
  NIGHT: '🌙',
  RAIN: '🌧',
  RESOLUTION: '🖼️',
};

export const PROCESS_KIND_DESCRIPTION: Record<ProcessKind, string> = {
  WINTER: '눈/설경 효과로 영상을 변환합니다.',
  NIGHT: '저조도 야간 환경으로 영상을 변환합니다.',
  RAIN: '강우 효과로 영상을 변환합니다.',
  RESOLUTION: '표준 하위 해상도 이미지셋으로 다운스케일합니다.',
};

/**
 * 증강(외부 위탁) 종류인지 좁히는 타입가드 — AugmentType 값 집합 기반 positive 검사.
 * 부정 조건(RESOLUTION 제외)이 아니라 화이트리스트로 판정해 PROCESS_KINDS 확장 시
 * 새 비-증강 종류가 증강으로 오분기되는 것을 막는다.
 */
const AUGMENT_KIND_SET = new Set<string>(Object.values(AugmentType));
export const isAugmentKind = (k: ProcessKind): k is AugmentType =>
  AUGMENT_KIND_SET.has(k);

export const AugmentJobStatus = {
  REQUESTED: 'REQUESTED',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
} as const;
export type AugmentJobStatus = (typeof AugmentJobStatus)[keyof typeof AugmentJobStatus];

export const AugmentDecision = {
  PENDING: 'PENDING',
  ACCEPTED: 'ACCEPTED',
  REJECTED: 'REJECTED',
  /**
   * 사용자 취소로 종결된 항목(BE `LS_DATA_AUG.AUG_PROC_STTS_CD=CANCELED`).
   *
   * 잡 카드 집계(`AugmentJobStatus` 4값)는 CANCELED 를 "종료"로 세어 **COMPLETED** 를 주고
   * BE 는 그 enum 을 확장하지 않기로 확정했다. 따라서 "취소가 완료로 보이는" 문제는
   * **표시 축(FE)** 에서 이 항목 상태로 보정한다.
   */
  CANCELED: 'CANCELED',
} as const;
export type AugmentDecision = (typeof AugmentDecision)[keyof typeof AugmentDecision];

/** 활용 결정 상태의 사용자 노출 문구 — 화면 어디서나 같은 단어를 쓴다. */
export const AUGMENT_DECISION_LABEL: Record<AugmentDecision, string> = {
  PENDING: '활용 결정 대기',
  ACCEPTED: '채택됨',
  REJECTED: '거부됨',
  CANCELED: '취소됨',
};

/** 증강 잡 카드 데이터 (이력 그리드용) */
export interface AugmentJob {
  jobId: number;
  videoId: number;
  cctvName: string;
  types: AugmentType[];
  // 해상도 파생(SFR-06-03) 코드 목록 (BE additive 응답 필드 resolutionTypes, RESL_*).
  // 증강 위탁 잡이 아니라 저작도구 직접 수행 결과이므로 types 와 별도로 노출한다.
  // 구 응답에는 없을 수 있어 optional — 없으면 빈 목록으로 취급한다.
  resolutionTypes?: string[];
  status: AugmentJobStatus;
  requestedAt: string;
  completedAt?: string;
  videoCount: number;
}

/**
 * 결과 화면의 유형 코드 — 외부 위탁 증강 3종 + 해상도 파생 3종(RESL_*, SFR-06-03).
 *
 * 해상도 파생은 "증강 요청" 대상이 아니므로 `AugmentType`(요청 union)은 넓히지 않는다.
 * 결과 응답에서만 등장하는 코드이므로 결과 전용 union 으로 분리한다.
 */
export type AugmentResultType = AugmentType | ResolutionPreset;

/** 해상도 파생 코드 접두 — BE `AUG_TYPE_CD` 의 `RESL_*` 규약. */
export const RESOLUTION_TYPE_PREFIX = 'RESL_';

/**
 * 해상도 파생(내부 생성물)인가.
 * 외부 위탁 증강과 달리 검수(채택/반려)·진행상태 조회 대상이 아니다(BE 는 진행상태에 400).
 */
export const isResolutionDerivativeType = (type: string | null | undefined): boolean =>
  typeof type === 'string' && type.startsWith(RESOLUTION_TYPE_PREFIX);

/**
 * 결과물 상태 — **`framePairs` 가 비어 있는 "이유"** 축 (BE `AugmentResultItemResponse.STATE_*`).
 *
 * 프레임 쌍 개수만으로는 성격이 전혀 다른 상태들이 구분되지 않아(생성 중 / 반입 중 / 신고 보류 /
 * 영구 실패 / 취소 / 실삭제) 화면이 전부 한 문구로 뭉갰다. 이 축이 그 구분의 정본이다.
 *
 * ⚠ **사람의 결정 축(`AugmentDecision`)과 다른 축이다.** 생성 실패와 사람의 반려는 둘 다
 * `decision=REJECTED` 로 내려온다 — 그 둘을 가르는 것은 이 값이다.
 * ⚠ BE 가 값을 추가할 수 있으므로 **exhaustive 처리 금지**(모르는 값은 폴백).
 */
export const AugmentResultState = {
  /** 생성이 아직 진행 중 — 결과물 자체가 없다 */
  GENERATING: 'GENERATING',
  /** 생성은 끝났고 비교 이미지 반입이 진행 중 — 0장이 **정상**인 구간 */
  PREPARING_FRAMES: 'PREPARING_FRAMES',
  /** 비교 이미지가 실재한다 */
  READY: 'READY',
  /** 파생본이 비식별 누락 신고 구간이라 이미지를 내보내지 않는다 */
  WITHHELD: 'WITHHELD',
  /** 생성이 **영구 실패**(dead-letter) — 기다려도 생기지 않는다 */
  GENERATION_FAILED: 'GENERATION_FAILED',
  /** 사용자 취소로 종결 */
  CANCELED: 'CANCELED',
  /** 유예 경과로 **실삭제** — 이미지가 영구히 없다 */
  PURGED: 'PURGED',
  /**
   * 파생 영상 매핑이 없어 비교 이미지를 **영구히** 제공할 수 없다 — 이전에 생성된 외부 위탁 증강.
   *
   * 생성 자체는 **성공**한 항목이라 실패로 표시하지 않는다. 기다려도 이미지가 생기지 않으므로
   * `PREPARING_FRAMES`("반입 중")로 뭉개면 화면이 영원히 오지 않을 것을 곧 온다고 말하게 된다.
   * 검수(채택/반려)는 그대로 가능하다(`reviewable` 은 이 상태에서도 유지된다).
   */
  DERIVATIVE_UNLINKED: 'DERIVATIVE_UNLINKED',
} as const;
export type AugmentResultState =
  (typeof AugmentResultState)[keyof typeof AugmentResultState];

/**
 * 폐기(소프트 삭제) 상태 — 반려된 결과물의 **유예·복구** 축.
 *
 * 폐기 상태가 **아니면**(표식 없음 · 복구됨 · 해상도 파생) 이 객체 자체가 `null` 이다.
 * "폐기되지 않음" 을 뜻하는 값 조합은 없다.
 */
export interface AugmentDiscardState {
  /** 폐기(반려) 시각 — 유예 기산점 */
  discardedAt: string;
  /**
   * 실삭제 **예정** 시각(= `discardedAt + 유예기간`).
   *
   * 폐기 스윕이 비활성이면 `null` — 영원히 지워지지 않으므로 예정 시각을 만들어 보이면 거짓이다.
   * 이 값이 이미 과거인데 `purged=false` 인 것도 **정상**이다(스윕 주기만큼 지연).
   */
  purgeAt: string | null;
  /** DB 실삭제가 커밋됐는가 — 복구 불가 */
  purged: boolean;
  /** 지금 복구를 시도할 수 있는가 — **UI 힌트일 뿐 최종 판정이 아니다**(BE 가 락 잡고 재판정) */
  restorable: boolean;
}

/** 증강 결과 — 영상별 + 유형별 */
export interface AugmentResult {
  /** 결과 항목 ID (acceptAugment/rejectAugment의 path param) */
  id: number;
  videoId: number;
  cctvName: string;
  type: AugmentResultType;
  /** 프레임 페어 (원본/증강) — BE 페이징된 슬라이스 */
  framePairs: AugmentFramePair[];
  decision: AugmentDecision;
  /** ACCEPTED 시 결정 일시 */
  decidedAt?: string;
  /** REJECTED 시 사유 */
  rejectReason?: string;
  /** 파생 영상 RAW_SN (해상도 파생) — 구 응답에는 없음 */
  derivativeRawSn?: number;
  /** 페이징 전 전체 프레임 쌍 수 — 구 응답에는 없어 optional */
  totalFramePairs?: number;
  /**
   * accept/reject 가능 여부. 해상도 파생은 검수 대상이 아닌 내부 생성물이라 false.
   * 구 응답(외부 위탁 증강)에는 없으므로 미지정은 "검수 가능"으로 취급한다.
   */
  reviewable?: boolean;
  /**
   * 이 결과물을 만들 때 외부로 전송한 **생성 조건 원문**(BE `LS_DATA_AUG.PROMPT_CN`).
   *
   * 같은 (영상 × 종류) 재요청이 허용되므로 "이 파생본이 어떤 조건으로 만들어졌는가"가
   * 결과 식별의 유일한 수단이다(R9 역추적). 보낸 그대로의 JSON 문자열이며 서버가 재가공하지
   * 않으므로 **파싱 실패에 안전하게** 다룬다(해상도 파생·구 요청은 null).
   */
  prompt?: string | null;
  /**
   * 결과물 상태 — 비교 이미지가 0장인 **이유**. BE 는 항상 채워 보내지만 구 응답에는 없어 optional.
   * 미지의 값이 올 수 있으므로 문구 매핑은 폴백을 갖는다(`emptyPairsMessage`).
   */
  resultState?: AugmentResultState;
  /**
   * 폐기(소프트 삭제) 상태. 폐기 상태가 아니면 `null`, 구 응답에는 필드 자체가 없다.
   * 해상도 파생(`RESL_*`)은 폐기 체계 밖이라 **항상** null.
   */
  discard?: AugmentDiscardState | null;
}

export interface AugmentFramePair {
  srcSn: number;
  frameNo?: number;
  originalUrl: string;
  /** 증강 처리 결과 — 실패 시 undefined */
  augmentedUrl?: string;
}

// 증강 결과 화면의 실제 집계 상태 — BE /{jobId}/result 응답 status 계약(3값).
// 프레임별 results 본문은 외부 SFR-07 연동 전이라 비어 있을 수 있으나, status 는 항상 실제 집계값이다.
export const AugmentResultStatus = {
  PROCESSING: 'PROCESSING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
} as const;
export type AugmentResultStatus =
  (typeof AugmentResultStatus)[keyof typeof AugmentResultStatus];

export interface AugmentResultPage {
  jobId: number;
  /**
   * BE 집계 상태(COMPLETED|FAILED|PROCESSING). 결과가 비어 있어도 이 값으로 표시하며,
   * results.length 로 상태를 파생하지 않는다(완료/실패의 "처리 중" 오표시 제거).
   */
  status: AugmentResultStatus;
  results: AugmentResult[];
  /** 프레임 쌍 페이지 번호 (0-based) — 구 응답에는 없음 */
  page?: number;
  /** 프레임 쌍 페이지 크기 — 구 응답에는 없음 */
  size?: number;
  /** 결과 항목 축 페이지 번호 (0-based) — 구 응답에는 없음 */
  itemPage?: number;
  /** 결과 항목 축 페이지 크기 — 구 응답에는 없음 */
  itemSize?: number;
  /** 결과 항목 축 총량(외부 위탁 + 해상도 파생) — 구 응답에는 없음 */
  totalElements?: number;
  /** 결과 항목 축 총 페이지 수(총량 0 이면 0) — 구 응답에는 없음 */
  totalPages?: number;
}

/**
 * 증강 결과 조회 파라미터 — **페이징 축이 둘이다**.
 * - `page`/`size` : 프레임 쌍 축 (BE 기본 0/12)
 * - `itemPage`/`itemSize` : 결과 항목 축 (BE 기본 0/20)
 *
 * 한 창을 공유하면 "한쪽 축 총량이 0이면 다른 축이 갇힌다"가 구조적으로 남는다
 * (순수 외부 위탁 잡은 프레임 쌍이 0건이라 프레임 페이저가 렌더되지 않는다).
 */
export interface GetAugmentResultParams {
  page?: number;
  size?: number;
  itemPage?: number;
  itemSize?: number;
}

/**
 * 증강 진행상태 — `GET /v1/augments/{id}/progress` (id = 결과 항목 id).
 *
 * `progress` 가 null 이면 값을 신뢰할 수 없고 사유는 `unavailableReason` 에 있다.
 * `nextPollAfterMs` 는 서버 **권고** 폴링 간격이며 0 이면 종결(폴링 중단)이다 —
 * 서버에 속도 제한이 없으므로 폴링 증폭을 줄이는 수단은 이 힌트뿐이다.
 */
export const AugmentProgressStatus = {
  RECEIVED: 'RECEIVED',
  RUNNING: 'RUNNING',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
  CANCELED: 'CANCELED',
} as const;
export type AugmentProgressStatus =
  (typeof AugmentProgressStatus)[keyof typeof AugmentProgressStatus];

/** 진행률을 산출하지 못한 사유 — 넷은 성격이 전혀 달라 화면 표시도 달라야 한다. */
export const AugmentProgressUnavailableReason = {
  /** 외부 연동 비활성(배포 기본값). **오류가 아니다** */
  NOOP: 'NOOP',
  /** 외부 호출 실패(서킷 open·타임아웃·계약 위반) — 작업 자체는 계속 진행 중 */
  TRANSIENT_ERROR: 'TRANSIENT_ERROR',
  /** 외부 작업 ID 미수신(접수 직후·ACK 유실) — 오류로 표시 금지 */
  AWAITING_ACK: 'AWAITING_ACK',
  /** 우리 쪽 자체 상한(청크 과다·요청 예산) — 벤더 장애가 아니다 */
  QUERY_LIMIT_EXCEEDED: 'QUERY_LIMIT_EXCEEDED',
} as const;
export type AugmentProgressUnavailableReason =
  (typeof AugmentProgressUnavailableReason)[keyof typeof AugmentProgressUnavailableReason];

export interface AugmentProgress {
  id: number;
  augTypeCd: string;
  status: AugmentProgressStatus;
  /** 0~100. null 이면 산출 불가(사유는 unavailableReason) */
  progress: number | null;
  unavailableReason: AugmentProgressUnavailableReason | null;
  totalJobCount: number;
  terminalJobCount: number;
  /** 취소 가능 여부(증강 행이 PENDING 일 때만 true) */
  cancelable: boolean;
  /** 권고 폴링 간격(ms). 0 = 더 폴링할 필요 없음(종결) */
  nextPollAfterMs: number;
}

/**
 * 증강 취소 응답 — **accept/reject(`AugmentSummaryResponse`)와 shape 이 다르다**.
 *
 * 증강 1건이 여러 청크로 나뉘어 위탁되므로 "일부만 취소" 가 정상 시나리오이며,
 * 요약 DTO 로는 그 사실을 표현할 수 없어 전용 타입이다. 같은 파서로 다루지 말 것.
 */
export interface AugmentCancelResult {
  id: number;
  augTypeCd: string;
  /** 취소 후 증강 상태(CANCELED 또는 이미 종결이던 기존 상태) */
  status: string;
  /** **이번 요청이** 취소를 확정했는가. 멱등 재요청·이미 종결이면 false(오류 아님) */
  canceled: boolean;
  /** 취소 대상 청크 전부의 외부 취소가 성립했는가 */
  fullyCanceled: boolean;
  targetJobCount: number;
  canceledJobCount: number;
  /** 외부 취소가 전달되지 않은 청크 순번 */
  failedJobSeqs: number[];
  /** BE 가 주는 사용자 안내 문구 — 화면은 이 문구를 그대로 쓴다 */
  message: string;
}

/** 취소 요청 바디 — **`reason` 하나만** 보낸다(Mass Assignment 방어, BE 계약). */
export interface CancelAugmentRequest {
  reason?: string;
}

/** BE `AugmentCancelRequest#reason` 과 동일 상한. */
export const AUGMENT_CANCEL_REASON_MAX_LENGTH = 500;

/**
 * 외부 생성형 AI 로 그대로 전달되는 **구조화 프롬프트 5필드** (「생성형 AI API 연동명세서 v1.1」 §4.1).
 *
 * - **5필드 전부 필수** — 하나라도 비면 BE 가 400 으로 거부한다. 벤더가 빈 조건을 임의 기본값으로
 *   채우면 결과가 비결정적이 되기 때문이다.
 * - **값은 자유 문자열** — 연동명세서가 `prompt` 를 자유 dict 로만 규정하고 허용값 enum 을 정의하지
 *   않는다. 예시값(NIGHT/WINTER/RAIN/ROAD/HIGH)은 규격서 **샘플**일 뿐 선택지가 아니므로,
 *   FE 에서 select 로 고정해 사용자를 가두지 않는다(벤더가 지원하는 조건을 우리가 모르는 채 막게 된다).
 * - 형식만 닫는다: 필수 · 공백 불가 · **보이지 않는 문자만 채운 값 불가** · 50자 이내.
 *   검증 규칙은 `validateAugmentPrompt`(BE `VisibleTextNormalizer` 미러) 참조.
 */
export const AUGMENT_PROMPT_FIELD_KEYS = [
  'time',
  'season',
  'weather',
  'terrain',
  'severity',
] as const;
export type AugmentPromptFieldKey = (typeof AUGMENT_PROMPT_FIELD_KEYS)[number];

/** 프롬프트 5필드 값 묶음 — 전송 페이로드의 `prompt` 그 자체. */
export type AugmentPromptFields = Record<AugmentPromptFieldKey, string>;

/** BE `PromptFields.MAX_FIELD_LENGTH` 와 동일 상한. 넘으면 BE 가 400 으로 거부한다. */
export const AUGMENT_PROMPT_MAX_LENGTH = 50;

/**
 * 입력 폼 표시 메타 — 라벨과 **예시(placeholder)**.
 * placeholder 는 규격서 샘플값이며 선택지가 아니다(자유 입력을 막지 않는다).
 */
export const AUGMENT_PROMPT_FIELD_META: Record<
  AugmentPromptFieldKey,
  { label: string; placeholder: string; hint: string }
> = {
  time: { label: '시간대', placeholder: 'NIGHT', hint: '예: NIGHT, 새벽, 해질녘' },
  season: { label: '계절', placeholder: 'WINTER', hint: '예: WINTER, 초봄' },
  weather: { label: '날씨', placeholder: 'RAIN', hint: '예: RAIN, 폭설, 안개' },
  terrain: { label: '지형', placeholder: 'ROAD', hint: '예: ROAD, 교차로, 골목' },
  severity: { label: '심각도', placeholder: 'HIGH', hint: '예: HIGH, 보통' },
};

/** 빈 프롬프트 초기값 — 상수 객체를 공유하지 않도록 매 호출 새 객체를 만든다(불변성). */
export const createEmptyAugmentPrompt = (): AugmentPromptFields => ({
  time: '',
  season: '',
  weather: '',
  terrain: '',
  severity: '',
});

export interface RequestAugmentRequest {
  videoIds: number[];
  types: AugmentType[];
  /**
   * 생성 조건 5필드 — **필수**. 미전송 시 BE 가 400(INVALID_INPUT).
   * `types` 는 이 값에서 파생되지 않는다(사용자가 카드로 직접 고른 값 그대로).
   */
  prompt: AugmentPromptFields;
}

export interface RequestAugmentResponse {
  jobId: number;
  /** 요청 일시 (ISO-8601) */
  requestedAt: string;
  /** 요청된 영상 수 */
  videoCount: number;
  /** 요청된 증강 유형 수 */
  typeCount: number;
}

/**
 * 증강 요청 실패(NOT_REVIEWED) 시 BE가 동봉하는 부가 정보.
 * `ApiError.data` 또는 응답 본문의 `data` 필드에 담겨 전달된다.
 */
export interface AugmentNotReviewedDetail {
  /** 검수 미완료로 차단된 영상 ID 목록 */
  blockedVideoIds: number[];
}

export interface ListAugmentJobsParams {
  page?: number;
  size?: number;
  /** 특정 원본 srcSn 필터 (선택) */
  srcSn?: number;
}
