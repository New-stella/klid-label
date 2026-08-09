// 회귀 가드 — 라벨링 진입 시 AI 정밀도 기본값을 **역할과 무관하게** 전용 경로로 조회한다.
//
// 경위(구 동작 → 현 동작):
//   ① 최초 결함: WORKER 진입마다 `GET /v1/manage/configs` 가 나가 **403 이 2건** 쌓였다.
//      그 엔드포인트는 SecurityConfig 의 `/v1/manage/**` 매처 + 컨트롤러 `@PreAuthorize` 로
//      검수자 전용이다.
//   ② 구 처방: 호출을 `enabled: isReviewer` 로 막았다. 403 은 사라졌지만 **작업자는 저장된
//      기본값을 아예 받지 못했다** — 문제를 옮긴 것이지 푼 것이 아니었다.
//   ③ 현 처방: 검수자·작업자 공통 읽기 전용 경로 `GET /v1/ai-defaults`(값 두 개만, 운영 메타 없음)를
//      신설해 조건 없이 호출한다. 관리 영역 조회는 시스템 설정 화면 전용으로 남는다.
//
// 그래서 이 가드는 두 축을 함께 센다 — **관리 영역 호출 0건** + **전용 경로 호출 1건 이상**.
// 한쪽만 세면 "작업자 403 은 없는데 값도 못 받는" 구 처방으로 조용히 되돌아간다.
//
// 값의 성격: 이 값은 AI 도구 슬라이더의 **초기값 프리필**일 뿐이고 미조회 시 컴포넌트 코드
// 상수로 폴백하도록 설계돼 있어, 조회가 실패해도 AI 도구 동작 자체는 달라지지 않는다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

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

describe('라벨링 진입 시 AI 정밀도 기본값 조회 경로', () => {
  let mock: MockAdapter;
  let manageConfigCalls: number;
  let aiDefaultsCalls: number;

  beforeEach(() => {
    manageConfigCalls = 0;
    aiDefaultsCalls = 0;
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
    // 호출 횟수를 세는 것이 이 가드의 전부다.
    mock.onGet('/manage/configs').reply(() => {
      manageConfigCalls += 1;
      return [200, ok([{ configKey: 'YOLO_CONF_THRESHOLD', configVl: '40' }])];
    });
    mock.onGet('/ai-defaults').reply(() => {
      aiDefaultsCalls += 1;
      return [200, ok({ confThreshold: 25, simplifyTolerance: 1.0 })];
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

  it('WORKER_진입시_검수자전용_관리설정을_호출하지_않고_전용경로로_기본값을_받는다', async () => {
    // given: WORKER 세션
    login('WORKER');

    // when: 라벨링 화면 진입 (프레임 목록까지 실제로 로드되는 지점까지 기다린다)
    renderPage();
    await screen.findByRole('option', { name: '프레임 0' });
    await vi.waitFor(() => expect(aiDefaultsCalls).toBeGreaterThan(0));

    // then: 구 동작에서 403 이 나던 관리 영역 호출은 0건이어야 한다.
    expect(manageConfigCalls).toBe(0);
  });

  it('REVIEWER_진입시에도_같은_전용경로를_쓴다_관리설정_조회로_되돌아가지_않는다', async () => {
    // given: REVIEWER 세션
    login('REVIEWER');

    // when
    renderPage();
    await screen.findByRole('option', { name: '프레임 0' });
    await vi.waitFor(() => expect(aiDefaultsCalls).toBeGreaterThan(0));

    // then: 검수자라고 해서 설정 전량 + 마지막 수정자를 담은 응답을 받을 이유가 없다.
    expect(manageConfigCalls).toBe(0);
  });
});
