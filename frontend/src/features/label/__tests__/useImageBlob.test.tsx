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

  it('raw_true_옵션_지정시_쿼리_파라미터_raw_true_포함', async () => {
    mock
      .onGet('/frames/321/image', { params: { raw: true } })
      .reply(200, new Blob(['raw-bytes'], { type: 'image/jpeg' }));

    const { result } = renderHook(() => useImageBlob(321, { raw: true }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.url).toMatch(/^blob:/);
    expect(result.current.error).toBeNull();
    // axios-mock-adapter 가 params 매칭 — 통과 시 raw=true 가 정상 전달된 것
    expect(mock.history.get).toHaveLength(1);
    expect(mock.history.get[0].params).toEqual({ raw: true });
  });

  it('srcSn_전환시_새_URL_도착_후_이전_URL_revoke_되고_그_전에는_유지된다', async () => {
    // FE — 깜빡임/Konva stale 접근 방지: 새 blob 도착 전까지 이전 URL 을 revoke 하지 않는다.
    mock.onGet('/frames/100/image').reply(200, new Blob(['a'], { type: 'image/jpeg' }));
    mock.onGet('/frames/200/image').reply(200, new Blob(['bb'], { type: 'image/jpeg' }));

    const { result, rerender } = renderHook(({ id }: { id: number }) => useImageBlob(id), {
      initialProps: { id: 100 },
    });

    await waitFor(() => expect(result.current.url).not.toBeNull());
    const firstUrl = result.current.url!;
    // 첫 URL 은 아직 살아있어야 한다 (revoke 안 됨).
    expect(revoked).not.toContain(firstUrl);

    rerender({ id: 200 });

    await waitFor(() => expect(result.current.url).not.toBe(firstUrl));
    const secondUrl = result.current.url!;
    expect(secondUrl).not.toBeNull();
    // 새 URL 도착 후 이전 URL 이 revoke 되어야 한다.
    await waitFor(() => expect(revoked).toContain(firstUrl));
    // 새(현재) URL 은 여전히 살아있어야 한다.
    expect(revoked).not.toContain(secondUrl);
  });

  it('raw_옵션_미지정시_raw_쿼리_파라미터_미포함', async () => {
    mock.onGet('/frames/322/image').reply((config) => {
      // raw 파라미터가 없어야 함
      expect(config.params === undefined || config.params.raw === undefined).toBe(true);
      return [200, new Blob(['no-raw'], { type: 'image/jpeg' })];
    });

    const { result } = renderHook(() => useImageBlob(322));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.url).toMatch(/^blob:/);
  });
});
