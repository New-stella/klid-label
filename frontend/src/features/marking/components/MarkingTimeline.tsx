import { cn } from '@/lib/cn';
import type { MarkItem } from '../types';

interface MarkingTimelineProps {
  marks: MarkItem[];
  durationSec: number;
  selectedIndex: number | null;
  onSelect: (index: number) => void;
  className?: string;
}

const NATIVE_FPS = 30;

export function MarkingTimeline({
  marks,
  durationSec,
  selectedIndex,
  onSelect,
  className,
}: MarkingTimelineProps) {
  const totalFrames = durationSec * NATIVE_FPS;
  if (totalFrames <= 0) return null;

  return (
    <div className={cn('relative h-8 bg-gray-100 rounded overflow-hidden', className)}>
      {marks.map((mark, i) => {
        const pct = (mark.frameIndex / totalFrames) * 100;
        return (
          <button
            key={`${mark.frameIndex}-${i}`}
            type="button"
            className={cn(
              'absolute top-0 h-full w-1 transition-colors',
              selectedIndex === i ? 'bg-blue-600' : 'bg-blue-400 hover:bg-blue-500',
            )}
            style={{ left: `${pct}%` }}
            onClick={() => onSelect(i)}
            title={`프레임 ${mark.frameIndex} (${mark.timestamp ?? ''})`}
          />
        );
      })}
    </div>
  );
}
