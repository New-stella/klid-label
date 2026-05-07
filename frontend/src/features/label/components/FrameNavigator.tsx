import { Button } from '@/components/common/Button';

interface FrameNavigatorProps {
  currentIndex: number;
  total: number;
  onPrev: () => void;
  onNext: () => void;
}

/**
 * 프레임 N/M 표시 + 이전/다음 버튼.
 */
export function FrameNavigator({ currentIndex, total, onPrev, onNext }: FrameNavigatorProps) {
  const display = total === 0 ? '0/0' : `${currentIndex + 1}/${total}`;
  return (
    <div className="flex items-center gap-2" role="group" aria-label="프레임 이동">
      <Button variant="ghost" size="sm" onClick={onPrev} aria-label="이전 프레임">
        ◀
      </Button>
      <span className="text-sub text-primary" data-testid="frame-counter">
        프레임 {display}
      </span>
      <Button variant="ghost" size="sm" onClick={onNext} aria-label="다음 프레임">
        ▶
      </Button>
    </div>
  );
}
