import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { LabelPanel } from '../components/LabelPanel';
import type { Label } from '../types';
import { trackIdToColor } from '../utils/trackColor';

const labels: Label[] = [
  {
    id: 'a-12345',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'AUTO_YOLO',
    confidence: 0.9,
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  },
  {
    id: 'b-67890',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'POLYGON', points: [0, 0, 10, 0, 10, 10] },
  },
];

describe('LabelPanel', () => {
  it('클래스별_그룹_+_카운트_표시', () => {
    useLabelStore.getState().reset();
    renderWithProviders(<LabelPanel labels={labels} />);
    // 2026-08-03 재확정 — 라벨명은 마스터 등록명 그대로 표시한다(한글 사전 치환 폐지).
    expect(screen.getByText('car')).toBeInTheDocument();
    expect(screen.queryByText('자동차')).toBeNull();
    expect(screen.getByText('(2)')).toBeInTheDocument();
  });

  it('항목_클릭_시_selectLabel_호출', () => {
    useLabelStore.getState().reset();
    renderWithProviders(<LabelPanel labels={labels} />);
    const btn = screen.getByText(/a-12345/);
    fireEvent.click(btn);
    expect(useLabelStore.getState().selectedLabelId).toBe('a-12345');
  });

  it('trackId_있는_라벨은_트랙ID_텍스트_표시', () => {
    useLabelStore.getState().reset();
    const withTrack: Label[] = [
      {
        id: 'a-trk-1',
        frameNo: 1,
        classId: 1,
        className: 'person',
        source: 'AUTO_YOLO',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
        trackId: '42',
      },
    ];
    renderWithProviders(<LabelPanel labels={withTrack} />);
    expect(screen.getByText(/#42/)).toBeInTheDocument();
  });

  it('trackId_없는_라벨은_트랙ID_텍스트_없음', () => {
    useLabelStore.getState().reset();
    const noTrack: Label[] = [
      {
        id: 'a-no-trk',
        frameNo: 1,
        classId: 1,
        className: 'person',
        source: 'AUTO_YOLO',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      },
    ];
    renderWithProviders(<LabelPanel labels={noTrack} />);
    // 라벨 ID 표시(#a-no-trk)는 있어도, '#42' 같은 trackId 표시는 없어야 함
    expect(screen.queryByText(/^#42$/)).toBeNull();
    expect(screen.queryByText(/^#track/)).toBeNull();
  });

  it('trackId_있는_라벨은_좌측_컬러_바_(inline style)_적용', () => {
    useLabelStore.getState().reset();
    const withTrack: Label[] = [
      {
        id: 'a-trk-color',
        frameNo: 1,
        classId: 1,
        className: 'person',
        source: 'AUTO_YOLO',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
        trackId: '7',
      },
      {
        id: 'b-trk-color',
        frameNo: 1,
        classId: 1,
        className: 'person',
        source: 'AUTO_YOLO',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
        trackId: '99',
      },
    ];
    const { container } = renderWithProviders(<LabelPanel labels={withTrack} />);
    // 같은 trackId 면 같은 색, 다른 trackId 면 다른 색 (회색 fallback 아님)
    const bars = container.querySelectorAll('[data-testid="label-color-bar"]');
    expect(bars).toHaveLength(2);
    const color0 = (bars[0] as HTMLElement).style.backgroundColor;
    const color1 = (bars[1] as HTMLElement).style.backgroundColor;
    expect(color0).not.toBe('');
    expect(color1).not.toBe('');
    expect(color0).not.toBe(color1);
    // trackId 가 있으면 회색 fallback 이 아니어야 함
    // jsdom 은 hsl → rgb 변환하므로 rgb 비교
    const grayRgb = 'rgb(153, 153, 153)'; // hsl(0, 0%, 60%) 의 rgb 변환
    expect(color0).not.toBe(grayRgb);
    expect(color1).not.toBe(grayRgb);
    // 헬퍼 함수 사용 검증 — 같은 trackId 두 번 호출 결과가 같음
    expect(trackIdToColor('7')).toBe(trackIdToColor('7'));
  });

  it('trackId_없는_라벨은_회색_컬러_바', () => {
    useLabelStore.getState().reset();
    const noTrack: Label[] = [
      {
        id: 'a-no-trk-color',
        frameNo: 1,
        classId: 1,
        className: 'person',
        source: 'AUTO_YOLO',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      },
    ];
    const { container } = renderWithProviders(<LabelPanel labels={noTrack} />);
    const bar = container.querySelector('[data-testid="label-color-bar"]') as HTMLElement | null;
    expect(bar).not.toBeNull();
    // jsdom 은 hsl(0, 0%, 60%) 을 rgb(153, 153, 153) 으로 변환
    expect(bar!.style.backgroundColor).toBe('rgb(153, 153, 153)');
  });
});
