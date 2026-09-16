import { useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { getVideo } from '../api';
import { isBatchProcessing, isLeadDeidentFailed, isLeadDeidentRunning } from '../types';
import type { VideoDetail } from '../types';

// 배치가 진행 중일 때만 상세를 재조회(폴링)할 간격(ms).
// 완료/실패/미진행이면 폴링을 중지해 self-DoS(무한 요청)를 방지한다.
export const BATCH_POLL_INTERVAL_MS = 5000;

/**
 * 「처리 중」인데 진행 로그가 아직 움직이지 않는 구간을 따라갈 **상한**(ms). [@design API-167]
 *
 * ★ 이것은 판정 축이 아니라 **상한**이다 — 되돌아가지 말 것.
 *   구 구현(`RETRY_ACCEPT_POLL_WINDOW_MS`, 60초)은 "재기동을 눌렀는가"를 시간으로 판정했다.
 *   그러면 ①버튼을 누르지 않고 화면에 들어온 사람(일괄 재시작 후 상세로 이동 등)은 창이 없어
 *   따라가지 못하고 ②창이 닫히는 순간, 실제로는 순서를 기다리는 중인데도 화면이 멈췄다.
 *   지금은 "지금 처리 중인가"를 <b>영상 상태</b>(`isBatchProcessing`)가 판정하고, 이 상수는
 *   그 상태가 <b>고착</b>됐을 때 폴링이 영원히 도는 것을 막는 역할만 한다.
 *
 * ★ 왜 상한이 필요한가 — PROCESSING 고착이 실재하는 조건이기 때문이다(별도 결함으로 인지됨:
 *   진입 가드 SKIPPED 보상 실패·노드 종료 등). 상한이 없으면 그 영상의 상세를 연 사람은 5초마다
 *   무한히 재조회한다(self-DoS). 상한을 넘겨 멈춰도 화면은 여전히 「처리 중」을 정확히 말하며,
 *   사용자는 새로고침으로 이어볼 수 있다 — 잃는 것은 자동 갱신뿐이다.
 *
 * ★ 값의 근거 — 배치 1건은 프레임 추출·AI 추론·외부 위탁을 포함해 분 단위로 걸리고, 동시 실행이
 *   제한돼 뒤 순번은 앞 작업이 끝날 때까지 기다린다. 60초는 그 대기의 첫머리도 못 덮는다.
 *   5분이면 대기의 상당 부분을 덮으면서 최대 요청 수가 1회 무장당 60회로 묶인다(창 안에서 실제로
 *   배치가 시작되면 진행 축이 이어받으므로, 이 창을 다 쓰는 것은 "끝내 시작되지 않은" 경우뿐이다).
 */
export const BATCH_PROCESSING_POLL_WINDOW_MS = 5 * 60_000;

/**
 * 선두 비식별 재시도 **접수 직후** 새 이력 회차가 쌓이기를 기다리는 상한(ms). [@design API-167]
 *
 * ★ 서버는 접수 뒤 비동기로 위탁을 시작하므로, 접수 직후의 상세에는 새 회차가 아직 없다 — 그
 *   구간의 화면은 「직전 실패」와 구분되지 않는다. 이 창은 그 짧은 틈만 메운다(새 회차가 보이면
 *   진행 중 판정({@link BATCH_PROCESSING_POLL_WINDOW_MS} 상한)이 이어받는다).
 * ⚠ 상한이다 — 위탁 디스패치가 끝내 일어나지 않아도 최대 12회(60초/5초)에서 멈춘다.
 */
export const LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS = 60_000;

/** 선두 비식별 재시도 접수 추적 입력 — 상세 화면이 접수 응답(stage=PENDING)을 관측했을 때 무장한다. */
export interface LeadDeidentAcceptWatch {
  /** 이 시각(epoch ms)이 지나면 추적을 멈춘다. */
  until: number;
  /** 접수 직전 화면이 본 비식별 이력 최신 회차 식별자(없으면 null). */
  baselineProcLogSn: number | null;
}

/** {@link batchPollInterval} 판정 보조 입력. */
export interface BatchPollOptions {
  /**
   * 「처리 중」 추적을 유지할 **마감 시각**(epoch ms). null/미지정이면 상한 창 없음.
   * 호출부는 영상이 처리 중임을 **관측했을 때** `Date.now() + BATCH_PROCESSING_POLL_WINDOW_MS` 를
   * 넣는다(관측할 때마다 늘려 잡으면 상한이 사라져 무한 폴링이 된다 — 상태가 바뀔 때만 무장한다).
   */
  pollUntil?: number | null;
  /** 판정 기준 시각(epoch ms). 테스트가 시간을 고정하려고 주입한다. */
  now?: number;
  /** 선두 비식별 재시도 접수 직후 추적 — null/미지정이면 없음. */
  leadDeidentAccept?: LeadDeidentAcceptWatch | null;
}

/**
 * 배치 진행 중이면 폴링 간격(ms), 아니면 false(중지)를 반환한다.
 *
 * <p>실시간 가시성 요구("마킹 진행중에 뭘 처리하는지 모른다")를 위해 배치가 도는 동안만
 * 자동 갱신하고, 종료되면 반드시 폴링을 멈춘다.
 * <ul>
 *   <li>영상이 최종 완료 상태(COMPLETED/APPROVED) → false (다른 무엇보다 우선 — 이미 끝났다)</li>
 *   <li>stages 없음/빈배열(배치 로그 없는 기존 영상) → 아래 「처리 중 + 창」 판정</li>
 *   <li>단계 중 FAIL 존재(실패 종료) → 아래 「처리 중 + 창」 판정</li>
 *   <li>단계 중 PROGRESS/PENDING 존재(진행 중) → 폴링 간격 반환(창과 무관 — 실제로 돌고 있다)</li>
 *   <li>그 외(전부 DONE) → 아래 「처리 중 + 창」 판정</li>
 * </ul>
 *
 * <p><b>「처리 중 + 창」 판정</b> — 진행 로그가 멈춰 보이는 위 세 경우는 두 가지가 겹쳐 있다:
 * 정말 끝난 것과, <b>접수만 되고 아직 시작되지 않은 것</b>이다. 둘을 가르는 것은 로그가 아니라
 * 영상 상태(`isBatchProcessing`)이며, 창은 그 추적의 상한일 뿐이다.
 */
export function batchPollInterval(
  data: VideoDetail | undefined,
  options: BatchPollOptions = {},
): number | false {
  const { pollUntil = null, now = Date.now(), leadDeidentAccept = null } = options;

  // 영상이 최종 완료 상태면 폴링 불필요 — 무엇보다 우선한다(이미 끝난 것을 따라갈 이유가 없다).
  if (data?.status === 'COMPLETED' || data?.status === 'APPROVED') return false;

  // [@design API-167] [@design SCREEN-009] ★선두 비식별이 실패한 영상 — 배치 상태·진행 로그가 움직이지
  //   않으므로(대기 그대로, 로그 없음) 아래 규칙으로는 따라갈 수 없다. 이 형상에서의 판정은 여기서 끝낸다.
  //   ⚠ 성공하면 비식별이 'Y'·마킹 대기로 바뀌어 이 형상을 벗어나고, 아래 규칙(처리 중 아님 → 중지)이 멈춘다.
  if (data && isLeadDeidentFailed(data)) {
    // ① 다시 요청한 비식별이 진행 중(최신 회차 REQUESTED) — 처리 중 추적과 같은 상한 창 안에서만 따라간다.
    if (isLeadDeidentRunning(data)) {
      return pollUntil !== null && now < pollUntil ? BATCH_POLL_INTERVAL_MS : false;
    }
    // ② 접수 직후 — 새 회차가 아직 쌓이지 않은 짧은 틈만 따라간다.
    if (leadDeidentAccept && now < leadDeidentAccept.until) {
      const latest = data.deidentHistory?.[0]?.procLogSn ?? null;
      const newRoundSeen =
        latest !== null &&
        (leadDeidentAccept.baselineProcLogSn === null || latest > leadDeidentAccept.baselineProcLogSn);
      // 새 회차가 보였는데 진행 중이 아니다 = 이미 종결(실패)됐다 → 멈춘다.
      return newRoundSeen ? false : BATCH_POLL_INTERVAL_MS;
    }
    // ③ 그 밖은 멈춰 있는 실패다 — 따라갈 것이 없다.
    return false;
  }

  // 처리 중(접수·대기 포함)이면서 상한 창이 열려 있을 때만, 멈춰 보이는 로그를 계속 따라간다.
  //   ★ 상태 조건이 없으면 "실패로 끝난 영상"까지 창 동안 따라가게 된다 — 창은 상한이지 판정이 아니다.
  const followStalledLog =
    data && isBatchProcessing(data) && pollUntil !== null && now < pollUntil
      ? BATCH_POLL_INTERVAL_MS
      : false;

  const stages = data?.stages;
  if (!stages || stages.length === 0) return followStalledLog;
  // 실패 단계가 하나라도 있으면 배치 종료로 간주 → 폴링 중지(잔여 PENDING 로 인한 무한 폴링 차단).
  //   ★ 단 영상이 처리 중이면 그 FAIL 은 "아직 갱신되지 않은 직전 실행의 기록"이다.
  if (stages.some((s) => s.status === 'FAIL')) return followStalledLog;
  // 진행/대기 단계가 하나라도 있으면 아직 처리 중 → 폴링.
  if (stages.some((s) => s.status === 'PROGRESS' || s.status === 'PENDING')) {
    return BATCH_POLL_INTERVAL_MS;
  }
  // 전부 DONE → 폴링 중지.
  return followStalledLog;
}

export function useVideoDetail(id: number | null, options: BatchPollOptions = {}) {
  const { pollUntil = null, leadDeidentAccept = null } = options;
  return useQuery({
    queryKey: VIDEO_KEYS.detail(id ?? -1),
    queryFn: () => getVideo(id as number),
    enabled: id !== null && id > 0,
    // 배치 진행 중에만 최신 단계를 폴링. 종료 시 false 반환으로 자동 중지.
    //   `pollUntil` 은 「처리 중」 추적의 상한이며, 창이 닫히면 다음 판정에서 스스로 멈춘다.
    refetchInterval: (query) =>
      batchPollInterval(query.state.data, { pollUntil, leadDeidentAccept }),
  });
}
