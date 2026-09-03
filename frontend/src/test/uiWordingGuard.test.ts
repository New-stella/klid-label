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
// ★ '거부'에는 예외 축이 하나 있다 — **요청 처리 축은 허용 · 검수/활용 결정 축은 절대 불가**
//   서버가 요청(또는 그 안의 개별 건)을 계약 위반으로 물리치는 뜻의 '거부'는 화면에 쓸 수 있다.
//   사람이 내리는 판정이 아니라 요청이 처리되지 않았다는 사실이라, 확정 용어 '반려'와 뜻이 다르다.
//   허용은 파일 단위가 아니라 **구절 단위**(REJECT_WORD_ALLOWLIST)다 — 예외를 준 파일에 훗날
//   진짜 검수·활용 결정의 '거부'가 들어와도 그대로 FAIL 해야 하기 때문이다.
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

/**
 * '거부' 예외 — **요청 처리 축**의 문구만 구절 단위로 좁게 허용한다.
 *
 * 파일 단위 예외를 만들지 않는 이유: 그 파일에 훗날 검수·활용 결정의 '거부'가 들어와도 통과해
 * 가드가 그 파일에서만 조용히 죽는다. 구절 단위면 예외 문구가 어디에 있든 허용되고,
 * 그 밖의 '거부'는 **같은 파일에서도** 여전히 FAIL 한다.
 *
 * ⚠ 항목을 추가하려면 "검수·활용 결정 축이 아닌 이유"를 why 에 적을 것. 문장 전체가 아니라
 *   뜻이 결정되는 **핵심 구절**만 넣는다(전문을 넣으면 한 글자만 다듬어도 예외가 풀린다).
 */
const REJECT_WORD_ALLOWLIST: ReadonlyArray<{ phrase: string; why: string }> = [
  {
    phrase: '거부된 건은 사유와 함께',
    // 시계열 일괄 조작 안내 — 서버가 전건을 받아 건별로 물리친다는 처리 결과이지 검수 판정이 아니다.
    why: '요청 처리 축 — 일괄 요청 중 서버가 물리친 건',
  },
  {
    phrase: '요청 전체가 거부됩니다',
    // 일괄 스킵 사유 입력 안내 — 사유가 비면 400 으로 요청 자체가 접수되지 않는다는 계약 설명이다.
    why: '요청 처리 축 — 계약 위반으로 접수되지 않는 요청',
  },
  {
    phrase: '목록에 없는 값이면 요청이 거부됩니다',
    // 포털 증강 요청 폼의 상시 안내 — 생성 조건 다섯이 비거나 값역 밖이면 접수 창구가 400 으로
    // 물리친다는 계약 설명이다. 결과물을 채택/반려로 가르는 결정 축이 아니며, 그 경로에는 검수
    // 자체가 없어 '반려'라는 판정이 존재하지 않는다.
    why: '요청 처리 축 — 값역 위반으로 접수되지 않는 요청',
  },
];

/** 허용 구절만 지우고 남긴다 — 같은 줄에 다른 '거부'가 있으면 그것은 그대로 잡힌다. */
function withoutAllowedRejectPhrases(line: string): string {
  return REJECT_WORD_ALLOWLIST.reduce((acc, { phrase }) => acc.split(phrase).join(''), line);
}

