import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { YoloConfigCard } from '../components/YoloConfigCard';

const baseConfigs = {
  YOLO_CONF_THRESHOLD: 40,
  YOLO_IMGSZ: 1280,
  YOLO_IOU: 50,
};

describe('YoloConfigCard', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('3개_입력_필드_렌더_(conf_threshold_imgsz_iou)', () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    expect(screen.getByLabelText(/Confidence Threshold/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/이미지 크기/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/IoU/i)).toBeInTheDocument();
  });

  it('기본값_표시_(서버_값_도착)', () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    const conf = screen.getByLabelText(/Confidence Threshold/i) as HTMLInputElement;
    const imgsz = screen.getByLabelText(/이미지 크기/i) as HTMLInputElement;
    const iou = screen.getByLabelText(/IoU/i) as HTMLInputElement;
    expect(conf.value).toBe('40');
    expect(imgsz.value).toBe('1280');
    expect(iou.value).toBe('50');
  });

  it('정상_입력_+_저장_클릭_시_PUT_API_3회_호출', async () => {
    const calls: Array<{ key: string; value: number }> = [];
    mock.onPut('/manage/configs').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      calls.push(body);
      return [
        200,
        {
          success: true,
          data: { ...body, updatedAt: '2026-05-13T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    const conf = screen.getByLabelText(/Confidence Threshold/i) as HTMLInputElement;
    fireEvent.change(conf, { target: { value: '50' } });

    const saveBtn = screen.getByRole('button', { name: /저장/ });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(calls.length).toBeGreaterThanOrEqual(1);
    });

    // 결국 3개 키 모두 PUT 되어야 함
    await waitFor(() => {
      const keys = new Set(calls.map((c) => c.key));
      expect(keys.has('YOLO_CONF_THRESHOLD')).toBe(true);
      expect(keys.has('YOLO_IMGSZ')).toBe(true);
      expect(keys.has('YOLO_IOU')).toBe(true);
    });

    const conf_call = calls.find((c) => c.key === 'YOLO_CONF_THRESHOLD');
    expect(conf_call?.value).toBe(50);
  });
});
