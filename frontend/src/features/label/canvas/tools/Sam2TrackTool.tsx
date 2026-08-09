// SAM2 자동추적 도구 — 선택된 라벨(박스/폴리곤)을 시작점으로 후속 N프레임에 폴리곤 전파.
//
// UI/UX §4-6: 자동추적 토글 [▶][□] + 진행률 표시.
// 보안: BE에서 IDOR/입력 검증 + 좌표 상한.

import { useCallback, useState } from 'react';
import { Play, Square } from 'lucide-react';

import { cn } from '@/lib/cn';
import { useIsEditBlocked } from '@/stores/useLabelStore';

import { useSam2Track } from '../../hooks/useSam2Track';
import { Sam2TrackChunkError, type DetectShapeType, type Sam2TrackedItem } from '../../api';

export interface Sam2TrackToolProps {
  /** 시작 프레임 SRC_SN */
  srcSn: number | undefined;
  /** 시작 폴리곤 [[x,y],...] (박스는 4점으로 변환되어 전달, 최소 3점) */
  prevPolygon: number[][] | undefined;
  /** 객체 라벨명 (BE NotBlank) */
  label: string | undefined;
  /**
   * (R12) 추적 라벨 override — AI Tool 팝업에서 라벨을 선택했으면 그 값을 우선한다.
   * 미지정이면 캔버스 선택 객체의 클래스명(label)을 사용.
   */
  labelOverride?: string;
  /** 트랙 식별자 — 기존 trackId 또는 신규 클라이언트 발급 */
  trackId: string | undefined;
  /**
   * (R12) 추적 결과 형태 'BBOX'|'POLYGON'. AI Tool 팝업에서 "박스"를 고르면 'BBOX' 로 전달돼
   * tracked 결과가 외접 박스로 반영된다. 미지정이면 요청에 shape 미포함(BE 기본 POLYGON).
   */
  shape?: DetectShapeType;
  /** 후속 프레임 SRC_SN 전체 리스트. 50개 초과 시 hook 이 청크로 분할 순차 호출. 비어있으면 비활성. */
  nextSrcSns: number[];
  /**
   * 추적 성공(전체/부분) 시 성공분(tracked) 전달 — 호출측이 작업본 병합/토스트 수행(HIGH #7/#10).
   * @param tracked 성공 확정 추적 결과
   * @param partial true 면 일부 청크 실패(부분 성공)
   */
  onCompleted?: (tracked: Sam2TrackedItem[], partial: boolean) => void;
}

/**
 * SAM2 자동추적 도구. 토글 버튼 + 진행률(progressbar) 표시.
 */
export function Sam2TrackTool({
  srcSn,
  prevPolygon,
  label,
  labelOverride,
  trackId,
  shape,
  nextSrcSns,
  onCompleted,
}: Sam2TrackToolProps) {
  // 팝업 라벨(labelOverride)이 있으면 우선, 없으면 캔버스 선택 객체 클래스(label).
  const effectiveLabel =
    labelOverride !== undefined && labelOverride.length > 0 ? labelOverride : label;
  // 다른 장시간 작업이 진행 중이면 추적 실행 버튼을 비활성화한다(눌러도 거부될 뿐인 버튼 제거).
  const editBlocked = useIsEditBlocked(srcSn);
  // 청크 순차 추적 진행 상태 (누적 프레임 / 전체) + 부분/전체 실패 메시지.
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  // mock(모델 미로드) 로 결과가 제외됐을 때의 안내 — 실패(danger)와 구분해 경고로 표시한다.
  const [mockNotice, setMockNotice] = useState<string | null>(null);

  const mutation = useSam2Track(srcSn, {
    onProgress: (done, total) => setProgress({ done, total }),
    // 전체/부분 성공분을 그대로 상위로 전달 — 상위가 작업본 병합 + 경고 토스트를 담당.
    onTracked: (tracked, partial) => {
      onCompleted?.(tracked, partial);
    },
    // C-ISSUE-81 — BE 가 mock 프레임을 제외했음을 사용자에게 알린다(자동 적용은 이미 차단됨).
    onMockWarning: (message) => setMockNotice(message),
    onError: (err) => {
      // 부분 실패: 성공분 병합은 상위(onCompleted, partial=true)가 수행. 여기선 실패 지점만 안내.
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
    effectiveLabel === undefined ||
    effectiveLabel.length === 0 ||
    trackId === undefined ||
    trackId.length === 0 ||
    nextSrcSns.length === 0;
  const isPending = mutation.isPending;

  const handleToggle = useCallback(() => {
    if (disabled || editBlocked || !prevPolygon || effectiveLabel === undefined || trackId === undefined) return;
    // 새 시도마다 진행률/실패 상태 초기화.
    setProgress({ done: 0, total: nextSrcSns.length });
    setFailure(null);
    setMockNotice(null);
    // (R12) shape 배선 — 팝업에서 고른 형태(BBOX/POLYGON)를 요청에 포함. 미지정이면 BE 기본.
    mutation.mutate({
      trackId,
      prevPolygon,
      label: effectiveLabel,
      nextSrcSns,
      ...(shape ? { shape } : {}),
    });
  }, [disabled, editBlocked, prevPolygon, effectiveLabel, trackId, shape, nextSrcSns, mutation]);

  const progressPct =
    progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0;

  return (
    <div className="flex items-center gap-2" data-testid="sam2-track-tool">
      <button
        type="button"
        onClick={handleToggle}
        disabled={disabled || editBlocked || isPending}
        aria-label={isPending ? '자동추적 진행 중' : '자동추적 시작'}
        className={cn(
          'flex items-center gap-1 rounded border border-border px-3 py-1 text-sub',
          isPending ? 'bg-warning/10 text-warning-700' : 'bg-white text-primary hover:bg-bgLight',
          (disabled || editBlocked) && 'opacity-50',
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
      {/* data===null 은 거부/폐기(취소·프레임 전환)라 완료로 표시하지 않는다. */}
      {mutation.isSuccess && mutation.data != null && !isPending && (
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
        <span className="text-caption text-danger" role="status">
          {failure ?? '추적 실패'}
        </span>
      )}
      {/* mock(모델 미로드) 안내 — 결과가 제외됐음을 실패와 구분해 경고로 알린다(C-ISSUE-81). */}
      {mockNotice !== null && !isPending && (
        <span className="text-caption text-warning" role="status">
          {mockNotice}
        </span>
      )}
    </div>
  );
}
