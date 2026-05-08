import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { useImageBlob } from '../hooks/useImageBlob';

describe('useImageBlob', () => {
  let mock: MockAdapter;
  const created: string[] = [];
  const revoked: string[] = [];

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    created.length = 0;
    revoked.length = 0;
    // jsdom 은 createObjectURL 미구현 — vitest mock 으로 대체
    Object.defineProperty(URL, 'createObjectURL', {
      writable: true,
      configurable: true,
      value: vi.fn((b: Blob) => {
        const u = `blob:test/${created.length + 1}-${b.size}`;
        created.push(u);
        return u;
      }),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      writable: true,
      configurable: true,
      value: vi.fn((u: string) => {
        revoked.push(u);
      }),
    });
  });

  afterEach(() => {
    mock.restore();
  });

  it('srcSn_제공시_blob_url_발급', async () => {
    mock.onGet('/frames/123/image').reply(200, new Blob(['fake-jpeg-bytes'], { type: 'image/jpeg' }));

    const { result } = renderHook(() => useImageBlob(123));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });
    expect(result.current.url).toMatch(/^blob:/);
    expect(result.current.error).toBeNull();
    expect(created.length).toBe(1);
  });

  it('srcSn_undefined시_fetch_안함', async () => {
    const { result } = renderHook(() => useImageBlob(undefined));
    expect(result.current.url).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(mock.history.get.length).toBe(0);
  });

  it('unmount시_revokeObjectURL_호출_메모리_누수_방지', async () => {
    mock.onGet('/frames/456/image').reply(200, new Blob(['x'], { type: 'image/jpeg' }));

    const { result, unmount } = renderHook(() => useImageBlob(456));
    await waitFor(() => expect(result.current.url).not.toBeNull());
    const url = result.current.url!;

    unmount();
    expect(revoked).toContain(url);
  });

  it('네트워크_실패시_error_state_세팅', async () => {
    mock.onGet('/frames/789/image').reply(404);

    const { result } = renderHook(() => useImageBlob(789));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).not.toBeNull();
    expect(result.current.url).toBeNull();
  });
});
