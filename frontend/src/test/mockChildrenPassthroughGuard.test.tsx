// 목(mock)의 "자식을 그대로 통과시킨다"는 계약을 지킨다 — react-konva 67벌 + recharts 공용 1벌.
//
// ★ 왜 필요한가 — 이 계약을 검증하는 테스트가 한 건도 없었다 (2026-08-09 실측)
//   캔버스 계열 테스트 67개 파일이 각자 react-konva 목을 복제해 두고 있다. 그 목은
//   Stage/Layer/Group 을 `<div data-konva="...">{children}</div>` 로 바꿔 자식 트리를
//   그대로 통과시킨다. 이 자식 전달을 67개 파일에서 **동시에 끊고 전체 스위트를 돌렸더니
//   4개 파일 18건만 실패했다** — CanvasShellZoomArea · CanvasShellRotationGrid ·
//   CanvasShellSegmentWiring · review/LabelCanvas. 나머지 63개 파일은 레이어를 중첩 없이
//   직접 렌더하므로 자식이 통째로 사라져도 **전량 통과한다**.
//   recharts 도 같다 — 공용 목(setup.ts)의 ResponsiveContainer 자식 전달을 끊어도
//   그 목을 쓰는 통계 화면 테스트가 전부 통과했다.
//
//   즉 지금은 무해하지만, 목을 손대는 사람이 자식 전달을 떨어뜨리면 **아무 신호도 없이**
//   계약만 사라진다. 그 뒤에 중첩 렌더가 필요한 테스트를 새로 쓰는 사람은 자기 코드가
//   아니라 목이 원인이라는 데까지 한참을 돌아가게 된다.
//
// ★ 왜 각 파일이 아니라 여기 한 곳인가
//   목이 67벌로 복제돼 있어 파일마다 단언을 흩뿌리면 67개를 영구히 함께 유지해야 한다.
//   반면 계약 자체는 하나다 — "자식을 그대로 통과시킨다". 그래서 계약을 한 곳에서 검사한다.
//   새 복제본이 생겨도 자동으로 검사 대상에 들어온다.
//   (근본 해소는 목을 공유 모듈로 모으는 것이며, 그건 67개 파일을 건드리는 별건이다.)
//
// ★ 두 가지 검사 방식을 쓰는 이유
//   - react-konva: 목이 **각 테스트 파일 안에 복제**돼 있어 밖에서 실행해 볼 방법이 없다.
//     그래서 소스 스캔으로 모양을 본다.
//   - recharts: 목이 **공용 setup 한 곳**에 있어 실제로 렌더해 볼 수 있다. 모양이 아니라
//     동작을 본다(더 강한 검사라 가능한 쪽은 이렇게 한다).
//
// ★ 이 가드가 못 보는 것 (수치와 함께 읽을 것)
//   - konva 쪽은 소스 모양만 본다. `children` 을 다른 이름에 담아 넘기는 변형은 통과시키지
//     못하고 오탐한다(실패 메시지로 안내). 반대로 실행 시점의 조건부 분기는 보지 못한다.
//   - `data-konva` 속성명·prop 직렬화 등 목의 나머지 계약은 각 테스트가 이미 단언하고 있어
//     여기서 중복 검사하지 않는다.
//   - 목이 아예 없는(= konva 를 쓰지 않는) 테스트는 대상이 아니다.
//
// ⚠ 지우지 말 것 — "아무것도 안 잡는 테스트"처럼 보이는 것이 정상이다. 이 가드가 없으면
//   위 63개 파일이 계약 파손을 조용히 통과시킨다. 살아있음은 아래 양성 대조군 테스트가 증명한다.

import fs from 'node:fs';
import path from 'node:path';

import { render, screen } from '@testing-library/react';
import { ResponsiveContainer } from 'recharts';
import { describe, expect, it } from 'vitest';

const SRC_DIR = path.resolve(__dirname, '..');

/**
 * 검사 대상 목 모듈. 문자열을 조립해 두는 이유는 이 가드 파일 자신이 검사 대상으로 잡히지
 * 않게 하기 위함이다(아래 self 제외와 함께 이중 방어).
 */
