import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getConfigs, updateConfig } from '../api';

describe('sysconfig api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('getConfigs_GET_manage_configs_정상_파싱', async () => {
    mock.onGet('/manage/configs').reply(200, {
      success: true,
      data: [
        { key: 'BATCH_INTERVAL_SEC', value: 60, updatedAt: '2026-05-01T00:00:00Z' },
        { key: 'BATCH_CONCURRENCY', value: 1, updatedAt: '2026-05-01T00:00:00Z' },
      ],
      message: null,
      errorCode: null,
    });

    const configs = await getConfigs();
    expect(configs).toHaveLength(2);
    expect(configs.find((c) => c.key === 'BATCH_INTERVAL_SEC')?.value).toBe(60);
  });

  it('updateConfig_PUT_manage_configs_key_path_body_value', async () => {
    // BE 시그니처: PUT /v1/manage/configs/{key}  body: { value: string }
    let capturedUrl = '';
    mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
      capturedUrl = config.url ?? '';
      const body = JSON.parse(config.data ?? '{}');
      expect(body).toEqual({ value: '120' });
      return [
        200,
        {
          success: true,
          data: { key: 'BATCH_INTERVAL_SEC', value: 120, updatedAt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const c = await updateConfig({ key: 'BATCH_INTERVAL_SEC', value: 120 });
    expect(capturedUrl).toBe('/manage/configs/BATCH_INTERVAL_SEC');
    expect(c.value).toBe(120);
  });
});
