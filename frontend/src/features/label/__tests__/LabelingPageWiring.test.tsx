// Phase 2 화면 배선 — LabelingPage 조립 검증.
// ① ImageAdjustPanel 마운트 · ② FrameFilmstrip 상태색(issueThreads 미해소 INQUIRY) · ⑤ T 표시/숨김 배선.
// v2 반려는 영상 단위(REJECTION.srcSn=null)라 프레임색 미대상 → 프레임 상태색은 확인요청·저장·현재만.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, screen, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      ...rest
    }: {
      children?: ReactNode;
      [key: string]: unknown;
    }) => createElement('div', { 'data-konva': name, ...rest }, children);
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
    Transformer: passthrough('Transformer'),
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

// 현재 프레임 300(idx0) + 형제 301(idx1, 미해소 INQUIRY 이슈) + 형제 302(idx2, 라벨 저장됨=SAVED).
// 300 도 hasLabel=true 지만 현재 프레임이므로 CURRENT 가 우선(SAVED 로 덮이지 않음).
function labelsPayload() {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: 300,
      videoId: 7,
      siblings: [
        { srcSn: 300, frameNo: 0, hasLabel: true },
        { srcSn: 301, frameNo: 1, hasLabel: false },
        { srcSn: 302, frameNo: 2, hasLabel: true },
      ],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

const TEST_TOKEN = ['t', 'o', 'k'].join('');

function setup() {
  renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/300'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage Phase 2 화면 배선', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    useLabelStore.getState().reset();
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload());
    mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: null,
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/description/).reply(200, {
      success: true,
      data: { srcSn: 300, description: '' },
      message: null,
      errorCode: null,
    });
    mock.onGet('/manage/labels').reply(200, { success: true, data: [], message: null, errorCode: null });
    // 301 프레임에 미해소 INQUIRY(확인요청) 이슈 → 빨강 테두리 기대.
    mock.onGet('/videos/7/issues').reply(200, {
      success: true,
      data: [
        {
          issueSn: 1,
          issueTypeCd: 'INQUIRY',
          issueSttsCd: 'OPEN',
          srcSn: 301,
          reason: '확인 요청',
          reportedUserNo: null,
          regDt: '2026-07-14T00:00:00',
          comments: [],
        },
      ],
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  it('①_ImageAdjustPanel_우측패널에_마운트', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => {
      expect(screen.getByRole('region', { name: '이미지 조절' })).toBeInTheDocument();
    });
  });

  it('②_형제프레임301_미해소INQUIRY_빨강_테두리_상태전달', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // FrameFilmstrip 의 프레임 301 썸네일(index 1)이 INQUIRY 상태여야 한다.
    await waitFor(() => {
      const thumb = document.querySelector('[data-frame-index="1"]');
      expect(thumb).not.toBeNull();
      expect(thumb?.getAttribute('data-frame-status')).toBe('INQUIRY');
    });
    // 현재 프레임(index 0)은 CURRENT.
    const cur = document.querySelector('[data-frame-index="0"]');
    expect(cur?.getAttribute('data-frame-status')).toBe('CURRENT');
  });

  it('③_저장된_형제프레임302_연두색_SAVED_표시', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // 302(index 2)는 라벨 저장됨 + 이슈 없음 + 현재 아님 → SAVED(연두).
    await waitFor(() => {
      const thumb = document.querySelector('[data-frame-index="2"]');
      expect(thumb).not.toBeNull();
      expect(thumb?.getAttribute('data-frame-status')).toBe('SAVED');
    });
  });

  it('④_현재프레임은_라벨있어도_CURRENT_우선', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // 300(index 0)은 hasLabel=true 지만 현재 프레임이므로 CURRENT 우선(SAVED 로 덮이지 않음).
    await waitFor(() => {
      const cur = document.querySelector('[data-frame-index="0"]');
      expect(cur).not.toBeNull();
      expect(cur?.getAttribute('data-frame-status')).toBe('CURRENT');
    });
    // 상태색 실동작 검증 — CURRENT(0)/INQUIRY(1)/SAVED(2) 세 상태가 동시에 렌더된다.
    const statuses = ['0', '1', '2'].map(
      (i) => document.querySelector(`[data-frame-index="${i}"]`)?.getAttribute('data-frame-status'),
    );
    expect(statuses).toEqual(['CURRENT', 'INQUIRY', 'SAVED']);
  });

  it('⑤_T키_선택라벨_가시성_토글_배선', async () => {
    setup();
    // data 로드 완료 대기 — 형제 프레임 strip 이 그려진 뒤라야 setLabels([]) 효과가
    // 이미 실행돼 이후 store 조작이 덮어써지지 않는다.
    await waitFor(() => {
      expect(document.querySelector('[data-frame-index="1"]')).not.toBeNull();
    });

    // 라벨을 스토어에 추가하고 선택 (data 미변경이라 setLabels 효과가 덮어쓰지 않음).
    act(() => {
      useLabelStore.getState().addLabel({
        id: 'lbl1',
        frameNo: 0,
        classId: 1,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
      useLabelStore.getState().selectLabel('lbl1');
    });

    // T 키 → 선택 라벨 숨김.
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 't' }));
    });
    expect(useLabelStore.getState().hiddenLabelIds.has('lbl1')).toBe(true);

    // 다시 T → 표시.
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 't' }));
    });
    expect(useLabelStore.getState().hiddenLabelIds.has('lbl1')).toBe(false);
  });
});
