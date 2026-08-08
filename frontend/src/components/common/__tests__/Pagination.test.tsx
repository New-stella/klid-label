import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Pagination, buildPageSlots, pageCountOf } from '../Pagination';

describe('Pagination', () => {
  it('Pagination_페이지_번호를_누르면_그_페이지로_이동을_요청한다', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Pagination page={0} totalPages={25} onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: '2페이지' }));
    expect(onChange).toHaveBeenCalledWith(1);
  });

  // ⚠ 구 테스트는 '첫 페이지'/'마지막 페이지' 전용 버튼이 disabled 인지 단언해
  //   **사양이 금지한 패턴을 정답으로 박제**하고 있었다(UI-008: 양끝 번호가 항상 보이므로
  //   처음·마지막 전용 버튼은 두지 않는다). 그 단언을 "버튼이 없다"로 뒤집어 정정한다.
  it('Pagination_첫_페이지에서_이전_disabled_처음버튼은_없다', () => {
    render(<Pagination page={0} totalPages={10} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '첫 페이지' })).not.toBeInTheDocument();
  });

  it('Pagination_마지막_페이지에서_다음_disabled_마지막버튼은_없다', () => {
    render(<Pagination page={9} totalPages={10} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '마지막 페이지' })).not.toBeInTheDocument();
  });

  it('Pagination_aria_current_page_현재_페이지에_표시', () => {
    render(<Pagination page={2} totalPages={10} onChange={() => {}} />);
    const cur = screen.getByRole('button', { name: '3페이지' });
    expect(cur).toHaveAttribute('aria-current', 'page');
  });

  // ── UI-008 회귀 가드: prop 계약 ───────────────────────────────────────────
  // 구 계약은 총 건수(totalElements)와 페이지 크기(size)를 받아 페이지 수를 **되계산**했다.
  // 그러면 페이지 크기 규칙을 이 컨트롤이 또 알아야 하고, 서버가 이미 준 totalPages 와
  // 어긋날 수 있다(두 번째 진실원). 사양대로 페이지 수를 그대로 받는다.

  it('Pagination_전체_페이지_수를_그대로_받아_그린다_건수로_되계산하지_않는다', () => {
    // given: 서버가 준 값은 3페이지 — 컨트롤은 건수·페이지 크기를 알지 못한다
    render(<Pagination page={0} totalPages={3} onChange={() => {}} />);

    // then: 3개 번호가 그대로 노출되고 그 밖은 없다
    expect(screen.getByRole('button', { name: '1페이지' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '3페이지' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '4페이지' })).not.toBeInTheDocument();
  });

  it('Pagination_총_건수_요약은_이_컨트롤이_갖지_않는다', () => {
    // given: 구 구현은 "1-10 / 총 245건" 을 컨트롤 안에서 그렸다. 그 표기는 총 건수와
    // 페이지 크기를 함께 받아야만 성립하므로 사양의 prop 계약과 공존할 수 없다.
    render(<Pagination page={0} totalPages={25} onChange={() => {}} />);

    // then: 건수 표기가 없다(필요한 화면이 화면 쪽에서 소유한다)
    expect(screen.queryByText(/총 .*건/)).toBeNull();
  });

  it('Pagination_0건이면_1페이지로_세고_컨트롤_자리는_유지된다', () => {
    // given: 서버는 빈 목록에 totalPages=0 을 내려보낸다. 그대로 0으로 두면 노출할 번호가
    // 하나도 없어 컨트롤이 통째로 사라지고 버튼 자리가 흔들린다.
    render(<Pagination page={0} totalPages={0} onChange={() => {}} />);

    // then: 1페이지 하나만 있고 양쪽 이동은 잠긴다
    expect(screen.getByRole('button', { name: '1페이지' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '2페이지' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
  });

  it('Pagination_전체가_1페이지면_번호는_하나뿐이고_이동이_모두_잠긴다', () => {
    render(<Pagination page={0} totalPages={1} onChange={() => {}} />);
    expect(screen.getByRole('button', { name: '1페이지' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '2페이지' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
  });

  it('Pagination_마지막_페이지에_도달하면_다음이_잠기고_이동_요청도_없다', async () => {
    // given: 마지막 페이지(5페이지 중 5번째)
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Pagination page={4} totalPages={5} onChange={onChange} />);

    // then: 마지막이므로 다음은 잠겨 있고 눌러도 요청이 나가지 않는다
    const next = screen.getByRole('button', { name: '다음 페이지' });
    expect(next).toBeDisabled();
    await user.click(next);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('Pagination_범위를_벗어난_현재_페이지는_마지막_페이지로_가둔다', () => {
    // given: 총량이 줄어 page 가 범위 밖인 순간(보던 중 총건수 감소)
    render(<Pagination page={99} totalPages={3} onChange={() => {}} />);

    // then: 마지막 페이지를 현재로 보고, 존재하지 않는 번호를 현재로 표시하지 않는다
    expect(screen.getByRole('button', { name: '3페이지' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
  });

  // ── UI-008 회귀 가드: 말줄임 축약 ─────────────────────────────────────────
  // 구 구현은 고정 5칸 창을 미끄러뜨려서 페이지가 많으면 **끝 페이지로 가는 경로가 사라졌다**.

  it('Pagination_페이지가_많아도_마지막_페이지_번호가_항상_보인다', () => {
    // given: 100페이지 중 50번째(0-based 49)
    render(<Pagination page={49} totalPages={100} onChange={() => {}} />);
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
      <Pagination page={49} totalPages={100} onChange={() => {}} />,
    );
    const ellipses = container.querySelectorAll('[aria-hidden="true"]');
    // 앞뒤 두 군데가 접힌다
    expect(
      Array.from(ellipses).filter((el) => el.textContent === '…').length,
    ).toBe(2);
  });

  it('Pagination_같은_페이지_클릭_시_콜백을_부르지_않는다', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Pagination page={2} totalPages={10} onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: '3페이지' }));
    expect(onChange).not.toHaveBeenCalled();
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

  // 클라이언트 측 페이징 화면(프리셋 관리·증강 프레임 쌍)이 호출부마다 ceil 을 다시 쓰지
  // 않도록 규칙을 한 곳에 둔다 — 0건 처리가 갈리면 "0페이지 중 1페이지" 가 생긴다.
  describe('pageCountOf', () => {
    it('0건은_빈_1페이지로_센다_페이저_규칙과_같은_값이다', () => {
      expect(pageCountOf(0, 10)).toBe(1);
    });

    it('나머지가_있으면_올림한다', () => {
      expect(pageCountOf(1, 10)).toBe(1);
      expect(pageCountOf(10, 10)).toBe(1);
      expect(pageCountOf(11, 10)).toBe(2);
      expect(pageCountOf(245, 10)).toBe(25);
    });

    it('페이지_크기가_비정상이면_1페이지로_떨어뜨린다_0으로_나누지_않는다', () => {
      expect(pageCountOf(100, 0)).toBe(1);
      expect(pageCountOf(100, -5)).toBe(1);
      expect(pageCountOf(Number.NaN, 10)).toBe(1);
    });
  });
});
