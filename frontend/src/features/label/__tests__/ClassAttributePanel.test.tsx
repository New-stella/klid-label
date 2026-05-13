// SCR-LABEL-001 우측 하단 속성 패널 — 객체 식별자 trackId/id fallback 검증.

import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ClassAttributePanel } from '../components/ClassAttributePanel';
import type { Label } from '../types';

const baseLabel: Label = {
  id: 'lbl-abc12345-rest',
  frameNo: 1,
  classId: 3,
  className: 'person',
  source: 'AUTO_YOLO',
  confidence: 0.8,
  shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 50 },
};

describe('ClassAttributePanel — 객체 식별자', () => {
  it('trackId 있는 객체는 #{trackId} 표시', () => {
    const withTrack: Label = { ...baseLabel, trackId: '42' };
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([withTrack]);
    useLabelStore.getState().selectLabel(withTrack.id);
    renderWithProviders(<ClassAttributePanel labels={[withTrack]} />);
    const idSpan = screen.getByTestId('class-attribute-id');
    expect(idSpan.textContent).toBe('#42');
  });

  it('trackId 없는 객체는 id 앞 8자 fallback', () => {
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([baseLabel]);
    useLabelStore.getState().selectLabel(baseLabel.id);
    renderWithProviders(<ClassAttributePanel labels={[baseLabel]} />);
    const idSpan = screen.getByTestId('class-attribute-id');
    // 'lbl-abc12345-rest'.slice(0, 8) === 'lbl-abc1'
    expect(idSpan.textContent).toBe('#lbl-abc1');
  });

  it('trackId 가 null 인 객체도 id fallback (legacy DB row)', () => {
    const legacy: Label = { ...baseLabel, trackId: null };
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([legacy]);
    useLabelStore.getState().selectLabel(legacy.id);
    renderWithProviders(<ClassAttributePanel labels={[legacy]} />);
    const idSpan = screen.getByTestId('class-attribute-id');
    expect(idSpan.textContent).toBe('#lbl-abc1');
  });
});
