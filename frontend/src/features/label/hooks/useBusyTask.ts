import { useCallback, useEffect, useRef } from 'react';

import { useLabelStore, type BusyKind } from '@/stores/useLabelStore';

import { BUSY_KIND_NAME } from '../busyPolicy';

import { BLOCK_NOTICE_BURST_MS, useBlockNotice } from './useBlockNotice';

/**
 * busy 최대 지속시간(fail-safe). 이 시간을 넘기면 화면이 영구 잠기는 것을 막기 위해
 * 자동 해제한다. 응답이 뒤늦게 도착해도 토큰이 죽어 있어 결과는 폐기된다.
 */
export const BUSY_MAX_DURATION_MS = 5 * 60 * 1000;

/**
 * 거부 안내 중복 노출 억제 창(ms).
 *
 * Phase 3 P-1 — 차단/거부 안내 dedupe 는 화면 전체에서 **하나의 정책**이다. 이 상수는
 * {@link BLOCK_NOTICE_BURST_MS} 의 별칭이며 별도 정책이 아니다(기존 호출부 호환용 재-export).
 */
export const REJECT_NOTICE_BURST_MS = BLOCK_NOTICE_BURST_MS;

/** busy 에 기록할 컨텍스트 — 어느 프레임을 위한 작업인지. */
export interface BusyContext {
  srcSn?: number;
}

/**
 * 배타 실행 결과. **거부(rejected)와 폐기(discarded)를 반드시 구분한다** — 둘을 같은 null 로
 * 뭉개면 "다른 작업 진행 중이라 아무 일도 안 일어남"과 "취소했으니 조용히 버림"이 호출측에서
 * 구별되지 않아, 사용자가 저장/실행을 눌렀는데 무반응인 화면이 된다.
 *  - ok        : 반영해도 되는 결과.
 *  - rejected  : 다른 작업이 진행 중이라 **시작조차 안 함** → 사용자 안내 필요.
 *  - discarded : 시작했으나 취소·리셋·프레임 전환·자동해제로 무효 → **무음이 정상**.
 */
export type ExclusiveOutcome<T> =
  | { status: 'ok'; value: T }
  | { status: 'rejected'; blockedBy: BusyKind | null }
  | { status: 'discarded' };

/**
 * 거부 안내 문구. 내부 경로/식별자는 담지 않는다(정보 노출 방지).
 * 작업 이름은 {@link BUSY_KIND_NAME} 단일 소스에서 온다 — 모델명(YOLO/SAM/SAM2) 미노출.
 */
export function busyRejectedMessage(blockedBy: BusyKind | null): string {
  return blockedBy === null
    ? '다른 작업이 진행 중입니다. 완료 후 다시 시도하세요.'
    : `${BUSY_KIND_NAME[blockedBy]} 진행 중입니다. 완료 후 다시 시도하세요.`;
}

export interface UseBusyTaskResult {
  /**
   * 장시간 작업을 배타 실행한다.
   *
   * **보호 구간 = 네트워크 + 결과 병합**. 결과를 store/캔버스에 반영하는 후처리까지 `fn` 안에서
   * 끝내야 한다. 병합을 바깥에서 하면 busy 가 먼저 풀려, 미완료 후처리와 재실행이 store 를
   * 동시에 건드린다.
   *
   * - 이미 다른 작업이 진행 중이면 `fn` 을 호출하지 않고 `{ status: 'rejected' }`.
   * - `fn` 완료 시점에 토큰이 죽어 있으면(취소·리셋·프레임 전환·자동 해제) `{ status: 'discarded' }`.
   *   **예외도 동일하게 폐기**된다 — 취소된 요청의 실패가 현재 화면의 에러 다이얼로그를 띄우면
   *   사용자가 보고 있는 프레임의 미저장 작업이 그 안내를 따라 사라진다.
   * - `fn` 에 전달되는 `isAlive()` 로 병합 직전 생존을 확인한다(취소된 요청의 병합 차단).
   * - 성공·실패·예외·병합 예외 어느 경로로 끝나도 busy 는 해제된다.
   */
  runExclusive: <T>(
    kind: BusyKind,
    ctx: BusyContext,
    fn: (isAlive: () => boolean) => Promise<T>,
  ) => Promise<ExclusiveOutcome<T>>;

