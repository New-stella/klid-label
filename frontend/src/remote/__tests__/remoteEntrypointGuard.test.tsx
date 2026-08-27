// 회귀 가드 — Remote 진입점이 "문서를 소유한 척" 하지 않는다.
//
// 포털 Host 는 우리를 **자기 React 트리 안에** 마운트한다. 따라서 독립 앱의 진입점
// (`main.tsx`)이 하던 세 가지는 Remote 진입점이 해서는 안 된다.
//
//   1. `ReactDOM.createRoot` / `React.StrictMode`
//      → Host 트리 안에 두 번째 root 가 생겨 이벤트·컨텍스트가 갈린다.
//   2. `window.addEventListener('vite:preloadError', ...)` 같은 전역 리스너
//      → 그 핸들러는 `window.location.reload()` 를 부른다. Host **문서 전체**를
//        새로고침시키므로 Remote 가 달아서는 안 된다(문서 소유자만 달 수 있는 조치다).
//   3. `document.getElementById('root')`
//      → 마운트 위치는 Host 가 정한다. 우리가 DOM 을 찾아 들어가면 Host 레이아웃을 침범한다.
//
// 이 셋은 "지금 없다"만으로는 유지되지 않는다 — 나중에 편의로 되살아나기 쉬워 소스 스캔으로 고정한다.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/AuthoringApp', () => ({
  // 진입점이 "무엇을 렌더하는가"만 보기 위해 프로바이더 트리 전체는 대역으로 세운다.
  AuthoringApp: () => <div data-testid="authoring-app-stub" />,
}));

const REMOTE_ROOT = path.resolve(__dirname, '..');
const SCANNED_EXTENSIONS = ['.ts', '.tsx'];

/** 진입점에 들어와서는 안 되는 표기. 이 파일(가드) 자신은 스캔에서 제외한다. */
const FORBIDDEN_TOKENS = [
  'createRoot',
  'StrictMode',
  // `window.` 접두를 떼고 메서드명만 본다 — 점 표기로 못 박으면
  // `window['addEventListener'](...)` 같은 대괄호 표기가 그대로 빠져나간다.
  // `src/remote/**` 에는 정당한 `addEventListener` 사용처가 없으므로 접두를 떼도 오탐이
  // 없다(정당한 사용처가 생기면 그때 다시 좁힌다).
  'addEventListener',
  'getElementById',
];

/**
 * 주석을 걷어낸 코드 본문 — 스캔은 **코드**만 본다.
 *
 * 진입점의 문서 주석은 "왜 `createRoot`·전역 리스너·`getElementById` 를 두지 않는가"를
 * 설명해야 하므로 그 낱말들을 반드시 포함한다. 원문 그대로 훑으면 그 설명이 위반으로 잡혀,
 * 결국 설명을 지우는 쪽으로 압력이 간다(이 저장소는 주석에 결정 근거를 싣는다).
 *
 * 걷는 범위는 **줄 첫머리에서 시작하는 주석**뿐이다 — 줄 첫머리 라인 주석과, 줄 첫머리에서
 * 열린 블록 주석(닫힐 때까지). 코드 뒤에 붙은 꼬리 주석과 줄 중간에서 열리는 블록 주석은
 * 남긴다. 덜 걷는 쪽이 보수적이라 위반을 놓치지 않는다.
 *
 * ⚠ 파일 전체에 정규식을 한 번 거는 방식(블록 주석을 먼저 일괄 삭제)은 쓰지 않는다. 그 방식은
 *   JS 의 주석 경계를 모르기 때문에, **라인 주석 안에 들어 있는 짝 없는 `/*`** 가 그보다 뒤에
 *   오는 블록 주석 닫기 기호까지를 통째로 삼켜 **그 사이의 실제 실행 코드가 스캔에서
 *   사라진다.** `eslint-disable` 류 지시문 주석이 흔해 악의 없이도 걸린다.
 *   회귀 가드: `라인주석_안의_블록주석_시작기호가_실제_코드를_삼키지_않는다`
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
      // 닫힌 뒤의 나머지는 코드다. 그 자리는 이미 줄 첫머리가 아니므로 그대로 남긴다.
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

function codeOf(file: string): string {
  return stripComments(readFileSync(file, 'utf-8'));
}

/** `src/remote` 아래 소스 파일 목록 — 테스트 디렉터리는 제외한다(구 표기를 설명해야 하는 곳). */
function collectRemoteSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === '__tests__') continue;
      collectRemoteSourceFiles(full, acc);
      continue;
    }
    if (SCANNED_EXTENSIONS.some((ext) => entry.endsWith(ext))) acc.push(full);
  }
  return acc;
}

