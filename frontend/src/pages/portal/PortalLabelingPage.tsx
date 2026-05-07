// SCR-PORTAL-002 — 포털 간편 라벨링 페이지 (V1.5).
// LabelingPage를 재사용 (portalMode는 useAuthStore.channel='PORTAL'에서 자동 분기).
// 포털 채널은 [히스토리]·[검수제출]·VLM/메타 탭 미노출 (LabelingPage 내부 분기).

import { useState } from 'react';
import { useParams } from 'react-router-dom';

import { LabelingPage } from '@/pages/label/LabelingPage';
import { useUiStore } from '@/stores/useUiStore';

import { AutolabelButton } from '../../features/portal/components/AutolabelButton';

export function PortalLabelingPage() {
  const { id } = useParams<{ id: string }>();
  const numericId = id ? Number(id) : NaN;
  const pushToast = useUiStore((s) => s.pushToast);
  const [autolabelKey, setAutolabelKey] = useState(0);

  return (
    <div className="flex flex-col gap-3">
      {Number.isFinite(numericId) && (
        <div className="flex items-center justify-end">
          <AutolabelButton
            srcSn={numericId}
            onSuccess={(r) => {
              pushToast({
                variant: 'success',
                message: `오토라벨 완료 (${r.detectedCount}건, ${r.elapsedMs}ms)`,
              });
              setAutolabelKey((k) => k + 1);
            }}
            onError={(err) =>
              pushToast({ variant: 'error', message: err.message ?? '오토라벨 실패' })
            }
          />
        </div>
      )}
      <LabelingPage key={autolabelKey} />
    </div>
  );
}
