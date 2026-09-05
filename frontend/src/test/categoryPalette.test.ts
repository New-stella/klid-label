import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

import { contrastRatio, toHex, WCAG_AA_NORMAL_TEXT } from './wcagContrast';

/**
 * 범주 구분색 회귀 가드 — 진실원: LogiCraft DS-001 do_rules(범주 구분색) / known_gaps.
 *
 * 정본 규칙: "범주 구분색(이벤트 유형·라벨 형태·역할처럼 우열 없이 서로 대등한 분류)은
 * semantic 이 선점한 대역을 피해 8슬롯 팔레트에서만 고른다. … 세 축이 이 한 팔레트를 공유하며
 * 축마다 별도 색표를 만들지 않는다. … 반대로 상태·성패·경고 전달에는 쓰지 않는다."
 *
 * 이 파일이 막는 것은 넷이다:
 *  H1 팔레트가 semantic 대역으로 다시 샌다
 *  H2 세 축이 각자 색표를 다시 만든다
 *  H3 역할 색표가 또 복제된다
 *  H4 대비가 무너진다
 *
 * ⚠ 클래스 문자열 존재만 세는 가짜 가드가 아니다 — H4 는 tailwind.config.js 를 실제로
 *   resolveConfig 해 얻은 팔레트 값으로 WCAG 상대휘도 대비비를 **계산**한다.
 */

const fullConfig = resolveConfig(tailwindConfig as never);
const colors = fullConfig.theme.colors as Record<string, unknown>;
const repoRoot = path.resolve(__dirname, '../..');
const readSrc = (relPath: string): string => readFileSync(path.join(repoRoot, relPath), 'utf-8');

/** DS-001 이 8슬롯에 배정한 색 이름 — 슬롯 번호는 우열·순서를 뜻하지 않는다. */
const SLOTS = [1, 2, 3, 4, 5, 6, 7, 8] as const;
type Slot = (typeof SLOTS)[number];

const slotScale = (slot: Slot): Record<string, string> =>
  colors[`category-${slot}`] as Record<string, string>;

/**
 * semantic 이 선점한 대역 — 범주 표에 들어오면 배지가 성패·경고로 오독된다.
 * (success=green/emerald 축 · warn=amber/yellow/orange 축 · error=red/rose 축)
 */
const SEMANTIC_BAND = [
  'red',
  'rose',
  'amber',
  'orange',
  'emerald',
  'green',
  'yellow',
  'lime',
] as const;

/** 소스에서 `const NAME: ... = { ... };` 의 본문을 잘라낸다(중첩 없는 1단 객체 전제). */
function constObjectBody(source: string, name: string, file: string): string {
  const m = new RegExp(`\\n(?:export )?const ${name}[^=]*= \\{([\\s\\S]*?)\\n\\};`).exec(source);
  if (!m) throw new Error(`${file} 에서 ${name} 정의를 찾을 수 없다`);
  return m[1];
}

/** 문자열에서 Tailwind 색 유틸리티 클래스(`bg-x-100` 등)를 모두 뽑는다. */
function colorClassesIn(text: string): string[] {
  return text.match(/\b(?:bg|text|border|ring|from|via|to)-[a-z]+(?:-[a-z0-9]+)*\b/g) ?? [];
}

// ── 세 범주 축의 색표 원문 ────────────────────────────────────────────────
// ★ 축이 늘면 여기에 추가한다. 이 목록이 곧 "범주 구분색을 쓰는 곳 전부"다.
const CATEGORY_TABLES: { axis: string; file: string; constName: string }[] = [
  { axis: '이벤트 유형', file: 'src/components/common/EventTypeBadge.tsx', constName: 'EVENT_COLORS' },
  {
    axis: '라벨 형태',
    file: 'src/features/review/components/ObjectListPanel.tsx',
    constName: 'TYPE_BADGE_CLASS',
  },
  { axis: '역할', file: 'src/lib/roleDisplay.ts', constName: 'ROLE_COLOR' },
];

