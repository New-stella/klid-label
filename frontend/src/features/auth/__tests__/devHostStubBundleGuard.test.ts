// [@design INT-013]
// 회귀 가드 — Host 대역이 **운영 산출물에 실리지 않는다**.
//
// 이 장치는 임의 권한의 토큰을 요청에 실어 주므로, 운영 산출물에 남으면 **그 자체가 인증 우회
// 경로**다. 산출물에서 빼는 축은 **산출 시점에 굳는 값**(`import.meta.env.DEV`)이며, 그 값을
// 먼저 보는 분기 안에서 **동적으로** 불러야 번들러가 분기째 지우고 청크를 방출하지 않는다.
//
// ⚠⚠ **개발용 로그인 노출 값(`VITE_DEV_LOGIN_ENABLED`)만으로 가르면 안 된다.** 폐쇄망 반입
//    산출은 그 값을 **기본으로 켜서** 만들기 때문에(`lib/devLogin` 주석 — 반입 스크립트 두 곳),
//    그 값 하나에 기대면 **반입 산출물에 이 장치가 들어간다.** 그래서 이 가드는 「개발용 로그인
//    토글이 있다」가 아니라 **「산출 시점 값이 앞에 있다」**를 본다.
//
// ⚠ 이 가드는 **소스 배치**를 지킨다. 산출물에 실제로 빠졌는지는 운영 빌드를 떠서 확인해야
//   하며(그쪽이 최종 근거다), 이 가드는 그 확인을 **다음 사람이 잊었을 때** 배치가 무너지는
//   것을 막는다. 둘은 서로를 대체하지 않는다.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC_ROOT = path.resolve(__dirname, '../../..');
const SCANNED_EXTENSIONS = ['.ts', '.tsx'];

/** 대역 모듈 자신 — 스캔 대상에서 뺀다. */
const STUB_MODULE = 'features/auth/devHostStub.ts';

/**
 * 대역을 부를 수 있는 자리 — **문서를 소유한 단독 진입점**과 **개발용 로그인 화면** 둘뿐이다.
 *
 * ★ Remote 진입점(`remote/AuthoringRemote.tsx`)이 여기 없는 것이 이 목록의 핵심이다. 대역을
 *   앞쪽에만 두면 실제 Host 안에서는 **구조적으로 활성화될 수 없다** — 조건 검사가 아니라
 *   배치로 보장한다.
 */
const ALLOWED_CALLERS = ['main.tsx', 'features/auth/DevLoginPage.tsx'];

/** 산출 시점에 굳어 분기째 지워지는 값. 이것이 **앞에** 있어야 한다. */
const BUILD_TIME_DEV_FLAG = 'import.meta.env.DEV';

/**
 * 주석을 걷어낸 코드 본문 — 스캔은 **코드**만 본다.
 *
 * 이 저장소는 주석에 결정 근거를 싣는다. 원문 그대로 훑으면 「왜 이렇게 가르는가」를 설명하는
 * 문장이 위반으로 잡혀 결국 설명을 지우는 쪽으로 압력이 간다.
 *
 * 걷는 범위는 **줄 첫머리에서 시작하는 주석**뿐이다(꼬리 주석·줄 중간 블록 주석은 남긴다) —
 * 덜 걷는 쪽이 보수적이라 위반을 놓치지 않는다. 파일 전체에 정규식을 한 번 거는 방식은 쓰지
 * 않는다: 라인 주석 안의 짝 없는 `/*` 가 뒤의 블록 주석 닫기 기호까지 삼켜 **그 사이의 실제
 * 코드가 스캔에서 사라진다**(`remote/__tests__/remoteEntrypointGuard` 가 같은 이유로 겪은 함정).
 */
function stripComments(source: string): string {
  const kept: string[] = [];
  let inBlock = false;

  for (const line of source.split('\n')) {
    if (inBlock) {
      const close = line.indexOf('*/');
      if (close === -1) {
        kept.push('');
        continue;
      }
      inBlock = false;
      kept.push(line.slice(close + 2));
      continue;
    }

    const opener = line.match(/^[ \t]*(\/\/|\/\*)/);
    if (!opener) {
      kept.push(line);
      continue;
    }
    if (opener[1] === '//') {
      kept.push('');
      continue;
    }

    const open = line.indexOf('/*');
    const close = line.indexOf('*/', open + 2);
    if (close === -1) {
      inBlock = true;
      kept.push('');
      continue;
    }
    kept.push(line.slice(close + 2));
  }

  return kept.join('\n');
}

