/**
 * 접근성 자동 감사 엔진 — 대상 라우트 · 진입/안정화 · <b>브라우저 측 스캐너</b> · 결과 서식.
 *
 * <p>두 스펙({@link ../specs/a11y-contrast.spec.ts} · {@link ../specs/a11y-alt.spec.ts})이
 * 같은 라우트 집합과 같은 스캐너를 공유한다.
 *
 * <p><b>스캐너를 스펙 밖에 두는 이유</b>: 검사기가 조용히 검사를 그만두는 사고를 막으려면
 * 검사기 자신을 검사해야 한다. 스캐너가 모듈로 노출돼 있어야
 * {@link ../specs/a11y-detector-selftest.spec.ts} 가 <b>같은 코드</b>를 합성 DOM 에 돌려
 * "위반을 실제로 잡는다"를 증명할 수 있다(복제본을 만들면 그 순간 두 번째 진실원이 된다).
 *
 * <p><b>Page Object 를 쓰지 않는 이유</b>: 이 감사는 특정 화면의 동선을 조작하지 않고 렌더된
 * DOM 전체를 구조적으로 훑는다. 화면별 선택자가 등장하지 않으며, 사용하는 선택자는
 * 랜드마크(`main`)와 role 기반 로딩 표시자뿐이다.
 */

import { expect, type Page } from '@playwright/test';

// ---------------------------------------------------------------------------
// 대상 라우트
// ---------------------------------------------------------------------------

export interface A11yRoute {
  /** 진입 경로. */
  path: string;
  /**
   * 진입 후 실제로 머무는 경로. 라우터 redirect 가 있는 경우에만 지정한다.
   * 지정하지 않으면 `path` 그대로 머물러야 하며, 다른 곳으로 튕기면 실패다
   * (권한 가드가 조용히 `/forbidden` 으로 보내는 상황을 통과로 읽지 않기 위함).
   */
  settlesAt?: string;
}

/**
 * REVIEWER 로 진입하는 내부 채널 화면.
 *
 * <p>★경로 파라미터가 필요한 화면(`/label/:id` · `/marking/:rawSn` · `/review/:id` ·
 * `/video/:id` · `/augment/result/:rawSn` · `/portal/uploads/:uldSn/label`)은 <b>이번 범위에서
 * 제외</b>한다. 실데이터(rawSn·srcSn) 해석에 의존해 시드 재적재마다 대상이 바뀌므로 별건이다.
 */
export const REVIEWER_ROUTES: A11yRoute[] = [
  { path: '/dashboard' },
  // 라우터가 `/video` 를 대표 하위인 `/video/status` 로 replace 한다(src/router/index.tsx).
  { path: '/video', settlesAt: '/video/status' },
  { path: '/video/status' },
  { path: '/task' },
  { path: '/review' },
  { path: '/review/pending' },
  { path: '/stat/worker' },
  { path: '/stat/overall' },
  { path: '/augment/request' },
  { path: '/manage/users' },
  { path: '/manage/settings' },
  { path: '/manage/presets' },
  { path: '/manage/labels' },
  { path: '/manage/event-types' },
  { path: '/manage/deident-reports' },
  { path: '/manage/imports' },
  { path: '/notice' },
];

/** PORTAL_USER 로 진입하는 외부 채널 화면. */
export const PORTAL_ROUTES: A11yRoute[] = [{ path: '/portal' }, { path: '/portal/uploads' }];

// ---------------------------------------------------------------------------
// 진입 · 안정화
// ---------------------------------------------------------------------------

/**
 * 라우트로 진입해 <b>스캔해도 되는 상태</b>까지 기다린다.
 *
 * <p>이른 스캔은 미탐을 만든다(아직 안 그려진 요소는 위반으로 잡히지 않는다). 고정 대기는
 * 금지이므로 화면 자신의 안정 신호를 기다린다:
 * <ol>
 *   <li>URL 이 기대 경로에 도달 — 권한 가드 redirect 를 통과로 읽지 않는다</li>
 *   <li>Suspense 로딩 표시자 소멸 — 지연 로딩된 페이지 청크가 붙었다는 뜻</li>
 *   <li>`main` 랜드마크 가시 + 그 안에 텍스트가 실제로 렌더됨</li>
 *   <li>(가능하면) 네트워크 정지 — 자동 갱신 화면은 영원히 idle 이 안 오므로 상한 안에서만 기다린다</li>
 * </ol>
 *
 * @throws 위 1~3 이 시간 안에 성립하지 않으면 예외 — 그 라우트는 실패로 드러난다.
 */
