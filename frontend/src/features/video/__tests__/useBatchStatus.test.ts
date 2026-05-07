import { describe, expect, it } from 'vitest';

import { BATCH_STATUS_POLL_INTERVAL_MS } from '../hooks/useBatchStatus';

describe('useBatchStatus', () => {
  it('처리_현황_5초_폴링_refetchInterval_확인', () => {
    expect(BATCH_STATUS_POLL_INTERVAL_MS).toBe(5000);
  });
});
