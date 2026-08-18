import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
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

describe('RoleClaimPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    localStorage.clear();
    // 권한 자가 부여 화면 진입 전제 — 인증은 됐으나 role 미부여 상태 (claims.role=null).
    useAuthStore.setState({
      token: 'tok',
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

  it('역할_미선택시_확인_버튼_disabled', () => {
    renderWithProviders(<RoleClaimPage />);
    const button = screen.getByRole('button', { name: '권한 부여 확인' });
    expect(button).toBeDisabled();
  });

  it('역할_선택만_하고_패스워드_미입력시도_disabled', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RoleClaimPage />);
    await user.click(screen.getByLabelText('작업자'));
    const button = screen.getByRole('button', { name: '권한 부여 확인' });
    expect(button).toBeDisabled();
  });

  it('패스워드_input_type_password_autocomplete_new_password', () => {
    renderWithProviders(<RoleClaimPage />);
    const passwordInput = screen.getByLabelText('관리자 패스워드') as HTMLInputElement;
    expect(passwordInput.type).toBe('password');
    expect(passwordInput.getAttribute('autocomplete')).toBe('new-password');
  });

  it('정확한_패스워드_입력_성공시_navigate_dashboard', async () => {
    const user = userEvent.setup();
    const newToken = buildJwt({
      sub: '1001',
      role: 'WORKER',
      channel: 'INTERNAL',
      exp: 9999999999,
      name: '홍길동',
    });
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: {
        accessToken: newToken,
        role: 'WORKER',
        userNo: 1001,
        userName: '홍길동',
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<RoleClaimPage />);

    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true });
    });
    // 새 토큰으로 useAuthStore 교체 확인
    expect(useAuthStore.getState().token).toBe(newToken);
    expect(useAuthStore.getState().claims?.role).toBe('WORKER');
  });

  it('잘못된_패스워드_401_에러_메시지_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(401, {
      success: false,
      data: null,
      message: '관리자 패스워드가 일치하지 않습니다.',
      errorCode: 'UNAUTHORIZED',
    });

    renderWithProviders(<RoleClaimPage />);

    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'wrong');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    // 안내는 「분류(제목) + 상세(본문)」 두 줄이다(시안 SCREEN-002 ③).
    // 구 기대값 '관리자 패스워드가 일치하지 않습니다.' 한 줄은 그 구조가 없던 시절의 것.
    await waitFor(() => {
      expect(screen.getByText('패스워드가 일치하지 않습니다')).toBeInTheDocument();
    });
    expect(
      screen.getByText('관리자에게 받은 패스워드를 다시 확인해주세요.'),
    ).toBeInTheDocument();
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('이미_권한_있는_사용자_409_안내_메시지', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(409, {
      success: false,
      data: null,
      message: '이미 권한이 부여된 사용자입니다.',
      errorCode: 'CONFLICT',
    });

    renderWithProviders(<RoleClaimPage />);

    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    // 구 기대값은 두 문장을 한 노드에서 찾던 정규식 — 제목/본문 분리로 더 이상 이어져 있지 않다.
    await waitFor(() => {
      expect(screen.getByText('이미 권한이 부여된 사용자입니다')).toBeInTheDocument();
    });
    expect(screen.getByText('새로고침 해주세요.')).toBeInTheDocument();
  });

  it('429_과다_시도_안내', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(429, {
      success: false,
      data: null,
      message: '시도 횟수가 제한을 초과했습니다.',
      errorCode: 'TOO_MANY_REQUESTS',
    });

    renderWithProviders(<RoleClaimPage />);

    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    await waitFor(() => {
      expect(
        screen.getByText(/시도 횟수가 제한을 초과했습니다/),
      ).toBeInTheDocument();
    });
  });

  it('PORTAL_USER_400_INVALID_INPUT_BE_userMessage_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/auth/role-claim').reply(400, {
      success: false,
      data: null,
      message: 'PORTAL_USER 역할은 본 API 로 부여할 수 없습니다.',
      errorCode: 'INVALID_INPUT',
    });

    renderWithProviders(<RoleClaimPage />);
    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    await waitFor(() => {
      expect(
        screen.getByText(/PORTAL_USER 역할은 본 API 로 부여할 수 없습니다/),
      ).toBeInTheDocument();
    });
  });
  it('REVIEWER_자가부여_옵션이_노출되고_전송된다', async () => {
    // ★2026-08-04 사용자 확정 — REVIEWER 개방. 구 화면은 WORKER 라디오 하나만 두고
    //   "검수자 권한은 자가 부여할 수 없습니다" 안내를 띄웠다.
    const user = userEvent.setup();
    const newToken = buildJwt({
      sub: '1001',
      role: 'REVIEWER',
      channel: 'INTERNAL',
      exp: 9999999999,
      name: '검수자',
    });
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: { accessToken: newToken, role: 'REVIEWER', userNo: 1001, userName: '검수자' },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<RoleClaimPage />);

    await user.click(screen.getByLabelText('검수자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true });
    });
    expect(JSON.parse(mock.history.post[0].data as string).role).toBe('REVIEWER');
    expect(useAuthStore.getState().claims?.role).toBe('REVIEWER');
  });

  /**
   * ★사양 SCREEN-002 — 화면에 보이는 문구는 **한글 호칭뿐**이고, 서버로 보내는 값은
   * WORKER · REVIEWER 그대로다.
   *
   * 구 화면은 라디오 문구가 '작업자 (WORKER)' · '검수자 (REVIEWER)' 라 내부 코드값을 사용자에게
   * 노출했다. 이 가드는 **두 축을 함께** 고정한다 — 문구만 고치고 전송값이 한글로 바뀌면 서버
   * 계약이 깨져 권한 부여 자체가 죽는다. 그래서 한 테스트 안에서 표시와 전송을 같이 본다.
   */
  it('라디오_문구에는_서버_코드값이_없고_전송값은_코드값_그대로다', async () => {
    const user = userEvent.setup();
    const newToken = buildJwt({
      sub: '1001',
      role: 'WORKER',
      channel: 'INTERNAL',
      exp: 9999999999,
      name: '홍길동',
    });
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: { accessToken: newToken, role: 'WORKER', userNo: 1001, userName: '홍길동' },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<RoleClaimPage />);

    // then ① 표시 축 — 역할 라디오 그룹 안에 영문 코드값이 한 글자도 보이지 않는다.
    const radiogroup = screen.getByRole('radiogroup', { name: '역할' });
    expect(radiogroup.textContent ?? '').not.toMatch(/WORKER|REVIEWER/);
    expect(radiogroup.textContent ?? '').toContain('작업자');
    expect(radiogroup.textContent ?? '').toContain('검수자');

    // then ② 계약 축 — 라디오의 value 와 실제 전송값은 서버 코드값 그대로다.
    const workerRadio = screen.getByLabelText('작업자') as HTMLInputElement;
    const reviewerRadio = screen.getByLabelText('검수자') as HTMLInputElement;
    expect(workerRadio.value).toBe('WORKER');
    expect(reviewerRadio.value).toBe('REVIEWER');

    await user.click(workerRadio);
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
    expect(JSON.parse(mock.history.post[0].data as string).role).toBe('WORKER');
  });

  it('관제_인계_표시정보가_클레임_요청에_동봉된다', async () => {
    const user = userEvent.setup();
    localStorage.setItem('userId', 'sjs123');
    localStorage.setItem('userNm', '신재석');
    mock.onPost('/auth/role-claim').reply(200, {
      success: true,
      data: {
        accessToken: buildJwt({ sub: '1001', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 }),
        role: 'WORKER',
        userNo: 1001,
        userName: '신재석',
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<RoleClaimPage />);
    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

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
        accessToken: buildJwt({ sub: '1001', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 }),
        role: 'WORKER',
        userNo: 1001,
        userName: '',
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<RoleClaimPage />);
    await user.click(screen.getByLabelText('작업자'));
    await user.type(screen.getByLabelText('관리자 패스워드'), 'admin1234');
    await user.click(screen.getByRole('button', { name: '권한 부여 확인' }));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    expect(body).not.toHaveProperty('userId');
    expect(body).not.toHaveProperty('userNm');
  });
});
