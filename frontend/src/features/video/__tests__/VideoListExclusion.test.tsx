/**
 * 영상 처리 현황 — 제외·복원 동선 회귀 가드.
 * [@design SCREEN-008] [@design API-042] [@design API-260] [@design API-261]
 * [@design AC-1124] [@design AC-1126] [@design AC-1127]
 *
 * ★이 화면이 제외·복원을 **수행하는 유일한 화면**이다(작업 목록·검수 목록은 열람까지다).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

// 마킹 팝업은 자체 API 훅을 렌더하므로 이 파일에서는 열지 않는 stub 으로 대체한다.
vi.mock('@/features/marking/components/MarkingModal', () => ({
  MarkingModal: () => null,
}));

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    claims: { sub: 'u-7', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

interface Row {
  id: number;
  cctvName: string;
  status?: string;
  workerId?: number | null;
  workerName?: string | null;
  assignmentId?: number | null;
  assignStatus?: string | null;
}

/** 목록 응답 — `excludedCount` 는 **페이지 응답**이 싣는다(이 화면엔 집계 창구가 없다). */
function mockVideos(mock: MockAdapter, rows: Row[], excludedCount = 0) {
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content: rows.map((r) => ({
        vmsClipId: '',
        eventName: '',
        eventTypeCd: '',
        frameCount: 0,
        capturedAt: '2026-05-07T10:00:00Z',
        deIdntfYn: 'Y',
        deidentStatus: 'DONE',
        status: 'MARKING_READY',
        ...r,
      })),
      totalElements: rows.length,
      totalPages: 1,
      number: 0,
      size: 20,
      excludedCount,
    },
    message: null,
    errorCode: null,
  });
}

/** 마지막 목록 요청의 질의 항목 — 「무엇을 빼고 보냈는가」를 본다. */
function lastVideoParams(mock: MockAdapter): Record<string, unknown> {
  const calls = mock.history.get.filter((c) => c.url === '/videos');
  return (calls.at(-1)?.params ?? {}) as Record<string, unknown>;
}

