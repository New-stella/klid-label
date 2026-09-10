/**
 * 재생 실패 → 서명 주소 재발급 재시도의 <b>예산 축</b>.
 * [@design API-114] [@design SCREEN-006] [@design SCREEN-045]
 *
 * <h3>왜 「연속 실패」인가 (이 파일이 지키는 것)</h3>
 * 서명 수명은 짧고(기본 60초) 주기 갱신이 없어, 한 영상을 몇 분간 탐색하며 마킹하는 정상 동선에서
 * 만료는 몇 번이고 일어난다. 예산을 <b>생애 누적</b>으로 세면 네 번째 만료에서 회복 경로가 영구히
 * 닫혀 새로고침 말고는 길이 없다. 그래서 「재생이 실제로 회복되면」 예산을 되돌린다.
 * 반대로 폭주(잘못된 경로·권한 실패)는 회복이 <b>한 번도</b> 일어나지 않으므로 상한 보호가 그대로
 * 유지된다 — 두 성질을 함께 못박는다(하나만 두면 반대쪽으로 되돌리기 쉽다).
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { act, renderHook } from '@testing-library/react';

import {
  STREAM_REISSUE_LIMIT,
  useStreamPlaybackRetry,
} from '../useStreamPlaybackRetry';

/** 재발급 한 발이 끝날 때까지 기다린다 — 진행 중이면 다음 실패가 무시되므로 반드시 비워야 한다. */
async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('useStreamPlaybackRetry — 연속 실패 상한', () => {
  // 형을 명시한다 — `ReturnType<typeof vi.fn>` 는 반환이 unknown 이라 훅의 `() => Promise<unknown>`
  // 에 맞지 않아 시험은 통과하는데 `tsc` 만 빨개진다(vitest 는 vite 변환이라 형을 보지 않는다).
  let reissue: Mock<[], Promise<unknown>>;
  let onExhausted: Mock<[], void>;

  beforeEach(() => {
    reissue = vi.fn<[], Promise<unknown>>().mockResolvedValue(undefined);
    onExhausted = vi.fn<[], void>();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function setup(resetKey: unknown = 'raw-42') {
    return renderHook(
      ({ key }: { key: unknown }) =>
        useStreamPlaybackRetry({ reissue, onExhausted, resetKey: key }),
      { initialProps: { key: resetKey } },
    );
  }

  async function fail(result: { current: { handleSrcError: () => void } }) {
    act(() => result.current.handleSrcError());
    await flush();
  }

  function recover(result: { current: { handlePlaybackRecovered: () => void } }) {
    act(() => result.current.handlePlaybackRecovered());
  }

  it('상한까지_재발급하고_그_이후에는_멈추며_통지는_한_번뿐이다', async () => {
    const { result } = setup();

    for (let i = 0; i < STREAM_REISSUE_LIMIT + 3; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }

    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT);
    expect(result.current.exhausted).toBe(true);
    expect(onExhausted).toHaveBeenCalledTimes(1);
  });

  it('★상한_이후에는_시간이_지나도_재발급하지_않는다', async () => {
    // 지연 없이 「더 나가지 않는다」를 단언하면 나중에 백오프(setTimeout)가 들어와도 통과한다.
    // 그래서 상한 분기를 <b>가짜 시계 아래에서</b> 밟고 시간을 크게 진행시킨 뒤 다시 센다.
    const { result } = setup();
    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }
    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT);

    vi.useFakeTimers();
    act(() => result.current.handleSrcError()); // 상한 분기
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });

    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT);
  });

  it('★재생이_회복되면_예산이_되살아나_다시_상한까지_재시도한다', async () => {
    const { result } = setup();

    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }
    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT);

    // when: 새 주소로 재생이 실제로 회복됐다
    recover(result);

    // then: 예산이 되돌아와 다시 상한만큼 재발급한다(만료가 잦은 정상 동선)
    for (let i = 0; i < STREAM_REISSUE_LIMIT; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }
    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT * 2);
    expect(onExhausted).not.toHaveBeenCalled();
    expect(result.current.exhausted).toBe(false);
  });

  it('★성공_재생이_한_번도_없는_연속_실패는_여전히_상한에서_멈춘다_폭주_보호', async () => {
    // 잘못된 경로·권한 실패에서는 회복 신호(canplay)가 오지 않는다 — 예산이 되돌아올 일이 없다.
    const { result } = setup();

    for (let i = 0; i < STREAM_REISSUE_LIMIT * 5; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }

    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT);
    expect(onExhausted).toHaveBeenCalledTimes(1);
  });

  it('회복은_멈춤_상태도_함께_푼다_되돌린_예산이_실제로_쓰인다', async () => {
    const { result } = setup();
    for (let i = 0; i < STREAM_REISSUE_LIMIT + 1; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }
    expect(result.current.exhausted).toBe(true);

    recover(result);
    expect(result.current.exhausted).toBe(false);

    await fail(result);
    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT + 1);
  });

  it('회복_신호만_계속_와도_예산은_그대로다_흔한_경로에서_상태를_건드리지_않는다', async () => {
    const { result } = setup();

    for (let i = 0; i < 5; i += 1) recover(result);
    for (let i = 0; i < STREAM_REISSUE_LIMIT + 2; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }

    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT);
  });

  it('대상이_바뀌면_예산이_되돌아간다_다른_영상은_다른_예산이다', async () => {
    const { result, rerender } = setup('raw-42');
    for (let i = 0; i < STREAM_REISSUE_LIMIT + 1; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      await fail(result);
    }
    expect(result.current.exhausted).toBe(true);

    rerender({ key: 'raw-43' });
    expect(result.current.exhausted).toBe(false);

    await fail(result);
    expect(reissue).toHaveBeenCalledTimes(STREAM_REISSUE_LIMIT + 1);
  });

  it('진행_중_재발급이_있으면_추가_실패는_무시된다_한_번의_실패로_여러_발이_나가지_않는다', () => {
    // 응답을 붙잡아 둔다 — in-flight 창을 사람이 만들어야 관측된다.
    const holder = { release: () => {} };
    reissue.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          holder.release = resolve;
        }),
    );

    const { result } = setup();
    act(() => result.current.handleSrcError());
    act(() => result.current.handleSrcError());
    act(() => result.current.handleSrcError());

    expect(reissue).toHaveBeenCalledTimes(1);
    act(() => holder.release());
  });
});
