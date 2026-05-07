import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { SaveCommitButton } from '../components/SaveCommitButton';
import type { Label } from '../types';

const sample: Label[] = [
  {
    id: 'tmp1',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
  },
];

describe('SaveCommitButton', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('내부_채널_저장_시_PUT_+_POST_commit_순차_호출', async () => {
    const calls: string[] = [];
    mock.onPut('/frames/123/labels').reply(() => {
      calls.push('PUT');
      return [
        200,
        {
          success: true,
          data: { frameNo: 1, srcSn: 123, labels: sample },
          message: null,
          errorCode: null,
        },
      ];
    });
    mock.onPost('/frames/123/commit').reply(() => {
      calls.push('POST');
      return [
        200,
        {
          success: true,
          data: { commitSha: 'sha', committedAt: '2026-05-07' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<SaveCommitButton srcSn={123} labels={sample} />);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(calls).toEqual(['PUT', 'POST']));
  });

  it('portalMode_저장시_commit_호출_안_함', async () => {
    const calls: string[] = [];
    mock.onPut('/frames/123/labels').reply(() => {
      calls.push('PUT');
      return [
        200,
        {
          success: true,
          data: { frameNo: 1, srcSn: 123, labels: sample },
          message: null,
          errorCode: null,
        },
      ];
    });
    mock.onPost('/frames/123/commit').reply(() => {
      calls.push('POST');
      return [200, { success: true, data: {}, message: null, errorCode: null }];
    });

    renderWithProviders(<SaveCommitButton srcSn={123} labels={sample} portalMode />);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(calls).toEqual(['PUT']));
  });
});