describe('DS-001 범주 구분색 — 토큰 정의', () => {
  it('8슬롯이_모두_정의되고_전_스케일이_따라온다', () => {
    // given: DS-001 이 규정한 8슬롯
    for (const slot of SLOTS) {
      const scale = slotScale(slot);
      // then: 슬롯이 존재하고 배경(50·100)·전경(700)·테두리(200) 단계가 모두 있다
      expect(scale, `category-${slot} 이 정의되지 않았다`).toBeDefined();
      for (const step of ['50', '100', '200', '700']) {
        expect(scale?.[step], `category-${slot}-${step} 미정의`).toBeTruthy();
      }
    }
  });

  /**
   * ⚠⚠ 회귀: 슬롯을 **중첩 키**(`category: { 1: blue, ... }`)로 두면 Tailwind v4 의 JS-config
   * 호환 계층이 키 `'1'` 가지를 통째로 버린다(2~8 과 `a1` 은 정상인데 `1` 만 사라진다).
   * 클래스가 생성되지 않아도 오류가 나지 않으므로 **배경이 조용히 비는 방식**으로 터진다.
   * 그래서 평면 키 `category-N` 으로 둔다. 이 단언은 그 구조를 고정한다.
   */
  it('슬롯1이_조용히_사라지지_않는다', () => {
    // given: v4 가 유일하게 삼켰던 슬롯
    const scale = slotScale(1);
    // then: 다른 슬롯과 동일하게 전 단계를 갖는다
    expect(scale?.['100']).toBeTruthy();
    expect(scale?.['700']).toBeTruthy();
    expect(Object.keys(scale ?? {})).toEqual(Object.keys(slotScale(2)));
  });

  /**
   * 슬롯 배정이 DS-001 표와 일치하는지 **기준값(500단)** 으로 확인한다.
   * 스케일 전체를 적지 않는 이유: 그 표가 곧 두 번째 진실원이 되기 때문이다.
   * 여기 적는 hex 는 정본이 명시한 슬롯 기준값이라 계약 그 자체다.
   */
  it('슬롯_배정이_DS_001_기준_hex와_일치한다', () => {
    const BASE: Record<Slot, string> = {
      1: '#2B7FFF', // blue
      2: '#AD46FF', // purple
      3: '#00B8DB', // cyan
      4: '#00BBA7', // teal
      5: '#615FFF', // indigo
      6: '#F6339A', // pink
      7: '#62748E', // slate
      8: '#E12AFB', // fuchsia
    };
    for (const slot of SLOTS) {
      expect(toHex(slotScale(slot)['500']), `category-${slot} 기준값 불일치`).toBe(BASE[slot]);
    }
  });

  it('설정이_값_표를_복제하지_않고_팔레트를_참조한다', () => {
    // given: tailwind.config.js 원문의 krdsCategory 정의 블록
    const configSource = readSrc('tailwind.config.js');
    const block = constObjectBody(configSource, 'krdsCategory', 'tailwind.config.js');
    // then: Tailwind 팔레트 객체를 참조한다(8슬롯 전부)
    expect(block.match(/tailwindPalette\.[a-z]+/g)?.length).toBe(SLOTS.length);
    // and: hex 리터럴을 다시 심으면 실패한다 — gray/neutral·bgLight/border 와 같은 이유다
    expect(block, 'krdsCategory 정의에 하드코딩 hex 가 있다').not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });
});

describe('DS-001 범주 구분색 — H1 semantic 대역 유출 차단', () => {
  it.each(CATEGORY_TABLES)('$axis 표가_semantic_대역_색을_쓰지_않는다', ({ file, constName }) => {
    // given: 그 축의 색표 원문
    const body = constObjectBody(readSrc(file), constName, file);
    const classes = colorClassesIn(body);
    // 스캔 대상이 0건이면 가드가 아무것도 보지 않은 것이다 — 통과가 아니라 실패다
    expect(classes.length, `${file} 의 ${constName} 에서 색 클래스를 한 건도 찾지 못했다`).toBeGreaterThan(0);
    // then: semantic 이 선점한 대역이 한 건도 없다
    for (const band of SEMANTIC_BAND) {
      const leaked = classes.filter((c) => new RegExp(`-${band}-\\d`).test(c));
      expect(leaked, `${constName} 에 semantic 대역(${band})이 샜다: ${leaked.join(', ')}`).toEqual(
        [],
      );
    }
  });
});

