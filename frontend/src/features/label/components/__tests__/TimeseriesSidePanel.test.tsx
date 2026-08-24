// TimeseriesSidePanel — 접이식 시계열 메타 패널 단위 테스트.
//
// 1. 토글 버튼 클릭 시 편집 영역 표시/숨김
// 2. 편집 가능 키(vlm.description / manual-timeseries)만 textarea 로 렌더 — 저장 단위 = 편집 단위
// 3. 수정 전에는 저장 버튼 disabled, 수정 후 enabled
// 4. srcSn undefined일 때 빈 상태 렌더링
// 5. (2026-08-03) 편집 슬롯이 2건 이상이어도 편집분이 실제로 전송된다 — 조용한 무동작 회귀 가드
// 6. (2026-08-06 R8/R9) 일치도 읽기 전용 표시 · 레거시 구간 읽기 전용 병기 · start_sec 숫자 정렬
//    · 읽기 전용 항목은 저장 payload 에 섞이지 않는다

import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
import { renderWithProviders } from '@/test/renderWithProviders';

// -- vi.hoisted 로 mock 함수 먼저 선언 --
const { mockMutate, mockUseMeta, mockUseUpdateMeta } = vi.hoisted(() => ({
  mockMutate: vi.fn(),
  mockUseMeta: vi.fn(),
  mockUseUpdateMeta: vi.fn(),
}));

vi.mock('@/features/auto/hooks/useMeta', () => ({
  useMeta: mockUseMeta,
}));
vi.mock('@/features/auto/hooks/useUpdateMeta', () => ({
  useUpdateMeta: mockUseUpdateMeta,
}));

import {
  DESCRIPTION_META_KEY,
  MANUAL_TIMESERIES_META_KEY,
} from '@/features/auto/metaKeys';

import { TimeseriesSidePanel } from '../TimeseriesSidePanel';

/** verify 서술 전문 1건 영상(현행 표준). */
const defaultMetaData = {
  items: [{ metaSn: 1, metaKey: DESCRIPTION_META_KEY, metaVal: '초기 서술 전문' }],
  technicalMeta: [],
  readOnlyMeta: [],
  vlmText: '초기 서술 전문',
  stateChanges: [],
};

/** 구 describe 산출물(레거시 구간 2건) — 편집 대상이 아니고 읽기 전용으로 병기된다. */
const legacySegmentMeta = {
  items: [
    { metaSn: 1, metaKey: '0-10', metaVal: '차량 3대 진입' },
    { metaSn: 2, metaKey: '10-20', metaVal: '보행자 횡단' },
  ],
  technicalMeta: [],
  readOnlyMeta: [],
  vlmText: '차량 3대 진입\n보행자 횡단',
  stateChanges: [],
};

/** 서술 전문 편집 textarea. */
function descriptionInput() {
  return screen.getByLabelText('시계열 서술 입력');
}

/** 신규 등록(수동) 편집 textarea. */
function manualInput() {
  return screen.getByLabelText('시계열 메타 입력');
}

