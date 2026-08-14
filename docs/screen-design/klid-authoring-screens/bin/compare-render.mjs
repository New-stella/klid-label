#!/usr/bin/env node
/**
 * compare-render.mjs — 화면 시안 정합 작업용 렌더 검증기
 *
 * 무엇을 하나
 *   SD 시안을 "관례 정합"할 때(색 토큰 rename · 타이포 사다리 전환 등) 시각 결과물이
 *   정말로 그대로인지를 눈이 아니라 **브라우저 실측**으로 판정한다.
 *
 *   diff  — 원본/정합본 두 렌더를 열어 요소를 1:1 로 대조한다.
 *           · computed style 차이(fontSize·fontWeight·lineHeight·color·backgroundColor·border*)
 *           · 위치·크기 차이(dx·dy·dw·dh)
 *   width — 특정 자식(도움말 문장 등)의 텍스트 길이를 ×1·×2·×4·축소로 바꿔가며
 *           대상 요소의 폭이 따라 변하는지 본다(= 내용이 폭을 지배하는 결함 검출).
 *
 * 왜 있나
 *   SD-009(SCREEN-024) 정합에서 이 두 검사가 결정적이었다.
 *   · width 검사가 "도움말 문장이 검색 입력의 폭을 정한다"를 435→873px 로 실증했다.
 *   · diff 검사가 눈에 보이지 않는 font-size 누락 1건(.du-alert>span 14→17px)을 잡아냈다.
 *     블록 자식만 있어 line box 가 생기지 않는 래퍼라 육안·스크린샷으로는 드러나지 않았다.
 *
 * ── 사용법 ────────────────────────────────────────────────────────────────
 *
 *   # 두 렌더 1:1 대조 (원본 → 정합본)
 *   node compare-render.mjs diff <A.html> <B.html> [옵션]
 *       --width 1280           뷰포트 폭(기본 1280). 콤마로 여러 폭: --width 1280,1024,820,480
 *       --height 900           뷰포트 높이(기본 900)
 *       --intended <selector>  이 서브트리 안의 기하 변화는 '의도된 변경'으로 분리 집계
 *                              (예: 레이아웃 결함을 고친 --intended '.du-filter-form')
 *       --props a,b,c          비교할 computed style 속성 목록(기본은 아래 STYLE_PROPS)
 *       --show-geom            기하 차이를 요약이 아니라 전건 출력
 *
 *   # 폭 지배 검사 (내용이 폭을 정하는가)
 *   node compare-render.mjs width <page.html> --target <sel> --vary <sel> [--width 1280]
 *       --target  폭을 관찰할 요소 (예: '.du-input')
 *       --vary    텍스트 길이를 바꿔볼 요소 (예: '.du-help')
 *
 * ── 런타임 ────────────────────────────────────────────────────────────────
 *   Node 18+ (검증: v24.4.0)
 *   playwright-core — 이 저장소에 npm 의존성을 추가하지 않는다. 전역 설치된
 *     @playwright/mcp 에 동봉된 것을 재사용한다:
 *       /opt/homebrew/lib/node_modules/@playwright/mcp/node_modules/playwright-core
 *     PLAYWRIGHT_CORE 환경변수로 다른 경로 지정 가능.
 *   Chromium — ~/Library/Caches/ms-playwright/chromium-<rev>/ 중 가장 최신을 자동 선택.
 *     ⚠ 동봉 playwright-core 의 기본 기대 리비전과 실제 설치본이 어긋날 수 있어
 *       (예: 기대 1212 / 설치 1208·1217·1223·1228) executablePath 를 명시적으로 넘긴다.
 *     SD_CHROMIUM 환경변수로 다른 바이너리 지정 가능.
 *
 *   설치가 필요 없다 — 위 두 가지는 이미 있는 것을 가리키기만 한다.
 *   없으면 스크립트가 무엇이 없는지 명시하고 종료한다(임의 설치하지 않는다).
 *
 * ── 종료 코드 ─────────────────────────────────────────────────────────────
 *   0  정상 수행(차이 유무와 무관 — 판정은 사람이 한다)
 *   1  구조 불일치(요소 수가 다름) · 파일/런타임 없음 · 선택자 매칭 실패
 */

import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const require = createRequire(import.meta.url);

