// Phase 5 — 포털 업로드 자산 FE 타입 (BE portal.dto.* 미러).
// BE: kr.co.cudo.authoring.portal.dto.PortalUploadResponse / PortalUploadDetailResponse /
//     PortalUploadFrameResponse. 저장 경로(FILE_PATH_NM) 등 내부 경로는 노출되지 않는다(CWE-209).

/** 자산 종류 — BE LsPortalUld.TYPE_IMAGE/TYPE_VIDEO. */
export const PortalUploadType = {
  IMAGE: 'IMAGE',
  VIDEO: 'VIDEO',
} as const;
export type PortalUploadType = (typeof PortalUploadType)[keyof typeof PortalUploadType];

/**
 * 자산 처리 상태 — BE LsPortalUld.STTS_*.
 * UPLOADED → PROCESSING → READY | FAILED. 이미지는 업로드 즉시 READY.
 */
export const PortalUploadStatus = {
  UPLOADED: 'UPLOADED',
  PROCESSING: 'PROCESSING',
  READY: 'READY',
  FAILED: 'FAILED',
} as const;
export type PortalUploadStatus = (typeof PortalUploadStatus)[keyof typeof PortalUploadStatus];

/** 후처리 진행 중(비종결) 상태 — 폴링 대상. */
export const IN_PROGRESS_STATUSES: readonly string[] = [
  PortalUploadStatus.UPLOADED,
  PortalUploadStatus.PROCESSING,
];

/**
 * 업로드 자산 1행 (목록/업로드 결과 공용) — BE PortalUploadResponse.
 *
 * `failRsnCn` 은 BE 목록/상세 DTO 가 FAILED 상태에서만 내려주는 실패 사유(예외 클래스 단순명만 —
 * 내부 경로/PII 미포함). optional 유지 — 없으면 화면은 일반 실패 문구로 대체한다.
 */
export interface PortalUpload {
  uldSn: number;
  uldTypeCd: string;
  orgnlFileNm: string;
  fileSz: number;
  mimeTypeNm: string;
  uldSttsCd: string;
  frmeCnt: number | null;
  frmeSn: number | null;
  failRsnCn?: string | null;
  regDt: string;
}

/** 프레임 요약 1행 — BE PortalUploadFrameResponse. */
export interface PortalUploadFrame {
  uldFrmeSn: number;
  uldSn: number;
  frmeNo: number;
  regDt: string;
}

/** 자산 상세 — BE PortalUploadDetailResponse. */
export interface PortalUploadDetail {
  uldSn: number;
  uldTypeCd: string;
  orgnlFileNm: string;
  fileSz: number;
  mimeTypeNm: string;
  uldSttsCd: string;
  frmeCnt: number | null;
  vdoLenSec: number | null;
  fps: number | null;
  regDt: string;
  mdfcnDt: string | null;
  frames: PortalUploadFrame[];
}
