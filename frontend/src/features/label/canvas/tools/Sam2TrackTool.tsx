// SAM2 자동추적 도구 — 단축키 T로 활성화 + 토글 버튼 클릭 시 BE 호출.
//
// UI/UX §4-6: 자동추적 토글 [▶][□] + 진행률 표시.
// 보안: BE에서 IDOR/입력 검증 + 1M 픽셀 한도.

import { useCallback } from 'react';
import { Play, Square } from 'lucide-react';

import { cn } from '@/lib/cn';

import { useSam2Track } from '../../hooks/useSam2Track';

export interface Sam2TrackToolProps {
  srcSn: number | undefined;
  bbox: [number, number, number, number] | undefined;
  classId: number | undefined;
  /** 다음 N개 프레임까지 자동 추적 */
  targetFrameCount?: number;
  onCompleted?: (trackId: number) => void;
}

/**
 * SAM2 자동추적 도구. 토글 버튼 + 진행률(progressbar) 표시.
 */
export function Sam2TrackTool({
  srcSn,
  bbox,
  classId,
  targetFrameCount = 30,
  onCompleted,
}: Sam2TrackToolProps) {
  const mutation = useSam2Track(srcSn, {
    onSuccess: (data) => {
      onCompleted?.(data.trackId);
    },
  });

  const disabled = srcSn === undefined || bbox === undefined || classId === undefined;
  const isPending = mutation.isPending;

  const handleToggle = useCallback(() => {
    if (disabled || !bbox || classId === undefined) return;
    mutation.mutate({ bbox, classId, targetFrameCount });
  }, [bbox, classId, disabled, mutation, targetFrameCount]);

  return (
    <div className="flex items-center gap-2" data-testid="sam2-track-tool">
      <button
        type="button"
        onClick={handleToggle}
        disabled={disabled || isPending}
        aria-label={isPending ? '자동추적 진행 중' : '자동추적 시작'}
        className={cn(
          'flex items-center gap-1 rounded border border-border px-3 py-1 text-sub',
          isPending ? 'bg-warning/10 text-warning' : 'bg-white text-primary hover:bg-bgLight',
          disabled && 'opacity-50',
        )}
      >
        {isPending ? <Square size={14} /> : <Play size={14} />}
        {isPending ? '추적 중' : '자동추적'}
      </button>
      {isPending && (
        <div
          role="progressbar"
          aria-label="SAM2 자동추적 진행"
          aria-valuemin={0}
          aria-valuemax={100}
          className="h-2 w-24 overflow-hidden rounded bg-bgLight"
        >
          <div className="h-full w-1/3 animate-pulse bg-primary" />
        </div>
      )}
      {mutation.isSuccess && !isPending && (
        <div
          role="progressbar"
          aria-label="SAM2 자동추적 완료"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={100}
          className="h-2 w-24 overflow-hidden rounded bg-bgLight"
        >
          <div className="h-full w-full bg-success" />
        </div>
      )}
      {mutation.isError && (
        <span className="text-xs text-danger" role="status">
          추적 실패
        </span>
      )}
    </div>
  );
}
