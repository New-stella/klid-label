import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { BatchConfigCard } from '../components/BatchConfigCard';

const baseConfigs = {
  BATCH_INTERVAL_SEC: 60,
  BATCH_CONCURRENCY: 1,
};

describe('BatchConfigCard', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('처리_주기_동시_처리_수_두_컨트롤_렌더', () => {
    renderWithProviders(<BatchConfigCard configs={baseConfigs} />);
    expect(screen.getByLabelText(/처리 주기/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/동시 처리 수/i)).toBeInTheDocument();
  });

  it('각_필드의_help_설명_텍스트_렌더', () => {
    renderWithProviders(<BatchConfigCard configs={baseConfigs} />);
    expect(screen.getByText(/신규 영상을 픽업해 처리하는 주기/)).toBeInTheDocument();
    expect(screen.getByText(/병렬 처리할 영상 수/)).toBeInTheDocument();
  });

  it('한_필드만_변경해_저장하면_그_키만_전송된다', async () => {
    // given: 구 버그 — onSubmit 이 dirty 여부와 무관하게 카드 내 전체 키를 항상 mutate 해,
    // 손대지 않은 값까지 매번 재전송됐다(동시 편집 시 다른 사용자 변경을 되돌릴 위험).
    const calledKeys: string[] = [];
    mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
      calledKeys.push(decodeURIComponent(config.url?.split('/').pop() ?? ''));
      return [
        200,
        {
          success: true,
          data: { key: calledKeys[calledKeys.length - 1], value: '999', description: '' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<BatchConfigCard configs={baseConfigs} />);

    // when: '처리 주기'만 변경하고 '동시 처리 수'는 손대지 않는다
    fireEvent.change(screen.getByLabelText(/처리 주기/i), { target: { value: '90' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // then: 변경한 키만 전송된다
    await waitFor(() => {
      expect(calledKeys).toEqual(['BATCH_INTERVAL_SEC']);
    });
  });
});
