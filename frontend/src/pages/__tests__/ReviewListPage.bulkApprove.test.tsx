import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ReviewListPage } from '@/pages/ReviewListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 검수 목록 — 점유 표시 · 선택 · 일괄 검수완료(SCREEN-018 · API-250 · AC-1113 · AC-1117).
 *
 * ★이 파일이 지키는 것
 *  - 고를 수 있는 것은 **내가 잡고 있는 영상뿐**이고, 고를 수 없는 행은 **이유가 보인다**.
 *  - 머리행 전체선택이 **고를 수 없는 행을 건너뛴다**.
 *  - 상한은 **응답에서 받은 값**이며 화면이 숫자를 스스로 갖지 않는다.
 *  - 실패 사유는 **서버가 보낸 문장 그대로**이고, 전건 실패도 오류 화면이 아니다.
 */

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual =
    await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

/** 로그인한 사람의 사번 — 「내가 검수 중」 판정의 기준. */
const MY_USER_NO = 9001;

/** 스토어 적재용 더미 토큰 — 이름 때문에 상수로 뺀다(시크릿 필터 훅). */
const DUMMY_JWT = 'tok';

interface RowOverrides {
  videoId?: number;
  cctvName?: string;
  status?: string;
  needsRecheck?: boolean;
  reviewingUserId?: number | null;
  reviewingUserName?: string | null;
  reviewStartedAt?: string | null;
  lastApproverId?: number | null;
  lastApproverName?: string | null;
  lastApproverRole?: string | null;
  lastApprovedAt?: string | null;
  bulkApprovable?: boolean;
}

function reviewRow(o: RowOverrides = {}) {
  const videoId = o.videoId ?? 10;
  return {
    // BE 는 id 와 videoId 에 **같은 값**(RAW_SN)을 싣는다.
    id: videoId,
    videoId,
    cctvName: o.cctvName ?? 'CCTV-A',
    workerId: 7,
    workerName: '홍길동',
    submittedAt: '2026-05-07T10:00:00Z',
    labelCount: 12,
    status: o.status ?? 'REVIEW_PENDING',
    eventName: '쓰러짐',
    eventTypeCd: 'EVT_FALL',
    needsRecheck: o.needsRecheck ?? false,
    reviewingUserId: o.reviewingUserId ?? null,
    reviewingUserName: o.reviewingUserName ?? null,
    reviewStartedAt: o.reviewStartedAt ?? null,
    lastApproverId: o.lastApproverId ?? null,
    lastApproverName: o.lastApproverName ?? null,
    lastApproverRole: o.lastApproverRole ?? null,
    lastApprovedAt: o.lastApprovedAt ?? null,
    bulkApprovable: o.bulkApprovable ?? false,
  };
}

/** 내가 잡고 있어 고를 수 있는 행. */
function mineRow(o: RowOverrides = {}) {
  return reviewRow({
    status: 'REVIEWING',
    reviewingUserId: MY_USER_NO,
    reviewingUserName: '나검수',
    reviewStartedAt: '2026-05-07T11:00:00Z',
    bulkApprovable: true,
    ...o,
  });
}

/** 남이 잡고 있어 고를 수 없는 행. */
function othersRow(o: RowOverrides = {}) {
  return reviewRow({
    status: 'REVIEWING',
    reviewingUserId: 7,
    reviewingUserName: '김검수',
    reviewStartedAt: '2026-05-07T11:00:00Z',
    bulkApprovable: false,
    ...o,
  });
}

/**
 * 목록 응답 — `bulkApproveLimit` 은 **항목마다가 아니라 응답 한 번에 하나**다(AC-1117).
 */
function listPage(
  content: ReturnType<typeof reviewRow>[],
  bulkApproveLimit?: number,
) {
  return {
    success: true,
    data: {
      content,
      totalElements: content.length,
      totalPages: 1,
      number: 0,
      size: 20,
      ...(bulkApproveLimit === undefined ? {} : { bulkApproveLimit }),
    },
    message: null,
    errorCode: null,
  };
}

