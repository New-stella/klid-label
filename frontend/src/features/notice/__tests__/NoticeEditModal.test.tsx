import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { NoticeEditModal } from '@/features/notice/components/NoticeEditModal';
import { NoticePubStatus, type Notice } from '@/features/notice/types';
import { renderWithProviders } from '@/test/renderWithProviders';

function editNotice(attachments: Notice['attachments'] = []): Notice {
  return {
    id: 7,
    title: '점검 안내',
    content: '02:00 점검',
    pinned: false,
    pubStatus: NoticePubStatus.DRAFT,
    pubDt: null,
    regId: 'reviewer',
    regDt: '2026-06-01T00:00:00',
    mdfcnDt: null,
    attachments,
  };
}

describe('NoticeEditModal', () => {
  it('작성_폼_제목_미입력시_검증오류', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <NoticeEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/내용/), {
      target: { value: '본문 내용' },
    });
    fireEvent.click(screen.getByText('작성'));

    await waitFor(() => {
      expect(screen.getByText(/제목은 필수/)).toBeInTheDocument();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('NoticeEditModal_에러_아이콘', async () => {
    // given: 제목 미입력 상태로 저장 → 검증 에러 발생
    renderWithProviders(
      <NoticeEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );
    fireEvent.change(screen.getByLabelText(/내용/), {
      target: { value: '본문 내용' },
    });
    fireEvent.click(screen.getByText('작성'));

    // then: 에러 메시지에 아이콘(svg)이 병기되어 색만으로 의존하지 않는다.
    const alert = await screen.findByRole('alert');
    expect(alert.querySelector('svg')).toBeInTheDocument();
  });

  it('정상_입력시_onSubmit_payload_전달', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <NoticeEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/제목/), {
      target: { value: '점검 안내' },
    });
    fireEvent.change(screen.getByLabelText(/내용/), {
      target: { value: '02:00 점검' },
    });
    fireEvent.click(screen.getByText('작성'));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      title: '점검 안내',
      content: '02:00 점검',
      pinned: false,
    });
  });

  // R3-4: 첨부 UI 는 수정 모드(id 발급 후)에서만 노출된다.
  // BE NoticeAttachController 는 /notices/{id}/attachments 라 신규 작성(id 미발급)에는
  // 첨부 input 을 두지 않는다 — 의도된 UX. 아래 테스트로 수정 모드 배선을 고정한다.
  it('R3-4_신규_작성_모드는_첨부_input_미노출_(id_미발급)', () => {
    renderWithProviders(
      <NoticeEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );
    expect(screen.queryByLabelText('첨부파일 선택')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '파일 추가' })).not.toBeInTheDocument();
  });

  it('R3-4_수정_모드는_파일_input_노출_및_선택시_onUploadAttachment_호출', () => {
    const onUploadAttachment = vi.fn();
    renderWithProviders(
      <NoticeEditModal
        open
        onClose={() => undefined}
        onSubmit={vi.fn()}
        initial={editNotice()}
        onUploadAttachment={onUploadAttachment}
      />,
    );

    const fileInput = screen.getByLabelText('첨부파일 선택') as HTMLInputElement;
    expect(fileInput).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '파일 추가' })).toBeInTheDocument();

    const file = new File(['dummy'], 'spec.pdf', { type: 'application/pdf' });
    fireEvent.change(fileInput, { target: { files: [file] } });

    expect(onUploadAttachment).toHaveBeenCalledTimes(1);
    expect(onUploadAttachment.mock.calls[0]![0]).toBe(file);
  });

  it('R3-4_수정_모드_기존_첨부_목록_렌더_및_삭제_버튼_동작', () => {
    const onDeleteAttachment = vi.fn();
    const attach = { attachSn: 11, fileName: '지침.pdf', fileSize: 2048, regDt: '2026-06-01T00:00:00' };
    renderWithProviders(
      <NoticeEditModal
        open
        onClose={() => undefined}
        onSubmit={vi.fn()}
        initial={editNotice([attach])}
        onDeleteAttachment={onDeleteAttachment}
      />,
    );

    expect(screen.getByText('지침.pdf')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '지침.pdf 삭제' }));
    expect(onDeleteAttachment).toHaveBeenCalledWith(attach);
  });
});
