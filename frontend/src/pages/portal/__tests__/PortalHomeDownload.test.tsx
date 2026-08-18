// 포털 홈 — 만료 예정일 병기 + 작업 데이터 다운로드 배선 회귀 가드. @design SCREEN-028
//
// 이 파일이 고정하는 계약:
//  - 만료 예정일은 **날짜까지만** 적고, 만료가 없는 상태(저장 라벨 없음)에서는 **자리를 비운다**
//    (`-`·`없음` 같은 문구를 지어내지 않는다).
//  - ★ **만료 표기와 다운로드 가부는 근거가 다르다.** 만료가 비는 이유는 둘이라(저장 라벨 없음 /
//    보존기간 설정 부재·비정상) 만료만 보고 버튼을 막으면 정상 다운로드를 화면이 먼저 차단하고
//    거짓 사유까지 댄다. 저장 라벨 0건은 서버가 410 으로 구분해 돌려주므로 그 판정을 그대로 안내한다.
//    (구 가드 «만료가 없으면 비활성 + "저장된 라벨이 없습니다" 툴팁» 은 폐기 — 아래에서 반대로 단언한다.)
//  - 다운로드 클릭이 **카드(라벨링 진입)로 전파되지 않는다** — 내려받으려던 사용자가 화면을 떠나면 안 된다.
//  - 실패 안내는 사유별로 **갈라진다**(요청량 초과 / 비식별 재처리 / 저장 라벨 없음 / 권한 없음 /
//    전송 중단). 한 문구로 합치면 사용자는 "다시 시도"만 반복하게 된다.
//  - 만료 임박 강조는 **두지 않는다**(사양에 없는 것을 더하지 않는다).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { DatamartVideo } from '@/features/portal/api';
import {
  DOWNLOAD_ERROR_DEIDENT,
  DOWNLOAD_ERROR_FORBIDDEN,
  DOWNLOAD_ERROR_INTERRUPTED,
  DOWNLOAD_ERROR_NO_LABEL,
  DOWNLOAD_ERROR_RATE_LIMIT,
} from '@/features/portal/downloadError';
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
    downloadDatamartVideoData: (rawSn: number) => downloadMock(rawSn),
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

describe('포털 홈 — 만료 예정일 표기', () => {
  it('만료_예정일은_날짜까지만_적는다', () => {
    // given: 서버가 시각까지 내려준다
    mockVideos([video({ myLabelExpiresAt: '2026-06-08T10:30:45' })]);

    // when
    renderHome();

    // then: 날짜까지만 — 시각은 표기하지 않는다(사양)
    expect(screen.getByTestId('datamart-video-expiry')).toHaveTextContent('만료: 2026-06-08');
    expect(screen.queryByText(/10:30/)).toBeNull();
  });

  it('만료가_없으면_자리를_비운다_임의_문구를_지어내지_않는다', () => {
    // given: 본인 저장 라벨이 없어 만료가 성립하지 않는다
    mockVideos([video({ myLabelExpiresAt: null })]);

    // when
    renderHome();

    // then: 만료 표기 자체가 비어 있다. `-`·`없음` 같은 문구는 "만료가 정해졌는데 표기만 빈 것"으로
    // 읽히므로 쓰지 않는다.
    expect(screen.getByTestId('datamart-video-expiry')).toHaveTextContent('');
    expect(screen.queryByText(/만료/)).toBeNull();
  });

  it('만료_임박_강조는_두지_않는다', () => {
    // given: 오늘 만료되는 영상
    const today = new Date().toISOString().slice(0, 10);
    mockVideos([video({ myLabelExpiresAt: `${today}T00:00:00` })]);

    // when
    renderHome();

    // then: 날짜만 적고 «임박»·«곧 삭제» 같은 사양에 없는 강조를 더하지 않는다
    expect(screen.getByTestId('datamart-video-expiry')).toHaveTextContent(`만료: ${today}`);
    expect(screen.queryByText(/임박|곧 삭제|D-/)).toBeNull();
  });
});

