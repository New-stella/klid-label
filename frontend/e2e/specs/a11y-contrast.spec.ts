/**
 * 접근성 자동 감사 ① — 명도 대비 (KWCAG 2.2 / WCAG 2.1 AA).
 *
 * <p>측정 로직은 {@link ../fixtures/a11y-audit.ts} 의 `scanContrast` 가 소유한다
 * (같은 코드를 {@link ./a11y-detector-selftest.spec.ts} 가 합성 DOM 으로 검증한다).
 * 이 스펙은 <b>라우트를 돌며 모으고 판정</b>하는 드라이버다.
 *
 * <ul>
 *   <li>기준: 일반 텍스트 <b>4.5:1</b> · 큰 글자(24px 이상, 또는 18.66px 이상이며 bold) <b>3:1</b></li>
 *   <li>기존 미달은 허용목록으로 통과시킨다 — {@link ../fixtures/a11y-baseline.ts}.
 *       허용목록에 없는 위반은 실패다.</li>
 *   <li>이 스펙은 결함을 <b>고치지 않고 드러내기만</b> 한다.</li>
 * </ul>
 *
 * <p><b>조용한 그린 차단</b>: 라우트마다 스캔한 요소 수를 세어 출력하고, 0건이면 실패시킨다.
 * 로딩 중이거나 가드에 튕긴 화면을 "위반 0건 = 통과"로 읽지 않기 위함이다.
 *
 * <p><b>BE 미기동</b>: 인증 픽스처가 BE `/v1/dev/tokens` 로 실서명 JWT 를 받는다. BE 가 없으면
 * 픽스처가 예외를 던지고 그대로 <b>실패</b>로 보고된다 — 이 스펙은 어떤 조건에서도
 * `test.skip()` 을 쓰지 않는다(조용히 지나가지 않게).
 */

import type { Page } from '@playwright/test';

import {
  PORTAL_ROUTES,
  REVIEWER_ROUTES,
  formatScanCounts,
  openRoute,
  scanContrast,
  zeroScanRoutes,
  type A11yRoute,
  type ContrastFinding,
  type RouteScanCount,
} from '../fixtures/a11y-audit';
import { findContrastAllowance, type ContrastAllowance } from '../fixtures/a11y-baseline';
import { test, expect } from '../fixtures/auth.fixture';

/** 라우트를 가로질러 합친 위반. 같은 조합이 여러 화면에 나오면 한 줄로 묶는다. */
interface MergedFinding extends ContrastFinding {
  routes: string[];
}

/** 라우트를 순회하며 위반을 모은다. 진입 실패는 그 라우트의 문제로 기록하고 계속 진행한다. */
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
      // 진입 자체가 실패한 라우트는 "위반 0건"이 아니라 미검증이다 — 실패로 남긴다.
      openFailures.push(
        `${route.path}: 진입/안정화 실패 — ${(err as Error).message.split('\n')[0]}`,
      );
      counts.push({ route: route.path, scanned: 0 });
      continue;
    }
    const { scanned, findings } = await scanContrast(page);
    counts.push({ route: route.path, scanned });
    for (const f of findings) {
      const key = `${f.fg}|${f.bg}|${f.need}|${f.selector}`;
      const hit = byKey.get(key);
      if (!hit) byKey.set(key, { ...f, routes: [route.path] });
      else if (!hit.routes.includes(route.path)) hit.routes.push(route.path);
    }
  }
  return { counts, merged: [...byKey.values()].sort((a, b) => a.ratio - b.ratio), openFailures };
}

function formatFinding(f: MergedFinding, allowance?: ContrastAllowance): string {
  const head =
    `  ${f.ratio.toFixed(2)}:1 (기준 ${f.need}:1) | ${f.fontSize} | ${f.fg} on ${f.bg}\n` +
    `      ${f.selector}  "${f.text}"\n` +
    `      화면: ${f.routes.join(' · ')}`;
  return allowance ? `${head}\n      허용 근거: ${allowance.reason}` : head;
}

/** 감사 결과를 사람이 읽을 리포트로 만든다(통과·실패 무관하게 항상 출력). */
function buildReport(
  title: string,
  counts: RouteScanCount[],
  merged: MergedFinding[],
): { report: string; violations: MergedFinding[] } {
  const allowed: string[] = [];
  const violations: MergedFinding[] = [];
  for (const f of merged) {
    const allowance = findContrastAllowance(f);
    if (allowance) allowed.push(formatFinding(f, allowance));
    else violations.push(f);
  }
  const lines = [formatScanCounts(title, counts)];
  lines.push(`\n--- 허용목록 적중 (${allowed.length}건 — 인지·수용한 미달) ---`);
  lines.push(allowed.length > 0 ? allowed.join('\n') : '  없음');
  lines.push(`\n--- 허용목록 밖 위반 (${violations.length}건) ---`);
  lines.push(violations.length > 0 ? violations.map((f) => formatFinding(f)).join('\n') : '  없음');
  return { report: lines.join('\n'), violations };
}

async function runAudit(page: Page, title: string, routes: A11yRoute[]): Promise<void> {
  const { counts, merged, openFailures } = await auditRoutes(page, routes);
  const { report, violations } = buildReport(title, counts, merged);
  // eslint-disable-next-line no-console -- 감사 리포트는 표준출력이 전달 수단이다.
  console.log(report);

  const problems = [
    ...openFailures,
    ...zeroScanRoutes(counts).map(
      (r) => `${r}: 스캔 요소 0건 — 화면이 그려지지 않았다(위반 0건을 통과로 읽을 수 없다)`,
    ),
    ...violations.map(
      (f) =>
        `${f.routes[0]} ${f.selector}: ${f.ratio.toFixed(2)}:1 < ${f.need}:1 (${f.fg} on ${f.bg})`,
    ),
  ];
  expect(problems, `${title} 접근성 감사 실패\n${report}\n`).toEqual([]);
}

test.describe('접근성 감사 — 명도 대비 (KWCAG 2.2 / WCAG AA)', () => {
  test('REVIEWER_내부_화면_명도_대비', async ({ reviewerPage }) => {
    test.setTimeout(420_000);
    await runAudit(reviewerPage, 'REVIEWER 내부 화면 · 명도 대비', REVIEWER_ROUTES);
  });

  test('PORTAL_외부_화면_명도_대비', async ({ portalPage }) => {
    test.setTimeout(180_000);
    await runAudit(portalPage, 'PORTAL 외부 화면 · 명도 대비', PORTAL_ROUTES);
  });
});
