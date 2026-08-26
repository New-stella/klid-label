/**
 * 접근성 감사 <b>검사기 자기검증</b> — 스캐너가 실제로 위반을 잡는지 증명한다.
 *
 * <p><b>왜 필요한가</b>: 감사 스펙은 "위반 0건"일 때 통과한다. 그래서 스캐너가 조용히
 * 아무것도 못 잡게 되면(선택자 변경·필터 실수·예외 삼킴) 감사는 계속 초록으로 보이면서
 * 실제로는 아무것도 지키지 않는다. 이 스펙은 <b>결과가 이미 알려진 합성 DOM</b>을 만들어
 * {@link ../fixtures/a11y-audit.ts} 의 스캐너를 그대로 돌린다 — 복제본이 아니라 같은 코드다.
 *
 * <p>기대 대비값은 WCAG 2.x 상대휘도 공식으로 미리 계산해 주석에 적었다. 반투명 합성 케이스는
 * 합성 <b>원값</b>(반올림 전)으로 계산한 값이다 — 스캐너도 그렇게 계산하고 hex 는 표기용으로만
 * 반올림한다.
 *
 * <p>BE·인증이 필요 없다({@link Page.setContent} 로 DOM 을 직접 만든다).
 */

import { test, expect } from '@playwright/test';

import { scanAlt, scanContrast } from '../fixtures/a11y-audit';
import { CONTRAST_ALLOWLIST, RATIO_TOLERANCE, findContrastAllowance } from '../fixtures/a11y-baseline';

/**
 * 대비 검사기 합성 케이스.
 *
 * <p>계산된 대비: `#999999`/흰=2.85 · `#a0a0a0`/흰=2.61 · `#8a8a8a`/흰=3.45 ·
 * 흰/`#808080`=3.95 · `#b3b3b3`/흰=2.10 · 검정/흰=21.0
 */
const CONTRAST_FIXTURE = `
<style>
  body { margin: 0; background: #ffffff; font-family: sans-serif; }
  .block { display: block; padding: 4px; margin: 0; }
  .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; }
</style>
<p id="fail-normal" class="block" style="color:#999999; font-size:16px;">일반 텍스트 미달 2.85</p>
<p id="pass-normal" class="block" style="color:#000000; font-size:16px;">일반 텍스트 통과 21.0</p>
<p id="fail-large" class="block" style="color:#a0a0a0; font-size:28px;">큰 글자 미달 2.61</p>
<p id="pass-large" class="block" style="color:#8a8a8a; font-size:28px;">큰 글자 통과 3.45</p>
<p id="skip-disabled" class="block" aria-disabled="true" style="color:#dddddd; font-size:16px;">비활성 예외</p>
<p id="skip-sronly" class="sr" style="color:#eeeeee; font-size:16px;">숨김 텍스트</p>
<div style="background:#ffffff;">
  <div style="background:rgba(0,0,0,0.5);">
    <p id="fail-alpha-bg" class="block" style="color:#ffffff; font-size:16px;">반투명 배경 합성 3.95</p>
  </div>
</div>
<p id="fail-alpha-fg" class="block" style="color:rgba(0,0,0,0.3); font-size:16px;">반투명 전경 합성 2.10</p>
<div style="background-image:linear-gradient(#000000,#ffffff);">
  <p id="skip-gradient" class="block" style="color:#111111; font-size:16px;">그라데이션 배경 제외</p>
</div>
`;

const ALT_FIXTURE = `
<img id="img-no-alt" src="/assets/no-alt.png">
<img id="img-empty-alt" src="/assets/deco.png" alt="">
<img id="img-hidden" src="/assets/hidden.png" alt style="display:none">
<div aria-hidden="true"><img id="img-aria-hidden" src="/assets/ah.png"></div>
<button id="btn-no-name"><svg width="16" height="16"></svg></button>
<button id="btn-aria-label" aria-label="닫기"><svg width="16" height="16"></svg></button>
<button id="btn-sr-text"><span style="position:absolute;width:1px;height:1px;overflow:hidden">저장</span></button>
<button id="btn-svg-title"><svg width="16" height="16"><title>도움말</title></svg></button>
<button id="btn-img-alt"><img src="/assets/icon.png" alt="검색"></button>
<a id="a-broken-labelledby" href="#" aria-labelledby="존재하지-않는-id"></a>
<a id="a-with-text" href="#">목록으로</a>
`;

