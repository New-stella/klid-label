// Phase 5 — 포털 업로드 화면. 목록/상태/폴링전환/삭제 동선을 검증한다.
// 서버 검증이 진실원이므로 여기서는 FE 렌더/동선만 확인(mock hooks).
//
// ★ 신규 접수는 **영상뿐**이다 — 이미지 접수 자리가 없다는 것을 아래 부정 케이스가 지킨다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUpload } from '@/features/portal/uploads/types';
import { ApiError } from '@/lib/api/errors';

import { PortalUploadPage } from '../PortalUploadPage';

const usePortalUploadsMock = vi.fn();
const useDeleteUploadMock = vi.fn();

vi.mock('@/features/portal/uploads/hooks/usePortalUploads', () => ({
  usePortalUploads: (params: unknown) => usePortalUploadsMock(params),
}));
vi.mock('@/features/portal/uploads/hooks/useDeleteUpload', () => ({
  useDeleteUpload: () => useDeleteUploadMock(),
}));

function up(over: Partial<PortalUpload> = {}): PortalUpload {
  return {
    uldSn: 1,
    // 신규 접수는 영상뿐이라 기본 픽스처도 영상이다.
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'a.mp4',
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    frmeSn: 10,
    regDt: '2026-07-17T00:00:00',
    expiresAt: null,
    ...over,
  };
}

function mockList(content: PortalUpload[]) {
  usePortalUploadsMock.mockReturnValue({
    data: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 },
    isLoading: false,
    isError: false,
  });
}

function mockDelete(deleteAsync = vi.fn().mockResolvedValue(undefined), error: unknown = null) {
  useDeleteUploadMock.mockReturnValue({ deleteAsync, isPending: false, error });
  return deleteAsync;
}

afterEach(() => {
  usePortalUploadsMock.mockReset();
  useDeleteUploadMock.mockReset();
  vi.restoreAllMocks();
});

