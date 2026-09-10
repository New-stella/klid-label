// 재생이 끊겼을 때 서명 주소를 다시 받아 재시도한다 — <b>상한 안에서만</b>.
// [@design API-114] [@design SCREEN-006] [@design SEQ-036]
//
// 왜 상한이 필요한가 — 재생이 실패할 때마다 주소를 다시 받아 물리면, 실패 사유가 「만료」가 아니라
// 「그 주소로는 영원히 못 받는다」인 경우(예: 접두를 빠뜨려 다른 경로 공간으로 나가는 경우)
// 발급→실패→발급이 끝없이 돈다. 실측으로 7초에 150회 넘는 요청이 나갔고, 매 요청이 새 서명을
// 만들어 서버 쪽 부담도 함께 늘었다.
//
// ★ 상한에 이르면 <b>멈추고 사용자에게 재생 실패를 알린다</b>. 조용히 멈추면 화면은 「불러오는 중」
//   에 머물러, 사용자가 기다리면 되는 상태와 구분할 수 없다.
//
// ★★ 세는 축은 <b>연속 실패</b>다 — 생애 누적이 아니다. 서명 수명은 짧고(기본 60초) 주기 갱신이
//    없어, 한 영상을 몇 분간 탐색하며 마킹하는 <b>정상 동선</b>에서 만료는 몇 번이고 일어난다.
//    누적으로 세면 네 번째 만료에서 회복이 영구히 막혀 새로고침 말고는 길이 없다. 그래서
//    「재생이 실제로 회복되면」 예산을 되돌린다. 폭주(잘못된 경로·권한 실패)는 회복이 한 번도
//    일어나지 않으므로 예산이 되돌아오지 않고 상한 보호가 그대로 유지된다.
import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 재발급 상한 — <b>연속 실패</b> 횟수다(재생이 회복되면 0으로 되돌아간다).
 *
 * <p>근거: 정상 동선에서 재발급이 필요한 경우는 「서명이 재생 도중 만료됐다」 하나이고 그때는
 * 1회로 회복된다. 3회는 그 위에 순간적인 실패(만료와 재생 시작이 겹치는 경합, 일시적 네트워크
 * 오류)를 두 번 더 견디는 여유이며, 그 이상 <b>회복 없이</b> 반복되는 실패는 사용자가 기다려서
 * 풀리는 종류가 아니다. 값을 키우면 잘못된 주소로 나가는 폭주 구간이 그만큼 길어진다.
 */
export const STREAM_REISSUE_LIMIT = 3;

interface UseStreamPlaybackRetryOptions {
  /** 서명 주소 재발급(보통 조회 훅의 `refetch`). */
  reissue: () => Promise<unknown>;
  /**
   * 상한에 이르러 재시도를 멈춘 순간 <b>한 번만</b> 불린다.
   *
   * <p>알림 수단은 화면이 정한다 — 이 훅은 「멈췄다」는 사실만 알리고, 이미 그 화면이 쓰는
   * 안내 표면을 재사용하게 둔다(새 표면을 만들지 않는다).
   */
  onExhausted?: () => void;
  /** 대상이 바뀌면 횟수를 되돌린다(다른 영상은 다른 예산이다). */
  resetKey?: unknown;
}

interface UseStreamPlaybackRetryResult {
  /** 재생 요소의 로드 실패 콜백에 그대로 연결한다. */
  handleSrcError: () => void;
  /**
   * 재생이 <b>실제로 회복</b>됐을 때 불러 예산을 되돌린다(재생 요소의 회복 신호에 연결한다).
   *
   * <p>회복이 예산을 되돌리지 않으면 상한이 「생애 누적」이 되어, 만료가 잦은 정상 동선에서
   * 회복 경로가 영구히 닫힌다.
   */
  handlePlaybackRecovered: () => void;
  /** 상한에 이르러 더는 재시도하지 않는 상태 — 화면이 실패 안내를 그리는 근거. */
  exhausted: boolean;
}

/**
 * 재생 실패 → 서명 주소 재발급 재시도를 <b>연속 실패 상한</b>까지만 수행한다.
 *
 * <p>진행 중 재발급이 있으면 추가 실패를 무시한다(구 동작 유지) — 그러지 않으면 한 번의 실패로
 * 여러 발이 동시에 나간다. 여기에 <b>연속 실패</b> 상한이 더해져, 회복되지 않는 실패는 유한한
 * 횟수 안에 멈춘다.
 */
export function useStreamPlaybackRetry({
  reissue,
  onExhausted,
  resetKey,
}: UseStreamPlaybackRetryOptions): UseStreamPlaybackRetryResult {
  // 회복 없이 이어진 재발급 횟수. 렌더를 유발하지 않아야 해서 ref 다(재발급 자체가 src 를 바꿔
  // 렌더한다).
  const attemptsRef = useRef(0);
  const inFlightRef = useRef(false);
  const [exhausted, setExhausted] = useState(false);
  // 통지 1회 보장은 <b>ref</b> 가 한다 — 상태 updater 안에서 부작용을 내면 StrictMode 의
  // 이중 호출로 같은 통지가 두 번 나간다.
  const exhaustedRef = useRef(false);

  // 콜백은 자주 갈리므로 최신 것을 ref 로 들고 있는다 — 의존성에 넣으면 handleSrcError 가
  // 매 렌더 새로 만들어져 재생 요소의 이벤트 배선이 흔들린다.
  const onExhaustedRef = useRef(onExhausted);
  onExhaustedRef.current = onExhausted;
  const reissueRef = useRef(reissue);
  reissueRef.current = reissue;

  useEffect(() => {
    attemptsRef.current = 0;
    inFlightRef.current = false;
    exhaustedRef.current = false;
    setExhausted(false);
  }, [resetKey]);

  const handleSrcError = useCallback(() => {
    if (inFlightRef.current) return;

    if (attemptsRef.current >= STREAM_REISSUE_LIMIT) {
      // 회복 한 번 없이 예산을 다 쓰고도 또 실패했다 — 여기서 멈춘다. 통지는 한 번만.
      if (!exhaustedRef.current) {
        exhaustedRef.current = true;
        setExhausted(true);
        onExhaustedRef.current?.();
      }
      return;
    }

    attemptsRef.current += 1;
    inFlightRef.current = true;
    void reissueRef.current().finally(() => {
      inFlightRef.current = false;
    });
  }, []);

  const handlePlaybackRecovered = useCallback(() => {
    // 흔한 경로(정상 재생 중 반복해서 오는 회복 신호)에서는 아무것도 하지 않는다 — 되돌릴 예산이
    // 없는데 상태를 건드리면 재생 중 내내 불필요한 렌더가 난다.
    if (attemptsRef.current === 0 && !exhaustedRef.current) return;

    attemptsRef.current = 0;
    if (exhaustedRef.current) {
      // 회복했으면 「멈춤」도 함께 푼다 — 예산만 되돌리고 멈춤을 남기면 다시 실패해도 재발급이
      // 일어나지 않아, 되돌린 예산이 쓰이지 못한다.
      exhaustedRef.current = false;
      setExhausted(false);
    }
  }, []);

  return { handleSrcError, handlePlaybackRecovered, exhausted };
}
