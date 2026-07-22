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

  // === Phase 2 [FE] 정밀도 조절 (인식 민감도 / 경계 세밀함) ===
  it('AI탐지모달_인식민감도_경계세밀함이_시스템설정값으로_프리필된다', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        defaultConfThreshold={0.5}
        defaultSimplifyTolerance={10}
      />,
    );
    // 인식 민감도는 항상 노출(detect) — 프리필 0.50.
    expect((screen.getByRole('slider', { name: '인식 민감도' }) as HTMLInputElement).value).toBe(
      '0.5',
    );
    // 경계 세밀함은 폴리곤에서만 — 폴리곤 선택 후 프리필 10 확인.
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    expect((screen.getByRole('slider', { name: '경계 세밀함' }) as HTMLInputElement).value).toBe(
      '10',
    );
  });

  it('폴리곤_shape일때만_경계세밀함_슬라이더가_보인다', () => {
    renderWithProviders(<AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} />);
    // 기본 박스 — 경계 세밀함 미노출.
    expect(screen.queryByRole('slider', { name: '경계 세밀함' })).not.toBeInTheDocument();
    // 인식 민감도는 항상 노출.
    expect(screen.getByRole('slider', { name: '인식 민감도' })).toBeInTheDocument();
    // 폴리곤 전환 시 노출.
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    expect(screen.getByRole('slider', { name: '경계 세밀함' })).toBeInTheDocument();
  });

  it('슬라이더_조절후_확인하면_onConfirm에_confThreshold_simplifyTolerance가_전달된다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        defaultConfThreshold={0.4}
        defaultSimplifyTolerance={1}
      />,
    );
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    fireEvent.change(screen.getByRole('slider', { name: '인식 민감도' }), {
      target: { value: '0.6' },
    });
    fireEvent.change(screen.getByRole('slider', { name: '경계 세밀함' }), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('POLYGON', [], 'detect', {
      confThreshold: 0.6,
      simplifyTolerance: 5,
    });
  });

  it('조절하지_않으면_onConfirm에_정밀도_옵션이_포함되지_않는다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        defaultConfThreshold={0.4}
        defaultSimplifyTolerance={1}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    // 미조절 → 4번째 인자(opts) 자체가 없어야 한다(BE 기본값).
    expect(onConfirm).toHaveBeenCalledWith('BBOX', [], 'detect');
    expect(onConfirm.mock.calls[0]).toHaveLength(3);
  });

  it('BBOX모드에서_인식민감도만_조절하면_confThreshold만_전달된다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} defaultConfThreshold={0.4} />,
    );
    fireEvent.change(screen.getByRole('slider', { name: '인식 민감도' }), {
      target: { value: '0.55' },
    });
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('BBOX', [], 'detect', { confThreshold: 0.55 });
  });

  it('정밀도_슬라이더에도_모델명(YOLO/SAM)이_노출되지_않는다', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        defaultConfThreshold={0.5}
        defaultSimplifyTolerance={10}
      />,
    );
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    // Modal 은 포털로 렌더되므로 문서 전체 텍스트를 확인한다.
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/YOLO/i);
    expect(text).not.toMatch(/SAM2?/i);
    // 노출 문구는 인식 민감도 / 경계 세밀함.
    expect(text).toContain('인식 민감도');
    expect(text).toContain('경계 세밀함');
  });
});
