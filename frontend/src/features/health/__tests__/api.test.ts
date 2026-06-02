import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getHealth, HEALTH_POLL_INTERVAL_MS } from '../api';

describe('health api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('actuator_health_5초_폴링_refetchInterval', () => {
    expect(HEALTH_POLL_INTERVAL_MS).toBe(5000);
  });

  it('getHealth_정상_파싱', async () => {
    // health는 wrapper 적용 안 되는 actuator 직접 호출 → ApiResponse 래퍼 미적용
    mock.onGet('/manage/health').reply(200, {
      status: 'UP',
      components: {
        db: { status: 'UP' },
        diskSpace: { status: 'UP' },
        controlServer: { status: 'UP' },
        portalServer: { status: 'UP' },
        aiServer: { status: 'UP' },
      },
    });

    const h = await getHealth();
    expect(h.status).toBe('UP');
    expect(h.components?.db?.status).toBe('UP');
  });
});
