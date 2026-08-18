// 부분 결과 계약이 **화면에 보이는가** — 이어 보내는 동안 진행이 이어지고, 끝내지 못했으면 알린다.
//
// ★ 조용히 끝나면 사용자는 «뒤쪽 프레임엔 왜 라벨이 없지» 를 알 수 없다. 서버는 200 을 줬고
//   화면도 오류를 띄우지 않으므로, 말해 주지 않으면 아무 데도 그 사실이 남지 않는다.

import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';

import { publishAiWaitBudgets, resetAiWaitBudgets } from '../aiBudget';
import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';
import { AutoTrackPanel } from '../components/AutoTrackPanel';

const TRIANGLE: number[][] = [
  [0, 0],
  [10, 0],
  [10, 10],
];

function ok(data: unknown, message: string | null = null) {
  return [200, { success: true, data, message, errorCode: null }] as [number, unknown];
}

describe('AI 추적 — 끝내지 못한 구간을 알린다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
    publishAiWaitBudgets({
      sam2Track: { baseSec: 0, perFrameSec: 0, ceilingSec: 100_000 },
      autoTrack: { baseSec: 0, perFrameSec: 0, ceilingSec: 100_000 },
    });
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('추적이_끝까지_가지_못하면_남은_프레임을_알린다', async () => {
    // given: 이어 보내도 진행이 없어 멈추는 응답(예산이 한 프레임도 담지 못하는 설정)
    mock.onPost(/\/frames\/\d+\/sam2-track/).reply((config) => {
      const body = JSON.parse(String(config.data ?? '{}'));
      return ok({
        tracked: [],
        truncated: true,
        resume: {
          srcSn: body.srcSn as number,
          prevPolygon: TRIANGLE,
          nextSrcSns: body.nextSrcSns as number[],
        },
      });
    });

    renderWithProviders(
      <Sam2TrackTool
        srcSn={55}
        prevPolygon={TRIANGLE}
        label="person"
        trackId="t-1"
        nextSrcSns={[56, 57, 58]}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'AI 추적 시작' }));
    });

    await waitFor(() => {
      expect(screen.getByText(/남은 프레임 3개/)).toBeInTheDocument();
    });
  });

  it('자동_추적이_끝까지_가지_못하면_남은_프레임을_알린다', async () => {
    mock.onPost(/\/frames\/\d+\/yolo-track/).reply((config) => {
      const body = JSON.parse(String(config.data ?? '{}'));
      return ok({
        frames: [],
        truncated: true,
        resume: { srcSn: body.srcSn as number, nextSrcSns: body.nextSrcSns as number[] },
      });
    });

    renderWithProviders(
      <AutoTrackPanel
        srcSn={70}
        frames={[
          { srcSn: 70, frameNo: 0 },
          { srcSn: 71, frameNo: 1 },
        ]}
        nextSrcSns={[71]}
        onApply={vi.fn(() => ({ appliedLabels: 0, skippedDuplicates: 0 }))}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'AI 자동 추적' }));
    });

    await waitFor(() => {
      // 시작 프레임 + 후속 1건 = 2건이 남았다
      expect(screen.getByText(/남은 프레임 2개/)).toBeInTheDocument();
    });
  });

  it('자동_추적은_이어_보내는_동안_진행을_보여준다', async () => {
    // ★ 서버가 잘라 보내면 화면이 이어 보내는데, 그동안 아무 것도 바뀌지 않으면 «멈췄나» 로 보인다.
    //   두 번째 요청을 끝나지 않게 두고, 그 사이 진행 표시를 확인한다.
    let calls = 0;
    mock.onPost(/\/frames\/\d+\/yolo-track/).reply(() => {
      calls += 1;
      if (calls === 1) {
        // 시퀀스 3건 중 시작 프레임 1건만 처리하고 남긴다
        return ok({
          frames: [{ srcSn: 80, frameIndex: 0, detections: [] }],
          truncated: true,
          resume: { srcSn: 81, nextSrcSns: [82] },
        });
      }
      // 이어 보낸 요청은 끝나지 않는다 — 그 사이의 화면을 본다
      return new Promise(() => {});
    });

    renderWithProviders(
      <AutoTrackPanel
        srcSn={80}
        frames={[
          { srcSn: 80, frameNo: 0 },
          { srcSn: 81, frameNo: 1 },
          { srcSn: 82, frameNo: 2 },
        ]}
        nextSrcSns={[81, 82]}
        onApply={vi.fn(() => ({ appliedLabels: 0, skippedDuplicates: 0 }))}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'AI 자동 추적' }));
    });

    // 이어 보내는 중에도 진행이 올라와 있다(«0/3» 에 멈춰 있지 않다)
    await waitFor(() => {
      expect(screen.getByTestId('auto-track-progress')).toHaveTextContent('1/3');
    });
    expect(calls).toBe(2);
  });
});
