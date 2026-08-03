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
  /** 전체 건수. */
  total: number;
  /** 완료율(%) 정수. 산출 불가(검수완료 미수신) 면 null. */
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

/** 카드 주 수치 표기 — 미수신이면 0 이 아니라 '-'. */
export function formatApprovedValue(ratio: ApprovedRatio): string {
  return ratio.approved === null ? '-' : ratio.approved.toLocaleString('ko-KR');
}

export interface ApprovedRatioNoteProps {
  ratio: ApprovedRatio;
  /** 단위 — 이미지 '장' / 영상 '건'. */
  unit: string;
}

export function ApprovedRatioNote({ ratio, unit }: ApprovedRatioNoteProps) {
  const total = `${ratio.total.toLocaleString('ko-KR')}${unit}`;
  const text =
    ratio.rate === null
      ? `전체 ${total} · 검수완료 집계를 불러오지 못했습니다`
      : `검수완료 기준 · 전체 ${total} (완료율 ${ratio.rate}%)`;

  return <p className="text-xs text-gray-500">{text}</p>;
}
