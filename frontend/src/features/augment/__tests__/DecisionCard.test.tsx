import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { DecisionCard } from '../components/DecisionCard';

describe('DecisionCard', () => {
  it('활용_결정_PENDING에서_채택_거부_버튼_노출', () => {
    render(
      <DecisionCard
        status="PENDING"
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );
    expect(screen.getByRole('button', { name: '채택' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '거부' })).toBeInTheDocument();
  });

  it('PENDING_채택_버튼_클릭시_onAccept_호출', async () => {
    const user = userEvent.setup();
    const onAccept = vi.fn();
    render(
      <DecisionCard
        status="PENDING"
        onAccept={onAccept}
        onReject={() => {}}
      />,
    );
    await user.click(screen.getByRole('button', { name: '채택' }));
    expect(onAccept).toHaveBeenCalledOnce();
  });

  it('PENDING_거부_버튼_클릭시_RejectReasonModal_노출_사유_입력_후_확정', async () => {
    const user = userEvent.setup();
    const onReject = vi.fn();
    render(
      <DecisionCard
        status="PENDING"
        onAccept={() => {}}
        onReject={onReject}
      />,
    );
    await user.click(screen.getByRole('button', { name: '거부' }));
    // 모달 노출
    const textarea = await screen.findByLabelText('거부 사유');
    await user.type(textarea, '품질 미달');
    await user.click(screen.getByRole('button', { name: '거부 확정' }));
    expect(onReject).toHaveBeenCalledWith('품질 미달');
  });

  it('활용_결정_REJECTED시_거부_사유_표시_변경_불가', () => {
    render(
      <DecisionCard
        status="REJECTED"
        decidedAt="2026-05-07T11:00:00Z"
        rejectReason="품질 미달"
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );
    // 사유 노출
    expect(screen.getByTestId('decision-reject-reason')).toHaveTextContent('품질 미달');
    // 채택/거부 버튼 미노출
    expect(screen.queryByRole('button', { name: '채택' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '거부' })).not.toBeInTheDocument();
  });

  it('ACCEPTED_상태에서_결정_일시_표시_변경_불가', () => {
    render(
      <DecisionCard
        status="ACCEPTED"
        decidedAt="2026-05-07T11:00:00Z"
        onAccept={() => {}}
        onReject={() => {}}
      />,
    );
    expect(screen.getByText('채택됨')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '채택' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '거부' })).not.toBeInTheDocument();
  });
});
