// 영상 목록 — 시계열 묶음 **일괄** 건너뛰기 / 재수행. [@design SCREEN-008] [@design ADR-050]
// [@design API-212] [@design API-214]
//
// ★ 「일괄 건너뛰기 해제」 버튼은 **폐기**됐다(구 API-213 배선). 재수행이 건너뛴 상태를 직접
//   수락하고 해제 표식까지 함께 남기므로 해제와 재수행을 두 번 돌 필요가 없다. 서버의 해제 API
//   자체는 남아 있으나 **이 화면은 부르지 않는다** — 아래에서 호출 0회를 고정한다.
//
// ★ 일괄 건너뛰기의 **접수** 대상은 그 묶음이 실패한 영상뿐이다 — 정상 영상을 미리 골라 건너뛰는
//   길은 두지 않는다(미연동 구간을 통째로 덮는 몫은 시스템 설정의 전체 건너뛰기 스위치가 맡는다).
//   ★★ 그러나 그 판정은 **서버가 건별로** 한다 — 화면은 버튼을 배치 상태로 게이팅하지 않는다.
//     시계열 위탁 실패는 파이프라인을 멈추지 않아 그 영상의 배치 상태가 완료로 남기 때문이다.
//     구 구현은 선택분 중 `status === 'FAILED'` 인 건이 있어야 버튼을 그렸고, 그래서 「실패 후 판단」
//     입구가 정확히 필요한 그 상황에서 닫혀 있었다(ADR-050). 아래 회귀 가드가 그것을 고정한다.
//
// ★ 이 세 조작의 대상 범위는 일괄 재시작과 다르다 — 재시작은 선택분 중 실패 건만 접수되지만
//   시계열 일괄 조작은 선택 전건에 적용되고 거부는 건별 사유로 돌아온다. 화면이 그 차이를
//   미리 알리는지까지 검증한다(보내고 결과를 받은 뒤에야 알게 되는 동선 방지).

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const SKIP_PATH = '/videos/batch/stages/VLM/skip';
const RERUN_PATH = '/videos/batch/stages/VLM/rerun';

function videoRow(id: number, status: string) {
  return {
    id,
    cctvName: `CCTV-${id}`,
    vmsClipId: `VMS-${id}`,
    eventName: '쓰러짐',
    eventTypeCd: 'FALL',
    frameCount: 900,
    status,
    capturedAt: '2026-05-01T12:00:00Z',
  };
}

/** 1번만 실패(FAILED), 나머지는 완료 — 재시작 대상과 시계열 대상의 범위 차이를 만든다. */
function mockVideos(mock: MockAdapter, count: number, failedIds: number[] = [1]) {
  const content = Array.from({ length: count }, (_, i) =>
    videoRow(i + 1, failedIds.includes(i + 1) ? 'FAILED' : 'COMPLETED'),
  );
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content,
      totalElements: count,
      totalPages: 1,
      number: 0,
      size: Math.max(count, 20),
    },
    message: null,
    errorCode: null,
  });
}

