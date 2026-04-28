import { useRef, useEffect } from 'react';
import { useFetch } from '../../api/queries';
import { useLabelStore } from '../../store/labelStore';
import type { FrameMeta } from '../../api/types';

interface Props {
  videoId: string;
  /** Additional frame numbers to highlight with 🚩 (review-time issues) */
  issueFrameNos?: Set<number>;
}

export function FrameStrip({ videoId, issueFrameNos }: Props) {
  const currentFrame = useLabelStore((s) => s.currentFrame);
  const setFrame = useLabelStore((s) => s.setFrame);
  const { data: frames } = useFetch<FrameMeta[]>(`/videos/${videoId}/frames`);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to keep current frame visible
  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const thumb = container.querySelector(`[data-frame="${currentFrame}"]`) as HTMLElement | null;
    if (thumb) {
      thumb.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
    }
  }, [currentFrame]);

  if (!frames) {
    return <div className="h-full bg-gray-800 flex items-center justify-center text-gray-500 text-xs">로드 중...</div>;
  }

  return (
    <div
      ref={scrollRef}
      className="h-full bg-gray-800 flex items-center gap-1 overflow-x-auto px-2 py-1 scrollbar-thin scrollbar-thumb-gray-600"
    >
      {frames.map((f) => (
        <button
          key={f.frameNo}
          data-frame={f.frameNo}
          onClick={() => setFrame(f.frameNo)}
          className={[
            'relative shrink-0 rounded overflow-hidden border-2 transition-all',
            currentFrame === f.frameNo
              ? 'border-blue-500 scale-105'
              : 'border-transparent hover:border-gray-500',
          ].join(' ')}
          style={{ width: 80, height: 45 }}
          aria-label={`프레임 ${f.frameNo}`}
        >
          <img
            src={f.thumbnailUrl}
            alt={`F${f.frameNo}`}
            width={80}
            height={45}
            loading="lazy"
            className="w-full h-full object-cover"
          />
          {/* Frame number */}
          <span className="absolute bottom-0 left-0 right-0 text-center text-white text-[9px] bg-black/50">
            {f.frameNo}
          </span>
          {/* Issue flag (from API meta or from review-time issues) */}
          {(f.hasIssue || issueFrameNos?.has(f.frameNo)) && (
            <span className="absolute top-0 right-0 text-xs leading-none p-0.5">🚩</span>
          )}
        </button>
      ))}
    </div>
  );
}