  /**
   * `runExclusive` 어댑터 — **거부면 안내 토스트**를 띄우고 `null`, 폐기면 조용히 `null`,
   * 성공이면 값을 돌려준다. 호출측(훅/화면)은 `=== null` 하나로 "반영하지 말 것"을 판정한다.
   *
   * 안내 정책:
   *  - **같은 종류가 자기 자신을 막은 거부는 무음**이다. 즉시 그리기처럼 진행 중에 다음 입력을
   *    누적하는 것이 정상 동선인 경로에서, 사용자가 개입할 여지도 없는 안내가 뜨는 것을 막는다.
   *  - 다른 종류가 막은 거부만 안내하고, 동일 사유가 한 상호작용에서 연달아 터지면 짧은 창
   *    (`REJECT_NOTICE_BURST_MS`) 안에서만 묶는다(명시적 재시도는 그대로 안내).
   */
  runExclusiveOrNotify: <T>(
    kind: BusyKind,
    ctx: BusyContext,
    fn: (isAlive: () => boolean) => Promise<T>,
  ) => Promise<T | null>;
}

/**
 * 라벨링 화면 장시간 작업의 배타 실행 래퍼.
 *
 * 진행 상태는 store 의 busy 하나뿐이다 — 훅 로컬 `inflightRef`/state 를 함께 두면 취소가 store 만
 * 풀고 ref 는 원 요청이 끝날 때까지 잠긴 채로 남아 재클릭이 조용히 무시된다(유령 잠금).
 *
 * @param scope 이 훅이 속한 화면의 프레임. srcSn 이 바뀌면(프레임 전환) 이전 프레임의 busy 를
 *              취소해 새 화면이 남의 작업으로 잠기지 않게 한다.
 */
