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
        href: 'http://app.local/video/status',
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
    const expectedNext = encodeURIComponent('http://app.local/video/status');
    expect(target).toContain(`next=${expectedNext}`);
  });

  it('next파라미터에서_token쿼리가_제거된다', () => {
    // given: `/ingress?token=<JWT>` 로 진입한 뒤 만료·claims 실패로 redirect 되는 상황
    window.location.href = 'http://app.local/ingress?token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.sig';
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');

    // when
    redirectToUpstream('INTERNAL');

    // then: JWT 가 상위 서버 access log / 브라우저 히스토리에 실려 나가면 안 된다 (CWE-598/200)
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target).not.toContain('eyJhbGciOiJIUzI1NiJ9');
    const next = decodeURIComponent(new URL(target).searchParams.get('next') ?? '');
    expect(new URL(next).searchParams.has('token')).toBe(false);
  });

  it('next파라미터에_다른_쿼리파라미터는_보존된다', () => {
    // given: 토큰 외 일반 쿼리(복귀 후 화면 복원에 필요)
    window.location.href = 'http://app.local/video?page=2&token=aaa.bbb.ccc&keyword=abc';
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');

    // when
    redirectToUpstream('INTERNAL');

    // then: 과잉 제거 방지 — token 만 빠지고 나머지는 그대로
    const target = assignSpy.mock.calls[0][0] as string;
    const next = new URL(decodeURIComponent(new URL(target).searchParams.get('next') ?? ''));
    expect(next.pathname).toBe('/video');
    expect(next.searchParams.get('page')).toBe('2');
    expect(next.searchParams.get('keyword')).toBe('abc');
    expect(next.searchParams.has('token')).toBe(false);
  });

  it('token쿼리가_없는_URL은_기존과_동일하게_동작한다', () => {
    // given: 토큰이 URL 에 없는 일반 경로 (회귀 가드)
    window.location.href = 'http://app.local/video/status?page=1';
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');

    // when
    redirectToUpstream('INTERNAL');

    // then: URL 이 손실 없이 그대로 보존된다
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target).toContain(`next=${encodeURIComponent('http://app.local/video/status?page=1')}`);
  });

  it('토큰_별칭_쿼리도_제거된다', () => {
    // given: 상위 시스템이 다른 이름으로 토큰을 실어 보낸 경우 (방어적 제거)
    window.location.href = 'http://app.local/ingress?access_token=aaa.bbb.ccc&jwt=ddd.eee.fff';
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');

    // when
    redirectToUpstream('INTERNAL');

    // then
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target).not.toContain('aaa.bbb.ccc');
    expect(target).not.toContain('ddd.eee.fff');
  });

  it('URL_파싱_실패시_next를_생략하고_redirect한다', () => {
    // given: href 를 신뢰할 수 없는 상황 (파싱 불가)
    window.location.href = '';
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');

    // when
    redirectToUpstream('INTERNAL');

    // then: 토큰 포함 여부를 보장할 수 없으므로 next 를 붙이지 않는다 (fail-closed).
    //       단 redirect 자체는 막지 않는다 — 막으면 막다른 화면이 된다.
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target).toBe('http://control.local/login');
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
