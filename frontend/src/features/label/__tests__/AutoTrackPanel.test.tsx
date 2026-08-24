// 온디맨드 자동 추적 패널 — 확정 사양(SCREEN-005) 회귀 가드.
//
// 이 파일이 고정하는 계약:
//  - 실행 버튼 표기는 「AI 자동 추적」이며 선택 객체를 따라가는 「AI 추적」과 구분된다.
//    ⚠ 이 화면의 고충실 시안(SD-002)·로컬 화면 키트는 아직 구 표기(내부 모델명이 든 이름)를
//      들고 있다 — 정본은 서버 사양이다.
//  - 시작 객체를 고르지 않고 실행되며 **요청에 시작 객체를 싣지 않는다**.
//  - 적용 방식 기본값은 「검토 후 수락」이고, 고른 방식은 그 실행에만 적용된다(다음 실행은 다시 기본값).
//  - 수락·제외의 단위는 트랙이며 제외한 묶음은 작업 목록에 들어가지 않는다.
//  - 라벨 마스터 식별자가 비어 있는 검출은 반영하지 않고 그 사실을 알린다.
//
// @design SCREEN-005, API-123, UC-034, AC-038, AC-039, AC-040

import { readFileSync } from 'node:fs';
import path from 'node:path';

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  AutoTrackPanel,
  type AutoTrackApplyOutcome,
} from '@/features/label/components/AutoTrackPanel';
import type { Label } from '@/features/label/types';
import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

const FRAMES = [
  { srcSn: 300, frameNo: 0 },
  { srcSn: 301, frameNo: 1 },
];
const NEXT = [301];

/** 트랙 7(2프레임) + 트랙 8(1프레임). */
function twoTrackPayload() {
  return {
    success: true,
    data: {
      frames: [
        {
          srcSn: 300,
          frameIndex: 0,
          detections: [
            { label: 'person', points: [10, 10, 20, 20], score: 0.9, trackId: 7, labelId: 11 },
            { label: 'car', points: [30, 30, 40, 40], score: 0.8, trackId: 8, labelId: 12 },
          ],
        },
        {
          srcSn: 301,
          frameIndex: 1,
          detections: [
            { label: 'person', points: [12, 12, 22, 22], score: 0.9, trackId: 7, labelId: 11 },
          ],
        },
      ],
    },
    message: null,
    errorCode: null,
  };
}

/**
 * 반영 주체(상위 화면) mock — **실제로 올라간 건수**를 돌려준다.
 *
 * 기본값은 중복이 없는 상황(요청분 전부 반영)을 흉내낸다. 중복 제거로 걸러지는 상황은
 * 개별 테스트에서 `outcome` 을 주어 따로 만든다.
 */
function makeApplyMock(outcome?: AutoTrackApplyOutcome) {
  return vi.fn(
    (bySrcSn: Record<number, Label[]>): AutoTrackApplyOutcome =>
      outcome ?? {
        appliedLabels: Object.values(bySrcSn).reduce((n, list) => n + list.length, 0),
        skippedDuplicates: 0,
      },
  );
}

