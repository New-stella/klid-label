// LabelingPage 메타 탭 — 이벤트 어노테이션 노출/미노출 검증.
//
// - INTERNAL 채널: 메타 탭의 요약 카드 → 창을 열면 이벤트 어노테이션 칸이 보인다
// - PORTAL 채널: 내부 창구를 부르는 이 카드·창을 두지 않는다(포털 본문은 별도 컴포넌트가 그린다)
//
// ⚠ 2026-09-14 — 구 시험은 「메타 탭에 이벤트 어노테이션 패널이 있다」를 고정했다. 그 패널이
//   창의 오른쪽 칸으로 옮겨갔으므로 진입 경로가 한 단계 늘었다(전제 변경, 회귀 아님).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const TEST_TOKEN = ['t', 'o', 'k'].join('');

function labelsPayload(srcSn: number) {
  return {
    success: true,
    data: { frameNo: 0, srcSn, videoId: 7, siblings: [{ srcSn, frameNo: 0 }], labels: [] },
    message: null,
    errorCode: null,
  };
}

function eventAnnoPayload(rawSn: number) {
  return {
    success: true,
    data: {
      rawSn,
      evntAnnoSn: 1,
      reviewStatus: 'AUTO_GENERATED',
      regId: 'sys',
      mdfcnId: null,
      payload: { event_class: 'car_accident' },
    },
    message: null,
    errorCode: null,
  };
}

/** 영상 상세 — 이벤트 분류 <b>이름</b>의 조달처(전체 검증 이벤트 유형 목록). */
function videoDetailPayload(rawSn: number) {
  return {
    success: true,
    data: {
      rawSn,
      id: rawSn,
      allVrfcEvntTypes: [
        { vrfcEvntTypeCd: 'car_accident', vrfcEvntTypeNm: '교통사고' },
        { vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재' },
      ],
    },
    message: null,
    errorCode: null,
  };
}

function commonMocks(mock: MockAdapter) {
  mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
  mock.onGet('/frames/300/image').reply(200, new Blob());
  mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
    success: true,
    data: { items: [] },
    message: null,
    errorCode: null,
  });
  mock.onGet(/\/frames\/\d+\/description/).reply(200, {
    success: true,
    data: { srcSn: 300, description: '' },
    message: null,
    errorCode: null,
  });
  mock.onGet('/videos/7/issues').reply(200, {
    success: true,
    data: [],
    message: null,
    errorCode: null,
  });
  mock.onGet('/videos/7/event-annotation').reply(200, eventAnnoPayload(7));
  mock.onGet('/videos/7').reply(200, videoDetailPayload(7));
  mock.onGet(/\/reviews\/\d+/).reply(200, {
    success: true,
    data: null,
    message: null,
    errorCode: null,
  });
}

function setup() {
  renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/300'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage 이벤트 어노테이션 노출', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    commonMocks(mock);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('INTERNAL_메타탭_요약카드에서_창을_열면_이벤트_어노테이션_칸이_보인다', async () => {
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());

    await user.click(screen.getByTestId('right-tab-meta'));
    await user.click(await screen.findByTestId('annotation-summary-open'));

    await waitFor(() =>
      expect(screen.getByTestId('event-annotation-panel')).toBeInTheDocument(),
    );
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toHaveValue('car_accident'));
  });

  it('★이벤트_분류는_이름과_코드로_함께_보이고_모르는_코드는_코드만_보인다', async () => {
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());
    await user.click(screen.getByTestId('right-tab-meta'));

    // 요약 카드 — 이름 + 코드
    const summary = await screen.findByTestId('annotation-summary-event-class');
    expect(summary).toHaveTextContent('교통사고');
    expect(summary).toHaveTextContent('car_accident');

    // 창 — 코드 입력 칸 옆에 이름
    await user.click(screen.getByTestId('annotation-summary-open'));
    await waitFor(() =>
      expect(screen.getByTestId('ea-event-class-name')).toHaveTextContent('교통사고'),
    );

    // when — 목록에 없는 코드로 바꾼다.
    await user.clear(screen.getByTestId('ea-event-class'));
    await user.type(screen.getByTestId('ea-event-class'), 'unknown_kind');

    // then — 이름을 지어내지 않는다(코드만 남는다).
    await waitFor(() => expect(screen.queryByTestId('ea-event-class-name')).toBeNull());
  });

  it('포털채널에서는_요약카드도_창도_두지_않는다', async () => {
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // 포털 본문은 포털 전용 컴포넌트가 그린다 — 내부 창구를 부르는 이 카드·창을 두면
    // 원장 수정·재검토 표시·관제 통지가 일어나 단방향 불변이 깨진다.
    expect(screen.queryByTestId('annotation-summary-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('annotation-window')).not.toBeInTheDocument();
  });
});
