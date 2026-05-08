import { Suspense, lazy, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';
import { FrameFilmstrip } from '@/features/label/components/FrameFilmstrip';
import { FrameNavigator } from '@/features/label/components/FrameNavigator';
import { LabelPanel } from '@/features/label/components/LabelPanel';
import { ObjectAttributePanel } from '@/features/label/components/ObjectAttributePanel';
import { SaveCommitButton } from '@/features/label/components/SaveCommitButton';
import { ToolBar } from '@/features/label/components/ToolBar';
import { UndoRedoToolbar } from '@/features/label/components/UndoRedoToolbar';
import { useLabelingShortcuts } from '@/features/label/hooks/useLabelingShortcuts';
import { useLabels } from '@/features/label/hooks/useLabels';
import type { FrameSummary } from '@/features/label/types';
import { useSubmitReview } from '@/features/review/hooks/useReviewActions';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

// konva는 브라우저 전용 — lazy load로 초기 번들 분리
const CanvasShell = lazy(() =>
  import('@/features/label/canvas/CanvasShell').then((m) => ({ default: m.CanvasShell })),
);

const CANVAS_W = 960;
const CANVAS_H = 540;

/**
 * SCR-LABEL-001 라벨링 캔버스 페이지.
 *
 * 보안: 사용자 입력 ID는 axios가 URL 인코딩. BE에서 IDOR/Mass Assignment 방어.
 */
export function LabelingPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const numericId = id ? Number(id) : NaN;
  // 포털 채널은 버전관리 미제공 — 헤더 [히스토리] 버튼 미렌더
  const portalMode = useAuthStore((s) => s.claims?.channel === 'PORTAL');
  // 검수제출 버튼: WORKER만 노출 (REVIEWER/PORTAL_USER 비노출)
  const role = useAuthStore((s) => s.claims?.role);
  const isWorker = role === Role.WORKER;
  const pushToast = useUiStore((s) => s.pushToast);

  const { data, isLoading, error } = useLabels(Number.isFinite(numericId) ? numericId : undefined);

  const { mutate: submitForReview, isPending: submitting } = useSubmitReview({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '검수 제출 완료' });
      navigate('/task');
    },
    onError: () => pushToast({ variant: 'error', message: '검수 제출 실패' }),
  });

  const setLabels = useLabelStore((s) => s.setLabels);
  const labels = useLabelStore((s) => s.labels);
  const addLabel = useLabelStore((s) => s.addLabel);
  const reset = useLabelStore((s) => s.reset);

  // 데모용 frames — Phase 8에서 영상별 프레임 목록 API 연동
  const frames: FrameSummary[] = useMemo(() => {
    if (!data) return [];
    return [
      {
        frameNo: data.frameNo,
        srcSn: data.srcSn,
        thumbnailUrl: '',
        imageUrl: '',
        imageWidth: 1920,
        imageHeight: 1080,
      },
    ];
  }, [data]);

  const [frameIdx, setFrameIdx] = useState(0);
  const currentFrame = frames[frameIdx];

  useEffect(() => {
    if (data) setLabels(Array.isArray(data.labels) ? data.labels : []);
    return () => {
      reset();
    };
  }, [data, setLabels, reset]);

  useLabelingShortcuts({
    onPrevFrame: () => setFrameIdx((i) => Math.max(0, i - 1)),
    onNextFrame: () => setFrameIdx((i) => Math.min(frames.length - 1, i + 1)),
    onSave: () => {
      // 저장은 SaveCommitButton에 위임 — 여기서는 버튼 클릭 시뮬레이션 트리거
      const btn = document.querySelector<HTMLButtonElement>('[aria-label="저장"]');
      btn?.click();
    },
  });

  if (Number.isNaN(numericId)) {
    return <ErrorState title="잘못된 프레임 ID" />;
  }

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="라벨 로딩" />
      </div>
    );
  }

  if (error) {
    return <ErrorState title="라벨 조회 실패" message={error.message} />;
  }

  return (
    <div className="flex h-full flex-col gap-3" data-testid="labeling-page">
      <PageHeader
        title={`라벨링 — 프레임 ${currentFrame?.frameNo ?? '-'}`}
        breadcrumb={[
          { label: '영상', href: '/video/completed' },
          { label: '라벨링' },
        ]}
        actions={
          <div className="flex items-center gap-2">
            <Link to="/video/completed" className="text-sub text-primary hover:underline">
              ◀ 목록
            </Link>
            <FrameNavigator
              currentIndex={frameIdx}
              total={frames.length}
              onPrev={() => setFrameIdx((i) => Math.max(0, i - 1))}
              onNext={() => setFrameIdx((i) => Math.min(frames.length - 1, i + 1))}
            />
            {!portalMode && (
              <Link
                to={data ? `/history/${data.srcSn}` : '#'}
                className="rounded border border-primary px-3 py-1 text-sub text-primary hover:bg-bgLight"
              >
                히스토리
              </Link>
            )}
            <SaveCommitButton
              srcSn={data?.srcSn}
              labels={labels}
              onSaved={() => navigate(0)}
            />
            {isWorker && data && (
              <Button
                variant="primary"
                onClick={() => submitForReview(data.srcSn)}
                disabled={submitting}
                loading={submitting}
                aria-label="검수제출"
                data-testid="submit-review-button"
              >
                검수제출
              </Button>
            )}
          </div>
        }
      />

      <div className="flex items-center gap-3">
        <ToolBar />
        <UndoRedoToolbar />
      </div>

      <div className="flex flex-1 gap-3 overflow-hidden">
        <LabelPanel labels={labels} />
        <div className="flex flex-1 items-center justify-center bg-black/5">
          {currentFrame ? (
            <Suspense
              fallback={
                <div className="flex items-center justify-center" style={{ width: CANVAS_W, height: CANVAS_H }}>
                  <Spinner label="캔버스 로딩" />
                </div>
              }
            >
              <CanvasShell
                frame={currentFrame}
                width={CANVAS_W}
                height={CANVAS_H}
                labels={labels}
                onLabelAdd={(l) => addLabel({ ...l, frameNo: currentFrame.frameNo })}
              />
            </Suspense>
          ) : (
            <div className="text-neutral">프레임 없음</div>
          )}
        </div>
        <ObjectAttributePanel labels={labels} />
      </div>

      <FrameFilmstrip frames={frames} currentIndex={frameIdx} onSelect={setFrameIdx} />
    </div>
  );
}
