// 회귀 가드 — 포털 업로드 목록의 **증강 요청 폼**. [@design SCREEN-033] [@design API-231]
//
// ★ 「AI 증강 요청」은 **버튼 하나로 끝나지 않는다** — 누르면 생성 조건을 입력하는 폼이 화면 안
//   창으로 열리고, 다섯 항목을 모두 고른 뒤에야 요청이 나간다.
// ★★ **표시명은 우리말, 전송값은 코드**다.
// ★★★ **이벤트 유형·세부 유형·증강 종류 입력칸을 두지 않는다** — 이 축은 최근에 뒤집혔고 BE 가
//   모르는 필드를 조용히 무시하므로, 되살아나도 통신으로는 드러나지 않는다. 화면에서 막는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUpload } from '@/features/portal/uploads/types';

import { PortalUploadPage } from '../PortalUploadPage';

const usePortalUploadsMock = vi.fn();
const useDeleteUploadMock = vi.fn();
const requestAsyncMock = vi.fn();
const resetMock = vi.fn();
const requestStateRef: { isPending: boolean; error: unknown } = { isPending: false, error: null };

vi.mock('@/features/portal/uploads/hooks/usePortalUploads', () => ({
  usePortalUploads: (params: unknown) => usePortalUploadsMock(params),
}));
vi.mock('@/features/portal/uploads/hooks/useDeleteUpload', () => ({
  useDeleteUpload: () => useDeleteUploadMock(),
}));
// ⚠ 통신 훅만 막는다. 어휘(conditionOptions)·폼 컴포넌트는 **모의하지 않는다** — 모듈을 통째로
//   모의하면 그 파일이 함께 내보내는 판정 함수·상수까지 undefined 가 되어 화면이 조용히
//   「모르는 값」 분기로 떨어지는 함정이 이 저장소에 실재한다.
vi.mock('@/features/portal/uploads/hooks/useRequestUploadAugment', () => ({
  useRequestUploadAugment: () => ({
    requestAsync: requestAsyncMock,
    isPending: requestStateRef.isPending,
    error: requestStateRef.error,
    reset: resetMock,
  }),
}));

