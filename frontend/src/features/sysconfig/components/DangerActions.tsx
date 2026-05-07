import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { useUiStore } from '@/stores/useUiStore';

interface DangerAction {
  id: string;
  label: string;
  description: string;
}

const DANGER_ACTIONS: DangerAction[] = [
  {
    id: 'reset-batch-queue',
    label: '배치 큐 초기화',
    description: '대기 중인 배치 작업이 모두 제거됩니다. 진행 중인 작업은 영향받지 않습니다.',
  },
  {
    id: 'clear-cache',
    label: '캐시 비우기',
    description: '서버 캐시가 모두 비워져 일시적으로 응답이 느려질 수 있습니다.',
  },
];

/**
 * UI/UX §4-16 ③ 위험 액션 (placeholder).
 *
 * Phase 4 단계에서는 ConfirmDialog 노출 + toast 표시까지만 — 실제 API 호출은 하지 않는다.
 *
 * 보안:
 * - 위험 액션은 confirm 한 단계 거쳐야 실행
 * - placeholder 단계에서는 외부 호출 없음 (의도하지 않은 변경 방지)
 */
export function DangerActions() {
  const [open, setOpen] = useState<DangerAction | null>(null);
  const pushToast = useUiStore((s) => s.pushToast);

  const handleConfirm = () => {
    if (!open) return;
    // placeholder — 실제 API 호출 없음
    pushToast({
      variant: 'info',
      message: `${open.label} 요청 (placeholder — 후속 Phase에서 구현)`,
    });
    setOpen(null);
  };

  return (
    <>
      <Card title="위험 액션">
        <div className="flex flex-col gap-3">
          <p className="text-sub text-neutral">
            아래 액션은 시스템 상태에 영향을 줄 수 있으니 신중하게 실행하세요.
          </p>
          {DANGER_ACTIONS.map((a) => (
            <div
              key={a.id}
              className="flex items-center justify-between rounded border border-border p-3"
            >
              <div className="flex flex-col gap-1">
                <span className="text-body font-medium text-primary">{a.label}</span>
                <span className="text-sub text-neutral">{a.description}</span>
              </div>
              <Button variant="danger" size="sm" onClick={() => setOpen(a)}>
                {a.label}
              </Button>
            </div>
          ))}
        </div>
      </Card>
      <ConfirmDialog
        open={!!open}
        title={open?.label ?? ''}
        description={`${open?.description ?? ''} 이 작업은 되돌릴 수 없습니다.`}
        confirmLabel="실행"
        cancelLabel="취소"
        variant="danger"
        onConfirm={handleConfirm}
        onCancel={() => setOpen(null)}
      />
    </>
  );
}
