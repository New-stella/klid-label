import { readFileSync } from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

const fullConfig = resolveConfig(tailwindConfig as never);
const colors = fullConfig.theme.colors as Record<string, unknown>;
const fontFamily = fullConfig.theme.fontFamily as Record<string, string[]>;
const fontSize = fullConfig.theme.fontSize as Record<string, unknown>;
const borderRadius = fullConfig.theme.borderRadius as Record<string, string>;
const boxShadow = fullConfig.theme.boxShadow as Record<string, string>;

const asObj = (v: unknown): Record<string, unknown> => v as Record<string, unknown>;
const repoRoot = path.resolve(__dirname, '../..');

describe('KRDS 디자인 토큰 — 색상', () => {
  // 진실원: LogiCraft DS-001 v6 — data.tokens.colors.primary(KRDS 공식 토큰 CSS 그대로,
  // 2026-08-06 교체). 구 값은 DS-001 known_gaps 가 "정본에 한 건도 등장하지 않는 출처
  // 미기록 값"이라 명시한 것이라 폐기됐다.
  it('primary가_KRDS_정본_블루_256EF4로_교체된다', () => {
    // given: KRDS primary 팔레트
    const primary = asObj(colors.primary);
    // then: 값이 신규 정본과 일치하면 구 출처미기록 값과는 동시에 같을 수 없다(상호배타)
    expect(primary.DEFAULT).toBe('#256EF4');
    expect(primary['500']).toBe('#256EF4');
    expect(primary['50']).toBe('#ECF2FE');
    expect(primary['950']).toBe('#020F27');
  });

  it('mock_blue_2563EB는_더이상_primary에_없다', () => {
    // 회귀: 구 mock 톤이 남아있으면 실패
    expect(JSON.stringify(colors.primary)).not.toContain('#2563EB');
  });

  it('info가_KRDS_정본_스케일(primary와_별개_축)로_교체된다', () => {
    // DS-001 v6 semantic.info — primary 와 같은 축 취급으로 전체 스케일을 채택했다
    const info = asObj(colors.info);
    expect(info.DEFAULT).toBe('#0B78CB');
    expect(info['500']).toBe('#0B78CB');
    expect(info['700']).toBe('#085691');
    // 회귀: info 가 더이상 primary 와 같은 단일 값이 아니다(구 코드는 info=primary 였다)
    expect(info.DEFAULT).not.toBe(asObj(colors.primary).DEFAULT);
  });

  it('secondary_가_관제_3단_값을_갖는다', () => {
    // DS-001 secondary — 구 단일값 #1850D7 은 KRDS 정본에 없는 출처 미기록 값이라 폐기.
    const secondary = asObj(colors.secondary);
    expect(secondary.DEFAULT).toBe('#346FB2');
    expect(secondary['50']).toBe('#EEF2F7');
    expect(secondary['500']).toBe('#346FB2');
    expect(secondary['600']).toBe('#1C589C');
    // 회귀: 구 단일값이 남아있으면 실패
    expect(JSON.stringify(colors.secondary)).not.toContain('#1850D7');
  });

  it('secondary_11단이_모두_정의돼_중간단계가_조용히_비지_않는다', () => {
    // 진실원: DS-001 tokens.colors.secondary scale(11개) — 인덱스 순서대로 50…950.
    // 3단(50/500/600)만 있던 동안 `secondary-700` 같은 클래스는 Tailwind 가 아예
    // 생성하지 않아 **색이 안 붙는데 오류도 안 나는** 상태였다(UI-110 작업자 배지).
    const SECONDARY: Record<string, string> = {
      DEFAULT: '#346FB2',
      '50': '#EEF2F7',
      '100': '#D6E0EB',
      '200': '#BACBDE',
      '300': '#90B0D5',
      '400': '#6B96C7',
      '500': '#346FB2',
      '600': '#1C589C',
      '700': '#063A74',
      '800': '#052B57',
      '900': '#031F3F',
      '950': '#02162C',
    };
    const secondary = asObj(colors.secondary);
    for (const [step, hex] of Object.entries(SECONDARY)) {
      expect(secondary[step], `secondary-${step} 미정의/불일치`).toBe(hex);
    }
  });

  it('warning_danger_success가_KRDS_정본_스케일(info와_동일_구조)로_교체된다', () => {
    // DS-001 v6 semantic.warn/error/success — info 와 같은 축 취급으로 전체 스케일을 채택했다.
    // 정본 명칭은 warn/error 이나 코드 키는 기존 호출부 보존을 위해 warning/danger 를 유지한다.
    const warning = asObj(colors.warning);
    expect(warning.DEFAULT).toBe('#9E6A00');
    expect(warning['500']).toBe('#9E6A00');
    expect(warning['700']).toBe('#614100');

    const danger = asObj(colors.danger);
    expect(danger.DEFAULT).toBe('#DE3412');
    expect(danger['500']).toBe('#DE3412');
    expect(danger['700']).toBe('#8A240F');

    const success = asObj(colors.success);
    expect(success.DEFAULT).toBe('#228738');
    expect(success['500']).toBe('#228738');
    expect(success['700']).toBe('#285D33');
  });

  it('mock_구값(C25700_D1322C_117C44)은_더이상_없다', () => {
    // 회귀: 교체 전 단일값 톤이 남아있으면 실패
    expect(JSON.stringify(colors.warning)).not.toContain('#C25700');
    expect(JSON.stringify(colors.danger)).not.toContain('#D1322C');
    expect(JSON.stringify(colors.success)).not.toContain('#117C44');
  });

  // ── 중립색(gray / neutral) ────────────────────────────────────────────────
  // 진실원: LogiCraft DS-001 tokens.colors.neutral (KRDS 11단). DS-001 known_gaps 가
  // "코드의 회색은 stock Tailwind gray 이며 tokens.colors.neutral 과 다르다"고 명시한
  // 그 갭을 닫는다 — 호출부(gray-* 1,228곳)는 치환하지 않고 `gray` 를 신설해 값으로 덮는다.
  /** DS-001 tokens.colors.neutral — gray/neutral 두 이름이 공유하는 단일 정본 표. */
  const KRDS_NEUTRAL: Record<string, string> = {
    DEFAULT: '#464C53',
    '50': '#F4F5F6',
    '100': '#E6E8EA',
    '200': '#CDD1D5',
    '300': '#B1B8BE',
    '400': '#8A949E',
    '500': '#6D7882',
    '600': '#58616A',
    '700': '#464C53',
    '800': '#33363D',
    '900': '#1E2124',
    '950': '#131416',
  };

  it('neutral이_KRDS_회색_스케일이다', () => {
    const neutral = asObj(colors.neutral);
    for (const [step, hex] of Object.entries(KRDS_NEUTRAL)) {
      expect(neutral[step], `neutral-${step}`).toBe(hex);
    }
  });

  it('gray_11키가_모두_정의돼_Tailwind_기본_팔레트로_떨어지지_않는다', () => {
    // gray 키를 정의하지 않으면 Tailwind 기본 팔레트(#6b7280 계열)가 그대로 쓰인다.
    const gray = asObj(colors.gray);
    expect(gray, 'colors.gray 미정의 — 기본 팔레트로 폴백된다').toBeDefined();
    for (const [step, hex] of Object.entries(KRDS_NEUTRAL)) {
      expect(gray[step], `gray-${step} 미정의/불일치`).toBe(hex);
    }
  });

  it('stock_Tailwind_gray값이_더이상_gray_스케일에_없다', () => {
    // 회귀: 기본 팔레트(#6b7280 / #374151 / #f9fafb …)가 남아있으면 실패
    const serialized = JSON.stringify(colors.gray).toLowerCase();
    for (const stock of ['#6b7280', '#4b5563', '#374151', '#111827', '#f9fafb', '#9ca3af']) {
      expect(serialized, `stock Tailwind gray 잔존: ${stock}`).not.toContain(stock);
    }
  });

  it('gray_스케일이_neutral_스케일과_같은_값을_가리킨다', () => {
    // 두 이름은 같은 KRDS 중립색의 별칭이다 — 한쪽만 갱신되는 드리프트를 기계 대조로 막는다.
    const gray = asObj(colors.gray);
    const neutral = asObj(colors.neutral);
    expect(Object.keys(gray).sort()).toEqual(Object.keys(neutral).sort());
    expect(gray).toEqual(neutral);
  });

  it('기존_컴포넌트가_참조하는_색_별칭이_보존된다', () => {
    // accent / bgLight / border 는 소스 전반에서 사용 중 — 삭제 시 회귀
    expect(colors.accent).toBeDefined();
    expect(colors.bgLight).toBeDefined();
    expect(colors.border).toBeDefined();
  });

  // ── 중립 별칭(bgLight / border) 정렬 ──────────────────────────────────────
  // @req R12 — 고충실 시안 요구의 선행 델타. 두 별칭은 중립색 교체 당시 "범위 밖"으로
  // 미뤄져 구 neutral 값(#FAFBFC / #E1E5EA)을 들고 있었고, 그래서 화면에 KRDS 중립색과
  // 미세하게 다른 두 번째 회색이 섞여 있었다. 값을 복제하지 말고 krdsNeutral 상수의
  // 해당 단을 **참조**해야 gray/neutral 과 같은 이유로 드리프트가 구조적으로 막힌다.
  /** tailwind.config.js 원문 — 별칭이 "값 복제"가 아니라 "참조"인지 소스에서 직접 판정한다. */
  const configSource = readFileSync(path.join(repoRoot, 'tailwind.config.js'), 'utf-8');

  /** `name: { ... },` 블록 본문을 원문에서 잘라낸다(중첩 없는 1단 객체 전제). */
  const aliasBlock = (name: string): string => {
    const m = new RegExp(`\\n\\s*${name}: \\{([\\s\\S]*?)\\n\\s*\\},`).exec(configSource);
    if (!m) throw new Error(`tailwind.config.js 에서 ${name} 별칭 정의를 찾을 수 없다`);
    return m[1];
  };

  it('별칭_bgLight_는_KRDS_중립_50단과_같은_값이다', () => {
    // given: 콘텐츠 배경 별칭
    const bgLight = asObj(colors.bgLight);
    // then: 중립 스케일 50단과 완전히 같은 값이어야 한다
    expect(bgLight.DEFAULT).toBe(KRDS_NEUTRAL['50']);
    expect(bgLight.DEFAULT).toBe(asObj(colors.neutral)['50']);
    // 회귀: 구 neutral 값으로 되돌리면 실패한다
    expect(bgLight.DEFAULT).not.toBe('#FAFBFC');
  });

  it('별칭_border_는_KRDS_중립_200단과_같은_값이다', () => {
    // given: 구분선·테두리 별칭
    const border = asObj(colors.border);
    // then: 중립 스케일 200단과 완전히 같은 값이어야 한다
    expect(border.DEFAULT).toBe(KRDS_NEUTRAL['200']);
    expect(border.DEFAULT).toBe(asObj(colors.neutral)['200']);
    // 회귀: 구 neutral 값으로 되돌리면 실패한다
    expect(border.DEFAULT).not.toBe('#E1E5EA');
  });

  it('별칭이_중립_스케일을_참조하고_값을_복제하지_않는다', () => {
    // given: 설정 원문의 두 별칭 정의 블록
    const blocks: [string, string][] = [
      ['bgLight', '50'],
      ['border', '200'],
    ];
    for (const [name, step] of blocks) {
      const block = aliasBlock(name);
      // then: 중립 상수의 해당 단을 참조한다
      expect(block, `${name} 이 krdsNeutral[${step}] 을 참조하지 않는다`).toMatch(
        new RegExp(`krdsNeutral\\[\\s*${step}\\s*\\]`),
      );
      // and: 값 표를 복제하지 않는다 — hex 리터럴을 다시 심으면 실패한다
      expect(block, `${name} 정의에 하드코딩 hex 가 남아있다`).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    }
  });
});

