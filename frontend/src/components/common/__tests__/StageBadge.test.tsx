import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { StageBadge } from '../StageBadge';

// ── UI-017 회귀 가드: 장식 아이콘 재유입 방지 ────────────────────────────────
// 2026-08-10 확정으로 단계 배지의 아이콘을 걷어냈다. 색상 톤은 그대로 남아 있으므로,
// 텍스트가 사라지거나 아이콘이 되살아나면 그 배지는 다시 "색 + 글리프"로 상태를 말하게 된다
// (색 단독 구분 금지 · 같은 의미가 두 표현으로 갈리는 드리프트).
// StatusBadge 에는 같은 축의 가드가 있었는데 이 컴포넌트에는 없어 사각이었다.
describe('StageBadge', () => {
  it('★단계_배지는_한글_라벨_텍스트만_렌더하고_아이콘은_폐지됐다', () => {
    const cases: { stage: string; status?: string; label: string }[] = [
      { stage: 'COMPLETED', status: 'COMPLETED', label: '완료' },
      { stage: 'FAILED', status: 'FAILED', label: '실패' },
      { stage: 'FRAME_EXTRACT', status: 'IN_PROGRESS', label: '프레임추출' },
      { stage: 'DEIDENTIFY', label: '비식별화' },
      { stage: 'VLM', label: 'VLM' },
    ];

    cases.forEach(({ stage, status, label }) => {
      const { container, unmount } = render(<StageBadge stage={stage} status={status} />);
      const badge = screen.getByText(label);

      // 텍스트 라벨 존재 — 정보 전달의 단독 축
      expect((badge.textContent ?? '').trim()).toBe(label);
      // 장식 아이콘 부재
      expect(container.querySelector('svg')).toBeNull();
      // 색상 톤은 유지 (텍스트 위에 얹힌 보조 축)
      expect(badge.className).toMatch(
        /bg-(success|danger|info|warning)\/10|bg-purple-100/,
      );
      unmount();
    });
  });

  it('매핑에_없는_단계는_원문을_라벨로_보여준다_빈_배지가_되지_않는다', () => {
    const { container } = render(<StageBadge stage="SOME_NEW_BE_STAGE" />);
    const badge = screen.getByText('SOME_NEW_BE_STAGE');
    expect(badge).toBeInTheDocument();
    // 아이콘이 없는 지금은 텍스트가 유일한 정보 전달 축이라 비면 안 된다.
    expect((badge.textContent ?? '').trim().length).toBeGreaterThan(0);
    expect(container.querySelector('svg')).toBeNull();
  });

  it('기술_모델명은_노출하지_않는다', () => {
    const { container, unmount } = render(<StageBadge stage="YOLO" />);
    expect(screen.getByText('AI 탐지')).toBeInTheDocument();
    expect(container.textContent).not.toContain('YOLO');
    unmount();

    const sam2 = render(<StageBadge stage="SAM2" />);
    expect(screen.getByText('AI 분할')).toBeInTheDocument();
    expect(sam2.container.textContent).not.toContain('SAM2');
  });
});
