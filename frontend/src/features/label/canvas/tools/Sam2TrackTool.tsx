// SAM2 자동추적 도구 — 선택된 라벨(박스/폴리곤)을 시작점으로 후속 N프레임에 폴리곤 전파.
//
// UI/UX §4-6: 자동추적 토글 [▶][□] + 진행률 표시.
// 보안: BE에서 IDOR/입력 검증 + 좌표 상한.

import { useCallback, useState } from 'react';
import { Play, Square } from 'lucide-react';

import { cn } from '@/lib/cn';

import { useSam2Track } from '../../hooks/useSam2Track';
import { Sam2TrackChunkError, type Sam2TrackResponse } from '../../api';

export interface Sam2TrackToolProps {
  /** 시작 프레임 SRC_SN */
  srcSn: number | undefined;
  /** 시작 폴리곤 [[x,y],...] (박스는 4점으로 변환되어 전달, 최소 3점) */
  prevPolygon: number[][] | undefined;
  /** 객체 라벨명 (BE NotBlank) */
  label: string | undefined;
  /** 트랙 식별자 — 기존 trackId 또는 신규 클라이언트 발급 */
  trackId: string | undefined;
  /** 후속 프레임 SRC_SN 전체 리스트. 50개 초과 시 hook 이 청크로 분할 순차 호출. 비어있으면 비활성. */
  nextSrcSns: number[];
  onCompleted?: (res: Sam2TrackResponse) => void;
  /**
   * Phase 9 — 포털 모드면 포털 전용 /portal/frames/{id}/sam2-track 경로로 추적(persist 없이 좌표만).
   * 내부 /frames/{id}/sam2-track 은 LS_DATA_LBL persist + PORTAL 채널 403 이므로 포털에서 호출 금지.
   */
  portalMode?: boolean;
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
  portalMode = false,
}: Sam2TrackToolProps) {
  // 청크 순차 추적 진행 상태 (누적 프레임 / 전체) + 부분/전체 실패 메시지.
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const mutation = useSam2Track(srcSn, {
    portalMode,
    onProgress: (done, total) => setProgress({ done, total }),
    onSuccess: (data) => {
      onCompleted?.(data);
    },
    onError: (err) => {
      // 부분 실패: 이미 성공한 청크 결과는 hook 이 LABEL_KEYS 무효화로 반영. 실패 지점만 안내.
      if (err instanceof Sam2TrackChunkError && err.partial.length > 0) {
        setFailure(
          `${err.partial.length}개 프레임까지 추적 후 중단 (구간 ${err.completedChunks + 1}/${err.totalChunks} 실패)`,
        );
      } else {
        setFailure('추적 실패');
      }
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
    // 새 시도마다 진행률/실패 상태 초기화.
    setProgress({ done: 0, total: nextSrcSns.length });
    setFailure(null);
    mutation.mutate({ trackId, prevPolygon, label, nextSrcSns });
  }, [disabled, prevPolygon, label, trackId, nextSrcSns, mutation]);

  const progressPct =
    progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0;

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
        <div className="flex items-center gap-1">
          <div
            role="progressbar"
            aria-label="AI 추적 진행"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progressPct}
            className="h-2 w-24 overflow-hidden rounded bg-bgLight"
          >
            <div
              className="h-full bg-primary transition-all"
              style={{ width: `${Math.max(progressPct, 5)}%` }}
            />
          </div>
          {progress && progress.total > 0 && (
            <span className="text-[11px] text-neutral" aria-live="polite">
              {progress.done}/{progress.total}
            </span>
          )}
        </div>
      )}
      {mutation.isSuccess && !isPending && (
        <div
          role="progressbar"
          aria-label="AI 추적 완료"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={100}
          className="h-2 w-24 overflow-hidden rounded bg-bgLight"
        >
          <div className="h-full w-full bg-success" />
        </div>
      )}
      {mutation.isError && !isPending && (
        <span className="text-xs text-danger" role="status">
          {failure ?? '추적 실패'}
        </span>
      )}
    </div>
  );
}
