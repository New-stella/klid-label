import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';

import { useAuthStore } from '@/stores/useAuthStore';

vi.mock('tus-js-client', () => {
  const instances: TusMockShape[] = [];

  class TusMock {
    options: Record<string, unknown>;
    file: unknown;
    url: string | null = null;
    started = false;
    aborted = false;
    shouldTerminate = false;

    constructor(file: unknown, options: Record<string, unknown>) {
      this.file = file;
      this.options = options;
      instances.push(this);
    }

    start() {
      this.started = true;
    }

    async abort(shouldTerminate?: boolean) {
      this.aborted = true;
      this.shouldTerminate = !!shouldTerminate;
    }

    async findPreviousUploads() {
      return [];
    }

    resumeFromPreviousUpload() {
      /* noop */
    }
  }

  return {
    Upload: TusMock,
    isSupported: true,
    __instances: instances,
  };
});

interface TusMockShape {
  options: Record<string, unknown>;
  file: unknown;
  url: string | null;
  started: boolean;
  aborted: boolean;
  shouldTerminate: boolean;
}

import * as tusModule from 'tus-js-client';

import { useTusUpload } from '../hooks/useTusUpload';

const tusInstances = (tusModule as unknown as { __instances: TusMockShape[] }).__instances;

function makeFile(name: string, size: number, type: string): File {
  const blob = new Blob([new Uint8Array(0)], { type });
  Object.defineProperty(blob, 'size', { value: size });
  const file = new File([blob], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

describe('useTusUpload', () => {
  beforeEach(() => {
    tusInstances.length = 0;
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('TUS_업로드_시작_상태_UPLOADING_및_진행률_업데이트', async () => {
    const { result } = renderHook(() => useTusUpload());
    const file = makeFile('a.mp4', 1000, 'video/mp4');

    act(() => {
      result.current.start(file);
    });

    expect(result.current.status).toBe('UPLOADING');

    type ProgressFn = (sent: number, total: number) => void;
    act(() => {
      (tusInstances[0].options.onProgress as ProgressFn)(500, 1000);
    });
    expect(result.current.progress.percent).toBe(50);

    type SuccessFn = (payload: { lastResponse: unknown }) => void;
    act(() => {
      (tusInstances[0].options.onSuccess as SuccessFn)({ lastResponse: {} });
    });
    expect(result.current.status).toBe('COMPLETED');
  });

  it('TUS_업로드_pause_resume_재개_offset부터', async () => {
    const { result } = renderHook(() => useTusUpload());
    const file = makeFile('a.mp4', 1000, 'video/mp4');

    act(() => {
      result.current.start(file);
    });
    await act(async () => {
      await result.current.pause();
    });
    expect(result.current.status).toBe('PAUSED');

    act(() => {
      result.current.resume();
    });
    expect(result.current.status).toBe('UPLOADING');
  });

  it('TUS_업로드_cancel_상태_초기화', async () => {
    const { result } = renderHook(() => useTusUpload());
    const file = makeFile('a.mp4', 1000, 'video/mp4');

    act(() => {
      result.current.start(file);
    });
    await act(async () => {
      await result.current.cancel();
    });
    expect(result.current.status).toBe('IDLE');
  });
});
