/**
 * 포털 업로드 마킹 무대 — 영상 · 마킹 눈금 · 재생 조작을 **한 표면**에 담는다. [@design SCREEN-045]
 * [@design DS-002]
 *
 * <h3>왜 관제 채널 재생기를 쓰지 않나</h3>
 * `features/marking/components/VideoPlayer` 는 <b>관제 마킹 화면과 함께 쓰는 부품</b>이다
 * (`pages/MarkingPage.tsx`). 거기에 포털 모양을 넣으면 관제 화면이 같이 바뀐다 — 사용자 확정
 * 구속(<b>관제향 화면·컴포넌트 불변</b>)에 걸린다. 그래서 포털은 자기 무대를 갖는다.
 *
 * ⚠ 그 대가로 재생 로직이 두 벌이 된다. 그래서 <b>이 무대가 밖으로 내는 창구를 최소로 좁혔다</b> —
 *   포털 마킹 화면이 실제로 쓰는 것은 「지금 위치를 묻는다 · 그 자리로 옮긴다」 둘뿐이라,
 *   관제 재생기의 나머지 창구(프레임 번호 환산 · 길이 조회)를 따라 만들지 않는다.
 *   ★프레임 번호 환산은 <b>여기서 하지 않는다</b> — 그 공식은 `markingFps` 한 곳이 소유하고
 *   호출부가 시각을 받아 그 모듈에 묻는다(공식을 두 벌로 만들지 않는다).
 *
 * <h3>세 조각을 왜 한 표면에 두나</h3>
 * 셋 다 <b>같은 시간축</b>을 말한다 — 재생 위치 · 마킹 지점 · 탐색 막대. 예전에는 영상과 눈금이
 * 서로 다른 상자로 떨어져 있어, 눈금의 막대가 영상의 어느 순간인지 눈으로 이어 붙여야 했다.
 * 한 표면에 세로로 쌓으면 세 축이 같은 가로 좌표를 공유해 그 연결이 화면에 드러난다.
 *
 * <h3>눈금에는 재생 머리가 함께 있다</h3>
 * 마킹 지점만 있으면 «지금 어디를 보고 있는지» 가 눈금에 없어서, 수동으로 찍을 때 사용자가
 * 영상과 눈금을 번갈아 봐야 한다. 재생 머리를 같은 축에 그리면 «여기서 찍으면 저 자리에 선다» 가
 * 한눈에 읽힌다.
 */
import { forwardRef, useCallback, useImperativeHandle, useRef, useState } from 'react';
import { Pause, Play } from 'lucide-react';

import { markAriaLabel } from '@/features/marking/components/MarkingTimeline';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { PORTAL_SURFACE } from '@/components/portal/ui/portalControl';

import type { MarkItem } from '../markingTypes';

/** 이 무대가 밖으로 내는 창구 — 호출부가 실제로 쓰는 둘뿐이다(위 ⚠ 참조). */
export interface PortalMarkingStageHandle {
  /** 지금 재생 위치(초). 영상이 아직 없으면 0. */
  getCurrentTime: () => number;
  /** 그 시각으로 옮긴다. */
  seekTo: (timeSec: number) => void;
}

export interface PortalMarkingStageProps {
  src: string;
  /** 눈금에 그릴 마킹 지점. */
  marks: MarkItem[];
  /** 눈금 좌표의 분모가 되는 영상 길이(초). 0 이면 눈금을 그리지 않는다. */
  durationSec: number;
  /**
   * 영상 실 초당 프레임 수. 마킹 지점의 좌표는 `프레임 번호 / 총 프레임` 이므로 그 번호를 만들 때
   * 쓴 값과 <b>같아야 한다</b> — 다르면 막대가 실제와 어긋난 자리에 선다.
   */
  fps: number;
  selectedIndex: number | null;
  onSelectMark: (index: number) => void;
  /** 재생 주소가 만료돼 로드가 실패했을 때 — 호출부가 주소를 다시 받아 `src` 를 갈아 준다. */
  onSrcError?: () => void;
  /** 메타데이터에서 읽은 실제 길이(초). 원장 길이가 없을 때의 눈금 분모로 쓰인다. */
  onDurationChange?: (sec: number) => void;
}

/** 배속 — 관제 마킹과 같은 여섯 단계다(사용자가 채널을 오가며 다른 선택지를 만나지 않게). */
const SPEEDS = [0.25, 0.5, 1, 1.5, 2, 4] as const;

