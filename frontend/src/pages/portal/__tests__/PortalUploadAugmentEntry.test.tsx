// 회귀 가드 — 포털 업로드 목록 상단의 「증강 요청 현황·결과」 진입. [@design SCREEN-033]
//
// ★ **자산별 액션이 아니라 목록 상단에 한 번만** 둔다. 요청 이후의 현황·결과 확인·후속 작업·
//   내려받기는 전부 증강 화면이 담당하고, 이 화면은 그리로 가는 링크만 갖는다. 행마다 두면
//   같은 목적지로 가는 링크가 자산 수만큼 생겨 보조기술 사용자가 훑을 것이 그만큼 늘어난다.
//
// ⚠ 이 파일은 「증강 요청을 거는 액션」을 검사하지 않는다 — 그것은 자산별 액션이라 축이 다르고
//   `PortalUploadAugmentRequest.test.tsx` 가 담당한다. 여기서 「없다」고 단언하지 않는 이유이기도
//   하다(단언해 두면 그 가드가 정상적인 추가를 막는다).
//   ⚠ **구 서술 폐기**: *"그 조작은 접수 창구의 생성 조건 항목이 확정되지 않아 아직 만들지
//   못했다"* 는 더 이상 사실이 아니다 — 다섯 항목·닫힌 값역이 확정돼 요청 폼이 섰다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';

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

function mockUploads(rows: PortalUpload[]) {
  usePortalUploadsMock.mockReturnValue({
    data: { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 20 },
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  });
  useDeleteUploadMock.mockReturnValue({
    deleteAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
    error: null,
  });
}

beforeEach(() => {
  usePortalUploadsMock.mockReset();
  useDeleteUploadMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('포털 업로드 목록 — 증강 요청 현황·결과 진입', () => {
  it('★증강_화면으로_가는_링크가_있다', () => {
    mockUploads([up()]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.getByRole('link', { name: '증강 요청 현황·결과' })).toHaveAttribute(
      'href',
      '/portal/augment',
    );
  });

  it('★자산이_여럿이어도_링크는_하나다_행마다_두지_않는다', () => {
    mockUploads([up({ uldSn: 1 }), up({ uldSn: 2 }), up({ uldSn: 3 })]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.getAllByRole('link', { name: '증강 요청 현황·결과' })).toHaveLength(1);
  });

  it('자산이_하나도_없어도_링크가_남는다_다음에_갈_곳을_잃지_않는다', () => {
    mockUploads([]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.getByRole('link', { name: '증강 요청 현황·결과' })).toBeInTheDocument();
  });
});
