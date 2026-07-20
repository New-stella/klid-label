// Phase 4 — 오토라벨/추적 결과 병합 시 중복 스킵(mergeDetections) 유닛 테스트.

import { describe, expect, it } from 'vitest';

import type { Label } from '../types';
import { bboxIoU, mergeDetections, polygonIoU, shapeIoU } from '../utils/labelDedup';

function bbox(
  id: string,
  l: number,
  t: number,
  r: number,
  b: number,
  extra?: Partial<Label>,
): Label {
  return {
    id,
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'AUTO_YOLO',
    shape: { type: 'BBOX', left: l, top: t, right: r, bottom: b },
    ...extra,
  };
}

function poly(id: string, points: number[], extra?: Partial<Label>): Label {
  return {
    id,
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'AUTO_SAM2',
    shape: { type: 'POLYGON', points },
    ...extra,
  };
}

describe('bboxIoU', () => {
  it('완전_동일_박스는_IoU_1', () => {
    expect(bboxIoU([0, 0, 10, 10], [0, 0, 10, 10])).toBeCloseTo(1, 5);
  });
  it('겹치지_않는_박스는_IoU_0', () => {
    expect(bboxIoU([0, 0, 10, 10], [100, 100, 110, 110])).toBe(0);
  });
  it('절반_겹침', () => {
    // A=[0,0,10,10](100), B=[5,0,15,10](100), inter=5*10=50, union=150 → 1/3
    expect(bboxIoU([0, 0, 10, 10], [5, 0, 15, 10])).toBeCloseTo(1 / 3, 5);
  });
});

describe('polygonIoU', () => {
  it('동일_폴리곤은_IoU_거의_1', () => {
    const p = [0, 0, 10, 0, 10, 10, 0, 10];
    expect(polygonIoU(p, p)).toBeGreaterThan(0.95);
  });
  it('겹치지_않는_폴리곤은_IoU_0', () => {
    const a = [0, 0, 10, 0, 10, 10, 0, 10];
    const b = [100, 100, 110, 100, 110, 110, 100, 110];
    expect(polygonIoU(a, b)).toBe(0);
  });
});

describe('shapeIoU — 다른 shape.type 조합은 보수적 0', () => {
  it('BBOX_vs_POLYGON은_겹쳐도_0', () => {
    const box = bbox('1', 0, 0, 10, 10).shape;
    const pg = poly('2', [0, 0, 10, 0, 10, 10, 0, 10]).shape;
    expect(shapeIoU(box, pg)).toBe(0);
  });
});

describe('mergeDetections', () => {
  it('같은_클래스이고_IoU가_임계값이상이면_병합에서_스킵된다', () => {
    const existing = [bbox('e1', 0, 0, 100, 100)];
    const detected = [bbox('d1', 2, 2, 100, 100)]; // 거의 동일 → IoU≈1
    const merged = mergeDetections(existing, detected, 0.7);
    expect(merged).toHaveLength(0);
  });

  it('다른_클래스는_겹쳐도_병합된다', () => {
    const existing = [bbox('e1', 0, 0, 100, 100, { className: 'person', labelId: 1 })];
    const detected = [bbox('d1', 0, 0, 100, 100, { className: 'car', labelId: 2 })];
    const merged = mergeDetections(existing, detected, 0.7);
    expect(merged).toHaveLength(1);
    expect(merged[0].className).toBe('car');
  });

  it('IoU가_임계값_미만이면_유지된다', () => {
    const existing = [bbox('e1', 0, 0, 10, 10)];
    const detected = [bbox('d1', 8, 8, 18, 18)]; // 작은 겹침
    const merged = mergeDetections(existing, detected, 0.7);
    expect(merged).toHaveLength(1);
  });

  it('검출_세트_내부_중복도_제거된다', () => {
    const detected = [bbox('d1', 0, 0, 100, 100), bbox('d2', 1, 1, 100, 100)];
    const merged = mergeDetections([], detected, 0.7);
    expect(merged).toHaveLength(1);
  });

  it('폴리곤_동일_객체는_스킵된다', () => {
    const p = [0, 0, 100, 0, 100, 100, 0, 100];
    const existing = [poly('e1', p)];
    const detected = [poly('d1', p)];
    expect(mergeDetections(existing, detected, 0.7)).toHaveLength(0);
  });

  it('IoU가_정확히_임계값_0_7이면_스킵된다_경계포함', () => {
    // A=[0,0,10,10] area 100. B=[0,0,10,7] area 70 (A 내부). inter=70, union=100 → IoU=0.7.
    // 임계값 비교가 `>= 0.7` 이므로 경계값에서 스킵되어야 한다.
    const existing = [bbox('e1', 0, 0, 10, 10)];
    const detected = [bbox('d1', 0, 0, 10, 7)];
    expect(mergeDetections(existing, detected, 0.7)).toHaveLength(0);
  });

  it('IoU가_임계값_바로_아래_0_6이면_유지된다_경계밖', () => {
    // A=[0,0,10,10] area 100. B=[0,0,10,6] area 60. inter=60, union=100 → IoU=0.6 < 0.7.
    const existing = [bbox('e1', 0, 0, 10, 10)];
    const detected = [bbox('d1', 0, 0, 10, 6)];
    expect(mergeDetections(existing, detected, 0.7)).toHaveLength(1);
  });

  it('폴리곤_겹치지않는_다른위치는_병합되어_유지된다', () => {
    const existing = [poly('e1', [0, 0, 10, 0, 10, 10, 0, 10])];
    const detected = [poly('d1', [100, 100, 110, 100, 110, 110, 100, 110])];
    const merged = mergeDetections(existing, detected, 0.7);
    expect(merged).toHaveLength(1);
    expect(merged[0].shape.type).toBe('POLYGON');
  });
});
