// SCR-REVIEW-002 Phase 4 — ObjectAttributesPanel 테스트.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// UI-064 — 색상 판정의 단일 진실원은 라벨 마스터다. 패널이 마스터 목록을 구독하므로
// QueryClientProvider 없이 렌더할 수 있도록 훅을 고정 목록으로 대체한다.
vi.mock('@/features/label/hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: [] }),
}));

import { ObjectAttributesPanel } from '../components/ObjectAttributesPanel';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import type { LabelItem } from '../types';

const bboxLabel: LabelItem = {
  id: 1,
  lblTypeCd: 'BBOX',
  label: '사람',
  points: [
    [10, 20],
    [110, 220],
  ],
  autoLblYn: 'N',
  confScore: 0.876,
};

const polygonLabel: LabelItem = {
  id: 2,
  lblTypeCd: 'POLYGON',
  label: '차량',
  points: [
    [10, 10],
    [20, 10],
    [20, 20],
    [10, 20],
    [5, 15],
  ],
  autoLblYn: 'Y',
  confScore: 0.5,
};

const segmentLabel: LabelItem = {
  id: 3,
  lblTypeCd: 'SEGMENT',
  label: '트럭',
  points: [
    [0, 0],
    [10, 0],
    [10, 10],
  ],
  autoLblYn: 'N',
  confScore: null,
};

describe('ObjectAttributesPanel', () => {
  beforeEach(() => {
    useReviewSelectionStore.getState().clear();
  });

  it('ObjectAttributesPanel_미선택_안내문', () => {
    render(<ObjectAttributesPanel labels={[bboxLabel, polygonLabel]} />);
    expect(screen.getByTestId('object-attributes-empty')).toBeInTheDocument();
    expect(screen.getByText('객체를 선택하세요')).toBeInTheDocument();
    expect(screen.getByText('캔버스 또는 목록에서 객체를 클릭')).toBeInTheDocument();
  });

  it('ObjectAttributesPanel_BBOX_좌표_xywh_표시', () => {
    useReviewSelectionStore.getState().setSelected(1);
    render(<ObjectAttributesPanel labels={[bboxLabel]} />);

    expect(screen.getByTestId('attr-type')).toHaveTextContent('BBOX');
    // points [[10,20],[110,220]] → x=10 y=20 w=100 h=200
    const coords = screen.getByTestId('attr-bbox-coords');
    expect(coords).toHaveTextContent('x:10');
    expect(coords).toHaveTextContent('y:20');
    expect(coords).toHaveTextContent('w:100');
    expect(coords).toHaveTextContent('h:200');
  });

  it('ObjectAttributesPanel_POLYGON_점_개수_표시', () => {
    useReviewSelectionStore.getState().setSelected(2);
    render(<ObjectAttributesPanel labels={[polygonLabel]} />);

    expect(screen.getByTestId('attr-type')).toHaveTextContent('POLYGON');
    expect(screen.getByTestId('attr-point-count')).toHaveTextContent('5');
  });

  it('ObjectAttributesPanel_SEGMENT_도_점_개수_표시', () => {
    useReviewSelectionStore.getState().setSelected(3);
    render(<ObjectAttributesPanel labels={[segmentLabel]} />);

    expect(screen.getByTestId('attr-type')).toHaveTextContent('SEGMENT');
    expect(screen.getByTestId('attr-point-count')).toHaveTextContent('3');
  });

  it('ObjectAttributesPanel_confScore_퍼센트_변환', () => {
    useReviewSelectionStore.getState().setSelected(1);
    render(<ObjectAttributesPanel labels={[bboxLabel]} />);

    // 0.876 → 87.6%
    expect(screen.getByTestId('attr-conf-score')).toHaveTextContent('87.6%');
  });

  it('ObjectAttributesPanel_confScore_null_시_dash', () => {
    useReviewSelectionStore.getState().setSelected(3);
    render(<ObjectAttributesPanel labels={[segmentLabel]} />);

    expect(screen.getByTestId('attr-conf-score')).toHaveTextContent('—');
  });

  it('ObjectAttributesPanel_autoLblYn_한글_표시', () => {
    useReviewSelectionStore.getState().setSelected(2);
    render(<ObjectAttributesPanel labels={[bboxLabel, polygonLabel]} />);
    expect(screen.getByTestId('attr-auto-yn')).toHaveTextContent('자동 라벨');

    useReviewSelectionStore.getState().setSelected(1);
    render(<ObjectAttributesPanel labels={[bboxLabel, polygonLabel]} />);
    const allAuto = screen.getAllByTestId('attr-auto-yn');
    // 두 번째 렌더 결과 — '수동 라벨'
    expect(allAuto[allAuto.length - 1]).toHaveTextContent('수동 라벨');
  });

  it('ObjectAttributesPanel_카테고리_순번_표시', () => {
    useReviewSelectionStore.getState().setSelected(2);
    // 사람 1건 + 차량 2건 + 차량 1건 → id=2 (차량 첫 번째)
    const labels: LabelItem[] = [
      bboxLabel, // 사람 #1
      polygonLabel, // 차량 #1
      { ...polygonLabel, id: 4, label: '차량' }, // 차량 #2
    ];
    render(<ObjectAttributesPanel labels={labels} />);

    expect(screen.getByTestId('object-attributes-panel')).toHaveTextContent('차량 #1');
  });

  it('ObjectAttributesPanel_선택_라벨이_제거된_경우_안내문', () => {
    useReviewSelectionStore.getState().setSelected(999);
    render(<ObjectAttributesPanel labels={[bboxLabel]} />);
    // id=999 미존재 → 미선택 처리
    expect(screen.getByTestId('object-attributes-empty')).toBeInTheDocument();
  });
});
