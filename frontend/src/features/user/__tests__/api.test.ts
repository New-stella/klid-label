import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getUser, listUsers, listWorkers } from '../api';

describe('user api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listWorkers_GET_users_workers', async () => {
    mock.onGet('/users/workers').reply(200, {
      success: true,
      data: [
        { id: 7, name: '홍길동', active: true },
        { id: 8, name: '김작업', active: true },
      ],
      message: null,
      errorCode: null,
    });

    const workers = await listWorkers();
    expect(workers).toHaveLength(2);
    expect(workers[0].name).toBe('홍길동');
  });

  it('listUsers_검색_파라미터_전달', async () => {
    mock.onGet('/users').reply((config) => {
      expect(config.params).toMatchObject({ keyword: '홍', role: 'WORKER' });
      return [
        200,
        {
          success: true,
          data: {
            content: [
              {
                id: 7,
                loginId: 'hong',
                name: '홍길동',
                role: 'WORKER',
                active: true,
                createdAt: '2026-05-01T00:00:00Z',
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

    const res = await listUsers({ keyword: '홍', role: 'WORKER' });
    expect(res.totalElements).toBe(1);
  });

  it('getUser_상세_정상_파싱', async () => {
    mock.onGet('/users/7').reply(200, {
      success: true,
      data: {
        id: 7,
        loginId: 'hong',
        name: '홍길동',
        role: 'WORKER',
        active: true,
        createdAt: '2026-05-01T00:00:00Z',
      },
      message: null,
      errorCode: null,
    });

    const u = await getUser(7);
    expect(u.id).toBe(7);
  });
});
