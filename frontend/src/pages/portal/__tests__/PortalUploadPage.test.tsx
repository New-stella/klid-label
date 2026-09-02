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
    expect(screen.getByLabelText(/영상 파일/)).toBeInTheDocument();
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

  it('삭제_확인_후_목록에서_제거', async () => {
    const user = userEvent.setup();
    mockList([up({ uldSn: 9, orgnlFileNm: 'del.mp4' })]);
    const deleteAsync = mockDelete();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-9');
    await user.click(within(row).getByRole('button', { name: /삭제/ }));

    await waitFor(() => expect(deleteAsync).toHaveBeenCalledWith(9));
  });

  it('삭제_취소시_요청_없음', async () => {
    const user = userEvent.setup();
    mockList([up({ uldSn: 9, orgnlFileNm: 'del.mp4' })]);
    const deleteAsync = mockDelete();
    vi.spyOn(window, 'confirm').mockReturnValue(false);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-9');
    await user.click(within(row).getByRole('button', { name: /삭제/ }));

    expect(deleteAsync).not.toHaveBeenCalled();
  });

  it('PROCESSING_자산_삭제버튼_비활성화', () => {
    mockDelete();
    mockList([up({ uldSn: 7, uldSttsCd: 'PROCESSING', orgnlFileNm: 'clip.mp4', uldTypeCd: 'VIDEO' })]);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-7');
    expect(within(row).getByRole('button', { name: /삭제/ })).toBeDisabled();
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
