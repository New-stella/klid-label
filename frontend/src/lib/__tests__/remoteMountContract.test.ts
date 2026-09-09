// [@design INT-013]
/**
 * `remoteMountContract` 의 **불변식 하나**를 지킨다 — 이 파일은 아무것도 import 하지 않는다.
 *
 * ## 왜 그것이 불변식인가
 * 이 상수들은 앱뿐 아니라 **빌드 설정(`vite.config.ts`)이 Node 에서** 읽는다. 계약값을 양쪽에
 * 따로 적으면 한쪽만 갱신될 때 Host 가 원격 모듈을 못 찾거나 청크가 통째로 404 가 되므로,
 * 두 곳이 **같은 파일**을 읽게 두는 것이 이 분리의 목적이다.
 *
 * 그런데 이 파일에 import 가 하나라도 생기면 그 사슬이 `import.meta.env` 를 최상위에서
 * 평가하는 모듈(`buildChannel`)에 닿을 수 있고, 그 순간 **Node 에서 설정 로드 자체가 깨진다.**
 * 원래 이 파일이 `remoteMount.ts` 에서 갈라져 나온 이유가 바로 그것이다.
 *
 * ⚠ 그 파손은 **단위 테스트로는 드러나지 않는다** — 테스트는 브라우저 환경에서 도므로
 *   `import.meta.env` 가 살아 있다. 빌드를 돌려야 드러나고, 그때 원인이 「왜 vite 가 안 뜨지」로
 *   보인다. 그래서 소스를 **읽어서** 막는다.
 *
 * ⚠ 값 자체(`authoring` · `./PortalApp` · `/workspace/authoring` · `/label-remote/` …)의 고정은
 *   `remoteMount.test.ts` 가 이미 하고 있다. 여기서 중복하지 않는다.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

// ⚠ `import.meta.url` 로 잡지 않는다 — 테스트 환경(jsdom)에서 그 값이 file: 스킴이 아니라
//   ERR_INVALID_URL_SCHEME 로 죽는다(실측). 같은 저장소의
//   `remote/__tests__/remoteEntrypointGuard` 가 이미 `__dirname` 을 쓰므로 그 관례를 따른다.
const source = readFileSync(path.resolve(__dirname, '../remoteMountContract.ts'), 'utf-8');

/** 주석·문자열 안의 「import」 를 세지 않도록 «문장»만 본다. */
function importStatements(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => /^import\b/.test(line) || /^export\s+.*\bfrom\b/.test(line));
}

describe('remoteMountContract — 빌드 설정이 Node 에서 읽는 파일', () => {
  it('아무것도_import_하지_않는다_Node에서_평가되기_때문이다', () => {
    expect(importStatements(source)).toEqual([]);
  });

  it('재수출도_하지_않는다_다른_모듈로_사슬이_이어지면_같은_파손이_난다', () => {
    expect(source).not.toMatch(/^\s*export\s+.*\bfrom\s+['"]/m);
  });

  it('import_meta_를_읽지_않는다_Node에서_성립하지_않는_자리다', () => {
    // 주석에 설명으로 적힌 것은 잡지 않도록 «코드에서의 사용»만 본다.
    const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    expect(withoutComments).not.toMatch(/import\s*\.\s*meta/);
  });
});
