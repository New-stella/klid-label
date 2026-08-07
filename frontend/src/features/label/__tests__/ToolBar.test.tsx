// ISSUE-2 — DarkToolbar SAM2 분할/추적 도구 버튼 노출 + 선택 동작 검증.

import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { DarkToolbar } from '../components/DarkToolbar';
import { ToolType } from '../types';

describe('DarkToolbar — SAM2 도구', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('SAM_분할과_SAM_추적_버튼이_렌더된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'AI 추적' })).toBeInTheDocument();
  });

  it('SAM_분할_클릭_시_activeTool이_SAM_SEGMENT로_전환된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'AI 분할' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SAM_SEGMENT);
  });

  it('SAM_추적_클릭_시_activeTool이_TRACK으로_전환된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'AI 추적' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.TRACK);
  });
});

describe('DarkToolbar — 키포인트 도구', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('키포인트_버튼이_렌더된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
  });

  it('키포인트_클릭_시_activeTool이_KEYPOINT로_전환된다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: '스켈레톤' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.KEYPOINT);
  });

  it('포털_사용자는_AI분할_도구를_사용할_수_없다', () => {
    // ADR-013 — 포털은 SAM2·오토라벨 미제공. 서버 엔드포인트도 제거됐다(PortalSam2RemovedTest).
    renderWithProviders(<DarkToolbar onSave={vi.fn()} portalMode />);
    expect(screen.queryByRole('button', { name: 'AI 분할' })).not.toBeInTheDocument();
  });

  it('포털_사용자는_AI추적_도구를_사용할_수_없다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} portalMode />);
    expect(screen.queryByRole('button', { name: 'AI 추적' })).not.toBeInTheDocument();
  });

  it('포털_사용자는_스켈레톤_도구를_사용할_수_없다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} portalMode />);
    expect(screen.queryByRole('button', { name: '스켈레톤' })).not.toBeInTheDocument();
  });

  it('포털_모드에서도_수동_라벨링_도구는_그대로_노출된다', () => {
    // 포털 사용자는 BBOX/POLYGON 수동 라벨링을 계속 제공받는다(ADR-013 예외) — 과잉 차단 회귀 가드.
    renderWithProviders(<DarkToolbar onSave={vi.fn()} portalMode />);
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '폴리곤' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '선택' })).toBeInTheDocument();
  });

  it('내부_라벨링_화면의_SAM2_도구는_기존과_동일하게_노출된다', () => {
    // SFR-08-01(VOS) 핵심 기능 — 포털 제거가 내부(INTERNAL) 채널을 함께 막았는지 회귀 고정.
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'AI 추적' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
  });
});

describe('DarkToolbar — YOLO 오토라벨', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('onAutolabel_핸들러_지정시_YOLO_버튼_렌더_및_클릭_호출', () => {
    const onAutolabel = vi.fn();
    renderWithProviders(<DarkToolbar onSave={vi.fn()} onAutolabel={onAutolabel} />);
    const btn = screen.getByRole('button', { name: 'AI 탐지' });
    expect(btn).toBeInTheDocument();
    fireEvent.click(btn);
    expect(onAutolabel).toHaveBeenCalledTimes(1);
  });

  it('onAutolabel_미지정시_YOLO_버튼_미노출', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    expect(screen.queryByRole('button', { name: 'AI 탐지' })).not.toBeInTheDocument();
  });

  it('진행중이면_YOLO_버튼_비활성화되어_중복클릭_방지', () => {
    const onAutolabel = vi.fn();
    renderWithProviders(
      <DarkToolbar onSave={vi.fn()} onAutolabel={onAutolabel} isAutolabeling />,
    );
    const btn = screen.getByRole('button', { name: 'AI 탐지' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-busy', 'true');
    fireEvent.click(btn);
    expect(onAutolabel).not.toHaveBeenCalled();
  });

  it('portalMode에서_YOLO_오토라벨_버튼_숨김_ADR_013', () => {
    renderWithProviders(
      <DarkToolbar onSave={vi.fn()} onAutolabel={vi.fn()} portalMode />,
    );
    expect(screen.queryByRole('button', { name: 'AI 탐지' })).not.toBeInTheDocument();
  });
});
