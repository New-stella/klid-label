// 영상 도메인 타입 (BE OpenAPI 기반 alias)

import type { BadgeStatus } from '@/components/common/StatusBadge';
import type { AssignmentStatus } from '@/features/task/types';

// 비식별 처리 상태 (BE Phase 2 응답 키 deidentStatus).
// enum 금지(frontend-coding-style) → as const 객체로 관리.
//  - IN_PROGRESS : 비식별 진행중 (마킹 진입 차단)
//  - FAILED      : 비식별 실패  (마킹 진입 차단)
//  - DONE        : 비식별 완료  (기존 dataSttsCd 배지 유지)
//  - NONE        : 비식별 대상 없음/미시작 (기존 dataSttsCd 배지 유지)
export const DEIDENT_STATUS = {
  IN_PROGRESS: 'IN_PROGRESS',
  FAILED: 'FAILED',
  DONE: 'DONE',
  NONE: 'NONE',
} as const;
export type DeidentStatus = (typeof DEIDENT_STATUS)[keyof typeof DEIDENT_STATUS];

/**
 * 마킹 진입 차단 여부 — 비식별이 "확정적으로 미완료" 일 때만 true.
 *
 * <p>deidentStatus(IN_PROGRESS/FAILED) 또는 deIdntfYn('N'/'F') 신호가 있으면 차단한다.
 * 정보가 없으면(undefined) 차단하지 않는다 — BE MarkingService(deIdntfYn='Y' 가드)가
 * 백스톱이므로 FE 는 알 수 있는 경우에만 UX 로 선차단한다(fail-open for UX, BE fail-closed).
 */
export function isMarkingBlocked(v: {
  deidentStatus?: DeidentStatus | string | null;
  deIdntfYn?: string | null;
}): boolean {
  if (
    v.deidentStatus === DEIDENT_STATUS.IN_PROGRESS ||
    v.deidentStatus === DEIDENT_STATUS.FAILED
  ) {
    return true;
  }
  return v.deIdntfYn === 'N' || v.deIdntfYn === 'F';
}

export interface VideoListParams {
  page?: number;
  size?: number;
  sort?: string;
  cctvNameKeyword?: string;
  eventTypeCd?: string;
  localGovId?: number;
  from?: string;
  to?: string;
  dataSttsCd?: string;
  // 검수 상태 필터 (LS_RAW_DATA_STATUS) — APPROVED 만 노출하는 증강 요청 화면용
  reviewStatusCd?: string;
}

export interface Video {
  id: number;
  cctvName: string;
  vmsClipId: string;
  eventName?: string;
  eventTypeCd?: string;
  localGov?: string;
  frameCount: number;
  status: BadgeStatus;
  capturedAt: string;
  thumbnailUrl?: string;
  privacyTypeCd?: string;
  durationSec?: number;
  // 영상별 최근 내보내기 상태 (BE: LS_RAW_DATA_STATUS join LS_DATA_SET 최신 1건)
  exportStatus?: 'EXPORTED' | 'FAILED' | null;
  exportedAt?: string | null;
  lastExportFailureReason?: string | null;
  // 영상 마지막 수정 시각 (LS_DATA_RAW.UPD_DT)
  updatedAt?: string | null;
  // 검수 완료 시각 (LS_RAW_DATA_STATUS.UPD_DT) — 검수 상태가 APPROVED 일 때만, 그 외 null
  reviewCompletedAt?: string | null;
  // 비식별 처리 여부 (BE LS_DATA_RAW.DE_IDENT_YN → 응답 키 deIdntfYn).
  // 'Y'=비식별 완료, 'N'=미처리, 'F'=실패. SC-009 재비식별 버튼 노출 조건에 사용.
  deIdntfYn?: 'Y' | 'N' | 'F';
  // 비식별 처리 상태 (BE Phase 2 응답 키 deidentStatus).
  // 목록 처리단계 배지(진행중/실패 우선 표시) + 마킹 진입 차단 판정에 사용.
  deidentStatus?: DeidentStatus;
  // 검수 상태 (BE LS_RAW_DATA_STATUS.DATA_STTS_CD → 응답 키 reviewSttsCd).
  // status(=배치단계 LS_DATA_RAW.DATA_STTS_CD)와 출처·의미가 다르다.
  // 'APPROVED'=검수완료. SC-009 재비식별 버튼 노출은 이 필드로 판정한다(status 아님).
  reviewSttsCd?: string;
  // LABELER 배정 정보 (BE VideoSummaryResponse — 미배정 영상은 모두 null/undefined).
  // TaskListPage 배정 시나리오와 정합: 이 필드 유무로 배정/재배정 버튼을 분기하고
  // AssignModal 재배정 모드에 현재 배정자를 사전선택한다.
  assignmentId?: number;
  workerId?: number;
  workerName?: string;
  assignedAt?: string;
  assignStatus?: AssignmentStatus;
}

