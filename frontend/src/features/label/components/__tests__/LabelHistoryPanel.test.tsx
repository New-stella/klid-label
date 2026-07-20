// LabelHistoryPanel — 라벨 변경 이력 리스트 UI (Phase 5 / A-3).
//
// 소비 계약(Phase 2 BE): GET /v1/frames/{srcSn}/label-history?page=&size=
//   → ApiResponse<Page<LabelHistoryResponse>> {content, totalElements, totalPages, number, size}
//   LabelHistoryResponse{ lblHstrySn, lblSn, changeKind('ADDED'|'UPDATED'|'DELETED'), actor, regDt, label? }

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { LabelHistoryPanel } from '@/features/label/components/LabelHistoryPanel';
import { renderWithProviders } from '@/test/renderWithProviders';

function pagePayload(
  content: Array<Record<string, unknown>>,
  opts: { number?: number; totalPages?: number; totalElements?: number; size?: number } = {},
) {
  return {
    success: true,
    data: {
      content,
      number: opts.number ?? 0,
      size: opts.size ?? 20,
      totalElements: opts.totalElements ?? content.length,
      totalPages: opts.totalPages ?? 1,
    },
    message: null,
    errorCode: null,
  };
}

const rows = [
  {
    lblHstrySn: 3,
    lblSn: 30,
    changeKind: 'ADDED',
    actor: 'worker-1',
    regDt: '2026-07-20T10:00:00',
    label: 'person',
  },
  {
    lblHstrySn: 2,
    lblSn: 20,
    changeKind: 'UPDATED',
    actor: 'worker-1',
    regDt: '2026-07-20T09:30:00',
    label: 'car',
  },
  {
    lblHstrySn: 1,
    lblSn: null,
    changeKind: 'DELETED',
    actor: 'reviewer-9',
    regDt: '2026-07-20T09:00:00',
    label: null,
  },
];

describe('LabelHistoryPanel — 라벨 변경 이력', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('히스토리_리스트가_변경종류_시각_작업자를_표시한다', async () => {
    mock.onGet('/frames/42/label-history').reply(200, pagePayload(rows));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    // 변경종류 한글 라벨
    await waitFor(() => expect(screen.getByText('추가')).toBeInTheDocument());
    expect(screen.getByText('수정')).toBeInTheDocument();
    expect(screen.getByText('삭제')).toBeInTheDocument();

    // 작업자 + 라벨명
    expect(screen.getAllByText(/worker-1/).length).toBeGreaterThan(0);
    expect(screen.getByText('person')).toBeInTheDocument();
    expect(screen.getByText('car')).toBeInTheDocument();

    // 목록 시맨틱 (접근성)
    const list = screen.getByRole('list', { name: '라벨 변경 이력 목록' });
    expect(list).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });

  it('히스토리_페이징이_동작한다', async () => {
    // page 0 → 다음 페이지 요청 시 page 1 데이터 노출
    mock
      .onGet('/frames/42/label-history', { params: { page: 0, size: 20 } })
      .reply(
        200,
        pagePayload([rows[0]], { number: 0, totalPages: 2, totalElements: 2 }),
      );
    mock
      .onGet('/frames/42/label-history', { params: { page: 1, size: 20 } })
      .reply(
        200,
        pagePayload([rows[1]], { number: 1, totalPages: 2, totalElements: 2 }),
      );

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByText('person')).toBeInTheDocument());

    const next = screen.getByRole('button', { name: '다음 페이지' });
    expect(next).toBeEnabled();
    fireEvent.click(next);

    await waitFor(() => expect(screen.getByText('car')).toBeInTheDocument());
  });

  it('히스토리_페이징_경계_첫페이지는_이전_disabled_마지막은_다음_disabled', async () => {
    // 3페이지 구성 — 첫 페이지(number=0): 이전 disabled / 다음 enabled.
    mock
      .onGet('/frames/42/label-history', { params: { page: 0, size: 20 } })
      .reply(200, pagePayload([rows[0]], { number: 0, totalPages: 3, totalElements: 3 }));
    mock
      .onGet('/frames/42/label-history', { params: { page: 1, size: 20 } })
      .reply(200, pagePayload([rows[1]], { number: 1, totalPages: 3, totalElements: 3 }));
    mock
      .onGet('/frames/42/label-history', { params: { page: 2, size: 20 } })
      .reply(200, pagePayload([rows[2]], { number: 2, totalPages: 3, totalElements: 3 }));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByText('person')).toBeInTheDocument());
    // 첫 페이지 경계: 이전 disabled, 다음 enabled
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeEnabled();

    // 마지막 페이지까지 이동 (0 → 1 → 2)
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    await waitFor(() => expect(screen.getByText('car')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    await waitFor(() => expect(screen.getByText('3 / 3')).toBeInTheDocument());

    // 마지막 페이지 경계: 다음 disabled, 이전 enabled
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeEnabled();
  });

  it('히스토리_로딩상태가_처리된다', async () => {
    let resolveReply!: () => void;
    const deferred = new Promise<[number, unknown]>((res) => {
      resolveReply = () => res([200, pagePayload(rows)]);
    });
    mock.onGet('/frames/42/label-history').reply(() => deferred);

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    // 로딩 스켈레톤(status 컨테이너) 노출
    expect(
      screen.getByRole('status', { name: '변경 이력 로딩 중' }),
    ).toBeInTheDocument();
    resolveReply();
    await waitFor(() => expect(screen.getByText('person')).toBeInTheDocument());
  });

  it('히스토리_빈상태가_처리된다', async () => {
    mock
      .onGet('/frames/42/label-history')
      .reply(200, pagePayload([], { totalPages: 0, totalElements: 0 }));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() =>
      expect(screen.getByText('변경 이력이 없습니다')).toBeInTheDocument(),
    );
  });

  it('히스토리_에러가_처리된다', async () => {
    mock.onGet('/frames/42/label-history').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL_ERROR',
    });

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() =>
      expect(screen.getByRole('alert')).toBeInTheDocument(),
    );
  });

  it('히스토리_패널에_모델명이_노출되지_않는다', async () => {
    mock.onGet('/frames/42/label-history').reply(200, pagePayload(rows));

    const { container } = renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByText('추가')).toBeInTheDocument());

    // FE 노출 문구에 AI 모델명 금지 (fe-ui-no-tech-model-names 구속)
    expect(container.textContent).not.toMatch(/YOLO/i);
    expect(container.textContent).not.toMatch(/SAM2/i);
  });
});
