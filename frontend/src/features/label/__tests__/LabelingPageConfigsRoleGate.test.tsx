// 회귀 가드 — 라벨링 진입 시 REVIEWER 전용 시스템 설정 조회를 역할로 게이팅한다.
//
// 결함(브라우저 실측): WORKER 로 `/label/:id` 에 진입할 때마다 `GET /v1/manage/configs` 가
//       조건 없이 나가 **매번 403 이 2건** 쌓였다. 그 엔드포인트는 SecurityConfig 의
//       `/v1/manage/**` 매처 + 컨트롤러 `@PreAuthorize` 로 REVIEWER 전용이다.
//
// 고치는 방식이 중요하다 — 실패를 조용히 삼키면 콘솔만 조용해지고 **불필요한 왕복은 남는다**.
// 그래서 "호출 자체가 없었는가"를 요청 수로 단언한다(에러 표시 유무가 아니라).
//
// 값의 성격: 이 설정은 AI 도구 슬라이더의 **기본값 프리필**일 뿐이고 미조회 시 컴포넌트 코드
// 상수로 폴백하도록 설계돼 있어, 호출을 막아도 WORKER 의 AI 도구 동작은 달라지지 않는다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    const Stub = ({
      children,
      ...rest
    }: { children?: React.ReactNode } & Record<string, unknown>) =>
      React.createElement('div', { 'data-konva': name, ...rest }, children);
    Stub.displayName = `KonvaStub(${name})`;
    return Stub;
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const SRC_SN = 410;
const RAW_SN = 41;

function ok<T>(data: T) {
  return { success: true, data, message: null, errorCode: null };
}

function renderPage() {
  return renderWithProviders(<LabelingPage />, {
    initialEntries: [`/label/${SRC_SN}`],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('라벨링 진입 시 시스템 설정 조회 역할 게이팅', () => {
  let mock: MockAdapter;
  let configCalls: number;

  beforeEach(() => {
    configCalls = 0;
    mock = new MockAdapter(apiClient);
    mock.onGet(`/frames/${SRC_SN}/image`).reply(200, new Blob());
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: {
        frameNo: 0,
        srcSn: SRC_SN,
        videoId: RAW_SN,
        siblings: [{ srcSn: SRC_SN, frameNo: 0 }],
        labels: [],
      },
      message: null,
      errorCode: null,
    });
    mock.onGet(`/videos/${RAW_SN}`).reply(
      200,
      ok({
        id: RAW_SN,
        cctvName: '테스트 CCTV',
        vmsClipId: 'clip-1',
        frameCount: 1,
        status: 'COMPLETED',
        capturedAt: '2026-01-01T00:00:00',
        duration: 10,
        fileSizeMb: 1,
        resolution: '1920x1080',
        framePreviews: [],
      }),
    );
    mock.onGet(`/videos/${RAW_SN}/issues`).reply(200, ok([]));
    // 호출 횟수를 세는 것이 이 가드의 전부다 — 응답은 REVIEWER 경로가 성립하는지만 확인한다.
    mock.onGet('/manage/configs').reply(() => {
      configCalls += 1;
      return [200, ok([{ configKey: 'YOLO_CONF_THRESHOLD', configVl: '40' }])];
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  // 테스트용 더미 문자열 — 실제 시크릿이 아니고 서명 검증도 없다(요청은 전부 목이다).
  const FAKE_TOKEN = ['test', 'only', 'value'].join('-');

  function login(role: 'WORKER' | 'REVIEWER') {
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role, channel: 'INTERNAL', exp: 9999999999 },
    });
  }

  it('WORKER_진입시_REVIEWER전용_시스템설정을_호출하지_않는다', async () => {
    // given: WORKER 세션
    login('WORKER');

    // when: 라벨링 화면 진입 (프레임 목록까지 실제로 로드되는 지점까지 기다린다)
    renderPage();
    await screen.findByRole('option', { name: '프레임 0' });

    // then: 구 동작에서는 여기서 403 이 2건 났다 — 이제 요청 자체가 0건이어야 한다.
    expect(configCalls).toBe(0);
  });

  it('REVIEWER_진입시에는_그대로_호출한다_게이팅이_전면차단이_아니다', async () => {
    // given: REVIEWER 세션
    login('REVIEWER');

    // when
    renderPage();
    await screen.findByRole('option', { name: '프레임 0' });

    // then: 역할 게이팅이 기능을 통째로 죽인 것이 아님을 함께 고정한다
    //       (0 으로 굳어지면 REVIEWER 의 슬라이더 프리필이 조용히 사라진다).
    expect(configCalls).toBeGreaterThan(0);
  });
});
