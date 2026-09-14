// 비모달 창의 위치·크기 계산 — 순수 함수 시험. [@design UI-156]
//
// ★렌더 없이 값으로 판정한다. 「복원 위치가 화면 밖이면 기본 위치」는 해상도가 줄어든 뒤 다시
//   열 때만 나타나는 경로라, 화면으로 재현하려면 뷰포트를 흉내 내야 하고 그러면 정작 판정식은
//   검사되지 않는다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  centerRect,
  clampSize,
  isRectOnScreen,
  readStoredRect,
  resolveInitialRect,
  writeStoredRect,
} from '../floatingWindowPosition';

const KEY = 'test.window.rect';
const VIEWPORT = { width: 1920, height: 1080 };
const DEFAULT_SIZE = { width: 1440, height: 810 };
const MIN_SIZE = { width: 560, height: 640 };

describe('floatingWindowPosition', () => {
  beforeEach(() => {
    localStorage.removeItem(KEY);
  });
  afterEach(() => {
    localStorage.removeItem(KEY);
  });

  it('기본_크기는_뷰포트_안으로_접힌다', () => {
    expect(clampSize(DEFAULT_SIZE, MIN_SIZE, VIEWPORT)).toEqual({ width: 1440, height: 810 });
    // 뷰포트가 작으면 그 안으로 접는다(여백 8px 씩).
    expect(clampSize(DEFAULT_SIZE, MIN_SIZE, { width: 1024, height: 768 })).toEqual({
      width: 1008,
      height: 752,
    });
  });

  it('★뷰포트가_최소_크기보다_작으면_최소_크기가_이긴다', () => {
    // 칸이 겹쳐 못 읽게 되는 것보다 창이 화면을 넘는 편이 낫다(작은 화면에서는 위아래로 쌓인다).
    expect(clampSize(DEFAULT_SIZE, MIN_SIZE, { width: 400, height: 500 })).toEqual({
      width: 560,
      height: 640,
    });
  });

  it('기본_위치는_뷰포트_중앙이다', () => {
    expect(centerRect({ width: 1440, height: 810 }, VIEWPORT)).toEqual({
      width: 1440,
      height: 810,
      x: 240,
      y: 135,
    });
  });

  it('★화면에서_잡을_수_없을_만큼_나간_사각형은_화면_밖으로_본다', () => {
    const size = { width: 600, height: 700 };
    // 대부분 보이면 화면 안이다.
    expect(isRectOnScreen({ ...size, x: 100, y: 50 }, VIEWPORT)).toBe(true);
    // 오른쪽으로 거의 다 나가 잡을 수 있는 폭이 남지 않으면 화면 밖이다.
    expect(isRectOnScreen({ ...size, x: 1900, y: 50 }, VIEWPORT)).toBe(false);
    // 제목 표시줄이 화면 위로 올라가면 되찾을 수단이 없다.
    expect(isRectOnScreen({ ...size, x: 100, y: -40 }, VIEWPORT)).toBe(false);
    // 아래로 거의 다 나가 제목 표시줄이 보이지 않아도 마찬가지다.
    expect(isRectOnScreen({ ...size, x: 100, y: 1070 }, VIEWPORT)).toBe(false);
  });

  it('기억한_위치를_다시_읽는다', () => {
    writeStoredRect(KEY, { x: 10, y: 20, width: 800, height: 700 });
    expect(readStoredRect(KEY)).toEqual({ x: 10, y: 20, width: 800, height: 700 });
  });

  it('형태가_아닌_값은_읽지_않는다_저장소_오염_방어', () => {
    localStorage.setItem(KEY, 'not json');
    expect(readStoredRect(KEY)).toBeNull();
    localStorage.setItem(KEY, JSON.stringify({ x: '10', y: 20, width: 800, height: 700 }));
    expect(readStoredRect(KEY)).toBeNull();
    localStorage.setItem(KEY, JSON.stringify([1, 2, 3]));
    expect(readStoredRect(KEY)).toBeNull();
  });

  it('기억한_위치가_쓸_만하면_그것으로_연다', () => {
    writeStoredRect(KEY, { x: 100, y: 60, width: 900, height: 700 });
    expect(
      resolveInitialRect({
        storageKey: KEY,
        defaultSize: DEFAULT_SIZE,
        minSize: MIN_SIZE,
        viewport: VIEWPORT,
      }),
    ).toEqual({ x: 100, y: 60, width: 900, height: 700 });
  });

  it('★복원_위치가_화면_밖이면_기본_위치로_연다', () => {
    // given — 큰 화면에서 오른쪽 끝에 두고 닫은 뒤, 작은 화면에서 다시 여는 경우.
    writeStoredRect(KEY, { x: 1800, y: 40, width: 900, height: 700 });

    // when
    const rect = resolveInitialRect({
      storageKey: KEY,
      defaultSize: DEFAULT_SIZE,
      minSize: MIN_SIZE,
      viewport: { width: 1024, height: 768 },
    });

    // then — 기억된 값을 화면 안으로 «끌어당겨» 살려 쓰지 않는다. 그러면 마지막으로 둔 자리도
    //   기본 자리도 아닌 제3의 위치가 되어 왜 거기 떴는지 설명할 수 없다.
    expect(rect).toEqual(centerRect(clampSize(DEFAULT_SIZE, MIN_SIZE, { width: 1024, height: 768 }), {
      width: 1024,
      height: 768,
    }));
  });

  it('★저장소를_읽지_못해도_창은_기본_위치로_열린다', () => {
    // given — 사생활 보호 모드 등으로 읽기가 실패하는 경우를 흉내 낸다.
    const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('denied');
      },
    });
    try {
      expect(readStoredRect(KEY)).toBeNull();
      expect(
        resolveInitialRect({
          storageKey: KEY,
          defaultSize: DEFAULT_SIZE,
          minSize: MIN_SIZE,
          viewport: VIEWPORT,
        }),
      ).toEqual(centerRect(DEFAULT_SIZE, VIEWPORT));
      // 쓰기 실패도 창을 못 열게 만들지 않는다.
      expect(() => writeStoredRect(KEY, { x: 1, y: 1, width: 600, height: 700 })).not.toThrow();
    } finally {
      if (original) Object.defineProperty(globalThis, 'localStorage', original);
    }
  });

  it('기억하지_않는_창은_언제나_기본_위치다', () => {
    expect(
      resolveInitialRect({ defaultSize: DEFAULT_SIZE, minSize: MIN_SIZE, viewport: VIEWPORT }),
    ).toEqual(centerRect(DEFAULT_SIZE, VIEWPORT));
  });
});
