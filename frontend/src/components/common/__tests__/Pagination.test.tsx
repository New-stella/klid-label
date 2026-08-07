import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Pagination, buildPageSlots } from '../Pagination';

describe('Pagination', () => {
  it('Pagination_총_245건_페이지_이동', async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    render(
      <Pagination page={0} size={10} totalElements={245} onPageChange={onPageChange} />,
    );
    expect(screen.getByText(/1-10 \/ 총 245건/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '2페이지' }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  // ⚠ 구 테스트는 '첫 페이지'/'마지막 페이지' 전용 버튼이 disabled 인지 단언해
  //   **사양이 금지한 패턴을 정답으로 박제**하고 있었다(UI-008: 양끝 번호가 항상 보이므로
  //   처음·마지막 전용 버튼은 두지 않는다). 그 단언을 "버튼이 없다"로 뒤집어 정정한다.
  it('Pagination_첫_페이지에서_이전_disabled_처음버튼은_없다', () => {
    render(
      <Pagination page={0} size={10} totalElements={100} onPageChange={() => {}} />,
    );
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '첫 페이지' })).not.toBeInTheDocument();
  });

  it('Pagination_마지막_페이지에서_다음_disabled_마지막버튼은_없다', () => {
    render(
      <Pagination page={9} size={10} totalElements={100} onPageChange={() => {}} />,
    );
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '마지막 페이지' })).not.toBeInTheDocument();
  });

  it('Pagination_aria_current_page_현재_페이지에_표시', () => {
    render(
      <Pagination page={2} size={10} totalElements={100} onPageChange={() => {}} />,
    );
    const cur = screen.getByRole('button', { name: '3페이지' });
    expect(cur).toHaveAttribute('aria-current', 'page');
  });

  // ── UI-008 회귀 가드: 말줄임 축약 ─────────────────────────────────────────
  // 구 구현은 고정 5칸 창을 미끄러뜨려서 페이지가 많으면 **끝 페이지로 가는 경로가 사라졌다**.

  it('Pagination_페이지가_많아도_마지막_페이지_번호가_항상_보인다', () => {
    // given: 100페이지 중 50번째(0-based 49)
    render(
      <Pagination page={49} size={10} totalElements={1000} onPageChange={() => {}} />,
    );
    // then: 양끝 번호가 살아 있어 끝 페이지 도달 경로가 존재한다
    expect(screen.getByRole('button', { name: '1페이지' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '100페이지' })).toBeInTheDocument();
    // 현재 앞뒤 1칸
    expect(screen.getByRole('button', { name: '49페이지' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '51페이지' })).toBeInTheDocument();
    // 사이는 접혀 있다 — 멀리 떨어진 번호는 노출되지 않는다
    expect(screen.queryByRole('button', { name: '25페이지' })).not.toBeInTheDocument();
  });

  it('Pagination_말줄임은_보조기술에_읽히지_않는다', () => {
    const { container } = render(
      <Pagination page={49} size={10} totalElements={1000} onPageChange={() => {}} />,
    );
    const ellipses = container.querySelectorAll('[aria-hidden="true"]');
    // 앞뒤 두 군데가 접힌다
    expect(
      Array.from(ellipses).filter((el) => el.textContent === '…').length,
    ).toBe(2);
  });

  it('Pagination_같은_페이지_클릭_시_콜백을_부르지_않는다', async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    render(
      <Pagination page={2} size={10} totalElements={100} onPageChange={onPageChange} />,
    );
    await user.click(screen.getByRole('button', { name: '3페이지' }));
    expect(onPageChange).not.toHaveBeenCalled();
  });

  describe('buildPageSlots 경계', () => {
    it('전체_페이지가_적으면_전부_노출하고_말줄임이_없다', () => {
      expect(buildPageSlots(0, 1)).toEqual([0]);
      expect(buildPageSlots(1, 3)).toEqual([0, 1, 2]);
      expect(buildPageSlots(2, 5)).toEqual([0, 1, 2, 3, 4]);
    });

    it('빠지는_페이지가_정확히_1개면_말줄임_대신_그_번호를_보여준다', () => {
      // 0 · [1] · 2,3,4 · [5] · 6  → 접을 것이 각각 1개뿐이므로 번호를 그대로 남긴다
      expect(buildPageSlots(3, 7)).toEqual([0, 1, 2, 3, 4, 5, 6]);
    });

    it('빠지는_페이지가_2개_이상이면_말줄임으로_접는다', () => {
      expect(buildPageSlots(5, 20)).toEqual([0, 'ellipsis', 4, 5, 6, 'ellipsis', 19]);
    });

    it('현재가_양끝이면_그_쪽에는_말줄임이_생기지_않는다', () => {
      expect(buildPageSlots(0, 20)).toEqual([0, 1, 'ellipsis', 19]);
      expect(buildPageSlots(19, 20)).toEqual([0, 'ellipsis', 18, 19]);
    });
  });
});
