import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';

import { contrastRatio, WCAG_AA_NORMAL_TEXT } from '@/test/wcagContrast';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../../../tailwind.config.js';
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

  // ── UI-001 회귀 가드: 계약 확장(추가만, 기존 값 불변) ──────────────────────
  it('Button_success_link_variant_가_존재한다', () => {
    const { rerender } = render(<Button variant="success">완료</Button>);
    // 흰 글자 대비 때문에 DEFAULT(500)가 아니라 600 단을 쓴다(4.57:1 → 5.9:1)
    expect(screen.getByRole('button').className).toMatch(/bg-success-600/);

    rerender(<Button variant="link">더보기</Button>);
    const cls = screen.getByRole('button').className;
    expect(cls).toMatch(/text-primary-600/);
    // 색만으로 링크를 구분하지 않도록 밑줄을 항상 유지
    expect(cls).toMatch(/underline/);
  });

  it('Button_icon_계열_size_는_정사각형이다', () => {
    const { rerender } = render(<Button size="icon" aria-label="닫기" />);
    // icon 은 KRDS 터치 타깃 44px
    expect(screen.getByRole('button').className).toMatch(/h-11/);
    expect(screen.getByRole('button').className).toMatch(/w-11/);

    rerender(<Button size="icon-sm" aria-label="닫기" />);
    expect(screen.getByRole('button').className).toMatch(/h-9/);
    expect(screen.getByRole('button').className).toMatch(/w-9/);
  });

  it('Button_xs_size_가_존재한다', () => {
    render(<Button size="xs">태그</Button>);
    expect(screen.getByRole('button').className).toMatch(/text-caption/);
  });

  it('Button_asChild_는_자식에_스타일만_위임하고_button_을_렌더하지_않는다', () => {
    render(
      <Button asChild variant="link">
        <a href="/videos">영상 목록</a>
      </Button>,
    );
    // button 요소는 생기지 않는다
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    const link = screen.getByRole('link', { name: '영상 목록' });
    expect(link.className).toMatch(/text-primary-600/);
    // button 전용 속성(type/disabled)은 자식으로 내려보내지 않는다
    expect(link).not.toHaveAttribute('type');
    expect(link).not.toHaveAttribute('disabled');
  });

  it('Button_asChild_는_자식의_기존_className_을_보존한다', () => {
    render(
      <Button asChild>
        <a href="/x" className="my-4">
          링크
        </a>
      </Button>,
    );
    expect(screen.getByRole('link').className).toMatch(/my-4/);
  });

  it('Button_기존_variant_size_기본값은_바뀌지_않는다', () => {
    // 계약 확장이 기존 화면을 흔들지 않는지 고정 — 기본은 여전히 primary/md
    render(<Button>확인</Button>);
    const cls = screen.getByRole('button').className;
    expect(cls).toMatch(/bg-primary-600/);
    expect(cls).toMatch(/min-h-11/);
    expect(cls).toMatch(/text-btn-label/);
  });

  // 클래스 문자열 존재만 세는 가짜 가드가 아니다 — tailwind.config.js 를 resolveConfig 해
  // 얻은 실제 hex 로 WCAG 대비비를 계산한다. 토큰 값이 바뀌면 이 테스트가 먼저 깨진다.
  it('Button_신규_variant_색이_AA_대비를_만족한다', () => {
    const colors = resolveConfig(tailwindConfig as never).theme.colors as Record<
      string,
      Record<string, string>
    >;
    // success 는 흰 글자 위 — DEFAULT(500)는 4.57:1 로 경계에 붙어 600 을 쓴다.
    expect(contrastRatio(colors.success['600']!, '#FFFFFF')).toBeGreaterThanOrEqual(
      WCAG_AA_NORMAL_TEXT,
    );
    // link 는 흰 배경 위 텍스트색
    expect(contrastRatio(colors.primary['600']!, '#FFFFFF')).toBeGreaterThanOrEqual(
      WCAG_AA_NORMAL_TEXT,
    );
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
