import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ReviewActionBar } from '../components/ReviewActionBar';

describe('ReviewActionBar', () => {
  it('반려_버튼_클릭시_onReject_콜백_호출', async () => {
    const onReject = vi.fn();
    const user = userEvent.setup();

    render(
      <ReviewActionBar
        status="REVIEWING"
        onApprove={() => {}}
        onReject={onReject}
      />,
    );

    const rejectBtn = screen.getByTestId('review-action-reject');
    await user.click(rejectBtn);

    expect(onReject).toHaveBeenCalledTimes(1);
  });

  it('승인_버튼_클릭시_onApprove_콜백_호출', async () => {
    const onApprove = vi.fn();
    const user = userEvent.setup();

    render(
      <ReviewActionBar
        status="REVIEWING"
        onApprove={onApprove}
        onReject={() => {}}
      />,
    );

    const approveBtn = screen.getByTestId('review-action-approve');
    await user.click(approveBtn);

    expect(onApprove).toHaveBeenCalledTimes(1);
  });

  it('검수상태_COMPLETED_시_액션버튼_disabled', () => {
    render(
      <ReviewActionBar
        status="COMPLETED"
        onApprove={() => {}}
        onReject={() => {}}
      />,
    );

    expect(screen.getByTestId('review-action-approve')).toBeDisabled();
    expect(screen.getByTestId('review-action-reject')).toBeDisabled();
    expect(screen.getByTestId('review-action-bar-message')).toHaveTextContent(
      '이미 승인 처리된 검수입니다.',
    );
  });

  it('검수상태_REJECTED_시_액션버튼_disabled', () => {
    render(
      <ReviewActionBar
        status="REJECTED"
        onApprove={() => {}}
        onReject={() => {}}
      />,
    );

    expect(screen.getByTestId('review-action-approve')).toBeDisabled();
    expect(screen.getByTestId('review-action-reject')).toBeDisabled();
  });

  it('isPending_true_시_버튼_disabled', () => {
    render(
      <ReviewActionBar
        status="REVIEWING"
        onApprove={() => {}}
        onReject={() => {}}
        isPending
      />,
    );

    expect(screen.getByTestId('review-action-approve')).toBeDisabled();
    expect(screen.getByTestId('review-action-reject')).toBeDisabled();
  });
});
