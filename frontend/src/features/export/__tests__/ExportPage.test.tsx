import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ExportPage } from '@/pages/ExportPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-EXPORT-001 ExportPage — V1.x mock 정합 (4 포맷 + 영상 선택 테이블 + 최근 이력).
 */
describe('ExportPage (mock 정합 — 4 포맷 + 영상 선택)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: {
        sub: 'u',
        role: 'REVIEWER',
        channel: 'INTERNAL',
        exp: 9999999999,
      },
    });
    mock.onGet('/exports/datasets', { params: undefined }).reply(200, {
      success: true,
      data: [{ id: 1, name: '교통 사고 2026-05', videoCount: 100 }],
      message: null,
      errorCode: null,
    });
    mock.onGet('/exports/datasets').reply(200, {
      success: true,
      data: [{ id: 1, name: '교통 사고 2026-05', videoCount: 100 }],
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 101,
            cctvName: 'CCTV-A',
            vmsClipId: 'V101',
            eventTypeCd: 'TRAFFIC',
            eventName: '교통사고',
            frameCount: 30,
            status: 'APPROVED',
            capturedAt: '2026-05-01T00:00:00Z',
          },
          {
            id: 102,
            cctvName: 'CCTV-B',
            vmsClipId: 'V102',
            eventTypeCd: 'FIRE',
            eventName: '화재',
            frameCount: 25,
            status: 'PENDING',
            capturedAt: '2026-05-02T00:00:00Z',
          },
        ],
        totalElements: 2,
        totalPages: 1,
        number: 0,
        size: 999,
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('진입_시_검수_완료_영상만_노출', async () => {
    renderWithProviders(<ExportPage />);

    // 검수 완료 영상(APPROVED) 만 테이블에 표시
    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    // PENDING 영상은 노출되지 않음
    expect(screen.queryByText('CCTV-B')).not.toBeInTheDocument();
  });

  it('4_포맷_라디오_노출_BE_미지원_포맷은_disabled', async () => {
    renderWithProviders(<ExportPage />);

    // 4 포맷 라디오 모두 노출 (COCO/YOLO/CVAT/Pascal VOC)
    await waitFor(() => {
      expect(screen.getByDisplayValue('COCO')).toBeInTheDocument();
    });
    expect(screen.getByDisplayValue('YOLO')).toBeInTheDocument();
    const cvat = screen.getByDisplayValue('CVAT');
    const pascal = screen.getByDisplayValue('PASCAL_VOC');
    expect(cvat).toBeDisabled();
    expect(pascal).toBeDisabled();
  });

  it('실행_버튼_클릭_시_pjtId_format_videoIds_BE_송신', async () => {
    let prepareBody: { pjtId?: number; format?: string; videoIds?: number[] } = {};
    mock.onPost('/exports/prepare').reply((config) => {
      prepareBody = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: {
            exportId: 7,
            status: 'READY',
            preview: { videoCount: 1, frameCount: 30, labelCount: 60 },
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<ExportPage />);

    // 영상 1건 선택
    const checkbox = await screen.findByLabelText(/영상 CCTV-A 선택/);
    await user.click(checkbox);

    const executeBtn = screen.getByTestId('export-execute-btn');
    await waitFor(() => expect(executeBtn).toBeEnabled());
    await user.click(executeBtn);

    await waitFor(() => {
      expect(prepareBody.pjtId).toBe(1);
      expect(prepareBody.format).toBe('COCO');
      expect(prepareBody.videoIds).toEqual([101]);
    });
  });
});