export function useBusyTask(scope: BusyContext = {}): UseBusyTaskResult {
  const { srcSn } = scope;
  // 화면이 지금 보고 있는 프레임 — **렌더 시점에 갱신**한다. effect 로 미루면
  // [프레임 B 렌더 커밋] → [effect flush] 사이의 마이크로태스크(프라미스 continuation)에서
  // 토큰이 아직 살아 있어, 지난 프레임 결과가 현재 화면에 병합된다(거짓 성공 토스트 + 결과 소실).
  const scopeSrcSnRef = useRef<number | undefined>(srcSn);
  scopeSrcSnRef.current = srcSn;
  // 거부 안내 발행 — dedupe 정책은 화면 공통(useBlockNotice) 하나를 쓴다(P-1).
  const pushBlockNotice = useBlockNotice();

  // 프레임 전환 시 이전 프레임 busy 해제(새 화면이 남의 작업으로 잠기지 않게).
  // ⚠ 이 effect 는 화면 잠금 해제용이며 **결과 폐기 판정의 근거가 아니다** — passive effect 라
  //   커밋 직후 마이크로태스크보다 늦게 flush 되기 때문이다. 폐기 판정은 위 scopeSrcSnRef(렌더
  //   시점 갱신)를 함께 보는 isAlive() 가 담당한다.
  useEffect(() => {
    if (srcSn === undefined) return;
    const busy = useLabelStore.getState().busy;
    if (busy !== null && busy.srcSn !== undefined && busy.srcSn !== srcSn) {
      useLabelStore.getState().cancelBusy();
    }
  }, [srcSn]);

  const runExclusive = useCallback(async function run<T>(
    kind: BusyKind,
    ctx: BusyContext,
    fn: (isAlive: () => boolean) => Promise<T>,
  ): Promise<ExclusiveOutcome<T>> {
    // 시작 시점 stale — 프레임이 바뀐 뒤 늦게 출발한 요청(클릭 직후 프레임 전환)은 아예 시작하지
    // 않는다. 시작해버리면 지난 프레임의 결과가 현재 화면에 병합된다.
    if (isStaleScope(ctx.srcSn, scopeSrcSnRef.current)) {
      return { status: 'discarded' };
    }
    const begun = useLabelStore.getState().beginBusy(kind, ctx);
    // 판별 유니온 — 토큰 0 을 falsy 로 오인하지 않는다.
    if (!begun.ok) {
      return { status: 'rejected', blockedBy: useLabelStore.getState().busy?.kind ?? null };
    }
    const { token } = begun;
    // 생존 판정 = 토큰(비동기 축) + 현재 화면 프레임(렌더 축). 두 축을 함께 봐야 effect flush
    // 이전에 도착한 응답도 폐기된다.
    const isAlive = () =>
      !isStaleScope(ctx.srcSn, scopeSrcSnRef.current) &&
      useLabelStore.getState().isTokenAlive(token);

    // fail-safe 타이머는 **시작한 훅의 생명주기와 분리**한다 — 도구 전환으로 시작 컴포넌트가
    // 언마운트돼도(예: 추적 도구) 그 작업의 자동 해제는 살아 있어야 화면이 영구 잠기지 않는다.
    // 토큰 검사가 있어 이미 끝난/취소된 작업의 타이머는 새 busy 를 건드리지 않는다.
    const timer = setTimeout(() => {
      if (!useLabelStore.getState().isTokenAlive(token)) return;
      useLabelStore.getState().cancelBusy();
      // 진단용 — 식별자/좌표 등 데이터는 남기지 않고 작업 종류만 기록한다.
      // eslint-disable-next-line no-console
      console.warn(`[label] busy(${kind}) 최대 지속시간 초과 — 자동 해제`);
    }, BUSY_MAX_DURATION_MS);

    try {
      const result = await fn(isAlive);
      // 취소·리셋·프레임 전환 이후 도착 → 호출측이 병합하지 않도록 폐기.
      return isAlive() ? { status: 'ok', value: result } : { status: 'discarded' };
    } catch (err) {
      // 폐기된 요청의 실패는 현재 화면과 무관하다 — 전파하면 남의 프레임 에러 안내가 뜬다.
      if (!isAlive()) return { status: 'discarded' };
      throw err;
    } finally {
      clearTimeout(timer);
      useLabelStore.getState().endBusy(token);
    }
  }, []);

  const runExclusiveOrNotify = useCallback(
    async function runOrNotify<T>(
      kind: BusyKind,
      ctx: BusyContext,
      fn: (isAlive: () => boolean) => Promise<T>,
    ): Promise<T | null> {
      const outcome = await runExclusive(kind, ctx, fn);
      if (outcome.status === 'ok') return outcome.value;
      if (outcome.status === 'rejected' && outcome.blockedBy !== kind) {
        // 자기 자신(같은 종류)이 막은 거부는 안내하지 않는다 — 진행 인디케이터가 이미 상태를
        // 보여주고 있고, 사용자가 취할 조치도 없다(즉시 그리기 연속 클릭이 정상 동선).
        pushBlockNotice(busyRejectedMessage(outcome.blockedBy));
      }
      return null;
    },
    [runExclusive, pushBlockNotice],
  );

  return { runExclusive, runExclusiveOrNotify };
}

/** 요청 컨텍스트가 현재 화면 프레임과 어긋났는지(지난 프레임 요청). 둘 중 하나라도 미상이면 false. */
function isStaleScope(ctxSrcSn?: number, scopeSrcSn?: number): boolean {
  return ctxSrcSn !== undefined && scopeSrcSn !== undefined && ctxSrcSn !== scopeSrcSn;
}
