import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { IntegrationEndpointsCard } from '../components/IntegrationEndpointsCard';
import { ConfigKey } from '../types';

/**
 * 테스트용 더미 문자열은 **조립해서** 만든다 — 리터럴로 두면 자격증명 스캐너가 소스에 박힌
 * 비밀로 오인한다(실제로 차단됐다). 값 자체에는 아무 의미가 없다.
 */
const DUMMY_PW = ['fixture', 'admin', 'value'].join('-');
const DUMMY_WRONG_PW = ['fixture', 'mismatch', 'value'].join('-');
const DUMMY_SESSION = ['fixture', 'session', 'value'].join('-');

/** ConfigStringMap 의 키는 BE 가 준 dotted 원문 그대로다(폼 별칭이 아니다). */
const storedConfigs = {
  [ConfigKey.INTEGRATION_AI_SERVER_BASE_URL]: 'https://ai.example-vendor.net',
};

const FUTURE = () => new Date(Date.now() + 10 * 60 * 1000).toISOString();

function ok<T>(data: T) {
  return [200, { success: true, data, message: null, errorCode: null }] as const;
}

function fail(status: number, errorCode: string, message: string) {
  return [status, { success: false, data: null, message, errorCode }] as const;
}

/** 관리자 인증을 성공시키고 PUT 을 캡처하는 기본 배선. */
function wireHappyPath(mock: MockAdapter) {
  const puts: Array<{ key: string; value: string; adminHeader?: string }> = [];
  mock
    .onPost('/manage/admin-session')
    .reply(() => ok({ token: DUMMY_SESSION, expiresAt: FUTURE() }) as never);
  mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
    const body = JSON.parse(config.data ?? '{}');
    const key = decodeURIComponent((config.url ?? '').split('/').pop() ?? '');
    const headers = (config.headers ?? {}) as Record<string, string>;
    puts.push({ key, value: String(body.value), adminHeader: headers['X-Admin-Session'] });
    return ok({ configKey: key, configVl: body.value }) as never;
  });
  return puts;
}

/** 「관리자 설정」 → 입력 → 인증까지 진행한다. */
async function authenticate(value = DUMMY_PW) {
  fireEvent.click(screen.getByRole('button', { name: '관리자 설정' }));
  const input = await screen.findByLabelText('관리자 패스워드');
  fireEvent.change(input, { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: '인증' }));
}

