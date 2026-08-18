import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { YoloConfigCard } from '../components/YoloConfigCard';

const baseConfigs = {
  YOLO_CONF_THRESHOLD: 40,
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

  it('2개_입력_필드_렌더_(conf_threshold_iou)', () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    expect(screen.getByLabelText(/Confidence Threshold/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/IoU/i)).toBeInTheDocument();
  });

  it('기본값_표시_(서버_값_도착)', () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    const conf = screen.getByLabelText(/Confidence Threshold/i) as HTMLInputElement;
    const iou = screen.getByLabelText(/IoU/i) as HTMLInputElement;
    expect(conf.value).toBe('40');
    expect(iou.value).toBe('50');
  });

  it('각_필드의_help_설명_텍스트_렌더', () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    expect(screen.getByText(/객체로 인식할 최소 확신도/)).toBeInTheDocument();
    expect(screen.getByText(/겹치는 박스를 중복으로 제거/)).toBeInTheDocument();
  });

  it('한_필드만_변경해_저장하면_그_키만_PUT된다', async () => {
    // given: 구 버그 — onSubmit 이 dirty 여부와 무관하게 카드 내 키를 항상 mutate 해,
    // 손대지 않은 IOU 까지 매번 재전송됐다(동시 편집 시 다른 사용자 변경을 되돌릴 위험).
    // 이제는 변경한 키(YOLO_CONF_THRESHOLD)만 전송돼야 한다.
    const calls: Array<{ key: string; value: number }> = [];
    // BE 정합: PUT /manage/configs/{key} body: { value }
    // path variable 에서 key 를 추출해 calls 누적.
    mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      const url = config.url ?? '';
      const key = decodeURIComponent(url.split('/').pop() ?? '');
      const numericValue = Number(body.value);
      calls.push({ key, value: numericValue });
      return [
        200,
        {
          success: true,
          data: { key, value: body.value, updatedAt: '2026-05-13T10:00:00Z' },
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

    // 변경한 키 하나만 PUT 되어야 한다
    const keys = new Set(calls.map((c) => c.key));
    expect(keys).toEqual(new Set(['YOLO_CONF_THRESHOLD']));

    const conf_call = calls.find((c) => c.key === 'YOLO_CONF_THRESHOLD');
    expect(conf_call?.value).toBe(50);
  });

  /**
   * ★ 되살리기 방지 가드 — 「이미지 크기(imgsz)」 입력칸은 이 카드에 없어야 한다.
   *
   * 추론 서버가 입력 크기를 640 으로 고정해 쓰므로 조정해도 결과가 달라지지 않는다. 조정되는
   * 척하는 입력칸이라 정밀도가 오르지 않을 때 운영자가 원인을 이 값에서 찾게 만들었다.
   * 되살리려면 추론 서버가 요청값을 실제로 쓰도록 먼저 고칠 것.
   * 사양 근거: SCREEN-025 · UC-031 / BE 가드: ConfigKeysTest.imgszKeyIsNotConfigurable
   *
   * 구 테스트 3건(32 배수 하드거부 없음 · blur 조용한 보정 · 범위 클램프)은 이 항목과 함께
   * 폐기됐다 — 검증 대상 자체가 사라졌다.
   */
  it('★이미지_크기_입력칸은_없다_구_imgsz_항목_폐지', () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    expect(screen.queryByLabelText(/이미지 크기/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/저장·내보내기 해상도와는 무관/)).not.toBeInTheDocument();
  });
});
