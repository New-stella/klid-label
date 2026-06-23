import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { StatusBadge, type BadgeStatus } from '../StatusBadge';

describe('StatusBadge', () => {
  const statuses: BadgeStatus[] = [
    'BATCH_PROCESSING',
    'BATCH_COMPLETED',
    'BATCH_FAILED',
    'PENDING',
    'MARKING_READY',
    'IN_PROGRESS',
    'REVIEW_PENDING',
    'REVIEWING',
    'COMPLETED',
    'REJECTED',
  ];

  it('StatusBadge_9종_상태별_색상_렌더', () => {
    statuses.forEach((s) => {
      const { unmount } = render(<StatusBadge status={s} />);
      const el = screen.getByText(/.*/, { selector: `[data-status="${s}"]` });
      expect(el).toBeInTheDocument();
      // 색상 토큰 클래스 검증 — bg-* 또는 text-* 가 매핑돼야 함 (mock tonal pill)
      expect(el.className).toMatch(
        /bg-(blue|green|yellow|red|gray|purple)-(50|100|200)|text-(blue|green|yellow|red|gray|purple)-(600|700|800)/,
      );
      unmount();
    });
  });

  it('StatusBadge_BATCH_FAILED_은_danger_색상', () => {
    render(<StatusBadge status="BATCH_FAILED" />);
    const el = screen.getByText('배치 실패');
    // danger 톤은 red 계열로 표현 (mock tonal pill)
    expect(el.className).toMatch(/text-red-700|bg-red-100/);
  });

  it('StatusBadge_MARKING_READY_는_마킹_대기_라벨_렌더', () => {
    render(<StatusBadge status="MARKING_READY" />);
    expect(screen.getByText('마킹 대기')).toBeInTheDocument();
  });

  it('StatusBadge_PROCESSING_는_처리중_라벨_렌더', () => {
    // Bug 2 — 마킹 완료~배치 완료 구간 '처리중' 표시
    render(<StatusBadge status="PROCESSING" />);
    const el = screen.getByText('처리중');
    expect(el).toBeInTheDocument();
    expect(el.className).toMatch(/bg-blue-100|text-blue-700/);
  });

  it('StatusBadge_FAILED_는_실패_라벨_렌더', () => {
    // Bug 2 — 배치 실패 시 '실패' 표시 (MARKING_READY 고착 금지)
    render(<StatusBadge status="FAILED" />);
    const el = screen.getByText('실패');
    expect(el).toBeInTheDocument();
    expect(el.className).toMatch(/bg-red-100|text-red-700/);
  });

  it('StatusBadge_커스텀_label_사용', () => {
    render(<StatusBadge status="COMPLETED" label="완료됨" />);
    expect(screen.getByText('완료됨')).toBeInTheDocument();
  });
});