function up(over: Partial<PortalUpload> = {}): PortalUpload {
  return {
    uldSn: 1,
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'street.mp4',
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    frmeSn: 10,
    regDt: '2026-09-03T00:00:00',
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

/** 요청 폼을 연다. 반환값은 그 창 안에서만 찾도록 좁힌 범위다. */
async function openForm(user: ReturnType<typeof userEvent.setup>, name = 'street.mp4') {
  await user.click(screen.getByRole('button', { name: `${name} AI 증강 요청` }));
  return within(await screen.findByRole('dialog'));
}

/** 다섯 항목을 모두 고른다 — 보이는 것은 우리말이다. */
async function chooseAll(
  user: ReturnType<typeof userEvent.setup>,
  form: ReturnType<typeof within>,
) {
  await user.selectOptions(form.getByLabelText(/시간대/), '밤');
  await user.selectOptions(form.getByLabelText(/계절/), '겨울');
  await user.selectOptions(form.getByLabelText(/날씨/), '눈');
  await user.selectOptions(form.getByLabelText(/지형/), '도로');
  await user.selectOptions(form.getByLabelText(/심각도/), '높음');
}

beforeEach(() => {
  usePortalUploadsMock.mockReset();
  useDeleteUploadMock.mockReset();
  requestAsyncMock.mockReset();
  requestAsyncMock.mockResolvedValue({ augSn: 9001, uldSn: 1, requestedAt: 'x' });
  resetMock.mockReset();
  requestStateRef.isPending = false;
  requestStateRef.error = null;
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('포털 업로드 목록 — 증강 요청 액션 노출', () => {
  it('★준비_완료된_영상_행에만_요청_액션이_있다', () => {
    mockUploads([up({ uldSn: 1, orgnlFileNm: 'ready.mp4', uldSttsCd: 'READY' })]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.getByRole('button', { name: 'ready.mp4 AI 증강 요청' })).toBeInTheDocument();
  });

  it('★준비되지_않았거나_실패한_영상_행에는_아예_노출하지_않는다_비활성으로_두지_않는다', () => {
    mockUploads([
      up({ uldSn: 1, orgnlFileNm: 'a.mp4', uldSttsCd: 'UPLOADED' }),
      up({ uldSn: 2, orgnlFileNm: 'b.mp4', uldSttsCd: 'PROCESSING' }),
      up({ uldSn: 3, orgnlFileNm: 'c.mp4', uldSttsCd: 'FAILED' }),
    ]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.queryAllByRole('button', { name: /AI 증강 요청/ })).toHaveLength(0);
  });

  it('영상이_아닌_자산_행에는_두지_않는다', () => {
    mockUploads([up({ uldSn: 9, orgnlFileNm: 'p.png', uldTypeCd: 'IMAGE', uldSttsCd: 'READY' })]);

    renderWithProviders(<PortalUploadPage />);

    expect(screen.queryAllByRole('button', { name: /AI 증강 요청/ })).toHaveLength(0);
  });
});

describe('포털 업로드 목록 — 증강 요청 폼', () => {
  it('★버튼이_곧바로_요청을_보내지_않고_화면_안_창을_연다_브라우저_기본_창이_아니다', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mockUploads([up()]);
    const user = userEvent.setup();

    renderWithProviders(<PortalUploadPage />);
    const form = await openForm(user);

    expect(form.getByTestId('augment-request-target')).toHaveTextContent('street.mp4');
    expect(requestAsyncMock).not.toHaveBeenCalled();
    expect(confirmSpy).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('★다섯_항목을_다_고르기_전에는_요청을_보낼_수_없다', async () => {
    mockUploads([up()]);
    const user = userEvent.setup();

    renderWithProviders(<PortalUploadPage />);
    const form = await openForm(user);

    expect(form.getByRole('button', { name: '요청' })).toBeDisabled();

    await user.selectOptions(form.getByLabelText(/시간대/), '밤');
    await user.selectOptions(form.getByLabelText(/계절/), '겨울');
    await user.selectOptions(form.getByLabelText(/날씨/), '눈');
    await user.selectOptions(form.getByLabelText(/지형/), '도로');
    // 아직 심각도가 비었다 — 넷만 골라서는 보낼 수 없다.
    expect(form.getByRole('button', { name: '요청' })).toBeDisabled();

    await user.selectOptions(form.getByLabelText(/심각도/), '높음');
    expect(form.getByRole('button', { name: '요청' })).toBeEnabled();
  });

  it('★보이는_것은_우리말이고_보내는_것은_코드다', async () => {
    mockUploads([up({ uldSn: 77 })]);
    const user = userEvent.setup();

    renderWithProviders(<PortalUploadPage />);
    const form = await openForm(user);
    await chooseAll(user, form);
    await user.click(form.getByRole('button', { name: '요청' }));

    expect(requestAsyncMock).toHaveBeenCalledTimes(1);
    expect(requestAsyncMock.mock.calls[0][0]).toEqual({
      uldSn: 77,
      body: {
        generationCondition: {
          time: 'NIGHT',
          season: 'WINTER',
          weather: 'SNOW',
          terrain: 'ROAD',
          severity: 'HIGH',
        },
      },
    });
  });

  it('자유_지시문은_선택이며_적으면_최상위_문자열로_실린다', async () => {
    mockUploads([up()]);
    const user = userEvent.setup();

    renderWithProviders(<PortalUploadPage />);
    const form = await openForm(user);
    await chooseAll(user, form);
    await user.type(form.getByLabelText(/자유 지시문/), '눈 내리는 밤으로');
    await user.click(form.getByRole('button', { name: '요청' }));

    const body = requestAsyncMock.mock.calls[0][0].body as Record<string, unknown>;
    expect(body.prompt).toBe('눈 내리는 밤으로');
  });

  it('★이벤트_유형_세부_유형_증강_종류를_고르는_입력칸이_없다', async () => {
    mockUploads([up()]);
    const user = userEvent.setup();

    renderWithProviders(<PortalUploadPage />);
    const form = await openForm(user);

    // 고를 수 있는 것은 생성 조건 다섯뿐이다.
    expect(form.getAllByRole('combobox')).toHaveLength(5);
    for (const forbidden of [/이벤트 유형/, /세부 유형/, /증강 종류/]) {
      expect(form.queryByLabelText(forbidden)).toBeNull();
    }
  });

  it('접수_창구가_거부하면_사유가_창_안에_뜨고_창은_닫히지_않는다', async () => {
    mockUploads([up()]);
    const user = userEvent.setup();
    // 화면이 `instanceof ApiError` 로 판정하므로 실제 클래스를 쓴다 — 모양만 흉내 낸 객체를
    // 넣으면 일반 문구로 떨어져 「서버 사유를 그대로 보인다」를 검증하지 못한다.
    const { ApiError } = await import('@/lib/api/errors');
    requestStateRef.error = new ApiError({
      errorCode: 'CONFLICT',
      status: 409,
      message: '준비가 끝나지 않은 영상은 증강을 요청할 수 없습니다.',
    });

    renderWithProviders(<PortalUploadPage />);
    const form = await openForm(user);

    expect(form.getByTestId('augment-request-notice')).toHaveTextContent(
      '준비가 끝나지 않은 영상은 증강을 요청할 수 없습니다.',
    );
    // 고쳐서 다시 보낼 수 있어야 하므로 창이 살아 있다.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('취소하면_요청을_보내지_않고_창이_닫힌다', async () => {
    mockUploads([up()]);
    const user = userEvent.setup();

    renderWithProviders(<PortalUploadPage />);
    const form = await openForm(user);
    await user.click(form.getByRole('button', { name: '취소' }));

    expect(requestAsyncMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
