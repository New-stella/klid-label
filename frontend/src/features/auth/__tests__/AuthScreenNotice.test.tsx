// 인증 3화면(SCREEN-001 · SCREEN-002 · SCREEN-004)의 「보이는 안내」 회귀 가드.
//
// 막는 결함 둘 —
//  ① 오류 안내가 「아이콘 + 제목 + 본문」 구조 없이 <p role="alert"> 한 줄이던 것.
//     세 화면이 각자 때우고 있어 같은 사실을 서로 다른 모양으로 말했다.
//  ② 세션 인계 화면에 <b>보이는 글자가 하나도 없던 것</b> — 안내가 Spinner 의 label prop 이었고
//     그 값은 sr-only 로만 렌더돼, 눈으로 보는 사용자에게는 회전하는 원만 있었다.
//
// ★②의 가드는 반드시 「sr-only 안에 있지 않다」를 직접 단언한다. jsdom 은 클래스 기반 숨김을
// 계산하지 못해 toBeVisible()·getByText 만으로는 sr-only 를 그대로 통과시키기 때문이다 —
// 그 헐거움이 곧 이 결함이었다.
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { RoleClaimPage } from '@/pages/RoleClaimPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

// 인증 실패를 화면에 남기려면 /dev/login 우회가 꺼져 있어야 한다(켜져 있으면 navigate 로 빠진다).
vi.mock('@/lib/devLogin', () => ({ isDevLoginEnabled: () => false }));

import { DevLoginPage } from '../DevLoginPage';
import { SessionIngressPage } from '../SessionIngressPage';

function b64url(obj: Record<string, unknown>): string {
  return btoa(unescape(encodeURIComponent(JSON.stringify(obj))))
    .replace(/=+$/, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}
function buildJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`;
}

/** 그 문구가 스크린리더 전용 영역이 아니라 화면에 실제로 그려지는지. */
function expectVisibleText(text: string): HTMLElement {
  const el = screen.getByText(text);
  expect(el.closest('.sr-only')).toBeNull();
  return el;
}

/** 안내 배너 = 제목 + 본문 + 장식 아이콘 한 벌. */
function expectAlert(title: string, description: string): void {
  const alert = screen.getByRole('alert');
  expect(alert).toHaveTextContent(title);
  expect(alert).toHaveTextContent(description);
  // 시안 .alert 의 아이콘 — 의미는 제목이 지므로 접근성 트리에서는 빠져 있어야 한다.
  expect(alert.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
}

function renderIngress(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries}>
      <Routes>
        <Route path="/ingress" element={<SessionIngressPage />} />
        <Route path="/dashboard" element={<div>DASHBOARD_HOME</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('SessionIngressPage — 보이는 안내(SCREEN-001)', () => {
  let originalLocation: Location;

  beforeEach(() => {
    useAuthStore.getState().clear();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/ingress', assign: vi.fn() },
    });
    vi.stubEnv('VITE_TOKEN_INGRESS', 'url');
    // 상위 시스템 로그인 주소가 없어야 redirect 대신 안내가 화면에 남는다.
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', '');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', '');
    vi.stubEnv('VITE_DEV_TOKEN', '');
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    vi.unstubAllEnvs();
  });

  it('세션_확인_중_문구가_sr_only_가_아니라_화면에_보인다', () => {
    // given: 상위 시스템으로 redirect 되는 경로 — 이동을 기다리는 동안 화면에 남는 상태가 이것이다.
    // (유효 토큰이면 곧바로 navigate 해 버려 이 상태를 관측할 수 없다.)
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    renderIngress(['/ingress']);

    // then: 구 구현은 이 두 줄이 아예 없거나(설명) sr-only 안에만(제목) 있었다.
    expectVisibleText('세션을 확인하는 중');
    expectVisibleText(
      '관제서버 또는 포털에서 전달한 인증 정보를 확인하고 있습니다. 확인이 끝나면 자동으로 이동합니다.',
    );
  });

  it('인증_정보가_없으면_제목과_본문이_나뉜_안내가_뜬다', async () => {
    renderIngress(['/ingress']);

    await waitFor(() => {
      expectAlert('로그인 서버에 연결할 수 없습니다', '관제서버 또는 포털에서 다시 접근해주세요.');
    });
  });

  it('세션이_만료되면_만료_제목으로_구분된다', async () => {
    renderIngress([`/ingress?token=${buildJwt({ sub: 'u1', role: 'REVIEWER', channel: 'INTERNAL', exp: 1 })}`]);

    await waitFor(() => {
      expectAlert('세션이 만료되었습니다', '관제서버 또는 포털에서 다시 접근해주세요.');
    });
  });
});

describe('RoleClaimPage — 오류 안내(SCREEN-002)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: {
        sub: '1001',
        role: undefined as unknown as 'WORKER',
        channel: 'INTERNAL',
        exp: Math.floor(Date.now() / 1000) + 3600,
      },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('서버가_내려준_메시지는_제목_아래_본문으로_그대로_보인다', async () => {
    // given: 상태코드로 분류할 수 없는 사유 — 서버만 원인을 안다.
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(400, {
      success: false,
      data: null,
      message: 'PORTAL_USER 역할은 본 API 로 부여할 수 없습니다.',
      errorCode: 'INVALID_INPUT',
    });

    renderWithProviders(<RoleClaimPage />);
    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'pw-for-test');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    // then: 제목이 분류를 말하되 서버 원인 문구를 덮어쓰지 않는다.
    await waitFor(() => {
      expectAlert('권한 부여에 실패했습니다', 'PORTAL_USER 역할은 본 API 로 부여할 수 없습니다.');
    });
  });
});

describe('DevLoginPage — 오류 안내(SCREEN-004)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    useAuthStore.getState().clear();
    localStorage.clear();
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    localStorage.clear();
  });

  it('발급_실패_시_시안_제목과_서버_원인이_함께_보인다', async () => {
    const user = userEvent.setup();
    mock.onPost('/dev/tokens').reply(400, {
      success: false,
      data: null,
      message: 'role-channel 불일치',
      errorCode: 'INVALID_INPUT',
    });

    render(
      <MemoryRouter initialEntries={['/dev/login']}>
        <Routes>
          <Route path="/dev/login" element={<DevLoginPage />} />
          <Route path="/ingress" element={<div>INGRESS_STUB</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

    await waitFor(() => {
      expectAlert('토큰을 발급하지 못했습니다', 'role-channel 불일치');
    });
  });
});