/** 기본 비교 속성. fontFamily 는 제외한다 — 상속이라 한 곳만 바뀌어도 전 요소가 차이로 잡혀
 *  잡음이 된다. 대신 body 기준으로 따로 한 줄 보고한다. */
const STYLE_PROPS = [
  'fontSize', 'fontWeight', 'lineHeight',
  'color', 'backgroundColor',
  'borderTopColor', 'borderRightColor', 'borderBottomColor', 'borderLeftColor',
  'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
];

// ── 런타임 해석 ──────────────────────────────────────────────────────────
function resolvePlaywright() {
  const candidates = [
    process.env.PLAYWRIGHT_CORE,
    '/opt/homebrew/lib/node_modules/@playwright/mcp/node_modules/playwright-core',
    '/usr/local/lib/node_modules/@playwright/mcp/node_modules/playwright-core',
    'playwright-core',
    'playwright',
  ].filter(Boolean);
  for (const c of candidates) {
    try { return { mod: require(c), from: c }; } catch { /* 다음 후보 */ }
  }
  die(
    'playwright-core 를 찾지 못했습니다.\n' +
    '  이 저장소에 의존성을 추가하지 마세요. 다음 중 하나를 확인하세요:\n' +
    '   · npm ls -g 에 @playwright/mcp 가 있는지\n' +
    '   · PLAYWRIGHT_CORE=<playwright-core 경로> 로 직접 지정'
  );
}

function resolveChromium(pw) {
  if (process.env.SD_CHROMIUM) {
    if (!fs.existsSync(process.env.SD_CHROMIUM)) die(`SD_CHROMIUM 경로에 파일이 없습니다: ${process.env.SD_CHROMIUM}`);
    return process.env.SD_CHROMIUM;
  }
  const cache = path.join(os.homedir(), 'Library/Caches/ms-playwright');
  const linux = path.join(os.homedir(), '.cache/ms-playwright');
  const root = fs.existsSync(cache) ? cache : (fs.existsSync(linux) ? linux : null);
  if (root) {
    const revs = fs.readdirSync(root)
      .filter(d => /^chromium-\d+$/.test(d))
      .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]));
    const rels = [
      'chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
      'chrome-mac/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
      'chrome-linux/chrome',
    ];
    for (const rev of revs) for (const rel of rels) {
      const p = path.join(root, rev, rel);
      if (fs.existsSync(p)) return p;
    }
  }
  try { const p = pw.chromium.executablePath(); if (p && fs.existsSync(p)) return p; } catch { /* noop */ }
  die(
    'Chromium 바이너리를 찾지 못했습니다.\n' +
    '  설치하지 마세요. SD_CHROMIUM=<chrome 실행파일 경로> 로 지정하거나,\n' +
    `  ${root ?? 'ms-playwright 캐시'} 에 chromium-* 가 있는지 확인하세요.`
  );
}

function die(msg) { console.error('\n[중단] ' + msg + '\n'); process.exit(1); }

function fileUrl(p) {
  if (!fs.existsSync(p)) die(`파일이 없습니다: ${p}`);
  return pathToFileURL(path.resolve(p)).href;
}

// ── 페이지 안에서 도는 수집기 ────────────────────────────────────────────
// SD-009 정합에서 실제로 돌린 evaluate 로직을 그대로 옮긴 것이다.
function collect([props, intendedSel]) {
  const els = [...document.querySelectorAll('body *')];
  const origin = document.body.getBoundingClientRect();
  const name = e => {
    const cls = (typeof e.className === 'string' ? e.className : '')
      .trim().split(/\s+/).filter(c => c && !c.startsWith('t-')).join('.');
    return e.tagName.toLowerCase() + (cls ? '.' + cls : '');
  };
  return {
    total: els.length,
    bodyFontFamily: getComputedStyle(document.body).fontFamily,
    scrollHeight: Math.round(document.body.scrollHeight),
    items: els.map(e => {
      const cs = getComputedStyle(e);
      const r = e.getBoundingClientRect();
      const style = {};
      for (const p of props) style[p] = cs[p];
      return {
        name: name(e),
        style,
        x: Math.round(r.left - origin.left), y: Math.round(r.top - origin.top),
        w: Math.round(r.width), h: Math.round(r.height),
        intended: intendedSel ? !!e.closest(intendedSel) : false,
      };
    }),
  };
}

