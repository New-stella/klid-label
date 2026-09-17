// TimeseriesSidePanel — 「영상 분석 설명」 칸 단위 시험.
//
// 1. 편집 가능 키(vlm.description / manual-timeseries)만 입력 칸으로 렌더 — 저장 단위 = 편집 단위
// 2. 편집한 슬롯만 전송(조용한 무동작 회귀 가드)
// 3. 기술메타·읽기 전용 메타·이전 방식 구간 키는 편집 슬롯이 되지 않는다
// 4. ★이전 방식 구간 키에는 <b>대응하지 않는다</b> — 병기·접기·대체 표시를 두지 않는다(2026-09-14)
// 5. ★프레임을 넘겨 재조회돼도 편집 중인 값을 덮지 않는다
// 6. ★표기 — 이 칸에서는 「영상 분석 설명」이라 쓰고 저장 키를 라벨로 노출하지 않는다
// 7. 읽기 전용(검수) — 입력 칸·글자 수를 그리지 않고 검토 상태 배지도 두지 않는다

import { useRef } from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DESCRIPTION_META_KEY, MANUAL_TIMESERIES_META_KEY } from '@/features/auto/metaKeys';
import type { AnnotationColumnHandle } from '@/features/label/components/AnnotationColumn';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { TimeseriesSidePanel } from '../TimeseriesSidePanel';

// -- vi.hoisted 로 mock 함수 먼저 선언 --
// vi.mock / vi.hoisted 는 변환 단계에서 파일 최상단으로 끌어올려지므로, 이 블록이 import 문
// 아래에 있어도 위 import 가 실제로 평가되기 전에 적용된다(소스 순서는 동작에 영향이 없다).
const { mockMutateAsync, mockUseMeta, mockUseUpdateMeta } = vi.hoisted(() => ({
  mockMutateAsync: vi.fn(),
  mockUseMeta: vi.fn(),
  mockUseUpdateMeta: vi.fn(),
}));

vi.mock('@/features/auto/hooks/useMeta', () => ({
  useMeta: mockUseMeta,
}));
vi.mock('@/features/auto/hooks/useUpdateMeta', () => ({
  useUpdateMeta: mockUseUpdateMeta,
}));

/** 위탁 서술 전문 1건 영상(현행 표준). */
const defaultMetaData = {
  items: [{ metaSn: 1, metaKey: DESCRIPTION_META_KEY, metaVal: '초기 서술 전문' }],
  technicalMeta: [],
  readOnlyMeta: [],
  vlmText: '초기 서술 전문',
  stateChanges: [],
};

/** 구 산출물(이전 방식 구간 키 2건) — 편집 대상도 표시 대상도 아니다. */
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

/** 편집 입력 칸(슬롯이 하나일 때의 이름). */
function descriptionInput() {
  return screen.getByLabelText('영상 분석 설명 입력');
}

/**
 * 창을 대신하는 최소 껍데기 — 저장 손잡이를 누를 버튼 하나만 둔다.
 * 실제 화면에서 이 버튼은 창 아래 공통 「저장」이며 «바뀐 칸만» 부른다.
 */
function Harness({ srcSn, readOnly }: { srcSn: number | undefined; readOnly?: boolean }) {
  const ref = useRef<AnnotationColumnHandle>(null);
  return (
    <>
      <TimeseriesSidePanel ref={ref} srcSn={srcSn} readOnly={readOnly} />
      <button type="button" data-testid="harness-save" onClick={() => void ref.current?.save()}>
        저장
      </button>
    </>
  );
}

