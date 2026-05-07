import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { EmptyState } from '../EmptyState';

describe('EmptyState', () => {
  it('EmptyState_action_버튼_클릭_핸들러_호출', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <EmptyState
        title="검색 결과 없음"
        message="다른 키워드를 시도해보세요"
        action={{ label: '초기화', onClick }}
      />,
    );
    await user.click(screen.getByRole('button', { name: '초기화' }));
    expect(onClick).toHaveBeenCalled();
  });
});
