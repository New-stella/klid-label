// 회귀 가드 — 해상도 파생영상의 **확정 결과**가 화면에 드러나는가.
//
// 결함: 상태 폴링 훅(`useResolutionDerivativeStatus`)과 BE 계약(GET /videos/{rawSn}/resolution,
//       상태 4값)은 있었지만 이 훅을 쓰는 화면이 하나도 없었다(소비자 0). 생성 응답(POST)은
//       `CREATED`(=예약됨)까지만 말하므로, 그 뒤의 **확정 실패(FAILED)** 는 사용자에게 아무
//       화면에도 나타나지 않았다.
//
// 폴링 시작/종료 규칙은 훅이 단독으로 소유한다 — 화면은 결과를 표시만 한다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const VIDEO_ID = 1;

/** 테스트 전용 더미 인증 상태 — 시크릿이 아니라 스토어를 로그인 상태로 만들기 위한 자리값이다. */
const AUTH_STUB = {
  value: 'tok',
  claims: {
    sub: 'u',
    role: 'REVIEWER' as const,
    channel: 'INTERNAL' as const,
    exp: 9999999999,
  },
};

describe('AugmentRequestPage 파생영상 확정 상태 배선', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: AUTH_STUB.value,
      claims: AUTH_STUB.claims,
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
    // 원본 영상(파생 아님) — 파생이면 카드가 비활성이라 해상도 종류를 고를 수 없다.
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
        derivative: false,
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  /** 확정 상태 조회 응답. 전부 종료 상태라 폴링은 첫 조회 후 멈춘다(훅 규칙). */
  function replyDerivatives(
    derivatives: {
      rawSn: number | null;
      goalResCd: string;
      targetW: number;
      targetH: number;
      status: string;
    }[],
  ) {
    mock.onGet(`/videos/${VIDEO_ID}/resolution`).reply(200, {
      success: true,
      data: { derivatives },
      message: null,
      errorCode: null,
    });
  }

  /** 영상 선택 → 해상도 변경 종류 선택(이 경로에서만 확정 현황을 조회한다). */
  async function selectVideoAndResolution() {
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);
    await user.click(await screen.findByLabelText('CCTV-1 선택'));
    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
  }

  it('★확정_실패가_화면에_드러난다_종전에는_어디에도_보이지_않았다', async () => {
    // given: 1건은 확정 완료, 1건은 확정 실패
    replyDerivatives([
      { rawSn: 11, goalResCd: 'RESL_1080P', targetW: 1920, targetH: 1080, status: 'COMPLETED' },
      { rawSn: null, goalResCd: 'RESL_720P', targetW: 1280, targetH: 720, status: 'FAILED' },
    ]);

    // when
    await selectVideoAndResolution();

    // then: 실패가 별도 경고로 노출된다
    const notice = await screen.findByTestId('derivative-failed-notice');
    expect(notice).toHaveTextContent('1건이 생성에 실패했습니다');
    expect(screen.getByTestId('derivative-status-RESL_720P')).toHaveTextContent('생성 실패');
    expect(screen.getByTestId('derivative-status-RESL_1080P')).toHaveTextContent('생성 완료');
  });

  it('진행_중이면_예약과_확정을_구분해_보여준다', async () => {
    // given: CREATED(예약만 됨) + IN_PROGRESS(확정 진행 중) — 둘 다 아직 완료가 아니다
    replyDerivatives([
      { rawSn: 12, goalResCd: 'RESL_1080P', targetW: 1920, targetH: 1080, status: 'CREATED' },
      { rawSn: 13, goalResCd: 'RESL_480P', targetW: 854, targetH: 480, status: 'IN_PROGRESS' },
    ]);

    // when
    await selectVideoAndResolution();

    // then
    const block = await screen.findByTestId('resolution-derivative-status');
    expect(block).toHaveTextContent('2건 진행 중');
    expect(screen.getByTestId('derivative-status-RESL_1080P')).toHaveTextContent('생성 대기');
    expect(screen.getByTestId('derivative-status-RESL_480P')).toHaveTextContent('생성 중');
    expect(screen.queryByTestId('derivative-failed-notice')).not.toBeInTheDocument();
  });

  it('전부_확정_완료면_실패_경고를_띄우지_않는다', async () => {
    // given
    replyDerivatives([
      { rawSn: 14, goalResCd: 'RESL_720P', targetW: 1280, targetH: 720, status: 'COMPLETED' },
    ]);

    // when
    await selectVideoAndResolution();

    // then: 정상 완료까지 경고로 물들이지 않는다(과경고 방지)
    const block = await screen.findByTestId('resolution-derivative-status');
    expect(block).toHaveTextContent('모두 완료');
    expect(screen.queryByTestId('derivative-failed-notice')).not.toBeInTheDocument();
  });

  it('파생이_0건이면_현황_블록_자체를_띄우지_않는다', async () => {
    // given: 아직 아무 해상도도 요청하지 않은 영상
    replyDerivatives([]);

    // when
    await selectVideoAndResolution();

    // then: 기다릴 대상이 없으므로 빈 카드를 만들지 않는다
    await waitFor(() => {
      expect(screen.getByTestId('target-resolution-block')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('resolution-derivative-status')).not.toBeInTheDocument();
  });
});