export interface FramePreview {
  srcSn: number;
  frameNo: number;
  thumbnailUrl: string;
  timestampMs?: number;
  hasIssue?: boolean;
}

export type BatchStageStatus = 'DONE' | 'PROGRESS' | 'PENDING' | 'FAIL';

// BE StageStatusDto(name, status, progress) 와 1:1 정합.
// name = BE canonical 배치 단계 코드(BatchStage enum name):
//   DEIDENTIFY, MARKING, VLM, FRAME_EXTRACT, YOLO, SAM2, INTERPOLATE
//   (이 순서가 canonical — BE 가 배열 순서/상태를 그대로 내려주며 FE 는 name→한글라벨만 매핑)
// progress = BE Integer(nullable) → FE 는 표시하지 않으므로 null 허용.
export interface BatchStageItem {
  name: string;
  status: BatchStageStatus;
  progress: number | null;
}

/**
 * 영상의 배치 단계 상태가 「처리 중」임을 뜻하는 코드 — BE `LsDataRaw.DATA_STTS_PROCESSING`.
 *
 * 이 값은 **접수된 순간 커밋**된다(재기동은 FAILED→PROCESSING 원자 클레임을 요청 안에서 끝낸다).
 * 즉 실행이 아직 시작되지 않고 순서를 기다리는 구간도 이 값이다.
 */
export const BATCH_STATUS_PROCESSING = 'PROCESSING';

/**
 * 이 영상이 **배치 처리 중**인가 — 접수되어 순서를 기다리는 구간을 포함한다. [@design API-167]
 *
 * ★ 왜 `stages` 가 아니라 `status` 로 판정하는가
 *   `stages` 는 **최신 배치 로그 1건**에서 파생되므로, 접수만 되고 아직 아무것도 실행되지 않은
 *   구간에는 <b>직전 실행의 FAIL 이 그대로</b> 남아 있다. 그 값을 근거로 삼으면 화면이 "실패"를
 *   계속 보여주고, 그 상태에서 살아 있는 재실행 버튼은 누를 때마다 서버가 막는다(이미 처리 중).
 *   반면 `status` 는 접수 시점에 이미 PROCESSING 으로 커밋돼 있어 <b>진실을 먼저 말한다</b>.
 *
 * ⚠ 이 판정은 시간 창(임의 상수)이 아니다 — 창은 폴링의 <b>상한</b>으로만 쓰이며 판정 축이 아니다.
 */
export function isBatchProcessing(video: Pick<Video, 'status'>): boolean {
  return video.status === BATCH_STATUS_PROCESSING;
}

/**
 * 영상의 배치 단계 상태가 「실패」임을 뜻하는 코드 — BE `LsDataRaw.DATA_STTS_FAILED`.
 *
 * 전체 재기동(`POST /v1/videos/{rawSn}/batch/retry`)이 <b>유일하게 받는 상태</b>이며, 서버는 이
 * 값에서만 FAILED→PROCESSING 원자 클레임에 성공한다.
 */
export const BATCH_STATUS_FAILED = 'FAILED';

