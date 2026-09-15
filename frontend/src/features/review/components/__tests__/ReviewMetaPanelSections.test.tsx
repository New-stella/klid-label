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
import userEvent from '@testing-library/user-event';
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
  // ★「(참고)」는 검수 화면에만 붙는다(SCREEN-019 v50). 라벨링 사양은 그 표기를 두지 않으므로
  //   두 화면의 제목을 「통일」하면 확정을 되돌리는 것이다.
  '이관 원문 정보 검토 (참고)',
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
    // ★도움말 선택은 브라우저에 기억된다 — 지우지 않으면 앞 케이스에서 켠 상태가 다음 케이스로
    //   새어, 「기본은 감춤」을 세는 단언이 앞 케이스 순서에 따라 흔들린다.
    localStorage.clear();
  });

  afterEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
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
    // then — 버튼은 <b>섹션 접기 토글 + 요약 카드의 「크게 보기」 + 도움말 토글</b>뿐이다.
    //   ⚠ 뒤의 둘은 값을 고치지 않는다 — 하나는 읽기 전용 창의 입구이고 하나는 설명문을 여닫는다.
    //     그것까지 「0개」로 고정하면 사양이 요구하는 진입점을 가드가 막는다.
    //   ★건수만 세지 않고 <b>정체를 하나씩 확인</b>한다 — 건수만 맞추면 편집 버튼이 하나 늘고
    //     접기 토글이 하나 사라져도 총합이 같아 통과한다.
    const buttons = screen.getAllByRole('button');
    const openButton = screen.getByTestId('annotation-summary-open');
    const helpToggle = screen.getByTestId('meta-help-toggle');
    expect(buttons.length).toBe(screen.getAllByTestId('meta-section').length + 2);
    for (const btn of buttons) {
      if (btn === openButton || btn === helpToggle) continue;
      expect(btn).toHaveAttribute('aria-expanded');
    }
    // 도움말 토글은 접기 토글이 아니다 — 눌림 상태로 자기 정체를 밝힌다.
    expect(helpToggle).toHaveAttribute('aria-pressed');
    expect(helpToggle).not.toHaveAttribute('aria-expanded');
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

    // then — 개인정보 판정은 읽기 전용에서 <b>한 줄로 묶인다</b>(SCREEN-019 v50):
    //   라벨 「익명 · 가명 · 개인정보 포함」 + 값을 같은 차례로 이어 붙인 한 줄.
    //   ⚠ 구 표기(「익명여부」 세 줄)로 되돌리면 이 단언이 죽는다 — 세 줄이면 좁은 탭에서 값
    //     박스가 셋 서 자리를 세 배로 쓴다.
    const videoPrivacy = screen.getByTestId('video-privacy-meta-readonly');
    expect(videoPrivacy).toHaveTextContent('익명 · 가명 · 개인정보 포함');
    expect(videoPrivacy).toHaveTextContent('예 · 아니오 · 예');
    expect(videoPrivacy).not.toHaveTextContent('익명여부');
    const framePrivacy = screen.getByTestId('frame-privacy-meta-readonly');
    // 프레임 축은 같은 모양이되 값이 다르다(입도가 다른 별개 축).
    expect(framePrivacy).toHaveTextContent('익명 · 가명 · 개인정보 포함');
    expect(framePrivacy).toHaveTextContent('아니오 · 예 · 아니오');
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
    expect(videoPrivacy).toHaveTextContent('익명 · 가명 · 개인정보 포함');
    expect(videoPrivacy).toHaveTextContent('예 · 아니오 · 아니오');

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

  // ───────── 시안·사양 문구 (SCREEN-019 v50 · 2026-09-15) ─────────

  it('★촬영환경의_빈_값은_표식이_아니라_미입력이다', async () => {
    // given — 작업자가 아직 고르지 않은 영상.
    mockUseEnvironmentMeta.mockReturnValue({
      data: { weather: null, timeOfDay: null, season: null },
      isLoading: false,
    });

    renderPanel();

    // then — 세 칸 모두 「미입력」이다. 이 구역만 따로 정해진 문구라 다른 구역의 빈 값 문구와 다르다.
    const env = await screen.findByTestId('environment-meta-readonly');
    expect(env.textContent?.match(/미입력/g) ?? []).toHaveLength(3);
    // 표식 하나(—)로 되돌리면 「값이 없다」가 「못 그렸다」와 구분되지 않는다.
    expect(env).not.toHaveTextContent('—');
  });

  it('★프레임_설명은_현재_프레임_설명_라벨을_달고_빈_값_문구가_따로_있다', async () => {
    mockUseFrameDescription.mockReturnValue({ data: { description: null }, isLoading: false });

    renderPanel();

    const panel = await screen.findByTestId('frame-description-readonly');
    // 구 라벨 「설명」은 구역 제목과 겹쳐 무엇의 설명인지 라벨만으로 서지 않았다.
    expect(panel).toHaveTextContent('현재 프레임 설명');
    // 촬영환경의 「미입력」과 <b>다른 문구</b>다 — 사양이 구역마다 따로 정했다.
    expect(panel).toHaveTextContent('아직 채우지 않았습니다');
    expect(panel).not.toHaveTextContent('미입력');
  });

  it('★프레임_설명의_조달원_안내는_도움말을_켰을_때만_보인다', async () => {
    // ★이 구역만 설명이 <b>값 아래</b>에 있지만(제목 아래가 아니라) 노출 조건은 다른 구역과
    //   <b>같은 도움말 토글</b>이다(SCREEN-019). 자리가 다르다고 규칙까지 갈리지 않는다.
    //
    // ⚠ 구 판은 «도움말을 켜지 않은 기본 상태에서 이 문장이 보인다»를 단언했다 — 구현이 게이트를
    //   빠뜨린 상태를 시험이 <b>사양처럼 고정</b>하고 있었고, 그 시험 때문에 사양대로 고치면
    //   빨개지는 형태였다. 그래서 양방향으로 센다(한쪽만 두면 게이트가 통째로 죽어도 통과한다).
    mockUseFrameDescription.mockReturnValue({
      data: { description: '야간 보도에서 보행자 낙상' },
      isLoading: false,
    });

    renderPanel();

    // then — 기본(감춤)에서는 없다. 값 자체는 그대로 보인다.
    const panel = await screen.findByTestId('frame-description-readonly');
    expect(panel).toHaveTextContent('야간 보도에서 보행자 낙상');
    expect(panel).not.toHaveTextContent('학습데이터 산출물의 이미지 설명 조달원입니다.');

    // when — 패널 머리의 토글 하나가 이 줄까지 함께 편다.
    await userEvent.click(screen.getByTestId('meta-help-toggle'));

    // then
    expect(
      await screen.findByTestId('frame-description-readonly'),
    ).toHaveTextContent('학습데이터 산출물의 이미지 설명 조달원입니다.');
  });

  it('★개인정보_세_값이_모두_비면_한_줄에_표식을_늘어놓지_않고_빈_값_문구를_쓴다', async () => {
    // given — 판정 자체가 없는 프레임.
    mockUseVideoPrivacyMeta.mockReturnValue({ data: undefined, isLoading: false });

    renderPanel();

    const videoPrivacy = await screen.findByTestId('video-privacy-meta-readonly');
    expect(videoPrivacy).toHaveTextContent('아직 채우지 않았습니다');
    // 「— · — · —」로 채우면 «판정이 없다»가 «셋 다 미상이다»처럼 읽혀 잡음만 남는다.
    expect(videoPrivacy).not.toHaveTextContent('· —');
  });

  it('★일부만_빈_개인정보는_자리를_유지해_어느_항목이_빈지_알_수_있다', async () => {
    mockUseVideoPrivacyMeta.mockReturnValue({
      data: { anonymity: 'Y', pseudonymity: null, privacyIncluded: 'N' },
      isLoading: false,
    });

    renderPanel();

    // 빈 자리를 접어 「예 · 아니오」로 만들면 <b>가명</b>이 아니오인 것으로 읽힌다.
    const videoPrivacy = await screen.findByTestId('video-privacy-meta-readonly');
    expect(videoPrivacy).toHaveTextContent('예 · — · 아니오');
  });

  // ───────── 메타 조회 실패 (SCREEN-019 — 빈 상태와 합치지 않는다) ─────────

  it('★메타_조회가_실패하면_전용_안내가_뜨고_빈_상태_안내는_뜨지_않는다', async () => {
    // given — 조회 실패. data 가 없어 빈 상태 판정식은 «비었다»로 떨어진다.
    mockUseMeta.mockReturnValue({ data: undefined, isError: true });

    renderPanel();

    // then — 실패 안내가 뜬다(사양 문구 그대로).
    const alert = await screen.findByTestId('review-meta-error');
    expect(alert).toHaveTextContent('메타 정보를 불러오지 못했습니다. 새로고침 후 다시 시도해 주세요.');
    // ★그리고 빈 상태 안내는 <b>뜨지 않는다</b> — 못 불러온 것을 「값이 없다」고 말하면 거짓말이고,
    //   새로고침하면 되는 상황을 사용자가 값 없음으로 읽는다(사양이 두 안내를 합치지 말라고 못박는다).
    expect(screen.queryByTestId('review-meta-empty')).toBeNull();
  });

  it('★조회가_성공했는데_값만_없으면_실패_안내가_아니라_빈_상태_안내다', async () => {
    // 위 케이스의 짝 — 한쪽만 두면 실패 안내를 항상 띄우는 변이도 통과한다.
    setMeta();

    renderPanel();

    await waitFor(() => expect(screen.getByTestId('review-meta-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('review-meta-error')).toBeNull();
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
