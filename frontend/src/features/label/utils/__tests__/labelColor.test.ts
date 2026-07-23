import { describe, expect, it } from 'vitest';

import type { LabelMaster } from '../../api/labelMaster';
import type { Label } from '../../types';
import { getLabelDisplayColor } from '../labelColor';

function makeLabel(over: Partial<Label> = {}): Label {
  return {
    id: '1',
    frameNo: 1,
    classId: 0,
    className: 'person',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
    ...over,
  };
}

const MASTERS: LabelMaster[] = [
  { labelId: 1, name: '사람', color: '#FF0000', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: null },
  { labelId: 2, name: '차량', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y', dtctTypeCd: null },
];

describe('getLabelDisplayColor — 우선순위 검증', () => {
  it('label.color 가 #RRGGBB 면 최우선', () => {
    const label = makeLabel({ color: '#00FF00', labelId: 1, trackId: '7' });
    expect(getLabelDisplayColor(label, MASTERS)).toBe('#00FF00');
  });

  it('label.color 가 null 이면 labelMasters[labelId].color 로 lookup', () => {
    const label = makeLabel({ color: null, labelId: 1, trackId: '7' });
    expect(getLabelDisplayColor(label, MASTERS)).toBe('#FF0000');
  });

  it('label.color 가 잘못된 포맷이면 lookup 으로 fallback', () => {
    const label = makeLabel({ color: 'red' as unknown as string, labelId: 2 });
    expect(getLabelDisplayColor(label, MASTERS)).toBe('#3B82F6');
  });

  it('labelId 매칭 실패 + trackId 있으면 trackIdToColor (HSL)', () => {
    const label = makeLabel({ color: null, labelId: 99, trackId: '7' });
    expect(getLabelDisplayColor(label, MASTERS)).toMatch(/^hsl\(\d+, 70%, 50%\)$/);
  });

  it('labelId/color 모두 없고 trackId 도 없으면 source 별 fallback (#RRGGBB)', () => {
    const label = makeLabel({ color: null, labelId: null, trackId: null, source: 'AUTO_YOLO' });
    expect(getLabelDisplayColor(label, MASTERS)).toBe('#5B8FF9');
  });

  it('AUTO_SAM2 fallback', () => {
    const label = makeLabel({ color: null, labelId: null, trackId: null, source: 'AUTO_SAM2' });
    expect(getLabelDisplayColor(label, MASTERS)).toBe('#9C6CF9');
  });

  it('MANUAL fallback', () => {
    const label = makeLabel({ color: null, labelId: null, trackId: null, source: 'MANUAL' });
    expect(getLabelDisplayColor(label, MASTERS)).toBe('#26A69A');
  });

  it('labelMasters 미로드(undefined) 시 trackId fallback 정상 동작', () => {
    const label = makeLabel({ color: null, labelId: 1, trackId: '7' });
    expect(getLabelDisplayColor(label, undefined)).toMatch(/^hsl\(\d+, 70%, 50%\)$/);
  });

  it('labelMasters 비어있어도 label.color 가 있으면 그대로 사용', () => {
    const label = makeLabel({ color: '#ABCDEF' });
    expect(getLabelDisplayColor(label, [])).toBe('#ABCDEF');
  });

  it('labelId 누락이지만 classId 가 양수면 classId 로 lookup (legacy 호환)', () => {
    // Phase 2 이전 응답: BE 가 labelId 를 아직 채우지 않고 classId(과거 LBL_TYPE_CD 기반)만 노출하던 케이스
    const label = makeLabel({ color: null, labelId: null, classId: 2 });
    expect(getLabelDisplayColor(label, MASTERS)).toBe('#3B82F6');
  });

  it('useTrackFallback=false 옵션이면 trackId 단계를 건너뛰고 곧바로 source fallback', () => {
    const label = makeLabel({ color: null, labelId: null, trackId: '7', source: 'AUTO_YOLO' });
    expect(getLabelDisplayColor(label, MASTERS, { useTrackFallback: false })).toBe('#5B8FF9');
  });

  it('대문자/소문자 HEX 모두 허용', () => {
    expect(getLabelDisplayColor(makeLabel({ color: '#ff0000' }), MASTERS)).toBe('#ff0000');
    expect(getLabelDisplayColor(makeLabel({ color: '#FF0000' }), MASTERS)).toBe('#FF0000');
  });
});
