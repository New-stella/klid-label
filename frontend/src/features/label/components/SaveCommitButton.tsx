import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { useLabelStore } from '@/stores/useLabelStore';

import { useUpdateLabels } from '../hooks/useUpdateLabels';
import type { Label } from '../types';

interface SaveCommitButtonProps {
  srcSn: number | undefined;
  labels: Label[];
  portalMode?: boolean;
  onSaved?: () => void;
}

/**
 * 저장 버튼. BE의 PUT /frames/{srcSn}/labels 가 저장 + (내부 채널 한정) Gitea 커밋을 함께 처리한다.
 * useUpdateLabels 훅을 통해 호출하여 저장 성공 시 LABEL/VIDEO/ASSIGNMENT/REVIEW 캐시를 일괄
 * invalidate 한다 — 프레임 왕복·작업 목록 진입 시 stale 노출 회귀 방지.
 *
 * portalMode 는 호출 측 의도를 명시하기 위해 prop 으로 유지하지만, BE 가 채널 정보로 커밋 여부를
 * 결정하므로 FE 행위는 동일(단일 PUT)이다.
 */
export function SaveCommitButton({ srcSn, labels, portalMode: _portalMode, onSaved }: SaveCommitButtonProps) {
  const [error, setError] = useState<string | null>(null);
  const clearDirty = useLabelStore((s) => s.clearDirty);
  const dirtyCount = useLabelStore((s) => s.dirtyLabels.size);

  const { mutateAsync: updateLabels, isPending: loading } = useUpdateLabels(srcSn);

  async function handleSave() {
    if (srcSn === undefined) return;
    setError(null);
    try {
      await updateLabels(labels);
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
        disabled={srcSn === undefined}
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
