import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { RoleClaimPage } from '@/pages/RoleClaimPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

// b64url 인코더 — 가짜 JWT 생성용 (UTF-8 safe)
function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  const b64 = btoa(utf8);
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function buildJwt(payload: Record<string, unknown>): string {
  const header = b64url({ alg: 'HS256', typ: 'JWT' });
  return `${header}.${b64url(payload)}.sig`;
}

/** 스토어 적재용 더미 인계 키 — 이 화면은 값을 해석하지 않는다(개폐·제출만 본다). */
const HANDOFF_JWT = 'tok';

/** 진입 시점에 이 화면이 반드시 묻는 창구(API-245). */
const AVAILABILITY_PATH = '/auth/role-claim/availability';

/**
 * 열린 상태에서만 나타나야 하는 문구. [@design SCREEN-002]
 *
 * ★이 문장이 <닫힌 시스템>에 뜨는 것이 이 라운드가 고친 결함이다(246 실측 2026-09-07 —
 * 관리자 9001 이 실재하는데 이 화면이 무조건 그렇게 말했다).
 */
const OPEN_ONLY_COPY = '이 시스템에는 아직 관리자가 없습니다';

describe('RoleClaimPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    localStorage.clear();
    // 기본 전제 — 창구가 <열려 있다>(관리자 0명). 닫힘·실패는 각 케이스가 덮어쓴다.
    mock.onGet(AVAILABILITY_PATH).reply(200, {
      success: true,
      data: { available: true },
      message: null,
      errorCode: null,
    });
    // 권한 자가 부여 화면 진입 전제 — 인증은 됐으나 role 미부여 상태 (claims.role=null).
    useAuthStore.setState({
      token: HANDOFF_JWT,
      claims: {
        sub: '1001',
        role: null,
        channel: 'INTERNAL',
        exp: 9999999999,
      },
      isHydrated: true,
    });
  });

  afterEach(() => {
    mock.restore();
    localStorage.clear();
    useAuthStore.getState().clear();
  });

  /** 창구가 <열린> 모습이 실제로 그려질 때까지 기다린다(개폐 조회는 비동기다). */
  async function renderOpen() {
    const result = renderWithProviders(<RoleClaimPage />);
    await screen.findByRole('heading', { name: '관리자 등록' });
    return result;
  }

  /** 개폐 응답을 이 케이스 전용으로 갈아끼운다. */
  function replaceAvailability(replier: (m: MockAdapter) => void) {
    mock.resetHandlers();
    replier(mock);
  }

  // ─────────────────────────────────────────────────────────────────
  // ★이 화면은 「역할 자가부여」가 아니라 **관리자 부트스트랩**이다 (2026-08-28 · ADR-055).
  //   창구는 관리자가 0명일 때만 열리고 부여 역할은 관리자 고정이며, 서버는 요청 바디의 역할
  //   값을 **읽지 않는다**. 그래서 화면에 역할 선택이 없다.
  //
  //   ⚠ 구 사양 폐기 — *"작업자 / 검수자 중 하나를 고른다"* · *"2026-08-04 REVIEWER 자가부여
  //     개방(되돌리지 말 것)"* · *"이 시스템에 ADMIN 역할은 존재하지 않는다"* · *"검수자에게
  //     받은 패스워드로 역할을 부여받으세요"*. 되살리면 **사용자가 고른 값과 실제 부여 역할이
  //     갈려** 화면이 거짓을 말한다(작업자를 골랐는데 관리자가 된다).
  // ─────────────────────────────────────────────────────────────────

  it('역할을_고르는_자리가_없다', async () => {
    // ★고를 것이 없기 때문이다 — 부여 역할이 고정이고 서버가 요청 값을 읽지 않는다.
    await renderOpen();

    expect(screen.queryByRole('radiogroup', { name: '역할' })).toBeNull();
    expect(screen.queryByLabelText('작업자')).toBeNull();
    expect(screen.queryByLabelText('검수자')).toBeNull();
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
  });

  it('무슨_권한이_부여되는지_화면이_말한다', async () => {
    // 선택지를 없앤 자리에 침묵을 두지 않는다 — 무엇이 일어나는지 알려야 한다.
    await renderOpen();

    expect(screen.getByText('관리자 권한이 부여됩니다')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '관리자 등록' })).toBeInTheDocument();
  });

  it('폐기된_역할_서술이_화면에_남아있지_않다', async () => {
    // ★이 저장소가 반복해 겪은 「철회된 정책 재시도」의 씨앗을 화면 축에서 막는다.
    await renderOpen();
    const text = document.body.textContent ?? '';

    expect(text).not.toContain('검수자에게 받은 패스워드로 역할을 부여받으세요.');
    expect(text).not.toContain('검수자가 안내한 공유 패스워드입니다');
    expect(text).not.toContain('영상에 라벨을 만들고 수정해 검수를 요청합니다.');
    expect(screen.queryByPlaceholderText('검수자에게 받은 패스워드를 입력하세요')).toBeNull();
  });

  it('패스워드_미입력이면_제출_버튼이_잠긴다', async () => {
    // 잠그는 사유는 **패스워드 하나뿐**이다 — 역할 미선택은 더 이상 사유가 아니다.
    await renderOpen();
    expect(screen.getByRole('button', { name: '관리자로 등록' })).toBeDisabled();
  });

  it('패스워드만_입력하면_제출할_수_있다', async () => {
    // ★역할 선택이 사라졌으므로 다른 조건이 남아 잠겨 있으면 안 된다 — 그러면 아무도 최초
    //   관리자가 될 수 없어 시스템 전체가 잠긴다.
    const user = userEvent.setup();
    await renderOpen();

    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    expect(screen.getByRole('button', { name: '관리자로 등록' })).toBeEnabled();
  });

  it('공백만_입력하면_여전히_잠긴다', async () => {
    // 완화가 「아무 값이나 통과」로 흐르지 않게 한다.
    await renderOpen();
    const input = screen.getByLabelText('관리자 패스워드');
    fireEvent.change(input, { target: { value: '   ' } });
    expect(screen.getByRole('button', { name: '관리자로 등록' })).toBeDisabled();
  });

  it('패스워드_input_type_password_autocomplete_new_password', async () => {
    await renderOpen();
    const passwordInput = screen.getByLabelText('관리자 패스워드') as HTMLInputElement;
    expect(passwordInput.type).toBe('password');
    expect(passwordInput.getAttribute('autocomplete')).toBe('new-password');
  });

  it('전송값의_역할은_관리자_고정이다', async () => {
    // ★계약 축. 서버 DTO 가 role 을 필수로 요구하므로 생략할 수 없고, 실제 부여될 역할과 같은
    //   값을 실어야 서버 로그의 요청 역할과 부여 역할이 어긋나지 않는다.
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: {
        accessToken: buildJwt({ sub: '1001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 }),
        role: 'ADMIN',
        userNo: 1001,
        userName: '홍길동',
      },
      message: null,
      errorCode: null,
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    expect(body.role).toBe('ADMIN');
    // 구 값이 새어 나가지 않는다.
    expect(body.role).not.toBe('WORKER');
    expect(body.role).not.toBe('REVIEWER');
  });

  it('성공하면_관리자_토큰으로_교체되고_dashboard_로_간다', async () => {
    const user = userEvent.setup();
    const newToken = buildJwt({
      sub: '1001',
      role: 'ADMIN',
      channel: 'INTERNAL',
      exp: 9999999999,
      name: '홍길동',
    });
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: { accessToken: newToken, role: 'ADMIN', userNo: 1001, userName: '홍길동' },
      message: null,
      errorCode: null,
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true });
    });
    expect(useAuthStore.getState().token).toBe(newToken);
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
  });

  it('잘못된_패스워드_401_에러_메시지_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(401, {
      success: false,
      data: null,
      message: '관리자 패스워드가 일치하지 않습니다.',
      errorCode: 'UNAUTHORIZED',
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'wrong');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => {
      expect(screen.getByText('패스워드가 일치하지 않습니다')).toBeInTheDocument();
    });
    expect(
      screen.getByText('배포 시 설정된 공유 패스워드를 다시 확인해주세요.'),
    ).toBeInTheDocument();
  });

  it('★창구가_닫혔을_때_409_는_서버가_보낸_사유를_그대로_보여준다', async () => {
    // ★구 동작 폐기 — 상태코드 분기가 *"이미 권한이 부여된 사용자입니다 / 새로고침 해주세요."*
    //   로 덮고 있었다. 그 분기(역할 보유자 거절)는 서버에서 제거됐고, 지금 409 가 뜻하는 것은
    //   **창이 닫혔다**이다. 고정 문구로 덮으면 사용자는 새로고침만 반복하고 실제로 해야 할 일
    //   (관리자에게 요청)을 영영 알 수 없다.
    const user = userEvent.setup();
    const serverReason =
      '이미 관리자가 있어 자가부여가 닫혀 있습니다. 관리자에게 역할 부여를 요청하세요.';
    mock.onPost('/auth/role-claim').reply(409, {
      success: false,
      data: null,
      message: serverReason,
      errorCode: 'CONFLICT',
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => {
      expect(screen.getByText(serverReason)).toBeInTheDocument();
    });
    // 구 고정 문구가 그 자리를 덮지 않는다.
    expect(screen.queryByText('이미 권한이 부여된 사용자입니다')).toBeNull();
    expect(screen.queryByText('새로고침 해주세요.')).toBeNull();
  });

  it('409_의_다른_사유도_서버_문장_그대로_전달된다', async () => {
    // ★같은 코드에 사유가 둘이다(창 닫힘 / 내부 채널 아님). 화면이 사유를 지어내지 않는다는
    //   것을 **다른 문장**으로 한 번 더 고정한다 — 한 문장만 보면 그 문장을 하드코딩해도 통과한다.
    const user = userEvent.setup();
    const otherReason = '이 창구는 내부 채널에서만 사용할 수 있습니다.';
    mock.onPost('/auth/role-claim').reply(409, {
      success: false,
      data: null,
      message: otherReason,
      errorCode: 'CONFLICT',
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => {
      expect(screen.getByText(otherReason)).toBeInTheDocument();
    });
    // ★사유를 지어내지 않는다 — 이 갈래는 「관리자가 이미 있다」가 아닌데 그 문장을 붙이면
    //   화면이 거짓을 말한다.
    expect(
      screen.queryByText('이미 관리자가 있어 최초 관리자 등록 창구가 닫혀 있습니다'),
    ).toBeNull();
  });

  it('429_과다_시도_안내', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(429, {
      success: false,
      data: null,
      message: '시도 횟수를 초과했습니다.',
      errorCode: 'TOO_MANY_REQUESTS',
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => {
      expect(screen.getByText(/시도 횟수가 제한을 초과했습니다/)).toBeInTheDocument();
    });
  });

  it('400_INVALID_INPUT_은_BE_userMessage_를_표시한다', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(400, {
      success: false,
      data: null,
      message: 'PORTAL_USER 역할은 본 API 로 부여할 수 없습니다.',
      errorCode: 'INVALID_INPUT',
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => {
      expect(
        screen.getByText(/PORTAL_USER 역할은 본 API 로 부여할 수 없습니다/),
      ).toBeInTheDocument();
    });
  });

  it('관제_인계_표시정보가_클레임_요청에_동봉된다', async () => {
    const user = userEvent.setup();
    localStorage.setItem('userId', 'sjs123');
    localStorage.setItem('userNm', '신재석');
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: {
        accessToken: buildJwt({ sub: '1001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 }),
        role: 'ADMIN',
        userNo: 1001,
        userName: '신재석',
      },
      message: null,
      errorCode: null,
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    expect(body.userId).toBe('sjs123');
    expect(body.userNm).toBe('신재석');
    // ★사용자 식별(userNo)은 JWT subject 로만 이루어진다 — 바디에 실어 보내지 않는다(CWE-639).
    expect(body).not.toHaveProperty('userNo');
    expect(body).not.toHaveProperty('authority');
  });

  it('인계정보가_없어도_클레임이_성립한다_하위호환', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: {
        accessToken: buildJwt({ sub: '1001', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 }),
        role: 'ADMIN',
        userNo: 1001,
        userName: '',
      },
      message: null,
      errorCode: null,
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    expect(body).not.toHaveProperty('userId');
    expect(body).not.toHaveProperty('userNm');
  });

  it('패스워드_라벨은_계약_축이라_그대로다', async () => {
    // ⚠ 라벨 '관리자 패스워드'·aria-label·API 필드 `adminPassword` 는 역할 호칭이 아니라
    //   "공유 부트스트랩 패스워드"라는 계약·변수 축이다. 화면 문구를 고치며 함께 바꾸지 말 것.
    await renderOpen();
    expect(screen.getByLabelText('관리자 패스워드')).toBeInTheDocument();
  });

  // ─────────────────────────────────────────────────────────────────
  // ★개폐를 진입 시점에 <먼저> 묻는다 (2026-09-07 · @design SCREEN-002 v32 · API-245 · AC-1098)
  //
  //   구 사양 폐기 — *"개폐는 제출 응답으로 가른다"*. 그러면 관리자가 이미 있는 시스템에서도
  //   등록 모습이 무조건 렌더되어 화면이 거짓을 말하고, 사용자는 제출한 뒤에야 거절을 받는다.
  // ─────────────────────────────────────────────────────────────────

  it('★진입하면_창구_개폐를_조회한다', async () => {
    // 조회 자체가 사라지면 화면은 개폐를 알 방법이 없다 — 그 배선을 값이 아니라 <호출>로 고정한다.
    await renderOpen();
    await waitFor(() =>
      expect(mock.history.get.map((r) => r.url)).toContain(AVAILABILITY_PATH),
    );
  });

  it('★창이_닫혀_있으면_관리자가_없다는_안내를_하지_않는다', async () => {
    replaceAvailability((m) =>
      m.onGet(AVAILABILITY_PATH).reply(200, {
        success: true,
        data: { available: false },
        message: null,
        errorCode: null,
      }),
    );

    renderWithProviders(<RoleClaimPage />);
    await screen.findByRole('heading', { name: '권한 요청 안내' });

    // ★핵심 단언 — 이 문장이 닫힌 시스템에 뜨는 것이 고친 결함이다.
    expect(document.body.textContent ?? '').not.toContain(OPEN_ONLY_COPY);
    expect(screen.queryByRole('heading', { name: '관리자 등록' })).toBeNull();
    expect(screen.queryByText('관리자 권한이 부여됩니다')).toBeNull();
  });

  it('★창이_닫혀_있으면_패스워드_입력칸과_등록_버튼을_두지_않는다', async () => {
    // 눌러도 409 만 돌아오는 자리는 안내가 아니라 함정이다.
    replaceAvailability((m) =>
      m.onGet(AVAILABILITY_PATH).reply(200, {
        success: true,
        data: { available: false },
        message: null,
        errorCode: null,
      }),
    );

    renderWithProviders(<RoleClaimPage />);
    await screen.findByRole('heading', { name: '권한 요청 안내' });

    expect(screen.queryByLabelText('관리자 패스워드')).toBeNull();
    expect(screen.queryByRole('button', { name: '관리자로 등록' })).toBeNull();
    // ★부재 단언은 짝이 필요하다 — 「그 자리에 서야 하는 다른 것」이 실제로 섰는지 함께 본다.
    //   안 그러면 화면이 통째로 비어도 위 단언이 통과한다.
    expect(
      screen.getByText('이미 관리자가 있어 최초 관리자 등록 창구가 닫혀 있습니다'),
    ).toBeInTheDocument();
    expect(screen.getByText(/관리자에게 권한을 요청하세요/)).toBeInTheDocument();
  });

  it('★개폐를_확인하기_전에는_어느_모습도_그리지_않는다', async () => {
    // 열림 모습을 먼저 그려 두고 닫힘이면 지우는 방식이면, 그 한 프레임 동안 화면이 거짓을 말한다.
    replaceAvailability((m) =>
      m.onGet(AVAILABILITY_PATH).reply(() => new Promise<never>(() => {})),
    );

    renderWithProviders(<RoleClaimPage />);
    await screen.findByText('등록 창구 상태를 확인하는 중');

    expect(document.body.textContent ?? '').not.toContain(OPEN_ONLY_COPY);
    expect(screen.queryByLabelText('관리자 패스워드')).toBeNull();
  });

  it('★개폐_조회가_401_이면_등록_모습_대신_세션_만료를_알린다', async () => {
    // 인증 실패를 「역할 없음」으로 뭉개면 오류가 사양으로 위장된다.
    replaceAvailability((m) =>
      m.onGet(AVAILABILITY_PATH).reply(401, {
        success: false,
        data: null,
        message: '인증이 필요합니다.',
        errorCode: 'UNAUTHORIZED',
      }),
    );

    renderWithProviders(<RoleClaimPage />);
    await screen.findByText('세션이 만료되었습니다');

    expect(document.body.textContent ?? '').not.toContain(OPEN_ONLY_COPY);
    expect(screen.queryByLabelText('관리자 패스워드')).toBeNull();
  });

  it('★개폐_조회가_장애로_실패하면_등록_모습_대신_오류를_알린다', async () => {
    replaceAvailability((m) => m.onGet(AVAILABILITY_PATH).reply(500));

    renderWithProviders(<RoleClaimPage />);
    await screen.findByText('등록 창구 상태를 확인할 수 없습니다');

    expect(document.body.textContent ?? '').not.toContain(OPEN_ONLY_COPY);
    expect(screen.queryByLabelText('관리자 패스워드')).toBeNull();
    // 세션 만료로 오인시키지 않는다 — 사유가 다르면 사용자가 할 일도 다르다.
    expect(screen.queryByText('세션이 만료되었습니다')).toBeNull();
  });

  it('★조회가_열림이라_답한_뒤_제출이_409_면_등록_수단이_사라진다', async () => {
    // ★경합 갈래 — 사전 조회는 이 갈래를 <없애지 못한다>. 조회와 제출 사이에 다른 사람이 먼저
    //   최초 관리자가 될 수 있다. 그때 화면은 권한 요청 안내로 전환한다.
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(409, {
      success: false,
      data: null,
      message: '이미 관리자가 있어 자가부여가 닫혀 있습니다.',
      errorCode: 'CONFLICT',
    });

    await renderOpen();
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '관리자로 등록' }));

    await screen.findByRole('heading', { name: '권한 요청 안내' });
    expect(screen.queryByLabelText('관리자 패스워드')).toBeNull();
    expect(screen.queryByRole('button', { name: '관리자로 등록' })).toBeNull();
    expect(document.body.textContent ?? '').not.toContain(OPEN_ONLY_COPY);
  });

  it('열려_있을_때만_관리자가_없다는_안내가_나온다', async () => {
    // 위 닫힘 케이스들의 <양성 대조군>. 이것이 없으면 「어떤 경우에도 안 뜬다」로 만들어도 통과한다.
    await renderOpen();
    expect(document.body.textContent ?? '').toContain(OPEN_ONLY_COPY);
  });
});
