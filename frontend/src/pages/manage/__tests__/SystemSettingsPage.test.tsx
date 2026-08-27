import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { SystemSettingsPage } from '@/pages/manage/SystemSettingsPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function setReviewer() {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
}

// BE ConfigResponse 실제 형태(configKey/configVl 문자열) 로 mock.
// concurrency 는 저장값 2 를 모사 — 재로드 시 슬라이더가 2 로 표시되어야 한다(R3-2).
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

// components 키는 BE ManageHealthController 가 실제로 넣는 3종 그대로다
// (구 픽스처는 db/controlServer 로 서버가 보내지 않는 응답을 흉내냈다).
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

describe('SystemSettingsPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('2섹션_렌더링_(편집_가능_+_실시간_모니터링)', async () => {
    mockConfigs(mock);
    mockHealth(mock);

    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    // 카드 제목: '배치 처리' / '외부 연동 상태'
    // 섹션 헤더는 별도로 '편집 가능 — DB 영속화' / '실시간 모니터링'
    await waitFor(() => {
      expect(screen.getByText('배치 처리')).toBeInTheDocument();
    });
    // '실시간 모니터링' 은 섹션 헤더(h2) 와 배지 양쪽에 등장 — getAllBy 로 처리
    expect(screen.getAllByText('실시간 모니터링').length).toBeGreaterThan(0);
    // ⚠ 위험 액션 섹션은 관리자 페이지(`/admin/maintenance`)로 옮겨갔다.
    expect(screen.queryByText('위험 액션')).not.toBeInTheDocument();
  });

  it('R3-2_저장된_설정값이_슬라이더에_반영됨_(재로드_시_기본값_폴백_금지)', async () => {
    mockConfigs(mock); // BATCH_CONCURRENCY 저장값 = 2
    mockHealth(mock);

    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });

    // 동시 처리 수 슬라이더 값 표시가 저장값 2 여야 한다 (기본값 1 폴백이면 버그).
    const concurrencySlider = await screen.findByLabelText(/동시 처리 수/);
    await waitFor(() => {
      expect(concurrencySlider).toHaveValue('2');
    });
    // 처리 주기도 저장값 60 으로 표시
    const intervalSlider = screen.getByLabelText(/처리 주기/);
    expect(intervalSlider).toHaveValue('60');
  });

  it('위험_액션과_연동_주소_카드가_이_화면에서_사라졌다', async () => {
    // 옮긴 것이지 복제한 것이 아니다 — 두 화면에 다 뜨면 어느 쪽이 정본인지 알 수 없다.
    // 실행 동작 자체의 회귀 가드는 옮겨간 화면(`AdminMaintenancePage`)이 갖는다.
    mockConfigs(mock);
    mockHealth(mock);

    renderWithProviders(<SystemSettingsPage />, { initialEntries: ['/manage/settings'] });
    await waitFor(() => expect(screen.getByText('배치 처리')).toBeInTheDocument());

    expect(screen.queryByRole('button', { name: /배치 큐 초기화/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /캐시 삭제/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('비식별 서버')).not.toBeInTheDocument();

    // 존치 축 — 남아야 할 편집 카드는 그대로다(전부 사라지는 변이를 함께 막는다).
    expect(screen.getByLabelText(/동시 처리 수/)).toBeInTheDocument();
  });
});