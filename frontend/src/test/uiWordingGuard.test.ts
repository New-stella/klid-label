// 화면 문구 재유입 가드 — 확정 용어에서 벗어난 표기와 이모지가 UI 로 다시 새어 들어오는 것을 막는다.
//
// ★ 왜 필요한가 — 이 두 결함은 고쳐도 다시 들어온다
//   ① 용어: '반려'가 확정 용어(사양 SCREEN-019/023)인데 증강 화면 전체가 '거부'로 적혀 있었다.
//      리뷰어가 매번 잡아내야 하는 종류의 드리프트라 사람 눈에 맡기면 반드시 재발한다.
//   ② 이모지: 전체 구축 현황의 다운로드 버튼이 `📥` 였다. 이모지는 OS·폰트마다 모양이 달라지고,
//      스크린리더가 문자 이름("인박스 트레이")을 읽으며, 아이콘 라이브러리(lucide-react)의 크기·색
//      토큰과도 어긋난다. 새 버튼을 만들 때 이모지를 붙이는 편이 손쉬워 계속 유입된다.
//
// ★ 검사 대상은 "사람이 읽는 문구"뿐이다
//   주석은 벗겨내고 본다. 코드 식별자·API 필드·상태코드(`reject`/`REJECTED`/`rejectReason`)는
//   BE 계약이라 검사하지 않으며, 문서·주석의 '거부'(서버가 요청을 물리치는 뜻 등)도 대상이 아니다.
//
// ⚠ 이 가드가 못 보는 것
//   - 주석 안의 문구(의도적 제외) · 테스트 파일 · BE 응답 문자열을 그대로 노출하는 경로
//     (예: 서버 메시지 토스트)는 소스에 문자열이 없어 잡히지 않는다.
//   - 변수로 조립되는 문구(`` `${verb} 확정` ``)는 정적 스캔으로 잡히지 않는다.
//
// ⚠ mutation 확인 절차: 아무 컴포넌트의 JSX 텍스트를 '반려' → '거부'로 되돌리거나 이모지를 한 자
//   넣으면 각 테스트가 그 파일을 지목하며 FAIL 해야 한다. (실제로 확인함)

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC_DIR = path.resolve(__dirname, '..');

/** src 하위 비-테스트 .ts/.tsx 전량. 문구는 상수 파일(.ts)에도 산다. */
function sourceFiles(): string[] {
  return fs
    .readdirSync(SRC_DIR, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .filter((f) => !/(^|[\\/])__tests__[\\/]/.test(f) && !/\.test\.tsx?$/.test(f))
    .filter((f) => !f.startsWith('test' + path.sep) && !f.startsWith('test/'))
    .map((f) => path.join(SRC_DIR, f));
}

/**
 * 주석 제거 — 블록(`/* *\/`, JSX 의 `{/* *\/}` 포함)과 줄 주석.
 *
 * 줄 주석은 URL(`https://`)·정규식 등 오탐을 피하려고 **줄 전체가 주석인 경우**와
 * 따옴표·백틱이 없는 꼬리 주석만 지운다. 남는 문자열 리터럴 안의 `//` 는 건드리지 않는다.
 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^[ \t]*\/\/.*$/gm, '')
    .replace(/\/\/[^\n"'`]*$/gm, '');
}

function relative(file: string): string {
  return path.relative(SRC_DIR, file).split(path.sep).join('/');
}

describe('화면 문구 — 확정 용어 재유입 가드', () => {
  /**
   * 검수·활용 결정의 확정 용어는 '반려'다(사양 SCREEN-019 검수, SCREEN-023 증강 결과).
   *
   * 주석을 벗기면 사용자 노출 문구만 남으므로 **0건**이 기대값이다. 서버가 요청을 물리치는
   * 뜻의 '거부'(권한 거부·입력 거부)를 화면에 써야 한다면 그때 이 가드에 예외 목록을 만들되,
   * 검수/활용 결정 축에는 절대 쓰지 않는다.
   */
  it('사용자_노출_문구에_거부가_없다_확정_용어는_반려다', () => {
    const offenders = sourceFiles().flatMap((file) => {
      const lines = stripComments(fs.readFileSync(file, 'utf-8')).split('\n');
      return lines
        .map((line, i) => ({ line: line.trim(), no: i + 1 }))
        .filter(({ line }) => line.includes('거부'))
        .map(({ line, no }) => `${relative(file)}:${no} — ${line}`);
    });

    expect(offenders).toEqual([]);
  });
});

