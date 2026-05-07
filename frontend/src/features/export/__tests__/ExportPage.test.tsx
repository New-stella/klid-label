import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ExportPage } from '@/pages/ExportPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

describe('ExportPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/exports/datasets').reply(200, {
      success: true,
      data: [{ id: 1, name: '교통 사고 2026-05', videoCount: 100 }],
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('내보내기_NAS_경로_입력_미리보기_API_호출', async () => {
    let prepareBody: unknown;
    mock.onPost('/exports/prepare').reply((config) => {
      prepareBody = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: {
            exportId: 7,
            status: 'READY',
            preview: { videoCount: 10, frameCount: 9000, labelCount: 15000 },
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<ExportPage />);

    // 데이터셋 라디오 선택
    const datasetRadio = await screen.findByDisplayValue('1');
    await user.click(datasetRadio);

    // 형식 — COCO 기본값
    // NAS 경로 입력
    const nasInput = screen.getByLabelText('NAS 경로');
    await user.type(nasInput, '/mnt/nas/exports/2026-05');

    // 미리보기
    const previewBtn = screen.getByTestId('export-preview-btn');
    await waitFor(() => {
      expect(previewBtn).toBeEnabled();
    });
    await user.click(previewBtn);

    await waitFor(() => {
      expect(prepareBody).toMatchObject({
        datasetId: 1,
        format: 'COCO',
        nasPath: '/mnt/nas/exports/2026-05',
      });
    });

    // 미리보기 결과 노출
    await waitFor(() => {
      expect(screen.getByTestId('export-preview')).toBeInTheDocument();
    });
  });

  it('NAS_경로_path_traversal_입력시_에러_표시', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ExportPage />);

    const datasetRadio = await screen.findByDisplayValue('1');
    await user.click(datasetRadio);

    const nasInput = screen.getByLabelText('NAS 경로');
    await user.type(nasInput, '/etc/../passwd');

    expect(
      await screen.findByText('`..` 경로는 허용되지 않습니다'),
    ).toBeInTheDocument();
  });

  it('절대_경로가_아닌_입력시_에러_표시', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ExportPage />);

    const datasetRadio = await screen.findByDisplayValue('1');
    await user.click(datasetRadio);

    const nasInput = screen.getByLabelText('NAS 경로');
    await user.type(nasInput, 'relative/path');

    expect(
      await screen.findByText('절대 경로(/)로 시작해야 합니다'),
    ).toBeInTheDocument();
  });
});
