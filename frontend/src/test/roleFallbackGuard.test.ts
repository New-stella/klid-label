// 역할 폴백 재유입 가드 — 화면이 「역할이 없으면 작업자」로 채우지 못하게 못 박는다.
// [@design ADR-055] [@design ROLE-004] [@design AC-125]
//
// ★ 무엇을 세는가 (이 문단이 이 가드의 사양이다)
//   **폴백 연산자의 오른쪽에 역할 값이 오는 자리**를 센다. 두 축이다.
//     ① enum 참조 축 — `?? Role.WORKER` · `|| Role.REVIEWER` 처럼 `Role.*` 를 채우는 것
//     ② 값 축      — `?? 'WORKER'` 처럼 역할 **문자열 값**을 채우는 것.
//   ②를 따로 두는 이유가 이 가드의 핵심이다. 이 저장소는 「값이 아니라 이름으로 세면 놓친다」를
//   이미 겪었다 — `?? Role.WORKER` 라는 **철자**만 찾으면 `?? 'WORKER'` 로 한 글자 바꿔 쓴 같은
//   결함이 그대로 통과한다. 그래서 역할 값 목록은 손으로 적지 않고 `Role` 에서 뽑는다(역할이
//   늘면 검사 범위도 함께 는다).
//
// ★ 왜 이 축을 닫는가 — 폴백이 잉여인데 거짓말을 한다
//   `roleSatisfies` 는 첫 줄이 `if (!actual || !required) return false;` 라 역할이 없어도
//   fail-closed 다. 그래서 `?? Role.WORKER` 는 판정 결과를 **바꾸지 못한다**. 바꾸지 못하면서
//   「역할이 없으면 작업자다」라는 있지도 않은 규칙을 코드에 남기고, 그 인상이 언젠가 역할을
//   **표시하는** 자리로 번진다(실제로 상단 헤더·접근 거부 화면이 그렇게 번져 사실과 다른 역할을
//   사용자에게 보여줬다). 잉여를 지우는 것으로 그 번짐의 씨앗을 없앤다.
//
// ★ 주석·시험은 허용한다
//   구 구현을 설명하는 주석이 실제로 여럿 있고(왜 걷어냈는지가 그 주석에 적혀 있다) 시험은 옛
//   형태를 픽스처로 들고 있어야 한다. 그래서 **주석을 벗겨낸 실행 코드**만, **비-시험 파일**만 본다.
//
// ⚠ 이 가드가 못 보는 것 (수치와 함께 적어 둔다 — 「0건」만 적으면 다음 사람이 전부 덮은 줄 안다)
//   - 삼항 폴백(`claims?.role ? claims.role : Role.WORKER`). `: Role.WORKER` 로 세면 객체
//     리터럴의 정당한 값(`{ role: Role.WORKER, size: 100 }` — 질의 파라미터)까지 잡혀 오탐이 난다.
//     술어를 넓혀 그 축을 죽이느니 못 보는 것으로 두고 여기 적는다.
//   - 변수를 한 번 거쳐 채우는 형태(`const fallback = Role.WORKER; ... ?? fallback`).
//   - 괄호로 감싼 형태(`?? (Role.WORKER)`). 술어의 `Role\s*\.` 앞을 `[\s(]*` 로 넓히면 잡히지만
//     `?? (a || Role.WORKER)` 처럼 폴백이 아닌 식까지 걸려 의미가 흐려져 넓히지 않았다.
//   - 시험 파일 안의 실코드(대상에서 제외한다).
//
// ⚠ mutation 확인 절차: 네 화면 중 하나에서 `?? Role.WORKER` 를 되살리면 제거 축이 그 파일을
//   지목하며 FAIL 하고, 그 화면의 `roleSatisfies(...)` 게이트를 지우면 존치 축이 FAIL 해야 한다.
//   (실제로 되돌려 확인하고 원복했다.)

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { Role } from '@/lib/api/types';

const SRC_DIR = path.resolve(__dirname, '..');

