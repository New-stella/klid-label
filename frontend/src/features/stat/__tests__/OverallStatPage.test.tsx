import { screen, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { OverallStatPage } from '@/pages/OverallStatPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function setRole(role: 'WORKER' | 'REVIEWER') {
  useAuthStore.setState({
    token: 'dummy-token',
    claims: {
      sub: '11',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

// 픽스처 원칙: 검수완료(approved*) < 전체(cumulative*) 이고 두 분포의 count 가 서로 달라야
// "어느 필드를 읽는지"가 단언으로 구분된다.
const sample = {
  cumulativeImageCount: 50000,
  cumulativeVideoCount: 1500,
  processing: {
    pending: 100,
    inProgress: 200,
    reviewPending: 400,
    // BE 계약상 approvedVideoCount 와 동일 원천이라 값을 일치시킨다.
    approved: 300,
    rejected: 50,
  },
  // BE 카테고리 분포 9항목 (eventTypeCd=categoryKey, label=카테고리 한글명).
  eventDistribution: [
    { eventTypeCd: '010001', label: '침수(범람)', count: 100 },
    { eventTypeCd: '010002', label: '산사태', count: 90 },
    { eventTypeCd: '020001', label: '화재', count: 80 },
    { eventTypeCd: '020002', label: '쓰러짐', count: 70 },
    { eventTypeCd: '030001', label: '파손', count: 60 },
    { eventTypeCd: '040001', label: '교통사고', count: 50 },
    { eventTypeCd: '050001', label: '싸움', count: 40 },
    { eventTypeCd: '060001', label: '흉기소지', count: 30 },
    { eventTypeCd: '070001', label: '납치(유괴)', count: 20 },
  ],
  // 검수완료(APPROVED) 기준 — 주 수치/분포의 원천
  approvedImageCount: 12000, // 12000/50000 = 24%
  approvedVideoCount: 300, //     300/1500  = 20%
  approvedEventDistribution: [
    { eventTypeCd: '010001', label: '침수(범람)', count: 51 },
    { eventTypeCd: '010002', label: '산사태', count: 46 },
    { eventTypeCd: '020001', label: '화재', count: 41 },
    { eventTypeCd: '020002', label: '쓰러짐', count: 36 },
    { eventTypeCd: '030001', label: '파손', count: 31 },
    { eventTypeCd: '040001', label: '교통사고', count: 26 },
    { eventTypeCd: '050001', label: '싸움', count: 21 },
    { eventTypeCd: '060001', label: '흉기소지', count: 16 },
    { eventTypeCd: '070001', label: '납치(유괴)', count: 11 },
  ],
  // 두 비율(approvalRate·autoLabelRate)은 BE 계약상 백분율(0~100)이다.
  workers: [
    {
      userId: 1,
      name: '홍길동',
      labeled: 100,
      reviewed: 50,
      approvalRate: 95.0,
      inProgress: 12,
      autoLabelRate: 40.0,
    },
  ],
};

describe('OverallStatPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/stats/overall').reply(200, {
      success: true,
      data: sample,
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('통계_누적_카드_ProgressBar_미노출', async () => {
    setRole('REVIEWER');
    const { container } = renderWithProviders(<OverallStatPage />);

    await screen.findByText('누적 이미지');
    const cards = screen.getByTestId('cumulative-cards');
    // ProgressBar / progressbar role 절대 미노출 (UI/UX §4-11 회귀 방지)
    expect(within(cards).queryByRole('progressbar')).not.toBeInTheDocument();
    // ProgressBar 가 붙이는 aria 속성도 없어야 함 (role 없이 우회 렌더 방지)
    expect(cards.querySelectorAll('[aria-valuenow]')).toHaveLength(0);
    // 누적 카드 렌더 자체는 정상
    expect(within(cards).getByText('누적 이미지')).toBeInTheDocument();
    expect(within(cards).getByText('누적 영상')).toBeInTheDocument();
    // KpiCard 컴포넌트는 progress prop 자체가 없으므로 구조적으로 표시 불가
    expect(container.querySelectorAll('progress')).toHaveLength(0);
  });

  it('전체현황_로딩중_KPI_0값_미노출_스켈레톤', async () => {
    // given: 통계 조회 응답을 지연(never-resolve)시켜 isLoading 을 유지.
    setRole('REVIEWER');
    mock.reset();
    mock.onGet('/stats/overall').reply(() => new Promise(() => {}));

    renderWithProviders(<OverallStatPage />);

    // then: 스켈레톤이 노출된다.
    await screen.findByTestId('overall-stat-loading');
    // then: 로딩 중에는 0값을 담은 누적/처리 현황 카드가 노출되지 않는다(오표시 방지).
    expect(screen.queryByTestId('cumulative-cards')).not.toBeInTheDocument();
    expect(screen.queryByTestId('processing-cards')).not.toBeInTheDocument();
  });

  it('이벤트_분포_BE_9항목_순회_렌더', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    const grid = await screen.findByTestId('event-distribution-grid');
    // 분포는 비동기 데이터 로드 후 렌더 — 항목 등장까지 대기
    const items = await within(grid).findAllByRole('listitem');
    expect(items).toHaveLength(9);
    expect(within(grid).getByText('침수(범람)')).toBeInTheDocument();
    expect(within(grid).getByText('납치(유괴)')).toBeInTheDocument();
  });

  it('전체구축현황_이미지카드가_검수완료_수치와_완료율을_표시한다', async () => {
    // given
    setRole('REVIEWER');

    // when
    renderWithProviders(<OverallStatPage />);
    const card = await screen.findByTestId('overall-image-card');

    // then: 주 수치 = 검수완료 12,000장, 보조 = 전체 50,000장 (24%)
    expect(within(card).getByText('12,000')).toBeInTheDocument();
    expect(within(card).queryByText('50,000')).not.toBeInTheDocument();
    expect(
      within(card).getByText('검수완료 기준 · 전체 50,000장 (완료율 24%)'),
    ).toBeInTheDocument();
  });

  it('전체구축현황_영상카드가_검수완료_수치와_완료율을_표시한다', async () => {
    // given
    setRole('REVIEWER');

    // when
    renderWithProviders(<OverallStatPage />);
    const card = await screen.findByTestId('overall-video-card');

    // then: 주 수치 = 검수완료 300건, 보조 = 전체 1,500건 (20%)
    expect(within(card).getByText('300')).toBeInTheDocument();
    expect(within(card).queryByText('1,500')).not.toBeInTheDocument();
    expect(
      within(card).getByText('검수완료 기준 · 전체 1,500건 (완료율 20%)'),
    ).toBeInTheDocument();
  });

  it('전체구축현황_이벤트분포가_검수완료_기준_값을_렌더한다', async () => {
    // given
    setRole('REVIEWER');

    // when
    renderWithProviders(<OverallStatPage />);
    const grid = await screen.findByTestId('event-distribution-grid');

    // then: approvedEventDistribution(51…) 을 렌더하고 전체 분포(100…) 는 렌더하지 않는다
    expect(await within(grid).findByText('51')).toBeInTheDocument();
    expect(within(grid).getByText('11')).toBeInTheDocument();
    expect(within(grid).queryByText('100')).not.toBeInTheDocument();
  });

  it('전체구축현황_전체가_0건이면_완료율이_0퍼센트로_표시된다', async () => {
    // given: 분모 0 — NaN/Infinity 방어
    setRole('REVIEWER');
    mock.reset();
    mock.onGet('/stats/overall').reply(200, {
      success: true,
      data: {
        ...sample,
        cumulativeImageCount: 0,
        cumulativeVideoCount: 0,
        approvedImageCount: 0,
        approvedVideoCount: 0,
      },
      message: null,
      errorCode: null,
    });

    // when
    renderWithProviders(<OverallStatPage />);
    const cards = await screen.findByTestId('cumulative-cards');

    // then
    expect(
      within(cards).getByText('검수완료 기준 · 전체 0장 (완료율 0%)'),
    ).toBeInTheDocument();
    expect(
      within(cards).getByText('검수완료 기준 · 전체 0건 (완료율 0%)'),
    ).toBeInTheDocument();
    expect(within(cards).queryByText(/NaN|Infinity/)).not.toBeInTheDocument();
  });

  it('전체구축현황_검수완료_수치_미수신시_0으로_오인표시하지_않는다', async () => {
    // given: 구버전 BE — approved* 필드 미수신
    setRole('REVIEWER');
    const legacy: Record<string, unknown> = { ...sample };
    delete legacy.approvedImageCount;
    delete legacy.approvedVideoCount;
    delete legacy.approvedEventDistribution;
    mock.reset();
    mock.onGet('/stats/overall').reply(200, {
      success: true,
      data: legacy,
      message: null,
      errorCode: null,
    });

    // when
    renderWithProviders(<OverallStatPage />);
    const cards = await screen.findByTestId('cumulative-cards');

    // then: 0 을 실데이터처럼 표시하지 않고 미집계임을 알린다 (크래시 없음)
    expect(
      within(cards).getByText('전체 50,000장 · 검수완료 집계를 불러오지 못했습니다'),
    ).toBeInTheDocument();
    expect(within(cards).queryByText(/완료율/)).not.toBeInTheDocument();
    expect(within(cards).queryByText('0')).not.toBeInTheDocument();
  });

  // ★기존 테스트 '처리_현황_5_카드_노출' 을 아래 2건으로 교체했다.
  // 그 테스트는 사양 SCREEN-021 ③ 이 명시적으로 부정한 형태(개별 카드 5개·grid-cols-5)를
  // 정답으로 박제하고 있었다 — '전체'가 나머지 4개와 같은 층위의 항목으로 읽히고(실제로는 합계)
  // 각 구간의 비율 정보가 어디에도 없던 레이아웃이다.
  it('처리현황은_스택바_1카드로_4구간과_비율을_보여준다', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    await screen.findByText('처리중');
    const card = screen.getByTestId('processing-cards');

    // 범례는 완료/처리중/대기/실패 4구간이다('전체'는 항목이 아니라 헤더의 합계 텍스트).
    expect(within(card).getByText('완료')).toBeInTheDocument();
    expect(within(card).getByText('처리중')).toBeInTheDocument();
    expect(within(card).getByText('대기')).toBeInTheDocument();
    expect(within(card).getByText('실패')).toBeInTheDocument();
    expect(within(card).queryByText('전체')).toBeNull();

    // 합계는 헤더 우측에 '전체 N건' 으로 표기된다(pending 100 + inProgress 200 +
    // reviewPending 400 + approved 300 + rejected 50 = 1,050).
    expect(screen.getByText('전체 1,050건')).toBeInTheDocument();

    // 스택 바 4구간이 각 비율만큼 폭을 갖는다 — 구 5카드에는 비율 정보가 전혀 없었다.
    expect(within(card).getByTestId('processing-stack-bar')).toBeInTheDocument();
    expect(within(card).getByTestId('processing-bar-completed')).toHaveStyle({
      width: `${(300 / 1050) * 100}%`,
    });
  });

  it('처리현황_4구간_합이_0이면_스택바를_그리지_않는다', async () => {
    // given: 모든 구간이 0 — 폭 0짜리 빈 트랙은 "0건"인지 "못 읽었는지"를 구분해 주지 못한다.
    setRole('REVIEWER');
    mock.onGet('/stats/overall').reply(200, {
      success: true,
      data: {
        ...sample,
        processing: {
          pending: 0,
          inProgress: 0,
          reviewPending: 0,
          approved: 0,
          rejected: 0,
        },
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<OverallStatPage />);

    const card = await screen.findByTestId('processing-cards');
    expect(within(card).queryByTestId('processing-stack-bar')).toBeNull();
    // 범례는 남아 숫자로 0건을 말한다
    expect(within(card).getByText('완료')).toBeInTheDocument();
  });

  it('리포트_다운로드_버튼_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    expect(screen.getByTestId('download-report-btn')).toBeInTheDocument();
  });

  it('헤더에_breadcrumb_설명문_다운로드캡션이_모두_렌더된다', () => {
    // 구 구현은 제목 + 버튼 텍스트뿐이라 아래 셋이 통째로 빠져 있었다(SCREEN-021 ①).
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    // ① breadcrumb — 현재 위치. '통계' 는 LNB 그룹명이라 이동 주소가 없다.
    const crumb = screen.getByRole('navigation', { name: '현재 위치' });
    expect(within(crumb).getByText('통계')).toBeInTheDocument();
    expect(within(crumb).getByText('전체 구축 현황')).toBeInTheDocument();

    // ② 화면 설명문
    expect(
      screen.getByText(
        '구축한 학습데이터의 누적량과 처리 현황, 이벤트 유형 분포, 작업자별 현황을 한자리에서 확인합니다.',
      ),
    ).toBeInTheDocument();

    // ③ 다운로드 캡션 — 무엇을 받는지(집계 범위·형식)를 누르기 전에 알린다.
    //   리포트는 섹션 5개(누적 학습데이터·처리현황·일별 작업량·이벤트 유형 분포·작업자별 현황)를
    //   담고 그중 기간 제한이 걸리는 것은 일별 작업량뿐이다. 그래서 캡션은 '전체 구축 현황'을
    //   말하고 1개월 단서는 일별 작업량에만 붙인다.
    expect(screen.getByTestId('download-report-note')).toHaveTextContent(
      '전체 구축 현황을 CSV 파일로 내려받습니다 (일별 작업량은 최근 1개월).',
    );
  });

  it('OverallStatPage_REVIEWER만_접근', () => {
    // REVIEWER 라우터 가드 검증 — 라우트 정의 자체가 internalReviewerOnly로 잠겨 있는지 확인.
    // 라우트 가드는 별도 가드 테스트(routerGuards)에서 검증되므로 여기선 페이지 렌더 가능 여부만 점검.
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);
    // breadcrumb 현재 위치에도 같은 문구가 있으므로 제목(heading)으로 특정한다.
    expect(screen.getByRole('heading', { name: '전체 구축 현황' })).toBeInTheDocument();
  });
});