/**
 * 이 영상의 배치가 **지금 실패 상태인가** — 「실패 기록이 있는가」와 다른 축이다. [@design API-167]
 *
 * ★ 왜 이 축이 따로 필요한가
 *   묶음 재수행이 실패하면 서버는 영상을 <b>완주 상태로 원상 복구</b>하되 진행 로그에는 실패를 남긴다
 *   (운영자가 실패를 봐야 하므로 의도된 동작이다). 그러면 `stages` 는 전 단계 DONE 인데
 *   `batchFailureReason` 은 남아 있어, 사유만 보고 전체 재기동 창구를 열면 그 버튼은 <b>항상</b>
 *   막힌다 — 전체 재기동은 이 함수가 참일 때만 받기 때문이다.
 *
 * ⚠ 이 판정은 <b>서버가 이미 내려주는 상태값을 읽을 뿐</b>이다. 소비자가 생산자의 성공 조건을
 *   `stages`·사유 문자열에서 <b>재유도하지 않는다</b>(재유도하면 서버가 조건을 바꿀 때 조용히 어긋난다).
 */
export function isBatchFailed(video: Pick<Video, 'status'>): boolean {
  return video.status === BATCH_STATUS_FAILED;
}

/**
 * 건너뛰기·되돌리기·재수행의 **단위** — 개별 단계가 아니라 **작업 묶음**이다.
 * [@design API-198] [@design API-200] [@design API-201] [@design API-043]
 *
 * ★ 왜 묶음인가 — 뒤 단계가 앞 결과를 입력으로 받고 <b>보간이 그 산출물을 재계산</b>하므로 일부만
 * 수행하면 산출물끼리 어긋난다. 무엇보다 <b>보간을 묶음 밖에 두면 어떤 재수행에서도 보간이 무조건
 * 돌아 사람이 손댄 보간 라벨을 지운다</b> — 묶음이 그 사고를 구조적으로 없앤다.
 *
 * ⚠ 이 값 공간(`VLM`·`AUTOLABEL`)은 <b>진행 축(`BatchStageItem.name` 7단계)과 다르다</b>. 표시기는
 * 여전히 단계 단위로 그리고, 조작만 묶음 단위다. 두 축을 합치지 말 것.
 *
 * ⚠ 구 값 `YOLO`·`SAM2` 를 개별 단위로 되살리지 말 것 — 그것이 보간을 묶음 밖에 남겨 두던 형태다.
 */
export const STAGE_BUNDLES = ['VLM', 'AUTOLABEL'] as const;
export type StageBundle = (typeof STAGE_BUNDLES)[number];

/**
 * 묶음이 품는 진행 축 단계 — **묶음 구성의 단일 진실원**. [@design API-198]
 *
 * 표시명(`bundleLabel`)·보간 포함 판정(`bundleRerunsInterpolation`)·실패 단계→묶음 역해석
 * (`bundleOfStage`)이 <b>전부 이 표에서 파생</b>된다. 파생하지 않고 따로 적으면 표가 둘이 되어
 * 한쪽만 갱신된다(이 저장소의 반복 결함 패턴).
 */
export const STAGE_BUNDLE_MEMBERS = {
  VLM: ['VLM'],
  AUTOLABEL: ['YOLO', 'SAM2', 'INTERPOLATE'],
} as const satisfies Record<StageBundle, readonly string[]>;

/**
 * 조작 대상 묶음인가 — **경로에 넣기 전 런타임 검증**(CWE-22/20).
 *
 * 타입만으로는 못 막는다. 이 값은 서버 응답(`skippedStages`)에서도 흘러오므로, 그대로 URL 에 이어
 * 붙이면 미지의 문자열이 경로 세그먼트가 된다. 화이트리스트 교집합만 통과시킨다.
 */
export function isStageBundle(name: string): name is StageBundle {
  return (STAGE_BUNDLES as readonly string[]).includes(name);
}

/**
 * 이 단계가 속한 묶음 — 없으면 null(비식별·마킹·프레임추출은 조작 대상이 아니다).
 *
 * 실패한 단계는 진행 축 코드로 오는데(예: `YOLO`) 조작은 묶음 단위라, 그 사이를 잇는 유일한 해석
 * 지점이다. 멤버 표에서 역으로 찾으므로 별도 매핑을 만들지 않는다.
 */
