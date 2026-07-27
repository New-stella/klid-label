import { forwardRef, useCallback, useImperativeHandle, useRef, useState } from 'react';
import { Spinner } from '@/components/common/Spinner';
import { cn } from '@/lib/cn';
import { markingFrameIndex } from '../markingFps';

export interface VideoPlayerHandle {
  getCurrentTime: () => number;
  /**
   * 현재 재생 위치의 frameIndex. fps 는 <b>필수</b>다 — 기본값(30) 을 두면 호출부가 실 fps 를 넘기지
   * 않아도 조용히 30fps 로 계산되어, 서버의 마킹 상한(실 fps 기준)과 어긋난 값이 만들어진다
   * (실측: 25fps 영상 뒷부분 마킹이 400). 호출부는 서버가 내려준 영상 fps 를 넘긴다.
   */
  getCurrentFrame: (fps: number) => number;
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

const SPEED_OPTIONS = [0.25, 0.5, 1, 1.5, 2, 4] as const;

export const VideoPlayer = forwardRef<VideoPlayerHandle, VideoPlayerProps>(
  function VideoPlayer({ src, className, onSrcError, onDurationChange }, ref) {
    const videoRef = useRef<HTMLVideoElement>(null);
    const [playing, setPlaying] = useState(false);
    const [currentTime, setCurrentTime] = useState(0);
    const [duration, setDuration] = useState(0);
    const [playbackRate, setPlaybackRate] = useState(1);
    // 버퍼링/탐색 중 스피너 — waiting/seeking 시 true, canplay/playing/seeked 시 false.
    const [buffering, setBuffering] = useState(false);

    const changeSpeed = useCallback((rate: number) => {
      if (videoRef.current) videoRef.current.playbackRate = rate;
      setPlaybackRate(rate);
    }, []);

    useImperativeHandle(ref, () => ({
      getCurrentTime: () => videoRef.current?.currentTime ?? 0,
      // frameIndex 공식은 markingFps 모듈 한 곳에만 둔다(BE 반올림 규칙과 1:1 — H10).
      getCurrentFrame: (fps: number) =>
        markingFrameIndex(videoRef.current?.currentTime ?? 0, fps),
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
        <div className="relative w-full">
          <video
            ref={videoRef}
            src={src}
            className="w-full rounded-lg bg-black"
            onTimeUpdate={() => setCurrentTime(videoRef.current?.currentTime ?? 0)}
            onLoadedMetadata={handleLoadedMetadata}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
            onError={() => onSrcError?.()}
            // 버퍼 고갈(waiting)·탐색(seeking) 시 스피너 노출, 재생 가능(canplay/playing)·
            // 탐색 완료(seeked) 시 해제. 느린 네트워크에서 화면이 멈춘 이유를 사용자에게 알린다.
            onWaiting={() => setBuffering(true)}
            onSeeking={() => setBuffering(true)}
            onCanPlay={() => setBuffering(false)}
            onPlaying={() => setBuffering(false)}
            onSeeked={() => setBuffering(false)}
          />
          {buffering && (
            <div
              data-testid="video-buffering-spinner"
              role="status"
              className="absolute inset-0 z-10 flex items-center justify-center rounded-lg bg-black/30"
            >
              <Spinner size="lg" label="버퍼링 중" />
            </div>
          )}
        </div>
        <div className="flex items-center gap-3 text-sm text-gray-600">
          <button
            type="button"
            onClick={togglePlay}
            className="min-h-11 rounded px-3 bg-gray-100 hover:bg-gray-200 transition-colors"
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
                  // 세그먼트(배속) 컨트롤 — 재생 버튼과 동일하게 KRDS 최소 터치 높이(min-h-11) 확보.
                  'inline-flex min-h-11 items-center justify-center px-2 rounded text-xs',
                  playbackRate === rate ? 'bg-primary-600 text-white' : 'bg-gray-100',
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
