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
        { key: 'FFMPEG_THREADS', value: 4, updatedAt: '2026-05-01T00:00:00Z' },
        { key: 'FFMPEG_OUTPUT_FPS', value: 5, updatedAt: '2026-05-01T00:00:00Z' },
        { key: 'BATCH_INTERVAL_SEC', value: 60, updatedAt: '2026-05-01T00:00:00Z' },
        { key: 'BATCH_CONCURRENCY', value: 1, updatedAt: '2026-05-01T00:00:00Z' },
      ],
      message: null,
      errorCode: null,
    });

    const configs = await getConfigs();
    expect(configs).toHaveLength(4);
    expect(configs.find((c) => c.key === 'FFMPEG_THREADS')?.value).toBe(4);
  });

  it('updateConfig_PUT_manage_configs_body_key_value', async () => {
    mock.onPut('/manage/configs').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      expect(body).toMatchObject({ key: 'FFMPEG_THREADS', value: 8 });
      return [
        200,
        {
          success: true,
          data: { key: 'FFMPEG_THREADS', value: 8, updatedAt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const c = await updateConfig({ key: 'FFMPEG_THREADS', value: 8 });
    expect(c.value).toBe(8);
  });
});
