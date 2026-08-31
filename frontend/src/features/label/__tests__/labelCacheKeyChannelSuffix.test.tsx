// 회귀 가드 — 라벨 캐시 키의 채널 접미사(`'internal'` / `'portal'`)는 <빌드 채널>과 무관하다.
//
// ★왜 이 파일이 있나 (2026-08-31, 빌드 채널 값 개명 `internal` → `control`)
//
//   이 저장소에서 `'internal'` 이라는 낱말은 서로 다른 세 축에 쓰인다.
//
//     ① 인증 채널 클레임      토큰 `channel = 'INTERNAL'` — 이 사용자가 어디서 들어왔나
//     ② 빌드 채널             `VITE_BUILD_CHANNEL` — 이 산출물이 어느 향으로 만들어졌나
//                             → 2026-08-31 에 값이 `'control'` 로 개명됐다(`lib/buildChannel`)
//     ③ **라벨 캐시 키 접미사** `[...LABEL_KEYS.byFrame(srcSn, 0), 'internal' | 'portal']`
//                             → 같은 프레임을 <내부 전용 경로>로 받았는지 <포털 전용 경로>로
//                               받았는지를 가르는 캐시 네임스페이스. ②와 아무 관계가 없다.
//
//   ③은 ②의 개명을 <따라가지 않는다>. 따라가면 조회 키와 저장 후 무효화 키가 어긋나
//   **저장했는데 화면이 갱신되지 않는** 형태로 깨지는데, 문자열이라 타입 오류도 나지 않고
//   한쪽만 바꾸면 테스트도 통과할 수 있다(= 조용히 깨진다).
//
//   ⇒ 이 가드는 「③이 아직 `'internal'` 이다」와 「③은 ②의 기본값과 다른 값이다」를 함께
//     못 박는다. **다음 사람이 "남은 internal 을 마저 치우자"로 지우지 못하게 하는 것이
//     이 파일의 목적**이므로, ③을 정말로 바꾸려면 조회·저장 두 리터럴을 <함께> 바꾸고
//     이 파일도 함께 고쳐야 한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import type { ReactNode } from 'react';

import { apiClient } from '@/lib/api/client';
import { LABEL_KEYS } from '@/lib/queryKeys';
import { BUILD_CHANNELS, DEFAULT_BUILD_CHANNEL } from '@/lib/buildChannel';

import { useLabels } from '../hooks/useLabels';
import { useUpdateLabels } from '../hooks/useUpdateLabels';
import type { Label } from '../types';

/** 라벨 캐시 키가 <내부 전용 경로>로 받은 자료임을 표시하는 접미사. 빌드 채널 값이 아니다. */
const INTERNAL_CACHE_SUFFIX = 'internal';

const SRC_SN = 777;

function bbox(id: string): Label {
  return {
    id,
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 100, bottom: 80 },
  };
}

function newClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function wrapperFor(qc: QueryClient) {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  Wrapper.displayName = 'LabelCacheKeyGuardWrapper';
  return Wrapper;
}

describe('라벨 캐시 키 채널 접미사 — 빌드 채널 개명의 영향을 받지 않는다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    vi.unstubAllEnvs();
  });

  it('내부_조회_캐시_키의_접미사는_internal_그대로다', async () => {
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: { srcSn: SRC_SN, frameNo: 0, labels: [], siblings: [] },
      message: null,
      errorCode: null,
    });

    const qc = newClient();
    renderHook(() => useLabels(SRC_SN, false), { wrapper: wrapperFor(qc) });

    // 프로덕션 코드(`useLabels`)가 실제로 만든 키를 읽는다 — 로컬에서 조립한 배열에
    // 단언하면 프로덕션을 한 줄도 실행하지 않는 동어반복이 된다.
    await waitFor(() => {
      expect(qc.getQueryCache().getAll().length).toBeGreaterThan(0);
    });
    const keys = qc.getQueryCache().getAll().map((q) => q.queryKey);

    expect(keys).toContainEqual([...LABEL_KEYS.byFrame(SRC_SN, 0), INTERNAL_CACHE_SUFFIX]);
  });

  it('포털_조회_캐시_키의_접미사는_portal이다_두_네임스페이스가_갈린다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: { srcSn: SRC_SN, frameNo: 0, labels: [], siblings: [] },
      message: null,
      errorCode: null,
    });

    const qc = newClient();
    renderHook(() => useLabels(SRC_SN, true), { wrapper: wrapperFor(qc) });

    await waitFor(() => {
      expect(qc.getQueryCache().getAll().length).toBeGreaterThan(0);
    });
    const keys = qc.getQueryCache().getAll().map((q) => q.queryKey);

    expect(keys).toContainEqual([...LABEL_KEYS.byFrame(SRC_SN, 0), 'portal']);
    // 같은 프레임이라도 내부 키와 겹치지 않는다.
    expect(keys).not.toContainEqual([...LABEL_KEYS.byFrame(SRC_SN, 0), INTERNAL_CACHE_SUFFIX]);
  });

  it('캐시_접미사는_빌드채널_기본값과_다른_값이다_두_축이_별개임을_고정', () => {
    // 빌드 채널의 관제 값은 `'control'` 이다. 라벨 캐시 접미사가 그것을 따라가면
    // (= 두 값이 같아지면) 두 축이 한 낱말로 다시 붙어 이 가드의 의미가 사라진다.
    expect(DEFAULT_BUILD_CHANNEL).toBe('control');
    expect(INTERNAL_CACHE_SUFFIX).not.toBe(DEFAULT_BUILD_CHANNEL);
    // 캐시 접미사 `'internal'` 은 빌드 채널의 유효값이 <아니다> — 두 집합이 분리돼 있다.
    expect(BUILD_CHANNELS as readonly string[]).not.toContain(INTERNAL_CACHE_SUFFIX);
  });

  it('저장_후_무효화_키가_조회_키와_문자_그대로_같다_한쪽만_개명하면_저장후_미갱신', async () => {
    // 이 저장소가 반복해 낸 사고(문자열 일괄 치환)가 실제로 깨뜨리는 지점이다.
    // 조회는 `useLabels`, 무효화는 `useUpdateLabels` 가 각자 리터럴을 들고 있어
    // 한쪽만 바꿔도 컴파일·기존 테스트가 통과한다.
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: { srcSn: SRC_SN, frameNo: 0, labels: [], siblings: [] },
      message: null,
      errorCode: null,
    });
    mock.onPut(`/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: { srcSn: SRC_SN, frameNo: 0, labels: [], siblings: [] },
      message: null,
      errorCode: null,
    });

    const qc = newClient();
    const wrapper = wrapperFor(qc);

    renderHook(() => useLabels(SRC_SN, false), { wrapper });
    await waitFor(() => {
      expect(qc.getQueryCache().getAll().length).toBeGreaterThan(0);
    });
    const readKey = qc
      .getQueryCache()
      .getAll()
      .map((q) => q.queryKey)
      .find((k) => Array.isArray(k) && k[0] === 'labels' && k.includes(SRC_SN));
    expect(readKey).toBeDefined();

    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useUpdateLabels(SRC_SN), { wrapper });
    result.current.mutate([bbox('a')]);
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    const invalidatedKeys = invalidate.mock.calls.map((c) => c[0]?.queryKey);
    expect(invalidatedKeys).toContainEqual(readKey);
  });
});