export function bundleOfStage(stageName: string): StageBundle | null {
  return (
    STAGE_BUNDLES.find((bundle) =>
      (STAGE_BUNDLE_MEMBERS[bundle] as readonly string[]).includes(stageName),
    ) ?? null
  );
}

/**
 * 이 묶음을 재수행하면 **트랙 보간이 다시 만들어지는가** — 파괴 경고의 단일 판정. [@design API-201]
 *
 * ★ 참/거짓을 손으로 적지 않고 <b>멤버 표에서 파생</b>한다. 손으로 적으면 묶음 구성이 바뀔 때 경고가
 * 따라오지 않아, 지우는 경로에 경고가 없거나 지우지 않는 경로에 경고가 붙는다(둘 다 오정보다).
 */
export function bundleRerunsInterpolation(bundle: StageBundle): boolean {
  return (STAGE_BUNDLE_MEMBERS[bundle] as readonly string[]).includes('INTERPOLATE');
}

/**
 * 배치 단건 재실행 **접수** 결과 — BE `BatchReprocessController` 응답. [@design API-167]
 *
 * ★ 200 은 **접수 사실**이지 파이프라인이 끝났다는 뜻이 아니다 — 서버는 실패 상태 선점까지만 요청
 * 안에서 처리하고 실행은 비동기로 넘긴다. 진행은 영상 상세의 단계 표시로 확인한다.
 * ⚠ 스키마는 무변경이며 달라진 것은 값의 **의미**다.
 */
export interface BatchRetryResult {
  rawSn: number;
  /** 접수 시점 배치 단계 코드(파이프라인 종료 단계가 아니다). 화면은 표시하지 않고 접수 여부만 쓴다. */
  stage: string;
}

/**
 * 되돌린 작업 묶음 재수행 **접수** 결과 — BE `POST …/batch/stages/{stage}/rerun` 응답.
 * [@design API-201]
 *
 * ★ `accepted` 는 <b>접수 여부</b>이지 파이프라인이 끝났다는 뜻이 아니다(재실행·일괄과 같은 시맨틱).
 *
 * ⚠ 구 `scope` 필드는 **폐지**됐다 — 되살리지 말 것. 묶음이 곧 범위라 고를 것이 없고, 범위를 고르게
 * 두면 보간을 뺀 부분 수행이 다시 가능해져 산출물이 어긋난다(그 갈래가 폐지된 이유다).
 */
export interface BatchStageRerunResult {
  rawSn: number;
  stage: StageBundle;
  accepted: boolean;
}

/** 작업 묶음 수동 스킵 결과 — BE `BatchStageSkipResponse` 와 1:1. [@design API-198] */
export interface BatchStageSkipResult {
  rawSn: number;
  stage: StageBundle;
  skipped: boolean;
  /** 서버가 정제해 저장한 사유(접두 포함). 화면은 재가공하지 않는다. */
  reason: string;
  skippedAt: string;
}

/**
 * 일괄 재시작 건별 결과 — BE `BatchBulkRetryResponse.Item` 과 1:1. [@design API-199]
 *
 * ★ `success` 는 **재기동을 접수했는지**이지 파이프라인이 끝났다는 뜻이 아니다.
 * `reason` 은 서버가 만든 사용자 문구다(접수됐으면 null). 화면이 상태코드로 재해석하지 않는다.
 */
export interface BatchBulkRetryItem {
  rawSn: number;
  success: boolean;
  reason: string | null;
}

/**
 * 일괄 재시작 **접수** 결과 — **부분 성공**을 그대로 표현한다. [@design API-199]
 *
 * ★ 한 건도 접수되지 못해도 HTTP 200 이다. 판정은 상태코드가 아니라 `results` 로 한다 —
 * 화면이 "요청 성공"만 보고 전부 재기동된 것처럼 알리면 사용자는 무엇이 안 됐는지 영영 모른다.
 * ★ 두 카운트도 **접수 건수**다(완료 건수가 아니다). 스키마는 무변경이며 값의 의미만 다르다.
 */