export async function openRoute(page: Page, route: A11yRoute): Promise<void> {
  const expected = route.settlesAt ?? route.path;

  await page.goto(route.path, { waitUntil: 'domcontentloaded' });
  await page.waitForURL((url) => url.pathname === expected, { timeout: 20_000 });

  await expect(page.getByRole('status', { name: '페이지 로딩' })).toHaveCount(0, {
    timeout: 20_000,
  });

  const main = page.locator('main').first();
  await expect(main).toBeVisible({ timeout: 20_000 });
  await expect
    .poll(async () => (await main.innerText()).trim().length, {
      message: `${route.path}: main 영역에 텍스트가 렌더되지 않았다 (빈 화면을 스캔하면 위반 0건이 거짓 통과가 된다)`,
      timeout: 20_000,
    })
    .toBeGreaterThan(0);

  // 데이터 페치가 끝나 화면이 안정될 때까지. 자동 갱신 화면은 idle 이 오지 않을 수 있어
  // 실패를 삼킨다 — 위 1~3 이 이미 "그려졌다"를 보장하므로 스캔 자체는 유효하다.
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => undefined);
}

// ---------------------------------------------------------------------------
// 스캐너 ① 명도 대비
// ---------------------------------------------------------------------------

/** 명도 대비 위반 1건. */
export interface ContrastFinding {
  selector: string;
  /** 위반 위치를 특정하기 위한 텍스트 조각(30자 상한). */
  text: string;
  /** 전경색 — 알파가 있으면 배경과 합성한 실효 색. */
  fg: string;
  /** 배경색 — 반투명 조상을 합성한 실효 배경. */
  bg: string;
  ratio: number;
  /** 요구 대비 — 일반 4.5 · 큰 글자 3. */
  need: number;
  fontSize: string;
}

export interface ContrastScan {
  /** 필터를 통과해 <b>실제로 판정한</b> 요소 수. 0이면 감사가 헛돈 것이다. */
  scanned: number;
  findings: ContrastFinding[];
}

/**
 * 현재 페이지의 모든 텍스트 노드를 훑어 대비를 측정한다.
 *
 * <p>기준: 일반 텍스트 4.5:1 · 큰 글자(24px 이상, 또는 18.66px 이상이며 bold) 3:1.
 * 제외: 비활성 요소(KWCAG 대비 예외) · 비표시/화면 밖/1px(sr-only) 요소.
 *
 * <p><b>알려진 한계</b>: 배경이 이미지·그라데이션이면 합성 불가라 판정에서 제외한다.
 * 요소 단위 `opacity`(0&lt;x&lt;1)는 합성하지 않는다(배경 알파와 다른 축).
 */
