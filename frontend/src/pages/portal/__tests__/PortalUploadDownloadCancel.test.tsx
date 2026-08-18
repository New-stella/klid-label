// 포털 업로드 라벨링 — **원본 파일** 다운로드 취소 회귀 가드. @design SCREEN-034
//
// 이 파일이 고정하는 계약:
//  - 취소는 **대용량 경로에만** 둔다. 원본 파일은 최대 5GB 라 대상이고, 라벨 내보내기(JSON)는
//    작아서 취소 버튼이 뜨기 전에 끝나므로 두지 않는다.
//  - ★ **사용자 취소는 오류가 아니라 정상 종료다** — 실패 토스트를 띄우지 않는다. 취소하면 응답이
//    오지 않아 일반 실패와 같은 모양으로 올라오므로, 갈라 놓지 않으면 스스로 멈춘 사용자에게
//    «원본 다운로드에 실패했습니다» 가 뜬다.
//  - 취소하지 않은 실패는 **여전히** 안내된다(통합을 뒤집지 않는다).
//  - 취소 후에는 다시 받을 수 있는 상태로 돌아온다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { ApiError } from '@/lib/api/errors';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { PortalUploadLabelingPage } from '../PortalUploadLabelingPage';

const downloadFileMock = vi.fn();
vi.mock('@/features/portal/uploads/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/portal/uploads/api')>();
  return {
    ...actual,
    downloadUploadFile: (uldSn: number, fallbackName: string, signal?: AbortSignal) =>
      downloadFileMock(uldSn, fallbackName, signal),
  };
});

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function detail() {
  return {
    uldSn: 1,
    uldTypeCd: 'IMAGE',
    orgnlFileNm: 'photo.jpg',
    fileSz: 1024,
    mimeTypeNm: 'image/jpeg',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    vdoLenSec: null,
    fps: null,
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
    frames: [{ uldFrmeSn: 100, uldSn: 1, frmeNo: 0, regDt: '2026-07-17T00:00:00' }],
  };
}

/** 취소해야만 끝나는(그리고 취소 시 응답 없는 실패로 올라오는) 다운로드. 실제 경로와 같은 모양. */
function hangUntilAborted() {
  downloadFileMock.mockImplementation(
    (_uldSn: number, _name: string, signal?: AbortSignal) =>
      new Promise<void>((_resolve, reject) => {
        signal?.addEventListener('abort', () => reject(ApiError.fromStatus(0, 'canceled')));
      }),
  );
}

function renderPage() {
  return renderWithProviders(<PortalUploadLabelingPage />, {
    initialEntries: ['/portal/uploads/1/label'],
    routes: [{ path: '/portal/uploads/:uldSn/label', element: <PortalUploadLabelingPage /> }],
  });
}

async function renderReady() {
  renderPage();
  await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
}

describe('포털 업로드 라벨링 — 원본 다운로드 취소', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    downloadFileMock.mockReset();
    downloadFileMock.mockResolvedValue(undefined);
    mock.onGet('/manage/labels').reply(200, ok([]));
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    vi.restoreAllMocks();
  });

  it('내려받기_전에는_취소_조작이_없다', async () => {
    // given / when
    await renderReady();

    // then
    expect(screen.queryByRole('button', { name: '원본 다운로드 취소' })).toBeNull();
  });

  it('원본을_내려받는_동안에만_취소_조작이_보인다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    await renderReady();

    // when
    await user.click(screen.getByRole('button', { name: '원본 다운로드' }));

    // then: 취소는 눌러야 하므로 진행 중 비활성 대상에서 제외된다
    const cancel = await screen.findByRole('button', { name: '원본 다운로드 취소' });
    expect(cancel).toBeEnabled();
    // then: 진행 사실은 기존 관례(aria-busy)로 전달한다
    expect(screen.getByRole('button', { name: '원본 다운로드' })).toHaveAttribute(
      'aria-busy',
      'true',
    );
  });

  /* ★★ 핵심 가드 — 스스로 멈춘 사용자에게 «실패했습니다» 가 뜨면 거짓 안내다. */
  it('취소하면_실패_토스트를_띄우지_않는다_정상_종료다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    await renderReady();
    await user.click(screen.getByRole('button', { name: '원본 다운로드' }));

    // when
    await user.click(await screen.findByRole('button', { name: '원본 다운로드 취소' }));

    // then: 진행 표시가 풀린 뒤에도 오류 토스트가 없다
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '원본 다운로드' })).toBeEnabled(),
    );
    expect(useUiStore.getState().toasts.filter((t) => t.variant === 'error')).toHaveLength(0);
  });

  it('취소하면_다시_받을_수_있는_상태로_돌아온다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    await renderReady();
    await user.click(screen.getByRole('button', { name: '원본 다운로드' }));
    await user.click(await screen.findByRole('button', { name: '원본 다운로드 취소' }));

    // then: 취소 조작이 걷히고 두 버튼이 원래대로 돌아온다(영구 고착 없음)
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '원본 다운로드' })).toBeEnabled(),
    );
    expect(screen.getByRole('button', { name: '내보내기(JSON)' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '원본 다운로드 취소' })).toBeNull();

    // when: 다시 누르면 다시 요청이 나간다
    downloadFileMock.mockResolvedValue(undefined);
    await user.click(screen.getByRole('button', { name: '원본 다운로드' }));

    // then
    await waitFor(() => expect(downloadFileMock).toHaveBeenCalledTimes(2));
  });

  it('취소하지_않은_실패는_여전히_안내된다_통합을_뒤집지_않는다', async () => {
    // given: 사용자는 아무것도 누르지 않았는데 실패한다
    const user = userEvent.setup();
    downloadFileMock.mockRejectedValue(ApiError.fromStatus(0, 'Network Error'));
    await renderReady();

    // when
    await user.click(screen.getByRole('button', { name: '원본 다운로드' }));

    // then
    await waitFor(() =>
      expect(useUiStore.getState().toasts.filter((t) => t.variant === 'error')).toHaveLength(1),
    );
  });

  /*
   * ★ 사양 — 취소는 대용량 두 경로에만 둔다. 라벨 내보내기(JSON)는 작아서 취소 버튼이 뜨기 전에
   *   끝나므로 두지 않는다. 여기에 취소를 더하면 «작아서 두지 않는다» 는 판단이 코드에서 지워진다.
   */
  it('라벨_내보내기_JSON_에는_취소를_두지_않는다', async () => {
    // given: export 응답을 붙잡아 둔다
    const user = userEvent.setup();
    mock.onGet('/portal/uploads/1/export').reply(() => new Promise(() => {}));
    await renderReady();

    // when
    await user.click(screen.getByRole('button', { name: '내보내기(JSON)' }));

    // then: 진행 중이어도 취소 조작이 생기지 않는다
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '원본 다운로드' })).toBeDisabled(),
    );
    expect(screen.queryByRole('button', { name: /내보내기.*취소/ })).toBeNull();
    expect(screen.queryByRole('button', { name: '원본 다운로드 취소' })).toBeNull();
  });
});
