/**
 * 조달 상태 폴링의 <b>판정</b> — 얼마나 자주 물을지, 그리고 <b>언제 멈출지</b>.
 *
 * <h3>왜 멈추는 조건이 따로 필요한가</h3>
 * React Query 는 관찰자가 없어지면(화면을 떠나면) 스스로 멈춘다. 그것만으로는 <b>화면을 열어 둔
 * 채 자리를 비운 경우</b>가 남는다 — 진행 중 상태가 서버 쪽 사정으로 영영 끝나지 않으면 브라우저가
 * 하루 종일 같은 질문을 되풀이한다. 그래서 예산을 둔다.
 *
 * <h3>★ 멈춤은 「포기」가 아니다</h3>
 * 예산이 다해도 화면은 <b>수동으로 다시 확인</b>할 수 있어야 한다. 자동 질문만 멎고 사람이 누르는
 * 길은 남는다 — 멈춤과 함께 조작까지 사라지면 새로고침 말고는 빠져나올 길이 없다.
 *
 * <h3>간격을 왜 이 값으로 잡았나</h3>
 * 조달은 기가바이트급 압축본의 <b>복사 + 해제</b>라 분 단위다. 업로드 프레임 추출(3초)처럼 짧게
 * 물으면 같은 답을 수백 번 받는다. 반대로 증강(10초)처럼 길게 두면 금방 끝나는 작은 데이터셋에서
 * 준비 완료가 화면에 늦게 뜬다. 그 사이를 잡는다.
 *
 * @design INT-014
 */

import { PortalMaterialsState } from './types';

/** 폴링 간격(ms). */
export const MATERIALS_POLL_MS = 5_000;

/**
 * 연속 진행 중 상태에서 자동으로 물어보는 총 시간(ms).
 *
 * 10분이면 간격 5초 기준 120회다. 이 시간을 넘도록 끝나지 않는 조달은 사람이 들여다봐야 하는
 * 상황이지 더 기다린다고 달라지는 상황이 아니다.
 */
export const MATERIALS_POLL_BUDGET_MS = 10 * 60_000;

/**
 * 다음 폴링 간격 — 진행 중이 아니거나 예산을 다 썼으면 `false`(중단).
 *
 * (React Query `refetchInterval` 계약: 숫자 = 폴링, false = 중단)
 *
 * @param state     지금 받은 상태. 아직 아무것도 못 받았으면 `undefined`.
 * @param elapsedMs 진행 중을 처음 본 시점부터의 경과(ms).
 */
export function materialsPollIntervalFor(
  state: PortalMaterialsState | undefined,
  elapsedMs: number,
): number | false {
  if (state !== PortalMaterialsState.IN_PROGRESS) return false;
  if (elapsedMs >= MATERIALS_POLL_BUDGET_MS) return false;
  return MATERIALS_POLL_MS;
}
