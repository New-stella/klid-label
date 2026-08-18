import { screen, waitFor, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { WorkerStatPage } from '@/pages/WorkerStatPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

/**
 * 작업자 통계 — 검수완료 기준 병기 회귀 가드. @design SCREEN-020, API-056
 *
 * 고정하는 계약:
 *  - 완료 카드: 주 수치 completed + 보조 '검수완료 기준 · 전체 N건 (완료율 M%)'
 *  - 총 라벨 수 카드: 주 수치 approvedLabelCount + 보조 '검수완료 기준 · 전체 N개'
 *    ★ 이 카드에는 <완료율을 붙이지 않는다> — completionRate 는 영상 건수의 비율이라 라벨
 *      분량에 그대로 쓸 수 없고, 라벨 단위 비율은 서버가 내려주지 않으므로 지어내지 않는다.
 *  - completionRate 는 0.0~1.0 비율이며 표시할 때만 백분율로 바꾼다. 화면이 다시 나누지 않는다.
 *  - 세 필드 미수신은 실데이터 0 으로 오인시키지 않는다.
 */

function setRole(role: 'WORKER' | 'REVIEWER') {
  useAuthStore.setState({
    token: 'dummy-token',
    claims: {
      sub: '11',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

const base = {
  workerId: '11',
  workerName: '홍길동',
  completed: 42,
  inProgress: 3,
  rejected: 2,
  labelCount: 624,
  autoLabelRate: 0.65,
  rejectRate: 0.045,
  dailyCompletion: [],
  monthly: [],
  assignedTotal: 45,
  completionRate: 0.93,
  approvedLabelCount: 580,
};

describe('작업자 통계 — 검수완료 기준 병기', () => {
  let mock: MockAdapter;
  // 응답 본문은 테스트마다 갈아 끼운다 — 핸들러는 한 번만 등록해 재등록/reset 경합을 없앤다.
  let payload: Record<string, unknown> = { ...base };

  function mockStat(next: Record<string, unknown>) {
    payload = next;
  }

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    payload = { ...base };
    mock.onGet('/stats/worker').reply(() => [
      200,
      { success: true, data: payload, message: null, errorCode: null },
    ]);
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 0 },
      message: null,
      errorCode: null,
    });
    setRole('WORKER');
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('완료_카드는_검수완료_주수치와_전체_완료율을_함께_보여준다', async () => {
    // given / when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-completed');

    // then
    expect(within(card).getByText('42')).toBeInTheDocument();
    expect(
      within(card).getByText('검수완료 기준 · 전체 45건 (완료율 93%)'),
    ).toBeInTheDocument();
  });

  it('완료율은_0에서1_비율을_표시할_때만_백분율로_바꾼다', async () => {
    // given: 서버가 내려주는 단위는 비율(0.0~1.0)이다 — 백분율이 아니다.
    mockStat({ ...base, completed: 21, assignedTotal: 50, completionRate: 0.42 });

    // when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-completed');

    // then: 42% 로 표시된다 (0.42% 도, 4200% 도 아니다)
    expect(within(card).getByText(/완료율 42%/)).toBeInTheDocument();
    expect(within(card).queryByText(/0\.42%|4,?200%/)).toBeNull();
  });

  it('완료율을_화면에서_다시_나눠_구하지_않는다', async () => {
    // given: 서버 비율과 completed/assignedTotal 의 몫이 일부러 어긋난 값.
    //        화면이 재유도하면 25% 가 나오고, 서버 값을 쓰면 90% 가 나온다.
    mockStat({ ...base, completed: 10, assignedTotal: 40, completionRate: 0.9 });

    // when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-completed');

    // then
    expect(within(card).getByText(/완료율 90%/)).toBeInTheDocument();
    expect(within(card).queryByText(/완료율 25%/)).toBeNull();
  });

  it('총_라벨_수_카드는_검수완료_라벨을_주수치로_두고_전체를_병기한다', async () => {
    // given / when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-label-count');

    // then: 주 수치는 approvedLabelCount(580), 전체(624)는 보조 라인에만 나온다
    expect(within(card).getByText('580')).toBeInTheDocument();
    expect(within(card).getByText('검수완료 기준 · 전체 624개')).toBeInTheDocument();
  });

  it('총_라벨_수_카드에는_완료율을_붙이지_않는다', async () => {
    // given / when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-label-count');
    await within(card).findByText(/검수완료 기준/);

    // then: 라벨 단위 비율은 서버가 내려주지 않으므로 화면이 지어내지 않는다
    expect(within(card).queryByText(/완료율/)).toBeNull();
  });

  it('검수완료_라벨수_미수신시_전체값으로_조용히_폴백하지_않는다', async () => {
    // given: 구버전 BE — approvedLabelCount 미수신
    const legacy: Record<string, unknown> = { ...base };
    delete legacy.approvedLabelCount;
    mockStat(legacy);

    // when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-label-count');

    // then: 주 수치는 '-' 이고 0 도 전체값(624)도 주 수치가 되지 않는다
    await within(card).findByText('-');
    expect(within(card).queryByText('580')).toBeNull();
    expect(within(card).queryByText('0')).toBeNull();
    expect(within(card).getByText(/검수완료 집계를 불러오지 못했습니다/)).toBeInTheDocument();
  });

  it('전체_라벨수_미수신시_검수완료_수치를_전체값으로_조용히_폴백하지_않는다', async () => {
    // given: 구버전·부분 장애 BE — labelCount(전체 라벨) 자체가 오지 않았다.
    //        이때 검수완료 라벨(580)을 전체로 적으면 "전체 580개 = 검수완료 580개"가 되어
    //        미수신이 100% 완료로 읽힌다(거짓 수치).
    const legacy: Record<string, unknown> = { ...base };
    delete legacy.labelCount;
    mockStat(legacy);

    // when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-label-count');

    // then: 주 수치(검수완료 580)는 그대로 두고, 보조 라인은 전체를 지어내지 않는다
    await within(card).findByText('580');
    await waitFor(() =>
      expect(within(card).getByText(/집계를 불러오지 못했습니다/)).toBeInTheDocument(),
    );
    expect(within(card).queryByText(/전체 580개/)).toBeNull();
    expect(within(card).queryByText(/전체 0개/)).toBeNull();
    // 라벨 축에는 완료율을 붙이지 않는다(미수신을 계기로 새로 만들지도 않는다)
    expect(within(card).queryByText(/완료율/)).toBeNull();
  });

  it('배정_전체_미수신시_전체_0건이라고_단정하지_않는다', async () => {
    // given: 구버전 BE — assignedTotal·completionRate 미수신
    const legacy: Record<string, unknown> = { ...base };
    delete legacy.assignedTotal;
    delete legacy.completionRate;
    mockStat(legacy);

    // when
    renderWithProviders(<WorkerStatPage />);
    const card = await screen.findByTestId('worker-kpi-completed');

    // then: 주 수치(완료 42)는 그대로, 보조 라인은 '전체 0건' 을 사실처럼 적지 않는다
    await within(card).findByText('42');
    await waitFor(() =>
      expect(within(card).getByText(/집계를 불러오지 못했습니다/)).toBeInTheDocument(),
    );
    expect(within(card).queryByText(/전체 0건/)).toBeNull();
    expect(within(card).queryByText(/완료율/)).toBeNull();
  });
});
