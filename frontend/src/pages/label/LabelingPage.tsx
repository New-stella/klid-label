// SCR-LABEL-001 라벨링 캔버스 페이지 — 풀스크린 다크 UI (mock 정합).
//
// 레이아웃:
//   ┌─ LabelHeader (h-14, bg-gray-800)
//   ├─ flex-1: [DarkToolbar w-14] [Canvas flex-1] [RightPanel w-72]
//   └─ Bottom (h-30): [DarkFrameStrip h-15] [DarkFrameSlider h-15]
//
// 라우트는 AppLayout 밖에서 직접 매칭되므로 LNB/GNB 없는 풀스크린.
// 보안: 사용자 입력 ID는 axios가 URL 인코딩. BE에서 IDOR/Mass Assignment 방어.

import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { Spinner } from '@/components/common/Spinner';
import { LabelHeader } from '@/features/label/components/LabelHeader';
import { DarkToolbar } from '@/features/label/components/DarkToolbar';
import { ObjectClassTree } from '@/features/label/components/ObjectClassTree';
import { ClassAttributePanel } from '@/features/label/components/ClassAttributePanel';
import { DarkFrameStrip } from '@/features/label/components/DarkFrameStrip';
import { DarkFrameSlider } from '@/features/label/components/DarkFrameSlider';
import { useLabelingShortcuts } from '@/features/label/hooks/useLabelingShortcuts';
import { useLabels } from '@/features/label/hooks/useLabels';
import { saveAndCommit } from '@/features/label/SaveCommitFlow';
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

// 캔버스 컨테이너에서 자동 측정해 ResponsiveCanvas로 전달
function useContainerSize<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const rect = el.getBoundingClientRect();
      setSize({ width: Math.floor(rect.width), height: Math.floor(rect.height) });
    };
    const observer = new ResizeObserver(update);
    observer.observe(el);
    update();
    return () => observer.disconnect();
  }, []);

  return [ref, size] as const;
}

/**
 * SCR-LABEL-001 라벨링 캔버스 페이지 (다크 풀스크린).
 */
