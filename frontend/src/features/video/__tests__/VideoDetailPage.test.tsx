import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';

describe('VideoDetailPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('영상_상세_프레임_미리보기_6장_노출', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        vmsClipId: 'VMS-42',
        eventName: '낙상',
        eventTypeCd: 'FALL',
        localGov: '강남구',
        frameCount: 900,
        status: 'COMPLETED',
        capturedAt: '2026-05-01T12:00:00Z',
        duration: 30,
        fileSizeMb: 10,
        resolution: '1920x1080',
        framePreviews: Array.from({ length: 6 }, (_, i) => ({
          frameNo: i * 150 + 1,
          thumbnailUrl: `/t/${i}.jpg`,
        })),
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/42'] },
    );

    // 영상 데이터 로드 대기
    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });

    // 프레임 미리보기 탭 활성화 (Tabs 컴포넌트는 role="tab" 사용)
    await user.click(screen.getByRole('tab', { name: /프레임 미리보기/ }));

    // 6장 프레임 버튼이 렌더된다 (aria-label="프레임 N 상세 보기")
    await waitFor(() => {
      const frameButtons = screen.getAllByRole('button', { name: /^프레임 \d+ 상세 보기$/ });
      expect(frameButtons).toHaveLength(6);
    });
  });

  it('SFR_06_03_해상도_export_섹션은_더이상_영상_상세에_노출되지_않음', async () => {
    // 해상도 변경 UI 는 데이터 증강 화면(/augment)으로 이동되었다.
    // 영상 상세 기본정보 탭에는 더 이상 해상도 섹션이 존재하지 않아야 한다.
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        status: 'COMPLETED',
        duration: 30,
        resolution: '1920x1080',
        framePreviews: [],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/42'] },
    );

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });

    // 해상도 export 섹션 + 프리셋 선택 + 실행 버튼이 노출되지 않는다
    expect(
      screen.queryByRole('heading', { name: /해상도 변경/ }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText('목표 해상도 선택')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '해상도 변환 실행' }),
    ).not.toBeInTheDocument();
  });

  it('버전관리로_이동_버튼은_영상_상세에_노출되지_않음', async () => {
    // 2026-08-03 사용자 확정 — 별도 버전관리 페이지(/history/:videoId) 제거.
    // 변경 이력·버전·diff·롤백은 라벨링 화면의 인라인 히스토리 패널이 제공한다.
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        status: 'COMPLETED',
        duration: 30,
        resolution: '1920x1080',
        framePreviews: [],
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/42'] },
    );

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });

    expect(
      screen.queryByRole('button', { name: /버전관리로 이동/ }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /버전관리/ })).not.toBeInTheDocument();
  });

  it('프레임_라이트박스_푸터에_라벨링_편집_버튼_없고_닫기만_노출', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        status: 'COMPLETED',
        duration: 30,
        resolution: '1920x1080',
        framePreviews: [{ frameNo: 1, srcSn: 101, thumbnailUrl: '/t/0.jpg' }],
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/42'] },
    );

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('tab', { name: /프레임 미리보기/ }));
    await user.click(screen.getByRole('button', { name: '프레임 1 상세 보기' }));

    // 라이트박스가 열리고 닫기 버튼만 남는다 (라벨링 편집 버튼 제거)
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getAllByRole('button', { name: '닫기' }).length,
    ).toBeGreaterThan(0);
    expect(
      within(dialog).queryByRole('button', { name: '라벨링 편집' }),
    ).not.toBeInTheDocument();
  });

  it('잘못된_id는_ErrorState_노출', () => {
    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/invalid'] },
    );

    expect(screen.getByText('잘못된 영상 ID')).toBeInTheDocument();
  });
});
