import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { useLabelStore } from '@/stores/useLabelStore';

import { saveAndCommit } from '../SaveCommitFlow';
import type { Label } from '../types';

interface SaveCommitButtonProps {
  srcSn: number | undefined;
  labels: Label[];
  portalMode?: boolean;
  onSaved?: () => void;
}

/**
 * 저장 + 커밋 버튼. PUT 후 portalMode가 아니면 POST commit.
 */
export function SaveCommitButton({ srcSn, labels, portalMode, onSaved }: SaveCommitButtonProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const clearDirty = useLabelStore((s) => s.clearDirty);
  const dirtyCount = useLabelStore((s) => s.dirtyLabels.size);

  async function handleSave() {
    if (srcSn === undefined) return;
    setLoading(true);
    setError(null);
    try {
      await saveAndCommit(srcSn, labels, { portalMode });
      clearDirty();
      onSaved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장 실패');
    } finally {
      setLoading(false);
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
