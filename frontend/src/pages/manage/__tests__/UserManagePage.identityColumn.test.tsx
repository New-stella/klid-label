import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, within } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 사용자 관리 목록의 <식별자 열>과 <검색 안내>. [@design SCREEN-024] (v35 · 2026-09-07 확정)
 *
 * <h3>★왜 「이메일」이 아니라 「로그인ID」인가</h3>
 * 관제 인계 토큰에 <b>이메일 클레임이 없다</b>(실측 계약: sub·iss·role·channel·name·exp).
 * 그래서 관제로 실제 진입한 사용자는 이메일이 <b>영구히 비어 있고</b>, 운영에서 이 열은 전 행이
 * 빈 칸이 된다 — 지금 값이 보이는 행은 전부 개발 시드다. 반면 로그인ID 는 실제로 들어온다.
 *
 * ⚠ 응답·타입의 `email` 필드는 <b>존치</b>한다(계약). 바뀐 것은 <b>표시 축</b>뿐이라, 이 시험은
 * 「응답에 이메일이 실려 와도 그 값을 열로 그리지 않는다」를 함께 고정한다 — 필드가 남아 있으면
 * 다음 사람이 「값이 있으니 보여 주자」로 되돌리기 쉽다.
 */
function setAdmin() {
  useAuthStore.setState({
    token: 'dummy-token',
    claims: { sub: 'u', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 },
  });
}

/** 이메일이 <있는> 행과 로그인ID 가 <없는> 행을 함께 흘린다 — 두 분기를 모두 실행시킨다. */
const ROWS = [
  {
    id: 9001,
    loginId: 'admin',
    name: '시스템관리자',
    email: 'admin@example.invalid',
    role: 'ADMIN',
    active: true,
    createdAt: '2026-05-01T00:00:00Z',
    lastLoginAt: null,
  },
  {
    id: 9000000002,
    loginId: '',
    name: '이름만있는사람',
    role: null,
    active: true,
    createdAt: '2026-05-02T00:00:00Z',
    lastLoginAt: null,
  },
];

describe('UserManagePage — 식별자 열과 검색 안내', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setAdmin();
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: ROWS, totalElements: 2, totalPages: 1, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function renderList() {
    renderWithProviders(<UserManagePage />, { initialEntries: ['/admin/users'] });
    await screen.findByText('시스템관리자');
  }

  it('★열 이름이 「로그인ID」이고 「이메일」 열이 없다', async () => {
    await renderList();

    expect(screen.getByRole('columnheader', { name: '로그인ID' })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: '이메일' })).toBeNull();
  });

  it('★그 열의 값은 로그인ID 다 — 응답에 이메일이 실려 와도 그것을 그리지 않는다', async () => {
    await renderList();

    // 판정 범위를 <그 행>으로 좁힌다 — 화면 전체로 걸면 다른 자리의 같은 낱말에 걸린다.
    const row = screen.getByText('시스템관리자').closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('admin')).toBeInTheDocument();
    expect(within(row as HTMLElement).queryByText('admin@example.invalid')).toBeNull();

    // ⚠ 구 표기 폐기 — *"이메일이 없으면 「{loginId} (로그인ID)」로 대체"*. 열 자체가 로그인ID 가
    //   됐으므로 출처 캡션이 필요 없다. 되살리면 같은 값이 두 이름으로 읽힌다.
    expect(screen.queryByText('(로그인ID)')).toBeNull();
  });

  it('★로그인ID 가 없으면 「—」로 표시한다 — 빈 칸으로 두지 않는다', async () => {
    // 빈 칸은 「로딩 중」과 구분되지 않는다.
    await renderList();

    const row = screen.getByText('이름만있는사람').closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('—')).toBeInTheDocument();
  });

  it('★검색 안내가 이름·아이디 2축을 말한다 — 이메일 축을 안내하지 않는다', async () => {
    await renderList();

    expect(screen.getByPlaceholderText('이름 / 아이디를 입력하세요.')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('이름 / 이메일을 입력하세요.')).toBeNull();
    expect(screen.getByText(/이름 또는 로그인ID 부분일치로 검색합니다/)).toBeInTheDocument();
    expect(screen.queryByText(/이름 또는 이메일 부분일치로 검색합니다/)).toBeNull();
  });
});
