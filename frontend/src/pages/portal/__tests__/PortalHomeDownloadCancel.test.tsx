// 포털 홈 — 작업 데이터 다운로드 **취소** 회귀 가드. @design SCREEN-028
//
// 이 파일이 고정하는 계약:
//  - 진행 중일 때만 취소 조작이 보인다(진행 중이 아닌데 취소가 떠 있으면 무엇을 멈추는지 알 수 없다).
//  - ★ **사용자 취소는 오류가 아니라 정상 종료다** — 오류 안내를 띄우지 않는다.
//    취소하면 요청은 응답 없이 끝나 `status=0` 인 `ApiError` 로 올라오는데, 그 자리는 «전송이
//    끊겼습니다 … 연결이 안정적인 환경에서 다시» 를 안내하는 분기다. 갈라 놓지 않으면 **스스로 멈춘
//    사용자에게 회선을 탓하는 거짓 안내**가 뜨고, 심지어 «다시 시도» 를 권해 GB 급 전송을 다시 유발한다.
//  - ⚠ 그렇다고 «응답 없는 실패» 통합을 뒤집지 않는다 — 중단·네트워크 단절·제한시간 초과를 한 문구로
//    묶은 것은 «사용자가 할 일이 같다» 는 의도된 결정이다(downloadError.ts 머리말). **사용자 취소만**
//    그 판정보다 **앞에서** 갈라낸다. 아래 마지막 케이스가 그 통합이 살아 있음을 함께 단언한다.
//  - 취소 후에는 다시 받을 수 있는 상태로 돌아온다(버튼 잠금 해제).
//  - 취소 버튼도 카드(라벨링 진입) 클릭 영역 밖에 있어 화면을 떠나게 만들지 않는다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { DatamartVideo } from '@/features/portal/api';
import { DOWNLOAD_ERROR_INTERRUPTED } from '@/features/portal/downloadError';
import { ApiError } from '@/lib/api/errors';

import { PortalHomePage } from '../PortalHomePage';

const useDatamartVideosMock = vi.fn();
vi.mock('@/features/portal/hooks/useDatamartVideos', () => ({
  useDatamartVideos: (params: { page?: number; size?: number }) => useDatamartVideosMock(params),
}));

const downloadMock = vi.fn();
vi.mock('@/features/portal/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/portal/api')>();
  return {
    ...actual,
    downloadDatamartVideoData: (rawSn: number, signal?: AbortSignal) =>
      downloadMock(rawSn, signal),
  };
});

function video(over: Partial<DatamartVideo> = {}): DatamartVideo {
  return {
    rawSn: 10,
    title: 'CLIP-10',
    eventName: 'FALL',
    frameCount: 5,
    firstSrcSn: 100,
    lastUpdatedAt: '2026-06-01T10:00:00',
    myLabelExpiresAt: '2026-06-08T10:30:45',
    ...over,
  };
}

