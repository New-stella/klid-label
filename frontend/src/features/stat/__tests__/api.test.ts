import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { downloadReport, getOverallStats, getWorkerDashboard } from '@/features/stat/api';

describe('stat api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('getWorkerDashboard_period_정규식_검증_불일치_에러', async () => {
    await expect(
      // @ts-expect-error — 의도적 불일치
      getWorkerDashboard('INVALID'),
    ).rejects.toThrow(/잘못된 period/);
  });

  it('getWorkerDashboard_정상_period_요청', async () => {
    mock.onGet('/stats/worker').reply(200, {
      success: true,
      data: {
        totalLabeled: 100,
        totalReviewed: 20,
        approvalRate: 95.5,
        averageElapsedSec: 120,
        dailyCompletion: [],
        eventDistribution: [],
        monthly: [],
      },
      message: null,
      errorCode: null,
    });
    const data = await getWorkerDashboard('WEEK');
    expect(data.totalLabeled).toBe(100);
  });

  it('getOverallStats_요청', async () => {
    mock.onGet('/stats/overall').reply(200, {
      success: true,
      data: {
        cumulativeImageCount: 50000,
        cumulativeVideoCount: 1500,
        processing: {
          pending: 1,
          inProgress: 2,
          reviewPending: 3,
          approved: 4,
          rejected: 5,
        },
        eventDistribution: [],
        workers: [],
      },
      message: null,
      errorCode: null,
    });
    const data = await getOverallStats();
    expect(data.cumulativeImageCount).toBe(50000);
  });

  it('리포트_다운로드_CSV_blob_받기', async () => {
    const csv = 'month,labeled\n2026-05,100\n';
    mock.onGet('/stats/report').reply(200, csv, { 'Content-Type': 'text/csv' });
    const blob = await downloadReport('MONTH');
    expect(blob).toBeInstanceOf(Blob);
  });
});
