// 라벨링 장시간 작업 — **취소는 실제로 요청을 끊고, 대기 상한은 종류별이며, 터지면 알린다.**
//
// 이 파일이 고정하는 것(사용자가 겪는 것 기준):
//  1. 취소하면 **서버로 가는 요청이 끊긴다**. 화면 안에서만 결과를 버리면 서버는 계속 돌며
//     추론 자원을 물고 있고, 사용자는 "취소했는데 왜 GPU 가 계속 도나" 를 알 수 없다.
//  2. **취소는 오류가 아니다** — 오류 안내를 띄우지 않는다(내려받기 경로와 같은 규칙:
//     자기가 중단을 걸었는지로 판정하고, 걸었으면 조용히 정상 종료).
//  3. 취소한 뒤 **다시 실행할 수 있다**(유령 잠금 없음).
//  4. 대기 상한은 **작업 종류마다 다르다** — 공용 상수 하나를 공유하면 올리면 전역 완화,
//     두면 추적이 죽는다.
//  5. 상한이 터지면 **조용히 사라지지 않는다** — 사용자는 성공도 실패도 못 보는 상태로 남지 않는다.

import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as labelApi from '@/features/label/api';
import { useBusyTask } from '@/features/label/hooks/useBusyTask';
import { aiWaitBusyMaxMs, publishAiWaitBudgets, resetAiWaitBudgets } from '@/features/label/aiBudget';
import { BUSY_KIND_TIMEOUT_MESSAGE } from '@/features/label/busyPolicy';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

const SRC_SN = 42;

function wrapper({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

beforeEach(() => {
  useLabelStore.getState().reset();
  useUiStore.setState({ toasts: [] });
});

afterEach(() => {
  resetAiWaitBudgets();
  vi.useRealTimers();
});

describe('취소 — 서버 요청까지 끊는다', () => {
  it('취소하면_진행_중인_요청에_중단_신호가_간다', async () => {
    // given: 끝나지 않는 작업이 중단 신호를 지켜본다
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let seenSignal: AbortSignal | undefined;
    let settle: (() => void) | undefined;

    const running = result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, (_alive, signal) => {
      seenSignal = signal;
      return new Promise<void>((resolve) => {
        settle = resolve;
      });
    });
    await waitFor(() => expect(seenSignal).toBeDefined());
    expect(seenSignal?.aborted).toBe(false);

    // when: 사용자가 취소
    act(() => {
      useLabelStore.getState().cancelBusy();
    });

    // then: 요청이 실제로 끊긴다 — 화면 안에서만 버리는 것이 아니다
    await waitFor(() => expect(seenSignal?.aborted).toBe(true));
    // 남은 작업을 정리한다 — 끝나지 않은 실행을 두면 다음 테스트의 렌더가 그 여파를 받는다.
    settle?.();
    await running;
  });

  it('취소된_작업은_오류로_처리되지_않는다', async () => {
    // ★ 취소는 정상 종료다. 중단 때문에 난 실패를 오류로 올리면 «내가 멈췄는데 오류 안내가 뜨는»
    //   화면이 된다(내려받기 경로가 이미 갈라 놓은 규칙).
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let reject: ((e: unknown) => void) | undefined;
    let started = false;

    const promise = result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, (_alive, signal) => {
      started = true;
      return new Promise<void>((_res, rej) => {
        reject = rej;
        signal?.addEventListener('abort', () => rej(new Error('canceled')));
      });
    });
    await waitFor(() => expect(started).toBe(true));

    // when
    act(() => {
      useLabelStore.getState().cancelBusy();
    });

    // then: 예외가 전파되지 않고 «폐기» 로 끝난다
    const outcome = await promise;
    expect(outcome.status).toBe('discarded');
    expect(reject).toBeDefined();
    // 오류 안내가 뜨지 않는다 — 어떤 안내도 남지 않아야 «내가 멈췄는데 무슨 일이 났나» 가 안 뜬다
    expect(useUiStore.getState().toasts.filter((t) => t.variant === 'error')).toHaveLength(0);
    expect(useUiStore.getState().toasts).toHaveLength(0);
  });

  it('취소한_뒤_같은_작업을_다시_실행할_수_있다', async () => {
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let firstStarted = false;
    const first = result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, (_a, signal) => {
      firstStarted = true;
      return new Promise<void>((_r, rej) => {
        signal?.addEventListener('abort', () => rej(new Error('canceled')));
      });
    });
    await waitFor(() => expect(firstStarted).toBe(true));

    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await first;

    // when: 다시 실행
    const second = await result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, async () => 'ok');

    // then: 거부되지 않는다(유령 잠금 없음)
    expect(second).toEqual({ status: 'ok', value: 'ok' });
  });

  it('정상_완료한_작업의_결과는_중단으로_뒤집히지_않는다', async () => {
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    const outcome = await result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, async () => 7);
    expect(outcome).toEqual({ status: 'ok', value: 7 });
  });
});

