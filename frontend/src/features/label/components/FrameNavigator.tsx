// UI-052 FrameNavigator — 프레임 이동 컨트롤.
//
// 사양(SCREEN-005 §캔버스 상단 옵션바 / SCREEN-029 동일 배치): 라벨링 화면은 캔버스 상단
// 옵션바에, 검수 화면은 헤더 바로 아래 상단 바에 둔다 — 두 화면이 같은 컨트롤을 공유한다.
//
// 구성 3부
//  ① 처음/이전/다음/마지막 버튼 — 첫 프레임에서는 처음·이전을, 마지막에서는 다음·마지막을 비활성.
//  ② 현재 프레임 번호 직접 입력(Enter 이동) + 전체 프레임 수 표시.
//     범위(1~frameCount) 밖이거나 빈 값이면 현재 번호로 되돌린다.
//  ③ 위치 슬라이더 — 끄는 동안 이동 요청을 솎아 보내고(THROTTLE_MS), 놓는 순간 대기 중이던
//     마지막 위치를 반드시 반영한다(release flush). 솎아내기만 하고 마무리하지 않으면
//     마지막 위치가 누락된다.
//
// ★세 부분 모두 실제 이동을 직접 수행하지 않고 **단일 콜백 `onRequestGoTo`** 에 위임한다 —
//   진입점마다 미저장 가드를 따로 붙이면 한 곳이 샌다. 미저장 상태에서 슬라이더를 주르륵 끌어도
//   확인은 한 번만 뜨고 목적지는 최종 위치로 갱신된다(호출부 LabelingPage.requestJumpTo).
//
// ★프레임 위치 표시·이동은 이 컨트롤이 단독으로 담당한다 — 라벨링 헤더·검수 헤더 어느 쪽에도
//   두지 않는다(구 LabelHeader `Frame N / 총` 표시 폐지).

import { useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';

import { KRDS_ICON_HIT_AREA } from '@/lib/focusRing';
import { cn } from '@/lib/cn';

export interface FrameNavigatorProps {
  /** 현재 프레임 순번(0부터). 화면에는 1부터의 번호로 표시한다. */
  frameIndex: number;
  /** 형제 프레임 총 개수. 이동 범위와 슬라이더 최대값을 정한다. */
  frameCount: number;
  /**
   * 프레임 이동 요청(0부터). 버튼·번호 입력·슬라이더가 모두 이 하나를 부른다 —
   * 미저장 변경 확인을 이 콜백 안에서 한 번만 처리하기 위해서다.
   */
  onRequestGoTo: (index: number) => void;
  /**
   * 위치 슬라이더 노출 여부. 프레임이 적어 스크럽 이득이 없거나, 다른 표면(하단 프레임
   * 타임라인)이 이미 스크럽을 제공하는 화면에서는 끄고 버튼·번호 입력만 둔다.
   */
  showSlider?: boolean;
  /**
   * 슬라이더가 남은 가로를 전부 채우게 한다(@design SCREEN-019 상단바 `.rv-slider-wrap { flex:1 }`).
   *
   * 기본은 `false` — 고정 폭(w-32)이라 좁은 옵션바에 인라인으로 놓을 수 있다. 검수 상세처럼
   * 슬라이더가 **자기 전용 상단바**를 갖는 화면만 켠다. 켜면 루트가 `w-full` 이 되어 부모의
   * 가로를 다 쓰므로, 가운데 정렬(justify-center) 컨테이너에 넣으면 정렬이 무의미해진다.
   * ⚠ `showSlider=false` 면 아무 효과가 없다.
   */
  sliderFill?: boolean;
  /**
   * 장시간 작업(저장·AI 처리) 진행 중 전체 비활성. 이동이 작업과 교차하면 어느 프레임에
   * 반영될지가 결정되지 않는다.
   */
  disabled?: boolean;
}

/**
 * 슬라이더 드래그 중 이동 요청 솎아내기 간격(ms).
 * 프레임마다 이미지를 새로 불러오므로 매 틱 요청하면 네트워크·캔버스가 과부하된다.
 */
const THROTTLE_MS = 120;

const navButtonClass =
  'flex items-center justify-center rounded text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:cursor-not-allowed disabled:text-gray-300';

export function FrameNavigator({
  frameIndex,
  frameCount,
  onRequestGoTo,
  showSlider = true,
  sliderFill = false,
  disabled = false,
}: FrameNavigatorProps) {
  const maxIndex = Math.max(0, frameCount - 1);
  const empty = frameCount === 0;

  // 번호 입력 중 초안. null 이면 현재 프레임 번호를 그대로 표시한다.
  const [numberDraft, setNumberDraft] = useState<string | null>(null);
  // 드래그 중 썸 위치(로컬 표시값). 놓으면 null 로 되돌려 실제 프레임 위치를 따른다.
  const [dragValue, setDragValue] = useState<number | null>(null);
  const lastEmitAtRef = useRef(0);
  const pendingRef = useRef<number | null>(null);

  const atFirst = empty || frameIndex <= 0;
  const atLast = empty || frameIndex >= maxIndex;

  const emit = (index: number) => {
    lastEmitAtRef.current = Date.now();
    pendingRef.current = null;
    onRequestGoTo(index);
  };

  const go = (index: number) => {
    if (disabled || empty) return;
    emit(Math.min(maxIndex, Math.max(0, index)));
  };

  // ── 번호 직접 입력 ───────────────────────────────────────────────
  const commitNumber = () => {
    const raw = numberDraft;
    setNumberDraft(null); // 성공·실패 모두 초안을 비워 현재 번호로 되돌린다.
    if (raw === null) return;
    const trimmed = raw.trim();
    if (trimmed === '') return; // 빈 값 → 되돌림
    const parsed = Number(trimmed);
    if (!Number.isInteger(parsed)) return; // 숫자 아님 → 되돌림
    if (parsed < 1 || parsed > frameCount) return; // 범위 밖 → 되돌림
    if (parsed - 1 === frameIndex) return; // 같은 프레임 → no-op
    emit(parsed - 1);
  };

  // ── 슬라이더(throttle + release flush) ───────────────────────────
  const handleSliderChange = (next: number) => {
    if (disabled || empty) return;
    setDragValue(next);
    const now = Date.now();
    if (now - lastEmitAtRef.current >= THROTTLE_MS) {
      emit(next);
    } else {
      // 솎아낸 요청은 버리지 않고 대기시켰다가 놓는 순간 반영한다.
      pendingRef.current = next;
    }
  };
  const flushSlider = () => {
    const pending = pendingRef.current;
    setDragValue(null);
    if (pending !== null) emit(pending);
  };

  const sliderValue = dragValue ?? frameIndex;
  const numberValue = numberDraft ?? String(empty ? 0 : frameIndex + 1);

  return (
    <div
      className={cn('flex items-center gap-1', sliderFill && 'w-full')}
      role="group"
      aria-label="프레임 이동"
    >
      <button
        type="button"
        className={cn(navButtonClass, KRDS_ICON_HIT_AREA)}
        onClick={() => go(0)}
        disabled={disabled || atFirst}
        aria-label="처음 프레임"
        title="처음 프레임"
      >
        <ChevronsLeft size={16} />
      </button>
      <button
        type="button"
        className={cn(navButtonClass, KRDS_ICON_HIT_AREA)}
        onClick={() => go(frameIndex - 1)}
        disabled={disabled || atFirst}
        aria-label="이전 프레임"
        title="이전 프레임"
      >
        <ChevronLeft size={16} />
      </button>

      <div className="flex items-center gap-1 px-1 text-body-md text-gray-900 tabular-nums">
        <input
          type="text"
          inputMode="numeric"
          aria-label="프레임 번호"
          data-testid="frame-number-input"
          className="w-12 rounded border border-gray-300 px-1 py-0.5 text-center text-body-md tabular-nums disabled:bg-gray-100 disabled:text-gray-400"
          value={numberValue}
          disabled={disabled || empty}
          onChange={(e) => setNumberDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              commitNumber();
            } else if (e.key === 'Escape') {
              setNumberDraft(null);
            }
          }}
          // 포커스를 잃으면 확정하지 않고 되돌린다 — 실수로 남긴 값이 조용히 이동을 일으키지 않게.
          onBlur={() => setNumberDraft(null)}
        />
        <span data-testid="frame-total-count" className="text-gray-500">
          / {frameCount}
        </span>
      </div>

      <button
        type="button"
        className={cn(navButtonClass, KRDS_ICON_HIT_AREA)}
        onClick={() => go(frameIndex + 1)}
        disabled={disabled || atLast}
        aria-label="다음 프레임"
        title="다음 프레임"
      >
        <ChevronRight size={16} />
      </button>
      <button
        type="button"
        className={cn(navButtonClass, KRDS_ICON_HIT_AREA)}
        onClick={() => go(maxIndex)}
        disabled={disabled || atLast}
        aria-label="마지막 프레임"
        title="마지막 프레임"
      >
        <ChevronsRight size={16} />
      </button>

      {showSlider && (
        <input
          type="range"
          min={0}
          max={maxIndex}
          value={sliderValue}
          disabled={disabled || empty}
          aria-label="프레임 위치 슬라이더"
          data-testid="frame-position-slider"
          className={cn(
            'h-1.5 accent-primary-500 disabled:cursor-not-allowed disabled:opacity-50',
            sliderFill ? 'ml-3 min-w-0 flex-1' : 'ml-1 w-32',
          )}
          onChange={(e) => handleSliderChange(Number(e.target.value))}
          onPointerUp={flushSlider}
          onMouseUp={flushSlider}
          onTouchEnd={flushSlider}
          onKeyUp={flushSlider}
          onBlur={flushSlider}
        />
      )}
    </div>
  );
}
