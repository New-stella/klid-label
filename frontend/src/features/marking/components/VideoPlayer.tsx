import { forwardRef, useCallback, useImperativeHandle, useRef, useState } from 'react';
import { cn } from '@/lib/cn';

export interface VideoPlayerHandle {
  getCurrentTime: () => number;
  getCurrentFrame: (fps?: number) => number;
  seekTo: (timeSec: number) => void;
  /** 메타데이터 로드 후 실제 영상 길이(초). 로드 전에는 0. */
  getDuration: () => number;
}

interface VideoPlayerProps {
  src: string;
  className?: string;
  /**
   * <video> 로드 실패(예: 서명 URL 만료로 401) 시 호출.
   * 상위에서 서명 URL 을 재발급해 src 를 교체하는 데 사용한다.
   */
  onSrcError?: () => void;
  /**
   * onLoadedMetadata 로 실제 영상 길이(초)를 얻으면 호출.
   * 상위(타임라인 등)에서 하드코딩 대신 실제 길이로 좌표를 계산하는 데 사용한다.
   */
  onDurationChange?: (sec: number) => void;
}

const NATIVE_FPS = 30;
const SPEED_OPTIONS = [0.25, 0.5, 1, 1.5, 2, 4] as const;

export const VideoPlayer = forwardRef<VideoPlayerHandle, VideoPlayerProps>(
  function VideoPlayer({ src, className, onSrcError, onDurationChange }, ref) {
    const videoRef = useRef<HTMLVideoElement>(null);
    const [playing, setPlaying] = useState(false);
    const [currentTime, setCurrentTime] = useState(0);
    const [duration, setDuration] = useState(0);
    const [playbackRate, setPlaybackRate] = useState(1);

    const changeSpeed = useCallback((rate: number) => {
      if (videoRef.current) videoRef.current.playbackRate = rate;
      setPlaybackRate(rate);
    }, []);

    useImperativeHandle(ref, () => ({
      getCurrentTime: () => videoRef.current?.currentTime ?? 0,
      getCurrentFrame: (fps = NATIVE_FPS) =>
        Math.round((videoRef.current?.currentTime ?? 0) * fps),
      seekTo: (timeSec: number) => {
        if (videoRef.current) videoRef.current.currentTime = timeSec;
      },
      getDuration: () => videoRef.current?.duration ?? 0,
    }));

    const handleLoadedMetadata = useCallback(() => {
      const d = videoRef.current?.duration ?? 0;
      setDuration(d);
      // 유효한 길이일 때만 상위에 통지 (NaN/Infinity 방어 — 일부 스트림은 duration 미확정).
      if (Number.isFinite(d) && d > 0) onDurationChange?.(d);
    }, [onDurationChange]);

    const togglePlay = useCallback(() => {
      const v = videoRef.current;
      if (!v) return;
      if (v.paused) {
        v.play();
        setPlaying(true);
      } else {
        v.pause();
        setPlaying(false);
      }
    }, []);

    const formatTime = (sec: number) => {
      const m = Math.floor(sec / 60);
      const s = Math.floor(sec % 60);
      return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    };

    return (
      <div className={cn('flex flex-col gap-2', className)}>
        <video
          ref={videoRef}
          src={src}
          className="w-full rounded-lg bg-black"
          onTimeUpdate={() => setCurrentTime(videoRef.current?.currentTime ?? 0)}
          onLoadedMetadata={handleLoadedMetadata}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onError={() => onSrcError?.()}
        />
        <div className="flex items-center gap-3 text-sm text-gray-600">
          <button
            type="button"
            onClick={togglePlay}
            className="rounded px-3 py-1 bg-gray-100 hover:bg-gray-200 transition-colors"
          >
            {playing ? '일시정지' : '재생'}
          </button>
          <div className="flex items-center gap-1">
            {SPEED_OPTIONS.map((rate) => (
              <button
                key={rate}
                type="button"
                onClick={() => changeSpeed(rate)}
                className={cn(
                  'px-1.5 py-0.5 rounded text-xs',
                  playbackRate === rate ? 'bg-blue-600 text-white' : 'bg-gray-100',
                )}
              >
                {rate}x
              </button>
            ))}
          </div>
          <input
            type="range"
            min={0}
            max={duration || 1}
            step={0.01}
            value={currentTime}
            onChange={(e) => {
              const t = parseFloat(e.target.value);
              if (videoRef.current) videoRef.current.currentTime = t;
              setCurrentTime(t);
            }}
            className="flex-1"
          />
          <span className="tabular-nums whitespace-nowrap">
            {formatTime(currentTime)} / {formatTime(duration)}
          </span>
        </div>
      </div>
    );
  },
);
