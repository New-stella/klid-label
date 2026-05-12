import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useAuthStore } from '@/stores/useAuthStore';

import { DevLoginPage } from '../DevLoginPage';
import { LOCAL_STORAGE_TOKEN_KEY } from '../tokenIngress';

// helper: base64url 인코딩으로 가짜 JWT 생성 (SessionIngressPage.test 패턴 재사용)
function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  const b64 = btoa(utf8);
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}
function buildJwt(
  headerObj: Record<string, unknown>,
  payloadObj: Record<string, unknown>,
): string {
  return `${b64url(headerObj)}.${b64url(payloadObj)}.signature`;
}

// /ingress 라우트는 본 테스트의 관심사가 아님 — 도달 확인용 stub.
function IngressStub() {
  return <div>INGRESS_STUB</div>;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/dev/login']}>
      <Routes>
        <Route path="/dev/login" element={<DevLoginPage />} />
        <Route path="/ingress" element={<IngressStub />} />
      </Routes>
    </MemoryRouter>,
  );
}

// TS strict 의 closure-aliased let narrowing 회피용 컨테이너.
interface BodyHolder {
  value: Record<string, unknown> | null;
}

describe('DevLoginPage', () => {
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

  it('WORKER_역할_선택_후_토큰_발급_버튼_클릭하면_BE_POST_호출_+_localStorage_저장_+_ingress_navigate', async () => {
    const user = userEvent.setup();
    const token = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: '2001', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999, name: '최라벨' },
    );

    const captured: BodyHolder = { value: null };
    mock.onPost('/dev/tokens').reply((config) => {
      captured.value = JSON.parse(config.data as string) as Record<string, unknown>;
      return [
        200,
        {
          success: true,
          data: {
            token,
            tokenType: 'Bearer',
            expiresAt: '2099-01-01T00:00:00Z',
            claims: {
              sub: '2001',
              role: 'WORKER',
              channel: 'INTERNAL',
              name: '최라벨',
              exp: 9999999999,
            },
            authorizationHeader: `Bearer ${token}`,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderPage();

    // 기본은 REVIEWER 라디오 → WORKER 로 변경
    await user.click(screen.getByLabelText(/WORKER/));
    await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

    await waitFor(() => {
      expect(screen.getByText('INGRESS_STUB')).toBeInTheDocument();
    });

    expect(localStorage.getItem(LOCAL_STORAGE_TOKEN_KEY)).toBe(token);
    expect(captured.value).not.toBeNull();
    expect(captured.value?.role).toBe('WORKER');
    expect(captured.value?.channel).toBe('INTERNAL');
  });

  it('PORTAL_USER_선택_시_channel_PORTAL_로_전송', async () => {
    const user = userEvent.setup();
    const token = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: '3001', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999, name: '홍길동' },
    );

    const captured: BodyHolder = { value: null };
    mock.onPost('/dev/tokens').reply((config) => {
      captured.value = JSON.parse(config.data as string) as Record<string, unknown>;
      return [
        200,
        {
          success: true,
          data: {
            token,
            tokenType: 'Bearer',
            expiresAt: '2099-01-01T00:00:00Z',
            claims: {
              sub: '3001',
              role: 'PORTAL_USER',
              channel: 'PORTAL',
              name: '홍길동',
              exp: 9999999999,
            },
            authorizationHeader: `Bearer ${token}`,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderPage();

    await user.click(screen.getByLabelText(/PORTAL_USER/));
    await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

    await waitFor(() => {
      expect(captured.value).not.toBeNull();
    });
    expect(captured.value?.role).toBe('PORTAL_USER');
    expect(captured.value?.channel).toBe('PORTAL');
  });

  it('BE_400_응답_시_에러_메시지_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/dev/tokens').reply(400, {
      success: false,
      data: null,
      message: 'role-channel 불일치',
      errorCode: 'INVALID_INPUT',
    });

    renderPage();

    await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/role-channel 불일치/);
    });
    // ingress 로 이동하지 않아야 한다
    expect(screen.queryByText('INGRESS_STUB')).not.toBeInTheDocument();
    expect(localStorage.getItem(LOCAL_STORAGE_TOKEN_KEY)).toBeNull();
  });

  it('userNo_직접_입력_시_요청_body_에_포함', async () => {
    const user = userEvent.setup();
    const token = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: '9999', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999, name: '김검수' },
    );

    const captured: BodyHolder = { value: null };
    mock.onPost('/dev/tokens').reply((config) => {
      captured.value = JSON.parse(config.data as string) as Record<string, unknown>;
      return [
        200,
        {
          success: true,
          data: {
            token,
            tokenType: 'Bearer',
            expiresAt: '2099-01-01T00:00:00Z',
            claims: {
              sub: '9999',
              role: 'REVIEWER',
              channel: 'INTERNAL',
              name: '김검수',
              exp: 9999999999,
            },
            authorizationHeader: `Bearer ${token}`,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderPage();

    const userNoInput = screen.getByLabelText(/userNo/i);
    await user.clear(userNoInput);
    await user.type(userNoInput, '9999');
    await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

    await waitFor(() => {
      expect(captured.value).not.toBeNull();
    });
    expect(captured.value?.userNo).toBe('9999');
  });
});
