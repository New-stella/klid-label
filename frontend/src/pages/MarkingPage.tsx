import { useCallback, useEffect, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { MarkingList } from '@/features/marking/components/MarkingList';
import { MarkingTimeline } from '@/features/marking/components/MarkingTimeline';
import { MarkingToolbar } from '@/features/marking/components/MarkingToolbar';
import { VideoPlayer, type VideoPlayerHandle } from '@/features/marking/components/VideoPlayer';
import { useCreateMarking, useDeleteMarking, useMarkings } from '@/features/marking/hooks/useMarkings';
import { useMarkingStore } from '@/features/marking/store';
import type { MarkItem, MarkingMode } from '@/features/marking/types';
import { useUiStore } from '@/stores/useUiStore';

const NATIVE_FPS = 30;

export function MarkingPage() {
  const { rawSn: rawSnParam } = useParams<{ rawSn: string }>();
  const rawSn = rawSnParam ? parseInt(rawSnParam, 10) : undefined;
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const videoRef = useRef<VideoPlayerHandle>(null);
  const {
    mode, eventName, intervalFrames, localMarks, selectedMarkIndex,
    setMode, setEventName, setIntervalFrames, addMark, selectMark,
    removeSelectedMark, clearMarks, reset,
  } = useMarkingStore();

  const { data: savedMarkings = [] } = useMarkings(rawSn);
  const createMutation = useCreateMarking(rawSn, {
    onSuccess: () => {
      clearMarks();
      pushToast({ variant: 'success', message: '마킹이 제출되었습니다. 배치 처리가 시작됩니다.' });
      navigate('/task');
    },
  });
  const deleteMutation = useDeleteMarking(rawSn);

  useEffect(() => {
    reset();
    return () => reset();
  }, [rawSn, reset]);

  const handleAddMarkAtCurrentTime = useCallback(() => {
    if (!videoRef.current) return;
    const frameIndex = videoRef.current.getCurrentFrame(NATIVE_FPS);
    const time = videoRef.current.getCurrentTime();
    const mm = String(Math.floor(time / 60)).padStart(2, '0');
    const ss = String(Math.floor(time % 60)).padStart(2, '0');
    addMark({ frameIndex, timestamp: `${mm}:${ss}` });
  }, [addMark]);

  const handleSubmit = useCallback(() => {
    if (!eventName.trim() || rawSn === undefined) return;
    if (mode === 'AUTO') {
      createMutation.mutate({
        eventName: eventName.trim(),
        markingMode: 'AUTO',
        intervalFrames,
      });
    } else {
      if (localMarks.length === 0) return;
      createMutation.mutate({
        eventName: eventName.trim(),
        markingMode: 'MANUAL',
        marks: localMarks,
      });
    }
  }, [eventName, mode, intervalFrames, localMarks, rawSn, createMutation]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (mode !== 'MANUAL') return;
      const tag = (e.target as HTMLElement).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      if (e.code === 'Space') {
        e.preventDefault();
        handleAddMarkAtCurrentTime();
      } else if (e.code === 'Delete' || e.code === 'Backspace') {
        e.preventDefault();
        removeSelectedMark();
      } else if (e.code === 'Enter') {
        e.preventDefault();
        handleSubmit();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [mode, handleAddMarkAtCurrentTime, removeSelectedMark, handleSubmit]);

  if (rawSn === undefined || isNaN(rawSn)) {
    return <div className="p-8 text-center text-gray-500">잘못된 영상 ID입니다.</div>;
  }

  const videoSrc = `/api/v1/videos/${rawSn}/stream`;

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-4">
      <h1 className="text-lg font-semibold">마킹 — 영상 #{rawSn}</h1>

      <VideoPlayer ref={videoRef} src={videoSrc} />

      <MarkingTimeline
        marks={localMarks}
        durationSec={60}
        selectedIndex={selectedMarkIndex}
        onSelect={selectMark}
      />

      <MarkingToolbar
        mode={mode}
        eventName={eventName}
        intervalFrames={intervalFrames}
        onModeChange={(m: MarkingMode) => setMode(m)}
        onEventNameChange={setEventName}
        onIntervalFramesChange={setIntervalFrames}
        onSubmit={handleSubmit}
        onClear={clearMarks}
        submitting={createMutation.isPending}
        markCount={localMarks.length}
      />

      {localMarks.length > 0 && (
        <div className="rounded border p-3 text-sm">
          <h3 className="mb-2 font-medium text-gray-700">현재 마킹 ({localMarks.length}건)</h3>
          <div className="flex flex-wrap gap-2">
            {localMarks.map((mark: MarkItem, i: number) => (
              <button
                key={`${mark.frameIndex}`}
                type="button"
                onClick={() => selectMark(i)}
                className={`rounded px-2 py-1 text-xs transition-colors ${
                  selectedMarkIndex === i
                    ? 'bg-blue-600 text-white'
                    : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                }`}
              >
                F{mark.frameIndex} {mark.timestamp && `(${mark.timestamp})`}
              </button>
            ))}
          </div>
        </div>
      )}

      <MarkingList
        markings={savedMarkings}
        onDelete={(sn) => deleteMutation.mutate(sn)}
        deleting={deleteMutation.isPending}
      />
    </div>
  );
}
