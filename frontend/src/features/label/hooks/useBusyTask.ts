import { useCallback, useEffect, useRef } from 'react';

import { useLabelStore, type BusyKind } from '@/stores/useLabelStore';

import { aiWaitBusyMaxMs, busyKindWaitKind } from '../aiBudget';
import { cancelAiRequest, newAiRequestId } from '../api';
import { BUSY_KIND_NAME, notifyBusyTimeout } from '../busyPolicy';

import { BLOCK_NOTICE_BURST_MS, useBlockNotice } from './useBlockNotice';

/**
 * busy 최대 지속시간의 **기본값**(fail-safe). 화면이 영구 잠기는 것을 막는 최후 장치다.
 *
 * ⚠ **더 이상 모든 작업이 이 값을 공유하지 않는다.** 공유하던 시절에는 올리면 전역 완화가 되고
 *   두면 프레임을 훑는 추적이 자기 상한보다 먼저 죽어, 요청이 성공해도 결과가 **조용히 폐기**됐다
 *   (사용자는 성공도 실패도 못 봤다). 이제 AI 작업은 종류마다 서버가 준 대기 예산에서 자기 상한을
 *   받고({@link aiWaitBusyMaxMs}), 이 상수는 예산 축이 없는 작업(저장·불러오기)의 기본값이다.
 *
 * ★ 이 값이 터지는 것은 **정상 경로가 아니다.** 요청은 자기 제한시간에서 스스로 끊기므로, 여기까지
 *   오면 «그 밖의 이유로 멈췄다» 는 뜻이고 그래서 **반드시 사용자에게 알린다**(무음 폐기 금지).
 */
export const BUSY_MAX_DURATION_MS = 5 * 60 * 1000;

/**
 * 진행 관측에 따른 **연장의 절대 배수** — 잠금 전체 시간은 처음 잡은 상한의 이 배수를 넘지 않는다.
 *
 * ★ 왜 필요한가 — 연장({@link BusyTaskFn} 의 `renewDeadline`)은 «응답 하나» 마다 걸린다. 서버가
 *   요청마다 1프레임만 진행해도 그때마다 상한이 다시 세어지므로, 진행 없음 가드에 걸리지 않은 채
 *   실질 상한이 **조각 수 × 프레임 수 × 상한** 으로 불어난다(50프레임이 2개씩 진행되면 화면이
 *   한 시간 넘게 잠긴 채 안전장치가 한 번도 발화하지 않는다). 연장은 «진행이 있는 동안» 이지
 *   «영원히» 가 아니다.
 *
 * ★ 왜 3 인가 — 처음 잡는 상한은 **«각 조각이 요청 한 번에 끝난다»** 는 가정에서 나온 값이다
 *   (`aiWaitBusyMaxMs`). 서버가 예산을 다 써 잘라 보내는 것은 그 가정이 어긋난 것이고, 3배는
 *   «조각당 요청 세 번» 까지 기다려 준다는 뜻이다. 그보다 더 걸린다면 서버가 요청마다 거의
 *   진행하지 못하고 있는 것(예산 설정 이상·과부하)이라 기다림을 끊는 편이 낫다.
 *   ⚠ 고정 분(分)으로 잡지 않는 이유 — 대상 프레임 수에 따라 정당한 소요가 크게 달라져, 고정값은
 *     짧은 작업엔 느슨하고 긴 작업엔 **정상 이어보내기를 죽인다**. 배수는 그 규모를 따라간다.
 * ⚠ 1 로 낮추면 연장이 사실상 사라져, 서버는 잘 돌고 있는데 결과가 버려지던 원래 결함으로 돌아간다.
 */
export const BUSY_RENEW_ABSOLUTE_FACTOR = 3;

/** 그 실행의 잠금이 **어떤 경우에도** 넘지 못하는 총 시간(ms). */
export function busyAbsoluteMaxMs(maxDurationMs: number): number {
  return maxDurationMs * BUSY_RENEW_ABSOLUTE_FACTOR;
}

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
 * 훅 범위 — 화면의 프레임 + **서버 취소 창구의 채널**.
 *
 * ★ `portal` 은 취소 API 경로만 가른다. 포털 채널의 AI 요청은 포털 창구로 나가므로 취소도 포털
 *   창구로 보내야 한다 — 내부 창구로 보내면 403 이고 그 실패는 삼켜지므로 «취소했는데 서버는
 *   계속 돈다» 가 신호 없이 남는다. 판정은 화면의 portalMode 에서 파생해 넘긴다.
 */
