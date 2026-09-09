/**
 * 포털 라벨링 — **업로드 자산 갈래의 프레임 이동 주소**. @design SCREEN-029
 *
 * 두 출처는 `:id` 가 가리키는 것이 다르다 — 데이터마트는 `:id` 자체가 프레임이라 경로가 바뀌고,
 * 업로드 자산은 `:id` 가 **자산**이라 프레임 표기(`frame`)만 바뀐다. 이 축이 어긋나면 프레임을
 * 넘기는 순간 자산 자리에 프레임 식별자가 들어가 **있지도 않은 자산**을 조회한다.
 *
 * ★이동은 이력에 쌓지 않는다(`replace: true`) — 프레임을 열 장 넘긴 뒤 닫기를 누르면 뒤로가기가
 *  진입 이전(목록)이 아니라 이전 프레임으로 되돌아간다.
 *
 * ⚠ 이 파일만 `useNavigate` 를 스파이로 바꾼다 — 실제 라우터로는 `replace` 여부를 관측할 수 없다.
 *   같은 모듈 모의를 다른 포털 시험에 넣으면 그 파일의 라우팅이 통째로 무력화되므로 분리했다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

const navigateSpy = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigateSpy };
});

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { PortalLabelingPage } from '@/pages/portal/PortalLabelingPage';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

describe('포털 업로드 자산 — 프레임 이동 주소', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    navigateSpy.mockClear();
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    mock.onGet('/manage/labels').reply(200, ok([]));
    mock.onGet('/portal/uploads/1').reply(
      200,
      ok({
        uldSn: 1,
        uldTypeCd: 'VIDEO',
        orgnlFileNm: 'clip.mp4',
        fileSz: 1024,
        mimeTypeNm: 'video/mp4',
        uldSttsCd: 'READY',
        frmeCnt: 2,
        vdoLenSec: null,
        fps: null,
        regDt: '2026-07-17T00:00:00',
        mdfcnDt: null,
        expiresAt: null,
        frames: [
          { uldFrmeSn: 100, uldSn: 1, frmeNo: 1, regDt: '2026-07-17T00:00:00' },
          { uldFrmeSn: 101, uldSn: 1, frmeNo: 2, regDt: '2026-07-17T00:00:00' },
        ],
      }),
    );
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/labels/).reply(200, ok([]));
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onAny().reply(200, ok(null));
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.clearAllMocks();
    vi.restoreAllMocks();
  });

  it('★프레임을_옮기면_자산은_그대로_두고_프레임_표기만_바꾼다', async () => {
    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1?source=upload'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    // ⚠ 이 화면은 DOM 이 커 `getByRole`+name 이 접근성 트리 계산에 걸려 기본 대기를 넘길 수
    //   있다 — aria-label 직접 조회로 낮춘다.
    const target = await screen.findByLabelText('프레임 2');
    fireEvent.click(target);

    await waitFor(() =>
      expect(navigateSpy).toHaveBeenCalledWith('/portal/label/1?source=upload&frame=101', {
        replace: true,
      }),
    );
    // 프레임 식별자를 자산 자리에 넣지 않는다 — 있지도 않은 자산을 조회하게 된다.
    expect(navigateSpy).not.toHaveBeenCalledWith('/portal/label/101', expect.anything());
    expect(navigateSpy).not.toHaveBeenCalledWith(
      '/portal/label/101?source=upload',
      expect.anything(),
    );
    // 내부 경로로도 가지 않는다(INTERNAL 채널 가드에 걸려 접근 거부 화면이 뜬다).
    expect(navigateSpy).not.toHaveBeenCalledWith('/label/101', expect.anything());
  });

  it('★이동을_이력에_쌓지_않는다_replace', async () => {
    renderWithProviders(<PortalLabelingPage />, {
      initialEntries: ['/portal/label/1?source=upload'],
      routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
    });

    fireEvent.click(await screen.findByLabelText('프레임 2'));

    await waitFor(() => expect(navigateSpy).toHaveBeenCalled());
    const [, options] = navigateSpy.mock.calls.at(-1) as [string, { replace?: boolean }];
    expect(options).toEqual({ replace: true });
  });
});