describe('검수 목록 — 점유 표시와 일괄 검수완료', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    navigateMock.mockReset();
    mock = new MockAdapter(apiClient);
    mock.onGet('/reviews/summary').reply(200, {
      success: true,
      data: { total: 0, pending: 0, inReview: 0, approved: 0, rejected: 0 },
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: DUMMY_JWT,
      claims: {
        sub: String(MY_USER_NO),
        role: 'REVIEWER',
        channel: 'INTERNAL',
        exp: 9999999999,
      },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function renderList() {
    return renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });
  }

  /** 영상명으로 그 행을 집는다. */
  async function rowOf(cctvName: string): Promise<HTMLElement> {
    const cell = await screen.findByText(cctvName);
    const tr = cell.closest('tr');
    if (!tr) throw new Error(`${cctvName} 행을 찾지 못했다`);
    return tr as HTMLElement;
  }

  // ── 점유 표시 ──────────────────────────────────────────────────

  it('내가_잡은_행과_남이_잡은_행을_구분해_보이고_아무도_안_잡았으면_비운다', async () => {
    mock
      .onGet('/reviews')
      .reply(200, listPage([
        mineRow({ videoId: 1, cctvName: 'CCTV-내것' }),
        othersRow({ videoId: 2, cctvName: 'CCTV-남의것' }),
        reviewRow({ videoId: 3, cctvName: 'CCTV-빈것' }),
      ], 20));

    renderList();

    expect(await screen.findByTestId('review-claim-1')).toHaveTextContent('내가 검수 중');
    expect(screen.getByTestId('review-claim-2')).toHaveTextContent('김검수 검수 중');
    // 아무도 잡지 않았거나 유예로 풀린 행은 점유 표시를 두지 않는다.
    expect(screen.queryByTestId('review-claim-3')).toBeNull();
  });

  it('최근_승인자는_역할과_함께_보이고_역할이_빈_옛_기록은_빈_괄호를_남기지_않는다', async () => {
    mock.onGet('/reviews').reply(200, listPage([
      reviewRow({
        videoId: 1,
        cctvName: 'CCTV-관리자승인',
        status: 'COMPLETED',
        lastApproverId: 3,
        lastApproverName: '박관리',
        lastApproverRole: 'ADMIN',
        lastApprovedAt: '2026-05-08T09:00:00Z',
      }),
      reviewRow({
        videoId: 2,
        cctvName: 'CCTV-옛기록',
        status: 'COMPLETED',
        lastApproverId: 4,
        lastApproverName: '이검수',
        // 이 축이 생기기 전에 쌓인 이력 — 역할을 백필하지 않았다.
        lastApproverRole: null,
        lastApprovedAt: '2026-05-08T09:00:00Z',
      }),
      reviewRow({ videoId: 3, cctvName: 'CCTV-승인없음' }),
    ], 20));

    renderList();

    // 역할은 **승인 시점에 기록된 값**이다 — 관리자가 승인한 건은 관리자로 남는다.
    expect(await screen.findByTestId('review-last-approval-1')).toHaveTextContent(
      '박관리(관리자)',
    );

    const old = screen.getByTestId('review-last-approval-2');
    expect(old).toHaveTextContent('이검수');
    // ★빈 괄호를 남기지 않는다 — 역할이 있는데 못 읽은 것처럼 보이면 안 된다.
    expect(old.textContent).not.toContain('()');

    expect(screen.queryByTestId('review-last-approval-3')).toBeNull();
  });

  // ── 선택 ───────────────────────────────────────────────────────

  it('남이_잡은_행은_체크되지_않고_그_이유가_보인다', async () => {
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-내것' }),
      othersRow({ videoId: 2, cctvName: 'CCTV-남의것' }),
    ], 20));

    renderList();

    const mine = await screen.findByTestId('review-select-1');
    const others = await screen.findByTestId('review-select-2');

    expect(mine).toBeEnabled();
    expect(others).toBeDisabled();

    // ★체크칸만 꺼 두면 왜 안 되는지 알 길이 없다 — 사유가 **체크칸 자리에** 함께 서야 한다.
    //
    // ⚠ 행 단위로 찾으면 안 된다 — 같은 문구가 「검수 중」 칸에도 있어 둘이 구분되지 않는다.
    //   그 둘은 서로 다른 것을 말한다(한쪽은 상태, 한쪽은 고를 수 없는 사유)므로 각각 본다.
    const selectCell = others.closest('td');
    expect(selectCell).not.toBeNull();
    expect(within(selectCell as HTMLElement).getByText('김검수 검수 중')).toBeInTheDocument();

    // 고를 수 있는 행의 체크칸에는 사유를 두지 않는다(둘 다 있으면 자기모순이다).
    const mineCell = mine.closest('td');
    expect(within(mineCell as HTMLElement).queryByText(/검수 중/)).toBeNull();
  });

  it('아무도_잡지_않은_행은_검수를_시작하라고_안내한다', async () => {
    mock
      .onGet('/reviews')
      .reply(200, listPage([reviewRow({ videoId: 3, cctvName: 'CCTV-빈것' })], 20));

    renderList();

    expect(await screen.findByTestId('review-select-3')).toBeDisabled();
    expect(
      within(await rowOf('CCTV-빈것')).getByText('검수 시작 후 선택할 수 있습니다'),
    ).toBeInTheDocument();
  });

  it('머리행_전체선택은_고를_수_있는_행만_고른다', async () => {
    const user = userEvent.setup();
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-내것1' }),
      mineRow({ videoId: 2, cctvName: 'CCTV-내것2' }),
      othersRow({ videoId: 3, cctvName: 'CCTV-남의것' }),
      reviewRow({ videoId: 4, cctvName: 'CCTV-빈것' }),
    ], 20));

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));

    // 고를 수 있는 두 건만 담긴다 — 실행줄 건수가 그것을 말한다.
    expect(await screen.findByTestId('bulk-approve-bar')).toHaveTextContent('선택한 2건');
    expect(screen.getByTestId('review-select-1')).toBeChecked();
    expect(screen.getByTestId('review-select-2')).toBeChecked();
    expect(screen.getByTestId('review-select-3')).not.toBeChecked();
    expect(screen.getByTestId('review-select-4')).not.toBeChecked();

    // 머리행이 **완전 선택**으로 보인다 — 다 골랐는데 덜 고른 것처럼 보이면 다시 누를 때
    // 해제가 아니라 재선택이 되어 토글이 뒤집힌다.
    //
    // ⚠ 이 단언은 「전체선택이 고를 수 없는 행을 건너뛰는가」를 지키지 **못한다**. 중간 상태
    //   판정이 이미 걸러진 집합(`selectedRows`)을 세기 때문에, 선택 집합에 비자격 행이 섞여도
    //   숫자가 같아 항상 완전 선택으로 보인다. 그 축은 아래 「전체선택 뒤 자격이 켜진 행이
    //   섞이지 않는다」가 값으로 판정한다.
    expect(screen.getByTestId('review-select-all')).toHaveAttribute('aria-checked', 'true');
  });

  it('★전체선택은_고를_수_없는_행을_집합에_담지_않는다_뒤늦게_자격이_켜져도_섞이지_않는다', async () => {
    // ★이 축은 **화면 상태로는 판정되지 않는다** — 체크칸도 건수도 이미 걸러진 집합을 보여
    //   바깥층이 흡수한다. 갈리는 순간은 「집합에 담긴 뒤 그 행의 자격이 켜질 때」뿐이다.
    //   실제로 일어난다: 남이 놓은 영상을 내가 다른 탭에서 잡으면 자격이 켜진다.
    //   그때 전체선택이 비자격 행까지 담아 뒀다면 **내가 고른 적 없는 영상이 조용히 대상에 든다**.
    const user = userEvent.setup();
    mock
      .onGet('/reviews')
      .replyOnce(200, listPage([
        mineRow({ videoId: 1, cctvName: 'CCTV-내것' }),
        othersRow({ videoId: 3, cctvName: 'CCTV-남의것' }),
      ], 20))
      .onGet('/reviews')
      .reply(200, listPage([
        mineRow({ videoId: 1, cctvName: 'CCTV-내것' }),
        // 남이 놓았고 이제 내가 잡았다 — 자격이 켜진다.
        mineRow({ videoId: 3, cctvName: 'CCTV-남의것' }),
      ], 20));

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    expect(await screen.findByTestId('bulk-approve-bar')).toHaveTextContent('선택한 1건');

    // 재조회 — 3번 행의 자격이 켜진다.
    await user.click(screen.getByRole('button', { name: /새로고침/ }));
    await waitFor(() => {
      expect(screen.getByTestId('review-select-3')).toBeEnabled();
    });

    // ★고른 적 없는 3번이 대상에 들어오면 안 된다.
    expect(screen.getByTestId('bulk-approve-bar')).toHaveTextContent('선택한 1건');
    expect(screen.getByTestId('review-select-3')).not.toBeChecked();
  });

  it('전체선택을_다시_누르면_해제된다', async () => {
    // 위 「완전 선택」 판정이 어긋나면 이 토글이 조용히 깨진다(누를 때마다 다시 선택된다).
    const user = userEvent.setup();
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-내것1' }),
      othersRow({ videoId: 3, cctvName: 'CCTV-남의것' }),
    ], 20));

    renderList();

    // ⚠ 머리행 체크칸은 상태가 바뀌면 다시 만들어진다 — 먼저 잡아 둔 참조를 다시 누르면
    //    이미 떨어져 나간 노드를 누르게 되어 아무 일도 일어나지 않는다. 누를 때마다 다시 찾는다.
    await user.click(await screen.findByTestId('review-select-all'));
    expect(await screen.findByTestId('bulk-approve-bar')).toHaveTextContent('선택한 1건');

    await user.click(await screen.findByTestId('review-select-all'));
    await waitFor(() => {
      expect(screen.queryByTestId('bulk-approve-bar')).toBeNull();
    });
  });

  it('한_건도_고르지_않으면_실행줄이_보이지_않는다', async () => {
    mock
      .onGet('/reviews')
      .reply(200, listPage([mineRow({ videoId: 1, cctvName: 'CCTV-내것' })], 20));

    renderList();

    await screen.findByText('CCTV-내것');
    expect(screen.queryByTestId('bulk-approve-bar')).toBeNull();
  });

  // ── 상한 ───────────────────────────────────────────────────────

  it('상한을_넘게_고르면_실행이_막히고_응답으로_받은_상한이_문구에_나온다', async () => {
    const user = userEvent.setup();
    // ★상한은 **응답에서 받은 값**이다 — 화면이 숫자를 스스로 갖지 않는다(AC-1117).
    //   여기서 2 를 내려보내므로, 화면이 숫자를 박아 뒀다면 이 케이스가 죽는다.
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
      mineRow({ videoId: 2, cctvName: 'CCTV-2' }),
      mineRow({ videoId: 3, cctvName: 'CCTV-3' }),
    ], 2));

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));

    expect(await screen.findByTestId('bulk-approve-bar')).toHaveTextContent('선택한 3건');
    expect(screen.getByTestId('bulk-approve-run')).toBeDisabled();

    const alert = screen.getByTestId('bulk-approve-limit-alert');
    // 상한 숫자가 응답값 그대로 나와야 한다.
    expect(alert).toHaveTextContent('2건');
    expect(alert).not.toHaveTextContent('3건');
    // ★버튼이 잠긴 사유는 `disabled` 속성만으로 전달되지 않는다 — 보조기술에 **알려야** 한다.
    expect(alert).toHaveAttribute('role', 'alert');
  });

  it('★상한을_아직_못_받았으면_막지_않는다_fail_open', async () => {
    // ★화면의 제한은 **인가 판정이 아니다**. 실제 강제는 일괄 승인 창구가 그대로 하므로,
    //   상한을 모른다고 미리 잠그면 **되는 것까지 막힌다**(일괄 검수완료가 통째로 죽는다).
    //   이 방향이 조용히 뒤집히는 것을 막는 것이 이 케이스의 전부다.
    const user = userEvent.setup();
    // 응답에 `bulkApproveLimit` 자체가 없다 — 구 배포본·미배선 구간에서 실제로 이 모양이다.
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
      mineRow({ videoId: 2, cctvName: 'CCTV-2' }),
      mineRow({ videoId: 3, cctvName: 'CCTV-3' }),
    ]));

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));

    expect(await screen.findByTestId('bulk-approve-run')).toBeEnabled();
    expect(screen.queryByTestId('bulk-approve-limit-alert')).toBeNull();
  });

  it('상한_안이면_실행이_열리고_상한_안내가_없다', async () => {
    const user = userEvent.setup();
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
      mineRow({ videoId: 2, cctvName: 'CCTV-2' }),
    ], 2));

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));

    expect(await screen.findByTestId('bulk-approve-run')).toBeEnabled();
    expect(screen.queryByTestId('bulk-approve-limit-alert')).toBeNull();
  });

  // ── 실행 ───────────────────────────────────────────────────────

  it('실행은_확인_창을_거치며_확인_전에는_아무것도_보내지_않는다', async () => {
    const user = userEvent.setup();
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
      mineRow({ videoId: 2, cctvName: 'CCTV-2' }),
    ], 20));
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: {
        successCount: 2,
        failureCount: 0,
        results: [
          { videoId: 1, success: true, errorCode: null, reason: null },
          { videoId: 2, success: true, errorCode: null, reason: null },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));

    // 확인 창이 대상 목록을 보여준다 — 건수만 보이면 잘못 고른 것을 확인할 수 없다.
    const body = await screen.findByTestId('bulk-approve-confirm-body');
    expect(body).toHaveTextContent('선택한 2건을 검수완료합니다.');
    const targets = within(screen.getByTestId('bulk-approve-target-list'));
    expect(targets.getByText('CCTV-1')).toBeInTheDocument();
    expect(targets.getByText('CCTV-2')).toBeInTheDocument();

    // ★확인 전에는 한 건도 나가지 않는다 — 되돌릴 수 없는 처리를 한 번의 클릭으로 시작하지 않는다.
    expect(mock.history.post.filter((r) => r.url === '/reviews/batch/approve')).toHaveLength(0);

    await user.click(screen.getByTestId('bulk-approve-confirm'));

    await waitFor(() => {
      expect(mock.history.post.filter((r) => r.url === '/reviews/batch/approve')).toHaveLength(1);
    });
  });

  it('고른_영상의_식별자를_그대로_보낸다', async () => {
    const user = userEvent.setup();
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 101, cctvName: 'CCTV-1' }),
      mineRow({ videoId: 202, cctvName: 'CCTV-2' }),
      othersRow({ videoId: 303, cctvName: 'CCTV-남의것' }),
    ], 20));
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: { successCount: 2, failureCount: 0, results: [] },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));
    await user.click(await screen.findByTestId('bulk-approve-confirm'));

    await waitFor(() => {
      expect(mock.history.post.filter((r) => r.url === '/reviews/batch/approve')).toHaveLength(1);
    });

    const sent = mock.history.post.find((r) => r.url === '/reviews/batch/approve');
    // ★본문뿐 아니라 **주소**도 단언한다 — 본문만 보면 창구를 바꾸는 변이를 통과시킨다.
    expect(sent?.url).toBe('/reviews/batch/approve');
    // 남이 잡은 건은 애초에 담기지 않는다 — 화면 상태가 아니라 **나간 값**으로 판정한다.
    expect(JSON.parse(sent?.data as string)).toEqual({ videoIds: [101, 202] });
    expect(JSON.parse(sent?.data as string).videoIds).not.toContain(303);
  });

  it('★재조회로_자격이_꺼진_행은_옛_선택이_남아_있어도_대상에서_빠진다', async () => {
    // ★교집합(「지금 화면에 있고 고를 수 있는 행」)이 **스스로 존재 이유로 든 시나리오**가
    //   바로 이것이다. 그런데 기존 시험은 재조회 응답이 같은 행을 계속 자격 있음으로 돌려줘
    //   이 경로를 **한 번도 지나가지 않았다** — 교집합을 통째로 지워도 전건 초록이었다.
    const user = userEvent.setup();
    mock
      .onGet('/reviews')
      .replyOnce(200, listPage([
        mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
        mineRow({ videoId: 2, cctvName: 'CCTV-2' }),
      ], 20))
      .onGet('/reviews')
      .reply(200, listPage([
        mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
        // 실패한 그 건을 남이 먼저 집어갔다 — 이제 내가 고를 수 없다.
        othersRow({ videoId: 2, cctvName: 'CCTV-2' }),
      ], 20));
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: {
        successCount: 1,
        failureCount: 1,
        results: [
          { videoId: 1, success: true, errorCode: null, reason: null },
          {
            videoId: 2,
            success: false,
            errorCode: 'CONFLICT',
            reason: '다른 검수자가 검수 중인 영상입니다.',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));
    await user.click(await screen.findByTestId('bulk-approve-confirm'));

    // 실패한 2번만 다시 고른 상태로 돌아간다 — 그런데 그 사이 2번의 자격이 꺼졌다.
    await user.click(await screen.findByTestId('bulk-approve-retry-failed'));

    await waitFor(() => {
      expect(screen.getByTestId('review-select-2')).toBeDisabled();
    });

    // ★고를 수 없게 된 행은 실행 대상에서 빠진다 — 남은 것이 없으니 실행줄 자체가 사라진다.
    //   여기가 살아 있으면 **화면에 고를 수 없다고 표시된 영상을 보내게 된다**.
    expect(screen.queryByTestId('bulk-approve-bar')).toBeNull();
    // 체크 표시도 남지 않는다 — 꺼진 칸에 체크만 남으면 자기모순 화면이다.
    expect(screen.getByTestId('review-select-2')).not.toBeChecked();
  });

  // ── 결과 ───────────────────────────────────────────────────────

  it('부분_실패는_성공_실패_건수와_서버가_보낸_사유를_함께_보인다', async () => {
    const user = userEvent.setup();
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
      mineRow({ videoId: 2, cctvName: 'CCTV-2' }),
      mineRow({ videoId: 3, cctvName: 'CCTV-3' }),
    ], 20));
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: {
        successCount: 1,
        failureCount: 2,
        results: [
          { videoId: 1, success: true, errorCode: null, reason: null },
          // ★같은 사유 코드에 서로 다른 사유가 온다 — 코드로 문장을 지어내면 둘이 같아진다.
          {
            videoId: 2,
            success: false,
            errorCode: 'CONFLICT',
            reason: '다른 검수자가 검수 중인 영상입니다.',
          },
          {
            videoId: 3,
            success: false,
            errorCode: 'CONFLICT',
            reason: '비식별 처리가 완료되지 않았습니다.',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));
    await user.click(await screen.findByTestId('bulk-approve-confirm'));

    const counts = await screen.findByTestId('bulk-approve-result-counts');
    expect(counts).toHaveTextContent('성공 1건');
    expect(counts).toHaveTextContent('실패 2건');

    const body = within(screen.getByTestId('bulk-approve-result-body'));
    // 사유는 서버가 보낸 문장 그대로이고, 두 문장이 서로 달라야 한다.
    expect(body.getByText('다른 검수자가 검수 중인 영상입니다.')).toBeInTheDocument();
    expect(body.getByText('비식별 처리가 완료되지 않았습니다.')).toBeInTheDocument();
    // 실패한 영상의 이름도 함께 보인다 — 무엇이 안 됐는지 알 수 있어야 한다.
    expect(body.getByText('CCTV-2')).toBeInTheDocument();
    expect(body.getByText('CCTV-3')).toBeInTheDocument();
  });

  it('한_건도_성공하지_못해도_오류_화면이_아니라_결과로_알린다', async () => {
    const user = userEvent.setup();
    mock
      .onGet('/reviews')
      .reply(200, listPage([mineRow({ videoId: 1, cctvName: 'CCTV-1' })], 20));
    // ★전건 실패도 200 이다 — 요청 자체는 받아들여졌다(API-250).
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: {
        successCount: 0,
        failureCount: 1,
        results: [
          {
            videoId: 1,
            success: false,
            errorCode: 'CONFLICT',
            reason: '다른 검수자가 먼저 처리했습니다.',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));
    await user.click(await screen.findByTestId('bulk-approve-confirm'));

    const counts = await screen.findByTestId('bulk-approve-result-counts');
    expect(counts).toHaveTextContent('성공 0건');
    expect(counts).toHaveTextContent('실패 1건');
    expect(
      within(screen.getByTestId('bulk-approve-result-body')).getByText(
        '다른 검수자가 먼저 처리했습니다.',
      ),
    ).toBeInTheDocument();
  });

  it('결과_창을_닫으면_목록을_다시_읽는다', async () => {
    // 처리된 건의 상태·점유 표시가 바뀌어 있다 — 닫은 뒤 옛 화면이 남으면 이미 승인된 영상을
    // 다시 고르게 된다. (무효화가 이미 신선도를 주지만 그것과 **별개 축**이라 따로 고정한다.)
    const user = userEvent.setup();
    mock
      .onGet('/reviews')
      .reply(200, listPage([mineRow({ videoId: 1, cctvName: 'CCTV-1' })], 20));
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: {
        successCount: 1,
        failureCount: 0,
        results: [{ videoId: 1, success: true, errorCode: null, reason: null }],
      },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));
    await user.click(await screen.findByTestId('bulk-approve-confirm'));
    await screen.findByTestId('bulk-approve-result-counts');

    const before = mock.history.get.filter((r) => r.url === '/reviews').length;
    await user.click(screen.getByTestId('bulk-approve-result-close'));

    await waitFor(() => {
      expect(mock.history.get.filter((r) => r.url === '/reviews').length).toBeGreaterThan(before);
    });
  });

  it('실패가_없으면_다시_선택_버튼을_두지_않는다', async () => {
    const user = userEvent.setup();
    mock
      .onGet('/reviews')
      .reply(200, listPage([mineRow({ videoId: 1, cctvName: 'CCTV-1' })], 20));
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: {
        successCount: 1,
        failureCount: 0,
        results: [{ videoId: 1, success: true, errorCode: null, reason: null }],
      },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));
    await user.click(await screen.findByTestId('bulk-approve-confirm'));

    await screen.findByTestId('bulk-approve-result-counts');
    expect(screen.queryByTestId('bulk-approve-retry-failed')).toBeNull();
  });

  it('실패한_건만_다시_고른_상태로_목록에_돌아간다', async () => {
    const user = userEvent.setup();
    mock.onGet('/reviews').reply(200, listPage([
      mineRow({ videoId: 1, cctvName: 'CCTV-1' }),
      mineRow({ videoId: 2, cctvName: 'CCTV-2' }),
    ], 20));
    mock.onPost('/reviews/batch/approve').reply(200, {
      success: true,
      data: {
        successCount: 1,
        failureCount: 1,
        results: [
          { videoId: 1, success: true, errorCode: null, reason: null },
          {
            videoId: 2,
            success: false,
            errorCode: 'CONFLICT',
            reason: '다른 검수자가 검수 중인 영상입니다.',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    await user.click(await screen.findByTestId('bulk-approve-run'));
    await user.click(await screen.findByTestId('bulk-approve-confirm'));

    await user.click(await screen.findByTestId('bulk-approve-retry-failed'));

    // 실패한 건만 남는다 — 사유를 없앤 뒤 곧바로 다시 시도할 수 있게.
    await waitFor(() => {
      expect(screen.getByTestId('bulk-approve-bar')).toHaveTextContent('선택한 1건');
    });
    expect(screen.getByTestId('review-select-2')).toBeChecked();
    expect(screen.getByTestId('review-select-1')).not.toBeChecked();
  });

  // ── 행 액션 ────────────────────────────────────────────────────

  it('재검수_건은_결과보기가_아니라_재검수_시작으로_보인다', async () => {
    // ★결과보기로 두면 그 영상을 잡는 길이 화면에 드러나지 않고, 잡히지 않으면 일괄
    //   검수완료 대상에도 담기지 않는다(SCREEN-018).
    mock.onGet('/reviews').reply(200, listPage([
      reviewRow({
        videoId: 1,
        cctvName: 'CCTV-재검수',
        status: 'COMPLETED',
        needsRecheck: true,
      }),
      reviewRow({ videoId: 2, cctvName: 'CCTV-승인완료', status: 'COMPLETED' }),
    ], 20));

    renderList();

    expect(
      await screen.findByRole('button', { name: /재검수 시작 CCTV-재검수/ }),
    ).toBeInTheDocument();
    // 재검토가 필요 없는 승인 건은 종전대로 결과보기다.
    expect(
      screen.getByRole('button', { name: /결과보기 CCTV-승인완료/ }),
    ).toBeInTheDocument();
  });

  // ── 필터·페이지 전환 ───────────────────────────────────────────

  it('필터를_바꾸면_고른_것이_풀린다', async () => {
    const user = userEvent.setup();
    mock
      .onGet('/reviews')
      .reply(200, listPage([mineRow({ videoId: 1, cctvName: 'CCTV-1' })], 20));

    renderList();

    await user.click(await screen.findByTestId('review-select-all'));
    expect(await screen.findByTestId('bulk-approve-bar')).toHaveTextContent('선택한 1건');

    // 조회 조건이 바뀌면 화면에 보이지 않는 행이 대상에 남지 않게 선택을 푼다(SCREEN-018).
    await user.click(screen.getByRole('button', { name: /검수중/ }));

    await waitFor(() => {
      expect(screen.queryByTestId('bulk-approve-bar')).toBeNull();
    });
  });
});
