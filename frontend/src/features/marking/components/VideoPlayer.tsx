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
   * 재생이 <b>실제로 회복</b>됐을 때(= 이 소스를 재생할 수 있게 됐을 때) 호출.
   * 상위에서 재발급 재시도 예산을 되돌리는 데 사용한다.
   *
   * <p>신호로 `canplay` 를 고른 이유 — `loadedmetadata` 는 길이만 읽힌 상태라 「재생 가능」을
   * 뜻하지 않고, `playing` 은 소스가 바뀌면 요소가 일시정지 상태로 돌아가므로 사용자가 다시
   * 재생을 누를 때까지 오지 않는다(자동 재발급으로 회복된 경우를 놓친다). `canplay` 는 새 소스가
   * 실제로 재생 가능해진 시점에 사용자의 조작 없이 온다.
   * ⚠ 401·404 처럼 회복되지 않는 실패에서는 `error` 만 오고 `canplay` 는 <b>오지 않는다</b> —
   * 그래서 폭주 구간에서는 예산이 되돌아오지 않는다.
   */
  onSrcRecovered?: () => void;
  /**
   * onLoadedMetadata 로 실제 영상 길이(초)를 얻으면 호출.
   * 상위(타임라인 등)에서 하드코딩 대신 실제 길이로 좌표를 계산하는 데 사용한다.
   */
  onDurationChange?: (sec: number) => void;
}

const SPEED_OPTIONS = [0.25, 0.5, 1, 1.5, 2, 4] as const;

export const VideoPlayer = forwardRef<VideoPlayerHandle, VideoPlayerProps>(
  function VideoPlayer({ src, className, onSrcError, onSrcRecovered, onDurationChange }, ref) {
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
          {/* 자막 트랙(<track kind="captions">) 미제공 — 이 플레이어가 재생하는 것은 무음의
              CCTV 비식별 영상이라 옮겨 적을 음성이 없다. 자막 파일 자체가 존재하지 않으므로
              빈 track 을 넣는 것은 규칙만 만족시키고 사용자에게는 아무것도 주지 않는다.
              음성이 있는 영상을 이 플레이어로 재생하게 되면 이 disable 을 걷고 자막을 붙일 것. */}
          {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
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
            onCanPlay={() => {
              setBuffering(false);
              // 이 소스를 재생할 수 있게 됐다 = 재생이 회복됐다(위 onSrcRecovered 주석 참조).
              onSrcRecovered?.();
            }}
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
        <div className="flex items-center gap-3 text-body-md text-gray-600">
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
                  'inline-flex min-h-11 items-center justify-center px-2 rounded text-caption',
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
