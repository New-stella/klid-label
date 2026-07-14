import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { DEFAULT_IMAGE_ADJUST, useLabelStore } from '@/stores/useLabelStore';

import { ImageAdjustPanel } from '../ImageAdjustPanel';
import { buildImageFilters } from '../../canvas/layers/imageFilters';

describe('ImageAdjustPanel — 이미지 조절 패널 (2c)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('이미지_밝기_슬라이더_konva_필터_반영', () => {
    render(<ImageAdjustPanel />);
    const slider = screen.getByLabelText('밝기') as HTMLInputElement;
    fireEvent.change(slider, { target: { value: '0.6' } });

    // 슬라이더 → 스토어 → konva 필터 구성까지 반영.
    expect(useLabelStore.getState().imageAdjust.brightness).toBeCloseTo(0.6);
    const filters = buildImageFilters(useLabelStore.getState().imageAdjust);
    expect(filters.hasFilters).toBe(true);
    expect(filters.brightness).toBeCloseTo(0.6);
  });

  it('대비_슬라이더_스토어_반영', () => {
    render(<ImageAdjustPanel />);
    const slider = screen.getByLabelText('대비') as HTMLInputElement;
    fireEvent.change(slider, { target: { value: '25' } });
    expect(useLabelStore.getState().imageAdjust.contrast).toBeCloseTo(25);
  });

  it('라벨_투명도_슬라이더_스토어_반영', () => {
    render(<ImageAdjustPanel />);
    const slider = screen.getByLabelText('라벨 투명도') as HTMLInputElement;
    fireEvent.change(slider, { target: { value: '0.3' } });
    expect(useLabelStore.getState().imageAdjust.labelOpacity).toBeCloseTo(0.3);
  });

  it('default_원본값_초기표시_후_초기화버튼으로_복원', () => {
    render(<ImageAdjustPanel />);
    const brightness = screen.getByLabelText('밝기') as HTMLInputElement;
    expect(brightness.value).toBe(String(DEFAULT_IMAGE_ADJUST.brightness));

    fireEvent.change(brightness, { target: { value: '0.8' } });
    expect(useLabelStore.getState().imageAdjust.brightness).toBeCloseTo(0.8);

    fireEvent.click(screen.getByRole('button', { name: '초기화' }));
    expect(useLabelStore.getState().imageAdjust).toEqual(DEFAULT_IMAGE_ADJUST);
  });
});
