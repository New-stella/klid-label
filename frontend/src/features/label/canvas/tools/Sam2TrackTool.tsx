// SAM2 자동추적 도구 — 선택된 라벨(박스/폴리곤)을 시작점으로 후속 N프레임에 폴리곤 전파.
//
// UI/UX §4-6: 자동추적 토글 [▶][□] + 진행률 표시.
// 보안: BE에서 IDOR/입력 검증 + 좌표 상한.

import { useCallback } from 'react';
import { Play, Square } from 'lucide-react';

import { cn } from '@/lib/cn';

import { useSam2Track } from '../../hooks/useSam2Track';
import type { Sam2TrackResponse } from '../../api';

export interface Sam2TrackToolProps {
  /** 시작 프레임 SRC_SN */
  srcSn: number | undefined;
  /** 시작 폴리곤 [[x,y],...] (박스는 4점으로 변환되어 전달, 최소 3점) */
  prevPolygon: number[][] | undefined;
  /** 객체 라벨명 (BE NotBlank) */
  label: string | undefined;
  /** 트랙 식별자 — 기존 trackId 또는 신규 클라이언트 발급 */
  trackId: string | undefined;
  /** 후속 프레임 SRC_SN 리스트 (1~50). 비어있으면 비활성. */
  nextSrcSns: number[];
  onCompleted?: (res: Sam2TrackResponse) => void;
}

/**
 * SAM2 자동추적 도구. 토글 버튼 + 진행률(progressbar) 표시.
 */
export function Sam2TrackTool({
  srcSn,
  prevPolygon,
  label,
  trackId,
  nextSrcSns,
  onCompleted,
}: Sam2TrackToolProps) {
  const mutation = useSam2Track(srcSn, {
    onSuccess: (data) => {
      onCompleted?.(data);
    },
  });

  const hasPolygon = prevPolygon !== undefined && prevPolygon.length >= 3;
  const disabled =
    srcSn === undefined ||
    !hasPolygon ||
    label === undefined ||
    label.length === 0 ||
    trackId === undefined ||
    trackId.length === 0 ||
    nextSrcSns.length === 0;
  const isPending = mutation.isPending;

  const handleToggle = useCallback(() => {
    if (disabled || !prevPolygon || label === undefined || trackId === undefined) return;
    mutation.mutate({ trackId, prevPolygon, label, nextSrcSns });
  }, [disabled, prevPolygon, label, trackId, nextSrcSns, mutation]);

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