export interface BatchBulkRetryResult {
  successCount: number;
  failureCount: number;
  results: BatchBulkRetryItem[];
}

/**
 * 일괄 재시작 1회 상한 — BE `BatchBulkRetryRequest.MAX_SIZE` 와 같은 값이어야 한다.
 * 화면은 이 값으로 **미리** 안내한다(400 을 받고서야 알게 되는 동선을 피한다).
 */
export const BULK_RETRY_MAX = 100;

/**
 * 비식별 이력 1건 — BE `VideoDetailResponse.DeidentHistoryDto` 와 1:1. [req: R14]
 *
 * 원천은 `LS_DEIDENT_PROC_LOG` 1행(= 위탁 1회차)이다. 최초 배치 비식별과 재비식별 재위탁이
 * 각각 한 행을 남기므로 그 행들이 곧 이력이다.
 *
 * 리포트 집계 4종(`faceDtctCnt`~`prcsEndDt`)은 null 일 수 있다 — 외부 솔루션의 결과 리포트를
 * 조회하지 못한 회차이거나, 리포트 적재 이전에 처리된 구 회차다. 완료 자체는 성공했을 수 있으므로
 * "집계 없음"을 "실패"로 표시하면 안 된다.
 *
 * BE 는 파일 경로를 내려주지 않는다(개인정보 위치 정보 — CWE-359). FE 도 요구하지 않는다.
 */
export interface DeidentHistoryItem {
  procLogSn: number;
  /** REQUESTED | SUCCEEDED | FAILED */
  procSttsCd: string;
  /** null=배치 비식별, 'REDEIDENT'=검수완료 재비식별 */
  reqKndCd?: string | null;
  reqDt?: string | null;
  resDt?: string | null;
  faceDtctCnt?: number | null;
  noPltDtctCnt?: number | null;
  frmeCnt?: number | null;
  prcsBgngDt?: string | null;
  prcsEndDt?: string | null;
}

