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
          {/*
           * hotfix(W-3): BE PortalAutolabelRequest 는 portalVideoSn + imageB64 가 모두 필수.
           * 캔버스 프레임 캡처 연동은 LabelingPage 캔버스 ref 확보 후 별도 작업 — 현재는 imageB64 미제공으로
           * 버튼이 비활성화 상태로 노출된다 (UI 자리만 잡고, 클릭 시 400 발생하지 않도록 차단).
           */}
          <AutolabelButton
            portalVideoSn={numericId}
            onSuccess={(r) => {
              pushToast({
                variant: 'success',
                message: `오토라벨 완료 (${r.detections.length}건${r.mock ? ', mock' : ''})`,
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
