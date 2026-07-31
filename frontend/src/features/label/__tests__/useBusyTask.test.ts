// Phase 1 — 배타 실행 래퍼 runExclusive 계약.
//
// 보호 구간 = 네트워크 + 결과 병합. fn 안에서 병합까지 끝내야 busy 해제 뒤에 미완료 후처리가
// 남지 않는다(위험 13). 취소/리셋/프레임 전환 이후 도착한 결과는 토큰 생존 검사로 폐기된다.
//
// DEV_FIX — 반환 계약이 판별 유니온(ok/rejected/discarded)이다. 거부(다른 작업 진행 중)와
// 폐기(취소·전환)를 같은 null 로 뭉개면 전 진입점이 무반응이 된다.
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  BUSY_MAX_DURATION_MS,
  REJECT_NOTICE_BURST_MS,
  busyRejectedMessage,
  useBusyTask,
  type ExclusiveOutcome,
} from '@/features/label/hooks/useBusyTask';
import { useLabelStore, type BusyState } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

/** 외부에서 resolve/reject 를 제어할 수 있는 지연 프라미스. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** ok 결과의 값만 꺼낸다(테스트 가독성). ok 가 아니면 null. */
function valueOf<T>(outcome: ExclusiveOutcome<T> | null): T | null {
  return outcome !== null && outcome.status === 'ok' ? outcome.value : null;
}

