import { AlertTriangle } from 'lucide-react';
import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { useUiStore } from '@/stores/useUiStore';

interface DangerAction {
  id: string;
  label: string;
  description: string;
  variant: 'danger' | 'secondary';
}

const DANGER_ACTIONS: DangerAction[] = [
  {
    id: 'system-reset',
    label: '시스템 초기화 (개발 전용)',
    description: '시스템을 초기화합니다. 개발 환경에서만 동작합니다.',
    variant: 'danger',
  },
  {
    id: 'reset-batch-queue',
    label: '배치 큐 초기화',
    description: '대기 중인 배치 작업이 모두 제거됩니다. 진행 중인 작업은 영향받지 않습니다.',
    variant: 'secondary',
  },
  {
    id: 'clear-cache',
    label: '캐시 삭제',
    description: '서버 캐시가 모두 비워져 일시적으로 응답이 느려질 수 있습니다.',
    variant: 'secondary',
  },
];

/**
 * UI/UX §4-16 ③ 위험 액션 (placeholder).
 *
 * placeholder 단계에서는 ConfirmDialog 노출 + toast 표시까지만 — 실제 API 호출은 하지 않는다.
 */
export function DangerActions() {
  const [open, setOpen] = useState<DangerAction | null>(null);
  const pushToast = useUiStore((s) => s.pushToast);

  const handleConfirm = () => {
    if (!open) return;
    pushToast({
      variant: 'info',
      message: `${open.label} 요청 (placeholder — 후속 Phase에서 구현)`,
    });
    setOpen(null);
  };

  return (
    <>
      <div className="rounded-lg border border-danger/30 bg-white px-6 py-4 space-y-4 shadow-sm">
        {/* 운영 도구 이관 예정 안내 배너 */}
        <div className="flex items-start gap-2 rounded-lg bg-warning/10 border border-warning/30 px-4 py-3">
          <AlertTriangle size={16} className="text-warning shrink-0 mt-0.5" />
          <p className="text-xs text-warning leading-relaxed">
            위험 액션은 별도 운영 도구로 이관 예정입니다. 본 화면에서는 데모 동작만 수행됩니다.
          </p>
        </div>

        <div className="flex items-center gap-2 text-danger">
          <AlertTriangle size={16} />
          <h3 className="text-sm font-semibold">위험 구역</h3>
        </div>

        <p className="text-xs text-gray-500">아래 작업은 되돌릴 수 없습니다. 신중하게 진행하세요.</p>

        <div className="flex flex-wrap gap-3">
          {DANGER_ACTIONS.map((a) => (
            <Button key={a.id} variant={a.variant} size="sm" onClick={() => setOpen(a)}>
              {a.label}
            </Button>
          ))}
        </div>
      </div>

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
