/**
 * 단위시험 증적 재촬영 하네스.
 *
 * ⚠ 이 파일은 `frontend/` 안에 있어야 한다 — 밖에 두면 playwright 가 resolve 되지 않는다.
 *
 * 사용: node capture-evidence.mjs <케이스ID...>      (인자 없으면 목록만 출력)
 */
import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { mkdirSync, readdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { CAPTIONS } from './capture-captions.mjs';

const APP = process.env.CAPTURE_APP ?? 'http://localhost:13000';
/**
 * 포털 채널 앱 주소.
 *
 * ⚠ 포털 화면은 **포털 채널 산출물에만** 있다(`VITE_BUILD_CHANNEL=portal`). 관제 채널 배포본에는
 *   그 라우트가 번들에 아예 없어 404 다 — 런타임 잠금이 아니다. 그래서 포털 케이스는 포털 채널로
 *   띄운 앱을 따로 가리킨다. 안 주면 포털 케이스는 실행되지 않는다.
 */
const PORTAL_APP = process.env.CAPTURE_PORTAL_APP ?? '';
const OUT = process.env.CAPTURE_OUT ?? '/Users/ck/Desktop/CBD-캡처루트-20260906/captures';
/**
 * 증적 해상도 — **실제 모니터 화면 기준 1920×1080** (2026-09-09 확정).
 *
 * ⚠ 구 값 1600×1000 + `fullPage:true` 는 **모니터에 존재하지 않는 그림**을 만들었다. fullPage 는
 *   스크롤 전체를 이어붙이므로 화면보다 세로가 긴 한 장이 나온다(실측 UT-001 컷1 = 1600×1761).
 *   그것이 「합성한 것 아니냐」로 읽힌다. 증적은 사람이 모니터에서 본 것이어야 한다.
 */
const VIEWPORT = { width: 1920, height: 1080 };
/**
 * **한 컷 = 화면 한 장.** 스크롤해 여러 장으로 나누지 않는다 (2026-09-09 사용자 확정).
 *
 * ⚠ 구 동작(스크롤 분할 최대 3장)은 **폐기**한다 — 나뉜 장마다 내용이 중간에서 잘려
 *   *"화면이 잘리고 어색하다"* 는 상태가 됐다. 증적은 사람이 모니터에서 본 한 화면이어야 한다.
 *
 * ★ 판정 기준은 「화면 전부를 담았는가」가 아니라 **그 케이스가 검증하는 것이 보이는가**다.
 *   검증 대상이 첫 화면 아래에 있으면 전체 화면을 여러 장으로 늘리지 말고 **`shotEl` 로 그
 *   자리만** 찍는다. 부분 캡처는 허용되며, 스크롤 분할이 그것을 대신하지 않는다.
 *
 * ⚠ 되살리지 말 것. 값을 2 이상으로 올리면 폐기된 그 동작이 그대로 돌아온다.
 */
const MAX_PAGES = Number(process.env.CAPTURE_MAX_PAGES ?? 1);

/**
 * 촬영 대상 ID — **환경마다 다르다.**
 *
 * 기본값은 로컬 개발 스택 기준이다. 246 처럼 다른 배포에서 찍을 때는 그 서버의 실제 ID 를
 * 환경변수로 준다. 예전에는 이 값들이 본문에 박혀 있어 다른 서버에서 돌리면 **없는 영상·없는
 * 프레임을 열고도 그럴듯한 화면을 찍었다.**
 */
const RAW_SN = Number(process.env.CAPTURE_RAW_SN ?? 1); // 마킹 대상 영상
const REVIEW_ID = Number(process.env.CAPTURE_REVIEW_ID ?? 1); // 검수 대상(영상 번호가 아니라 검수 번호다)
const FRAME_MAIN = Number(process.env.CAPTURE_FRAME_MAIN ?? 1); // 라벨링·검수·버전 이력
const FRAME_AI = Number(process.env.CAPTURE_FRAME_AI ?? 5); // 보간·분할·비식별 신고
const FRAME_META = Number(process.env.CAPTURE_FRAME_META ?? 13); // 시계열 메타 검토
/** 추적·보간이 <뒤따르는 프레임>에 라벨을 만드는지 보는 대상. 같은 영상의 다음 프레임이어야 한다. */
const FRAME_NEXT = Number(process.env.CAPTURE_FRAME_NEXT ?? 2);
/**
 * 추적·보간 촬영에 쓸 두 프레임.
 *
 * ⚠ **이미 라벨이 촘촘한 프레임을 쓰면 아무 일도 안 일어난 것처럼 보인다** — 새 검출이 기존 라벨과
 *   겹쳐 반영되지 않기 때문이다(화면이 「겹쳐 반영하지 않았습니다」로 알린다). 라벨이 드문 프레임을
 *   고른다. 그 서버에서 이렇게 찾는다:
 *     SELECT s.src_sn, s.raw_sn, (SELECT count(*) FROM klid_at.ls_data_lbl l WHERE l.src_sn=s.src_sn) c
 *       FROM klid_at.ls_data_src s ... ORDER BY c;
 */
/**
 * 비식별 누락 신고용 프레임 — **AI 용과 같은 값을 쓰면 안 된다.**
 *
 * 두 케이스의 전제가 정반대다: AI 탐지는 오토라벨이 붙은(=파이프라인이 끝난) 프레임이라야 하고,
 * 신고는 **한 번도 검수 승인된 적 없는** 영상이라야 한다(승인 이력이 있으면 신고 버튼이 잠긴다).
 * 한 값을 공유하면 하나를 맞추는 순간 다른 하나가 깨진다(실측 2026-09-09).
 */
const FRAME_REPORT = Number(process.env.CAPTURE_FRAME_REPORT ?? FRAME_AI);
/**
 * 해상도 파생을 만들 대상 영상 — **이미 3종이 다 만들어진 영상이면 안 된다.**
 *
 * 중복 방지 제약(같은 영상 × 같은 프리셋 1건)이 있어, 세 프리셋이 모두 있는 영상을 고르면
 * 「전부 스킵」으로 400 이 되어 결과 화면이 뜨지 않는다(실측 2026-09-09: 영상 #1 이 그랬다).
 */
const RESL_RAW_SN = Number(process.env.CAPTURE_RESL_RAW_SN ?? 1);
const FRAME_TRACK = Number(process.env.CAPTURE_FRAME_TRACK ?? FRAME_MAIN);
const FRAME_TRACK_NEXT = Number(process.env.CAPTURE_FRAME_TRACK_NEXT ?? FRAME_NEXT);
/**
 * 수동 마킹을 찍을 시점(초).
 *
 * ★**임의로 고르면 안 된다.** 마킹은 프레임 추출 위치를 정하는 것이라, 대상이 드문 구간에 찍으면
 *   추출된 프레임에 잡을 것이 없어 **빈 캔버스가 라벨링 증적으로 남는다.** 영상을 실제로 보고
 *   대상이 또렷한 구간을 고른 뒤 그 값을 환경변수로 준다.
 * ★서로 같은 프레임으로 합쳐지지 않을 만큼 떨어지되, **같은 대상이 이어질 만큼은 가까워야**
 *   추적·보간 증적이 성립한다.
 */
/** 적재 전/후를 보는 케이스(007-01)가 기다리는 클립 이름. 촬영 중 실제로 인입시킬 대상이다. */
const INGEST_CLIP = process.env.CAPTURE_INGEST_CLIP ?? 'CCTV-019';

const MARK_SECONDS = (process.env.CAPTURE_MARK_SECONDS ?? '5,20,45')
  .split(',')
  .map((s) => Number(s.trim()))
  .filter((n) => Number.isFinite(n));

mkdirSync(OUT, { recursive: true });

/** dev 로그인 — 역할 토큰을 발급받아 내부 채널로 진입한다. */
async function login(page, role) {
  await page.goto(`${APP}/dev/login`, { waitUntil: 'domcontentloaded' });
  await page.locator(`#dev-login-role-${role}`).check();
  await page.getByRole('button', { name: /토큰 발급/ }).click();
  // ⚠ 베이스 경로가 있는 배포에서는 경로가 `/label-studio/dev/login` 이라 `startsWith('/dev/login')`
  //   이 로그인 페이지에서도 거짓이다 — 그러면 이 대기가 즉시 통과해 토큰 발급을 안 기다리고,
  //   뒤이은 화면 진입이 경쟁 조건으로 간헐 실패한다. 포함 여부로 판정한다.
  await page.waitForURL((u) => !u.pathname.includes('/dev/login'), { timeout: 15000 });
  await page.waitForLoadState('networkidle').catch(() => {});
}

async function shot(page, id, cut) {
  const { height } = await page.evaluate(() => ({
    height: Math.max(document.documentElement.scrollHeight, document.body?.scrollHeight ?? 0),
  }));
  const vh = VIEWPORT.height;
  const need = Math.max(1, Math.ceil(height / vh));
  const pages = Math.min(MAX_PAGES, need);

  // 한 화면에 들어오면 파일 이름을 그대로 둔다 — 대부분이 이쪽이고, 공유 컷 참조가 이 이름을 쓴다.
  if (pages === 1) {
    const file = path.join(OUT, `KLID-AT-UT-${id}-cut${cut}.png`);
    await page.screenshot({ path: file });
    console.log(`  촬영 ${path.basename(file)}  (${VIEWPORT.width}x${vh})`);
    return [file];
  }

  const files = [];
  for (let i = 0; i < pages; i += 1) {
    await page.evaluate((y) => window.scrollTo(0, y), i * vh);
    // 스크롤 뒤 지연 렌더(가상 목록·lazy 이미지)를 기다린다. 없으면 빈 칸을 찍는다.
    await page.waitForTimeout(500);
    const file = path.join(OUT, `KLID-AT-UT-${id}-cut${cut}p${i + 1}.png`);
    await page.screenshot({ path: file });
    files.push(file);
    console.log(`  촬영 ${path.basename(file)}  (${VIEWPORT.width}x${vh})`);
  }
  // ⚠ 스크롤 위치를 되돌린다 — 다음 동작이 화면 위쪽 요소를 클릭하는 경우가 있다.
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(200);
  if (need > pages) {
    console.log(`  ! ${id}-cut${cut}: 화면이 ${height}px 라 ${need}장이 필요하나 상한 ${pages}장까지만 찍었다`);
    console.log('    검증 대상이 아래쪽이면 전체 화면 대신 shotEl 로 그 자리를 찍을 것 (스크롤 분할은 폐기됐다)');
  }
  return files;
}

/**
 * 앱 확인 모달을 확정한다.
 *
 * ⚠ 브라우저 `confirm` 이 아니라 앱이 그리는 모달이라 `page.on('dialog')` 로는 안 잡힌다.
 *   그리고 backdrop 이 뒤 화면의 클릭을 가로막으므로 모달을 닫기 전에는 다음 동작이 안 된다.
 */
async function confirmModal(page, buttonName) {
  const back = page.locator('[data-testid=modal-backdrop]');
  await back.first().waitFor({ state: 'visible', timeout: 10000 });
  await back.first().getByRole('button', { name: buttonName }).click();
  await back.first().waitFor({ state: 'detached', timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(1200);
}

/**
 * **검증 대상을 화면 안으로 끌어온 뒤 화면 한 장을 찍는다.**
 *
 * 대상이 첫 화면 아래에 있으면 그냥 찍은 1920×1080 에는 그 대상이 없다 — 증적이 아무것도
 * 증명하지 못한다. 그렇다고 스크롤 분할로 여러 장을 내면 내용이 중간에서 잘린다(폐기된 동작).
 * 그래서 **대상을 보이게 만든 뒤 한 장**을 찍는다. 주변 맥락이 함께 남으므로, 잘라낸 조각만
 * 남기는 `shotEl` 보다 이쪽이 기본이다.
 */
async function shotAt(page, locator, id, cut) {
  await locator.first().scrollIntoViewIfNeeded();
  // 스크롤 뒤 지연 렌더(가상 목록·lazy 이미지)를 기다린다. 없으면 빈 자리를 찍는다.
  await page.waitForTimeout(600);
  const file = path.join(OUT, `KLID-AT-UT-${id}-cut${cut}.png`);
  await page.screenshot({ path: file });
  console.log(`  촬영 ${path.basename(file)}  (${VIEWPORT.width}x${VIEWPORT.height} · 대상 정렬)`);
  return [file];
}

/**
 * 요소 하나만 찍는다.
 *
 * 전체 화면을 찍으면 서로 다른 케이스가 <같은 그림>을 증적으로 갖게 되어 무엇을 보였는지가
 * 흐려진다. 그 케이스가 말하는 자리가 화면의 한 조각이면 그 조각만 찍는다.
 *
 * ⚠ 맥락이 함께 필요하면 `shotAt` 을 쓴다 — 조각만 남기면 그 화면의 어디인지가 사라진다.
 */
async function shotEl(page, locator, id, cut) {
  await locator.first().scrollIntoViewIfNeeded();
  await page.waitForTimeout(600);
  const file = path.join(OUT, `KLID-AT-UT-${id}-cut${cut}.png`);
  await locator.first().screenshot({ path: file });
  console.log(`  촬영 ${path.basename(file)}  (요소)`);
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

/**
 * 설정 화면의 카드별 「저장」 버튼.
 *
 * ⚠ 카드마다 같은 이름의 버튼이 있다. 순번(`nth(2)`)이나 `first()` 로 집으면 카드가 하나 늘거나
 *   순서가 바뀌는 순간 <다른 카드를 저장하고도 오류가 나지 않는다>. 그 카드에만 있는 입력으로
 *   카드를 특정한다.
 */
function cardSaveButton(page, ownInputSelector) {
  // 그 입력에서 위로 올라가 <저장 버튼을 품은 가장 가까운 조상>이 곧 그 카드다.
  // `filter({has})` + `first/last` 는 카드가 아니라 전체 컨테이너나 버튼 없는 안쪽 div 를 집는다.
  return page
    .locator(ownInputSelector)
    .locator('xpath=ancestor::*[.//button[normalize-space()="저장"]][1]')
    .getByRole('button', { name: '저장' });
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
  '003-01': async (page) => {
    await login(page, 'REVIEWER');

    // 사전 정리 — 케이스 시작점인 기본값 1.0 으로 되돌린다(직전 회차가 2.5 로 남겨 두었다).
    await openSettings(page);
    const shown = await page.locator('#precision-tolerance').inputValue();
    if (Number(shown) !== 1) {
      await setRange(page, '#precision-tolerance', 1);
      await cardSaveButton(page, '#precision-tolerance').click();
      await expectVisible(page, page.getByText('정밀도 설정 저장됨'), '저장 토스트(사전정리)');
      await page.waitForTimeout(4500); // 토스트가 사라진 뒤 찍는다
    }
    await openSettings(page);
    await expectVisible(page, page.getByText('1.0px'), '기본값 1.0px');
    await shot(page, '003-01', 1);

    // ② 2.5 로 바꿔 저장 — 성공 토스트가 뜬 상태를 찍는다
    await setRange(page, '#precision-tolerance', 2.5);
    await expectVisible(page, page.getByText('2.5px'), '변경값 2.5px');
    await cardSaveButton(page, '#precision-tolerance').click();
    await expectVisible(page, page.getByText('정밀도 설정 저장됨'), '저장 성공 토스트');
    await shot(page, '003-01', 2);

    // ③ 화면을 벗어났다가 재진입 — 저장값이 유지되는지(토스트 없는 상태로 구분된다)
    await page.goto(`${APP}/dashboard`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    await openSettings(page);
    await expectVisible(page, page.getByText('2.5px'), '재진입 후 2.5px 유지');
    await shot(page, '003-01', 3);
  },
};

/**
 * 영상이 **실제로 재생 가능한 상태**인지 확인한다.
 *
 * ⚠ 이것이 없으면 영상이 안 뜬 화면을 조용히 찍는다 — 실제로 그렇게 찍힌 증적이 있었다.
 *   실패하면 진단값(주소·readyState·해상도·오류코드)을 담아 던진다.
 */
async function waitForVideoReady(page, what) {
  await page
    .locator('video')
    .first()
    .waitFor({ state: 'attached', timeout: 20000 })
    .catch(() => {
      throw new Error(`영상 요소를 찾지 못했다 [${what}]`);
    });

  const ok = await page
    .waitForFunction(
      () => {
        const v = document.querySelector('video');
        return !!v && v.readyState >= 1 && v.videoWidth > 0;
      },
      undefined,
      { timeout: 30000 },
    )
    .then(() => true)
    .catch(() => false);

  if (!ok) {
    const diag = await page.evaluate(() => {
      const v = document.querySelector('video');
      if (!v) return null;
      return {
        src: v.currentSrc || v.getAttribute('src'),
        readyState: v.readyState,
        videoWidth: v.videoWidth,
        errorCode: v.error ? v.error.code : null,
      };
    });
    throw new Error(`영상이 재생 가능 상태가 되지 않았다 [${what}] ${JSON.stringify(diag)}`);
  }
}

/** 영상을 지정 시점으로 옮긴다 — 옮겨졌는지 확인하고 돌아온다. */
async function seekVideo(page, seconds) {
  await page.evaluate((t) => {
    const v = document.querySelector('video');
    if (v) v.currentTime = t;
  }, seconds);
  await page.waitForFunction(
    (t) => {
      const v = document.querySelector('video');
      return !!v && Math.abs(v.currentTime - t) < 0.5;
    },
    seconds,
    { timeout: 10000 },
  );
}

/** 라벨링 화면 진입 — 캔버스가 실제로 그려질 때까지 기다린다. */
async function openLabel(page, srcSn) {
  await page.goto(`${APP}/label/${srcSn}`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('그리기 도구'), `라벨링 화면 ${srcSn}`);
  // ★시간이 아니라 **상태**로 기다린다 — 캔버스가 실제 크기를 가질 때까지.
  //   고정 대기(2.5초)는 프레임·서버 상태에 따라 모자라고, 그러면 도구 클릭과 드래그가
  //   **조용히 무시된다**(오류 없이 「편집 중」이 안 뜬다 — 실측 2026-09-09 014-01).
  await page
    .waitForFunction(
      () => {
        const c = document.querySelector('canvas');
        if (!c) return false;
        const r = c.getBoundingClientRect();
        return r.width > 100 && r.height > 100;
      },
      undefined,
      { timeout: 30000 },
    )
    .catch(() => {
      throw new Error(`라벨링 캔버스가 준비되지 않았다 [프레임 ${srcSn}]`);
    });
  await page.waitForTimeout(1500); // 라벨 오버레이 렌더 여유
  // ⚠ 승인 버전이 둘 이상이면 진입 직후 <시작 버전 선택>이 자동으로 뜬다. backdrop 이 그리기 도구
  //   클릭을 가로막으므로 편집을 하려는 케이스는 먼저 닫아야 한다 — 작업본을 그대로 이어 간다.
  const back = page.locator('[data-testid=modal-backdrop]');
  if ((await back.count()) > 0 && (await page.getByText('시작 버전 선택').count()) > 0) {
    await page.getByRole('button', { name: '현재 작업본으로 시작' }).click();
    await back.first().waitFor({ state: 'detached', timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(1200);
  }
}

/**
 * 그리기 도구를 고르고 라벨 분류까지 확정한다.
 *
 * ⚠ 도구를 누르면 「라벨 선택」 다이얼로그가 먼저 뜬다 — 그 사이에 드래그하면 조용히 무시된다.
 */
/**
 * 검수를 제출한다 — **이미 제출돼 있으면 건너뛴다.**
 *
 * ⚠ 제출 버튼은 사라지지 않고 `disabled` 로 남는다(title: 「이미 검수 제출되어 검수 대기 중입니다」).
 *   그래서 그냥 누르면 **30초 클릭 타임아웃**으로 죽는다 — 실측 2026-09-09.
 *   앞 케이스가 이미 제출해 둔 상태가 정상 동선이므로, 없는 일로 보고 지나간다.
 */
async function submitForReview(page) {
  const btn = page.getByRole('button', { name: /검수제출|재검수 제출/ }).first();
  await expectVisible(page, btn, '검수 제출 버튼');
  if (await btn.isDisabled()) {
    console.log('  이미 검수 제출된 상태 — 제출 단계 생략');
    return false;
  }
  await btn.click();
  const c = page.getByRole('dialog');
  if ((await c.count()) > 0) {
    await c.getByRole('button', { name: /제출|확인/ }).last().click();
  }
  await page.waitForTimeout(3000);
  return true;
}

async function pickTool(page, toolName, labelName) {
  await page.getByRole('button', { name: new RegExp(toolName) }).click();
  const dialog = page.getByRole('dialog');
  // ⚠ 창은 **비동기로** 뜬다 — 클릭 직후 count() 를 세면 0 이라 라벨 선택을 통째로 건너뛰고,
  //   그 뒤 창이 떠서 backdrop 이 드래그를 막는다. 그러면 아무 오류 없이 「편집 중」이 안 뜬다
  //   (실측 2026-09-09 014-01 — 개수를 세기 전에 <떠오르기를> 기다려야 한다).
  await dialog.first().waitFor({ state: 'visible', timeout: 4000 }).catch(() => {});
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
CASES['012-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_META);
  await page.getByRole('tab', { name: '메타' }).click().catch(async () => {
    await page.getByText('메타', { exact: true }).first().click();
  });
  // 우측 패널은 자체 스크롤이라 전체 페이지 촬영으로는 아래 섹션이 잡히지 않는다.
  await page.getByText('시계열 메타').first().scrollIntoViewIfNeeded();
  await expectVisible(page, page.getByText('시계열 메타'), '시계열 메타 패널');
  await page.waitForTimeout(1200);
  await shot(page, '012-01', 1);
};

/** 라벨 편집·저장 (2컷: 편집 중 → 저장됨) */
CASES['014-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_MAIN);
  await pickTool(page, '바운딩 박스', '사람');
  await dragOnCanvas(page, 700, 300, 900, 480);
  await expectVisible(page, page.getByText('편집 중'), '편집 중 표시');
  await shot(page, '014-01', 1);

  await page.getByRole('button', { name: /^저장/ }).first().click();
  await expectVisible(page, page.getByText('저장됨'), '저장됨 표시');
  await page.waitForTimeout(1500);
  await shot(page, '014-01', 2);
};

/** AI 탐지(YOLO) 자동 라벨링 결과 */
CASES['017-02'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_AI);
  await page.getByRole('button', { name: /AI 탐지/ }).click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg.getByText('AI 탐지'), 'AI 탐지 다이얼로그');
  await dlg.getByText('사람', { exact: true }).click(); // 대상 라벨 지정
  await page.waitForTimeout(300);
  // 실행 방식은 「일반」(현재 프레임)과 「트랙」(뒤따르는 프레임 전파) 둘 — 케이스는 일반 탐지다.
  await dlg.getByRole('button', { name: '일반' }).click();
  await page.waitForTimeout(9000); // 추론 대기
  await shot(page, '017-02', 1);
};

/** SAM2 인터랙티브 분할 (2컷: 객체 목록 → 분할 결과) */
CASES['016-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_AI);
  // ★진입 직후를 찍으면 015-01(AI 탐지)의 cut1 과 **바이트까지 같은 그림**이 된다 — 같은 프레임을
  //   같은 상태로 열기 때문이다(실측 2026-09-09). 이 케이스의 대상은 **AI 분할** 이므로 도구를
  //   고른 뒤, 그 도구가 보이게 찍는다.
  await pickTool(page, 'AI 분할', '사람');
  await shotAt(page, page.getByRole('button', { name: /AI 분할/ }), '016-01', 1);
  // 「즉시 그리기」를 켜야 클릭 프롬프트가 곧바로 폴리곤으로 그려진다(켜지 않으면 점만 찍힌다).
  const draw = page.getByText('즉시 그리기');
  await draw.scrollIntoViewIfNeeded();
  const cb = page.getByRole('checkbox', { name: /즉시 그리기/ });
  if ((await cb.count()) > 0 && !(await cb.first().isChecked())) await cb.first().check();

  // ★ **찍을 자리를 좌표로 박지 않는다** (2026-09-10).
  //
  // 구 동작은 `mouse.click(555, 520)` 이었다. 그 좌표는 어느 한 프레임에서 보행자가 있던
  // 자리이고, 대상 프레임이 바뀌면 **빈 노면을 찍는다.** 그러면 SAM2 가 「낮은 신뢰도(23%)」를
  // 돌려주고 자동 적용이 차단되어(임계 0.3) 증적에 **폴리곤이 붙은 화면이 남지 않는다** —
  // 실측 2026-09-09. 246 의 SAM2 는 목이 아니므로 그 낮은 점수는 실제 모델의 정직한 출력이었다.
  //
  // 그래서 **라벨이 그려져 있는 자리**를 후보로 쓴다. 오토라벨이 상자를 그린 곳에는 대상이
  // 실재하므로(YOLO 가 거기서 검출했다) 그 픽셀 위를 찍으면 분할할 것이 있다.
  const targets = await sam2ClickTargets(page);
  if (targets.length === 0) throw new Error('분할할 대상을 찾지 못했다 — 이 프레임에 라벨이 하나도 없다');

  const before = await objectCount(page);
  let applied = false;
  for (const [x, y] of targets) {
    await page.mouse.click(x, y);
    await page.waitForTimeout(9000); // SAM2 추론 대기
    const low = await page.getByText(/낮은 신뢰도/).count();
    const now = await objectCount(page);
    if (low === 0 && now > before) { applied = true; break; }
    console.log(`  (${Math.round(x)},${Math.round(y)}) 신뢰도 미달 — 다음 후보`);
  }
  // 빈 캔버스나 「낮은 신뢰도」 안내만 남은 화면은 **외곽 분할의 증적이 아니다.** 조용히 찍지 않는다.
  if (!applied) throw new Error('SAM2 분할 결과가 적용되지 않았다 — 외곽선이 붙은 화면이 아니다');
  await page.waitForTimeout(1200);
  await shot(page, '016-01', 2);
};

/**
 * SAM2 클릭 프롬프트로 쓸 화면 좌표 후보 — **라벨이 그려진 픽셀**에서 뽑는다.
 *
 * 라벨 오버레이는 별도 canvas 에 그려지고 그 위에는 이미지가 없어 `getImageData` 가 막히지
 * 않는다(이미지 레이어는 교차 출처면 tainted 되므로 건너뛴다). 비어 있지 않은 픽셀 = 상자·
 * 폴리곤이 그려진 자리 = 대상이 실재하는 자리다.
 *
 * 여러 후보를 돌려주는 이유: 상자 **테두리**를 집으면 객체 경계라 점수가 낮게 나올 수 있다.
 * 부르는 쪽이 성공할 때까지 차례로 시도한다.
 */
async function sam2ClickTargets(page) {
  return page.evaluate(() => {
    const pts = [];
    for (const c of document.querySelectorAll('canvas')) {
      const r = c.getBoundingClientRect();
      if (r.width < 100 || r.height < 100) continue;
      let data;
      try {
        data = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
      } catch {
        continue; // 교차 출처 이미지가 올라간 레이어 — 읽을 수 없다(그 레이어는 대상이 아니다)
      }
      const hits = [];
      for (let y = 0; y < c.height; y += 4) {
        for (let x = 0; x < c.width; x += 4) {
          if (data[(y * c.width + x) * 4 + 3] > 40) hits.push([x, y]);
        }
      }
      if (hits.length < 20) continue; // 거의 빈 레이어 — 라벨 레이어가 아니다
      // 고르게 퍼진 12점만 남긴다(같은 상자만 반복해 찍지 않도록).
      const step = Math.max(1, Math.floor(hits.length / 12));
      for (let i = 0; i < hits.length; i += step) {
        const [x, y] = hits[i];
        pts.push([r.left + (x * r.width) / c.width, r.top + (y * r.height) / c.height]);
      }
    }
    return pts.slice(0, 12);
  });
}

/** 마킹 화면 + 처리 현황 (cut1 마킹 / cut4 처리 단계) */
/**
 * 촬영 전 준비 — 마킹 대상 영상을 작업자에게 배정한다(케이스가 아니라 준비 절차다).
 *
 * ⚠ **마킹 단계 영상은 작업 목록에 없다**(그 화면은 처리 완료만 표시). 그래서 「배정」 버튼으로는
 *   갈 수 없고, 영상 처리 현황의 **「마킹 설정」 → 「수동」** 을 고르면 작업자 배정 흐름으로
 *   전환된다. 이 경로를 모르면 011-01 이 403(본인에게 배정되지 않은 영상)으로 막힌다
 *   — 실측 2026-09-09. 마킹은 작업권한이 WORKER 라 검수자로 대신 찍어서는 안 된다.
 */
CASES['prep-marking-assign'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/video/status`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('영상 처리 현황'), '영상 처리 현황');
  await page.waitForTimeout(2000);

  const row = page.getByRole('row').filter({ hasText: `#${RAW_SN}` }).first();
  const target = (await row.count()) > 0 ? row : page.getByRole('row').filter({ hasText: '마킹 대기' }).first();
  const setBtn = target.getByRole('button', { name: /마킹 설정/ });
  if ((await setBtn.count()) === 0) {
    console.log(`  이미 배정돼 있거나 마킹 대상이 아니다 (영상 ${RAW_SN}) — 준비 생략`);
    return;
  }
  await setBtn.click();
  await expectVisible(page, page.getByText('수동', { exact: true }), '마킹 방식 선택');
  await page.getByText('수동', { exact: true }).click();
  await page.waitForTimeout(1200);

  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, '작업자 배정 다이얼로그');
  await dlg.locator('#assign-worker').click();
  const opts = page.getByRole('option');
  await opts.first().waitFor({ state: 'visible', timeout: 10000 });
  const worker = opts.filter({ hasNotText: '작업자 선택' }).first();
  const who = (await worker.innerText()).trim();
  await worker.click();
  await page.waitForTimeout(600);
  await dlg.getByRole('button', { name: /배정|저장|확인/ }).last().click();
  await page.waitForTimeout(2500);
  console.log(`  영상 ${RAW_SN} → ${who} 배정`);
};

CASES['011-01'] = async (page) => {
  await login(page, 'WORKER');
  await page.goto(`${APP}/marking/${RAW_SN}`, { waitUntil: 'domcontentloaded' });

  // 진입 차단 화면이면 그건 증적이 아니라 결함이다 — 조용히 찍지 않는다.
  if ((await page.getByText(/비식별 완료 후 마킹이 가능합니다/).count()) > 0) {
    throw new Error(`마킹 진입이 차단됐다 (영상 ${RAW_SN}) — 비식별 상태를 먼저 확인할 것`);
  }
  await expectVisible(page, page.getByText('현재 마킹'), '마킹 화면');
  await waitForVideoReady(page, `마킹 영상 ${RAW_SN}`);

  // 단축키는 입력 요소에 포커스가 있으면 발화하지 않는다 — 포커스를 본문으로 돌린다.
  await page.evaluate(() => {
    const el = document.activeElement;
    if (el instanceof HTMLElement) el.blur();
  });
  await page.keyboard.press('Digit1'); // 수동 모드
  await page.waitForTimeout(300);

  for (const t of MARK_SECONDS) {
    await seekVideo(page, t);
    await page.keyboard.press('Space');
    await page.waitForTimeout(400);
  }

  // ★몇 건이 찍혔는지 화면이 말한다. 0건짜리 화면을 「마킹 생성」 증적으로 남기지 않는다.
  await expectVisible(
    page,
    page.getByText(`현재 마킹 (${MARK_SECONDS.length}건)`),
    `마킹 칩 ${MARK_SECONDS.length}건`,
  );
  await page.waitForTimeout(800);
  await shot(page, '011-01', 1);

  // ★촬영 뒤 [마킹 완료]까지 해야 한다 — 마크는 제출 전까지 브라우저 안에만 있고,
  //   제출해야 잔여 배치(프레임 추출·오토라벨)가 돌아 뒤 케이스들의 재료가 생긴다.
  //   D11 절차의 3단계이기도 하다.
  const submit = page.getByRole('button', { name: /마킹 완료/ });
  if ((await submit.count()) > 0) {
    await submit.first().click();
  } else {
    await page.keyboard.press('Enter'); // 단축키 경로
  }
  const dlg = page.getByRole('dialog');
  if ((await dlg.count()) > 0) {
    await dlg.getByRole('button', { name: /완료|확인|제출/ }).last().click().catch(() => {});
  }
  await page.waitForTimeout(3000);
  console.log('  마킹 완료 제출함');
};

CASES['011-01-cut4'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/video/status`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('영상 처리 현황'), '영상 처리 현황');
  await page.waitForTimeout(2000);
  await shot(page, '011-01', 4);
};

/** 비식별 완료 → 마킹 대기 표시 */
CASES['009-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/video/status`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('영상 처리 현황'), '영상 처리 현황');
  // 예상결과는 비식별 완료 후 **마킹 대기** 표시다 — 완료 건만 있는 목록으로는 증명되지 않는다.
  // ⚠ '마킹 대기' 는 상태 필터의 숨은 <option> 에도 있다 — 목록 행으로 범위를 좁힌다.
  await expectVisible(page, page.locator('tbody').getByText('마킹 대기'), '목록의 마킹 대기 표시');
  await page.waitForTimeout(2000);
  // ★대상을 겨냥한다 — 목록 전체를 찍으면 011-01-cut4(같은 화면)와 **바이트까지 같은 그림**이
  //   되어 두 케이스가 구분되지 않는다(실측 2026-09-09).
  await shotAt(page, page.locator('tbody').getByText('마킹 대기').first(), '009-01', 1);
};

/** 비식별 누락 신고 (2컷: 신고 다이얼로그 → 접수 후 조회 차단) */
CASES['010-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_REPORT);
  const reportBtn = page.getByRole('button', { name: /비식별 누락 신고/ });
  // 버튼이 잠겨 있으면 그건 전제조건 문제다 — 잠긴 화면을 신고 증적으로 남기지 않는다.
  if (await reportBtn.first().isDisabled()) {
    throw new Error(
      `신고 버튼이 잠겨 있다 (프레임 ${FRAME_REPORT}) — 승인 이력이 없는 영상의 프레임을 ` +
        'CAPTURE_FRAME_REPORT 로 지정할 것',
    );
  }
  await reportBtn.click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg.getByText('비식별 누락 신고'), '신고 다이얼로그');
  await dlg.getByRole('textbox').fill('오른쪽 보행자 얼굴 블러 처리 누락');
  await page.waitForTimeout(600);
  await shot(page, '010-01', 1);

  await dlg.getByRole('button', { name: '신고하기' }).click();
  await page.waitForTimeout(3000);
  // 신고가 접수되면 그 영상의 라벨 조회가 막힌다 — 그 차단 화면이 곧 성공의 증거다.
  await page.goto(`${APP}/label/${FRAME_REPORT}`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText(/비식별 재처리 대기/), '조회 차단 안내');
  await page.waitForTimeout(1200);
  await shot(page, '010-01', 2);
};

/** 검수 승인 (2컷: 검수중 → 승인 완료) */
CASES['019-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_MAIN);
  await submitForReview(page);

  await login(page, 'REVIEWER');
  await page.goto(`${APP}/review/${REVIEW_ID}`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('검수중'), '검수중 배지');
  await page.waitForTimeout(2000);
  await shot(page, '019-01', 1);

  await page.getByRole('button', { name: '승인' }).click();
  const ok = page.getByRole('dialog');
  if ((await ok.count()) > 0) await ok.getByRole('button', { name: /승인|확인/ }).last().click();
  await page.waitForTimeout(3000);
  // 승인 직후가 아니라 재진입 화면이 종결 상태를 말한다(완료 배지 + 재처리 불가 안내).
  await page.goto(`${APP}/review/${REVIEW_ID}`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText(/이미 승인 처리된 검수/), '승인 완료 표시');
  await page.waitForTimeout(1500);
  await shot(page, '019-01', 2);
};

/** 승인 완료 화면만 재촬영(승인이 이미 끝난 뒤 이어 찍을 때) */
CASES['019-01-cut2'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/review/${REVIEW_ID}`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText(/이미 승인 처리된 검수/), '승인 완료 표시');
  await page.waitForTimeout(1500);
  await shot(page, '019-01', 2);
};

/**
 * 버전 간 라벨 비교(diff) 결과 표시.
 *
 * ⚠ 승인 버전이 1건뿐인 프레임은 두 버전을 고를 수 없다 — 그 경우의 정답 경로는
 * 「커밋 1건 선택 = 현재 작업본과 비교」다. 그래서 먼저 작업본을 바꿔 변경점을 만든다.
 */
CASES['021-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_MAIN);
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
  await shot(page, '021-01', 1);
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
CASES['023-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await fillAugmentRequest(page, '증강 AI', 1);
  // 생성 조건 5항목은 전부 필수다 — 하나라도 비면 요청이 열리지 않는다.
  const mtdt = { time: 'DAY', season: 'WINTER', weather: 'SNOW', terrain: 'ROAD', severity: 'MEDIUM' };
  for (const [k, v] of Object.entries(mtdt)) {
    await page.locator(`#aug-mtdt-${k}`).selectOption(v);
  }
  await page.waitForTimeout(800);
  // ★생성 조건을 채우면서 스크롤이 내려간다 — 그대로 찍으면 **이 케이스가 증명할 5항목이
  //   화면 위로 잘려 나간다**(실측 2026-09-09). 그 블록을 화면 안으로 끌어온 뒤 찍는다.
  await shotAt(page, page.locator('#aug-mtdt-time'), '023-01', 1);

  await page.locator('[data-testid=augment-submit]').click();
  await page.waitForTimeout(6000);
  await shot(page, '023-01', 2);
};

/** 해상도 변경 파생영상 생성 (2컷: 프리셋 선택 → 생성 결과) */
CASES['026-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await fillAugmentRequest(page, '해상도 변경', RESL_RAW_SN);
  await expectVisible(page, page.locator('[data-testid=target-resolution-block]'), '해상도 프리셋');
  await page.waitForTimeout(800);
  await shot(page, '026-02', 1);

  await page.locator('[data-testid=augment-submit]').click();
  await expectVisible(page, page.locator('[data-testid=resolution-derivative-result]'), '파생 생성 결과');
  await page.waitForTimeout(3000);
  await shot(page, '026-02', 2);
};


/** 처리 현황 전체 목록을 연다(검색은 CCTV명만 걸려 클립ID 로는 판정할 수 없다). */
async function openStatusList(page) {
  await page.goto(`${APP}/video/status`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('영상 처리 현황'), '영상 처리 현황');
  await page.waitForTimeout(2000);
}

/** 관제 학습용 영상 적재 — cut1 적재 전(그 CCTV 가 목록에 없다) */
CASES['007-01-cut1'] = async (page) => {
  await login(page, 'REVIEWER');
  await openStatusList(page);
  if ((await page.getByText(INGEST_CLIP).count()) > 0) {
    throw new Error(`${INGEST_CLIP} 가 이미 목록에 있다 — 적재 전 상태가 아니다`);
  }
  await shot(page, '007-01', 1);
};

/** 관제 학습용 영상 적재 — cut2 적재 후(그 CCTV 가 나타난다) */
CASES['007-01-cut2'] = async (page) => {
  await login(page, 'REVIEWER');
  await openStatusList(page);
  await expectVisible(page, page.getByText(INGEST_CLIP), '적재된 영상 행');
  await shot(page, '007-01', 2);
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


// ─────────────────────────────────────────────────────────────────────────────
// 2026-09-09 추가분 — 실제 화면을 몰아 데이터를 만들면서 찍는다.
// ─────────────────────────────────────────────────────────────────────────────

/** 시스템 설정 — 조회·수정과 값 반영 */
CASES['002-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await openSettings(page);
  await expectVisible(page, page.getByText('배치 처리'), '배치 처리 절');
  await expectVisible(page, page.getByText('AI 탐지 추론 파라미터'), 'AI 추론 절');
  await shot(page, '002-01', 1);

  // 배치 처리 주기를 실제로 바꿔 저장한다.
  // ⚠ 지금 값과 <같은 값>을 넣으면 폼이 dirty 가 되지 않아 저장이 잠긴 채로 남는다 —
  //   반복 실행해도 늘 바뀌도록 현재값과 다른 값을 고른다.
  const before = Number(await page.locator('#batch-interval').inputValue());
  const target = before === 90 ? 120 : 90;
  await setRange(page, '#batch-interval', target);
  await expectVisible(page, page.getByText(`${target}s`), `변경값 ${target}s`);
  await cardSaveButton(page, '#batch-interval').click();
  await expectVisible(page, page.getByText(/저장|반영/), '저장 결과 안내');
  await shot(page, '002-01', 2);

  // 재진입 — 저장값이 남아 있는지
  await page.goto(`${APP}/dashboard`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  await openSettings(page);
  await expectVisible(page, page.getByText(`${target}s`), `재진입 후 ${target}s 유지`);
  await shot(page, '002-01', 3);

  // 되돌린다 — 촬영이 서버 설정을 바꾼 채로 끝나지 않게.
  await setRange(page, '#batch-interval', 60);
  const restore = cardSaveButton(page, '#batch-interval');
  await restore.waitFor({ state: 'visible', timeout: 5000 });
  await restore.click({ timeout: 10000 }).catch(() => {
    console.log('  !! 기본값 되돌리기 실패 — 배치 주기가 바뀐 채로 남았다');
  });
  await page.waitForTimeout(1500);
};

/** 외부 연동 상태 확인 — 설정 화면의 연동 헬스 */
CASES['002-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await openSettings(page);
  const card = page
    .getByText('외부 연동 상태')
    .first()
    .locator('xpath=ancestor::*[self::section or self::div][2]');
  await expectVisible(page, card, '외부 연동 상태 카드');
  // 세 연동 대상이 <실제로> 판정돼 있어야 증적이 된다 — '알 수 없음' 은 증적이 아니다.
  for (const name of ['비식별 서버', 'AI 서버', '데이터베이스']) {
    await expectVisible(page, card.getByText(name), `연동 대상 ${name}`);
  }
  await page.waitForTimeout(1200);
  await shotEl(page, card, '002-02', 1);
};

/** 작업 목록 조회와 요약 · 필터 */
CASES['013-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/task`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('작업 목록'), '작업 목록 화면');
  await expectVisible(page, page.getByText('전체 작업'), '요약 카드');
  await page.waitForTimeout(1500);
  await shot(page, '013-01', 1);

  // 이벤트 유형으로 걸러 조회 — 결과가 실제로 줄어드는지
  const evt = page.locator('select').first();
  await evt.selectOption({ label: '교통사고' });
  await page.getByRole('button', { name: '조회' }).click();
  await page.waitForTimeout(2500);
  // 안 걸려도 목록은 멀쩡해 보인다 — 결과가 실제로 좁혀졌는지 단정한다.
  const rows = page.getByRole('row').filter({ hasText: /video-/ });
  const cnt = await rows.count();
  if (cnt === 0) throw new Error('교통사고로 걸렀더니 0건이다 — 증적으로 쓸 수 없다');
  for (let i = 0; i < cnt; i += 1) {
    const txt = await rows.nth(i).innerText();
    if (!txt.includes('교통사고')) throw new Error(`필터가 안 걸렸다 — 교통사고 아닌 행: ${txt.slice(0, 50)}`);
  }
  console.log(`  교통사고 필터 결과 ${cnt}건`);
  await shot(page, '013-01', 2);
};

/** 전체 구축 현황 — 검수완료 기준과 전체 기준 병기 */
CASES['027-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/stat/overall`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('전체 구축 현황').first(), '전체 구축 현황 화면');
  await expectVisible(page, page.getByText(/검수완료 기준/).first(), '검수완료·전체 병기');
  await expectVisible(page, page.getByRole('heading', { name: '이벤트 유형 분포' }), '이벤트 분포');
  await page.waitForTimeout(2500); // 차트 렌더
  await shot(page, '027-01', 1);
};

/** 작업자별 현황 */
CASES['027-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/stat/overall`, { waitUntil: 'domcontentloaded' });
  const head = page.getByRole('heading', { name: '작업자별 현황' });
  await expectVisible(page, head, '작업자별 현황 표');
  const table = page.locator('section,div').filter({ has: head }).last();
  await shotEl(page, table, '027-02', 1);

  await page.goto(`${APP}/stat/worker`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByRole('heading', { name: '작업자 통계' }), '작업자 통계 화면');
  const combo = page.getByRole('combobox').first();
  await expectVisible(page, combo, '작업자 선택 콤보박스');
  await combo.click();
  const opt = page.getByRole('option');
  await opt.first().waitFor({ state: 'visible', timeout: 10000 });
  const n = await opt.count();
  if (n === 0) throw new Error('작업자 목록이 비어 있다');
  await opt.first().click();
  await page.waitForTimeout(3000);
  // 고르기 전 안내문이 남아 있으면 통계가 안 나온 것이다 — 그 화면은 증적이 아니다.
  await page
    .getByText('상단에서 작업자를 선택하면')
    .waitFor({ state: 'detached', timeout: 15000 })
    .catch(() => {
      throw new Error('작업자를 골랐는데 통계가 표시되지 않았다');
    });
  await shot(page, '027-02', 2);
};

/** 라벨 마스터 등록과 화면 반영 */
CASES['005-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/manage/labels`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('라벨 관리'), '라벨 관리 화면');
  await page.waitForTimeout(1200);
  await shot(page, '005-01', 1);

  // 반복 실행해도 마스터에 쌓이지 않게 — 같은 이름이 남아 있으면 화면에서 먼저 지운다.
  const name = '증적확인용라벨';
  for (let guard = 0; guard < 5; guard += 1) {
    const stale = page.getByRole('row').filter({ hasText: /증적/ });
    if ((await stale.count()) === 0) break;
    await stale.first().getByRole('button', { name: '삭제' }).click();
    await confirmModal(page, '삭제');
  }

  await page.getByRole('button', { name: /라벨 추가/ }).click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, '라벨 추가 다이얼로그');
  await dlg.getByPlaceholder('예: 사람, 차량').fill(name);
  await page.waitForTimeout(400);
  await shot(page, '005-01', 2);

  await dlg.getByRole('button', { name: /저장|등록|추가/ }).last().click();
  await dlg.first().waitFor({ state: 'detached', timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(1500);
  await expectVisible(page, page.getByText(name), `등록한 라벨(${name})이 목록에 보임`);
  await shot(page, '005-01', 3);
};


/** 프리셋 등록 — 이벤트유형에 라벨 세트를 묶는다 */
// 이 이벤트유형에는 프리셋이 없다. 1이벤트 1프리셋이라 있는 곳에 만들면 409 로 거부된다.
const PRESET_EVENT = process.env.CAPTURE_PRESET_EVENT ?? 'EV07000201';

async function openPresets(page) {
  await page.goto(`${APP}/manage/presets`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByRole('heading', { name: '프리셋 관리' }), '프리셋 관리 화면');
  await page.waitForTimeout(1500);
}

CASES['006-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await openPresets(page);

  for (let guard = 0; guard < 3; guard += 1) {
    const del = page.getByRole('button', { name: new RegExp(`${PRESET_EVENT}.*삭제`) });
    if ((await del.count()) === 0) break;
    await del.first().click();
    await confirmModal(page, '삭제');
  }
  await shot(page, '006-01', 1);

  await page.getByRole('button', { name: '프리셋 추가' }).click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, '새 프리셋 다이얼로그');
  await dlg.locator('#preset-event').click();
  const opt = page.getByRole('option').filter({ hasText: PRESET_EVENT }).first();
  await opt.waitFor({ state: 'visible', timeout: 10000 });
  await opt.click();
  await page.waitForTimeout(600);
  await dlg.locator('#preset-label-1').check();
  await dlg.locator('#preset-label-2').check();
  await shot(page, '006-01', 2);

  await dlg.getByRole('button', { name: '만들기' }).click();
  await dlg.first().waitFor({ state: 'detached', timeout: 15000 }).catch(() => {
    throw new Error('프리셋 저장이 닫히지 않았다 — 거부됐을 수 있다');
  });
  await page.waitForTimeout(2000);
  await expectVisible(
    page,
    page.getByRole('button', { name: new RegExp(`${PRESET_EVENT}.*수정`) }),
    `등록한 프리셋(${PRESET_EVENT})이 목록에 반영`,
  );
  await shot(page, '006-01', 3);
};

/** 라벨 세트 교체 — 프리셋의 라벨 구성을 바꾸면 목록에 그대로 반영된다 */
CASES['006-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await openPresets(page);
  const edit = page.getByRole('button', { name: new RegExp(`${PRESET_EVENT}.*수정`) }).first();
  await expectVisible(page, edit, `수정할 프리셋(${PRESET_EVENT})`);
  await edit.click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, '프리셋 수정 다이얼로그');
  await dlg.locator('#preset-label-3').check();
  await shot(page, '006-02', 1);
  await dlg.getByRole('button', { name: /저장|수정|만들기/ }).last().click();
  await dlg.first().waitFor({ state: 'detached', timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(2000);
  await expectVisible(page, page.getByText('3개 라벨').first(), '교체된 라벨 세트가 반영');
  await shot(page, '006-02', 2);
};

/** 공지 작성 · 발행과 열람 범위 */
CASES['028-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/notice/new`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByRole('heading', { name: '새 공지 작성' }), '공지 작성 화면');

  const title = '증적 확인용 공지';
  await page.getByLabel(/제목/).fill(title);
  await page.getByLabel(/내용/).fill('단위시험 증적 촬영으로 작성한 공지입니다.');
  await page.waitForTimeout(500);
  await shot(page, '028-01', 1);

  await page.getByRole('button', { name: '작성' }).click();
  await page.waitForTimeout(2500);
  await expectVisible(page, page.getByText(title).first(), '작성한 공지');
  await shot(page, '028-01', 2);

  // 발행 — 발행해야 작업자에게 보인다
  const pub = page.getByRole('button', { name: /발행/ }).first();
  if ((await pub.count()) > 0) {
    await pub.click();
    await page.waitForTimeout(2500);
    await shot(page, '028-01', 3);
  }

  // 작업자 눈으로 목록 확인
  await login(page, 'WORKER');
  await page.goto(`${APP}/notice`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText(title).first(), '작업자 목록에 발행 공지가 보임');
  await page.waitForTimeout(1200);
  await shot(page, '028-01', 4);
};

/** 작업 배정 · 재배정과 이력 */
CASES['013-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/task`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByRole('heading', { name: '작업 목록' }), '작업 목록');
  await page.waitForTimeout(1500);

  const row = page.getByRole('row').filter({ hasText: '미배정' }).first();
  await expectVisible(page, row, '미배정 영상 행');
  await row.getByRole('button', { name: '배정' }).click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, '배정 다이얼로그');
  await page.waitForTimeout(800);
  await shot(page, '013-02', 1);

  await dlg.locator('#assign-worker').click();
  const opts = page.getByRole('option');
  await opts.first().waitFor({ state: 'visible', timeout: 10000 });
  // ⚠ 첫 항목은 <플레이스홀더>('작업자 선택')다 — 그걸 고르면 저장이 잠긴 채로 남는다.
  const worker = opts.filter({ hasNotText: '작업자 선택' }).first();
  await expectVisible(page, worker, '배정할 작업자 항목');
  const workerName = (await worker.innerText()).trim();
  await worker.click();
  await page.waitForTimeout(800);
  console.log(`  배정 대상 작업자: ${workerName}`);

  // 작업자를 고르기 전에는 저장이 잠겨 있다 — 열렸다는 것이 곧 선택이 먹혔다는 증거다.
  await page
    .waitForFunction(() => {
      const b = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '저장');
      return !!b && !b.disabled;
    }, undefined, { timeout: 10000 })
    .catch(() => {
      throw new Error('작업자를 골랐는데 저장이 열리지 않았다');
    });
  await dlg.getByRole('button', { name: '저장' }).click();
  await dlg.first().waitFor({ state: 'detached', timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(2500);
  await shot(page, '013-02', 2);
};


/** 비식별 마스킹 옵션 설정 저장·반영 */
CASES['004-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await openSettings(page);
  const card = page
    .locator('#deident-masking-range')
    .locator('xpath=ancestor::*[.//button[normalize-space()="저장"]][1]');
  await expectVisible(page, card, '비식별 옵션 카드');
  await shotEl(page, card, '004-01', 1);

  // 지금 값과 다른 값을 고른다 — 같은 값이면 폼이 dirty 가 되지 않아 저장이 잠긴다.
  const before = Number(await page.locator('#deident-masking-range').inputValue());
  const target = before === 1.5 ? 1.2 : 1.5;
  await setRange(page, '#deident-masking-range', target);
  await cardSaveButton(page, '#deident-masking-range').click();
  await expectVisible(page, page.getByText(/저장|반영/), '저장 결과 안내');
  await page.waitForTimeout(1200);
  await shotEl(page, card, '004-01', 2);

  await openSettings(page);
  await expectVisible(page, page.getByText(`${target.toFixed(1)}배`), `재진입 후 ${target}배 유지`);
  await shotEl(page, card, '004-01', 3);
};

/** 토큰 인계 진입과 채널 · 역할 인가 */
CASES['001-01'] = async (page) => {
  // 검수자 — 관리 메뉴가 보인다
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/dashboard`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('검수자').first(), '검수자 역할 표시');
  await expectVisible(page, page.getByText('시스템 설정'), '검수자에게 관리 메뉴가 보임');
  await page.waitForTimeout(1200);
  await shot(page, '001-01', 1);

  // 작업자 — 같은 화면인데 관리 메뉴가 없다
  await login(page, 'WORKER');
  await page.goto(`${APP}/dashboard`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('작업자').first(), '작업자 역할 표시');
  await page
    .getByText('시스템 설정')
    .first()
    .waitFor({ state: 'detached', timeout: 8000 })
    .catch(async () => {
      if ((await page.getByText('시스템 설정').count()) > 0) {
        throw new Error('작업자에게 관리 메뉴가 보인다 — 인가가 갈리지 않았다');
      }
    });
  await page.waitForTimeout(1200);
  await shot(page, '001-01', 2);

  // 작업자가 관리 화면에 직접 들어가려 하면 막힌다
  await page.goto(`${APP}/manage/settings`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const blocked =
    page.url().includes('/forbidden') ||
    (await page.getByText(/권한|접근|거부/).count()) > 0;
  if (!blocked) throw new Error('작업자가 관리 화면에 들어가졌다');
  await shot(page, '001-01', 3);
};

/** 공지 첨부 업로드 · 다운로드와 삭제 */
CASES['028-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/notice`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('증적 확인용 공지').first(), '앞서 만든 공지');
  await page.getByText('증적 확인용 공지').first().click();
  await page.waitForTimeout(2000);

  const edit = page.getByRole('button', { name: /수정/ }).first();
  await expectVisible(page, edit, '수정 버튼');
  await edit.click();
  await page.waitForTimeout(2000);

  const file = page.locator('input[type="file"]').first();
  await file.waitFor({ state: 'attached', timeout: 15000 });
  // ⚠ 확장자 allowlist 가 있다(pdf·doc(x)·xls(x)·ppt(x)·png·jpg·jpeg·zip·hwp(x)).
  //   허용 밖 확장자는 400 으로 <정상 거부>되므로 증적용으로는 허용 확장자를 쓴다.
  await file.setInputFiles({
    name: '증적첨부.png',
    mimeType: 'image/png',
    buffer: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
      'base64',
    ),
  });
  await page.waitForTimeout(3000);
  await expectVisible(page, page.getByText('증적첨부.png').first(), '첨부한 파일이 목록에 보임');
  await shot(page, '028-02', 1);
};


/** 객체 목록의 '16개 객체' 같은 개수를 읽는다 — 반영됐는지 재는 잣대다. */
async function objectCount(page) {
  const txt = await page.getByText(/개 객체/).first().innerText();
  return Number(txt.replace(/[^0-9]/g, ''));
}

/** 현재 프레임 AI 탐지 실행과 반영 */
CASES['015-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_AI);

  // ★ **전후가 비교되는 화면을 만든다** (2026-09-10 사용자 지시).
  //
  // 이 프레임에는 프레임 추출 직후 배치가 이미 오토라벨을 붙여 둔다. 그대로 「AI 탐지」를 다시
  // 돌리면 같은 상자가 중복으로 억제되어 **11개 → 14개** 식으로 조금 늘 뿐이고, 두 컷을 나란히
  // 놓아도 무엇이 달라졌는지 사람 눈에 보이지 않는다 — 오토라벨의 증적으로 성립하지 않는다.
  // 그래서 **이 프레임의 라벨을 먼저 전부 지우고 저장한 뒤** 빈 캔버스를 「전」으로 찍는다.
  //
  // ⚠ 실제 작업 데이터를 지운다. 대상은 증적 촬영 전용으로 만든 영상의 프레임이어야 한다
  //   (`CAPTURE_FRAME_AI` — 시나리오 §5). 다른 사람이 작업 중인 프레임을 주면 그 작업이 사라진다.
  // ⚠ **지우고 저장까지** 해야 한다. 저장하지 않으면 서버에는 라벨이 그대로라 탐지가 중복으로
  //   억제되고, 「후」가 다시 예전 그림이 된다(지운 것이 화면에만 반영된 상태).
  const wipe = async () => {
    for (let i = 0; i < 200; i += 1) {
      const del = page.getByRole('button', { name: '객체 삭제' });
      if ((await del.count()) === 0) break;
      await del.first().click({ force: true });   // hover 로만 드러나는 버튼이라 force 로 누른다
      await page.waitForTimeout(120);
    }
  };
  await wipe();
  if ((await objectCount(page)) !== 0) throw new Error('라벨을 다 지우지 못했다 — 「전」이 빈 캔버스가 아니다');
  await page.getByRole('button', { name: /^저장/ }).first().click({ timeout: 20000 });
  await expectVisible(page, page.getByText('저장됨'), '라벨 삭제 저장');
  await page.waitForTimeout(1200);

  const before = await objectCount(page);
  console.log(`  실행 전 객체 ${before}개 (라벨을 모두 지운 상태)`);
  await shot(page, '015-01', 1);

  await page.getByRole('button', { name: 'AI 탐지' }).click();
  await page.waitForTimeout(1500);
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, 'AI 탐지 창');
  // 라벨을 고르지 않으면 매핑된 전체 라벨이 대상이다. 실행 방식은 '일반'(현재 프레임).
  await dlg.getByRole('button', { name: '일반' }).click();

  // 탐지는 서버를 다녀온다 — 진행 표시가 풀릴 때까지 기다린다.
  //
  // ⚠ 사양은 *"검출이 없으면 0건으로 정상 처리된다"* 이므로(D11 015-01 예상결과 2) **증가 자체를 성공
  //   조건으로 삼지 않는다.** 다만 이 케이스는 라벨을 지우고 시작하므로 중복 억제가 일어나지
  //   않고, 대상이 있는 프레임이라면 실제로 붙는다 — 안 붙으면 아래에서 빈 캔버스로 걸린다.
  //   (구 동작에서는 배치가 붙여 둔 라벨 때문에 12개 → 12개가 나왔고 그것도 정상이었다.)
  await page
    .getByRole('dialog')
    .waitFor({ state: 'detached', timeout: 90000 })
    .catch(() => {});
  await page.waitForTimeout(3000);
  const after = await objectCount(page);
  console.log(`  실행 후 객체 ${after}개 (증가 ${after - before})`);
  // ★증적의 요건은 **사람·차에 라벨이 실제로 그려진 화면**이다. 빈 캔버스를 오토라벨 증적으로
  //   남기지 않는다 — 그것이 이 케이스가 증명해야 하는 바로 그것이다.
  if (after < 1) {
    throw new Error('캔버스에 라벨이 하나도 없다 — 오토라벨 결과가 보이는 화면이 아니다');
  }
  await page.waitForTimeout(1500);
  await shot(page, '015-01', 2);
};

/** 탐지 대상 분류 지정과 게이팅 */
CASES['015-02'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_AI);
  await page.getByRole('button', { name: 'AI 탐지' }).click();
  await page.waitForTimeout(1800);
  const dlg = page.getByRole('dialog');
  if ((await dlg.count()) === 0) {
    throw new Error('AI 탐지에 분류를 고르는 창이 없다 — 게이팅을 보일 자리가 없다');
  }
  await expectVisible(page, dlg, '탐지 분류 선택 창');
  await shotEl(page, dlg, '015-02', 1);
};

/** 구간 자동 추적 실행과 결과 반영 */
CASES['018-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_MAIN);
  const panel = page
    .getByText('AI 자동 추적')
    .first()
    .locator('xpath=ancestor::*[.//button[contains(normalize-space(),"AI 자동 추적")]][1]');
  await expectVisible(page, panel, 'AI 자동 추적 패널');
  await shotEl(page, panel, '018-01', 1);

  await page.getByRole('button', { name: 'AI 자동 추적' }).last().click();
  await page.waitForTimeout(3000);
  await shot(page, '018-01', 2);
};


/**
 * 비식별 신고 해소 창구.
 *
 * ★**성공 경로를 이 도구로 만들 수 있다** (2026-09-09 · CO-20260909 · ADR-027 v4).
 *   해소 자격의 판정은 **무결성 하나**이며, 신고 이전부터 있던 산출물(지금 쓰고 있는 비식별
 *   영상 포함)도 고를 수 있다.
 *
 * ⚠ **구 서술 폐기** — *"해소는 <신고 이후에 만들어진> 재비식별 산출물을 골라야 성립하는데 그
 *   산출물을 만드는 경로가 저작도구에 없어 성공 경로를 만들 수 없다"*. 그 시간 조건이 바로
 *   이번에 걷어낸 것이다. 되살려 「창구와 전제조건 가드까지만」으로 되돌리지 말 것.
 */
CASES['010-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/manage/deident-reports`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByRole('heading', { name: /비식별 신고/ }), '비식별 신고 관리');
  await page.waitForTimeout(1500);

  let btn = page.getByRole('button', { name: /해소/ }).first();
  if ((await btn.count()) === 0) {
    console.log('  열린 신고가 없어 먼저 신고를 접수한다');
    await CASES['010-01'](page);
    await login(page, 'REVIEWER');
    await page.goto(`${APP}/manage/deident-reports`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    btn = page.getByRole('button', { name: /해소/ }).first();
  }
  await expectVisible(page, btn, '해소 버튼');
  await shot(page, '010-02', 1);

  await btn.click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, '재비식별 산출물 선택 창');
  await page.waitForTimeout(1200);
  await shotEl(page, dlg, '010-02', 2);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);
};

