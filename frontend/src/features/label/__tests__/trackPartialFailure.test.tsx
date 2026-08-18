// 이어보내기가 **실패했을 때** 앞 조각 결과가 살아남는가 — 자동 추적(AI 자동 추적) 경로.
//
// ★ 이 파일의 존재 이유
//   서버가 요청 하나의 시간 예산을 다 쓰면 그때까지의 결과를 돌려주고 화면이 이어 보낸다.
//   그 이어 보내기 중 한 요청이 실패하면, **이미 받아 둔 앞 조각의 검출까지 통째로 사라진다**
//   (지역 변수와 함께 버려진다). 사용자에게는 실패 안내만 뜨고, 서버가 이미 계산해 돌려준
//   최대 50프레임의 검출이 폐기된 채 같은 일을 다시 시키게 된다 — 이 라운드가 없애려던
//   「조용한 폐기 + 중복 재실행」이 그대로 재발한다.
//
// ★ 기존 추적(SAM2) 경로에는 이미 그 장치가 있다(성공분을 담은 오류를 던지고 화면이 병합한다).
//   여기서도 **같은 모양**을 쓴다 — 새 방식을 만들지 않는다.

import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';

import { publishAiWaitBudgets, resetAiWaitBudgets } from '../aiBudget';
import { AutoTrackPartialError, requestAutoTrackAll } from '../api/autoTrack';
import { AutoTrackPanel } from '../components/AutoTrackPanel';
import type { Label } from '../types';

const YOLO_PATH_RE = /\/frames\/(\d+)\/yolo-track/;

function ok(data: unknown, message: string | null = null) {
  return [200, { success: true, data, message, errorCode: null }] as [number, unknown];
}

/** 프레임 하나의 검출 1건 — 라벨 마스터에 연결된(labelId 있는) 정상 검출. */
function frame(srcSn: number, frameIndex: number) {
  return {
    srcSn,
    frameIndex,
    detections: [{ label: 'person', points: [0, 0, 10, 10], score: 0.9, trackId: 1, labelId: 7 }],
  };
}

describe('AI 자동 추적 — 이어보내기 실패 시 앞 조각 결과 보존', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
    publishAiWaitBudgets({ autoTrack: { baseSec: 0, perFrameSec: 0, ceilingSec: 100_000 } });
  });

  afterEach(() => {
    mock.restore();
    resetAiWaitBudgets();
  });

  it('이어보내기가_실패해도_앞_조각_검출이_오류에_실려_온다', async () => {
    // given: 1차는 앞 2프레임을 돌려주고 잘렸다고 알리고, 이어 보낸 2차가 실패한다
    let calls = 0;
    mock.onPost(YOLO_PATH_RE).reply(() => {
      calls += 1;
      if (calls === 1) {
        return ok({
          frames: [frame(100, 0), frame(101, 1)],
          truncated: true,
          resume: { srcSn: 102, nextSrcSns: [103] },
        });
      }
      return [500, { success: false, data: null, message: '서버 오류', errorCode: 'INTERNAL' }];
    });

    // when
    const err = await requestAutoTrackAll(100, [101, 102, 103]).then(
      () => null,
      (e: unknown) => e,
    );

    // then: 서버가 이미 계산해 준 앞 조각 2프레임이 버려지지 않는다
    expect(err).toBeInstanceOf(AutoTrackPartialError);
    const partial = (err as AutoTrackPartialError).partial;
    expect((partial.frames ?? []).map((f) => f.srcSn)).toEqual([100, 101]);
    // 그리고 «어디부터 못 했는지» 도 남는다 — 남은 구간을 감추지 않는다
    expect(partial.truncated).toBe(true);
    expect(partial.resume).toEqual({ srcSn: 102, nextSrcSns: [103] });
    expect(calls).toBe(2);
  });

  it('실패해도_앞_조각_검출은_화면에_남아_다시_실행하지_않아도_된다', async () => {
    // given: 위와 같은 상황 — 1차 부분 결과 뒤 2차 실패
    let calls = 0;
    mock.onPost(YOLO_PATH_RE).reply(() => {
      calls += 1;
      if (calls === 1) {
        return ok({
          frames: [frame(200, 0), frame(201, 1)],
          truncated: true,
          resume: { srcSn: 202, nextSrcSns: [] },
        });
      }
      return [500, { success: false, data: null, message: '서버 오류', errorCode: 'INTERNAL' }];
    });

    renderWithProviders(
      <AutoTrackPanel
        srcSn={200}
        frames={[
          { srcSn: 200, frameNo: 0 },
          { srcSn: 201, frameNo: 1 },
          { srcSn: 202, frameNo: 2 },
        ]}
        nextSrcSns={[201, 202]}
        onApply={vi.fn(() => ({ appliedLabels: 0, skippedDuplicates: 0 }))}
      />,
    );

    // when
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'AI 자동 추적' }));
    });

    // then: 실패했지만 앞 조각 검출은 검토 목록에 남는다(통째로 버려지지 않는다)
    await waitFor(() => {
      expect(screen.getByTestId('auto-track-review')).toBeInTheDocument();
    });
    expect(screen.getByText(/person T:/)).toBeInTheDocument();
    // 그리고 실패 사실도 함께 알린다 — 부분 결과를 남겼다고 실패를 감추지 않는다
    expect(screen.getByTestId('auto-track-notice')).toHaveTextContent(/오류가 나 중단했습니다/);
  });

  it('자동_반영이면_앞_조각_검출이_작업본에_올라간다', async () => {
    let calls = 0;
    mock.onPost(YOLO_PATH_RE).reply(() => {
      calls += 1;
      if (calls === 1) {
        return ok({
          frames: [frame(300, 0), frame(301, 1)],
          truncated: true,
          resume: { srcSn: 302, nextSrcSns: [] },
        });
      }
      return [500, { success: false, data: null, message: '서버 오류', errorCode: 'INTERNAL' }];
    });

    const onApply = vi.fn((_bySrcSn: Record<number, Label[]>) => ({
      appliedLabels: 2,
      skippedDuplicates: 0,
    }));
    renderWithProviders(
      <AutoTrackPanel
        srcSn={300}
        frames={[
          { srcSn: 300, frameNo: 0 },
          { srcSn: 301, frameNo: 1 },
          { srcSn: 302, frameNo: 2 },
        ]}
        nextSrcSns={[301, 302]}
        onApply={onApply}
      />,
    );

    // 자동 반영으로 실행한다
    await act(async () => {
      fireEvent.click(screen.getByLabelText('자동 반영'));
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'AI 자동 추적' }));
    });

    // then: 실패 전까지 받은 두 프레임이 작업본에 올라간다
    await waitFor(() => expect(onApply).toHaveBeenCalledTimes(1));
    const applied = onApply.mock.calls[0][0];
    expect(
      Object.keys(applied)
        .map(Number)
        .sort((a, b) => a - b),
    ).toEqual([300, 301]);
  });

  it('첫_요청부터_실패하면_부분_결과가_없다고_말한다', async () => {
    // 부분 결과 보존이 «실패를 성공처럼 보이게» 하면 안 된다 — 받은 것이 없으면 없다고 한다.
    mock
      .onPost(YOLO_PATH_RE)
      .reply(() => [
        500,
        { success: false, data: null, message: '서버 오류', errorCode: 'INTERNAL' },
      ]);

    const err = await requestAutoTrackAll(400, [401]).then(
      () => null,
      (e: unknown) => e,
    );

    expect(err).toBeInstanceOf(AutoTrackPartialError);
    expect((err as AutoTrackPartialError).partial.frames).toEqual([]);
  });
});
