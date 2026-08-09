import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss/resolveConfig';
import { describe, expect, it } from 'vitest';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM default export)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig from '../../tailwind.config.js';

import { contrastRatio, WCAG_AA_NORMAL_TEXT } from './wcagContrast';

/**
 * 적용관례 가드 — 색값이 같은 것을 넘어 **쓰는 방식**을 고정한다.
 *
 * 진실원: LogiCraft DS-001 `do_rules`
 *  1. "표 헤더 배경은 secondary 스케일의 가장 옅은 단계를 쓴다 — 페이지 배경과 같은 색을
 *     쓰면 열 구조가 먼저 읽히지 않는다."
 *  2. "표의 행 hover 표면은 #FFFBEB 를 쓴다 — 긴 표에서 커서가 짚은 행을 확실히 알린다.
 *     회색 계열은 표면 배경과 겹쳐 구분이 약하다."
 *  3. "카드 표면에는 shadow.sm 만 쓴다 — md·lg 는 오버레이·팝오버 전용이다."
 *
 * ⚠ 클래스 문자열 존재만 세지 않는다 — 헤더 글자색은 tailwind.config.js 를 resolveConfig 해
 *   얻은 hex 로 WCAG 대비비를 실제 계산한다(표 헤더 배경이 회색 → secondary-50 으로
 *   바뀌면서 그 위 글자색의 대비도 함께 달라지기 때문이다).
 */

const fullConfig = resolveConfig(tailwindConfig as never);
const colors = fullConfig.theme.colors as Record<string, unknown>;
const boxShadow = fullConfig.theme.boxShadow as Record<string, string>;
const asObj = (v: unknown): Record<string, string> => v as Record<string, string>;

const gray = asObj(colors.gray);
const secondary = asObj(colors.secondary);

const repoRoot = path.resolve(__dirname, '../..');
const readSrc = (relPath: string): string => readFileSync(path.join(repoRoot, relPath), 'utf-8');

/** DS-001 do_rules 2 가 못박은 행 hover 표면색. 토큰 값이 이 값이어야 한다. */
const ROW_HOVER_HEX = '#FFFBEB';

interface TableCase {
  label: string;
  file: string;
  /** 헤더 글자색 클래스가 선언된 지점(상수 선언 줄 또는 thead 태그). */
  headerTextAnchor: string;
}

/**
 * 이번 Phase 의 적용 대상 3곳.
 *
 * ⚠ 표 헤더를 자체 구현한 파일은 이 밖에도 있다(VideoListPage · AugmentRequestPage ·
 *   DashboardPage · NoticeListPage · DeidentReportListPage · EventTypeManagePage ·
 *   LabelMasterManagePage · WorkerStatPage · LabelAttrDefPanel · ShortcutCheatSheet).
 *   이번 범위가 아니며, 확장할 때 이 배열에 추가한다.
 */
const TABLES: TableCase[] = [
  {
    label: 'DataTable(공용)',
    file: 'src/components/common/DataTable.tsx',
    headerTextAnchor: 'const HEADER_CLASS =',
  },
  {
    label: 'TaskBoardTable(작업목록)',
    file: 'src/features/task/components/TaskBoardTable.tsx',
    headerTextAnchor: 'const TH_CLASS =',
  },
  {
    label: 'WorkerStatsTable(작업자 통계)',
    file: 'src/features/stat/components/WorkerStatsTable.tsx',
    headerTextAnchor: '<thead',
  },
];

/**
 * `<thead` 부터 헤더 행이 닫히는 `</tr>` 까지를 잘라낸다 — 표 헤더 배경 클래스는
 * `<thead>` 또는 그 바로 아래 헤더 `<tr>` 중 한쪽에 붙기 때문에 두 경우를 함께 덮는다.
 */
function headerRegion(content: string, file: string): string {
  const start = content.indexOf('<thead');
  if (start === -1) throw new Error(`${file}: <thead 를 찾을 수 없음`);
  const end = content.indexOf('</tr>', start);
  if (end === -1) throw new Error(`${file}: 헤더 행의 </tr> 를 찾을 수 없음`);
  return content.slice(start, end);
}

