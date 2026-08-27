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

  // R2 — 개인정보 유무(분류) 항목 제거.
  // 관제서버가 개인정보 유무를 실제로 보내지 않으며, 화면이 보던 privacyTypeCd 는
  // 적재 시 고정되는 레거시 컬럼이라 상세 기본정보에서 제거한다.
  it('영상_상세에_개인정보_항목이_렌더되지_않는다', async () => {
    // privacyTypeCd 가 내려와도(BE 응답 계약 무변경) 화면에는 노출하지 않는다.
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        status: 'COMPLETED',
        duration: 30,
        resolution: '1920x1080',
        privacyTypeCd: 'PRVC',
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

    expect(screen.queryByText('개인정보 분류')).not.toBeInTheDocument();
    // 구 개인정보 등급 라벨(개인정보 / 가명처리 / 비식별)도 노출되지 않는다.
    expect(screen.queryByText('개인정보')).not.toBeInTheDocument();
    expect(screen.queryByText('가명처리')).not.toBeInTheDocument();
    expect(screen.queryByText('비식별')).not.toBeInTheDocument();
  });

  /**
   * ★ 기본정보 메타 그리드는 **서버가 준 실값**을 쓴다. [@design SCREEN-009] [@design API-043]
   *
   * 두 칸이 각각 다른 방식으로 거짓을 말하고 있었다:
   *  - 해상도 — FE 타입이 **BE 에 없는 필드를 선언**한 계약 드리프트라 항상 `undefined` 였고
   *    `|| '-'` 폴백이 그 사실을 완벽히 가려 화면은 영구히 `-` 였다.
   *  - CCTV ID — `video-${rawSn}` 로 조립해, 같은 화면 상단 제목의 진짜 식별자와
   *    **서로 다른 두 값**이 동시에 떴다.
   */
  describe('기본정보 메타 — 조립값이 아니라 서버 실값을 표시한다', () => {
    /**
     * 메타 그리드에서 라벨로 그 칸의 값을 읽는다(항목 구성·순서는 이 라운드에서 불변).
     *
     * ⚠ 라벨 문자열로 곧장 찾지 않고 **term(dt) 역할**로 좁힌다 — 확정 시안 정합(SCREEN-009 B9/B10)
     *   이후 헤더 메타 행이 「해상도」를 키와 값 **두 노드**로 나눠 갖게 되어, 같은 낱말이 화면에
     *   둘 이상 존재한다. 이 헬퍼가 보려는 것은 그중 메타 그리드 칸이다.
     */
    const metaValueOf = (label: string) =>
      screen
        .getAllByRole('term')
        .find((el) => el.textContent === label)
        ?.parentElement?.querySelector('dd')?.textContent;

    /**
     * 헤더 메타 행(`.hero-sub-row`)에서 라벨에 딸린 값을 읽는다.
     *
     * ★ 확정 시안은 이 행을 「콜론 없는 키 + 값」 **두 노드 두 색**으로 그린다(`.kv .k` / `.kv .v`).
     *   구 구현은 「해상도: 1920x1440」을 한 노드에 한 색으로 담았고, 그래서 이 검증도 그 합친
     *   문자열을 통째로 단언했다. 검증하려는 사실(**서버 실값이 헤더에도 온다**)은 그대로이므로
     *   읽는 방법만 새 구조에 맞춘다 — 값 자체는 여전히 정확히 대조한다.
     */
    const heroValueOf = (label: string) =>
      within(screen.getByTestId('video-hero-meta')).getByText(label).nextElementSibling
        ?.textContent;

    function reply(data: Record<string, unknown>) {
      mock.onGet('/videos/42').reply(200, {
        success: true,
        data: {
          id: 42,
          cctvName: 'CCTV-001',
          status: 'COMPLETED',
          duration: 30,
          framePreviews: [],
          ...data,
        },
        message: null,
        errorCode: null,
      });
    }

    async function renderDetail() {
      renderWithProviders(
        <Routes>
          <Route path="/video/:id" element={<VideoDetailPage />} />
        </Routes>,
        { initialEntries: ['/video/42'] },
      );
      await waitFor(() => {
        expect(screen.getAllByRole('term').length).toBeGreaterThan(0);
      });
    }

    it('★해상도는_서버가_준_값_그대로_표시된다_영구_대시가_아니다', async () => {
      reply({ vmsCctvId: 'CCTV-001', resolution: '1920x1440' });
      await renderDetail();

      expect(metaValueOf('해상도')).toBe('1920x1440');
      // 헤더 메타 행에도 같은 값이 온다(두 표시 지점).
      expect(heroValueOf('해상도')).toBe('1920x1440');
    });

    it('해상도_메타가_없는_영상은_두_곳_모두_대시다_숫자를_지어내지_않는다', async () => {
      reply({ vmsCctvId: 'CCTV-001', resolution: null });
      await renderDetail();

      expect(metaValueOf('해상도')).toBe('-');
      expect(heroValueOf('해상도')).toBe('-');
    });

    it('★CCTV_ID는_실값이며_상단_제목과_같은_식별자다_조립값을_쓰지_않는다', async () => {
      reply({ vmsCctvId: 'CCTV-001', resolution: '1920x1440' });
      await renderDetail();

      expect(metaValueOf('CCTV ID')).toBe('CCTV-001');
      // 구 조립값(`video-0042`)은 어디에도 남지 않는다.
      expect(screen.queryByText('video-0042')).not.toBeInTheDocument();
    });

    it('★CCTV_식별자가_없으면_조립값으로_되돌아가지_않고_비운다', async () => {
      // 없는 식별자를 지어내면 그것이 실값처럼 보인다 — 폴백을 두지 않는 이유다.
      reply({ cctvName: '이름만 있는 영상', vmsCctvId: null, resolution: '1920x1440' });
      await renderDetail();

      expect(metaValueOf('CCTV ID')).toBe('-');
      expect(screen.queryByText('video-0042')).not.toBeInTheDocument();
    });

    it('메타_그리드의_항목_구성과_순서는_바뀌지_않았다_값만_바뀐다', async () => {
      reply({ vmsCctvId: 'CCTV-001', resolution: '1920x1440' });
      await renderDetail();

      // 이 화면에는 `<dl>` 이 여럿일 수 있다(배치 사유 영역 등) — 메타 그리드를 지목한다.
      const grid = screen
        .getAllByRole('term')
        .find((el) => el.textContent === 'CCTV ID')
        ?.closest('dl');
      const labels = Array.from(grid?.querySelectorAll('dt') ?? []).map((el) => el.textContent);
      expect(labels).toEqual(['CCTV ID', '해상도', '길이', '녹화 시각', '생성일', '수정일']);
    });
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
