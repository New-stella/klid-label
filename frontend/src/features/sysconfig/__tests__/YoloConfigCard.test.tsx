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

  it('각_필드의_help_설명_텍스트_렌더', () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    expect(screen.getByText(/객체로 인식할 최소 확신도/)).toBeInTheDocument();
    expect(screen.getByText(/저장·내보내기 해상도와는 무관/)).toBeInTheDocument();
    expect(screen.getByText(/겹치는 박스를 중복으로 제거/)).toBeInTheDocument();
  });

  it('한_필드만_변경해_저장하면_그_키만_PUT된다', async () => {
    // given: 구 버그 — onSubmit 이 dirty 여부와 무관하게 카드 내 3개 키를 항상 mutate 해,
    // 손대지 않은 IMGSZ/IOU 까지 매번 재전송됐다(동시 편집 시 다른 사용자 변경을 되돌릴 위험).
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

  it('imgsz에_32의_배수가_아닌_값을_입력해도_저장_버튼이_거부되지_않는다', () => {
    // given: 구 버그 — zod .refine(v % 32 === 0) 이 32의 배수가 아니면 저장 자체를 하드
    // 거부했다. 사양(SCREEN-025)은 "서버는 320~1920 범위만 검증하며 32의 배수 강제가
    // 없다 — 화면은 거부가 아니라 조용한 보정만 한다."
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    const imgsz = screen.getByLabelText(/이미지 크기/i) as HTMLInputElement;

    fireEvent.change(imgsz, { target: { value: '1300' } }); // 32의 배수가 아님
    fireEvent.blur(imgsz); // blur 로 보정되기 전 타이핑 상태

    // then: 에러 메시지(role=alert)가 뜨지 않는다 (하드 거부 없음). 도움말 문구는
    // "32의 배수" 를 안내로 언급하므로 role 로 에러 alert 만 좁혀서 확인한다.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('imgsz_입력칸에서_포커스가_벗어나면_가까운_32의_배수로_조용히_보정된다', async () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    const imgsz = screen.getByLabelText(/이미지 크기/i) as HTMLInputElement;

    // 1300 에 가장 가까운 32의 배수는 1312 (1300/32=40.625 → round 41 × 32 = 1312)
    fireEvent.change(imgsz, { target: { value: '1300' } });
    fireEvent.blur(imgsz);

    await waitFor(() => {
      expect(imgsz.value).toBe('1312');
    });
  });

  it('imgsz_범위를_벗어난_값도_blur시_범위_안으로_클램프_후_보정된다', async () => {
    renderWithProviders(<YoloConfigCard configs={baseConfigs} />);
    const imgsz = screen.getByLabelText(/이미지 크기/i) as HTMLInputElement;

    fireEvent.change(imgsz, { target: { value: '2000' } }); // 상한 1920 초과
    fireEvent.blur(imgsz);

    await waitFor(() => {
      // 클램프(1920) 후 가장 가까운 32의 배수 = 1920 (32×60)
      expect(imgsz.value).toBe('1920');
    });
  });
});
