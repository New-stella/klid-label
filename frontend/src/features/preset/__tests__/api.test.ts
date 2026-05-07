import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { clonePreset, listPresets } from '@/features/preset/api';

describe('preset api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('listPresets_조회', async () => {
    mock.onGet('/manage/presets').reply(200, {
      success: true,
      data: [
        { id: 1, name: '화재 기본', eventTypeCd: 'FIRE', items: [] },
      ],
      message: null,
      errorCode: null,
    });
    const list = await listPresets();
    expect(list).toHaveLength(1);
    expect(list[0]!.name).toBe('화재 기본');
  });

  it('프리셋_복사시_복사본_접미사', async () => {
    mock.onPost('/manage/presets/1/clone').reply(200, {
      success: true,
      data: {
        id: 2,
        name: '화재 기본 (복사본)',
        eventTypeCd: 'FIRE',
        items: [],
      },
      message: null,
      errorCode: null,
    });
    const cloned = await clonePreset(1);
    expect(cloned.name).toMatch(/복사본/);
  });
});