describe('KRDS 디자인 토큰 — 폰트', () => {
  // 진실원: LogiCraft DS-001 tokens.typography — body/label/headline 전부
  // family "Pretendard GOV"(공공 배포판). 한글 260자는 일반 Pretendard 와 아웃라인·자폭이
  // 동일하고, 실제로 갈리는 것은 숫자 0-9·문장부호·라틴 I W i j l w 총 48자다
  // (I/l/1 혼동을 줄인 판). 따라서 "한글이 그대로"인 것은 회귀가 아니라 정상이다.
  it('fontFamily_sans_1순위가_Pretendard_GOV_다', () => {
    expect(fontFamily.sans[0]).toBe('Pretendard GOV');
  });

  // GOV 가 로드되지 않은 환경(캐시 실패·구 배포본)에서 시스템 폰트로 곧장 떨어지지 않도록
  // 일반 Pretendard 를 2순위 안전망으로 남긴다.
  it('fontFamily_sans_2순위에_Pretendard_폴백이_남아있다', () => {
    expect(fontFamily.sans[1]).toBe('Pretendard');
  });

  it('mono는_D2Coding_우선이다', () => {
    expect(fontFamily.mono[0]).toBe('D2Coding');
  });
});

describe('KRDS 디자인 토큰 — 타이포/모양/모션', () => {
  // 진실원: LogiCraft DS-001 KRDS Public **v2** — data.tokens.typography_use (15단 ladder)
  // ⚠ 같은 ITEM 의 design_md "Typography Hierarchy" 표(body-md 16 / body-sm 14 / label 12)와
  //   모순이나, ladder + Do's("본문 17px 이상 + line-height 1.6 이상")가 서로 일치하므로
  //   ladder 를 기준으로 한다.
  const flatSize = (v: unknown): string => (Array.isArray(v) ? (v[0] as string) : (v as string));
  const optsOf = (v: unknown): { lineHeight?: string; fontWeight?: string } =>
    (Array.isArray(v) ? v[1] : {}) as { lineHeight?: string; fontWeight?: string };

  /** DS-001 v2 확정 ladder — [size, weight, lineHeight] */
  const LADDER: Record<string, [string, string, string]> = {
    'display-xl': ['45px', '800', '1.15'],
    'display-lg': ['37px', '800', '1.2'],
    'display-md': ['31px', '700', '1.25'],
    'display-sm': ['26px', '700', '1.3'],
    'title-lg': ['22px', '700', '1.4'],
    'title-md': ['18px', '600', '1.45'],
    'title-sm': ['17px', '600', '1.5'],
    'body-lg': ['19px', '400', '1.6'],
    'body-md': ['17px', '400', '1.6'],
    'body-sm': ['15px', '400', '1.6'],
    label: ['14px', '600', '1.4'],
    caption: ['14px', '400', '1.5'],
    button: ['17px', '500', '1.4'],
    'nav-link': ['17px', '500', '1.4'],
    mono: ['14px', '400', '1.5'],
  };

  it('DS_001_v2_ladder_15단이_전부_정의된다', () => {
    for (const [key, [size, weight, lh]] of Object.entries(LADDER)) {
      const token = fontSize[key];
      expect(token, `${key} step 미정의`).toBeDefined();
      expect(flatSize(token), `${key} 크기`).toBe(size);
      expect(optsOf(token).fontWeight, `${key} weight`).toBe(weight);
      expect(optsOf(token).lineHeight, `${key} line-height`).toBe(lh);
    }
  });

  it('ladder_에_소수점_px가_없다', () => {
    // 확정 ladder 는 정수 px 다 — 스케일 배수를 직접 곱한 21.6/25.9 같은 중간값 금지
    for (const key of Object.keys(fontSize)) {
      expect(flatSize(fontSize[key]), `${key} 에 소수점 px`).not.toMatch(/\d\.\d+px/);
    }
  });

  it('본문_계열_토큰이_ladder_body_md_17px이다', () => {
    // text-body / text-body-md / text-sm / text-base 는 화면에서 본문으로 쓰인다
    for (const key of ['body', 'body-md', 'sm', 'base']) {
      expect(flatSize(fontSize[key]), `${key} 크기`).toBe('17px');
    }
    expect(flatSize(fontSize['body-sm'])).toBe('15px');
  });

  it('본문_계열_토큰에_line_height_1_6_이상이_명시된다', () => {
    // KRDS Do's: "본문 17px 이상 + line-height 1.6 이상으로 가독성 확보"
    for (const key of ['body', 'body-md', 'body-lg', 'body-sm', 'sub', 'xs', 'sm', 'base']) {
      const lh = optsOf(fontSize[key]).lineHeight;
      expect(lh, `${key} line-height 미지정`).toBeDefined();
      expect(Number(lh), `${key} line-height`).toBeGreaterThanOrEqual(1.6);
    }
  });

  it('라벨_계열_토큰이_ladder_label_14px_w600이다', () => {
    for (const key of ['label', 'table-header']) {
      expect(flatSize(fontSize[key]), `${key} 크기`).toBe('14px');
      expect(optsOf(fontSize[key]).fontWeight, `${key} weight`).toBe('600');
    }
    // 배지·캡션으로 쓰이는 text-xs / text-sub 도 label/caption 크기 축
    expect(flatSize(fontSize.xs)).toBe('14px');
    expect(flatSize(fontSize.sub)).toBe('14px');
  });

  it('버튼_텍스트는_label이_아니라_body계열_button_step이다', () => {
    // 회귀: btn-label 을 label(14px/w600)로 되돌리면 실패한다.
    // DS-001 v2 `button` = 17px / w500 / LH 1.4
    expect(flatSize(fontSize['btn-label'])).toBe('17px');
    expect(optsOf(fontSize['btn-label']).fontWeight).toBe('500');
    expect(optsOf(fontSize['btn-label']).lineHeight).toBe('1.4');
  });

  it('제목_계열_별칭_토큰이_ladder_step에_스냅된다', () => {
    expect(flatSize(fontSize['section-title'])).toBe('18px'); // = title-md
    expect(flatSize(fontSize.lg)).toBe('18px');
    expect(flatSize(fontSize['page-title'])).toBe('22px'); // = title-lg
    expect(optsOf(fontSize['page-title']).fontWeight).toBe('700');
    expect(flatSize(fontSize.xl)).toBe('22px');
    expect(flatSize(fontSize['2xl'])).toBe('26px'); // = display-sm
    expect(flatSize(fontSize['3xl'])).toBe('31px'); // = display-md
  });

  it('구_md표_값_16_14_12px가_본문_라벨_토큰에_남아있지_않다', () => {
    // 회귀: DS-001 design_md 표(body-md 16 / body-sm 14 / label 12)로 되돌아가면 실패
    expect(flatSize(fontSize['body-md'])).not.toBe('16px');
    expect(flatSize(fontSize.label)).not.toBe('12px');
    expect(flatSize(fontSize.sub)).not.toBe('12px');
    expect(flatSize(fontSize.xs)).not.toBe('0.75rem');
  });

  it('기존_fontSize_토큰이_보존된다', () => {
    // text-sub(181회), text-page-title 등 광범위 사용 — 삭제 시 회귀
    expect(fontSize.sub).toBeDefined();
    expect(fontSize['page-title']).toBeDefined();
    expect(fontSize['section-title']).toBeDefined();
    expect(fontSize.body).toBeDefined();
    expect(fontSize['btn-label']).toBeDefined();
    expect(fontSize['table-header']).toBeDefined();
  });

  it('borderRadius_토큰이_KRDS_값이다', () => {
    expect(borderRadius.sm).toBe('4px');
    expect(borderRadius.md).toBe('6px');
    expect(borderRadius.lg).toBe('8px');
    expect(borderRadius.full).toBe('9999px');
  });

  it('boxShadow_토큰이_KRDS_값이다', () => {
    expect(boxShadow.sm).toContain('rgba(14,21,40,0.06)');
    expect(boxShadow.md).toContain('rgba(14,21,40,0.08)');
    expect(boxShadow.lg).toContain('rgba(14,21,40,0.12)');
  });

  it('transition_duration_timing_토큰이_추가된다', () => {
    const dur = fullConfig.theme.transitionDuration as Record<string, string>;
    const timing = fullConfig.theme.transitionTimingFunction as Record<string, string>;
    expect(dur.fast).toBe('120ms');
    expect(dur.slow).toBe('320ms');
    expect(timing.standard).toBe('cubic-bezier(0.2,0,0,1)');
    expect(timing.emphasized).toBe('cubic-bezier(0.3,0,0,1)');
  });
});

