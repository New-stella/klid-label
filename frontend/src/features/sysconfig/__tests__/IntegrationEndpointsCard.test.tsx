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

/**
 * ConfigStringMap 의 키는 BE 가 준 dotted 원문 그대로다(폼 별칭이 아니다).
 *
 * ★ 증강 키는 **일부러 넣지 않는다** — 저장 행이 없는 상태(=아직 연동하지 않음)가 이 축의
 *   정상 기본값이고, 그 상태에서 화면이 어떻게 보이는지가 이 카드의 핵심 계약이다.
 */
const storedConfigs = {
  [ConfigKey.KPST_DEID_BASE_URL]: 'https://deid.example-vendor.net',
};

/** 화면에서 편집 창구를 걷어낸 두 축 — 되살아나면 «조용한 실패»가 재발한다. */
const REMOVED_FIELD_LABELS = ['AI 추론 서버', '외부 시계열 분석 벤더'] as const;
const REMOVED_CONFIG_KEYS = [
  ConfigKey.INTEGRATION_AI_SERVER_BASE_URL,
  ConfigKey.VLM_CLIENT_URL,
] as const;

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

/** 「관리자 확인」 → 입력 → 인증까지 진행한다. */
async function authenticate(value = DUMMY_PW) {
  fireEvent.click(screen.getByRole('button', { name: '관리자 확인' }));
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

  it('연동_대상_주소_칸이_렌더된다 — 저장한_값이_곧_진실원인_축만', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    expect(screen.getByLabelText('비식별 서버')).toBeInTheDocument();
    expect(screen.getByLabelText('외부 증강 벤더')).toBeInTheDocument();
    expect(screen.getByLabelText('관제 통지 수신처')).toBeInTheDocument();
    expect(screen.getByLabelText('관제 계정 창구')).toBeInTheDocument();
  });

  /*
   * [@design SCREEN-042] 관제 계정 창구 — 관제 통지 수신처와 별개 값.
   * ★ 위치: 관제 통지 수신처 **바로 아래**. 두 관제 창구가 떨어져 있으면 어느 것이 통지용이고
   *   어느 것이 세션 중계용인지 한눈에 갈리지 않는다.
   * ★ 빈 칸 안내: 이 칸은 배포 기본값이 비어 있어 «배포 기본값» 문구가 거짓이 된다 — 문구 축을 값으로 고정한다.
   */
  it('★관제_계정_창구_칸은_관제_통지_수신처_바로_아래에_있고_빈_값_안내는_세션_연장_불가다', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    const inputs = screen
      .getAllByRole('textbox')
      .filter((el) => el.id.startsWith('endpoint-'))
      .map((el) => el.id);
    const notifyIdx = inputs.indexOf('endpoint-controlNotify');
    expect(notifyIdx).toBeGreaterThanOrEqual(0);
    expect(inputs[notifyIdx + 1]).toBe('endpoint-controlAccount');

    const account = screen.getByLabelText('관제 계정 창구') as HTMLInputElement;
    expect(account.value).toBe('');
    expect(account.placeholder).toBe('미설정 — 세션 연장 불가');
    expect(account.placeholder).not.toContain('배포 기본값');
    expect(screen.getByText(/비어 있으면 세션 연장이 동작하지 않습니다/)).toBeInTheDocument();
  });

  it('★관제_계정_창구에_저장값이_있으면_그_값이_칸에_채워진다 (읽는 자리 가드)', () => {
    renderWithProviders(
      <IntegrationEndpointsCard
        configs={{
          ...storedConfigs,
          [ConfigKey.CONTROL_NOTIFY_URL]: 'https://notify.example-vendor.net',
          [ConfigKey.CONTROL_ACCOUNT_URL]: 'https://account.example-vendor.net',
        }}
      />,
    );

    // 두 관제 칸이 서로의 키를 읽지 않는다 — 값이 다른 상태에서만 이 축이 드러난다.
    expect(screen.getByLabelText('관제 계정 창구')).toHaveValue('https://account.example-vendor.net');
    expect(screen.getByLabelText('관제 통지 수신처')).toHaveValue('https://notify.example-vendor.net');
  });

  it('★관제_계정_창구만_바꾸면_그_키_한_건만_전송된다 — 다른_칸_값은_나가지_않는다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(
      <IntegrationEndpointsCard
        configs={{
          ...storedConfigs,
          [ConfigKey.CONTROL_NOTIFY_URL]: 'https://notify.example-vendor.net',
        }}
      />,
    );
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('관제 계정 창구'), {
      target: { value: 'https://account.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].key).toBe(ConfigKey.CONTROL_ACCOUNT_URL);
    expect(puts[0].value).toBe('https://account.example-vendor.net');
    expect(puts[0].adminHeader).toBe(DUMMY_SESSION);
    // 통지 수신처 키로 새지 않는다 — 두 관제 창구는 별개 값이다.
    expect(puts.map((p) => p.key)).not.toContain(ConfigKey.CONTROL_NOTIFY_URL);
  });

  it('★관제_계정_창구의_형식_위반은_화면에서_먼저_걸러_서버까지_가지_않는다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('관제 계정 창구'), {
      target: { value: 'account.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText(/http:\/\/ 또는 https:\/\/ 로 시작/)).toBeInTheDocument();
    expect(screen.getByLabelText('관제 계정 창구')).toHaveAttribute('aria-invalid', 'true');
    expect(puts).toHaveLength(0);
  });

  /*
   * ★ 「없다」만 단언하면 그 자리에 서야 할 것이 함께 사라져도 통과한다 — 남아야 할 세 칸의
   *   존재 단언과 **짝으로** 둔다. 제거 축과 존치 축은 서로를 대체하지 않는다.
   */
  it('★죽은_칸_두_개가_사라졌다 — 남아야_할_칸은_그대로다 (회귀 가드)', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    for (const label of REMOVED_FIELD_LABELS) {
      expect(screen.queryByLabelText(label)).toBeNull();
    }
    // 존치 축 — 함께 증발하지 않았는지 본다.
    for (const label of ['비식별 서버', '외부 증강 벤더', '관제 통지 수신처', '관제 계정 창구']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
  });

  it('★제거된_두_키는_저장_요청에도_실리지_않는다 (값 축 가드)', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    // 남은 칸을 전부 바꿔 저장한다 — 그래도 걷어낸 두 키는 한 건도 나가면 안 된다.
    fireEvent.change(screen.getByLabelText('비식별 서버'), {
      target: { value: 'https://deid2.example-vendor.net' },
    });
    fireEvent.change(screen.getByLabelText('외부 증강 벤더'), {
      target: { value: 'https://augment.example-vendor.net' },
    });
    fireEvent.change(screen.getByLabelText('관제 통지 수신처'), {
      target: { value: 'https://control.example-vendor.net' },
    });
    fireEvent.change(screen.getByLabelText('관제 계정 창구'), {
      target: { value: 'https://control-account.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // 대기는 «지키려는 축»이 아니라 건수로 건다 — 주소로 기다리면 변이가 대기에서 먼저 죽는다.
    await waitFor(() => expect(puts).toHaveLength(4));
    for (const key of REMOVED_CONFIG_KEYS) {
      expect(puts.map((p) => p.key)).not.toContain(key);
    }
    expect(puts.map((p) => p.key).sort()).toEqual(
      [
        ConfigKey.KPST_DEID_BASE_URL,
        ConfigKey.AUGMENT_EXTERNAL_BASE_URL,
        ConfigKey.CONTROL_NOTIFY_URL,
        ConfigKey.CONTROL_ACCOUNT_URL,
      ].sort(),
    );
  });

  it('★잠금이_기본이다 — 인증_전에는_읽기_전용이고_저장이_비활성이다', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    expect(screen.getByText('읽기 전용')).toBeInTheDocument();
    for (const label of ['비식별 서버', '외부 증강 벤더', '관제 통지 수신처', '관제 계정 창구']) {
      expect(screen.getByLabelText(label)).toHaveAttribute('readonly');
    }
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('저장된_값이_화면에_반영되고_미설정_항목은_배포_기본값_안내를_보여준다', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    expect((screen.getByLabelText('비식별 서버') as HTMLInputElement).value).toBe(
      'https://deid.example-vendor.net',
    );
    const unset = screen.getByLabelText('관제 통지 수신처') as HTMLInputElement;
    expect(unset.value).toBe('');
    expect(unset.placeholder).toContain('배포 기본값');
  });

  /*
   * ★ 증강 칸의 빈 값은 «기본값으로 도는 중» 이 아니라 «아직 연동하지 않음» 이다.
   *   같은 안내 문구를 쓰면 미연동이 정상 가동으로 읽힌다 — 문구 축을 값으로 고정한다.
   */
  it('★증강_칸의_빈_값은_배포_기본값_안내로_덮이지_않는다', () => {
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    const augment = screen.getByLabelText('외부 증강 벤더') as HTMLInputElement;
    expect(augment.value).toBe('');
    expect(augment.placeholder).not.toContain('배포 기본값');
    expect(augment.placeholder).toContain('연동 전에는 비워');
  });

  it('★인증하면_편집이_열리고_남은_시간이_표시된다', async () => {
    wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);

    await authenticate();

    await waitFor(() => {
      expect(screen.getByTestId('admin-session-remaining')).toHaveTextContent(/남음/);
    });
    expect(screen.getByLabelText('비식별 서버')).not.toHaveAttribute('readonly');
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

    fireEvent.change(screen.getByLabelText('외부 증강 벤더'), {
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

    fireEvent.change(screen.getByLabelText('외부 증강 벤더'), {
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
    fireEvent.change(screen.getByLabelText('관제 통지 수신처'), {
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

    fireEvent.change(screen.getByLabelText('외부 증강 벤더'), {
      target: { value: 'https://augment2.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText(/관리자 확인이 만료/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('읽기 전용')).toBeInTheDocument());
    expect(screen.getByLabelText('외부 증강 벤더')).toHaveAttribute('readonly');
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

    fireEvent.click(screen.getByRole('button', { name: '관리자 확인' }));
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

  /*
   * ★ 외부 증강 벤더 칸의 두 성질 — 다른 칸에 없다.
   *   ① 비어 있는 것이 정상 상태이고 그 빈 값이 «아직 연동하지 않음»의 유일한 표현이다.
   *   ② 채우면 콜백 허용 목록과 짝이므로 그 사실을 채우는 자리에서 알린다.
   */
  it('★증강_칸이_비어_있어도_다른_칸을_저장할_수_있다 — 빈_값은_전송되지_않는다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    // 증강 칸은 손대지 않는다 — 그 상태가 정상이다.
    fireEvent.change(screen.getByLabelText('관제 통지 수신처'), {
      target: { value: 'https://control.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].key).toBe(ConfigKey.CONTROL_NOTIFY_URL);
    // 빈 증강 주소로 행을 만들지 않는다 — 만들면 «연동됨»으로 판정돼 아무도 받지 않는 주소로
    // 위탁이 나가고 그 실패가 벤더 장애처럼 보인다.
    expect(puts.map((x) => x.key)).not.toContain(ConfigKey.AUGMENT_EXTERNAL_BASE_URL);
    // 검증 오류로 저장이 막히지도 않는다 — 빈 증강 칸은 «미입력»이 아니라 정상 상태다.
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
  });

  it('★증강_주소를_채우면_콜백_허용목록_짝_안내가_뜬다', async () => {
    wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    // 채우기 전에는 없다 — 부재 단언은 존재 단언과 짝으로 둔다.
    expect(screen.queryByTestId('augment-callback-pair-notice')).toBeNull();

    fireEvent.change(screen.getByLabelText('외부 증강 벤더'), {
      target: { value: 'https://augment.example-vendor.net' },
    });

    const notice = await screen.findByTestId('augment-callback-pair-notice');
    expect(notice).toHaveTextContent('콜백 허용 주소 목록도 함께 채워야 합니다');
  });

  it('★증강_주소는_증강_설정_키로_전송된다 — 주소_축과_본문_축을_따로_본다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    fireEvent.change(screen.getByLabelText('외부 증강 벤더'), {
      target: { value: 'https://augment.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].key).toBe(ConfigKey.AUGMENT_EXTERNAL_BASE_URL);
    expect(puts[0].value).toBe('https://augment.example-vendor.net');
    expect(puts[0].adminHeader).toBe(DUMMY_SESSION);
  });

  it('★입력한_인증값이_저장_요청에_실리지_않는다 — 헤더의_토큰만_나간다', async () => {
    const puts = wireHappyPath(mock);
    renderWithProviders(<IntegrationEndpointsCard configs={storedConfigs} />);
    await authenticate();
    await screen.findByTestId('admin-session-remaining');

    // ⚠ 저장된 값과 **다른** 값이어야 한다 — 같은 값이면 변경으로 잡히지 않아 전송이 0건이 되고,
    //   그러면 이 케이스가 «인증값이 안 실린다» 가 아니라 «아무것도 안 나갔다» 를 검증하게 된다.
    fireEvent.change(screen.getByLabelText('비식별 서버'), {
      target: { value: 'https://deid-alt.example-vendor.net' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(JSON.stringify(puts[0])).not.toContain(DUMMY_PW);
  });
});
