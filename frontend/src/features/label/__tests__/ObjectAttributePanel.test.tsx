import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import type { Label } from '../types';

const labelLowConf: Label = {
  id: 'a',
  frameNo: 1,
  classId: 3,
  className: 'pedestrian',
  source: 'AUTO_YOLO',
  confidence: 0.4,
  shape: { type: 'BBOX', left: 0, top: 0, right: 100, bottom: 50 },
};

describe('ObjectAttributePanel', () => {
  it('선택된_라벨이_없으면_안내_문구', () => {
    useLabelStore.getState().reset();
    renderWithProviders(<ObjectAttributePanel labels={[]} />);
    expect(screen.getByText(/선택된 객체가 없습니다/)).toBeInTheDocument();
  });

  it('신뢰도_0_5_미만_라벨_경고_뱃지', () => {
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([labelLowConf]);
    useLabelStore.getState().selectLabel('a');
    renderWithProviders(<ObjectAttributePanel labels={[labelLowConf]} />);
    expect(screen.getByRole('status')).toHaveTextContent('낮은 신뢰도');
  });

  it('트랙_ID_있는_객체는_트랙_ID_필드에_원값_표시', () => {
    const tracked: Label = { ...labelLowConf, id: 't', trackId: '42' };
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([tracked]);
    useLabelStore.getState().selectLabel('t');
    renderWithProviders(<ObjectAttributePanel labels={[tracked]} />);
    // "트랙 ID" 라벨이 붙은 별도 필드에 track_id 원값(42) 노출.
    expect(screen.getByText('트랙 ID')).toBeInTheDocument();
    expect(screen.getByTestId('object-track-id')).toHaveTextContent('42');
  });

  it('트랙_ID_없는_객체는_미부여_표기', () => {
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([labelLowConf]);
    useLabelStore.getState().selectLabel('a');
    renderWithProviders(<ObjectAttributePanel labels={[labelLowConf]} />);
    expect(screen.getByText('트랙 ID')).toBeInTheDocument();
    expect(screen.getByTestId('object-track-id')).toHaveTextContent('미부여');
  });

  it('BBOX_좌표_표시', () => {
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([labelLowConf]);
    useLabelStore.getState().selectLabel('a');
    renderWithProviders(<ObjectAttributePanel labels={[labelLowConf]} />);
    // Phase 6: 좌표는 X/Y/W/H input 필드로 편집 가능 — 각 input의 value 확인
    const xInput = screen.getByLabelText(/X 좌표/i) as HTMLInputElement;
    const yInput = screen.getByLabelText(/Y 좌표/i) as HTMLInputElement;
    const wInput = screen.getByLabelText(/W 우측/i) as HTMLInputElement;
    const hInput = screen.getByLabelText(/H 하단/i) as HTMLInputElement;
    expect(xInput.value).toBe('0');
    expect(yInput.value).toBe('0');
    expect(wInput.value).toBe('100');
    expect(hInput.value).toBe('50');
  });

  it('정점_적은_폴리곤은_꼭짓점_편집_안내문구_없음', () => {
    const smallPoly: Label = {
      id: 'sp',
      frameNo: 1,
      classId: 1,
      className: 'car',
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: [0, 0, 10, 0, 5, 10] },
    };
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([smallPoly]);
    useLabelStore.getState().selectLabel('sp');
    renderWithProviders(<ObjectAttributePanel labels={[smallPoly]} />);
    expect(screen.getByText(/3개 정점/)).toBeInTheDocument();
    expect(screen.queryByText(/꼭짓점 편집은 비활성화/)).not.toBeInTheDocument();
  });

  it('ObjectAttributePanel_KEYPOINT_좌표표시_Mask아님', () => {
    const keypoints = Array.from({ length: 17 }, (_, i) => ({
      x: i,
      y: i,
      v: i < 10 ? 2 : i < 14 ? 1 : 0,
    }));
    const kpLabel: Label = {
      id: 'kp',
      frameNo: 1,
      classId: 2,
      className: 'person',
      source: 'MANUAL',
      shape: { type: 'KEYPOINT', keypoints },
    };
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([kpLabel]);
    useLabelStore.getState().selectLabel('kp');
    renderWithProviders(<ObjectAttributePanel labels={[kpLabel]} />);
    // "Mask" 로 오표시되면 안 되고, 키포인트 요약이 표시되어야 한다.
    expect(screen.queryByText('Mask')).not.toBeInTheDocument();
    expect(screen.getByText(/키포인트/)).toBeInTheDocument();
    expect(screen.getByText(/17/)).toBeInTheDocument();
  });

  it('정점_많은_폴리곤은_꼭짓점_편집_비활성_안내문구', () => {
    const points: number[] = [];
    for (let i = 0; i < 150; i += 1) points.push(i, i);
    const bigPoly: Label = {
      id: 'bp',
      frameNo: 1,
      classId: 1,
      className: 'car',
      source: 'AUTO_SAM2',
      shape: { type: 'POLYGON', points },
    };
    useLabelStore.getState().reset();
    useLabelStore.getState().setLabels([bigPoly]);
    useLabelStore.getState().selectLabel('bp');
    renderWithProviders(<ObjectAttributePanel labels={[bigPoly]} />);
    expect(screen.getByText(/꼭짓점 편집은 비활성화/)).toBeInTheDocument();
  });
});
