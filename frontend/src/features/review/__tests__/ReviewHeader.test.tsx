import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ReviewHeader } from '../components/ReviewHeader';

describe('ReviewHeader', () => {
  const baseProps = {
    videoId: 42,
    cctvName: 'CCTV-1',
    workerName: '홍길동',
    submittedAt: '2026-05-07T01:30:00Z',
    status: 'REVIEWING' as const,
    onClose: () => {},
  };

  it('검수페이지_로드시_헤더에_영상명과_작업자_표시', () => {
    render(<ReviewHeader {...baseProps} />);

    expect(screen.getByTestId('review-header-cctv-name')).toHaveTextContent('CCTV-1');
    expect(screen.getByTestId('review-header-worker-name')).toHaveTextContent('홍길동');
  });

  // SCREEN-019 §검수 헤더 — "헤더에는 프레임 위치 표시를 두지 않는다"
  // (헤더 컴포넌트 목록의 `[폐기] Frame N/total`).
  it('헤더에_프레임_위치_표시가_없다', () => {
    render(<ReviewHeader {...baseProps} />);

    expect(screen.queryByTestId('review-header-frame-counter')).toBeNull();
    expect(screen.queryByText(/Frame\s*\d+\s*\/\s*\d+/)).toBeNull();
    expect(screen.queryByLabelText('현재 프레임')).toBeNull();
  });

  it('닫기_버튼_클릭시_onClose_콜백_호출', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();

    render(<ReviewHeader {...baseProps} onClose={onClose} />);

    const closeBtn = screen.getByRole('button', { name: '검수 페이지 닫기' });
    await user.click(closeBtn);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('상태_배지_렌더링', () => {
    render(<ReviewHeader {...baseProps} status="COMPLETED" />);
    // StatusBadge data-status attribute로 검증
    const badge = document.querySelector('[data-status="COMPLETED"]');
    expect(badge).not.toBeNull();
  });

  // ── 승인·반려 (하단 액션 바 폐기 → 헤더 단독 담당) ─────────────────────────
  describe('승인·반려 액션', () => {
    it('승인_반려_버튼이_헤더_안에_렌더된다', () => {
      render(<ReviewHeader {...baseProps} onApprove={() => {}} onReject={() => {}} />);

      const header = screen.getByTestId('review-header');
      const approve = screen.getByTestId('review-action-approve');
      const reject = screen.getByTestId('review-action-reject');

      expect(header).toContainElement(approve);
      expect(header).toContainElement(reject);
    });

    it('승인_반려_버튼_접근성_이름_노출', () => {
      render(<ReviewHeader {...baseProps} onApprove={() => {}} onReject={() => {}} />);

      expect(screen.getByRole('button', { name: '승인' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '반려' })).toBeInTheDocument();
      // 액션 그룹 자체도 식별 가능해야 한다(하단 액션 바 폐기 후 유일 진입점).
      expect(screen.getByTestId('review-header-actions')).toBeInTheDocument();
    });

    it('승인_버튼_클릭시_onApprove_콜백_호출', async () => {
      const onApprove = vi.fn();
      const user = userEvent.setup();

      render(<ReviewHeader {...baseProps} onApprove={onApprove} onReject={() => {}} />);

      await user.click(screen.getByTestId('review-action-approve'));

      expect(onApprove).toHaveBeenCalledTimes(1);
    });

    it('반려_버튼_클릭시_onReject_콜백_호출', async () => {
      const onReject = vi.fn();
      const user = userEvent.setup();

      render(<ReviewHeader {...baseProps} onApprove={() => {}} onReject={onReject} />);

      await user.click(screen.getByTestId('review-action-reject'));

      expect(onReject).toHaveBeenCalledTimes(1);
    });

    it('검수상태_COMPLETED_시_액션버튼_disabled_와_사유_안내', () => {
      render(
        <ReviewHeader {...baseProps} status="COMPLETED" onApprove={() => {}} onReject={() => {}} />,
      );

      expect(screen.getByTestId('review-action-approve')).toBeDisabled();
      expect(screen.getByTestId('review-action-reject')).toBeDisabled();
      expect(screen.getByTestId('review-header-action-message')).toHaveTextContent(
        '이미 승인 처리된 검수입니다.',
      );
    });

    it('검수상태_REJECTED_시_액션버튼_disabled_와_사유_안내', () => {
      render(
        <ReviewHeader {...baseProps} status="REJECTED" onApprove={() => {}} onReject={() => {}} />,
      );

      expect(screen.getByTestId('review-action-approve')).toBeDisabled();
      expect(screen.getByTestId('review-action-reject')).toBeDisabled();
      expect(screen.getByTestId('review-header-action-message')).toHaveTextContent(
        '이미 반려 처리된 검수입니다.',
      );
    });

    it('검수대기_상태에서는_두_액션_모두_활성', () => {
      render(
        <ReviewHeader
          {...baseProps}
          status="REVIEW_PENDING"
          onApprove={() => {}}
          onReject={() => {}}
        />,
      );

      expect(screen.getByTestId('review-action-approve')).toBeEnabled();
      expect(screen.getByTestId('review-action-reject')).toBeEnabled();
      expect(screen.queryByTestId('review-header-action-message')).toBeNull();
    });

    it('승인_진행중이면_반려도_함께_비활성', () => {
      render(<ReviewHeader {...baseProps} isApproving onApprove={() => {}} onReject={() => {}} />);

      expect(screen.getByTestId('review-action-approve')).toBeDisabled();
      expect(screen.getByTestId('review-action-reject')).toBeDisabled();
    });

    it('반려_진행중이면_승인도_함께_비활성', () => {
      render(<ReviewHeader {...baseProps} isRejecting onApprove={() => {}} onReject={() => {}} />);

      expect(screen.getByTestId('review-action-approve')).toBeDisabled();
      expect(screen.getByTestId('review-action-reject')).toBeDisabled();
    });

    it('핸들러_미지정시_액션_영역을_렌더하지_않는다', () => {
      render(<ReviewHeader {...baseProps} />);

      expect(screen.queryByTestId('review-header-actions')).toBeNull();
      expect(screen.queryByTestId('review-action-approve')).toBeNull();
      expect(screen.queryByTestId('review-action-reject')).toBeNull();
    });

    // ── Phase 7b — 재검토 필요 표시 + 재승인 동선 ──────────────────────────
    describe('재검토 필요(needsRecheck)', () => {
      it('COMPLETED_이고_needsRecheck_true면_승인_버튼만_다시_활성화된다', () => {
        render(
          <ReviewHeader
            {...baseProps}
            status="COMPLETED"
            needsRecheck
            onApprove={() => {}}
            onReject={() => {}}
          />,
        );

        expect(screen.getByTestId('review-action-approve')).toBeEnabled();
        // 반려는 예외 없이 완료 상태에서 그대로 비활성이다.
        expect(screen.getByTestId('review-action-reject')).toBeDisabled();
      });

      it('COMPLETED_이고_needsRecheck_false면_기존과_동일하게_두_액션_모두_비활성', () => {
        render(
          <ReviewHeader
            {...baseProps}
            status="COMPLETED"
            needsRecheck={false}
            onApprove={() => {}}
            onReject={() => {}}
          />,
        );

        expect(screen.getByTestId('review-action-approve')).toBeDisabled();
        expect(screen.getByTestId('review-action-reject')).toBeDisabled();
      });

      it('needsRecheck_true면_재검토_필요_배지가_상태_배지와_나란히_노출된다', () => {
        render(<ReviewHeader {...baseProps} status="COMPLETED" needsRecheck />);

        // 상태 배지(COMPLETED)와 재검토 필요 배지(NEEDS_RECHECK)가 둘 다 존재 — 대체가 아니라 병기.
        expect(document.querySelector('[data-status="COMPLETED"]')).not.toBeNull();
        expect(document.querySelector('[data-status="NEEDS_RECHECK"]')).not.toBeNull();
        expect(screen.getByText('재검토 필요')).toBeInTheDocument();
      });

      it('needsRecheck_false면_재검토_필요_배지가_렌더되지_않는다', () => {
        render(<ReviewHeader {...baseProps} status="COMPLETED" needsRecheck={false} />);

        expect(document.querySelector('[data-status="NEEDS_RECHECK"]')).toBeNull();
      });

      it('needsRecheck_true여도_REVIEWING_상태면_예외가_적용되지_않는다_검수중은_원래_활성', () => {
        // canReapprove 는 status==='COMPLETED' 조건이 함께 있어야 한다 — REVIEWING 은 원래도 활성.
        render(
          <ReviewHeader
            {...baseProps}
            status="REVIEWING"
            needsRecheck
            onApprove={() => {}}
            onReject={() => {}}
          />,
        );

        expect(screen.getByTestId('review-action-approve')).toBeEnabled();
        expect(screen.getByTestId('review-action-reject')).toBeEnabled();
      });

      it('재승인_가능_상태에서는_사유_안내_문구가_재검토_안내로_바뀐다', () => {
        render(
          <ReviewHeader
            {...baseProps}
            status="COMPLETED"
            needsRecheck
            onApprove={() => {}}
            onReject={() => {}}
          />,
        );

        expect(screen.getByTestId('review-header-action-message')).toHaveTextContent(
          '검수 승인 이후 라벨/메타가 수정되어 재검토가 필요합니다. 다시 확인 후 승인하세요.',
        );
      });

      it('재승인_가능_상태에서도_승인_진행중이면_승인_버튼이_다시_비활성된다', () => {
        render(
          <ReviewHeader
            {...baseProps}
            status="COMPLETED"
            needsRecheck
            isApproving
            onApprove={() => {}}
            onReject={() => {}}
          />,
        );

        expect(screen.getByTestId('review-action-approve')).toBeDisabled();
      });
    });
  });
});
