// 검수 '메타' 탭 구성 회귀 가드 — 작업자 라벨링 화면과 같은 섹션·같은 순서 [design: SCREEN-019]
//
// 왜 필요한가: 구 구성은 시계열 메타·이벤트 어노테이션·영상 정보 3개뿐이고 순서도 작업자와
//   역순이라, 작업자가 입력한 촬영환경·개인정보 판정(영상축·프레임축)·프레임 설명을 검수자가
//   확인할 경로가 <b>아예 없었다</b>. 개인정보 3필드는 학습데이터 산출물의 원천이라 검수 없이
//   확정되면 안 되는 값이다.
//
// ⚠ 모듈 전체 자동 모의를 쓰지 않고 <b>export 를 하나씩 명시</b>한다 — 자동 모의는 그 파일이
//   함께 내보내는 판정 함수·상수까지 undefined 로 만들어 화면이 조용히 다른 분기로 떨어진다.
//
// 변이 실증(되돌리면 RED):
//   ① 읽기 전용 플래그를 기본값으로 뒤집기 → 작업자 편집 가드(각 패널 단위 테스트)가 RED
//   ② 어댑터에서 importedMeta 를 다시 버리기 → '이관 원문' 케이스가 RED
//   ③ 빈 상태 판정에서 importedMeta 를 빼기 → '이관 원문만 있는 영상' 케이스가 RED
//   ④ 섹션 순서 바꾸기 → '섹션 제목·순서' 케이스가 RED
//   ⑤ 빈 상태 안내를 패널 맨 위 + 구 문구("표시할 메타 정보가 없습니다")로 되돌리기
//      → '개인정보 판정만 채워진 프레임' 케이스가 RED (문구 축·자리 축 둘 다)

import { screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

const {
  mockUseMeta,
  mockUseEventAnnotation,
  mockUseEnvironmentMeta,
  mockUseVideoPrivacyMeta,
  mockUseFrameDescription,
  mockUseFramePrivacyMeta,
  noopMutation,
} = vi.hoisted(() => ({
  mockUseMeta: vi.fn(),
  mockUseEventAnnotation: vi.fn(),
  mockUseEnvironmentMeta: vi.fn(),
  mockUseVideoPrivacyMeta: vi.fn(),
  mockUseFrameDescription: vi.fn(),
  mockUseFramePrivacyMeta: vi.fn(),
  noopMutation: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
}));

vi.mock('@/features/auto/hooks/useMeta', () => ({ useMeta: mockUseMeta }));
vi.mock('@/features/label/hooks/useEventAnnotation', () => ({
  useEventAnnotation: mockUseEventAnnotation,
}));
vi.mock('@/features/label/hooks/useEnvironmentMeta', () => ({
  useEnvironmentMeta: mockUseEnvironmentMeta,
  useUpdateEnvironmentMeta: noopMutation,
}));
vi.mock('@/features/label/hooks/useVideoPrivacyMeta', () => ({
  useVideoPrivacyMeta: mockUseVideoPrivacyMeta,
  useUpdateVideoPrivacyMeta: noopMutation,
}));
vi.mock('@/features/label/hooks/useFrameDescription', () => ({
  useFrameDescription: mockUseFrameDescription,
  useUpdateFrameDescription: noopMutation,
}));
vi.mock('@/features/label/hooks/useFramePrivacyMeta', () => ({
  useFramePrivacyMeta: mockUseFramePrivacyMeta,
  useUpdateFramePrivacyMeta: noopMutation,
}));

import { ReviewMetaPanel } from '../ReviewMetaPanel';

/**
 * 사양이 고정한 <b>접이식 섹션</b>의 제목·순서 — 라벨링 화면(SCREEN-005) 메타 탭과 같다.
 * 뒤의 두 개는 참고 정보이며 값이 있을 때만 나타난다.
 *
 * ⚠ 2026-09-14 — 구 목록의 「시계열 메타」·「이벤트 어노테이션」 두 섹션은 <b>요약 카드 하나</b>로
 *   바뀌었다(전제 변경, 회귀 아님). 카드는 접이식 섹션이 아니라 이 목록에 없고, 그 자리는 아래
 *   별도 케이스가 DOM 순서로 고정한다.
 */
const EXPECTED_SECTIONS = [
  // ★검수 화면의 구역 이름은 라벨링과 <b>일부러 다르다</b> — 검수는 「…검토」로 끝난다
  //   (SCREEN-019 v48). 구 이름(「촬영환경」·「개인정보(영상)」 …)은 구현이 사양을 따르지
  //   않았던 것이고, 두 화면을 같은 이름으로 「통일」하면 그 확정을 되돌리는 것이다.
  '촬영환경 검토',
  '개인정보 판정 검토 (영상 축)',
  '프레임 설명 검토',
  '개인정보 판정 검토 (프레임 축)',
  '이관 원문 정보 검토',
  '영상 기술 정보 (읽기 전용)',
];

/**
 * 빈 상태 판정이 <b>실제로 세는</b> 축 — 이 넷은 하나의 메타 조회 응답에서 나온다.
 * 안내 문구는 이 이름들을 그대로 담아 자기 범위를 밝혀야 한다.
 * (앞의 둘은 요약 카드가 가리키는 축이라 접이식 섹션 목록에는 없다.)
 */
const EMPTY_BANNER_SCOPE = [
  '영상 분석 설명',
  '이벤트 어노테이션',
  '이관 원문 정보',
  '영상 기술 정보',
];

/**
 * 빈 상태 판정이 <b>세지 못하는</b> 섹션 — 각 패널이 자기 훅으로 따로 조회하고 판정과 무관하게
 * 항상 렌더된다. 안내 문구가 이 이름들까지 아우르면 값이 있는데도 없다고 말하는 셈이 된다.
 */
const OUT_OF_BANNER_SCOPE = EXPECTED_SECTIONS.slice(0, 4);

const IMPORTED_META = [
  { metaSn: 401, metaKey: 'import.video.cctv_height', metaVal: '4.5' },
  { metaSn: 402, metaKey: 'import.image.privacy_included', metaVal: 'N' },
  // 매핑에 없는 열쇠 — 버리지 않고 원문 그대로 표시해야 한다(조용한 손실 금지).
  { metaSn: 403, metaKey: 'import.video.brand_new_key', metaVal: '아직 이름 없는 값' },
];

function setMeta(overrides: Record<string, unknown> = {}) {
  mockUseMeta.mockReturnValue({
    data: {
      items: [],
      technicalMeta: [],
      readOnlyMeta: [],
      importedMeta: [],
      vlmText: '',
      stateChanges: [],
      ...overrides,
    },
  });
}

/** 렌더된 접이식 섹션의 제목을 나타난 순서대로 뽑는다. */
function sectionTitles(): string[] {
  return screen
    .getAllByTestId('meta-section')
    .map((el) => el.querySelector('button')?.textContent?.trim() ?? '');
}

function renderPanel() {
  return renderWithProviders(
    <ReviewMetaPanel rawSn={10} srcSn={20} windowState="closed" onOpenWindow={vi.fn()} />,
  );
}

describe('검수 메타 탭 — 작업자 화면과 같은 구성', () => {
  beforeEach(() => {
    mockUseEventAnnotation.mockReturnValue({ data: undefined });
    mockUseEnvironmentMeta.mockReturnValue({ data: undefined, isLoading: false });
    mockUseVideoPrivacyMeta.mockReturnValue({ data: undefined, isLoading: false });
    mockUseFrameDescription.mockReturnValue({ data: undefined, isLoading: false });
    mockUseFramePrivacyMeta.mockReturnValue({ data: undefined, isLoading: false });
    setMeta();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('섹션_제목과_순서가_작업자_메타탭과_같다', async () => {
    // given — 참고 섹션까지 모두 뜨도록 전 축에 값을 채운다.
    mockUseEventAnnotation.mockReturnValue({
      data: { reviewStatus: 'PENDING', payload: { event_class: '화재발생' } },
    });
    setMeta({
      items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '연기가 보인다' }],
      technicalMeta: [{ metaSn: 91, metaKey: 'video.fps', metaVal: '30' }],
      importedMeta: IMPORTED_META,
    });

    // when
    renderPanel();

    // then — 제목과 순서가 사양과 정확히 일치한다(부분 일치가 아니라 전량 대조).
    await waitFor(() =>
      expect(screen.getAllByTestId('meta-section').length).toBe(EXPECTED_SECTIONS.length),
    );
    expect(sectionTitles()).toEqual(EXPECTED_SECTIONS);

    // then — 요약 카드는 프레임축 개인정보와 이관 원문 <b>사이</b>다(라벨링 화면과 같은 자리).
    const card = screen.getByTestId('annotation-summary-card');
    const sections = screen.getAllByTestId('meta-section');
    const framePrivacy = sections[3];
    const imported = sections[4];
    expect(
      framePrivacy.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeGreaterThan(0);
    expect(card.compareDocumentPosition(imported) & Node.DOCUMENT_POSITION_FOLLOWING).toBeGreaterThan(
      0,
    );
  });

  it('입력_가능한_컨트롤이_하나도_없다_섹션_접기_토글만_있다', async () => {
    // given — 편집 컨트롤이 생길 여지가 가장 큰 상태(전 축에 값이 있음)
    mockUseEventAnnotation.mockReturnValue({
      data: { reviewStatus: 'PENDING', payload: { event_class: '화재발생', answer: 'A' } },
    });
    mockUseEnvironmentMeta.mockReturnValue({
      data: { weather: '맑음', timeOfDay: 'DAY', season: 'SUMMER' },
      isLoading: false,
    });
    mockUseVideoPrivacyMeta.mockReturnValue({
      data: { anonymity: 'Y', pseudonymity: 'N', privacyIncluded: 'N' },
      isLoading: false,
    });
    mockUseFrameDescription.mockReturnValue({
      data: { description: '보도에 사람이 서 있다' },
      isLoading: false,
    });
    mockUseFramePrivacyMeta.mockReturnValue({
      data: { anonymity: 'Y', pseudonymity: 'N', privacyIncluded: 'N' },
      isLoading: false,
    });
    setMeta({
      items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '연기가 보인다' }],
      importedMeta: IMPORTED_META,
    });

    // when
    renderPanel();
    const panel = await screen.findByTestId('review-meta-panel');

    // then — 입력·선택·체크박스·라디오 어느 것도 없다.
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('radio')).toBeNull();
    // then — 버튼은 <b>섹션 접기 토글 + 요약 카드의 「크게 보기」 하나</b>뿐이다.
    //   ⚠ 카드 버튼은 값을 고치지 않는다 — 읽기 전용 창을 여는 입구다. 그것까지 「0개」로 고정하면
    //     사양이 요구하는 진입점을 가드가 막는다(구 「버튼 수 = 섹션 수」 단언은 그래서 넓혔다).
    const buttons = screen.getAllByRole('button');
    const openButton = screen.getByTestId('annotation-summary-open');
    expect(buttons.length).toBe(screen.getAllByTestId('meta-section').length + 1);
    for (const btn of buttons) {
      if (btn === openButton) continue;
      expect(btn).toHaveAttribute('aria-expanded');
    }
    // ⚠ 「'저장' 글자가 없다」로 보지 않는다 — 요약 카드 설명에 「학습데이터에 함께 <b>저장</b>되는」이
    //   들어 있어 정상 문구가 위반으로 잡힌다. 지키려는 것은 <b>저장 조작이 없다</b>이므로 버튼으로 센다.
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull();
    expect(panel).toBeInTheDocument();
  });

  it('작업자가_입력한_촬영환경_개인정보_프레임설명이_그대로_보인다', async () => {
    // given — 작업자가 라벨링 화면에서 입력해 둔 값
    mockUseEnvironmentMeta.mockReturnValue({
      data: { weather: '눈', timeOfDay: 'NGT', season: 'WINTER' },
      isLoading: false,
    });
    mockUseVideoPrivacyMeta.mockReturnValue({
      data: {
        anonymity: 'Y',
        pseudonymity: 'N',
        privacyIncluded: 'Y',
        anonymitySource: 'MANUAL',
        pseudonymitySource: 'MANUAL',
        privacyIncludedSource: 'MANUAL',
      },
      isLoading: false,
    });
    mockUseFrameDescription.mockReturnValue({
      data: { description: '야간 보도에서 보행자 낙상' },
      isLoading: false,
    });
    mockUseFramePrivacyMeta.mockReturnValue({
      data: { anonymity: 'N', pseudonymity: 'Y', privacyIncluded: 'N' },
      isLoading: false,
    });

    // when
    renderPanel();

    // then — 촬영환경은 코드가 아니라 한글 표시로 뜬다.
    const env = await screen.findByTestId('environment-meta-readonly');
    expect(env).toHaveTextContent('눈');
    expect(env).toHaveTextContent('야간');
    expect(env).toHaveTextContent('겨울');

    // then — 개인정보 판정은 축별로 각자의 값이 뜬다(입도가 다른 별개 축).
    const videoPrivacy = screen.getByTestId('video-privacy-meta-readonly');
    expect(videoPrivacy).toHaveTextContent('익명여부');
    const framePrivacy = screen.getByTestId('frame-privacy-meta-readonly');
    expect(videoPrivacy.textContent).not.toBe(framePrivacy.textContent);

    // then — 프레임 설명 원문
    expect(screen.getByTestId('frame-description-readonly')).toHaveTextContent(
      '야간 보도에서 보행자 낙상',
    );
  });

  it('이관_원문은_한글_라벨로_뜨고_매핑에_없는_열쇠는_원문_그대로_뜬다', async () => {
    // given
    setMeta({ importedMeta: IMPORTED_META });

    // when
    renderPanel();

    // then — 사람이 읽는 이름 + 값
    const imported = await screen.findByTestId('imported-meta-panel');
    expect(imported).toHaveTextContent('카메라 설치 높이');
    expect(imported).toHaveTextContent('4.5');
    expect(imported).toHaveTextContent('원천 개인정보 포함여부(프레임)');
    // then — 매핑에 없는 열쇠도 사라지지 않는다(조용한 손실 금지).
    expect(imported).toHaveTextContent('import.video.brand_new_key');
    expect(imported).toHaveTextContent('아직 이름 없는 값');
  });

  it('이관_원문_열쇠는_영상_분석_설명_요약에_한_건도_나오지_않는다', async () => {
    // given — 서버가 두 목록을 갈라 내려준다(분류의 소유자는 서버다).
    setMeta({
      items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '연기가 보인다' }],
      importedMeta: IMPORTED_META,
    });

    // when
    renderPanel();

    // then
    const card = await screen.findByTestId('annotation-summary-card');
    expect(card).toHaveTextContent('연기가 보인다');
    expect(card).not.toHaveTextContent('import.');
    expect(card).not.toHaveTextContent('카메라 설치 높이');
  });

  it('이관_원문만_있는_영상은_빈_상태_안내가_뜨지_않는다', async () => {
    // given — 보여줄 값이 이관 원문뿐인 영상. 빈 상태 판정이 그 목록을 세지 않으면 거짓말이 된다.
    setMeta({ importedMeta: IMPORTED_META });

    // when
    renderPanel();

    // then
    await waitFor(() =>
      expect(screen.getByTestId('imported-meta-panel')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('review-meta-empty')).toBeNull();
  });

  // ───────── 빈 상태 안내의 범위 (2026-08-27, 독립 QA 지적) ─────────
  //
  // 빈 상태 판정은 <b>메타 조회 응답</b>만 센다. 앞의 네 섹션은 각 패널이 자기 훅으로 따로
  // 조회하고 판정과 무관하게 항상 렌더되므로, 안내를 패널 맨 위에 두고 "표시할 메타 정보가
  // 없습니다"라고 쓰면 값이 있는데도 없다고 말하게 된다. 아래 두 케이스가 <b>양방향</b>을 고정한다.

  it('★개인정보_판정만_채워진_프레임에서_안내와_실제_값이_동시에_보이지_않는다', async () => {
    // given — 메타 응답 네 목록이 모두 0건이고 이벤트 어노테이션도 없다. 그런데 개인정보 3필드는
    //   적재 시점에 값이 채워지므로 그 두 섹션에는 실제 판정값이 있다. 기술메타가 없는 영상
    //   (ffprobe skip · 이관 영상)이면 이 조합이 실제로 나온다 — 도달성이 낮지 않다.
    setMeta();
    mockUseVideoPrivacyMeta.mockReturnValue({
      data: { anonymity: 'Y', pseudonymity: 'N', privacyIncluded: 'N' },
      isLoading: false,
    });
    mockUseFramePrivacyMeta.mockReturnValue({
      data: { anonymity: 'Y', pseudonymity: 'N', privacyIncluded: 'N' },
      isLoading: false,
    });

    // then — 값이 실제로 그려진다(시나리오가 성립함을 먼저 고정한다).
    renderPanel();
    const videoPrivacy = await screen.findByTestId('video-privacy-meta-readonly');
    expect(videoPrivacy).toHaveTextContent('익명여부');
    expect(videoPrivacy).toHaveTextContent('예');

    // then — 안내는 뜨되 «자기가 세는 네 섹션»만 이름으로 가리킨다.
    //   범위를 밝히지 않는 구 문구로 되돌리면 위 판정값과 정면으로 모순된다.
    const banner = screen.getByTestId('review-meta-empty');
    const text = banner.textContent ?? '';
    for (const name of EMPTY_BANNER_SCOPE) {
      expect(text).toContain(name);
    }
    // then — 판정이 세지 못하는 섹션까지 없다고 말하지 않는다.
    for (const name of OUT_OF_BANNER_SCOPE) {
      expect(text).not.toContain(name);
    }

    // then — 자리도 그 네 섹션 쪽이다: 값이 있는 패널들보다 <b>뒤</b>에 온다.
    const framePrivacy = screen.getByTestId('frame-privacy-meta-readonly');
    expect(
      framePrivacy.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeGreaterThan(0);
  });

  it('메타가_전혀_없는_프레임에서는_빈_상태_안내가_그대로_뜬다', async () => {
    // given — 네 목록 모두 0건 + 이벤트 어노테이션 미생성
    setMeta();

    // when
    renderPanel();

    // then
    await waitFor(() =>
      expect(screen.getByTestId('review-meta-empty')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('imported-meta-panel')).toBeNull();
    expect(screen.queryByTestId('review-meta-timeseries')).toBeNull();
    // then — 정말 0건일 때도 안내는 «범위를 밝힌 같은 문구»다(두 상황에 서로 다른 문구를 두지
    //   않는다 — 검수자가 문구 차이를 상태 차이로 읽게 된다).
    const text = screen.getByTestId('review-meta-empty').textContent ?? '';
    for (const name of EMPTY_BANNER_SCOPE) {
      expect(text).toContain(name);
    }
  });

  it('importedMeta_필드가_없는_구_BE_응답에도_크래시하지_않는다', async () => {
    // given — 구 서버(배포 스큐): 새 목록 자체가 없다.
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '연기가 보인다' }],
        technicalMeta: [],
        readOnlyMeta: [],
        vlmText: '연기가 보인다',
        stateChanges: [],
      },
    });

    // when
    renderPanel();

    // then
    await waitFor(() =>
      expect(screen.getByTestId('annotation-summary-description')).toHaveTextContent(
        '연기가 보인다',
      ),
    );
    expect(screen.queryByTestId('imported-meta-panel')).toBeNull();
  });
});
