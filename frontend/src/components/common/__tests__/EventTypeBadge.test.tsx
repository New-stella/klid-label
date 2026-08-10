import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { EventTypeBadge } from '../EventTypeBadge';

// 라벨 맵은 서버 조회 결과라 여기서는 관심 밖 — 고정 맵을 주입한다(QueryClientProvider 불필요).
vi.mock('@/features/eventType/hooks', () => ({
  useEventTypeLabels: () => ({ data: { EV01000102: '침수(범람)', EV01000201: '화재' } }),
}));

// ── UI-016 회귀 가드: 장식 아이콘 재유입 방지 ────────────────────────────────
// 2026-08-10 확정으로 이벤트유형 배지의 태그 아이콘을 걷어냈다(모든 이벤트에 같은 글리프라
// 카테고리를 구분해 주지도 않던 순수 장식). 남은 축은 카테고리 색 + **한글 라벨 텍스트**이며,
// 텍스트가 사라지면 색 단독 구분이 된다. StatusBadge 에는 있던 가드가 여기엔 없어 사각이었다.
describe('EventTypeBadge', () => {
  it('★이벤트_배지는_한글_라벨_텍스트만_렌더하고_아이콘은_폐지됐다', () => {
    const cases = [
      { input: 'EV01000102', label: '침수(범람)' },
      { input: 'EV01000201', label: '화재' },
      // 맵에 없는 값은 원문 폴백 — 미등록 코드라도 화면에서 사라지지 않는다.
      { input: '쓰러짐', label: '쓰러짐' },
      { input: 'INTRUSION', label: 'INTRUSION' },
    ];

    cases.forEach(({ input, label }) => {
      const { container, unmount } = render(<EventTypeBadge eventType={input} />);
      const badge = screen.getByText(label);

      // 텍스트 라벨 존재 — 정보 전달의 단독 축
      expect((badge.textContent ?? '').trim()).toBe(label);
      // 장식 아이콘 부재
      expect(container.querySelector('svg')).toBeNull();
      unmount();
    });
  });

  it('빈_입력은_빈_배지로_유지되고_아이콘도_생기지_않는다', () => {
    // 아이콘이 남아 있었다면 빈 입력에서 **글리프만 뜬 배지**가 됐다.
    const { container } = render(<EventTypeBadge eventType="" />);
    expect(container.querySelector('svg')).toBeNull();
    expect((container.textContent ?? '').trim()).toBe('');
  });
});
