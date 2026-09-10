import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useAuthStore } from '@/stores/useAuthStore';

import { DevLoginPage } from '../DevLoginPage';
import { DEV_HOST_TOKEN_STORAGE_KEY, clearDevHostToken } from '../devHostStub';
import { clearHostTokenHandoff, getAccessToken } from '../tokenHandoff';
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
    sessionStorage.clear();
    clearHostTokenHandoff();
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    clearDevHostToken();
    clearHostTokenHandoff();
    vi.unstubAllEnvs();
    localStorage.clear();
    sessionStorage.clear();
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

  it('expSeconds_는_기본값이_선채움되지_않고_placeholder로만_안내된다', async () => {
    // given: 사양(SCREEN-004) — expSeconds 는 선택 입력, placeholder=3600.
    // 구 버그: state 초기값이 String(3600) 이라 입력칸이 항상 "3600" 으로 채워져 있었고,
    // 사용자가 손대지 않아도 매 요청에 expSeconds=3600 이 명시 전송됐다(userNo 와 다른 계약).
    renderPage();

    const expInput = screen.getByLabelText(/expSeconds/i) as HTMLInputElement;
    expect(expInput.value).toBe('');
    expect(expInput.placeholder).toBe('3600');
  });

  it('expSeconds_를_비워둔_채_제출하면_요청_body에_필드_자체가_없다', async () => {
    // given
    const user = userEvent.setup();
    const token = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: '1001', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999, name: '김검수' },
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
              sub: '1001',
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

    // when: expSeconds 입력을 건드리지 않고 바로 제출
    await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

    // then: BE 기본값을 쓰도록 필드 자체가 빠져야 한다 (userNo 와 동일 계약)
    await waitFor(() => {
      expect(captured.value).not.toBeNull();
    });
    expect(captured.value).not.toHaveProperty('expSeconds');
  });

  it('★관제_채널_선택지는_내부_역할_셋이며_순서까지_같다', () => {
    // @design SCREEN-004 — 관리자가 첫 번째다. 재현할 수 없는 역할이 남으면 관리자 전용
    // 화면을 사람이 눌러 확인할 수단이 없어진다(사양이 밝힌 이유).
    //
    // ★ 포털 사용자는 여기 **없다.** 이 산출물은 관제 서버에 배포되어 내부 채널 사용자만
    //   받으므로, 그 선택지를 두면 고르는 순간 채널이 맞지 않아 진입이 막힌다.
    renderPage();

    const radios = screen.getAllByRole('radio');
    const expected = [
      ['ADMIN (9001, 시스템관리자)', 'INTERNAL'],
      ['REVIEWER (1001, 김검수)', 'INTERNAL'],
      ['WORKER (2001, 최라벨)', 'INTERNAL'],
    ] as const;

    expect(radios).toHaveLength(expected.length);
    expected.forEach(([title, channel], i) => {
      const radio = radios[i];
      // 접근 이름은 제목만이다(설명은 aria-describedby 로 갈린다 — RadioCard 계약).
      expect(radio).toHaveAccessibleName(title);
      // 채널 칩은 카드의 후행 슬롯이라 접근 이름에 들어오지 않는다 → 카드 본문에서 확인한다.
      expect(radio.closest('label')).toHaveTextContent(channel);
    });
    // ⚠ 「셋뿐이다」만 보면 다른 이름으로 넷째가 들어와도 통과한다 — 값으로 못 박는다.
    expect(screen.queryByLabelText(/PORTAL_USER/)).not.toBeInTheDocument();
  });

  it('기본_선택은_검수자_그대로다', () => {
    // 관리자를 선택지에 더하는 것과 기본값을 옮기는 것은 다른 축이다. 개발자가 가장 자주 쓰는
    // 역할이 바뀌면 기존 동선이 흔들리므로, 관리자는 '고를 수 있으면' 된다.
    renderPage();

    expect(screen.getByLabelText('REVIEWER (1001, 김검수)')).toBeChecked();
    expect(screen.getByLabelText('ADMIN (9001, 시스템관리자)')).not.toBeChecked();
  });

  it('ADMIN_선택_시_role_ADMIN_channel_INTERNAL_로_전송되고_ingress_로_진입한다', async () => {
    const user = userEvent.setup();
    const token = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: '9001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999, name: '시스템관리자' },
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
              sub: '9001',
              role: 'ADMIN',
              channel: 'INTERNAL',
              name: '시스템관리자',
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

    await user.click(screen.getByLabelText('ADMIN (9001, 시스템관리자)'));
    await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

    await waitFor(() => {
      expect(screen.getByText('INGRESS_STUB')).toBeInTheDocument();
    });

    expect(localStorage.getItem(LOCAL_STORAGE_TOKEN_KEY)).toBe(token);
    expect(captured.value?.role).toBe('ADMIN');
    // ADMIN 은 내부 채널 역할이다 — BE 의 role-channel 정합 검사가 PORTAL 조합을 400 으로 막는다.
    expect(captured.value?.channel).toBe('INTERNAL');
    // 관제서버 stub 재현 — 관리자 화면이 읽는 사용자 식별값도 함께 놓인다.
    expect(localStorage.getItem('klid-authority')).toBe('ADMIN');
    expect(localStorage.getItem('klid-user-id')).toBe('9001');
  });

  it('ADMIN_기본값_안내는_9001_시스템관리자다', async () => {
    // userNo placeholder·안내 문구가 역할과 연동된다(사양). 시드(9001 시스템관리자)와 어긋나면
    // 토큰의 sub 가 다른 사람의 행을 가리킨다 — 과거 1002/2001 오매핑이 그 사고였다.
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByLabelText('ADMIN (9001, 시스템관리자)'));

    const userNoInput = screen.getByLabelText(/userNo/i) as HTMLInputElement;
    expect(userNoInput.placeholder).toBe('9001');
    // 카드 제목에도 같은 값이 들어 있으므로 안내 문구 쪽만 집는다.
    expect(screen.getByText(/비워두면 BE 기본값\(9001, 시스템관리자\)/)).toBeInTheDocument();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 포털 채널 산출물 — 선택지·보관 자리가 함께 갈린다 (@design SCREEN-004 · INT-013)
  // ───────────────────────────────────────────────────────────────────────────
  describe('포털 채널 산출물', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    });

    it('★선택지는_포털_사용자_하나이며_그것이_기본_선택이다', () => {
      // 이 산출물에서 그것이 **유일한 진입 수단**이다. 기본 선택도 그 채널에서 유효한 값이어야
      // 한다 — 관제 채널 기본값(검수자)이 남으면 첫 화면부터 고를 수 없는 값이 선택돼 있다.
      renderPage();

      const radios = screen.getAllByRole('radio');
      expect(radios).toHaveLength(1);
      expect(radios[0]).toHaveAccessibleName('PORTAL_USER (3001, 홍길동)');
      expect(radios[0]).toBeChecked();
      expect(radios[0].closest('label')).toHaveTextContent('PORTAL');

      // 반대 채널 역할이 섞여 있지 않다 — 고르는 순간 채널 불일치로 막히는 선택지가 남으면 안 된다.
      for (const name of [/ADMIN/, /REVIEWER/, /WORKER/]) {
        expect(screen.queryByLabelText(name)).not.toBeInTheDocument();
      }
    });

    it('★발급_토큰은_Host_대역이_보관하고_본체_인계_키에는_쓰지_않는다', async () => {
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
              claims: { sub: '3001', role: 'PORTAL_USER', channel: 'PORTAL', name: '홍길동' },
              authorizationHeader: `Bearer ${token}`,
            },
            message: null,
            errorCode: null,
          },
        ];
      });

      renderPage();
      await user.click(screen.getByRole('button', { name: /토큰 발급/ }));

      await waitFor(() => {
        expect(screen.getByText('INGRESS_STUB')).toBeInTheDocument();
      });

      expect(captured.value?.role).toBe('PORTAL_USER');
      expect(captured.value?.channel).toBe('PORTAL');
      // ★ 본체는 포털 채널에서 브라우저 저장소를 쓰지 않는다 — 인계 키에 쓰면 그 불변식이
      //   흐려지고, 무엇보다 본체가 그 값을 읽지 않으므로 아무 소용이 없다.
      expect(localStorage.getItem(LOCAL_STORAGE_TOKEN_KEY)).toBeNull();
      // 대역이 자기 자리에 들고 있고, 본체의 조달 지점이 그것을 받는다.
      expect(sessionStorage.getItem(DEV_HOST_TOKEN_STORAGE_KEY)).toBe(token);
      expect(await getAccessToken()).toBe(token);
    });
  });
});
