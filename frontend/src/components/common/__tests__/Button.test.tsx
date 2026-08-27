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
  // ── 시안(SCREEN-009 `.btn`) 정합 — 모서리 축 ──────────────────────────
  // `.btn { border-radius: var(--radius-md) }` = 6px 이다. 구 구현은 한 단 위(8px)를 써
  // 같은 화면에서 버튼·카드·모달이 서로 다른 곡률로 보였다.
  it('Button_모서리는_토큰_md_6px_이다', () => {
    render(<Button>확인</Button>);
    const cls = screen.getByRole('button').className.split(/\s+/);
    expect(cls).toContain('rounded-md');
    expect(cls).not.toContain('rounded-lg');
  });

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
    expect(cls).toMatch(/bg-primary-500/);
    expect(cls).toMatch(/min-h-11/);
    expect(cls).toMatch(/text-btn-label/);
  });

  // ★ primary 의 팔레트 **단수**를 고정하는 유일한 지점이다. 화면 테스트가 단수를 각자
  //   적어 두면 팔레트가 한 단 움직일 때마다 색과 무관한 기능 가드가 줄줄이 깨진다
  //   (실제로 BatchFailurePanel 가드가 시안을 인용하면서 시안과 반대 단수를 박고 있었다).
  it('Button_primary_는_시안_btn_primary_단수를_쓴다', () => {
    render(<Button variant="primary">확인</Button>);
    const cls = screen.getByRole('button').className;

    // ⚠ `\b` 로는 기본형과 variant 형이 구분되지 않는다 — `\bbg-primary-600\b` 은
    //   `hover:bg-primary-600` 에도 걸려 아래 «되돌리지 말 것» 이 항상 실패한다.
    //   그래서 기본형(variant 접두 없음)은 앞을 문자열 시작·공백으로 못박는다.
    const bare = (token: string) => new RegExp(`(?:^|\\s)${token}(?![\\w-])`);

    // 시안 `.btn-primary { background: var(--p-5) }` — `--p-5` = #256EF4 = primary-500.
    expect(cls, '시안 --p-5 = primary-500').toMatch(bare('bg-primary-500'));
    // 시안 `.btn-primary:hover:not(:disabled) { background: var(--p-6) }` — #0B50D0 = primary-600.
    expect(cls, '시안 --p-6 = primary-600').toMatch(/\bhover:bg-primary-600\b/);
    // 구 값(한 단씩 어두웠다)으로 되돌리지 말 것.
    expect(cls, '구 기본 600 으로 되돌리지 말 것').not.toMatch(bare('bg-primary-600'));
    expect(cls, '구 호버 700 으로 되돌리지 말 것').not.toMatch(/\bhover:bg-primary-700\b/);
    // 눌림은 계속 "어두워지는" 방향이고 계단은 한 단씩이다(500 → 600 → 700).
    expect(cls, '눌림은 호버보다 한 단 더 어둡다').toMatch(/\bactive:bg-primary-700\b/);
    // 비활성은 기본보다 옅다 — 시안 규정이 없어 구 값을 그대로 둔다.
    expect(cls).toMatch(/\bdisabled:bg-primary-300\b/);
  });

  it('Button_primary_배경은_흰_글자_AA_를_통과한다_경계값', () => {
    const colors = resolveConfig(tailwindConfig as never).theme.colors as Record<
      string,
      Record<string, string>
    >;
    const base = contrastRatio(colors.primary['500']!, '#FFFFFF');
    // 4.55:1 — AA(4.5) 통과이나 **경계**다. primary-500 이 조금이라도 밝아지면 미달이 된다.
    expect(base).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    expect(base, 'AA 경계임을 명시 — 여유가 거의 없다').toBeLessThan(4.7);
    // 호버·눌림은 더 어두워지므로 대비가 함께 올라간다(방향이 안전한 쪽이다).
    expect(contrastRatio(colors.primary['600']!, '#FFFFFF')).toBeGreaterThan(base);
    expect(contrastRatio(colors.primary['700']!, '#FFFFFF')).toBeGreaterThan(
      contrastRatio(colors.primary['600']!, '#FFFFFF'),
    );
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
  // ── 시안(SCREEN-009 `.btn-secondary`) 정합 — 테두리 축 ─────────────────
  // `.btn-secondary { border-color: var(--border-strong) }` = `--n-4`(#8a949e) = gray-400.
  // 구 gray-300 은 흰 배경 위 2.01:1 이라 WCAG 1.4.11(비텍스트 3:1)에 미달했다 —
  // gray-400 은 3.08:1 로 통과하므로 시안 정합과 접근성이 같은 방향이다.
  it('Button_secondary_테두리는_gray_400_이다', () => {
    render(<Button variant="secondary">취소</Button>);
    const cls = screen.getByRole('button').className.split(/\s+/);
    expect(cls).toContain('border-gray-400');
    expect(cls).not.toContain('border-gray-300');
  });

  // ── 시안(SCREEN-009 `.btn-sm`) 정합 — 높이·글자 축 ─────────────────────
  // `.btn-sm { min-height: 36px; font-size: 15px }`. 구 구현은 높이 하한이 아예 없어
  // 내용에 따라 높이가 흔들렸고, 글자도 한 step 작았다(14px).
  it('Button_sm_은_최소높이_36px_과_15px_글자를_갖는다', () => {
    render(<Button size="sm">확인</Button>);
    const cls = screen.getByRole('button').className.split(/\s+/);
    expect(cls).toContain('min-h-9');
    expect(cls).toContain('text-body-sm');
    expect(cls).not.toContain('text-label');
  });
});