export async function scanContrast(page: Page): Promise<ContrastScan> {
  return page.evaluate(() => {
    const toLinear = (channel: number): number => {
      const s = channel / 255;
      return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    };
    const luminance = (rgb: number[]): number =>
      0.2126 * toLinear(rgb[0]) + 0.7152 * toLinear(rgb[1]) + 0.0722 * toLinear(rgb[2]);

    /** `rgb()` · `rgba()` · 공백/슬래시 표기 모두 받는다. 반환은 `[r, g, b, a]`. */
    const parseColor = (value: string): number[] | null => {
      const m = value.match(
        /rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.%]+))?\s*\)/,
      );
      if (!m) return null;
      let alpha = 1;
      if (m[4] !== undefined) {
        alpha = m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
      }
      return [+m[1], +m[2], +m[3], alpha];
    };

    const contrast = (a: number[], b: number[]): number => {
      const l1 = luminance(a);
      const l2 = luminance(b);
      return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    };

    const toHex = (rgb: number[]): string =>
      '#' +
      rgb
        .slice(0, 3)
        .map((c) => Math.round(Math.min(255, Math.max(0, c))).toString(16).padStart(2, '0'))
        .join('');

    /**
     * 조상을 타고 올라가며 실효 배경을 합성한다(source-over).
     * 아직 채워지지 않은 알파(remaining)를 위에서 아래로 소진시키고,
     * 끝까지 투명하면 캔버스 기본색(흰색)으로 채운다.
     */
    const effectiveBackground = (el: Element): number[] | null => {
      let r = 0;
      let g = 0;
      let b = 0;
      let remaining = 1;
      for (let node: Element | null = el; node; node = node.parentElement) {
        const cs = getComputedStyle(node);
        if (cs.backgroundImage !== 'none') return null;
        const c = parseColor(cs.backgroundColor);
        if (!c || c[3] === 0) continue;
        const a = Math.min(c[3], 1) * remaining;
        r += c[0] * a;
        g += c[1] * a;
        b += c[2] * a;
        remaining -= a;
        if (remaining <= 0.001) return [r, g, b];
      }
      return [r + 255 * remaining, g + 255 * remaining, b + 255 * remaining];
    };

    const describe = (el: Element): string => {
      const parts: string[] = [el.tagName.toLowerCase()];
      if (el.id) parts.push(`#${el.id}`);
      const raw = typeof el.className === 'string' ? el.className : '';
      const cls = raw.trim().split(/\s+/).filter(Boolean).slice(0, 3);
      if (cls.length > 0) parts.push(`.${cls.join('.')}`);
      const testId = el.getAttribute('data-testid');
      if (testId) parts.push(`[data-testid="${testId}"]`);
      return parts.join('');
    };

    /** KWCAG 대비 예외 — 비활성 컨트롤과 그 라벨(내포형·for 연결형·형제형). */
    const isDisabledContext = (el: Element): boolean => {
      if (el.closest('[disabled], [aria-disabled="true"], :disabled')) return true;
      const label = el.closest('label');
      if (!label) return false;
      if (label.querySelector(':disabled')) return true;
      const forId = label.getAttribute('for');
      if (forId) {
        const control = document.getElementById(forId) as HTMLInputElement | null;
        if (control?.disabled) return true;
      }
      return label.parentElement?.querySelector(':disabled') != null;
    };

    const findings: {
      selector: string;
      text: string;
      fg: string;
      bg: string;
      ratio: number;
      need: number;
      fontSize: string;
    }[] = [];
    let scanned = 0;

    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const textNode = walker.currentNode;
      const text = (textNode.textContent ?? '').trim();
      if (!text) continue;
      const el = textNode.parentElement;
      if (!el) continue;
      if (isDisabledContext(el)) continue;

      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) continue;

      // sr-only(1px 클리핑)·화면 밖 스킵링크는 시각 대비 대상이 아니다.
      const rect = el.getBoundingClientRect();
      if (rect.width < 2 || rect.height < 2) continue;
      if (rect.bottom < 0 || rect.right < 0) continue;

      const bg = effectiveBackground(el);
      if (!bg) continue;
      const fgRaw = parseColor(cs.color);
      if (!fgRaw) continue;

      // 전경 알파도 배경에 합성한다 — 반투명 텍스트는 실제로 배경 쪽으로 옅어진다.
      const fa = Math.min(Math.max(fgRaw[3], 0), 1);
      const fg = [0, 1, 2].map((i) => fgRaw[i] * fa + bg[i] * (1 - fa));

      scanned++;

      const size = parseFloat(cs.fontSize);
      const bold = parseInt(cs.fontWeight, 10) >= 700;
      const large = size >= 24 || (size >= 18.66 && bold);
      const need = large ? 3 : 4.5;
      const ratio = Math.round(contrast(fg, bg) * 100) / 100;
      if (ratio >= need) continue;

      findings.push({
        selector: describe(el),
        text: text.slice(0, 30),
        fg: toHex(fg),
        bg: toHex(bg),
        ratio,
        need,
        fontSize: `${Math.round(size)}px${bold ? '/bold' : ''}`,
      });
    }
    return { scanned, findings };
  });
}

// ---------------------------------------------------------------------------
// 스캐너 ② 대체 텍스트
// ---------------------------------------------------------------------------

/** 대체 텍스트 위반 1건. */
export interface AltFinding {
  kind: 'img-alt' | 'no-name';
  selector: string;
  detail: string;
}

export interface AltScan {
  /** 화면에 보이는 `img`·`button`·`a` 의 합. 0이면 감사가 헛돈 것이다. */
  scanned: number;
  findings: AltFinding[];
}

/**
 * 현재 페이지의 이미지·컨트롤을 훑는다.
 * <ol>
 *   <li>`img` 에 `alt` 속성 자체가 없는 경우 — 장식 이미지는 `alt=""` 로 명시해야 통과</li>
 *   <li>접근 가능한 이름이 빈 `button`·`a`</li>
 * </ol>
 * 제외: 비표시 요소 · `aria-hidden` 서브트리.
 */
