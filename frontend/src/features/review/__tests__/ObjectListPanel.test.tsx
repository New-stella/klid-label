// SCR-REVIEW-002 Phase 4 — ObjectListPanel 테스트.

import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ObjectListPanel } from '../components/ObjectListPanel';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import type { LabelItem } from '../types';

function bbox(id: number, label: string, overrides: Partial<LabelItem> = {}): LabelItem {
  return {
    id,
    lblTypeCd: 'BBOX',
    label,
    points: [
      [0, 0],
      [10, 10],
    ],
    autoLblYn: 'N',
    confScore: null,
    ...overrides,
  };
}

function polygon(id: number, label: string, overrides: Partial<LabelItem> = {}): LabelItem {
  return {
    id,
    lblTypeCd: 'POLYGON',
    label,
    points: [
      [0, 0],
      [10, 0],
      [10, 10],
    ],
    autoLblYn: 'N',
    confScore: null,
    ...overrides,
  };
}

describe('ObjectListPanel', () => {
  beforeEach(() => {
    useReviewSelectionStore.getState().clear();
  });

  it('ObjectListPanel_라벨_없을_때_안내문', () => {
    render(<ObjectListPanel labels={[]} />);
    expect(screen.getByTestId('object-list-empty')).toBeInTheDocument();
  });

  it('ObjectListPanel_카테고리별_그룹화_정확', () => {
    const labels: LabelItem[] = [
      bbox(1, '사람'),
      bbox(2, '차량'),
      polygon(3, '차량'),
      bbox(4, '오토바이'),
      polygon(5, '트럭'),
      bbox(6, '차량'),
      polygon(7, '오토바이'),
    ];
    // 4개 카테고리 (사람/차량/트럭/오토바이), 7개 라벨
    render(<ObjectListPanel labels={labels} />);

    // 그룹 헤더 4개
    expect(screen.getByTestId('object-list-group-사람')).toBeInTheDocument();
    expect(screen.getByTestId('object-list-group-차량')).toBeInTheDocument();
    expect(screen.getByTestId('object-list-group-트럭')).toBeInTheDocument();
    expect(screen.getByTestId('object-list-group-오토바이')).toBeInTheDocument();

    // 차량 카테고리 카운트 (3)
    const vehicleHeader = screen.getByTestId('object-list-group-header-차량');
    expect(within(vehicleHeader).getByText('(3)')).toBeInTheDocument();
  });

  it('ObjectListPanel_카테고리내_순번_1base', () => {
    const labels: LabelItem[] = [
      bbox(10, '차량'),
      bbox(11, '차량'),
      bbox(12, '차량'),
    ];
    render(<ObjectListPanel labels={labels} />);

    const row1 = screen.getByTestId('object-list-row-10');
    const row2 = screen.getByTestId('object-list-row-11');
    const row3 = screen.getByTestId('object-list-row-12');
    expect(row1).toHaveTextContent('차량 #1');
    expect(row2).toHaveTextContent('차량 #2');
    expect(row3).toHaveTextContent('차량 #3');
  });

  it('ObjectListPanel_row_클릭_시_setSelected_호출', async () => {
    const user = userEvent.setup();
    const labels: LabelItem[] = [bbox(1, '사람'), bbox(2, '차량')];
    render(<ObjectListPanel labels={labels} />);

    await user.click(screen.getByTestId('object-list-row-2'));
    expect(useReviewSelectionStore.getState().selectedLabelId).toBe(2);
  });

  it('ObjectListPanel_keyboard_enter_시_setSelected', async () => {
    const user = userEvent.setup();
    const labels: LabelItem[] = [bbox(1, '사람')];
    render(<ObjectListPanel labels={labels} />);

    const row = screen.getByTestId('object-list-row-1');
    row.focus();
    await user.keyboard('{Enter}');
    expect(useReviewSelectionStore.getState().selectedLabelId).toBe(1);
  });

  it('ObjectListPanel_그룹_헤더_클릭_시_collapse_토글', async () => {
    const user = userEvent.setup();
    const labels: LabelItem[] = [bbox(1, '사람'), bbox(2, '사람')];
    render(<ObjectListPanel labels={labels} />);

    // 기본 — 행 보임
    expect(screen.getByTestId('object-list-row-1')).toBeInTheDocument();

    const header = screen.getByTestId('object-list-group-header-사람');
    await user.click(header);
    // collapse 후 — 행 사라짐
    expect(screen.queryByTestId('object-list-row-1')).toBeNull();
    expect(header).toHaveAttribute('aria-expanded', 'false');

    await user.click(header);
    expect(screen.getByTestId('object-list-row-1')).toBeInTheDocument();
    expect(header).toHaveAttribute('aria-expanded', 'true');
  });

  it('ObjectListPanel_타입_배지_표시', () => {
    const labels: LabelItem[] = [bbox(1, '사람'), polygon(2, '차량')];
    render(<ObjectListPanel labels={labels} />);

    const row1 = screen.getByTestId('object-list-row-1');
    const row2 = screen.getByTestId('object-list-row-2');
    expect(within(row1).getByTestId('object-list-type-badge')).toHaveTextContent('bbox');
    expect(within(row2).getByTestId('object-list-type-badge')).toHaveTextContent('polygon');
  });

  it('ObjectListPanel_선택된_row_aria_selected_true', async () => {
    const user = userEvent.setup();
    const labels: LabelItem[] = [bbox(1, '사람'), bbox(2, '차량')];
    render(<ObjectListPanel labels={labels} />);

    await user.click(screen.getByTestId('object-list-row-2'));
    expect(screen.getByTestId('object-list-row-2')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('object-list-row-1')).toHaveAttribute('aria-selected', 'false');
  });

  it('ObjectListPanel_외부_setSelected_시_해당_카테고리_자동_펼침', async () => {
    const user = userEvent.setup();
    const labels: LabelItem[] = [bbox(1, '사람'), bbox(2, '차량')];
    const { rerender } = render(<ObjectListPanel labels={labels} />);

    // 사람 카테고리 collapse
    await user.click(screen.getByTestId('object-list-group-header-사람'));
    expect(screen.queryByTestId('object-list-row-1')).toBeNull();

    // 캔버스에서 사람 라벨 선택 → 외부에서 store 변경
    useReviewSelectionStore.getState().setSelected(1);
    rerender(<ObjectListPanel labels={labels} />);

    // 자동 펼침
    expect(screen.getByTestId('object-list-row-1')).toBeInTheDocument();
  });
});
