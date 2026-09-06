// 포털 내 작업 — **작업 데이터 내려받기** 회귀 가드. @design SCREEN-028, API-203
//
// 이 파일이 고정하는 계약:
//  - ★★ **내려받기 가부의 판정은 `lastSavedAt` 이지 `labelCount` 가 아니다.** 라벨을 하나도 만들지
//    않고 메타·이벤트 어노테이션만 고쳐도 그것은 저작물이고 묶음에 담겨 나간다. 아래 첫 케이스가
//    **라벨 0건 + 저장 이력 있음** 이라는, 두 판정이 정반대 결론을 내는 픽스처로 그 축을 고정한다.
//  - ★ 창구는 **출처마다 다르다** — 데이터마트 축은 작업 데이터 묶음 창구, 업로드 축은 그 자산의
//    내보내기 창구다. 한쪽으로 합치면 다른 축이 없는 자원을 조회한다.
//  - 한 번에 하나만 받으며 받는 동안 다른 행의 내려받기는 잠긴다.
//  - 실패 사유는 갈라 안내한다(요청량 초과 / 비식별 재처리 / 저장 라벨 없음 / 권한 / 전송 끊김).
//    서버 원문 메시지는 노출하지 않는다(CWE-209).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUserWork } from '@/features/portal/api';
import {
  DOWNLOAD_ERROR_DEIDENT,
  DOWNLOAD_ERROR_FORBIDDEN,
  DOWNLOAD_ERROR_INTERRUPTED,
  DOWNLOAD_ERROR_NO_LABEL,
  DOWNLOAD_ERROR_RATE_LIMIT,
} from '@/features/portal/downloadError';
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

describe('포털 내 작업 — 내려받기 가부 판정', () => {
  /*
   * ★★ 이 파일의 핵심 가드. 판정을 `labelCount` 로 바꾸면 이 케이스가 죽어야 한다 — 라벨 0건인데
   *    저장 이력이 있는 행이라 두 판정의 결론이 정반대다. (판정을 `labelCount > 0` 으로 되돌리는
   *    변이를 실제로 넣어 RED 를 확인했다.)
   */
  it('★라벨_0건이어도_저장_이력이_있으면_내려받을_수_있다_판정은_lastSavedAt_이다', async () => {
    // given: 라벨 없이 메타만 고친 행 — labelCount 는 0 인데 저작물은 있다
    const user = userEvent.setup();
    mockWorks([work({ rawSn: 55, labelCount: 0, lastSavedAt: '2026-06-02T09:00:00' })]);
    renderHome();

    // then
    const button = screen.getByTestId('portal-work-download-55');
    expect(button).toBeEnabled();

    // when: 실제로 요청이 나간다
    await user.click(button);

    // then
    await waitFor(() => expect(datamartDownloadMock).toHaveBeenCalledTimes(1));
    expect(datamartDownloadMock).toHaveBeenCalledWith(55, expect.anything());
  });

  it('★저장_이력이_없는_행은_내려받기가_막히고_사유가_드러난다', async () => {
    // given: 업로드만 하고 아직 아무 저작물도 남기지 않은 행
    const user = userEvent.setup();
    mockWorks([
      work({
        rawSn: 66,
        assetSource: 'PORTAL_UPLOAD',
        labelCount: 0,
        lastSavedAt: null,
        entrySrcSn: 600,
      }),
    ]);
    renderHome();

    // then(존재): 행은 목록에 그대로 있다
    expect(screen.getByTestId('portal-work-row-66')).toBeInTheDocument();
    // then: 내려받기만 막힌다 + 사유가 눈으로도 보조기술로도 읽힌다
    const button = screen.getByTestId('portal-work-download-66');
    expect(button).toBeDisabled();
    expect(screen.getByText('아직 저장한 작업이 없어 내려받을 것이 없습니다.')).toBeInTheDocument();
    expect(button).toHaveAccessibleDescription('아직 저장한 작업이 없어 내려받을 것이 없습니다.');
    // then: 이어서 작업은 함께 막히지 않는다(프레임이 있으므로)
    expect(screen.getByTestId('portal-work-continue-66')).toHaveAttribute('href');

    // when: 눌러도 요청이 나가지 않는다
    await user.click(button);

    // then
    expect(datamartDownloadMock).not.toHaveBeenCalled();
    expect(uploadExportMock).not.toHaveBeenCalled();
  });
});

