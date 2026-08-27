// 회귀 가드 — 연동 서버 주소 관리 화면. [@design SCREEN-042] [@design API-069]
//
// ★조회와 저장의 요건이 다르다 — 현재 값을 보는 것은 검수자 권한만으로 되고, 저장에만 관리자
//   유효창이 가산된다. 그래서 유효창이 없어도 **값은 보이고 저장만 막힌다**.
//
// ⚠ 카드 자체의 동작(변경분만 전송·400/403 안내·대역 차단 폐지 등)은 `IntegrationEndpointsCard`
//   테스트가 갖는다. 여기서 보는 것은 «이 화면이 그 카드를 제자리에 놓았는가» 와 «옮겨온 화면이
//   위험 액션까지 끌고 오지는 않았는가» 두 가지다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { AdminEndpointsPage } from '@/pages/admin/AdminEndpointsPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const CONFIGS = [
  { configKey: 'kpst.deid.base-url', configVl: 'https://deid.invalid', configTypeCd: 'STRING' },
  { configKey: 'vlm.client.url', configVl: '', configTypeCd: 'STRING' },
];

describe('AdminEndpointsPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/configs').reply(200, {
      success: true,
      data: CONFIGS,
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: '1001', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useAdminSessionStore.getState().clear();
  });

  it('연동_대상_네_종과_대상_밖_안내가_함께_보인다', async () => {
    renderWithProviders(<AdminEndpointsPage />, { initialEntries: ['/admin/endpoints'] });

    await waitFor(() => expect(screen.getByLabelText('비식별 서버')).toBeInTheDocument());
    expect(screen.getByLabelText('AI 추론 서버')).toBeInTheDocument();
    expect(screen.getByLabelText('외부 시계열 분석 벤더')).toBeInTheDocument();
    expect(screen.getByLabelText('관제 통지 수신처')).toBeInTheDocument();
    // 여기서 찾다가 없다고 판단하는 일이 없도록 화면이 대상 밖임을 밝힌다.
    expect(
      screen.getByText('데이터베이스 접속정보는 이 화면에서 다루지 않습니다.'),
    ).toBeInTheDocument();
  });

  it('★유효창이_없어도_저장된_값은_보이고_저장만_막힌다', async () => {
    useAdminSessionStore.getState().clear();
    renderWithProviders(<AdminEndpointsPage />, { initialEntries: ['/admin/endpoints'] });

    await waitFor(() => expect(screen.getByLabelText('비식별 서버')).toBeInTheDocument());
    // 조회는 검수자 권한만으로 된다 — 값이 가려지지 않는다.
    expect(screen.getByLabelText('비식별 서버')).toHaveValue('https://deid.invalid');
    // 저장만 잠긴다.
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '관리자 확인' })).toBeInTheDocument();
  });

  it('진입_게이트를_통과한_유효창을_이_화면이_그대로_이어받는다', async () => {
    // 화면마다 다시 물으면 관리자 페이지로 묶은 의미가 없다.
    useAdminSessionStore.getState().open({
      token: 'dummy-window',
      expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
    });
    renderWithProviders(<AdminEndpointsPage />, { initialEntries: ['/admin/endpoints'] });

    await waitFor(() => expect(screen.getByLabelText('비식별 서버')).toBeInTheDocument());
    expect(screen.getByTestId('admin-session-remaining')).toHaveTextContent(/수정 가능 \d+:\d{2}/);
    expect(screen.getByLabelText('비식별 서버')).not.toHaveAttribute('readonly');
  });

  it('위험_액션은_이_화면으로_따라오지_않았다', async () => {
    // 옮겨온 것은 연동 주소 카드 하나다 — 시스템 설정 화면의 다른 영역을 함께 끌고 오지 않는다.
    renderWithProviders(<AdminEndpointsPage />, { initialEntries: ['/admin/endpoints'] });

    await waitFor(() => expect(screen.getByLabelText('비식별 서버')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /배치 큐 초기화/ })).toBeNull();
    expect(screen.queryByText('위험 구역')).toBeNull();
  });
});
