/**
 * 누적 카드의 "검수완료 기준" 보조 라인 — 대시보드(SCR-DASH-001)와
 * 전체 구축 현황(SCR-STAT-002)이 동일 문구·동일 계산을 공유한다.
 *
 * 정책: 검수 승인 = 작업 완료 = 학습데이터 확정. 따라서 카드의 주 수치는 검수완료 건수이고
 * 전체 건수와 완료율은 이 보조 라인으로만 병기한다.
 *
 * 회귀 방지 (UI/UX §4-11): 완료율은 반드시 텍스트로만 표기한다.
 * ProgressBar / role="progressbar" / `<progress>` 는 누적 카드 영역에 렌더하지 않는다.
 */

export interface ApprovedRatio {
  /** 검수완료 건수. BE 미수신(구버전) 이면 null. */
  approved: number | null;
  /**
   * 전체 건수. BE 미수신이면 <b>null</b> — 0 으로 단정하지 않는다.
   *
   * ⚠ {@link computeApprovedRatio} 는 기존 계약대로 미수신을 0 으로 채운다(누적 카드 2종이
   * 그 동작에 의존한다). null 이 되는 것은 {@link approvedRatioWithServerRate} 경로뿐이다.
   */
  total: number | null;
  /** 완료율(%) 정수. 산출 불가(검수완료·완료율 미수신) 면 null. */
  rate: number | null;
}

/**
 * 검수완료/전체 비율 계산.
 *
 * - 분모가 0 이면 완료율은 0 — NaN(0/0)·Infinity 를 화면에 노출하지 않는다.
 * - 검수완료가 전체보다 큰 이상 데이터는 클램프하지 않는다 (100% 초과를 그대로 노출해 이상을 드러낸다).
 * - 검수완료 값이 수(number)가 아니면 0 으로 대체하지 않고 null 로 둔다 — 미수신을 실데이터 0 으로
 *   오인시키지 않기 위함.
 */
export function computeApprovedRatio(
  approved: number | undefined,
  total: number | undefined,
): ApprovedRatio {
  const safeTotal = Number.isFinite(total) ? (total as number) : 0;

  if (!Number.isFinite(approved)) {
    return { approved: null, total: safeTotal, rate: null };
  }

  const safeApproved = approved as number;
  return {
    approved: safeApproved,
    total: safeTotal,
    rate: safeTotal > 0 ? Math.round((safeApproved / safeTotal) * 100) : 0,
  };
}

/**
 * 검수완료/전체 비율 — <b>완료율은 서버가 내려준 값을 그대로 채택</b>한다.
 *
 * 화면이 approved/total 을 다시 나누지 않는 이유: 분자·분모의 정의가 서버 쪽에서 바뀌면
 * (예: 반려를 어느 집합에 넣는가) 화면이 재유도한 값이 조용히 어긋난다. 작업자 통계
 * (API-056)의 completionRate 가 그 축이며 단위는 <b>비율(0.0~1.0)</b>이다.
 *
 * - 검수완료 값이 수가 아니면 null(미수신) — 0 으로 대체하지 않는다.
 * - 전체 값이 수가 아니면 null(미수신) — 0 으로 <b>단정하지 않는다</b>.
 * - 서버 비율이 수가 아니면 완료율 null — 화면이 대신 계산하지 않는다.
 * - 100% 초과는 클램프하지 않는다(데이터 이상을 그대로 드러낸다).
 *
 * @param approved 검수완료 건수
 * @param total    전체 건수
 * @param rate     서버가 내려준 완료율 <b>비율(0.0~1.0)</b>
 */
export function approvedRatioWithServerRate(
  approved: number | undefined,
  total: number | undefined,
  rate: number | undefined,
): ApprovedRatio {
  return {
    approved: Number.isFinite(approved) ? (approved as number) : null,
    total: Number.isFinite(total) ? (total as number) : null,
    rate: Number.isFinite(rate) ? Math.round((rate as number) * 100) : null,
  };
}

/** 카드 주 수치 표기 — 미수신이면 0 이 아니라 '-'. */
export function formatApprovedValue(ratio: ApprovedRatio): string {
  return ratio.approved === null ? '-' : ratio.approved.toLocaleString('ko-KR');
}

export interface ApprovedRatioNoteProps {
  ratio: ApprovedRatio;
  /** 단위 — 이미지 '장' / 영상 '건' / 라벨 '개'. */
  unit: string;
  /**
   * 완료율 병기 생략 (선택).
   *
   * 비율 축이 성립하지 않는 카드에 쓴다 — 작업자 통계의 "총 라벨 수" 카드가 그렇다.
   * 그 카드의 주 수치는 <b>라벨</b> 분량인데 서버가 내려주는 completionRate 는 <b>영상 건수</b>의
   * 비율이라 그대로 쓸 수 없고, 라벨 단위 비율은 서버가 내려주지 않으므로 화면에서 지어내지
   * 않는다(사양 SCREEN-020 이 명시적으로 금지).
   */
  omitRate?: boolean;
}

export function ApprovedRatioNote({ ratio, unit, omitRate = false }: ApprovedRatioNoteProps) {
  const total = ratio.total === null ? null : `${ratio.total.toLocaleString('ko-KR')}${unit}`;
  let text: string;
  if (total === null) {
    // 전체가 미수신이면 '전체 0건' 같은 거짓 수치를 적지 않는다.
    text = '집계를 불러오지 못했습니다';
  } else if (ratio.approved === null) {
    text = `전체 ${total} · 검수완료 집계를 불러오지 못했습니다`;
  } else if (omitRate) {
    text = `검수완료 기준 · 전체 ${total}`;
  } else if (ratio.rate === null) {
    // 검수완료는 왔는데 완료율만 미수신 — 화면이 대신 나눠 구하지 않는다.
    text = `검수완료 기준 · 전체 ${total}`;
  } else {
    text = `검수완료 기준 · 전체 ${total} (완료율 ${ratio.rate}%)`;
  }

  // 각주(집계 근거 안내) — DS-001 ladder `caption`(14px/w400). 크기는 구 `text-xs` 와 동일.
  return <p className="text-caption text-gray-500">{text}</p>;
}
