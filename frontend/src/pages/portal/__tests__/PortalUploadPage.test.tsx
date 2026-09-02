// Phase 5 — 포털 업로드 화면. 목록/상태/폴링전환/삭제/사전검증 동선을 검증한다.
// 서버 검증이 진실원이므로 여기서는 FE 렌더/동선/사전 안내만 확인(mock hooks).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUpload } from '@/features/portal/uploads/types';
import { ApiError } from '@/lib/api/errors';

import { PortalUploadPage } from '../PortalUploadPage';

const usePortalUploadsMock = vi.fn();
const useUploadImagesMock = vi.fn();
const useDeleteUploadMock = vi.fn();

vi.mock('@/features/portal/uploads/hooks/usePortalUploads', () => ({
  usePortalUploads: (params: unknown) => usePortalUploadsMock(params),
}));
vi.mock('@/features/portal/uploads/hooks/useUploadImages', () => ({
  useUploadImages: () => useUploadImagesMock(),
}));
vi.mock('@/features/portal/uploads/hooks/useDeleteUpload', () => ({
  useDeleteUpload: () => useDeleteUploadMock(),
}));

function up(over: Partial<PortalUpload> = {}): PortalUpload {
  return {
    uldSn: 1,
    uldTypeCd: 'IMAGE',
    orgnlFileNm: 'a.jpg',
    fileSz: 1024,
    mimeTypeNm: 'image/jpeg',
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

function mockUpload(uploadAsync = vi.fn().mockResolvedValue([])) {
  useUploadImagesMock.mockReturnValue({ uploadAsync, isPending: false, progress: 0 });
  return uploadAsync;
}

function mockDelete(deleteAsync = vi.fn().mockResolvedValue(undefined), error: unknown = null) {
  useDeleteUploadMock.mockReturnValue({ deleteAsync, isPending: false, error });
  return deleteAsync;
}

function imgFile(name: string, type = 'image/jpeg'): File {
  return new File([new Uint8Array([1, 2, 3])], name, { type });
}

afterEach(() => {
  usePortalUploadsMock.mockReset();
  useUploadImagesMock.mockReset();
  useDeleteUploadMock.mockReset();
  vi.restoreAllMocks();
});

describe('PortalUploadPage', () => {
  it('이미지_선택시_multipart_업로드_호출과_목록_갱신', async () => {
    const user = userEvent.setup();
    mockList([]);
    const uploadAsync = mockUpload();
    mockDelete();

    renderWithProviders(<PortalUploadPage />);

    const input = screen.getByLabelText(/이미지 파일/);
    await user.upload(input, [imgFile('a.jpg'), imgFile('b.jpg')]);

    await user.click(screen.getByRole('button', { name: /이미지 업로드/ }));

    await waitFor(() => expect(uploadAsync).toHaveBeenCalledTimes(1));
    const passed = uploadAsync.mock.calls[0][0] as File[];
    expect(passed).toHaveLength(2);
  });

  it('허용외_확장자_선택시_클라이언트_사전_차단_안내', async () => {
    const user = userEvent.setup();
    mockList([]);
    const uploadAsync = mockUpload();
    mockDelete();

    renderWithProviders(<PortalUploadPage />);

    const input = screen.getByLabelText(/이미지 파일/) as HTMLInputElement;
    // accept 속성(1차 가드)을 우회해 사용자가 허용외 파일을 넣은 상황을 시뮬레이션(fireEvent 는
    // accept 필터를 적용하지 않음) — 우리 클라이언트 사전검증(안전망)이 차단·안내하는지 검증한다.
    fireEvent.change(input, { target: { files: [imgFile('evil.gif', 'image/gif')] } });

    // 서버 정책을 명시한 안내가 노출되고, 업로드 호출은 발생하지 않는다.
    expect(await screen.findByRole('alert')).toHaveTextContent(/jpg|jpeg|png/i);
    const uploadBtn = screen.getByRole('button', { name: /이미지 업로드/ });
    await user.click(uploadBtn);
    expect(uploadAsync).not.toHaveBeenCalled();
  });

  it('PROCESSING_자산은_폴링되고_READY_전환시_라벨링_버튼_활성화', async () => {
    mockUpload();
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
    mockList([up({ uldSn: 9, orgnlFileNm: 'del.jpg' })]);
    mockUpload();
    const deleteAsync = mockDelete();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-9');
    await user.click(within(row).getByRole('button', { name: /삭제/ }));

    await waitFor(() => expect(deleteAsync).toHaveBeenCalledWith(9));
  });

  it('삭제_취소시_요청_없음', async () => {
    const user = userEvent.setup();
    mockList([up({ uldSn: 9, orgnlFileNm: 'del.jpg' })]);
    mockUpload();
    const deleteAsync = mockDelete();
    vi.spyOn(window, 'confirm').mockReturnValue(false);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-9');
    await user.click(within(row).getByRole('button', { name: /삭제/ }));

    expect(deleteAsync).not.toHaveBeenCalled();
  });

  it('PROCESSING_자산_삭제버튼_비활성화', () => {
    mockUpload();
    mockDelete();
    mockList([up({ uldSn: 7, uldSttsCd: 'PROCESSING', orgnlFileNm: 'clip.mp4', uldTypeCd: 'VIDEO' })]);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-7');
    expect(within(row).getByRole('button', { name: /삭제/ })).toBeDisabled();
  });

  it('삭제_실패시_alert_노출', () => {
    mockUpload();
    const conflict = new ApiError({ errorCode: 'CONFLICT', status: 409, message: '충돌' });
    mockDelete(vi.fn().mockRejectedValue(conflict), conflict);
    mockList([up({ uldSn: 8, uldSttsCd: 'READY', orgnlFileNm: 'a.jpg' })]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.getByRole('alert')).toHaveTextContent(/처리 중 자산은 삭제할 수 없습니다/);
  });

  it('FAILED_자산은_실패_사유_노출', () => {
    mockUpload();
    mockDelete();
    mockList([up({ uldSn: 3, uldSttsCd: 'FAILED', failRsnCn: '프레임 추출 실패', orgnlFileNm: 'x.mp4', uldTypeCd: 'VIDEO' })]);

    renderWithProviders(<PortalUploadPage />);

    const row = screen.getByTestId('portal-upload-item-3');
    expect(within(row).getByText(/프레임 추출 실패/)).toBeInTheDocument();
  });

  it('사용자_파일명은_텍스트노드로_렌더_XSS_방어', () => {
    mockUpload();
    mockDelete();
    const xss = '<img src=x onerror=alert(1)>.jpg';
    mockList([up({ uldSn: 2, orgnlFileNm: xss })]);

    const { container } = renderWithProviders(<PortalUploadPage />);
    // 텍스트로 그대로 노출(escape) — 실제 img 태그로 주입되지 않아야 한다.
    expect(screen.getByText(xss)).toBeInTheDocument();
    expect(container.querySelector('img[src="x"]')).toBeNull();
  });
});