/**
 * 이모지·기호 글리프 기준선 — **현재 남아 있는 곳**을 파일 단위로 고정한다.
 *
 * 목적은 전량 제거가 아니라 **재유입 차단**이다. 새 파일이나 목록 밖 파일에서 이모지가 등장하면
 * 즉시 실패하고, 여기 적힌 곳을 정리하면 항목을 지워 기준선을 좁힌다.
 *
 * 값은 그 파일에 남아 있는 글리프의 정렬·중복제거 문자열이다(무엇이 남았는지 눈으로 보이게).
 * ⚠ 목록에 추가하려면 "왜 아이콘 라이브러리가 아닌가"를 함께 적을 것. 새 버튼 장식용은 사유가 아니다.
 */
const EMOJI_BASELINE: Record<string, string> = {
  // 증강 종류 카드의 장식 픽토그램(겨울/야간/우천/해상도). 아이콘 교체는 별도 디자인 결정.
  'features/augment/types.ts': '❄🌙🌧🖼',
  // 비식별 신고 프레임 표식.
  'features/label/components/FrameFilmstrip.tsx': '🚩',
  // 저장 상태 표식(편집 중 ●, 저장됨 ✓).
  'features/label/components/LabelHeader.tsx': '●✓',
  // 라벨 출처 표식(보간 🔗, 자동 🤖).
  'features/label/components/LabelPanel.tsx': '🔗🤖',
  // 접힘/펼침 표식.
  'features/label/components/MetaSection.tsx': '▸▾',
  // 라벨 출처 표식(수동 ✏️ 포함).
  'features/label/components/ObjectClassTree.tsx': '✏🔗🤖',
  // 그룹 접힘/펼침 표식.
  'features/review/components/ObjectListPanel.tsx': '▶▼',
  // 진행 방향 표식(aria-hidden).
  'pages/ReviewListPage.tsx': '▶',
  // 진행 방향 표식.
  'pages/portal/PortalHomePage.tsx': '▶',
};

/** 이모지·픽토그램·기하 글리프 범위. CJK·한글·화살표(→)·물결표는 산문에 쓰이므로 제외한다. */
const GLYPH_RANGES: ReadonlyArray<readonly [number, number]> = [
  [0x2300, 0x23ff], // 기타 기술 기호(⏸ ⏭ 등)
  [0x2460, 0x24ff], // 원문자
  [0x25a0, 0x27bf], // 기하 도형 + 딩벳(▶ ● ✓ ✏)
  [0x26a0, 0x26ff], // 기타 기호(⚠ 등) — 주석 밖에서만 잡힌다
  [0x2b00, 0x2bff], // 기타 기호·화살표
  [0x1f300, 0x1faff], // 이모지 본체
];
// ⚠ variation selector-16(U+FE0F)은 일부러 뺐다 — 눈에 보이지 않는 결합 문자라 기준선 문자열에
//    섞이면 사람이 읽을 수 없는 값이 된다. 그것을 달고 다니는 본체 글리프는 위 범위가 이미 잡는다.

function glyphsIn(text: string): string {
  const found = new Set<string>();
  for (const ch of text) {
    const code = ch.codePointAt(0) ?? 0;
    if (GLYPH_RANGES.some(([lo, hi]) => code >= lo && code <= hi)) found.add(ch);
  }
  return [...found].sort().join('');
}

describe('화면 문구 — 이모지 재유입 가드', () => {
  it('기준선에_없는_파일에는_이모지_기호_글리프가_없다', () => {
    const actual: Record<string, string> = {};
    for (const file of sourceFiles()) {
      const glyphs = glyphsIn(stripComments(fs.readFileSync(file, 'utf-8')));
      if (glyphs) actual[relative(file)] = glyphs;
    }

    // 파일 단위 비교 — 새 파일 유입도, 기존 파일의 글리프 추가도 함께 잡힌다.
    expect(actual).toEqual(EMOJI_BASELINE);
  });

  it('리포트_다운로드_버튼은_이모지가_아니라_아이콘_라이브러리를_쓴다', () => {
    const src = fs.readFileSync(path.join(SRC_DIR, 'pages/OverallStatPage.tsx'), 'utf-8');

    expect(src).toContain('leftIcon={Download}');
    // 라벨 텍스트는 유지 — 아이콘만 교체한 변경이다.
    expect(src).toContain('리포트 다운로드');
  });
});
