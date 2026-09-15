import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { BUILD_CHANNELS } from '@/lib/buildChannel';

import {
  colorScale,
  CONFIG_DESIGN_CHANNELS,
  CONFIGURED,
  configThemeExtendForChannel,
  CONTROL_THEME_EXTEND,
  designChannelOfSourceFile,
  DESIGN_CHANNELS,
  fontPx,
  fontStep,
  PORTAL_THEME_EXTEND,
  themeOf,
} from './designChannel';
import { contrastRatio, WCAG_AA_NORMAL_TEXT } from './wcagContrast';

/**
 * 포털 채널 디자인 토큰 — **DS-002 값 고정 + 관제 축(DS-001)과의 분리**.
 *
 * 진실원은 LogiCraft `DS-002` ITEM 본문이며, `design-tokens/ds002.js` 가 그 투영이다.
 * 이 파일은 ①투영이 ITEM 값과 같은지 ②채널 배선이 실제로 동작하는지 ③두 축의 값이
 * 섞이지 않는지 셋을 고정한다.
 *
 * ★**「포털이 DS-002 로 검사된다」만 단언하면 관제 축이 통째로 빠져도 통과한다.** 그래서
 *   축이 갈리는 자리마다 **두 채널의 값을 함께** 단언한다(한쪽만 보면 반대쪽이 무검사).
 *
 * ⚠ 이 파일에 관제 축의 행 hover 색 리터럴을 적지 않는다 — `tableSurfaceConvention.test.ts`
 *   의 전수 스캔이 `src` 안의 그 hex 하드코딩을 0건으로 강제하고, 이 파일은 그 스캔의
 *   제외 대상이 아니다. 값 비교는 리터럴 대신 **관제 토큰과 다르다**로 표현한다.
 */

const repoRoot = path.resolve(__dirname, '../..');

/* ------------------------------------------------------------------ *
 * ① 채널 배선 — 설정이 실제로 채널로 값을 가르는가
 * ------------------------------------------------------------------ */