describe('포털 홈 — 작업 데이터 다운로드', () => {
  /*
   * ★ 구 가드 «만료가 없으면 비활성 + "저장된 라벨이 없습니다" 툴팁» 을 **뒤집는다**(폐기 사유는
   *   파일 머리말 참조). 만료가 비는 원인은 둘인데(저장 라벨 없음 / 보존기간 설정 부재·비정상)
   *   화면은 그 둘을 구분할 근거가 없다 — 목록 응답에 저장 라벨 유무 필드가 없다. 후자에서는
   *   서버가 정상 응답을 주므로 구 동작은 **정상 다운로드를 화면이 먼저 막고 거짓 사유를 대는** 것이었다.
   */
  it('만료가_없어도_다운로드_버튼을_막지_않는다_저장_라벨_없음으로_단정하지_않는다', async () => {
    // given: 만료가 없다 — 저장 라벨이 없어서일 수도, 보존기간 설정이 없어서일 수도 있다
    const user = userEvent.setup();
    mockVideos([video({ rawSn: 55, myLabelExpiresAt: null })]);
    renderHome();

    // then: 화면이 추정으로 막지 않는다
    const button = screen.getByTestId('datamart-download-button');
    expect(button).toBeEnabled();
    // then: 있지도 않은 사유를 지어내지 않는다
    expect(button).not.toHaveAttribute('title');
    expect(screen.queryByText(/저장된 라벨이 없습니다/)).toBeNull();

    // when: 눌러 보면 판정은 서버가 한다
    await user.click(button);

    // then
    await waitFor(() => expect(downloadMock).toHaveBeenCalledTimes(1));
    expect(downloadMock).toHaveBeenCalledWith(55);
  });

  it('저장_라벨_0건_판정은_서버가_하고_그_사유가_그대로_안내된다', async () => {
    // given: 만료가 없는 영상 — 서버는 저장 라벨 0건을 410 으로 구분해 돌려준다
    const user = userEvent.setup();
    downloadMock.mockRejectedValue(ApiError.fromStatus(410, '다운로드할 작업 데이터가 없습니다.'));
    mockVideos([video({ myLabelExpiresAt: null })]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-download-button'));

    // then: 화면이 미리 단정하는 대신 서버 판정을 안내한다
    const alert = await screen.findByTestId('datamart-download-error');
    expect(alert).toHaveTextContent(DOWNLOAD_ERROR_NO_LABEL);
  });

  it('저장_라벨이_있으면_버튼이_활성이고_해당_영상으로_요청한다', async () => {
    // given
    const user = userEvent.setup();
    mockVideos([video({ rawSn: 77, myLabelExpiresAt: '2026-06-08T10:00:00' })]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-download-button'));

    // then
    await waitFor(() => expect(downloadMock).toHaveBeenCalledTimes(1));
    expect(downloadMock).toHaveBeenCalledWith(77);
  });

  /*
   * ★ 전파 차단은 **구조로** 보장한다 — 다운로드 버튼을 카드(라벨링 진입) 클릭 영역 **밖**에 둔다.
   *   카드 안에 넣으면 ①버튼 안의 버튼이라 마크업이 성립하지 않고 ②클릭이 위로 전파돼 내려받으려던
   *   사용자가 라벨링 화면으로 끌려간다. 그래서 «클릭해도 안 넘어간다» 라는 동작뿐 아니라 «중첩되어
   *   있지 않다» 라는 구조도 함께 단언한다 — 동작만 보면 중첩으로 되돌려도 테스트가 통과할 수 있다.
   *   (핸들러의 stopPropagation 은 감싸는 컨테이너가 생겼을 때를 위한 이중 방어다.)
   */
  it('다운로드_버튼은_카드_클릭_영역_밖에_있고_클릭이_라벨링으로_전파되지_않는다', async () => {
    // given
    const user = userEvent.setup();
    mockVideos([video({ rawSn: 77, firstSrcSn: 100 })]);
    renderHome();

    // then(구조): 카드 클릭 영역의 자손이 아니다
    const card = screen.getByTestId('datamart-video-item');
    const downloadButton = screen.getByTestId('datamart-download-button');
    expect(card.contains(downloadButton)).toBe(false);

    // when
    await user.click(downloadButton);
    await waitFor(() => expect(downloadMock).toHaveBeenCalledTimes(1));

    // then(동작): 화면을 떠나지 않는다 — 내려받으려던 사용자가 라벨링으로 끌려가면 안 된다
    expect(screen.queryByTestId('label-route')).toBeNull();
    expect(screen.getByTestId('datamart-download-button')).toBeInTheDocument();
  });

  it('카드_본문_클릭은_그대로_라벨링으로_진입한다', async () => {
    // given: 다운로드 버튼을 분리해도 기존 동선이 살아 있어야 한다
    const user = userEvent.setup();
    mockVideos([video({ rawSn: 77, firstSrcSn: 100 })]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-video-item'));

    // then
    await waitFor(() => expect(screen.getByTestId('label-route')).toBeInTheDocument());
    expect(downloadMock).not.toHaveBeenCalled();
  });

  it.each([
    [429, DOWNLOAD_ERROR_RATE_LIMIT, '요청량 초과'],
    [412, DOWNLOAD_ERROR_DEIDENT, '비식별 재처리 구간'],
    [410, DOWNLOAD_ERROR_NO_LABEL, '저장 라벨 없음'],
    [403, DOWNLOAD_ERROR_FORBIDDEN, '권한 없음'],
  ])('실패_사유_%s_는_구분되어_안내된다_(%s)', async (status, message) => {
    // given
    const user = userEvent.setup();
    downloadMock.mockRejectedValue(
      ApiError.fromStatus(status as number, '서버 내부 사유 — 노출되면 안 된다'),
    );
    mockVideos([video()]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-download-button'));

    // then: 사유별 문구가 뜨고, 서버 원문 메시지는 노출되지 않는다(CWE-209)
    const alert = await screen.findByTestId('datamart-download-error');
    expect(alert).toHaveTextContent(message as string);
    expect(alert).toHaveAttribute('role', 'alert');
    expect(screen.queryByText(/서버 내부 사유/)).toBeNull();
  });

  /*
   * ★ 응답이 아예 오지 않는 실패(전송 중단·네트워크 단절·제한시간 초과)는 서버가 준 네 가지와
   *   **다른 문구**여야 한다. 일반 실패로 뭉개면 «잠시 후 다시 시도» 가 뜨는데, 이 응답은 GB 급이라
   *   그 재시도마다 서버는 전량을 다시 보내고 버린다.
   */
  it('전송이_끊긴_실패는_일반_실패로_뭉개지지_않고_따로_안내된다', async () => {
    // given: 상태코드 없는 실패(응답 없음)
    const user = userEvent.setup();
    downloadMock.mockRejectedValue(ApiError.fromStatus(0, 'timeout of 1800000ms exceeded'));
    mockVideos([video()]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-download-button'));

    // then
    const alert = await screen.findByTestId('datamart-download-error');
    expect(alert).toHaveTextContent(DOWNLOAD_ERROR_INTERRUPTED);
    // then: 기다림이 답이 아닌 상황에 재시도를 권하지 않는다
    expect(alert).not.toHaveTextContent('잠시 후');
    // then: axios 원문(영문 메시지)은 노출되지 않는다(CWE-209)
    expect(screen.queryByText(/timeout of/)).toBeNull();
  });

  /*
   * ★ 진행 표시 — 이 요청은 GB 급이라 오래 걸린다. 버튼 이름을 `aria-label` 이 정하므로 바뀐 본문
   *   ('내려받는 중…')은 보조기술에 읽히지 않는다 → 진행 사실은 `aria-busy` 로 전달한다.
   */
  it('내려받는_동안_진행_중임이_보이고_보조기술에도_전달된다', async () => {
    // given: 응답이 끝나지 않은 상태를 붙잡아 둔다
    const user = userEvent.setup();
    let finish: (() => void) | undefined;
    downloadMock.mockReturnValue(
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
    );
    mockVideos([video()]);
    renderHome();

    // when
    await user.click(screen.getByTestId('datamart-download-button'));

    // then: 눈으로도(본문) 보조기술로도(aria-busy) 진행 중임을 알 수 있고 중복 실행이 막힌다
    const button = await screen.findByTestId('datamart-download-button');
    await waitFor(() => expect(button).toHaveTextContent('내려받는 중…'));
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button).toBeDisabled();

    // when: 끝나면 원래대로 돌아온다(영구히 갇히지 않는다)
    finish?.();
    await waitFor(() => expect(button).not.toHaveAttribute('aria-busy'));
    expect(button).toBeEnabled();
  });

  it('네_가지_실패_문구는_서로_다르다', () => {
    // given / when
    const messages = [
      DOWNLOAD_ERROR_RATE_LIMIT,
      DOWNLOAD_ERROR_DEIDENT,
      DOWNLOAD_ERROR_NO_LABEL,
      DOWNLOAD_ERROR_FORBIDDEN,
    ];

    // then: 하나로 합치면 사용자는 "다시 시도"만 반복하게 된다
    expect(new Set(messages).size).toBe(4);
  });
});
