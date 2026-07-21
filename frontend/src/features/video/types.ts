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

export interface BatchStageItem {
  name: string; // 'FRAME_EXTRACT' | 'DEIDENTIFY' | 'YOLO' | 'SAM2' | 'VLM_VERIFY'
  status: BatchStageStatus;
  progress: number;
}

export interface VideoDetail extends Video {
  duration: number;
  fileSizeMb: number;
  resolution: string;
  framePreviews: FramePreview[];
  stages?: BatchStageItem[];
  createdAt?: string;
  updatedAt?: string;
}

// SFR-06-03 — 해상도 변경 파생영상 생성 (저작도구 직접 수행, 증강 아님).
// 표준 해상도 화이트리스트 3종 (BE ResolutionPreset enum 과 1:1). 미지정/빈 목록 시 전체 생성.
export const RESOLUTION_PRESETS = ['RES_1080P', 'RES_720P', 'RES_480P'] as const;
export type ResolutionPreset = (typeof RESOLUTION_PRESETS)[number];

export const RESOLUTION_PRESET_LABEL: Record<ResolutionPreset, string> = {
  RES_1080P: '1080P (1920×1080)',
  RES_720P: '720P (1280×720)',
  RES_480P: '480P (854×480)',
};

// SFR-06-03 — 해상도 변경(파생영상 생성) 결과 1건.
// 구 "export 프레임셋(exportSn/srcW/frameCount)" 의미 폐기.
// 신 계약: 원본에서 목표 해상도별 새 파생영상(RAW_SN)을 만들어 검수 파이프라인(PENDING)에 넣는다.
//  - rawSn    : 생성된 파생영상 ID (CREATED 일 때 유효, FAILED 시 BE 계약상 null)
//  - goalResCd : 목표 해상도 코드 (RES_1080P/RES_720P/RES_480P)
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
