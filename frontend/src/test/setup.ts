import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

import { useAdminSessionStore } from '@/features/adminSession/store';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

// vitest 의 jsdom 환경은 `localStorage`/`sessionStorage` 를 메서드 없는 빈 객체로만 노출한다
// (`localStorage.setItem is not a function`). production 코드(tokenIngress 등)는 실제 Storage
// API 를 사용하므로, 테스트 인프라 레벨에서 Storage 폴리필을 전역 주입한다.
// NOTE: production 코드는 건드리지 않는다 — 테스트 환경 setup 만 보정.
class MemoryStorage implements Storage {
  private store = new Map<string, string>();

  get length(): number {
    return this.store.size;
  }

  clear(): void {
    this.store.clear();
  }

  getItem(key: string): string | null {
    return this.store.has(key) ? (this.store.get(key) as string) : null;
  }

  key(index: number): string | null {
    return Array.from(this.store.keys())[index] ?? null;
  }

  removeItem(key: string): void {
    this.store.delete(key);
  }

  setItem(key: string, value: string): void {
    this.store.set(key, String(value));
  }
}

function ensureStorage(name: 'localStorage' | 'sessionStorage'): void {
  const current = (globalThis as { [k: string]: unknown })[name] as Storage | undefined;
  if (current && typeof current.setItem === 'function') return;
  const storage = new MemoryStorage();
  Object.defineProperty(globalThis, name, {
    value: storage,
    configurable: true,
    writable: true,
  });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, name, {
      value: storage,
      configurable: true,
      writable: true,
    });
  }
}

ensureStorage('localStorage');
ensureStorage('sessionStorage');

// jsdom 은 ResizeObserver 를 구현하지 않는다. Radix Checkbox 는 `<form>` 안에서 네이티브
// 제출값을 위한 숨은 input 을 만들고 그 크기를 ResizeObserver 로 재므로, 폴리필이 없으면
// 폼 안의 체크박스를 렌더하는 테스트가 "ResizeObserver is not defined" 로 즉사한다.
// NOTE: production 코드는 건드리지 않는다 — 테스트 환경 setup 만 보정.
if (typeof globalThis.ResizeObserver === 'undefined') {
  class NoopResizeObserver implements ResizeObserver {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
  globalThis.ResizeObserver = NoopResizeObserver as unknown as typeof ResizeObserver;
}

// jsdom 은 PointerEvent 캡처 API(`hasPointerCapture`/`setPointerCapture`/`releasePointerCapture`)와
// `scrollIntoView` 를 구현하지 않는다. Radix `Select`(UI-003)는 트리거 pointerdown 처리와 옵션
// 스크롤 정렬에 이 API 들을 직접 호출하므로, 폴리필이 없으면 "target.hasPointerCapture is not a
// function"/"scrollIntoView is not a function" 로 즉사한다(Radix 진영에 알려진 jsdom 제약).
// NOTE: production 코드는 건드리지 않는다 — 테스트 환경 setup 만 보정.
if (typeof Element.prototype.hasPointerCapture !== 'function') {
  Element.prototype.hasPointerCapture = () => false;
}
if (typeof Element.prototype.setPointerCapture !== 'function') {
  Element.prototype.setPointerCapture = () => {};
}
if (typeof Element.prototype.releasePointerCapture !== 'function') {
  Element.prototype.releasePointerCapture = () => {};
}
if (typeof Element.prototype.scrollIntoView !== 'function') {
  Element.prototype.scrollIntoView = () => {};
}

// 테스트 환경에서는 zustand persist hydration 이 즉시 끝난 상태로 가정한다.
// 가드 컴포넌트들이 `isHydrated=false` 일 때 "인증 확인 중" Spinner 만 렌더하기 때문에
// 모든 테스트 시작 시점에 hydration 완료 플래그를 강제 셋팅한다.
// 각 테스트의 `clear()` 호출은 token/claims 만 비우고 isHydrated 는 유지하므로 안전하다.
beforeEach(() => {
  useAuthStore.setState({ isHydrated: true });
  // 차단 안내 dedupe 는 화면 단위 단일 저장소(useUiStore)에 있다. 테스트 간에 남으면 앞 테스트의
  // 안내가 뒤 테스트의 같은 문구를 삼켜 위양성 실패가 난다 — 매 테스트 시작 시 비운다.
  useUiStore.getState().resetBlockNotice();
  // 관리자 단기 유효창은 모듈 스코프 스토어라 테스트 사이에 남는다. 남으면 앞 테스트가 연 창이
  // 뒤 테스트의 「잠겨 있다」 단언을 통과시켜, 잠금이 풀려도 초록으로 남는다.
  useAdminSessionStore.getState().clear();
});

afterEach(() => {
  cleanup();
});

// recharts ResponsiveContainer는 jsdom에서 width/height=0이라 차트가 비어 렌더된다.
// 테스트에서는 고정 크기로 감싸서 자식 차트 SVG가 정상 마운트되도록 한다.
vi.mock('recharts', async () => {
  const actual = await vi.importActual<typeof import('recharts')>('recharts');
  const React = await vi.importActual<typeof import('react')>('react');
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) =>
      React.createElement(
        'div',
        { 'data-testid': 'responsive-container', style: { width: 600, height: 240 } },
        children,
      ),
  };
});