// ── diff 모드 ────────────────────────────────────────────────────────────
async function cmdDiff(pw, exe, args) {
  const [aPath, bPath] = args._;
  if (!aPath || !bPath) die('사용법: compare-render.mjs diff <A.html> <B.html> [옵션]');
  const props = args.props ? args.props.split(',').map(s => s.trim()).filter(Boolean) : STYLE_PROPS;
  const widths = String(args.width ?? 1280).split(',').map(s => Number(s.trim()));
  const height = Number(args.height ?? 900);
  const intended = args.intended ?? null;

  const browser = await pw.chromium.launch({ executablePath: exe, headless: true });
  let structuralFail = false;
  try {
    for (const width of widths) {
      const ctx = await browser.newContext({ viewport: { width, height } });
      const [pa, pb] = [await ctx.newPage(), await ctx.newPage()];
      await pa.goto(fileUrl(aPath), { waitUntil: 'load' });
      await pb.goto(fileUrl(bPath), { waitUntil: 'load' });
      const A = await pa.evaluate(collect, [props, intended]);
      const B = await pb.evaluate(collect, [props, intended]);

      console.log(`\n${'='.repeat(72)}\n뷰포트 ${width}×${height}\n${'='.repeat(72)}`);
      console.log(`A = ${aPath}\nB = ${bPath}`);

      if (A.total !== B.total) {
        console.log(`\n★ 구조 불일치: 요소 수 A ${A.total} vs B ${B.total} — 대조 불가(DOM 구조가 다름)`);
        structuralFail = true;
        await ctx.close();
        continue;
      }
      console.log(`\n요소 ${A.total}개 1:1 대조`);

      // 1) computed style
      const byProp = new Map(), rows = new Map();
      for (let i = 0; i < A.total; i++) {
        const d = {};
        for (const p of props) if (A.items[i].style[p] !== B.items[i].style[p]) {
          d[p] = `${A.items[i].style[p]} → ${B.items[i].style[p]}`;
          byProp.set(p, (byProp.get(p) || 0) + 1);
        }
        if (Object.keys(d).length) {
          const k = JSON.stringify({ el: A.items[i].name, ...d });
          rows.set(k, (rows.get(k) || 0) + 1);
        }
      }
      console.log('\n── computed style 차이 ──');
      for (const p of props) console.log(`  ${p.padEnd(20)} ${byProp.get(p) || 0}건`);
      if (rows.size) {
        console.log('\n  상세:');
        for (const [k, n] of rows) {
          const o = JSON.parse(k); const el = o.el; delete o.el;
          console.log(`   ${el}  ×${n}`);
          for (const [p, v] of Object.entries(o)) console.log(`      ${p}: ${v}`);
        }
      } else {
        console.log('  → 차이 없음');
      }

      // 2) 기하
      const moved = [];
      for (let i = 0; i < A.total; i++) {
        const a = A.items[i], b = B.items[i];
        const dx = b.x - a.x, dy = b.y - a.y, dw = b.w - a.w, dh = b.h - a.h;
        if (dx || dy || dw || dh) moved.push({ el: a.name, intended: a.intended, dx, dy, dw, dh });
      }
      const inside = moved.filter(m => m.intended), outside = moved.filter(m => !m.intended);
      console.log('\n── 위치·크기 차이 ──');
      if (intended) {
        console.log(`  '${intended}' 안 (의도된 변경): ${inside.length}건`);
        console.log(`  '${intended}' 밖            : ${outside.length}건`);
      } else {
        console.log(`  변화 있는 요소: ${moved.length}건`);
      }
      const scope = intended ? outside : moved;
      const horiz = scope.filter(m => m.dx || m.dw);
      console.log(`  그중 가로 변화(dx≠0 또는 dw≠0): ${horiz.length}건` +
                  (horiz.length === 0 ? '  ← 가로 리플로우 없음' : ''));
      if (scope.length) {
        const agg = new Map();
        for (const m of scope) {
          const k = `${m.el} dx${m.dx} dy${m.dy} dw${m.dw} dh${m.dh}`;
          agg.set(k, (agg.get(k) || 0) + 1);
        }
        const list = [...agg];
        const show = args['show-geom'] ? list : list.slice(0, 40);
        for (const [k, n] of show) console.log(`   ${k}  ×${n}`);
        if (show.length < list.length) console.log(`   … 외 ${list.length - show.length}종 (--show-geom 으로 전건 출력)`);
      }

      console.log('\n── 참고 ──');
      console.log(`  문서 높이 A/B: ${A.scrollHeight} / ${B.scrollHeight}  (${B.scrollHeight - A.scrollHeight >= 0 ? '+' : ''}${B.scrollHeight - A.scrollHeight}px)`);
      if (A.bodyFontFamily !== B.bodyFontFamily) {
        console.log(`  ★ body font-family 가 다릅니다 (상속이라 위 대조에서는 제외됨):`);
        console.log(`      A: ${A.bodyFontFamily}`);
        console.log(`      B: ${B.bodyFontFamily}`);
      } else {
        console.log('  body font-family 동일');
      }
      await ctx.close();
    }
  } finally { await browser.close(); }
  if (structuralFail) process.exit(1);
}

