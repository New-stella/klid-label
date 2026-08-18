// 라벨링 화면 — 온디맨드 자동 추적 배선.
//
// 이 파일이 고정하는 계약:
//  - 자동 반영이어도 **저장 전에는 확정되지 않는다**(PUT 미호출, 미저장 표식만 선다).
//  - 응답의 라벨 마스터 식별자(labelId)가 **저장 요청 본문까지 그대로** 실린다.
//    (여기가 끊기면 색·라벨명·속성 정의가 함께 끊기고 재조회 후에야 드러난다.)
//  - 미래 프레임 결과는 보류 스테이징에 담겨 그 프레임 진입 시 병합된다(사일런트 유실 방지).
//
// @design SCREEN-005, API-123, API-019, UC-034

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

const EXISTING = {
  id: 91,
  frameNo: 0,
  lblTypeCd: 'BBOX',
  label: 'car',
  labelId: 5,
  points: [[0, 0], [10, 10]],
  autoLblYn: 'N',
  confScore: null,
  trackId: null,
};

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
      ],
      labels: [EXISTING],
    },
    message: null,
    errorCode: null,
  };
}

/**
 * 현재 프레임에 **이미 있는 라벨과 같은 자리·같은 분류**만 돌아오는 응답.
 * 상위 병합이 전건을 중복으로 걸러내므로 실제 반영은 0건이 된다.
 */
function duplicateOnlyPayload() {
  return {
    success: true,
    data: {
      frames: [
        {
          srcSn: 300,
          frameIndex: 0,
          detections: [
            { label: 'car', points: [0, 0, 10, 10], score: 0.9, trackId: 4, labelId: 5 },
          ],
        },
      ],
    },
    message: null,
    errorCode: null,
  };
}

/** 현재 프레임 1건(person, 마스터 42) + 다음 프레임 1건(같은 트랙). */
function trackPayload() {
  return {
    success: true,
    data: {
      frames: [
        {
          srcSn: 300,
          frameIndex: 0,
          detections: [
            { label: 'person', points: [500, 500, 600, 600], score: 0.9, trackId: 3, labelId: 42 },
          ],
        },
        {
          srcSn: 301,
          frameIndex: 1,
          detections: [
            { label: 'person', points: [505, 505, 605, 605], score: 0.9, trackId: 3, labelId: 42 },
          ],
        },
      ],
    },
    message: null,
    errorCode: null,
  };
}

const TEST_TOKEN = ['t', 'o', 'k'].join('');

describe('LabelingPage — AI 자동 추적', () => {
  let mock: MockAdapter;
  let putBodies: unknown[];

  beforeEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    putBodies = [];
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload());
    mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, { success: true, data: null, message: null, errorCode: null });
    mock.onGet(/\/frames\/\d+\/description/).reply(200, {
      success: true,
      data: { srcSn: 300, description: '' },
      message: null,
      errorCode: null,
    });
    mock.onGet('/manage/labels').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet('/manage/labels/detect-candidates').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet('/videos/7/issues').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet(/\/reviews\/\d+/).reply(200, { success: true, data: null, message: null, errorCode: null });
    mock.onPost('/frames/300/yolo-track').reply(200, trackPayload());
    mock.onPut('/frames/300/labels').reply((config) => {
      putBodies.push(JSON.parse(String(config.data)));
      return [200, { success: true, data: labelsPayload().data, message: null, errorCode: null }];
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    vi.restoreAllMocks();
  });

  function setup() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  async function runAutoTrackWithAutoApply() {
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
    await userEvent.click(screen.getByRole('radio', { name: '자동 반영' }));
    await userEvent.click(screen.getByRole('button', { name: 'AI 자동 추적' }));
    await screen.findByTestId('auto-track-notice');
  }

  it('자동_반영이어도_저장_전에는_확정되지_않는다', async () => {
    // given / when
    setup();
    await runAutoTrackWithAutoApply();

    // then: 현재 프레임 결과가 기존 라벨을 유지한 채 작업본에 올라가고, 저장은 호출되지 않는다
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(2));
    const labels = useLabelStore.getState().labels;
    expect(labels.some((l) => l.className === 'car')).toBe(true);
    expect(labels.some((l) => l.className === 'person')).toBe(true);
    expect(useLabelStore.getState().dirtyLabels.size).toBe(1);
    expect(putBodies).toHaveLength(0);
  });

  it('응답의_labelId가_저장_요청_본문까지_그대로_실린다', async () => {
    // given
    setup();
    await runAutoTrackWithAutoApply();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(2));

    // when: 사용자가 저장을 눌러야 확정된다
    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    // then: 검출 클래스명으로 다시 찾은 값이 아니라 응답값 42 가 그대로 실린다
    await waitFor(() => expect(putBodies).toHaveLength(1));
    const body = putBodies[0] as { items: { label: string; labelId: number | null }[] };
    const person = body.items.find((i) => i.label === 'person');
    expect(person).toBeDefined();
    expect(person?.labelId).toBe(42);
  });

  /*
   * ★ 중복 제거로 **실제 반영이 0건인데 «올렸습니다» 라고 알리던** 지점(적대 검증 지적).
   * 상위 병합은 이미 작업본에 있는 같은 분류·겹치는 검출을 건너뛴다 — 패널이 요청 건수로 안내하면
   * 사용자는 작업본에 없는 것을 있다고 믿는다. 실제 병합 결과를 근거로 안내해야 한다.
   */
  it('이미_있는_라벨과_겹쳐_전부_걸러지면_올렸다고_알리지_않는다', async () => {
    // given: 응답이 기존 라벨(car, [[0,0],[10,10]])과 같은 자리·같은 분류뿐이다
    mock.onPost('/frames/300/yolo-track').reply(200, duplicateOnlyPayload());
    setup();

    // when
    await runAutoTrackWithAutoApply();

    // then: 작업본은 그대로(1건)이고 안내도 «올렸습니다» 라고 말하지 않는다
    expect(useLabelStore.getState().labels).toHaveLength(1);
    const notice = screen.getByTestId('auto-track-notice');
    expect(notice.textContent ?? '').not.toMatch(/올렸습니다/);
    expect(notice.textContent ?? '').toMatch(/작업본에 올라간 검출이 없습니다/);
    expect(notice.textContent ?? '').toMatch(/겹쳐 반영하지 않았습니다/);
  });

  it('미래_프레임_결과는_보류_스테이징에_담긴다', async () => {
    // given / when
    setup();
    await runAutoTrackWithAutoApply();

    // then: 다음 프레임(301) 몫은 그 프레임에 진입할 때 병합되도록 보류된다
    await waitFor(() => {
      const pending = useLabelStore.getState().pendingTracks[301] ?? [];
      expect(pending).toHaveLength(1);
      expect(pending[0].labelId).toBe(42);
      expect(pending[0].trackId).toBe('3');
    });
  });
});
