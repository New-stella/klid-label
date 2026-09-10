#!/usr/bin/env node
// [@design INT-013]
/**
 * 소스 «옆에» 남아 있는 낡은 tsc 산출물을 지운다 — 빌드·개발 서버 시작 전에 돌린다.
 *
 * ## 왜 필요한가 (2026-09-10 실측 사고)
 *
 * `tsconfig.node.json` 은 `composite: true` 라 **emit 을 끌 수 없다.** 예전에는 outDir 이
 * 없어서 `include` 에 든 파일 «옆에» `.js`·`.d.ts` 를 떨궜다. 지금은 outDir 을 지정해 더는
 * 떨구지 않지만, **그 수정 이전에 만들어진 파일은 그대로 남는다.**
 *
 * 그리고 그 잔재는 `.gitignore` 대상이라 **`git checkout -f` 로도 지워지지 않는다.**
 * 워크스페이스를 재사용하는 CI 에서는 영원히 남는다.
 *
 * ## 무엇이 깨지나 — 오류가 나지 않는다는 것이 최악이다
 *
 * Vite 의 설정 파일 해석 순서는 **`.js` 가 `.ts` 보다 앞**이다. 그래서 낡은 `vite.config.js`
 * 가 있으면 Vite 는 **그것을 설정으로 집는다.** 실측(2026-09-10 CI):
 *
 *   · Module Federation 배선이 통째로 빠진 채 빌드가 **성공**했다
 *   · 모듈 수만 4,276 → 4,201 로 달랐다(사람이 볼 이유가 없는 숫자다)
 *   · 산출물에 `remoteEntry.js` 가 없어 포털이 저작도구를 불러올 수 없었다
 *   · **경고도 오류도 없었다** — 같은 커밋이 로컬에서는 정상 빌드됐다
 *
 * ⚠ `src/lib/remoteMountContract.ts` 도 같은 위험을 진다. 그 파일은 MF 계약값(원격 모듈명·
 *   서빙 경로)의 단일 진실원이라, 낡은 `.js` 를 집으면 **옛 계약값으로 빌드된다.**
 *
 * ## 왜 npm 스크립트에 넣나
 *
 * CI 한 대를 손으로 청소해도 다음 사람이 다른 곳에서 같은 함정을 밟는다. 빌드가 스스로
 * 치우게 두면 **재실행만으로 해소**된다.
 */
import { existsSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 지울 대상 — `tsconfig.node.json` 의 `include` 에서 «기계적으로» 나온 목록이다.
 *
 * ⚠ 그 `include` 에 파일을 추가하면 여기도 함께 늘려야 한다. 늘리지 않으면 그 파일만
 *   같은 함정에 남는다.
 */
const INCLUDED_SOURCES = ['vite.config.ts', 'vitest.config.ts', 'src/lib/remoteMountContract.ts'];

const targets = INCLUDED_SOURCES.flatMap((src) => {
  const base = src.replace(/\.ts$/, '');
  return [`${base}.js`, `${base}.d.ts`, `${base}.js.map`, `${base}.d.ts.map`];
});

const removed = [];
for (const rel of targets) {
  const abs = path.join(FRONTEND_ROOT, rel);
  if (!existsSync(abs)) continue;
  rmSync(abs, { force: true });
  removed.push(rel);
}

if (removed.length > 0) {
  // ★ 조용히 지우지 않는다. 이 파일들이 있었다는 것은 «그 워크스페이스가 낡은 설정으로
  //   빌드하고 있었을 수 있다»는 뜻이라, 사람이 알아야 하는 사실이다.
  console.warn(
    '[clean-stale-tsc-output] 소스 옆의 낡은 tsc 산출물을 지웠습니다 — ' +
      'Vite 가 이것을 설정으로 집으면 배선이 조용히 빠집니다:\n  ' +
      removed.join('\n  '),
  );
}
