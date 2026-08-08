import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { DecisionCard } from '../components/DecisionCard';

// 사용자 노출 문구를 확정 용어 '반려'로 통일했다(사양 SCREEN-023). 아래 단언의 '거부' →
// '반려' 정정은 그 변경분이며, 식별자·API 필드(REJECTED/onReject/rejectReason)는 계약이라 그대로다.
describe('DecisionCard', () => {
  it('활용_결정_PENDING에서_채택_반려_버튼_노출', () => {
    render(
      <DecisionCard
        status="PENDING"
        onAccept={() => {}}
        onReject={() => {}}
        onRestore={() => {}}
      />,
    );
    expect(screen.getByRole('button', { name: '채택' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '반려' })).toBeInTheDocument();
  });

  it('PENDING_채택_버튼_클릭시_onAccept_호출', async () => {
    const user = userEvent.setup();
    const onAccept = vi.fn();
    render(
      <DecisionCard
        status="PENDING"
        onAccept={onAccept}
        onReject={() => {}}
        onRestore={() => {}}
      />,
    );
    await user.click(screen.getByRole('button', { name: '채택' }));
    expect(onAccept).toHaveBeenCalledOnce();
  });

  it('PENDING_반려_버튼_클릭시_RejectReasonModal_노출_사유_입력_후_확정', async () => {
    const user = userEvent.setup();
    const onReject = vi.fn();
    render(
      <DecisionCard
        status="PENDING"
        onAccept={() => {}}
        onReject={onReject}
        onRestore={() => {}}
      />,
    );
    await user.click(screen.getByRole('button', { name: '반려' }));
    // 모달 노출
    const textarea = await screen.findByLabelText('반려 사유');
    await user.type(textarea, '품질 미달');
    await user.click(screen.getByRole('button', { name: '반려 확정' }));
    expect(onReject).toHaveBeenCalledWith('품질 미달');
  });

  it('활용_결정_REJECTED시_반려_사유_표시_변경_불가', () => {
    render(
      <DecisionCard
        status="REJECTED"
        decidedAt="2026-05-07T11:00:00Z"
        rejectReason="품질 미달"
        onAccept={() => {}}
        onReject={() => {}}
        onRestore={() => {}}
      />,
    );
    // 사유 노출
    expect(screen.getByTestId('decision-reject-reason')).toHaveTextContent('품질 미달');
    // 채택/반려 버튼 미노출
    expect(screen.queryByRole('button', { name: '채택' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '반려' })).not.toBeInTheDocument();
  });

  it('ACCEPTED_상태에서_결정_일시_표시_변경_불가', () => {
    render(
      <DecisionCard
        status="ACCEPTED"
        decidedAt="2026-05-07T11:00:00Z"
        onAccept={() => {}}
        onReject={() => {}}
        onRestore={() => {}}
      />,
    );
    expect(screen.getByText('채택됨')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '채택' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '반려' })).not.toBeInTheDocument();
  });
});
