import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { useUpdateLabels } from '../hooks/useUpdateLabels';
import type { Label } from '../types';

interface SaveCommitButtonProps {
  srcSn: number | undefined;
  labels: Label[];
  portalMode?: boolean;
  onSaved?: () => void;
}

/**
 * 저장 버튼. BE의 PUT /frames/{srcSn}/labels 는 작업본 임시저장만 수행한다(버전 스냅샷 미생성 —
 * 버전은 검수 승인 시점에 BE가 생성, SFR-08).
 * useUpdateLabels 훅을 통해 호출하여 저장 성공 시 LABEL/VIDEO/ASSIGNMENT/REVIEW 캐시를 일괄
 * invalidate 한다 — 프레임 왕복·작업 목록 진입 시 stale 노출 회귀 방지.
 *
 * ⚠️ portalMode 사용 주의 (오용 방지): 이 버튼은 **내부 전용** PUT /frames/{srcSn}/labels 를
 *   호출한다(useUpdateLabels → putLabels). PORTAL 채널 사용자는 이 엔드포인트에서 403 을 받는다
 *   (포털 작업본은 LS_PORTAL_USER_LABEL 별도 적재 — ADR-013, 포털은 버전관리/검수 미제공).
 *   따라서 portalMode 는 "포털 저장을 이 버튼으로 수행한다"는 의미가 **아니다**. 포털 저장 동선이
 *   생기면 별도 포털 저장 훅(예: putPortalLabels)을 받는 분기를 추가해야 한다. 현재 production
 *   사용처가 없어 내부 PUT 동작을 유지하되, dev 환경에서 portalMode 로 마운트되면 경고를 남긴다.
 */
export function SaveCommitButton({ srcSn, labels, portalMode = false, onSaved }: SaveCommitButtonProps) {
  if (portalMode && import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.warn(
      '[SaveCommitButton] portalMode=true 로 마운트됨 — 이 버튼은 내부 PUT 만 수행하며 포털 저장을 지원하지 않습니다(403 위험). 포털 저장 훅 분기가 필요합니다.',
    );
  }
  const [error, setError] = useState<string | null>(null);
  const clearDirty = useLabelStore((s) => s.clearDirty);
  const dirtyCount = useLabelStore((s) => s.dirtyLabels.size);
  // 다른 장시간 작업(AI/불러오기)이 진행 중이면 저장 버튼도 비활성 — 눌러도 거부될 뿐이다.
  const editBlocked = useIsEditBlocked(srcSn);

  const { mutateAsync: updateLabels, isPending: loading } = useUpdateLabels(srcSn);

  async function handleSave() {
    if (srcSn === undefined || editBlocked) return;
    setError(null);
    try {
      // 폐기·거부된 저장(null)이면 dirty 를 비우거나 완료 콜백을 호출하지 않는다 —
      // 저장되지 않았는데 "저장됨"으로 취급하면 미저장 작업이 그대로 사라진다.
      const saved = await updateLabels(labels);
      if (saved === null) return;
      clearDirty();
      onSaved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장 실패');
    }
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <Button
        type="button"
        onClick={handleSave}
        loading={loading}
        disabled={srcSn === undefined || editBlocked}
        aria-label="저장"
      >
        저장 {dirtyCount > 0 && <span className="ml-1 text-xs">({dirtyCount})</span>}
      </Button>
      {error && (
        <span role="alert" className="text-xs text-danger">
          {error}
        </span>
      )}
    </div>
  );
}
