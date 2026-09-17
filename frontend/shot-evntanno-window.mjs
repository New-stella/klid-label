// 시계열 · 이벤트 어노테이션 창 시안(SCREEN-005·019)을 1920×1080 PNG 로 찍는다 — 시안 검토·전달용.
//   node shot-evntanno-window.mjs <시안 폴더 절대경로>
// ⚠ frontend/ 안에 있어야 playwright 를 찾는다. v1 PNG 는 시안 폴더 v1/ 에 보존.
import { chromium } from 'playwright';
import path from 'node:path';

const dir = process.argv[2];
const shots = [
  ['1', 's1-labeling-card-closed'],
  ['2', 's2-labeling-window-open'],
  ['2k', 's2k-candidate-key-raw'],
  ['3', 's3-review-window-readonly'],
  ['4', 's4-window-maximized'],
  ['5', 's5-window-min-stacked'],
  ['6', 's6-evidence-pick-mode'],
  ['7', 's7-evidence-filled-restored'],
  ['8a', 's8a-review-evidence-frame-link'],
  ['8', 's8-review-evidence-frame-jump'],
];
const browser = await chromium.launch();
for (const [scene, name] of shots) {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await page.goto('file://' + path.join(dir, 'index.html') + '?scene=' + scene, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join(dir, `${name}.png`) });
  await page.close();
}
await browser.close();
console.log('ok', shots.length);