/** 검사 대상 — src 하위 비-시험 `.ts`/`.tsx` 전량. */
function sourceFiles(): string[] {
  return fs
    .readdirSync(SRC_DIR, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .filter((f) => !/(^|[\\/])__tests__[\\/]/.test(f) && !/\.test\.tsx?$/.test(f))
    .filter((f) => !f.startsWith('test' + path.sep) && !f.startsWith('test/'))
    .map((f) => path.join(SRC_DIR, f));
}

/**
 * 주석 제거 — 블록(`/* *\/`)과 줄 주석. 문자열 리터럴 안의 `//` 는 건드리지 않는다.
 * (같은 저장소의 문구 가드와 동일한 제거기를 쓴다.)
 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^[ \t]*\/\/.*$/gm, '')
    .replace(/\/\/[^\n"'`]*$/gm, '');
}

/** 역할 값 전부 — 손으로 적지 않는다. 역할이 늘면 검사 범위도 함께 는다. */
const ROLE_VALUES: readonly string[] = Object.values(Role);

/** ① enum 참조 축 — `?? Role.X` · `|| Role.X` */
const ENUM_FALLBACK = /(?:\?\?|\|\|)\s*Role\s*\.\s*[A-Z][A-Z_]*/g;

/** ② 값 축 — `?? 'WORKER'` 처럼 역할 문자열을 채우는 것 */
function literalFallback(): RegExp {
  return new RegExp(`(?:\\?\\?|\\|\\|)\\s*(['"\`])(?:${ROLE_VALUES.join('|')})\\1`, 'g');
}

/** 실행 코드에서 역할 폴백을 뽑는다. 주석은 이미 벗겨진 문자열을 받는다. */
function roleFallbacks(code: string): string[] {
  return [...(code.match(ENUM_FALLBACK) ?? []), ...(code.match(literalFallback()) ?? [])];
}

describe('역할 폴백 재유입 가드 — 제거 축', () => {
  it('검사기가_실제로_동작한다_옛_형태를_두_축_모두_잡는다', () => {
    // 검사기 자신을 먼저 고정한다. 이게 없으면 정규식이 깨져도 아래가 「0건」으로 조용히
    // 통과해, 가드가 죽었다는 사실이 드러나지 않는다(이 저장소가 실제로 겪은 실패 유형이다).
    expect(roleFallbacks("const role = claims?.role ?? Role.WORKER;")).toHaveLength(1);
    expect(roleFallbacks("const role = claims?.role || Role.REVIEWER;")).toHaveLength(1);
    expect(roleFallbacks("const role = claims?.role ?? 'WORKER';")).toHaveLength(1);
    // 정당한 값은 잡지 않는다 — 질의 파라미터의 역할 값은 폴백이 아니다.
    expect(roleFallbacks("const params = { role: Role.WORKER, size: 100 };")).toHaveLength(0);
  });

  it('주석_안의_언급은_허용된다', () => {
    // 왜 걷어냈는지를 적은 주석이 실코드로 오인되면, 다음 사람이 그 설명을 지우게 된다.
    const commented = stripComments(
      "// 구 구현은 `?? Role.WORKER` 로 작업자를 채웠다.\n" +
        "/* 옛 형태: claims?.role ?? Role.WORKER */\n" +
        "const role = claims?.role;\n",
    );
    expect(roleFallbacks(commented)).toHaveLength(0);
  });

  it('실행_코드에_역할_폴백이_한_건도_없다', () => {
    const files = sourceFiles();
    // 스캔 0건 = 파일을 못 읽은 것이므로 실패로 다룬다(공허한 통과 차단).
    expect(files.length, 'src 소스 파일을 한 건도 스캔하지 못했다').toBeGreaterThan(0);

    const offenders = files
      .map((file) => ({ file, hits: roleFallbacks(stripComments(fs.readFileSync(file, 'utf-8'))) }))
      .filter(({ hits }) => hits.length > 0)
      .map(({ file, hits }) => `${path.relative(SRC_DIR, file)}: ${hits.join(' , ')}`);

    expect(offenders, '역할을 폴백으로 채우는 코드가 남아 있다').toEqual([]);
  });
});

/**
 * 존치 축 — 제거 축과 **짝**이다.
 *
 * 「없다」만 단언하면 게이트를 통째로 지워도 초록이다(폴백도 함께 사라지므로). 즉 제거만 보는
 * 가드는 '걷어냈다'와 '기능이 사라졌다'를 구분하지 못한다. 그래서 폴백을 걷어낸 네 화면이
 * **여전히 검수자 게이트를 물고 있는지**를 같은 라운드에서 확인한다.
 */
describe('역할 폴백 재유입 가드 — 존치 축', () => {
  /** 이번에 폴백을 걷어낸 네 화면. 게이트가 사라지면 여기서 잡힌다. */
  const GATED_PAGES = [
    'pages/TaskListPage.tsx',
    'pages/VideoListPage.tsx',
    'pages/NoticeDetailPage.tsx',
    'pages/NoticeListPage.tsx',
  ];

  it.each(GATED_PAGES)('%s 가 검수자 게이트를 그대로 물고 있다', (relative) => {
    const code = stripComments(fs.readFileSync(path.join(SRC_DIR, relative), 'utf-8'));
    expect(code, `${relative} 에서 검수자 판정이 사라졌다`).toMatch(
      /roleSatisfies\(\s*[^,)]+,\s*Role\s*\.\s*REVIEWER\s*\)/,
    );
  });
});
