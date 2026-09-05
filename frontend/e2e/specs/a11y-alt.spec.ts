/**
 * 접근성 자동 감사 ② — 대체 텍스트 (KWCAG 2.2 / WCAG 2.1 A).
 *
 * <p>검사 로직은 {@link ../fixtures/a11y-audit.ts} 의 `scanAlt` 가 소유한다
 * (같은 코드를 {@link ./a11y-detector-selftest.spec.ts} 가 합성 DOM 으로 검증한다).
 * 이 스펙은 <b>라우트를 돌며 모으고 판정</b>하는 드라이버다.
 *
 * <p>검사 두 가지:
 * <ol>
 *   <li><b>img alt 속성 누락</b> — 장식 이미지는 `alt=""` 로 <b>명시</b>해야 통과한다.
 *       속성 자체가 없으면 보조기술이 파일명을 읽어 버린다.</li>
 *   <li><b>접근 가능한 이름이 빈 `button`·`a`</b> — 아이콘 전용 컨트롤의 라벨 누락.</li>
 * </ol>
 *
 * <p><b>허용목록을 두지 않는다</b> — 현재 수용하기로 한 대체 텍스트 미달이 0건이다.
 * 빈 허용목록을 미리 만들어 두면 "여기 넣으면 통과"로 읽히므로 만들지 않는다.
 * 위반이 나오면 <b>고치지 말고 보고</b>한다(수정 여부는 사용자 판단).
 *
 * <p><b>조용한 그린 차단</b>: 라우트마다 스캔한 요소 수를 세어 출력하고 0건이면 실패시킨다.
 *
 * <p><b>BE 미기동</b>: 인증 픽스처가 예외를 던져 그대로 실패로 보고된다 —
 * 이 스펙은 어떤 조건에서도 `test.skip()` 을 쓰지 않는다.
 */

import type { Page } from '@playwright/test';

import {
  PORTAL_ROUTES,
  REVIEWER_ROUTES,
  formatScanCounts,
  openRoute,
  scanAlt,
  zeroScanRoutes,
  type A11yRoute,
  type AltFinding,
  type RouteScanCount,
} from '../fixtures/a11y-audit';
import { test, expect } from '../fixtures/auth.fixture';

interface MergedFinding extends AltFinding {
  routes: string[];
}

async function auditRoutes(
  page: Page,
  routes: A11yRoute[],
): Promise<{ counts: RouteScanCount[]; merged: MergedFinding[]; openFailures: string[] }> {
  const counts: RouteScanCount[] = [];
  const openFailures: string[] = [];
  const byKey = new Map<string, MergedFinding>();

  for (const route of routes) {
    try {
      await openRoute(page, route);
    } catch (err) {
      openFailures.push(
        `${route.path}: 진입/안정화 실패 — ${(err as Error).message.split('\n')[0]}`,
      );
      counts.push({ route: route.path, scanned: 0 });
      continue;
    }
    const { scanned, findings } = await scanAlt(page);
    counts.push({ route: route.path, scanned });
    for (const f of findings) {
      const key = `${f.kind}|${f.selector}|${f.detail}`;
      const hit = byKey.get(key);
      if (!hit) byKey.set(key, { ...f, routes: [route.path] });
      else if (!hit.routes.includes(route.path)) hit.routes.push(route.path);
    }
  }
  return { counts, merged: [...byKey.values()], openFailures };
}

function buildReport(title: string, counts: RouteScanCount[], merged: MergedFinding[]): string {
  const lines = [formatScanCounts(title, counts)];
  lines.push(`\n--- 대체 텍스트 위반 (${merged.length}건) ---`);
  lines.push(
    merged.length > 0
      ? merged
          .map(
            (f) =>
              `  [${f.kind}] ${f.selector}\n      ${f.detail}\n      화면: ${f.routes.join(' · ')}`,
          )
          .join('\n')
      : '  없음',
  );
  return lines.join('\n');
}

async function runAudit(page: Page, title: string, routes: A11yRoute[]): Promise<void> {
  const { counts, merged, openFailures } = await auditRoutes(page, routes);
  const report = buildReport(title, counts, merged);
  // eslint-disable-next-line no-console -- 감사 리포트는 표준출력이 전달 수단이다.
  console.log(report);

  const problems = [
    ...openFailures,
    ...zeroScanRoutes(counts).map(
      (r) => `${r}: 스캔 요소 0건 — 화면이 그려지지 않았다(위반 0건을 통과로 읽을 수 없다)`,
    ),
    ...merged.map((f) => `${f.routes[0]} [${f.kind}] ${f.selector}: ${f.detail}`),
  ];
  expect(problems, `${title} 접근성 감사 실패\n${report}\n`).toEqual([]);
}

test.describe('접근성 감사 — 대체 텍스트 (KWCAG 2.2 / WCAG A)', () => {
  test('REVIEWER_내부_화면_대체_텍스트', async ({ reviewerPage }) => {
    test.setTimeout(420_000);
    await runAudit(reviewerPage, 'REVIEWER 내부 화면 · 대체 텍스트', REVIEWER_ROUTES);
  });

  test('PORTAL_외부_화면_대체_텍스트', async ({ portalPage }) => {
    test.setTimeout(180_000);
    await runAudit(portalPage, 'PORTAL 외부 화면 · 대체 텍스트', PORTAL_ROUTES);
  });
});
