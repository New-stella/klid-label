/**
 * 단위시험 증적 재촬영 하네스.
 *
 * ⚠ 이 파일은 `frontend/` 안에 있어야 한다 — 밖에 두면 playwright 가 resolve 되지 않는다.
 *
 * 사용: node capture-evidence.mjs <케이스ID...>      (인자 없으면 목록만 출력)
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const APP = process.env.CAPTURE_APP ?? 'http://localhost:13000';
const OUT = process.env.CAPTURE_OUT ?? '/Users/ck/Desktop/CBD-캡처루트-20260906/captures';
const VIEWPORT = { width: 1600, height: 1000 };

mkdirSync(OUT, { recursive: true });

/** dev 로그인 — 역할 토큰을 발급받아 내부 채널로 진입한다. */
async function login(page, role) {
  await page.goto(`${APP}/dev/login`, { waitUntil: 'domcontentloaded' });
  await page.locator(`#dev-login-role-${role}`).check();
  await page.getByRole('button', { name: /토큰 발급/ }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/dev/login'), { timeout: 15000 });
  await page.waitForLoadState('networkidle').catch(() => {});
}

async function shot(page, id, cut) {
  const file = path.join(OUT, `KLID-AT-UT-${id}-cut${cut}.png`);
  await page.screenshot({ path: file, fullPage: true });
  const { width, height } = await page.evaluate(() => ({
    width: document.documentElement.scrollWidth,
    height: document.documentElement.scrollHeight,
  }));
  console.log(`  촬영 ${path.basename(file)}  (${width}x${height})`);
  return file;
}

/** 화면이 실제로 그려졌는지 — 빈 화면을 조용히 찍는 것을 막는다. */
async function expectVisible(page, locator, what) {
  await locator.first().waitFor({ state: 'visible', timeout: 20000 }).catch((e) => {
    throw new Error(`화면 확인 실패 [${what}]: ${e.message}`);
  });
}

/**
 * range 입력에 값을 넣는다.
 *
 * ⚠ `fill()` 은 react-hook-form 이 구독하는 이벤트를 항상 태우지는 않는다 — 네이티브 setter 로
 * 값을 넣고 input·change 를 직접 발화시켜야 폼이 dirty 로 바뀌고 저장 버튼이 열린다.
 */
async function setRange(page, selector, value) {
  await page.locator(selector).evaluate((el, v) => {
    const setter = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      'value',
    ).set;
    setter.call(el, String(v));
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

async function openSettings(page) {
  await page.goto(`${APP}/manage/settings`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('라벨링 정밀도'), '시스템 설정');
  await page.waitForTimeout(1000);
}

const CASES = {
  /**
   * 시스템 설정 — 라벨링 정밀도(경계 세밀함) 저장·반영.
   * 3컷이 각각 다른 것을 말한다: 기본값 → 저장 직후(성공 토스트) → 재진입 후 유지.
   */
  '006-01': async (page) => {
    await login(page, 'REVIEWER');

    // 사전 정리 — 케이스 시작점인 기본값 1.0 으로 되돌린다(직전 회차가 2.5 로 남겨 두었다).
    await openSettings(page);
    const shown = await page.locator('#precision-tolerance').inputValue();
    if (Number(shown) !== 1) {
      await setRange(page, '#precision-tolerance', 1);
      await page.getByRole('button', { name: '저장' }).nth(2).click();
      await expectVisible(page, page.getByText('정밀도 설정 저장됨'), '저장 토스트(사전정리)');
      await page.waitForTimeout(4500); // 토스트가 사라진 뒤 찍는다
    }
    await openSettings(page);
    await expectVisible(page, page.getByText('1.0px'), '기본값 1.0px');
    await shot(page, '006-01', 1);

    // ② 2.5 로 바꿔 저장 — 성공 토스트가 뜬 상태를 찍는다
    await setRange(page, '#precision-tolerance', 2.5);
    await expectVisible(page, page.getByText('2.5px'), '변경값 2.5px');
    await page.getByRole('button', { name: '저장' }).nth(2).click();
    await expectVisible(page, page.getByText('정밀도 설정 저장됨'), '저장 성공 토스트');
    await shot(page, '006-01', 2);

    // ③ 화면을 벗어났다가 재진입 — 저장값이 유지되는지(토스트 없는 상태로 구분된다)
    await page.goto(`${APP}/dashboard`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    await openSettings(page);
    await expectVisible(page, page.getByText('2.5px'), '재진입 후 2.5px 유지');
    await shot(page, '006-01', 3);
  },
};

/** 라벨링 화면 진입 — 캔버스가 실제로 그려질 때까지 기다린다. */
async function openLabel(page, srcSn) {
  await page.goto(`${APP}/label/${srcSn}`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('그리기 도구'), `라벨링 화면 ${srcSn}`);
  await page.waitForTimeout(2500); // 이미지·라벨 로드
}

/**
 * 그리기 도구를 고르고 라벨 분류까지 확정한다.
 *
 * ⚠ 도구를 누르면 「라벨 선택」 다이얼로그가 먼저 뜬다 — 그 사이에 드래그하면 조용히 무시된다.
 */
async function pickTool(page, toolName, labelName) {
  await page.getByRole('button', { name: new RegExp(toolName) }).click();
  const dialog = page.getByRole('dialog');
  if ((await dialog.count()) > 0) {
    await expectVisible(page, page.getByText('라벨 선택'), '라벨 선택 다이얼로그');
    await dialog.getByText(labelName, { exact: true }).first().click();
    await dialog.first().waitFor({ state: 'detached', timeout: 10000 }).catch(() => {});
  }
  await page.waitForTimeout(400);
}

/** 캔버스에 사각형을 그린다(절대 좌표 — 스택된 canvas 3장이라 locator hover 는 가로막힌다). */
async function dragOnCanvas(page, x1, y1, x2, y2) {
  await page.mouse.move(x1, y1);
  await page.waitForTimeout(120);
  await page.mouse.down();
  const steps = 20;
  for (let i = 1; i <= steps; i += 1) {
    await page.mouse.move(x1 + ((x2 - x1) * i) / steps, y1 + ((y2 - y1) * i) / steps);
    await page.waitForTimeout(20);
  }
  await page.mouse.up();
  await page.waitForTimeout(1200);
}

/** 메타 탭 — 시계열 메타·이벤트 어노테이션 표시 */
CASES['022-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, 13);
  await page.getByRole('tab', { name: '메타' }).click().catch(async () => {
    await page.getByText('메타', { exact: true }).first().click();
  });
  // 우측 패널은 자체 스크롤이라 전체 페이지 촬영으로는 아래 섹션이 잡히지 않는다.
  await page.getByText('시계열 메타').first().scrollIntoViewIfNeeded();
  await expectVisible(page, page.getByText('시계열 메타'), '시계열 메타 패널');
  await page.waitForTimeout(1200);
  await shot(page, '022-01', 1);
};

/** 라벨 편집·저장 (2컷: 편집 중 → 저장됨) */
CASES['021-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, 1);
  await pickTool(page, '바운딩 박스', '사람');
  await dragOnCanvas(page, 700, 300, 900, 480);
  await expectVisible(page, page.getByText('편집 중'), '편집 중 표시');
  await shot(page, '021-01', 1);

  await page.getByRole('button', { name: /^저장/ }).first().click();
  await expectVisible(page, page.getByText('저장됨'), '저장됨 표시');
  await page.waitForTimeout(1500);
  await shot(page, '021-01', 2);
};

/** AI 탐지(YOLO) 자동 라벨링 결과 */
CASES['004-02'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, 5);
  await page.getByRole('button', { name: /AI 탐지/ }).click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg.getByText('AI 탐지'), 'AI 탐지 다이얼로그');
  await dlg.getByText('사람', { exact: true }).click(); // 대상 라벨 지정
  await page.waitForTimeout(300);
  // 실행 방식은 「일반」(현재 프레임)과 「트랙」(뒤따르는 프레임 전파) 둘 — 케이스는 일반 탐지다.
  await dlg.getByRole('button', { name: '일반' }).click();
  await page.waitForTimeout(9000); // 추론 대기
  await shot(page, '004-02', 1);
};

/** SAM2 인터랙티브 분할 (2컷: 객체 목록 → 분할 결과) */
CASES['005-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, 5);
  await shot(page, '005-01', 1);
  await pickTool(page, 'AI 분할', '사람');
  // 「즉시 그리기」를 켜야 클릭 프롬프트가 곧바로 폴리곤으로 그려진다(켜지 않으면 점만 찍힌다).
  const draw = page.getByText('즉시 그리기');
  await draw.scrollIntoViewIfNeeded();
  const cb = page.getByRole('checkbox', { name: /즉시 그리기/ });
  if ((await cb.count()) > 0 && !(await cb.first().isChecked())) await cb.first().check();
  // 프레임 좌측의 보행자 위 — 빈 노면을 찍으면 분할할 대상이 없다.
  await page.mouse.click(555, 520);
  await page.waitForTimeout(9000); // SAM2 추론 대기
  await shot(page, '005-01', 2);
};

/** 마킹 화면 + 처리 현황 (cut1 마킹 / cut4 처리 단계) */
CASES['019-01'] = async (page) => {
  await login(page, 'WORKER');
  await page.goto(`${APP}/marking/1`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('마킹'), '마킹 화면');
  await page.waitForTimeout(2500);
  await shot(page, '019-01', 1);
};

CASES['019-01-cut4'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/video/status`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('영상 처리 현황'), '영상 처리 현황');
  await page.waitForTimeout(2000);
  await shot(page, '019-01', 4);
};

/** 비식별 완료 → 마킹 대기 표시 */
CASES['011-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/video/status`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('영상 처리 현황'), '영상 처리 현황');
  // 예상결과는 비식별 완료 후 **마킹 대기** 표시다 — 완료 건만 있는 목록으로는 증명되지 않는다.
  // ⚠ '마킹 대기' 는 상태 필터의 숨은 <option> 에도 있다 — 목록 행으로 범위를 좁힌다.
  await expectVisible(page, page.locator('tbody').getByText('마킹 대기'), '목록의 마킹 대기 표시');
  await page.waitForTimeout(2000);
  await shot(page, '011-01', 1);
};

/** 비식별 누락 신고 (2컷: 신고 다이얼로그 → 접수 후 조회 차단) */
CASES['016-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, 5);
  await page.getByRole('button', { name: /비식별 누락 신고/ }).click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg.getByText('비식별 누락 신고'), '신고 다이얼로그');
  await dlg.getByRole('textbox').fill('오른쪽 보행자 얼굴 블러 처리 누락');
  await page.waitForTimeout(600);
  await shot(page, '016-01', 1);

  await dlg.getByRole('button', { name: '신고하기' }).click();
  await page.waitForTimeout(3000);
  // 신고가 접수되면 그 영상의 라벨 조회가 막힌다 — 그 차단 화면이 곧 성공의 증거다.
  await page.goto(`${APP}/label/5`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText(/비식별 재처리 대기/), '조회 차단 안내');
  await page.waitForTimeout(1200);
  await shot(page, '016-01', 2);
};

