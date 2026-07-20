// Phase 4 — AI Tool 팝업 테스트 (형태 라디오 + 라벨 선택 + 일반/트랙).

import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

import { AiToolModal } from '../components/AiToolModal';

describe('AiToolModal', () => {
  it('열리면_형태_라디오와_라벨_목록_렌더', () => {
    renderWithProviders(<AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} />);
    expect(screen.getByRole('radio', { name: '박스' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '폴리곤' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '사람' })).toBeInTheDocument();
    // 기본 형태는 박스.
    expect(screen.getByRole('radio', { name: '박스' })).toBeChecked();
  });

  it('AI_Tool_팝업에_모델명(YOLO/SAM)이_노출되지_않는다', () => {
    const { container } = renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} />,
    );
    const text = container.textContent ?? '';
    expect(text).not.toMatch(/YOLO/i);
    expect(text).not.toMatch(/SAM2?/i);
  });

  it('일반_실행시_onConfirm에_shape_classIds_detect_전달', () => {
    const onConfirm = vi.fn();
    renderWithProviders(<AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('checkbox', { name: '사람' }));
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('BBOX', ['person'], 'detect');
  });

  it('AI_Tool_팝업_트랙모드_선택시_추적이_실행된다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(<AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('button', { name: '트랙' }));
    expect(onConfirm).toHaveBeenCalledWith('BBOX', [], 'track');
  });

  it('폴리곤_선택후_일반_실행시_POLYGON_전달', () => {
    const onConfirm = vi.fn();
    renderWithProviders(<AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('POLYGON', [], 'detect');
  });

  it('canTrack이_false면_트랙_버튼_비활성', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} canTrack={false} />,
    );
    expect(screen.getByRole('button', { name: '트랙' })).toBeDisabled();
  });

  it('취소시_onClose_호출되고_onConfirm_미호출', () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<AiToolModal open onClose={onClose} onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  // === 즉시 그리기 토글 ===
  it('즉시_그리기_토글이_체크박스와_라벨로_렌더된다', () => {
    renderWithProviders(<AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} />);
    const cb = screen.getByRole('checkbox', { name: '즉시 그리기' });
    expect(cb).toBeInTheDocument();
    // 기본 OFF (미지정 시 false).
    expect(cb).not.toBeChecked();
  });

  it('토글_변경시_onImmediateDrawChange가_호출된다', () => {
    const onImmediateDrawChange = vi.fn();
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        immediateDraw={false}
        onImmediateDrawChange={onImmediateDrawChange}
      />,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: '즉시 그리기' }));
    expect(onImmediateDrawChange).toHaveBeenCalledWith(true);
  });

  it('immediateDraw_prop이_체크상태에_반영된다', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        immediateDraw
        onImmediateDrawChange={vi.fn()}
      />,
    );
    expect(screen.getByRole('checkbox', { name: '즉시 그리기' })).toBeChecked();
  });

  it('즉시_그리기_토글에도_모델명(YOLO/SAM)이_노출되지_않는다', () => {
    const { container } = renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} immediateDraw onImmediateDrawChange={vi.fn()} />,
    );
    const text = container.textContent ?? '';
    expect(text).not.toMatch(/YOLO/i);
    expect(text).not.toMatch(/SAM2?/i);
  });
});