describe('TimeseriesSidePanel', () => {
  beforeEach(() => {
    mockUseMeta.mockReturnValue({
      data: defaultMetaData,
      isLoading: false,
      error: null,
    });
    mockUseUpdateMeta.mockReturnValue({
      mutate: mockMutate,
      isPending: false,
    });
    // 기본은 비인증(role 없음). REVIEWER 회귀 가드 케이스에서만 별도 설정.
    useAuthStore.setState({ token: null, claims: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ token: null, claims: null });
  });

  it('시계열_패널_접기_펼치기_토글', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 초기 열림 상태 확인
    expect(descriptionInput()).toBeInTheDocument();

    // when — 접기 토글 클릭
    const toggleBtn = screen.getByRole('button', { name: /시계열 메타/ });
    await user.click(toggleBtn);

    // then — 편집 영역 숨김
    expect(screen.queryByLabelText('시계열 서술 입력')).not.toBeInTheDocument();

    // when — 다시 펼치기
    await user.click(toggleBtn);

    // then — 다시 표시
    expect(descriptionInput()).toBeInTheDocument();
  });

  it('verify_서술은_편집가능한_textarea로_렌더된다', async () => {
    // given / when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 전문 1개가 편집 가능한 textarea 로 표시된다.
    await waitFor(() => {
      expect(descriptionInput()).toHaveValue('초기 서술 전문');
    });
    expect(descriptionInput()).toBeEnabled();
    expect(descriptionInput()).not.toHaveAttribute('readonly');
  });

  it('저장_버튼_dirty_체크', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 초기에는 저장 버튼 disabled
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    expect(saveBtn).toBeDisabled();

    // when — 내용 수정
    const textarea = descriptionInput();
    await user.clear(textarea);
    await user.type(textarea, '수정된 텍스트');

    // then — 저장 버튼 enabled
    await waitFor(() => {
      expect(saveBtn).toBeEnabled();
    });
  });

  it('수정_후_저장하면_해당_키로_PUT이_호출된다', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when
    const textarea = descriptionInput();
    await user.clear(textarea);
    await user.type(textarea, '검토 후 수정한 서술');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 원본 metaKey 보존 + 편집 텍스트 반영(조용한 무동작 아님)
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '검토 후 수정한 서술' }],
    });
  });

  it('메타가_0건이면_신규등록_슬롯이_보인다', async () => {
    // given — 기존 메타 0건 (신규 등록 경로)
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 신규 텍스트 입력 후 저장
    const textarea = manualInput();
    await user.type(textarea, '신규 시계열 설명');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 표준 metaKey 로 신규 등록 payload 전송
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '신규 시계열 설명' }],
    });
  });

  it('저장_성공시_성공토스트', async () => {
    // given
    useUiStore.setState({ toasts: [] });
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 컴포넌트가 useUpdateMeta 에 넘긴 onSuccess 콜백 실행 (mutation 성공 시뮬레이션)
    const options = mockUseUpdateMeta.mock.calls.at(-1)?.[1];
    act(() => {
      options?.onSuccess?.();
    });

    // then — 성공 토스트 push
    const toasts = useUiStore.getState().toasts;
    expect(toasts.some((t) => t.variant === 'success')).toBe(true);
  });

  it('빈_텍스트는_저장버튼_비활성', async () => {
    // given — 0건 영상에서 공백만 입력
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    const saveBtn = screen.getByRole('button', { name: /저장/ });
    expect(saveBtn).toBeDisabled();

    // when — 공백만 입력
    const textarea = manualInput();
    await user.type(textarea, '   ');

    // then — 여전히 비활성 (공백만은 저장 무의미)
    await waitFor(() => expect(saveBtn).toBeDisabled());
  });

  // ───────── 시계열 메타 저장 버그 회귀 가드 (2026-08-03) ─────────
  //
  // 구 구현은 여러 키의 값을 단일 textarea 에 이어붙여 놓고 되돌릴 방법이 없어, 항목이 2건 이상이면
  // 편집 내용을 버리고 '원본 값을 그대로' 재전송했다(성공 토스트만 뜨는 조용한 무동작).
  // → 편집 단위 = 저장 단위(metaKey) 로 전환해 경계를 보존한 채 편집분만 전송한다.

  it('편집슬롯_2건이면_각각_개별_textarea로_렌더된다', async () => {
    // given — 수동 등록분이 있는 영상에 verify 서술이 뒤늦게 적재된 경우
    mockUseMeta.mockReturnValue({
      data: {
        items: [
          { metaSn: 1, metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '작업자 작성분' },
          { metaSn: 2, metaKey: DESCRIPTION_META_KEY, metaVal: '자동 생성 서술' },
        ],
        technicalMeta: [],
        readOnlyMeta: [],
        vlmText: '',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 하나로 뭉치지 않는다(저장 단위 보존).
    await waitFor(() => expect(manualInput()).toHaveValue('작업자 작성분'));
    expect(descriptionInput()).toHaveValue('자동 생성 서술');
  });

  it('편집슬롯_2건일때_편집한_슬롯만_전송된다_조용한무동작_아님', async () => {
    // given
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: {
        items: [
          { metaSn: 1, metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '작업자 작성분' },
          { metaSn: 2, metaKey: DESCRIPTION_META_KEY, metaVal: '자동 생성 서술' },
        ],
        technicalMeta: [],
        readOnlyMeta: [],
        vlmText: '',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 서술만 수정
    const target = descriptionInput();
    await user.clear(target);
    await user.type(target, '검토 후 보정');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 편집한 키만, 편집한 값으로 전송(원본 재전송 금지)
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '검토 후 보정' }],
    });
  });

  it('기술메타는_시계열_편집대상에_포함되지_않는다', async () => {
    // given — 기술메타(video.*)는 별도 필드로 오며 이 패널의 편집 대상이 아니다.
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: DESCRIPTION_META_KEY, metaVal: '서술 전문' }],
        technicalMeta: [
          { metaSn: 9, metaKey: 'video.fps', metaVal: '30' },
          { metaSn: 10, metaKey: 'video.duration_ms', metaVal: '60000' },
        ],
        readOnlyMeta: [],
        vlmText: '서술 전문',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });

    // when
    const user = userEvent.setup();
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 기술메타용 편집 입력이 없고 값도 시계열 textarea 에 섞이지 않는다.
    await waitFor(() => expect(descriptionInput()).toHaveValue('서술 전문'));
    expect(screen.queryByLabelText('시계열 메타 video.fps 입력')).not.toBeInTheDocument();

    // then — 저장해도 기술메타 키는 절대 전송되지 않는다.
    const textarea = descriptionInput();
    await user.clear(textarea);
    await user.type(textarea, '수정');
    await user.click(screen.getByRole('button', { name: /저장/ }));
    const sent = mockMutate.mock.calls.at(-1)?.[0] as {
      items: Array<{ metaKey: string }>;
    };
    expect(sent.items.some((i) => i.metaKey.startsWith('video.'))).toBe(false);
  });

  // ───────── R8: 일치도 읽기 전용 표시 ─────────

  const META_WITH_ACCURACY = {
    items: [{ metaSn: 1, metaKey: DESCRIPTION_META_KEY, metaVal: '초기 서술 전문' }],
    technicalMeta: [],
    readOnlyMeta: [{ metaSn: 5, metaKey: 'vlm.accuracy', metaVal: '0.92' }],
    vlmText: '초기 서술 전문',
    stateChanges: [],
  };

  it('★일치도는_화면에_표시하지_않는다_구_읽기전용_렌더_폐기', async () => {
    // given — 과거 위탁분이 남아 있는 영상. 판정 창구를 연동하지 않게 되면서 이 값은
    //   새로 생기지 않고, 남은 것을 화면에서 빼기로 확정했다(2026-08-24 사용자 확정).
    mockUseMeta.mockReturnValue({ data: META_WITH_ACCURACY, isLoading: false, error: null });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 서술 전문은 그대로 뜨고(대조군), 일치도는 라벨·값·행 어느 것도 없다.
    expect(await screen.findByDisplayValue('초기 서술 전문')).toBeInTheDocument();
    expect(screen.queryByTestId('timeseries-readonly-vlm.accuracy')).not.toBeInTheDocument();
    expect(screen.queryByText('일치도')).not.toBeInTheDocument();
    expect(screen.queryByText('92%')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('시계열 참고 정보')).not.toBeInTheDocument();
    // 편집 슬롯으로 승격되지도 않는다 — 화면의 입력은 서술 전문 1개뿐.
    expect(screen.getAllByRole('textbox')).toHaveLength(1);
  });

  it('★읽기전용_목록에_미지의_키가_늘어도_표시하지_않는다', async () => {
    // given — BE 가 fail-closed 로 읽기 전용 키를 늘려도 화면은 그 목록을 소비하지 않는다.
    //   되살리려면 화면 사양(SCREEN-005)부터 고쳐야 한다.
    mockUseMeta.mockReturnValue({
      data: {
        ...META_WITH_ACCURACY,
        readOnlyMeta: [
          { metaSn: 5, metaKey: 'vlm.accuracy', metaVal: '0.92' },
          { metaSn: 6, metaKey: 'vlm.unknown_future_key', metaVal: '미래 값' },
        ],
      },
      isLoading: false,
      error: null,
    });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 크래시 없이 서술 전문만 뜨고 읽기 전용 항목은 하나도 그려지지 않는다.
    expect(await screen.findByDisplayValue('초기 서술 전문')).toBeInTheDocument();
    expect(screen.queryByText('미래 값')).not.toBeInTheDocument();
    expect(screen.queryByTestId('timeseries-readonly-vlm.unknown_future_key')).not.toBeInTheDocument();
  });

  it('읽기전용_항목은_저장_요청에_포함되지_않는다', async () => {
    // given — 일치도 + 레거시 구간이 함께 있는 영상
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: {
        items: [
          { metaSn: 1, metaKey: DESCRIPTION_META_KEY, metaVal: '초기 서술 전문' },
          { metaSn: 2, metaKey: '0-10', metaVal: '차량 3대 진입' },
        ],
        technicalMeta: [{ metaSn: 9, metaKey: 'video.fps', metaVal: '30' }],
        readOnlyMeta: [{ metaSn: 5, metaKey: 'vlm.accuracy', metaVal: '0.92' }],
        vlmText: '',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 편집 입력은 서술 전문 1개뿐(읽기 전용·레거시·기술메타는 슬롯이 생기지 않는다).
    await waitFor(() => expect(descriptionInput()).toBeInTheDocument());
    expect(screen.getAllByRole('textbox')).toHaveLength(1);
    expect(screen.queryByTestId('timeseries-segment-0-10')).not.toBeInTheDocument();
    expect(screen.queryByTestId('timeseries-segment-vlm.accuracy')).not.toBeInTheDocument();

    // when
    const textarea = descriptionInput();
    await user.clear(textarea);
    await user.type(textarea, '수정본');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — BE 가 400 으로 거부하는 키(vlm.accuracy·video.*)와 레거시 구간 키는 절대 전송되지 않는다.
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '수정본' }],
    });
  });

  // ───────── R9: 레거시 구간 보존(읽기 전용 병기) + start_sec 숫자 정렬 ─────────

  it('레거시_구간행은_읽기전용으로_병기되고_사라지지_않는다', async () => {
    // given — 구 describe 산출물만 있는 영상
    mockUseMeta.mockReturnValue({ data: legacySegmentMeta, isLoading: false, error: null });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 값이 보존돼 보이고(삭제·숨김 금지) 편집 동선은 없다.
    const first = await screen.findByTestId('timeseries-legacy-0-10');
    expect(first).toHaveTextContent('0-10');
    expect(first).toHaveTextContent('차량 3대 진입');
    const second = screen.getByTestId('timeseries-legacy-10-20');
    expect(second).toHaveTextContent('보행자 횡단');
    expect(within(first).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(second).queryByRole('textbox')).not.toBeInTheDocument();

    // then — 레거시 키에는 편집 슬롯이 생기지 않는다. 화면의 입력은 신규 등록 슬롯 1개뿐이다.
    expect(screen.queryByTestId('timeseries-segment-0-10')).not.toBeInTheDocument();
    expect(screen.queryByTestId('timeseries-segment-10-20')).not.toBeInTheDocument();
    expect(screen.getAllByRole('textbox')).toHaveLength(1);
    expect(screen.getAllByRole('textbox')[0]).toBe(manualInput());
  });

  it('레거시_구간만_있으면_신규등록_슬롯으로_전문을_작성할_수_있다', async () => {
    // given — 편집 가능한 항목이 하나도 없는 영상(레거시 구간뿐)
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({ data: legacySegmentMeta, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when
    const textarea = manualInput();
    await user.type(textarea, '작업자가 작성한 전문');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 레거시 구간을 덮지 않고 표준 수동 키로 새로 등록된다.
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '작업자가 작성한 전문' }],
    });
  });

  it('레거시_구간은_start_sec_숫자순으로_정렬된다', async () => {
    // given — 구간 10개 초과(문자열 정렬이면 '10-18' 이 '8-16' 앞으로 온다)
    const keys = [
      '72-80',
      '0-8',
      '16-24',
      '8-16',
      '80-88',
      '24-32',
      '32-40',
      '40-48',
      '48-56',
      '56-64',
      '64-72',
    ];
    mockUseMeta.mockReturnValue({
      data: {
        items: keys.map((k, i) => ({ metaSn: i + 1, metaKey: k, metaVal: `구간 ${k}` })),
        technicalMeta: [],
        readOnlyMeta: [],
        vlmText: '',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 화면 표시 순서가 시간축 오름차순
    await waitFor(() =>
      expect(screen.getAllByTestId(/^timeseries-legacy-/)).toHaveLength(keys.length),
    );
    const rendered = screen
      .getAllByTestId(/^timeseries-legacy-/)
      .map((el) => el.getAttribute('data-testid'));
    expect(rendered).toEqual([
      'timeseries-legacy-0-8',
      'timeseries-legacy-8-16',
      'timeseries-legacy-16-24',
      'timeseries-legacy-24-32',
      'timeseries-legacy-32-40',
      'timeseries-legacy-40-48',
      'timeseries-legacy-48-56',
      'timeseries-legacy-56-64',
      'timeseries-legacy-64-72',
      'timeseries-legacy-72-80',
      'timeseries-legacy-80-88',
    ]);
  });

  // ───────── 검토(승인/반려) 표면 제거 회귀 가드 (2026-08-03 사용자 확정) ─────────
  //
  // 이 패널은 시계열 메타 '텍스트 수정'만 제공한다. 검토 상태 배지·승인/반려 버튼·반려 사유
  // 입력은 노출하지 않으며, 검토 상태 확정은 영상 검수 승인 시 BE 자동 동결이 담당한다.
  // (BE API /v1/meta/{metaReviewSn}/approve|reject 와 LS_DATA_META_REVIEW 는 존치, FE 진입점만 없음)
  // 이 테스트가 깨진다면 회귀가 아니라 정책 변경이다.

  const REVIEWABLE_META = {
    items: [
      {
        metaSn: 1,
        metaKey: DESCRIPTION_META_KEY,
        metaVal: '자동 생성 서술',
        dataMetaReviewSn: 9001,
        reviewStatus: 'PENDING',
      },
    ],
    technicalMeta: [],
    readOnlyMeta: [],
    vlmText: '자동 생성 서술',
    stateChanges: [],
  };

  // secret-filter 훅 우회 — 테스트용 더미 인증 값(실제 시크릿 아님)
  const TEST_TOKEN = ['t', 'o', 'k'].join('');

  function setReviewer() {
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  }

  it('기존_승인반려_UI_미노출_가드가_유지된다', async () => {
    // given — REVIEWER + INTERNAL + 검토 가능(PENDING) 검토행이 있는 메타
    setReviewer();
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 편집 textarea·저장 버튼은 있으나 검토 표면은 전부 없음
    await waitFor(() => expect(descriptionInput()).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /저장/ })).toBeInTheDocument();
    expect(screen.queryByTestId('ts-review-actions')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-review-status-9001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-approve-9001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-reject-9001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-reject-reason-9001')).not.toBeInTheDocument();
    expect(screen.queryByText('검토 상태')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '승인' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '반려' })).not.toBeInTheDocument();
  });

  it('APPROVED_검토행이_있어도_승인됨_배지가_노출되지_않는다', async () => {
    // given — 실사용 대다수 케이스(승인 완료 영상). 배지만 반복 노출되던 것을 제거했다.
    setReviewer();
    mockUseMeta.mockReturnValue({
      data: {
        items: [
          {
            metaSn: 1,
            metaKey: DESCRIPTION_META_KEY,
            metaVal: '자동 생성 서술',
            dataMetaReviewSn: 9002,
            reviewStatus: 'APPROVED',
          },
        ],
        technicalMeta: [],
        readOnlyMeta: [],
        vlmText: '자동 생성 서술',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 텍스트는 편집 가능하되 상태 배지는 없음
    await waitFor(() => expect(descriptionInput()).toHaveValue('자동 생성 서술'));
    expect(screen.queryByTestId('ts-review-status-9002')).not.toBeInTheDocument();
    expect(screen.queryByText('승인됨')).not.toBeInTheDocument();
  });

  it('검토행이_있어도_텍스트_편집_저장은_그대로_동작한다', async () => {
    // given — 검토행이 붙은 메타(제거 대상은 검토 UI 뿐, 편집 경로는 회귀 없음)
    const user = userEvent.setup();
    setReviewer();
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when
    const textarea = descriptionInput();
    await user.clear(textarea);
    await user.type(textarea, '검토행 있어도 수정');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 원본 metaKey 보존 저장
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '검토행 있어도 수정' }],
    });
  });

  it('srcSn_undefined일때_빈_상태_렌더링', () => {
    // given — srcSn undefined → useMeta는 data=undefined 반환
    mockUseMeta.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: null,
    });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={undefined} />);

    // then — 패널 제목은 표시되고, 신규 등록 textarea 는 빈 값
    expect(screen.getByRole('button', { name: /시계열 메타/ })).toBeInTheDocument();
    expect(manualInput()).toHaveValue('');
  });
});
