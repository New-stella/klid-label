// 포털 라벨링 — 맨 아래 재생 줄(재생·멈춤 · 위치 막대 · 번호와 시간).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 재생 줄(`DarkFrameSlider`)과 같은 규칙이다 —
//   · 재생하면 0.5초마다 다음 프레임으로 넘기고, 끝 프레임에서 멈춘다
//   · 저장 안 한 변경이 있으면 다음으로 넘어가려는 순간 재생을 멈추고 이동 요청을 **한 번만** 보낸다
//     (화면이 이동 전 확인 창을 띄운다 — 멈추지 않으면 창이 계속 뜬다)
//   · 편집 차단 중에는 재생 · 이동을 막고, 차단이 걸리면 돌던 재생도 멈춘다
//   · 막대를 끌면 그 위치로 곧바로 이동 요청을 보낸다
//   · 시간 표기는 원본과 같이 프레임 순번을 초로 본 분:초다
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면의 재생 줄 조각(`PlaybackBar`)을 그대로 쓴다 — 줄 끝에 「1 / 23 · 00:00」.

import { useCallback, useEffect, useRef, useState } from 'react';

import { PlaybackBar } from '@portal/pages/workspace/authoring/PlaybackBar';

const PLAY_INTERVAL_MS = 500;

export function PortalFramePlayback({
  currentIndex,
  totalFrames,
  onSelect,
  dirtyGuard,
  disabled,
}: {
  currentIndex: number;
  totalFrames: number;
  onSelect: (index: number) => void;
  /** 저장 안 한 변경이 있다 — 자동 재생이 넘어가려는 순간 멈춘다. */
  dirtyGuard: boolean;
  /** 편집 차단(장시간 작업) 중 — 재생 · 이동을 막는다. */
  disabled: boolean;
}) {
  const [isPlaying, setIsPlaying] = useState(false);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPlay = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    setIsPlaying(false);
  }, []);

  useEffect(() => {
    if (!isPlaying || disabled) return;
    intervalRef.current = setInterval(() => {
      const next = currentIndex + 1;
      if (next >= totalFrames) {
        stopPlay();
        return;
      }
      if (dirtyGuard) {
        stopPlay();
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
  }, [isPlaying, disabled, currentIndex, totalFrames, onSelect, dirtyGuard, stopPlay]);

  useEffect(() => {
    if (disabled) stopPlay();
  }, [disabled, stopPlay]);

  useEffect(
    () => () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    },
    [],
  );

  const mm = String(Math.floor(currentIndex / 60)).padStart(2, '0');
  const ss = String(currentIndex % 60).padStart(2, '0');
  const total = Math.max(totalFrames, 1);

  return (
    <PlaybackBar
      playing={isPlaying}
      onToggle={() => {
        if (disabled) return;
        if (isPlaying) stopPlay();
        else setIsPlaying(true);
      }}
      position={currentIndex}
      max={Math.max(0, total - 1)}
      onSeek={(next) => {
        if (disabled) return;
        onSelect(next);
      }}
      seekLabel="프레임 위치"
      seekValueText={`프레임 ${currentIndex + 1} / ${total}`}
      trail={`${currentIndex + 1} / ${total} · ${mm}:${ss}`}
    />
  );
}
