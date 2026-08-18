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
    // components 키는 BE ManageHealthController 가 실제로 넣는 3종 그대로다
    // (구 픽스처는 db/diskSpace/controlServer/portalServer 로 서버가 보내지 않는 응답을 흉내냈다).
    mock.onGet('/manage/health').reply(200, {
      status: 'UP',
      components: {
        deidentify: { status: 'UP' },
        aiServer: { status: 'UP' },
        database: { status: 'UP', details: { service: 'control-db' } },
      },
    });

    const h = await getHealth();
    expect(h.status).toBe('UP');
    expect(h.components?.database?.status).toBe('UP');
  });
});