describe('폰트 자가호스팅 — CDN 0', () => {
  // ⚠ 검사 지점이 `main.tsx` → `styles/bootstrap.ts` 로 **옮겨졌다**(구 테스트명
  //   `main_이_pretendard_gov_dynamic_subset_css_를_import_한다` 는 폐기). 진입점이 둘
  //   (독립 앱 `main.tsx` / 포털 Remote `remote/AuthoringRemote.tsx`)이 되면서 CSS import 를
  //   한 모듈로 모았기 때문이다 — 검사 자체는 약해지지 않았고 위치만 따라갔다.
  //   부트스트랩이 유일한 로드 지점이라는 사실은 `styles/__tests__/bootstrapSingleSource.test.ts`
  //   가 별도로 고정한다.
  it('부트스트랩이_pretendard_gov_dynamic_subset_css_를_import_한다', () => {
    const bootstrap = readFileSync(path.join(repoRoot, 'src/styles/bootstrap.ts'), 'utf-8');
    // 공공 배포판(GOV) 경로여야 한다. 구 경로 'pretendard/dist/...' 로 되돌아가면
    // 아래 두 단언이 각각 실패한다(경로 불일치 + 구 패키지 잔존).
    expect(bootstrap).toMatch(
      /pretendard-gov\/dist\/web\/static\/pretendard-gov-dynamic-subset\.css/,
    );
    expect(bootstrap).not.toMatch(/from\s+'pretendard\/|import\s+'pretendard\//);
    expect(bootstrap).toMatch(/d2coding\/.*\.css/);
  });

  it('소스에_폰트_CDN_링크가_없다', () => {
    // global.css / 부트스트랩 / 두 진입점 어디에도 폰트 CDN URL 이 없어야 함
    const cdnPattern = /(fastly|jsdelivr|googleapis|gstatic|cdn\.jsdelivr|fonts\.google)/i;
    for (const rel of [
      'src/styles/global.css',
      'src/styles/bootstrap.ts',
      'src/main.tsx',
      'src/remote/AuthoringRemote.tsx',
    ]) {
      expect(readFileSync(path.join(repoRoot, rel), 'utf-8')).not.toMatch(cdnPattern);
    }
  });
});
