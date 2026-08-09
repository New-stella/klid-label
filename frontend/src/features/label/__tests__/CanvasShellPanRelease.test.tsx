// R2 보강(MEDIUM-1) — 스페이스 팬 홀드 중 window blur(Alt+Tab)/탭 숨김 시 spaceDown 고착으로
// 좌클릭 드로잉/선택이 먹통 되는 버그 방지. blur/visibilitychange 에서 팬 상태가 해제되는지 검증.
// (konva Stage 는 passthrough 로 목킹 — 상태→커서/레이어 listening 반영만 확인)

import { act } from 'react';
import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { CanvasShell } from '../canvas/CanvasShell';
import type { FrameSummary } from '../types';

const frame: FrameSummary = { frameNo: 0, srcSn: 1, thumbnailUrl: '', imageUrl: '' };

describe('CanvasShell — 스페이스 팬 홀드 blur/탭숨김 시 해제(MEDIUM-1)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('스페이스_다운시_grab커서_window_blur시_해제되어_커서복귀', () => {
    renderWithProviders(<CanvasShell frame={frame} width={800} height={600} labels={[]} />);
    const shell = screen.getByTestId('canvas-shell');
    // 초기: 팬 커서 없음
    expect(shell.style.cursor).toBe('');

    // 스페이스 홀드 → grab 커서(팬 준비, 드로잉/선택 억제)
    fireEvent.keyDown(window, { code: 'Space', key: ' ' });
    expect(shell.style.cursor).toBe('grab');

    // window blur(Alt+Tab) → 고착 방지: 상태 해제 → 커서 복귀
    act(() => {
      window.dispatchEvent(new Event('blur'));
    });
    expect(shell.style.cursor).toBe('');
  });

  it('스페이스_다운후_탭숨김(visibilitychange_hidden)시_해제', () => {
    renderWithProviders(<CanvasShell frame={frame} width={800} height={600} labels={[]} />);
    const shell = screen.getByTestId('canvas-shell');

    fireEvent.keyDown(window, { code: 'Space', key: ' ' });
    expect(shell.style.cursor).toBe('grab');

    const spy = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(shell.style.cursor).toBe('');
    spy.mockRestore();
  });
});