describe('포털 내 작업 — 출처별 창구', () => {
  it('★데이터마트_행은_작업_데이터_묶음_창구로_받는다', async () => {
    // given
    const user = userEvent.setup();
    mockWorks([work({ rawSn: 71, assetSource: 'DATAMART' })]);
    renderHome();

    // when
    await user.click(screen.getByTestId('portal-work-download-71'));

    // then(존재)
    await waitFor(() => expect(datamartDownloadMock).toHaveBeenCalledWith(71, expect.anything()));
    // then(부재): 업로드 창구로 새지 않는다
    expect(uploadExportMock).not.toHaveBeenCalled();
  });

  it('★업로드_행은_그_자산의_내보내기_창구로_받는다', async () => {
    // given: 업로드 축에서 rawSn 이 곧 자산 식별자다
    const user = userEvent.setup();
    mockWorks([
      work({ rawSn: 72, assetSource: 'PORTAL_UPLOAD', lastSavedAt: '2026-06-02T09:00:00' }),
    ]);
    renderHome();

    // when
    await user.click(screen.getByTestId('portal-work-download-72'));

    // then(존재)
    await waitFor(() => expect(uploadExportMock).toHaveBeenCalledTimes(1));
    expect(uploadExportMock).toHaveBeenCalledWith(72, undefined, expect.anything());
    // then(부재): 데이터마트 창구로 새지 않는다
    expect(datamartDownloadMock).not.toHaveBeenCalled();
  });
});

describe('포털 내 작업 — 진행 표시와 동시 실행 방지', () => {
  it('받는_동안_진행이_보이고_다른_행의_내려받기가_잠긴다', async () => {
    // given: 응답이 끝나지 않은 상태를 붙잡아 둔다
    const user = userEvent.setup();
    const hold = { release: () => {} };
    datamartDownloadMock.mockReturnValue(
      new Promise<void>((resolve) => {
        hold.release = resolve;
      }),
    );
    mockWorks([work({ rawSn: 81 }), work({ rawSn: 82, videoName: 'CLIP-82' })]);
    renderHome();

    // when
    await user.click(screen.getByTestId('portal-work-download-81'));

    // then: 진행 중임이 눈으로도 보조기술로도 전달되고 중복 실행이 막힌다
    const button = screen.getByTestId('portal-work-download-81');
    await waitFor(() => expect(button).toHaveTextContent('내려받는 중…'));
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button).toBeDisabled();
    // then: 다른 행도 함께 잠긴다(한 번에 하나)
    expect(screen.getByTestId('portal-work-download-82')).toBeDisabled();

    // when: 끝나면 원래대로 돌아온다(영구히 갇히지 않는다)
    hold.release();
    await waitFor(() => expect(button).not.toHaveAttribute('aria-busy'));
    expect(button).toBeEnabled();
    expect(screen.getByTestId('portal-work-download-82')).toBeEnabled();
  });
});

describe('포털 내 작업 — 실패 사유 안내', () => {
  it.each([
    [429, DOWNLOAD_ERROR_RATE_LIMIT, '요청량 초과'],
    [412, DOWNLOAD_ERROR_DEIDENT, '비식별 재처리 구간'],
    [410, DOWNLOAD_ERROR_NO_LABEL, '저장 라벨 없음'],
    [403, DOWNLOAD_ERROR_FORBIDDEN, '권한 없음'],
    [0, DOWNLOAD_ERROR_INTERRUPTED, '전송 끊김'],
  ])('실패_사유_%s_는_구분되어_안내된다_(%s)', async (status, message) => {
    // given
    const user = userEvent.setup();
    datamartDownloadMock.mockRejectedValue(
      ApiError.fromStatus(status as number, '서버 내부 사유 — 노출되면 안 된다'),
    );
    mockWorks([work()]);
    renderHome();

    // when
    await user.click(screen.getByTestId('portal-work-download-10'));

    // then
    const alert = await screen.findByTestId('portal-work-download-error');
    expect(alert).toHaveTextContent(message as string);
    expect(alert).toHaveAttribute('role', 'alert');
    // then: 서버 원문은 노출되지 않는다(CWE-209)
    expect(screen.queryByText(/서버 내부 사유/)).toBeNull();
  });

  it('업로드_축의_실패도_같은_자리에서_사유별로_안내된다', async () => {
    // given: 두 축이 한 목록에 있으므로 실패 안내도 한 자리여야 한다
    const user = userEvent.setup();
    uploadExportMock.mockRejectedValue(ApiError.fromStatus(403, '내부 사유'));
    mockWorks([work({ rawSn: 91, assetSource: 'PORTAL_UPLOAD' })]);
    renderHome();

    // when
    await user.click(screen.getByTestId('portal-work-download-91'));

    // then
    const alert = await screen.findByTestId('portal-work-download-error');
    expect(alert).toHaveTextContent(DOWNLOAD_ERROR_FORBIDDEN);
  });
});
