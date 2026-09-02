import { AlertCircle } from 'lucide-react';
import { useEffect, useState } from 'react';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';

import {
  AiSrvrStatus,
  AI_SRVR_ALLOWED_TRANSITIONS,
  AI_SRVR_STATUS_LABEL,
  AI_SRVR_TYPE_LABEL,
  type AiSrvr,
} from '../types';

export interface AiServerStatusDialogProps {
  open: boolean;
  target: AiSrvr | null;
  /** 그 유형의 **가용** 장비 수. 마지막 하나를 내리려는지 화면이 스스로 센다. */
  availableCountOfType: number;
  onClose: () => void;
  onSubmit: (next: AiSrvrStatus) => void;
  isSubmitting: boolean;
  /** 서버가 준 거부 사유 — 문구를 그대로 보여준다(세 사유가 문구로 갈린다). */
  error?: string;
}

/**
 * 장비 상태 전이 다이얼로그. [@design SCREEN-042] [@design API-229] [@design AC-1091]
 *
 * <h3>갈 수 없는 상태는 아예 고를 수 없다</h3>
 * 현재 상태에서 허용되는 목표만 목록에 올린다. 같은 상태로의 전이는 전이가 아니므로 목록에 없다
 * (서버도 409 로 거부한다). ⚠ 이 목록을 <b>더 좁히지 말 것</b> — 좁히면 서버가 허용하는 조작을
 * 화면이 먼저 막아, 사본이 서버보다 엄격한 두 번째 진실원이 된다.
 *
 * <h3>마지막 가용 장비는 «누른 뒤»가 아니라 «누르기 전»에 알린다</h3>
 * 서버가 409 로 막지만, 그때 알려 주면 이미 조작을 시도한 뒤다. 그 유형에 가용 장비가 이 하나뿐인
 * 것은 화면이 목록만 보고도 알 수 있으므로 <b>미리</b> 알리고, «교체하려면 새 장비를 먼저 넣으라»는
 * 다음 행동까지 함께 적는다.
 *
 * <p>⚠ 이 판정은 서버 문구를 문자열로 뜯어보고 하는 것이 아니라 <b>목록에서 직접 센 것</b>이다.
 * 서버 문구가 다듬어져도 이 안내는 그대로 성립한다.
 */
export function AiServerStatusDialog({
  open,
  target,
  availableCountOfType,
  onClose,
  onSubmit,
  isSubmitting,
  error,
}: AiServerStatusDialogProps) {
  const [next, setNext] = useState<string>('');

  useEffect(() => {
    if (open) setNext('');
  }, [open, target]);

  if (!target) return null;

  const options = AI_SRVR_ALLOWED_TRANSITIONS[target.srvrSttsCd] ?? [];
  const leavingLastAvailable =
    target.srvrSttsCd === AiSrvrStatus.AVAILABLE && availableCountOfType <= 1;

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="md"
      title="장비 상태 바꾸기"
      description={`${target.srvrId} · 현재 ${AI_SRVR_STATUS_LABEL[target.srvrSttsCd]}`}
    >
      <div className="flex flex-col gap-4">
        {leavingLastAvailable && (
          <Alert
            variant="error"
            role="status"
            title="이 장비는 그 유형의 마지막 가용 장비입니다"
            data-testid="ai-server-last-available-warning"
          >
            지금 {AI_SRVR_TYPE_LABEL[target.srvrTypeCd]} 유형의 가용 장비는 이 장비뿐입니다. 내리면
            그 유형의 처리가 통째로 멈추므로 서버가 막습니다. 교체하려면 새 장비를 먼저 등록한 뒤 이
            장비를 내리세요.
          </Alert>
        )}

        <div className="flex flex-col gap-1">
          <label className="text-label font-medium text-gray-700" htmlFor="ai-server-next-status">
            바꿀 상태
          </label>
          <Select value={next} onValueChange={setNext}>
            <SelectTrigger id="ai-server-next-status">
              <SelectValue placeholder="상태를 고르세요" />
            </SelectTrigger>
            <SelectContent>
              {options.map((option) => (
                <SelectItem key={option} value={option}>
                  {AI_SRVR_STATUS_LABEL[option]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {/*
            이용불가는 상태점검이 스스로 세우는 상태이기도 하다 — 사람이 고를 수는 있으나 그 뜻이
            «지금 닿지 않는다»라는 관측이라는 것을 적어 둔다.
          */}
          <p className="text-caption text-gray-600">
            이용불가는 상태점검이 연속 실패하면 자동으로 세워지기도 합니다. 사람이 잠시 빼 둘 때는
            정비중이나 비활성을 씁니다.
          </p>
        </div>

        {error && (
          <p
            className="flex items-center gap-1 text-caption text-danger"
            role="alert"
            data-testid="ai-server-status-error"
          >
            <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            {error}
          </p>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            loading={isSubmitting}
            disabled={next === ''}
            onClick={() => next !== '' && onSubmit(next as AiSrvrStatus)}
          >
            상태 바꾸기
          </Button>
        </div>
      </div>
    </Modal>
  );
}
