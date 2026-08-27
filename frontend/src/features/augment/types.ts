import type { ComponentType } from 'react';
import { CloudRain, Moon, Ratio, Snowflake } from 'lucide-react';

import type { ResolutionPreset } from '@/features/video/types';

// 증강 도메인 타입 (BE OpenAPI alias) — UI/UX §4-12 정합.
//
// 활용 결정 상태:
// - PENDING: 채택/반려 액션 노출
// - ACCEPTED: 결정 일시 표시 (변경 불가)
// - REJECTED: 반려 사유 표시 (변경 불가)

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

/**
 * 처리 종류 카드의 픽토그램 — 아이콘 라이브러리(lucide-react) 컴포넌트다.
 *
 * 이모지 문자열이 아닌 이유: 이모지는 OS·폰트마다 모양이 달라지고 크기·색 토큰이 먹지 않으며
 * 스크린리더가 문자 이름을 읽는다. 값 타입은 공통 Button 의 `leftIcon` 과 같은 계약이다.
 * 종류와 의미가 맞는 아이콘만 쓴다(겨울=눈송이 / 야간=달 / 우천=비구름 / 해상도 변경=해상도·비율).
 * ⚠ 해상도에 `Scaling`(사각형+대각 화살표)을 쓰면 증강 결과 화면의 `ExternalLink` 와 모양이
 *   겹쳐 "새 창으로 열기"로 오독된다 — 실측 확인 후 `Ratio` 로 골랐다.
 */
export const PROCESS_KIND_ICON: Record<
  ProcessKind,
  ComponentType<{ className?: string }>
