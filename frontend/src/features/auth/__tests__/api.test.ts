import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getMe } from '../api';

describe('auth api - getMe', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('getMe_호출_시_role_channel_정상_파싱', async () => {
    mock.onGet('/me').reply(200, {
      success: true,
      data: {
        sub: 'user-1',
        role: 'REVIEWER',
        channel: 'INTERNAL',
        name: '홍길동',
      },
      message: null,
      errorCode: null,
    });

    const me = await getMe();
    expect(me.sub).toBe('user-1');
    expect(me.role).toBe('REVIEWER');
    expect(me.channel).toBe('INTERNAL');
    expect(me.name).toBe('홍길동');
  });
});
