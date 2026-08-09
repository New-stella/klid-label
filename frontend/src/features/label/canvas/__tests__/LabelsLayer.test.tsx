// Phase 4: LabelsLayer 점선/실선 외곽선 — react-konva 모킹으로 prop 검증.

import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

// LabelsLayer 가 Phase 3 부터 useLabelMasters() 를 호출 — TanStack Query 의존성 회피용 모킹.
// 기본은 빈 배열(미로드 상태와 동일) — 색상 결정은 label.color → trackIdToColor → source fallback 순.
vi.mock('../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: [], isLoading: false, isError: false }),
}));

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

describe('LabelsLayer — Phase 3 LS_LABEL.color 매핑', () => {
  it('label.color 가 #RRGGBB 면 그대로 stroke 사용 (LS_LABEL.color 우선)', () => {
    const labels: Label[] = [
      bbox({ id: 'c1', color: '#FF0000', trackId: '99' }),
    ];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);
    const rect = container.querySelector('[data-konva="Rect"]') as HTMLElement | null;
    expect(rect).not.toBeNull();
    expect(rect!.getAttribute('stroke')).toBe('#FF0000');
  });

  it('label.color 미설정이고 trackId 있으면 trackIdToColor 사용 (fallback 단계)', () => {
    const labels: Label[] = [bbox({ id: 'c2', trackId: '7', color: null })];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);
    const rect = container.querySelector('[data-konva="Rect"]') as HTMLElement | null;
    expect(rect).not.toBeNull();
    // hsl(...) 형태로 trackId 해시 색상이 들어옴
    expect(rect!.getAttribute('stroke')).toMatch(/^hsl\(\d+, 70%, 50%\)$/);
  });

  it('POLYGON 도 label.color 우선 (#RRGGBB)', () => {
    const labels: Label[] = [polygon({ id: 'c3', color: '#3B82F6' })];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);
    const line = container.querySelector('[data-konva="Line"]') as HTMLElement | null;
    expect(line).not.toBeNull();
    expect(line!.getAttribute('stroke')).toBe('#3B82F6');
    // POLYGON fill 은 `${stroke}33` (33 alpha) — getLabelDisplayColor 결과 그대로 사용 검증
    expect(line!.getAttribute('fill')).toBe('#3B82F633');
  });

  it('잘못된 color 포맷 (#FFF 등) 은 무시되고 fallback 진행', () => {
    // normalizeLabel 단계에서 걸러지지만 캔버스 직접 입력 케이스 안전망 검증
    const labels: Label[] = [
      bbox({ id: 'c4', color: '#FFF' as unknown as string, trackId: '7' }),
    ];
    const { container } = render(<LabelsLayer labels={labels} geometry={geom} />);
    const rect = container.querySelector('[data-konva="Rect"]') as HTMLElement | null;
    expect(rect).not.toBeNull();
    // #FFF 는 6자리가 아니므로 무시 → trackId 색상으로 fallback
    expect(rect!.getAttribute('stroke')).toMatch(/^hsl\(\d+, 70%, 50%\)$/);
  });
});
