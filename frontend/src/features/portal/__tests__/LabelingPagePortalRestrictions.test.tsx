// 포털 채널 라벨링 화면 — 미노출 보장 회귀 테스트.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const labelsPayload = {
  success: true,
  data: {
    frameNo: 1,
    srcSn: 555,
    videoId: 5,
    siblings: [{ srcSn: 555, frameNo: 1 }],
    labels: [],
  },
  message: null,
  errorCode: null,
};

describe('포털 채널 라벨링 미노출', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    // R16 — 포털 모드는 포털 전용 엔드포인트만 호출
    mock.onGet('/portal/frames/555/labels').reply(200, labelsPayload);
    mock.onGet('/portal/frames/555/image').reply(200, new Blob([new Uint8Array([1])]));
    // ★포털 전용 메타 창구(API-234) — 메타 탭 본문이 이것만 부른다.
    mock.onGet('/portal/frames/555/meta').reply(200, {
      success: true,
      data: {
        rawSn: 5,
        srcSn: 555,
        items: [],
        readOnlyMeta: [],
        technicalMeta: [],
      },
      message: null,
      errorCode: null,
    });
    mock.onGet('/portal/videos/5/event-annotation').reply(200, {
      success: true,
      data: { rawSn: 5, annotation: null, overridden: false },
      message: null,
      errorCode: null,
    });
    // 포털 전용 저장 창구(API-235·API-237) — <b>쓰기 경로까지</b> 지나가려면 등록해야 한다.
    mock.onPut('/portal/frames/555/meta').reply(200, {
      success: true,
      data: { rawSn: 5, srcSn: 555, items: [], readOnlyMeta: [], technicalMeta: [] },
      message: null,
      errorCode: null,
    });
    mock.onPut('/portal/videos/5/event-annotation').reply(200, {
      success: true,
      data: { rawSn: 5, annotation: { event_class: '화재' }, overridden: true },
      message: null,
      errorCode: null,
    });
    // ⚠ 내부 메타 창구는 <b>등록하지 않는다</b> — 불리면 mock 이 404 를 내므로 「안 불렀다」와
    //   「불렀는데 실패했다」가 구분되도록 호출 이력으로 직접 단언한다(아래 테스트).
  });

  /** 내부(비-포털) 메타 창구 호출 이력 — 포털 채널에서는 하나도 없어야 한다. */
  function internalMetaCalls() {
    return mock.history.get
      .concat(mock.history.put)
      .map((r) => r.url ?? '')
      .filter((url) => /^\/(videos|frames)\//.test(url));
  }

  /** 포털 전용 창구로 나간 저장 요청 주소. 「저장이 아예 안 나갔다」를 공허한 통과로 만들지 않는다. */
  function portalPutUrls() {
    return mock.history.put.map((r) => r.url ?? '');
  }

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function renderPortalLabel() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/555'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('포털_라벨링_화면_검수제출_버튼_미렌더', async () => {
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    expect(screen.queryByTestId('submit-review-button')).toBeNull();
    expect(screen.queryByRole('button', { name: '검수제출' })).toBeNull();
  });

  /*
   * ★구 케이스 「포털_라벨링_화면_VLM_메타_탭_미노출」은 <b>폐기</b>됐다 — 그 단언(포털에 메타 탭이
   *   서지 않는다)이 확정 사양과 반대다. 미제공의 축은 <b>외부 시계열 분석 서버로 나가는 위탁
   *   연동(호출·콜백)</b>이지 그 결과물의 표시·수정이 아니다(ADR-013 v10 · SCREEN-029). 되살리지 말 것.
   *
   *   대신 그 자리가 실제로 지켜야 하는 축을 단언한다 — <b>포털 메타 탭이 내부 창구를 부르지
   *   않는다</b>. 내부 패널을 그대로 재사용하면 원장 컬럼 쓰기·재검토 표시·관제 통지·동결본
   *   재동결이 일어나 「원본·데이터마트를 수정하지 않는다(단방향)」가 깨지는데, 화면만 보면
   *   똑같이 그려져 눈으로는 구분되지 않는다.
   */
  it('포털_메타_탭은_포털_전용_창구만_부른다_내부창구_호출_0건', async () => {
    const user = userEvent.setup();
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    const metaTab = await screen.findByTestId('right-tab-meta');
    await user.click(metaTab);

    // 포털 전용 본문이 서고,
    expect(await screen.findByTestId('portal-work-meta-tab')).toBeInTheDocument();
    // 비동기 조회가 모두 끝난 뒤에도
    await waitFor(() => expect(screen.getByTestId('portal-meta-panel')).toBeInTheDocument());
    await new Promise((resolve) => setTimeout(resolve, 100));
    // 내부 메타 창구(/videos/**·/frames/**)는 <b>한 번도</b> 불리지 않는다.
    expect(internalMetaCalls()).toEqual([]);
  });

  /*
   * ★★<b>쓰기 절반</b>의 가드다. 위 케이스는 탭을 열어 <b>조회만</b> 하므로 저장 경로를 지나가지
   *   않는다 — 저장 두 창구의 주소를 실재하는 내부 경로(`PUT /frames/{srcSn}/meta` ·
   *   `PUT /videos/{rawSn}/event-annotation`)로 바꾸는 변이가 <b>포털 시험 전건을 통과했다</b>.
   *   그런데 원장 컬럼 쓰기·재검토 표시·관제 통지·동결본 재동결이 발화하는 것은 정확히 그 쓰기
   *   절반이다. 「단방향」 불변의 가드가 읽기 절반에만 서 있으면 안 된다.
   * ★판정은 <b>값</b>으로 한다 — mock 미등록으로 인한 404 는 「안 불렀다」와 구분되지 않으므로
   *   호출 이력을 직접 꺼내 단언하고, 포털 창구로 실제로 나갔다는 것도 함께 본다(공허한 통과 방지).
   */
  it('포털_메타_탭의_저장도_포털_전용_창구만_부른다_내부창구_호출_0건', async () => {
    const user = userEvent.setup();
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await user.click(await screen.findByTestId('right-tab-meta'));
    await screen.findByTestId('portal-work-meta-tab');

    // ① 메타 저장 — 시계열 메타를 새로 더해 저장 버튼을 살린다.
    const slot = await screen.findByTestId('portal-meta-row-video:manual-timeseries');
    await user.type(within(slot).getByRole('textbox'), '내가 쓴 시계열 서술');
    await user.click(screen.getByRole('button', { name: '메타 저장' }));
    // ⚠ 주소가 아니라 <b>건수</b>로 기다린다 — 주소로 기다리면 잘못된 주소로 나간 변이에서 여기서
    //   먼저 죽어, 정작 이 케이스가 지키는 「내부 창구 0건」 단언이 실행되지 않는다.
    await waitFor(() => expect(mock.history.put).toHaveLength(1));

    // ② 이벤트 어노테이션 저장 — 이벤트 분류가 비면 저장이 잠기므로 채운다.
    await user.type(screen.getByLabelText('이벤트 분류'), '화재');
    await user.click(screen.getByRole('button', { name: '이벤트 어노테이션 저장' }));
    await waitFor(() => expect(mock.history.put).toHaveLength(2));

    // 두 저장이 모두 나간 뒤에도 내부 창구는 <b>한 번도</b> 불리지 않는다.
    expect(internalMetaCalls()).toEqual([]);
    // 그리고 두 저장이 <b>포털 전용 창구로</b> 실제로 나갔다(공허한 통과 방지).
    expect(portalPutUrls()).toEqual([
      '/portal/frames/555/meta',
      '/portal/videos/5/event-annotation',
    ]);
  });

  // 포털 라벨링 화면(SCREEN-029)은 AI 보조 세 기능(AI 탐지·AI 분할·AI 자동 추적)을 포털 전용 창구로
  // 제공한다(2026-09-15 확정). 여기서 계속 막는 것은 스켈레톤·선택 객체 AI 추적이다.
  // ★반전 — 구 케이스 「포털_라벨링_도구바에_SAM2_분할_추적_스켈레톤이_노출되지_않는다」 의
  //   AI 분할·AI 탐지 숨김 단언만 뒤집었다. 스켈레톤·AI 추적 숨김과 과잉 차단 가드는 그대로다.
  it('포털_라벨링_도구바에_AI보조_세_버튼이_서고_스켈레톤_AI추적은_노출되지_않는다', async () => {
    renderPortalLabel();
    // 로딩 화면도 labeling-page testid 를 갖는다 → 도구바가 실제 렌더될 때까지(바운딩박스 버튼) 대기.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument(),
    );
    const aiGroup = screen.getByTestId('label-toolbar-ai-group');
    expect(within(aiGroup).getByRole('button', { name: 'AI 탐지' })).toBeInTheDocument();
    expect(within(aiGroup).getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
    expect(
      within(aiGroup).getByRole('button', { name: 'AI 자동 추적 패널로 이동' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 추적' })).toBeNull();
    expect(screen.queryByRole('button', { name: '스켈레톤' })).toBeNull();
    // 기본 도구(바운딩박스/폴리곤)는 그대로 노출 — 과잉 차단 회귀 가드.
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '폴리곤' })).toBeInTheDocument();
  });
});
