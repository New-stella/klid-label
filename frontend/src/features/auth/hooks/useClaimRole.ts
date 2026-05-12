import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

import { claimRole, type ClaimRoleRequest, type ClaimRoleResponse } from '@/features/auth/api';
import { useAuthStore } from '@/stores/useAuthStore';

interface UseClaimRoleOptions {
  /** 성공 시 추가로 실행할 콜백 (토큰 교체 + 홈 navigate 이후) */
  onSuccess?: (data: ClaimRoleResponse) => void;
  /** 실패 시 호출. UI 에서 에러 메시지 표시에 사용. */
  onError?: (error: unknown) => void;
  /** 성공 시 이동할 경로. 기본 `/dashboard`. */
  redirectTo?: string;
}

/**
 * 권한 자가 부여 mutation 훅.
 *
 * - 성공: 응답의 새 토큰으로 `useAuthStore.setTokenAndClaims` 갱신 후 `redirectTo` 로 navigate.
 * - 실패: `onError` 콜백으로 ApiError 를 전달 (status 별 UI 분기).
 *
 * 보안:
 * - 토큰 갱신은 항상 BE 응답을 신뢰원으로 사용한다 (FE 입력 평문은 즉시 폐기).
 * - 평문 adminPassword 는 mutation 실행 후 React 메모리에서 폐기되며 어디에도 영속화하지 않는다.
 */
export function useClaimRole(opts?: UseClaimRoleOptions) {
  const setTokenAndClaims = useAuthStore((s) => s.setTokenAndClaims);
  const navigate = useNavigate();

  return useMutation<ClaimRoleResponse, unknown, ClaimRoleRequest>({
    mutationFn: claimRole,
    onSuccess: (data) => {
      setTokenAndClaims(data.accessToken);
      navigate(opts?.redirectTo ?? '/dashboard', { replace: true });
      opts?.onSuccess?.(data);
    },
    onError: (error) => {
      opts?.onError?.(error);
    },
  });
}
