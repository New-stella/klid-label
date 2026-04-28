import { useCallback, useEffect, useRef, useState } from 'react';

interface CompareSliderProps {
  beforeSrc: string;
  afterSrc: string;
  height?: number;
  label?: { before: string; after: string };
}

export function CompareSlider({
  beforeSrc,
  afterSrc,
  height = 400,
  label = { before: '원본', after: '비식별' },
}: CompareSliderProps) {
  const [position, setPosition] = useState(50); // 0–100 %
  const containerRef = useRef<HTMLDivElement>(null);
  const isDragging = useRef(false);

  const updatePosition = useCallback((clientX: number) => {
    const el = containerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const x = Math.min(Math.max(clientX - rect.left, 0), rect.width);
    setPosition((x / rect.width) * 100);
  }, []);

  // Mouse events
  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      isDragging.current = true;
      updatePosition(e.clientX);
    },
    [updatePosition],
  );

  useEffect(() => {
    const onMouseMove = (e: MouseEvent) => {
      if (!isDragging.current) return;
      updatePosition(e.clientX);
    };
    const onMouseUp = () => {
      isDragging.current = false;
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, [updatePosition]);

  // Touch events
  const handleTouchStart = useCallback(
    (e: React.TouchEvent) => {
      isDragging.current = true;
      const touch = e.touches[0];
      if (touch) updatePosition(touch.clientX);
    },
    [updatePosition],
  );

  useEffect(() => {
    const onTouchMove = (e: TouchEvent) => {
      if (!isDragging.current) return;
      const touch = e.touches[0];
      if (touch) updatePosition(touch.clientX);
    };
    const onTouchEnd = () => {
      isDragging.current = false;
    };
    document.addEventListener('touchmove', onTouchMove);
    document.addEventListener('touchend', onTouchEnd);
    return () => {
      document.removeEventListener('touchmove', onTouchMove);
      document.removeEventListener('touchend', onTouchEnd);
    };
  }, [updatePosition]);

  return (
    <div
      ref={containerRef}
      className="relative w-full select-none overflow-hidden rounded-lg bg-gray-900 cursor-col-resize"
      style={{ height }}
      onMouseDown={handleMouseDown}
      onTouchStart={handleTouchStart}
      aria-label="비교 슬라이더"
    >
      {/* After (right) — full width underneath */}
      <img
        src={afterSrc}
        alt={label.after}
        className="absolute inset-0 w-full h-full object-cover pointer-events-none"
        draggable={false}
      />

      {/* Before (left) — clipped to position% */}
      <div
        className="absolute inset-0 overflow-hidden pointer-events-none"
        style={{ width: `${position}%` }}
      >
        <img
          src={beforeSrc}
          alt={label.before}
          className="absolute inset-0 w-full h-full object-cover"
          style={{ width: containerRef.current?.clientWidth ?? '100%' }}
          draggable={false}
        />
      </div>

      {/* Divider line */}
      <div
        className="absolute top-0 bottom-0 w-1 bg-blue-500 pointer-events-none"
        style={{ left: `calc(${position}% - 2px)` }}
      />

      {/* Drag handle circle */}
      <div
        className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 w-10 h-10 rounded-full bg-blue-500 border-4 border-white shadow-lg flex items-center justify-center pointer-events-none z-10"
        style={{ left: `${position}%` }}
      >
        <svg
          width="18"
          height="18"
          viewBox="0 0 18 18"
          fill="none"
          className="text-white"
        >
          <path
            d="M5 9H13M5 9L7 7M5 9L7 11M13 9L11 7M13 9L11 11"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </div>

      {/* Labels */}
      <span className="absolute top-3 left-3 bg-black/60 text-white text-xs font-semibold px-2.5 py-1 rounded pointer-events-none">
        {label.before}
      </span>
      <span className="absolute top-3 right-3 bg-blue-600/90 text-white text-xs font-semibold px-2.5 py-1 rounded pointer-events-none">
        {label.after}
      </span>
    </div>
  );
}

export default CompareSlider;
