import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { DeidentReportListPage } from '@/pages/manage/DeidentReportListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function pageBody<T>(content: T[]) {
  return {
    success: true,
    data: {
      content,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  };
}

const OPEN_ROW = {
  rprtSn: 7,
  rawSn: 42,
  reporterNo: 100,
  reason: '얼굴 미블러',
  status: 'OPEN',
  reportDt: '2026-06-05T10:00:00',
  resolvedDt: null,
  stage: 'LABELING',
};

describe('DeidentReportListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('OPEN_신고_목록_렌더', async () => {
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => {
      expect(screen.getByTestId('deident-report-row-7')).toBeInTheDocument();
    });
    expect(screen.getByText('얼굴 미블러')).toBeInTheDocument();
    expect(screen.getByText('영상 #42')).toBeInTheDocument();
  });

  it('신고_단계가_사용자_언어로_표시된다', async () => {
    // given — 마킹 화면 신고 1건 + 라벨링 화면 신고 1건
    mock.onGet('/deident-reports').reply(
      200,
      pageBody([
        { ...OPEN_ROW, rprtSn: 7, stage: 'LABELING' },
        { ...OPEN_ROW, rprtSn: 8, stage: 'MARKING' },
      ]),
    );

    renderWithProviders(<DeidentReportListPage />);

    // then — 코드값 원문(MARKING/LABELING)·내부 컬럼명은 노출하지 않는다
    await waitFor(() => {
      expect(screen.getByTestId('deident-stage-7')).toHaveTextContent('라벨링');
    });
    expect(screen.getByTestId('deident-stage-8')).toHaveTextContent('마킹');
    expect(screen.queryByText('LABELING')).not.toBeInTheDocument();
    expect(screen.queryByText('MARKING')).not.toBeInTheDocument();
    expect(screen.queryByText(/DCLR_STP_CD/)).not.toBeInTheDocument();
  });

  it('단계가_null_인_레거시_신고는_미상으로_표시되고_빈칸이_아니다', async () => {
    // given — V171 이전 신고(백필하지 않아 영구히 null)
    mock.onGet('/deident-reports').reply(200, pageBody([{ ...OPEN_ROW, stage: null }]));

    renderWithProviders(<DeidentReportListPage />);

    // then — 빈칸이면 "값이 없다"와 "로딩 실패"가 구분되지 않는다
    await waitFor(() => {
      expect(screen.getByTestId('deident-stage-7')).toHaveTextContent('미상');
    });
    // 해소해도 단계별 재개가 없다는 사실이 툴팁으로 읽힌다
    expect(screen.getByTitle(/재마킹·프레임 재추출은 자동으로 진행되지 않습니다/)).toBeInTheDocument();
  });

  it('stage_필드가_없는_구_응답도_미상으로_표시된다', async () => {
    // given — 필드 추가 이전 형태의 응답(하위호환) — 화면이 깨지지 않아야 한다
    const legacyRow: Record<string, unknown> = { ...OPEN_ROW };
    delete legacyRow.stage;
    mock.onGet('/deident-reports').reply(200, pageBody([legacyRow]));

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => {
      expect(screen.getByTestId('deident-stage-7')).toHaveTextContent('미상');
    });
  });

  it('해소_처리_클릭시_resolve_API_호출_후_목록_갱신', async () => {
    let listCalls = 0;
    let resolveCalled = false;
    mock.onGet('/deident-reports').reply(() => {
      listCalls += 1;
      // 1차: OPEN 1건, resolve 후 재조회(2차): 0건
      return [200, pageBody(listCalls === 1 ? [OPEN_ROW] : [])];
    });
    mock.onPost('/deident-reports/7/resolve').reply(() => {
      resolveCalled = true;
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });

    await user.click(screen.getByTestId('deident-resolve-7'));

    await waitFor(() => expect(resolveCalled).toBe(true));
    // invalidate 로 목록 재조회 → 2회 이상 호출
    await waitFor(() => expect(listCalls).toBeGreaterThanOrEqual(2));
  });
});
