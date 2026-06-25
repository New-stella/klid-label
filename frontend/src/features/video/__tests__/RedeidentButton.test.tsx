import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

import { RedeidentButton } from '../components/RedeidentButton';

describe('RedeidentButton', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
  });

  it('재비식별_버튼_클릭시_확인_다이얼로그_노출', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RedeidentButton rawSn={42} />);

    await user.click(screen.getByRole('button', { name: '재비식별' }));

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('영상 재비식별')).toBeInTheDocument();
  });

  it('H1_처리중_ESC_입력해도_다이얼로그_닫히지_않음', async () => {
    const user = userEvent.setup();
    // given: POST 응답을 보류시켜 mutation.isPending 상태를 유지한다.
    let resolvePost: (() => void) | undefined;
    mock.onPost('/videos/42/redeident').reply(
      () =>
        new Promise((resolve) => {
          resolvePost = () =>
            resolve([
              200,
              {
                success: true,
                data: { rawSn: 42, status: 'ACCEPTED' },
                message: null,
                errorCode: null,
              },
            ]);
        }),
    );

    renderWithProviders(<RedeidentButton rawSn={42} />);
    await user.click(screen.getByRole('button', { name: '재비식별' }));

    const dialog = screen.getByRole('dialog');
    // when: 확인을 눌러 처리 중(pending) 상태로 만든 뒤 ESC 입력.
    // pending 시 버튼에 스피너 aria-label('처리 중')이 추가돼 접근명이 '확인'+'처리 중'이 되므로
    // 부분 매칭(/확인/)으로 조회한다.
    await user.click(within(dialog).getByRole('button', { name: /확인/ }));
    await waitFor(() => {
      expect(within(dialog).getByRole('button', { name: /확인/ })).toBeDisabled();
    });
    await user.keyboard('{Escape}');

    // then: closeOnEsc=false 로 다이얼로그가 유지되어 재클릭(중복요청) 경로가 열리지 않음.
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    resolvePost?.();
  });

  it('H1_처리중_백드롭_클릭해도_다이얼로그_닫히지_않음', async () => {
    const user = userEvent.setup();
    let resolvePost: (() => void) | undefined;
    mock.onPost('/videos/42/redeident').reply(
      () =>
        new Promise((resolve) => {
          resolvePost = () =>
            resolve([
              200,
              {
                success: true,
                data: { rawSn: 42, status: 'ACCEPTED' },
                message: null,
                errorCode: null,
              },
            ]);
        }),
    );

    renderWithProviders(<RedeidentButton rawSn={42} />);
    await user.click(screen.getByRole('button', { name: '재비식별' }));

    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: /확인/ }));
    await waitFor(() => {
      expect(within(dialog).getByRole('button', { name: /확인/ })).toBeDisabled();
    });

    // when: 백드롭 클릭 (closeOnBackdrop=false 라 무시되어야 함)
    await user.click(screen.getByTestId('modal-backdrop'));

    // then: 다이얼로그 유지
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    resolvePost?.();
  });

  it('확인_성공시_성공_토스트_노출_및_다이얼로그_닫힘', async () => {
    const user = userEvent.setup();
    mock.onPost('/videos/42/redeident').reply(200, {
      success: true,
      data: { rawSn: 42, status: 'ACCEPTED' },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<RedeidentButton rawSn={42} />);
    await user.click(screen.getByRole('button', { name: '재비식별' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: '확인' }),
    );

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(useUiStore.getState().toasts).toHaveLength(1);
    expect(useUiStore.getState().toasts[0]?.variant).toBe('success');
  });
});
