// SCREEN-019 메타 탭 개편 시안을 PNG 로 찍고 패널 내용 높이를 잰다 — 시안 검토·전달용.
//   node shot-meta-redesign.mjs <시안 폴더 절대경로>
// ⚠ frontend/ 안에 있어야 playwright 를 찾는다.
import { chromium } from 'playwright';
import { writeFileSync } from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
const FOLD = 709;
// v2(2026-09-14 실제 VLM 응답 기준) — v1(목서버 데이터 기준)은 시안 폴더의 v1-mock/ 에 보존.
const shots = [
  ['a.html', '?v=43', 'a43-default', '영상 43 교통사고 · 기본'],
  ['a.html', '?v=43&expanded', 'a43-expanded', '영상 43 · 전체 펼침'],
  ['a.html', '?v=6', 'a6-default', '영상 6 화재 · 기본'],
  ['a.html', '?v=6&expanded', 'a6-expanded', '영상 6 · 전체 펼침'],
  ['a.html', '?v=43&expanded&md=raw', 'md-raw-43', '추가 질문 (a) 원문 그대로 · 영상 43'],
  ['a.html', '?v=6&expanded&md=raw', 'md-raw-6', '추가 질문 (a) 원문 그대로 · 영상 6'],
];

const browser = await chromium.launch();
const results = [];
for (const [file, query, name, label] of shots) {
  const page = await browser.newPage({ viewport: { width: 400, height: 600 }, deviceScaleFactor: 2 });
  await page.goto('file://' + path.join(dir, file) + query, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  const height = await page.evaluate(() => document.querySelector('.content').getBoundingClientRect().height);
  await (await page.$('.shell')).screenshot({ path: path.join(dir, `${name}.png`) });
  results.push({ name, label, contentHeight: Math.round(height), screens: +(height / FOLD).toFixed(2) });
  await page.close();
}
writeFileSync(path.join(dir, 'measurements.json'), JSON.stringify(results, null, 2));

const figs = results
  .map((r) => `<figure><figcaption>${r.label}<small>내용 ${r.contentHeight}px · 첫 화면의 ${r.screens}배</small></figcaption><img src="${r.name}.png" alt="${r.label}"></figure>`)
  .join('');
writeFileSync(
  path.join(dir, 'compare.html'),
  `<!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><title>SCREEN-019 메타 탭 비교</title><link rel="stylesheet" href="meta.css"></head><body><div class="cmp">${figs}</div></body></html>`,
);
const page = await browser.newPage({ viewport: { width: 1900, height: 600 }, deviceScaleFactor: 1 });
await page.goto('file://' + path.join(dir, 'compare.html'), { waitUntil: 'load' });
await page.screenshot({ path: path.join(dir, 'compare.png'), fullPage: true });
await browser.close();
console.log(JSON.stringify(results));
