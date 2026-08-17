// 사양 SCREEN-037 — 공지 **수정** 폼의 제목·내용 도움말 캡션 회귀 가드.
//
// 확정 사양: 제목 `필수 · 최대 200자` / 내용 `필수`.
// 작성 폼(SCREEN-036 · NoticeCreateForm)이 이미 같은 정보를 도움말로 알리고 있으므로
// 같은 정보를 두 화면이 다르게 말하지 않도록 문구 톤을 맞춘다.
//
// ⚠ 이번 확정 범위는 **도움말 캡션**이다 — 제목 문자 수 카운터(UI-115)는 여전히 작성 화면 전용.

import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';

import { NoticeFormSection } from '@/features/notice/components/NoticeFormSection';

function renderEditForm() {
  return render(
    <NoticeFormSection
      defaultValues={{ title: '점검 안내', content: '02:00 점검', pinned: false }}
      submitLabel="저장"
      onSubmit={vi.fn()}
      onCancel={vi.fn()}
    />,
  );
}

/** 이 노드가 시각적으로 숨겨진 영역(sr-only) 안에 있지 않은지 — jsdom 사각 보완. */
function isVisuallyPresent(el: HTMLElement): boolean {
  return el.closest('.sr-only') === null;
}

describe('NoticeFormSection (공지 수정 폼) — 도움말 캡션 (사양 SCREEN-037)', () => {
  it('수정_폼의_제목과_내용에_도움말_캡션이_보인다', () => {
    // given / when
    renderEditForm();

    // then: 제목은 필수 + 최대 200자, 내용은 필수임을 캡션이 알린다.
    const titleHelp = screen.getByTestId('notice-title-description');
    expect(titleHelp).toHaveTextContent('필수');
    expect(titleHelp).toHaveTextContent('최대 200자');
    expect(isVisuallyPresent(titleHelp)).toBe(true);

    const contentHelp = screen.getByTestId('notice-content-description');
    expect(contentHelp).toHaveTextContent('필수');
    expect(isVisuallyPresent(contentHelp)).toBe(true);
  });

  it('도움말이_입력과_프로그램적으로_연결된다', () => {
    // given / when
    renderEditForm();

    // then: 시각적으로만 있으면 스크린리더 사용자에게 전달되지 않는다.
    const title = screen.getByDisplayValue('점검 안내');
    const titleDescribedBy = title.getAttribute('aria-describedby') ?? '';
    expect(titleDescribedBy.split(/\s+/)).toContain(
      screen.getByTestId('notice-title-description').id,
    );

    const content = screen.getByDisplayValue('02:00 점검');
    const contentDescribedBy = content.getAttribute('aria-describedby') ?? '';
    expect(contentDescribedBy.split(/\s+/)).toContain(
      screen.getByTestId('notice-content-description').id,
    );
  });

  it('수정_폼에는_제목_문자수_카운터를_두지_않는다', () => {
    // given / when — 이번 확정 범위는 도움말뿐이며 카운터는 작성 화면 전용이다.
    const { container } = renderEditForm();

    // then: `N/200` 형태 카운터가 없다.
    expect(within(container).queryByText(/^\d+\/200$/)).not.toBeInTheDocument();
  });
});
