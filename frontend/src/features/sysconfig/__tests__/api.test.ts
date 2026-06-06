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

  it('getConfigs_GET_manage_configs_정상_파싱_BE_configKey_configVl', async () => {
    // BE ConfigResponse 실제 형태: configKey / configVl(문자열)
    mock.onGet('/manage/configs').reply(200, {
      success: true,
      data: [
        { configKey: 'BATCH_INTERVAL_SEC', configVl: '60', mdfcnDt: '2026-05-01T00:00:00Z' },
        { configKey: 'BATCH_CONCURRENCY', configVl: '1', mdfcnDt: '2026-05-01T00:00:00Z' },
      ],
      message: null,
      errorCode: null,
    });

    const configs = await getConfigs();
    expect(configs).toHaveLength(2);
    expect(configs.find((c) => c.configKey === 'BATCH_INTERVAL_SEC')?.configVl).toBe('60');
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
          data: { configKey: 'BATCH_INTERVAL_SEC', configVl: '120', mdfcnDt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const c = await updateConfig({ key: 'BATCH_INTERVAL_SEC', value: 120 });
    expect(capturedUrl).toBe('/manage/configs/BATCH_INTERVAL_SEC');
    expect(c.configVl).toBe('120');
  });
});