export function LabelingPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const numericId = id ? Number(id) : NaN;

  // 채널/역할 가드
  const portalMode = useAuthStore((s) => s.claims?.channel === 'PORTAL');
  const role = useAuthStore((s) => s.claims?.role);
  const isWorker = role === Role.WORKER;
  const pushToast = useUiStore((s) => s.pushToast);

  const { data, isLoading, error } = useLabels(
    Number.isFinite(numericId) ? numericId : undefined,
  );

  const { mutate: submitForReview, isPending: submitting } = useSubmitReview({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '검수 제출 완료' });
      navigate('/task');
    },
    onError: () => pushToast({ variant: 'error', message: '검수 제출 실패' }),
  });

  const setLabels = useLabelStore((s) => s.setLabels);
  const labels = useLabelStore((s) => s.labels);
  const dirtyCount = useLabelStore((s) => s.dirtyLabels.size);
  const clearDirty = useLabelStore((s) => s.clearDirty);
  const addLabel = useLabelStore((s) => s.addLabel);
  const reset = useLabelStore((s) => s.reset);

  // Phase 8 전: 단일 프레임만 보유 — frames 배열에 srcSn 1건
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

  // 저장 (PUT + commit) — 단축키와 헤더 버튼 공유
  const [saving, setSaving] = useState(false);
  const handleSave = async () => {
    if (!currentFrame) return;
    setSaving(true);
    try {
      await saveAndCommit(currentFrame.srcSn, labels, { portalMode });
      clearDirty();
      pushToast({ variant: 'success', message: portalMode ? '저장됨' : '저장됨 · 버전 기록됨' });
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : '저장 실패',
      });
    } finally {
      setSaving(false);
    }
  };

  useLabelingShortcuts({
    onPrevFrame: () => setFrameIdx((i) => Math.max(0, i - 1)),
    onNextFrame: () => setFrameIdx((i) => Math.min(frames.length - 1, i + 1)),
    onSave: handleSave,
  });

  const [canvasRef, canvasSize] = useContainerSize<HTMLDivElement>();

  // 잘못된 ID — 풀스크린 다크 에러
  if (Number.isNaN(numericId)) {
    return (
      <div
        className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white"
        style={{ zIndex: 50 }}
        data-testid="labeling-page"
      >
        <div className="text-center">
          <p className="text-lg font-semibold mb-2">잘못된 프레임 ID</p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-blue-600 rounded-lg text-sm hover:bg-blue-700 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div
        className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white"
        style={{ zIndex: 50 }}
        data-testid="labeling-page"
      >
        <div className="flex flex-col items-center gap-3">
          <Spinner label="라벨 로딩" />
          <p className="text-sm text-gray-300">라벨 로딩 중...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white"
        style={{ zIndex: 50 }}
        data-testid="labeling-page"
      >
        <div className="text-center">
          <p className="text-lg font-semibold mb-2">라벨 조회 실패</p>
          <p className="text-sm text-gray-400 mb-4">{error.message}</p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-blue-600 rounded-lg text-sm hover:bg-blue-700 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  const objectCount = labels.length;
  const isDirty = dirtyCount > 0;
  // CCTV명/이벤트는 향후 useVideoDetail 연동 시 채워짐 — 현재는 srcSn 표시
  const cctvName = data ? `프레임 #${data.srcSn}` : undefined;

  return (
    <div
      className="fixed inset-0 bg-gray-900 flex flex-col overflow-hidden"
      style={{ zIndex: 50 }}
      data-testid="labeling-page"
    >
      <LabelHeader
        cctvName={cctvName}
        eventType={undefined}
        currentFrame={frameIdx}
        totalFrames={Math.max(frames.length, 1)}
        objectCount={objectCount}
        dirty={isDirty}
        videoId={data?.srcSn}
        showHistory={!portalMode}
        onSave={handleSave}
        saving={saving}
        submitButton={
          isWorker && data ? (
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
          ) : null
        }
      />

      {/* 본문 — 좌측 도구바 + 캔버스 + 우측 패널 */}
      <div className="flex flex-1 overflow-hidden">
        <DarkToolbar onSave={handleSave} />

        {/* 캔버스 영역 — flex로 자동 채움 */}
        <div
          ref={canvasRef}
          className="flex-1 relative overflow-hidden flex items-center justify-center bg-gray-900"
        >
          {currentFrame && canvasSize.width > 0 && canvasSize.height > 0 ? (
            <Suspense
              fallback={
                <div className="flex items-center justify-center text-gray-400">
                  <Spinner label="캔버스 로딩" />
                </div>
              }
            >
              <CanvasShell
                frame={currentFrame}
                width={canvasSize.width}
                height={canvasSize.height}
                labels={labels}
                onLabelAdd={(l) => addLabel({ ...l, frameNo: currentFrame.frameNo })}
              />
            </Suspense>
          ) : (
            <div className="text-gray-400 text-sm">프레임 없음</div>
          )}
        </div>

        {/* 우측 패널 — 객체 트리 + 속성 */}
        <div className="w-72 flex flex-col bg-gray-800 border-l border-gray-700 overflow-hidden shrink-0">
          <div className="flex-1 flex flex-col overflow-hidden border-b border-gray-700">
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
              객체 목록
            </div>
            <ObjectClassTree labels={labels} />
          </div>
          <div className="flex-1 flex flex-col overflow-hidden">
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
              속성
            </div>
            <ClassAttributePanel labels={labels} />
          </div>
        </div>
      </div>

      {/* 하단 — 썸네일 strip + 슬라이더 */}
      <div className="shrink-0 flex flex-col border-t border-gray-700" style={{ height: 120 }}>
        <div style={{ height: 60 }}>
          <DarkFrameStrip
            frames={frames}
            currentIndex={frameIdx}
            onSelect={setFrameIdx}
          />
        </div>
        <div style={{ height: 60 }}>
          <DarkFrameSlider
            currentIndex={frameIdx}
            totalFrames={Math.max(frames.length, 1)}
            onSelect={setFrameIdx}
          />
        </div>
      </div>
    </div>
  );
}