/** anchor 직후 구간에서 첫 `text-gray-NNN` 단계를 뽑는다. */
function headerTextGrayStep(content: string, anchor: string, file: string): string {
  const idx = content.indexOf(anchor);
  if (idx === -1) throw new Error(`${file}: anchor 를 찾을 수 없음 — "${anchor}"`);
  const window = content.slice(idx, idx + 400);
  const m = window.match(/text-gray-(\d{2,3})\b/);
  if (!m) throw new Error(`${file}: "${anchor}" 인근에서 text-gray-* 토큰을 찾을 수 없음`);
  return m[1];
}

describe('적용관례 — 표 헤더 배경(DS-001 do_rules)', () => {
  it.each(TABLES)('$label — 표_헤더가_보조색_최옅단_배경을_쓴다', ({ file }) => {
    // given: 헤더 영역(thead ~ 헤더 행 닫힘)
    const region = headerRegion(readSrc(file), file);

    // then: secondary 스케일의 가장 옅은 단계를 쓰고, 페이지 배경과 같은 회색은 쓰지 않는다
    expect(region, `${file}: 표 헤더 배경이 bg-secondary-50 이 아니다`).toContain(
      'bg-secondary-50',
    );
    expect(region, `${file}: 표 헤더에 페이지 배경과 같은 bg-gray-50 이 남아있다`).not.toMatch(
      /\bbg-gray-50\b/,
    );
  });

  it('secondary_50_이_페이지_배경_회색과_실제로_다른_색이다', () => {
    // do_rules 의 근거("페이지 배경과 같은 색을 쓰면 열 구조가 먼저 읽히지 않는다")가
    // 토큰 값 수준에서 성립하는지 — 두 값이 같아지면 이 규칙 자체가 무의미해진다.
    expect(secondary['50']).toBe('#EEF2F7');
    expect(secondary['50']).not.toBe(gray['50']);
  });

  it.each(TABLES)('$label — 헤더_글자색이_secondary_50_위에서_AA를_만족한다', (t) => {
    // given: 소스에 실제로 적용된 헤더 글자색 단계
    const step = headerTextGrayStep(readSrc(t.file), t.headerTextAnchor, t.file);

    // when: 새 헤더 배경(secondary-50) 위 대비비를 계산
    const ratio = contrastRatio(gray[step], secondary['50']);

    // then: AA(4.5:1) 이상
    expect(
      ratio,
      `${t.file}: 헤더 글자색 text-gray-${step} 이 bg-secondary-50 위에서 AA 미달(${ratio.toFixed(2)}:1)`,
    ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
  });

  it('★gray_500_은_secondary_50_위에서_AA_미달이고_gray_600_은_통과한다', () => {
    // 헤더 배경이 바뀌면서 달라지는 경계값을 못박는다 — 헤더 글자색을 500 으로
    // 되돌리면 위 케이스가 실패해야 하는 이유다.
    expect(contrastRatio(gray['500'], secondary['50'])).toBeLessThan(WCAG_AA_NORMAL_TEXT);
    expect(contrastRatio(gray['600'], secondary['50'])).toBeGreaterThanOrEqual(
      WCAG_AA_NORMAL_TEXT,
    );
  });
});

describe('적용관례 — 표 행 hover 표면(DS-001 do_rules)', () => {
  it('행_hover_전용_토큰이_tailwind_설정에_정의된다', () => {
    const rowHover = colors.rowHover as Record<string, string> | string | undefined;
    expect(rowHover, 'colors.rowHover 미정의 — 임의 hex 를 컴포넌트에 박게 된다').toBeDefined();
    const hex = typeof rowHover === 'string' ? rowHover : (rowHover as Record<string, string>).DEFAULT;
    expect(hex?.toUpperCase()).toBe(ROW_HOVER_HEX);
  });

  it.each(TABLES)('$label — 표_행_hover_가_전용_토큰을_쓴다', ({ file }) => {
    const content = readSrc(file);
    expect(content, `${file}: 행 hover 가 rowHover 토큰을 쓰지 않는다`).toContain(
      'hover:bg-rowHover',
    );
    // 회색 계열 hover 로 되돌아가면 실패한다(표면 배경과 겹쳐 구분이 약해진다).
    expect(content, `${file}: 회색 계열 행 hover 가 남아있다`).not.toContain('hover:bg-gray-50');
  });

  it('★소스_어디에도_임의_hex_FFFBEB_가_직접_박혀_있지_않다', () => {
    // 토큰 경유가 아니라 컴포넌트에 raw hex 를 박으면 값이 두 군데로 갈린다.
    // (설정 파일은 토큰 정의처라 스캔 대상이 아니다 — src 만 본다.)
    const files = readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
      .filter((f) => /\.(ts|tsx|css)$/.test(f))
      .map((f) => path.join('src', f))
      // 이 가드 자신은 기대값으로 hex 를 들고 있으므로 제외한다.
      .filter((f) => !f.endsWith('tableSurfaceConvention.test.ts'));
    expect(files.length, '스캔 대상 파일 0건 — 파일 수집이 깨졌다').toBeGreaterThan(100);

    const hits = files.filter((rel) => /#FFFBEB/i.test(readSrc(rel)));
    expect(hits, `raw hex 하드코딩:\n${hits.join('\n')}`).toEqual([]);
  });

  it('본문_셀_글자색이_hover_표면_위에서도_AA를_만족한다', () => {
    // hover 표면은 셀 텍스트 아래에 깔린다 — 배경만 바꾸고 대비를 확인하지 않으면
    // 커서를 올린 행만 읽기 어려워지는 회귀가 조용히 생긴다.
    for (const step of ['600', '700', '800', '900']) {
      expect(
        contrastRatio(gray[step], ROW_HOVER_HEX),
        `text-gray-${step} on ${ROW_HOVER_HEX}`,
      ).toBeGreaterThanOrEqual(WCAG_AA_NORMAL_TEXT);
    }
  });
});

describe('적용관례 — 표면 음영(DS-001 do_rules)', () => {
  /** 카드 계열 — 저음영(sm)만 허용. */
  const CARD_FILES = [
    'src/components/common/Card.tsx',
    'src/components/common/KpiCard.tsx',
    'src/components/common/DataTable.tsx',
  ];
  /** 오버레이·팝오버 계열 — md 이상 유지. */
  const OVERLAY_FILES = [
    'src/components/common/Modal.tsx',
    'src/components/common/Drawer.tsx',
    'src/components/common/Popover.tsx',
    'src/components/common/Toast.tsx',
  ];

  it.each(CARD_FILES)('%s — 카드_표면은_저음영_shadow_sm_만_쓴다', (file) => {
    const content = readSrc(file);
    expect(content, `${file}: shadow-sm 이 없다`).toContain('shadow-sm');
    expect(content, `${file}: 카드에 오버레이용 음영(md/lg/xl)이 쓰였다`).not.toMatch(
      /\bshadow-(md|lg|xl|2xl)\b/,
    );
  });

  it.each(OVERLAY_FILES)('%s — 오버레이_계열은_md_이상을_유지한다', (file) => {
    const content = readSrc(file);
    expect(content, `${file}: 오버레이 음영(md/lg)이 없다`).toMatch(/\bshadow-(md|lg)\b/);
  });

  it('★오버레이_음영도_토큰_단계만_쓴다_stock_shadow_xl_금지', () => {
    // boxShadow 토큰은 sm/md/lg 3단만 정의돼 있다 — shadow-xl 은 Tailwind 기본
    // 팔레트(순수 검정 기반)로 폴백해 KRDS 음영색(rgba(14,21,40,…))과 어긋난다.
    for (const file of OVERLAY_FILES) {
      expect(readSrc(file), `${file}: 토큰 밖 음영 단계(xl/2xl) 사용`).not.toMatch(
        /\bshadow-(xl|2xl)\b/,
      );
    }
  });

  it('boxShadow_토큰이_DS_001_값이다', () => {
    expect(boxShadow.sm).toBe('0 1px 2px rgba(14,21,40,0.06)');
    expect(boxShadow.md).toBe('0 4px 10px rgba(14,21,40,0.08)');
    expect(boxShadow.lg).toBe('0 10px 24px rgba(14,21,40,0.12)');
  });
});
