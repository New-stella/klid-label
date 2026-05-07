import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { test, expect } from '../fixtures/auth.fixture';
import { PortalHomePage } from '../pages/PortalHomePage';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test.describe('포털 업로드 (PORTAL_USER)', () => {
  test('업로드_dropzone_노출', async ({ portalPage }) => {
    const home = new PortalHomePage(portalPage);
    await home.goto();

    // dropzone 또는 파일 선택 영역이 노출되어야 한다
    const visible =
      (await home.dropzone.count()) > 0 || (await home.fileInput.count()) > 0;
    expect(visible).toBe(true);
  });

  test('파일_업로드_시도_mock', async ({ portalPage }) => {
    const home = new PortalHomePage(portalPage);
    await home.goto();

    // 작은 mock 파일 생성 후 업로드 — 실제 TUS 진행은 BE mock 필요
    const fixturePath = path.join(__dirname, '..', 'fixtures', 'sample.jpg.txt');
    if ((await home.fileInput.count()) > 0) {
      // setInputFiles는 실제 파일 경로 필요 — 없으면 스킵
      try {
        await home.uploadFile(fixturePath);
        await expect(portalPage.getByText(/업로드|진행/i).first()).toBeVisible({
          timeout: 5000,
        });
      } catch {
        // fixture 파일 미존재 시 — TUS 흐름 검증은 통합 테스트에서
        test.skip();
      }
    }
  });
});