describe('DS-001 범주 구분색 — H2 세 축이 한 팔레트를 공유', () => {
  it.each(CATEGORY_TABLES)('$axis 표가_category_토큰만_쓴다', ({ file, constName }) => {
    // given: 그 축의 색표 원문
    const body = constObjectBody(readSrc(file), constName, file);
    const classes = colorClassesIn(body);
    // 스캔 0건 = 가드 무력화 → 실패
    expect(classes.length, `${file} 의 ${constName} 에서 색 클래스를 한 건도 찾지 못했다`).toBeGreaterThan(0);
    // then: 모든 색 클래스가 `{prefix}-category-{1..8}-{step}` 형태다
    for (const cls of classes) {
      expect(cls, `${constName} 이 범주 토큰 밖의 색을 쓴다: ${cls}`).toMatch(
        /^(?:bg|text|border|ring|from|via|to)-category-[1-8]-(?:50|100|200|300|400|500|600|700|800|900|950)$/,
      );
    }
  });

  it('세_축이_쓰는_슬롯이_모두_정의된_8슬롯_안에_있다', () => {
    // given: 세 축이 실제로 참조하는 슬롯 번호 전부
    const used = new Set<number>();
    for (const { file, constName } of CATEGORY_TABLES) {
      const body = constObjectBody(readSrc(file), constName, file);
      for (const cls of colorClassesIn(body)) {
        const m = /-category-([1-8])-/.exec(cls);
        if (m) used.add(Number(m[1]));
      }
    }
    // then: 스캔이 비어 있지 않고, 쓰는 슬롯이 전부 토큰으로 정의돼 있다
    expect(used.size).toBeGreaterThan(0);
    for (const slot of used) {
      expect(slotScale(slot as Slot), `사용 중인 category-${slot} 이 미정의다`).toBeTruthy();
    }
  });
});

describe('DS-001 범주 구분색 — H3 역할 색표 단일 진실원', () => {
  it('ROLE_COLOR_정의가_저장소에_한_곳뿐이다', () => {
    // given: src 전체의 ROLE_COLOR **정의**(선언) 위치
    const files = listSourceFiles(path.join(repoRoot, 'src'));
    const definers = files.filter((f) =>
      /\n(?:export )?const ROLE_COLOR\b/.test(readFileSync(f, 'utf-8')),
    );
    // 스캔 0건 = 파일을 못 읽은 것이므로 실패로 다룬다
    expect(files.length, 'src 소스 파일을 한 건도 스캔하지 못했다').toBeGreaterThan(0);
    // then: 정의는 공용 모듈 한 곳뿐이다 — Gnb/ForbiddenPage 가 각자 표를 갖던 구조로 돌아가면 실패
    expect(definers.map((f) => path.relative(repoRoot, f))).toEqual(['src/lib/roleDisplay.ts']);
  });

  it('배지_컴포넌트가_공용_모듈을_import_한다', () => {
    // 두 화면(GNB·접근 거부)의 역할 배지는 `CurrentRoleBadge` 하나가 그린다 — 그 컴포넌트가
    // 공용 표를 읽는다. 예전에는 두 화면이 각자 표를 들고 있었고, 그 뒤에는 각자 공용 표를
    // import 했다. 지금은 배지 자체가 한 곳이라 여기서 그 한 곳을 못 박는다.
    const src = readSrc('src/components/common/CurrentRoleBadge.tsx');
    expect(src, 'CurrentRoleBadge 가 공용 역할 표를 쓰지 않는다').toMatch(
      /import \{[^}]*ROLE_COLOR[^}]*\} from '@\/lib\/roleDisplay'/,
    );
  });

  it('두_화면이_역할_배지를_직접_그리지_않고_공용_컴포넌트에_맡긴다', () => {
    // 배지를 화면이 직접 그리면 「역할이 없을 때 무엇을 보일지」가 화면마다 갈린다 — 실제로
    // 두 화면이 각각 `?? Role.WORKER` 로 작업자를 채워 사실과 다른 역할을 보여주고 있었다.
    for (const file of ['src/components/layout/Gnb.tsx', 'src/components/common/ForbiddenPage.tsx']) {
      const src = readSrc(file);
      expect(src, `${file} 이 공용 배지 컴포넌트를 쓰지 않는다`).toContain('CurrentRoleBadge');
      expect(src, `${file} 이 역할 색표를 직접 읽는다`).not.toMatch(/ROLE_COLOR\b/);
      expect(src, `${file} 이 역할 표시명 표를 직접 읽는다`).not.toMatch(/ROLE_LABEL\b/);
    }
  });
});

