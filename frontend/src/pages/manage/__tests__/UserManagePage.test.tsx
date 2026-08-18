import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { UserManagePage } from '@/pages/manage/UserManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

function setReviewer() {
  useAuthStore.setState({
    token: 'dummy-token',
    claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('UserManagePage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('사용자_관리_검색_필터_동작', async () => {
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 7,
            loginId: 'hong',
            name: '홍길동',
            role: 'WORKER',
            active: true,
            createdAt: '2026-05-01T00:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    await waitFor(() => {
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    const input = screen.getByLabelText('검색');
    await user.type(input, '홍');
    await user.click(screen.getByRole('button', { name: '검색' }));

    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ keyword: '홍' });
    });
  });

  it('역할_필터는_클라이언트_필터가_아니라_서버_파라미터로_전송된다', async () => {
    // given: 구 버그 — roleFilter 가 컴포넌트 로컬 state 로만 존재해 현재 페이지(20건)
    // 안에서만 걸러졌다("CLAUDE.md 목록 필터 정책" 위반 — 전체 기준 서버 필터여야 한다).
    // BE GET /v1/users 는 role 쿼리 파라미터를 이미 지원한다.
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    await waitFor(() => {
      expect(mock.history.get.length).toBeGreaterThan(0);
    });

    await user.click(screen.getByLabelText('역할 필터'));
    await user.click(await screen.findByRole('option', { name: '작업자' }));
    // ★ 확정 단계가 필요하다 — 사양 SCREEN-024 는 "셀렉트를 바꾸는 것만으로 즉시 재조회되지
    //   않음"을 명시한다. 이 클릭이 없으면 role 은 조회에 실리지 않는다(아래 전용 가드가 그
    //   성질을 따로 고정한다). 이 케이스가 지키려는 것은 "역할 필터가 **서버** 파라미터인가"
    //   이며 그 단언은 그대로다.
    await user.click(screen.getByRole('button', { name: '검색' }));

    // then: role=WORKER 가 서버 요청 파라미터로 전송된다
    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ role: 'WORKER' });
    });
  });

  // ── 사양 SCREEN-024 회귀 가드: 필터 확정 시점 ──────────────────────
  // "입력값은 Enter 또는 검색 실행으로 확정되어야 조회에 반영되며(셀렉트를 바꾸는 것만으로
  //  즉시 재조회되지 않음), 확정 시 1페이지로 초기화된다."
  //
  // 구 구현은 역할 select 의 onValueChange 에서 곧바로 updateParams 를 불러 **고르는 즉시**
  // 재조회했다. 검색어는 이미 확정 방식이라 같은 필터바에서 두 컨트롤의 확정 시점이 갈렸다.

  function stubEmptyUsers() {
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });
  }

  it('★역할_셀렉트만_바꾸면_재조회하지_않는다', async () => {
    // given
    stubEmptyUsers();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await waitFor(() => {
      expect(mock.history.get.length).toBeGreaterThan(0);
    });
    const callsBefore = mock.history.get.length;

    // when: 역할만 고르고 확정하지 않는다
    await user.click(screen.getByLabelText('역할 필터'));
    await user.click(await screen.findByRole('option', { name: '작업자' }));

    // then: 요청이 늘지 않는다 — 즉 role 이 실린 조회가 나가지 않았다.
    // (마지막 요청의 params 만 보면 "아직 안 나갔다"와 "나갔는데 role 이 없다"가 구분되지
    //  않으므로 호출 횟수 자체를 고정한다.)
    expect(mock.history.get.length).toBe(callsBefore);
    expect(
      mock.history.get.some((r) => (r.params as { role?: string } | undefined)?.role === 'WORKER'),
    ).toBe(false);
  });

  it('★검색어와_역할을_함께_고른_뒤_Enter로_확정하면_두_축이_같은_요청에_실린다', async () => {
    // given: 확정 진입점은 검색 버튼과 Enter 두 곳이며 결과가 같아야 한다.
    stubEmptyUsers();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await waitFor(() => {
      expect(mock.history.get.length).toBeGreaterThan(0);
    });

    // when
    await user.click(screen.getByLabelText('역할 필터'));
    await user.click(await screen.findByRole('option', { name: '검수자' }));
    await user.type(screen.getByLabelText('검색'), '홍{Enter}');

    // then: 검색어만 실리고 역할이 누락되는(구 동작의 어긋남) 일이 없다
    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ keyword: '홍', role: 'REVIEWER', page: 0 });
    });
  });

  it('★필터_초기화는_상시_노출되며_활성_필터가_없으면_비활성이다', async () => {
    // given: 사양 note 는 "하나라도 활성일 때만 눌림 가능" — **존재** 조건이 아니라 **눌림**
    // 조건이다. 구 구현은 아예 렌더하지 않아 버튼이 나타났다 사라지며 옆 '검색' 버튼 위치가
    // 흔들렸다.
    stubEmptyUsers();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await waitFor(() => {
      expect(mock.history.get.length).toBeGreaterThan(0);
    });

    // then: 필터가 비어 있어도 버튼은 화면에 있고, 비활성이다
    const reset = screen.getByRole('button', { name: '필터 초기화' });
    expect(reset).toBeInTheDocument();
    expect(reset).toBeDisabled();

    // when: 확정 전이라도 값을 고르면 되돌릴 수 있어야 한다(확정값만 보면 여기서 잠긴다)
    await user.click(screen.getByLabelText('역할 필터'));
    await user.click(await screen.findByRole('option', { name: '작업자' }));

    // then
    expect(screen.getByRole('button', { name: '필터 초기화' })).toBeEnabled();
  });

  it('상태(활성_비활성)_필터_컨트롤은_존재하지_않는다', async () => {
    // given: 사양(SCREEN-024) — "상태 필터는 두지 않는다. 활성 상태는 관제서버 소유값"
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await waitFor(() => {
      expect(mock.history.get.length).toBeGreaterThan(0);
    });

    expect(screen.queryByLabelText('상태 필터')).not.toBeInTheDocument();
  });

  // ── 사양 SCREEN-024 정합 회귀 가드 ─────────────────────────────────

  /** 역할 미배정(BE `UserSummaryResponse.from` 이 role=null 로 내려보내는) 사용자 1건. */
  function stubUnassignedUser() {
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 9,
            loginId: 'newbie',
            name: '신규사용자',
            role: null,
            active: true,
            createdAt: '2026-05-01T00:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  }

  it('역할이_없는_사용자는_미배정으로_표시된다', async () => {
    // given: 관제 인계 키에 역할 클레임이 없는 자동등록 사용자(role=null).
    // 구 구현은 이 null 을 그대로 흘려보내 라벨 없는 빈 회색 배지를 그렸다 —
    // "역할이 없다"와 "값을 못 읽었다"가 화면에서 구분되지 않았다.
    stubUnassignedUser();

    // when
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // then
    const badge = await screen.findByTestId('user-role-unassigned-9');
    expect(badge).toHaveTextContent('미배정');
  });

  it('미배정_사용자_수정모달은_안내와_함께_저장을_잠근다', async () => {
    // given
    stubUnassignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await screen.findByTestId('user-role-unassigned-9');

    // when: 수정 모달을 연다
    await user.click(screen.getByRole('button', { name: '수정' }));

    // then: 임의 기본값(WORKER)을 미리 채우지 않는다 — 그러면 사용자가 고른 적 없는 역할이
    // 저장될 수 있다. 선택 전까지 저장은 잠기고 왜 잠겼는지 안내가 뜬다.
    expect(
      await screen.findByTestId('edit-user-unassigned-notice'),
    ).toHaveTextContent('아직 역할이 배정되지 않은 사용자입니다');
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  // ── 사양 SCREEN-024 회귀 가드: "역할을 선택하지 않았거나 원래 값과 같으면 비활성화된다" ──
  // 구 구현은 **미선택만** 막아, 아무것도 바꾸지 않은 채 저장을 눌러 PATCH 없는 빈 왕복을
  // 만들 수 있었다(요청은 나가지 않고 모달만 닫혀, 눌렀는데 아무 일도 없는 것처럼 보인다).

  /** 역할이 이미 배정된(WORKER) 사용자 1건 — '원래 값과 같음' 축을 보려면 초기값이 있어야 한다. */
  function stubAssignedUser() {
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 11,
            loginId: 'worker1',
            name: '김작업',
            role: 'WORKER',
            active: true,
            createdAt: '2026-05-01T00:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  }

  /** 수정 모달을 열고 그 안의 역할 select 트리거를 돌려준다(목록 필터의 select 와 구분). */
  async function openEditModal(user: UserEvent): Promise<HTMLElement> {
    await user.click(await screen.findByRole('button', { name: '수정' }));
    const dialog = await screen.findByRole('dialog');
    return within(dialog).getByRole('combobox');
  }

  it('원래_역할_그대로면_저장이_잠기고_사유를_밝힌다', async () => {
    // given: 역할이 이미 WORKER 인 사용자
    stubAssignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // when: 아무것도 바꾸지 않고 모달만 연다
    await openEditModal(user);

    // then: 저장은 잠기고 **왜 잠겼는지** 화면이 말한다(이유 없이 잠긴 버튼은 고장으로 읽힌다)
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByTestId('edit-user-unchanged-notice')).toHaveTextContent(
      '변경된 내용이 없습니다',
    );
  });

  it('다른_역할로_바꾸면_저장이_풀리고_안내가_사라진다', async () => {
    // given
    stubAssignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    const trigger = await openEditModal(user);

    // when: WORKER → REVIEWER
    await selectRadixOption(user, trigger, '검수자');

    // then
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    });
    expect(screen.queryByTestId('edit-user-unchanged-notice')).toBeNull();
  });

  it('바꿨다가_원래_역할로_되돌리면_저장이_다시_잠긴다', async () => {
    // given: 되돌림까지 봐야 판정이 "한 번이라도 건드렸는가"가 아니라 "지금 값이 원래와
    // 같은가"임이 고정된다(dirty 플래그로 구현하면 이 케이스에서 저장이 열린 채 남는다).
    stubAssignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    const trigger = await openEditModal(user);

    await selectRadixOption(user, trigger, '검수자');
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    });

    // when: 원래 값(작업자)으로 되돌린다
    await selectRadixOption(user, trigger, '작업자');

    // then
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    });
    expect(screen.getByTestId('edit-user-unchanged-notice')).toBeInTheDocument();
  });

  it('미선택_차단은_그대로_동작하고_변경없음_안내와_겹치지_않는다', async () => {
    // given: 미배정 사용자 — 두 잠금 사유가 동시에 뜨면 사용자는 무엇을 해야 할지 모른다.
    stubUnassignedUser();
    const user = userEvent.setup();
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // when
    await openEditModal(user);

    // then: 미선택 사유만 뜬다
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByTestId('edit-user-unassigned-notice')).toBeInTheDocument();
    expect(screen.queryByTestId('edit-user-unchanged-notice')).toBeNull();
  });

  // ── 사양 SCREEN-024 회귀 가드: 등록일 · 최신 로그인은 **각각 별도 컬럼**이다 ──
  // 구 구현은 컬럼이 하나뿐이었고 `lastLoginAt ?? createdAt` 로 폴백해, 로그인 기록이
  // 존재하지 않던 동안 **등록일 값을 "최근 로그인" 헤더로 표시**했다(거짓 표기).
  // 두 값은 용도가 다르다 — 등록일은 가입 이력, 최신 로그인은 휴면 계정 판단.

  /** 두 날짜 축을 모두 가진 사용자 1건. BE 는 타임존 없는 LocalDateTime 을 내려준다. */
  function stubUserWithBothDates() {
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 21,
            loginId: 'both',
            name: '두날짜',
            role: 'WORKER',
            active: true,
            createdAt: '2026-05-01T09:00:00',
            lastLoginAt: '2026-08-16T14:30:00',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  }

  it('등록일과_최신_로그인이_각각_별도_컬럼으로_표시된다', async () => {
    // given
    stubUserWithBothDates();

    // when
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // then: 두 헤더가 동시에 존재한다(하나가 다른 하나를 대체하지 않는다)
    expect(await screen.findByRole('columnheader', { name: '등록일' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '최신 로그인' })).toBeInTheDocument();

    // 각 셀은 자기 축의 값을 그린다
    expect(screen.getByTestId('user-created-at-21')).toHaveTextContent('2026. 5. 1.');
    expect(screen.getByTestId('user-last-login-21')).toHaveTextContent('2026. 8. 16.');
  });

  it('최신_로그인이_없으면_등록일로_대체하지_않는다', async () => {
    // given: 한 번도 접속하지 않은 계정 — BE 가 lastLoginAt 을 null 로 내려준다.
    //   ★ 이 케이스가 이번 결함의 회귀 가드다. 등록일 값이 최신 로그인 셀에 나타나면 실패한다.
    mock.onGet('/users').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 22,
            loginId: 'never',
            name: '미접속',
            role: 'WORKER',
            active: true,
            createdAt: '2026-05-01T09:00:00',
            lastLoginAt: null,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    // when
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // then: 등록일은 그대로 보이고, 최신 로그인은 **명시적 미접속 표기**다
    expect(await screen.findByTestId('user-created-at-22')).toHaveTextContent('2026. 5. 1.');
    const lastLogin = screen.getByTestId('user-last-login-22');
    expect(lastLogin).toHaveTextContent('-');
    expect(lastLogin).not.toHaveTextContent('2026. 5. 1.');
  });

  it('두_날짜_컬럼의_표기_형식이_같다', async () => {
    // given: 같은 표에서 두 날짜가 다른 형식으로 보이면 비교가 불가능하다 —
    //   두 컬럼은 같은 포매터를 재사용해야 한다.
    stubUserWithBothDates();

    // when
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });

    // then: 같은 ko-KR 날짜 형식(YYYY. M. D.)
    const KO_DATE = /^\d{4}\. \d{1,2}\. \d{1,2}\.$/;
    expect((await screen.findByTestId('user-created-at-21')).textContent?.trim()).toMatch(KO_DATE);
    expect(screen.getByTestId('user-last-login-21').textContent?.trim()).toMatch(KO_DATE);
  });

  it('헤더_부제는_동적_카운트가_아니라_고정_문구다', async () => {
    // given: 사양 — "부제는 정적 텍스트이며 전체 사용자 수 등 동적 수치는 표시하지 않는다".
    // 동적 카운트는 로딩 중 '전체 0명'이 사실처럼 읽히는 문제가 있었다.
    stubUnassignedUser();

    // when
    renderWithProviders(<UserManagePage />, { initialEntries: ['/manage/users'] });
    await screen.findByTestId('user-role-unassigned-9');

    // then
    expect(screen.getByText('시스템 사용자 계정을 관리합니다.')).toBeInTheDocument();
    expect(screen.queryByText(/전체 \d+명/)).toBeNull();
  });
});