/** `mm:ss`. 길이를 모르는 동안(NaN·Infinity)에도 자리를 지킨다. */
function formatClock(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '00:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export const PortalMarkingStage = forwardRef<PortalMarkingStageHandle, PortalMarkingStageProps>(
  function PortalMarkingStage(
    { src, marks, durationSec, fps, selectedIndex, onSelectMark, onSrcError, onDurationChange },
    ref,
  ) {
    const videoRef = useRef<HTMLVideoElement>(null);
    const [playing, setPlaying] = useState(false);
    const [currentTime, setCurrentTime] = useState(0);
    const [duration, setDuration] = useState(0);
    const [rate, setRate] = useState(1);
    const [buffering, setBuffering] = useState(false);

    useImperativeHandle(ref, () => ({
      getCurrentTime: () => videoRef.current?.currentTime ?? 0,
      seekTo: (timeSec: number) => {
        if (videoRef.current) videoRef.current.currentTime = timeSec;
      },
    }));

    const togglePlay = useCallback(() => {
      const v = videoRef.current;
      if (!v) return;
      if (v.paused) void v.play();
      else v.pause();
    }, []);

    const changeRate = useCallback((next: number) => {
      if (videoRef.current) videoRef.current.playbackRate = next;
      setRate(next);
    }, []);

    const handleLoadedMetadata = useCallback(() => {
      const d = videoRef.current?.duration ?? 0;
      setDuration(d);
      // 유효한 길이일 때만 알린다 — 일부 스트림은 길이가 확정되지 않아 NaN·Infinity 로 온다.
      if (Number.isFinite(d) && d > 0) onDurationChange?.(d);
    }, [onDurationChange]);

    // 탐색 막대의 최댓값 — 원장 길이가 없으면 재생 요소가 읽은 길이를 쓴다.
    const seekMax = duration > 0 ? duration : durationSec;
    const totalFrames = durationSec * fps;
    const showRuler = totalFrames > 0;
    // 재생 머리 — 길이를 모르면 그리지 않는다(0 에 붙어 있으면 «맨 앞» 이라는 거짓을 말한다).
    const headPct = seekMax > 0 ? Math.min(100, (currentTime / seekMax) * 100) : null;

    return (
      <div className={cn(PORTAL_SURFACE, 'overflow-hidden')}>
        {/*
          영상 — 어두운 면이 표면 가장자리까지 닿는다. 높이를 화면 높이에 비례해 묶어 두어야
          아래 눈금·조작이 <b>한 화면에 함께</b> 보인다(스크롤해야 조작이 나오면 마킹이 두 손
          일이 된다). 비율이라 낮은 창에서도 조작이 밀려나지 않는다.

          ★<b>상한은 남는 높이에서 거꾸로 잡은 값</b>이다 — 위의 구역 머리와 아래의 눈금·조작이
            쓰는 만큼을 빼고 남는 자리를 영상에 준다. 그래서 이 값을 올리려면 <b>먼저 다른 데서
            높이를 벌어야</b> 한다(그 반대로 하면 조작이 화면 밖으로 밀려난다). 넓은 창에서는
            가로 폭이 먼저 한계가 되어 영상이 표면을 꽉 채우고 좌우 여백이 사라진다.

          ★<b>무대 비율을 바깥 상자가 갖는다.</b> 재생 요소에 높이를 맡기면 <b>메타데이터가
            오기 전에는 고유 크기가 없어 상자가 납작하게 접힌다</b> — 그리고 영상이 뜨는 순간
            아래 눈금·조작이 통째로 밀려 내려간다. 비율을 미리 잡아 두면 처음부터 끝까지
            같은 자리를 지킨다(가로세로가 다른 영상은 그 안에서 여백을 갖는다).
        */}
        <div className="relative aspect-video max-h-[65vh] w-full bg-gray-900">
          {/* 자막 트랙 미제공 — 이용자가 올린 영상의 음성을 저작도구가 옮겨 적지 않는다. */}
          {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
          <video
            ref={videoRef}
            src={src}
            className="absolute inset-0 size-full bg-gray-900 object-contain"
            onTimeUpdate={() => setCurrentTime(videoRef.current?.currentTime ?? 0)}
            onLoadedMetadata={handleLoadedMetadata}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
            onError={() => onSrcError?.()}
            onWaiting={() => setBuffering(true)}
            onSeeking={() => setBuffering(true)}
            onCanPlay={() => setBuffering(false)}
            onPlaying={() => setBuffering(false)}
            onSeeked={() => setBuffering(false)}
          />
          {buffering && (
            <div
              data-testid="portal-marking-buffering"
              role="status"
              className="absolute inset-0 z-10 flex items-center justify-center bg-gray-900/40"
            >
              <span className="rounded-pill bg-white/90 px-in-component py-tight text-caption text-gray-700">
                불러오는 중
              </span>
            </div>
          )}
        </div>

        {/* 마킹 눈금 — 영상 바로 아래, 같은 가로 좌표축 위에 둔다. */}
        {showRuler && (
          <div className="border-t border-gray-200 px-in-component pb-tight pt-tight">
            <div className="mb-tight flex items-baseline justify-between gap-inline">
              <span className="text-caption text-gray-600">마킹 지점</span>
              <span className="text-caption tabular-nums text-gray-500">{marks.length}건</span>
            </div>
            <div className="relative h-7 overflow-hidden rounded-input bg-gray-100">
              {/* 재생 머리 — 조작이 아니라 표시다(누를 수 있는 것은 마킹 막대뿐이다). */}
              {headPct !== null && (
                <div
                  aria-hidden
                  data-testid="portal-marking-playhead"
                  className="absolute top-0 h-full w-0.5 bg-gray-500"
                  style={{ left: `${headPct}%` }}
                />
              )}
              {marks.map((mark, i) => {
                const pct = (mark.frameIndex / totalFrames) * 100;
                const selected = selectedIndex === i;
                return (
                  <button
                    key={mark.frameIndex}
                    type="button"
                    onClick={() => onSelectMark(i)}
                    aria-label={markAriaLabel(mark)}
                    aria-pressed={selected}
                    title={`프레임 ${mark.frameIndex}${mark.timestamp ? ` (${mark.timestamp})` : ''}`}
                    className={cn(
                      // 누르는 자리는 넉넉히(가로 8px) 두고, 보이는 막대는 그 안에 가늘게 그린다 —
                      // 4px 막대는 정확히 겨누기 어렵고 서로 붙으면 어느 것을 눌렀는지 모른다.
                      'absolute top-0 flex h-full w-2 -translate-x-1/2 justify-center',
                      KRDS_FOCUS,
                    )}
                    style={{ left: `${pct}%` }}
                  >
                    <span
                      aria-hidden
                      className={cn(
                        'h-full w-0.5 rounded-pill transition-colors',
                        selected ? 'w-1 bg-primary-600' : 'bg-primary-400',
                      )}
                    />
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* 재생 조작 — 왼쪽부터 「재생 · 시각 · 탐색 · 배속」. 시선이 왼쪽에서 오른쪽으로
            «무엇을 · 언제 · 어디로 · 얼마나 빠르게» 로 이어진다. */}
        <div className="flex flex-wrap items-center gap-in-component border-t border-gray-200 px-in-component py-dense">
          <button
            type="button"
            onClick={togglePlay}
            aria-label={playing ? '일시정지' : '재생'}
            data-testid="portal-marking-play"
            className={cn(
              'inline-flex size-10 shrink-0 items-center justify-center rounded-full',
              'bg-primary-500 text-white transition-colors duration-fast',
              'hover:bg-primary-600 active:bg-primary-700',
              KRDS_FOCUS,
            )}
          >
            {playing ? (
              <Pause className="size-5" aria-hidden />
            ) : (
              <Play className="size-5 translate-x-px" aria-hidden />
            )}
          </button>

          <span className="shrink-0 text-caption tabular-nums text-gray-700">
            {formatClock(currentTime)}
            <span className="text-gray-400"> / {formatClock(seekMax)}</span>
          </span>

          <input
            type="range"
            aria-label="재생 위치"
            min={0}
            max={seekMax || 1}
            step={0.01}
            value={currentTime}
            onChange={(e) => {
              const t = parseFloat(e.target.value);
              if (videoRef.current) videoRef.current.currentTime = t;
              setCurrentTime(t);
            }}
            className={cn('h-1.5 min-w-40 flex-1 cursor-pointer accent-primary-500', KRDS_FOCUS)}
          />

          {/* 배속 — 트랙 안에서 고른 값만 흰 알약으로 떠오른다. 낱개 버튼 여섯을 늘어놓는 것보다
              «하나를 고르는 자리» 라는 것이 형태로 드러난다. */}
          <div
            role="group"
            aria-label="재생 속도"
            className="flex shrink-0 items-center gap-0.5 rounded-pill bg-gray-100 p-1"
          >
            {SPEEDS.map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => changeRate(s)}
                aria-pressed={rate === s}
                className={cn(
                  'inline-flex h-8 min-w-11 items-center justify-center rounded-pill px-2',
                  'text-caption transition-colors duration-fast',
                  KRDS_FOCUS,
                  rate === s ? 'bg-white text-primary-600' : 'text-gray-600 hover:text-gray-900',
                )}
              >
                {s}x
              </button>
            ))}
          </div>
        </div>
      </div>
    );
  },
);
