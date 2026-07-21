// LabelHistoryPanel — 저장 이벤트 단위 라벨 변경 이력 + 펼침 diff UI.
//
// 소비 계약(Phase 2 BE): GET /v1/frames/{srcSn}/label-history?page=&size=
//   → ApiResponse<Page<LabelHistoryResponse>> {content, totalElements, totalPages, number, size}
//   LabelHistoryResponse{ lblHstrySn, srcSn, regDt, actor, addCnt, mdfcnCnt, delCnt, changes[] }
//   LabelChangeView{ lblSn, changeKind('ADDED'|'UPDATED'|'DELETED'), labelName, before, after }

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

/** 추가 1 / 수정 1 / 삭제 1 을 담은 저장 이벤트 1건. */
const eventRow = {
  lblHstrySn: 3,
  srcSn: 42,
  regDt: '2026-07-20T10:00:00',
  actor: 'worker-1',
  addCnt: 1,
  mdfcnCnt: 1,
  delCnt: 1,
  changes: [
    {
      lblSn: 30,
      changeKind: 'ADDED',
      labelName: 'person',
      before: null,
      after: { lblTypeCd: 'BBOX', labelId: 1, labelNm: 'person', pointCn: '[[10,20],[100,80]]' },
    },
    {
      lblSn: 20,
      changeKind: 'UPDATED',
      labelName: 'car',
      before: { lblTypeCd: 'BBOX', labelId: 2, labelNm: 'car', pointCn: '[[0,0],[10,10]]' },
      after: { lblTypeCd: 'BBOX', labelId: 2, labelNm: 'truck', pointCn: '[[0,0],[20,20],[30,30]]' },
    },
    {
      lblSn: null,
      changeKind: 'DELETED',
      labelName: 'bike',
      before: { lblTypeCd: 'POLYGON', labelId: 3, labelNm: 'bike', pointCn: '[[1,1],[2,2],[3,3]]' },
      after: null,
    },
  ],
};

