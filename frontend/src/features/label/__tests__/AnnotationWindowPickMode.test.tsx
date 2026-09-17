// 근거 「화면에서 지정」 모드 — 창을 숨기고 화면에서 프레임·객체를 담는다.
// [@design UI-158] [@design UI-107] [@design SCREEN-005]
//
// 이 파일이 고정하는 것:
//  ① 「화면에서 지정」을 누르면 창이 숨고 띠가 뜬다(창은 <b>언마운트되지 않는다</b> — 값 보존)
//  ② 담기 두 버튼은 기존 「현재 프레임 추가」·「선택 객체 추가」와 <b>같은 동작</b>이다
//  ③ 「완료」는 창을 되돌리고 담은 값을 그 근거 후보에 넣는다(저장은 아직이다)
//  ④ 「취소」는 이번에 담은 것만 버리고 창은 <b>똑같이</b> 되돌린다

import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';
import type { Label } from '@/features/label/types';

const {
  mockUseMeta,
  mockUseUpdateMeta,
  mockUseEventAnnotation,
  mockUseUpdateEventAnnotation,
  mockUseEventAnnotationReview,
} = vi.hoisted(() => ({
  mockUseMeta: vi.fn(),
  mockUseUpdateMeta: vi.fn(),
  mockUseEventAnnotation: vi.fn(),
  mockUseUpdateEventAnnotation: vi.fn(),
  mockUseEventAnnotationReview: vi.fn(),
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

import { AnnotationWindow } from '../components/AnnotationWindow';
import { useAnnotationWindow } from '../hooks/useAnnotationWindow';

const RAW_SN = 7;
const SRC_SN = 300;

function Host() {
  const w = useAnnotationWindow();
  return (
    <>
      <button type="button" data-testid="open" onClick={w.openOrFocus}>
        열기
      </button>
      <span data-testid="window-state">{w.state}</span>
      {w.mounted && (
        <AnnotationWindow
          mode="editable"
          rawSn={RAW_SN}
          srcSn={SRC_SN}
          state={w.state}
          focusRequestedAt={w.focusRequestedAt}
          onClose={w.close}
          onFold={w.fold}
          onExpand={w.expand}
          onPickingChange={w.setPicking}
          frameIndex={2}
          frameTotal={6}
        />
      )}
    </>
  );
}

function makeLabel(overrides: Partial<Label> = {}): Label {
  return {
    id: 'lbl-1',
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 78, top: 419, right: 145, bottom: 477 },
    trackId: '1374',
    ...overrides,
  };
}

/** 창을 열고 근거 후보 하나를 만든 뒤 지정 모드로 들어간다. */
async function enterPickMode() {
  const user = userEvent.setup();
  renderWithProviders(<Host />);
  await user.click(screen.getByTestId('open'));
  await screen.findByTestId('annotation-window');
  await user.click(screen.getByTestId('ea-add-evidence'));
  await user.click(screen.getByTestId('ea-evidence-pick-c1'));
  await screen.findByTestId('annotation-strip-pick');
  return user;
}

describe('근거 화면에서 지정', () => {
  beforeEach(() => {
    useLabelStore.setState({ labels: [], selectedLabelId: null });
    mockUseMeta.mockReturnValue({
      data: { items: [], technicalMeta: [], readOnlyMeta: [], importedMeta: [] },
      isLoading: false,
      error: null,
    });
    mockUseUpdateMeta.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
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
      mutateAsync: vi.fn(),
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
    useLabelStore.setState({ labels: [], selectedLabelId: null });
  });

  it('★지정을_시작하면_창이_숨고_띠가_대신한다_창은_언마운트되지_않는다', async () => {
    await enterPickMode();

    expect(screen.getByTestId('window-state')).toHaveTextContent('picking');
    // 창은 DOM 에 남아 있다 — 언마운트하면 고치던 값이 사라진다.
    expect(screen.getByTestId('annotation-window')).toBeInTheDocument();
    expect(screen.getByTestId('annotation-window')).not.toBeVisible();

    const strip = screen.getByTestId('annotation-strip-pick');
    expect(strip).toHaveTextContent('근거 1 지정 중');
    expect(strip).toHaveTextContent('지금 프레임 2/6');
    expect(strip).toHaveTextContent('담은 것 · 프레임 0 · 객체 0');
    expect(strip).toHaveTextContent(
      '프레임을 넘기고 화면에서 객체를 고른 뒤 담기 버튼을 누르세요.',
    );
  });

  it('고른_객체가_없으면_객체_담기가_잠긴다', async () => {
    await enterPickMode();
    const strip = screen.getByTestId('annotation-strip-pick');

    expect(within(strip).getByRole('button', { name: '고른 객체 담기' })).toBeDisabled();
    expect(strip).toHaveTextContent('고른 객체: 없음');
  });

  it('★담고_완료하면_창이_돌아오고_근거에_값이_들어간다', async () => {
    const user = await enterPickMode();
    const strip = screen.getByTestId('annotation-strip-pick');

    // 지금 프레임 담기
    await user.click(within(strip).getByRole('button', { name: '지금 프레임 담기' }));
    expect(screen.getByTestId('annotation-strip-picked-counts')).toHaveTextContent(
      '담은 것 · 프레임 1 · 객체 0',
    );

    // 화면에서 객체를 고른다(캔버스 선택) → 담기
    useLabelStore.setState({ labels: [makeLabel()], selectedLabelId: 'lbl-1' });
    await waitFor(() =>
      expect(screen.getByTestId('annotation-strip-pick')).toHaveTextContent(
        '고른 객체: car (번호 1374)',
      ),
    );
    await user.click(
      within(screen.getByTestId('annotation-strip-pick')).getByRole('button', {
        name: '고른 객체 담기',
      }),
    );
    expect(screen.getByTestId('annotation-strip-picked-counts')).toHaveTextContent(
      '담은 것 · 프레임 1 · 객체 1',
    );

    // 완료
    await user.click(
      within(screen.getByTestId('annotation-strip-pick')).getByRole('button', { name: '완료' }),
    );

    // then — 창이 돌아오고 담은 값이 그 근거 후보에 들어간다(기존 담기 규칙 그대로).
    expect(screen.getByTestId('annotation-window')).toBeVisible();
    expect(screen.queryByTestId('annotation-strip-pick')).toBeNull();
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue(String(SRC_SN));
    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('1374');
    expect(screen.getByTestId('ea-evidence-objlabel-c1')).toHaveValue('car');
    expect(screen.getByTestId('ea-evidence-objbbox-c1')).toHaveValue('78,419,145,477');
    // then — 아직 저장 전임을 분명히 말한다.
    expect(screen.getByTestId('ea-evidence-picked-notice-c1')).toHaveTextContent(
      '방금 「화면에서 지정」으로 담은 값입니다(프레임 1개 · 객체 1개). 저장해야 반영됩니다.',
    );
    // then — 저장 바가 이 칸을 가리킨다(반영은 저장으로 확정된다).
    expect(screen.getByTestId('annotation-window-save-bar')).toHaveTextContent(
      '바뀐 칸만 저장합니다 — 이벤트 어노테이션',
    );
  });

  it('★취소는_이번에_담은_것만_버리고_창은_완료와_똑같이_되돌린다', async () => {
    const user = await enterPickMode();
    const strip = screen.getByTestId('annotation-strip-pick');

    await user.click(within(strip).getByRole('button', { name: '지금 프레임 담기' }));
    await user.click(within(strip).getByRole('button', { name: '취소' }));

    // 창은 바로 돌아온다(「완료」와 복원 동작이 같다 — 값 반영만 다르다).
    expect(screen.getByTestId('annotation-window')).toBeVisible();
    expect(screen.getByTestId('window-state')).toHaveTextContent('open');
    expect(screen.queryByTestId('annotation-strip-pick')).toBeNull();
    // 담은 값은 들어가지 않는다.
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue('');
    expect(screen.queryByTestId('ea-evidence-picked-notice-c1')).toBeNull();
  });

  it('같은_프레임을_두_번_담아도_한_번만_들어간다', async () => {
    const user = await enterPickMode();
    const strip = screen.getByTestId('annotation-strip-pick');

    await user.click(within(strip).getByRole('button', { name: '지금 프레임 담기' }));
    await user.click(within(strip).getByRole('button', { name: '지금 프레임 담기' }));
    expect(screen.getByTestId('annotation-strip-picked-counts')).toHaveTextContent(
      '담은 것 · 프레임 1 · 객체 0',
    );

    await user.click(within(strip).getByRole('button', { name: '완료' }));
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue(String(SRC_SN));
  });

  it('읽기_전용에는_화면에서_지정_버튼이_없다', async () => {
    mockUseEventAnnotation.mockReturnValue({
      data: {
        rawSn: RAW_SN,
        evntAnnoSn: 1,
        reviewStatus: null,
        regId: null,
        mdfcnId: null,
        payload: {
          event_class: 'car_accident',
          evidence: { c1: { evidence_text: '근거', frame_id: [SRC_SN] } },
        },
      },
    });
    const user = userEvent.setup();
    renderWithProviders(
      <ReadOnlyHost />,
    );
    await user.click(screen.getByTestId('open'));
    await screen.findByTestId('annotation-window');

    expect(screen.queryByTestId('ea-evidence-pick-c1')).toBeNull();
    expect(screen.queryByTestId('ea-add-evidence')).toBeNull();
  });
});

/** 읽기 전용(검수) 껍데기 — 지정 컨트롤이 그려지지 않는지 보기 위한 최소 구성. */
function ReadOnlyHost() {
  const w = useAnnotationWindow();
  return (
    <>
      <button type="button" data-testid="open" onClick={w.openOrFocus}>
        열기
      </button>
      {w.mounted && (
        <AnnotationWindow
          mode="readOnly"
          rawSn={RAW_SN}
          srcSn={SRC_SN}
          state={w.state}
          onClose={w.close}
          onFold={w.fold}
          onExpand={w.expand}
          onPickingChange={w.setPicking}
        />
      )}
    </>
  );
}
