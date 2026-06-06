// 비식별 신고 관리(REVIEWER) 도메인 타입 — BE GET /v1/deident-reports.

export const DeidentReportStatus = {
  OPEN: 'OPEN',
  RESOLVED: 'RESOLVED',
  DISMISSED: 'DISMISSED',
} as const;
export type DeidentReportStatus =
  (typeof DeidentReportStatus)[keyof typeof DeidentReportStatus];

/** 신고 목록 행 — BE DeidentReportListResponse 와 정합. */
export interface DeidentReportRow {
  rprtSn: number;
  rawSn: number;
  reporterNo: number | null;
  reason: string;
  status: DeidentReportStatus;
  reportDt: string; // ISO-8601
  resolvedDt: string | null;
}

export interface ListDeidentReportsParams {
  status?: DeidentReportStatus;
  page?: number;
  size?: number;
}
