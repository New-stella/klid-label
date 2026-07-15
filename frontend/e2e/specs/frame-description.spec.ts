import { expect, test, type Page, type Route } from '@playwright/test';

import { LabelingPage } from '../pages/LabelingPage';

/**
 * FrameDescriptionPanel(프레임 설명 입력 패널) E2E — 실제 브라우저 렌더/동작 검증.
 *
 * <p>검증 성격(Critical): 본 스펙은 **API 를 page.route() 로 모킹**한다. BE(Spring Boot)를
 * 기동하지 않고 FE dev server(vite, 5174) 만으로 실행하므로, "실제 BE 연동" 이 아니라
 * "FE 화면 동작(렌더·바인딩·저장 요청·에러·XSS 방어)" 을 검증한다. 계약(GET/PUT
 * /v1/frames/{srcSn}/description → ApiResponse) 준수 여부는 여기서 담보하지 않는다.
 *
 * <p>인증: 관제/포털이 발급하는 JWT 는 FE 에서 서명 검증 없이 payload 만 base64url 디코드해
 * claims 를 추출한다(useAuthStore.decodeJwtPayload — 무결성 검증은 BE 책임). 따라서 BE 없이도
 * 유효 payload 를 담은 토큰을 ingress 로 주입하면 화면 진입이 가능하다. 실제 시크릿/계정 미사용.
 */

const FRAME_SN = 501;
const API = '**/api/v1';

/** BE 없이 화면 진입용 테스트 JWT (payload 만 유효, 서명은 검증 안 됨). */
function fakeInternalWorkerJwt(): string {
  const enc = (obj: object) => Buffer.from(JSON.stringify(obj)).toString('base64url');
  const header = enc({ alg: 'HS256', typ: 'JWT' });
  const payload = enc({
    sub: '1003',
    role: 'WORKER',
    channel: 'INTERNAL',
    name: '작업자',
    exp: Math.floor(Date.now() / 1000) + 3600,
  });
  return `${header}.${payload}.sig`;
}

function apiResponse<T>(data: T) {
  return JSON.stringify({ success: true, data, message: null, errorCode: null });
}

/** 1x1 투명 PNG (프레임 이미지 API mock 용). */
const PNG_1x1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

/**
 * 라벨링 화면 진입에 필요한 공통 API 를 모킹한다.
 * - 프레임 라벨(useLabels): 패널이 뜨려면 data.srcSn 이 필요.
 * - 프레임 이미지(useImageBlob): 캔버스용, 패널과 무관하지만 콘솔 노이즈 최소화.
 * - 그 외 /api/v1 호출: 빈 ApiResponse 폴백(대시보드/시계열 등).
 * videoId 는 응답에서 생략 — 이슈 스레드/검수 상태 부가 조회를 발생시키지 않아 mock 표면 축소.
 */
async function mockLabelingShell(page: Page) {
  // 폴백(가장 먼저 등록 → 이후 구체 라우트가 우선).
  await page.route(`${API}/**`, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiResponse(null),
    }),
  );

  await page.route(`${API}/frames/*/labels*`, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiResponse({
        srcSn: FRAME_SN,
        frameNo: 1,
        siblings: [{ srcSn: FRAME_SN, frameNo: 1 }],
        labels: [],
      }),
    }),
  );

  await page.route(`${API}/frames/*/image*`, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1x1 }),
  );
}

/** ingress 로 토큰 주입 후 라벨링 화면 진입. */
async function enterLabeling(page: Page): Promise<LabelingPage> {
  await page.goto(`/ingress?token=${fakeInternalWorkerJwt()}`);
  // ingress 는 토큰 디코드 후 채널 메인(/dashboard)으로 SPA navigate — ingress 를 벗어날 때까지 대기.
  await page.waitForURL((url) => !url.pathname.startsWith('/ingress'));

  const labeling = new LabelingPage(page);
  await labeling.goto(FRAME_SN);
  // 패널은 프레임 라벨 로드 후 렌더 — textarea 노출 대기.
  await expect(labeling.frameDescriptionTextarea).toBeVisible();
  return labeling;
}

