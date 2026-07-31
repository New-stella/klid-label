// Phase 3 DEV_FIX M1 — 차단 안내 dedupe 는 **화면 단위 단일 정책**이다.
//
// 훅 인스턴스마다 dedupe 상태를 따로 들면, 같은 화면의 서로 다른 소비처(캔버스 / 속성 패널 /
// 배타 실행 래퍼)가 같은 사유로 연달아 거부될 때 같은 문구가 여러 번 뜬다.

import { act, render } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { useUiStore } from '@/stores/useUiStore';

import { useBlockNotice } from '../hooks/useBlockNotice';

const REASON = '저장 진행 중입니다. 완료 후 다시 시도하세요.';
const OTHER_REASON = '불러오기 진행 중입니다. 완료 후 다시 시도하세요.';

type Push = (message: string) => void;

let pushFromCanvas: Push | undefined;
let pushFromPanel: Push | undefined;

/** 같은 화면 안의 서로 다른 소비처 두 곳 — 각자 훅을 부른다(프로덕션 배선과 동일). */
function TwoConsumers() {
  pushFromCanvas = useBlockNotice();
  pushFromPanel = useBlockNotice();
  return null;
}

describe('useBlockNotice — 화면 단위 dedupe', () => {
  beforeEach(() => {
    useUiStore.setState({ toasts: [] });
    useUiStore.getState().resetBlockNotice();
    pushFromCanvas = undefined;
    pushFromPanel = undefined;
  });

  it('같은_사유의_차단_안내는_소비처가_달라도_중복되지_않는다', () => {
    render(<TwoConsumers />);

    act(() => {
      pushFromCanvas?.(REASON);
      pushFromPanel?.(REASON);
    });

    expect(useUiStore.getState().toasts).toHaveLength(1);
  });

  it('사유가_다르면_각각_안내된다', () => {
    render(<TwoConsumers />);

    act(() => {
      pushFromCanvas?.(REASON);
      pushFromPanel?.(OTHER_REASON);
    });

    expect(useUiStore.getState().toasts).toHaveLength(2);
  });
});