/**
 * H3 의 짝 — **표시명 축**의 단일 진실원.
 *
 * 색 축에만 유일성 가드가 있고 표시명 축에는 없었다. 그래서 사용자 관리 화면이 같은 표를 한 벌
 * 더 들고 있어도 아무도 잡지 못했고, 관리자 역할이 신설될 때 두 곳을 각각 고쳐야 했다.
 *
 * ⚠ **`RoleBadge` 는 의도된 예외다 — 「빠뜨린 것」으로 보고 합치지 말 것.** 그쪽은 미배정
 *   variant 와 경고 아이콘을 갖고 semantic 토큰(primary/secondary/warning)으로 대비를 따로
 *   실측한 **별도 화면 사양**이다. 표시명이 겹치는 것은 우연이 아니라 같은 역할을 가리키기
 *   때문이고, 색 축은 서로 다르다. 그래서 정의는 하나가 아니라 **둘**이 정답이다 —
 *   공용 표시축(`roleDisplay`) 1 + 배지 사양(`RoleBadge`) 1.
 */
describe('역할 표시명 단일 진실원 — 공용 표시축 + 배지 사양 둘뿐', () => {
  /** 네 역할의 표시명 — 이 중 셋 이상을 한 객체 안에 나열하면 「역할 표시명 표」로 본다. */
  const ROLE_LABEL_VALUES = ['관리자', '검수자', '작업자', '포털'];

  /**
   * 이 축의 **정당한 예외**. 술어를 느슨하게 하는 대신 여기에 못 박는다 — 검사를 넓히면 표가
   * 몇 벌이 늘어도 초록으로 남아 그 축이 통째로 죽는다.
   */
  const ROLE_LABEL_TABLE_FILES = [
    'src/components/common/RoleBadge.tsx', // 배지 사양(미배정 variant + 경고 아이콘 + 별도 색 축)
    'src/lib/roleDisplay.ts', // 공용 표시축
    // 검수 이슈 **작성자 역할** 표기 — 모집단이 다르다. 이슈를 쓰는 것은 내부 작업자·검수자·
    // 관리자뿐이고 포털 회원은 이 축에 등장하지 않아, 그 코드가 오면 「모르는 역할」로 원문
    // 폴백하는 것이 이 화면의 사양이다(회귀 가드가 그 폴백을 직접 못 박고 있다).
    // 공용 표시축으로 합치면 포털 코드가 '포털' 로 해석돼 그 사양이 바뀐다 — 합치려면 그
    // 화면 사양을 먼저 정해야 하므로 여기서는 예외로 못 박아 둔다.
    'src/features/review/issueLabels.ts',
  ];

  /** `const NAME ... = { ... };` 블록을 전부 뽑는다(중첩 없는 1단 객체 전제). */
  function constObjectBodies(source: string): string[] {
    const re = /\n(?:export )?const [A-Za-z_$][\w$]*[^=\n]*=\s*\{([\s\S]*?)\n\};/g;
    const bodies: string[] = [];
    let m: RegExpExecArray | null;
    while ((m = re.exec(source)) !== null) bodies.push(m[1]);
    return bodies;
  }

  it('역할_표시명_표가_두_곳뿐이다', () => {
    // 시험 파일은 제외한다 — 기대값으로 라벨을 나열하는 것이 정상이라 그것까지 세면 가드가
    // 자기 자신을 잡는다.
    const files = listSourceFiles(path.join(repoRoot, 'src')).filter(
      (f) => !/\.test\.tsx?$/.test(f) && !f.includes(`${path.sep}__tests__${path.sep}`) &&
        !f.includes(`${path.sep}test${path.sep}`),
    );
    expect(files.length, 'src 소스 파일을 한 건도 스캔하지 못했다').toBeGreaterThan(100);

    const definers = files.filter((f) =>
      constObjectBodies(readFileSync(f, 'utf-8')).some(
        (body) => ROLE_LABEL_VALUES.filter((v) => body.includes(`'${v}'`)).length >= 3,
      ),
    );

    expect(definers.map((f) => path.relative(repoRoot, f)).sort()).toEqual(
      [...ROLE_LABEL_TABLE_FILES].sort(),
    );
  });

  it('공용_표시축이_네_역할을_모두_담는다', () => {
    // 스캔이 실제로 무언가를 본다는 확인 + 표시명 값 자체의 고정(형식만 보는 가드는 값이 서로
    // 뒤바뀌어도 통과한다).
    const body = constObjectBody(readSrc('src/lib/roleDisplay.ts'), 'ROLE_LABEL', 'roleDisplay.ts');
    expect(body).toContain("ADMIN: '관리자'");
    expect(body).toContain("REVIEWER: '검수자'");
    expect(body).toContain("WORKER: '작업자'");
    expect(body).toContain("PORTAL_USER: '포털'");
  });

  it('사용자_관리_화면이_자기_표시명_표를_갖지_않는다', () => {
    // 이 화면이 정확히 그 중복을 갖고 있었다 — 관리자 역할 신설 때 두 곳을 각각 고쳐야 했던
    // 자리다. 공용 표시축을 import 해 쓴다.
    const src = readSrc('src/pages/manage/UserManagePage.tsx');
    expect(src).not.toMatch(/\nconst ROLE_LABEL\b/);
    expect(src).toMatch(/import \{[^}]*ROLE_LABEL[^}]*\} from '@\/lib\/roleDisplay'/);
  });
});