export interface BusyScope extends BusyContext {
  portal?: boolean;
}

/** 실행 1건의 옵션. */
export interface BusyRunOptions {
  /**
   * 이 실행의 대기 상한(ms). 생략하면 **작업 종류의 예산**에서 받는다({@link aiWaitBusyMaxMs}).
   *
   * ★ 명시해야 하는 경우는 하나다 — **나눠 보내는 작업**. 잠금 구간이 요청 하나가 아니라 조각
   *   묶음 전체라, 종류 기본값(요청 1건 기준)으로 두면 두 번째 조각에서 잠금이 먼저 풀려
   *   결과가 조용히 폐기된다.
   */
  maxDurationMs?: number;
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

/**
 * 보호 구간에서 도는 작업 본체.
 *
 * @param isAlive        병합 직전 생존 확인(취소·프레임 전환 뒤면 false).
 * @param signal         중단 신호 — 서버로 가는 요청에 실어야 취소가 실제로 끊는다.
 * @param requestId      서버 취소 식별자.
 * @param renewDeadline  **진행이 있었을 때** fail-safe 상한을 그 시점부터 다시 세는 함수(ms).
 *   ★ 나눠 보내는 작업이 서버의 부분 결과를 받아 **이어 보낼 때** 부른다. 부르지 않으면 처음 잡은
 *     상한에서 잠금이 풀려, 서버는 잘 돌고 있는데 결과가 버려진다. 진행 없이 부르면 그건 fail-safe
 *     를 무력화하는 것이므로, **진행을 확인한 자리에서만** 부른다.
 *   ⚠ 연장에도 끝이 있다 — 잠금 전체는 {@link BUSY_RENEW_ABSOLUTE_FACTOR} 배를 넘지 못하며, 넘으면
 *     연장 요청이 곧 fail-safe 발화가 된다(호출측이 따로 셀 필요가 없다).
 */
export type BusyTaskFn<T> = (
  isAlive: () => boolean,
  signal: AbortSignal,
  requestId: string,
  renewDeadline: (ms: number) => void,
) => Promise<T>;

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
    fn: BusyTaskFn<T>,
    opts?: BusyRunOptions,
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
    fn: BusyTaskFn<T>,
    opts?: BusyRunOptions,
  ) => Promise<T | null>;
}

/**
 * 라벨링 화면 장시간 작업의 배타 실행 래퍼.
 *
 * 진행 상태는 store 의 busy 하나뿐이다 — 훅 로컬 `inflightRef`/state 를 함께 두면 취소가 store 만
 * 풀고 ref 는 원 요청이 끝날 때까지 잠긴 채로 남아 재클릭이 조용히 무시된다(유령 잠금).
 *
 * @param scope 이 훅이 속한 화면의 프레임. srcSn 이 바뀌면(프레임 전환) 이전 프레임의 busy 를
 *              취소해 새 화면이 남의 작업으로 잠기지 않게 한다. `portal` 은 서버 취소 창구 채널.
 */
