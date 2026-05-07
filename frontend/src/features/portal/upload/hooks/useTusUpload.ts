// useTusUpload — TusUploader를 React state로 래핑.
// 진행률/상태/액션을 컴포넌트에서 쉽게 사용.

import { useCallback, useRef, useState } from 'react';

import type { TusUploadProgress, TusUploadStatus } from '../../types';
import { TusUploader } from '../TusUploader';

interface UseTusUploadResult {
  status: TusUploadStatus;
  progress: TusUploadProgress;
  error: Error | null;
  start: (file: File) => void;
  pause: () => Promise<void>;
  resume: () => void;
  cancel: () => Promise<void>;
}

const INITIAL_PROGRESS: TusUploadProgress = {
  bytesUploaded: 0,
  bytesTotal: 0,
  percent: 0,
};

export function useTusUpload(): UseTusUploadResult {
  const [status, setStatus] = useState<TusUploadStatus>('IDLE');
  const [progress, setProgress] = useState<TusUploadProgress>(INITIAL_PROGRESS);
  const [error, setError] = useState<Error | null>(null);
  const uploaderRef = useRef<TusUploader | null>(null);

  const start = useCallback((file: File) => {
    setError(null);
    setProgress(INITIAL_PROGRESS);
    const uploader = new TusUploader({
      file,
      onProgress: (p) => setProgress(p),
      onSuccess: () => setStatus('COMPLETED'),
      onError: (err) => {
        setError(err);
        setStatus('FAILED');
      },
    });
    uploaderRef.current = uploader;
    uploader.start();
    setStatus('UPLOADING');
  }, []);

  const pause = useCallback(async () => {
    if (uploaderRef.current) {
      await uploaderRef.current.pause();
      setStatus('PAUSED');
    }
  }, []);

  const resume = useCallback(() => {
    if (uploaderRef.current) {
      uploaderRef.current.resume();
      setStatus('UPLOADING');
    }
  }, []);

  const cancel = useCallback(async () => {
    if (uploaderRef.current) {
      await uploaderRef.current.cancel();
      uploaderRef.current = null;
    }
    setProgress(INITIAL_PROGRESS);
    setStatus('IDLE');
  }, []);

  return { status, progress, error, start, pause, resume, cancel };
}