const KONVA_MODULE = 'react-konva';
const MOCK_MARKERS = [`vi.mock('${KONVA_MODULE}'`, `vi.mock("${KONVA_MODULE}"`];

/**
 * 자식 전달로 인정하는 표기.
 * - `createElement(..., children)` / `createElement(..., children as ReactNode)` — 현재 67벌 전부
 * - `<div ...>{children}` — JSX 로 쓴 변형(현재 konva 목에는 없으나 미리 허용)
 *
 * 주의: `{ children, ...rest }` 같은 **구조분해**는 인정하지 않는다. 자식을 받기만 하고
 * 넘기지 않는 것이 정확히 이 가드가 잡으려는 결함이기 때문이다.
 */
const PASSTHROUGH_PATTERNS = [/,\s*children(\s+as\s+[^,)]+)?\s*,?\s*\)/, />\s*\{\s*children\s*\}/];

function hasPassthrough(factory: string): boolean {
  return PASSTHROUGH_PATTERNS.some((re) => re.test(factory));
}

/** src 하위 테스트 소스를 재귀 수집한다. */
function testSources(dir: string): string[] {
  return fs
    .readdirSync(dir, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.test\.tsx?$/.test(f))
    .map((f) => path.join(dir, f));
}

/**
 * `vi.mock('react-konva', ...)` 호출 전체를 괄호 균형으로 잘라낸다.
 * 정규식으로 끝을 찾으면 팩토리 안의 중첩 괄호에서 잘려 검사가 조용히 헛돌기 때문에
 * 문자 단위로 센다. 잘라내지 못하면 null 을 돌려주고, 그 자체를 실패로 취급한다(검사 사각).
 */
