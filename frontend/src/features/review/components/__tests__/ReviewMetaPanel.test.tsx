// ReviewMetaPanel — 검수 화면 메타 읽기 패널 단위 테스트.
//
// 2026-08-03: 'video.*' 영상 기술메타가 '시계열 메타' 섹션에 섞여 나오던 결함의 회귀 가드.
//   기술메타는 VLM 시계열 메타가 아니라 ffprobe/관제 인입이 채운 영상 기술 정보이므로
//   별도 '영상 정보' 섹션에 읽기 전용으로 표시한다(버리지 않는다).

import { screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

const { mockUseMeta, mockUseEventAnnotation } = vi.hoisted(() => ({
  mockUseMeta: vi.fn(),
  mockUseEventAnnotation: vi.fn(),
}));

vi.mock('@/features/auto/hooks/useMeta', () => ({ useMeta: mockUseMeta }));
vi.mock('@/features/label/hooks/useEventAnnotation', () => ({
  useEventAnnotation: mockUseEventAnnotation,
}));

import { ReviewMetaPanel } from '../ReviewMetaPanel';

const TECHNICAL_META = [
  { metaSn: 91, metaKey: 'video.fps', metaVal: '30' },
  { metaSn: 92, metaKey: 'video.codec', metaVal: 'h264' },
  { metaSn: 93, metaKey: 'video.bit_rate', metaVal: '2500000' },
  { metaSn: 94, metaKey: 'video.duration_ms', metaVal: '60000' },
  { metaSn: 95, metaKey: 'video.filesize', metaVal: '18874368' },
  { metaSn: 96, metaKey: 'video.resolution', metaVal: '1920x1080' },
];

describe('ReviewMetaPanel — 기술메타 분리 표시', () => {
  beforeEach(() => {
    mockUseEventAnnotation.mockReturnValue({ data: undefined });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('기술메타는_시계열메타_섹션이_아니라_영상정보_섹션에_표시된다', async () => {
    // given — 시계열 1건 + 기술메타 6건
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: '0-10', metaVal: '차량 3대 진입' }],
        technicalMeta: TECHNICAL_META,
        vlmText: '차량 3대 진입',
        stateChanges: [],
      },
    });

    // when
    renderWithProviders(<ReviewMetaPanel rawSn={10} srcSn={20} />);

    // then — 시계열 섹션에는 VLM 메타만
    const ts = await screen.findByTestId('review-meta-timeseries');
    expect(ts).toHaveTextContent('0-10');
    expect(ts).not.toHaveTextContent('video.fps');
    expect(ts).not.toHaveTextContent('video.resolution');

    // then — 기술메타는 별도 '영상 정보' 섹션에 값과 함께 표시(정보 유실 없음)
    const tech = screen.getByTestId('review-meta-technical');
    expect(tech).toHaveTextContent('video.fps');
    expect(tech).toHaveTextContent('30');
    expect(tech).toHaveTextContent('video.resolution');
    expect(tech).toHaveTextContent('1920x1080');
  });

  it('기술메타만_있으면_시계열_섹션은_렌더되지_않고_영상정보만_표시', async () => {
    // given — VLM 미수행 영상(배치 직후 상태)
    mockUseMeta.mockReturnValue({
      data: {
        items: [],
        technicalMeta: TECHNICAL_META,
        vlmText: '',
        stateChanges: [],
      },
    });

    // when
    renderWithProviders(<ReviewMetaPanel rawSn={10} srcSn={20} />);

    // then
    await waitFor(() =>
      expect(screen.getByTestId('review-meta-technical')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('review-meta-timeseries')).not.toBeInTheDocument();
    // 표시할 것이 있으므로 빈 상태 문구는 나오지 않는다.
    expect(screen.queryByTestId('review-meta-empty')).not.toBeInTheDocument();
  });

  it('메타가_전혀_없으면_빈상태_문구', async () => {
    // given
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], vlmText: '', stateChanges: [] },
    });

    // when
    renderWithProviders(<ReviewMetaPanel rawSn={10} srcSn={20} />);

    // then
    await waitFor(() =>
      expect(screen.getByTestId('review-meta-empty')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('review-meta-technical')).not.toBeInTheDocument();
  });

  // ───────── 일치도 읽기 표시 (2026-08-06, R8) ─────────

  it('검수화면에도_일치도가_읽기전용으로_표시된다', async () => {
    // given — verify 결과: 서술 전문 + 일치도. 검수자는 서술의 신뢰도를 판단할 근거가 필요하다.
    mockUseMeta.mockReturnValue({
      data: {
        items: [
          { metaSn: 1, metaKey: 'vlm.description', metaVal: '차량이 정지선을 넘었다' },
        ],
        technicalMeta: [],
        readOnlyMeta: [{ metaSn: 5, metaKey: 'vlm.accuracy', metaVal: '0.92' }],
        vlmText: '차량이 정지선을 넘었다',
        stateChanges: [],
      },
    });

    // when
    renderWithProviders(<ReviewMetaPanel rawSn={10} srcSn={20} />);

    // then — 값이 보이되 입력 요소는 없다(이 패널은 전체가 읽기 전용).
    const ro = await screen.findByTestId('review-meta-readonly');
    expect(ro).toHaveTextContent('일치도');
    expect(ro).toHaveTextContent('92%');
    expect(ro).not.toHaveTextContent('accuracy');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('일치도만_있어도_빈상태_문구가_뜨지_않는다', async () => {
    // given — 표시할 것이 읽기 전용 항목뿐인 경우
    mockUseMeta.mockReturnValue({
      data: {
        items: [],
        technicalMeta: [],
        readOnlyMeta: [{ metaSn: 5, metaKey: 'vlm.accuracy', metaVal: '0.5' }],
        vlmText: '',
        stateChanges: [],
      },
    });

    // when
    renderWithProviders(<ReviewMetaPanel rawSn={10} srcSn={20} />);

    // then
    await waitFor(() =>
      expect(screen.getByTestId('review-meta-readonly')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('review-meta-empty')).not.toBeInTheDocument();
  });

  it('readOnlyMeta_필드가_없는_응답에도_크래시하지_않는다', async () => {
    // given — 구 BE(배포 스큐) / 로딩 직후
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: '0-10', metaVal: '차량 3대 진입' }],
        technicalMeta: [],
        vlmText: '차량 3대 진입',
        stateChanges: [],
      },
    });

    // when
    renderWithProviders(<ReviewMetaPanel rawSn={10} srcSn={20} />);

    // then
    await waitFor(() =>
      expect(screen.getByTestId('review-meta-timeseries')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('review-meta-readonly')).not.toBeInTheDocument();
  });

  it('technicalMeta_필드가_없는_응답에도_크래시하지_않는다', async () => {
    // given — 구 BE / 로딩 직후 등 방어
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: '0-10', metaVal: '차량 3대 진입' }],
        vlmText: '차량 3대 진입',
        stateChanges: [],
      },
    });

    // when
    renderWithProviders(<ReviewMetaPanel rawSn={10} srcSn={20} />);

    // then
    await waitFor(() =>
      expect(screen.getByTestId('review-meta-timeseries')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('review-meta-technical')).not.toBeInTheDocument();
  });
});
