import { useMutation, useQueryClient } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import {
  acceptAugment,
  cancelAugment,
  rejectAugment,
  requestAugment,
  restoreAugment,
} from '../api';
import type {
  AugmentCancelResult,
  RequestAugmentRequest,
  RequestAugmentResponse,
} from '../types';

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/**
 * 증강 요청 옵션 — 성공 콜백에 **요청 본문(variables)** 을 함께 넘긴다.
 *
 * 응답의 `jobId` 는 결과 화면 경로로 쓸 수 없다(BE `AugmentRequestService#jobIdSeq` 는 어떤
 * 엔티티의 식별자도 아닌 placeholder 카운터다). 결과 API 의 경로변수는 **원본 영상 RAW_SN** 이므로
 * 호출부가 "무엇을 요청했는가"(= `videoIds`)를 근거로 이동해야 한다. 렌더 시점 상태 클로저 대신
 * 요청 본문을 그대로 받으면 요청 시점과 성공 시점 사이에 선택이 바뀌어도 어긋나지 않는다.
 */
interface RequestAugmentOptions {
  onSuccess?: (
    data: RequestAugmentResponse,
    variables: RequestAugmentRequest,
  ) => void;
  onError?: (err: unknown) => void;
}

/**
 * 증강 요청 생성 (POST /augments/request).
 */
export function useRequestAugment(options: RequestAugmentOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: RequestAugmentRequest) => requestAugment(body),
    onSuccess: (data, variables) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data, variables);
    },
    onError: options.onError,
  });
}

/**
 * 증강 결과 채택 (PENDING → ACCEPTED).
 */
export function useAcceptAugment(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => acceptAugment(id),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 증강 결과 반려 (PENDING → REJECTED). 반려 사유 zod 검증 후 호출.
 */
export function useRejectAugment(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; reason: string }) =>
      rejectAugment(vars.id, vars.reason),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 폐기(반려)된 증강 파생영상 복구 (REVIEWER). 사유 필수.
 *
 * <h3>성공/실패 **양쪽에서** 결과를 다시 받는다 (Critical)</h3>
 * 복구 실패는 코드가 둘이고 사유가 넷이다(BE `AugmentDiscardService` 실측):
 * - **404** "복구할 폐기 이력이 없습니다." — 되돌릴 결정 자체가 없음
 * - **404** "증강 결과를 찾을 수 없습니다." — 증강 행이 이미 사라짐
 * - **409** "복구할 검수 이력이 없습니다." — 표식은 열려 있는데 검수 행이 먼저 지워진 레이스
 * - **409** "유예 기간이 지나 이미 삭제된 파생영상입니다…" — 실삭제 커밋과 경합
 *
 * 코드로도 메시지로도 **분기하지 않는다** — 넷 다 필요한 반응이 같고(서버 안내 노출 + 재동기화),
 * **메시지 문자열로 분기하면 문구가 바뀔 때 화면이 조용히 깨진다**. 서버 진실을 다시 받아 화면을
 * 정정한다(실삭제 케이스는 그 결과로 복구 버튼이 사라진다). 그래서 무효화를 `onSettled` 에 둔다.
 *
 * 낙관적 업데이트는 쓰지 않는다 — 쓰면 사라져야 할 버튼이 되돌아온다.
 *
 * 무효화 범위는 `details()` 로 좁힌다 — `all` 은 항목별 진행상태 폴링(`progress`)까지 깨워
 * 서버 요청을 증폭시킨다(서버에 속도 제한이 없다).
 */
export function useRestoreAugment(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; reason: string }) =>
      restoreAugment(vars.id, vars.reason),
    onSuccess: (data) => {
      options.onSuccess?.(data);
    },
    onError: options.onError,
    onSettled: () => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.details() });
    },
  });
}

/**
 * 증강 요청 취소 (REVIEWER). 사유는 선택.
 *
 * 성공 시 증강 도메인 전체를 무효화해 결과(항목 상태)·진행상태가 함께 갱신된다.
 * `onSettled` 는 호출부의 연타 락 해제 지점이다(성공/실패 무관).
 */
export function useCancelAugment(
  options: MutationOptions<AugmentCancelResult> & { onSettled?: () => void } = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; reason?: string }) =>
      cancelAugment(vars.id, vars.reason),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
    onSettled: options.onSettled,
  });
}
