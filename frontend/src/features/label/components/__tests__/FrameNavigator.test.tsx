import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { FrameNavigator } from '../FrameNavigator';

describe('FrameNavigator', () => {
  it('FrameNavigator_44px', () => {
    // given: 단독 아이콘 네비 버튼(이전/다음)
    render(
      <FrameNavigator currentIndex={0} total={5} onPrev={vi.fn()} onNext={vi.fn()} />,
    );

    // when: 이전/다음 프레임 버튼을 조회
    const prev = screen.getByLabelText('이전 프레임');
    const next = screen.getByLabelText('다음 프레임');

    // then: KRDS 최소 터치 타깃 44x44px(h-11 w-11) 보장
    expect(prev.className).toMatch(/\bh-11\b/);
    expect(prev.className).toMatch(/\bw-11\b/);
    expect(next.className).toMatch(/\bh-11\b/);
    expect(next.className).toMatch(/\bw-11\b/);
  });
});
