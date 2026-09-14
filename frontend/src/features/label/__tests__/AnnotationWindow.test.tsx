// 「영상 분석 설명 · 이벤트 어노테이션」 창 — 저장 합침·미저장 닫기·도움말·표기.
// [@design UI-156] [@design UI-107] [@design UI-056] [@design SCREEN-005]
//
// 이 파일이 고정하는 것:
//  ① 저장은 <b>바뀐 칸만</b> 보낸다(한쪽만 · 둘 다 · 아무것도 안 바뀜)
//  ② 한쪽만 실패하면 그 칸에 「저장 실패」가 서고 성공한 칸의 「저장 안 됨」은 풀린다
//  ③ 미저장으로 닫으면 세 갈래 확인을 거친다(저장하고 닫기 · 저장하지 않고 닫기 · 계속 작성)
//  ④ 도움말 숨김 선택을 브라우저가 기억한다
//  ⑤ 이 창의 표기는 「영상 분석 설명」이다(「시계열」이라 쓰지 않는다)

import { useState } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

const {
  mockUseMeta,
  mockUseUpdateMeta,
  mockUseEventAnnotation,
  mockUseUpdateEventAnnotation,
  mockUseEventAnnotationReview,
  tsSave,
  eaSave,
} = vi.hoisted(() => ({
  mockUseMeta: vi.fn(),
  mockUseUpdateMeta: vi.fn(),
  mockUseEventAnnotation: vi.fn(),
  mockUseUpdateEventAnnotation: vi.fn(),
  mockUseEventAnnotationReview: vi.fn(),
  tsSave: vi.fn(),
  eaSave: vi.fn(),
}));

vi.mock('@/features/auto/hooks/useMeta', () => ({ useMeta: mockUseMeta }));
vi.mock('@/features/auto/hooks/useUpdateMeta', () => ({ useUpdateMeta: mockUseUpdateMeta }));
vi.mock('@/features/label/hooks/useEventAnnotation', () => ({
  useEventAnnotation: mockUseEventAnnotation,
}));
vi.mock('@/features/label/hooks/useUpdateEventAnnotation', () => ({
  useUpdateEventAnnotation: mockUseUpdateEventAnnotation,
}));
vi.mock('@/features/label/hooks/useEventAnnotationReview', () => ({
  useEventAnnotationReview: mockUseEventAnnotationReview,
}));

import { ANNOTATION_HELP_STORAGE_KEY } from '../components/annotationHelpPreference';
import { AnnotationWindow } from '../components/AnnotationWindow';
import { useAnnotationWindow } from '../hooks/useAnnotationWindow';

const RAW_SN = 7;
const SRC_SN = 300;
const POSITION_KEY = 'klid.annotationWindow.rect';

/** 창을 여는 화면 껍데기 — 상태는 실제 화면과 같이 <b>화면</b>이 든다. */
function Host({ mode = 'editable' as 'editable' | 'readOnly' }) {
  const w = useAnnotationWindow();
  // 창이 떠 있는 동안의 <b>프레임 이동</b>. 비모달이라 뒤 화면을 조작할 수 있는 것이 사양이고,
  // 근거 「화면에서 지정」이 이 이동을 주 동선으로 쓴다.
  const [srcSn, setSrcSn] = useState(SRC_SN);
  return (
    <>
      <button type="button" data-testid="open" onClick={w.openOrFocus}>
        {w.state === 'closed' ? '크게 보기 · 작성' : '창 앞으로 가져오기'}
      </button>
      <button type="button" data-testid="next-frame" onClick={() => setSrcSn((n) => n + 1)}>
        다음 프레임
      </button>
      <span data-testid="window-state">{w.state}</span>
      {w.mounted && (
        <AnnotationWindow
          mode={mode}
          rawSn={RAW_SN}
          srcSn={srcSn}
          state={w.state}
          focusRequestedAt={w.focusRequestedAt}
          onClose={w.close}
          onFold={w.fold}
          onExpand={w.expand}
          onPickingChange={w.setPicking}
          eventTypes={[{ vrfcEvntTypeCd: 'car_accident', vrfcEvntTypeNm: '교통사고' }]}
          frameIndex={1}
          frameTotal={6}
        />
      )}
    </>
  );
}