// ── width 모드 ───────────────────────────────────────────────────────────
async function cmdWidth(pw, exe, args) {
  const [pagePath] = args._;
  if (!pagePath || !args.target || !args.vary)
    die("사용법: compare-render.mjs width <page.html> --target '.du-input' --vary '.du-help'");
  const width = Number(String(args.width ?? 1280).split(',')[0]);
  const height = Number(args.height ?? 900);

  const browser = await pw.chromium.launch({ executablePath: exe, headless: true });
  try {
    const ctx = await browser.newContext({ viewport: { width, height } });
    const page = await ctx.newPage();
    await page.goto(fileUrl(pagePath), { waitUntil: 'load' });

    const res = await page.evaluate(([targetSel, varySel]) => {
      const t = document.querySelector(targetSel), v = document.querySelector(varySel);
      if (!t) return { error: `--target 선택자에 맞는 요소가 없습니다: ${targetSel}` };
      if (!v) return { error: `--vary 선택자에 맞는 요소가 없습니다: ${varySel}` };
      const W = () => Math.round(t.getBoundingClientRect().width);
      const orig = v.textContent;
      const out = {};
      out['원본'] = W();
      v.textContent = orig + ' ' + orig;                                  out['2배'] = W();
      v.textContent = [orig, orig, orig, orig].join(' ');                 out['4배'] = W();
      v.textContent = '짧게.';                                            out['축소'] = W();
      v.textContent = orig;
      out['복원'] = W();
      return { widths: out, varyChars: orig.trim().length };
    }, [args.target, args.vary]);

    if (res.error) die(res.error);

    console.log(`\n${'='.repeat(72)}\n폭 지배 검사 — 뷰포트 ${width}×${height}\n${'='.repeat(72)}`);
    console.log(`페이지 : ${pagePath}`);
    console.log(`관찰   : ${args.target}  (이 요소의 폭)`);
    console.log(`변화   : ${args.vary}  (이 요소의 텍스트 길이, 원본 ${res.varyChars}자)\n`);
    for (const [k, v] of Object.entries(res.widths)) console.log(`  ${k.padEnd(6)} ${String(v).padStart(6)} px`);

    const vals = ['원본', '2배', '4배', '축소'].map(k => res.widths[k]);
    const fixed = vals.every(v => v === vals[0]);
    console.log('\n판정: ' + (fixed
      ? `폭 고정 — 내용 길이가 폭을 지배하지 않는다 (전부 ${vals[0]}px)`
      : `★ 폭 지배 — '${args.vary}' 의 길이가 '${args.target}' 의 폭을 정하고 있다 ` +
        `(${Math.min(...vals)}~${Math.max(...vals)}px 로 변동)`));
    await ctx.close();
  } finally { await browser.close(); }
}

// ── 인자 파싱 ────────────────────────────────────────────────────────────
function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) out[key] = true;
      else { out[key] = next; i++; }
    } else out._.push(a);
  }
  return out;
}

const [, , cmd, ...rest] = process.argv;
if (!cmd || ['-h', '--help', 'help'].includes(cmd)) {
  console.log(fs.readFileSync(new URL(import.meta.url), 'utf-8').split('*/')[0].replace(/^#!.*\n/, ''));
  process.exit(0);
}
const args = parseArgs(rest);
const { mod: pw, from } = resolvePlaywright();
const exe = resolveChromium(pw);
if (args.verbose) { console.log(`playwright-core: ${from}`); console.log(`chromium: ${exe}`); }

if (cmd === 'diff') await cmdDiff(pw, exe, args);
else if (cmd === 'width') await cmdWidth(pw, exe, args);
else die(`알 수 없는 명령: ${cmd}  (diff | width)`);
