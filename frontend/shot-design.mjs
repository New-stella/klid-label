// 시안 HTML 파일 하나를 PNG 로 찍는다 — 화면 시안 검토·전달용.
//   node shot-design.mjs <html 절대경로> <png 출력경로> [뷰포트 폭]
// ⚠ 이 스크립트는 frontend/ 안에 있어야 한다 — /tmp 에 두면 playwright 를 못 찾는다.
// ⚠ fullPage 는 max(내용, 뷰포트)를 찍으므로 뷰포트 높이를 작게 잡아야 아래 여백이 안 붙는다.
import { chromium } from 'playwright';
const [src, out, w] = process.argv.slice(2);
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: Number(w) || 1440, height: 600 }, deviceScaleFactor: 2 });
await p.goto('file://' + src, { waitUntil: 'networkidle' });
await p.screenshot({ path: out, fullPage: true });
await b.close();
console.log('ok', out);
