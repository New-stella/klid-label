/**
 * 데이터셋 영상 구역 회귀 가드. [@design SCREEN-046] [@design API-253]
 *
 * 고정하는 것
 *  ① 등록 중 · 등록 실패 · 조회 실패 · 모르는 상태 · 영상 없음을 **서로 다른 자리**로 그린다 —
 *     오류나 등록 중을 「영상이 없다」로 보이면 데이터셋이 비었다고 오해한다.
 *  ② 라벨링 진입 프레임은 응답의 `entrySrcSn` 이 정한다. 비면 진입을 두지 않고 사유를 보인다.
 *  ③ 저장한 적 있는 영상은 「이어서 라벨링」 + 배지, 없으면 「라벨링」.
 *  ④ 구역 머리가 「저장해야 내 작업에 남는다」를 말한다.
 *  ⑤ 등록 실패 판은 사유 문구 + 「다시 등록」이다 — 사유 코드를 그대로 찍지 않고, 모르는 값·null 은
 *     폴백이며, 구조 불일치 계열은 버튼이 보조 위계다. 누르면 재착수 창구를 부른 뒤 목록 쿼리를 무효화하고,
 *     요청 중엔 버튼이 잠기며, 거부되면 안내 문장이 서고 버튼은 다시 눌린다. [@design API-262] [@design AC-1132]
 *
 * ⚠ 이 파일은 목록 훅을 모의하므로 「무효화 뒤 등록 중 판이 나타난다」는 여기서 볼 수 없다 —
 *    그 축은 `DatasetVideoSectionRestart.test.tsx`(훅 실물 + 요청 모의)가 고정한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ApiError } from '@/lib/api/errors';
import { PORTAL_KEYS } from '@/lib/queryKeys';
import { createTestQueryClient, renderWithProviders } from '@/test/renderWithProviders';

import {
  DatasetVideoSection,
  DATASET_VIDEOS_LEAD,
  REGISTRATION_FAILED_TITLE,
  RESTART_REJECTED_FALLBACK,
} from '../components/DatasetVideoSection';
import { registrationFailureNotice } from '../registrationFailureReason';
import {
  PortalDatasetRegistrationFailureReason,
  type PortalDatasetVideo,
  type PortalDatasetVideoPage,
} from '../types';

const useDatasetVideosMock = vi.fn();
vi.mock('../hooks/useDatasetVideos', () => ({
  useDatasetVideos: (...args: unknown[]) => useDatasetVideosMock(...args),
}));

// 통신만 모의한다 — 재착수 훅은 실물이라 무효화 배선이 실제로 돈다.
// ⚠ `../api` 를 통째로 모의해도 사유 표기(`registrationFailureReason.ts`)는 별개 모듈이라 살아 있다.
const restartMock = vi.fn();
vi.mock('../api', () => ({
  restartDatasetRegistration: (...args: unknown[]) => restartMock(...args),
}));

function video(over: Partial<PortalDatasetVideo> = {}): PortalDatasetVideo {
  return {
    rawSn: 11,
    videoName: 'CCTV-서울-0912.mp4',
    frameCount: 30,
    labelCount: 42,
    entrySrcSn: 901,
    lastSavedAt: null,
    ...over,
  };
}

function page(over: Partial<PortalDatasetVideoPage> = {}): PortalDatasetVideoPage {
  const content = over.content ?? [video()];
  return {
    registrationState: 'DONE',
    registrationFailureReason: null,
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 20,
    ...over,
  };
}

function mockQuery(over: Partial<Record<string, unknown>> = {}) {
  const refetch = vi.fn();
  useDatasetVideosMock.mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
    refetch,
    ...over,
  });
  return refetch;
}

function renderSection() {
  return renderWithProviders(<DatasetVideoSection datasetId={4704} />);
}

/** 실패 응답 픽스처 — 사유는 케이스가 고른다. */
function failedPage(reason: string | null) {
  return page({ registrationState: 'FAILED', registrationFailureReason: reason, content: [] });
}