function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === '__tests__' || entry === 'test') continue;
      collectSourceFiles(full, acc);
      continue;
    }
    if (SCANNED_EXTENSIONS.some((ext) => entry.endsWith(ext))) acc.push(full);
  }
  return acc;
}

interface ScannedFile {
  readonly rel: string;
  readonly code: string;
}

function scan(): ScannedFile[] {
  return collectSourceFiles(SRC_ROOT)
    .map((file) => ({
      rel: path.relative(SRC_ROOT, file).split(path.sep).join('/'),
      code: stripComments(readFileSync(file, 'utf-8')),
    }))
    .filter(({ rel }) => rel !== STUB_MODULE);
}

function callers(files: ScannedFile[]): ScannedFile[] {
  return files.filter(({ code }) => code.includes('devHostStub'));
}

describe('Host 대역 — 운영 산출물에 실리지 않는 배치', () => {
  // 스캐너 자신에 대한 가드가 먼저다 — 스캐너가 조용히 눈이 멀면 그 뒤 가드는 전부
  // 위반 0건으로 통과한다(죽은 가드).
  it('스캔이_실제로_돌고_대역을_부르는_자리를_찾아낸다', () => {
    const files = scan();

    expect(files.length).toBeGreaterThan(100);
    expect(callers(files).map((f) => f.rel).sort()).toEqual([...ALLOWED_CALLERS].sort());
  });

  it('주석에_적힌_설명은_호출로_오해하지_않는다', () => {
    const source = [
      '/**',
      " * 여기서는 `devHostStub` 을 import 하지 않는다 — 이유를 적어 둔다.",
      ' */',
      'export const x = 1;',
    ].join('\n');

    expect(stripComments(source)).not.toContain('devHostStub');
  });

  it('★대역을_부르는_자리는_허용된_둘뿐이다_Remote_진입점은_포함되지_않는다', () => {
    const offenders = callers(scan())
      .map((f) => f.rel)
      .filter((rel) => !ALLOWED_CALLERS.includes(rel));

    expect(offenders).toEqual([]);
  });

  it('★Remote_진입점은_대역을_전혀_모른다', () => {
    // 배치로 보장하는 축이라 값으로 따로 못 박는다 — 위 목록이 넓어져도 이 한 줄이 남는다.
    const remote = scan().find((f) => f.rel === 'remote/AuthoringRemote.tsx');

    expect(remote).toBeDefined();
    expect(remote?.code).not.toContain('devHostStub');
  });

  it('★정적_import로_끌어오지_않는다_동적_import만_쓴다', () => {
    // 정적 import 는 분기와 무관하게 모듈 그래프에 남아, 분기를 지워도 청크가 방출될 수 있다.
    const staticImport = /import\s[^;]*from\s*['"][^'"]*devHostStub['"]/;

    const offenders = callers(scan())
      .filter(({ code }) => staticImport.test(code))
      .map((f) => f.rel);

    expect(offenders).toEqual([]);
  });

  it('★★모든_동적_import_앞에_산출시점_값이_먼저_있다', () => {
    // 「개발용 로그인 토글이 있다」가 아니라 「산출 시점 값이 앞에 있다」를 본다 — 반입 산출은
    // 그 토글을 기본으로 켜서 만들기 때문이다.
    const dynamicImport = /import\(\s*['"][^'"]*devHostStub['"]\s*\)/g;
    const offenders: string[] = [];

    for (const { rel, code } of callers(scan())) {
      const gate = code.indexOf(BUILD_TIME_DEV_FLAG);
      if (gate === -1) {
        offenders.push(`${rel}: 산출시점 값 없음`);
        continue;
      }
      const sites = [...code.matchAll(dynamicImport)];
      if (sites.length === 0) {
        offenders.push(`${rel}: 동적 import 없음`);
        continue;
      }
      for (const site of sites) {
        if ((site.index ?? 0) < gate) offenders.push(`${rel}: 산출시점 값보다 앞선 호출`);
      }
    }

    expect(offenders).toEqual([]);
  });
});
