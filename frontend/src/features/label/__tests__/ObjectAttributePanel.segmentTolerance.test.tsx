// Phase 2 [FE] — AI 분할(SAM_SEGMENT) 도구 활성 시 "AI 분할 정밀도" 섹션 노출/조절 검증.
//
// 규칙: AI 분할은 경계 세밀함만 노출(인식 민감도는 분할에 무의미 → 미노출).
// 프리필은 시스템 설정값, 조절 시 상위 콜백(onToleranceChange)으로 값이 올라간다.
// "즉시 그리기" 토글도 같은 섹션에 있다 — 이 옵션은 AI 분할 클릭 프리뷰에만 효력이 있어
// 구 위치(AI Tool 팝업)에서 여기로 이동했다.

import { fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import type { Label } from '../types';
import { ToolType } from '../types';

describe('ObjectAttributePanel — AI 분할 경계 세밀함', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('AI분할_도구활성시_경계세밀함만_노출되고_인식민감도는_없다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{ defaultTolerance: 3, onToleranceChange: vi.fn() }}
      />,
    );
    // 선택 객체가 없어도 분할 도구 활성이면 슬라이더 노출.
    expect(screen.getByRole('slider', { name: '경계 세밀함' })).toBeInTheDocument();
    // 인식 민감도는 분할에서 미노출.
    expect(screen.queryByRole('slider', { name: '인식 민감도' })).not.toBeInTheDocument();
  });

  it('경계세밀함_프리필은_시스템설정값이다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{ defaultTolerance: 12, onToleranceChange: vi.fn() }}
      />,
    );
    expect((screen.getByRole('slider', { name: '경계 세밀함' }) as HTMLInputElement).value).toBe(
      '12',
    );
  });

  it('슬라이더_조절시_onToleranceChange가_호출된다', () => {
    const onToleranceChange = vi.fn();
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    renderWithProviders(
      <ObjectAttributePanel labels={[]} segment={{ defaultTolerance: 1, onToleranceChange }} />,
    );
    fireEvent.change(screen.getByRole('slider', { name: '경계 세밀함' }), {
      target: { value: '8' },
    });
    expect(onToleranceChange).toHaveBeenCalledWith(8);
  });

  it('분할_도구가_아니면_경계세밀함_슬라이더가_노출되지_않는다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SELECT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{ defaultTolerance: 3, onToleranceChange: vi.fn() }}
      />,
    );
    expect(screen.queryByRole('slider', { name: '경계 세밀함' })).not.toBeInTheDocument();
  });

  // === 즉시 그리기 토글 (구 위치: AI Tool 팝업 → 현 위치: "AI 분할 정밀도" 섹션) ===
  // 이 옵션은 AI 분할 클릭 프리뷰에만 효력이 있어 실제 사용 지점인 이 섹션에 둔다.
  it('AI분할_도구활성시_즉시그리기_토글이_경계세밀함과_함께_노출된다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{ defaultTolerance: 3, onToleranceChange: vi.fn(), onImmediateDrawChange: vi.fn() }}
      />,
    );
    // 선택 객체가 없어도 분할 도구 활성이면 노출 — 슬라이더와 같은 섹션.
    expect(screen.getByRole('slider', { name: '경계 세밀함' })).toBeInTheDocument();
    const cb = screen.getByRole('checkbox', { name: '즉시 그리기' });
    expect(cb).toBeInTheDocument();
    // 기본 OFF (미지정 시 false).
    expect(cb).not.toBeChecked();
  });

  it('분할_도구가_아니면_즉시그리기_토글이_노출되지_않는다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SELECT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{ defaultTolerance: 3, onToleranceChange: vi.fn(), onImmediateDrawChange: vi.fn() }}
      />,
    );
    expect(screen.queryByRole('checkbox', { name: '즉시 그리기' })).not.toBeInTheDocument();
  });

  it('즉시그리기_토글_변경시_onImmediateDrawChange가_호출된다', () => {
    const onImmediateDrawChange = vi.fn();
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{
          defaultTolerance: 3,
          onToleranceChange: vi.fn(),
          immediateDraw: false,
          onImmediateDrawChange,
        }}
      />,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: '즉시 그리기' }));
    expect(onImmediateDrawChange).toHaveBeenCalledWith(true);
  });

  it('immediateDraw_prop이_체크상태에_반영된다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{
          defaultTolerance: 3,
          onToleranceChange: vi.fn(),
          immediateDraw: true,
          onImmediateDrawChange: vi.fn(),
        }}
      />,
    );
    expect(screen.getByRole('checkbox', { name: '즉시 그리기' })).toBeChecked();
  });

  it('AI분할_슬라이더에_모델명(YOLO/SAM)이_노출되지_않는다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    const { container } = renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{ defaultTolerance: 3, onToleranceChange: vi.fn() }}
      />,
    );
    const text = container.textContent ?? '';
    expect(text).not.toMatch(/YOLO/i);
    expect(text).not.toMatch(/SAM2?/i);
    expect(text).toContain('경계 세밀함');
  });
});

