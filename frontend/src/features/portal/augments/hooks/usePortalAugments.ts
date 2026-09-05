/**
 * 포털 증강 요청 현황 목록 훅(API-232).
 *
 * <h3>왜 폴링하나</h3>
 * 증강은 서버가 외부로 보내는 **비동기 위탁**이라 요청한 즉시 결과가 나오지 않는다. 화면은
 * *"도착하면 목록의 상태가 바뀝니다"* 라고 약속하므로(사양 SCREEN-044), 그 약속을 지키려면
 * 스스로 다시 물어야 한다 — 새로고침 버튼은 사양의 부품에 없다.
 *
 * <h3>간격이 업로드 목록(3초)과 다른 이유</h3>
 * 저 쪽은 우리 서버 안에서 몇 초 안에 끝나는 프레임 추출이고, 이 쪽은 **외부 위탁이라 분 단위**다.
 * 3초로 물으면 같은 답을 수백 번 받는다. 그래서 더 길게 잡는다.
 *
 * ⚠ **끝나지 않는 폴링이 되지 않는가** — 관찰자가 없으면(화면을 떠나면) React Query 가 폴링을
 *   멈추고, 창이 뒤로 가면 기본값상 배경 폴링도 하지 않는다. 즉 사용자가 이 화면을 보고 있는
 *   동안만 돈다.
 * ★ **실패한 요청은 재조회하지 않는다.** 목록 행이 실패 사유를 함께 나르므로 실패가 「기다리는
 *   중」으로 읽히지 않고, 대기 중인 요청이 하나도 남지 않으면 폴링이 멎는다.
 *   ⚠ **구 서술 폐기**: *"실패한 요청은 목록 계약에 실패 축이 없어 「기다리는 중」으로 읽히고
 *   그동안 폴링이 계속된다"* 는 더 이상 사실이 아니다 — 그 공백은 닫혔다. 그 문장을 근거로
 *   판정을 도착 여부 하나로 되돌리면 실패한 요청에서 재조회가 영영 멎지 않는다.
 *
 * @design SCREEN-044
 * @design API-232
 */
import { useQuery } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { listPortalAugments, type ListPortalAugmentsParams } from '../api';
import { resolvePortalAugmentOutcome } from '../outcome';
import type { PortalAugmentSummary } from '../types';

/** 폴링 간격(ms). 외부 위탁의 소요가 분 단위라 업로드 목록보다 길다. */
export const AUGMENT_POLL_MS = 10_000;

/**
 * 아직 결과를 기다리는 요청이 하나라도 있으면 폴링 간격을, 모두 종결이면 `false` 를 준다.
 * (React Query `refetchInterval` 계약: 숫자 = 폴링, false = 중단)
 */
export function augmentPollIntervalFor(
  rows: PortalAugmentSummary[] | undefined,
): number | false {
  if (!rows || rows.length === 0) return false;
  return rows.some((r) => resolvePortalAugmentOutcome(r) === 'waiting') ? AUGMENT_POLL_MS : false;
}

export function usePortalAugments(params: ListPortalAugmentsParams = {}) {
  return useQuery({
    queryKey: PORTAL_KEYS.augments(params as Record<string, unknown>),
    queryFn: () => listPortalAugments(params),
    refetchInterval: (query) => augmentPollIntervalFor(query.state.data?.content),
  });
}
