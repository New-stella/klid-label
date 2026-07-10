import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Button } from '../Button';

describe('Button', () => {
  it('Button_loading_상태에서_disabled_+_스피너_노출', () => {
    render(<Button loading>저장</Button>);
    const btn = screen.getByRole('button');
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('Button_5종_variant_적절한_색상_클래스', () => {
    const { rerender } = render(<Button variant="primary">P</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-primary/);

    rerender(<Button variant="secondary">S</Button>);
    // secondary는 white 배경 + gray border 스타일 (mock tone)
    expect(screen.getByRole('button').className).toMatch(/bg-white|border-gray/);

    rerender(<Button variant="outline">O</Button>);
    expect(screen.getByRole('button').className).toMatch(/border/);

    rerender(<Button variant="danger">D</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-danger/);

    rerender(<Button variant="ghost">G</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-transparent|hover:bg/);
  });

  it('Button_클릭_시_onClick_호출', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<Button onClick={onClick}>확인</Button>);
    await user.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('Button_disabled_일_때_클릭_안됨', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <Button onClick={onClick} disabled>
        확인
      </Button>,
    );
    await user.click(screen.getByRole('button'));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('Button_fullWidth_w_full_클래스', () => {
    render(<Button fullWidth>전체</Button>);
    expect(screen.getByRole('button').className).toMatch(/w-full/);
  });

  it('Button_키보드_포커스시_3px_링과_2px_offset', () => {
    // given/when: 기본 버튼 렌더 (KRDS focus 유틸 적용 대상)
    render(<Button>저장</Button>);
    const cls = screen.getByRole('button').className;
    // then: focus-visible 시 3px ring + 2px offset + primary 색
    expect(cls).toMatch(/focus-visible:ring-\[3px\]/);
    expect(cls).toMatch(/focus-visible:ring-offset-2/);
    expect(cls).toMatch(/focus-visible:ring-primary/);
  });

  it('Button_md_터치타깃_최소_44px', () => {
    // given/when: 기본 사이즈(md) 버튼
    render(<Button>확인</Button>);
    // then: 최소 높이 44px(min-h-11) 확보
    expect(screen.getByRole('button').className).toMatch(/min-h-11/);
  });

  it('Button_danger_hover_어두워짐', () => {
    // given/when: danger variant 버튼
    render(<Button variant="danger">삭제</Button>);
    const cls = screen.getByRole('button').className;
    // then: 눌림 피드백은 primary(600→700→800, 어두워짐)와 동일 방향이어야 한다.
    //  - hover/active 가 밝아지는 옛 `/90 → /80` 반전 패턴은 제거되어야 함.
    expect(cls).not.toMatch(/hover:bg-danger\/90/);
    expect(cls).not.toMatch(/active:bg-danger\/80/);
    // brightness(<100%) 필터로 어두워지는 방향 확보 (active 가 hover 보다 더 어둡다).
    expect(cls).toMatch(/hover:brightness-95/);
    expect(cls).toMatch(/active:brightness-90/);
  });
});
