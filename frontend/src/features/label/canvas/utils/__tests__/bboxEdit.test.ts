// R17 이슈4: 기존 BBOX 이동/리사이즈 → 이미지 좌표 변환 + 경계 클램프 순수 유틸 테스트.

import { describe, expect, it } from 'vitest';

import { buildGeometry } from '../canvasGeometry';
import { canvasRectToImageBox, MIN_BOX_SIZE } from '../bboxEdit';

const CANVAS = { width: 200, height: 200 };
const IMAGE = { width: 100, height: 100 };

// zoom=1, pan=0 → fitScale=min(200/100,200/100)=2, scale=2, 이미지 100x100 → 캔버스 200x200, left/top=0
function geom(zoom = 1, panX = 0, panY = 0) {
  return buildGeometry(IMAGE, CANVAS, zoom, panX, panY, 0);
}

describe('canvasRectToImageBox — 캔버스 Rect → 이미지 BBOX', () => {
  it('이동된 캔버스 Rect 가 이미지 좌표로 환산된다 (scale 보정)', () => {
    // 캔버스 (20,20) 크기 40x40 → 이미지 (10,10) 크기 20x20 (scale=2)
    const box = canvasRectToImageBox(geom(), { x: 20, y: 20, width: 40, height: 40 });
    expect(box).not.toBeNull();
    expect(box!.left).toBeCloseTo(10, 4);
    expect(box!.top).toBeCloseTo(10, 4);
    expect(box!.right).toBeCloseTo(30, 4);
    expect(box!.bottom).toBeCloseTo(30, 4);
  });

  it('이미지 밖으로 나간 좌표는 경계로 클램프된다', () => {
    // 캔버스 (-40,-40) 크기 80x80 → 이미지 (-20,-20)~(20,20) → 좌상단 클램프 0,0
    const box = canvasRectToImageBox(geom(), { x: -40, y: -40, width: 80, height: 80 });
    expect(box).not.toBeNull();
    expect(box!.left).toBeCloseTo(0, 4);
    expect(box!.top).toBeCloseTo(0, 4);
    expect(box!.right).toBeCloseTo(20, 4);
    expect(box!.bottom).toBeCloseTo(20, 4);
  });

  it('우/하단도 이미지 크기로 클램프된다', () => {
    // 캔버스 (180,180) 크기 80x80 → 이미지 (90,90)~(130,130) → 우하단 클램프 100,100
    const box = canvasRectToImageBox(geom(), { x: 180, y: 180, width: 80, height: 80 });
    expect(box).not.toBeNull();
    expect(box!.left).toBeCloseTo(90, 4);
    expect(box!.top).toBeCloseTo(90, 4);
    expect(box!.right).toBeCloseTo(100, 4);
    expect(box!.bottom).toBeCloseTo(100, 4);
  });

  it('최소 크기 미만(0/음수 크기) 박스는 null 반환 (무효)', () => {
    expect(canvasRectToImageBox(geom(), { x: 20, y: 20, width: 0, height: 0 })).toBeNull();
    expect(
      canvasRectToImageBox(geom(), { x: 20, y: 20, width: MIN_BOX_SIZE * 2, height: 0.1 }),
    ).toBeNull();
  });

  it('pan/zoom 이 적용된 geometry 에서도 정확히 환산된다', () => {
    // fit(zoom=1)에서는 pan 이 여백 방지 클램프로 항상 0(중앙)이므로, pan 이 유효한 줌인
    // 상태(zoom=2, scale=4)로 검증한다. left=(200-400)/2+40=-60, top=(200-400)/2+20=-80.
    const g = geom(2, 40, 20);
    // 캔버스 (-60,-80) → 이미지 (0,0). 캔버스 (20,0) → 이미지 (20,20). (scale=4)
    const box = canvasRectToImageBox(g, { x: -60, y: -80, width: 80, height: 80 });
    expect(box!.left).toBeCloseTo(0, 4);
    expect(box!.top).toBeCloseTo(0, 4);
    expect(box!.right).toBeCloseTo(20, 4);
    expect(box!.bottom).toBeCloseTo(20, 4);
  });
});
