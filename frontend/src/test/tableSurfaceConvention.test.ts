import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';
import { describe, expect, it } from 'vitest';

import { cn } from '@/lib/cn';

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
 *  4. 본문(표 셀 포함)은 17px 이상 — DS-001 typography Do's.
 *
 * ⚠ 클래스 문자열 존재만 세지 않는다 — 두 층에서 각각 실물을 만든다.
 *   · **색**: 헤더 글자색은 tailwind.config.js 를 resolveConfig 해 얻은 hex 로 WCAG 대비비를
 *     실제 계산한다(표 헤더 배경이 회색 → secondary-50 으로 바뀌면서 그 위 글자색의 대비도
 *     함께 달라지기 때문이다).
 *   · **클래스**: `<th>`/`<td>` 에 적용되는 클래스는 소스 조각을 이어 붙인 문자열이 아니라
 *     **실제 `cn()`(tailwind-merge)을 통과시킨 결과**로 판정한다({@link resolvedClass}).
 *     소스에 토큰이 "있다"와 DOM 에 그 토큰이 "남는다"는 서로 다른 사실이고,
 *     그 둘이 갈리는 실패 모드가 실재한다(아래 twMerge 절 참고).
 */

const fullConfig = resolveConfig(tailwindConfig as never);
const colors = fullConfig.theme.colors as Record<string, unknown>;
const boxShadow = fullConfig.theme.boxShadow as Record<string, string>;
const fontSize = fullConfig.theme.fontSize as Record<string, [string, { fontWeight?: string }]>;
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
}

/**
 * 표 헤더를 자체 구현한 파일 전량.
 *
 * ⚠ **이 배열은 열거다 — 열거는 새 파일을 놓친다.** 그래서 아래
 *   `★TABLES_열거가_소스의_thead_보유_파일_전량과_일치한다_전수_스캔` 이 `src` 를 전수
 *   스캔해 이 배열 + {@link EXCLUDED_TABLE_FILES} 와의 **대칭차가 비어 있음**을 강제한다.
 *   새 목록 화면을 만들고 여기 등록하지 않으면 그 케이스가 시끄럽게 실패한다
 *   (같은 보완을 음영 축은 이미 하고 있었는데 표 축에만 빠져 있었다).
 */
const TABLES: TableCase[] = [
  { label: 'DataTable(공용)', file: 'src/components/common/DataTable.tsx' },
  { label: 'TaskBoardTable(작업목록)', file: 'src/features/task/components/TaskBoardTable.tsx' },
  {
    label: 'WorkerStatsTable(작업자 통계)',
    file: 'src/features/stat/components/WorkerStatsTable.tsx',
  },
  { label: 'VideoListPage(영상 목록)', file: 'src/pages/VideoListPage.tsx' },
  { label: 'AugmentRequestPage(증강 요청 — 대상 영상)', file: 'src/pages/AugmentRequestPage.tsx' },
  {
    label: 'DashboardPage(대시보드 — 최근 영상/작업 현황)',
    file: 'src/pages/DashboardPage.tsx',
  },
  { label: 'NoticeListPage(공지 목록)', file: 'src/pages/NoticeListPage.tsx' },
  {
    label: 'EventTypeManagePage(이벤트유형 관리)',
    file: 'src/pages/manage/EventTypeManagePage.tsx',
  },
  {
    label: 'VerificationEventTypeSection(검증 이벤트 유형·질문)',
    file: 'src/features/eventType/components/VerificationEventTypeSection.tsx',
  },
  {
    label: 'LabelMasterManagePage(라벨 마스터 관리)',
    file: 'src/pages/manage/LabelMasterManagePage.tsx',
  },
  { label: 'WorkerStatPage(작업자 통계 — 월별)', file: 'src/pages/WorkerStatPage.tsx' },
  {
    label: 'LabelAttrDefPanel(라벨 속성 정의)',
    file: 'src/features/label/components/LabelAttrDefPanel.tsx',
  },
  {
    label: 'DeidentReportListPage(비식별 누락 신고 목록)',
    file: 'src/pages/manage/DeidentReportListPage.tsx',
  },
  {
    label: 'UnmappedCategorySection(산출물 가져오기 — 처음 보는 분류)',
    file: 'src/features/import/components/UnmappedCategorySection.tsx',
  },
  {
    label: 'ConfirmedMappingSection(산출물 가져오기 — 확정된 분류 대응)',
    file: 'src/features/import/components/ConfirmedMappingSection.tsx',
  },
  {
    label: 'ImportHistorySection(산출물 가져오기 — 가져온 내역)',
    file: 'src/features/import/components/ImportHistorySection.tsx',
  },
  {
    label: 'MarkingScanResultPanel(산출물 가져오기 — 이벤트 마킹 짝 목록)',
    file: 'src/features/import/components/MarkingScanResultPanel.tsx',
  },
  {
    label: 'MarkingImportProgressPanel(산출물 가져오기 — 일괄 적재 건별 결과)',
    file: 'src/features/import/components/MarkingImportProgressPanel.tsx',
  },
  {
    label: 'AiServerListCard(연동 서버 주소 — AI 장비 목록)',
    file: 'src/features/aiServer/components/AiServerListCard.tsx',
  },
  {
    label: 'PortalAugmentPage(포털 증강 — 요청 현황)',
    file: 'src/pages/portal/PortalAugmentPage.tsx',
  },
];

/**
 * `<thead>` 를 갖고 있지만 표 표면 규약의 판정 대상이 **아닌** 파일.
 *
 * ⚠ **"빠뜨린 것"이 아니라 판정을 마친 제외 건이다** — 다시 추가하기 전에 사유부터 볼 것.
 * ⚠ 여기 적힌 파일이 `<thead>` 를 잃으면(삭제·리팩터링) 위 전수 스캔이 **대칭차로 잡아낸다**
 *   — 죽은 제외 항목이 조용히 남지 않는다.
 */
const EXCLUDED_TABLE_FILES: { file: string; reason: string }[] = [
  {
    file: 'src/features/label/components/ShortcutCheatSheet.tsx',
    reason:
      '적용 대상 아님(확정) — `<thead className="sr-only">` 라 시각 헤더가 아예 없다. ' +
      'do_rules 1 의 근거가 "페이지 배경과 같은 색을 쓰면 열 구조가 먼저 읽히지 않는다"인데 ' +
      '보이지 않는 헤더에는 읽힐 열 구조 자체가 없다. 행 hover 도 정적 안내표라 해당 없음. ' +
      '헤더를 시각화하게 되면 그때 TABLES 로 옮긴다.',
  },
];

/* ------------------------------------------------------------------ *
 * 소스 파싱 유틸
 * ------------------------------------------------------------------ */

/**
 * 주석을 같은 길이의 공백으로 치환한다(줄바꿈은 보존 — 위반 위치의 줄번호가 어긋나지 않게).
 *
 * ⚠ 없으면 안 된다: 이 파일들의 **설명 주석 자체가 `<th>`·`<thead>` 라고 적고 있어서**,
 *   주석을 지우지 않으면 산문이 실제 마크업으로 잡혀 오탐이 난다(실제로 났다).
 */
