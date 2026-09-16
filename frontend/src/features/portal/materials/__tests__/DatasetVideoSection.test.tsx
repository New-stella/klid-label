/**
 * 데이터셋 영상 구역 회귀 가드. [@design SCREEN-046] [@design API-253]
 *
 * 고정하는 것
 *  ① 등록 중 · 등록 실패 · 조회 실패 · 모르는 상태 · 영상 없음을 **서로 다른 자리**로 그린다 —
 *     오류나 등록 중을 「영상이 없다」로 보이면 데이터셋이 비었다고 오해한다.
 *  ② 라벨링 진입 프레임은 응답의 `entrySrcSn` 이 정한다. 비면 진입을 두지 않고 사유를 보인다.
 *  ③ 저장한 적 있는 영상은 「이어서 라벨링」 + 배지, 없으면 「라벨링」.
 *  ④ 구역 머리가 「저장해야 내 작업에 남는다」를 말한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';

import { DatasetVideoSection, DATASET_VIDEOS_LEAD } from '../components/DatasetVideoSection';
import type { PortalDatasetVideo, PortalDatasetVideoPage } from '../types';

const useDatasetVideosMock = vi.fn();
vi.mock('../hooks/useDatasetVideos', () => ({
  useDatasetVideos: (...args: unknown[]) => useDatasetVideosMock(...args),
}));

function video(over: Partial<PortalDatasetVideo> = {}): PortalDatasetVideo {
  return {
    rawSn: 11,
    videoName: 'CCTV-서울-0912.mp4',
    frameCount: 30,
    labelCount: 42,
    entrySrcSn: 901,
    lastSavedAt: null,
    ...over,
  };
}

function page(over: Partial<PortalDatasetVideoPage> = {}): PortalDatasetVideoPage {
  const content = over.content ?? [video()];
  return {
    registrationState: 'DONE',
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 20,
    ...over,
  };
}

function mockQuery(over: Partial<Record<string, unknown>> = {}) {
  const refetch = vi.fn();
  useDatasetVideosMock.mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
    refetch,
    ...over,
  });
  return refetch;
}

function renderSection() {
  return renderWithProviders(<DatasetVideoSection datasetId={4704} />);
}

describe('데이터셋 영상 구역', () => {
  beforeEach(() => {
    useDatasetVideosMock.mockReset();
  });

  it('★구역_머리가_저장해야_내_작업에_남는다고_말한다', () => {
    mockQuery({ data: page() });
    renderSection();
    expect(screen.getByRole('heading', { name: '데이터셋 영상' })).toBeInTheDocument();
    expect(screen.getByText(DATASET_VIDEOS_LEAD)).toBeInTheDocument();
    expect(DATASET_VIDEOS_LEAD).toContain('저장해야 내 작업에 남습니다');
    // 건수는 목록 바로 위 킷 건수 줄이 그린다(「총 N건」) — 굵은 숫자가 별개 조각이라
    // 텍스트 노드 하나로는 잡히지 않는다. 줄 전체를 후크로 집어 본문으로 본다.
    expect(screen.getByTestId('dataset-videos-count')).toHaveTextContent('총 1건');
  });

  it('★응답이_준_프레임으로_라벨링을_연다_저장_전이면_라벨링', () => {
    mockQuery({ data: page({ content: [video({ rawSn: 11, entrySrcSn: 901, lastSavedAt: null })] }) });
    renderSection();

    const row = within(screen.getByTestId('dataset-video-row-11'));
    const link = row.getByRole('link', { name: 'CCTV-서울-0912.mp4 라벨링' });
    expect(link).toHaveAttribute('href', '/portal/label/901');
    expect(row.getByText('30장')).toBeInTheDocument();
    expect(row.getByText('42건')).toBeInTheDocument();
    expect(row.queryByText('저장한 작업 있음')).not.toBeInTheDocument();
  });

  it('★저장한_적_있으면_이어서_라벨링과_배지를_보인다', () => {
    mockQuery({
      data: page({ content: [video({ rawSn: 12, entrySrcSn: 955, lastSavedAt: '2026-09-15T10:20:00' })] }),
    });
    renderSection();

    const row = within(screen.getByTestId('dataset-video-row-12'));
    expect(row.getByRole('link', { name: /이어서 라벨링$/ })).toHaveAttribute('href', '/portal/label/955');
    expect(row.getByText('저장한 작업 있음')).toBeInTheDocument();
    expect(row.getByText(/마지막 저장/)).toBeInTheDocument();
  });

  it('★열_프레임이_없으면_진입을_두지_않고_사유를_보인다', () => {
    mockQuery({ data: page({ content: [video({ rawSn: 13, entrySrcSn: null })] }) });
    renderSection();

    const row = within(screen.getByTestId('dataset-video-row-13'));
    expect(row.queryByRole('link')).not.toBeInTheDocument();
    expect(row.getByText('열 수 있는 프레임이 없습니다.')).toBeInTheDocument();
  });

  it('★등록_중이면_목록도_빈_상태도_아니라_등록_중이라고_말한다', () => {
    mockQuery({ data: page({ registrationState: 'IN_PROGRESS', content: [] }) });
    renderSection();

    expect(screen.getByTestId('dataset-videos-registering')).toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-empty')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-list')).not.toBeInTheDocument();
  });

  it('★등록_실패는_빈_상태와_구분하고_다시_확인을_둔다', () => {
    const refetch = mockQuery({ data: page({ registrationState: 'FAILED', content: [] }) });
    renderSection();

    const alert = screen.getByTestId('dataset-videos-registration-failed');
    expect(screen.queryByTestId('dataset-videos-empty')).not.toBeInTheDocument();
    within(alert).getByRole('button', { name: '다시 확인' }).click();
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('★조회_실패는_영상이_없다고_말하지_않는다', () => {
    mockQuery({ isError: true });
    renderSection();

    expect(screen.getByTestId('dataset-videos-error')).toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-empty')).not.toBeInTheDocument();
  });

  it('★모르는_등록_상태는_완료로_읽지_않는다', () => {
    mockQuery({ data: page({ registrationState: 'PAUSED', content: [video()] }) });
    renderSection();

    expect(screen.getByTestId('dataset-videos-unknown-state')).toBeInTheDocument();
    expect(screen.queryByTestId('dataset-videos-list')).not.toBeInTheDocument();
  });

  it('등록이_끝났는데_영상이_없으면_빈_상태를_보인다', () => {
    mockQuery({ data: page({ content: [] }) });
    renderSection();

    expect(screen.getByTestId('dataset-videos-empty')).toBeInTheDocument();
  });
});
