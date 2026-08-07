import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { PrecisionConfigCard } from '../components/PrecisionConfigCard';

const baseConfigs = {
  YOLO_CONF_THRESHOLD: 40,
  POLYGON_SIMPLIFY_TOLERANCE: 1,
};

describe('PrecisionConfigCard (FEAT-007 라벨링 정밀도)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('인식_민감도_경계_세밀함_두_컨트롤_렌더', () => {
    renderWithProviders(<PrecisionConfigCard configs={baseConfigs} />);
    expect(screen.getByLabelText(/인식 민감도/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/경계 세밀함/i)).toBeInTheDocument();
  });

  it('서버_값으로_초기화', () => {
    renderWithProviders(<PrecisionConfigCard configs={baseConfigs} />);
    const sensitivity = screen.getByLabelText(/인식 민감도/i) as HTMLInputElement;
    const tolerance = screen.getByLabelText(/경계 세밀함/i) as HTMLInputElement;
    expect(sensitivity.value).toBe('40');
    expect(tolerance.value).toBe('1');
  });

  it('각_컨트롤의_help_설명_텍스트_렌더', () => {
    renderWithProviders(<PrecisionConfigCard configs={baseConfigs} />);
    expect(screen.getByText(/Confidence Threshold 와 동일한 설정값/)).toBeInTheDocument();
    expect(screen.getByText(/클수록 경계가 단순해져/)).toBeInTheDocument();
  });

  it('값_변경_후_저장_클릭시_두_키_PUT_호출', async () => {
    const calls: Array<{ key: string; value: string }> = [];
    mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      const url = config.url ?? '';
      const key = decodeURIComponent(url.split('/').pop() ?? '');
      calls.push({ key, value: String(body.value) });
      return [
        200,
        {
          success: true,
          data: { key, value: body.value, updatedAt: '2026-05-29T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<PrecisionConfigCard configs={baseConfigs} />);

    const sensitivity = screen.getByLabelText(/인식 민감도/i) as HTMLInputElement;
    fireEvent.change(sensitivity, { target: { value: '55' } });
    const tolerance = screen.getByLabelText(/경계 세밀함/i) as HTMLInputElement;
    fireEvent.change(tolerance, { target: { value: '2.5' } });

    fireEvent.click(screen.getByRole('button', { name: /저장/ }));

    await waitFor(() => {
      const keys = new Set(calls.map((c) => c.key));
      expect(keys.has('YOLO_CONF_THRESHOLD')).toBe(true);
      expect(keys.has('POLYGON_SIMPLIFY_TOLERANCE')).toBe(true);
    });

    expect(calls.find((c) => c.key === 'YOLO_CONF_THRESHOLD')?.value).toBe('55');
    expect(calls.find((c) => c.key === 'POLYGON_SIMPLIFY_TOLERANCE')?.value).toBe('2.5');
  });

  it('한_필드만_변경해_저장하면_그_키만_PUT된다', async () => {
    // given: 구 버그 — onSubmit 이 dirty 여부와 무관하게 두 키를 항상 mutate 했다.
    const calls: Array<{ key: string; value: string }> = [];
    mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      const url = config.url ?? '';
      const key = decodeURIComponent(url.split('/').pop() ?? '');
      calls.push({ key, value: String(body.value) });
      return [
        200,
        {
          success: true,
          data: { key, value: body.value, updatedAt: '2026-05-29T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<PrecisionConfigCard configs={baseConfigs} />);

    const sensitivity = screen.getByLabelText(/인식 민감도/i) as HTMLInputElement;
    fireEvent.change(sensitivity, { target: { value: '55' } });

    fireEvent.click(screen.getByRole('button', { name: /저장/ }));

    await waitFor(() => {
      expect(calls.length).toBeGreaterThanOrEqual(1);
    });
    const keys = new Set(calls.map((c) => c.key));
    expect(keys).toEqual(new Set(['YOLO_CONF_THRESHOLD']));
  });
});