describe('영상 처리 현황 — 제외·복원', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 배정 모달이 REVIEWER 일 때 조회하는 창구.
    const emptyPage = {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 },
      message: null,
      errorCode: null,
    };
    mock.onGet('/users').reply(200, emptyPage);
    mock.onGet('/users/workers').reply(200, emptyPage);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.clearAllMocks();
  });

  // ── 「제외됨 N건」 표시 ────────────────────────────────────────────

  it('★제외됨이_0건이어도_표시된다', async () => {
    // 0건일 때 사라지면 화면이 「제외된 것이 없다」와 「제외 기능이 없다」를 구분해 보여 주지 못한다.
    setRole('REVIEWER');
    mockVideos(mock, [{ id: 1, cctvName: 'CCTV-A' }], 0);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    // ★★**응답이 도착한 뒤**를 관측한다. 이 표시는 첫 렌더부터 자리에 있고 그때는 아직 값을
    //   못 받아 「제외됨 0건」으로 그려진다 — 기다리지 않으면 이 케이스는 **서버 값을 한 번도
    //   읽지 않고도 통과**한다(0 을 기대하는 케이스라 로딩 상태와 글자가 우연히 같다).
    //   값을 받았는지는 **버튼이 눌리는지**로 가른다(못 받았으면 잠겨 있다).
    const toggle = await screen.findByTestId('excluded-count-toggle');
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveTextContent('제외됨 0건');
  });

  it('★행이_하나도_없는_페이지에서도_제외됨_건수가_실린다', async () => {
    // 제외분만 남은 상태에서 화면이 건수를 모르면 되돌아갈 길을 안내하지 못한다.
    setRole('REVIEWER');
    mockVideos(mock, [], 3);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    // 행이 0건이라 기다릴 행이 없다 — 표시 자체를 기다린다(위 케이스와 같은 이유).
    const toggle = await screen.findByTestId('excluded-count-toggle');
    await waitFor(() => expect(toggle).toHaveTextContent('제외됨 3건'));
  });

  // ── 전환 — 이 화면은 어떤 필터도 빼지 않는다 ───────────────────────

  it('★제외됨을_누르면_걸린_필터를_하나도_빼지_않고_전환한다', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [{ id: 1, cctvName: 'CCTV-A' }], 2);

    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video?dataSttsCd=COMPLETED&cctvNameKeyword=강남&eventTypeCd=010001'],
    });

    await userEvent.click(await screen.findByTestId('excluded-count-toggle'));

    await waitFor(() => {
      expect(lastVideoParams(mock).excludedOnly).toBe(true);
    });
    const params = lastVideoParams(mock);
    // ★상태 축까지 그대로 간다 — 이 화면은 집계 창구가 없어 페이지 응답이 **같은 조건**으로
    //   세므로, 조건을 빼면 누른 숫자와 전환 결과가 도리어 어긋난다.
    //   ⚠ 작업 목록·검수 목록은 자기 상태 축을 뺀다. 「일관성」을 이유로 맞추지 말 것.
    expect(params.dataSttsCd).toBe('COMPLETED');
    expect(params.cctvNameKeyword).toBe('강남');
    expect(params.eventTypeCd).toBe('010001');
  });

  it('기본_목록에서는_제외분만_보기_항목을_싣지_않는다', async () => {
    // 이 축을 모르던 기존 북마크·저장된 URL 과 요청 형태가 갈리지 않아야 한다.
    setRole('REVIEWER');
    mockVideos(mock, [{ id: 1, cctvName: 'CCTV-A' }], 0);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });
    await screen.findByText('CCTV-A');

    expect(lastVideoParams(mock)).not.toHaveProperty('excludedOnly');
  });

  // ── 배정 충돌 — 미리 비활성 + 사유 ────────────────────────────────

  it('★배정이_있으면_제외_버튼이_미리_비활성되고_사유가_함께_보인다', async () => {
    // 눌러서 물리쳐진 뒤에야 아는 동선을 만들지 않는다(AC-1127).
    setRole('REVIEWER');
    mockVideos(
      mock,
      [
        {
          id: 1,
          cctvName: 'CCTV-배정됨',
          workerId: 7,
          workerName: '김작업',
          assignmentId: 500,
          assignStatus: 'IN_PROGRESS',
        },
      ],
      0,
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    expect(await screen.findByTestId('video-exclude-1')).toBeDisabled();
    // ★사유를 `title` 로만 두면 비활성 버튼이 초점을 받지 못해 보조기술이 닿지 못한다 —
    //   눈에 보이는 문구로 함께 세운다.
    expect(screen.getByTestId('video-exclude-blocked-1')).toHaveTextContent(
      '먼저 배정을 해제하세요',
    );
  });

  it('배정이_없으면_제외_버튼이_활성이고_사유가_보이지_않는다', async () => {
    // ★비활성 축만 단언하면 「항상 비활성」으로 굳어도 통과한다 — 반대 갈래를 짝으로 둔다.
    setRole('REVIEWER');
    mockVideos(mock, [{ id: 2, cctvName: 'CCTV-미배정', workerId: null }], 0);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    expect(await screen.findByTestId('video-exclude-2')).toBeEnabled();
    expect(screen.queryByTestId('video-exclude-blocked-2')).not.toBeInTheDocument();
    // ★복원은 **제외분만 보는 목록의 동작**이라 기본 목록에는 서지 않는다 — 「제외분 보기에서
    //   복원이 보인다」만 단언하면 그 조건이 풀려 기본 목록의 모든 행에 복원이 새어도 아무
    //   시험이 울지 않는다(변이로 실증됨). 존재 축과 부재 축을 짝으로 둔다.
    expect(screen.queryByTestId('video-restore-2')).not.toBeInTheDocument();
  });

  // ── 제외 실행 ─────────────────────────────────────────────────────

  it('사유를_적어야_제외할_수_있고_그_사유가_요청에_실린다', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [{ id: 3, cctvName: 'CCTV-C', workerId: null }], 0);
    mock.onPost('/videos/3/exclusion').reply(200, {
      success: true,
      data: { rawSn: 3, excluded: true, changed: true, reason: '시험 데이터', excludedAt: null },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await user.click(await screen.findByTestId('video-exclude-3'));
    const dialog = await screen.findByTestId('video-exclude-reason-modal');
    const confirm = screen.getByTestId('video-exclude-confirm');
    // 사유가 비어 있으면 확인할 수 없다(서버도 같은 제약을 건다).
    expect(confirm).toBeDisabled();

    // ★입력칸을 **대화상자 안으로** 좁힌다 — 이 화면에는 검색 필터의 입력칸도 있어 역할만으로
    //   고르면 둘이 잡힌다. 그때 나오는 실패 메시지는 구현 결함처럼 보이지만 픽스처의 문제다.
    await user.type(within(dialog).getByRole('textbox'), '시험 데이터');
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => {
      expect(mock.history.post.filter((c) => c.url === '/videos/3/exclusion')).toHaveLength(1);
    });
    const body = JSON.parse(mock.history.post.at(-1)!.data) as { reason: string };
    expect(body.reason).toBe('시험 데이터');
  });

  it('제외_사유_입력에_개인정보_경고가_함께_선다', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [{ id: 4, cctvName: 'CCTV-D', workerId: null }], 0);

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });

    await user.click(await screen.findByTestId('video-exclude-4'));

    // ★입력하는 자리에서 보여야 효력이 있다 — 다른 곳에 두면 다 적고 나서야 보인다.
    const dialog = await screen.findByTestId('video-exclude-reason-modal');
    expect(dialog).toHaveTextContent('개인정보를 적지 마세요');
  });

  // ── 제외분만 보기 ─────────────────────────────────────────────────

  it('★제외분_보기에서는_배정_마킹_일괄바가_사라지고_복원만_남는다', async () => {
    // 제외한 것을 작업 대상으로 만들 수 있으면 제외가 무의미해진다(AC-1127).
    setRole('REVIEWER');
    mockVideos(
      mock,
      [
        {
          id: 5,
          cctvName: 'CCTV-제외됨',
          workerId: 7,
          workerName: '김작업',
          assignmentId: 700,
          assignStatus: 'IN_PROGRESS',
        },
      ],
      1,
    );

    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video?excludedOnly=true'],
    });

    // 복원만 남는다.
    expect(await screen.findByTestId('video-restore-5')).toBeInTheDocument();
    // 배정·마킹·제외 동작은 전부 사라진다.
    expect(screen.queryByRole('button', { name: /재배정/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /마킹 설정/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId('video-exclude-5')).not.toBeInTheDocument();
    // 선택 수단이 없으니 일괄 작업 바에 이를 길도 없다.
    expect(screen.queryByLabelText('전체 선택')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('CCTV-제외됨 선택')).not.toBeInTheDocument();
    // 지금 보는 것이 기본 목록이 아니라는 사실과 되돌아갈 수단을 함께 세운다.
    expect(screen.getByTestId('excluded-only-notice')).toBeInTheDocument();
    expect(screen.getByTestId('excluded-only-exit')).toBeInTheDocument();
  });

  it('복원은_사유를_받지_않고_확인만_거친다', async () => {
    setRole('REVIEWER');
    mockVideos(mock, [{ id: 6, cctvName: 'CCTV-F' }], 1);
    mock.onDelete('/videos/6/exclusion').reply(200, {
      success: true,
      data: { rawSn: 6, excluded: false, restored: true },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video?excludedOnly=true'] });

    await user.click(await screen.findByTestId('video-restore-6'));
    const dialog = await screen.findByTestId('video-restore-confirm-modal');
    // 사유 입력 칸이 없다 — 「감추는 쪽만 사유를 남긴다」.
    expect(within(dialog).queryByRole('textbox')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('video-restore-confirm'));
    await waitFor(() => {
      expect(mock.history.delete.filter((c) => c.url === '/videos/6/exclusion')).toHaveLength(1);
    });
  });

  // ── 인가(AC-1126) ────────────────────────────────────────────────

  it('★작업자에게는_제외됨_표시도_제외_버튼도_없다', async () => {
    setRole('WORKER');
    mockVideos(mock, [{ id: 7, cctvName: 'CCTV-작업자', workerId: null }], 4);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video'] });
    await screen.findByText('CCTV-작업자');

    expect(screen.queryByTestId('excluded-count-toggle')).not.toBeInTheDocument();
    expect(screen.queryByTestId('video-exclude-7')).not.toBeInTheDocument();
  });
});
