// 포털 업로드 **자산 목록** — 내려받기 두 갈래와 취소 회귀 가드. @design SCREEN-033
//
// ★ 이 조작들은 폐기된 업로드 자산 라벨링 화면에서 **이 목록으로 옮겨 왔다**(확정 사양 —
//   자산 단위 조작이므로 자산이 늘어놓인 자리가 제 위치다). 옮기면서 아래 계약을 잃지 않는다.
//
// 이 파일이 고정하는 계약:
//  - 취소는 **대용량 경로에만** 둔다. 원본 파일은 최대 5GB 라 대상이고, 라벨 내보내기(JSON)는
//    작아서 취소 버튼이 뜨기 전에 끝나므로 두지 않는다.
//  - ★ **사용자 취소는 오류가 아니라 정상 종료다** — 실패 토스트를 띄우지 않는다. 취소하면 응답이
//    오지 않아 일반 실패와 같은 모양으로 올라오므로, 갈라 놓지 않으면 스스로 멈춘 사용자에게
//    «원본 다운로드에 실패했습니다» 가 뜬다.
//  - 취소하지 않은 실패는 **여전히** 안내된다(통합을 뒤집지 않는다).
//  - 취소 후에는 다시 받을 수 있는 상태로 돌아온다.
//  - 행이 여럿이므로 **접근 이름에 파일명이 붙는다** — 붙지 않으면 같은 이름의 버튼이 자산 수만큼
//    생겨 보조기술 사용자가 어느 자산인지 가릴 수 없다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { ApiError } from '@/lib/api/errors';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

import { PortalUploadPage } from '../PortalUploadPage';

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

