import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { MarkingTimeline } from '@/features/marking/components/MarkingTimeline';
import { MarkingToolbar } from '@/features/marking/components/MarkingToolbar';
import { VideoPlayer, type VideoPlayerHandle } from '@/features/marking/components/VideoPlayer';
import { useCreateMarking } from '@/features/marking/hooks/useMarkings';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { useMarkingStore } from '@/features/marking/store';
import type { MarkItem, MarkingMode } from '@/features/marking/types';
import { useStreamUrl } from '@/features/video/hooks/useStreamUrl';
import { useVideoDetail } from '@/features/video/hooks/useVideoDetail';
import { isMarkingBlocked } from '@/features/video/types';
import { useUiStore } from '@/stores/useUiStore';

const NATIVE_FPS = 30;
// 메타데이터 로드 전 타임라인 fallback 길이(초). 로드되면 실제값으로 대체된다.
const FALLBACK_DURATION_SEC = 60;

export function MarkingPage() {
  const { rawSn: rawSnParam } = useParams<{ rawSn: string }>();
  const rawSn = rawSnParam ? parseInt(rawSnParam, 10) : undefined;
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const videoRef = useRef<VideoPlayerHandle>(null);
  // 영상 메타데이터 로드 전에는 fallback(60s), 로드되면 실제 길이로 갱신.
  const [durationSec, setDurationSec] = useState(FALLBACK_DURATION_SEC);
  const {
    mode, intervalFrames, localMarks, selectedMarkIndex,
    setMode, setIntervalFrames, addMark, selectMark,
    removeSelectedMark, clearMarks, reset,
  } = useMarkingStore();

  // AC4 백스톱 — URL 직접 진입 시 비식별 미완료면 마킹 화면 진입을 막는다.
  // (BE MarkingService 의 deIdntfYn='Y' 가드가 최종 백스톱이며, 여기선 UX 선차단.)
  const { data: videoDetail } = useVideoDetail(rawSn ?? null);

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

  const createMutation = useCreateMarking(rawSn, {
    onSuccess: () => {
      clearMarks();
      pushToast({ variant: 'success', message: '마킹이 제출되었습니다. 배치 처리가 시작됩니다.' });
      navigate('/task');
    },
    // 이벤트 유형(evntTypeCd)이 없는 영상이면 BE 가 400(INVALID_INPUT)을 내린다.
    // onError 가 없으면 조용히 실패하므로 에러 토스트로 사용자에게 알린다.
    onError: (err) => {
      pushToast({
        variant: 'error',
        message: extractBeMessage(
          err,
          '마킹 생성에 실패했습니다. 이벤트 유형이 없는 영상일 수 있습니다.',
        ),
      });
    },
  });

  useEffect(() => {
    reset();
    // 영상이 바뀌면 길이를 fallback 으로 되돌리고 새 영상 메타데이터 로드를 기다린다.
    setDurationSec(FALLBACK_DURATION_SEC);
    return () => reset();
  }, [rawSn, reset]);

  const handleDurationChange = useCallback((sec: number) => {
    setDurationSec(sec);
  }, []);

  const handleAddMarkAtCurrentTime = useCallback(() => {
    if (!videoRef.current) return;
    const frameIndex = videoRef.current.getCurrentFrame(NATIVE_FPS);
    const time = videoRef.current.getCurrentTime();
    const mm = String(Math.floor(time / 60)).padStart(2, '0');
    const ss = String(Math.floor(time % 60)).padStart(2, '0');
    addMark({ frameIndex, timestamp: `${mm}:${ss}` });
  }, [addMark]);

  const handleSubmit = useCallback(() => {
    if (rawSn === undefined) return;
    // 이벤트명은 영상의 evntTypeCd 에서 서버가 자동 소싱하므로 요청에 포함하지 않는다.
    if (mode === 'AUTO') {
      if (!intervalFrames || intervalFrames < 1) return;
      createMutation.mutate({
        mode: 'AUTO',
        intervalFrames,
      });
    } else {
      if (localMarks.length === 0) return;
      createMutation.mutate({
        mode: 'MANUAL',
        marks: localMarks,
      });
    }
  }, [mode, intervalFrames, localMarks, rawSn, createMutation]);

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

  // 비식별 미완료 영상은 마킹 진입 차단(백스톱) — 영상 상세가 로드되어 미완료가 확정될 때만.
  if (videoDetail && isMarkingBlocked(videoDetail)) {
    return (
      <div
        role="alert"
        className="mx-auto max-w-3xl space-y-2 p-8 text-center"
      >
        <h1 className="text-lg font-semibold text-gray-800">
          마킹 — 영상 #{rawSn}
        </h1>
        <p className="text-sm text-gray-500">
          비식별 완료 후 마킹이 가능합니다.
        </p>
      </div>
    );
  }

  const videoSrc = streamUrl?.url ?? '';

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-4">
      <h1 className="text-lg font-semibold">마킹 — 영상 #{rawSn}</h1>

      {videoSrc ? (
        <VideoPlayer
          ref={videoRef}
          src={videoSrc}
          onSrcError={handleStreamError}
          onDurationChange={handleDurationChange}
        />
      ) : (
        <div className="flex aspect-video w-full items-center justify-center rounded-lg bg-black text-sm text-gray-400">
          영상을 불러오는 중…
        </div>
      )}

      <MarkingTimeline
        marks={localMarks}
        durationSec={durationSec}
        selectedIndex={selectedMarkIndex}
        onSelect={selectMark}
      />

      <MarkingToolbar
        mode={mode}
        intervalFrames={intervalFrames}
        onModeChange={(m: MarkingMode) => setMode(m)}
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
                    ? 'bg-primary-600 text-white'
                    : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                }`}
              >
                F{mark.frameIndex} {mark.timestamp && `(${mark.timestamp})`}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
