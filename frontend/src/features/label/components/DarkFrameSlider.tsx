// SCR-LABEL-001 하단 프레임 슬라이더 + 재생 컨트롤 (mock 정합 — 다크 톤, 40px).

import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, Pause, Play } from 'lucide-react';

interface DarkFrameSliderProps {
  currentIndex: number;
  totalFrames: number;
  onSelect: (index: number) => void;
  /**
   * R5 — 미저장 변경(dirty) 존재 여부. true 이면 자동 재생이 다음 프레임으로 넘어가려 할 때
   * 재생을 정지하고 이동 요청을 1회만 위임한다(상위 LabelingPage 가 가드 모달 노출).
   * 무한 팝업 방지를 위해 정지 후 인터벌을 종료한다. dirty 없으면 정상 자동 진행.
   */
  dirtyGuard?: boolean;
  /**
   * 프레임 전환 차단(장시간 작업 진행 중). true 면 재생/이동 컨트롤을 모두 비활성화하고
   * 자동 재생도 진행하지 않는다 — 이동이 막힌 상태에서 인터벌만 도는 것을 막는다.
   */
  disabled?: boolean;
}

const PLAY_INTERVAL_MS = 500;

export function DarkFrameSlider({
  currentIndex,
  totalFrames,
  onSelect,
  dirtyGuard = false,
  disabled = false,
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
    if (disabled) return;
    if (isPlaying) {
      stopPlay();
      return;
    }
    setIsPlaying(true);
  }, [disabled, isPlaying, stopPlay]);

  // 재생 인터벌 — currentIndex가 바뀔 때마다 재설정 (closure 문제 회피)
  useEffect(() => {
    if (!isPlaying || disabled) return;
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
      // R5 — 미저장 변경이 있으면 자동 재생을 멈추고 이동 요청을 1회만 위임(상위 가드 모달).
      // 정지 없이 계속 onSelect 를 쏘면 이동이 막힌 채 인터벌이 반복 발화(무한 팝업)한다.
      if (dirtyGuard) {
        setIsPlaying(false);
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
        }
        onSelect(next);
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
  }, [isPlaying, disabled, currentIndex, totalFrames, onSelect, dirtyGuard]);

  // 차단이 걸리면 진행 중이던 자동 재생을 즉시 멈춘다(정지 버튼도 눌리지 않으므로).
  useEffect(() => {
    if (disabled) stopPlay();
  }, [disabled, stopPlay]);

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
    <div className="flex items-center gap-2 px-3 bg-white h-full border-t border-gray-200">
      <button
        type="button"
        onClick={() => onSelect(Math.max(0, currentIndex - 1))}
        disabled={disabled || currentIndex === 0}
        aria-label="이전 프레임"
        className="flex h-11 w-11 items-center justify-center rounded text-gray-600 hover:text-gray-900 hover:bg-gray-100 disabled:text-gray-300 disabled:cursor-not-allowed transition-colors"
      >
        <ChevronLeft size={16} />
      </button>

      <button
        type="button"
        onClick={handlePlayToggle}
        disabled={disabled}
        aria-label={isPlaying ? '정지' : '재생'}
        className="flex h-11 w-11 items-center justify-center rounded text-gray-600 hover:text-gray-900 hover:bg-gray-100 disabled:text-gray-300 disabled:cursor-not-allowed transition-colors"
      >
        {isPlaying ? <Pause size={16} /> : <Play size={16} />}
      </button>

      <input
        type="range"
        min={0}
        max={max}
        value={currentIndex}
        onChange={(e) => onSelect(Number(e.target.value))}
        disabled={disabled}
        aria-label="프레임 슬라이더"
        className="flex-1 accent-primary-500 h-1.5 disabled:cursor-not-allowed disabled:opacity-50"
      />

      <button
        type="button"
        onClick={() => onSelect(Math.min(max, currentIndex + 1))}
        disabled={disabled || currentIndex >= max}
        aria-label="다음 프레임"
        className="flex h-11 w-11 items-center justify-center rounded text-gray-600 hover:text-gray-900 hover:bg-gray-100 disabled:text-gray-300 disabled:cursor-not-allowed transition-colors"
      >
        <ChevronRight size={16} />
      </button>

      <span className="text-mono text-gray-700 font-mono tabular-nums whitespace-nowrap">
        {currentIndex + 1} / {totalFrames} · {timecode}
      </span>
    </div>
  );
}
