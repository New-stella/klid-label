// SCR-REVIEW-002 Phase 4 — ObjectListPanel 테스트.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// UI-064 — 색상 판정의 단일 진실원은 라벨 마스터다. 패널이 마스터 목록을 구독하므로
// QueryClientProvider 없이 렌더할 수 있도록 훅을 고정 목록으로 대체한다.
vi.mock('@/features/label/hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({
    data: [
      {
        labelId: 11,
        name: '사람',
        color: '#123456',
        type: 'BBOX',
        sortNo: 1,
        useYn: 'Y',
        dtctTypeCd: null,
      },
      {
        labelId: 12,
        name: '차량',
        color: '#ABCDEF',
        type: 'BBOX',
        sortNo: 2,
        useYn: 'Y',
        dtctTypeCd: null,
      },
    ],
  }),
}));

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

// ─────────────────────────────────────────────────────────────────────────────
// UI-064 회귀 가드 — 색상 축 2개.
//   그룹 헤더 = 분류 축(라벨 마스터 색) / 각 행 막대 = 트랙 시각화 축(trackId 해시색).
//   두 축을 통일하면 안 되고, 그룹 헤더가 하드코딩 색상표로 되돌아가서도 안 된다.
// ─────────────────────────────────────────────────────────────────────────────
describe('ObjectListPanel — 색상 단일 진실원 (UI-064)', () => {
  beforeEach(() => {
    useReviewSelectionStore.getState().clear();
  });

  /** rgb(r, g, b) 문자열 → #RRGGBB (jsdom 이 style.backgroundColor 를 rgb 로 정규화한다). */
  function toHex(rgb: string): string {
    const m = rgb.match(/^rgb\((\d+),\s*(\d+),\s*(\d+)\)$/);
    if (!m) return rgb;
    return `#${[m[1], m[2], m[3]]
      .map((v) => Number(v).toString(16).padStart(2, '0'))
      .join('')}`.toUpperCase();
  }

  it('그룹_헤더_점은_라벨_마스터_색상이다_하드코딩표_부활_금지', () => {
    // given: 마스터(labelId=11, #123456)에 연결된 '사람' 라벨
    const labels = [
      bbox(1, '사람', { labelId: 11 } as Partial<LabelItem>),
    ];
    // when
    render(<ObjectListPanel labels={labels} />);
    // then: 구 하드코딩 색상표의 '사람'(#ef4444)이 아니라 마스터 색이 나온다
    const dot = screen
      .getByTestId('object-list-group-header-사람')
      .querySelector('span[aria-hidden="true"]:not(:first-child)') as HTMLElement;
    expect(toHex(dot.style.backgroundColor)).toBe('#123456');
  });

  it('행_막대는_트랙_해시색이라_그룹_헤더_색과_다르다_두_축_통일_금지', () => {
    // given: 마스터에 연결됐고 trackId 도 있는 라벨
    const labels = [
      bbox(1, '사람', { labelId: 11, trackId: '7' } as Partial<LabelItem>),
    ];
    // when
    render(<ObjectListPanel labels={labels} />);
    // then
    const bar = screen.getByTestId('review-label-color-bar') as HTMLElement;
    expect(bar.style.backgroundColor).not.toBe('');
    expect(toHex(bar.style.backgroundColor)).not.toBe('#123456');
  });

  it('마스터에_없는_분류도_회색으로_죽지_않는다', () => {
    // given: 마스터 미등록 분류(labelId 없음)
    const labels = [bbox(1, 'INTRUSION')];
    // when
    render(<ObjectListPanel labels={labels} />);
    // then: 구 하드코딩 표의 알려진 결함(미등록 = 회색)이 재현되지 않는다
    const dot = screen
      .getByTestId('object-list-group-header-INTRUSION')
      .querySelector('span[aria-hidden="true"]:not(:first-child)') as HTMLElement;
    expect(toHex(dot.style.backgroundColor)).not.toBe('#94A3B8');
  });

  it('그룹_대표는_배열_순서가_바뀌어도_같은_색을_낸다', () => {
    // given: 같은 라벨명·다른 labelId 가 한 그룹에 섞인 경우(라벨명에 유일성 제약이 없다)
    const a = bbox(1, '사람', { labelId: 12 } as Partial<LabelItem>);
    const b = bbox(2, '사람', { labelId: 11 } as Partial<LabelItem>);
    const dotOf = () =>
      toHex(
        (
          screen
            .getByTestId('object-list-group-header-사람')
            .querySelector('span[aria-hidden="true"]:not(:first-child)') as HTMLElement
        ).style.backgroundColor,
      );
    // when
    const { unmount } = render(<ObjectListPanel labels={[a, b]} />);
    const first = dotOf();
    unmount();
    render(<ObjectListPanel labels={[b, a]} />);
    // then: 최소 labelId(11) 가 대표라 순서와 무관하게 같은 색이다
    expect(dotOf()).toBe(first);
    expect(first).toBe('#123456');
  });

  // ───────────────────────────────────────────────────────────────────────
  // 키포인트(SKELETON) 형태 뱃지 — `LabelType` 유니온에 값이 없던 동안 이 뱃지는 표에서
  // 빠져 무채색 폴백으로만 표시됐다. 표(`TYPE_BADGE_CLASS`)는 **형태 축**이며 라벨 색상
  // (마스터 단일 진실원)과 다른 축이다.
  // ───────────────────────────────────────────────────────────────────────
  it('키포인트_라벨도_형태_뱃지를_받는다_무채색_폴백_아님', () => {
    // given: BE 가 실제로 내려보내는 SKELETON 라벨
    const skeleton: LabelItem = {
      id: 21,
      lblTypeCd: 'SKELETON',
      label: '사람',
      points: Array.from({ length: 17 }, (_, i) => [i, i, 2]),
      autoLblYn: 'N',
      confScore: null,
    };
    // when
    render(<ObjectListPanel labels={[skeleton]} />);
    // then: 뱃지 문구는 다른 형태와 같은 규칙(소문자 코드)이고, 폴백 회색 클래스가 아니다
    const badge = screen.getByTestId('object-list-type-badge');
    expect(badge).toHaveTextContent('skeleton');
    expect(badge.className).not.toContain('bg-gray-100');
  });
});