describe('DS-001 범주 구분색 — H4 대비(WCAG AA)', () => {
  /**
   * DS-001 이 규정한 조합은 둘이다 — 전경 700 on 배경 100 / 전경 700 on 배경 50.
   * 기대값을 나열하지 않고 **팔레트 값에서 직접 계산**한다(팔레트가 바뀌면 여기서 잡힌다).
   */
  it.each(SLOTS)('슬롯%i_의_700_전경이_100_50_배경에서_AA를_통과한다', (slot) => {
    const scale = slotScale(slot);
    const fg = scale['700'];
    for (const bgStep of ['100', '50'] as const) {
      const ratio = contrastRatio(fg, scale[bgStep]);
      expect(
        ratio,
        `category-${slot}: 700 on ${bgStep} = ${ratio.toFixed(2)}:1 (AA ${WCAG_AA_NORMAL_TEXT} 미달)`,
      ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    }
  });

  it('흰_배경_위_전경도_AA를_통과한다', () => {
    // 배지 밖으로 텍스트만 쓰이는 경우를 대비한 여유 확인(정본 조합은 아니지만 회귀 신호로 둔다)
    for (const slot of SLOTS) {
      const ratio = contrastRatio(slotScale(slot)['700'], '#FFFFFF');
      expect(ratio, `category-${slot}: 700 on white = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(
        WCAG_AA_NORMAL_TEXT,
      );
    }
  });
});

/** src 하위 .ts/.tsx 전체 경로 목록. */
function listSourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listSourceFiles(full));
    else if (/\.tsx?$/.test(entry.name)) out.push(full);
  }
  return out;
}