export interface VideoDetail extends Video {
  duration: number;
  fileSizeMb: number;
  resolution: string;
  framePreviews: FramePreview[];
  stages?: BatchStageItem[];
  createdAt?: string;
  updatedAt?: string;
  /**
   * 영상 실 프레임레이트 (BE VideoDetailResponse.fps — 진실원은 서버 VideoFpsResolver).
   *
   * 마킹 화면이 frameIndex 를 계산할 때 반드시 이 값을 쓴다. 과거 FE 가 30 을 하드코딩하는 동안
   * 서버의 마킹 상한은 실 fps 로 계산돼, 25fps 영상에서 영상 뒷부분 마킹이 400 으로 거부됐다.
   * 서버가 값을 못 내리는 경우(구 응답 등)만 MARKING_FALLBACK_FPS 로 폴백한다.
   */
  fps?: number | null;
  /**
   * 파생영상(증강 WINTER/NIGHT/RAIN · 해상도 변환본) 여부 — BE VideoDetailResponse.derivative.
   *
   * 파생영상은 비식별 누락 신고 체계 밖이라 BE 가 412 로 거부한다(원본의 비식별 결과를 복사한
   * 사본이라 재비식별 수단이 없다). 화면은 이 값으로 신고 버튼을 <b>미리</b> 비활성화해, 사용자가
   * 사유를 다 적고 제출한 뒤에야 거부를 알게 되는 동선을 없앤다.
   * 값을 못 내리는 구 응답은 undefined → 기존처럼 제출 시 412 안내로 처리된다.
   */
  derivative?: boolean;
  /**
   * 한번이라도 검수 완료된 적이 있는가 — BE `VideoDetailResponse.everApproved`. [req: P2b]
   *
   * ★ `reviewSttsCd`(현재 상태)와 <b>다른 축</b>이다. 검수 완료 뒤 작업자가 재제출하면 상태는 PENDING
   * 으로 내려가지만 이 값은 계속 true 다. 화면은 이 값으로 <b>비식별 누락 신고</b>와 <b>프레임 폐기·복원</b>
   * 을 미리 비활성화한다(BE 는 각각 412/400 으로 거부한다).
   *
   * 값을 못 내리는 구 응답은 undefined → 판정이 현재 상태로 폴백하고, 남는 창은 서버 거부가 받는다.
   */
  everApproved?: boolean;
  /**
   * 비식별 이력 — BE `VideoDetailResponse.deidentHistory`. 최신 회차가 먼저 온다. [req: R14]
   *
   * 값을 못 내리는 구 응답은 빈 배열로 정규화된다(api.getVideo) — 화면은 길이 0 을
   * "이력 없음" 으로만 다루고 undefined 분기를 따로 두지 않는다.
   */
  deidentHistory?: DeidentHistoryItem[];
  /**
   * 배치 실패 사유 — BE `VideoDetailResponse.batchFailureReason`. [@design API-043]
   *
   * ★**이미 사용자 문구로 변환된 값**이다. 화면은 그대로 보여주고 재해석·재가공하지 않는다
   * (예외 클래스명·SQL·DB 제약명 같은 내부 원문은 서버가 이미 걷어냈다 — CWE-209).
   * 실패가 아니면 null 이며, **단계를 특정할 수 없는 실패**에도 값이 담긴다(그때 `stages` 는 빈
   * 배열이라 이 값이 사용자가 얻는 유일한 단서다).
   */
  batchFailureReason?: string | null;
  /**
   * 검수자가 수동으로 건너뛴 **작업 묶음** — BE `VideoDetailResponse.skippedStages`. [@design API-043]
   *
   * ⚠ 건너뛴 묶음은 **진행 축(`stages`)에 흔적을 남기지 않고 DONE 으로 렌더**되므로 `stages` 만으로는
   * 구분할 수 없다. 건너뜀 표시와 되돌리기 조작의 노출은 이 값이 유일한 근거다.
   * 값을 못 내리는 구 응답은 빈 배열로 정규화된다(api.getVideo).
   */
  skippedStages?: StageBundle[];
}

/**
 * 마킹 frameIndex 계산의 폴백 fps — 서버가 fps 를 못 내릴 때만 사용한다.
 * BE {@code VideoFpsResolver.DEFAULT_FPS} 와 동일한 값이어야 한다(양쪽 폴백이 갈리면 다시 어긋난다).
 */
export const MARKING_FALLBACK_FPS = 30;

// SFR-06-03 — 해상도 변경 파생영상 생성 (저작도구 직접 수행, 증강 아님).
// 표준 해상도 화이트리스트 3종 (BE ResolutionPreset enum 과 1:1). 미지정/빈 목록 시 전체 생성.
// 물리 코드는 표준용어(해상도=RESL) 정합 — BE enum 화이트리스트(RESL_*)와 1:1. 구 RES_* 폐기.
export const RESOLUTION_PRESETS = ['RESL_1080P', 'RESL_720P', 'RESL_480P'] as const;
export type ResolutionPreset = (typeof RESOLUTION_PRESETS)[number];

export const RESOLUTION_PRESET_LABEL: Record<ResolutionPreset, string> = {
  RESL_1080P: '1080P (1920×1080)',
  RESL_720P: '720P (1280×720)',
  RESL_480P: '480P (854×480)',
};

// 증강 이력 카드에 노출하는 해상도 파생 뱃지 문구 — 기술코드(RESL_*) 비노출, 사용자 문구만.
// 미지의 코드는 '해상도 파생' 으로 폴백해 기술코드가 화면에 새는 것을 막는다.
export const RESOLUTION_DERIVATIVE_LABEL: Record<ResolutionPreset, string> = {
  RESL_1080P: '해상도 1080p',
  RESL_720P: '해상도 720p',
  RESL_480P: '해상도 480p',
};

export function resolutionDerivativeLabel(code: string): string {
  return (
    (RESOLUTION_DERIVATIVE_LABEL as Record<string, string>)[code] ?? '해상도 파생'
  );
}

