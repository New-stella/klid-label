// 포털 내 작업 — 내려받기 **취소** 회귀 가드. @design SCREEN-028
//
// 이 파일이 고정하는 계약:
//  - ★★ **두 축 모두에서 취소가 동작한다.** 행에 따라 취소가 되기도 안 되기도 하면 사용자가
//    예측할 수 없다 — 사양은 「같은 자리에서 전송을 멈출 수 있어야 한다」를 행 구분 없이 요구한다.
//    (업로드 내보내기 창구에는 중단 수단이 아예 없었고, 이번에 선택 인자로 더했다.)
//  - 진행 중일 때만 취소 조작이 보인다.
//  - ★ **사용자 취소는 오류가 아니라 정상 종료다** — 안내를 띄우지 않는다. 취소하면 응답이 오지
//    않아 `status=0` 인 `ApiError` 로 올라오는데, 그 자리는 «전송이 끊겼습니다 … 연결이 안정적인
//    환경에서 다시» 를 안내하는 분기다. 갈라 놓지 않으면 스스로 멈춘 사용자에게 회선을 탓하는
//    거짓 안내가 뜨고 «다시» 라는 권유까지 붙는다.
//  - ⚠ 그렇다고 «응답 없는 실패» 통합을 뒤집지 않는다 — 마지막 케이스가 그 통합이 살아 있음을
//    함께 단언한다(삼키면 진짜 장애가 아무 표시 없이 사라진다).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUserWork } from '@/features/portal/api';
import { DOWNLOAD_ERROR_INTERRUPTED } from '@/features/portal/downloadError';
import { ApiError } from '@/lib/api/errors';

import { PortalHomePage } from '../PortalHomePage';

const useUserWorksMock = vi.fn();
vi.mock('@/features/portal/hooks/useUserWorks', () => ({
  useUserWorks: (params: { page?: number; size?: number }) => useUserWorksMock(params),
}));

const datamartDownloadMock = vi.fn();
vi.mock('@/features/portal/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/portal/api')>();
  return {
    ...actual,
    downloadDatamartVideoData: (rawSn: number, signal?: AbortSignal) =>
      datamartDownloadMock(rawSn, signal),
  };
});

const uploadExportMock = vi.fn();
vi.mock('@/features/portal/uploads/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/portal/uploads/api')>();
  return {
    ...actual,
    downloadUploadExport: (uldSn: number, fallbackName?: string, signal?: AbortSignal) =>
      uploadExportMock(uldSn, fallbackName, signal),
  };
});

function work(over: Partial<PortalUserWork> = {}): PortalUserWork {
  return {
    rawSn: 10,
    assetSource: 'DATAMART',
    videoName: 'CLIP-10',
    labelCount: 3,
    lastSavedAt: '2026-06-01T10:00:00',
    entrySrcSn: 100,
    expiresOn: '2026-06-08',
    ...over,
  };
}

function mockWorks(content: PortalUserWork[]) {
  useUserWorksMock.mockReturnValue({
    data: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 },
    isLoading: false,
    isError: false,
  });
}

/**
 * 실제 경로를 그대로 흉내낸다 — 취소하면 응답이 오지 않으므로 공용 클라이언트가
 * `ApiError.fromStatus(0, …)` 로 감싸 올린다(취소 여부를 알려주는 표식은 남지 않는다).
 * **오류 객체만 봐서는 취소와 회선 단절을 구분할 수 없다** 는 사실이 이 가드의 전제다.
 */
function hangUntilAborted(mock: ReturnType<typeof vi.fn>, signalArgIndex: number) {
  mock.mockImplementation((...args: unknown[]) => {
    const signal = args[signalArgIndex] as AbortSignal | undefined;
    return new Promise<void>((_resolve, reject) => {
      signal?.addEventListener('abort', () => {
        reject(ApiError.fromStatus(0, 'canceled'));
      });
    });
  });
}

function renderHome() {
  return renderWithProviders(<PortalHomePage />, {
    initialEntries: ['/portal'],
    routes: [
      { path: '/portal', element: <PortalHomePage /> },
      { path: '/portal/label/:id', element: <div data-testid="label-route">labeling</div> },
    ],
  });
}

beforeEach(() => {
  datamartDownloadMock.mockReset();
  datamartDownloadMock.mockResolvedValue(undefined);
  uploadExportMock.mockReset();
  uploadExportMock.mockResolvedValue(undefined);
});

afterEach(() => {
  useUserWorksMock.mockReset();
});