describe('디자인 채널 배선', () => {
  it('가드와_설정이_같은_채널_집합을_본다', () => {
    // 두 목록이 갈리면 「설정은 아는데 가드는 모르는 채널」이 생겨 그 채널이 무검사가 된다.
    expect([...CONFIG_DESIGN_CHANNELS].sort()).toEqual([...DESIGN_CHANNELS].sort());
  });

  it('토큰_채널과_라우트_채널이_같은_값_집합을_쓴다', () => {
    // 화면(라우트)과 값(토큰)이 다른 이름으로 갈리면 「화면은 포털인데 색은 관제」가 된다.
    expect([...BUILD_CHANNELS].sort()).toEqual([...DESIGN_CHANNELS].sort());
  });

  it('설정이_채널_선택_함수를_거쳐_extend_를_만든다', () => {
    const source = readFileSync(path.join(repoRoot, 'tailwind.config.js'), 'utf-8');
    // 배선을 지우고 관제 extend 를 직접 꽂으면 이 세 단언이 함께 실패한다.
    expect(source).toMatch(/extend:\s*themeExtendForChannel\(/);
    expect(source).toMatch(/resolveDesignChannel\(\s*process\.env\[DESIGN_CHANNEL_ENV_KEY\]/);
    expect(source).toMatch(/from '\.\/design-tokens\/channel\.js'/);
  });

  it('선택_함수가_채널마다_다른_표를_돌려준다', () => {
    // 관제는 원본 그대로(값이 한 글자도 바뀌지 않는다), 포털은 투영을 거친 표.
    expect(configThemeExtendForChannel('control', CONTROL_THEME_EXTEND)).toBe(CONTROL_THEME_EXTEND);
    expect(configThemeExtendForChannel('portal', CONTROL_THEME_EXTEND)).toEqual(
      PORTAL_THEME_EXTEND(CONTROL_THEME_EXTEND),
    );
    // 미지의 값·미설정은 관제로 떨어진다(fail-closed) — 지금 동작 보존.
    expect(configThemeExtendForChannel('internal', CONTROL_THEME_EXTEND)).toBe(CONTROL_THEME_EXTEND);
    expect(configThemeExtendForChannel(undefined as never, CONTROL_THEME_EXTEND)).toBe(
      CONTROL_THEME_EXTEND,
    );
  });

  it('환경변수_미설정이면_설정이_관제_표를_그대로_쓴다', () => {
    // 시험 실행은 채널을 주지 않는다 — 그때 나오는 것이 관제 표여야 기존 가드가 유효하다.
    expect(CONFIGURED.theme.extend).toBe(CONTROL_THEME_EXTEND);
  });

  /**
   * ★**실제로 산출 프로세스에서 갈리는지**를 본다 — 위 단언들은 함수와 소스만 보므로
   * 「환경변수가 설정 파일까지 닿는가」를 증명하지 못한다.
   *
   * ⚠ 이 축이 조용히 깨지는 형태가 있다: 값을 `.env` 파일로 주면 Vite 가
   *   `import.meta.env` 에만 주입하고 `process.env` 는 건드리지 않아 **화면은 포털인데 색은
   *   관제**가 된다. `npm run build:portal` 이 셸 앞자리로 넘기는 것이 그래서 중요하다.
   */
  it('★산출_프로세스에서_환경변수가_설정까지_닿는다', () => {
    const probe = (channel: string | undefined): string =>
      execFileSync(
        process.execPath,
        [
          '--input-type=module',
          '-e',
          "import cfg from './tailwind.config.js';" +
            'process.stdout.write(cfg.theme.extend.colors.primary.DEFAULT);',
        ],
        {
          cwd: repoRoot,
          encoding: 'utf-8',
          env: channel
            ? { ...process.env, VITE_BUILD_CHANNEL: channel }
            : (() => {
                const e = { ...process.env };
                delete e.VITE_BUILD_CHANNEL;
                return e;
              })(),
        },
      );

    const control = colorScale('control', 'primary').DEFAULT;
    const portal = colorScale('portal', 'primary').DEFAULT;
    expect(control).not.toBe(portal);

    expect(probe('portal')).toBe(portal);
    expect(probe('control')).toBe(control);
    expect(probe(undefined)).toBe(control);
  }, 30_000);
});

/* ------------------------------------------------------------------ *
 * ② DS-002 값 고정 — ITEM 본문 그대로
 * ------------------------------------------------------------------ */

describe('DS-002 값 고정 — 색', () => {
  /** DS-002 `tokens.colors` — 앵커(`hex`)와 11칸 scale 그대로. */
  const DS002_COLORS: Record<string, [string, string[]]> = {
    primary: [
      '#2e45dc',
      ['#eef1fe', '#e0e6fd', '#c4cffb', '#9daef7', '#6e84f1', '#2e45dc', '#2336b8', '#1f2e92', '#1e2b74', '#16205c', '#0f1640'],
    ],
    secondary: [
      '#4757a8',
      ['#eef1fe', '#dee4fa', '#bcc6f0', '#94a3e2', '#6878c8', '#4757a8', '#36448a', '#2a3670', '#1e2b74', '#16205c', '#0f1640'],
    ],
    neutral: [
      '#64748b',
      ['#f8fafc', '#f1f5f9', '#e2e8f0', '#cbd5e1', '#94a3b8', '#64748b', '#475569', '#334155', '#1e293b', '#0f172a', '#020617'],
    ],
    info: [
      '#2e45dc',
      ['#eef1fe', '#e0e6fd', '#c4cffb', '#9daef7', '#6e84f1', '#2e45dc', '#2336b8', '#1f2e92', '#1e2b74', '#16205c', '#0f1640'],
    ],
    warning: [
      '#d97706',
      ['#fffbeb', '#fef3c7', '#fde68a', '#fcd34d', '#fbbf24', '#d97706', '#b45309', '#92400e', '#78350f', '#451a03', '#2e1102'],
    ],
    danger: [
      '#dc2626',
      ['#fef2f2', '#fee2e2', '#fecaca', '#fca5a5', '#f87171', '#dc2626', '#b91c1c', '#991b1b', '#7f1d1d', '#450a0a', '#2c0606'],
    ],
    // ⚠ ITEM 의 success scale 은 600 과 700 이 같은 값이다 — 오타로 보여도 고치지 않는다
    //   (이 저장소는 사본이고 정본은 상대 정의다). 상대에 제기할 대상으로 남긴다.
    success: [
      '#047857',
      ['#ecfdf5', '#d1fae5', '#a7f3d0', '#6ee7b7', '#34d399', '#047857', '#065f46', '#065f46', '#064e3b', '#022c22', '#011a14'],
    ],
  };

  const STEPS = ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950'];

  it.each(Object.keys(DS002_COLORS))('%s 스케일이 DS-002 값 그대로다', (name) => {
    const [anchor, values] = DS002_COLORS[name];
    const scale = colorScale('portal', name === 'neutral' ? 'neutral' : name);
    expect(scale.DEFAULT).toBe(anchor);
    STEPS.forEach((step, i) => {
      expect(scale[step], `${name}-${step}`).toBe(values[i]);
    });
  });

  it('gray_와_neutral_이_같은_slate_표를_가리킨다', () => {
    // 관제 축과 같은 관례 — 한쪽만 갱신되면 화면에 두 종류 회색이 섞인다.
    expect(colorScale('portal', 'gray')).toEqual(colorScale('portal', 'neutral'));
  });

  it('별칭이_스케일의_해당_단을_가리킨다', () => {
    const neutral = colorScale('portal', 'neutral');
    const primary = colorScale('portal', 'primary');
    expect(colorScale('portal', 'accent').DEFAULT).toBe(primary['400']);
    expect(colorScale('portal', 'bgLight').DEFAULT).toBe(neutral['50']);
    expect(colorScale('portal', 'border').DEFAULT).toBe(neutral['200']);
  });

  it('캔버스_도메인_토큰이_정의된다', () => {
    // DS-002 color_usage.neutral: "캔버스는 도메인 토큰 #f4f6fa" — slate 스케일 밖 값이라 별도 키.
    expect(colorScale('portal', 'canvas').DEFAULT).toBe('#f4f6fa');
  });

  it('관제_축에만_있는_범주_팔레트는_그대로_물려받는다', () => {
    // DS-002 에 대응 규칙이 없다(known_gaps "스키마가 담지 못하는 도메인 토큰").
    // 지우면 `bg-category-*` 클래스가 생성되지 않아 색이 조용히 사라진다.
    for (const slot of [1, 2, 3, 4, 5, 6, 7, 8]) {
      expect(colorScale('portal', `category-${slot}`), `category-${slot} 미정의`).toBeDefined();
    }
  });
});

/**
 * 우리 시스템이 **정의한** fontSize 이름만 본다.
 *
 * ⚠ 해석된 theme 에는 Tailwind 기본 스케일(`4xl`~`9xl`, rem 단위)이 함께 남는다 —
 *   `extend` 는 교체가 아니라 병합이기 때문이고, **관제 채널도 똑같다.** 그것은 채널
 *   누수가 아니라 두 채널 공통의 기본값이므로 사다리 판정 대상에서 뺀다. 판정 대상은
 *   「우리가 값을 정한 이름」이다.
 */
const extendFontSize = (channel: 'control' | 'portal'): Record<string, unknown> => {
  const extend =
    channel === 'portal'
      ? PORTAL_THEME_EXTEND(CONTROL_THEME_EXTEND)
      : (CONTROL_THEME_EXTEND as Record<string, unknown>);
  return (extend as { fontSize: Record<string, unknown> }).fontSize;
};

describe('DS-002 값 고정 — 타이포 사다리', () => {
  /** DS-002 `typography_use` 12칸 — [size, weight, lineHeight]. */
  const LADDER: Record<string, [string, string, string]> = {
    'display-xl': ['52px', '600', '1.15'],
    'display-lg': ['40px', '600', '1.2'],
    'display-md': ['30px', '600', '1.3'],
    'title-lg': ['24px', '600', '1.35'],
    'title-md': ['20px', '600', '1.4'],
    'title-sm': ['17px', '600', '1.55'],
    'body-md': ['15px', '400', '1.6'],
    'body-sm': ['14px', '400', '1.55'],
    caption: ['13px', '600', '1.5'],
    button: ['15px', '600', '1.6'],
    label: ['14px', '600', '1.55'],
    'nav-link': ['15px', '500', '1.6'],
  };

  it('DS_002_사다리_12칸이_전부_정의된다', () => {
    for (const [name, [size, weight, lh]] of Object.entries(LADDER)) {
      const step = fontStep('portal', name);
      expect(step, `${name} 미정의`).toBeDefined();
      expect(step[0], `${name} 크기`).toBe(size);
      expect(step[1].fontWeight, `${name} 굵기`).toBe(weight);
      expect(step[1].lineHeight, `${name} 행간`).toBe(lh);
    }
  });

  /**
   * ★DS-002 는 **크기와 굵기의 값 집합 자체가 닫혀 있다** — 사다리 밖 값이 새면 관제 축의
   *   값이 이름만 같은 채 남아 있다는 뜻이다. 별칭·보간 칸까지 전수로 본다.
   */
  it('★포털_fontSize_전량이_DS_002_허용_크기_안에_있다', () => {
    const allowed = new Set([13, 14, 15, 17, 20, 24, 30, 40, 52]);
    const violations: string[] = [];
    for (const [name, value] of Object.entries(extendFontSize('portal'))) {
      const size = Array.isArray(value) ? (value[0] as string) : (value as string);
      const px = Number.parseInt(size, 10);
      if (!allowed.has(px)) violations.push(`${name} = ${size}`);
    }
    expect(
      violations,
      `DS-002 사다리 밖 크기 — 관제 값이 이름만 같은 채 남아 있는 형태다:\n${violations.join('\n')}`,
    ).toEqual([]);
  });

  it('★포털_fontSize_전량의_굵기가_상한_600_을_넘지_않는다', () => {
    // DS-002 key_characteristics: "굵기 상한이 600 이다 — 700 이상을 쓰지 않고 위계는 크기와 색으로".
    const violations: string[] = [];
    for (const [name, value] of Object.entries(extendFontSize('portal'))) {
      const meta = (Array.isArray(value) ? value[1] : undefined) as
        | { fontWeight?: string }
        | undefined;
      if (meta?.fontWeight && Number(meta.fontWeight) > 600) {
        violations.push(`${name} = ${meta.fontWeight}`);
      }
    }
    expect(violations, `굵기 상한 위반:\n${violations.join('\n')}`).toEqual([]);
  });

  /**
   * ★이름이 하나라도 빠지면 **그 클래스가 오류 없이 생성되지 않아** 글자가 브라우저
   *   기본값으로 조용히 떨어진다(관제 축 `secondary-700` 사고와 같은 형태).
   */
  it('★포털_표가_관제_표의_이름을_하나도_빠뜨리지_않는다', () => {
    const controlKeys = Object.keys(extendFontSize('control'));
    const portalKeys = new Set(Object.keys(extendFontSize('portal')));
    const missing = controlKeys.filter((k) => !portalKeys.has(k));
    expect(missing, `포털 표에 없는 이름:\n${missing.join(', ')}`).toEqual([]);
  });
});

describe('DS-002 값 고정 — 모양·음영·간격', () => {
  it('모서리_역할_토큰이_DS_002_값이다', () => {
    const radius = themeOf('portal').borderRadius as Record<string, string>;
    expect(radius.tag).toBe('6px');
    expect(radius.input).toBe('8px');
    expect(radius.tile).toBe('12px');
    expect(radius.surface).toBe('16px');
    expect(radius.panel).toBe('24px');
    expect(radius.pill).toBe('9999px');
  });

  it('음영_두_단계가_DS_002_값이다', () => {
    const shadow = themeOf('portal').boxShadow as Record<string, string>;
    expect(shadow['card-soft']).toBe(
      '0 1px 2px rgba(15,23,42,0.05), 0 10px 30px -12px rgba(30,43,116,0.16)',
    );
    expect(shadow.dropdown).toBe('0 20px 25px -5px rgba(15,23,42,0.10)');
  });

  it('간격_명명_사다리가_DS_002_값이다', () => {
    const spacing = themeOf('portal').spacing as Record<string, string>;
    // 투영 키 `block-gap` ≠ DS-002 토큰명 `block` — 이유는 ds002.js 주석(Tailwind `inline-<간격>` 충돌).
    const LADDER: Record<string, string> = {
      tight: '4px',
      'label-gap': '6px',
      inline: '8px',
      dense: '10px',
      'in-component': '12px',
      'card-gap': '16px',
      'block-gap': '20px',
      column: '24px',
      section: '32px',
      group: '48px',
      'page-section': '80px',
    };
    for (const [name, value] of Object.entries(LADDER)) {
      expect(spacing[name], `spacing.${name}`).toBe(value);
    }
  });

  /**
   * ★Tailwind 4.3 은 `inline-<간격>`(inline-size)·`block-<간격>`(block-size) 함수형 유틸리티를 두고
   *   값을 `--spacing-*` 에서 찾는다. 간격 키가 `block`·`flex`·`grid`·`table` 이면 정적 유틸리티
   *   `inline-block`·`inline-flex`·`inline-grid`·`inline-table` 이 함수형과 겹쳐 **`inline-size` 규칙을
   *   함께 생성**한다 — 포털 실측(2026-09-15)에서 `.inline-block{inline-size:20px}` 이 `width` 를 이겨
   *   라디오가 타원, 트랙 색 막대가 20px 로 떴다. 관제 토큰에는 그 키가 없어 관제 빌드는 정상이었다.
   */
  it('간격_키에_Tailwind_inline_정적_유틸리티_접미가_없다', () => {
    const keys = Object.keys(themeOf('portal').spacing as Record<string, string>);
    const colliding = keys.filter((k) => ['block', 'flex', 'grid', 'table'].includes(k));
    expect(
      colliding,
      `\`inline-<키>\` 정적 유틸리티와 겹치는 간격 키 — inline-size 규칙이 생성된다:\n${colliding.join(', ')}`,
    ).toEqual([]);
    // 양성 대조 — 개명된 키가 실제로 있고 값은 DS-002 그대로다(키 집합이 비어 통과하는 형태를 막는다).
    expect((themeOf('portal').spacing as Record<string, string>)['block-gap']).toBe('20px');
  });

  it('콘텐츠_최대폭과_본문_자간이_DS_002_값이다', () => {
    expect((themeOf('portal').maxWidth as Record<string, string>).wrap).toBe('1200px');
    expect((themeOf('portal').letterSpacing as Record<string, string>).body).toBe('-0.5px');
  });
});

/* ------------------------------------------------------------------ *
 * ③ 두 축이 섞이지 않는다 — 한쪽만 보지 않고 둘을 함께 단언한다
 * ------------------------------------------------------------------ */

describe('★두 축 분리 — DS-001 과 DS-002 가 실제로 다른 값을 준다', () => {
  /**
   * `DS-002.dont_rules`: "토큰 이름이 같다고 값을 같은 것으로 다루지 않는다 — 이쪽은 원본
   * 위에 브랜드 색을 덮어썼다." 이름이 같으므로 **값이 갈리는지**를 축마다 직접 본다.
   *
   * ⚠ 어느 한쪽 값을 상대 축 값으로 되돌리면(양방향 어느 쪽이든) 여기서 잡힌다.
   */
  it('주색이_채널마다_다르다', () => {
    expect(colorScale('portal', 'primary').DEFAULT).not.toBe(
      colorScale('control', 'primary').DEFAULT,
    );
    expect(colorScale('portal', 'primary').DEFAULT).toBe('#2e45dc');
    expect(colorScale('control', 'primary').DEFAULT).toBe('#256EF4');
  });

  it('중립_온도가_채널마다_다르다', () => {
    // 포털은 slate 쿨톤, 관제는 KRDS gray 웜톤.
    expect(colorScale('portal', 'gray')['500']).toBe('#64748b');
    expect(colorScale('control', 'gray')['500']).toBe('#6D7882');
  });

  it('본문_기준_크기가_채널마다_다르다', () => {
    // 포털 15 / 관제 17 — 한 단 차이다.
    expect(fontPx('portal', 'body-md')).toBe(15);
    expect(fontPx('control', 'body-md')).toBe(17);
    // 별칭도 함께 따라가야 한다 — 안 따라가면 `text-sm` 이 포털에서 17px 을 뿜는다.
    for (const alias of ['body', 'sm', 'base']) {
      expect(fontPx('portal', alias), `portal ${alias}`).toBe(15);
      expect(fontPx('control', alias), `control ${alias}`).toBe(17);
    }
  });

  it('★표_헤더_굵기가_채널마다_다르다', () => {
    // DS-002 do_rules: "표 헤더처럼 죽여야 할 요소는 굵기(500)보다 색(slate-400~500)으로 죽인다."
    // 관제 축은 같은 14px 에 600 을 쓴다.
    expect(fontStep('portal', 'table-header')[1].fontWeight).toBe('500');
    expect(fontStep('control', 'table-header')[1].fontWeight).toBe('600');
    // 크기는 두 축이 같다 — 갈리는 것은 굵기다.
    expect(fontPx('portal', 'table-header')).toBe(14);
    expect(fontPx('control', 'table-header')).toBe(14);
  });

  it('굵기_상한이_채널마다_다르다', () => {
    const maxWeight = (channel: 'control' | 'portal'): number => {
      return Math.max(
        ...Object.values(extendFontSize(channel)).map((v) => {
          const meta = (Array.isArray(v) ? v[1] : undefined) as { fontWeight?: string } | undefined;
          return meta?.fontWeight ? Number(meta.fontWeight) : 0;
        }),
      );
    };
    expect(maxWeight('portal')).toBe(600);
    expect(maxWeight('control')).toBeGreaterThan(600);
  });

  it('★행_hover_표면이_채널마다_다르다', () => {
    // 관제 축의 호박색은 DS-002 에서 **경고색(warn 최옅단)** 이라 그대로 물려받으면
    // 「경고로 읽히는 hover」가 된다. 값 리터럴을 적지 않고 관계로 단언한다(위 ⚠ 참조).
    const portalHover = colorScale('portal', 'rowHover').DEFAULT;
    const controlHover = colorScale('control', 'rowHover').DEFAULT;
    expect(portalHover).not.toBe(controlHover);
    // 포털은 중립 최저 단계다(DS-002 미규정 자리 — 시안 근거).
    expect(portalHover).toBe(colorScale('portal', 'neutral')['50']);
    // 그리고 관제 호박색은 포털 경고 팔레트의 최옅단과 같은 값이다 — 그래서 쓸 수 없다.
    expect(controlHover.toLowerCase()).toBe(colorScale('portal', 'warning')['50']);
  });

  it('모서리_체계가_채널마다_다르다', () => {
    const portalRadius = themeOf('portal').borderRadius as Record<string, string>;
    const controlRadius = themeOf('control').borderRadius as Record<string, string>;
    // 포털에만 있는 역할 이름(표면 16 · 패널 24 · 타일 12)
    for (const name of ['surface', 'panel', 'tile', 'tag', 'input', 'pill']) {
      expect(portalRadius[name], `portal radius.${name}`).toBeDefined();
      expect(controlRadius[name], `control radius.${name} 이 생겼다`).toBeUndefined();
    }
  });

  /**
   * ★<b>일반 단(sm·md·lg)도 채널이 값을 정한다.</b>
   *
   * 두 채널이 <b>함께 쓰는 화면</b>이 있기 때문이다 — 라벨링 도구가 그렇고, 그 본문은 역할
   * 이름을 모른 채 일반 단만 쓴다. 일반 단을 관제 값으로 두면 포털 산출물에서 그 화면만
   * KRDS 모서리로 남아 <b>색은 갈리는데 모양은 안 갈리는</b> 상태가 된다.
   *
   * ⚠ 값은 DS-002 자기 사다리에서 온다 — 지어낸 수가 없다는 것을 <b>역할 토큰과 대조해</b>
   *   고정한다(숫자 리터럴만 적으면 나중에 사다리가 바뀌어도 이 시험이 신호를 주지 않는다).
   */
  it('★일반_단도_포털에서는_DS_002_사다리_값이다_관제는_불변', () => {
    const portalRadius = themeOf('portal').borderRadius as Record<string, string>;
    const controlRadius = themeOf('control').borderRadius as Record<string, string>;

    // 포털 — 일반 단이 역할 토큰과 같은 값을 가리킨다(사다리 밖 값을 지어내지 않았다).
    expect(portalRadius.sm).toBe(portalRadius.tag);
    expect(portalRadius.md).toBe(portalRadius.input);
    expect(portalRadius.lg).toBe(portalRadius.tile);
    // 오름차순 보존 — 단이 뒤집히면 중첩 모서리가 눌린 것처럼 보인다.
    expect(Number.parseInt(portalRadius.sm, 10)).toBeLessThan(Number.parseInt(portalRadius.md, 10));
    expect(Number.parseInt(portalRadius.md, 10)).toBeLessThan(Number.parseInt(portalRadius.lg, 10));

    // 관제 — KRDS 값 그대로다. 이 단언이 「관제 영향 0」의 값 축 증거다.
    expect(controlRadius.sm).toBe('4px');
    expect(controlRadius.md).toBe('6px');
    expect(controlRadius.lg).toBe('8px');
    // 음성 대조 — 두 채널이 실제로 갈렸는가(같으면 remap 이 안 걸린 것이다).
    expect(portalRadius.md).not.toBe(controlRadius.md);
  });

  it('음영_체계가_채널마다_다르다', () => {
    const portalShadow = themeOf('portal').boxShadow as Record<string, string>;
    const controlShadow = themeOf('control').boxShadow as Record<string, string>;
    expect(portalShadow['card-soft']).toBeDefined();
    expect(controlShadow['card-soft']).toBeUndefined();
    // 관제 3단은 두 채널 모두에 남는다 — 대응이 서지 않아 임의 매핑하지 않았다.
    expect(portalShadow.sm).toBe(controlShadow.sm);
  });
});

/* ------------------------------------------------------------------ *
 * ④ 대비 — DS-002 자신의 금지 규칙이 성립하는가
 * ------------------------------------------------------------------ */

describe('DS-002 대비 — 표 헤더 색이 500 이어야 하는 이유', () => {
  it('★slate_400_은_흰_면_위_AA_미달이고_500_은_통과한다', () => {
    // DS-002 color_usage.neutral.avoid_for: "흰 배경 위 의미 텍스트에 gray-400 이하(4.5:1 미달)".
    // typography_use 는 표 헤더 색을 slate-400 이라 적지만 같은 ITEM 의 색 사용 규칙이
    // 그것을 금지하고 "메타·표 헤더·라벨 500" 이라 못박는다 — 500 이 ITEM 자신과 정합이다.
    const neutral = colorScale('portal', 'neutral');
    expect(contrastRatio(neutral['400'], '#FFFFFF')).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(neutral['500'], '#FFFFFF')).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
  });

  it('본문_셀_글자색이_포털_행_hover_표면_위에서도_AA_를_만족한다', () => {
    const neutral = colorScale('portal', 'neutral');
    const hover = colorScale('portal', 'rowHover').DEFAULT;
    for (const step of ['600', '700', '800', '900']) {
      expect(
        contrastRatio(neutral[step], hover),
        `neutral-${step} on portal rowHover`,
      ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    }
  });
});

/* ------------------------------------------------------------------ *
 * ⑤ 채널 판정 — 어느 파일이 어느 축인가
 * ------------------------------------------------------------------ */

describe('채널 판정 — 단일 지점', () => {
  it.each([
    ['src/pages/portal/PortalHomePage.tsx', 'portal'],
    ['src/pages/portal/PortalAugmentPage.tsx', 'portal'],
    ['src/features/portal/uploads/components/UploadList.tsx', 'portal'],
    ['src/components/layout/PortalLayout.tsx', 'portal'],
    ['src/components/layout/PortalContentTabs.tsx', 'portal'],
    ['src/lib/portalNav.ts', 'portal'],
    ['src/pages/VideoListPage.tsx', 'control'],
    ['src/components/common/DataTable.tsx', 'control'],
    ['src/components/layout/Lnb.tsx', 'control'],
    ['src/pages/manage/EventTypeManagePage.tsx', 'control'],
  ])('%s → %s', (file, expected) => {
    expect(designChannelOfSourceFile(file)).toBe(expected);
  });

  it('포털_소유_파일이_실제로_그_경로_규칙에_전부_걸린다', () => {
    // 규칙이 열거가 아니라 경로라, 새 포털 파일이 자동으로 포털 축이 된다.
    // 이 케이스는 그 전제(포털 소스가 전부 그 자리에 있다)를 고정한다.
    const files = readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
      .filter((f) => /\.tsx?$/.test(f))
      .map((f) => path.join('src', f));
    const portalFiles = files.filter((f) => designChannelOfSourceFile(f) === 'portal');
    expect(portalFiles.length, '포털 축으로 잡힌 파일이 없다 — 판정이 깨졌다').toBeGreaterThan(20);
    // 음성 대조 — 관제 축도 비어 있지 않아야 한다(전부 포털로 떨어지면 관제가 무검사가 된다).
    expect(files.length - portalFiles.length).toBeGreaterThan(100);
  });
});

/* ------------------------------------------------------------------ *
 * ⑤-b 정적 CSS 의 관제 축 잔재 — 셸 뿌리가 덮는다
 * ------------------------------------------------------------------ */

describe('정적 CSS 잔재 — 포털 셸 뿌리가 본문 색·바탕을 명시한다', () => {
  /**
   * ★`styles/global.css` 의 `body` 규칙은 **관제 축 값**(KRDS 웜 그레이)을 물고 있고, 그
   *   파일은 정적이라 채널로 갈리지 않는다. 그대로 두면 포털 산출물의 본문 글자색·바탕만
   *   웜톤으로 남아 **이름도 클래스도 없이 조용히 두 축이 섞인다**(빌드 산출물의 CSS 를
   *   실제로 훑어 확인한 잔재다).
   *
   *   그 var 들의 **유일한 소비자가 `body`** 이므로, 포털 셸 뿌리가 자기 색·바탕을 명시하면
   *   그 아래 전부가 DS-002 값을 받는다. 명시를 지우면 이 케이스가 실패한다.
   *
   * ⚠ 남는 잔재(이 라운드 범위 밖): `input::placeholder` 의 관제 축 리터럴. 입력들이 대개
   *   `placeholder:text-gray-400` 을 명시해 채널 값으로 덮이지만, 명시하지 않은 자리에서는
   *   웜 그레이가 남는다.
   */
  it('포털_셸_뿌리가_캔버스_바탕과_본문_글자색을_명시한다', () => {
    const src = readFileSync(
      path.join(repoRoot, 'src/components/layout/PortalLayout.tsx'),
      'utf-8',
    );
    // 글자색·자간은 채널과 무관하게 뿌리가 한 번 명시한다.
    expect(src).toMatch(/'flex flex-col [^']*\btext-gray-800\b/);
    expect(src).toMatch(/'flex flex-col [^']*\btracking-body\b/);
    // 바탕은 «두 갈래 모두» 명시한다 — 한쪽이라도 비면 그 채널만 관제 축 회색을 물려받는다.
    // 임베드는 Host 카드(흰 바탕)에 맞춘 흰색, 독립 앱은 DS-002 캔버스.
    expect(src).toMatch(/\bbg-white\b/);
    expect(src).toMatch(/\bmin-h-screen bg-canvas\b/);
  });

  it('그_세_토큰이_포털_채널에서_DS_002_값으로_해석된다', () => {
    // 클래스 이름이 있어도 값이 관제 축이면 아무 소용이 없다 — 값 축을 함께 본다.
    expect(colorScale('portal', 'canvas').DEFAULT).toBe('#f4f6fa');
    expect(colorScale('portal', 'gray')['800']).toBe('#1e293b');
    expect((themeOf('portal').letterSpacing as Record<string, string>).body).toBe('-0.5px');
    // 관제 축에는 이 세 토큰이 없다(있으면 이름만 같은 다른 값이 생긴 것이다).
    expect((themeOf('control').colors as Record<string, unknown>).canvas).toBeUndefined();
    expect((themeOf('control').letterSpacing as Record<string, string>).body).toBeUndefined();
  });
});

