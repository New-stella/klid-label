// 비식별 신고 관리(REVIEWER) 도메인 타입 — BE GET /v1/deident-reports.

export const DeidentReportStatus = {
  OPEN: 'OPEN',
  RESOLVED: 'RESOLVED',
  DISMISSED: 'DISMISSED',
} as const;
export type DeidentReportStatus =
  (typeof DeidentReportStatus)[keyof typeof DeidentReportStatus];

/**
 * 신고 단계 — 신고가 접수된 화면. 해소 후 작업 재개 지점이 이 값으로 갈린다.
 * - MARKING  : 마킹 화면 신고 → 해소 후 마킹부터 다시
 * - LABELING : 라벨링 화면 신고 → 해소 후 프레임 이미지만 재추출(마킹·라벨 유지)
 * - null     : 이 기능 이전에 접수된 신고(기록 없음) → 해소해도 단계별 재개가 없다
 */
export const DeidentReportStage = {
  MARKING: 'MARKING',
  LABELING: 'LABELING',
} as const;
export type DeidentReportStage =
  (typeof DeidentReportStage)[keyof typeof DeidentReportStage];

/** 신고 목록 행 — BE DeidentReportListResponse 와 정합. */
export interface DeidentReportRow {
  rprtSn: number;
  rawSn: number;
  /** 신고자 원값 — `USER_NO`. 표시는 reporterName 우선. */
  reporterNo: number | null;
  /**
   * 신고자 표시명 — `MNG_ACCT_USER.USER_NM`.
   * 신고자 번호가 없거나(레거시 행) 사용자 마스터에 없으면(탈퇴·관제 계정 삭제) null.
   * 화면은 이 값을 우선 표시하고 없을 때만 reporterNo 로 폴백한다.
   */
  reporterName: string | null;
  reason: string;
  status: DeidentReportStatus;
  reportDt: string; // ISO-8601
  resolvedDt: string | null;
  /** 신고 단계 — 레거시 신고는 null(단계 미상). 구 응답 호환을 위해 optional. */
  stage?: DeidentReportStage | null;
}

export interface ListDeidentReportsParams {
  status?: DeidentReportStatus;
  page?: number;
  size?: number;
}
