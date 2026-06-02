import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { reprocessDeident } from '../api';

describe('deident api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('재처리_POST_deident_id_reprocess_호출', async () => {
    let called = false;
    mock.onPost('/deident/42/reprocess').reply(() => {
      called = true;
      return [
        200,
        { success: true, data: null, message: null, errorCode: null },
      ];
    });
    await reprocessDeident(42);
    expect(called).toBe(true);
  });
});
