// R6/D4 — 「시작 버전 선택」 모달.
//
// D4 는 히스토리 패널의 **제거가 아니라 재배치**다. 이 모달이 네 기능(영상 버전 목록 · 버전 간
// diff · 작업본 diff · 롤백)의 진입점을 모두 품고 있어야 하며, 하나라도 도달 불가가 되면 위반이다.

import { QueryClient } from '@tanstack/react-query';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { StartVersionModal } from '@/features/version/components/StartVersionModal';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

const RAW_SN = 9;
const SRC_SN = 55;
const HASH_NEW = 'a'.repeat(64);
const HASH_OLD = 'b'.repeat(64);

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

function seedAuth() {
  useAuthStore.setState({
    claims: { userNo: '2001', role: Role.WORKER, channel: 'INTERNAL' },
  } as never);
}

function renderModal(overrides: Partial<Parameters<typeof StartVersionModal>[0]> = {}) {
  const onClose = vi.fn();
  const onApplied = vi.fn();
  const result = renderWithProviders(
    <StartVersionModal
      open
      rawSn={RAW_SN}
      srcSn={SRC_SN}
      dirty={false}
      onClose={onClose}
      onApplied={onApplied}
      {...overrides}
    />,
    { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }) },
  );
  return { ...result, onClose, onApplied };
}