/**
 * 해상도 파생영상의 상태 — BE `ResolutionChangeResponse.DerivativeStatus` 와 1:1.
 *
 * ★네 값은 **엔드포인트별로 나오는 집합이 다르다**(BE enum 주석이 명시한 계약).
 *  - `CREATED`     : **예약 성공**만 의미한다. 생성(POST) 응답 전용 — 확정은 비동기라 아직 안 끝났다.
 *  - `IN_PROGRESS` : 확정 진행 중. 조회(GET) 응답 전용.
 *  - `COMPLETED`   : 확정 완료(파일·라벨 산출까지). 조회(GET) 응답 전용.
 *  - `FAILED`      : 예약 또는 확정 실패. 양쪽 모두에서 나온다.
 *
 * 구 FE 선언은 `'CREATED' | 'FAILED'` 2값뿐이라 조회 응답의 확정 결과를 타입이 표현하지 못했고,
 * 그래서 확정 실패가 어느 화면에도 드러나지 않았다.
 */
export const DERIVATIVE_STATUS = {
  CREATED: 'CREATED',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
} as const;
export type DerivativeStatus =
  (typeof DERIVATIVE_STATUS)[keyof typeof DERIVATIVE_STATUS];

/**
 * 파생영상 확정이 **끝났는가**(더 기다려도 바뀌지 않는가).
 *
 * 폴링 종료 판정의 단일 원천이다 — 훅이 이 규칙을 복제하지 않는다.
 * `CREATED`(예약만 됨)·`IN_PROGRESS` 는 아직 진행 중이므로 종료가 아니다.
 * 미지의 값(구/신 BE 혼재)은 **종료로 간주**한다 — 모르는 값에서 영원히 폴링하지 않기 위한
 * fail-closed 다(무한 요청은 self-DoS 다).
 */
export function isDerivativeSettled(status: DerivativeStatus | string): boolean {
  return (
    status !== DERIVATIVE_STATUS.CREATED &&
    status !== DERIVATIVE_STATUS.IN_PROGRESS
  );
}

// SFR-06-03 — 해상도 변경(파생영상 생성) 결과 1건.
// 구 "export 프레임셋(exportSn/srcW/frameCount)" 의미 폐기.
// 신 계약: 원본에서 목표 해상도별 새 파생영상(RAW_SN)을 만들어 검수 파이프라인(PENDING)에 넣는다.
//  - rawSn    : 파생영상 ID (예약 성공 시 유효, 예약 실패 FAILED 시 BE 계약상 null)
//  - goalResCd : 목표 해상도 코드 (RESL_1080P/RESL_720P/RESL_480P)
//  - targetW/H : 목표 해상도 픽셀
//  - status   : 위 DerivativeStatus 참조(생성 응답과 조회 응답의 값 집합이 다르다)
export interface ResolutionDerivativeResult {
  rawSn: number | null;
  goalResCd: string;
  targetW: number;
  targetH: number;
  status: DerivativeStatus;
}

// BE: ResolutionChangeResponse (POST /v1/videos/{rawSn}/resolution).
// 1건 이상 CREATED → 201, 전부 FAILED → 500, 적용 프리셋 0(전부 스킵) → 400.
export interface ResolutionChangeResult {
  derivatives: ResolutionDerivativeResult[];
}

// [@design SCREEN-009] 재비식별 요청 응답 타입(`RedeidentResult`)은 두지 않는다 — 영상 상세
//   화면에서 '재비식별 요청' 진입점이 제거되면서 FE 에 그 응답을 받는 코드가 없어졌다.
//   BE 엔드포인트는 그대로이므로 진입점을 다시 두게 되면 그때 응답 계약을 다시 세운다.

export interface LabelObject {
  id: string;
  labelCode: string;
  labelName: string;
  color: string;
  confidence: number; // 0~1
  createdBy?: 'auto' | 'manual';
}

export interface FrameLabels {
  videoId: string | number;
  objects: LabelObject[];
}
