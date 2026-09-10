import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useBeforeUnloadWarning, useUnsavedWorkStore } from '@/lib/unsavedWork';
import { useAuthStore } from '@/stores/useAuthStore';

import { ControlSessionMonitor, EXTEND_UNAVAILABLE_MESSAGE } from '../ControlSessionMonitor';
import { CONTROL_SESSION_PATH, CONTROL_TOKENS_PATH, controlSessionHttp } from '../controlSession';
import { SESSION_EXPIRY_DIALOG_LABEL, UNSAVED_WORK_WARNING } from '../SessionExpiryDialog';

import {
  fireStorage,
  makeAccessJwt,
  makeRefreshJwt,
  putControlSession,
  referenceControlTokenInfoJson,
  signInTab,
  stubUpstreamRedirect,
} from './controlSessionFixture';

/**
 * [@design SHELL-001] [@design AC-1105] [@design AC-1106] [@design ADR-012]
 * 관제 채널 세션 만료 감시 + 연장 팝업 — 표시 · 닫힘 수단 · 결말 · 적용 범위 회귀 가드.
 *
 * 픽스처: 임계 5분(`sessionExpAlarm=5`) · 시작 시 남은 시간 `lifetimeSec`.
 */
function BeforeUnloadProbe() {
  useBeforeUnloadWarning(true);
  return null;
}

function fireBeforeUnload(): boolean {
  const ev = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(ev);
  return ev.defaultPrevented;
}