test.describe('접근성 검사기 자기검증 — 명도 대비 스캐너', () => {
  test('미달_요소를_실제로_잡고_예외_대상은_잡지_않는다', async ({ page }) => {
    await page.setContent(CONTRAST_FIXTURE);
    const { scanned, findings } = await scanContrast(page);

    // 판정 대상에서 빠진 셋(비활성·1px 숨김·그라데이션 배경)을 뺀 6건만 판정한다.
    expect(scanned, '스캔 요소 수').toBe(6);

    const byId = new Map(findings.map((f) => [f.selector.split('.')[0], f]));
    expect([...byId.keys()].sort(), '위반으로 잡힌 요소').toEqual(
      ['p#fail-alpha-bg', 'p#fail-alpha-fg', 'p#fail-large', 'p#fail-normal'].sort(),
    );

    // 일반 텍스트 기준(4.5:1)과 측정치
    expect(byId.get('p#fail-normal')).toMatchObject({
      fg: '#999999',
      bg: '#ffffff',
      ratio: 2.85,
      need: 4.5,
    });
    // 큰 글자 기준(3:1)이 실제로 적용됐다 — 같은 색이 16px 였다면 pass-large 도 위반이다.
    expect(byId.get('p#fail-large')).toMatchObject({ ratio: 2.61, need: 3, fontSize: '28px' });
    // 반투명 배경을 조상과 합성해 실효 배경을 얻었다(합성이 없으면 흰색으로 읽혀 미탐).
    // ★대비는 합성 원값(127.5)으로 계산하고 hex 는 표기용으로만 반올림한다 —
    //   그래서 3.98 이지 반올림한 #808080(128) 기준 3.95 가 아니다. 정밀도 손실이 없는 쪽이 맞다.
    expect(byId.get('p#fail-alpha-bg')).toMatchObject({
      fg: '#ffffff',
      bg: '#808080',
      ratio: 3.98,
    });
    // 반투명 전경도 배경에 합성한다 — rgba(0,0,0,0.3) on white → 원값 178.5(표기 #b3b3b3).
    expect(byId.get('p#fail-alpha-fg')).toMatchObject({
      fg: '#b3b3b3',
      bg: '#ffffff',
      ratio: 2.11,
    });
  });

  test('위반이_없는_화면은_0건이지만_스캔_수는_0이_아니다', async ({ page }) => {
    await page.setContent(
      '<body style="background:#ffffff"><p style="color:#000000">검정 텍스트</p></body>',
    );
    const { scanned, findings } = await scanContrast(page);

    expect(findings, '위반').toEqual([]);
    // 조용한 그린 차단의 근거 — "위반 0건"과 "아무것도 안 봄"은 다르다.
    expect(scanned, '스캔 요소 수').toBeGreaterThan(0);
  });

  test('빈_화면은_스캔_수_0으로_드러난다', async ({ page }) => {
    await page.setContent('<body></body>');
    const { scanned, findings } = await scanContrast(page);

    expect(findings).toEqual([]);
    // 감사 스펙은 이 0을 라우트 실패로 승격시킨다(zeroScanRoutes).
    expect(scanned, '빈 화면의 스캔 요소 수').toBe(0);
  });
});

