// UI-071 회귀 가드 — 파생영상 선택 시 처리 종류 카드가 비활성화되고 사유가 안내되는가.
//
// 결함: 페이지가 `ProcessKindCard` 에 **`disabled` prop 자체를 전달하지 않았고** 파생 여부를
//       사전 조회하지도 않았다. 확정 정책이 "안전망"으로만 인정한 경로(제출 후 400)가 유일한
//       경로가 되어, 사용자는 생성 조건까지 다 채운 뒤에야 막혔다.
//       파생 깊이는 1 로 고정이라 이 조건은 **재시도 여지가 없는 영구 조건**이다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const VIDEO_ID = 1;

describe('AugmentRequestPage 파생영상 차단 배선 (UI-071)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: VIDEO_ID,
            cctvName: 'CCTV-1',
            vmsClipId: 'V1',
            eventName: '쓰러짐',
            eventTypeCd: 'FALL',
            frameCount: 100,
            status: 'COMPLETED',
            capturedAt: '2026-05-01T12:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function replyVideoDetail(derivative: boolean) {
    mock.onGet(`/videos/${VIDEO_ID}`).reply(200, {
      success: true,
      data: {
        id: VIDEO_ID,
        cctvName: 'CCTV-1',
        vmsClipId: 'V1',
        frameCount: 100,
        status: 'COMPLETED',
        capturedAt: '2026-05-01T12:00:00Z',
        duration: 10,
        fileSizeMb: 1,
        resolution: '1920x1080',
        framePreviews: [],
        derivative,
      },
      message: null,
      errorCode: null,
    });
  }

  it('파생영상을_고르면_처리종류_카드가_전부_비활성화되고_사유가_툴팁으로_뜬다', async () => {
    // given: 선택 대상이 파생영상
    replyVideoDetail(true);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // when: 영상 선택
    await user.click(await screen.findByLabelText('CCTV-1 선택'));

    // then: 4종 카드 전부 비활성 + 사유 툴팁(title) + 화면 안내
    await waitFor(() => {
      expect(screen.getByTestId('process-kind-WINTER')).toBeDisabled();
    });
    for (const kind of ['WINTER', 'NIGHT', 'RAIN', 'RESOLUTION']) {
      const card = screen.getByTestId(`process-kind-${kind}`);
      expect(card).toBeDisabled();
      expect(card).toHaveAttribute('title', expect.stringContaining('파생영상'));
    }
    expect(screen.getByTestId('derivative-block-notice')).toBeInTheDocument();
  });

  it('파생영상이면_제출_버튼도_막힌다', async () => {
    // given
    replyVideoDetail(true);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // when
    await user.click(await screen.findByLabelText('CCTV-1 선택'));

    // then: 제출 후 400 을 받아야만 아는 동선을 없앤다
    await waitFor(() => {
      expect(screen.getByTestId('augment-submit')).toBeDisabled();
    });
  });

  it('원본영상이면_카드가_활성이다', async () => {
    // given: 파생이 아닌 원본
    replyVideoDetail(false);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // when
    await user.click(await screen.findByLabelText('CCTV-1 선택'));

    // then: 정상 영상까지 막지 않는다(과차단 방지)
    await waitFor(() => {
      expect(screen.getByTestId('process-kind-WINTER')).not.toBeDisabled();
    });
    expect(screen.queryByTestId('derivative-block-notice')).not.toBeInTheDocument();
  });
});