test.describe('프레임 설명 입력 패널 (FrameDescriptionPanel)', () => {
  test('프레임_선택시_기존_설명이_표시된다', async ({ page }) => {
    await mockLabelingShell(page);
    // GET 이 기존 설명을 반환하면 textarea 에 표시되어야 한다.
    await page.route(`${API}/frames/*/description`, (route: Route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiResponse({ srcSn: FRAME_SN, description: '보행자가 횡단보도를 건넌다' }),
        });
      }
      return route.fallback();
    });

    const labeling = await enterLabeling(page);

    await expect(labeling.frameDescriptionTextarea).toHaveValue('보행자가 횡단보도를 건넌다');
  });

  test('설명_입력_저장시_PUT이_호출되고_반영된다', async ({ page }) => {
    await mockLabelingShell(page);

    let stored: string | null = null;
    await page.route(`${API}/frames/*/description`, async (route: Route) => {
      const req = route.request();
      if (req.method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiResponse({ srcSn: FRAME_SN, description: stored }),
        });
      }
      if (req.method() === 'PUT') {
        const body = req.postDataJSON() as { srcSn: number; description: string | null };
        stored = body.description;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiResponse({ srcSn: FRAME_SN, description: stored }),
        });
      }
      return route.fallback();
    });

    const labeling = await enterLabeling(page);
    await labeling.fillFrameDescription('차량이 정지선을 넘었다');

    const [putResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          /\/frames\/\d+\/description$/.test(r.url()) && r.request().method() === 'PUT',
      ),
      labeling.saveFrameDescription(),
    ]);

    // PUT body 가 입력값을 그대로 담아 전송됐는지 검증.
    const sent = putResponse.request().postDataJSON() as { description: string | null };
    expect(sent.description).toBe('차량이 정지선을 넘었다');

    // 저장 성공 후 재조회(GET 무효화) → 값 유지, dirty 해제로 저장 버튼 비활성.
    await expect(labeling.frameDescriptionTextarea).toHaveValue('차량이 정지선을 넘었다');
    await expect(labeling.saveFrameDescriptionBtn).toBeDisabled();
  });

  test('저장_실패시_에러가_표시된다', async ({ page }) => {
    await mockLabelingShell(page);
    await page.route(`${API}/frames/*/description`, (route: Route) => {
      const method = route.request().method();
      if (method === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiResponse({ srcSn: FRAME_SN, description: null }),
        });
      }
      if (method === 'PUT') {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({
            success: false,
            data: null,
            message: '서버 오류',
            errorCode: 'INTERNAL_ERROR',
          }),
        });
      }
      return route.fallback();
    });

    const labeling = await enterLabeling(page);
    await labeling.fillFrameDescription('저장 실패를 유발하는 설명');
    await labeling.saveFrameDescription();

    await expect(labeling.frameDescriptionError).toBeVisible();
  });

  test('script태그_입력해도_텍스트로_표시된다_XSS방어', async ({ page }) => {
    await mockLabelingShell(page);

    const payload = '<script>alert(1)</script>';
    let stored: string | null = null;
    await page.route(`${API}/frames/*/description`, (route: Route) => {
      const req = route.request();
      if (req.method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiResponse({ srcSn: FRAME_SN, description: stored }),
        });
      }
      if (req.method() === 'PUT') {
        const body = req.postDataJSON() as { description: string | null };
        stored = body.description;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiResponse({ srcSn: FRAME_SN, description: stored }),
        });
      }
      return route.fallback();
    });

    const labeling = await enterLabeling(page);
    await labeling.fillFrameDescription(payload);

    await Promise.all([
      page.waitForResponse(
        (r) =>
          /\/frames\/\d+\/description$/.test(r.url()) && r.request().method() === 'PUT',
      ),
      labeling.saveFrameDescription(),
    ]);

    // 값은 textarea value 로만 존재 (React 자동 escape).
    await expect(labeling.frameDescriptionTextarea).toHaveValue(payload);

    // DOM 에 payload 를 실행할 <script> 요소가 주입되지 않아야 한다.
    const injectedScripts = await page
      .locator('script')
      .filter({ hasText: 'alert(1)' })
      .count();
    expect(injectedScripts).toBe(0);
  });
});
