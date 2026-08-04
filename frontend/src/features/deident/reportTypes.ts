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
}

export interface ListDeidentReportsParams {
  status?: DeidentReportStatus;
  page?: number;
  size?: number;
}