// === 레이아웃 회귀 가드 — "AI 분할 정밀도 카드가 잘려 조작 불가" 재현/차단 ===
//
// 결함: 패널 루트(aside)의 두 분기 중 **선택 객체 없음** 분기에만 `overflow-y-auto` 가 없었다.
// 이 패널은 `overflow-hidden` 인 조상(우측 패널) 안의 flex 아이템이라, 스크롤이 없으면
// flex 자동 최소 크기(min-height:auto = 콘텐츠 높이)가 걸려 줄어들지 못하고 잘린다.
// 하필 "AI 분할 정밀도" 카드는 선택 객체가 없어도 노출되는 유일한 컨트롤이라, 스크롤이
// 없는 쪽에만 콘텐츠가 늘어 슬라이더 하단과 "즉시 그리기" 체크박스가 화면에서 사라졌다.
//
// ⚠ jsdom 한계 — 이 테스트가 **검증하지 못하는 것**:
//   - jsdom 은 레이아웃을 계산하지 않는다(모든 요소의 크기가 0). 따라서 "실제로 잘렸는지",
//     "스크롤로 도달 가능한지"는 단언할 수 없다. 실제 픽셀 확인은 브라우저 검증(ui-tester) 몫이다.
//   - 대신 결함의 **구조적 원인**을 단언한다: ① 두 분기가 동일한 높이·스크롤·폭 계약을 갖는지
//     ② 잘림을 유발했던 클래스(h-full / w-72)가 없고 스크롤 계약(overflow-y-auto, flex-1, min-h-0)이
//     있는지 ③ 두 컨트롤이 선택 객체 없음 분기에서도 쿼리·조작 가능한지.
const selectedLabel: Label = {
  id: 'sel-1',
  frameNo: 1,
  classId: 3,
  className: 'pedestrian',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
};

/** 루트 aside 의 클래스 집합에서 gap(분기별로 다른 간격)만 제외해 레이아웃 계약을 비교한다. */
function layoutContract(el: HTMLElement): string[] {
  return el.className
    .split(/\s+/)
    .filter((c) => c.length > 0 && !c.startsWith('gap-'))
    .sort();
}

describe('ObjectAttributePanel — 패널 레이아웃 계약(잘림 회귀 가드)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('선택객체가_없어도_AI분할_슬라이더와_즉시그리기_체크박스를_모두_조작할_수_있다', () => {
    const onToleranceChange = vi.fn();
    const onImmediateDrawChange = vi.fn();
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{
          defaultTolerance: 3,
          onToleranceChange,
          immediateDraw: false,
          onImmediateDrawChange,
        }}
      />,
    );
    // 선택 객체 없음 분기임을 명시(잘림이 관측된 바로 그 상태).
    expect(screen.getByText(/선택된 객체가 없습니다/)).toBeInTheDocument();

    // 두 컨트롤 모두 존재하고 비활성이 아니며 실제로 값이 올라간다.
    const slider = screen.getByRole('slider', { name: '경계 세밀함' });
    const checkbox = screen.getByRole('checkbox', { name: '즉시 그리기' });
    expect(slider).toBeEnabled();
    expect(checkbox).toBeEnabled();
    fireEvent.change(slider, { target: { value: '7' } });
    fireEvent.click(checkbox);
    expect(onToleranceChange).toHaveBeenCalledWith(7);
    expect(onImmediateDrawChange).toHaveBeenCalledWith(true);
  });

  it('선택객체_유무와_무관하게_루트_패널의_레이아웃_계약이_동일하다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    const segment = { defaultTolerance: 3, onToleranceChange: vi.fn(), onImmediateDrawChange: vi.fn() };

    // 분기 A — 선택 객체 없음
    const noneView = renderWithProviders(<ObjectAttributePanel labels={[]} segment={segment} />);
    const noneAside = noneView.getByLabelText('객체 속성');
    const noneContract = layoutContract(noneAside);
    noneView.unmount();

    // 분기 B — 선택 객체 있음
    useLabelStore.getState().setLabels([selectedLabel]);
    useLabelStore.getState().selectLabel(selectedLabel.id);
    const selView = renderWithProviders(
      <ObjectAttributePanel labels={[selectedLabel]} segment={segment} />,
    );
    const selAside = selView.getByLabelText('객체 속성');

    expect(noneContract).toEqual(layoutContract(selAside));
  });

  it('루트_패널은_스크롤_가능하고_형제_헤더와_높이·폭을_다투지_않는다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SAM_SEGMENT);
    const { getByLabelText } = renderWithProviders(
      <ObjectAttributePanel
        labels={[]}
        segment={{ defaultTolerance: 3, onToleranceChange: vi.fn(), onImmediateDrawChange: vi.fn() }}
      />,
    );
    const classes = layoutContract(getByLabelText('객체 속성'));

    // 조상이 overflow-hidden 이므로 이 패널이 스스로 스크롤해야 잘리지 않는다.
    expect(classes).toContain('overflow-y-auto');
    // 형제 헤더('속성')가 있는 flex-col 부모에서 남은 높이만 차지 + 축소 허용.
    expect(classes).toContain('flex-1');
    expect(classes).toContain('min-h-0');
    // h-full 은 부모 100% 라 형제 헤더 높이만큼 항상 넘친다(잘림 원인) — 재도입 금지.
    expect(classes).not.toContain('h-full');
    // 폭은 부모 패널 소유. 자식이 w-72 로 중복 고정하면 가로로 삐져나온다 — 재도입 금지.
    expect(classes).not.toContain('w-72');
    expect(classes).toContain('w-full');
  });
});
