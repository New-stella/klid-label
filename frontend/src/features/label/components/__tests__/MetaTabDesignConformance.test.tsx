// 메타 탭 시안 정합 회귀 가드 — <b>클래스 이름이 아니라 그 클래스가 해석되는 값</b>을 센다.
// [@design SCREEN-019] [@design SD-005] [@design DS-001]
//
// <h3>왜 클래스 문자열만 보면 안 되나</h3>
// 이 저장소는 「색·크기를 클래스 문자열로만 확인」하다 여러 번 뚫렸다 — `twMerge` 가 뒤에 붙은
// 클래스로 앞의 크기 토큰을 <b>삼켜</b> 클래스가 DOM 에서 사라지거나, 클래스는 그대로인데
// 설정의 토큰 값이 바뀌어 화면만 조용히 달라진다. 그래서 이 파일은 두 단계를 모두 센다:
//   ① 실제로 DOM 에 실린 class 속성(= 병합이 끝난 최종 문자열)에서 토큰 클래스를 찾고
//   ② 그 클래스를 <b>해석된 설정</b>(resolveConfig)에 대입해 나온 px·hex 가 시안 값과 같은지 본다.
// jsdom 은 Tailwind CSS 를 적용하지 않아 `getComputedStyle` 로는 px 가 나오지 않는다 — ②가
// 그 자리를 대신하는 가장 가까운 수단이다.
//
// <h3>기대값의 출처</h3>
// 전부 게시된 고충실 시안의 CSS 원문이다(`SD-005` · `.rv-panel-section` · `.meta-collapsible-head` ·
// `.meta-label` · `.meta-readonly-value` · `.meta-help` · `.meta-panel-help` 와 `--n-*`/`--sp-*`
// 변수). 우리가 고른 숫자가 아니라 <b>받아 적은 값</b>이며, 디자인 체계(DS-001) 사다리의 정규
// 단계와도 일치한다(t-title-sm 17/600 · t-body-sm 15/400 · t-label 14/600 · t-caption 14/400).
//
// ⚠ 「본문 17px 이상」 규정은 <b>표 본문 셀</b>에 대한 확정이라 이 패널에 적용되지 않는다.
//   그 축을 근거로 여기 크기를 올리면 시안과 다시 갈린다.

import { render, screen } from '@testing-library/react';
import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../../../../tailwind.config.js';
import { MetaHelpProvider } from '../metaHelp';
import { MetaReadonlyField, MetaSection } from '../MetaSection';

const fullTheme = resolveConfig(tailwindConfig as never).theme;
const fontSizes = fullTheme.fontSize as unknown as Record<
  string,
  [string, { lineHeight?: string; fontWeight?: string }]
>;
const colorScale = fullTheme.colors as unknown as Record<string, Record<string, string>>;
const spacingScale = fullTheme.spacing as unknown as Record<string, string>;
const radiusScale = fullTheme.borderRadius as unknown as Record<string, string>;

/** DOM 에 실제로 실린 class 목록 — 병합이 끝난 뒤의 값이다. */
function classesOf(el: Element): string[] {
  return (el.getAttribute('class') ?? '').split(/\s+/).filter(Boolean);
}

function single(el: Element, re: RegExp, axis: string): string {
  const hit = classesOf(el).filter((c) => re.test(c));
  // 0건이면 twMerge 가 삼켰거나 누가 지운 것이고, 2건 이상이면 어느 쪽이 이기는지 알 수 없다.
  expect(hit, `${axis}: ${el.getAttribute('class')}`).toHaveLength(1);
  return hit[0];
}

/** `text-<step>` → 해석된 [size, {fontWeight}] */
function fontOf(el: Element, axis: string): { size: string; weight: string | undefined } {
  const cls = single(el, /^text-(?!gray-|primary-|danger-|warning-|right$|left$|center$)/, axis);
  const step = cls.slice('text-'.length);
  const entry = fontSizes[step];
  expect(entry, `${axis}: 사다리에 없는 크기 단계 ${step}`).toBeDefined();
  return { size: entry[0], weight: entry[1]?.fontWeight };
}

/** `text-gray-900` 같은 색 클래스 → 해석된 hex */
function colorOf(el: Element, prefix: 'text' | 'bg' | 'border', axis: string): string {
  const cls = single(el, new RegExp(`^${prefix}-gray-\\d+$`), axis);
  const shade = cls.split('-').pop() as string;
  const gray = colorScale.gray;
  const hex = gray[shade];
  expect(hex, `${axis}: 중립색 ${shade} 단이 없다`).toBeDefined();
  return hex.toUpperCase();
}

/** `p-2.5`·`mb-1` 같은 여백 클래스 → 해석된 px */
function spacingOf(el: Element, prefix: string, axis: string): string {
  const cls = single(el, new RegExp(`^${prefix}-[\\d.]+$`), axis);
  const step = cls.slice(prefix.length + 1);
  const rem = spacingScale[step];
  expect(rem, `${axis}: 여백 단계 ${step} 가 없다`).toBeDefined();
  return `${parseFloat(rem) * 16}px`;
}

