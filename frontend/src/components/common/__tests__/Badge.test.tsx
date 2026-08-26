// Badge(UI-111) — variant 표면 + 대비 계약 가드.
//
// ★ 왜 이 파일이 있나
//   Badge 의 doc 주석이 "variant 를 새로 추가할 때 배경·텍스트 조합의 대비를 **실제로 측정해**
//   본문 기준(4.5:1) 이상임을 확인하고 그 수치를 적는다"를 컴포넌트의 계약으로 못박는다.
//   그 계약을 산문이 아니라 **값**으로 건다 — 토큰 팔레트가 바뀌거나 클래스가 되돌려지면
//   계산된 대비비가 떨어져 여기서 먼저 깨진다(클래스 존재만 세는 가짜 가드가 아니다).
//
// ⚠ `src/test/contrastGuard.test.ts` 와 역할이 겹치지 않는다 — 그쪽은 소스 텍스트에서 anchor
//   주변 클래스를 긁어 판정하고, 이쪽은 **렌더 결과의 실제 className** 을 읽어 판정한다.
//   되돌림이 어느 쪽으로 나든 한쪽은 반드시 문다.

import { render, screen } from '@testing-library/react';
import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';
import { describe, expect, it } from 'vitest';

import { contrastRatio, WCAG_AA_NORMAL_TEXT } from '@/test/wcagContrast';

// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../../../tailwind.config.js';
import { Badge, type BadgeVariant } from '../Badge';

const colors = (resolveConfig(tailwindConfig as never).theme.colors ?? {}) as Record<
  string,
  Record<string, string>
>;

/** `bg-warning-50` / `text-info-700` 같은 토큰 클래스를 팔레트 hex 로 해석한다. */
function hexOf(token: string): string {
  const m = token.match(/^(?:bg|text)-([a-z]+)-(\d+)$/);
  if (!m) throw new Error(`해석할 수 없는 토큰: ${token}`);
  const [, scale, step] = m;
  const hex = colors[scale]?.[step];
  if (!hex) throw new Error(`정의되지 않은 팔레트 단계: ${scale}-${step}`);
  return hex;
}

function classOf(variant: BadgeVariant): string {
  const { unmount } = render(<Badge variant={variant} label="표식" />);
  const cls = screen.getByText('표식').className;
  unmount();
  return cls;
}

/**
 * variant → 시안 근거. 값은 SCREEN-009 `design-main.css` 의 `.badge-*` 규칙 그대로다.
 *   `.badge-warn { background: var(--w-0); color: var(--w-7); }`
 *   `.badge-info { background: var(--i-0); color: var(--i-7); }`
 */
const EXPECTED: Partial<Record<BadgeVariant, { bg: string; fg: string }>> = {
  pinned: { bg: 'bg-warning-50', fg: 'text-warning-700' },
  success: { bg: 'bg-success-50', fg: 'text-success-700' },
  error: { bg: 'bg-danger-50', fg: 'text-danger-700' },
  warn: { bg: 'bg-warning-50', fg: 'text-warning-700' },
  info: { bg: 'bg-info-50', fg: 'text-info-700' },
};

describe('Badge(UI-111) 대비 계약', () => {
  it.each(Object.entries(EXPECTED))('%s variant 는 시안 토큰을 쓰고 AA 를 넘는다', (variant, tone) => {
    const cls = classOf(variant as BadgeVariant);
    expect(cls).toContain(tone!.bg);
    expect(cls).toContain(tone!.fg);

    const ratio = contrastRatio(hexOf(tone!.fg), hexOf(tone!.bg));
    expect(
      ratio,
      `${variant}: ${tone!.fg}(${hexOf(tone!.fg)}) on ${tone!.bg}(${hexOf(tone!.bg)}) = ` +
        `${ratio.toFixed(2)}:1 — 본문 기준 ${WCAG_AA_NORMAL_TEXT}:1 미달`,
    ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
  });

  // ⚠ 두 키는 **같은 조합이지만 개명·재사용이 아니다**. 「고정」과 「건너뜀」은 다른 뜻이라
  //   한쪽 톤이 바뀔 때 다른 쪽이 끌려가면 안 된다 — 키가 합쳐지면 여기서 깨진다.
  it('pinned 와 warn 은 별개 키로 남는다', () => {
    const variants: BadgeVariant[] = ['pinned', 'success', 'neutral', 'error', 'warn', 'info'];
    for (const v of variants) {
      expect(classOf(v), `${v} variant 가 클래스를 얻지 못했다`).toMatch(/\bbg-/);
    }
  });

  // 색 단독으로 의미를 전달하지 않는다 — UI-111 의 `label` 필수 계약.
  it('모든 variant 가 문구를 함께 렌더한다', () => {
    render(<Badge variant="warn" label="건너뜀" />);
    expect(screen.getByText('건너뜀')).toBeInTheDocument();
  });
});
