/**
 * 화면에 나타나지 않는 결과를 위한 <쿼리 증적> 촬영.
 *
 * ⚠ 이 파일도 `frontend/` 안에 있어야 한다 — 밖에 두면 playwright 가 resolve 되지 않는다.
 *
 * 왜 이미지인가 — 증적 원장이 받는 것은 이미지(경로 또는 data URI)뿐이다. 쿼리문을 문자열로 넣으면
 * 원장 형식 검사에 걸린다. 그래서 <실제로 돌린 쿼리와 그 결과>를 한 장으로 구워 캡처와 같은 길로 보낸다.
 *
 * 사용: node capture-query-evidence.mjs [케이스ID...]   (인자 없으면 전부)
 */
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const OUT = process.env.CAPTURE_OUT ?? '/Users/ck/Desktop/CBD-캡처루트-20260906/captures';
const SSH = process.env.CAPTURE_DB_HOST ?? 'cudo_246';
mkdirSync(OUT, { recursive: true });

/** 246 의 실제 DB 에서 돌린다 — 손으로 옮겨 적은 결과가 아니다. */
function runSql(sql) {
  const inner =
    `docker exec -e PGPASSWORD=$(docker exec klid-authoring-jboss printenv CONTROL_DB_PASSWORD) ` +
    // ⚠ 줄바꿈을 그대로 넘기면 원격 셸이 `\n` 을 리터럴로 흘려 psql 이 구문 오류를 낸다 —
    //   실행용은 한 줄로 편다(화면에 싣는 것은 원문 그대로다).
    `postgis-klid psql -U cudo -d klid -P pager=off -c ${JSON.stringify(sql.replace(/\s+/g, ' ').trim())}`;
  return execFileSync('nt', ['ssh', SSH, inner], { encoding: 'utf-8', maxBuffer: 1 << 24 });
}

const CASES = {
  '002-01': {
    title: '증강 결과 수신 · 새 영상 등록',
    note: '외부 증강 결과를 받으면 새 영상 행이 생기고 부모 영상을 가리킨다. 화면에 나타나지 않아 원장으로 확인한다.',
    sql: `SELECT a.data_aug_sn AS "증강번호", a.src_sn AS "원본영상", a.aug_type_cd AS "증강종류",
       a.aug_proc_stts_cd AS "생성결과", a.new_raw_sn AS "생성된영상",
       r.orgnl_raw_sn AS "부모참조", r.src_type AS "출처"
  FROM klid_at.ls_data_aug a
  JOIN klid_at.ls_data_raw r ON r.raw_sn = a.new_raw_sn
 ORDER BY a.data_aug_sn DESC LIMIT 5;`,
  },
  '007-03': {
    title: '승인 / 수정 이벤트별 통지 분기',
    note: '완료 통지와 수정 통지가 서로 다른 이벤트로 적재된다. 통지는 서버 사이 통신이라 화면에 나타나지 않는다.',
    sql: `SELECT evnt_type_cd AS "통지 이벤트", count(*) AS "건수",
       min(reg_dt) AS "처음", max(reg_dt) AS "마지막"
  FROM klid_at.ls_control_notify_fallback
 GROUP BY evnt_type_cd ORDER BY 1;`,
  },
  '009-01': {
    title: '검수 승인 · 재승인 시 관제 통지 발송',
    note: '검수 승인이 관제 통지로 이어졌는지는 발송 결과 원장으로만 확인된다.',
    sql: `SELECT queue_sn AS "번호", raw_sn AS "영상", evnt_type_cd AS "이벤트",
       stts_cd AS "상태", send_rslt_cd AS "발송결과", rtry_nmtm AS "재시도", reg_dt AS "등록시각"
  FROM klid_at.ls_control_notify_fallback ORDER BY queue_sn DESC LIMIT 5;`,
  },
  '038-01': {
    title: '적재부터 라벨링 준비까지 전체 흐름',
    note: '배치 파이프라인의 단계별 처리 결과다. 각 단계는 화면 뒤에서 돌아 원장에만 남는다.',
    sql: `SELECT data_raw_sn AS "영상", proc_step_cd AS "단계", proc_stts_cd AS "결과",
       count(*) AS "건수", max(end_dt) AS "마지막 완료"
  FROM klid_at.ls_batch_proc_log
 WHERE data_raw_sn = (SELECT max(data_raw_sn) FROM klid_at.ls_batch_proc_log)
 GROUP BY data_raw_sn, proc_step_cd, proc_stts_cd
 ORDER BY 5;`,
  },
};

const esc = (s) => s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);

const ids = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(CASES);
const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 })).newPage();

let failed = 0;
for (const id of ids) {
  const c = CASES[id];
  if (!c) { console.log(`!! 미정의 케이스: ${id}`); failed += 1; continue; }
  console.log(`== ${id} ${c.title}`);
  let result;
  try {
    result = runSql(c.sql).trimEnd();
  } catch (e) {
    console.log(`!! ${id} 쿼리 실패: ${String(e.stderr || e.message).slice(0, 300)}`);
    failed += 1;
    continue;
  }
  if (!/\(\d+ rows?\)/.test(result)) {
    console.log(`!! ${id} 결과에 행 수 표시가 없다 — 증적으로 쓸 수 없다`);
    failed += 1;
    continue;
  }
  const rows = Number(result.match(/\((\d+) rows?\)/)[1]);
  if (rows === 0) {
    console.log(`!! ${id} 결과가 0행이다 — 증적으로 쓸 수 없다`);
    failed += 1;
    continue;
  }
  const html = `<!doctype html><meta charset="utf-8">
<style>
 body{margin:0;padding:28px 32px;font:14px/1.6 -apple-system,"Apple SD Gothic Neo",sans-serif;background:#fff;color:#111}
 h1{font-size:17px;margin:0 0 4px}
 .note{font-size:13px;color:#555;margin:0 0 18px}
 .lbl{font-size:12px;font-weight:600;color:#444;margin:16px 0 6px}
 pre{margin:0;padding:14px 16px;background:#f6f7f9;border:1px solid #dfe3e8;border-radius:6px;
     font:13px/1.55 "SFMono-Regular",Menlo,monospace;white-space:pre;overflow:visible}
 .meta{margin-top:16px;font-size:12px;color:#666}
</style>
<h1>${esc(c.title)}</h1>
<p class="note">${esc(c.note)}</p>
<div class="lbl">확인 쿼리</div><pre>${esc(c.sql)}</pre>
<div class="lbl">실행 결과</div><pre>${esc(result)}</pre>
<div class="meta">개발서버 PostgreSQL · 스키마 klid_at · 실행 ${new Date().toLocaleString('ko-KR')}</div>`;
  await page.setContent(html, { waitUntil: 'load' });
  const file = path.join(OUT, `KLID-AT-UT-${id}-cut1.png`);
  await page.locator('body').screenshot({ path: file });
  console.log(`  촬영 ${path.basename(file)}  (${rows}행)`);
}
await browser.close();
console.log(`\n== 실행 ${ids.length}건 · 실패 ${failed}건`);
process.exit(failed === 0 ? 0 : 1);