/** 검수 승인 (2컷: 검수중 → 승인 완료) */
CASES['023-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, 1);
  await page.getByRole('button', { name: /검수제출/ }).click();
  const c = page.getByRole('dialog');
  if ((await c.count()) > 0) {
    await c.getByRole('button', { name: /제출|확인/ }).last().click();
  }
  await page.waitForTimeout(3000);

  await login(page, 'REVIEWER');
  await page.goto(`${APP}/review/1`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('검수중'), '검수중 배지');
  await page.waitForTimeout(2000);
  await shot(page, '023-01', 1);

  await page.getByRole('button', { name: '승인' }).click();
  const ok = page.getByRole('dialog');
  if ((await ok.count()) > 0) await ok.getByRole('button', { name: /승인|확인/ }).last().click();
  await page.waitForTimeout(3000);
  // 승인 직후가 아니라 재진입 화면이 종결 상태를 말한다(완료 배지 + 재처리 불가 안내).
  await page.goto(`${APP}/review/1`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText(/이미 승인 처리된 검수/), '승인 완료 표시');
  await page.waitForTimeout(1500);
  await shot(page, '023-01', 2);
};

/** 승인 완료 화면만 재촬영(승인이 이미 끝난 뒤 이어 찍을 때) */
CASES['023-01-cut2'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/review/1`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText(/이미 승인 처리된 검수/), '승인 완료 표시');
  await page.waitForTimeout(1500);
  await shot(page, '023-01', 2);
};

/**
 * 버전 간 라벨 비교(diff) 결과 표시.
 *
 * ⚠ 승인 버전이 1건뿐인 프레임은 두 버전을 고를 수 없다 — 그 경우의 정답 경로는
 * 「커밋 1건 선택 = 현재 작업본과 비교」다. 그래서 먼저 작업본을 바꿔 변경점을 만든다.
 */
CASES['008-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, 1);
  await pickTool(page, '바운딩 박스', '자동차');
  await dragOnCanvas(page, 500, 250, 660, 400);
  await page.getByRole('button', { name: /^저장/ }).first().click();
  await expectVisible(page, page.getByText('저장됨'), '작업본 저장');
  await page.waitForTimeout(1500);

  await page.getByRole('button', { name: '버전' }).click();
  await page.waitForTimeout(1200);
  const hist = page.getByRole('button', { name: /버전 이력/ });
  if ((await hist.count()) > 0) {
    await hist.first().click();
    await page.waitForTimeout(1500);
  }
  // ⚠ 체크박스는 「두 버전 비교」축이다. 승인 버전이 1건뿐이면 두 건을 고를 수 없으므로
  //   커밋 **행을 클릭**해 「현재 작업본과 비교」로 들어간다(체크하면 diff 가 나오지 않는다).
  const verTab = page.getByRole('tab', { name: /버전/ });
  if ((await verTab.count()) > 0) await verTab.first().click();
  await page.waitForTimeout(800);
  await page.getByText('APPROVED', { exact: true }).first().click();
  await expectVisible(page, page.getByText(/현재 작업본/), 'diff 비교 대상 표시');
  await page.waitForTimeout(2500);
  await shot(page, '008-01', 1);
};

/** 증강 요청 화면에서 처리 종류와 대상 영상을 고른다. */
async function fillAugmentRequest(page, kind, rawSn) {
  await page.goto(`${APP}/augment`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('처리 종류 선택'), '증강 요청 화면');
  await page.waitForTimeout(1500);
  // ⚠ 「겨울」 같은 낱말은 최근 요청 이력 카드에도 있다 — 처리 종류 목록으로 범위를 좁힌다.
  await page.locator('[data-testid=process-kind-list]').getByText(kind, { exact: true }).first().click();
  await page.waitForTimeout(1000);
  // 대상 영상 — 목록은 표 태그가 아니라 testid 로 잡는다.
  // ⚠ 첫 행을 그냥 고르면 증강 이력이 있는 영상이 잡혀 결과 화면에 옛 실패 배너가 함께 뜬다.
  const list = page.locator('[data-testid=augment-video-table]');
  const row = list.locator('label,tr,li').filter({ hasText: `#${rawSn}` }).first();
  if ((await row.count()) > 0) await row.getByRole('radio').first().check();
  else await list.getByRole('radio').first().check();
  await page.waitForTimeout(800);
}

