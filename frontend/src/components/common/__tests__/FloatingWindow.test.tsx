// FloatingWindow — 비모달 창 부품 시험. [@design UI-156]
//
// 이 창이 Modal 과 갈리는 지점만 본다: 백드롭 없음 · 포커스 미구속 · aria-modal=false ·
// 접어도 언마운트하지 않음 · Esc 는 창 안에서만 · 위치와 크기를 브라우저에 기억.

import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { FloatingWindow } from '../FloatingWindow';
import { useFloatingWindowLayout } from '../floatingWindowContext';
import { readStoredRect, writeStoredRect } from '../floatingWindowPosition';

const STORAGE_KEY = 'test.floating.rect';

/**
 * 포인터 이벤트를 좌표와 함께 만든다.
 *
 * ⚠ jsdom 에는 `PointerEvent` 생성자가 없어 `fireEvent.pointerDown(el, {clientX})` 로 넘긴
 * 좌표가 <b>이벤트에 실리지 않는다</b>(실측: clientX 가 undefined 로 들어온다). 그러면 끌기는
 * 「일어났는데 거리가 NaN」이 되어 창이 움직이지 않고, 시험은 마치 끌기 배선이 없는 것처럼
 * 실패한다 — 원인을 제품 코드에서 찾게 되는 함정이다. 좌표를 싣는 `MouseEvent` 로 만들어
 * 타입만 포인터 계열로 준다(React 는 네이티브 타입으로 듣는다).
 */
function pointerEvent(type: string, clientX: number, clientY: number): MouseEvent {
  return new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY });
}

/** 본문이 배치 정보를 읽는지 확인하는 조각 — 칸 쌓임 판정은 창이 소유한다. */
function LayoutProbe() {
  const { stacked, width } = useFloatingWindowLayout();
  return (
    <p data-testid="layout-probe">
      {stacked ? 'stacked' : 'side-by-side'}:{width}
    </p>
  );
}

function Host({
  onRequestClose = vi.fn(),
  storageKey = STORAGE_KEY,
}: {
  onRequestClose?: () => void;
  storageKey?: string;
}) {
  const [folded, setFolded] = useState(false);
  const [maximized, setMaximized] = useState(false);
  return (
    <>
      <button type="button" data-testid="outside-button">
        뒤 화면 버튼
      </button>
      <FloatingWindow
        open
        title="영상 분석 설명 · 이벤트 어노테이션"
        subtitle="부제"
        dirty
        dirtyLabel="저장 안 된 변경"
        folded={folded}
        onFoldChange={setFolded}
        maximized={maximized}
        onMaximizeChange={setMaximized}
        defaultSize={{ width: 1440, height: 810 }}
        minSize={{ width: 560, height: 640 }}
        storageKey={storageKey}
        stackBelowWidth={880}
        onRequestClose={onRequestClose}
        data-testid="win"
        footer={<div data-testid="win-footer">하단 바</div>}
      >
        <LayoutProbe />
        <input aria-label="창 안 입력" />
      </FloatingWindow>
    </>
  );
}

