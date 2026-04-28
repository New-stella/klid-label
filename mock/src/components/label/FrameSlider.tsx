import { useRef, useCallback, useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Play, Pause } from 'lucide-react';
import { useLabelStore } from '../../store/labelStore';

export function FrameSlider() {
  const currentFrame = useLabelStore((s) => s.currentFrame);
  const totalFrames = useLabelStore((s) => s.totalFrames);
  const setFrame = useLabelStore((s) => s.setFrame);
  const [isPlaying, setIsPlaying] = useState(false);
  const playIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPlay = useCallback(() => {
    if (playIntervalRef.current) {
      clearInterval(playIntervalRef.current);
      playIntervalRef.current = null;
    }
    setIsPlaying(false);
  }, []);

  const handlePlayToggle = useCallback(() => {
    if (isPlaying) {
      stopPlay();
    } else {
      setIsPlaying(true);
      playIntervalRef.current = setInterval(() => {
        useLabelStore.setState((state) => {
          const next = state.currentFrame + 1;
          if (next >= state.totalFrames) {
            clearInterval(playIntervalRef.current!);
            playIntervalRef.current = null;
            setIsPlaying(false);
            return { currentFrame: 0, selectedId: null };
          }
          return { currentFrame: next, selectedId: null };
        });
      }, 500);
    }
  }, [isPlaying, stopPlay]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (playIntervalRef.current) clearInterval(playIntervalRef.current);
    };
  }, []);

  // Stop playback when reaching last frame
  useEffect(() => {
    if (currentFrame >= totalFrames - 1 && isPlaying) {
      stopPlay();
    }
  }, [currentFrame, totalFrames, isPlaying, stopPlay]);

  // Timecode (1fps assumption)
  const seconds = currentFrame;
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0');
  const ss = String(seconds % 60).padStart(2, '0');
  const timecode = `${mm}:${ss}`;

  const prev = () => {
    if (currentFrame > 0) setFrame(currentFrame - 1);
  };
  const next = () => {
    if (currentFrame < totalFrames - 1) setFrame(currentFrame + 1);
  };

  return (
    <div className="flex items-center gap-2 px-3 bg-gray-800 h-full border-t border-gray-700">
      <button
        onClick={prev}
        disabled={currentFrame === 0}
        className="p-1 rounded text-gray-300 hover:text-white hover:bg-gray-700 disabled:text-gray-600 disabled:cursor-not-allowed transition-colors"
        aria-label="이전 프레임"
      >
        <ChevronLeft size={16} />
      </button>

      <button
        onClick={handlePlayToggle}
        className="p-1 rounded text-gray-300 hover:text-white hover:bg-gray-700 transition-colors"
        aria-label={isPlaying ? '정지' : '재생'}
      >
        {isPlaying ? <Pause size={16} /> : <Play size={16} />}
      </button>

      <input
        type="range"
        min={0}
        max={totalFrames - 1}
        value={currentFrame}
        onChange={(e) => setFrame(Number(e.target.value))}
        className="flex-1 accent-blue-500 h-1.5"
        aria-label="프레임 슬라이더"
      />

      <button
        onClick={next}
        disabled={currentFrame >= totalFrames - 1}
        className="p-1 rounded text-gray-300 hover:text-white hover:bg-gray-700 disabled:text-gray-600 disabled:cursor-not-allowed transition-colors"
        aria-label="다음 프레임"
      >
        <ChevronRight size={16} />
      </button>

      <span className="text-xs text-gray-300 font-mono tabular-nums whitespace-nowrap">
        {currentFrame + 1} / {totalFrames} · {timecode}
      </span>
    </div>
  );
}