test.describe('접근성 검사기 자기검증 — 대체 텍스트 스캐너', () => {
  test('alt_누락과_이름_없는_컨트롤을_잡고_정상_요소는_잡지_않는다', async ({ page }) => {
    await page.setContent(ALT_FIXTURE);
    const { scanned, findings } = await scanAlt(page);

    // 보이는 img 3(btn-img-alt 안의 아이콘 포함) + button 5 + a 2 = 10
    // (display:none·aria-hidden 서브트리는 제외)
    expect(scanned, '스캔 요소 수').toBe(10);

    expect(
      findings.map((f) => `${f.kind}|${f.selector}`).sort(),
      '위반으로 잡힌 요소',
    ).toEqual(['img-alt|img#img-no-alt', 'no-name|a#a-broken-labelledby', 'no-name|button#btn-no-name'].sort());
  });

  test('aria_labelledby_는_속성_존재가_아니라_가리키는_텍스트로_판정한다', async ({ page }) => {
    await page.setContent(
      '<span id="label-node">닫기</span><button id="ok" aria-labelledby="label-node"></button>' +
        '<button id="ng" aria-labelledby="없는-id"></button>',
    );
    const { findings } = await scanAlt(page);

    expect(findings.map((f) => f.selector)).toEqual(['button#ng']);
  });

  test('빈_화면은_스캔_수_0으로_드러난다', async ({ page }) => {
    await page.setContent('<body><p>이미지도 컨트롤도 없다</p></body>');
    const { scanned, findings } = await scanAlt(page);

    expect(findings).toEqual([]);
    expect(scanned, '빈 화면의 스캔 요소 수').toBe(0);
  });
});

/**
 * `openRoute` 는 "로딩 표시자가 사라질 때까지" 기다린다. 그런데 그 로케이터가 아무것도
 * 못 찾으면 `toHaveCount(0)` 이 <b>즉시 통과</b>해 대기가 통째로 무력화된다(스스로는 초록으로
 * 보이는 사각). 로케이터가 실제 Spinner 계약(role=status + 접근 이름)을 집는지 못박아 둔다.
 *
 * <p>계약 출처: `src/components/common/Spinner.tsx` + 라우터의 Suspense fallback(label="페이지 로딩").
 */
test.describe('접근성 감사 진입 — 로딩 대기가 무력화되지 않는지', () => {
  const SPINNER_MARKUP =
    '<div role="status" aria-live="polite" aria-label="페이지 로딩">' +
    '<span class="sr-only">페이지 로딩</span></div>';

  test('로딩_표시자_로케이터가_실제_Spinner_를_집는다', async ({ page }) => {
    await page.setContent(SPINNER_MARKUP);

    await expect(
      page.getByRole('status', { name: '페이지 로딩' }),
      '이 값이 0이면 openRoute 의 로딩 대기가 무동작이다',
    ).toHaveCount(1);
  });

  test('이름이_다르면_집지_않는다', async ({ page }) => {
    await page.setContent(SPINNER_MARKUP.replace('페이지 로딩', '다른 로딩'));

    await expect(page.getByRole('status', { name: '페이지 로딩' })).toHaveCount(0);
  });
});

test.describe('접근성 감사 허용목록 — 범위가 좁은지 확인', () => {
  test('기록된_조합과_대비는_허용된다', () => {
    for (const allowance of CONTRAST_ALLOWLIST) {
      expect(
        findContrastAllowance({ fg: allowance.fg, bg: allowance.bg, ratio: allowance.measured }),
        `${allowance.fg} on ${allowance.bg}`,
      ).toBeDefined();
      expect(allowance.reason.length, '허용 근거가 비어 있으면 장부 구실을 못 한다').toBeGreaterThan(
        10,
      );
    }
  });

  test('같은_조합이라도_대비가_더_나빠지면_허용하지_않는다', () => {
    const [first] = CONTRAST_ALLOWLIST;
    expect(
      findContrastAllowance({
        fg: first.fg,
        bg: first.bg,
        ratio: first.measured - RATIO_TOLERANCE - 0.01,
      }),
      '팔레트가 바뀌어 더 나빠진 조합은 신규 위반이다',
    ).toBeUndefined();
  });

  test('다른_배경_위의_같은_전경색은_허용하지_않는다', () => {
    const [first] = CONTRAST_ALLOWLIST;
    expect(
      findContrastAllowance({ fg: first.fg, bg: '#f4f5f6', ratio: 1.5 }),
      '허용목록이 색 조합 단위로 좁게 잡혔는지',
    ).toBeUndefined();
  });
});