describe('Remote 진입점 — Host 문서를 건드리지 않는다', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
  });

  // 아래 셋은 "스캐너 자신"에 대한 가드다 — 스캐너가 조용히 눈이 멀면 그 뒤 가드는 전부
  // 위반 0건으로 통과한다(죽은 가드).

  it('라인주석_안의_블록주석_시작기호가_실제_코드를_삼키지_않는다', () => {
    // given: 1행은 JS 엔진이 통째로 무시하는 라인 주석이고, 그 안에 짝 없는 `/*` 가 들어 있다.
    //        2행은 **실제로 실행되는 코드**이며 3행은 흔한 지시문 주석이다.
    const source = [
      '// 참고: JSX 는 /* 처럼 보이는 표현식도 허용한다',
      "window.addEventListener('vite:preloadError', () => location.reload());",
      '/* eslint-disable no-console */',
    ].join('\n');

    // when
    const body = stripComments(source);

    // then: 2행이 살아남아야 위반으로 잡힌다. 파일 전체에 블록 주석 정규식을 먼저 걸면
    //       1행의 `/*` 가 3행의 닫기 기호까지를 삼켜 2행이 사라지고, 가드가 조용히 죽는다.
    expect(body).toContain('addEventListener');
    // 1행(라인 주석)과 3행(줄 첫머리 블록 주석)은 그대로 걷혀야 한다.
    expect(body).not.toContain('참고');
    expect(body).not.toContain('eslint-disable');
  });

  it('블록_주석에_적힌_금지어는_위반으로_잡지_않는다', () => {
    // given: 진입점 문서 주석은 "왜 두지 않는가"를 설명하느라 금지어를 반드시 포함한다.
    const source = [
      '/**',
      ' * 여기서는 `createRoot` 도 `window.addEventListener` 도 쓰지 않는다 — 이유를 적어 둔다.',
      ' */',
      'export default function AuthoringRemote() {',
      '  return <AuthoringApp />;',
      '}',
    ].join('\n');

    // when
    const body = stripComments(source);

    // then: 설명은 걷히고 코드는 남는다(주석을 아예 안 걷는 쪽으로 되돌리면 설명을 지우는
    //       압력이 다시 생긴다).
    expect(body).not.toContain('createRoot');
    expect(body).not.toContain('addEventListener');
    expect(body).toContain('AuthoringApp');
  });

  it('금지_토큰은_대괄호_표기_우회도_잡는다', () => {
    // given: 점 표기로 못 박은 토큰(`window.addEventListener`)은 이 표기를 놓친다.
    const bracketed = "window['addEventListener']('vite:preloadError', () => location.reload());";

    // when
    const hit = FORBIDDEN_TOKENS.filter((token) => bracketed.includes(token));

    // then
    expect(hit).toEqual(['addEventListener']);
  });

  it('Remote_진입점은_createRoot도_StrictMode도_쓰지_않는다', () => {
    // given
    const files = collectRemoteSourceFiles(REMOTE_ROOT);

    // 스캔이 실제로 돌았음을 먼저 확인한다 — 파일을 못 찾았는데 "위반 0건 = 통과"로 읽히면
    // 그 가드는 죽은 가드다.
    expect(files.length).toBeGreaterThan(0);

    // when
    const offenders = files
      .map((file) => ({ file: path.relative(REMOTE_ROOT, file), body: codeOf(file) }))
      .filter(({ body }) => body.includes('createRoot') || body.includes('StrictMode'))
      .map(({ file }) => file);

    // then
    expect(offenders).toEqual([]);
  });

  it('Remote_진입점_소스에_전역_리스너와_DOM_조회가_없다', () => {
    // given
    const files = collectRemoteSourceFiles(REMOTE_ROOT);
    expect(files.length).toBeGreaterThan(0);

    // when
    const offenders: string[] = [];
    for (const file of files) {
      const body = codeOf(file);
      for (const token of FORBIDDEN_TOKENS) {
        if (body.includes(token)) offenders.push(`${path.relative(REMOTE_ROOT, file)}: ${token}`);
      }
    }

    // then
    expect(offenders).toEqual([]);
  });

  it('Remote_진입점은_window에_전역_리스너를_달지_않는다', async () => {
    // given: 모듈 평가(import) 시점이 실제 위험 구간이다 — `main.tsx` 가 바로 그 시점에 단다.
    const spy = vi.spyOn(window, 'addEventListener');
    vi.resetModules();

    // when
    await import('@/remote/AuthoringRemote');

    // then
    expect(spy.mock.calls.map(([type]) => type)).toEqual([]);
  });

  it('Remote_진입점이_기본_export로_렌더된다', async () => {
    // given
    const spy = vi.spyOn(window, 'addEventListener');
    const mod = await import('@/remote/AuthoringRemote');
    const AuthoringRemote = mod.default;

    // when
    render(<AuthoringRemote />);

    // then: Host 가 import 하는 것은 default export 이며, 그것이 프로바이더 트리를 렌더한다.
    expect(typeof AuthoringRemote).toBe('function');
    expect(screen.getByTestId('authoring-app-stub')).toBeInTheDocument();
    // 렌더 과정에서도 문서 전체를 새로고침시키는 핸들러를 달지 않는다.
    expect(spy.mock.calls.filter(([type]) => type === 'vite:preloadError')).toEqual([]);
  });
});
