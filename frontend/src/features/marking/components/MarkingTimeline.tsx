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

/** 마크 버튼 접근성 이름 — 프레임 번호 + 시각(`F{frame}·mm:ss`). 시각이 없으면 번호만. */
export function markAriaLabel(mark: MarkItem): string {
  return mark.timestamp ? `F${mark.frameIndex}·${mark.timestamp}` : `F${mark.frameIndex}`;
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
            // 마크 버튼에는 시각 텍스트가 없다(폭 4px 막대). 툴팁(title)은 스크린리더에
            // 전달되지 않으므로 접근성 이름은 aria-label 로 따로 준다 — `F{프레임}·mm:ss`.
            aria-label={markAriaLabel(mark)}
            title={`프레임 ${mark.frameIndex} (${mark.timestamp ?? ''})`}
          />
        );
      })}
    </div>
  );
}
