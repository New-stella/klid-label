import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { IssueSidebar } from '../components/IssueSidebar';

describe('IssueSidebar', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('이슈_추가시_사이드바_카드_누적', async () => {
    // 초기 빈 상태
    let issues = [
      { id: 1, frameId: 5, description: '라벨 누락', createdAt: '2026-05-07T10:00:00Z' },
    ];
    mock.onGet('/reviews/10/issues').reply(() => [
      200,
      {
        success: true,
        data: issues,
        message: null,
        errorCode: null,
      },
    ]);

    mock.onPost('/reviews/10/issues').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      const newIssue = {
        id: issues.length + 1,
        frameId: body.frameId,
        description: body.description,
        createdAt: '2026-05-07T11:00:00Z',
      };
      issues = [...issues, newIssue];
      return [
        201,
        { success: true, data: newIssue, message: null, errorCode: null },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<IssueSidebar reviewId={10} />);

    // 초기 1개 카드 표시
    await waitFor(() => {
      expect(screen.getByText(/프레임 #5/)).toBeInTheDocument();
    });
    expect(screen.getByText('라벨 누락')).toBeInTheDocument();

    // [+ 이슈 추가] 클릭
    const addBtn = screen.getByTestId('add-issue-button');
    await user.click(addBtn);

    // 모달 입력
    const frameInput = await screen.findByLabelText('프레임 번호');
    await user.clear(frameInput);
    await user.type(frameInput, '9');

    const descInput = screen.getByLabelText('이슈 설명');
    await user.type(descInput, '바운딩 박스 어긋남');

    const submitBtn = screen.getByRole('button', { name: '추가' });
    await waitFor(() => {
      expect(submitBtn).not.toBeDisabled();
    });
    await user.click(submitBtn);

    // 사이드바에 새 카드 누적
    await waitFor(() => {
      expect(screen.getByText(/프레임 #9/)).toBeInTheDocument();
    });
    expect(screen.getByText('바운딩 박스 어긋남')).toBeInTheDocument();
    // 기존 카드 유지 확인
    expect(screen.getByText(/프레임 #5/)).toBeInTheDocument();
  });
});