function stripComments(src: string): string {
  const out = src.split('');
  let i = 0;
  let quote: string | null = null;
  const blank = (from: number, to: number) => {
    for (let k = from; k < to; k += 1) if (out[k] !== '\n') out[k] = ' ';
  };
  while (i < src.length) {
    const c = src[i];
    if (quote) {
      if (c === '\\') i += 1;
      else if (c === quote) quote = null;
      i += 1;
      continue;
    }
    if (c === "'" || c === '"' || c === '`') {
      quote = c;
      i += 1;
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end === -1 ? src.length : end + 2;
      blank(i, stop);
      i = stop;
      continue;
    }
    if (c === '/' && src[i + 1] === '/') {
      const nl = src.indexOf('\n', i);
      const stop = nl === -1 ? src.length : nl;
      blank(i, stop);
      i = stop;
      continue;
    }
    i += 1;
  }
  return out.join('');
}

/**
 * `<thead` 부터 **`</thead>`** 까지를 잘라낸다 — 표 헤더 배경 클래스는 `<thead>` 또는 그
 * 아래 헤더 `<tr>`/`<th>` 중 어디에도 붙을 수 있어 셋을 함께 덮는다.
 *
 * ⚠ **한 파일에 표가 여러 개일 수 있다** — 첫 `<thead>` 만 보면 둘째 표는 구조적으로 검사
 *   밖이 된다(실제로 `DashboardPage` 는 '최근 완료 영상'·'내 작업 현황' 두 표를 갖는다).
 *   그래서 파일 안의 **모든** `<thead>` 를 잘라 각각 판정한다.
 *
 * ⚠ 구 구현은 **첫 `</tr>`** 에서 잘랐다 — 헤더가 2행(그룹 헤더 + 실헤더)이면 2행째가
 *   통째로 검사 밖이었다. 지금은 `</thead>` 까지 본다.
 */
function headerRegions(rawContent: string, file: string): string[] {
  const content = stripComments(rawContent);
  const regions: string[] = [];
  for (let start = content.indexOf('<thead'); start !== -1; start = content.indexOf('<thead', start + 6)) {
    const end = content.indexOf('</thead>', start);
    if (end === -1) throw new Error(`${file}: 헤더의 </thead> 를 찾을 수 없음`);
    regions.push(content.slice(start, end));
  }
  if (regions.length === 0) throw new Error(`${file}: <thead 를 찾을 수 없음`);
  return regions;
}

/**
 * 헤더 영역(`<thead` ~ `</thead>`)의 **오프셋 구간**.
 *
 * 본문 축이 "헤더 안의 `<tr>`" 을 제외하는 데 쓴다 — 헤더 `<tr>` 은 헤더 축(굵기·토큰 도달·
 * 대문자화)이 따로 판정하므로 본문 크기 규칙을 겹쳐 걸면 같은 요소를 두 축이 다투게 된다.
 */
function headerSpans(content: string): [number, number][] {
  const spans: [number, number][] = [];
  for (let s = content.indexOf('<thead'); s !== -1; s = content.indexOf('<thead', s + 6)) {
    const e = content.indexOf('</thead>', s);
    spans.push([s, e === -1 ? content.length : e + '</thead>'.length]);
  }
  return spans;
}

/** `<table` ~ `</table>` 구간 중 **헤더를 가진 표**(= 데이터 표)만 돌려준다. */
function dataTableRegions(rawContent: string, file: string): string[] {
  const content = stripComments(rawContent);
  const regions: string[] = [];
  for (let start = content.indexOf('<table'); start !== -1; start = content.indexOf('<table', start + 6)) {
    const end = content.indexOf('</table>', start);
    if (end === -1) throw new Error(`${file}: </table> 를 찾을 수 없음`);
    const region = content.slice(start, end);
    // 헤더 없는 표는 자리표시(Skeleton) — 행이 없어 hover 축이 성립하지 않는다.
    if (region.includes('<thead')) regions.push(region);
  }
  if (regions.length === 0) throw new Error(`${file}: 헤더를 가진 <table> 을 찾을 수 없음`);
  return regions;
}

/**
 * `const NAME = '...'`(개행·문자열 연결 포함) 형태의 클래스 상수를 이름 → 값으로 모은다.
 *
 * 헤더/셀 클래스를 파일 상단 상수로 뽑는 것이 이 저장소의 관례라, `<th className={TH_CLASS}>`
 * 를 판정하려면 그 상수의 실제 문자열을 알아야 한다.
 */
function classConstants(content: string): Record<string, string> {
  const out: Record<string, string> = {};
  const literal = String.raw`(?:'[^']*'|"[^"]*"|\`[^\`]*\`)`;
  const re = new RegExp(
    String.raw`\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*(${literal}(?:\s*\+\s*${literal})*)\s*;`,
    'g',
  );
  for (const m of content.matchAll(re)) {
    out[m[1]] = m[2].replace(/['"`]/g, ' ').replace(/\s\+\s/g, ' ');
  }
  return out;
}

interface OpenTag {
  /** 여는 태그 전체 — `<th ... >` 또는 `<th ... />` */
  open: string;
  /** 자식이 없는 셀(체크박스 열의 빈 칸 등). 글자가 없으므로 타이포 판정 대상이 아니다. */
  empty: boolean;
  line: number;
  /** 여는 태그가 시작하는 문자 오프셋 — 헤더 영역 안/밖 판정에 쓴다. */
  offset: number;
}

/** 여는 태그를 원문 그대로 뽑을 수 있는 마크업 태그. */
type MarkupTag = 'th' | 'td' | 'table' | 'tbody' | 'tr';

/** `<th`/`<td` 등 여는 태그를 원문 그대로 뽑는다(자식 유무 포함). */
function openTags(content: string, tag: MarkupTag): OpenTag[] {
  const tags: OpenTag[] = [];
  // `<thead` 를 배제하기 위해 뒤따르는 문자를 제한한다.
  for (const m of content.matchAll(new RegExp(`<${tag}(?=[\\s>/])`, 'g'))) {
    const start = m.index as number;
    // 여는 태그의 끝(`>`)을 찾는다 — `{...}` 표현식·따옴표 안의 `>` 는 세지 않는다.
    let depth = 0;
    let quote: string | null = null;
    let end = -1;
    for (let i = start; i < content.length; i += 1) {
      const c = content[i];
      if (quote) {
        if (c === quote) quote = null;
        continue;
      }
      if (c === "'" || c === '"' || c === '`') quote = c;
      else if (c === '{') depth += 1;
      else if (c === '}') depth -= 1;
      else if (c === '>' && depth === 0) {
        end = i;
        break;
      }
    }
    if (end === -1) throw new Error(`<${tag} 여는 태그가 닫히지 않음 (offset ${start})`);
    const open = content.slice(start, end + 1);
    const rest = content.slice(end + 1).trimStart();
    tags.push({
      open,
      empty: open.endsWith('/>') || rest.startsWith(`</${tag}>`),
      line: content.slice(0, start).split('\n').length,
      offset: start,
    });
  }
  return tags;
}

/** 여는 태그에서 `className` 값을 **중괄호 균형**을 지켜 잘라낸다. */
function classNameValue(open: string): { literal?: string; expr?: string } | null {
  const at = open.search(/\bclassName\s*=/);
  if (at === -1) return null;
  let i = open.indexOf('=', at) + 1;
  while (i < open.length && /\s/.test(open[i])) i += 1;
  const c = open[i];
  if (c === '"' || c === "'") {
    const end = open.indexOf(c, i + 1);
    return end === -1 ? null : { literal: open.slice(i + 1, end) };
  }
  if (c !== '{') return null;
  let depth = 0;
  let quote: string | null = null;
  for (let k = i; k < open.length; k += 1) {
    const ch = open[k];
    if (quote) {
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === "'" || ch === '"' || ch === '`') {
      quote = ch;
      continue;
    }
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return { expr: open.slice(i + 1, k) };
    }
  }
  return null;
}

/** 템플릿 리터럴 본문의 `${IDENT}` 를 알려진 상수 값으로 치환한다. */
function expandTemplate(body: string, consts: Record<string, string>): string {
  return body
    .replace(/\$\{\s*([A-Za-z_$][\w$]*)\s*\}/g, (_, id: string) => consts[id] ?? ' ')
    .replace(/\$\{[\s\S]*?\}/g, ' ');
}

/**
 * className 표현식을 **소스에 나타난 순서 그대로** 펼친다.
 *
 * ⚠ 순서가 판정의 일부다 — tailwind-merge 는 **뒤에 온 것이 이긴다**. 구 구현은 문자열
 *   리터럴을 전부 모은 뒤 상수를 이어 붙여 순서를 뒤집었고, 그러면 `cn(TH_CLASS, 'text-body-md')`
 *   가 `'text-body-md' + TH_CLASS` 로 재구성되어 실제와 **반대 결론**이 난다.
 */
function expandExpr(expr: string, consts: Record<string, string>): string {
  const parts: string[] = [];
  const re = /'([^']*)'|"([^"]*)"|`([^`]*)`|([A-Za-z_$][\w$]*)/g;
  for (const m of expr.matchAll(re)) {
    if (m[1] !== undefined) parts.push(m[1]);
    else if (m[2] !== undefined) parts.push(m[2]);
    else if (m[3] !== undefined) parts.push(expandTemplate(m[3], consts));
    else {
      const v = consts[m[4] as string];
      if (v !== undefined) parts.push(v);
    }
  }
  return parts.join(' ');
}

