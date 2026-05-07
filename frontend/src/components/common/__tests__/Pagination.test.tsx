import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Pagination } from '../Pagination';

describe('Pagination', () => {
  it('Pagination_총_245건_페이지_이동', async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    render(
      <Pagination page={0} size={10} totalElements={245} onPageChange={onPageChange} />,
    );
    expect(screen.getByText(/1-10 \/ 총 245건/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '2페이지' }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('Pagination_첫_페이지에서_이전_disabled', () => {
    render(
      <Pagination page={0} size={10} totalElements={100} onPageChange={() => {}} />,
    );
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '첫 페이지' })).toBeDisabled();
  });

  it('Pagination_마지막_페이지에서_다음_disabled', () => {
    render(
      <Pagination page={9} size={10} totalElements={100} onPageChange={() => {}} />,
    );
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '마지막 페이지' })).toBeDisabled();
  });

  it('Pagination_aria_current_page_현재_페이지에_표시', () => {
    render(
      <Pagination page={2} size={10} totalElements={100} onPageChange={() => {}} />,
    );
    const cur = screen.getByRole('button', { name: '3페이지' });
    expect(cur).toHaveAttribute('aria-current', 'page');
  });
});
