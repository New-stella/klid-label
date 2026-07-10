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
      // 색상 토큰 클래스 검증 — KRDS 시맨틱 토큰 또는 gray/purple 카테고리 매핑
      expect(el.className).toMatch(
        /bg-(success|danger|warning|info)\/10|text-(success|danger|warning|info)|bg-(gray|purple)-(50|100|200)|text-(gray|purple)-(600|700|800)/,
      );
      unmount();
    });
  });

  it('StatusBadge_BATCH_FAILED_은_danger_색상', () => {
    render(<StatusBadge status="BATCH_FAILED" />);
    const el = screen.getByText('배치 실패');
    // danger 톤은 KRDS danger 토큰으로 표현 (tonal pill)
    expect(el.className).toMatch(/text-danger|bg-danger/);
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
    expect(el.className).toMatch(/bg-info|text-info/);
  });

  it('StatusBadge_FAILED_는_실패_라벨_렌더', () => {
    // Bug 2 — 배치 실패 시 '실패' 표시 (MARKING_READY 고착 금지)
    render(<StatusBadge status="FAILED" />);
    const el = screen.getByText('실패');
    expect(el).toBeInTheDocument();
    expect(el.className).toMatch(/bg-danger|text-danger/);
  });

  it('StatusBadge_커스텀_label_사용', () => {
    render(<StatusBadge status="COMPLETED" label="완료됨" />);
    expect(screen.getByText('완료됨')).toBeInTheDocument();
  });

  it('StatusBadge_상태별_색과_아이콘_텍스트_병기', () => {
    // KRDS: 색만으로 상태 구분 금지 — 색+아이콘(svg)+텍스트 3중 병기 검증
    const cases: BadgeStatus[] = [
      'COMPLETED',
      'FAILED',
      'IN_PROGRESS',
      'REVIEWING',
      'PENDING',
    ];
    cases.forEach((s) => {
      const { container, unmount } = render(<StatusBadge status={s} />);
      const badge = container.querySelector(`[data-status="${s}"]`) as HTMLElement;
      // 아이콘(svg) 존재
      expect(badge.querySelector('svg')).toBeInTheDocument();
      // 텍스트 라벨 존재
      expect((badge.textContent ?? '').trim().length).toBeGreaterThan(0);
      // 색상 토큰 유지 (KRDS 시맨틱 토큰 tonal pill 또는 gray/purple 카테고리)
      expect(badge.className).toMatch(
        /bg-(success|danger|warning|info)\/10|bg-(gray|purple)-(50|100|200)/,
      );
      unmount();
    });
  });
});
