import { cn } from '@/lib/cn';
import type { MarkItem } from '../types';

interface MarkingTimelineProps {
  marks: MarkItem[];
  durationSec: number;
  /**
   * 영상 실 프레임레이트. 마크 위치(%)는 frameIndex/총프레임 이므로, frameIndex 를 만들 때 쓴 fps 와
   * <b>같은 값</b>이어야 한다. 30 을 하드코딩하면 25fps 영상에서 마크가 실제보다 앞쪽에 찍힌다.
   */
  fps: number;
  selectedIndex: number | null;
  onSelect: (index: number) => void;
  className?: string;
}

export function MarkingTimeline({
  marks,
  durationSec,
  fps,
  selectedIndex,
  onSelect,
  className,
}: MarkingTimelineProps) {
  const totalFrames = durationSec * fps;
  if (totalFrames <= 0) return null;

  return (
    <div className={cn('relative h-8 bg-gray-100 rounded overflow-hidden', className)}>
      {marks.map((mark, i) => {
        const pct = (mark.frameIndex / totalFrames) * 100;
        return (
          <button
            key={mark.frameIndex}
            type="button"
            className={cn(
              'absolute top-0 h-full w-1 transition-colors',
              selectedIndex === i ? 'bg-primary-600' : 'bg-primary-400 hover:bg-primary-500',
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