describe('데이터셋 영상 구역', () => {
  beforeEach(() => {
    useDatasetVideosMock.mockReset();
    restartMock.mockReset();
  });

  it('★구역_머리가_저장해야_내_작업에_남는다고_말한다', () => {
    mockQuery({ data: page() });
    renderSection();
    expect(screen.getByRole('heading', { name: '데이터셋 영상' })).toBeInTheDocument();
    expect(screen.getByText(DATASET_VIDEOS_LEAD)).toBeInTheDocument();
    expect(DATASET_VIDEOS_LEAD).toContain('저장해야 내 작업에 남습니다');
    // 건수는 목록 바로 위 킷 건수 줄이 그린다(「총 N건」) — 굵은 숫자가 별개 조각이라
    // 텍스트 노드 하나로는 잡히지 않는다. 줄 전체를 후크로 집어 본문으로 본다.
    expect(screen.getByTestId('dataset-videos-count')).toHaveTextContent('총 1건');
  });

  it('★응답이_준_프레임으로_라벨링을_연다_저장_전이면_라벨링', () => {
    mockQuery({ data: page({ content: [video({ rawSn: 11, entrySrcSn: 901, lastSavedAt: null })] }) });
    renderSection();

    const row = within(screen.getByTestId('dataset-video-row-11'));
    const link = row.getByRole('link', { name: 'CCTV-서울-0912.mp4 라벨링' });
    expect(link).toHaveAttribute('href', '/portal/label/901');
    expect(row.getByText('30장')).toBeInTheDocument();
    expect(row.getByText('42건')).toBeInTheDocument();
    expect(row.queryByText('저장한 작업 있음')).not.toBeInTheDocument();
  });

  it('★저장한_적_있으면_이어서_라벨링과_배지를_보인다', () => {
    mockQuery({
      data: page({ content: [video({ rawSn: 12, entrySrcSn: 955, lastSavedAt: '2026-09-15T10:20:00' })] }),
    });
    renderSection();

    const row = within(screen.getByTestId('dataset-video-row-12'));
    expect(row.getByRole('link', { name: /이어서 라벨링$/ })).toHaveAttribute('href', '/portal/label/955');
    expect(row.getByText('저장한 작업 있음')).toBeInTheDocument();
    expect(row.getByText(/마지막 저장/)).toBeInTheDocument();
  });

  it('★열_프레임이_없으면_진입을_두지_않고_사유를_보인다', () => {
    mockQuery({ data: page({ content: [video({ rawSn: 13, entrySrcSn: null })] }) });
    renderSection();

    const row = within(screen.getByTestId('dataset-video-row-13'));
    expect(row.queryByRole('link')).not.toBeInTheDocument();
    expect(row.getByText('열 수 있는 프레임이 없습니다.')).toBeInTheDocument();
  });

  it('★등록_중이면_목록도_빈_상태도_아니라_등록_중이라고_말한다', () => {
    mockQuery({ data: page({ registrationState: 'IN_PROGRESS', content: [] }) });
    renderSection();

    expect(screen.getByTestId('dataset-videos-registering')).toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-empty')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-list')).not.toBeInTheDocument();
  });

  it('★등록_실패는_빈_상태와_구분하고_사유_문구와_다시_등록을_둔다_코드를_찍지_않는다', () => {
    mockQuery({ data: failedPage(PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING) });
    renderSection();

    const alert = screen.getByTestId('dataset-videos-registration-failed');
    // ★싸개가 role="alert" 를 갖는다 — 킷 빈 판은 busy 일 때만 읽어 주므로 이것이 없으면 실패 사실이
    //   보조기술에 한 마디도 가지 않는다(변이 실증: role 제거 시 이 단언만 RED).
    expect(alert).toHaveAttribute('role', 'alert');
    expect(screen.queryByTestId('dataset-videos-empty')).not.toBeInTheDocument();
    expect(within(alert).getByText(REGISTRATION_FAILED_TITLE)).toBeInTheDocument();
    const notice = registrationFailureNotice(PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING);
    expect(within(alert).getByTestId('dataset-videos-registration-failed-reason')).toHaveTextContent(
      notice.title,
    );
    expect(alert).toHaveTextContent(notice.description);
    // 사유 코드 문자열은 화면 어디에도 없다.
    expect(alert.textContent).not.toContain('VIDEO_FILENAME_MISSING');
    expect(within(alert).getByRole('button', { name: '다시 등록' })).toBeEnabled();
    // 구 걸음 「다시 확인」은 이 판에서 사라졌다 — 재조회로는 실패 표식이 풀리지 않는다.
    expect(within(alert).queryByRole('button', { name: '다시 확인' })).not.toBeInTheDocument();
  });

  it('★다시_등록을_누르면_재착수를_부른_뒤_그_데이터셋의_목록_쿼리를_무효화한다', async () => {
    const calls: string[] = [];
    restartMock.mockImplementation(() => {
      calls.push('restart');
      return Promise.resolve({ registrationState: 'IN_PROGRESS', registrationFailureReason: null });
    });
    mockQuery({ data: failedPage(PortalDatasetRegistrationFailureReason.IO_ERROR) });
    const queryClient = createTestQueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockImplementation(() => {
      calls.push('invalidate');
      return Promise.resolve();
    });
    renderWithProviders(<DatasetVideoSection datasetId={4704} />, { queryClient });

    await userEvent.click(screen.getByRole('button', { name: '다시 등록' }));

    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(1));
    expect(restartMock).toHaveBeenCalledWith(4704);
    // ★순서 — 재착수가 먼저, 무효화가 뒤. 무효화가 앞서면 옛 실패 상태를 한 번 더 받는다.
    expect(calls).toEqual(['restart', 'invalidate']);
    // ★대상 — 이 데이터셋의 영상 목록 전 페이지(접두 키).
    expect(invalidate).toHaveBeenCalledWith({ queryKey: PORTAL_KEYS.datasetVideosOf(4704) });
  });

  it('★요청_중에는_다시_등록_버튼이_잠긴다', async () => {
    const handover = { release: () => {} };
    restartMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          handover.release = () =>
            resolve({ registrationState: 'IN_PROGRESS', registrationFailureReason: null });
        }),
    );
    mockQuery({ data: failedPage(PortalDatasetRegistrationFailureReason.IO_ERROR) });
    renderSection();

    const button = screen.getByRole('button', { name: '다시 등록' });
    await userEvent.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    expect(restartMock).toHaveBeenCalledTimes(1);

    handover.release();
    await waitFor(() => expect(button).toBeEnabled());
  });

  it('★재착수가_거부되면_서버_문장을_판_안에_보이고_버튼은_다시_눌린다', async () => {
    restartMock.mockRejectedValue(
      ApiError.fromBody(
        { success: false, data: null, message: '데이터셋 소재가 아직 준비되지 않았습니다.', errorCode: 'CONFLICT' },
        409,
      ),
    );
    mockQuery({ data: failedPage(PortalDatasetRegistrationFailureReason.IO_ERROR) });
    renderSection();

    await userEvent.click(screen.getByRole('button', { name: '다시 등록' }));

    const banner = await screen.findByTestId('dataset-videos-restart-rejected');
    expect(banner).toHaveTextContent('데이터셋 소재가 아직 준비되지 않았습니다.');
    // 거부 띠는 실패 판 **안**에 선다 — 판을 둘 세우지 않는다.
    expect(screen.getByTestId('dataset-videos-registration-failed')).toContainElement(banner);
    expect(screen.getByRole('button', { name: '다시 등록' })).toBeEnabled();
  });

  it('재착수_거부에_서버_문장이_없으면_폴백_문장을_보인다', async () => {
    restartMock.mockRejectedValue(new Error(''));
    mockQuery({ data: failedPage(PortalDatasetRegistrationFailureReason.IO_ERROR) });
    renderSection();

    await userEvent.click(screen.getByRole('button', { name: '다시 등록' }));

    expect(await screen.findByTestId('dataset-videos-restart-rejected')).toHaveTextContent(
      RESTART_REJECTED_FALLBACK,
    );
  });

  it('★구조_불일치_계열_사유는_버튼을_보조_위계로_낮추고_같은_결과임을_말한다', () => {
    mockQuery({ data: failedPage(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH) });
    renderSection();

    const alert = screen.getByTestId('dataset-videos-registration-failed');
    expect(alert).toHaveTextContent('같은 소재로는 같은 결과입니다');
    const button = within(alert).getByRole('button', { name: '다시 등록' });
    // 숨기지 않는다 — 위계만 낮춘다(킷 버튼의 tertiary).
    expect(button).toBeEnabled();
    expect(button.className).toMatch(/tertiary/);
  });

  it('★일시_장애_사유는_버튼이_2차_위계다', () => {
    mockQuery({ data: failedPage(PortalDatasetRegistrationFailureReason.IO_ERROR) });
    renderSection();

    const button = screen.getByRole('button', { name: '다시 등록' });
    expect(button.className).toMatch(/secondary/);
    expect(button.className).not.toMatch(/tertiary/);
  });

  it('★모르는_사유_값과_null_은_폴백_문구이고_버튼은_있다', () => {
    for (const reason of ['SOMETHING_NEW', null]) {
      useDatasetVideosMock.mockReset();
      mockQuery({ data: failedPage(reason) });
      const { unmount } = renderSection();

      const alert = screen.getByTestId('dataset-videos-registration-failed');
      const fallback = registrationFailureNotice(null);
      expect(within(alert).getByTestId('dataset-videos-registration-failed-reason')).toHaveTextContent(
        fallback.title,
      );
      expect(alert.textContent).not.toContain('SOMETHING_NEW');
      expect(within(alert).getByRole('button', { name: '다시 등록' })).toBeEnabled();
      unmount();
    }
  });

  it('조회_실패와_모르는_상태의_재조회_걸음은_그대로다', () => {
    const refetch = mockQuery({ data: page({ registrationState: 'PAUSED', content: [] }) });
    renderSection();
    within(screen.getByTestId('dataset-videos-unknown-state'))
      .getByRole('button', { name: '다시 확인' })
      .click();
    expect(refetch).toHaveBeenCalledTimes(1);
    expect(restartMock).not.toHaveBeenCalled();
  });

  it('★조회_실패는_영상이_없다고_말하지_않는다', () => {
    mockQuery({ isError: true });
    renderSection();

    expect(screen.getByTestId('dataset-videos-error')).toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-empty')).not.toBeInTheDocument();
  });

  it('★모르는_등록_상태는_완료로_읽지_않는다', () => {
    mockQuery({ data: page({ registrationState: 'PAUSED', content: [video()] }) });
    renderSection();

    expect(screen.getByTestId('dataset-videos-unknown-state')).toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-list')).not.toBeInTheDocument();
  });

  it('등록이_끝났는데_영상이_없으면_빈_상태를_보인다', () => {
    mockQuery({ data: page({ content: [] }) });
    renderSection();

    expect(screen.getByTestId('dataset-videos-empty')).toBeInTheDocument();
  });
});
