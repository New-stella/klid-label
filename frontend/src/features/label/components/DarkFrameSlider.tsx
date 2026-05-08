// SCR-LABEL-001 하단 프레임 슬라이더 + 재생 컨트롤 (mock 정합 — 다크 톤, 40px).

import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, Pause, Play } from 'lucide-react';

interface DarkFrameSliderProps {
  currentIndex: number;
  totalFrames: number;
  onSelect: (index: number) => void;
}

const PLAY_INTERVAL_MS = 500;

export function DarkFrameSlider({
  currentIndex,
  totalFrames,
  onSelect,
}: DarkFrameSliderProps) {
  const [isPlaying, setIsPlaying] = useState(false);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPlay = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    setIsPlaying(false);
  }, []);

  const handlePlayToggle = useCallback(() => {
    if (isPlaying) {
      stopPlay();
      return;
    }
    setIsPlaying(true);
  }, [isPlaying, stopPlay]);

  // 재생 인터벌 — currentIndex가 바뀔 때마다 재설정 (closure 문제 회피)
  useEffect(() => {
    if (!isPlaying) return;
    intervalRef.current = setInterval(() => {
      const next = currentIndex + 1;
      if (next >= totalFrames) {
        setIsPlaying(false);
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
        }
        return;
      }
      onSelect(next);
    }, PLAY_INTERVAL_MS);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    };
  }, [isPlaying, currentIndex, totalFrames, onSelect]);

  // 언마운트 시 정리
  useEffect(() => {
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, []);

  const seconds = currentIndex;
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0');
  const ss = String(seconds % 60).padStart(2, '0');
  const timecode = `${mm}:${ss}`;

  const max = Math.max(0, totalFrames - 1);

  return (
    <div className="flex items-center gap-2 px-3 bg-gray-800 h-full border-t border-gray-700">
      <button
        type="button"
        onClick={() => onSelect(Math.max(0, currentIndex - 1))}
        disabled={currentIndex === 0}
        aria-label="이전 프레임"
        className="p-1 rounded text-gray-300 hover:text-white hover:bg-gray-700 disabled:text-gray-600 disabled:cursor-not-allowed transition-colors"
      >
        <ChevronLeft size={16} />
      </button>

      <button
        type="button"
        onClick={handlePlayToggle}
        aria-label={isPlaying ? '정지' : '재생'}
        className="p-1 rounded text-gray-300 hover:text-white hover:bg-gray-700 transition-colors"
      >
        {isPlaying ? <Pause size={16} /> : <Play size={16} />}
      </button>

      <input
        type="range"
        min={0}
        max={max}
        value={currentIndex}
        onChange={(e) => onSelect(Number(e.target.value))}
        aria-label="프레임 슬라이더"
        className="flex-1 accent-blue-500 h-1.5"
      />

      <button
        type="button"
        onClick={() => onSelect(Math.min(max, currentIndex + 1))}
        disabled={currentIndex >= max}
        aria-label="다음 프레임"
        className="p-1 rounded text-gray-300 hover:text-white hover:bg-gray-700 disabled:text-gray-600 disabled:cursor-not-allowed transition-colors"
      >
        <ChevronRight size={16} />
      </button>

      <span className="text-xs text-gray-300 font-mono tabular-nums whitespace-nowrap">
        {currentIndex + 1} / {totalFrames} · {timecode}
      </span>
    </div>
  );
}