function okResult(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'dummy-test-token',
    claims: {
      sub: 'u-1',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('VideoListPage 시계열 일괄 조작', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setRole('REVIEWER');
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function selectFirstTwo() {
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    await user.click(screen.getByRole('checkbox', { name: 'CCTV-1 선택' }));
    await user.click(screen.getByRole('checkbox', { name: 'CCTV-2 선택' }));
    return user;
  }

  it('REVIEWER_에게_시계열_일괄_2버튼이_노출된다', async () => {
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await selectFirstTwo();

    expect(
      screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '2개 영상 시계열 일괄 재수행' })).toBeInTheDocument();
    // 오토라벨 묶음은 이 바에 두지 않는다(산출물이 라벨이라 대량 건너뛰기를 열지 않았다).
    expect(screen.queryByRole('button', { name: /오토라벨/ })).not.toBeInTheDocument();
  });

  // ★ [폐기] 「N건 시계열 일괄 건너뛰기 해제」 — 재수행이 건너뛴 상태를 직접 수락하므로 두지 않는다.
  it('★일괄_건너뛰기_해제_버튼을_두지_않는다', async () => {
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await selectFirstTwo();

    expect(screen.queryByRole('button', { name: /건너뛰기 해제/ })).not.toBeInTheDocument();
  });

  // ★ 버튼이 없다는 것만으로는 부족하다 — 다른 조작이 해제를 곁들여 부르지 않는지까지 본다.
  it('★재수행을_눌러도_해제_API_를_부르지_않는다', async () => {
    mockVideos(mock, 2);
    mock.onPost(RERUN_PATH).reply(
      200,
      okResult({
        successCount: 2,
        failureCount: 0,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: true, reason: null },
        ],
      }),
    );
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();

    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 재수행' }));

    await waitFor(() => expect(mock.history.post.some((h) => h.url === RERUN_PATH)).toBe(true));
    expect(mock.history.delete.filter((h) => h.url === SKIP_PATH)).toHaveLength(0);
  });

  // ⚠ [폐기] '★실패한_영상이_선택에_없으면_일괄_건너뛰기_버튼이_없다' (ADR-050 후속).
  //   구 동작: 선택분 중 배치 상태가 `FAILED` 인 건이 하나도 없으면 일괄 건너뛰기 버튼을 두지 않았다.
  //   폐기 사유: 그 게이팅 축(배치 상태)이 **시계열 위탁 실패를 집지 못한다** — 그 실패는 파이프라인을
  //     멈추지 않아 영상이 완주 상태로 남는다. 그래서 「실패 후 판단」 입구가 필요한 바로 그 영상에서
  //     버튼이 사라졌다. 과대 노출은 서버의 건별 거부가 보정하지만 과소 노출은 보정되지 않는다.
  //   그 자리를 대신하는 것이 아래 두 가드다.
  it('★★실패한_영상이_선택에_없어도_일괄_건너뛰기_버튼을_둔다_서버가_건별로_거른다', async () => {
    // 배치 상태로는 실패가 하나도 없는 선택 — 시계열 위탁만 실패한 영상이 정확히 이 모양이다.
    mockVideos(mock, 2, []);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await selectFirstTwo();

    expect(
      screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }),
    ).toBeInTheDocument();
    // 재수행은 종전대로 실패 여부와 무관하게 남는다(회수 동선이라 대상 축이 다르다).
    expect(screen.getByRole('button', { name: '2개 영상 시계열 일괄 재수행' })).toBeInTheDocument();
  });

  // ★ 버튼이 뜨는 것으로 끝나지 않는다 — 실제로 선택 전건이 서버로 나가야 창구가 열린 것이다.
  it('★★배치_실패가_없는_선택에서도_건너뛰기_요청이_실제로_나간다', async () => {
    mockVideos(mock, 2, []);
    mock.onPost(SKIP_PATH).reply(
      200,
      okResult({
        successCount: 0,
        failureCount: 2,
        results: [
          { rawSn: 1, success: false, reason: '건너뛸 수 있는 상태가 아닙니다.' },
          { rawSn: 2, success: false, reason: '건너뛸 수 있는 상태가 아닙니다.' },
        ],
      }),
    );
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();

    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }));
    await user.type(await screen.findByRole('textbox', { name: /건너뛰는 사유/ }), '벤더 미연동');
    await user.click(screen.getByRole('button', { name: '2건 건너뛰기' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === SKIP_PATH);
      expect(call).toBeDefined();
      // 화면이 미리 거르지 않는다 — 선택 전건을 그대로 보낸다.
      expect(JSON.parse(call!.data).rawSns).toEqual([1, 2]);
    });
  });

  it('WORKER_에게는_시계열_일괄_버튼이_노출되지_않는다', async () => {
    setRole('WORKER');
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    expect(screen.queryByRole('button', { name: /시계열 일괄/ })).not.toBeInTheDocument();
  });

  it('안내는_재시작_대상과_시계열_대상_범위를_구분해_알린다', async () => {
    // 조작마다 접수 대상이 다르다는 사실을 문구가 직접 말해야 한다.
    // ⚠ 구 단언 「배치가 실패한 1건」 → **폐기**. 재시작 대상에 선두 비식별 실패 영상(배치 상태 대기)이
    //   더해져 배치 상태로 센 숫자가 틀린 대상 수가 됐다 — 건수는 결과 창이 서버 결과로 말한다.
    mockVideos(mock, 2, [1]);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await selectFirstTwo();

    const hint = await screen.findByTestId('bulk-scope-hint');
    expect(hint).toHaveTextContent('재시작은 배치가 실패한 영상과 비식별이 실패한 영상에');
    expect(hint).toHaveTextContent('선택한 2건');
  });

  // ★ 재수행의 대상은 「건너뛴 적이 있는 묶음」이다(건너뜀·해제 모두) — 해제 버튼이 사라진 뒤
  //   사용자가 "해제부터 해야 하나"를 되짚지 않도록 안내가 그 사실을 직접 말한다.
  it('★안내가_재수행_대상은_건너뛴_적이_있는_영상임을_말한다', async () => {
    mockVideos(mock, 2, [1]);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await selectFirstTwo();

    const hint = await screen.findByTestId('bulk-scope-hint');
    expect(hint).toHaveTextContent('건너뛴 적이 있는 영상');
    // 건너뛰기의 접수 대상이 시계열 작업이 실패한 영상이라는 사실도 함께 말한다.
    expect(hint).toHaveTextContent('시계열 작업이 실패한 영상');
    // ★ 그 대상을 목록에서 모으는 수단(필터)까지 알린다 — 버튼 게이팅을 걷어낸 대가로, 무엇이
    //   접수되는지는 이 문구와 결과 모달이 전담한다.
    expect(hint).toHaveTextContent('작업 묶음 실패');
  });

  it('상한을_넘게_고르면_시계열_3버튼도_막고_보내기_전에_알린다', async () => {
    mockVideos(mock, 101, [1]);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    await user.click(screen.getByRole('checkbox', { name: '전체 선택' }));

    expect(await screen.findByTestId('bulk-retry-limit-notice')).toHaveTextContent('최대 100건');
    expect(screen.getByRole('button', { name: '101개 영상 시계열 일괄 건너뛰기' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '101개 영상 시계열 일괄 재수행' })).toBeDisabled();
    expect(mock.history.post.filter((h) => h.url === SKIP_PATH)).toHaveLength(0);
  });

  it('사유가_공백만이면_제출_버튼이_막힌다', async () => {
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }));

    const submit = await screen.findByRole('button', { name: '2건 건너뛰기' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByRole('textbox', { name: /건너뛰는 사유/ }), '   ');
    expect(screen.getByRole('button', { name: '2건 건너뛰기' })).toBeDisabled();
    expect(mock.history.post.filter((h) => h.url === SKIP_PATH)).toHaveLength(0);
  });

  it('사유_글자수는_라벨의_접근성_이름을_바꾸지_않는다', async () => {
    // 라벨 안에 글자수를 넣으면 스크린리더가 필드 이름을 타이핑할 때마다 다르게 읽는다.
    mockVideos(mock, 2);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }));

    const textarea = await screen.findByRole('textbox', { name: /건너뛰는 사유/ });
    const before = textarea.getAttribute('aria-labelledby');
    const nameBefore = screen.getByRole('textbox', { name: /건너뛰는 사유/ });
    expect(nameBefore).toBe(textarea);

    await user.type(textarea, '벤더 연동 전');
    // 글자수 표시는 갱신되지만 필드 이름은 그대로여야 한다.
    expect(screen.getByTestId('bulk-skip-reason-count')).toHaveTextContent('7 / 500');
    expect(screen.getByRole('textbox', { name: /건너뛰는 사유/ })).toBe(textarea);
    expect(textarea.getAttribute('aria-labelledby')).toBe(before);
  });

  it('사유를_적어_제출하면_묶음_경로로_전건과_같은_사유를_보낸다', async () => {
    mockVideos(mock, 2);
    mock.onPost(SKIP_PATH).reply(
      200,
      okResult({
        successCount: 1,
        failureCount: 1,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: false, reason: '이미 건너뛴 작업입니다.' },
        ],
      }),
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 건너뛰기' }));
    await user.type(
      await screen.findByRole('textbox', { name: /건너뛰는 사유/ }),
      '외부 시계열 분석 벤더 연동 전',
    );
    await user.click(screen.getByRole('button', { name: '2건 건너뛰기' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === SKIP_PATH);
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({
        rawSns: [1, 2],
        reason: '외부 시계열 분석 벤더 연동 전',
      });
    });

    // 부분 성공을 기존 결과 모달로 그대로 보여준다(제목만 조작별로 다르다).
    const result = await screen.findByTestId('bulk-retry-result');
    expect(result).toHaveTextContent('접수 1건');
    expect(within(result).getByTestId('bulk-retry-failure-2')).toHaveTextContent(
      '이미 건너뛴 작업입니다.',
    );
    expect(screen.getByRole('dialog', { name: '시계열 일괄 건너뛰기 결과' })).toBeInTheDocument();
  });

  // ★ [폐기] '건너뛰기_해제는_확인창_없이_바로_요청하고_결과를_보여준다' —
  //   해제 버튼 자체를 두지 않기로 확정되어(ADR-050) 이 동작은 화면에 없다.
  //   그 자리를 대신하는 것이 위의 '★일괄_건너뛰기_해제_버튼을_두지_않는다' 와
  //   '★재수행을_눌러도_해제_API_를_부르지_않는다' 다.

  it('재수행은_확인창_없이_바로_요청하고_결과를_보여준다', async () => {
    mockVideos(mock, 2);
    mock.onPost(RERUN_PATH).reply(
      200,
      okResult({
        successCount: 1,
        failureCount: 1,
        results: [
          { rawSn: 1, success: true, reason: null },
          { rawSn: 2, success: false, reason: '건너뛰기를 해제한 작업이 아닙니다.' },
        ],
      }),
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = await selectFirstTwo();
    await user.click(screen.getByRole('button', { name: '2개 영상 시계열 일괄 재수행' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === RERUN_PATH);
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ rawSns: [1, 2] });
    });
    const result = await screen.findByTestId('bulk-retry-result');
    expect(within(result).getByTestId('bulk-retry-failure-2')).toHaveTextContent(
      '건너뛰기를 해제한 작업이 아닙니다.',
    );
    expect(screen.getByRole('dialog', { name: '시계열 일괄 재수행 결과' })).toBeInTheDocument();
  });

  it('같은_영상을_여러_번_눌러도_요청에는_1건만_담긴다', async () => {
    mockVideos(mock, 2);
    mock.onPost(RERUN_PATH).reply(
      200,
      okResult({ successCount: 1, failureCount: 0, results: [{ rawSn: 1, success: true, reason: null }] }),
    );

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    const box = screen.getByRole('checkbox', { name: 'CCTV-1 선택' });
    await user.click(box);
    await user.click(box);
    await user.click(box);

    await user.click(screen.getByRole('button', { name: '1개 영상 시계열 일괄 재수행' }));

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === RERUN_PATH);
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ rawSns: [1] });
    });
  });
});
