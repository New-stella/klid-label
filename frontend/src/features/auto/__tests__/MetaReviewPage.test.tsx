import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { MetaReviewPage } from '@/pages/MetaReviewPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

// BE(SoT) 실제 응답: items K/V 목록. R7-2 — 과거 imagined {vlmText, stateChanges} 형태 폐기.
const metaPayload = {
  items: [{ metaSn: 1, metaKey: '0001', metaVal: '09:00 맑음, 차량 3대 진입' }],
};
// 어댑터가 결합하는 기대 vlmText
const expectedVlmText = '09:00 맑음, 차량 3대 진입';

function setReviewer() {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: 'u-1',
      role: 'REVIEWER',
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('MetaReviewPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('메타_화면_VLM_시계열_패널_렌더링', async () => {
    // given
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: metaPayload,
      message: null,
      errorCode: null,
    });

    // when
    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta?srcSn=100'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    // then
    await waitFor(() => {
      expect(screen.getByLabelText('VLM 시계열 메타')).toBeInTheDocument();
    });

    // 좌/우 패널 분리
    expect(screen.getByTestId('meta-left-panel')).toBeInTheDocument();
    expect(screen.getByTestId('meta-right-panel')).toBeInTheDocument();

    // vlmText가 textarea에 표시 (어댑터가 items→vlmText 결합)
    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue(expectedVlmText);
  });

  it('VLM_메타_0건이면_빈상태_렌더_크래시없음', async () => {
    // given — BE 가 {items:[]} 반환 (R7-2 RED: 과거엔 undefined.length 로 ErrorBoundary 500)
    mock.onGet('/frames/22/meta').reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    // when
    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/6/meta?srcSn=22'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    // then — 크래시 없이 빈 상태 안내가 렌더된다
    await waitFor(() => {
      expect(screen.getByTestId('meta-empty-state')).toBeInTheDocument();
    });
    expect(screen.getByText('VLM 메타 없음')).toBeInTheDocument();
    // 빈 상태에서는 패널/타임라인을 렌더하지 않는다
    expect(screen.queryByTestId('state-change-timeline')).not.toBeInTheDocument();
  });

  it('상태_변화_타임라인_빈데이터_렌더링', async () => {
    // given — BE 메타에는 상태변화 데이터가 없으므로 항상 빈 타임라인
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: metaPayload,
      message: null,
      errorCode: null,
    });

    // when
    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta?srcSn=100'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    // then — 타임라인 컨테이너는 렌더되되 "변화 없음" 안내
    await waitFor(() => {
      expect(screen.getByTestId('state-change-timeline')).toBeInTheDocument();
    });
    expect(screen.getByText('감지된 상태 변화가 없습니다.')).toBeInTheDocument();
  });

  it('메타_저장시_PUT_vlmText_전송', async () => {
    // given
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: metaPayload,
      message: null,
      errorCode: null,
    });
    let putCalled = false;
    mock.onPut('/frames/100/meta').reply((config) => {
      putCalled = true;
      const body = JSON.parse(config.data);
      // 단일 메타 항목 — 원본 metaKey 보존 + 편집 텍스트 반영
      expect(body.items).toHaveLength(1);
      expect(body.items[0].metaKey).toBe('0001');
      expect(body.items[0].metaVal).toContain('추가 내용');
      return [
        200,
        {
          success: true,
          data: {
            items: [
              { metaSn: 1, metaKey: '0001', metaVal: expectedVlmText + ' 추가 내용' },
            ],
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();

    // when
    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta?srcSn=100'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByLabelText('VLM 시계열 메타')).toBeInTheDocument();
    });

    // vlmText 수정
    const textarea = screen.getByRole('textbox');
    await user.type(textarea, ' 추가 내용');

    // 저장 버튼 클릭
    await user.click(screen.getByRole('button', { name: '저장' }));

    // then
    await waitFor(() => {
      expect(putCalled).toBe(true);
    });
  });

  it('srcSn_누락시_프레임_선택_안내', () => {
    // given / when
    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    // then
    expect(screen.getByText('프레임이 선택되지 않았습니다')).toBeInTheDocument();
  });
});
