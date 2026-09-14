// 비모달 창(FloatingWindow)의 위치·크기 계산과 브라우저 기억 — 순수 함수 모듈. [@design UI-156]
//
// 컴포넌트에서 떼어낸 이유는 두 가지다.
//  1) 「복원 위치가 화면 밖이면 기본 위치」 판정은 렌더 없이 값으로 검증할 수 있어야 한다.
//  2) 저장소 접근은 예외를 던질 수 있는 경계(사생활 보호 모드·용량 초과)라 한 곳에 모은다 —
//     읽기가 실패하면 기본 위치로, 쓰기가 실패하면 조용히 넘긴다(창을 못 열게 만들지 않는다).
//
// 서버에 저장하지 않는다. 창 위치는 그 브라우저를 쓰는 사람의 화면 사정이지 계정 설정이 아니다.

export interface WindowSize {
  width: number;
  height: number;
}

export interface WindowRect extends WindowSize {
  x: number;
  y: number;
}

/** 화면 가장자리에서 띄우는 최소 여백(px). 창이 모서리에 붙어 잡기 어려워지는 것을 막는다. */
const EDGE_MARGIN = 8;

/**
 * 복원한 창이 「화면에 있다」로 인정되는 최소 노출 폭·높이(px).
 *
 * 제목 표시줄을 잡아 끌 수 있을 만큼은 보여야 한다 — 그만큼도 안 보이면 사용자는 창이 열렸다는
 * 사실조차 알 수 없고 되돌릴 수단도 없다(그래서 기본 위치로 연다).
 */
const MIN_VISIBLE_WIDTH = 120;
const MIN_VISIBLE_HEIGHT = 40;

/**
 * 기본 크기를 뷰포트 안으로 접는다.
 *
 * ★최소 크기가 뷰포트보다 크면 <b>최소 크기를 이긴다</b> — 칸이 겹쳐 못 읽게 되는 것보다
 * 창이 화면을 넘어 스크롤되는 편이 낫다(작은 화면에서 두 칸은 위아래로 쌓인다).
 */
export function clampSize(size: WindowSize, min: WindowSize, viewport: WindowSize): WindowSize {
  return {
    width: Math.max(min.width, Math.min(size.width, viewport.width - EDGE_MARGIN * 2)),
    height: Math.max(min.height, Math.min(size.height, viewport.height - EDGE_MARGIN * 2)),
  };
}

/** 뷰포트 중앙에 놓는다(음수 좌표가 나오지 않도록 여백에서 잘라낸다). */
export function centerRect(size: WindowSize, viewport: WindowSize): WindowRect {
  return {
    width: size.width,
    height: size.height,
    x: Math.max(EDGE_MARGIN, Math.round((viewport.width - size.width) / 2)),
    y: Math.max(EDGE_MARGIN, Math.round((viewport.height - size.height) / 2)),
  };
}

/**
 * 이 사각형이 화면에서 <b>조작 가능할 만큼</b> 보이는가.
 *
 * 단순히 좌표가 뷰포트 안인지가 아니라 「잡을 수 있는가」를 본다 — 화면 밖으로 거의 나간 창은
 * 좌표상 존재해도 사용자에게는 없는 것과 같다. 해상도가 줄어든 뒤 다시 열 때가 이 경우다.
 */
export function isRectOnScreen(rect: WindowRect, viewport: WindowSize): boolean {
  if (![rect.x, rect.y, rect.width, rect.height].every((n) => Number.isFinite(n))) return false;
  if (rect.width <= 0 || rect.height <= 0) return false;
  if (rect.y < 0) return false;
  const visibleRight = Math.min(rect.x + rect.width, viewport.width);
  const visibleLeft = Math.max(rect.x, 0);
  const visibleBottom = Math.min(rect.y + rect.height, viewport.height);
  return (
    visibleRight - visibleLeft >= MIN_VISIBLE_WIDTH &&
    visibleBottom - rect.y >= MIN_VISIBLE_HEIGHT
  );
}

/** 저장된 사각형을 읽는다. 저장소를 못 읽거나 형태가 아니면 null(호출부가 기본 위치로 간다). */
export function readStoredRect(storageKey: string): WindowRect | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    const raw = localStorage.getItem(storageKey);
    if (raw === null) return null;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const { x, y, width, height } = parsed as Partial<WindowRect>;
    if (
      typeof x !== 'number' ||
      typeof y !== 'number' ||
      typeof width !== 'number' ||
      typeof height !== 'number'
    ) {
      return null;
    }
    return { x, y, width, height };
  } catch {
    // 저장소를 읽지 못하는 것은 창을 못 여는 사유가 아니다 — 기본 위치로 연다.
    return null;
  }
}

/** 사각형을 기억한다. 실패해도 조용히 넘긴다(다음에 기본 위치로 열릴 뿐이다). */
export function writeStoredRect(storageKey: string, rect: WindowRect): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(storageKey, JSON.stringify(rect));
  } catch {
    /* 용량 초과·사생활 보호 모드 — 기억하지 못할 뿐 창은 정상 동작한다. */
  }
}

export interface InitialRectOptions {
  storageKey?: string;
  defaultSize: WindowSize;
  minSize: WindowSize;
  viewport: WindowSize;
}

/**
 * 창을 열 때 쓸 사각형 — 기억된 값이 쓸 만하면 그것, 아니면 기본 위치·크기.
 *
 * ★판정 순서가 사양이다: 저장소를 못 읽거나(null) 화면 밖이면 <b>기본 위치</b>다. 기억된 값을
 * 화면 안으로 끌어당겨 살려 쓰지 않는다 — 그러면 사용자가 마지막으로 둔 자리도 아니고 기본
 * 자리도 아닌 제3의 위치가 되어 「왜 여기 떴는지」를 설명할 수 없다.
 */
export function resolveInitialRect({
  storageKey,
  defaultSize,
  minSize,
  viewport,
}: InitialRectOptions): WindowRect {
  const fallback = centerRect(clampSize(defaultSize, minSize, viewport), viewport);
  if (storageKey === undefined) return fallback;
  const stored = readStoredRect(storageKey);
  if (stored === null) return fallback;
  const sized: WindowRect = {
    ...stored,
    ...clampSize({ width: stored.width, height: stored.height }, minSize, viewport),
  };
  return isRectOnScreen(sized, viewport) ? sized : fallback;
}
