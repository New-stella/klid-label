import { chromium } from 'playwright'; import fs from 'fs';
const BASE='http://192.168.102.246:13000';
const token=fs.readFileSync(process.argv[2],'utf8').trim();
const b=await chromium.launch({headless:true});
const p=await (await b.newContext({viewport:{width:1680,height:1200}})).newPage();
await p.goto(BASE+'/',{waitUntil:'domcontentloaded'});
await p.evaluate(t=>{localStorage.setItem('klid-jwt-token',t);localStorage.setItem('userId','1001');localStorage.setItem('userNm','검수자');},token);
await p.goto(BASE+'/dashboard',{waitUntil:'domcontentloaded'});
await p.getByRole('link',{name:'영상 처리 현황'}).first().waitFor({timeout:30000});
await p.getByRole('link',{name:'영상 처리 현황'}).first().click(); await p.waitForTimeout(2500);
const rows=p.locator('table tbody tr');
for(let i=0;i<await rows.count();i++){ if((await rows.nth(i).innerText()).includes('CCTV-VERIFY')){ await rows.nth(i).getByRole('button',{name:'상세'}).click(); break; } }
await p.waitForTimeout(3500);
const names=[]; for(const x of await p.locator('button').all()){ const s=(await x.innerText()).replace(/\s+/g,' ').trim(); if(/재수행|건너뛰/.test(s)) names.push(s+(await x.isDisabled()?'(disabled)':'')); }
console.log('URL =',p.url(),'| 재수행/건너뛰기 버튼 =', JSON.stringify(names));
await b.close();
