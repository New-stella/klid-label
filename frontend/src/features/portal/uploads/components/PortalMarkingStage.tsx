/**
 * 포털 업로드 마킹 무대 — 영상 · 마킹 눈금 · 재생 조작을 **한 표면**에 담는다. [@design SCREEN-045]
 *
 * <h3>모양 — 포털 저작도구 화면이 정본</h3>
 * 짜임·조각은 포털 화면(KLID_Portal `AuthoringMarkingView` 의 재생기 카드)과 같다 — 흰 카드 한 장에
 * 영상(`VideoStage`) → 마킹 지점 줄(`MarkTimeline`) → 재생 줄(`PlaybackBar`) → 배속(`Dropdown`)을 쌓는다.
 * 조각은 포털 저장소 것을 그대로 가져다 쓴다(복사하지 않는다). 이 파일에 남는 것은 **실제 영상을 다루는
 * 흐름**(재생 요소 · 재생 위치 · 끊김 · 회복 신호)뿐이다.
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
 * <h3>눈금에는 재생 머리가 함께 있다</h3>
 * 마킹 지점만 있으면 «지금 어디를 보고 있는지» 가 눈금에 없어서, 수동으로 찍을 때 사용자가
 * 영상과 눈금을 번갈아 봐야 한다. 재생 머리를 같은 축에 그리면 «여기서 찍으면 저 자리에 선다» 가
 * 한눈에 읽힌다.
 */
import { forwardRef, useCallback, useImperativeHandle, useMemo, useRef, useState } from 'react';

import { Dropdown, type DropdownOption } from '@portal/components/custom';
import { MarkTimeline } from '@portal/pages/workspace/authoring/MarkTimeline';
import { PlaybackBar } from '@portal/pages/workspace/authoring/PlaybackBar';
import { VideoStage } from '@portal/pages/workspace/authoring/VideoStage';
import '@portal/pages/workspace/authoring/AuthoringMarkingView.css';

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
  /** 눈금 좌표의 분모가 되는 영상 길이(초). 0 이면 눈금을 세우지 않는다. */
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
  /**
   * 이 소스를 재생할 수 있게 됐다 = **재생이 회복됐다.**
   *
   * 화면은 이 신호로 재발급 재시도 예산을 되돌린다. 없으면 예산이 누적으로만 줄어
   * 서명이 여러 번 만료되는 정상 동선에서 **회복 경로가 영구히 닫힌다**
   * (마킹은 오래 머무는 화면이라 만료가 여러 번 일어난다).
   */
  onSrcRecovered?: () => void;
  /** 메타데이터에서 읽은 실제 길이(초). 원장 길이가 없을 때의 눈금 분모로 쓰인다. */
  onDurationChange?: (sec: number) => void;
}

/** 배속 — 관제 마킹과 같은 여섯 단계다(사용자가 채널을 오가며 다른 선택지를 만나지 않게). */
const SPEEDS = [0.25, 0.5, 1, 1.5, 2, 4] as const;
const SPEED_OPTIONS: DropdownOption[] = SPEEDS.map((s) => ({ value: String(s), label: `${s}x` }));

