// DEV_FIX Phase 1 — AI 추적(useSam2Track / Sam2TrackTool) ↔ 배타 실행(busy) 통합 검증.
//
// 기존 커버리지는 store 를 직접 호출하는 단위 테스트뿐이라 "추적 훅이 실제로 배타 축을 지키는가",
// "취소된 추적의 응답·실패·진행률이 화면에 새어나오지 않는가"가 검증되지 않았다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';

const START_SRC_SN = 1;
const NEXT_SRC_SNS = [2, 3];
const TRIANGLE: number[][] = [
  [0, 0],
  [10, 0],
  [10, 10],
];

function trackedOk() {
  return {
    success: true,
    data: {
      tracked: [
        {
          srcSn: 2,
          trackId: 't-1',
          label: 'person',
          points: [
            [0, 0],
            [1, 0],
            [1, 1],
          ],
          score: 0.9,
        },
      ],
    },
    message: null,
    errorCode: null,
  };
}

function renderTool(onCompleted = vi.fn()) {
  renderWithProviders(
    <Sam2TrackTool
      srcSn={START_SRC_SN}
      prevPolygon={TRIANGLE}
      label="person"
      trackId="t-1"
      nextSrcSns={NEXT_SRC_SNS}
      onCompleted={onCompleted}
    />,
  );
  return onCompleted;
}

describe('AI 추적 — 배타 실행/폐기 계약', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
    useLabelStore.setState({ busy: null, busyGeneration: 0 });
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('다른_작업_진행중이면_AI_추적_버튼이_비활성이라_요청이_나가지_않는다', async () => {
    // given: 저장이 진행 중
    //   ⚠ Phase 2 에서 차단 지점이 "요청 거부"에서 "입력 차단"으로 올라갔다 — 눌러봐야 거부될
    //     버튼을 활성처럼 보이게 두지 않는다(AC3). 진행 사실은 진행 표시가 알린다.
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply(200, trackedOk());
    const onCompleted = renderTool();
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: START_SRC_SN });
    });

    // when
    const trackButton = screen.getByRole('button', { name: 'AI 추적 시작' });
    expect(trackButton).toBeDisabled();
    fireEvent.click(trackButton);
    await act(async () => {
      await Promise.resolve();
    });

    // then: 요청 자체가 나가지 않는다.
    expect(mock.history.post).toHaveLength(0);
    expect(onCompleted).not.toHaveBeenCalled();
  });

  it('추적_취소_후_도착한_응답은_onTracked_없이_폐기되고_완료표시도_뜨지_않는다', async () => {
    // given: 추적 in-flight (응답을 수동으로 늦춘다)
    let releaseTrack: (() => void) | null = null;
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply(
      () =>
        new Promise((resolve) => {
          releaseTrack = () => resolve([200, trackedOk()]);
        }),
    );
    const onCompleted = renderTool();
    fireEvent.click(screen.getByRole('button', { name: 'AI 추적 시작' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('AI_TRACK'));

    // when: 사용자가 취소(다른 프레임 이동/취소 버튼 등) 후 응답 도착
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      releaseTrack?.();
      await Promise.resolve();
    });

    // then: 결과 병합도, 완료 표시도 없다(거짓 성공 금지).
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(onCompleted).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('AI 추적 완료')).toBeNull();
  });

  it('취소된_추적의_실패는_추적실패로_표시되지_않는다', async () => {
    // given: 취소 뒤에 실패 응답이 도착하는 상황
    let failTrack: (() => void) | null = null;
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply(
      () =>
        new Promise((resolve) => {
          failTrack = () => resolve([500, { success: false, data: null, message: 'boom', errorCode: 'E' }]);
        }),
    );
    renderTool();
    fireEvent.click(screen.getByRole('button', { name: 'AI 추적 시작' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('AI_TRACK'));

    // when
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      failTrack?.();
      await Promise.resolve();
    });

    // then: 내가 취소한 작업의 실패를 지금 화면에 띄우지 않는다.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(screen.queryByText('추적 실패')).toBeNull();
  });

  it('정상_추적은_완료_표시와_함께_결과가_전달된다', async () => {
    // given (무회귀 기준선)
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply(200, trackedOk());
    const onCompleted = renderTool();

    // when
    fireEvent.click(screen.getByRole('button', { name: 'AI 추적 시작' }));

    // then
    await waitFor(() => expect(onCompleted).toHaveBeenCalledTimes(1));
    expect(onCompleted.mock.calls[0][1]).toBe(false); // partial=false
    await waitFor(() => expect(screen.getByLabelText('AI 추적 완료')).toBeInTheDocument());
  });
});
