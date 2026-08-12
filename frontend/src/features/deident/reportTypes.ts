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
   * 신고자 표시명 — `LS_ACNT_USER.USER_NM`(V169 로 저작도구 소유 마스터로 이관).
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

/**
 * 재비식별 산출물 후보 1건 — BE `GET /v1/deident-reports/{rprtSn}/deident-candidates`.
 *
 * 외부 비식별 솔루션은 결과를 원본과 **다른 이름**(예: `{원본stem}-mask{ext}`)으로 만들 수 있어
 * 서버가 어느 파일이 재비식별 결과인지 단정할 수 없다. 그래서 사람이 목록에서 고른다.
 *
 * ★ **내부 저장 경로는 응답에 없다**(파일명뿐). 화면에도 경로를 표시하지 않는다.
 */
export interface DeidentCandidate {
  /** 파일명(basename). 해소 요청에 그대로 실어 보낸다. */
  fileName: string;
  /** 파일 크기(바이트) — 어느 것이 새 산출물인지 사람이 판단할 근거. */
  sizeBytes: number;
  /** 파일 수정 시각(ISO-8601) — 위와 동일. */
  modifiedAt: string;
  /**
   * 해소에 쓸 수 있는가(무결성 + 신고 이후 생성 조건 통과). false 면 서버가 409 로 거부하므로
   * 화면에서도 선택할 수 없게 한다.
   */
  eligible: boolean;
  /** 현재 시스템이 이 영상의 비식별본으로 쓰고 있는 파일인가. */
  current: boolean;
}
