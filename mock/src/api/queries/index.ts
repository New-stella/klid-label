import { useEffect, useRef, useState, useCallback } from 'react';
import { ApiError } from '../client';

export function useFetch<T>(
  path: string,
  params?: Record<string, unknown>,
): {
  data: T | undefined;
  isLoading: boolean;
  error: Error | null;
  refetch: () => void;
} {
  const [data, setData] = useState<T | undefined>(undefined);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [tick, setTick] = useState(0);

  // Serialize params to detect changes
  const paramsKey = params ? JSON.stringify(params) : '';

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    setError(null);

    const url = new URL('/api/v1' + path, window.location.origin);
    if (params) {
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
      });
    }

    fetch(url.toString(), {
      headers: { 'Content-Type': 'application/json' },
    })
      .then(async (res) => {
        if (!res.ok) throw new ApiError(res.status, res.statusText);
        const body = await res.json();
        if (!body.success) throw new ApiError(400, body.message ?? 'request failed', body.errorCode);
        return body.data as T;
      })
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err : new Error(String(err)));
          setIsLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, paramsKey, tick]);

  const refetch = useCallback(() => setTick((t) => t + 1), []);

  return { data, isLoading, error, refetch };
}

export function useMutation<B, R>(
  fn: (body: B) => Promise<R>,
): {
  mutate: (body: B) => Promise<R>;
  isLoading: boolean;
  error: Error | null;
} {
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const mutate = useCallback(
    async (body: B): Promise<R> => {
      setIsLoading(true);
      setError(null);
      try {
        const result = await fnRef.current(body);
        setIsLoading(false);
        return result;
      } catch (err: unknown) {
        const e = err instanceof Error ? err : new Error(String(err));
        setError(e);
        setIsLoading(false);
        throw e;
      }
    },
    [],
  );

  return { mutate, isLoading, error };
}