/**
 * 셀에 **실제로 남는** 클래스 문자열 — 소스 조각을 이어 붙인 뒤 **`cn()`(tailwind-merge)을
 * 그대로 통과**시킨 결과다.
 *
 * ★2026-08-10 — 이 합성이 없으면 **가드 통과 / 렌더 위반**이 성립한다:
 *
 *     <th className={cn(TH_CLASS, 'text-body-md')}>영상명</th>
 *     // 소스 문자열에는 `text-table-header` 가 "있다"      → 구 가드 PASS
 *     // 그런데 twMerge 가 font-size 충돌로 그 토큰을 지운다 → DOM 은 17px + UA bold(700)
 *
 *   `text-table-header` 는 `src/lib/cn.ts` 가 **font-size 그룹**에 등록해 둔 커스텀 토큰이라
 *   같은 그룹의 뒤 클래스에 밀려 사라진다. 색 축도 같다(`cn(TH_CLASS, 'text-gray-500')` →
 *   `text-gray-600` 이 지워져 secondary-50 위 4.01:1 로 AA 미달). 두 축 모두 합성 결과로
 *   판정해야 닫힌다. 그리고 `cn(TH_CLASS, 'text-right')` 같은 관용구가 이미 실사용 중이라
 *   이 실패 모드의 진입점은 가정이 아니라 실재한다.
 */
function resolvedClass(open: string, consts: Record<string, string>): string {
  const v = classNameValue(open);
  if (!v) return '';
  if (v.literal !== undefined) return cn(v.literal);
  return cn(expandExpr(v.expr ?? '', consts));
}

/* ------------------------------------------------------------------ *
 * ① 열거 사각 — TABLES 미등록 파일은 전 축 무검사가 된다
 * ------------------------------------------------------------------ */

describe('적용관례 — 표 목록 자체의 완전성', () => {
  /**
   * ★열거는 새 파일을 놓친다 — `TABLES` 에 없는 `<thead>` 보유 파일은 아래 **어떤 케이스도
   * 참조하지 않아** 헤더 배경·글자·hover·본문 크기 전 축을 위반해도 초록이다.
   *
   * 그래서 규칙을 "이 11개 표"가 아니라 "`<thead>` 를 가진 파일 전부"로 표현하고,
   * 예외는 {@link EXCLUDED_TABLE_FILES} 에 **사유와 함께** 명시한다.
   */
  it('★TABLES_열거가_소스의_thead_보유_파일_전량과_일치한다_전수_스캔', () => {
    // given: src 전수에서 실제 마크업으로 <thead> 를 가진 .tsx (주석 속 언급은 제외)
    const scanned = readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
      .filter((f) => /\.tsx$/.test(f))
      // 테스트 파일은 기대값·픽스처로 마크업을 들고 있을 수 있으므로 제외한다.
      .filter((f) => !/\.test\.tsx$/.test(f))
      .map((f) => path.join('src', f))
      .filter((rel) => {
        const raw = readSrc(rel);
        return raw.includes('<thead') && stripComments(raw).includes('<thead');
      })
      .sort();
    expect(scanned.length, '스캔 결과 0건 — 파일 수집이 깨졌다').toBeGreaterThan(0);

    // when: 열거(판정 대상 + 명시적 제외)와 대조
    const declared = [...TABLES.map((t) => t.file), ...EXCLUDED_TABLE_FILES.map((e) => e.file)]
      .slice()
      .sort();

    const unregistered = scanned.filter((f) => !declared.includes(f));
    const stale = declared.filter((f) => !scanned.includes(f));

    // then: 대칭차가 비어야 한다
    expect(
      unregistered,
      '표 헤더를 가진 파일이 TABLES 에 등록되지 않았다 — 등록 전까지 이 파일의 헤더 배경·글자·' +
        'hover·본문 크기가 **한 축도 검사되지 않는다**. TABLES 에 추가하거나, 대상이 아니면 ' +
        `EXCLUDED_TABLE_FILES 에 사유와 함께 넣을 것:\n${unregistered.join('\n')}`,
    ).toEqual([]);
    expect(
      stale,
      '열거에는 있는데 소스에 <thead> 가 없다 — 파일이 사라졌거나 표가 걷혔다. ' +
        `열거에서 제거할 것:\n${stale.join('\n')}`,
    ).toEqual([]);
  });

  it('제외_목록의_모든_항목이_사유를_갖는다', () => {
    // 사유 없는 제외는 다음 사람이 판단할 근거가 없어 그대로 굳는다.
    for (const e of EXCLUDED_TABLE_FILES) {
      expect(e.reason.length, `${e.file}: 제외 사유가 비었거나 너무 짧다`).toBeGreaterThan(30);
    }
  });
});

/* ------------------------------------------------------------------ *
 * ② 헤더 배경
 * ------------------------------------------------------------------ */

/** 표 헤더 배경으로 허용되는 **유일한** 유틸리티. */
const HEADER_BG_TOKEN = 'bg-secondary-50';

