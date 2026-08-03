// 대시보드 도메인 타입

/**
 * 이벤트 분포 항목의 식별자 — 관제 카테고리 키(EVNT_CLS_CD+EVNT_CTGRY_CD, 예 "020002").
 * 하드코딩 약어 union(FALL 등)을 폐지하고 BE 가 내려주는 categoryKey 문자열을 그대로 사용한다.
 */
export type EventTypeCd = string;

export interface EventDistribution {
  /** 카테고리 키(categoryKey). BE EventDistributionItem.eventTypeCd 와 1:1. */
  eventTypeCd: string;
  /** 카테고리 한글명 (BE 제공). */
  label: string;
  count: number;
}

export interface MyTask {
  pendingCount: number;
  inProgressCount: number;
  reviewPendingCount: number;
  rejectedCount: number;
}

export interface Notice {
  id: number;
  title: string;
  pinned: boolean;
  createdAt: string;
}

export interface DashboardSummary {
  // 역할별 KPI (UI/UX §4-3)
  // WORKER: 4 KPI (처리 대기 / 처리 완료 / 내 작업 / 반려 건수)
  // REVIEWER: 3 KPI (처리 대기 / 처리 완료 / 반려 건수 — 내 작업 제외)
  pendingCount: number; // 처리 대기
  completedCount: number; // 처리 완료
  myTaskCount: number; // 내 작업 (WORKER 전용)
  rejectedCount: number; // 반려 건수

  // 누적 카드 2종 — 전체 기준 (검수 여부 무관, 미검수 영상 포함)
  cumulativeImageCount: number; // 목표 10만장
  cumulativeVideoCount: number; // 목표 5,000건

  // 6종 이벤트 분포 (영상 단위) — 전체 기준
  eventDistribution: EventDistribution[];

  // 6종 이벤트 분포 (이미지/프레임 단위 — "이미지 데이터 개수" 카드용) — 전체 기준
  imageDistribution?: EventDistribution[];

  // ── 검수완료(APPROVED) 기준 ──
  // 검수 승인 = 작업 완료 = 학습데이터 확정 정책상 카드의 "주 수치"는 아래 값이고,
  // 위 전체 기준 값은 보조(전체/완료율)로만 병기한다.
  // optional 로 두지 않는다 — `?? 0` 폴백이 미수신을 실데이터 0 으로 오인시키기 때문.
  approvedImageCount: number;
  approvedVideoCount: number;
  approvedEventDistribution: EventDistribution[];
  approvedImageDistribution: EventDistribution[];

  // 내 작업 현황 (WORKER 전용)
  myTask: MyTask;

  // 공지사항
  notices: Notice[];
}
