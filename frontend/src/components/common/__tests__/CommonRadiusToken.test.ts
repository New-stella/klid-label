// 공용 컴포넌트가 **borderRadius 토큰 4단(sm 4 / md 6 / lg 8 / full)** 안에서만 모서리를
// 적는지 소스 레벨에서 고정한다.
//
// ★ 왜 필요한가
//   `tailwind.config.js` 의 `borderRadius` 는 위 4단만 정의한다. 그보다 큰 단(xl 이상)을
//   쓰면 Tailwind **기본 팔레트로 폴백**해 12px 이 나오는데, 토큰에 없는 값이라 시안과
//   어긋나면서도 클래스가 **정상 생성되어 오류 없이 조용히** 적용된다. 같은 성질의 사고를
//   음영 축에서 이미 겪었고(`shadow-` 상위 단 → 순수 검정 기반 폴백), 그때 만든 전수 스캔
//   가드와 같은 골격이다.
//
// ⚠ mutation 확인 절차: `Modal.tsx` 의 dialog `rounded-lg` 를 토큰 밖 상위 단으로 되돌리면
//   이 테스트가 FAIL 해야 한다.
//
// ⚠ 이 스캔이 못 보는 것
//   1. 동적으로 조립되는 클래스명(`` `rounded-${size}` ``) — 소스에 문자열이 통째로 없으면 못 잡는다.
//   2. CSS 파일의 `border-radius` 직접 선언 · 인라인 `style` 런타임 값.
//   3. **스코프가 `src/components/common` 뿐이다** — 포털 화면(`src/pages/portal/*`)에 토큰 밖
//      모서리가 남아 있으나 다른 축의 작업 범위라 여기서 판정하지 않는다.

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const COMMON_DIR = path.resolve(__dirname, '..');

/**
 * 토큰 밖 모서리 단 — 정규식을 **분해해** 적는다.
 * Tailwind content 스캐너가 `src/**` 의 .ts 도 훑으므로, 금지 클래스명을 소스에 통째로
 * 적으면 그 클래스가 번들에 되살아난다(색 축에서 실제로 겪은 사고).
 */
const OUT_OF_TOKEN_RADIUS = /\brounded-(xl|2xl|3xl)\b/g;

function sourceFiles(dir: string): string[] {
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.tsx'))
    .map((f) => path.join(dir, f));
}

describe('공용 컴포넌트 모서리 — borderRadius 토큰 4단 준수', () => {
  it('★components_common_에_토큰_밖_모서리_단이_없다_전수_스캔', () => {
    const files = sourceFiles(COMMON_DIR);
    expect(files.length, '스캔 대상 .tsx 0건 — 파일 수집이 깨졌다').toBeGreaterThan(20);

    const violations: string[] = [];
    for (const file of files) {
      fs.readFileSync(file, 'utf-8')
        .split('\n')
        .forEach((line, i) => {
          for (const m of line.matchAll(OUT_OF_TOKEN_RADIUS)) {
            violations.push(`${path.basename(file)}:${i + 1} — rounded-${m[1]}`);
          }
        });
    }

    expect(
      violations,
      `borderRadius 토큰은 sm/md/lg/full 4단뿐이다. 그 밖의 단은 Tailwind 기본값으로 폴백한다:\n${violations.join('\n')}`,
    ).toEqual([]);
  });
});
