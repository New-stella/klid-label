// LabelHeader — 히스토리 진입 폐지 회귀 가드 (R6/D4).
//
// 구 케이스(`onHistoryClick_지정시_토글_버튼_렌더` 등 4건)는 헤더의 [히스토리] 토글 버튼을
// 검증하던 것이라 폐기했다. 그 버튼과 짝이던 props 넷(`showHistory`·`onHistoryClick`·
// `historyOpen`·`onRolledBack`)이 UI-055 에서 폐기 표기로 바뀌었고, 진입점은 라벨링 진입 시 띄우는
// 「시작 버전 선택」 모달 하나로 옮겨졌다.
//
// ⚠ **기능이 사라진 것이 아니라 자리가 옮겨졌다** — 버전 목록·버전 간 diff·작업본 diff·롤백은
//   그 모달 안에 있으며, 도달 가능성은 `features/version/__tests__/StartVersionModal.test.tsx` 의
//   'D4 — 재배치된 프레임 버전 축(4기능 도달 보장)' 이 지킨다. 이 파일은 "헤더에 되돌아오지
//   않는가" 한 축만 본다.

import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { LabelHeader } from '@/features/label/components/LabelHeader';
import { renderWithProviders } from '@/test/renderWithProviders';

function setup(overrides: Partial<Parameters<typeof LabelHeader>[0]> = {}) {
  const props = {
    currentFrame: 0,
    dirty: false,
    ...overrides,
  } as Parameters<typeof LabelHeader>[0];
  return { props, ...renderWithProviders(<LabelHeader {...props} />) };
}

describe('LabelHeader — 히스토리 진입 폐지(R6/D4)', () => {
  it('헤더에_히스토리_진입점을_두지_않는다', () => {
    // given/when
    setup();

    // then: 진입점이 둘로 갈리면 어느 쪽이 최신인지 사용자가 알 수 없다.
    expect(screen.queryByTestId('history-toggle')).toBeNull();
    expect(screen.queryByRole('button', { name: /히스토리/ })).toBeNull();
    expect(screen.queryByRole('link', { name: /히스토리/ })).toBeNull();
  });

  it('폐지_이후에도_저장_상태_표시는_남는다', () => {
    // given/when: 헤더에 저장 버튼이 없으므로 이 문구가 유일한 텍스트 진행 피드백이다.
    setup({ dirty: true });

    // then
    expect(screen.getByTestId('label-save-status')).toHaveTextContent('편집 중');
  });
});