/** `mm:ss`. 길이를 모르는 동안(NaN·Infinity)에도 자리를 지킨다. */
function formatClock(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '00:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export const PortalMarkingStage = forwardRef<PortalMarkingStageHandle, PortalMarkingStageProps>(
  function PortalMarkingStage(
    {
      src, marks, durationSec, fps, selectedIndex, onSelectMark,
      onSrcError, onSrcRecovered, onDurationChange,
    },
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
    const seekMax = Number.isFinite(duration) && duration > 0 ? duration : durationSec;
    const totalFrames = durationSec * fps;
    const hasRuler = totalFrames > 0;
    /*
     * 재생 머리 — 눈금과 같은 가로 자(총 프레임)로 옮겨 넘긴다. 비율은 재생 요소가 읽은 길이 기준이다
     * (재생 막대와 같은 자리에 서야 한다). 길이를 모르면 그리지 않는다 — 0 에 붙어 있으면 «맨 앞» 이라는
     * 거짓을 말한다.
     */
    const headAt =
      hasRuler && seekMax > 0 ? Math.min(1, currentTime / seekMax) * totalFrames : undefined;

    // 눈금 조각은 지점을 프레임 번호로 받는다 — 번호 ↔ 목록 순번 · 표시 시각을 여기서 잇는다.
    const frames = useMemo(() => marks.map((m) => m.frameIndex), [marks]);
    const stampOf = useMemo(() => new Map(marks.map((m) => [m.frameIndex, m.timestamp])), [marks]);
    const selectedFrame = selectedIndex !== null ? (marks[selectedIndex]?.frameIndex ?? null) : null;

    return (
      <section className="klid-section-card klid-marking-player" aria-label="영상 재생">
        {/*
          ★<b>무대 비율을 바깥 상자가 갖는다.</b> 재생 요소에 높이를 맡기면 메타데이터가 오기 전에는 고유
            크기가 없어 상자가 납작하게 접힌다 — 영상 자리 조각이 16:9 판을 먼저 잡아 둔다.
          끊겨 기다리는 동안은 조각이 멈춘 장면을 흐리게 덮고 「불러오는 중」을 띄운다.
        */}
        <VideoStage
          status={buffering ? 'buffering' : 'ready'}
          message="불러오는 중"
          media={
            /* 자막 트랙 미제공 — 이용자가 올린 영상의 음성을 저작도구가 옮겨 적지 않는다. */
            /* eslint-disable-next-line jsx-a11y/media-has-caption */
            <video
              ref={videoRef}
              src={src}
              onTimeUpdate={() => setCurrentTime(videoRef.current?.currentTime ?? 0)}
              onLoadedMetadata={handleLoadedMetadata}
              onPlay={() => setPlaying(true)}
              onPause={() => setPlaying(false)}
              onError={() => onSrcError?.()}
              onWaiting={() => setBuffering(true)}
              onSeeking={() => setBuffering(true)}
              onCanPlay={() => {
                setBuffering(false);
                // 재생 가능해진 것이 곧 회복 신호다 — `onPlaying` 이 아니라 여기다.
                // 이용자가 재생을 누르지 않아도 소스가 되살아난 것은 사실이기 때문이다.
                onSrcRecovered?.();
              }}
              onPlaying={() => setBuffering(false)}
              onSeeked={() => setBuffering(false)}
            />
          }
        />

        <div className="klid-marking-controls">
          {/* 마킹 지점 줄 — 영상 바로 아래, 재생 줄과 같은 가로 자 위에 둔다.
              길이를 모르면 눈금 자리를 셀 수 없어 지점을 세우지 않는다 */}
          <MarkTimeline
            marks={hasRuler ? frames : []}
            total={totalFrames}
            markLabel={(f) => {
              const stamp = stampOf.get(f);
              return `프레임 ${f}${stamp ? ` (${stamp})` : ''}`;
            }}
            onSeek={(f) => {
              const index = frames.indexOf(f);
              if (index >= 0) onSelectMark(index);
            }}
            position={headAt}
            selected={selectedFrame}
          />
          <div className="klid-marking-playback">
            <PlaybackBar
              playing={playing}
              onToggle={togglePlay}
              position={currentTime}
              max={seekMax || 1}
              step={0.01}
              onSeek={(t) => {
                if (videoRef.current) videoRef.current.currentTime = t;
                setCurrentTime(t);
              }}
              seekValueText={formatClock(currentTime)}
              lead={`${formatClock(currentTime)} / ${formatClock(seekMax)}`}
            />
            {/* 배속은 재생 줄 한 줄 아래 끝 — 줄 끝에 꽂으면 좁은 재생기에서 위치 막대가 눌린다(포털 화면과 같다) */}
            <div className="klid-marking-speed">
              <Dropdown
                size="small"
                aria-label="재생 속도"
                options={SPEED_OPTIONS}
                value={String(rate)}
                onChange={(v) => changeRate(Number(v))}
              />
            </div>
          </div>
        </div>
      </section>
    );
  },
);
