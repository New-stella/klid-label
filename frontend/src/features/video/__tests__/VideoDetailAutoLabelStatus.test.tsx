import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';

/**
 * 영상 상세 「오토라벨 결과」 탭 — 처리 정보의 '처리 상태'.
 *
 * ★ 이 값은 오래도록 `'COMPLETED'` **리터럴**이었다. 배치가 처리중이든 실패든 화면은 늘
 *   '완료'라고 말했다. 조달처가 이 탭의 오토라벨 응답(FrameLabels)에 없어서 하드코딩된
 *   것이며, 실제 상태는 부모가 가진 영상 상세(VideoDetail.status)에 있다.
 *
 * 아래 케이스들은 그 하드코딩이 되살아나면(어느 상태를 넣어도 '완료'로 보이면) 실패한다.
 */
describe('영상 상세 — 오토라벨 결과 탭의 처리 상태', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  const baseDetail = {
    id: 42,
    cctvName: '강남대로 CCTV',
    vmsClipId: 'VMS-42',
    eventName: '낙상',
    eventTypeCd: 'FALL',
    localGov: '강남구',
    frameCount: 900,
    capturedAt: '2026-05-01T12:00:00Z',
    duration: 30,
    fileSizeMb: 10,
    resolution: '1920x1080',
    framePreviews: [],
  };

  /** 영상 상세를 주어진 status 로, 오토라벨 결과를 라벨 1건으로 응답한다. */
  function replyWith(detail: Record<string, unknown>) {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: { ...baseDetail, ...detail },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos/42/labels/auto').reply(200, {
      success: true,
      data: {
        videoId: 42,
        objects: [
          {
            id: 'L1',
            labelCode: 'PERSON',
            labelName: '사람',
            color: '#4ECDC4',
            confidence: 0.95,
            createdBy: 'auto',
          },
        ],
      },
      message: null,
      errorCode: null,
    });
  }

  /** '처리 상태' 카드의 값 텍스트를 읽는다(라벨 <p> 의 형제 <p>). */
  async function readProcessingStatus(): Promise<string> {
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/42'] },
    );

    await waitFor(() => expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: /오토라벨 결과/ }));

    const labelNode = await screen.findByText('처리 상태');
    const card = labelNode.closest('div');
    expect(card).not.toBeNull();
    // 카드 안 값은 라벨 <p> 다음 <p> 하나뿐이다.
    const value = within(card as HTMLElement)
      .getAllByText(/.+/)
      .map((el) => el.textContent)
      .filter((t) => t !== '처리 상태');
    return value[0] ?? '';
  }

  it('★배치_처리중인_영상은_처리중_상태를_보여준다_COMPLETED_하드코딩_금지', async () => {
    replyWith({ status: 'BATCH_PROCESSING' });

    expect(await readProcessingStatus()).toBe('BATCH_PROCESSING');
    // 하드코딩이 되살아나면 이 단언이 잡는다.
    expect(screen.queryByText('COMPLETED')).not.toBeInTheDocument();
  });

  it('★배치_실패한_영상은_실패_상태를_보여준다', async () => {
    replyWith({ status: 'BATCH_FAILED' });

    expect(await readProcessingStatus()).toBe('BATCH_FAILED');
    expect(screen.queryByText('COMPLETED')).not.toBeInTheDocument();
  });

  it('완료된_영상은_완료_상태를_보여준다', async () => {
    replyWith({ status: 'COMPLETED' });

    expect(await readProcessingStatus()).toBe('COMPLETED');
  });

  it('상태를_못_받은_구_응답도_완료로_지어내지_않는다_생산자_폴백을_그대로_보여준다', async () => {
    // 빈값 처리의 소유자는 생산자(api.ts normalizeVideo — dataSttsCd → 최종 'PENDING')다.
    // 화면은 그 전제를 재유도하지 않으며, 어떤 경우에도 '완료'를 지어내지 않는다.
    replyWith({ status: undefined, dataSttsCd: 'MARKING_READY' });

    expect(await readProcessingStatus()).toBe('MARKING_READY');
    expect(screen.queryByText('COMPLETED')).not.toBeInTheDocument();
  });

  it('상태_필드가_아무것도_없으면_생산자_최종폴백_PENDING_이_그대로_보인다', async () => {
    replyWith({ status: undefined });

    expect(await readProcessingStatus()).toBe('PENDING');
    expect(screen.queryByText('COMPLETED')).not.toBeInTheDocument();
  });
});
