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

  it('getWorkerDashboard_workerId_없이_본인_조회', async () => {
    mock.onGet('/stats/worker').reply(200, {
      success: true,
      data: {
        workerId: 'self',
        workerName: 'me',
        completed: 100,
        inProgress: 5,
        rejected: 2,
        labelCount: 1000,
        autoLabelRate: 0.3,
        rejectRate: 0.02,
        dailyCompletion: [],
        monthly: [],
      },
      message: null,
      errorCode: null,
    });
    const data = await getWorkerDashboard();
    expect(data.completed).toBe(100);
  });

  it('getWorkerDashboard_workerId_지정_조회', async () => {
    mock.onGet('/stats/worker').reply(200, {
      success: true,
      data: {
        workerId: '11',
        workerName: '홍길동',
        completed: 50,
        inProgress: 1,
        rejected: 0,
        labelCount: 500,
        autoLabelRate: 0.5,
        rejectRate: 0.0,
        dailyCompletion: [],
        monthly: [],
      },
      message: null,
      errorCode: null,
    });
    const data = await getWorkerDashboard(11);
    expect(data.workerId).toBe('11');
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
