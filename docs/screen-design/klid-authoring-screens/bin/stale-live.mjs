#!/usr/bin/env node
/**
 * stale 현황을 **서버 라이브**로 조회한다.
 *
 * ★왜 로컬 .staging 을 쓰면 안 되나 (2026-08-07 실측 사고):
 *   download-kit 의 델타 판정은 content_hash/version 기준이라
 *   **stale 플래그만 바뀐 ITEM 은 파일을 다시 쓰지 않는다.**
 *   그래서 로컬 _raw/*.json 의 stale 은 조용히 낡고, 그걸로 세면 "0건" 오보가 난다.
 * ★그리고 include_retired 없이는 deprecated/superseded 가 통째로 안 보인다(74+5건).
 *
 * 사용: LOGICRAFT_API_KEY=... node stale-live.mjs [--reasons]
 */
const B = process.env.LOGICRAFT_API_BASE || "https://logicraft.cudo.co.kr:10000/api";
const P = process.env.LOGICRAFT_PROJECT || "4ece2c3f-8e99-46f5-9580-71108a76e578";
const K = process.env.LOGICRAFT_API_KEY;
const wantReasons = process.argv.includes("--reasons");

async function get(qs) {
  const r = await fetch(`${B}/projects/${P}/kit-export?${qs}`, { headers: { Authorization: `Bearer ${K}` } });
  if (!r.ok) { console.error(`HTTP ${r.status}`, (await r.text()).slice(0, 200)); process.exit(2); }
  return r.json();
}

const light = await get("include_bodies=false&include_retired=true");
let stale = light.items.filter(i => i.stale);

if (wantReasons && stale.length) {
  const reasons = {};
  for (let i = 0; i < stale.length; i += 40) {
    const ids = stale.slice(i, i + 40).map(x => x.id).join(",");
    const j = await get(`include_bodies=true&include_retired=true&ids=${encodeURIComponent(ids)}`);
    for (const it of j.items) reasons[it.id] = it.stale_reason || "";
  }
  stale = stale.map(s => ({ ...s, stale_reason: reasons[s.id] ?? "" }));
}

const by = {};
for (const i of stale) (by[i.type] ||= []).push(i);
console.log(`전체 ${light.count}건 (draft/approved + deprecated/superseded) · stale ${stale.length}건`);
for (const t of Object.keys(by).sort((a, b) => by[b].length - by[a].length)) {
  console.log(`== ${t} ${by[t].length}`);
  for (const i of by[t].sort((a, b) => a.id.localeCompare(b.id)))
    console.log(`   ${i.id.padEnd(13)} ${i.status.padEnd(11)} v${String(i.version).padEnd(3)} ${i.stale_reason ?? ""}`);
}
