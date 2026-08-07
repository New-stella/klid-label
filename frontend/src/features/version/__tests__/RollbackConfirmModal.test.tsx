import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { RollbackConfirmModal } from '../components/RollbackConfirmModal';

describe('RollbackConfirmModal', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('롤백_확인_모달_confirm시_useRollback_mutation', async () => {
    const onClose = vi.fn();
    const onSuccess = vi.fn();
    let posted = false;
    mock.onPost('/versions/bbb222/rollback').reply(() => {
      posted = true;
      return [
        200,
        {
          success: true,
          data: { lblHstrySn: 9001, srcSn: 241, versionHash: 'bbb222', registeredUserNo: '42', registeredAt: '2026-05-29T09:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(
      <RollbackConfirmModal
        open
        commitSha="bbb222"
        shortHash="bbb222"
        srcSn={777}
        onClose={onClose}
        onSuccess={onSuccess}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '롤백' }));

    await waitFor(() => {
      expect(posted).toBe(true);
      expect(onSuccess).toHaveBeenCalled();
    });
  });

  it('취소_클릭시_onClose_호출', () => {
    const onClose = vi.fn();
    const onSuccess = vi.fn();
    renderWithProviders(
      <RollbackConfirmModal
        open
        commitSha="bbb222"
        shortHash="bbb222"
        srcSn={777}
        onClose={onClose}
        onSuccess={onSuccess}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onClose).toHaveBeenCalled();
  });
});
