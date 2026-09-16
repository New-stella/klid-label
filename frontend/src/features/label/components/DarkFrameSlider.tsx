// SCR-LABEL-001 하단 프레임 슬라이더 + 재생 컨트롤 (mock 정합 — 다크 톤, 40px).

import { useCallback, useEffect, useRef, useState } from 'react';
import { Pause, Play } from 'lucide-react';

// ★포털 채널 전용 — 부모 포털 시안이 쓰는 킷 재생 줄. 관제 렌더 경로는 거치지 않는다.
import { PlaybackBar } from '@/components/portal/authoring';

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
  /**
   * 포털 채널이면 시안대로 **킷 재생 줄**을 그린다. 관제는 종전 줄 그대로다.
   * 갈리는 것은 <b>부품</b>뿐이고 재생·스크럽·미저장 가드 배선은 두 채널이 같은 것을 쓴다.
   */
  portalMode?: boolean;
}

const PLAY_INTERVAL_MS = 500;

export function DarkFrameSlider({
  currentIndex,
  totalFrames,
  onSelect,
  dirtyGuard = false,
  disabled = false,
  portalMode = false,
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
  const trail = `${currentIndex + 1} / ${totalFrames} · ${timecode}`;

  if (portalMode) {
    /*
      ★킷 재생 줄 — 코발트 원형 재생 단추 · 킷 막대 · 줄 끝 글.
        종전에는 날 `input[type=range]`(굵기 1.5) 라 부모 포털 옆에 두면 이 줄만 «되다 만» 것처럼
        보였다(2026-09-16 사용자 지적). 킷을 입으면 막대 굵기·손잡이 치수를 토큰이 정한다.
      ★줄 끝 글은 <b>두 채널이 같은 문자열</b>이다(`1 / 23 · 00:00`) — 시안과도 같다.
      ⚠ 막대 이름은 종전 그대로 「프레임 슬라이더」다. 킷 기본값(「재생 위치」)으로 바꾸면 이 줄을
        이름으로 집던 기존 가드가 끊긴다.
    */
    return (
      <div className="flex items-center px-3 py-2">
        <PlaybackBar
          /* 차단 중에는 눌러도 되돌려 보내지만(핸들러 가드) 그 사실이 보이지도 않으면
             고장으로 읽힌다 — 킷 재생 줄에는 잠금 축이 없어 겉면에서 막는다. */
          className={disabled ? 'w-full opacity-50 pointer-events-none' : 'w-full'}
          playing={isPlaying}
          onToggle={handlePlayToggle}
          position={currentIndex}
          max={max}
          onSeek={(next) => !disabled && onSelect(next)}
          seekLabel="프레임 슬라이더"
          seekValueText={trail}
          trail={trail}
        />
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2 px-3 bg-white h-full border-t border-gray-200">
      {/* ★이전/다음 프레임 버튼은 여기 두지 않는다 — 프레임 이동 버튼은 캔버스 상단 옵션바의
          프레임 이동 컨트롤(FrameNavigator)이 단독으로 담당한다(UI-052). 여기 남겨두면 같은
          aria-label 을 가진 버튼이 화면에 둘이 되어 보조기술·회귀 가드가 어느 쪽을 가리키는지
          결정되지 않는다. 이 표면은 스크럽(슬라이더)·자동 재생만 담당한다. */}
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

      <span className="text-mono text-gray-700 font-mono tabular-nums whitespace-nowrap">
        {currentIndex + 1} / {totalFrames} · {timecode}
      </span>
    </div>
  );
}
