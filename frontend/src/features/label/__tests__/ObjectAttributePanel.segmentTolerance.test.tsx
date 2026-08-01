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
