/**
 * 마킹 화면 진입 자리 회귀 가드. [@design SCREEN-033] [@design SCREEN-045] [@design NAV-002]
 *
 * 이 파일이 고정하는 계약:
 *  1. 마킹 진입은 **마킹 대기 상태인 영상 자산 행에만** 둔다 — 그 자산에서 할 수 없는 액션은
 *     비활성으로 두지 않고 아예 노출하지 않는다(라벨링 링크와 같은 규칙).
 *  2. 「업로드됨」이 아니라 **「마킹 대기」**로 표기한다 — 이 자리에서 알려야 할 것은 업로드가
 *     끝났다는 사실이 아니라 다음에 무엇을 해야 하는가이다.
 *  3. **라벨링 화면에는 마킹으로 되돌아가는 자리를 두지 않는다** — 라벨링 중이라는 것은 이미
 *     추출이 끝났다는 뜻이고 그 시점의 재마킹은 제공하지 않으므로, 두면 눌러 봐야 거절되는
 *     자리가 되어 회복 경로를 잘못 안내한다.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import {
  PORTAL_UPLOAD_MARKING_ROUTE,
  buildPortalUploadMarkingPath,
} from '@/features/portal/uploads/markingPath';
import { renderWithProviders } from '@/test/renderWithProviders';

import { PortalUploadPage } from '../PortalUploadPage';

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function row(uldSn: number, uldSttsCd: string, uldTypeCd = 'VIDEO') {
  return {
    uldSn,
    uldTypeCd,
    orgnlFileNm: `clip-${uldSn}.mp4`,
    fileSz: 2048,
    mimeTypeNm: 'video/mp4',
    uldSttsCd,
    frmeCnt: null,
    frmeSn: null,
    expiresAt: null,
    regDt: '2026-09-02T00:00:00',
  };
}

function page(content: unknown[]) {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 20,
  };
}

describe('내 업로드 목록 — 마킹 진입', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  async function renderList(rows: unknown[]) {
    mock.onGet('/portal/uploads').reply(200, ok(page(rows)));
    renderWithProviders(<PortalUploadPage />);
    await screen.findByTestId(`portal-upload-item-${(rows[0] as { uldSn: number }).uldSn}`);
  }

  it('★마킹_대기_영상_행에만_마킹_진입을_둔다', async () => {
    await renderList([row(1, 'UPLOADED')]);

    const item = screen.getByTestId('portal-upload-item-1');
    const link = within(item).getByRole('link', { name: 'clip-1.mp4 마킹' });
    // 주소 조립은 한 곳이 한다 — 문자열을 화면에 흩지 않는다.
    expect(link).toHaveAttribute('href', buildPortalUploadMarkingPath(1));
  });

  it('★마킹_대기가_표기다 — 「업로드됨」이 아니다', async () => {
    await renderList([row(1, 'UPLOADED')]);

    expect(within(screen.getByTestId('portal-upload-item-1')).getByText('마킹 대기')).toBeInTheDocument();
    expect(screen.queryByText('업로드됨')).toBeNull();
  });

  it.each([['PROCESSING'], ['READY'], ['FAILED']])(
    '★%s 행에는 마킹 진입을 두지 않는다 — 눌러 봐야 거절되는 자리가 된다',
    async (status) => {
      await renderList([row(1, status)]);

      const item = screen.getByTestId('portal-upload-item-1');
      expect(within(item).queryByRole('link', { name: /마킹/ })).toBeNull();
    },
  );

  it('★영상이_아닌_자산에는_두지_않는다 — 이벤트 구간이라는 개념이 없다', async () => {
    await renderList([row(1, 'UPLOADED', 'IMAGE')]);

    const item = screen.getByTestId('portal-upload-item-1');
    expect(within(item).queryByRole('link', { name: /마킹/ })).toBeNull();
  });
});

/**
 * 라벨링 화면에 마킹 진입이 없다는 것은 **렌더로는 확인하기 어렵다** — 없는 것을 찾는 단언은
 * 화면이 통째로 그려지지 않아도 통과하기 때문이다. 그래서 화면 소스가 그 진입 주소를 조립하는
 * 함수를 참조하는지 직접 본다. 대상 파일이 없으면 던진다(대상이 없어서 위반 0 건이 통과로
 * 읽히지 않게 한다).
 */
describe('라벨링 화면 — 마킹 진입을 두지 않는다', () => {
  /*
   * ⚠ 업로드 갈래 전용 화면(`PortalUploadLabelingView`)은 폐기되고 관제용 라벨링 도구로
   *   흡수됐다 — 그 자리를 **실제 본문**인 `pages/label/LabelingPage` 가 이어받는다.
   *   폐기된 파일명을 그대로 두면 `readFileSync` 가 던져 이 가드가 통째로 죽는다.
   */
  const LABELING_SOURCES = [
    'src/pages/portal/PortalLabelingPage.tsx',
    'src/pages/label/LabelingPage.tsx',
  ];

  it.each(LABELING_SOURCES)('%s 가 마킹 진입 주소를 조립하지 않는다', (rel) => {
    const abs = path.resolve(process.cwd(), rel);
    const source = readFileSync(abs, 'utf-8');

    expect(source.length).toBeGreaterThan(0);
    expect(source).not.toContain('buildPortalUploadMarkingPath');
    expect(source).not.toContain('/marking');
  });
});

/**
 * 라우터는 경로 문자열을 **직접** 들고 있다 — 포털 전용 모듈을 그 파일에서 import 하면 죽은
 * 가지에 있어도 모듈 순서가 밀려 관제 채널 산출물의 압축 결과가 바뀌기 때문이다(형제 경로
 * `uploads/:uldSn/label` 도 같은 이유로 문자열이다).
 *
 * ★ 그 대가로 <b>같은 경로가 두 곳에 적힌다</b>. 여기서 그 둘이 어긋나지 않는지를 확인한다 —
 *   어긋나면 목록의 「마킹」이 라우터에 없는 주소로 보내 「찾을 수 없음」이 뜨는데, 그 어긋남은
 *   컴파일에도 화면 시험에도 걸리지 않는다.
 * ★ 시험이 스스로 라우트 트리를 조립하지 않고 **프로덕션 라우터를 그대로 읽는다** — 조립하면
 *   지키려던 배선을 읽지 않는 예시가 된다.
 */
describe('마킹 경로 — 라우터와 진입 주소 조립기가 어긋나지 않는다', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('★프로덕션 포털 라우터에 그 경로가 실제로 등록돼 있다', async () => {
    vi.resetModules();
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    const mod = await import('@/router');

    const portalTree = mod.router.routes.find((r) => r.path === '/portal');
    if (!portalTree) throw new Error('포털 라우트 트리를 찾지 못했다 — 이 시험의 대상이 없다.');
    const childPaths = (portalTree.children ?? []).map((c) => c.path);

    expect(childPaths).toContain(PORTAL_UPLOAD_MARKING_ROUTE);
  });

  it('★진입 주소가 그 경로 패턴에 자산 식별자를 채운 값과 같다', () => {
    const filled = `/portal/${PORTAL_UPLOAD_MARKING_ROUTE.replace(':uldSn', '42')}`;

    expect(buildPortalUploadMarkingPath(42)).toBe(filled);
  });
});