describe('TimeseriesSidePanel', () => {
  beforeEach(() => {
    mockMutateAsync.mockResolvedValue(undefined);
    mockUseMeta.mockReturnValue({ data: defaultMetaData, isLoading: false, error: null });
    mockUseUpdateMeta.mockReturnValue({ mutateAsync: mockMutateAsync, isPending: false });
    // 기본은 비인증(role 없음). REVIEWER 회귀 가드 케이스에서만 별도 설정.
    useAuthStore.setState({ token: null, claims: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ token: null, claims: null });
  });

  it('★칸_제목과_설명은_영상_분석_설명이다_시계열이라_쓰지_않는다', () => {
    renderWithProviders(<Harness srcSn={1} />);

    const column = screen.getByTestId('timeseries-column');
    expect(column).toHaveTextContent('영상 분석 설명');
    expect(column).toHaveTextContent('영상 전체');
    expect(column).toHaveTextContent(
      '외부 분석이 영상을 보고 장소·날씨·상황 등을 적은 설명입니다. 틀린 내용은 고칠 수 있습니다.',
    );
    // 「시계열」 계열 표기도, 내부 저장 키도 라벨로 노출하지 않는다.
    expect(column).not.toHaveTextContent('시계열');
    expect(column).not.toHaveTextContent(DESCRIPTION_META_KEY);
  });

  it('서술_전문은_편집가능한_입력칸으로_렌더된다', async () => {
    renderWithProviders(<Harness srcSn={1} />);

    await waitFor(() => expect(descriptionInput()).toHaveValue('초기 서술 전문'));
    expect(descriptionInput()).toBeEnabled();
    expect(descriptionInput()).not.toHaveAttribute('readonly');
    // 글자 수는 「{n} / 2000자」로 보인다.
    expect(screen.getByTestId('timeseries-column')).toHaveTextContent('8 / 2000자');
  });

  it('수정_후_저장하면_해당_키로_전송된다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness srcSn={1} />);

    const textarea = descriptionInput();
    await user.clear(textarea);
    await user.type(textarea, '검토 후 수정한 서술');
    await user.click(screen.getByTestId('harness-save'));

    // 원본 metaKey 보존 + 편집 텍스트 반영(조용한 무동작 아님)
    expect(mockMutateAsync).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '검토 후 수정한 서술' }],
    });
  });

  it('★고친_것이_없으면_저장을_보내지_않는다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness srcSn={1} />);
    await waitFor(() => expect(descriptionInput()).toHaveValue('초기 서술 전문'));

    await user.click(screen.getByTestId('harness-save'));

    expect(mockMutateAsync).not.toHaveBeenCalled();
  });

  it('메타가_0건이면_신규등록_슬롯이_보인다', async () => {
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<Harness srcSn={1} />);

    await user.type(descriptionInput(), '신규 설명');
    await user.click(screen.getByTestId('harness-save'));

    expect(mockMutateAsync).toHaveBeenCalledWith({
      items: [{ metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '신규 설명' }],
    });
  });

  it('공백만_남긴_편집은_전송하지_않는다', async () => {
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<Harness srcSn={1} />);

    await user.type(descriptionInput(), '   ');
    await user.click(screen.getByTestId('harness-save'));

    expect(mockMutateAsync).not.toHaveBeenCalled();
  });

  // ───────── 저장 단위 보존 회귀 가드 (2026-08-03) ─────────
  //
  // 구 구현은 여러 키의 값을 단일 입력 칸에 이어붙여 놓고 되돌릴 방법이 없어, 항목이 2건 이상이면
  // 편집 내용을 버리고 '원본 값을 그대로' 재전송했다(성공만 알리는 조용한 무동작).

  const TWO_SLOTS = {
    items: [
      { metaSn: 1, metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '작업자 작성분' },
      { metaSn: 2, metaKey: DESCRIPTION_META_KEY, metaVal: '자동 생성 서술' },
    ],
    technicalMeta: [],
    readOnlyMeta: [],
    vlmText: '',
    stateChanges: [],
  };

  it('편집슬롯_2건이면_각각_개별_입력칸으로_렌더된다', async () => {
    mockUseMeta.mockReturnValue({ data: TWO_SLOTS, isLoading: false, error: null });
    renderWithProviders(<Harness srcSn={1} />);

    // 하나로 뭉치지 않는다(저장 단위 보존). 이름은 순번으로 가른다 — 저장 키를 이름에 쓰지 않는다.
    await waitFor(() =>
      expect(screen.getByLabelText('영상 분석 설명 입력 1')).toHaveValue('작업자 작성분'),
    );
    expect(screen.getByLabelText('영상 분석 설명 입력 2')).toHaveValue('자동 생성 서술');
  });

  it('편집슬롯_2건일때_편집한_슬롯만_전송된다_조용한무동작_아님', async () => {
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({ data: TWO_SLOTS, isLoading: false, error: null });
    renderWithProviders(<Harness srcSn={1} />);

    const target = screen.getByTestId(`timeseries-input-${DESCRIPTION_META_KEY}`);
    await user.clear(target);
    await user.type(target, '검토 후 보정');
    await user.click(screen.getByTestId('harness-save'));

    expect(mockMutateAsync).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '검토 후 보정' }],
    });
  });

  it('기술메타_읽기전용메타_이전방식_구간키는_편집대상이_아니다', async () => {
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: {
        items: [
          { metaSn: 1, metaKey: DESCRIPTION_META_KEY, metaVal: '서술 전문' },
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
    renderWithProviders(<Harness srcSn={1} />);

    // 화면의 입력은 서술 전문 1개뿐이다.
    await waitFor(() => expect(descriptionInput()).toBeInTheDocument());
    expect(screen.getAllByRole('textbox')).toHaveLength(1);

    const target = descriptionInput();
    await user.clear(target);
    await user.type(target, '수정본');
    await user.click(screen.getByTestId('harness-save'));

    // BE 가 400 으로 거부하는 키(vlm.accuracy·video.*)와 이전 방식 구간 키는 절대 전송되지 않는다.
    expect(mockMutateAsync).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '수정본' }],
    });
  });

  // ───────── ★이전 방식 구간 키 미대응 (2026-09-14 사용자 확정) ─────────
  //
  // 구 동작은 그 값을 「이전 방식으로 생성된 구간별 정보」로 <b>읽기 전용 병기</b>했다(보존 확정).
  // 그 근거가 뒤집혔다 — 그 값은 분석 결과가 아니라 모의 응답 서버가 만든 값으로 판명됐고
  // 개발 서버에서 삭제됐다. 이제 표시·접기·대체 규칙을 두지 않는다.

  it('★이전방식_구간키는_화면에_나타나지_않는다_구_읽기전용_병기_폐기', async () => {
    mockUseMeta.mockReturnValue({ data: legacySegmentMeta, isLoading: false, error: null });
    renderWithProviders(<Harness srcSn={1} />);

    // 편집 가능한 항목이 없으므로 신규 등록 슬롯 1개만 선다.
    await waitFor(() => expect(descriptionInput()).toHaveValue(''));
    expect(screen.getAllByRole('textbox')).toHaveLength(1);
    // 구간 키도, 그 값도, 구 안내 문구도 없다.
    expect(screen.queryByTestId('timeseries-legacy-0-10')).not.toBeInTheDocument();
    expect(screen.queryByText('차량 3대 진입')).not.toBeInTheDocument();
    expect(screen.queryByText(/이전 방식으로 생성된 구간별 정보/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('이전 구간별 시계열 정보')).not.toBeInTheDocument();
  });

  it('이전방식_구간키만_있어도_신규등록_슬롯으로_전문을_작성할_수_있다', async () => {
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({ data: legacySegmentMeta, isLoading: false, error: null });
    renderWithProviders(<Harness srcSn={1} />);

    await user.type(descriptionInput(), '작업자가 작성한 전문');
    await user.click(screen.getByTestId('harness-save'));

    // 구간 키를 덮지 않고 표준 수동 키로 새로 등록된다.
    expect(mockMutateAsync).toHaveBeenCalledWith({
      items: [{ metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '작업자가 작성한 전문' }],
    });
  });

  // ───────── ★프레임 전환이 편집 값을 덮지 않는다 (2026-09-14) ─────────

  it('★★처음_가는_프레임으로_넘어가는_조회_공백에도_편집_중인_값이_남는다', async () => {
    // given — 조회 키가 프레임이라 <b>처음 가는 프레임</b>에서는 캐시가 없어 응답이 도착하기 전까지
    //   data 가 undefined 다. 구 구현은 그때 슬롯을 새로 만들어 편집 슬롯이 신규 등록 슬롯으로
    //   갈아끼워졌고, 그 결과 ①입력칸이 빠지고 ②바뀐 것이 없다고 판정돼 ③닫을 때 확인도 없이
    //   고친 내용이 사라졌다(조용한 유실).
    //
    // ⚠ 이 케이스가 필요한 이유 — 아래 「재조회돼도 덮지 않는다」는 목이 <b>항상 data 를 돌려줘</b>
    //   이 공백 구간을 한 번도 지나지 않는다. 같은 축으로 보이지만 실제로 검사하는 구간이 다르다.
    const user = userEvent.setup();
    const onDirtyChange = vi.fn();
    const { rerender } = renderWithProviders(
      <TimeseriesSidePanel srcSn={1} onDirtyChange={onDirtyChange} />,
    );
    await waitFor(() => expect(descriptionInput()).toHaveValue('초기 서술 전문'));
    await user.clear(descriptionInput());
    await user.type(descriptionInput(), '고치던 문장');
    await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(true));

    // when — 처음 가는 프레임으로 이동(응답 도착 전 = 조회 공백)
    mockUseMeta.mockReturnValue({ data: undefined, isLoading: true, error: null });
    rerender(<TimeseriesSidePanel srcSn={2} onDirtyChange={onDirtyChange} />);

    // then ⓐ — 고치던 입력칸이 그대로 있다(빈 신규 등록 슬롯으로 갈아끼워지지 않는다).
    expect(descriptionInput()).toHaveValue('고치던 문장');
    expect(
      screen.queryByTestId(`timeseries-segment-${MANUAL_TIMESERIES_META_KEY}`),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId(`timeseries-segment-${DESCRIPTION_META_KEY}`)).toBeInTheDocument();

    // then ⓑ — 「바뀐 것이 있다」가 유지된다. 여기서 false 로 떨어지면 저장 버튼이 잠기고
    //   제목 표시줄의 미저장 표시가 사라지며 닫기가 확인 없이 통과한다.
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);

    // then ⓒ — 공백이 끝나 응답이 도착해도 고치던 값이 살아 있다.
    mockUseMeta.mockReturnValue({
      data: { ...defaultMetaData, items: [...defaultMetaData.items] },
      isLoading: false,
      error: null,
    });
    rerender(<TimeseriesSidePanel srcSn={2} onDirtyChange={onDirtyChange} />);
    await waitFor(() => expect(descriptionInput()).toHaveValue('고치던 문장'));
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);
  });

  it('★조회_공백에_들어가도_저장할_내용이_그대로_전송된다', async () => {
    // 위 케이스의 «그래서 어떻게 되는가» 축 — 표시만 남고 전송이 비면 결과는 같다(유실).
    const user = userEvent.setup();
    const ref = { current: null as AnnotationColumnHandle | null };
    const { rerender } = renderWithProviders(<TimeseriesSidePanel ref={ref} srcSn={1} />);
    await waitFor(() => expect(descriptionInput()).toHaveValue('초기 서술 전문'));
    await user.clear(descriptionInput());
    await user.type(descriptionInput(), '공백 전에 고친 문장');

    mockUseMeta.mockReturnValue({ data: undefined, isLoading: true, error: null });
    rerender(<TimeseriesSidePanel ref={ref} srcSn={2} />);

    await ref.current?.save();

    expect(mockMutateAsync).toHaveBeenCalledWith({
      items: [{ metaKey: DESCRIPTION_META_KEY, metaVal: '공백 전에 고친 문장' }],
    });
  });

  it('★프레임을_넘겨_재조회돼도_편집_중인_값을_덮지_않는다', async () => {
    // given — 값은 영상 단위인데 조회는 프레임 식별자로 한다. 창을 열어 둔 채 프레임을 넘기면
    //   같은 내용이 다시 도착하는데, 그때 폼을 다시 채우면 고치던 문장이 서버값으로 되돌아간다.
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Harness srcSn={1} />);
    await waitFor(() => expect(descriptionInput()).toHaveValue('초기 서술 전문'));

    const textarea = descriptionInput();
    await user.clear(textarea);
    await user.type(textarea, '고치던 문장');

    // when — 프레임 전환(다른 srcSn 으로 재조회. 응답 객체도 새 신원으로 도착한다)
    mockUseMeta.mockReturnValue({
      data: { ...defaultMetaData, items: [...defaultMetaData.items] },
      isLoading: false,
      error: null,
    });
    rerender(<Harness srcSn={2} />);

    // then — 편집 중인 값이 그대로다.
    await waitFor(() => expect(descriptionInput()).toHaveValue('고치던 문장'));
  });

  // ───────── 읽기 전용(검수) ─────────

  it('★읽기전용이면_입력칸도_글자수도_그리지_않고_값만_보인다', async () => {
    renderWithProviders(<Harness srcSn={1} readOnly />);

    await waitFor(() =>
      expect(screen.getByTestId(`timeseries-readonly-${DESCRIPTION_META_KEY}`)).toHaveTextContent(
        '초기 서술 전문',
      ),
    );
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByTestId('timeseries-column')).not.toHaveTextContent('/ 2000자');
    // 검수 설명은 「고칠 수 있습니다」를 말하지 않는다.
    expect(screen.getByTestId('timeseries-column')).not.toHaveTextContent('고칠 수 있습니다');
  });

  it('★읽기전용에는_검토_상태_배지가_없다', async () => {
    // given — 검토행이 붙은 메타(REVIEWER 로 보아도 배지는 서지 않는다).
    useAuthStore.setState({
      token: ['t', 'o', 'k'].join(''),
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mockUseMeta.mockReturnValue({
      data: {
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
      },
      isLoading: false,
      error: null,
    });

    renderWithProviders(<Harness srcSn={1} readOnly />);

    await waitFor(() => expect(screen.getByText('자동 생성 서술')).toBeInTheDocument());
    expect(screen.queryByText('검토 상태')).not.toBeInTheDocument();
    expect(screen.queryByText('검토 대기')).not.toBeInTheDocument();
    expect(screen.queryByText('승인됨')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '승인' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '반려' })).not.toBeInTheDocument();
  });

  it('값이_비었으면_읽기전용은_미입력_표식을_보인다', async () => {
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<Harness srcSn={1} readOnly />);

    await waitFor(() =>
      expect(screen.getByTestId(`timeseries-readonly-${MANUAL_TIMESERIES_META_KEY}`)).toHaveTextContent(
        '아직 채우지 않았습니다',
      ),
    );
  });

  it('srcSn_undefined일때_빈_상태_렌더링', () => {
    mockUseMeta.mockReturnValue({ data: undefined, isLoading: false, error: null });

    renderWithProviders(<Harness srcSn={undefined} />);

    expect(screen.getByTestId('timeseries-column')).toHaveTextContent('영상 분석 설명');
    expect(descriptionInput()).toHaveValue('');
  });
});