describe('AI 자동 추적 패널', () => {
  let mock: MockAdapter;
  let onApply: ReturnType<typeof makeApplyMock>;
  let bodies: unknown[];

  beforeEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock = new MockAdapter(apiClient);
    bodies = [];
    onApply = makeApplyMock();
    mock.onPost('/frames/300/yolo-track').reply((config) => {
      bodies.push(JSON.parse(String(config.data)));
      return [200, twoTrackPayload()];
    });
  });

  afterEach(() => {
    mock.restore();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  function renderPanel(props: Partial<Parameters<typeof AutoTrackPanel>[0]> = {}) {
    return render(
      <AutoTrackPanel
        srcSn={300}
        frames={FRAMES}
        nextSrcSns={NEXT}
        onApply={onApply}
        {...props}
      />,
    );
  }

  const runButton = () => screen.getByRole('button', { name: 'AI 자동 추적' });

  it('실행_버튼_표기는_AI_자동_추적이며_AI_추적과_구분된다', () => {
    // given / when
    renderPanel();

    // then: 정확히 이 이름이어야 한다(구 표기·내부 모델명 금지)
    expect(runButton()).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 추적' })).toBeNull();
    expect(screen.queryByText(/YOLO/i)).toBeNull();
  });

  it('시작_객체를_고르지_않아도_실행되고_요청에_시작_객체를_싣지_않는다', async () => {
    // given: 선택된 라벨이 없다(store 초기 상태)
    expect(useLabelStore.getState().selectedLabelId).toBeNull();
    renderPanel();

    // when
    await userEvent.click(runButton());

    // then: 프레임 구간만 실린다 — 기존 추적의 시작 객체 필드(trackId·prevPolygon·label)는 없다
    await screen.findByTestId('auto-track-review');
    expect(bodies).toHaveLength(1);
    expect(bodies[0]).toEqual({ srcSn: 300, nextSrcSns: [301] });
  });

  it('뒤따르는_프레임이_없으면_실행할_수_없다', async () => {
    // given / when
    renderPanel({ nextSrcSns: [] });

    // then
    expect(runButton()).toBeDisabled();
    expect(screen.getByText('뒤따르는 프레임이 없어 실행할 수 없습니다.')).toBeInTheDocument();
    expect(bodies).toHaveLength(0);
  });

  it('편집이_차단되면_실행할_수_없다', () => {
    // given / when
    renderPanel({ disabled: true });

    // then
    expect(runButton()).toBeDisabled();
  });

  it('적용_방식_기본값은_검토_후_수락이다', () => {
    // given / when
    renderPanel();

    // then
    const group = screen.getByRole('radiogroup', { name: '트랙 결과 적용 방식' });
    expect(within(group).getByRole('radio', { name: '검토 후 수락' })).toBeChecked();
    expect(within(group).getByRole('radio', { name: '자동 반영' })).not.toBeChecked();
  });

  it('검토_후_수락에서는_수락하기_전까지_작업본에_반영하지_않는다', async () => {
    // given
    renderPanel();

    // when
    await userEvent.click(runButton());
    await screen.findByTestId('auto-track-review');

    // then: 결과가 왔지만 아직 아무것도 올라가지 않았다
    expect(onApply).not.toHaveBeenCalled();
  });

  it('자동_반영을_고르면_검토_없이_전량_작업본에_올라간다', async () => {
    // given
    renderPanel();
    await userEvent.click(screen.getByRole('radio', { name: '자동 반영' }));

    // when
    await userEvent.click(runButton());

    // then: 검토 목록 없이 즉시 반영(작업본만 — 확정은 저장)
    await screen.findByTestId('auto-track-notice');
    expect(onApply).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('auto-track-review')).toBeNull();
    const applied = onApply.mock.calls[0][0] as Record<number, Label[]>;
    expect(applied[300]).toHaveLength(2);
    expect(applied[301]).toHaveLength(1);
  });

  it('고른_방식은_그_실행에만_적용되고_다음_실행은_다시_기본값으로_시작한다', async () => {
    // given: 직전 실행에서 자동 반영을 골랐다
    renderPanel();
    await userEvent.click(screen.getByRole('radio', { name: '자동 반영' }));
    await userEvent.click(runButton());
    await screen.findByTestId('auto-track-notice');
    expect(onApply).toHaveBeenCalledTimes(1);

    // then: 선택이 기본값으로 되돌아가 있다(이전 선택을 기억하지 않는다)
    expect(screen.getByRole('radio', { name: '검토 후 수락' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '자동 반영' })).not.toBeChecked();

    // when: 라디오를 다시 만지지 않고 그대로 재실행
    await userEvent.click(runButton());

    // then: 검토 목록이 뜨고 추가 반영은 없다(무심코 자동 반영되지 않는다)
    await screen.findByTestId('auto-track-review');
    expect(onApply).toHaveBeenCalledTimes(1);
  });

  it('수락_제외는_트랙_단위이고_제외한_묶음은_작업본에_들어가지_않는다', async () => {
    // given
    renderPanel();
    await userEvent.click(runButton());
    const review = await screen.findByTestId('auto-track-review');

    // then: 고를 항목은 검출 3건이 아니라 트랙 2건이다
    const checkboxes = within(review).getAllByRole('checkbox');
    expect(checkboxes).toHaveLength(2);

    // when: person 트랙(2프레임 2건)을 제외하고 반영
    await userEvent.click(within(review).getByRole('checkbox', { name: 'person T:7 수락' }));
    await userEvent.click(screen.getByRole('button', { name: '수락한 트랙 반영' }));

    // then: 제외한 트랙의 라벨은 어느 프레임에도 들어가지 않는다
    await screen.findByTestId('auto-track-notice');
    expect(onApply).toHaveBeenCalledTimes(1);
    const applied = onApply.mock.calls[0][0] as Record<number, Label[]>;
    expect(applied[300]).toHaveLength(1);
    expect(applied[300][0].className).toBe('car');
    expect(applied[301] ?? []).toHaveLength(0);
  });

  it('모두_제외하면_작업본에_아무것도_올리지_않는다', async () => {
    // given
    renderPanel();
    await userEvent.click(runButton());
    await screen.findByTestId('auto-track-review');

    // when
    await userEvent.click(screen.getByRole('button', { name: '모두 제외' }));

    // then
    expect(onApply).not.toHaveBeenCalled();
    expect(screen.queryByTestId('auto-track-review')).toBeNull();
    expect(
      screen.getByText(/검출 결과를 모두 제외했습니다/),
    ).toBeInTheDocument();
  });

  it('마스터_미연결_검출은_반영하지_않고_그_사실과_사유를_알린다', async () => {
    // given: labelId 가 비어 있는 검출 1건 + 정상 1건
    mock.onPost('/frames/300/yolo-track').reply(200, {
      success: true,
      data: {
        frames: [
          {
            srcSn: 300,
            frameIndex: 0,
            detections: [
              { label: 'kite', points: [1, 1, 2, 2], score: 0.7, trackId: 9, labelId: null },
              { label: 'car', points: [3, 3, 4, 4], score: 0.8, trackId: 8, labelId: 12 },
            ],
          },
        ],
      },
      message: null,
      errorCode: null,
    });
    renderPanel();

    // when
    await userEvent.click(runButton());
    const review = await screen.findByTestId('auto-track-review');

    // then: 고를 수 있는 것은 연결된 트랙뿐이고, 빠진 사실·사유가 드러난다
    expect(within(review).getAllByRole('checkbox')).toHaveLength(1);
    expect(within(review).queryByText(/kite/)).toBeNull();
    const notice = screen.getByTestId('auto-track-notice');
    expect(within(notice).getByText(/검출 1건은 라벨 마스터에 연결되지 않아/)).toBeInTheDocument();
    expect(within(notice).getByText(/검출 클래스 매핑 미지정/)).toBeInTheDocument();

    // and: 수락해도 미연결 검출은 올라가지 않는다
    await userEvent.click(screen.getByRole('button', { name: '수락한 트랙 반영' }));
    await waitFor(() => expect(onApply).toHaveBeenCalledTimes(1));
    await screen.findByTestId('auto-track-notice');
    const applied = onApply.mock.calls[0][0] as Record<number, Label[]>;
    expect(applied[300]).toHaveLength(1);
    expect(applied[300][0].className).toBe('car');
  });

  it('마스터_미연결만_돌아오면_빈_검토_목록_대신_사유를_알린다', async () => {
    // given
    mock.onPost('/frames/300/yolo-track').reply(200, {
      success: true,
      data: {
        frames: [
          {
            srcSn: 300,
            frameIndex: 0,
            detections: [
              { label: 'kite', points: [1, 1, 2, 2], score: 0.7, trackId: 9, labelId: null },
            ],
          },
        ],
      },
      message: null,
      errorCode: null,
    });
    renderPanel();

    // when
    await userEvent.click(runButton());

    // then
    const notice = await screen.findByTestId('auto-track-notice');
    expect(within(notice).getByText(/반영할 검출이 없습니다/)).toBeInTheDocument();
    expect(screen.queryByTestId('auto-track-review')).toBeNull();
    expect(onApply).not.toHaveBeenCalled();
  });

  /*
   * ★ 자동 반영 + 전량 마스터 미연결 — 안내가 **모순되게 이중 출력**되던 지점.
   * 구 동작: 「트랙 0건을 작업본에 올렸습니다」 + 「반영할 검출이 없습니다」 가 함께 떴다.
   * 검토 모드에는 빈 결과 방어가 있었는데 자동 반영에만 없어서 두 모드가 비대칭이었다.
   */
  it('자동_반영에서_전량_미연결이면_올렸다고_말하지_않고_사유만_알린다', async () => {
    // given: 응답 전건이 마스터 미연결
    mock.onPost('/frames/300/yolo-track').reply(200, {
      success: true,
      data: {
        frames: [
          {
            srcSn: 300,
            frameIndex: 0,
            detections: [
              { label: 'kite', points: [1, 1, 2, 2], score: 0.7, trackId: 9, labelId: null },
            ],
          },
        ],
      },
      message: null,
      errorCode: null,
    });
    renderPanel();
    await userEvent.click(screen.getByRole('radio', { name: '자동 반영' }));

    // when
    await userEvent.click(runButton());

    // then: 사유 한 줄만 남고 «올렸습니다» 는 없다 — 검토 모드와 같은 처리다(대칭)
    const notice = await screen.findByTestId('auto-track-notice');
    expect(within(notice).getByText(/반영할 검출이 없습니다/)).toBeInTheDocument();
    expect(within(notice).queryByText(/올렸습니다/)).toBeNull();
    expect(within(notice).getByText(/라벨 마스터에 연결되지 않아/)).toBeInTheDocument();
    // 올릴 것이 없으면 반영 자체를 시도하지 않는다
    expect(onApply).not.toHaveBeenCalled();
  });

  /*
   * ★ 중복 제거로 실제 반영이 0건인데 «올렸습니다» 라고 알리던 지점.
   * 상위 병합은 이미 작업본에 있는 같은 분류·같은 자리 라벨과 겹치는 검출을 건너뛴다. 패널이 요청
   * 건수로 안내하면 사용자는 작업본에 없는 것을 있다고 믿는다.
   */
  it('자동_반영에서_전량_중복이면_올렸다고_말하지_않고_겹쳐_빠진_사실을_알린다', async () => {
    // given: 상위가 "요청 3건 전부 중복으로 걸러짐" 을 돌려준다
    onApply = makeApplyMock({ appliedLabels: 0, skippedDuplicates: 3 });
    renderPanel();
    await userEvent.click(screen.getByRole('radio', { name: '자동 반영' }));

    // when
    await userEvent.click(runButton());

    // then: 실제 반영 건수를 근거로 안내한다
    const notice = await screen.findByTestId('auto-track-notice');
    expect(onApply).toHaveBeenCalledTimes(1);
    expect(within(notice).queryByText(/올렸습니다/)).toBeNull();
    expect(within(notice).getByText(/작업본에 올라간 검출이 없습니다/)).toBeInTheDocument();
    expect(within(notice).getByText(/겹쳐 반영하지 않았습니다/)).toBeInTheDocument();
  });

  it('일부만_중복이면_실제_반영_건수로_안내하고_빠진_건수도_알린다', async () => {
    // given: 요청 3건 중 2건만 실제로 올라갔다
    onApply = makeApplyMock({ appliedLabels: 2, skippedDuplicates: 1 });
    renderPanel();
    await userEvent.click(screen.getByRole('radio', { name: '자동 반영' }));

    // when
    await userEvent.click(runButton());

    // then: 요청 건수(3)가 아니라 반영 건수(2)를 말한다
    const notice = await screen.findByTestId('auto-track-notice');
    expect(within(notice).getByText(/검출 2건을 작업본에 올렸습니다/)).toBeInTheDocument();
    expect(within(notice).getByText(/검출 1건은 이미 작업본에 있는 라벨과 겹쳐/)).toBeInTheDocument();
  });

  it('검토_후_수락에서도_실제_반영_건수로_안내한다', async () => {
    // given: 수락한 트랙이 전부 중복으로 걸러진다
    onApply = makeApplyMock({ appliedLabels: 0, skippedDuplicates: 3 });
    renderPanel();
    await userEvent.click(runButton());
    await screen.findByTestId('auto-track-review');

    // when
    await userEvent.click(screen.getByRole('button', { name: '수락한 트랙 반영' }));

    // then: 두 모드가 같은 근거로 안내한다
    const notice = await screen.findByTestId('auto-track-notice');
    expect(within(notice).queryByText(/올렸습니다/)).toBeNull();
    expect(within(notice).getByText(/작업본에 올라간 검출이 없습니다/)).toBeInTheDocument();
  });

  it('실패하면_실패로_알리고_작업본을_건드리지_않는다', async () => {
    // given
    mock.onPost('/frames/300/yolo-track').reply(502, {
      success: false,
      data: null,
      message: '추론 서버 연동 실패',
      errorCode: 'EXTERNAL_API_ERROR',
    });
    renderPanel();

    // when
    await userEvent.click(runButton());

    // then
    expect(await screen.findByText(/AI 자동 추적에 실패했습니다/)).toBeInTheDocument();
    expect(onApply).not.toHaveBeenCalled();
  });
});

describe('AI 자동 추적 — 마스터 재조회 금지(정적 가드)', () => {
  /**
   * 화면이 검출 클래스명으로 라벨 마스터를 다시 찾으면 같은 규칙이 두 곳에 생겨 한쪽이 낡는다.
   * 게다가 이 경로의 이름은 **AI 검출 클래스명(COCO 영문)** 이고 그 조회 함수는 한글 마스터명을
   * 기대하므로 원리적으로 매칭되지 않는다 — 조용히 labelId=null 로 저장된다.
   */
  it('자동_추적_경로는_라벨명으로_마스터를_다시_찾지_않는다', () => {
    const files = [
      'components/AutoTrackPanel.tsx',
      'hooks/useAutoTrack.ts',
      'utils/autoTrackResult.ts',
      'api/autoTrack.ts',
    ];

    for (const rel of files) {
      const src = readFileSync(path.resolve(__dirname, '..', rel), 'utf-8');
      expect(src, `${rel} 가 라벨명으로 마스터를 재조회한다`).not.toContain(
        'resolveLabelIdByName',
      );
      expect(src, `${rel} 가 라벨 마스터 목록을 조회한다`).not.toContain('useLabelMasters');
    }
  });
});