describe('ControlSessionMonitor — 관제 채널 세션 연장 팝업', () => {
  let relay: MockAdapter;
  let redirect: ReturnType<typeof stubUpstreamRedirect>;

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    // 초 경계에 고정한다 — 토큰의 `iat` 은 초 단위로 내림되므로 시계가 초 중간이면 남은 시간이
    // 1초 모자라게 보인다.
    vi.setSystemTime(new Date('2026-09-10T10:00:00.000Z'));
    relay = new MockAdapter(controlSessionHttp);
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
    redirect = stubUpstreamRedirect('/label/1');
  });

  afterEach(() => {
    relay.restore();
    redirect.restore();
    vi.useRealTimers();
    vi.unstubAllEnvs();
    useAuthStore.getState().clear();
  });

  function controlTab(lifetimeSec: number) {
    const session = makeAccessJwt(lifetimeSec);
    putControlSession(session, makeRefreshJwt());
    signInTab(session);
    return session;
  }

  function dialog() {
    return screen.queryByRole('dialog', { name: SESSION_EXPIRY_DIALOG_LABEL });
  }

  function tick(ms = 1000) {
    act(() => {
      vi.advanceTimersByTime(ms);
    });
  }

  it('★임계에_닿으면_남은_시간과_함께_뜨고_버튼은_로그아웃·로그인_연장_둘뿐이다', () => {
    controlTab(4 * 60 + 30);
    render(<ControlSessionMonitor />);

    const d = dialog();
    expect(d).not.toBeNull();
    expect(within(d as HTMLElement).getByTestId('session-expiry-remaining')).toHaveTextContent(
      '4분 30초',
    );
    const names = within(d as HTMLElement)
      .getAllByRole('button')
      .map((b) => b.textContent);
    expect(names).toEqual(['로그아웃', '로그인 연장']);
    expect(within(d as HTMLElement).queryByRole('button', { name: '닫기' })).toBeNull();
    expect(d).toHaveTextContent('로그인 후 30분이 경과하면 자동으로 로그아웃됩니다.');

    tick();
    expect(within(dialog() as HTMLElement).getByTestId('session-expiry-remaining')).toHaveTextContent(
      '4분 29초',
    );
  });

  it('임계_전에는_뜨지_않는다', () => {
    controlTab(6 * 60);
    render(<ControlSessionMonitor />);
    expect(dialog()).toBeNull();
    tick(61 * 1000);
    expect(dialog()).not.toBeNull();
  });

  it('★ESC·배경_클릭으로_닫히지_않는다', () => {
    controlTab(4 * 60);
    render(<ControlSessionMonitor />);

    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.click(screen.getByTestId('modal-backdrop'));

    expect(dialog()).not.toBeNull();
  });

  it('★「로그인_연장」은_갱신하고_관제_형식으로_저장한_뒤_닫히며_새_수명에서_임계_전엔_다시_뜨지_않는다', async () => {
    controlTab(4 * 60);
    const renewed = makeAccessJwt(30 * 60);
    const renewedRefresh = makeRefreshJwt();
    relay.onPost(CONTROL_TOKENS_PATH).reply(201, {
      success: true,
      data: { sessionToken: renewed, refreshToken: renewedRefresh },
      message: null,
      errorCode: null,
    });
    render(<ControlSessionMonitor />);

    fireEvent.click(screen.getByRole('button', { name: '로그인 연장' }));

    await waitFor(() => expect(dialog()).toBeNull());
    expect(localStorage.getItem('klid-jwt-token')).toBe(renewed);
    expect(localStorage.getItem('tokenInfo')).toBe(
      referenceControlTokenInfoJson(renewed, renewedRefresh),
    );
    tick(5 * 1000);
    expect(dialog()).toBeNull();
  });

  it('★팝업이_열리면_초점은_「로그인_연장」에_있다', () => {
    controlTab(4 * 60);
    render(<ControlSessionMonitor />);

    // 사용자가 부르지 않은 팝업이다 — 입력 중이던 Space·Enter 가 로그아웃이 되면 안 된다.
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '로그인 연장' }));
    expect(document.activeElement).not.toBe(screen.getByRole('button', { name: '로그아웃' }));
  });

  it('버튼_배치와_순서는_그대로다_초점만_옮긴다', () => {
    controlTab(4 * 60);
    render(<ControlSessionMonitor />);

    // DOM 순서는 관제 팝업과 같게 「로그아웃」이 먼저다(초점 이동이 순서를 바꾸지 않았다).
    expect(
      within(dialog() as HTMLElement)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['로그아웃', '로그인 연장']);
  });

  it('★연장이_거절되면_즉시_로그아웃_이동하고_공유_저장소의_토큰_키도_지운다', async () => {
    controlTab(4 * 60);
    relay.onPost(CONTROL_TOKENS_PATH).reply(401, {
      success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_REJECTED',
    });
    render(<ControlSessionMonitor />);

    fireEvent.click(screen.getByRole('button', { name: '로그인 연장' }));

    await waitFor(() => expect(redirect.assign).toHaveBeenCalledTimes(1));
    expect(useAuthStore.getState().token).toBeNull();
    // 관제 웹과 같은 결말 — 같은 출처의 관제 탭도 함께 로그아웃된다.
    expect(localStorage.getItem('klid-jwt-token')).toBeNull();
    expect(localStorage.getItem('tokenInfo')).toBeNull();
  });

  it('★연장이_일시_장애면_오류를_안내하고_팝업을_유지하며_저장소에_쓰지_않는다', async () => {
    const session = controlTab(4 * 60);
    const before = localStorage.getItem('tokenInfo');
    relay.onPost(CONTROL_TOKENS_PATH).reply(503, {
      success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_UNAVAILABLE',
    });
    render(<ControlSessionMonitor />);

    fireEvent.click(screen.getByRole('button', { name: '로그인 연장' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(EXTEND_UNAVAILABLE_MESSAGE);
    expect(dialog()).not.toBeNull();
    expect(localStorage.getItem('tokenInfo')).toBe(before);
    // 일시 장애는 거절이 아니다 — 공유 저장소의 토큰 키를 지우지 않는다(관제 탭은 멀쩡할 수 있다).
    expect(localStorage.getItem('klid-jwt-token')).toBe(session);
    expect(useAuthStore.getState().token).toBe(session);
    expect(redirect.assign).not.toHaveBeenCalled();
  });

  /**
   * [@design API-247] [@design AC-1106]
   * 관제가 새 refresh 를 주지 않은 갱신 — 오탐 안내 없이 닫히고, 그 뒤로는 갱신을 부르지 않는다.
   */
  it('★새_refresh가_없는_201도_연장_성공으로_닫히고_일시_장애_안내를_하지_않는다', async () => {
    controlTab(4 * 60);
    const renewed = makeAccessJwt(30 * 60);
    relay.onPost(CONTROL_TOKENS_PATH).reply(201, {
      success: true,
      data: { sessionToken: renewed, refreshToken: null },
      message: null,
      errorCode: null,
    });
    render(<ControlSessionMonitor />);

    fireEvent.click(screen.getByRole('button', { name: '로그인 연장' }));

    await waitFor(() => expect(dialog()).toBeNull());
    expect(screen.queryByText(EXTEND_UNAVAILABLE_MESSAGE)).toBeNull();
    expect(localStorage.getItem('klid-jwt-token')).toBe(renewed);
    expect(localStorage.getItem('tokenInfo')).toBe(referenceControlTokenInfoJson(renewed));
    expect(redirect.assign).not.toHaveBeenCalled();

    // 갱신할 수단이 없어졌으므로 임계에 다시 닿아도 팝업을 띄우지 않고 창구도 부르지 않는다.
    tick(26 * 60 * 1000);
    expect(dialog()).toBeNull();
    expect(relay.history.post).toHaveLength(1);
  });

  it('★남은_시간_0이면_팝업을_닫고_이탈_경고_없이_로그아웃_이동한다', () => {
    controlTab(3);
    render(
      <>
        <BeforeUnloadProbe />
        <ControlSessionMonitor />
      </>,
    );
    expect(fireBeforeUnload()).toBe(true); // 미저장 경고가 걸려 있는 화면이다

    tick(4 * 1000);

    expect(dialog()).toBeNull();
    expect(useAuthStore.getState().token).toBeNull();
    expect(redirect.assign).toHaveBeenCalledTimes(1);
    expect(fireBeforeUnload()).toBe(false);
    // 관제 웹과 같은 결말 — 만료도 같은 출처의 토큰 키를 지운다.
    expect(localStorage.getItem('klid-jwt-token')).toBeNull();
    expect(localStorage.getItem('tokenInfo')).toBeNull();
  });

  it('★「로그아웃」은_로그아웃_중계를_부르고_응답과_무관하게_토큰_키를_지운_뒤_이동한다', async () => {
    const session = controlTab(4 * 60);
    relay.onDelete(CONTROL_SESSION_PATH).networkError();
    render(<ControlSessionMonitor />);

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));

    // ⚠ RTL `waitFor` 는 폴링을 `setInterval` 로 하는데 이 파일은 그것을 가짜로 둔다 — 이동 호출은
    //   DOM 을 바꾸지 않아 깨울 계기가 없다. 가짜 시계를 스스로 진행시키는 `vi.waitFor` 를 쓴다.
    await vi.waitFor(() => expect(redirect.assign).toHaveBeenCalledTimes(1));
    expect(relay.history.delete).toHaveLength(1);
    expect(relay.history.delete[0].headers?.Authorization).toBe(`Bearer ${session}`);
    expect(localStorage.getItem('klid-jwt-token')).toBeNull();
    expect(localStorage.getItem('tokenInfo')).toBeNull();
  });

  it('미저장_편집이_있으면_팝업에_경고가_보이고_없으면_보이지_않는다', () => {
    controlTab(4 * 60);
    render(<ControlSessionMonitor />);
    expect(screen.queryByText(UNSAVED_WORK_WARNING)).toBeNull();

    act(() => useUnsavedWorkStore.getState().setSource('labeling', true));

    expect(within(dialog() as HTMLElement).getByText(UNSAVED_WORK_WARNING)).toBeInTheDocument();
  });

  it('★미저장_편집이_있으면_로그아웃_전에_확인하고_취소하면_남으며_다음_주기에_팝업이_다시_뜬다', () => {
    controlTab(4 * 60);
    act(() => useUnsavedWorkStore.getState().setSource('labeling', true));
    render(<ControlSessionMonitor />);

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(dialog()).toBeNull();
    const confirm = screen.getByRole('dialog', { name: '저장하지 않은 작업이 있습니다' });
    tick(3000);
    expect(dialog()).toBeNull(); // 확인 중에는 되풀이하지 않는다

    fireEvent.click(within(confirm).getByRole('button', { name: '취소' }));
    expect(relay.history.delete).toHaveLength(0);
    expect(redirect.assign).not.toHaveBeenCalled();
    tick();
    expect(dialog()).not.toBeNull();
  });

  it('★미저장_확인에서_계속을_고르면_로그아웃하며_이탈_경고가_다시_뜨지_않는다', async () => {
    controlTab(4 * 60);
    relay.onDelete(CONTROL_SESSION_PATH).reply(204);
    act(() => useUnsavedWorkStore.getState().setSource('labeling', true));
    render(
      <>
        <BeforeUnloadProbe />
        <ControlSessionMonitor />
      </>,
    );

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));
    const confirm = screen.getByRole('dialog', { name: '저장하지 않은 작업이 있습니다' });
    fireEvent.click(within(confirm).getByRole('button', { name: '로그아웃' }));

    await vi.waitFor(() => expect(redirect.assign).toHaveBeenCalledTimes(1));
    expect(relay.history.delete).toHaveLength(1);
    expect(fireBeforeUnload()).toBe(false);
  });

  it('★다른_탭이_갱신하면_떠_있던_팝업이_닫힌다', () => {
    const session = controlTab(4 * 60);
    render(<ControlSessionMonitor />);
    expect(dialog()).not.toBeNull();

    const renewed = makeAccessJwt(30 * 60);
    putControlSession(renewed, makeRefreshJwt());
    act(() => fireStorage('klid-jwt-token', session, renewed));

    expect(dialog()).toBeNull();
    expect(useAuthStore.getState().token).toBe(renewed);
  });

  it('★다른_탭이_로그아웃하면(토큰_키_삭제)_따라서_로그아웃한다', () => {
    const session = controlTab(20 * 60);
    render(<ControlSessionMonitor />);

    localStorage.removeItem('klid-jwt-token');
    act(() => fireStorage('klid-jwt-token', session, null));

    expect(useAuthStore.getState().token).toBeNull();
    expect(redirect.assign).toHaveBeenCalledTimes(1);
  });

  it('★라벨링_캔버스_전체_화면에서도_뜨고_로그인_전_화면에서는_뜨지_않는다', () => {
    controlTab(4 * 60);
    const { unmount } = render(<ControlSessionMonitor />);
    expect(dialog()).not.toBeNull(); // /label/1
    unmount();
    redirect.restore();

    redirect = stubUpstreamRedirect('/ingress');
    render(<ControlSessionMonitor />);
    expect(dialog()).toBeNull();
  });

  it('★개발_로그인(갱신_불가)에는_팝업을_띄우지_않되_만료_0의_로그아웃은_그대로다', () => {
    const devSession = makeAccessJwt(4 * 60);
    localStorage.setItem('klid-jwt-token', devSession);
    signInTab(devSession);
    render(<ControlSessionMonitor />);
    expect(dialog()).toBeNull();

    tick(4 * 60 * 1000 + 1000);
    expect(redirect.assign).toHaveBeenCalledTimes(1);
  });

  it('세션이_없으면_아무것도_하지_않는다', () => {
    render(<ControlSessionMonitor />);
    tick(5000);
    expect(dialog()).toBeNull();
    expect(redirect.assign).not.toHaveBeenCalled();
  });
});
