import { useCallback, useEffect, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { MarkingList } from '@/features/marking/components/MarkingList';
import { MarkingTimeline } from '@/features/marking/components/MarkingTimeline';
import { MarkingToolbar } from '@/features/marking/components/MarkingToolbar';
import { VideoPlayer, type VideoPlayerHandle } from '@/features/marking/components/VideoPlayer';
import { useCreateMarking, useDeleteMarking, useMarkings } from '@/features/marking/hooks/useMarkings';
import { useMarkingStore } from '@/features/marking/store';
import type { MarkItem, MarkingMode } from '@/features/marking/types';
import { useStreamUrl } from '@/features/video/hooks/useStreamUrl';
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

  // <video> 는 Authorization 헤더를 못 붙이므로 단기 서명 URL 을 발급받아 src 로 사용한다.
  const { data: streamUrl, refetch: refetchStreamUrl } = useStreamUrl(rawSn);
  // 만료(401)로 인한 재발급 무한루프 방지 — 에러당 1회만 재발급.
  const streamRetriedRef = useRef(false);

  const handleStreamError = useCallback(() => {
    if (streamRetriedRef.current) return;
    streamRetriedRef.current = true;
    void refetchStreamUrl().finally(() => {
      // 다음 만료 시 다시 1회 재시도 허용
      streamRetriedRef.current = false;
    });
  }, [refetchStreamUrl]);

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
        mode: 'AUTO',
        intervalFrames,
      });
    } else {
      if (localMarks.length === 0) return;
      createMutation.mutate({
        eventName: eventName.trim(),
        mode: 'MANUAL',
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

  const videoSrc = streamUrl?.url ?? '';

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-4">
      <h1 className="text-lg font-semibold">마킹 — 영상 #{rawSn}</h1>

      {videoSrc ? (
        <VideoPlayer ref={videoRef} src={videoSrc} onSrcError={handleStreamError} />
      ) : (
        <div className="flex aspect-video w-full items-center justify-center rounded-lg bg-black text-sm text-gray-400">
          영상을 불러오는 중…
        </div>
      )}

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