const DESCRIPTION = '이 구역의 값이 어디서 와서 어디로 나가는지 알리는 한 줄.';

function renderSection(helpVisible: boolean) {
  return render(
    <MetaHelpProvider visible={helpVisible}>
      <MetaSection title="촬영환경 검토" description={DESCRIPTION}>
        <MetaReadonlyField
          testId="probe"
          label="날씨"
          value="맑음"
          help="학습데이터 산출물의 이미지 설명 조달원입니다."
        />
      </MetaSection>
    </MetaHelpProvider>,
  );
}

describe('메타 탭 — 시안(SD-005) 정합', () => {
  it('구역_제목은_17px_600_에_가장_진한_중립색이고_대문자변환과_자간이_없다', () => {
    renderSection(false);
    const head = screen.getByRole('button', { name: /촬영환경 검토/ });

    // 시안 `.meta-collapsible-head` + `.t-title-sm`
    expect(fontOf(head, '구역 제목')).toEqual({ size: '17px', weight: '600' });
    expect(colorOf(head, 'text', '구역 제목')).toBe('#1E2124'); // var(--n-9)

    // ⚠ 구 구현은 `text-label`(14px) + gray-600 에 <b>uppercase + tracking</b>까지 붙어 있었다.
    //   한글에는 대문자가 없어 자간만 남아 글자가 흩어져 보였고 시안에는 둘 다 없다.
    const cls = classesOf(head);
    expect(cls).not.toContain('uppercase');
    expect(cls.some((c) => c.startsWith('tracking-'))).toBe(false);
  });

  it('구역_바깥은_16px_안쪽여백과_아래쪽_구분선이다', () => {
    const { container } = renderSection(false);
    const section = screen.getByTestId('meta-section');

    // 시안 `.rv-panel-section { padding: var(--sp-md); border-bottom: 1px solid var(--n-1) }`
    expect(spacingOf(section, 'p', '구역 안쪽 여백')).toBe('16px');
    expect(colorOf(section, 'border', '구역 구분선')).toBe('#E6E8EA'); // var(--n-1)
    // ★방향이 아래쪽이다 — 구 구현은 <b>위쪽</b>이었고 색도 한 단 진했다(#CDD1D5).
    expect(classesOf(section)).toContain('border-b');
    expect(classesOf(section)).not.toContain('border-t');
    expect(container.querySelector('.border-t')).toBeNull();
  });

  it('필드_라벨은_14px_600_이고_값보다_진하다', () => {
    renderSection(false);
    const label = screen.getByText('날씨');

    // 시안 `.meta-label` + `.t-label` — 구 구현은 caption(400) + gray-500 이라 값보다 흐렸다.
    expect(fontOf(label, '필드 라벨')).toEqual({ size: '14px', weight: '600' });
    expect(colorOf(label, 'text', '필드 라벨')).toBe('#33363D'); // var(--n-8)
    expect(spacingOf(label, 'mb', '필드 라벨 아래 여백')).toBe('4px');
  });

  it('★필드_값은_15px_이고_회색_배경_박스_안에_들어간다', () => {
    renderSection(false);
    const value = screen.getByText('맑음');

    // 시안 `.meta-readonly-value` — <b>박스가 이 화면 체감의 가장 큰 축</b>이다.
    //   박스가 없으면 구역이 카드 덩어리가 아니라 줄 목록으로 읽힌다(사용자 지적의 직접 원인).
    expect(fontOf(value, '필드 값').size).toBe('15px');
    expect(colorOf(value, 'text', '필드 값')).toBe('#33363D'); // var(--n-8)
    expect(colorOf(value, 'bg', '값 박스 배경')).toBe('#F4F5F6'); // var(--n-0)
    expect(spacingOf(value, 'p', '값 박스 안쪽 여백')).toBe('10px');
    expect(radiusScale[single(value, /^rounded-/, '값 박스 모서리').slice('rounded-'.length)])
      .toBe('6px');
  });

  it('값_박스_높이가_시안의_44px_로_떨어진다', () => {
    renderSection(false);
    const value = screen.getByText('맑음');

    // 시안은 이 자리 높이를 `--hit-area`(44px)와 같게 잡는다. 안쪽 여백 10 + 글줄 높이 + 10 이며
    // 글줄 높이는 15px × line-height 1.6 = 24px 다. 세 값 중 하나만 틀어져도 박스가 시안과 어긋난다.
    const step = single(value, /^text-body-/, '필드 값').slice('text-'.length);
    const [size, meta] = fontSizes[step];
    const lineBox = parseFloat(size) * parseFloat(meta.lineHeight ?? '0');
    const pad = parseFloat(spacingOf(value, 'p', '값 박스 안쪽 여백'));
    expect(lineBox).toBe(24);
    expect(pad * 2 + lineBox).toBe(44);
  });

  it('보조_문장은_11px_잔글씨가_아니라_14px_이고_도움말_축이다', () => {
    // ★`help` 는 도움말 축이라 감춘 상태에서는 렌더되지 않는다(사양 SCREEN-019).
    //   ⚠ 값 박스 <b>안</b>의 `hint` 는 값의 일부라 이 게이트를 타지 않는다 — 아래 케이스가 그것을 센다.
    const { unmount } = renderSection(false);
    expect(screen.queryByTestId('meta-field-help')).toBeNull();
    unmount();

    renderSection(true);
    const help = screen.getByTestId('meta-field-help');

    // 시안 `.meta-help` — 구 구현은 11px 이라 사다리 밖이었다(최소 단계가 14px).
    expect(help).toHaveTextContent('학습데이터 산출물의 이미지 설명 조달원입니다.');
    expect(fontOf(help, '보조 문장')).toEqual({ size: '14px', weight: '400' });
    expect(colorOf(help, 'text', '보조 문장')).toBe('#6D7882'); // var(--n-5)
    expect(spacingOf(help, 'mt', '보조 문장 위 여백')).toBe('4px');
  });

  it('★값_박스_안의_보조_표기는_배경이_회색이라_AA_를_통과하는_단을_쓴다', () => {
    // 이 표기(예: 「기본값」)는 값 박스 <b>안</b>에 있어 배경이 흰색이 아니라 gray-50 이다.
    // 바깥 보조 문장과 같은 gray-500 을 쓰면 4.13:1 로 AA(4.5:1)에 못 미친다.
    //
    // ★전역 대비 가드는 이 자리를 <b>못 지킨다</b> — 지금 이 `hint` 를 넘기는 운영 호출부가 없어
    //   (검수 영상축의 「기본값」이 한 줄 묶음 전환으로 사라졌다) 그 가드가 훑는 소스 조합이 성립
    //   하지 않는다. 그래서 규칙이 주석에만 남아 다음 사람이 gray-500 으로 되돌려도 아무것도 울지
    //   않는다. 여기서 <b>직접 렌더해</b> 그 구멍을 막는다.
    render(
      <MetaHelpProvider visible={false}>
        <MetaSection title="개인정보 판정 검토 (영상 축)">
          <MetaReadonlyField testId="probe" label="익명 · 가명 · 개인정보 포함" value="예" hint="기본값" />
        </MetaSection>
      </MetaHelpProvider>,
    );

    const hint = screen.getByText('기본값');
    const box = screen.getByText('예');
    const bg = colorOf(box, 'bg', '값 박스 배경');
    const fg = colorOf(hint, 'text', '값 박스 안 보조 표기');

    const lum = (hex: string) => {
      const ch = [1, 3, 5]
        .map((i) => parseInt(hex.substr(i, 2), 16) / 255)
        .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
      return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    };
    const [hi, lo] = [lum(fg), lum(bg)].sort((a, b) => b - a);
    const ratio = (hi + 0.05) / (lo + 0.05);

    expect(bg).toBe('#F4F5F6');
    expect(ratio, `${fg} on ${bg} = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5);
  });

  it('필드_행_사이_간격이_16px_이다', () => {
    renderSection(false);
    const body = screen.getByTestId('probe').parentElement as HTMLElement;

    // 시안 `.meta-field { margin-bottom: var(--sp-md) }` — 구 구현은 6px 이라 줄이 붙어 보였다.
    expect(spacingOf(body, 'space-y', '필드 행 간격')).toBe('16px');
  });

  // ───────── 구역 설명문은 도움말 뒤에 있다 (2026-09-15 사용자 확정) ─────────

  it('★구역_설명문은_기본으로_보이지_않는다', () => {
    renderSection(false);
    // 폭 360px 탭에서 이 한 문장이 값을 아래로 밀어낸다는 것이 사용자 지적이었다.
    expect(screen.queryByTestId('meta-section-description')).toBeNull();
    expect(screen.queryByText(DESCRIPTION)).toBeNull();
  });

  it('★도움말을_켜면_구역_설명문이_제목_바로_아래에_14px_로_나온다', () => {
    renderSection(true);
    const desc = screen.getByTestId('meta-section-description');

    // 시안 `.meta-panel-help` — 자리는 제목 <b>바로 아래</b>다(구 구현은 구역 <b>끝</b>).
    expect(desc).toHaveTextContent(DESCRIPTION);
    expect(fontOf(desc, '구역 설명문')).toEqual({ size: '14px', weight: '400' });
    expect(colorOf(desc, 'text', '구역 설명문')).toBe('#6D7882'); // var(--n-5)

    const head = screen.getByRole('button', { name: /촬영환경 검토/ });
    const value = screen.getByText('맑음');
    expect(head.compareDocumentPosition(desc) & Node.DOCUMENT_POSITION_FOLLOWING).toBeGreaterThan(0);
    expect(desc.compareDocumentPosition(value) & Node.DOCUMENT_POSITION_FOLLOWING).toBeGreaterThan(0);
  });
});