beforeEach(() => {
  useLabelStore.getState().reset();
  useLabelStore.setState({ busy: null, busyGeneration: 0 });
  useUiStore.setState({ toasts: [] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useBusyTask.runExclusive', () => {
  it('요청_성공시_busy가_해제된다', async () => {
    // given
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));

    // when
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      out = await result.current.runExclusive('AI_DETECT', { srcSn: 11 }, async () => 'ok');
    });

    // then
    expect(out).toEqual({ status: 'ok', value: 'ok' });
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('요청_예외시에도_busy가_해제된다', async () => {
    // given
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));

    // when
    await act(async () => {
      await expect(
        result.current.runExclusive('AI_DETECT', { srcSn: 11 }, async () => {
          throw new Error('network down');
        }),
      ).rejects.toThrow('network down');
    });

    // then
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('결과_병합_단계에서_예외가_나도_busy가_해제된다', async () => {
    // given: 네트워크는 성공했지만 병합(후처리)에서 실패하는 작업
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const merge = vi.fn(() => {
      throw new Error('merge failed');
    });

    // when
    await act(async () => {
      await expect(
        result.current.runExclusive('AI_DETECT', { srcSn: 11 }, async () => {
          await Promise.resolve('payload');
          merge();
          return 'unreachable';
        }),
      ).rejects.toThrow('merge failed');
    });

    // then: 병합 예외도 보호 구간 안이라 busy 가 풀리고 재클릭이 가능하다.
    expect(merge).toHaveBeenCalledTimes(1);
    expect(useLabelStore.getState().busy).toBeNull();
    let retried: ExclusiveOutcome<boolean> | null = null;
    await act(async () => {
      retried = await result.current.runExclusive('AI_DETECT', { srcSn: 11 }, async () => true);
    });
    expect(valueOf(retried)).toBe(true);
  });

  it('busy_진행중_다른_작업_시작_요청은_거부이며_폐기와_구분된다', async () => {
    // given: 미완료 작업 1건 진행 중
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const first = deferred<string>();
    let firstRun: Promise<ExclusiveOutcome<string>> = Promise.resolve({ status: 'discarded' });
    act(() => {
      firstRun = result.current.runExclusive('AI_DETECT', { srcSn: 11 }, () => first.promise);
    });

    // when: 다른 종류 작업 시작 시도
    const secondFn = vi.fn(async () => 'second');
    let second: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      second = await result.current.runExclusive('SAVE', { srcSn: 11 }, secondFn);
    });

    // then: 폐기가 아니라 '거부'로 식별되고(무엇이 막았는지 포함), fn 은 실행조차 되지 않는다.
    expect(second).toEqual({ status: 'rejected', blockedBy: 'AI_DETECT' });
    expect(secondFn).not.toHaveBeenCalled();

    await act(async () => {
      first.resolve('first');
      await firstRun;
    });
  });

  it('첫_토큰이_0이어도_유효한_시작으로_처리된다', async () => {
    // given: 세션 첫 작업(토큰 0) — falsy 토큰 함정
    useLabelStore.setState({ busy: null, busyGeneration: 0 });
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const fn = vi.fn(async () => 'ran');

    // when
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      out = await result.current.runExclusive('SAVE', { srcSn: 11 }, fn);
    });

    // then
    expect(fn).toHaveBeenCalledTimes(1);
    expect(valueOf(out)).toBe('ran');
  });

  it('취소_후_도착한_결과는_같은_프레임이어도_폐기된다', async () => {
    // given: 같은 프레임에서 진행 중인 작업
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const net = deferred<string>();
    const merge = vi.fn();
    let run: Promise<ExclusiveOutcome<string>> = Promise.resolve({ status: 'discarded' });
    act(() => {
      run = result.current.runExclusive('AI_SEGMENT', { srcSn: 11 }, async (isAlive) => {
        const value = await net.promise;
        if (!isAlive()) return value; // 병합 skip — 취소된 요청
        merge();
        return value;
      });
    });

    // when: 취소 후 응답 도착
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      net.resolve('late');
      out = await run;
    });

    // then: 호출측에는 폐기가 반환되고 병합도 수행되지 않는다.
    expect(out).toEqual({ status: 'discarded' });
    expect(merge).not.toHaveBeenCalled();
  });

  it('취소된_요청의_예외는_폐기되어_호출측_에러경로에_도달하지_않는다', async () => {
    // given: 프레임 A 저장이 진행 중 (409 등으로 실패할 예정)
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const net = deferred<string>();
    let run: Promise<ExclusiveOutcome<string>> = Promise.resolve({ status: 'discarded' });
    act(() => {
      run = result.current.runExclusive('SAVE', { srcSn: 11 }, async () => net.promise);
    });

    // when: 사용자가 다른 프레임으로 이동(취소) → 그 뒤 A 요청이 실패
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      net.reject(new Error('conflict 409'));
      out = await run;
    });

    // then: 예외가 전파되면 지금 보고 있는 프레임에 남의 충돌 다이얼로그가 뜬다 → 폐기해야 한다.
    expect(out).toEqual({ status: 'discarded' });
  });

  it('취소_직후_같은_작업을_다시_실행하면_실제로_재요청된다', async () => {
    // given
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const fn = vi.fn(() => deferred<string>().promise); // 첫 요청은 영원히 미완
    act(() => {
      void result.current.runExclusive('AI_DETECT', { srcSn: 11 }, fn);
    });
    expect(fn).toHaveBeenCalledTimes(1);

    // when: 취소 후 즉시 재실행
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    const second = deferred<string>();
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      const run = result.current.runExclusive('AI_DETECT', { srcSn: 11 }, () => second.promise);
      second.resolve('again');
      out = await run;
    });

    // then: 유령 잠금 없이 실제로 재요청된다.
    expect(valueOf(out)).toBe('again');
  });

  it('reset_호출시_진행중_busy가_취소되고_이후_응답이_폐기된다', async () => {
    // given
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const net = deferred<string>();
    const merge = vi.fn();
    let run: Promise<ExclusiveOutcome<string>> = Promise.resolve({ status: 'discarded' });
    act(() => {
      run = result.current.runExclusive('SAVE', { srcSn: 11 }, async (isAlive) => {
        const value = await net.promise;
        if (isAlive()) merge();
        return value;
      });
    });

    // when: 비식별 신고 성공/언마운트 등으로 store 전체 초기화
    act(() => {
      useLabelStore.getState().reset();
    });
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      net.resolve('late');
      out = await run;
    });

    // then
    expect(out).toEqual({ status: 'discarded' });
    expect(merge).not.toHaveBeenCalled();
  });

  it('다른_프레임으로_이동하면_이전_프레임_busy가_현재_화면에_적용되지_않는다', async () => {
    // given: A 프레임에서 시작한 작업
    const { result, rerender } = renderHook(({ srcSn }: { srcSn: number }) => useBusyTask({ srcSn }), {
      initialProps: { srcSn: 11 },
    });
    const net = deferred<string>();
    const merge = vi.fn();
    let run: Promise<ExclusiveOutcome<string>> = Promise.resolve({ status: 'discarded' });
    act(() => {
      run = result.current.runExclusive('AI_TRACK', { srcSn: 11 }, async (isAlive) => {
        const value = await net.promise;
        if (isAlive()) merge();
        return value;
      });
    });

    // when: B 프레임으로 이동
    rerender({ srcSn: 22 });

    // then: B 화면은 busy 가 아니며(유령 잠금 없음) A 응답은 폐기된다.
    expect(useLabelStore.getState().busy).toBeNull();
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      net.resolve('late');
      out = await run;
    });
    expect(out).toEqual({ status: 'discarded' });
    expect(merge).not.toHaveBeenCalled();
  });

  it('프레임_전환_effect가_돌기_전_도착한_이전_프레임_응답도_폐기된다', async () => {
    // given: A 프레임 작업 진행 중. 토큰을 죽이는 것은 passive effect 라, 커밋 직후 마이크로태스크
    //   에서 응답이 오면 토큰이 아직 살아 있다 — 그 창을 렌더 시점 프레임 참조로 닫아야 한다.
    const { result, rerender } = renderHook(({ srcSn }: { srcSn: number }) => useBusyTask({ srcSn }), {
      initialProps: { srcSn: 11 },
    });
    const net = deferred<string>();
    const merge = vi.fn();
    let run: Promise<ExclusiveOutcome<string>> = Promise.resolve({ status: 'discarded' });
    act(() => {
      run = result.current.runExclusive('AI_SEGMENT', { srcSn: 11 }, async (isAlive) => {
        const value = await net.promise;
        if (isAlive()) merge();
        return value;
      });
    });
    const aliveBusy = useLabelStore.getState().busy as BusyState;
    const aliveGeneration = useLabelStore.getState().busyGeneration;

    // when: B 프레임 렌더 후, effect 가 아직 취소를 수행하지 않은 상태를 재현(취소분 복원).
    rerender({ srcSn: 22 });
    act(() => {
      useLabelStore.setState({ busy: aliveBusy, busyGeneration: aliveGeneration });
    });
    expect(useLabelStore.getState().isTokenAlive(aliveBusy.token)).toBe(true); // 토큰은 살아 있다
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      net.resolve('late');
      out = await run;
    });

    // then: 토큰이 살아 있어도 현재 화면 프레임이 다르면 병합하지 않는다.
    expect(out).toEqual({ status: 'discarded' });
    expect(merge).not.toHaveBeenCalled();
  });

  it('프레임_전환_뒤_뒤늦게_출발한_이전_프레임_요청은_시작조차_하지_않는다', async () => {
    // given: A 프레임에서 실행을 눌렀지만 실제 시작 전에 B 프레임으로 이동한 상황
    const { result, rerender } = renderHook(({ srcSn }: { srcSn: number }) => useBusyTask({ srcSn }), {
      initialProps: { srcSn: 11 },
    });
    rerender({ srcSn: 22 });
    const fn = vi.fn(async () => 'stale');

    // when: 지난 프레임(11) 컨텍스트로 뒤늦게 출발
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      out = await result.current.runExclusive('AI_TRACK', { srcSn: 11 }, fn);
    });

    // then: 요청 자체가 나가지 않는다(거부가 아니라 폐기 — 안내 불필요).
    expect(out).toEqual({ status: 'discarded' });
    expect(fn).not.toHaveBeenCalled();
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('언마운트된_이전_영상의_지연_응답은_현재_영상_작업본에_반영되지_않는다', async () => {
    // given: A 영상에서 시작한 작업
    const { result, unmount } = renderHook(() => useBusyTask({ srcSn: 11 }));
    const net = deferred<string>();
    const merge = vi.fn();
    let run: Promise<ExclusiveOutcome<string>> = Promise.resolve({ status: 'discarded' });
    act(() => {
      run = result.current.runExclusive('AI_DETECT', { srcSn: 11 }, async (isAlive) => {
        const value = await net.promise;
        if (isAlive()) merge();
        return value;
      });
    });

    // when: 페이지 언마운트(reset) 후 B 영상 진입 — 그 뒤 A 응답 도착
    unmount();
    act(() => {
      useLabelStore.getState().reset();
    });
    let out: ExclusiveOutcome<string> | null = null;
    await act(async () => {
      net.resolve('late');
      out = await run;
    });

    // then
    expect(out).toEqual({ status: 'discarded' });
    expect(merge).not.toHaveBeenCalled();
  });

  it('최대_지속시간_초과시_busy가_자동_해제된다', async () => {
    // given: 응답이 영원히 오지 않는 작업(fail-safe 대상)
    vi.useFakeTimers();
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    act(() => {
      void result.current.runExclusive('AI_TRACK', { srcSn: 11 }, () => deferred<string>().promise);
    });
    expect(useLabelStore.getState().busy).not.toBeNull();

    // when
    act(() => {
      vi.advanceTimersByTime(BUSY_MAX_DURATION_MS + 1);
    });

    // then: 화면이 영구 잠기지 않도록 자동 해제된다.
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('시작한_훅이_언마운트돼도_그_작업의_fail_safe는_살아있다', () => {
    // given: 도구 전환으로 시작 컴포넌트(예: 추적 도구)가 언마운트되는 상황.
    //   타이머 소유가 훅 생명주기에 묶여 있으면 그 작업만 자동 해제 없이 영구 잠긴다.
    vi.useFakeTimers();
    const { result, unmount } = renderHook(() => useBusyTask({ srcSn: 11 }));
    act(() => {
      void result.current.runExclusive('AI_TRACK', { srcSn: 11 }, () => deferred<string>().promise);
    });

    // when: 훅만 언마운트(작업은 계속 진행 중)
    unmount();
    act(() => {
      vi.advanceTimersByTime(BUSY_MAX_DURATION_MS + 1);
    });

    // then
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('언마운트_후에도_옛_타이머가_새_busy를_해제하지_않는다', () => {
    // given
    vi.useFakeTimers();
    const { result, unmount } = renderHook(() => useBusyTask({ srcSn: 11 }));
    act(() => {
      void result.current.runExclusive('AI_TRACK', { srcSn: 11 }, () => deferred<string>().promise);
    });

    // when: 언마운트 후 새 작업 시작 → 옛 타이머가 토큰 검사 없이 돌면 새 busy 를 끊어버린다.
    unmount();
    act(() => {
      useLabelStore.getState().cancelBusy();
      useLabelStore.getState().beginBusy('SAVE', { srcSn: 22 });
      vi.advanceTimersByTime(BUSY_MAX_DURATION_MS + 1);
    });

    // then
    expect(useLabelStore.getState().busy?.kind).toBe('SAVE');
  });
});

describe('useBusyTask.runExclusiveOrNotify — 거부 안내', () => {
  it('거부되면_무엇이_진행중인지_안내하고_null을_돌려준다', async () => {
    // given: AI 추적 진행 중(수 분 소요)
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    act(() => {
      void result.current.runExclusive('AI_TRACK', { srcSn: 11 }, () => deferred<string>().promise);
    });

    // when: 사용자가 저장(Ctrl+S)
    const fn = vi.fn(async () => 'saved');
    let out: string | null = 'not-null';
    await act(async () => {
      out = await result.current.runExclusiveOrNotify('SAVE', { srcSn: 11 }, fn);
    });

    // then: 요청은 나가지 않고, "왜 아무 일도 없는지"를 사용자에게 알린다(모델명 미노출).
    expect(out).toBeNull();
    expect(fn).not.toHaveBeenCalled();
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toBe(busyRejectedMessage('AI_TRACK'));
    expect(toasts[0].message).toContain('AI 추적');
    expect(toasts[0].message).not.toMatch(/SAM|YOLO/i);
  });

  it('폐기는_무음이며_안내하지_않는다', async () => {
    // given: 프레임 전환으로 폐기될 요청
    const { result, rerender } = renderHook(({ srcSn }: { srcSn: number }) => useBusyTask({ srcSn }), {
      initialProps: { srcSn: 11 },
    });
    rerender({ srcSn: 22 });

    // when
    let out: string | null = 'not-null';
    await act(async () => {
      out = await result.current.runExclusiveOrNotify('SAVE', { srcSn: 11 }, async () => 'stale');
    });

    // then: 사용자가 의도적으로 떠난 작업이라 안내가 필요 없다.
    expect(out).toBeNull();
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });

  it('같은_종류가_자기자신을_막은_거부는_무음이다', async () => {
    // given: AI 분할이 진행 중(즉시 그리기 — 프리뷰 요청 in-flight)
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    act(() => {
      void result.current.runExclusive('AI_SEGMENT', { srcSn: 11 }, () => deferred<string>().promise);
    });

    // when: 사용자가 다음 점을 찍어 같은 종류 요청이 다시 시도된다(정상 동선 — 점은 누적된다)
    let out: string | null = 'not-null';
    await act(async () => {
      out = await result.current.runExclusiveOrNotify('AI_SEGMENT', { srcSn: 11 }, async () => 'x');
    });

    // then: 사용자가 취할 조치가 없는 자기 거부라 안내하지 않는다(구 동작: 매 클릭 경고 토스트).
    expect(out).toBeNull();
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });

  it('안내_후_잠시_뒤_사용자가_다시_시도하면_다시_안내한다', async () => {
    // given: 저장 진행 중 — 다른 종류 요청은 거부되고 안내가 뜬다.
    vi.useFakeTimers();
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    act(() => {
      void result.current.runExclusive('SAVE', { srcSn: 11 }, () => deferred<string>().promise);
    });
    await act(async () => {
      await result.current.runExclusiveOrNotify('AI_SEGMENT', { srcSn: 11 }, async () => 'a');
    });
    expect(useUiStore.getState().toasts).toHaveLength(1);

    // when: 안내를 보고 잠시 뒤(버스트 창 이후) 사용자가 명시적으로 다시 누른다
    await act(async () => {
      vi.advanceTimersByTime(REJECT_NOTICE_BURST_MS + 1);
      await result.current.runExclusiveOrNotify('AI_SEGMENT', { srcSn: 11 }, async () => 'b');
    });

    // then: 명시적 재시도까지 삼키면 화면이 고장난 것처럼 보인다 — 다시 안내해야 한다.
    expect(useUiStore.getState().toasts).toHaveLength(2);
  });

  it('연속_거부시_안내가_폭주하지_않는다', async () => {
    // given: 진행 중 작업 1건 + 즉시 그리기처럼 연속으로 거부되는 요청들
    const { result } = renderHook(() => useBusyTask({ srcSn: 11 }));
    act(() => {
      void result.current.runExclusive('SAVE', { srcSn: 11 }, () => deferred<string>().promise);
    });

    // when
    await act(async () => {
      await result.current.runExclusiveOrNotify('AI_SEGMENT', { srcSn: 11 }, async () => 'a');
      await result.current.runExclusiveOrNotify('AI_SEGMENT', { srcSn: 11 }, async () => 'b');
      await result.current.runExclusiveOrNotify('AI_SEGMENT', { srcSn: 11 }, async () => 'c');
    });

    // then
    expect(useUiStore.getState().toasts).toHaveLength(1);
  });
});
