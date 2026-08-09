// RedeidentButton — 영상 재비식별 요청 버튼 (SC-009).
//
// 사용 시나리오:
//   검수완료(APPROVED)됐으나 비식별 미완인 영상에서 REVIEWER 가 재비식별을 요청.
//   클릭 → 확인 다이얼로그 → POST /v1/videos/{rawSn}/redeident → 비동기 접수.
//
// 노출 조건(호출부에서 가드):
//   role === REVIEWER && status === 'APPROVED' && deIdntfYn !== 'Y'
//   권한 가드는 UX 편의일 뿐 — 실제 강제는 BE(403/404/409).
//
// 보안: rawSn 은 number — apiClient path 자동 인코딩. 사용자 입력 없음(텍스트만, XSS 무관).

import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

import { useRequestRedeident } from '../hooks/useRequestRedeident';

export interface RedeidentButtonProps {
  /** 대상 영상 RAW_SN. */
  rawSn: number;
}

function errorMessageOf(err: unknown): string {
  const status = err instanceof ApiError ? err.status : undefined;
  if (status === 409) return '이미 처리 중이거나 비식별된 영상입니다.';
  if (status === 403) return '권한이 없습니다.';
  return '재비식별 요청에 실패했습니다. 잠시 후 다시 시도해주세요.';
}

export function RedeidentButton({ rawSn }: RedeidentButtonProps) {
  const [open, setOpen] = useState(false);
  const pushToast = useUiStore((s) => s.pushToast);

  const mutation = useRequestRedeident(rawSn, {
    onSuccess: () => {
      pushToast({
        variant: 'success',
        message: '재비식별 요청이 접수되었습니다(처리 중).',
      });
      setOpen(false);
    },
    onError: (err) => {
      pushToast({ variant: 'error', message: errorMessageOf(err) });
      setOpen(false);
    },
  });

  return (
    <>
      <Button
        variant="danger"
        size="sm"
        onClick={() => setOpen(true)}
      >
        재비식별
      </Button>

      <ConfirmDialog
        open={open}
        variant="danger"
        title="영상 재비식별"
        description="검수완료 영상을 다시 비식별 처리합니다. 라벨·검수상태는 보존됩니다. 진행할까요?"
        loading={mutation.isPending}
        // H-1: 처리 중 ESC/백드롭으로 다이얼로그가 강제로 닫히면 중복요청 가드를 우회할 수 있어
        // 명시적으로 비활성화한다. onCancel 의 isPending 가드와 이중 방어.
        closeOnEsc={false}
        closeOnBackdrop={false}
        onConfirm={() => mutation.mutate()}
        onCancel={() => {
          if (mutation.isPending) return;
          setOpen(false);
        }}
      />
    </>
  );
}