/** 검수 반려 처리(사유 · 이슈 생성) */
CASES['019-02'] = async (page) => {
  // 반려하려면 검수중 건이 있어야 한다 — 작업자가 먼저 제출한다.
  await login(page, 'WORKER');
  await openLabel(page, FRAME_MAIN);
  await submitForReview(page);

  await login(page, 'REVIEWER');
  await page.goto(`${APP}/review/${REVIEW_ID}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const reject = page.getByRole('button', { name: '반려' }).first();
  await expectVisible(page, reject, '반려 버튼');
  await shot(page, '019-02', 1);

  await reject.click();
  const dlg = page.getByRole('dialog');
  await expectVisible(page, dlg, '반려 사유 입력창');
  await dlg.getByRole('textbox').first().fill('경계 상자가 대상 밖으로 벗어나 있어 재작업이 필요합니다.');
  await page.waitForTimeout(600);
  await shot(page, '019-02', 2);

  await dlg.getByRole('button', { name: /반려|확인/ }).last().click();
  await page.waitForTimeout(3000);
  await page.goto(`${APP}/review/${REVIEW_ID}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await shot(page, '019-02', 3);
};

/** 검출 분류 매핑과 열람 권한 */
CASES['005-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await page.goto(`${APP}/manage/labels`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByRole('heading', { name: '라벨 관리' }), '라벨 관리 화면');
  await page.waitForTimeout(1500);
  // 라벨마다 AI 탐지 매핑이 붙어 있고, 안 붙은 것은 오토라벨 대상이 아니다.
  await expectVisible(page, page.getByText('AI 탐지 매핑').first(), '검출 분류 매핑 표시');
  // ★검출 분류 매핑이 이 케이스의 대상이다 — 화면 전체를 찍으면 005-01(라벨 마스터 등록)과
  //   같은 그림이 된다.
  await shotEl(page, page.getByRole('table').first(), '005-02', 1);

  // 작업자는 라벨 관리에 들어갈 수 없다.
  await login(page, 'WORKER');
  await page.goto(`${APP}/manage/labels`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const blocked = page.url().includes('/forbidden') || (await page.getByText(/권한|접근|거부/).count()) > 0;
  if (!blocked) throw new Error('작업자가 라벨 관리에 들어가졌다');
  // ⚠ 거부 화면 자체는 어느 자원을 막았든 **같은 그림**이라 001-01(토큰 인계 인가)의 컷과
  //   바이트까지 같아진다(실측 2026-09-09). 열람 권한의 증거로 더 나은 것은 **작업자 메뉴에
  //   「라벨 관리」가 아예 없다**는 사실이다 — 그 자리를 찍는다.
  // ⚠ 거부 화면에는 좌측 메뉴가 없다 — 작업자 화면으로 돌아가 그 메뉴를 찍는다.
  await page.goto(`${APP}/`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const nav = page.getByRole('navigation', { name: '좌측 메뉴' });
  await expectVisible(page, nav, '작업자 좌측 메뉴');
  if ((await nav.getByText('라벨 관리').count()) > 0) {
    throw new Error('작업자 메뉴에 라벨 관리가 보인다');
  }
  await shotEl(page, nav, '005-02', 2);
};


/** 증강 이력에서 「활용 결정 대기」인 결과 화면을 연다. */
async function openAugmentResultPendingDecision(page) {
  await page.goto(`${APP}/augment`, { waitUntil: 'domcontentloaded' });
  await expectVisible(page, page.getByText('데이터 증강 요청'), '증강 요청 화면');
  await page.waitForTimeout(2500);
  const cards = page.getByRole('button').filter({ hasText: /처리 종료/ });
  const n = await cards.count();
  if (n === 0) throw new Error('증강 이력 카드가 없다');
  for (let i = 0; i < n; i += 1) {
    await cards.nth(i).click();
    await page.waitForTimeout(3000);
    // ⚠ 「활용 결정 대기」 글자만 보고 고르면 안 된다 — **해상도 파생(RESL_*)은 내부 생성물이라
    //   채택·반려가 차단**돼 있어 대기로 보이면서 버튼이 없다. 실제로 결정할 수 있는 카드,
    //   즉 채택 버튼이 있는 카드를 찾는다(실측 2026-09-09: 대기 3건이 전부 해상도 파생이었다).
    const accept = await page.getByRole('button', { name: '채택' }).count();
    const reject = await page.getByRole('button', { name: '반려' }).count();
    const waiting = await page.getByText('활용 결정 대기').count();
    console.log(`  카드 ${i + 1}/${n}: 채택 ${accept} · 반려 ${reject} · 결정대기 ${waiting}`);
    if (accept > 0 || reject > 0) return;
    await page.goto(`${APP}/augment`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2000);
  }
  throw new Error(
    '채택·반려를 고를 수 있는 증강 결과가 없다 — 활용 결정 대기가 있어도 해상도 파생(RESL_*)은 ' +
      '결정 대상이 아니다. 외부 증강 결과를 새로 만들어야 한다',
  );
}

/** 증강 결과 채택 처리 */
CASES['025-01'] = async (page) => {
  await login(page, 'REVIEWER');
  await openAugmentResultPendingDecision(page);
  await expectVisible(page, page.getByRole('button', { name: '채택' }), '채택 버튼');
  // ★결정 버튼은 화면 아래에 있다 — 그냥 찍으면 **이 케이스가 증명할 대상이 빠진 그림**이 된다.
  await shotAt(page, page.getByRole('button', { name: '채택' }), '025-01', 1);

  // ⚠ 한 요청에 결과가 여럿이면 결정 대기도 여럿이다. 「글자가 사라졌는가」로 보면 나머지 대기가
  //   남아 있어 **정상 처리인데 실패로 잡힌다**(실측 2026-09-09). 「하나 줄었는가」로 본다.
  const waiting = page.getByText('활용 결정 대기');
  const before = await waiting.count();
  await page.getByRole('button', { name: '채택' }).first().click();
  await page.waitForTimeout(1500);
  const back = page.locator('[data-testid=modal-backdrop]');
  if ((await back.count()) > 0) await confirmModal(page, /채택|확인/);
  await page.waitForTimeout(3000);
  await page
    .waitForFunction(
      ([n]) => document.body.innerText.split('활용 결정 대기').length - 1 < n,
      [before],
      { timeout: 20000 },
    )
    .catch(() => {
      throw new Error(`채택했는데 활용 결정 대기가 ${before}건 그대로다`);
    });
  await shot(page, '025-01', 2);
};

/** 증강 결과 거부 처리(사유 저장) */
CASES['025-02'] = async (page) => {
  await login(page, 'REVIEWER');
  await openAugmentResultPendingDecision(page);
  await expectVisible(page, page.getByRole('button', { name: '반려' }), '반려 버튼');
  await page.getByRole('button', { name: '반려' }).first().scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  await page.getByRole('button', { name: '반려' }).first().click();
  await page.waitForTimeout(1500);

  // 사유 입력이 있으면 채운다 — 사유 저장이 이 케이스의 요점이다.
  const box = page.getByRole('dialog').getByRole('textbox').first();
  if ((await box.count()) > 0) {
    await box.fill('증강 결과의 대상 객체가 원본과 어긋나 학습데이터로 쓰기 어렵습니다.');
    await page.waitForTimeout(500);
    await shot(page, '025-02', 1);
    await page.getByRole('dialog').getByRole('button', { name: /반려|확인/ }).last().click();
  } else {
    await shot(page, '025-02', 1);
    const back = page.locator('[data-testid=modal-backdrop]');
    if ((await back.count()) > 0) await confirmModal(page, /반려|확인/);
  }
  await page.waitForTimeout(3000);
  await page
    .getByText('활용 결정 대기')
    .waitFor({ state: 'detached', timeout: 20000 })
    .catch(() => {
      throw new Error('반려했는데 활용 결정 대기가 그대로다');
    });
  await shot(page, '025-02', 2);
};


/**
 * 자동 추적으로 라벨이 자동 생성된다.
 *
 * ⚠ 세 가지를 빠뜨리면 아무 일도 안 일어난 것처럼 보인다:
 *   ① 추적 모드 기본값이 「검토 후 수락」이라 그대로 돌리면 작업본에 붙지 않는다 → 「자동 반영」
 *   ② 붙어도 <저장해야 확정된다>
 *   ③ **이미 라벨이 있는 자리에는 겹쳐서 반영하지 않는다** — 화면이 「겹쳐 반영하지 않았습니다」로
 *      알린다. 반대로 대상이 드문 프레임에서는 검출 자체가 0이라 「반영할 검출이 없습니다」가 뜬다.
 *      둘 다 정상 동작이며, 증적은 <실제로 몇 건이 올라갔는가>로 잡는다.
 */
CASES['017-01'] = async (page) => {
  await login(page, 'WORKER');
  await openLabel(page, FRAME_TRACK);
  const before = await objectCount(page);
  console.log(`  실행 전 ${before}개`);
  await shot(page, '017-01', 1);

  const auto = page.getByRole('radio', { name: '자동 반영' });
  await expectVisible(page, auto, '자동 반영 모드');
  await auto.check();
  await page.waitForTimeout(600);

  await page.getByRole('button', { name: 'AI 자동 추적' }).last().click();
  const panel = page.getByText('AI 자동 추적 결과');
  await panel.first().waitFor({ state: 'visible', timeout: 120000 }).catch(() => {
    throw new Error('AI 자동 추적 결과 패널이 뜨지 않았다 — 실행 자체가 안 된 것이다');
  });
  const msg = await panel
    .first()
    .locator('xpath=ancestor::*[self::div or self::section][1]')
    .innerText()
    .catch(() => '');
  console.log(`  결과: ${msg.replace(/\n+/g, ' / ').slice(0, 160)}`);
  if (!/올렸습니다/.test(msg)) {
    throw new Error(`작업본에 올린 것이 없다 — ${msg.replace(/\n+/g, ' / ').slice(0, 160)}`);
  }
  // ⚠ 현재 프레임의 객체 수로 단정하지 않는다 — 추적은 <뒤따르는 프레임>에도 붙어서, 현재 프레임
  //   수가 그대로여도 실제로는 올라간 것이다(실측: 「10건 올렸습니다」인데 현재 프레임은 불변).
  //   확정 근거는 결과 패널이 말하는 건수다.
  const applied = Number((msg.match(/검출\s*(\d+)\s*건을 작업본에 올렸습니다/) || [])[1] || 0);
  if (applied === 0) throw new Error(`올린 건수를 읽지 못했다 — ${msg.slice(0, 120)}`);
  console.log(`  올린 건수 ${applied} · 현재 프레임 객체 ${await objectCount(page)}개`);
  await page.waitForTimeout(1200);
  await shot(page, '017-01', 2);

  await page.getByRole('button', { name: /^저장/ }).first().click();
  await expectVisible(page, page.getByText('저장됨'), '추적 결과 저장');
  await page.waitForTimeout(2000);
  await shot(page, '017-01', 3);
};

/**
 * 본인 배정 영상 이전 버전 롤백 복원.
 *
 * ⚠ 되돌리는 행위의 이름은 「롤백」이 아니라 **「이 버전으로 시작」**이고, 그것은 화면에만 올리는
 *   것이라 <저장>을 눌러야 확정된다 — 두 단계가 한 벌이다.
 * ⚠ 승인 버전이 둘 이상이면 이 창은 라벨링 화면 진입 시 <자동으로> 뜬다. 그래서 여기서는
 *   openLabel 을 쓰지 않는다(그쪽은 이 창을 닫아 버린다).
 */
CASES['021-02'] = async (page) => {
  await login(page, 'WORKER');
  await page.goto(`${APP}/label/${FRAME_MAIN}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(4000);

  let dlg = page.locator('[data-testid=modal-backdrop]');
  if ((await dlg.count()) === 0) {
    await page.getByRole('button', { name: '버전' }).click();
    await page.waitForTimeout(1800);
    dlg = page.locator('[data-testid=modal-backdrop]');
  }
  await expectVisible(page, page.getByText('시작 버전 선택'), '시작 버전 선택 창');

  // 최신이 아닌 이전 버전을 고른다 — 그것이 롤백이다.
  const older = page.getByRole('radio').filter({ hasNot: page.getByText('최신') });
  const radios = page.getByRole('radio');
  const n = await radios.count();
  if (n < 2) throw new Error(`승인 버전이 ${n}개뿐이라 이전 버전으로 되돌릴 수 없다`);
  await radios.nth(n - 1).check(); // 목록은 최신이 위 — 마지막이 가장 오래된 버전이다
  await page.waitForTimeout(800);
  await shot(page, '021-02', 1);

  await page.getByRole('button', { name: '이 버전으로 시작' }).click();
  await page.waitForTimeout(3000);
  // ⚠ 창은 <일부러> 남는다 — 불러오기는 화면에만 올린 것이라 「아직 저장되지 않았습니다」를 알린다.
  //   결함이 아니다. 그 안내가 곧 이 단계의 증거다.
  await expectVisible(page, page.getByText(/아직 저장되지 않았습니다/), '불러오기 안내');
  await shot(page, '021-02', 2);

  // 확정하려면 창을 닫고 저장한다 — 두 단계가 한 벌이다.
  await page.getByRole('button', { name: '닫기' }).first().click();
  await dlg.first().waitFor({ state: 'detached', timeout: 15000 });
  await page.waitForTimeout(1200);
  await page.getByRole('button', { name: /^저장/ }).first().click({ timeout: 20000 });
  await expectVisible(page, page.getByText('저장됨'), '복원 결과 확정');
  await page.waitForTimeout(1500);
  await shot(page, '021-02', 3);
};


/** 라벨을 실제로 바꾸고 저장한다 — 되돌릴 것·통지할 것을 만드는 준비 동작. */
async function modifyAndSave(page, srcSn) {
  await openLabel(page, srcSn);
  const before = await objectCount(page);
  await pickTool(page, '바운딩 박스', '자동차');
  await dragOnCanvas(page, 500, 250, 660, 400);
  await page.getByRole('button', { name: /^저장/ }).first().click();
  await expectVisible(page, page.getByText('저장됨'), '작업본 저장');
  await page.waitForTimeout(2000);
  const after = await objectCount(page);
  if (after <= before) throw new Error(`라벨이 늘지 않았다 (${before} → ${after}) — 수정이 안 된 것이다`);
  console.log(`  라벨 ${before} → ${after}`);
}

/** 작업자가 검수 제출 → 검수자가 승인. 반복하면 두 번째부터가 <재승인>이다. */
async function submitAndApprove(page, srcSn, reviewId) {
  await login(page, 'WORKER');
  await openLabel(page, srcSn);
  const submit = page.getByRole('button', { name: /검수제출|재검수 제출/ }).first();
  await expectVisible(page, submit, '검수 제출 버튼');
  await submit.click();
  const c = page.getByRole('dialog');
  if ((await c.count()) > 0) await c.getByRole('button', { name: /제출|확인/ }).last().click();
  await page.waitForTimeout(3000);

  await login(page, 'REVIEWER');
  await page.goto(`${APP}/review/${reviewId}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const ok = page.getByRole('button', { name: '승인' }).first();
  await expectVisible(page, ok, '승인 버튼');
  await ok.click();
  const d = page.getByRole('dialog');
  if ((await d.count()) > 0) await d.getByRole('button', { name: /승인|확인/ }).last().click();
  await page.waitForTimeout(4000);
}

/**
 * 준비 — 수정 통지(TASK_MODIFIED)를 실제로 만든다.
 *
 * 수정 통지는 <승인된 뒤 내용을 고치고 다시 승인해야> 나간다. 한 번의 승인만으로는 완료 통지뿐이라
 * 「이벤트별 분기」를 보일 자리가 없다.
 */
CASES['prep-modified-notify'] = async (page) => {
  console.log('  승인 뒤 다시 수정 → 재제출 → 재승인 (수정 통지)');
  await login(page, 'WORKER');
  await modifyAndSave(page, FRAME_MAIN);
  await submitAndApprove(page, FRAME_MAIN, REVIEW_ID);
  console.log('  완료 — 통지 원장을 확인한다');
};


/** 포털 채널 진입 — 그 채널의 dev 로그인은 포털 사용자만 발급한다. */
async function portalLogin(page) {
  if (!PORTAL_APP) {
    throw new Error(
      '포털 채널 앱 주소가 없다(CAPTURE_PORTAL_APP). 포털 화면은 관제 채널 산출물에 존재하지 않는다',
    );
  }
  await page.goto(`${PORTAL_APP}/dev/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('#dev-login-role-PORTAL_USER').check();
  await page.getByRole('button', { name: /토큰 발급/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('/dev/login'), { timeout: 20000 });
  await page.waitForTimeout(2500);
}

async function openPortal(page, route) {
  await page.goto(`${PORTAL_APP}${route}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);
  // 인증이 끊기면 화면이 「인증이 필요합니다」만 그린다 — 그 화면을 증적으로 남기지 않는다.
  if ((await page.getByText('인증이 필요합니다').count()) > 0) {
    throw new Error(`포털 인증이 끊겼다 (${route})`);
  }
}

/** 데이터마트 영상 라벨 · 메타 수정과 본인 저장 */
CASES['029-01'] = async (page) => {
  await portalLogin(page);
  await openPortal(page, '/portal');
  await expectVisible(page, page.getByText('내 저장 작업'), '포털 내 작업 화면');
  await shot(page, '029-01', 1);

  const go = page.getByRole('button', { name: /이어서 작업/ }).first();
  const link = page.getByRole('link', { name: /이어서 작업/ }).first();
  if ((await go.count()) > 0) await go.click();
  else if ((await link.count()) > 0) await link.click();
  else throw new Error('이어서 작업할 대상이 없다');
  await page.waitForTimeout(4000);
  await expectVisible(page, page.getByText(/그리기 도구|라벨/), '포털 라벨링 화면');
  await shot(page, '029-01', 2);
};

/** 본인 작업 데이터 내려받기와 보존기간 */
CASES['029-02'] = async (page) => {
  await portalLogin(page);
  await openPortal(page, '/portal');
  // 보존기간은 만료 예정일로 화면에 드러난다 — 그것이 이 케이스의 요점이다.
  await expectVisible(page, page.getByText(/만료/), '만료 예정일 표시');
  await expectVisible(page, page.getByRole('button', { name: '내려받기' }), '내려받기');
  // ★보존기간(만료 예정일)과 내려받기가 이 케이스의 대상이다 — 화면 전체를 찍으면
  //   029-01(라벨·메타 수정)과 같은 그림이 된다.
  // ⚠ 「만료」 문자열의 첫 일치는 표의 **스크린리더 전용 캡션**(sr-only)이라 보이지 않는다 —
  //   그걸 겨냥하면 스크롤이 30초 타임아웃으로 죽는다(실측 2026-09-09). 표 자체를 찍는다.
  await shotEl(page, page.getByRole('table').first(), '029-02', 1);
};

/** 영상 업로드와 마킹 · 프레임 추출 */
CASES['030-01'] = async (page) => {
  await portalLogin(page);
  await openPortal(page, '/portal/uploads');
  await expectVisible(page, page.getByText('영상 업로드'), '포털 업로드 화면');
  await expectVisible(page, page.getByText(/mp4\/mov\/avi/), '허용 형식 안내');
  await shot(page, '030-01', 1);

  // 업로드된 자산이 프레임 추출까지 끝났음을 목록이 말한다.
  await expectVisible(page, page.getByText('준비 완료'), '준비 완료 자산');
  await expectVisible(page, page.getByText(/프레임 \d+건/), '추출된 프레임 수');
  // ★추출 결과가 이 컷의 대상이다 — 같은 페이지를 두 번 통째로 찍어 cut1 과 **바이트까지
  //   같은 그림**이 되어 있었다(실측 2026-09-09).
  await shotEl(
    page,
    page.getByText(/프레임 \d+건/).first().locator('xpath=ancestor::*[self::li or self::tr or self::article][1]'),
    '030-01',
    2,
  );
};

/** 수동 라벨링과 본인 데이터 내려받기 */
CASES['030-02'] = async (page) => {
  await portalLogin(page);
  await openPortal(page, '/portal/uploads');
  const label = page.getByRole('button', { name: '라벨링' }).first();
  const labelLink = page.getByRole('link', { name: '라벨링' }).first();
  if ((await label.count()) > 0) await label.click();
  else if ((await labelLink.count()) > 0) await labelLink.click();
  else throw new Error('라벨링으로 갈 자산이 없다');
  await page.waitForTimeout(4500);
  await expectVisible(page, page.getByText(/그리기 도구|라벨/), '포털 업로드 라벨링 화면');

  // ★이 케이스는 「사각형·다각형을 그려 저장」을 증명한다 — 화면만 열고 찍으면 **빈 캔버스**가
  //   증적이 되고, 실제로 029-01(라벨·메타 수정)과 바이트까지 같은 그림이었다(실측 2026-09-09).
  //   포털 도구바도 내부와 같은 aria-label 을 쓴다(선택·이동·바운딩 박스·폴리곤).
  await page.getByRole('button', { name: '바운딩 박스' }).click();
  await page.waitForTimeout(400);
  await dragOnCanvas(page, 330, 430, 520, 610);

  // 폴리곤 — 점을 찍고 첫 점을 다시 눌러 닫는다.
  await page.getByRole('button', { name: '폴리곤' }).click();
  await page.waitForTimeout(400);
  const poly = [
    [600, 420],
    [760, 440],
    [780, 600],
    [610, 590],
  ];
  for (const [x, y] of poly) {
    await page.mouse.click(x, y);
    await page.waitForTimeout(250);
  }
  await page.mouse.click(poly[0][0], poly[0][1]); // 닫기
  await page.waitForTimeout(1200);

  await page.getByRole('button', { name: /^저장/ }).first().click();
  await page.waitForTimeout(2500);
  // 저장된 라벨이 실제로 캔버스에 남아 있어야 증적이다 — 다시 열어 확인한다.
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(4000);
  await shot(page, '030-02', 1);

  await openPortal(page, '/portal/uploads');
  await expectVisible(page, page.getByRole('button', { name: /내보내기\(JSON\)/ }), '본인 데이터 내보내기');
  await expectVisible(page, page.getByRole('button', { name: /원본 다운로드/ }), '원본 다운로드');
  // ★내려받기 수단이 이 컷의 대상이다 — 페이지 전체를 찍으면 030-01-cut1(업로드 화면)과
  //   **바이트까지 같은 그림**이 된다(같은 페이지다 — 실측 2026-09-09).
  //   ⚠ 행 전체를 자르면 030-01-cut2(같은 행의 프레임 수)와 또 같아진다 — **버튼 묶음만** 자른다.
  await shotEl(
    page,
    page.getByRole('button', { name: /내보내기\(JSON\)/ }).first().locator('xpath=..'),
    '030-02',
    2,
  );
};

/**
 * 다른 케이스의 컷을 그대로 증적으로 쓰는 자리.
 *
 * 같은 순간·같은 화면이라 두 번 찍을 것이 없다. 파일 이름만으로는 알 수 없으므로 여기 선언한다.
 * ⚠ 원장은 <컷 수>로 세지 <파일 수>로 세지 않는다 — 공유분 때문에 둘이 다르다.
 */
const SHARED = {
  '026-01': ['026-02-cut2'],
  '020-01': ['019-01-cut2'],
};

/** 찍힌 파일을 훑어 증적 원장(capture-manifest.json)을 만든다. */
function writeManifest() {
  // ★ `p2`·`p3` 은 한 화면에 안 들어가 나눠 찍은 <같은 컷의 뒷장>이다(2026-09-09).
  //   이 정규식이 그것을 모르면 뒷장이 원장에서 통째로 빠지는데, 파일은 있으니 조용하다.
  const files = readdirSync(OUT).filter((f) => /^KLID-AT-UT-\d{3}-\d{2}-cut\d+(p\d+)?\.png$/.test(f));
  const man = {};
  for (const f of files.sort()) {
    const m = f.match(/^(KLID-AT-UT-\d{3}-\d{2})-cut(\d+)(?:p(\d+))?\.png$/);
    (man[m[1]] ??= []).push({ n: Number(m[2]), p: Number(m[3] ?? 1), src: `/captures/${f}` });
  }
  for (const k of Object.keys(man)) {
    man[k] = man[k].sort((a, b) => a.n - b.n || a.p - b.p).map((x) => x.src);
  }
  /** 공유 컷 참조 해석 — 그 컷이 나눠 찍혔으면 뒷장까지 펴서 돌려준다. */
  const resolveShared = (c) => {
    const one = `KLID-AT-UT-${c}.png`;
    if (existsSync(path.join(OUT, one))) return [`/captures/${one}`];
    const parts = files
      .filter((f) => f.startsWith(`KLID-AT-UT-${c}p`))
      .sort((a, b) => Number(a.match(/p(\d+)\.png$/)[1]) - Number(b.match(/p(\d+)\.png$/)[1]));
    return parts.map((f) => `/captures/${f}`);
  };
  for (const [caseId, cuts] of Object.entries(SHARED)) {
    const id = `KLID-AT-UT-${caseId}`;
    const srcs = cuts.flatMap(resolveShared);
    const missing = cuts.filter((c) => resolveShared(c).length === 0).map((c) => `KLID-AT-UT-${c}.png`);
    if (missing.length) {
      console.log(`!! 공유 컷 없음 ${id}: ${missing.join(' ')}`);
      continue;
    }
    man[id] = srcs;
  }

  /**
   * 컷마다 캡션을 얹는다 — 단위시험 결과서(I2)의 「비고」가 이 값을 그대로 싣는다.
   *
   * 캡션이 있는 케이스만 `{src, caption}` 객체가 되고 나머지는 문자열 그대로다(생성기가 둘 다
   * 받는다). **캡션이 없다고 컷을 빼지 않는다** — 빼면 그림이 조용히 사라진다.
   *
   * ⚠ 컷과 캡션의 개수가 어긋나면 남는 컷은 캡션 없이 실린다. 그것을 아래에서 **이름으로**
   *   열거한다 — 이 고지가 캡션 표와 실제 촬영이 갈라졌음을 알아채는 유일한 자리다.
   */
  const noCaption = [];
  for (const [id, srcs] of Object.entries(man)) {
    const caps = CAPTIONS[id.replace('KLID-AT-UT-', '')] || [];
    man[id] = srcs.map((src, i) => {
      if (!caps[i]) { noCaption.push(`${id} 컷 ${i + 1}/${srcs.length}`); return src; }
      return { src, caption: caps[i] };
    });
  }
  const out = path.join(path.dirname(OUT), 'capture-manifest.json');
  writeFileSync(out, JSON.stringify(man, null, 2) + '\n', 'utf-8');
  const cuts = Object.values(man).reduce((a, v) => a + v.length, 0);
  console.log(`\n원장 기록 ${out}`);
  console.log(`  케이스 ${Object.keys(man).length} · 컷 ${cuts} · 파일 ${files.length} · 캡션 ${cuts - noCaption.length}/${cuts}`);
  if (noCaption.length) {
    // 「몇 건」이 아니라 **어느 컷인지**를 말한다 — 숫자만으로는 capture-captions.mjs 의 어느
    // 줄을 고쳐야 할지 알 수 없고, 그러면 이 고지를 보고도 아무도 고치지 않는다.
    console.log(`  !! 캡션 없는 컷 ${noCaption.length}건 — ${noCaption.join(' / ')}`);
    console.log('     capture-captions.mjs 의 그 케이스에 컷 순서대로 캡션을 넣는다.');
  }
  auditManifest(man, files);
  return man;
}

/**
 * 원장을 세 축으로 훑는다 — **눈으로는 못 잡는 것들이다.**
 *
 * ① 빈 케이스   컷이 하나도 없는 케이스
 * ② 규격        화면 컷이 정확히 1920x1080 인가(요소 캡처·쿼리 렌더는 제외)
 * ③ ★중복      서로 다른 케이스가 **바이트까지 같은 그림**을 갖는가
 *
 * ③ 이 이 검사의 값어치다. 2026-09-09 에 해시 대조가 **7건**을 잡았고 그중 하나는 「수동
 * 라벨링」 증적이 **빈 캔버스**인 건이었다 — 그림을 다 열어 봐도 눈으로는 안 잡힌다.
 * 의도된 공유는 둘뿐이며(같은 순간·같은 화면을 두 케이스가 나눠 쓴다) 그 밖은 전부
 * 「그 케이스가 자기 대상을 겨냥하지 않았다」는 신호다.
 */
function auditManifest(man, files) {
  const SHARED_OK = [
    ['KLID-AT-UT-026-01-cut1.png', 'KLID-AT-UT-026-02-cut2.png'],
    ['KLID-AT-UT-020-01-cut1.png', 'KLID-AT-UT-019-01-cut2.png'],
  ].map((v) => v.slice().sort().join('|'));
  const QUERY_CASES = new Set(['024-01', '020-03', '022-01', '008-01']);

  const empty = Object.entries(man)
    .filter(([, v]) => v.length === 0)
    .map(([k]) => k);
  const byHash = new Map();
  const offSpec = [];
  for (const f of files) {
    const buf = readFileSync(path.join(OUT, f));
    const key = createHash('sha256').update(buf).digest('hex');
    byHash.set(key, [...(byHash.get(key) ?? []), f]);
    // PNG IHDR — 시그니처(8) + 길이(4) + 'IHDR'(4) 뒤에 width·height 가 온다.
    if (buf.length > 24 && buf.readUInt32BE(0) === 0x89504e47) {
      const w = buf.readUInt32BE(16);
      const h = buf.readUInt32BE(20);
      const cid = f.replace('KLID-AT-UT-', '').replace(/-cut.*/, '');
      const isQuery = QUERY_CASES.has(cid);
      const isFull = w === VIEWPORT.width && h === VIEWPORT.height;
      const isElement = w <= VIEWPORT.width && h <= VIEWPORT.height;
      if (!isQuery && !isFull && !isElement) offSpec.push(`${f} ${w}x${h}`);
    }
  }
  const dup = [...byHash.values()]
    .filter((v) => v.length > 1 && !SHARED_OK.includes(v.slice().sort().join('|')))
    .map((v) => v.join(' = '));

  if (empty.length + offSpec.length + dup.length === 0) {
    console.log('  검사 통과 — 빈 케이스 0 · 규격 밖 0 · 의도치 않은 중복 0');
    return;
  }
  if (empty.length) console.log(`  !! 빈 케이스 ${empty.length}건: ${empty.join(' ')}`);
  if (offSpec.length) console.log(`  !! 규격 밖 ${offSpec.length}건: ${offSpec.join(', ')}`);
  if (dup.length) {
    console.log(`  !! 의도치 않은 중복 ${dup.length}건 — 그 케이스가 자기 대상을 겨냥하지 않았다:`);
    for (const d of dup) console.log(`       ${d}`);
  }
}

const argv = process.argv.slice(2);
if (argv.includes('--list')) {
  console.log(Object.keys(CASES).sort().join(' '));
  process.exit(0);
}
if (argv.includes('--manifest-only')) {
  writeManifest();
  process.exit(0);
}
// `prep-*` 는 촬영이 아니라 <사전 준비> 항목이라 --all 에서 뺀다. 필요하면 이름을 직접 준다.
const ids = argv.includes('--all')
  ? Object.keys(CASES).filter((k) => !k.startsWith('prep-')).sort()
  : argv.filter((a) => !a.startsWith('--'));
if (ids.length === 0) {
  console.log('사용: node capture-evidence.mjs [--all | --list | --manifest-only | <케이스ID...>]');
  console.log('케이스:', Object.keys(CASES).sort().join(' '));
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

writeManifest();
console.log(`\n== 실행 ${ids.length}건 · 실패 ${failed}건`);
process.exit(failed === 0 ? 0 : 1);