export function useBusyTask(scope: BusyScope = {}): UseBusyTaskResult {
  const { srcSn, portal = false } = scope;
  // 취소 창구 채널 — 렌더마다 최신값. 실행 시작 시점에 붙잡아 쓴다(아래 run).
  const portalRef = useRef(portal);
  portalRef.current = portal;
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
    fn: BusyTaskFn<T>,
    opts?: BusyRunOptions,
  ): Promise<ExclusiveOutcome<T>> {
    // 시작 시점 stale — 프레임이 바뀐 뒤 늦게 출발한 요청(클릭 직후 프레임 전환)은 아예 시작하지
    // 않는다. 시작해버리면 지난 프레임의 결과가 현재 화면에 병합된다.
    if (isStaleScope(ctx.srcSn, scopeSrcSnRef.current)) {
      return { status: 'discarded' };
    }
    // 이 실행에 실제로 적용되는 대기 상한. 잠금을 걸며 함께 기록해 진행 오버레이가 그 값을
    // 그대로 보여주게 한다 — 화면이 다시 계산하면 «적용된 상한» 과 «표시된 상한» 이 갈린다.
    const maxDurationMs = opts?.maxDurationMs ?? aiWaitBusyMaxMs(kind);
    const begun = useLabelStore.getState().beginBusy(kind, { ...ctx, maxDurationMs });
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

    // ★ 취소를 **실제 요청 중단**으로 잇는다. 화면 안에서 결과만 버리면 서버는 계속 돌아 추론
    //   자원을 물고 있고, 사용자는 "취소했는데 왜 계속 도나" 를 알 수 없다.
    //   토큰이 죽는 모든 경로(사용자 취소 · 프레임 전환 · 리셋 · 아래 fail-safe)가 한 지점으로
    //   모이므로, store 를 구독해 토큰 사망을 중단으로 번역하면 경로마다 배선할 필요가 없다.
    const controller = new AbortController();
    // ★ 브라우저측 중단만으로는 **서버가 멈추지 않는다** — "클라이언트가 연결을 끊으면 서버도
    //   끊는다" 는 이 스택에서 성립하지 않기 때문이다(서버 담당이 실험으로 확인). 그래서 요청에
    //   이 식별자를 실어 보내고, 취소 시 같은 식별자로 서버 취소 API 를 부른다.
    //   중단 신호는 «우리가 더 안 기다린다», 취소 API 는 «서버도 그만해라» 로 역할이 다르며
    //   **둘 다 필요하다** — 하나만 하면 서버가 계속 돌거나(신호만) 화면이 계속 기다린다(API 만).
    const requestId = newAiRequestId();
    // 추론이 아닌 작업(저장·불러오기)은 서버의 취소 등록 대상 경로가 아니다 — 부르면 존재하지
    // 않는 식별자로 왕복만 돈다.
    const cancellableOnServer = busyKindWaitKind(kind) !== null;
    // 요청을 보낸 채널로 취소도 보낸다 — 실행 시작 시점에 붙잡는다(도중에 훅이 사라져도 같은 값).
    const cancelViaPortal = portalRef.current;
    let serverCancelSent = false;
    const abortIfDead = () => {
      if (useLabelStore.getState().isTokenAlive(token)) return;
      controller.abort();
      // 멱등 — 토큰 사망은 구독 콜백에서 여러 번 관측될 수 있다.
      if (cancellableOnServer && !serverCancelSent) {
        serverCancelSent = true;
        void cancelAiRequest(requestId, cancelViaPortal);
      }
    };
    const unsubscribe = useLabelStore.subscribe(abortIfDead);

    // fail-safe 타이머는 **시작한 훅의 생명주기와 분리**한다 — 도구 전환으로 시작 컴포넌트가
    // 언마운트돼도(예: 추적 도구) 그 작업의 자동 해제는 살아 있어야 화면이 영구 잠기지 않는다.
    // 토큰 검사가 있어 이미 끝난/취소된 작업의 타이머는 새 busy 를 건드리지 않는다.
    //
    // ★ 상한은 **작업 종류별**이다(공용 5분 하나를 나눈 자리). 예산 축이 없는 작업은 종전 값.
    // ★ 터지면 **반드시 알린다** — 종전에는 여기서 조용히 해제만 해, 요청이 성공해도 결과가
    //   폐기되고 안내도 없어 사용자가 성공도 실패도 못 보는 상태로 남았다.
    const startedAt = Date.now();
    // 연장이 아무리 걸려도 잠금 전체가 넘지 못하는 시간 — 아래 renewDeadline 주석 참조.
    const absoluteMaxMs = busyAbsoluteMaxMs(maxDurationMs);
    const onDeadline = () => {
      if (!useLabelStore.getState().isTokenAlive(token)) return;
      useLabelStore.getState().cancelBusy();
      // 진행 중이던 요청도 끊고 **서버에도 멈추라고 알린다** — 상한이 터졌는데 서버만 계속 돌면
      // 결과는 버려지고 자원만 소모된다(정확히 이 라운드가 없애려는 낭비다).
      // 취소 경로와 같은 함수를 부른다 — 규칙이 두 곳에 생기면 한쪽만 갱신되며 갈라진다.
      abortIfDead();
      notifyBusyTimeout(kind);
      // 진단용 — 식별자/좌표 등 데이터는 남기지 않고 작업 종류만 기록한다.
      // eslint-disable-next-line no-console
      console.warn(`[label] busy(${kind}) 최대 지속시간 초과 — 자동 해제`);
    };
    let timer = setTimeout(onDeadline, maxDurationMs);

    // ★ **진행이 있었을 때만** 상한을 그 시점부터 다시 센다. 나눠 보내는 작업이 서버의 부분 결과를
    //   받아 이어 보낼 때 쓰며, 이것이 없으면 처음 잡은 상한에서 잠금이 풀려 «서버는 잘 돌고 있는데
    //   결과가 버려지는» 상태가 된다. 진행 없이 부르면 fail-safe 가 무력화되므로 호출측이 진행을
    //   확인한 자리에서만 부른다.
    const renewDeadline = (ms: number) => {
      if (!Number.isFinite(ms) || ms <= 0) return;
      if (!useLabelStore.getState().isTokenAlive(token)) return; // 이미 끝난 작업은 되살리지 않는다
      // ★ 연장에는 **절대 상한**이 있다. 없으면 서버가 요청마다 1프레임만 진행해도 그때마다 상한이
      //   다시 세어져 실질 상한이 사라진다(진행 없음 가드는 «0 프레임» 만 막는다).
      const remainingToAbsolute = absoluteMaxMs - (Date.now() - startedAt);
      if (remainingToAbsolute <= 0) {
        // 이미 절대 상한을 넘겼다 — 미루지 않고 그 자리에서 fail-safe 를 발화시킨다(안내 포함).
        onDeadline();
        return;
      }
      const applied = Math.min(ms, remainingToAbsolute);
      clearTimeout(timer);
      timer = setTimeout(onDeadline, applied);
      // 오버레이가 말하는 상한도 함께 늘린다 — 적용된 상한과 표시된 상한이 갈리면 안내가 거짓이 된다.
      useLabelStore.getState().extendBusy(token, Date.now() - startedAt + applied);
    };

    try {
      const result = await fn(isAlive, controller.signal, requestId, renewDeadline);
      // 취소·리셋·프레임 전환 이후 도착 → 호출측이 병합하지 않도록 폐기.
      return isAlive() ? { status: 'ok', value: result } : { status: 'discarded' };
    } catch (err) {
      // ★ **취소는 오류가 아니다.** 우리가 스스로 끊었으면 그 실패는 정상 종료이고, 오류 안내를
      //   띄우면 «내가 멈췄는데 무슨 일이 났나» 하는 화면이 된다.
      //
      //   판정 근거는 내려받기 경로와 같은 원칙이다 — **오류 객체가 아니라 «내가 중단을 걸었는가»**.
      //   공용 클라이언트가 ApiError 로 감싸며 취소 표식을 남기지 않아 오류만 봐서는 취소와 회선
      //   단절이 구분되지 않기 때문이다. 다만 그 사실을 나르는 것이 여기서는 컨트롤러가 아니라
      //   **토큰**이다: 중단은 토큰이 죽을 때만 걸리므로(위 abortIfDead) 두 값은 항상 같은 사실을
      //   말한다. `controller.signal.aborted` 를 따로 보는 분기를 두면 **같은 판정이 두 곳에**
      //   생기고, 한쪽만 갱신되며 조용히 갈라진다(이 저장소가 반복해 겪은 결함 계열).
      //   ⚠ 토큰 판정은 프레임 전환까지 함께 덮는다 — 그쪽도 «이 결과를 쓰지 않는다» 로 같다.
      if (!isAlive()) return { status: 'discarded' };
      throw err;
    } finally {
      clearTimeout(timer);
      // 구독을 먼저 끊는다 — 아래 endBusy 가 토큰을 죽이면서 «정상 종료한 작업» 을 중단
      // 처리하는 것을 막는다(결과는 이미 손에 있고, 중단 표식만 뒤늦게 서는 것은 거짓이다).
      unsubscribe();
      useLabelStore.getState().endBusy(token);
    }
  }, []);

  const runExclusiveOrNotify = useCallback(
    async function runOrNotify<T>(
      kind: BusyKind,
      ctx: BusyContext,
      fn: BusyTaskFn<T>,
      opts?: BusyRunOptions,
    ): Promise<T | null> {
      const outcome = await runExclusive(kind, ctx, fn, opts);
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
