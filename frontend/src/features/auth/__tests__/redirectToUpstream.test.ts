import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { redirectToUpstream } from '../redirectToUpstream';

describe('redirectToUpstream (Open Redirect 방어)', () => {
  let assignSpy: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    assignSpy = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: {
        ...originalLocation,
        href: 'http://app.local/video/completed',
        assign: assignSpy,
      },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    vi.unstubAllEnvs();
  });

  it('토큰_없을_때_INTERNAL_채널_상위_시스템_redirect', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');

    redirectToUpstream('INTERNAL');

    expect(assignSpy).toHaveBeenCalledTimes(1);
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('http://control.local/login')).toBe(true);
    expect(target).toContain('next=');
  });

  it('토큰_없을_때_PORTAL_채널_포털_로그인_redirect', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');

    redirectToUpstream('PORTAL');

    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('http://portal.local/login')).toBe(true);
  });

  it('next_파라미터는_현재_URL_encode되어_보존', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    redirectToUpstream('INTERNAL');

    const target = assignSpy.mock.calls[0][0] as string;
    const expectedNext = encodeURIComponent('http://app.local/video/completed');
    expect(target).toContain(`next=${expectedNext}`);
  });

  it('환경변수_없으면_redirect_안함_안전_가드', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', '');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', '');
    redirectToUpstream('INTERNAL');
    expect(assignSpy).not.toHaveBeenCalled();
  });

  it('채널_없으면_INTERNAL_기본값_사용', () => {
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    redirectToUpstream();
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('http://control.local/login')).toBe(true);
  });
});
