// ReviewMetaPanel — 검수 화면 메타 읽기 패널 단위 테스트.
//
// 2026-08-03: 'video.*' 영상 기술메타가 시계열 메타 섹션에 섞여 나오던 결함의 회귀 가드.
//   기술메타는 분석 결과가 아니라 ffprobe/관제 인입이 채운 영상 기술 정보이므로
//   별도 '영상 정보' 섹션에 읽기 전용으로 표시한다(버리지 않는다).
//
// ⚠ 2026-09-14 — 영상 분석 설명·이벤트 어노테이션은 이 탭에서 <b>요약 카드</b>가 되고 전문은
//   창으로 옮겨갔다(전제 변경, 회귀 아님). 그래서 이 파일이 보는 것은 「기술메타가 그 요약에
//   섞이지 않는가」와 「빈 상태 판정」이다.

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

function renderPanel() {
  return renderWithProviders(
    <ReviewMetaPanel rawSn={10} srcSn={20} windowState="closed" onOpenWindow={vi.fn()} />,
  );
}

describe('ReviewMetaPanel — 기술메타 분리 표시', () => {
  beforeEach(() => {
    mockUseEventAnnotation.mockReturnValue({ data: undefined });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('기술메타는_요약카드가_아니라_영상_기술_정보_구역에_표시된다', async () => {
    // given — 영상 분석 설명 1건 + 기술메타 6건
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '차량 3대 진입' }],
        technicalMeta: TECHNICAL_META,
        vlmText: '차량 3대 진입',
        stateChanges: [],
      },
    });

    // when
    renderPanel();

    // then — 요약 카드에는 분석 설명만(기술메타가 섞이지 않는다)
    const card = await screen.findByTestId('annotation-summary-card');
    expect(card).toHaveTextContent('차량 3대 진입');
    expect(card).not.toHaveTextContent('video.fps');
    expect(card).not.toHaveTextContent('video.resolution');

    // then — 기술메타는 별도 「영상 기술 정보 (읽기 전용)」 구역에 <b>사람이 읽는 값</b>으로 뜬다.
    // ★구 렌더는 서버 K/V 를 날것으로 늘어놓아 내부 저장 키가 라벨이었다(`video.fps 30`).
    //   되돌아가면 이 단언들이 한꺼번에 깨진다.
    const tech = screen.getByTestId('video-technical-meta');
    expect(tech).toHaveTextContent('해상도');
    expect(tech).toHaveTextContent('1920×1080');
    expect(tech).toHaveTextContent('코덱');
    expect(tech).toHaveTextContent('h264');
    expect(tech).toHaveTextContent('프레임률');
    expect(tech).toHaveTextContent('30.00 fps');
    expect(tech).toHaveTextContent('길이');
    expect(tech).toHaveTextContent('1분 0.0초');

    // ★내부 저장 키를 라벨로 쓰지 않는다.
    expect(tech).not.toHaveTextContent('video.');
    // ★네 항목만 보인다 — 파일 크기·비트레이트는 응답에 있어도 이 자리의 대상이 아니다.
    expect(tech).not.toHaveTextContent('18874368');
    expect(tech).not.toHaveTextContent('2500000');
    // ★밀리초를 날것으로 보이지 않는다(길이는 분·초로 환산한다).
    expect(tech).not.toHaveTextContent('60000');
  });

  it('기술메타만_있어도_요약카드는_항상_있고_영상_기술_정보가_표시된다', async () => {
    // given — 분석 미수행 영상(배치 직후 상태)
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: TECHNICAL_META, vlmText: '', stateChanges: [] },
    });

    // when
    renderPanel();

    // then
    await waitFor(() =>
      expect(screen.getByTestId('video-technical-meta')).toBeInTheDocument(),
    );
    // ★요약 카드는 값이 없어도 <b>항상</b> 있다 — 창을 여는 유일한 입구이기 때문이다.
    expect(screen.getByTestId('annotation-summary-card')).toBeInTheDocument();
    // 표시할 것이 있으므로 빈 상태 문구는 나오지 않는다.
    expect(screen.queryByTestId('review-meta-empty')).not.toBeInTheDocument();
  });

  it('메타가_전혀_없으면_빈상태_문구', async () => {
    // given
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], vlmText: '', stateChanges: [] },
    });

    // when
    renderPanel();

    // then
    await waitFor(() => expect(screen.getByTestId('review-meta-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('video-technical-meta')).not.toBeInTheDocument();
  });

  // ───────── 일치도 읽기 표시 (2026-08-06, R8) ─────────

  it('★검수화면도_일치도를_표시하지_않는다_구_참고정보_섹션_폐기', async () => {
    // given — 과거 위탁분이 남아 있는 영상. 판정 창구를 연동하지 않게 되면서 이 값은 새로
    //   생기지 않고, 남은 것을 화면에서 빼기로 확정했다(2026-08-24 사용자 확정).
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '차량이 정지선을 넘었다' }],
        technicalMeta: [],
        readOnlyMeta: [{ metaSn: 5, metaKey: 'vlm.accuracy', metaVal: '0.92' }],
        vlmText: '차량이 정지선을 넘었다',
        stateChanges: [],
      },
    });

    // when
    renderPanel();

    // then — 분석 설명은 그대로 요약에 뜨고(대조군), 참고 정보 섹션은 통째로 없다.
    expect(await screen.findByText('차량이 정지선을 넘었다')).toBeInTheDocument();
    expect(screen.queryByTestId('review-meta-readonly')).not.toBeInTheDocument();
    expect(screen.queryByText('참고 정보')).not.toBeInTheDocument();
    expect(screen.queryByText('일치도')).not.toBeInTheDocument();
    expect(screen.queryByText('92%')).not.toBeInTheDocument();
  });

  it('★일치도만_있는_영상은_빈상태_문구가_뜬다_구_동작_반전', async () => {
    // given — 표시할 것이 읽기 전용 항목뿐인 경우. 그 항목을 그리지 않기로 했으므로
    //   이 영상은 <b>정말로 보여줄 메타가 없다</b> — 빈 상태 문구가 사실에 맞다.
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
    renderPanel();

    // then
    await waitFor(() => expect(screen.getByTestId('review-meta-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('review-meta-readonly')).not.toBeInTheDocument();
  });

  it('readOnlyMeta_필드가_없는_응답에도_크래시하지_않는다', async () => {
    // given — 구 BE(배포 스큐) / 로딩 직후
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '차량 3대 진입' }],
        technicalMeta: [],
        vlmText: '차량 3대 진입',
        stateChanges: [],
      },
    });

    // when
    renderPanel();

    // then
    await waitFor(() =>
      expect(screen.getByTestId('annotation-summary-card')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('review-meta-readonly')).not.toBeInTheDocument();
  });

  it('technicalMeta_필드가_없는_응답에도_크래시하지_않는다', async () => {
    // given — 구 BE / 로딩 직후 등 방어
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '차량 3대 진입' }],
        vlmText: '차량 3대 진입',
        stateChanges: [],
      },
    });

    // when
    renderPanel();

    // then
    await waitFor(() =>
      expect(screen.getByTestId('annotation-summary-card')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('video-technical-meta')).not.toBeInTheDocument();
  });

  it('★검수_요약카드에는_검토_상태_행이_없다', async () => {
    // given — 검토 상태가 있는 영상. 라벨링 카드에는 이 행이 있지만 검수에는 두지 않는다
    //   (검토 상태 확정은 영상 검수 승인 시 자동이라 검수자가 이 자리에서 판단할 것이 없다).
    mockUseEventAnnotation.mockReturnValue({
      data: { reviewStatus: 'PENDING', payload: { event_class: 'fire' } },
    });
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], vlmText: '', stateChanges: [] },
    });

    // when
    renderPanel();

    // then
    const card = await screen.findByTestId('annotation-summary-card');
    expect(card).not.toHaveTextContent('검토 상태');
    expect(card).not.toHaveTextContent('검토 대기');
    expect(screen.queryByTestId('annotation-summary-review-status')).not.toBeInTheDocument();
    // 검수 화면의 버튼 문구는 「크게 보기」다(「작성」이 붙지 않는다).
    expect(screen.getByTestId('annotation-summary-open')).toHaveTextContent('크게 보기');
    expect(screen.getByTestId('annotation-summary-open')).not.toHaveTextContent('작성');
  });
});
