// 포털 업로드 목록 — 보존기간 만료 예정일 표기 회귀 가드. @design SCREEN-033
//
// 이 파일이 고정하는 계약:
//  - 각 자산에 만료 예정일을 **날짜까지만** 병기한다.
//  - 만료가 **없는 상태**(처리 전·처리 중은 삭제 대상이 아니다)에서는 **자리를 비운다** —
//    `-`·`없음` 같은 문구를 지어내면 "만료가 정해졌는데 표기만 빈 것"으로 읽힌다.
//  - 만료 임박 강조는 두지 않는다(사양에 없는 것을 더하지 않는다).

import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUpload } from '@/features/portal/uploads/types';

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
    expiresAt: '2026-07-24T00:00:00',
    ...over,
  };
}

function renderList(content: PortalUpload[]) {
  usePortalUploadsMock.mockReturnValue({
    data: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 },
    isLoading: false,
    isError: false,
  });
  useDeleteUploadMock.mockReturnValue({
    deleteAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
    error: null,
  });
  return renderWithProviders(<PortalUploadPage />);
}

afterEach(() => {
  usePortalUploadsMock.mockReset();
  useDeleteUploadMock.mockReset();
});

describe('포털 업로드 목록 — 만료 예정일', () => {
  it('자산마다_만료_예정일을_날짜까지만_병기한다', () => {
    // given: 서버가 시각까지 내려준다
    renderList([up({ uldSn: 5, expiresAt: '2026-07-24T09:15:00' })]);

    // when / then: 날짜까지만 — 시각은 표기하지 않는다(사양)
    expect(screen.getByTestId('portal-upload-expiry-5')).toHaveTextContent('만료: 2026-07-24');
    expect(screen.queryByText(/09:15/)).toBeNull();
  });

  it('처리_중_자산처럼_만료가_없으면_자리를_비운다', () => {
    // given: 처리 중 자산은 삭제 대상이 아니라 만료가 **없다**
    renderList([up({ uldSn: 6, uldSttsCd: 'PROCESSING', expiresAt: null })]);

    // when / then: 표기 자체가 없다 — `-`·`없음` 을 지어내지 않는다
    const item = screen.getByTestId('portal-upload-item-6');
    expect(within(item).queryByTestId('portal-upload-expiry-6')).toBeNull();
    expect(within(item).queryByText(/만료/)).toBeNull();
  });

  it('만료가_있는_자산과_없는_자산이_한_목록에_섞여도_각각_맞게_그린다', () => {
    // given: READY(만료 있음) + UPLOADED(만료 없음)
    renderList([
      up({ uldSn: 7, uldSttsCd: 'READY', expiresAt: '2026-07-24T00:00:00' }),
      up({ uldSn: 8, uldSttsCd: 'UPLOADED', orgnlFileNm: 'b.mp4', expiresAt: null }),
    ]);

    // when / then
    expect(screen.getByTestId('portal-upload-expiry-7')).toHaveTextContent('만료: 2026-07-24');
    expect(screen.queryByTestId('portal-upload-expiry-8')).toBeNull();
  });

  it('만료_임박_강조는_두지_않는다', () => {
    // given: 오늘 만료
    const today = new Date().toISOString().slice(0, 10);
    renderList([up({ uldSn: 9, expiresAt: `${today}T00:00:00` })]);

    // when / then: 사양에 없는 강조를 더하지 않는다
    expect(screen.getByTestId('portal-upload-expiry-9')).toHaveTextContent(`만료: ${today}`);
    expect(screen.queryByText(/임박|곧 삭제|D-/)).toBeNull();
  });
});
