// Phase 3 DEV_FIX D4 / M4 — ESC 취소 정책.
//
// 고정하는 것:
//  - **지연 창(<300ms) 안의 ESC 취소는 반드시 알린다**(D4). 이 구간에는 오버레이가 뜬 적이 없어
//    취소가 아무 흔적도 남기지 않는다 — 사용자는 결과를 무한정 기다린다.
//  - 오버레이가 보이는 구간의 취소는 시각 피드백이 이미 있으므로 중복 안내하지 않는다.
//  - ESC 취소 판정은 **단일 헬퍼 하나**다(M4). 여러 리스너가 같은 ESC 에 반응해도 안내는 1회.

import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it } from 'vitest';
import { type ReactNode } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { BUSY_OVERLAY_DELAY_MS, handleBusyEscape } from '../busyPolicy';
import { useLabelingShortcuts } from '../hooks/useLabelingShortcuts';

function press(key: string) {
  window.dispatchEvent(new KeyboardEvent('keydown', { key, code: key === 'Escape' ? 'Escape' : key }));
}

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }
  return Wrapper;
}

/** 오버레이가 이미 떠 있는 상태(작업 시작이 지연 창보다 오래됨)를 만든다. */
function ageBusyPastOverlayDelay() {
  const busy = useLabelStore.getState().busy;
  if (busy === null) throw new Error('busy 가 없습니다');
  useLabelStore.setState({
    busy: { ...busy, startedAt: busy.startedAt - (BUSY_OVERLAY_DELAY_MS + 1000) },
  });
}

describe('busy ESC 취소 정책', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('지연창_안에서_ESC로_취소하면_취소_안내가_뜬다', () => {
    // given: AI 탐지를 막 시작했다(오버레이 지연 창 안 — 화면에는 아무 표시도 없다).
    renderHook(() => useLabelingShortcuts({}, { blocked: true }), { wrapper: makeWrapper() });
    act(() => {
      useLabelStore.getState().beginBusy('AI_DETECT', { srcSn: 1 });
    });

    // when: 습관적으로 ESC
    act(() => press('Escape'));

    // then: 취소되고, 취소됐다는 사실이 안내된다(무음 폐기 금지).
    expect(useLabelStore.getState().busy).toBeNull();
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toContain('AI 탐지');
    expect(toasts[0].message).toContain('취소');
    // 취소가 **요청을 끊는다**는 사실을 알린다 — 이제 화면이 실제로 중단 신호를 보내므로,
    // «결과만 안 쓴다» 로만 적으면 사용자는 서버가 계속 도는 줄 알고 취소를 주저한다.
    expect(toasts[0].message).toContain('요청을 중단');
    // 그렇다고 «서버가 즉시 멈춘다» 고 단정하지도 않는다 — 어느 지점에서 손을 떼는지는 서버 몫이다.
    expect(toasts[0].message).not.toContain('즉시 중단');
    // 모델명 미노출(R6) · 내부 식별자 미노출.
    expect(toasts[0].message).not.toMatch(/YOLO|SAM/i);
    expect(toasts[0].message).not.toMatch(/srcSn|\/v1\//i);
  });

  it('오버레이가_보이는_구간의_ESC_취소는_중복_안내하지_않는다', () => {
    // given: 오버레이가 이미 떠 있다(취소 사실이 화면에서 즉시 보인다).
    renderHook(() => useLabelingShortcuts({}, { blocked: true }), { wrapper: makeWrapper() });
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: 1 });
      ageBusyPastOverlayDelay();
    });

    // when
    act(() => press('Escape'));

    // then
    expect(useLabelStore.getState().busy).toBeNull();
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });

  it('같은_ESC에_여러_리스너가_반응해도_취소_안내는_1회다', () => {
    // M4 — 취소 판정이 한 곳(handleBusyEscape)이므로, 두 번째 호출은 이미 취소된 상태를 보고
    // 아무 것도 하지 않는다(멱등).
    act(() => {
      useLabelStore.getState().beginBusy('AI_SEGMENT', { srcSn: 1 });
    });

    let first = false;
    let second = false;
    act(() => {
      first = handleBusyEscape();
      second = handleBusyEscape();
    });

    expect(first).toBe(true);
    expect(second).toBe(false);
    expect(useUiStore.getState().toasts).toHaveLength(1);
  });

  it('진행_중_작업이_없으면_ESC_취소_헬퍼는_아무_것도_하지_않는다', () => {
    let cancelled = true;
    act(() => {
      cancelled = handleBusyEscape();
    });
    expect(cancelled).toBe(false);
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });
});