const FILE_NAME = 'clip.mp4';
const EXPORT_BTN = `${FILE_NAME} 내보내기(JSON)`;
const FILE_BTN = `${FILE_NAME} 원본 다운로드`;
const CANCEL_BTN = `${FILE_NAME} 원본 다운로드 취소`;

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function readyAsset() {
  return {
    uldSn: 1,
    uldTypeCd: 'VIDEO',
    orgnlFileNm: FILE_NAME,
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 3,
    frmeSn: null,
    regDt: '2026-07-17T00:00:00',
    expiresAt: null,
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

async function renderList() {
  renderWithProviders(<PortalUploadPage />, { initialEntries: ['/portal/uploads'] });
  await screen.findByTestId('portal-upload-item-1');
}

describe('포털 업로드 자산 목록 — 내려받기와 취소', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useUiStore.setState({ toasts: [] });
    downloadFileMock.mockReset();
    downloadFileMock.mockResolvedValue(undefined);
    mock
      .onGet('/portal/uploads')
      .reply(
        200,
        ok({ content: [readyAsset()], totalElements: 1, totalPages: 1, number: 0, size: 20 }),
      );
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useUiStore.setState({ toasts: [] });
    vi.restoreAllMocks();
  });

  it('준비_완료_자산에_내려받기_두_갈래가_있다', async () => {
    // given / when
    await renderList();

    // then
    expect(screen.getByRole('button', { name: EXPORT_BTN })).toBeEnabled();
    expect(screen.getByRole('button', { name: FILE_BTN })).toBeEnabled();
  });

  it('내려받기_전에는_취소_조작이_없다', async () => {
    // given / when
    await renderList();

    // then
    expect(screen.queryByRole('button', { name: CANCEL_BTN })).toBeNull();
  });

  it('원본을_내려받는_동안에만_취소_조작이_보인다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    await renderList();

    // when
    await user.click(screen.getByRole('button', { name: FILE_BTN }));

    // then: 취소는 눌러야 하므로 진행 중 비활성 대상에서 제외된다
    const cancel = await screen.findByRole('button', { name: CANCEL_BTN });
    expect(cancel).toBeEnabled();
    // then: 진행 사실은 기존 관례(aria-busy)로 전달한다
    expect(screen.getByRole('button', { name: FILE_BTN })).toHaveAttribute('aria-busy', 'true');
  });

  /* ★★ 핵심 가드 — 스스로 멈춘 사용자에게 «실패했습니다» 가 뜨면 거짓 안내다. */
  it('취소하면_실패_토스트를_띄우지_않는다_정상_종료다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    await renderList();
    await user.click(screen.getByRole('button', { name: FILE_BTN }));

    // when
    await user.click(await screen.findByRole('button', { name: CANCEL_BTN }));

    // then: 진행 표시가 풀린 뒤에도 오류 토스트가 없다
    await waitFor(() => expect(screen.getByRole('button', { name: FILE_BTN })).toBeEnabled());
    expect(useUiStore.getState().toasts.filter((t) => t.variant === 'error')).toHaveLength(0);
  });

  it('취소하면_다시_받을_수_있는_상태로_돌아온다', async () => {
    // given
    const user = userEvent.setup();
    hangUntilAborted();
    await renderList();
    await user.click(screen.getByRole('button', { name: FILE_BTN }));
    await user.click(await screen.findByRole('button', { name: CANCEL_BTN }));

    // then: 취소 조작이 걷히고 두 버튼이 원래대로 돌아온다(영구 고착 없음)
    await waitFor(() => expect(screen.getByRole('button', { name: FILE_BTN })).toBeEnabled());
    expect(screen.getByRole('button', { name: EXPORT_BTN })).toBeEnabled();
    expect(screen.queryByRole('button', { name: CANCEL_BTN })).toBeNull();

    // when: 다시 누르면 다시 요청이 나간다
    downloadFileMock.mockResolvedValue(undefined);
    await user.click(screen.getByRole('button', { name: FILE_BTN }));

    // then
    await waitFor(() => expect(downloadFileMock).toHaveBeenCalledTimes(2));
  });

  it('취소하지_않은_실패는_여전히_안내된다_통합을_뒤집지_않는다', async () => {
    // given: 사용자는 아무것도 누르지 않았는데 실패한다
    const user = userEvent.setup();
    downloadFileMock.mockRejectedValue(ApiError.fromStatus(0, 'Network Error'));
    await renderList();

    // when
    await user.click(screen.getByRole('button', { name: FILE_BTN }));

    // then
    await waitFor(() =>
      expect(useUiStore.getState().toasts.filter((t) => t.variant === 'error')).toHaveLength(1),
    );
  });

  /*
   * ★ 사양 — 취소는 대용량 경로에만 둔다. 라벨 내보내기(JSON)는 작아서 취소 버튼이 뜨기 전에
   *   끝나므로 두지 않는다. 여기에 취소를 더하면 «작아서 두지 않는다» 는 판단이 코드에서 지워진다.
   */
  it('라벨_내보내기_JSON_에는_취소를_두지_않는다', async () => {
    // given: export 응답을 붙잡아 둔다
    const user = userEvent.setup();
    mock.onGet('/portal/uploads/1/export').reply(() => new Promise(() => {}));
    await renderList();

    // when
    await user.click(screen.getByRole('button', { name: EXPORT_BTN }));

    // then: 진행 중이어도 취소 조작이 생기지 않는다
    // ⚠ 2026-09-16 — 잠긴 걸음을 **속성으로 잠그지 않는다**(WCAG 2.1.1 — native `disabled` 는
    //   Tab 순서에서 빠져 못 누르는 사유에 닿을 길이 사라진다). 구 기대값 `toBeDisabled()` 는
    //   폐기하고 `aria-disabled` + 사유 도달성으로 바꾼다. 아래 「다시 누르면 다시 요청이
    //   나간다」가 실제 차단(눌러도 아무 일도 없다)을 함께 지킨다.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: FILE_BTN })).toHaveAttribute(
        'aria-disabled',
        'true',
      ),
    );
    expect(screen.getByRole('button', { name: FILE_BTN })).toHaveAccessibleDescription(
      /다른 내려받기가 끝난 뒤에/,
    );
    expect(
      screen.queryByRole('button', { name: new RegExp(`${FILE_NAME} 내보내기.*취소`) }),
    ).toBeNull();
    expect(screen.queryByRole('button', { name: CANCEL_BTN })).toBeNull();

    // ★ 잠김이 표시가 아니라 **실제 차단**인지 눌러서 확인한다 — `aria-disabled` 는 눌림 자체를
    //   막지 않으므로, 이 한 줄이 없으면 「모양만 잠긴 버튼」이 통과한다.
    await user.click(screen.getByRole('button', { name: FILE_BTN }));
    expect(downloadFileMock).not.toHaveBeenCalled();
  });

  it('내보내기는_export_엔드포인트를_호출한다', async () => {
    // given
    const user = userEvent.setup();
    mock.onGet('/portal/uploads/1/export').reply(200, '{"ok":true}', {
      'content-disposition': 'attachment; filename="upload-1.json"',
    });
    await renderList();

    // when
    await user.click(screen.getByRole('button', { name: EXPORT_BTN }));

    // then
    await waitFor(() =>
      expect(mock.history.get.some((r) => r.url === '/portal/uploads/1/export')).toBe(true),
    );
  });

  it('준비_완료가_아닌_자산에는_내려받기를_두지_않는다', async () => {
    // given: 아직 준비되지 않은 자산 — 내보낼 라벨도 라벨링을 거친 결과도 없다
    mock.reset();
    mock.onGet('/portal/uploads').reply(
      200,
      ok({
        content: [{ ...readyAsset(), uldSttsCd: 'UPLOADED' }],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      }),
    );

    // when
    await renderList();

    // then
    expect(screen.queryByRole('button', { name: EXPORT_BTN })).toBeNull();
    expect(screen.queryByRole('button', { name: FILE_BTN })).toBeNull();
  });
});
