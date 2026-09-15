// 회귀 가드 — 크기를 rem 으로 직접 적지 않는다. [@design INT-013]
//
// ## 무엇을 지키나 (2026-09-15 개발망 실측)
//
// 포털 Host 는 `html { font-size: 10px }` 이라 **1rem 이 10px** 이다. 간격 유틸리티는 앵커에서
// `--spacing: 4px` 로 고정해 두었지만(`styles/global.css`), 그 고정은 `calc(var(--spacing) * N)`
// 꼴에만 먹는다. **`w-[9.5rem]` 처럼 rem 을 직접 적은 값은 그 고정을 비껴가 62.5% 로 줄어든다.**
// 실제로 「내 작업」 표의 열 폭(152 → 95px)과 표 최소 폭(832 → 520px)이 줄어 저장 시각이 만료
// 예정일 칸을 덮고 조작 버튼이 세로로 쌓였다.
//
// ## 규칙
//
// 1. Tailwind 임의값(`[...]`) 안에 rem 을 쓰지 않는다 — 전 소스 대상. px 로 적으면 관제 채널
//    (1rem = 16px)에서는 같은 값이라 관제 화면이 바뀌지 않는다.
// 2. 포털에 실리는 트리(포털 화면 · 라벨링 화면)에서는 rem 기반 이름 크기(`max-w-sm` 등 컨테이너
//    단계)도 쓰지 않는다 — 같은 이유로 줄어든다(`PortalLayout` 머리말의 `max-w-6xl` 사례).
//
// ⚠ 이 가드는 **소스 문자열**을 본다. 동적으로 조립한 클래스·CSS 파일 안의 rem 은 보지 못한다.

import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC_ROOT = path.resolve(__dirname, '../..');

function sourceFiles(dir: string): string[] {
  return readdirSync(path.join(SRC_ROOT, dir), { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.tsx?$/.test(f))
    .filter((f) => !/(^|[\\/])__tests__[\\/]/.test(f) && !/\.test\.tsx?$/.test(f))
    .map((f) => path.join(dir, f));
}

/** 주석을 걷어낸다 — 설명문 속 예시(`w-[9.5rem]`)가 걸리지 않게. */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** Tailwind 임의값 안의 rem — `w-[9.5rem]` · `max-h-[calc(100vh-2rem)]` · `max-h-[min(16rem,34vh)]`. */
const ARBITRARY_REM = /\[[^\]\s'"`]*\d(?:\.\d+)?rem[^\]\s'"`]*\]/g;

/** rem 기반 컨테이너 단계 이름 크기. */
const CONTAINER_SIZE = /\b(?:max-w|min-w|w)-(?:3xs|2xs|xs|sm|md|lg|xl|[2-7]xl)\b/g;

/** 포털 채널 산출물에 실리는 트리. 라벨링 화면은 포털이 그대로 재사용한다(`PortalLabelingPage`). */
const PORTAL_TREES = [
  'pages/portal',
  'components/portal',
  'features/portal',
  'pages/label',
  'features/label',
  'components/layout/PortalLayout.tsx',
];

function scan(files: string[], pattern: RegExp): string[] {
  const hits: string[] = [];
  for (const rel of files) {
    const src = stripComments(readFileSync(path.join(SRC_ROOT, rel), 'utf-8'));
    for (const m of src.matchAll(pattern)) hits.push(`${rel}: ${m[0]}`);
  }
  return hits;
}

describe('포털 임베드 — rem 직접 표기 금지', () => {
  it('★임의값_안에_rem_을_쓰지_않는다_Host_루트_10px_에서_줄어든다', () => {
    const files = ['pages', 'components', 'features', 'lib'].flatMap(sourceFiles);
    expect(files.length).toBeGreaterThan(100); // 스캔 대상이 비어 통과하는 공허한 성공을 막는다
    expect(scan(files, ARBITRARY_REM)).toEqual([]);
  });

  it('★포털에_실리는_트리에서_rem_기반_컨테이너_크기를_쓰지_않는다', () => {
    const files = PORTAL_TREES.flatMap((p) =>
      p.endsWith('.tsx') ? [p] : sourceFiles(p),
    );
    expect(files.length).toBeGreaterThan(20);
    expect(scan(files, CONTAINER_SIZE)).toEqual([]);
  });

  it('검사식이_실제로_잡는다_양성_대조', () => {
    expect('w-[9.5rem] max-h-[calc(100vh-2rem)] max-h-[min(16rem,34vh)]'.match(ARBITRARY_REM))
      .toHaveLength(3);
    expect('w-[152px] text-[13px]'.match(ARBITRARY_REM)).toBeNull();
    expect('max-w-sm w-2xl min-w-xs'.match(CONTAINER_SIZE)).toHaveLength(3);
    expect('max-w-wrap max-w-[384px] w-full'.match(CONTAINER_SIZE)).toBeNull();
  });
});
