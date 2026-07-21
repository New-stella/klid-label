// LabelHistoryPanel — 저장 이벤트 카드의 "되돌리기" 버튼 검증.
//
// onRevert prop 이 있으면 각 저장 이벤트 카드에 되돌리기 버튼을 노출하고,
// 클릭 시 카드 펼침(toggle)과 무관하게 onRevert(item) 을 호출한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { LabelHistoryPanel } from '@/features/label/components/LabelHistoryPanel';
import { renderWithProviders } from '@/test/renderWithProviders';

function historyPayload() {
  return {
    success: true,
    data: {
      content: [
        {
          lblHstrySn: 1,
          srcSn: 300,
          regDt: '2026-07-21T00:00:00Z',
          actor: 'worker-1',
          addCnt: 0,
          mdfcnCnt: 1,
          delCnt: 0,
          changes: [
            {
              lblSn: 100,
              changeKind: 'UPDATED',
              labelName: 'person',
              before: { lblTypeCd: 'BBOX', labelId: 3, labelNm: 'person', pointCn: '[[0,0],[10,10]]' },
              after: { lblTypeCd: 'BBOX', labelId: 3, labelNm: 'person', pointCn: '[[5,5],[20,20]]' },
            },
          ],
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

describe('LabelHistoryPanel 되돌리기 버튼', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, historyPayload());
  });

  afterEach(() => {
    mock.restore();
  });

  it('이벤트_카드_되돌리기_버튼_클릭시_확장토글없이_onRevert_호출', async () => {
    const user = userEvent.setup();
    const onRevert = vi.fn();
    renderWithProviders(<LabelHistoryPanel srcSn={300} onRevert={onRevert} />);

    const revertBtn = await screen.findByRole('button', { name: '이 저장으로 되돌리기' });
    await user.click(revertBtn);

    expect(onRevert).toHaveBeenCalledTimes(1);
    expect(onRevert).toHaveBeenCalledWith(expect.objectContaining({ lblHstrySn: 1 }));
    // 되돌리기 버튼은 카드 펼침(diff)을 토글하지 않는다 — 변경 상세 미노출.
    expect(screen.queryByLabelText('변경 상세 목록')).toBeNull();
  });

  it('onRevert_미지정시_되돌리기_버튼_미노출', async () => {
    renderWithProviders(<LabelHistoryPanel srcSn={300} />);
    // 카드가 렌더될 때까지 대기(요약 뱃지).
    await waitFor(() => expect(screen.getByText(/수정 ~1/)).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '이 저장으로 되돌리기' })).toBeNull();
  });
});
