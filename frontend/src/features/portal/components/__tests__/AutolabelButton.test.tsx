import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { AutolabelButton } from '../AutolabelButton';

describe('AutolabelButton', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('클릭시_오토라벨링_API_호출_및_성공_콜백', async () => {
    let called = false;
    mock.onPost('/portal/autolabel').reply(() => {
      called = true;
      return [
        200,
        {
          success: true,
          data: { srcSn: 5, detectedCount: 3, elapsedMs: 800 },
          message: null,
          errorCode: null,
        },
      ];
    });

    let receivedCount = 0;
    renderWithProviders(
      <AutolabelButton srcSn={5} onSuccess={(r) => (receivedCount = r.detectedCount)} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /오토라벨링/ }));

    await waitFor(() => expect(called).toBe(true));
    await waitFor(() => expect(receivedCount).toBe(3));
  });
});