/** 창을 연 상태로 시작한다. */
async function openWindow(mode: 'editable' | 'readOnly' = 'editable') {
  const user = userEvent.setup();
  renderWithProviders(<Host mode={mode} />);
  await user.click(screen.getByTestId('open'));
  await screen.findByTestId('annotation-window');
  return user;
}

function timeseriesInput() {
  return screen.getByLabelText('영상 분석 설명 입력');
}

describe('영상 분석 설명 · 이벤트 어노테이션 창', () => {
  beforeEach(() => {
    localStorage.removeItem(ANNOTATION_HELP_STORAGE_KEY);
    localStorage.removeItem(POSITION_KEY);
    useUiStore.setState({ toasts: [] });
    tsSave.mockResolvedValue(undefined);
    eaSave.mockResolvedValue(undefined);
    mockUseMeta.mockReturnValue({
      data: {
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '원본 서술' }],
        technicalMeta: [],
        readOnlyMeta: [],
        importedMeta: [],
      },
      isLoading: false,
      error: null,
    });
    mockUseUpdateMeta.mockReturnValue({ mutateAsync: tsSave, isPending: false });
    mockUseEventAnnotation.mockReturnValue({
      data: {
        rawSn: RAW_SN,
        evntAnnoSn: 1,
        reviewStatus: null,
        regId: null,
        mdfcnId: null,
        payload: { event_class: 'car_accident' },
      },
    });
    mockUseUpdateEventAnnotation.mockReturnValue({
      mutateAsync: eaSave,
      isPending: false,
      isError: false,
      error: null,
    });
    mockUseEventAnnotationReview.mockReturnValue({
      approve: { mutate: vi.fn(), isPending: false },
      reject: { mutate: vi.fn(), isPending: false },
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    localStorage.removeItem(ANNOTATION_HELP_STORAGE_KEY);
    localStorage.removeItem(POSITION_KEY);
  });

  it('★창_제목과_부제_두_칸이_함께_뜬다_표기는_영상_분석_설명이다', async () => {
    await openWindow();
    const win = screen.getByTestId('annotation-window');

    expect(win).toHaveAttribute('aria-label', '영상 분석 설명 · 이벤트 어노테이션');
    expect(win).toHaveTextContent(
      '외부 분석 설명과 사건 판단 정보를 크게 보고 고치는 창 · 영상 전체에 해당하므로 프레임을 넘겨도 같습니다',
    );
    expect(screen.getByTestId('timeseries-column')).toBeInTheDocument();
    expect(screen.getByTestId('annotation-column')).toBeInTheDocument();
    // 「시계열」 계열 표기는 이 창에 없다.
    expect(win).not.toHaveTextContent('시계열');
  });

  it('고친_것이_없으면_저장_바가_그_사실을_말하고_저장이_잠긴다', async () => {
    await openWindow();

    expect(screen.getByTestId('annotation-window-save-bar')).toHaveTextContent(
      '고친 내용이 없습니다.',
    );
    expect(screen.getByTestId('annotation-window-save')).toBeDisabled();
  });

  it('★한쪽만_고치면_그_칸만_저장한다', async () => {
    const user = await openWindow();

    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');

    expect(screen.getByTestId('annotation-window-save-bar')).toHaveTextContent(
      '바뀐 칸만 저장합니다 — 영상 분석 설명',
    );
    await user.click(screen.getByTestId('annotation-window-save'));

    await waitFor(() =>
      expect(tsSave).toHaveBeenCalledWith({
        items: [{ metaKey: 'vlm.description', metaVal: '고친 서술' }],
      }),
    );
    // 이벤트 어노테이션은 손대지 않았으므로 보내지 않는다(남의 수정을 같은 값으로 덮지 않는다).
    expect(eaSave).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(useUiStore.getState().toasts.some((t) => t.message === '저장했습니다.')).toBe(true),
    );
  });

  it('★둘_다_고치면_둘_다_저장한다', async () => {
    const user = await openWindow();

    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');
    await user.type(screen.getByTestId('ea-answer'), '사람이 넘어졌다');

    expect(screen.getByTestId('annotation-window-save-bar')).toHaveTextContent(
      '바뀐 칸만 저장합니다 — 영상 분석 설명 · 이벤트 어노테이션',
    );
    await user.click(screen.getByTestId('annotation-window-save'));

    await waitFor(() => expect(tsSave).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(eaSave).toHaveBeenCalledTimes(1));
    expect(eaSave).toHaveBeenCalledWith({
      event_class: 'car_accident',
      answer: '사람이 넘어졌다',
    });
  });

  it('★한쪽만_실패하면_그_칸에_저장_실패가_서고_성공한_칸의_저장_안됨은_풀린다', async () => {
    eaSave.mockRejectedValue(new Error('boom'));
    const user = await openWindow();

    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');
    await user.type(screen.getByTestId('ea-answer'), '사람이 넘어졌다');

    // 저장 전 — 두 칸 모두 「저장 안 됨」
    expect(screen.getByTestId('timeseries-column-dirty')).toBeInTheDocument();
    expect(screen.getByTestId('annotation-column-dirty')).toBeInTheDocument();

    await user.click(screen.getByTestId('annotation-window-save'));

    // then — 실패한 칸에만 「저장 실패」, 성공한 칸의 「저장 안 됨」은 사라진다.
    await waitFor(() =>
      expect(screen.getByTestId('annotation-column-failed')).toHaveTextContent('저장 실패'),
    );
    expect(screen.queryByTestId('timeseries-column-dirty')).toBeNull();
    expect(screen.queryByTestId('timeseries-column-failed')).toBeNull();
    // then — 「저장했습니다」로 뭉뚱그리지 않는다(실패한 칸이 저장된 줄로 읽힌다).
    await waitFor(() =>
      expect(
        useUiStore
          .getState()
          .toasts.some((t) => t.message === '일부만 저장했습니다 — 실패한 칸을 확인해 주세요.'),
      ).toBe(true),
    );
    // then — 아직 저장되지 않은 칸이 있으므로 저장 바는 그 칸을 계속 가리킨다.
    expect(screen.getByTestId('annotation-window-save-bar')).toHaveTextContent(
      '바뀐 칸만 저장합니다 — 이벤트 어노테이션',
    );
  });

  it('고친_것이_없으면_닫기는_확인_없이_바로_닫힌다', async () => {
    const user = await openWindow();

    await user.click(screen.getByTestId('annotation-window-close-button'));

    expect(screen.queryByTestId('annotation-window')).toBeNull();
    expect(screen.getByTestId('window-state')).toHaveTextContent('closed');
  });

  it('★미저장으로_닫으면_세_갈래_확인을_거친다_계속_작성이면_닫히지_않는다', async () => {
    const user = await openWindow();
    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');

    await user.click(screen.getByTestId('annotation-window-close-button'));

    const dialog = await screen.findByRole('dialog', { name: '저장하지 않은 변경이 있습니다' });
    expect(dialog).toHaveTextContent('닫으면 고친 내용이 사라집니다. 바뀐 칸: 영상 분석 설명');
    expect(within(dialog).getByRole('button', { name: '저장하고 닫기' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '저장하지 않고 닫기' })).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: '계속 작성' }));

    expect(screen.getByTestId('annotation-window')).toBeInTheDocument();
    expect(timeseriesInput()).toHaveValue('고친 서술');
    expect(tsSave).not.toHaveBeenCalled();
  });

  it('★★조회_공백_중에_닫아도_확인을_거친다_조용한_유실_차단', async () => {
    // given — 창을 열어 둔 채 <b>처음 가는 프레임</b>으로 넘어가면 그 사이 메타 조회가 undefined 다.
    //   구 구현은 그때 편집 슬롯이 통째로 갈아끼워져 「바뀐 것이 없다」가 되었고, 그래서 닫기가
    //   확인 없이 통과해 고친 내용이 <b>조용히</b> 사라졌다(§2-12 위반).
    // ⚠ 창은 비모달이라 프레임을 넘기며 열어 두는 것이 사양이고, 근거 「화면에서 지정」은 프레임
    //   이동이 주 동선이다 — 예외 경로가 아니다.
    const user = await openWindow();
    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고치던 문장');
    expect(screen.getByTestId('annotation-window-save')).toBeEnabled();

    // when — 처음 가는 프레임으로 넘어가 조회 공백에 들어간다.
    mockUseMeta.mockReturnValue({ data: undefined, isLoading: true, error: null });
    await user.click(screen.getByTestId('next-frame'));

    // then — 미저장 표시·저장 버튼이 살아 있다.
    expect(screen.getByTestId('floating-window-dirty')).toHaveTextContent('저장 안 된 변경');
    expect(screen.getByTestId('annotation-window-save')).toBeEnabled();
    expect(screen.getByTestId('annotation-window-save-bar')).toHaveTextContent(
      '바뀐 칸만 저장합니다 — 영상 분석 설명',
    );

    // then — 닫기를 눌러도 바로 닫히지 않고 확인을 거친다.
    await user.click(screen.getByTestId('annotation-window-close-button'));
    expect(
      await screen.findByRole('dialog', { name: '저장하지 않은 변경이 있습니다' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('annotation-window')).toBeInTheDocument();
  });

  it('★저장하지_않고_닫기는_보내지_않고_닫는다', async () => {
    const user = await openWindow();
    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');

    await user.click(screen.getByTestId('annotation-window-close-button'));
    await user.click(await screen.findByTestId('annotation-window-discard-close'));

    expect(screen.queryByTestId('annotation-window')).toBeNull();
    expect(tsSave).not.toHaveBeenCalled();
  });

  it('★저장하고_닫기는_저장에_성공해야_닫는다', async () => {
    const user = await openWindow();
    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');

    await user.click(screen.getByTestId('annotation-window-close-button'));
    await user.click(await screen.findByTestId('annotation-window-save-close'));

    await waitFor(() => expect(tsSave).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByTestId('annotation-window')).toBeNull());
  });

  it('★저장에_실패하면_닫지_않는다_고친_내용이_사라지지_않게', async () => {
    tsSave.mockRejectedValue(new Error('boom'));
    const user = await openWindow();
    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');

    await user.click(screen.getByTestId('annotation-window-close-button'));
    await user.click(await screen.findByTestId('annotation-window-save-close'));

    await waitFor(() => expect(tsSave).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId('annotation-window')).toBeInTheDocument();
    expect(timeseriesInput()).toHaveValue('고친 서술');
  });

  it('★도움말은_기본으로_보이고_숨긴_선택을_브라우저가_기억한다', async () => {
    const user = await openWindow();

    // 기본은 표시 — 처음 쓰는 사람이 무엇을 적는 칸인지 알아야 한다.
    expect(screen.getByText('이 영상에서 확인하려는 사건의 종류입니다.')).toBeInTheDocument();
    // ★글자 없이 물음표 아이콘만 두는 버튼이라, 이름은 텍스트가 아니라 접근성 이름으로 있다.
    //   지금 도움말이 보이는 상태인지는 aria-pressed 가 함께 말한다.
    const toggle = screen.getByRole('button', { name: '도움말 숨기기' });
    expect(toggle).toHaveAttribute('data-testid', 'annotation-window-help-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    // 글자를 지우고 아이콘만 남긴 것이 사양이다 — 버튼 안에 문구가 남아 있으면 그 사양이 아니다.
    expect(toggle).not.toHaveTextContent('도움말');

    await user.click(toggle);

    expect(screen.queryByText('이 영상에서 확인하려는 사건의 종류입니다.')).toBeNull();
    expect(screen.getByRole('button', { name: '도움말 보기' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    // 라벨은 남는다 — 숨기는 것은 도움말이지 칸 이름이 아니다.
    expect(screen.getByText('이벤트 분류')).toBeInTheDocument();

    // 창을 닫았다 다시 열어도 숨김이 유지된다.
    await user.click(screen.getByTestId('annotation-window-close-button'));
    await user.click(screen.getByTestId('open'));
    await screen.findByTestId('annotation-window');
    expect(screen.getByRole('button', { name: '도움말 보기' })).toBeInTheDocument();
    expect(screen.queryByText('이 영상에서 확인하려는 사건의 종류입니다.')).toBeNull();
  });

  it('★잠시_접으면_띠가_대신하고_저장_안_된_변경을_병기한다', async () => {
    const user = await openWindow();
    await user.clear(timeseriesInput());
    await user.type(timeseriesInput(), '고친 서술');

    await user.click(screen.getByTestId('floating-window-fold'));

    const strip = await screen.findByTestId('annotation-strip-folded');
    expect(strip).toHaveTextContent(
      '영상 분석 설명 · 이벤트 어노테이션 창을 잠시 접었습니다. 다시 보려면 펼치세요.',
    );
    expect(screen.getByTestId('annotation-strip-folded-dirty')).toHaveTextContent(
      '저장 안 된 변경 있음',
    );
    expect(screen.getByTestId('annotation-window')).not.toBeVisible();

    // 「펼치기」로 되돌리면 고치던 값이 그대로다(창은 언마운트되지 않았다).
    await user.click(within(strip).getByRole('button', { name: '펼치기' }));
    expect(screen.getByTestId('annotation-window')).toBeVisible();
    expect(timeseriesInput()).toHaveValue('고친 서술');
  });

  it('★창은_동시에_하나다_이미_열려_있으면_앞으로_가져온다', async () => {
    const user = await openWindow();

    expect(screen.getByTestId('open')).toHaveTextContent('창 앞으로 가져오기');
    await user.click(screen.getByTestId('open'));

    expect(screen.getAllByTestId('annotation-window')).toHaveLength(1);
    expect(screen.getByTestId('window-state')).toHaveTextContent('open');
  });

  it('★폭이_880_미만이면_두_칸을_위아래로_쌓는다', async () => {
    // given — 사용자가 창을 좁혀 두고 닫은 경우(기억된 크기로 다시 열린다).
    localStorage.setItem(
      POSITION_KEY,
      JSON.stringify({ x: 20, y: 20, width: 700, height: 700 }),
    );

    await openWindow();

    // ⚠ 배치는 클래스로만 드러나 다른 관측 수단이 없다 — 이 저장소는 죽은 브레이크포인트 접두어
    //   때문에 «클래스는 붙었는데 적용은 안 되는» 사고를 겪었고, jsdom 은 CSS 를 적용하지 않아
    //   「나란히/쌓임」을 시각으로 물어볼 대상이 없다. 그래서 여기서는 클래스로 고정한다.
    const columns = screen.getByTestId('annotation-window-columns');
    expect(columns.className).toContain('flex-col');
    expect(columns.className).not.toContain('grid-cols-');
    expect(screen.getByTestId('annotation-window')).toHaveAttribute('data-stacked', 'true');
    // 쌓여도 두 칸은 그대로 있다(감추는 것이 아니라 배치만 바꾼다).
    expect(screen.getByTestId('timeseries-column')).toBeInTheDocument();
    expect(screen.getByTestId('annotation-column')).toBeInTheDocument();
  });

  it('폭이_넉넉하면_두_칸을_5대7로_나란히_둔다', async () => {
    localStorage.setItem(
      POSITION_KEY,
      JSON.stringify({ x: 8, y: 8, width: 1000, height: 700 }),
    );

    await openWindow();

    const columns = screen.getByTestId('annotation-window-columns');
    expect(columns.className).toContain('grid-cols-[5fr_7fr]');
    expect(screen.getByTestId('annotation-window')).toHaveAttribute('data-stacked', 'false');
  });

  it('★이벤트_분류는_이름과_코드로_보인다', async () => {
    await openWindow();

    expect(screen.getByTestId('ea-event-class')).toHaveValue('car_accident');
    expect(screen.getByTestId('ea-event-class-name')).toHaveTextContent('교통사고');
  });
});
