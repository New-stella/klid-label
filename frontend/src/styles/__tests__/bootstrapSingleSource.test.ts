// 회귀 가드 — 폰트·전역 CSS 로드 지점은 하나뿐이다.
//
// 진입점이 둘(독립 앱 `main.tsx` / Remote `remote/AuthoringRemote.tsx`)이 되면서 CSS import 를
// 양쪽에 복제하면, 한쪽만 갱신될 때 **채널별로 스타일이 갈린다**(포털에서만 폰트가 빠지는 식).
// 그래서 CSS 부트스트랩 모듈 한 곳에만 두고 두 진입점이 그것을 import 한다.
//
// 이 가드는 "스타일시트 side-effect import" 자체를 센다 — 어느 파일이 무엇을 import 하는지
// 열거하지 않으므로 폰트가 늘어도 가드를 고칠 필요가 없다.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC_ROOT = path.resolve(__dirname, '../..');
const SCANNED_EXTENSIONS = ['.ts', '.tsx'];

/** CSS 부트스트랩 모듈 — 스타일시트를 import 해도 되는 유일한 파일. */
const BOOTSTRAP_MODULE = path.join(SRC_ROOT, 'styles', 'bootstrap.ts');

/** side-effect 스타일시트 import 문(`import '<...>.css';`)만 매칭한다. */
const STYLESHEET_IMPORT = /^[ \t]*import\s+['"][^'"]+\.css['"]\s*;?[ \t]*$/gm;

function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, acc);
      continue;
    }
    if (SCANNED_EXTENSIONS.some((ext) => entry.endsWith(ext))) acc.push(full);
  }
  return acc;
}

describe('CSS 부트스트랩 — 단일 지점', () => {
  it('폰트와_전역CSS_import는_부트스트랩_모듈_한_곳에만_있다', () => {
    // given
    const files = collectSourceFiles(SRC_ROOT);

    // 스캔이 실제로 돌았음을 먼저 확인한다(0건 스캔이 통과로 보이는 것을 막는다).
    expect(files.length).toBeGreaterThan(300);

    // when
    const importers = files.filter((file) => {
      STYLESHEET_IMPORT.lastIndex = 0;
      return STYLESHEET_IMPORT.test(readFileSync(file, 'utf-8'));
    });

    // then
    expect(importers.map((f) => path.relative(SRC_ROOT, f))).toEqual([
      path.relative(SRC_ROOT, BOOTSTRAP_MODULE),
    ]);
  });

  it('부트스트랩_모듈이_폰트와_전역CSS를_모두_싣는다', () => {
    // given / when
    const body = readFileSync(BOOTSTRAP_MODULE, 'utf-8');
    STYLESHEET_IMPORT.lastIndex = 0;
    const imports = body.match(STYLESHEET_IMPORT) ?? [];

    // then: 폰트 2종 + 전역 스타일 — 한 곳에 모여 있는지가 이 모듈의 존재 이유다.
    expect(imports.length).toBeGreaterThanOrEqual(3);
    expect(body).toContain('pretendard-gov');
    expect(body).toContain('d2coding');
    expect(body).toContain('global.css');
  });
});