export async function scanAlt(page: Page): Promise<AltScan> {
  return page.evaluate(() => {
    const isVisible = (el: Element): boolean => {
      if (el.closest('[aria-hidden="true"]')) return false;
      const cs = getComputedStyle(el);
      return cs.display !== 'none' && cs.visibility !== 'hidden';
    };

    const describe = (el: Element): string => {
      const parts: string[] = [el.tagName.toLowerCase()];
      if (el.id) parts.push(`#${el.id}`);
      const raw = typeof el.className === 'string' ? el.className : '';
      const cls = raw.trim().split(/\s+/).filter(Boolean).slice(0, 3);
      if (cls.length > 0) parts.push(`.${cls.join('.')}`);
      const testId = el.getAttribute('data-testid');
      if (testId) parts.push(`[data-testid="${testId}"]`);
      return parts.join('');
    };

    /** `aria-labelledby` 는 속성 존재가 아니라 <b>가리키는 요소의 텍스트</b>로 판정한다. */
    const labelledByText = (el: Element): string =>
      (el.getAttribute('aria-labelledby') ?? '')
        .split(/\s+/)
        .filter(Boolean)
        .map((id) => document.getElementById(id)?.textContent?.trim() ?? '')
        .join(' ')
        .trim();

    /**
     * 접근 가능한 이름 후보. sr-only 텍스트도 이름이므로 textContent 를 포함한다.
     * SVG `<title>` 은 별도 분기가 필요 없다 — `textContent` 가 네임스페이스와 무관하게
     * 자손 텍스트를 모두 이어 주므로 이미 이 단계에서 잡힌다.
     */
    const accessibleName = (el: Element): string =>
      (
        (el.getAttribute('aria-label') ?? '').trim() ||
        labelledByText(el) ||
        (el.getAttribute('title') ?? '').trim() ||
        (el.textContent ?? '').trim() ||
        [...el.querySelectorAll('img')]
          .map((img) => (img.getAttribute('alt') ?? '').trim())
          .join(' ')
          .trim()
      ).trim();

    const findings: { kind: 'img-alt' | 'no-name'; selector: string; detail: string }[] = [];
    let scanned = 0;

    document.querySelectorAll('img').forEach((img) => {
      if (!isVisible(img)) return;
      scanned++;
      if (img.getAttribute('alt') === null) {
        const src = (img.getAttribute('src') ?? '').split('/').pop() ?? '';
        findings.push({
          kind: 'img-alt',
          selector: describe(img),
          detail: `alt 속성 없음 (src=${src.slice(0, 60)})`,
        });
      }
    });

    document.querySelectorAll('button, a').forEach((el) => {
      if (!isVisible(el)) return;
      scanned++;
      if (accessibleName(el) === '') {
        findings.push({
          kind: 'no-name',
          selector: describe(el),
          detail: `접근 가능한 이름 없음: ${el.outerHTML.replace(/\s+/g, ' ').slice(0, 110)}`,
        });
      }
    });

    return { scanned, findings };
  });
}

// ---------------------------------------------------------------------------
// 결과 서식 · 조용한 그린 차단
// ---------------------------------------------------------------------------

/** 라우트별 스캔 요소 수 — 감사가 실제로 돌았다는 증거. 통과·실패와 무관하게 항상 출력한다. */
export interface RouteScanCount {
  route: string;
  scanned: number;
}

/** 스캔 요소 수 표를 만든다. 0건 라우트는 호출부가 실패시켜야 한다. */
export function formatScanCounts(title: string, counts: RouteScanCount[]): string {
  const width = Math.max(...counts.map((c) => c.route.length), 10);
  const rows = counts.map(
    (c) =>
      `  ${c.route.padEnd(width)}  ${String(c.scanned).padStart(5)}${c.scanned === 0 ? '  ← 0건(실패)' : ''}`,
  );
  const total = counts.reduce((sum, c) => sum + c.scanned, 0);
  return [
    `\n=== ${title} — 라우트별 스캔 요소 수 (${counts.length}개 라우트 / 합계 ${total}건) ===`,
    ...rows,
  ].join('\n');
}

/** 스캔 요소가 0건인 라우트를 골라낸다(조용한 그린 차단). */
export function zeroScanRoutes(counts: RouteScanCount[]): string[] {
  return counts.filter((c) => c.scanned === 0).map((c) => c.route);
}