describe('StartVersionModal — 시작 버전 선택', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    seedAuth();
    mock = new MockAdapter(apiClient);
    // 프레임 축(버전 이력) 기본 응답 — 개별 케이스에서 덮어쓴다.
    mock.onGet(`/frames/${SRC_SN}/versions`).reply(200, ok([]));
    mock.onGet(`/videos/${RAW_SN}/versions`).reply(
      200,
      ok([
        { versionNo: 3, snapshotCnt: 2, latestRegDt: '2026-08-09T10:00:00' },
        { versionNo: 1, snapshotCnt: 5, latestRegDt: '2026-08-01T10:00:00' },
      ]),
    );
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.setState({ claims: null } as never);
  });

  it('산출_버전이_최신순으로_나열되고_가장_마지막_버전이_기본_선택된다', async () => {
    // given/when
    renderModal();

    // then: 서버가 준 순서 그대로 + 기본 선택은 최신
    const rows = await screen.findAllByTestId(/^start-version-row-/);
    expect(rows.map((r) => r.getAttribute('data-testid'))).toEqual([
      'start-version-row-3',
      'start-version-row-1',
    ]);
    expect(await screen.findByTestId('start-version-radio-3')).toBeChecked();
  });

  it('가장_마지막_버전_행에만_최신_배지가_붙는다', async () => {
    // given/when
    renderModal();

    // then
    const latest = await screen.findByTestId('start-version-row-3');
    expect(within(latest).getByText('최신')).toBeInTheDocument();
    expect(within(screen.getByTestId('start-version-row-1')).queryByText('최신')).toBeNull();
  });

  it('건너뛴_회차_번호를_결손으로_알리지_않는다', async () => {
    // given: 회차 2 는 모든 프레임이 그대로여서 스냅샷이 없다(정상).
    renderModal();

    // then: "누락"·"결손" 같은 경고를 띄우지 않는다.
    await screen.findByTestId('start-version-row-3');
    expect(screen.queryByText(/누락|결손/)).toBeNull();
  });

  it('이_버전으로_시작을_누르면_고른_회차를_적용한다', async () => {
    // given
    let sent: unknown = null;
    mock.onPut(`/videos/${RAW_SN}/start-version`).reply((config) => {
      sent = JSON.parse(config.data as string);
      return [
        200,
        ok({
          rawSn: RAW_SN,
          versionNo: 1,
          totalFrames: 10,
          appliedFrames: 10,
          revivedFrames: 0,
          discardedFrames: 0,
          unresolvedFrames: 0,
        }),
      ];
    });
    const { onApplied } = renderModal();
    const user = userEvent.setup();

    // when: 회차 1 을 고르고 확정
    await user.click(await screen.findByTestId('start-version-radio-1'));
    await user.click(screen.getByTestId('start-version-apply'));

    // then
    await waitFor(() => expect(sent).toEqual({ versionNo: 1 }));
    expect(onApplied).toHaveBeenCalledWith(expect.objectContaining({ versionNo: 1 }));
  });

  it('되돌리지_못한_프레임이_있으면_그_수를_알린다', async () => {
    // given: 요청 회차 이하 스냅샷이 없어 건드리지 않은 프레임 — 숨기면 "전부 되돌렸다"는 거짓말이 된다.
    mock.onPut(`/videos/${RAW_SN}/start-version`).reply(
      200,
      ok({
        rawSn: RAW_SN,
        versionNo: 3,
        totalFrames: 10,
        appliedFrames: 7,
        revivedFrames: 1,
        discardedFrames: 0,
        unresolvedFrames: 3,
      }),
    );
    renderModal();
    const user = userEvent.setup();

    // when
    await user.click(await screen.findByTestId('start-version-apply'));

    // then
    const notice = await screen.findByTestId('start-version-unresolved');
    expect(notice).toHaveTextContent('3');
  });

  it('미저장_편집이_있으면_확인을_거친_뒤에만_적용한다', async () => {
    // given
    let called = false;
    mock.onPut(`/videos/${RAW_SN}/start-version`).reply(() => {
      called = true;
      return [
        200,
        ok({
          rawSn: RAW_SN,
          versionNo: 3,
          totalFrames: 1,
          appliedFrames: 1,
          revivedFrames: 0,
          discardedFrames: 0,
          unresolvedFrames: 0,
        }),
      ];
    });
    renderModal({ dirty: true });
    const user = userEvent.setup();

    // when: 확정을 눌러도 곧바로 나가지 않는다
    await user.click(await screen.findByTestId('start-version-apply'));
    expect(called).toBe(false);
    expect(await screen.findByText(/저장하지 않은 편집/)).toBeInTheDocument();

    // and: 확인해야 나간다
    await user.click(screen.getByRole('button', { name: '불러오기' }));
    await waitFor(() => expect(called).toBe(true));
  });

  it('현재_작업본으로_시작을_고르면_아무것도_적용하지_않고_닫는다', async () => {
    // given
    let called = false;
    mock.onPut(`/videos/${RAW_SN}/start-version`).reply(() => {
      called = true;
      return [200, ok({})];
    });
    const { onClose } = renderModal();
    const user = userEvent.setup();

    // when
    await user.click(await screen.findByTestId('start-version-keep-working'));

    // then
    expect(called).toBe(false);
    expect(onClose).toHaveBeenCalled();
  });

  it('목록_조회_실패는_고를_버전이_없음이_아니라_실패로_표시된다', async () => {
    // given
    mock.onGet(`/videos/${RAW_SN}/versions`).reply(500, ok(null));

    // when
    renderModal();

    // then: 렌더 분기(로딩 → 에러 → 결과)가 지켜져야 조회 실패가 "없음"으로 둔갑하지 않는다.
    expect(await screen.findByTestId('start-version-error')).toBeInTheDocument();
    expect(screen.queryByTestId('start-version-empty')).toBeNull();
  });

  it('적용_실패는_모달을_닫지_않고_사유를_보여준다', async () => {
    // given: 신고 구간(412) 등 — 실패했는데 닫히면 사용자는 적용됐다고 오인한다.
    mock.onPut(`/videos/${RAW_SN}/start-version`).reply(412, {
      success: false,
      data: null,
      message: '비식별 재처리 대기 중인 영상입니다.',
      errorCode: 'PRECONDITION_FAILED',
    });
    const { onApplied } = renderModal();
    const user = userEvent.setup();

    // when
    await user.click(await screen.findByTestId('start-version-apply'));

    // then
    expect(await screen.findByTestId('start-version-apply-error')).toHaveTextContent(
      '비식별 재처리 대기 중인 영상입니다.',
    );
    expect(onApplied).not.toHaveBeenCalled();
  });

  describe('D4 — 재배치된 프레임 버전 축(4기능 도달 보장)', () => {
    /**
     * 프레임 버전 이력을 펼친다 — 이 토글이 **재배치 후의 유일한 진입점**이다.
     * 사라지면 네 기능이 도달 불가가 되므로 각 케이스가 이 경로를 실제로 통과한다.
     */
    async function openFrameHistory(user: ReturnType<typeof userEvent.setup>) {
      await user.click(await screen.findByTestId('start-version-history-toggle'));
      return screen.findByTestId('start-version-frame-history');
    }

    beforeEach(() => {
      mock.onGet(`/frames/${SRC_SN}/versions`).reply(
        200,
        ok([
          {
            commitSha: HASH_NEW,
            shortHash: HASH_NEW.slice(0, 7),
            authorName: '홍길동',
            authorNo: '2001',
            message: 'APPROVED',
            committedAt: '2026-08-09T10:00:00Z',
            isCurrent: true,
          },
          {
            commitSha: HASH_OLD,
            shortHash: HASH_OLD.slice(0, 7),
            authorName: '김검수',
            authorNo: '1001',
            message: 'APPROVED',
            committedAt: '2026-08-01T10:00:00Z',
            isCurrent: false,
          },
        ]),
      );
    });

    it('프레임_버전_목록이_모달_안에서_도달_가능하다', async () => {
      // given
      renderModal();
      const user = userEvent.setup();

      // when
      await openFrameHistory(user);

      // then: 헤더 히스토리 버튼이 사라져도 진입점이 남아 있어야 한다.
      expect(
        await screen.findByTestId(`commit-row-${HASH_NEW.slice(0, 7)}`),
      ).toBeInTheDocument();
    });

    it('버전_한_건을_고르면_현재_작업본과_비교한다', async () => {
      // given
      mock
        .onGet(`/versions/${HASH_OLD}/diff-with-working`)
        .reply(200, ok([{ type: 'ADDED', frameId: SRC_SN, objectId: 'o1', after: null }]));
      renderModal();
      const user = userEvent.setup();
      await openFrameHistory(user);

      // when
      await user.click(
        within(await screen.findByTestId(`commit-row-${HASH_OLD.slice(0, 7)}`)).getByRole(
          'button',
        ),
      );

      // then
      expect(await screen.findByTestId('diff-compare-target')).toHaveTextContent(
        '현재 작업본',
      );
    });

    it('버전_두_건을_체크하면_버전_간_비교로_전환된다', async () => {
      // given
      mock.onGet(`/versions/${HASH_NEW}/diff`).reply(200, ok([]));
      renderModal();
      const user = userEvent.setup();
      await openFrameHistory(user);

      // when
      await user.click(await screen.findByLabelText(`커밋 ${HASH_NEW.slice(0, 7)} 선택`));
      await user.click(screen.getByLabelText(`커밋 ${HASH_OLD.slice(0, 7)} 선택`));

      // then: 두 해시가 함께 표시되고 '현재 작업본' 축이 아니다.
      const target = await screen.findByTestId('diff-compare-target');
      expect(target).toHaveTextContent(HASH_OLD.slice(0, 7));
      expect(target).not.toHaveTextContent('현재 작업본');
    });

    it('롤백_진입점이_모달_안에서_도달_가능하다', async () => {
      // given
      mock.onGet(`/versions/${HASH_OLD}/diff-with-working`).reply(200, ok([]));
      renderModal();
      const user = userEvent.setup();
      await openFrameHistory(user);

      // when: 최신이 아닌 버전을 고른다(최신은 롤백 대상이 아니다)
      await user.click(
        within(await screen.findByTestId(`commit-row-${HASH_OLD.slice(0, 7)}`)).getByRole(
          'button',
        ),
      );

      // then
      expect(
        await screen.findByTestId(`rollback-trigger-${HASH_OLD.slice(0, 7)}`),
      ).toBeInTheDocument();
    });
  });
});
