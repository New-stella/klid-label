import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { DashboardPage } from '@/pages/DashboardPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

// 픽스처 원칙: 검수완료(approved*) < 전체(cumulative*) 이고 4개 분포의 count 가 모두 서로 달라야
// "어느 필드를 읽는지"가 단언으로 구분된다. 같은 값이면 테스트가 무효다.
const samplePayload = {
  pendingCount: 12,
  // BE 계약상 approvedVideoCount 와 동일 원천(APPROVED 카운트)이라 값을 일치시킨다.
  completedCount: 300,
  myTaskCount: 38,
  rejectedCount: 3,
  cumulativeImageCount: 50000,
  cumulativeVideoCount: 1500,
  // BE 카테고리 분포 응답 (eventTypeCd=categoryKey, label=카테고리 한글명).
  eventDistribution: [
    { eventTypeCd: '010001', label: '침수(범람)', count: 80 },
    { eventTypeCd: '020001', label: '화재', count: 50 },
    { eventTypeCd: '020002', label: '쓰러짐', count: 40 },
    { eventTypeCd: '040001', label: '교통사고', count: 30 },
    { eventTypeCd: '050001', label: '싸움', count: 20 },
    { eventTypeCd: '070001', label: '납치(유괴)', count: 10 },
  ],
  imageDistribution: [
    { eventTypeCd: '010001', label: '침수(범람)', count: 800 },
    { eventTypeCd: '020001', label: '화재', count: 500 },
    { eventTypeCd: '020002', label: '쓰러짐', count: 400 },
    { eventTypeCd: '040001', label: '교통사고', count: 300 },
    { eventTypeCd: '050001', label: '싸움', count: 200 },
    { eventTypeCd: '070001', label: '납치(유괴)', count: 100 },
  ],
  // 검수완료(APPROVED) 기준 — 주 수치/분포의 원천
  approvedImageCount: 12000, // 12000/50000 = 24%
  approvedVideoCount: 300, //     300/1500  = 20%
  approvedEventDistribution: [
    { eventTypeCd: '010001', label: '침수(범람)', count: 41 },
    { eventTypeCd: '020001', label: '화재', count: 26 },
    { eventTypeCd: '020002', label: '쓰러짐', count: 21 },
    { eventTypeCd: '040001', label: '교통사고', count: 16 },
    { eventTypeCd: '050001', label: '싸움', count: 11 },
    { eventTypeCd: '070001', label: '납치(유괴)', count: 6 },
  ],
  approvedImageDistribution: [
    { eventTypeCd: '010001', label: '침수(범람)', count: 411 },
    { eventTypeCd: '020001', label: '화재', count: 261 },
    { eventTypeCd: '020002', label: '쓰러짐', count: 211 },
    { eventTypeCd: '040001', label: '교통사고', count: 161 },
    { eventTypeCd: '050001', label: '싸움', count: 111 },
    { eventTypeCd: '070001', label: '납치(유괴)', count: 61 },
  ],
  myTask: {
    pendingCount: 1,
    inProgressCount: 2,
    reviewPendingCount: 0,
    rejectedCount: 1,
  },
  notices: [
    { id: 1, title: '시스템 점검 안내', pinned: true, createdAt: '2026-05-01T00:00:00Z' },
  ],
};

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'dummy-token',
    claims: {
      sub: 'u-1',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('DashboardPage', () => {
  let mock: MockAdapter;

  /** /stats/summary 응답만 교체 (최근 완료 영상 핸들러는 함께 재등록해 미매칭 요청을 막는다). */
  function mockSummary(payload: unknown) {
    mock.reset();
    mock.onGet('/stats/summary').reply(200, {
      success: true,
      data: payload,
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 },
      message: null,
      errorCode: null,
    });
  }

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/stats/summary').reply(200, {
      success: true,
      data: samplePayload,
      message: null,
      errorCode: null,
    });
    // mock 정합 — 최근 완료 영상 빈 응답
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('대시보드_KPI_3개_처리대기_처리완료_반려건수_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('처리 대기')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('dashboard-kpi-grid');
    expect(within(grid).getByText('처리 대기')).toBeInTheDocument();
    expect(within(grid).getByText('처리 완료')).toBeInTheDocument();
    expect(within(grid).getByText('반려 건수')).toBeInTheDocument();
  });

  it('이미지_영상_데이터_카드_이벤트_6종_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('이미지 데이터 개수')).toBeInTheDocument();
    });

    expect(screen.getByText('영상 데이터 개수')).toBeInTheDocument();

    // 데이터 로드 후 BE 카테고리 라벨 노출 (이미지+영상 카드 양쪽 모두 있어 getAllByText 사용)
    const labels = ['침수(범람)', '화재', '쓰러짐', '교통사고', '싸움', '납치(유괴)'];
    for (const l of labels) {
      await waitFor(() => {
        expect(screen.getAllByText(l).length).toBeGreaterThan(0);
      });
    }
  });

  it('대시보드_이미지카드가_검수완료_수치를_주_수치로_표시한다', async () => {
    // given
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    const card = await screen.findByTestId('dashboard-image-card');

    // then: 주 수치는 검수완료(approvedImageCount) — 전체(cumulativeImageCount)가 아니다
    await within(card).findByText('12,000');
    expect(within(card).queryByText('50,000')).not.toBeInTheDocument();
  });

  it('대시보드_이미지카드가_전체_수치와_완료율을_보조로_표시한다', async () => {
    // given
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    const card = await screen.findByTestId('dashboard-image-card');

    // then: 12,000 / 50,000 = 24%
    await within(card).findByText('검수완료 기준 · 전체 50,000장 (완료율 24%)');
  });

  it('대시보드_영상카드가_동일_패턴으로_표시된다', async () => {
    // given
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    const card = await screen.findByTestId('dashboard-video-card');

    // then: 주 수치 = 검수완료 300건, 보조 = 전체 1,500건 (300/1500 = 20%)
    await within(card).findByText('300');
    expect(within(card).queryByText('1,500')).not.toBeInTheDocument();
    within(card).getByText('검수완료 기준 · 전체 1,500건 (완료율 20%)');
  });

  it('대시보드_이벤트분포가_검수완료_기준_값을_렌더한다', async () => {
    // given
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    const imageCard = await screen.findByTestId('dashboard-image-card');
    const videoCard = screen.getByTestId('dashboard-video-card');

    // then: 이미지 카드는 approvedImageDistribution(411…), 영상 카드는 approvedEventDistribution(41…)
    await within(imageCard).findByText('411');
    expect(within(imageCard).queryByText('800')).not.toBeInTheDocument();

    expect(within(videoCard).getByText('41')).toBeInTheDocument();
    expect(within(videoCard).queryByText('80')).not.toBeInTheDocument();
  });

  it('전체가_0건이면_완료율이_0퍼센트로_표시된다', async () => {
    // given: 전체·검수완료 모두 0 (분모 0 — NaN/Infinity 방어)
    mockSummary({
      ...samplePayload,
      cumulativeImageCount: 0,
      cumulativeVideoCount: 0,
      approvedImageCount: 0,
      approvedVideoCount: 0,
    });
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    const card = await screen.findByTestId('dashboard-image-card');

    // then
    await within(card).findByText('검수완료 기준 · 전체 0장 (완료율 0%)');
    expect(within(card).queryByText(/NaN|Infinity/)).not.toBeInTheDocument();
  });

  it('검수완료_수치_미수신시_0으로_오인표시하지_않는다', async () => {
    // given: 구버전 BE — approved* 필드 미수신
    const legacy: Record<string, unknown> = { ...samplePayload };
    delete legacy.approvedImageCount;
    delete legacy.approvedVideoCount;
    delete legacy.approvedEventDistribution;
    delete legacy.approvedImageDistribution;
    mockSummary(legacy);
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    const card = await screen.findByTestId('dashboard-image-card');

    // then: 0 을 실데이터처럼 표시하지 않고 미집계임을 알린다 (크래시도 없어야 함)
    await within(card).findByText('전체 50,000장 · 검수완료 집계를 불러오지 못했습니다');
    expect(within(card).queryByText('0')).not.toBeInTheDocument();
    expect(within(card).queryByText(/완료율/)).not.toBeInTheDocument();
  });

  it('검수완료가_전체보다_커도_렌더가_깨지지_않는다', async () => {
    // given: 논리적으로 불가하나 방어적으로 — 클램프하지 않고 그대로 노출(데이터 이상 은폐 금지)
    mockSummary({ ...samplePayload, approvedImageCount: 60000 });
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    const card = await screen.findByTestId('dashboard-image-card');

    // then
    await within(card).findByText('검수완료 기준 · 전체 50,000장 (완료율 120%)');
    expect(within(card).getByText('60,000')).toBeInTheDocument();
  });

  it('분포_라벨에_스크립트_태그가_와도_텍스트로_이스케이프된다', async () => {
    // given: BE 가 내려준 라벨에 스크립트가 섞인 경우 (XSS, CWE-79)
    const xss = '<script>alert(1)</script>';
    mockSummary({
      ...samplePayload,
      approvedImageDistribution: [{ eventTypeCd: '010001', label: xss, count: 411 }],
    });
    setRole('REVIEWER');

    // when
    const { container } = renderWithProviders(<DashboardPage />);
    const card = await screen.findByTestId('dashboard-image-card');

    // then: 텍스트로 이스케이프되어 렌더되고 실제 script 요소는 생성되지 않는다
    await within(card).findByText(xss);
    expect(container.querySelectorAll('script')).toHaveLength(0);
  });

  it('최근_완료_영상_섹션_제목_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('최근 완료 영상')).toBeInTheDocument();
    });
  });

  it('대시보드_제목_렌더', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '대시보드' })).toBeInTheDocument();
    });
  });

  it('내_작업_현황_task_PENDING_배지는_배정_완료로_표시', async () => {
    // given: WORKER 의 최근 작업에 PENDING 상태 1건 (task 문맥)
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 1,
            videoId: 10,
            cctvName: 'CCTV-강남-001',
            workerId: 1,
            workerName: '작업자',
            status: 'PENDING',
            assignedAt: '2026-05-01T00:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 5,
      },
      message: null,
      errorCode: null,
    });
    setRole('WORKER');

    // when
    renderWithProviders(<DashboardPage />);

    // then: 공유 StatusBadge 기본값 '대기'가 아니라 task 문맥 '배정 완료'
    await waitFor(() => {
      expect(screen.getByText('배정 완료')).toBeInTheDocument();
    });
    expect(screen.queryByText('대기')).not.toBeInTheDocument();
  });

  it('최근_완료_영상은_검수완료_영상만_조회한다', async () => {
    // given: "최근 완료 영상" 카드는 제목대로 검수 승인(APPROVED)된 영상만 보여야 한다.
    //        (필터가 없으면 미검수 영상까지 섞여 바로 위 '영상 데이터 개수' 카드와 모순된다)
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    await screen.findByText('최근 완료 영상');

    // then: 컴포넌트 상수가 아니라 '실제로 나간 요청'의 쿼리 파라미터를 검증한다
    await waitFor(() => {
      expect(mock.history.get.filter((r) => r.url === '/videos').length).toBe(1);
    });
    const videoRequest = mock.history.get.filter((r) => r.url === '/videos')[0];
    expect(videoRequest.params).toMatchObject({ reviewStatusCd: 'APPROVED' });
  });

  it('최근_완료_영상은_검수완료시각_내림차순으로_조회한다', async () => {
    // given: "최근 완료 영상" = 최근 '완료'된 순서 — 적재 시각(capturedAt=regDt)이 아니라
    //        검수 완료 시각(reviewCompletedAt = LS_RAW_DATA_STATUS.UPD_DT) 기준이어야 한다.
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    await screen.findByText('최근 완료 영상');

    // then: 컴포넌트 상수가 아니라 '실제로 나간 요청'의 정렬 파라미터를 검증한다
    await waitFor(() => {
      expect(mock.history.get.filter((r) => r.url === '/videos').length).toBe(1);
    });
    const videoRequest = mock.history.get.filter((r) => r.url === '/videos')[0];
    expect(videoRequest.params).toMatchObject({ sort: 'reviewCompletedAt,desc' });
  });

  it('완료일_컬럼은_검수완료시각을_표시한다', async () => {
    // given: 적재 시각과 검수 완료 시각이 서로 다른 1건 (같은 값이면 어느 필드를 읽는지 구분 불가)
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 77,
            cctvName: 'CCTV-강남-001',
            vmsClipId: 'CLIP-77',
            eventTypeCd: '020001',
            frameCount: 10,
            status: 'COMPLETED',
            capturedAt: '2026-05-01T13:07:00',
            reviewCompletedAt: '2026-05-01T13:10:00',
            durationSec: 30,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 5,
      },
      message: null,
      errorCode: null,
    });
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    await screen.findByText('최근 완료 영상');

    // then: 검수 완료 시각(13:10)이 표시되고 적재 시각(13:07)은 표시되지 않는다
    expect(await screen.findByText('05-01 13:10')).toBeInTheDocument();
    expect(screen.queryByText('05-01 13:07')).not.toBeInTheDocument();
  });

  it('검수완료시각이_없으면_완료일은_하이픈으로_표시된다', async () => {
    // given: reviewCompletedAt 미수신(구버전 BE/검수 미완료) — capturedAt 으로 폴백하지 않는다
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 78,
            cctvName: 'CCTV-강남-002',
            vmsClipId: 'CLIP-78',
            eventTypeCd: '020001',
            frameCount: 10,
            status: 'COMPLETED',
            capturedAt: '2026-05-01T13:07:00',
            durationSec: 30,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 5,
      },
      message: null,
      errorCode: null,
    });
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);
    await screen.findByText('CCTV-강남-002');

    // then
    expect(screen.getByText('-')).toBeInTheDocument();
    expect(screen.queryByText('05-01 13:07')).not.toBeInTheDocument();
  });

  it('I3_최근_완료_영상_API_오류시_빈상태가_아니라_오류_메시지와_재시도_노출', async () => {
    // given: 최근 완료 영상 API 가 500 으로 실패 (이전엔 빈 상태로 조용히 표시됨)
    mock.onGet('/videos').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL_ERROR',
    });
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);

    // then: "데이터 없음" 이 아니라 로드 실패 메시지 + 재시도 버튼 노출
    await waitFor(() => {
      expect(screen.getByText('목록을 불러오지 못했습니다')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: '재시도' })).toBeInTheDocument();
    expect(screen.queryByText('완료된 영상이 없습니다.')).not.toBeInTheDocument();
  });
});