describe('PortalUploadPage', () => {
  /*
   * ★ 이미지 접수 축 폐기 — **부정 케이스가 이 축의 본체다.**
   *
   * 「영상 접수 자리가 있다」는 시험은 이미 있었지만 「이미지 접수 자리가 없다」는 없었다. 그래서
   * 이미지 업로드 구획이 화면에 그대로 남아 있어도 전건이 초록이었고, 사용자가 **세 차례** 지적할
   * 때까지 아무도 알려 주지 않았다. 아래 두 케이스는 그 구획을 되살리는 변경에서 죽는다.
   *
   * ⚠ 접수 축만 본다 — 자산 종류 값역(`PortalUploadType.IMAGE`)과 그 투영인 응답 enum 은 이미
   *   적재된 행의 판독용이라 **남는 것이 정상**이다. 여기서 그것까지 금지하면 안 된다.
   */
  it('이미지_접수_자리가_화면에_없다', () => {
    mockDelete();
    mockList([]);

    renderWithProviders(<PortalUploadPage />);

    // 파일 선택 입력 · 실행 버튼 · 정책 안내 — 접수 자리를 이루는 세 요소가 모두 없다.
    expect(screen.queryByLabelText(/이미지 파일/)).toBeNull();
    expect(screen.queryByRole('button', { name: /이미지 업로드/ })).toBeNull();
    expect(screen.queryByRole('region', { name: /이미지 업로드/ })).toBeNull();
    // 이미지 확장자 정책 문구(jpg/jpeg/png)가 어디에도 노출되지 않는다.
    expect(document.body.textContent ?? '').not.toMatch(/jpe?g|png/i);
    // 화면 안내 문구도 이미지를 받는다고 말하지 않는다.
    expect(document.body.textContent ?? '').not.toContain('이미지');
  });

  it('영상_접수_자리는_그대로_있다', () => {
    mockDelete();
    mockList([]);

    renderWithProviders(<PortalUploadPage />);

    // 짝 케이스 — 위 부정 케이스가 «접수 자리를 통째로 지워도» 통과하는 것을 막는다.
    // ⚠ 2026-09-16 — 받침이 파선 드롭존에서 KRDS 파일 받침으로 바뀌면서 「영상 파일」이
    //   `<label>` 이 아니라 **받침의 제목**이 됐다. 구 기대값 `getByLabelText(/영상 파일/)` 은
    //   폐기. 대신 ①제목 ②실제 파일 입력 ③그 입력이 받는 확장자 셋을 함께 고정한다 —
    //   라벨 한 줄만 보던 구 단언보다 오히려 넓다(껍데기만 남겨도 죽는다).
    expect(screen.getByRole('heading', { name: '영상 파일' })).toBeInTheDocument();
    const picker = document.querySelector('input[type="file"]');
    expect(picker).not.toBeNull();
    expect(picker?.getAttribute('accept')).toBe('.mp4,.mov,.avi');
    expect(screen.getByRole('button', { name: /영상 업로드/ })).toBeInTheDocument();
    expect(document.body.textContent ?? '').toMatch(/mp4/);
  });

  it('PROCESSING_자산은_폴링되고_READY_전환시_라벨링_버튼_활성화', async () => {
    mockDelete();

    // 1) PROCESSING 상태 — 라벨링 버튼 없음
    mockList([up({ uldSn: 5, uldSttsCd: 'PROCESSING', orgnlFileNm: 'clip.mp4', uldTypeCd: 'VIDEO' })]);
    const { rerender } = renderWithProviders(<PortalUploadPage />);
    expect(screen.queryByRole('link', { name: /라벨링/ })).toBeNull();

    // 2) READY 전환(폴링 결과) — 라벨링 진입 링크 활성화
    mockList([up({ uldSn: 5, uldSttsCd: 'READY', orgnlFileNm: 'clip.mp4', uldTypeCd: 'VIDEO' })]);
    rerender(<PortalUploadPage />);
    const link = await screen.findByRole('link', { name: /라벨링/ });
    // 통합 라벨링 화면으로 보낸다 — 업로드 자산 전용 라벨링 화면은 폐기됐다(@design SCREEN-029).
    // 값을 통째로 고정한다 — 진입 주소를 만드는 함수를 시험이 같이 부르면 그 함수가 어떤 값을
    // 만들든 항상 초록이라, 「목록이 통합 화면으로 보낸다」를 지키지 못한다.
    expect(link).toHaveAttribute('href', '/portal/label/5?source=upload');
  });

  /*
   * ⚠ 2026-09-16 — **묻는 자리가 브라우저 기본 창에서 포털 확인 창으로 바뀌었다**(시안 결정).
   *   구 기대값(`window.confirm` 을 가로채 참·거짓을 돌려준다)은 폐기다. 지키는 것은 그대로다 —
   *   ①한 번 더 묻고 ②확인해야만 요청이 나가며 ③취소하면 나가지 않는다. 오히려 **기본 창을
   *   쓰지 않는다**는 축이 하나 늘었다(아래 단언).
   */
  it('삭제는_확인_창을_거쳐야_요청이_나간다', async () => {
    const user = userEvent.setup();
    mockList([up({ uldSn: 9, orgnlFileNm: 'del.mp4' })]);
    const deleteAsync = mockDelete();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-9');
    await user.click(within(row).getByRole('button', { name: /삭제/ }));

    // 누른 것만으로는 아직 나가지 않는다 — 화면 안 창이 떠서 한 번 더 묻는다.
    expect(deleteAsync).not.toHaveBeenCalled();
    expect(confirmSpy).not.toHaveBeenCalled();
    const dialog = within(await screen.findByRole('dialog'));
    expect(dialog.getByText(/되돌릴 수 없습니다/)).toBeInTheDocument();

    await user.click(dialog.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(deleteAsync).toHaveBeenCalledWith(9));
    confirmSpy.mockRestore();
  });

  it('삭제_확인_창에서_취소하면_요청_없음', async () => {
    const user = userEvent.setup();
    mockList([up({ uldSn: 9, orgnlFileNm: 'del.mp4' })]);
    const deleteAsync = mockDelete();

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-9');
    await user.click(within(row).getByRole('button', { name: /삭제/ }));
    const dialog = within(await screen.findByRole('dialog'));
    await user.click(dialog.getByRole('button', { name: '취소' }));

    expect(deleteAsync).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('PROCESSING_자산_삭제버튼_비활성화', () => {
    mockDelete();
    mockList([up({ uldSn: 7, uldSttsCd: 'PROCESSING', orgnlFileNm: 'clip.mp4', uldTypeCd: 'VIDEO' })]);

    renderWithProviders(<PortalUploadPage />);

    /*
     * ⚠ 2026-09-16 — **속성으로 잠그지 않는다**(`disabled` 미사용). WCAG 2.1.1 — native
     *   `disabled` 는 Tab 순서에서 빠져 **왜 못 누르는지 알 길이 사라진다.** 구 기대값
     *   `toBeDisabled()` 는 폐기하고 ①`aria-disabled` ②초점이 남는다 ③사유가 붙는다 셋으로
     *   바꾼다 — 잠김 자체는 그대로 지키면서 사유 도달성까지 함께 고정한다.
     */
    const row = screen.getByTestId('portal-upload-item-7');
    const del = within(row).getByRole('button', { name: /삭제/ });
    expect(del).toHaveAttribute('aria-disabled', 'true');
    expect(del).not.toHaveAttribute('tabindex', '-1');
    expect(del).toHaveAccessibleDescription(/프레임을 뽑는 중에는 지울 수 없습니다/);
  });

  it('삭제_실패시_alert_노출', () => {
    const conflict = new ApiError({ errorCode: 'CONFLICT', status: 409, message: '충돌' });
    mockDelete(vi.fn().mockRejectedValue(conflict), conflict);
    mockList([up({ uldSn: 8, uldSttsCd: 'READY', orgnlFileNm: 'a.mp4' })]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.getByRole('alert')).toHaveTextContent(/처리 중 자산은 삭제할 수 없습니다/);
  });

  it('FAILED_자산은_실패_사유_노출', () => {
    mockDelete();
    mockList([up({ uldSn: 3, uldSttsCd: 'FAILED', failRsnCn: '프레임 추출 실패', orgnlFileNm: 'x.mp4', uldTypeCd: 'VIDEO' })]);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-3');
    expect(within(row).getByText(/프레임 추출 실패/)).toBeInTheDocument();
  });

  it('사용자_파일명은_텍스트노드로_렌더_XSS_방어', () => {
    mockDelete();
    const xss = '<img src=x onerror=alert(1)>.mp4';
    mockList([up({ uldSn: 2, orgnlFileNm: xss })]);

    const { container } = renderWithProviders(<PortalUploadPage />);
    // 텍스트로 그대로 노출(escape) — 실제 img 태그로 주입되지 않아야 한다.
    expect(screen.getByText(xss)).toBeInTheDocument();
    expect(container.querySelector('img[src="x"]')).toBeNull();
  });
});
