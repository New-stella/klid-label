import { screen } from '@testing-library/react';
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
});
