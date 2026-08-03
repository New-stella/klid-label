import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { type ReactNode } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelingShortcuts } from '../hooks/useLabelingShortcuts';
import { ToolType } from '../types';

function press(key: string, opts: KeyboardEventInit = {}) {
  const ev = new KeyboardEvent('keydown', { key, ...opts });
  window.dispatchEvent(ev);
}

// useLabelingShortcuts 가 내부적으로 useLabelMasters(useQuery) 를 호출하므로 QueryClientProvider 가 필요.
function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe('useLabelingShortcuts (Rev.1.1 재배치)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ── 도구 단축키 (유지) ──────────────────────────────────────────
  it('단축키_B_누르면_activeTool_BBOX', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('b'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
  });

  it('단축키_P_누르면_POLYGON', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('p'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.POLYGON);
  });

  it('키포인트_K_유지', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('k'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.KEYPOINT);
  });

  it('SAM분할_G_유지', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('g'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SAM_SEGMENT);
  });

  it('한글IME_물리키_KeyK_누르면_KEYPOINT_e_key_가_ㅏ_여도', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('ㅏ', { code: 'KeyK' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.KEYPOINT);
  });

  it('한글IME_물리키_KeyB_누르면_BBOX_e_key_가_ㅠ_여도', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('ㅠ', { code: 'KeyB' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
  });

  // ── SAM 추적 재배치: T → Shift+T ────────────────────────────────
  it('SAM추적_Shift_T로_재배치_T와_충돌없음', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    // 평문 T 는 더 이상 TRACK 을 활성화하지 않는다(도구 미변경).
    act(() => press('t'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
    // Shift+T 가 TRACK.
    act(() => press('T', { shiftKey: true }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.TRACK);
  });

  it('한글IME_물리키_Shift_KeyT_누르면_TRACK', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('ㅅ', { code: 'KeyT', shiftKey: true }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.TRACK);
  });

  it('평문_T_는_라벨표시숨김_핸들러_호출', () => {
    const onToggleVisibility = vi.fn();
    renderHook(() => useLabelingShortcuts({ onToggleVisibility }), { wrapper: makeWrapper() });
    act(() => press('t'));
    expect(onToggleVisibility).toHaveBeenCalledTimes(1);
  });

  // ── SELECT 도구 재배치: S → Esc (WASD 충돌 회피) ────────────────
  it('Esc_누르면_SELECT_도구', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    useLabelStore.getState().setActiveTool(ToolType.BBOX);
    act(() => press('Escape'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
  });

  // ── WASD 프레임 이동 (신규) ─────────────────────────────────────
  it('WASD_프레임_이동_동작', () => {
    const onFirstFrame = vi.fn();
    const onPrevFrame = vi.fn();
    const onLastFrame = vi.fn();
    const onNextFrame = vi.fn();
    renderHook(
      () => useLabelingShortcuts({ onFirstFrame, onPrevFrame, onLastFrame, onNextFrame }),
      { wrapper: makeWrapper() },
    );
    act(() => press('w'));
    act(() => press('a'));
    act(() => press('s'));
    act(() => press('d'));
    expect(onFirstFrame).toHaveBeenCalledTimes(1);
    expect(onPrevFrame).toHaveBeenCalledTimes(1);
    expect(onLastFrame).toHaveBeenCalledTimes(1);
    expect(onNextFrame).toHaveBeenCalledTimes(1);
  });

  it('한글IME_물리키_KeyD_프레임_다음이동', () => {
    const onNextFrame = vi.fn();
    renderHook(() => useLabelingShortcuts({ onNextFrame }), { wrapper: makeWrapper() });
    act(() => press('ㅇ', { code: 'KeyD' }));
    expect(onNextFrame).toHaveBeenCalledTimes(1);
  });

  it('화살표_프레임_이동_호환유지', () => {
    const onPrev = vi.fn();
    const onNext = vi.fn();
    renderHook(() => useLabelingShortcuts({ onPrevFrame: onPrev, onNextFrame: onNext }), {
      wrapper: makeWrapper(),
    });
    act(() => press('ArrowLeft'));
    act(() => press('ArrowRight'));
    expect(onPrev).toHaveBeenCalledTimes(1);
    expect(onNext).toHaveBeenCalledTimes(1);
  });

  // ── 폴리곤 F/Q, 삭제 R/Del ──────────────────────────────────────
  it('폴리곤_F_점추가_Q_자동완료_핸들러', () => {
    const onPolygonAddPoint = vi.fn();
    const onPolygonComplete = vi.fn();
    renderHook(() => useLabelingShortcuts({ onPolygonAddPoint, onPolygonComplete }), {
      wrapper: makeWrapper(),
    });
    act(() => press('f'));
    act(() => press('q'));
    expect(onPolygonAddPoint).toHaveBeenCalledTimes(1);
    expect(onPolygonComplete).toHaveBeenCalledTimes(1);
  });

  it('R_또는_Del_선택라벨_삭제', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    const store = useLabelStore.getState();
    act(() => {
      store.addLabel({
        id: 'x',
        frameNo: 1,
        classId: 1,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
      useLabelStore.getState().selectLabel('x');
    });
    act(() => press('r'));
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });

  it('삭제_단축키는_잠금_선택라벨에_적용되지_않는다', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => {
      useLabelStore.getState().addLabel({
        id: 'locked',
        frameNo: 1,
        classId: 1,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
      useLabelStore.getState().selectLabel('locked');
      useLabelStore.getState().toggleLabelLock('locked');
    });
    act(() => press('Delete'));
    // 잠금 라벨은 store 가드로 삭제 no-op — 라벨 유지.
    expect(useLabelStore.getState().labels).toHaveLength(1);
    act(() => press('r'));
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('Del_누르면_선택된_라벨_삭제', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    const store = useLabelStore.getState();
    act(() => {
      store.addLabel({
        id: 'y',
        frameNo: 1,
        classId: 1,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
      useLabelStore.getState().selectLabel('y');
    });
    act(() => press('Delete'));
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });

  // ── 저장/undo (유지) ────────────────────────────────────────────
  it('단축키_Ctrl+S_저장_트리거', () => {
    const onSave = vi.fn();
    renderHook(() => useLabelingShortcuts({ onSave }), { wrapper: makeWrapper() });
    act(() => press('s', { ctrlKey: true }));
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('Ctrl+S_는_프레임_끝이동과_충돌없이_저장만', () => {
    const onSave = vi.fn();
    const onLastFrame = vi.fn();
    renderHook(() => useLabelingShortcuts({ onSave, onLastFrame }), { wrapper: makeWrapper() });
    act(() => press('s', { ctrlKey: true }));
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onLastFrame).not.toHaveBeenCalled();
  });

  // ── 줌 단축키 (US 키보드 회귀) ──────────────────────────────────
  it('Shift_플러스_확대_줌_증가', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    const before = useLabelStore.getState().zoom;
    act(() => press('+', { shiftKey: true }));
    expect(useLabelStore.getState().zoom).toBeGreaterThan(before);
  });

  it('Shift_언더스코어_축소_줌_감소', () => {
    // fit(=1) 이 최소 배율 바닥이므로 줌인된 상태(2)에서 축소가 감소하는지 검증한다.
    useLabelStore.setState({ zoom: 2 });
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    const before = useLabelStore.getState().zoom;
    act(() => press('_', { shiftKey: true }));
    expect(useLabelStore.getState().zoom).toBeLessThan(before);
  });

  it('등호_하이픈_무수식_줌_동작', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    act(() => press('='));
    expect(useLabelStore.getState().zoom).toBeGreaterThan(1);
    act(() => press('-'));
    act(() => press('-'));
    expect(useLabelStore.getState().zoom).toBeLessThan(1.2);
  });

  it('Ctrl+Z_undo_트리거', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    const store = useLabelStore.getState();
    act(() => {
      store.addLabel({
        id: 'a',
        frameNo: 1,
        classId: 1,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
    });
    expect(useLabelStore.getState().labels).toHaveLength(1);
    act(() => press('z', { ctrlKey: true }));
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });

  it('input_포커스_상태에서는_단축키_무시', () => {
    renderHook(() => useLabelingShortcuts(), { wrapper: makeWrapper() });
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();

    const ev = new KeyboardEvent('keydown', { key: 'b', bubbles: true });
    Object.defineProperty(ev, 'target', { value: input });
    act(() => {
      window.dispatchEvent(ev);
    });

    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
    document.body.removeChild(input);
  });

  // ── 포털 SAM2/키포인트 허용 (Phase 9, ADR-013 override) ─────────────
  // ADR-013 — 포털은 SAM2·오토라벨 미제공. 툴바 버튼만 숨기고 단축키를 열어두면 게이팅이 그대로
  // 우회된다(키로 도구 활성화). 서버의 포털 전용 SAM2 엔드포인트도 제거됐으므로(BE
  // PortalSam2RemovedTest) 도구가 켜져도 404 만 만난다 — 애초에 켜지지 않게 한다.
  it('포털_사용자는_스켈레톤_도구를_사용할_수_없다', () => {
    renderHook(() => useLabelingShortcuts({}, { portalMode: true }), { wrapper: makeWrapper() });
    act(() => press('k'));
    expect(useLabelStore.getState().activeTool).not.toBe(ToolType.KEYPOINT);
  });

  it('포털_사용자는_AI분할과_AI추적_도구를_사용할_수_없다', () => {
    renderHook(() => useLabelingShortcuts({}, { portalMode: true }), { wrapper: makeWrapper() });
    act(() => press('g'));
    expect(useLabelStore.getState().activeTool).not.toBe(ToolType.SAM_SEGMENT);
    act(() => press('T', { shiftKey: true }));
    expect(useLabelStore.getState().activeTool).not.toBe(ToolType.TRACK);
  });

  it('포털모드_B_P_기본도구는_정상동작', () => {
    renderHook(() => useLabelingShortcuts({}, { portalMode: true }), { wrapper: makeWrapper() });
    act(() => press('b'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
    act(() => press('p'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.POLYGON);
  });

  it('비포털_K_G_ShiftT_단축키_정상동작_회귀없음', () => {
    renderHook(() => useLabelingShortcuts({}, { portalMode: false }), { wrapper: makeWrapper() });
    act(() => press('k'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.KEYPOINT);
    act(() => press('g'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SAM_SEGMENT);
    act(() => press('T', { shiftKey: true }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.TRACK);
  });
});