/**
 * 겨울 증강 단일 요청 (2컷: 요청 화면 → 결과).
 *
 * ⚠ 처리 종류는 「겨울/야간/우천」 3종이 아니라 **「증강 AI」 하나**로 합쳐졌다(ADR-059).
 *   겨울이라는 성격은 종류가 아니라 **생성 조건(계절=WINTER·날씨=SNOW)** 으로 지정한다.
 */
CASES['001-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await fillAugmentRequest(page, '증강 AI', 1);
  // 생성 조건 5항목은 전부 필수다 — 하나라도 비면 요청이 열리지 않는다.
  const mtdt = { time: 'DAY', season: 'WINTER', weather: 'SNOW', terrain: 'ROAD', severity: 'MEDIUM' };
  for (const [k, v] of Object.entries(mtdt)) {
    await page.locator(`#aug-mtdt-${k}`).selectOption(v);
  }
  await page.waitForTimeout(800);
  await shot(page, '001-01', 1);

  await page.locator('[data-testid=augment-submit]').click();
  await page.waitForTimeout(6000);
  await shot(page, '001-01', 2);
};

/** 해상도 변경 파생영상 생성 (2컷: 프리셋 선택 → 생성 결과) */
CASES['003-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await fillAugmentRequest(page, '해상도 변경', 1);
  await expectVisible(page, page.locator('[data-testid=target-resolution-block]'), '해상도 프리셋');
  await page.waitForTimeout(800);
  await shot(page, '003-02', 1);

  await page.locator('[data-testid=augment-submit]').click();
  await expectVisible(page, page.locator('[data-testid=resolution-derivative-result]'), '파생 생성 결과');
  await page.waitForTimeout(3000);
  await shot(page, '003-02', 2);
};