describe('화면 문구 — 확정 용어 재유입 가드', () => {
  /**
   * 검수·활용 결정의 확정 용어는 '반려'다(사양 SCREEN-019 검수, SCREEN-023 증강 결과).
   *
   * 주석을 벗기고 REJECT_WORD_ALLOWLIST 의 허용 구절을 지우면 **0건**이 기대값이다.
   * 서버가 요청을 물리치는 뜻의 '거부'(권한 거부·입력 거부)는 그 allowlist 에 구절 단위로 올려
   * 쓰되, 검수/활용 결정 축에는 절대 쓰지 않는다.
   */
  it('사용자_노출_문구에_거부가_없다_확정_용어는_반려다', () => {
    const offenders = sourceFiles().flatMap((file) => {
      const lines = stripComments(fs.readFileSync(file, 'utf-8')).split('\n');
      return lines
        .map((line, i) => ({ line: line.trim(), no: i + 1 }))
        .filter(({ line }) => withoutAllowedRejectPhrases(line).includes('거부'))
        .map(({ line, no }) => `${relative(file)}:${no} — ${line}`);
    });

    expect(offenders).toEqual([]);
  });

  /**
   * 예외 목록의 각 항목이 **실제로 쓰이는지** 확인한다 — 죽은 항목은 지우게 만든다.
   *
   * 예외는 조용히 죽는다. 화면 문구가 나중에 다듬어져 그 구절이 소스에서 사라져도 항목은 남고,
   * 아무도 그것이 왜 있는지 모른 채 그 옆에 새 항목을 더한다. 그렇게 부푼 목록은 '거부' 가드의
   * 실효 범위를 넓히기만 하고 근거는 잃는다. 그래서 쓰이지 않는 항목을 결함으로 본다.
   *
   * ⚠ 검사 대상은 첫 번째 테스트와 **정확히 같아야 한다** — sourceFiles()(테스트 파일 제외) +
   *   stripComments(주석 제외). 이 범위를 넓혀 src 전체를 훑으면 REJECT_WORD_ALLOWLIST 가 적힌
   *   이 파일 자신이 매칭되어 **항상 통과**한다(자기 자신을 근거로 삼는 함정). 마찬가지로 주석에만
   *   남은 구절도 사용자 노출 문구가 아니므로 '쓰인다'로 쳐서는 안 된다.
   *
   * 줄 단위로 보는 것도 의도다 — 예외는 withoutAllowedRejectPhrases 가 **한 줄 안에서** 지워야
   * 효력이 있으므로, 줄바꿈에 걸려 어느 줄과도 맞지 않는 구절은 있어도 아무것도 허용하지 못한다.
   */
  it('예외_목록의_각_구절이_실제_화면_문구에_존재한다', () => {
    const lines = sourceFiles().flatMap((file) =>
      stripComments(fs.readFileSync(file, 'utf-8'))
        .split('\n')
        .map((line) => line.trim()),
    );

    const dead = REJECT_WORD_ALLOWLIST.filter(
      ({ phrase }) => !lines.some((line) => line.includes(phrase)),
    ).map(
      ({ phrase, why }) =>
        `'${phrase}' — 이 구절이 화면 소스에 없다(사유로 적힌 예외: ${why}). ` +
        '문구가 다듬어진 것이면 REJECT_WORD_ALLOWLIST 의 phrase 를 현재 문구로 고치고, ' +
        '문구가 사라진 것이면 그 항목을 목록에서 삭제할 것. 쓰이지 않는 예외는 근거 없이 ' +
        "'거부' 가드의 범위만 넓힌다.",
    );

    expect(dead).toEqual([]);
  });
});

/**
 * 이모지·기호 글리프 기준선 — **현재 남아 있는 곳**을 파일 단위로 고정한다.
 *
 * 목적은 **재유입 차단**이다. 새 파일이나 목록 밖 파일에서 이모지가 등장하면 즉시 실패한다.
 * 값은 그 파일에 남아 있는 글리프의 정렬·중복제거 문자열이다(무엇이 남았는지 눈으로 보이게).
 *
 * 현재 기준선은 **비어 있다**(전량 아이콘 교체 완료). 즉 사용자 노출 코드의 이모지·기호 글리프는
 * 0건이며, 한 자만 새로 들어와도 그 파일을 지목하며 FAIL 한다.
 */
const EMOJI_BASELINE: Record<string, string> = {
  // ★ 비어 있는 것이 정상이다 — 남아 있던 9개 파일의 글리프를 전부 아이콘 라이브러리로 교체했다.
  //   증강 종류 픽토그램(❄🌙🌧🖼 → Snowflake/Moon/CloudRain/Scaling) · 확인요청 프레임(🚩 → Flag) ·
  //   저장 상태(●✓ → Circle/Check) · 라벨 출처(🔗🤖✏️ → Link2/Bot/Pencil) ·
  //   접힘/펼침(▸▾ ▶▼ → ChevronRight/ChevronDown) · 진행 방향(▶ → ChevronRight).
  //
  // ⚠ 항목을 다시 추가하려면 "왜 아이콘 라이브러리가 아닌가"를 함께 적을 것.
  //   새 버튼 장식용은 사유가 아니다 — lucide-react 에 맞는 아이콘이 없다는 근거가 필요하다.
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
