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

// SFR-06-03 — 해상도 변경(파생영상 생성) 결과 1건.
// 구 "export 프레임셋(exportSn/srcW/frameCount)" 의미 폐기.
// 신 계약: 원본에서 목표 해상도별 새 파생영상(RAW_SN)을 만들어 검수 파이프라인(PENDING)에 넣는다.
//  - rawSn    : 생성된 파생영상 ID (CREATED 일 때 유효, FAILED 시 BE 계약상 null)
//  - goalResCd : 목표 해상도 코드 (RESL_1080P/RESL_720P/RESL_480P)
//  - targetW/H : 목표 해상도 픽셀
//  - status   : CREATED(검수 대기 파생영상 생성) | FAILED(해당 프리셋 실패)
export interface ResolutionDerivativeResult {
  rawSn: number | null;
  goalResCd: string;
  targetW: number;
  targetH: number;
  status: 'CREATED' | 'FAILED';
}

// BE: ResolutionChangeResponse (POST /v1/videos/{rawSn}/resolution).
// 1건 이상 CREATED → 201, 전부 FAILED → 500, 적용 프리셋 0(전부 스킵) → 400.
export interface ResolutionChangeResult {
  derivatives: ResolutionDerivativeResult[];
}

// SC-009 — 영상 재비식별 요청 (POST /v1/videos/{rawSn}/redeident, REVIEWER).
// BE 가 비식별 재처리를 비동기 접수 → 200/202 + status='ACCEPTED'.
export interface RedeidentResult {
  rawSn: number;
  procLogSn?: number;
  kpstPrjId?: number;
  status: string;
}

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
