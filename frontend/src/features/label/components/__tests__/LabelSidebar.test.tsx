// Phase 7 — LabelSidebar 컴포넌트 테스트.
//
// 좌측 DarkToolbar 우측에 위치하는 라벨 마스터 사이드바.
// - 13개 라벨 색상 박스 + 한글 이름 + sortNo<=9 단축키 번호 표시
// - 클릭 → setActiveLabelId
// - 로딩/에러/빈 상태

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { LabelSidebar } from '../LabelSidebar';

const samplePayload = {
  success: true,
  data: [
    { labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y' },
    { labelId: 2, name: '차량', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y' },
    { labelId: 3, name: '자전거', color: '#10B981', type: 'BBOX', sortNo: 3, useYn: 'Y' },
  ],
  message: null,
  errorCode: null,
};

describe('LabelSidebar', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('라벨_목록_렌더링_+_색상_박스_+_단축키_번호', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    renderWithProviders(<LabelSidebar />);

    await waitFor(() => {
      expect(screen.getByText('사람')).toBeInTheDocument();
    });
    expect(screen.getByText('차량')).toBeInTheDocument();
    expect(screen.getByText('자전거')).toBeInTheDocument();

    // 단축키 번호 1, 2, 3 (sortNo)
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();

    // 색상 박스 (data-testid)
    const colorBoxes = document.querySelectorAll('[data-testid="label-sidebar-color"]');
    expect(colorBoxes.length).toBe(3);
    expect((colorBoxes[0] as HTMLElement).style.backgroundColor).not.toBe('');
  });

  it('클릭_시_setActiveLabelId_호출', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    renderWithProviders(<LabelSidebar />);

    await waitFor(() => {
      expect(screen.getByText('차량')).toBeInTheDocument();
    });

    const btn = screen.getByRole('button', { name: /차량/ });
    fireEvent.click(btn);

    expect(useLabelStore.getState().activeLabelId).toBe(2);
  });

  it('로딩_상태_표시', () => {
    mock.onGet('/manage/labels').reply(() => new Promise(() => {})); // never resolves
    renderWithProviders(<LabelSidebar />);
    expect(screen.getByText(/로딩/)).toBeInTheDocument();
  });

  it('에러_상태_표시', async () => {
    mock.onGet('/manage/labels').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL',
    });
    renderWithProviders(<LabelSidebar />);

    await waitFor(() => {
      expect(screen.getByText(/라벨 조회 실패|불러올 수 없|오류/)).toBeInTheDocument();
    });
  });

  it('키포인트_배치중_가이드가_좌측패널_내부에_표시', async () => {
    // given — 라벨 목록 + KEYPOINT 배치 진행(placingIndex=2)
    mock.onGet('/manage/labels').reply(200, samplePayload);
    renderWithProviders(<LabelSidebar keypointPlacingIndex={2} />);

    await waitFor(() => {
      expect(screen.getByText('사람')).toBeInTheDocument();
    });

    // then — 라벨 네비게이션(패널) 내부에 키포인트 가이드가 렌더된다.
    const panel = screen.getByRole('navigation', { name: '라벨 마스터' });
    const guide = screen.getByRole('group', { name: '스켈레톤 배치 가이드' });
    expect(panel).toContainElement(guide);
    expect(screen.getByTestId('kpt-guide-caption')).toHaveTextContent('3/17');
  });

  it('키포인트_미배치_null이면_가이드_미표시', async () => {
    // given — placingIndex 기본(null)
    mock.onGet('/manage/labels').reply(200, samplePayload);
    renderWithProviders(<LabelSidebar />);

    await waitFor(() => {
      expect(screen.getByText('사람')).toBeInTheDocument();
    });

    // then — 가이드 미표시
    expect(screen.queryByRole('group', { name: '스켈레톤 배치 가이드' })).toBeNull();
  });

  it('현재_activeLabelId_라벨_시각적_강조', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);
    useLabelStore.getState().setActiveLabelId(2);
    renderWithProviders(<LabelSidebar />);

    await waitFor(() => {
      expect(screen.getByText('차량')).toBeInTheDocument();
    });

    // aria-pressed 또는 data-active="true" 로 강조 표현
    const activeBtn = screen.getByRole('button', { name: /차량/ });
    expect(activeBtn.getAttribute('aria-pressed')).toBe('true');
  });
});
