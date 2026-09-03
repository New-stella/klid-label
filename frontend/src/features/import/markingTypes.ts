// 마킹 산출물 일괄 가져오기(DOMAIN-017) 도메인 타입 — BE `transfer` 패키지 DTO 의 1:1 미러.
//
// ★ 라벨링 완료 갈래(`types.ts`)와 **파일을 나눈다**. 두 갈래는 방향이 반대다 — 그쪽은 라벨링이
//   끝난 결과를 받아 검수만 하고, 이쪽은 시작점만 받아 비식별부터 앞 단계를 전부 밟는다.
//   ADR-053 이 **계약을 합치지 않으며 화면에서만 갈래를 고른다**고 정했으므로, 한 파일에 섞어
//   필드를 공유하면 한쪽 갈래의 사정으로 다른 갈래의 타입이 함께 흔들린다.
//
// 진실원은 백엔드 응답 계약이다. 여기서 필드를 지어내거나 판정을 다시 계산하지 않는다.
//
// @design SCREEN-039
// @design ADR-053
// @design API-216 API-217 API-218

/* ------------------------------------------------------------------ *
 * API-216 폴더 검사
 * ------------------------------------------------------------------ */

/** 검사 요청 — 경로는 본문으로 보낸다(주소줄에 실으면 접근 기록에 남는다). */
export interface MarkingScanRequest {
  folderPath: string;
}

/**
 * 검사 알림 1건.
 *
 * ★이 목록에는 적재를 막는 사유와 막지 않는 사유가 **함께** 담긴다. 개수를 세어 적재 가능 여부를
 * 판정하지 말 것 — 판정은 항목의 {@link MarkingScanItem.importable} 하나가 소유한다.
 */
export interface MarkingWarning {
  code: string;
  message: string;
}

/** 검사 결과 한 항목 — 마킹 문서 하나가 한 줄이다. */
export interface MarkingScanItem {
  markingFileName: string;
  /** 영상 파일 이름에서 확장자를 뗀 값. 만들 수 없으면 null. */
  clipId: string | null;
  videoFileName: string | null;
  /** 같은 이름의 영상이 둘 이상이면 **찾지 못한 것으로** 온다(짐작해 고르지 않는다). */
  videoFound: boolean;
  segmentCount: number;
  markCount: number;
  /** 마킹 문서의 프레임 번호와 시각으로 역산한 프레임 재생 속도. */
  declaredFps: number | null;
  /** 영상 파일에서 실제로 읽은 프레임 재생 속도 — 적재 시 마킹에 고정되는 값은 이쪽이다. */
  probedFps: number | null;
  videoFrameCount: number | null;
  /** ★적재 가능 여부의 단일 판정값. 알림 유무로 대신 판정하지 않는다. */
  importable: boolean;
  warnings: MarkingWarning[];
}

/** 검사 결과 — 아무것도 저장하지 않는 미리보기다. */
export interface MarkingScanResult {
  items: MarkingScanItem[];
  scannedFileCount: number;
  matchedCount: number;
  importableCount: number;
  /** 어느 마킹 문서도 가리키지 않은 영상 수 — 묶음이 온전한지 사람이 판단하는 근거다. */
  unmatchedVideoCount: number;
  /** 그 영상의 이름. 수가 많으면 상한까지만 담기며 전체 수는 위 값이 알린다. */
  unmatchedVideoNames: string[];
  /** ★참이면 이 결과가 폴더 전체가 아니다. 조용히 넘기면 일부가 전부로 보인다. */
  truncated: boolean;
  warnings: MarkingWarning[];
}

/* ------------------------------------------------------------------ *
 * API-217 일괄 적재
 * ------------------------------------------------------------------ */

/** 개인정보 유형 — 「미상」은 선택지에 없다(사람이 직접 고르는 자리다). */
export const MarkingPrivacyType = {
  ANONY: 'ANONY',
  PRVC: 'PRVC',
  PSDO: 'PSDO',
} as const;
export type MarkingPrivacyType = (typeof MarkingPrivacyType)[keyof typeof MarkingPrivacyType];

/**
 * 항목마다 같은 값으로 붙는 지정값.
 *
 * ★영상 식별자는 여기 없다 — 영상 파일 이름에서 얻으므로 사람이 지정하면 파일과 어긋난다.
 */
export interface MarkingImportMeta {
  eventTypeCd: string;
  localGovCd: string;
  cctvId: string;
  prvcTypeCd: MarkingPrivacyType;
  /** 촬영 시각(선택). 마킹 문서에 없으므로 지정하지 않으면 싣지 않는다 — 대용값 금지. */
  capturedAt?: string;
}

export interface MarkingCreateRequest {
  folderPath: string;
  meta: MarkingImportMeta;
  /** 적재할 마킹 문서의 **이름** 목록. 비우면 적재할 수 있다고 나온 항목을 모두 적재한다. */
  targets?: string[];
}

/**
 * 적재 접수 결과.
 *
 * ★이 응답이 뜻하는 것은 **작업이 등록되었다**는 것뿐이다(202). 적재는 아직 끝나지 않았으며
 * 영상 목록도 결과도 여기 없다 — 진행은 작업 식별번호로 따로 조회한다(API-218).
 */
export interface MarkingCreateResult {
  jobSn: number;
  targetCount: number;
}

/* ------------------------------------------------------------------ *
 * API-218 진행 조회
 * ------------------------------------------------------------------ */

/** 작업 상태 — 완료는 실패가 없는 종결, 실패는 실패가 남은 종결이다. */
export const MarkingJobStatus = {
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELED: 'CANCELED',
} as const;
export type MarkingJobStatus = (typeof MarkingJobStatus)[keyof typeof MarkingJobStatus];

/** 항목 상태 — 건너뜀은 처리하지 않은 것이라 실패와 구분한다(사람이 할 일이 다르다). */
export const MarkingItemStatus = {
  PENDING: 'PENDING',
  PROCESSING: 'PROCESSING',
  SUCCESS: 'SUCCESS',
  FAILED: 'FAILED',
  SKIPPED: 'SKIPPED',
} as const;
export type MarkingItemStatus = (typeof MarkingItemStatus)[keyof typeof MarkingItemStatus];

export interface MarkingProgressItem {
  markingFileName: string;
  videoFileName: string | null;
  status: MarkingItemStatus;
  /** 적재에 성공해 만들어진 영상의 식별번호. 성공하기 전에는 null. */
  rawSn: number | null;
  failureReason: string | null;
}

/**
 * 진행 상황.
 *
 * ★집계는 언제나 전체 기준이다 — 상태로 거르는 것은 {@link items} 뿐이다.
 * ★{@link itemsTruncated} 가 참이면 건별 결과가 일부만 담긴 것이다.
 */
export interface MarkingProgress {
  jobSn: number;
  status: MarkingJobStatus;
  folderPath: string;
  targetCount: number;
  /** 끝난 항목 수 — 성공과 실패와 건너뜀을 모두 센다. 진행률의 분자다. */
  doneCount: number;
  succeededCount: number;
  /** 실패했거나 건너뛴 항목 수. */
  failedCount: number;
  startedAt: string | null;
  finishedAt: string | null;
  itemsTruncated: boolean;
  items: MarkingProgressItem[];
}
