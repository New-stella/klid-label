// Phase 3 R6 — 객체 행 개별 표시/숨김(eye) + 잠금(lock) 토글.
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// ObjectClassTree 는 그룹 헤더 색상 판정을 위해 라벨 마스터를 구독한다(2026-08-06).
// 모킹하지 않으면 jsdom 이 실제 XHR 을 쏴 AggregateError 로그가 쌓여 진짜 실패가 묻힌다.
vi.mock('../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: [], isLoading: false, isError: false }),
}));

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectClassTree } from '../components/ObjectClassTree';
import type { Label } from '../types';

function makeLabel(over: Partial<Label> & Pick<Label, 'id'>): Label {
  return {
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'AUTO_YOLO',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
    ...over,
  };
}

beforeEach(() => {
  useLabelStore.getState().reset();
});

describe('ObjectClassTree — 개별 표시/숨김·잠금(R6)', () => {
  it('객체행_표시숨김_토글시_hiddenLabelIds가_갱신된다', async () => {
    const labels = [makeLabel({ id: 'a' })];
    useLabelStore.getState().setLabels(labels);
    renderWithProviders(<ObjectClassTree labels={labels} />);
    const user = userEvent.setup();

    // 초기: 표시 상태 → "숨김" 버튼 노출.
    const hideBtn = screen.getByLabelText(/#1 숨김$/);
    expect(hideBtn).toHaveAttribute('aria-pressed', 'false');
    await user.click(hideBtn);

    expect(useLabelStore.getState().hiddenLabelIds.has('a')).toBe(true);
    // 토글 후 라벨은 "표시" 버튼으로 전환(aria-pressed=true).
    const showBtn = screen.getByLabelText(/#1 표시$/);
    expect(showBtn).toHaveAttribute('aria-pressed', 'true');
  });

  it('객체행_잠금_토글시_lockedLabelIds가_갱신된다', async () => {
    const labels = [makeLabel({ id: 'a' })];
    useLabelStore.getState().setLabels(labels);
    renderWithProviders(<ObjectClassTree labels={labels} />);
    const user = userEvent.setup();

    const lockBtn = screen.getByLabelText(/#1 잠금$/);
    expect(lockBtn).toHaveAttribute('aria-pressed', 'false');
    await user.click(lockBtn);

    expect(useLabelStore.getState().lockedLabelIds.has('a')).toBe(true);
    const unlockBtn = screen.getByLabelText(/#1 잠금 해제$/);
    expect(unlockBtn).toHaveAttribute('aria-pressed', 'true');
  });

  it('잠금_객체는_삭제_버튼_비활성_연필_미노출', async () => {
    const labels = [makeLabel({ id: 'a', trackId: '5' })];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().toggleLabelLock('a');
    renderWithProviders(<ObjectClassTree labels={labels} />);

    // 삭제 버튼은 존재하나 disabled.
    const del = screen.getByLabelText('객체 삭제') as HTMLButtonElement;
    expect(del.disabled).toBe(true);
    // 잠금 시 연필(트랙 ID 변경) 진입 차단.
    expect(screen.queryByLabelText(/트랙 ID 변경$/)).toBeNull();

    // disabled 클릭은 removeLabel 미발생(라벨 유지).
    const user = userEvent.setup();
    await user.click(del);
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('ObjectClassTree에서_잠금객체_행_클릭시_선택되지_않는다', async () => {
    const labels = [makeLabel({ id: 'a', trackId: '5' })];
    useLabelStore.getState().setLabels(labels);
    useLabelStore.getState().toggleLabelLock('a');
    renderWithProviders(<ObjectClassTree labels={labels} />);
    const user = userEvent.setup();

    // 선택 버튼 클릭 → store selectLabel 가드로 no-op(캔버스와 일관).
    await user.click(screen.getByLabelText(/#1 선택$/));
    expect(useLabelStore.getState().selectedLabelId).toBeNull();
  });

  it('비잠금객체_행_클릭시_정상_선택된다_회귀없음', async () => {
    const labels = [makeLabel({ id: 'a', trackId: '5' })];
    useLabelStore.getState().setLabels(labels);
    renderWithProviders(<ObjectClassTree labels={labels} />);
    const user = userEvent.setup();

    await user.click(screen.getByLabelText(/#1 선택$/));
    expect(useLabelStore.getState().selectedLabelId).toBe('a');
  });

  it('비잠금_객체는_삭제_연필_정상노출_회귀없음', () => {
    const labels = [makeLabel({ id: 'a', trackId: '5' })];
    useLabelStore.getState().setLabels(labels);
    renderWithProviders(<ObjectClassTree labels={labels} />);

    const del = screen.getByLabelText('객체 삭제') as HTMLButtonElement;
    expect(del.disabled).toBe(false);
    expect(screen.getByLabelText(/트랙 ID 변경$/)).toBeInTheDocument();
  });
});