describe('취소 — 서버에도 멈추라고 알린다', () => {
  it('취소하면_같은_식별자로_서버_취소를_요청한다', async () => {
    // ★ 연결을 끊는 것만으로는 서버가 멈추지 않는다(서버 담당이 실험으로 확인한 제약) — 화면이
    //   요청에 실어 보낸 식별자로 취소 API 를 불러야 추론이 실제로 끝난다. 이게 빠지면 «취소했는데
    //   GPU 는 계속 도는» 상태가 되고, 그게 이 과제가 없애려는 낭비 그 자체다.
    const cancelSpy = vi.spyOn(labelApi, 'cancelAiRequest').mockResolvedValue(undefined);
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let seenRequestId: string | undefined;

    const running = result.current.runExclusive(
      'AI_DETECT',
      { srcSn: SRC_SN },
      (_alive, signal, requestId) => {
        seenRequestId = requestId;
        return new Promise<void>((_r, rej) => {
          signal?.addEventListener('abort', () => rej(new Error('canceled')));
        });
      },
    );
    await waitFor(() => expect(seenRequestId).toBeDefined());

    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await running;

    expect(cancelSpy).toHaveBeenCalledWith(seenRequestId);
    cancelSpy.mockRestore();
  });

  it('정상_완료한_작업은_서버_취소를_부르지_않는다', async () => {
    // 오탐이 나면 끝난 추론마다 쓸데없는 왕복이 하나씩 더 붙는다.
    const cancelSpy = vi.spyOn(labelApi, 'cancelAiRequest').mockResolvedValue(undefined);
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });

    await result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, async () => 'ok');

    expect(cancelSpy).not.toHaveBeenCalled();
    cancelSpy.mockRestore();
  });

  it('추론이_아닌_작업은_서버_취소를_부르지_않는다', async () => {
    // 저장·불러오기는 서버의 취소 등록 대상 경로가 아니다 — 부르면 존재하지 않는 식별자로 왕복만 돈다.
    const cancelSpy = vi.spyOn(labelApi, 'cancelAiRequest').mockResolvedValue(undefined);
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let started = false;

    const running = result.current.runExclusive('SAVE', { srcSn: SRC_SN }, (_a, signal) => {
      started = true;
      return new Promise<void>((_r, rej) => {
        signal?.addEventListener('abort', () => rej(new Error('canceled')));
      });
    });
    await waitFor(() => expect(started).toBe(true));

    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await running;

    expect(cancelSpy).not.toHaveBeenCalled();
    cancelSpy.mockRestore();
  });

  it('실행마다_다른_식별자를_받는다', async () => {
    // 겹치면 취소가 **남의 추론**을 끊는다.
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    const ids: (string | undefined)[] = [];
    const capture = async (_a: () => boolean, _s: AbortSignal, requestId: string) => {
      ids.push(requestId);
      return 'ok';
    };

    await result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, capture);
    await result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, capture);

    expect(ids).toHaveLength(2);
    expect(ids[0]).not.toBe(ids[1]);
  });
});

