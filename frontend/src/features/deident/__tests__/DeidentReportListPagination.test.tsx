// 회귀 가드 — 비식별 신고 관리 목록의 페이저는 공용 컴포넌트(UI-008)여야 한다.
//
// 구 동작: 페이지 번호가 없는 약식 "이전/다음 + N건(x/y페이지)" 을 이 화면이 자체 구현하고
// 있었다. 사양은 "양끝 + 현재 앞뒤 1칸 + 말줄임" 규칙을 요구하고 **그 구현체가 이미
// 코드베이스에 있다**(components/common/Pagination). 화면마다 다시 만들면 규칙이 갈린다.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { DeidentReportListPage } from '@/pages/manage/DeidentReportListPage';
import { renderWithProviders } from '@/test/renderWithProviders';

const ROW = {
  rprtSn: 7,
  rawSn: 42,
  reporterNo: 100,
  reporterName: null as string | null,
  reason: '얼굴 미블러',
  status: 'OPEN',
  reportDt: '2026-06-05T10:00:00',
  resolvedDt: null,
  stage: 'LABELING',
};

function pageBody(number: number, totalPages: number, totalElements: number) {
  return {
    success: true,
    data: {
      content: [{ ...ROW, rprtSn: 100 + number }],
      totalElements,
      totalPages,
      number,
      size: 20,
    },
    message: null,
    errorCode: null,
  };
}

describe('DeidentReportListPage 페이저', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('공용_페이저가_렌더되고_페이지_번호_버튼이_있다', async () => {
    mock.onGet('/deident-reports').reply(200, pageBody(0, 3, 45));

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() =>
      expect(screen.getByRole('navigation', { name: '페이지네이션' })).toBeInTheDocument(),
    );
    // 번호 없는 약식 이전/다음이 아니라 임의 페이지로 직접 갈 수 있어야 한다.
    expect(screen.getByRole('button', { name: '2페이지' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '3페이지' })).toBeInTheDocument();
  });

  it('페이지_번호를_누르면_그_페이지를_조회한다', async () => {
    const user = userEvent.setup();
    mock.onGet('/deident-reports').reply((config) => {
      const page = Number(config.params?.page ?? 0);
      return [200, pageBody(page, 3, 45)];
    });

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: '3페이지' })).toBeInTheDocument(),
    );
    await user.click(screen.getByRole('button', { name: '3페이지' }));

    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params?.page).toBe(2);
    });
  });

  it('전체_건수_표기는_그대로_유지된다', async () => {
    // 페이저를 교체하면서 기존 정보를 잃지 않는다 — 공용 Pagination 은 건수를 갖지 않으므로
    // 건수 표기는 화면이 계속 소유한다.
    mock.onGet('/deident-reports').reply(200, pageBody(0, 3, 45));

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => expect(screen.getByText('45건')).toBeInTheDocument());
  });
});
