// 2026-08-06 — 헤더 [저장] 버튼 제거(좌측 도구바와 중복 진입점)에 따른 계약 고정.
//
// 구 테스트 `LabelHeader_액션_primary_토큰`(헤더 저장 버튼이 KRDS primary 토큰을 쓰는지)은
// 대상 버튼이 사라져 **폐기**했다. 공통 Button 의 토큰 계약은 CommonControlFontSize.test.tsx 가
// 계속 덮는다. 그 자리를 대신해, 버튼 제거로 **증발할 뻔한 진행 표시**(저장 중...)가 상태 문구로
// 이관됐는지와 버튼이 되살아나지 않는지를 고정한다.

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { LabelHeader } from '../LabelHeader';

function renderHeader(overrides: Partial<Parameters<typeof LabelHeader>[0]> = {}) {
  const props = {
    currentFrame: 0,
    totalFrames: 10,
    objectCount: 3,
    dirty: false,
    showHistory: false,
    ...overrides,
  } as Parameters<typeof LabelHeader>[0];
  return render(
    <MemoryRouter>
      <LabelHeader {...props} />
    </MemoryRouter>,
  );
}

describe('LabelHeader 저장 상태 표시', () => {
  it('저장중이면_저장_중_문구를_보여준다_버튼_제거로_증발했던_진행_피드백', () => {
    // given/when: 저장 요청 진행 중
    renderHeader({ dirty: true, saving: true });

    // then: 헤더 중앙 상태가 진행을 알린다(구 버튼 라벨 '저장 중...' 의 이관처).
    const status = screen.getByTestId('label-save-status');
    expect(status).toHaveTextContent('저장 중...');
    // 스크린리더에도 알려야 한다 — 버튼이 없으므로 시각 문구가 유일한 단서다.
    expect(status).toHaveAttribute('aria-live', 'polite');
  });

  it('진행중_표시는_편집중보다_우선한다', () => {
    // 저장은 항상 dirty 상태에서 시작한다 — dirty 를 먼저 보면 진행 표시가 영영 뜨지 않는다.
    renderHeader({ dirty: true, saving: true });
    expect(screen.getByTestId('label-save-status')).not.toHaveTextContent('편집 중');
  });

  it('저장중이_아니면_기존_편집중_저장됨_표시가_그대로다', () => {
    // 표식은 이모지가 아니라 아이콘(점/체크)이라 낭독되는 텍스트는 문구만 남는다.
    const { unmount } = renderHeader({ dirty: true });
    expect(screen.getByTestId('label-save-status')).toHaveTextContent('편집 중');
    unmount();

    renderHeader({ dirty: false });
    expect(screen.getByTestId('label-save-status')).toHaveTextContent('저장됨');
  });

  it('★저장_상태_표식은_이모지가_아니라_아이콘이다', () => {
    // 이모지는 OS·폰트마다 모양이 달라지고 스크린리더가 문자 이름을 읽는다.
    // 상태 구분은 문구가 지고(색만으로 정보 전달 금지) 아이콘은 장식(aria-hidden)이다.
    const { unmount } = renderHeader({ dirty: true });
    let status = screen.getByTestId('label-save-status');
    expect(status.textContent ?? '').not.toMatch(/[●✓]/);
    expect(status.querySelector('svg')).not.toBeNull();
    unmount();

    renderHeader({ dirty: false });
    status = screen.getByTestId('label-save-status');
    expect(status.textContent ?? '').not.toMatch(/[●✓]/);
    expect(status.querySelector('svg')).not.toBeNull();
  });

  it('★헤더에는_저장_버튼이_없다_중복_진입점_제거_회귀가드', () => {
    // 저장 진입점은 캔버스 상단 옵션바 + Ctrl+S 뿐이다. 되살리면 같은 중복이 재발한다.
    renderHeader({ dirty: true });
    expect(screen.queryByTestId('label-header-save')).toBeNull();
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull();
  });

  it('★헤더에는_프레임_위치_표시가_없다_옵션바와_중복_금지', () => {
    // 사양(SCREEN-005 §라벨링 헤더 바)이 `Frame N / 총 프레임` 을 폐기했다 —
    // 위치 표시는 캔버스 상단 옵션바의 프레임 이동 컨트롤이 단독 담당한다.
    // 되살리면 같은 값이 두 곳에 표시돼 한쪽만 갱신되는 어긋남이 생긴다.
    const { container } = renderHeader({ currentFrame: 3 });
    expect(screen.queryByTestId('frame-counter')).toBeNull();
    expect(container.textContent ?? '').not.toMatch(/Frame\s*\d+\s*\//);
  });
});
