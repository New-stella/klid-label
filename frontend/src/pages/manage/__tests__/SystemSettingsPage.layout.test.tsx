// 사양 SCREEN-025 — 실시간 모니터링(헬스) 섹션을 본문 흐름에서 빼내 **고정(sticky) 사이드바**로
// 재배치한 것의 회귀 가드.
//
// 배치만 바뀐다 — `HealthStatusList` 컴포넌트 자체(5초 폴링 read-only)는 그대로다.
//
// ⚠ jsdom 한계: 실제 레이아웃(2열 여부·sticky 동작)을 계산하지 못한다. 반응형·sticky 축은
//   **클래스 존재 단언에 그친다**(그 한계를 아래 테스트 이름·주석에 명시). 실제 렌더 검증은
//   브라우저 시각 회귀의 몫이다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { SystemSettingsPage } from '@/pages/manage/SystemSettingsPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/** 인가 축은 이 테스트의 대상이 아니다 — 화면이 뜨게만 하는 더미 세션. */
function setReviewer() {
  useAuthStore.setState({
    token: 'dummy-token',
    claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
}

function mockConfigs(mock: MockAdapter) {
  mock.onGet('/manage/configs').reply(200, {
    success: true,
    data: [
      { configKey: 'BATCH_INTERVAL_SEC', configVl: '60' },
      { configKey: 'BATCH_CONCURRENCY', configVl: '2' },
    ],
    message: null,
    errorCode: null,
  });
}

function mockHealth(mock: MockAdapter) {
  mock.onGet('/manage/health').reply(200, {
    status: 'UP',
    components: {
      deidentify: { status: 'UP' },
      aiServer: { status: 'UP' },
      database: { status: 'UP', details: { service: 'control-db' } },
    },
  });
}

describe('SystemSettingsPage — 헬스 사이드바 재배치 (사양 SCREEN-025)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
    mockConfigs(mock);
    mockHealth(mock);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('헬스_섹션이_사이드바_랜드마크_안에_있다', async () => {
    // given / when
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });
    await waitFor(() => expect(screen.getByText('배치 처리')).toBeInTheDocument());

    // then: <aside>(complementary) 랜드마크가 본문과 구분되고 그 안에 헬스 카드가 있다.
    const sidebar = screen.getByRole('complementary');
    expect(within(sidebar).getByText('외부 연동 상태')).toBeInTheDocument();
    // 섹션 제목은 유지된다 — 없으면 그 영역이 무엇인지 알 수 없다.
    expect(
      within(sidebar).getByRole('heading', { name: '실시간 모니터링' }),
    ).toBeInTheDocument();
    // 랜드마크에는 접근 가능한 이름이 있어야 여러 랜드마크 중 무엇인지 식별된다.
    expect(sidebar).toHaveAccessibleName('실시간 모니터링');
  });

  it('편집_가능_카드와_위험_액션은_본문에_남는다', async () => {
    // given / when
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });
    await waitFor(() => expect(screen.getByText('배치 처리')).toBeInTheDocument());

    // then: 사이드바로 옮겨간 것은 헬스뿐이다.
    const sidebar = screen.getByRole('complementary');
    expect(within(sidebar).queryByText('배치 처리')).not.toBeInTheDocument();
    expect(within(sidebar).queryByText('위험 액션')).not.toBeInTheDocument();

    expect(screen.getByRole('heading', { name: '편집 가능 — DB 영속화' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '위험 액션' })).toBeInTheDocument();
  });

  it('사이드바는_스크롤을_따라오도록_고정된다_클래스_단언_한계', async () => {
    // given / when
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });
    await waitFor(() => expect(screen.getByText('배치 처리')).toBeInTheDocument());

    // then: jsdom 은 position:sticky 를 계산하지 못하므로 클래스 적용만 확인한다.
    //       ⚠ 좁은 화면에서는 한 열로 쌓이므로 sticky 는 md 이상에서만 켜진다.
    const sidebar = screen.getByRole('complementary');
    expect(sidebar.className).toMatch(/\bmd:sticky\b/);
  });

  it('좁은_화면에서는_한_열로_쌓인다_클래스_단언_한계', async () => {
    // given / when
    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });
    await waitFor(() => expect(screen.getByText('배치 처리')).toBeInTheDocument());

    // then: 2열 그리드는 md 이상에서만 켜지고 기본은 1열이다.
    //       (이 저장소 Tailwind screens 는 md/xl 두 개뿐 — sm:/lg: 는 죽은 접두사다)
    const layout = screen.getByTestId('system-settings-layout');
    expect(layout.className).toMatch(/\bgrid-cols-1\b/);
    expect(layout.className).toMatch(/\bmd:grid-cols-/);
  });
});