function mockVideos(content: DatamartVideo[]) {
  useDatamartVideosMock.mockReturnValue({
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
function hangUntilAborted() {
  downloadMock.mockImplementation((_rawSn: number, signal?: AbortSignal) => {
    return new Promise<void>((_resolve, reject) => {
      signal?.addEventListener('abort', () => {
        reject(ApiError.fromStatus(0, 'canceled'));
      });
    });
  });
}

function renderHome() {
  const Probe = () => <div data-testid="label-route">labeling</div>;
  return renderWithProviders(<PortalHomePage />, {
    initialEntries: ['/portal'],
    routes: [
      { path: '/portal', element: <PortalHomePage /> },
      { path: '/portal/label/:id', element: <Probe /> },
    ],
  });
}

beforeEach(() => {
  downloadMock.mockReset();
  downloadMock.mockResolvedValue(undefined);
});

afterEach(() => {
  useDatamartVideosMock.mockReset();
});

describe('포털 홈 — 작업 데이터 다운로드 취소', () => {
  it('내려받기_전에는_취소_조작이_없다', () => {
    // given
    mockVideos([video()]);

    // when
    renderHome();

    // then: 멈출 것이 없는데 취소가 떠 있으면 무엇을 멈추는지 알 수 없다
    expect(screen.queryByTestId('datamart-download-cancel')).toBeNull();
  });

  it('내려받는_동안에만_취소_조작이_보인다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    mockVideos([video()]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-download-button'));

    // then: 진행 중에는 보이고, 진행 표시(다운로드 버튼)와 달리 **비활성이 아니다**
    const cancel = await screen.findByTestId('datamart-download-cancel');
    expect(cancel).toBeEnabled();
    // then: 보조기술에도 무엇을 취소하는지 전달된다
    expect(cancel).toHaveAccessibleName(/CLIP-10/);
    expect(cancel).toHaveAccessibleName(/취소/);
  });

  /*
   * ★★ 이 파일의 핵심 가드. 취소했는데 «전송이 끊겼습니다 … 연결이 안정적인 환경에서» 가 뜨면
   *    사용자가 스스로 한 일을 장애로 뒤집어씌우는 거짓 안내다.
   */
  it('취소하면_오류_안내를_띄우지_않는다_정상_종료다', async () => {
    // given: 진행 중인 다운로드
    const user = userEvent.setup();
    hangUntilAborted();
    mockVideos([video()]);
    renderHome();
    await user.click(screen.getByTestId('datamart-download-button'));
    const cancel = await screen.findByTestId('datamart-download-cancel');

    // when: 사용자가 스스로 멈춘다
    await user.click(cancel);

    // then: 진행 표시가 풀린 뒤에도 오류 영역이 없다
    await waitFor(() =>
      expect(screen.getByTestId('datamart-download-button')).toBeEnabled(),
    );
    expect(screen.queryByTestId('datamart-download-error')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    // then: 특히 전송 중단 문구가 뜨면 안 된다 — 사용자는 회선을 의심하게 된다
    expect(screen.queryByText(DOWNLOAD_ERROR_INTERRUPTED)).toBeNull();
  });

  it('취소하면_다시_받을_수_있는_상태로_돌아온다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    mockVideos([video({ rawSn: 42 })]);
    renderHome();
    await user.click(screen.getByTestId('datamart-download-button'));
    await user.click(await screen.findByTestId('datamart-download-cancel'));

    // then: 진행 표시·취소 조작이 걷히고 버튼이 원래대로 돌아온다(영구 고착 없음)
    const button = screen.getByTestId('datamart-download-button');
    await waitFor(() => expect(button).toBeEnabled());
    expect(button).not.toHaveAttribute('aria-busy');
    expect(button).toHaveTextContent('다운로드');
    expect(screen.queryByTestId('datamart-download-cancel')).toBeNull();

    // when: 다시 누르면 다시 요청이 나간다
    downloadMock.mockResolvedValue(undefined);
    await user.click(button);

    // then
    await waitFor(() => expect(downloadMock).toHaveBeenCalledTimes(2));
    expect(downloadMock).toHaveBeenLastCalledWith(42, expect.anything());
  });

  it('취소_버튼은_카드_클릭_영역_밖에_있고_라벨링으로_전파되지_않는다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    mockVideos([video()]);
    renderHome();
    await user.click(screen.getByTestId('datamart-download-button'));
    const cancel = await screen.findByTestId('datamart-download-cancel');

    // then(구조): 카드 클릭 영역의 자손이 아니다 — 카드 안에 넣으면 버튼 안의 버튼이 된다
    expect(screen.getByTestId('datamart-video-item').contains(cancel)).toBe(false);

    // when
    await user.click(cancel);

    // then(동작): 멈추려던 사용자가 라벨링 화면으로 끌려가지 않는다
    expect(screen.queryByTestId('label-route')).toBeNull();
  });

  /*
   * ★ 통합을 뒤집지 않았음을 함께 단언한다. «사용자 취소만» 앞에서 갈라내는 것이지,
   *   회선 단절·제한시간 초과까지 조용히 삼키면 진짜 장애가 아무 안내 없이 사라진다.
   */
  it('취소하지_않은_전송_중단은_여전히_안내된다_통합을_뒤집지_않는다', async () => {
    // given: 사용자는 아무것도 누르지 않았는데 응답이 오지 않는다(회선 단절·제한시간 초과)
    const user = userEvent.setup();
    downloadMock.mockRejectedValue(ApiError.fromStatus(0, 'Network Error'));
    mockVideos([video()]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-download-button'));

    // then
    const alert = await screen.findByTestId('datamart-download-error');
    expect(alert).toHaveTextContent(DOWNLOAD_ERROR_INTERRUPTED);
  });
});