describe('포털 내 작업 — 내려받기 취소', () => {
  it('내려받기_전에는_취소_조작이_없다', () => {
    // given
    mockWorks([work()]);

    // when
    renderHome();

    // then(존재): 내려받기 조작은 있다 — 자리 자체가 없어서 통과하는 공허한 부재 단언을 막는다
    expect(screen.getByTestId('portal-work-download-10')).toBeInTheDocument();
    // then(부재): 멈출 것이 없는데 취소가 떠 있으면 무엇을 멈추는지 알 수 없다
    expect(screen.queryByTestId('portal-work-download-cancel-10')).toBeNull();
  });

  it('★데이터마트_축_취소가_전송을_실제로_중단하고_다시_받을_수_있게_한다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted(datamartDownloadMock, 1);
    mockWorks([work({ rawSn: 42 })]);
    renderHome();
    await user.click(screen.getByTestId('portal-work-download-42'));

    // then: 진행 중에만 나타나고, 진행 표시(내려받기 버튼)와 달리 비활성이 아니다
    const cancel = await screen.findByTestId('portal-work-download-cancel-42');
    expect(cancel).toBeEnabled();
    expect(cancel).toHaveAccessibleName(/CLIP-10/);
    expect(cancel).toHaveAccessibleName(/취소/);

    // when
    await user.click(cancel);

    // then: 다시 받을 수 있는 상태로 돌아온다(영구 고착 없음)
    const button = screen.getByTestId('portal-work-download-42');
    await waitFor(() => expect(button).toBeEnabled());
    expect(button).toHaveTextContent('내려받기');
    expect(screen.queryByTestId('portal-work-download-cancel-42')).toBeNull();
    // then: 사용자가 스스로 멈춘 것이므로 오류 안내를 띄우지 않는다
    expect(screen.queryByTestId('portal-work-download-error')).toBeNull();
    expect(screen.queryByText(DOWNLOAD_ERROR_INTERRUPTED)).toBeNull();

    // when: 다시 누르면 다시 요청이 나간다
    datamartDownloadMock.mockResolvedValue(undefined);
    await user.click(button);

    // then
    await waitFor(() => expect(datamartDownloadMock).toHaveBeenCalledTimes(2));
  });

  /*
   * ★★ 이 파일의 핵심 가드. 업로드 축 내보내기 창구에는 중단 수단이 **아예 없었다** — 그대로 두면
   *    한 목록 안에서 데이터마트 행은 취소가 되고 업로드 행은 안 되는 화면이 된다.
   */
  it('★업로드_축_취소도_전송을_실제로_중단한다_중단_신호가_창구까지_간다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted(uploadExportMock, 2);
    mockWorks([
      work({ rawSn: 43, videoName: 'MY-UPLOAD', assetSource: 'PORTAL_UPLOAD' }),
    ]);
    renderHome();
    await user.click(screen.getByTestId('portal-work-download-43'));

    // then: 중단 신호가 창구 인자로 실제로 실려 갔다 — 신호를 받아만 두고 요청에 싣지 않으면
    //       '취소' 는 버튼만 있고 전송은 계속되는 거짓 조작이 된다
    await waitFor(() => expect(uploadExportMock).toHaveBeenCalledTimes(1));
    const signal = uploadExportMock.mock.calls[0][2] as AbortSignal;
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(signal.aborted).toBe(false);

    // when
    await user.click(await screen.findByTestId('portal-work-download-cancel-43'));

    // then: 신호가 실제로 끊겼고, 화면은 다시 받을 수 있는 상태로 돌아온다
    expect(signal.aborted).toBe(true);
    await waitFor(() => expect(screen.getByTestId('portal-work-download-43')).toBeEnabled());
    // then: 사용자가 누른 취소는 정상 종료다
    expect(screen.queryByTestId('portal-work-download-error')).toBeNull();
  });

  /*
   * ★ 통합을 뒤집지 않았음을 함께 단언한다. «사용자 취소만» 앞에서 갈라내는 것이지,
   *   회선 단절·제한시간 초과까지 조용히 삼키면 진짜 장애가 아무 안내 없이 사라진다.
   */
  it('취소하지_않은_전송_중단은_여전히_안내된다_통합을_뒤집지_않는다', async () => {
    // given: 사용자는 아무것도 누르지 않았는데 응답이 오지 않는다
    const user = userEvent.setup();
    datamartDownloadMock.mockRejectedValue(ApiError.fromStatus(0, 'Network Error'));
    mockWorks([work()]);
    renderHome();

    // when
    await user.click(screen.getByTestId('portal-work-download-10'));

    // then
    const alert = await screen.findByTestId('portal-work-download-error');
    expect(alert).toHaveTextContent(DOWNLOAD_ERROR_INTERRUPTED);
  });
});