/**
 * 영역 안의 배경 유틸리티 전량(`hover:`·`md:` 같은 변형 접두 포함).
 *
 * ★2026-08-10 — 구 구현은 **금지 열거**(`bg-(white|gray-*|neutral-*|transparent|bgLight)`)였고
 *   그래서 `bg-slate-50`·`bg-zinc-100`·`bg-[#FAFBFC]`(당시 콘텐츠 배경 별칭 실값)가 전부 통과했다.
 *   같은 파일의 **행 hover 축은 이미 gray/slate/zinc/stone 4계열 + 임의값을 막고 있어** 두 축이
 *   비대칭이었다 — 헤더 배경도 허용목록으로 뒤집어 축을 맞춘다. 새 색 팔레트가 추가돼도
 *   목록을 손대지 않아도 막힌다(열거는 새 값을 놓친다).
 */
function backgroundUtilities(region: string): string[] {
  return [...region.matchAll(/(?:[a-z-]+:)*bg-[A-Za-z0-9_[\]#().%/-]+/g)].map((m) => m[0]);
}

describe('적용관례 — 표 헤더 배경(DS-001 do_rules)', () => {
  it.each(TABLES)('$label — 표_헤더가_보조색_최옅단_배경을_쓴다', ({ file }) => {
    // given: 파일 안의 모든 헤더 영역(thead ~ </thead>)
    const regions = headerRegions(readSrc(file), file);

    // then: secondary 스케일의 가장 옅은 단계를 쓰고, 그것을 덮는 배경이 후손에 없다
    regions.forEach((region, i) => {
      const where = `${file}(표 ${i + 1}/${regions.length})`;
      expect(region, `${where}: 표 헤더 배경이 ${HEADER_BG_TOKEN} 이 아니다`).toContain(
        HEADER_BG_TOKEN,
      );
      // ⚠ `toContain` 만으로는 <thead> 가 규칙색을 갖고 그 안 <tr>/<th> 가 다른 배경으로
      //   덮는 형태를 통과시킨다 — 헤더 영역 안에서는 규칙색 **말고 다른 배경 자체**를 금지한다.
      const foreign = backgroundUtilities(region).filter(
        (u) => u.replace(/^(?:[a-z-]+:)+/, '') !== HEADER_BG_TOKEN,
      );
      expect(
        foreign,
        `${where}: 헤더 영역에 ${HEADER_BG_TOKEN} 밖의 배경(${foreign.join(' · ')})이 있다 — ` +
          `<thead> 에 규칙색을 걸어도 그 안 <tr>/<th> 의 배경이 위에 그려져 헤더가 페이지 ` +
          `배경과 같아진다. 허용목록이므로 계열·단계·임의 hex 를 가리지 않고 실패한다`,
      ).toEqual([]);
    });
  });

  it('secondary_50_이_페이지_배경_회색과_실제로_다른_색이다', () => {
    // do_rules 의 근거("페이지 배경과 같은 색을 쓰면 열 구조가 먼저 읽히지 않는다")가
    // 토큰 값 수준에서 성립하는지 — 두 값이 같아지면 이 규칙 자체가 무의미해진다.
    expect(secondary['50']).toBe('#EEF2F7');
    expect(secondary['50']).not.toBe(gray['50']);
  });

  it.each(TABLES)('$label — 헤더_글자색이_secondary_50_위에서_AA를_만족한다', ({ file }) => {
    // given: 글자가 있는 모든 <th> 에 **실제로 남는** 클래스(twMerge 통과 결과)
    const content = stripComments(readSrc(file));
    const consts = classConstants(content);
    const cells = openTags(content, 'th').filter((t) => !t.empty);
    expect(cells.length, `${file}: 글자 있는 <th> 0건 — 태그 수집이 깨졌다`).toBeGreaterThan(0);

    // when: 각 <th> 의 최종 회색 단계로 secondary-50 위 대비비를 계산
    // ⚠ 구 구현은 `const TH_CLASS =` 선언 지점 **한 곳**만 봤다 — 한 파일에 헤더 상수가
    //   둘 이상이면(TH_CLASS_SUB 등) 두 번째는 검사 밖이었고, `cn(TH_CLASS, 'text-gray-500')`
    //   처럼 나중에 덧칠해 지워지는 경우도 못 봤다. 이제 <th> 단위로 합성 결과를 본다.
    const violations: string[] = [];
    for (const cell of cells) {
      const resolved = resolvedClass(cell.open, consts);
      const steps = [...resolved.matchAll(/\btext-(?:gray|neutral)-(\d{2,3})\b/g)];
      if (steps.length === 0) {
        violations.push(
          `${file}:${cell.line} — 헤더 글자색(text-gray-*) 토큰이 남지 않았다: "${resolved}"`,
        );
        continue;
      }
      const step = steps[steps.length - 1][1];
      const ratio = contrastRatio(gray[step], secondary['50']);
      if (ratio < WCAG_AA_NORMAL_TEXT) {
        violations.push(
          `${file}:${cell.line} — text-gray-${step} 이 bg-secondary-50 위에서 AA 미달(${ratio.toFixed(2)}:1)`,
        );
      }
    }

    // then: 전부 AA(4.5:1) 이상
    expect(violations, `${file}: 헤더 글자색 대비 위반\n${violations.join('\n')}`).toEqual([]);
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

/* ------------------------------------------------------------------ *
 * ③ 헤더 글자 — 소스 계약으로 표현한다(jsdom 은 스타일을 계산하지 않는다).
 * ------------------------------------------------------------------ */

/**
 * 굵기 유틸리티 **전부**를 금지한다 — 낮추는 쪽만이 아니라 올리는 쪽도.
 *
 * ★2026-08-10 — 구 구현은 `font-(medium|normal|light)` 만 막았다. 그래서
 * `cn(TH_CLASS, 'font-bold')`(700)는 그대로 통과했는데, **이 라운드가 열린 원인이 바로
 * 헤더 굵기 700 드리프트**였다. 굵기는 `text-table-header` step(600)이 단독으로 정한다 —
 * 어느 방향이든 덧칠하면 화면마다 굵기가 갈린다.
 */
const WEIGHT_UTILITY =
  /\bfont-(thin|extralight|light|normal|medium|semibold|bold|extrabold|black)\b/;

/** 표 헤더 전용 타이포 step. 크기(14px)·굵기(600)를 이 토큰 하나가 정한다. */
const HEADER_TYPO_TOKEN = 'text-table-header';

describe('적용관례 — 표 헤더 글자(크기·굵기·대문자화)', () => {
  /**
   * ★2026-08-10 — 브라우저 실측에서 헤더 굵기가 **500 / 600 / 700 세 갈래**로 갈려 있었다.
   * 셋 다 이 파일의 구 케이스들을 통과했다(색·배경·hover 만 봤기 때문이다).
   *
   * 원인은 두 가지였고 아래 두 케이스가 각각을 막는다.
   *  1. `TH_CLASS` 에 굵기 유틸이 섞여 토큰의 600 을 덮음 → 케이스 ①
   *  2. 헤더 글자 클래스를 `<tr>`/`<thead>` 에만 걸어 `<th>` 에 도달하지 못함 → 케이스 ②
   *     **`font-weight` 는 상속되지만, 브라우저 UA 스타일시트의 `th { font-weight: bold }`
   *     가 `<th>` 에 *직접* 적용되므로 상속값을 이긴다.** 그래서 `<tr>` 에 600 을 걸어도
   *     실제 렌더는 700 이 된다. `font-size`·`color` 는 UA 선언이 없어 상속이 통해,
   *     **굵기만 조용히 어긋난다**(눈으로도 "조금 굵네" 정도로만 보인다).
   *
   * ⚠ **이 축은 jsdom 기반 렌더 테스트로는 못 잡는다** — jsdom 은 CSS 를 계산하지 않아
   *   `getComputedStyle(th).fontWeight` 가 UA/토큰 어느 쪽도 반영하지 않는다. 그래서
   *   런타임이 아니라 **소스 계약**으로 고정한다.
   *
   * ✅ **닫힌 실패 모드(다시 열지 말 것)** — "소스에 토큰이 있는데 DOM 에는 없다".
   *   구 목록 1번은 *"문자열이 소스에 나타나지 않으면 못 읽는다"* 였는데, 실제로 새던 것은
   *   그 반대였다: **문자열이 나타났고 가드가 읽었으며 그 판독이 DOM 과 정반대**였다
   *   (`cn(TH_CLASS, 'text-body-md')`). 지금은 {@link resolvedClass} 가 실제 `cn()` 을
   *   통과시켜 판정하므로 이 부류는 검사 안으로 들어왔다 — 판정을 다시 문자열 이어붙이기로
   *   되돌리면 그대로 재발한다.
   *
   * ⚠ 이 케이스들이 **못 보는 것**(브라우저 실측으로만 확인 가능):
   *  1. **동적으로 조립되는 클래스**(`` `text-${size}` ``, 배열 `.join(' ')`) — 소스에 문자열이
   *     통째로 나타나지 않으면 못 읽는다.
   *  2. **다른 파일에서 넘어오는 클래스** — 상수 해석은 같은 파일 안으로 한정한다.
   *     ★ 그래서 **헤더 셀을 공용 컴포넌트로 추출하면 이 축은 구조적으로 검사 불가**가 된다:
   *     추출한 파일에는 `<thead>` 가 없어 위 전수 스캔에도 잡히지 않고, 억지로 TABLES 에
   *     넣으면 `headerRegions` 가 throw 한다. **헤더 셀 컴포넌트를 만든다면 그 컴포넌트
   *     자신의 렌더 테스트로 굵기·색 계약을 따로 고정해야 한다.**
   *  3. **CSS 파일의 직접 선언·인라인 `style`** — Tailwind 유틸리티만 본다.
   *  4. **자식이 없는 `<th>`**(체크박스 열의 빈 칸)는 글자가 없어 판정에서 뺀다.
   *  5. 실제 계산된 px·weight 값(토큰 정의가 바뀌면 이 검사는 그대로 통과한다) — 토큰 값
   *     자체는 아래 `표_헤더_토큰이_14px_600_이다` 가 못박는다.
   *  6. **삼항 분기의 한쪽에만 토큰이 있는 경우**(`compact ? TH_COMPACT : TH_CLASS`) —
   *     두 분기를 합집합으로 펼치므로 한쪽만 규약을 지켜도 통과한다. 분기별 판정은
   *     표현식 평가가 필요해 여기서는 하지 않는다.
   *  7. ★**`DataTable` 의 공개 API `ColumnMeta.headerClassName`** — 호출부가 넘긴 문자열이
   *     `cn(HEADER_CLASS, …, meta?.headerClassName)` 의 **마지막**에 붙어 규약 토큰을 지울 수
   *     있다. 지금은 사용처 0건이라 아무도 밟지 않았을 뿐, **첫 사용자가 위 twMerge 절과
   *     똑같은 방식으로 침묵으로 깨진다.** 컬럼 정의 파일에는 `<thead>` 가 없어 이 가드가
   *     구조적으로 도달하지 못하는 통로다.
   */
  it.each(TABLES)('$label — 헤더에_굵기_유틸리티를_겹치지_않는다', ({ file }) => {
    // given: 헤더 영역(thead ~ </thead>) + 각 <th> 에 실제로 남는 클래스
    const content = stripComments(readSrc(file));
    const consts = classConstants(content);
    const cells = openTags(content, 'th').filter((t) => !t.empty);
    expect(cells.length, `${file}: 글자 있는 <th> 0건 — 태그 수집이 깨졌다`).toBeGreaterThan(0);

    const violations: string[] = [];
    headerRegions(readSrc(file), file).forEach((region, i) => {
      const m = region.match(WEIGHT_UTILITY);
      if (m) violations.push(`헤더 영역 ${i + 1} 에 ${m[0]}`);
    });
    for (const cell of cells) {
      const m = resolvedClass(cell.open, consts).match(WEIGHT_UTILITY);
      if (m) violations.push(`${file}:${cell.line} <th> 에 ${m[0]}`);
    }

    // then: 굵기는 text-table-header step(600) 이 단독으로 정한다
    expect(
      violations,
      `${file}: 표 헤더 굵기는 \`${HEADER_TYPO_TOKEN}\`(600) 이 단독으로 정한다. ` +
        `font-* 굵기 유틸을 겹치면 어느 방향이든 토큰의 600 을 덮어 화면마다 굵기가 갈린다:\n` +
        violations.join('\n'),
    ).toEqual([]);
  });

  it.each(TABLES)('$label — 헤더_타이포_토큰이_th_요소까지_도달한다', ({ file }) => {
    // given: 글자가 있는 모든 <th> (빈 칸은 판정 대상 아님)
    const content = stripComments(readSrc(file));
    const consts = classConstants(content);
    const cells = openTags(content, 'th').filter((t) => !t.empty);
    expect(cells.length, `${file}: 글자 있는 <th> 0건 — 태그 수집이 깨졌다`).toBeGreaterThan(0);

    // then: 토큰이 <th> 자신의 className 에 **살아남아** 있어야 한다
    //       (<tr>/<thead> 상속은 UA bold 에 지고, 뒤에 온 font-size 유틸에는 지워진다)
    const missing = cells
      .filter((t) => !resolvedClass(t.open, consts).includes(HEADER_TYPO_TOKEN))
      .map((t) => `${file}:${t.line} — ${t.open.replace(/\s+/g, ' ').slice(0, 100)}`);

    expect(
      missing,
      `${file}: \`${HEADER_TYPO_TOKEN}\` 가 <th> 에 살아남지 않았다. ` +
        `<tr>/<thead> 에만 걸면 브라우저 UA 기본 \`th { font-weight: bold }\`(700)가 상속값을 ` +
        `이겨 600 이 적용되지 않고, 뒤에 다른 text-* 크기 유틸을 겹치면 tailwind-merge 가 ` +
        `토큰을 지운다(둘 다 소스만 봐서는 통과한다):\n${missing.join('\n')}`,
    ).toEqual([]);
  });

  it.each(TABLES)('$label — 헤더_글자가_대문자화_유틸을_유지한다', ({ file }) => {
    // ★2026-08-10 — 이 describe 제목은 "대문자화"를 검증한다고 적혀 있었는데 정작
    //   `uppercase` 를 검사하는 케이스가 **하나도 없었다**(지워도 전부 초록). 제목이
    //   과대 진술이 되지 않도록 실제 계약으로 만든다.
    const content = stripComments(readSrc(file));
    const consts = classConstants(content);
    const cells = openTags(content, 'th').filter((t) => !t.empty);
    expect(cells.length, `${file}: 글자 있는 <th> 0건 — 태그 수집이 깨졌다`).toBeGreaterThan(0);

    const missing = cells
      .filter((t) => !/\buppercase\b/.test(resolvedClass(t.open, consts)))
      .map((t) => `${file}:${t.line} — ${t.open.replace(/\s+/g, ' ').slice(0, 100)}`);

    expect(
      missing,
      `${file}: 표 헤더는 \`uppercase tracking-wide\` 로 열 이름을 본문과 구분한다. ` +
        `일부 <th> 에만 빠지면 같은 헤더 행에서 대소문자 표기가 갈린다:\n${missing.join('\n')}`,
    ).toEqual([]);
  });

  it('표_헤더_토큰이_14px_600_이다', () => {
    // 위 케이스들은 "토큰을 쓰는가"만 본다 — 토큰 값 자체가 흔들리면 여기서 잡힌다.
    const [size, meta] = fontSize['table-header'];
    expect(size).toBe('14px');
    expect(meta.fontWeight).toBe('600');
  });
});

/* ------------------------------------------------------------------ *
 * ④ 본문 셀 크기 — 17px(DS-001 typography Do's)
 * ------------------------------------------------------------------ */

/** 표 본문 계열에 허용되는 크기 토큰(둘 다 17px 별칭). */
const BODY_SIZE_TOKENS = ['text-body-md', 'text-body'] as const;

/** tailwind 설정에 실제로 등록된 **크기** 스케일 이름 전량(`text-<key>` 의 `<key>`). */
const FONT_SIZE_KEYS = new Set(Object.keys(fontSize));

/**
 * 클래스 문자열에서 **크기를 정하는 유틸리티만** 골라낸다(색 `text-gray-600`·정렬 `text-right`
 * 는 같은 `text-` 접두를 쓰지만 크기 축이 아니다).
 *
 * ★2026-08-10 — 구 구현은 축소 토큰 6종 **열거**였다(`caption|label|sub|xs|body-sm|mono`).
 *   열거라서 ①`text-[13px]` 같은 **임의값** ②목록에 없던 기존 토큰(`text-table-header` 14px)
 *   ③ladder 에 새로 추가되는 step 이 전부 새어나갔다. 이제 **허용목록**으로 뒤집어,
 *   크기 유틸리티가 오면 그것이 17px 별칭일 때만 통과시킨다.
 *
 * 판정 근거는 tailwind 설정의 `fontSize` 키 집합이라 ladder 가 늘어도 자동으로 따라온다.
 */
function sizeUtilities(resolved: string): { raw: string; base: string }[] {
  const out: { raw: string; base: string }[] = [];
  for (const raw of resolved.split(/\s+/).filter(Boolean)) {
    // `md:text-caption` 처럼 변형 접두가 붙어도 크기 축은 그대로다 — 접두를 떼고 판정한다.
    const m = raw.match(/(?:^|:)(text-(\[[^\]]*\]|[^\s:[\]]+))$/);
    if (!m) continue;
    const [, base, value] = m;
    if (value.startsWith('[')) {
      // 임의값은 **색으로 확정되는 것만** 제외하고 크기로 본다(fail-closed).
      if (/^\[(#|rgb|hsl)/i.test(value)) continue;
      out.push({ raw, base });
      continue;
    }
    if (FONT_SIZE_KEYS.has(value)) out.push({ raw, base });
  }
  return out;
}

/** 17px 별칭이 아닌 크기 유틸리티 — 표 본문 계열 요소에 오면 그 아래 글자가 통째로 갈린다. */
function bodySizeViolations(resolved: string): string[] {
  return sizeUtilities(resolved)
    .filter((u) => !(BODY_SIZE_TOKENS as readonly string[]).includes(u.base))
    .map((u) => u.raw);
}

/**
 * 크기 축의 판정 대상 — 표 **본문 계열** 요소 전부.
 *
 * ★2026-08-10 — 구 구현은 `<table>` 루트(17px 선언 요구)와 `<td>`(축소 금지) **두 홉만** 봤고
 *   그 사이 한 홉이 비어 있었다. `<tbody>`/`<tr>` 에 축소 토큰을 걸면 표 전체가 14px 로 렌더되는데
 *   가드는 전부 통과했다. 진입점은 이론이 아니다 — `WorkerStatsTable` 에는 이미
 *   `<tbody className="divide-y divide-gray-50">` 가 있어, "이 표 좀 촘촘하게" 요청을 받은
 *   사람이 그 자리에 토큰 하나를 더하는 것이 가장 자연스러운 수정이다.
 *
 * ⚠ 헤더 영역 안의 `<tr>` 은 제외한다 — 그쪽은 헤더 축(굵기·토큰 도달·대문자화)이 판정한다.
 * ⚠ 배지·칩·식별자(영상 ID, 경로·해시)는 `<td>` **안쪽** `<span>` 에 붙어 있어 이 검사에
 *   걸리지 않는다 — 의도적이다. 그것들은 "읽는 데이터"가 아니라 별도 축이라 14px 이 규약이다.
 */
function bodySizeTargets(content: string): { tag: MarkupTag; t: OpenTag }[] {
  const spans = headerSpans(content);
  const inHeader = (offset: number) => spans.some(([s, e]) => offset >= s && offset < e);
  return (['table', 'tbody', 'tr', 'td'] as const).flatMap((tag) =>
    openTags(content, tag)
      .filter((t) => !inHeader(t.offset))
      .map((t) => ({ tag: tag as MarkupTag, t })),
  );
}

describe('적용관례 — 표 본문 셀 크기(DS-001 typography Do\'s)', () => {
  /**
   * ★2026-08-10 — 이 파일에는 **본문 셀 크기를 요구하는 단언이 하나도 없었다**(색 대비만
   * 순수 계산으로 확인했을 뿐 소스를 읽지도 않았다). 그 사이 실제로 한 행 안에서 크기가
   * 갈려 있었다(작업자 통계표의 오토라벨률·반려율만 14px).
   *
   * 완전 자동화는 "읽는 데이터 / 배지 / 식별자"를 구분해야 해서 성립하지 않는다. 그래서
   * **좁고 방어 가능한 두 계약**으로 나눠 고정한다:
   *   ① 표 루트가 17px 본문 토큰을 선언한다(셀은 이걸 상속한다)
   *   ② 표 **본문 계열 요소**(`<table>`·`<tbody>`·`<tr>`·`<td>`)가 그 크기를 덮어쓰지 않는다
   *
   * ⚠ 이 두 계약이 **못 보는 것**:
   *  · ★`<td>` **안쪽** 요소(`<span>`)의 축소 — 배지·식별자와 구분이 불가능해 판정하지 않는다.
   *    **적어만 두는 한계가 아니라 실제로 자주 밟히는 축이다** — 이번 라운드의 실물 위반
   *    (작업자 통계표의 오토라벨률·반려율)이 정확히 이 형태였고, 그래서 가드가 아니라
   *    **브라우저 실측**이 잡았다. 게다가 수정본조차 `<span>` 에 `text-body-md` 를 **명시**하는
   *    방식이라, 다음 사람이 셀 안에 `<span>` 을 새로 만들면 그대로 되밟는다.
   *    이 축의 최종 확인은 여전히 실측의 몫이다.
   *  · 셀 안에서 렌더되는 **다른 파일의 컴포넌트**가 스스로 작게 그리는 경우.
   *  · ★`DataTable` 의 공개 API `ColumnMeta.cellClassName` — 이미 적혀 있는 `headerClassName`
   *    (헤더 축 주석 7번)의 쌍둥이인데 목록에서 빠져 있었다. 호출부가 넘긴 문자열이
   *    `cn(CELL_CLASS, …, meta?.cellClassName)` 의 **마지막**에 붙어 17px 을 지운다.
   *    **소비자는 `ReviewListPage`·`UserManagePage` 두 곳**이며, 컬럼 정의 파일에는 `<thead>` 가
   *    없어 전수 스캔에도 잡히지 않는 **구조적 도달 불가** 통로다.
   *  · ★`<thead>` 가 **없는** `<table>` — 전수 스캔이 `<thead>` 보유 파일만 모으므로,
   *    key-value 표(`<tbody><tr><th>파일명</th><td>…`) 같은 정상 패턴은 **전 축 무검사**다.
   *    즉 *"새 표를 등록 없이 추가하면 실패한다"* 는 이 부류에는 성립하지 않는다.
   *  · ★셀 클래스 상수를 `cn()`/`clsx()` **호출로** 정의한 경우 — {@link classConstants} 가
   *    리터럴 연결만 매칭해 값이 빈 문자열이 되고, 그러면 축소 토큰이 "없는 것"으로 판정되어
   *    **fail-open** 이 된다. **헤더 축은 같은 상황에서 fail-closed**(토큰이 안 보이므로 실패)라
   *    두 축이 비대칭이다 — 2026-08-10 실측: 셀 상수를 `cn('… text-caption')` 으로 정의하면
   *    전 케이스 통과(위반이 안 보임), 같은 형태를 헤더 상수에 적용하면 3건 실패.
   *    이걸 fail-closed 로 바꾸려면 "해석 못 한
   *    식별자"를 위반으로 봐야 하는데, `cn(CELL_CLASS, alignClass(...), meta?.cellClassName)`
   *    처럼 해석 불가 식별자가 정상 코드에 널려 있어 잡음이 커진다.
   */
  it.each(TABLES)('$label — 표_루트가_17px_본문_토큰을_선언한다', ({ file }) => {
    const content = stripComments(readSrc(file));
    const consts = classConstants(content);

    const regions = dataTableRegions(readSrc(file), file);
    regions.forEach((region, i) => {
      // ⚠ 구 구현은 `region.indexOf('>')` 로 잘랐다 — `openTags()` 의 균형 파싱과 규칙이 달라
      //   `<table onScroll={(e) => …} className=…>` 이면 화살표 함수의 `>` 에서 잘려 className 을
      //   못 읽고 **규약을 지킨 코드가 false FAIL** 했다(개발자를 잘못된 수정으로 유도한다).
      const [root] = openTags(region, 'table');
      const resolved = resolvedClass(root.open, consts);
      const hit = BODY_SIZE_TOKENS.find((t) => new RegExp(`\\b${t}\\b`).test(resolved));
      expect(
        hit,
        `${file}(표 ${i + 1}/${regions.length}): <table> 에 본문 크기 토큰이 없다 — ` +
          `${BODY_SIZE_TOKENS.join(' / ')} 중 하나를 명시할 것. 없으면 셀이 문서 기본값(16px)로 ` +
          `떨어져 표마다 본문 크기가 갈린다. 실제 클래스: "${resolved}"`,
      ).toBeDefined();
    });
  });

  it.each(TABLES)('$label — 본문_계열_요소가_17px를_덮어쓰지_않는다', ({ file }) => {
    const content = stripComments(readSrc(file));
    const consts = classConstants(content);
    const targets = bodySizeTargets(content);
    expect(
      targets.filter((x) => x.tag === 'td').length,
      `${file}: <td> 0건 — 태그 수집이 깨졌다`,
    ).toBeGreaterThan(0);
    expect(
      targets.filter((x) => x.tag === 'tbody' || x.tag === 'tr').length,
      `${file}: 본문 <tbody>/<tr> 0건 — 태그 수집이 깨졌다`,
    ).toBeGreaterThan(0);

    const violations = targets.flatMap(({ tag, t }) =>
      bodySizeViolations(resolvedClass(t.open, consts)).map(
        (u) => `${file}:${t.line} <${tag}> 에 ${u}`,
      ),
    );

    expect(
      violations,
      `${file}: 표 본문은 17px 이다(DS-001 "본문 17px 이상"). <table>/<tbody>/<tr>/<td> 에 ` +
        `17px 별칭(${BODY_SIZE_TOKENS.join(' / ')}) 밖의 크기 유틸을 걸면 그 아래 데이터가 ` +
        `통째로 작아져 표마다·행마다 크기가 갈린다. 배지·식별자처럼 작아야 하는 것은 ` +
        `셀 안쪽 요소에 둘 것:\n${violations.join('\n')}`,
    ).toEqual([]);
  });

  it('본문_크기_토큰_두_별칭이_모두_17px_다', () => {
    for (const token of BODY_SIZE_TOKENS) {
      expect(fontSize[token.replace(/^text-/, '')][0], `${token} 이 17px 이 아니다`).toBe('17px');
    }
  });

  it('★크기_판정이_허용목록이라_열거에_없던_값도_막는다', () => {
    // 구 구현(축소 토큰 6종 열거)이 놓치던 세 부류가 실제로 잡히는지 판정기 수준에서 못박는다.
    // 이 셋은 전부 "금지 목록에 없어서" 통과하던 값이다.
    expect(bodySizeViolations('px-4 py-3 text-[13px]')).toEqual(['text-[13px]']); // 임의값
    expect(bodySizeViolations('px-4 text-table-header')).toEqual(['text-table-header']); // 14px 기존 토큰
    expect(bodySizeViolations('px-4 md:text-caption')).toEqual(['md:text-caption']); // 변형 접두
    // 색·정렬은 같은 `text-` 접두를 쓰지만 크기 축이 아니다 — 오탐이 나면 안 된다.
    expect(bodySizeViolations('text-gray-700 text-right text-[#FAFBFC] text-body-md')).toEqual([]);
  });

  it('구_축소_토큰들이_실제로_17px_미만이다', () => {
    // 허용목록 전환 이전의 금지 열거가 "정말 작은 것"이었는지 — ladder 가 바뀌면 여기서 잡힌다.
    for (const key of ['caption', 'label', 'sub', 'xs', 'body-sm', 'mono']) {
      const px = Number.parseInt(fontSize[key][0], 10);
      expect(px, `text-${key} 이 17px 미만이 아니다`).toBeLessThan(17);
    }
  });
});

/* ------------------------------------------------------------------ *
 * ⑤ 행 hover 표면
 * ------------------------------------------------------------------ */

/** 표면 배경과 겹쳐 구분이 약해지는 hover(do_rules 2 가 명시적으로 배제한 계열). */
const GRAY_ROW_HOVER = /hover:bg-(gray|neutral|slate|zinc|stone)-\d{2,3}\b/;

describe('적용관례 — 표 행 hover 표면(DS-001 do_rules)', () => {
  it('행_hover_전용_토큰이_tailwind_설정에_정의된다', () => {
    const rowHover = colors.rowHover as Record<string, string> | string | undefined;
    expect(rowHover, 'colors.rowHover 미정의 — 임의 hex 를 컴포넌트에 박게 된다').toBeDefined();
    const hex = typeof rowHover === 'string' ? rowHover : (rowHover as Record<string, string>).DEFAULT;
    expect(hex?.toUpperCase()).toBe(ROW_HOVER_HEX);
  });

  it.each(TABLES)('$label — 표_행_hover_가_전용_토큰을_쓴다', ({ file }) => {
    // ⚠ 구 구현은 **파일 단위 toContain** 이었다 — 파일에 `hover:bg-rowHover` 가 한 번만
    //   있으면 같은 파일의 **다른 표**는 회색이어도 통과했다(DashboardPage 는 표가 둘이다).
    //   그래서 판정 단위를 헤더를 가진 `<table>` 하나하나로 좁힌다.
    // ⚠ **이 축이 못 보는 것**: 판정이 "표 영역에 그 문자열이 있는가"라 **행 유형별로는
    //   판정하지 못한다** — 데이터 행에 규칙 토큰이 하나만 있으면, 요약행·빈 상태 행에
    //   다른 hover 를 줘도 그대로 통과한다. 행 단위 판정은 `<tr>` 이 데이터 행인지
    //   (map 콜백 안인지) 판별해야 해서 표현식 평가가 필요하다.
    const regions = dataTableRegions(readSrc(file), file);
    regions.forEach((region, i) => {
      const where = `${file}(표 ${i + 1}/${regions.length})`;
      expect(region, `${where}: 행 hover 가 rowHover 토큰을 쓰지 않는다`).toContain(
        'hover:bg-rowHover',
      );
      // 구 구현은 `hover:bg-gray-50` 문자열 하나만 금지해 gray-100/200·neutral 은 통과했다.
      const m = region.match(GRAY_ROW_HOVER);
      expect(
        m?.[0] ?? null,
        `${where}: 회색 계열 행 hover(${m?.[0]})가 남아있다 — 표면 배경과 겹쳐 구분이 약하다`,
      ).toBeNull();
    });
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

/* ------------------------------------------------------------------ *
 * ⑥ 표면 음영
 * ------------------------------------------------------------------ */

describe('적용관례 — 표면 음영(DS-001 do_rules)', () => {
  /** 카드 계열 — 저음영(sm)만 허용. */
  const CARD_FILES = [
    'src/components/common/Card.tsx',
    'src/components/common/KpiCard.tsx',
    'src/components/common/DataTable.tsx',
  ];
  /**
   * 오버레이·팝오버 계열 — md 이상 유지.
   *
   * ⚠ **이 배열은 열거라 새 파일을 놓친다.** 실제로 공용 `Drawer` 를 쓰지 않고 자체
   *   구현한 `HistoryDrawer`(배정 이력)가 목록 밖이라 오랫동안 검사되지 않았다.
   *   "음영이 있는가"는 파일마다 판단이 필요해 열거로 두되, **"토큰 밖 단계를 쓰지
   *   않는가"는 아래 전수 스캔이 맡는다**(그쪽이 실제 회귀를 막는 축이다).
   *   자체 구현 오버레이를 새로 만들면 이 배열에도 추가할 것.
   */
  const OVERLAY_FILES = [
    'src/components/common/Modal.tsx',
    'src/components/common/Drawer.tsx',
    'src/components/common/Popover.tsx',
    'src/components/common/Toast.tsx',
    // 공용 Drawer 미사용 자체 구현 — 위 누락 사고의 당사자다.
    'src/features/task/components/HistoryDrawer.tsx',
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

  /**
   * ★토큰 밖 음영 단계(xl/2xl) 금지 — **src 전수 스캔**.
   *
   * boxShadow 토큰은 sm/md/lg 3단만 정의돼 있다 — `shadow-xl` 은 Tailwind 기본
   * 팔레트(순수 검정 기반 `rgba(0,0,0,…)`)로 폴백해 KRDS 음영색(`rgba(14,21,40,…)`)과
   * 어긋난다. 값이 아니라 **색 계열 자체**가 달라지므로 눈으로는 "조금 진한 그림자"로만 보인다.
   *
   * ⚠ 2026-08-09 — 구 구현은 위 `OVERLAY_FILES` **열거 목록만** 검사했다. 그래서
   *   공용 `Drawer` 를 쓰지 않고 자체 구현한 `HistoryDrawer`(배정 이력)의 `shadow-xl` 을
   *   **구조적으로 못 봤고**, 브라우저 실측에서만 stock 음영(`rgba(0,0,0,0.1) 0 20px 25px -5px`)
   *   으로 잡혔다. **열거 방식은 새 파일을 놓친다** — 규칙이 "오버레이 4종"이 아니라
   *   "토큰 3단 밖을 쓰지 않는다"이므로 스캔도 소스 전체를 대상으로 한다.
   *
   * ⚠ 이 스캔이 못 보는 것:
   *  1. **동적으로 조립되는 클래스명**(`` `shadow-${size}` ``) — 문자열이 소스에 통째로
   *     나타나지 않으면 정규식이 못 잡는다.
   *  2. **CSS 파일의 `box-shadow` 직접 선언** — Tailwind 유틸리티만 본다.
   *  3. **인라인 `style={{ boxShadow }}`** 런타임 값.
   *  4. `shadow-{sm,md,lg}` 를 **용도에 맞게** 골랐는지(카드에 lg 등)는 판정하지 않는다 —
   *     그 축은 위 CARD_FILES / OVERLAY_FILES 케이스가 (열거로) 맡는다.
   */
  it('★소스_어디에도_토큰_밖_음영_단계_xl_2xl_가_없다_전수_스캔', () => {
    const files = readdirSync(path.join(repoRoot, 'src'), { recursive: true, encoding: 'utf-8' })
      .filter((f) => /\.tsx$/.test(f))
      // 테스트 파일은 기대값으로 금지 문자열을 들고 있을 수 있으므로 제외한다.
      .filter((f) => !/\.test\.tsx$/.test(f))
      .map((f) => path.join('src', f));
    expect(files.length, '스캔 대상 .tsx 0건 — 파일 수집이 깨졌다').toBeGreaterThan(100);

    const violations: string[] = [];
    for (const rel of files) {
      readSrc(rel)
        .split('\n')
        .forEach((line, i) => {
          for (const m of line.matchAll(/\bshadow-(xl|2xl)\b/g)) {
            violations.push(`${rel}:${i + 1} — shadow-${m[1]}`);
          }
        });
    }

    expect(
      violations,
      `boxShadow 토큰은 sm/md/lg 3단뿐이다. xl/2xl 은 Tailwind 기본값(순수 검정 기반)으로 ` +
        `폴백해 KRDS 음영색과 어긋난다 — 오버레이는 shadow-lg 를 쓸 것:\n` +
        violations.join('\n'),
    ).toEqual([]);
  });

  it('boxShadow_토큰이_DS_001_값이다', () => {
    expect(boxShadow.sm).toBe('0 1px 2px rgba(14,21,40,0.06)');
    expect(boxShadow.md).toBe('0 4px 10px rgba(14,21,40,0.08)');
    expect(boxShadow.lg).toBe('0 10px 24px rgba(14,21,40,0.12)');
  });
});
