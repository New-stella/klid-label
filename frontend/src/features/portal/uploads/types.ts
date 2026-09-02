// Phase 5 — 포털 업로드 자산 FE 타입 (BE portal.dto.* 미러).
// BE: kr.co.cudo.authoring.portal.dto.PortalUploadResponse / PortalUploadDetailResponse /
//     PortalUploadFrameResponse. 저장 경로(FILE_PATH_NM) 등 내부 경로는 노출되지 않는다(CWE-209).

/**
 * 자산 종류 — BE LsPortalUld.TYPE_IMAGE/TYPE_VIDEO.
 *
 * ★ **`IMAGE` 를 지우지 말 것.** 신규 접수는 영상뿐이지만, 이 값역은 **이미 적재된 행을 읽기
 *   위해** 남긴다(지우면 계약이 데이터를 표현하지 못한다). 값이 남아 있다는 것이 이미지 접수
 *   수단이 있다는 뜻은 아니다 — 접수 자리는 화면에서 폐기됐다.
 */
export const PortalUploadType = {
  IMAGE: 'IMAGE',
  VIDEO: 'VIDEO',
} as const;
export type PortalUploadType = (typeof PortalUploadType)[keyof typeof PortalUploadType];

/**
 * 자산 처리 상태 — BE LsPortalUld.STTS_*.
 * UPLOADED → PROCESSING → READY | FAILED.
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
  /**
   * 보존기간 만료 예정 시각. 처리 중(UPLOADED·PROCESSING)이면 삭제 대상이 아니라 `null` 이다.
   *
   * ★ 서버가 **조회 시점에 계산하는 파생값**이다(저장되지 않는다) — 보존기간 설정이 바뀌면 다음
   * 조회부터 값이 달라지므로 화면이 보관하거나 스스로 계산하지 않고 받은 값을 그대로 쓴다.
   */
  expiresAt: string | null;
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
  /** 보존기간 만료 예정 시각 — 목록과 동일 판정(처리 중이면 `null`). */
  expiresAt: string | null;
}
