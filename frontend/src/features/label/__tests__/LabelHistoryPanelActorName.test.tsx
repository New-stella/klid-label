// LabelHistoryPanel — 저장 이벤트 카드의 작성자 표기 검증.
//
// BE 계약: actor=사번(REG_ID, 하위호환) / actorName=표시명(USER_NM, 해석 실패 시 null).
// 화면은 표시명을 보여주고 없을 때만 사번으로 폴백한다(빈칸 금지) — 사번이 그대로 찍히던 결함의 가드.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { LabelHistoryPanel } from '@/features/label/components/LabelHistoryPanel';
import { renderWithProviders } from '@/test/renderWithProviders';

function historyPayload(actor: string | null, actorName: string | null) {
  return {
    success: true,
    data: {
      content: [
        {
          lblHstrySn: 1,
          srcSn: 300,
          regDt: '2026-07-21T00:00:00Z',
          actor,
          actorName,
          addCnt: 0,
          mdfcnCnt: 1,
          delCnt: 0,
          changes: [],
        },
      ],
      number: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelHistoryPanel 작성자 표기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('작성자는_사번이_아니라_이름으로_표시된다', async () => {
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, historyPayload('2001', '홍길동'));
    renderWithProviders(<LabelHistoryPanel srcSn={300} />);

    expect(await screen.findByText(/홍길동/)).toBeInTheDocument();
    expect(screen.queryByText(/2001/)).toBeNull();
  });

  it('이름_해석에_실패하면_사번으로_폴백해_빈칸이_되지_않는다', async () => {
    // 퇴사·비숫자 사번 등으로 BE 가 이름을 못 찾으면 actorName=null 로 내려온다.
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, historyPayload('2001', null));
    renderWithProviders(<LabelHistoryPanel srcSn={300} />);

    expect(await screen.findByText(/2001/)).toBeInTheDocument();
  });

  it('사번도_이름도_없는_시스템_이력행은_시스템으로_표시된다', async () => {
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, historyPayload(null, null));
    renderWithProviders(<LabelHistoryPanel srcSn={300} />);

    expect(await screen.findByText(/시스템/)).toBeInTheDocument();
  });
});
