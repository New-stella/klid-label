// 캔버스 상단 옵션바 계약 테스트 (SCREEN-005 §캔버스 상단 옵션바).
//
// 사양: "삭제·실행취소·다시실행·저장은 좌측 도구바가 아니라 이 영역에 둔다."
// 저장은 화면의 **유일한 진입점**이며 Ctrl+S 와 동일 동작이다. 잠금(재비식별 대기)과 편집 차단
// (장시간 작업)은 서로 다른 축이며 둘 다 저장을 막는다.

import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { CanvasOptionBar } from '../components/CanvasOptionBar';
import { formatBindingKeys } from '../hooks/labelingKeymap';
import type { Label } from '../types';

const SRC_SN = 555;

const sample: Label = {
  id: 'a1',
  frameNo: 1,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
};

function setup(overrides: Partial<Parameters<typeof CanvasOptionBar>[0]> = {}) {
  const props = {
    frameIndex: 0,
    frameCount: 3,
    onRequestGoTo: vi.fn(),
    srcSn: SRC_SN,
    labels: [] as Label[],
    onRequestSave: vi.fn(),
    ...overrides,
  };
  renderWithProviders(<CanvasOptionBar {...props} />);
  return props;
}

describe('CanvasOptionBar — 구성과 위치', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });
  afterEach(() => {
    useLabelStore.getState().reset();
  });

  it('★삭제_실행취소_다시실행_저장_4개_액션이_모두_옵션바_안에_있다', () => {
    setup();
    const bar = screen.getByTestId('canvas-option-bar');
    for (const name of ['삭제', '실행 취소', '다시 실행', '저장']) {
      expect(bar.contains(screen.getByRole('button', { name }))).toBe(true);
    }
  });

  it('프레임_이동_컨트롤이_옵션바_중앙에_있다', () => {
    setup();
    const bar = screen.getByTestId('canvas-option-bar');
    expect(bar.contains(screen.getByRole('group', { name: '프레임 이동' }))).toBe(true);
  });

  it('하단_타임라인이_스크럽을_담당하므로_옵션바에는_위치_슬라이더를_두지_않는다', () => {
    setup();
    expect(screen.queryByTestId('frame-position-slider')).toBeNull();
  });

  it('접근성_컨테이너는_role_toolbar_이고_저장_버튼에_aria_label_이_있다', () => {
    setup();
    expect(screen.getByRole('toolbar', { name: '캔버스 옵션바' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '저장' })).toBeInTheDocument();
  });

  it('편집_액션_툴팁의_단축키가_키맵과_일치한다', () => {
    setup();
    expect(screen.getByRole('button', { name: '저장' })).toHaveAttribute(
      'title',
      expect.stringContaining(formatBindingKeys('edit.save')),
    );
    expect(screen.getByRole('button', { name: '삭제' })).toHaveAttribute(
      'title',
      expect.stringContaining(formatBindingKeys('label.delete')),
    );
  });
});

describe('CanvasOptionBar — 저장(UI-053)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('저장_클릭은_화면이_소유한_저장_절차에_위임한다', () => {
    const props = setup();
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(props.onRequestSave).toHaveBeenCalledTimes(1);
  });

  it('★잠금이_전달되면_저장이_비활성이다_편집_차단과_다른_축이다', () => {
    const props = setup({ locked: true });
    const save = screen.getByRole('button', { name: '저장' });
    expect(save).toBeDisabled();
    fireEvent.click(save);
    expect(props.onRequestSave).not.toHaveBeenCalled();
  });

  it('잠금이_없으면_저장은_활성이다', () => {
    setup();
    expect(screen.getByRole('button', { name: '저장' })).not.toBeDisabled();
  });

  it('저장중이면_스피너와_aria_busy_로_알리고_중복_클릭을_막는다', () => {
    const props = setup({ saving: true });
    const save = screen.getByTestId('label-toolbar-save');
    expect(save).toHaveAttribute('aria-busy', 'true');
    expect(save).toBeDisabled();
    fireEvent.click(save);
    expect(props.onRequestSave).not.toHaveBeenCalled();
  });

  it('미저장_변경_건수를_배지로_표시한다', () => {
    setup();
    expect(screen.getByRole('button', { name: '저장' }).textContent).not.toContain('(1)');
    act(() => {
      useLabelStore.getState().addLabel(sample);
    });
    expect(screen.getByRole('button', { name: '저장' }).textContent).toContain('(1)');
  });
});

describe('CanvasOptionBar — 실행취소/다시실행(UI-054)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('스택이_비면_둘_다_비활성이고_편집하면_실행취소가_활성이_된다', () => {
    setup();
    expect(screen.getByRole('button', { name: '실행 취소' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다시 실행' })).toBeDisabled();

    act(() => {
      useLabelStore.getState().addLabel(sample);
    });
    expect(screen.getByRole('button', { name: '실행 취소' })).not.toBeDisabled();
  });

  it('실행취소_다시실행_클릭이_실제로_작업본을_되돌리고_다시_적용한다', () => {
    setup();
    act(() => {
      useLabelStore.getState().addLabel(sample);
    });
    expect(useLabelStore.getState().labels).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: '실행 취소' }));
    expect(useLabelStore.getState().labels).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: '다시 실행' }));
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });
});

describe('CanvasOptionBar — 삭제', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('선택_객체를_지운다', () => {
    setup();
    act(() => {
      useLabelStore.getState().addLabel(sample);
      useLabelStore.getState().selectLabel(sample.id);
    });
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });

  it('선택이_없으면_조용히_no_op_이다', () => {
    setup();
    act(() => {
      useLabelStore.getState().addLabel(sample);
    });
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });
});

describe('CanvasOptionBar — 편집 차단(busy)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });
  afterEach(() => {
    useLabelStore.getState().reset();
  });

  it('busy_중에는_저장_삭제_실행취소_프레임이동이_모두_비활성이다', () => {
    setup({ frameIndex: 1, frameCount: 3 });
    act(() => {
      useLabelStore.getState().addLabel(sample);
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '삭제' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '실행 취소' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 프레임' })).toBeDisabled();
  });

  it('busy_가_풀리면_즉시_복구된다', () => {
    setup({ frameIndex: 1, frameCount: 3 });
    act(() => {
      useLabelStore.getState().addLabel(sample);
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    act(() => {
      useLabelStore.getState().cancelBusy();
    });

    expect(screen.getByRole('button', { name: '저장' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: '삭제' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: '실행 취소' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 프레임' })).not.toBeDisabled();
  });
});