describe('FloatingWindow', () => {
  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });
  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  it('★비모달이다_백드롭이_없고_뒤_화면을_조작할_수_있다', async () => {
    const user = userEvent.setup();
    render(<Host />);

    const win = screen.getByTestId('win');
    expect(win).toHaveAttribute('role', 'dialog');
    // ★이 값은 표시가 아니라 동작 계약이다 — 라벨링 화면의 단축키 억제 판정이
    //   `aria-modal="true"` 만 보고 막으므로, true 로 바꾸면 뒤 화면 단축키가 함께 죽는다.
    expect(win).toHaveAttribute('aria-modal', 'false');
    expect(screen.queryByTestId('modal-backdrop')).not.toBeInTheDocument();

    // 뒤 화면 요소가 눌린다(포커스를 창에 가두지 않는다).
    await user.click(screen.getByTestId('outside-button'));
    expect(screen.getByTestId('outside-button')).toHaveFocus();
  });

  it('제목_부제_미저장_표시와_하단_바를_보인다', () => {
    render(<Host />);
    const win = screen.getByTestId('win');
    expect(win).toHaveAttribute('aria-label', '영상 분석 설명 · 이벤트 어노테이션');
    expect(win).toHaveTextContent('부제');
    expect(screen.getByTestId('floating-window-dirty')).toHaveTextContent('저장 안 된 변경');
    expect(screen.getByTestId('win-footer')).toBeInTheDocument();
  });

  it('★잠시_접어도_언마운트하지_않는다_입력값이_남는다', async () => {
    const user = userEvent.setup();
    render(<Host />);

    await user.type(screen.getByLabelText('창 안 입력'), '작성 중');
    await user.click(screen.getByTestId('floating-window-fold'));

    // 창은 보이지 않지만 DOM 에 남아 값을 들고 있다 — 접기의 목적이 「값을 둔 채 잠깐 뒤를 보는 것」이다.
    expect(screen.getByTestId('win')).not.toBeVisible();
    expect(screen.getByLabelText('창 안 입력')).toHaveValue('작성 중');
  });

  it('크게_누르면_원래_크기_버튼으로_바뀐다', async () => {
    const user = userEvent.setup();
    render(<Host />);

    const button = screen.getByTestId('floating-window-maximize');
    expect(button).toHaveAccessibleName('크게');
    await user.click(button);
    expect(screen.getByTestId('floating-window-maximize')).toHaveAccessibleName('원래 크기');
  });

  it('★Esc_는_창_안에_포커스가_있을_때만_닫기를_요청한다', async () => {
    const onRequestClose = vi.fn();
    const user = userEvent.setup();
    render(<Host onRequestClose={onRequestClose} />);

    // 뒤 화면에서 누른 Esc 는 이 창을 닫지 않는다(캔버스 도구 취소를 누른 사람이 창을 잃지 않는다).
    await user.click(screen.getByTestId('outside-button'));
    await user.keyboard('{Escape}');
    expect(onRequestClose).not.toHaveBeenCalled();

    // 창 안에서 누르면 닫기를 «요청»한다(미저장 확인은 요청을 받은 쪽이 거친다).
    await user.click(screen.getByLabelText('창 안 입력'));
    await user.keyboard('{Escape}');
    expect(onRequestClose).toHaveBeenCalledTimes(1);
  });

  it('닫기_버튼도_같은_요청을_보낸다', async () => {
    const onRequestClose = vi.fn();
    const user = userEvent.setup();
    render(<Host onRequestClose={onRequestClose} />);

    await user.click(screen.getByTestId('floating-window-close'));
    expect(onRequestClose).toHaveBeenCalledTimes(1);
  });

  it('★폭이_좁으면_본문에_칸을_쌓으라고_알린다', () => {
    // given — 기억된 크기가 880 미만인 창(사용자가 좁혀 두고 닫은 경우)
    writeStoredRect(STORAGE_KEY, { x: 20, y: 20, width: 600, height: 700 });

    render(<Host />);

    expect(screen.getByTestId('layout-probe')).toHaveTextContent('stacked:600');
    expect(screen.getByTestId('win')).toHaveAttribute('data-stacked', 'true');
    // 좁은 창에서는 부제를 감춰 제목과 조작 버튼 자리를 지킨다.
    expect(screen.getByTestId('win')).not.toHaveTextContent('부제');
  });

  it('기억된_위치로_열린다', () => {
    writeStoredRect(STORAGE_KEY, { x: 120, y: 64, width: 900, height: 700 });

    render(<Host />);

    const win = screen.getByTestId('win');
    expect(win.style.left).toBe('120px');
    expect(win.style.top).toBe('64px');
    expect(win.style.width).toBe('900px');
    expect(win.style.height).toBe('700px');
  });

  it('★제목_표시줄을_끌어_옮긴_위치를_기억한다', () => {
    writeStoredRect(STORAGE_KEY, { x: 100, y: 100, width: 900, height: 700 });
    render(<Host />);

    // 제목 표시줄을 잡고 오른쪽 아래로 40,30 만큼 끈다.
    fireEvent(screen.getByTestId('floating-window-titlebar'), pointerEvent('pointerdown', 200, 150));
    fireEvent(document, pointerEvent('pointermove', 240, 180));
    fireEvent(document, pointerEvent('pointerup', 240, 180));

    const win = screen.getByTestId('win');
    expect(win.style.left).toBe('140px');
    expect(win.style.top).toBe('130px');
    // 놓는 순간 기억한다 — 다음에 열 때 같은 자리다.
    expect(readStoredRect(STORAGE_KEY)).toEqual({ x: 140, y: 130, width: 900, height: 700 });
  });

  it('★조작_버튼_위에서_시작한_포인터는_끌기가_아니다', () => {
    writeStoredRect(STORAGE_KEY, { x: 100, y: 100, width: 900, height: 700 });
    render(<Host />);

    // 닫기 버튼을 누르려다 손이 조금 움직여도 창이 따라 움직이면 안 된다.
    fireEvent(screen.getByTestId('floating-window-close'), pointerEvent('pointerdown', 900, 110));
    fireEvent(document, pointerEvent('pointermove', 940, 140));
    fireEvent(document, pointerEvent('pointerup', 940, 140));

    expect(screen.getByTestId('win').style.left).toBe('100px');
  });

  it('★모서리를_끌면_크기가_바뀌고_최소_크기_아래로는_줄지_않는다', () => {
    writeStoredRect(STORAGE_KEY, { x: 20, y: 20, width: 900, height: 700 });
    render(<Host />);

    // 왼쪽 위로 크게 끌어 최소 크기 아래로 줄이려 한다.
    fireEvent(screen.getByTestId('floating-window-resize'), pointerEvent('pointerdown', 920, 720));
    fireEvent(document, pointerEvent('pointermove', 300, 100));
    fireEvent(document, pointerEvent('pointerup', 300, 100));

    const win = screen.getByTestId('win');
    expect(win.style.width).toBe('560px');
    expect(win.style.height).toBe('640px');
  });
});