/** 처리 현황 전체 목록을 연다(검색은 CCTV명만 걸려 클립ID 로는 판정할 수 없다). */
async function openStatusList(page) {
  await page.goto(`${APP}/video/status`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('영상 처리 현황'), '영상 처리 현황');
  await page.waitForTimeout(2000);
}

/** 관제 학습용 영상 적재 — cut1 적재 전(그 CCTV 가 목록에 없다) */
CASES['018-01-cut1'] = async (page) => {
  await login(page, 'REVIEWER');
  await openStatusList(page);
  if ((await page.getByText('CCTV-019').count()) > 0) {
    throw new Error('CCTV-019 가 이미 목록에 있다 — 적재 전 상태가 아니다');
  }
  await shot(page, '018-01', 1);
};

/** 관제 학습용 영상 적재 — cut2 적재 후(그 CCTV 가 나타난다) */
CASES['018-01-cut2'] = async (page) => {
  await login(page, 'REVIEWER');
  await openStatusList(page);
  await expectVisible(page, page.getByText('CCTV-019'), '적재된 영상 행');
  await shot(page, '018-01', 2);
};

/** 촬영 전 상태 정리 — 신고를 해소해 라벨 접근을 연다(케이스가 아니라 준비 절차다). */
CASES['prep-resolve'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/manage/deident-reports`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  const btn = page.getByRole('button', { name: /해소/ }).first();
  if ((await btn.count()) === 0) {
    console.log('  열린 신고 없음 — 건너뜀');
    return;
  }
  await btn.click();
  await page.waitForTimeout(1500);
  const radios = page.getByRole('radio');
  const n = await radios.count();
  console.log(`  후보 ${n}건`);
  for (let i = 0; i < n; i += 1) {
    if (await radios.nth(i).isEnabled()) {
      await radios.nth(i).check();
      break;
    }
  }
  await page.getByRole('button', { name: /해소 처리|확인/ }).last().click();
  await page.waitForTimeout(2500);
  console.log('  해소 요청 완료');
};

const ids = process.argv.slice(2);
if (ids.length === 0) {
  console.log('케이스:', Object.keys(CASES).join(' '));
  process.exit(0);
}

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 1, locale: 'ko-KR' });
const page = await ctx.newPage();
page.on('console', (m) => {
  if (m.type() === 'error') console.log('  [브라우저 오류]', m.text().slice(0, 200));
});

let failed = 0;
for (const id of ids) {
  const fn = CASES[id];
  if (!fn) {
    console.log(`!! 미정의 케이스: ${id}`);
    failed += 1;
    continue;
  }
  console.log(`== ${id}`);
  try {
    await fn(page);
  } catch (e) {
    console.log(`!! ${id} 실패: ${e.message}`);
    failed += 1;
  }
}
await browser.close();
process.exit(failed === 0 ? 0 : 1);
