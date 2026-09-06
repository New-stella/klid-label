// R17 이슈5 — 포털 라벨 로드 거부 시 graceful 차단 화면 회귀 테스트.
// 빈 캔버스 UI 노출 대신 "접근할 수 없는 영상입니다" 안내 + 뒤로 가기 버튼이 떠야 한다.
//
// ★★403 과 404 를 <b>가르지 않는다</b>(@design SCREEN-029 @design AC-1068). 백엔드는 미노출·미승인에
//   403 을, 프레임 부재에 404 를 내므로 화면이 두 상태를 다른 문구로 나누면 그 분기가 그대로
//   <b>실재 여부 오라클</b>이 된다 — 서버가 감춘 것이 화면 층에서 풀린다. 같은 포털 화면의 메타·
//   이벤트 어노테이션 축은 이미 두 상태를 한 문구로 모으므로, 라벨 축만 가르면 한 화면 안에서
//   규칙이 갈린다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { PORTAL_WORK_ERROR_UNAVAILABLE } from '@/features/portal/work/workError';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const forbiddenBody = {
  success: false,
  data: null,
  message: '데이터마트에 노출되지 않은 영상입니다.',
  errorCode: 'FORBIDDEN',
};

/** ★서버가 싣는 문구 자체가 단서다 — 화면이 이것을 에코하면 404 가 403 과 구분된다. */
const NOT_FOUND_BODY = {
  success: false,
  data: null,
  message: '프레임을 찾을 수 없습니다.',
  errorCode: 'NOT_FOUND',
};

describe('포털 라벨 로드 403 graceful 차단', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-77', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('포털_미승인_영상_403시_접근불가_안내_화면', async () => {
    // given: 포털 라벨 로드가 403 (미승인/미노출 영상)
    mock.onGet('/portal/frames/777/labels').reply(403, forbiddenBody);
    mock.onGet('/portal/frames/777/image').reply(403, forbiddenBody);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/777'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // then: graceful 차단 화면 — 빈 캔버스(labeling-page) 대신 전용 안내 화면이 떠야 한다.
    await waitFor(() => {
      expect(screen.getByTestId('portal-forbidden-screen')).toBeInTheDocument();
    });
    expect(screen.getByText('접근할 수 없는 영상입니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '뒤로 가기' })).toBeInTheDocument();
    // 캔버스/도구바가 들어있는 본 화면은 렌더되지 않아야 한다.
    expect(screen.queryByTestId('labeling-page')).toBeNull();
  });

  /**
   * ★403·404 <b>두 상태에서 같은 화면·같은 문구</b>가 나오는 것을 값으로 단언한다.
   *
   * 두 회차가 <b>같은 상수</b>(PORTAL_WORK_ERROR_UNAVAILABLE)에 단언한다 — 그것이 「둘이 같다」를
   * 값으로 고정하는 방법이다. 회차마다 그때 화면에 뜬 문자열을 그대로 적으면 갈라져도 통과한다.
   * 문구를 다시 가르는 변이(404 를 「라벨 조회 실패」 분기로 되돌리거나 서버 메시지를 에코)는
   * 404 회차에서 죽는다.
   */
  it.each([
    ['403', forbiddenBody] as const,
    ['404', NOT_FOUND_BODY] as const,
  ])('포털_라벨_로드_%s_는_실재_여부를_가르지_않는_같은_안내다', async (label, body) => {
    const status = Number(label);
    mock.onGet('/portal/frames/777/labels').reply(status, body);
    mock.onGet('/portal/frames/777/image').reply(status, body);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/777'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 같은 화면 — 403 전용 화면이 404 에서도 그대로 선다.
    await waitFor(() => expect(screen.getByTestId('portal-forbidden-screen')).toBeInTheDocument());
    expect(screen.getByText('접근할 수 없는 영상입니다')).toBeInTheDocument();
    // 같은 문구 — 두 상태가 <b>같은 상수</b>를 쓴다.
    expect(screen.getByTestId('portal-unavailable-message')).toHaveTextContent(
      PORTAL_WORK_ERROR_UNAVAILABLE,
    );
    // ★서버 메시지 에코 금지 — 그 문장이 곧 실재 여부 단서다(CWE-209).
    expect(screen.queryByText(body.message)).toBeNull();
    // 「라벨 조회 실패」 분기로 갈라지지 않는다.
    expect(screen.queryByText('라벨 조회 실패')).toBeNull();
  });
});
