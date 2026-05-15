import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { assignTask, listTasks, reassignTask } from '../api';

describe('task api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listTasks_workerId_쿼리_파라미터로_전달', async () => {
    mock.onGet('/assignments').reply((config) => {
      expect(config.params).toMatchObject({ workerId: 7 });
      return [
        200,
        {
          success: true,
          data: {
            content: [
              {
                id: 100,
                videoId: 1,
                cctvName: 'CCTV-1',
                workerId: 7,
                workerName: '홍길동',
                status: 'PENDING',
                assignedAt: '2026-05-07T10:00:00Z',
              },
            ],
            totalElements: 1,
            totalPages: 1,
            number: 0,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await listTasks({ workerId: 7 });
    expect(res.totalElements).toBe(1);
  });

  it('assignTask_POST_assignments_body_workerId_rawDataIds', async () => {
    mock.onPost('/assignments').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      expect(body).toMatchObject({ workerId: 7, rawDataIds: [1, 2] });
      return [
        201,
        {
          success: true,
          data: { id: 200, videoId: 1, workerId: 7, status: 'PENDING', assignedAt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const a = await assignTask({ workerId: 7, rawDataIds: [1, 2] });
    expect(a.id).toBe(200);
  });

  it('reassignTask_PATCH_assignments_id_body_workerId', async () => {
    mock.onPatch('/assignments/200').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      expect(body).toMatchObject({ workerId: 9 });
      return [
        200,
        {
          success: true,
          data: { id: 200, videoId: 1, workerId: 9, status: 'PENDING', assignedAt: '2026-05-07T11:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const a = await reassignTask(200, { workerId: 9 });
    expect(a.workerId).toBe(9);
  });
});