describe('대기 상한 — 종류별로 갈리고, 터지면 알린다', () => {
  it('종류마다_다른_상한에서_해제된다', async () => {
    // given: 탐지는 짧게, 자동 추적은 길게
    vi.useFakeTimers();
    publishAiWaitBudgets({
      autolabel: { baseSec: 10, perFrameSec: 0, ceilingSec: 10 },
      autoTrack: { baseSec: 300, perFrameSec: 0, ceilingSec: 300 },
    });
    const detectMax = aiWaitBusyMaxMs('AI_DETECT');
    const autoTrackMax = aiWaitBusyMaxMs('AI_AUTO_TRACK');
    expect(detectMax).toBeLessThan(autoTrackMax);

    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });

    // when: 자동 추적을 시작하고 «탐지 상한» 만큼 흐르게 한다
    void result.current.runExclusive(
      'AI_AUTO_TRACK',
      { srcSn: SRC_SN },
      () => new Promise<void>(() => {}),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(detectMax + 1_000);
    });

    // then: 아직 살아 있다 — 짧은 종류의 상한에 끌려 죽지 않는다
    expect(useLabelStore.getState().busy).not.toBeNull();

    // when: 자기 상한까지 흐르면
    await act(async () => {
      await vi.advanceTimersByTimeAsync(autoTrackMax);
    });

    // then: 그때 해제된다
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('상한을_넘겨_해제되면_사용자에게_알린다', async () => {
    // ★ 종전에는 조용히 폐기됐다 — 요청이 성공해도 결과가 반영되지 않고 안내도 없어
    //   사용자는 성공도 실패도 못 봤다. 그 무음이 이 라운드가 고치는 결함이다.
    vi.useFakeTimers();
    publishAiWaitBudgets({ autolabel: { baseSec: 5, perFrameSec: 0, ceilingSec: 5 } });
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });

    void result.current.runExclusive(
      'AI_DETECT',
      { srcSn: SRC_SN },
      () => new Promise<void>(() => {}),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(aiWaitBusyMaxMs('AI_DETECT') + 1_000);
    });

    // then: 안내가 남는다
    const messages = useUiStore.getState().toasts.map((t) => t.message);
    expect(messages).toContain(BUSY_KIND_TIMEOUT_MESSAGE.AI_DETECT);
  });

  it('상한을_넘기면_진행_중인_요청도_끊는다', async () => {
    // 상한이 터졌는데 요청이 살아 있으면 서버는 계속 돌고 결과만 버려진다.
    vi.useFakeTimers();
    publishAiWaitBudgets({ autolabel: { baseSec: 5, perFrameSec: 0, ceilingSec: 5 } });
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let seenSignal: AbortSignal | undefined;

    void result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, (_a, signal) => {
      seenSignal = signal;
      return new Promise<void>(() => {});
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(aiWaitBusyMaxMs('AI_DETECT') + 1_000);
    });

    expect(seenSignal?.aborted).toBe(true);
  });

  it('정상_종료한_작업에는_상한_안내가_뜨지_않는다', async () => {
    // 오탐이 나면 사용자는 성공한 작업마다 «중단됐다» 를 본다.
    vi.useFakeTimers();
    publishAiWaitBudgets({ autolabel: { baseSec: 5, perFrameSec: 0, ceilingSec: 5 } });
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });

    await act(async () => {
      await result.current.runExclusive('AI_DETECT', { srcSn: SRC_SN }, async () => 'done');
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(aiWaitBusyMaxMs('AI_DETECT') + 10_000);
    });

    const messages = useUiStore.getState().toasts.map((t) => t.message);
    expect(messages).not.toContain(BUSY_KIND_TIMEOUT_MESSAGE.AI_DETECT);
  });

  it('사용자가_취소한_작업에는_상한_안내가_뜨지_않는다', async () => {
    // 취소와 상한 초과는 다른 사건이다 — 뭉개면 «내가 멈췄는데 시스템이 멈췄다» 고 안내한다.
    vi.useFakeTimers();
    publishAiWaitBudgets({ autolabel: { baseSec: 5, perFrameSec: 0, ceilingSec: 5 } });
    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });

    void result.current.runExclusive(
      'AI_DETECT',
      { srcSn: SRC_SN },
      () => new Promise<void>(() => {}),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(aiWaitBusyMaxMs('AI_DETECT') + 10_000);
    });

    const messages = useUiStore.getState().toasts.map((t) => t.message);
    expect(messages).not.toContain(BUSY_KIND_TIMEOUT_MESSAGE.AI_DETECT);
  });

  it('진행이_이어지는_동안은_상한이_연장된다', async () => {
    // ★ 서버가 요청 하나의 시간 예산을 다 쓰면 그때까지의 결과를 돌려주고, 화면은 남은 프레임을
    //   **이어 보낸다**. 그 이어 보내기는 처음 잡은 상한 밖에서 일어나므로, 연장하지 않으면
    //   잠금이 먼저 풀려 «서버는 계속 잘 돌고 있는데 결과가 버려지는» 상태가 된다.
    vi.useFakeTimers();
    publishAiWaitBudgets({ sam2Track: { baseSec: 10, perFrameSec: 0, ceilingSec: 10 } });
    const firstMax = aiWaitBusyMaxMs('AI_TRACK', 5, 50);

    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let renew: ((ms: number) => void) | undefined;
    void result.current.runExclusive(
      'AI_TRACK',
      { srcSn: SRC_SN },
      (_alive, _signal, _requestId, renewDeadline) => {
        renew = renewDeadline;
        return new Promise<void>(() => {});
      },
      { maxDurationMs: firstMax },
    );
    // 가짜 타이머라 waitFor 를 쓰지 않는다(그 자체가 타이머를 기다린다) — 마이크로태스크만 흘린다.
    await act(async () => {});
    expect(renew).toBeDefined();

    // when: 상한 직전에 «한 조각을 더 끝냈다» 고 알린다
    await act(async () => {
      await vi.advanceTimersByTimeAsync(firstMax - 1_000);
    });
    act(() => renew?.(firstMax));

    // then: 원래 상한을 지나도 살아 있다(연장이 없으면 여기서 죽는다)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });
    expect(useLabelStore.getState().busy).not.toBeNull();
    // 오버레이가 말하는 상한도 함께 늘어난다 — 적용된 상한과 표시된 상한이 갈리면 안내가 거짓이 된다
    expect(useLabelStore.getState().busy?.maxDurationMs).toBeGreaterThan(firstMax);

    // and: 연장분이 다하면 그때 해제된다(연장은 «영원히» 가 아니다)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(firstMax);
    });
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('진행이_계속_보고돼도_절대_상한을_넘으면_해제된다', async () => {
    // ★ 연장이 **응답 하나마다** 걸리면 실질 상한이 사라진다. 서버가 요청마다 1프레임만 진행해도
    //   그때마다 상한이 다시 세어져, 진행 없음 가드에 걸리지 않은 채 화면이 **한 시간 넘게** 잠긴
    //   상태로 남는다(조각 수 × 프레임 수 × 상한). 연장은 «진행이 있는 동안» 이지 «영원히» 가 아니다.
    vi.useFakeTimers();
    publishAiWaitBudgets({ autoTrack: { baseSec: 10, perFrameSec: 0, ceilingSec: 10 } });
    const runMax = aiWaitBusyMaxMs('AI_AUTO_TRACK');

    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    let renew: ((ms: number) => void) | undefined;
    void result.current.runExclusive(
      'AI_AUTO_TRACK',
      { srcSn: SRC_SN },
      (_alive, _signal, _requestId, renewDeadline) => {
        renew = renewDeadline;
        return new Promise<void>(() => {});
      },
      { maxDurationMs: runMax },
    );
    await act(async () => {});
    expect(renew).toBeDefined();

    // 서버가 1초마다 «조금 진행했다» 고 알려 온다 — 그때마다 연장이 걸린다.
    const tickWithProgress = async (totalMs: number) => {
      for (let elapsed = 0; elapsed < totalMs; elapsed += 1_000) {
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1_000);
        });
        act(() => renew?.(runMax));
      }
    };

    // 계획 상한의 2배까지는 살아 있어야 한다 — 정상 이어보내기를 죽이면 안 된다.
    await tickWithProgress(runMax * 2 - 1_000);
    expect(useLabelStore.getState().busy).not.toBeNull();

    // 그러나 무한정 늘어나지는 않는다 — 계획 상한의 10배까지 가면 실질 상한이 없는 것이다.
    await tickWithProgress(runMax * 8);
    expect(useLabelStore.getState().busy).toBeNull();
    // 그리고 조용히 사라지지 않는다 — 사용자는 왜 멈췄는지 알아야 한다.
    expect(useUiStore.getState().toasts.map((t) => t.message)).toContain(
      BUSY_KIND_TIMEOUT_MESSAGE.AI_AUTO_TRACK,
    );
  });

  it('나눠_보내는_작업은_묶음_전체를_덮는_상한을_받는다', async () => {
    // 요청 하나 기준으로 잡으면 두 번째 조각에서 잠금이 먼저 풀려 결과가 조용히 폐기된다.
    vi.useFakeTimers();
    publishAiWaitBudgets({ sam2Track: { baseSec: 0, perFrameSec: 10, ceilingSec: 20 } });
    const onePiece = aiWaitBusyMaxMs('AI_TRACK', 2, 50); // 2건 → 한 조각
    const wholeBatch = aiWaitBusyMaxMs('AI_TRACK', 10, 50); // 10건 → 다섯 조각
    expect(wholeBatch).toBeGreaterThan(onePiece);

    const { result } = renderHook(() => useBusyTask({ srcSn: SRC_SN }), { wrapper });
    void result.current.runExclusive(
      'AI_TRACK',
      { srcSn: SRC_SN },
      () => new Promise<void>(() => {}),
      { maxDurationMs: wholeBatch },
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(onePiece + 1_000);
    });
    expect(useLabelStore.getState().busy).not.toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(wholeBatch);
    });
    expect(useLabelStore.getState().busy).toBeNull();
  });
});