function extractMockCall(source: string): string | null {
  const start = MOCK_MARKERS.map((m) => source.indexOf(m)).filter((i) => i >= 0).sort((a, b) => a - b)[0];
  if (start === undefined) return null;

  let depth = 0;
  for (let i = source.indexOf('(', start); i < source.length; i += 1) {
    if (source[i] === '(') depth += 1;
    else if (source[i] === ')') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  return null;
}

const konvaMockFiles = testSources(SRC_DIR)
  // 이 가드 자신은 제외한다 — 아래 양성 대조군이 "자식을 넘기지 않는 팩토리" 문자열을
  // 의도적으로 들고 있어(가드가 살아있음을 증명), 제외하지 않으면 스스로를 위반으로 잡는다.
  .filter((f) => path.basename(f) !== path.basename(__filename))
  .filter((f) => {
    const src = fs.readFileSync(f, 'utf-8');
    return MOCK_MARKERS.some((m) => src.includes(m));
  });

describe('react-konva 목 — 자식 전달 계약(소스 스캔)', () => {
  it('★검사할_konva_목이_실제로_존재한다_가드_공회전_방지', () => {
    // 스캔 결과가 0건이면 아래 두 테스트는 "위반 없음"으로 항상 통과한다.
    // 목이 사라졌거나 마커 표기가 바뀌어 검사기가 눈이 먼 상태를 여기서 먼저 드러낸다.
    expect(
      konvaMockFiles.length,
      'konva 목을 하나도 찾지 못했다 — 목이 전부 사라졌거나 vi.mock 표기가 바뀌어 이 가드가 헛돌고 있다',
    ).toBeGreaterThan(0);
  });

  it('★모든_konva_목에서_팩토리를_잘라낼_수_있다_추출_실패는_검사_사각이다', () => {
    const unextractable = konvaMockFiles
      .filter((f) => extractMockCall(fs.readFileSync(f, 'utf-8')) === null)
      .map((f) => path.relative(SRC_DIR, f));

    expect(
      unextractable,
      '괄호 균형으로 vi.mock 호출을 잘라내지 못했다 — 잘라내지 못한 파일은 아래 계약 검사에서 그냥 빠진다(조용한 사각).\n' +
        unextractable.join('\n'),
    ).toEqual([]);
  });

  it('★모든_konva_목이_자식을_그대로_통과시킨다', () => {
    // given: konva 목을 가진 모든 테스트 소스
    // when: 각 목 팩토리에서 자식 전달 표기를 찾는다
    const offenders = konvaMockFiles
      .filter((f) => {
        const factory = extractMockCall(fs.readFileSync(f, 'utf-8'));
        return factory !== null && !hasPassthrough(factory);
      })
      .map((f) => path.relative(SRC_DIR, f));

    // then: 한 건도 없어야 한다
    expect(
      offenders,
      'react-konva 목이 자식을 통과시키지 않는다 — Stage/Layer 안에 중첩된 도형이 통째로 사라지는데,\n' +
        '중첩 없이 렌더하는 대다수 테스트는 그래도 통과하므로 아무 신호가 뜨지 않는다.\n' +
        "고치는 법: 목 팩토리의 createElement 마지막 인자로 children 을 넘긴다 — createElement('div', props, children).\n" +
        '자식을 다른 이름에 담아 넘기는 표기를 썼다면 이 가드가 오탐한 것이니 PASSTHROUGH_PATTERNS 에 그 표기를 추가한다.\n' +
        offenders.join('\n'),
    ).toEqual([]);
  });

  it('가드가_실제로_위반을_잡는다_양성_대조군', () => {
    // 이 가드가 "아무것도 안 잡는 테스트"로 굳지 않도록 판정기 자체를 고정한다.
    // 아래 두 문자열은 실제 목에서 가져온 모양이며, 자식 인자만 다르다.
    const passing = "createElement('div', { 'data-konva': name, ...rest }, children);";
    const passingAsCast = "createElement('div', { 'data-konva': 'Stage' }, children as ReactNode);";
    const passingJsx = 'const M = ({ children }) => <div data-konva="Layer">{children}</div>;';
    const broken = "createElement('div', { 'data-konva': name, ...rest });";
    const brokenReceivesOnly = 'const M = ({ children, ...rest }) => createElement("div", rest);';

    expect(hasPassthrough(passing)).toBe(true);
    expect(hasPassthrough(passingAsCast)).toBe(true);
    expect(hasPassthrough(passingJsx)).toBe(true);
    expect(hasPassthrough(broken)).toBe(false);
    expect(hasPassthrough(brokenReceivesOnly)).toBe(false);
  });

  it('괄호가_중첩된_팩토리도_끝까지_잘라낸다', () => {
    // 정규식으로 끝을 찾던 구현이 여기서 잘려 검사가 헛돌던 것을 고정한다.
    const source = [
      "vi.mock('react-konva', () => {",
      '  const p = (name: string) => (props: Record<string, unknown>) =>',
      "    createElement('div', { 'data-konva': name, ...props }, props.children);",
      "  return { Stage: p('Stage') };",
      '});',
      "const after = 'not part of the factory';",
    ].join('\n');

    const factory = extractMockCall(source);
    expect(factory).not.toBeNull();
    expect(factory).toContain("return { Stage: p('Stage') };");
    expect(factory).not.toContain('not part of the factory');
  });
});

describe('recharts 공용 목 — 자식 전달 계약(동작 검증)', () => {
  it('★ResponsiveContainer_목이_자식을_그대로_렌더한다', () => {
    // 공용 목은 setup.ts 에 있어 실제로 렌더해 볼 수 있다 — 모양이 아니라 동작을 본다.
    // jsdom 에서 실제 ResponsiveContainer 는 width/height 가 0 이라 차트가 비어 렌더되므로
    // 고정 크기 래퍼로 대체돼 있는데, 그 래퍼가 자식을 떨어뜨리면 통계 화면 테스트는
    // 여전히 전부 통과하면서 차트만 사라진다.
    render(
      <ResponsiveContainer width={600} height={240}>
        <div data-testid="chart-child" />
      </ResponsiveContainer>,
    );

    expect(screen.getByTestId('responsive-container')).toBeInTheDocument();
    expect(
      screen.getByTestId('chart-child'),
      'ResponsiveContainer 목이 자식을 렌더하지 않는다 — 차트 본문이 통째로 사라진다',
    ).toBeInTheDocument();
  });
});