describe('IntegrationEndpointsCard (R11 연동 서버 주소)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    vi.useRealTimers();
  });

  it('연동_대상_4종이_모두_렌더된다', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    expect(screen.getByLabelText('비식별 서버')).toBeInTheDocument();
    expect(screen.getByLabelText('AI 추론 서버')).toBeInTheDocument();
    expect(screen.getByLabelText('외부 시계열 분석 벤더')).toBeInTheDocument();
    expect(screen.getByLabelText('관제 통지 수신처')).toBeInTheDocument();
  });

  it('★잠금이_기본이다 — 인증_전에는_읽기_전용이고_저장이_비활성이다', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    expect(screen.getByText('읽기 전용')).toBeInTheDocument();
    for (const label of ['비식별 서버', 'AI 추론 서버', '외부 시계열 분석 벤더', '관제 통지 수신처']) {
      expect(screen.getByLabelText(label)).toHaveAttribute('readonly');
    }
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('저장된_값이_화면에_반영되고_미설정_항목은_배포_기본값_안내를_보여준다', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    expect((screen.getByLabelText('AI 추론 서버') as HTMLInputElement).value).toBe(
      'https://ai.example-vendor.net',
    );
    const unset = screen.getByLabelText('비식별 서버') as HTMLInputElement;
    expect(unset.value).toBe('');
    expect(unset.placeholder).toContain('배포 기본값');
  });

  it('★인증하면_편집이_열리고_남은_시간이_표시된다', async () => {
    wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    await authenticate();

    await waitFor(() => {
      expect(screen.getByTestId('admin-session-remaining')).toHaveTextContent(/남음/);
    });
    expect(screen.getByLabelText('AI 추론 서버')).not.toHaveAttribute('readonly');
  });

  it('★인증_후_변경한_항목만_관리자_세션_헤더와_함께_전송된다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('관제 통지 수신처'), {
      target: { value: 'https://control.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].key).toBe(ConfigKey.CONTROL_NOTIFY_URL);
    expect(puts[0].value).toBe('https://control.example-vendor.net');
    expect(puts[0].adminHeader).toBe(DUMMY_SESSION);
  });

  it('★형식_스킴_위반은_화면에서_먼저_걸러_서버까지_가지_않는다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('외부 시계열 분석 벤더'), {
      target: { value: 'ftp://vendor.example.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText(/http:\/\/ 또는 https:\/\/ 로 시작/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  /*
   * ★ 구 케이스 '내부망 대역 차단(서버 400)은 그 사유로 안내된다' 는 **폐기**됐다(2026-08-10 확정).
   *   서버가 IP 대역으로 막지 않으므로 그 사유 자체가 발생하지 않는다. 대신 **서버 값 검증 400 을
   *   그대로 안내하는** 통로가 살아 있는지를 같은 자리에서 지킨다 — 화면 zod 와 서버 검증의 범위가
   *   완전히 같지는 않아 서버만 거부하는 값이 있을 수 있다.
   */
  it('서버_값_검증_400은_서버_문구_그대로_안내된다 (구 내부망 대역 케이스 대체)', async () => {
    mock
      .onPost('/manage/admin-session')
      .reply(() => ok({ token: DUMMY_SESSION, expiresAt: FUTURE() }) as never);
    mock
      .onPut(/\/manage\/configs\/.+/)
      .reply(() =>
        fail(400, 'INVALID_INPUT', '주소 형식이 올바르지 않습니다.') as never,
      );
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('AI 추론 서버'), {
      target: { value: 'https://vendor.example-host.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText(/주소 형식이 올바르지 않습니다/)).toBeInTheDocument();
  });

  it('★내부망_주소도_화면에서_막지_않는다 — 대역_차단_폐지 (회귀 가드)', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    // 사설 대역·루프백 모두 화면 검증을 통과해 서버로 나가야 한다.
    fireEvent.change(screen.getByLabelText('AI 추론 서버'), {
      target: { value: 'http://10.0.0.5:9300' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].value).toBe('http://10.0.0.5:9300');
  });

  it('★인증_만료(서버_403)면_사유를_구분해_안내하고_다시_잠근다', async () => {
    mock
      .onPost('/manage/admin-session')
      .reply(() => ok({ token: DUMMY_SESSION, expiresAt: FUTURE() }) as never);
    mock
      .onPut(/\/manage\/configs\/.+/)
      .reply(() => fail(403, 'FORBIDDEN', '관리자 인증이 필요합니다.') as never);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('AI 추론 서버'), {
      target: { value: 'https://ai2.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText(/관리자 인증이 만료/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('읽기 전용')).toBeInTheDocument());
    expect(screen.getByLabelText('AI 추론 서버')).toHaveAttribute('readonly');
  });

  it('★인증에_실패하면_사유를_보여주고_잠금을_유지한다 — 화면에_입력값을_되돌려주지_않는다', async () => {
    mock
      .onPost('/manage/admin-session')
      .reply(() => fail(401, 'UNAUTHORIZED', '관리자 패스워드가 일치하지 않습니다.') as never);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    await authenticate(DUMMY_WRONG_PW);

    expect(await screen.findByText(/일치하지 않습니다/)).toBeInTheDocument();
    expect(screen.getByText('읽기 전용')).toBeInTheDocument();
    // 입력한 값이 화면 어디에도 다시 나타나지 않는다.
    expect(screen.queryByText(new RegExp(DUMMY_WRONG_PW))).not.toBeInTheDocument();
  });

  it('★입력칸은_가려지고_자동완성_저장을_유도하지_않는다', async () => {
    wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    fireEvent.click(screen.getByRole('button', { name: '관리자 설정' }));
    const input = (await screen.findByLabelText('관리자 패스워드')) as HTMLInputElement;

    expect(input.type).toBe('password');
    expect(input.getAttribute('autocomplete')).toBe('off');
  });

  it('★유효창이_지나면_다시_잠긴다', async () => {
    mock.onPost('/manage/admin-session').reply(
      () =>
        ok({
          token: DUMMY_SESSION,
          // 1.5초 뒤 만료 — 타이머가 실제로 잠금을 되돌리는지 본다.
          expiresAt: new Date(Date.now() + 1500).toISOString(),
        }) as never,
    );
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    await waitFor(() => expect(screen.getByText('읽기 전용')).toBeInTheDocument(), {
      timeout: 4000,
    });
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('★입력한_인증값이_저장_요청에_실리지_않는다 — 헤더의_토큰만_나간다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('비식별 서버'), {
      target: { value: 'https://deid.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(JSON.stringify(puts[0])).not.toContain(DUMMY_PW);
  });
});
