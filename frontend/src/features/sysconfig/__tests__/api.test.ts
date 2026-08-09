import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getAiDefaults, getConfigs, updateConfig } from '../api';

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

  it('getAiDefaults_는_관리영역_밖의_ai_defaults_를_호출한다', async () => {
    // 관리 영역(/manage/**)은 검수자 전용이라 작업자가 부르면 403 이 쌓인다. 별도 경로여야 한다.
    let capturedUrl = '';
    mock.onGet('/ai-defaults').reply((config) => {
      capturedUrl = config.url ?? '';
      return [
        200,
        {
          success: true,
          data: { confThreshold: 25, simplifyTolerance: 1.0 },
          message: null,
          errorCode: null,
        },
      ];
    });

    const defaults = await getAiDefaults();
    expect(capturedUrl).toBe('/ai-defaults');
    expect(defaults).toEqual({ confThreshold: 25, simplifyTolerance: 1.0 });
  });

  it('getAiDefaults_는_생략된_항목을_undefined_로_남겨_화면_폴백을_허용한다', async () => {
    // BE 는 저장값이 없거나 숫자로 해석되지 않는 항목을 응답에서 생략한다.
    mock.onGet('/ai-defaults').reply(200, {
      success: true,
      data: { simplifyTolerance: 2.5 },
      message: null,
      errorCode: null,
    });

    const defaults = await getAiDefaults();
    expect(defaults.confThreshold).toBeUndefined();
    expect(defaults.simplifyTolerance).toBe(2.5);
  });
});
