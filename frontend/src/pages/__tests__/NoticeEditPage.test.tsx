import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { NoticeEditPage } from '@/pages/NoticeEditPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return { ...actual, useNavigate: () => navigateMock };
});

function detailBody(attachments: unknown[] = []) {
  return {
    success: true,
    data: {
      id: 7,
      title: '점검 안내',
      content: '02:00 점검',
      pinned: false,
      pubStatus: 'DRAFT',
      pubDt: null,
      regId: 'reviewer',
      writerName: null,
      regDt: '2026-06-01T00:00:00',
      mdfcnDt: null,
      attachments,
    },
    message: null,
    errorCode: null,
  };
}

function renderEditPage() {
  return renderWithProviders(<div />, {
    initialEntries: ['/notice/7/edit'],
    routes: [{ path: '/notice/:id/edit', element: <NoticeEditPage /> }],
  });
}

describe('NoticeEditPage (공지 수정 전용 화면)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
  });

  it('수정_화면은_전용_URL로_직접_진입하고_기존_값이_채워진다', async () => {
    // given: 상세 조회가 기존 내용을 돌려준다.
    mock.onGet('/notices/7').reply(200, detailBody());

    // when: 상세 화면을 거치지 않고 /notice/7/edit 로 바로 들어온다.
    renderEditPage();

    // then: 모달이 아니라 페이지가 뜨고 폼이 기존 값으로 초기화된다.
    expect(await screen.findByDisplayValue('점검 안내')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '공지 수정' })).toBeInTheDocument();
    expect(screen.getByDisplayValue('02:00 점검')).toBeInTheDocument();
  });

  it('조회_실패시_폼_대신_오류_상태를_보여준다', async () => {
    // given
    mock.onGet('/notices/7').reply(500);

    // when
    renderEditPage();

    // then: 빈 폼을 먼저 보여 주면 기존 내용이 지워진 것으로 읽고 그대로 저장할 수 있다.
    expect(await screen.findByText('공지를 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull();
    expect(screen.queryByRole('button', { name: '파일 추가' })).toBeNull();
    expect(screen.getByRole('button', { name: /다시 시도/ })).toBeInTheDocument();
  });

  it('저장_성공시_상세로_이동하고_성공_토스트를_띄운다', async () => {
    mock.onGet('/notices/7').reply(200, detailBody());
    mock.onPut('/notices/7').reply(200, detailBody());
    renderEditPage();
    await screen.findByDisplayValue('점검 안내');

    await userEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/notice/7');
    });
    expect(useUiStore.getState().toasts.map((t) => t.message)).toContain(
      '공지를 수정했습니다.',
    );
  });

  it('취소하면_해당_게시글_상세로_돌아간다', async () => {
    mock.onGet('/notices/7').reply(200, detailBody());
    renderEditPage();
    await screen.findByDisplayValue('점검 안내');

    await userEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(navigateMock).toHaveBeenCalledWith('/notice/7');
  });

  // 아래 2건은 폐기된 NoticeEditModal 의 R3-4 첨부 커버리지를 이 화면으로 이관한 것이다.
  // 첨부 관리는 게시글 id 가 발급된 수정 화면만 갖는다(작성 화면에는 섹션 자체가 없다).
  it('첨부_선택시_업로드하고_성공_토스트를_띄운다', async () => {
    mock.onGet('/notices/7').reply(200, detailBody());
    mock.onPost('/notices/7/attachments').reply(200, {
      success: true,
      data: { attachSn: 11, fileName: 'spec.pdf', fileSize: 5, regDt: '2026-06-01T00:00:00' },
      message: null,
      errorCode: null,
    });
    renderEditPage();
    await screen.findByDisplayValue('점검 안내');

    const fileInput = screen.getByLabelText('첨부파일 선택') as HTMLInputElement;
    await userEvent.upload(
      fileInput,
      new File(['dummy'], 'spec.pdf', { type: 'application/pdf' }),
    );

    await waitFor(() => {
      expect(mock.history.post).toHaveLength(1);
    });
    await waitFor(() => {
      expect(useUiStore.getState().toasts.map((t) => t.message)).toContain(
        '첨부파일을 업로드했습니다.',
      );
    });
  });

  it('기존_첨부는_파일명과_크기와_함께_행별로_삭제된다', async () => {
    mock
      .onGet('/notices/7')
      .reply(
        200,
        detailBody([
          { attachSn: 11, fileName: '지침.pdf', fileSize: 2048, regDt: '2026-06-01T00:00:00' },
        ]),
      );
    mock.onDelete('/notices/7/attachments/11').reply(204);
    renderEditPage();

    expect(await screen.findByText('지침.pdf')).toBeInTheDocument();
    expect(screen.getByText('(2.0 KB)')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '지침.pdf 삭제' }));

    await waitFor(() => {
      expect(mock.history.delete).toHaveLength(1);
    });
    await waitFor(() => {
      expect(useUiStore.getState().toasts.map((t) => t.message)).toContain(
        '첨부파일을 삭제했습니다.',
      );
    });
  });
});
