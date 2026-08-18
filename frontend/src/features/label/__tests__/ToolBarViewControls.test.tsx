// UI-046 / SCREEN-005 §좌측 도구바 — 보기 조작(좌·우 90° 회전)과 그리드 표시 토글.
//
// 회전·그리드는 **표시 전용**이라 도구바가 상태를 갖지 않는다(화면이 갖고 캔버스와 공유한다).
// 따라서 도구바에 대한 가드는 셋이다:
//   ① 콜백을 넘기지 않은 기존 호출부에는 버튼이 늘지 않는다(무회귀)
//   ② 토글 버튼은 눌림 상태를 노출하고 키보드로 조작된다(접근성)
//   ③ 회전 중에는 그리기 도구를 잠근다 — 캔버스가 편집 입력을 봉인하므로 잠그지 않으면 죽은 버튼이 된다

import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ToolBar } from '../components/ToolBar';

const toolbar = () => within(screen.getByRole('toolbar', { name: '라벨링 도구' }));

beforeEach(() => {
  useLabelStore.getState().reset();
});

describe('ToolBar — 보기 조작(회전) · 그리드 토글', () => {
  it('콜백_미지정_기존_호출부에는_회전_그리드_버튼이_생기지_않는다', () => {
    renderWithProviders(<ToolBar />);

    expect(screen.queryByTestId('label-toolbar-rotate-left')).toBeNull();
    expect(screen.queryByTestId('label-toolbar-rotate-right')).toBeNull();
    expect(screen.queryByTestId('label-toolbar-grid')).toBeNull();
    expect(screen.queryByTestId('label-toolbar-zoom-area')).toBeNull();
    // 기존 보기 조작(화면 맞춤)은 그대로다.
    expect(screen.getByTestId('label-toolbar-fit')).toBeInTheDocument();
  });

  it('회전_버튼은_좌우_90도를_요청한다_아이콘_전용이어도_접근가능한_이름을_갖는다', async () => {
    const onRotate = vi.fn();
    renderWithProviders(<ToolBar rotation={0} onRotate={onRotate} />);

    const left = toolbar().getByRole('button', { name: '왼쪽으로 90도 회전' });
    const right = toolbar().getByRole('button', { name: '오른쪽으로 90도 회전' });

    await userEvent.click(left);
    expect(onRotate).toHaveBeenCalledWith(-90);
    await userEvent.click(right);
    expect(onRotate).toHaveBeenLastCalledWith(90);

    // 일회성 액션이라 눌림 상태(토글)로 표기하지 않는다 — 현재 각도는 툴팁으로 알린다.
    expect(left).not.toHaveAttribute('aria-pressed');
    expect(left).toHaveAttribute('title', expect.stringContaining('현재 0도'));
  });

  it('그리드_토글은_눌림_상태를_노출하고_키보드로_조작된다', async () => {
    const onToggleGrid = vi.fn();
    const { rerender } = renderWithProviders(
      <ToolBar showGrid={false} onToggleGrid={onToggleGrid} />,
    );

    const grid = toolbar().getByRole('button', { name: '그리드 표시' });
    expect(grid).toHaveAttribute('aria-pressed', 'false');

    // 키보드 도달·조작(Tab 포커스 후 Enter) — 마우스 전용이 아니다.
    // (포커스 시 도구바가 툴팁 상태를 갱신하므로 act 로 감싼다.)
    act(() => grid.focus());
    expect(grid).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(onToggleGrid).toHaveBeenCalledTimes(1);

    rerender(<ToolBar showGrid onToggleGrid={onToggleGrid} />);
    expect(toolbar().getByRole('button', { name: '그리드 표시' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('회전_중에는_그리기_도구를_잠근다_선택도구와_보기조작은_남긴다', () => {
    const onRotate = vi.fn();
    const onToggleGrid = vi.fn();
    renderWithProviders(
      <ToolBar rotation={90} onRotate={onRotate} onToggleGrid={onToggleGrid} />,
    );

    // 'AI 추적'은 도구바 버튼이 아니라 우측 객체 패널의 실행 버튼이라 이 목록에 없다(사양 정합).
    for (const name of ['바운딩 박스', '폴리곤', 'AI 분할', '스켈레톤']) {
      const btn = toolbar().getByRole('button', { name });
      expect(btn).toBeDisabled();
      expect(btn).toHaveAttribute('title', expect.stringContaining('회전 중에는 사용할 수 없습니다'));
    }
    // 회전을 풀었을 때 되돌아갈 기본 도구와, 회전을 되돌릴 수단 자체는 막지 않는다.
    expect(toolbar().getByRole('button', { name: '선택' })).not.toBeDisabled();
    expect(screen.getByTestId('label-toolbar-rotate-right')).not.toBeDisabled();
    expect(screen.getByTestId('label-toolbar-grid')).not.toBeDisabled();
  });

  it('영역_확대는_모드_토글이라_눌림_상태를_노출하고_키보드로_조작된다', async () => {
    const onToggleZoomArea = vi.fn();
    const { rerender } = renderWithProviders(
      <ToolBar zoomAreaMode={false} onToggleZoomArea={onToggleZoomArea} />,
    );

    const zoomArea = toolbar().getByRole('button', { name: '영역 확대' });
    expect(zoomArea).toHaveAttribute('aria-pressed', 'false');

    // 키보드 도달·조작(포커스 후 Enter) — 마우스 전용이 아니다.
    act(() => zoomArea.focus());
    expect(zoomArea).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(onToggleZoomArea).toHaveBeenCalledTimes(1);

    rerender(<ToolBar zoomAreaMode onToggleZoomArea={onToggleZoomArea} />);
    expect(toolbar().getByRole('button', { name: '영역 확대' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('영역_확대_중에도_되돌릴_수단인_화면_맞춤은_열려_있다', () => {
    renderWithProviders(<ToolBar zoomAreaMode onToggleZoomArea={vi.fn()} />);

    // 확대한 뒤 원래 배율로 돌아가는 경로가 같은 도구바 안에 있어야 한다(길 잃음 방지).
    expect(screen.getByTestId('label-toolbar-fit')).not.toBeDisabled();
    expect(screen.getByTestId('label-toolbar-zoom-area')).not.toBeDisabled();
  });

  it('영역_확대는_보기_조작이라_회전_중에도_잠기지_않는다', () => {
    renderWithProviders(
      <ToolBar rotation={90} onRotate={vi.fn()} zoomAreaMode={false} onToggleZoomArea={vi.fn()} />,
    );

    // 회전이 잠그는 것은 **그리기 도구**뿐이다 — 보기 조작까지 잠그면 회전 상태에서 확대할 길이 없다.
    expect(screen.getByTestId('label-toolbar-zoom-area')).not.toBeDisabled();
    expect(toolbar().getByRole('button', { name: '바운딩 박스' })).toBeDisabled();
  });

  it('회전이_0이면_그리기_도구가_정상_활성이다_무회귀', () => {
    renderWithProviders(<ToolBar rotation={0} onRotate={vi.fn()} onToggleGrid={vi.fn()} />);

    expect(toolbar().getByRole('button', { name: '바운딩 박스' })).not.toBeDisabled();
    expect(toolbar().getByRole('button', { name: '폴리곤' })).not.toBeDisabled();
  });
});
