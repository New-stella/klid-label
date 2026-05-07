import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';


import { useAuthStore } from '@/stores/useAuthStore';

import { apiClient, redirectToUpstreamLogin } from '../client';
import { ApiError } from '../errors';


describe('apiClient interceptors', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.getState().clear();
    vi.clearAllMocks();
  });

  afterEach(() => {
    mock.restore();
  });

  it('axios_interceptor_Bearer_토큰_자동_주입', async () => {
    useAuthStore.setState({ token: 'tok-abc', claims: null });

    let receivedHeader: string | undefined;
    mock.onGet('/echo').reply((cfg) => {
      receivedHeader = cfg.headers?.Authorization as string | undefined;
      return [200, { success: true, data: { ok: true }, message: null, errorCode: null }];
    });

    const res = await apiClient.get('/echo');
    expect(receivedHeader).toBe('Bearer tok-abc');
    expect(res.data).toEqual({ ok: true });
  });

  it('ApiResponse_data_필드_unwrap_성공', async () => {
    mock.onGet('/items').reply(200, {
      success: true,
      data: { count: 3 },
      message: null,
      errorCode: null,
    });

    const res = await apiClient.get('/items');
    expect(res.data).toEqual({ count: 3 });
  });

  it('ApiResponse_success_false면_ApiError로_변환', async () => {
    mock.onGet('/bad').reply(200, {
      success: false,
      data: null,
      message: '입력값이 유효하지 않습니다.',
      errorCode: 'INVALID_INPUT',
    });

    await expect(apiClient.get('/bad')).rejects.toBeInstanceOf(ApiError);
    try {
      await apiClient.get('/bad');
    } catch (e) {
      const err = e as ApiError;
      expect(err.errorCode).toBe('INVALID_INPUT');
      expect(err.userMessage).toBe('입력값이 유효하지 않습니다.');
    }
  });

  it('403_응답_시_ApiError_errorCode_보존', async () => {
    mock.onGet('/forbidden').reply(403, {
      success: false,
      data: null,
      message: '권한이 없습니다.',
      errorCode: 'FORBIDDEN',
    });

    try {
      await apiClient.get('/forbidden');
      throw new Error('should have thrown');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const err = e as ApiError;
      expect(err.status).toBe(403);
      expect(err.errorCode).toBe('FORBIDDEN');
    }
  });

  it('401_응답시_redirectToUpstreamLogin_호출', async () => {
    const assignSpy = vi.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/secure', assign: assignSpy },
    });
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');

    useAuthStore.setState({ token: 'expired', claims: null });
    mock.onGet('/secure').reply(401, {
      success: false,
      data: null,
      message: '인증이 필요합니다.',
      errorCode: 'UNAUTHORIZED',
    });

    await expect(apiClient.get('/secure')).rejects.toBeInstanceOf(ApiError);
    expect(useAuthStore.getState().token).toBeNull();
    // Phase 1: redirectToUpstream으로 통합 — ?next= 파라미터로 현재 URL 보존
    expect(assignSpy).toHaveBeenCalledTimes(1);
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('http://control.local/login')).toBe(true);
    expect(target).toContain('next=');

    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    vi.unstubAllEnvs();
  });

  it('redirectToUpstreamLogin_PORTAL_채널이면_포털_URL_사용', () => {
    const assignSpy = vi.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/portal/me', assign: assignSpy },
    });
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');

    useAuthStore.setState({
      token: 'x',
      claims: { sub: 'u1', role: 'PORTAL_USER', channel: 'PORTAL', exp: 1 },
    });

    redirectToUpstreamLogin();
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('http://portal.local/login')).toBe(true);
    expect(target).toContain('next=');

    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    vi.unstubAllEnvs();
  });
});
