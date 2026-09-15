import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ReviewHeader } from '../components/ReviewHeader';

/**
 * 검수 상세 헤더 — 점유 표시 · 최근 승인자 · 검수 시작(SCREEN-019).
 *
 * ★이 화면에는 **「이력 보기」 진입을 두지 않는다**(SCREEN-019 v56). 승인자는 헤더가 이미 싣고,
 * 이력 전체는 작업 목록이, 반려 왕복은 이슈 탭이 보여주며, 결정적으로 이력 조회 창구가 배정
 * 식별자를 받는데 **검수는 배정 없이** 집어가는 것이 정상 동선이라 그 진입이 애초에 열리지 않는다.
 */
describe('검수 상세 헤더 — 점유와 승인자', () => {
  const baseProps = {
    videoId: 42,
    cctvName: 'CCTV-1',
    workerName: '홍길동',
    submittedAt: '2026-05-07T01:30:00Z',
    status: 'REVIEWING' as const,
    onClose: () => {},
  };

  // ── 점유 표시 ──────────────────────────────────────────────────

  it('내가_잡은_영상과_남이_잡은_영상을_구분해_보인다', () => {
    const mine = render(<ReviewHeader {...baseProps} claim={{ kind: 'mine' }} />);
    expect(screen.getByTestId('review-header-claim')).toHaveTextContent('내가 검수 중');
    mine.unmount();

    render(<ReviewHeader {...baseProps} claim={{ kind: 'other', name: '김검수' }} />);
    expect(screen.getByTestId('review-header-claim')).toHaveTextContent('김검수 검수 중');
  });

  it('점유_표시에_언제부터_보고_있는지가_함께_나온다', () => {
    // 방금 잡은 것인지 한참 전에 잡은 것인지 알아야 기다릴지 말지를 판단할 수 있다(SCREEN-019).
    render(
      <ReviewHeader
        {...baseProps}
        claim={{ kind: 'other', name: '김검수' }}
        reviewStartedAt="2026-05-07T02:30:00Z"
      />,
    );

    const claim = screen.getByTestId('review-header-claim');
    expect(claim).toHaveTextContent('김검수 검수 중');
    expect(claim).toHaveTextContent('부터');
  });

  it('시작_시각을_모르면_이름만_보이고_빈_시각을_지어내지_않는다', () => {
    render(<ReviewHeader {...baseProps} claim={{ kind: 'other', name: '김검수' }} />);

    const claim = screen.getByTestId('review-header-claim');
    expect(claim).toHaveTextContent('김검수 검수 중');
    expect(claim.textContent).not.toContain('부터');
  });

  it('아무도_잡지_않았으면_점유_표시를_두지_않는다', () => {
    // 만료 판정은 서버가 한다 — 유예로 풀린 것도 여기로 온다.
    render(<ReviewHeader {...baseProps} claim={{ kind: 'none' }} />);
    expect(screen.queryByTestId('review-header-claim')).toBeNull();
  });

  it('점유를_모르면_표시를_지어내지_않는다', () => {
    render(<ReviewHeader {...baseProps} />);
    expect(screen.queryByTestId('review-header-claim')).toBeNull();
  });

  // ── 최근 승인자 ────────────────────────────────────────────────

  it('마지막_승인자를_역할과_함께_보인다', () => {
    render(
      <ReviewHeader
        {...baseProps}
        status="COMPLETED"
        lastApproverName="박관리"
        lastApproverRole="ADMIN"
        lastApprovedAt="2026-05-08T09:00:00Z"
      />,
    );

    // ★역할은 승인한 **그 시점**에 기록된 값이다 — 관리자가 승인한 건은 관리자로 남는다.
    const el = screen.getByTestId('review-header-last-approval');
    expect(el).toHaveTextContent('박관리(관리자)');
    // 무엇의 시각인지 밝히지 않으면 제출일과 구분되지 않는다(SCREEN-019 「… {시각} 승인」).
    expect(el).toHaveTextContent('승인');
  });

  it('같은_사람이라도_역할이_다르면_다르게_표기된다', () => {
    // 역할을 상수로 굳히거나 지우는 변이를 죽인다 — 이름만 보면 둘이 같다.
    const admin = render(
      <ReviewHeader
        {...baseProps}
        status="COMPLETED"
        lastApproverName="같은사람"
        lastApproverRole="ADMIN"
        lastApprovedAt="2026-05-08T09:00:00Z"
      />,
    );
    const adminText = screen.getByTestId('review-header-last-approval').textContent;
    admin.unmount();

    render(
      <ReviewHeader
        {...baseProps}
        status="COMPLETED"
        lastApproverName="같은사람"
        lastApproverRole="REVIEWER"
        lastApprovedAt="2026-05-08T09:00:00Z"
      />,
    );
    expect(screen.getByTestId('review-header-last-approval').textContent).not.toBe(adminText);
  });

  it('역할이_빈_옛_기록은_빈_괄호를_남기지_않는다', () => {
    render(
      <ReviewHeader
        {...baseProps}
        status="COMPLETED"
        lastApproverName="이검수"
        lastApproverRole={null}
        lastApprovedAt="2026-05-08T09:00:00Z"
      />,
    );

    const el = screen.getByTestId('review-header-last-approval');
    expect(el).toHaveTextContent('이검수');
    expect(el.textContent).not.toContain('()');
  });

  it('승인_이력이_없으면_표시를_두지_않는다', () => {
    render(<ReviewHeader {...baseProps} />);
    expect(screen.queryByTestId('review-header-last-approval')).toBeNull();
  });

  // ── 검수 시작 ──────────────────────────────────────────────────

  it('검수_시작_버튼이_서고_누르면_콜백이_불린다', async () => {
    const onStartReview = vi.fn();
    const user = userEvent.setup();
    render(<ReviewHeader {...baseProps} onStartReview={onStartReview} />);

    const btn = screen.getByTestId('review-action-start');
    expect(btn).toBeInTheDocument();
    await user.click(btn);
    expect(onStartReview).toHaveBeenCalledTimes(1);
  });

  it('검수_시작을_주지_않으면_버튼을_렌더하지_않는다', () => {
    render(<ReviewHeader {...baseProps} onApprove={() => {}} />);
    expect(screen.queryByTestId('review-action-start')).toBeNull();
    // 짝 단언 — 액션 영역 자체는 서 있어야 부재가 의미를 갖는다.
    expect(screen.getByTestId('review-action-approve')).toBeInTheDocument();
  });

  it('검수_시작_중에는_버튼이_잠긴다', () => {
    render(<ReviewHeader {...baseProps} onStartReview={() => {}} isStarting />);
    expect(screen.getByTestId('review-action-start')).toBeDisabled();
  });

  // ── 검수 시작 거절 안내 ────────────────────────────────────────

  it('검수_시작_거절_사유는_서버_문장_그대로_머무는_안내로_선다', () => {
    // ★사라지는 알림으로 두면 자리를 비웠다 돌아온 사람이 왜 자기 이름이 안 뜨는지 알 수 없다.
    //   같은 409 에 사유가 셋이라 코드로 문장을 지어내지 않고 서버 문장을 그대로 싣는다.
    render(
      <ReviewHeader
        {...baseProps}
        onApprove={() => {}}
        onReject={() => {}}
        claimConflictMessage="김검수 님이 검수 중입니다."
      />,
    );

    expect(screen.getByTestId('review-header-claim-conflict')).toHaveTextContent(
      '김검수 님이 검수 중입니다.',
    );
    // ★막히는 것은 검수 시작 하나뿐이다 — 다른 액션은 그대로 열려 있어야 한다.
    expect(screen.getByTestId('review-action-reject')).toBeInTheDocument();
    expect(screen.getByTestId('review-action-approve')).toBeInTheDocument();
  });

  it('거절_사유가_없으면_안내를_두지_않는다', () => {
    render(<ReviewHeader {...baseProps} onApprove={() => {}} />);
    expect(screen.queryByTestId('review-header-claim-conflict')).toBeNull();
    expect(screen.getByTestId('review-action-approve')).toBeInTheDocument();
  });

  // ── 이력 보기 부재 ─────────────────────────────────────────────

  it('이력_보기_진입을_두지_않는다', () => {
    // SCREEN-019 v56 이 이 진입을 명시적으로 걷어냈다 — 되살리지 말 것.
    render(<ReviewHeader {...baseProps} onApprove={() => {}} onReject={() => {}} />);

    expect(screen.queryByRole('button', { name: /이력/ })).toBeNull();
    // 짝 단언 — 헤더가 통째로 비어서 통과하는 것이 아님을 보인다.
    expect(screen.getByTestId('review-header-actions')).toBeInTheDocument();
    expect(screen.getByTestId('review-action-approve')).toBeInTheDocument();
  });
});
