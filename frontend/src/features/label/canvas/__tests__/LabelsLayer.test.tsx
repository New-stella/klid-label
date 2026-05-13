// Phase 4: LabelsLayer 점선/실선 외곽선 — react-konva 모킹으로 prop 검증.

import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, dash, ...rest }: any) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (dash !== undefined) {
        props['data-dash'] = Array.isArray(dash) ? dash.join(',') : String(dash);
      }
      return React.createElement('div', props, children);
    };
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
  };
});

import { LabelsLayer } from '../layers/LabelsLayer';
import type { Geometry } from '../utils/coordinateTransformer';
import type { Label } from '../../types';

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

function bbox(over: Partial<Label> & Pick<Label, 'id'>): Label {
  return {
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'AUTO_YOLO',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
    ...over,
  };
}

function polygon(over: Partial<Label> & Pick<Label, 'id'>): Label {
  return {
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'AUTO_SAM2',
    shape: { type: 'POLYGON', points: [0, 0, 10, 0, 10, 10] },
    ...over,
  };
}

describe('LabelsLayer — Phase 4 INTERPOLATED 점선 외곽선', () => {
  it('LabelsLayer — INTERPOLATED 라벨은 점선 외곽선 (dash 적용)', () => {
    const labels: Label[] = [
      bbox({ id: 'a', trackId: '7', lblSrcCd: 'INTERPOLATED' }),
    ];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);

    const rect = container.querySelector('[data-konva="Rect"]') as HTMLElement | null;
    expect(rect).not.toBeNull();
    // dash prop 이 배열로 전달되어야 함
    const dashAttr = rect!.getAttribute('data-dash');
    expect(dashAttr).not.toBeNull();
    expect(dashAttr).toMatch(/\d+,\d+/);
  });

  it('LabelsLayer — DETECTED (lblSrcCd null) 라벨은 실선 (dash 없음)', () => {
    const labels: Label[] = [
      bbox({ id: 'a', trackId: '7', lblSrcCd: null }),
    ];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);

    const rect = container.querySelector('[data-konva="Rect"]') as HTMLElement | null;
    expect(rect).not.toBeNull();
    // dash prop 이 전달되지 않거나 빈 값
    expect(rect!.getAttribute('data-dash')).toBeNull();
  });

  it('LabelsLayer — POLYGON 도 INTERPOLATED 면 점선 적용', () => {
    const labels: Label[] = [
      polygon({ id: 'p1', trackId: '8', lblSrcCd: 'INTERPOLATED' }),
    ];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);

    const line = container.querySelector('[data-konva="Line"]') as HTMLElement | null;
    expect(line).not.toBeNull();
    const dashAttr = line!.getAttribute('data-dash');
    expect(dashAttr).not.toBeNull();
    expect(dashAttr).toMatch(/\d+,\d+/);
  });

  it('LabelsLayer — lblSrcCd 미설정 시 회귀 안전 (실선)', () => {
    // legacy 응답 시뮬 — lblSrcCd 필드 자체가 없음
    const labels: Label[] = [bbox({ id: 'a', trackId: '7' })];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);

    const rect = container.querySelector('[data-konva="Rect"]') as HTMLElement | null;
    expect(rect).not.toBeNull();
    expect(rect!.getAttribute('data-dash')).toBeNull();
  });
});