describe('LabelHistoryPanel — 저장 이벤트 변경 이력', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('저장이벤트_카드가_추가수정삭제_요약뱃지를_보여준다', async () => {
    mock.onGet('/frames/42/label-history').reply(200, pagePayload([eventRow]));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByText('추가 +1')).toBeInTheDocument());
    expect(screen.getByText('수정 ~1')).toBeInTheDocument();
    expect(screen.getByText('삭제 -1')).toBeInTheDocument();

    // 이벤트 헤더 작업자 표시
    expect(screen.getByText(/worker-1/)).toBeInTheDocument();

    // 접힌 상태에서는 상세(라벨명)가 아직 노출되지 않음
    expect(screen.queryByText('person')).not.toBeInTheDocument();
  });

  it('0인_종류의_요약뱃지는_생략한다', async () => {
    const addOnly = {
      ...eventRow,
      lblHstrySn: 9,
      addCnt: 2,
      mdfcnCnt: 0,
      delCnt: 0,
    };
    mock.onGet('/frames/42/label-history').reply(200, pagePayload([addOnly]));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByText('추가 +2')).toBeInTheDocument());
    expect(screen.queryByText(/^수정/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^삭제/)).not.toBeInTheDocument();
  });

  it('이벤트를_펼치면_변경상세가_노출된다', async () => {
    mock.onGet('/frames/42/label-history').reply(200, pagePayload([eventRow]));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    const toggle = await screen.findByRole('button', { expanded: false });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    // 펼치기 전에는 상세 목록 없음
    expect(screen.queryByRole('list', { name: '변경 상세 목록' })).not.toBeInTheDocument();

    fireEvent.click(toggle);

    // aria-expanded 전환 + 상세 목록 노출
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'));
    expect(screen.getByRole('list', { name: '변경 상세 목록' })).toBeInTheDocument();
    // 라벨명(person)은 항목 제목 + '라벨:' 필드 행에 함께 노출된다.
    expect(screen.getAllByText('person').length).toBeGreaterThan(0);
  });

  it('수정항목은_이전값과_새값을_함께_표시한다', async () => {
    mock.onGet('/frames/42/label-history').reply(200, pagePayload([eventRow]));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    const toggle = await screen.findByRole('button', { expanded: false });
    fireEvent.click(toggle);

    // 라벨명 변경 car → truck 를 이전값 → 새값 형태로 표시
    await waitFor(() => expect(screen.getByText('car → truck')).toBeInTheDocument());
    // 좌표 변경도 요약(점 개수)으로 이전값 → 새값 표시
    expect(screen.getByText('점 2개 → 점 3개')).toBeInTheDocument();
  });

  it('삭제항목은_삭제_라벨명을_표시한다', async () => {
    mock.onGet('/frames/42/label-history').reply(200, pagePayload([eventRow]));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    const toggle = await screen.findByRole('button', { expanded: false });
    fireEvent.click(toggle);

    // 삭제된 라벨명(bike) 노출 + 삭제 뱃지 (제목 + '라벨:' 필드 행)
    await waitFor(() => expect(screen.getAllByText('bike').length).toBeGreaterThan(0));
    expect(screen.getByText('삭제')).toBeInTheDocument();
  });

  it('변경이력이_없으면_EmptyState를_표시한다', async () => {
    mock
      .onGet('/frames/42/label-history')
      .reply(200, pagePayload([], { totalPages: 0, totalElements: 0 }));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() =>
      expect(screen.getByText('변경 이력이 없습니다')).toBeInTheDocument(),
    );
  });

  it('히스토리_페이징이_동작한다', async () => {
    const page0 = { ...eventRow, lblHstrySn: 100, actor: 'worker-1' };
    const page1 = { ...eventRow, lblHstrySn: 200, actor: 'reviewer-9' };
    mock
      .onGet('/frames/42/label-history', { params: { page: 0, size: 20 } })
      .reply(200, pagePayload([page0], { number: 0, totalPages: 2, totalElements: 2 }));
    mock
      .onGet('/frames/42/label-history', { params: { page: 1, size: 20 } })
      .reply(200, pagePayload([page1], { number: 1, totalPages: 2, totalElements: 2 }));

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByText(/worker-1/)).toBeInTheDocument());

    const next = screen.getByRole('button', { name: '다음 페이지' });
    expect(next).toBeEnabled();
    fireEvent.click(next);

    await waitFor(() => expect(screen.getByText(/reviewer-9/)).toBeInTheDocument());
  });

  it('히스토리_로딩상태가_처리된다', async () => {
    let resolveReply!: () => void;
    const deferred = new Promise<[number, unknown]>((res) => {
      resolveReply = () => res([200, pagePayload([eventRow])]);
    });
    mock.onGet('/frames/42/label-history').reply(() => deferred);

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    expect(
      screen.getByRole('status', { name: '변경 이력 로딩 중' }),
    ).toBeInTheDocument();
    resolveReply();
    await waitFor(() => expect(screen.getByText('추가 +1')).toBeInTheDocument());
  });

  it('히스토리_에러가_처리된다', async () => {
    mock.onGet('/frames/42/label-history').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL_ERROR',
    });

    renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
  });

  it('히스토리_패널에_모델명이_노출되지_않는다', async () => {
    mock.onGet('/frames/42/label-history').reply(200, pagePayload([eventRow]));

    const { container } = renderWithProviders(<LabelHistoryPanel srcSn={42} />);

    await waitFor(() => expect(screen.getByText('추가 +1')).toBeInTheDocument());
    // 펼쳐도 모델명이 노출되지 않아야 함
    fireEvent.click(await screen.findByRole('button', { expanded: false }));
    await waitFor(() => expect(screen.getAllByText('person').length).toBeGreaterThan(0));

    // FE 노출 문구에 AI 모델명 금지 (fe-ui-no-tech-model-names 구속)
    expect(container.textContent).not.toMatch(/YOLO/i);
    expect(container.textContent).not.toMatch(/SAM2/i);
  });
});