> = {
  WINTER: Snowflake,
  NIGHT: Moon,
  RAIN: CloudRain,
  RESOLUTION: Ratio,
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

/**
 * 활용 결정 상태의 사용자 노출 문구 — 화면 어디서나 같은 단어를 쓴다.
 *
 * 확정 용어는 **반려**다(사양 SCREEN-023). 키(`REJECTED`)는 BE 계약이라 그대로 두고
 * 값(사람이 읽는 문구)만 그 용어를 따른다.
 */
export const AUGMENT_DECISION_LABEL: Record<AugmentDecision, string> = {
  PENDING: '활용 결정 대기',
  ACCEPTED: '채택됨',
  REJECTED: '반려됨',
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
  /**
   * 이 **폐기 표식**이 지금 열려 있고 실삭제 클레임도 잡히지 않았는가 — 유예 안내 문구용 힌트.
   *
   * ⚠ **복구 버튼의 근거가 아니다. 그 근거는 `AugmentResult.restoreEligible` 하나다**(프로덕션
   * 컴포넌트는 이 필드를 참조하지 않는다). 이 값은 *표식 수준* 이라 두 방향으로 틀린다 —
   * 표식이 없는 반려(그랜드퍼더링)는 객체째 `null` 이라 표현할 수 없고, 실삭제 클레임 구간에서는
   * 보수적으로 `false` 인데 복구는 실제로 성립한다. 두 값이 갈리는 것이 정상이며 합치지 말 것.
   */
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
  /**
   * 이 결과로 만들어진 **파생 영상 RAW_SN**.
   *
   * BE 는 매핑이 없는 항목(V155 이전 그랜드퍼더링 · 콜백 도착 전)에 **`null`** 을 싣는다.
   * 구 응답에는 필드 자체가 없어 `undefined` 도 온다 — 세 값(`number`/`null`/`undefined`)을
   * 모두 견뎌야 하므로 타입에 `null` 을 명시한다.
   */
  derivativeRawSn?: number | null;
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
  /**
   * **복구 버튼을 그릴 수 있는가** — BE 가 자기 복구 사전조건으로 계산해 내려주는 값.
   *
   * 화면은 이 값을 **그대로** 쓴다. `decision`/`resultState`/`discard` 로 재유도하지 말 것 —
   * `decision === 'REJECTED'` 는 세 입력(사람의 반려 · 폐기 표식 · **생성 영구 실패**)에서 나오는데
   * 복구 API 는 앞 둘만 받는다. 재유도하던 구현은 dead-letter 항목에 버튼을 그렸고, 재조회해도
   * 같은 값이 돌아와 **404 무한 재시도**가 됐다(서버측 중복 차단·속도 제한이 없는 확정 정책).
   *
   * 구 응답에는 필드가 없어 optional 이며, **미지정은 "그리지 않음"** 으로 다룬다(fail-closed).
   * BE 는 항상 boolean 을 싣는다 — 없다는 것은 구 BE 라는 뜻이고, 그때는 버튼이 없는 편이
   * 반드시 실패하는 버튼보다 낫다.
   *
   * **최종 판정이 아니다** — BE 가 행을 잠그고 재판정하므로 버튼을 그린 뒤에도 404/409 가 날 수 있다.
   */
  restoreEligible?: boolean;
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
  /**
   * 요청일시(ISO-8601) — 이 영상의 증강 요청 시각(증강 행 `MIN(REG_DT)`).
   * 목록(`AugmentJob.requestedAt`)과 **같은 축**이며, 항목별 `decidedAt`(채택·반려 **결정** 시각)과는
   * 축이 달라 서로 대체할 수 없다. 증강 행이 0건이면 `null`, 구 응답에는 없음(`undefined`).
   */
  requestedAt?: string | null;
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
 * 외부 생성형 AI 이벤트 유형 — 「생성형 AI API 연동명세서 v1.3」 §4.1 `evnt_type`.
 *
 * **영상의 관제 이벤트 코드에서 변환하지 않는다.** 두 분류 축이 서로 다른 체계라 자동 변환은
 * 추정이 되고, 추정한 값이 그대로 외부 위탁에 실린다. 요청자가 화면에서 고른 값을 그대로 중계한다.
 *
 * ⚠ **증강 종류(`AugmentType`)와 다른 축이다.** 이름이 비슷해 섞이기 쉬우나 값도 조달처도 전혀
 * 다르며, 어느 한쪽을 다른 쪽에서 유추하지 않는다(아래 `AUGMENT_MTDT_CODES` 주석 참조).
 *
 * [@design INT-008] [@design API-060] [@design SCREEN-022]
 */
export const AUGMENT_EVENT_TYPES = ['FLOOD', 'WILDFIRE'] as const;
export type AugmentEventType = (typeof AUGMENT_EVENT_TYPES)[number];

/** 이벤트 유형의 사용자 노출 문구 — 전송값은 언제나 코드다. */
export const AUGMENT_EVENT_TYPE_LABEL: Record<AugmentEventType, string> = {
  FLOOD: '침수',
  WILDFIRE: '산불',
};

/**
 * 침수 세부 유형 — v1.3 §4.1 `evnt_subtype`. **선택이며 침수일 때만 허용**한다.
 *
 * 계약에 산불 세부 코드가 정의돼 있지 않아 `WILDFIRE` 와 함께 보내면 BE 가 400 이다.
 * 그래서 화면은 침수일 때만 노출하고, 산불이면 값 자체를 전송하지 않는다.
 */
export const AUGMENT_FLOOD_SUBTYPES = [
  'ROAD_FLOOD',
  'RIVER_OVERFLOW',
  'UNDERPASS_FLOOD',
  'URBAN_INUNDATION',
  'OTHER',
] as const;
export type AugmentFloodSubtype = (typeof AUGMENT_FLOOD_SUBTYPES)[number];

export const AUGMENT_FLOOD_SUBTYPE_LABEL: Record<AugmentFloodSubtype, string> = {
  ROAD_FLOOD: '도로 침수',
  RIVER_OVERFLOW: '하천 범람',
  UNDERPASS_FLOOD: '지하차도 침수',
  URBAN_INUNDATION: '도심 침수',
  OTHER: '기타',
};

/**
 * 외부로 나가는 **구조화 생성 조건**(v1.3 §4.1 최상위 `mtdt`)의 항목 키·순서.
 *
 * 순서는 명세서와 같다 — 화면의 표시 순서이자 BE 조립 순서다.
 */
export const AUGMENT_MTDT_FIELD_KEYS = [
  'time',
  'season',
  'weather',
  'terrain',
  'severity',
] as const;
export type AugmentMtdtFieldKey = (typeof AUGMENT_MTDT_FIELD_KEYS)[number];

/**
 * 다섯 축의 **허용 코드** — v1.3 이 전부 닫아 두었다(BE `AugmentPrompts` 의 enum 미러).
 *
 * ⚠ **구 서술 폐기(2026-08-27)**: *"연동명세서가 `prompt` 를 자유 dict 로만 규정하고 허용값 enum 을
 * 정의하지 않으므로 FE 에서 select 로 고정해 사용자를 가두지 않는다"* 는 **더 이상 사실이 아니다.**
 * v1.3 은 다섯 축 전부의 코드를 규정했고, 코드 밖 값은 벤더에서 `400 INVALID_PARAMETER` 로 돌아온다.
 * 그래서 화면은 **드롭다운으로만** 고르게 해 허용 코드 밖 값을 보낼 수단 자체를 없앤다.
 *
 * ★**여기서 증강 종류(`AUG_TYPE_CD`)를 파생하지 않는다.** 생성 조건 값(예: `season=WINTER`)으로 종류를
 * 유추하면 그 값이 파생 산출물 경로(`.../{augTypeCd}.mp4`)와 해상도 네임스페이스(`RESL_` 접두) 판별로
 * 흘러 경로 순회(CWE-22)·검수 우회가 열린다. 코드 공간이 실제로 겹치므로(`Season.WINTER` ↔ 증강 종류
 * `WINTER`) 코드로 닫힌 뒤에도 이 방어는 그대로 유효하다. 이벤트 유형에서도 파생하지 않는다.
 */
export const AUGMENT_MTDT_CODES = {
  time: ['DAWN', 'DAY', 'DUSK', 'NIGHT'],
  season: ['SPRING', 'SUMMER', 'AUTUMN', 'WINTER'],
  weather: ['CLEAR', 'CLOUDY', 'RAIN', 'SNOW', 'FOG', 'WINDY'],
  terrain: [
    'ROAD',
    'UNDERPASS',
    'RIVER',
    'URBAN',
    'RESIDENTIAL',
    'RURAL',
    'MOUNTAIN',
    'FOREST',
  ],
  severity: ['LOW', 'MEDIUM', 'HIGH'],
} as const satisfies Record<AugmentMtdtFieldKey, readonly string[]>;

export type AugmentTimeCode = (typeof AUGMENT_MTDT_CODES.time)[number];
export type AugmentSeasonCode = (typeof AUGMENT_MTDT_CODES.season)[number];
export type AugmentWeatherCode = (typeof AUGMENT_MTDT_CODES.weather)[number];
export type AugmentTerrainCode = (typeof AUGMENT_MTDT_CODES.terrain)[number];
export type AugmentSeverityCode = (typeof AUGMENT_MTDT_CODES.severity)[number];

/**
 * 전송되는 구조화 생성 조건 — **다섯 항목 전부 필수**.
 *
 * 벤더 계약은 "최소 1개" 지만 하나라도 비면 벤더가 어떤 기본값으로 채울지 알 수 없어 결과가
 * 비결정적이 된다(2026-07-31 사용자 확정, 2026-08-27 재확인). **더 엄격한 쪽이 의도된 선택**이며
 * BE 도 항목마다 `@NotNull` 로 400 을 낸다 — "계약이 선택이니 완화하자" 로 되돌리지 말 것.
 */
export interface AugmentMtdt {
  time: AugmentTimeCode;
  season: AugmentSeasonCode;
  weather: AugmentWeatherCode;
  terrain: AugmentTerrainCode;
  severity: AugmentSeverityCode;
}

/**
 * 입력 폼이 들고 있는 **초안** — 미선택은 빈 문자열이다.
 *
 * 전송 타입(`AugmentMtdt`)과 분리하는 이유는 "아직 고르지 않음" 을 타입으로 표현하기 위해서다.
 * 초안 → 전송값 변환·검증은 `validateAugmentConditions` 한 곳이 담당한다.
 */
export type AugmentMtdtDraft = Record<AugmentMtdtFieldKey, string>;

/**
 * 항목별 표시 메타 — **라벨과 코드 표시 문구의 단일 정의 지점**.
 *
 * 컴포넌트는 이 표를 순회할 뿐 라벨·코드 목록을 복제하지 않는다. 복제하면 코드가 늘 때 한쪽만
 * 고쳐져 화면과 전송값이 갈라진다.
 */
export const AUGMENT_MTDT_FIELD_META: {
  [K in AugmentMtdtFieldKey]: {
    label: string;
    codes: readonly (typeof AUGMENT_MTDT_CODES)[K][number][];
    codeLabel: Readonly<Record<(typeof AUGMENT_MTDT_CODES)[K][number], string>>;
  };
} = {
  time: {
    label: '시간대',
    codes: AUGMENT_MTDT_CODES.time,
    codeLabel: { DAWN: '새벽', DAY: '낮', DUSK: '황혼', NIGHT: '밤' },
  },
  season: {
    label: '계절',
    codes: AUGMENT_MTDT_CODES.season,
    codeLabel: { SPRING: '봄', SUMMER: '여름', AUTUMN: '가을', WINTER: '겨울' },
  },
  weather: {
    label: '날씨',
    codes: AUGMENT_MTDT_CODES.weather,
    codeLabel: {
      CLEAR: '맑음',
      CLOUDY: '흐림',
      RAIN: '비',
      SNOW: '눈',
      FOG: '안개',
      WINDY: '바람',
    },
  },
  terrain: {
    label: '지형',
    codes: AUGMENT_MTDT_CODES.terrain,
    codeLabel: {
      ROAD: '도로',
      UNDERPASS: '지하차도',
      RIVER: '하천',
      URBAN: '도심',
      RESIDENTIAL: '주거지역',
      RURAL: '시골',
      MOUNTAIN: '산지',
      FOREST: '숲',
    },
  },
  severity: {
    label: '심각도',
    codes: AUGMENT_MTDT_CODES.severity,
    codeLabel: { LOW: '낮음', MEDIUM: '보통', HIGH: '높음' },
  },
};

/** 해당 축의 허용 코드인가 — 초안 문자열을 전송값으로 좁히는 fail-closed 판정. */
export const isAugmentMtdtCode = (key: AugmentMtdtFieldKey, value: string): boolean =>
  (AUGMENT_MTDT_CODES[key] as readonly string[]).includes(value);

/**
 * 코드 → 사람이 읽는 문구. 모르는 값(구 자유 문자열 적재분 등)은 **그대로 돌려준다**.
 * 결과 화면이 옛 요청 원문을 보여줄 때 한글 자유 입력이 그대로 나와야 하기 때문이다.
 */
export const augmentMtdtCodeLabel = (
  key: AugmentMtdtFieldKey,
  code: string,
): string =>
  (AUGMENT_MTDT_FIELD_META[key].codeLabel as Record<string, string>)[code] ?? code;

/**
 * 자유 지시문(`prompt`) 길이 상한 — v1.3 §4.1. BE `AugmentPrompts.MAX_PROMPT_LENGTH` 미러.
 *
 * ⚠ 구 상수(생성 조건 필드당 50자)와 **다른 축이다**. 생성 조건은 이제 자유 입력이 아니라
 * 드롭다운이라 길이 제한이 존재하지 않는다.
 */
export const AUGMENT_PROMPT_MAX_LENGTH = 1000;

/** 빈 생성 조건 초안 — 상수 객체를 공유하지 않도록 매 호출 새 객체를 만든다(불변성). */
export const createEmptyAugmentMtdt = (): AugmentMtdtDraft => ({
  time: '',
  season: '',
  weather: '',
  terrain: '',
  severity: '',
});

/**
 * 증강 유형별 생성 조건 **기본값**(프리필) — 유형이 실제 요청을 가르게 하는 유일한 통로.
 *
 * <b>왜 필요한가</b>: 외부 위탁 요청 바디에는 증강 유형 필드가 없다. 유형에 따라 달라질 수 있는
 * 값은 생성 조건과 자유 지시문뿐이므로, 유형이 반영되지 않으면 겨울·야간·우천이 **완전히 동일한
 * 요청**이 되어 종류를 나눈 의미가 사라진다.
 *
 * <b>강제가 아니라 기본값이다</b>: 검수자가 조건을 조절할 수 있어야 한다는 정책을 지키려면
 * ①프리필 값을 바꿀 수 있고 ②이미 사용자가 손댄 항목은 종류를 바꿔도 보존돼야 한다.
 * 그 판정(무엇을 사용자가 손댔는가)은 입력 화면이 소유한다 — 이 상수는 값만 정한다.
 *
 * <b>서버로 올라가는 파생은 없다</b>: 반대 방향(=생성 조건으로 증강 유형을 유추)은 만들지 않는다.
 *
 * 채우지 않는 축(지형·심각도 등)은 <b>비워 둔다</b> — 영상마다 다른 값을 시스템이 지어내면
 * 검수자가 확인하지 않은 조건이 그대로 외부로 나간다.
 */
export const AUGMENT_MTDT_PRESET: Record<
  AugmentType,
  Readonly<Partial<AugmentMtdtDraft>>
> = {
  WINTER: { season: 'WINTER', weather: 'SNOW' },
  NIGHT: { time: 'NIGHT' },
  RAIN: { weather: 'RAIN' },
};

/**
 * 처리 종류에 대응하는 생성 조건 기본값을 만든다(매 호출 새 객체 — 불변성).
 *
 * 해상도 변경(RESOLUTION)은 외부 위탁이 아니라 생성 조건 자체가 없으므로 전부 빈 값이다.
 * 프리필이 없는 항목을 빈 문자열로 **명시**해 반환하는 이유는, 호출부가 "이전 종류의 프리필
 * 잔재"를 지울 수 있게 하기 위해서다(부분 병합이면 야간을 골라도 계절=겨울이 남는다).
 */
export const createMtdtPresetFor = (kind: ProcessKind): AugmentMtdtDraft => ({
  ...createEmptyAugmentMtdt(),
  ...(isAugmentKind(kind) ? AUGMENT_MTDT_PRESET[kind] : {}),
});

export interface RequestAugmentRequest {
  videoIds: number[];
  /**
   * 요청 증강 유형 — 사용자가 카드로 직접 고른 값 그대로다.
   * 생성 조건·이벤트 유형 어느 쪽에서도 파생하지 않는다.
   */
  types: AugmentType[];
  /** 외부 이벤트 유형 — **필수**. 미전송 시 BE 가 400. 관제 이벤트 코드에서 변환하지 않는다. */
  evntType: AugmentEventType;
  /** 침수 세부 유형 — 선택. `evntType==='FLOOD'` 일 때만 싣는다(산불과 함께 보내면 400). */
  evntSubtype?: AugmentFloodSubtype;
  /** 구조화 생성 조건 — **필수**이며 다섯 항목 전부 있어야 한다. */
  mtdt: AugmentMtdt;
  /** 자유 지시문 — 선택. 비어 있으면 키 자체를 싣지 않는다. */
  prompt?: string;
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