/* ------------------------------------------------------------------ *
 * ⑥ 굵기 상한 — 소스 축(포털만 걸리고 관제는 안 걸린다)
 * ------------------------------------------------------------------ */

/** 700 이상 굵기 유틸리티. DS-002 는 이것을 쓰지 않는다. */
const HEAVY_WEIGHT = /\bfont-(bold|extrabold|black)\b/g;

function sourceFilesUnder(): string[] {
  return readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .filter((f) => !/\.test\.tsx?$/.test(f))
    .map((f) => path.join('src', f));
}

function heavyWeightHits(files: string[]): string[] {
  const out: string[] = [];
  for (const rel of files) {
    readFileSync(path.join(repoRoot, rel), 'utf-8')
      .split('\n')
      .forEach((line, i) => {
        for (const m of line.matchAll(HEAVY_WEIGHT)) out.push(`${rel}:${i + 1} — ${m[0]}`);
      });
  }
  return out;
}

describe('★굵기 상한 — 포털 축에만 걸린다', () => {
  it('포털_소유_소스에_700_이상_굵기_유틸이_없다', () => {
    // DS-002 key_characteristics: "굵기 상한이 600 이다 — 700 이상을 쓰지 않고 위계는
    // 크기와 색으로 만든다." 토큰이 막는 것은 사다리 칸의 굵기뿐이고, `font-bold` 같은
    // **별도 유틸은 토큰을 덮으므로** 소스 축에서 따로 막아야 한다.
    const portalFiles = sourceFilesUnder().filter(
      (f) => designChannelOfSourceFile(f) === 'portal',
    );
    expect(portalFiles.length, '포털 소스 0건 — 파일 수집이 깨졌다').toBeGreaterThan(20);
    const hits = heavyWeightHits(portalFiles);
    expect(hits, `DS-002 굵기 상한(600) 위반:\n${hits.join('\n')}`).toEqual([]);
  });

  it('★관제_소유_소스는_이_상한의_대상이_아니다_음성_대조', () => {
    // 채널 축을 지우고 이 규칙을 전 소스에 걸면 관제 화면이 무더기로 실패한다는 사실을
    // 고정한다. 이 케이스가 없으면 「상한을 전역에 걸어도 되겠지」라는 오해를 막지 못하고,
    // 반대로 채널 축이 사라져도 아무도 알아채지 못한다.
    const controlFiles = sourceFilesUnder().filter(
      (f) => designChannelOfSourceFile(f) === 'control',
    );
    expect(heavyWeightHits(controlFiles).length).toBeGreaterThan(0);
  });
});
