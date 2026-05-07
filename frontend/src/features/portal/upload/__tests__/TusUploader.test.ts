import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/stores/useAuthStore';

// vi.mock 팩토리는 호이스트되어 모듈 외부 변수 참조 불가 → 팩토리 내부에 상태 보관 + 노출.
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

    async abort(shouldTerminate?: boolean): Promise<void> {
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
  start(): void;
  abort(shouldTerminate?: boolean): Promise<void>;
}

import * as tusModule from 'tus-js-client';

import { TusUploader } from '../TusUploader';

const tusInstances = (tusModule as unknown as { __instances: TusMockShape[] }).__instances;

function makeFile(name: string, size: number, type: string): File {
  const blob = new Blob([new Uint8Array(0)], { type });
  Object.defineProperty(blob, 'size', { value: size });
  const file = new File([blob], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

describe('TusUploader', () => {
  beforeEach(() => {
    tusInstances.length = 0;
    useAuthStore.setState({
      token: 'jwt.token.here',
      claims: { sub: 'u1', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('TUS_업로드_5GB_파일_시작_진행률_콜백', () => {
    const file = makeFile('video.mp4', 100 * 1024 * 1024, 'video/mp4'); // jsdom 메모리상 100MB로 시뮬
    const onProgress = vi.fn();
    const uploader = new TusUploader({
      file,
      onProgress,
    });
    uploader.start();

    expect(tusInstances).toHaveLength(1);
    const inst = tusInstances[0];
    expect(inst.started).toBe(true);
    expect(inst.options.endpoint).toBe('/api/v1/portal/uploads');
    expect(inst.options.chunkSize).toBe(8 * 1024 * 1024);

    type ProgressFn = (sent: number, total: number) => void;
    (inst.options.onProgress as ProgressFn)(50 * 1024 * 1024, 100 * 1024 * 1024);
    expect(onProgress).toHaveBeenCalledWith({
      bytesUploaded: 50 * 1024 * 1024,
      bytesTotal: 100 * 1024 * 1024,
      percent: 50,
    });
  });

  it('TUS_업로드_Bearer_토큰_헤더_자동_주입', () => {
    const file = makeFile('a.mp4', 1000, 'video/mp4');
    new TusUploader({ file }).start();

    const headers = tusInstances[0].options.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer jwt.token.here');
  });

  it('TUS_업로드_metadata_filename_sanitize_path_traversal_차단', () => {
    const file = makeFile('../../etc/evil.mp4', 1000, 'video/mp4');
    new TusUploader({ file }).start();

    const metadata = tusInstances[0].options.metadata as Record<string, string>;
    expect(metadata.filename).toBe('etcevil.mp4');
    expect(metadata.filename).not.toContain('..');
    expect(metadata.filename).not.toContain('/');
    expect(metadata.filetype).toBe('video/mp4');
  });

  it('TUS_업로드_pause_resume_재개_offset부터', async () => {
    const file = makeFile('a.mp4', 1000, 'video/mp4');
    const uploader = new TusUploader({ file });
    uploader.start();
    expect(tusInstances[0].started).toBe(true);

    await uploader.pause();
    expect(tusInstances[0].aborted).toBe(true);
    expect(tusInstances[0].shouldTerminate).toBe(false);

    uploader.resume();
    expect(tusInstances[0].started).toBe(true);
  });

  it('TUS_업로드_cancel_terminate_true', async () => {
    const file = makeFile('a.mp4', 1000, 'video/mp4');
    const uploader = new TusUploader({ file });
    uploader.start();

    await uploader.cancel();
    expect(tusInstances[0].aborted).toBe(true);
    expect(tusInstances[0].shouldTerminate).toBe(true);
  });

  it('TUS_업로드_onSuccess_콜백', () => {
    const file = makeFile('a.mp4', 1000, 'video/mp4');
    const onSuccess = vi.fn();
    new TusUploader({ file, onSuccess }).start();

    type SuccessFn = (payload: { lastResponse: unknown }) => void;
    (tusInstances[0].options.onSuccess as SuccessFn)({ lastResponse: {} });
    expect(onSuccess).toHaveBeenCalled();
  });
});
